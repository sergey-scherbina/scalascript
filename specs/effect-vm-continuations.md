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

## Phase 2c — compile the resume expression in the resolver — ✅ DONE 2026-06-14 (effectReader 2.63 → 0.175 ms ~15×, effectOneShot 1.64 → 0.145 ms ~11×)

Re-measuring the full `InterpreterBench` landscape (2026-06-14) put **effectReader (2.63 ms)** and
**effectOneShot (1.64 ms)** as the top two outliers. After Phase 2/2b the loop already JITs and
calls the bridge each iteration; the residual is the resolver itself re-running `interp.eval` on
the resume expr **every perform**. The 1.0 ms `effectReader − effectOneShot` gap was exactly
`interp.eval(k * 2)` tree-walked 5000× (effectOneShot's `resume(1)` tree-walks a trivial `Lit`);
re-entering the megamorphic tree-walker from inside the JIT'd loop also defeats bridge inlining.

**Mechanism.** `EffectsRuntime.compileIntArith(t, argIdx)` compiles a single pure-**Int**-arith
resume expr — `Lit.Int/Long`, `Name(op-arg)`, `+` / `-` / `*`, unary `±` — into a direct
`Array[Long] => Long` closure (`argIdx` maps each op-arg name to its perform-arg slot). The
resolver builds a `Long[]` from the perform args and returns `Value.intV(closure(args))`. It is
**bit-identical** to `interp.eval` because `IntV op IntV ⇒ intV` is plain 64-bit `Long`
(`DispatchRuntime`). `compileIntArith` returns `null` (→ the unchanged `interp.eval` resolver) for
any shape NOT provably bit-exact: `/` / `%` (div-by-zero + Double-promotion semantics),
Double/conversions, free names, tuples. At runtime the fast resolver also falls back to
`interp.eval` if any op-arg isn't an `IntV` — so semantics are unconditionally preserved.

**Honest — not folded.** The effect still dispatches every iteration (bridge → resolver) and the
handler still computes the arithmetic; only the redundant per-perform tree-walk is removed — the
same principle as Phase 2 compiling the loop body. We do NOT inline the resume value into the loop
(that would drop the dispatch = gaming). Evidence the loop is not folded: effectReader's
**unfoldable** `i * 2` runs at ≈ effectOneShot's constant `resume(1)` (0.175 vs 0.145 ms) — a
folded constant loop would be ~10× faster (cf. `tupleMonoid`/`effectStream` at ~0.01 ms when a
loop folds); ~30 ns/iter is the genuine bridge + TLS-lookup dispatch floor. `loop(5000)` still
computes the exact sum 24 995 000 (`EffectVmContinuationsTest`). Tests +5
(nested/unary/subtraction, division fallback-to-`interp.eval`, 0-arg constant, at-scale sum).

STILL OPEN: ref-return / ref-arg effect ops (`resolveEffectRef` / `walkRef`); >2 args; the
residual ~30 ns/iter (the bridge call + `lookupResolver` TLS walk) — a slot-cached resolver could
shave the TLS walk but the dispatch floor remains.

## Phase 3 — general delimited continuations (multi-shot + non-tail)

Multi-shot effects + non-tail resumes need real continuations. The resolver trick (P1–P2c) only
covers a handler arm that is a single tail-position `resume(pure-expr)`; it cannot cover a handler
that calls `resume` zero or many times (`opts.flatMap(o => resume(o))`) or wraps it in a larger
expression (`msg :: resume(())`). Those already **work correctly today** via the Free-monad
trampoline (`handleInterp`, `EffectsRuntime`): the body is reified into a `Computation`
(`Pure | Perform | FlatMap`) tree, the continuation is the reified `FlatMap` closure
`f: Value => Computation`, and `resume(v)` re-runs `handleInterp(f(v))` — once for one-shot, once
per value for multi-shot. P3 is about making that **fast/compiled**, not correct.

### Profile finding (2026-06-14, `scripts/bench profile effectMultiShot`)

`effectMultiShot` (`4 × choose` over `List(1,2,3,4)` ⇒ 256 paths) is **0.93 ms — NOT an interp
outlier** (the slowest benches are the recursionFib family at 1.16–1.58 ms, at the Phase-C JIT
floor). It is **alloc-bound: 406 KB/op** (`gc.alloc.rate.norm`), and the JFR allocation breakdown
is **spread**, not dominated by one site: `Object[]`/`::` (List + arg arrays), `mutable.HashMap`
nodes + `FrameMap1` (per-call **env construction** in the re-evaluated continuation —
`EvalRuntime.eval:3082`, `evalApplyGeneral:4507`, `evalPlainApply:3060`), `Computation.Pure`/
`FlatMap` nodes, and the per-perform `resume` `NativeFnV` + continuation lambdas. **There is no
single wasteful allocation to remove** — the 406 KB is the aggregate cost of the tree-walking
interpreter **re-reifying + re-evaluating the continuation 256×**. So the only real lever is to
stop re-tree-walking the continuation, i.e. compile it. There is no tractable incremental
alloc-cut that meaningfully moves a non-outlier.

### Design options (for when an effect-heavy workload justifies the build)

1. **Compiled/CPS continuation segments.** Split an effectful body at its `perform` points into
   continuation segments and compile each segment once (to a Scala closure or via the existing
   javac-JIT), so `resume(v)` invokes a **compiled** segment instead of re-reifying the AST. This
   mirrors what the JVM (`emitCpsBlock`) and JS (`JsGenCpsCodegen`) backends already do for
   effectful functions — bring that to the interpreter. Multi-shot just calls the compiled segment
   N times; non-tail resumes return a concrete value the segment composes. Largest payoff,
   largest effort (an effect-aware JIT).
2. **Register-VM stack capture.** Run the body in `SscVm` and at a `perform` snapshot the VM frame
   stack + pc into a resumable handle; the handler resumes by restarting from the snapshot (a
   deep-copy of the frame stack per resume for multi-shot). Requires teaching `SscVm` to (a) host
   effect bodies and (b) save/restore/clone its state. True delimited continuations; large.
3. **Stopgap — cheaper reification.** Pool/reuse `FlatMap`/`Pure` nodes and avoid the per-call
   `HashMap` env where a `FrameMap` chain suffices. Modest, doesn't change the fundamental
   re-evaluation, and the env-allocation cut is a *general* interp win (not effect-specific) — if
   pursued, do it as a general eval-perf slice with its own A/B, not under this spec.

### Phase 3a — const collection-literal memoization (SHIPPED 2026-06-14)

The one **safe + tractable** piece of option 3, shipped as the first P3 slice. Deep-profiling the
`::`/`Object[]` allocation found the `choose(List(1,2,3,4))` argument was rebuilt on **every**
continuation re-run (~85× for the 4×4 search) — a *cacheable* allocation distinct from the
inherent flat-map result lists. `EvalRuntime.isPureConstExpr` + the `pureConstCache` gate now
memoise a constant **immutable** collection literal (`List(..)` / `Vector(..)` / `Seq(..)` with
all-pure-const args) by AST identity, the same mechanism already used for `Term.Tuple` /
`Term.ApplyInfix`. Sharing is safe (immutable value); the callee-name gate keeps non-collection
applies (`fib(n-1)`) off the cache path. **effectMultiShot 406 → 350 KB/op (−13.7% alloc),
~0.93 → ~0.78 ms.** Also a *general* win for any const collection literal in a hot loop. Guard:
`EffectVmContinuationsTest` "multi-shot over a cached const list yields all 256 paths" (256/2560 —
the shared cached list must not corrupt the enumeration). No regression on the Apply-heavy benches
(recursionFib family / arithLoop). This is **not** the compiled-continuation feature — it reduces
reification allocation; the per-resume AST re-walk + Free-monad rebuild remain (options 1/2).

### Phase 3b — stress bench + unapply-allocation cuts in the continuation re-eval path (SHIPPED 2026-06-14)

`effectMultiShot` (256 trivial paths) is partly per-op `Interpreter`-construction-bound, a poor
signal for the continuation cost. Added **`effectMultiShotDeep`** — a representative nondeterministic
search (5 levels × 5 options = 3125 paths, with interleaved per-step scoring `val sa = a*a; val b =
choose(..); val sb = b*b+sa; …`) where the per-resume continuation **re-evaluation** genuinely
dominates: **baseline 7.5 ms, 1.95 MB/op** (a real outlier). Deep-profiling its allocation
(`jfr ObjectAllocationSample`) found two avoidable **unapply** allocations in the re-eval path — a
`Some` + `Tuple4` per visit — from scalameta version-extractors: `BlockRuntime.step`'s
`case Defn.Val(_, pats, _, rhs)` (Tuple4 ~89 samples) and `EvalRuntime.fastPrimitiveValue`'s
`Term.ApplyInfix.After_4_6_0(lhs, op, _, ac)` (Tuple4 ~55 / Some ~60). Both converted to the
codebase's established **type-test + direct field access** (`dv.pats`/`dv.rhs`, `ai.lhs`/`ai.op`/
`ai.argClause`) — behaviour-identical, no per-visit `Some`/`Tuple4`. **effectMultiShotDeep
1.95 → 1.57 MB/op (−19.5% alloc)**; a *general* win for every `val`-binding + binary-op eval, not
just effects. Guard: `EffectVmContinuationsTest` "deep interleaved multi-shot (5×5) yields all 3125
paths" (3125/171875). 225 interp/effects tests green; no regression. Still **not** the
compiled-continuation feature — the AST re-walk itself remains (options 1/2).

