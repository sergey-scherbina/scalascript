# ScalaScript — Project Summary

> **One language, one source, five backends.** Write Scala 3 inside Markdown
> and run it on a tree-walking interpreter, transpile it to JavaScript, compile
> it to the JVM, emit a native Rust binary, or target WebAssembly — from the
> *same* `.ssc` file.

ScalaScript (`.ssc`) is a hybrid **Markdown + Scala 3** language. A document is
literate: prose, YAML front-matter, GFM tables, and fenced code blocks are all
part of the program. `scalascript` blocks are the dialect (algebraic effects,
handlers, content helpers, TCO); `scala` blocks are plain Scala 3; `rust` blocks
pass through verbatim to the Rust backend. The result runs identically across
backends, so you pick the runtime at *build* time, not authoring time.

```scalascript
@main def run(): Unit =
  val xs = List(1, 2, 3, 4).map(_ * 2).filter(_ > 4)
  println(s"Result: $xs")          // Result: List(6, 8)
```

```bash
ssc run hello.ssc          # interpreter
ssc compile hello.ssc      # → JS (Node)
ssc build hello.ssc        # → JVM
ssc build-rust hello.ssc   # → native binary (no JVM, no Node)
ssc emit-wasm hello.ssc    # → WebAssembly
```

---

## Backends

| Backend | Command | What it produces |
|---------|---------|------------------|
| **Interpreter** | `ssc run` | Tree-walking evaluator with a hot-spot register VM + run-time JIT |
| **JavaScript** | `ssc compile`, `ssc emit-spa`, `ssc emit-wc` | Node modules, browser SPAs, Web Components |
| **JVM** | `ssc build` | scala-cli–compiled JVM programs |
| **Rust** | `ssc build-rust`, `ssc run-rust` | Self-contained native binary via Cargo |
| **WebAssembly** | `ssc emit-wasm` | `.wasm` via Scala.js |
| **Apache Spark** | `ssc run` (Spark plugin) | Spark 4 jobs — `Dataset[T]`, `sql` blocks, Structured Streaming |

Cross-backend fidelity is enforced by a property-based differential test that
runs generated programs on the interpreter, JS, and JVM and asserts
byte-identical output.

---

## What it can do

- **Core language** — case classes, enums/ADTs, pattern matching, for-comprehensions,
  typeclasses + `given`/`summon`, higher-kinded types, extension methods,
  tail-call optimisation (self + mutual, no `@tailrec`), optics (lenses, prisms,
  traversals), and bitwise operators on `Int`.
- **Real collection semantics** — `Array` is mutable with reference identity,
  `LazyList` is genuinely lazy (infinite streams, `#::` deferral), `Vector` is a
  distinct O(log₃₂ n) indexed type — not a single uniform list.
- **Algebraic effects** — `effect`/`handle`/`resume`, multi-shot continuations,
  typed effect rows (`A ! Logger`), compile-time unhandled-effect errors, and
  standard effects (Logger, Random, Clock, Reader, NonDet). Effects run on the
  interpreter, JVM, JS, **and WebAssembly**.
- **Web & HTTP** — route DSL, middleware, auth (sessions, OAuth2, WebAuthn,
  TOTP), WebSockets, MCP (Model Context Protocol) servers and clients, x402
  micropayments.
- **Frontend** — one UI source compiled to React / Vue 3 / Solid / a custom
  runtime, Web Components with SSR + hydration, a declarative `std/ui` widget
  toolkit, and Markdown-driven content controls.
- **Native reactive web server (Rust)** — `serve(view, port)` compiles a
  `std/ui` view to a `tokio` + `hyper` binary with server-side rendering, a
  reactive signal store, computed-signal live recompute, Server-Sent Events, and
  a direct WebSocket signal endpoint.
- **Data** — typed SQL across backends, Apache Spark (Datasets, UDFs, Delta Lake,
  MLlib), graph/RDF storage, typed JSON/row/object codecs.
- **Metaprogramming** — `inline`, `derives`, `compiletime.*`, and restricted
  quoted macros that run on the interpreter, JVM, **and** JS — including
  compile-time constant folding and cross-module expansion.
- **Tooling** — `ssc new` scaffolding, plugin system (`.sscpkg`), sbt
  integration with cross-backend builds, REPL debugger, `ssc fmt`, config
  system, GraalVM native image, and a conformance suite.

---

## Recent highlights

The latest development wave focused on the Rust backend, WebAssembly, performance,
and metaprogramming:

- **Reactive web toolkit on Rust** — `serve(view, port)` now emits a real
  reactive server: server-side rendering, a thread-safe signal store,
  `computedSignal` that recomputes live when a dependency changes, typed signal
  reads (`Signal[Int]` parses back to `i64`), Server-Sent Events push, and a
  bidirectional WebSocket signal endpoint. Verified end-to-end with `curl` and a
  raw WebSocket client — no browser required.
- **Algebraic effects on WebAssembly** — arithmetic, collection HOFs, multi-shot
  resume, and cross-module effects all compile and run via a pure-Scala effect
  runtime with no preamble bloat. `@wasm` extern FFI and cross-module
  inlining/macros are wired.
- **Metaprogramming v2** — quoted macros run on the JVM and JS backends (not just
  the interpreter), with `Expr.asValue match` constant folding, cross-module
  expansion, `Mirror.Of[T]` conformance, and custom `derives`.
- **Performance** — Application Class-Data Sharing cuts `ssc` cold start ~51%
  (378 → 182 ms) and peak RSS ~32%; a `foldLeft` VM-compile pass and a
  typeclass-fold memo speed up combinator-heavy folds; `real-workload-perf`
  harnesses cover cold-start, steady-state server RSS (no leak; settles ~195 MB),
  and GC under load.
- **sbt cross-build** — `sscBackends` emits JVM/JS/Rust/Wasm artifacts from one
  source, with Phase 5 dependency resolution.

See [`CHANGELOG.md`](../CHANGELOG.md) for the full, dated history.

---

## Where to go next

| Document | What's in it |
|----------|--------------|
| [README](../README.md) | Capability catalogue + quick start |
| [User Guide](user-guide.md) | Installation, CLI, language, HTTP, effects, Spark, frontend, deploy |
| [Tutorial](tutorial.md) | Build a Todo API; build a Spark ETL pipeline |
| [Rust backend](rust-backend.md) | `emit-rust` / `build-rust` / `run-rust` + the reactive web toolkit |
| [Target backends](targets.md) | Interpreter · JS · JVM · Rust — capabilities & tradeoffs |
| [Performance](performance.md) | JIT, benchmarks, cold-start, memory |
| [Architecture](architecture.md) | Compiler pipeline, module structure, backend SPI |
