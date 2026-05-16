# Backend SPI — Plan

Status: **draft / planning**. This document is the source of truth for the
backend abstraction refactor. It is not yet implemented; until each phase
below is checked off, the code does not match this design.

## 1. Goals

- **One contract, many backends.** `core` knows only the SPI; concrete
  backends (`jvm`, `js`, `scalajs`, `interpreter`) live behind it as
  plugins on equal footing with third-party backends.
- **No core recompilation to add a backend.** Drop a JAR (or register a
  subprocess) and the new target is available.
- **In-process and out-of-process backends, both first-class.** Anything
  on the JVM via `ServiceLoader`; anything else (native, Rust, .NET
  Roslyn, WASM toolchain in Rust, …) via a subprocess protocol over
  serialised IR.
- **Capability negotiation is mandatory.** A backend declares what it
  supports; the compiler refuses to invoke it on programs that use
  unsupported features and gives a human-readable error.
- **Effect lowering is core's job.** Backends receive a normalised IR
  where algebraic effects are already expressed as ordinary IR nodes.
  Each new backend should not have to re-derive `analyzeEffects`.

## 2. Non-goals

- We are **not** writing the WASM / native / .NET backends here. We are
  designing the contract so they can be written later without changing
  `core`.
- We are **not** locking down a *binary* IR format. The wire format is
  JSON for now; we can add a compact binary later behind the same SPI.
- We are **not** introducing a plugin manifest registry, plugin
  download, or sandboxing. Plugins are local files the user installs.

## 3. Current state (baseline)

Four code generators, no shared abstraction:

| Component             | Entry point                                                   | Output         |
|-----------------------|---------------------------------------------------------------|----------------|
| `JvmGen`              | `JvmGen.generate(module, baseDir): String`                    | Scala source   |
| `JsGen`               | `JsGen.generate(module, baseDir): String`                     | JS source      |
| `JsGen` (segmented)   | `JsGen.generateSegmented(module, baseDir): List[Segment]`     | Segments       |
| `ScalaJsBackend`      | top-level `object`, compiles scala blocks via `scala-cli --js`| JS source      |
| `Interpreter`         | `Interpreter(ps).run(module)`                                 | side effects   |

Call sites: `cli/Main.scala` (5 commands wired directly to each gen),
`server/WebServer.scala` (calls `Interpreter.run`), `server/Routes.scala`
(uses `Interpreter.invoke` for handlers).

Effect handling: each of `JvmGen` and `JsGen` has its own
`analyzeEffects` / `emitEffectsRuntime` (~250 lines each, duplicated).
`Interpreter` has its own `Computation` Free-monad in
`interpreter/Value.scala`.

The `Backend` trait and `BackendRegistry` shown in
`docs/architecture.md` §4 are aspirational — they do not exist yet. This
plan delivers them.

## 4. Target architecture

### 4.1 sbt module layout

```
scalascript-backend-spi/         # public, semver-stable
  └─ src/main/scala/scalascript/backend/spi/
       ├─ Backend.scala          # the trait
       ├─ Ir.scala               # IR types (re-export / mirror of ast+typer)
       ├─ Capabilities.scala
       ├─ CompileResult.scala
       ├─ BackendOptions.scala
       └─ Diagnostic.scala

scalascript-ir/                  # IR + JSON codecs (also depended on by core and SPI)
  └─ ...                         # see §5

scalascript-core/                # parser, typer, effect lowering, registry, loader
  └─ depends on: ir, spi

scalascript-backend-jvm/         # plugin
scalascript-backend-js/          # plugin
scalascript-backend-scalajs/     # plugin
scalascript-backend-interpreter/ # plugin
  └─ each depends on: spi (+ ir for in-proc plugins)

scalascript-cli/                 # CLI; bundles core + the 4 standard plugins
  └─ depends on: core, all 4 plugins
```

`backend-spi` is the only artifact a third-party plugin author depends
on. It is small (a few hundred lines) and changes only via versioned
releases.

### 4.2 The `Backend` trait

```scala
// in scalascript-backend-spi
package scalascript.backend.spi

trait Backend:
  def id: String                              // e.g. "jvm", "js", "wasm"
  def displayName: String                     // human-friendly
  def spiVersion: String                      // semver of SPI this plugin was built against
  def capabilities: Capabilities

  /** One-shot compilation. */
  def compile(ir: ir.NormalizedModule, opts: BackendOptions): CompileResult

trait InteractiveBackend extends Backend:
  /** Stateful session: used by REPL/interpreter and incremental web server. */
  def openSession(opts: BackendOptions): Session

trait Session extends AutoCloseable:
  def feed(block: ir.NormalizedBlock): CompileResult
  def invokeHandler(handlerRef: ir.SymbolRef, args: List[ir.Value]): ir.Value
  def close(): Unit
```

