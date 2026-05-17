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

The SPI exposes **two orthogonal extension axes**:

- **Source language** (`SourceLanguage` trait — §9): a dialect the
  compiler can read inside fence blocks. Parses, type-checks, and
  lowers to IR. Examples: `scala`, `sql`, `python`, `wat`, `html`.
- **Target output** (`Backend` trait — §4.3): consumes IR and emits
  an artifact. Examples: Scala source, JavaScript, WASM bytecode, an
  SPA bundle, in-process execution.

A plugin can register as one role, the other, or both. The two
registries are populated independently (see §12 Discovery). They meet
only in `core`, which feeds IR from source-language compilers into the
chosen target backend.

**Both roles support in-process and out-of-process plugins.** JVM
plugins register via `ServiceLoader` (§12.1); plugins in any other
language register a subprocess that speaks the wire protocol (§12.2).
The SPI does not privilege one direction over the other — a Python
frontend can run as a real `python3` subprocess just as a WASM
backend runs as a `wasm-tools` subprocess.

How the bundled plugin set maps onto the new shape. The **core**
itself handles only Markdown (host syntax) and `scalascript`/`ssc`
(host embedded language); everything else — including `html`/`css`
fence blocks, the `html"…"`/`css"…"` interpolators, and `scala`
blocks — lives in bundled plugins that use the same SPI as any
third-party plugin:

| Plugin            | Role             | Provides                                                    |
|-------------------|------------------|-------------------------------------------------------------|
| `scala-source`    | SourceLanguage   | `scala` fence blocks (scalameta parser + verbatim embedding)|
| `html`            | SourceLanguage   | `html` blocks, `html"…"` interpolator, `Html` type + DSL    |
| `css`             | SourceLanguage   | `css` blocks, `css"…"` interpolator, `Css` type             |
| `jvm`             | Backend          | Scala-source target; accepts `scala`/`html`/`css`           |
| `js`              | Backend          | JS-source target; accepts `html`/`css` (no `scala` without `scalajs`) |
| `scalajs`         | Backend          | SPA target (HTML + JS); accepts `scala`/`html`/`css`        |
| `interpreter`     | Backend          | Execution target; accepts everything                        |

**Bundled plugins ship in the CLI distribution but are loaded via the
same `ServiceLoader` mechanism as any other plugin.** A CLI build
without the bundled set is technically valid — `html"…"` then
resolves to "unknown symbol" at parse time — but the released CLI
always includes them. This eats our own dog food: `core` knows
nothing privileged about HTML, CSS, or Scala, and the bundled
plugins are the worked example for any future SourceLanguage author.

What stays in core's prelude (NOT a plugin): the Markdown structure
itself, `scalascript`/`ssc` parsing and typing, the basic standard
library (`Int`, `String`, `List`, `Option`, `Map`, …), the `s` / `f`
string interpolators (standard Scala), the `md"…"` interpolator and
`Md` type (Markdown is the host syntax — first-class).

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
  def intrinsics: Map[QualifiedName, IntrinsicImpl]   // §8 — platform-native operations
  def acceptedSources: Set[String]                 // §9 — canonical source-language names this target can emit

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

`scalascript-ir` provides upickle codecs that read/write the same case
classes in either of two wire formats:

- **JSON** — newline-delimited, human-readable, trivial to dump for
  debugging.
- **MsgPack** — length-prefixed binary, ~3–5× smaller and ~2× faster
  to parse on large modules. Same upickle derivation, no separate
  type machinery.

**Serialisation is only used at process boundaries.** In-process
plugins receive the `NormalizedModule` as an ordinary Scala object by
reference — no copying, no encoding. Out-of-process plugins (§12.2)
pick their wire format declaratively in `plugin.yaml`; the choice is
the plugin author's, not core's. The codecs are the contract between
the two worlds.

Round-trip is part of CI for **both** formats:
`Parser → Typer → Normalize → toBytes → fromBytes` must equal the
in-memory `NormalizedModule`. This is the contract between in-proc and
out-of-proc plugins — break it and out-of-proc backends silently
diverge.

JSON schema is committed at `schemas/ir.json` for cross-language
plugin authors (a MsgPack-conformant message has the same field
shape). Each `NormalizedModule` carries the single SPI/IR version
number (§13).

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

## 7. Lenses — language feature, not SPI feature

Monocle-style lenses (`Lens`, `Prism`, `Optional`, `Traversal`, `Iso`,
plus a `focus(_.a.b.c).modify(f)` API) belong in core, not in the
backend SPI. This section exists to make the deliberate contrast with
§6 explicit, so a future contributor doesn't try to add `Focus` /
`Replace` IR nodes by analogy.

### Why they are not like effects

| Question | Effects | Lenses |
|---|---|---|
| Affect control flow? | Yes — reify continuations | No — pure data transformation |
| Platform-specific idiom? | Yes — stack switching, trampolines, threads, callbacks; every target picks differently | No — always lowers to "build a new value with one slot replaced" |
| Best lowering site? | Late, per-backend | Early, in core, before backends see anything |
| What the backend learns | Where effect boundaries are; how to materialise `k` | Nothing — IR contains only ordinary field-selects and `copy` |

Effects need IR primitives because backends pick *different* platform
mechanisms. Lenses have no such choice — every reasonable target
lowers them the same way. Adding `Focus` / `Replace` IR nodes would
force every backend to re-implement the same trivial expansion.

### Decision

- Lenses are a **prelude library + parser/typer feature**, not part of
  `backend-spi`.
