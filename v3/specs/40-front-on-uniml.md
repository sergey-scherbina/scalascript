# The front is built on UniML: the CST is the storage, a typed projection is the AST

> Sergiy's call. Invariants I-1 and I-2 of [`00-charter.md`](00-charter.md); the IR this front
> lowers to is [`10-ssc-ir.md`](10-ssc-ir.md).

v3 does not define its own parser and does not define an AST independent of what was parsed. Source
becomes a **lossless UniML CST**, a **typed projection** over that CST is the AST, and lowering
reads the projection and emits SSC IR.

```text
source  →  UniML CST (lossless, canonical)  →  typed projection (the AST)  →  SSC IR  →  execute
```

**This section changed on 2026-08-01, and the earlier version was worse.** It said the UniML tree
*is* the AST and that lowering pattern-matches `Branch(kind: String, …)` directly, accepting the
loss of exhaustivity as a deliberate cost (§3). `specs/uniml-ssc3-frontend.md` argued that the cost
does not have to be paid, and that argument is better than mine:

> A lossless CST is the right STORAGE for an AST and the wrong INTERFACE for one.

Every system that made this work keeps BOTH layers — Roslyn's red/green trees, rust-analyzer's
syntax + HIR, Swift's libsyntax + typed AST — and **UniML already has the shape, proven in this
repository**: `MarkdownProjection` takes the lossless CST and produces a typed `MarkdownDocument`
while the CST stays canonical. Adopting it costs nothing that was not already built and removes a
weakness I had merely mitigated.

Sergiy's decision is unchanged by this and is what both documents implement: UniML supplies the
parsing and the AST. The refinement is *how* — one representation, two views, rather than one
untyped tree that every later phase re-derives shape from.

**It changed a SECOND time on 2026-08-05, and again because the facts moved.** Both earlier versions
said v3 defines no AST type of its own. That was true and cheap when it was written: v3 had an
interim parser and a small lowering. It is neither now. `Lower.scala` is the largest and most
measured part of v3, and the behaviours in it were not designed — each was found by running the
corpus and cost a debugging round: layout continuations for `else` and for a trailing operator,
alternative patterns, default and named arguments, block comments, character literals, statement
bodies, tuple destructuring. Forty-five fixtures hold them, on both lanes.

Replacing the AST **and** rewriting the lowering in one step puts all forty-five at risk
simultaneously, and a differential between v3's two LANES cannot see it — both lanes would move
together. So:

> **UniML's typed projection is mapped into v3's `Ast`. The lowering does not change.**

This is not a retreat from Sergiy's decision; it is how to carry it out reversibly. UniML still
supplies the parsing and the tree. What changes is that the swap is a PROJECTION rather than a
transplant, which makes it gateable: the same source through both fronts must produce the same
`Ast`, printed and compared as text. That gate is the acceptance criterion in §7, and it is the only
form of this work that can be trusted, because it is the only one that can fail loudly.

Whether v3's `Ast` survives once UniML's projection is proven equivalent is a LATER question with an
answer that costs nothing to defer — and it is a much smaller question once the two are known to
agree.

## 1 · Why UniML rather than a bespoke ADT

- **It is ours and it has no dependencies.** Invariant I-1 asks that nothing in the chain be
  delegated to a third party. UniML is a standalone lossless token→tree framework living in
  [`uniml/`](../../uniml), independent of ScalaScript with a binding to it.
- **It already compiles on both hosts.** UniML is dual-compilable — the same sources build with
  Scala 3 and run on ScalaScript 2 — which is precisely the subset invariant I-2 requires of the v3
  kernel. Adopting it costs no new portability work, and the differential gate (I-3) covers the
  front for free.
- **This was always its purpose.** UniML was built so that a ScalaScript parser could eventually be
  written on it. v3 is that parser.

## 2 · What UniML supplies

