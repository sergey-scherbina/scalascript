# Target Backends

ScalaScript compiles to multiple target platforms. This document describes the backend translation model and target-specific considerations.

## Design Principle

> **One source, many targets.** Source semantics are target-independent. Backends translate; they do not reinterpret.

The compiler pipeline produces a **Typed IR** (intermediate representation) that captures the full semantics of the program. Each backend translates this IR to target-specific code.

### Numeric widths are a conformance requirement, not a backend choice

> **NORMATIVE — read this before writing or reviewing a backend.** `Int` is **64-bit**
> two's-complement with wrapping arithmetic. `2147483647 + 1` is `2147483648` on **every** target.
> **A backend that truncates `Int` to 32 bits is NON-CONFORMING** — it is not "a backend with a
> different `Int`", it is a backend that reinterprets the source, which the Design Principle above
> forbids.

The name is a trap: ssc `Int` is **not** a 32-bit integer, despite reading like one. Mapping it onto
the host's natural 32-bit `int` is the single easiest way to write a non-conforming backend, and it
fails **silently** — the program prints a wrong number and exits 0. `Int` and `Long` are the same
type (both 64-bit); so are `Float` and `Double` (both IEEE-754 binary64).

The normative table — every spelling's width, its ABI declaration, the required per-host carriers,
and the current per-backend conformance status — is **[`specs/numeric-widths.md`](../specs/numeric-widths.md)**.
Consult it rather than inferring a width from a type name.

**Currently non-conforming (known, declared, expiring):** the **v1 codegen** lanes
(`ssc-tools run-jvm`, `ssc-tools emit-js`) map ssc `Int` onto a 32-bit host integer and truncate.
They are slated for deletion with the v1/scalameta hybrid tier and are **not** to be fixed; do not
model a new backend on them, and do not ship integer-sensitive work on them. See
`specs/numeric-widths.md` §4.

## Target Matrix

| Backend | Status | Runtime | Use Case |
|---------|--------|---------|----------|
| JVM (Scala-CLI) | **M1** | JVM 17+ | Server, CLI, scripting |
| JavaScript | **M3** | Browser/Node | Web apps, universal |
| WASM | Future | WASM runtime | Portable binary |
| Rust | **R.1** | Native (Cargo crate → binary) | CLI, single-file native tools |
| Native | Future | OS native | Performance-critical |

## JVM Backend (Primary)

### Overview

The JVM backend generates Scala 3 code that runs via Scala-CLI. This provides:
- Mature, battle-tested runtime
- Excellent tooling and IDE support
- Java ecosystem interoperability
- Fast iteration via Scala-CLI scripting mode

### Translation Model

```text
.ssc source → Typed IR → Scala 3 source → JVM bytecode
```

**Module mapping:**
- ScalaScript module → Scala object
- Heading scopes → Nested objects
- Functions → Methods
- Types → Scala types

**Example:**

ScalaScript:
````markdown
---
name: math-utils
---

# MathUtils

```scala
def square(x: Int): Int = x * x
```
````

Generated Scala:
```scala
object MathUtils:
  def square(x: Int): Int = x * x
```

### Interop

JVM backend supports Java/Scala interop via facade declarations:

```scala
@js.native // Actually @jvm.native for JVM
trait JavaList[A]:
  def add(elem: A): Boolean
  def get(index: Int): A
  def size(): Int
```

### Configuration

In front-matter:
```yaml
scala:
  version: 3.3.0
  jvmTarget: "17"
  options:
    - "-Xmax-inlines:64"
```

## JavaScript Backend

### Overview

The JS backend generates ES modules that run in browsers or Node.js. Key features:
- Zero-install distribution (just serve the JS)
- DOM interop via facade types
- Async/Promise integration

### Translation Model

```text
.ssc source → Typed IR → JavaScript (ESM)
```

**Type mapping:**

| ScalaScript | JavaScript |
|-------------|------------|
| `Int` | `number` |
| `Double` | `number` |
| `String` | `string` |
| `Boolean` | `boolean` |
| `List[A]` | `Array` |
| `Map[K,V]` | `Map` |
| `Option[A]` | `A \| null` |
| `Unit` | `undefined` |

### Module Output

ScalaScript:
````markdown
---
name: greeter
---

# Greeter

```scala
def greet(name: String): String = s"Hello, $name!"
```
````

Generated JavaScript:
```javascript
// greeter.js
export function greet(name) {
  return `Hello, ${name}!`;
}
```

### Browser Interop

DOM access via facade types:

```scala
@js.native
object document:
  def getElementById(id: String): Element
  def createElement(tag: String): Element

@js.native
trait Element:
  var innerHTML: String
  def appendChild(child: Element): Unit
```

