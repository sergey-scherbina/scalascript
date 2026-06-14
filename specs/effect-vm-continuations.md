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

**Slice 2a — the block RESULT expr too (SHIPPED 2026-06-14).** A re-profile after slice 1 showed
`evalCore` *still* ~50 % leaf — because slice 1 only handled `Defn.Val`, while the block's final
expression statement (`e*e + sd`) is evaluated **once per complete path = 3125×** (more than all the
intermediate vals, ~780×, combined). Extending the same fast path to `step`'s `case t: Term` (bind a
provably-pure expression statement via `fastPrimitiveValue`, fall back to `interp.eval` for an
effectful one) gives the bigger win: **effectMultiShotDeep 6.95 → 5.44 ms (−21.7 %)** (clean
back-to-back A/B, tight non-overlapping bars). Cumulative 7.39 → 5.44 ms (−26 %). 207 tests green; no
regression. Same safety argument (pure-only ⇒ no dropped/reordered effect). General win for any pure
expression statement / block result.

### Phase 3e — post-slice-2a profile: the cheap arith cuts are exhausted, residual is structural (DIAGNOSTIC 2026-06-14)

A CPU re-profile after slices 1+2a (196 exec samples) shows the picture has shifted: **`evalCore`
33 %** (down from ~50 %), and **env `HashMap` operations ~26 %** — `MutableEnvView.foreachEntry`
(the per-block env copy in `evalBlock`, reached via `evalApplyGeneral`, ~14 %) + `findNode`/`index`/
`String.hashCode`/`equals` (name lookups, ~12 %). The remaining `evalCore` is reached mostly from
**`evalApplyGeneral`** — i.e. the `choose(..)` *perform* eval and the handler-body `opts.flatMap(..)`,
NOT pure arithmetic. So the cheap `fastPrimitiveValue` fast-path approach (slices 1/2a) is
**exhausted** for this shape: `choose` performs and `flatMap` is a HOF — neither is pure, so the fast
path returns null and they stay on `evalCore`. The two remaining levers are **structural**:
1. **Compile the perform / handler-body eval** (the `evalApplyGeneral` path) — part of the fuller
   compiled-continuation feature; large.
2. **Avoid the per-block `HashMap` env copy** (`evalBlock` `foreachEntry`) — use a `FrameMap` chain /
   reuse where block-scoping permits. A *general* eval-perf lever (helps far beyond effects), but
   correctness-sensitive (scoping) → its own slice with its own A/B, NOT under this spec.

`dispatchCase`'s per-perform recompute (`argPats.dropRight(1).map`/`lastOption`/`zip`) is a real but
**marginal** alloc cut (its own code is not a hot leaf; the per-perform cost is the handler-body eval,
which a precompute does not touch) — not worth a change to the correctness-critical effect handler
for a small gain. **TRIED + MEASURED 2026-06-14 (post-3f) + REVERTED:** built the per-`(eff,op)`
`OpCaseSpec` precompute (no per-perform `dropRight`/`map`/`zip`/`lastOption`/iterator); 316 effect+interp
tests green, but the clean back-to-back A/B was **4.25 → 4.19 ms — within noise (overlapping bars)**.
Confirms the post-3f residual is CPU-bound on the perform/handler eval (`evalApplyGeneral`), not the
dispatch overhead — so the alloc cut doesn't move wall-clock (the lesson), and a within-noise result
does not justify a change to the core effect dispatch. **The cheap + safe cuts are now definitively
EXHAUSTED.**

**Net: slices 1+2a captured the tractable arith re-eval cuts (−26 %). The residual is the larger
structural CPS/env work — approach deliberately, not as a tail-end micro-slice.**

### Phase 3f — free-var-limited closure capture (SHIPPED 2026-06-14, the biggest single slice)

3e flagged the env-copy as the next lever. Tracing the `MutableEnvView.foreachEntry` (~14 % CPU)
found it was **not** `evalBlock` — it was **lambda closure capture** (`EvalRuntime`'s `Term.Function`
case). The multi-shot handler body `opts.flatMap(opt => resume(opt))` is evaluated **per perform**
(781× for effectMultiShotDeep), and creating the `opt => resume(opt)` lambda captured the **entire**
env via `foreachEntry` — even though the body only references `resume`. Massive over-capture (the env
holds all accumulated continuation vars `a, sa, b, sb, …`).

