# v3 bugs

Defects whose FIX goes in `v3/`. Layout and entry format: `specs/work-tracking-layout.md`,
`specs/bugs-index.md`. Cross-module defects — the same defect in more than one implementation —
belong in the repository-root `BUGS.md` instead, not here.

Query: `scripts/bugs-report --module v3`.

## v3-capability-list-outlived-the-divergence-it-declared — the front-capability gate was RED in CI for four rows that had already closed

<!-- status: fixed
     fixed-in: 4222bd4d0
     lane: v3
     area: build
     gate: v3/front-capability-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**The job `the two fronts accept the same programs` failed on every v3 run that got far enough to
report.** Not for a divergence that appeared — for four that DISAPPEARED and were still declared:

    FAIL   std-ui-i18n     no longer diverges; drop it from KNOWN_uniml in this commit
    FAIL   tkv2-component  no longer diverges; drop it from KNOWN_uniml in this commit
    FAIL   tkv2-offline    no longer diverges; drop it from KNOWN_uniml in this commit
    FAIL   tkv2-webauthn   no longer diverges; drop it from KNOWN_uniml in this commit

That is the gate working exactly as designed — `check_set` is bidirectional so a declared row that
stops diverging is as red as an undeclared one that starts. It was red for five days because the
red was invisible: the suite was being cancelled before it could report
(`v3-workflow-is-cancelled-before-it-can-report`), so nobody saw the gate that was telling them.

**ONE CAUSE, NOT FOUR.** `ssc3 ast` compares `Loader.closure`, which FOLLOWS IMPORTS, and all four
cases reach `std/ui/primitives.ssc` — three `opaque type` declarations that v3's own parser refused
and UniML's accepted. The divergence was in an import, not in any of the four cases. `28c34951e`
taught BOTH fronts `opaque type` and closed all four at once, and did not take the rows out.

**The claim is bounded by a check, not by the story sounding right:** no other corpus case reaches
`primitives.ssc`, so the set that stopped diverging is exactly the set that imported it, with
nothing left over. This is also what a row-per-file list buys over a count — a ceiling would have
dropped by four and said nothing about one cause.

**Fixed** by removing the four rows, with the mechanism recorded above the list so the next reader
does not re-derive it. Gate GREEN locally: "the two fronts differ on exactly the declared rows".

## v3-workflow-is-cancelled-before-it-can-report — 5 usable verdicts in 100 runs, so v3's gates protect almost nothing

<!-- status: open
     lane: v3
     area: build
     gate: .github/workflows/v3.yml
     found-by: claude-code
     found-at: 2026-08-15 -->

