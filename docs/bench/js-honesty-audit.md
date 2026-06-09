# JS bench honesty audit

Same methodology as the Rust audit (`rust-honest-disassembly.md`):
run each suspect workload, capture `BENCH_SINK` (the `+=`-accumulator
of every iteration's workload result), divide by the expected
single-call result, and check that the quotient matches the
iteration count the harness believed it did.

If the wrapper says "I ran N iters in T seconds" and the sink
equals "N × workload_result", every iteration actually executed.
If the sink equals "1 × workload_result", V8 collapsed the outer
loop the way HotSpot had done.

## Results

| Workload | Expected | Sink | Quotient | Iters (harness) | Verdict |
|---|---|---|---|---|---|
| streams-pipeline | 36 | 29_491_200 | 819_200 | 819_200 | ✓ honest |
| typeclass-monoid | 6 | 9_830_400 | 1_638_400 | 1_638_400 | ✓ honest |
| bool-predicate | 999 | 409_190_400 | 409_600 | 409_600 | ✓ honest |
| either-chain | 45450 | 581_760_000 | 12_800 | 12_800 | ✓ honest |
| option-chain | 44850 | 1_148_160_000 | 25_600 | 25_600 | ✓ honest |
| typeclass-fold | 16500 | 105_600_000 | 6_400 | 6_400 | ✓ honest |

All six quotients exactly match the iteration count the adaptive
loop expanded to.  V8 / TurboFan is NOT folding the outer timing
loop on any of these workloads.  The reported JS times are real
per-iteration costs.

## Why V8 doesn't fold

Dynamic typing.  V8's optimiser has to assume `workload()` could
return any JavaScript value (the type-feedback array only profiles
the first ~100 calls).  The `+=` accumulator runs through the
`_arith('+', ...)` runtime helper to handle string/number mixing,
which TurboFan cannot prove pure.  So even when the workload is
constant-foldable in principle (`(1 to 10).map.filter.fold = 36`),
V8 still emits the loop and evaluates each iter.

The result is that the JS wrapper measures correctly without
needing the JMH-style `AtomicLong.getAndAdd` anti-fold that the
JVM wrapper required.

## Action

None.  JS numbers in the bench table are real measurements.  The
remaining work on JS is genuine perf (the `_arith` indirection
and `_dispatch` method routing dominate hot loops), not bench
infrastructure.