Fix (a classic, well-understood closure optimization): capture **only the names the body could
reference**. `collectBodyNames(body)` = the distinct `Term.Name`s anywhere in the body, cached by AST
identity (`interp.lambdaFreeNamesCache`); the closure is built by looking up just those names in the
env instead of iterating the whole env. **Sound over-approximation** of the free vars (it also picks
up locally-bound / method / nested-lambda names — harmless: a non-captured name simply isn't in the
env, a same-as-globals name is left to re-read live at call time exactly as before, a shadowed one is
overridden at call), so no needed binding is ever dropped.

**effectMultiShotDeep 5.53 → 4.23 ms (−23.4 %)** (clean back-to-back A/B, tight non-overlapping bars)
— the biggest single slice. A **general** win for every lambda created in a non-trivial env (HOFs,
handlers, callbacks). No regression (hofPipeline ≈10⁻³, recursionFib family at baseline); 248
interp/effects tests green incl. the capture-heavy `GivenUsingTest`. **Cumulative effectMultiShotDeep:
7.39 → 4.23 ms (−43 %)** across slices 1 + 2a + 3f.

### Recommendation (full feature — IN PROGRESS)

Building incrementally (user-directed). Slices 1 + 2a (arith off `evalCore`) + 3f (free-var closure
capture) total **−43 %** on effectMultiShotDeep. The remaining lever is compiling the `perform` /
handler-body eval (`evalApplyGeneral`) — the fuller compiled-continuation feature, larger. Each slice
ships green + A/B'd on a quiet machine (CPU wins need wall-clock validation — alloc metrics won't show
them). This section is the durable design + the profiled justification so the next session continues
from data.

## Phase 4 — implementation plan: compiling the perform / handler-body eval (the remaining large lever)

This is the durable, concrete plan for the only residual after slices 1/2a/3f (cumulative −43 % on
`effectMultiShotDeep`, now ~4.2 ms). Profiles + the **s3 experiment** (the `dispatchCase` precompute
measured *within noise* and was reverted) prove there is **no cheaper slice** — the residual is
genuinely the CPS-style compile of the perform path, an effect-aware interpreter JIT. Build it
**deliberately**, when an effect-heavy workload (deep nondeterministic search, generators, streaming
with non-trivial per-step work) shows up as a *production* outlier — `effectMultiShotDeep` is a
synthetic non-outlier today.

### Where the residual CPU goes (post-3f profile, 178 leaf samples)
`evalCore` ~53 %, reached from **`evalApplyGeneral`** (the `choose(..)` *perform* eval + the handler
body `opts.flatMap(opt => resume(opt))`) and **`CallRuntime.runBody1`** (running the `opt => resume(opt)`
lambda body). I.e. per perform (781× for the 5×5 search) the interpreter tree-walks: the perform-op
call, the HOF (`flatMap`) dispatch, and the lambda body — none of which `fastPrimitiveValue` can fold
(they are not pure). The continuation is also rebuilt as a `FlatMap`/`Pure` tree each resume.

### The proven approach (reference implementations already in the tree)
The JVM and JS **codegen** backends already CPS-transform direct-style effectful `.ssc` into
monadic/Free-threaded target code: `runtime/backend/jvm/.../JvmGenCpsTransform.scala` (~1070 lines)
and `runtime/backend/js/.../JsGenCpsCodegen.scala` (~890 lines). They split an effectful body at its
`perform` points, keep pure sub-expressions as-is, and thread effect ops through `_bind`. Phase 4 is
**bringing that transform to the interpreter's JIT** (`JavacJitBackend`, which emits Java source →
javac → `MethodHandle`): compile a `handle` body's continuation **segments** once, so a multi-shot
`resume(v)` invokes a *compiled* segment instead of re-tree-walking the AST + rebuilding the Free tree.

