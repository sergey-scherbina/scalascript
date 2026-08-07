# UniML — backlog

Can-wait and not-yet-started work whose code lives in `uniml/`. When an item is
picked up it moves to `uniml/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## the version is declared twice — remove the COPY, not the standalone build

Blocked until `release/0.1.1` lands: `build.sbt` and `uniml/build.sbt` are both held by the live
`release-v0-1-1` claim, deliberately ("the standalone build must carry the same version").

Sergiy asked on 2026-08-07 whether `uniml/build.sbt` could go, to end the version duplication. **The
answer measured out to no, and the framing that produced the question was mine and was wrong.** I
had told him only one property still argued for keeping it — "uniml builds without the root build
definition" — having checked publishing metadata and module coverage and never checked *what runs
the tests*. That property is doing three jobs at once:

- **It is the only automated gate for UniML's ~300 tests.** The CI job `uniml:` is
  `cd uniml && sbt -batch test`; there is no other. Until 2026-08-04 `grep uniml .github/workflows/`
  returned nothing at all.
- **`smoke-ci` was tried for this and reverted** — a smoke check consumes the staged toolchain and
  never builds one, and UniML ships in no artefact for a check to consume (`scripts/smoke-ci.ssc`).
- **The standalone build IS the proof that UniML stands alone** — zero dependency on the
  ScalaScript trees — so one command checks the tests and that property together. Putting UniML in
  the default distribution instead is what §7 invariant 1 of `specs/project-partitioning.md`
  forbids (no Part III module in the standard tier, 144/144 holding).

So the duplication is real and worth ending, but the thing to delete is the second **declaration**,
not the build. Read `version` (and `organization`, `licenses`, `homepage`, `scmInfo`) in
`uniml/build.sbt` from the root `build.sbt` rather than restating them. Then one value exists,
`UnimlCoordinatesSpec`'s cross-build comparison guards something that can no longer drift — and it
should be re-pointed at whatever the new single source is, or deleted with its reason recorded,
rather than left asserting a tautology.

Related and still open: `sbt-plugin-version-and-the-coordinate-templates-emit-disagree` in
`tests/BUGS.md` is a FOURTH declaration of the same coordinate.

## the operator lexer munches at most TWO characters — FIXED, and the entry's premise was wrong

**FIXED 2026-08-06.** Maximal munch over operator characters, which is Scala's rule; the two
lex-time rewrites (`:::` means `++`, `+:` means `::`) are statements about MEANING and survive, and
the token's lexeme stays the source slice so round-trip is untouched.

**The entry's own premise was wrong, and so was the comment in the code.** Both said the reference
front splits `<~>` and that the corpus wants that reproduced bug-for-bug. Measured: the interpreter
prints **304** for `extension (a: Int) def <~>(b: Int) = a * 100 + b`, and
`js-symbolic-infix-operator` passes on int. Reproducing the split is what made this dialect diverge.

**The half that cost the most was not the munch.** Widening it took tagged diagnostics 12 → 16,
because a wider token exposed that `--`, `/:` and `<~>` were not in the precedence table at all and
fell through to 0 — not infix. Adding Scala's precedence-by-first-character as a fallback then took
it to **54 and breached the floor**, because precedence 0 did NOT mean "unknown" for every token
that reached the table: for `=>` and `<-` it meant *deliberately not infix*, and the fallback turned
every lambda arrow into an infix operator. Excluding the arrows by name took it to **6**.

    12  ->  16   maximal munch alone
        ->  54   plus a naive first-character fallback   (floor caught it)
        ->   6   with `=>`, `<-`, `<:`, `>:`, `@` excluded

`SpikeOperatorMunchSpec` keeps the arrows test as the load-bearing one, and it asserts on the tree
SHAPE rather than on absence of diagnostics: a lambda that became an infix application still parses
cleanly and means something else.

## UNIML-SSC3 criterion (3) — the last breadth diagnostics — CLOSED 2026-08-06 at the floor

**Closed at 4 diagnostics over 1642 tagged fences, and all four are the `@side = server` shape
below** — i.e. every remaining diagnostic is source the REFERENCE front rejects too ("expected
start of definition"), so there is no dialect gap left in this corpus. The last two slices were
`uniml-operator-maximal-munch` (12 → 6) and `uniml-block-stops-at-comma` (6 → 4). The gate's
ceiling moved 250 → 12 in the same commit; at 4-vs-250 it could not fail, which is why the number
had stopped being evidence of anything.

The `@side` count reads 4 here and 6 below because the entry below counted before the fence rule
changed which blocks are compiled — the construct and the verdict are the same.

**What this does NOT close:** the corpus is the measure, so a construct no example uses stays
invisible. One was found while closing this and is filed as its own entry (multi-name `val`,
below). The method paragraph at the end of this entry is what makes the next one cheap; keep it.

### History (the entry as queued)

Queued here rather than left `[~]` on the sprint: nothing has held this since the claim was
released, and a board that says "in progress" with no live claim misdirects whoever reads it.

**Where it stands.** Diagnostics from TAGGED fences went 172 → ~12 across ten measured slices
(inferred `def` result type, dotted patterns, fewer-braces, expression-position ascription, quote
and splice with the char-literal lexer bug underneath, infix type operators, by-name params and
abstract vals, indented `try`, comma-separated parents, and the fence rule itself). Clean files
94.8% → 99.3%.

**The remainder is explained, which is the point — the target is not zero.**

- 6 are `@side = server` in `examples/frontend/data-table/data-table.ssc`, a construct the language
  does not have: it appears in a spec's "later phases can introduce" paragraph and the reference
  front rejects it. Filed at the root `BUGS.md`. Implementing it would "improve" breadth by teaching
  the dialect to accept what the language refuses.
- The rest is a flat tail of 1-3 per shape, each needing the same question asked one at a time:
  extract the construct into a PROGRAM file — not a module, see the oracle entry above — and ask the
  reference front. Three of today's slices came from that question and one candidate was rejected by
  it.

**The method, because it is what made the ten slices cheap.** `sbt "unimlScala/testOnly
*SscBreadthSpec"` prints a TAGGED-only histogram with one example location per shape;
`sbt "unimlBench/runMain scalascript.uniml.bench.Diagnose <file>"` prints the offending SOURCE LINE
beside each diagnostic, so a construct names itself instead of being guessed from a message like
"expected type, found '='". A histogram message can point at the wrong construct entirely — the
indented-`try` gap reported `case`, three lines below its cause.


## multi-name `val a, b = 1` does not parse — found 2026-08-06, NOT in the corpus

`val a, b = 1` — one `val` binding several names — is unparsed by the dialect, both at the top
level of a `def` body and inside an offside block. The diagnostic is `expected statement, found
','` at the comma.

**Not a regression, and not corpus-visible.** Found while writing a control for
`uniml-block-stops-at-comma`: the control asserted a comma at paren-depth 0 does not truncate a
block, and it used this construct to produce one. It fails with BOTH of that commit's stops
reverted, so it predates them, and breadth is at its floor without it — no example in the corpus
uses the form. The control was rewritten to assert something the parser can actually reach (a call
with two block-valued arguments still has two arguments).

**ASKED, 2026-08-06, and the answer was not the expected one — do not implement this yet.** I wrote
that the reference front should be asked and that "Scala has the form, so the likely answer is that
it should parse". It does parse, and it binds GARBAGE: `int` prints `<native:a>` for `a` and calls
`b` undefined, `js` prints `<function>`, `v2/F` gets `a` right and rejects the file once `b` is
used, and only `jvm` binds both names. Four lanes, four answers, two of them silently wrong with
exit 0. Filed at the root as `multi-name-val-binds-garbage-and-says-nothing` (lane `multi` — no
single module owns it).

So the dialect's diagnostic is currently the most honest behaviour of the five implementations, and
teaching it to parse the form would make it agree with an oracle that disagrees with itself.
Implement it here only after the language decides what the form means; the root entry is where that
decision belongs.

## the reference front is NOT an oracle for a module's fences — three method corrections

Measured 2026-08-05/06. The conclusion matters less than how many times the route to it was wrong,
so both are recorded.

**The question.** `v1/runtime/std/ui/form.ssc` contains `formBody([...])` in a ```` ```scala ````
fence. `...` is not valid ScalaScript — the reference rejects `g([...])`, `g(1, ...)` and
`val x = [...]` in a program. The breadth probe counts that fence's diagnostics. Is it a gap in the
dialect or content the reference would never compile?

**Three tests, two of them worthless, in the order they were run:**

1. RUN the module — `ssc-tools run --v1 form.ssc` — clean. Read as "the reference accepts it".
   Worthless: a file whose front matter has `package:` is a MODULE, and running one parses nothing
   at all. Narrowed afterwards: `name:` and `exports:` do not do this, `package:` does.
2. IMPORT the module from a consumer — runs, prints. Read as "the module compiles, so the fence
   must be fine". Also worthless, as the third test showed.
3. Put a DELIBERATELY broken fence in a `package:` module and import it. **It still runs.** With the
   break in a ```` ```scala ```` fence: prints. With it in a ```` ```scalascript ```` fence:
   prints too.

**So the reference front does not report a module fence's parse errors on either path.** It compiles
what an import asks for and nothing else. "Does the reference accept this construct?" therefore
cannot be answered with a module at all — only by putting the construct in a PROGRAM, a file with
no `package:`. Every earlier slice was tested that way and stands; this file could not have been.

**Which flips the original question.** The composer parsing every fence is not obviously wrong — a
front end for ScalaScript 3 will have to compile module bodies, and the reference's silence here is
leniency, not a specification. What is certain is only this: **the breadth column includes
diagnostics from blocks no lane has ever compiled**, so it is stricter than any existing front, and
a number that mixes the two answers neither question.

**What would settle it**, for whoever takes it: decide what a `.ssc` MODULE's non-exported fences
are — code that must compile, or examples — and make the probe ask that question explicitly, with
tagged/untagged already split the same way. Do not add an attribute check to `SscCompose` on a
guess; `form.ssc`'s fence carries no `@doc`, so the attribute is not the mechanism.

## the CST does not keep an import's PATH — FIXED 2026-08-05, same day it was filed

**FIXED under `uniml-typed-ast-complete`.** `parseImportStmt` now attaches the path as
`imp.seg`/`imp.dot`, selectors as `imp.sel` and `.*` as `imp.wildcard`, instead of consuming and
discarding them. The frame KIND stays `spike.sealed` deliberately: `SpikeProject` matches on it and
returns a constant, so the v2 lane sees no change and no kind census moves. Losslessness stayed
green — the token sequence is identical, only its attachment changed. The projection reads it as
`ImportDecl(path, selectors, wildcard)`, and an anonymous `given` — which shares the kind and
genuinely carries nothing — stays `NoOpDecl`. Regression tests assert the path, the selectors, the
wildcard, and that two different imports are DISTINGUISHABLE, which is the property that was
missing. The original report follows.

### Original report (superseded 2026-08-05)

Found 2026-08-05 under `uniml-typed-ast`, by writing the assertion that the path was there and
watching it fail. Not a projection bug: the information was not in the tree.

`ScalaSpike.parseImportStmt` consumes the dotted path token by token and then calls `sealedNoop`,
which frames `spike.sealed` from ONE carrier token. So `import a.b.c` and `import x.y` produce
indistinguishable nodes, and the same holds for the Markdown link-import `[name](path)` and for an
anonymous `given`, which share the kind.

That is correct for the consumer it was built for — the v2 front treats imports as a parse-only
no-op and resolves them by other means (`ssc1-front.ssc0:2485`). It is not correct for the role
UniML is being readied for: `v3/specs/40-front-on-uniml.md` makes UniML the parser AND the AST, and
a front that cannot see what a file imports cannot build a module graph.

Pinned rather than assumed: `SpikeTypedRolesSpec` asserts the two imports project to the same
contentless node, so a dialect change that starts keeping the path turns that test red and gets
noticed instead of landing silently.

It was deferred once, on the reasoning that changing the CST shape touches frozen losslessness and
breadth baselines and deserves its own before/after. It got one: the change landed alone, with the
losslessness suite run against it, and the baselines did not move because the token SEQUENCE never
changed — only which node owns each token. The caution was right and the cost was one build.

## HAND-OVER: eight things the v3 FRONT DIFFERENTIAL found — 2026-08-06 — ALL EIGHT HOLD

**Re-measured 2026-08-07, all eight green, and there is now a gate: `Ssc3HandoverSpec`.** The
section below says "run `v3/front-diff.sh` after any change here and the number moves or it does
not" — that is no longer true and is the reason the gate was written. The differential reports
`GREEN (48 fixtures, 1 front, agree 0)`: `v3/src/Front.scala` lists one runnable front, so it
compares nothing. It is honest about that in its output, but it means no change on this side moves
its number, and eight items fixed under eight claims had nothing asking them the same question
together afterwards.

One of the eight assertions was wrong on its first run and the CODE was right: I asserted that a
fenceless `.ssc` leaves `fences` empty, and the composer in fact records a synthetic `<bare>` fence
naming the dialect it used. That is the better design — the bare path names itself, so a consumer
can tell "no fence, taken whole" from "no fence, nothing happened" — and the assertion now checks
that instead.

**Nothing on this list is waiting on UniML.** The remaining work for the swap is v3's: the
`SpikeAst` → `Ast` projection (`v3/specs/50-uniml-projection.md`) and the wiring in `Front.parse`.

### The hand-over, as written

Written by the `ssc3-core` claim. Everything here was measured by running the two fronts over the
same file and diffing the resulting v3 `Ast`, not by reading code. The apparatus is
`v3/front-diff.sh`; run it after any change here and the number moves or it does not.

**State when this was written: the two fronts agree on 29 of v3's 40 fixtures, and all 11 remaining
differences are on this side.** v3's half is done for that fixture set.

The full argument for each is `v3/specs/40-front-on-uniml.md` §5b; this is the actionable list.

### 1 — a `trait` VANISHES, and none of UniML's own gates can see it

The most consequential item. `trait Shape:` with a body gives CST kinds `spike.program,
spike.sealed` — the same `spike.sealed` the dialect gives imports and anonymous `given`s — and
`SpikeTyped` maps it to `NoOpDecl`, "parsed, and genuinely carrying nothing". **The trait's methods
never reach the CST at all.**

So it is invisible to all three measurements at once:

- the `spike.error` count does not see it — nothing failed to parse;
- the silent-drop census does not see it — `NoOpDecl` is modelled, and there is no subtree beneath
  it to drop;
- coverage counts it as typed rather than as a gap.

This is the same shape as the coverage metric that used to REWARD dropping: a measurement can only
see what the representation admits exists. Worth a census question of its own — *which CST kinds
project to a contentless node, and is that right for each?* — because `spike.sealed` currently
covers three unrelated constructs.

It matters more than the rest combined for v3: `trait` gates **137 corpus cases**, and v3's traits
carry dispatch (`v3/specs/20-core-language.md` §2). Fixture: `v3/tests/front/traits-methods.ssc`.

### 2 — `ValDef` does not record MUTABILITY

`ValDef(name, rhs, span)` has no `isVar`, so `var x = 0` and `val x = 0` project identically. A
WRONG ANSWER rather than a smaller tree: a `var` read as a `val` makes every later assignment to it
a refusal. Four fixtures show it — `demo`, `globals`, `val-block`, `arrays`.

### 3 — `ObjectDecl` carries no `case` marker

`case object A extends K` and an empty `object A` are indistinguishable. v3 needs the first as a
NULLARY CONSTRUCTOR — it is a value, and it was **116 corpus cases** for v3. The projection cannot
guess: an empty object is useless but legal, and turning it into a constructor would invent a value
nobody wrote. Fixtures: `case-object`, `alt-pattern`.

### 4 — a CHARACTER literal projects as `IntLit`

`'x'` arrives as `IntLit("120")`, indistinguishable from the integer `120`. Also a wrong answer
rather than a loss, and the reason is specific to this language: ScalaScript's `Char` IS an integer
that prints differently (v2 stores `CharV extends IntV`), so `println('x')` prints `x` and
`println(120)` prints `120`. The distinction has to survive the projection. Fixture:
`char-literals`.

### 5 — `0 +: xs` projects as `Infix("::")`

The dialect normalises `+:` to `::`. For a `List` they agree, which is why it survived; they are
different methods on anything else, and v3 lowers them differently — `::` builds a `Cons`, `+:` is a
method call with the operands SWAPPED. Fixture: `append-ops`.

### 6 — `if c then a(i) = v` does not parse

The ONLY breadth gap in SSC3 core. Measured over every file: `v3/tests/front/` 46 files, 1 with a
parse error; `tests/conformance/` 390 files, 2 with a parse error and 4 `spike.error` nodes in
total. The other two constructs in that count — `x += 1` and `def <~>(b: Int)` — are outside v3's
core, so this is the whole debt.

The narrowing is not what the fixture name suggests, and took four probes: `a(1) = 4` on its own
line parses, and `if c then n = 5` parses. Only the COMBINATION fails. Fixture: `assign-body`.

### 7 — a NESTED block comment is diagnosed

`/* a /* b */ c */` yields `missing.right`. v3 supports nesting, because Scala does. The v1
interpreter does NOT — it answers `structural CoreIR contains parser sentinel _err` on the same
input — so this is a gap the reference front shares, and v3 is the outlier for supporting it.
Lowest priority of the eight for exactly that reason. Fixture: `block-comments`.

### 8 — the composer has no BARE mode

A `.ssc` with NO code fence yields **zero** ScalaScript subtrees; measured: bare → 0,
```` ```scalascript ```` → 1, ```` ```scala ```` → 1. Fences have been optional in this project
since 2026-07-09 — a bare `.ssc` is the program in its entirety — so a whole program reads as prose
with no diagnostic.

v3 works around it by fencing the text before handing it over (`v3/uniml/UniFront.scala`), so this
is a request rather than a blocker. It is on the list because the failure it prevents is silent.

### Two things this list is NOT

- **`given`/`using` is not on it.** v3 refuses it on its own grounds — it needs type-directed
  resolution, which Tier 0 does not have — so UniML modelling it changes nothing for v3.
- **The import PATH is not on it.** `ImportDecl(a.b.c, [], false)` works; an earlier note in v3's
  spec said otherwise and was wrong. The markdown link-import yields nothing, which is CORRECT —
  it sits outside the code fence, and `Loader` reads it from the source text.

## a 2,687-line ssc0 program lives in a `.ssc` file, and nothing had ever parsed it

Found 2026-08-06 by switching on the composer's BARE mode, and it is the finding that mode exists
to produce.

`specs/v2.2-p6.5-fsub.ssc` is the P6.5 **F** compiler — 2,687 lines of **ssc0**, the older subset
syntax (`def atEnd(i, n) = i >= n`: no parameter types, no result type). It carries no code fence,
so before bare mode the composer handed it to no dialect at all. It contributed zero diagnostics and
counted as a CLEAN file, which it was in the way an unopened box is empty.

With bare mode it is handed to the ScalaScript dialect, which does not parse ssc0, and reports
**7,110 diagnostics — 7,110 of the corpus's 7,188**. Every other bare file is under 12.

**This is not a bare-mode defect.** A fenceless `.ssc` IS the program (fences optional since
2026-07-09), so handing it over is correct; the file really is code and the dialect really cannot
read it.

### ⚠️ CORRECTED 2026-08-06 — the option this entry recommended FIRST would have broken the default front

The entry originally offered two resolutions and put "**the file is misnamed — rename it to `.ssc0`,
one rename, no parser work**" first, as the cheap one. **That is wrong, and acting on it would have
damaged a production path.** Corrected by reading who CONSUMES the file, which is what should have
happened before writing any recommendation.

`specs/v2.2-p6.5-fsub.ssc` is **F, the self-hosting subset compiler, and since 2026-07-23 (Sergiy's
flip) the DEFAULT NATIVE LOWERER.** `RunNativeV2.scala:812` states the wiring: *"staged as
`tower/bin/fsub.ssc`, run via `tower/bin/ssc1-run-fsub.ssc0`"*. The `.ssc` extension is load-bearing
— the staging path names it — and the file has its own 24 KB gate, `specs/v2.2-p6.5-fsub.sh`, with a
`--self` mode and an X1 fixpoint (stage1 == stage2).

So there is no misnaming to fix:

- the file is `.ssc` **by design**, because that is how the tower stages it;
- it is written in the **ssc0 subset by necessity** — F must compile its OWN source, so that source
  has to stay inside the subset F handles. That is what the X1 fixpoint means;
- therefore the ScalaScript dialect legitimately cannot read it, and that is a gap in neither.

**What bare mode revealed is a MEASUREMENT question, not a source one.** The breadth sweep now
includes a file that is not ScalaScript-dialect source and never was. The honest resolutions are to
classify it — the `bare` column already isolates it, and 7,110 of 7,113 bare diagnostics are this
one file — or to exclude it with the reason stated. Neither touches the file, neither touches the
dialect, and both are UniML-side.

**The lesson, since I wrote the bad half myself: a recommendation is a claim about consequences and
needs the same evidence as a measurement.** One `grep` for who references the path would have
prevented it; I wrote it from the file's CONTENTS instead. An entry a reader can act on is worse
than no entry when the action is destructive.

Original text follows, with option 1 struck.

~~1. The file is misnamed — `.ssc0` is its extension and the corpus sweep stops seeing it.~~ WRONG:
see above.
2. **The dialect owes ssc0.** If a `.ssc` may legitimately hold subset syntax, that is a breadth
   item with a 2,687-line fixture attached. Still open, still much bigger than a rename, and now
   the only one of the two that was ever a real option.

Until it is classified,
`SscBreadthSpec` carries a `bare` column with its own floor, so the number is visible and bounded
instead of averaged into the population that measures the language.

## HAND-OVER: the typed AST (UNIML-SSC3 criterion 2)

Written 2026-08-05 for whoever picks this up. Numbers measured the same day; re-measure before
trusting them, since every one of them moved during the writing of this entry.

> **PICKED UP the same day by claim `uniml-typed-ast`; steps 1 and 2 are DONE and step 3 is the
> live front.** Read this section for the reasoning — it is still the right reasoning — but take
> the numbers from the SSC3-P entry below, because they moved again. In short: step 2 was worth
> more than step 1, exactly as this section predicted. The role audit found **6,641 silently
> dropped subtrees against 2,943 admitted gaps** — the coverage metric could not see them, since
> a dropped child is absent from both sides of its ratio and dropping therefore RAISES the number.
> The largest single item was the whole pattern language (4,579) arriving as the empty string.
> All 6,641 are closed and a census now holds the count at 0.
> **Step 3 — the real gaps (`narg`, `interp`, `blockapp`, `sealed`, `listlit`) — is still open**,
> and so is the paragraph below about nothing consuming the AST: that remains true and remains
> the most valuable thing anyone can do here.

### What it is

UniML stores a `.ssc` as a LOSSLESS CST — every token, including whitespace and comments, so the
tree reconstructs the file byte for byte. That is the right STORAGE and the wrong INTERFACE: a
`Branch(kind: String, …)` is an open universe, so code dispatching on it gets no exhaustivity from
the compiler. The typed projection is the interface — a sealed ADT over the same tree.

    .ssc  ->  UniML CST (lossless)  ->  typed projection (the AST)  ->  SSC IR  ->  run

`specs/uniml-ssc3-frontend.md` §3 argues the split; `v3/specs/40-front-on-uniml.md` §3 is v3's side
of the same decision.

### Where it lives

    uniml/scala/src/main/scala/…/dialect/scalascript/SpikeAst.scala      92 lines — the sealed ADT
    uniml/scala/src/main/scala/…/dialect/scalascript/SpikeTyped.scala   137 lines — CST -> ADT
    uniml/scala/src/test-jvm/…/ssc/SpikeTypedCoverageSpec.scala          the corpus floor
    uniml/scala/src/test-jvm/…/dialect/scalascript/SpikeTypedIfSpec.scala  a SHAPE assertion

`Unsupported` / `UnsupportedDecl` are load-bearing: a projection that silently dropped what it does
not model would report a clean AST for a file it half understood. Coverage is therefore countable —
walk the AST, count the Unsupported nodes.

### Where it stands

    1,182 files   171,119 nodes   2,943 gaps   98.3% of what the dialect parses

⚠️ **CORRECTED 2026-08-06. The gap list this entry first carried was almost entirely PROSE.**
It read `spike.narg` 772, `spike.interp` 583, `spike.blockapp` 509, `decl:spike.sealed` 382,
`spike.listlit` 239 — and those were not unmodelled constructs, they were English text reaching the
projection because the composer treated UNTAGGED fences as ScalaScript and the reference front does
not. Once the composer's fence rule was made to match the reference, gaps went **2,943 → 3** and
coverage to **100.0%**, with node count RISING 171,119 → 221,619, so nothing collapsed.

The three that remain: `missing.right` ×2, `spike.sealed` ×1.

### The thing that matters most, and it is not the number

**Nothing consumes this AST.** No lowering, no type checker, no printer — so 98.3% is a SELF-REPORT
validated only by its own floor. Until something depends on the projection, nothing can refute it,
and "the AST covers 98.3%" means "the projection did not say Unsupported", not "the projection was
right".

That is not hypothetical. On 2026-08-05 the histogram's biggest entry, `spike.kw` at 2,120 — 44% of
all gaps — turned out to be a BUG, not a missing construct: the CST names an `if`'s keyword tokens
`if.then`/`if.else` and its branches `if.thenE`/`if.elseE`, and the projection read the keyword
roles. Every `if` in the corpus modelled both arms as the words `then` and `else`. The `If` node was
present and its children were syntax. The floor said 96.5% and passed, because a COUNT cannot tell
"not modelled" from "modelled wrongly".

### What to do first

1. **Re-measure.** `cd uniml && sbt "unimlScala/testOnly *SpikeTypedCoverageSpec"`. Do not inherit
   the numbers above — the first version of this entry was wrong by three orders of magnitude, and
   it was wrong because it trusted a corpus number without asking what the corpus contained.
2. **Audit the projection against the CST's ROLES, construct by construct.** The `if` bug is a class,
   not an incident: `SpikeTyped` picks children by role name, and a role that names a keyword reads
   as plausibly as one that names a subtree. Dump a CST (`sbt "unimlBench/runMain
   scalascript.uniml.bench.Diagnose <file>"` for diagnostics; a five-line walker over `UniNode`
   prints roles) and check each `byRole` against what is actually there. Cheaper than modelling a
   new construct and probably worth more.
