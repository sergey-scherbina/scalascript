# ScalaScript 3

`source → UniML tree (the AST) → SSC IR → execute | translate` — every stage owned by the language
core, with no external dependency anywhere in the chain.

**Start here:** [`specs/10-ssc-ir.md`](specs/10-ssc-ir.md). SSC IR is the design of this version;
everything else is an implementation detail it constrains.

| file | what it settles |
|---|---|
| [`specs/00-charter.md`](specs/00-charter.md) | the five invariants and their gates |
| [`specs/10-ssc-ir.md`](specs/10-ssc-ir.md) | the IR — model, instruction set, validation, text form |
| [`specs/20-core-language.md`](specs/20-core-language.md) | the language v3 commits to, and how compatibility is counted |
| [`specs/30-portable-subset.md`](specs/30-portable-subset.md) | the Scala ∩ ScalaScript rules the kernel obeys |
| [`specs/40-front-on-uniml.md`](specs/40-front-on-uniml.md) | the front — UniML supplies the parse machinery and the tree IS the AST |
| [`specs/50-uniml-projection.md`](specs/50-uniml-projection.md) | the projection — which UniML node becomes which v3 node, and what is refused |
| [`specs/60-compile-time-extension.md`](specs/60-compile-time-extension.md) | how a plugin supplies SYNTAX — the rewrite door, and what it deliberately cannot do |
| [`SPRINT.md`](SPRINT.md) | the queue, in the order the work has to happen |
| [`BACKLOG.md`](BACKLOG.md) | parked alternatives, with the trade-off that parked them |

`v3/BUGS.md` is the module's board. This paragraph said there was none — "a board exists only where
something is in it, and the one defect v3 work has found so far is `lane: multi`" — which stopped
being true a long time ago and was left standing; corrected 2026-08-19 while adding the row above.
A repository-wide defect still belongs at the [root](../BUGS.md).

## The short version

An AST says what a program *is*. SSC IR says what the machine *does*, in order: a linear sequence of
instructions per function, each naming its operands and where its result goes. A module in this form
is already a program waiting to be run.

v2's `CoreIR` is a tree of terms walked recursively. That is a good denotational representation and
a poor operational one — evaluation order lives in the walker rather than in the data, and "where
execution currently is" is a position in the host call stack, so it cannot be inspected, moved or
saved. Making it data is what buys the four things this version is for: stability, lightness,
self-sufficiency, correctness.

## Status

`SSC3-0` — specs. Nothing is runnable yet; `bin/ssc3` arrives at `SSC3-3`. Progress is on the
[board](SPRINT.md), and compatibility is a measured `N/381` from `SSC3-5`, never an adjective.
