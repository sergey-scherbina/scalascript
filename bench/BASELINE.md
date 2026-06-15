# Benchmark Baseline

Baseline numbers are captured by running:
```
./bench.sh --baseline
```

Workflow manifest: [`bench/perf-manifest.yaml`](perf-manifest.yaml). Developer
smoke command: `ssc bench --smoke` or `scripts/perf-smoke.sh --jmh`.

This requires `ssc` to be built (`sbt cli/stage`).  The median of 7 runs after 2 warmup
runs is recorded.  Each v1.61.N optimization PR must include before/after numbers
against this baseline.

Default benchmark runs are informational. Treat a benchmark as CI-blocking only
when the command line includes an explicit target gate such as
`--target-ms N --require-target`. Raw runtime/JMH result files are generated
artifacts and are ignored; promote stable summaries into this markdown file.

## Wall-clock baselines (`./bench.sh`)

Captured 2026-06-15 with `./bench.sh --reps 20 --warmup-time 800` (all 5 backends, `bin/ssc`
classpath launch). Machine: macOS arm64, JDK 21.0.7; `node`; `scala-cli`; Rust stable `-O3`.
ms/iter, lower is better. `n/a` = backend doesn't support the workload (effects on jvm/rust;
`Array`/`LazyList` on rust; `LazyList.from` on js). `ssc-asm` is the interpreter with the ASM
JIT backend (≈ `ssc` for tree-walked collection/effect workloads).

| Workload                  | ssc (ms/iter) | ssc-asm (ms/iter) | jvm (ms/iter) | js (ms/iter) | rust (ms/iter) |
| ------------------------- | ------------- | ----------------- | ------------- | ------------ | -------------- |
| `arith-loop`              |         0.244 |             0.245 |         0.241 |        0.576 |          0.954 |
| `array-update`            |        1580.5 |            1578.6 |         0.535 |         24.4 |            n/a |
| `bool-predicate`          |        0.0093 |            0.0094 |      0.000658 |        0.017 |         0.0021 |
| `effect-multishot`        |          4.55 |              4.65 |           n/a |        0.228 |            n/a |
| `effect-oneshot`          |         0.029 |              6.47 |           n/a |        0.369 |            n/a |
| `effect-pure`             |        0.0068 |            0.0068 |        0.0033 |       0.0027 |         0.0100 |
| `effect-stream`           |         0.017 |             0.018 |         0.019 |        0.017 |          0.020 |
| `either-chain`            |         0.013 |             0.012 |        0.0018 |        0.027 |         0.0020 |
| `hello-world`             |        0.0031 |            0.0031 |      0.000485 |     0.000024 |       0.000385 |
| `hof-pipeline`            |        0.0051 |            0.0051 |        0.0049 |       0.0083 |         0.0032 |
| `instance-field`          |         0.300 |             0.514 |        0.0061 |        0.087 |          0.011 |
| `lazylist-take`           |         198.2 |             197.4 |          5.59 |          n/a |            n/a |
| `list-fold`               |        0.0062 |            0.0064 |      0.000338 |       0.0026 |          0.049 |
| `literal-match`           |         0.010 |             0.010 |        0.0080 |        0.029 |         0.0016 |
| `map-ops`                 |         0.025 |             0.027 |         0.020 |        0.193 |          0.022 |
| `mutual-recursion`        |          1.35 |              1.35 |         0.517 |         6.50 |           2.82 |
| `nested-loop`             |         0.256 |             0.258 |         0.252 |        0.590 |           1.01 |
| `option-chain`            |         0.017 |             0.015 |      0.000550 |        0.024 |       0.000646 |
| `pattern-match-heavy`     |         0.059 |             0.058 |         0.052 |        0.052 |          0.319 |
| `range-sum`               |        0.0041 |            0.0040 |         0.012 |        0.011 |         0.0012 |
| `recursion-fib`           |          1.22 |              1.19 |          1.26 |         4.32 |           1.81 |
| `recursion-tco`           |         0.031 |             0.031 |         0.026 |        0.116 |          0.025 |
| `streams-pipeline`        |        0.0095 |            0.0094 |      0.000043 |     0.000417 |       0.000017 |
| `string-concat`           |         0.096 |             0.145 |         0.090 |        0.448 |           1.01 |
| `string-split`            |         0.185 |             0.149 |         0.090 |        0.185 |          0.316 |
| `tuple-monoid`            |          2.06 |              2.11 |         0.087 |         9.18 |          0.121 |
| `type-lambda-native`      |        0.0046 |            0.0047 |      0.000008 |     0.000042 |       0.000002 |
| `type-lambda-placeholder` |        0.0076 |            0.0078 |        0.0012 |        0.018 |       0.000490 |
| `typeclass-fold`          |          1.21 |              1.20 |        0.0029 |        0.045 |       0.000594 |
| `typeclass-monoid`        |         0.010 |             0.010 |      0.000005 |     0.000221 |       0.000006 |
| `vector-index`            |        1056.3 |            1048.9 |         0.507 |         16.3 |          0.638 |

