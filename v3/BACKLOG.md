# ScalaScript 3 — backlog

Work that can wait, and **alternatives that were considered and parked with their trade-offs**
(P-4.2). A parked alternative costs nothing and is there the day it becomes right; the same
alternative held as "I should ask about this someday" is lost at the next reboot.

## Owner decisions, 2026-08-30 — the FULL remaining queue, ordered, with three new end-stages

The owner asked for the complete remaining-v3 inventory, set the order, and directed it into this
backlog ("все это запланировать в беклог и делать аккуратно"). The queue, each stage its own
claim(s), landed wave by wave; a stage does not start before the previous one's numbers are in:

> **⚠ AUDITED 2026-08-31 — SIX OF THESE TWELVE WERE ALREADY BUILT WHEN THE QUEUE WAS WRITTEN.**
> The queue was drafted from the boards, and the boards lagged the code by up to three weeks, so it
> read as twelve stages of pending work when four were finished and two were substantially done.
> That cost three agents their start-up time before anyone checked — one was handed stage 4 and found
> it landed, another was handed the R2–R4 series and found it complete with only checkboxes missing.
> Each stage below now carries its verdict and the evidence. **The owner's ORDERING and INTENT are
> preserved unchanged**; only "pending" is replaced with what is actually left. Where a stage is
> partly done, the remainder is named rather than the whole stage being re-listed.
>
> The rule this keeps re-teaching, now three incidents deep: **a board that outlives its work sends
> the next agent to reimplement something that already runs.** Probe the code before taking a queued
> v3 task — every agent who did found the answer in minutes.

1. **✓ DONE** — the four cheap slices of 2026-08-30: abstract `val` in `extern class` (landed),
   `finally` (landed `0b2a48749`, SSC3-16), lexer `@` (landed `b5872f835`, SSC3-17), `extension` in
   `Lower`'s dispatch — the last needing NO code: it had landed 2026-08-10 under
   `v3-extension-dispatch` (`10024d732`+`9040b520f`, N 171 → 194), and the section prescribing it is
   marked superseded in `5f0a09122`.
2. **✓ DONE — all nine marker cases pass.** `direct` `ea2f7b57e`, `Focus` `7c6873a95`, `Prism`
   `a679ef8e9`, the three optic KINDS as shared ScalaScript `cd58fa4f5` (exec 273 → 276), and
   `optic-polish` last at `8580f7480` (exec 277 → 278). The door itself — `Plugins.scala`, the pass
   at `Lower.programOf`'s first line, `rewrite-gate.sh` asserting all seven rules — was already
   complete when the queue was written; only SPRINT checkboxes were missing (`c17fe7460`).
   `v3-a-marker-is-a-compile-time-rewrite-nothing-in-a-library-can-answer` is closed, verified by
   probe rather than by reading the release notes.
3. **→ IN FLIGHT** — own-front parsing batch, three open bugs, each a parser/`Lower` slice:
   `v3-own-front-has-no-extern-class`, `v3-front-cannot-parse-a-curried-member-method`,
   `infix-application-does-not-reach-a-declared-class-method`. Held under claim
   `v3-infix-class-method` at the time of this audit; all three confirmed still `status: open`.
   Note `object-nested-class` is NOT one of them and is not an `@` problem — it refuses on a nested
   `case class` in an `object` and is now the only fixture holding the front-diff ceiling at 1.
4. **✓ DONE** — `v3-value-show-and-copy` landed `8580f7480`: `copy` fixed on BOTH sides and the
   rendering rule that was holding it landed with it. Holding the `copy` fix had been correct —
   landing it alone moved DIFF 0 → 1, turning an honest refusal into a wrong answer — so the pair
   had to arrive together. exec 277 → 278, bridge 274 → 275, 13 gates green, smoke 121/121.
5. **PARTLY DONE — and the remainder is NOT v3's.** The v3 half is finished: an interpolator prefix
   stopped being hardcoded (`f92e3c644`), `std/html.ssc` defines `html(parts, args)` alongside a
   one-argument `raw(value)`, and `std-ui-native-html-lambda` PASSES on both v3 lanes. What stays
   open in `v3-an-interpolator-prefix-and-an-ordinary-function-share-one-namespace` is the collision
   in the OTHER direction, on v2: there `raw` is a front built-in answering `_Raw(v)`, so a library
   `def raw` is shadowed — and it cannot be worked around portably, because honouring the wrapper
   needs a `case _Raw(x)` pattern and v2 cannot match a constructor named with a leading underscore
   (filed separately). Genuinely still open here: `v3-the-content-provider-has-no-root-document`,
   confirmed live by probe — `content-binding` refuses with `the host function 'contentData' is not
   implemented on this lane`. That one is an INTEGRATION (run the self-hosted structural pass,
   decode `NativeContentModule` values, pass them through the door `V2Fleet` currently calls without
   arguments), not a hand-over; the entry sizes it end to end.
6. **← THE REAL NEXT STAGE. The type checker.** `v3-given-syntax` G1 is `[x]` (`given name: T with`
   parses as a named value); **G2 is genuinely `[ ]`** — the checker itself, MANDATORY per the
   2026-08-09 decision, plus `given`/`using` stage-2 in `Lower`. ~24 typing-bucket corpus cases.
   This is the largest piece of real work left in the queue.
7. **✓ DONE** — `v3-jvm-interop` released `a0e9dd99e`: a JVM package is importable from OUTSIDE the
   kernel (landed `df8992fa5..8d3067169`, entry closed `24fe007a2`). Invariant I-1 holds — the
   kernel gains no dependency, it gains the ability to ASK, through two new SPI doors
   (`registerPackages`/`hostPackage`); `v3/plugins/JvmInterop.scala` and `JvmBridge.scala` answer
   them on the executor lane (`c579bbbe2`). **Still owed:** the parked `Dataset` host-surface
   decision, which was to be re-put to the owner with post-interop numbers.
