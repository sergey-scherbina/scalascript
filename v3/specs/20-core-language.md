# SSC3 core — the language v3 commits to, and how compatibility is measured

> Invariant I-5 of [`00-charter.md`](00-charter.md). The IR every construct here lowers to is
> [`10-ssc-ir.md`](10-ssc-ir.md).

## 1 · Why a named core rather than "all of ScalaScript"

v3 starts from an IR, not from the existing front, so its coverage starts small and grows. Two ways
to describe that state, and only one of them is usable:

- *"v3 supports most of ScalaScript"* — unfalsifiable, ages badly, and hides regressions;
- *"v3 core is this list of constructs; against the 381-case conformance corpus it passes `N`"* —
  a number that a gate can hold and a person can read.

This file is the list. The number is produced by `v3/corpus-report.sh` and is **non-regressing**:
`N` may go up in any commit and may go down in none.

## 2 · The core

**Tier 0 — what `SSC3-4` targets first.** Enough to write real programs, and chosen because it is
also the subset the v3 kernel itself is written in ([`30-portable-subset.md`](30-portable-subset.md)),
so reaching it is what makes v3 self-hosting.

| group | constructs |
|---|---|
| literals | `Unit` `Boolean` `Int` (i64) `Double` `String` `Char` |
| definitions | `def` (incl. nested and recursive), `val`, `var`, top-level and local |
| expressions | application, operators with precedence, `if`/`else`, blocks, `while`, assignment |
| logic | `&&` / `\|\|` with short-circuit — lowered to `If`, never to a binary op |
| data | `case class`, `enum` / sealed ADT, tuples |
| matching | constructor patterns, literal patterns, binding and wildcard, guards |
| collections | `List`, `Option`, `Array` |
| entry | `def main(): Unit` — the entry point `ssc run` calls |
| output | `println` via `Prim` |

**Tier 1 — next, in this order:** `object` and companions, `trait` with generic dispatch, closures
as values and higher-order functions, string interpolation, `for`/`yield`, exceptions.

**Tier 2 — deferred with a reason, parked in [`../BACKLOG.md`](../BACKLOG.md):** implicits and
given/using, macros, typeclass derivation, effects and handlers (the IR reserves `Perform`/`Handle`
for them, so this is a front gap and not a representation gap), separate compilation.

## 3 · How the number is produced

`v3/corpus-report.sh` runs every `tests/conformance/*.ssc` case through `ssc3 run` and compares
stdout against the same case's existing expected output — the *same* oracle the other lanes use, not
a v3-specific one. Each case lands in exactly one bucket:

| bucket | meaning |
|---|---|
| `PASS` | output matches the shared expectation |
| `DIFF` | v3 ran it and produced different output — **a defect**, and the interesting bucket |
| `UNSUPPORTED` | v3 refused it naming a construct outside §2 — honest, not a defect |
| `CRASH` | v3 neither ran it nor refused it cleanly — **a defect**, and worse than `DIFF` |

`UNSUPPORTED` must name the construct. A refusal that says only "cannot compile" is a `CRASH` for
reporting purposes, because a bucket you cannot act on is a bucket that hides work.

Two rules this repo paid for, applied here from the start:

- **Never compare exit codes.** v2 fails by printing a sentinel at exit 0; the array defect in
  §2 of the portable-subset spec exits 0 on a wrong answer. Compare *output*.
- **The gate must be observed failing.** Before `corpus-report.sh` is trusted, plant a wrong answer
  and watch each bucket move. A report that has only ever been green is a hypothesis.
