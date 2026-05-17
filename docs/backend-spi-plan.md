# Backend SPI — Implementation Plan

> Working plan for the `feature/backend-spi` branch.  Source of truth
> for design decisions: [`docs/backend-spi.md`](backend-spi.md).
> This document tracks **execution**: stages, iterations, what's done,
> what's pending, what surfaced along the way.
>
> Process: `AGENTS.md` §"Big-feature workflow (long-lived branch)".

## Spec verification

Re-read of `docs/backend-spi.md` against the current code on `main`
(`44dda1e` + AGENTS.md update + install.sh fix).  The spec is
implementable as written; **no design ambiguities block start**.

Reality check on numbers cited in the spec:

| Claim in spec                                  | Actual on `main`                 |
|------------------------------------------------|----------------------------------|
| `JvmGen.analyzeEffects` ~55 lines              | exists, line 234                 |
| `JsGen.analyzeEffects` ~70 lines               | exists, line 2814                |
| `Interpreter.Computation` Free monad           | in `interpreter/Value.scala`     |
| Four code generators, no shared abstraction    | confirmed (JvmGen 4008, JsGen 4486, ScalaJsBackend 128, Interpreter 3672 LOC) |
| `Lang.isStringBlock` / `isParseable` predicates| `ast/Lang.scala:31,35`           |
| `containerTagNames` / `voidTagNames` in JvmGen | `JvmGen.scala:1415,1425`         |
| Hardcoded `serve` / `route` in interpreter     | `Interpreter.scala:222` (nativeP)|

23 `Lang.*` references across `cli/Main`, `parser/Parser`, `typer/Typer`,
`codegen/*`, `interpreter/Interpreter` — all in scope for Phase 9
extraction.

Current build is a single `compiler` sbt module.  The 9-module split
in §4.1 is real work — Phase 1 alone moves ~13k LOC.

## Process — quick reminder

- This branch (`feature/backend-spi`) is long-lived.  Intermediate
  iterations push **here**, not to `main`.
- Each iteration:  implement → green check suite (`sbt test` + `scala-cli
  conformance/run.sc`) → commit → push to this branch → update this
  document (strike done, append surfaced work).
- Rebase onto `origin/main` at iteration boundaries when `main` moves.
- Merge to `main` once at the end:  every phase green, every test
  passing, README/SPEC/MILESTONES updated to match reality.

## Stage map

The spec's 9 phases map onto stages in this plan.  Some phases break
into multiple iterations; some are single-iteration.

| Stage | Spec phase | Iterations | Estimate |
|-------|-----------|------------|----------|
| 1 | Module split & SPI skeleton    | 3 | ~1d  |
| 2 | IR + JSON/MsgPack codec        | 2 | ~1d  |
| 3 | Effect lowering                | 3 | ~1–2d |
| 4 | Capabilities + validation      | 2 | ~0.5d |
| 5 | Backends-as-plugins            | 4 | ~1–1.5d |
| 6 | Out-of-process loader          | 3 | ~1–1.5d |
| 7 | CLI ergonomics                 | 2 | ~0.5–1d |
| 8 | Docs & sample plugin           | 2 | ~0.5d |
| 9 | Bundled SourceLanguage plugins | 4 | ~1.5–2d |
| 10| Final integration              | 1 | ~0.5d |

Total: ~8–11 working days, matching the spec estimate.

---

## Stage 1 — Module split & SPI skeleton

**Goal:** the sbt build is modular; `core` compiles against `backend-spi`
and `ir`; every test still passes; nothing user-visible changes.

### 1.1 sbt subproject scaffold
- Create empty subprojects per spec §4.1: `backend-spi/`, `ir/`,
  `core/`, `backend-jvm/`, `backend-js/`, `backend-scalajs/`,
  `backend-interpreter/`, `cli/`.  (`scala-source`, `html`, `css`
  SourceLanguage modules deferred to Stage 9 — placeholder dirs.)
- Each module: empty `src/main/scala`, sbt entries with the right
  dependency arrows.
- Existing `compiler/` stays in place but its sources will move in
  1.2; nothing builds against `compiler` after Stage 1 except a
  transitional alias.
- **Done when:** `sbt compile` succeeds with empty modules; existing
  `compiler/test` still runs by being aliased to the new structure (or
  temporarily kept).