8. **SUBSTANTIALLY DONE — verify the remainder before planning it.** `multi effect X:` continuation
   capture (SSC3-7b, design decided 2026-08-08, spec `f6dcbc391`: a continuation is a CLOSURE the
   lowering builds). Landed: the transitively-performing set and a command that shows it (step 1,
   `e36d6e5df`), splitting a function at a `Perform` so the rest of it is a function (step 2,
   `eb509d81c`), multi-shot continuations running (steps 4–5, `addd2d89c`), `multi effect` accepted
   with a resume inside a nested lambda capturing its continuation (`74d28869b`), and the UniML
   front projecting `effect` declarations on the DEFAULT path (`85b8253a7`). This audit did not
   establish whether step 3 and the acceptance criteria are complete — that is a SPRINT read plus a
   probe, half an hour, and it should happen before anyone sizes this as remaining work. The owner's
   ordering stands: effects complete the language before perf is touched.
9. **Performance, LAST and THOROUGH — but the ladder is PART-CLIMBED, so re-baseline before
   planning.** ("перф на последнее место — но уже потом основательно".) Still owed: SSC3-3c-rest
   cells-frame (measured 1.27–1.74× on hand-written IR, not yet built). Already landed on the
   `specs/ssc3-jit.md` ladder, which the queue's "~900× off" figure predates: SSC3-J1, the
   specializer writing the kind field the IR was designed around (`6feef7689`); SSC3-J4's
   compare-and-branch peephole (`fc7149566`) and its follow-up finding that the fix belonged WHERE
   THE DECISION IS TAKEN (`5ef9c49af`, arith-loop 20 of 20 at 0.770). So the first measurement of
   this stage is not a new benchmark run but a re-baseline: the ~900× and the 13 415-bytecode
   `Exec$.invoke` are both pre-J1/J4 numbers and should be re-taken before anything is sized against
   them. The owner's placement is unchanged — this stays after 2–8, optimising the complete language
   once rather than the half-built one repeatedly.

Then three NEW end-stages, in this order, each planned carefully before any code:

10. **UniML refactor — eliminate mutable types, everywhere it is possible** (owner, 2026-08-30,
    strengthening the first phrasing: "по возможности желательно ПОЛНОСТЬЮ избавиться от
    мутабельных типов везде" — all `uniml/` submodules, not just the core tree). **The replacement
    idiom is named, not left open: instead of mutability, use ALGEBRAS and AGGREGATORS** — the
    `std/aggregator.ssc` discipline applied to the compiler's own internals: state that today
    accumulates by mutation (parse-time builders, symbol/registry maps, counters) becomes a fold
    over a monoid/aggregator (`empty`/`combine`/`prepare`/`present`), joins become `combine`,
    retraction where needed comes from a `Group`. Survey first (`var` fields, mutable collections,
    in-place tree edits), convert in measured steps with front-diff/parity/corpus gates green at
    every one; where a measurement says the algebraic form is too expensive on a hot path, park
    THAT piece here with the number — the goal is full elimination, the qualifier is per-piece
    and evidence-based, not a blanket excuse.
11. **v3 front audit + refactor on the FRESH UniML** — after 10, re-audit
    `specs/40-front-on-uniml.md`/`specs/50-uniml-projection.md` against the refactored UniML and
    refactor v3's front accordingly (same algebras-over-mutability discipline); the front-diff
    ceiling and the projection's refusal list are the acceptance instruments.
12. **v3's OWN backends, replacing the v2 bridge** — the end-state: `translate` targets owned by
    v3 rather than delegated through `BridgeV2`/the v2 fleet. Sequenced LAST deliberately: it
    inherits the audited front (11), the complete language (2–8), and the perf baseline (9).
    **Target order is DECIDED (owner, 2026-08-30): JVM → JS → Rust → Swift → Python → R.**
    The bridge retires PER TARGET: each new backend must beat a parity gate against the bridge on
    the full corpus before the bridge stops serving that target; the bridge as a whole retires
    only when every target it serves has a native successor. First deliverable is still a plan
    document per target (starting with JVM: bytecode directly à la v2's `backend-jvm-bytecode`,
    or source-level à la `run-jvm` — put to the owner with measured trade-offs before code).

**WHAT IS ACTUALLY LEFT, after the 2026-08-31 audit, in the owner's own order:** stage 3 (three
front-parser bugs, in flight) → **stage 6, the type checker G2, which is the largest real piece** →
stage 5's remainder (the content-provider integration; the interpolator half that is left is a v2
defect, not v3's) → stage 8's verification-then-remainder → stage 7's parked `Dataset` decision →
stage 9 re-baselined → stages 10–12, the owner's new end-stages, which are genuinely untouched.
Stages 1, 2, 4 and 7 are DONE. That is four fewer stages than the queue listed and one stage —
6 — carrying more of the remaining weight than its position suggests.

Execution discipline for all twelve: the module's own rules — measured against N/exec/bridge and
the front-diff/parity floors, every mechanism switchable off from its first commit, and honest
refusals over silent wrong answers.

## Owner decisions, 2026-08-17 — four questions put and answered

Put to the owner after a census showed v3 has no defect of its own left in the corpus: 259/369 on
the executor and 257 on the bridge, with the four unclosed cases belonging to another claim or
deliberate. What remains is 106 honest refusals, and they are not one problem — these are the four
that needed a decision rather than a slice.

**SIZED 2026-08-18, AND THE APPROVAL WAS GIVEN WITHOUT THREE FACTS THAT CHANGE ITS PRICE.** Read
before writing any of it:

1. **The five cases declare `backends: [jvm]`** — `distributed-map`, `distributed-shuffle`,
   `distributed-heterogeneous`, `distributed-failure-retry`, `distributed-failure-partial`. v3 is not
   among their declared targets; `jvm` is v1's JVM backend. They land in UNSUPPORTED rather than
   LANE-EXCLUDED only because `holds_v2` filters DIFFERENCES and these refuse at build time, so the
   five would still count as +5 if v3 could run them — but v3 refusing a case aimed at another
   backend is not obviously a defect.
2. **Nothing in the plugin fleet provides those names.** The cheap shape — let the loader ignore a
   dotted JVM import and resolve the names through the SPI — was checked and fails:
   `v2NativeDatasetPlugin` registers Dataset OPERATIONS (`avg`, `collect`, `filter`, …) and neither
   `DatasetWire` nor `DatasetWirePartition`.