3. **Then close real gaps**, largest first, and assert on SHAPE not on the count — see
   `SpikeTypedIfSpec` for the pattern.

### The boundary — read this before writing a lowering

`v3/specs/40-front-on-uniml.md` §4 assigns UniML's readiness to the claim
`uniml-ssc3-frontend-readiness` and says v3's item `SSC3-4` **consumes** the result and "does not do
that work, and must not duplicate it". So: improving the projection is UniML-side work. Writing the
lowering to SSC IR is `SSC3-4`, in `v3/`, held by the `ssc3-core` claim — coordinate there rather
than starting one here. `v3/` also has a live claim on it, so the paths are not free.

The most valuable single thing anyone can do for this criterion is make SOMETHING consume the
projection end to end, even one file, and compare the result against the reference front. That turns
98.3% from a self-report into a measurement. Everything else is polishing a number nobody has
checked.


## a list item's continuation prefix is lost when a code span crosses the break — FIXED 2026-08-07

**FIXED** in `md-continuation-prefix-inside-code-span`. `emitParagraphWithSegments` now splices the
prefix INTO a lexeme that swallowed its break, immediately after the embedded newline, instead of
looking for a break piece that is not there. Break pieces are deliberately left alone — a
`SoftBreak` lexeme IS the newline, and rewriting it there would insert the prefix twice. Composed
round-trip 1235 -> 1236 of 1238, and the control that a swallowed break with NO container prefix
comes back byte-identical is in the spec, because a splice driven by the wrong table would invent
indentation rather than restore it.

**The two files that remain are NOT this defect, and this entry used to imply they were.** Their
first divergence is inside a FENCED BLOCK's body: `v1/runtime/std/nodes.ssc:67` at the newline
ending the last body line before the closing fence, and `v1/runtime/std/streams.ssc:211` at a body
line beginning `//   ↑`. Neither is a paragraph continuation. Grouping all three under one
mechanism is what made them look like one fix; whoever takes them should trace from the fence body,
not from here.

### The report, as filed (the reproduction and the isolation are still the useful part)

Three files still fail composed round-trip. Minimal reproduction, and the isolation is the
interesting part:

    src   2. a (`x +\n   y`) b\n
    got   2. a (`x +\ny`) b\n

The three-space continuation indent moves to the END of the block. Order, not loss — every
character is present, which is why a length check passes and only comparing the string catches it.

**It is a COMBINATION, and each half is green on its own.** Measured in this order, all passing:

    1. one\n   two\n              list item with a plain continuation      OK
    `x +\n   y`\n                 code span crossing a line break          OK
    text `a\n b` more\n           code span inside a paragraph             OK
    2. a (`x +\n   y`) b\n        the two together                        LOSS

So three separate hypotheses each tested green before the fourth reproduced it. Worth remembering
when the next one of these is chased: a construct that behaves on its own says nothing about the
construct it is nested in.

**The mechanism, fully traced.** `matchContainers` strips a list item's continuation indent into
`paragraphPendingPrefix`, and `emitParagraphWithSegments` puts it back after the k-th BREAK PIECE
in the inline stream — "the k-th break ends segment k, so segment k+1's prefix follows", as its own
comment says. When a code span crosses the line break there IS no break piece: the newline sits
inside the span's single `CodeContent` lexeme. So the prefix has no position between pieces, the
segment is never consumed, and `finishParagraph` flushes it after the block.

**Which makes the fix bigger than it looks, and that is why this is filed rather than done.** The
prefix belongs INSIDE a token, so a fix has to split the code span's `CodeContent` at the line
break and emit the trivia between the halves. That changes the token stream for every multi-line
code span, and the markdown corpus asserts on a `tokens` axis whose baselines are checksum-frozen —
so it is a change plus a baseline regeneration, not a patch. Doing it alongside unrelated work
would also deny both halves their own before/after.

Files: `v1/runtime/std/dep-cps-ping.ssc` (differs at 576, shortest), `v1/runtime/std/nodes.ssc`,
`v1/runtime/std/streams.ssc`.

Third instance of one shape in one afternoon: an injected subtree appended instead of spliced, an
indented code block whose body interleaves with its indents, and now a paragraph's continuation
prefix. Each was a token emitted somewhere other than where it sits in the source, and each was
invisible to a check that counts rather than compares.

## indented code blocks lose the indent on every line after the first — FIXED, and the cause was
## not what this entry said

The composed tree reconstructs 1,173 of 1,179 `.ssc` exactly. The six that do not all fail the
same way — a markdown indented code block keeps the four-space prefix on its FIRST line and drops
it on every line after:

    src   \n    lane    n     O.n\n    jvm     100   8
    got   \n    lane    n     O.n\njvm     100   8

`tests/conformance/object-var-member-scope.ssc` is the shortest reproduction, differing at offset
617. The others: `examples/effects.ssc`, `tests/conformance/map-getorelse-expr-receiver.ssc`,
`v1/runtime/std/dep-cps-ping.ssc`, `v1/runtime/std/nodes.ssc`, `v1/runtime/std/streams.ssc`.

**Why the markdown corpus does not catch it.** That corpus checks CommonMark examples, and the
indented-code work in UPR-3 was measured on it — 607 of 675 passing. This is the ScalaScript
profile over real files, a combination the 675 do not contain. Two corpora, two questions.

**FIXED, and the diagnosis in this entry was wrong.** The indent was not being dropped by the
indented-code handler: the COMPOSER was injecting a ScalaScript subtree into indented code blocks
at all. An indented block is a `markdown.code-block` with no info string, so it fell through to the
untyped-fence default, and its body interleaves with a per-line indent token — replacing that body
with one subtree cannot preserve the order, so the indents ended up behind the code. Injection is
now restricted to blocks carrying a `fence.open` edge. Composed exact 1,173 → 1,176, and a
four-space-indented table stopped being handed to the compiler front as source.

Frozen as a SET in `SscComposedLosslessSpec`, so a file leaving the list is as much news as one
joining it.


## 2026-07-27 — UniML production completion (Sergiy: "Реализуй всё. Доведи до продакшена")

**Claim: UNCLAIMED** — released 2026-07-28 in triage (heartbeat 2 h stale, no live
process). Its finished work was **not** discarded: UPR-2a.2 was green in the worktree
and is now landed as `74c722c13`, so a reclaimer starts at UPR-2b with a clean tree.
**Normative execution contract:**
[`specs/uniml-production.md`](specs/uniml-production.md). This section is the single entry point for
the remaining UniML work; older unchecked UniML rows are evidence/history, not a second queue.

### Honest starting point and bounded meaning of "all"

M0–M4 exist and the four format leaves are JVM/Scala.js green in their originally declared safe
profiles. That is not the same as production completion:

- YAML has only 8 embedded official cases out of the pinned 402-case YAML test-suite and leaves its
  M3.1 grammar/lexical rows open;
- Markdown has 34 curated CommonMark inputs out of 652 and checks losslessness/no-throw, not the
  official expected semantics; the HTML5 entity tail is absent;
- the ScalaScript adapter and hybrid composer live under test source roots, not in a publishable
  module;
- M6 (query/rewrite/diff/source-map/incremental/formatter) has no implementation;
- ordinary `.ssc` code cannot obtain a lossless UniML document;
- the canonical self-hosted front `F` is default but still delegates on a measured corpus; the
  test-only Scala spike is an oracle/tooling adapter, not the selected product front;
- `scljet-address-uniml` has only its pure JSON resolver (U1), with no runtime bridge or `.ssc`
  surface.

For this program, **all** means every currently declared UniML M0–M6 production obligation plus the
named P6.22/binding/address/release tails. It does not mean automatic language detection, arbitrary
future programming-language grammars, user-executable grammars, deep incremental typing, a second
handwritten JS parser, or a stylistically opinionated universal formatter. Those stay explicit
future work and cannot be used to move this finish line.

### Non-negotiable measurement rule

Every compatibility gate **compares first and classifies after**. Official cases are never skipped
because a marker, heuristic, or old exclusion predicts failure. Each failing case prints its id,
expected/actual observable, and diff. Exclusions are allowed only for transport encodings the
Unicode `String` API cannot represent and are asserted by an exact pinned manifest. A passing
self-parity test is not external conformance.

### Ordered production slices

- [x] **UPR-0 — freeze the production contract before implementation.**
      Write and commit `specs/uniml-production.md`; reconcile the stale mutable `Processor[I,O]`
      prose in `specs/uniml.md` with the shipped immutable `Processor[S,I,O]`; reconcile
      `specs/uniml-yaml.md`, `specs/uniml-markdown.md`, and
      `specs/v2.2-self-hosted-dialect.md`. Record the settled split:
      (1) M5 publishes a ScalaScript **tooling dialect** and safe hybrid composer;
      (2) canonical compiler front remains the already-selected self-hosted `F`, not the Scala
      spike; (3) M6 is a standalone cross-platform tooling artifact. Sergiy's request in this
      section satisfies the old P6.22 "do not act without Sergiy" gate, but it does not waive any
      corpus/fixpoint gate. Ready when the specs name exact APIs, observable laws, corpora,
      security/resource limits, compatibility policy, paths, and release evidence.
      **Landed:** `24255fe1a` freezes the production boundary and reconciles all four companion
      specs.

- [x] **UPR-1 — build fail-closed external corpus gates before grammar changes.**
  - [x] **UPR-1a YAML corpus.** Vendor the exact `yaml/yaml-test-suite`
        `data-2022-01-17` material with provenance + digest and all 402 `in.yaml` cases. Compare
        validity/error classification and normalized `test.event` semantics after first asserting
        exact CST source reconstruction. Keep a tiny explicit encoding-only exclusion manifest;
        no production-based preclassification.
        **Landed:** `5daed9083`; 402/402 source and chunk reconstruction, 210 validity matches,
        128 semantic matches, 112 strict passes, 290 expected strict failures, zero crashes.
  - [x] **UPR-1b CommonMark/GFM corpora.** Vendor CommonMark 0.31.2 `spec.json` (652 examples) and
        the official GFM 0.29 extension cases with provenance + digest. Add a deterministic
        test-only semantic renderer and compare exact official output on JVM and Scala.js, in
        addition to losslessness/chunk invariance.
        **Landed:** `68f84e90c`; 675 cases × 5 axes on JVM and Scala.js, 3,131 matches and 244
        expected non-pass axes with identical full/non-pass/section digests.
  - [x] **UPR-1c report.** Print per-section pass/fail totals and the full case diff; persist the
        red baseline in this section and the spec before fixing it. The gate must prove its own red
        path with a deliberately corrupted expectation.
        **Landed:** exact case/axis rows and per-section reports ship with both corpora; the frozen
        starting digests and expected-red semantics are recorded in `30e9b5424`.
  - [x] **UPR-1d exception isolation.** Close
        `BUGS.md` `uniml-yaml-corpus-gate-exception-isolation` before landing UPR-1a: keep
        reconstruction hashing and full-row canonicalization inside explicit per-axis/per-case
        capture, preserve already collected observations, continue semantic/later-case evaluation
        after malformed UTF-16, and keep fatal VM errors propagating. Prove the old local commit
        fails the regression, then renew independent review and rerun the full UPR-1a matrix.
        **Landed:** `70068fd7b`; fail-first malformed-UTF-16/fatal-error coverage is 17/17 on both
        platforms and renewed independent review accepted it.
  - [x] **UPR-1e root/standalone build isolation.** Close
        `BUGS.md` `uniml-root-standalone-target-cache-collision`: the prescribed root-build then
        standalone-build sequence must not share incompatible incremental products. Reproduce
        without `clean`, isolate the target/analysis collision, make products disjoint (or prove
        build definitions identical), and run root→standalone→root→standalone green before UPR-1
        can claim a production gate.
        **Landed:** `3e52e6909`; all nine standalone targets are disjoint and the two-round no-clean
        transition gate is green with zero second-round compilations. Both UPR-1 apparatus bugs
        are recorded FIXED in `47df3d5af`.

- [ ] **UPR-2 — YAML 1.2.2 M3.1 full grammar and hardening.**
  - [ ] **UPR-2a lexical/directive layer.** Implement the YAML printable set, BOM rules, directive
        placement/scoping, `%YAML`/`%TAG` validation and handle expansion, tag URI/anchor/alias
        lexical rules, and the lexical/directive diagnostics promised by `specs/uniml-yaml.md`.
    - [x] **UPR-2a.1 document-scoped `%TAG` expansion.** Parse the two default handles and each
          document's `%TAG` declarations, validate duplicates/undefined handles and percent escapes,
          reset declarations at document boundaries, and expand scalar/sequence/mapping tags in the
          semantic output consumed by the event trace. Keep the raw property spelling lossless in
          the CST and expanded `YamlValue` representation tag, preserve every `%HH` triplet and its
          hex case in the normative event observable, and reject only malformed percent-triplet
          syntax. The validator must be linear in the bounded spelling, without repeated string
          prefix copies. The pinned 6CK3 decoded oracle is compared literally and classified only
          afterward by the measurement-correction sub-slice below.
          Bare non-specific `!` must remain `!` even when `%TAG ! ...` overrides the primary handle,
          and it is legal only before separation/end-of-input, never directly before `[` or `{`.
          Measured target cases are exactly `5TYM`,
          `6CK3`, `6JWB`, `6WLZ`, `735Y`, `9WXW`, `CC74`, `P76L`, `U3C3`, and `Z9M4`: all are
          were valid/valid with equal event counts and tag-only diffs before implementation. Bare non-specific `!`
          must also remain a legal tag in `52DL`, `8MK2`, `S4JQ`, and `UKK6/02`; those event rows
          were already exact but lexer validity was red. The originally measured movement was
          semantics 128→138, validity 210→214, strict 112→126, actual errors 220→216, and failures
          290→276; the compare-first correction below supersedes one manufactured 6CK3 pass.
          **Landed:** `3341a35a9`; the exact 402-case census reached all five targets with zero
          crashes, JVM/Scala.js focused and full YAML suites passed, portable lint and the affected
          conformance slice passed, and root/standalone products stayed isolated across two
          no-clean transitions. Two independent read-only reviews returned GO.
    - [x] **UPR-2a.1-measurement-correction — compare before the 6CK3 oracle
          classification.** Remove percent decoding from the production tag model and from the
          actual side of the pinned `test.event` comparison. Compare the `%HH`-preserving expanded
          tag first; only afterward classify 6CK3 as the exact known
          `yaml/yaml-test-suite#9` oracle discrepancy. If the decoded spelling is retained, expose
          it only as a named test-suite-compatibility observation which cannot contribute to the
          existing semantics or strict pass counts. Add fail-first coverage proving `%21` and `!`
          remain distinct before comparison, keep the vendored event unchanged, and verify that
          6CK3 is the only changed corpus row. Expected corrected census before UPR-2a.2:
          validity `214`, semantics `137`, strict `125`, actual errors `216`, failures `277`,
          source/chunks `402/402`, crashes `0`; regenerate the frozen row/category digests only
          after inspecting the full candidate diff.
          **Landed:** `024d80524`; the fail-first test observed the old decoded actual, the full
          candidate diff changed only 6CK3 plus its three derived category rows, and the frozen
          baseline/category digests are
          `563ec95401acfb8fab062b11408b5be8e5397a61c5d54676fff04865170fff95` /
          `cc0d52c8f34207900a95afe6725d5f9a9265c1cb6ce10f6d82feb9bf21288c78`.
          JVM/Scala.js corpus suites passed 19/19 each, full YAML passed 41/41 and 40/40, portable
          lint, affected conformance, and the two-round no-clean dual-build gate passed; three
          independent read-only audits returned implementation GO.
    - [x] **UPR-2a.2 tag/property lexical grammar.** LANDED `74c722c13` (committed
          2026-07-28 during triage: the work was finished and green in the claim's
          worktree, only the commit was missing). Every pre-registered number below
          was hit exactly — `216→218` / `214→216` / `137→138` / `125→126`, source and
          chunks `402/402`, zero crashes, and `2SXE`/`LHL4`/`U99R` the only changed
          rows. Re-verified before landing on both lanes: `unimlYaml/test` 54/54,
          `unimlYamlJs/test` 53/53.
          Enforce the complete YAML 1.2.2
          `ns-uri-char`, `ns-tag-char`, local/global prefix, verbatim tag, expanded-URI,
          anchor, and alias spelling productions on JVM and Scala.js. Cover raw non-ASCII and
          forbidden punctuation, empty suffix/verbatim forms, invalid global URIs, exact
          `%HH`/hex-case preservation, property-aware `:` detection, and flow-context-sensitive
          property termination. The only authorized official-row changes are `2SXE`, `LHL4`, and
          `U99R`; from the corrected baseline the bounded target is actual errors `216→218`,
          validity `214→216`, semantics `137→138`, strict `125→126`, failures `277→276`, with
          source/chunks `402/402` and zero crashes. `7FWL`, `W5VH`, `8XYN`, `Y2GN`, `WZ62`, and
          every UPR-2a.1 target are explicit no-regression sentinels. Queue, rather than absorb,
          later structural/event-recovery or alias-resolution defects discovered by this slice.
  - [ ] **UPR-2b scalar engine.** Implement multiline plain/single/double quoted folding, complete
        double-quoted escapes and escaped line breaks, literal/folded block indentation,
        more-indented lines, chomping, explicit indentation, and malformed-header recovery.
        Regression seeds include official `7T8X`: `* bullet` inside folded block-scalar content
        must be scalar text rather than an alias, and official `FBC9`: its continued plain-scalar
        line beginning with `!` must remain scalar content. Also cover `!`/`&`/`*` at the beginning
        of literal, folded, and multiline plain content lines, while a flow comma or trailing
        comment must not carry plain-continuation state to a following node. Keep these multiline
        cases out of UPR-2a.2 so its authenticated corpus transition remains exactly
        `{2SXE,LHL4,U99R}`.
  - [ ] **UPR-2c structural grammar.** Replace heuristic indentation acceptance with strict
        block/dedent state; cover indentationless sequences, compact/property-only nodes,
        explicit/complex keys, flow pairs/collections, document streams, and deterministic local
        recovery.
  - [ ] **UPR-2d resource behavior.** Remove quadratic `state + chunk`/repeated string
        concatenation, enforce every source/line/scalar/indent/depth/node/token/diagnostic/
        anchor/alias/expansion limit, and add adversarial/fuzz/no-throw tests.
  - [ ] **UPR-2e closure.** All representable official YAML cases compare green on JVM and
        Scala.js; every chunk split of dense CRLF/astral documents is identical; Core Schema
        differential remains green; `uniml/lint-portable-subset.sh` stays green.

