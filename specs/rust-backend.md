# Rust Backend

Status: **draft / planning**. This document is the source of truth for the
`rust` target. It is a specification only — no code lands until each phase
below is checked off and the matching commit is recorded in `CHANGELOG.md`.

## 1. Goals

- **Native, AOT, no JVM.** Produce a Cargo project a user can `cargo build`
  to a single self-contained binary. The output is the artefact `rustc`
  would have written if the user had hand-translated their `.ssc` source.
- **First-class SPI plugin.** `RustBackend extends Backend` lives in
  `runtime/backend/rust/` and is loaded through `ServiceLoader` like
  every other backend — no privileged hook in core, no `match
  backendId { case "rust" => … }`. The CLI gains it via the standard
  bundled-plugin path (`build.sbt` aggregate + `cli.dependsOn`).
- **IR-driven.** Backend consumes `NormalizedModule` (effects already
  lowered, pattern matching desugared to `MatchTree`, tail calls
  annotated). No re-implementation of `analyzeEffects` in Rust idioms.
- **Capability-honest.** Whatever the backend cannot do yet (effects,
  WebSockets, GraphQL, …) is *absent* from `RustCapabilities.features`
  and `CapabilityCheck` rejects programs that need them, with a
  human-readable diagnostic — never a silent miscompile.

## 2. Non-goals (initial scope)

- **Not** a parity replacement for `jvm`/`js`. The fully populated
  intrinsic table (HTTP server, WS, Auth, MCP, Dataset, Payments, …) is
  out of scope for the skeleton phases and only sketched here for the
  parity phase.
- **Not** a JIT or interpretive Rust runtime. We emit Rust source; the
  user's Cargo toolchain does the rest.
- **Not** `no_std`. The standard library is assumed available; targeted
  embedded variants are a separate future spec.
- **Not** an FFI bridge for `extern def` intrinsics into existing JVM
  helpers. Rust intrinsics ship as Rust code inside the generated
  crate (or as crate dependencies in `Cargo.toml`).

## 3. Output shape — Cargo project emit

`ssc emit-rust file.ssc` writes a self-contained Cargo crate under
`./<stem>-rust/` (configurable via `-o <dir>`):

```
<stem>-rust/
├─ Cargo.toml
├─ rust-toolchain.toml         # pins MSRV; section 9
└─ src/
   ├─ main.rs                  # @main entry point (if present in source)
   ├─ lib.rs                   # exported defs; required when no @main
   ├─ runtime/
   │   ├─ mod.rs               # runtime preamble glue (println, _show, …)
   │   ├─ value.rs             # the closed `Value` enum (section 7)
   │   ├─ effect.rs            # Free-monad / CPS runtime (effects phase)
   │   └─ intrinsics/
   │       ├─ mod.rs
   │       ├─ io.rs            # ConsoleIO
   │       ├─ time.rs          # nowMillis, sleep
   │       ├─ http.rs          # std.http (parity phase)
   │       └─ …
   └─ generated/               # per-module IR → Rust transcription
       └─ <module>.rs
```

`Cargo.toml` lists exactly the crates the backend's intrinsics require for
the program at hand — no blanket dependency on `tokio`, `reqwest`,
`serde`, `axum` for a program that calls only `println`. Dependency
selection is driven by `Capabilities.features` actually invoked by the
IR (parallel to how `JsCapabilities.detectCapabilities` walks the module
today).

For programs without an `@main`, the emitted crate is a `lib` and
`Cargo.toml` declares `[lib]` instead of `[[bin]]`; the user wires it
into their own binary.

## 4. SPI surface

```scala
// runtime/backend/rust/src/main/scala/scalascript/codegen/rust/RustBackend.scala
class RustBackend extends Backend with IntrinsicOverlayAwareBackend:
  def id              = "rust"
  def displayName     = "Rust (Cargo crate)"
  def spiVersion      = SpiVersion.Current
  def capabilities    = RustCapabilities
  def intrinsics      = RustIntrinsics
  def acceptedSources = Set("scala", "scalascript", "ssc") // no html/css yet
  override def runtimePreamble: String = ""                // shipped as files, not concatenated
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    compileWithOverlay(module, opts, intrinsics, runtimePreamble)
  def compileWithOverlay(module, opts, effectiveIntrinsics, _): CompileResult =
    RustGen.generate(module, opts.baseDir, effectiveIntrinsics)
```