3. **`scalascript.typeddata` is a real Scala library**, with generics, derived-schema MACROS and
   three wire codecs. Supporting `DatasetWirePartition(partId, values)` means constructing JVM
   objects and calling their methods — reflection or a generated bridge — not a loader tweak.

So "allow JVM-package imports" means giving v3 JVM-class interop. It can still live outside the
kernel behind the plugin SPI, so I-1 survives, but it is a feature of that size and not a slice —
and it buys five cases that name another backend.

**A SMALLER THING IS WORTH DOING EITHER WAY:** the refusal currently lists four candidate FILE paths,
which sends the reader hunting for a file that cannot exist. A dotted JVM-package import should be
refused as what it is.

**WAS: APPROVED — JVM-package imports.** `std/mapreduce/typed.ssc:25` writes
`import scalascript.typeddata.{DatasetCodec, DatasetWirePartition}` — a Scala PACKAGE, not an `.ssc`
module. v2 resolves it through its interop descriptor; v3 treats it as a file path and refuses with
four candidate paths, which is what the 5-case `typeddata` bucket in the refusal histogram is. The
door has to keep invariant I-1: the kernel gains no dependency, so this belongs behind the plugin SPI
or an explicit import form, not in `v3/src`.

**DONE 2026-08-18 (a95a337c5) — an exact decimal, carried as its canonical text.** exec 259 -> 262
with the DIFF floor to ZERO, bridge 257 -> 259. No arbitrary-precision library and no Tier 0 type;
arithmetic deliberately not added. The printing half needed a third SPI door — the owner renders its
own handles — and giving that door `VHostData` as well broke three passing cases, since v3 can read a
host datum and must print it by the language's rules.

**WAS: APPROVED — an exact decimal.** `v2` has `DecimalV` and v3 has no counterpart at all, so
`json-self-hosted-import` cannot cross: it exists to pin `jsonParse("0.0")` printing `0.0` rather
than a float. Entry: `v3-has-no-decimal-so-the-json-core-cannot-cross`. It is a runtime value like
`VBytes` rather than a Tier 0 type addition — the program never names it, prims consume it.

**DONE 2026-08-18 (46bccf742) — the plugin fleet is ON by default, and it is worth twenty cases.**
Measured on one tree by the switch the change adds: bridge 257 by default against 237 with
`SSC3_FLEET=off`. It was five the day the fleet was wired and grew as the adapter learned maps, sets,
arrays, thrown failures, closures in both directions, methods on host values and plain-value globals.

Of the two guarantees `plugin-classpath.sh` was written under, one lapses and one is kept. "A caller
not using the fleet has no business waiting for sbt" goes, because everybody uses it now. "A build
failure has no business making `ssc3 run` fail" stands: the build is attempted once, its output
discarded, and a failure leaves the fleet absent and the run going — verified with sbt off the PATH.

`SSC3_FLEET=off` exists because a mechanism that cannot be switched off cannot be measured. Until
now the control was "move the file aside", which the auto-build would have silently undone.

**INTERPOLATORS — the owner asked the right question and it is now a design.** `html"…"`, `md"…"`
and `f"…"` are refused because both fronts hardcode `s`. Rather than widen Tier 0, an interpolator
becomes an ordinary CALL — `pfx"a${x}b"` lowers to `pfx(List("a","b"), List(x))` — so `def html(parts,
args)` in `std` or a plugin defines one with nothing added to the kernel. Entry:
`v3-an-interpolator-prefix-is-hardcoded-in-both-fronts`.

**DECIDED 2026-08-19 — the owner admits all three groups.** Put with today's histogram rather than
the one below, which predates the interpolator design and the JVM interop: the boundary refuses 14
cases now, as `marker` 9, `operator` 3 and abstract `val` 2. The owner took all three, and the order
is cheapest-first because the third is a different kind of work:

| group | cases | shape | status |
| --- | --- | --- | --- |
| operators | 3 | an operator the core does not define is a METHOD CALL | claimed `v3-tier0-operator-as-method` |
| abstract `val` | 2 | `val name: String` with no value — TWO shapes, see below | next |
| markers | 9 | `Focus`/`direct`/`Prism` — a compile-time REWRITE, and it belongs OUTSIDE the front | planned: `specs/60-compile-time-extension.md` |

**THE ABSTRACT `val` IS TWO QUESTIONS WEARING ONE MESSAGE, and only one of them is the refusal's
own.** `UniFront.scala:281` refuses `U.AbstractVal` wherever it appears, with the comment "v3's
traits carry methods, not abstract state" — which is a deliberate answer for a TRAIT. But the use in
the tree is `std/http.ssc:170`, inside an **`extern class`**:

    extern class UploadedFile:
      val name:     String
      val filename: String

Those are not abstract state: they DESCRIBE the fields of a host type, which is the same fact
`registerFieldNames` carries on the plugin side. So the slice is to accept an abstract `val` in an
`extern class` as a field declaration and leave the trait refusal exactly as it is — one shape
admitted, one deliberately not, rather than one message covering both.

**THE OWNER ASKED FOR THE EXTENSIBLE SHAPE RATHER THAN TWO REWRITES, 2026-08-19, and the answer is
a compile-time door** — `specs/60-compile-time-extension.md`. The runtime SPI supplies VALUES; a
marker needs SYNTAX, because `Focus[Person](_.address.city)` must read field names out of a lambda
and `direct[F] { x = e; … }` must see a block's statements unevaluated. A call receives values and
can see neither, which is exactly what separates a macro from a function — and why the interpolator
answer, which works because its pieces ARE values, does not extend to these.

The door goes at the shared `Expr`, not in either front: one rewrite for two fronts, I-3 for free
because it happens before lowering, and I-1 kept because the kernel gains one node and one pass
while the meaning lives in `v3/plugins/`. Interpolators become its THIRD client rather than a second
mechanism of the same shape, and the hardcoded `s` goes with them.

