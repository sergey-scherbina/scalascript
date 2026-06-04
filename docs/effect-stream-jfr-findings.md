# JFR Investigation: `effect-stream` — Root Cause Report

**Date:** 2026-06-04  
**Task:** `effect-stream-jfr`  
**Status:** Root cause confirmed; concrete optimisation candidates identified.

---

## Workload

```
# bench/corpus/effect-stream.ssc
def workload(): Int =
  val (src, _) = runStream {
    var i = 0
    while i < 10000 do
      Stream.emit(i)
      i = i + 1
  }
  val lst = src.runToList()
  lst.length
```

Inner loop: 10 000 `Stream.emit(i)` calls inside a `runStream { }` handler.

---

## Numbers

| Benchmark | ms/op (JMH) | B/op |
|---|---|---|
| `effectStream` | 28.465 ± 2.1 | 3,453,200 |
| `effectPure`   |  0.015 ± 0.0 |    37,232 |
| ratio          | **1897×** | **92.7×** |

`effectPure` runs `while i < 10000 do Logger.info("x")` inside `runLogger {}` — structurally identical except the body has a single effect call; there is no accumulation.  The 1897× gap isolates cost to the `runStream` handler + allocation pressure.

Wall-clock (bench.sh): `effect-stream` 30.1 ms vs `effect-pure` 0.016 ms.  JMH
numbers are consistent.

---

## Profiling Methodology

```bash
scripts/bench profile effectStream    # JMH -prof gc -prof "jfr:configName=profile"
```

JFR file: `runtime/backend/interpreter-bench/scalascript.bench.InterpreterBench.effectStream-AverageTime/profile.jfr`

GC summary extracted from JMH `-prof gc` output:

```
·gc.alloc.rate          5,950 ± 600 MB/s
·gc.alloc.rate.norm     3,453,200 ± 200 B/op
·gc.count               25 counts
·gc.time                145 ms   (≈ 5.8 ms / iter)
```

5.8 ms GC per 28.5 ms iteration = **20% GC overhead**.  The remaining 22.7 ms
is trampoline execution = 2.27 µs per `Stream.emit` call.

---

## Allocation Profile (JFR CPU samples, allocation-event view)

| Type | JFR samples | % |
|---|---|---|
| `Computation$FlatMap`  | 583 | 42.2% |
| Lambda closures (SAM)  | 334 | 24.2% |
| `scala.collection.immutable.$colon$colon` | 201 | 14.6% |
| `Computation$Perform`  | 141 | 10.2% |
| `Computation$Pure`     | 123 |  8.9% |

Cross-check: 3,453,200 B / 10,000 iterations = **345 bytes per emit call**.
At ~40–48 bytes per JVM object (object header + fields), that is **7–8 objects
per `Stream.emit` call** — consistent with the sample distribution above.

---

## Root Cause: Free Monad Trampoline Allocates ≈ 8 Objects per Emit

### How the while body is evaluated

The ScalaScript interpreter evaluates the while body one iteration at a time as
a `Computation` (Free Monad) tree.  For each iteration of
`while i < 10000 do Stream.emit(i); i = i + 1`, the eval path produces:

```
FlatMap(
  Perform("Stream", "emit", List(IntV(i))),   -- 1 Perform + 1 List cons + 1 IntV
  _unit => FlatMap(                            -- 1 FlatMap + 1 lambda
    Pure(IntV(i+1)),                           -- 1 Pure
    _next => <loop continuation>               -- 1 FlatMap + 1 lambda
  )
)
```

Per emit: **1 Perform + 1 List cons + 2 FlatMap + 2 lambdas + 1 Pure = 7–8 objects**.

### The re-association step amplifies FlatMap allocations

`EffectHandlers.streamRun.go()` uses the standard Free Monad trampoline.  When
it encounters `FlatMap(FlatMap(s2, g), f)`, it re-associates:

```scala
case FlatMap(s2, g) => cur = FlatMap(s2, x => FlatMap(g(x), f))
```

Each re-association creates **1 new FlatMap + 1 new lambda**, which is why
`Computation$FlatMap` is the single dominant allocation (42%).  With two levels
of nesting per emit (the emit Perform + the loop counter update), each
iteration triggers at least one re-association.

### Why `effectPure` allocates 92× less