```scala
enum UniNode:
  case Branch(kind: String, edges: Vector[UniEdge], span: SourceSpan, origin: Origin)
  case Token(value: SourceToken)

final case class UniEdge(role: Option[String], child: UniNode)
enum Origin:  case SourceBacked;  case Synthetic(reason: String)
```

Four properties that a hand-rolled AST would have to grow one at a time:

- **`span` on every branch** — diagnostics point at source, and so can IR-level errors, because the
  lowering can carry the span through.
- **Roles on edges** — `role = "cond"` / `"then"` / `"else"` names a child's meaning without a
  positional convention that drifts.
- **`Origin`** — a node the parser synthesised (error recovery, desugaring) is distinguishable from
  one the user wrote. Error messages that point at code nobody typed are a class of bug this
  removes by construction.
- **Losslessness** — comments and whitespace survive, so `ssc3 fmt` and exact round-trip are
  reachable later rather than a rewrite.

And one property that matters more than any of them: there is **one** representation of what the
source said. A token stream plus a separate tree is two, and two copies of one fact is the shape
this repository has paid for repeatedly.

## 3 · What UniML does NOT supply, stated plainly

**The ScalaScript grammar.** Operator precedence, indentation sensitivity, the shape of every
construct in [`20-core-language.md`](20-core-language.md) — all of that is v3's work. UniML gives
the machinery and the tree; it does not know Scala.

**Exhaustiveness — and this is where the two-layer split earns its keep.** A
`Branch(kind: String, …)` is an open universe where a sealed ADT is closed, so code dispatching on
the CST directly cannot lean on the compiler to prove every node kind is handled. **The typed
projection is a sealed ADT, so lowering gets exhaustivity back**, and the openness stays where it
belongs: in the storage layer, which is supposed to accept whatever the source contained.

Two things still hold at the seam, because the projection is where an unknown kind arrives:

1. the CST kinds the projection accepts are a **closed table**, and an unknown kind is refused
   loudly rather than falling through — an unhandled construct must be an `UNSUPPORTED` naming
   itself (`20-core-language.md` §4), never a silent miss;
2. the **IR verifier remains the backstop** (I-4), now for lowering bugs rather than for missing
   cases the compiler can already prove absent.

## 4 · How the two are BUILT together — measured, not assumed

`v3/src` is compiled by scala-cli with no dependencies, UniML is an sbt project. The obvious idea —
point scala-cli at both source trees — was tried on 2026-08-05 and **does not work**:

```
uniml/core/.../dialect/Literal.scala:3: import scalascript.uniml.*
    value uniml is not a member of scalascript.uniml.dialect.scalascript
```

UniML has a package `scalascript.uniml.dialect.scalascript`. Inside it, `import scalascript.uniml.*`
resolves the leading `scalascript` to the INNERMOST one, not the root. sbt never sees this because
it compiles each subproject as its OWN compilation unit; merging the sources into one makes the
shadowing reachable. The dialect also pulls in `markdown`, `yaml` and `json` — 33 files and ~10,200
lines against v3's 14 files and ~5,200.

**So UniML is consumed as JARS built by sbt, never by merging sources.** The driver already caches
jars keyed on a source digest (`v3/ssc3`), and this is the same mechanism with one more input. It
also keeps invariant I-1 honest in the way that matters: UniML is ours and has no third-party
dependencies, so depending on it adds no third party — but it is a separate build, and pretending
otherwise would have produced a compile error at the worst possible moment.

**VERIFIED 2026-08-06.** `sbt "export unimlScala/Compile/fullClasspath"` yields seven entries, and a
scala-cli program compiled against them parses real ScalaScript and projects it:

```
scala subtrees = 1
  Def(f, Vector(Param(x, Some(TypeRef(Int, …)), None, false, …)), …)
  TopExpr(ValDef(xs, Apply(Ident(List, …), Vector(IntLit(1, …), …)), …))
  TopExpr(For(Vector(ForGen(Vector(y), Ident(xs, …), …)), …))
  CaseClass(P, Vector(Param(a, Some(TypeRef(Int, …)), …), …), …)
  TopExpr(Match(Ident(xs, …), Vector(Arm(PatCons(PatVar(h, …), …), …), …), …))
```

