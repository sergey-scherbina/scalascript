# F's compile-time cost — measured (V-6a)

> Status: measurement complete 2026-07-28 (`f-compile-cost-profile`). No fix attempted; this
> establishes WHERE the cost is, which the board had never done.
> Bug: `BUGS.md` → `f-front-compile-cost-7x-on-scljet`.

## Why this exists

F has been the default native front since `56d7d705f`, and the flip was known to cost speed — the
board recorded "2-4× slower; hello 0.8→1.5 s, scljet 8→32 s" and, later, a measured 28.20 s vs
4.16 s on `examples/scljet-crud.ssc` that was breaching a 30 s corpus-contract budget. What nobody
had established is **where the time goes**, so two plausible explanations were live:

1. F fails on these programs, `validateNoReader` rejects its output, and the F4a fallback re-lowers
   with legacy — i.e. the cost is a DOUBLE lowering, not F being slow;
2. F's own execution is the cost.

The board also asserted that the F5b typed-IR arc is "the recovery path" for this. That assertion
predates the measurement in `specs/v2-f5b-typed-ir-design.md` showing typed arithmetic is worth ~1%.

## Method

The front phase is isolated by invoking the staged tower directly — the same thing
`RunNativeV2.runTower` does, minus `--structural` (which returns a Data value instead of IR text):

```bash
cd bin/lib/standard/native-front
java -Dssc.stackSize=1073741824 -jar $SSC_JAR run tower/bin/ssc1-run-fsub.ssc0 \
  --fsub-src $PWD/tower/bin/fsub.ssc --std-root $PWD/runtime --lib-root <repo>/bin/lib <prog.ssc>
# legacy: tower/bin/ssc1-run.ssc0, same flags minus --fsub-src
```

Both fronts run under identical JVM flags in a fresh process, so the RATIO is meaningful. Absolute
numbers are NOT comparable to `bin/ssc run`, which invokes the tower in-process on a warm JVM — an
end-to-end run of the same program measured 14.80 s (F) vs 7.82 s (legacy) on this machine, because
execution is shared and dominates. Compare ratios within a row, never across the two methods.

## Result 1 — the cost is the FRONT, and it is not the fallback

| | front F | front legacy | F IR | legacy IR |
|---|---|---|---|---|
| `examples/scljet-crud.ssc` | **25.72 s** | **1.28 s** | 561,807 B | 569,168 B |

Explanation (1) is **refuted**: `SSC_FRONT=F SSC_FRONT_TRACE=1` on the same program reports
**0 delegations** — F lowers it successfully and its output is accepted. The end-to-end run is
correct and byte-identical to legacy's. So this is F's own execution, not double work.

## Result 2 — it SCALES; it is not a fixed bootstrap tax

| program | source | front F | front legacy | ratio |
|---|---|---|---|---|
| `examples/hello.ssc` | 414 B | 1.41 s | 0.34 s | **4.2×** |
| `examples/scljet-crud.ssc` | 2,115 B | 19.48 s | 1.44 s | **13.5×** |

The disadvantage grows with the program rather than being amortised by it. (Source size understates
the difference — `scljet-crud` imports the whole engine — but the direction is what matters: a fixed
startup cost would show the ratio SHRINKING as work grows, and it does the opposite.)

This is the practically important half. A constant tax could be absorbed by warming or caching; a
growing one cannot, and it is why a correct case landed on the wrong side of a 30 s gate budget.

## What this rules out

- **Not the F4a fallback / double lowering** — 0 delegations on the measured program.
- **Not F5b typed IR as the recovery path.** The board says so in two places; typed arithmetic was
  measured at ~1% (`specs/v2-f5b-typed-ir-design.md` §"MEASURED PERF FINDING"), and F5b's real payoff
  is kernel-size and directness. Those board lines should be corrected rather than relied on.
- **Not a constant startup cost** — Result 2.

## What is still open (V-6b)

