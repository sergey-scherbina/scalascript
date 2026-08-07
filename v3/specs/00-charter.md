# ScalaScript 3 — charter

> What v3 is for, what it promises, and what it refuses. The design itself is
> [`10-ssc-ir.md`](10-ssc-ir.md).

v3 improves on v2 in four named respects — **stability, lightness, self-sufficiency, correctness** —
while keeping compatibility. Each is stated below as an invariant with a gate, because in this
repository the recurring failure is not broken code but a check that is green because it cannot see.

## The one idea

Source → **UniML tree (the AST)** → **SSC IR** → execution *or* translation. Every stage, and the
IR itself, is defined and owned by the ScalaScript core. Nothing in that chain is delegated to a
third party. v1 parses with scalameta; v3 parses with UniML — our own lossless token→tree framework
— into its own IR, run by its own executor, and emits bytecode or another language's source from
that same IR. The front is [`40-front-on-uniml.md`](40-front-on-uniml.md).

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

UniML already satisfies this invariant, which is most of why the front is built on it: adopting it
costs no new portability work and puts the parser inside the differential gate for free.

**I-3 · The two hosts must agree byte-for-byte.** The same v3 front, built by scalac and run by
ScalaScript 2, emits identical `.ssir` for every corpus file. *This is the gate that makes I-2 worth
having* — the same technique on UniML found 8 defects that point examples had missed. A differential
harness is worth more than any number of hand-written cases, because it compares rather than
pre-judges.

Two clarifications this invariant needed, both bought with a debugging round:

*It is about v3's OWN lanes, not about v1.* v3 accepts several programs the v1 interpreter rejects
(`20-core-language.md` §6). That is not an I-3 violation — I-3 asks that v3's two lanes agree with
each other, and they do. Accepting more than the reference cannot change the meaning of a program
the reference accepts, which is the same safe direction the identifier alphabet takes in §3 of the
core-language spec. Where the reference produces a WRONG ANSWER rather than a refusal, v3 matching
it would be the defect.

*Agreement is not correctness.* 2026-08-05: a lifted lambda took its index BEFORE its body was
lowered, so a lambda nested inside a capturing lambda and its enclosing one shared a number. Both
lanes failed identically — the differential said GREEN, because a differential compares two things
and says nothing when both are wrong the same way. The third opinion was the v1 interpreter. **When
a differential is the only evidence, it is evidence of agreement and nothing more**; a third
implementation, or an oracle outside the pair, is what turns it into evidence of correctness.

**I-4 · No IR is executed unverified.** §4 of the IR spec. Loading always verifies.

**I-5 · Compatibility is a measured number, never an adjective.** v3 defines a core language
([`20-core-language.md`](20-core-language.md)) and publishes `N/M` against the existing conformance
corpus on every run. The gate is **non-regression** on `N`. "Mostly compatible" is not a status.

## Self-sufficiency: `ssc3 run` is v3's own runtime — since 2026-08-07

For most of v3's life `ssc3 run` meant *build v2 Core IR, then hand it to the v2 VM*. It no longer
does. `run` executes on v3's own runtime, and the bridge is reached explicitly as `run --bridge`.

**The switch was made on a measurement, and the first answer to that measurement was no.** When the
question was first asked the executor scored 34 of the corpus against the bridge's 48, with 14
crashes — while a 42-probe parity gate read 40 of 42 green. The probe set had been built from the
corpus's method names by FREQUENCY, and the corpus lives in the tail. Three things had to be true
before the switch, and all three are now measured rather than assumed:

1. both lanes give the same number: **48, DIFF 0, CRASH 0**, and `corpus-report.sh --exec` keeps
   measuring it;
2. the executor implements **every primitive v3's lowering can emit** — so no capability exists that
   a v3 program can reach through the bridge and not through the executor;
3. the parity gate still COMPARES two lanes. It briefly did not: with `run` switched, it was
   comparing the executor against itself, and it was green for it. It now names `run --bridge`.

**The bridge is not retired and is not a fallback.** It is the COMPATIBILITY lane: `ssc3 build`
emits v2 Core IR, which is how v3 has the whole v2 backend fleet without having written a backend.
Invariant I-3's differential is between these two lanes, so both have to stay real.

## How v3 measures itself, and the ways the apparatus has lied

Every number in these specs comes from a script. Those scripts have been wrong more often than the
compiler has, and each way they were wrong is written down here because the next one will rhyme.

**A gate that cannot fail is not a gate.** Before any harness is trusted, plant the defect it exists
to catch and watch it go red. Three in this module have gone green while proving nothing: an F4
dual-run gate that emitted nothing on a clean tree, a bridge refusal probe whose "untranslatable"
instruction had quietly become translatable, and a front-diff gate that would have compared one
front against itself. The first two were found by planting; the third says so in its own output.

**Compare OUTPUT, never an exit code.** v1 signals failure by printing a `Stub` sentinel at exit 0,
and v2 has done the same. A compile that dies mid-way also exits non-zero with an EMPTY stdout,
which a gate reading exit codes calls a crash and a gate reading output calls a wrong answer — the
second is the one you can act on.

**A contended host does not report contention; it reports a defect.** Twice on 2026-08-05 the
apparatus produced a total regression that did not exist: a corpus report run beside three gates
said `CRASH 360, N = 0` against a true `N = 26`, and a `PATH` prefix meant to fix a broken
environment silently swapped bash 5 for bash 3.2 and turned four smoke checks red. The mechanism in
the first case took a third round to find: `scala-cli run` recompiles into a SHARED `.scala-build`,
and a concurrent invocation deletes it underneath the running one, so the program prints nothing.
The rule that follows is operational, not clever: **one gate run at a time, jars cached per source
digest, and never a corpus report beside a gate.**

**A differential proves agreement, not correctness** — see I-3.

**A measurement answers only the question it literally asked.** A census of one refusal message is
evidence about that message and nothing else. Three times in this module the bucket's LABEL named a
construct the corpus barely used and hid a single line reached by 116 files through an import:
`found case` was `case object`, `found <newline>` was a `val` with its value on the next line, and
`unterminated character literal` was a block comment — the apostrophe in the English word
`journal's`, inside `/* … */`, which the lexer had never learned to skip. **A missing form does not
announce itself; it announces whatever it stumbles into first.**

## What v3 is not

Not a rewrite of v1 or v2, and not their replacement on any schedule. v1 remains the compatibility
lane and v2 the self-hosting lane; both keep working while v3 grows. v3 earns its lane by the number
in I-5, not by announcement.

## Order of work

The IR is designed first and the compiler second, and not the other way round. A front written
against an IR that later turns out to be wrong is work thrown away twice — once writing it, once
finding out. The sequence is in [`../SPRINT.md`](../SPRINT.md).
