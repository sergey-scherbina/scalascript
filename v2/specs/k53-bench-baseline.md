# K53 — Benchmark Baseline (post-K47 Array-env)

Captured 2026-06-30 via `scripts/bench interp` (JMH, `avgt 5` iterations, 3 warmup, 1 fork).
Machine: macOS arm64, JDK 21. All times in ms/op, lower is better.

## InterpreterBench — post-K47 baseline

| Benchmark                        | ms/op  | ± error |
|----------------------------------|-------:|--------:|
| `multiVal`                       | 0.528  | ± 0.020 |
| `nestedMatchExpr`                | 0.007  | ± 0.001 |
| `optionChain`                    | 0.002  | ± 0.001 |
| `patternGuard`                   | 0.039  | ± 0.001 |
| `patternMatchHeavy`              | 0.021  | ± 0.001 |
| `patternMatchSet`                | 0.067  | ± 0.002 |
| `patternMatchWide`               | 0.041  | ± 0.001 |
| `pureCallSum`                    | 0.003  | ± 0.001 |
| `pureCallSum2`                   | 0.003  | ± 0.001 |
| `pureCallSumBlock`               | 0.003  | ± 0.001 |
| `pureCallSumIf`                  | 0.253  | ± 0.002 |
| `rangeSum`                       | ~10⁻³  |         |
| `recursionFib`                   | 1.176  | ± 0.035 |
| `recursionFibD`                  | 1.428  | ± 0.011 |
| `recursionFibMul`                | 1.272  | ± 0.059 |
| `recursionFibMulD`               | 1.573  | ± 0.101 |
| `recursionTco`                   | 0.026  | ± 0.001 |
| `recursiveEval`                  | 0.066  | ± 0.005 |
| `recursiveEvalMixed`             | 0.065  | ± 0.002 |
| `refChainArg`                    | 0.040  | ± 0.001 |
| `refFieldArg`                    | 0.042  | ± 0.001 |
| `stringSplit`                    | 0.213  | ± 0.001 |
| `tupleMonoid`                    | 0.007  | ± 0.001 |
| `tupleMonoidVal`                 | 0.007  | ± 0.001 |
| `typeclassFold`                  | 0.005  | ± 0.001 |
| `typeclassFoldMacro`             | 1.350  | ± 0.033 |
| `valIf`                          | 0.405  | ± 0.006 |
| `valIntermediate`                | 0.254  | ± 0.002 |
| `valMatch`                       | 0.478  | ± 0.026 |

## ssct-hm type checker timing (hm-json.hm)

Measured via `time v2/ssct-hm examples/hm-json.hm` (3 runs, best):
- Wall clock: ~3.0 s
- User CPU:   ~0.5 s
- JVM startup accounts for ~2.5 s of wall clock.

The user-CPU time is the meaningful signal. Hot path is the HM unifier + let-poly
instantiation over the ~90-fn prelude + the ~300-node JSON showcase program.

JFR profiling of a scala-cli-launched short-lived JVM is non-trivial (custom agent
attach, sub-second window). No >20% win identified from timing alone → K53(c)
optimization pass deferred (BACKLOG: "profile ssct-hm unifier with JFR and implement
if >20% win visible").

## Comparison to pre-K47 (pre-Array-env) reference

The June-15 BASELINE.md recorded `RuntimeBench` wall-clock numbers, not `InterpreterBench`
JMH microbenchmarks. Direct K47 before/after InterpreterBench comparison is unavailable
(no pre-K47 JMH run recorded). K47 is expected to improve `recursionFib`-class benchmarks
(heavy `Local(i)` lookup) by removing O(i) list traversal; the 1.176 ms `recursionFib`
number is the post-K47 baseline going forward.