The operator answer is the interpolator answer applied again: rather than widen Tier 0 to hold the
operator, make the operator ORDINARY. `Lower` already lowered an ALPHANUMERIC infix operator to
`Invoke` — `a to b` is `a.to(b)` — so the change is to stop treating the symbolic ones as a closed
set. The kernel gains nothing (I-1) and an operator becomes a library matter.

**STILL OPEN — the Tier 0 boundary itself, and the markers are no longer a mystery.** The 24 Tier-0
refusals are five different questions:

| construct | cases | what it is |
|---|---|---|
| markers | 9 | `Focus[Person](_.age)` (5, `lenses`, `optic-polish`), `direct[Option] { … }` (3, tagless direct style), `prism` (1) |
| interpolators | 6 | `html` 4, `md` 1, `f` 1 — answered by the design above |
| abstract `val` | 2 | `val name: String` with no value, `std/http.ssc:170` |
| ~~`finally`~~ | ~~1~~ | **DONE, SSC3-16** — desugared in `Lower` into `try`/`catch` nodes the IR already runs |
| other | 1 | a handler expression in `std-ui-jobpanel` |

**THE MARKERS ARE MACRO-SHAPED, WHICH IS WHY THEY ARE REFUSED BY NAME.** `Focus[T](_.field)` must
become a lens over a named field and `direct[F] { x = … }` must become a flatMap chain: both are
COMPILE-TIME REWRITES, not runtime features, so no amount of library code answers them. That is a
front feature to design, and the largest single group of the five.

**CHEAPEST OF THE REST:** `finally` and the abstract `val` were three cases between them and neither
needed a rewrite — both have since landed (abstract `val`, then `finally` in SSC3-16). `html"…"` is deliberately NOT proposed for Tier 0 — the interpolator design covers
it without putting markup into the language.

## v3's own front cannot lex `@` — two fixtures are uniml-only because of it

**THE `@` HALF IS DONE — SSC3-17.** `@` lexes as punctuation and whole annotations are skipped by
one loop before the declaration dispatch (and before the member dispatch), layout included.
Agreement 86 → 87 of 88, uniml-only 2 → 1, ceiling lowered with it.

**AND THIS SECTION GROUPED TWO FIXTURES THAT ARE NOT ONE PROBLEM.** It read that
`annotation-own-line` and `object-nested-class` are "the two fixtures `front-diff.sh` counts as
declared uniml-only, and the ceiling is at 2 because of them" — the ceiling part is true, the
implication is not. **`object-nested-class` contains no `@` at all.** It refuses with
`only `def` members are supported in a object at Tier 0, found case` — a `case class` nested in an
`object`, a members-dispatch gap and its own slice, still open and now the only fixture holding the
ceiling at 1.

The annotation half was the cheaper: v3's lexer refused `@` with `unexpected character`, and the
skip belonged where UniML's is — one loop before the declaration dispatch.

`v3/src/Lexer.scala` is `ssc3-core`'s and `v3/src/Parser.scala` is `ssc3-multi-effect`'s, so the
pair had to land together; tokenising `@` on its own changes nothing observable and would just move
the refusal one layer down.

The reference front parses these files (`ssc1-front-annotation-before-declaration` was ITS bug and
is fixed there), so this is v3's own gap rather than a compatibility question.


## `extension` belongs in `Lower`'s dispatch, and the projection CANNOT do it — measured 2026-08-09

**✓ SUPERSEDED — THIS WAS BUILT, and the entry outlived it by three weeks.** Landed under claim
`v3-extension-dispatch` as `10024d732` (feature) + `9040b520f` (entry), released `afd0b8d6e`.
`v3/src/Lower.scala:1657` carries exactly what this section prescribes: `v.m(args)` becomes
`m(v, args)`, guarded by the built-in vocabulary that `v3/extension-gate.sh` DERIVES from
`Exec.invoke` (it caught 28 names missing from the first hand-written copy — the reason it derives),
plus a typed arm for an extension whose name a built-in also has. Design: `specs/ssc3-extensions.md`.

**MEASURED, on one tree, before and after: N = 171/368 → 194/368, plus 23.**

The rule that made it sound is a NARROWING of §51's conclusion rather than a contradiction of it —
worth keeping, because the two read as opposites at a glance. §51's finding stands: which method a
receiver has is a fact about its runtime VALUE, not its syntax. That rules out choosing BETWEEN
candidates. It does not rule out observing that there are NONE BUT ONE: if `m` is a top-level def,
no class in the merged program declares it, and it is not a built-in, then no receiver value can
answer to it and the rewrite is the absence of alternatives rather than a guess about a type. Every
condition can only PREVENT a rewrite, so no call that works today can stop working.

Kept below, unedited, because the reasoning that ruled out the projection-only approach is still the
reason the implementation lives where it does — and because the blocked-on note at the end is how a
stale entry looks from the inside: it names claims that finished long ago.

Tier 2 was un-deferred and the plan's first stage was "`extension`, projection-only" — on the
reading that it resolves by NAME and therefore needs no types. The name part is right. Doing it in
the projection is not, and two measurements say so.

**A name-based rewrite breaks the built-ins.** Turning `v.m(a)` into `m(v, a)` wherever `m` is an
extension name and no class in the module declares it took **N from 188 to 130 with CRASH 131**: an
extension named `map` or `join` rewrote every `.map(…)` and `.join(…)` in the program, including
the ones on lists. The projection knows the members a module DECLARES and nothing about the
built-in vocabulary — and adding that list is not the fix, because which method a receiver has is a
fact about its runtime VALUE, not its syntax.

**And refusing at the call site cannot cover it either.** `UniFront.parse` runs once per FILE
(`Loader.closureWith`), so an extension declared in an imported module is invisible where it is
called. Two corpus cases went from a clean UNSUPPORTED to an unpositioned CRASH for exactly that
reason — `collection-join` and `js-parser-combinator-choice`.