The runtime perf axis was fixed by compiling hot code to JVM bytecode (`f5c-1..3`; fib ~8.5 ms warm).
F is an `.ssc` program **interpreted** on the VM, so the obvious question is whether F itself belongs
on the bytecode lane. **Do not assume the win transfers**: the f5c wins were NUMERIC (unboxed `Long`
loops, no-`Done` boxing) and F is string- and list-heavy — its hot path is lexing, parsing and string
concatenation. Qualify that before investing:

1. profile F's own execution to name the hot shapes (this document measures WHERE the phase is, not
   WHICH constructs dominate inside it);
2. check the bytecode lane admits F's shape at all (`OpAnf` purity, closures, deep recursion);
3. only then estimate the win.

A cheaper line worth pricing first, since Result 2 says the cost is proportional: F's own algorithmic
complexity. A ~13× ratio that grows with input suggests a super-linear step in F that the reference
front does not have — finding it may be worth more than changing the execution lane.

## Result 3 (2026-08-10) — the cost tracks the REACHED call graph, not the program or its imports

Measurable for the first time on `scljet-hello`, because until 2026-08-10 F died on it at the first
alternative pattern (`v2/BUGS.md scljet-app-not-a-function-after-the-concat-fix`). Every row below
is `bin/ssc run --v2`, one host, consecutive minutes, `legacy` as the control wherever it is quoted.

### The finding

**Calling `jdbcOpen` costs 24 s. Calling `jdbcExecuteUpdate` from the same module, same file, same
position, does not finish in 300 s.** Nothing else changes between those two programs.

```scalascript
buildTableDatabase(…) match
  case Left(e) => println("nope")
  case Right(image) =>
    val db = jdbcOpen(image)          // this alone: 24 s
    val r  = jdbcExecuteUpdate(db, "INSERT INTO books VALUES (2, 'x', 1985)")
    println("no match")               // adding this line: >300 s, cap
```

So the unit of cost is **what the program REACHES**, not what it declares, imports or nests. F
appears to lower the reachable subgraph on demand: `jdbcExecuteUpdate` pulls in the SQL engine,
`jdbcOpen` does not.

### Eight probes, and each one KILLED a candidate rather than confirming it

| probe | F | verdict |
|---|---|---|
| `scljet-hello`'s full import list, body `println("imports only")` | 47 s (legacy 56 s) | **not the imports** — F is faster here |
| the three `def`s of `hello`, nothing called | 25 s | not the definitions |
| + top-level `match` with a short arm | 24 s | not the top-level match |
| + a call to a TRIVIAL `def` from that arm | 53 s | not "calling a def" |
| + a call to `jdbcExecuteUpdate` from that arm | **>300 s** | ← here |
| the same, with a `match` on its result | >300 s | not the match |
| the same, `match` without a field access | >300 s | not the field access |
| 1→5 nested top-level `match`es, no imports | 8/9/11/10/14 s | **not nesting depth** |
| 50/100/200/400 near-identical defs in one file | 4/5/6/8 s | **not program size in defs** |

The last two are worth keeping because they refute the hypothesis this document carried in Result 2
— "the cost SCALES with program size, so a super-linear step inside F is the likely remaining
cause". Program size in defs is flat and linear; so is nesting. The scaling variable is the reached
call graph.

### Which phase — settled by one observation

Whether the program has PRINTED anything separates front cost from run cost, and it costs one run:

    scljet-hello  F       200 s cap -> no output at all      the front has NOT finished
    scljet-hello  legacy  200 s cap -> the complete output
    scljet-jdbc   F       prints `-- insert two more rows --` before its cap -> its front DID finish

**So the two slow cases are slow in different phases** and no single probe settles both. Run the
print test first.

### The prediction was written down first, then TESTED — and it held

If the cost is the reached subgraph, a program importing FAR LESS than `hello` but calling one
function deep in the SQL engine must be just as slow. Two imported names from `jdbc.ssc` against
`hello`'s dozen:

```scalascript
[SqlInteger, SqlText, buildTableDatabase](std/scljet/index.ssc)
[jdbcOpen, jdbcExecuteUpdate](std/scljet/jdbc.ssc)

buildTableDatabase(…) match
  case Left(e) => println("nope")
  case Right(image) =>
    val r = jdbcExecuteUpdate(jdbcOpen(image), "INSERT INTO books VALUES (2, 'x', 1985)")
    println("done")
```

**`rc=124` at a 400 s cap** — indistinguishable from `hello`, with a sixth of the imports. Had it
been fast, this result would have been wrong and the reached-graph story with it; it was written
before the run for exactly that reason.

**This is also the smallest reproduction on record**: ten lines, two modules, one call.

### What is NOT established

Whether any of this is a regression. Result 1 above records `scljet-jdbc` at 27.94 s on F against
today's >1200 s — but `legacy` moved 9.35 s → 88 s on the same case over the same period, and no
change of ours is in `legacy` at all. Something shared moved by roughly 9×, so cross-day absolute
numbers cannot carry a regression claim. Only same-day ratios are evidence here.

### Result 3b (same day) — a MINIMAL PAIR, and the link to the class-size limit

The pair is one line apart, same file, same imports, and it carries its own control:

```scalascript
    val db = jdbcOpen(image)
    println("opened")                                        F 21s   legacy 25s   parity
```
```scalascript
    val db = jdbcOpen(image)
    val r  = jdbcExecuteUpdate(db, "INSERT INTO books VALUES (2, 'x', 1985)")
    println("no match")                                      F >400s  legacy 55s   >7x
```

**The legacy column is why this is a finding and not an anecdote** — it was missing from the first
eight probes of Result 3, and without it "F is slow here" is an assumption. Added.

Two more candidates died today:

- **a single-quoted substring inside a double-quoted string** (`'x'` inside the SQL). Every slow
  variant had one and every fast one did not, which is a real correlation and a wrong cause: on its
  own, `val sql = "… (2, 'x', 1985)"` is 3 s.
- **the `--bytecode` class-size fallback firing.** It fires for BOTH members of the pair — the fast
  one included — so its presence does not discriminate.

**But the fallback is still the lead, because of what it means here.** These runs are `run --v2`,
and the message comes from F's OWN nested `coreir.eval`: the front tries direct ASM and falls back
to VM interpretation when the emitted class is too large (`specs/v2-f-bytecode-probe.md`, the
V-6b.3 work, which measured direct ASM at **4.38× faster** on the product SClJet F0). Both members
fall back; the difference is the SIZE of the nested eval each one forces.

**REFUTED 2026-08-10, by the split it predicted.** `ssc/gen/Entry` now spills into siblings
(`abf9a4075`) and `scljet-hello` compiles on the bytecode lane. Re-running the pair:

    the fallback stopped firing        as predicted
    B5 under F   rc=124 at 500 s       UNCHANGED — the >400 s row did NOT collapse

**The class-size limit was not the cause of the F cost.** Removing it changed the timing not at all,
so the paragraph below is wrong about the mechanism and is kept only because the prediction it
carried is what made the test worth running. What survives from it: the fallback WAS firing, it is
gone now, and it was not what cost the time.

**So the F cost is still unexplained, with everything below still refuted and one more candidate
added to the list.** The reached-call-graph result (Result 3) stands; what does not is any story
that routes it through the bytecode fast path.

**The superseded claim, kept for the prediction it made:** the F cost and
`scljet-jdbc-facade-bytecode-class-too-large` are the same problem seen from two sides. The class-size limit is what denies F its fast path, and the ~4.38× it loses is the right
order for the gap measured here. Splitting `ssc/gen/Entry` is therefore not only a `--bytecode`
capacity fix; it is the candidate fix for this entry. That prediction is cheap to test the moment
the split lands: re-run this minimal pair and see whether the fallback stops firing and the >400 s
row collapses.