- The `focus(_.path)` macro runs in core during normalisation. By the
  time `NormalizedModule` is produced, lens expressions are gone.
- Backends see only ordinary field selects and case-class `copy` —
  which they must already support to handle case classes at all.
- No new `Feature` flag. No new IR node. No SPI change.

### What core needs to provide

1. **Parser** — accept the path-lambda form inside `focus(_.a.b.c)`.
   The body must be a chain of field selections; reject otherwise at
   parse time. No grammar change beyond a small validation pass.
2. **Typer** — derive the optic type. `focus[S](_.a.b)` where
   `S.a: A`, `A.b: B` has type `Lens[S, B]`. Composition: `Lens ∘ Lens
   = Lens`, `Lens ∘ Prism = Optional`, `Lens ∘ Traversal = Traversal`,
   etc.
3. **Prelude library** — `Lens`, `Prism`, `Optional`, `Traversal`,
   `Iso` as plain ScalaScript definitions:
   ```scala
   case class Lens[S, A](get: S => A, replace: A => S => S):
     def modify(f: A => A): S => S = s => replace(f(get(s)))(s)
     def andThen[B](that: Lens[A, B]): Lens[S, B] =
       Lens(s => that.get(get(s)),
            b => s => replace(that.replace(b)(get(s)))(s))
   ```
   They live in user-visible source, so backends pick them up through
   the normal compilation pipeline.
4. **Optimiser pass (optional)** — fuse
   `focus(_.a.b).replace(v)` into a direct `s.copy(a = s.a.copy(b = v))`
   chain to eliminate intermediate `Lens` allocations. Lives in core;
   backends unaffected.

### Sanity test

If someone proposes a backend where this design breaks, ask: *can the
backend compile a hand-written `s.copy(a = s.a.copy(b = v))`?*

- Yes → lenses work for free.
- No → the backend doesn't support case classes at all; lenses are
  the least of its problems.

So lenses are subsumed by ordinary case-class semantics. No separate
capability flag.

### Parked: optimisation IR node

A future `StructUpdate(target, path, newValue)` IR node would let
backends emit efficient updates on targets with first-class
record-update syntax (`with` records in .NET, struct update in Rust).
This is *optimisation*, not semantics. Defer until a real performance
case appears; if it ever lands, it goes through an SPI minor bump and
the core optimiser falls back to plain `copy` for backends that don't
opt in.

## 8. Platform intrinsics (HTTP, WebSockets, auth, FS, crypto)

Today every code generator hard-codes its own HTTP runtime: each
`route()` / `serve()` call is recognised by name in `JvmGen`, `JsGen`,
and `Interpreter`, and emitted as inline platform-specific code. This
won't scale — for each new backend (WASM, native, .NET) we'd duplicate
the embedding, and the same problem recurs for WebSockets, auth,
filesystem, crypto, database access.

The SPI introduces a uniform mechanism: **platform intrinsics**.

### Position in the spectrum

- **Effects** (§6): control-flow abstraction. IR primitives;
  per-backend representation.
- **Lenses** (§7): pure data transformation. Fully lowered in core;
  backends see nothing.
- **Intrinsics**: side-effecting *capabilities* whose implementation
  must use the platform's native API. Core cannot lower them — only
  the backend knows the platform. Backends supply implementations
  through the SPI.

### Mechanism

1. **Declaration in user space.** An `extern` modifier marks a
   definition that *may* be implemented by a backend. The typer
   treats it as an ordinary symbol; normalisation lowers calls to it
   into an `ExternCall(qualifiedName, args)` IR node. A declaration
   without a body requires every backend to supply an implementation;
   a declaration *with* a body is an **optional intrinsic** whose
   body is the default fallback (see "Optional intrinsics" below).

   ```scala
   package std.http
   case class Request(method: String, path: String,
                      headers: Map[String, String], body: String,
                      files: List[FilePart])
   case class Response(status: Int,
                       headers: Map[String, String], body: String)

   extern def serve(port: Int): Unit
   extern def route(method: String, path: String,
                    handler: Request => Response): Unit
   extern def stop(): Unit
   ```

2. **Implementation in the backend.** The `Backend` trait carries an
   intrinsic table (see §4.2):

   ```scala
   sealed trait IntrinsicImpl
   case class InlineCode(emit: (List[IrExpr], EmitContext) => TargetCode) extends IntrinsicImpl
   case class RuntimeCall(targetSymbol: String)                            extends IntrinsicImpl
   case class HostCallback(name: String)                                   extends IntrinsicImpl
   ```

   The backend either inlines target code at the call site
   (`InlineCode`), or routes the call to a runtime function it ships
   (`RuntimeCall`), or, for out-of-process plugins, calls back into
   core via a named host callback (`HostCallback`).

3. **Validation.** Missing intrinsics are caught by the same
   `CapabilityCheck` as missing language features: using
   `std.http.serve` against a backend with no entry for it produces a
   `Diagnostic.Unsupported` *before* `compile()` runs.

### Optional intrinsics (extern with fallback body)

Some `extern` declarations carry a default implementation in core — a
fallback that backends MAY but need not override. The pattern:

```scala
package std.markup
case class Html(nodes: List[HtmlNode])
case class Css(rules: List[CssRule])
case class Md(blocks: List[MdBlock])

extern def render(h: Html): String = internal.renderHtmlToString(h)
extern def render(c: Css): String  = internal.renderCssToString(c)
extern def render(m: Md): String   = internal.renderMdToString(m)
```

