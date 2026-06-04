# Browser-side SQL — `sql` blocks on JS / Node / Wasm

> v1.27 spec.  Extends the v1.26 `sql` fenced-block feature
> (`SPEC.md` § 3.3.1) from JVM-only to the JS, Node, and Wasm backends.
> Source semantics unchanged — same blocks, same binds, same result
> shape; only the runtime changes.

## Goals

1. **Same source, more targets.**  A `.ssc` module that uses `sql`
   blocks against an embedded engine compiles and runs on every
   backend that declares `Lang.Sql` in its `Capabilities.blockLanguages`.
   Today: JVM + interpreter only.  After v1.27: + JS, Node, Wasm.
2. **Provider parity at the source level.**  Source code does not
   name the provider.  The front-matter `databases:` entry's URL
   prefix picks the runtime engine (`sqlite:` → sql.js, `duckdb:` →
   DuckDB-Wasm, `jdbc:…` → JVM only).
3. **Bind safety, unchanged.**  Every `${expr}` is still a single
   positional bind parameter — both sql.js and DuckDB-Wasm consume the
   same `?`-templated string the existing `SqlBindRewriter.rewriteJdbc`
   already produces.  No re-implementation of the bind rule per
   backend.
4. **Async by construction.**  Browser SQL engines load asynchronously
   (WASM init, optional fetch of the engine binary).  The emitted
   contract is honest about this: per-block result type is
   `Promise<SqlResult>`; module-level `await` at top of generated
   source gates everything else.
