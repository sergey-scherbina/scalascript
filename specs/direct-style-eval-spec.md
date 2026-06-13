# Direct-style eval migration spec — Direction C

> **Status**: SPEC ONLY — no code changes permitted until this document
> is reviewed and the capability flag `SSC_DIRECT_EVAL` gating strategy
> is agreed.  See `BACKLOG.md § direct-style-eval` and
> `~/.claude/plans/noble-discovering-knuth.md § Direction C`.

---

## 1  Motivation

The interpreter's `eval(term, env, interp): Computation` signature wraps
every return in a `Computation.Pure` allocation.  On the hot path a
simple `1 + 1` expression traverses:

```
evalCore → Pure(IntV(2))   # allocation
flatMap   → k(IntV(2))     # continuation call  
Pure(IntV(2))              # final allocation
```

The `Computation` free-monad exists for one reason: algebraic effects
require the ability to suspend a computation (via `Perform`) and later
**resume** it with a value — possibly more than once (multi-shot).  The
trampoline guarantees stack safety across arbitrarily long bind chains.

For effect-free expressions (the vast majority of hot code paths) every
`Pure` allocation is pure overhead.  JFR profiling (2026-06-02, see
`docs/interpreter-perf-findings-2026-06.md`) confirms `Pure` object
construction shows up in allocation profiles for CPU-bound benchmarks.

**Goal**: replace the `Computation` return type in the pure-path fast
lane with a direct `Value` return, keeping the monadic trampoline only
where effects are genuinely in scope.

---

## 2  Threat model — multi-shot continuations

Multi-shot handlers call `resume` more than once with different values.
Example (ScalaScript):

```scalascript
effect Choice:
  def pick(xs: List[Int]): Int

def search(f: Int => Boolean): List[Int] =
  handle f(Choice.pick(List(1, 2, 3))):
    case Choice.pick(xs) => resume => xs.flatMap(v => resume(v))
```

Each `resume(v)` re-enters the suspended continuation of `f` from the
point of the `perform`.  This is sound only because `Perform` captures
the **entire remaining computation** as a `k: Value => Computation`
closure, and the trampoline can call `k` repeatedly.

A direct-style rewrite that encodes suspension via a thrown exception
(CPS-exception model) inherits the **one-shot** limitation of exception
handlers: a `catch` block can only recover once.  Multi-shot resumes
require either:

- Capturing a **delimited continuation** (requires JVM `Continuation`
  or a CPS transform), or
- Keeping the trampoline intact for multi-shot handlers.

The `multishot-stack` worktree (referenced in `BACKLOG.md`) explored
stack-capturing continuations.  Its findings must be incorporated before
any code change.

**Constraint**: the direct-style fast path is restricted to
**one-shot-or-no-effect** code.  Any site that may encounter a
`Perform` node at runtime must remain on the monadic path.

---

## 3  Capability flag and phased migration strategy

### 3.1  Flag

Environment variable `SSC_DIRECT_EVAL` (default: `off`).  The
interpreter checks it once at startup and stores the result as
`interp.directEvalEnabled: Boolean`.  When `off`, behavior is
identical to today.

### 3.2  Dual-entry-point pattern

Rather than changing the existing `eval` signature (which would require
updating all 530+ call sites simultaneously), introduce a parallel
fast-path entry point:

```scala
// Existing — unchanged
def eval(term: Term, env: Env, interp: Interpreter): Computation

// New fast path — returns Value; throws EffectPerform on Perform
def evalDirect(term: Term, env: Env, interp: Interpreter): Value
```

`evalDirect` throws a lightweight `EffectPerform(Computation)` exception
when it encounters a `Perform` node.  The caller catches this, hands the
`Computation` to the normal monadic trampoline, and continues.

```scala
// Call-site idiom once a site is migrated:
try
  val v = evalDirect(term, env, interp)
  // pure path — no allocation
  continuation(v)
catch
  case EffectPerform(c) =>
    c.flatMap(continuation).run(interp)   // monadic fallback
```

