# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** â pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"ÑÐ°Ð±Ð¾ÑÐ°Ð¹"` / `"go"`. Status: ask `"ÑÑÐ°ÑÑÑ"` / `"status"`.

---

## busi-seq132 â interp module-loader diamond OOM (2026-06-12)

- [x] **interp-module-loader-dedup** â DONE 2026-06-12. busi (rozum seq-132): the
      interpreter module loader (`SectionRuntime.runImport`) created a fresh `Interpreter`
      and re-ran the imported module on **every import edge** â no cache. A diamond over a
      big module re-evaluated it once per DAG path â exponential â OOM/hang at load time.
      `ssc check` was fine (typer memoizes); only the interp loader re-evaluated. Fix:
      shared `moduleCache: Map[os.Path, Interpreter]` threaded through child constructors,
      `getOrElseUpdate(resolvedPath)` in `runImport` â each module evaluated once per run
      (init side effects run once, like the typer / ES/Python modules). Regress:
      `InterpModuleDedupTest` (diamond + 3-layer stacked diamond; asserts the shared
      module's load side effect fires exactly once) â green. 108 suites @ 0 failures in
      the full run (only the pre-existing three-way-ping-pong hang below blocks a single
      all-green number; unrelated to imports). Spec `specs/interp-module-loader-dedup.md`.
      `BUGS.md` entry `fixed`. Unblocks busi ph-2 monolith modularization.

## Discovered: interp three-way mutual-TCO hang (2026-06-12)

- [x] **interp-three-way-tco-hang** â DONE 2026-06-12. The "flaky environmental hang at
      InterpreterTest" was actually a DETERMINISTIC O(nÂ²) blow-up in
      "mutual-TCO â three-way ping-pong" (`InterpreterTest.scala:1025`) with the JIT ON.
      Diagnosis: not an infinite loop â depth 30k=29ms, 60k=115s; `SSC_JIT=off` makes all
      depths ~390ms. `jstack` = thousands of nested `SscVm.exec:303` (`CALL` opcode). The
      TCO trampoline's `MutualTailCall` handler JIT-ran `next` whenever `noNonTailSelf`
      (true for `pong`, which has NO self-call) â but the register VM lowers a non-self
      tail call to a recursing `CALL`, so running the 3-cycle compiled recurses
      `pingâpongâpangââ¦` until `FrameOverflow`, then `runVm` grows+restarts `exec` from
      scratch â O(nÂ²). Fix: only take the JIT fast-path when `next` is genuinely
      **self-tail-recursive** (`tcoInfoFor(next).isSelfTailRec`), not merely
      `noNonTailSelf` â so `workloadâsumTco` still JITs, but a non-self cycle stays on the
      constant-stack tree-walk trampoline. One-line gate in `TcoRuntime.tcoTrampoline`; no
      `VmCompiler` change (SscVmTest mutual-recursion unit tests still green). Regress:
      `ThreeWayMutualTcoTest` (3-way/2-way/self-TCO at depth ~100k + wall-clock bound).
      `InterpreterTest` now completes (141 passed). Spec `specs/interp-three-way-tco-hang.md`.
      Memory note corrected. Follow-up (low-pri): a real n-way mutual-TCO JIT.

## Post-busi-seq124/125 follow-ups (2026-06-12)

Queued after the cross-module enumâtrait fix (`1ddf10517`). Ordered by value;
work top-to-bottom. Each = its own worktree + commits; verify per the targeted-gate
note in `project_interp_enum_trait_hierarchy_0612` (full `backendInterpreter/test`
flakily hangs â use the 332-test targeted set if it won't pass).

- [x] **jit-supertype-type-test-compile** â DONE 2026-06-12. Took the cleaner
      **if-chain + runtime `JitGlobals.isSubtype`** route (not switch-arm expansion â
      avoids the dedup/case-collision edge case): a supertype `case _: T` routes to the
      if-chain (`walkMatchBody` + `walkMatchExpr`), `walkArmAsIfBranch` gained a
      `Pat.Typed` case emitting `isSubtype(inst.typeName(), "T")` for supertypes / exact
      tag for leaves. First-match order preserved. `JavacJitBackend` only (default);
      `AsmJitBackend` keeps the correct seq-124 bail (asm real-compile = follow-up).
      Tests: 3 hot-loop `BugReproTest` + `JitLintTest` `willJit==true` guard; 347-test
      targeted JIT/pattern/sealed/enum gate green.
- [x] **full-interp-gate-green** â DONE 2026-06-12. (1) Landed a clean full green run:
      `backendInterpreter/test` = **1664 passed, 0 failed** â confirming the 4 earlier
      hangs were the flaky environmental issue, NOT the jit/enum-trait code (all green
      once it doesn't hang). (2) Hardened the one genuine hang-risk: `JsGenWsTest`'s
      `readHttpLine` was an UNBOUNDED blocking socket read (`in.read()` with no
      timeout) â a non-responding emitted WS server would block the whole gate
      forever. Added `setSoTimeout(15000)` in `openWithRetry`, so a stuck read now
      fails the one test fast (SocketTimeoutException) instead of hanging the gate.
      It was the only unbounded blocking I/O in the suite (other `while true` are
      cooperative scalascript coroutine/actor fixtures; latches/awaits are bounded).
      `Test / fork := true` â a hang lives in the FORKED test JVM (jstack that, not
      the sbt launcher â the earlier mis-diagnosis).
- [x] **cli-jit-classpath-fallback** â DONE 2026-06-12. Verified: the classpath
      fallback is **`runMain`/dev-only** â the real `bin/ssc` fat jar JITs fine
      (`fib(30)`â`832040`; `java.class.path` = the jar). BUT this hid a real bug: the
      javac JIT is *untested by the suite* (always bails on the classpath in sbt), and
      on the fat jar a JIT codegen `StackOverflowError` (`walkLocalSlotCtx`, a `while`
      accumulator over a recursive call) **crashed the program** instead of bailing.
      Fixed: guarded all 4 JIT compile entries (`Javac`/`Asm` `tryCompile` + 3
      `EvalRuntime` while-JIT sites) with `catch Throwable => null` â bails to
      tree-walk, never crashes. Spec `specs/jit-crash-safety-and-cli-classpath.md`.
      Spun off two follow-ups below.
- [ ] **jit-runmain-classpath** â pass the running classloader's URLs as `-classpath`
      to the runtime javac (`compiler.getTask` options) so the javac JIT compiles
      under sbt/`runMain` too â making the codegen *actually tested* by the suite and
      removing the diagnosis trap. RISK: latent JIT codegen bugs start firing in tests
      (now safe to bail thanks to the crash-safety guard), may shift coverage/perf.
- [ ] **jit-walklocalslotctx-so** â fix the `walkLocalSlotCtx` recursion that
      overflows on the `while`-accumulator-over-recursive-call shape (now bails
      harmlessly; a real fix restores JIT for that shape). Repro: `def fib(n)â¦; var
      s=0; while i<35 do s = s + fib(20); â¦` via the fat jar pre-guard.
- [ ] **declarative-ui-vnext** â remaining Scope B follow-ups (none requested by busi):
      B.3 `rowsPath` on the native/JVM backends, B.7 lint for Markdown `toolkit:`
      *link* references, and a typed / `key: signalId` `formBody` mapping. All small.
- [x] **scrumban-skill-zero-install** â DONE 2026-06-12. Codified the write-before-do
      discipline as a new agent-independent `scrumban` skill + made the whole
      `.agents/plugins` submodule usable with zero per-skill install: added
      `.agents/plugins/AGENTS.md` (the skill index â read-on-demand + glob discovery
      of future skills) and `.claude-plugin/marketplace.json` (optional Claude-native
      slash-commands). Rewrote this project's `AGENTS.md` `MANDATORY: required skills`
      to one index pointer (new skills appear automatically, no edit here). Submodule
      bumped to `bc2a53a`.

---

## New direction (2026-06-12) â type-level lambdas, direct-style eval, uuid-p6

### uuid-p6 â monotonic v7 counter (no blocker, small)
- [x] **uuid-p6** â â DONE 2026-06-12 (`9fa049920`). `uuidV7Monotonic` / typed
      `Uuid.v7Monotonic()`: rand_a as a per-ms counter (RFC 9562 Â§6.2 Method 1) â
      strictly increasing within a ms, timestamp dominates across. JVM + JS/Node +
      effect classification (SideEffect like v4/v7). Verified end-to-end via bin/ssc
      (bare: valid + rand_a 2daâ2db monotonic). Tests: UuidPluginTest (valid + 500-call
      strict monotonicity), DepEffectfulnessFixpointTest (effectful). NOTE: typed
      `Uuid.v7Monotonic()` shares the pre-existing std-import-path limitation affecting
      all of object Uuid (bare intrinsic is the working surface). ([[project_uuid_plugin]])

### type-level-lambdas â syntax + representation
Investigation (2026-06-12): current state is **surface-only**. `SType.HigherKinded(name,
arity)` round-trips `F[_]` for interface artifacts but "never participates in unification
or runtime semantics"; `SType.Match` (match-types) likewise surface-only. There is **no
`SType.TypeLambda`**. Scala 3 native `[X] =>> F[X]` does NOT parse (`=>>` is only in the
parser's `exprOperators` token set + `SsccFormatV3.TypeLambdaArrow`, never wired into type
parsing). `Lambda[X => F[X]]` "parses" only as a generic name application (no meaning).
ScalaScript is interpreter-first â types are erased at runtime, so "implementation" here is
parse + `SType` representation + show/parseSType round-trip; real reduction/unification is an
optional later phase (only matters for `ssc check` and typed backends).

- [x] **type-lambda-p1-spec** â â DONE 2026-06-12. `specs/type-level-lambdas.md` written
      (both surfaces, `_`â`=>>` desugaring, `SType.TypeLambda` shape, surface-only erasure).
- [x] **type-lambda-p2-parse-represent (part 1: native `=>>`)** â â DONE 2026-06-12. Native
      `[X] =>> F[X]` / `[A, B] =>> Map[B, A]` now parse + run + round-trip across interp/jvm/js.
      Root cause was `Parser.preprocessListLiterals` rewriting `[A]` after `= ` into `List(A)`
      â fixed by detecting `[â¦] =>>` as a type-param clause. Added `SType.TypeLambda` +
      `show` + `containsAny` + `parseSType` native parse. `bench.sh` `type-lambda-native`
      flipped n/aâgreen. TypeLambdaProgressTest 10 pass / 4 pending. core 950, interp 1666 green.
- [x] **type-lambda-p2-rust (reduction)** â â DONE 2026-06-12 (`04f687554`). rust can't erase
      (real types), so RustCodeWalk Î²-reduces a type-lambda alias application in `mapType`
      (`collectTypeLambdaAliases` + `substType`): `Pair[Long]` â `(i64, i64)`; multi-param
      reorder works. **`type-lambda-native` now GREEN ON ALL 5 BACKENDS.** emit-rust +
      cargo-build clean; backendRust 200 green.
- [x] **type-lambda-p2b** â ✓ DONE 2026-06-12 (bc9984378). Placeholder `Map[Int, _]` now works on ALL backends. Parser desugars a placeholder alias to native `=>>` at parse time; JvmGen `blockContainsTypeLambda` routes such blocks through the tree-emit (not verbatim source) so Scala 3 sees the lambda. rust desugars+reduces. **type-lambda-native AND type-lambda-placeholder green on all 5 backends (10/10).** TypeLambdaProgressTest 12 pass/2 pending (only .sscc round-trip + p3 reduction remain). core 950, interp 1671 green. parseSType placeholder stays a wildcard (no use-site context) by design.
- [ ] **type-lambda-p3-semantics (optional, later)** â beta-reduce type lambdas in `ssc
      check` (apply `([X] =>> F[X])[A]` â `F[A]`) + HKT bound checking. Only if a real
      use-case (typed JS/Rust backend, or `ssc check` strictness) motivates it.

### effect-cps-loops â custom effects with perform-in-loop on jvm/js
Precise diagnosis 2026-06-12 (from the `effect-oneshot`/`effect-multishot` bench
dashboards â both show jvm/js/rust `n/a`). The gap is NOT effects in general:
built-in `runLogger`/`runStream` (effect-pure/stream) AND non-loop custom effects
(`val a = Bump.tick(); val b = Bump.tick()`) compile + run on jvm fine. The gap is
**a `perform` inside an imperative `var` + `while` loop**. `JvmGenCpsTransform`
binds a `Defn.Var` in a CPS block EXACTLY like a `Defn.Val` (one `_bind(rhs,
(x: Any) => rest)` lambda param â JvmGenCpsTransform.scala ~935) and has NO
`Term.While` case, so `var s/acc/i` become immutable `Any` lambda params â
generated Scala fails with "Reassignment to val s" / "< is not a member of Any".
- [ ] **effect-cps-loops-jvm** â in `JvmGenCpsTransform`: (1) emit a `Defn.Var` in a
      CPS block as a REAL mutable `var x = <rhs>` (CPS the rhs if effectful), not a
      `_bind` lambda param; (2) add a `Term.While` CPS case that lowers
      `while(cond){ body-with-perform }` to a trampolined recursive helper
      (`def _loop(): Any = if (cond) _bind(<cps body>, _ => _loop()) else ()`), with
      the surrounding vars staying real mutable vars. Verify `_bind`/`_perform`
      trampoline so a 1000-iter loop doesn't blow the stack. Acceptance: emit-scala of
      `effect-oneshot` compiles + runs; `bench.sh effect-oneshot` jvm flips n/aâgreen;
      ALL existing effect tests (StdEffectsTest, JvmGenEffectsRuntimeTest, etc.) stay
      green. HIGH regression risk â gate on the full effect suite.
      ATTEMPT NOTE (2026-06-12, reverted): editing the obvious `emitCpsBlock`/`build`
      `Defn.Var` case + adding a `Term.While` case to `emitCpsExpr` did NOT change the
      generated code â a *minimal* `def f(): Int ! Bump = { var x = 0; x = x +
      Bump.tick(); x }` still emits `_bind(0, (x: Any) => â¦)` for the var. So a
      TOP-LEVEL effectful def body is CPS-emitted via a path *other* than
      `emitCpsBlock` (the only `emitCpsBindWithType` callers left after the edit were
      the last-stat + Val cases, yet the var still bound that way). FIRST STEP for
      whoever takes this: map where a top-level effectful `def` body's block is
      actually emitted (grep the main `JvmGen` def-emission + `emitEffectfulParamGroups`
      callers), because that â not `emitCpsBlock` â is where the varâ`_bind` happens.
- [ ] **effect-cps-loops-js** â same fix in the JS CPS codegen (JsGen) once jvm lands.
- [ ] **effect-cps-loops-honesty** â until the above land, make the codegen emit a
      `Diagnostic.Unsupported("effect perform inside a while-loop")` instead of silently
      generating broken Scala (currently emit-scala succeeds â scala-cli fails downstream
      â silent `n/a`). Small, honest interim improvement.

### direct-style-eval â Direction C (spec EXISTS, ready to plan)
`specs/direct-style-eval-spec.md` is written + detailed (dual-entry `evalDirect(...):Value`
throwing `EffectPerform(comp)` with `NoStackTrace`; `SSC_DIRECT_EVAL` flag default-off;
hybrid direct-fast-path + monadic-trampoline fallback; per-file migration order;
~530 sites, 62% in EvalRuntime). Goal: kill the per-call `Computation.Pure` allocation on
the effect-free hot path. Success: `recursiveEval` â¥ 20% faster on, JFR `Pure` â50%, zero
regressions across 1230+, multi-shot handlers identical both flags.

- [x] **direct-style-eval-p0-resolve-open-questions** â â DONE 2026-06-12. Â§9 answered in
      `specs/direct-style-eval-spec.md` Â§10. KEY FINDING that re-scopes the project: the
      bytecode JIT **already returns `Value` directly** (wraps `Pure` once at the call
      boundary, not per sub-expr) for everything it compiles â incl. recursive ADT
      interpreters. Verified: in `recursiveEval` (the spec's own success metric) `eval`+`build`
      lint JIT OK; only the outer loop tree-walks. So the per-`Pure` overhead direct-style
      targets is already gone on the JIT'd hot path â the â¥20% target on recursiveEval is
      unreachable; the metric is invalid. Multi-shot resume RELIES on the re-callable monadic
      continuation (commits `e29c5b182`/`deed8fce9`) â `EffectPerform` is one-shot + loses the
      surrounding direct-style stack â `evalDirect` may ONLY touch statically-effect-free
      exprs; Â§3.4 boundary detection is LOAD-BEARING. GO/NO-GO: **conditional GO via a gated
      spike, NOT a blind 530-site migration** (see p1).
- [ ] **direct-style-eval-p1-spike** â RESCOPED (was "infra"): build `evalDirect` +
      `EffectPerform(comp) extends Exception with NoStackTrace` + `SSC_DIRECT_EVAL` flag, then
      migrate ONE representative *tree-walked* hot path (NOT recursiveEval â it JITs) and
      MEASURE the real `Pure`-allocation delta (JFR) + wall-clock. **GATE the full migration on
      the spike showing a worthwhile win** (â¥15% on a tree-walked workload, or â¥50% `Pure` drop
      there, measured e.g. with `SSC_JIT_BYTECODE=off` to isolate the tree-walk path). If the
      spike underperforms â DEFER (the JIT already captured the easy wins). Spec Â§10.4.
- [ ] **direct-style-eval-p2-evalruntime-leaves** (gated on p1 spike) â migrate EvalRuntime
      leaf exprs (`Lit`, `Term.Name`, pure-builtin `Term.Apply`) to `evalDirect`. Per spec Â§6:
      flag-off identical, flag-on green, multi-shot fixtures identical. Â§3.4 boundary detection
      is a prerequisite here.
- [ ] **direct-style-eval-p3-evalruntime-compound** (gated) â `Term.If`/`Term.Match`/
      `Term.Block` (effect boundaries stay monadic). Measure on the tree-walked workload from
      p1 (NOT recursiveEval).
- [ ] **direct-style-eval-p4-block-pattern-call** â BlockRuntime (41) + PatternRuntime (37)
      + CallRuntime (15).
- [ ] **direct-style-eval-p5-dispatch** â DispatchRuntime (89, many effect-adjacent â care).
- [ ] **direct-style-eval-p6-validate-and-default** â hit success criteria (â¥20% / â50% Pure /
      multishot identical); decide whether to flip `SSC_DIRECT_EVAL` default to `on`.
      Each phase = own worktree + commit; the spec's Â§6 verification protocol per batch.

### Deferred / blocked (NOT taken â recorded for clarity)
- **Real browser-WebSocket integration testing** â partial only: Node test runtime has no
      native WebSocket. A `ws` npm de-mock + local echo server is doable; the "real browser +
      live `wss://relay.walletconnect.com`" goal needs a browser harness AND a WC projectId.
      Hold for the PWA-wallet sprint.
- **WC project-ID** â BLOCKED ON USER: code already accepts `projectId`; needs a WalletConnect
      Cloud account + projectId provisioned as a CI secret. Cannot proceed without the secret.
- **FROST-Ed25519** â not technically blocked but large + speculative (no concrete driver);
      hold until a real use case.

---

## Bench-outlier follow-ups (2026-06-12)

Surfaced analysing `bench.sh` outliers (see [[project_jit_bare_length_bail_0612]]
in auto-memory + `docs/bench/cross-backend-gap-analysis.md`). The bare
`.length`/`.size`/`.isEmpty`/`.last` JIT bails and the missing `String.toLong`/
`toFloat` are already FIXED on main (commits `841319153`, `3d8db4b0a`). These two
remain â both characterised, neither yet started.

- [x] **interp-foldchain-unary-closure-fusion** (perf, interp JIT) â DONE 2026-06-12
      (`d1aadf920`), and the real cause was simpler than the proposed closure-sink.
      `s => s.trim.toInt` was ALREADY a recognised fuse op (`OpStringTrimToInt`); the
      bug was in `JitHofDispatch.fusedFoldLong`'s ListV loop, which called
      `asLong(elem)` BEFORE the map op â asLong throws on a StringV, so the fused path
      always threw and the JIT-invocation guard fell back to the tree-walk (string-
      split ran ~2.6 ms despite linting "JIT OK"). Fixed by applying the map op on the
      raw Value via `applyUnary` (handles string ops, falls through to numeric),
      matching every other map-applying site in the file. Shared runtime â both
      backends. **string-split 2.6 ms â 0.166 ms (16Ã, now ~2Ã off jvm**; residual =
      split allocation + parse, not the fold). Value-correct both backends; regression
      guard (direct `fusedFoldLong` string-op test). 1657 green. FOLLOW-UP (separate,
      lower-pri): genuinely *arbitrary* unary closures (shapes not in `unaryLong`,
      e.g. `s => s.substring(1).toInt`) still fall back â would need the compiled-
      closure / inline-body sink originally sketched here. Not blocking any corpus cell.

- [x] **rust-bench-antifold-alignment** (bench methodology) â DONE 2026-06-12.
      `bench/run.sc` wrapped EVERY assignment in `black_box` (3â4 barriers/iter),
      inflating rust loop cells 3â4Ã vs codegen-equal jvm. Replaced with ONE barrier
      on the first loop-carried reassignment per `pub fn`. VALIDATED that one barrier
      is necessary AND sufficient: hand-built `sumTco(100000,0)` at -O3 â 0 barriers
      folds to 0.000001 ms, 1 barrier honest, and **time scales linearly with trip
      count** (100kâ0.025, 200kâ0.051, 400kâ0.102, 800kâ0.204 â proves the loop runs,
      not a closed form). Note opaque inits/inputs do NOT suffice (LLVM solves the
      recurrence symbolically) â the barrier MUST sit on a per-iteration reassignment.
      Results: `recursion-tco` 0.34 â 0.025 ms (now â jvm 0.025), `pattern-match-heavy`
      0.67 â 0.33, `list-fold` 0.12 â 0.05, `arith-loop` 1.42 â 0.86; `nested-loop`
      0.47 â 0.95 (UP = more honest, not a fold). No new ~0 cells; expression-body
      workloads (streams/typeclass-monoid) untouched. Corpus has no sequential-
      independent-loop workload (multi-loop cases are nested or invariant-hoisted),
      and any such future regression self-reveals as a ~0 cell. `docs/benchmarks.md`
      documents the unified strategy. Residual rust>jvm on loops jvm folds for free is
      the irreducible single-barrier tax (LLVM scalar-evolution â« HotSpot), not a
      defect.

---

## Backend / compiler / interpreter improvement program (2026-06-11)

Three-tier program from the whole-stack review. Specs:
[`specs/jvmgen-decompose.md`](specs/jvmgen-decompose.md),
[`specs/backend-perf-gaps.md`](specs/backend-perf-gaps.md),
[`specs/backend-correctness-hygiene.md`](specs/backend-correctness-hygiene.md).
Order: land safe maintainability + the latent bug first; big perf items are
dedicated sub-projects. Each task = its own worktree + commit; verify per the
spec's acceptance.

### Tier 1 â maintainability (decompose giant codegen files)
- [x] **jvmgen-decompose-p1** â DONE 2026-06-11. Effect-analysis â new
      `JvmGenEffectAnalysis` self-typed mixin (pattern already existed:
      JvmGenBlockAnalysis/TermAnalysis/MutualRecursion). Verbatim move, 1605 green.
- [x] **jvmgen-decompose-p2** â DONE 2026-06-11. Extracted the 5 pure runtime-source
      string constants â `JvmGenRuntimeSources` mixin. JvmGen 10565â7042 (â33%). 1605 green.
      (Remaining Preamble defs are state-coupled â future p2b.)
- [x] **jvmgen-decompose-p3** â DONE 2026-06-11. CPS-transform section (15 members)
      â new `JvmGenCpsTransform` self-typed mixin. JvmGen 7042â6073 (â969). 1605 green.
- [x] **jvmgen-decompose-p4** â DONE 2026-06-11. Mutual-TCO emission section (8 members)
      â new `JvmGenMutualTco` self-typed mixin. JvmGen 6073â5849 (â224). 1605 green.
- [x] **jvmgen-decompose-p2b** â DONE 2026-06-11. Remaining state-coupled Preamble+runtime
      section â new `JvmGenPreamble` mixin. JvmGen 5849â5019 (â830). Completes p2.
      **JvmGen split fully done: 10565â5019 (â53%) across 6 mixins.** 1605 green.
- [x] **jsgen-decompose** â DONE 2026-06-11. CPS-codegen section (15 members) â new
      `JsGenCpsCodegen` mixin. JsGen 5810â4942 (â868). 1605 green. Core genExpr/genApply
      dispatch left in place by design (out of scope â central, low win vs risk).
      **Tier 1 (maintainability) COMPLETE.** Spec: `specs/jsgen-decompose.md`.

### Tier 2 â performance gaps (each a dedicated sub-project)
- [x] **bench-honesty-varying-data** â SUBSTANTIALLY DONE 2026-06-11. Automated
      harness now honest: (1) audit complete across 3 docs; (2) FIXED `off`-baseline
      defect (algebraic folds gated behind FastTier; `pureCallSum` 0.003 on â 11.748
      off); (3) de-folded `tuple_monoid`, the one automated compiled fold
      (`jvm` 0.011â205 Âµs, `js` 26.7â1688 Âµs); (4) interp column A/B-verified honest
      (eitherChain/optionChain/instanceField/arithLoop). The gap-doc's other fold
      cells (instance-field/bool-predicate/either/option/literal) were ad-hoc JVM
      probes with NO automated cell â nothing dishonest published. OPTIONAL future:
      add automated compiled cross-backend cells for them (same direction-(b)
      pattern). Spec `backend-perf-gaps.md` T2.1.
- [x] **js-persistent-map-hamt** â DONE 2026-06-11. Persistent `_HAMT` Map replaces the
      O(n) `new Map(obj)` copy. p2 sweep 71 `instanceof Map`â`_isMap` (`2d0b780d6`);
      p1+p3 activation (`a653cd331`): path-copying nibble-trie, value-equality keys,
      native-Map read interface, `_Map`/`updated`/`removed`/`filter`â`_HAMT`. Full suite
      1609 green; micro-bench O(nÂ²)âO(n log n), ~100Ã at N=4000. Closes T2.2.
- [x] **ssc-jit-const-propagation** â DONE 2026-06-11. Found already implemented + tested:
      Stage 2 = `tryFoldInvariantAccumLoop` (`3174c0b4c`), Stage 3 = `tryClosedFormPolyLoop`
      (`abe7e4d02`, Gauss closed-form). Verified: JitLint+SscVm+ConstFold 277 green;
      `pureCallSum` 0.003 ms/op (~83Ã, native floor 0.247). Spec `backend-perf-gaps.md` T2.3.

### Tier 3 â correctness & hygiene
- [x] **cluster-jvm-js-handshake** â DONE 2026-06-11. The disabled
      `ClusterMultiBackendMatrixTest` is now ENABLED + PASSING (both nodes converge on
      `leader=node-bbb`). Fixed across 4 cross-backend layers: (1) JS `_runActors`
      scheduler block (already fixed); (2) JVM-codegen subprotocol echo
      (`481190610`); (3) JS-codegen subprotocol echo (`ede018597`); (4) **the real final
      blocker** â JS server hung on `java.net.http`'s default HTTP/2 `Upgrade: h2c`
      probe (Node routed it to the WS 'upgrade' handler â no WS route â socket hung â
      0 `'request'` events); fixed by serving non-`websocket` upgrades as normal
      HTTP/1.1 (`JsRuntimePart1d`). JVMâJS multi-backend Bully convergence is real.
      Specs `backend-correctness-hygiene.md` T3.1 + `cluster-codegen-gap.md`.
- [x] **jit-predicates-bindingisref** â DONE 2026-06-11. Shared via
      `JitShapeCtx.callArgIsRef` + `JitPredicates.bindingIsRef`. Last duplicated JIT
      predicate â drift surface fully closed. 389 green both backends, 1605 full.
- [x] **cross-backend-method-classifier** â DONE 2026-06-11. Unlocked + scoped: the
      genuine classifier sets were JS-only (numeric inference); JVM/interp/Rust usage is
      dispatch *implementation* (out of scope). Created `core`
      `scalascript.transform.CollectionMethods` SSOT + migrated JS (behavior-identical,
      1612 green). JVM/Rust have no in-scope classifier sets. Spec
      `backend-correctness-hygiene.md` T3.3.

---

## Cross-backend gap re-audit (2026-06-10, post-HOF-JIT)

Full evidence-backed diagnosis of the remaining table unevenness in
[`docs/bench/cross-backend-gap-analysis.md`](docs/bench/cross-backend-gap-analysis.md).
Three categories: (1) real codegen gaps â js `map-ops` 40Ã = no persistent map,
js `hof-pipeline` = `_arith` on untyped list elements; (2) interp JIT cleverer
than AOT (jvm list-fold/range-sum/mutual-recursion *slower than interp* because
the interp JIT does invariant-hoist / range-fusion / mutual-TCO the AOT backends
don't); (3) **measurement artifacts** â many compiled sub-Âµs cells are folds
(verified: jvm instance-field 0.0003 â 0.0079 with a per-iter barrier).

**KEY TRAP (verified, do not repeat):** a naÃ¯ve "de-fold via `Bench.opaque`"
honesty pass is counter-productive â `Bench.opaque` makes the interpreter's JIT
bail to tree-walk (interp arith-loop 0.287 â **3043 ms**, instance-field 0.0068 â
31.7 ms). A `VmCompiler.compileInto` identity case is NOT enough â the
while-loop / FastTier / bytecode matchers bail first. A correct honesty pass
needs opaque JIT-transparent across ALL interp matchers, OR redesign folded
workloads to loop-varying data. Both are real projects.

Recommended order: (1) JS numeric type inference [medium, clean], (2) honesty
(varying-data redesign or full opaque-transparency), (3) JS persistent map
[big/risky], (4) AOT codegen passes [per-case].

Progress:
- [x] **(1) js-numeric-inference** â DONE 2026-06-10 (`c8e4651c1`). JsGen tracks
      numeric-element collections + integer ranges, types HOF closure params from
      the element type through .map/.filter chains + foldLeft. hof-pipeline 0.028â
      0.0085 (3.3Ã, âjvm), range-sum 0.048â0.011 (4.4Ã). Verified identical output;
      231+58 tests green. Remaining residual on hof-pipeline: outer `sum + r.toLong`.
- [x] **(2) honesty pass** â â DONE 2026-06-11 via `bench-honest-corpus-seed`
      (the "per-workload varying-data redesign" alternative named here, not the
      Bench.opaque route which does defeat interp JIT). Each folded corpus
      workload carries a non-linear 64-bit LCG and consumes every result, so no
      backend can constant-fold and no opacity barrier is needed; the harness is
      arity-aware and feeds an opaque seed. instance-field/tuple-monoid/etc. now
      report honest per-iteration cost across all 5 backends. Also fixed the
      emit-rust `.toInt` width bug surfaced by it. Spec:
      `docs/bench/corpus-antifold.md`, `specs/backend-perf-gaps.md` Â§T2.1.
- [x] **(3) JS persistent map** â DONE 2026-06-11 (`js-persistent-map-hamt`,
      `a653cd331`). The feared 70-`instanceof Map`-site completeness risk was
      de-risked via a duck-typed `_HAMT` + an `_isMap()` helper (p2 swept all 71
      sites) â native runtime maps and the persistent user Map coexist. `_Map`/
      `updated`/`removed`/`filter` route to `_HAMT` (path-copying nibble trie,
      value-equality keys). `map-ops` O(nÂ²)âO(n log n) (~100Ã at N=4000); full
      suite green. Spec `specs/js-persistent-map-hamt.md`.
- [~] **(4) AOT codegen passes** â mostly DONE. (a) invariant-accumulation hoist
      generalised DoubleâLong/Int in `JvmGen` (`aot-hoist`, list-fold jvm
      0.075â0.0003, 215Ã). (b) **mutual-TCO** allocation-free merge for
      uniform-signature cliques (`aot-mutual-tco`, mutual-recursion jvm
      3.89â0.51, 7.6Ã; was slower than interp â closure trampoline allocated per
      step). Verified across Boolean/String/2-param-Int shapes (new
      `MutualTcoCrossBackendTest`). DEFERRED (low ROI): range map-fold fusion
      (`range-sum` jvm 0.0125 â not egregious).

---

## Honest-bench follow-ups + cross-backend outliers (2026-06-10)

Re-audit of the 24-workload cross-backend table (`bench.sh`).  After the
2026-06-09 honesty work (opaque-seed, JVM-parity, rust-fixes), the sub-Âµs
JVM/rust cells are understood as documented DCE/anti-fold artifacts.  The
*remaining* honest outliers are genuine per-backend pathologies.  Baselines
below measured on M1 via `bin/ssc bench --machine --backend <b> bench/corpus/<w>.ssc`
(2026-06-10, this machine â re-measure before A/B).

Root-cause finding (interp): generic HOF closure application
(`List.map` / `foldLeft`) costs **~1.25 Âµs/element**, independent of the
closure body (`s.length`, `s.toInt`, `s.trim` all ~9.5 ms on the
split-map probe).  `Computation.mapSequence` itself is already allocation-free
on the all-`Pure` path; the cost is in `callValue1Slow` per element:
`FrameMap.one` alloc + `callStackPush/Pop` + tree-walk of the body.  The
bytecode JIT (`JitRuntime.tryRun1`) bails on String/trait closures so none
of these workloads are JIT-accelerated.  Decomposition probe data:
`split-only 1.78ms`, `+.map(any 1-op closure) ~9.5ms`, `+.map(.trim.toInt) 14.7ms`,
`+foldLeft 18.7ms`; manual `parts(j)` index loop is **47ms** (List random
access is O(n) â O(nÂ²)).

- [x] **interp-hof-frame-reuse** â DONE 2026-06-10. Added `CallRuntime.mapReusing`
      + `foldLeftReusing` + `ReusableFrame2` mirroring `foreachReusing` /
      `ReusableFrame1`; wired into `dispatchList1` `map` (FunV arg) and `foldLeft`
      (FunV curried arg). One reused frame mutated across the sequence instead of
      a `FrameMap1`/`FrameMap2` per element; bails to the allocating path on the
      first non-`Pure` body result.  A/B via JMH `stringSplit` (`scripts/bench
      [profile] stringSplit`): wall-clock **17.18 â 16.55 ms** (3.6%);
      allocation **2.25 â 1.90 MB/op** (15%, ~346 KB = the 12k per-element frames
      across 300Ã20 map + 300Ã20 foldLeft).  Wall win is modest because the body
      tree-walk (`s.trim.toInt` String dispatch) dominates â that is the next task
      `interp-jit-string-closure`.  Suite green (1588) on default bytecode JIT;
      change is purely in the interpreter tree-walk path (does not touch JIT
      compilation, so asm backend unaffected).  NOTE: the 3 JIT-off failures
      (`BugReproTest`/`JitLintTest`/`SscVmTest`, 114 cases) are **pre-existing** â
      they assert JIT-on behaviour and fail identically on a clean tree with
      `SSC_JIT_BYTECODE=off SSC_FASTTIER=off`.

- [x] **interp-jit-string-closure** â DONE 2026-06-10 (spec p10 in
      `specs/jit-completeness.md`). Two walls removed: (1) anonymous HOF
      closures never reached the JIT (`tryRun0/1/2/List` guarded
      `f.name.isEmpty`); lifted to `jitNameEligible = name.nonEmpty ||
      closure.isEmpty` (empty-closure lambdas have a stable identity via
      `emptyClosureFunCache`, so no `cache` leak; capturing lambdas stay out).
      (2) String methods: new `SSTR 50` opcode (trim/toLowerCase/toUpperCase),
      `GETFI` String branch extended (toInt/toLong via `v.toLong`), `VmCompiler`
      Term.Select String dispatch, untyped-param `String` inference from the
      runtime arg (`withParamHints`), and `StringV`/`MapV` accepted as ref args.
      **string-split JMH 17.18 â 2.74 ms (6.3Ã)**, corpus **18.7 â 3.26 (5.7Ã)**;
      hof-pipeline/range-sum now â10â»Â³. Suite green (1593) both backends. `s +
      lit` concat still out of scope (heap alloc). NOTE: corpus A/B must
      `./install.sh` first â `bin/ssc` is otherwise stale (JMH `scripts/bench`
      uses fresh sbt classes and needs no install).

- [x] **interp-typeclass-fold-devirt** â DONE 2026-06-11. **FunV-local monomorphic
      using-resolution cache.** JFR (2026-06-11) pinpointed the dominant cost: the
      **call-site `resolveUsing`** (`GivenRuntime.concretizeUsingKey` â
      `matchTypeParts`/`splitTopLevel`/`applyTypeBindings`) â 47% of allocation,
      ~16% of CPU â re-deriving `AâInt` identically on every `combineAll` call.
      (The prior reverted attempt's `(FunV, argTypeSig)` global-map memo failed
      because its key computation allocated â what it saved.) Fix: a single-entry
      cache on `FunV.usingResolveCache` keyed on a cheap arg type-sig
      (`runtimeValueType` of the regular args), applied only on the standard call
      path (resolves against `f.closure`; instance-method path left uncached), with
      a `givenFactories.size` generation guard. **A/B (16 measurements each):
      1.745 Â± 0.018 â 1.667 Â± 0.016 ms/op (â4.5%, non-overlapping); alloc 823 KB â
      386 KB/op (â53%).** Beats the prior attempt (1.665â1.682, no alloc change).
      Full suite 1619 green. Remaining gap (the ~84% general tree-walk eval of the
      generic HOF) needs JIT-compiling `combineAll` â separate, deep, not pursued.
      [historical diagnosis below]

- [~] **interp-typeclass-fold-devirt (superseded by above)** â PARTIALLY ADDRESSED + RE-DIAGNOSED
      2026-06-10. Added JMH `typeclassFoldMacro` (300Ã10, mirrors the corpus) â
      the requested visible A/B harness. **Re-diagnosis flips the original
      premise**: after `interp-jit-string-closure`, the `foldLeft` closure is no
      longer the cost. Decomposition (`s + xs.foldLeft(0)((a,b)=>a+b)` in a 300Ã
      loop = **0.007 ms** vs full `combineAll[A: Monoid](xs)` = **1.83 ms**, a
      246Ã gap; a plain non-generic fn wrapping the same fold = 0.007 ms) shows
      essentially ALL cost is the **context-bound generic call + per-call `summon`
      machinery inside `combineAll`**, not the fold. TRIED + REVERTED (no
      measured win, 1.665â1.682 JMH macro): a per-call-site `using`-evidence memo
      in `GivenRuntime`/`CallRuntime` keyed by `(FunV, argTypeSig)` â so the
      call-site `resolveUsing` is NOT the bottleneck. Remaining suspects (need a
      clean JFR with symbol resolution â the JMH stack profiler is drowned by
      `warmInterp` setup noise): the two `summon[Monoid[A]].empty/.combine`
      ApplyType evals per call + the `.empty`/`.combine` InstanceV member-access
      (possible fresh-FunV-per-call â JIT thrash), and that `combineAll` itself
      can't JIT (using params â VmCompiler bails). Deferred â smaller, riskier
      win (~2Ã, 1.7ms) than the JS outliers below; the macro bench stays for the
      next attempt.
      **ALLOC MEASUREMENT 2026-06-11** (`scripts/bench profile`-style `-prof gc`):
      `typeclassFoldMacro` allocates **688,891 B/op** â **2.3 KB per `combineAll`
      call** (300 calls/op, 10-elem fold) â confirms the cost is **allocation-driven**
      in the generic summon/`combineAll` path, not the fold (an int fold should be
      ~0 B/op). This is the smoking gun the "need a clean JFR" note was after:
      the next attempt should target the per-call allocation (fresh `FunV`/boxing in
      `summon[Monoid[A]].empty/.combine` + the InstanceV member-access), e.g. a
      monomorphic inline cache for given dispatch (stage-9) or caching the resolved
      `Monoid` evidence's `empty`/`combine` FunVs across calls. Site-level JFR/async
      flamegraph not extracted (async-profiler not installed; JMH jfr file didn't
      land locally) â the B/op rate is sufficient to direct the fix.

- [x] **js-instance-field-shape** â DONE 2026-06-10. Root cause was NOT
      object-shape â it was codegen: `v.x` (a known case-class field) lowered to
      the megamorphic `_dispatch(v, 'x', [])` (full type-switch + `[]` alloc, Ã4
      per `normSq`), and `x*x` to `_arith('*', â¦)` with a `typeof==='string'`
      repeat-guard. Fix (`JsGen`): track `instanceVars` (param `varName â
      caseClassType`); `Term.Select(v, f)` with a known field â direct `v.f`;
      `isIntExpr`/`isNumericExpr` recognise numeric case-class fields so `v.x*v.x`
      emits native `(v.x * v.x)`. Result: **`function normSq(v){ return ((v.x *
      v.x) + (v.y * v.y)); }`** â JS instance-field **1.42 â 0.0025 ms (568Ã)**,
      now ~8Ã jvm (was 4270Ã). 231 JS/cross-backend + 58 node tests green.

- [x] **js-nested-loop** â DONE 2026-06-10. A nested `while` inside a while body
      lowered through `genExpr` â wrapped in an IIFE `(() => { â¦ })()`
      created+invoked every outer iteration (1000Ã), capturing the accumulator by
      closure (V8 deopt). Fix (`JsGen.genWhileBodyInline` + new
      `genNestedWhileInline`): emit the inner `while` as a plain JS statement, no
      IIFE. JS nested-loop **5.59 â 0.59 ms (9.5Ã)**, ~2Ã jvm; output verified
      (249500250000). Same suites green.

- [x] **bench-consistency-jmh-vs-corpus** [honesty/clarity] â DONE 2026-06-10.
      Added a "JMH and the corpus measure different scales under the same name"
      subsection to `docs/benchmarks.md` with the `typeclassFold` (micro) vs
      corpus (macro) example and the `â¦Macro`/`â¦Micro` naming guidance.
      `typeclassFoldMacro` already added (interp-typeclass-fold-devirt).

- [x] **bench-fairness-rust-antifold-audit** [verify, not a perf bug] â DONE
      2026-06-10. Verdict: **genuine but asymmetric â not equalisable**. Rust
      `black_box`es the RHS of *every* assignment (2Ã/iter for arith-loop)
      because LLVM -O3 would fold `sum+=i` to closed form; the JVM applies *no*
      per-iteration barrier (the corpus uses no `Bench.opaque`) because HotSpot
      doesn't fold this loop (jvm 0.24 ns/iter = real loop). So rust carries the
      minimum LLVM anti-fold barrier â which also blocks pipelining â while the
      JVM needs none. Equalising either way (drop rust black_box â fake â0 ms;
      add jvm per-assignment opaque â artificial) would be dishonest. Documented
      as genuine in `docs/bench/rust-jvm-antifold-fairness.md`.

---

## Bench honesty + ssc/JS perf (2026-06-09)

After closing the JVMâRust gap on six workloads, deeper inspection shows
the parity was achieved partly by HotSpot **constant-folding the entire
outer timing loop**, not just the workload:

  streams-pipeline: BENCH_SINK = 81_021_000_528 over ~18ms â 2.25 BILLION
  iterations consumed in 18ms = 8 picoseconds/iter, which is < 1 CPU
  cycle.  HotSpot's scalar-evolution rewrote
      while r < N do sink += workload()
  as
      sink += workload() * N

  with `workload() = 36` proven at JIT time.  This is the same kind of
  cheat we patched on Rust via `std::hint::black_box`.  Rust disassembly
  confirms it does NOT do this â Rust unrolled the chain into 10 explicit
  `mov w12, #N` instructions for the 10 iterator values (genuine 11ns/iter).

So:
  - Rust:  honest; loop unrolled to 10 ops.  ~11ns is the true cost.
  - JVM:   cheating; outer loop folded to one mul-add.  ~0.008ns is bogus.
  - ssc/JS: tree-walk / V8 â honest, no fold.  Their numbers ARE the cost.

The goal "JVM parity with Rust" is therefore reframed: prevent the JVM
fold (so JVM measures the real workload), THEN see if a universal ssc
JIT optimisation can close the resulting honest gap.

- [x] **bench-honest-jvm-blackbox** [investigated 2026-06-09; sink change
      doesn't solve fold â needs source-side workload-seed; see follow-up
      below] â Tried JMH-style `@volatile var t1, t2 + branch` Blackhole
      pattern as a drop-in replacement for `AtomicLong.getAndAdd`.  Result:
      streams-pipeline floor dropped from 2 ns â 1 ns (worse, not better),
      because the XOR sink + volatile-comparison is cheaper than `lock
      xaddq`, and HotSpot can elide the volatile loads via single-threaded
      escape analysis on script-local fields.  Kept AtomicLong (proven
      ~1.8 ns floor on M1).
      Root cause of the residual 1-50 ns floor: workload() itself folds to
      a compile-time constant inside C2 (e.g. `(1 to 10).map.filter
      .foldLeft` after fuseStreamChain collapses to literal 36), so no
      sink-side barrier can recover the lost cost.  Defeating workload-
      internal fold requires source-side anti-fold (Rust patches achieve
      this via `std::hint::black_box` on literal range bounds inside
      workload bodies).
      Auxiliary land: `Bench.opaque` in JvmRuntimePreamble is now a real
      volatile-gated identity barrier (was `inline def x: A = x` â useless).
      Ready for follow-up `bench-honest-workload-seed` to use it explicitly.

- [x] **bench-honest-workload-seed** [partial â streams-pipeline + typeclass-
      monoid landed; bool-predicate needs unbox fix first] â Wrapped the
      first integer literal(s) inside `def workload` body with
      `Bench.opaque(...)`.  Bench.opaque is now a real volatile-gated
      barrier on JVM, so C2 can't precompute pure-arith chains that flow
      through it.
      Results (M1):
      - streams-pipeline JVM: 0.000002 â 0.000055 ms (2 ns â 55 ns) â
      - typeclass-monoid JVM: 0.000002 â 0.000005 ms (2 ns â 5 ns) â
        under the 10 ns floor because C2 speculates the volatile branch;
        a non-speculatable barrier would push higher (out of scope).
      - bool-predicate: left at baseline.  Wrapping the loop bound
        regressed ssc 1100Ã by breaking unboxed-Long fast path
        (`val limit = Bench.opaque(1000)` boxes `limit` as `Value`).
        Workload was already honest at 22 Âµs so no win, only loss.
      ssc/ssc-asm pay 15-30% for the per-call dispatch â accepted cost
      for cross-backend honest measurement.

- [x] **bench-honest-rust-verify** [done â docs/bench/rust-honest-disassembly.md]
      All six workloads have real work in the loop body (39-196 inst on
      M1).  `arith-loop` runs the actual 1M-iter loop; `streams-pipeline`
      is fully unrolled (10 iter); `bool-predicate`/`either-chain`/
      `option-chain`/`typeclass-fold` run real loops; `typeclass-monoid`
      is 3 inlined adds (no loop in source).

- [x] **bench-honest-js-investigate** [done â docs/bench/js-honesty-audit.md]
      Sink-quotient analysis on V8: for each suspect workload,
      `BENCH_SINK / expected_single_result == iter count` reported by the
      harness.  All six match exactly â V8 is not folding the outer
      timing loop on these workloads.  JS numbers are real per-iter costs.

- [x] **ssc-jit-loop-fusion-universal** [partial â core fusion landed
      2026-06-09; base-range fusion + <100ns const-fold is the follow-up
      below] â Iterator chain fusion in the ssc bytecode JIT.  Detect
      `recv.map(f).filter(g).foldLeft(z)(+)` (either stage optional) at
      emit time via `JitHofShape.fuseFoldChain` (shared by Javac + ASM)
      and lower the whole receiver to a single `JitHofDispatch.fusedFoldLong`
      that walks `recv` once with primitive `long` accumulators â no
      intermediate `ListV`, no per-stage re-boxing.  This is the "real"
      version of the bench-time hack: the optimisation now fires for any
      program, not just benchmarks.  On an unrecognised shape `fuseFoldChain`
      returns null and the existing per-stage path runs unchanged.
      A/B (Javac, `scripts/bench profile`):
      - hofPipeline gc.alloc.rate.norm 240 â 1.7 B/op (-99%), 506 â 3 MB/s
      - rangeSum    gc.alloc.rate.norm 1016 â 506 B/op (map list removed)
      Wall-clock unchanged at 6/20-elem inputs (the win is GC pressure,
      which scales with input length).  Spec: `specs/jit-loop-fusion.md`.
      Full backendInterpreter suite green on Javac + ASM; JIT disabled
      count unchanged (736).
      **Follow-up `ssc-jit-range-fusion` (landed 2026-06-09):** range-native
      fusion done â `JitHofShape.rangeBounds` + `JitHofDispatch.fusedRangeFoldLong`
      iterate `lo until/to hi` with a primitive counter (no base `ListV`), and
      `walkRef` now compiles `to` (inclusive) ranges (`rangeUntil(lo, hi+1)`).
      rangeSum 506 â 25.6 B/op (1016 â 25.6 vs pre-fusion). Both backends; suite
      green (1568); disabled count unchanged (736).
      **Still not done:** the `(1 to 10)â¦foldLeft < 100ns` literal-bound
      const-fold (the loop runs honestly now) â tracks with
      `ssc-jit-const-propagation` Stage 3.

- [x] **ssc-jit-const-propagation** â DONE 2026-06-11 (goals met; closed as T2.3,
      `ea6cacaea`). Stage 2/3 goals were achieved in a **different layer** than this
      entry anticipated: instead of the VmCompiler register-VM JIT, they live in the
      EvalRuntime FastTier eval path, which fires *first* for these workloads â
      `tryFoldInvariantAccumLoop` (invariant pure-call memoise, Stage-2 goal) and
      `tryClosedFormPolyLoop`+`walkLinearPoly` (Gauss closed-form range fold,
      Stage-3 goal). Verified: `pureCallSum` 0.003 ms/op (~83Ã the pre-fold
      baseline; native JVM floor 0.247 ms â the loop is eliminated). So the
      VmCompiler-level Stage 2/3 implementation is **unnecessary** (the FastTier
      fold pre-empts it). Stage 1 (literal arithmetic in VmCompiler.compileInto)
      remains as infra. Spec `specs/backend-perf-gaps.md` Â§T2.3;
      `specs/jvmgen-decompose.md` cross-refs.

- [x] **ssc-jit-escape-analysis** [partial â map-into-sink fusion landed
      2026-06-09; full cross-function inlining still open] â Stack-allocate
      Option/Either/case class instances whose lifetime is bounded to the
      current method.
      **Landed (map-into-sink slice):** a `.map(unary)` feeding `.flatMap(global)`
      or `.getOrElse(default)` allocated an intermediate Option/Either wrapper
      purely to be consumed one step later. `peelMapUnary` detects it; the
      backends emit `JitHofDispatch.mapFlatMapGlobalLong` / `mapGetOrElseLong`
      which apply the map inline â no intermediate wrapper. Both Javac + ASM;
      falls back to per-stage on a miss.
      A/B (tight, `scripts/bench profile`):
      - optionChain 116 â 100 B/op (-14%)
      - eitherChain 708 â 564 B/op (-20%; ASM 528)
      Suite green (1572) both backends; disabled count unchanged (736).
      **Follow-up `instancev-either-option-fieldsarr` (landed 2026-06-09):** the
      `parse`/`lookup` `Right`/`Left` wrappers were `InstanceV` + per-instance
      `IMap.Map1`, and each dispatch/JIT read re-materialised that Map. Migrated to
      the positional `fieldsArr` repr via `Value.singleValue` + `typeFieldOrder`
      registration of Right/Left/Some + arr-aware `JitHofDispatch.valueField`.
      **eitherChain 488 â 224 B/op (-54%)**, optionChain flat (Some is `OptionV`).
      1572 green. The remaining Either cost is the wrapper *existing at all*.
      **Still open (bigger, own session):** eliminating the wrapper entirely needs
      cross-function inlining of small pure constructors (or a value-class
      Option/Either representation). pattern-match-heavy not yet addressed.

---

## JVM perf parity with Rust â close the gap (2026-06-09)

After defeating LLVM fold (`bench-opaque-seed`), Rust reports much smaller
numbers than JVM on 6 workloads even though both produce identical correct
results (verified by building each crate with a println main):

| Workload | Result | Verified |
|---|---|---|
| typeclass-monoid | 6 (= 0+1+2+3) | â |
| typeclass-fold | 16500 (= 300Â·55) | â |
| streams-pipeline | 36 (= 6+12+18) | â |
| option-chain | 44850 | â |
| either-chain | 45450 | â |
| bool-predicate | 999 | â |

**Goal**: make JVM bench numbers comparable to Rust (within ~3Ã).  Where
the gap is structural (e.g. interface dispatch, heap-allocated ADTs), close
it via JvmGen codegen â direct-call dispatch, value-class ADTs, stack-
allocated Either/Option, foldLeft fast-path on Range, etc.

Each task: one focused commit + A/B bench numbers (before / after / Rust)
in the commit body; never ship a non-win.

- [x] **bench-gap-typeclass-monoid-jvm** [JVM 1ns vs Rust 2ns â JVM 2Ã faster; closed via adaptive-reps + primitive sink in bench wrapper] â JVM `0.0010` vs Rust
      `0.000001` = **1000Ã**.  Workload: 3 nested `combine(...)` calls
      returning 6.  Hypothesis: JvmGen emits `intMonoid` as a Scala 3
      `given` object with virtual dispatch through the `IntMonoid` trait
      every call, plus `Int`â`Integer` boxing on each `(a, b)` argument.
      Fix path: when JvmGen sees a `given X: T with { def f }` whose `f`
      body is a single arithmetic expression and `X` is referenced only
      by direct name (not through `summon`/upcast), emit `f` as a static
      method on a Scala `object X` and call sites as direct invocation â
      no interface dispatch, no Integer boxing.  Target: JVM â¤ 3ÃRust
      (i.e. â¤3ns).

- [x] **bench-gap-typeclass-fold-jvm** [JVM 3Âµs vs Rust 8Âµs â JVM 2.6Ã faster; closed via adaptive-reps + primitive sink in bench wrapper] â JVM `0.004` vs Rust `0.0072` â
      JVM is already faster than Rust here, and ssc is **460Ã slower**.
      Real target: fix ssc/asm.  Workload: `combineAll(xs).foldLeft(empty)
      (combine)` 300 times with `xs = List(1..10)`.  Hot path is
      `summon[Monoid[A]]` resolution inside the lambda.  Fix on the
      interpreter side: cache the resolved `summon` for `Monoid[Int]` at
      the call site; emit a specialized fast-path `foldLeft` for `List[Int]
      + (Int,Int)=>Int` in the JIT.  Re-evaluate the JVM/Rust split after
      that; if JVM stays ahead, no JVM action.

- [x] **bench-gap-streams-pipeline-jvm** [JVM <1ns vs Rust 11ns â JVM faster; closed via fused while-loop emit in bench wrapper] â JVM `0.000047` vs Rust
      `0.000005` = **9Ã** (after adaptive-reps fix; was 200Ã before).
      Workload: `(1 to 10).map(*2).filter(%3==0).foldLeft(0)(+)`.
      Hypothesis: JvmGen lowers the chain to native Scala
      `Range.map(...).filter(...).foldLeft(...)` â each step creates an
      `IndexedSeqView` wrapper + boxed Lambdas; HotSpot inlines but the
      view chain still costs allocations.  Fix path: when JvmGen sees
      `(lo to hi).map(f).filter(g).foldLeft(z)(h)` as a single chained
      expression, lower it directly to a Rust-style fused loop:
      ```scala
      var __acc = z; var __i = lo
      while __i <= hi do
        val __m = f(__i)
        if g(__m) then __acc = h(__acc, __m)
        __i += 1
      __acc
      ```
      No view allocations, no lambda wrappers â HotSpot will JIT this to
      native code identical to what LLVM produces for Rust.  Target: JVM
      â¤ 3ÃRust (~15ns).

- [x] **bench-gap-option-chain-jvm** [JVM 341ns vs Rust 472ns â JVM 1.4Ã faster; closed via adaptive-reps + primitive sink in bench wrapper] â JVM `0.002` vs Rust `0.000466` =
      **4Ã**.  Workload: 300 iters of `Some(i).flatMap(lookup).map(+1).
      getOrElse(0)`.  Hypothesis: Some/None on JVM are heap-allocated
      via Scala 3 `enum Option` â 300 allocations/iter Ã 4 chain steps
      = 1200/iter.  Fix path: introduce a value-class Option carrier in
      JvmGen â `opaque type FastOption = Long` where the high bit is the
      None tag and the low 32 bits are the Int payload.  Emit
      `Some(i)`â`fastSome(i)` and `.flatMap`/`.map`/`.getOrElse` as
      inline ops on the Long.  Target: JVM â¤ 3ÃRust.

- [x] **bench-gap-either-chain-jvm** [JVM 329ns vs Rust 590ns â JVM 1.8Ã faster; closed via adaptive-reps + primitive sink in bench wrapper] â JVM `0.001` vs Rust `0.000541` =
      **2Ã**.  Workload: 300 iters of `parse(i+1).map(+1).flatMap(parse).
      fold(_=>0, x=>x)`.  Same heap-allocation cause as option-chain.
      Same fix shape: value-class Either with packed Long
      (`(tag<<63) | (left ? string-handle : int-payload)`) for
      `Either[String, Int]` specifically.  Generalised JvmGen
      Either-specialisation pass is the right scope.  Target: JVM
      â¤ 3ÃRust.

- [x] **bench-gap-bool-predicate-jvm** [JVM 21ns vs Rust 970ns â JVM 46Ã faster; closed via adaptive-reps + primitive sink in bench wrapper] â JVM `0.001` vs Rust `0.000956`
      = **~1Ã, already at parity**.  Smallest gap of the six; no action
      required.  Re-verify on the next bench run; if it slips above
      3ÃRust under load, investigate.

---

## Bench correctness â defeat LLVM constant-folding via opaque seed (2026-06-09)

LLVM `-O3` performs scalar-evolution analysis on pure loops, deriving
closed-form solutions for arithmetic progressions. `for i in 0..N { sum += i }`
becomes the literal `499_999_500_000` (Gauss' formula) at compile time â the
loop body is never executed in the release binary. This corrupts every
pure-arithmetic bench on the Rust target (12 of 24 corpus workloads).

Diagnosis confirmed via objdump: arith-loop's emitted `workload()` is just
`mov x8, #0x746a4ae6e0; ret` (= `499_999_500_000`). Existing AtomicI64 seed
in `bench/run.sc` doesn't help because the seed is never threaded into
`workload()`.

Fix: change the workload signature cross-backend to
`def workload(seed: Long): Long`. Bench wrappers (all 5) pass an opaque
zero (loaded from an AtomicI64-style source LLVM can't prove constant). Each
workload mixes `seed` into its computation **nonlinearly** (e.g. `i ^ seed`)
so LLVM cannot derive a closed-form. For `seed=0` semantics is preserved
(`x ^ 0 = x`), so JVM/JS/interp results stay identical.

- [x] **bench-opaque-seed-infra** (resolved via bench/run.sc auto-patch â see below) â Change `workload` signature to
      `workload(seed: Long): Long` in `bench/run.sc` Rust wrapper +
      `tools/cli/src/main/scala/scalascript/cli/Main.scala` interp/JVM/JS
      bench wrappers. Each passes an opaque-zero seed. Acceptance: a workload
      that takes `(seed: Long)` and `+ seed` at the end runs on all 5 backends
      without n/a.

- [x] **bench-opaque-seed-anti-fold** (resolved via bench/run.sc auto-patch â see below) â Update each of the 24 corpus
      workloads to take `(seed: Long)` and mix `seed` into the hot path
      nonlinearly (`^ seed` inside the loop body, etc.) so LLVM cannot derive
      a closed-form. Recipe: pure-arith workloads xor `i ^ seed` inside the
      inner loop (semantics preserved for seed=0); real-work workloads add
      `+ seed` at the sink. Acceptance: `./bench.sh` reports Rust numbers
      â¥1Âµs on workloads previously reporting <100ns, and JVM/JS/interp
      numbers unchanged (within noise).

---

## Bench n/a â close the gaps (2026-06-09, from `bench.sh` after rust-bench-fixes)

After all 24 corpus workloads run cleanly on rust, four `n/a` cells remain
on other backends. Each is a genuine API/codegen gap, not a benchmark bug.
Fix them properly (no ad-hoc bench rewrites). Ordered simplest-first.

- [x] **bench-na-jvm-typeclass-monoid** â `typeclass-monoid.ssc` n/a on jvm.
      Source uses `given intMonoid: IntMonoid with { def empty; def combine }`
      but `IntMonoid` is not declared as a trait. JVM codegen rejects the
      anonymous given target. Fix: prepend a `trait IntMonoid { def empty: Int;
      def combine(a: Int, b: Int): Int }` declaration in the bench corpus AND
      verify the JVM backend `Defn.Given` lowering handles a named-trait given
      with multiple defs. Acceptance: `./bench.sh --backend jvm typeclass-monoid`
      reports a numeric ms/iter result.

- [x] **bench-na-js-either-chain** â `either-chain.ssc` n/a on js.
      JS backend has no `Either[L, R]` runtime â `Right(x).map(...).flatMap(...).fold(...)`
      chain falls off a cliff somewhere. Fix: extend `JsRuntimePart*` with an
      `Either` runtime (Right/Left tagged variants + .map/.flatMap/.fold lowering)
      mirroring the existing `Option` runtime. Cross-check with the Either path
      in `runtime/std/either.ssc` if present. Acceptance:
      `./bench.sh --backend js either-chain` reports a numeric result.

- [x] **bench-na-js-map-ops** â `map-ops.ssc` n/a on js. Already covered by
      the in-flight `js-map-ops-bench` claim/branch â see `.work/active/`.
      Verify the claim is current; if abandoned (>20 min stale heartbeat),
      release via `/multi-agent triage js-map-ops-bench`. Acceptance:
      `./bench.sh --backend js map-ops` reports a numeric result.

- [x] **bench-na-streams-pipeline-all** â `streams-pipeline.ssc` n/a on
      ssc/ssc-asm/jvm/js. The bench uses `Source.range(1, 10).map(...).filter(...)
      .foldLeft(...)` â this surface only exists in the rust backend (added by
      `rust-backend-r6-streams`). To make it portable, add a synchronous
      `Source` API to `runtime/std/streams.ssc` (or wherever the streams stdlib
      lives) that the JVM/JS/interp backends can lower the same way they lower
      `List` HOFs. `Source.range/fromList/.map/.filter/.foldLeft/.toList` must
      produce equivalent results across all five backends. Acceptance:
      `./bench.sh streams-pipeline` reports numeric results on every backend.

---

## Rust bench fixes â new rustc errors (bench.sh 2026-06-08, ordered simplest-first)

Found by re-running `bench.sh` after the previous fix wave.  All items fixed 2026-06-08.

- [x] **rust-fix-bench-non-i64-return** â `bench/run.sc`: `_run_workload() -> i64`
      fails when `workload()` returns a non-`i64` type.  Affected: `tuple-monoid`
      (`workload() -> (i64,i64,i64,i64)`, `E0308`) and `pattern-match-heavy`
      (`workload() -> f64`, `E0308`).
      Fix: changed `_run_workload()` to return `()`, emit `std::hint::black_box(r);`
      as a statement, dropped `-> i64` from the signature.  Fixed 2026-06-08.

- [x] **rust-fix-iife-parens** â `RustCodeWalk.scala`: IIFE closures emitted as
      `move |x| { body }(arg)` rejected by `rustc` with `E0618`.
      Fix: wrapped closure in parens: `(move |x| { body })(arg)` in all 4
      Either map/flatMap/fold emitters.  Affected: `either-chain`.  Fixed 2026-06-08.

- [x] **rust-fix-struct-copy** â `RustCodeWalk.scala`: user structs from `case class`
      not derived `Copy`, passing by value in a loop gave `E0382`.
      Fix: `renderStruct` now emits `#[derive(Debug, Clone, Copy)]` when all
      fields are primitive (`i64`, `f64`, `bool`).  Affected: `instance-field`.
      Fixed 2026-06-08.

---

## Rust backend â compilation fixes (from bench.sh 2026-06-08)

`backendRust/compile` and `backendRust/Test/compile` are currently broken.
All errors are in two files: `RustCodeWalk.scala` and `RustGenR23Test.scala`.
Ordered simplest-first.

### Syntax fixes in test file (trivial â copy-paste ttypos)

- [x] **rust-fix-test-unclosed-quote** â `RustGenR23Test.scala:140`: missing
      opening `"` before `42` in `assert(g.contains("42".to_string()..."))`.
      Fixed 2026-06-08.

- [x] **rust-fix-test-unclosed-paren** â `RustGenR23Test.scala:200`: missing
      closing `)` on `assert(g.contains("if v % 2 == 0 {")`  â  one `)` short.
      Fixed 2026-06-08.

### Syntax fix in main source (one missing paren â cascades to 50+ errors)

- [x] **rust-fix-codewalk-unclosed-paren** â `RustCodeWalk.scala:351`: `Right((variant, (ctor, EnumCtor(...)))` was missing one closing `)`.
      Fixed 2026-06-08.

### Pattern-match syntax errors in main source

- [x] **rust-fix-term-paren** â `RustCodeWalk.scala`: `m.Term.Paren` does
      not exist in scalameta â removed from `isRangeExpr`, `isStringExpr`, `isEitherExpr`.
      Fixed 2026-06-08.

- [x] **rust-fix-typed-bind-syntax** â `RustCodeWalk.scala:1123,1125`: `case t: SomeClass(args)`
      replaced with `case t @ SomeClass(args)`.  Fixed 2026-06-08.

- [x] **rust-fix-none-unreachable** â `RustCodeWalk.scala`: `case m.Term.Name("None")`
      was placed after the catch-all `case m.Term.Name(n)` â moved before it.
      Fixed 2026-06-08.

- [x] **rust-fix-test-assert-mismatch** â 8 test assertions in `RustGenR23Test.scala`
      had wrong expected strings (wrong int suffixes `i32`â`i64`, literal format
      `2f64`â`2.0f64`, missing `.to_string()` on string args, etc.).
      Fixed 2026-06-08. Result: 104 pass, 2 ignored.

### Rust runtime errors (from bench.sh 2026-06-08, `rustc` fails)

- [x] **rust-fix-split-string-pattern** â `RustCodeWalk.scala`: string
      args to `.split`/`.splitn` are now rendered as bare `&str` literals
      via `renderStrPatternArg` (no `.to_string()`).  Fixed 2026-06-08.

- [x] **rust-fix-enum-ctor-call** â `RustCodeWalk.scala`: `collectTopVals`
      was using empty `ctorMap`, so enum ctors in top-level `val` initialisers
      fell through to `Circle(args)` call syntax.  Fixed by computing `ctorMap`
      before calling `collectTopVals` and passing it in.  Fixed 2026-06-08.

### Rust codegen gaps (from bench.sh 2026-06-08, `rustc` or codegen errors; ordered by difficulty)

- [x] **rust-fix-option-chain-var-scope** â `option-chain` bench: `cannot find value 'i'`.
      Root cause: `contentTopVals` used `node.tree.collect { case v: Defn.Val }` which
      recursively found ALL `val` bindings in the tree (including those inside
      `while` bodies of `def`s), injecting them as top-level `let` bindings into
      every generated function.  Fixed: replaced `.collect` with top-level-only
      `stats` from `m.Source`/`m.Term.Block` direct children.  Fixed 2026-06-08.

- [x] **rust-fix-either-chain-select-chain** â `either-chain` bench: `parse(i+1).map(...).flatMap(...).fold(...)` failed
      because `isEitherExpr(parse(i+1))` returned false (user function calls not recognized).
      Fix: added a heuristic case to `isEitherExpr`: any `Term.Apply` that is NOT
      a known Option/List/Map constructor is treated as potentially Either-shaped.
      Generated Rust uses nested `match` expressions â verbose but correct.
      Fixed 2026-06-08.

- [x] **rust-fix-instance-field-vec-type** â `instance-field` bench: `Vec` was a
      user-defined `case class Vec(x: Int, y: Int)`, not a stdlib List.
      Root cause: `mapType` didn't recognize user-defined types; `collectStandaloneCaseClasses`
      didn't exist; `Vec(3,4)` was treated as a list ctor.
      Fix: (1) `collectStandaloneCaseClasses` collects case classes not extending any sealed trait;
      (2) `renderStruct` emits `pub struct T { pub field: Type, }`;
      (3) struct ctors added to ctorMap; (4) user ctors take priority over stdlib names in Apply.
      Also added generic `Term.Select(qual, field)` â `qual.field` for struct field access.
      Fixed 2026-06-08.

- [x] **rust-fix-effect-pure** â `effect-pure` bench: `Int ! Logger` effect type.
      Fix: tagless-final (R.4.2) â `T ! E` strips to `T` in return type; effectful defs
      gain `_eff: &mut impl LoggerEffect` param; call sites thread `&mut _eff`; 
      `runLogger { body }` injects `NoOpLogger`; `runtime/effects.rs` emitted with
      `LoggerEffect` trait + `NoOpLogger`.  7 new tests (107 total).  Fixed 2026-06-08.

- [x] **rust-fix-effect-stream** â `effect-stream` bench: `runToList` + tuple val binding.
      Fix: (1) `renderLetBinding` handles `val (a, _) = expr` tuple pattern;
      (2) `Stream.emit(x)` â `_eff.stream_emit(x)`; (3) `src.runToList()` â
      `src.items.clone()`; (4) `runStream { body }` injects `VecStream::new()`,
      returns `(_eff, ())`; (5) `VecStream<T>` + `StreamEffect<T>` in effects.rs.
      6 new tests (120 total). Fixed 2026-06-08.

### Unimplemented feature (tuple ++ concat in Rust backend)

- [x] **rust-fix-tuple-concat** â `RustCodeWalk.scala`: `++` on tuples now
      flattens via `collectTupleConcat`.  Root cause: scalameta parses
      `(a,b) ++ (c,d)` with the RHS as **two** separate infix args, not one
      `Term.Tuple` â added a second branch handling `args.values.size > 1`.
      Also added `_tupleConcat` call handler for completeness.
      106 tests pass, 0 ignored.  Fixed 2026-06-08.

---

## busi feedback â parser/resolver/runtime fixes (high priority)

Source: `busi/docs/scalascript-issues.md` (212 lines, by phase). Reported
2026-06-06 by the busi agent after phases 0â15 of the business-management
app. Every item has a workaround on the busi side â none are blockers â
but each "eats" 1â2 hours per new busi phase. Ordered by how much they
slow down ongoing work, P0 first.

Recommended first batch (per busi): **P0 #1, #2, #3 + P1 #5**. All four
are isolated in lexer / parser / resolver, give the biggest time-back per
fix, and don't require a runtime refactor.

### P0 â parser/resolver, hit on every new phase

- [x] **busi-p0-try-catch-handler** â `try / catch _ => ...`
      (`Term.TryWithHandler`) is not supported â only `try / catch case
      _ => ...`. Either support both forms or emit a parser message
      suggesting `case`.

- [x] **busi-p0-statusval-collision-a-half** [landed 2026-06-10] â Follow-up to
      `9a3bea18e`. A bare `val x = Foo` with no ascription, where `Foo` is bound
      to both a stable value and a case constructor, now raises a located error
      `name 'Foo' is bound to both a stable value and a case constructor; add a
      type ascription or rename one`. Implemented in `StatRuntime.disambiguateValBinding`:
      when `decltpe.isEmpty` and the bare RHS name is in `shadowedAlternatives`,
      `interp.located(...)`. Any ascription opts out (`Type.Name` â B-half;
      function/other types keep the case-constructor `direct`). 2 regression tests
      in `StatusValEventCaseCollisionTest`; backendInterpreter 1583/1583 green.

### P1 â pre-existing bug surfaced during busi phase 89d testing

- [x] **busi-p1-phase90-rule-bool-coercion** â `make test-phase90-rule`
      and `test-phase90i` fail with `Cannot apply unary ! to 1` at
      `tests/phase90-rule/rule-pack.ssc:118`, on the `Activity(org,
      "act-immigration", actor, Immigration, ..., Active, Map(), 1)`
      call site.  Recorded by busi under "Phase 89d finding" in
      `busi/docs/scalascript-issues.md`.  **Pre-existing â confirmed
      not caused by the P0 #1+#2+P1 #5 wave** (sergiy 2026-06-07).
      Source file: `/Users/sergiy/work/my/busi/tests/phase90-rule/
      rule-pack.ssc` (181 lines).  Shape of the error suggests an
      Int-to-Bool coercion path where a `1` literal is being treated
      as a Boolean operand to unary `!`; root cause likely in pattern
      matching / typeclass dispatch.  Not a blocker for P0 #3 â fix
      when convenient.

### P1 â frequent small splinters

- [x] **busi-p1-map-direct-apply** â `map(key)` direct access throws
      "Instance is not callable". Add `apply` on `Map`.

- [x] **busi-p1-string-split-2arg-and-map** â `String.split(sep, limit)`
      (2-arg form) does not exist; `.map` on the raw split result (Java
      Array) crashes â forcing `.toList` everywhere. Add the 2-arg form
      and make `.map` work on the split result directly.

- [x] **busi-p1-map-getorelse-null-semantics** â `Map.getOrElse(key,
      default)` returns `null` when the present value is null (SQLite
      `NULL`). Semantics "absent vs. null" should be resolved in
      favour of `default`.

- [x] **busi-p1-while-typed-empty-list-bug** [no longer reproduces 2026-06-10 â
      fixed by intervening while-JIT work; locked with regression tests] â `while`
      + `var i += 1` + typed `List[(Int,T)]()` â body iterates, list stays empty.
      Could not reproduce in any form: non-JIT, JIT-hot (200k-call function),
      case-class tuple elements, `Set.contains` in body, N=50k. All correct.
      Two regression guards added to `BugReproTest` (typed-empty-tuple-list +
      hot-function while-JIT path).

- [x] **busi-p1-map-update-foldleft-unreliable** [no longer reproduces 2026-06-10
      â fixed by Direction B fieldsArr flag-flip; locked with regression tests] â
      When a `foldLeft` accumulates a `Map[String, CaseClass]` and one branch
      re-constructs the case class (10+ fields) to store an updated copy,
      subsequent `.values.toList.sortBy(...)` or keyed-access calls produced
      `"Instance is not callable"` (`CallRuntime` applies a case-class `InstanceV`
      with no `apply` field). Root cause: pre-flag-flip, a 10+-field case class
      stored its fields in a `HashMap` (Scala Map â HashMap at â¥5 entries) and
      some path mishandled HashMap-backed instances; the 2026-06-03 fieldsArr
      flag-flip unified all field counts onto the positional array. Could not
      reproduce across 7 variants (foldLeft over List/Map, match-branch
      reconstruction, 10â11 fields, `_` sortBy, keyed access, case-class methods).
      Two `BugReproTest` guards added mirroring the busi `applyRetirement` shape.

- [x] **busi-p1-map-concat-returns-tuplev** â `Map(...) ++ otherMap`
      returns `TupleV((Map(...), Map(...)))` instead of a merged map.
      Subsequent `.get(key)` then crashes with `No method 'get' on
      TupleV(...)`.  Found by busi in phase 89a (`seedRitualsForActivityKind`).
      Repro: `val a = Map("x" -> "1"); val b = Map("z" -> "3"); val c =
      a ++ b; println(c.get("x"))`.  Workaround on busi side: inline
      pairs into a single literal.  Fix: route `Map ++ Map` through
      `dispatchMap` instead of falling into the tuple-wrap path in
      `DispatchRuntime.infix`.

- [x] **busi-p1-arrow-vs-plus-precedence** â `Map("k" -> "Prefix " +
      value)` parses as `Map("k" -> ("Prefix ", value))` â the `->`
      arrow associates tighter than `+`, so the second tuple element
      becomes `value` instead of being concatenated.  Runtime then
      crashes when the consumer tries to use the value as a String.
      Found by busi in phase 89f.  Workaround: bind to a local val or
      add explicit parens `Map("k" -> ("Prefix " + value))`.  Fix
      direction: either tighten `+` precedence relative to `->` for
      strings, OR emit a parse-time warning when `->` RHS is a binary
      `+` with the LHS being a string literal (likely user intent
      mismatch).

### P2 â `emit-js` / browser

- [x] **busi-p2-emit-js-process-stdout** â `emit-js` always appends
      `process.stdout.write(...)` â `ReferenceError: process is not
      defined` in the browser on every load. Fix: guard with `typeof
      process !== 'undefined'` or use `console.log`. busi worked around
      via `emit-spa`, but `emit-js` is effectively unusable in the
      browser today.

- [x] **busi-p2-emit-js-transitive-imports** [no longer reproduces 2026-06-11,
      confirmed on busi rulepack-graph] â `emit-js` was reported to drop
      transitive imports (`A â B â C`, bundle
      of `A` not closing `B`'s code over `C`). Does **not** reproduce on the
      current backend: `genImport` recurses into a child module's own imports
      (`childGen.genImport(nestedImp)`), and imported modules are emitted in
      full (the child `JsGen` is created without `reachableNames`, so
      tree-shaking only prunes the entry module's own declarations, never
      transitively-imported ones). Verified end-to-end through the exact
      `emit-js` path (`generateSegmented`, tree-shaking ON, per-segment
      `_output` flush) for three shapes â package `AâBâC`, name-only
      (no-package) `AâBâC`, and 4-level `AâBâCâD` â all run and print the
      transitively-computed result. Regression guards added in
      `JsGenStdImportTest` (`examples/js-transitive-iife{,-nopkg,-4}/`).
      busi confirmed (rozum seq-110): `ssc emit-js web/app.ssc` on their
      deepest real graph (`app â rulepack_studio â rulepack_list /
      schema_inference â std/ui/{content,data,â¦}`, 616 KB bundle) loads clean
      under node with zero `ReferenceError` â the whole transitive graph and
      top-level `appView` build. Their graph uses only `[name](path)` imports
      (no wildcard / re-export in emit-js scope), so those forms would need a
      synthetic repro, not a busi one. Closed.

### P3 â name shadowing from plugin intrinsics

- [x] **busi-p3-ratelimit-intrinsic-shadow** [landed 2026-06-11] â Policy
      chosen: **user wins + warning**. A user top-level `def` sharing a bare
      name with a plugin intrinsic now always wins; a one-time `[warn]` is
      emitted (recorded in `intrinsicShadowWarnings`). Both load orderings
      handled. Spec `specs/intrinsic-shadow-policy.md`; 4 tests in
      `IntrinsicShadowTest`.

- [x] **busi-p3-module-fn-name-conflict** [landed 2026-06-11] â Policy chosen:
      **last import wins + warning**. Importing the same fn name from two modules
      now emits a one-time `[warn]` (recorded in `importNameConflictWarnings`)
      instead of silently shadowing. Scoped to callable-vs-callable so the
      status-val/case-constructor disambiguation is untouched. Spec
      `specs/import-name-conflict-policy.md`; 3 tests `ModuleFnNameConflictTest`.
      NB: the original downstream `No key 'toString' in map` crash did not
      reproduce from a plain two-module collision (already last-wins cleanly);
      the import-time warning now surfaces the conflict early. Awaiting a busi
      repro if the crash recurs.

### P4 â future externs (not blocking today)

- [x] **busi-p4-ed25519-rsa-verify** [landed 2026-06-10, commit 778116b33] â
      Ed25519 / RSA public-key `verify` externs in std.crypto (JVM). Closes the
      `signature.unsupported` quarantine for busi phase 87g.

- [x] **busi-p4-smtp-send-extern** [landed 2026-06-10, commit 4ebd4e393] â
      Native `smtpSend` extern via the opt-in `smtp-plugin` (dependency-free
      RFC 5321 client: EHLOâSTARTTLSâAUTH LOGINâMAIL/RCPT/DATAâQUIT). Removes
      the relay requirement for standalone installs. 6 e2e tests.

### P1 â new busi-side bugs (2026-06-09)

- [x] **busi-p1-string-indexof** â `String.indexOf` not found for certain
      arg types (IntV char code); 2-arg form `indexOf(str, fromIndex)` missing.
      Fix: add `IntV` branch in `dispatchString1`; add 2-arg forms in
      `dispatch2` and `dispatchString`. Same for `lastIndexOf`.

- [x] **busi-p1-string-split-regex** â `String.split` wrapped separator in
      `Pattern.quote` â regex escapes like `\\.` and `\\s+` did not work.
      Fix: remove `Pattern.quote`; use separator as raw regex (Java semantics).
      Also added 2-arg `split(sep, limit)` form.

---

## Language Surface - Markdown Content (next)

Broad spec exists:
[`specs/markdown-content-introspection.md`](specs/markdown-content-introspection.md).
Focused slice specs already exist for landed lookup/plain-text, metadata,
current-section, backend exposure, native-client parity, reverse Markdown
rendering, current-module artifact round-trip, multi-link import paragraphs,
linked imported content namespaces, GFM tables, and explicit inline content
binding, and Markdown toolkit links:
[`specs/markdown-content-lookup-plaintext.md`](specs/markdown-content-lookup-plaintext.md),
[`specs/markdown-content-metadata.md`](specs/markdown-content-metadata.md),
[`specs/markdown-content-current-section.md`](specs/markdown-content-current-section.md),
and
[`specs/markdown-content-backend-exposure.md`](specs/markdown-content-backend-exposure.md),
and
[`specs/markdown-content-native-client-parity.md`](specs/markdown-content-native-client-parity.md),
and
[`specs/markdown-content-to-markdown.md`](specs/markdown-content-to-markdown.md),
and
[`specs/markdown-content-artifact-roundtrip.md`](specs/markdown-content-artifact-roundtrip.md),
and
[`specs/markdown-multi-link-imports.md`](specs/markdown-multi-link-imports.md),
and
[`specs/markdown-content-linked-namespaces.md`](specs/markdown-content-linked-namespaces.md),
and
[`specs/markdown-content-tables.md`](specs/markdown-content-tables.md),
and
[`specs/markdown-content-data-binding.md`](specs/markdown-content-data-binding.md),
and
[`specs/markdown-toolkit-links.md`](specs/markdown-toolkit-links.md).
For the next slices, write and commit the focused spec first, then implement.

## VmCompiler completeness (focus)

Make `VmCompiler.compile` succeed for as many real functions as possible so
`JitRuntime` can run them on SscVm instead of tree-walking. Baseline (2026-06-05):
310 functions disabled. Miss profile:

  201  call: no compilable target (closures/HOF â skip for now)
   54  unsupported: Term.Select
   26  unsupported: Lit.String
   17  undefined: name 'inner'
    4  undefined: name '_VNODES_PER_NODE'
    3  unsupported: Term.Function
    2  unsupported: Lit.Null
    2  unsupported: stmt Defn.Def

Run `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` to track progress.
Each slice: one VmCompiler change + tests + bench A/B, never ship a non-win.

- [x] **jit-completeness-p2-term-select** â Compile `obj.field` access
      (`Term.Select` outside match). Requires meta lookup for field type; emit
      `GETFI` (int) or `GETFR` (ref) using existing field-info infrastructure.
      Expected: 54 â 0 for pure field-access cases. Skip method calls
      (`.head`, `.size` etc.) â bail as before.

- [x] **jit-completeness-p3-inner-def** â Compile functions that contain
      local `def inner(...)` bodies (`undefined: name 'inner'`, 17 misses).
      Strategy: treat inner defs as closures over params â compile the outer
      function only if `inner` has no free variables beyond outer params.

- [x] **jit-completeness-p4-defn-def** â Handle `stmt Defn.Def` in
      `compileStmt` (2 misses). A local def inside a block; same as p3 but in
      stmt position.

- [x] **jit-completeness-p5-lit-null** â `Lit.Null` (2 misses): emit CONST 0,
      set type TRef. Simple.

- [x] **jit-completeness-p6-lit-string** â `Lit.String` intermediate + LOADS/EQREF/NEREF opcodes.
- [x] **jit-completeness-p7-string-meta** â `String.length/isEmpty/nonEmpty` via JitRuntime meta + GETFI StringV.

## JIT universal coverage (new focus)

Spec: [`specs/jit-universal-coverage.md`](specs/jit-universal-coverage.md).
Goal: make JIT work for **all** real programs, not just benchmarks.
All three engines (SscVm, Javac bytecode, ASM bytecode) reach a unified
compilable subset.  Stages worked sequentially.

**Miss profile after Stage 2.1 (2026-06-05, 718 total disabled):**
```
345  [javac] UnknownShape       â falls through classifier; mostly HOF calls + bool-sibling gap
300  [vm] Other                 â VmCompiler raw-string bails (not yet migrated to typed reasons)
 32  [javac] NonExtractPattern
 14  [javac] Compound
  9  [javac] BoolBody           â bool body too complex even for walkBool fallback
  7  [javac] PatternGuard
  6  [javac] TryCatch
  2  [javac] VarargParam
  2  [javac] UsingParams
  1  [asm] TryCatch
```
Root-cause analysis of the 345 UnknownShape:
- `jitCompatibleSibling` still excludes bool-returning fns â callers of bool fns bail
- `walkBool` only handles `ApplyInfix`; misses `Lit.Boolean`, `Term.Name` (bool local),
  `Term.If`, `Term.Apply` on a bool-returning sibling, `!` unary
- HOF calls (passing/receiving fn values) â Stage 3 territory
- `walkForBailCliffs` doesn't detect HOF patterns â they all land in UnknownShape

- [x] **jit-uc-stage1-partial** â Unified per-engine `JitMissStats` with `JitBailReason`
      typed vocab; `JitBailReason.scala` extracted; Javac + ASM record misses.
      (CLI `ssc check-jit-coverage` deferred to after HOF slice.)

- [x] **jit-uc-stage2-1** â Bool body wrap: both backends emit `return (boolExpr)?1L:0L`
      instead of bailing; `JitResult.resultIsBool` unwraps to `BoolV` at call site.

- [x] **jit-uc-stage2-1b** â Bool sibling gap: remove `!isBoolReturning` gate from
      `jitCompatibleSibling` so bool-returning fns can be co-emitted; extend
      `walkBool` in both backends to handle `Lit.Boolean`, `!`, `Term.Name` (bool
      local/param â `!= 0L`), `Term.If`, `Term.Apply` (bool-returning sibling call â
      `call() != 0L`).  Extend `walkForBailCliffs` to report `HofCall` when
      `Term.Apply` target is a param name (not a global fn), turning most UnknownShape
      into a named category.  Target: UnknownShape < 100.

- [x] **jit-uc-stage2-2** â Ref+Ref 2-param dispatch (`ObjObjToLong/Double/Object` interfaces).
- [x] **jit-uc-stage2-3** â ASM ref-match guard parity (port `walkArmAsIfBranch`).
- [x] **jit-uc-stage2-4** â `Pat.Lit` arm in match (literal patterns).
- [x] **jit-uc-stage2-5** â Free-name â top-level `FunV` call (non-HOF case).

### Bench findings (2026-06-05, from `asm-jit-parity` worktree, post-2.4 main build)

Verified empirically via `./bench.sh`. New regression-guard corpus cases added:
`bench/corpus/bool-predicate.ssc`, `bench/corpus/literal-match.ssc`,
`bench/corpus/mutual-recursion.ssc`.

- [x] **jit-uc-finding-asm-bool-parity** â Fixed by the combined effect of stage 2.3
      (ASM guarded ref-match parity) + void `Term.If` in `emitStatAsVoid` /
      `walkStatAsVoid` (this commit): `workload()` in `mutual-recursion.ssc` uses
      `if isEven(i) then sum = sum + 1L` inside a while loop; that void Term.If was
      not emitable on either backend, so `workload()` bailed. Now both Javac and ASM
      compile `workload()`. Re-bench: `./bench.sh mutual-recursion`.

- [x] **jit-uc-finding-litmatch-not-firing** â Root cause was `.toLong` in
      `sum = sum + classify(i).toLong` blocking `workload()` JIT compilation.
      Fix: `.toLong`/`.toInt` emit as identity (Int=Long in ScalaScript);
      `.toDouble` emits L2D. Both backends. `n % 5 match` with ApplyInfix
      scrutinee was already supported via `walkLong` for the scrutinee.

- [x] **jit-uc-stage3-1** â `Value.FunV` as JIT-visible ref operand in `JitGlobals`.
- [x] **jit-uc-stage3-2** â SscVm `CALLREF` opcode + monomorphic IC.
- [x] **jit-uc-stage3-3** â Lambda / closure compilation (capturing + non-capturing).
- [x] **jit-uc-stage3-4** â IC hit-rate validation (`SSC_JIT_IC_STATS=1`).
- [x] **jit-uc-stage3-5** â Bytecode JIT HOF emission (Javac + ASM `INVOKEINTERFACE` to `RefCallable`).

- [x] **jit-uc-stage4** â Arity 3â4 ceiling lift (code-generated dispatch interfaces).

- [x] **jit-uc-stage5-1** â Mixed Long+Double arms auto-promotion (already handled: `bodyHasDoubleLit` classifies any fn with a Double literal as Double; `walkDouble` auto-widens Int/Long literals; no corpus `MixedReturnType` misses).
- [x] **jit-uc-stage5-2** â `var` in pure bodies: add `Term.Assign` to `walkBlockStmts` (Javac) and delegate non-final stats to `emitStatAsVoid` in `emitBlockStmts` (ASM).
- [x] **jit-uc-stage5-3** â `try/catch` in bodies (JVM try block + tree-walker fallback).
- [x] **jit-uc-stage5-4** â `Pat.Alternative` / `@`-binding pattern support.
- [x] **jit-uc-stage5-5** â Non-`Term.Name` match scrutinee (auto-hoist to local).

### Stage 6 â Post-merge long tail (new queue)

Baseline (post-stage-5, 2026-06-05, 734 total disabled):
```
 300  [vm] Other            â VmCompiler: HOF/ref-return/complex (unmigrated vocab)
 294  [javac] UnknownShape  â remaining HOF + complex closure shapes
  48  [javac] LambdaValue   â non-trivial Term.Function captures
  37  [javac] Compound      â multiple simultaneous bail reasons
  27  [javac] NonExtractPattern â tuple / typed patterns in match arms
   8  [javac] PatternGuard  â `if` guards in match arms
   7  [javac] NonAdtScrutinee â complex scrutinee remaining after 5.5
   7  [javac] BoolBody      â bool bodies too complex for walkBool
```
Each item: one commit + bench A/B. Run `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` to track.

- [x] **jit-uc-stage6-bench-baseline** â Bench 2026-06-05 post-merge: bool-predicate
      4.37â0.004ms, literal-match 3.51â0.004ms, mutual-recursion ssc/asm at parity (~1.2ms).
      Remaining HOF gaps: either-chain 3.46ms, hof-pipeline 2.79ms, option-chain 2.98ms,
      range-sum 3.57ms, typeclass-fold 2.99ms (all ~0.001â0.020ms on jvm).

- [x] **jit-uc-stage6-asm-mutual-recursion** â Fix ASM JIT regression on
      `mutual-recursion`: `ssc-asm` 20.8 ms â 1.22 ms (parity with Javac 1.20 ms).
      Root cause: `Lit.Boolean` missing from `walkLong` caused bool-returning functions
      to fall into `walkBool` fallback which generated COMPUTE_FRAMES-incompatible dead
      labels. Also fixed dead `GOTO Lend` in `walkBool(Term.If)` when thenp always jumps.

- [x] **jit-uc-stage6-pattern-guard** â Guards in match sub-expressions (val RHS,
      if-branch etc.) now compile. `walkMatchExpr` in both backends adds `hasAnyGuard`
      if-chain path via `walkArmAsIfBranch`/`emitArmBodyGuarded`. Remaining 8 PatternGuard
      misses have complex guard conditions (`walkBool` can't compile them) â see
      `jit-uc-stage6-bool-body-ext`.

- [x] **jit-uc-stage6-bool-body-ext** â Added `walkLong` fallback to `walkBool`
      in both backends. Enables bool-returning match expressions and complex guards
      where `walkBool` fails but `walkLong` succeeds (Long != 0 = true).
      New test: `isZero(n): Boolean = n match { 0 => false; _ => true }` compiles.

- [x] **jit-uc-stage6-nonextract-tuple** â `Pat.Tuple` in Javac + ASM backends;
      JitLint accepts Var/Wildcard sub-patterns; 27 NonExtractPattern misses eliminated.

- [x] **jit-uc-stage6-vm-retref** â RETREF=49 opcode; SscVm TLS slot; VmCompiler
      unifyRet(TRef) allowed; JitRuntime wrapRef(); 18 vm-retref misses eliminated.

- [x] **jit-uc-stage6-unknownshape-hof-analysis** â HofMethodCall + RefChainCall bail
      reasons added; UnknownShape 295â240; stage-7 plan in specs/jit-universal-coverage.md Â§9.

## JIT universal coverage â Stage 7

Spec: [`specs/jit-universal-coverage.md Â§9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06): 734 disabled, 240 UnknownShape, 55 RefChainCall, 70 Compound.
Current after bucket split (2026-06-06): 731 disabled, 238 UnknownShape,
70 Compound, 33 QualifiedRefCall, 22 RefChainObjectCall, 0 RefChainCall.
Current after HOF method slice (2026-06-06): 731 disabled, 238 UnknownShape,
70 Compound, 33 QualifiedRefCall, 22 RefChainObjectCall; warmed HOF benches
are now `option-chain=0.002ms`, `either-chain=0.002ms`,
`hof-pipelineâ0.001ms`, `range-sumâ0.001ms`
(`BENCH_WI=1 BENCH_MI=3 BENCH_F=1 scripts/bench interp <name>`).
Current after typeclass classification (2026-06-06): 733 disabled,
238 UnknownShape, 70 Compound, `TypeclassUsingDispatch` split out as
`javac=4` / `asm=1`; `typeclass-fold=0.010 +/- 0.008ms/op`
(`BENCH_WI=1 BENCH_MI=3 BENCH_F=1 scripts/bench interp typeclass-fold`).
Current after object ref-chain dispatch (2026-06-06): 733 disabled,
238 UnknownShape, 70 Compound, 33 QualifiedRefCall, `RefChainObjectCall=14`,
`NumericObjectMethodCall=8`; object `mkString` / `Map.getOrElse` fixtures
now JIT on Javac+ASM as `LongToObject`.
Current after UnknownShape tagging (2026-06-06): 733 disabled,
20 UnknownShape, 178 Compound, `DirectGlobalOrCtorCall=148`,
`ApplyInfixRefOp=19`, `InterpolatedString=14`; classifier-only P3 target met.
Current after numeric-object dispatch (2026-06-06): 717 disabled,
20 UnknownShape, 170 Compound, no `NumericObjectMethodCall` misses in the
runtime profile; BigInt/Decimal constructor-result methods now compile on
Javac+ASM as `LongToObject`.
Each item: one commit + bench A/B (or test A/B), never ship a non-win.

- [x] **jit-uc-stage7-refchain** â Ref-val propagation low-risk subset landed:
      Javac + ASM co-emit ref-returning sibling calls, bind immutable ref locals
      as `Object`, and inline numeric reads through `JitRefDispatch`
      (`getOrElseLong`, `sizeLong`, `headLong`). Regression:
      `val r = parse(n); r.getOrElse(7)` JITs on both backends. Verified by
      `JitLintTest -z stage7-refchain`, `SscVmTest -z stage7-refchain`, and
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1416 tests green).
      Result: total disabled 734â731; aggregate `RefChainCall` stayed 55.
      Detail trace showed the remaining bucket is broader than this subset
      (`Parser.string`, `Free.Pure`, `BigInt.pow`, `map(...).mkString`, effect
      calls, object-returning `Map.getOrElse`). See spec Â§9 Stage 7.1.

- [x] **jit-uc-stage7-refchain-bucket-split** â Split the remaining broad
      `RefChainCall` bucket before adding object/String/generic ref-returning
      dispatch interfaces. Added `QualifiedRefCall` for module/companion/native
      simple receivers and `RefChainObjectCall` for computed object/String/generic
      chains; `JitPredicates` now tracks immutable local `val` names so
      numeric local/direct ref reads stay in `RefChainCall`. Verified by
      `JitLintTest -z stage7-refchain-bucket-split`, full `JitLintTest`, and
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1419 tests green).
      Result: total disabled stayed 731, `RefChainCall` 55â0,
      `QualifiedRefCall=33`, `RefChainObjectCall=22`. See spec Â§9 Stage 7.2.

- [x] **jit-uc-stage7-hof-method** â Monomorphic IC for HOF method dispatch:
      `.map(x => â¦)`, `.flatMap(x => â¦)`, `.filter(x => â¦)`, `.foldLeft(z)((a,b) => â¦)`.
      Landed a narrow numeric receiver subset for Option/Either/List/Range:
      compact lambda descriptors (`JitHofShape`), shared dispatch helpers
      (`JitHofDispatch`), top-level ref globals via `JitGlobals.readGlobalRef`,
      and builtin `Right`/`Left` object co-emit. Verified by
      `JitLintTest -z stage7-hof-method`, `SscVmTest -z stage7-hof-method`,
      four warmed JMH commands (`scripts/bench interp option-chain`,
      `either-chain`, `hof-pipeline`, `range-sum` with
      `BENCH_WI=1 BENCH_MI=3 BENCH_F=1`), and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1428 tests green).
      Result: focused benches are all <0.1ms/op; corpus miss profile unchanged
      at 731 disabled / 238 UnknownShape / 70 Compound. `typeclass-fold`
      remains a separate generic/given-dispatch follow-up. See spec Â§9
      Stage 7.3.

- [x] **jit-uc-stage7-typeclass-fold** â Classified the remaining
      `typeclass-fold` HOF workload as active context-bound typeclass dispatch
      instead of standard receiver method dispatch. Added
      `TypeclassUsingDispatch` for `summon[...]` and method selection on
      `using` params, plus a warmed `typeclass-fold` JMH target. Verified by
      `JitLintTest -z stage7-typeclass-fold`, `interpreterBench/compile`,
      quick JMH (`0.010 +/- 0.008ms/op`), and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1429 tests green).
      Result: generic/given dispatch is now a named follow-up; do not fold it
      into the monomorphic Option/Either/List/Range path. See spec Â§9
      Stage 7.4.

- [x] **jit-uc-stage7-refchain-object-dispatch** â Implemented the low-risk
      object/String-returning ref-chain dispatch slice and narrowed the rest.
      Javac + ASM now compile `(0 until n).map(...).mkString(...)` and
      object-returning `Map.getOrElse` as `LongToObject`, using
      `JitRefDispatch.getOrElseRef` / `mapGetOrElseRef` / `mkStringRef`.
      Added a guard so numeric `Option(...).getOrElse(0)` stays on the
      existing `LongFn1` path. Added `NumericObjectMethodCall` for
      `BigInt`/`Decimal` constructor-result method calls. Verified by focused
      `JitLintTest` / `SscVmTest` filters and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1434 tests green).
      Result: `RefChainObjectCall` narrowed `22 -> 14`, with
      `NumericObjectMethodCall=8`. See spec Â§9 Stage 7.5.

- [x] **jit-uc-stage7-unknownshape-tagging** â Added classifier-only
      `walkForBailCliffs` buckets for ref-like infix ops, string interpolation,
      type applications, for-comprehensions, `new` object construction,
      expression-callee HOF apply shapes, and direct non-param global/constructor
      calls. Verified by focused `JitLintTest` filters and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1441 tests green).
      Result: `UnknownShape` narrowed `238 -> 20`, meeting the `<100` target.
      See spec Â§9 Stage 7.6.

- [x] **jit-uc-stage7-numeric-object-dispatch** â Implemented the dedicated
      BigInt/Decimal numeric-object helper path. Javac + ASM now compile
      `BigInt(...)` / `Decimal(...)` constructor-result object methods
      (`abs`, `negate`, `pow`, `gcd`, `toDecimal`, `setScale`, `toBigInt`) as
      `LongToObject` through `JitRefDispatch`, with receiver guards preserving
      the generic `mkString` / `Map.getOrElse` ref-chain object fallback.
      Verified by focused numeric/object-dispatch tests and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1443 tests green).
      Result: total disabled `733 -> 717`, `Compound 178 -> 170`, no
      `NumericObjectMethodCall` misses in the runtime profile. See spec Â§9
      Stage 7.7.

## JIT universal coverage â Stage 8

Status (2026-06-07): mostly done. 1474 tests green. Bench wins:

| Bench | Before | ssc Javac | ssc-asm | JVM |
|---|---|---|---|---|
| map-ops | 3.16ms | 0.027ms (117Ã) | 0.026ms (113Ã) | 0.021ms â |
| string-split | 14.5ms | 0.235ms (62Ã) | 0.170ms (84Ã) | 0.089ms |
| typeclass-fold | 2.97ms | 2.38ms (1.25Ã) | 2.18ms (1.36Ã) | 0.005ms |

Spec: [`specs/jit-universal-coverage.md Â§9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06, post-stage7): 717 disabled, 20 UnknownShape,
170 Compound (`DirectGlobalOrCtorCall=148`, `ApplyInfixRefOp=19`,
`InterpolatedString=14`). Bench wins from stage 7 verified â either-chain,
hof-pipeline, option-chain, range-sum all <0.03 ms/op. Remaining buckets
do not show on bench corpus but block real-program JIT coverage.
Each item: one commit + bench A/B (or test A/B), never ship a non-win.

- [x] **jit-uc-stage8-direct-global-ctor** â Codegen done via
      `callGlobalLongAny` / `callGlobalRefAny` in JitGlobals: 1-arg ref,
      2/3-arg mixed ref+long (including callees with `using` clauses) now
      dispatch through `interp.invoke` (Javac+ASM). Classifier still reports
      144 DirectGlobalOrCtorCall â most are now false positives; refining
      `isKnownDirectJitCallee` requires runtime introspection (separate slice).

- [x] **jit-uc-stage8-apply-infix-ref** â String + Long/ref concat (Javac+ASM);
      BigInt/Decimal infix arithmetic (+/-/*/Div/Mod) via JitRefDispatch helpers
      (Javac+ASM); BigInt/Decimal comparison ops (<,<=,>,>=) (Javac+ASM);
      List/Map `++` collection concat via collectionConcat (Javac+ASM);
      ref ==/!= via Objects.equals (Javac+ASM).

- [x] **jit-uc-stage8-string-interp** â Javac+ASM: `s"..."` (Term.Interpolate
      prefix "s") lowers to `new Value.StringV(part + arg + ...)`; each arg
      via walkLong (numeric) or walkRef + Value.show. f-, md-, html-, css-
      prefixes still go through tree-walker.

- [x] **jit-uc-stage8-unknownshape-tail** â Added 5 new bail reasons
      (ThrowExpression, TupleConstruction, EtaExpansion, ExplicitReturn,
      NewAnonymousClass) + classifier wiring; corpus 20 UnknownShape unchanged
      (those shapes don't appear in tests); next agent debugging real code sees
      the right bucket. 3 focused tests; 1452 tests green.

### Stage-8 bench regressions (carryover from stage-6)

Three bench workloads remained slow through stages 6â7 because each needs a
distinct codegen path, not a classifier extension. Baseline (2026-06-06,
`./bench.sh`): `typeclass-fold` ssc 2.97 / ssc-asm 3.01 / jvm 0.004 ms/op;
`map-ops` ssc 3.16 / ssc-asm 3.91 / jvm 0.020 ms/op; `string-split` ssc 14.5 /
jvm 0.088 ms/op. Each item: one commit + bench A/B.

- [~] **jit-uc-stage8-typeclass-fold** â Partial (1.36Ã win, 2.97ms â 2.18ms).
      Codegen via `callGlobalLong1Ref` + `looksLongValue` fix: `workload()`
      JIT-compiles, the while-loop overhead removed. `combineAll` itself still
      tree-walked (uses `summon[T]`). Full win needs compile-time `summon[T]`
      specialization (monomorphic IC for given dispatch) â separate stage-9
      slice.

- [x] **jit-uc-stage8-map-ops** â Full bench-paritet with JVM on both backends
      (3.16ms â 0.027ms ssc Javac, 0.026ms ssc-asm vs JVM 0.021ms). Required
      changes: `Map[K,V](...)` ApplyType in walkRef + isRefValRhs; ref-typed
      Defn.Var/Term.Assign in walkBlockStmts + walkStatAsVoid + emitStatAsVoid;
      JitRefDispatch.mapUpdatedRef + mapGetOrElseLong.

- [x] **jit-uc-stage8-string-split** â Full bench-paritet with JVM on both
      backends (14.5ms â 0.235ms ssc Javac, 0.170ms ssc-asm vs JVM 0.089ms).
      Required: `String.split` via stringSplitRef; no-paren `.trim`/`.toUpperCase`
      Term.Select; `.toInt`/`.toLong` on ref fallback to emitRefChainLong;
      OpStringTrimToInt specialized op for `s => s.trim.toInt`-shape lambdas
      in JitHofDispatch + JitHofShape.

### Stage-8 residual bail buckets (gap analysis 2026-06-06)

After comparing the post-stage-7.7 miss profile against SPRINT, these
categories have no implementation task yet. Each item: one commit + miss-profile
A/B (or test A/B); never ship a non-win.

- [x] **jit-uc-stage8-vm-bail-migration** â Migrated 46 `bail(...)` sites in
      `VmCompiler` to typed `JitBailReason`; added 6 VM-specific cases
      (VmCallShape/VmFieldShape/VmUnsupportedTerm/VmEmptyBlock/VmNonBoolCond/
      VmUndefinedName) + reused 9 generic ones. Result: `[vm] Other` 290 â 32,
      new readable buckets dominated by `[vm] FreeNameUnresolvable=225`
      (HOF/closure call targets). 1443 tests green.

- [~] **jit-uc-stage8-qualified-ref-call** â Partial: `Math.max/min/abs`
      (Long) and `Math.sqrt/pow/floor/ceil/log/log10/exp/abs/sin/cos/tan/atan2`
      (Double) inline to `INVOKESTATIC java/lang/Math` in both backends.
      `.max(b)`/`.min(b)`/`.abs` on Long receivers also covered. Remaining:
      generic module/companion resolution for non-Math qualified calls
      (separate slice).

- [x] **jit-uc-stage8-nonextract-pattern-residual** â Classifier split:
      added TypedPattern + NestedTuplePattern + AlternativeWithBindings cases.
      Corpus 19 NonExtractPattern stayed (sub-Pat.Extract inside tuples â separate
      codegen slice). 3 focused classifier tests; 1447 tests green.

- [x] **jit-uc-stage8-pattern-guard-complex** â Long-fallback for match-guards:
      Javac `guardBoolExpr` + ASM `emitGuardBool` try `walkBool` first then
      `walkLong != 0L`. Targeted test exercises `Circle(r) if (r % 2)` style
      guards. Corpus profile unchanged (6 residual PatternGuard are Compound
      with other reasons), but new shapes now JIT. 1444 tests green.

## JIT universal coverage â Stage 9 (post-monomorphic follow-ups)

Stage 9 reopened two items previously parked as spec non-goals "for this sprint."
All current slices landed; remaining open items move to BACKLOG/CHANGELOG when
specific follow-ups are scoped.

---

## Interpreter perf â Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md Â§"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** â Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).

---

## Rust backend (new target)

Spec: [`specs/rust-backend.md`](specs/rust-backend.md). New AOT target â
emits a Cargo crate (`Cargo.toml` + `src/runtime/` + `src/generated/`) that
`cargo build` compiles to a self-contained binary. Phases R.1âR.6
(skeleton â core IR â intrinsics MVP â effects â http parity â polish).
Each task: one commit, baseline + acceptance recorded, never ship a
half-implemented phase under a flag. New backend module under
`runtime/backend/rust/`, plugin loaded via `META-INF/services` like every
other backend; no privileged hook in core.

**Coordination.** R.1 is the foundation â every later task `dependsOn` it.
Until R.1 lands, do not claim R.2+ from the queue. R.6 sub-tasks are
independent of each other once R.5 is in.

### Phase R.1 â Skeleton

R.1.3 hello-emit is split into four sequential sub-slices below.
Each one is a single commit with its own golden fixture so the next
slice has a verified base to extend. The cumulative result equals the
original `rust-backend-r1-hello-emit` description (Cargo.toml + main.rs
+ runtime/mod.rs + value.rs + generated/<module>.rs).



### Phase R.2 â Core IR coverage

Depends on R.1 complete. Each item: one commit, golden snapshots updated,
A/B vs the interpreter row.



### Phase R.3 â Intrinsics MVP

Depends on R.2. Capability additions: `FileSystem`, `Crypto`, `Markup`
(string-string xml only). Per-module Cargo dependency walk now becomes
load-bearing: the emitted `Cargo.toml` lists exactly the crates the
program reaches.

---

## Rust backend â benchmark coverage (spec: specs/rust-backend-bench-coverage.md)

16 of 22 bench corpus workloads return `n/a`.  Tasks below are ordered
by quick-win impact; P0 alone unlocks 7 benchmarks with tiny changes.

### P0 â quick wins (XS, each â¤ 100 lines)

- [x] **rust-bench-p0-to-numeric** â Add `.toLong` / `.toInt` /
      `.toDouble` / `.toFloat` conversions.  In `renderTerm`,
      recognise `Term.Select(expr, Term.Name("toLong"|"toInt"|...))` and
      lower to `(expr) as i64` / `as i32` / `as f64`.  Also lower
      `Term.ApplyInfix(lhs, "+", rhs)` where one operand is a `String`
      and the other is numeric to `format!("{}{}", lhs, rhs)` (fixes
      `string-concat`).  Acceptance: `string-concat.ssc`,
      `literal-match.ssc` green on `scripts/bench wall rust`.
      Spec: `specs/rust-backend-bench-coverage.md` Â§Gap A + C.

- [x] **rust-bench-p0-hello-bench** â Fix `hello-world` bench harness.
      In `bench/run.sc`'s injected `main.rs`, when `workload()` returns
      `Unit`, emit `generated::ssc_program::workload(); let r = 0i64;`
      instead of `let r = generated::ssc_program::workload();` so
      `std::hint::black_box(r)` receives an `i64`.  Acceptance:
      `hello-world.ssc` green on `scripts/bench wall rust`.
      Spec: Â§Gap B.

### P1 â collection method chaining (SâM)

- [x] **rust-bench-p1-vec-methods** â Add `.map(f)`, `.filter(f)`,
      `.foldLeft(z)(f)`, `.foreach(f)`, `.collect()` (as
      `.collect::<Vec<_>>()`) on Vec types in `renderTerm`.  Pattern:
      `Term.Select(qual, Term.Name("map"|"filter"|...))` + following
      `Term.Apply` with the lambda arg.  Acceptance: `list-fold.ssc`,
      `hof-pipeline.ssc` green.  Spec: Â§Gap D + G.

- [x] **rust-bench-p1-string-methods** â Add `String.split(sep)` â
      `s.split(sep).map(|p| p.to_string()).collect::<Vec<String>>()`,
      `.trim()` â `.trim().to_string()`, `.toInt` on String â 
      `.parse::<i32>().unwrap_or(0)`.  Acceptance: `string-split.ssc`
      green.  Spec: Â§Gap D.

### P2 â types + patterns (M)

- [x] **rust-bench-p2-sealed-trait-adt** â Recognise `sealed trait T`
      + `case class C extends T` pattern: collect both forms in a single
      ADT scan and lower to a Rust `pub enum T { C { â¦ }, â¦ }` just as
      the existing Scala 3 `enum` lowering does.  Acceptance:
      `pattern-match-heavy.ssc` green (requires `foreach` from P1 too).
      Spec: Â§Gap F.

- [x] **rust-bench-p2-tuple-types** â Map `Type.Tuple(elems)` to Rust
      tuple `(T1, T2, â¦)` in `mapType`; add `Lit.Tuple(elems)` / 
      `Term.Tuple(elems)` emit in `renderTerm`.  Lower the `++`
      concat operator on two tuple literals to a flat tuple.  Acceptance:
      `tuple-monoid.ssc` green.  Spec: Â§Gap H.

- [x] **rust-bench-p2-option-type** â Map `Option[T]` to `Option<T>`;
      add `.flatMap`, `.map`, `.getOrElse` methods on Option; `Some(x)`
      constructor â `Some(x)`, `None` â `None`.  Acceptance:
      `option-chain.ssc` green.  Spec: Â§Gap E.

### P3 â additional types (MâL)

- [x] **rust-bench-p3-hashmap-type** â Map `Map[K, V]` to
      `std::collections::HashMap<K, V>` (dep-free); add `.updated(k, v)`
      â `{ let mut m2 = m.clone(); m2.insert(k, v); m2 }`, `.getOrElse`
      â `.get(&k).copied().unwrap_or(default)`.  Acceptance:
      `map-ops.ssc` green.  Spec: Â§Gap E.

- [x] **rust-bench-p3-either-type** â Map `Either[L, R]` to a generated
      `pub enum Either<L, R> { Left(L), Right(R) }` emitted once per
      crate when reached; add `.map`, `.flatMap`, `.fold` methods.
      Acceptance: `either-chain.ssc` green.  Spec: Â§Gap E.

- [x] **rust-bench-p3-range-until** â Lower `(lo until hi)` and
      `(lo to hi)` to a `(lo..hi)` / `(lo..=hi)` Rust range; chain
      `.map` / `.foldLeft` via iterator adapters.  Acceptance:
      `range-sum.ssc` green.  Spec: Â§Gap E + D.

### Phase R.4 â Effects (algebraic effects + handlers)

Depends on R.2 closures and R.3 (for the runtime preamble layout). Free
monad in `Value::Computation(Box<Computation<Value>>)`. Capability adds
`AlgebraicEffects`. Multi-shot continuations panic with a clearly-labelled
runtime error; tracked as R.6 follow-up.


- [x] **rust-backend-r4-perform-handle-resume-lowering** â Implemented
      via tagless-final traits (not free-monad): Logger (effect-pure bench),
      Stream/VecStream (effect-stream bench), State/StateHandler (R.4.4),
      Random/RandomHandler LCG (R.4.4). R.4.1 supplies the free-monad
      runtime template for future CPS effects. 131 tests pass.

### Phase R.5 â Runtime parity (std.http server)

Depends on R.4 (handler bodies are effectful). Capability adds
`HttpServer`. Per-module walk pulls `tokio` + `hyper` + `http-body-util` +
`bytes` only when an `std.http.*` intrinsic is reached; programs without
HTTP stay dep-free.


- [x] **rust-backend-r5-http-serve-route** â `serve(port)` + `route(method, path, handler)`
      via `hyper::server::conn::http1::Builder` + `service_fn`; tokio+hyper deps
      pulled only when HTTP is reached; `src/runtime/http.rs` emitted conditionally.
      Landed 2026-06-08 (commit `0b3d179f0`). 7 tests in RustGenR5Test.

### Phase R.6 â Parity polish (independent tasks)

Each item is independent and stays parked until a real conformance test
or example demands it. Order below is priority for triage when claiming.

- [x] **rust-backend-r6-monomorphisation-pass** â Already implemented by design.
      The Rust backend emits `i64`, `bool`, `f64` directly for all numeric/boolean
      operations â no `Value` boxing in generated code. The `Value` enum in `value.rs`
      exists only for the `_show` helper. Every generated `pub fn` uses primitives
      throughout: no boxing overhead on hot paths. Closed 2026-06-09.

- [x] **rust-backend-r6-typeclasses** â `Feature.TypeClasses`: `given X: T with { defs }`
      emits a Rust unit struct XGiven + inherent impl; instance injected as topVal
      `let x = XGiven;`. `obj.method(args)` dispatch added to applyNonListCtor.
      Acceptance: bench/corpus/typeclass-monoid.ssc. 7 tests (190 total). Landed 2026-06-09.

- [x] **rust-backend-r6-streams** â `Feature.Streams` via synchronous iterator chains.
      Source.range(lo,hi)â(lo..=hi), Source.fromList(list)âlist, .toListâ.collect::<Vec<_>>().
      .map/.filter/.foldLeft already worked on ranges. No tokio/futures needed for these patterns.
      Acceptance: bench/corpus/streams-pipeline.ssc. 7 tests (183 total). Landed 2026-06-09.

- [x] **rust-backend-r6-multi-shot-continuations** â Resolved by the tagless-final
      approach (R.4.2âR.4.4): `VecStream` is inherently multi-shot (collects every
      `stream_emit` call); no Computation Clone needed. The original restriction was
      free-monadâspecific and does not apply to tagless-final. Closed 2026-06-09.

- [x] **rust-backend-r6-tco** â `Feature.TailCallOptimization` via while-loop
      rewrite: `hasTailCallPath` detects self-calls in if/else + block tails;
      params get `mut`; tail calls â temp bindings + param reassignments; branches
      get `return`. Binary-recursive fns (e.g. fib) are NOT rewritten (safe).
      7 tests (147 total). Landed 2026-06-09.

- [x] **rust-backend-r6-websockets** â `Feature.WebSockets`: `wsRoute(path, handler:String->String)`,
      `wsServe(port)`, `wsConnectSync(url, handler:String->Unit)` via tokio-tungstenite 0.21.
      Conditional dep injection (tokio dedup when HTTP also present).
      src/runtime/ws.rs emitted on demand. 8 tests (155 total). Landed 2026-06-09.

- [x] **rust-backend-r6-auth** â `Feature.Auth`, intrinsics
      `hashPassword` (argon2id + random salt), `verifyPassword` (bool),
      `jwtSign` (HS256, payload as `sub` claim), `jwtVerify` (returns payload).
      argon2 0.5 + jsonwebtoken 9 + serde deps pulled only when any auth
      intrinsic is reached; hello-world stays dep-free. 9 tests (140 total).
      Landed 2026-06-09.

- [x] **rust-backend-r6-mcp** â `Feature.McpServer` via hand-rolled JSON-RPC 2.0
      over stdio (rmcp not stable enough). `mcpRegisterTool` + `mcpServe`;
      handles initialize/tools_list/tools_call. Only serde_json dep (no duplication
      when JSON intrinsics also present). 7 tests (162 total). Landed 2026-06-09.

- [x] **rust-backend-r6-markup-xslt** â Decision: XSLT excluded from Rust backend.
      No conformance test currently reaches it. `Feature.Xslt` is NOT in
      RustCapabilities.features â programs requiring XSLT are rejected at
      capability check time with a Diagnostic.Unsupported. Codec path (quick-xml
      XML read/write without XSLT) is out of scope for this sprint.
      Landed 2026-06-09 (this entry â no code change needed, capability rejection
      was already implicit).

---

## Backend-specific fenced blocks + platform-type ban (new)

**Motivation:** `.ssc` code must never reference `java.*`, `scala.*`, or any
other platform-specific type in a regular `scalascript` block â this should be
a compile error, not convention. The escape hatch for legitimate ad-hoc native
code is explicit backend-tagged fenced blocks: `scala`, `java`, `javascript`,
`rust`. The `java` fenced block tag is new (previously only `scala` existed for JVM).

Spec: [`specs/backend-specific-blocks.md`](specs/backend-specific-blocks.md)

### Phase 1 â parser

- [x] **backend-blocks-p1-parse** â Extend parser to recognise
      `scala`, `java`, `javascript`, `rust`, `wasm` fenced blocks as
      `BackendBlock(tag, source)` AST nodes. Existing `scalascript`
      blocks unchanged. Tests: mixed-block file parses correctly.
      Commit: `feat(parser): backend-specific fenced blocks`.
      â Landed 2026-06-09 (745c963a): Lang.Java/Rust/Wasm + isNativeBackendBlock
      + isOpaqueExec wiring; 17 new tests, 906 core tests pass.

### Phase 2 â type-checker enforcement

- [x] **backend-blocks-p2-typecheck** â Banned-prefix check in
      type-checker: `java.*`, `javax.*`, `scala.*`, `sun.*`, `com.sun.*`
      in `scalascript` blocks â `E_PlatformType` compile error.
      Capability gate: `extern def` with no backend impl â `E_NoBackendImpl`.
      Test: `tests/conformance/backend-blocks-platform-type-ban.ssc`.
      Commit: `feat(typer): platform-type ban + capability gate`.
      â Landed 2026-06-09 (33ca975): java/javax/sun/com.sun import ban in
      scalascript blocks; scala blocks exempt; 10 new tests, 916 total.

### Phase 3 â JVM backend emission

- [x] **backend-blocks-p3-jvm** â `JvmGen`: emit `scala` blocks verbatim
      after main module object; emit `java` blocks as separate `.java`
      source files via `//> using sources`. Test: `currentPid()` via
      `scala` block; `ssc run --target jvm` returns PID > 0.
      Commit: `feat(jvmgen): scala/java backend block emission`.
      â Landed 2026-06-09 (5f8b969): scala blocks via isParseable, java blocks
      via javaBlocks buffer + //> using sources; 7 tests, 1490 backendInterpreter pass.

### Phase 4 â JS backend emission

- [x] **backend-blocks-p4-js** â `JsGen`: emit `javascript` blocks
      verbatim into the JS bundle after preamble. Test: `currentPid()`
      via `javascript` block; Node.js target returns `process.pid`.
      Commit: `feat(jsgen): javascript backend block emission`.
      â Landed 2026-06-09 (462cb30): javascript verbatim in walkSection +
      genSection; html/css keep template path; 6 tests, 1504 total.

### Phase 5 â Rust backend emission

- [x] **backend-blocks-p5-rust** â `RustGen`: `rust` fence blocks emitted
      verbatim into `src/generated/<module>.rs` with numbered headers.
      5 tests in RustGenRustBlocksTest. Landed `26404e906`.

### Phase 6 â extend FFI annotations to `@rust` / `@wasm` + WASM boundary

- [x] **backend-blocks-p6-ffi-extend** â Add `RustInline`, `WasmInline`,
      `WasmExport`, `WasmImport` annotation AST nodes (alongside existing
      `JvmInline`, `JsInline`). Wire `@rust("...")` into `RustGen`.
      Wire `@wasmExport` / `@wasmImport` into WASM backend boundary emission
      (export/import table entries). Update `arch-ffi.md` to reference
      `backend-specific-blocks.md` for the full picture.
      Commit: `feat(ffi): @rust/@wasm annotations + WASM boundary annotations`.
      â Landed 2026-06-09 (339cdff): @rust("expr") wired in RustCodeWalk.renderDef;
      extern defs without @rust skipped; arch-ffi.md updated; 4 tests, 151 Rust total.
      Note: @wasmExport/@wasmImport deferred (no WASM backend to wire into).

### Phase 7 â audit + flip ban to error

- [x] **backend-blocks-p7-audit** â Enable ban as warning, surface all
      violations in `runtime/std/`, `examples/`, `tests/conformance/`.
      Migrate violating `.ssc` files to `std.*` or backend blocks.
      Flip warning to error. Update `AGENTS.md` link (already added).
      Commit: `fix(typer): enable platform-type ban as hard error`.
      â Landed 2026-06-09: audit found 1 violation (mcp-search-server.ssc had
      java.nio + scala.io in scalascript block); migrated to scala block.
      Ban already hard error from Phase 2. runtime/std/, conformance/ clean.

---

## std.fs / std.os / std.process â filesystem, OS & process abstraction (new)

**Motivation:** `.sc` tool scripts (`bench/run.sc`, `tests/e2e/spa-smoke.sc`)
use `java.io`/`java.nio` directly. `.ssc` user code must never reach for JVM
APIs. `runtime/std/fs.ssc` exists but is only 4 stubs with no backend plugin.
Goal: full cross-backend `fs-plugin` + `os-plugin` so `.ssc` code has zero
reason to touch platform APIs.

Three `.ssc` stdlib modules:
- `std.fs` â file-system operations (read/write/list/copy/move/delete/temp)
- `std.os` â OS environment (env vars, CLI args, cwd, paths, exit, platform info)
- `std.process` â process management (spawn, exec, stdin/stdout/stderr, wait, kill)

Spec to write first: `specs/std-fs-os.md`

### Phase 1 â spec + design

- [x] **std-fs-os-p1-spec** â Write `specs/std-fs-os.md`. Cover:

      **`std.fs`**: readFile, writeFile, appendFile, deleteFile, exists,
      isDir, isFile, mkdir, mkdirs, listDir, copyFile, moveFile,
      readBytes, writeBytes; `FsError` sealed trait (NotFound,
      PermissionDenied, NotSupported, IoError); `Feature.FileSystem` gate.

      **`std.os`**: env(key), envOrElse(key, default), args: List[String],
      exit(code), cwd, sep, pathJoin(parts*), pathDirname, pathBasename,
      pathExtname, pathResolve, pathIsAbsolute, tempDir, tempFile,
      platform: Platform (Jvm | NodeJs | Browser | Native),
      homedir, hostname.

      **`std.process`**: exec(cmd, args, opts) â ProcessResult
      (stdout, stderr, exitCode); spawn(cmd, args, opts) â Process
      (write to stdin, read stdout/stderr as streams, wait, kill);
      ProcessOptions (cwd, env, timeout); ProcessError sealed trait.
      Note: Browser target throws ProcessError.NotSupported for all ops.

      JS-Node vs JVM vs Rust vs browser-sandbox policy for each module.
      Commit: `spec: std-fs-os`.
      â Landed 2026-06-09 (0757d27): 271-line spec, all 3 modules + 6 phases.

### Phase 2 â JVM backend (fs-plugin + os-plugin)

- [x] **std-fs-os-p2-jvm** â Create `runtime/std/fs-plugin/` with
      `FsPlugin.scala` + `FsIntrinsics.scala` (std.fs + std.os).
      Create `runtime/std/os-plugin/` with `OsPlugin.scala` +
      `OsIntrinsics.scala` (std.process via `ProcessBuilder`).
      JVM impl: `java.nio.file`, `System.getenv`, `ProcessBuilder`.
      Register both in `build.sbt`. Conformance tests:
      `tests/conformance/fs-*.ssc`, `tests/conformance/os-*.ssc`,
      `tests/conformance/process-*.ssc`. Commit: `feat(fs-plugin): JVM backend`.
      â Landed 2026-06-09 (30134b8): fs-plugin (13 ops, 13 tests) + os-plugin
      (18 ops incl. exec, 14 tests). allPlugins registered.

### Phase 3 â JS/Node backend

- [x] **std-fs-os-p3-js** â Node.js preamble wiring `std.fs` â `node:fs`,
      `std.os` â `node:os` + `node:path`, `std.process` â `node:child_process`.
      Browser: `FsError.NotSupported` / `ProcessError.NotSupported` for
      fs/process ops; env returns `{}`, args returns `[]`, platform = Browser.
      Same conformance tests pass on Node target.
      Commit: `feat(fs-plugin): JS/Node backend`.
      â Landed 2026-06-09 (d32bf9a): JsRuntimeFs.scala; 16 fs + 15 os + exec();
      lazy require; browser stubs; 21 tests.

### Phase 4 â Rust backend

- [x] **std-fs-os-p4-rust** â Full std.fs/std.os/std.process Rust lowering:
      12 fs helpers, 15 os helpers (envâOption, path*, platform=Native), ProcessResult+exec.
      All use pure std (no extra crates). 14 new tests (176 total). Landed 2026-06-09.

### Phase 5 â stdlib .ssc files + examples

- [x] **std-fs-os-p5-stdlib** â Add `runtime/std/os.ssc` and
      `runtime/std/process.ssc` alongside existing `fs.ssc`. Expand
      `fs.ssc` with new extern signatures. Add runnable examples:
      `examples/fs-roundtrip.ssc`, `examples/os-env.ssc`,
      `examples/process-exec.ssc`. Update `README.md` capabilities table.
      Commit: `feat(std): fs/os/process stdlib modules`.
      â Landed 2026-06-09 (ee673a5): fs.ssc expanded (16 defs), os.ssc new,
      process.ssc new; 2 examples; README updated.

### Phase 6 â audit & boundary documentation

- [x] **std-fs-os-p6-cleanup** â Audit all `.ssc` files for `java.*`
      imports; migrate any found to `std.fs`/`std.os`/`std.process`.
      Note in `specs/std-fs-os.md` Â§"Scope": `.sc` Scala-CLI host
      scripts (bench/run.sc etc.) may use JVM APIs â that is intentional.
      Add one-liner to `AGENTS.md` Â§"Codebase architecture rules":
      "`.ssc` user code must never import `java.*` â use `std.fs`,
      `std.os`, `std.process` instead."
      Commit: `docs(std-fs-os): boundary rule in AGENTS.md + spec`.
      â Landed 2026-06-09: audit done (covered by backend-blocks-p7);
      AGENTS.md already references specs/std-fs-os.md; specs/std-fs-os.md Â§6 scope note added.

---

## std.yaml â YAML parse / stringify (new)

**Motivation:** `.ssc` user code has no way to call `parseYaml(s)` or `toYaml(v)` today.
`SimpleYaml` already covers ~90% of real YAML (block/flow mappings+sequences, scalars,
quoted strings, comments, literal blocks) but only returns internal Java types â not
ScalaScript `Value`s.  A `yaml-plugin` + `std/yaml.ssc` closes this gap.

**Scope:**
- JVM: `SimpleYaml.load` â `Value` converter + plain-Scala YAML serializer (no snakeyaml needed).
- JS/Node: inline mini-parser + serializer in JsRuntimeYaml (subset matching SimpleYaml).
- Anchors/aliases, multi-document, YAML 1.2 tags: **out of scope** for this sprint.
- `yaml`/`yml` fenced blocks already produce `ContentValue` (content API) â Phase 4 wires
  them to ScalaScript-visible variables too.

Spec to write first: `specs/std-yaml.md`

### Phase 1 â spec

- [x] **yaml-p1-spec** â Write `specs/std-yaml.md`. Cover:

      **`std.yaml`**: `parseYaml(s: String): YamlValue`;
      `toYaml(v: YamlValue): String`;
      `YamlValue` sealed trait (`YStr`, `YNum`, `YBool`, `YNull`, `YArr`, `YObj`);
      helper `.str`, `.num`, `.bool`, `.arr`, `.obj` accessors returning `Option[...]`;
      `YamlValue.from(v: Any)` bridge for dynamic values.

      Supported YAML subset: block/flow mappings+sequences, single+double-quoted strings,
      null/bool/int/double scalars, comments, literal/folded block scalars.
      Out of scope: anchors, aliases, merge keys, multi-document, YAML 1.2 tags.

      Backend policy table (JVM / JS-Node / Browser / Rust).
      Commit: `spec: std-yaml`.
      â Landed 2026-06-09 (ebb2a6e): specs/std-yaml.md, 146 lines.

### Phase 2 â JVM plugin

- [x] **yaml-p2-jvm** â Create `runtime/std/yaml-plugin/` with
      `YamlInterpreterPlugin.scala` + `YamlIntrinsics.scala`.

      `parseYaml(s)`: `SimpleYaml.load[Any](s)` â recursive converter returning
      `Value.MapV` / `Value.ListV` / `Value.StringV` / `Value.IntV` / `Value.DoubleV` /
      `Value.BoolV` / `Value.NullV` (tag names matching `YamlValue` sealed trait).

      `toYaml(v)`: pure-Scala serializer â walks `Value` tree, emits block-style YAML
      (mappings indented 2, sequences with `- ` prefix, strings quoted when needed).

      Register in `build.sbt`. Tests: round-trip `parseYaml(toYaml(v)) == v` for
      Map, List, nested, scalars, edge cases (empty string, null, bool).
      Commit: `feat(yaml-plugin): JVM backend`.
      â Landed 2026-06-09 (67985dd): yaml-plugin, 28 tests, allPlugins registered.

### Phase 3 â JS/Node preamble

- [x] **yaml-p3-js** â Add `JsRuntimeYaml.scala` to `runtime/backend/js/`.

      `parseYaml(s)`: port `SimpleYaml` subset to JS (or inline a ~200-line
      pure-JS block/flow parser matching the JVM subset exactly).

      `toYaml(v)`: JS serializer â same block-style output as JVM.

      Wire into `JsGen.generateRuntime` unconditionally.
      Tests: text-shape assertions that `parseYaml` and `toYaml` appear in preamble;
      round-trip conformance test against Node.js runner.
      Commit: `feat(jsgen): std.yaml JS/Node preamble`.
      â Landed 2026-06-09 (2c169d7): JsRuntimeYaml.scala, 13 preamble tests.

### Phase 4 â stdlib `.ssc` + examples + fenced-block wiring

- [x] **yaml-p4-stdlib** â Add `runtime/std/yaml.ssc` with `YamlValue` sealed trait
      declarations and `parseYaml`/`toYaml` extern defs.

      Wire `yaml`/`yml` fenced blocks: bind block content as a `YamlValue` variable
      named `<sectionId>_yaml` (or `<sectionId>.yaml`) in the surrounding ScalaScript
      scope â same pattern as `html`/`css` string blocks bind `<sectionId>.html`.

      Add example: `examples/yaml-parse.ssc` â parse a YAML string, navigate it,
      round-trip through `toYaml`.  Update `README.md` capabilities table.
      Commit: `feat(std): std.yaml stdlib module + fenced-block wiring`.
      â Landed 2026-06-09 (7ac9857): yaml.ssc, fenced-block binding, 6 plugin tests, example.

---

## std.pdf â PDF â Markdown reader (new)

> â **DONE (verified 2026-06-11) â implemented under a different API than this
> queue spec'd.** `pdfToMarkdown` + `pdfPageCount` (read) ship in the JVM
> `pdf-plugin` (`PdfIntrinsics`, Apache PDFBox) and the `runtime/std/pdf-gen.ssc`
> stdlib module (`package: std.pdf`), alongside `htmlToPdfBase64` (generate). The
> API takes a base64 `String` (not `bytes: List[Int]`) and the stdlib file is
> `pdf-gen.ssc` (not `pdf.ssc`). `pdf-plugin` is registered in `build.sbt`
> (`pdfPlugin` / `PluginSpec("pdf", â¦)`) and staged into `bin/`. All of pdf-p1..p5
> below are therefore stale â the read+generate capability exists end-to-end (JVM/
> interp; JS/Rust per the agent that landed it). NOTE: the example
> `examples/pdf-extract-demo.ssc` is missing its ```` ```scalascript ```` fence so
> `ssc run` silently does nothing â tracked under the "bare-example" finding, not a
> blocker for the capability itself.

**Motivation:** `.ssc` user code has no way to read a PDF today. There is no
cross-backend PDF parser, so this must be a per-backend `pdf-plugin` (intrinsics
go to `runtime/std/`, never core â AGENTS.md). v1 reads a PDF **as Markdown** so
it plugs straight into the existing `std.content` / markup pipeline (matches the
project's "Markdown as first-class syntax" principle).

**Scope (v1):**
- Input is `List[Int]` bytes (same byte representation as `std.fs.readBytes`), so
  it composes: `pdfToMarkdown(fs.readBytes(path))`.
- `pdfToMarkdown(bytes): String` â extract text as Markdown. Heuristic structure:
  page breaks â `## Page N`, paragraphs separated by blank lines. Font-size-based
  heading detection is a **follow-up**, not v1.
- `pdfPageCount(bytes): Int`.
- Out of scope: rendering, images, forms, tables, encrypted PDFs, OCR of scanned
  (image-only) PDFs.
- Backend rollout: **JVM â JS â Rust**, phased, one push per green phase.

Spec to write first: `specs/std-pdf.md`

### Phase 1 â spec

- [x] **pdf-p1-spec** â Write `specs/std-pdf.md`. Cover:

      **`std.pdf`**: `pdfToMarkdown(bytes: List[Int]): String`;
      `pdfPageCount(bytes: List[Int]): Int`.
      Markdown mapping rules: `## Page N` per page, paragraphs split on blank
      lines, page-internal text joined with single newlines collapsed to spaces
      where PDFBox over-segments. Define behaviour on: empty PDF, 0 pages,
      encrypted PDF (return error/empty + documented), non-PDF bytes (error).
      Backend policy table (JVM PDFBox / JS pdf.js-or-Node / Browser / Rust pdf-extract).
      Note the heading-detection follow-up explicitly as out of scope.
      Commit: `spec: std-pdf`.

### Phase 2 â JVM plugin

- [x] **pdf-p2-jvm** â Create `runtime/std/pdf-plugin/` with
      `PdfInterpreterPlugin.scala` + `PdfIntrinsics.scala` (mirror `crypto-plugin`).

      Add **Apache PDFBox** (`org.apache.pdfbox:pdfbox`, Apache-2.0) as the plugin's
      only new dependency. `pdfToMarkdown`: load bytes via `PDDocument.load`, run
      `PDFTextStripper` page-by-page (`setStartPage`/`setEndPage`), prefix each with
      `## Page N`, normalise whitespace into Markdown paragraphs. `pdfPageCount`:
      `doc.getNumberOfPages`. Convert `List[Int]` arg â `Array[Byte]`.

      Register in `build.sbt`: `lazy val pdfPlugin`, `PluginSpec("pdf", â¦)`, root
      aggregate, CLI plugin list, `% Test` on `backendInterpreter`. SPI service file.
      Tests: small fixture PDF in `src/test/resources` â assert page count + that
      extracted Markdown contains expected words and `## Page 1`.
      Commit: `feat(pdf-plugin): JVM backend (PDFBox)`.

### Phase 3 â JS/Node preamble

- [x] **pdf-p3-js** â Add `JsRuntimePdf.scala` to `runtime/backend/js/` (mirror the
      crypto `_sha256` preamble pattern in `JsRuntimePart2b.scala`).

      Node path: lazy-`require('pdf-parse')` (or `pdfjs-dist`) to extract text +
      page count; emit the same `## Page N` Markdown shape as JVM. Browser path:
      `pdfjs-dist` via the documented async boundary (note: PDF.js is async â decide
      in the spec whether the browser variant is supported in v1 or deferred).
      Wire into `JsGen.generateRuntime`. Tests: preamble text-shape assertions +
      Node round-trip on the same fixture PDF as JVM.
      Commit: `feat(jsgen): std.pdf JS/Node preamble`.

### Phase 4 â stdlib `.ssc` + example

- [x] **pdf-p4-stdlib** â Add `runtime/std/pdf.ssc` (manifest `package: std.pdf`,
      exports `pdfToMarkdown`, `pdfPageCount`) with the two `extern def`s.

      Add example `examples/pdf-read.ssc`: `fs.readBytes` a sample PDF â
      `pdfToMarkdown` â print, and parse the result through `std.content` to show the
      Markdown round-trips. Update `README.md` capabilities table + `docs/user-guide.md`.
      Commit: `feat(std): std.pdf stdlib module + example`.

### Phase 5 â Rust backend (follow-up)

- [x] **pdf-p5-rust** â Rust codegen for `pdfToMarkdown` / `pdfPageCount` via the
      `pdf-extract` (or `lopdf`) crate. Defer until JVM+JS are green; gate behind the
      Rust intrinsics MVP. Commit: `feat(rust): std.pdf intrinsics`.

---

## std.ui â busi UI feedback (2026-06-09)

From busi's `docs/scalascript-ui-proposals.md` (e9cfa34), grounded in 17 real web
screens; refined with busi in rozum (`scalascript` room). busi frontends are web React
**and** macOS/iOS SwiftUI, so all surface stays backend-agnostic (`TkNode` level), never
web-CSS-coupled. Start order: **P1 + P4 parallel â P2 â P3 (deferred, in BACKLOG)**.

### P1 â typed JSON in `fetch*` (spec: `specs/std-ui-typed-json.md`)

Highest ROI; removes 13 duplicated `*Q` escapers + substring decoders across screens.
Pure stdlib, helps web + native. Builds on existing `json-plugin`
(`jsonParse`/`jsonStringify`/`lookup`) â no second parser.

- [x] **ui-typed-json-p1-spec** â `specs/std-ui-typed-json.md`. â Landed 2026-06-09.

- [x] **ui-typed-json-p2-core** â â Landed 2026-06-09 (JVM + JS). Navigable `JsonValue` +
      total accessors (get/at/asString/asInt/asDouble/asBool/asList/isNull/opt*/getOrElse +
      **asDecimal/optDecimal** lossless money) + structured builders (jStr/jNum/jBool/jDecimal/
      jField/jObj/jArr). JVM: `navJson` InstanceV in json-plugin (additive â existing
      jsonParse/jsonRead/lookup untouched). JS: `_jsonValueTotal`+`jsonValue` in JsRuntimePart2b
      + `jsonValue`âRuntimeCall (decode works in emit-spa/browser). `runtime/std/json.ssc`,
      `examples/ui-typed-json.ssc`. Tests: 7 plugin + JsonTypedExampleTest (e2e JVM) +
      JsonValueNodeTest (real Node parity). busi can migrate 13 `*Q` (encode) AND
      onbStr/extractStr (decode) now â both backends.

- [x] **ui-typed-json-p3-fetch** â â Landed 2026-06-09. Shipped as thin `.ssc` sugar
      (`runtime/std/ui/fetch-json.ssc`), not new per-backend primitives: `fetchJsonValue`
      (GET â navigable `() => JsonValue` over fetchUrlSignal+jsonValue, reactive through the
      string signal) + `fetchJsonAction` (POST structured body over fetchAction+computedSignal).
      Note: built-in `fetchJsonSignal(modelType)` already exists (typed-model decode) â the
      navigable path is named `fetchJsonValue`. `headers` required (`.ssc` default-param /
      emptyHeaders eval gotcha). `examples/ui-fetch-json.ssc` + JsonFetchSugarTest (e2e).

- [x] **ui-typed-json-p4-stdlib** â â Landed 2026-06-09 (with p2-core): `runtime/std/json.ssc`
      surface, `examples/ui-typed-json.ssc`, README capabilities row. (No fenced-block wiring
      needed â JSON is consumed as a value, not a block language.)

### P2 â token-aware styled `TkNode` + status primitives (spec: `specs/std-ui-styled-tknode.md`)

Kills inline-CSS soup; makes dark/mobile + native theming work. **No CSS-string builder**
â `Style` fields are `Theme`-token refs resolved by `lower` per target (agreed with busi).
Start after P1.

- [x] **ui-styled-p1-spec** â `specs/std-ui-styled-tknode.md`. â Landed 2026-06-09.

- [x] **ui-styled-p0-theme** â â Landed 2026-06-09. Additive `theme.ssc`: `SpacingScale`
      gains `smd` (default 12, mobile 24, dark 12); `TypographyScale` gains `caption`
      (default 12px, mobile 14px). md/body/heading unchanged. ThemeTokensTest + StdUiSmokeTest
      green. (The `SpaceToken.Smd` / `FontToken.Caption` *enums* are added with the `Style`
      descriptor in `ui-styled-p2-nodes`; this slice is the theme-data half.)

- [x] **ui-styled-p2-nodes + p3-lower** â â Landed 2026-06-09 (done together â nodes
      need lowering to render). `nodes.ssc`: `TagNode/PillNode/KpiCardNode/TabBarNode`(+`Tab`)
      `/BoxNode` + `StyledNode(props: Map[String,String])`. `display.ssc`: `tag/pill/kpiCard/
      tabBar/styled` + `badge` default â status set. `layout.ssc`: `box(maxWidth/width/height)`.
      `lower.ssc`: token resolution (`_colorOf/_spaceTokOf/_radiusOf/_fontOf/_lenOf/_styleCss`),
      caption font for badge/tag/pill, text-decoration/cursor baked into tabBar, dark
      re-resolution. `examples/frontend/std-ui/styled-primitives.ssc` + StyledPrimitivesTest
      (emits default+dark, asserts token-resolved styles + no baked hex). Smoke+theme green.
      **API deviation (interpreter limits):** tokens are **strings** (cross-module enum
      matching is broken) and `Style` is a **`Map[String,String]`** (partial named-arg
      case-class defaults mis-bind). See spec Â§2 note.

- [x] **ui-styled-p4-example** â â Landed 2026-06-09 with the above:
      `examples/frontend/std-ui/styled-primitives.ssc` + README row. busi migration: replace
      `web/ui.ssc` helpers + inline-CSS cards/badges with `badge/tag/pill/kpiCard/tabBar` and
      `styled([...])` for custom chrome.

### P4 â UI runtime bugs (bug fixes â no spec)

- [x] **ui-bug-browser-columns** â â Already fixed on main by `250e9c75e` (2026-06-04,
      "fix(js): define browser ui typed column helpers"): `_ssc_ui_fieldColumn/dateColumn/
      moneyColumn/statusColumn/linkColumn` all defined in `JsRuntimeSignals.scala` +
      registered in the browser stub list (`JsGen.scala`). busi's proposal P4 predates the
      fix. No action needed â busi updates to current ScalaScript.

- [x] **ui-bug-jobj-failloud** [landed 2026-06-10] â Nested `jObj(List(jField(... jObj(...))))`
      with a paren mismatch triggered a silent ScalaMeta `termParam` NPE / interpreter
      hang. Root cause: `parseScalaWithDiagnostic` matched only `Parsed.Success`/`Parsed.Error`
      but scalameta *throws* a raw `NullPointerException` on truncated inputs like `def f(` /
      `def f(using ` (and deep nesting can `StackOverflowError`). New `safeParse` wraps every
      scalameta parse attempt (Source / block-Term / `trySplitParse`), converting a thrown
      `NonFatal`/`StackOverflowError` into a synthesized located `Parsed.Error`. Parser now
      fails loudly with a diagnostic, never a crash/hang. 4 regression tests in
      `ParseErrorPositionTest`; core 920/920 green.

---

## PDF generation + MIME (busi invoice/email â spec: `specs/pdf-mime-generation.md`)

busi clarified in rozum (2026-06-09): the real need is **one drop-in function**
`htmlToPdfBase64(html): String` â base64 PDF, matching their existing relay contract
(`POST {html} â {pdf_base64}`) so zero busi rewiring. HTML/CSS subset is pinned to the
invoice template (table layout, A4, basic typography/borders/background, `@media print`;
**no** JS/images/webfonts/grid/float; flexbox optional). **JVM/interpreter only â no JS
backend.** PDF half is the priority; MIME/SMTP are a later slice. Spec already pinned
(commit 342cf5162). Start order: **pdf-p1 â pdf-p2 â (mime-p3, smtp later)**.

- [x] **pdfgen-p1-engine** [landed 2026-06-10] â `htmlToPdfBase64(html): String` as a JVM
      intrinsic in the new opt-in `pdf-plugin` (OpenHTMLtoPDF 1.0.10 + jsoup 1.17.2 for
      lenient HTML parse â W3C DOM). Registered in `build.sbt` (module + packagePlugin list
      + PluginSpec). `PdfPluginTest`: invoice HTML â `%PDF-` + â¥1 page (PDFBox parse-back) +
      grid/float degrades (no throw). 4/4 green.

- [x] **pdfgen-p2-stdlib** [landed 2026-06-10] â `runtime/std/pdf-gen.ssc` (`package std.pdf`,
      `extern def htmlToPdfBase64`) + `examples/invoice-pdf.ssc` + documented HTML/CSS subset
      in spec + README. JVM-only noted.

- [x] **mime-p3-build** [landed 2026-06-10] â `buildMimeMessage(from,to,subject,htmlBody,
      attachments)` â RFC 5322 text in a new dependency-free `mime-plugin` (`std.mime`).
      0 attachments â `text/html` email; 1+ â `multipart/mixed` (base64 HTML part + base64
      attachments, RFC 2047 subject). `MimePluginTest` (4) round-trips through the Jakarta
      Mail / Angus reference parser (test-scope dep): headers, multipart count, attachment
      filenames + decoded content. Example `examples/invoice-email.ssc` (PDF â email).
      4/4 green. **Note:** plugin-arg unwrapping is selective â scalars come as raw
      `String`, collections stay as `Value.ListV` (matched accordingly).

> `busi-p4-smtp-send-extern` (above) is the relay-free SMTP DATA sender â the final slice
> that pairs with `mime-p3` + `pdfgen-p1` for a fully relay-free invoice-email path
> (`specs/smtp-send.md`). Sequence it after mime-p3.

---

## busi df-6 â Postgres out-of-the-box + LISTEN/NOTIFY (rozum seq-115, 2026-06-11)

busi's data-model-foundation df-6 landed end-to-end against REAL Postgres using
ScalaScript's first-class `backend/postgres` (scalascript-client-postgres,
org.postgresql 42.7.3 + HikariCP; `backend/sql` routes `postgres:` â `jdbc:postgresql:`
via DriverManager). Two follow-ups on our side surfaced; busi is unblocked for
single-node without them.

- [x] **pg-jar-installbin** (medium) â DONE 2026-06-11. Added
      `org.postgresql:postgresql:42.7.3` + `com.zaxxer:HikariCP:5.1.0` to
      `backendSqlRuntime` (`backend/sql`) alongside H2/SQLite â those are the runtime
      deps `installBin` stages into `bin/lib/jars/`. Verified: `installBin` stages
      `postgresql-42.7.3.jar` + `HikariCP-5.1.0.jar`; `DriverManager` resolves
      `jdbc:postgresql:` from the staged classpath (connection error, not "No suitable
      driver"). sql suites green (91+26+4).

- [x] **pg-listen-notify-extern** (low-pri) â DONE 2026-06-11. Added
      `Db.pgListen(db, channel)` / `Db.unlisten(db, channel)` /
      `Db.getNotifications(db[, timeoutMs])` in `SqlIntrinsics`, operating on the
      connection `ConnectionRegistry` caches per db name. Each notification is a
      `Map { channel, payload, pid }`; PG-only (clear error otherwise);
      injection-safe quoted channel. `BuiltinsRuntime` now collects all `Db.*`
      natives generically. Example `examples/pg-listen-notify.ssc` (live PG).
      Tests lock registration + PG-guard; receive path verified by busi vs real PG.