> **THE CHANGE IS LANDED (`ac924a416`) AND THIS STAYS OPEN UNTIL THE NUMBER MOVES.** `cancel-in-progress`
> is now `false`. The effect on the cancelled rate cannot be read from a single push, so closing this
> on the strength of the edit would be closing it on an intention. Re-run
> `gh run list --workflow v3.yml --limit 100 --json conclusion` after a day and close it when
> `success` is no longer 5 in 100.
>
> **The diagnosis below is wrong in a way that changed the fix, and is corrected here rather than
> quietly superseded.** It says "main's push interval is shorter than the suite". Measured: runs are
> created at **1.7 per hour** over 58 hours — one every ~35 minutes — while completed runs take a
> median of **26 minutes**. On average a run has time to finish. What kills them is that arrivals
> come in BURSTS, several `v3/`/`v2/` commits within minutes, each evicting the last; cancelled runs
> die at a median of 5.9 minutes. So the remedy is not "run less" but "do not kill a run that is
> already executing" — and that distinction is why the trigger could be WIDENED in the same commit
> instead of narrowed.
>
> **Not `no group at all`, which is what `smoke.yml` carries and what this entry points at.** That
> position rests on a premise this suite does not share: smoke is ONE job of ~6 minutes that skips
> sbt on a cache hit, so 25 runs/hour is affordable. This is ~26 minutes and builds an sbt project
> before its first gate. `ci.yml` uses `false` for its heavy jobs for the same reason. The cure was
> already in the repository; the premise had to be checked before copying the conclusion.
>
> If `cancelled` stays high after this, concurrency is no longer the cause and the next suspects are
> the 45-minute job timeout and the account's concurrent-job budget — neither of which that line can
> fix.
>
> **RE-MEASURED 2026-08-15, AND `cancel-in-progress: false` IS NOT ENOUGH — the paragraph above sends
> the next reader to the wrong suspects.** Two runs whose sha CONTAINS `ac924a416` were cancelled
> anyway (`063849133`, `c9ee83035`), so it is still concurrency and not the job timeout. The timing
> says how: every cancelled run dies within ~1–20 seconds of the NEXT run being created, and the only
> runs that reach a conclusion are the ones with no push behind them. `cancel-in-progress: false`
> protects the run that is EXECUTING; a run that arrives while the group is busy goes **pending**, and
> a pending run in a concurrency group is cancelled by the next arrival regardless of that setting.
> With ~26-minute runs arriving in bursts, most runs are pending, so most are still cancelled.
>
> So the choice is real, not a setting: keep the group and accept that only the newest pending run
> survives — the TIP is always tested, intermediate commits never are — or drop the group and pay for
> parallel runners. Whoever picks, measure again; the number to watch is unchanged.
>
> **THE TRADE IS TAKEN, 2026-08-15: `v2/**` IS OUT OF THE PUSH TRIGGER AND THE SUITE RUNS NIGHTLY.**
> Owner's decision, asked because the alternatives cost different things and neither is free. The
> number that decided the shape: of the last 300 commits on `main`, 48 trigger this workflow — 32
> touch `v3/`/`uniml/`, 16 touch `v2/` and nothing under `v3/` or `uniml/`, and the intersection is
> ZERO. So this removes one arrival in three, and a third fewer arrivals is a third fewer pending
> runs for the next push to evict.
>
> **What it costs is named rather than hidden:** those 16 are exactly the population that once let a
> lane divergence live for four commits — a change to the bridge's runtime with no v3 file beside
> it. They are DEFERRED, not dropped: `schedule` at 03:17 UTC runs the whole suite nightly, so v2
> drift surfaces within a day, and `workflow_dispatch` is the button for anyone who wants the answer
> now. Editing a v3 file to provoke a run is the thing that button exists to replace.
>
> **CLOSE THIS** when `gh run list --workflow v3.yml --limit 100 --json conclusion` shows `success`
> well above 5 in 100 — the same instrument as the original measurement, at least a day later so the
> window is not dominated by today's bursts. If `cancelled` is still the majority with a third of
> the arrivals gone, then arrivals were never the binding constraint and the next thing to measure
> is how long a run sits PENDING before it starts.
>
> **What the change DID buy, measured the same day:** the suite now reaches a conclusion often enough
> to report, and what it reports is RED — 10 failures in the last 40 runs where previously there were
> almost none to read. The failing job is `front-capability-gate.sh`, for a real and self-inflicted
> reason: see `v3-capability-list-outlived-the-divergence-it-declared`. A suite that cannot finish
> cannot tell you it is red, which is the cost this entry is about.

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

<!-- status: fixed
     fixed-in: 5758cdb0d
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
delivered.

### CLOSED 2026-08-15 — `5758cdb0d`, shape 2, and both directions measured

Both gates now refuse the moment they learn the front is unregistered, through the `ssc3 fronts`
call they already make — not by testing for the file, because the driver owns that decision and a
second copy would be a second place to be wrong.

| gate | without uniml | with uniml |
|---|---|---|
| `exec-gate.sh` | **refuses in 35 s** (was 244 s to the same verdict) | does not fire — GREEN, 85 cases, 244 s |
| `front-gate.sh` | **refuses in 1 s** (was 51 s) | does not fire — GREEN, 89 cases, 51 s |

The 35 s is the v3 kernel compile the gate needs anyway; `front-gate` answers in one second against
a warm cache. The saving is larger than the table suggests, because the old cost was paid TWICE —
once to discover it and once after the fix.

