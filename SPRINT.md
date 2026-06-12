# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Post-busi-seq124/125 follow-ups (2026-06-12)

Queued after the cross-module enum→trait fix (`1ddf10517`). Ordered by value;
work top-to-bottom. Each = its own worktree + commits; verify per the targeted-gate
note in `project_interp_enum_trait_hierarchy_0612` (full `backendInterpreter/test`
flakily hangs — use the 332-test targeted set if it won't pass).

- [x] **jit-supertype-type-test-compile** — DONE 2026-06-12. Took the cleaner
      **if-chain + runtime `JitGlobals.isSubtype`** route (not switch-arm expansion —
      avoids the dedup/case-collision edge case): a supertype `case _: T` routes to the
      if-chain (`walkMatchBody` + `walkMatchExpr`), `walkArmAsIfBranch` gained a
      `Pat.Typed` case emitting `isSubtype(inst.typeName(), "T")` for supertypes / exact
      tag for leaves. First-match order preserved. `JavacJitBackend` only (default);
      `AsmJitBackend` keeps the correct seq-124 bail (asm real-compile = follow-up).
      Tests: 3 hot-loop `BugReproTest` + `JitLintTest` `willJit==true` guard; 347-test
      targeted JIT/pattern/sealed/enum gate green.
- [ ] **full-interp-gate-green** — the seq-124/125 fix was pushed on the 332-test
      targeted gate because full `backendInterpreter/test` intermittently hangs at
      startup (parked-thread environmental hang, jstack-confirmed not-our-code).
      Land a clean full green run; if the hang is reproducible, fix the flaky suite
      (likely a server/port bind in a cluster test). Low new value, closes the loop.
- [ ] **cli-jit-classpath-fallback** — discovered during seq-124 diagnosis: `ssc run`
      / `cli/runMain` JIT codegen fails at runtime (`GenJit_*.java: package
      scalascript.interpreter.vm.jit does not exist`) and silently falls back to
      tree-walk. Verify whether the assembled `bin/ssc` fat jar (after
      `sbt cli/installBin`) is affected too, or only the dev `runMain` classpath. If
      the real CLI is affected, `ssc run` never JITs → silent perf loss; fix the
      JIT-runtime classpath. If runMain-only, document it.
- [ ] **declarative-ui-vnext** — remaining Scope B follow-ups (none requested by busi):
      B.3 `rowsPath` on the native/JVM backends, B.7 lint for Markdown `toolkit:`
      *link* references, and a typed / `key: signalId` `formBody` mapping. All small.

---

## New direction (2026-06-12) — type-level lambdas, direct-style eval, uuid-p6

### uuid-p6 — monotonic v7 counter (no blocker, small)
- [x] **uuid-p6** — ✓ DONE 2026-06-12 (`9fa049920`). `uuidV7Monotonic` / typed
      `Uuid.v7Monotonic()`: rand_a as a per-ms counter (RFC 9562 §6.2 Method 1) →
      strictly increasing within a ms, timestamp dominates across. JVM + JS/Node +
      effect classification (SideEffect like v4/v7). Verified end-to-end via bin/ssc
      (bare: valid + rand_a 2da→2db monotonic). Tests: UuidPluginTest (valid + 500-call
      strict monotonicity), DepEffectfulnessFixpointTest (effectful). NOTE: typed
      `Uuid.v7Monotonic()` shares the pre-existing std-import-path limitation affecting
      all of object Uuid (bare intrinsic is the working surface). ([[project_uuid_plugin]])

### type-level-lambdas — syntax + representation
Investigation (2026-06-12): current state is **surface-only**. `SType.HigherKinded(name,
arity)` round-trips `F[_]` for interface artifacts but "never participates in unification
or runtime semantics"; `SType.Match` (match-types) likewise surface-only. There is **no
`SType.TypeLambda`**. Scala 3 native `[X] =>> F[X]` does NOT parse (`=>>` is only in the
parser's `exprOperators` token set + `SsccFormatV3.TypeLambdaArrow`, never wired into type
parsing). `Lambda[X => F[X]]` "parses" only as a generic name application (no meaning).
ScalaScript is interpreter-first → types are erased at runtime, so "implementation" here is
parse + `SType` representation + show/parseSType round-trip; real reduction/unification is an
optional later phase (only matters for `ssc check` and typed backends).

- [ ] **type-lambda-p1-spec** — SYNTAX DECIDED 2026-06-12: support **BOTH** (a) placeholder
      `Map[Int, _]` short form (each `_` = a fresh lambda param, bound left-to-right in source
      order; `_` is free — ScalaScript has no existentials) AND (b) Scala-3 native
      `[X] =>> F[X]` for full control / reorder / multi-param / Scala copy-paste. Equivalent:
      `Map[Int, _]` desugars to `[X] =>> Map[Int, X]`. DELIVERABLE: write
      `specs/type-level-lambdas.md` — grammar for both surfaces, the `_`→`=>>` desugaring
      (param order = source order of `_`), `SType.TypeLambda(params, body)` shape, canonical
      `show` = `=>>` (`_` is sugar-in only), parseSType round-trip, and that all runtime
      backends ignore it (surface-only, like HKT/Match).
- [ ] **type-lambda-p2-parse-represent** — add `SType.TypeLambda(params, body)`; parse the
      agreed surface in type position; `show`/`parseSType` round-trip; `.sscc` v3 artifact
      round-trip. Accept partial application `Map[Int, _]` and named forms. Tests: parse +
      round-trip + interface-artifact stability. NO semantics yet (no reduction).
- [ ] **type-lambda-p3-semantics (optional, later)** — beta-reduce type lambdas in `ssc
      check` (apply `([X] =>> F[X])[A]` → `F[A]`) + HKT bound checking. Only if a real
      use-case (typed JS/Rust backend, or `ssc check` strictness) motivates it.

### direct-style-eval — Direction C (spec EXISTS, ready to plan)
`specs/direct-style-eval-spec.md` is written + detailed (dual-entry `evalDirect(...):Value`
throwing `EffectPerform(comp)` with `NoStackTrace`; `SSC_DIRECT_EVAL` flag default-off;
hybrid direct-fast-path + monadic-trampoline fallback; per-file migration order;
~530 sites, 62% in EvalRuntime). Goal: kill the per-call `Computation.Pure` allocation on
the effect-free hot path. Success: `recursiveEval` ≥ 20% faster on, JFR `Pure` −50%, zero
regressions across 1230+, multi-shot handlers identical both flags.

- [ ] **direct-style-eval-p0-resolve-open-questions** — clear spec §9 before any code:
      (1) review the `multishot-stack` worktree fixtures — are any passes dependent on the
      trampoline preserving all resume branches? link findings; (2) confirm `@pure` annotation
      is deferred to its own spec; (3) verify the DoubleV double-slot / JIT path stays correct
      under direct-style; (4) confirm `evalDirect` is suppressed inside any active handler
      scope (`EffectsRuntime.withHandler`). Output: §9 answered + go/no-go on the
      `EffectPerform` exception model. No production code.
- [ ] **direct-style-eval-p1-infra** — `evalDirect(term,env,interp): Value` +
      `final class EffectPerform(comp) extends Exception with NoStackTrace` + flag plumbing
      (`interp.directEvalEnabled`) + the dual-entry call-site idiom helper. NO call sites
      migrated yet; behavior identical with flag off. Tests: pure expr returns Value; a
      `Perform` throws+catches into the trampoline with identical result.
- [ ] **direct-style-eval-p2-evalruntime-leaves** — migrate EvalRuntime leaf exprs
      (`Lit`, `Term.Name`, pure-builtin `Term.Apply`) to `evalDirect`. Per spec §6 verify:
      flag-off identical, flag-on green, multi-shot fixtures identical.
- [ ] **direct-style-eval-p3-evalruntime-compound** — `Term.If`/`Term.Match`/`Term.Block`
      (effect boundaries stay monadic). Bench `recursiveEval` should start moving.
- [ ] **direct-style-eval-p4-block-pattern-call** — BlockRuntime (41) + PatternRuntime (37)
      + CallRuntime (15).
- [ ] **direct-style-eval-p5-dispatch** — DispatchRuntime (89, many effect-adjacent — care).
- [ ] **direct-style-eval-p6-validate-and-default** — hit success criteria (≥20% / −50% Pure /
      multishot identical); decide whether to flip `SSC_DIRECT_EVAL` default to `on`.
      Each phase = own worktree + commit; the spec's §6 verification protocol per batch.

### Deferred / blocked (NOT taken — recorded for clarity)
- **Real browser-WebSocket integration testing** — partial only: Node test runtime has no
      native WebSocket. A `ws` npm de-mock + local echo server is doable; the "real browser +
      live `wss://relay.walletconnect.com`" goal needs a browser harness AND a WC projectId.
      Hold for the PWA-wallet sprint.
- **WC project-ID** — BLOCKED ON USER: code already accepts `projectId`; needs a WalletConnect
      Cloud account + projectId provisioned as a CI secret. Cannot proceed without the secret.
- **FROST-Ed25519** — not technically blocked but large + speculative (no concrete driver);
      hold until a real use case.

---

## Bench-outlier follow-ups (2026-06-12)

Surfaced analysing `bench.sh` outliers (see [[project_jit_bare_length_bail_0612]]
in auto-memory + `docs/bench/cross-backend-gap-analysis.md`). The bare
`.length`/`.size`/`.isEmpty`/`.last` JIT bails and the missing `String.toLong`/
`toFloat` are already FIXED on main (commits `841319153`, `3d8db4b0a`). These two
remain — both characterised, neither yet started.

- [x] **interp-foldchain-unary-closure-fusion** (perf, interp JIT) — DONE 2026-06-12
      (`d1aadf920`), and the real cause was simpler than the proposed closure-sink.
      `s => s.trim.toInt` was ALREADY a recognised fuse op (`OpStringTrimToInt`); the
      bug was in `JitHofDispatch.fusedFoldLong`'s ListV loop, which called
      `asLong(elem)` BEFORE the map op — asLong throws on a StringV, so the fused path
      always threw and the JIT-invocation guard fell back to the tree-walk (string-
      split ran ~2.6 ms despite linting "JIT OK"). Fixed by applying the map op on the
      raw Value via `applyUnary` (handles string ops, falls through to numeric),
      matching every other map-applying site in the file. Shared runtime → both
      backends. **string-split 2.6 ms → 0.166 ms (16×, now ~2× off jvm**; residual =
      split allocation + parse, not the fold). Value-correct both backends; regression
      guard (direct `fusedFoldLong` string-op test). 1657 green. FOLLOW-UP (separate,
      lower-pri): genuinely *arbitrary* unary closures (shapes not in `unaryLong`,
      e.g. `s => s.substring(1).toInt`) still fall back — would need the compiled-
      closure / inline-body sink originally sketched here. Not blocking any corpus cell.

- [x] **rust-bench-antifold-alignment** (bench methodology) — DONE 2026-06-12.
      `bench/run.sc` wrapped EVERY assignment in `black_box` (3–4 barriers/iter),
      inflating rust loop cells 3–4× vs codegen-equal jvm. Replaced with ONE barrier
      on the first loop-carried reassignment per `pub fn`. VALIDATED that one barrier
      is necessary AND sufficient: hand-built `sumTco(100000,0)` at -O3 → 0 barriers
      folds to 0.000001 ms, 1 barrier honest, and **time scales linearly with trip
      count** (100k→0.025, 200k→0.051, 400k→0.102, 800k→0.204 — proves the loop runs,
      not a closed form). Note opaque inits/inputs do NOT suffice (LLVM solves the
      recurrence symbolically) — the barrier MUST sit on a per-iteration reassignment.
      Results: `recursion-tco` 0.34 → 0.025 ms (now ≈ jvm 0.025), `pattern-match-heavy`
      0.67 → 0.33, `list-fold` 0.12 → 0.05, `arith-loop` 1.42 → 0.86; `nested-loop`
      0.47 → 0.95 (UP = more honest, not a fold). No new ~0 cells; expression-body
      workloads (streams/typeclass-monoid) untouched. Corpus has no sequential-
      independent-loop workload (multi-loop cases are nested or invariant-hoisted),
      and any such future regression self-reveals as a ~0 cell. `docs/benchmarks.md`
      documents the unified strategy. Residual rust>jvm on loops jvm folds for free is
      the irreducible single-barrier tax (LLVM scalar-evolution ≫ HotSpot), not a
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

### Tier 1 — maintainability (decompose giant codegen files)
- [x] **jvmgen-decompose-p1** — DONE 2026-06-11. Effect-analysis → new
      `JvmGenEffectAnalysis` self-typed mixin (pattern already existed:
      JvmGenBlockAnalysis/TermAnalysis/MutualRecursion). Verbatim move, 1605 green.
- [x] **jvmgen-decompose-p2** — DONE 2026-06-11. Extracted the 5 pure runtime-source
      string constants → `JvmGenRuntimeSources` mixin. JvmGen 10565→7042 (−33%). 1605 green.
      (Remaining Preamble defs are state-coupled → future p2b.)
- [x] **jvmgen-decompose-p3** — DONE 2026-06-11. CPS-transform section (15 members)
      → new `JvmGenCpsTransform` self-typed mixin. JvmGen 7042→6073 (−969). 1605 green.
- [x] **jvmgen-decompose-p4** — DONE 2026-06-11. Mutual-TCO emission section (8 members)
      → new `JvmGenMutualTco` self-typed mixin. JvmGen 6073→5849 (−224). 1605 green.
- [x] **jvmgen-decompose-p2b** — DONE 2026-06-11. Remaining state-coupled Preamble+runtime
      section → new `JvmGenPreamble` mixin. JvmGen 5849→5019 (−830). Completes p2.
      **JvmGen split fully done: 10565→5019 (−53%) across 6 mixins.** 1605 green.
- [x] **jsgen-decompose** — DONE 2026-06-11. CPS-codegen section (15 members) → new
      `JsGenCpsCodegen` mixin. JsGen 5810→4942 (−868). 1605 green. Core genExpr/genApply
      dispatch left in place by design (out of scope — central, low win vs risk).
      **Tier 1 (maintainability) COMPLETE.** Spec: `specs/jsgen-decompose.md`.

### Tier 2 — performance gaps (each a dedicated sub-project)
- [x] **bench-honesty-varying-data** — SUBSTANTIALLY DONE 2026-06-11. Automated
      harness now honest: (1) audit complete across 3 docs; (2) FIXED `off`-baseline
      defect (algebraic folds gated behind FastTier; `pureCallSum` 0.003 on ↔ 11.748
      off); (3) de-folded `tuple_monoid`, the one automated compiled fold
      (`jvm` 0.011→205 µs, `js` 26.7→1688 µs); (4) interp column A/B-verified honest
      (eitherChain/optionChain/instanceField/arithLoop). The gap-doc's other fold
      cells (instance-field/bool-predicate/either/option/literal) were ad-hoc JVM
      probes with NO automated cell — nothing dishonest published. OPTIONAL future:
      add automated compiled cross-backend cells for them (same direction-(b)
      pattern). Spec `backend-perf-gaps.md` T2.1.
- [x] **js-persistent-map-hamt** — DONE 2026-06-11. Persistent `_HAMT` Map replaces the
      O(n) `new Map(obj)` copy. p2 sweep 71 `instanceof Map`→`_isMap` (`2d0b780d6`);
      p1+p3 activation (`a653cd331`): path-copying nibble-trie, value-equality keys,
      native-Map read interface, `_Map`/`updated`/`removed`/`filter`→`_HAMT`. Full suite
      1609 green; micro-bench O(n²)→O(n log n), ~100× at N=4000. Closes T2.2.
- [x] **ssc-jit-const-propagation** — DONE 2026-06-11. Found already implemented + tested:
      Stage 2 = `tryFoldInvariantAccumLoop` (`3174c0b4c`), Stage 3 = `tryClosedFormPolyLoop`
      (`abe7e4d02`, Gauss closed-form). Verified: JitLint+SscVm+ConstFold 277 green;
      `pureCallSum` 0.003 ms/op (~83×, native floor 0.247). Spec `backend-perf-gaps.md` T2.3.

### Tier 3 — correctness & hygiene
- [x] **cluster-jvm-js-handshake** — DONE 2026-06-11. The disabled
      `ClusterMultiBackendMatrixTest` is now ENABLED + PASSING (both nodes converge on
      `leader=node-bbb`). Fixed across 4 cross-backend layers: (1) JS `_runActors`
      scheduler block (already fixed); (2) JVM-codegen subprotocol echo
      (`481190610`); (3) JS-codegen subprotocol echo (`ede018597`); (4) **the real final
      blocker** — JS server hung on `java.net.http`'s default HTTP/2 `Upgrade: h2c`
      probe (Node routed it to the WS 'upgrade' handler → no WS route → socket hung →
      0 `'request'` events); fixed by serving non-`websocket` upgrades as normal
      HTTP/1.1 (`JsRuntimePart1d`). JVM↔JS multi-backend Bully convergence is real.
      Specs `backend-correctness-hygiene.md` T3.1 + `cluster-codegen-gap.md`.
- [x] **jit-predicates-bindingisref** — DONE 2026-06-11. Shared via
      `JitShapeCtx.callArgIsRef` + `JitPredicates.bindingIsRef`. Last duplicated JIT
      predicate — drift surface fully closed. 389 green both backends, 1605 full.
- [x] **cross-backend-method-classifier** — DONE 2026-06-11. Unlocked + scoped: the
      genuine classifier sets were JS-only (numeric inference); JVM/interp/Rust usage is
      dispatch *implementation* (out of scope). Created `core`
      `scalascript.transform.CollectionMethods` SSOT + migrated JS (behavior-identical,
      1612 green). JVM/Rust have no in-scope classifier sets. Spec
      `backend-correctness-hygiene.md` T3.3.

---

## Cross-backend gap re-audit (2026-06-10, post-HOF-JIT)

Full evidence-backed diagnosis of the remaining table unevenness in
[`docs/bench/cross-backend-gap-analysis.md`](docs/bench/cross-backend-gap-analysis.md).
Three categories: (1) real codegen gaps — js `map-ops` 40× = no persistent map,
js `hof-pipeline` = `_arith` on untyped list elements; (2) interp JIT cleverer
than AOT (jvm list-fold/range-sum/mutual-recursion *slower than interp* because
the interp JIT does invariant-hoist / range-fusion / mutual-TCO the AOT backends
don't); (3) **measurement artifacts** — many compiled sub-µs cells are folds
(verified: jvm instance-field 0.0003 → 0.0079 with a per-iter barrier).

**KEY TRAP (verified, do not repeat):** a naïve "de-fold via `Bench.opaque`"
honesty pass is counter-productive — `Bench.opaque` makes the interpreter's JIT
bail to tree-walk (interp arith-loop 0.287 → **3043 ms**, instance-field 0.0068 →
31.7 ms). A `VmCompiler.compileInto` identity case is NOT enough — the
while-loop / FastTier / bytecode matchers bail first. A correct honesty pass
needs opaque JIT-transparent across ALL interp matchers, OR redesign folded
workloads to loop-varying data. Both are real projects.

Recommended order: (1) JS numeric type inference [medium, clean], (2) honesty
(varying-data redesign or full opaque-transparency), (3) JS persistent map
[big/risky], (4) AOT codegen passes [per-case].

Progress:
- [x] **(1) js-numeric-inference** — DONE 2026-06-10 (`c8e4651c1`). JsGen tracks
      numeric-element collections + integer ranges, types HOF closure params from
      the element type through .map/.filter chains + foldLeft. hof-pipeline 0.028→
      0.0085 (3.3×, ≈jvm), range-sum 0.048→0.011 (4.4×). Verified identical output;
      231+58 tests green. Remaining residual on hof-pipeline: outer `sum + r.toLong`.
- [x] **(2) honesty pass** — ✓ DONE 2026-06-11 via `bench-honest-corpus-seed`
      (the "per-workload varying-data redesign" alternative named here, not the
      Bench.opaque route which does defeat interp JIT). Each folded corpus
      workload carries a non-linear 64-bit LCG and consumes every result, so no
      backend can constant-fold and no opacity barrier is needed; the harness is
      arity-aware and feeds an opaque seed. instance-field/tuple-monoid/etc. now
      report honest per-iteration cost across all 5 backends. Also fixed the
      emit-rust `.toInt` width bug surfaced by it. Spec:
      `docs/bench/corpus-antifold.md`, `specs/backend-perf-gaps.md` §T2.1.
- [x] **(3) JS persistent map** — DONE 2026-06-11 (`js-persistent-map-hamt`,
      `a653cd331`). The feared 70-`instanceof Map`-site completeness risk was
      de-risked via a duck-typed `_HAMT` + an `_isMap()` helper (p2 swept all 71
      sites) — native runtime maps and the persistent user Map coexist. `_Map`/
      `updated`/`removed`/`filter` route to `_HAMT` (path-copying nibble trie,
      value-equality keys). `map-ops` O(n²)→O(n log n) (~100× at N=4000); full
      suite green. Spec `specs/js-persistent-map-hamt.md`.
- [~] **(4) AOT codegen passes** — mostly DONE. (a) invariant-accumulation hoist
      generalised Double→Long/Int in `JvmGen` (`aot-hoist`, list-fold jvm
      0.075→0.0003, 215×). (b) **mutual-TCO** allocation-free merge for
      uniform-signature cliques (`aot-mutual-tco`, mutual-recursion jvm
      3.89→0.51, 7.6×; was slower than interp — closure trampoline allocated per
      step). Verified across Boolean/String/2-param-Int shapes (new
      `MutualTcoCrossBackendTest`). DEFERRED (low ROI): range map-fold fusion
      (`range-sum` jvm 0.0125 — not egregious).

---

## Honest-bench follow-ups + cross-backend outliers (2026-06-10)

Re-audit of the 24-workload cross-backend table (`bench.sh`).  After the
2026-06-09 honesty work (opaque-seed, JVM-parity, rust-fixes), the sub-µs
JVM/rust cells are understood as documented DCE/anti-fold artifacts.  The
*remaining* honest outliers are genuine per-backend pathologies.  Baselines
below measured on M1 via `bin/ssc bench --machine --backend <b> bench/corpus/<w>.ssc`
(2026-06-10, this machine — re-measure before A/B).

Root-cause finding (interp): generic HOF closure application
(`List.map` / `foldLeft`) costs **~1.25 µs/element**, independent of the
closure body (`s.length`, `s.toInt`, `s.trim` all ~9.5 ms on the
split-map probe).  `Computation.mapSequence` itself is already allocation-free
on the all-`Pure` path; the cost is in `callValue1Slow` per element:
`FrameMap.one` alloc + `callStackPush/Pop` + tree-walk of the body.  The
bytecode JIT (`JitRuntime.tryRun1`) bails on String/trait closures so none
of these workloads are JIT-accelerated.  Decomposition probe data:
`split-only 1.78ms`, `+.map(any 1-op closure) ~9.5ms`, `+.map(.trim.toInt) 14.7ms`,
`+foldLeft 18.7ms`; manual `parts(j)` index loop is **47ms** (List random
access is O(n) → O(n²)).

- [x] **interp-hof-frame-reuse** — DONE 2026-06-10. Added `CallRuntime.mapReusing`
      + `foldLeftReusing` + `ReusableFrame2` mirroring `foreachReusing` /
      `ReusableFrame1`; wired into `dispatchList1` `map` (FunV arg) and `foldLeft`
      (FunV curried arg). One reused frame mutated across the sequence instead of
      a `FrameMap1`/`FrameMap2` per element; bails to the allocating path on the
      first non-`Pure` body result.  A/B via JMH `stringSplit` (`scripts/bench
      [profile] stringSplit`): wall-clock **17.18 → 16.55 ms** (3.6%);
      allocation **2.25 → 1.90 MB/op** (15%, ~346 KB = the 12k per-element frames
      across 300×20 map + 300×20 foldLeft).  Wall win is modest because the body
      tree-walk (`s.trim.toInt` String dispatch) dominates — that is the next task
      `interp-jit-string-closure`.  Suite green (1588) on default bytecode JIT;
      change is purely in the interpreter tree-walk path (does not touch JIT
      compilation, so asm backend unaffected).  NOTE: the 3 JIT-off failures
      (`BugReproTest`/`JitLintTest`/`SscVmTest`, 114 cases) are **pre-existing** —
      they assert JIT-on behaviour and fail identically on a clean tree with
      `SSC_JIT_BYTECODE=off SSC_FASTTIER=off`.

- [x] **interp-jit-string-closure** — DONE 2026-06-10 (spec p10 in
      `specs/jit-completeness.md`). Two walls removed: (1) anonymous HOF
      closures never reached the JIT (`tryRun0/1/2/List` guarded
      `f.name.isEmpty`); lifted to `jitNameEligible = name.nonEmpty ||
      closure.isEmpty` (empty-closure lambdas have a stable identity via
      `emptyClosureFunCache`, so no `cache` leak; capturing lambdas stay out).
      (2) String methods: new `SSTR 50` opcode (trim/toLowerCase/toUpperCase),
      `GETFI` String branch extended (toInt/toLong via `v.toLong`), `VmCompiler`
      Term.Select String dispatch, untyped-param `String` inference from the
      runtime arg (`withParamHints`), and `StringV`/`MapV` accepted as ref args.
      **string-split JMH 17.18 → 2.74 ms (6.3×)**, corpus **18.7 → 3.26 (5.7×)**;
      hof-pipeline/range-sum now ≈10⁻³. Suite green (1593) both backends. `s +
      lit` concat still out of scope (heap alloc). NOTE: corpus A/B must
      `./install.sh` first — `bin/ssc` is otherwise stale (JMH `scripts/bench`
      uses fresh sbt classes and needs no install).

- [x] **interp-typeclass-fold-devirt** — DONE 2026-06-11. **FunV-local monomorphic
      using-resolution cache.** JFR (2026-06-11) pinpointed the dominant cost: the
      **call-site `resolveUsing`** (`GivenRuntime.concretizeUsingKey` →
      `matchTypeParts`/`splitTopLevel`/`applyTypeBindings`) — 47% of allocation,
      ~16% of CPU — re-deriving `A→Int` identically on every `combineAll` call.
      (The prior reverted attempt's `(FunV, argTypeSig)` global-map memo failed
      because its key computation allocated ≈ what it saved.) Fix: a single-entry
      cache on `FunV.usingResolveCache` keyed on a cheap arg type-sig
      (`runtimeValueType` of the regular args), applied only on the standard call
      path (resolves against `f.closure`; instance-method path left uncached), with
      a `givenFactories.size` generation guard. **A/B (16 measurements each):
      1.745 ± 0.018 → 1.667 ± 0.016 ms/op (−4.5%, non-overlapping); alloc 823 KB →
      386 KB/op (−53%).** Beats the prior attempt (1.665→1.682, no alloc change).
      Full suite 1619 green. Remaining gap (the ~84% general tree-walk eval of the
      generic HOF) needs JIT-compiling `combineAll` — separate, deep, not pursued.
      [historical diagnosis below]

- [~] **interp-typeclass-fold-devirt (superseded by above)** — PARTIALLY ADDRESSED + RE-DIAGNOSED
      2026-06-10. Added JMH `typeclassFoldMacro` (300×10, mirrors the corpus) —
      the requested visible A/B harness. **Re-diagnosis flips the original
      premise**: after `interp-jit-string-closure`, the `foldLeft` closure is no
      longer the cost. Decomposition (`s + xs.foldLeft(0)((a,b)=>a+b)` in a 300×
      loop = **0.007 ms** vs full `combineAll[A: Monoid](xs)` = **1.83 ms**, a
      246× gap; a plain non-generic fn wrapping the same fold = 0.007 ms) shows
      essentially ALL cost is the **context-bound generic call + per-call `summon`
      machinery inside `combineAll`**, not the fold. TRIED + REVERTED (no
      measured win, 1.665→1.682 JMH macro): a per-call-site `using`-evidence memo
      in `GivenRuntime`/`CallRuntime` keyed by `(FunV, argTypeSig)` — so the
      call-site `resolveUsing` is NOT the bottleneck. Remaining suspects (need a
      clean JFR with symbol resolution — the JMH stack profiler is drowned by
      `warmInterp` setup noise): the two `summon[Monoid[A]].empty/.combine`
      ApplyType evals per call + the `.empty`/`.combine` InstanceV member-access
      (possible fresh-FunV-per-call → JIT thrash), and that `combineAll` itself
      can't JIT (using params → VmCompiler bails). Deferred — smaller, riskier
      win (~2×, 1.7ms) than the JS outliers below; the macro bench stays for the
      next attempt.
      **ALLOC MEASUREMENT 2026-06-11** (`scripts/bench profile`-style `-prof gc`):
      `typeclassFoldMacro` allocates **688,891 B/op** ≈ **2.3 KB per `combineAll`
      call** (300 calls/op, 10-elem fold) — confirms the cost is **allocation-driven**
      in the generic summon/`combineAll` path, not the fold (an int fold should be
      ~0 B/op). This is the smoking gun the "need a clean JFR" note was after:
      the next attempt should target the per-call allocation (fresh `FunV`/boxing in
      `summon[Monoid[A]].empty/.combine` + the InstanceV member-access), e.g. a
      monomorphic inline cache for given dispatch (stage-9) or caching the resolved
      `Monoid` evidence's `empty`/`combine` FunVs across calls. Site-level JFR/async
      flamegraph not extracted (async-profiler not installed; JMH jfr file didn't
      land locally) — the B/op rate is sufficient to direct the fix.

- [x] **js-instance-field-shape** — DONE 2026-06-10. Root cause was NOT
      object-shape — it was codegen: `v.x` (a known case-class field) lowered to
      the megamorphic `_dispatch(v, 'x', [])` (full type-switch + `[]` alloc, ×4
      per `normSq`), and `x*x` to `_arith('*', …)` with a `typeof==='string'`
      repeat-guard. Fix (`JsGen`): track `instanceVars` (param `varName →
      caseClassType`); `Term.Select(v, f)` with a known field → direct `v.f`;
      `isIntExpr`/`isNumericExpr` recognise numeric case-class fields so `v.x*v.x`
      emits native `(v.x * v.x)`. Result: **`function normSq(v){ return ((v.x *
      v.x) + (v.y * v.y)); }`** — JS instance-field **1.42 → 0.0025 ms (568×)**,
      now ~8× jvm (was 4270×). 231 JS/cross-backend + 58 node tests green.

- [x] **js-nested-loop** — DONE 2026-06-10. A nested `while` inside a while body
      lowered through `genExpr` → wrapped in an IIFE `(() => { … })()`
      created+invoked every outer iteration (1000×), capturing the accumulator by
      closure (V8 deopt). Fix (`JsGen.genWhileBodyInline` + new
      `genNestedWhileInline`): emit the inner `while` as a plain JS statement, no
      IIFE. JS nested-loop **5.59 → 0.59 ms (9.5×)**, ~2× jvm; output verified
      (249500250000). Same suites green.

- [x] **bench-consistency-jmh-vs-corpus** [honesty/clarity] — DONE 2026-06-10.
      Added a "JMH and the corpus measure different scales under the same name"
      subsection to `docs/benchmarks.md` with the `typeclassFold` (micro) vs
      corpus (macro) example and the `…Macro`/`…Micro` naming guidance.
      `typeclassFoldMacro` already added (interp-typeclass-fold-devirt).

- [x] **bench-fairness-rust-antifold-audit** [verify, not a perf bug] — DONE
      2026-06-10. Verdict: **genuine but asymmetric — not equalisable**. Rust
      `black_box`es the RHS of *every* assignment (2×/iter for arith-loop)
      because LLVM -O3 would fold `sum+=i` to closed form; the JVM applies *no*
      per-iteration barrier (the corpus uses no `Bench.opaque`) because HotSpot
      doesn't fold this loop (jvm 0.24 ns/iter = real loop). So rust carries the
      minimum LLVM anti-fold barrier — which also blocks pipelining — while the
      JVM needs none. Equalising either way (drop rust black_box → fake ≈0 ms;
      add jvm per-assignment opaque → artificial) would be dishonest. Documented
      as genuine in `docs/bench/rust-jvm-antifold-fairness.md`.

---

## Bench honesty + ssc/JS perf (2026-06-09)

After closing the JVM↔Rust gap on six workloads, deeper inspection shows
the parity was achieved partly by HotSpot **constant-folding the entire
outer timing loop**, not just the workload:

  streams-pipeline: BENCH_SINK = 81_021_000_528 over ~18ms → 2.25 BILLION
  iterations consumed in 18ms = 8 picoseconds/iter, which is < 1 CPU
  cycle.  HotSpot's scalar-evolution rewrote
      while r < N do sink += workload()
  as
      sink += workload() * N

  with `workload() = 36` proven at JIT time.  This is the same kind of
  cheat we patched on Rust via `std::hint::black_box`.  Rust disassembly
  confirms it does NOT do this — Rust unrolled the chain into 10 explicit
  `mov w12, #N` instructions for the 10 iterator values (genuine 11ns/iter).

So:
  - Rust:  honest; loop unrolled to 10 ops.  ~11ns is the true cost.
  - JVM:   cheating; outer loop folded to one mul-add.  ~0.008ns is bogus.
  - ssc/JS: tree-walk / V8 — honest, no fold.  Their numbers ARE the cost.

The goal "JVM parity with Rust" is therefore reframed: prevent the JVM
fold (so JVM measures the real workload), THEN see if a universal ssc
JIT optimisation can close the resulting honest gap.

- [x] **bench-honest-jvm-blackbox** [investigated 2026-06-09; sink change
      doesn't solve fold — needs source-side workload-seed; see follow-up
      below] — Tried JMH-style `@volatile var t1, t2 + branch` Blackhole
      pattern as a drop-in replacement for `AtomicLong.getAndAdd`.  Result:
      streams-pipeline floor dropped from 2 ns → 1 ns (worse, not better),
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
      volatile-gated identity barrier (was `inline def x: A = x` → useless).
      Ready for follow-up `bench-honest-workload-seed` to use it explicitly.

- [x] **bench-honest-workload-seed** [partial — streams-pipeline + typeclass-
      monoid landed; bool-predicate needs unbox fix first] — Wrapped the
      first integer literal(s) inside `def workload` body with
      `Bench.opaque(...)`.  Bench.opaque is now a real volatile-gated
      barrier on JVM, so C2 can't precompute pure-arith chains that flow
      through it.
      Results (M1):
      - streams-pipeline JVM: 0.000002 → 0.000055 ms (2 ns → 55 ns) ✓
      - typeclass-monoid JVM: 0.000002 → 0.000005 ms (2 ns → 5 ns) —
        under the 10 ns floor because C2 speculates the volatile branch;
        a non-speculatable barrier would push higher (out of scope).
      - bool-predicate: left at baseline.  Wrapping the loop bound
        regressed ssc 1100× by breaking unboxed-Long fast path
        (`val limit = Bench.opaque(1000)` boxes `limit` as `Value`).
        Workload was already honest at 22 µs so no win, only loss.
      ssc/ssc-asm pay 15-30% for the per-call dispatch — accepted cost
      for cross-backend honest measurement.

- [x] **bench-honest-rust-verify** [done — docs/bench/rust-honest-disassembly.md]
      All six workloads have real work in the loop body (39-196 inst on
      M1).  `arith-loop` runs the actual 1M-iter loop; `streams-pipeline`
      is fully unrolled (10 iter); `bool-predicate`/`either-chain`/
      `option-chain`/`typeclass-fold` run real loops; `typeclass-monoid`
      is 3 inlined adds (no loop in source).

- [x] **bench-honest-js-investigate** [done — docs/bench/js-honesty-audit.md]
      Sink-quotient analysis on V8: for each suspect workload,
      `BENCH_SINK / expected_single_result == iter count` reported by the
      harness.  All six match exactly — V8 is not folding the outer
      timing loop on these workloads.  JS numbers are real per-iter costs.

- [x] **ssc-jit-loop-fusion-universal** [partial — core fusion landed
      2026-06-09; base-range fusion + <100ns const-fold is the follow-up
      below] — Iterator chain fusion in the ssc bytecode JIT.  Detect
      `recv.map(f).filter(g).foldLeft(z)(+)` (either stage optional) at
      emit time via `JitHofShape.fuseFoldChain` (shared by Javac + ASM)
      and lower the whole receiver to a single `JitHofDispatch.fusedFoldLong`
      that walks `recv` once with primitive `long` accumulators — no
      intermediate `ListV`, no per-stage re-boxing.  This is the "real"
      version of the bench-time hack: the optimisation now fires for any
      program, not just benchmarks.  On an unrecognised shape `fuseFoldChain`
      returns null and the existing per-stage path runs unchanged.
      A/B (Javac, `scripts/bench profile`):
      - hofPipeline gc.alloc.rate.norm 240 → 1.7 B/op (-99%), 506 → 3 MB/s
      - rangeSum    gc.alloc.rate.norm 1016 → 506 B/op (map list removed)
      Wall-clock unchanged at 6/20-elem inputs (the win is GC pressure,
      which scales with input length).  Spec: `specs/jit-loop-fusion.md`.
      Full backendInterpreter suite green on Javac + ASM; JIT disabled
      count unchanged (736).
      **Follow-up `ssc-jit-range-fusion` (landed 2026-06-09):** range-native
      fusion done — `JitHofShape.rangeBounds` + `JitHofDispatch.fusedRangeFoldLong`
      iterate `lo until/to hi` with a primitive counter (no base `ListV`), and
      `walkRef` now compiles `to` (inclusive) ranges (`rangeUntil(lo, hi+1)`).
      rangeSum 506 → 25.6 B/op (1016 → 25.6 vs pre-fusion). Both backends; suite
      green (1568); disabled count unchanged (736).
      **Still not done:** the `(1 to 10)…foldLeft < 100ns` literal-bound
      const-fold (the loop runs honestly now) — tracks with
      `ssc-jit-const-propagation` Stage 3.

- [x] **ssc-jit-const-propagation** — DONE 2026-06-11 (goals met; closed as T2.3,
      `ea6cacaea`). Stage 2/3 goals were achieved in a **different layer** than this
      entry anticipated: instead of the VmCompiler register-VM JIT, they live in the
      EvalRuntime FastTier eval path, which fires *first* for these workloads —
      `tryFoldInvariantAccumLoop` (invariant pure-call memoise, Stage-2 goal) and
      `tryClosedFormPolyLoop`+`walkLinearPoly` (Gauss closed-form range fold,
      Stage-3 goal). Verified: `pureCallSum` 0.003 ms/op (~83× the pre-fold
      baseline; native JVM floor 0.247 ms — the loop is eliminated). So the
      VmCompiler-level Stage 2/3 implementation is **unnecessary** (the FastTier
      fold pre-empts it). Stage 1 (literal arithmetic in VmCompiler.compileInto)
      remains as infra. Spec `specs/backend-perf-gaps.md` §T2.3;
      `specs/jvmgen-decompose.md` cross-refs.

- [x] **ssc-jit-escape-analysis** [partial — map-into-sink fusion landed
      2026-06-09; full cross-function inlining still open] — Stack-allocate
      Option/Either/case class instances whose lifetime is bounded to the
      current method.
      **Landed (map-into-sink slice):** a `.map(unary)` feeding `.flatMap(global)`
      or `.getOrElse(default)` allocated an intermediate Option/Either wrapper
      purely to be consumed one step later. `peelMapUnary` detects it; the
      backends emit `JitHofDispatch.mapFlatMapGlobalLong` / `mapGetOrElseLong`
      which apply the map inline — no intermediate wrapper. Both Javac + ASM;
      falls back to per-stage on a miss.
      A/B (tight, `scripts/bench profile`):
      - optionChain 116 → 100 B/op (-14%)
      - eitherChain 708 → 564 B/op (-20%; ASM 528)
      Suite green (1572) both backends; disabled count unchanged (736).
      **Follow-up `instancev-either-option-fieldsarr` (landed 2026-06-09):** the
      `parse`/`lookup` `Right`/`Left` wrappers were `InstanceV` + per-instance
      `IMap.Map1`, and each dispatch/JIT read re-materialised that Map. Migrated to
      the positional `fieldsArr` repr via `Value.singleValue` + `typeFieldOrder`
      registration of Right/Left/Some + arr-aware `JitHofDispatch.valueField`.
      **eitherChain 488 → 224 B/op (-54%)**, optionChain flat (Some is `OptionV`).
      1572 green. The remaining Either cost is the wrapper *existing at all*.
      **Still open (bigger, own session):** eliminating the wrapper entirely needs
      cross-function inlining of small pure constructors (or a value-class
      Option/Either representation). pattern-match-heavy not yet addressed.

---

## JVM perf parity with Rust — close the gap (2026-06-09)

After defeating LLVM fold (`bench-opaque-seed`), Rust reports much smaller
numbers than JVM on 6 workloads even though both produce identical correct
results (verified by building each crate with a println main):

| Workload | Result | Verified |
|---|---|---|
| typeclass-monoid | 6 (= 0+1+2+3) | ✓ |
| typeclass-fold | 16500 (= 300·55) | ✓ |
| streams-pipeline | 36 (= 6+12+18) | ✓ |
| option-chain | 44850 | ✓ |
| either-chain | 45450 | ✓ |
| bool-predicate | 999 | ✓ |

**Goal**: make JVM bench numbers comparable to Rust (within ~3×).  Where
the gap is structural (e.g. interface dispatch, heap-allocated ADTs), close
it via JvmGen codegen — direct-call dispatch, value-class ADTs, stack-
allocated Either/Option, foldLeft fast-path on Range, etc.

Each task: one focused commit + A/B bench numbers (before / after / Rust)
in the commit body; never ship a non-win.

- [x] **bench-gap-typeclass-monoid-jvm** [JVM 1ns vs Rust 2ns — JVM 2× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.0010` vs Rust
      `0.000001` = **1000×**.  Workload: 3 nested `combine(...)` calls
      returning 6.  Hypothesis: JvmGen emits `intMonoid` as a Scala 3
      `given` object with virtual dispatch through the `IntMonoid` trait
      every call, plus `Int`→`Integer` boxing on each `(a, b)` argument.
      Fix path: when JvmGen sees a `given X: T with { def f }` whose `f`
      body is a single arithmetic expression and `X` is referenced only
      by direct name (not through `summon`/upcast), emit `f` as a static
      method on a Scala `object X` and call sites as direct invocation —
      no interface dispatch, no Integer boxing.  Target: JVM ≤ 3×Rust
      (i.e. ≤3ns).

- [x] **bench-gap-typeclass-fold-jvm** [JVM 3µs vs Rust 8µs — JVM 2.6× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.004` vs Rust `0.0072` —
      JVM is already faster than Rust here, and ssc is **460× slower**.
      Real target: fix ssc/asm.  Workload: `combineAll(xs).foldLeft(empty)
      (combine)` 300 times with `xs = List(1..10)`.  Hot path is
      `summon[Monoid[A]]` resolution inside the lambda.  Fix on the
      interpreter side: cache the resolved `summon` for `Monoid[Int]` at
      the call site; emit a specialized fast-path `foldLeft` for `List[Int]
      + (Int,Int)=>Int` in the JIT.  Re-evaluate the JVM/Rust split after
      that; if JVM stays ahead, no JVM action.

- [x] **bench-gap-streams-pipeline-jvm** [JVM <1ns vs Rust 11ns — JVM faster; closed via fused while-loop emit in bench wrapper] — JVM `0.000047` vs Rust
      `0.000005` = **9×** (after adaptive-reps fix; was 200× before).
      Workload: `(1 to 10).map(*2).filter(%3==0).foldLeft(0)(+)`.
      Hypothesis: JvmGen lowers the chain to native Scala
      `Range.map(...).filter(...).foldLeft(...)` — each step creates an
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
      No view allocations, no lambda wrappers — HotSpot will JIT this to
      native code identical to what LLVM produces for Rust.  Target: JVM
      ≤ 3×Rust (~15ns).

- [x] **bench-gap-option-chain-jvm** [JVM 341ns vs Rust 472ns — JVM 1.4× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.002` vs Rust `0.000466` =
      **4×**.  Workload: 300 iters of `Some(i).flatMap(lookup).map(+1).
      getOrElse(0)`.  Hypothesis: Some/None on JVM are heap-allocated
      via Scala 3 `enum Option` → 300 allocations/iter × 4 chain steps
      = 1200/iter.  Fix path: introduce a value-class Option carrier in
      JvmGen — `opaque type FastOption = Long` where the high bit is the
      None tag and the low 32 bits are the Int payload.  Emit
      `Some(i)`→`fastSome(i)` and `.flatMap`/`.map`/`.getOrElse` as
      inline ops on the Long.  Target: JVM ≤ 3×Rust.

- [x] **bench-gap-either-chain-jvm** [JVM 329ns vs Rust 590ns — JVM 1.8× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.001` vs Rust `0.000541` =
      **2×**.  Workload: 300 iters of `parse(i+1).map(+1).flatMap(parse).
      fold(_=>0, x=>x)`.  Same heap-allocation cause as option-chain.
      Same fix shape: value-class Either with packed Long
      (`(tag<<63) | (left ? string-handle : int-payload)`) for
      `Either[String, Int]` specifically.  Generalised JvmGen
      Either-specialisation pass is the right scope.  Target: JVM
      ≤ 3×Rust.

- [x] **bench-gap-bool-predicate-jvm** [JVM 21ns vs Rust 970ns — JVM 46× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.001` vs Rust `0.000956`
      = **~1×, already at parity**.  Smallest gap of the six; no action
      required.  Re-verify on the next bench run; if it slips above
      3×Rust under load, investigate.

---

## Bench correctness — defeat LLVM constant-folding via opaque seed (2026-06-09)

LLVM `-O3` performs scalar-evolution analysis on pure loops, deriving
closed-form solutions for arithmetic progressions. `for i in 0..N { sum += i }`
becomes the literal `499_999_500_000` (Gauss' formula) at compile time — the
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

- [x] **bench-opaque-seed-infra** (resolved via bench/run.sc auto-patch — see below) — Change `workload` signature to
      `workload(seed: Long): Long` in `bench/run.sc` Rust wrapper +
      `tools/cli/src/main/scala/scalascript/cli/Main.scala` interp/JVM/JS
      bench wrappers. Each passes an opaque-zero seed. Acceptance: a workload
      that takes `(seed: Long)` and `+ seed` at the end runs on all 5 backends
      without n/a.

- [x] **bench-opaque-seed-anti-fold** (resolved via bench/run.sc auto-patch — see below) — Update each of the 24 corpus
      workloads to take `(seed: Long)` and mix `seed` into the hot path
      nonlinearly (`^ seed` inside the loop body, etc.) so LLVM cannot derive
      a closed-form. Recipe: pure-arith workloads xor `i ^ seed` inside the
      inner loop (semantics preserved for seed=0); real-work workloads add
      `+ seed` at the sink. Acceptance: `./bench.sh` reports Rust numbers
      ≥1µs on workloads previously reporting <100ns, and JVM/JS/interp
      numbers unchanged (within noise).

---

## Bench n/a — close the gaps (2026-06-09, from `bench.sh` after rust-bench-fixes)

After all 24 corpus workloads run cleanly on rust, four `n/a` cells remain
on other backends. Each is a genuine API/codegen gap, not a benchmark bug.
Fix them properly (no ad-hoc bench rewrites). Ordered simplest-first.

- [x] **bench-na-jvm-typeclass-monoid** — `typeclass-monoid.ssc` n/a on jvm.
      Source uses `given intMonoid: IntMonoid with { def empty; def combine }`
      but `IntMonoid` is not declared as a trait. JVM codegen rejects the
      anonymous given target. Fix: prepend a `trait IntMonoid { def empty: Int;
      def combine(a: Int, b: Int): Int }` declaration in the bench corpus AND
      verify the JVM backend `Defn.Given` lowering handles a named-trait given
      with multiple defs. Acceptance: `./bench.sh --backend jvm typeclass-monoid`
      reports a numeric ms/iter result.

- [x] **bench-na-js-either-chain** — `either-chain.ssc` n/a on js.
      JS backend has no `Either[L, R]` runtime — `Right(x).map(...).flatMap(...).fold(...)`
      chain falls off a cliff somewhere. Fix: extend `JsRuntimePart*` with an
      `Either` runtime (Right/Left tagged variants + .map/.flatMap/.fold lowering)
      mirroring the existing `Option` runtime. Cross-check with the Either path
      in `runtime/std/either.ssc` if present. Acceptance:
      `./bench.sh --backend js either-chain` reports a numeric result.

- [x] **bench-na-js-map-ops** — `map-ops.ssc` n/a on js. Already covered by
      the in-flight `js-map-ops-bench` claim/branch — see `.work/active/`.
      Verify the claim is current; if abandoned (>20 min stale heartbeat),
      release via `/multi-agent triage js-map-ops-bench`. Acceptance:
      `./bench.sh --backend js map-ops` reports a numeric result.

- [x] **bench-na-streams-pipeline-all** — `streams-pipeline.ssc` n/a on
      ssc/ssc-asm/jvm/js. The bench uses `Source.range(1, 10).map(...).filter(...)
      .foldLeft(...)` — this surface only exists in the rust backend (added by
      `rust-backend-r6-streams`). To make it portable, add a synchronous
      `Source` API to `runtime/std/streams.ssc` (or wherever the streams stdlib
      lives) that the JVM/JS/interp backends can lower the same way they lower
      `List` HOFs. `Source.range/fromList/.map/.filter/.foldLeft/.toList` must
      produce equivalent results across all five backends. Acceptance:
      `./bench.sh streams-pipeline` reports numeric results on every backend.

---

## Rust bench fixes — new rustc errors (bench.sh 2026-06-08, ordered simplest-first)

Found by re-running `bench.sh` after the previous fix wave.  All items fixed 2026-06-08.

- [x] **rust-fix-bench-non-i64-return** — `bench/run.sc`: `_run_workload() -> i64`
      fails when `workload()` returns a non-`i64` type.  Affected: `tuple-monoid`
      (`workload() -> (i64,i64,i64,i64)`, `E0308`) and `pattern-match-heavy`
      (`workload() -> f64`, `E0308`).
      Fix: changed `_run_workload()` to return `()`, emit `std::hint::black_box(r);`
      as a statement, dropped `-> i64` from the signature.  Fixed 2026-06-08.

- [x] **rust-fix-iife-parens** — `RustCodeWalk.scala`: IIFE closures emitted as
      `move |x| { body }(arg)` rejected by `rustc` with `E0618`.
      Fix: wrapped closure in parens: `(move |x| { body })(arg)` in all 4
      Either map/flatMap/fold emitters.  Affected: `either-chain`.  Fixed 2026-06-08.

- [x] **rust-fix-struct-copy** — `RustCodeWalk.scala`: user structs from `case class`
      not derived `Copy`, passing by value in a loop gave `E0382`.
      Fix: `renderStruct` now emits `#[derive(Debug, Clone, Copy)]` when all
      fields are primitive (`i64`, `f64`, `bool`).  Affected: `instance-field`.
      Fixed 2026-06-08.

---

## Rust backend — compilation fixes (from bench.sh 2026-06-08)

`backendRust/compile` and `backendRust/Test/compile` are currently broken.
All errors are in two files: `RustCodeWalk.scala` and `RustGenR23Test.scala`.
Ordered simplest-first.

### Syntax fixes in test file (trivial — copy-paste ttypos)

- [x] **rust-fix-test-unclosed-quote** — `RustGenR23Test.scala:140`: missing
      opening `"` before `42` in `assert(g.contains("42".to_string()..."))`.
      Fixed 2026-06-08.

- [x] **rust-fix-test-unclosed-paren** — `RustGenR23Test.scala:200`: missing
      closing `)` on `assert(g.contains("if v % 2 == 0 {")`  —  one `)` short.
      Fixed 2026-06-08.

### Syntax fix in main source (one missing paren — cascades to 50+ errors)

- [x] **rust-fix-codewalk-unclosed-paren** — `RustCodeWalk.scala:351`: `Right((variant, (ctor, EnumCtor(...)))` was missing one closing `)`.
      Fixed 2026-06-08.

### Pattern-match syntax errors in main source

- [x] **rust-fix-term-paren** — `RustCodeWalk.scala`: `m.Term.Paren` does
      not exist in scalameta — removed from `isRangeExpr`, `isStringExpr`, `isEitherExpr`.
      Fixed 2026-06-08.

- [x] **rust-fix-typed-bind-syntax** — `RustCodeWalk.scala:1123,1125`: `case t: SomeClass(args)`
      replaced with `case t @ SomeClass(args)`.  Fixed 2026-06-08.

- [x] **rust-fix-none-unreachable** — `RustCodeWalk.scala`: `case m.Term.Name("None")`
      was placed after the catch-all `case m.Term.Name(n)` — moved before it.
      Fixed 2026-06-08.

- [x] **rust-fix-test-assert-mismatch** — 8 test assertions in `RustGenR23Test.scala`
      had wrong expected strings (wrong int suffixes `i32`→`i64`, literal format
      `2f64`→`2.0f64`, missing `.to_string()` on string args, etc.).
      Fixed 2026-06-08. Result: 104 pass, 2 ignored.

### Rust runtime errors (from bench.sh 2026-06-08, `rustc` fails)

- [x] **rust-fix-split-string-pattern** — `RustCodeWalk.scala`: string
      args to `.split`/`.splitn` are now rendered as bare `&str` literals
      via `renderStrPatternArg` (no `.to_string()`).  Fixed 2026-06-08.

- [x] **rust-fix-enum-ctor-call** — `RustCodeWalk.scala`: `collectTopVals`
      was using empty `ctorMap`, so enum ctors in top-level `val` initialisers
      fell through to `Circle(args)` call syntax.  Fixed by computing `ctorMap`
      before calling `collectTopVals` and passing it in.  Fixed 2026-06-08.

### Rust codegen gaps (from bench.sh 2026-06-08, `rustc` or codegen errors; ordered by difficulty)

- [x] **rust-fix-option-chain-var-scope** — `option-chain` bench: `cannot find value 'i'`.
      Root cause: `contentTopVals` used `node.tree.collect { case v: Defn.Val }` which
      recursively found ALL `val` bindings in the tree (including those inside
      `while` bodies of `def`s), injecting them as top-level `let` bindings into
      every generated function.  Fixed: replaced `.collect` with top-level-only
      `stats` from `m.Source`/`m.Term.Block` direct children.  Fixed 2026-06-08.

- [x] **rust-fix-either-chain-select-chain** — `either-chain` bench: `parse(i+1).map(...).flatMap(...).fold(...)` failed
      because `isEitherExpr(parse(i+1))` returned false (user function calls not recognized).
      Fix: added a heuristic case to `isEitherExpr`: any `Term.Apply` that is NOT
      a known Option/List/Map constructor is treated as potentially Either-shaped.
      Generated Rust uses nested `match` expressions — verbose but correct.
      Fixed 2026-06-08.

- [x] **rust-fix-instance-field-vec-type** — `instance-field` bench: `Vec` was a
      user-defined `case class Vec(x: Int, y: Int)`, not a stdlib List.
      Root cause: `mapType` didn't recognize user-defined types; `collectStandaloneCaseClasses`
      didn't exist; `Vec(3,4)` was treated as a list ctor.
      Fix: (1) `collectStandaloneCaseClasses` collects case classes not extending any sealed trait;
      (2) `renderStruct` emits `pub struct T { pub field: Type, }`;
      (3) struct ctors added to ctorMap; (4) user ctors take priority over stdlib names in Apply.
      Also added generic `Term.Select(qual, field)` → `qual.field` for struct field access.
      Fixed 2026-06-08.

- [x] **rust-fix-effect-pure** — `effect-pure` bench: `Int ! Logger` effect type.
      Fix: tagless-final (R.4.2) — `T ! E` strips to `T` in return type; effectful defs
      gain `_eff: &mut impl LoggerEffect` param; call sites thread `&mut _eff`; 
      `runLogger { body }` injects `NoOpLogger`; `runtime/effects.rs` emitted with
      `LoggerEffect` trait + `NoOpLogger`.  7 new tests (107 total).  Fixed 2026-06-08.

- [x] **rust-fix-effect-stream** — `effect-stream` bench: `runToList` + tuple val binding.
      Fix: (1) `renderLetBinding` handles `val (a, _) = expr` tuple pattern;
      (2) `Stream.emit(x)` → `_eff.stream_emit(x)`; (3) `src.runToList()` →
      `src.items.clone()`; (4) `runStream { body }` injects `VecStream::new()`,
      returns `(_eff, ())`; (5) `VecStream<T>` + `StreamEffect<T>` in effects.rs.
      6 new tests (120 total). Fixed 2026-06-08.

### Unimplemented feature (tuple ++ concat in Rust backend)

- [x] **rust-fix-tuple-concat** — `RustCodeWalk.scala`: `++` on tuples now
      flattens via `collectTupleConcat`.  Root cause: scalameta parses
      `(a,b) ++ (c,d)` with the RHS as **two** separate infix args, not one
      `Term.Tuple` — added a second branch handling `args.values.size > 1`.
      Also added `_tupleConcat` call handler for completeness.
      106 tests pass, 0 ignored.  Fixed 2026-06-08.

---

## busi feedback — parser/resolver/runtime fixes (high priority)

Source: `busi/docs/scalascript-issues.md` (212 lines, by phase). Reported
2026-06-06 by the busi agent after phases 0–15 of the business-management
app. Every item has a workaround on the busi side — none are blockers —
but each "eats" 1–2 hours per new busi phase. Ordered by how much they
slow down ongoing work, P0 first.

Recommended first batch (per busi): **P0 #1, #2, #3 + P1 #5**. All four
are isolated in lexer / parser / resolver, give the biggest time-back per
fix, and don't require a runtime refactor.

### P0 — parser/resolver, hit on every new phase

- [x] **busi-p0-try-catch-handler** — `try / catch _ => ...`
      (`Term.TryWithHandler`) is not supported — only `try / catch case
      _ => ...`. Either support both forms or emit a parser message
      suggesting `case`.

- [x] **busi-p0-statusval-collision-a-half** [landed 2026-06-10] — Follow-up to
      `9a3bea18e`. A bare `val x = Foo` with no ascription, where `Foo` is bound
      to both a stable value and a case constructor, now raises a located error
      `name 'Foo' is bound to both a stable value and a case constructor; add a
      type ascription or rename one`. Implemented in `StatRuntime.disambiguateValBinding`:
      when `decltpe.isEmpty` and the bare RHS name is in `shadowedAlternatives`,
      `interp.located(...)`. Any ascription opts out (`Type.Name` → B-half;
      function/other types keep the case-constructor `direct`). 2 regression tests
      in `StatusValEventCaseCollisionTest`; backendInterpreter 1583/1583 green.

### P1 — pre-existing bug surfaced during busi phase 89d testing

- [x] **busi-p1-phase90-rule-bool-coercion** — `make test-phase90-rule`
      and `test-phase90i` fail with `Cannot apply unary ! to 1` at
      `tests/phase90-rule/rule-pack.ssc:118`, on the `Activity(org,
      "act-immigration", actor, Immigration, ..., Active, Map(), 1)`
      call site.  Recorded by busi under "Phase 89d finding" in
      `busi/docs/scalascript-issues.md`.  **Pre-existing — confirmed
      not caused by the P0 #1+#2+P1 #5 wave** (sergiy 2026-06-07).
      Source file: `/Users/sergiy/work/my/busi/tests/phase90-rule/
      rule-pack.ssc` (181 lines).  Shape of the error suggests an
      Int-to-Bool coercion path where a `1` literal is being treated
      as a Boolean operand to unary `!`; root cause likely in pattern
      matching / typeclass dispatch.  Not a blocker for P0 #3 — fix
      when convenient.

### P1 — frequent small splinters

- [x] **busi-p1-map-direct-apply** — `map(key)` direct access throws
      "Instance is not callable". Add `apply` on `Map`.

- [x] **busi-p1-string-split-2arg-and-map** — `String.split(sep, limit)`
      (2-arg form) does not exist; `.map` on the raw split result (Java
      Array) crashes — forcing `.toList` everywhere. Add the 2-arg form
      and make `.map` work on the split result directly.

- [x] **busi-p1-map-getorelse-null-semantics** — `Map.getOrElse(key,
      default)` returns `null` when the present value is null (SQLite
      `NULL`). Semantics "absent vs. null" should be resolved in
      favour of `default`.

- [x] **busi-p1-while-typed-empty-list-bug** [no longer reproduces 2026-06-10 —
      fixed by intervening while-JIT work; locked with regression tests] — `while`
      + `var i += 1` + typed `List[(Int,T)]()` — body iterates, list stays empty.
      Could not reproduce in any form: non-JIT, JIT-hot (200k-call function),
      case-class tuple elements, `Set.contains` in body, N=50k. All correct.
      Two regression guards added to `BugReproTest` (typed-empty-tuple-list +
      hot-function while-JIT path).

- [x] **busi-p1-map-update-foldleft-unreliable** [no longer reproduces 2026-06-10
      — fixed by Direction B fieldsArr flag-flip; locked with regression tests] —
      When a `foldLeft` accumulates a `Map[String, CaseClass]` and one branch
      re-constructs the case class (10+ fields) to store an updated copy,
      subsequent `.values.toList.sortBy(...)` or keyed-access calls produced
      `"Instance is not callable"` (`CallRuntime` applies a case-class `InstanceV`
      with no `apply` field). Root cause: pre-flag-flip, a 10+-field case class
      stored its fields in a `HashMap` (Scala Map → HashMap at ≥5 entries) and
      some path mishandled HashMap-backed instances; the 2026-06-03 fieldsArr
      flag-flip unified all field counts onto the positional array. Could not
      reproduce across 7 variants (foldLeft over List/Map, match-branch
      reconstruction, 10–11 fields, `_` sortBy, keyed access, case-class methods).
      Two `BugReproTest` guards added mirroring the busi `applyRetirement` shape.

- [x] **busi-p1-map-concat-returns-tuplev** — `Map(...) ++ otherMap`
      returns `TupleV((Map(...), Map(...)))` instead of a merged map.
      Subsequent `.get(key)` then crashes with `No method 'get' on
      TupleV(...)`.  Found by busi in phase 89a (`seedRitualsForActivityKind`).
      Repro: `val a = Map("x" -> "1"); val b = Map("z" -> "3"); val c =
      a ++ b; println(c.get("x"))`.  Workaround on busi side: inline
      pairs into a single literal.  Fix: route `Map ++ Map` through
      `dispatchMap` instead of falling into the tuple-wrap path in
      `DispatchRuntime.infix`.

- [x] **busi-p1-arrow-vs-plus-precedence** — `Map("k" -> "Prefix " +
      value)` parses as `Map("k" -> ("Prefix ", value))` — the `->`
      arrow associates tighter than `+`, so the second tuple element
      becomes `value` instead of being concatenated.  Runtime then
      crashes when the consumer tries to use the value as a String.
      Found by busi in phase 89f.  Workaround: bind to a local val or
      add explicit parens `Map("k" -> ("Prefix " + value))`.  Fix
      direction: either tighten `+` precedence relative to `->` for
      strings, OR emit a parse-time warning when `->` RHS is a binary
      `+` with the LHS being a string literal (likely user intent
      mismatch).

### P2 — `emit-js` / browser

- [x] **busi-p2-emit-js-process-stdout** — `emit-js` always appends
      `process.stdout.write(...)` → `ReferenceError: process is not
      defined` in the browser on every load. Fix: guard with `typeof
      process !== 'undefined'` or use `console.log`. busi worked around
      via `emit-spa`, but `emit-js` is effectively unusable in the
      browser today.

- [x] **busi-p2-emit-js-transitive-imports** [no longer reproduces 2026-06-11,
      confirmed on busi rulepack-graph] — `emit-js` was reported to drop
      transitive imports (`A → B → C`, bundle
      of `A` not closing `B`'s code over `C`). Does **not** reproduce on the
      current backend: `genImport` recurses into a child module's own imports
      (`childGen.genImport(nestedImp)`), and imported modules are emitted in
      full (the child `JsGen` is created without `reachableNames`, so
      tree-shaking only prunes the entry module's own declarations, never
      transitively-imported ones). Verified end-to-end through the exact
      `emit-js` path (`generateSegmented`, tree-shaking ON, per-segment
      `_output` flush) for three shapes — package `A→B→C`, name-only
      (no-package) `A→B→C`, and 4-level `A→B→C→D` — all run and print the
      transitively-computed result. Regression guards added in
      `JsGenStdImportTest` (`examples/js-transitive-iife{,-nopkg,-4}/`).
      busi confirmed (rozum seq-110): `ssc emit-js web/app.ssc` on their
      deepest real graph (`app → rulepack_studio → rulepack_list /
      schema_inference → std/ui/{content,data,…}`, 616 KB bundle) loads clean
      under node with zero `ReferenceError` — the whole transitive graph and
      top-level `appView` build. Their graph uses only `[name](path)` imports
      (no wildcard / re-export in emit-js scope), so those forms would need a
      synthetic repro, not a busi one. Closed.

### P3 — name shadowing from plugin intrinsics

- [x] **busi-p3-ratelimit-intrinsic-shadow** [landed 2026-06-11] — Policy
      chosen: **user wins + warning**. A user top-level `def` sharing a bare
      name with a plugin intrinsic now always wins; a one-time `[warn]` is
      emitted (recorded in `intrinsicShadowWarnings`). Both load orderings
      handled. Spec `specs/intrinsic-shadow-policy.md`; 4 tests in
      `IntrinsicShadowTest`.

- [x] **busi-p3-module-fn-name-conflict** [landed 2026-06-11] — Policy chosen:
      **last import wins + warning**. Importing the same fn name from two modules
      now emits a one-time `[warn]` (recorded in `importNameConflictWarnings`)
      instead of silently shadowing. Scoped to callable-vs-callable so the
      status-val/case-constructor disambiguation is untouched. Spec
      `specs/import-name-conflict-policy.md`; 3 tests `ModuleFnNameConflictTest`.
      NB: the original downstream `No key 'toString' in map` crash did not
      reproduce from a plain two-module collision (already last-wins cleanly);
      the import-time warning now surfaces the conflict early. Awaiting a busi
      repro if the crash recurs.

### P4 — future externs (not blocking today)

- [x] **busi-p4-ed25519-rsa-verify** [landed 2026-06-10, commit 778116b33] —
      Ed25519 / RSA public-key `verify` externs in std.crypto (JVM). Closes the
      `signature.unsupported` quarantine for busi phase 87g.

- [x] **busi-p4-smtp-send-extern** [landed 2026-06-10, commit 4ebd4e393] —
      Native `smtpSend` extern via the opt-in `smtp-plugin` (dependency-free
      RFC 5321 client: EHLO→STARTTLS→AUTH LOGIN→MAIL/RCPT/DATA→QUIT). Removes
      the relay requirement for standalone installs. 6 e2e tests.

### P1 — new busi-side bugs (2026-06-09)

- [x] **busi-p1-string-indexof** — `String.indexOf` not found for certain
      arg types (IntV char code); 2-arg form `indexOf(str, fromIndex)` missing.
      Fix: add `IntV` branch in `dispatchString1`; add 2-arg forms in
      `dispatch2` and `dispatchString`. Same for `lastIndexOf`.

- [x] **busi-p1-string-split-regex** — `String.split` wrapped separator in
      `Pattern.quote` — regex escapes like `\\.` and `\\s+` did not work.
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

  201  call: no compilable target (closures/HOF — skip for now)
   54  unsupported: Term.Select
   26  unsupported: Lit.String
   17  undefined: name 'inner'
    4  undefined: name '_VNODES_PER_NODE'
    3  unsupported: Term.Function
    2  unsupported: Lit.Null
    2  unsupported: stmt Defn.Def

Run `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` to track progress.
Each slice: one VmCompiler change + tests + bench A/B, never ship a non-win.

- [x] **jit-completeness-p2-term-select** — Compile `obj.field` access
      (`Term.Select` outside match). Requires meta lookup for field type; emit
      `GETFI` (int) or `GETFR` (ref) using existing field-info infrastructure.
      Expected: 54 → 0 for pure field-access cases. Skip method calls
      (`.head`, `.size` etc.) — bail as before.

- [x] **jit-completeness-p3-inner-def** — Compile functions that contain
      local `def inner(...)` bodies (`undefined: name 'inner'`, 17 misses).
      Strategy: treat inner defs as closures over params — compile the outer
      function only if `inner` has no free variables beyond outer params.

- [x] **jit-completeness-p4-defn-def** — Handle `stmt Defn.Def` in
      `compileStmt` (2 misses). A local def inside a block; same as p3 but in
      stmt position.

- [x] **jit-completeness-p5-lit-null** — `Lit.Null` (2 misses): emit CONST 0,
      set type TRef. Simple.

- [x] **jit-completeness-p6-lit-string** — `Lit.String` intermediate + LOADS/EQREF/NEREF opcodes.
- [x] **jit-completeness-p7-string-meta** — `String.length/isEmpty/nonEmpty` via JitRuntime meta + GETFI StringV.

## JIT universal coverage (new focus)

Spec: [`specs/jit-universal-coverage.md`](specs/jit-universal-coverage.md).
Goal: make JIT work for **all** real programs, not just benchmarks.
All three engines (SscVm, Javac bytecode, ASM bytecode) reach a unified
compilable subset.  Stages worked sequentially.

**Miss profile after Stage 2.1 (2026-06-05, 718 total disabled):**
```
345  [javac] UnknownShape       — falls through classifier; mostly HOF calls + bool-sibling gap
300  [vm] Other                 — VmCompiler raw-string bails (not yet migrated to typed reasons)
 32  [javac] NonExtractPattern
 14  [javac] Compound
  9  [javac] BoolBody           — bool body too complex even for walkBool fallback
  7  [javac] PatternGuard
  6  [javac] TryCatch
  2  [javac] VarargParam
  2  [javac] UsingParams
  1  [asm] TryCatch
```
Root-cause analysis of the 345 UnknownShape:
- `jitCompatibleSibling` still excludes bool-returning fns → callers of bool fns bail
- `walkBool` only handles `ApplyInfix`; misses `Lit.Boolean`, `Term.Name` (bool local),
  `Term.If`, `Term.Apply` on a bool-returning sibling, `!` unary
- HOF calls (passing/receiving fn values) — Stage 3 territory
- `walkForBailCliffs` doesn't detect HOF patterns → they all land in UnknownShape

- [x] **jit-uc-stage1-partial** — Unified per-engine `JitMissStats` with `JitBailReason`
      typed vocab; `JitBailReason.scala` extracted; Javac + ASM record misses.
      (CLI `ssc check-jit-coverage` deferred to after HOF slice.)

- [x] **jit-uc-stage2-1** — Bool body wrap: both backends emit `return (boolExpr)?1L:0L`
      instead of bailing; `JitResult.resultIsBool` unwraps to `BoolV` at call site.

- [x] **jit-uc-stage2-1b** — Bool sibling gap: remove `!isBoolReturning` gate from
      `jitCompatibleSibling` so bool-returning fns can be co-emitted; extend
      `walkBool` in both backends to handle `Lit.Boolean`, `!`, `Term.Name` (bool
      local/param → `!= 0L`), `Term.If`, `Term.Apply` (bool-returning sibling call →
      `call() != 0L`).  Extend `walkForBailCliffs` to report `HofCall` when
      `Term.Apply` target is a param name (not a global fn), turning most UnknownShape
      into a named category.  Target: UnknownShape < 100.

- [x] **jit-uc-stage2-2** — Ref+Ref 2-param dispatch (`ObjObjToLong/Double/Object` interfaces).
- [x] **jit-uc-stage2-3** — ASM ref-match guard parity (port `walkArmAsIfBranch`).
- [x] **jit-uc-stage2-4** — `Pat.Lit` arm in match (literal patterns).
- [x] **jit-uc-stage2-5** — Free-name → top-level `FunV` call (non-HOF case).

### Bench findings (2026-06-05, from `asm-jit-parity` worktree, post-2.4 main build)

Verified empirically via `./bench.sh`. New regression-guard corpus cases added:
`bench/corpus/bool-predicate.ssc`, `bench/corpus/literal-match.ssc`,
`bench/corpus/mutual-recursion.ssc`.

- [x] **jit-uc-finding-asm-bool-parity** — Fixed by the combined effect of stage 2.3
      (ASM guarded ref-match parity) + void `Term.If` in `emitStatAsVoid` /
      `walkStatAsVoid` (this commit): `workload()` in `mutual-recursion.ssc` uses
      `if isEven(i) then sum = sum + 1L` inside a while loop; that void Term.If was
      not emitable on either backend, so `workload()` bailed. Now both Javac and ASM
      compile `workload()`. Re-bench: `./bench.sh mutual-recursion`.

- [x] **jit-uc-finding-litmatch-not-firing** — Root cause was `.toLong` in
      `sum = sum + classify(i).toLong` blocking `workload()` JIT compilation.
      Fix: `.toLong`/`.toInt` emit as identity (Int=Long in ScalaScript);
      `.toDouble` emits L2D. Both backends. `n % 5 match` with ApplyInfix
      scrutinee was already supported via `walkLong` for the scrutinee.

- [x] **jit-uc-stage3-1** — `Value.FunV` as JIT-visible ref operand in `JitGlobals`.
- [x] **jit-uc-stage3-2** — SscVm `CALLREF` opcode + monomorphic IC.
- [x] **jit-uc-stage3-3** — Lambda / closure compilation (capturing + non-capturing).
- [x] **jit-uc-stage3-4** — IC hit-rate validation (`SSC_JIT_IC_STATS=1`).
- [x] **jit-uc-stage3-5** — Bytecode JIT HOF emission (Javac + ASM `INVOKEINTERFACE` to `RefCallable`).

- [x] **jit-uc-stage4** — Arity 3–4 ceiling lift (code-generated dispatch interfaces).

- [x] **jit-uc-stage5-1** — Mixed Long+Double arms auto-promotion (already handled: `bodyHasDoubleLit` classifies any fn with a Double literal as Double; `walkDouble` auto-widens Int/Long literals; no corpus `MixedReturnType` misses).
- [x] **jit-uc-stage5-2** — `var` in pure bodies: add `Term.Assign` to `walkBlockStmts` (Javac) and delegate non-final stats to `emitStatAsVoid` in `emitBlockStmts` (ASM).
- [x] **jit-uc-stage5-3** — `try/catch` in bodies (JVM try block + tree-walker fallback).
- [x] **jit-uc-stage5-4** — `Pat.Alternative` / `@`-binding pattern support.
- [x] **jit-uc-stage5-5** — Non-`Term.Name` match scrutinee (auto-hoist to local).

### Stage 6 — Post-merge long tail (new queue)

Baseline (post-stage-5, 2026-06-05, 734 total disabled):
```
 300  [vm] Other            — VmCompiler: HOF/ref-return/complex (unmigrated vocab)
 294  [javac] UnknownShape  — remaining HOF + complex closure shapes
  48  [javac] LambdaValue   — non-trivial Term.Function captures
  37  [javac] Compound      — multiple simultaneous bail reasons
  27  [javac] NonExtractPattern — tuple / typed patterns in match arms
   8  [javac] PatternGuard  — `if` guards in match arms
   7  [javac] NonAdtScrutinee — complex scrutinee remaining after 5.5
   7  [javac] BoolBody      — bool bodies too complex for walkBool
```
Each item: one commit + bench A/B. Run `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` to track.

- [x] **jit-uc-stage6-bench-baseline** — Bench 2026-06-05 post-merge: bool-predicate
      4.37→0.004ms, literal-match 3.51→0.004ms, mutual-recursion ssc/asm at parity (~1.2ms).
      Remaining HOF gaps: either-chain 3.46ms, hof-pipeline 2.79ms, option-chain 2.98ms,
      range-sum 3.57ms, typeclass-fold 2.99ms (all ~0.001–0.020ms on jvm).

- [x] **jit-uc-stage6-asm-mutual-recursion** — Fix ASM JIT regression on
      `mutual-recursion`: `ssc-asm` 20.8 ms → 1.22 ms (parity with Javac 1.20 ms).
      Root cause: `Lit.Boolean` missing from `walkLong` caused bool-returning functions
      to fall into `walkBool` fallback which generated COMPUTE_FRAMES-incompatible dead
      labels. Also fixed dead `GOTO Lend` in `walkBool(Term.If)` when thenp always jumps.

- [x] **jit-uc-stage6-pattern-guard** — Guards in match sub-expressions (val RHS,
      if-branch etc.) now compile. `walkMatchExpr` in both backends adds `hasAnyGuard`
      if-chain path via `walkArmAsIfBranch`/`emitArmBodyGuarded`. Remaining 8 PatternGuard
      misses have complex guard conditions (`walkBool` can't compile them) — see
      `jit-uc-stage6-bool-body-ext`.

- [x] **jit-uc-stage6-bool-body-ext** — Added `walkLong` fallback to `walkBool`
      in both backends. Enables bool-returning match expressions and complex guards
      where `walkBool` fails but `walkLong` succeeds (Long != 0 = true).
      New test: `isZero(n): Boolean = n match { 0 => false; _ => true }` compiles.

- [x] **jit-uc-stage6-nonextract-tuple** — `Pat.Tuple` in Javac + ASM backends;
      JitLint accepts Var/Wildcard sub-patterns; 27 NonExtractPattern misses eliminated.

- [x] **jit-uc-stage6-vm-retref** — RETREF=49 opcode; SscVm TLS slot; VmCompiler
      unifyRet(TRef) allowed; JitRuntime wrapRef(); 18 vm-retref misses eliminated.

- [x] **jit-uc-stage6-unknownshape-hof-analysis** — HofMethodCall + RefChainCall bail
      reasons added; UnknownShape 295→240; stage-7 plan in specs/jit-universal-coverage.md §9.

## JIT universal coverage — Stage 7

Spec: [`specs/jit-universal-coverage.md §9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06): 734 disabled, 240 UnknownShape, 55 RefChainCall, 70 Compound.
Current after bucket split (2026-06-06): 731 disabled, 238 UnknownShape,
70 Compound, 33 QualifiedRefCall, 22 RefChainObjectCall, 0 RefChainCall.
Current after HOF method slice (2026-06-06): 731 disabled, 238 UnknownShape,
70 Compound, 33 QualifiedRefCall, 22 RefChainObjectCall; warmed HOF benches
are now `option-chain=0.002ms`, `either-chain=0.002ms`,
`hof-pipeline≈0.001ms`, `range-sum≈0.001ms`
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

- [x] **jit-uc-stage7-refchain** — Ref-val propagation low-risk subset landed:
      Javac + ASM co-emit ref-returning sibling calls, bind immutable ref locals
      as `Object`, and inline numeric reads through `JitRefDispatch`
      (`getOrElseLong`, `sizeLong`, `headLong`). Regression:
      `val r = parse(n); r.getOrElse(7)` JITs on both backends. Verified by
      `JitLintTest -z stage7-refchain`, `SscVmTest -z stage7-refchain`, and
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1416 tests green).
      Result: total disabled 734→731; aggregate `RefChainCall` stayed 55.
      Detail trace showed the remaining bucket is broader than this subset
      (`Parser.string`, `Free.Pure`, `BigInt.pow`, `map(...).mkString`, effect
      calls, object-returning `Map.getOrElse`). See spec §9 Stage 7.1.

- [x] **jit-uc-stage7-refchain-bucket-split** — Split the remaining broad
      `RefChainCall` bucket before adding object/String/generic ref-returning
      dispatch interfaces. Added `QualifiedRefCall` for module/companion/native
      simple receivers and `RefChainObjectCall` for computed object/String/generic
      chains; `JitPredicates` now tracks immutable local `val` names so
      numeric local/direct ref reads stay in `RefChainCall`. Verified by
      `JitLintTest -z stage7-refchain-bucket-split`, full `JitLintTest`, and
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1419 tests green).
      Result: total disabled stayed 731, `RefChainCall` 55→0,
      `QualifiedRefCall=33`, `RefChainObjectCall=22`. See spec §9 Stage 7.2.

- [x] **jit-uc-stage7-hof-method** — Monomorphic IC for HOF method dispatch:
      `.map(x => …)`, `.flatMap(x => …)`, `.filter(x => …)`, `.foldLeft(z)((a,b) => …)`.
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
      remains a separate generic/given-dispatch follow-up. See spec §9
      Stage 7.3.

- [x] **jit-uc-stage7-typeclass-fold** — Classified the remaining
      `typeclass-fold` HOF workload as active context-bound typeclass dispatch
      instead of standard receiver method dispatch. Added
      `TypeclassUsingDispatch` for `summon[...]` and method selection on
      `using` params, plus a warmed `typeclass-fold` JMH target. Verified by
      `JitLintTest -z stage7-typeclass-fold`, `interpreterBench/compile`,
      quick JMH (`0.010 +/- 0.008ms/op`), and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1429 tests green).
      Result: generic/given dispatch is now a named follow-up; do not fold it
      into the monomorphic Option/Either/List/Range path. See spec §9
      Stage 7.4.

- [x] **jit-uc-stage7-refchain-object-dispatch** — Implemented the low-risk
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
      `NumericObjectMethodCall=8`. See spec §9 Stage 7.5.

- [x] **jit-uc-stage7-unknownshape-tagging** — Added classifier-only
      `walkForBailCliffs` buckets for ref-like infix ops, string interpolation,
      type applications, for-comprehensions, `new` object construction,
      expression-callee HOF apply shapes, and direct non-param global/constructor
      calls. Verified by focused `JitLintTest` filters and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1441 tests green).
      Result: `UnknownShape` narrowed `238 -> 20`, meeting the `<100` target.
      See spec §9 Stage 7.6.

- [x] **jit-uc-stage7-numeric-object-dispatch** — Implemented the dedicated
      BigInt/Decimal numeric-object helper path. Javac + ASM now compile
      `BigInt(...)` / `Decimal(...)` constructor-result object methods
      (`abs`, `negate`, `pow`, `gcd`, `toDecimal`, `setScale`, `toBigInt`) as
      `LongToObject` through `JitRefDispatch`, with receiver guards preserving
      the generic `mkString` / `Map.getOrElse` ref-chain object fallback.
      Verified by focused numeric/object-dispatch tests and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1443 tests green).
      Result: total disabled `733 -> 717`, `Compound 178 -> 170`, no
      `NumericObjectMethodCall` misses in the runtime profile. See spec §9
      Stage 7.7.

## JIT universal coverage — Stage 8

Status (2026-06-07): mostly done. 1474 tests green. Bench wins:

| Bench | Before | ssc Javac | ssc-asm | JVM |
|---|---|---|---|---|
| map-ops | 3.16ms | 0.027ms (117×) | 0.026ms (113×) | 0.021ms ✓ |
| string-split | 14.5ms | 0.235ms (62×) | 0.170ms (84×) | 0.089ms |
| typeclass-fold | 2.97ms | 2.38ms (1.25×) | 2.18ms (1.36×) | 0.005ms |

Spec: [`specs/jit-universal-coverage.md §9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06, post-stage7): 717 disabled, 20 UnknownShape,
170 Compound (`DirectGlobalOrCtorCall=148`, `ApplyInfixRefOp=19`,
`InterpolatedString=14`). Bench wins from stage 7 verified — either-chain,
hof-pipeline, option-chain, range-sum all <0.03 ms/op. Remaining buckets
do not show on bench corpus but block real-program JIT coverage.
Each item: one commit + bench A/B (or test A/B), never ship a non-win.

- [x] **jit-uc-stage8-direct-global-ctor** — Codegen done via
      `callGlobalLongAny` / `callGlobalRefAny` in JitGlobals: 1-arg ref,
      2/3-arg mixed ref+long (including callees with `using` clauses) now
      dispatch through `interp.invoke` (Javac+ASM). Classifier still reports
      144 DirectGlobalOrCtorCall — most are now false positives; refining
      `isKnownDirectJitCallee` requires runtime introspection (separate slice).

- [x] **jit-uc-stage8-apply-infix-ref** — String + Long/ref concat (Javac+ASM);
      BigInt/Decimal infix arithmetic (+/-/*/Div/Mod) via JitRefDispatch helpers
      (Javac+ASM); BigInt/Decimal comparison ops (<,<=,>,>=) (Javac+ASM);
      List/Map `++` collection concat via collectionConcat (Javac+ASM);
      ref ==/!= via Objects.equals (Javac+ASM).

- [x] **jit-uc-stage8-string-interp** — Javac+ASM: `s"..."` (Term.Interpolate
      prefix "s") lowers to `new Value.StringV(part + arg + ...)`; each arg
      via walkLong (numeric) or walkRef + Value.show. f-, md-, html-, css-
      prefixes still go through tree-walker.

- [x] **jit-uc-stage8-unknownshape-tail** — Added 5 new bail reasons
      (ThrowExpression, TupleConstruction, EtaExpansion, ExplicitReturn,
      NewAnonymousClass) + classifier wiring; corpus 20 UnknownShape unchanged
      (those shapes don't appear in tests); next agent debugging real code sees
      the right bucket. 3 focused tests; 1452 tests green.

### Stage-8 bench regressions (carryover from stage-6)

Three bench workloads remained slow through stages 6–7 because each needs a
distinct codegen path, not a classifier extension. Baseline (2026-06-06,
`./bench.sh`): `typeclass-fold` ssc 2.97 / ssc-asm 3.01 / jvm 0.004 ms/op;
`map-ops` ssc 3.16 / ssc-asm 3.91 / jvm 0.020 ms/op; `string-split` ssc 14.5 /
jvm 0.088 ms/op. Each item: one commit + bench A/B.

- [~] **jit-uc-stage8-typeclass-fold** — Partial (1.36× win, 2.97ms → 2.18ms).
      Codegen via `callGlobalLong1Ref` + `looksLongValue` fix: `workload()`
      JIT-compiles, the while-loop overhead removed. `combineAll` itself still
      tree-walked (uses `summon[T]`). Full win needs compile-time `summon[T]`
      specialization (monomorphic IC for given dispatch) — separate stage-9
      slice.

- [x] **jit-uc-stage8-map-ops** — Full bench-paritet with JVM on both backends
      (3.16ms → 0.027ms ssc Javac, 0.026ms ssc-asm vs JVM 0.021ms). Required
      changes: `Map[K,V](...)` ApplyType in walkRef + isRefValRhs; ref-typed
      Defn.Var/Term.Assign in walkBlockStmts + walkStatAsVoid + emitStatAsVoid;
      JitRefDispatch.mapUpdatedRef + mapGetOrElseLong.

- [x] **jit-uc-stage8-string-split** — Full bench-paritet with JVM on both
      backends (14.5ms → 0.235ms ssc Javac, 0.170ms ssc-asm vs JVM 0.089ms).
      Required: `String.split` via stringSplitRef; no-paren `.trim`/`.toUpperCase`
      Term.Select; `.toInt`/`.toLong` on ref fallback to emitRefChainLong;
      OpStringTrimToInt specialized op for `s => s.trim.toInt`-shape lambdas
      in JitHofDispatch + JitHofShape.

### Stage-8 residual bail buckets (gap analysis 2026-06-06)

After comparing the post-stage-7.7 miss profile against SPRINT, these
categories have no implementation task yet. Each item: one commit + miss-profile
A/B (or test A/B); never ship a non-win.

- [x] **jit-uc-stage8-vm-bail-migration** — Migrated 46 `bail(...)` sites in
      `VmCompiler` to typed `JitBailReason`; added 6 VM-specific cases
      (VmCallShape/VmFieldShape/VmUnsupportedTerm/VmEmptyBlock/VmNonBoolCond/
      VmUndefinedName) + reused 9 generic ones. Result: `[vm] Other` 290 → 32,
      new readable buckets dominated by `[vm] FreeNameUnresolvable=225`
      (HOF/closure call targets). 1443 tests green.

- [~] **jit-uc-stage8-qualified-ref-call** — Partial: `Math.max/min/abs`
      (Long) and `Math.sqrt/pow/floor/ceil/log/log10/exp/abs/sin/cos/tan/atan2`
      (Double) inline to `INVOKESTATIC java/lang/Math` in both backends.
      `.max(b)`/`.min(b)`/`.abs` on Long receivers also covered. Remaining:
      generic module/companion resolution for non-Math qualified calls
      (separate slice).

- [x] **jit-uc-stage8-nonextract-pattern-residual** — Classifier split:
      added TypedPattern + NestedTuplePattern + AlternativeWithBindings cases.
      Corpus 19 NonExtractPattern stayed (sub-Pat.Extract inside tuples — separate
      codegen slice). 3 focused classifier tests; 1447 tests green.

- [x] **jit-uc-stage8-pattern-guard-complex** — Long-fallback for match-guards:
      Javac `guardBoolExpr` + ASM `emitGuardBool` try `walkBool` first then
      `walkLong != 0L`. Targeted test exercises `Circle(r) if (r % 2)` style
      guards. Corpus profile unchanged (6 residual PatternGuard are Compound
      with other reasons), but new shapes now JIT. 1444 tests green.

## JIT universal coverage — Stage 9 (post-monomorphic follow-ups)

Stage 9 reopened two items previously parked as spec non-goals "for this sprint."
All current slices landed; remaining open items move to BACKLOG/CHANGELOG when
specific follow-ups are scoped.

---

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).

---

## Rust backend (new target)

Spec: [`specs/rust-backend.md`](specs/rust-backend.md). New AOT target —
emits a Cargo crate (`Cargo.toml` + `src/runtime/` + `src/generated/`) that
`cargo build` compiles to a self-contained binary. Phases R.1–R.6
(skeleton → core IR → intrinsics MVP → effects → http parity → polish).
Each task: one commit, baseline + acceptance recorded, never ship a
half-implemented phase under a flag. New backend module under
`runtime/backend/rust/`, plugin loaded via `META-INF/services` like every
other backend; no privileged hook in core.

**Coordination.** R.1 is the foundation — every later task `dependsOn` it.
Until R.1 lands, do not claim R.2+ from the queue. R.6 sub-tasks are
independent of each other once R.5 is in.

### Phase R.1 — Skeleton

R.1.3 hello-emit is split into four sequential sub-slices below.
Each one is a single commit with its own golden fixture so the next
slice has a verified base to extend. The cumulative result equals the
original `rust-backend-r1-hello-emit` description (Cargo.toml + main.rs
+ runtime/mod.rs + value.rs + generated/<module>.rs).



### Phase R.2 — Core IR coverage

Depends on R.1 complete. Each item: one commit, golden snapshots updated,
A/B vs the interpreter row.



### Phase R.3 — Intrinsics MVP

Depends on R.2. Capability additions: `FileSystem`, `Crypto`, `Markup`
(string-string xml only). Per-module Cargo dependency walk now becomes
load-bearing: the emitted `Cargo.toml` lists exactly the crates the
program reaches.

---

## Rust backend — benchmark coverage (spec: specs/rust-backend-bench-coverage.md)

16 of 22 bench corpus workloads return `n/a`.  Tasks below are ordered
by quick-win impact; P0 alone unlocks 7 benchmarks with tiny changes.

### P0 — quick wins (XS, each ≤ 100 lines)

- [x] **rust-bench-p0-to-numeric** — Add `.toLong` / `.toInt` /
      `.toDouble` / `.toFloat` conversions.  In `renderTerm`,
      recognise `Term.Select(expr, Term.Name("toLong"|"toInt"|...))` and
      lower to `(expr) as i64` / `as i32` / `as f64`.  Also lower
      `Term.ApplyInfix(lhs, "+", rhs)` where one operand is a `String`
      and the other is numeric to `format!("{}{}", lhs, rhs)` (fixes
      `string-concat`).  Acceptance: `string-concat.ssc`,
      `literal-match.ssc` green on `scripts/bench wall rust`.
      Spec: `specs/rust-backend-bench-coverage.md` §Gap A + C.

- [x] **rust-bench-p0-hello-bench** — Fix `hello-world` bench harness.
      In `bench/run.sc`'s injected `main.rs`, when `workload()` returns
      `Unit`, emit `generated::ssc_program::workload(); let r = 0i64;`
      instead of `let r = generated::ssc_program::workload();` so
      `std::hint::black_box(r)` receives an `i64`.  Acceptance:
      `hello-world.ssc` green on `scripts/bench wall rust`.
      Spec: §Gap B.

### P1 — collection method chaining (S–M)

- [x] **rust-bench-p1-vec-methods** — Add `.map(f)`, `.filter(f)`,
      `.foldLeft(z)(f)`, `.foreach(f)`, `.collect()` (as
      `.collect::<Vec<_>>()`) on Vec types in `renderTerm`.  Pattern:
      `Term.Select(qual, Term.Name("map"|"filter"|...))` + following
      `Term.Apply` with the lambda arg.  Acceptance: `list-fold.ssc`,
      `hof-pipeline.ssc` green.  Spec: §Gap D + G.

- [x] **rust-bench-p1-string-methods** — Add `String.split(sep)` →
      `s.split(sep).map(|p| p.to_string()).collect::<Vec<String>>()`,
      `.trim()` → `.trim().to_string()`, `.toInt` on String → 
      `.parse::<i32>().unwrap_or(0)`.  Acceptance: `string-split.ssc`
      green.  Spec: §Gap D.

### P2 — types + patterns (M)

- [x] **rust-bench-p2-sealed-trait-adt** — Recognise `sealed trait T`
      + `case class C extends T` pattern: collect both forms in a single
      ADT scan and lower to a Rust `pub enum T { C { … }, … }` just as
      the existing Scala 3 `enum` lowering does.  Acceptance:
      `pattern-match-heavy.ssc` green (requires `foreach` from P1 too).
      Spec: §Gap F.

- [x] **rust-bench-p2-tuple-types** — Map `Type.Tuple(elems)` to Rust
      tuple `(T1, T2, …)` in `mapType`; add `Lit.Tuple(elems)` / 
      `Term.Tuple(elems)` emit in `renderTerm`.  Lower the `++`
      concat operator on two tuple literals to a flat tuple.  Acceptance:
      `tuple-monoid.ssc` green.  Spec: §Gap H.

- [x] **rust-bench-p2-option-type** — Map `Option[T]` to `Option<T>`;
      add `.flatMap`, `.map`, `.getOrElse` methods on Option; `Some(x)`
      constructor → `Some(x)`, `None` → `None`.  Acceptance:
      `option-chain.ssc` green.  Spec: §Gap E.

### P3 — additional types (M–L)

- [x] **rust-bench-p3-hashmap-type** — Map `Map[K, V]` to
      `std::collections::HashMap<K, V>` (dep-free); add `.updated(k, v)`
      → `{ let mut m2 = m.clone(); m2.insert(k, v); m2 }`, `.getOrElse`
      → `.get(&k).copied().unwrap_or(default)`.  Acceptance:
      `map-ops.ssc` green.  Spec: §Gap E.

- [x] **rust-bench-p3-either-type** — Map `Either[L, R]` to a generated
      `pub enum Either<L, R> { Left(L), Right(R) }` emitted once per
      crate when reached; add `.map`, `.flatMap`, `.fold` methods.
      Acceptance: `either-chain.ssc` green.  Spec: §Gap E.

- [x] **rust-bench-p3-range-until** — Lower `(lo until hi)` and
      `(lo to hi)` to a `(lo..hi)` / `(lo..=hi)` Rust range; chain
      `.map` / `.foldLeft` via iterator adapters.  Acceptance:
      `range-sum.ssc` green.  Spec: §Gap E + D.

### Phase R.4 — Effects (algebraic effects + handlers)

Depends on R.2 closures and R.3 (for the runtime preamble layout). Free
monad in `Value::Computation(Box<Computation<Value>>)`. Capability adds
`AlgebraicEffects`. Multi-shot continuations panic with a clearly-labelled
runtime error; tracked as R.6 follow-up.


- [x] **rust-backend-r4-perform-handle-resume-lowering** — Implemented
      via tagless-final traits (not free-monad): Logger (effect-pure bench),
      Stream/VecStream (effect-stream bench), State/StateHandler (R.4.4),
      Random/RandomHandler LCG (R.4.4). R.4.1 supplies the free-monad
      runtime template for future CPS effects. 131 tests pass.

### Phase R.5 — Runtime parity (std.http server)

Depends on R.4 (handler bodies are effectful). Capability adds
`HttpServer`. Per-module walk pulls `tokio` + `hyper` + `http-body-util` +
`bytes` only when an `std.http.*` intrinsic is reached; programs without
HTTP stay dep-free.


- [x] **rust-backend-r5-http-serve-route** — `serve(port)` + `route(method, path, handler)`
      via `hyper::server::conn::http1::Builder` + `service_fn`; tokio+hyper deps
      pulled only when HTTP is reached; `src/runtime/http.rs` emitted conditionally.
      Landed 2026-06-08 (commit `0b3d179f0`). 7 tests in RustGenR5Test.

### Phase R.6 — Parity polish (independent tasks)

Each item is independent and stays parked until a real conformance test
or example demands it. Order below is priority for triage when claiming.

- [x] **rust-backend-r6-monomorphisation-pass** — Already implemented by design.
      The Rust backend emits `i64`, `bool`, `f64` directly for all numeric/boolean
      operations — no `Value` boxing in generated code. The `Value` enum in `value.rs`
      exists only for the `_show` helper. Every generated `pub fn` uses primitives
      throughout: no boxing overhead on hot paths. Closed 2026-06-09.

- [x] **rust-backend-r6-typeclasses** — `Feature.TypeClasses`: `given X: T with { defs }`
      emits a Rust unit struct XGiven + inherent impl; instance injected as topVal
      `let x = XGiven;`. `obj.method(args)` dispatch added to applyNonListCtor.
      Acceptance: bench/corpus/typeclass-monoid.ssc. 7 tests (190 total). Landed 2026-06-09.

- [x] **rust-backend-r6-streams** — `Feature.Streams` via synchronous iterator chains.
      Source.range(lo,hi)→(lo..=hi), Source.fromList(list)→list, .toList→.collect::<Vec<_>>().
      .map/.filter/.foldLeft already worked on ranges. No tokio/futures needed for these patterns.
      Acceptance: bench/corpus/streams-pipeline.ssc. 7 tests (183 total). Landed 2026-06-09.

- [x] **rust-backend-r6-multi-shot-continuations** — Resolved by the tagless-final
      approach (R.4.2–R.4.4): `VecStream` is inherently multi-shot (collects every
      `stream_emit` call); no Computation Clone needed. The original restriction was
      free-monad–specific and does not apply to tagless-final. Closed 2026-06-09.

- [x] **rust-backend-r6-tco** — `Feature.TailCallOptimization` via while-loop
      rewrite: `hasTailCallPath` detects self-calls in if/else + block tails;
      params get `mut`; tail calls → temp bindings + param reassignments; branches
      get `return`. Binary-recursive fns (e.g. fib) are NOT rewritten (safe).
      7 tests (147 total). Landed 2026-06-09.

- [x] **rust-backend-r6-websockets** — `Feature.WebSockets`: `wsRoute(path, handler:String->String)`,
      `wsServe(port)`, `wsConnectSync(url, handler:String->Unit)` via tokio-tungstenite 0.21.
      Conditional dep injection (tokio dedup when HTTP also present).
      src/runtime/ws.rs emitted on demand. 8 tests (155 total). Landed 2026-06-09.

- [x] **rust-backend-r6-auth** — `Feature.Auth`, intrinsics
      `hashPassword` (argon2id + random salt), `verifyPassword` (bool),
      `jwtSign` (HS256, payload as `sub` claim), `jwtVerify` (returns payload).
      argon2 0.5 + jsonwebtoken 9 + serde deps pulled only when any auth
      intrinsic is reached; hello-world stays dep-free. 9 tests (140 total).
      Landed 2026-06-09.

- [x] **rust-backend-r6-mcp** — `Feature.McpServer` via hand-rolled JSON-RPC 2.0
      over stdio (rmcp not stable enough). `mcpRegisterTool` + `mcpServe`;
      handles initialize/tools_list/tools_call. Only serde_json dep (no duplication
      when JSON intrinsics also present). 7 tests (162 total). Landed 2026-06-09.

- [x] **rust-backend-r6-markup-xslt** — Decision: XSLT excluded from Rust backend.
      No conformance test currently reaches it. `Feature.Xslt` is NOT in
      RustCapabilities.features — programs requiring XSLT are rejected at
      capability check time with a Diagnostic.Unsupported. Codec path (quick-xml
      XML read/write without XSLT) is out of scope for this sprint.
      Landed 2026-06-09 (this entry — no code change needed, capability rejection
      was already implicit).

---

## Backend-specific fenced blocks + platform-type ban (new)

**Motivation:** `.ssc` code must never reference `java.*`, `scala.*`, or any
other platform-specific type in a regular `scalascript` block — this should be
a compile error, not convention. The escape hatch for legitimate ad-hoc native
code is explicit backend-tagged fenced blocks: `scala`, `java`, `javascript`,
`rust`. The `java` fenced block tag is new (previously only `scala` existed for JVM).

Spec: [`specs/backend-specific-blocks.md`](specs/backend-specific-blocks.md)

### Phase 1 — parser

- [x] **backend-blocks-p1-parse** — Extend parser to recognise
      `scala`, `java`, `javascript`, `rust`, `wasm` fenced blocks as
      `BackendBlock(tag, source)` AST nodes. Existing `scalascript`
      blocks unchanged. Tests: mixed-block file parses correctly.
      Commit: `feat(parser): backend-specific fenced blocks`.
      ✓ Landed 2026-06-09 (745c963a): Lang.Java/Rust/Wasm + isNativeBackendBlock
      + isOpaqueExec wiring; 17 new tests, 906 core tests pass.

### Phase 2 — type-checker enforcement

- [x] **backend-blocks-p2-typecheck** — Banned-prefix check in
      type-checker: `java.*`, `javax.*`, `scala.*`, `sun.*`, `com.sun.*`
      in `scalascript` blocks → `E_PlatformType` compile error.
      Capability gate: `extern def` with no backend impl → `E_NoBackendImpl`.
      Test: `tests/conformance/backend-blocks-platform-type-ban.ssc`.
      Commit: `feat(typer): platform-type ban + capability gate`.
      ✓ Landed 2026-06-09 (33ca975): java/javax/sun/com.sun import ban in
      scalascript blocks; scala blocks exempt; 10 new tests, 916 total.

### Phase 3 — JVM backend emission

- [x] **backend-blocks-p3-jvm** — `JvmGen`: emit `scala` blocks verbatim
      after main module object; emit `java` blocks as separate `.java`
      source files via `//> using sources`. Test: `currentPid()` via
      `scala` block; `ssc run --target jvm` returns PID > 0.
      Commit: `feat(jvmgen): scala/java backend block emission`.
      ✓ Landed 2026-06-09 (5f8b969): scala blocks via isParseable, java blocks
      via javaBlocks buffer + //> using sources; 7 tests, 1490 backendInterpreter pass.

### Phase 4 — JS backend emission

- [x] **backend-blocks-p4-js** — `JsGen`: emit `javascript` blocks
      verbatim into the JS bundle after preamble. Test: `currentPid()`
      via `javascript` block; Node.js target returns `process.pid`.
      Commit: `feat(jsgen): javascript backend block emission`.
      ✓ Landed 2026-06-09 (462cb30): javascript verbatim in walkSection +
      genSection; html/css keep template path; 6 tests, 1504 total.

### Phase 5 — Rust backend emission

- [x] **backend-blocks-p5-rust** — `RustGen`: `rust` fence blocks emitted
      verbatim into `src/generated/<module>.rs` with numbered headers.
      5 tests in RustGenRustBlocksTest. Landed `26404e906`.

### Phase 6 — extend FFI annotations to `@rust` / `@wasm` + WASM boundary

- [x] **backend-blocks-p6-ffi-extend** — Add `RustInline`, `WasmInline`,
      `WasmExport`, `WasmImport` annotation AST nodes (alongside existing
      `JvmInline`, `JsInline`). Wire `@rust("...")` into `RustGen`.
      Wire `@wasmExport` / `@wasmImport` into WASM backend boundary emission
      (export/import table entries). Update `arch-ffi.md` to reference
      `backend-specific-blocks.md` for the full picture.
      Commit: `feat(ffi): @rust/@wasm annotations + WASM boundary annotations`.
      ✓ Landed 2026-06-09 (339cdff): @rust("expr") wired in RustCodeWalk.renderDef;
      extern defs without @rust skipped; arch-ffi.md updated; 4 tests, 151 Rust total.
      Note: @wasmExport/@wasmImport deferred (no WASM backend to wire into).

### Phase 7 — audit + flip ban to error

- [x] **backend-blocks-p7-audit** — Enable ban as warning, surface all
      violations in `runtime/std/`, `examples/`, `tests/conformance/`.
      Migrate violating `.ssc` files to `std.*` or backend blocks.
      Flip warning to error. Update `AGENTS.md` link (already added).
      Commit: `fix(typer): enable platform-type ban as hard error`.
      ✓ Landed 2026-06-09: audit found 1 violation (mcp-search-server.ssc had
      java.nio + scala.io in scalascript block); migrated to scala block.
      Ban already hard error from Phase 2. runtime/std/, conformance/ clean.

---

## std.fs / std.os / std.process — filesystem, OS & process abstraction (new)

**Motivation:** `.sc` tool scripts (`bench/run.sc`, `tests/e2e/spa-smoke.sc`)
use `java.io`/`java.nio` directly. `.ssc` user code must never reach for JVM
APIs. `runtime/std/fs.ssc` exists but is only 4 stubs with no backend plugin.
Goal: full cross-backend `fs-plugin` + `os-plugin` so `.ssc` code has zero
reason to touch platform APIs.

Three `.ssc` stdlib modules:
- `std.fs` — file-system operations (read/write/list/copy/move/delete/temp)
- `std.os` — OS environment (env vars, CLI args, cwd, paths, exit, platform info)
- `std.process` — process management (spawn, exec, stdin/stdout/stderr, wait, kill)

Spec to write first: `specs/std-fs-os.md`

### Phase 1 — spec + design

- [x] **std-fs-os-p1-spec** — Write `specs/std-fs-os.md`. Cover:

      **`std.fs`**: readFile, writeFile, appendFile, deleteFile, exists,
      isDir, isFile, mkdir, mkdirs, listDir, copyFile, moveFile,
      readBytes, writeBytes; `FsError` sealed trait (NotFound,
      PermissionDenied, NotSupported, IoError); `Feature.FileSystem` gate.

      **`std.os`**: env(key), envOrElse(key, default), args: List[String],
      exit(code), cwd, sep, pathJoin(parts*), pathDirname, pathBasename,
      pathExtname, pathResolve, pathIsAbsolute, tempDir, tempFile,
      platform: Platform (Jvm | NodeJs | Browser | Native),
      homedir, hostname.

      **`std.process`**: exec(cmd, args, opts) → ProcessResult
      (stdout, stderr, exitCode); spawn(cmd, args, opts) → Process
      (write to stdin, read stdout/stderr as streams, wait, kill);
      ProcessOptions (cwd, env, timeout); ProcessError sealed trait.
      Note: Browser target throws ProcessError.NotSupported for all ops.

      JS-Node vs JVM vs Rust vs browser-sandbox policy for each module.
      Commit: `spec: std-fs-os`.
      ✓ Landed 2026-06-09 (0757d27): 271-line spec, all 3 modules + 6 phases.

### Phase 2 — JVM backend (fs-plugin + os-plugin)

- [x] **std-fs-os-p2-jvm** — Create `runtime/std/fs-plugin/` with
      `FsPlugin.scala` + `FsIntrinsics.scala` (std.fs + std.os).
      Create `runtime/std/os-plugin/` with `OsPlugin.scala` +
      `OsIntrinsics.scala` (std.process via `ProcessBuilder`).
      JVM impl: `java.nio.file`, `System.getenv`, `ProcessBuilder`.
      Register both in `build.sbt`. Conformance tests:
      `tests/conformance/fs-*.ssc`, `tests/conformance/os-*.ssc`,
      `tests/conformance/process-*.ssc`. Commit: `feat(fs-plugin): JVM backend`.
      ✓ Landed 2026-06-09 (30134b8): fs-plugin (13 ops, 13 tests) + os-plugin
      (18 ops incl. exec, 14 tests). allPlugins registered.

### Phase 3 — JS/Node backend

- [x] **std-fs-os-p3-js** — Node.js preamble wiring `std.fs` → `node:fs`,
      `std.os` → `node:os` + `node:path`, `std.process` → `node:child_process`.
      Browser: `FsError.NotSupported` / `ProcessError.NotSupported` for
      fs/process ops; env returns `{}`, args returns `[]`, platform = Browser.
      Same conformance tests pass on Node target.
      Commit: `feat(fs-plugin): JS/Node backend`.
      ✓ Landed 2026-06-09 (d32bf9a): JsRuntimeFs.scala; 16 fs + 15 os + exec();
      lazy require; browser stubs; 21 tests.

### Phase 4 — Rust backend

- [x] **std-fs-os-p4-rust** — Full std.fs/std.os/std.process Rust lowering:
      12 fs helpers, 15 os helpers (env→Option, path*, platform=Native), ProcessResult+exec.
      All use pure std (no extra crates). 14 new tests (176 total). Landed 2026-06-09.

### Phase 5 — stdlib .ssc files + examples

- [x] **std-fs-os-p5-stdlib** — Add `runtime/std/os.ssc` and
      `runtime/std/process.ssc` alongside existing `fs.ssc`. Expand
      `fs.ssc` with new extern signatures. Add runnable examples:
      `examples/fs-roundtrip.ssc`, `examples/os-env.ssc`,
      `examples/process-exec.ssc`. Update `README.md` capabilities table.
      Commit: `feat(std): fs/os/process stdlib modules`.
      ✓ Landed 2026-06-09 (ee673a5): fs.ssc expanded (16 defs), os.ssc new,
      process.ssc new; 2 examples; README updated.

### Phase 6 — audit & boundary documentation

- [x] **std-fs-os-p6-cleanup** — Audit all `.ssc` files for `java.*`
      imports; migrate any found to `std.fs`/`std.os`/`std.process`.
      Note in `specs/std-fs-os.md` §"Scope": `.sc` Scala-CLI host
      scripts (bench/run.sc etc.) may use JVM APIs — that is intentional.
      Add one-liner to `AGENTS.md` §"Codebase architecture rules":
      "`.ssc` user code must never import `java.*` — use `std.fs`,
      `std.os`, `std.process` instead."
      Commit: `docs(std-fs-os): boundary rule in AGENTS.md + spec`.
      ✓ Landed 2026-06-09: audit done (covered by backend-blocks-p7);
      AGENTS.md already references specs/std-fs-os.md; specs/std-fs-os.md §6 scope note added.

---

## std.yaml — YAML parse / stringify (new)

**Motivation:** `.ssc` user code has no way to call `parseYaml(s)` or `toYaml(v)` today.
`SimpleYaml` already covers ~90% of real YAML (block/flow mappings+sequences, scalars,
quoted strings, comments, literal blocks) but only returns internal Java types — not
ScalaScript `Value`s.  A `yaml-plugin` + `std/yaml.ssc` closes this gap.

**Scope:**
- JVM: `SimpleYaml.load` → `Value` converter + plain-Scala YAML serializer (no snakeyaml needed).
- JS/Node: inline mini-parser + serializer in JsRuntimeYaml (subset matching SimpleYaml).
- Anchors/aliases, multi-document, YAML 1.2 tags: **out of scope** for this sprint.
- `yaml`/`yml` fenced blocks already produce `ContentValue` (content API) — Phase 4 wires
  them to ScalaScript-visible variables too.

Spec to write first: `specs/std-yaml.md`

### Phase 1 — spec

- [x] **yaml-p1-spec** — Write `specs/std-yaml.md`. Cover:

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
      ✓ Landed 2026-06-09 (ebb2a6e): specs/std-yaml.md, 146 lines.

### Phase 2 — JVM plugin

- [x] **yaml-p2-jvm** — Create `runtime/std/yaml-plugin/` with
      `YamlInterpreterPlugin.scala` + `YamlIntrinsics.scala`.

      `parseYaml(s)`: `SimpleYaml.load[Any](s)` → recursive converter returning
      `Value.MapV` / `Value.ListV` / `Value.StringV` / `Value.IntV` / `Value.DoubleV` /
      `Value.BoolV` / `Value.NullV` (tag names matching `YamlValue` sealed trait).

      `toYaml(v)`: pure-Scala serializer — walks `Value` tree, emits block-style YAML
      (mappings indented 2, sequences with `- ` prefix, strings quoted when needed).

      Register in `build.sbt`. Tests: round-trip `parseYaml(toYaml(v)) == v` for
      Map, List, nested, scalars, edge cases (empty string, null, bool).
      Commit: `feat(yaml-plugin): JVM backend`.
      ✓ Landed 2026-06-09 (67985dd): yaml-plugin, 28 tests, allPlugins registered.

### Phase 3 — JS/Node preamble

- [x] **yaml-p3-js** — Add `JsRuntimeYaml.scala` to `runtime/backend/js/`.

      `parseYaml(s)`: port `SimpleYaml` subset to JS (or inline a ~200-line
      pure-JS block/flow parser matching the JVM subset exactly).

      `toYaml(v)`: JS serializer — same block-style output as JVM.

      Wire into `JsGen.generateRuntime` unconditionally.
      Tests: text-shape assertions that `parseYaml` and `toYaml` appear in preamble;
      round-trip conformance test against Node.js runner.
      Commit: `feat(jsgen): std.yaml JS/Node preamble`.
      ✓ Landed 2026-06-09 (2c169d7): JsRuntimeYaml.scala, 13 preamble tests.

### Phase 4 — stdlib `.ssc` + examples + fenced-block wiring

- [x] **yaml-p4-stdlib** — Add `runtime/std/yaml.ssc` with `YamlValue` sealed trait
      declarations and `parseYaml`/`toYaml` extern defs.

      Wire `yaml`/`yml` fenced blocks: bind block content as a `YamlValue` variable
      named `<sectionId>_yaml` (or `<sectionId>.yaml`) in the surrounding ScalaScript
      scope — same pattern as `html`/`css` string blocks bind `<sectionId>.html`.

      Add example: `examples/yaml-parse.ssc` — parse a YAML string, navigate it,
      round-trip through `toYaml`.  Update `README.md` capabilities table.
      Commit: `feat(std): std.yaml stdlib module + fenced-block wiring`.
      ✓ Landed 2026-06-09 (7ac9857): yaml.ssc, fenced-block binding, 6 plugin tests, example.

---

## std.pdf — PDF → Markdown reader (new)

> ✓ **DONE (verified 2026-06-11) — implemented under a different API than this
> queue spec'd.** `pdfToMarkdown` + `pdfPageCount` (read) ship in the JVM
> `pdf-plugin` (`PdfIntrinsics`, Apache PDFBox) and the `runtime/std/pdf-gen.ssc`
> stdlib module (`package: std.pdf`), alongside `htmlToPdfBase64` (generate). The
> API takes a base64 `String` (not `bytes: List[Int]`) and the stdlib file is
> `pdf-gen.ssc` (not `pdf.ssc`). `pdf-plugin` is registered in `build.sbt`
> (`pdfPlugin` / `PluginSpec("pdf", …)`) and staged into `bin/`. All of pdf-p1..p5
> below are therefore stale — the read+generate capability exists end-to-end (JVM/
> interp; JS/Rust per the agent that landed it). NOTE: the example
> `examples/pdf-extract-demo.ssc` is missing its ```` ```scalascript ```` fence so
> `ssc run` silently does nothing — tracked under the "bare-example" finding, not a
> blocker for the capability itself.

**Motivation:** `.ssc` user code has no way to read a PDF today. There is no
cross-backend PDF parser, so this must be a per-backend `pdf-plugin` (intrinsics
go to `runtime/std/`, never core — AGENTS.md). v1 reads a PDF **as Markdown** so
it plugs straight into the existing `std.content` / markup pipeline (matches the
project's "Markdown as first-class syntax" principle).

**Scope (v1):**
- Input is `List[Int]` bytes (same byte representation as `std.fs.readBytes`), so
  it composes: `pdfToMarkdown(fs.readBytes(path))`.
- `pdfToMarkdown(bytes): String` — extract text as Markdown. Heuristic structure:
  page breaks → `## Page N`, paragraphs separated by blank lines. Font-size-based
  heading detection is a **follow-up**, not v1.
- `pdfPageCount(bytes): Int`.
- Out of scope: rendering, images, forms, tables, encrypted PDFs, OCR of scanned
  (image-only) PDFs.
- Backend rollout: **JVM → JS → Rust**, phased, one push per green phase.

Spec to write first: `specs/std-pdf.md`

### Phase 1 — spec

- [x] **pdf-p1-spec** — Write `specs/std-pdf.md`. Cover:

      **`std.pdf`**: `pdfToMarkdown(bytes: List[Int]): String`;
      `pdfPageCount(bytes: List[Int]): Int`.
      Markdown mapping rules: `## Page N` per page, paragraphs split on blank
      lines, page-internal text joined with single newlines collapsed to spaces
      where PDFBox over-segments. Define behaviour on: empty PDF, 0 pages,
      encrypted PDF (return error/empty + documented), non-PDF bytes (error).
      Backend policy table (JVM PDFBox / JS pdf.js-or-Node / Browser / Rust pdf-extract).
      Note the heading-detection follow-up explicitly as out of scope.
      Commit: `spec: std-pdf`.

### Phase 2 — JVM plugin

- [x] **pdf-p2-jvm** — Create `runtime/std/pdf-plugin/` with
      `PdfInterpreterPlugin.scala` + `PdfIntrinsics.scala` (mirror `crypto-plugin`).

      Add **Apache PDFBox** (`org.apache.pdfbox:pdfbox`, Apache-2.0) as the plugin's
      only new dependency. `pdfToMarkdown`: load bytes via `PDDocument.load`, run
      `PDFTextStripper` page-by-page (`setStartPage`/`setEndPage`), prefix each with
      `## Page N`, normalise whitespace into Markdown paragraphs. `pdfPageCount`:
      `doc.getNumberOfPages`. Convert `List[Int]` arg → `Array[Byte]`.

      Register in `build.sbt`: `lazy val pdfPlugin`, `PluginSpec("pdf", …)`, root
      aggregate, CLI plugin list, `% Test` on `backendInterpreter`. SPI service file.
      Tests: small fixture PDF in `src/test/resources` → assert page count + that
      extracted Markdown contains expected words and `## Page 1`.
      Commit: `feat(pdf-plugin): JVM backend (PDFBox)`.

### Phase 3 — JS/Node preamble

- [x] **pdf-p3-js** — Add `JsRuntimePdf.scala` to `runtime/backend/js/` (mirror the
      crypto `_sha256` preamble pattern in `JsRuntimePart2b.scala`).

      Node path: lazy-`require('pdf-parse')` (or `pdfjs-dist`) to extract text +
      page count; emit the same `## Page N` Markdown shape as JVM. Browser path:
      `pdfjs-dist` via the documented async boundary (note: PDF.js is async — decide
      in the spec whether the browser variant is supported in v1 or deferred).
      Wire into `JsGen.generateRuntime`. Tests: preamble text-shape assertions +
      Node round-trip on the same fixture PDF as JVM.
      Commit: `feat(jsgen): std.pdf JS/Node preamble`.

### Phase 4 — stdlib `.ssc` + example

- [x] **pdf-p4-stdlib** — Add `runtime/std/pdf.ssc` (manifest `package: std.pdf`,
      exports `pdfToMarkdown`, `pdfPageCount`) with the two `extern def`s.

      Add example `examples/pdf-read.ssc`: `fs.readBytes` a sample PDF →
      `pdfToMarkdown` → print, and parse the result through `std.content` to show the
      Markdown round-trips. Update `README.md` capabilities table + `docs/user-guide.md`.
      Commit: `feat(std): std.pdf stdlib module + example`.

### Phase 5 — Rust backend (follow-up)

- [x] **pdf-p5-rust** — Rust codegen for `pdfToMarkdown` / `pdfPageCount` via the
      `pdf-extract` (or `lopdf`) crate. Defer until JVM+JS are green; gate behind the
      Rust intrinsics MVP. Commit: `feat(rust): std.pdf intrinsics`.

---

## std.ui — busi UI feedback (2026-06-09)

From busi's `docs/scalascript-ui-proposals.md` (e9cfa34), grounded in 17 real web
screens; refined with busi in rozum (`scalascript` room). busi frontends are web React
**and** macOS/iOS SwiftUI, so all surface stays backend-agnostic (`TkNode` level), never
web-CSS-coupled. Start order: **P1 + P4 parallel → P2 → P3 (deferred, in BACKLOG)**.

### P1 — typed JSON in `fetch*` (spec: `specs/std-ui-typed-json.md`)

Highest ROI; removes 13 duplicated `*Q` escapers + substring decoders across screens.
Pure stdlib, helps web + native. Builds on existing `json-plugin`
(`jsonParse`/`jsonStringify`/`lookup`) — no second parser.

- [x] **ui-typed-json-p1-spec** — `specs/std-ui-typed-json.md`. ✓ Landed 2026-06-09.

- [x] **ui-typed-json-p2-core** — ✓ Landed 2026-06-09 (JVM + JS). Navigable `JsonValue` +
      total accessors (get/at/asString/asInt/asDouble/asBool/asList/isNull/opt*/getOrElse +
      **asDecimal/optDecimal** lossless money) + structured builders (jStr/jNum/jBool/jDecimal/
      jField/jObj/jArr). JVM: `navJson` InstanceV in json-plugin (additive — existing
      jsonParse/jsonRead/lookup untouched). JS: `_jsonValueTotal`+`jsonValue` in JsRuntimePart2b
      + `jsonValue`→RuntimeCall (decode works in emit-spa/browser). `runtime/std/json.ssc`,
      `examples/ui-typed-json.ssc`. Tests: 7 plugin + JsonTypedExampleTest (e2e JVM) +
      JsonValueNodeTest (real Node parity). busi can migrate 13 `*Q` (encode) AND
      onbStr/extractStr (decode) now — both backends.

- [x] **ui-typed-json-p3-fetch** — ✓ Landed 2026-06-09. Shipped as thin `.ssc` sugar
      (`runtime/std/ui/fetch-json.ssc`), not new per-backend primitives: `fetchJsonValue`
      (GET → navigable `() => JsonValue` over fetchUrlSignal+jsonValue, reactive through the
      string signal) + `fetchJsonAction` (POST structured body over fetchAction+computedSignal).
      Note: built-in `fetchJsonSignal(modelType)` already exists (typed-model decode) — the
      navigable path is named `fetchJsonValue`. `headers` required (`.ssc` default-param /
      emptyHeaders eval gotcha). `examples/ui-fetch-json.ssc` + JsonFetchSugarTest (e2e).

- [x] **ui-typed-json-p4-stdlib** — ✓ Landed 2026-06-09 (with p2-core): `runtime/std/json.ssc`
      surface, `examples/ui-typed-json.ssc`, README capabilities row. (No fenced-block wiring
      needed — JSON is consumed as a value, not a block language.)

### P2 — token-aware styled `TkNode` + status primitives (spec: `specs/std-ui-styled-tknode.md`)

Kills inline-CSS soup; makes dark/mobile + native theming work. **No CSS-string builder**
— `Style` fields are `Theme`-token refs resolved by `lower` per target (agreed with busi).
Start after P1.

- [x] **ui-styled-p1-spec** — `specs/std-ui-styled-tknode.md`. ✓ Landed 2026-06-09.

- [x] **ui-styled-p0-theme** — ✓ Landed 2026-06-09. Additive `theme.ssc`: `SpacingScale`
      gains `smd` (default 12, mobile 24, dark 12); `TypographyScale` gains `caption`
      (default 12px, mobile 14px). md/body/heading unchanged. ThemeTokensTest + StdUiSmokeTest
      green. (The `SpaceToken.Smd` / `FontToken.Caption` *enums* are added with the `Style`
      descriptor in `ui-styled-p2-nodes`; this slice is the theme-data half.)

- [x] **ui-styled-p2-nodes + p3-lower** — ✓ Landed 2026-06-09 (done together — nodes
      need lowering to render). `nodes.ssc`: `TagNode/PillNode/KpiCardNode/TabBarNode`(+`Tab`)
      `/BoxNode` + `StyledNode(props: Map[String,String])`. `display.ssc`: `tag/pill/kpiCard/
      tabBar/styled` + `badge` default → status set. `layout.ssc`: `box(maxWidth/width/height)`.
      `lower.ssc`: token resolution (`_colorOf/_spaceTokOf/_radiusOf/_fontOf/_lenOf/_styleCss`),
      caption font for badge/tag/pill, text-decoration/cursor baked into tabBar, dark
      re-resolution. `examples/frontend/std-ui/styled-primitives.ssc` + StyledPrimitivesTest
      (emits default+dark, asserts token-resolved styles + no baked hex). Smoke+theme green.
      **API deviation (interpreter limits):** tokens are **strings** (cross-module enum
      matching is broken) and `Style` is a **`Map[String,String]`** (partial named-arg
      case-class defaults mis-bind). See spec §2 note.

- [x] **ui-styled-p4-example** — ✓ Landed 2026-06-09 with the above:
      `examples/frontend/std-ui/styled-primitives.ssc` + README row. busi migration: replace
      `web/ui.ssc` helpers + inline-CSS cards/badges with `badge/tag/pill/kpiCard/tabBar` and
      `styled([...])` for custom chrome.

### P4 — UI runtime bugs (bug fixes — no spec)

- [x] **ui-bug-browser-columns** — ✓ Already fixed on main by `250e9c75e` (2026-06-04,
      "fix(js): define browser ui typed column helpers"): `_ssc_ui_fieldColumn/dateColumn/
      moneyColumn/statusColumn/linkColumn` all defined in `JsRuntimeSignals.scala` +
      registered in the browser stub list (`JsGen.scala`). busi's proposal P4 predates the
      fix. No action needed — busi updates to current ScalaScript.

- [x] **ui-bug-jobj-failloud** [landed 2026-06-10] — Nested `jObj(List(jField(... jObj(...))))`
      with a paren mismatch triggered a silent ScalaMeta `termParam` NPE / interpreter
      hang. Root cause: `parseScalaWithDiagnostic` matched only `Parsed.Success`/`Parsed.Error`
      but scalameta *throws* a raw `NullPointerException` on truncated inputs like `def f(` /
      `def f(using ` (and deep nesting can `StackOverflowError`). New `safeParse` wraps every
      scalameta parse attempt (Source / block-Term / `trySplitParse`), converting a thrown
      `NonFatal`/`StackOverflowError` into a synthesized located `Parsed.Error`. Parser now
      fails loudly with a diagnostic, never a crash/hang. 4 regression tests in
      `ParseErrorPositionTest`; core 920/920 green.

---

## PDF generation + MIME (busi invoice/email — spec: `specs/pdf-mime-generation.md`)

busi clarified in rozum (2026-06-09): the real need is **one drop-in function**
`htmlToPdfBase64(html): String` → base64 PDF, matching their existing relay contract
(`POST {html} → {pdf_base64}`) so zero busi rewiring. HTML/CSS subset is pinned to the
invoice template (table layout, A4, basic typography/borders/background, `@media print`;
**no** JS/images/webfonts/grid/float; flexbox optional). **JVM/interpreter only — no JS
backend.** PDF half is the priority; MIME/SMTP are a later slice. Spec already pinned
(commit 342cf5162). Start order: **pdf-p1 → pdf-p2 → (mime-p3, smtp later)**.

- [x] **pdfgen-p1-engine** [landed 2026-06-10] — `htmlToPdfBase64(html): String` as a JVM
      intrinsic in the new opt-in `pdf-plugin` (OpenHTMLtoPDF 1.0.10 + jsoup 1.17.2 for
      lenient HTML parse → W3C DOM). Registered in `build.sbt` (module + packagePlugin list
      + PluginSpec). `PdfPluginTest`: invoice HTML → `%PDF-` + ≥1 page (PDFBox parse-back) +
      grid/float degrades (no throw). 4/4 green.

- [x] **pdfgen-p2-stdlib** [landed 2026-06-10] — `runtime/std/pdf-gen.ssc` (`package std.pdf`,
      `extern def htmlToPdfBase64`) + `examples/invoice-pdf.ssc` + documented HTML/CSS subset
      in spec + README. JVM-only noted.

- [x] **mime-p3-build** [landed 2026-06-10] — `buildMimeMessage(from,to,subject,htmlBody,
      attachments)` → RFC 5322 text in a new dependency-free `mime-plugin` (`std.mime`).
      0 attachments → `text/html` email; 1+ → `multipart/mixed` (base64 HTML part + base64
      attachments, RFC 2047 subject). `MimePluginTest` (4) round-trips through the Jakarta
      Mail / Angus reference parser (test-scope dep): headers, multipart count, attachment
      filenames + decoded content. Example `examples/invoice-email.ssc` (PDF → email).
      4/4 green. **Note:** plugin-arg unwrapping is selective — scalars come as raw
      `String`, collections stay as `Value.ListV` (matched accordingly).

> `busi-p4-smtp-send-extern` (above) is the relay-free SMTP DATA sender — the final slice
> that pairs with `mime-p3` + `pdfgen-p1` for a fully relay-free invoice-email path
> (`specs/smtp-send.md`). Sequence it after mime-p3.

---

## busi df-6 — Postgres out-of-the-box + LISTEN/NOTIFY (rozum seq-115, 2026-06-11)

busi's data-model-foundation df-6 landed end-to-end against REAL Postgres using
ScalaScript's first-class `backend/postgres` (scalascript-client-postgres,
org.postgresql 42.7.3 + HikariCP; `backend/sql` routes `postgres:` → `jdbc:postgresql:`
via DriverManager). Two follow-ups on our side surfaced; busi is unblocked for
single-node without them.

- [x] **pg-jar-installbin** (medium) — DONE 2026-06-11. Added
      `org.postgresql:postgresql:42.7.3` + `com.zaxxer:HikariCP:5.1.0` to
      `backendSqlRuntime` (`backend/sql`) alongside H2/SQLite — those are the runtime
      deps `installBin` stages into `bin/lib/jars/`. Verified: `installBin` stages
      `postgresql-42.7.3.jar` + `HikariCP-5.1.0.jar`; `DriverManager` resolves
      `jdbc:postgresql:` from the staged classpath (connection error, not "No suitable
      driver"). sql suites green (91+26+4).

- [x] **pg-listen-notify-extern** (low-pri) — DONE 2026-06-11. Added
      `Db.pgListen(db, channel)` / `Db.unlisten(db, channel)` /
      `Db.getNotifications(db[, timeoutMs])` in `SqlIntrinsics`, operating on the
      connection `ConnectionRegistry` caches per db name. Each notification is a
      `Map { channel, payload, pid }`; PG-only (clear error otherwise);
      injection-safe quoted channel. `BuiltinsRuntime` now collects all `Db.*`
      natives generically. Example `examples/pg-listen-notify.ssc` (live PG).
      Tests lock registration + PG-guard; receive path verified by busi vs real PG.
