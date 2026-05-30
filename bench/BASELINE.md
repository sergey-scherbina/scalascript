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

*Not yet captured — run `./bench.sh --baseline` after building `ssc`.*

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