### 3.3  Per-file migration order

Migrate in dependency order — inner utilities first, outer dispatch
last.  This lets each file's tests pass independently before the next
layer is touched.

| Priority | File | Sites (approx.) | Notes |
|---|---|---:|---|
| 1 | `EvalRuntime.scala` | 230 | Core hot path; largest leverage |
| 2 | `BlockRuntime.scala` | 41 | Small; mostly sequential statement lists |
| 3 | `PatternRuntime.scala` | 37 | Pattern match bodies |
| 4 | `CallRuntime.scala` | 15 | Function application |
| 5 | `DispatchRuntime.scala` | 89 | Method dispatch; many effect-adjacent sites |
| 6 | `EffectHandlers.scala` | 34 | Keep monadic; only adapt call sites |
| 7 | Others | ~84 | `FastTier`, `TcoRuntime`, `AsyncRuntime`, … |

Total: ~530 sites.

Within `EvalRuntime`, migrate leaf expressions first
(`Lit`, `Term.Name`, `Term.Apply` of pure builtins), then compound
expressions (`Term.If`, `Term.Match`, `Term.Block`), then effect
boundaries last.

### 3.4  Effect-boundary detection

A call site can use `evalDirect` when it can statically guarantee no
`Perform` node will be emitted.  Three categories:

1. **Literal expressions** — `Lit.*`, `Term.Tuple` with literal args:
   always safe.
2. **Pure-path builtins** — arithmetic, string ops, collection
   constructors: safe when the fast-path dispatcher does not call
   `eval` recursively on untyped user terms.
3. **User-defined function calls** — unsafe unless the function is
   marked `@pure` (not yet implemented) or the call is inside a
   known-effect-free scope.

Initially gate `evalDirect` on literal expressions only.  Expand the
safe set incrementally, verified by the test suite with
`SSC_DIRECT_EVAL=on`.

---

## 4  Effect handling design

### 4.1  Rejected: pure CPS-exception model

Encoding all suspension as thrown exceptions eliminates the trampoline
but sacrifices multi-shot continuations.  `Choice`-style handlers and
any handler that calls `resume` more than once would silently fall back
to incorrect behavior (only the last resume branch would execute).

Rejected until ScalaScript removes multi-shot handlers from the
language spec.

### 4.2  Preferred: hybrid — direct fast path + trampoline fallback

The `evalDirect` / `EffectPerform` design in §3.2 preserves the full
trampoline for all effect-bearing code while eliminating allocations on
the pure path.  This is compatible with multi-shot continuations because
the monadic fallback is unchanged.

Implementation:

```scala
final class EffectPerform(val comp: Computation) extends Exception with
  scala.util.control.NoStackTrace
```

`NoStackTrace` eliminates the JVM cost of stack-frame capture.  The
exception is thrown at most once per `evalDirect` call (on the first
`Perform` encounter); if the expression is pure, no exception is thrown
and the allocation saving applies.

### 4.3  Alternative: Loom virtual threads (deferred)

JDK 21+ `VirtualThread` + `Thread.currentThread().interrupt()` can
implement delimited continuations natively.  This would enable full
direct-style *with* multi-shot, but requires targeting JDK 21+ and a
larger architectural change.  Deferred to a future cycle.

---

## 5  Multi-shot compatibility verification

Before any code lands, verify the following against the `multishot-stack`
worktree's test fixtures:

1. `SSC_DIRECT_EVAL=on` must not change the output of any test in
   `scalascript.SscTest` or `scalascript.SscVmTest`.
2. Multi-shot handler tests (files matching `*effect*`, `*multishot*`,
   `*choice*` in `ssc-tests/`) must pass with no change in semantics.
3. Run `sbt "backendInterpreter/testOnly *Effects*"` — zero regressions.
4. If any multi-shot test fails, the affected call sites must remain on
   the monadic path and be excluded from the `evalDirect` migration.

---

## 6  Verification protocol for each migration batch

1. `sbt "backendInterpreter/test"` — all tests pass with
   `SSC_DIRECT_EVAL=off` (baseline unchanged).