Registered through `META-INF/services/scalascript.backend.spi.Backend`
(single line: `scalascript.codegen.rust.RustBackend`).

**Output kind.** `CompileResult.Segmented` with one `Segment.Asset`
per emitted file (`Cargo.toml`, `src/**`, `rust-toolchain.toml`). This
mirrors how `backend-wasm` ships multiple artefacts. The CLI
`emit-rust` command writes them under `-o <dir>` (default
`./<stem>-rust/`).

A new `OutputKind.RustSource` enum case is added to
`runtime/backend/spi/src/main/scala/scalascript/backend/spi/OutputKind.scala`
(SPI minor bump; documented in section 12).

## 5. Phases

The phases are cumulative — each one ships an installable plugin that
passes its own slice of `tests/cross/rust/*.ssc` snapshots; later phases
expand the slice. The skeleton phase R.1 is **not** behind a flag — it
is OK for `ssc emit-rust` to fail on programs outside its capabilities,
because `CapabilityCheck` produces the diagnostic *before* `compile()`
runs.

### Phase R.1 — Skeleton

**Goal.** A plugin that emits a buildable Cargo crate for a 4-line
hello-world: `println`, `Int` literals, top-level `def`, `@main`.

Scope:
- New sbt module `backendRust` mirroring `backendWasm` (deps:
  `backendSpi`, `core`).
- `RustBackend`, `RustCapabilities` (`Feature.ConsoleIO` only),
  `RustIntrinsics` (`println`, `print` → `RuntimeCall` to a runtime
  helper).
- `RustGen.generate(module, baseDir, intrinsics): CompileResult.Segmented`
  emitting:
  - `Cargo.toml` with `[package] edition = "2021"` and no deps
  - `src/main.rs` calling into a generated module
  - `src/runtime/mod.rs` shipping `pub fn _show(v: &Value) -> String`,
    `pub fn println(s: impl AsRef<str>) -> ()`, …
  - `src/value.rs` with the closed `Value` enum (Int, Long, Double,
    Bool, Unit, String, plus `Tuple(Vec<Value>)` and the open variants
    needed for R.2)
  - `src/generated/<module>.rs` with the transcribed `def`s
- `EmitRustCmd extends CliCommand` in `tools/cli/src/main/scala/
  scalascript/cli/EmitCommands.scala`, registered in
  `CommandRegistry`. Flags: `-o <dir>`, `--print-only` (writes to
  stdout, skips disk).
- One snapshot test under `tests/cross/rust/hello.ssc` +
  `hello.rust.expected.toml` / `hello.rust.expected.rs`.
- `RustBackend` aggregated into root, `cli.dependsOn(backendRust)`,
  `installBin` carries the JAR into `lib/jars/`.

Out of scope for R.1: case classes, pattern matching, closures,
mutable state, effects, anything in `std.*`.

Acceptance:
- `ssc emit-rust examples/rust/hello.ssc` writes a crate; running
  `cargo run` inside it prints the expected lines.
- `CapabilityCheck` rejects a program that uses `var` against
  the `rust` target with a diagnostic mentioning
  `Feature.MutableState` is unsupported.

### Phase R.2 — Core IR coverage

**Goal.** Transcribe enough of `NormalizedModule` to cover the
non-effectful subset of the conformance suite.

Subset and Rust lowering:

| IR construct                | Rust lowering |
|-----------------------------|----------------|
| `Int`, `Long`, `Double`, `Bool`, `String`, `Unit` | `i64`, `i64`, `f64`, `bool`, `String`, `()` (see §7 for `Value` boxing) |
| `Tuple(es)`                 | `(T0, T1, …)` when the arity is fixed; `Vec<Value>` otherwise |
| `If(c, t, e)`               | `if c { t } else { e }` |
| `Let(name, rhs, body)`      | `{ let name = rhs; body }` |
| `Block(stmts, last)`        | `{ stmts; last }` |
| `Apply(f, args)`            | `f(args…)` |
| `DefDef(name, params, body)`| `fn name(params…) -> R { body }` |
| `Closure(captures, body)`   | `move ||  body` boxed as `Box<dyn Fn(…) -> _>` when stored as a value |
| `Var(name) := rhs`          | `let mut name = rhs;` + later `name = rhs2;` (gated by `Feature.MutableState`) |
| `While(cond, body)`         | `while cond { body }` (gated by `Feature.WhileLoops`) |
| `MatchTree(subject, dt)`    | nested `match` on tagged enums (see §7) |
| `Seq(lang="scala", body)`   | unsupported in `rust` target; diagnostic |