`def`, `val`, `for` with its generator, a `case class` with a defaulted parameter, a `match` with a
cons pattern — with spans, from the classpath, with no sbt at run time. The integration question is
answered.

**Two API facts the probe found, and both would have cost a round during the swap:**

1. `SpikeTyped.module` takes a SCALA SUBTREE, not the composed document's root. Handed the root it
   returns `UnsupportedDecl(markdown.paragraph, …)` — correctly, because at that level the document
   IS markdown. The subtrees are the branches whose `kind` starts with `spike.`.
2. **A BARE `.ssc` yields ZERO scala subtrees.** Measured: bare → 0, ```` ```scalascript ```` → 1,
   ```` ```scala ```` → 1. But fences have been OPTIONAL in this project since 2026-07-09 — a bare
   `.ssc` is the program in its entirety, which `v3/src/Source.scala` implements and 6 of the 383
   conformance cases rely on. Either v3 fences bare text before handing it over, or the composer
   gains a bare mode. It is cheap on v3's side and belongs in §5 either way, because a front that
   silently reads a whole program as prose is the quietest failure in this document.

## 5 · What UniML still owes v3 — CORRECTED 2026-08-06

**The previous version of this section was wrong, and the way it was wrong is worth keeping.** It
listed five blockers — `for`, `try`/`throw`, `spike.pfblock`, a nested `def` — as constructs UniML
had not modelled. They were modelled. I built that table from the prose of UniML's sprint file
without opening `SpikeTyped.scala`, and the prose was stale by ONE COMMIT: `b5035d15d` had already
closed every one of them. Reading the code takes a minute and would have caught it.

That is the same error this repository keeps paying for, applied to a sibling's work instead of my
own: **a list inherited rather than measured.** `30-portable-subset.md` says it about gap maps and
`SSC3-4` says it about UniML's own gaps; it applies to a colleague's sprint notes too.

The measured state, from UniML's own gate:

| | |
|---|---|
| files / nodes | 1,185 / 221,824 |
| coverage of what the dialect parses | **100.0%** |
| admitted gaps | **28** |
| silently dropped subtrees | **0** |

**No construct the CST has is unmodelled.** The 28 are parse-recovery holes — an infix whose
operand the DIALECT itself diagnosed — which makes them a BREADTH question about the dialect, not a
typing question about the projection.

So the remaining question for v3 is not "has UniML modelled it" but **"does the ScalaSpike dialect
PARSE what SSC3 core needs"** — a different question with a different owner. **Measured 2026-08-06**,
by running the dialect over every file and counting `spike.error`:

| corpus | files | files with a parse error | `spike.error` nodes |
|---|---|---|---|
| `v3/tests/front/` — v3's own core, both lanes green | 46 | **1** | 1 |
| `tests/conformance/` — the whole thing | 390 | **2** | 4 |

Three constructs in total, and two of them v3 does not support either:

| construct | where | v3's? |
|---|---|---|
| `if c then a(i) = v` — an INDEX assignment as a single-line branch body | `assign-body.ssc` | **YES** — v3 core, fixture green on both lanes |
| `x += 1` — compound assignment | `js-compound-assign.ssc` | no — outside Tier 0 |
| `def <~>(b: Int)` — a user-defined symbolic operator | `js-symbolic-infix-operator.ssc` | no — outside Tier 0 |

The narrowing is worth recording because it is not what the file name suggests: `a(1) = 4` on its
own line parses, and `if c then n = 5` parses. Only the COMBINATION fails.

## 5a · Why the projection reads the TYPED AST and not the CST — Sergiy's question, 2026-08-06