### 1.2 Move sources into `core`, `cli`, backend modules
- Bulk move:
  - `parser/`, `typer/`, `ast/`, `imports/`, `interpreter/Value.scala`
    (Computation Free monad) → `core/`
  - `cli/Main.scala` → `cli/`
  - `interpreter/Interpreter.scala` → `backend-interpreter/`
  - `codegen/JvmGen.scala` → `backend-jvm/`
  - `codegen/JsGen.scala`, `JsRuntime*.scala` → `backend-js/`
  - `codegen/ScalaJsBackend.scala` → `backend-scalajs/`
  - `server/*` → stays close to interpreter for now (will rethink in
    Stage 5 — these wrap intrinsics for HTTP/WS/auth).
  - `bench/WsStress.scala` → its own `bench/` subproject so the
    `--main-class scalascript.cli.ssc` workaround can go away.
- Resolve circular imports as they appear (none expected — the
  layers are already cleanly stacked: ast → parser → typer →
  codegen / interpreter → cli).
- **Done when:** `sbt test` + `scala-cli conformance/run.sc` green;
  no `compiler/` references remain outside the alias.

#### Discovered while implementing 1.2

- **`server/WebServer.scala` imports `codegen.{JsGen, JsRuntime}`** —
  the server runtime embeds the JS runtime string into SPA-mode
  responses.  This forces `backend-interpreter dependsOn backendJs`,
  which violates the spec's "backends don't depend on each other"
  rule.  Marked TRANSITIONAL in `build.sbt`; **Stage 5** removes it
  via the HTTP-intrinsic refactor (§8) — the server moves behind the
  SPI and the JS runtime becomes a capability the backend declares.
- **`JsGenWsTest`** depends on `scalascript.server.WsFraming`
  (lives in `backend-interpreter`).  Moved it from
  `backend-js/src/test` to `backend-interpreter/src/test/scalascript/codegen/`
  so the test sees both sides.  Cross-backend tests of this shape
  belong in `backend-interpreter`'s test scope until Stage 5 splits
  out the WS runtime.
- **WsStress placement** — went into `backend-interpreter/.../bench/`
  (not a separate `bench` subproject).  Reason: existing `bench/`
  dir is scala-cli scripts (fib/sum/list-ops), not sbt; and
  WsStress logically stresses the interpreter runtime.  Also lets
  Stage 5's HTTP intrinsic refactor pick it up naturally.
- **`compiler/project.scala` deleted** — was scala-cli-only deps
  list parallel to `build.sbt`.  After the split, scala-cli no
  longer compiles the project directly; sbt + `sbt-assembly` does
  it via `cli/assembly` → `cli/target/scala-3.8.3/ssc.jar`.
- **`scripts/install.sh` rewritten** to invoke `sbt cli/assembly`
  and emit a tiny `java -jar` wrapper at the target path.  Drops
  `scala-cli --power package` entirely.
- **`scripts/launchers/{jssc,sscc,ssc-js}` rewritten** to call
  `bin/ssc <subcommand>` instead of `scala-cli run compiler --`.
  Symlinks land them in `bin/` via install.sh as before.
- **CI workflow `.github/workflows/ci.yml` updated** — conformance
  job now does `bash scripts/install.sh` to build `bin/ssc` first,
  then runs `scala-cli conformance/run.sc`.  Dropped the standalone
  `scala3` job (conformance suite already covers the JVM backend).
  `sbt` job runs `sbt compile test` (was `sbt compiler/compile compiler/test`).
- **`conformance/run.sc` + `examples/run-all.sc`** no longer fall
  back to `scala-cli run compiler --`; they require pre-built
  `bin/ssc` (with a clear error message if missing).
- **`README.md` Project Layout + Library Usage sections** refreshed
  to match new module tree.

### 1.3 SPI trait stubs in `backend-spi`
- Define exactly the surface from spec §4.2–4.4:
  `Backend`, `InteractiveBackend`, `Session`, `CompileResult`,
  `Segment`, `BackendOptions`, `Diagnostic`, `Capabilities`,
  `Feature`, `OutputKind`, `IntrinsicImpl`, `BlockArtifact`,
  `SymbolExport`, `PreludeContribution`, `SourceLanguage`,
  `SpiVersionRange`.  Just types + empty methods; no implementations.
- No backend implements them yet — that's Stage 5.
- **Done when:** `backend-spi` compiles standalone; published as its
  own artifact in the local build.

#### Discovered while implementing 1.3

