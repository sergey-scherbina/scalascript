# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

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
