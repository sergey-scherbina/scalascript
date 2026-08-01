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