Type inference at emit time: where the IR has no inferred Rust type, the
generator uses the boxed `Value` enum (§7) and lets the user pay the
allocation. Phase R.6 introduces a monomorphisation pass to specialise
hot paths to primitive types.

Capabilities added: `MutableState`, `WhileLoops`, `PatternMatching`,
`ExtensionMethods`, `DefaultParameters`, `ForComprehensions` (lowered
to `flatMap`/`map` calls on `Vec`/`Iterator`).

Acceptance:
- Cross-backend snapshot tests under `tests/cross/rust/r2/*.ssc` pass:
  fib, ackermann, list reduce, case-class destructuring, tuple swap,
  string interpolation via the `s"…"` interpolator.
- All snapshots build with `cargo build --release` and the binary's
  stdout matches the interpreter's output for the same module.

### Phase R.3 — Intrinsics MVP

**Goal.** The minimal `std.*` surface needed for a useful CLI: I/O,
time, FS read, basic JSON.

`Feature` set added: `FileSystem`, `Crypto` (sha256 + base64 only),
`Markup` (string-string xml codec, not XSLT).

Intrinsic registrations:

| QualifiedName                       | IntrinsicImpl           | Rust target                |
|-------------------------------------|--------------------------|-----------------------------|
| `nowMillis`                          | `RuntimeCall(_now_millis)` | `std::time::SystemTime`     |
| `println`, `print`, `Console.*`      | `RuntimeCall(_println / _print)` | runtime helpers      |
| `std.io.readFile(path)`              | `RuntimeCall(_read_file)`  | `std::fs::read_to_string`   |
| `std.io.writeFile(path, body)`       | `RuntimeCall(_write_file)` | `std::fs::write`            |
| `std.crypto.sha256(bytes)`           | `RuntimeCall(_sha256)`     | `sha2` crate (added to `Cargo.toml`) |
| `std.crypto.base64Encode(bytes)`     | `RuntimeCall(_base64_enc)` | `base64` crate              |
| `std.crypto.base64Decode(s)`         | `RuntimeCall(_base64_dec)` | `base64` crate              |
| `std.json.parse(s)`                  | `RuntimeCall(_json_parse)` | `serde_json` crate          |
| `std.json.stringify(v)`              | `RuntimeCall(_json_stringify)` | `serde_json` crate     |

Dep selection: `Cargo.toml` lists `sha2`, `base64`, `serde_json` **only**
when the corresponding intrinsic is reached during the per-module IR walk
that builds `Cargo.toml`. A program with `println` alone gets an empty
`[dependencies]` table.

Acceptance:
- `examples/rust/fs-roundtrip.ssc` reads a file, sha256s it, prints the
  hex digest. Snapshot test compares to interpreter and to a known
  `openssl dgst -sha256` digest.

### Phase R.4 — Effects (algebraic effects + handlers)

**Goal.** Lower `Perform` / `Handle` / `Resume` IR nodes onto a Rust
runtime so user-defined effects work.

Approach. Effects sit at the same spot in the spectrum as on the JVM:
the IR has them as explicit nodes (§6 of `backend-spi.md`), every
backend picks its own representation. For Rust, two viable lowerings
exist:

| Variant | Pros | Cons | Verdict |
|---|---|---|---|
| **Free monad in `Value` enum** | Direct port of `JvmRuntime` / `Interpreter.Computation`; reuses the same shape; works on stable Rust | All effectful values are boxed `Value::Computation(Box<…>)`; no Rust-native ergonomics | **Phase R.4 default** — port what already works |
| **`async fn` + continuations via `Pin<Box<dyn Future>>`** | Native Rust syntax; integrates with `tokio` if the program also uses async I/O | One-shot continuations only; multi-shot effects (rare in current corpus, but in the SPI) need fallback to the Free representation | **R.6 follow-up** when an actual perf case demands it |
| **`unwind`-based / coroutines** | True multi-shot, zero-cost | Requires nightly + unstable feature | **Rejected** — bound to nightly is incompatible with §9 |