**Which phase, for this pair:** `B` prints `opened`, so its front finished; `B5` prints nothing at a
300 s cap, so its front did not. Consistent with the reached-graph result — `B5` reaches the SQL
engine through `jdbcExecuteUpdate` and `B` does not.


## Result 4 (2026-08-12) — the phase answer was WRONG, and the replacement does not yet close

### The phase marker Result 3 relied on was invalid

Result 3 concluded "for `scljet-hello` the cost is in F's compilation" from the fact that the
program printed nothing at a 200 s cap. **That test was wrong**: `scljet-hello`'s first `println`
sits after `buildTableDatabase`, `jdbcOpen` and TWO inserts, so "no output" never meant "the front
has not finished".

With a marker as the genuinely FIRST statement:

    legacy    43s  FRONT-DONE          45s  all books, newest first: …      (program: ~2 s)
    F         35s  FRONT-DONE          then nothing for 365 s

**F's FRONT IS FASTER THAN LEGACY'S** — 35 s against 43 s. Everything after the marker is the gap.
So neither "F compiles slowly" nor "the reached graph costs compile time" survives; both earlier
readings of the phase question are withdrawn.

### The IR is not worse — it is better typed

Both fronts' IR dumped for the same subject (`ssc.cli run` on the tower runners, whose stdout IS
`#coreir.encode(ir)`), then histogrammed:

| prim | F | legacy |
|---|---|---|
| `__arith__` | 1277 | **1677** |
| `__eq__` | 481 | **707** |
| `__method__` | 1105 | 1137 |
| `i.add` / `i.eq` / `i.lt` | 125 / 91 / 47 | 5 / 0 / 4 |
| **total prims** | **9656** | **9656** |

Identical prim count, and F emits FEWER dynamic dispatches and more typed ones. "F emits worse
code" is refuted in the obvious sense.

### What the profile says, and the one concrete defect it names

JFR, `settings=profile`, 240 s of the F run — hottest leaves:

```text
884  ssc.gen.Entry$1.lam$21285      757  ssc.Emit$.s2
769  java.lang.String.hashCode      636  ssc.Emit$.extend1
571  ssc.Emit$.prim2                567  ssc.Emit$.dataArity
454  ssc.Prims$.methodDispatch4     444  HashMap$Node.findNode
305  ConcurrentHashMap.computeIfAbsent
```

**A real, bounded defect falls straight out of that**: `Emit.s1/s2/s3` are

```scala
private def s2(op: String): Slot[Prims.Fn2] = slot2.computeIfAbsent(op, mkSlot2)
```

— a `ConcurrentHashMap` lookup keyed on the prim's NAME on **every prim invocation** in generated
bytecode, where the VM resolves the same prim ONCE at compile time. Hashing and lookup together are
~24 % of samples. Worth fixing on its own.

**But 24 % cannot explain 180×, and it is not claimed to.**

### THE CONFLICT, stated rather than resolved

Two measurements disagree and I could not reconcile them:

- the marker says the front finished at 35 s and the remaining 365 s is the PROGRAM;
- the profile is dominated by `ssc.gen.Entry$…` and the `Emit.*` shims — generated BYTECODE — in
  every 30 s bucket of the recording, and under `run --v2` the user program is supposed to be
  interpreted, not compiled.

`SSC_FRONT_TRACE=1` shows the nested direct-ASM path firing **exactly once**, as designed, so a
runaway re-compilation is ruled out.

Either `lam$21285` is F0 (and the marker is lying about the phase) or it is the user program (and
`run --v2` is compiling it to bytecode without saying so). **The next measurement decides it and
nothing else should be attempted first**: dump the emitted class's method count and compare it
against F0's own lowering — F0 is ~3 000 lines, the user program measured 29 463 methods, so
`lam$21285` picks a side on its own.

