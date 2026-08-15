# v3 bugs

Defects whose FIX goes in `v3/`. Layout and entry format: `specs/work-tracking-layout.md`,
`specs/bugs-index.md`. Cross-module defects — the same defect in more than one implementation —
belong in the repository-root `BUGS.md` instead, not here.

Query: `scripts/bugs-report --module v3`.

## v3-workflow-is-cancelled-before-it-can-report — 5 usable verdicts in 100 runs, so v3's gates protect almost nothing

<!-- status: open
     lane: v3
     area: build
     gate: .github/workflows/v3.yml
     found-by: claude-code
     found-at: 2026-08-15 -->

**`.github/workflows/v3.yml` almost never finishes.** Measured 2026-08-15 over its last 100 runs
(2026-08-12 → 2026-08-15, `gh run list --workflow v3.yml --limit 100`):

| conclusion | runs |
|---|---|
| cancelled | 76 |
| failure | 18 |
| **success** | **5** |
| in progress | 1 |

The mechanism is stated in the file itself: `concurrency: group: v3-${{ github.ref }}` with
`cancel-in-progress: true`. Every push to `main` evicts the run in flight, and main's push interval
is shorter than the suite — selftest, executor differential, bridge, parity, front, front report,
plus a `v3/uniml-classpath.sh` step that builds an sbt project first. So the gate that owns v3's
correctness reports on about one commit in twenty, and 76 % of its history reads as RED without
having tested anything.

**THIS REPOSITORY HAS ALREADY DIAGNOSED AND CURED THIS EXACT DISEASE ONCE.** `scripts/smoke-ci`'s
own header records why the push path was restructured on 2026-08-01: *"of the last 100 `ci.yml`
runs: 83 cancelled, 4 failure, 0 success. A suite that cannot finish inside the push interval does
not give a verdict on most commits — and `cancelled` reads as RED, so it was also manufacturing
reds for commits that were fine."* The numbers here are 76/18/5 against that 83/4/0. It is the same
shape, in a workflow that was not part of that change.

**Found while trying to read a verdict on my own commit** (`v3-opaque-type`, `28c34951e`). Its
`v3.yml` run was cancelled; so was the run for `62c3ec562`; so were the twenty most recent runs
without exception. There was no green descendant to fall back on either, which is the usual escape
— the escape assumes SOME run completes.

**A dispatched run does not currently escape it.** `AGENTS.md` recommends
`gh workflow run … --ref main` when a push gives no verdict, on the grounds that `workflow_dispatch`
has its own per-SHA concurrency group. That is true of `ci.yml`; here the group is
`v3-${{ github.ref }}` with no event or SHA in it, so a dispatched run on `main` shares the group
with pushes and is evicted the same way.

**Consequence, and why this is worse than a slow gate.** `v3/front-gate.sh` is the only thing that
runs v3's fixtures in CI, and it is also the only thing that exercises the UniML front there (the
workflow registers it deliberately). Both are effectively unwatched. Two live examples from the
same evening: `v3-workflow-does-not-trigger-on-uniml-and-uniml-is-half-of-what-the-front-gate-runs`
(filed beside this one) means a uniml-only change is not even scheduled; this entry means that when
it IS scheduled, it is cancelled anyway.

**Done when** `v3.yml` produces a usable verdict on a normal commit — measurable the same way, as a
success rate over the next 100 runs rather than as a green on one SHA. Three shapes worth weighing,
deliberately not chosen here because each spends someone else's time: put the SHA in the concurrency
group so runs queue instead of evicting (most faithful, most runner-minutes); drop
`cancel-in-progress` and accept a backlog; or split the suite the way the push path was split on
2026-08-01 — a fast subset on push, the full set nightly and on dispatch — which is the option this
repository already chose once for the same numbers.

## v3-parser-rejects-opaque-type — `opaque` fell through to the expression parser, and the tool could not read its own standard library

<!-- status: fixed
     fixed-in: 28c34951e
     lane: v3
     area: front
     gate: v3/front-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**`opaque type X = Y` was refused; `type X = Y` beside it was accepted and erased.** The two
spellings mean the same thing at this tier, and only one parsed.

    ssc3: std/ui/primitives.ssc:74:26: expected an expression, found =

Column 26 is the `=` of `opaque type Signal[T] = Any`. `opaque` is not in `keywords`, so it fell
through to the expression parser, became a top-level statement reading a name, and the file died on
the `=` that followed — **the fourth occurrence of one pattern**, after `type`, `sealed` and
`extern`, each recorded in the comment block at that branch in `v3/src/Parser.scala`.

**Where it was found is the part worth keeping.** Not by a corpus sweep — by trying to use `ssc3 ir`
as a DIAGNOSTIC on an unrelated bug (`f-placeholder-u0-reduced-but-not-solved`). The tool could not
read the file it was pointed at, so the instrument was unavailable exactly when it was wanted. Seven
occurrences in four shipped modules were blocked behind it: `std/uuid.ssc`, `std/json.ssc`,
`std/graphql.ssc` (two), `std/ui/primitives.ssc` (three).

**This was two fronts disagreeing, not a feature gap.** F already ships the decision:
`specs/v2.2-p6.5-fsub.ssc:2301`, whose `isTypeHead` accepts `type` and `opaque type` alike and emits
nothing for either — "alias — erased, emits nothing". v3's own parser accepted one spelling and
refused the other.

