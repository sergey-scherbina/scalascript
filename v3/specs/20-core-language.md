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
| strings | `length` `isEmpty` `nonEmpty` `toUpperCase`† `toLowerCase`† `trim` `split` `charAt` `substring` `indexOf` `replace` `contains` `startsWith` `endsWith` |
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

**† `toUpperCase` / `toLowerCase` — SUPPORTED BUT NOT YET DEFINED, and the gap is the same one §3
exists to close.** They are listed because programs call them and both lanes answer. What is not
settled is WHAT they answer.

`String.toLowerCase()` on the JVM takes no argument and uses the **default locale**, so its result
is a property of the environment rather than of the string: in Turkish `"I"` folds to a dotless
`"ı"`, not `"i"`. The js lane's `toLowerCase` is locale-independent. So `"TITLE".toLowerCase` is
`"tıtle"` on one lane and `"title"` on the other, decided by `-Duser.language` — **exactly the
host-dependent semantics §3 refuses for the lexer, arriving instead through the standard library.**

This is measured, not anticipated. UniML carried the same defect until 2026-08-06 and it was proved
in the locale, not by inspection: the same Markdown document parsed differently under `tr`, because
HTML tag names containing an `I` (`<LI>`, `<TITLE>`, `<IFRAME>`, `<DIALOG>`) mis-folded, and a
heading `TITLE` produced the anchor `t-tle`. `MdLocaleIndependenceSpec` is the regression.

**Two ways to settle it, and Tier 0 must pick one:**

1. **ASCII fold.** `A`–`Z` ↔ `a`–`z`, everything else unchanged. One range comparison, every lane
   agrees, and it is the same shape §3's table already uses. Narrower than Scala.
2. **Locale-independent Unicode fold.** `Character.toLowerCase(char)` is defined by the Unicode data
   alone — unlike `String.toLowerCase()` it is NOT locale-sensitive — so it folds every script and
   still agrees across lanes. Costs the table §3 discusses above.

What is not available is leaving them listed with no definition: the table would then promise a
portability this operation does not have. UniML took (2) for CommonMark link labels, where the spec
demands Unicode folding, and (1) everywhere the decision is ASCII by construction — tag names,
schemes, slugs.

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
| **uppercase start** | `A`–`Z`, or a code point in the 606 Unicode uppercase RANGES |

Every line but the last is a range comparison. There is no table on any host — the last line is a
table IN THE SOURCE, which is a different thing, and it is the one class that could not be decided
away. Why it exists and why it is not one comparison:

**It decides constructor from binder.** `case Foo =>` tests a constructor; `case foo =>` binds. Get
it wrong toward "constructor" and an unknown name is a LOUD refusal; get it wrong toward "binder"
and the pattern silently matches EVERYTHING. Those are not symmetric, and the second is the failure
this section's own philosophy is arranged to avoid.

**So why not `A`–`Z` or ≥ U+0080, matching identifier-start?** One comparison, no table, and it errs
loud — but it makes every non-ASCII lowercase name a constructor, so `case имя =>` stops binding.
A language whose patterns cannot bind a Russian or Greek name is a worse outcome than 606 ranges of
data, and this project's author writes Russian.

**ONE copy, shared, which is better than a gate watching two.** The ranges live in
`alphabet/src/Alphabet.scala` — a SOURCE DIRECTORY that v3's kernel build and UniML's both include,
not a library either depends on. `v3/ssc3` compiles `v3/src` and `alphabet/src` together, so the
kernel still builds with UniML absent (measured) and self-hosting still means "compile these repo
files"; UniML adds it as an unmanaged source directory, in `uniml/build.sbt` **and** in the root
`build.sbt`, because two build definitions cover the same sources and saying it once left the other
one compiling against a package it could not see.

It was two hand-copied tables for a day, with a gate sweeping the BMP for drift. A gate that watches
for drift is worth having only while sharing is impossible. What the gate asserts now is different:
that both NAMES answer alike over the BMP (either could be mis-delegated), that exactly ONE
definition of the table exists in the repository, and how many code points we differ from Java's
classifier on. All three observed failing — a broken delegation gives 1143 disagreements, and a
second table planted in the same file is caught only because the check counts DEFINITIONS; counting
files missed it, which is how that version was found.

