# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

Driven by the agreed roadmap (BACKLOG.md → "Roadmap — agreed priority order, 2026-06-17").
Work top-to-bottom, one major theme at a time. **Maven/centralized publication is LAST.**

### ▶ JS-runtime + polyglot follow-ups (2026-06-22 eve, with Sergiy — "запиши в спринт все эти задачи и делай автономно")

Queued after the JS `.mjs`-resource cleanup + rename. Drive top-to-bottom (tractability order).

- [ ] **optics-emit-lib-cli** — make the optics npm packager USER-reachable. `JsLibPackager.opticsNpmPackage`
      exists + is node-proven but only callable from a test. Add CLI `ssc emit-lib --host js --feature optics
      -o <dir>` that writes the 3 package files. New `final class EmitLibCmd extends CliCommand` (mirror
      `EmitCommands.scala` `EmitRustCmd`'s dir-write) + register in `META-INF/services/scalascript.cli.CliCommand`
      (ServiceLoader-backed `CommandRegistry`; cli already dependsOn backendJs). + example + README capabilities/CLI
      row + user-guide subsection. Test: assert the 3 files written. **CLAIMED (bright-quail).**
- [ ] **jvm-rust-runtime-resources** — mirror the JS `.mjs`-resource cleanup (polyglot §3 #8) for JVM
      (`JvmGenRuntimeSources`) + Rust (`RustRuntimeTemplates`) big runtime-string constants → resource files via
      a small cached loader (like `JsRuntimeResource`). **PROBE FIRST:** if the strings are `s"…"`-interpolated
      or computed (not pure literals), they can't move to a static resource as-is — scope honestly (migrate the
      pure ones, document the rest). Byte-identical discipline: `diff`-verify each vs `git HEAD`. Closes §3 #8 for
      the remaining backends. Spec: extend `specs/js-runtime-resources.md` or a sibling.
- [ ] **optics-jvm-facade** — Phase 2 next host (`specs/polyglot-libraries.md` §4/§6): publish optics as a JVM
      jar facade + golden API-signature test. Optics has no `.ssc` defs (AST-level) → author a thin Scala facade
      object `Ssc.Optics` (or a `.ssc` facade) over the same 4 optic shapes; reuse `FacadeGenerator`/`ssc link
      --emit-scala-facade`/`JarCommands`. Golden: mirror the JS `optics.d.ts` golden with a Scala signature golden.
      Then Rust crate + Java facade follow (same packager shape). Bigger; slice per host.
- [ ] **rust-effects-multishot-r6** — multi-shot algebraic effects on Rust (resume re-invoked, e.g. NonDet). One-
      shot already shipped (`a87afba34`, tagless-final). RESEARCH: probe whether a captured-closure continuation
      (`Box<dyn FnMut>`) or CPS/defunctionalized re-entry is tractable in `RustCodeWalk`'s handle lowering; if not
      bounded, SCOPE DOWN + document the blocker in `specs/rust-effects.md` §R.6 + BACKLOG. Lower confidence.

### ▶ Newly queued (2026-06-22, with Sergiy — "бери все эти задачи если других нет, заноси в спринт")

Queued after closing rust-web-toolkit follow-ons + fixing the index-read move bug it shipped.

- [x] **worktree-guardrail** ✓ DONE 2026-06-22 (`bffef3447`, mellow-shrew, with Sergiy) — structural fix so
      feature commits can't land in the shared `main` checkout again (root cause of the parked-feature-branch
      mess: a prior session committed rust-web-toolkit directly in shared main instead of a worktree, partly
      due to the `EnterWorktree` false-positive, claude-code #27881). **`.githooks/pre-commit`** blocks a
      non-`.work/` commit when in the main checkout (`git-dir==git-common-dir`) OR on branch `main`; feature
      worktrees unaffected; `--no-verify` escape hatch. **`scripts/new-worktree <name>`** = external-path
      worktree recipe (NOT under `.worktrees/`, which siblings prune). **`scripts/setup-hooks`** sets
      `core.hooksPath`. Spec `specs/worktree-guardrail.md`; `scripts/test-worktree-guardrail` 5/5.
      **ACTIVATED** on the shared repo (`core.hooksPath=.githooks`) + verified live: a feature commit in
      shared main is refused, a `.work/` coordination commit passes. (Other clones: run `scripts/setup-hooks`
      once; worktrees off current `origin/main` already carry `.githooks/`.)

- [x] **rust-cargo-smoke-coverage** ✓ DONE 2026-06-22 (`2c8032a5c`, mellow-shrew) — `RustGenCargoSmokeTest`:
      a Rust-toolchain-gated suite (`assume(cargoAvailable)` — probes `cargo --version` directly, since
      `backendRust` doesn't depend on the CLI's `RustToolchain`) that emits a feature-exercising program
      to a temp crate, `cargo run`s it, and asserts real stdout. Covers collection ops (take/drop/
      takeRight/dropRight/sorted/distinct/sum), string ops (replace/startsWith/endsWith/contains), and
      the `Vec<String>` index-read regression (E0507). Closes the move/borrow/type bug class the
      string-match suite can't see. `backendRust` 236/0. BACKLOG `rust-backend-cargo-smoke-coverage` landed.

- [x] **metaprogramming-v2-track-c2** ✓ DONE 2026-06-22 (mellow-shrew, with Sergiy — CONSERVATIVE slice).
      Probed first: the full ambition (Typer over expanded code + map errors to `.ssc` positions) is a real
      trap — both expanders flatten trees→string→re-parse (positions destroyed; a position map would have to
      be built inside 4 hand-written char-scanners) AND full inference over expanded macro-runtime constructs
      risks false positives (confirmed; spec deferred it for good reason). Built the SAFE slice instead:
      `MacroCodegen.expansionTypeWarnings` (wired into `ssc check` `checkOneFile`) catches a macro/inline
      **expansion** that references an undefined name (source type-checks, expansion doesn't). **Zero false
      positives** via a pre/post `Reference to undefined name` DIFF (machinery cancels; user's own undefined
      names stay with the normal check); warning-only; file-level (no position map); excludes builtins/stripped
      names/`_`-helpers; never breaks `ssc check`. Reach is bounded by the strict Typer's position-sensitive
      undefined-name check (val-rhs/bare-stmt). `MacroCodegenTest` +5 (broken→1, valid const-fold/direct-quote/
      interpreter→0, no-op→0); core artifact+typer 496/0; verified end-to-end via `ssc check`. Spec
      `specs/arch-metaprogramming-v2.md` C2 updated. DEFERRED still: precise positions + full-inference recheck.

### ▶ emit-js whole-program effect analysis (2026-06-22, with Sergiy — "берись, запиши в спринт, напиши спеку, и делай") — busi-reported #3, transitive piece

Closes the last open piece of the emit-js effect-handler cluster (BUGS.md
`jsgen-emitjs-effect-handler`; #1/#2/#4 done, #3 core done on `6def53541`, #5
documented). Spec: **`specs/emitjs-effect-whole-program.md`**. The per-module
`EffectAnalysis` doesn't see effects reachable through a 3+-level import chain
(busi: `ledger.accountBalance` → `journal.query` → `Journal`), so a function
calling a transitively-imported effectful function isn't CPS-lowered and its Free
value leaks at runtime. Raw `emit-js` of such a program throws on Node; the JIT
path is fine.

- [x] **emitjs-effect-whole-program** ✓ DONE 2026-06-22 — busi `ledger.ssc` (+ obligation/plan/payment/gate/income) now run end-to-end as raw `emit-js` standalone bundles on Node; guard `tests/conformance/effect-transitive-handler.ssc` (3-level, INT==JS==JVM); busi `make v2-test`+`v2-test-js` + cross-backend green. (1) `JsGen.analyzeEffects` collects trees
      recursively across the import graph (reuse `genImport`'s resolution; parse
      once; visited-set for cycles) and runs `EffectAnalysis.analyze` on the union;
      (2) `effectOps`/`effectfulFuns`/`multiShotEffects` become shared constructor
      params threaded to child gens (like `topLevelConsts`), populated once by the
      entry gen's whole-program pre-pass; (3) drop the now-redundant per-`genImport`
      `analyzeEffects`+merge. Guard: `tests/conformance/effect-transitive-handler.ssc`
      (3-level, INT==JS==JVM) + `ssc emit-js tests/v2/ledger.ssc | node` runs e2e +
      `CrossBackendPropertyTest`/conformance/busi `make v2-test`+`v2-test-js` green.

- [x] **emitjs-standalone-frontiers** ✓ DONE 2026-06-22 (claude-code, `fix/js-standalone-frontiers`) —
      closes the three remaining busi standalone-bundle frontiers recorded under
      `jsgen-emitjs-effect-handler` so `tests/v2/{trust,qr}.ssc` now run end-to-end as raw
      `emit-js | node` bundles and `ksef.ssc` passes `node --check`. Three JS-codegen fixes +
      one refinement: (1) `Term.ApplyUnary` CPS-lowers an effectful operand (`!x`/`-x`) via
      `_bind` instead of `_run`-wrapping it outside the handler (fixes `trust.ssc`); (2) `_dispatch`
      routes `Array.fill/tabulate/range/empty` to the `List` companion since `Array(...)` emits a
      bare native-constructor value (fixes `qr.ssc`); (3) the 14 std/fs file-ops are seeded into
      `declaredBindings` so importing them never re-emits a colliding top-level `const readFile`
      (fixes `ksef.ssc` syntax); (4) refined the `fn-typed-field` `_dispatch` guard from a blanket
      "_type instance → return field as-is" to a precise variadic-lambda check, so genuine zero-arg
      methods (`JsonValue.asString`) auto-invoke again (`json-value` FAIL→PASS). Guards:
      `tests/conformance/{js-applyunary-effect-cps,array-companion-statics}.ssc` + the existing
      `fn-typed-field`/`json-value`. **Before/after emit-js+node sweep over all 113 conformance
      tests: zero PASS→FAIL regressions** (82→85 PASS); busi `make v2-test`+`v2-test-js` green
      (26 files, both backends).

- [x] **emitjs-standalone-capability** ✓ DONE 2026-06-22 (claude-code) — the follow-on frontier:
      emit `nowMillis` (clock) + crypto capabilities into the raw `emit-js` standalone bundle so
      `inbox`/`ksef`/`repo*` run under `ssc emit-js | node`. Two bugs (see BUGS.md
      `jsgen-emitjs-capability-standalone`): (1) a `RuntimeCall` intrinsic (`nowMillis`→`Date.now`)
      reached via the CPS path wasn't rewritten — `genCpsApply` now applies it (new helper
      `intrinsicRuntimeTarget`); (2) a `std/crypto` extern (`sha256`) bound to the `undefined` host
      stub and shadowed its `_sha256` intrinsic — `genObjectAsExpr` now falls back to the intrinsic
      target (guarded by `typeof` + `target != fname` so std/auth's identity webauthn externs don't
      self-reference→TDZ). Standalone emit-js+node sweep **13/21 → 20/21** v2 domain files; guards
      `tests/conformance/{js-cps-intrinsic-rewrite,js-crypto-extern-standalone}.ssc` (INT==JS);
      before/after conformance sweep **zero PASS→FAIL** (84→84); busi `make v2-test`+`v2-test-js`
      green. **Remaining:** `auth.ssc` standalone needs Node WebAuthn impls (host-only externs, no
      `_webauthn*` preamble) — a separate feature, not a capability-emission gap.

### ▶ Core-minimization + polyglot-libraries program (2026-06-22, with Sergiy — "минимизировать ядро всех рантаймов и компиляторов, все вынести в библиотеки и плагины" + "сделать все переиспользуемым со всех рантаймов — из скалы, джавы, джаваскрипт, раста — в виде библиотек, сначала написать спеку")

Two complementary directives, ONE program. **Design spec written: `specs/polyglot-libraries.md`**
(grounded in a full core-vs-plugin extraction analysis). A self-contained module is the unit of reuse:
extract a feature behind the SPI (A) → publish it as a per-host library (B) is the same artifact.

- [~] **polyglot-libraries-spec** ✓ SPEC DRAFTED 2026-06-22 — `specs/polyglot-libraries.md`. Unifies A (minimize
      core) + B (cross-language reuse). Key findings: the SPI/plugin spine exists (40 std + 13 backend plugins,
      `IntrinsicImpl`, lazy loading) but ~6–7.5K LOC of FEATURE code is still baked into the interpreter core
      because the SPI can register named intrinsics but NOT (i) block-forms (`runLogger`/`runActors`/`httpClient`
      — 27 hardcoded in `EvalRuntime`), (ii) effect handlers (`Perform` resolvers in `EffectHandlers.scala`), or
      (iii) Typer prelude symbols (`effectBuiltins`/`pluginObjects`/`pluginBuiltins` — ~150 hardcoded names).
      The keystone = 3 small additive SPI hooks (`blockForms`, `effectHandlers`, `preludeSymbols`/`typeSignatures`).
- [x] **core-min-phase1-logger-keystone** (A — the SPI keystone) ✓ KEYSTONE PROVEN END-TO-END 2026-06-22. The
      block-form + effect-handler plugin SPI now works: a plugin can contribute a `keyword { body }` effect-runner
      and the interpreter dispatches to it. 5 increments on origin/main: (1) `c2eec8d3c` generic effect trampoline
      `EffectHandlers.runWithHandler`; (2) `f2d8b5304` SPI contract `BlockForm`/`EffectHandler`/`BlockContext`;
      (3) `7dc508c3b` made it **type-safe** — a host-neutral `SpiValue` ADT instead of `Any` (per Sergiy's review);
      (4) `af58335bc` interp wiring — `valueToSpi`/`spiToValue`, a `_blockForms` registry populated by
      `installPlugins`/`ensurePluginsLoaded`, and an `EvalRuntime` generic block-form case; (5) `0a578ab88` **proof**:
      `reservedApplyHeads` fast-path also excludes `interp.blockForms` names so a plugin keyword reaches the
      dispatch (empty until a plugin loads → plugin-free scripts unchanged). `BlockFormSpiTest`: a `runTally { }`
      plugin block-form + stateful handler → `25`, Int args/replies round-tripped `Value↔SpiValue`. **No
      regression** (StdEffectsTest 48/0, InterpreterTest 141/0). **Mechanical follow-up (each effect now a
      template):** migrate Logger off the hardcoded `EvalRuntime` cases into a `logger-effect-plugin` (a `BlockForm`
      per `runLogger`/`runLoggerJson`/`runLoggerToList`) + remove `loggerRun` from core + make the plugin
      default-loaded; then clock/random/env/state/actors. The SPI being proven is the hard part; the migrations are
      copy-the-template.
- [x] **core-min-logger-migrate** (A) — ✓ DONE 2026-06-22 (`0353e51ae`). Logger fully extracted from
      interpreter core into `runtime/std/logger-effect-plugin` (`LoggerEffectPlugin extends Backend` with
      `blockForms = Map(runLogger→text, runLoggerJson→json, runLoggerToList→collect-with-`result`-tuple)`,
      handlers over `SpiValue`/`ctx.out`) + `META-INF/services/scalascript.backend.spi.Backend`; build.sbt wired
      via the `allPlugins` registry (`PluginSpec("logger", …)` → auto aggregate + `installBin` + plugin-tests
      classpath). Removed from core: 3 `runLogger*` cases + the 3 names in `reservedApplyHeads` (`EvalRuntime`),
      `loggerRun`/`loggerToListRun`/`loggerJsonStr` (`EffectHandlers`; generic `runWithHandler` stays). The 4
      Logger tests moved `StdEffectsTest`→`LoggerPluginTest` (`interpreter-plugin-tests`) and run with NO
      `installPlugins` — proving production lazy-ServiceLoader dispatch. Verified: StdEffectsTest+InterpreterTest
      **185 green**, LoggerPluginTest+BlockFormSpiTest **7 green**. **This is the reusable template** —
      clock/random/env/state/actors copy it (see `core-min-phase3plus`).
- [x] **core-min-random-migrate** (A) — ✓ DONE 2026-06-22 (`2d525ea59`). Random extracted to
      `runtime/std/random-effect-plugin` (`RandomEffectPlugin`; one `RandomBlockForm` registered under both
      `runRandom` and `runRandomSeeded`; per-block `java.util.Random`, replies over `SpiValue` —
      nextInt/nextDouble/uuid/pick, `pick` round-trips arbitrary list elements via `SpiValue.Opaque`). **This
      slice GENERALIZED the block-form SPI to CONFIG ARGS** — `keyword(config…){body}`, not just `keyword{body}`:
      `dispatchBlockForm` now evaluates leading config terms → `newHandler(ctx, cfgArgs)` (the seed). Added the
      generic *curried* block-form cases in `EvalRuntime` (loaded + lazy-load mirror), placed AFTER all hardcoded
      curried special-forms (runClockAt/runEnvWith/httpClient/…) so they only catch genuinely-unmatched applies.
      Removed core `randomRun` + 2 cases + 2 `reservedApplyHeads` names. Tests moved
      `StdEffectsTest`→`RandomPluginTest` (no `installPlugins`). Verified: StdEffectsTest+InterpreterTest **179
      green**, RandomPluginTest+LoggerPluginTest+BlockFormSpiTest **13 green** + full-suite sweep.
- [x] **core-min-clock-env-migrate** (A) — ✓ DONE 2026-06-22. Clock + Env extracted to
      `clock-effect-plugin` + `env-effect-plugin` (one effect = one library). Both curried-config siblings, so
      they REUSE the config-args SPI path from `core-min-random-migrate` with ZERO new dispatch machinery:
      `runClockAt(t0)` → `newHandler` reads frozen-ms; `runEnvWith(map)` → reads the overlay (exercises the
      SPI's `MapV` config path). `ClockBlockForm`/`EnvBlockForm` registered under both plain+curried keywords;
      handlers reply over `SpiValue` (Clock now/nowIso/sleep, frozen=no-op; Env get/set/required with per-block
      mutable overlay + real-`getenv` fallback). Removed core `clockRun`/`envRun` + 4 cases + 4
      `reservedApplyHeads` names. Tests moved `StdEffectsTest`→`ClockPluginTest`+`EnvPluginTest`. Verified:
      interpreter **169 green**, full plugin-tests **647 green** (1 env-gated cancel). FOUR effects are now
      plugins: Logger, Random, Clock, Env.
- [x] **core-min-state-migrate** (A) — ✓ DONE 2026-06-22. State extracted to `state-effect-plugin`. State is
      the first NON-pure-reply effect: `State.modify(f)` must *apply a ScalaScript closure*, which the
      pure-reply SPI couldn't do. **Grew the SPI by exactly one capability — `BlockContext.applyFn(fn, args)`**
      (defaulted to throw → backward-compatible; the interpreter overrides it, routing back through
      `callValue` + synchronous `Computation.run`, parity with the old `callValue1`). `StateBlockForm` under
      `runState`; `newHandler` takes the initial state (config arg); get/set/modify reply over `SpiValue`;
      the `result` hook returns `(finalState, bodyResult)`. Removed core `stateRun` + case + `reservedApplyHeads`
      name. Tests `StdEffectsTest`→`StatePluginTest`. Verified: interpreter **165 green**, full plugin-tests
      **651 green** (1 env cancel). **FIVE effects now plugins: Logger, Random, Clock, Env, State.** Probed and
      recorded: the REMAINING runners (Retry/Cache/Http/Actors) also need interp callbacks — Retry/Cache via
      `applyFn` (thunks); Http additionally needs to construct a `Response` record (no `SpiValue` record case
      yet → would need a `BlockContext.makeRecord` or an Opaque-instance helper); Actors need the message loop.
- [x] **core-min-retry-cache-migrate** (A) — ✓ DONE 2026-06-22. Retry + Cache extracted to `retry-effect-plugin` +
      `cache-effect-plugin`, copying the State template (both re-invoke the body thunk via `BlockContext.applyFn`).
      `RetryBlockForm(sleep)` under `runRetry`/`runRetryNoSleep`; `CacheBlockForm(bypass)` under
      `runCache`/`runCacheBypass`. The Cache TTL store moved into the plugin (process-local `object CacheStore`,
      was `interp._cacheStore`); per-block `bypass` replaces the `_cacheBypass` ThreadLocal (each block's handler
      carries it; trampoline dynamic-scope == ThreadLocal). Removed from core: 4 `EvalRuntime` cases + 4
      `reservedApplyHeads` names; `EffectHandlers.retryRun`/`cacheRun`; `Interpreter._cacheStore`/`_cacheBypass`.
      Wired into `allPlugins` (auto aggregate + plugin-tests classpath) + the explicit `pluginPkgs` installBin list.
      Tests moved `StdEffectsTest`→`RetryPluginTest`(3)+`CachePluginTest`(2) (no `installPlugins`, lazy dispatch).
      Verified: plugin-tests **656/0** (1 env-gated cancel) + InterpreterTest+StdEffectsTest **160/0**. **SEVEN
      effects now plugins: Logger, Random, Clock, Env, State, Retry, Cache.** NOTE: emitters (`Retry`/`Cache`
      globals in `StdEffectsRuntime`) stay in core per the State precedent — only the heavy handlers move.
- [ ] **polyglot-phase2-optics-allhosts** (B) — prove per-host library packaging on the EASY case: publish the
      PURE optics feature to all four hosts (JVM jar + Java facade + npm + Rust crate) with a golden API-signature
      test per host. Spec §4 + §6. **BLUEPRINT (Explore, 2026-06-22) — load-bearing:**
      • Optics is **NOT** a `.ssc` module or named intrinsics — it's AST-level: `Focus[T](_.a.b)`
        (`EvalRuntime.scala:4591`→`OpticsRuntime.evalFocus`) + `Prism[Outer,Variant]` (`:4318`→`buildPrism`); JS at
        `JsGen.scala:4542`/`3746`, runtime `JsRuntimeOptics.scala` gated by `Capability.Optics`. **There is no
        exported symbol table to read — the public facade must be AUTHORED.** The canonical contract is the 4 synth
        optic shapes: Lens(get/set/modify/andThen), Optional(getOption/set/modify/andThen),
        Traversal(getAll/modify/set/andThen), Prism(getOption/reverseGet/set/modify/andThen) — IDENTICAL between
        `OpticsRuntime` (interp/JVM) and `JsRuntimeOptics` (JS). `PathStep`=Field/Some/Each/Index/AtKey.
      • Packaging infra TODAY: `ssc package --lib` (`SsclibPackaging.scala`) emits a `.ssclib` SOURCE zip (NOT a
        host artifact). `emit-js`/`emit-rust`/`emit-scala` emit programs. `ssc link --backend jvm --bytecode
        --emit-scala-facade` (`FacadeGenerator`) is the closest jar/facade path. **Spec §4's `emit-rust --lib` is
        FICTIONAL** — Rust lib mode = "module has no `@main`" (`RustGen.scala:62` → `renderLibRs()`/`src/lib.rs`,
        Cargo `[lib]`, golden-tested in `RustGenRuntimeFilesTest`/`RustGenCargoTomlTest`).
      • Per-host state: **JS = most tractable** (runtime exists+gated; only need ESM wrapper + `package.json` +
        hand-written `.d.ts`; no new codegen). **JVM** = facade/link-to-jar exists but optics has no compilable
        `.ssc` defs → author a thin facade. **Rust** = lib-crate skeleton exists but optic `pub fn` codegen is
        GREENFIELD. **Java** = fully greenfield (`JavaFacadeEmitter` + value-mapping seam). Golden pattern: mirror
        `RustGenCargoTomlTest` exact-string asserts, or `WireGoldenVectorTest` table.
      • **First slice = JS optics npm package**: call `JsGen.generateRuntime(Set(Capability.Optics,Core))`, wrap as
        ESM re-exporting `makeLens/makeOptional/makeTraversal/makePrism`, emit `package.json` + curated `optics.d.ts`
        (the 4 shapes above); golden test asserts the `.d.ts` + exported symbols. Then JVM/Rust/Java follow the
        same packager shape. Rank to ship: JS → JVM → Rust → Java.
      • **✓ JS SLICE LANDED 2026-06-22** — `JsLibPackager` (in `backendJs`) emits the `@scalascript/optics` npm
        ESM package (`package.json` + `index.mjs` + curated `optics.d.ts`); bundles the `JsRuntimeOptics` `_make*`
        factories + only the `_None`/`_Some`/`_isMap` deps (HAMT narrowed to native `Map` at the edge) + step
        builders; re-exports stable `makeLens/makeOptional/makeTraversal/makePrism/Some/None/field/index/at/some/each`.
        `JsLibPackagerTest` 5/5 incl. a node ESM smoke that imports the generated package + exercises all 4 optics.
        The `.d.ts` is the frozen API golden. **REMAINING slices** (each independently shippable, same packager
        shape): (a) a CLI command `emit-lib --host js --feature optics -o <dir>` (ServiceLoader `CliCommand` +
        `META-INF/services/scalascript.cli.CliCommand`; cli already dependsOn backendJs) + `examples/` demo +
        README/user-guide row — makes it USER-reachable; (b) **JVM** facade jar (`FacadeGenerator`/`ssc link
        --emit-scala-facade`; author a thin facade since optics has no `.ssc` defs); (c) **Rust** crate — GREENFIELD
        optic `pub fn` codegen in `RustRuntimeTemplates`; (d) **Java** facade — GREENFIELD `JavaFacadeEmitter` +
        `java.util.List` value-mapping seam. Golden API-signature test per host (mirror this JS `.d.ts` golden).
- [x] **js-runtime-resources** ✓ DONE 2026-06-22 (optics pilot) — first slice of polyglot-libraries §3 #8:
      move JS backend runtime fragments out of big Scala string constants into real `.mjs` resource files
      (lintable / `node --check`-able / editor-friendly). `JsRuntimeResource.load(name)` reads + caches a
      classpath resource under `/scalascript/js-runtime/`; `JsRuntimeOptics` is now a thin wrapper
      (`load("optics.mjs")`) keeping its `val X: String` API → call sites + emitted JS unchanged, verified
      **byte-identical** (7555B, `diff`-empty; `JsLibPackager` golden+node-smoke unchanged). `JsRuntimeResourceTest`
      5/5. Spec `specs/js-runtime-resources.md`. **✓ REST DONE 2026-06-22 (js-runtime-resources-rest):** the
      remaining 17 fragments (`Part1a`–`d`, `Part2a/2b`, `AsyncA/B`, `Signals`, `Dataset`, `IndexedDb`,
      `BrowserPatch`, `Graphql`, `Mcp`, `McpBrowser`, `Payment`, `V14Effects`) all migrated — `diff`-verified
      byte-identical, backendJs compiles, 65 JS codegen tests green. **§3 #8 closed for JS** (all 18 fragments
      now `.mjs`; the `JsRuntime`/`JsRuntimeAsync` aggregators in `JsGen.scala` stay computed). FOLLOW-UPS: same
      pattern for JVM/Rust runtime strings; optional `tsc --checkJs`/`eslint` CI gate (needs JSDoc first).
- [ ] **rust-effects-multishot-r6** (Rust backend, R.6) — multi-shot algebraic effects on Rust (resume invoked
      more than once, e.g. NonDet `{1,2}×{10,20}`). One-shot handle/resume already SHIPPED (`a87afba34`, tagless-
      final, no trampoline). lucky-otter flagged multi-shot as out-of-scope/hard: needs an `FnMut` continuation
      that can be re-invoked — the tagless-final one-shot lowering (`resume(v)`→`v` tail-substitution) can't express
      it. RESEARCH slice: probe whether a captured-closure continuation (`Box<dyn FnMut>`) or a CPS/defunctionalized
      re-entry is tractable in `RustCodeWalk`'s handle lowering; if not bounded, SCOPE DOWN + document the blocker
      in `specs/rust-effects.md` §R.6 and BACKLOG. Spec `specs/rust-effects.md`. Lower confidence than the other two.
- [ ] **core-min-phase3plus** (A, then B) — widen per the spec §3 roadmap: clock/random/env/state effects →
      optics → storage/signals → actors-plugin → cluster-plugin (raft/gossip out of `ActorInterp`) → migrate
      Typer feature tables onto `preludeSymbols` → move codegen feature runtime strings into plugin
      `runtimePreamble`. Each phase independently shippable; core keeps a built-in until its plugin lands; loud
      failure when a needed plugin is absent.
- [ ] **core-min-value-unification** (A, big — week-scale; LATER, not blocking) — collapse the duplication
      between the interpreter's `Value` and the SPI's `SpiValue` into ONE value type. Today they're separate by
      necessity: `interpreter.Value` (in `core`) is entangled with *execution* — `FunV(closure: Env)`,
      `NativeFnV(f: List[Value] => Computation)`, mutable `InstanceV`, `type Env = Map[String, Value]` — and
      `backendSpi` (which `core` depends on, not vice versa) can't reference it, so the boundary uses the
      host-neutral `SpiValue` (+ a `Value↔SpiValue` conversion). **Goal:** un-entangle `Value` from execution —
      split the *pure-data* cases (`Int/Double/Str/Bool/Char/Unit/List/Vector/Array/Map/Tuple/Option/Instance`)
      from the *runtime-carrier* cases (closures/native-fns hold an `Env`/`Computation`), moving closures +
      `Computation` out of the `Value` ADT into a separate runtime structure. Then the data ADT can live in a
      low shared module and **be** `SpiValue` — one value type across interp + SPI + host libraries (Task B),
      deleting the conversion. **Caveat (why it's LATER):** it's a deep refactor touching every `Value` match in
      the interpreter (DispatchRuntime/PatternRuntime/EvalRuntime), and it still privileges the interpreter's
      shape, so it's lower-priority than the keystone extractions; the current `SpiValue` (= the safe data
      subset) is correct in the meantime. **Verify:** full interp suite green; `Value↔SpiValue` conversion gone;
      no `Env`/`Computation` reachable from the SPI value type.

### ▶ Prioritized build queue (2026-06-18, with Sergiy — "внеси всё и делай автономно")

The genuine remaining **autonomously-actionable** build work, in priority order. Drive top-to-bottom,
one theme at a time, per-feature worktrees + claims. Everything below the queue is either history (`[x]`)
or blocked/deferred (kept for record, NOT actionable now — see "Excluded from the sprint").

> **Status 2026-06-18 (autonomous pass):** queue worked top-to-bottom. #1 meta-v2-track-c —
> verified already complete (no build). #2 sbt-plugin dep-resolution — ✓ built + tested (residuals
> design-/Maven-gated). #3 wasm-effects — **effectively COMPLETE**: arithmetic (2a) + `_dispatch`
> collection-HOFs (2b) + multi-shot (2c) + cross-module (2d) all built + run-verified on node (36 tests);
> `@main` args/non-Unit edge later closed by `wasm-main-edge` (40 tests). #4 build-registry-phase4 — assessed, no concrete target → no
> action. Then `sscBackends` cross-build ✓ DONE (user picked spec open-Q #2 → parallel outputs in one
> `compile`; scripted `cross-build/`). **What remains is Maven-gated only:** Maven Central + Plugin Portal
> publication (LAST, explicit-go). No bounded autonomous build work left.

### ▶ Quality / perf queue (2026-06-20, with Sergiy — "все эти задачи занеси в спринт и начинай делать")

After the perf series (foldLeft VM compile + typeclass-fold memo) micro-throughput is at the floor. The
next autonomously-actionable work is quality + unmeasured-axis perf, priority order. Drive top-to-bottom,
per-feature worktrees + claims.

> **Status 2026-06-20 (queue worked top-to-bottom — ALL DONE):** #1 real-workload-perf ✓ all three axes:
> (a) cold-start AppCDS −51% + harness, (b)+(c) steady-state server RSS+GC harness (~195 MB STABLE, no leak).
> #2 xbackend full+CI ✓ generator already broad (12 kinds) + wired into CI. #3 xbackend-test-hardening ✓
> `runCaptured` hang-proof runner. #4 rust-web-toolkit ✓ verified essentially complete + shipped the one
> bounded deferred slice (set/toggle client wiring); rest is browser/rozum-driven. **Queue fully resolved.**
> Follow-ups also DONE 2026-06-20 (per "сделай всё кроме maven"): **xbackend hang-proof sweep** — converted
> all 17 deadlock-risk (both-streams) subprocess-test files to `ProcTestUtil.runOrThrow`/`runCaptured` (the
> 22 single-stream `redirectErrorStream` files are deadlock-safe + behaviour-subtle → left as-is, standard
> set for new tests); 54 converted tests run green. **Server leak-hunt** — 4-min sustained-load run:
> definitively no leak (RSS peaked 205 MB, *ended 80 MB* as the JVM reclaimed heap; GC light/steady). **Only
> Maven publication (gated, excluded) + rozum/browser-driven rust refinements remain.**

### ▶ Rust-web computed-signal queue (2026-06-20, with Sergiy — "делай всё, заноси в спринт и делай")

The rust-web S5 refinements turned out to be autonomously buildable + curl/cargo-verifiable (set/toggle,
SSE, computed-read compile+SSR all DONE). Remaining, priority order:

- [x] **computed-live-recompute** ✓ DONE 2026-06-20 — computed signals are now fully reactive. Moved the
      signal store to `value.rs` (so `signal_value` can read it) + a computed-closure registry +
      `ssc_register_computed`/`ssc_recompute_all`; `_ui_computed_signal` is a re-runnable `Fn` returning a
      NAMED signal; `/__ssc/push` recomputes before broadcasting (SSE). **Verified cargo+curl:** push a dep →
      the computed signal auto-updates (`{"__c0":"fr"}` → `{"__c0":"de"}`). `backendRust` 224/0.
- [x] **computed-typed-reads** ✓ DONE 2026-06-20 — `collectLocalSignals` carries the element type; the apply
      emits `.parse::<i64>()`/`.parse::<f64>()` for `Signal[Int]`/`[Double]`, `.show()` for String. Verified:
      `signal("n", 10)` + `n() + 5` → `15`. `backendRust` 225/0.
- [x] **direct-WS** ✓ DONE 2026-06-20 — a `serve(view)` program also exposes a WS signal endpoint on
      `port + 1` for external clients (rozum bridge), bidirectional + sharing the SSE store/broadcast/recompute.
      `ssc_ws_serve` (accept_async) sends state on connect, streams updates, and an incoming `name=value` frame
      sets+recomputes. **Verified cargo + raw-WS client (python):** WS-push `locale=de` → `{"__c0":"de"}`.
      `backendRust` 226/0. **rust-web S5 now FULLY COMPLETE** (set/toggle, SSE, computed compile+SSR + live
      recompute, typed reads, direct-WS — all built + cargo/curl/WS-verified).

### ▶ Benchmark perf-divergence queue (2026-06-21, with Sergiy — "разбирайся в чем дела — в jit? В codegen? В bench?")

The big per-workload outliers from the same `./bench.sh` sweep, each ROOT-CAUSED by hand (emit + read the
generated code / toggle the JIT). Verdict per case: **codegen**, **jit**, or **bench** (intentional anti-fold).

- [x] **asm-jit-effect-pathology** (JIT) ✓ DONE 2026-06-21 — `ssc-asm` `effect-oneshot` **9.46 → 0.032
      ms/iter**, now effectively matching default `ssc` (0.025 ms/iter). Root cause: Javac bytecode JIT lowered
      active one-shot tail-resume effect ops through `JitGlobals.resolveEffectLong*`, but ASM `walkLong` did
      not, so `Bump.tick().toLong` bailed out to the slow effect trampoline. Fix `0d5e03b87`: ASM mirrors the
      resolver lowering and treats resolved effect calls as Long-shaped for `.toLong`/`.toInt`. Verified with
      `AsmEffectJitTest`, `EffectOneShotFastPathTest`, `JitLintTest` (85/85), `sbt -no-colors cli/installBin`,
      and `./bench.sh effect-oneshot --backend ssc{,-asm}`.
- [x] **js-tuple-monoid-alloc** (CODEGEN) ✓ DONE 2026-06-21 — **`js` `tuple-monoid` 7.40 → 2.60 ms (2.85×)**,
      no longer the slowest cell. Two general JsGen fixes: (1) `t._N` on a statically-known tuple lowers to a
      direct `t[N-1]` array read (new `tupleVars` tracking + `isTupleExpr`), skipping the megamorphic
      `_dispatch(t,'_N',[])`; case classes never match `isTupleExpr` so their Product `._N` is untouched.
      (2) a tuple-LITERAL concat `(a,b) ++ (c,d)` flattens into ONE `Object.assign([a,b,c,d],{_isTuple:true})`
      instead of `_tupleConcat(Object.assign(..),Object.assign(..))` (3 allocs → 1); a variable operand still
      uses `_tupleConcat`. **Verified:** 281 JS unit tests green; interp == js on tuple flatten/`._N`/show/eq.
      NOT done (left): native `+` for the `_arith('+')` on tuple-element reads (needs tuple-element type
      tracking) — lower value. The `s` LCG interp/js delta in this workload is the separate 64-bit-Long-on-JS
      precision limitation, not a tuple bug.
- NOTE (no task — **bench**, intentional): rust `arith-loop` **1.52 ms (4.7× jvm)** is largely the harness's
      anti-fold — `run.sc` wraps every rust closure body + per-iter reassignment in `std::hint::black_box(...)`,
      blocking LLVM loop optimization (the comment at `run.sc:176` even tunes this so rust "stops looking 3–4×
      slower"). Not a codegen bug; leave as-is unless we want a lighter rust anti-fold.

### ▶ Benchmark backend-gap queue (2026-06-21, with Sergiy — "Запиши в спринт все n/a")

Every `n/a` from a full `./bench.sh` sweep (31 workloads × ssc/ssc-asm/jvm/js/rust), each VERIFIED by hand
against the current toolchain (the corpus comments were stale). The bench measures time only (no correctness
check — that's `CrossBackendPropertyTest`, green); `n/a` = that backend's emit/build/run failed.

- [x] **rust-effects-handle-resume** (R.4.2, ONE-SHOT) ✓ DONE 2026-06-22 — **`effect-oneshot` n/a → 0.0020 ms
      on rust** (the fastest backend on it). Custom algebraic effects with explicit `handle`/`resume` now
      compile + run on rust via **tagless-final traits** (per `specs/rust-effects.md §10`), NOT the Free-monad
      CPS port the old `rust-backend.md §R.4` implied — so the `while`-loop case needs **no trampoline** (the
      loop runs directly; `Bump.tick()` is `_eff.tick()`). 3 gaps implemented: (1) a custom `effect E:` object
      emits a `trait ${E}Effect` with required methods (`collectEffectOps` + `renderTaglessEffectsRs`); (2)
      `Eff.op(args)` → `_eff.op(args)`; (3) `handle(body){ case Eff.op(binders, resume) => arm }` → a handler
      `struct __H_E; impl ${E}Effect for __H_E { fn op(&mut self, binders) -> ret { <resume(v)⇒v> } }` +
      `{ let mut _eff = __H_E; <body> }`. **Verified:** minimal probe cargo-builds → `10`; the real
      `effect-oneshot.ssc` workload → `962` (== interp/jvm); `backendRust` 230/0 + 3 new `RustGenR44Test`
      cases. **Remaining (R.6 follow-up, NOT this task): multi-shot.** `effect-multishot` stays `n/a` — its
      `opts.flatMap(opt => resume(opt))` calls `resume` many times, which a single trait-method return can't
      model (needs FnMut continuation re-invocation); it fails cargo cleanly (out of scope by design).
- [x] **jvm-multishot-result-type** ✓ DONE 2026-06-21 — `effect-multishot` was `n/a` on **jvm** because
      CPS def emission widened total handled-effect wrappers from their declared result type to `Any`:
      `def workload(seed: Long): Long` emitted as `def workload(seed: Long): Any`, and the bench wrapper's
      typed sink failed with `Found: Any; Required: Long`. Fix (`39b7c665f`): keep declared non-effect-row
      result types at CPS def boundaries and cast the final CPS result there; effect-row defs (`A ! Eff`)
      still return `Any` so handlers can unwrap Free computations. Guard: `JvmGenEffectsRuntimeTest`
      `addLong(workload(0L))` e2e. **Verified:** `backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest`
      34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` `n/a` -> 0.075 ms.
- [x] **rust-either-chain-closure-type** (E0282) ✓ DONE 2026-06-21 — `either-chain` was `n/a` on **rust**
      (`cargo build` → `error[E0282]: type annotations needed` because the chained `match match match …`
      emitted each Either arm as `(move |x| { … })(v)`, whose closure param type rustc couldn't infer). Fix:
      a new `inlineArm` lowers a 1-param Either map/flatMap/fold arm to a `{ let x = v; body }` block instead
      of an immediately-applied closure — the `let` flows `x`'s type straight from `v`. Function-reference args
      keep `(f)(v)`. **Verified:** `cargo build` green; interp == rust (`R=632`); `./bench.sh either-chain
      --backend rust` n/a → **0.0040 ms**; `backendRust` 229/0 + a new `RustGenR23Test` E0282 regression test.
- [x] **bench-stale-jvm-na-hygiene** ✓ DONE 2026-06-21 — the stale JVM `n/a` was not a cache issue; it shared
      the `jvm-multishot-result-type` root cause. Total CPS wrappers declared as `Long` emitted as `Any`, so
      the bench sink rejected both `effect-oneshot` and `effect-multishot`. Corpus comments were refreshed.
      **Verified:** `./bench.sh effect-oneshot --backend jvm` = 0.160 ms; `./bench.sh effect-multishot --backend jvm`
      = 0.075 ms; `./bench.sh effect-oneshot effect-multishot --backend js` = 0.347 / 0.224 ms.

### ▶ Improvement queue (2026-06-20, with Sergiy — "занеси все в спринт и делай")

Fresh do-soon queue after rust-web S5 closed. Work top-to-bottom, one claim/worktree per slice. Maven Central
publication remains explicit-go only; the registry work below is intentionally domain-independent first.

- [x] **wasm-main-edge** ✓ DONE 2026-06-20 — closed the last WASM effects tail. Effectful WASM now derives
      the user `@main` from the AST, preserves a single Scala 3 `@main` parameter clause (including
      `String*` splicing), discards non-`Unit` returns in the synthetic wrapper, and rejects raw
      `Array[String]` `@main` args with a clear "use `String*`" diagnostic. **Verified:**
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/wasm-main-edge && sbt "backendWasm/testOnly scalascript.codegen.WasmBackendTest"`
      → 40/40 green. Gotcha recorded in `specs/wasm-main-edge.md`: Scala.js ES-module launcher argument
      delivery is out of scope; a direct Node probe supplies empty `String*` args.
- [x] **stable-plugin-spi-p3** ✓ DONE 2026-06-21 — completed one small Phase 3 SPI cleanup slice:
      `bench-plugin` now implements `Bench.opaque` through `PluginNative.eval` / `PluginValue` instead of
      importing `scalascript.interpreter.Value` directly. Added `BenchIntrinsicsTest` to lock identity
      behavior (including empty args -> `Unit`) and to scan `bench-plugin/src/main` for direct interpreter
      imports so this slice does not regress. **Verified:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/stable-plugin-spi-p3 && sbt -no-colors "benchPlugin/test; pluginApi/test; benchPlugin/checkPluginBoundary"`
      → `BenchIntrinsicsTest` 2/2 green, `PluginApiTest` 14/14 green, `benchPlugin/checkPluginBoundary` green.
- [x] **js-char-wrapper-string-map** ✓ DONE 2026-06-21 — added a JS `_Char` box (`JsRuntimePart2a`):
      `valueOf`→code point, `toString`→1-char string (so concat/arith/`_show` coerce). Iterated chars
      (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) box;
      `String.map` returns a String only when every result is a `_Char`, else a Seq (mirrors `strMapResult`).
      `_dispatch` got a `_Char` branch mirroring the interp `dispatchChar` (`toInt`→code, `isDigit`/`toUpper`/
      `asDigit`/…); `_eq` bridges `_Char` ↔ 1-char String literal and ↔ Int. `CrossBackendPropertyTest`
      "String.map char vs non-char" now asserts interp == JS == JVM (+ a char-method map/filter case).
      **Verified:** 280 JS unit tests green (23 suites, 0 fail); String.map + string-method-gaps cross-backend
      green on all 3 backends; direct node probe matches interp byte-for-byte. Residual (BUGS.md): a char
      *literal*'s `.toInt` (`'5'.toInt`) still diverges (literals stay strings to avoid literal-pattern
      `===` codegen) — separate, lower-value follow-up.
- [x] **rust-web-example** ✓ DONE 2026-06-21 (a55e101f2) — added `examples/rust/web-signals.ssc`
      (signal + computedSignal + signalText + serve), emit-rust + `cargo build` green, binary serves SSR and
      `/__ssc/push?name=locale&value=de` recomputes the computed signal (`{"__c0":"fr"}` → `{"__c0":"de"}`).
      Building it (vs the string-match tests, which never cargo-build) surfaced + fixed **two real bugs**:
      (1) computed move-closure use-after-move (cargo E0382) — `renderClosure` now clone-captures read signal
      locals; new regression test, backendRust 228/0; (2) docs showed `POST /__ssc/push -d` but the endpoint
      reads query params `?name=&value=` — corrected example + rust-backend.md + user-guide.md.
- [x] **real-workload-perf** (roadmap-next #1) ✓ DONE 2026-06-20 (all three axes). **(a) cold-start:**
      `tests/perf/coldstart/` + AppCDS in `bin/ssc`/`install.sh` → **378 → 182 ms (−51%)**, peak RSS −32%.
      **(b)+(c) steady-state RSS + GC:** `tests/perf/serverrss/` boots a real server under load → interp
      server **~195 MB RSS, STABLE** (no leak), light GC (~41 pauses/27 ms). Long minutes-scale leak-hunt
      left to demand (`secs=300+`). BACKLOG `real-workload-perf`.
- [x] **xbackend-property-equivalence (full + CI)** ✓ DONE 2026-06-20 — broaden was already complete (12
      kinds incl. effects/Option/Either/closures/nested; node leg 74 programs / 0 skipped) so the work was
      reconciling that + **wiring into CI**: added Node.js setup to the `sbt` job so the interp==JS
      differential now runs in CI (it was skipping). Made hang-safe first (next item). BACKLOG `xbackend-property-equivalence`.
- [x] **xbackend-test-hardening** ✓ DONE 2026-06-20 — root cause was NOT bloop per se: `runProc` read
      subprocess streams with blocking `mkString` BEFORE the bounded `awaitExit`, so a wedged child parked
      the read forever (and could pipe-buffer-deadlock). Fixed via `ProcTestUtil.runCaptured` (threaded
      stream drain + hard timeout that actually fires); `ProcTestUtilTest` proves a `sleep 60`@2s returns
      <15s + a stderr flood doesn't deadlock. `CrossBackendPropertyTest.runProc` delegates. (~9 other test
      files share the old antipattern but run fixed small programs — follow-up sweep, lower risk.)
- [x] **rust-web-toolkit finish** ✓ VERIFIED ESSENTIALLY COMPLETE 2026-06-20 (the "~56 cargo errors" was
      badly stale). Checked against the authoritative signal: **`backendRust` 221/0**, **`RustGenWebToolkitTest`
      17/17** green. Per `specs/rust-web-toolkit.md`: cargo `build` of the std/ui crate is **290 → 0** (whole
      toolkit compiles on Rust), **S4** named/curried args DONE, **S5a** (SSR initial value) + **S5b.1** (local
      client reactivity) + **S5b.2 A/B/C** (generic push / rozum bridge / computed-derived) all DELIVERED at
      poll-transport depth. **REMAINING = explicitly-deferred refinements**, NOT bounded build work: SSE/WS
      streaming transport, client recompute of computed signals, set/toggle/show client wiring, direct-WS
      client. All are **browser-dependent** (can't verify autonomously without a browser) and **rozum-driven**
      (spec method: "drive from the target … ultimately `rozum-web.ssc`"). Hand back to the rozum driver; do
      NOT push speculative client-JS refinements onto `feature/rust-web-toolkit` (rozum's active branch).


- [x] **meta-v2-track-c** ✓ DONE 2026-06-18 (verified, no build needed) — Track C is COMPLETE. C1
      (multi-clause inline) ✓ done 2026-06-18. C2's high-value slice ✓ already done + wired:
      `MacroCodegen.codegenWarnings(module)` is computed in `ssc check` (`Main.scala:5265`, merged into
      `CheckResult.errors:5267`) and warns up-front on interpreter-only macros that can't compile to JVM/JS —
      `MacroCodegenTest` 6/6 green. The remaining C2 ambition (run the Typer over *arbitrary* macro-expanded
      source, map type-errors to `.ssc` positions) is **DEFERRED by design** in the spec: needs a position
      map (re-parse loses positions) + risks false positives (Typer may not grok expanded macro-runtime
      constructs), niche audience — low ROI vs the codegen warning that covers the real failure mode. Building
      it now = busywork against the spec's own judgment. **→ Next pick: sbt-plugin-finish.**
- [x] **board-meta-v2-reconcile** ✓ DONE 2026-06-21 — removed stale meta-v2 Track C/C2 "still open"
      guidance from the board.
      **How:** reconcile `SPRINT.md`'s later `[~] metaprogramming-v2` paragraph and `BACKLOG.md` roadmap text
      with the authoritative `meta-v2-track-c` done entry plus `specs/arch-metaprogramming-v2.md` §4b, which
      says the remaining arbitrary post-expansion re-typecheck ambition is deferred by design. Keep the
      historical spec rationale; change only active queue/backlog wording so future agents do not pick C2 as
      buildable work. **Verify:** targeted grep now leaves only spec/history/deferred wording; active
      `SPRINT.md`/`BACKLOG.md` guidance no longer presents C2 as buildable work.
- [~] **sbt-plugin-finish** (roadmap #4, Phase 5) — **dep-resolution ✓ DONE 2026-06-18**: the concrete
      actionable Phase 5 slice. `SscFrontMatter` lifts `.ssc` front-matter `dependencies:` `dep:` Maven
      coords into `sscManagedDependencies` → `libraryDependencies` (Java `%`, Scala-cross `%%`, local paths
      ignored); scripted `dep-resolution/` + full scripted suite green (9). Spec §3h/Phase 5 reconciled.
      **`sscBackends` cross-build ✓ DONE 2026-06-18** (user picked spec open-Q #2 → design A = parallel
      outputs in one `compile`): `sscBackends: Seq[String]` (default `Seq(sscBackend)`); `sscCompile` forks
      `ssc build --backend <b>` per backend — single = flat dir (backward-compat), multiple = per-backend
      subdirs. Scripted `cross-build/`; full suite green (10). RESIDUALS (NOT done): (a) LSP/BSP "polish" —
      `BspIntegration`/`sscBspSetup` already landed Phase 4, no concrete remaining deliverable; (b) Maven
      Central publish + Plugin Portal — Maven-gated (LAST). So the only buildable remainder here is
      Maven-gated.
- [x] **wasm-effects** ✓ COMPLETE 2026-06-20 — additive, wasm-only.
      **arithmetic ✓ DONE (slice 2a):** `_binOp` (+`_bigIntOp`/`_bigDecOp`) — `a + b`/`sum * 2` over effect-op
      results link + run (test → 40). **`_dispatch` ✓ DONE (slice 2b):** collection HOFs on `Any` —
      `xs.map(..).filter(..).head` in a handler links + runs (test → 6); copied the pure subset of `_dispatch`
      + `_seqX`/`_seq`/`_isFree`, reflection fallback → clear error. **multi-shot ✓ DONE (slice 2c):** did NOT
      need a `_handle` rewrite (probe disproved it) — just the pure `_anyFlatMap` helper + a `usesEffects` fix
      to recognise `multi effect Foo:`; NonDet `{1,2}×{10,20}` runs on node (test → 4). **cross-module ✓ DONE
      (slice 2d, no code change):** an imported `effect` already works — `generateUserOnly` resolves imports via
      `baseDir`; run test → `hello\nworld`. **`@main` args/non-Unit edge ✓ DONE (wasm-main-edge):** effectful
      `@main` wrappers preserve Scala 3 main parameter clauses, discard non-Unit returns, and reject invalid raw
      `Array[String]` args clearly. **Complete:** common + advanced cases all run; `WasmBackendTest` 40/40 green.
      BACKLOG `wasm-effects`.
- [x] **build-registry-phase4** ✓ ASSESSED → no action 2026-06-18 (demand-driven). Surveyed the ~24
      `*Registry` classes: they are domain-distinct (Preprocessor / Interpolator / Backend / Capability /
      Route / Command / GlueClasspath / GlueJsPreamble / …), each registering a different kind of thing —
      **not** a duplicated template. The closest pairs (`Glue*`, `Interpolator*`) are small and cohesive;
      consolidating them would be speculative refactoring, exactly what the spec's "only where they remove
      real duplication" guard rules out. No concrete duplication target → no build. Revisit only if one
      appears. (Phases 1–2 landed; Phase 3 moot/load-bearing.)

---

- [x] **rust-web-toolkit** (external driver: rozum) — bring the declarative std/ui toolkit
      (`vstack/heading/text` → `lower(theme)` → `View` → `serve(view, port)`), which works on JVM,
      up on the **Rust** backend via an HTML/SSR binding (operator path A; native GUI rejected as
      too costly). **DONE 2026-06-19:** I1 `s"…${expr}…"` splices + S1a HTML/SSR View primitives
      (`element/textNode/fragment` → `runtime/ui.rs`, gated) + S1b `renderHtml` SSR — `textNode`/
      `fragment` compile AND run end-to-end (`renderHtml(...)` → escaped HTML via `ssc run-rust`).
      `backendRust` 211/0. + S1c `element` (`->` → tuple; non-empty `Map(k->v)` → HashMap-insert;
      `_ui_element` key-sorted attrs) — `renderHtml(element("div",Map("class"->"root"),…))` →
      `<div class="root" …>…</div>` end-to-end, `backendRust` 212/0. + S2 `serve(view, port)` SSR
      overload (`_ui_serve` in `http.rs`, gated on uiUsage) — `curl :8099` → SSR'd HTML, proven
      end-to-end, `backendRust` 214/0. + S1d void elements (`<meta>` self-close) + **capstone
      `examples/ssr-page.ssc`**: full nested HTML page built from primitives → `ssc build-rust` →
      `curl :8123` returns the SSR'd page. **The Rust-SSR web goal is reachable today via primitives.**
      + **S3 (a–k) the std/ui library now CODEGEN-transpiles** (import inliner + block exprs +
      partial fns + patterns + placeholder `_`-lambdas + varargs type + `++`/try/null + struct
      field types + String-match `.as_str()` + opaque-type mapping + signal SSR stubs). Cascade:
      codegen 28→11→6→3→**0**; cargo 290→170→108→70→**56**. **REMAINING:** a finicky cargo
      type-reconciliation tail (~56: TkNode/i64 + String/Value + struct-field i64 + curried-vararg
      **call-site** `vec![]` wrapping + `defaultTheme` val) — converging, multi-session. Then S4
      named/curried args · S5 signal reactivity (stubs are static-only). Spec `specs/rust-web-toolkit.md`.
      **✓ CLOSED 2026-06-22:** S1–S5 all landed on `origin/main` (S4 named/curried args + omitted-default
      fill; S5 SSR + local client + server-push + SSE/direct-WS + computed live recompute + typed signal
      reads — see CHANGELOG 2026-06-19/06-20). The driving use case `examples/rozum-meeting.ssc` builds to a
      binary and SSRs over hyper. General Rust-backend follow-ons (Vec `take/drop/sorted/distinct`, String
      `.replace`, http prefix-routing/no-store/POST-body/MIME, indexable `split/toList`) landed on main via
      `rwt-followons` (613c2bb21, `backendRust` 233/0). The `feature/rust-web-toolkit` branch is rozum's own.

- [x] **agent-sdk-remainder** ✓ DONE 2026-06-17 (actionable scope) — consolidated `specs/agent-sdk.md`
      + **P3a MCP bridge both directions** (`runtime/std/agent-mcp.ssc`: `serveAgentToolsMcp` +
      `mcpToolSource`; examples `agent-mcp-{server,toolsource}.ssc`; all `ssc check` OK). Loop
      conformance already covered by `AgentSdkInterpreterTest`. DEFERRED (reasons in spec): bridge
      round-trip test (heavy jvm/js infra for thin glue), golden transcripts, P3b embedded (blocked
      on rozum `rozum-embed`). spec `specs/agent-sdk.md`. → **Next: package-registry.**

- [x] **package-registry** (roadmap #3) ✓ DONE 2026-06-17 — found ALREADY BUILT (spec was stale):
      `ssc search`/`info`/`add` over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh`) +
      seed `registry/packages.yaml`. spec `specs/arch-registry.md` reconciled. Added the minor
      `--offline` flag (cached-only search, `RegistryClient.loadOffline()`). REMAINING (external only):
      the `scalascript/registry` GitHub repo + Pages HTML + validate/publish CI.

- [x] **sbt-plugin-finish** ✓ ACTIONABLE SCOPE DONE 2026-06-18 — this duplicate open marker was stale.
      Front-matter `dependencies:` → Coursier and `sscBackends` cross-build are done + scripted-tested;
      LSP/BSP Phase 4 already landed with no concrete remaining deliverable. Publishing the plugin artifact
      itself is the deferred Maven Central / sbt Plugin Portal step and remains excluded from autonomous work.

- [x] **metaprogramming-v2** ✓ ACTIONABLE SCOPE DONE 2026-06-21 — AUDIT 2026-06-17: NOT a from-scratch build. All three
      phases have working bases (P3 Linker `inlineTable`/`expandInlineSource`; P4 `${impl('x)}` + direct
      `'{ $x+1 }` + interp parity + `MacroImpl` IR; P5 runtime `Mirror` + user `derived(m: Mirror)`).
      PROGRESS: **Track A** (P5 cross-backend derives conformance) ✓ DONE (A1a/b/c + A2 + A3,
      2026-06-17; only deferred edge cases remain — sum-type/enum mirrors, generics, mixed-derives clauses).
      **Track B** (P4 const-folding `Expr.asValue match`): **B1 + B2 ✓ DONE 2026-06-18** (interp splice
      unwraps `Expr(v)`; `Linker.expandMacroSource` const-folds literal args to the `Some` branch, else the
      `None` direct quote; `LinkerRewriteTest` +7 / `InlineDerivesTest`; `examples/quoted-macro-constfold.ssc`).
      **B3 ✓ DONE 2026-06-18 — JVM + JS** (was blocked — quoted macros were interpreter-only): the
      `macro-codegen-backends` pass (`MacroCodegen.expand`, hooked into `JvmGen` + `JsGen` generate entry
      points) expands + strips macros pre-codegen, no-op for macro-free modules;
      `QuotedMacroJvmConformanceTest` (scala-cli) + `QuotedMacroJsConformanceTest` (node) match interp.
      **Track B is complete (B1+B2+B3).** **Track C:** C1 (multi-clause inline) ✓ DONE 2026-06-18
      (curry tail clauses into the body — no scanner/wire change); C2's practical backend guard is already
      wired through `MacroCodegen.codegenWarnings`, and the broader arbitrary post-expansion re-typecheck +
      source-positioned-error ambition is deferred by design (position-map requirement + false-positive risk).
      No bounded autonomous meta-v2 build slice remains on the board.

### Tier 2 — AUDIT 2026-06-17: most "themes" are already BUILT (specs stale)

While pulling these in I audited each against the code — and like agent-sdk + package-registry,
most are already implemented; the specs/BACKLOG were stale. So Tier 2 is mostly **reconcile +
verify residuals**, NOT from-scratch builds:

**RECONCILED 2026-06-18 (`tier2-spec-reconcile`)** — verified each theme against the code:
- [x] **theme-f-dsl-platform-hooks** — spec Status already accurate ("implemented through Phase 4",
      `InterpolatorRegistry`). No change needed.
- [x] **theme-h-library-modularity** — spec Status already accurate ("implemented through Phase 6",
      `SsclibManifest`). No change needed.
- [x] **theme-j-lightweight-ffi** — ✓ DONE: `@jvm`/`@js` (Phases 1–4) + `@rust` + **`@wasm`** all wired.
      The WASM backend exists (`runtime/backend/wasm`, Scala.js → `.wasm`); `WasmGen` lowers `@wasm("expr")`
      externs to a `def` (2026-06-18, `WasmBackendTest`). Only `@wasmExport`/`@wasmImport` (raw WASM ABI)
      stay out of scope **by design** (the Scala.js path owns the ABI). The "no WASM backend wiring" note
      was stale.
- [~] **theme-a-stable-plugin-spi** — spec Status accurate ("partially implemented"; Phases 1+2 landed).
      Residual = Phase 3 versioned stable API module. Left as-is (accurate).
- [x] **ssc-new-audit** ✓ DONE 2026-06-19 — verified and tightened the local `ssc new` /
      standalone-install surface without touching Maven/publication. Fixed `NewProject.create` to best-effort
      `git init -q`; fixed `ssc new` usage to list all bundled templates; made root `install.sh` match docs
      (`./install.sh` prints standalone Coursier/Homebrew/curl guidance, `./install.sh --dev` runs monorepo
      staging); clarified `specs/arch-ssc-new.md` (plugin template intentionally has no `project/plugins.sbt`;
      live channel publication remains deferred); updated the old benchmark note to use `install.sh --dev`.
      Added tests for all six templates, output-dir aliases, placeholder-free rendering, git-init, and release
      fixtures. Verify: `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/ssc-new-audit && sbt
      "cli/testOnly scalascript.cli.NewProjectTest scalascript.cli.StandaloneInstallFixturesTest"` → 8/8 green.
- [x] **board-ledger-hygiene** ✓ DONE 2026-06-19 — docs-only cleanup. Marked the duplicate
      `sbt-plugin-finish` open item as actionable-scope done/Maven-gated, and removed three stale
      `Status: open` lines inside fixed `BUGS.md` entries (`jvmgen-multishot-handle-result-any`,
      `jvmgen-handle-in-arg-position`, `js-self-handling-cps-fn-not-run`). Verify:
      `git grep -n "\*\*Status:\*\* open\|Status: open" -- BUGS.md` → no matches, and
      `git grep -n "^- \[ \] \*\*sbt-plugin-finish" -- SPRINT.md` → no matches.
- [x] **theme-b-build-registry-consolidation** — Phase 3 is **MOOT** (triaged 2026-06-18):
      `PluginManifest`/`LocalRegistry` are the **implementation** the facade is built on (not removable
      wrappers — `BackendRegistry` uses `PluginManifest`; `ImportResolver`/`PluginCommands` use
      `LocalRegistry`), and `isStdPluginInterpreterTest` is already gone. Nothing to remove. OPTIONAL
      Phase 4 (family registries) remains, demand-driven.
- [x] **module-graph-grouping** — ✓ INVESTIGATED → leave-as-is (2026-06-18, `docs/module-graph-findings.md`):
      197 modules; the per-impl module IS the SPI boundary; grouping either collapses it or is a no-op on
      the graph. No action.
- [ ] **std-nfc-packager-adapters** — BLOCKED autonomously: needs real iOS/Android/Web-NFC packager
      integration + device/browser harnesses. Native platform follow-up; can't verify without targets.
- [ ] **wallet-browser-ws-itest** — BLOCKED autonomously: real browser-WebSocket integration; full run
      needs a browser.

**Genuine remaining BUILD work** (across Tiers): no bounded autonomous build slice is currently ready here.
The old sbt-plugin build pieces are done and publication is Maven-gated; build-registry Phase 3 is moot and
Phase 4 is demand-driven; meta-v2 Tracks A/B/C are actionable-scope done with only deferred edge cases. The
small residuals above are blocked by real browser/device/external inputs. See BACKLOG "Roadmap reality check".

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.