- **Reversed `ir ⇄ backend-spi` dep direction.**  1.1 had
  `ir dependsOn backendSpi`; spec §4.1 actually says the opposite
  (SPI references IR types).  Reversed to `backendSpi dependsOn ir`
  and dropped `ir` from explicit dep lists where it's transitively
  available via backendSpi.
- **Placeholder IR types live in `ir/`** (`ir/src/.../ir/Ir.scala`):
  `QualifiedName`, `SymbolRef`, `NormalizedModule`, `NormalizedBlock`,
  `Value`, `IrExpr`, `EmitContext`, opaque `TargetCode`.  All are
  trait/case-class stubs; Stage 2 fills in concrete shapes + codecs.
  Backend-spi traits reference them at signature level so the SPI is
  type-checked end-to-end now.
- **`SourceLanguage.aliases` and `preludeFiles` defaulted to empty.**
  Lets a SourceLanguage author opt in without boilerplate; matches
  the spec's expected minimal plugin shape.

### Stage 1 — closed

All three iterations green: sbt `compile test` 117/117, conformance
38/38.  9 sbt subprojects build; `cli/assembly` produces a runnable
`ssc.jar`.  The SPI surface (Backend, SourceLanguage, Capabilities,
CompileResult, IntrinsicImpl, …) exists as types in `backend-spi/`
and is ready for Stage 5 to implement against.

---

## Stage 2 — IR + JSON / MsgPack codec

**Goal:** `NormalizedModule` exists and round-trips through both wire
formats.  No backend uses it yet — it's just the data layer.

### 2.1 `NormalizedModule` types in `ir/`
- Types per spec §5.2:  flattened definition list, explicit
  `Perform`/`Handle`/`Resume` placeholders (only stubs — actual
  lowering is Stage 3), `MatchTree`, `TailCall(...)`, resolved
  `SymbolRef`s, `ExternCall(qualifiedName, args)`,
  `EmbeddedBlock(language, source)`.
- A near-no-op `Normalize` pass: take today's typed AST, copy fields
  across with minimal rewriting (just `ExternCall` tagging + foreign
  block tagging).  Effect lowering lands in 3.1.

### 2.2 JSON + MsgPack codecs + round-trip test
- upickle derivation for every `NormalizedModule` node.
- Commit `schemas/ir.json` (generated from the types).
- New test: `IrRoundTripSpec` — for each conformance fixture, parse →
  typer → normalize → toJson → fromJson should equal the in-memory
  module.  Same for MsgPack.
- **Done when:** round-trip green on all 38 conformance fixtures, both
  formats.

#### Discovered while implementing 2.x

- **IR shape simplification.**  The plan said "flattened definition
  list" per spec §5.2.  Stage 2.1 keeps the nested
  `Section/subsections` tree instead — simpler round-trip and the
  flattening can land when a backend actually consumes IR (Stage 5).
  No semantic change; "definition list" is now a derived view, not a
  primary shape.
- **`schemas/ir.json` deferred to Stage 8.**  upickle has no schema
  emitter; hand-writing one for every case class is doc-shaped work
  best done with the rest of the SPI docs.  Tracked here so it
  doesn't slip.
- **`case object PatWildcard` derivation.**  Scala 3 `case object`
  inside a sealed trait deriving ReadWriter Just Works™ — no special
  handling needed.  Noting because it's the only case-object in IR
  and I wasn't sure upickle 4.x handles it cleanly.

### Stage 2 — closed

193 unit tests green (was 117; +76 round-trip).  Conformance 38/38.
IR layer is ready for Stage 3 (effect lowering populates Perform /
Handle / Resume nodes inside `Content.CodeBlock.body`).

---

## Stage 3 — Effect lowering (the migration)

**Goal:** the CPS effect analysis lives in one shared place; both code
generators consume it instead of carrying parallel copies.

**Scope revised after reading the code.**  The plan originally said
"`JvmGen`/`JsGen` lose ~250 lines each; instead they pattern-match on
`Perform`/`Handle`/`Resume` IR nodes."  Reality on `feature/backend-spi`:

- Backends consume `scalameta.Tree` directly, not IR.  Switching them
  to IR consumption is a **Stage 5** change (when backends migrate
  behind the SPI as plugins).
- What actually duplicates today is the CPS-analysis loop —
  `effectOps` + `effectfulFuns` fixed-point in both `JvmGen` and
  `JsGen` (~70-80 LOC each, nearly identical).