### Phased build (each phase ships green + a quiet-machine wall-clock A/B; CPU wins are invisible in alloc)
1. **P4.1 — straight-line effectful blocks only.** Recognise the canonical shape
   `val x1 = E.op(a..); <pure>; val x2 = E.op(..); …; <pure result>` (exactly `effectMultiShotDeep`).
   Build a `CompiledEffBlock` = ordered list of `(bindName, performTemplate)` + a compiled pure
   `resultFn: Array[Value] => Value` (reuse `compileIntArith` / the slice-1/2a fast-path machinery for
   the pure segments). The handler drives it: each `resume(v)` writes the slot and runs the next
   compiled perform-template + pure segment — no AST re-walk, no per-resume `FlatMap` alloc. Bail to
   the existing trampoline for any non-straight-line body (nested `if`/`while`/`match` between performs,
   non-`Pat.Var` binders, guards). This is the bulk of the win for search/generator workloads.
2. **P4.2 — compiled continuation across one branch point.** Handle a single `if`/`match` between
   performs by compiling each arm's segment; the runtime picks the arm, then runs its compiled tail.
3. **P4.3 — JavacJitBackend lowering.** For a *hot* compiled-eff-block, emit Java source for the pure
   segments (already supported by `walkLong`/`walkRef`) + a perform bridge (cf. the P2 `resolveEffect*`
   bridge), so the segments run as compiled bytecode, not closures. Only when a segment is hot enough.
4. **P4.4 — multi-shot fast resume.** With segments compiled, a multi-shot `resume` is just re-running
   the compiled tail with a fresh slot value — the structural win over today's `handleInterp(f(v))`
   re-reification.

### Safety invariants (non-negotiable — effects are correctness-critical)
- **Effect order + multiplicity preserved.** A compiled segment must perform exactly the same ops in
  the same order as the tree-walk; `resume` called 0/1/N times must run the continuation 0/1/N times.
- **Bail must be transparent.** Any unsupported shape (or a `perform` reaching an outer handler with no
  compiled segment) falls back to the current trampoline with no partial side effects (mirror the
  P2 `tryWhileJit` "write slots only on success" property).
- **Pure-segment compile reuses the proven slice-1/2a path** (`fastPrimitiveValue` ⇒ provably
  side-effect-free), so no effect is ever folded away or reordered.
- **Test:** `EffectVmContinuationsTest` 3125/171875 multi-shot guard + one-shot + deep-handler +
  `StdEffects`/`JvmGenEffects`/`JsEffectLoop` + a new compiled-vs-trampoline differential test over a
  corpus of handler shapes; A/B on a quiet machine.

### Honest cost / recommendation
~1000-line-class-scale effort (the codegen CPS transforms are that size), high blast radius (the core
effect path), for a **non-outlier** (4.2 ms synthetic). The implementable plan above stands; but
**slice 1 below captured the dominant handler-side residual cheaply first.**

### Phase 4 — slice 1: `flatMap`-resume η-reduction (SHIPPED 2026-06-14, −47 % — the biggest single slice)

Building P4 started by re-checking *where* the residual is: post-3f the profile shows it is
**handler-side** (`evalApplyGeneral` + `CallRuntime.runBody1` — the handler body
`opts.flatMap(opt => resume(opt))` re-evaluated on every perform, 781× for the 5×5 search), **not**
the block's `step` (~5 leaf samples). So P4.1-as-written (compile the *block*) targeted the wrong
thing. The high-value cut is the canonical multi-shot handler body itself:
`coll.flatMap(x => resume(x))` is **η-equivalent to `coll.flatMap(resume)`** (`resume` is a 1-arg
`NativeFnV`). `EffectsRuntime.flatMapResumeColl` recognises that exact shape and `dispatchCase`
calls the `flatMap` dispatch with the `resume` Value **directly** — eliminating, per perform, the
`x => resume(x)` lambda `FunV` creation, the `evalApplyGeneral` `flatMap` method-resolution, and the
per-element lambda-body re-eval (`callValue1`/`runBody1`/`eval(resume(opt))`). Bails to the unchanged
`interp.eval(c.body)` for any other handler shape, so semantics are preserved everywhere else.