- [ ] **UPR-3 — CommonMark/GFM completion and full HTML5 entities.**
  **Measured 2026-07-31, corpus 460 → 516 passing of 675.** The census says where the work is
  and it is not where "finish the parser" suggests: of the 244 non-pass axes at the start,
  **215 were `html` and only 14 + 14 + 1 were source/tokens/status**. The LOSSLESS core is
  essentially there; what is missing is projection and the block/inline grammar behind it. Two
  defects closed so far — a shortcut/collapsed reference link looked its definition up by the
  EMPTY key (+27), and indented code opened one block per line with the four columns left in the
  literal (+29, and it moved source/tokens 14 → 10 each, so per-line framing had been producing
  worse trees than one block does). Then two more:
  links stop nesting (+5), and GFM extended autolinks were implemented (+11 — that section was
  0 of 11, absent rather than broken, and is now 11 of 11).
  A fifth then took it to **552**: CommonMark's own trimming rule (4.8 + 6.7) was not applied
  to block content, and applying it reached far past the two sections that led there —
  hard line breaks 7 → 0 and setext 8 → 5, but also Links, List items, Images, Tabs and
  block quotes. Whitespace in the SEMANTIC view was contaminating cases filed everywhere.
  A sixth took it to **563**: the three independent readers of a `[foo]:` line were replaced
  by ONE scanner returning source slices, which both fixed the pre-pass/emitter disagreement
  (`Foo\n[bar]: /baz` registered a destination-less ref) and made the multi-line forms fall
  out. Known restriction, deliberate: a multi-line definition is offered at TOP LEVEL only,
  because `matchContainers` has already eaten the container prefix — scanning raw lines inside
  a list item emitted it twice and broke the source axis. Multi-line definitions inside a list
  item or block quote stay red rather than lossy.
  A seventh took it to **572** and was the worst class in the file: `- one\n\n two` LOST `two`
  from the output entirely. A failing item continuation closed the ListItem but left the
  ListFrame open, the following paragraph became a direct child of the list, and the
  projection — which collects only list items — dropped it. Tokens all present, source axis
  green; only `html` could see it. Worth remembering when triaging: a green lossless axis
  says nothing about whether the PROJECTION keeps your content.
  Ranked remainder at 572, by failing cases: Links 16, List items 14, emphasis 10, Lists 10,
  raw HTML 8, fenced code 6, entities 5, autolinks 4, images 4, tabs 4, block quotes 3.
  An eighth took it to **577**: a fence body inside a container never lost the container's
  prefix, because processLine dispatched fence lines BEFORE matching containers — so the
  closing fence read as four columns of indent and the block swallowed the rest of the
  document. Lists 10 → 7, List items 14 → 12.
  A ninth took it to **587**: reference labels were matched on the RENDERED text, so
  `[*foo* bar]` looked up `foo bar` and missed its own definition — the emphasis still
  rendered, so it read as a link that had lost its destination rather than a failed
  lookup. CommonMark matches on the RAW label source. Same defect's second cause: the
  full-reference form found its opening bracket with a raw `lastIndexOf('[')` and sliced
  `[foo][ref\[]` in half. Links 16 → 11, Images 4 → 0.
  A tenth took it to **595**: inline raw HTML had NO tag grammar — anything up to the next
  `>` without a `<` was emitted as raw HTML, so `<a h*#ref="hi">` and `<a href="\\"">` went
  through unescaped. A malformed tag is TEXT; emitting it as HTML is the difference between
  showing a user their typo and putting it in the document, which is worth more than the
  eight cases it scored. Raw HTML 8 → 4.
  **UPR-3a is now DONE** and took it to **601**; see its entry above.
  An eleventh took it to **607** and was aimed at the LOSSLESS axes, not the count: seven of
  the ten remaining source failures were one bug — a blank line held inside indented code
  was overtaken by the NEXT line's container prefix, because matchContainers emits that
  prefix as soon as it matches. Extending its existing paragraph buffering to an open
  indented code block fixed all seven. **source 10 → 3, tokens 10 → 3**, html unchanged.
  ⚠️ The first attempt also added a matchContainers branch for that case, which swallowed
  the partial-match path and stopped closing containers — example 231 then THREW instead of
  parsing. Section counting reads that as 'Block quotes 3 → 5'; it was an exception. Count
  the exceptions, not just the failures, when a container change looks mildly negative.
  Ranked remainder at 607: emphasis 10, Links 9, Lists 7, fenced code 6, List items 6,
  raw HTML 4, tabs 4, block quotes 3, setext 3, tables 3, HTML blocks 2.
  The `status: 1` axis is ONE pre-existing parse exception, A/B-confirmed as not introduced
  by any of this work; it is the obvious next thing to name and fix.

  **UPR-3a is now the best-value block left and it is not a parser change.** All 5 Entity
  cases plus several Links need (a) the real WHATWG HTML5 entity table — the built-in one
  covers `&nbsp; &amp; &copy; &AElig;` and stops there — and (b) decoding applied in
  DESTINATIONS, TITLES and fence INFO STRINGS, which is a wiring job once the table exists.
  It is a generator + pinned-snapshot + manifest task in the shape of the corpus pipeline
  already in `uniml/corpus/markdown/`, not a grammar rewrite.

  **TRIED AND REVERTED — read this before retrying it.** CommonMark 5.2 says 5+ spaces
  after a list marker mean the content starts with INDENTED CODE, and the item's content
  indent is then marker + 1 (examples 273, 274). Implementing that in `startsListItem`
  alone left `passing` unchanged at 577 and moved **source and tokens 10 → 12**: shortening
  the marker lexeme drops those spaces out of the token stream, and no leaf handler picks
  them up. The rule is right; it needs the EMISSION side reworked in the same change.
  Trading a lossless axis for an html one is never the deal — the axes are what caught it.

  The heaviest remaining theme is still CONTAINER CONTENT INDENT: indented code inside a
  list item (273, 274) and the two source-axis failures (254, 264) are all one area, and it
  wants its own pass over how containers hand content to leaf handlers. Mapped onto the sub-items:
  **3b** owns List items + Lists + setext + the multiline `[foo]:` forms; **3c** owns the rest of
  Links (angle destinations, escaped titles, bracket-vs-raw-HTML precedence) and emphasis; **3a**
  is not optional decoration — a real HTML5 entity table is what several remaining Links cases
  and all four Entity cases actually need.
  - [x] **UPR-3a generated entity data.** Generate a deterministic, checksummed table from pinned
        WHATWG `entities.json`: all semicolon-terminated HTML5 names and multi-code-point values;
        distinguish unknown text from an entity token; reject invalid numeric/surrogate values;
        decode only in contexts CommonMark permits (including destinations/titles/definitions,
        excluding code/raw HTML).
        **Landed.** `whatwg-entities.json` pinned by SHA-256 (CC BY 4.0, LICENSE.whatwg.txt);
        generate.py emits all 2125 SEMICOLON-TERMINATED names, excluding the 106 legacy
        names without the semicolon that CommonMark 6.2 does not recognise. It replaced a
        HAND-TYPED table of ~250, which is why `&copy;` decoded and `&Dcaron;` did not.
        Emitted as one encoded string, not a 2125-entry Map literal — that size of literal
        is a single static initialiser at the JVM's 64 KB method limit and would never be
        JIT-compiled. It lands in the MAIN tree (the decoder is production code), which
        needed a THIRD controlled root in generate.py, manifested and probed like the
        other two. Decoding also wired into destinations, titles and fence info strings,
        which hold raw slices rather than token streams and so were never covered.
        Entity section 5 failures -> 0; corpus 595 -> 601.
        STILL OPEN from this item's text: rejecting invalid numeric/surrogate values, and
        proving decode-context exclusions (code spans, raw HTML) by gate rather than by
        reading the code.

  - [ ] **UPR-3b block grammar.** Close tabs/indentation, indented code coalescing, fenced closing
        whitespace, HTML blocks, multiline reference definitions/titles, lists/containers and
        blank/lazy continuation by official section.
  - [ ] **UPR-3c inline grammar.** Close destinations/titles, labels and Unicode normalization,
        bracket/reference precedence, autolinks/email/raw HTML, delimiter/emphasis rule-of-three
        behavior, escapes/entities/code spans and line breaks by official section.
  - [ ] **UPR-3d limits/complexity.** Actually enforce line/delimiter/fence/reference/block and
        core limits with diagnostics; keep scanning bounded and linear on adversarial inputs.
  - [ ] **UPR-3e closure.** CommonMark 0.31.2 is 652/652 exact on JVM + Scala.js; enabled GFM 0.29
        cases are exact; lossless/chunk/property suites and `DocumentContent` differential stay
        green. Only then may the specs call the profile conformant.