### 4.3 `CompileResult`

A closed sum-type so callers can dispatch without `instanceOf`:

```scala
enum CompileResult:
  case TextOutput(code: String, language: String, sources: List[SourceArtifact])
  case Segmented(segments: List[Segment])
  case BinaryOutput(bytes: Array[Byte], mime: String, files: List[FileArtifact])
  case Executed(stdout: String, stderr: String, exit: Int)
  case Failed(diagnostics: List[Diagnostic])

enum Segment:
  case Code(language: String, code: String)
  case Source(language: String, source: String)
  case Asset(name: String, bytes: Array[Byte], mime: String)
```

`Segmented` subsumes today's `JsGen.Segment`; `BinaryOutput` exists for
WASM/native; `Executed` is what the interpreter and `compile-and-run`
modes return.

### 4.4 `BackendOptions`

A simple typed record. Backends ignore options they don't understand —
core validates known options against `capabilities.options` at startup.

```scala
case class BackendOptions(
  baseDir: Option[Path],
  outputDir: Option[Path],
  optimizationLevel: Int,       // 0..3
  emitSourceMaps: Boolean,
  emitAssertions: Boolean,
  target: Option[String],       // sub-target hint (e.g. "node", "browser", "wasi")
  extra: Map[String, String]    // free-form, backend-specific
)
```

## 5. IR

### 5.1 Stages

```
source ─► raw AST ─► TypedModule ─► NormalizedModule ─► Backend
                                  ↑
                          effect lowering, desugaring,
                          method dispatch resolution
```

`NormalizedModule` is what a backend sees. Everything backends used to
re-derive lives here as explicit nodes.

### 5.2 What ends up in `NormalizedModule`

- Sections flattened into a definition list (heading scopes already
  collapsed into qualified names).
- All effect operations are explicit nodes: `Perform(op, args)`,
  `Handle(body, cases, return)`, `Resume(k, v)`.
- `effectful` is a property on `DefDef`, computed in core, not a string
  marker.
- Pattern matching is desugared to a decision tree (`MatchTree`), not
  raw cases — every backend would otherwise re-implement match
  compilation.
- Tail calls are annotated (`TailCall(...)`) so backends can choose
  between direct goto, trampoline, or native TCO.
- Imports already resolved to absolute symbol references.

### 5.3 Serialisation

`scalascript-ir` provides JSON codecs (upickle, already a dep).

Round-trip is part of CI:
`Parser → Typer → Normalize → toJson → fromJson` must equal the
in-memory `NormalizedModule`. This is the contract between in-proc and
out-of-proc plugins — break it and out-of-proc backends silently
diverge.

JSON schema is committed at `schemas/ir.json` and versioned. Each
`NormalizedModule` carries `irSchemaVersion`.

## 6. Effect normalisation (the big migration)

Current state: `JvmGen.analyzeEffects` (~55 lines), `JsGen.analyzeEffects`
(~70 lines), `Interpreter.Computation` (full Free monad).

Target state: one pass in core (`core/transform/EffectLowering.scala`)
producing explicit `Perform`/`Handle`/`Resume` IR nodes. Backends just
emit them; how they emit them is up to the platform:

- `jvm` plugin: continues to emit Scala source with a `Computation` Free
  monad runtime (same as today, but driven by IR not analysis).
- `js` plugin: same Free monad, JS flavour.
- `wasm` plugin (future): can use the WASM stack-switching proposal or
  CPS-transform `Perform` nodes itself. Either way, the IR already tells
  it where the effect boundaries are.
- `interpreter` plugin: maps directly onto its existing `Computation`.

Effects that need explicit IR support today (from current code +
`InterpreterTest.scala`):

- algebraic effects with `effect E:` declarations, `handle`, `perform`,
  `resume` — all user-defined; the language has no built-in effects
- mutable state via `var` — already an IR node, no work needed
- exceptions: today simulated via `Fail` user-effect; will remain so
- async/await: **not implemented**, out of scope for this refactor

## 7. Capabilities

Backends MUST declare their capabilities. Core MUST validate before
invoking `compile`.

