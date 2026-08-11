# The prelude as a correctness test for the compiler

**This file is a working list, owned by `v3-prelude-and-dataset`.** It is separate so that adding to
it never conflicts with anyone else's board edit.

The prelude did not create these defects. It is the first code in v3 that **lives beside an
arbitrary user program and is reused by every one of them**, so it exercises properties nothing else
did: that a declaration remembers which module it came from, that a position belongs to its own
file, that a parameter shadows a name from elsewhere. Each entry below is a place where the
compiler forgets one of those.

The frame that generates most of them, stated once: `Loader.merge` concatenates every unit's
declarations into ONE `Program` and the unit boundary is lost. A prelude is a MODULE, not text
appended to the user's file — and every defect here is the compiler treating it as the latter.

---

## P-1 — a parameter does not shadow a top-level function in the arity check

**Status: FIXED 2026-08-11.** `checkArity` now carries the names bound inside the def — parameters,
lambda parameters, `Try`'s binder, local `val`s and local `def`s — and does not look a bound name up
in the global table. Conservative on purpose: the set is collected over the WHOLE def rather than
per-scope, so it can MISS an error and cannot invent one, and inventing one was the defect.

VERIFIED BY REMOVING THE WORKAROUND, which is what makes it a fix rather than a claim: the prelude's
parameters are back to `f`, `p`, `x`, and front-gate, exec-gate, prelude-gate and the `dataset-*`
cases are green. Per-scope tracking remains as a follow-up.

```text
prelude   def map(f: Any => Any): Dataset = Dataset(items.map(x => f(x)))
user      def f(a: Int, b: Int): Int = a + b
result    call to 'f' passes 1 argument(s), it takes 2
```

The arity check resolves the name against the global function table instead of the enclosing
parameter. Three front fixtures went red at once, and the corpus report shows **3 conformance cases**
blocked on the same message independently of the prelude — so this is not a prelude problem wearing
a disguise, it is a defect the prelude found.

Worked around in `v3/prelude/index.ssc` by renaming parameters to `__fn`, `__pred`, `__x`. THE
WORKAROUND MUST COME OUT when this is fixed, and its removal is how the fix gets verified.

## P-2 — a position from one unit is reported against another file

**Status: FIXED 2026-08-11.** `LowerFail` has carried an `origin` since before the prelude, attached
at ONE place per pass — but only around the lowering of a def's BODY. `checkArity`, `fillDefaults`,
`flattenCurried` and `expandPlaceholders` run EARLIER, so everything they refuse arrived with no
origin and `Main` fell back to the path the user named. Same one-place discipline applied to that
pass. Line numbers were never the problem: they are already counted inside their own file, and it
was the FILE that was lost. Checked in `prelude-gate.sh` with a deliberately broken prelude.

A three-line fixture reported `bitwise.ssc:38:60`. Line 38 is in the PRELUDE. Line numbers must be
counted inside their own file and carry that file's name.

`Loader` already fixes exactly this for IMPORTS — "a parse failure inside an IMPORTED unit must name
THAT unit" — but the repair is in the loader's parse step, and a position that survives into
LOWERING carries no unit at all. `merge` keeps an `origin: Map[String, String]` for non-root `defs`
and for nothing else: not classes, not objects, not traits, and not positions.

## P-3 — the emptiness rule is written twice

**Status: FIXED 2026-08-11.** `Program.hasCode` is the one predicate; `Lower`'s empty-program refusal
and `Loader`'s "does this unit need a prelude" both read it. It lives on `Program` because both
callers are asking about a program. N measured at 204 before pushing.

`Loader` decides whether to load the prelude with its own "no defs and no statements" test;
`Lower.scala:2227` refuses an empty program with another. Two predicates in two files that must
agree. `v3/prelude-gate.sh` fails if they diverge, which makes it survivable, not correct.

## P-4 — a user redefining a prelude name

