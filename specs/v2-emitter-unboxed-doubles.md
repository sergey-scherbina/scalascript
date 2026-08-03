# v2 emitter — unboxed doubles past the match boundary

**Status:** spec / design (no code yet). Written 2026-08-01, out of
[`v2-wide-jit.md`](v2-wide-jit.md) §9, which finished the JIT work and named this as the thing that
remains. **This is not a JIT spec** — it is about `JvmByteGen`, and every change lands in the AOT
lane and the JIT at once, because they share the walker.

## 1. The measurement that motivates it

After the wide-JIT programme, three of the four representative rows sit at **1.00–1.04× of the AOT
lane** — there is nothing left for a JIT to win on them. The fourth does not, and the reason is not
the JIT:

| row | JIT | AOT | v1 (`ssc`) | AOT vs v1 |
|---|---:|---:|---:|---:|
| `arith-loop` | 0.596 | 0.565 | 0.243 | 2.3× |
| `recursion-fib` | 1.21 | 1.15 | 1.17 | 0.98× |
| `recursion-tco` | 0.0271 | 0.0241 | 0.029 | 0.83× |
| `pattern-match-heavy` | 10.7 | **8.5** | **0.052** | **163×** |

On `pattern-match-heavy` the **AOT lane itself** is 163× off v1's JIT'd interpreter. Closing the
JIT's remaining 1.28× would leave that row two orders of magnitude behind, so the limit is the code
the emitter produces, not when it produces it.

## 2. The defect, precisely

`JvmByteGen.canDouble` — the predicate deciding whether a term can be computed in unboxed `double`
— accepts exactly three shapes:

```scala
case Term.Lit(Const.CFloat(_))                     => true   // a literal
case Term.Prim("dcell.get", List(Term.Local(_)))   => true   // a double cell read
case DArithB(op, a, b) if "+-*/".contains(op)      => canDouble(a) && canDouble(b)
case _                                             => false
```

The workload is:

```scala
def area(s: Shape): Double = s match
  case Circle(r)      => 3.14159 * r * r
  case Rect(w, h)     => w * h
  …
```

`r`, `w`, `h` are **`Local`s bound by a match arm**, and that shape is not in the list. So every
multiplication falls to the boxed `Emit.arith`, allocating a `FloatV` per operation, 500 000 times.
v1's JIT keeps the same arithmetic in unboxed `double` registers — hence 163×.

**Two adjacent holes of the same kind**, worth fixing together because they share the mechanism:

- **no `canParamDouble`.** `canParamLong` lifts an all-`Int` def onto a `$long(J…)J` entry behind an
  `INSTANCEOF IntV` guard. There is no `$double` twin, so a `def f(x: Double): Double` is boxed
  end to end even when every operation is arithmetic.
- **no unboxed return.** A `Double`-returning def re-boxes at every `RET`, so a caller that
  immediately unboxes pays for a `FloatV` that exists for one instruction.

## 3. Mechanism — a runtime guard, not a type system

Core IR is untyped and F types only `Int | String | BigInt` (`v2/SPRINT.md` VC-2c), so "this Local
is a Double" cannot be proven statically today. It does not have to be: **the Long path already
solves this exact problem at run time** — `canParamLong` emits `INSTANCEOF IntV` per parameter and
falls to the generic boxed body when the guard fails.

The same shape applies here:

1. `canDouble` accepts a match-bound `Local` **when the arm's emitted code has already established
   the field is a `FloatV`** — the arm knows the constructor it matched, so the guard is one
   `INSTANCEOF` per bound field, hoisted to the top of the arm, not per operation.
2. On guard failure the arm runs its existing boxed body. No new semantics, and no case where a
   program computes a different answer — the same rule that makes the Long path safe.
3. `canParamDouble` + a `$double(D…)D` entry mirrors `canParamLong` exactly.

**Why this is the right layer:** both lanes share `JvmByteGen`, so this lands in `--bytecode` and in
the JIT in one commit. That was the argument for one walker, and this is the first slice that
collects on it.

## 4. Slices

