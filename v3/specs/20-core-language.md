# SSC3 core — the language v3 commits to, and how compatibility is measured

> Invariant I-5 of [`00-charter.md`](00-charter.md). The IR every construct here lowers to is
> [`10-ssc-ir.md`](10-ssc-ir.md).

## 1 · Why a named core rather than "all of ScalaScript"

v3 starts from an IR, not from the existing front, so its coverage starts small and grows. Two ways
to describe that state, and only one of them is usable:

- *"v3 supports most of ScalaScript"* — unfalsifiable, ages badly, and hides regressions;
- *"v3 core is this list of constructs; against the conformance corpus it passes `N` of `M`"* —
  a number that a gate can hold and a person can read.

This file is the list. The number is produced by `v3/corpus-report.sh` and is **non-regressing**:
`N` may go up in any commit and may go down in none.

## 2 · The core

**This table is MEASURED, not planned, and it has been wrong before.** Its first version listed
`Char`, tuples, guards and `Array` because they were intended and none of them worked. It was
re-measured on 2026-08-03 and again on **2026-08-05**, and the second re-measure is the reason this
warning stays at the top: between the two, the table drifted the OTHER way — it still called `trait`,
`Char`, tuples, guards and `Array` unimplemented weeks after they landed. A spec that describes
something other than what exists is worse than no spec, in both directions.

Every row below has a fixture under `v3/tests/front/` that runs on **both lanes** — `ssc3 run`
through the v2 bridge and `ssc3 exec` on v3's own executor — and is compared against a checked-in
expectation. 46 fixtures at the time of writing.

| group | constructs |
|---|---|
| literals | `Unit` `Boolean` `Int` (i64, incl. the `L` suffix) `Double` (incl. exponents `1e-3`) `String` `Char` |
| interpolation | `s"… $name … ${expr} … $$"`, holes may contain anything |
| definitions | `def` (recursive, mutually recursive, parameterless), `val`, `var`, local and top-level |
| arguments | DEFAULTS on `def` and on `case class` fields; NAMED arguments in any order |
| module scope | a top-level `val`/`var` is a module GLOBAL, visible inside any `def`; locals shadow it |
| expressions | application, operators with Scala's precedence and associativity, `if`/`else`, blocks, `while`, assignment |
| continuations | a value on the next line (`val x =` …), an `else` on its own line, a line ending in a binary operator |
| statements | a single-line `if`/`while`/`for` body may be an ASSIGNMENT, not only an expression |
| logic | `&&` / `\|\|` with short-circuit — lowered to `If`, never to a binary op |
| operators | arithmetic, comparison, bitwise `<< >> >>> & \| ^`, `++` `:+` `+:` `::` |
| functions | lambdas `(x) => e` and `{ x => e }`, closures with capture INCLUDING nested capture, `f(x)` on a value, function types in signatures (`f: Int => Int`) |
| data | `case class` **with a body of methods**, `case object`, `enum` with `case` members, `object` as a namespace |
| traits | `trait` with abstract AND concrete members, `extends`/`with`, `override`, inheritance of concrete members, dispatch by the receiver's tag at RUN TIME |
| tuples | `(a, b)` to arity 8, `._1`…, `case (a, b) =>`, `val (x, y) = e`, tuple types in signatures |
| matching | constructor, literal, binding and wildcard patterns; `h :: t`; NESTED to any depth; guards (`case n if …`); alternatives (`case A \| B`); `{ case … }` as a lambda |
| lists | `List(…)` `::` `Nil` `Some`/`None`; `size` `head` `tail` `map` `filter` `flatMap` `foreach` `sum` `mkString` `reverse` `sorted` `zip` `++` `:+` `+:` |
| strings | `length` `isEmpty` `nonEmpty` `toUpperCase` `toLowerCase` `trim` `split` `charAt` `substring` `indexOf` `replace` `contains` `startsWith` `endsWith` |
| arrays | `Array(…)`, `a(i)`, `a(i) = v`, `a.length` |
| copy | `x.copy(field = v)` on any `case class` |
| maps and sets | `Map(k -> v, …)` with `m(k)` `size` `contains` `get` `getOrElse` `keys` `values`; `Set(…)` with `size` `contains` `toList`; `k -> v` builds a `Pair` |
| application | curried — `f(a)(b)`, and with it `foldLeft(z)(f)`; a function held in a FIELD is callable |
| local functions | a `def` inside a `def`, recursive and capturing — lifted with captures as leading parameters |
| comprehensions | `for x <- xs do e` / `yield e`, several generators, `if` filters — desugared to `foreach`/`map`/`filter`/`flatMap` |
| generics | type parameters and type arguments are PARSED AND ERASED, everywhere they may appear |
| errors | `try`/`catch` binding one name, `throw` |
| files | literate `.ssc` (```` ```scalascript ```` / ```` ```scala ```` fences, line numbers preserved); `//` and `/* … */` comments, the latter NESTED |
| scripts | top-level statements ARE the program; `main()` runs after them if defined |
| output | `println`, per-block auto-output of a non-Unit tail, and one printing convention shared by both lanes |