`runLogger` handles a single-level body: `Logger.info(msg)` produces
`Perform("Logger","info",args)` with no inner sequential composition.  The
trampoline sees `FlatMap(Perform(...), f)` directly — no re-association, no
nested FlatMap, no extra lambda.  One FlatMap + one Perform per loop iteration
vs seven in `effectStream`.

---

## Optimisation Candidates

### OPT-1: `Perform1(effect, op, singleArg: AnyRef)` — eliminates List cons

`Perform("Stream", "emit", List(v))` boxes `v` into a 1-element list on every
call.  A specialised `Perform1` case class that carries the argument directly
avoids 1 allocation per emit:

```scala
sealed trait Computation
case class Perform1(effect: String, op: String, arg: AnyRef) extends Computation
```

`NativeFnV` for `Stream.emit` emits `Perform1("Stream","emit",arg)` instead of
`Perform(...)`.  Every handler switches on the new case.

**Savings:** 1 list cons per emit = ~48 bytes → from 345 B/op to ~300 B/op.
**Complexity:** Medium — requires changes to all handlers in `EffectHandlers`.

### OPT-2: Detect `while … Stream.emit(expr)` in `EvalRuntime` (FastTier)

When `EvalRuntime.evalCore` encounters a `runStream { while i < N do Stream.emit(expr); i = i + 1 }` pattern, it can compile the body to a tight buffer-fill loop:

```scala
val buf = ListBuffer.empty[Value]
var i   = 0
while i < n do
  buf += evalPure(emitExpr, env, interp)
  i += 1
Pure(Value.TupleV(Value.ListV(buf.toList) :: Value.UnitV :: Nil))
```

This bypasses the Free Monad trampoline entirely for the common push-stream pattern.
**Savings:** All 8 allocations → near-zero (only `Value` wrapper for result).
**Complexity:** High — requires syntactic pattern detection in `EvalRuntime`.

### OPT-3: Reuse the while-continuation lambda across iterations

Currently the while-body eval creates a fresh closure on every iteration to
capture the continuation `_ => loopAgain`.  If the closure is pure (no mutable
captured state), it can be created once and reused.  This eliminates the 2
lambda allocations per iteration that `FlatMap` re-association creates.

**Savings:** ~2 lambdas × 40 bytes = ~80 bytes → from 345 B/op to ~265 B/op.
**Complexity:** Medium — requires identifying "closure-stable" while continuations.

### OPT-4: Direction C — direct-style eval (long-term)

Direction C replaces the Free Monad trampoline with direct-style evaluation
using a mutable effect handler stack.  Effect `perform` operations suspend to
the nearest handler frame on the stack rather than building a `FlatMap` tree.
Zero per-step allocations; effect handling becomes a stack push/pop.

This is the fundamental fix.  It is tracked as `direct-style-eval-spec` in
WORK_QUEUE and requires a full interpreter architecture change.

---

## Priority Assessment

| Candidate | Complexity | Savings | Recommended |
|---|---|---|---|
| OPT-1: `Perform1` | Medium | ~14% fewer B/op | Yes — safe, incremental |
| OPT-2: FastTier stream-while | High | ~100% | Yes — high ROI |
| OPT-3: Closure reuse | Medium | ~23% fewer B/op | Maybe — tricky to do safely |
| OPT-4: Direction C | Very High | ~100% | Long-term only |

**Recommended starting point:** OPT-2 (FastTier `while … emit` detection) for
maximum speedup on the canonical streaming pattern, plus OPT-1 as a
low-risk foundation improvement regardless of which path is taken.

---

## Conclusion

The 1897× slowdown is fully explained by **Free Monad trampoline allocation
overhead**: approximately 7–8 JVM object allocations per `Stream.emit` call,
dominated by `FlatMap` re-association (42%), lambda closures (24%), and the
1-element `List` wrapper for `Perform` arguments (15%).  GC pressure adds a
further 20% overhead (5.8 ms/iter).

The streaming effect handler itself (`EffectHandlers.streamRun.go`) is
correct and efficient given the trampoline model.  The allocation cost is
structural — inherent to the Free Monad encoding — not a bug in the handler.

The fastest path to a concrete improvement is **OPT-2: FastTier detection of
`while … Stream.emit`**, which eliminates the trampoline entirely for this
pattern.  OPT-1 (`Perform1`) is a good preparatory cleanup regardless.
