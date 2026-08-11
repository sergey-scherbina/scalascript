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

**Status: FIXED 2026-08-11, on the second attempt, and the first attempt is the more useful record.
SUPERSEDED the same day by P-6, which turned out to be this rule asked from the other end — the
filter became a rename and the code now lives in `Loader.disambiguate`. The rule below is unchanged
and still holds; what changed is that the losing module keeps its own function instead of losing it.**

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

**Status: MEASURED 2026-08-11. It costs 22–26% of an invocation, and 93% of 398 corpus cases pay it
for a library they never mention. The fix is not a cache. What to do instead is a decision for the
owner and is stated at the bottom.**

**The apparatus first, because this host cannot be trusted with a single A/B.** Load averaged 7.4
with three other agents' JVMs resident, and identical code has been measured 2.5× apart here at load
5.5. So every run carries a FLOOR arm: the same configuration against itself, interleaved with the
others. Whatever spread the floor shows is what this host cannot resolve.

```text
  prelude OFF (arm A)   n=12  min=295  median=322  max=465
  prelude OFF (arm B)   n=12  min=298  median=328  max=506
  prelude ON            n=12  min=365  median=438  max=591

  FLOOR   |medianA - medianB| =   6 ms      the same configuration against itself
  SIGNAL   medianON - medianOFF = 113 ms    18.8x the floor
```

**Then the cost was split against a ONE-LINE prelude, which pays the whole mechanism — resolve,
read, parse, merge, and every downstream pass seeing one extra declaration — and none of the
library's size:**

```text
  OFF    median=288      TINY  median=294   mechanism = +6 ms   <- at the floor. Free.
                         REAL  median=369   contents  = +75 ms  <- 93% of the cost
```

**So the mechanism is free and the LIBRARY is the cost.** 109 lines, ~25 methods, 75–113 ms
depending on load — and the prelude adds 517 registers to a 24-register program, which is the size
of what is parsed and lowered every time.

**WHO PAYS, and this is the number that decides everything:** of 398 conformance cases, **28 mention
a name the prelude declares** (`Dataset`, `DsAbsent`, `RuntimeException`) and **370 mention none**.
Ninety-three percent of invocations parse and lower a standard library they cannot reach.

**WHAT WAS NOT RESOLVED, said plainly rather than reported as a number.** I tried to split the cost
into parse and lower by comparing `ssc3 ast` (parses, does not lower) against `ssc3 build` (parses
and lowers). Both use `Front.default`, so the front is not the confound — but `ast` also RENDERS the
tree to canonical text, and with the prelude imported it renders the prelude's tree too. That
rendering lands on the "parse" side of the subtraction and inflates it. All the run supports is a
BOUND: lowering is at least 27% of the prelude's cost, parse-plus-render is the rest. Splitting it
properly needs an in-JVM harness, and it is **not decision-relevant** — see below — so it was not
built.

### What to do — the options, and why the obvious one is wrong

**A cache is the wrong instinct and the measurement says so.** Every invocation is a fresh JVM, so a
cache would have to be on disk; and this repository has been burned twice by digest-keyed caches
serving the wrong state. It would also be keyed on the prelude's CONTENT, never its path, or editing
the prelude serves a stale lowering. Most of all it would optimise work that 93% of programs should
not be doing at all.

**LAZY, NOT CACHED — lower the user's program first, and load the prelude only if it needs one.** For
370 of 398 cases the cost goes to zero with no cache, no key and nothing to invalidate. The natural
trigger is the refusal itself: if the program lowers, it never needed the prelude; if it fails on an
unknown name, retry with the prelude in scope. Note this does NOT require knowing the prelude's
names in advance, which is what makes it cheap — asking "does this program mention `Dataset`" would
mean parsing the prelude to find out, which is the cost being avoided.

**Two things it would change, and neither should be decided quietly:**