**The RED did not change and was never the defect.** A gate that goes green with fixtures unrun
reports less than it claims, so an unregistered front still fails these gates deliberately. Only the
delivery moved.

**Proven in both directions rather than only the useful one:** with the classpath moved aside the
refusal fires and the gate exits 1; with it restored the refusal does not fire at all — `grep -c`
of its message returns 0 — and both gates complete green. A fail-fast that also fired on a healthy
tree would have been worse than the problem it replaced.

CI was unaffected by construction and that is why this survived: `.github/workflows/v3.yml`
registers the UniML front in a step of its own before the gates, so `uniml=1` there and this path is
unreachable. The whole cost fell on local work and never appeared in a run anybody reviews.

## v3-workflow-does-not-trigger-on-uniml-and-uniml-is-half-of-what-the-front-gate-runs

<!-- status: fixed
     lane: v3
     area: build
     gate: .github/workflows/v3.yml
     found-by: claude-code
     found-at: 2026-08-15
     fixed-in: ac924a41628a9103772d3fc5a90f52ae096ff412 -->

> **FIXED `ac924a416` — and the cost was measured before adding, not after.** Of the last 300 commits on
> `main`, `uniml/` is touched by ONE, and by ZERO that do not already touch `v3/` or `v2/`. So the
> trigger adds no runs to today's traffic; what it closes is the case where somebody works on uniml
> alone, which is exactly when nobody is watching the front gate's other half. That is the same hole
> the `v2/**` note in that file describes, and there it cost four commits of undetected lane
> divergence.

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

## v3-extern-member-in-an-object-has-no-meaning — one front refused it, the other silently made it an unpositioned crash

<!-- status: fixed
     fixed-in: 9ca1f4da2
     lane: v3
     area: front
     gate: v3/front-gate.sh (v3/tests/front/extern-object-member.ssc)
     found-by: claude-code
     found-at: 2026-08-14 -->

**`object math: extern def sqrt(x: Double): Double` was not expressible, and the two fronts failed
differently — which was the part that mattered.**

    before   SSC3_FRONT=v3   …:2:3: only `def` members are supported in a object at Tier 0, found extern
             uniml (default) (def "sqrt" (params (p "x")) (prim "__throw__" (str "an implementation is missing (`???`)")))
                             → at run time: a JVM stack trace, no position, no name

    after    SSC3_FRONT=v3   …:4:26: the host function 'fsx.exists' is not implemented on this lane
             uniml (default) …:4:22: the host function 'fsx.exists' is not implemented on this lane

**FIXED IN THREE PLACES, AND EACH ONE MIRRORS A RULE THAT ALREADY EXISTED AT TOP LEVEL** rather than
inventing one:

- `Parser.scala` steps over `extern` before a member `def` — the same two-token test it already
  applies at top level. **Only when the member list belongs to an `object`.** The loop is shared
  with `trait` and `class`, where a body-less def means "dispatch to a subclass"; accepting the
  keyword there would erase a word instead of honouring it, which is this bug moved to a new site.
  Verified: `trait Fs: extern def exists(…)` is still refused, by name, with a position.
- `UniFront.scala` projects a body-less OBJECT member as `hostGap`, not as `???`. `???` is right in
  a trait or a class and wrong here for a reason that is structural: an object is a NAMESPACE,
  nothing extends it, so "no body" has exactly one reading left — the same one it has at top level.
- `Lower.scala` partitions the object's members and sends the abstract ones through `resolveExtern`,
  **the same function top-level externs use**, keyed on the QUALIFIED name. No new branch: a dotted
  key would bind exactly as a plain one does, and with no key the extern gets a body that throws
  naming itself and its position.

**Qualified rather than plain, and the alternative is the reason to say so.** Keying on the member
name would let `object anything: extern def exists(p: String)` silently capture the host `exists`
that `hostPrims` already answers, handing a program a working function it never asked for. A
qualified key is one somebody has to write on purpose.