**Status: FIXED 2026-08-11, on the second attempt, and the first attempt is the more useful record.**

Measured before deciding, which is why it could not be left to intuition: `def` had it BACKWARDS —
the prelude's definition won and the user's own function was silently ignored — while `case class`
was already correct, so the two kinds disagreed about one question. A silent override of code
someone wrote themselves is worse than any collision error.

**The first fix was wrong and reached `main`.** I kept the LAST declaration of every name, which
reads identically in English and is not the same rule: several `def`s sharing a name inside ONE unit
are a working mechanism of this compiler, not a collision. The corpus went **204 -> 132** and I
found out AFTER pushing, because I ran the gates before landing and the corpus in the same command
as the commit. Reverted; N back to 204.

**The rule that holds:** only a name the ROOT ITSELF declares displaces one from another module.
Provenance decides, not list position — the same lesson as P-1 and P-2, which is the pattern this
whole file keeps finding. Applies to imports too: a module you imported can no longer override a
function you wrote. Checked in `prelude-gate.sh`; N unchanged at 204 BEFORE pushing this time.

**The process lesson, which cost more than the defect:** v3's gates do not cover the corpus. Gate
green is not evidence for a change that touches `merge`, `Lower` or the loader — N is, and it has to
be measured before the push, not beside it.

## P-5 — the prelude is parsed and lowered on EVERY invocation

**Status: open, unmeasured with a real library.**

On an empty prelude the difference was below this host's resolution. With a library it is 736 parses
in one corpus sweep. Measure before optimising, and if it costs, cache the LOWERED form rather than
the text.


## P-6 — a call binds to another MODULE's function of the same name

**Status: open. Reported 2026-08-11, and it is the same defect as P-1, P-2 and P-4.**

Two `std` modules declare one name with different arities. They never appeared in a single program
before, so nothing forced the question; with a prelude in every program they do, and a call inside
`scljet/mutate.ssc` now resolves to the OTHER module's function.

The prelude did not create this. `Loader.merge` concatenates every unit's declarations into one flat
table and a call carries no record of the module it was written in, so resolution cannot prefer the
caller's own module. That is the fourth place the same missing thing shows up:

- P-1 the arity check forgot a name was bound locally
- P-2 a diagnostic forgot which file its position came from
- P-4 the merge forgot which unit a declaration came from
- P-6 a call forgets which module it was written in

**Reproduced and measured 2026-08-11**, so the next person starts from a fact rather than a report:

```text
std/scljet/mutate.ssc   def filterRows(rows, drop)                 2 parameters
std/scljet/sql.ssc      def filterRows(rows, where, colNames)      3 parameters
sql.ssc IMPORTS mutate.ssc;  mutate.ssc does not know sql.ssc exists
```

Loading `sql.ssc` pulls `mutate.ssc` in, both `filterRows` land in one flat table, and a call
written inside `mutate.ssc` can bind to the three-parameter one it has never heard of.

**That gives the rule exactly, and it is narrower than "prefer your own module":** a module sees its
OWN declarations and those of what it IMPORTS — never those of a module that imports IT. `mutate.ssc`
must not see `sql.ssc`'s `filterRows` under any circumstances, because the dependency runs the other
way. Direction is what makes this decidable without heuristics.

**The shape of the fix follows the other three: provenance.** Resolution inside unit U must prefer
U's own declarations, then what U imported, and only then the rest. That is more than a filter in
`merge` — it needs the call site to know its unit, which is the piece none of the earlier fixes
needed. Measure the corpus BEFORE landing anything here (P-4's first attempt cost 72 cases).

**A NOTE ON THE TREE, found while reproducing this:** `scljet/` is back under `std/` — it was at the
repo root this morning, which is what the loader's stripped-prefix candidate was added for. That
candidate is tried LAST and costs nothing while the directory sits under `std/`, so it is harmless
either way, but the layout has now moved twice in a day and anything keyed on it should be read
rather than remembered.