**effectMultiShotDeep 4.26 → 2.24 ms (−47.3 %)** (clean back-to-back A/B, tight non-overlapping
bars) — the biggest single slice. **Cumulative 7.39 → 2.24 ms (−70 %)** across slices 1/2a/3f + this.
247 effect/interp tests green incl. the 3125/171875 + 256/2560 multi-shot guards (η-reduction is
result-identical). Safe: recognised pattern + fallback, scoped to `dispatchCase` (effect path only).
Generalises to any `coll.flatMap(x => resume(x))` handler (the textbook nondeterminism shape). The
*fuller* compiled-eff-block / JavacJit-lowering work (P4.1/P4.3 above) remains larger and is now
lower-priority (effectMultiShotDeep is 2.24 ms) — build it only when a real workload justifies it.

### Phase 4 — post-slice-1 re-profile (DIAGNOSTIC 2026-06-14): the cheap cuts are now genuinely done

After slice 1 (effectMultiShotDeep 2.24 ms), a CPU re-profile (194 leaf samples, quiet machine)
shows the residual is **inherent enumeration + the larger P4.1 block-compile**, NOT a missed cheap
cut:
- **`DispatchRuntime.dispatchList` 38 %** — the `coll.flatMap(resume)` enumeration loop (now called
  directly). Largely *inherent*: it builds the 3125-path result via `buf ++= inner` across the nested
  flatMaps — that IS the multi-shot work; can't be cut without folding the effect away.
- **`evalCore` 23 %** — the block continuation re-eval (`step` re-walk + the `NonDet.choose` perform
  eval). This is the P4.1 *compiled-eff-block* territory (compile the continuation block so resume
  replays compiled segments instead of re-walking) — the larger, lower-priority feature.
- `Tuple4`/`Some` unapply alloc reappears in the block-re-eval path — but the **s3 experiment already
  proved an unapply/alloc cut in the effect re-eval is within-noise** on this CPU-bound residual, so
  it is not worth a change to the core effect path.

**Conclusion (profile-backed this time): the cheap + safe handler-side cuts are done.** slice 1
(η-reduction) captured the dominant avoidable cost; the remaining ~2.24 ms is the inherent
nondeterminism enumeration plus the larger P4.1 block-compile. effectMultiShotDeep went 7.39 → 2.24 ms
(−70 %) across slices 1/2a/3f + the η-reduction. Build P4.1/P4.3 only when a real effect-heavy
workload makes 2.24 ms a production outlier.

### Phase 4 — P4.1 block-side micro-cut attempt (TRIED + within-noise + REVERTED 2026-06-14)

User asked to speed multi-shot further (build P4.1). Profiling the 2.24 ms residual pinpointed the
biggest *tractable* block-side waste: `step`'s compound-assign case
`case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, …)` UNAPPLIES every `Term.ApplyInfix` statement
(allocating `Some`+`Tuple4`, JFR 95/80 samples) before the `lhs: Term.Name` guard can reject it — and
a block's pure result expr (`e*e + sd`) is exactly such an infix, re-evaluated per continuation re-run
(3125×). Converted it to a type-test + field-access guard (no unapply); 226 tests green. But the clean
back-to-back A/B was **2.34 → 2.31 ms — within noise (overlapping bars)** → reverted.

This is the **second** block-side micro-cut to land within-noise (after the s3 `dispatchCase`
precompute). Both confirm: post-slice-1 the residual is **`dispatchList` 38 % (inherent nondeterminism
enumeration) + `evalCore` 23 % (block continuation re-eval)**, and the block-side `evalCore` does NOT
yield to cheap per-statement cuts (they're alloc on a partly-CPU-bound bench → within-noise, the
recurring lesson). The **only** path to a further *measurable* multi-shot speedup is the full P4.1
compiled-eff-block (compile the straight-line continuation so resume replays compiled segments instead
of re-walking `step`) — a ~1000-line, high-blast-radius build for a 2.24 ms non-outlier. **Disproportionate;
deferred.** effectMultiShotDeep is at its practical floor at −70 % (7.39 → 2.24 ms) via the cheap+safe
cuts + the slice-1 η-reduction.

