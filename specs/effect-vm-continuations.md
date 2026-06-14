# Effect performance: compiling effectful loops (delimited continuations in the VM)

Status: **in progress** — Phase 1 (one-shot tail-resume resolver) under implementation.
Owner: interp perf. Tracking: SPRINT.md, BACKLOG.md.

## Problem

`effectOneShot` is the #1 interpreter-bench outlier (~18 ms):

```scalascript
effect Bump:
  def tick(): Int
def loop(n: Int): Int ! Bump =
  var acc = 0
  var i = 0
  while i < n do
    acc = acc + Bump.tick()
    i = i + 1
  acc
handle(loop(5000)) {
  case Bump.tick(resume) => resume(1)
}
```

Re-profiled 2026-06-14: **72% leaf `EvalRuntime.evalCore`** — the loop body
(`acc = acc + Bump.tick(); i = i + 1`) tree-walks 5000× because the `perform`
forces the Free-monad trampoline (`Computation = Pure | Perform | FlatMap`), so the
loop can **never** reach the while-JIT (which the loop-val-inline family made fast for
pure loops). The remaining ~28% is the trampoline machinery (a `FlatMap` + reassoc per
iteration; the one-shot tail-resume *handler* alloc was already cut −39% in
`effect-oneshot-perf`). The bench measures real work (5000 sequential performs, an
i-dependent accumulation) — it must keep doing so; we may not fold the loop away.

## How effects work today (reference)

- A `effect Eff: def op(...)` declaration registers each op as a `NativeFnV` that returns
  `Perform(eff, op, args)` (`StatRuntime.scala:257`).
- `e1 + Eff.op()` threads the perform through a `FlatMap(Perform, v => e1 + v)`; a `while`
  body containing a perform yields `FlatMap(bodyComp, loopCont)` per iteration
  (`EvalRuntime` effectful-while trampoline, ~3890).
- `handle(body) { cases }` (`EffectsRuntime.evalHandle`) evaluates `body` to a Computation,
  then `handleInterp` walks the `FlatMap`/`Perform` tree, dispatching each handled op.
  A **one-shot tail-resume** arm `case Eff.op(a.., resume) => resume(simpleArgs)` is the
  common shape (`tailResumeFast`, `effect-oneshot-perf`).

## Phase 1 — one-shot tail-resume resolver (this slice)

**Insight.** A one-shot tail-resume arm `case Eff.op(a.., resume) => resume(rexpr)` (no
guard, single arm for that op, `rexpr` side-effect-free) means: *the value of
`Eff.op(callArgs)` is `rexpr` evaluated in the handler's env with `a.. := callArgs`.* So
we can resolve the op **at the perform site** to that value, instead of emitting a
`Perform`. Then `acc + Bump.tick()` evaluates to a plain `Pure` value, the loop body is
**pure**, and the effectful trampoline's all-pure fast path runs it as a native JVM loop
(no `FlatMap` per iteration). Semantically identical (the effect still "runs" 5000×, each
resolved through the handler) — this is the same equivalence `effect-oneshot-perf` already
exploits, applied one step earlier so the body becomes pure.

**Mechanism.**
- A thread-local **resolver stack** in `EffectsRuntime`: each frame maps `(eff, op)` →
  `(List[Value] => Value)` for the one-shot tail-resume ops a handler installs.
- `evalHandle`, when an arm is a clean one-shot tail-resume with a side-effect-free `rexpr`
  (reuse the `tailResumeFast` classification), pushes a resolver frame, evaluates the body,
  and pops it (try/finally). The resolver for `(eff, op)` binds the op-arg patterns to the
  call args in the handler env and evaluates `rexpr` → the value.
- The op `NativeFnV` (`StatRuntime`) checks the innermost resolver for `(eff, op)`: if found,
  returns `Pure(resolver(args))`; else `Perform(eff, op, args)` (unchanged propagation).

**Scope / safety.**
- Only clean one-shot tail-resume arms get a resolver: single arm per op, no guard, body is
  exactly `resume(simpleArgs)` with `simpleArgs` literals/names (so `rexpr` is pure — never
  performs a nested effect). Everything else (multi-shot, deep-handler `msg :: resume(())`,
  return clauses, guards) uses the unchanged `Perform`/`handleInterp` path.