- The interpreter has no such analysis pass — `Computation.Perform`
  is produced at runtime when an effect op is evaluated.  Nothing to
  refit.

So Stage 3 here is the analysis extraction, no IR-consumption switch.
The IR `Perform`/`Handle`/`Resume` placeholders from Stage 2.1 stay
empty until Stage 5 wires backends through the SPI; then populating
them is incremental.

### 3.1 Extract `EffectAnalysis` to `core/transform/`
- New `core/transform/EffectAnalysis.scala`:
  ```scala
  case class Result(effectOps: Set[String], effectfulFuns: Set[String])
  def analyze(trees: List[scala.meta.Tree], builtins: Set[String]): Result
  ```
- JvmGen + JsGen call into it.  Each keeps its own `effectOps` /
  `effectfulFuns` mutable state (used during emission) but populates
  it from the shared analysis instead of its own loop.
- Built-in `Async` / `Storage` gating stays per-backend (JvmGen
  gates by `blocksUseAsync`; JsGen unconditionally adds them).
- **Done when:** both `analyzeEffects` methods reduced to thin
  adapters calling `EffectAnalysis.analyze`; suite green.

### 3.2 Cleanup + verification
- Confirm the shared analysis is the only fixed-point loop —
  no `analyzeEffects` LOC duplication remains.
- Verify line-count regression: both backends should *lose* LOC, not
  add it.
- **Done when:** effect-using conformance tests
  (`effects`, `signals`, `storage`, `async`, `async-parallel`,
  `std-monaderror`, `std-selective`) green on all 3 backends; net LOC
  delta across `backend-jvm/` + `backend-js/` is negative.

#### Discovered while implementing 3.x

- **`isEffectOpDef` was duplicated too.**  Moved to
  `EffectAnalysis.isEffectOpDef` along with the main algorithm.
  Both backends keep a tiny `private def isEffectOpDef = EffectAnalysis.isEffectOpDef`
  forwarder to avoid touching every call site (5 sites between the two).
- **JvmGen vs JsGen built-in gating diverges intentionally.**  JvmGen
  only pre-registers `Async.*`/`Storage.*` when the module actually
  uses them (saves emit work on lean modules); JsGen always registers.
  Both call shapes preserved as-is — the gating belongs at the
  adapter level (each backend knows its emission cost), not in the
  shared analyzer.
- **Net LOC delta: -100 in backends, +101 in core.**  +1 overall, but
  the +101 is heavier on docstrings than the extracted code was.
  Plan acceptance ("net LOC across backends is negative") satisfied.

### Stage 3 — closed

193 unit tests + 38 conformance green.  Effect analysis lives in
one place; both Free-monad backends consume it.  Stage 5 will
populate the IR `Perform`/`Handle`/`Resume` nodes once backends
move behind the SPI.

---

## Stage 4 — Capabilities + validation

**Goal:** programs that need a feature a backend doesn't support fail
**before** `compile()` with a human-readable diagnostic.

### 4.1 Tag backends with `Capabilities`
- Each of 4 backends declares `features` / `outputs` / `options` /
  `spiRange` per spec §11.  Initial set: everything they actually
  support today (everyone gets `AlgebraicEffects`, `MutableState`,
  `PatternMatching`, …; `js` lacks `TailCallOptimization`; …).
- Plus the platform-capability flags (`HttpServer`, `WebSockets`,
  `Auth`, `Crypto`, `Console`) — initially all 4 backends expose all,
  matching current reality.

### 4.2 `CapabilityCheck` walker + diagnostic plumbing
- New `core/validate/CapabilityCheck.scala`: walks `NormalizedModule`,
  collects required features, intersects with backend's set, emits
  `Diagnostic.Unsupported(feature, backend)` per miss.
- Wired into the pipeline between `Normalize` and `backend.compile`.
- New test:  a program using `algebraic effects` against a stubbed
  backend that doesn't declare `AlgebraicEffects` produces the
  expected diagnostic and does NOT call `compile()`.
- **Done when:** test green; existing conformance unaffected (every
  current backend declares every current feature).

#### Discovered while implementing 4.x