## §5 — P4.1 compiled-eff-block (the full straight-line continuation compile) — user-directed build

Status: **building 2026-06-14** (user override of the "deferred" disposition: *"единственный путь к
измеримому дальнейшему ускорению multi-shot — полная P4.1 (compiled-eff-block): компилировать
прямолинейный блок-продолжение, чтобы resume проигрывал скомпилированные сегменты вместо повторного
обхода step"*). This section is the durable design; it ships green + a quiet-machine A/B and is kept
even if the A/B is within-noise (the design + measured result are the value, as for the two prior
within-noise block-side cuts in §4).

### Goal
Compile a **straight-line effectful block** (the canonical multi-shot continuation shape — exactly
`effectMultiShotDeep`) ONCE into a pre-classified array of compiled segments, so a multi-shot
`resume(v)` REPLAYS the compiled segments instead of re-walking `BlockRuntime.step`'s per-statement
`s match` dispatch (+ list-cons traversal + `Defn.Val`/`Pat.Var` unapply) on every resume. Per the §4
post-slice-1 profile the block-side residual is `evalCore` ~23 % (the `step` re-walk + the `choose(..)`
perform eval); this targets the `step` re-walk + the pure-arith re-eval directly.

### Where the win is — and is NOT (honest, profile-backed)
The §4 profile splits the 2.24 ms residual into **`dispatchList` 38 % (inherent nondeterminism
enumeration — uncuttable without folding the effect away)** + **`evalCore` 23 % (block continuation
re-eval) + ~39 % env/resume/FlatMap/trampoline**. P4.1 attacks the `evalCore` 23 % *block-side* part:
- **Structural**: a pre-classified `Array[CStep]` indexed by position replaces `step`'s `remaining
  match` (list-cons) + `s match` (type-test chain) + the `Defn.Val` field-access + `List(Pat.Var(n))`
  unapply per statement per resume.
- **Pure-Int-arith**: the scoring segments (`a*a`, `b*b+sa`, …, `e*e+sd`) compile to a direct
  `Env => Long` closure (no `fastPrimitiveValue` recursion, no per-intermediate `IntV` boxing, no
  `fastPrimitiveInfixValue` op-string match, no `evalCore`).

P4.1 does **NOT** cut the *perform's* `evalCore` dispatch (the `choose(..)` call that must dispatch to
produce a `Perform`). Cutting that would need a **direct-Perform replay** (eval args + emit
`Perform(eff,op,args)` without `interp.eval`), which re-implements correctness-critical effect-op
dispatch — including the one-shot **resolver** check (§ Phase 2: an in-scope resolver makes the op
return `Pure(resolver(args))` not a `Perform`) and nested-effect arg threading. That is the
~1000-line, high-blast-radius work; **explicitly OUT OF SCOPE for this slice** (disproportionate for a
2.24 ms non-outlier). Performs stay on the proven `interp.eval` monadic path, unchanged.

### Data structures
```
sealed abstract class CStep
final class CValStep(name: String, rhs: Term, arith: (Env => Long) | Null)   // val name = rhs
final class CExprStep(rhs: Term, arith: (Env => Long) | Null, isLast: Boolean) // bare expr stmt
```
`arith` (when non-null) is a compiled pure-Int-arithmetic closure over env names + Int/Long literals
(`+`/`-`/`*`, unary `±`) — the `compileEnvIntArith` analogue of §2c's `compileIntArith`, keyed by env
name instead of perform-arg slot. It is paired with the set of free names it reads; at replay the step
first checks all those names are currently `IntV` (a cheap pre-pass) and only then runs the primitive
`Long` closure (no intermediate boxing); if any name is non-`IntV`/absent it falls back to
`fastPrimitiveValue` then `interp.eval` — so semantics are unconditionally preserved.

### Recognition — `compileEffBlock(stats): Array[CStep] | Null`
Returns the compiled array, or **null to bail to `step`** for any non-straight-line shape:
- `Defn.Val` with `pats == List(Pat.Var(n))` → `CValStep(n.value, rhs, compileEnvIntArith(rhs))`.
- A bare `Term` expression statement that is **not** a `Term.Assign` and **not** a compound-assign
  `Term.ApplyInfix` (`op` ends in `=`, not a compare) → `CExprStep(rhs, compileEnvIntArith(rhs), isLast)`.
- **Bail (null)** for: destructuring / typed / multi-pat `Defn.Val`, `Defn.Var`, `Term.Assign`,
  compound-assign infix, `Defn.Def`, and any other `Stat`. Those need `step`'s local+global
  write-through / scoping and are not the straight-line pure/perform shape.
Cached by `stats` AST identity in `interp.effBlockCache` (List object is stable for a given
`Term.Block` node); a bail caches the `EffBlockMiss` sentinel so re-bail is one IdentityHashMap.get.

### Replay — `runCompiled(steps, i, lastVal): Computation`
Replaces `step(remaining, lastVal)`; same `local`/`localView` env as `step` (defined as its sibling in
`evalBlock`, sharing the captured `local`/`localView` so binds + reads are identical). For step `i`:
- `CValStep`: if `arith` non-null and its names are all `IntV` → `local(name) = IntV(arith(localView))`,
  recurse `runCompiled(i+1, Unit)`. Else `fastPrimitiveValue(rhs)` → bind + recurse (the slice-1 fast
  path); else `interp.eval(rhs)` → `Pure(v)` bind + recurse, or `FlatMap(rhsC, v => bind; runCompiled(i+1))`
  (the perform path — IDENTICAL to `step`'s `Defn.Val` monadic arm, continuation re-enters `runCompiled`).
- `CExprStep`: same, binding `lastVal` instead of a name (the slice-2a fast path for a pure result expr;
  the monadic path threads an effectful expr unchanged).
- `i == steps.length` → `Pure(lastVal)`.

`evalBlock` routes the **multi-statement** path (its single-statement and env-setup fast paths are
untouched) to `runCompiled(steps, 0, Unit)` when `compileEffBlock` succeeds, else to `step` unchanged.

### Safety invariants (effects are correctness-critical)
- **Effects untouched**: every perform/effectful rhs takes the exact `interp.eval` monadic path `step`
  uses; `resume` 0/1/N times runs the continuation 0/1/N times (the `FlatMap` continuations re-enter
  `runCompiled` identically to how they re-enter `step`). No perform is folded, dropped, or reordered.
- **Pure-arith bit-identical**: `IntV op IntV ⇒ intV` is plain 64-bit `Long` (`DispatchRuntime`); the
  closure computes the same `Long`. Any non-`IntV` operand at runtime ⇒ fall back to
  `fastPrimitiveValue`/`interp.eval`. `/`,`%`, Double, conversions, free names, tuples ⇒ `arith == null`.
- **No cross-context perform mis-classification**: only the **context-free** structural classification
  (val-vs-expr, names, `arith` closures) is cached by `stats` identity. The perform-vs-pure runtime
  determination is made fresh by `interp.eval`'s return (`Pure` vs `FlatMap`/`Perform`) on each
  `evalBlock` call's pass — never cached — so the same block evaluated under a different handler
  (different resolver scope) is always correct.
- **Bail is transparent**: an unsupported shape uses `step` with no behavioural change.

### Test plan
`EffectVmContinuationsTest`: the 3125/171875 deep-interleaved multi-shot guard (the compiled block) +
256/2560 (cached const list) + one-shot tail-resume + deep-handler `msg :: resume(())` (return clause,
NOT a straight-line block → must bail to `step`) + op-arg binding + multi-shot-untouched. Plus
`StdEffectsTest`, `EffectOneShotFastPathTest`, `JvmGenEffectsRuntimeTest`, `JsEffectLoopTest`,
`InterpreterTest` (block semantics: nested blocks, destructuring vals → bail path, var/assign → bail
path). Then a back-to-back **stash A/B** on a quiet machine (`scripts/bench interp effectMultiShotDeep`,
several reps): ship only on a measurable non-overlapping-error-bar win; revert the impl if within-noise.
