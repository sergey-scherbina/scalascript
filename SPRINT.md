# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Language Surface - Markdown Content (next)

Broad spec exists:
[`specs/markdown-content-introspection.md`](specs/markdown-content-introspection.md).
Focused slice spec already exists only for the landed lookup/plain-text work:
[`specs/markdown-content-lookup-plaintext.md`](specs/markdown-content-lookup-plaintext.md).
For the next slices, write and commit the focused spec first, then implement.

- [ ] **markdown-content-current-section-spec** - Write
      `specs/markdown-content-current-section.md`. Decide how parser/runtime
      records the calling code block's enclosing section, what happens at
      document top-level, and how sibling blocks are represented.

- [ ] **markdown-content-current-section** - Implement interpreter
      `contentCurrentSection(): SectionContent` using the committed spec.
      Cover nested sections, top-level code blocks, and stable behavior when a
      section contains both Markdown prose and executable code.

- [ ] **markdown-content-backend-exposure-spec** - Write
      `specs/markdown-content-backend-exposure.md` for JS/JVM native-context
      exposure of the landed interpreter helpers: `contentDocument`,
      `contentData`, `contentSection`, `contentBlock`, `contentPlainText`, and
      `contentMetadata`, and eventually `contentCurrentSection`.

- [ ] **markdown-content-backend-exposure** - Implement JS/JVM exposure for the
      landed `std/content` helper set, then un-pend or replace
      `tests/conformance/content-introspection.ssc` so INT/JS/JVM agree on
      observable output.

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

---

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).