- [~] **UNIML-SSC3 — UniML as ScalaScript's parser and AST (language version 3).**
  Direction decided 2026-08-01: [`specs/uniml-ssc3-frontend.md`](../specs/uniml-ssc3-frontend.md).
  This is the umbrella; UPR-4 below is its first construction step and UPR-8b supplies the oracle.
  Tracked on `uniml/SPRINT.md` as UNIML-SSC3.
  - [ ] **SSC3-M measure before building.** Parse throughput and retained tree size for the UniML
        front on the real `.ssc` corpus, against `F`, via `scripts/bench` and the alternating A/B
        protocol. Write the budget down BEFORE the numbers so the result cannot be rationalised
        after the fact. THIS IS FIRST: it is the cheapest of the six criteria and the only one
        that can still change the design. Neither number exists today, and a front end runs on
        every compile of every file, so "unmeasured" here is a live risk and not a formality.
  - [x] **SSC3-L1 stop duplicating tokens in the spike tree.** MEASURED: `def f(using ev: Int)(a:
        Int): Int = a` gives 17 tree leaves over 16 distinct token ids — `Node.Leaf(c.peek.get, …)`
        adds a token without advancing and a later rule adds it again. Until each lexed token
        appears at most once, NO re-interleaving scheme can restore trivia correctly, and the tree
        cannot be lossless in either direction. This blocks SSC3-L2.
  - [x] **SSC3-L2 emit over the full lexed stream.** Re-attach the tree's frame transitions by
        token id and emit every other lexed token in source position; filter the projection's
        `kids` by ROLE, not by kind, because an `unparsed` token has an ordinary syntactic kind.
        TRIED AND REVERTED 2026-08-01 before L1: clean input went lossless (36 Syntax + 22 Trivia)
        and `actors.ssc` loss fell 23,647 → 43 chars, but C_min's projection collapsed on the
        duplicate-token case. Details in `specs/uniml-ssc3-frontend.md` §4.3.
  - [x] **SSC3-L3 raw string lexemes.** `spike.str` stores the DECODED value, deliberately
        ("mirrors ssc1-front"), so quotes and escapes never reach the CST — the residual 43
        characters. Move decoding to the projection, where Markdown already does it; touches every
        `spike.str` consumer including interpolation.
  - [x] **SSC3-L4 raw NUMBER lexemes.** The last character. `30_000` loses its digit
        separator because the number lexer stores a normalised VALUE, exactly like `spike.str`
        did: it also folds hex to decimal, strips `L` suffixes and decides int-vs-float, so its
        decoder is a bigger mirror than the string one. **actors.ssc is at 1 lost character of
        29,568 and C_min is fully lossless**, so this is the only thing between the dialect and
        losslessness on real ScalaScript.
        **Landed.** Numbers AND char literals now carry the source slice, with
        `SpikeNum.decode` producing the value in the projection. VM semantics unchanged —
        chars are still codes, hex still folds, a Long suffix is still not part of the
        value — only WHERE that happens moved. **`actors.ssc` (31 KB of real production
        ScalaScript) and C_min now reconstruct EXACTLY**; the arc across L1-L4 was 23,647
        lost characters to zero.

  - [ ] **SSC3-B/textarea — 20 diagnostics whose CAUSE IS NOT FOUND.** Recorded rather than
        left as a hunch, with the methods that failed, because the next person will otherwise
        repeat all three.
        `examples/std-ui/textarea.ssc`. The first diagnostic reads `expected statement, found
        'class'` at 46:56, inside `html"""<small class="${errCls}">…"""`. That is a RECOVERY
        LANDING SITE, not the origin: every shape of that line parses CLEANLY in isolation —
        plain triple-quote, `s`/`html` prefix, single-quote form, an attribute with a nested
        `"` and `${…}`, and the whole `val x = if c then html"""…""" else ""` in an indented
        def body. All zero diagnostics.
        ⚠️ **Bisecting the fence body by LINES is invalid here** and pointed confidently at
        line 9 (`sc.css("""` opening a multi-line string): cutting at a line splits that
        string in half, so every prefix ending inside it fails for a reason the file does not
        have. Verified by parsing the same construct whole — one-line, multi-line, and with a
        nested `"` inside — all clean.
        **SOLVED BY DELTA DEBUGGING — `SscReduce`.** 44 lines reduce to 5, keeping the
        diagnostic. The GUARD is the whole design: "still fails" is not enough and neither
        is "fails no more often" — a torn multi-line string does both, and the weaker guard
        produced a 2-line fragment whose failure was its own. A candidate is accepted only
        if the target message survives AND it introduces NO message the original lacked.
        The reduction points at what every isolation test I wrote had missed: a
        **MULTI-LINE** interpolated triple-quoted string —
        `val labelEl = if … then "" else html"""<label class="${labCls}" for="${name}">${`
        with the `${` left open at end of line. Every shape I had checked by hand was
        single-line, which is why they all came back clean.
        ⚠️ **Found on the way: an UNTERMINATED triple-quoted string produces NO diagnostic**
        at all — the lexer reads it to end of input and emits it. That is its own gap.
        **Reducing over TOKENS instead of lines removes that limit** — `SscReduce.reduceTokens`.
        It works because of the losslessness work: every lexeme is a SOURCE SLICE, so
        concatenating surviving tokens is real source, and a multi-line string is ONE token so it
        cannot be torn. Removed ranges must also be BRACKET-BALANCED. textarea's 2,018-character
        fence body reduces to **81 characters carrying exactly the one target diagnostic**:
        `def render(name:String,value:String,placeholder:String):String=` / `ifthen{}class`.
        `class` survives as its OWN TOKEN, which is the fact worth having: in this file the
        `html"""…"""` does NOT lex as a single string, though every isolated shape of it does.
        Debugging is now 81 characters instead of 2,018 — the cause is still unnamed, but it is
        cornered rather than guessed at. Checked and ruled out: the fence count (this file has
        exactly ONE ScalaScript fence, so no concatenation artefact).

  - [~] **SSC3-P typed projection.** The ScalaScript analogue of `MarkdownProjection`: CST in,
        typed AST out. This is the artifact the decision actually names — the lossless CST is the
        STORAGE, the projection is the INTERFACE. Without it the type checker would dispatch on
        `Branch("spike.def", …)`, which no shipped compiler does and which §3 of the spec argues
        against explicitly.
        **First cut landed.** `SpikeAst` + `SpikeTyped`, separate from `SpikeProject` (which
        serialises ssc0 text for the v2 front — this is the tree a compiler would hold; both
        read the same CST). `Unsupported` is never dropped, so coverage is a NUMBER and it
        did its job immediately: the first run read 24% and named `spike.exprStmt` as 96,343
        of the gaps, a construct I had not modelled at all.
        **96.3% of what the dialect PARSES, over 1,143 files and 140,351 nodes**, gated with
        floors rather than equalities. The gate reports parse-errors and AST gaps SEPARATELY —
        they are breadth (SSC3-B) and typing, and conflating them hides which is which.
        ⚠️ **The first version of these numbers was WRONG by two orders of magnitude** — 33,487
        "parse errors" against today's 444 — because it fed whole `.ssc` files to the BARE
        ScalaScript dialect. A `.ssc` is a literate document, so every heading and fence
        backtick counted as broken ScalaScript; 18,782 of them were backticks. Measure through
        `SscCompose`, which hands each dialect its own bytes. The gate lives in `uniml/scala`
        for that reason and carries the mistake in its header.
        Ranked remainder, printed by the gate: `spike.kw` 2,130, `sealed` 845, `narg` 719,
        `blockapp` 504, `interp` 394, `listlit` 199.
        Still owed: the AST is not yet the thing anything CONSUMES — no lowering to SSC IR,
        and no differential proving `SpikeTyped` and `SpikeProject` agree on meaning.
        **2026-08-05, claim `uniml-typed-ast` — the ROLE AUDIT the hand-over asked for, and the
        count was hiding more than it reported.** Re-measured first, inheriting nothing: 1185
        files / 171,119 nodes / 2,943 gaps / 98.3% reproduced exactly.
        **The metric could not see its own largest failure.** `Unsupported` is honest about a shape
        not MODELLED; it says nothing about a subtree never LOOKED AT. A dropped child produces no
        node, so it is missing from the numerator AND the denominator — dropping a construct RAISES
        the coverage figure. A second census in `SpikeTypedCoverageSpec` walks CST→AST instead and
        asks of every branch child whether it reached the AST at all: **6,641 silently dropped
        subtrees against 2,943 admitted gaps — 2.25× more dropped than reported.**
        Nineteen distinct sites, summing exactly to the total, every one a variant of the `if` bug:
        - `case.pat` read with a helper that returns `""` for a branch — **4,579 patterns**, the
          whole pattern language, projected as the empty string. `case Some(x)` and `case _` were
          indistinguishable, and an empty `String` is perfectly well-formed so nothing complained.
        - `cc.fieldType` 1,720, `cc.method` 130, `def.dflt` 56, `cc.dflt` 14 — roles emitted by the
          dialect and read by the reference, never read here. `Param.tpe` was hard-coded `None`.
        - `enum.case` 142 — a role **the dialect never emits**; cases are `spike.enumcase` child
          branches, so every `EnumDecl` in the corpus carried an empty case list.
        - `group.elem` selected by "not a token", so `(x)` projected as `UnitLit` and `(1, 2)` as an
          EMPTY tuple. Token-level, therefore invisible to the census too — caught by reading, held
          by shape assertions.
        **All 6,641 closed: the census now reads 0, and that is the gate's ceiling.** Nodes
        171,119 → **197,773** (+26,654 subtrees that had never reached the projection), coverage
        98.3% → **98.5%**, admitted gaps 2,943 → 2,964 — UP, which is the point: newly-reached
        subtrees now report themselves honestly instead of vanishing.
        Verified by A/B, not by assertion: re-planting the `enum.case` defect turns the census red.
        Shape assertions per construct in `SpikeTypedRolesSpec` (20 tests), since a COUNT cannot
        tell "not modelled" from "modelled wrongly" — the lesson the `if` bug taught, applied.
        **Then step 3, the honestly-reported gaps: top five closed.** `narg` 772, `interp` 589,
        `blockapp` 511, `sealed` 382, `listlit` 252, plus `spike.lam` — a SECOND CST kind for
        lambda (`ScalaSpike.scala:1443`) that only `spike.lambda` was handling.
        **Gaps 2,964 → 672, coverage 98.5% → 99.7%, nodes → 212,885, silent drops still 0.**
        Coverage floor raised 95.0 → 99.0; four points of slack is what let a 96.5% reading pass
        with every `if` in the corpus mis-modelled.
        Two of the five shape tests failed first and both were FINDINGS: a leading `[` in statement
        position is a link-import, so the list-literal probe was measuring a no-op; and an import's
        PATH is not in the CST at all (own entry above). Modelled as `NoOpDecl` rather than claimed.
        `interp` keeps prefix + raw text, and that is not a shortcut: the dialect does not
        decompose an interpolation — `spike.interp` holds exactly two tokens — so the embedded
        expressions are not subtrees that could be dropped. Splitting them is a re-lex and belongs
        where the interpolation is given meaning.
        **Then the rest, under `uniml-typed-ast-complete`: gaps 672 → 28, coverage → 100.0%.**
        Every remaining construct modelled — `pfblock`, `throw`, `try`, `for`, `rangeop`, `summon`,
        `givenobj`, `givenval`, `effectdecl`, quote/splice/qname, `???`, a local `def`, index and
        compound assignment, destructuring val, the optics markers — plus five statement kinds that
        `expr` already handled and `decl` did not ROUTE, so the identical node projected fine inside
        a block and read as unmodelled at top level. The 28 left are parse-recovery holes
        (`missing.right` = an infix whose operand the DIALECT diagnosed): breadth, not typing.
        **No construct the CST has is unmodelled.** Floor 95.0 → 99.0 → 99.9.
        **The census learned to judge TOKENS, and that is what closed the loop.** The branch-only
        version stated its blind spot honestly, and the blind spot is exactly where `group.elem`
        had hidden — found by reading, which does not scale. `SpikeTyped.traced` now reports every
        span consumed AS TEXT, recorded in `lex`/`text`, the only two places text is read.
        Two wrong attempts first, each caught by its own output: a kind-based content rule reported
        1,437 `var.kw -> spike.id` because **this dialect lexes keywords as identifiers**, and
        flag-reads via `isDefined` consumed 35 tokens without leaving a trace. It then found two
        bugs nothing else could: **an extension dropped its RECEIVER** (`ext.recv`, 35 — wrong, not
        merely small) and a dotted `def Source.distributed` kept only `Source` (`def.nameseg`, 40).
        Evidence, all slices: `cd uniml && sbt -batch test` exit 0, 10 projects passing — the exact
        command and floor of the nightly `UniML — standalone build` job; 38 typed-AST tests green.
        (`scripts/smoke-ci` does NOT cover uniml; its only mention is the comment recording why.)

  - [ ] **SSC3-B breadth — to the reachable floor, NOT to zero.** Differential against `F` over the whole corpus until
        DIFF=HOLE=EMPTY=TIMEOUT=0. Until then the declared dialect id names the passing SUBSET
        (UPR-4a's own rule), and no fallback may be counted as a match.
        ⚠️ **THE TARGET IN THIS ITEM'S TITLE WAS WRONG AND MEASUREMENT CORRECTED IT.** An
        UNTAGGED fence (```) defaults to ScalaScript, so a fence holding a protocol diagram,
        shell output or pseudocode produces diagnostics NO parser fix can remove —
        `v1/runtime/std/mapreduce/shuffle.ssc` spends 18 of them on `Phase A (map stage):`.
        Measured 2026-08-04: of the 64 files with any diagnostic, **17 hold an untagged fence
        and account for 129 of 366 diagnostics**. (Upper bound on the unreachable share: such a
        file may also have real gaps in its tagged fences.) So "DIFF=HOLE=EMPTY=TIMEOUT=0" has
        to be read against TAGGED ScalaScript fences only; chasing literal zero would push
        toward changing the untagged default or teaching the parser prose, and both are wrong.
        **Measured 2026-08-02 through `SscCompose`: 1,029 of 1,143 files parse COMPLETELY
        CLEAN (90.0%), 1,278 diagnostics in total.** So breadth is a finite list, not a
        rewrite. Ranked by what the parser trips on: `object` (94 "expected class name"),
        `class` (102 "expected X after class"), then `:`/`(`/`)`/`=` shapes inside them.
        Worst files: `scljet/values.ssc` 205, `v1/runtime/std/streams.ssc` 159,
        `scljet/vfs.ssc` 85. ⚠️ An earlier census said 3.8% clean; it fed literate files to
        the bare dialect and was measuring markdown.
        **`case object` outside the top level: FIXED** — `parseProgram` handled it and its twin
        `parseMember` did not. 1,029 → 1,041 clean files, 1,278 → 808 diagnostics.
        Gated by `SscBreadthSpec` (diagnostics, not AST nodes — the node-count gate could not
        see this fix at all).
        **`extern` + qualified def names: LANDED TOGETHER** — diagnostics 808 → 678 (-16%),
        `v1/runtime/std/streams.ssc` 159 → 24 (-85%). The pairing was the result: `extern` alone
        went the WRONG WAY (808 → 888) because it only exposed `def Source.from[A](…)`. The
        third form I had listed, a def with no parameter clause, was already supported — checked,
        not assumed. ⚠️ Measuring this also exposed a GATE defect: all three sweeps walked `bin/`,
        where `install.sh --dev` copies the whole runtime, so the corpus jumped 1,145 → 1,406
        files the moment anyone ran the installer and every count moved with it. `bin/lib/` is
        now excluded in all three.
        Then three more slices, each measured: **escaped identifiers** (`` `type` `` — the
        lexer had no backtick case at all) and **varargs** (`TkNode*`) together took
        678 → 522; **type ascription** `(42: Int)` took 522 → 483. Running total for
        SSC3-B (at that point): 1,278 → 483 diagnostics.
        Then **qualified names whose QUALIFIER carries type params** (`Source[A].distributed` —
        they sit between the segment and the dot, so the earlier dot-chain stopped at `[`) and
        **any interpolator prefix** (`html"""…"""`; Scala's rule is any identifier ABUTTING a
        string, and adjacency is the discriminator) took 483 → 362.
        **Running total: 1,278 → 362 diagnostics, 90.0% → 94.5% of files parsing completely
        clean.** ⚠️ Those last two were only findable after the composer's span remap: the probe
        used to pick them had WRONG line numbers until then, and I had mis-read both constructs
        from it. Worst files now: `std-ui/textarea.ssc` 20, `error-handling.ssc` 20,
        `std-ui/input.ssc` 18, `mapreduce/shuffle.ssc` 18.
        Named constructs still open, each seen in a probe rather than guessed: **type
        aliases** (`type X = Y`, and `infix type throws[A, E]`), and **function/tuple
        types in a parameter** (`(B, A) => B`).
        _Superseded record of the first attempt:_ ssc1-front
        treats it as a declaration-STARTING identifier (`ssc1-front.ssc0:2705,:3038`), and there
        are 455 `extern def` sites. Adding it to `declModifiers` made the numbers WORSE —
        streams.ssc 159 → 228, total 808 → 888 — because it is not the only gap in those lines:
        letting the parser past `extern` exposes **`def Source.from[A](…)`, a DOTTED def name**,
        and **`def Source.empty[A]: T`, a def with NO parameter list**. The parse got further and
        failed more often. `extern` must land together with those two, not before them; alone it
        trades an early cascade for many later ones. (`extern class`, which ssc1-front consumes
        atomically, does not appear in the worst files and is a third, separate shape.)
  - [~] **SSC3-L losslessness on the language corpus.** Exact source reconstruction and
        chunk-invariance across every `.ssc` in the corpus, with the same axis discipline the
        Markdown corpus uses. This is the reason to put UniML in the front at all, so it is a
        release criterion and not a nice-to-have.
        **L1-L4 landed AND GATED.** `SpikeLosslessSpec` checks three properties over sixteen
        hand-written shapes plus two real files (`actors.ssc`, C_min): every lexeme is a source
        slice, the parse is invariant to chunking (1/7/64/4096), and no token appears twice.
        Verified in BOTH directions — planting "drop a string's opening quote" turns it red and
        names each input with its exact loss.
        **The corpus is now EVERY `.ssc` in the repository — 1,140 files, all reconstructing
        exactly** — with an assertion that it found >500 so it cannot silently shrink back to
        hand-written input. Widening it from two files immediately found two more defects the
        pair could not: `:::` lost a character (the lexer REWRITES `:::`→`++` and `+:`→`::`, so
        the lexeme was not the source), and `case A =>` with an empty body carried the arrow
        token twice. ⚠️ While fixing the first, the suite went GREEN with `:::` completely
        broken — moving the raw slice out without moving the rewrite left `opPrec(":::")` = 0,
        so it stopped parsing as an infix at all, and nothing in 78 tests covered it. Both
        operators are in the gate now. Still owed: no frozen baseline the way Markdown has one,
        so a REGRESSION shows as a red test rather than as a diff of what changed.
  - [ ] **SSC3-I independence.** Zero dependency on `v1/` or `v2/` from the front-end dialect —
        the surviving half of `project-partitioning.md` §8.3, already enforced by
        `tests/e2e/project-partition-gate.sh` check 3. A dialect describes the LANGUAGE, not a
        compiler's data structures.

- [ ] **UPR-4 — M5 production ScalaScript dialect and hybrid composition.**
  **This item changed meaning on 2026-08-01.** It used to build an oracle/tooling adapter;
  it is now the first construction step of the ScalaScript 3 FRONT END
  ([`specs/uniml-ssc3-frontend.md`](../specs/uniml-ssc3-frontend.md)). Two consequences:
  a typed projection over the CST is needed as well (the ScalaScript analogue of
  `MarkdownProjection` — the CST is the storage, the projection is what the compiler
  consumes), and **parse throughput and retained tree size must be MEASURED against `F`
  BEFORE 4a is built**, because a front end runs on every compile and neither number
  exists today.
  - [ ] **UPR-4a publishable adapter.** Move/refactor the 2,447-line test spike into
        `uniml/scala/src/main/scala/scalascript/uniml/dialect/scalascript/` as lexer/parser/
        projection/public facade. Preserve exact raw lexemes; use code-point spans; make malformed
        input total; add finite source/depth/token/diagnostic limits and typed projection failures.
        The declared dialect id must name the actually passing subset until its corpus reaches full
        ScalaScript breadth.
  - [ ] **UPR-4b cross build.** Define `unimlScalaCross` JVM + Scala.js in the root and standalone
        builds, publish `scalascript-uniml-scalascript`, remove all production `test->test`
        dependencies, aggregate it in root CI, and keep the former spike only as a thin
        compatibility/oracle alias if needed.
  - [ ] **UPR-4c safe composer.** Publish `SscCompose`. Retain the host body's source tokens while
        attaching all injected roots; remap child spans to the parent source/absolute code-point
        range; guarantee globally monotone unique token ids; localize child diagnostics; keep the
        built-in registry non-overridable and unknown fences inert.
  - [ ] **UPR-4d gates.** JVM+JS exact reconstruction/chunk/surrogate/limit/error tests, single- and
        multi-file external front differential for the declared profile, and a hybrid
        front-matter/prose/multiple-fence corpus. No lossless or compatibility label from a
        first-root-only/self-parity shortcut.

- [ ] **UPR-5 — M6 cross-platform tooling reference implementation.**
  - [ ] **UPR-5a module/API.** Add `uniml/tooling` as JVM+Scala.js artifact with
        `NodePath`, deterministic iterative query, validated `TextEdit`/`RewritePlan`, structural
        `TreeDiff`, monotone `SourceMap` (explicit synthetic/unmapped segments),
        `ParseSnapshot`/stable sidecar node ids/damage ranges, and formatter protocols.
  - [ ] **UPR-5b rewrite + diff laws.** Reject overlapping/out-of-range/cross-source edits; identity
        produces no edit; unchanged bytes remain byte-identical; `apply(diff(a,b),a) == b`;
        diagnostics include the actual conflicting ranges.
  - [ ] **UPR-5c source-map/query laws.** Source/token/node lookups are deterministic and
        source-ordered, synthetic regions never pretend to have source bytes, and forward/reverse
        mappings are monotone with explicit ambiguity.
  - [ ] **UPR-5d incremental reparse.** A full-reparse fallback may preserve correctness but is not
        advertised as reuse. Ship at least one dialect-aware damage/reuse implementation; random
        edit sequences over JSON/YAML/XML/Markdown/ScalaScript must equal clean parsing, while
        unchanged nodes outside damage retain stable ids.
  - [ ] **UPR-5e formatter.** Formatter returns a rewrite plan, is idempotent, reparses to equivalent
        semantics, preserves excluded/inert regions exactly, and ships conservative canonical
        formatters for the declared format profiles rather than a style-preference framework.

- [ ] **UPR-6 — portable `.ssc` ABI and additive standard-library bindings.**
  - [ ] **UPR-6a ABI.** Add pure `runtime/std/uniml.ssc` ADTs for spans, diagnostics, ordered
        lossless nodes/results/tool operations. Never leak Scala `UniNode`, platform objects,
        unordered maps, `Double`-coerced YAML numbers, or duplicate-key loss across the ABI.
  - [ ] **UPR-6b public format surfaces.** Add lossless wrappers to `std.json` and `std.yaml`, create
        public `std.markdown`, and expose XML/ScalaScript through `std.uniml`. Preserve every legacy
        `jsonParse/jsonRead/jsonValue`, `parseYaml/toYaml`, bootstrap Markdown/content behavior;
        migration happens only after byte/semantic parity gates and with explicit duplicate/alias/
        numeric policies.
  - [ ] **UPR-6c backend parity.** Prefer the one portable `.ssc` implementation compiled by the
        normal v2/JS path. Host plugins are adapters, not a second parser. If an intrinsic is needed,
        use plugin modules and `TargetedIntrinsicProvider`/runtime preambles; never hard-code a new
        intrinsic in interpreter core or JS/JVM generators. Production closure requires
        interpreter, v2 native/ASM, JVM and JS/Node behavior to agree.
  - [ ] **UPR-6d examples/gates.** Add runnable `examples/uniml-{json,yaml,markdown}.ssc` plus a hybrid
        example, std-interface checks, assembled-jar tests and affected conformance across all
        supported backends.

- [ ] **UPR-7 — finish `scljet-address-uniml` U2/U3 without lying about physical units.**
  - [ ] **UPR-7a resolver correctness.** Before exposure, use RFC 6901 path escaping, decode JSON
        string keys correctly, reject Error **and Fatal**, return source slices for composites,
        define code-point/UTF-8 units explicitly, and test duplicate keys, arrays, escaped keys,
        emoji/multibyte input and invalid documents against an independent JSON oracle.
  - [ ] **UPR-7b bridges.** Add separate v1 interpreter and v2 native plugin packages around the
        shared resolver, ServiceLoader/build/installBin/standard-JAR wiring, and a pure
        `DocAddressedValue` ABI. New intrinsics live in plugins only.
  - [ ] **UPR-7c `.ssc` surface.** Add `addressReadDoc(text,path)` to `scljet/address.ssc`,
        conformance and an external differential that compares logical value first and then verifies
        the exact reported source slice. Do not advertise legacy JS/JVM source-codegen support until
        the portable UPR-6 binding supplies it; final production closure requires that parity.

- [ ] **UPR-8 — P6.22 canonical self-hosted front closure (separate from the M5 adapter).**
  - [ ] **UPR-8a reconcile authority.** Option A holds **for v2 and is superseded for
        ScalaScript 3**: Sergiy decided on 2026-08-01 that in ScalaScript 3 UniML is the front
        end — the parser AND the AST. See [`specs/uniml-ssc3-frontend.md`](../specs/uniml-ssc3-frontend.md)
        for what that costs and what must be measured first. For v2, self-hosted `F` remains the
        canonical product front and the Scala UniML adapter remains an oracle/tooling artifact. Record fresh compare-first corpus counts because the historical
        205 MATCH / 315 delegated snapshot may move under parallel v2 work.
  - [ ] **UPR-8b eliminate breadth gaps.** Grow `specs/v2.2-p6.5-fsub.ssc` in measured slices until
        raw single-file and multi-file corpus reports DIFF=HOLE=EMPTY=TIMEOUT=0. Every slice reruns
        `--self`, semantic, multi-file, v2 conformance, and exact byte diffs; no fallback may be
        counted as a match.
  - [ ] **UPR-8c production proof.** Add tools-fat-jar CI for spike differential, C_min/X1
        stage1==stage2 and capstone. An assembled e2e run must prove the no-delegate marker and fail
        if legacy is invoked.
  - [ ] **UPR-8d retire legacy only after zero.** Remove the F4a delegate and
        `ssc1-front.ssc0`/`ssc1-lower.ssc0` only after all zero-fallback gates pass; then rerun full
        tests/conformance/examples and both literal fixed points. The user authorization permits
        this decision, not premature deletion.

- [ ] **UPR-9 — production release, packaging, CI and durable closeout.**
  - [ ] Publishable versioned artifacts for core + JSON/XML/YAML/Markdown/ScalaScript/tooling, no
        test-scope production dependencies, generated-data provenance/checksums, API docs and
        migration/security/limits documentation.
  - [ ] Root CI and standalone `cd uniml && sbt test` cover all JVM/Scala.js projects; corpus,
        portable-subset, hybrid, M6 property, std/backend, address, fixpoint and assembled-jar gates
        run fail-closed with bounded shards/timeouts.
  - [ ] Run affected conformance after every slice and full conformance before release. Record the
        strongest available CI evidence level exactly; `cancelled` remains red.
  - [ ] Update `BACKLOG.md`, `CHANGELOG.md`, `MILESTONES.md`, README and all behavior checkboxes only
        from measured results. Release the claim and remove every worktree/build daemon only when no
        required row above remains.

### Required local gate matrix

```text
scripts/sbtc ";uniml/test;unimlJs/test;unimlJson/test;unimlJsonJs/test;unimlXml/test;unimlXmlJs/test"
scripts/sbtc ";unimlYaml/test;unimlYamlJs/test;unimlMarkdown/test;unimlMarkdownJs/test;unimlMarkdownBridge/test"
scripts/sbtc ";unimlScala/test;unimlScalaJs/test;unimlTooling/test;unimlToolingJs/test"
(cd uniml && sbt test)
uniml/lint-portable-subset.sh
tests/conformance/run.sh --only 'json*,yaml*,markdown*,content*,uniml*,scljet-address*'
./v2/conformance/check.sh
git diff --check
```

Each implementation slice adds its narrower red/green command and exact observed counts here before
it is checked off. The final matrix is additive; it does not replace external corpus, self-host,
assembled-jar, or full-suite evidence.

---

## uniml-portable — dual-compile UniML dialects on v2 (2026-07-14, Sergiy: "продолжай, записывай в спринт, пуш")

Goal: the SAME UniML source compiles on scalac AND self-hosted ssc v2, so v2's parser can be written
on UniML. Method: flatten a dialect's parse path (`scripts` in scratchpad `flatten-*.py`) → run via
`v2/ssc1` → root-cause each blocker FROM THE IR → fix (v2 additive, or portable UniML rewrite) →
verify by probe + JVM dialect test + conformance 640ok. Detail: memory `project_uniml_portable_program`.

### JSON — DONE
- [x] **json-on-v2 COMPLETE** — parses `{…}` to `roots=1 status=Complete 0 diags`; invalid → diags.
  unimlJson 16/16 (ujson diff). 10 fixes: leading `final`/`private`; case-class+`def` trailing comma;
  `extends`/`with`/`derives` continuation; continuation-header body capture (`declHead`); all-named
  ctor default-fill; sibling method dispatch; `\r`+`\uXXXX` escapes; lexer `char.toString`→substring.

### YAML — 8 fixes landed (87b88f393/45120d9b3/4ec3d0c54), NOT YET running end-to-end
- [x] type decls nested in object bodies (accessors + enum cases); `startsWith(prefix,offset)`;
  `||=`/`&&=`; lexicographic Tuple2/3 Ordering (sortBy tuple key); UniML rewrites (val-destructure,
  `+:`, `Option.when`, tuple-destructuring lambda). unimlYaml 18/18.
- [x] **v2-vector-cons-list DONE** — `Vector(a,b,…)`/`Seq(…)` were `_arr_fill` mutable ArrayBuffers
  (no `.head`/`.tail`; `.map` returned another ArrayBuffer) → `Vector(1,2,3).map(f).head` = ".head on
  <foreign>". Now Cons-lists (indexed access `v(i)` + all list ops). Fixed the map-placeholder-copy.
- [x] **v2-untyped-field-access DONE** — `__method__(field, DataV)` resolves a by-name field via the
  registry (mirrors `__methodOrExt__`); `localVar.method().field` was Stub'd.
- [x] **v2-eager-global-regfields-order DONE** — a parameterless `def run = …` doing UNTYPED
  case-class field access was an eager global evaluated BEFORE the entry's `__regfields__` → registry
  empty → Stub. Fixed: `compileWithGlobals` now runs a pass-0 that registers all `__regfields__` from
  the entry before evaluating value defs. Repro `p-varlast.ssc` now `kind=seq`. (Did NOT fix the YAML
  kind-on-string — that receiver is a StrV, unrelated.)
- [x] **v2-yaml-kind-on-string ROOT-CAUSED + fixed** — was NOT a `.kind` bug per se: an all-named
  enum-case construction that omits a LEADING default (`Reframe(open=…, closeAfter=…)` omitting
  `closeBefore`) places the named values POSITIONALLY without reorder/fill (v2 gap: enum-case
  `lookupFields` is empty, so `resolveCtorArgs` can't reorder). `open` then held the `closeAfter`
  kind-strings → `spec.kind` on a string. Fixed UniML-side: pass `closeBefore = Vector.empty`
  explicitly, in field order (YamlStructure). Plus `.lexeme.head`→`.charAt(0)` (no `String.head` on v2)
  and `val (open,start)=…`→`._1`/`._2` (no tuple-pattern val). **YAML BLOCK-STYLE NOW COMPLETE on v2**
  (scalar/map/seq/nested → roots=1 Complete 0 diags; unimlYaml 18/18).
- [x] **v2-enum-named-nonleading-default FIXED** — the real cause was NOT missing field order (enum
  cases ARE in caseFieldOrderCell): the `.method` call site (ssc1-lower ~2013) `stripNargs(rargs)`
  BEFORE calling resolveMethodCall, so the enum-case construction lost its labels before it could
  reorder. Fix: keep the named-arg labels for the enum-case path so resolveMethodCall routes them
  through resolveCtorArgs (reorder + fill); every other method still strips/positional. Verified all
  variants (`p-enumvar.ssc`). The YAML/Markdown explicit-field-order Reframe workarounds can now be
  reverted (left in place; harmless — the v2 fix is additive).
- [x] **v2-yaml-flow-err FIXED — YAML NOW FULLY COMPLETE (block + flow) on v2.** The flow `_err` was
  `case "]" | "}" if stack.nonEmpty =>` — v2 does not support a GUARD on an ALTERNATION pattern
  (`A | B if …`). Fixed UniML-side: move the guard into the body. Flow-seq `[1,2,3]` / flow-map
  `{a:1}` → roots=1 Complete 0 diags; invalid `[1,2` → Incomplete+diag. unimlYaml 18/18.
- [x] **v2-alternation-pattern-guard FIXED** — `case A | B if g =>` AND `case LIT if g =>` now work.
  Three fixes: guardablePat accepts lpat/apat; expandAltArms distributes the guard over alternatives;
  lowerOrderedGuardArms tests a literal base AND the guard (the `gpat(lpat)` else applied only the
  guard — a pre-existing bug). Verified `p-altguard.ssc` / `p-litguard.ssc`.
- [x] **v2-plain-class-instance-method — MISDIAGNOSIS** — basic plain-class methods work
  (`Counter(5).add(3)`=8); MarkdownBlocks Stub was the nested-Container cascade (fixed by hoist).
- [x] **v2-object-qualified-nested-ctor FIXED** (8426d606c) — `O.Inner(…)`/`O.Origin`/`O.Dir.Case` now
  resolve (objectNestedTypes registry → unqualified hoisted global); also fixed a parser boundary bug
  where a case class as an object's LAST member swallowed the following top-level `def` (skipExt stops at `}`).

### Markdown — FULL v2==JVM parity (block + inline) (2026-07-14)
- [x] **markdown at parity** — heading/para/list/blockquote/code-fence/table/thematic-break/emphasis
  AND the full inline layer (code spans, links, images, autolinks, raw HTML) → v2==JVM byte-identical
  (deep tree digest, 48 rows / 25-input corpus). Earlier "COMPLETE" was point-example only; the
  differential (below) found 8 real bugs it missed — all fixed, conformance 640 ok.
- [x] **differential harness** (scratchpad `gen_diff.py`/`run_diff.sh`) — concatenate the LIVE core+
  dialect Scala into ONE dual-compilable program (strip package/import/access-mods, drop `*Projection.
  project`, keep local companion imports), append a corpus loop emitting `IDX|status|roots|diags|<full
  recursive tree serialization: branch kinds + edge roles + token kinds+lexemes>`, run on scalac (ref)
  AND ssc1, diff. JSON 25/25 and YAML 42/42 rows byte-identical. Found 6 v2/portability bugs +1 gap:
  - [x] `char.toString`→decimal code (no Char box): 10 markdown sites → `substring` slice (cba4c4a41)
  - [x] `String.forall/exists` passed 1-char StrV → `_ == 'x'` always false; now IntV (0f06d4a64)
  - [x] `Vector.updated/.patch` unimplemented on Cons-list → added
  - [x] line-leading `!` glued as infix actor-send → `unbound t`; isCont excludes `!`
  - [x] capitalized object-member val (`VerticalTab`) unbound internally; uid case checks owner members
  - [x] `+:` unlexed → `_err`; lexed as `::` (identical in Cons-list model)
  - [x] non-local **`return`** (56f7cd170) — `return` inside a `while` was a no-op (lowerer's early-return
    couldn't unwind a loop). Now `ReturnThrow` + prims `__throw_return__`/`__with_return__`; every `return e`
    throws; a named-def body is wrapped ONLY if it contains a return (cell-detected, save/restored around
    nested defs) so TCO on return-free defs is untouched (all tco/mutual-tco/wasm-tco tests still green); a
    `return` in a lambda propagates to the enclosing def. Covers top-level/object/local-nested/class-method.
  - [x] `String.contains(Char)` unimplemented (56f7cd170)
  - [x] **`obj.method` eta-expansion GENERAL FIX (691334d4e)** — v2 now eta-expands a method selected on a
    value (`list.exists(lc.contains)`) at the __method__ zero-arg fallthrough → `x => recv.name(x)` (native
    1-arg ClosV re-dispatching via methodOp; untyped-safe — a real field/nullary method matches earlier).
    Reverted both markdown shims to idiomatic method refs (dd1a57c7a); differential still 48/48. conformance 640ok.

### uniml-portable follow-ups (Sergiy 2026-07-14: "занеси у спринт і зроби, спочатку 1 потім 2")
- [x] **1. Full CommonMark/GFM corpus differential (HARDENING) DONE** — fetched the REAL specs
  (spec.commonmark.org 0.31.2 = 652 examples; cmark-gfm test/spec.txt under the Gfm profile). Ran ALL
  through the v2-vs-JVM differential (batched ~130/run, deep tree digest). **Every single example parses
  BYTE-IDENTICAL v2==JVM** (CommonMark: 334+386+339+145+282 rows; GFM: 385+272+277+145+280 rows). Found +
  fixed ONE v2 gap: `String.indexWhere` / `String.count` (Char-predicate closures) unimplemented — hit by
  reference-definition parsing (740b3fe24). Harness: scratchpad `cmark_corpus.py`/`build_batch.py`/
  `harden_rest.sh`/`harden_gfm.sh` + `gen_diff.py` (now takes a `parse_extra` for the profile arg).
- [x] **2. Port XML to portable + v2 parity DONE (566df747f)** — the XML PARSE path already used immutable
  Vector (only the Markup/validate layer had ArrayBuffer/HashSet, and it's stripped). Made it dual-compile
  with SOURCE-only portable rewrites (no v2 change; JVM unimlXml/test 13/13): 3 regex `.matches` (XML-decl +
  entity-ref) → hand predicates; `0.toChar`→`' '`; attr-dup `Set[String]`→`Vector` (no `Set.empty` on v2;
  dedup redundant); `quote.toString`→substring (no Char box); `0x10FFFFL`→`0x10FFFF` (v2 no hex+L suffix).
  Differential over 31 XML inputs → **byte-identical v2==JVM**. 4th dialect at parity. Harness
  `scratchpad/gen_xml_diff.py` (strips the Markup layer).
- [x] **`Set.empty` + hex-`L` general v2 fixes DONE (1cd903b5d)** — the two gaps XML surfaced, fixed
  generally (conformance 640ok; markdown differential still 48/48). (1) hex `0x…L`: number lexer now strips
  the `L`/`l` after a hex literal (was only after decimal). (2) `Set.empty` → emit `[].toSet` directly (Set
  isn't a registered ctor so the companion path yielded a closure → `Op("Set.empty")`); and the `++`-on-list
  runtime case adds a NON-list RHS as a distinct element (`set + "str"` lowers to `++` via the string
  heuristic), so `Set.empty + a + b` builds a real set (with the existing `-`/`+`). Verified vs JVM.

### Markdown — NOT STARTED (obsolete note below)
- [ ] **markdown-on-v2** — flatten `markdown/` parse path (nested enums InlinePiece/AngleKind/OpenLeaf
  — nested-enum support already landed) → run → sweep the same construct gaps → `status=Complete`.

## uniml-portable — dual-compilable standalone UniML library (Scala 3 ∩ ScalaScript v2) (2026-07-13, Sergiy)

**Vision.** UniML is a **standalone library, independent of ScalaScript**. Its single Scala 3
source must compile **both** with standard scalac **and** with the self-hosted ScalaScript v2
compiler — so it is version-neutral (identical for v1 and v2) and can eventually host the v2.2
parser. Design decisions already agreed with Sergiy:
- UniML uses its **own minimal compat layer** (mutable `Buffer`/`Map`/`Set`/`StrBuilder`, int↔hex
  parse, and a **compact portable Unicode table**) written in the Scala3∩v2 subset — **no JDK**
  (`Character.*`, `Integer.*`, `java.*`) and **no `scala.collection.mutable`**. v2 supports
  `var`/`while`/mutable, so the imperative style survives.
- **`.ssc`/v2 must be Scala-3-compatible** (burden on v2, not on UniML). Mechanism **(b)**: one
  source text, two extensions — `.scala` for scalac, `.ssc` for v2 (symlink/generation).
- The target Scala-3 subset is **defined by what UniML uses** (UniML = living spec for v2); we
  deliberately avoid heavy features (implicits/HKT/macros/match-types).
- Bindings (`uniml-xml→Markup`, `uniml-markdown-bridge→DocumentContent`) stay ScalaScript-side,
  **out of the portable core** (they depend on v1 models).
- Verify: scalac build + a v2-compile smoke + a small set of **dual-compilable behavioral tests**
  ("compiles under both" ≠ "behaves under both").

Lead: opus-continue; phases are open to other agents (esp. the v2-side track uniml-portable-3).

**✅ DECISION (Sergiy, 2026-07-13): the UniML side is an IMMUTABLE REWRITE (primary); v2 fixes shrink
to whatever remains.** Phase 0.5 deep-probe found v2's object model is immutable: only `case
class`(constructor params) + methods — no mutable object fields, no plain `class`, no anonymous
instances, no arrays; only local `var`/`while` in `def` bodies. Sergiy: UniML being pervasively
imperative-stateful (mutable lexer/VM fields, buffers) is **bad design** — rewrite it to something
immutable. This is a win-win: an immutable UniML is cleaner **and** fits v2's model, so the v2-side
fix list mostly evaporates. Key insight: **eliminate mutable OBJECT STATE (fields) and mutable
collections; local `var`/`while` of immutable values inside functions stays (v2 supports it)** — so
this is "immutable interfaces + immutable data with a local imperative shell", not "no `var` at all".
After the rewrite, **re-measure the v2 gap** — likely only multi-file `package`/`import` (+ maybe an
immutable `Map` primitive) remains. Design is being worked out with Sergiy. See
`specs/uniml-portable-gapmap.md` § "The wall".

- [x] **uniml-portable-0-move** — ✓ DONE 2026-07-13. Moved core/json/yaml/markdown to top-level
      `uniml/` with its own sbt build (`uniml/build.sbt` + `uniml/project/`). `cd uniml && sbt test` =
      all 8 suites green (4 modules × JVM+JS), zero ScalaScript dependency. Root `build.sbt` updated
      (4 `.in(file(...))` paths only); v1 bindings `uniml-xml` (13/13) + `uniml-markdown-bridge`
      (11/11) unchanged and green. History preserved (git renames). Follow-up (at true extraction):
      collapse the dual build to `publishLocal`.
- [x] **uniml-portable-0.5-gapmap** — ✓ DONE 2026-07-13. `specs/uniml-portable-gapmap.md` + a red
      v2-compile smoke `uniml/v2-smoke/` (run.sh via `v2/ssc1`). Finding: v2's `.ssc` frontend
      **already** compiles enums/ADT+nested match, generic defs, generic case classes, `var`/`while`,
      traits + generic-trait `[I,O]` dispatch, and string ops (`.length/.charAt/.substring/toString`)
      — most of UniML's surface. **Two blocking gaps:** (1) `new Array[T](n)` + indexed apply/update
      is broken (IndexOutOfBounds) — the compat-layer floor; (2) anonymous `new Trait[..]:` →
      `unbound global: _err`. Untested: variance `[+A]`, multi-file `package`/`import`.
- [x] **uniml-portable-1-immutable** — ✓ DONE 2026-07-13 (interfaces + VM + driver + dialect
      wrappers). [UniML-side, PRIMARY] Eliminated the mutable-object-state design at the streaming
      boundary. `Processor` is now the pure fold `trait Processor[S, I, O]: start / step(state,input):
      Stepped[S,O] / stop(state): ProcessBatch[O]` — the `step(state, chunk)` fold Sergiy asked us to
      keep for genuine incrementality — replacing the old `push`/`finish` + mutable `finished` flag.
      `TreeVm` is a pure fold over an immutable `VmState` (frame stack as `Vector`, counters, roots,
      diagnostics; a local `var`/`while` shell inside `step`/`stop` over immutable values, no object
      fields). `UniML.parse` threads the dialect processor over chunks then folds tokens through the
      VM — no shared mutable state. All 5 dialect processors (literal/json/yaml/markdown/xml) are pure
      case classes `Processor[String, SourceChunk, VmToken]` that buffer the source in `step` and lex
      once in `stop`. Behaviour-preserving: green on scalac across JVM+JS (core 15, json 16, yaml 18,
      md 32) and the root bindings (unimlXml 13, unimlMarkdownBridge 11). Net −80 LOC. The
      after-`finish` "reject reuse" / `uniml.*.finished` diagnostic is gone (a pure `stop` is
      idempotent). Remaining internal-lexer mutability carved out → `uniml-portable-1d-lexers`.
- [x] **uniml-portable-1d-lexers** — ✓ DONE 2026-07-13. [UniML-side] internal lexers drop mutable
      **object fields** (v2's object-model wall): rewrite each as a pure function returning a token
      `Vector` with a local `var`/`while` shell + immutable `Vector` accumulation (mutating helpers =
      nested defs over the locals, pure classifiers top-level). Behaviour-preserving; all tests green
      throughout. All four dialects done 2026-07-13:
      - [x] `JsonLexer` → pure `scan(source,text,limits): JsonLexResult` (was a class with 10+ mutable
            fields + 2 ArrayBuffers + push/finish/drain). unimlJson 16/16 JVM+JS.
      - [x] `YamlLexer` → inlined its inner mutable `Scanner` class (8 fields + 2 `Vector.newBuilder`)
            into `scan`. unimlYaml 18/17 JVM+JS.
      - [x] `XmlScanner` → pure `scan` (3 ArrayBuffers + counters + local mutable HashSet → immutable
            `Vector` element-stack + immutable `Set` attr-dedup). unimlXml 13/13 JVM+JS. (XmlProjection
            namespace code left as-is — it's the ScalaScript-side →Markup binding, not the lexer.)
      - [x] **markdown** — the hard remainder (~1500 LOC, delicate/losslessness-critical), done as its
            own batch. `TokenSink` (used only by `MarkdownBlocks`) folded into `MarkdownBlocks.parse`
            as local vars + nested defs; `MarkdownBlocks`' 8 mutable fields → locals (`containers`
            `Vector` stack, `refs` immutable `Map`, `diagnostics`/`paragraphSegs` `Vector`s); dead
            `ListFrame.lastBlank` dropped; pure classifiers kept as class methods.
            `MarkdownInlines.WDelim` → immutable `case class`, delimiter algorithm rewritten to rebuild
            the node `Vector` by index (reduced opener/closer are fresh copies) instead of in-place
            mutate/remove/insert; `tokenize`/`processEmphasis` return/thread `Vector` not `ArrayBuffer`.
            Green md 32/32 JVM+JS + bridge 11/11. (Remaining local `StringBuilder`/`Vector.newBuilder`
            + `MarkdownProjection`'s local `mutable` collections are not object fields → 1c-compat.)
- [~] **uniml-portable-1c-compat** — make the DIALECTS v2-construct-free. Gaps fully probed 2026-07-13
      (see gapmap "Dialect gaps" table): `StringBuilder` (unbound → `Vector[String]`+`.mkString`),
      `ArrayBuffer` (unbound → `Vector`), plain `class` (crash → immutable rewrite), regex `.r`
      (no-dispatch → hand-rolled predicates), `Character.getType`/`isSpaceChar`/`digit` (unresolved Op
      → **portable Unicode table**, the hard one), mutable case-class `var` field (→ copy-on-transition).
      - [x] **JSON**: JsonLexer `StringBuilder`→`Vector[String]`; JsonStructure `ArrayBuffer`→immutable
            `Vector` + `Frame.state` immutable (copy-on-transition), nested-def pattern. Green
            unimlJson 16/16 JVM+JS. Uses only v2-probed constructs. Core+JSON now v2-construct-free.
      - [x] **YAML**: parse-path structure DONE (YamlStructure immutable; +v2 `.indices`). Optional layers
            DONE: `YamlProjection` mutable→immutable Vector/Map/Set; `YamlSemanticParser` plain classes
            (Parser/FlowParser)→immutable nested-def shell + 7 regexes→exact hand-rolled predicates +
            `Character.digit`→hexDigit + `.isWhitespace`→portable `isWs`. Green unimlYaml (incl.
            YamlCoreDifferentialSpec vs snakeyaml).
      - [x] **Markdown**: parse path DONE (StringBuilder→`Vector[String]`; `MdChars` Character→portable
            BMP table generated from Character.getType + JVM `MdCharsParitySpec` proving exact
            0x0–0xFFFF equivalence). Optional `MarkdownProjection` DONE (mutable→immutable;
            `Character.toChars`→portable surrogate encoder). Green md 34/32 + bridge 11.
      - [x] **All optional projection layers** (Json/Yaml/Markdown Projection) mutable→immutable — 0 gap
            markers across all dialect files. UniML side is fully construct-clean.
      - [~] Gold-standard: ran the ACTUAL JSON dialect flattened→one `.ssc` on v2 — UniML constructs all
            pass, but the v2 `.ssc` FRONTEND can't parse the full module (modifiers, nested types in
            objects, first-class object values, `Set`/`Map` companions). See gapmap "Gold-standard
            finding" — the precise v2.2-frontend handoff. Multi-file `package`/`import` still to probe.
- [~] **uniml-portable-1b-namedclasses** — SUPERSEDED by `uniml-portable-1-immutable` (the immutable
      rewrite removes the anonymous `Processor` instances entirely, along with all mutable fields).
- [ ] **uniml-portable-v2-objectmodel** — [v2-side] RE-MEASURE after the immutable rewrite; the list
      shrinks. Likely remaining: **multi-file `package`/`import`** (UniML is multi-file) and maybe an
      immutable `Map` primitive. **Anonymous trait instances** stay a nice-to-have for v2 (Sergiy:
      "анонимные трейты хорошо бы сделать в scalascript") but are no longer required by UniML.
- [~] **uniml-portable-2-subset** — RUNTIME-subset lint DONE: `uniml/lint-portable-subset.sh` scans
      core+dialects for mutable collections / regex / `java.lang.Character` / `StringBuilder` /
      `ArrayBuffer` / `newBuilder` / Char-Unicode methods (`.isLetter` etc.) / `new Array` and fails on
      any. It immediately caught 2 misses (core `Tree.sourceTokens` `Vector.newBuilder`; `JsonLexer`
      `.isLetter`) — both fixed (→ `Vector` accumulation; ASCII-letter). Now passes; guards regressions.
      Deliberately does NOT flag frontend-only-blocked idiomatic Scala (companion `val`s, first-class
      objects, nested types, modifiers, `Set.empty`) — those are v2.2-frontend, not UniML violations.
      REMAINING: mechanism (b) `.scala`↔`.ssc` generation/mirror (deferred — blocked on the v2 frontend
      gaps in the gapmap handoff; the generator would strip modifiers + hoist, but companion-`val` /
      object-value / `Set.empty` need frontend support first).
- [~] **uniml-portable-3-v2compile** — [v2-side] drive v2 to compile UniML, module by module. Re-probed
      the *immutable* core (Phase 1/1d) against v2 2026-07-13 — the Phase-0.5 asks are now MOOT for
      UniML (no `new Array`, no anon-trait, no mutable fields after the rewrite). Findings + status:
      - [x] **`Vector`/`List` `.dropRight`/`.takeRight`** — was the ONE real core blocker (the immutable
            stack-pop idiom `xs.dropRight(1)` used in TreeVm/XmlScanner/MarkdownBlocks). v2 crashed
            `no dispatch for .dropRight on <foreign>`. FIXED v2-side: 2 additive cases in the `isList`
            block of `v2/src/Runtime.scala` (mirror `drop`/`take`). Full core probe now runs on v2
            (`uniml/v2-smoke/core-blocks.ssc` PASS). v2 already supports the rest of the core's surface
            (generic 3-param trait, enum+match, `.copy`, `Option.forall`, Vector `:+`/`.last`/`.length`).
      - [ ] **DIALECTS** (not core): plain `class` (Yaml Parser/FlowParser/BlockFrame), regex `.matches`
            (YAML scalar typing), `java.lang.Character.getType`/`isSpaceChar`/`digit` (Markdown flanking)
            → these are the `uniml-portable-1c-compat` scope. Multi-file `package`/`import` still to
            probe (UniML is multi-file). See the gapmap's 2026-07-13 UPDATE.
- [ ] **uniml-portable-4-parity** — a small set of **dual-compilable behavioral tests** (in the
      subset) run under both scalac and v2, proving v2-compiled UniML behaves identically
      (lossless/chunk-invariance agree on a handful of cases).
- [ ] **uniml-portable-5-binding** — formalize the binding module(s): →Markup, →DocumentContent,
      and expose dialects as `std.json`/`std.yaml`/`std.markdown`; ScalaScript starts reading
      data/documents via UniML instead of commonmark-java / ad-hoc readers.
- **uniml-portable-6-language / ssc v2.2** — self-hosted Scala-3 subset dialect on UniML, the
      endgame ("v2.2 parser on UniML"). Full design: **`specs/v2.2-self-hosted-dialect.md`**
      (2026-07-13, co-led Sergiy). Triple invariant (impl-lang = object-lang = subset; Scala3 ⊇
      subset = oracle+seed). 4 decisions settled: typed holes (total pipeline), design-for-injection
      ship-composition-first, user-dialects deferred, resync-at-structural-boundary + only-Scala-
      subset-executable. Spike-first sub-phases:

  > **v2.2 STATUS — what "done" means here (single source, 2026-07-17).** The arc's THESIS is proven:
  > ScalaScript compiles itself. Both literal fixed points hold, re-verified by the coordinator from
  > clean builds on 2026-07-17:
  > - **P6.6 `C_min`** (a minimal compiler for subset L, written in L) — `stage1 == stage2` byte-identical,
  >   32,824 B, exit 0. The concept: no quine, reads its own source from a file.
  > - **P6.5 `X1` `F`** (a richer subset compiler, Core IR byte-identical to `ssc1-front`) — `stage1 == stage2`,
  >   79,667 B, 89 ok / 0 FAIL.
  >
  > **v2.2 is NOT "finished" as a language milestone, and the gap is deliberate, not hidden.** "Compiles
  > itself" is true of the SUBSET `F` is written in, not of all ScalaScript. That's why **P6.5 stays `[~]`,
  > by design** (see its HONEST BOUNDARY note) — the remaining work is *bounded mechanical breadth* with no
  > capability/design question left: given/summon, enums, extensions, for-comprehensions, `var`/`while`,
  > interpolation, prelude selectors, the List-var registry (case classes landed 2026-07-16 as X1h). Each
  > is corpus growth re-proving `--self` on every slice.
  >
  > **Also open under the arc:** P6.2/P6.2c/P6.3 (spike dialect breadth), P6.20 nested-cons `a::b::t` in the
  > *spike/subset* (works on v2-native, unverified in the subset — do not infer), P6.21 CI protection —
  > **which is currently one of the 5 red suites in `Test via sbt`** (`C_min … projects cleanly through the
  > spike`), so the self-host guard is red while the self-host itself is green; see §`ci-last-red`.
  >
  > One-line answer to "is v2.2 done?": **the hard part (self-compilation) is proven; the breadth to cover
  > the whole language, and to make CI defend it, is not.**

  - [x] **P6.0 spike (GATE) ✓ Landed 2026-07-13 (e510e53ab)** — GREEN. Verdict
        (`specs/v2.2-p6.0-spike-notes.md`): precedence IS expressible via UniML — Pratt-parse INSIDE
        the dialect, then serialise the tree with "open-on-first-token / `Reframe.closeAfter`-on-last"
        (Reframe handles multi-open/close), source-order + lossless. NO separate parser layer; one
        CST. Proven: 6/6 CST-shape tests; 4/4 end-to-end projection → UNCHANGED `ssc1-lower` → run-ir
        with BYTE-IDENTICAL Core IR vs `ssc1-front`; scalac dual-compile agrees (7 5 9 9). Dialect:
        `uniml/core/src/test/.../spike/ScalaSpike.scala`. Trivia-losslessness + error nodes + full
        grammar deferred to P6.1/P6.2 (do not block).
  - [x] **P6.1 error model ✓ Landed 2026-07-13** — GREEN (11/11 spike tests + e2e). Total parser
        (EOF-safe, never throws), `Diagnostic`s via `ProcessBatch` → `ParseResult.diagnostics`
        (status Incomplete), resync to next `def` boundary, `spike.error` CST frames for junk, total
        projection → `__notImplemented__` holes. Proven: containment — `def broken = 1 +` ⧺ `def main
        = 2*3` compiles (broken body = hole) and `run-ir main` = 6; happy path still byte-identical
        Core IR vs `ssc1-front`. Notes: `specs/v2.2-p6.0-spike-notes.md` §P6.1. Deferred (non-block):
        trivia losslessness, typed-holes-proper (needs v2.2 typer), intra-def statement resync.
  - [~] **P6.2 grow the dialect** to full front coverage (layout/precedence/given-using/patterns/
        for-match/decls), differential-tested vs `ssc1-front`, until it replaces it. Sliced:
    - [x] **P6.2a full infix table ✓ Landed 2026-07-13** — greedy operator-run lexer + exact
          `ssc1-front` `opPrec` (left-assoc) via one generic `spike.infix` frame. 16/16 tests; the
          operator corpus (prec/assoc/shift/cmp/bool/bit) is **Core IR byte-identical to ssc1-front**.
          Notes: `specs/v2.2-p6.0-spike-notes.md` §P6.2.
    - [x] **P6.2b offside layout ✓ Landed 2026-07-13** — indented def body → `spike.block` of
          `val` bindings + final expr; block structure computed from token COLUMNS in the RD parser
          (no synthetic tokens → lossless CST), leading-operator continuation lines glued (matches
          ssc1-front `isCont`). Projection `Pair("block",[mkVal…,mkSExpr])` → unchanged `lowerBlock`
          → nested `IrLet`s. 20/20 tests; block-vals/single/cont are **Core IR byte-identical to
          ssc1-front**. Notes §P6.2b. Deferred: nested/if-layout blocks, `var`, full continuation matrix.
    - [~] **P6.2c match + patterns** — [x] `match` + literal/`_`/var + guard (offside & braced) ✓ and
          [x] ctor/tuple patterns + `uid` lexing + tuple literals ✓ **Landed 2026-07-13**, all Core IR
          byte-identical to ssc1-front (match-lit→42/var→107/guard→16/braced→30; ctor-some→5/none→42/
          cons→3; tuple-pat `(4,5)`→9). Notes §P6.2c.
    - [x] **P6.2d case class + field access ✓ Landed 2026-07-13** — `case class Name(f: T,…)` decl +
          `.field` access (postfix `spike.sel`). Projection emits `mkCaseCls`/`mkSel`; `lowerProg`
          generates ctor/Mirror/`_sel_` accessors/`__regfields__` from the `casecls` node. 26/26 tests;
          cc-field/cc-arith/cc-match Core IR byte-identical to ssc1-front (run-ir needs plugin VM —
          bare VM lacks `__regfields__`, identical on both sides). Notes §P6.2d. Deferred: derives/
          extends/type-params/methods; enum/trait/object/type.
    - [x] **P6.2e given + summon ✓ Landed 2026-07-13** — `given name: T = e` + `summon[T]` (typeclass
          resolution core). Projection emits `("given",…)`/`("summon",T)`; lowerProg's resolve pass
          does the dict-passing (buildGivenTable/findGiven) — no hidden cells. 27/27 tests;
          given-summon→42, given-summon2→8, **Core IR byte-identical to ssc1-front AND runs on bare
          VM**. Notes §P6.2e. Tractability probe verdict: typeclass family is NOT (C) — resolution is
          in the lower's resolve pass over AST-derived tables. Deferred: trait+`with`+context-bound
          dispatch loop (finicky even in ssc1-front), named `using` + `tcExtendsCell` (hidden cells).
    - [x] **P6.2f–i ✓ Landed 2026-07-13** — enum (offside/braced + comma-nullary), extension methods
          + parameterless defs, alternative/bind patterns (apat/bpat), `::`(right-assoc)/`->`. All Core
          IR byte-identical to ssc1-front (enum-nullary/params, ext-method→10, pat-alt→100/pat-bind→9,
          op-cons→1/op-arrow→3). Notes §P6.2f–i. **P6.2 core COMPLETE**: 9 grammatical families spanning
          the whole ssc1-front (2899 lines), 34-toy corpus all byte-identical — architecture validated
          end-to-end.
    - [x] **P6.2j–k ✓ Landed 2026-07-13** — prefix ops (-e/!e/~e), to/until ranges, typed patterns
          (p: T), type parameters (plain [A] on def/case-class/enum, erased). All Core IR byte-identical
          (op-prefix→-2, op-range→7, pat-typed→0, tparam→99). **Corpus now 38 programs, all Core IR
          identical.** Notes §P6.2j–k + "honest boundary". **Remaining NOT byte-identically achievable:**
          (1) full context-bound dispatch loop — errors inside ssc1-front itself for minimal forms;
          (2) named `using` + `trait extends` — need the two hidden `#cell` channels (usingSigCell/
          tcExtendsCell), a different integration than pure AST-node projection. **P6.2 grow-the-dialect
          effectively COMPLETE** — everything the architecture can reach byte-identically is reached.
  - [~] **P6.3 injection + registry** — *hybrid composition ✓ Landed 2026-07-13* (035e120c1): new
        JVM-only module `unimlScala` + `SscCompose` composes Markdown+YAML+ScalaScript so a whole `.ssc`
        (front-matter + prose + fenced code) parses as ONE lossless UniML tree by injection (each dialect
        sees only its own bytes; foreign fences inert). 7/7 tests + harness: hybrid-basic→14, hybrid-cc,
        both Core IR byte-identical to ssc1-front on the extracted bare source; precedence survives the
        hybrid pipeline; extraction lossless. Notes §P6.3.
    - [x] **P6.3b registry hook ✓ Landed 2026-07-13** (6af752e85): fence/front-matter language resolved via
          `DialectRegistry`, not hardcoded. `SscCompose.builtins` = closed set (ScalaScript/Markdown/YAML/
          JSON); `registryWith(extra*)` extends but a built-in name can't be overridden (user-closed);
          unregistered language → inert. 10/10 tests (```json injected via registry; builtins resolve the
          4 langs; re-register built-in fails; fresh MermaidDialect drives injection). Notes §P6.3b.
    - [x] **P6.3c trailing-EOL tolerance ✓ Landed 2026-07-13** (970e56fc9): raw lossless fence body fed to
          the dialect (trailing EOL is spike.ws Trivia → skipped); scalaSource = clean accessor; test
          proves projection byte-identical w/ & w/o trailing \n/\n\n/\r\n. Notes §P6.3c.
    - [x] **P6.3d string literals ✓ Landed 2026-07-13** (1d691ae57): spike lexes "…" → spike.str with the
          decoded value (buildStr semantics: \n→NL, \t→TAB, \<c>→c) + raw triple-quote; projects mkStr via
          escStr (round-trips ssc0). str-plain→5, str-escape→6, Core IR byte-identical. Notes §P6.3d.
    - [x] **P6.3e string interpolation ✓ Landed 2026-07-14** (f223c584d): s / ${expr} / md byte-identical to
          ssc1-front. Detection = s/f/raw/md id before a str token → spike.interp. Projection mirrors
          interpParts/partsToExpr: literal→mkStr, $name→mkVar, ${expr}→RE-PARSED by the spike's own front
          (wrap-as-def, lift body), folded right-assoc into ++; md→Pair(prim,__mdStrip__). scanInterpEnd/
          scanNestedStr balance ${…}. 41 tests + harness: interp-var/interp-expr(inner x+1 re-parsed)/
          interp-md, all CoreIR≡ssc1-front. **Corpus now 43 programs, 0 fail.** Notes §P6.3e.
    - [x] **P6.3e+ f-interpolation ✓ Landed 2026-07-14** (7393c406f): f"…" printf specs → __fInterpolate__,
          byte-identical (fInterp mirrors buildFInterp/goFArgs/splitFFormatPrefix). interp-f→CoreIR≡ssc1-front.
    - [x] **P6.3 injection + registry COMPLETE** — hybrid composition + registry hook (closed/user-closed) +
          trailing-EOL tolerance + string literals + all four interpolators (s/f/raw/md). **44-program corpus,
          all Core IR byte-identical to ssc1-front, 0 fail.** Remaining edge cases (f-arg bare `_`, brace in
          a ${…} nested string) = future polish. Next roadmap phase: **P6.4 self-host proof.**
    - [x] **P6.4a grammar completeness ✓ Landed 2026-07-14** (e0bf9aa25): comments (//, /* */ → trivia),
          booleans (true/false→mkBool), floats (1.5→mkFloat; 1.field stays sel), lambdas (x=>e, (a,b)=>e→
          mkLam; paren form via Cur.mark/reset backtrack). bool→1/comment→3/float→0/lambda1→5/lambda2→7,
          all CoreIR≡ssc1-front. Notes §P6.4a.
    - [x] **P6.4b gold-standard scale test ✓ Landed 2026-07-14** (8a70bee5a): a 27-line module (enum+match+
          case class+given/summon+if/else-if+interpolation+lambdas+blocks+recursion) CAUGHT A REAL BUG no
          isolated toy hit — non-braced match arms greedily swallowed a following top-level `case class`.
          Fixed: match arms are offside-bounded (dedent or `case class` ends the match). scale-prog→CoreIR≡
          ssc1-front. **Corpus now 50 programs, 0 fail.** LESSON: whole-module scale tests surface
          interaction bugs the per-feature corpus can't. Notes §P6.4b.
    - [x] **P6.4c/d more scale tests + edge probes ✓ Landed 2026-07-14** (259bf166b/08759e349): 4 more
          whole-module scale programs (decl-boundaries, nested match+lambda, ${field} holes, recursion+HOF)
          all pass; 3 edge probes caught 3 MORE real gaps — offside if/else branches (branchExpr→parseBlock),
          function types A=>B in return/param position (skipTypeTail, also closes latent List[T] param gap),
          chained application f(a)(b) (postfix applyArgs). **Corpus now 57 programs, 0 fail.** Probe-and-fix
          loop keeps surfacing real gaps the prior corpus missed. Notes §P6.4c/d.
  - [x] **P6.4 self-host proof ✓ Landed 2026-07-14** (ceac60766): a real compiler written ENTIRELY in the
        subset — selfhost-arith (tokeniser+parser+stack-machine codegen+VM, `+ 3 * 4 5`→**23**) and
        selfhost-eval (let/variable interpreter with de Bruijn scoping+environment→**56**) — compiles to
        Core IR byte-identical to ssc1-front AND executes to the correct answer on the bare VM. The
        differential oracle IS the fixed-point analog: spike front and ssc1-front agree byte-for-byte on a
        compiler's source. Not literal spike-compiles-spike (needs spike front ported to subset, P6.5-adj);
        proves the prerequisite: subset hosts a compiler + spike compiles it faithfully. **Corpus 62
        programs, 0 fail.** Notes §P6.4.
    - [x] **P6.5-step block-in-arm gap + closures interpreter ✓ Landed 2026-07-14** (b456ae8f5): closed the
          block-body-in-match-arm gap (parseArm→branchExpr/parseBlock; parseBlock stops at case/}); added
          selfhost-closures — a higher-order interpreter with CLOSURES (lambda→closure capturing env,
          application extends it; (λf. f(f(3)))(λx. x*2)→**12**), byte-identical + runs. Three self-host
          artifacts now (compiler→23, scoped interp→56, closures→12). **Corpus 64 programs, 0 fail.**
    - [x] **P6.5-step string ops + source-text compiler ✓ Landed 2026-07-14** (03febbe42): probed string ops
          (length/==/+/substring/charAt) — ALL run on the VM byte-identically. selfhost-full = a COMPLETE
          compiler from source TEXT (lexer reads s.charAt/s.length → tokens → recursive-descent parse → AST
          → eval; compile("+ 1 * 2 3")→**7**), byte-identical + runs. A genuine front component consuming
          source text, in the subset. **Corpus 71 programs, 0 fail.** Notes §P6.5-step.
    - [x] **P6.5-step precedence parser ✓ Landed 2026-07-14** (06f45b8fe): selfhost-infix — a precedence-
          climbing parser with parentheses (SAME algorithm as the spike's parseExpr), reading source text:
          compile("2 * (1 + 3)")→**8** (precedence + grouping), byte-identical + runs. Notes §P6.5-step.
    - [x] **P6.5-step full pipeline (lower to IR text) ✓ Landed 2026-07-14** (940b8f172): string-return/
          building/int→string all run byte-identically; selfhost-compiler = the FULL pipeline in the subset
          (source text → lexer → tokens → parser → AST → LOWERER emitting CoreIR-like S-expr text):
          compile("+ 1 * 2 3")→**"(add (int 1) (mul (int 2) (int 3)))"**, byte-identical + runs. Seven self-
          host artifacts cover every compiler phase (lex/parse/eval/closures/lower). **Corpus 77 programs, 0
          fail.** Literal P6.5 now purely mechanical breadth — no capability gap remains. Notes §P6.5-step.
    - [x] **P6.5 two-stage self-compilation ✓ Landed 2026-07-14** (8fe916eb2+fc2d7d422): selfhost-emit — a
          compiler in the subset that emits REAL EXECUTABLE Core IR (arith + comparison + control flow:
          i.add/i.mul/i.sub/i.lt/if, wrapped in program/defs/entry). Harness verifies END-TO-END: stage 1 =
          spike compiles it BYTE-IDENTICAL to ssc1-front; stage 2 = the Core IR it EMITS runs to **8** (via a
          .emit file). Literal-self-host loop CLOSED + automated. Core IR target also runs functions+recursion
          (factorial(5)→120). Every compiler layer now proven in the subset (lex/parse/eval/closures/lower/
          emit-executable). **Corpus 78 programs, 0 fail.** Notes §P6.5.
    - [x] **P6.5 Turing-complete milestone ✓ Landed 2026-07-14** (c5831c285): selfhost-rec — a compiler in
          the subset for a language with FUNCTIONS + RECURSION + variables + control flow, emitting EXECUTABLE
          Core IR. Compiles a recursive factorial from source text ("? < x 1 1 * x @ - x 1") into (def f (lam
          1 …(app (global f)…))) + main→f(5); the emitted Core IR runs to **120**. Every compiler capability
          now proven in the subset (lex/precedence-parse/eval/closures/lower-text/lower-executable/functions+
          recursion+local-slots+global-refs+application). **Corpus 79 programs, 0 fail.**
  - [~] **P6.5 literal fixed point (follow-on, non-gate)** — the whole ScalaScript-subset compiler, written
        in the subset, compiling itself. **X1 CLOSED 2026-07-16: the fixed point HOLDS** (`stage1 == stage2`,
        byte-identical, on a compiler written in the subset it compiles) — see X1f + the HONEST BOUNDARY note.
        Still `[~]` and not `[x]`: the subset S that `F` covers is the one `F` is written in, so the remaining
        breadth (case classes, given/summon, enums, extensions, lambdas, comprehensions, `var`/`while`,
        interpolation) is corpus growth against the same exact oracle. No capability/design question remains:
    - [~] **F1 — subset lexer in the subset.** ✓ *Core landed 2026-07-14* (538b8e2c6): `selfhost-lexer` ports
          SpikeLex's core — whitespace-skip, multi-char identifiers + keyword classification, integers,
          operator runs (+-*/%<>=!&|^~:), single-char punctuation → a rendered `tag:lexeme` token stream,
          byte-identical + runs (verified via a new harness `.want` check). Remaining F1 breadth: string
          literals + escapes + interpolators, `//` + `/* */` comments (all individually proven runnable), and
          returning tagged-tuple tokens instead of a rendered string (for F2 to consume).
    - [~] **F2 — subset parser in the subset.** ✓ *Core landed 2026-07-14* (3ac411eb6): `selfhost-scala`
          reads REAL Scala syntax `def f(x) = if x < 1 then 1 else x * f(x - 1)` — a precedence-climbing parser
          (infix `< + - *`, `if`/`then`/`else`, function calls `f(e)`, parens, variables) over the F1 token
          stream, same climb algorithm as `SpikeParse`. Remaining F2 breadth: `match`/all pattern kinds,
          `val`/case-class/enum/extension/given/summon/lambda, offside blocks, the full infix table, multiple
          defs/params.
    - [~] **F3 + L1 — projection + lowerer in the subset.** ✓ *Core landed with F2* (3ac411eb6): `selfhost-
          scala` folds parse→AST→Core IR directly, with **name resolution** (param → `(local 0)`, function →
          `(global f)`) and a lowerer emitting **executable Core IR** (`(prim i.add/i.sub/i.mul/i.lt …)`,
          `(if c t e)`, `(app (global f) …)`, `(local 0)`, `(lit (int n))`, `(def f (lam 1 …))` + main). The
          emitted Core IR runs to factorial(5) = 120. Remaining breadth: multiple defs + arbitrary arity +
          proper slot allocation (env → local i); case-class ctor/Mirror/`_sel_`/`__regfields__`; given/summon
          dict-passing; enum ctor path; extension registration; the full `ssc1-lower` walk.
    - [x] **X1 — the fixpoint ✓ DONE 2026-07-16** (32b46f78d). `F` = `specs/v2.2-p6.5-fsub.ssc` (131 defs)
          — a compiler for subset S, written in S, whose Core IR is byte-identical to `ssc1-front` +
          `ssc1-lower`, compiling its OWN source. `specs/v2.2-p6.5-fsub.sh --self` → **65 ok / 0 FAIL**:
          61-program corpus byte-identical, `F(F_src) == ssc1-front(F_src)` (61750 B), C1 faithful + runs
          → 120, **`stage1 == stage2` byte-identical**. Scope caveat in the HONEST BOUNDARY note below.
      - **X1 architecture (established 2026-07-16 by MEASUREMENT — read this before continuing).** Three
        facts that were NOT written down before and that shape the whole remaining port:
        1. **The fixpoint is a COROLLARY of self byte-identity, not a separate goal.** If `F(P) ≡
           ssc1-front(P)` for all P in the corpus *including `F`'s own source*, then `stage1 == stage2`
           follows: `stage1 = C0(F_src) = F(F_src) ≡ ssc1-front(F_src)`, so `C1 ≡ C0` and `stage2 =
           C1(F_src) = stage1`. **So X1 == "extend the differential corpus until it contains `F_src`."**
           There is no separate fixpoint engineering — only breadth.
        2. **`ssc1-lower` emits a CONSTANT 7258-byte prelude** (~50 `_sel_*`/`__list_*`/exception/
           `Bump_tick` defs), byte-identical across programs (verified: `def main(): Int = 1+2` and
           `def main(): String = "hi"` have the same 7258-B prefix, sha `422e0067…`); user defs follow it
           in source order, then `) (entry (app (global main))))`. In `ssc1-lower` it is *built* as `IrDef`
           structures (~2000 lines), but it is fixed OUTPUT — so `F` emits it as a **string constant**.
           This is not a cheat: byte-identity is the oracle, and the blob is exactly what the reference
           produces. It does mean `F_src` carries a ~7.2 KB quoted blob (spliced with `dq`).
        3. **The exact reference shapes for the subset `F` is written in** (measured, not guessed):
           `1+2`→`(prim __arith__ (lit (str "+")) …)` — arith is `__arith__` + a QUOTED symbol, *not*
           bare `i.add` (C_min's style); `"a"+"b"`→`__arith__ "++"` but `a+b` on String **params**→
           `__arith__ "+"` (the front specialises on literal operands ONLY — a small special case, not a
           type system); `.charAt`→`scodeAt`, `.length`→`slen`, `.substring`→`sslice`, `==`→`__eq__`;
           `Cons(1,Nil)`→`(ctor Cons … (ctor Nil))`; `(1,2)`→`(ctor Tuple2 …)`; `._1`→`(app (global
           _sel__1) …)`; **`match` let-binds its scrutinee** — `(let ((scrut)) (match (local 0) ((arm Cons
           2 …) …)))` — so every arm's slots are shifted by the let; a tuple pattern `(a,b)` emits **two**
           arms (`Pair 2` and `Tuple2 2`); `val`-block→`(let (E) BODY)`; params are reverse-indexed
           (`def g(a,b)` → a=`(local 1)`, b=`(local 0)`).
        - Consequence for scope: `F` need only handle the constructs **`F`'s own source uses** (+ the
          corpus) — the classic bootstrap discipline that kept C_min at 84 defs. Keep the subset `S` that
          `F` is written in minimal-but-idiomatic and grow `F` and `S` together.
        - Artifact layout follows C_min's blessed pattern (single source of truth, harness reads the file),
          NOT the Scala-string-literal-in-`ScalaSpikeSpec` pattern that F1/F2 used — a 1–3k-line compiler
          cannot live in a test string, and `ScalaSpike.scala` is owned by the newfront agent.
      - [x] **X1a — architecture + harness + prelude-exact minimal `F` ✓ Landed 2026-07-16** (514fc72a3):
            `specs/v2.2-p6.5-fsub.ssc` + `specs/v2.2-p6.5-fsub.sh` (ssc1-front bootstrap, no sbt/spike).
            Int literals, `+ - * /` via `__arith__` + a QUOTED symbol, parens, `def main(): Int = e`,
            `//` comments, and the exact 7258-B prelude as a dq-spliced string constant. **10/10
            byte-identical.** The oracle caught the first bug on its first run: the quote sitting ON a
            prelude chunk boundary was dropped — exactly 4 bytes, one per boundary.
      - [x] **X1b — params/locals, calls, `if`, comparisons, recursion ✓ Landed 2026-07-16** (d2d3ca001):
            env with the innermost binding at the HEAD, so a name's position IS its `(local i)` index —
            this reproduces the reference's reverse param indexing for free. `< > <= >=` also route
            through `__arith__`+quoted symbol; `==`→`__eq__`; `!=`→negated `__eq__`; `&&`/`||` desugar to
            `if`; `true`/`false`→`(lit true/false)`. **26/26.**
      - [x] **X1c — strings + the two literal-driven specialisations ✓ Landed 2026-07-16** (f98604602):
            **43/43.** Both subtleties were read out of `ssc1-lower` and confirmed by probe, not guessed:
            (1) `+`→`++` iff either RESOLVED side is a str-expr (`ssc1-lower:2214` "KC5-micro"; str
            literal / `i->str` / `sslice` / `inf ++`) — so `a+b` on String *params* stays `+`;
            (2) `.length` is receiver-dependent — `slen` for a literal-ish receiver, `__method__`
            otherwise, and a `++` receiver takes the `__method__` path because `isConcatInf` is tested
            FIRST (`ssc1-lower:1577`). `.charAt`→`scodeAt`/`.substring`→`sslice` are unconditional.
            F emits canonically, so a receiver's class is recoverable from its emitted PREFIX.
      - [x] **X1d — `match`, ctors, tuples, cons-infix ✓ Landed 2026-07-16** (1c7654252): **57/57.**
            Scrutinee let-binding + the resulting slot shift; int-literal match → an if-chain (not a
            `match`); tuple pattern → TWO arms (Pair+Tuple2); `case _` → `(default …)` outside the arm
            list; `::` right-assoc. Two real bugs the oracle localised at once: a paren slip lowered
            three defs to `(global _err)` in the entry seq (F0 still *bootstrapped*, to 58 KB, and only
            crashed at run time — grepping `_err` found it instantly); and a **tuple param type**
            `def g(p: (Int, Int))` came out `(lam 2)` not `(lam 1)` because the type-skipper stopped at
            the `,` INSIDE the type. Type skipping now tracks paren depth.
      - [x] **X1e — `val`-blocks ✓ Landed 2026-07-16** (96a004ba6): `{ val x = e  BODY }` → `(let (e)
            BODY)`, one nested let per val. **62/62.**
      - [x] **X1f — THE FIXPOINT ✓ Landed 2026-07-16** (32b46f78d). `specs/v2.2-p6.5-fsub.sh --self` →
            **65 ok / 0 FAIL**, personally observed:
            - 61-program differential corpus: `F(P) == ssc1-front(P)` **byte-identical** for every P;
            - **`F(F_src) == ssc1-front(F_src)` byte-identical (61750 B)** — F compiles its OWN source;
            - C1 (the self-produced compiler) is byte-identical to the reference AND its IR runs → 120;
            - **`stage1 == stage2`, byte-identical, 61750 B.**
            F is 131 defs / 208 lines of subset. No quine (source read from a FILE via the ssc0 driver);
            escape-free (`dq` parameter). The last shape needed was the **entry clause**: F's own source
            is main-less, and the reference emits `(entry (lit unit))` for a main-less program — making
            that faithful is what closed self byte-identity, and it then exposed that wrapping stage1
            must REPLACE the entry, not just splice the main def in, or C1 never runs.
      - [x] **X1g — lambdas, local callees, chained application ✓ Landed 2026-07-16** (2435b2a5a):
            corpus 61 → 66, **70 ok / 0 FAIL, fixpoint still holds** (64851 B). `(lam N body)`; lambda
            params push onto the env like def params, so reverse indexing + closure-by-depth fall out.
            The paren form is ambiguous with group/tuple → F looks ahead to the MATCHING `)` and tests
            for `=>` (the spike backtracks with mark/reset; lookahead needs neither). **Real gap the
            lambdas exposed**: a call to a BOUND name is `(app (local i) …)`, not `(app (global nm) …)`
            — F always emitted a global callee, and the 61-program corpus never caught it because F's
            own source never calls a local. Keeping `--self` in the same harness means every breadth
            slice re-proves self-compilation or fails loudly.
      - [x] **X1h — case classes ✓ Landed 2026-07-16** (98421d615 prep, 1c253090e decls, f8625c681
            ctor+field). **89 ok / 0 FAIL, fixpoint holds** (79667 B); corpus 69 → 85. The first
            construct that is not a local shape: it needed a PRE-PASS (accessors are global and
            cross-declaration) *and* made the parser CONTEXT-DEPENDENT (ctor + field selection must know
            what was declared), so the collected registry is threaded as `cx = (dq, (ccNames,
            fieldNames))`. The measured table below is the durable record — it was implemented against
            exactly this and every line is pinned by a corpus entry:
            - **`_sel_<f>` defs HOIST TO THE VERY FRONT**, before all user defs — verified with
              `def helper …` BEFORE `case class P(x)`: order is `_sel_x, helper, P, __mirror_P, main`.
              One global def per field NAME, merging one arm per class having that field (two classes
              sharing `x` → a single `(def _sel_x …)` carrying `(arm P 1 …) (arm Q 1 …)`, in class
              declaration order). Shape: `(lam 1 (match (local 0) (<arms>) (default (prim __method__
              (lit (str "<f>")) (local 0)))))`; a field's slot is `(local arity-1-pos)`.
            - **ctor + mirror stay AT the declaration site** (interleaved: `_sel_x, _sel_y, P,
              __mirror_P, helper, Q, __mirror_Q, main`). `(def P (lam N (ctor P (local N-1) … (local
              0))))`; `(def __mirror_P (ctor Mirror (lit (str "P")) <field-name Cons-list> <field-TYPE
              Cons-list>))`.
            - **Mirror type names are whitespace-stripped SOURCE TEXT**: `List[Int]` → `"List[Int]"`,
              `(Int, Int)` → `"(Int,Int)"`. So F must lex `[`/`]` (it currently DROPS them) and rebuild
              the text by concatenating token lexemes with NO separator — which reproduces the stripped
              form exactly. This is why case classes force real type parsing: types leave no trace
              anywhere else in the Core IR, which is why skipping them was byte-faithful up to X1g.
            - **The ENTRY rule** (measured across all four combinations): items = one `(prim
              __regfields__ (lit (str "C")) <field-name Cons-list>)` per case class in declaration
              order, then `(app (global main))` if a main exists. **0 items → `(entry (lit unit))`;
              exactly 1 → that item BARE (no `seq`!); >1 → `(entry (seq i1 i2 …))`.** A main-less
              program with one case class really is `(entry (prim __regfields__ …))`, not a seq.
            - **`.field` dispatch**: `.x` with no parens → `(app (global _sel_x) recv)` **iff** `x` is a
              collected case-class field; an unknown field → `(prim __method__ (lit (str "zzz")) recv)`
              (measured: `o.zzz` with no case class at all). `.length` keeps its X1c dispatch.
            - **REAL LATENT BUG ✓ FIXED** (98421d615; found by probing past the corpus): `def f(m:
              Map[String, Int], k: Int)` emitted **`(lam 3)` where the reference says `(lam 2)`** — F's
              lexer DROPPED `[`/`]`, so the `,` inside the brackets read as a parameter separator. Wrong
              arity is silent and total: every local index in the body shifts. The 66-program corpus had
              stayed green over a broken lexer. Type skipping now tracks BOTH paren and bracket depth
              (same family as the X1d tuple-param bug). Pinned by ty_generic/ty_nested/ty_tuplist.
            - **`.name` with no parens ✓ FIXED** (f8625c681): it used to fall into the method path
              (which expects `(`). Now a declared field → `_sel_`, unknown → `__method__`. **Still open**:
              the PRELUDE selectors (`._1`.., `.trim`, `.mkString`, …) have their own `_sel_` defs in the
              prelude and are NOT case-class fields, so `(1,2)._1` → `(app (global _sel__1) …)` is still
              unhandled — F would emit `__method__`. Needs the fixed prelude-selector name table. No
              corpus entry uses it yet; add one when closing.
      - [ ] **X1i — remaining breadth**, in the same style: given/summon dict-passing, enums, extensions,
            for-comprehensions, `var`/`while`, string interpolation, and the List-variable registry that
            dispatches `.length` to `_sel_length` (`ssc1-lower:233,301`).
      - **HONEST BOUNDARY of the landed X1 — read before claiming P6.5 done.** The fixed point holds for
        the subset **S that F is itself written in** (the same bootstrap discipline that kept C_min at 84
        defs), not for all of ScalaScript. `F(P) == ssc1-front(P)` is proven on the 61-program corpus, and
        on `F_src`. **Boundary as of 2026-07-16 (moves as slices land — keep this line honest):** the
        corpus is **85 programs**, and `F` covers arithmetic/comparison/booleans, defs+params+recursion,
        `if`, strings + `charAt`/`length`/`substring`/`==`/`++`, `match` (Cons/Nil/tuple/int-lit/
        wildcard/cons-infix), `val`-blocks, **lambdas + HOFs** (X1g), and **case classes** end-to-end
        (X1h). Still out, unchanged in kind (each is corpus growth against the same exact oracle, no new
        design question): **given/summon** dict-passing, **enums**, **extensions**, **for-comprehensions**,
        **`var`/`while`**, string **interpolation**, the **prelude-selector table** (`._1`/`.trim`/
        `.mkString` — see X1h), and the **List-variable registry** that dispatches `.length` to
        `_sel_length` (`ssc1-lower:233,301` — F has no registry, so a `List` local's `.length` would
        diverge; F's own source avoids it by using a recursive `dlen`).
    - Sequencing: F1 → F2/F3 → L1 → X1. Each stage is differential-tested against the spike/`ssc1-front` on
      the growing corpus (same harness). Estimated ~1–3k lines of subset code; multi-session but purely
      mechanical — no unknowns. Every primitive it needs (strings incl. charAt/length/concat/eq/substring,
      int↔string, tuples, Cons-lists, nested/tag patterns, recursion, closures, HOFs, executable Core IR
      emission incl. functions/recursion) is proven runnable on the bare VM and byte-identical to ssc1-front.
  - [x] **P6.6 — literal self-compilation fixpoint (NO quine) ✓ DONE 2026-07-14; re-verified 2026-07-17.**
        (Accounting fix 2026-07-17: this parent sat `[ ]` while its deliverable P6.6d + capstone c3 were both
        `[x]` done and the only open children were superseded plan lines — see below. The coordinator re-ran
        the gate: `specs/v2.2-p6.6-fixpoint.sh` → `stage1 == stage2` byte-identical, 32,824 B, C1 compiles
        fac(5)→120, exit 0. The fixpoint holds.) — spec `specs/v2.2-p6.6-self-compilation.md`.
        CORRECTION of an earlier note: there is no quine — the compiler reads its source FROM A FILE, exactly
        like `v2/bin/ssc1-run.ssc0` (`match #io.args() { case Cons(path,_) => compile(#utf8->str(#io.readFile(
        path))) }`). `#`-prims are ssc0 (not subset), so the file reading is an ssc0 DRIVER wrapping the
        compiler's pure `compile: String→String`. The fixpoint: spike(C)=C0; stage1=C0(C_src); stage2=driver(
        stage1)(C_src); **stage1==stage2**.
    - [x] **P6.6a — driver ✓ DONE 2026-07-14**: `specs/v2.2-p6.6-selfcompile-demo.sh` wraps selfhost-str's
          `compile` in an ssc0 file-reading driver (dropLast the hard-coded main + a file-reading main AST via
          mk*/prim `Pair`s). C0 (the compiler, as Core IR with a `match #io.args()` entry) READS an object-
          language program from a FILE, compiles it, and the emitted Core IR runs -> 4. NO quine, NO hard-coded
          source — exactly like ssc1-run.ssc0.
    - [x] **P6.6b — F completeness DESIGN DE-RISKED 2026-07-14**: NO var-patterns, NO escapes needed. (1)
          bindings via HELPER FUNCTIONS (`val x=e; body` -> `def h(..,x)=body; h(..,e)`). (2) escape-free
          emission: bare quote-free prims (+->i.add, -->i.sub, *->i.mul, <->i.lt, ==->__eq__ polymorphic,
          ++->sconcat, .length->slen, .charAt->scodeAt, .substring->sslice — all verified to run) + a `dq`
          PARAMETER carrying the `"` char (`compile(src,dq)`; driver builds dq via #sfromCodes(Cons(34,Nil)),
          verified). So C's source has NO `"`-inside-a-string literal -> scanStr compares code 34, emits
          `dq++content++dq`; escStr = identity. Spec §"quote-free emission design".
    - [x] **P6.6c — write C_min in L (self-compiling) ✓ DONE 2026-07-14.** (Was `[ ]` by oversight: its
          outcome lines `c1/c2 ✓ DONE` and `c3 (capstone) ✓ DONE` below are both `[x]`; the two granular
          `[ ]` c1/c2 lines directly under this are the SUPERSEDED plan, now marked so.) A compiler
          `compile(src, dq): String` for language L,
          WRITTEN in L, whose OWN source is entirely within L (so it can compile itself). Design decisions that
          shrink L to the minimum: **match only for `Cons(h,t)`/`Nil`/`(a,b)`** — ALL token-kind dispatch via
          `if`-chains + `fst/snd/hd/tl` (NO int-literal patterns `(2,26)`, NO wildcards `case _`, NO nested
          non-trivial matches); **helper-function bindings** (no `val`-blocks); **escape-free** (`dq` param for
          `"`, bare prims); **`++`→sconcat / `+`→i.add** distinguished (i.add is NOT polymorphic on strings);
          **only `<` and `==`** for numeric compare (`a>=b` ≡ `b<a+1`, `a<=b` ≡ `a<b+1`, `a>b` ≡ `b<a`).
      - [x] ~~c1 — lexer (helper-fn bindings; scanStr compares code 34; tokens: def/if/then/else/match/case +
            `= ( ) + - * < , { } => . == ++`; kinds int/lower/Upper/str).~~ SUPERSEDED by `c1/c2 ✓ DONE` below.
      - [x] ~~c2 — parser+emitter (Pratt climb; atoms int/str(dq)/local/call/ctor/tuple/if/match; postfix method
            + match; arms Cons/Nil/tuple; multi-param def loop). Emits bare-prim Core IR.~~ SUPERSEDED by `c1/c2 ✓ DONE` below.
      - [x] c1/c2 ✓ DONE 2026-07-14 — `specs/v2.2-p6.6-cmin.L` (74 defs). VERIFIED: C_min compiles a spread of
            L programs correctly (arith, calls, if, recursion, bool, strings+`.charAt/.substring/.length`, `==`,
            `++`, match Cons/Nil/tuple) via the ssc1-front file-driver → each result runs to the expected value.
            Bug found+fixed: C_min must emit `true`/`false` as `(lit true)`/`(lit false)` (else `false`→unbound
            local→`(local 0)`=a char code → "if condition not Bool"; ssc1-front had masked it).
    - [x] **P6.6d — FIXPOINT ✓ DONE 2026-07-14.** `specs/v2.2-p6.6-fixpoint.sh` (self-contained, ssc1-front
          bootstrap — no sbt/spike). `C0 = driver(ssc1-front(cmin.L))` (file-reading main + `dq` as a string
          literal via `#sfromCodes(Cons(34,Nil))`, `#coreir.encode` escapes it to `\"`); `stage1 = C0(cmin.L)`
          (C_min compiles its OWN source, balanced 22085 B); `C1 = stage1 + the same file-main`; `C1` proven a
          WORKING compiler (compiles fac(5)→120); `stage2 = C1(cmin.L)`. **`stage1 == stage2` byte-identical.**
          The literal self-compilation fixpoint — no quine (reads source from a FILE), no source-embedding.
      - [x] c3 (capstone) ✓ DONE 2026-07-14 — **the SPIKE bootstraps C_min.** Added `cmin` toy to
            ScalaSpikeSpec (reads `specs/v2.2-p6.6-cmin.L`); the p6.0 harness confirms **`spike(cmin.L) ≡
            ssc1-front(cmin.L)` byte-identical** (both 42135 B) → the UniML ScalaScript dialect parses+projects
            C_min's entire 74-def source identically to the reference front, so the spike is a valid bootstrap.
            p6.0 harness: 86 ok / 0 FAIL (cmin: `CoreIR≡ssc1-front`; no runnable main, so run-ir display empty).
  - Prereqs: subset must hold — the one v2-side lift is **immutable indexed `Array`** (gapmap:76);
        anon-trait + mutable-object-field stay out; multi-file `package`/`import` reconciliation
        (gapmap:82-83) needed before the compiler's own multi-file source dual-compiles.
  - [x] **P6.7 — L gains `val`-blocks (let); C_min self-uses them ✓ DONE 2026-07-14.** Braced `{ val x = e …
        final }` → nested `(let (E) BODY)`. (1) C_min compiles them (`parseBlock`/`parseBlockVal`/
        `parseBlockEnd`, `{`(28) atom, `val`(7) kwCode). (2) C_min self-uses them — 5 `…2`-helpers merged back
        into idiomatic blocks (parseArmTup/parseArmCons/postfixDot/climbStep/parseOneDef); C_min now 72 defs;
        **fixpoint still holds** stage1==stage2 (22794 B), C1 compiles fac(6)→720. (3) The SPIKE parses them:
        `ScalaSpike.parseAtom` gained a `spike.lbrace` case (`parseBracedBlock`) → same `spike.block` node →
        byte-identical to ssc1-front (before: braced block → `__notImplemented__`; spike only did offside).
        Regression: `braced-block`/`braced-nest` toys + unit test; p6.0 harness **88 ok / 0 FAIL**; `spike(
        cmin.L) ≡ ssc1-front(cmin.L)` byte-identical (43898 B). Fixpoint script gained 2 val-block L-tests.
  - [x] **P6.8 — spike gap-scan: 2 byte-identity gaps found + fixed ✓ DONE 2026-07-14.** Probed 8 common
        ScalaScript constructs (all valid subset) through spike vs ssc1-front; 6 were already byte-identical
        (guard/lamblock/listlit/neglit/ormatch/blockarg), **2 were real spike gaps, fixed**: (1) **cons-infix
        pattern** `case h :: t =>` — the spike parsed it to garbage `(ctor Cons (let…__notImplemented__)…)`;
        added `parseConsPattern` (right-assoc) + a `spike.conspat` node → `Pair("cpat", Pair("Cons", …))`.
        (2) **parameterless `def x: T = e`** (no param clause) — a bare `x` reference did not auto-apply
        (`(global x)` vs ssc1-front's `(app (global x))`); `defNode` now wraps the body in
        `mkParameterlessBody` when there is no `def.lparen`. Regression: 8 `gap-*` toys + 2 unit tests; p6.0
        harness **96 ok / 0 FAIL**. Makes the UniML ScalaScript dialect a more complete front.
  - [x] **P6.9 — spike gap-scan round 2: `throw`/`new` fixed; imperative/currying scoped ✓ DONE 2026-07-14.**
        Probed 8 more constructs; 3 already byte-identical (tupleacc `._1`, multi-type-params, — kept as
        toys), **1 gap FIXED**: `throw e` → `Pair("prim", Pair("__throw__", [e]))` + `new C(args)` == `C(args)`
        (both dispatched on the identifier in `parseAtom`, mirroring ssc1-front; a `spike.throw` node). p6.0
        harness **99 ok / 0 FAIL**; +1 unit test. **5 gaps found + scoped as KNOWN spike boundary** (deferred,
        below): they need a dedicated "imperative + currying" project.
    - [x] **P6.10 — imperative + currying + comprehensions ✓ DONE 2026-07-14.** All FIVE P6.9 gaps now
          byte-identical to ssc1-front (the spike is no longer functional-subset-only): (a) **curried**
          `def f(a)(b)` — parseDef loops over param clauses, appending → one flat `(lam N)` (lowerProg flattens
          the call by arity); (b) **nested `def`** in a block — parseStmt handles `def` → a block stmt →
          lowerBlock's `letrec`; (c) **`var` + assignment** — `spike.var`/`spike.assign` → `Pair("var"/"assign",
          …)`, backed by lcell in lowerProg; (d) **`while c do body`** → `Pair("while", (cond, body))`; (e)
          **`for x <- gen do/yield e`** — desugared at parse time to `gen.foreach/map(x => e)`, guard →
          `gen.filter` (a for-do body may be an assignment). `var`/`while`/`for`/`do` are dispatched by
          identifier value (like ssc1-front, not lexer keywords). Regression: 9 `i-*` toys + 3 unit tests;
          p6.0 harness **106 ok / 0 FAIL**.
    - [x] **P6.11 — final completeness sweep: 3 more gaps fixed ✓ DONE 2026-07-14.** Probed 12 more constructs;
          7 already byte-identical (block-lambda, bool ops, chained selection, offside arm bodies, nested tuple
          patterns, unary `!`). 3 gaps FIXED: (a) **if-without-else** → else defaults to `mkTup(Nil)` (Unit),
          and `then`/`else` branches may be assignments (`if c then r = n`); (b) **`for` tuple binder**
          `for (a,b) <- gen` → a `__fp => { val a = __fp._1; val b = __fp._2; … }` destructuring binder-lambda
          (detected by binder count > 1); (c) **`for` multi-generator** `for x <- xs; y <- ys` → flatMap chain
          (flatMap for each generator but the last). Regression caught+fixed: an offside `else` block needs
          `elseLine` = the `else` line (computed BEFORE consuming) or it de-nests (nested3). 3 unit tests; p6.0
          harness **115 ok / 0 FAIL**.
    - [x] **P6.12 — underscore-placeholder lambdas ✓ DONE 2026-07-14.** `.map(_ + 1)` / `.filter(_ < 3)` /
          `_ + _` / `_ * 10`: a `_` in a call ARGUMENT (reached through inf/pre/sel/app/paren, NOT a nested
          lambda) lifts the whole arg to an N-ary lambda — `_ + 1` → `mkLam(["__u0"], __u0 + 1)`, `_ + _` →
          `mkLam(["__u0","__u1"], __u0 + __u1)` (each `_` a distinct param left-to-right). A bare `_` arg is
          left unwrapped. `call(b)` now wraps each arg via `wrapArg`; `countPh`/`projectPh` (a mutable counter,
          stops at lambda boundaries) mirror ssc1-front's `wrapPhArg`/`countPh`/`replacePhSeq`. p6.0 harness
          **119 ok / 0 FAIL**; 4 `u-*` toys + 1 unit test. **The spike now matches ssc1-front byte-for-byte
          across the entire common ScalaScript subset** (functional + imperative + currying + comprehensions +
          placeholder lambdas).
    - [x] **P6.13 — C_min language extension: comparison + boolean operators ✓ DONE 2026-07-14.** Extended the
          self-compiling compiler's object language L with `>`/`>=`/`<=` (→ bare `i.gt`/`i.ge`/`i.le`) and
          short-circuit `&&`/`||` (→ `(if L R (lit false))` / `(if L (lit true) R)`, desugared in emitBin).
          Lexer gained `lexLt`/`lexGt`/`lexAmp`/`lexPipe`; precedence renumbered (`||`1 `&&`2 cmp 3 `+ -`4
          `*`5). **C_min self-uses them**: its char-classification is now idiomatic — `isLo(c) = c >= 97 && c
          <= 122` (was `if 96 < c then c < 123 else false`), `atEnd(i, n) = i >= n`. C_min now 76 defs; the
          fixpoint STILL holds (`stage1 == stage2`, 25234 B; C1 compiles fac(6)→720), and `spike(cmin.L) ≡
          ssc1-front(cmin.L)` byte-identical (46697 B) — the spike already lowered these operators, so no
          spike change was needed. Fixpoint script gained `cmp`/`andor` L-tests.
    - [x] **P6.14 — C_min language extension: match wildcard `case _` ✓ DONE 2026-07-14.** L's `match` gained a
          wildcard/default arm `case _ => body` → CoreIR `(match scrut (arms) (default body))` (the default is
          OUTSIDE the arm list). Lexer tokenizes `_` (code 95 → `(2,45)`); `parseArms` now returns `(arms,
          (defStr, rest))` (a nested tuple — C_min can't 3-tuple), each arm helper threads the default; a
          `case _` arm emits the `(default …)` and consumes the closing `}` (the bug that first leaked token
          codes as def names). **C_min self-uses it**: `hd`/`tl`/`isEmpty` now dispatch with `case _` instead
          of the redundant `Cons`/`Nil` second arm. C_min now 77 defs; the fixpoint STILL holds (`stage1 ==
          stage2`, 25979 B; C1 compiles fac(6)→720), and `spike(cmin.L) ≡ ssc1-front(cmin.L)` byte-identical
          (48663 B) — the spike already handled `case _` (match-lit toy). Fixpoint script gained
          `wildcard`/`wildonly` L-tests.
    - [x] **P6.15 — C_min language extension: int-literal match patterns `case N =>` ✓ DONE 2026-07-14.** L's
          `match` gained integer-literal arms `n match { case 0 => … case 1 => … case _ => … }`. C_min detects
          an int-literal match (first arm's pattern token is kind 0) in `postfixMatch` and emits an INLINE
          if-chain `(if (prim __eq__ recv (lit (int N))) body <rest>)` ending in the `case _` default (vs
          ssc1-front's let-bound if-chain — C_min emits its own bare-prim style, only self-consistency +
          runnability matter). `parseIntMatch`/`parseIntArms`/`parseIntArm` are the new path; the ctor-match
          path is unchanged. **C_min self-uses it**: `arithBare(k)` now dispatches token codes with `k match {
          case 23 => "i.add" … case _ => "i.le" }` instead of an if-chain. C_min now 80 defs; the fixpoint STILL
          holds (`stage1 == stage2`, 27586 B; C1 compiles fac(6)→720 AND an int-literal program→1), and
          `spike(cmin.L) ≡ ssc1-front(cmin.L)` byte-identical (52138 B). Fixpoint script gained
          `litpat`/`litdef` L-tests.
    - [x] **P6.16 — C_min language extension: `::` cons-infix ✓ DONE 2026-07-14.** L gained `::` in EXPRESSIONS
          (`a :: b` → `(ctor Cons a b)`, RIGHT-associative — `1 :: 2 :: Nil` = `Cons(1, Cons(2, Nil))`) and in
          simple PATTERNS (`case h :: t => …` → the same `(arm Cons 2 …)` as `case Cons(h, t)`). Lexer gained
          `lexColon` (`:` + `:` → code 46); `binPrec` renumbered to Scala order (`||`1 `&&`2 cmp 3 `::`4 `+ -
          ++`5 `*`6); `climbStep` uses `rightMin` (right-assoc parses the RHS at prec `p`, not `p+1`); `emitBin`
          46 → `(ctor Cons …)`; `parseArm` gained `isConsInfix`/`parseArmConsInfix`. **C_min self-uses it**:
          `hd`/`tl`/`dlen` now pattern-match `case h :: t`, and env building uses `tv :: hv :: env` /
          `bv :: av :: env` instead of nested `Cons(…)`. C_min now 84 defs; the fixpoint STILL holds
          (`stage1 == stage2`, 29293 B; C1 compiles a `::` program→60), and `spike(cmin.L) ≡ ssc1-front(cmin.L)`
          byte-identical (54779 B). Scope: only simple `h :: t` patterns (nested `a :: b :: t` would need
          nested destructuring — not used by C_min). Fixpoint script gained a `consinfix` L-test.
    - [x] **P6.17 — C_min language extension: `//` line comments ✓ DONE 2026-07-14.** L's lexer gained `//`
          line comments (skip to the next newline) via `lexSlash`/`scanLineEnd` (`/` code 47). **C_min self-uses
          it**: cmin.L now opens with a 3-line documenting header comment. C_min now 84 defs (+ header); the
          fixpoint holds (`stage1 == stage2`, 29954 B; C1 compiles → 42), and `spike(cmin.L) ≡ ssc1-front(
          cmin.L)` byte-identical (55520 B) — the spike already treats `//`/`/* */` as trivia (p6.0). Fixpoint
          script gained a `comment` L-test (trailing `// …` skipped to EOL).
    - [x] **P6.18 — CAPSTONE: C_min compiles an INDEPENDENT program ✓ DONE 2026-07-14.**
          `specs/v2.2-p6.18-rpn.L` is a Reverse-Polish-Notation calculator written in L — a string tokenizer +
          a stack machine (~20 defs using match on lists/tuples, `::`, if-chain kind-dispatch, `.charAt`/
          `.length`, recursion, `>=`/`<=`/`&&`). `specs/v2.2-p6.18-capstone.sh` builds C_min, uses it to compile
          rpn.L to Core IR, and runs it on several RPN expressions: `2 3 4 * +`→14, `3 4 + 5 *`→35, `100 20 30
          + -`→50, `2 3 + 4 5 + *`→45, etc. — all correct. C_min compiling a real independent program (beyond
          compiling itself) is the proof that it is a general-purpose compiler for L, closing the P6.6→P6.18
          self-host arc: a self-compiling compiler (fixpoint) for an idiomatic Scala-subset language, byte-
          identically bootstrappable by the UniML spike front.
  - **P6.19+ — C_min pattern-matching COMPLETENESS (make everything work).** The self-host arc is proven; this
    sub-arc closes C_min's real object-language gaps so it can compile ADT-based programs, each verified by the
    fixpoint + spike + a capstone. All follow the established loop (emitter/lexer/parser edit → self-use where
    natural → fixpoint(fast, ssc1-front) → spike byte-identity → land).
    - [x] **P6.19 — arbitrary ctor patterns + variable-arity construction ✓ DONE 2026-07-14.** `case Name(a,
          b, …) => …` now emits `(arm Name k body)` — `parseArmCtor`/`parseCtorPatVars`/`envApp` read the ctor
          name + collect a variable number of var-patterns (arity `k`, env = `reverse(vars) ++ env`), replacing
          the `Cons`/arity-2 hard-code. `Name(a, b, c)` construction: `parseCtorArgs` now collects args until
          `)` → `(ctor Name a b c)` (any arity; `Cons(a,b)`/`Num(v)`/`Tri(a,b,c)` all work). C_min now 88 defs
          (`idxOf` still uses `case Cons(h,t)`, now via the general path). Fixpoint holds (`stage1 == stage2`,
          30583 B; C1 compiles an ADT program → 42); `spike(cmin.L) ≡ ssc1-front` byte-identical (58049 B).
          **CAPSTONE 2**: `specs/v2.2-p6.19-ast.L` — a tree-walking arithmetic AST evaluator (`Num`/`Add`/`Sub`/
          `Mul`) — is compiled by C_min via `p6.18-capstone.sh` (`(3*4)+(10-8)`→14, `(2+3)*4`→20). Fixpoint
          script gained `ctorpat`/`ctor3` L-tests.
    - [~] **P6.20 — mixed tuple patterns `case (0, v)` ✓ DONE 2026-07-14; nested cons `a :: b :: t` deferred.**
          Mixed literal+var tuple patterns now work: a match whose first arm is `( <int> , …` is compiled to a
          single `(arm Tuple2 2 <if-chain>)` — the tuple is destructured once (field0=`(local 1)` the tag,
          field1=`(local 0)` the value), then an `if (prim __eq__ (local 1) (lit (int litN))) body <rest>`
          chain ends in the `case _` default. Each arm's var-name is bound at the value slot via env
          `vn :: "_" :: env` (a "_" placeholder for the tag), the default via `"_" :: "_" :: env` (shift by 2).
          `isMixedFirst`/`parseMixedMatch`/`parseMixedArms`/`parseMixedArm` — a third dispatch in `postfixMatch`
          alongside the int-literal and ctor paths. Verified externally (`(0,v)`→v, `(1,w)`→w+100, `(9,_)`→
          default) — NOT self-used, so the fixpoint (`stage1 == stage2`, 32824 B) is untouched; C1 compiles a
          mixed-tuple program → 77; `spike(cmin.L) ≡ ssc1-front` byte-identical (62131 B). C_min now 92 defs.
      - [ ] nested cons `a :: b :: t` DEFERRED — it desugars to a NESTED match on the tail with the OUTER
            match's default threaded into the inner match (`(arm Cons 2 (match tail ((arm Cons 2 body)) (default
            d)))`); C_min parses arms left-to-right and only learns the default last, so correct threading is
            intricate and the pattern is rare (an explicit nested `case h :: t => t match { case h2 :: t2 => … }`
            is the idiomatic workaround, and works today).
    - [~] **P6.21 — CI protection of the self-host (lightweight, in CI now; full jar-based still future).**
          DONE: a `ScalaSpikeSpec` test (`"C_min … projects cleanly through the spike — no holes, every def"`)
          reads the real `specs/v2.2-p6.6-cmin.L`, projects it through the spike, and asserts NO
          `__notImplemented__` hole, `compile`/`lex`/`parseArmCtor`/`emitBin`/`parseMixedMatch`/… present, and
          `#mkDef == #source-defs`. Needs no ssc jar, so it runs in CI (uniml tests) and catches any spike
          regression that breaks the C_min bootstrap. The artifact is required (fallback resolves the repo-root
          and `uniml/` CWDs; `CMIN_L` overrides).
      - [ ] FULL jar-based CI still future: the byte-identity-vs-ssc1-front (`p6.0-spike-verify.sh`) and the
            `stage1==stage2` fixpoint + capstone (`p6.6-fixpoint.sh` / `p6.18-capstone.sh`) need the ssc0 kernel
            `run`/`run-ir`, which the standard-tier `bin/lib/ssc.jar` (from `install.sh`) does NOT provide —
            they need the **tools-tier fat jar** (`sbt cli/assembly`, ~92 MB, run-ir-capable). Wiring: add a CI
            step that builds that jar and runs the three scripts with `SSC_JAR=` it. Deferred as a heavier infra
            change (fat-jar build time + timeouts).
    - [ ] **P6.22 (architectural, Sergiy-gated) — spike → production front.** The spike is byte-identical to
          `ssc1-front` across 119 constructs; consider it as an alternative/validation front. Big decision — do
          NOT act without Sergiy.

