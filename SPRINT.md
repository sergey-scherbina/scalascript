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
current-section, backend exposure, and native-client parity:
[`specs/markdown-content-lookup-plaintext.md`](specs/markdown-content-lookup-plaintext.md),
[`specs/markdown-content-metadata.md`](specs/markdown-content-metadata.md),
[`specs/markdown-content-current-section.md`](specs/markdown-content-current-section.md),
and
[`specs/markdown-content-backend-exposure.md`](specs/markdown-content-backend-exposure.md),
and
[`specs/markdown-content-native-client-parity.md`](specs/markdown-content-native-client-parity.md).
For the next slices, write and commit the focused spec first, then implement.

- [ ] **markdown-content-native-client-parity** - Implement native-client
      parity after the spec lands. Add examples/tests showing the same
      Markdown-defined controls rendered through Swing, JavaFX, and SwiftUI
      frontend paths, with shared `contentData` / component metadata behavior.

- [ ] **markdown-content-to-markdown-spec** - Write
      `specs/markdown-content-to-markdown.md` for `contentToMarkdown(...)`.
      Define supported nodes, metadata round-trip rules, formatting stability,
      and explicit non-goals for exact source preservation.

- [ ] **markdown-content-to-markdown** - Implement `contentToMarkdown` after
      backend exposure is stable; cover document, section, block, and embedded
      data rendering with focused tests.

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
compilable subset.  Stages worked sequentially:

- [ ] **jit-uc-stage1** — Unified bail accounting: per-engine `JitMissStats`,
      `JitBailReason` typed vocab, `ssc check-jit-coverage` CLI command.

- [ ] **jit-uc-stage2-1** — Bool body wrap (`isBoolReturning` → emit `if … then 1L else 0L`).
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