If a backend registers an intrinsic for `std.markup.render(Html)`,
that intrinsic wins at the call site. Otherwise the fallback is
compiled like any other prelude function — the `js` backend emits the
same `String`-producing code as the `jvm` backend; no special
treatment.

This is the resolution shape for `html"…"` / `css"…"` / `md"…"`
interpolators (see §16 Resolved): the interpolator builds a typed
`Html` / `Css` / `Md` value in the prelude (parsing and escaping done
once in core), and rendering goes through the optional intrinsic.
Backends with native templating (browser DOM/VDOM, .NET Razor,
server-side template caching) can later opt into a platform-native
representation without affecting backends that don't.

### Migration of existing HTTP code

- `route`, `serve`, `Request`, `Response` move into `std/http.ssc` in
  the prelude with `extern` markers.
- Each current plugin exposes its existing platform code through
  `Backend.intrinsics`:
  - `jvm` plugin → wraps `com.sun.net.httpserver`
  - `js` plugin → wraps Node's `http` module
  - `interpreter` plugin → wraps the existing `server/WebServer.scala`
- The hard-coded HTTP generation in `JvmGen` and `JsGen` disappears;
  emission is now triggered by `ExternCall` IR nodes whose target is
  in the intrinsic table.

This is relocation, not rewriting. Absorbed by Phase 5.

### Generalisation

Same pattern, no new SPI surface, for everything the platform provides:

- `std.ws.{accept, send, recv, close}` — WebSockets, per backend.
- `std.auth.{signJwt, verifyJwt, hashPassword, hashPasswordVerify}` —
  wraps platform crypto.
- `std.fs.*`, `std.crypto.*`, `std.db.*` — same story.

`Feature` flags in §9 group intrinsics into capability-check buckets
(`HttpServer`, `WebSockets`, `Auth`, `FileSystem`, `Crypto`,
`Database`). A backend that declares a feature MUST provide
intrinsics for the whole package; partial coverage is a bug, not a
degraded mode.

### Why not model HTTP as an algebraic effect?

Tempting because the effect machinery already exists, but:

- Effects exist for non-local control transfer. `bind a socket and
  call user code on each request` doesn't use that.
- Effects compose with user-written handlers. We don't want users
  writing an in-process HTTP handler that the type system treats as
  interchangeable with the backend's native one — semantics would
  diverge silently.
- Intrinsics are also the natural place for FS, crypto, DB —
  practically none of which is effectful in the algebraic sense. One
  mechanism for all platform capabilities is simpler than two.

Intrinsics and effects coexist: an `extern` function can also be
`effectful` (an HTTP handler that performs algebraic effects). The IR
carries both pieces of information independently.

### Third-party intrinsic packages

A plugin author can ship its own `extern` package
(`thirdparty.kafka.*`, `thirdparty.redis.*`) together with the
intrinsics that implement it. Programs depending on that package work
only against backends that bundle the plugin — the capability check is
the same. No core change needed.

### Open questions

- **Sync vs async handler semantics.** JVM `HttpServer` is sync per
  request; Node and WASM are async. Today the language presents a
  sync API. When we add cancellation/timeouts, decide whether they
  go through effects or `Future`-style types. Doesn't block this SPI
  design.
- **Shared runtime artefacts.** If `jvm` and `interpreter` plugins
  both wrap `com.sun.net.httpserver`, do they share a runtime jar?
  Probably not in v1 — duplication is cheap; decoupling is valuable.

## 9. Multi-language code blocks

**Core handles only `scalascript`/`ssc` blocks** (the host embedded
language) plus the host Markdown structure itself. Every other fence
tag — `scala`, `html`, `css`, `wat`, `csharp`, `sql`, `python`, … —
is the responsibility of a `SourceLanguage` plugin. Three of those
plugins (`scala-source`, `html`, `css`) are *bundled* with the CLI
but architecturally identical to any third-party plugin.

Today's compiler hard-codes `html`/`css` blocks and the
`html"…"`/`css"…"` interpolators across `JvmGen`, `JsGen`, and
`Interpreter`. Phase 9 (§14) extracts them into the bundled plugins;
the wire is already there because the parser already treats fence
tags as opaque strings and interpolator prefixes as user-space
identifiers.

This section covers the **source-language** axis of the SPI (see §4
intro). It is orthogonal to the **target-output** axis (`Backend`,
§4.3): a `SourceLanguage` plugin produces IR that any target consumes,
and a target backend emits its artifact regardless of which source
languages contributed to the IR.

### Mechanism

A plugin can implement `Backend`, `SourceLanguage`, or both:

```scala
trait SourceLanguage:
  /** Canonical fence tags this plugin owns: "scala", "sql", "rust", ... */
  def languages: Set[String]

  /** Prelude .ssc files this plugin contributes globally. Compiled by
   *  core before user code; symbols become visible across every
   *  block. This is where `Html` / `Css` / DSL extensions / extern
   *  signatures live for the bundled `html` and `css` plugins. */
  def preludeFiles: List[PreludeContribution]

  /** Pass 1: report symbols this block contributes to the module
   *  scope (§10). Bodies are NOT type-checked here. Enables forward
   *  and cyclic references between blocks. */
  def signatures(language: String, source: String, span: Span)
    : Either[List[Diagnostic], List[SymbolExport]]

  /** Pass 2: type-check + lower against the now-complete module
   *  scope. Result is woven into the surrounding NormalizedModule. */
  def compileBlock(
    language: String,
    source: String,
    span: Span,
    scope: ReadonlyScope,
    opts: BackendOptions
  ): Either[List[Diagnostic], BlockArtifact]

case class BlockArtifact(
  irNode:    NormalizedNode,        // injected into NormalizedModule
  exports:   List[SymbolExport],    // symbols this block contributes globally (§10)
  artifacts: List[FileArtifact]     // any side-files the block produces
)

case class PreludeContribution(
  virtualPath: String,    // for diagnostics, e.g. "html-plugin:Html.ssc"
  source:      String     // raw .ssc source
)
```

