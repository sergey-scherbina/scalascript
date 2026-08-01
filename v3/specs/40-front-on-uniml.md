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

## 4 · What this depends on, and who owns it

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
