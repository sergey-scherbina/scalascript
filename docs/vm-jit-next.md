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

### Phase D — Update 2026-06-02 (b): LApply in-loop fn-call inlining

The A4 slice (this same day) cut `pureCallSum` from 2541 ms → 119 ms by
making `pureCallValue` direct-style-Value-return any pure non-Match body.
The residual 71 MB was the outer-loop `i = i + 1` IntV+Pure overhead because
`tryLongWhileAssign` bails when any RHS contains a `Term.Apply` (the
`total = total + f(i)` couldn't compile to LExpr).

This slice fixes that by extending `EvalRuntime`'s LExpr ADT with a new
`LApply` variant and teaching `tryLongWhileAssign`'s `compileExpr` to fold
1-param pure-bodied function calls inline:

- New `PatternRuntime.compileSlotLongFn1(body, paramName, interp): (Long,
  Env) => Long` — raw-`Long`-arg variant of `compileSlotLongBody` reusing
  the existing `DExpr` AST. `DSlot(0)` is read as the arg directly (no
  `IntV` boxing); other slots throw `NotDouble` (1-param only). Free names
  still go through `slotToL`.
- New `LApply` LExpr subclass holds the compiled `slotFn`, the fn's name,
  and a reference to the fn's `body` AST node as `expectedBody`. At eval
  time it re-resolves `interp.globals.getOrElse(fnName, null)` (so a
  user-reassigned `f` is observed correctly) and bails with
  `PatternRuntime.NotDouble` if `fn.body ne expectedBody`. The cost per
  call is one `HashMap.getOrElse` + one `eq` check + one delegated
  `slotFn(arg, fn.closure)` call — no allocation.
- `tryLongWhileAssign`'s hot loop now runs inside `try/catch
  PatternRuntime.NotDouble => null`, so a reassignment bail returns to
  the value-space loop. The `ControlThrowable` is stackless; the cost is
  paid only on the bail path, not the steady-state loop.

Same-session A/B JMH (2 forks × 5 iters, stash → bench → pop → bench):

| Bench | Baseline (post-A4) | Spike (post-LApply) | Δ |
|---|---|---|---|
| `pureCallSum` time | 119.332 ± 3.413 ms | **12.369 ± 0.394 ms** | **−89.6%** (9.65×) |
| `pureCallSum` `alloc.rate.norm` | 71,247,179 B/op | **24,030,942 B/op** | **−66.3%** |

**Cumulative from pre-Phase D baseline**: `pureCallSum` 2541 ms → **12.4 ms**
(**205× speedup**). The interpreter is now ~22× off the JVM-codegen backend
on this shape (vs ~4540× before Phase D).

`patternMatchHeavy`/`Wide`, `arithLoop`, `recursionFib`/`Tco`/`recursiveEval`,
`tupleMonoid`, `effectPure` all within noise of pre-LApply. Full 1204-test
`backendInterpreter/test` suite green with `SSC_FASTTIER` off AND on.
`recursionTco` measured 0.984 ms in this sanity run — back to the historical
~0.99 ms baseline, confirming the earlier "TCO +17%" from the day before was
machine noise (concurrent build daemons), not a regression from any Phase D
landing.

### Phase D — Update 2026-06-02 (c): mixed apply+assign while bodies

`tryFastWhileAssign` previously required all body stmts to be
`Term.Assign(Term.Name, _)` — `collectFastAssignBody` bailed on anything
else. The `patternMatchHeavy` / `patternMatchWide` outer-while is
`{ shapes.foreach(...); i = i + 1 }` (or `ops.foreach(...); i = i + 1`):
one leading `Term.Apply` followed by one `Term.Assign`. The Apply triggered
the bail and the whole loop fell to the value-space path, allocating
per-iter `IntV`+`Pure` wrappers for the int counter and `evalBlock`
threading overhead.

This slice adds:

- New `MixedAssignBody { leadingApplies, names, rhs }` body shape +
  `collectMixedAssignBody(body)` that recognizes `Block(apply*, assign+)`.
- New `tryMixedLongWhile` mirrors `tryLongWhileAssign` but per iter also
  runs each leading apply via `interp.eval(applyTerm, frameView)` and
  discards the result (FastTier handles `xs.foreach(...)` shape cheaply,
  so the side effect is near-free).
- Safety: a new `applyAccessesNames(tree, names)` AST walker verifies that
  the leading applies do NOT reference any slot-assigned name. If they
  did, the apply (which reads through `frameView`, not the long slot) would
  see stale data, and a long-slot update would not flow back to the apply.
  The `patternMatch` benches don't read `i` inside the foreach closure, so
  the check passes; any user code that breaks the assumption falls back to
  the value-space loop with no semantic change.
- Hooked in `tryFastWhileAssign`: when `collectFastAssignBody` returns null,
  try `collectMixedAssignBody` before bailing.

Same-session A/B JMH (2 forks × 5 iters, stash → bench → pop → bench):

| Bench | Baseline | Spike | Δ |
|---|---|---|---|
| `patternMatchHeavy` time | 244.575 ± 2.170 ms | **114.643 ± 1.334 ms** | **−53.1%** (2.13×) |
| `patternMatchHeavy` `alloc.rate.norm` | 9,035,891 B/op | **4,091,085 B/op** | **−54.7%** |
| `patternMatchWide` time | 138.989 ± 1.005 ms | **73.436 ± 0.644 ms** | **−47.2%** (1.89×) |
| `patternMatchWide` `alloc.rate.norm` | 4,315,327 B/op | **2,167,647 B/op** | **−49.8%** |

`arithLoop`, `pureCallSum`, `recursionFib`/`Tco`/`recursiveEval`,
`tupleMonoid`, `effectPure` all within noise. Full 1204-test
`backendInterpreter/test` suite green with `SSC_FASTTIER` off AND on. The
2× win exceeded the original 5-15% estimate because the value-space path's
overhead (the per-iter `evalBlock` Computation threading + the
`fastPrimitiveValue` non-cached re-walk + the IntV pool miss for `i >
16383`) added up to ~50% of the iteration cost on these benches, not the
~10% that just the IntV alloc accounted for.

**Cumulative from pre-Phase D**: `patternMatchHeavy` 491 → **115 ms (4.27×)**,
`patternMatchWide` 630 → **73 ms (8.58×)**, `pureCallSum` 2541 → 12 ms (205×).
Cross-backend interpreter-vs-JVM gap on `patternMatch` shape now ~208× off
(from 1156× off at session start).

**Remaining Phase D work:**

- **Broader closure shapes** — `foreach` over `Set`/`Map`/`Option`,
  multi-statement closure bodies, 2-param closures, accumulator declared
  inside a nested block (resolution via closure-env instead of globals).
- **2-param `LApply`** — `compileSlotLongFn1` is 1-param only; a 2-param
  variant covers `g(x, y)`-shaped pure-bodied calls inside a long-while.

---

## Phase C+D roadmap (2026-06-02, post the recursive-cluster wins)

After three Phase C slices (int-arith, ADT match, TCO loop) plus the
free-name globals subset, the recursive cluster is at JVM-codegen speed
or faster (`recursionFib` 1.20 vs JVM 1.27 ms). The remaining
high-leverage gap is `patternMatch`: 213× off JVM in `RuntimeBench`.
Both directions stay open and are queued in order of bench impact.

### Phase C — wider subset (focused commits, in order)

1. **Double-typed params/return** — non-match-bodied fns whose params or
   result are `Double`. Generate Java `double` instead of `long`; handle
   `Lit.Double`, double-typed arithmetic ops, and a discriminator at the
   MH return boundary so the caller wraps `DoubleV` vs `IntV`. Bench:
   `recursionFibD`. Expected ~24× over the existing SscVm Double path
   (mirrors the int fib ratio).

2. **Mixed-type self-recursion** — `paramIsRef` is currently uniform per
   param-slot in `JitRuntime.marshalBytecode`. `def g(x: Int, e: Expr):
   Int = match e ...` needs argument-by-argument type-checking against
   the fn's declared param shapes. Bench: an AST eval with an Int
   accumulator + Expr scrutinee.

3. **Free-name `Double` globals** — companion to the Int globals work
   (commit `7162a155`). Emit `BytecodeJit.readGlobalDouble(name)`.

4. **Mutual recursion** — each `BytecodeJit` compile yields a self-
   contained class today. A pair / cycle of mutually recursive funcs
   needs either co-compilation OR a runtime MH registry indexed by fn
   name. Lower priority — uncommon in practice.

5. **Wider `Term.Match` arms** — guards, literal patterns, `Pat.Bind`,
   `Pat.Alternative`, nested matches. Each adds a few Java-emission
   cases in `walkArm`.

6. **`Term.Block` bodies** — multi-stmt blocks need local-var decls and
   sequence handling.

### Phase D — `patternMatch` (the 213× off-JVM gap)

The dominant interpreter/JVM gap remaining after the foreach-cluster
wins. `interp_patternMatch` is 118.6 ms vs `jvm_patternMatch` 0.56 ms in
`RuntimeBench`. Subsequent slices to attack, in order:

1. **`var total: Double` slot in the outer while** —
   `tryMixedLongWhile` keeps Int counters in Long slots but the
   Double-typed accumulator falls back to per-iter `DoubleV` alloc. A
   parallel `tryMixedDoubleWhile` (or extending the existing path to a
   dual-bank slot frame) closes the remaining accumulator alloc gap.

2. **`Pure` wrapper elimination on the foreach driver** — FastTier
   returns `Computation.PureUnit` once per outer iter; the `evalBlock`
   threading STILL allocates per-stmt `FlatMap`/`Pure` wrappers in the
   value-space fallback that some inner shapes still take.

3. **`InstanceV` ordered-array repr** (the C-opt in §"Phase C" above) —
   replaces `Map[String, Value]` with `Array[Value]` + a typeName-keyed
   dispatch table. Eliminates the HashMap lookup in `field("name")`
   calls, both for BytecodeJit `recursiveEval` (2.5× → ~10× target) AND
   for `patternMatch` arm field reads. Interpreter-wide change; gate
   behind a feature flag like the existing perf flags.

4. **Pattern compilation across multiple match-bodied callees** — detect
   that `area(s) match` is a slot-compilable double match AND `s` is
   iterated via `shapes.foreach`, then fuse the foreach driver + the
   match into a single bytecode-JIT-style loop. Likely needs (3) first.

5. **`patternMatchSet` extension** — direct `Set`-aware FastTier path
   (skips `Set.toList` allocation in the current dispatchSet path).

6. **`patternMatch` cross-Phase-C-D synergy** — once `InstanceV` array
   repr lands, BytecodeJit could compile `area(s) match` directly to a
   Java method that operates on the array repr, removing both HashMap
   lookups AND per-element foreach call overhead. This is the path
   toward closing the remaining 213× gap.

Both directions stay open in parallel; pick whichever has a clearer
bench-driven win next.

### Current baseline (2026-06-02 end-of-session)

Default flags ON: `SSC_JIT=on`, `SSC_FASTTIER=on`, `SSC_JIT_BYTECODE=on`.
Cross-backend `RuntimeBench` (µs/op, default flags):

```
interp_recursionFib:    1190  vs jvm 1281   (interp 0.93× — FASTER than JVM)
interp_recursionTco:      31  vs jvm   24   (interp 1.29× — parity)
interp_arithLoop:       2717  vs jvm  240   (interp 11.3× off — future, not in C/D)
interp_patternMatch:  114610  vs jvm  566   (interp 203× off — main Phase D gap)
```

`InterpreterBench` highlights (ms/op):

```
patternMatchHeavy: 113 (Phase D target)    recursionFib:  1.19 (at JVM speed)
patternMatchSet:   113 (Phase D target)    recursionFibD: 1.44 (at JVM speed)
patternMatchWide:   74 (Phase D target)    recursionTco:  0.032 (at JVM speed)
pureCallSum:        12.5 (covered)         recursiveEval: 13.0 (Phase D target — HashMap floor)
pureCallSum2:       13.9 (covered)         recursionFibMul: 6.06 (globals overhead)
```

A next-session A/B should re-establish these as the baseline before
attacking the next slice. See `WORK_QUEUE.md §"Interpreter perf — Phase
C + D continuation"` for the per-task implementation notes + gotchas
that came out of today's session.

---

## Direction A — incremental BytecodeJit extensions (2026-06-02 roadmap)

Per `~/.claude/plans/noble-discovering-knuth.md`, after Phase C / D
landed JVM parity on `arithLoop` / `recursionFib` / `recursionTco`,
the next-tier benches (`pureCallSum`/`2` 13–14 ms,
`patternMatchHeavy`/`Set`/`Wide` 7.8–20.8 ms,
`recursiveEval`/`Mixed` 13 ms) still tree-walk because their AST
shapes hit `return null` paths in the BytecodeJit walkers. The Direction
A roadmap extends walker coverage one shape at a time. Each slice is
independently A/B-provable and bails to SscVm.exec / tree-walk on
shape miss.

### Bail-shape → bench-target mapping

| WORK_QUEUE item | Walker(s) | Bail shape | Bench target |
|---|---|---|---|
| `phase-c-bytecode-block-single` (A.1) | `walkLong`, `walkDouble`, `walkRef` | `Term.Block(single stat)` | any 1-stmt-block closure body |
| `phase-c-bytecode-if-in-while` (A.2) | `tryCompileWhileLong.walkLocalSlot`, `walkLocalBool` | `Term.If` in RHS | loops with `x = if c then a else b` |
| `phase-c-bytecode-pure-fn-call` (A.3) | `walkLong` Term.Apply | non-self-recursive Apply on a pure top-level def | `pureCallSum` / `pureCallSum2` |
| `phase-c-bytecode-foreach-static` (A.4) | new path combining `tryCompileWhileLong` + `walkArm` | `xs.foreach(s => acc = acc + fn(s))` over stable global | `patternMatchHeavy`/`Set`/`Wide` outer loop |
| `phase-c-bytecode-block-multistat` (A.5) | all walkers | `Term.Block(multi-stat)` with let-bindings | compound function bodies |

### Slice dependencies

- A.1, A.2, A.3 are independent — can run in parallel sessions.
- A.4 gated on `phase-d-instancev-array-repr-activation` (Direction B
  Phase 3) so `walkArm` field reads compile to `inst.fieldsArr()[idx]`,
  AND on `phase-c-bytecode-match-double-body` (sibling plan
  `~/.claude/plans/typed-seeking-cosmos.md`) so the inner match arms
  compile too.
- A.5 benefits from B Activation similarly (bound values can be
  array indices) but is not gated.

### Sibling — patternMatch JIT (parallel agent, `typed-seeking-cosmos.md`)

A parallel agent's plan `~/.claude/plans/typed-seeking-cosmos.md`
(`phase-c-bytecode-match-double-body` in WORK_QUEUE) extends
`BytecodeJit` to JIT Double-typed `Term.Match`. Three changes:

- **A** — `walkMatchBody` becomes parametric by return type
  (`long`/`double`/`Object`); `walkDouble`/`walkLong` recursively
  invoke it (currently `Term.Match` only fires when match IS the
  entire function body, blocking nested match in compiled bodies).
- **B** — Replace if-equals chain in `walkMatchBody` with
  `switch (tn) { case … }`; javac lowers `switch(String)` to
  `lookupswitch` by `String.hashCode`.
- **D** — Remove the duplicate `t == tn` check in
  `PatternRuntime.scala:806/961/992` arm-closures (the tag-scan loop
  in `runValueDouble/Long` already verified the tag, so the closure-
  internal re-check is dead work). Also helps the non-JIT fallback
  by ~10%.

Target: `interp_patternMatch` 7615 → < 2500 µs/op (3×+).
**Independent of Direction A.1-A.5 here**; lands as its own PR.
A.4 (foreach-static) is gated on it landing.

### Per-slice protocol

Same as Phase C / D: one worktree per slice; compile + test green
default AND `SSC_JIT_BYTECODE=off`; bench A/B target bench (must
move) + `scripts/bench interp` full set (no regression); JFR-profile
target bench to confirm `SscVm.exec` / tree-walk dropped from the
top of the allocator samples.

### Reusable infrastructure

- `BytecodeJit.cache: IdentityHashMap[Term, AnyRef]` with sentinel
  `BailSentinel` — model for compile-once memoisation. A.3 needs a
  per-pure-fn cache keyed by the fn's body AST.
- `JitInterfaces.scala` traits (`LongFn1`/`DoubleFn1`/`ObjToLong`/…)
  — model for typed-primitive dispatch. A.3 adds arity-N variants
  parallel to existing ones.
- `interp.typeFieldOrder` — A.4 / A.5 field-index emission references
  this directly (after B Activation).

---

## Direction B — InstanceV positional array fields

Detailed in `docs/instancev-array-repr-spec.md`. Direction B is the
biggest single lever remaining (`recursiveEval` 2-2.5× projected) and
unblocks A.4 / A.5. The Direction A slices that don't depend on B can
run first or in parallel.

---

## Direction C — Direct-style eval (deferred)

`docs/direct-style-eval-spec.md` to be written before any Direction C
code. Multi-week project, 530+ sites. See WORK_QUEUE item
`direct-style-eval-spec`.

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