### Collection-type microbenchmarks

The three workloads exercising the real collection semantics added in
`collection-real-type` / `collection-vector-indexed`, annotated (same numbers as the
full table above). `n/a` is the honest support signal: `Vector` is now O(1)-indexed
on Rust (`vec![]` + `seq[i as usize]`), but `Array` (mutable) and `LazyList` (lazy)
need a distinct runtime Rust doesn't have; JS is eager with no `LazyList.from`.

| Workload | what it measures | ssc (interp) | jvm | js | rust |
|---|---|---:|---:|---:|---:|
| `vector-index`  | O(1) `Vector` indexed reads      | 1056.3 | 0.507 | 16.3 | 0.638 |
| `array-update`  | in-place mutable `Array` update  | 1580.5 | 0.535 | 24.4 | n/a |
| `lazylist-take` | lazy `take(8)` of an infinite `LazyList` | 198.2 | 5.59 | n/a | n/a |

Notes: the interpreter **tree-walks** these (collection ops aren't bytecode-JITted, so
`ssc-asm` ≈ `ssc` — that ~1000× gap to jvm/rust is the tree-walk tax, not a real-work
cost); `vector-index` and `array-update` use a JS-f64-safe MINSTD index generator so
they stay in bounds on every backend (see the corpus headers).

## JMH microbenchmarks (`sbt "interpreterBench/Jmh/run"`)

Captured 2026-05-30 with `sbt "interpreterBench/Jmh/run -wi 3 -i 5 -f 1 -t 1 RuntimeBench"`.
Machine: macOS arm64, JDK 21.0.7 OpenJDK.

All times are average time per operation (µs/op); lower is better.

| Benchmark | µs/op | ± error |
|---|---:|---:|
| `interp_arithLoop` | 105 836 | ± 16 129 |
| `interp_patternMatch` | 2 789 254 | ± 215 943 |
| `interp_recursionFib` | 8 049 974 | ± 426 549 |
| `interp_recursionTco` | 286 840 | ± 15 048 |
| `js_arithLoop` | 1 277 | ± 47 |
| `js_patternMatch` | 1 299 | ± 58 |
| `js_recursionFib` | 4 442 | ± 343 |
| `js_recursionTco` | 104 | ± 118 |
| `jvm_arithLoop` | 265 | ± 8 |
| `jvm_patternMatch` | 553 | ± 17 |
| `jvm_recursionFib` | 1 295 | ± 83 |
| `jvm_recursionTco` | 26 | ± 1 |

Interpreter vs JS: arithLoop ×83, patternMatch ×2147, recursionFib ×1812, recursionTco ×2754.  
Interpreter vs JVM: arithLoop ×399, patternMatch ×5043, recursionFib ×6211, recursionTco ×11032.

## Bundle sizes (`./scripts/bundle-size.sh`)

*Not yet captured — run `./scripts/bundle-size.sh` after building `ssc` and paste the output here.*

---

## Updating baselines

Before any v1.61.N optimization:
1. `sbt cli/stage` — build latest CLI
2. `./bench.sh --baseline` — capture wall-clock
3. `sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"` — capture JMH
4. `./scripts/bundle-size.sh` — capture bundle sizes
5. Commit to `bench/BASELINE.md`

Each optimization commit must then report:
```
Before: arith-loop = NNN ms / M.M allocs/op
After:  arith-loop = NNN ms / M.M allocs/op
```