`scalascript` blocks are the language proper and are always handled by
core's parser/typer — never by the `SourceLanguage` mechanism.

### Prelude contributions

A `SourceLanguage` plugin needs more than per-block parsing — it needs
to make *types and helpers visible globally*. The bundled `html`
plugin, for example, has to expose `Html`, the `div`/`p`/`body` DSL
functions, an `extension (sc: StringContext) def html(args: Any*): Html`
(the interpolator), and an `extern def render(h: Html): String`
(rendering hook for backends, §8). None of these are *block-scoped* —
they're project-wide.

The mechanism: each plugin contributes a list of `PreludeContribution`
entries (see trait above). For in-process plugins, the entries are
typically static resources packed in the JAR (a `prelude/` folder).
For out-of-process plugins, they come back from the `describe()` RPC
as an array of `{virtualPath, source}` records.

Core compiles every plugin's prelude contributions **before** user
code, in a deterministic order:

1. Core's own prelude (`Int`, `String`, `List`, `Option`, `s`, `f`,
   `md`, `Md`, …).
2. Bundled plugins (`scala-source`, `html`, `css`) in plugin-id
   lexicographic order.
3. Discovered plugins (project → user → env → CLI-flag) — same id
   order within each scope.

Symbol collisions across prelude contributions are a hard error:
`Diagnostic.DuplicatePreludeSymbol(name, [sources])`. No silent
shadowing. If a user wants to override `div`, they do so in their own
code, not by injecting a competing plugin.

### Reserved names and mandatory support

- **Reserved.** The canonical name `scalascript` and its alias `ssc`
  are reserved by core. A plugin that declares either in its
  `acceptedSources` or `SourceLanguage.languages` fails to register
  with a clear error. Prevents accidental shadowing of the base
  language.
- **Mandatory.** Every program is built on `scalascript`/`ssc`
  blocks; core's parser/typer always processes them, regardless of
  the active backend. A backend's `acceptedSources` lists
  *additional* languages it natively understands; it can legitimately
  be `Set.empty` (a pure code generator with no foreign-language
  embedding).

### Aliases and canonical names

Some fence tags have multiple accepted spellings — `scalascript` ↔
`ssc`, `javascript` ↔ `js`, `csharp` ↔ `cs`, `webassembly` ↔
`wasm`/`wat`. Core maintains a **canonical-name table** and normalises
every fence tag to its canonical form *before* any lookup against
`acceptedSources` or `SourceLanguage.languages`. A plugin therefore
declares only the canonical name (e.g. `Set("scalascript")`, not
`Set("scalascript", "ssc")`).

Initial canonical table:

| Canonical     | Aliases       |
|---------------|---------------|
| `scalascript` | `ssc`         |
| `javascript`  | `js`          |
| `python`      | `py`          |
| `csharp`      | `cs`          |
| `webassembly` | `wasm`, `wat` |
| `html`        | —             |
| `css`         | —             |
| `scala`       | —             |

Plugins extend the table by listing aliases in `plugin.yaml`
(out-of-process) or returning them from `Backend` / `SourceLanguage`
(in-process):

```yaml
aliases:
  rust: [rs]
```

### Resolution

For each foreign block, core asks:

1. Does the active **target backend** declare this language in its
   `acceptedSources` set? If yes, the backend handles it natively
   — the raw source is preserved in the IR as an `EmbeddedBlock` for
   the backend to emit during `compile()`.
2. Otherwise, is there a registered `SourceLanguage` for this
   language? If yes, invoke it; the resulting `BlockArtifact.irNode`
   replaces the block in the IR before the target backend sees it.
3. Otherwise, `Diagnostic.UnknownBlockLanguage(language, available)`.

If multiple source-language plugins claim the same language, the user picks
explicitly: `--block-handler scala=scalajs` overrides the default
order (declared-by-target → first-registered).

**Worked example.** `jvm` is the active target and natively handles
`scala`; `scalajs` is *also* registered as a `SourceLanguage` for
`scala`. By rule (1) the `jvm` backend wins — `scala` blocks are
embedded as raw Scala source in the generated `.scala` file. To force
delegation to `scalajs` (so the same blocks become JS), the user
passes `--block-handler scala=scalajs`; rule (2) then fires even
though the target backend would have claimed the language natively.

### Examples (bundled + planned)

| Fence tag    | Handler                                              | Output                          |
|--------------|------------------------------------------------------|---------------------------------|
| `scalascript`| core (always)                                        | NormalizedModule                |
| `scala`      | bundled `scala-source` `SourceLanguage`              | `EmbeddedSource(scala)` IR node |
| `html`       | bundled `html` `SourceLanguage`                      | typed `Html` value in IR        |
| `css`        | bundled `css` `SourceLanguage`                       | typed `Css` value in IR         |
| `wat`        | future `wasm` plugin                                 | embedded in WASM module         |
| `csharp`     | future `dotnet` plugin                               | embedded in generated C#        |
| `sql`        | future `sql` `SourceLanguage` plugin                 | typed query AST in IR           |
| `python`     | future `python` plugin                               | embedded Python                 |
| (unknown)    | `Diagnostic.UnknownBlockLanguage(tag, available)`    | —                               |