**Recorded because it was got wrong first:** for one day `Chars.isUpperStart` called
`Character.isUpperCase`, justified by measuring agreement with UniML over the BMP. That measurement
was taken on one JVM, which is exactly the guarantee this section says not to rely on.

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
cleanest and is disqualifying. `Prim` is the right door for I/O and the wrong one for language
semantics.

**⚠️ The reason this section used to give for that was MEASURED AND IS FALSE.** It said "the same
source would then lex differently on the JVM, on JS and on the v2 VM". As a claim about the two
COMPILE hosts that is simply untrue: JVM and Scala.js agree exactly — 1,169 uppercase code points,
identical hash, checked character by character and now frozen on both lanes as a canary
(`HostCaseAgreementSpec`). Keeping a correct conclusion propped up by a wrong reason is how the
conclusion gets overturned later by someone who checks the reason.

The reason that replaced it was **also wrong, and this paragraph is the correction.** It said the
real divergence was between ScalaScript's own runtimes — interpreter via `Character.isUpperCase`, js
via `/\p{Lu}/u`, 42 BMP characters apart — with `case Ⅷ =>` matching on one lane and binding on the
other. I took that from a sprint note dated 2026-08-05 and wrote it in here as current fact.
Measured 2026-08-07, it is not:

- **The js runtime no longer tests `\p{Lu}`.** `core-collections.mjs` uses `/\p{Uppercase}/u`, which
  DOES include `Other_Uppercase`, and its comment names the exact trap: *"`\p{Lu}` is NOT
  isUpperCase (Java adds Other_Uppercase, e.g. Roman numerals)"*. It is held by
  `tests/e2e/js-char-classification-parity.sh`, which reports **"PASS: both lanes agree on every
  probe"**.
- **`case Ⅷ =>` was never governed by that anyway.** Constructor-versus-binder is decided by the
  FRONT, not the runtime, and every front spells uppercase as ASCII: `isUpper = (c) => c >= 65 && c
  <= 90` in `ssc1-front.ssc0`, `mira-front.ssc0` and `ssct-front.ssc0` alike. `Ⅷ` is U+2167, so it
  is a BINDER on every lane by construction — one range comparison, no host call, no table.

So the conclusion of this section survives its second wrong reason, which is exactly why the reason
is worth stating precisely: **`Prim` is the wrong door for language semantics because a rule the
host answers is a rule the language does not own.** That argument needs no divergence to exist
today; it is about where the decision lives, and a divergence that has since been fixed is evidence
for the rule rather than the rule itself.

The case TABLE below remains justified on its own ground — making every lane agree about `isUpper`
as a LIBRARY operation, which is what the parity gate now enforces — and not on a pattern-matching
divergence that the fronts' ASCII rule already prevents.

**So the alphabet and CASE are two questions, and only the first is tableless.** The table above
stands: every line a range comparison, no table, on any host. Case cannot be done that way — making
every lane agree requires a baked table, which is what UniML now carries (1,143 code points in 606
ranges, consulted only after an ASCII fast path and once per identifier TOKEN rather than per
character). **That is Sergiy's decision of 2026-08-05**, taken after the tableless answer was
implemented, measured and rejected on the measurement. This section's title — "so it needs no
tables" — is therefore true of the identifier alphabet and not of case, and saying so here is the
point of this paragraph.

**Open, and deliberately not decided here:** v3 carries its own copy of the alphabet in `Chars`
while UniML has the shared module this section asks for. Either adopt the shared one or record that
the two copies are intentional and state what they are obliged to agree on — a value written down
twice and agreeing only by memory is the shape this repository paid for three times on 2026-08-06
alone (the uniml version, the `Main.scala` coordinate, and the sbt-plugin's fourth version, still
open in `tests/BUGS.md`).

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

This is v1-parity behaviour, and v3 INHERITS it rather than forking it: the corpus expectations are
the ones every other lane is held to, and both of v3's lanes match them. Two consequences worth stating
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

## 4a · Where v3 differs from the other lanes — CORRECTED 2026-08-06

