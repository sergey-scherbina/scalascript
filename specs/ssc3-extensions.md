# SSC3 — `extension`, and the one rewrite that is sound

> `v3-extension-type-params`. The feature has a reverted attempt on record
> ([`../v3/SPRINT.md`](../v3/SPRINT.md) §51) and this spec exists to say what changed.

## 1 · What is asked for

Two shapes, both already in the conformance corpus:

```scala
extension (s: String) def boxed: Box = Box(s)          // "a".boxed
extension (p: Int)    def ~(q: Int): Int = p * 100 + q  // 3 ~ 4
```

A method call and an infix operator. They are the same thing after parsing — `3 ~ 4` is
`(3).~(4)` — so one lowering covers both.

## 2 · Why the obvious version was reverted, and it is worth reading twice

§51's stage 1 rewrote `v.m(a)` to `m(v, a)` wherever `m` was an extension name and no class in the
module declared it. Measured: **N 188 → 130, CRASH 0 → 131.** An extension named `map` or `join`
rewrote every `.map(…)` in the program, including the ones on lists.

Its conclusion was that the projection "knows what a module DECLARES and nothing about the built-in
vocabulary — and adding that list is not the fix, because **which method a receiver has is a fact
about its runtime value, not its syntax**."

**That conclusion is right about the general case and too strong for this one.** It rules out
deciding *which of several candidates* a receiver has. It does not rule out deciding that there are
**no candidates but one**: if a name is not a built-in method and no class in the merged program
declares it, then no receiver value can have it, and the extension is the only thing it can mean.
The rewrite is then not a guess about a runtime type — it is the observation that nothing else in
the program answers to that name.

## 3 · The rule

Rewrite `v.m(args)` to `m(v, args)` when **all three** hold:

1. `m` is declared by an `extension` in the merged program;
2. no `ClassDef` in the merged program declares a member `m`;
3. `m` is not in the executor's built-in method vocabulary.

Otherwise, leave the call exactly as it is lowered today. Every condition can only *prevent* a
rewrite, so the rule is conservative by construction: it cannot make a call that works today stop
working. What it can do is decline a rewrite that would have been fine, and that shows up as a
program still refused rather than as a wrong answer.

`boxed`, `~`, `~>` and `<~` — the names the two blocked corpus cases use — pass all three.

## 4 · The dangerous drift, and the gate that owns it

Condition 3 needs a list of the executor's built-in method names, and **a hand-written copy of
another file's table is exactly the shape this repository has been burned by**: the copy goes stale
in the direction nobody notices. Here the bad direction is specific — if `Exec` gains a method that
`Lower`'s list does not have, an extension with that name is rewritten and SHADOWS the built-in.

So the list is not hand-written. `v3/extension-gate.sh` derives the names from `v3/src/Exec.scala`
and requires `Lower`'s vocabulary to contain every one of them. A new built-in therefore turns the
gate red on the day it lands, in the file that added it, instead of turning a program's answer wrong
some weeks later.

The gate must be shown to fail: deleting one name from `Lower`'s list, or planting a new arm in
`Exec`, has to be caught. A green from a check that never compared is the failure mode named at the
top of `AGENTS.md`.

## 5 · What this does NOT do

- **No type-directed resolution.** An extension on `Int` and another on `String` with the SAME name
  is refused, because choosing between them is exactly the runtime-type question §51 ruled out. One
  name, one extension.
- **No `given`/`using`.** That is [`../v3/SPRINT.md`](../v3/SPRINT.md) §52 and a different problem.
- **No change to `Exec`.** The dispatch fallback §51 imagined lives in the executor, and the
  executor is another claim's file; this rewrite reaches the same two corpus cases without it.

## 6 · Order

Spec, then the vocabulary gate with its own discrimination proof, then the parser and AST, then the
lowering — measured on `v3/corpus-report.sh` before and after, because the number this feature is
judged on is `N` and §51's attempt moved it the wrong way by 58.