| id | what | gate |
|---|---|---|
| **E-0** | Baseline + a JFR allocation profile of `pattern-match-heavy` on the AOT lane, to confirm `FloatV` is the dominant allocation rather than assuming it from the code read. | the profile, recorded in §6 |
| **E-1** | `canDouble` accepts a match-bound `Local` under an arm-level `INSTANCEOF FloatV` guard. | `pattern-match-heavy` on **both** lanes; byte-identical output; conformance |
| **E-2** | `canParamDouble` + the `$double(D…)D` entry, twin of `canParamLong`. | `float-loop`, `float-fold`; the guard proven live by a rename probe |
| **E-3** | Unboxed `Double` return where the callee and caller are both compiled. | the same rows; no regression on `arith-loop` |

Each slice must show the number on **both** lanes — a change that helps the JIT and not the AOT lane
means it was made in the wrong place.

## 5. Non-goals

- Typing Core IR. This is a guard-based unboxing pass; the typed-IR route is `v2-f5c-typed-bytecode`
  and is orthogonal.
- Touching the VM lane's interpreter. It stays boxed; the JIT is how the VM lane gets this.
- `pattern-match-heavy` reaching v1. 163× will not close in one slice, and claiming a target this
  spec cannot hold would make the gates meaningless.

## 6. Results

### E-0 — the profile, and the control that made it readable

`bin/ssc-tools --backend v2-bytecode bench --machine --warmup-time 500 --reps 8` under
`-XX:StartFlightRecording=…,settings=profile`, allocation samples by class:

| class | `pattern-match-heavy` | control (`hello-world`) | **workload** |
|---|---:|---:|---:|
| `ssc.Value[]` | 260 | 221 | 39 |
| `ssc.Value$FloatV` | 179 | **0** | **179** |
| `ssc.Done` | 149 | 150 | −1 |
| `byte[]` | 68 | 78 | −10 |
| `::` | 33 | 55 | −22 |
| `Value$BoolV` | 31 | 35 | −4 |

**`FloatV` is 179 in the workload and ZERO in the control.** Everything else cancels: it is the F
front's own execution, which both runs pay identically. §2's claim — boxed doubles per operation —
is now measured rather than read from source, and the workload's allocation profile is essentially
*only* that, plus a small `Value[]` residue.

**The control is the whole result.** Without it the histogram says `Value[]` (260) is the dominant
allocation and the fix aims at argument arrays. Two things make a raw profile of `bin/ssc run`
misleading, and both are structural rather than accidental:

1. **JFR profiles the whole process, not the measured window.** `bench --machine` times the workload
   iterations; the recording also covers the front compiling the program.
2. **On the bytecode lane a large part of the process still runs on the VM** — `ssc.Done` and half
   the `Value[]` samples come from `ssc.Compiler`'s own closures (`Runtime.scala:691-693`, the `Lit`
   and `Local` cases), because the F front executes on the VM even when the user program is
   compiled ahead-of-time.

So: **profile the workload AND a trivial control on the same lane, and subtract.** A single profile
on this toolchain is a profile of the compiler.

### E-1 — the guarded unbox, on both lanes

`canDouble` now accepts a `Local` that a guard has PROVEN to hold a `FloatV`, and the match arm emits
that guard once at its top — one `INSTANCEOF FloatV` per bound field, not per operation. When it
fails, the arm runs the boxed body it always had, so a non-`Double` field changes the path taken and
nothing else. Pure double arithmetic contains no calls, so tail position cannot matter on the
unboxed path.

| lane | before | after (3 runs) | median |
|---|---:|---|---:|
| AOT (`--backend v2-bytecode`) | 8.5 | 7.23 / 7.57 / 8.05 | **7.57** (1.12×) |
| JIT (`SSC_V2_JIT=on`) | 10.7 | 8.47 / 8.67 / 9.80 | **8.67** (1.23×) |

**Both lanes moved, which is the gate** — one walker means a change in the right place cannot help
only one of them.

**Correctness is checked where the guard FAILS, not only where it fires.** A probe with
`case class Box(v: Double)` and `scale(b) = b match { case Box(v) => v * 2.0 + 1.0 }`, called with
`Box(3.5)`, `Box(3)` (an `IntV` field at run time — the guard must decline) and `Box(0.0)`, prints
`8 / 7 / 1` identically on the VM lane, the VM lane with the JIT, and the AOT lane.

**Allocation, same control method as E-0:**

| class | before | after | control |
|---|---:|---:|---:|
| `ssc.Value$FloatV` | 179 | **99** | 0 |
| `ssc.Value[]` | 260 | 353 | 221 |

