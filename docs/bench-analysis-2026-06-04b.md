# Benchmark Analysis — 2026-06-04 (session 2)

JS backend optimizations from the `js-bench-honest-foreach` feature branch.
Three improvements: bench DCE barrier (Part 0), stdlib direct-call fast-path
(Part B), foreach literal-arrow inlining (Part A), plus `emptyParamFns` bonus.

## Results (wall-clock bench.sh, ms/iter)

Baseline from session 1 (after `js-pattern-match-dispatch` tag-switch landed):

```
effect-stream:       interp 0.090   jvm 0.064   js 0.257  ← Part B target
pattern-match-heavy: interp 0.057   jvm 0.638   js 3.14   ← Part A target
tuple-monoid:        interp 0.006   jvm 0.000   js 0.025  ← Part 0 (honest sink)
arith-loop:          interp 0.270   jvm 0.265   js 0.589
```

After this session:

```
effect-stream:       interp 0.091   jvm 0.064   js 0.018  ← 14× JS improvement
pattern-match-heavy: interp 0.051   jvm 0.605   js 2.90   ← ~8% JS improvement
tuple-monoid:        interp 0.005   jvm 0.000   js 0.026
arith-loop:          interp 0.273   jvm 0.251   js 0.589
```

## What landed

### Part 0 — Bench honesty (DCE barrier)

`BenchCmd.generateWrapper` now emits `var _ssc_sink: Any = null` and wraps
every `workload()` invocation in `_ssc_sink = workload()`.  Prevents HotSpot
and V8 from DCE-ing the loop result.

`tuple-monoid` JVM still reports 0.000 ms because JvmGen constant-hoists the
`(1,2)++(3,4)` out of the while loop before HotSpot even sees it — this is a
legitimate backend optimization, not DCE.  Same for interp (tryFoldCounterLoop).
The note is documented in `bench/corpus/tuple-monoid.ssc`.

### Part B — Stdlib singleton direct-call fast-path

Known stdlib singleton receiver methods (`Stream.emit`, `Stream.complete`, etc.)
now lower to `Stream.emit(x)` instead of `_dispatch(Stream, 'emit', [x])`.
Eliminates per-iteration `[x]` array allocation + `_dispatch` walk.

`effect-stream`: 0.257 → 0.018 ms (**14×**).

### Part A — `xs.foreach(p => body)` inlining

`inlineForeachOrGenStat` intercepts `xs.foreach(literal-arrow)` calls inside
`genWhileBodyInline`.  When the receiver is an Array (ScalaScript `List`),
emits a flat:

```js
const __t0 = xs; if (Array.isArray(__t0)) {
  for (let __t1 = 0; __t1 < __t0.length; __t1++) { const p = __t0[__t1]; body; }
} else { _forEach(__t0, (p) => { body; }); }
```

No closure allocated; V8 can inline the body.  Full preamble (~1570 lines)
limits V8's inlining budget in real benches vs. isolated tests (isolated:
1.87 ms vs. full-preamble: 2.90 ms).

`pattern-match-heavy`: 3.14 → 2.90 ms (~8%).

### Bonus — `emptyParamFns` direct call for `def f(): T`

`def f(): T` (one explicit empty param clause) was not in `funcParamOrder`
(guarded by `params.nonEmpty`) nor `zeroParamFns` (requires NO param clause).
New `emptyParamFns` set tracks these; `genApply` emits `f()` instead of `_call(f,)`.
Eliminates ~0.33 ms overhead per bench harness `workload()` invocation.

## Remaining JS gaps

| Workload              | interp | jvm   | js    | gap vs best |
|-----------------------|-------:|------:|------:|------------:|
| `arith-loop`          | 0.273  | 0.251 | 0.589 | 2.3× jvm    |
| `pattern-match-heavy` | 0.051  | 0.605 | 2.90  | 57× interp  |
| `tuple-monoid`        | 0.005  | 0.000 | 0.026 | structural  |
| `effect-stream`       | 0.091  | 0.064 | 0.018 | **JS leads!** |

`effect-stream` JS is now 3.5× faster than interp, 3.5× faster than JVM.
`pattern-match-heavy` residual gap is V8 preamble budget limiting `area()` inlining.
`arith-loop` gap is structural (V8 float64 vs JVM int64).