So R.4 ships the Free representation. The `runtime/effect.rs` file
provides `pub enum Computation<A> { Pure(A), Effect(Op, Box<dyn FnOnce(Value) -> Computation<A>>) }`
plus a `run_with(handlers: &HandlerStack) -> A` driver. `Perform`
expands to `Computation::Effect(op, |k| k(v))`; `Handle` expands to a
new frame pushed on the handler stack; `Resume` expands to a call into
the captured continuation.

Capabilities added: `AlgebraicEffects`. The interpreter conformance
suite's effect tests gain a `rust` row.

Acceptance:
- The `state`, `reader`, and `nondet` handler examples in
  `examples/effects/*.ssc` build and run with output matching the
  interpreter row.
- Multi-shot effects throw a clearly-labelled runtime panic on R.4
  ("multi-shot continuation not yet supported by rust backend");
  follow-up tracked in `SPRINT.md`.

### Phase R.5 — Runtime parity (std.http server + client)

**Goal.** A program using `std.http.serve` + `route` produces a
working HTTP server in the emitted Cargo binary.

Choice of HTTP library: `hyper` + `tokio`, not `axum`. Reason: `axum`
pulls a deeper transitive tree and constrains the handler shape; we
already produce closure-shaped handlers from the IR and `hyper`'s
`service_fn` accepts them as-is. `Cargo.toml` adds `tokio` (with
`rt-multi-thread`, `macros`), `hyper` (with `server`, `http1`,
`http2`), `http-body-util`, `bytes`.

`Feature.HttpServer` graduates from "absent" to "supported". The
existing `std.http` `extern def`s do not change shape; only the
`RustIntrinsics` table grows. `runtimePreamble` for the rust backend
contributes one shared module `src/runtime/intrinsics/http.rs`
containing the `_http_serve(port, handler)` helper and a thin
`_http_route(method, path, handler)` registration.

Concurrency model. The emitted `main()` constructs a `tokio::runtime::Runtime`
and blocks on a service driver — even for non-`async` user code — so
that `serve(port)` and `route(...)` can wire onto the same executor.
For programs that do **not** call any HTTP/WS intrinsic the `tokio`
runtime is not emitted; this keeps the no-network case dep-free.

Capabilities added: `HttpServer`. (`WebSockets` and `Auth` deferred to
R.6 — they cost roughly as much as HTTP again.)

Acceptance:
- `examples/rust/http-hello.ssc` (one GET route returning JSON) builds,
  starts on `127.0.0.1:0`, an integration test issues `curl
  http://127.0.0.1:$port/` and matches the body to the interpreter
  row.

### Phase R.6 — Parity polish

Catch-all phase tracking remaining work toward feature parity with the
`jvm` target. Each line item is a separate task in `SPRINT.md` when
the time comes; they are listed here in priority order, not
implementation order.

1. **Monomorphisation pass.** A core post-normalisation optimiser
   replaces `Value` boxing with native Rust types on hot paths driven
   by type inference output. Lives in `core`, not the backend — any
   future native target benefits.
2. **WebSockets** (`Feature.WebSockets`): `tokio-tungstenite`.
3. **Auth** (`Feature.Auth`): `argon2` + `jsonwebtoken`.
4. **MCP server/client** (`Feature.McpServer`, `Feature.McpClient`).
5. **Markup / XSLT** (`Feature.Xslt`): `quick-xml` + a hand-rolled
   XSLT 1.0 subset, or skip XSLT and document the gap.
6. **Streams** (`Feature.Streams`): map ScalaScript backpressured
   streams onto `futures::stream::Stream` + `tokio::sync::mpsc`.
7. **Multi-shot continuations.** If a real program needs them, port
   the JVM `Computation` cloning mechanism — `Computation<A>` becomes
   `Clone` for `A: Clone`.
8. **Type classes** (`Feature.TypeClasses`): map type-class dispatch
   onto Rust traits when the IR has enough information; fall back to
   vtable dispatch via the boxed `Value` for higher-rank cases.

R.6 does not commit to all of these — each item ships only when a
real conformance test or example demands it.

## 6. Decisions and rejected alternatives