```scala
case class Capabilities(
  features:  Set[Feature],
  outputs:   Set[OutputKind],
  options:   Set[String],     // names of supported BackendOptions.extra keys
  spiRange:  SpiVersionRange  // compatible SPI versions
)

enum Feature:
  case AlgebraicEffects
  case MutableState
  case PatternMatching
  case TypeClasses
  case ExtensionMethods
  case DefaultParameters
  case ForComprehensions
  case WhileLoops
  case TailCallOptimization
  case StringInterpolators       // s"", html"", css"", md""
  case ModuleImports
  case HttpRouting               // route(), serve(), Request/Response
  case ConsoleIO

enum OutputKind:
  case ScalaSource, JavaScriptSource, CssSource, HtmlSource
  case JvmBytecode, WasmBytecode, NativeBinary, DotNetIL
  case ExecutionResult
```

The list above mirrors what `core` actually exercises today; new
features get added here at the same time they enter the language.

Validation pass: `core/validate/CapabilityCheck.scala` walks the
`NormalizedModule`, tags features it sees, intersects with
`backend.capabilities.features`, and produces `Diagnostic.Unsupported`
entries for misses.

## 8. Discovery and loading

### 8.1 In-process (JVM plugins)

- `META-INF/services/scalascript.backend.spi.Backend` lists the plugin's
  `Backend` implementation classes. Standard `ServiceLoader`.
- Default discovery paths:
  - The bundled CLI classpath (the four standard plugins).
  - `~/.scalascript/plugins/*.jar`
  - `$SCALASCRIPT_PLUGIN_PATH` (colon-separated dirs).
  - CLI flags: `--plugin <jar>`, `--plugin-dir <dir>`.
- Each plugin JAR is loaded in its own `URLClassLoader` whose parent is
  the SPI classloader only — plugins cannot see each other's
  dependencies (avoids version conflicts).

### 8.2 Out-of-process plugins

- Plugin distribution is a directory with a `plugin.yaml`:

  ```yaml
  id: wasm
  displayName: WebAssembly (via wasm-tools)
  spiVersion: "1.0"
  protocol: jsonrpc-stdio    # only protocol initially
  executable: ./bin/sscbackend-wasm
  args: ["--quiet"]
  capabilities:
    features: [PatternMatching, MutableState, TailCallOptimization]
    outputs:  [WasmBytecode]
  ```

- Core spawns the executable, exchanges newline-delimited JSON-RPC
  messages over stdin/stdout. Three methods initially:
  - `describe()` → `Capabilities` (sanity-checked against `plugin.yaml`)
  - `compile(ir, opts)` → `CompileResult`
  - `shutdown()`
- stderr is forwarded to the user as diagnostic logs.
- Discovery paths mirror the in-process case but look for
  `plugin.yaml` files instead of JARs.

A subprocess backend whose `executable` is a JVM jar is allowed (we just
spawn `java -jar ...`); that gives plugin authors a path to total
isolation from core's classpath if they need it.

## 9. SPI versioning

- SPI uses **semver**.
- Each plugin declares `spiVersion`. The loader rejects plugins outside
  the compatible range and logs a clear message.
- Breaking SPI changes go in **major** versions only.
- `scalascript-backend-spi` is published independently of `core` (same
  group / different artifact / independent version).

## 10. Migration plan (8 phases)

Each phase is one PR. Tests stay green at every step.

### Phase 1 — Module split & SPI skeleton  (M, ~1d)

- Add sbt subprojects: `backend-spi`, `ir`, `core`, `cli`, four backend
  modules.
- Move existing sources into `core` (no semantic changes).
- Define `Backend` trait, `CompileResult`, `Capabilities`,
  `BackendOptions` in `backend-spi` (stubs are fine).
- `cli/Main.scala` still calls concrete backends directly — registry
  comes in Phase 5. Output: nothing visible changes, but the build is
  modular.

### Phase 2 — IR + JSON codec  (M, ~1d)

- Introduce `NormalizedModule` types under `ir/`.
- Initially, `NormalizedModule = TypedModule` — i.e. just a renamed
  alias plus a normalisation step that's a no-op.
- Write upickle codecs; add round-trip property test
  (`module → json → module` equals original).
- Commit JSON schema under `schemas/ir.json`.

### Phase 3 — Effect lowering  (L, ~1–2d)

- Move `analyzeEffects` out of `JvmGen` and `JsGen` into
  `core/transform/EffectLowering.scala`.
- Add `Perform`/`Handle`/`Resume` IR nodes.
- `JvmGen` and `JsGen` lose ~250 lines each; instead they pattern-match
  on these nodes.
- All existing `InterpreterTest` cases involving effects must still
  pass.

### Phase 4 — Capabilities + validation  (S, ~0.5d)

- Define `Feature` / `OutputKind` enums as in §7.
- Tag the four existing backends with their actual capability sets.
- Add `CapabilityCheck` walker; wire it into the pipeline before
  `compile()`.