2. `SSC_DIRECT_EVAL=on sbt "backendInterpreter/test"` — all tests pass
   with the fast path enabled.
3. `scripts/bench interp` — no regression on locked benchmarks
   (`recursiveEval`, `nestedMatchExpr`, `refFieldArg`, `instanceFieldAccess`).
4. `SSC_DIRECT_EVAL=on scripts/bench interp` — numeric improvement vs.
   `SSC_DIRECT_EVAL=off` (measure before landing each batch).
5. JFR profile `SSC_DIRECT_EVAL=on` vs `off` for the `recursiveEval`
   bench to confirm `Pure` allocations decline.

---

## 7  Migration scope exclusions

The following files must **not** be migrated in the initial phase:

| File | Reason |
|---|---|
| `EffectHandlers.scala` | Resume closures are inherently multi-shot |
| `EffectsRuntime.scala` | Effect dispatch and handler state |
| `AsyncRuntime.scala` | `Future`-backed async; different suspension model |
| `ActorInterp.scala` | Message passing; inter-actor effects |

These files contain `Perform` at call sites that are structurally
incompatible with single-shot exceptions.  They may be migrated in a
later phase under the Loom virtual-thread model (§4.3).

---

## 8  Success criteria

- `SSC_DIRECT_EVAL=on`: `recursiveEval` bench improves ≥ 20% vs. off.
- Zero test regressions across 1230+ tests.
- Multi-shot effect handlers produce identical output under both flags.
- `Pure` allocation count in JFR drops ≥ 50% for the pure-expression
  hot path.

---

## 9  Open questions (must resolve before coding)

1. **`multishot-stack` worktree findings** — has the worktree been
   reviewed?  Are there test cases that currently pass only because the
   trampoline preserves all resume branches?  Read the worktree's
   findings and link them here.

2. **`@pure` annotation** — should ScalaScript add a `@pure` annotation
   for user functions to widen the `evalDirect` safe set?  Defer to a
   separate spec.

3. **Double-slot interaction** — `Value.DoubleV` unboxing
   (`longBitsToDouble`) interacts with the JIT.  Verify the JIT still
   fires correctly on the direct-style path.

4. **Effect scope propagation** — when a `with` block introduces an
   effect handler, inner `eval` calls must use the monadic path.  The
   scope entry point is `EffectsRuntime.withHandler`.  Verify that
   `evalDirect` is suppressed for the duration of any handler scope.

---

## 10  §9 resolution + go/no-go (p0, 2026-06-12)

Investigated `direct-style-eval-p0`. Each open question answered, plus a
material finding that re-scopes the whole effort.

### 10.1  Q1 — multi-shot continuations (the load-bearing constraint)

The `multishot-stack` worktree is gone, but its conclusion is captured in
commits `e29c5b182` ("multi-shot handler dispatch stays eager") and `deed8fce9`.
The live mechanism (`EffectsRuntime.handleInterp`): a multi-shot `resume` is a
`Value.NativeFnV` that **re-runs `handleInterp(f(v))` on each call**, where
`f: Value => Computation` is the continuation captured by the trampoline's
`FlatMap(Perform(...), v => handleInterp(f(v)))`. So multi-shot correctness
**depends on the monadic continuation being a re-callable closure**.

Implication for direct-style: the `EffectPerform` exception model is **one-shot
by nature** (a `catch` recovers once) AND it loses the surrounding direct-style
stack (`a + evalDirect(b)` — if `evalDirect(b)` throws, `a + _` is unwound and
gone). Therefore `evalDirect` must **never** intercept a `Perform` that needs
resumption. The only sound rule: **`evalDirect` is applied only to expressions
statically proven effect-free** (no reachable `Perform`); `EffectPerform` is a
*safety net* (should never actually throw if the analysis is sound), not a
control-flow device. **§3.4 effect-boundary detection is load-bearing, not
optional** — without it, both multi-shot and ordinary surrounding continuations
break.