---

## the breadth probe parses PROSE as ScalaScript, and the headline number includes it

Found while measuring UNIML-SSC3-ALPHABET, and it changes how every breadth number
in this module should be read.

`SscCompose.parse` treats an untyped fence as ScalaScript — deliberate, and the
documented rule for a bare `.ssc` (fences optional since 2026-07-09, the file is
code unless a fence says otherwise). The consequence nobody had looked at: a
plain ``` block holding a diagram is handed to the ScalaScript lexer.

`v1/runtime/std/mapreduce/shuffle.ssc` lines 30-42 are a protocol sketch —
`coordinator -> workers: ProcessPartition(...)` with a U+2192 arrow — inside an
untagged fence. It contributed 18 of the file's diagnostics.

**How it surfaced.** The alphabet change moved diagnostics 319 -> 296 on the same
tree in the same session, and 11 of the 23 were the arrow: U+2192 is >= U+0080, so
it went from "unexpected token" to a legal identifier character. Nothing about
real code improved. The parser merely became permissive enough to swallow a
diagram, and the headline improved for it.

So `diagnostics=296, clean=94.8%` is measured against a corpus that includes
markdown prose, and a change that makes the lexer more accepting will always look
like a breadth win. That is a probe that rewards the wrong thing.

**It happened AGAIN the same day, which is why this is filed rather than noted.**
Dropping the dialect's uppercase-name requirement moved diagnostics 296 -> 273.
The seven lowercase declarations in the corpus that this "fixed" are English
sentences — "case class lifts into a `Dataset`", "trait that both implement" —
prose beginning with a word the lexer reads as a keyword. Two separate changes
in one session, both correct on their own merits, both credited by this probe
for making prose parse. A number that rewards permissiveness twice in a day is
not measuring the language.

**FIXED 2026-08-05 by splitting the columns**, which was the first of the three options below.
`SscBreadthSpec` now attributes diagnostics per FENCE rather than per file, and the split is
stark: 1,650 tagged fences carry 172 diagnostics (0.10 each), 38 untagged ones carry 96 (2.53
each) — **25x the density in 2.3% of the fences**. The tagged column has its own floor, verified
by tightening it and watching it fail. A change that only makes prose parse now moves `untagged`
and leaves `tagged` flat, which is the distinction the headline could not make.
The entry stays open for the part that is NOT fixed: nothing prevents a future probe from
reporting the mixture again, and the per-fence re-parse is an approximation — it re-parses each
fence standalone, which is why tagged+untagged (268) is slightly under the headline (273); the
remainder belongs to the markdown and front-matter layers.

**The options as they stood, kept because the reasoning outlived the choice.** Skipping untagged fences would
contradict the fences-optional rule and would stop measuring real code in every
file that uses bare fences properly. Options worth weighing when someone takes it:
count tagged and untagged fences as SEPARATE columns so a change cannot hide in
the mixture; or classify an untagged fence by whether it lexes at all and report
the two populations. Either way the requirement is that the number stops moving
for reasons that have nothing to do with the language.