- New error type `Diagnostic.Unsupported(feature, backend)`.

### Phase 5 — Convert existing backends to plugins  (M, ~1d)

- Each of `JvmGen`, `JsGen`, `ScalaJsBackend`, `Interpreter` gets a
  `Backend` implementation in its own subproject with a
  `META-INF/services` file.
- `Interpreter` implements `InteractiveBackend` (its `Session` wraps
  the existing `run` / `invoke` API; the existing `runSnippet` becomes
  `feed`).
- `core` adds `BackendRegistry` (ServiceLoader-based discovery).
- `cli/Main.scala`, `server/WebServer.scala`, `server/Routes.scala`
  switch to looking up backends by id from the registry. No direct
  imports of `JvmGen`/`JsGen`/`Interpreter` outside their plugin
  modules.

### Phase 6 — Out-of-process loader  (M, ~1d)

- `core/plugin/Subprocess.scala`: JSON-RPC over stdio.
- `plugin.yaml` parser and loader.
- An in-tree smoke-test plugin (a 50-line Scala-CLI script that returns
  a canned `TextOutput`) so we exercise the protocol in CI without
  needing a real external backend.
- Document the protocol under `docs/backend-spi-protocol.md`.

### Phase 7 — CLI ergonomics  (S–M, ~0.5–1d)

- New flags: `--plugin <path>`, `--plugin-dir <dir>`, `--target <id>`,
  `--list-backends`, `--describe-backend <id>`.
- `Main.scala` becomes a thin dispatcher: parse args → pick backend by
  id → load IR → call `backend.compile`.
- WebServer accepts a `--backend` flag for which engine to render with
  (defaults to `interpreter` for backward compatibility).

### Phase 8 — Docs & sample plugin  (S, ~0.5d)

- Rewrite `docs/architecture.md` §4 to match reality (the current text
  is aspirational).
- New `docs/writing-a-backend.md`: walks through implementing a "no-op"
  backend in <100 lines.
- New `examples/plugins/hello-backend/` — a buildable third-party
  plugin used as the worked example.

**Total:** ~6–9 working days.

## 11. What this enables (future)

For each future backend, the work fits this template:

1. Implement `Backend` against `backend-spi` (in any JVM language) OR
   build a subprocess executable that speaks the JSON-RPC protocol.
2. Map `NormalizedModule` nodes to target code:
   - effects → platform mechanism (stack-switching / CPS / threads)
   - `MatchTree` → target's switch / jump table
   - `TailCall` → target's native TCO / trampoline
3. Declare capabilities truthfully.
4. Ship the JAR / executable. No `core` change needed.

Specifically:

- **WASM**: most likely subprocess plugin invoking `wasm-tools` /
  `binaryen`. Effects via stack-switching (or CPS as fallback).
- **Native**: subprocess plugin invoking Scala Native or a small Rust
  toolchain.
- **.NET**: in-process JVM plugin emitting C# source + invoking
  `dotnet` CLI; or subprocess plugin in pure C# using Roslyn.

## 12. Open questions

- **IR stability vs. language evolution.** Adding a language feature
  often needs a new IR node. We bump the IR schema version every time.
  Out-of-process backends that don't support the new version are still
  allowed to run on older programs but refuse newer ones — is that the
  policy we want, or do we require all plugins to update lockstep?
- **Plugin trust.** A loaded JAR runs with full JVM permissions. Do we
  want any sandboxing for third-party plugins, or document this and
  rely on the user knowing what they install? (Initial recommendation:
  document; revisit if we ever ship a plugin marketplace.)
- **Where do `html"…"`, `css"…"`, `md"…"` interpolators live?**
  Today they're in `JvmGen` and `JsGen` separately. Are they a
  language feature (lowered in core) or a backend-supplied builtin?
  Lean: lower in core, every backend gets them free.
- **`ScalaJsBackend` role.** Today it's a hybrid — it compiles
  *embedded* scala blocks for a JS module. Probably becomes a helper
  inside the `js` plugin, not its own backend. Confirm during Phase 5.

## 13. Definition of done

- [ ] All eight phases merged.
- [ ] `cli/Main.scala`, `server/*.scala` contain zero direct references
      to `JvmGen`, `JsGen`, `ScalaJsBackend`, or `Interpreter`.
- [ ] Adding a new backend requires no edits to `core` or `cli`.
- [ ] Existing test suite (`InterpreterTest`) green.
- [ ] New tests:
  - IR JSON round-trip
  - capability check rejects program with unsupported feature
  - subprocess plugin smoke test
  - plugin discovery from `--plugin-dir`
- [ ] `docs/architecture.md` rewritten to match.
