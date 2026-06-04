# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Language surface — Markdown frontend (next)

- [ ] **markdown-frontend-mvp** — Implement
      [`specs/markdown-content-introspection.md`](specs/markdown-content-introspection.md)
      Phase 1: parse Markdown-hosted content into a rendering-grade
      `DocumentContent` snapshot and lower `contentView(...)` to the existing
      `std/ui` toolkit so a page/screen authored in Markdown emits through a
      frontend backend without hand-written UI construction. Treat the full
      `std/content` metadata/introspection API as the follow-up slice, not the
      first milestone.

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [ ] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. ASM smoke after
      the Javac slice stayed at `recursiveEval` 2.106 ms/op and
      `recursiveEvalMixed` 1.951 ms/op with
      `SSC_JIT_BACKEND=asm BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'`.
      Do this after the active dirty ASM worktree lands to avoid conflict.
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).