*"Что насчёт того, чтобы строить AST для v3 прямо из UniML во время парсинга?"* Three architectures
are being compared, and the measurement that separates them is what the two trees actually contain.

**C — build v3's `Ast` inside the parser's actions.** Rejected, and not narrowly. It destroys the
lossless CST, which is UniML's entire value and the thing every measurement in §5 is taken against;
it couples UniML's Scala dialect to v3's types, so UniML stops being a reusable framework and
becomes v3's front; and it removes the ability to ask *did everything the parser saw reach the
AST?* — the question whose answer was **6,641 silently dropped subtrees** the day somebody asked it.

**B — CST → v3's `Ast`, one projection instead of two.** This is the real content of the question
and the saving it points at is real. It is still the wrong trade, because it re-opens exactly the
hole UniML has just spent a sprint closing: a CST branch is `Branch(kind: String, …)`, so a `kind`
v3 forgets is a SILENT MISS. There is no exhaustiveness to lean on, and the failure mode is not a
crash — it is a smaller tree that still lowers, still runs, and prints something plausible. The
typed layer plus `Unsupported` is what converts that into a countable number, and a coverage metric
that REWARDED dropping is what it looks like when nobody is counting.

**A — CST → typed projection → v3's `Ast`.** Chosen. The second projection is checked by the
compiler: a `SpikeAst` node v3 forgets is a compile error.

**The cost the question is pointing at is real, and here it is measured.** `SpikeAst` has 47 node
kinds and keeps Scala's SURFACE shapes: `For`/`ForGen`, `PartialFn`, `Tuple`/`TupleVal`,
`PatTuple`/`PatCons`, `IndexAssign`, `CompoundAssign`, `Infix`/`Prefix`, and `Select` + `Apply` +
`BlockApply` as three separate nodes. v3's `Ast` has about 25 and has already desugared all of them
— `for` into `map`/`flatMap`/`foreach`, a tuple into `Call("TupleN")`, `a(i) = v` into `Update`,
three call shapes into one `MethodCall`.

So the second projection is **where v3's desugaring decisions live. It is not overhead; it is the
work.** Building from the CST would not remove that work — it would remove the compiler's help
while doing it.

**What is NOT rejected** is the stronger version of the same instinct: delete v3's `Ast` and lower
from `SpikeAst` directly, so the language has ONE tree. That may well be right, and it is sequenced
rather than refused — `Lower.scala` is the largest and most measured part of v3, and rewriting it
against a different tree in the same step as the parser swap is precisely the risk this plan is
shaped to avoid. Once §7's differential is green, that question is small, cheap and reversible;
today it is none of those.

## 5b · So: the complete list, and it is short — ALL EIGHT DISCHARGED, measured 2026-08-07

**Every item below now holds, asserted by `uniml/scala/src/test-jvm/…/ssc/Ssc3HandoverSpec.scala`.**
That spec exists because this section says of its own list that it is "stale by construction —
re-measure, do not inherit", and that instruction had no apparatus: each item was fixed under its own
claim, each claim recorded its own verdict, and nothing afterwards asked all eight the same question
at once. The drift is visible in item 8 below, which still reads "still open" while
`uniml/SPRINT.md` has had it `[x]` since 2026-08-05. The list is left as written, with this note
above it, because the ARGUMENT for each item is why the gate asserts what it asserts.

**The differential could not have caught the drift.** `v3/front-diff.sh` reports
`GREEN (48 fixtures, 1 front, agree 0)` — `v3/src/Front.scala` lists `v3` as the only runnable
front, so the gate that "will decide the UniML front swap" currently compares nothing. It says so
in its own output, which is the right behaviour; the point is that a green run there is not
evidence about UniML today, and `Ssc3HandoverSpec` is what replaces it until the second front runs.

