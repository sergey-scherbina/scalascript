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

*Not yet verified:* that a v3 built against those jars runs. It cannot be verified from here today —
the jars are not built in this worktree and sbt would contend with the live `uniml-typed-ast-complete`
claim. It is the first step of the integration batch, not an assumption inside it.

## 5 · What UniML still owes v3 — measured from UniML's OWN numbers, 2026-08-05

The projection is in good shape and the work behind it is careful: 1,185 files, 212,885 nodes,
**99.7% coverage, 672 admitted gaps, 0 silent drops**. The finding that makes those numbers
trustworthy is theirs, not v3's: **the coverage metric used to reward dropping nodes** — a dropped
subtree is absent from BOTH sides of the ratio, so losing a construct RAISED the figure. They found
it, fixed it, and admitted gaps went UP (2,943 → 2,964) as a result. A/B rather than assertion:
re-planting `enum.case` turns the census red at 142.

What still blocks the swap, in priority order for v3:

| # | what | why v3 needs it |
|---|---|---|
| 1 | **`for`** (37 gaps) | v3 supports `for … do` / `yield`, multiple generators and `if` guards, with a green fixture on both lanes. A swap today LOSES it. |
| 2 | **`try` / `throw`** (32 + 56) | same — `try-catch.ssc` is green on both lanes today. |
| 3 | **`spike.pfblock`** (185) | `{ case (k, v) => … }` as a lambda. v3 has it; it is how every destructuring callback is written. |
| 4 | **a nested `def`** (48) | v3 lifts local functions; losing them silently would be worse than losing them loudly. |
| 5 | **the ALPHABET** (`UNIML-SSC3-ALPHABET`, still open) | v3's requirement, and a language-level one: route character classification through the host and the same source lexes differently on JVM, JS and the v2 VM. The table is [`20-core-language.md`](20-core-language.md) §3 — every line a range comparison, no Unicode tables on any host. |

Two more that v3 can live without today but should be recorded:

- **an import's PATH is not in the CST.** `parseImportStmt` consumes the dotted path without
  attaching it, so `import a.b.c` and `import x.y` are indistinguishable. UniML filed this
  themselves and modelled it as the contentless `NoOpDecl` it really is, which is the honest
  choice. **It does not block v3**, because v3's imports are markdown links (`[names](path.ssc)`)
  read from the source TEXT by `Loader`, not from the tree — a fact worth stating so nobody
  sequences the swap behind it.
- `givenobj` 45, `effectdecl` 42, `focusmarker`/`direct`/`try` 32 each, `summon` 26 — all outside
  SSC3 core Tier 0 today, so they are not v3's blockers. `given`/`using` in particular stays refused
  by v3 on its own grounds: it needs type-directed resolution, which Tier 0 does not have.

**The ordering is a request, not an instruction.** UniML's claim is theirs; this table exists so the
sequencing question has a measured answer instead of two agents guessing at each other.

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