### 10.2  Q3 — double-slot / JIT: no interaction (and the big finding)

The bytecode JIT **already returns a `Value` directly** — `JitRuntime`:
`r.mh.invoke().asInstanceOf[AnyRef]` produces the Value, wrapped in `Pure`
exactly **once** at the call boundary, not per sub-expression. The JIT path is
therefore *already direct-style*; `evalDirect` is a **tree-walk-only** change and
does not touch the JIT, so the `DoubleV` double-slot concern is moot (it lives on
the JIT path, untouched).

**Consequence (re-scopes the project):** the per-`Pure` overhead direct-style
targets is **already eliminated on every function/expression the JIT compiles** —
including tight numeric loops, simple recursion, AND recursive ADT interpreters.
Verified: in `recursiveEval` (the spec's own §8 success metric) `eval` and `build`
both lint **JIT OK**; only the outer `workload` loop tree-walks. JIT on = 4.6 ms,
off = 52 ms (11×). So direct-style's ceiling on `recursiveEval` is just the outer
loop's handful of `Pure`s per iteration — **nowhere near the claimed ≥20%**. The
spec (2026-06-02) predates the JIT covering this shape.

### 10.3  Q4 — effect scope: handled by boundary detection

`evalDirect` must be suppressed inside any active handler scope. The entry point
is `EffectsRuntime.withHandler`; a `with`/`handle` region is marked effectful by
the same §3.4 analysis, so `evalDirect` is simply never selected there. No extra
mechanism needed beyond boundary detection. Q2 (`@pure`): deferred, as written.

### 10.4  Go / no-go

**Conditional GO — but NOT the spec's framing, and NOT yet the 530-site
migration.** The remaining genuine win is **hot *tree-walked* code the JIT bails
on** — closure/HOF-heavy, method-dispatch-heavy, effect-adjacent business logic
(the bulk of real apps), where per-`Pure` allocation is pervasive. That is also
the riskiest surface (effect boundaries), so boundary detection must come first.

Recommended sequencing change:
1. **p1 = infra + a measurement SPIKE first.** Build `evalDirect` + `EffectPerform`
   behind `SSC_DIRECT_EVAL`, migrate ONE representative *tree-walked* hot path
   (not `recursiveEval` — it JITs), and measure the real `Pure`-allocation delta
   (JFR) + wall-clock. **Gate the full migration on the spike showing a
   worthwhile win (target ≥15% on a tree-walked workload, or ≥50% `Pure` drop
   there).** If the spike underperforms, DEFER — the JIT already captured the easy
   wins and the migration's cost/risk would not pencil out.
2. **Fix the success metric (§8):** `recursiveEval` is invalid (it JITs). Replace
   with a closure/dispatch-heavy tree-walked workload, or measure with
   `SSC_JIT_BYTECODE=off` to isolate the tree-walk path the change actually
   touches.
3. **§3.4 effect-boundary detection is a prerequisite of p2**, not a late step.

Net: the architecture in §3–§4 is sound, but its value is narrower and its metric
is wrong because the JIT matured past the 2026-06-02 premise. Proceed via a gated
spike, not a blind 530-site migration.

## 11  p1 spike result — **DEFER** (2026-06-12)

Followed the **JFR-profile-first** methodology (cheap gate before building any
`evalDirect` infra): profile the `Computation.Pure` allocation *fraction* on the
representative **tree-walked** HOF/dispatch workload (`typeclassFoldMacro` — its
`combineAll` cannot JIT, so it tree-walks even with the JIT on). `scripts/bench
profile typeclassFoldMacro` → 1.4 ms/op, ~132 KB/op. JFR `ObjectAllocationSample`
weight breakdown:

| class | ~% of alloc |
|---|---|
| `scala.collection.immutable.::` (List cons) | ~22% |
| **`Computation$Pure`** | **~18%** |
| `DispatchRuntime$$Lambda` | ~18% |
| `scala.Some` | ~17% |
| `scala.Tuple2` | ~13% |
| `byte[]` | ~8% |

**`Computation.Pure` is a MINORITY (~18%) of allocation, dwarfed by the dispatch
machinery** (Lambda + Some + Tuple2 + List ≈ 70%). This confirms §10.2 empirically:
the JIT already captured every *high*-`Pure`-fraction shape (numeric loops, simple
recursion, ADT interpreters — they JIT and wrap `Pure` once at the boundary); the
shapes that still tree-walk are *dispatch*-heavy, where `Pure` is a small slice and
the real cost is the generic-HOF dispatch tree-walk (CPU + the Lambda/Some/Tuple2/List
allocation it produces) — which `evalDirect` does **not** touch.

**Decision: DEFER.** `evalDirect` would drop `Pure` ~100% on effect-free paths (meeting
the spec's literal "≥50% `Pure` drop" sub-criterion), but that is ≥50% of an ~18% slice
— the wall-clock ceiling is well below the "≥15% on a tree-walked workload" target,
while the migration is HIGH risk (530 sites, load-bearing §3.4 effect-boundary
detection). The cost/risk does not pencil out. The honest win for these tree-walked
dispatch shapes is **JIT-compiling the generic HOF dispatch** (see
[[project_backend_improvement_program]] / typeclass-fold devirt notes), not direct-style
eval. Caveat: the allocation fraction is a proxy — it omits the per-`Pure` wrap/unwrap
CPU — so a full on/off wall-clock spike could refine the number; but the bounded ceiling
(`Pure` ≪ dispatch) plus the HIGH migration risk justify deferring without building
speculative infra (per the user's JFR-profile-first rule). Revisit only if a real
workload surfaces where `Pure` dominates a *tree-walked* path.

## 11.1  Re-validation on the current codebase — **DEFER stands** (2026-06-13)

Re-ran the gated profile (`scripts/bench profile typeclassFoldMacro`, the representative
tree-walked HOF/dispatch workload) on the current `origin/main` after a request to take the
migration off the shelf. **1.30 ms/op, ~132 KB/op** — unchanged from the 2026-06-02 / -12
numbers. Fresh JFR `jdk.ObjectAllocationSample` counts (≈480 samples):

| class | ~% |
|---|---|
| `scala.Some` | 24% |
| **`Computation$Pure`** | **~16%** |
| `scala.collection.immutable.::` | 16% |
| `DispatchRuntime$$Lambda` | 16% |
| `byte[]` | 10% |
| `EvalRuntime$$Lambda` + `Tuple2` | 10% |

`Computation.Pure` is **~16% of allocation**, the dispatch machinery (`Some` + the two
`Lambda`s + `Tuple2` + `::` ≈ **66%**) dominates — identical conclusion to §11. `evalDirect`
would zero the `Pure` slice on effect-free paths but leaves the 66% dispatch cost untouched;
the wall-clock ceiling is well under the ≥15% gate against a 530-site, high-risk migration.
**DEFER recommendation stands.** The real win for these shapes is JIT/devirt of the generic
HOF dispatch (the `Some`/`Lambda`/`Tuple2`/`List` producers), not direct-style eval. If
interpreter perf is the goal, pursue HOF-dispatch devirtualization instead. (Surfaced to the
user as a direction decision: proceed with the 530-site migration anyway / accept the
data-driven defer / redirect to HOF-dispatch devirt.)

## 11.2  CPU profile — resolves the §11 "proxy" caveat — **DEFER confirmed** (2026-06-13)

§11 flagged one residual uncertainty: the allocation fraction omits the per-`Pure`
**wrap/unwrap + `FlatMap` trampoline CPU**, which a CPU (not allocation) profile would reveal.
Two independent results now close it:

1. **Empirical proof the workload is CPU-bound, not alloc-bound** (`hof-dispatch-devirt`,
   2026-06-13): eliminating the dominant-by-sample-count `Some` allocation left
   `gc.alloc.rate.norm` (132 KB/op) **and** wall-clock (1.30 ms/op) **unchanged**. So removing
   `Pure` *allocation* would likewise move wall-clock by ~0.

2. **CPU profile of `typeclassFoldMacro`** (JFR `jdk.ExecutionSample`, 186 samples): leaf-frame
   CPU is **79% `EvalRuntime.evalCore`** (the term-dispatch match), and the `Computation`
   trampoline machinery direct-style-eval would remove (`callValue` + `callValue1Slow` +
   `Interpreter.callValue`) is **~2%** of CPU. (96% of *stacks* mention `Computation` only
   because every eval *returns* one — almost no CPU is spent *in* the trampoline.)

So even counting the trampoline CPU the proxy missed, direct-style-eval's ceiling is **~2%**,
≪ the ≥15% gate, against a 530-site, high-risk migration. **DEFER is the definitive,
fully-measured verdict** (allocation *and* CPU). The interpreter CPU lives in `evalCore`'s
term dispatch — which direct-style-eval does not change — so the win is `hof-dispatch-cpu-devirt`
(devirt/JIT the dispatch the tree-walk does inside `evalCore`), not a `Computation`→`Value`
signature migration.

## 11.3  `hof-dispatch-cpu-devirt` investigated — **no targeted devirt win; DEFER → BACKLOG** (2026-06-13)

Took §11.2's redirect (`hof-dispatch-cpu-devirt`) off the shelf and measured it directly on
`typeclassFoldMacro` (`combineAll[A: Monoid]` = `xs.foldLeft(empty)(combine)`, 300× over a
10-element list; baseline **1.286 ms/op**). Findings:

1. **The inner `combine` is already JIT-compiled.** JIT on/off A/B: **1.256 ms (on)** vs
   **3.795 ms (off)** — a 3× win. The bytecode JIT compiles `def combine(a,b) = a+b` to a
   `LongFn2` and dispatches via `invokeBytecode2` (no tree-walk for the 3000 inner calls).
   `SSC_JIT_STATS=1` reports **no bails** for this workload.

2. **The residual is `evalCore` self-time, not a devirtualizable callee.** Fresh JFR
   `jdk.ExecutionSample` (189 samples, stack-depth 1): **147 (78%) leaf = `EvalRuntime.evalCore`**
   — the megamorphic `term match` dispatch itself. No callee is hot: `entryFor`/`synchronized`,
   `trackPos`, `resolveGiven`, `JitGlobals.withInterp`, `dispatch*` each appear 0–2× as leaves.
   The 78% is the 300× re-walk of `combineAll`'s HOF glue (the `foldLeft` Apply, the two
   `summon[Monoid[A]].{empty,combine}` Selects) + the `macroFold` loop body — all flowing
   through `evalCore`'s ~50-arm match.

3. **Every targeted micro-lever measured 0%.** (a) `trackPos` no-op (eliminate the per-eval
   `tree.pos` read): **1.250 vs 1.256 ms** — no change. (b) Cache the JIT `Entry` on `FunV`
   to remove the `synchronized` IdentityHashMap lookup `entryFor` does on *every* dispatch
   (`bytecodeFor`/`hotCompiled`): **fib 1.219 vs 1.216, fold 1.253 vs 1.256** — no change
   (HotSpot's uncontended lock + identity-get is in the noise). Both reverted (the prior
   lesson: don't ship non-improvements as wins).

**Verdict: there is no targeted ≥15% devirt win.** The per-call dispatch machinery is already
cached/JIT'd; the cost is the irreducible megamorphic term-match in a >1000-line `evalCore`,
only reducible by a *different dispatch mechanism* (tagged-switch / compiled-AST) or by
**compiling the HOF glue itself** — i.e. JIT-compiling `combineAll`'s `foldLeft`-with-a-runtime-
monoid (`combineAll` bails the bytecode/VM JIT on the `foldLeft` HOF call: the known
`call:no-compilable-target` gap). That is a large architectural effort (CALLREF opcode / HOF
inlining / the dual-bank `LExpr` roadmap), **not** a quick devirt slice. Moved to **BACKLOG**
under that framing.