⚠ **These histograms are NOT normalised, and `Value[]` rising is the apparatus, not a regression.**
The bench's warmup is time-based, so a faster program completes more warmup iterations inside the
same recording window. The `FloatV` drop is therefore understated if anything — it fell by 45 %
while the run was doing *more* work.

**Why the win is 1.12×/1.23× and not more, which E-2 and E-3 now have to answer.** The arm no longer
boxes per operation, but two boundaries remain: `area(s)` still RETURNS a boxed `FloatV`, and
`total = total + area(s)` accumulates through a cell. So the two operations inside an arm collapsed
to one boxing of the result — roughly half the allocations, which is exactly what the profile shows.
E-2 (`canParamDouble` + the `$double` entry) and E-3 (unboxed return) are the other half of the same
mechanism, not incremental polish.

### E-2 — re-aimed by measurement, before a line was written

E-2 was specced as `canParamDouble` + a `$double(D…)D` entry, the twin of `canParamLong`. Measuring
the rows it was meant to serve says that is not where the problem is:

| row | v1 (`ssc`) | AOT | JIT |
|---|---:|---:|---:|
| `float-loop` | 1.30 | **0.900** | 1.75 |
| `float-fold` | **0.0110** | 0.922 | 1.50 |

Two things fall out, neither of which `canParamDouble` addresses:

1. **The AOT lane already beats v1 on `float-loop`** (0.900 vs 1.30). The emitter is not the limit
   there — so an emitter slice aimed at it would be optimising the wrong lane.
2. **The JIT is 1.94× off that AOT result** (alternating 3-round A/B, disjoint ranges), and the
   obvious explanation is refuted: lowering the tier-up threshold to 2 or 1 makes it *worse*
   (1.39 → 3.24 → 2.82), so it is not "the long first call runs interpreted". Unexplained.

**So E-2 becomes: profile `float-loop` on the JIT against the AOT lane and find the 1.94×** — with
E-0's control method, because a raw profile here is a profile of the compiler. `canParamDouble` and
the unboxed return (E-3) stay queued behind that, since neither is now known to be the lever.

⚠ **`float-fold` v1 = 0.0110 ms against AOT 0.922 — 84× — is a separate question and probably an
apparatus one.** A bench cell that fast usually means the work was eliminated, not performed; this
repo has the rule that a suspiciously fast cell is a correctness question until proven otherwise.
Not chased here, recorded so it is not read as a v2 deficiency.

### E-2 — the answer is warm-up accounting, not the emitter

The 1.94× is **not a code deficit**. The bench measures per-iteration time; the AOT lane enters that
window already compiled, while the JIT compiles *inside* it. Raising the warm-up flips the result:

```
warmup=400ms    AOT=3.40   JIT=3.71
warmup=3000ms   AOT=4.11   JIT=2.67    <- the JIT is now FASTER than the AOT lane
```

(Absolute values are not comparable with the earlier table — the host load moved by 4× between
sessions. The direction is what this shows, and it is consistent.)

It also explains, retroactively, why lowering `SSC_V2_JIT_THRESHOLD` made `float-loop` *worse*
(1.39 → 3.24 → 2.82): a lower threshold compiles MORE units inside the measured window.

**The corollary matters more than the finding.** Every JIT-vs-AOT number in
[`v2-wide-jit.md`](v2-wide-jit.md) §9 was taken at `--warmup-time 400`, so all of them are a **lower
bound** on the JIT's steady state, and "the JIT is 1.00–1.28× of AOT" should be read as "no worse
than". A steady-state comparison needs the warm-up to exceed tier-up, and nothing in the harness
enforces that today.

**Three apparatus layers had to be peeled to get here, each invalidating the previous measurement:**

1. **JFR profiles the whole process**, so a raw profile is a profile of the compiler (E-0) — fixed
   with a control run on the same lane, subtracted.
2. **`ssc-tools bench` forks**, so `JDK_JAVA_OPTIONS` profiled the wrong process: 30 s of recording
   yielded ONE execution sample, in a scalameta tokenizer.
3. **A single-run program is dominated by the front.** At 60 M iterations the profile was still the
   compiler; only at 600 M did the workload dominate — and there the two lanes are at **parity**
   (AOT 5.70 s / JIT 5.57 s, both spending ~60 % of samples in the same generated `lam$46`).