### Phase 3c — CPU profile: the residual is the tree-walk, not allocation (DIAGNOSTIC 2026-06-14)

After P3a/P3b cut reification *allocation* (1.95 → 1.57 MB/op), a CPU leaf-frame profile of
`effectMultiShotDeep` (`jdk.ExecutionSample`, 156 samples — load-independent) settles the strategy:

| leaf frame | % |
|---|---|
| `EvalRuntime.evalCore` | **49 %** |
| + `eval` / `fastValue` / `fastPrimitiveValue` / `evalApplyGeneral` / `step` | **~63 % (the walk)** |
| `HashMap.foreachEntry` / `Node` (env copy) | ~8 % |
| `IdentityHashMap.get` (pureConstCache) | ~2 % |

**`effectMultiShotDeep` is CPU-bound on the megamorphic `evalCore` re-walk of the continuation**
(re-walked across all 3125 paths) — NOT allocation-bound. Per the project's repeatedly-reinforced
lesson (*alloc cuts don't move CPU-bound wall-clock*), **further P3a/P3b-style allocation cuts will
not move wall-clock here.** The only lever that reduces the `evalCore` re-walk is compiling the
continuation (options 1/2). P3a/P3b remain worthwhile as alloc/GC hygiene (and general
val/binop/const-collection wins) but the multi-shot wall-clock floor is now the tree-walk.

### Phase 3d — slice 1 of the compiled-continuation build: `fastPrimitiveValue` in `step` (SHIPPED 2026-06-14)

The first real attack on the `evalCore` tree-walk (the 49 % from 3c), and the first CPU/wall-clock
win on multi-shot. `BlockRuntime.step` now binds a single `val x = <expr>` via
`EvalRuntime.fastPrimitiveValue` (bare Value — no `evalCore` megamorphic match dispatch, no `Pure`
alloc) and only falls back to `interp.eval` when that returns null. **Safe by construction:**
`fastPrimitiveValue` returns non-null ONLY for a provably side-effect-free expression — effect ops
are `NativeFnV` (→ `pureCallValue` bails), and an effectful function body does not compile via
`compileSlotBody`/`valueCapable` (→ bails) — so binding the value directly can never drop a
`perform`; a `perform` rhs returns null and takes the unchanged monadic path. Only the single
`Pat.Var` shape uses the fast path; destructuring / multi-pat use `interp.eval` unchanged.

**Measured (clean back-to-back A/B, low load): effectMultiShotDeep 7.39 → 6.98 ms (−5.6 %)** —
a real CPU win (tight, non-overlapping error bars ±0.23 / ±0.19), not allocation. A *general* win
for any pure single-`val` binding in any block (function bodies, effect-body continuations). 231
interp/effects tests green (incl. the 3125/171875 multi-shot guard); no regression on recursionFib /
block benches. This is the first slice — the continuation is still re-walked structurally
(`step`/`FlatMap`); subsequent slices push the compiled-segment idea further (options 1/2).

### Recommendation (full feature — IN PROGRESS)

Building incrementally (user-directed). 3d shipped the first CPU slice (−5.6 %). The fuller
compiled-continuation feature (compile whole straight-line continuation segments once, re-run
compiled instead of re-walking `step`) remains the larger lever; each slice ships green + A/B'd on a
quiet machine (CPU wins need wall-clock validation — alloc metrics won't show them). This section is
the durable design + the profiled justification so the next session continues from data.
