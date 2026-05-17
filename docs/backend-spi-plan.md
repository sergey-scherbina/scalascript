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

---

## Stage 3 — Effect lowering (the migration)

**Goal:** effects live as IR nodes; `JvmGen` / `JsGen` lose ~250 lines
each.  Risk concentration — largest behavioural change in this whole
plan.

### 3.1 `EffectLowering` pass in `core/transform/`
- Move `JvmGen.analyzeEffects` (line 234) and `JsGen.analyzeEffects`
  (line 2814) into a single core pass that produces `Perform`/`Handle`/
  `Resume` IR nodes.
- Existing in-line `_run` / `_perform` / `_handle` emission in each
  codegen becomes a straight pattern-match on these nodes.
- **Done when:** `effects`, `signals`, `storage`, `async`,
  `async-parallel`, `std-monaderror`, `std-selective` conformance
  tests still green on all 3 backends.

### 3.2 Interpreter-side: use the same IR
- The interpreter's `Computation` already mirrors this shape; refit it
  to consume `Perform`/`Handle`/`Resume` from `NormalizedModule` rather
  than re-deriving them from raw AST.
- **Done when:** `InterpreterTest` + effect-using conformance tests
  green; line-count regression in `Interpreter.scala` is non-negative
  (we're not adding mass while moving structure).

### 3.3 Cleanup: delete the dead duplicated paths
- Remove `analyzeEffects` from `JvmGen` and `JsGen` entirely (the
  pass is gone — they now consume IR).
- **Done when:** zero matches for `analyzeEffects` in `backend-*`
  modules; suite still green.

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

### 5.4 Extract HTTP intrinsics
- `std/http.ssc` in prelude:  `extern def serve(port)`, `route`,
  `stop`, `Request`, `Response` case classes.
- Each backend exposes its existing HTTP runtime through
  `Backend.intrinsics` (spec §8 — `InlineCode` / `RuntimeCall`).
- Hard-coded `nativeP("serve")` etc. in `Interpreter` and the inline
  HTTP emission in `JvmGen` / `JsGen` go through the intrinsic table.
- **Done when:** `examples/hello-server.ssc` + auth/REST examples
  still run identically; no `route` / `serve` string-literal matches
  in `backend-*` modules outside the intrinsic registrations.

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
| 2     | 0 / 2           | Not started |
| 3     | 0 / 3           | Not started |
| 4     | 0 / 2           | Not started |
| 5     | 0 / 4           | Not started |
| 6     | 0 / 3           | Not started |
| 7     | 0 / 2           | Not started |
| 8     | 0 / 2           | Not started |
| 9     | 0 / 4           | Not started |
| 10    | 0 / 1           | Not started |
