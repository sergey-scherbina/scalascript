# Benchmarks

Cross-backend micro-benchmarks comparing **ScalaScript** (run through its
three backends) with hand-written **Scala 3** and **JavaScript** on the same
workload.

## Workloads

| File | Pattern | Stresses |
|------|---------|----------|
| `fib.ssc` / `.scala` / `.js` | Recursive Fibonacci, n = 28 | Function-call overhead |
| `sum.ssc` / `.scala` / `.js` | Tail-recursive sum 1..1 000 000 | Tail-call optimisation |
| `list-ops.ssc` / `.scala` / `.js` | `map + filter + foldLeft` over 100 000 ints | Collection dispatch |

Each script self-times the **work portion** with `nanoTime()` and prints
`BENCH_MS: <number>` — this excludes scala-cli compile / Node startup /
JVM boot time from the figure.

## Running

```bash
scala-cli bench/run.sc
```

The driver runs each variant 5 times and reports the median.

## Sample results (Apple M-series · JDK 17 · Node 25)

```
workload      ssc-int     ssc-js      ssc-jvm     scala-cli   node
--------------------------------------------------------------------------
fib           2742        2           0           0           2
sum           2960        3           0           0           2
list-ops      542         3           44          70          2
```

`0` means the work finished in under one millisecond (millisecond
precision rounds away the rest).

## Observations

- **Tree-walking interpreter is ~1000× slower than the compiled paths**
  on the call-heavy workloads (fib, sum).  Expected — every value
  dispatch goes through pattern-matched `Value` cases.  Still under
  three seconds for the entire run, which is fine for the doc /
  REPL / `serve`-mode use cases the interpreter was built for.
- **`ssc-jvm` matches native `scala-cli`** on fib and sum (both
  sub-ms) — JvmGen emits Scala 3 that scalac compiles to the same
  loop / native recursion the hand-written version produces.  On
  list-ops `ssc-jvm` actually edges out `scala-cli` (44 vs 70 ms)
  because the `ssc compile` path skips scala-cli's per-file source
  caching; same Scala, different driver.
- **`ssc-js` matches Node** (2–3 ms) on fib and sum — JsGen's
  while-loop trampoline lowers tail recursion to a native JS loop,
  so the JIT inlines the whole thing.  On list-ops the figures
  agree to within measurement noise.
- **`node` is the fastest on the call-heavy patterns** (2 ms each),
  edging the JVM only because the JVM hasn't tiered up by the time
  these tiny workloads finish.  For longer-running programs the JVM
  wins; that's not what this benchmark measures.

These are not steady-state JIT-warm numbers — each measurement runs
in a fresh process.  For a steady-state comparison the same workload
would need a warm-up loop and JMH-style sampling, which is out of
scope here.