### Configuration

```yaml
js:
  moduleType: esm      # esm | commonjs | umd
  target: es2020       # es5 | es2015 | es2020 | esnext
```

## WASM Backend (Future)

### Goals

- Portable binary format
- Near-native performance
- Language-agnostic runtime

### Approach

Two possible strategies:

1. **Direct compilation**: SSC → WASM binary
2. **Via existing toolchain**: SSC → Scala → Scala Native → WASM

Strategy TBD based on ecosystem maturity.

## Rust Backend

### Overview

The Rust backend emits a self-contained **Cargo crate** that
`cargo build` compiles to a native binary.  See the full guide in
[`rust-backend.md`](rust-backend.md); the spec lives in
[`../specs/rust-backend.md`](../specs/rust-backend.md).

Goals:
- AOT, no JVM, no JS runtime
- One source file → one Cargo crate → one binary, in one command
- Capability-honest: features the backend doesn't yet support are
  rejected before `compile` runs, never silently miscompiled

### Translation Model

```text
.ssc source → Typed IR → ast.Module (Denormalize) → Cargo crate (RustGen)
                                                     │
                                                     └─ cargo build → native binary
```

### Output shape

`ssc emit-rust hello.ssc -o /tmp/hello-rust` writes:

```
hello-rust/
├─ Cargo.toml
└─ src/
   ├─ main.rs                  (or src/lib.rs when @main is absent)
   ├─ value.rs                 (closed Value enum)
   ├─ runtime/mod.rs           (_show / _print / _println helpers)
   └─ generated/
      ├─ mod.rs                (pub mod <crate>)
      └─ <crate>.rs            (one `pub fn` per top-level def +
                                 rust fence blocks appended verbatim)
```

### CLI commands

| Command | Purpose |
|---|---|
| `ssc emit-rust <file>` | Write the Cargo crate to `-o <dir>` (default `./<stem>-rust/`). |
| `ssc build-rust <file>` | Emit + `cargo build --release` + copy the binary to `-o <path>` (default `./<stem>`). |
| `ssc run-rust <file> [-- args…]` | Emit + build + run the binary with argv after `--`. |

All three require `cargo` on `PATH`.  When it is missing, the command
prints exactly the wording from `specs/rust-backend.md §10` (with the
Homebrew + rust-lang.org/tools/install hints) and exits 1 — nothing
else.

### `rust` fence blocks

Markdown sources targeting the rust backend can mix `scalascript`
and `rust` blocks in the same `.ssc`:

````markdown
```scalascript
@main def run(): Unit = println("Hello via rust block")
```

```rust
pub fn util() -> i64 { 7 }
```
````

The rust source is appended into `src/generated/<crate>.rs` verbatim
(under a `// ── rust block <N> ──` separator) so `cargo build` sees
both ScalaScript-derived `pub fn`s and the user's hand-written Rust
items as ordinary crate-level definitions.

### R.1 capability surface

Phase R.1 is intentionally narrow — the hello-world shape is what's
accepted; anything outside it is rejected by `CapabilityCheck` before
`compile` runs.

Supported features:
- `ConsoleIO` (`println`, `print`, `Console.println`, `Console.print`)
- `StringInterpolators` (the `s"…"` form, required by every string
  literal in the SS pipeline)
- `ModuleImports`

Rejected (with `Diagnostic.Unsupported` naming the feature + backend):
- `MutableState`, `WhileLoops`, `PatternMatching`, `TypeClasses`,
  `AlgebraicEffects`, `HttpServer`, `WebSockets`, … — see
  `specs/rust-backend.md §8` for the roadmap (R.2–R.6).

### Roadmap

Phases R.2 through R.6 widen the capability set: core IR coverage
(R.2), intrinsics MVP (R.3 — fs, sha256, json), algebraic effects
(R.4), HTTP server parity (R.5), and a polish pass (R.6 —
monomorphisation, WebSockets, Auth, MCP, Streams, type classes).
Full spec: [`../specs/rust-backend.md`](../specs/rust-backend.md).

## Swift Backend (ScalaScript 2 AppCore)

The v2 Swift backend consumes checked CoreIR and writes a deterministic Swift
Package containing `Sources/AppCore/SscRuntime.swift`,
`Sources/AppCore/GeneratedProgram.swift`, and a thin executable target. It is a
separate implementation from the v1 SwiftUI/JvmGen compatibility generator.

| Command | Purpose |
|---|---|
| `ssc emit-swift --target macos|ios [-o dir] <file>` | Write the package without building it. |
| `ssc run-swift <file> [-- args…]` | Build and run AppCore with host SwiftPM on macOS. |
| `ssc build --target macos|ios <file>` | Use the v2 generator by default; `--v1` explicitly selects compatibility. |
| `ssc run --target macos <file>` | Generate and run the v2 domain executable. |