**Where it goes.** `Lower` already has the dispatch this needs: a `Switch` per declaring class with
a dynamic `Invoke` default, over the MERGED program. An extension is one more fallback in that
default, tried after every class arm and after the built-in table have missed — which is the only
point where both the whole program and the receiver's runtime tag are known. The projection's half
is small and uncontroversial: an extension method is a top-level `def` with the receiver as its
first parameter.

Blocked on `v3/src/Lower.scala`, held by `ssc3-cps-split` (active 11 minutes ago),
`v3-dataset-vertical-slice`, `v3-calls-a-captured-function-parameter` and `v3-bridge-lifted-capture`.

**`given`/`using` has the same shape of problem and is NOT the same problem.** Its stage-2 plan —
match a `using` parameter's declared type TEXT against the `given`s in scope — has the same
per-file blindness, and additionally needs the call site rewritten. It waits on the same file.


## DATASET — the second decision v3 has not made, measured 2026-08-08

Not a task, and taken off the queue after measuring it rather than after starting it. `Dataset` is
the largest single name left in the corpus's `unknown name` bucket, which makes it look like the
next link in the chain SSC3-6 walked. It is not a link. It is the question of HOW v3 GETS HOST
SURFACE AT ALL, and it cannot be answered one lane at a time.

**The prize, counted rather than estimated.** 42 corpus files mention `Dataset`. **21 fail FIRST on
the name itself** — 12 in `tests/conformance` (counted in `N`) and 9 in `examples` (not counted).
The other 17 fail on something else before they ever reach it: `spark`, `HandlerRegistry`, `Seq`,
`java`, `args`, `ActiveUsers`, a multi-arm `catch`. So the ceiling is **N 166 → 178**, and only if
every one of the 12 also passes the rest of its program — each uses `collect`, `map`, `groupBy`,
`reduce`, `sortBy`, `first`, `mkString` on top of the constructor.

The surface those files actually call, counted across the corpus:

    46  Dataset.of          11  Dataset.fromTable      9  Dataset.fromFile
     7  Dataset.fromList     3  Dataset.fromGenerator  2  Dataset.fromCsvAs
     1  Dataset.run          1  Dataset.runParallel    1  Dataset.runShuffle

**What already exists, and where.** v1 implements it in host code — `DatasetRuntime.scala` (284
lines) plus `DStreamsIntrinsics.scala` (1200). v2 implements it as a NATIVE PLUGIN,
`v2/runtime/std/dataset-plugin`, which registers **the exact names above**: `context.register
("Dataset.of")`, `"Dataset.fromList"`, `"Dataset.fromFile"`, `"Dataset.fromGenerator"`. v3's entire
host surface is three entries — `Lower.scala:20`: `println`, `__autoOutput__`, `__throw__`.

**Why it cannot be added to one lane.** This is the part that turns a task into a decision, and
every clause of it was measured:

- `Ir.Prim` is the single door to the host, and v2's runtime does route prims through
  `V2PluginRegistry.handlers`. The seam is real and the names already agree.
- But the corpus's bridge lane is `java -cp "$V2_CP" ssc.cli run-ir`, and `$V2_CP` is
  `classes_for ssc2 "" "$ROOT/v2/src"` plus the toolchain — **0 dataset entries on it**. `run-ir`
  is `v2/src` ALONE (`Jit.scala:133` says so in as many words), and `v2/src` contains no
  `.install(`, no `NativePluginContext`, no `ServiceLoader`: there is no plugin-loading path in
  that lane at all. `Dataset` appears in `v2/src/Runtime.scala` exactly once, in a comment.
- `holds_v2` returns TRUE for these cases — they carry no `known-red: … v2` and no `backends:`
  line, only `requires: Feature.Dataset`, which **nothing in `corpus-report.sh` reads**. So a
  bridge-lane failure lands as **DIFF, not EXCL**.

Put together: implementing `Dataset` in v3's executor alone would turn 12 UNSUP into 12 PASS on the
exec lane and **12 DIFF on the bridge lane**, breaking the stated DIFF 0. The escape hatch that
exists for exactly this shape — `EXCL`, "ran, differed, but this case does not hold the v2 lane" —
does not apply, because these cases DO hold it.

**The three ways out, none of them cheap, and the choice is not a lowering author's to make:**

1. **Implement it twice** — in `v3/src/Exec.scala` and in the shared `v2/src` kernel. That is the
   1200-line runtime in two places, one of them the kernel both v2 lanes execute, and a feature
   implemented in two places is two implementations that will disagree — the same argument this
   file already makes about by-name arguments.
2. **Give `run-ir` a plugin path**, so the bridge lane can load `v2/runtime/std/dataset-plugin` and
   v3 only has to LOWER `Dataset.of(…)` to `Prim("Dataset.of", …)`. One implementation, and it is
   already written. Cost is an integration v2 has deliberately not had: `run-ir` being a pure VM is
   what makes the zero-dependency invariant checkable rather than aspirational (`Ir.scala:137`).
3. **Write `Dataset` in ScalaScript**, as a library over lists, so both lanes get it with no host
   surface at all. Architecturally the best answer and the one the project's own rule points at
   (new intrinsics go to the plugin, never the core). **Blocked on a prelude:** the corpus files
   call `Dataset.of` with NO import, v3's module system is markdown links, and v3 has no prelude or
   auto-import mechanism — `grep -rniE 'prelude|auto-import' v3/src v3/ssc3` is empty. Ambient
   names are a mechanism v3 does not have, and adding one is its own decision.

**What is NOT worth doing, stated so nobody does it:** making `corpus-report.sh` honour `requires:`
so these cases stop counting. It would move `N` without moving anything real, and the script's own
comment already settled the policy — *"still counted and still listed — a silent skip would hide
work"*.

## The two fronts disagree on WHERE a by-name argument becomes a thunk — CLOSED, 0 cases

Measured 2026-08-08 **on `origin/main` in a clean worktree**, before any of my own uncommitted work:
`front-diff.sh` reads 263 corpus cases printed by both fronts, 234 agreeing and **29 differing**,
and the gate is RED. It is not a regression from the boxing or infix work in the same session —
that tree measures the same 29.

    v3     (call "runActors" (block …))
    uniml  (call "runActors" (lam (params) (block …)))

