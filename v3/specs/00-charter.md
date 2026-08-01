# ScalaScript 3 — charter

> What v3 is for, what it promises, and what it refuses. The design itself is
> [`10-ssc-ir.md`](10-ssc-ir.md).

v3 improves on v2 in four named respects — **stability, lightness, self-sufficiency, correctness** —
while keeping compatibility. Each is stated below as an invariant with a gate, because in this
repository the recurring failure is not broken code but a check that is green because it cannot see.

## The one idea

Lexer → AST → **SSC IR** → execution *or* translation. All four stages, and the IR itself, are
defined and owned by the ScalaScript core. Nothing in that chain is delegated to a third party.
v1 parses with scalameta; v3 parses with its own parser, into its own IR, run by its own executor,
and emits bytecode or another language's source from that same IR.

## Invariants

**I-1 · The core has zero external dependencies.** The kernel — lexer, AST, IR, verifier, executor,
backends — builds with an empty `libraryDependencies`: the JDK and nothing else. Everything outside
(postgres, netty, sqlite, crypto providers) reaches the language only through `Prim` and the plugin
SPI. *Gate:* a build of the v3 kernel with an empty dependency list, plus a check that no kernel
source imports outside `java.*`/`scala.*`.

This is the precise form of "no external dependencies". The unqualified version is not achievable
and not desirable — `std` plugins legitimately wrap real libraries. What must hold is that the
**boundary** is mechanical and checkable.

**I-2 · The core is written in the Scala ∩ ScalaScript subset.** One source tree, two hosts: it
compiles with Scala 3 *and* runs on ScalaScript 2. Self-hosting is then not a rewrite but a
consequence — the day `ssc3` compiles the portable subset, it compiles itself.
Rules: [`30-portable-subset.md`](30-portable-subset.md).

**I-3 · The two hosts must agree byte-for-byte.** The same v3 front, built by scalac and run by
ScalaScript 2, emits identical `.ssir` for every corpus file. *This is the gate that makes I-2 worth
having* — the same technique on UniML found 8 defects that point examples had missed. A differential
harness is worth more than any number of hand-written cases, because it compares rather than
pre-judges.

**I-4 · No IR is executed unverified.** §4 of the IR spec. Loading always verifies.

**I-5 · Compatibility is a measured number, never an adjective.** v3 defines a core language
([`20-core-language.md`](20-core-language.md)) and publishes `N/M` against the existing conformance
corpus on every run. The gate is **non-regression** on `N`. "Mostly compatible" is not a status.

## What v3 is not

Not a rewrite of v1 or v2, and not their replacement on any schedule. v1 remains the compatibility
lane and v2 the self-hosting lane; both keep working while v3 grows. v3 earns its lane by the number
in I-5, not by announcement.

## Order of work

The IR is designed first and the compiler second, and not the other way round. A front written
against an IR that later turns out to be wrong is work thrown away twice — once writing it, once
finding out. The sequence is in [`../SPRINT.md`](../SPRINT.md).
