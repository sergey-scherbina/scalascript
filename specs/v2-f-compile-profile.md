# F compile-time profile (V-6a)

Measured 2026-07-27. This report answers one question: where the native
self-hosted F frontend spends its compile time relative to the legacy
`ssc1-front` + `ssc1-lower` path.

## Result

The dominant cost is **executing F as a tree-walked v2 program**, including
re-parsing and re-lowering F's own 271 KB source on every native compilation.
It is not the checker, user-program execution, file IO, or typed arithmetic.
The mechanism is explicit in `v2/bin/ssc1-run-fsub.ssc0`: `sscFsubIr` reads
the staged F source, calls legacy `parse(fSrc)` + `lowerProg(fProg)`, and then
executes that result through `#coreir.eval`. `RunNativeV2.runTower` in turn
uses `Compiler.compile` + `Runtime.runManaged`; it does not invoke
`JvmByteGen`.

On `examples/scljet-hello.ssc`, F does not cover the complete program. The
product therefore spends 59.25 s in the failed F attempt and then another
9.57 s in the required legacy delegate fallback. The checker takes 0.20 s.
The same program on the legacy lane spends 10.28 s in its only frontend run.
Removing or weakening the fallback is not an option: it is the correctness
boundary that makes F never worse than legacy.

F fuses parse, type-directed lowering, and CoreIR string emission rather than
materialising an AST and running a separate lower pass. Controlled probes on
F compiling its own source split its 4.75 s median as follows:

| Component | Median wall | Share of full |
|---|---:|---:|
| JVM + driver + reading the 271 KB source | 0.24 s | 5.1% |
| Lexing | 0.23 s net | 4.8% |
| Indentation/layout after lexing | 0.37 s net | 7.8% |
| Fused parse + classification + lower + emit | 3.91 s net | 82.3% |

The 59 s SclJet F attempt is therefore not an IO problem that can be repaired
with buffering. JFR attributes 2,485 execution samples to that F frontend
thread, versus 139 to the warmed legacy fallback, 11 to the user-program/main
thread, and 2 to the checker.

## Environment and frozen inputs

- Measured source commit: `bb11f1fc9ae2ab60fd65e3bdb41006202e1da8c7`
- Host: Apple M4 Max, 14 cores, 36 GiB RAM, macOS 26.5.2
- JVM: Eclipse Temurin OpenJDK 21.0.7
- Scala CLI: 1.15.0, Scala 3.8.4
- Product launcher: one `scripts/sbtc "installBin"` result from the measured
  worktree; `SSC_NO_CDS=1`; `--interpret` on both fronts so backend selection
  cannot contaminate the frontend A/B
- Kernel jar:
  `/tmp/ssc-v6a-bb11f1fc9.jar`, SHA-256
  `f647ea594d642fe7b3778b7010890a8b1981249849ca83d162c15af90e61a04d`
- F source: 271,258 bytes, SHA-256
  `cad926be8a815dc0856fa572fe98c56b16b4315c9a32f25f41cce07a5b48733e`
- Bootstrapped F0: 432,398 bytes, SHA-256
  `71be60c9f2978dfacb719d65a4f17fcadf0bc20e96e878f0418dd59a9e48e5b5`
- `F(F_src)` output: 409,629 bytes, SHA-256
  `364ab60cdd6cfecc655fa3b9d7fa9ac55ce373896aa0516fff07d3d47b10e6fd`

Each timing is a fresh JVM process. Front order alternated between rounds.
Output was redirected to files and compared after both sides completed.
There was no memoisation or skipped execution. Other project agents were
active on the host, so absolute times should be treated as machine-local;
the alternating same-jar ratios and JFR phase boundaries are the result.

## Reproduction

Build both artifacts from one checkout:

```bash
cd "$ROOT"
scripts/sbtc "installBin"
scala-cli --power package v2/src --assembly -f \
  -o /tmp/ssc-v6a-bb11f1fc9.jar
```

Product-path cold runs:

```bash
SSC_NO_CDS=1 SSC_FRONT=F \
  /usr/bin/time -p bin/ssc run --interpret examples/hello.ssc
SSC_NO_CDS=1 SSC_FRONT=legacy \
  /usr/bin/time -p bin/ssc run --interpret examples/hello.ssc

SSC_NO_CDS=1 SSC_FRONT=F \
  /usr/bin/time -p bin/ssc run --interpret examples/scljet-hello.ssc
SSC_NO_CDS=1 SSC_FRONT=legacy \
  /usr/bin/time -p bin/ssc run --interpret examples/scljet-hello.ssc
```

The self-compile A/B uses the exact bootstrap driver in
`specs/v2.2-p6.5-fsub.sh`:

1. Lines 52-76 build `F0 = driver(ssc1-front(F_src))`; copy `F0.ir` before
   that script's cleanup trap.
2. Measure the legacy bootstrap process.
3. Measure F on the same source:

```bash
/usr/bin/time -p java -Dssc.stackSize=1073741824 \
  -jar /tmp/ssc-v6a-bb11f1fc9.jar \
  run-ir /tmp/ssc-v6a-F0.ir specs/v2.2-p6.5-fsub.ssc \
  > /tmp/ssc-v6a-stage1.ir
```

The path-portable command used to preserve F0 while executing the bootstrap
prefix is:

```bash
ROOT=$PWD
awk -v here="$ROOT/specs" -v out=/tmp/ssc-v6a-F0.ir '
  NR == 28 { print "HERE=\"" here "\""; next }
  NR <= 76 { print }
  NR == 76 { print "cp \"$WORK/F0.ir\" \"" out "\"" }
' specs/v2.2-p6.5-fsub.sh |
  SSC_JAR=/tmp/ssc-v6a-bb11f1fc9.jar V2_DIR="$ROOT/v2" bash
```

The F and legacy outputs are not expected to be byte-identical to each other
in the F5b typed regime. Repeated F runs are byte-identical to the frozen
409,629-byte stage output; product `hello` and SclJet stdout were byte-identical
between F and legacy in every round.

## Raw wall-clock samples

Values are seconds from `/usr/bin/time -p`.

| Workload | Front | Samples | Median | Ratio |
|---|---|---|---:|---:|
| `F(F_src)` / bootstrap | F | 4.93, 4.86, 6.14, 5.17, 5.25 | 5.17 | 3.47x |
| `F(F_src)` / bootstrap | legacy | 1.20, 1.21, 1.85, 1.49, 1.89 | 1.49 | 1.00x |
| `examples/hello.ssc` | F | 2.04, 2.95, 2.32, 1.88, 1.87 | 2.04 | 1.89x |
| `examples/hello.ssc` | legacy | 1.53, 1.60, 1.08, 1.07, 0.92 | 1.08 | 1.00x |
| `examples/scljet-hello.ssc` | F | 71.60, 66.20, 66.07 | 66.20 | 6.14x |
| `examples/scljet-hello.ssc` | legacy | 11.06, 10.78, 10.67 | 10.78 | 1.00x |

This reproduces the old direction (`hello` roughly 2x, heavy SclJet much
worse) but not its stale magnitude. F grew from the board's recorded
approximately 222 KB to 271 KB, and this SclJet case now pays a complete failed
F attempt before the legacy fallback.

## Phase attribution

### Product thread boundaries

`RunNativeV2.runTower` names its sequential phase threads. JFR
`jdk.ThreadStart` / `jdk.ThreadEnd` therefore gives direct wall-clock phase
boundaries without adding product instrumentation.

| Workload and lane | Checker | F attempt | Legacy front/fallback | Total JFR run |
|---|---:|---:|---:|---:|
| `hello`, F | 0.126 s | 1.268 s | none | 1.75 s |
| `hello`, legacy | 0.127 s | none | 0.391 s | 0.85 s |
| SclJet, F | 0.197 s | 59.252 s | 9.572 s fallback | 70.22 s |
| SclJet, legacy | 0.202 s | none | 10.278 s | 11.49 s |

JFR startup/teardown and the final program execution account for the small
remainder. On the SclJet F run, 99.5% of execution samples were on frontend
threads. The first F thread alone held 2,485 of 2,638 total samples (94.2%).

### F-internal controlled probes

F emits CoreIR strings while parsing, so “parse” and “lower” are not separate
runtime phases. To avoid inventing a boundary, three throwaway F variants
changed only the final `compile` definition and were never committed:

```scala
def compile(src, dq, bs) = 0
def compile(src, dq, bs) = dlen(lex(src, 0, src.length))
def compile(src, dq, bs) = dlen(layout(lex(src, 0, src.length)))
```

Each variant was bootstrapped through the same driver, then executed five
times on the unchanged F source. Medians were 0.24 s (no-op), 0.47 s (lex),
0.84 s (layout), and 4.75 s (full compile). Subtracting adjacent medians
produces the component table in the Result section. This classifies the
honest fused phase instead of pretending JFR can distinguish source functions
inside the generic VM dispatch stack.

### CPU and allocation evidence

The SclJet F profile's hottest Java frames are interpreter machinery:

| Hot method | Execution samples |
|---|---:|
| `ssc.Runtime.run` | 18.57% |
| `scala.Tuple2.equals` | 12.74% |
| `ssc.Compiler.C.compile...` | 11.14% |
| `ssc.Done.apply` | 8.07% |
| `java.lang.String.equals` | 6.29% |
| another `ssc.Compiler.C.compile...` path | 6.22% |
| `TrieMap` lookup | 5.76% |

JFR allocation-weight estimates:

| Phase | Estimated allocation |
|---|---:|
| SclJet failed F attempt | 318.8 GB |
| SclJet warmed legacy fallback | 170.3 GB |
| SclJet standalone legacy front | 170.2 GB |
| Direct `F(F_src)` | 14.27 GB |
| Direct legacy bootstrap | 2.01 GB |

For the failed F attempt, the largest estimated classes were 157.3 GB of
`byte[]`, 50.8 GB of `Value[]`, 42.7 GB of `Done`, 22.0 GB of list cons cells,
and 15.3 GB of `Call`. The byte arrays come primarily from
`StringConcatHelper` beneath the `sconcat` primitive; the additional
`Value[]`/`Done`/`Call` churn is the tree-walking VM.

The complete SclJet F run performed 161 young collections, versus 61 on
legacy. GC wall time was 1.76 s versus 0.62 s. Allocation pressure is
important, but GC pauses themselves are not the 59 s dominant cost.

The `profile` JFRs contained no `FileRead` or `FileWrite` event above the
recording threshold. Source reading is already included in the 0.24 s no-op
probe. IO is therefore ruled out as the primary lever.

## What this rules out

- **Not checker time:** approximately 0.20 s and equal on both SclJet lanes.
- **Not user execution/backend time:** `--interpret` was identical on both
  sides; only 11 SclJet F samples were on `main`.
- **Not file IO:** no threshold-crossing file events; the source-read baseline
  is 0.24 s including JVM startup.
- **Not GC tuning alone:** F's GC wall time is 1.76 s, far smaller than the
  59.25 s F attempt.
- **Not typed-arithmetic coverage:** generic VM dispatch, continuations,
  equality/lookups, list/string construction, and fallback dominate.
- **Not the CI timeout budget:** the larger timeout masks the cost but cannot
  reduce it.

## V-6b admission criteria

V-6b should test the existing bytecode lane on the already-bootstrapped F0,
not speculate about another F5b syntax slice.

The bytecode hypothesis is admitted only if a prototype:

1. executes the same F0 without silently falling back to the VM;
2. emits the exact 409,629-byte `F(F_src)` output above;
3. reduces the 5.17 s direct self-compile median by at least 2x;
4. preserves product `hello` output and reduces the successful F frontend
   phase by at least 2x from its 1.268 s JFR boundary; and
5. reports fallback explicitly on SclJet. If SclJet still requires legacy,
   bytecode acceleration alone is insufficient unless it removes at least
   75% of the 59.25 s failed-attempt tax.

If the bytecode lane rejects F's string/list/closure shape or misses these
targets, V-6c should reject that hypothesis and queue the measured alternatives:

- stage and cache a precompiled F0 artifact instead of parsing/lowering F's
  271 KB source on every invocation;
- add a correctness-preserving preflight for known F coverage gaps so a
  guaranteed fallback does not first execute F for 59 s; and
- attack the measured `sconcat` plus `Value[]`/`Done`/`Call` allocation paths,
  with the same self-compile and SclJet profiles as the acceptance gate.