UniML's projection emits the THUNK; v3's own front passes the block eagerly and `rewriteByName`
wraps it in the lowering. Both readings can be made to work, but they are two places, and a feature
implemented in two places is two implementations that will disagree — which is what the differential
is reporting.

**Worth checking before choosing a side:** if `rewriteByName` wraps an argument that UniML has
ALREADY wrapped, the uniml lane gets a thunk of a thunk, and that is a wrong answer rather than a
printed difference. All 29 are `actors-*` cases and every one is currently UNSUPPORTED for other
reasons, so the corpus cannot see it either way yet.

**MEASURED AGAIN 2026-08-10 AND IT IS CLOSED, which is why the heading changed rather than a note
being tacked on below it.** `front-diff.sh` reads `both fronts print: 277; they AGREE on 277, differ
on 0`, and the gate is GREEN.

Checked the way that separates a fix from a disappearance, because a differential goes quiet for
both reasons: `actors-bounded-mailbox` and `actors-cluster-config` are PRINTED BY BOTH fronts and
their canonical trees are identical. They are not sitting in the one-front-only bucket. Earlier the
same day both fronts refused `actors-bounded-mailbox` outright — v3's own with `expected ')', found
:`, UniML with `` `extension` is outside Tier 0 `` — so this section's own closing sentence stopped
being true the moment they began printing, and the agreement is an answer rather than a silence.

`rewriteByName` still has three references in `Lower.scala`, so the two READINGS converged rather
than one being deleted. The warning above — a thunk of a thunk is a wrong ANSWER, not a printed
difference — therefore still holds and is simply no longer urgent.

Owned by `ssc3-effect-protocol`, which holds `v3/src/Lower.scala` and landed the by-name rewrite.
Filed here rather than fixed because choosing where by-name lives is that claim's decision.

## ~~v3's parser CONTINUES an expression onto a line starting with `(`~~ — FIXED 2026-08-07

Filed by whoever narrowed the front differential's 74 corpus disagreements, and fixed the same day.
Kept here rather than deleted, because the *shape* recurs and the note that stood here was right
about the direction when the premise on record said otherwise.

The claim in the parser was: "a newline is its own token, so a `(` opening the next line is a new
statement and is not reached here." **False whenever the expression ended with an INDENTED BLOCK** —
closing that block consumes the newline AND the dedent, so the `(` becomes adjacent and `while … do
… ⏎ (0 :: Nil) ++ xs` read as applying the `while`'s result. `parsePostfix` now requires the `(` to
be on the line the expression ENDS on.

Two things went wrong on the way to a three-line fix, and both are worth more than the fix:

- **Identity by reference did not hold.** The end-of-expression line was found by walking the token
  list until it reached the remaining suffix, compared with `eq` — and `dropDedents` removes tokens
  from the middle, so the list is rebuilt and `eq` never matched. The helper returned "unknown"
  every time and, degrading permissively by design, changed nothing at all. The measurement said so
  immediately: the fronts still differed. Identity is now the head token's POSITION, which is unique.
- **A guard maintained only where it is read is wrong everywhere else.** The line was updated in the
  `(` branch alone, so after `Dataset.of(⏎ … ⏎).reduceByKey(a)(b)` it still held the line of
  `Dataset`, three lines up, and a legitimate second argument list was refused. It is updated after
  every postfix step now.

**A `v3/tests/front/` FIXTURE CANNOT GATE THIS, and that was measured rather than assumed.** A
second agent wrote one — a `while` body and an `if` body each followed by a line opening a paren —
then planted the defect back to watch it fail. It did not. With `endLineBetween` forced to its
permissive `-1` the tree returns to `(apply (while …) …)` and `(apply (if …) …)`, and the program
still prints `List(0, 1, 2)` and `4`: those fixtures compare program OUTPUT, both parses evaluate to
the same output, so the fixture is green in BOTH states. It was deleted rather than committed as a
passing test that proves nothing.

So the corpus AST differential is the only gate for this class, and that is the argument for its
existence: a difference that changes the TREE and not the ANSWER is invisible to every
output-comparing gate this repository has. `front-diff.sh`'s ceiling of 0 is what holds it.

Result: front agreement on the corpus went **74 differing → 0**, 219 of 219.

## v3 carries its own copy of the character alphabet — decide, do not drift

Left open deliberately when `specs/20-core-language.md` §3 was corrected (`41534ad3c`,
`c42173618`). §3 bans baked tables; Sergiy adopted a Unicode CASE table on 2026-08-05 after the
tableless answer was implemented, measured and rejected on the measurement, and UniML now ships it
as a shared module. **v3 keeps a separate copy in `Chars`.**

Two acceptable outcomes, and "leave it" is not one of them:

- adopt the shared module, or
- record that two copies are intentional, and state **what they must agree on** and what checks it.

Two copies agreeing only by memory is the shape that has cost this repository repeatedly — the
version coordinate is on its fourth declaration
(`sbt-plugin-version-and-the-coordinate-templates-emit-disagree`, `tests/BUGS.md`).

An identifier alphabet that disagrees between the front and the language it compiles does not
produce a build error; it produces two parses of one file.

## WHAT THE REMAINING REFUSALS ACTUALLY ARE — a census, 2026-08-09

Measured after the day's language work took `N` from 188 to 191. The point of counting was to find
the next LEVER, and the honest answer is that there is not one — there are several subsystems, and
naming them is worth more than another guess at which construct to add.

`corpus-report.sh` at `N = 191 / 368`, `UNSUPPORTED = 171`:

    51  unknown name '…'
    34  call to unknown function '…'
    26  the host function '…' is not implemented on this lane
     9  the marker '…' is outside SSC3 core Tier 0
     9  a `trait` member that is not a `def`
     9  `extension` is outside SSC3 core Tier 0
     6  '…' is not a declared effect operation

**The first three are 111 of the 171, and they are NOT language gaps.** Resolving the names in the
first two buckets, over the conformance corpus:

    12  Dataset        6  suspend        4  Async       3  Parser      3  math
     2  RoundingMode   2  jsonParse      2  BigInt      2  Array       2  List
     … then a tail of one each

**`Dataset` is the largest single item and it is not a missing import.** `std/mapreduce/dataset.ssc`
exists and defines it; the cases that use it carry `requires: Feature.Dataset` in their frontmatter
and NO import link, so it is a LANE FEATURE the runtime is expected to provide, the way the other
lanes do. Ten conformance cases stop on the bare name. `suspend`/`Async` (10 together) are the same
shape for coroutines.

**So the remaining corpus does not yield to one task.** It is: a Dataset feature (≈12), an
async/coroutine feature (≈10), `extension` behaviour (9 refusals plus the three rows §54's X1
names), and a long tail where each name is its own small piece of library. Anyone planning from the
histogram alone would read "51 unknown names" as one job; it is at least four, and the biggest two
are runtime FEATURES rather than modules to import.

**Not started here because both files it needs were claimed** — `Exec.scala` by
`v3-charat-on-the-executor` and `Lower.scala` by `v3-lowerfail-names-the-right-file` — and a
census is what could be done without them. §51's design conclusion for `extension` still stands and
is the shortest path to three rows when those files free up.

## THE TYPE CHECKER — DECIDED 2026-08-09: v3 gets one. See `v3/SPRINT.md` §52

⚠ **This section is now HISTORY, kept for the reasoning.** Sergiy took the decision on 2026-08-09
against the numbers below: `given … with` as plain syntax first (2 rows, no inference), and **the
checker itself as mandatory work rather than an open question** — `tagless-*` is a goal in its own
right. The queue entries are `SSC3-G1` and `SSC3-G2` in `v3/SPRINT.md` §52; the alternative that
resolves instances by spelling was rejected there on `tagless-resolution`, which declares
`Show[Int]` and `Show[String]` and cannot be told apart without types.

## THE TYPE CHECKER — the decision v3 has not made, framed 2026-08-08

Not a task. A decision, with what is known about it, so that whoever makes it is not starting from a
blank page — and so that nobody makes it by accident while implementing something else.

⚠ **RE-MEASURED 2026-08-08, AND THE WALL HAS FALLEN — without a type checker.** The three
measurements this section lists as "not done" are done, and the first answers the question the
section says was assumed rather than shown.

**Traits dispatch, and no checker exists.** Its own words: *"separate 'needs a checker' from 'needs
dispatch' — they are assumed to be the same question and have not been shown to be."* They are not
the same question. Two implementations, a `List[Shape]`, a virtual `area()` — both v3 lanes answer
19, the same as the reference front.

**The histogram no longer has the rows this decision was framed on.** Same script,
`v3/corpus-report.sh`, 2026-08-08:

    N = 166 / 367          (was 18 / 355 on 2026-08-03)

    48  unknown name '…'                 32  call to unknown function '…'
    15  expected an expression, found =  11  expected '…', found :
     9  expected '…', found *             8  host function not implemented on this lane
     8  only `def` members are supported in a trait at Tier 0, found extension
     7  unexpected character '…'          7  expected an expression, found :
     7  expected an expression, found ;   6  dedent to column 12 matches no enclosing block

`trait` at 137 is gone. The only trait row left is 8 cases about `extension` members inside a trait
— a Tier-0 membership rule, not dispatch. `[` generics at 36 is not in the top twelve at all, so it
is at most 6.

**The two `given` rows are two different questions.** `typeclass-monoid` uses its instance as
`intMonoid.combine(…)` — a reference BY NAME, not type-directed resolution, no checker needed.
`typeclass-fold` uses `summon[Monoid[A]]` inside a `foldLeft` generic in `A`, which does need one.
So that row is 1, not 2, and this section's own suspicion about `typeclass-monoid` was right.

**Of 92 traits declared across the corpus, 38 need no dispatch at all** — 29 have no implementation
anywhere (a bare namespace) and 9 have exactly one, where there is nothing to choose between.
Counted over implementation sites, `extends` and `given … with` together.

⚠ **RE-VERIFIED 2026-08-09 on a rebuilt tree, and both `given` rows now refuse at the SYNTAX.**
Re-run because effects and Scala-style imports landed in between, and because this section's own
rule is that a decision of this weight is taken against the current number. `v3/ssc3 run`:

| row | what it does with its instance | refusal today |
|---|---|---|
| `typeclass-monoid` | `intMonoid.combine(…)` — reference BY NAME | `` `given … with` `` at 10:1 |
| `typeclass-fold` | `summon[Monoid[A]]` inside a `foldLeft` generic in `A` | `` `given … with` `` at 15:1 |

**So the split is confirmed and it is sharper than "one of the two".** Neither row reaches
resolution at all — both stop at the declaration form. Teaching `given name: T with` to parse as a
named value implementing `T` is enough for `typeclass-monoid` and NOT enough for `typeclass-fold`,
which still needs `summon[Monoid[A]]` answered where `A` is a type parameter. The prize for the
syntax alone is 1 row; the prize for the checker is the other.

**Measured the same day, and it is not evidence for a checker:** `effect-multishot` and
`type-lambda-native` both exit 0 with empty output — correct, not a defect, because each is a bench
workload with no `println`; `ssc3 bench` is their instrument. `effect-pure` and `effect-stream`
still refuse on an unknown name, which is a library gap and not a typing one.

**What this does and does not change.** The decision is not wrong: a checker is still the only thing
that reaches `summon[T]` in a generic, and everything below about the erasure bargain, lane
agreement (I-3) and `N` rising only (I-5) stands. What changed is the SIZE OF THE PRIZE it was being
justified by — 137 + 36 has become ~8 + ≤6 + 1. A decision of this weight should be taken against
the current number, so read the table below as history.

**The original framing, 2026-08-08:**

**What it is gating, measured, not guessed.** `SPRINT.md` SSC3-6 walked the corpus blocker chain by
measurement and ended at a wall rather than another link:

| blocker | corpus cases | why a checker |
|---|---|---|
| `trait` | 137 | needs dispatch, and dispatch needs to know a value's type |
| `[` generics | 36 | |
| `given` / `using` | 2 bench rows | `20-core-language.md`: *"needs type-directed resolution. This is the one item on the list that Tier 0 cannot reach by adding syntax."* |
| type lambdas `[A] =>> …` | 1 bench row | behind generics |

Everything else in that chain fell to one construct each. These do not, and that is the difference
between "the next construct" and "the next decision".

**What makes it a decision rather than a task.** Tier 0's stated bargain is that types are ERASED —
which is why `skipType` discards them, why `asInstanceOf` is the identity in the executor, and why
`Vector` could share `Array`'s representation at all (SSC3-7j). A checker does not add a feature to
that design; it changes what the design IS. Three things follow that nobody should discover midway:

1. **The erasure bargain gets partly taken back.** SSC3-7j is the worked example already on the
   board: `v(i) = x` is accepted on a `Vector` today and a checker must reject it. Every place the
   tier traded checking for simplicity becomes a decision to re-open, one at a time.
2. **Two lanes, one answer.** `exec-gate.sh` requires v3's executor and the v2 bridge to agree. A
   checker that rejects a program v2 accepts is a lane divergence by construction (I-3), so the
   question "what does the bridge do with a program v3's checker refuses" has to be answered in the
   design, not after.
3. **`N` may only rise** (I-5). A checker that refuses previously-accepted programs collides with
   that invariant unless the two are reconciled deliberately.

**What can be measured BEFORE deciding, and has not been.** All of it is a day or less and none of
it commits anything:

- run the corpus with `trait` parsed-but-undispatched, to separate "needs a checker" from "needs
  dispatch" — they are assumed to be the same question and have not been shown to be;
- count how many of the 137 `trait` cases use dispatch at all, versus a trait as a bare namespace;
- take the `given` rows apart: `typeclass-monoid` may only need a value resolved by NAME, which is
  not type-directed resolution and would not need a checker.

Doing that measurement first is what SSC3-6 did for the chain, and it is why the chain's answers
were repeatedly not the ones anyone predicted — link 6 being the standing example, where 116 of 126
cases in one symptom bucket were a construct nobody guessed from the message.

## The prefix matcher re-parses its pattern on every call — measured 2026-08-11, NOT urgent

`std/parsing/regex.ssc` calls `rxParse(pat)` inside `regexMatchPrefix`, so a parser that matches
the same `PRegex` a thousand times parses that pattern a thousand times. The obvious fix is to
parse once — either a cache keyed on the pattern string, or parsing at `PRegex` construction and
carrying nodes in the node rather than a string.

**It is filed here rather than done because the measurement says it is not the bottleneck**, and
the reason is worth keeping: the implementation this module replaced had the SAME defect. v2's
native `matchPrefix` called `java.util.regex.Pattern.compile(pat)` per call. So the comparison was
never "interpreted matcher against a compiled automaton" — it was against `compile` plus a match,
every time.

Measured in ONE binary, on the v2 native lane, with both implementations reachable side by side
(`s.matchPrefix(pat)` is still in v2's runtime), order ALTERNATED between pairs:

| workload | host `java.util.regex` | this module |
|---|---|---|
| 6 std patterns × 300 reps | 29–76 ms | 28–80 ms |
| `[^\n]+` over 4096 chars × 40 reps | 72–86 ms | 68–84 ms |

Within noise on both, and the JIT warm-up drift across a single run (80 ms → 28 ms, 2.7×) is
larger than any difference between the two sides. A first attempt with a FIXED order inside each
pair reported the ScalaScript side uniformly faster; alternating the order removed that, which is
the whole reason the alternating protocol exists.

**So the honest statement is "no slowdown measurable at these scales", not "it is faster".** The
caveats are real: only the v2 native lane was measured, only up to 4096 characters, and a pattern
with heavy backtracking was not tried at size.

When this is picked up, cache the PARSED FORM, not the matched result — and note that the same
change helps whatever implementation sits underneath, so it is not an argument for moving back to
a host regex. Moving back would reintroduce the reason the module exists: a host implementation
must be written once per lane and the dialects disagree (`v3-extension-unblocks-two-files-into-a-lane-DIFF`).

## Parked design alternatives

- **Flat basic blocks + SSA instead of structured regions.** Rejected for
  [`specs/10-ssc-ir.md`](specs/10-ssc-ir.md) §2: source-language output is a stated requirement, and
  recovering loops from a block graph is the relooper problem. Becomes right if v3 ever grows an
  optimizer whose passes genuinely need SSA and dominance — the conversion is then a *pass* on a
  module, not a change of the canonical form. Note the cost honestly: some classical optimizations
  (global value numbering, aggressive code motion) are meaningfully harder on regions.

- **A stack machine instead of a register machine.** More compact encoding and a simpler emitter;
  rejected because interpretation pays push/pop per operand and native lowering needs registers
  anyway. Reconsider only if `.ssirb` size ever becomes a real constraint.

- **Typed IR.** `kind` currently carries only what the front proved. A fully typed IR would let the
  verifier reject far more, and would let backends emit unboxed primitives directly. Blocked on
  v3 having a type checker at all; the `kind` field is the forward-compatible seam.

- **Reusing v2's Core IR so v3 inherits its backends.** Rejected: it would tie a linear register IR
  to a term tree and give up the property the version exists for. The backends are re-earned from
  the new IR instead — cheaply, because structured control flow makes source emission direct.

## Deferred capability

- Separate compilation — a module is a whole program in v1 of the IR.
- Garbage collection — values are host objects.
- Effects and handlers in the **front**. The IR reserves `Perform` / `Handle` / `Resume`, so this is
  a front gap rather than a representation gap; that is the whole reason they are in the instruction
  set from day one.
- Tier 2 language surface: implicits and `given`/`using`, macros, typeclass derivation
  ([`specs/20-core-language.md`](specs/20-core-language.md) §2).
- `.ssirb` binary form — `.ssir` text is canonical and sufficient until artifact size or load time
  is measured to be a problem.
- Backends beyond the executor: JVM bytecode, JS, Rust, Swift source. Each is a `Module → Artifact`
  function; none is scheduled until the IR has stopped moving.
