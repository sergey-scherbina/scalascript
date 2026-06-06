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

- [ ] **jit-uc-stage7-hof-method** — Monomorphic IC for HOF method dispatch:
      `.map(x => …)`, `.flatMap(x => …)`, `.filter(x => …)`, `.foldLeft(z)((a,b) => …)`.
      Requires ref-val propagation (stage7-refchain) + existing CALLREF for the lambda.
      Target: bench HOF workloads (either-chain 3.46ms, hof-pipeline 2.79ms,
      option-chain 2.98ms, range-sum 3.57ms, typeclass-fold 2.99ms) toward <0.1ms.
      Baseline bench: `scripts/bench interp either-chain`.

- [ ] **jit-uc-stage7-refchain-object-dispatch** — Implement or further narrow the
      22 `RefChainObjectCall` misses from Stage 7.2. Scope is object/String/generic
      computed ref chains such as `BigInt(10).pow(n)`, `xs.map(...).mkString`,
      and object-returning `Map.getOrElse`; do not fold qualified module/native
      calls (`QualifiedRefCall=33`) into this implementation path without a
      separate dispatch design.

- [ ] **jit-uc-stage7-unknownshape-tagging** — Further `walkForBailCliffs` tagging to
      reduce UnknownShape 238→<100. Candidates: `Term.ApplyInfix` on ref operands
      (`ApplyInfixRefOp`), string interpolation (`InterpolatedString`), non-FunV callee
      chains. Not implementation code — produces an updated miss profile and extended
      bail-reason vocabulary. Run `SSC_JIT_STATS=1` before and after.

---

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).