| Question | Choice | Why | Rejected |
|---|---|---|---|
| **What does the backend output?** | Cargo crate (Cargo.toml + src/) | `cargo build` is the only sane Rust workflow; users get debugger / clippy / cross-compile for free | Single-file `.rs`: makes the user wire the toolchain themselves; LLVM IR / cranelift: a second compiler to maintain |
| **Async runtime?** | `tokio` (added only when needed) | The de-facto Rust async ecosystem; no point inventing a smaller one when intrinsics will pull `hyper` and `tungstenite` anyway | `async-std`: smaller community; `smol`: nice but doesn't help for `hyper` |
| **Effect representation?** | Free monad in a `Value::Computation` variant | Direct port of the JVM/JS lowering; works on stable Rust; multi-shot is straightforward when the variant becomes `Clone` | `async fn` continuations: one-shot only; coroutines: nightly only |
| **Where the runtime preamble lives** | Files under `src/runtime/` in the emitted crate, not a string concatenated by core | Rust does not have a "preamble at top of file" idiom; `mod runtime;` is the idiomatic shape | `runtimePreamble: String`: would force one giant `mod.rs`, breaks orientation |
| **Where intrinsic crates are listed** | Per-module walk decides `[dependencies]` | Same shape as `JsCapabilities.detectCapabilities`; prevents a 30-second `cargo build` for `println("hi")` | Always pull a fat dependency set: build time + binary size + transitive vulnerabilities |
| **Pattern matching lowering** | Reuse `MatchTree` decision tree directly into nested `match` | Same data already lowered for jvm/js; Rust's `match` is exhaustively checked, so we benefit from a second verifier | Re-derive from raw `case`s in the backend: re-implements work already done in core |
| **Tuple representation** | Native Rust tuple when arity ≤ 12, `Value::Tuple(Vec<Value>)` otherwise | 12 is `std::ops::Tuple` derive cutoff in serde; matches user expectations | Always `Vec<Value>`: throws away Rust's tuple ergonomics |
| **MSRV** | Rust 1.74 (released Nov 2023) | Stable `Result::inspect_err`, `Option::is_some_and`, `LazyLock` available — covers the runtime preamble's needs | Any newer: cuts off users on long-term distros without buying us anything yet |

## 7. The `Value` enum (Rust side)

The IR is dynamically typed in places; the emitted runtime needs a boxed
representation for the polymorphic cases (heterogeneous collections,
return values from `Perform`, etc.). The full enum:

```rust
pub enum Value {
    Unit,
    Bool(bool),
    Int(i64),                  // Int + Long both map here
    Double(f64),
    Str(String),
    Tuple(Vec<Value>),
    List(Vec<Value>),
    Map(std::collections::BTreeMap<String, Value>),
    Closure(std::rc::Rc<dyn Fn(Vec<Value>) -> Value>),
    Computation(Box<Computation<Value>>), // for the Effect runtime
}
```

`Rc` not `Arc` until concurrency intrinsics arrive (`tokio` spawning of
closures forces `Arc<dyn Fn(…) + Send + Sync>`); the codegen picks
which one based on whether the closure escapes into an async boundary,
mirroring the way `JvmGen` decides between value class and reference
class.

Monomorphisation (phase R.6) replaces `Value` with the inferred Rust
type on call sites where the IR has full type information; the boxed
shape stays as the fallback path.

## 8. Capability negotiation

R.1 declares:

```scala
val RustCapabilities = Capabilities(
  features = Set(Feature.ConsoleIO),
  outputs  = Set(OutputKind.RustSource),        // new enum case; §12
  options  = Set("optimizationLevel", "emitAssertions", "cargoEdition"),
  spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty                    // no `sql`/`graphql` blocks yet
)
```

Each subsequent phase adds entries:

| Phase | New `Feature`s declared |
|---|---|
| R.2 | `MutableState`, `WhileLoops`, `PatternMatching`, `ExtensionMethods`, `DefaultParameters`, `ForComprehensions`, `StringInterpolators`, `ModuleImports` |
| R.3 | `FileSystem`, `Crypto`, `Markup` |
| R.4 | `AlgebraicEffects` |
| R.5 | `HttpServer` |
| R.6 | `WebSockets`, `Auth`, `McpServer`, `McpClient`, `Streams`, `Xslt`, `TypeClasses`, `TailCallOptimization` |

