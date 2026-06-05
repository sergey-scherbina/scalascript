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
current-section, backend exposure, native-client parity, and reverse Markdown
rendering:
[`specs/markdown-content-lookup-plaintext.md`](specs/markdown-content-lookup-plaintext.md),
[`specs/markdown-content-metadata.md`](specs/markdown-content-metadata.md),
[`specs/markdown-content-current-section.md`](specs/markdown-content-current-section.md),
and
[`specs/markdown-content-backend-exposure.md`](specs/markdown-content-backend-exposure.md),
and
[`specs/markdown-content-native-client-parity.md`](specs/markdown-content-native-client-parity.md),
and
[`specs/markdown-content-to-markdown.md`](specs/markdown-content-to-markdown.md).
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

- [ ] **jit-uc-stage2-1b** — Bool sibling gap: remove `!isBoolReturning` gate from
      `jitCompatibleSibling` so bool-returning fns can be co-emitted; extend
      `walkBool` in both backends to handle `Lit.Boolean`, `!`, `Term.Name` (bool
      local/param → `!= 0L`), `Term.If`, `Term.Apply` (bool-returning sibling call →
      `call() != 0L`).  Extend `walkForBailCliffs` to report `HofCall` when
      `Term.Apply` target is a param name (not a global fn), turning most UnknownShape
      into a named category.  Target: UnknownShape < 100.

- [ ] **jit-uc-stage2-2** — Ref+Ref 2-param dispatch (`ObjObjToLong/Double/Object` interfaces).
- [ ] **jit-uc-stage2-3** — ASM ref-match guard parity (port `walkArmAsIfBranch`).
- [ ] **jit-uc-stage2-4** — `Pat.Lit` arm in match (literal patterns).
- [ ] **jit-uc-stage2-5** — Free-name → top-level `FunV` call (non-HOF case).

- [ ] **jit-uc-stage3-1** — `Value.FunV` as JIT-visible ref operand in `JitGlobals`.
- [ ] **jit-uc-stage3-2** — SscVm `CALLREF` opcode + monomorphic IC.
- [ ] **jit-uc-stage3-3** — Lambda / closure compilation (capturing + non-capturing).
- [ ] **jit-uc-stage3-4** — IC hit-rate validation (`SSC_JIT_IC_STATS=1`).
- [ ] **jit-uc-stage3-5** — Bytecode JIT HOF emission (Javac + ASM `INVOKEINTERFACE` to `RefCallable`).

- [ ] **jit-uc-stage4** — Arity 3–4 ceiling lift (code-generated dispatch interfaces).

- [ ] **jit-uc-stage5-1** — Mixed Long+Double arms auto-promotion.
- [ ] **jit-uc-stage5-2** — `var` in pure bodies (extend `walkLocalSlotCtx`).
- [ ] **jit-uc-stage5-3** — `try/catch` in bodies (JVM try block + tree-walker fallback).
- [ ] **jit-uc-stage5-4** — `Pat.Alternative` / `@`-binding pattern support.
- [ ] **jit-uc-stage5-5** — Non-`Term.Name` match scrutinee (auto-hoist to local).

---

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).
