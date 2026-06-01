# Interpreter performance — next optimization phases

Successor to [`vm-jit-spec.md`](vm-jit-spec.md). That spec covers the shipped
register VM (`SscVm`) + run-time JIT (`JitRuntime`): integer/double functions,
self/mutual recursion, and **VM 2a** (ref-values + ADT `match`, the recursive
ADT-eval workload — 36.8× faster, 170× less allocation on
`InterpreterBench.recursiveEval`).

This doc scopes what is left, why the easy levers are exhausted, and the two
large phases that remain. Both are gated on explicit go-ahead; each is a
multi-day project, not a localized patch.

## Where we are (measured 2026-06-01)

`RuntimeBench` cross-backend, `SSC_JIT` on, µs/op (lower is faster):

| Workload | Interpreter | JS (node) | JVM | Interp ÷ JVM |
|---|---|---|---|---|
| arithLoop | 3,125 | 1,367 | 292 | 10.7× |
| recursionTco | 1,173 | 125 | 29 | 40× |
| recursionFib | 32,797 | 4,967 | 1,507 | 22× |
| patternMatch (foreach) | 745,197 | 1,344 | 635 | **1173×** |

Two clusters remain off the native backends:

1. **Recursive / call-heavy code** (fib, tco, recursiveEval) — already JITted;
   22–40× off JVM. The gap is the **register-VM interpretation overhead** (the
   `while`/`switch`/array-index dispatch loop in `SscVm.exec`), not anything in
   the bodies.
2. **The foreach-accumulator** (`patternMatch`) — 1173× off JVM. It never
   enters the JIT: `List.foreach` + a closure + a boxed `Value` accumulator
   threaded through the free-monad `Computation`. This is the architectural
   tree-walk floor.

## Why the localized levers are exhausted

Documented in [`memory project_vm_jit`] and the spec's optimization log. The
honest, measured findings:

- **VM micro-ops on the recursive path are done.** Destination-passing and
  immediate-RHS opcodes shipped; the explicit-call-stack `exec` rewrite was a
  proven non-win (−4% fib but +25% tco regression — HotSpot already optimizes
  the recursive `exec`). Packing op/a/b/c into one Long regressed tco ~40%.
- **String-interning of ctor tags / field names (2026-06-01): NON-WIN,
  reverted.** A JFR of `recursiveEval` attributed ~23% to `String.equals`
  (ISTAG tag tests + `Map1/Map2` field-key compares). Interning both sides to
  hit the identity short-circuit produced **identical** deterministic
  `gc.alloc.rate.norm` and time-neutral results within noise. Lesson: on
  3-char ctor names even full content compares are cheap; the sampler
  over-attributed to a hot leaf. The real cost is the **`exec` dispatch loop
  itself** (194/~290 execution samples).
- **The foreach floor is structural**, confirmed both by a stub experiment and
  by per-class allocation profiles: a VM that compiles only `area` cannot touch
  the `List.foreach` + closure + `Computation.run` threading around it.

**Conclusion:** no remaining surgical patch moves either cluster. Closing the
gap needs one of the two phases below.

---

## Phase C — Bytecode codegen JIT (targets the recursive cluster)

**Goal:** emit real JVM bytecode for the compilable subset instead of
interpreting register bytecode in `SscVm.exec`. Removes the dispatch-loop
overhead entirely; recursive workloads (fib/tco/recursiveEval) approach the
JVM-codegen backend (~100×+ over tree-walk; recursiveEval from 36.8× toward
the fib/tco regime).

**Why this is the lever:** profiling shows the recursive cluster is bound by
`exec` interpretation, not body cost. The only way to remove an interpreter
loop is to not have one — generate a `MethodHandle`/class whose body *is* the
function, so HotSpot JITs it as ordinary Java code.

**Design sketch:**
- Reuse `VmCompiler`'s front half unchanged — the type lattice (TInt/TDouble/
  TRef), the bail-to-tree-walk gate, the `Meta`/`Resolve` resolvers, the ADT
  match shape. Only the back end changes: instead of emitting `op/a/b/c`
  arrays, emit bytecode.