**So the remaining blocker is on v3's side, not UniML's**: the `SpikeAst` → v3 `Ast` projection
(§5a, contract in `50-uniml-projection.md`, four open questions to measure first) and the wiring in
`Front.parse`. Nothing on the list below is waiting on UniML.

### The list, as originally written

1. **`if c then a(i) = v`.** One construct, one line of one fixture, and the only breadth gap in
   SSC3 core. Everything else the dialect already parses.
2. **A BARE mode for the composer.** A `.ssc` with no code fence yields zero ScalaScript subtrees;
   fences have been optional here since 2026-07-09. v3 can fence the text itself, so this is a
   request — but the failure it prevents is a whole program read as prose with no diagnostic.
3. **A `trait` VANISHES, and nothing in UniML's own gates can see it.** Measured 2026-08-06 by the
   front differential. The CST for `trait Shape:` with a body is `spike.sealed` — the same kind the
   dialect gives imports and anonymous `given`s — and the projection maps it to `NoOpDecl`, which is
   documented as "parsed, and genuinely carrying nothing". **The trait's METHODS are not in the CST
   at all**, so:

   - the `spike.error` count does not see it: nothing failed to parse;
   - the silent-drop census does not see it: `NoOpDecl` is a modelled node, and there is no subtree
     under it to drop;
   - the coverage figure does not see it: it is `typed`, not a gap.

   A construct that is consumed into a contentless node is invisible to all three measurements at
   once. This is the same shape as the coverage metric that REWARDED dropping — a measurement can
   only see what the representation admits exists.

   It matters most of anything on this list: `trait` gates 137 corpus cases for v3, and v3's traits
   carry the dispatch that makes them worth having (`20-core-language.md` §2).
4. **`ValDef` does not record MUTABILITY.** `ValDef(name, rhs, span)` has no `isVar`, so `var x = 0`
   and `val x = 0` project identically. Found 2026-08-06 by the front differential, not by reading:
   v3's front printed `(var "counter" …)` and UniML's printed `(val "counter" …)` for the same
   source. It is a WRONG ANSWER rather than a smaller tree — a `var` projected as a `val` makes
   every later assignment to it a refusal.
5. **`ObjectDecl` carries no `case` marker**, so `case object A extends K` and an empty
   `object A` are indistinguishable. v3 needs the first as a NULLARY CONSTRUCTOR — it is a value,
   and it was 116 corpus cases. The projection cannot guess: an empty object is useless but legal,
   and mapping it to a constructor would invent a value the author did not write.
6. **`0 +: xs` projects as `Infix("::")`.** The dialect normalises `+:` to `::`. For a `List` they
   agree, which is why it survives; they are different methods on anything else, and v3 lowers them
   differently — `::` builds a `Cons`, `+:` is a method call with the operands SWAPPED.
7. **A CHARACTER literal is projected as `IntLit`.** `'x'` arrives as `IntLit("120")`, so it is
   indistinguishable from the integer `120`. Also a wrong answer rather than a loss: `println('x')`
   prints `x` and `println(120)` prints `120`, and the language's `Char` is exactly an integer that
   prints differently — which is why the distinction has to survive the projection.
8. **`UNIML-SSC3-ALPHABET`** — one character classifier, no host `Char` calls, the table in
   [`20-core-language.md`](20-core-language.md) §3. ~~Still open~~ **— landed 2026-08-05; this line
   is the stale one the note above is about.** The only item on this list with a
   consequence for the LANGUAGE rather than for a file: route classification through the host and
   the same source lexes differently on JVM, JS and the v2 VM.

Everything else is v3's own work: `SpikeAst` → v3's `Ast`, which is §5a's subject and where v3's
desugaring lives. Its contract — node by node, with the refusals and the four open questions to be
measured before any of it is written — is [`50-uniml-projection.md`](50-uniml-projection.md).