Guessing between the two is exactly what produced the two withdrawn conclusions above.

### The conflict is RESOLVED, and both readings were partly wrong

`SSC_GEN_STATS=1` prints two emissions per F run:

    classes=1  methods=3 968    <- F0, the compiler itself
    classes=3  methods=29 288   <- the USER program

`lam$21285` is far past 3 968, so the hot bytecode is the **user program**. And the same instrument
under `legacy` emits `classes=3 methods=29 464` — **both fronts compile the user program to
bytecode**, so the class split landed on 2026-08-10 is not the cause; only F's bytecode is slow.

### An execution census settles what the IR histogram could not

`SSC_PRIM_CENSUS=1` counts prim EXECUTIONS (`Emit.s1/s2/s3` and, after a correction, `Emit.arith`,
which bypasses the slots and was invisible in the first version).

| | legacy | F |
|---|---|---|
| `__eq__` | 110 531 | **161 377 209** |
| `scharAt` | 6 581 | 1 204 558 |
| `sslice` | ~0 | 811 229 |
| `cell.get` / `__method__` / `cell.set` | equal | equal |

**But that 161 M is NOT the gap**, and this is the trap the census itself caught: the FAST variant
(`B`, 21 s) and the SLOW one (`B5`, >400 s) have *identical* counts — 161.2 M against 161.0 M
`__eq__`, `arith:+` 3.09 M against 3.10 M, `scharAt` 1.196 M both. The 161 M is F0 compiling, a
fixed cost of every F run, and naming it as the cause would have meant pointing at the largest
number in the table.

**So the number of operations is the SAME and the time differs 20×** — which kills "F does more
work" as thoroughly as it killed "F compiles slowly" and "F emits worse code". Whatever the gap is,
it is per-operation cost or machinery the census does not count, not operation count.

### What the profile does establish

Not GC: 3.9 s of pause in a 240 s recording. Allocation is heavy (top type `ssc.Value[]`, allocated
in `Emit.extend1`, which copies the WHOLE environment array per binding) but the collector absorbs
it.

The single largest identified cost is the **per-call slot lookup**:

    s2 837 + s1 502 + String.hashCode 769 + findNode 444 + computeIfAbsent 305 + String.equals 195
    = 3 052 of 9 932 samples = 31 %

`Emit.s1/s2/s3` are `slotN.computeIfAbsent(op, mkSlotN)` — a `ConcurrentHashMap` lookup keyed on the
prim's NAME on every prim invocation in generated bytecode, where the VM resolves each prim once at
compile time. **That is a real defect worth fixing on its own merits, and it is not claimed to
explain the 20×** — 31 % of samples bounds it at about 1.45×.

The clean fix is emitter-side, not here: `JvmByteGen` should resolve each op to a static field once
at class init and `GETSTATIC` it, instead of passing the name string per call. That is a different
file and a different claim, and the 31 % above is the number that justifies it.

### Still unexplained, stated plainly

`B` and `B5` differ by one line — a single `jdbcExecuteUpdate` call — and differ 20× in time while
EVERY instrument shows them doing the same work:

| instrument | B | B5 |
|---|---|---|
| prim executions (`__eq__`) | 161 240 733 | 161 052 767 |
| `arith:+` / `arith:++` | 3.09 M / 2.57 M | 3.10 M / 2.56 M |
| `extend1` calls / elements copied | 7 595 677 / 24 796 053 | 7 721 666 / 25 594 774 |
| profile, hottest frames | — | within 2.4 % of B on every frame |

**Two hypotheses died here.** "F executes more operations" — the counts are equal. And
"`extend1` copies huge environments" — `maxEnv` is 19–23 in every run, so the arrays are tiny and
the `System.arraycopy` cost is not the mechanism, however plausible the allocation profile made it
look.

