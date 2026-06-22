# JS runtime fragments as `.mjs` resources (not Scala string constants)

Status: **done for JS** (2026-06-22) — all 18 `JsRuntime*` string fragments migrated to `.mjs`
resources (optics pilot + the remaining 17). Closes polyglot-libraries §3 #8 for the JS backend.

## Problem

The JS backend ships its runtime support code (the helper functions the emitted JS calls) as
**large Scala string constants** — `JsRuntimeOptics`, `JsRuntimePart2a/2b`, `JsRuntimeSignals`,
`JsRuntimeFs`, `JsRuntimeMcp`, `JsRuntimeGraphql`, `JsRuntimeAsyncA/B`, … (a dozen-plus). Each is a
`val X: String = """ …real JavaScript… """` inside a `.scala` file.

Downsides:
- **No tooling on the embedded JS** — no editor syntax highlighting, no `prettier`/`eslint`, no
  `node --check`, no `tsc --checkJs`. A JS syntax error is only caught at runtime / by a node smoke
  test, late.
- Two languages tangled in one file; awkward to read and diff.

This is the cleanup flagged in [`polyglot-libraries.md`](polyglot-libraries.md) §3 item 8 ("move
codegen feature runtime strings into plugin `runtimePreamble`"). It also aligns with Task B: these
runtime fragments literally become publishable host libraries (e.g. `@scalascript/optics`), so
storing them as real `.js` is a step toward that.

## Approach (A — chosen)

Move each fragment into a real `.mjs` resource file and load it through a tiny cached loader,
**keeping the public `val X: String` API unchanged** so no call site changes:

```
runtime/backend/js/src/main/resources/scalascript/js-runtime/optics.mjs   ← real JavaScript
```
```scala
// JsRuntimeOptics.scala
val JsRuntimeOptics: String = JsRuntimeResource.load("optics.mjs")
```

`JsRuntimeResource.load(name)` reads `/scalascript/js-runtime/<name>` from the classpath as UTF-8,
caches it in a `ConcurrentHashMap`, and fails loudly if the resource is absent.

### Hard invariant: byte-identical emitted JS

The migration must **not change a single byte** of generated output. `JsGen` appends
`JsRuntimeOptics` verbatim and depends on its exact shape (e.g. it special-cases the trailing
newline; `JsLibPackager` does `stripPrefix("\n")` on the leading newline). Therefore the `.mjs`
content is the *verbatim* triple-quoted body — including the **leading newline** and trailing
newline — extracted mechanically, not retyped. Guards: the existing optics conformance + the
`JsLibPackager` golden + node-ESM smoke must stay green unchanged.

### Why optics is the pilot

Smallest self-contained fragment (depends only on `_None`/`_Some`/`_isMap`), and it already has a
golden `.d.ts` + an `assume(node)` ESM runtime smoke (`JsLibPackagerTest`) — so the "string →
resource" refactor is immediately and end-to-end verifiable at minimal risk. Once proven, the same
`JsRuntimeResource.load(...)` mechanically absorbs the other fragments.

## Rejected alternatives

- **B — build-time source generator** (sbt `sourceGenerators` bakes `.mjs` files into the Scala
  constants at compile time). Keeps the string compiled-in (no runtime resource IO) but adds build
  complexity; only worth it under a no-runtime-IO constraint (e.g. GraalVM native-image). Not needed
  for the JVM/CLI packaging used today.
- **C — runtime as a standalone TS project** compiled separately. The endgame (real type-checking,
  matches Task B), but pulling a TS toolchain into the build is a larger commitment; defer.

## Validation

- `node --check` on the raw `.mjs` resource (assume-gated test) — catches JS syntax errors at test
  time, the concrete win.
- `JsLibPackagerTest` (golden `.d.ts` + node ESM smoke) unchanged green ⇒ behavior + byte-identity.
- Optics conformance (`tests/conformance/optics-*.ssc`) unchanged green.

`tsc --checkJs` / `eslint` as a CI gate is a follow-up (the runtime JS is intentionally loose;
gating on `tsc` would need JSDoc annotations first).

## Migration result (2026-06-22)

The JS runtime fragments now load from `.mjs` resources via `JsRuntimeResource.load`, with
**content-meaningful names** (no cryptic `partNx` / version numbers). Always-included core:
`core` (base — output/`Console`/helpers), `core-dispatch` (`_Char`/`_dispatch`/`_show`/`_tupleConcat`/
Free-monad/fs), `core-collections` (seq + sync fallbacks). Capability-gated: `http-server` (`HtmlDsl`),
`jwt-auth` (`Jwt`), `ws-server` (`WsServer`), `optics`, `signals`, `indexeddb`, `dataset`, `payment`,
`mcp`, `mcpbrowser`, `graphql`, `effects` (the v1.4 built-in effects — Logger/Random/Clock/Env), plus
`async` and `browserpatch`. Each `.mjs` body is **byte-identical** to the prior Scala literal
(mechanically extracted + `diff`-verified vs `git HEAD`), so the emitted JS is unchanged. The aggregator
`JsRuntime` in `JsGen.scala` is computed and stays as-is.

Note on the "lintable" win: self-contained capability fragments (optics, signals, payment, graphql,
mcp, …) are independently `node --check`-able; the always-included core (`core`, `core-dispatch`,
`core-collections`) is split for concatenation/interleaving (gated `optics` sits between `core-dispatch`
and `core-collections`), so per-file parse may span a boundary — those still gain editor highlighting +
clean diffs, and lint as the concatenated `JsRuntime` blob.

### Consolidating size-only splits + meaningful names (2026-06-22)

The `.mjs` move removed the JVM 65 535-byte string-constant cap that originally forced several fragments
to be split. Keeping **logical** boundaries:
- The one genuinely size-driven split was consolidated: `asynca.mjs` + `asyncb.mjs` → **`async.mjs`**
  (`JsRuntimeAsync` now `load("async.mjs")`; the `JsRuntimeAsyncA`/`B` vals + files are gone).
- The remaining `partNx` fragments turned out to be real boundaries, so instead of merging they were
  **renamed** to reflect their content/capability: `part1a`→`core`, `part1b`→`http-server` (`HtmlDsl`),
  `part1c`→`jwt-auth` (`Jwt`), `part1d`→`ws-server` (`WsServer`), `part2a`→`core-dispatch`,
  `part2b`→`core-collections`, `v14effects`→`effects`. `http-server`/`jwt-auth`/`ws-server` stay
  separate to preserve per-capability tree-shaking; `core-dispatch`/`core-collections` stay separate
  because gated `optics` is concatenated between them. Content unchanged ⇒ emitted JS byte-identical.

## Follow-ups

- **JVM done (2026-06-22):** `JvmGenRuntimeSources` (3656→61 lines) → `resources/scalascript/jvm-runtime/`
  via `JvmRuntimeResource.load` (verbatim `|`-body + loader `.stripMargin` ⇒ byte-identical; `effectsRuntime`
  kept as 7 `+`-concatenated chunks for its former size-split). Rust (`RustRuntimeTemplates`) remains — 17
  stripMargin strings migratable + 1 `s"""` interpolated to leave; same loader shape.
- Optional `tsc --checkJs`/`eslint` CI gate on the self-contained `.mjs` (needs JSDoc first).