5. **One JS-side runtime module shared across backends.**  JsGen,
   NodeBackend, and WasmBackend's JS shim all emit calls into the same
   `backend-sql-runtime-js` facade.  Differences between backends are
   confined to dep declarations (npm vs. CDN vs. data URL) and to
   how the entrypoint is wrapped (Node's `top-level await` vs.
   browser's `<script type="module">`).

## Non-goals

- **JDBC drivers in browsers.**  No Postgres / MySQL / Oracle from
  JS.  `jdbc:*` URLs surface a clear capability diagnostic on JS / Node /
  Wasm targets; the message names the JVM target as the only consumer
  and points at `docs/specs/postgres.md` for client-side network access.
- **Synchronous SQL.**  Every browser-side engine is async at init
  and per call (Worker boundary, Promise-based init).  The emitted code
  does not hide this — no fake sync wrappers, no `deasync`.
- **Server-side database access *through* the browser.**  Browsers
  cannot open TCP sockets to a database.  Use HTTP / a `client-*`
  module from a server backend.
- **Cross-runtime data sharing.**  An in-memory SQLite created in a
  JVM run is *not* visible to a JS run of the same module — they are
  separate processes with separate memory.  File-backed SQLite on
  Node is the only durable surface; browser SQL is in-memory only
  unless the user explicitly wires OPFS.
- **Static SQL type-checking.**  Same deferral as v1.26 — possible
  later via engine metadata at parse time, kept off until someone
  asks.
- **`transaction { ... }` block-level helper.**  Deferred from v1.26;
  v1.27 inherits the deferral.  When it lands, it lands once in
  `backend-sql-runtime` + `backend-sql-runtime-js` so both runtimes
  pick it up together.

## Architecture

### Module layout

```
backend-sql-runtime-js/          NEW — pure JS facade
  src/main/scala/scalascript/sql/js/
    SqlRuntimeJs.scala            — façade: execute / connect / Row shape
    Providers.scala               — URL prefix → ProviderId dispatch
    providers/
      SqlJsProvider.scala         — sql.js wiring (init, exec, free)
      DuckDbWasmProvider.scala    — DuckDB-Wasm wiring (worker, query)
    SqlRuntimeJsEmit.scala        — codegen helper: ES module preamble
                                    (engine init, registry, helpers)
  src/main/resources/scalascript/sql/js/
    sql-runtime.mjs               — hand-written JS runtime; emitted
                                    verbatim into bundles by JsGen
  src/test/scala/...
    SqlRuntimeJsTest.scala        — Node-runner integration tests
                                    against real sql.js + duckdb-wasm

backend-js/
  JsCapabilities.scala            — add Lang.Sql to blockLanguages
  JsGen.scala                     — sql-block recognition + emit
                                    (mirrors JvmGen.collectBlocks /
                                    emitSqlRegistry)
backend-node/
  NodeCapabilities.scala          — add Lang.Sql
  NodeBackend.scala               — npm dep declarations (sql.js,
                                    @duckdb/duckdb-wasm) emitted only
                                    when sql blocks are present
backend-wasm/
  WasmCapabilities.scala          — add Lang.Sql
  WasmBackend.scala               — JS shim path mirrors NodeBackend;
                                    Wasm body itself is unaffected
```

### Source-level surface (unchanged)

User-visible syntax is identical to v1.26:

````markdown
---
databases:
  default: { url: "sqlite::memory:" }
  analytics: { url: "duckdb:" }            # in-memory DuckDB
  audit:     { url: "sqlite:./audit.db" }  # Node only — file SQLite
---

# Quickstart

```sql
CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)
```

```sql
INSERT INTO users(id, name) VALUES (${id}, ${name})
```

```sql @db=analytics
SELECT category, SUM(amount) AS total
FROM events
WHERE date >= ${cutoff}
GROUP BY category
```
````

What changes is the *type* the block evaluates to on JS / Node / Wasm
backends — `Promise[SqlResult]` instead of `SqlResult`.  At the IR
level there is no change: `ir.Content.SqlBlock(source, binds, dbName,
span)` is the same node, with the same `binds` produced by the same
`SqlBindRewriter.rewriteJdbc`.

### URL → provider dispatch

Resolved at runtime by `Providers.fromUrl(url)` in
`backend-sql-runtime-js`:

| URL prefix             | Provider        | Target support     | Notes                                                              |
| ---------------------- | --------------- | ------------------ | ------------------------------------------------------------------ |
| `sqlite::memory:`      | `sql.js`        | JS · Node · Wasm   | In-memory; new instance per `connect`.                             |
| `sqlite:<path>`        | `sql.js`        | Node · Electron fallback | Node reads/writes file via `fs/promises`; Electron renderer uses the documented localStorage fallback. Browser without the fallback raises `MissingFs`. |
| `duckdb:`              | `duckdb-wasm`   | JS · Node · Wasm   | In-memory; bundled DuckDB worker.                                  |
| `duckdb:<path>`        | `duckdb-wasm`   | Node only          | File-backed; browser raises `MissingFs`.                           |
| `jdbc:…`               | (JVM only)      | —                  | JS / Node / Wasm: `UnsupportedJdbcUrl` diagnostic at validate-time.|

The validate-time diagnostic is the only build-time enforcement; runtime
`MissingFs` covers the browser-vs-Node split because the parser cannot
tell the two apart from front-matter alone (same backend id for both).
Electron renderer behavior is a special browser-like packaging case; see
[`electron-sql.md`](electron-sql.md).

### Runtime contract — `backend-sql-runtime-js`

```ts
// sql-runtime.mjs (the emitted JS surface, paraphrased in TS for clarity)

export type SqlResult =
  | { kind: 'rows';    rows: Row[] }
  | { kind: 'update';  count: number }

export interface Row {
  (index: number):   unknown          // positional access
  (name:  string):   unknown          // name-indexed, case-insensitive
  toMap():           Record<string, unknown>
}

export interface Connection {
  execute(sql: string, binds: unknown[]): Promise<SqlResult>
  close(): Promise<void>
}

export class ConnectionRegistry {
  constructor(specs: Record<string, DatabaseDecl>,
              envLookup: (name: string) => string | undefined)
  connect(name: string): Promise<Connection>           // cached
  fresh(name: string):   Promise<Connection>           // uncached
  close(): Promise<void>
}

export function execute(conn: Connection,
                        sql: string,
                        binds: unknown[]): Promise<SqlResult>
```

This is exactly the JVM `backend-sql-runtime` surface translated to
JS conventions:

- `SqlResult = Rows | UpdateCount` ↔ JVM `Rows(Seq[Row]) | UpdateCount(Int)`.
- `Row` is name+position indexed with a `.toMap()` materialiser.
- `ConnectionRegistry` is lazy-open + cached, has `fresh` for the
  override path, idempotent `close()`.
- Statement-type detection by leading keyword identical to JVM
  (`isResultSetProducer`: SELECT / WITH / VALUES / SHOW / EXPLAIN /
  PRAGMA — PRAGMA added for SQLite parity).

Bind type matrix mirrors the JVM `Jdbc.bindAll` surface:

| User type (eval'd `${expr}`)            | Provider binding                                  |
| --------------------------------------- | ------------------------------------------------- |
| `null` / `undefined`                    | `NULL`                                            |
| `boolean`                               | `INTEGER 0/1` (SQLite) · `BOOLEAN` (DuckDB)        |
| `number` (integer-valued)               | `INTEGER`                                         |
| `number` (fractional) / `BigDecimal`    | `REAL` (SQLite) · `DOUBLE`/`DECIMAL` (DuckDB)     |
| `bigint`                                | `INTEGER` (SQLite — `Number.MAX_SAFE_INTEGER`     |
|                                         |  ceiling; overflow throws) · `HUGEINT` (DuckDB)   |
| `string`                                | `TEXT`                                            |
| `Uint8Array` / `Buffer`                 | `BLOB`                                            |
| `Date` / `Temporal.Instant`             | ISO-8601 `TEXT` (SQLite) · `TIMESTAMP` (DuckDB)   |
| `Temporal.PlainDate`                    | ISO `TEXT` (SQLite) · `DATE` (DuckDB)             |

`Temporal.*` accepted iff the runtime exposes the proposal; otherwise
`Date` is the only datetime input — same fallback rule as Node 22+
behaviour.

### Connection resolution — `given` override path

The JVM path uses Scala 3 `summonFrom` to prefer a `given Connection`
in scope.  JS has no analogue, so the override path is explicit:

```scalascript
// Authoritative form — module-scoped.
@sscBrowserSqlConnection(name = "default")
val conn = SqlJs.openInMemory()    // Promise[Connection]

// or:
val conn: Future[Connection] = SqlJs.openFile("./audit.db")
```

JsGen looks for `@sscBrowserSqlConnection(name = "<x>")`-annotated
top-level vals at codegen time and emits

```js
_ssc_sql_connections["<x>"] = await <expr>   // bypasses registry
```

before the block calls.  When no annotation matches, the registry
path runs.  This keeps "I have one connection I built by hand" easy
without inventing implicits on JS.

### Phase composition with existing code

- `core/.../ast/Lang.scala` — no change; `Lang.Sql` already exists and
  `isOpaqueExec` already covers it.  All recognition logic from v1.26
  applies unchanged.
- `core/.../transform/SqlBindRewriter` — no change; the `rewriteJdbc`
  output (`?`-templated, ordered binds) is exactly what sql.js and
  DuckDB-Wasm consume.  No need for a third rewriter mode.
- `core/.../ir/Content.scala` — no change; `SqlBlock(source, binds,
  dbName, span)` carries everything needed.
- `validate/CapabilityCheck` — no change to the gating mechanism.
  Once the three new backends declare `Lang.Sql` in `blockLanguages`,
  the existing per-backend diagnostic surface goes away naturally.
  The new build-time diagnostic is `UnsupportedJdbcUrl` (added in
  Phase 6), raised when a `databases:` entry on a JS / Node / Wasm
  target uses `jdbc:*`.
- `schemas/frontmatter.yaml` — no change; URL prefix is data, not
  schema.

## Migration

Existing v1.26 callers see no source-level change.  The non-JVM
diagnostic message and a handful of tests change:

1. **`NodeBackendTest`** ("unknown sql lang" case) — replace with
   "sql block compiles to sql-runtime call" (the diagnostic no longer
   fires).  Add a new case: "jdbc URL on Node target → `UnsupportedJdbcUrl`".
2. **`WasmBackendTest`** — same swap.
3. **`docs/targets.md`** — block-language matrix gains ✅ for `sql` on
   JS / Node / Wasm.  A new subsection notes the URL-prefix dispatch
   table and the JDBC-on-browser exception.
4. **`docs/specs/postgres.md`** — header note: "if you need Postgres from a
   browser-side run, you can't go direct; expose a server endpoint
   that uses `client-postgres` and call it via HTTP."

Existing users of `client-postgres` are unaffected — it's a JVM-only
library that talks to a real network database; v1.27 adds embedded
in-process engines on browser targets, which is a different surface.

## Phases

Each phase is independently shippable per AGENTS.md Rule 3.  Phases
build on each other but each leaves the project compile-clean and
test-green.

### Phase 1 — Spec + milestone (this iteration)

- [x] `docs/specs/browser-sql.md` (this file).
- [x] `MILESTONES.md` v1.27 entry referencing this spec.

### Phase 2 — `backend-sql-runtime-js` module

New sbt module mirroring `backend-sql-runtime`.

- [ ] `build.sbt` entry — name `backendSqlRuntimeJs`, dependsOn `core`
      for shared `SqlResult` shape only (no backend SPI dependency).
- [ ] `sql-runtime.mjs` hand-written JS facade — `ConnectionRegistry`,
      `execute`, `Row`, provider dispatch.  Bundled as a classpath
      resource that JsGen emits verbatim.
- [ ] `SqlJsProvider` — wires the npm `sql.js` package.  Lazy
      WASM-binary load; `Database` per connection.  Bind matrix per
      § "Runtime contract".
- [ ] `DuckDbWasmProvider` — wires the npm `@duckdb/duckdb-wasm`
      package.  Worker-based; uses the JsDelivr bundle locator for
      browser-default WASM load (`getJsDelivrBundles`).  In Node,
      uses the AMD bundle from `node_modules`.
- [ ] `Providers.fromUrl` dispatch table per § "URL → provider dispatch".
- [ ] `SqlRuntimeJsEmit` — codegen helper exposing the preamble JsGen
      prepends to every bundle that contains sql blocks (engine
      imports, helper functions).  Mirrors `JvmGen.emitSqlRegistry`.
- [ ] Tests via `node --test`:
      *10 sql.js cases — in-memory CREATE/INSERT/SELECT, every bind
        type, `Row` shape, statement-type detection, multi-row order,
        case-insensitive name lookup, `toMap`, null binds, BLOB
        round-trip, error path.
      * 6 DuckDB-Wasm cases — same shape, plus a DuckDB-only case
        (window function / CTE) to prove the engine surface.
      * 2 dispatch cases — `Providers.fromUrl` happy/error.

### Phase 3 — JsGen codegen for sql blocks

Mirrors JvmGen Phase 6.C, adapted for async.

- [ ] `JsCapabilities.blockLanguages += Lang.Sql`.
- [ ] `JsGen.collectBlocks` — recognise `Lang.isSql`, increment
      `sqlBlockCounter`, emit a `_sqlBlock_<N>` const initialised by
      `await SqlRuntimeJs.execute(await _ssc_sql_resolve(<dbName>),
      "<?-templated>", [<binds>])`.  First sql block per section also
      emits `const <sectionId> = { sql: _sqlBlock_<N> }` alias.
- [ ] `JsGen.emitSqlRegistry(manifest.databases)` — materialises the
      front-matter map into a single `_ssc_sql_registry` instance at
      bundle top.  When `databases:` is absent, registry is the empty
      stub so `@sscBrowserSqlConnection` standalone still works.
- [ ] `_ssc_sql_resolve(dbName)` helper — checks
      `_ssc_sql_connections` (annotation path) first, falls back to
      `_ssc_sql_registry.connect(dbName ?? "default")`.
- [ ] Bundle preamble — when any sql block exists, JsGen prepends:
      *the `sql-runtime.mjs` source verbatim, and
      * an `await` gate that wraps user-script body in `(async () =>
        { ... })()` (Node ≥ 14.8 and modern browsers support
        top-level `await`; the IIFE wrapper avoids needing it).
- [ ] Tests: `JsGenSqlBlockTest` (~12 cases — no-sql passthrough,
      preamble emission, registry materialisation with/without
      `databases:`, per-block emission with/without binds, sequential
      numbering, section alias dedup, `@db=name` threading,
      `@sscBrowserSqlConnection` annotation path, `${env:NAME}`
      preservation).

### Phase 4 — NodeBackend wiring

- [ ] `NodeCapabilities.blockLanguages += Lang.Sql`.
- [ ] `NodeBackend.emitDeps` — when sql blocks are present, emit
      `package.json` `dependencies` entries:
      *`"sql.js": "^1.10.3"`
      * `"@duckdb/duckdb-wasm": "^1.28.0"`
      Only the providers actually referenced (computed from
      `manifest.databases` URL prefixes) are listed.  Module with
      no sql blocks emits no deps.
- [ ] `NodeBackend` test loop — extend the existing `npm install`
      step in the harness (where present) to install the new deps;
      tests gracefully skip when `npm` is unavailable (`assume(...)`
      idiom from v1.26.2).
- [ ] Tests: `NodeBackendSqlTest` (4 cases — sqlite in-memory
      end-to-end on Node, sqlite file end-to-end on Node,
      duckdb in-memory end-to-end on Node, jdbc URL → validate-time
      diagnostic).
- [ ] **Test swap:** `NodeBackendTest`'s existing `UnknownBlockLanguage("sql")`
      case is replaced with a no-diagnostic case (sql now declared)
      plus a fresh `UnsupportedJdbcUrl` case.

### Phase 5 — WasmBackend wiring

The Wasm backend already emits a JS shim alongside the `.wasm` blob.
SQL execution piggybacks on the shim — the Wasm body itself does not
gain SQL support, but `sql` blocks in the source are routed through
the shim and so "work on the Wasm target."

- [ ] `WasmCapabilities.blockLanguages += Lang.Sql`.
- [ ] `WasmBackend.emitJsShim` — when sql blocks are present, the
      shim includes `sql-runtime.mjs` and the registry preamble,
      identical to `NodeBackend`'s emit.
- [ ] `WasmBackend.emitDeps` — same conditional npm deps as Node.
- [ ] Tests: `WasmBackendSqlTest` (2 cases — sqlite in-memory + duckdb
      in-memory through the JS shim, using Node as the test
      harness).
- [ ] **Test swap:** `WasmBackendTest`'s existing `UnknownBlockLanguage("sql")`
      case is replaced analogously to Phase 4.

### Phase 6 — `UnsupportedJdbcUrl` diagnostic

- [ ] `core/.../validate/CapabilityCheck` — when the target's
      `blockLanguages` includes `Lang.Sql` *and* a `manifest.databases`
      entry's URL starts with `jdbc:`, raise
      `Diagnostic.UnsupportedJdbcUrl(dbName, url, targetId)`.  The
      JVM target keeps using `jdbc:` URLs without diagnostic
      (its capabilities + URL space are the canonical pair).
- [ ] Tests: `CapabilityCheckTest` — 2 cases (jdbc URL on Node →
      diagnostic; sqlite URL on Node → no diagnostic).

### Phase 7 — Examples + conformance

- [ ] `examples/sql-browser-sqlite.ssc` — sqlite in-memory, DDL + DML
      + SELECT with binds.  Tagged `backends: [js, node, wasm]` so the
      conformance harness runs it on each of the three.
- [ ] `examples/sql-browser-duckdb.ssc` — duckdb in-memory analytical
      query (GROUP BY + aggregate), `@db=analytics` routing.
- [ ] `SqlBrowserExamplesTest` — Node-runner harness; inlines the
      example sources verbatim, asserts they parse + run + produce
      the expected output.  Same self-contained pattern as v1.26.2's
      `SqlExamplesTest` for the JVM path.
- [ ] `conformance/sql-browser-basic.ssc` +
      `conformance/expected/sql-browser-basic.txt` +
      `SqlBrowserConformanceCaptureTest` — gated to `backends: [js,
      node, wasm]`, exercises the documented usage shape against all
      three.
- [ ] `docs/targets.md` — block-language matrix updates ✅ for
      `sql` on JS / Node / Wasm.  New v1.27 subsection documents the
      URL-prefix dispatch table + the jdbc-only-on-JVM rule.

## Testing strategy

- **Unit (per-provider).**  `backend-sql-runtime-js` tests run under
  `node --test` against the real sql.js and DuckDB-Wasm npm packages.
  No mocks.
- **Codegen.**  `JsGenSqlBlockTest` is pure-Scala — asserts emitted
  source matches the expected shape, no JS execution.
- **End-to-end (per backend).**  `NodeBackendSqlTest`,
  `WasmBackendSqlTest`, `SqlBrowserExamplesTest`,
  `SqlBrowserConformanceCaptureTest` compile a real module, run it
  under Node, assert observable output.  All gated by
  `assume(hasNode && hasNpm, …)` so CI lanes without the toolchain
  stay green.
- **Browser smoke.**  One Playwright test (in `infra/playwright/`
  if/when the infra lands; otherwise documented manually in the
  example) that loads `examples/sql-browser-sqlite.ssc`'s emitted
  bundle from a static page and asserts the result appears in
  `document.body`.  Smoke only — not in the default test loop.
- **No JVM tests change.**  `backend-sql-runtime`, `JvmGenSqlBlockTest`,
  `JvmGenSqlRuntimeTest`, `SqlExamplesTest`, `SqlBlockInterpreterTest`,
  the `CapabilityCheckTest` cases that target `int` and `jvm` — all
  green throughout, no edits.

## Open questions

These resolve before Phase 2 starts.  Phase 1 lands as-is regardless.

1. **`@sscBrowserSqlConnection` annotation name.**  Long but explicit.
   Alternatives: `@sscSqlConnection(scope = "browser")` or a flat
   `@sscSqlConnection` that wins on both JVM and JS sides (would
   require JvmGen to recognise it too, fold this into the v1.26
   `given`-summoning path).  Picking the latter would unify the override
   story across runtimes at the cost of cross-touching v1.26 code.
2. **DuckDB-Wasm bundle delivery in browsers.**  JsDelivr default
   (zero-config, network dependency) vs. self-host (more setup but
   offline-clean) vs. inline data URL (huge bundle).  Suggest:
   JsDelivr default + a `databases: { ..., bundle: "self-host" }`
   opt-in for users who care.
3. **Top-level `await` vs. IIFE wrapper.**  Top-level `await` requires
   ESM and modern runtimes; IIFE works everywhere but pushes one
   extra indent level into emitted output and hides the await from
   tooling.  Suggest: top-level `await` in `.mjs` output (the
   default), IIFE only as a fallback for the legacy `.js` target if
   we ever add one.
4. **`bigint` overflow on SQLite.**  SQLite stores integers in 8
   bytes (signed), so the precision matches `bigint`, but sql.js
   marshals through `Number` by default.  Two options: (a) configure
   sql.js's `Statement.get({ asBigInt: true })` for INTEGER columns
   and accept that all integers come back as `bigint`; (b) keep
   `Number` and throw on input `bigint > 2^53 - 1`.  Suggest: (a) —
   honest about SQLite's actual type, even if it means user code has
   to `Number(row("id"))` for arithmetic.