A program that uses a feature absent from the current phase's
`RustCapabilities` is rejected by `CapabilityCheck` *before*
`RustBackend.compile` runs, with a diagnostic of shape
`Diagnostic.Unsupported(feature, backend = "rust")`. Never a silent
miscompile.

## 9. Toolchain pinning

The emitted crate includes:

```toml
# rust-toolchain.toml
[toolchain]
channel = "1.74.0"
components = ["clippy", "rustfmt"]
```

so a user with multiple toolchains gets reproducible builds. The MSRV
is bumped only by a documented spec edit, never silently.

The backend itself does **not** require `rustc` to be on the host
running `ssc emit-rust` — it only emits text. The acceptance tests use
a CI matrix that runs `cargo build` separately when Rust is
available, mirroring how `backend-wasm` runs `scala-cli` only when
present.

## 10. CLI surface

Three commands, mirroring the JVM target's `compile-jvm` / `run-jvm`
split. `emit-rust` is the low-level "write the crate" primitive;
`build-rust` is the one-shot UX for users who just want a binary;
`run-rust` builds and executes the binary in one step.

```
ssc emit-rust [flags] <file.ssc> [<file.ssc> …]

Flags:
  -o, --output <dir>      Output directory (default ./<stem>-rust/)
      --print-only        Write to stdout instead of disk (one file at a time)
      --release           Emit a Cargo profile.release section pre-tuned
                            (lto = true, codegen-units = 1) — equivalent to
                            BackendOptions.optimizationLevel = 3
      --bin-name <name>   Override the binary name in Cargo.toml
```

```
ssc build-rust [flags] <file.ssc>

Build a single .ssc to a native binary in one step. Implemented as:
  1. emit Cargo crate to a temp dir (same path as `ssc emit-rust`),
  2. spawn `cargo build` (release by default) inside it,
  3. copy the produced binary to <output> (or print path if --keep-crate).

Flags:
  -o, --output <path>     Output binary path (default ./<stem>)
      --debug             Use `cargo build` (debug profile); default is --release
      --keep-crate <dir>  Keep the emitted Cargo crate at <dir> (does not delete
                            the temp dir). Useful for inspecting generated code.
      --target <triple>   Forwarded to cargo as `--target <triple>` for cross-compile
      --offline           Forwarded to cargo as `--offline`
      --verbose           Stream cargo's stdout/stderr through; default is quiet
```

`build-rust` requires `cargo` on `PATH`. If absent, both `build-rust`
and `run-rust` print one short, plain-language message and exit 1.
**They do nothing else** — no fallback advice, no partial emit, no
"try this other command" suggestion. The exact wording is fixed so the
output is predictable to script and to read:

```
ssc build-rust: cargo not found on PATH.

Rust is not installed on this machine. Install it and re-run the
command. Two ways to install:

  • Homebrew (macOS):   brew install rust
  • Official installer: https://www.rust-lang.org/tools/install
```

(The first line uses the command name actually invoked, so `run-rust`
shows `ssc run-rust: cargo not found on PATH.` instead.) No trailing
hints, no environment dump, no link to `emit-rust`. The
`rust-toolchain.toml` written by `emit-rust` (§9) is honoured by the
spawned cargo when it *is* present, so the binary is reproducible
across hosts with `rustup` installed.

```
ssc run-rust [flags] <file.ssc> [-- <program args>…]

Build a single .ssc to a native binary and execute it immediately.
Equivalent to `cargo run` for an emitted crate; the analogue of
`run-jvm` (which compiles via JvmGen and runs via scala-cli). Pipeline:
emit crate → `cargo build` (release by default) → spawn the produced
binary with the user's arguments → forward its exit code.

Flags:
      --debug             Use `cargo build` (debug profile); default is --release
      --target <triple>   Forwarded to cargo as `--target <triple>`
      --offline           Forwarded to cargo as `--offline`
      --verbose           Stream cargo's stdout/stderr through; default is quiet
                            for the build step; the binary's stdout/stderr are
                            always inherited
  --                      Everything after `--` is passed as argv to the
                            built binary (same convention as `cargo run --`)
```

`run-rust` shares the cargo-presence check and the shutdown-hook
process-tree kill with `build-rust` (Ctrl-C tears down both the cargo
process and the running binary cleanly). Unlike `build-rust`, the
emitted crate and binary live in a temp dir that is deleted after the
binary exits — there is no `-o` flag.