**A SECOND DEFECT WAS UNCOVERED BY THE FIRST FIX, and it is why this is not a two-line change.**
With both fronts carrying the keyword, the refusal still arrived at RUN time — positioned, but as a
stack trace. Two walkers decide the difference: one computes reachability from the entry, the other
turns a reachable gap into a refusal at the call. **Both matched only `Expr.Call`**, and a call to
an object's member is still a `MethodCall` at that stage, so the gap was reachable and unrefused.
Adding the one form to BOTH is what turns it into a lowering refusal. It stays an
under-approximation — dispatch on a value is still invisible, which is what keeps the 113 importers
of `jvm-vfs.ssc` compiling.

**The `Lower` change this entry recorded as REVERTED is the one that landed**, now that a front can
reach it. The comment at the `objectDefs` site is replaced by the code it described.

**Measured after, all four v3 gates and the corpus:** front-gate GREEN 91 (the new fixture refuses
with a position), exec-gate GREEN 86, front-capability-gate OK, corpus 223/369 with CRASH 9 —
**unchanged**, which is the number that matters for the reachability widening: seeing a new call
form could have refused programs that used to run, and refused none.

**The prelude keeps its `__mathSqrt` workaround, deliberately.** It could now be written as
`object math: extern def sqrt(…)`, and it should not be: the delegating body carries `.toDouble`,
and that widening is load-bearing — v2's `flt` refuses an Int, so `math.sqrt(16)` died on the bridge
while the executor answered 4. Moving the declaration into the object would move that one line of
`.ssc` both lanes run into a builder in Scala. The workaround is not what this entry was about.

## v3-the-fleet-wires-two-plugin-modules-of-twenty-six — wired seven; the rest is not a wiring problem

<!-- status: fixed
     lane: v3
     area: runtime
     fixed-in: 93726d1da
     gate: v3/corpus-report.sh (with v3/.jars/plugins.cp present)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN 93726d1da by adding the five modules the corpus actually reaches — ui, content, json,
crypto, actors — chosen by measurement rather than by reading the directory:**

    two modules    PASS 228  DIFF 3  UNSUPPORTED 126  CRASH 9  EXCL 3
    seven modules  PASS 230  DIFF 3  UNSUPPORTED 122  CRASH 9  EXCL 5

Both floors held and the lists are identical — the same three DIFFs, the same nine CRASHes, all nine
being the single `v2 bridge V-0 does not translate perform/handle` cause.

**THE REFUSAL SHORT-CIRCUITS, SO THE FIRST CENSUS WAS A LOWER BOUND.** Before the change the names
were `element ×7`, `signal`, `contentDocument`, `sha256` and the rest; after it the list is not those
minus the fixed ones but a DIFFERENT set led by `forJsonView ×8`, which no earlier sweep could see
because the case died on an earlier name. Anyone re-measuring this bucket should expect the same:
each round of unblocking reveals the next layer, and no single count is the total.

**WHAT REMAINS IS NOT UNWIRED, IT IS UNIMPLEMENTED.** `forJsonView`, `actorGroupTell`,
`webauthnRegister` and `webauthnChallenge` appear in NO plugin source anywhere in the tree, so no
module list answers them. Adding the remaining nineteen modules buys nothing measurable and costs an
sbt build each in `v3/plugin-classpath.sh`, which is why the list stays at what the corpus reaches.

**`add` LOOKED LIKE A SIXTH MODULE AND IS NOT.** A source grep put it in http-fast, but there it is
`registerTaggedMethod("WsRoom", "add")` — a method on a tagged handle — while the refusing case is
`node-basic.ssc:20:9`, a graph node's `add`. Wiring http-fast for it would have been a change
justified by a name collision.

**AND THAT NAMED A REAL BOUND ON THE WHOLE PATH: `V2PluginRegistry` HAS THREE TABLES** — `handlers`,
`taggedApply` and `taggedMethods` — and `v3/plugins/V2Fleet.scala` bridges only the first. So a
plugin's tagged-handle surface is invisible to v3. It FAILS SAFE rather than silently: lowering gates
on the same table it bridges, so a tagged name is refused at compile time on BOTH lanes and I-3
holds. Filed as `v3-the-fleet-bridges-one-of-the-registrys-three-tables`.
## v3-the-fleet-bridges-one-of-the-registrys-three-tables — tagged handles are invisible to v3

