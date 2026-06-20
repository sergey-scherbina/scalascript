# Steady-state server perf — RSS + GC under load

The third unmeasured perf axis (`real-workload-perf` (b)+(c)): the memory footprint
and GC behaviour of a *long-running* `ssc` HTTP server under sustained load — what
neither `scripts/bench wall` (work-time) nor `tests/perf/coldstart` (process startup)
captures.

Boots a real server (`examples/health-defaults.ssc` on the JVM interpreter, bounded
to `-Xmx512m` with a GC log), drives concurrent load, samples RSS over the run, and
reports the steady-state footprint, the start→end drift (a leak signal), and the GC
pause count/time. Pure bash + the JVM launcher — **no scala-cli/bloop**, and the
server is reliably torn down on exit (trap → kill + `lsof`/`pkill`).

## Run

```sh
sbt cli/assembly                      # once — builds tools/cli/target/.../ssc.jar
tests/perf/serverrss/run.sh           # 30s default
tests/perf/serverrss/run.sh secs=120 conc=8 interval=5
tests/perf/serverrss/run.sh jar=/path/to/ssc.jar port=8770
```

Output (machine-readable `SERVERRSS_*` tail for regression capture):

```
  start 184 MB → end 196 MB (Δ 12 MB, 6%) · peak 197 MB · GC 41 pauses / 27 ms
  verdict: STABLE
SERVERRSS_START_MB: 184
SERVERRSS_END_MB: 196
SERVERRSS_PEAK_MB: 197
SERVERRSS_DELTA_PCT: 6
SERVERRSS_GC_PAUSES: 41
SERVERRSS_GC_MS: 27
```

## Baseline (2026-06-20, 20s / 4 loops, JDK 21, -Xmx512m)

The interpreter HTTP server settles at **~195 MB RSS** and is **STABLE** under
sustained load — RSS ramps from a ~184 MB cold start to a ~195 MB plateau and does
not climb further (no leak), with **light GC** (a few dozen short pauses, tens of ms
total). The `verdict` flips to `GROWING (possible leak — investigate)` if the
start→end drift exceeds 20%.

For a real leak hunt, run a long window (`secs=300+`); a steady plateau across
minutes is the no-leak signal, a monotonic climb is the red flag to profile (JFR
`jdk.OldObjectSample` / heap histogram).
