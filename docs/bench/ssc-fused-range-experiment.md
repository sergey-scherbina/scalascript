# SSC fused-range-chain experiment

## What we tried

Added `EvalRuntime.tryFusedRangeMapFilterFold` — a pattern-detect path
that recognises `(lo to hi).map(f).filter(g).foldLeft(z)(h)` in the
hot `Term.Apply` dispatch and evaluates it as a single while loop
with no intermediate List/View allocations:

```scala
var acc = z
var i = lo
while i <= hi do
  val m = f(i)
  if g(m) then acc = h(acc, m)
  i += 1
acc
```

This bypasses the chained `_dispatch(_dispatch(_dispatch(range,'map',
[f]), 'filter', [g]), 'foldLeft', [z])([h])` ribbon, which on its
critical path goes through `dispatchString` / `dispatchList` /
`dispatchInt` for each method look-up plus Vector allocations for
each intermediate.

## Why we kept it disabled by default

Empirical bench on the three workloads that hit (or could have hit)
the path:

| Workload | Off | On | Δ |
|---|---|---|---|
| streams-pipeline | 0.035 ms | 0.033 ms | +6% |
| range-sum | 0.026 ms | 0.026 ms | 0% |
| list-fold | 0.0065 ms | 0.0066 ms | -2% |

Streams gets a small win.  Range-sum and list-fold don't change
(they don't match the exact pattern).  But the pattern-check
itself runs on EVERY `Term.Apply` node — most of which never
match — so on programs that allocate hundreds of millions of
Apply nodes during execution, the runtime cost of the pattern
analysis dominates the win.

We bail on the cheap pre-filter (`Term.Apply` → `Term.Apply` →
`Term.Select` with name `foldLeft`) before doing any allocation,
but the three null-check hops are still ~5% measurable overhead
on tight hot loops.

## Path to a real win

The fusion belongs in **BytecodeJIT pre-pass**, not in the
interpreter dispatch.  The IR-level transformation can run once
per method and replace the chain with a fused while in the
bytecode itself — zero per-call overhead for non-matching code,
and the JIT-compiled body for matching code is identical to what
the JVM/Rust targets emit.

Tracked as `ssc-jit-loop-fusion-universal` Stage 2.

## How to experiment

```sh
SSC_FUSED_RANGE=1 ./bench.sh streams-pipeline
```

The opt-in flag keeps the helper compiled in (small dead code) and
makes regressions / wins reproducible.