Example — the question that prompted this section:

```bash
$ ssc build-rust hello.ssc            # produces ./hello
$ ./hello
Hello from Rust

$ ssc run-rust hello.ssc              # equivalent end-to-end run
Hello from Rust
$ ssc run-rust greeter.ssc -- Sergiy  # forwarded argv after `--`
Hello, Sergiy
```

Help text registered through `EmitRustCmd.summary` /
`BuildRustCmd.summary` / `RunRustCmd.summary` (and the corresponding
`details`). All three listed under category "Emit & transpile"
alongside `emit-js` / `emit-wasm` / `run-jvm`.
`CommandRegistryTest.scala` updated to include `"emit-rust"`,
`"build-rust"`, and `"run-rust"` in the expected set.

## 11. Tests

- **Cross-backend snapshots.** Each phase adds rows to
  `conformance/RuntimeBench` and `tests/cross/rust/`. Snapshot files:
  `<case>.rust.expected.toml` (Cargo.toml content), `<case>.rust.expected.rs`
  (main.rs content). Diff failures point at exact lines.
- **Build smoke.** Phase R.1 adds `tests/rust-build-smoke.sh` that runs
  `cargo build --offline` on each emitted crate when `which cargo`
  succeeds; skipped otherwise. CI lane: separate job
  `cross-rust-build` running on a Rust-installed image.
- **Capability gating tests.** For each phase, one negative test that
  feeds the next phase's feature against the current capabilities
  and asserts `Diagnostic.Unsupported` mentions the right feature
  name and the `rust` backend id.

## 12. SPI changes

R.1 introduces exactly one SPI addition:

- `OutputKind.RustSource` enum case in
  `runtime/backend/spi/src/main/scala/scalascript/backend/spi/OutputKind.scala`.
  SPI minor bump (`SpiVersion`). All existing backends are
  source-compatible because `OutputKind` is consumed via pattern
  matches with `case _ =>` fallback.

No other SPI extension is required by the skeleton. Later phases may
need:

- A `BackendOptions.extra` convention for `"cargoEdition"`,
  `"cargoLto"`, `"cargoCodegenUnits"` — these stay free-form per
  §4.4 of `backend-spi.md`, no SPI change.
- (R.5) A `runtimeAssets: Map[String, Array[Byte]]` hook on
  `Backend` if it turns out we need to ship pre-built `.so` /
  `.dylib` helpers; deferred until the parity polish phase actually
  demands it.

## 13. Open questions

- **Cross-compilation.** Does `ssc emit-rust` accept a `--target
  <triple>` flag and bake it into `Cargo.toml`, or is that the user's
  job? Tentatively leave it to the user (Cargo already supports
  `--target` at build time without project changes).
- **No-std variant.** A `--no-std` switch that drops the runtime
  preamble to `core::*` would unlock embedded targets. Out of scope
  until a real user asks; documented here so we do not paint
  ourselves into a corner that prevents it.
- **Wasm via Rust.** Cargo has first-class `wasm32-unknown-unknown`
  support. After R.5, `ssc emit-rust --target wasm32-unknown-unknown`
  would give us a third path to WASM (in addition to the existing
  `backend-wasm` via Scala.js). Decide at that point whether that
  duplication is useful or confusing.
- **Sharing intrinsics with the JVM target.** Several JVM intrinsics
  are essentially platform-agnostic algorithms (`sha256`,
  `base64Encode`, `_show`, `_eq`). Worth factoring a
  language-neutral "intrinsic recipe" representation that core can
  retarget — explicitly **deferred** to phase R.6 because doing it
  before R.3 would block on a refactor that itself needs the rust
  target as its second data point.

## 14. Glossary cross-refs

This spec uses terminology defined in:

- `specs/backend-spi.md` — Backend trait, NormalizedModule, Capability
  negotiation, IntrinsicImpl variants, runtimePreamble.
- `specs/spi-intrinsics-design.md` — IntrinsicImpl ADT, `extern def`,
  HostCallback semantics (relevant if a future R.7 wires Rust as an
  out-of-process backend).
- `specs/wasm-backend.md` — sibling backend that emits a multi-file
  artefact via `CompileResult.Segmented`; the closest existing
  precedent for the Cargo-crate emit shape used here.