- The resolver is keyed per `(eff, op)`, so an effect NOT handled here still `Perform`s and
  propagates to an outer handler; nested handles push/pop their own frames.
- `rexpr` is evaluated in the handler env (+ op-arg bindings) — captured per handle.

**MEASURED 2026-06-14 — Phase 1 alone is a NON-IMPROVEMENT (~0%); reverted.** The resolver was
implemented and verified correct (`Bump.tick()` resolves inline 5000×, result 5000, all effects
suites green). With it, the handled body IS pure and runs through the effectful-while all-pure
fast path. But `effectOneShot` measured **18.8 vs 19.3 ms (within noise)**: removing the
Free-monad trampoline does NOT help, because the dominant cost (the re-profiled 72% `evalCore`)
is the **per-iteration tree-walk of the body**, and the all-pure native loop tree-walks it
exactly the same way. **Conclusion: the win is NOT "skip the trampoline" — it is "compile the
body". Phase 1's resolver is necessary only as the substrate for Phase 2 (it gives Phase 2 a
runtime value for the effect op), so it ships TOGETHER with Phase 2, not alone.** The Phase-1
code was reverted to avoid dead weight on the hot effect-op path (a per-op TLS lookup with no
benefit). The TLS resolver design above stands; re-introduce it with Phase 2.

## Phase 2 — JIT the effectful loop body — ✅ DONE 2026-06-14 (effectOneShot ~18 → 1.67 ms, ~11×)

Phase 1 proved the body must be **compiled**, not just de-trampolined. Shipped:
1. **Resolver substrate** (Phase 1, re-added): `EffectsRuntime` TLS resolver stack + `evalHandle`
   installs it for clean one-shot tail-resume arms + the op `NativeFnV` (`StatRuntime`) returns
   `Pure(resolver(args))` when one is in scope.
2. **Bridge**: `JitGlobals.resolveEffectLong(eff, op): Long` reads the innermost resolver,
   returns its value; throws if absent.
3. **JIT lowering**: `JavacJitBackend.walkLong` lowers a 0-arg effect-op call `Eff.op()` whose
   `(eff,op)` has a **live resolver at compile time** (the JIT runs during the handle's body eval)
   to `resolveEffectLong("eff","op")` — so `acc + Bump.tick()` compiles and the while-JIT compiles
   the whole loop.
4. **Safety — no resolver-gating needed**: `tryWhileJit` writes its slots back ONLY on success and
   is try/catch-wrapped, so if the bridge throws at run time (the same `loop` later deep-handled —
   no resolver), the compiled loop discards its partial slot updates and bails to the trampoline →
   the effect is handled normally, no double execution. `EffectVmContinuationsTest` covers the JIT
   path, the deep-handler bail, op-arg binding, and multi-shot-untouched.

Residual ~1.67 ms (vs ~0.26 ms arithLoop) = the per-iteration bridge call (TLS lookup +
`interp.eval` of the resume expr). NB: we deliberately do NOT inline a constant resume value to
reach arithLoop parity — that would fold the effect away (gaming); consulting the handler each
iteration is the honest floor.

**Phase 2b — arg-carrying effect ops — ✅ DONE 2026-06-14 (effectReader ~9.8×).** Extended the
JIT lowering from 0-arg to `Eff.op(a)` / `Eff.op(a, b)` (numeric args): `JitGlobals
.resolveEffectLong1/2` pass the args to the resolver; `JavacJitBackend.walkLong` emits them for a
≤2-numeric-arg op with a live resolver. Also broadened `EffectsRuntime.isSimpleResumeArg` (the
resolver classifier) from literals/names to **pure arithmetic** so a `resume(k * 2)` arm resolves
(previously only `resume(1)`-style literals did). `effectReader` (`acc + Reader.ask(i)` × 5000,
`case Reader.ask(k,resume)=>resume(k*2)`) 26.7 → 2.72 ms. STILL OPEN: ref-return / ref-arg effect
ops (`resolveEffectRef` / `walkRef`); >2 args.

## Phase 3 — general delimited continuations (future)

Multi-shot effects + non-tail resumes need real continuations: a VM **suspend** that captures
the resumable VM state (stack + pc) at a `perform`, and the handler **resumes** it (once for
one-shot, repeatedly for multi-shot). This is the general solution but a major VM feature
(save/restore VM state); deferred until Phases 1–2 are exhausted.