- Two candidate back ends, pick by spike:
  - **ASM** (`org.ow2.asm`): generate a `final class` per `CompiledFn` with a
    `static long apply(long a0, …)` (numeric) plus `Object`-typed slots for ref
    params. Most control, a real dependency, more code.
  - **`java.lang.invoke` / `MethodHandles`**: assemble from combinators. Less
    raw code but combinator trees can themselves carry overhead — spike before
    committing.
- Keep the dual representation: numeric values stay raw `long`/double-bits;
  refs are `Object` (`InstanceV`). ADT `match` becomes a `typeName`-keyed branch
  + field reads (still `Map.apply`, or see Phase C-opt below).
- Recursion = ordinary JVM method calls (self-call → the same generated method;
  HotSpot inlines/optimizes). Self-tail-recursion can stay a generated loop.
- **Safety contract identical to today:** anything unsupported bails to
  tree-walk at compile time; any runtime throwable falls back and recomputes
  (the subset is side-effect-free, so re-running is observationally identical).
  `SSC_JIT=off` disables.

**C-opt (only if a profile after C still shows field access hot):** resolve
ADT field access to an array index at codegen time. Requires giving `InstanceV`
an ordered `Array[Value]` (or unboxed parallel arrays) alongside/instead of its
`Map[String,Value]` — an interpreter-wide representation change, so do it only
on profile evidence, as its own sub-phase.

**Risks:** bytecode generation is intricate (stack-map frames, verifier);
generated-class lifecycle/GC; a new build dependency (ASM). Mitigate by
spiking one function shape (int fib) end-to-end behind the existing gate before
broadening.

**Gating:** same-session back-to-back A/B on `recursionFib`/`recursionTco`/
`recursiveEval` ranked by time (+ `gc.alloc.rate.norm` as the deterministic
guard); full `backendInterpreter/test` green with `SSC_JIT` on AND off; never
ship a non-win.

## Phase D — Tier-2b foreach rearchitecture (targets the foreach floor)

**Goal:** close the 1173×-off-JVM `patternMatch` gap — the un-JIT-able
`xs.foreach(x => acc = acc + f(x))` shape that dominates idiomatic interpreter
code.

This is the **gated fast-tier** from the binary-strolling-river plan (A3/A4):
unboxed numeric slots + a Computation-free direct-style runner for the pure
compilable subset, boxing to `Value` only at the boundary where a value escapes
to a non-compiled context. Parts A1 (closure cells) and A2 (slot frames)
shipped; A3/A4 are the remainder.

**Why it is its own phase:** the cost is *around* the call (the foreach driver,
the closure, the boxed accumulator threaded through `Computation`), not inside
it — so Phase C's per-function codegen does **not** help here. It needs the
interpreter's value/effect threading to carry unboxed values across call
boundaries for the pure subset, with the monadic path retained for effectful
bodies. Prior incremental Stage A/B attacks on this were proven non-wins and
reverted because the match body was already DExpr-folded; only the
cross-boundary threading is left, and it is invasive.

**Gating:** as Phase C, on `patternMatchHeavy` + `patternMatchWide`; full suite
green with the fast-tier gate off AND on; spot-check identical output off vs on.

### Phase D — Update 2026-06-01: Heavy-double sub-target landed

