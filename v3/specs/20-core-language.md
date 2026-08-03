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

**This table is MEASURED, not planned.** Every row was checked by running it; the first version of
this section listed `Char`, tuples, guards and `Array` because they were intended, and none of them
worked. A spec that describes something other than what exists is worse than no spec — it is a
promise the reader cannot tell from a fact.

**Implemented — runs on BOTH lanes** (`v3/ssc3 run` through the v2 bridge, `ssc3 exec` on v3's own
executor), and every row has a fixture under `v3/tests/front/`:

| group | constructs |
|---|---|
| literals | `Unit` `Boolean` `Int` (i64, incl. the `L` suffix) `Double` `String` |
| interpolation | `s"… $name … ${expr} … $$"`, holes may contain anything, including strings |
| definitions | `def` (recursive and mutually recursive), `val`, `var`, local and top-level |
| module scope | a top-level `val`/`var` is a module GLOBAL, visible inside any `def`; locals shadow it |
| expressions | application, operators with precedence, `if`/`else`, blocks, `while`, assignment |
| logic | `&&` / `\|\|` with short-circuit — lowered to `If`, never to a binary op |
| functions | lambdas `(x) => e` and `{ x => e }`, closures with capture, `f(x)` on a value |
| data | `case class`, `enum` with `case` members, `object` as a namespace |
| matching | `match` with constructor, literal, binding and wildcard patterns; `h :: t` |
| lists | `List(…)`, `::`, `Nil`, `Some`/`None`; `size` `head` `tail` `map` `filter` `sum` `mkString` `reverse` |
| strings | `length` `toUpperCase` `toLowerCase` `trim` `isEmpty` `split` |
| errors | `try`/`catch` binding one name, `throw` |
| files | literate `.ssc` — the program in ```` ```scalascript ```` fences, line numbers preserved |
| scripts | top-level statements ARE the program; `main()` runs after them if defined |
| output | `println`, and per-block auto-output of a non-Unit tail |

**Not implemented, measured on 2026-08-03** — each fails with a positioned diagnostic naming it,
never with a wrong answer: `Char` literals, tuples, pattern guards (`case n if …`), `Array` as a
source construct (the IR has arrays; the front has no syntax for them), `foreach`, `for`
comprehensions, `trait`, `extension`, `given`/`using`, imports across files, and typed `catch` arms
(the type is consumed and discarded, so an arm catches everything).

**Where that leaves compatibility:** cross-file imports are the dominant remaining blocker — 73 of
the 123 unknown-name refusals are three names from other files. §4's number is measured against
single-file cases in practice, and that is a property of the corpus rather than a choice.

## 3 · The lexical alphabet — decided here so it needs no tables

The portable subset bans host character classification (`isLetter`, `isDigit`, `isWhitespace`,
`java.lang.Character`), so v3 classifies characters itself. That reads like a burden and is not one,
because the expensive part was never "we cannot call the host" — it is that `Character.isLetter`
covers thousands of Unicode ranges and needs tables to answer. **Deciding the alphabet removes the
tables**, and this is a language decision rather than an implementation problem:

| class | definition |
|---|---|
| whitespace | space, tab, CR, LF, FF — and nothing else |
| digit | `0`–`9` |
| identifier start | `a`–`z`, `A`–`Z`, `_`, `$`, **or any code point ≥ U+0080** |
| identifier part | identifier start, or digit |
| operator character | `+ - * / % < > = ! & \| ^ ~ : # @ ?` |

Every line is a range comparison. There is no table, on any host.

**Why "any code point ≥ U+0080" rather than a Unicode letter test.** It is one comparison instead
of a table, and it accepts identifiers written in any script — Cyrillic, Greek, CJK — which a letter
test would only reach by carrying the tables we are trying to avoid. It is more permissive than
Scala, which would reject some of what this accepts, and that is the **safe direction for a
compatibility lane**: every valid Scala identifier is still an identifier here, so no existing
program changes meaning. Only programs Scala rejects are additionally accepted. Being wrong in the
other direction — rejecting something a user reasonably wrote — is the one this ordering avoids.

Two consequences worth naming. It is *faster* than host classification (a range check against a
Unicode table lookup, in a compiler's hottest loop). And it is *checkable*: a sweep over the whole
code-point range on the jvm lane, comparing this classifier against Java's, states exactly where we
differ on purpose — which is the difference between a decision and an accident.

**One implementation, not one per dialect.** UniML today classifies per dialect and privately —
`JsonLexer.isWhitespace` compares four string literals, `Source.scala` carries its own `Unicode`
object. That is the duplicated-helper shape this repository keeps paying for. The classifier is a
single shared module in the portable subset, and it is *less* code than the copies it replaces.

**Not a `Prim`.** Routing classification through the host boundary is the abstraction that looks
cleanest and is disqualifying: the same source would then lex differently on the JVM, on JS and on
the v2 VM, making the language's syntax host-dependent. `Prim` is the right door for I/O and the
wrong one for language semantics.

## 4 · How the number is produced

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
