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