<!-- status: open
     lane: v3
     area: runtime
     gate: v3/corpus-report.sh (with v3/.jars/plugins.cp present)
     found-by: claude-code
     found-at: 2026-08-15 -->

**`ssc.V2PluginRegistry` keeps THREE tables** (`v2/src/Runtime.scala:1283`): `handlers` for plain
Prim ops, `taggedApply` for calling a tagged handle, and `taggedMethods` for `(tag, method)` pairs
such as `registerTaggedMethod("WsRoom", "add")`. `v3/plugins/V2Fleet.scala` snapshots only
`handlers`, so every plugin surface expressed as a handle with methods — sockets, rooms, sessions,
cursors — is unreachable from v3 no matter how many modules `v3/plugin-classpath.sh` lists.

**IT FAILS SAFE, WHICH IS WHY THIS IS A GAP AND NOT A DEFECT.** `Lower.resolveExtern` emits a `Prim`
only for names in the same table the adapter bridges, so a tagged name is refused with a position at
compile time — on BOTH lanes, since lowering is shared. Invariant I-3 holds and no program gets a
wrong answer; the surface is simply smaller than "the v2 plugins work in v3" suggests.

**WHAT IT WOULD TAKE.** A handle is a `ForeignV` on the v2 side and has no v3 counterpart at all —
`V2Fleet.toV3` converts a `ForeignV` only when it holds an `ArrayBuffer`, and refuses the rest by
name deliberately. So this is not a table to copy but a representation to design: v3 needs a value
that carries an opaque v2 handle plus the tag, and lowering needs to admit `x.method(…)` on it. No
corpus case reaches it today, so it is filed rather than taken.

## v3-plugin-fleet-regresses-four-cases-when-enabled — it does not; the fleet now RAISES N by five

<!-- status: fixed
     lane: v3
     area: runtime
     fixed-in: fbf16fb97
     gate: v3/corpus-report.sh (with v3/.jars/plugins.cp present)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN fbf16fb97. Measured on the rebased tree, the only difference being whether
`v3/.jars/plugins.cp` exists:**

    fleet off   PASS 223  DIFF 3  UNSUPPORTED 132  CRASH 9  EXCL 2   <- the control, identical to before
    fleet on    PASS 228  DIFF 3  UNSUPPORTED 126  CRASH 9  EXCL 3

Both floors held and the three DIFFs are the SAME three the control reports, so the fleet costs
nothing and gains five. Six cases leave the host bucket; five pass and one differs on a case that
does not hold the v2 lane.

**THE FOUR WERE THREE SEPARATE DEFECTS AND ONLY THE FIRST WAS THE ONE THIS ENTRY PREDICTED.**

1. *The value surface*, as filed: `VMap`, `VSet` and `VArr` cross now, both directions. Each was
   found by a failing program rather than by reading the enum, because the refusal names the shape
   it met — `VData` first, then `VMap`. v2 has no array case at all, so an array crosses as the
   `ForeignV(ArrayBuffer)` handle itself.
2. *A thrown failure is part of a host function's contract.* `listDir` on a missing directory is
   SUPPOSED to raise; the plugin's Java exception escaped the executor and surfaced as
   `cannot read '<the .ssc>': NoSuchFileException` — a message about the SOURCE FILE, from a handler
   that assumes anything thrown came from reading it.
3. *This report kept its own copy of the v2 invocation*, which is why the cases still counted DIFF
   long after they matched by hand on both lanes. It BUILT the IR with the front — which has the
   fleet, so lowering emits `(prim "mkdirs" …)` rather than a refusal — and RAN it on a plain
   `ssc.cli` with no plugins. `v3/ssc3 __v2-run` prints the whole command now and the report uses it.

**THE ENTRY SAID THE LAST THREE WERE "NOT DIAGNOSED" AND THAT THE DIFFERENCE WAS IN THE HARNESS.
That was right about the domain and wrong about every guess inside it** — not the parallel jobs, not
stdin, but the harness's own v2 entry. The guesses were named as guesses, which is the only reason
they cost nothing.