macOS declares a 13.0 deployment floor; iOS declares 16.0. The current
domain-only slice intentionally rejects iOS launch/package/publish before the
portable NativeUi application target exists, naming that boundary and never
falling back to v1. Decimal/BigInt, Money, algebraic effects, tail recursion,
and executable argv are implemented by the target-owned Swift runtime.

## Conformance

### Semantic Guarantees

All backends must provide identical observable behavior for:
- Pure computations
- Data structure operations
- Pattern matching
- Type checking

### Allowed Divergence

Backends may differ in:
- Performance characteristics
- Memory layout
- FFI/interop mechanisms
- Platform-specific APIs

### Conformance Suite

The conformance suite (M4) defines tests that all backends must pass:

```text
conformance/
├── arithmetic/
│   ├── int-ops.ssc
│   ├── double-ops.ssc
│   └── expected.json
├── collections/
│   ├── list-ops.ssc
│   └── expected.json
└── ...
```

Each test specifies expected output; all backends must produce identical results.

## Backend Selection

### Automatic

Based on context:
- Running via Scala-CLI → JVM
- Browser `<script>` import → JS
- Explicit target flag → specified backend

### Manual

```bash
ssc run --target jvm myfile.ssc       # compile via JvmGen + run (no artifacts)
ssc build --target jvm myfile.ssc     # compile to distributable JAR in dist/
ssc run-js myfile.ssc                 # compile via JsGen + run with node
ssc build --target js myfile.ssc      # compile to JS bundle in dist/
```

Or in front-matter:
```yaml
targets:
  - jvm
  - js
```

## Block Language Support

ScalaScript code blocks carry a language tag on the fence
(```` ```scalascript ````, ```` ```sql ````, etc.) that determines
how each backend processes the block.  The table below tracks
per-backend support for every block language currently in `Lang.scala`.

| Block lang        | Interpreter | JVM | JS / Scala.js | Node | WASM | Spark |
|-------------------|:-----------:|:---:|:-------------:|:----:|:----:|:-----:|
| `scalascript` / `ssc` | ✅          | ✅  | ✅            | ✅   | ✅   | ✅    |
| `scala`           | ✅          | ✅  | ✅            | ✅   | ✅   | ✅    |
| `html`            | ✅ (`<id>.html` String binding) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `css`             | ✅ (`<id>.css` String binding)  | ✅ | ✅ | ✅ | ✅ | ✅ |
| `javascript` / `js` | ✅ (String value) | ✅ | ✅ (spliced into output) | ✅ | ✅ | ✅ |
| `node.js` / `node` | ❌ (`UnknownBlockLanguage`) | ❌ | ❌ | ✅ (linked into bundle) | ❌ | ❌ |
| `sql`             | ✅ (JDBC via `backend-sql-runtime`) | ✅ (emits `SqlRuntime.execute`) | ✅ (sql.js / DuckDB-Wasm — v1.27) | ✅ (sql.js / DuckDB-Wasm + `package.json` — v1.27) | ✅ (JS shim via `SqlRuntimeJsEmit` — v1.27) | ✅ (Spark SQL) |

`scala` fences run through the same ScalaScript engine as `scalascript` fences,
in document order. **Today a `scala` fence is not compiled by real Scala 3 /
Scala.js** — its output is byte-identical to a `scalascript` fence on every lane,
so `Int` is 64-bit ([`../specs/numeric-widths.md`](../specs/numeric-widths.md); the
width follows the BACKEND, not the fence tag). The `runScalaFences:` /
`scalaFences:` front-matter keys are **reserved and currently a no-op** (no
compiler/interpreter code reads them; `scala` fences already run by default).
Real Scala.js/scala-cli compilation of `scala` fences — carrying Scala's own
32-bit `Int` — is a future, separately-widthed capability
([`../specs/w5-int-width-findings.md`](../specs/w5-int-width-findings.md)).

When a backend doesn't claim a block language, `CapabilityCheck`
emits a `Diagnostic.UnknownBlockLanguage(<lang>)` so the user gets a
precise diagnostic at compile time instead of silently dropping the
block.  The mechanism is wired generically via `Lang.isOpaqueExec` —
adding a new opaque-exec block lang only requires updating
`ast.Lang.scala` plus the producing backend's
`Capabilities.blockLanguages`; every other backend automatically
starts rejecting it.

### v1.26 — `sql` block specifics

The `sql` block is parameterised executable (SPEC § 3.3.1):
every `${expr}` is rewritten to a JDBC `?` bind parameter by the
shared `transform/SqlBindRewriter`, with no string-substitution
escape hatch.  Two backends consume the same rewriter:

- **Interpreter / JVM** — `SqlBindRewriter.rewriteJdbc` → JDBC
    `PreparedStatement` via `scalascript.sql.SqlRuntime.execute`.
    H2 + SQLite drivers ship bundled; Postgres / MySQL / Oracle /
    MSSQL come in via `dep:` front-matter imports.
- **Spark** — `SqlBindRewriter.rewriteSparkSql` → named
    `:bind<N>` placeholders consumed by `spark.sql(text, args)`.

Connection resolution on the JVM/Interpreter path: front-matter
`databases:` map → `ConnectionRegistry`; `given Connection` in scope
overrides the registry.  See `specs/postgres.md` for the
`client-postgres` library that complements this with a Future-based
async API for end-user scalascript code.

### v1.27 — `sql` on JS-family targets (JS / Node / WASM)

The v1.27 follow-up brings the same `sql` block to the JS / Node /
WASM backends.  Source semantics are unchanged: the
`SqlBindRewriter.rewriteJdbc` output (?-templated SQL + ordered
bind list) is consumed by `backend-sql-runtime-js`'s JS facade,
which dispatches to one of two embedded engines based on URL prefix.
Full spec: [`specs/browser-sql.md`](browser-sql.md).

| URL prefix             | Provider        | Notes                                                                  |
| ---------------------- | --------------- | ---------------------------------------------------------------------- |
| `sqlite::memory:`      | sql.js          | Fresh in-memory SQLite per `connect`.                                  |
| `sqlite:<path>`        | sql.js          | File-backed on Node; Electron renderer uses localStorage fallback.      |
| `duckdb:`              | DuckDB-Wasm     | In-memory; worker-based.                                               |
| `duckdb:<path>`        | DuckDB-Wasm     | File-backed (Node only).                                               |
| `jdbc:…`               | (JVM only)      | Build-time `Diagnostic.UnsupportedJdbcUrl` on JS-family targets.       |

Differences from the JVM/Interpreter path:

- **Electron renderer caveat.**  `sqlite:<path>` in Electron does not write a
    real file from renderer code; see [`electron-sql.md`](electron-sql.md).

- **Async-by-construction.**  Browser SQL engines load asynchronously
    (WASM init + worker spin-up).  Every `sql` block compiles to
    `await SqlRuntimeJs.execute(...)`; the bundle's user body is
    wrapped in an async IIFE so the await is legal in classic-script
    and ESM contexts alike.
- **`<sectionId>.sql` shape.**  Same alias convention as
    JvmGen — the first `sql` block per section binds the result to
    `<sectionId>.sql`.  Result shape:
    `{ kind: 'rows', rows: Row[] }` for SELECTs,
    `{ kind: 'update', count: number }` for DML/DDL.  Rows are
    callable: `row("col")` (case-insensitive name), `row(0)`
    (position), `row.toMap()`.
- **NodeBackend `package.json` artifact.**  When a module has any
    sql block, NodeBackend ships a companion `package.json` alongside
    `main.cjs`.  Deps are gated on actually-referenced providers
    (`sql.js`, `@duckdb/duckdb-wasm`, `web-worker`); modules that
    use only one provider don't carry the other's npm dep.
- **`jdbc:` URL gating.**  `Diagnostic.UnsupportedJdbcUrl(db, url,
    backend)` fires at validate time for JS-family targets carrying a
    `jdbc:` URL — the diagnostic message points the user at the JVM
    target or at a different URL scheme.  JVM-family targets are
    unaffected.

Examples in [`examples/sql-browser-sqlite.ssc`](../examples/sql-browser-sqlite.ssc)
and [`examples/sql-browser-duckdb.ssc`](../examples/sql-browser-duckdb.ssc);
end-to-end pinning in `SqlBrowserExamplesTest` and
`SqlBrowserConformanceCaptureTest`.

The Wasm target ships the JS runtime + per-module registry as
`Segment.Asset`s alongside the `.wasm` blob (`sql-runtime.mjs`,
`sql-registry.mjs`, `package.json`); the Node target ships
`package.json` next to `main.cjs`.  Full v1.27 spec:
[`specs/browser-sql.md`](browser-sql.md).

## Adding New Backends

To add a new backend:

1. Implement `Backend` trait with `translate(ir: TypedIR): TargetCode`
2. Define type mappings for all core types
3. Implement standard library stubs
4. Pass full conformance suite
5. Document target-specific considerations

```scala
trait Backend:
  def name: String
  def translate(module: TypedModule): TargetOutput
  def typeMapping: Map[SSCType, TargetType]
```