That third probe is what proved the emitted loop identical, which is what forced the explanation to
be about *when* compilation happens rather than *what* is compiled.

**E-2 as originally specced (`canParamDouble` + `$double` entry) is therefore still unmotivated by
any measurement.** It stays queued. The next real task is a harness that can measure a v2 lane in
steady state — without it, every future JIT number carries the same understatement.

### E-1 has a cost its own measurement did not show — and it is the actionable finding

Chasing `pattern-match-heavy`'s residual through five refuted hypotheses ended somewhere neither
lane's timing pointed: `-XX:+PrintInlining`, run on a direct invocation (NOT through
`ssc-tools bench`, which forks and profiles the wrong process), says the same thing on **both**
lanes:

```
aot: ssc.gen.Entry::lam$58 (437 bytes)   hot method too big
jit: ssc.gen.Entry::lam$58 (437 bytes)   hot method too big
```

`lam$58` is `area` — the callee this row runs 500 000 times. **HotSpot's `FreqInlineSize` is 325
bytes; the method is 426–437.** So the hot callee is never inlined into the loop, on either lane.
That does not explain the JIT-vs-AOT difference (it is identical on both), but it does explain a
large part of why both are far from v1.

**And E-1 is implicated in it.** The guarded unbox keeps the arm's boxed body as the guard's else
branch, so each arm carries BOTH paths — measured in the dump: 20 `FloatV` unbox references and 5
`Emit.arith` fallbacks in one 426-byte method. Before E-1 the arms held the boxed path only, which
is roughly half. **E-1 very likely moved `area` from inlinable to not**, and its own measurement
(1.12× AOT / 1.23× JIT) could not see that, because the win it measured is real and the loss is in
a different mechanism.

**Fix, and it is standard: outline the fallback.** Emit the boxed body as its own method and leave
the hot method with the guard, the unboxed path, and a call. The hot path then stays under the
inline threshold and the cold path costs one call it was already paying in spirit. Both lanes get it
at once.

**Gate for that slice, which E-1 did not have:** assert the emitted size of the hot method from the
`SSC_V2_JIT_DUMP` class, not just the wall-clock. A throughput win that silently crosses an
inlining cliff is exactly the defect this repo has a doc section about
(`docs/benchmarks.md`, "Bytecode size — the perf defect no profiler shows you"), and E-1 walked into
it while improving the number it was watching.

**To confirm rather than infer**, the A/B is a rebuild with the E-1 arm disabled and the same
`PrintInlining` probe: if `area` drops under 325 and starts inlining, the cost is confirmed and the
outlining slice has its before-number.

### The A/B that settles it: E-1 is net-positive, and outlining would give both

`SSC_V2_EMIT_NO_ARM_UNBOX=1` turns E-1's guarded arm off — an A/B lever, not a feature flag, so the
inference above becomes a measurement.

**The cliff is confirmed.** Same program, same lane, emitted size of `area` (`lam$58`):

| | bytes | vs `FreqInlineSize` 325 |
|---|---:|---|
| E-1 on | **436** | over — never inlined |
| E-1 off | **280** | under — inlinable |

**And E-1 still wins.** `pattern-match-heavy`, AOT lane, 3 rounds on a loaded host (absolute values
inflated; the ratio is the result):

| round | E-1 on | E-1 off |
|---|---:|---:|
| 1 | 24.3 | 35.1 |
| 2 | 13.5 | 24.1 |
| 3 | 9.73 | 10.2 |

Faster in 3 of 3. The unboxing win exceeds the inlining loss, so **E-1 stays** — but it is paying a
tax it does not have to.

**So the outlining slice is now specified by numbers, not by taste:** move each arm's boxed body
into its own method, leaving the hot method with the guard, the unboxed path and a call. Target:
`area` under 325 bytes with the unboxed path intact — i.e. both the 1.12–1.79× E-1 measures AND the
inlining it currently forfeits. **Its gate is the emitted size**, read from the `SSC_V2_JIT_DUMP`
class, plus `PrintInlining` showing `lam$58` no longer "hot method too big".

The general lesson, which cost this investigation five refuted hypotheses to reach: **a throughput
win can cross an inlining cliff invisibly.** Neither E-1's wall-clock nor its allocation profile
could see it; only the emitted size could, and nothing was watching that.