A first sub-slice of Phase D shipped behind the `SSC_FASTTIER` /
`-Dssc.fasttier=on` gate, targeting the dominant
`xs.foreach(s => acc = acc + fn(s))` shape with a `Double` accumulator
(`patternMatchHeavy`'s exact form). Implementation:

- `PatternRuntime.CompiledMatch` gained a parallel `dhandlers` array and a
  `runValueDouble(scrutV, env): Double` that returns the matched arm's body
  folded directly to a primitive `double`. Built when every arm's body is
  `compileSlotD`-foldable (the existing unboxed-double slot evaluator); arms
  signal "no match" via a `NaNMiss` sentinel bit pattern and bail via the
  existing `NotDouble` `ControlThrowable`.
- New `FastTier.scala` recognizes the closure shape on AST identity (`acc =
  acc + fn(paramName)`), resolves `fn` from `closure.closure` or
  `interp.globals` (the empty-closure A1 path leaves global names in globals),
  reads `acc` initial value as a primitive `double` from `interp.globals`
  (since `Term.Assign` always targets globals — `EvalRuntime` line ~1403),
  iterates the list reading raw `double` from `runValueDouble`, and writes
  the accumulator back exactly once. Same-session A/B JMH, 2 forks × 5 iters:

  | Bench | Baseline | Spike | Δ |
  |---|---|---|---|
  | `patternMatchHeavy` time | 491.250 ± 10.043 ms | **240.195 ± 1.262 ms** | **−51.1%** (2.04×) |
  | `patternMatchHeavy` `alloc.rate.norm` | 28,236,753 B/op | **9,036,046 B/op** | **−68.0%** |
  | `patternMatchWide` time | 627.423 ms | 634.091 ms | +1.1% (noise) |
  | `patternMatchWide` `alloc.rate.norm` | 28,233,144 B/op | 28,234,187 B/op | noise |

  Full 1,204-test `backendInterpreter` suite green with the gate off AND on.
  `Wide` is intentionally outside this slice's scope: its accumulator is `Int`
  (`var total = 0`), so the spike returns `null` and falls through to the
  standard `foreachReusing` path — no regression.

### Phase D — Update 2026-06-01 (b): Wide-long sub-target landed

The `Int`-accumulator sub-target shipped same day, mirroring the double path:

- `PatternRuntime.CompiledMatch` gained `lhandlers: Array[(Value, Env) => Long]`
  and `runValueLong` (raw-`Long` arm dispatch). Sentinel for no-match:
  `LongMiss = Long.MinValue` (the user's `+`/`*`/etc. on case-class `Int`
  fields almost never produce this exact value; a pathological overflow falls
  back to the monadic path for that one element — correctness preserved).
- New `compileSlotLongBody` reuses the existing `DExpr` AST but evaluates via
  a new `evalSlotI` that throws `NotDouble` (the shared slot-eval bail signal)
  if a name resolves to `DoubleV` (the Long path refuses to demote precision).
  Bodies containing a Double literal are explicitly rejected by
  `containsDoubleLit` so `x + 0.5` stays Double-typed.
- `FastTier.tryLongAccumForeach` mirrors `tryDoubleAccumForeach` — same
  closure-shape detector, but the accumulator must be `IntV` in
  `interp.globals` and write-back uses `Value.intV(acc)` (which hits the
  cached `_intVPool` for any small result, eliding even the boundary box).

Same-session A/B JMH (2 forks × 5 iters):

| Bench | Baseline | Spike | Δ |
|---|---|---|---|
| `patternMatchHeavy` time | 492.745 ± 4.224 ms | **241.789 ± 1.008 ms** | **−50.9%** (2.04×) |
| `patternMatchHeavy` `alloc.rate.norm` | 28,237,334 B/op | **9,036,546 B/op** | **−68.0%** |
| `patternMatchWide` time | 630.089 ± 4.228 ms | **138.259 ± 0.924 ms** | **−78.1%** (4.56×) |
| `patternMatchWide` `alloc.rate.norm` | 28,236,350 B/op | **4,315,930 B/op** | **−84.7%** |

Full 1,204-test `backendInterpreter` suite green with the gate off AND on.

The `Wide` gain is larger than `Heavy` because the Long path leans entirely
on HotSpot's primitive `Long` arithmetic, and `Value.intV` hits the cached
`_intVPool` for the small accumulator results — even the boundary write-back
allocation is gone. The 12-arm dispatch is in a `while` over an
`Array[Long-returning function]` that HotSpot tracks well.

### Phase D — Update 2026-06-02: A4 pure-call generalization landed

Before this slice, `EvalRuntime.pureCallValue` only direct-style-ran
`Term.Match`-bodied functions. A4 generalizes the same direct-style-Value
return to any function whose body is in `PatternRuntime.compileSlotBody`'s
supported subset (literals, name lookups, primitive Int/Double arithmetic,
comparisons).

- `pureCallValue` split into `pureCallValueMatch` (the existing match path,
  unchanged) and a new `pureCallValueGeneric`. The generic path looks up the
  function body in `interp.pureBodyCache` (an `IdentityHashMap[Term, AnyRef]`
  keyed by the body's AST node — stable across `FunV` rebuilds since the AST
  is immutable; sentinel `PatternRuntime.PureBodyMiss` distinguishes "tried
  and bailed" from "not yet tried"). On a hit it invokes the cached
  `SlotBody` directly with the args passed positionally as `v0`/`v1` — no
  per-call `FrameMap.one`/`two`, no `Pure` wrapper.
- `PatternRuntime.compileSlotBody` and its supporting `SlotBody` type
  widened to `private[interpreter]`. No code duplication — the same compiler
  serves match-arm slot bodies and pure non-match function bodies.

New `InterpreterBench.pureCallSum` exercises the exact shape A4 targets:
`def f(x: Int): Int = x + 1; var total = 0; while i < 1_000_000 do total =
total + f(i); i = i + 1`. Clean same-session A/B JMH (2 forks × 5 iters,
stash-baseline → bench → pop → bench):

| Bench | Baseline | Spike | Δ |
|---|---|---|---|
| `pureCallSum` time | 2541.089 ± 23.366 ms | **118.850 ± 1.093 ms** | **−95.3%** (21.4×) |
| `pureCallSum` `alloc.rate.norm` | 134,731,016 B/op | **71,247,169 B/op** | **−47.1%** |

`patternMatchHeavy`/`Wide` unchanged (they hit `pureCallValueMatch`, not the
new generic path). `recursionFib`/`Tco`/`recursiveEval`/`arithLoop`/
`tupleMonoid`/`effectPure` all within noise. Full 1204-test suite green
with gate off AND on.

The 21× speedup is on the same order as switching from interpreter to JIT —
because for `f(x) = x + 1` we essentially WERE doing JIT-quality work (the
SlotBody returns IntV directly via `numericFastValue`), eliminating both the
per-call `Pure` wrapper and the param-frame allocation. The remaining 71 MB
alloc is mostly the outer-loop `i = i + 1` IntV+Pure overhead that the
non-tryLongWhileAssign path still pays (mixed apply+assign while body).

**Remaining Phase D work:**

- **Broader closure shapes** — multi-statement closure bodies (e.g.
  `s => { x = ...; acc = acc + fn(s) }`), 2-param closures, `foreach` over
  `Set`/`Map`/`Option`, accumulator declared inside a nested block
  (resolution via closure-env instead of globals).
- **Mixed apply+assign while bodies** — currently `tryLongWhileAssign` bails
  if a block has non-`Assign` stmts (the patternMatch outer-while is
  `{foreach_apply, i_assign}`). Generalizing it to accept leading pure
  applies (e.g. FastTier-handled foreach) + trailing long-assigns would
  remove the per-iter `i = i + 1` IntV+Pure overhead. Targets `pureCallSum`'s
  residual ~71 MB and `patternMatchHeavy`/`Wide`'s residual 4–9 MB.

---

## Methodology (both phases)

Carry the project's hard-won rules:
- Noisy machine — trust only **same-session back-to-back A/B** (stash baseline →
  bench → restore → bench). Rank by deterministic `gc.alloc.rate.norm`; for
  CPU-bound wins (Phase C) rank by time but require many iters/forks and treat
  wide error bars as "no signal."
- **Never ship a non-win.** Honest scoping over busywork.
- **Profile before declaring a floor, and profile before optimizing** — but
  cross-check JFR sampled weights against the deterministic alloc metric (a
  sampler can over-attribute to a hot leaf or point at a runtime-dead line).
- Per phase: commit (no `Co-Authored-By` trailer) → `git fetch origin &&
  git rebase origin/main` → full `backendInterpreter/test` (~1204 green) →
  ff-merge `git push origin HEAD:main`.
