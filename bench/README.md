# Benchmark Workflow

Status: lightweight guard workflow. The default policy is informational; perf
checks become blocking only when the caller passes an explicit target gate.

## Quick Smoke

Use the smoke path before or after interpreter/compiler optimization work:

```bash
ssc bench --smoke
ssc bench --smoke --target-ms 250 --require-target
scripts/perf-smoke.sh
scripts/perf-smoke.sh --jmh
```

`ssc bench --smoke` runs `bench/corpus/hello-world.ssc` through the interpreter
only, with `warmup=0` and `reps=1` unless overridden. It is meant to catch broken
benchmark wiring, not to prove a performance claim.

`scripts/perf-smoke.sh --jmh` adds one short JMH run for
`InterpreterBench.interp_arithLoop` and writes raw JSON to `bench/jmh-smoke.json`.
That file is ignored; copy meaningful summaries into `bench/BASELINE.md` only
when updating a baseline intentionally.

## Full Runs

Use full runs when an optimization PR needs before/after numbers:

```bash
sbt cli/assembly
scripts/runtime-bench.sh --baseline
sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"
sbt "compilerBench/Jmh/run -rff bench/jmh-compiler-results.json -rf json"
```

Generated raw files are ignored. The durable checked-in summaries are:

- `bench/BASELINE.md` for runtime/JMH summaries and update policy;
- `bench/BUNDLE_SIZES.md` for bundle-size trend notes;
- `bench/perf-manifest.yaml` for the machine-readable workflow manifest.

## Gate Policy

- Default `ssc bench`, JMH, and smoke runs are informational.
- `--target-ms N --require-target` is an explicit local or dedicated-runner
  gate. It exits non-zero when any measured backend exceeds the p50 target.
- Do not use laptop wall-clock numbers as a shared hard gate unless the runner,
  JVM, warmup, reps, and workload are pinned in `bench/perf-manifest.yaml`.
- Generated JMH profiler directories and raw result files stay ignored so local
  benchmark experiments do not dirty the shared checkout.
