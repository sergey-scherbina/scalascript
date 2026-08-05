# UniML is the ScalaScript 3 front end — the parser and the AST

Status: **decided, not built.** Sergiy set this direction on 2026-08-01. It REVERSES the
authority recorded in `uniml/BACKLOG.md` UPR-8a ("the Scala UniML adapter is an oracle/tooling
artifact") and narrows the claim in [`project-partitioning.md`](project-partitioning.md) §8.3
("UniML … is not the language's parser infrastructure"). Both now point here.

This document records WHAT WAS DECIDED and WHAT IT COSTS. It does not move a file, and nothing
in the tree implements it yet.

## 1. The decision

In **ScalaScript 3**, UniML is the front end: `.ssc` text is parsed by a UniML dialect, and the
resulting UniML tree IS the compiler's AST. There is no separate parser and no separate syntax
tree to lower into.

Two things it does NOT say, because the difference decides the design:

- It is not a statement about today. `F` (`specs/v2.2-p6.5-fsub.ssc`) remains the product front
  for v2, and UPR-8b/8c/8d still describe closing it out. ScalaScript 3 is where this lands.
- It is not "delete the typed tree". See §3 — every system that has made a lossless tree its
  AST kept a typed view over it, and UniML already has that shape.

## 2. Where the tree actually is today

Measured 2026-08-01, so the gap is a number rather than an impression.

| | |
|---|---|
| ScalaScript dialect in any `src/main` tree | **none** |
| the dialect that exists | `uniml/core/src/test/…/spike/ScalaSpike.scala`, 2,447 lines, TEST scope |
| composer | `uniml/scala/src/test/…/SscCompose.scala`, 130 lines, TEST scope |
| declared breadth | "a UniML dialect for a growing Scala **subset**" — its own header |
| UPR-4 (production adapter, cross build, composer, gates) | **0 of 4** |
| publishable artifacts | none — UPR-9, `publishLocal` still open |

The spike is a working proof that the approach parses real ScalaScript, and it is byte-identical
to `ssc1-front` on the constructs it covers. It is not a front end: nothing consumes it, it
cannot be depended on, and it covers a subset the item itself refuses to let anyone overstate
(UPR-4a: "the declared dialect id must name the actually passing subset").

## 3. The design point that decides whether this works

**A lossless CST is the right STORAGE for an AST and the wrong INTERFACE for one.**

`UniNode` is `Branch(kind: String, edges, …) | Token(…)` — untyped, string-keyed, every source
character present including trivia. That is exactly what you want underneath: spans survive,
comments survive, a formatter and a refactoring tool see what the compiler saw, incremental
reparse is natural. It is also exactly what you do not want a type checker dispatching on: no
exhaustivity, stringly-typed branch kinds, every phase re-deriving shape from strings.

Every system that made this work kept BOTH layers — Roslyn's red/green trees, rust-analyzer's
syntax + HIR, Swift's libsyntax + typed AST. The lossless tree is the source of truth; a typed
view is projected over it and is what the rest of the compiler consumes.

**UniML already has this shape and it is proven in-repo.** `MarkdownProjection` takes the
lossless CST and produces a typed `MarkdownDocument`; the CST stays canonical. The ScalaScript
case is the same split:

    ScalaScriptDialect     .ssc text  ->  lossless UniML CST     (the storage)
    ScalaScriptProjection  CST        ->  typed AST              (what the compiler consumes)

So "UniML is the parser and the AST" is best read as *one representation, two views* — which is
achievable — rather than *the type checker pattern-matches on `Branch("spike.def", …)`*, which
is not how any shipped compiler does it. If the intent is genuinely the second, that should be
written down explicitly here, because it changes every phase downstream.

## 3.1 Where this meets SSC IR — the seam already exists

`v3/` is being built in parallel and its design document,
[`v3/specs/10-ssc-ir.md`](../v3/specs/10-ssc-ir.md), opens by drawing exactly the line this
decision needs:

> SSC IR is **not** a pruned AST. An AST says what the program *is*; SSC IR says what the machine
> *does*, in order.

That document specifies the executable core in full and refers to "the front" as something
outside itself — "what the front proved", "from a front" — without ever saying what it is. This
decision fills that hole, and the two fit without either giving ground:

    .ssc  ->  UniML dialect  ->  lossless CST  ->  typed projection  ->  SSC IR  ->  run
              \________________ what the program IS ______________/     \_ what the machine DOES _/

**So "UniML is the AST" does not mean "no lowering".** The AST is the typed projection over the
CST; SSC IR is what it lowers into, and the IR spec's own first sentence is the reason the two
are different artifacts rather than one. Reading the decision as "the type checker and the
runtime both walk `UniNode`" would collapse a distinction v3 was built around.

## 4. What is unmeasured and could invalidate the plan

**Parse performance is unknown.** UniML currently accumulates `Vector[VmToken]` for the whole
source and folds it into a tree; nothing in the repo measures how fast that is on real `.ssc`,
and no benchmark compares it with `F`. If UniML is the front end, it runs on every compile of
every file, so this stops being a library question and becomes a compiler question. It should be
measured BEFORE UPR-4a is built, not after — `scripts/bench` and the alternating A/B protocol in
the `performance` skill exist for exactly this.

**Memory shape is unknown too.** A lossless tree holds every trivia token; for a large `.ssc`
that is a much larger object graph than a conventional AST. Also unmeasured.

## 4.1 SSC3-M: what I expect, written before the numbers

Recorded ahead of measuring so the result can disagree with me (`performance` §1.3).
Reference arm is the existing `ParserBench` — v1's `scalascript.parser.Parser` over
`runtime/std/actors.ssc`. Same JVM, same file, same harness as the UniML arm, which is the
point: it isolates UniML's DESIGN from the execution substrate. Comparing against `F` instead
would fold in the v2 VM and answer a different question.

**Expected, and why:**

- **Throughput 2–10 MB/s**, i.e. **3–10× slower than the v1 parser**. UniML materialises a
  `Vector[VmToken]` for the whole source and folds it into a tree, where a conventional parser
  emits AST nodes and drops trivia as it goes. Every source character survives as a `String`
  lexeme inside a `SourceToken` carrying kind, channel and a two-`SourcePosition` span.
- **Retained tree 8–20× the source bytes.** Per-token object overhead (~48–64 B) against an
  average token of a few characters, plus `Vector` node overhead and one edge per child.

**Disqualifying evidence — what would say the plan is wrong:**

- Throughput below **~0.5 MB/s** (a 100 KB file costing >200 ms in parsing alone) makes a
  whole-project compile parse-dominated, and the front-end decision would need rethinking
  before any of UPR-4 is built.
- Retained size above **~50×** makes large projects a memory problem rather than a speed one.

**A measurement hazard to settle FIRST** (`performance` §1.4): the spike parses a SUBSET, and
it is deliberately error-resilient — it never throws, it emits diagnostics and holes. So it will
return a tree for `actors.ssc` whether or not it understood it, and a degraded-parse timing is
not a parse timing. The diagnostic count must be reported next to every number, and an input the
spike parses CLEANLY must be measured alongside the real file.

## 4.2 SSC3-M results — the blocker is not speed

Measured 2026-08-01 on `v1/runtime/std/actors.ssc` (31,403 B, 683 lines) and on a small
clean-subset input, via a hand probe rather than JMH (`performance` §1.4 — run the claim by hand
before it becomes a harness category). **Host load 10.82**, so every TIMING below is provisional;
the losslessness finding is a boolean about token channels and does not depend on load.

**THE DIALECT IS NOT LOSSLESS, and that is the finding.**

On input it understands completely — `status=Complete`, **zero diagnostics** — the spike
reconstructs 57 characters from 84:

    src  def add(a: Int, b: Int): Int = a + b\n\ndef main(): Int =\n  val x = add(1, 2)\n  x * 3\n
    got  defadd(a:Int,b:Int):Int=a+bdefmain():Int=valx=add(1,2)x*3

Token channels for that parse: **`Syntax` 36, `Trivia` 0**. It discards whitespace at lex time.
This is not error recovery losing text and not a bug to hunt — the dialect never emits trivia at
all, by construction. Compare the Markdown dialect, which reconstructs its whole 675-case corpus
exactly because trivia tokens are in the tree.

**That is the property the entire ScalaScript 3 decision rests on.** Losslessness is the reason
to put UniML in the front rather than a conventional parser; today the ScalaScript dialect does
not have it. So criterion (4) of UNIML-SSC3 is currently FALSE, not merely unmeasured, and
UPR-4a is not "move the spike into `src/main` and refactor" — the dialect has to become lossless
first, which touches its lexer and every emission path. UPR-4a's own text already asks for this
("preserve exact raw lexemes"); the measurement is that it has not been done.

**Speed is not the blocker, and my prediction was wrong in both directions.**

| | predicted | measured |
|---|---|---|
| throughput, real parse | 2–10 MB/s | **~0.9–1.0 MB/s** (load 10.8, provisional) |
| retained tree | 8–20× | not measured — see below |

Slower than predicted by 2–10×, but still ~2× above the 0.5 MB/s disqualifying threshold, so it
does not refute the plan. Two caveats that matter more than the number: it is measured WITHOUT
trivia, and adding the missing trivia makes both time and tree size worse; and the reference arm
(v1's `ParserBench` on the same file) was NOT taken, because a comparison at load 10.8 would be
noise. The ratio is still owed.

**A trap worth recording.** The first throughput reading on `actors.ssc` was **14.18 MB/s** and
looked excellent. It was measuring FAILURE: `status=Incomplete`, 85 diagnostics, and 2,118 nodes
for 31 KB of source — the parse bailed out early and the number rewarded it for not working.
Real parsing on clean input is ~1 MB/s, i.e. **14× slower than the flattering number**. Any
front-end benchmark here must report diagnostics and node count beside the time, or it will
measure how fast the parser gives up.

## 4.2b SSC3-M reopened: the ratio and the retained size, predicted before measuring

§4.2 owed two numbers and its throughput reading is now obsolete, so this is a re-measurement
rather than a continuation. Written before running anything (`performance` §1.3).

**Why the old number no longer describes the dialect.** §4.2 measured ~0.9-1.0 MB/s on a dialect
that emitted NO trivia — it was fast partly because it was throwing the source away. Criterion (4)
has since been closed: every one of 1,146 `.ssc` files now reconstructs exactly, which means the
tree carries every whitespace and comment token it used to discard. §4.2 said in advance that this
would make both time and size worse. So a fair reading needs the lossless number, not the old one.

**Conditions.** The previous attempt was taken at host load 10.82 and called itself provisional for
that reason; the reference arm was skipped because a comparison at that load is noise. This one
runs at load ~1.4, and both arms alternate (`performance` §1.1) rather than being measured as a
before-block and an after-block.

**Expected:**

- **Throughput 0.4-0.8 MB/s** — slower than the 0.9-1.0 recorded without trivia, because every
  whitespace run is now a `SourceToken` with a kind, a channel and two `SourcePosition`s. Not
  more than 2x slower, because the lexer already walked those characters; what changed is that it
  keeps them.
- **Ratio against v1's `Parser` 5-15x slower.** v1 drops trivia as it goes and emits a
  conventional AST; UniML materialises a token vector and folds a tree over it.
- **Retained tree 10-25x the source bytes.** §4.1 predicted 8-20x for the lossy tree; trivia adds
  tokens without adding much text.

**Disqualifying evidence, unchanged from §4.1 and restated so this cannot be quietly softened:**
throughput below **0.5 MB/s** makes a whole-project compile parse-dominated and the front-end
decision needs rethinking before UPR-4 is built; retained size above **50x** makes large projects
a memory problem. Note the lower prediction bound and the disqualifying threshold now nearly
touch — if the trivia cost lands at the pessimistic end, this measurement decides something.

**Method, and its one honest gap.** Both arms use JMH with identical settings (3 warmup x 5
measurement x 1 fork, average time). They cannot share a build: `compilerBench` would have to
depend on `unimlScala`, which means editing the root `build.sbt`, and that file is held by another
claim. So the arms are separate JMH invocations on the same machine, alternated, medians compared.
JMH forks a fresh JVM per benchmark anyway, so "same sbt invocation" would not have bought a
shared JVM — but the arms do not interleave within one process, and that is the residual
difference this method carries.

**The trap §4.2 recorded, still armed.** The first reading there was 14.18 MB/s and was measuring
FAILURE — an incomplete parse with 85 diagnostics. Every number below reports diagnostics, status
and node count beside the time.

## 4.2c SSC3-M results — speed is not a constraint, and §4.2's number was an artifact

Measured 2026-08-05, JMH on both arms with identical settings, alternated three rounds, medians.
Host load rose 4.98 -> 6.09 -> 8.40 across the rounds and the readings moved less than 3%, which is
the main reason to believe them.

| | predicted (§4.2b) | measured |
|---|---|---|
| throughput, composed front | 0.4-0.8 MB/s | **13.5 MB/s** over the file, 12.2 over code |
| ratio against v1's `Parser` | 5-15x slower | **0.96x — parity** |
| retained tree | 10-25x source | **29.8x** (829.7 KiB per tree) |

**Both disqualifying thresholds are clear.** Throughput is 27x above the 0.5 MB/s floor that would
have made a compile parse-dominated; retained size is under the 50x ceiling. Nothing here refuses
the front-end plan, and the concern §4 opened with — "parse performance is unknown, and if UniML is
the front it runs on every compile of every file" — is now answered: it costs the same as the
parser it replaces.

**My prediction was not merely off, it was qualitatively wrong**, and the reason is worth more than
the number. I predicted 5-15x slower from the shape of the design — a materialised token vector
folded into a lossless tree, against a conventional parser that drops trivia as it goes. That
reasoning is sound and the conclusion is still false: a `Vector[VmToken]` plus one fold is simply
not expensive next to what a parser does per node. Reasoning from an architecture's shape predicts
its cost badly, which is the whole reason §1.3 asks for the prediction in writing.

**§4.2's "~0.9-1.0 MB/s real parse" was an artifact and should not be carried forward.** Its two
history rows say what happened: the "clean" reading is `0.0897 ms` on an **84-byte** input, where
per-parse fixed cost is the entire measurement — 84 B / 0.0897 ms is arithmetically 0.94 MB/s and
means nothing about throughput. The other row, `2.11 ms` on `actors.ssc`, was rejected as measuring
a failed parse (85 diagnostics) — yet the composed front now takes **2.188 ms** on the same file
for a COMPLETE parse. The rejected timing was roughly right; what was wrong was the arm it came
from, and no reading of a real file at real size was ever taken.

**The bare dialect must never be quoted as the front.** On a literate `.ssc` it is
`status=Incomplete` with 46 diagnostics and runs in 0.713 ms — three times "faster" than the
composed front because it gives up on the 9.9% of the file that is markdown prose. The trap §4.2
recorded is still live, and this is its second instance in the same document.

**Method notes.** Retained size is the live set, not allocation, and the first probe could not be
reported: holding one tree and reading the heap spread 11.8x-47.7x over five rounds because
classloading and JIT structures are in the same delta. Measuring the SLOPE between ten trees and
one cancels them and spreads 28.3-31.8x. The arms cannot share a build — `compilerBench` would need
to depend on `unimlScala` and the root `build.sbt` is held by another claim — so they are separate
JMH invocations, alternated; JMH forks per benchmark anyway, so a shared sbt session would not have
bought a shared JVM.

## 4.3 SSC3-L attempted — three ordered obstacles, measured

Attempted 2026-08-01 and **reverted**. The attempt is recorded because it located the real
dependency chain, and because the obvious fix looks obviously right and will be tried again
otherwise.

**First, a correction to §4.2.** That section said the dialect "never emits trivia at all, by
construction". That is wrong and the difference matters: `SpikeLex` DOES emit `spike.ws` on
`TokenChannel.Trivia`. The loss happens later — `SpikeParse` consumes trivia through
`skipTrivia` and never puts it in its tree, and `SpikeEmit` emits the tree, so trivia is dropped
between the lexer and the CST. Smaller and more fixable than "the lexer discards it".

**The attempt.** Emit over the FULL lexed stream instead of the tree: tokens carry a stable
lexer-assigned `id`, so re-attach the tree's frame transitions to the tokens that earned them
and emit every other lexed token in its source position. No parse rule changes. It worked on
clean input — `lossless=true`, 36 Syntax + 22 Trivia — and cut the loss on `actors.ssc` from
23,647 characters to 43.

**It broke `ScalaSpikeSpec`'s C_min projection** (`compile` became `Nil`), and the reason is
structural rather than a slip:

1. **The parser DUPLICATES tokens.** Measured: for `def f(using ev: Int)(a: Int): Int = a` the
   tree holds 17 leaves but only 16 distinct ids — token `Int` appears twice, because
   `Node.Leaf(c.peek.get, …)` adds a token without advancing the cursor and a later rule adds it
   again. So the tree is NOT a 1:1 map over the lexed stream, an id→event map silently keeps one
   of the two, and the lost frame transitions collapse the projection. It is also a losslessness
   violation in the other direction: reconstruction would print `Int` twice.
2. **The projection must filter by ROLE, not by kind.** With a lossless emitter the CST also
   carries tokens the parse did not use; an `unparsed` one has an ordinary kind like `spike.id`,
   so the existing `kind == "spike.ws"` filter in `kids` lets it through and the projection reads
   it as real structure.
3. **The string lexer stores the DECODED value as the lexeme**, deliberately — its own comment
   says "mirrors ssc1-front". That is the residual 43 characters on `actors.ssc`: quotes and
   escapes never reach the CST. Fixing it moves decoding to the projection, which is where
   Markdown already does it (`unescape`/`decodeText` live in `MarkdownProjection`), and touches
   every consumer of `spike.str` including interpolation.

**Order, forced by the above:** (1) stop duplicating tokens — until each lexed token appears at
most once in the tree, no re-interleaving scheme can be correct; then (2) the emitter change
above plus the role-based filter; then (3) raw string lexemes with decoding moved to the
projection. Doing (2) first is what was just tried and reverted.

## 5. Order of work

1. **Measure first** — parse throughput and retained size of the UniML tree on the real corpus,
   against `F`. A number here can change the design; getting it after UPR-4a would be finding out
   too late.
2. **UPR-4a/4b** — move the spike into `uniml/scala/src/main/`, cross-build it, publish it.
3. **Typed projection** — the ScalaScript analogue of `MarkdownProjection`, which is the actual
   "AST" the decision is about.
4. **Breadth** — grow the dialect's corpus to the full language; until then the declared dialect
   id names the passing subset.
5. **UPR-8b** — F's corpus to `DIFF=HOLE=EMPTY=TIMEOUT=0`, because F is what the dialect is
   differentially checked against; without it there is no oracle.

## 6. What this supersedes

- `uniml/BACKLOG.md` UPR-8a recorded "Option A: self-hosted `F` is the canonical product front;
  the Scala UniML adapter is an oracle/tooling artifact." That remains true FOR v2 and is
  superseded for ScalaScript 3.
- `project-partitioning.md` §8.3 says UniML "is not the language's parser infrastructure and must
  not be tied to a language version." The second half still holds and is the harder constraint:
  UniML must not depend on `v1/` or `v2/` internals even when it becomes the front end. The
  dialect describes the LANGUAGE, not a compiler's data structures.
