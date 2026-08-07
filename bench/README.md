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

## Comparing the three versions

`./bench.sh` with no flags spans all three: `ssc ssc-asm jvm js rust` (v1), `v2 v2-bytecode` (v2),
`v3`. Before 2026-08-07 the default was the v1/v2 columns only and v3 had none at all, so the
question people bring to this table — is the new one faster — needed a flag that did not exist.

**v3 is measured differently, and the difference is small but real.** Every other column is timed by
a wrapper written in ScalaScript that calls `System.nanoTime()` itself. v3 has no clock: its prim
table is `io.println` plus collection operations, and the charter keeps the kernel's only host door
at `Prim`, so `v3/ssc3 bench` runs the loop driver-side. Compilation is excluded on both sides and
the window doubles to 100 ms on both sides; what differs is that v3 is not charged for executing its
own rep counter. On an IR walker that is one host increment against a whole `workload()` call.

**A blank v3 cell means v3 declined the program, not that it was slow.** Measured 2026-08-07:
**19 of 36 run**, 13 are declined by the front, and 4 compile but stop in the executor. Every one is
a named task with its diagnostic in `v3/SPRINT.md` §SSC3-7.

An earlier version of this paragraph said "compiles 23 of 36", which was a `ssc3 ir` sweep reported
as if it were coverage — eleven of those 23 do not execute. Compiling is not running, and the split
above is what the two stages actually report.

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
