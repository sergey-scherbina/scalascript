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