### Capabilities vs fence-tag support

Fence-tag language support is **not** mirrored in §11 `Feature` flags.
`acceptedSources` (per Backend) and `SourceLanguage.languages` are
the single source of truth: if a tag isn't listed there (after alias
normalisation), core emits `Diagnostic.UnknownBlockLanguage`; if it
is, the block compiles. A `Feature.ScalaBlocks` / `Feature.SqlBlocks`
enum would duplicate the same information.

### Relationship between `html`/`css` fence blocks and `html"…"`/`css"…"` interpolators

The same language names appear in two surface forms, both owned by
the **bundled `html` / `css` plugins** (not by core):

- **Fence block** ` ```html … ``` ` — the whole block is HTML (or
  CSS). The plugin's `compileBlock` parses it and returns a typed
  `Html` (or `Css`) value as a `BlockArtifact`; a `{#id}` attribute
  (Tier 2, §10) names it.
- **String interpolator** `html"…${expr}…"` — inline expression in
  ScalaScript code, with `${…}` slots. Desugars (via Scala 3's
  standard interpolator mechanism) to a call to
  `extension (sc: StringContext) def html(args: Any*): Html`, which
  the plugin ships in its prelude contribution.

Both surface forms produce values of the same plugin-defined types
(`Html` / `Css`) and route through the same `Html.render` /
`Css.render` *optional intrinsic* (§8) supplied by the same plugin.
One parsing+escaping codepath in the plugin, one render-override hook
for backends. This consolidates and relocates the `renderStringBlock`
logic currently duplicated across `JvmGen`, `JsGen`, and `Interpreter`
— the duplication doesn't survive into the new architecture.

`md` is **different** — it stays in core. There is no ` ```md ``` `
fence block (markdown is the host document syntax; embedding it as a
fenced block would be circular), and the `md"…"` interpolator + `Md`
type are part of core's prelude, not a plugin contribution. The
standard `s` and `f` Scala interpolators likewise stay in core.

This resolves the corresponding open question from §16.

### Why this matters

This is the *only* mechanism by which ScalaScript's polyglot promise
extends to user plugins. Without it, every new dialect a plugin wants
to embed becomes a special case in core. With it, the plugin-author
surface stays small (one interface, one fence-tag set) and the
language can grow horizontally as the ecosystem adds backends.

## 10. Block references and identity

How does a `scala` block call a function defined in a `scalascript`
block? How does a `scalascript` block reference an HTML template
declared in an `html` block? §9 gave plugins the right to *parse*
foreign-language code; this section makes the results visible to the
rest of the document.

### Decisions (locked)

- **Exports are declared by the plugin**, not by user-side annotation
  in the markdown. The `SourceLanguage` introspects its source and
  reports what it exposes; no `{exports="..."}` markup, no
  `-- exports:` comments to maintain.
- **Visibility is global within the module.** A symbol or ID exposed
  by any block is visible to every other block. Heading hierarchy
  affects documentation only, not name resolution.
- **Cycles allowed.** Forward and mutual references work the same as
  for ordinary `def`s.

### Tier 1 — by exported symbol (default)

Blocks that define top-level names contribute them to the module's
global scope via the `SourceLanguage`:

```scala
case class SymbolExport(
  name:   String,
  tpe:    ir.SType,            // mapped into core's type system
  span:   Span,
  origin: QualifiedName        // for diagnostics, e.g. "py:my-module.foo"
)
```

Example:

```markdown
## Data
​```sql
SELECT * FROM users WHERE active = true;
​```
(the SQL plugin reports: `usersQuery: Query[User]`)

## Render
​```scalascript
val rows = usersQuery.run()    // visible globally
​```
```

### Tier 2 — by block ID

For blocks that don't define names (HTML template, CSS rule set, JSON
fixture, opaque artifact), use Pandoc-style fence attributes:

```markdown
​```html {#login-form}
<form action="/login"> ... </form>
​```

​```scalascript
def page = include("#login-form")
// or via ordinary markdown link:
val link = "see [the login form](#login-form)"
​```
```

`{#id}` is markdown-level metadata, parsed by core and stored as a
property of the `EmbeddedBlock` IR node. The prelude function
`include` resolves the ID at IR-link time. A block can carry both
exports and an ID — they're orthogonal.

### Cycles: two-pass typing

Mutual recursion across blocks requires the typer to learn all
signatures before checking any body:

```markdown
​```scalascript
def isEven(n: Int): Boolean = if n == 0 then true else isOdd(n - 1)
​```
​```scalascript
def isOdd(n: Int): Boolean  = if n == 0 then false else isEven(n - 1)
​```
```

The `SourceLanguage` exposes two stages (signatures + compileBlock,
shown in §9). Core orchestrates: first gather signatures across every
block in the document; then re-walk and compile bodies against the
complete scope. Same shape as let-rec in any modern language.

### Collisions

Two blocks exposing the same name →
`Diagnostic.DuplicateExport(name, sites)`. Users disambiguate by
renaming or, in a future minor addition, by an `{exports.prefix="…"}`
attribute that qualifies the names. Wait until the first user
complaint before implementing.

### Not in scope (v1)

- **Parameterised components (MDX-style).** A future extension on
  Tier 2 — block IDs that take arguments — is feasible but not part
  of this design.
- **Cross-module block references.** Module-level imports (§5)
  already handle symbols across files; block-ID references across
  files need a new resolver. Defer until use-case appears.
- **CSS-style selectors over blocks.** Excluded — heading hierarchy +
  IDs is a complete addressing scheme; selectors would re-invent
  symbol resolution.

## 11. Capabilities

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
  // Platform capabilities — each gates a std.* intrinsic package (§8)
  case ConsoleIO                 // std.io
  case HttpServer                // std.http
  case WebSockets                // std.ws
  case Auth                      // std.auth
  case FileSystem                // std.fs
  case Crypto                    // std.crypto
  case Database                  // std.db (future)

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

## 12. Discovery and loading

### 12.1 In-process (JVM plugins)

- Two `META-INF/services` files, one per role — a single plugin JAR
  may list classes in either or both:
  - `scalascript.backend.spi.Backend` — target-output classes
  - `scalascript.backend.spi.SourceLanguage` — source-language classes
- Standard `ServiceLoader` discovery for each.
- Default discovery paths:
  - The bundled CLI classpath (the four standard plugins).
  - `~/.scalascript/plugins/*.jar`
  - `$SCALASCRIPT_PLUGIN_PATH` (colon-separated dirs).
  - CLI flags: `--plugin <jar>`, `--plugin-dir <dir>`.
- Each plugin JAR is loaded in its own `URLClassLoader` whose parent is
  the SPI classloader only — plugins cannot see each other's
  dependencies (avoids version conflicts).

### 12.2 Out-of-process plugins

A plugin distribution is a directory with a `plugin.yaml`. Either role
— `Backend`, `SourceLanguage`, or both — can run out-of-process. The
`roles` field declares which.

#### Examples

A pure target backend:

```yaml
id: wasm
displayName: WebAssembly (via wasm-tools)
spiVersion: "0.1.0"
protocol: stdio-json         # or stdio-msgpack
executable: ./bin/sscbackend-wasm
args: ["--quiet"]
roles: [backend]
backend:
  features: [PatternMatching, MutableState, TailCallOptimization]
  outputs:  [WasmBytecode]
  acceptedSources: [wat]
```

A pure source-language plugin (e.g. real Python parser as a frontend):

```yaml
id: python-frontend
displayName: Python (via CPython AST)
spiVersion: "0.1.0"
protocol: stdio-json
executable: python3
args: [./bin/ssc_python_frontend.py]
roles: [source-language]
sourceLanguage:
  canonicalName: python
  aliases: [py]
```

Both roles served by one subprocess:

```yaml
id: dotnet
spiVersion: "0.1.0"
protocol: stdio-msgpack
executable: ./bin/ssc_dotnet
roles: [source-language, backend]
sourceLanguage:
  canonicalName: csharp
  aliases: [cs]
backend:
  features: [PatternMatching, MutableState]
  outputs:  [DotNetIL]
  acceptedSources: [csharp]
```

#### Wire protocol

Two framings, selected by `protocol`:

- `stdio-json` — newline-delimited JSON, one message per line.
- `stdio-msgpack` — 4-byte big-endian length prefix + MsgPack payload.

Methods are role-scoped:

| Role            | Methods |
|-----------------|---------|
| common          | `describe()`, `shutdown()` |
| backend         | `compile(ir, opts)`; for `InteractiveBackend`: `openSession`, `session.feed`, `session.close` |
| source-language | `signatures(language, source, span)`, `compileBlock(language, source, span, scope, opts)` |

stderr from the subprocess is forwarded to the user as diagnostic
logs. Discovery paths mirror the in-process case but look for
`plugin.yaml` files instead of JARs.

A subprocess plugin whose `executable` is a JVM jar is allowed (just
spawn `java -jar …`); useful when an author wants total isolation
from core's classpath.

## 13. Versioning

Plain semver, no edge cases. **Current version: 0.1.0** (pre-stable —
breaking changes are allowed in 0.x minor bumps).

- One version number governs both the SPI and the IR schema. Plugins
  declare `spiVersion = "0.1.0"`; the loader rejects mismatches by
  major version (and, while we're in 0.x, by minor too).
- Once we hit 1.0, breaking changes require a major bump; until then,
  minor bumps may break.
- `scalascript-backend-spi` is published as its own artifact so
  third-party plugin authors can depend on it independently of
  `core`, on the same version number.
- No version ranges, no compatibility shims, no graceful degradation
  for older plugins. Bumping the compiler means rebuilding plugins.

## 14. Migration plan (9 phases)

Each phase is one PR. Tests stay green at every step.

### Phase 1 — Module split & SPI skeleton  (M, ~1d)

- Add sbt subprojects: `backend-spi`, `ir`, `core`, `cli`, four backend
  modules (the bundled SourceLanguage modules — `scala-source`,
  `html`, `css` — come in Phase 9; placeholder dirs are fine now).
- Move existing sources into `core` (no semantic changes).
- Define `Backend` and `SourceLanguage` traits plus `CompileResult`,
  `Capabilities`, `BackendOptions`, `IntrinsicImpl`, `BlockArtifact`,
  `SymbolExport`, `PreludeContribution` in `backend-spi` (stubs are
  fine).
- `cli/Main.scala` still calls concrete backends directly — registry
  comes in Phase 5. Output: nothing visible changes, but the build is
  modular.

### Phase 2 — IR + JSON codec  (M, ~1d)

- Introduce `NormalizedModule` types under `ir/`, including
  `ExternCall(qualifiedName, args)` (§8) and
  `EmbeddedBlock(language, source)` (§9) nodes.
- Initially, normalisation is a near-no-op — it rewrites calls to
  `extern`-marked symbols into `ExternCall` and tags foreign code
  blocks as `EmbeddedBlock`.
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

- Define `Feature` / `OutputKind` enums as in §11.
- Tag the four existing backends with their actual capability sets.
- Add `CapabilityCheck` walker; wire it into the pipeline before
  `compile()`.
- New error type `Diagnostic.Unsupported(feature, backend)`.

### Phase 5 — Convert existing backends to plugins  (M, ~1–1.5d)

- Each of `JvmGen`, `JsGen`, `ScalaJsBackend`, `Interpreter` gets a
  `Backend` implementation in its own subproject with a
  `META-INF/services` file. `ScalaJsBackend` is a top-level plugin
  (target id `scalajs-spa`); it additionally implements
  `SourceLanguage` so the `js` plugin can delegate `scala` blocks to
  it when configured.
- `Interpreter` implements `InteractiveBackend` (its `Session` wraps
  the existing `run` / `invoke` API; the existing `runSnippet` becomes
  `feed`).
- `core` adds `BackendRegistry` (ServiceLoader-based discovery), with
  separate registries for `Backend` and `SourceLanguage` plugins.
- Hard-coded HTTP/web handling (route / serve / Request / Response)
  is extracted: signatures move to `std/http.ssc` in the prelude with
  `extern` markers; each plugin exposes its platform code through
  `Backend.intrinsics` (§8).
- Each backend declares its `acceptedSources`: `jvm` →
  `{"scala", "html", "css"}`, `js` → `{"html", "css"}`,
  `scalajs` (as both Backend + SourceLanguage) →
  `{"scala", "html", "css"}`, `interpreter` →
  `{"scala", "html", "css"}`. Strings reference future bundled
  source plugins (delivered in Phase 9); for the duration of Phases
  5–8 they're declared as expected but `core`'s built-in handling
  for `html`/`css`/`scala` remains in place. Phase 9 removes the
  built-in handling and the declarations finally point at real
  registered plugins.
- Block-export pipeline (§10) wired into the typer: signature
  collection across all foreign blocks first, then per-block
  compilation with the full module scope visible. Cycle support
  validated by a cross-block mutual-recursion test.
- `cli/Main.scala`, `server/WebServer.scala`, `server/Routes.scala`
  switch to looking up backends by id from the registry. No direct
  imports of `JvmGen`/`JsGen`/`Interpreter` outside their plugin
  modules.

### Phase 6 — Out-of-process loader  (M, ~1–1.5d)

- `core/plugin/Subprocess.scala`: framed messages over stdio
  (`stdio-json` and `stdio-msgpack`); role-aware dispatch (Backend
  methods + SourceLanguage methods).
- `plugin.yaml` parser and loader; the `roles:` field gates which
  method set the loader expects from the subprocess.
- Two in-tree smoke-test plugins exercising both roles in CI without
  needing real external tooling:
  - a 50-line Scala-CLI Backend plugin that returns a canned
    `TextOutput`
  - a 50-line Scala-CLI SourceLanguage plugin for a trivial dialect
    (e.g. `toml`) — returns a single `SymbolExport` and a stub IR
    fragment
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

### Phase 9 — Extract bundled SourceLanguage plugins  (M–L, ~1.5–2d)

The radical core-simplification step: pull `html`/`css`/`scala`
handling out of `core` into bundled plugins. After this, the only
languages `core` itself parses are Markdown (the document syntax) and
`scalascript`/`ssc` (the host embedded language).

- New sbt subprojects:
  - `backend-scala-source/` — `SourceLanguage` for `scala` fence
    blocks. Wraps the existing scalameta parsing; produces
    `EmbeddedSource(scala, …)` IR nodes for `acceptedSources`
    backends to embed verbatim, or a lowered IR fragment for backends
    that ask for it.
  - `backend-html/` — `SourceLanguage` for `html` blocks and the
    `html"…"` interpolator. Owns the `Html` type, the DSL tag bindings
    (`div`, `p`, `body`, …), and the `extern def render(h: Html): String`
    optional intrinsic. All of this ships via `preludeFiles`.
  - `backend-css/` — same shape for `Css`.
- Remove the now-redundant code from `core`:
  - `Lang.isStringBlock` / `Lang.isParseable` predicates collapse to
    "is it `scalascript`/`ssc`?".
  - `JvmGen` preamble loses the `containerTagNames` / `voidTagNames`
    list and the `_Raw` case-class emission.
  - `Interpreter` loses the `nativeP("div") { … }` block and the
    `renderStringBlock` paths for `html`/`css`.
  - The interpolator match arms for `html` / `css` in `JvmGen`,
    `JsGen`, and `Interpreter` are removed — they're now ordinary
    extension methods supplied by the plugins' prelude.
- Verify by build: a CLI build *without* the three new modules on its
  classpath compiles, runs `scalascript` blocks fine, and reports
  `Diagnostic.UnknownBlockLanguage` for `html`/`css`/`scala` — proving
  the boundary holds.
- Released CLI continues to bundle all three so user-facing behaviour
  is unchanged.

**Total:** ~8–11 working days.

## 15. What this enables (future)

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

## 16. Open questions

- **Plugin trust.** A loaded JAR runs with full JVM permissions. Do we
  want any sandboxing for third-party plugins, or document this and
  rely on the user knowing what they install? (Initial recommendation:
  document; revisit if we ever ship a plugin marketplace.)

### Resolved (logged for the record)

- **`html`/`css`/`md` interpolators** → option **B with core
  fallback**: interpolators build typed `Html` / `Css` / `Md` values
  in the prelude (parsing + escaping in core, once). Rendering is an
  *optional intrinsic* (§8 — `extern` with default body): default
  String render lives in core, backends with native templating
  (browser DOM/VDOM, .NET Razor, server-side template caching) opt
  in by overriding the intrinsic. Best of both: shared validation,
  platform-native output where it matters.
- **`ScalaJsBackend` role** → separate top-level plugin
  (target = `scalajs-spa`); additionally registered as a
  `SourceLanguage` for `scala` blocks when the `js` target opts in.
- **Block references across languages** → two-tier (§10): exports
  declared by the `SourceLanguage` (no user-side annotation), global
  module-wide visibility, cycles allowed via two-pass typing. Tier 1
  symbol-level + Tier 2 Pandoc-style `{#id}` for opaque blocks.
- **IR / SPI versioning policy** → plain semver, one version number
  for both (§13); lockstep updates across plugins, no partial
  compatibility. Pre-stable at 0.1.0.
- **Core boundary minimisation** → core handles only Markdown and
  `scalascript`/`ssc`. `html`, `css`, and `scala` fence blocks plus
  the `html"…"`/`css"…"` interpolators move into bundled
  SourceLanguage plugins (Phase 9, §14). `md`/`Md` and the `s`/`f`
  Scala interpolators stay in core's prelude — markdown is the host
  syntax, the others are standard Scala. The bundled plugins use the
  same SPI as any third-party plugin: dog-fooding of the contract.
- **Prelude contributions** → each `SourceLanguage` plugin can ship
  `.ssc` files compiled into the module-wide scope before user code
  (§9 "Prelude contributions"). This is how the `html` plugin
  introduces the `Html` type and the `div`/`p`/`body` DSL globally
  without touching `core`.
- **`std/*` as bundled prelude** → after Phase 9 introduces
  `PreludeContribution`, the existing `std/*.ssc` typeclass
  hierarchy (landed under v1.1: Functor/Applicative/Monad,
  Foldable/Traversable, Bifunctor, Either, Selective, MonadError,
  Semigroup/Monoid) ships as a bundled `backend-std-prelude`
  plugin. **Predef-style hybrid:** the universally applicable
  layer (`Functor`/`Applicative`/`Monad`, `Foldable`/`Traversable`,
  `Either`/`Left`/`Right`, plus `List`/`Option`/`Either` instances)
  becomes auto-prelude — visible without explicit imports.
  Specialised abstractions (`MonadError`, `Selective`, `Bifunctor`,
  `Semigroup`/`Monoid`) remain explicit `[X](./std/…)` imports.
  Same SPI as any third-party plugin — the bundled split is just
  configuration of which files land in `preludeFiles` versus which
  the user imports by hand. Concrete tier split TBD during the
  Phase 9 follow-up; tracked in
  [`MILESTONES.md → Backend SPI v0.1`](../MILESTONES.md).

## 17. Definition of done

- [ ] All nine phases merged.
- [ ] `cli/Main.scala`, `server/*.scala` contain zero direct references
      to `JvmGen`, `JsGen`, `ScalaJsBackend`, or `Interpreter`.
- [ ] Adding a new backend requires no edits to `core` or `cli`.
- [ ] Adding a new platform capability (WebSockets, auth, db) requires
      only new `extern` signatures in the prelude plus per-backend
      intrinsic entries — no `core` change.
- [ ] Adding a new fenced-block language (e.g. `python`, `sql`)
      requires only a new plugin implementing `SourceLanguage` and/or
      `Backend` — no `core` change.
- [ ] HTTP handling is no longer hard-coded in any code generator;
      `std.http` ships as a prelude package, runtime lives in each
      plugin's intrinsic table.
- [ ] `html"…"`/`css"…"` live in their bundled SourceLanguage
      plugins as `StringContext` extension methods; `md"…"` stays in
      core. All three build typed AST (in plugin or core prelude) and
      render via optional `extern` with default String body. No
      per-backend HTML/CSS/MD generation duplicated; backends can opt
      into native templating later without SPI breakage.
- [ ] `core` builds and runs without the bundled `scala-source` /
      `html` / `css` plugins on the classpath: `scalascript` blocks
      compile, the three fence tags produce
      `Diagnostic.UnknownBlockLanguage`. Released CLI bundles all
      three.
- [ ] No `Lang.scala`-style `if lang == "html" || lang == "css"`
      anywhere in `core` after Phase 9.
- [ ] Existing test suite (`InterpreterTest`) green.
- [ ] New tests:
  - IR round-trip in both JSON and MsgPack
  - capability check rejects program with unsupported feature
  - capability check rejects program calling missing intrinsic
  - capability check rejects program with unknown block language
  - cross-block reference: scalascript ↔ foreign-language mutual
    recursion compiles and runs
  - duplicate-export collision reported with both source sites
  - subprocess plugin smoke test — Backend role
  - subprocess plugin smoke test — SourceLanguage role
  - plugin discovery from `--plugin-dir` (both registries)
  - prelude-contribution collision (duplicate symbol from two plugins)
    reported with both source paths
  - bundled-plugin-less build: `core` alone rejects `html`/`css`/`scala`
    cleanly
- [ ] `docs/architecture.md` rewritten to match.