**A LATENT HARNESS DEFECT CAME OUT WITH IT, worth more than the fix.** The dispatch loop reads the
case list on fd 0 and a background job inherits it, so once the os plugin made `std-os-readline`
really read stdin it ATE THE REST OF THE LIST. The report still announced `running 369 case(s)` —
that count is taken upfront — while its buckets summed to 294. Seventy-five cases were never
dispatched, and no error was printed anywhere: it looked like an ordinary report with a lower N.
The quantity is what named the mechanism, being exactly the tail behind the reading case.
`< /dev/null` on the dispatch closes it for every case and every configuration.

**THE FLEET IS STILL OPT-IN**, but no longer because it regresses anything — only because
`v3/.jars/plugins.cp` needs an sbt build, and availability is a cached fact here exactly as the
second front's is. Turning it on by default is a separate decision with a separate cost.
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
BOTH fronts populating it — which at the time the default front could not do, because it dropped a
parenthesised parameter type entirely (`v3-uniml-drops-a-parenthesised-parameter-type`, since
FIXED; the 39 use sites are what still decides this, not the front). The route taken is one file and
covers STRICTLY MORE: `val xs = List(1, 2, 3)` has no written type at all and resolves.

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
does not. That is the whole difference. When this was written, fixing it alone still would not have
made `std-bifunctor` pass on the DEFAULT front, because
`v3-uniml-drops-a-parenthesised-parameter-type` masked it there. That mask is GONE — the entry is
fixed and the default front now keeps the parameter's type.

## v3-uniml-drops-a-parenthesised-parameter-type — `def go(t: (Int, String))` loses its type on the default front

<!-- status: fixed
     fixed-in: 482e3393b
     lane: v3
     area: front
     gate: v3/front-gate.sh (v3/tests/front/paren-param-type-tuple.ssc)
     found-by: claude-code
     found-at: 2026-08-15 -->

**A two-front pair, and the default front is the one that lost.** uniml's parameter loop read

    if c.peekKind == "spike.lparen" then skipBalancedParens(c)
    else expectType(c, …).foreach(kids += _)

— so a parenthesised parameter type was CONSUMED AND NOT CAPTURED, and `Param.tpe` arrived as
`None`. v3's own parser keeps it: `skipType` consumes the balanced parens and `typeTextOf`
reassembles the text.

**FIXED by using the mechanism that was already there.** `captureType` opens with
`if c.peekKind == "spike.lparen" then takeBalanced(…)` under the comment `` `(A, B)` domain `` and
returns a `Frame` carrying the role; `SpikeTyped.text` concatenates it into a `TypeRef`, and its own
comment says the same thing from the other side — "types are captured as token runs
(`ScalaSpike.captureType`)". Both ends were built for this. The one call site not using it was this
one, so the fix is `kids += captureType(c, role)` in place of the skip. It also swallows a trailing
`=> C`, which the skip left behind for the arrow branch — that is the whole function type instead of
half of it, and it is what the reference front records too.

**Measured on the same host, one probe, both fronts, before and after:**

    def go(t: (Int, String)) = t.bimap(…)
      before   SSC3_FRONT=v3   (12, ok)     uniml (default)   'bimap' is provided by a `given`
                                                              instance, and the type of the
                                                              receiver is not known here
      after    SSC3_FRONT=v3   (12, ok)     uniml (default)   (12, ok)

**THE CORPUS DOES NOT MOVE, AND THAT IS REPORTED RATHER THAN OMITTED.** `corpus-report.sh` was run
on a pre-fix build and a post-fix build in the same worktree, and the two reports are identical byte
for byte — PASS 223, DIFF 3, CRASH 9, UNSUPPORTED 132, N = 223 / 369. 8 corpus files do parse
through the changed branch (5 with a parenthesised parameter type, 6 with a function-typed
parameter, overlapping), so the path is exercised; none of them NEEDS the type downstream, which is
the difference between a case that touches a defect and a case that can decide it. The one of those
8 that is a defect after the fix — `head-field-effect-shadow` — crashes on `v2 bridge V-0 does not
translate handle`, a bridge refusal that has nothing to do with parsing. Identical totals could in
principle hide a swap; they cannot here, because only those 8 files can change verdict and none of
them did.