- **Capability declarations as standalone `val`** — backends don't yet
  implement the `Backend` trait (that's Stage 5).  Each backend module
  ships a top-level `val JvmCapabilities` / `val JsCapabilities` /
  `val ScalaJsCapabilities` / `val InterpreterCapabilities` in the
  same `scalascript.codegen` / `scalascript.interpreter` package.
  Stage 5 hooks each `val` into `override def capabilities` of the
  corresponding `Backend` impl — zero data motion, just wrapping.
- **Wiring into pipeline deferred.**  Plan 4.2 said "wired into the
  pipeline between Normalize and backend.compile".  Since nothing
  calls `backend.compile` yet (Stage 5 territory), there's no pipeline
  to wire into.  CapabilityCheck is callable; Stage 5 hooks it.
- **Detection is keyword-based.**  `Content.CodeBlock.source` is raw
  scalascript text (no parsed IrExpr nodes yet).  Detection uses
  word-boundary regexes (`\bvar\b`, `\bextension\b`, …) and substring
  scans for platform calls (`route(`, `onWebSocket`, `hashPassword`,
  …).  Imprecise compared to structural traversal but suitable for
  Stage 4's bar — "everyone declares everything → no diagnostics on
  real programs".  Stage 5+ swaps detector for `IrExpr` walker; same
  public API.
- **`InterpolatorPat` regex** — matches any `identifier"..."` shape
  for the `StringInterpolators` feature.  Catches `s"..."`,
  `html"..."`, `css"..."`, `md"..."`, plus user-defined interpolators
  identically.
- **Feature.values.toSet** — Scala 3's enum derives `values` (Array);
  test uses it to spin up "the most permissive backend possible".

### Stage 4 — closed

202 unit tests (193 + 9 new in CapabilityCheckTest) + 38 conformance
green.  Capability layer ready for Stage 5 to plug into Backend impls
and call `validate` before `compile`.

#### Discovered while implementing 5.x

- **Backend.compile signature vs codegen reality.**  Spec says
  `compile(ir: NormalizedModule, opts: BackendOptions): CompileResult`.
  All existing codegens consume `ast.Module` with parsed scalameta
  trees.  Resolved by adding `transform.Denormalize` (inverse of
  Normalize) so adapters can convert IR back to AST and call the
  existing `generate(...)` methods.  Round-trip cost: ~1 ms per
  compile, acceptable.  Post-Stage-5 cleanup: have codegens consume
  IR directly, remove both transform passes.
- **Streaming vs buffered output.**  Initial `InterpreterBackend.compile`
  buffered stdout into `Executed.stdout`.  Worked for one-shot
  programs but broke `run` / `watch` UX (long-running programs
  produce no visible output until exit).  Switched to streaming
  through `System.out` / `System.err`; `Executed.stdout/stderr` come
  back empty by design.  CLI prints nothing additional.
- **sbt-assembly + ServiceLoader.**  The merge strategy
  `META-INF/_* → discard` was eating META-INF/services/, breaking
  ServiceLoader at runtime.  Fix: `META-INF/services → concat`
  before the discard rule.  Caught only when bin/ssc reported
  "Unknown backend: 'int'" mid-conformance.  Will bite anyone who
  forks the build later.
- **Cross-backend integration test scope.**  Registry test belongs
  in cli/ — the only module that aggregates every backend on its
  classpath.  Added scalatest %Test to cli; tests assert all 4
  bundled adapters discovered, lookup by id, acceptedSources
  intersection, etc.
- **HTTP intrinsics deferral.**  Stage 5.4 as originally scoped is
  4–6 iterations of work (parser change + IR rewrite + per-backend
  emission refactor + per-package intrinsic migration).  Closed
  Stage 5 at 5.3 and proposed a post-Stage-5 multi-stage rollout
  (5+/A, 5+/B, 5+/C in plan).  Transitional `backendInterpreter
  dependsOn backendJs` stays.

### Stage 5 — closed at 5.3

209 unit tests (was 202; +7 BackendRegistryTest) + 38 conformance
green.  Stage achievements:

- 4 backend adapters implementing the SPI (`JvmBackend`,
  `JsBackend`, `ScalaJsPluginBackend`, `InterpreterBackend` /
  `InterpreterSession`).
- ServiceLoader discovery via `core/plugin/BackendRegistry`.
- 7 of ~12 CLI commands route through the registry; `--list-backends`
  prints all 4.
- Denormalize bridges IR → AST so existing codegens stay unchanged.

HTTP intrinsics (5.4) deferred to a follow-up multi-stage rollout —
preserves the spec contract without forcing a parser change in the
middle of Stage 5.

---

## Stage 5 — Convert existing backends to plugins

**Goal:** `JvmGen`, `JsGen`, `ScalaJsBackend`, `Interpreter` are
loaded via `ServiceLoader` through their own subprojects.  CLI doesn't
import any of them directly.

### 5.1 Adapter classes
- Each backend module gains a `backend/<name>/<Name>Backend.scala`
  implementing `Backend` (or `InteractiveBackend` for interpreter).
- `compile()` delegates to the existing `generate(...)` /
  `Interpreter.run(...)` calls; result wrapped in
  `CompileResult.TextOutput` / `Segmented` / `Executed`.
- `META-INF/services/scalascript.backend.spi.Backend` per module.
- **Done when:** `ServiceLoader.load(classOf[Backend])` returns 4
  entries; existing suite green via the adapters.

### 5.2 `BackendRegistry` in `core`
- Discovery: `ServiceLoader` + `~/.scalascript/plugins/*.jar` +
  `$SCALASCRIPT_PLUGIN_PATH` + CLI flags.  Each plugin in its own
  `URLClassLoader` rooted at the SPI loader (spec §12.1).
- `lookup(id)` → `Option[Backend]`.
- **Done when:** registry test asserts the 4 bundled backends present
  and isolated from each other's classpath.

### 5.3 CLI moves to registry lookup
- `cli/Main.scala` stops importing `JvmGen`, `JsGen`,
  `ScalaJsBackend`, `Interpreter`.  Every command goes through
  `registry.lookup(id).get.compile(...)`.
- `WebServer` + `Routes` move to registry lookup for the engine
  (defaults to `interpreter`).
- **Done when:** grep for `import scalascript.codegen` and
  `import scalascript.interpreter` outside the backend modules
  returns zero hits.

### 5.4 Extract HTTP intrinsics — DEFERRED

After scoping during 5.1–5.3 it became clear that **full HTTP
intrinsic extraction is multi-stage work**, not a single iteration.
Required pieces:

  1. **`extern` modifier in the parser** (or convention like
     `__extern__` body marker) — every recognised intrinsic needs a
     parseable declaration.
  2. **Typer rules** to treat `extern def` symbols as intrinsic call
     sites rather than ordinary defs.
  3. **`std/http.ssc` prelude file** with `extern def serve / route /
     stop`, `Request` / `Response` case classes.
  4. **Normalize pass extension** to rewrite calls to extern symbols
     into `ir.ExternCall(qualifiedName, args)` IR nodes.
  5. **Each codegen's emission swap** — `JvmGen`'s hardcoded
     `route(...)` pattern-match (~150 LOC), `JsGen`'s Node-http
     emission (~200 LOC), `Interpreter`'s `nativeP("serve")` block
     (~100 LOC) — all replaced by intrinsic-table consultation.
  6. **WS / auth / FS / crypto** — same shape for every platform
     package in spec §8.

Each of those is at least one iteration.  Together they're a
**Stage 5.5+ multi-session effort** — bigger than the rest of
Stage 5 combined.

For now:

- The transitional `backend-interpreter dependsOn backend-js`
  stays in `build.sbt` (`server/WebServer.scala` still imports
  `codegen.JsGen` for the SPA runtime preamble).  Tracked here.
- `nativeP("serve")` / `nativeP("route")` etc. stay in
  `Interpreter.scala`.
- `route` / `serve` keyword recognition stays inline in `JvmGen` /
  `JsGen`.

**When this matters next:** the moment a new backend (WASM, .NET, …)
needs HTTP support — the HTTP code can't be re-implemented in every
backend.  Until then the intrinsics extraction is pure architecture
work without a concrete consumer, so defer.

Concrete next-stage proposal (post-Stage 5):

- **Stage 5+/A — Intrinsic plumbing.**  Add the `extern` parsing,
  the `ExternCall` IR-node populated, the intrinsic-table
  consultation at emit time.  Migrate ONE intrinsic (e.g.
  `Console.println`) end-to-end as a proof point.  ~1 iteration.
- **Stage 5+/B — `std.http` extraction.**  Move HTTP through the
  pipeline established in 5+/A.  ~2 iterations.
- **Stage 5+/C — `std.ws`, `std.auth`, `std.fs`, `std.crypto`.**
  Same pattern.  ~1 iteration each.

---

## Stage 6 — Out-of-process loader

**Goal:** a subprocess plugin speaking `stdio-json` or `stdio-msgpack`
is indistinguishable from an in-process plugin from core's POV.

### 6.1 Protocol implementation
- `core/plugin/Subprocess.scala`: framed messages, role-aware
  dispatch.  Both wire formats.
- `plugin.yaml` parser + loader (spec §12.2).
- `core/plugin/HostCallbacks.scala` for the `HostCallback` intrinsic
  variant (out-of-proc backends can call back into core).

### 6.2 Smoke-test plugins
- Two in-tree examples per spec Phase 6:
  - `examples/plugins/canned-backend/`: 50-line scala-cli backend,
    returns a fixed `TextOutput`.
  - `examples/plugins/toml-source-language/`: 50-line scala-cli
    SourceLanguage for `toml` blocks; returns one `SymbolExport`.
- CI runs them through the subprocess loader.

### 6.3 Protocol doc
- `docs/backend-spi-protocol.md` — every method, every framing.
  Self-contained enough that a non-Scala plugin author can implement
  against it.

---

## Stage 7 — CLI ergonomics

### 7.1 Plugin-management flags
- `--plugin <jar>`, `--plugin-dir <dir>`, `--target <id>`,
  `--list-backends`, `--describe-backend <id>`.
- `Main.scala` becomes pure dispatcher.

### 7.2 WebServer / runtime backend selection
- `--backend` flag for `serve` / examples runner; defaults to
  `interpreter`.

---

## Stage 8 — Docs & sample plugin

### 8.1 Doc rewrite
- `docs/architecture.md` §4 rewritten against post-SPI reality (it's
  currently aspirational).
- `docs/writing-a-backend.md`:  walk through a no-op backend in
  <100 lines.

### 8.2 Sample plugin
- `examples/plugins/hello-backend/` — buildable, in its own
  scala-cli project.  README walks a third-party author through:
  declare → implement `compile` → register → install → invoke.

---

## Stage 9 — Extract bundled SourceLanguage plugins

**Goal:** `core` knows only Markdown + `scalascript`/`ssc`.  `html`,
`css`, `scala` blocks live in bundled plugins on equal footing with
third-party.  This is the radical core-simplification step; spec
§14 Phase 9 is the locked design.

### 9.1 `backend-scala-source` plugin
- `SourceLanguage` for `scala` fence blocks.  Wraps existing
  scalameta parsing; produces `EmbeddedSource(scala, …)` IR or a
  lowered fragment depending on consumer.
- Move scalameta dependency out of `core` into this plugin.

### 9.2 `backend-html` plugin
- `SourceLanguage` for `html` blocks + `html"…"` interpolator.
- Owns `Html` type, the `containerTagNames` / `voidTagNames` lists
  currently in `JvmGen.scala:1415,1425`.
- Ships `preludeFiles` defining `Html` and the DSL tag bindings
  (`div`, `p`, `body`, …).
- `extern def render(h: Html): String` with default body in plugin
  prelude (Stage 4 optional-intrinsic pattern).

### 9.3 `backend-css` plugin
- Same shape as 9.2 for `Css`.

### 9.4 Remove duplicated handling from core / backends
- `Lang.scala` collapses to `isScalaScript`.  Drop `isStringBlock`,
  `isParseable` (or reduce to scalascript-only).
- `JvmGen` preamble loses container/void tag lists and `_Raw`
  emission.
- `Interpreter` loses `nativeP("div")` block (line 485) and
  `renderStringBlock` paths for html/css.
- Interpolator match arms for `html` / `css` in `JvmGen`, `JsGen`,
  `Interpreter` deleted.
- Verify by build:  a CLI build *without* the three plugins on
  classpath compiles, runs `.ssc` files with `scalascript` blocks
  fine, and reports `Diagnostic.UnknownBlockLanguage` for
  `html`/`css`/`scala`.
- **Done when:** zero `if lang == "html" || lang == "css"`-style
  checks anywhere in `core` after this stage.

---

## Stage 10 — Final integration

- Rebase onto current `origin/main`; resolve any drift.
- Full check suite (`sbt test`, `scala-cli conformance/run.sc`,
  every relevant e2e smoke).
- Update `README.md` ("What works" matrix), `SPEC.md` (backend
  abstraction reference), `MILESTONES.md` (move "Backend SPI v0.1"
  from forward section into history; surface any follow-ups that
  appeared along the way).
- Delete `docs/backend-spi-plan.md` once milestone is in history
  (its surviving content moves into MILESTONES).
- Single merge commit (or fast-forward) into `main`.  Push once.
- `ExitWorktree(action: "remove")`.

---

## Open questions surfaced while reading

Spec is largely closed.  Two execution-time decisions worth flagging:

1. **`bench/WsStress`** has its own `main` and forces the
   `--main-class scalascript.cli.ssc` flag in `scripts/install.sh`
   today.  Stage 1.2 moves it to a `bench/` subproject so the
   workaround can disappear; the only question is whether `bench`
   should depend on `backend-interpreter` (to share the runtime it
   stresses) or stand alone.  Recommend depend — avoids drift.

2. **Server intrinsics scope.**  The spec §8 lists HTTP + WS + auth
   + crypto + FS as the canonical intrinsic packages.  Today
   `server/*.scala` is ~10 files mixing transport (WS framing),
   protocol (HTTP server), and auth primitives (JWT, OAuth, Totp,
   WebAuthn).  Stage 5.4 extracts HTTP; the rest (WS, auth) is in
   scope but might be a separate iteration (5.5? 5.6?) if 5.4 grows
   beyond a session.  Decide when we get there based on actual size.

Anything else that surfaces during execution: append here under a
**"Discovered while implementing"** section per stage, then carry to
`MILESTONES.md` on the final merge if it's a separate follow-up.

---

## Status

| Stage | Iterations done | Notes |
|-------|-----------------|-------|
| 1     | 3 / 3           | **Stage 1 closed.** 1.1 sbt scaffold; 1.2 sources moved (compiler/ gone, sbt-assembly added); 1.3 SPI trait stubs + IR placeholders (ir/Ir.scala + 10 SPI files in backend-spi/). Transitional `backendInterpreter dependsOn backendJs` for `WebServer→JsGen` — Stage 5 fixes via HTTP intrinsics. |
| 2     | 2 / 2           | **Stage 2 closed.** 2.1 IR + Normalize; 2.2 upickle `derives ReadWriter` on every IR data type + 76 round-trip tests (38 fixtures × JSON+MsgPack).  `schemas/ir.json` deferred to Stage 8 (docs). |
| 3     | 2 / 2           | **Stage 3 closed.** EffectAnalysis extracted to `core/transform/`; JvmGen + JsGen are now thin adapters.  LOC delta: JvmGen -48, JsGen -52 (-100 in backends), +101 in core.  IR-consumption switch deferred to Stage 5. |
| 4     | 2 / 2           | **Stage 4 closed.** 4 Capabilities values declared (1 per backend module); CapabilityCheck.detect + validate; 9 new tests (6 detect + 3 validate).  Detection coarse (keyword scan on `Content.CodeBlock.source`) until Stage 5+ populates IrExpr nodes. |
| 5     | 3 / 4           | **Stage 5 closed at 5.3.** 5.1 adapters + Denormalize; 5.2 BackendRegistry; 5.3 CLI registry dispatch (7 of ~12 commands). 5.4 (HTTP intrinsics extraction) deferred — see preamble; full scope is multi-stage post-Stage-5 work. |
| 6     | 3 / 3           | **Stage 6 closed.** 6.1 wire protocol + SubprocessBackend; 6.2 plugin.yaml + registry discovery; 6.3 canned-backend smoke plugin + `docs/backend-spi-protocol.md`. |
| 7     | 2 / 2           | **Stage 7 closed.** GlobalFlags preprocessor (--plugin / --plugin-dir / --target / --backend / --list-backends / --describe-backend); BackendRegistry.addPluginJar via URLClassLoader; addPluginDir extends plugin.yaml search.  `--backend` lets `run`/`compile`/`emit-*` cross-dispatch. PluginManifest.executablePath now distinguishes PATH-resolvable commands (no slash) from relative paths. 6 new GlobalFlags tests. |
| 8     | 2 / 2           | **Stage 8 closed.** architecture.md §4 + Extension Points + Directory Structure rewritten against post-SPI reality; new docs/writing-a-backend.md walks no-op backend in <100 lines; examples/plugins/hello-backend/ buildable scala-cli project (~30 LOC + META-INF entry) — verified end-to-end with `--plugin /tmp/hello-backend.jar --backend hello run`. |
| 9     | 0 / 4           | Not started |
| 10    | 0 / 1           | Not started |
