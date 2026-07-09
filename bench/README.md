# Benchmark Workflow

> **Primary reference:** [`docs/benchmarks.md`](../docs/benchmarks.md) lists
> every benchmark, what each one measures, and the one-line `scripts/bench`
> command that runs it. This file covers only the gate policy and the
> durable-baseline rules.

Status: lightweight guard workflow. The default policy is informational; perf
checks become blocking only when the caller passes an explicit target gate.

## Quick Smoke

Use the smoke path before or after interpreter/compiler optimization work:

```bash
ssc bench --smoke                          # corpus-driven CLI smoke
ssc bench --smoke --target-ms 250 --require-target
scripts/bench smoke                        # one-iter JMH smoke
scripts/perf-smoke.sh                      # wrapper around `ssc bench --smoke`
scripts/perf-smoke.sh --jmh                # adds the one-iter JMH smoke
```

`ssc bench --smoke` runs `bench/corpus/hello-world.ssc` through the interpreter
only, with `warmup=0` and `reps=1` unless overridden. It is meant to catch broken
benchmark wiring, not to prove a performance claim.

`scripts/bench smoke` / `scripts/perf-smoke.sh --jmh` add one short JMH run for
`InterpreterBench.arithLoop` and write raw JSON to `bench/jmh-smoke.json`.
That file is ignored; copy meaningful summaries into `bench/BASELINE.md` only
when updating a baseline intentionally.

## Full Runs

Use full runs when an optimization PR needs before/after numbers:

```bash
sbt cli/assembly
scripts/runtime-bench.sh --baseline                       # wall-clock corpus
scripts/bench interp                                       # all interp JMH
scripts/bench cross                                        # cross-backend JMH
scripts/bench compile                                      # compiler JMH
scripts/bench v2-backends arith-loop                       # v2 VM/source backend corpus slice
sbt "interpreterBench/Jmh/run -rff $(pwd)/bench/jmh-results.json -rf json"
sbt "compilerBench/Jmh/run -rff $(pwd)/bench/jmh-compiler-results.json -rf json"
```

The last two commands are kept here for tools that already parse those exact
JSON paths; the wrapper commands above produce the same data more ergonomically.

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