1. **A module that declares a name the prelude also declares.** Today the prelude is loaded first and
   therefore OWNS the name (P-6's rule), so the module's copy is renamed. Loading lazily, a program
   that lowers cleanly without the prelude would use the MODULE's. That is arguably the better
   answer — a module you imported is nearer than an ambient library — but it is a semantic change
   and it belongs to whoever owns the language, not to a performance fix.
2. **Which diagnostic a broken program reports.** A failure unrelated to the prelude would be lowered
   twice and the second message is the one the user sees. Fixable — keep the first refusal and
   report it if the retry also fails — but it has to be built deliberately, and `prelude-gate.sh`
   check 8 (an error in the prelude names the prelude) is what would catch getting it wrong.

On an empty prelude the difference was below this host's resolution. With a library it is 736 parses
in one corpus sweep. Measure before optimising, and if it costs, cache the LOWERED form rather than
the text.

**Two inputs this now has that it did not when it was filed, both from P-7's failure:**

- **Not one instruction in the prelude specializes** — 16 `bin ne dyn`, 1 `div`, 3 `eq`, read off the
  specializer while diagnosing the jit gate. So whatever the prelude costs at run time, it is paid
  in the dynamic path, and none of it is recovered by the tier the jit gate measures.
- **The prelude adds 517 registers to a 24-register program** (`long 13 of 24` became
  `long 13 of 541`). That is the size of the thing being parsed and lowered on every invocation,
  stated in the unit the executor allocates in, and it is a better number to reason from than a
  count of files.

**A question to answer before caching anything, and it is not the timing one:** whether the cost is
per INVOCATION or per program. A cache keyed on the prelude's path would serve a stale lowering the
moment the prelude is edited, and this repository has been burned by a digest-keyed cache of a
directory before. Measure first; if it costs, the key has to be the prelude's CONTENT.


## P-7 — a measurement that was about ONE program is now about the program AND the prelude

**Status: FIXED for the case that broke 2026-08-11; the general form is a standing hazard.**

`v3/jit-gate.sh` went RED on all five specializer fixtures, and it was the prelude, not the
specializer. Two families of check, and the numbers say plainly that nothing regressed:

```text
kinds   4a5,24         twenty lines APPENDED; the fixture's own four unchanged
banks   long 13 of 24  ->  long 13 of 541      the NUMERATOR never moved on any of the five
```

The `.kinds` goldens record the kind the specializer assigns each arithmetic instruction in ONE
named program. A prelude is loaded before every program, so its arithmetic joined the list. Fixed by
pinning `SSC3_PRELUDE=` on the two `SpecializeMain` invocations rather than regenerating the
goldens: regenerating would make five expectations a function of the standard library's contents, so
adding one method would rewrite them and the diff would look exactly like a specializer regression.

**THE GENERAL FORM, which is why this is an entry and not a one-line fix.** Anything that measured a
program now measures the program plus the prelude — every golden, every count, every benchmark. The
question to ask of each is whether the prelude is part of its subject. For the specializer it is not:
the fixture IS the program. For the corpus report it is: a case's answer must be right with the
standard library present. P-5's timing measurement has not been asked yet.

**A fact worth keeping from the failure:** those twenty appended lines were `bin ne dyn` sixteen
times, one `div`, three `eq`. **Not one instruction in the prelude specializes.** That is a fact
about the standard library's shape and it belongs to P-5.

## P-6 — a call binds to another MODULE's function of the same name

**Status: FIXED 2026-08-11, and it SUBSUMED P-4 rather than sitting beside it.**

`Loader.disambiguate` runs before the merge: a name declared by more than one unit is a collision,
the OWNER keeps it — the root if the root declares it, otherwise the first declaring unit, because
that is who wins today via `fns.indexOf` — and every other declaring unit's copy is renamed and its
own references rewritten. On a program with no collision the set is empty and nothing runs, which is
the property that keeps N from moving by accident.

**P-4 turned out to be the same rule written as a FILTER.** It dropped the module's copy; dropping
and renaming are identical for everyone calling from outside — the root's def is what they reach
either way — and dropping is a WRONG answer for the module itself, whose own calls then went to the
root's function. The gate probe that found it is three modules and a root, all legal ScalaScript,
refused with `call to 'shared' passes 2 argument(s), it takes 1`. So there is now one pass, not two:
the owner keeps the name, everyone else is renamed, **nobody is dropped**.

**MEASURED, and this is the part P-4's first attempt is a warning about.** Two full corpus runs, the
three touched sources reverted to `HEAD` for the baseline and restored by checksum afterwards:
`v3/corpus-report.sh --names` is **byte-identical** — same N = 204, same DIFF 3, same CRASH 4, same
blocked-message histogram, same identifier histogram. A per-case classification of both fronts over
all 398 conformance files (`both` / `differ` / `v3-only` / `uniml-only` / `neither`) is identical row
for row. Counts alone would not have shown a compensating pair; the row-level diff does.

**It fires on the one real collision in the tree, and that collision was LATENT** — say so rather
than claim a corpus win. `std/scljet/sql.ssc`'s `filterRows` is now `filterRows__2` in the merged
program, confirmed by reading the printed AST. But `sql.ssc` never calls it: the two calls are in
`mutate.ssc`, which is loaded FIRST because `sql.ssc` imports it, so `mutate` was already the owner
and was already right. The reported direction cannot occur through this import edge. A census of the
whole `std` tree finds five names declared in more than one file, two at differing arities
(`filterRows`, `contentRows`) and three at the SAME arity (`link`, `text`, `textOf`) — and the
same-arity three are the dangerous ones, since an arity collision refuses and says so while a
same-arity one runs the wrong function and prints a plausible answer. None of those pairs co-occurs
in one program today. **The defect is real, reproducible and now fixed; its corpus instances are
loaded but not yet fired.**

Guarded in `v3/loader-gate.sh` — a second section, four checks over a four-module probe, plus two
new planted defects in the self-test (the rename disabled; the owner chosen as the LAST declarer
rather than the first, which every "does my own module win" check still passes).

---

**The report, and the reproduction, kept below as written.**

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

### P-6 — the design, settled 2026-08-11

Written down before any code because the risky part of this entry is scope, not difficulty, and
because P-4's first attempt proved that a rule which *reads* right can still be the wrong rule.

**Rejected: resolve names against a per-call-site visibility set.** It is the general answer and it
touches every resolution in the lowering, so every program pays for a defect a handful have. The
blast radius is the whole compiler; the bug is six modules wide.

**Chosen: rename only what actually collides, at merge time.**

1. Group the units' `defs` by name and keep the names declared by MORE THAN ONE unit. On a program
   with no collision this set is empty and NOTHING below runs — which is what keeps N at 204 rather
   than hoping it stays there.
2. For each colliding name `N` and each unit `U` that declares it, rename `U`'s copy to a
   unit-unique symbol and rewrite calls to `N` **inside U's own declarations and statements** to
   that symbol.
3. A unit that declares `N` therefore always calls its own. That is exactly the reported failure —
   a call inside `mutate.ssc`, which declares a two-parameter `filterRows`, binding to `sql.ssc`'s
   three-parameter one.

**What step 3 deliberately does NOT decide:** a unit that calls `N` without declaring it, where two
imported modules both provide one. That needs the import EDGES, not just the unit list, and it is a
separate change with a separate measurement. Today's behaviour is unchanged there and it is
ambiguous; say so rather than pretend the rename closed it.

**Order of work, and the middle step is not optional:**
- a failing test first — two units declaring one name at two arities, the caller declaring its own;
- the rename in `Loader.merge`, which needs a deep expression rewriter Loader does not yet have
  (`Lower.mapDeep` is private and in another file — copy the traversal rather than widen it, since
  Loader must not depend on the lowering);
- `./v3/corpus-report.sh` BEFORE the push. Gates do not cover the corpus. P-4's first attempt was
  gate-green at N = 132.