**A correction to an earlier note in this file.** It said an import's PATH was absent from the CST.
That was true when written and is no longer: `ImportDecl(path, selectors, wildcard)` is real, and
measured 2026-08-06 it yields `ImportDecl(a.b.c, [], false)` and `ImportDecl(a.b, [x, y], false)`.
I had repeated a sprint note instead of reading the code — the second time in this file, which is
why the correction is kept rather than edited away.

What IS true, and verified rather than assumed: **the markdown link-import yields nothing.**
`[Node, Cluster](std/mapreduce/cluster.ssc)` produces only a `TopExpr`, because the link sits
OUTSIDE the code fence and belongs to the markdown layer, which the ScalaScript dialect never sees.
That is correct behaviour rather than a gap — `Loader.importsOf` scans the raw source TEXT and
always has — and it means the projection does not build the module graph. Recorded because the
opposite assumption is the natural one and would have produced a front that resolved no imports
while looking correct. Detail in [`50-uniml-projection.md`](50-uniml-projection.md) §6.

## 6 · What v3 does on its own side, before any of that lands

Nothing in §5 blocks the apparatus. Under `ssc3-core`, in this order:

1. **A canonical text form for `Ast`** — the same role `Text.write` plays for the IR. Without it two
   fronts cannot be compared at all; with it the comparison is a diff a person can read.
2. **A front SEAM** — one entry point from source text to `Program`, so a second implementation is a
   parameter rather than an edit.
3. **The differential gate** (§7). It lands with ONE front and is honest about that: it is a change
   detector until the second front exists, and the file says so rather than implying coverage it
   does not have.

## 7 · Acceptance: the swap is a NUMBER, not a judgement

The UniML front is adopted when, for every case in `v3/tests/front/` and every corpus case v3
currently compiles, **both fronts produce the same `Ast`, byte for byte in canonical form**. Until
then `SSC3_FRONT=uniml` selects it and the default does not, so the two can be compared at any time
and a regression is one environment variable away from being isolated.

Two rules this repository paid for, applied here from the start:

- **Observe the gate failing first.** Before the front-diff gate is trusted, plant a divergence — a
  dropped modifier, a reordered argument — and watch it go red. A gate that has only ever been green
  is a hypothesis.
- **Do not compare exit codes.** Compare the printed `Ast`. A parser that refuses and a parser that
  returns an empty tree both exit 0 on some path, and this session already lost a round to an empty
  output being read as a wrong answer.

## 8 · What this depends on, and who owns it

**Making UniML ready for this role is a separate piece of work under a separate claim**
(`uniml-ssc3-frontend-readiness`, item `UNIML-SSC3`, spec `specs/uniml-ssc3-frontend.md`). `SSC3-4`
consumes the result; it does not do that work, and must not duplicate it. The v3-side requirements
were handed over rather than left to be guessed: the tree is the AST, the lexer may not use host
`Char` classification — the alphabet that replaces it is [`20-core-language.md`](20-core-language.md) §3 —
recovered nodes are
`Origin.Synthetic`, and a dialect's `kind` vocabulary needs to be enumerable so §3's closed table is
constructible.

The gaps below are UniML's own, from `specs/uniml-portable-gapmap.md` and **stale by
construction** — re-measure, do not inherit:

- **`Array`** — the floor for both UniML's compat layer and the SSC IR frame, and being fixed under
  `SSC3-1`. Green on int, js, jvm and the v2 **legacy** front; the v2 **F** front is the default and
  is still red, so `uniml/v2-smoke/gap-array.ssc` stays red on the default front until that lands.
  Declared in `tests/conformance/generic-ctor-and-array-alloc.ssc`.
- **Anonymous instances** (`new Trait:`) — UniML's `Processor.andThen` builds them, and they lower
  to `unbound global` on v2. Resolution on the UniML side was named classes; confirm before the
  front is written against it.

`SSC3-4` re-measures both before a line of grammar is written, for the reason `30-portable-subset.md`
gives: building on an inherited list of what does not work is how you discover, three files in, that
two of them work fine and a fourth does not.