**Fix.** One branch beside the existing `type` alias branch, testing the WHOLE shape before
consuming anything — `opaque`, then `type`, then an alias that reaches its `=` — for the reason the
neighbouring comment already gives: a branch keyed on the word alone would consume nothing on a real
use of the name and the top-level loop would spin. That guard is not theoretical: `std/bench.ssc:26`
declares `extern def Bench.opaque[A](x: A): A`, called from `bench/corpus/streams-pipeline.ssc` and
`bench/corpus/typeclass-monoid.ssc`.

Erasure only — `opaque` asks a type checker to hide the right-hand side outside the defining scope,
and Tier 0 keeps no types at run time and has no checker, so the modifier has nothing to change.
Recorded as a row in `v3/specs/20-core-language.md` beside `generics`, so the tier's answer is
written down rather than inferred from the parser not objecting.

**Gate: `v3/front-gate.sh`, fixture `v3/tests/front/opaque-type.ssc`.** It covers both shapes the
corpus uses — a plain opaque alias and one with a type parameter — and, deliberately, a `def` named
`opaque` that is called, so a future branch keyed on the bare word fails here rather than in a
benchmark. DISCRIMINATION MEASURED, not assumed: with `v3/src/Parser.scala` reverted the fixture
fails at `opaque-type.ssc:10:18: expected an expression, found =`, the same shape as the original;
with the fix it prints `abc/41/5/8`.

**Payoff, measured.** `v3/ssc3 ir std/ui/primitives.ssc` now lowers end to end — 10,167 bytes of SSC
IR with an `(entry 58)` — where it used to produce nothing. `std/ui/content.ssc`, which imports it,
advances past this blocker and stops at a DIFFERENT gap, `content.ssc:57:51: expected an
expression, found [`. That one is not filed here: it is a separate construct and this entry should
not grow to cover whatever the next file needs.

## v3-gates-open-red-in-every-fresh-worktree-because-uniml-cp-is-per-checkout — and nothing warns you until a gate run has been spent

<!-- status: open
     lane: v3
     area: build
     gate: v3/exec-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**`v3/exec-gate.sh` and `v3/front-gate.sh` are RED on a clean, correct tree the first time any agent
runs them in a new worktree**, because `v3/.jars/uniml.cp` is a gitignored build artifact and every
worktree starts without one. Two `.uniml-only` fixtures — `annotation-own-line` and
`object-nested-class` — then cannot be read, and the gates go RED rather than skip, which is
DELIBERATE and correct: a gate that goes green with fixtures unrun reports less than it claims.

**Measured three times in one day**, in three separate worktrees (`f-placeholder-u0-fix`,
`j4-cmp-branch-peephole`, `j4-fuse-decide-once`). Each time the fix is the same and the gate says so
itself — run `v3/uniml-classpath.sh`, re-run — and each time it cost a full gate run to find out.

**Nothing tells you beforehand.** `scripts/new-worktree` does not mention uniml; `AGENTS.md` does not
mention `uniml-classpath.sh` at all. `.github/workflows/v3.yml` gets it right — it has a "Register
the UniML front" step before the gates — so CI never sees this and the cost falls entirely on agents
working locally, which is also why it has survived.

**Not the same as the two entries it looks like.** `BUGS.md`'s `uniml-classpath.sh --check` entry is
about a classpath going STALE when UniML's sources change; `v3-uniml-drops-a-parenthesised-parameter-type`
is about the two fronts disagreeing. This one is about the artifact being ABSENT, which is the normal
state of a worktree on its first day.

**Done when** an agent cannot pay for this twice. Three shapes, any one of which closes it, and they
differ in who pays:

1. `scripts/new-worktree` runs `v3/uniml-classpath.sh` when the tree has a `v3/` — correct but
   expensive, since it builds an sbt project for every worktree including those that never touch v3.
2. The two gates check for `v3/.jars/uniml.cp` FIRST and refuse in seconds with the one-line remedy,
   instead of after a full fixture sweep. Cheapest, and it keeps the RED that is deliberate.
3. `AGENTS.md` names the step beside the worktree mechanics. Cheapest of all and the weakest — it
   relies on being read before the gate is run, which is exactly the order that failed three times.

Shape 2 is the one worth taking: it preserves the current, correct verdict and only moves WHEN it is
delivered. Not attempted here — this entry was written from inside a claim on a different subject,
and both gates are shared apparatus that deserve their own claim.

## v3-workflow-does-not-trigger-on-uniml-and-uniml-is-half-of-what-the-front-gate-runs

<!-- status: open
     lane: v3
     area: build
     gate: .github/workflows/v3.yml
     found-by: claude-code
     found-at: 2026-08-15 -->

**`.github/workflows/v3.yml` triggers on `v3/**`, `v2/**` and itself. It does not trigger on
`uniml/**` — and `v3/front-gate.sh`'s verdict depends on `uniml/**`.**

This is the SAME defect the workflow's own header describes, one path down. That header opens
"`v2/**` IS IN THE TRIGGER, and leaving it out cost a red `main` nobody could have seen coming",
and explains it: the bridge lane runs on the v2 runtime, so `v2/src/Runtime.scala` "is not a
neighbouring project — it is half of what `exec-gate.sh` compares". `charAt` diverged for four
commits before an unrelated push happened to trigger the workflow.

**The same is true of uniml, and it is measured rather than argued.** The workflow's own
"Register the UniML front" step runs `v3/uniml-classpath.sh` before the gates, so in CI `ssc3 run`
takes the UniML front — and `v3/front-gate.sh` is therefore a verdict on
`uniml/scala/.../ScalaSpike.scala` as much as on `v3/src/Parser.scala`. Measured today while adding
`opaque type` support:

| tree | `v3/front-gate.sh` |
|---|---|
| `v3/src/Parser.scala` fixed, uniml untouched | **RED** — `opaque-type.ssc:10:1: unknown name 'opaque'` |
| both fronts fixed | GREEN, 88 cases |

The RED came from a file the trigger does not watch. An edit confined to `uniml/**` can turn v3's
front gate red and this workflow will not run — which is the four-commit blind spot the header was
written about, still open on the other side.

**Sharper still: the same registration makes the gate blind in the other direction.** With uniml
registered, `ssc3 run` uses it, so the gate no longer exercises v3's OWN parser at all. Both halves
of today's fix were confirmed only because they were run with `SSC3_FRONT` pinned, by hand:

    SSC3_FRONT=v3     opaque-type.ssc -> abc/41/5/8   (fails 10:18 with v3's half reverted)
    SSC3_FRONT=uniml  opaque-type.ssc -> abc/41/5/8   (failed 10:1 before uniml's half)

So a regression in v3's own parser passes `front-gate.sh` in any environment where uniml is
registered — which is every CI run.

**Done when** two things hold, and they are separable — take either alone. (1) `uniml/**` is in the
`push` and `pull_request` path filters of `.github/workflows/v3.yml`, for the reason its header
already gives for `v2/**`. (2) `v3/front-gate.sh` runs its fixtures on BOTH fronts when both are
registered, rather than on the default one, so that "the two fronts agree" is asserted instead of
assumed — the `.uniml-only` marker already proves the gate knows the fronts differ in what they
accept. Not attempted here: this was found while landing `v3-parser-rejects-opaque-type`, the
workflow is outside that claim, and (1) makes every push touching `uniml/**` run v3's gates, which
is a cost paid by every agent and should be someone's deliberate decision rather than a side effect
of a parser fix.

## v3-extern-member-in-an-object-has-no-meaning — one front refuses it, the other silently makes it an unpositioned crash

<!-- status: open
     lane: v3
     area: front
     gate: none
     found-by: claude-code
     found-at: 2026-08-14 -->

**`object math: extern def sqrt(x: Double): Double` is not expressible, and the two fronts fail
differently — which is the part that matters.**

    SSC3_FRONT=v3   ssc3: …:2:3: only `def` members are supported in a object at Tier 0, found extern
    uniml (default) (def "sqrt" (params (p "x")) (prim "__throw__" (str "an implementation is missing (`???`)")))

v3's own parser REFUSES, with a position, which is honest. uniml DROPS THE KEYWORD and gives the
member the body it gives any bodyless member — proved by a control: a member written without
`extern` at all projects to the identical `???`. So on the default front the declaration is accepted
and the program dies at run time with **no position and no name**, which `corpus-report.sh`
classifies CRASH rather than UNSUPPORTED.

**Found while wiring `math` (SSC3-14).** Worked around by declaring the four host hooks at TOP
LEVEL — `__mathSqrt`, `__mathFloor`, `__mathCeil`, `__mathRound` — with `object math` delegating.
The `__` prefix is not decoration: the prelude loads for every program, so a bare `sqrt` there would
shadow a program's own.

**A `Lower` change for this was written and REVERTED rather than shipped.** Resolving `hostPrims`
over object members is correct and unreachable — no front produces an abstract object member for it
to act on — and dead code that looks like support is worse than none. The comment at the
`objectDefs` site records this so the next reader does not re-derive it. When a front learns the
keyword, that is the line to add back.

**The fix is a FRONT pair**: v3's parser accepts `extern` as an object member and marks the def
abstract; uniml carries the keyword instead of dropping it. Both, or the divergence just changes
shape.

## v3-a-toplevel-extension-shadows-a-given-instance-one-of-the-same-name — was: v3-handleError-on-a-val-bound-None-matches-no-arm

<!-- status: fixed
     lane: v3
     area: runtime
     fixed-in: 5e8b9c2dc
     gate: v3/corpus-report.sh (std-index, std-monaderror)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN 5e8b9c2dc BY THE PASS ORDER, not by a new table.** `rewriteExtensionCalls` rewrites by NAME and
never looks at the receiver, and it ran BEFORE the pass that can type one. Swapped: the resolver
that knows the receiver's type claims what it can, the name-only rewrite takes the rest — the same
"more specific wins" the subtrait preference encodes one level down. No new guard was needed:
`instanceOnly` already subtracts `defs.map(_.name)`, so a method a top-level extension also provides
cannot be refused by the resolver running first.

**RENAMED, BECAUSE THE TITLE I FILED WAS THE SYMPTOM AND NOT THE MECHANISM.** It read
`handleError-on-a-val-bound-None`, and none of those three words is load-bearing: not `val`, not
`None`, not `handleError`.

**A TOP-LEVEL `extension` AND A `given`-INSTANCE `extension` OF THE SAME NAME COLLIDE, and the
top-level one wins whatever the receiver is.** `std/monaderror.ssc` declares `handleError` twice —
once inside `given optionUnitError: MonadError[Option, Unit]` for `Option`, and once as a top-level
`extension [A](fa: Either[String, A])` for `Either`. A call on an `Option` is rewritten to the
EITHER body, whose `fa match { case Right(_) …; case Left(e) … }` matches no arm. The throw comes
from `Exec.prim` with NO position, which is why the case lands in DIFF rather than UNSUPPORTED.

**Reproducer, fourteen lines, no `std` and no `val`:**

    trait ME[F[_], E]:
      def raise[A](e: E): F[A]

    given oue: ME[Option, Unit] with
      def raise[A](e: Unit): Option[A] = None
      extension [A](fa: Option[A]) def he(h: Unit => Option[A]): Option[A] = fa match
        case Some(_) => fa
        case None    => h(())

    extension [A](fa: Either[String, A])
      def he(h: String => Either[String, A]): Either[String, A] = fa match
        case Right(_) => fa
        case Left(e)  => h(e)

    println(Some(4).he((_: Unit) => Some(0)))    // match: no arm matched

**FOUR REDUCTIONS GOT HERE AND THE FIRST THREE KILLED A HYPOTHESIS EACH**, which is why the original
title was wrong: `case Some(_)` and `case Some(v)` patterns work; the same extension body at top
level works; the receiver shape is irrelevant (`val`-bound `None`, `val`-bound `Some`, and the bare
constructor `Some(42)` all failed identically); a multi-param trait with TWO instances works. Only
the name collision reproduces it.

**A SEPARATE DEFECT — `v3-multi-param-typeclass-never-resolves` — was found and FIXED on the way**
(`headAndArg` keyed the instance table on the string `Option, Unit`), and fixing it did NOT fix
this. Two defects behind one message.

**IT IS INVISIBLE TODAY, which is the only reason it has not been filed before.** Both cases are
refused earlier by the `is provided by a given instance` diagnostic, so the line is never reached.
Fixing that refusal is what exposes this.

## v3-given-instance-as-a-receiver-is-refused — `intSum.combine(a, b)` is a member call, not extension dispatch

<!-- status: fixed
     lane: v3
     area: front
     fixed-in: 86ba35237
     gate: v3/corpus-report.sh (the `is provided by a given instance` histogram line)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN 86ba35237, AT THE THIRD ATTEMPT, AND THE TWO WITHDRAWALS ARE WHY IT IS CORRECT.** Shipped alone
it took DIFF from 3 to 5 twice, because it unblocked cases that then hit two OTHER defects and
produced wrong answers instead of honest refusals. The floor refused it both times; the second
refusal is what sent me looking for the cause instead of the symptom, and found them:
`v3-multi-param-typeclass-never-resolves` and
`v3-a-toplevel-extension-shadows-a-given-instance-one-of-the-same-name`. With both fixed, this arm
gives **N 218 -> 222 with DIFF back to 3 and CRASH 9** — the number predicted in the claim before it
was measured. `std-monaderror` and `std-index`, the two that had become DIFF, now MATCH.

**The receiver IS the instance and its member is refused.** `given intSum: Monoid[Int] with { def
empty; def combine(a, b) }` makes `intSum` an object, so `intSum.combine(intSum.empty, 42)` is an
ordinary qualified call that the object flattening already resolves. It is refused by
`rewriteGivenExtensionCalls`, whose guard asks two true questions — `receiverType` cannot type the
NAME `intSum`, and `combine` is declared only by given instances — from which the conclusion does
not follow.

Three cases, one shape: `std-semigroup-monoid:21` `intSum.combine(intSum.empty, 42)`,
`tagless-multi-file:39` `listLogged.pure(42)`, `std-monaderror:21` `optionUnitError.raise[Int](())`.

**THE FIX IS WRITTEN AND MEASURED AND DELIBERATELY NOT SHIPPED.** One arm, before the refusal, and
the distinction it encodes is the whole of it:

    extension        xs.foldMap(f)        -> listFoldable.foldMap(xs, f)   receiver PREPENDED,
                                                                          it is the value operated on
    instance member  intSum.combine(a, b) -> intSum.combine(a, b)          receiver NOT prepended,
                                                                          it is the OWNER

    val instanceMember = recv match
      case Expr.Name(n, _) if instanceDefs.contains(n + "." + m) => Some(n)
      case _                                                     => None
    if instanceMember.isDefined then Expr.Call(instanceMember.get + "." + m, args, p)
    else <the existing receiverType/pick chain>

`instanceDefs` already holds `object.member` for every given instance, so this adds no table and no
new source of types — it only asks the question in the right order.

**WHY IT IS NOT SHIPPED: the DIFF floor.** Measured 2026-08-15, control against the same tree minus
exactly this arm:

    control (no arm)   PASS 218  DIFF 3  UNSUPPORTED 137  CRASH 9
    with the arm       PASS 220  DIFF 5  UNSUPPORTED 133  CRASH 9

The `is provided by a given instance` histogram line goes from 6 to ZERO — two cases reach PASS, two
move to a different honest refusal, and two become DIFF. Lists compared rather than counts: control
DIFF is {parameterless-def-local, indent-block-statements, indent-config-format}; with the arm it is
those three plus `std-index` and `std-monaderror`. NEITHER was passing before, so there is no
PASS -> DIFF regression — but an honest refusal became a silent wrong answer, and the floor exists
for exactly that trade.

**BOTH NEW DIFFS ARE ONE DEFECT** — `v3-handleError-on-a-val-bound-None-matches-no-arm`, filed
above. Land that first, then this arm, then re-measure; the expectation is DIFF back to 3 with PASS
higher than 220.

## v3-stmt-val-discards-a-type-the-author-wrote — WORKED AROUND, not fixed: the resolver follows the initialiser instead

<!-- status: wontfix
     lane: v3
     area: front
     gate: v3/tests/conformance/std-bifunctor.ssc (via the corpus report)
     found-by: claude-code
     found-at: 2026-08-15 -->

**CLOSED AS `wontfix` IN 32ac55842, and the reason is a count rather than a shrug.** The receiver problem
this entry was filed for is solved by a different route: `rewriteGivenExtensionCalls` now follows a
`val` ONE STEP to its initialiser's constructor, which is source 1 of §4a1 applied one binding away
— no unification, no type variable, no propagation through a function. `std-bifunctor` and
`tagless-sealed-dispatch` both pass; N 216 -> 218.

**Why not the route this entry proposed.** Carrying the author's written type on `Stmt.Val` means
39 use sites over six files, 21 of them PATTERNS on four positional fields that a fifth breaks, and
BOTH fronts populating it — which the default front cannot do, because it drops a parenthesised
parameter type entirely (`v3-uniml-drops-a-parenthesised-parameter-type`). The route taken is one
file and covers STRICTLY MORE: `val xs = List(1, 2, 3)` has no written type at all and resolves.

**What the proposal would still buy, so this is a wontfix and not a never:** a declared type is the
only source when the initialiser's constructor does not name the type — `val f: Foo = makeFoo()`.
Nothing in the corpus needs it today. Reopen with a case, not with a preference.

The original report follows, unchanged.

**`Stmt.Val` is `Val(name, value, mutable, pos)` — there is nowhere to put a declared type**, so
`val t: (Int, String) = (10, "ok")` and `val t = (10, "ok")` reach the lowering as the same thing.
`rewriteGivenExtensionCalls` then has no type for `t` and refuses `t.bimap(…)`
(`tests/conformance/std-bifunctor.ssc:19`).

**THIS IS NOT THE INFERENCE HALF OF THE DEBT, and separating the two is the point of this entry.**
`v3/specs/20-core-language.md` §4a1's first bullet says *"`Stmt.Val` records NO declared type.
`val xs = List(1, 2, 3)` gives the lowering nothing … Inferring `List[Int]` from the initialiser is
type INFERENCE and is not being built."* That sentence covers two different situations and prices
them as one:

- `val xs = List(1, 2, 3)` — nothing was written; recovering `List[Int]` needs INFERENCE, and that
  is the type-checker project, correctly deferred.
- `val t: (Int, String) = …` — the author WROTE the type and the front discarded it. Keeping what
  the source says is not inference and needs no checker. It is the same fact `Param.tpe` already
  carries for a parameter, on the other binder.

**The second is small and is what `std-bifunctor` needs:** `tpe: Option[String]` on `Stmt.Val`,
populated by both fronts, read where the parameter map is built in `rewriteGivenExtensionCalls`.

**MEASURED, so the yield is not overstated:** on v3's own front, with the tuple head fixed, a
PARAMETER declared `(Int, String)` resolves `bimap` and a `val` with the identical declared type
does not. That is the whole difference. Fixing this alone still does not make `std-bifunctor` pass
on the DEFAULT front — see `v3-uniml-drops-a-parenthesised-parameter-type`, which masks it there.

## v3-uniml-drops-a-parenthesised-parameter-type — `def go(t: (Int, String))` loses its type on the default front

<!-- status: open
     lane: v3
     area: front
     gate: none
     found-by: claude-code
     found-at: 2026-08-15 -->

**A two-front pair, and the default front is the one that loses.** uniml's parameter loop reads

    if c.peekKind == "spike.lparen" then skipBalancedParens(c)
    else expectType(c, …).foreach(kids += _)

— so a parenthesised parameter type is CONSUMED AND NOT CAPTURED, and `Param.tpe` arrives as
`None`. v3's own parser keeps it: `skipType` consumes the balanced parens and `typeTextOf`
reassembles the text.

**Measured on one probe, both fronts, after the tuple-head fix landed:**

    def go(t: (Int, String)) = t.bimap(…)
      SSC3_FRONT=v3     (11, ok!)
      uniml (default)   'bimap' is provided by a `given` instance, and the type of the receiver
                        is not known here

One language, two answers, decided by whether `v3/.jars/uniml.cp` exists — invariant I-3.

**It hides the other defect.** `v3-stmt-val-discards-a-type-the-author-wrote` is what stops
`std-bifunctor` on v3's own front; this one stops the same program on the default front for a
different reason, so fixing either alone changes nothing that the corpus can see. Fix both, then
measure.

**The fix is where `def.byname` and `def.vararg` already are:** capture the balanced-paren span as
the parameter's type text instead of skipping it. Both of those roles were added the same way, so
the shape is established rather than novel.

## v2-f-round-is-three-different-roundings-across-the-backends — `rint`, `Math.round` and `.round()` disagree at exactly `.5`

<!-- status: open
     lane: multi
     area: codegen
     gate: none
     found-by: claude-code
     found-at: 2026-08-15 -->

**FOUND BY READING, NOT BY RUNNING, and that is stated up front because it changes what the entry
is worth.** Noticed while adding `f.pow` beside `f.sqrt` in every backend. The four implementations
of `f.round` do not agree:

    v2/src/Runtime.scala:1437                 math.rint(flt(a, 0))                half to EVEN
    v2/backend/jvm/JvmBackend.scala:446       math.rint(_asDouble(a0))            half to EVEN
    v2/backend/swift/…/SwiftRuntime.scala     .rounded(.toNearestOrEven)          half to EVEN
    v2/backend/js/JsBackend.scala:329         Math.round(a0)                      half UP
    v2/backend/rust/RustBackend.scala:1248    as_float(a0).round()                half AWAY FROM ZERO

Three different rules. They agree on every value that is not exactly `.5`, which is why nothing has
caught it: `round(2.5)` is **2** on the VM, jvm and swift, **3** on js, and **3** on rust;
`round(-2.5)` is `-2`, `-2` and `-3`.

**WHY IT MATTERS HERE.** v3's `math.round` goes through this prim, and v3 shipped parity probes
pinning `round(2.5) = 2` and `round(3.5) = 4` on its two lanes — both of which run the VM. The same
program compiled to JS by v2 answers differently. Core IR is meant to be one language whatever
executes it.

**NOT FIXED, and not by me in the same breath as finding it:** which rule is CORRECT is a decision
about the Core IR's contract, not a typo to patch. `10-core-ir.md` says `f.round` and nothing about
its tie-breaking. Whoever owns that spec picks one — `rint` is what three of five already do and
what Scala's `math.rint` and IEEE-754 "round half to even" mean — and then the two odd backends
follow, with a conformance row at `.5` so it cannot drift again.

**Confirm before fixing.** This is a code reading; run one program per backend at `2.5`, `3.5`,
`-2.5` before acting on it. `f.pow` was reported to the owner as "one line" on exactly this kind of
evidence and turned out to be six sites.

## v3-math-pow-fractional-needs-a-v2-prim — a fractional exponent had no answer on the bridge, and no v3-only fix could match

<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: 58f866033
     gate: v3/tests/parity/math-pow-frac.ssc, math-pow-irrational.ssc, math-pow-int-args.ssc
     found-by: claude-code
     found-at: 2026-08-14 -->

**`math.pow` (SSC3-14) is partial: an integer exponent is a multiply loop, a fractional one raises
with a message naming the reason.** The owner asked for that to be fixed. It can be, in one line —
but not in v3.

**WHAT A FIX HAS TO MATCH, measured before choosing one:**

    reference lane   pow(2.0, 0.5) = 1.4142135623730951
                     pow(2.0, 0.1) = 1.0717734625362931
                     pow(10.0,1.5) = 31.622776601683793

libm-exact, bit for bit what JVM `Math.pow` gives. **That rules out every approximation.** A
ScalaScript series, or the repeated-sqrt expansion — which is the tempting one, since `b^f` follows
from the binary expansion of `f` and BOTH lanes already have `f.sqrt` — lands within about 1e-12 and
differs in the last bits, so v3 would disagree with the reference on every fractional pow. Replacing
an honest refusal with a plausible wrong number is the defect this repository keeps paying for.

**AND IT CANNOT BE DONE ON ONE LANE.** v3's executor could call `Math.pow` in a line; the bridge
emits `(prim …)` to v2, and v2 has no `f.pow`, no `f.exp` and no `f.log` — checked name by name
against `v2/src/Runtime.scala`. A v3-only prim runs on the executor and is refused by the bridge,
which is invariant I-3 and exactly the defect closed this morning in
`v3-flatmap-nonlist-lane-divergence`.

**THE FIX**, beside the existing `f.sqrt` at `v2/src/Runtime.scala:1434`:

    case "f.pow" => a => FloatV(math.pow(flt(a, 0), flt(a, 1)))

then v3 wires it exactly as it wired `f.sqrt`: a `hostPrims` entry, an `Exec` case, and a prelude
`def pow(b: Double, e: Double) = __mathPow(b.toDouble, e.toDouble)`. Additive — nothing currently
emits that name.

**FIXED 2026-08-15.** The owner chose the kernel when the question was put to him, and the change
was SIX sites rather than the one line this entry first claimed: the VM, the js, jvm, rust and swift
backends, and swift's allowed-prim LIST. `v2/specs/10-core-ir.md` said "(transcendentals such as
`sin`/`cos`/`log`/`exp` live in the `Mira` prelude, not the kernel)" and was NARROWED in the same
commit rather than left to contradict the code — `f.sqrt` was already kernel and is exactly
`pow(x, ½)`, `pow` is not in that list, and no `f.exp`/`f.log`/`f.pow` existed anywhere, so the
prelude named there was an intention and not an implementation.

All three lanes now agree bit for bit, including the row that fails first if anyone ever swaps in an
approximation:

    1.4142135623730951  1.0717734625362931  31.622776601683793  256  0.125  1  8

N held at 216/369 with DIFF 3 and CRASH 9, which is the expected answer: no corpus case uses a
fractional exponent. Ten v3 gates green; the four non-VM backends are COMPILED and not executed —
`v2/backend/rust` through scala-cli, which is what builds it, since no sbt project references it.

## v3-concat-nonlist-splits-three-ways — `List ++ nonList` wraps on native, refuses on the v2 VM, and v3 must pick one

<!-- status: open
     lane: multi
     area: runtime
     gate: v3/parity-gate.sh (a probe was written and WITHDRAWN — see below)
     found-by: claude-code
     found-at: 2026-08-14 -->

**Found by a parity probe written while fixing `v3-flatmap-nonlist-lane-divergence`, and it caught
the fix itself over-reaching.** Three implementations, three answers, for `val n = 5; List(1,2) ++ n`:

    reference native (bin/ssc run)   1,2,5                     wraps
    v2 VM (v3/ssc3 run --bridge)     RuntimeException:         refuses, UNCAUGHT — a Java stack
                                     expected a list, got 5    trace reaches the user
    v3 exec                          ++ needs a List and got   refuses, with a position
                                     the Int 5

**v2 IS INTERNALLY ASYMMETRIC and that is the root of it:** its `flatMap` WRAPS a non-list (measured
— `v3/ssc3 run --bridge` prints `List(10, 20, 30)` for `List(1,2,3).flatMap(x => x * 10)`) while its
`++` refuses in `Prims.unlistPub`. Two operators, same question, opposite answers.

**WHY v3 REFUSES RATHER THAN WRAPPING, for now.** I widened `++` alongside `flatMap` because the
reference native lane wraps, and the new parity probe went RED in the same run: wrapping made
`ssc3 run` disagree with `ssc3 run --bridge`, which is the exact I-3 violation that commit existed
to remove, moved into a different operator. Invariant I-3 is about v3's OWN two lanes, and v3's
bridge IS the v2 VM, so matching v2 is what agreement means here. The widening was reverted.

**THE PROBE WAS WITHDRAWN, NOT SILENTLY DROPPED.** `v3/tests/parity/list-concat-nonlist.ssc` would
sit RED for a divergence in the SHAPE of two refusals (v3's positioned diagnostic versus v2's
uncaught exception) rather than in the answer, and a standing red teaches everyone to stop reading
the gate. It goes back the day v2 picks a side. Re-create it with:
`val n = 5` then `println((List(1,2) ++ n).mkString(","))`.

**THE FIX GOES IN v2**, making `++` agree with its own `flatMap`; v3 then widens to match in the
same commit. Filed here rather than in the repository-root `BUGS.md` — where a cross-module defect
belongs — because that file was held by the `v3-bridge-effects` claim at the time of writing. Move
it when that releases.

## v3-flatmap-nonlist-lane-divergence — `flatMap` and `++` refused a non-list that v3's OWN BRIDGE accepted (was: v3-multishot-handler-without-a-return-clause)

<!-- status: fixed
     lane: v3
     area: runtime
     fixed-in: 54eccf31f
     gate: v3/bench-corpus-gate.sh (row effect-multishot)
     found-by: claude-code
     found-at: 2026-08-14 -->

**RENAMED, BECAUSE THE TITLE I FILED THIS MORNING NAMED THE WRONG MECHANISM.** It read
`v3-multishot-handler-without-a-return-clause` and said the fixture's handler was missing a return
clause. Nothing about multi-shot handlers was wrong. The defect was `flatMap` on a NON-LIST result,
and the effect fixture was merely the loudest place it showed. The wrong name came from taking the
refusal's own advice at face value — the message said "if this is a handler, its return clause is
what lifts the final value" — instead of measuring another lane. That advice was in the code and it
was wrong, which is the kind of diagnostic that costs more than silence.

**THE FACT THAT SETTLES IT NEEDS NO OTHER IMPLEMENTATION: the two lanes of one compiler
disagreed.** `v3/ssc3 run --bridge` printed `List(10, 20, 30)` for a program `v3/ssc3 run` refused.
That is invariant I-3.

    op                     reference (bin/ssc run)    v2 VM (v3 --bridge)   v3 exec, before
    List.flatMap non-list  List(10, 20, 30)           List(10, 20, 30)      REFUSED
    List ++ non-list       List(1, 2, 5)              —                     REFUSED
    List.zip non-list      refuses "expected a list"  —                     refuses

A non-list is ONE ELEMENT on every lane that answers. `zip` refuses everywhere, so it was already
right and is untouched.

**THREE BEHAVIOURS, NOT TWO, AND COLLAPSING THEM IS WHAT WENT WRONG.** `7730f6039` (2026-08-12)
changed this walk from SWALLOWING a non-list to REFUSING it, on the owner's instruction, and it was
right that the swallow was a defect: a swallowed element made `xs.flatMap(f)` produce the EMPTY list
and a `foldLeft` over that returned a number that looked like an answer. What it got wrong is one
sentence — *"v2's runtime still swallows"*. It does not; it WRAPS, and
`git log -S flatMap 4a93c440c..HEAD -- v2/src/` is empty, so it wrapped then too. Swallow, wrap and
refuse are three different answers and only one of them is every other lane's.

**FIXED in `65a4a6d90` by a SECOND function rather than a flag on the shared one.** `listOut` serves
`flatMap`, `zip` and `++`; relaxing it would have made `zip` agree with nobody. So `listOrOne` wraps
a bare value and is used at the `flatMap` and `++` sites only.

**THE CHECK THAT TELLS WRAP FROM SWALLOW**, and it is the one worth keeping:
`tests/conformance/js-effect-multishot-long-fold.ssc` carries a checked-in **204**, v3 answered 0,
and **0 is also what swallowing produces** — so removing the refusal is not evidence on its own. It
answers 204 now, which means the element is contributing rather than merely no longer being refused.
`bench-corpus-gate` goes 33 -> 34 of 36 rows.

**N DID NOT MOVE — 212/369, DIFF 3, CRASH 9 — AND I PREDICTED THAT IT WOULD.** The prediction was
wrong and the reason is structural rather than surprising: both affected programs are invisible to
`corpus-report.sh` by construction. `js-effect-multishot-long-fold` declares `backends: [int, js]`,
so it is LANE-EXCLUDED and never counts in either direction, and `effect-multishot` lives in
`bench/corpus`, which that report does not read at all. I carried the price recorded in
`7730f6039` (CRASH 3 -> 4) forward without re-checking that the same case is lane-excluded today.
**The gate that covers this defect is `bench-corpus-gate`, and reading N for it was reading the
wrong instrument.**

## v3-mixed-int-double-arith — the executor refused `1 * 2.0` while its own bridge computed it

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     fixed-in: 9447789af
     gate: v3/exec-gate.sh (fixture v3/tests/front/mixed-numeric.ssc, run on BOTH lanes) -->

**Measured 2026-08-11.** `binOp` in `v3/src/Exec.scala` had only HOMOGENEOUS arms — `(VInt, VInt)`
and `(VFloat, VFloat)` — so every mixed pair missed all of them and fell through to the failure
case:

    println(1 * 2.0)   ->  ssc3: Mul on Int 1 and Double 2
    println(1 + 2.0)   ->  ssc3: Add on Int 1 and Double 2
    println(7 / 2.0)   ->  ssc3: Div on Int 7 and Double 2
    var r = 4
    println(r * 1000000.0)  ->  ssc3: Mul on Int 4 and Double 1000000

**This was a two-lane divergence, not only a missing feature — which is what makes it an I-3
defect rather than a gap.** The same file on the same commit:

    v3/ssc3 run --bridge mix.ssc   ->  2 2 3 3.5
    v3/ssc3 exec mix.ssc           ->  ssc3: Mul on Int 1 and Double 2

**The direction was measured before it was chosen**, because the other reading — that v3 is
deliberately strict about numeric towers — would have made a widening fix a regression. It is not:
interp, native and the v2 bridge all widen, and all three print `2 2 3 3.5` for the four lines
above. v3 was alone.

**Fixed** by two arms placed beside the existing `VChar` widening arms, which do the same thing for
the same reason. Deliberately narrow in two ways, both measured rather than assumed:

- **Four operators, not five.** `%` on a Double is refused by EVERY lane (native `TYPEERR: %
  requires Int left operand`, interp `No method '%' on Double`, v3 `Rem on Double`), so widening
  `Rem` would only exchange one refusal's message for another while claiming support no lane has.
- **Arithmetic only, no comparisons.** On mixed comparison the lanes disagree with each OTHER:
  interp evaluates `1 < 2.0` to `true`, while native and v2 refuse it at type-check time with
  `cannot unify Int vs Float`. v3 still refuses it, which is the majority position; picking a side
  of that divergence is a separate decision and is NOT made here. That divergence is between v1 and
  v2 and so belongs in the root `BUGS.md`, where it is NOT yet filed — it is carried as a follow-up
  in `v3/SPRINT.md` rather than referenced here as if it already existed.

  **SUPERSEDED the next day — see `v3-mixed-int-double-compare` below.** The paragraph above was
  right about what it had measured and wrong about what it concluded, and the missing measurement
  was v3's OWN bridge: it widens comparisons too, so the executor's refusal was not "the majority
  position", it was v3 disagreeing with itself. Left in place rather than rewritten, because the
  shape of the error is worth keeping: a survey of the OTHER lanes answered a question that was
  about THIS one.

**Guard.** `v3/tests/front/mixed-numeric.ssc` + `.expected`. Fixtures in that directory are run by
`v3/exec-gate.sh` on the executor AND through the bridge, and the two outputs are compared, so a
future one-lane fix cannot pass it. Verified to fail without the fix: reverting `Exec.scala` alone
makes the fixture die on its first line with `Add on Int 1 and Double 2`.

**Found from.** §55 B1 (one timing wrapper for every column): the shared bench wrapper's last line
is `_ssc_reps * 1000000.0` with an Int counter, so this refusal — not any parser gap — is what
stopped the wrapper from running on v3 at all.

## v3-mixed-int-double-compare — `1 == 1.0` was `false` on the executor and `true` on the bridge

<!-- status: fixed
     fixed-in: d1648e07a
     lane: v3
     area: runtime
     kind: bug
     gate: v3/exec-gate.sh (fixture v3/tests/front/mixed-compare.ssc, run on BOTH lanes) -->

**Measured 2026-08-11**, the day after `v3-mixed-int-double-arith` fixed the arithmetic half and
deliberately left this one alone. The deferral was wrong, and one measurement it had not taken says
why — the same file on the same commit, executor versus bridge:

| expression   | `ssc3 run` (executor) | `ssc3 run --bridge` | Scala |
|---|---|---|---|
| `1 < 2.0`    | `Lt on Int 1 and Double 2` | `true`  | `true`  |
| `2.0 > 1`    | `Gt on Double 2 and Int 1` | `true`  | `true`  |
| `1 == 1.0`   | **`false`**                | `true`  | `true`  |
| `1 != 1.0`   | **`true`**                 | `false` | `false` |

The earlier entry declined to widen comparisons on the grounds that interp and v2 disagree with
each other, so any choice would take a side. That survey asked about the OTHER lanes and the
question was about THIS one: v3's two lanes disagreed on every mixed comparison, which is I-3, and
the bridge's answers are also Scala's.

**Equality was the dangerous half.** An ordering comparison REFUSED stops the program and gets
looked at. `1 == 1.0` returning `false` is a wrong answer that nothing reports: the program simply
takes the other branch.

**Fixed** by adding `Lt/Le/Gt/Ge/Eq/Ne` to the widening arms already in `binOp`, so a mixed pair is
retried as two Doubles.

**Where it is NOT fixed, deliberately.** The first draft widened inside `eq` — the helper shared by
scalar `==`, collection equality and pattern matching — which also made `List(1) == List(1.0)` true.
That is Scala's answer, but measurement says every lane here disagrees with Scala together: the
executor, the bridge AND interp all answer `false`. Widening there would have repaired one
divergence by opening another where the lanes were consistent. Fix what disagrees; leave what
agrees. The both-lanes fixture caught this in one run — it is asserted at its current value so the
regression cannot come back silently.

**Still open, and not v3's:** interp answers `1 == 1.0` with `false` while the v2 runtime answers
`true`, and the native front refuses every mixed comparison at type-check with `cannot unify
Int vs Float`. Filed as `lanes-disagree-on-mixed-numeric-comparison` in the root `BUGS.md`.
