# Cold-start perf — `ssc` CLI startup

`scripts/bench wall` measures **work-time only** (it self-reports, excluding
process startup). This harness measures the thing that excludes: the wall-clock
of a *fresh* `ssc run hello.ssc` process — JVM boot + classload + parse + first
eval + teardown — the latency a user feels on every CLI invocation.

Pure bash + the JVM launcher; **no scala-cli/bloop**, so it can't hang the way
the cross-backend property test does.

## Run

```sh
sbt cli/assembly                      # once — builds tools/cli/target/.../ssc.jar
tests/perf/coldstart/run.sh           # measure (auto-finds the jar)
tests/perf/coldstart/run.sh runs=20 warmup=3
tests/perf/coldstart/run.sh jar=/path/to/ssc.jar
```

It reports two modes and a machine-readable tail for regression capture:

```
  baseline (no CDS)      median   378 ms · peak RSS  167 MB
  AppCDS (as bin/ssc)    median   182 ms · peak RSS  114 MB
  ── baseline 378 ms → AppCDS 182 ms (−51%) · peak RSS 114 MB ──
COLDSTART_MS: 182
COLDSTART_BASELINE_MS: 378
COLDSTART_RSS_MB: 114
```

## The AppCDS cut (shipped)

JVM boot floor is ~36 ms; the rest of the baseline (~340 ms) is classloading the
~88 MB fat jar. **Application Class-Data Sharing** mmaps a pre-parsed class
archive instead, cutting cold-start ~50% — and, as a bonus, peak RSS ~30% (shared
classes are mapped read-only rather than loaded per process).

`bin/ssc` (and the launcher `install.sh` generates) enable it via
`-XX:+AutoCreateSharedArchive` — the archive is created on the first run and
auto-recreated if the classpath changes, so there is no build step. Only CDS is
enabled; `-XX:TieredStopAtLevel=1` would shave a little more off startup but
**cripple long-running `ssc serve` throughput**, so it is deliberately omitted.
Disable CDS with `SSC_NO_CDS=1`; the archive lives in
`${XDG_CACHE_HOME:-~/.cache}/scalascript/ssc.jsa`.

The GraalVM native binary (`sbt cli/graalvm-native-image:packageBin`) has no JVM
startup at all and needs no CDS.

## Not yet measured (follow-on slices of `real-workload-perf`)

- **(b) steady-state RSS** of a long-running `ssc serve` over hours.
- **(c) GC behaviour** under sustained server load.

Both need a long-running-server harness (start `ssc serve`, drive load, sample
RSS / GC over time) — a separate, larger slice than this one-shot cold-start
measurement.