**Not implemented, measured 2026-08-05** — each fails with a positioned diagnostic naming it, never
with a wrong answer:

| construct | why, where it is not just "not yet" |
|---|---|
| `given` / `using` | needs type-directed resolution. This is the one item on the list that Tier 0 cannot reach by adding syntax. |
| `extension` | |
| `lazy val`, varargs (`xs: Int*`) | **NOT IN THE LANGUAGE AT ALL** — measured 2026-08-06: v1 answers `unbound global: lazy` and `arity: 1 expected, 3 given`. They were on this list as v3 gaps, which was wrong: implementing them would have made v3 accept programs no other lane runs, for no compatibility gain. The measurement that saved the work took one minute. |
| an `object` member that is not a `def` | |
| qualified enum access (`C.Red`) | the case is reachable unqualified |
| a `catch` with several typed arms | a single-name `catch` works and catches everything |

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

## 3a · Printing, and why it is v1's convention rather than Scala's

Both lanes print the same thing, and what they print was MEASURED off the reference lane rather than
chosen:

| value | v3 prints | Scala would print |
|---|---|---|
| `3.0` | `3` | `3.0` |
| `-0.0` | `0` | `-0.0` |
| `123456789.0` | `123456789` | `1.23456789E8` |
| `P(1, 2)` | `P(1, 2)` | same |
| `(1, "a")` | `(1, a)` | `(1,a)` |
| an `Array` | `<foreign>` | `[I@…` |
| `"abc".charAt(1)` | `98` | `b` |

This is v1-parity behaviour, and v3 INHERITS it rather than forking it: `ssc3 run` goes through v2,
and the corpus expectations are the ones every other lane is held to. Two consequences worth stating
because each cost a debugging round:

- the executor's printer is a SEPARATE function from `Text.floatText`, which is the canonical
  `.ssir` form. One helper with two contracts is how `3.0` came to print as `3.0` on one lane and
  `3` on the other for weeks, invisible because no fixture printed a whole-number `Double`;
- a `Char` is an integer that prints as a character (v2 stores `CharV extends IntV`), which is why
  `'x' + 1` is `121` and `charAt` returns `98`. `Char` was deferred once on a misread of exactly
  this, and the misread was mine.

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

## 4a · Where v3 accepts MORE than the reference lane

Four constructs run on both v3 lanes and fail on the v1 interpreter. Recorded because a reader who
finds them will otherwise think v3 is broken:

| construct | what v1 does |
|---|---|
| a trait's concrete member inherited by a subclass | `__method__: no dispatch for .describe on Sq(3)` |
| `case A \| B =>` | `match: no arm for IndexLeafPage/0` — it takes only the FIRST alternative |
| a NESTED block comment | `structural CoreIR contains parser sentinel _err` |
| `xs ++ ys ++` continued on the next line | prints the `Stub` SENTINEL at exit 0 — a wrong answer, not a refusal |
| `while i < 3 do i = i + 1` | prints 2 of 6 lines and exits 0 with no diagnostic |

This is the SAFE direction and it does not weaken invariant I-5. Accepting a program the reference
rejects cannot change the meaning of a program the reference accepts, so `N` is unaffected; the
reverse — matching a reference that gives a WRONG ANSWER — is what would be a defect. The last two
rows are v1 defects rather than v1 limitations, and they are the shape this repository compares
OUTPUT rather than exit codes to catch.

## 4b · What the number does not measure

`N` counts corpus cases. It does not count how pleasant v3 is to write, and on 2026-08-05 Sergiy
chose the second explicitly: optimise for being able to write programs, not for `N`. The two pull
apart — the corpus wall was `trait` and generics, which are library-author features, while what
stops an ordinary program is tuples and `for`. `N` moved slowly through that stretch, and saying so
is what a measured number is for.

Two rules this repo paid for, applied here from the start:

- **Never compare exit codes.** v2 fails by printing a sentinel at exit 0; the array defect in
  §2 of the portable-subset spec exits 0 on a wrong answer. Compare *output*.
- **The gate must be observed failing.** Before `corpus-report.sh` is trusted, plant a wrong answer
  and watch each bucket move. A report that has only ever been green is a hypothesis.
