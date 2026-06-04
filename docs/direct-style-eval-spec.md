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