**The regression is a front-gate fixture, and the control was run.** `paren-param-type-tuple.ssc`
passes now (front-gate GREEN 90, exec-gate GREEN 86 with both lanes agreeing); with the fix reverted
and uniml rebuilt, the same fixture FAILS with exactly the original diagnostic and the gate is RED.
A fixture that is green either way would have measured nothing.

**It went in front-gate rather than front-capability-gate**, which is the gate FOR two-front
divergence, because that gate asks `ssc3 ast <file> <front>` over the corpus — and the corpus has no
program this can decide, as the paragraph above measures. This defect is also not a refusal AT the
front: the front accepted the program on both fronts and threw the type away, and the refusal came
later, from the resolver. An `ast`-level accept/refuse differential is blind to information lost
INSIDE an accepted parse.

**The pairing note this entry shipped with was already stale when it was written.**
`v3-stmt-val-discards-a-type-the-author-wrote` is `wontfix` as of `32ac55842` — the receiver problem
it was filed for is solved by a different route, `rewriteGivenExtensionCalls` following a `val` one
step to its initialiser — and `std-bifunctor` passes. So "fix both, then measure" was wrong twice
over: there was nothing to fix on the other side, and this side needed no help to be measured.

## v2-f-round-is-three-different-roundings-across-the-backends — `rint`, `Math.round` and `.round()` disagreed at exactly `.5`

<!-- status: fixed
     fixed-in: d47dbf7e3
     lane: multi
     area: codegen
     gate: v2/conformance/float-round-ties.coreir (v2/backend/check.sh — NOT wired to CI, see below)
     found-by: claude-code
     found-at: 2026-08-15 -->

**THE CONTRACT IS HALF TO EVEN.** Owner's decision, taken after this entry was filed asking for one:
`round(2.5)` is 2, `round(3.5)` is 4, `round(-2.5)` is -2. That is IEEE-754's default and what
`math.rint` means, it is what three of the five implementations already did, and it is what v3's
shipped parity probes already pinned. Written into `v2/specs/10-core-ir.md` beside the prim list,
because the list alone could not settle it and five implementations had settled it three ways.

**FIXED IN TWO BACKENDS, AND THE CONTROL SAYS IT IS FOUR LANES.** js emitted `Math.round` (half up)
and rust `.round()` (half away from zero); js now emits a `$frint` helper and rust
`round_ties_even()`. Measured with the fix reverted and the new fixture in place:

    jvm   ok
    js    FAIL  row 1: expected 2, got 3
    rust  FAIL  row 1: expected 2, got 3
    wasm  FAIL  row 1: expected 2, got 3      <- NOT in the original census

**The wasm lane is generated through the rust backend**, so it inherited the away-from-zero rule and
was a fourth wrong answer the entry did not know about. With both fixes: ALL GREEN, 4 backends.
swift was read rather than run — `SwiftRuntime.scala:1112` is `.rounded(.toNearestOrEven)`, already
correct, and this harness has no swift lane.

**THE GATE IS HONEST ABOUT ITS REACH.** `v2/conformance/float-round-ties.coreir` is run by
`v2/backend/check.sh`, and **nothing in CI runs check.sh** — it appears only in changelogs and bug
entries. So this is a manual instrument, and the fix is not protected by a job. A conformance case
was written to cover it in CI and then WITHDRAWN, because the `int`, `jvm` and `v2` lanes answer a
DIFFERENT question — see the entry below.

**`math.round` IS NOT `f.round`, and that is now filed separately.** Measured on the shipped lanes:
`math.round(2.5)` prints **3** on both `--v1` and native, half UP and integer-valued, against **2**
for the Core IR prim. Filed as `math-round-and-f-round-disagree-at-a-tie` in `v2/BUGS.md` — it is a
consequence of this decision rather than part of it, and changing v1's semantics is its own call.

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
