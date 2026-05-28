# Benchmark Baseline

Baseline numbers are captured by running:
```
./bench.sh --baseline
```

This requires `ssc` to be built (`sbt cli/stage`).  The median of 7 runs after 2 warmup
runs is recorded.  Each v1.61.N optimization PR must include before/after numbers
against this baseline.

## Wall-clock baselines (`./bench.sh`)

*Not yet captured — run `./bench.sh --baseline` after building `ssc`.*

## JMH microbenchmarks (`sbt "interpreterBench/Jmh/run"`)

*Not yet captured — run `sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"` and paste the summary here.*

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