**A CONFOUND worth recording, because the number invites a wrong reading.** `extend1` shows F at
7.6 M calls against legacy's 18 K, which looks like a 400× indictment of F's lowering. It is not
comparable: the counter lives in `Emit`, which only the BYTECODE lane goes through. Under `F` the
compiler F0 itself runs as bytecode and lands in the count; the legacy front runs on the VM and is
invisible to it. That row compares bytecode against interpretation, not front against front.

**Where the difference DOES show is VM-side.** In the B-vs-B5 profile diff the frames that grow are
`ssc.Runtime$.run` (+0.7), `ssc.Runtime$.extend` (+0.8), `ssc.Prims$.arithFast` (+0.7),
`ssc.Compiler$C.compile$$anonfun$3` (+0.8) — all interpreter, none of it bytecode.

**So the next instrument must be in `Prims`/`Runtime`, not in `Emit`.** Everything measured so far
lives in the bytecode shim and is therefore blind to exactly the half where the difference appears.
That is the single concrete next step, and it is a different file and a different claim.


## Result 5 (2026-08-12) — the INSERT is not the cost, and the timings behind Result 3b are NOT reproducible

### Bisecting inside the slow call: the INSERT takes ~1 second

`scljet/sql.ssc` instrumented with ordered markers on a scratch copy, run under F:

```text
 90s  MARK 0 built                     <- buildTableDatabase returns
 90s  MARK 2 parsed / 2C tableContext / 2C3 colNames / 2e rowids assigned
 91s  MARK 3 executed
 91s  MARK 4 done
```

**Everything from entering `jdbcExecuteUpdate` to finishing it spans one second.** The 90 s is
`buildTableDatabase` plus the front. So `jdbcExecuteUpdate` — the one line that separates the fast
variant from the slow one in Result 3b — is not where the time goes.

### Two ways this bisect misled me, both worth recording

**A missing marker was read as "it stalls here".** Twice the next marker simply had not been
REACHED before the cap: `MARK 2a` looked like a stall inside `tableContext`, and `MARK 2C3` looked
like a stall inside `codePointsToString`. Adding one more probe made both appear. A marker that does
not print means "not yet", not "stuck here", and the difference needs a longer cap, not a
conclusion.

**A promising cause survived two probes and was still wrong.** `codePointsToString` accumulates a
String in a loop over code points, which is the classic quadratic shape — and both fronts see the
same 68-element list, and a standalone probe of that exact loop shape answers identically on both.

### And the number the whole thread rests on does not reproduce

| | measured earlier | re-measured today |
|---|---|---|
| `B` under F | 21 s | 129 s |
| `B` under legacy | 25 s | **206 s** |

Same build, same file. The host is at **load average 64 with 26 JVMs** from other agents, and
`legacy` — which none of this work touches — moved by 8×. `contended-host-needs-alternating-ab`
records identical code spreading 2.5× at load 5.5; this is an order of magnitude past that.

**So every WALL-CLOCK claim in Result 3b and Result 4 is suspended**, including the 20× pair and the
"F's front is faster, 35 s against 43 s" split. They were taken across hours on a machine whose load
was not controlled, and they must be re-taken alternating on a quiet host before anything is built
on them.

**What survives, because it is load-INDEPENDENT and was measured in single runs:**

- prim execution counts — `B` and `B5` identical (161.2 M / 161.0 M `__eq__`, `arith:+` 3.09 M /
  3.10 M, `extend1` 7.60 M / 7.72 M calls, `maxEnv` 19–23);
- IR composition — 9 656 prims on both fronts, F with fewer `__arith__`/`__eq__` and more `i.*`;
- emitted method counts — 3 968 for F0 and ~29 400 for the user program, on both fronts;
- the marker ORDER above: the INSERT is one step, not the bulk.

**The next measurement is a scheduling problem, not a technical one.** Take the pair alternating,
several rounds, on a host with no sibling builds running, and only then decide whether there is a
gap to explain at all.
