# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

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

Spec: [`specs/jit-universal-coverage.md §9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06, post-stage7): 717 disabled, 20 UnknownShape,
170 Compound (`DirectGlobalOrCtorCall=148`, `ApplyInfixRefOp=19`,
`InterpolatedString=14`). Bench wins from stage 7 verified — either-chain,
hof-pipeline, option-chain, range-sum all <0.03 ms/op. Remaining buckets
do not show on bench corpus but block real-program JIT coverage.
Each item: one commit + bench A/B (or test A/B), never ship a non-win.

- [ ] **jit-uc-stage8-direct-global-ctor** — Compile `Term.Apply` on a free name
      that resolves to a top-level FunV or constructor, even when the callee is
      not a sibling (currently bails as `DirectGlobalOrCtorCall`). 148 Compound
      misses today. Strategy: extend `walkRef`/`walkLong` to look up the callee
      in `JitGlobals`, emit a static dispatch (FunV → bytecode handle if
      compilable, ctor → `InstanceV.create`-style path). Baseline:
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` → record
      `DirectGlobalOrCtorCall` before and after.

- [ ] **jit-uc-stage8-apply-infix-ref** — `Term.ApplyInfix` where one operand is
      ref-typed (e.g. `BigInt + n`, `bigDec * x`, `path / "sub"`). 19 Compound
      misses. Strategy: route ref-operand infixes through the stage-7.7 numeric
      object dispatch path (`JitRefDispatch`) for arithmetic operators, and
      `String + …` to the existing concat path. Baseline: record `ApplyInfixRefOp`
      count before and after.

- [x] **jit-uc-stage8-string-interp** — Javac `walkRef` lowers `s"..."`
      (Term.Interpolate prefix "s") to `new Value.StringV(part + arg + ...)`;
      each arg via walkLong (numeric) or walkRef + Value.show. ASM deferred.
      f-, md-, html-, css- prefixes still go through tree-walker. 1449 tests
      green; +2 focused JIT tests.

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

- [ ] **jit-uc-stage8-typeclass-fold** — Compile the resolved typeclass
      dispatch path: `xs.foldLeft(summon[M[A]].empty)(summon[M[A]].combine)`.
      Strategy: detect `summon[T]` at JIT time and inline the resolved given
      instance as a ref constant; route `.combine(a, b)` through a monomorphic
      method handle on the resolved instance (no GivenRuntime dispatch on each
      call). Replaces the current `TypeclassUsingDispatch` bail. Bench target:
      `typeclass-fold` <0.05 ms/op (≈100× win). Baseline:
      `scripts/bench interp typeclass-fold`.

- [ ] **jit-uc-stage8-map-ops** — Compile the immutable-`Map` mutation pattern:
      `var m: Map[K,V] = …; m = m.updated(k, v); m.getOrElse(k, d)`. Strategy:
      hoist the Map to a ref slot (already supported as ref-typed var?); route
      `.updated` and `.getOrElse` through `JitRefDispatch` `mapUpdatedRef` /
      `mapGetOrElseLong`. The hot pattern is identical to a List loop but on a
      different ref shape. Bench target: `map-ops` <0.5 ms/op (≈6× win,
      Map churn floor is structural — allocation-dominated). Baseline:
      `scripts/bench interp map-ops`.

- [ ] **jit-uc-stage8-string-split** — Compile the
      `s.split(d).map(f).foldLeft(z)(g)` pipeline on `String → Array[String]`.
      Strategy: emit `String.split` as a native call returning `Array[String]`
      (existing intrinsic), then route the array `.map(...).foldLeft(...)` chain
      through the stage-7.3 HOF dispatch with an `Array`-receiver shape.
      Per-element `s.trim.toInt` should compile via existing String/Int paths
      once the ref-chain receiver is the array element. Bench target:
      `string-split` <0.5 ms/op (≈30× win). Baseline:
      `scripts/bench interp string-split`.

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

- [ ] **jit-uc-stage8-qualified-ref-call** — Compile `Module.fn(x)`,
      `Math.max(a, b)`, `Foo.bar(...)` — currently 15 `QualifiedRefCall` misses.
      Strategy: in `walkLong` / `walkRef`, when callee is `Term.Select(modName,
      methodName)` and `modName` resolves to a module/companion in
      `interp.globals`, look up the resolved `FunV` and emit a direct dispatch
      (same path stage-7 uses for sibling calls). Baseline: record
      `QualifiedRefCall` count before/after.

- [x] **jit-uc-stage8-nonextract-pattern-residual** — Classifier split:
      added TypedPattern + NestedTuplePattern + AlternativeWithBindings cases.
      Corpus 19 NonExtractPattern stayed (sub-Pat.Extract inside tuples — separate
      codegen slice). 3 focused classifier tests; 1447 tests green.

- [x] **jit-uc-stage8-pattern-guard-complex** — Long-fallback for match-guards:
      Javac `guardBoolExpr` + ASM `emitGuardBool` try `walkBool` first then
      `walkLong != 0L`. Targeted test exercises `Circle(r) if (r % 2)` style
      guards. Corpus profile unchanged (6 residual PatternGuard are Compound
      with other reasons), but new shapes now JIT. 1444 tests green.

- [ ] **jit-uc-stage8-refchain-object-residual** — Address the 4 residual
      `RefChainObjectCall` misses left over after stage-7.5. Strategy: dump the
      4 specific call shapes; extend `JitRefDispatch` with whichever
      method-on-ref helpers they need (likely `.toString` on InstanceV,
      `.headOption`, or similar). Each helper is one method + one test.
      Baseline: `RefChainObjectCall` 4 → 0.

## JIT universal coverage — Stage 9 (post-monomorphic follow-ups)

Stage 9 reopens two items previously parked as spec non-goals "for this sprint."
They become tractable after stage-7's monomorphic IC infrastructure landed.

- [ ] **jit-uc-stage9-poly-ic** — Polymorphic inline cache for HOF dispatch
      (multiple receiver shapes at one call site). Stage-7.3 implemented a
      monomorphic IC; this extends it to a small N-way IC (typical N=2..4) for
      sites that see more than one shape during warm-up. Strategy: extend
      `JitGlobals.callRefCache` entries from `(FunV, CompiledFn|null)` pairs to
      small arrays, with linear scan + LRU eviction; instrument hit-rate via
      `SSC_JIT_IC_STATS=1`. Bench target: workloads with megamorphic call sites
      regress less than today. Baseline: design a polyIC microbench
      (e.g. fold over a `List[Shape]` with 3 shapes) before implementing.

- [ ] **jit-uc-stage9-lambda-value-solo** — Compile standalone `Term.Function`
      lambdas not adjacent to a HOF method call (5 solo `LambdaValue` misses).
      Strategy: when a `Term.Function` is bound to a local val or passed to a
      non-stage-7-known method, emit a `FunV` constant via the existing
      `LOADFV` opcode and let CALLREF dispatch handle invocation. Same
      mechanism as stage-3.3 closure compilation but at the use site.
      Baseline: `LambdaValue` solo 5 → < 2.

---

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).