**The first version of this section was wrong about WHICH LANE, and the error is worth keeping.** It
said v3 accepted five constructs that "v1 rejects". It does accept them — but v1 accepts them too.
What rejects them is the SELF-HOSTED front, which `bin/ssc run` and `ssc-tools run --v2` both use.

I ran `bin/ssc run` for a whole session and reported its answers as v1's. The lane map is already
written down in this repository — `bin/ssc run` is NATIVE, `ssc-tools run --v1` is the tree-walking
interpreter — and I had it. **A measurement is evidence about the command you actually ran.**

Re-measured, all six on `ssc-tools run --v1`:

| construct | interpreter (`--v1`) | self-hosted front (native / `--v2`) | v3 |
|---|---|---|---|
| a trait's concrete member inherited by a subclass | `area 9` ✓ | `RuntimeException: __method__` | `area 9` |
| `case A \| B =>` | `ab` / `ab` ✓ | first alternative only | `ab` / `ab` |
| a NESTED block comment | `3` ✓ | a native-front exception | `3` |
| `xs ++ ys ++` continued on the next line | `1,2,3` ✓ | `Stub` at exit 0 | `1,2,3` |
| `while i < 3 do i = i + 1` | `3` / `after` ✓ | nothing at all, exit 0 | `3` / `after` |
| `Cfg.n = 7` on an object member | refuses, with a position | `0`, silently | `7` |

So v3 is **level with the interpreter**, not ahead of it, on five of the six — and ahead of the
self-hosted front on all six. On the last, v3 implements what the interpreter honestly refuses.

The four defects on the self-hosted front are filed in `BUGS.md` as `selfhost-front-*`.

**What this changes about `N`.** Nothing. `N` is measured through `ssc3 run`, which is v3's front on
the v2 VM, against expectations every lane is held to. But it does change what "compatibility" means
in this document: v3's target is the LANGUAGE as the interpreter defines it, and the self-hosted
front is a peer implementation with its own defects — not the definition.

## 4a1 · TYPE-DIRECTED RESOLUTION IS DEFERRED, AND THIS IS THE DEBT — decided 2026-08-13

**The owner's decision, recorded here rather than in a sprint board because a board gets rewritten
and this outlives it:** typeclass dispatch in v3 will eventually be done PROPERLY — static types, a
type checker, type inference — and what is being built now is an approximation taken deliberately,
with its edges known.

**What is being built now.** An extension method declared inside a `trait` or a `given … with`
resolves at LOWERING time from two static sources:

1. the CONSTRUCTOR of the receiver expression, because a runtime tag names exactly one type head —
   `Cons`/`Nil` belong only to `List`, `Some`/`None` only to `Option`, so tag → type is a function
   and not a guess;
2. the receiver parameter's DECLARED type, `Param.tpe`, which the front keeps as text.

When several instances provide the method for one type, the SUBTRAIT wins (`Traversable[T] extends
Foldable[T]`, so `listTraversable` beats `listFoldable`); unrelated traits are refused by name.

**What that approximation cannot do, stated so nobody discovers it as a bug:**

- `Stmt.Val` records NO declared type. `val xs = List(1, 2, 3)` gives the lowering nothing, so a
  receiver bound by a `val` is resolved by its constructor or not at all. Inferring `List[Int]` from
  the initialiser is type INFERENCE and is not being built.
- A value whose static type is WIDER than its runtime shape — declared `Any`, holding a list —
  resolves here where Scala would refuse the call. That is a widening, not a wrong answer, and it is
  the one place this deliberately differs from the language it follows.
- The element type is invisible. `Foldable[List]` does not care, but any instance that varies by
  element type cannot be told apart this way.

**Why not do it properly now.** I-2 says the core carries no enforced notion of types, and
`Param.tpe` is text precisely because "Tier 0 has nothing to parse it into". A type checker is not a
larger version of this change; it is a different project, with its own decision to make about I-2.
The approximation is chosen so the typeclass tower can load and be measured — `Foldable`,
`Functor`, `Applicative`, `Monad`, `Traversable` all exist in `std/` today and none of them compiles
— rather than to be the final answer.

**When it is done properly, this section is what to delete.** The two sources above become one — the
static type — and the widening in the second bullet stops being legal.

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
