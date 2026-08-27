# Cross-module bugs

Per-module bug files live in the modules — see `specs/work-tracking-layout.md` for the
layout and `specs/bugs-index.md` for the entry format. **This file holds only entries that
no single module owns**: the same defect present in more than one implementation, where the
root is the nearest common ancestor. It is not a leftovers bin — if an entry here turns out
to be one module's, move it and change its `lane:`.

Query across every file at once:

```sh
scripts/bugs-report                        # counts by status, per module
scripts/bugs-report --module v2 --status open
scripts/bugs-report --no-gate              # open entries with no regression gate named
```

Newest first.












## ios-run-refuses-the-host-before-the-program — `no available iOS Simulator` for a program that is not a UI app

<!-- status: fixed
     lane: multi
     kind: bug
     area: cli
     gate: tests/e2e/v2-swift-cli.sh
     reported-by: claude-code
     reported-at: 2026-08-27
     confirmed: yes
     fixed-in: 000263b9b -->

**FIXED 2026-08-27 (`000263b9b`).**

**The same command answered two different things depending on where it was asked.**

```
macOS with Xcode : run --target ios: checked program does not define a NativeUi application
Linux runner     : run --target ios: no available iOS Simulator
```

`runV2IosTargets` picked the simulator BEFORE emitting the package, and the `xcodeApp` check lives
inside `buildXcodeApplication`, one step further on. So the HOST's problem was announced ahead of
the PROGRAM's — and the program's is true on every host: a file that defines no NativeUi
application cannot be run on iOS anywhere. The message as it stood sends someone to install Xcode
for a diagnostic they will still get afterwards.

**REPRODUCED BOTH WAYS ON ONE MACHINE**, no CI round trip needed: `pickIosSimulator` spawns `xcrun`
and treats any failure as "no simulator", so a failing `xcrun` first on PATH IS the Linux runner.

```
default PATH        run --target ios: checked program does not define a NativeUi application
xcrun stub on PATH  run --target ios: no available iOS Simulator          <- the CI red, exactly
```

**FIX:** emit and check `xcodeApp` first; `pickIosSimulator` becomes a `lazy val`, forced by the
destination string, which is the first thing that genuinely needs it. A lazy binding rather than a
move into the loop, because `pickIosSimulator` shells out to `xcrun simctl list` and running that
once per file would trade one defect for a cost. After it, both rows above say the same sentence.

**IT HAD NEVER RUN IN CI.** `tests/e2e/v2-swift-cli.sh` sits behind
`tests/e2e/v2-f-nested-bytecode-fast-path.sh` in one `bash -e` step, and that gate was red for
weeks, so the step aborted before reaching this one. It surfaced the same day the fast path went
green — the fourth time in one day that unblocking a path revealed what was standing behind it.

**THE GATE NOW CARRIES THE OTHER HOST.** A row runs the same command with the `xcrun` stub on PATH
and asserts the program sentence AND the absence of the simulator sentence, so the fix is checked
where it can actually fail rather than only on a developer's Mac. The stub is verified to fail
before it is trusted — an emulation that silently stops emulating is the failure mode this repo has
paid for before.
## both-native-fronts-refuse-a-named-parameterised-given-the-oracle-runs — `given anyS[A]: S[A] with` answers `unbound global: __missing_using_S`

<!-- status: open
     lane: native
     area: front
     kind: bug
     gate: tests/e2e/f-output-agreement-gate.sh
     found-by: claude-code
     found-at: 2026-08-19
     ssc-version: a657c7bf1
     repro: the four programs in the matrix below
     confirmed: yes
     fixed-in: - -->

```scalascript
trait S[A]:
  def z(a: A): Int
given anyS[A]: S[A] with
  def z(a: A): Int = 7
def go[A](a: A)(using s: S[A]): Int = s.z(a)
println(go(1))
```

    ssc: unbound global: __missing_using_S      ← both native fronts
    7                                           ← v1, the oracle

**This is legal Scala 3 and the oracle runs it.** A type-parameter clause between a given's name
and its `:` is the standard way to write a polymorphic instance — `given listOrd[A]: Ord[List[A]]
with` — and it is the shape every `derives`-free typeclass library uses. The reference lane answers
`7`. The default lane answers with the name of a compiler-internal synthetic.

**Found while re-measuring `selfhost-front-accepts-a-parameterised-anonymous-given-the-language-rejects`
before the 0.2.0 release.** That entry frames the subject as *anonymity* — "a parameterised
ANONYMOUS given". A control run on 2026-08-19 says the discriminator is the **type-parameter
clause**, and anonymity is a second, independent axis:

| given | v1 (oracle) | F (default) | legacy (`SSC_FRONT=legacy`) |
|---|---|---|---|
| `given intS: S[Int] with` — named, monomorphic | `7` | `7` | `7` |
| `given anyS[A]: S[A] with` — named, **parameterised**, legal | **`7`** | **`__missing_using_S`** | **`__missing_using_S`** |
| `given [A]: S[A] with` — anonymous, parameterised | refuses at the parser | erases it, runs the rest | erases it, runs the rest |
| `given [A] => S[A] with` — the other spelling | refuses at the parser | erases it, runs the rest | erases it, runs the rest |

Row 1 is the control and it is what makes rows 2–4 mean something: the given machinery works, and it
is the `[A]` that breaks it. Without that row the whole table is consistent with "givens are broken",
which is not what happens.

**The mechanism, named to the line.** In F, `collectGT` (`specs/v2.2-p6.5-fsub.ssc:2767`) requires the
token after `given` to be an identifier, then `collectGT1` (:2768) requires the token straight after
that name to be `:` — `isTok(hd(t2), 2, 34)`. For `given anyS[A]:` the token after `anyS` is `[`, the
test fails, and the given never enters the table `findGivenF` later reads. The call site then finds
no candidate and `usingPick2` (:2852) synthesises `(global __missing_using_S)` — the same expression
it emits for a given that genuinely does not exist, which is why the error names an internal.
The legacy front has the identical shape at `v2/lib/ssc1-lower.ssc0:1046`
(`case None => Pair("var", #sconcat("__missing_using_", tc))`).

**Two fronts, one defect — so a fix in one of them changes nothing a user sees.** `RunNativeV2`
delegates a file F declines to `defaultRunner`, which IS the legacy runner (`ssc1-run.ssc0`,
`RunNativeV2.scala:665` names the marker), so both halves have to land together or the behaviour is
unchanged. The two fronts were confirmed to be genuinely different code paths rather than one path
behind an ignored env var: with `bin/lib/standard/native-front/tower/bin/ssc1-run-fsub.ssc0` moved
aside, the default lane fails to start and `SSC_FRONT=legacy` still runs. That check is recorded
because three cheaper probes before it — `info --front-report`, a paren-cons pattern, and the
`summon` fixture from `f-front-delegation-visible` — all answered identically on both fronts and
proved nothing either way.

**Done when** `given anyS[A]: S[A] with` answers `7` on both native fronts, asserted against the v1
oracle in `tests/e2e/f-output-agreement-gate.sh`, with the monomorphic row of the matrix above kept
as the anti-case so a fix that breaks ordinary givens cannot pass. The anonymous rows are the other
entry's subject and are NOT closed by this one.

**Not fixed here.** `specs/v2.2-p6.5-fsub.ssc` was held by a live sibling claim
(`f-triple-quoted-interpolation`) for the whole of this measurement, and half of this fix is that
file. Filed rather than half-landed.

## v3-an-arm-performing-its-own-operation-is-answered-by-itself — the handler was never hidden

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: 599e16020
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: v3/tests/effects/arm-forwards-to-an-outer-handler.ssc, arm-performs-its-own-op-unhandled.ssc
     impact: wrong-answer -->

A handler arm that performs an operation was answered by ITS OWN handler, on both lanes:

```scalascript
val r = handle(
  handle(f()) { case op(n, k) => E.op(n + 1) + 1000 }   // meant for the OUTER handler
) { case op(n, k2) => k2(n * 7) }
```

The inner arm re-entered itself, and the outer handler — the only one that resumes — never ran. It
diverged rather than answering: `OutOfMemoryError` on the executor, `StackOverflowError` on the
bridge. An arm performing its own op with NO outer handler was worse than a crash — it printed
`1300`, a number assembled from a handler answering its own question. That 32 fixtures and three
gates were green about both is the part worth keeping:
**the executor and the bridge implement one specification, so a defect in the SPECIFICATION is
invisible to a differential.** The two lanes agreeing is evidence about the lanes, never about the
rule they share.

**A HANDLER IS NOW HIDDEN WHILE ITS OWN ARM RUNS, AND VISIBLE AGAIN FOR THE CONTINUATION.** This is
what OCaml 5, Koka, Effekt and Eff all do, and the reason is not taste: an arm is the code that runs
INSTEAD of the perform, so an arm that performs the same operation is asking its own handler to
answer a question it is in the middle of answering. The continuation belongs to the `handle` BODY,
not to the arm, so `k(…)` restores visibility.

| lane | how |
|---|---|
| executor | one field, `armHidden`, set around an arm's execution and cleared for `runCont`; the search is `handlers.find(h => !(h eq hidden) && …)` |
| v2 bridge | `__ssc3_eff_arm_at__`, an array used as a stack of hidden indices, pushed in `effFind` next to `effPushAll` and popped in the same `__tryFinally__`; `effResume` pushes `-1` |

**SKIPPED, NOT TRUNCATED — and the first implementation got this wrong on both lanes.** Hiding by
starting the search BELOW the arm's own handler reads as equivalent and is not: an arm may install a
handler of its own, `case a(k) => handle(inner()) { … }`, whose record sits ABOVE it, and a search
that begins underneath can never reach it. It cost a probe that had answered 14 for a week
(`p12-handle-in-arm`) and the bridge answered nothing at all. The fix is a one-record skip.

**THE RULE CREATED A NEW FAILURE MODE, SO IT CARRIES A POSITION.** `case op(n, k) => E.op(n + 1)`
with no outer handler is now a program with no answer. Left alone it would fail through the
executor's positionless `no handler for effect operation 0`, which `corpus-report.sh` classifies
CRASH rather than a refusal. `Lower.scala` refuses it where both the perform and the handles still
exist:

```
p11.ssc:8:19: no handler for the effect operation 'E.op' — a handler is hidden while its own arm
runs, and no other `handle` in this program handles it
```

It is narrow in two directions on purpose, because each is a way the perform could still reach a
handler at run time: only when the op has exactly ONE `handle` in the module, and not inside a
`Lambda`, which the arm may store for the handle body to call later — where the handler is visible
again. A perform in a function the arm CALLS stays a run-time failure, exactly as the older
whole-program check leaves the not-on-this-path case alone.

**A THIRD GUARD WAS WRITTEN AND REMOVED**, and it is recorded because it read as live: excluding a
perform inside a nested `handle` of the same op cannot ever fire, since that nested `handle` is
itself a second handle of the op and the count above already skips the check. Both the code comment
and this entry claimed three working conditions before the arithmetic was checked against itself.

Control, and it is a REAL one because the baseline arm was rebuilt in the same worktree with the
same per-checkout registrations: `arm-forwards-to-an-outer-handler` answers 1014 on both lanes (14
from the outer resume, +1000 from the inner arm) and the rebuilt baseline OOMs on the executor and
overflows on the bridge; `arm-performs-its-own-op-unhandled` is refused with a position where the
baseline printed 1300. The 54-cell A/B over every conformance case that mentions an effect moved
NOTHING — which here means no regression, not confirmation: none of those 27 cases has an arm that
performs, which is the same blindness that let this defect live.

## v3-effects-refuse-a-perform-inside-a-region — the `if` was the boundary, on both lanes

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: f0a6e4840
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-20
     repro: v3/tests/effects/perform-in-if.ssc, perform-in-loop.ssc
     impact: workaround -->

A `perform` standing directly inside an `if` or a `while` was refused by BOTH lanes whenever its
handler arm was not tail-resumptive:

```scalascript
def f(x: Int): Int =
  if x > 0 then
    val a = E.op()          // ssc3: this handler for operation 0 is not tail-resumptive
    a + 1
  else 0

handle(f(5)) { case op(k) => k(10) + k(20) }     // the answer is 32
```

**THE REFUSAL NAMED THE WRONG THING**, which is why it lasted. It said the ARM was the problem — "the
executor implements only an arm whose LAST act is a single `resume`" — and the arm is fine; the same
arm over the same effect works when the `perform` sits at a function's top level. What was missing
was the CONTINUATION, and `Cps.split` only cuts at the top level of a function body. `Cps.scala`'s
header calls that step 3 and is accurate about it: this is not fixed there either.

**BOTH LANES ANSWER IT NOW AND NEITHER ANSWERS IT THE SAME WAY**, which is `v3/specs/10-ssc-ir.md`
§3's own shape — one invariant, two realisations, because the lanes differ on who owns the registers:

| lane | what it does |
|---|---|
| executor | `stepFramed` hands the perform the rest of ITS OWN list as a `PendingFrame` (`performRest`), and `k` becomes a `VCont` whose closure is null — the rest of the list IS the segment's first frame |
| v2 bridge | `splitRegionPerforms` REBUILDS the remainder into one function (`cutAt`), because `MkClos` captures by value and two closures cannot share a write |

The null closure is the whole difference on the executor side and is a null rather than a second
`Value` case for that reason: there is nothing for it to hold.

**IT ALSO CLOSED A REFUSAL NOBODY FILED.** An operation performed at a function's top level (which
`Cps` cuts) AND inside a region (which it does not) had two encodings in one module, and the bridge
refuses that by name — "the bridge needs every `perform` of an operation to use one encoding". The
region split is what makes them agree, so `splitRegionPerforms` takes an op that is CPS-encoded
anywhere, not only one whose arm needs a continuation.

Control: reverting `v3/src` turns exactly `perform-in-if` and `perform-in-loop` red and leaves the
other 19 rows of `effects-gate.sh` green.

## v3-bridge-refuses-a-call-inside-a-region — a rebuild, not a closure

<!-- status: fixed
     lane: v3
     area: codegen
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: f0a6e4840
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-20
     repro: v3/tests/effects/cross-frame-in-if.ssc, cross-frame-in-loop.ssc
     impact: workaround -->

The executor has crossed a call frame into a region since `830efe318`; the v2 bridge refused it by
name, and the refusal explained itself correctly:

> `g` performs an effect and is called inside an `if`, `loop` or `try`, where the continuation would
> have to resume into that region — this lane can make a closure of a list's remainder, not of a
> region's

**THE REASON IT COULD NOT WAS REAL.** `MkClos` captures registers BY VALUE, so a continuation made of
two closures — "finish the region", then "finish what encloses it" — loses every register the first
one wrote before the second reads it. Code after an `if` must see what the branch wrote.

**SO THE COMPILER REBUILDS INSTEAD OF SPLITTING.** `cutAt` reconstructs the whole remainder as ONE
instruction list — the region suffix wrapped in a `Block`, then the enclosing suffix, up to the
function — which becomes one continuation function with one frame, where nothing has to be shared
because nothing was ever split. `Block` consumes a branch level exactly as an `if` arm and a `switch`
arm do, so depths need no adjustment; `Try` is branch-transparent on both lanes and adds none.

**THE LOOP IS THE CASE WITH A TRAP IN IT.** The rebuild is

    Block( Block( suffix ; br 1 ) , Loop( splitBody ) )

— the inner block finishes the interrupted iteration, falling off its end leaves the loop, a `br 0`
drops into the loop for the next one, and depths past the loop shift by one for the added block.
Putting the UNSPLIT body back there does not terminate: the copy contains the same cross-frame call,
gets cut, and mints a continuation of the same shape for ever. Putting the SPLIT body back does,
because its `mkclos` already names the continuation being built — **the function refers to itself**.
`alreadySplit` is what keeps the walk off that copy.

Two smaller things the region case forced, both of which the top-level rule never had to think about:

* **the function goes back on the worklist, not only its continuation.** One cut no longer empties a
  function — `if c then f() else g()` keeps the second call in the arm the cut did not touch.
* **one `k` register per function, reused by every cut in it.** The emitter carries a single
  `crossK`; a second cut with a second register makes the first call site read the wrong one. Safe
  because a `k` is live for exactly two instructions.

**THE NARROWED REFUSAL WAS WRONG ABOUT CORRECT CODE, AND ONLY THE A/B SAID SO.** What is left to
refuse is a call inside a region that is itself inside a `handle` BODY, and the first version tracked
"inside a region" from the FUNCTION inward rather than from the handle inward. `handle(program())`
sitting inside a `while` — `tests/conformance/js-effect-multishot-long-fold` — then looked like the
refused combination, when its call is at the handle body's top level and has been split correctly
since `bc78e963c`. It printed `204` before this work and a refusal after.

The flag has to mean "inside a region the rebuild cannot reach", and the rebuild stops AT the handle,
so a region OUTSIDE the handle is irrelevant and `inRegion` resets there. Found by an A/B over every
conformance case that mentions an effect, same tree, only `v3/src` swapped: three of 54 cells moved
and that was one of them. Reading the new refusal against the new fixtures would never have found it
— it is correct on all five of those.

Control: reverting `v3/src` turns exactly `cross-frame-in-if` and `cross-frame-in-loop` red.

## v3-bridge-arm-composed-with-frames-the-continuation-already-owned — invisible until a loop

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: f0a6e4840
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-20
     repro: v3/tests/effects/cross-frame-in-loop.ssc
     impact: wrong-answer -->

The bridge never took the parked caller frames off `__ssc3_eff_pending__` while a handler arm ran. A
perform inside the arm — or inside a resume the arm makes — therefore composed its continuation with
frames that had ALREADY been handed to the previous continuation. `Exec.Perform` has spelled the
correct rule since the cross-frame work (`pending = pending.drop(depth)` with a `finally` that puts
it back); the emitted runtime had no equivalent.

**IT WAS INVISIBLE, AND THE REASON IS THE POINT.** The only fixture that could reach it before regions
was `cross-frame-statement`, whose caller remainder is

    def body(): String ! C =
      prog()
      "END"

— a function whose value does not depend on its argument. Composing it twice answers what composing
it once answers, so the fixture stayed green over the defect for as long as it existed. A green suite
saying nothing, of the kind this repository has recorded before.

**A LOOP MADE IT FATAL RATHER THAN HARMLESS**, because there the stale frame is the PREVIOUS
ITERATION's continuation. It resumed the loop from the state it had captured, so the program printed
`iter i=1 acc=2` for ever and the v2 stack went with a `StackOverflowError` — no message, no lane
disagreement, just a crash. Found by instrumenting the emitted `__ssc3_eff_push__` to print its own
depth: the pending stack grew 0, 1, 2, 3, … one entry per iteration, which named the mechanism in one
run after an hour of reading the rebuild for a fault that was not in it.

Filed separately from the two entries above because it is a different defect that they merely
UNCOVERED: it predates them, it is in the emitted runtime rather than in any pass, and a reader
looking for why an arm sees the wrong pending stack should not have to find it inside an entry about
regions.

## v3-continuation-cannot-break-past-its-loop — a branch's depth is a count of frames

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: f0a6e4840
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-20
     repro: v3/tests/effects/break-past-loop.ssc
     impact: workaround -->

`Exec.resumeCont` refused a resumed remainder that branched out more than one level:

> a continuation resumed into a `loop` and its remainder branched out 1 level(s); v3 crosses a back
> edge but not a break past the loop it is in

**THE STATED REASON WAS THE ANSWER.** It said "the chain would have to know how many region frames it
unwinds, and it records depth per frame rather than per branch". It does record per frame — and a
branch's depth IS a count of frames, because `stepFramed` records exactly one per region on the way
in. Unwinding `Branch(dd)` is dropping `dd` frames; what is then at the head is the region the branch
was aimed at, and delivering to it is `Branch(0)` — a loop repeats, an `if` or a `block` finishes and
its remainder runs.

**REACHABLE FROM ORDINARY SOURCE, which is not obvious**: `Lower` emits `br` at depths 0 and 1 only,
from the one site that builds a `while`. The deeper ones come from `TailCalls`, which turns a self
tail call inside nested regions into `br <nesting>`. Two nested `if`s and a self-recursive function
is enough, and `break-past-loop.ssc` is that program.

**FIXING IT EXPOSED A SECOND ONE IN THE SAME WALK.** A loop being re-entered is still LIVE, unlike
every other frame in the chain, whose remainder runs once and is finished. It has to go back on
`pending` while it iterates, because the iteration is run from `resumeCont` rather than through
`step(Instr.Loop)` and so records nothing for itself. Without it the second perform of a
self-recursive function captured a segment with no loop in it, its `br` found a call frame where a
region belonged, the back edge was dropped and the arm was handed unit — `Add on Unit () and Int 1`.

A third, smaller thing found by reading the same lines: `backEdge` was consumed only by a frame that
had a loop body, so at any other frame it stayed set and the NEXT loop out would iterate for a branch
that had already been answered. Now consumed at whichever frame receives it.

Control: `break-past-loop` is the only one of the five new fixtures that pins this. With the old
refusal restored on its own it goes red and `perform-in-if`, `perform-in-loop`, `cross-frame-in-if`
and `cross-frame-in-loop` still answer 32, 12, 232 and 20 — so the row is not sharing its evidence
with the other fixes.

## v3-gates-job-is-killed-at-its-cap-and-reports-cancelled — 42 minutes of work, 45 of budget

<!-- status: fixed
     lane: v3
     area: build
     kind: apparatus
     gate: .github/workflows/v3.yml
     fixed-in: 7320efbcc
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-21
     repro: compare startedAt/completedAt of the `v3 gates` job across consecutive runs
     impact: none -->

**A JOB KILLED BY `timeout-minutes` IS REPORTED AS `cancelled`, NOT AS A FAILURE**, and `cancelled`
reads exactly like "a newer push superseded it". I read four runs of this job that way before
checking a clock, and each time concluded the run had simply been overtaken.

    8a42cbfad  41m58s  failure    (reported)
    74bbc4b1d  45m16s  cancelled  (killed at the cap)
    22a36413d  41m29s  failure    (reported)
    b894bcb09  45m16s  cancelled  (killed at the cap, on the nightly)

Two of them are the cap to the second. **42 minutes of work under a 45-minute cap is a 7% margin on
a shared runner**, so roughly half the runs die with nothing to say — and the half that survive are
the only reason anybody has ever seen this job's verdict.

**PRICED PER STEP** on the run that did finish (41.4 min over 30 steps), because "the job got slower"
and "the cap was always too tight" are different diagnoses and only one of them is true here:

| step | min |
|---|---|
| `front differential` | 12.4 |
| `jit gate — method sizes and the specializer` | 10.9 |
| `Build the plugin fleet` | 4.4 |
| `executor differential` | 4.2 |
| … | |
| `effects` — 22 fixtures on BOTH lanes | **0.6** |
| `Register the importable JVM packages` | **0.6** |

Two steps are 56% of the job. Nothing recent is a slow gate that snuck in: the effects suite that
grew from 16 to 22 fixtures this week costs 37 seconds in total, and the classpath step added
alongside it costs 34. **The cap was set when the job was smaller and never re-priced.**

**THE SIBLING JOB IN THE SAME FILE ASKS FOR 60 AND FINISHES IN 20.** Giving the 42-minute job less
budget than the 20-minute one was backwards.

**AND 60 WAS STILL WRONG, which the first green run said immediately.** `a13e7854c` produced the
first `success` this workflow has ever reported — in **58m55s**, against 41m29s for the same commit
one run earlier. Every step is ~40% slower and nothing changed between them:

| step | fast run | slow run |
|---|---|---|
| `front differential` | 12.4 | 17.3 |
| `jit gate — method sizes …` | 10.9 | 15.0 |
| `executor differential` | 4.2 | 6.8 |
| `Build the plugin fleet` | 4.4 | 5.9 |
| **total** | **41.4** | **58.9** |

That is RUNNER-SPEED VARIANCE, so the job's duration is a distribution spanning 41–59 minutes and
not a number — and **a cap has to be set against the SLOW end**. 60 left the slow end a 1.8% margin,
which is the same coin flip 45 was, one sample further along.

**The sample that refuted it was the one that had just been read as a success**, which is the part
worth keeping: a green run is evidence about the RESULT and says nothing about the margin, and the
duration was sitting in the same JSON I had already fetched. Raised to 90 — ~1.5x the observed slow
end, still half the sibling's ratio.

Found while trying to read a verdict for unrelated work — every `v3` run on the branch came back
`cancelled` and I had explained it to myself twice as "superseded" before comparing `startedAt` with
`completedAt`. Same species as `corpus-contract-gate`, where a job timeout also surfaced as
`cancelled`; the lesson did not carry across workflows because nothing wrote the number down.

## v3-jvm-classpath-writes-an-escape-sequence-and-calls-it-a-classpath — four bytes, three gates, two weeks

<!-- status: fixed
     lane: v3
     area: build
     kind: bug
     gate: .github/workflows/v3.yml
     fixed-in: b894bcb09
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-20
     repro: a stub `sbt` on PATH printing a classpath then ESC[0J; v3/jvm-classpath.sh writes 4 bytes
     impact: workaround -->

**THE TITLE THIS ENTRY WAS FILED UNDER WAS WRONG**, and it is kept in the body rather than quietly
replaced because the wrong one is half the lesson: `v3-ci-never-registers-the-importable-jvm-packages`
described a workflow with a missing step. CI DID register the packages, both before the step existed
(lazily, from the driver) and after it (explicitly). What it registered was garbage.

The `v3` CI job has been RED on main since the fixture landed (`dff03be59`), on one row in three
gates at once — `executor differential`, `front` and `front differential`:

```
FAIL jvm-package-import — executor [] bridge [] expected [7/0]
  ssc3: v3/tests/front/jvm-package-import.ssc:17:1: cannot find the import
  'std/scalascript/typeddata.ssc' — looked in: v1/runtime/std/scalascript/typeddata.ssc,
  std/scalascript/typeddata.ssc, v3/tests/front/std/scalascript/typeddata.ssc, scalascript/typeddata.ssc
```

**IT IS GREEN IN EVERY CHECKOUT A PERSON HAS EVER RUN, which is why nobody saw a defect.**
`scalascript.typeddata` is a JVM PACKAGE, not a file. It is declared in `v3/jvm-classpath.sh` and the
answer is cached in `v3/.jars/jvm.cp` — per checkout, exactly like `uniml.cp`. Without that file the
loader cannot ask "is this a package you provide", so v3 falls back — correctly — to treating the
import as a FILE, and refuses with four candidate paths to a file that cannot exist. The message is
right about what it did and says nothing about why.

**THE MECHANISM WAS MEASURED, not inferred from the message.** Deleting `jvm.cp` alone changes
nothing: `v3/ssc3`'s `jvm_cp()` rebuilds it on first use and discards the output. Deleting the file
AND the script that builds it reproduces the CI line character for character; restoring both answers
`7` / `0`. So the CI failure is "`jvm.cp` is absent there", and the lazy rebuild is failing silently.

**`.github/workflows/v3.yml` BUILDS TWO OF THE THREE PER-CHECKOUT CLASSPATH FILES** — `uniml.cp` and
`plugins.cp` — and not this one. The comment beside the fleet step already contains the argument for
why: building it there "makes the failure name itself" instead of letting a silent fallback shrink
what the gates measure, two layers from the cause. That is the same reasoning, and it had one file
missing from it.

The step is added to BOTH jobs. Whether the runner can actually build it is what the next run
answers — and that is the point of the change rather than a hole in it: if sbt cannot export
`backendTypedDataRuntime` on CI, a named step now says so, where today the only symptom is a fixture
claiming a Scala library should have been a file.

**IT ANSWERED, AND THE ANSWER WAS "THE STEP SUCCEEDS AND THE FILE IS GARBAGE".** `Register the
importable JVM packages` came back green on `22a36413d` and `executor differential` stayed red. The
cause was forty lines away, in the SIBLING script: `plugin-classpath.sh` carries
`-Dsbt.supershell=false`, an ANSI-escape strip and a per-entry existence check, all three added on
2026-08-18 after this exact failure cost a CI-log archaeology session. `jvm-classpath.sh` had none of
them.

Reproduced with a stub `sbt` that prints a classpath and then `ESC[0J`, which is what a COLD runner's
supershell emits as its last stdout line:

| script | result |
|---|---|
| old | reports success, writes **4 bytes** — `\033[0J` — as the classpath |
| new | strips the escape, validates, exits naming the module and the entry |

A non-empty file passes the emptiness check, so the step is green and the driver believes the
packages are available. That is why the symptom appeared as a FILE-import refusal three gates away.
A warm local sbt never prints the sequence, which is the whole reason every checkout is green.

**ONE RULE, TWO SITES, AND ONLY ONE OF THEM HAD IT.** The guards are lifted VERBATIM rather than
paraphrased, comments included, so the two scripts cannot drift again. Control both ways: the normal
run produces a byte-identical `jvm.cp`, and the stub run turns the old script's silent success into a
named failure.

**CONFIRMED ON CI.** `b894bcb09` turned both gates that carried the row green — `executor
differential` and `front` — after two weeks red. The explicit step (`22a36413d`) stays: it is what
made the difference between "CI cannot build this" and "CI built four bytes" visible at all, and
without it the lazy rebuild leaves no trace to read.

**THREE WRONG ANSWERS ON THE WAY, and each was needed to reach the next**, which is why they are
written down rather than tidied away:

1. *"the workflow never registers it."* True as a fact about the file, false as a cause. The control
   — delete `jvm.cp` — changed NOTHING, because `v3/ssc3`'s `jvm_cp()` rebuilds it silently. **A
   control that changes nothing is a refutation, not a confirmation**, and this one was run only
   after the fix and the entry were already written.
2. *"then CI cannot build it, and an explicit step will say so."* The step went GREEN and the gate
   stayed red — which is the useful kind of wrong answer, because it moved the question from
   "can it build" to "what did it build".
3. *"the answer must be in the new code."* It was in a sibling script nobody had touched, forty lines
   away, carrying the fix and its own CI history since 2026-08-18.

**NOT MINE AND TAKEN ANYWAY.** No claim held `.github/workflows/v3.yml` or the fixture, and the red
blocked the whole `v3` job for everyone. Diagnosed and offered in the coordination room first; taken
when the diagnosis turned out to be one line of workflow and a measurable control.

## v3-bridge-refuses-a-region-inside-a-handle-body — the lanes diverged, and it moved rather than closed

<!-- status: fixed
     lane: v3
     area: codegen
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: 284c23dea
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-22
     repro: v3/tests/effects/region-in-handle-body.ssc, perform-in-region-in-handle-body.ssc
     impact: workaround -->

The last position `f0a6e4840` left named, and unlike the three it closed this one was a **lane
divergence**: the executor answered and the bridge refused.

```scalascript
handle {
  var acc = 0
  if n > 0 then acc = g() + 100      // g() performs
  acc
} { case op(k) => k(10) + k(20) }     // 232
```

**WHY `ret` CANNOT SERVE, which is the whole design.** A handle body's remainder ends AT the handle,
with the handle's own destination register. `ret` leaves the FUNCTION and takes the answer with it,
and on this lane it also sets CTL to -1, which no `endRegion` ever clears. So the rebuilt body is
wrapped in a `Block` and the cut leaves by BRANCHING out of it — `mkclos ; INSTR ; move dh,d ;
br <depth>` — ending the body exactly where `Handle` reads `dh`.

The branch also SKIPS the enclosing suffixes, which is required rather than incidental: `cutAt`
leaves them in place because a region's other arm still reaches them, and on the path through the
cut they belong to the continuation.

**TWO DEFECTS ON THE WAY, both found by running rather than reading:**

* `ssc3 build` **HUNG**. `alreadySplit` knew one shape — `mkclos ; INSTR ; ret d` — and a handle-body
  cut leaves another, so the walk re-found the cut it had just made and split it for ever.
* `perform` inside a `while` inside a handle body answered **1** where the executor said **12** — a
  wrong answer, not a refusal. The loop rebuild puts a copy of the split instruction back and its
  `br` needs a target in the CONTINUATION too; without a `Block` around the continuation's remainder
  it aimed at a block that exists only in the function the cut came from.

**THE BOUNDARY MOVED, IT DID NOT CLOSE.** What the rebuild still cannot reach is a handler **ARM**:
a call or perform inside a region inside an arm body, or inside a `handle` nested in another
handle's body. Measured rather than assumed — an arm performing an OUTER effect with a
non-tail-resumptive handler for it answers 13 on the executor and refuses on the bridge. The refusal
was narrowed and its message corrected instead of deleted, and its flag now means "the rebuild
cannot cut here", which is a property of where the `handle` stands rather than of whether there is
one.

Control: reverting `v3/src` turns exactly the two new fixtures red, the other 22 stay green. A/B over
every conformance case that mentions an effect — 27 cases, both lanes, 54 cells — moved **none**.

## v3-a-cross-frame-effect-loop-overflows-the-executor-at-a-few-hundred-iterations

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: ad741e91b
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-22
     repro: a `while` loop calling a performing function, handled outside; vary the iteration count
     impact: workaround -->

**THE LANE THAT PROMISES CONSTANT STACK DIES FIRST, BY THREE ORDERS OF MAGNITUDE.** A loop that calls
a performing function, handled outside the loop:

| arm | executor | v2 bridge |
|---|---|---|
| `k(1) + 0` (not tail-resumptive) | **StackOverflow between 120 and 150 iterations** | 1000 ✓ |
| `k(1)` (tail-resumptive) | **StackOverflow at 1000** | **200 000 ✓** |

120 iterations answer 240; 150 overflow. The bridge — which has no TCO at all, and whose own
documentation says so — reaches 200 000.

**THE DEPTH COMES FROM THE RESUME CHAIN, NOT FROM THE LOOP.** Each iteration's resume runs inside the
previous arm's activation, on the HOST stack: `Perform` runs the arm, the arm calls `k`,
`resumeCont` runs the continuation, which performs again. `TailCall` cannot help, because none of
those is a tail call.

**THIS IS WHY §3's PRESCRIBED ROUTE WOULD NOT FIX IT**, and the entry exists mainly to say so before
anyone spends the work. That paragraph reads:

> a `Loop` containing a `Perform` becomes a recursive function, since a loop's remainder cannot be a
> closure without one. v3 has `TailCall`, so those recursions are constant-stack rather than a new
> leak.

The reasoning is about the LOOP's recursion. The recursion that overflows is the resume chain, which
that rewrite leaves exactly as it is. So "one mechanism instead of two" and "deep effect loops work"
are DIFFERENT goals, and the route delivers only the first — worth knowing, because the sentence
above reads as though it delivered both.

**Not attributed to a change.** Both lanes' numbers are from today; the shape has only been runnable
on the bridge since `f0a6e4840`, and on the executor it predates that. Whether the executor's limit
moved is unmeasured and would need a build from before the region work.

**CORRECTED 2026-08-23, AND THE CORRECTION IS TO THE COMPARISON RATHER THAN THE MECHANISM.** The
depth IS the resume chain and `TailCall` does not touch it — that half stands. But the bridge was not
three orders of magnitude better at this: `v3/ssc3` launches the v2 lane with `-Xss512m` and launched
v3's own executor with nothing, so the two columns above differ by a JVM flag as much as by any
design. With the budgets equal the executor answers at 100 000 (`ad741e91b`). **Two things differed
between the lanes and this entry credited the wrong one** — the shape
[[a-benchmark-variant-that-changes-two-things-credits-the-wrong-one]] describes, committed by the
entry that was supposed to be the careful measurement.

The tail-resumptive column is superseded too: since `1adbb3274` that encoding is not split at all,
so it is constant-stack at any budget rather than dying at 1000. What remains true and unfixed by
either commit is the last paragraph — N performs under an arm with work after its resume leave N
pending continuations, and only cps-converting the ARM makes that O(1).

## v3-the-return-clause-was-guarded-on-a-proxy-at-four-sites — `performs` is not "an arm answered"

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: ae5e96d03
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: v3/tests/effects/return-clause-with-a-tail-arm.ssc, mixed-encodings-one-handler.ssc
     impact: wrong-answer -->

A `handle`'s return clause must lift the value the BODY produced and must not lift one an ARM already
produced. Every site that decides this asked `performs` — a counter of how many times the operation
was performed — as a stand-in for "did an arm answer". **The two coincide only while every perform
UNWINDS.**

    Exec.Handle          if sig == Done && hf.performs == 0L
    BridgeV2.Handle      if ctl == 0 && rec.performs[0] == 0
    Exec.resumeCont      if c.h.performs != before then raw else lift
    BridgeV2.effResume   if rec.performs[0] != before then raw else lift

Four sites, one substitution, and it was invisible for as long as the substitution held.

**IT STOPPED HOLDING WHEN THE TAIL-RESUMPTIVE ENCODING BECAME REACHABLE.** That encoding does not
unwind — the arm resumes, the perform RETURNS, the body carries on to its own value — so the counter
was non-zero while nothing had answered, and the lift was skipped. Measured on one program written
three ways, all of which should answer 1100:

| where the `perform` stands | executor | bridge |
|---|---|---|
| top of the performing function | 1100 | **1001** |
| inside an `if` | **11** | **11** |
| in the `handle` body | 1100 | **11** |

**THE FIRST TWO SITES WERE FOUND BY A PROBE, THE OTHER TWO BY AN A/B**, and the difference is worth
recording. The probe was written to check a PRECONDITION for an unrelated change and found the
`Handle` pair immediately. The `resumeCont` pair needed a shape no fixture had: ONE handler carrying
BOTH encodings —

```scalascript
case J.append(scope, fact, resume) => (scope + "=" + fact) :: resume(())   // work after — split
case J.read(scope, resume)         => resume(List(1, 2, 3))                // bare resume — not
```

— which **could not exist** until `1adbb3274` made the encoding depend on the arm. So the class of
programs that exposes the remaining two sites was created by the change that exposed the first two,
and only a sweep of every conformance case mentioning an effect saw it: `read` bumped the counter
without unwinding, the lift was skipped, and the `append` arm was handed the Int 6 where it wanted a
list.

**THE QUESTION EACH SITE CAN ACTUALLY ANSWER** is whether the value arrived through the unwind.
`Exec` sets a flag in the `catch` that already exists for exactly that value; the bridge reads
`effAborting`, which since `1adbb3274` means precisely "travelling home from an arm" — bound before
the reset rather than after, because bindings there are sequential.

Two fixtures pin it: `return-clause-with-a-tail-arm` and `mixed-encodings-one-handler`.

## v3-the-executor-runs-on-the-default-jvm-stack-while-its-sibling-asks-for-512m

<!-- status: fixed
     lane: v3
     area: build
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: ad741e91b
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: a `while` loop calling a performing function under a non-tail-resumptive arm
     impact: workaround -->

`v3/ssc3` launches the v2 lane as `java -Xss512m …` and v3's own executor as `java …`. Same file,
twenty lines apart, one of them with a 256x smaller stack budget than the other. A deep handler whose
arm does work after resuming — `case op(k) => k(1) + 0` — keeps one pending frame per perform, and
those frames live on the host stack, so the budget is what decides how deep an effect loop may go:

| iterations | before | after |
|---|---|---|
| 150 | **StackOverflow** | 150 |
| 5 000 | StackOverflow | 5 000 |
| 100 000 | StackOverflow | 100 000 |

**AND IT CORRECTS A CLAIM I FILED THE DAY BEFORE.**
`v3-a-cross-frame-effect-loop-overflows-the-executor-at-a-few-hundred-iterations` reported the
executor dying three orders of magnitude earlier than the v2 bridge and reasoned about the resume
chain against `TailCall`. The mechanism half is right — the depth IS the resume chain, and `TailCall`
does not touch it. **The comparison was not**: the bridge was not doing better, it was being handed
`-Xss512m`. Two things differed between the lanes and I credited the wrong one.

**THE PROBE THAT NEARLY MISSED IT.** The first attempt patched `SSC3=(java -cp …` and measured NO
change, which read as "the flag is irrelevant, so it really is the machinery". `v3/ssc3` assigns
`SSC3` THREE times — plain, plugin fleet, uniml — and the fleet is on by default, so the line I
edited is not the line that runs. A probe that edits the wrong one of three identical-looking
assignments produces a confident null result.

**WHAT IS NOT FIXED, and it is a design boundary rather than a defect.** N performs under an arm with
work after its resume leave N pending continuations; that is what the program means, and no stack
size makes it O(1). Moving them off the host stack requires CPS-converting the ARM at its `Resume` —
which `Cps` already knows how to do for a performing function — and that is a different change with
its own risks. The tail-resumptive case needs none of it: since `1adbb3274` it is constant-stack and
runs 200 000 iterations at any budget.

512m rather than a number of my own: it is what the sibling lane in the same file already asks for,
so the two are comparable by construction and there is one value to change if it is ever wrong.

## v3-corpus-report-excluded-exec-lane-cases-on-a-field-about-another-backend

<!-- status: fixed
     lane: v3
     area: conformance
     kind: apparatus
     gate: v3/corpus-report.sh
     fixed-in: ad741e91b
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: v3/corpus-report.sh --exec --list-diff, before and after
     impact: none -->

`holds_v2` asked `backends: … v2 …` on BOTH lanes. That is the right question for the DEFAULT lane,
which executes through the v2 bridge and therefore inherits v2's differences — the correction that
paragraph was written for. **`--exec` inherits nothing**: it runs v3's own executor start to finish,
so excluding a case there on the strength of a lane that never ran counted a v3 difference as
somebody else's.

    exec lane, before -> after      DIFF 0 -> 1     LANE-EXCL 1 -> 0     N 279 unchanged

One cell, and `N` did not move: this is not a number being adjusted, it is one verdict moving from
"not ours" to "ours". The case is `js-int-division-by-zero`, which the report's own header cites as
the REASON the LANE-EXCLUDED bucket exists — v3 prints `inf` where the case expects `Infinity`. On
the bridge that is v2's formatting and genuinely not v3's to answer for; on the executor it is v3's
own output and nothing else's.

**NOT "REQUIRE `v3` IN `backends:`"**, which is the obvious shape and the wrong one: no case in the
corpus lists `v3` today, so requiring it would have excluded EVERYTHING and read as a healthy report.
`known-red:` stays the only escape hatch on that lane — named, with a reason, and expiring. Nothing
declares `known-red: v3` today, which is the honest state.

The report now says the two lanes measure different populations instead of printing two numbers that
look comparable.

**One self-inflicted defect on the way, caught by its own gate.** The population note was written
with backticks inside a double-quoted `echo`, so bash ran `known-red:` as a command and printed
`known-red:: command not found` into the report. `no-live-backticks-in-heredocs` names exactly this
and the project has it written down; it still happened, in the same commit that added the note.

## v3-a-non-tail-resumptive-arm-held-one-host-frame-per-perform — 17, in fact

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: c29bb6c52
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: v3/tests/effects/deep-non-tail-arm.ssc, and the same shape at 200 000 on a 1 MB stack
     impact: workaround -->

A handler arm that does work AFTER resuming — `case op(k) => k(1) + 0`, `x :: resume(())`,
`k(1) + k(2)` — is live across the whole resumed computation. Calling `resumeCont` from inside it
left the arm's frames on the HOST stack while the rest of the program ran; the rest performed again,
its arm called again, and the depth grew with the number of performs.

**MEASURED, NOT ESTIMATED: 17 host frames per perform**, from the overflow trace, in a repeating
cycle of

    resumeCont -> exec x5 -> callFunc + exec x5 -> exec x5 -> resumeCont

so 150 iterations exhausted the 2 MB default. On a deliberately small 1 MB stack the same shape now
answers at **200 000**.

**ONE PENDING ARM IS REAL AND WAS NOT THE PROBLEM.** N performs under such an arm leave N pending
continuations, because that is what the program means. The choice is only where they live, and they
are on a heap list now — the same trade `Cps` already makes for the performing function.

**THE DRIVER HAD TO BE AT THE `handle`, and that is forced.** An exception unwinds to wherever it is
caught, so only a loop there releases the frames of everything between. A loop inside `Perform`
would still sit under the continuation that reaches the NEXT perform: fewer frames per step, same
growth. So `Perform` throws the arm instead of running it — which replaces `PerformAbort`, that
carried the arm's VALUE home and there is no value yet.

**THREE THINGS THE FIXTURES FOUND AND READING DID NOT:**

* **the rest of an arm is still the arm.** `k(1) + k(2)` has its second resume in the parked
  remainder, and without the arm's own delimiter there it resumed in place.
* **a called function is NOT the arm.** `opts.flatMap(opt => resume(opt))` resumes inside a lambda
  `flatMap` is iterating; a request there unwinds out of `flatMap` and abandons the rest of the
  iteration. `handle-return` answered `List(11)` where the cross product is `List(11, 21, 12, 22)`.
* **and that rule has TWO DOORS.** Clearing the flag in `callFunc` was not enough: `fastApply` sends
  a closure through `callClos1` instead, and the same row stayed red until both were closed. One
  rule, two entry points, and the first fix looked complete.

**THE BOUNDARY THIS LEAVES** follows from the second of those and is a boundary rather than an
omission: an arm resuming inside a function it CALLS keeps the recursive path — correct, as before,
but O(N) — because there the rest of the arm is not a suffix of an instruction list but the middle
of someone else's iteration, and there is nothing for the machine to walk. Loops that perform are
written the other way.

**THE FIXTURE IS 5 000 AND THE NUMBER IS THE BRIDGE'S.** `effects-gate.sh` wants three-way
agreement, so a row can only be as deep as the shallower lane, and the v2 VM recurses per resume and
overflows between 5 000 and 10 000. The row pins the ANSWER on both lanes; the depth is one lane's
property and is recorded as a measurement in `v3/specs/10-ssc-ir.md` §3, the way that section
already records a clause no fixture can hold.

## v3-tail-resumptive-fast-path-loses-a-value-when-the-arm-performs-outward — 12 where the answer is 13

<!-- status: fixed
     lane: v3
     area: runtime
     kind: regression
     gate: v3/effects-gate.sh
     fixed-in: 1c5b49ebe
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: v3/tests/effects/arm-performs-outward.ssc
     impact: wrong-answer -->

**MY OWN REGRESSION, from `1adbb3274`, and every gate was green over it.**

```scalascript
handle(inner()) { case a(k) => k(h() + 5) }      // h() performs B, handled further out
handle(outer()) { case b(k2) => k2(3) + 1 }
```

| | answer |
|---|---|
| executor | **12** |
| v2 bridge | 13 |
| the same program with a NON-tail-resumptive arm | 13 |

Two independent witnesses say 13; the outer arm's `+ 1` was lost.

**WHY.** `1adbb3274` stopped splitting an operation whose arms are all tail-resumptive, which selects
the fast path — and the fast path keeps a LIVE `Perform` frame plus a `resumedWith` field: the arm
runs inside that frame, resumes, and the value travels back through it. An effect the arm performs
ITSELF, handled further out, unwinds through that frame, and when the outer continuation returns
there is no frame left to receive the resumed value. Before the skip the operation was always split
and the arm got a real continuation, so this used to work.

**HOW IT WAS FOUND, and this is the part worth keeping.** By re-running a probe from two days earlier
while answering "is anything left?" — and noticing the number had CHANGED. Not by a gate. The change
that caused it shipped with 27 fixtures on both lanes, three v3 gates, and an A/B over every
conformance case that mentions an effect: 54 cells, none moved. All of it honest and all of it blind,
because **no case anywhere has an arm that performs an outer effect**. A differential compares what
someone wrote down.

**THE FIX TOOK TWO GOES, and the second was the A/B's doing.** Banning every call in an arm removed
the wrong answer and also removed a case the skip had just fixed — `head-field-effect-shadow`, whose
arm is `resume(simGigs())`, a direct call to a function that performs nothing. The condition now
computes the reachable-perform set instead: a `Perform` anywhere, then transitively through direct
calls. One exception the blunt rule would also have eaten — the resume's own call is not a call
outward, because `k(1)` lowers to a `CallV` on the arm's `k` register.

`arm-performs-outward` is a three-way row: the bridge answers 13 on its own, so the fixture is not
the executor grading itself.

## v3-a-continuation-resuming-through-a-nested-handle-loses-it — executor 1, bridge 107

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: f8d36e0f3
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: v3/tests/effects/continuation-crosses-a-nested-handle.ssc
     impact: wrong-answer -->

**PRE-EXISTING, and checked before it was called mine.** The same program answers 1 on a build from
before any of this week's effect work, and the v2 bridge has answered 107 throughout — so the lanes
have disagreed here for as long as both have run it.

```scalascript
handle {                                     // outer: answers B
  acc = handle(innerBody()) { case a(k) => k(1) }    // inner: answers A only
  acc                                        // innerBody performs B — it passes the inner handle by
} { case b(k2) => k2(3) + 1 }
```

| | answer |
|---|---|
| executor | **1** |
| v2 bridge | 107 |
| hand-derived | 107 |

**THE ARITHMETIC NAMED IT, not the code.** `1` is `k2(3) + 1` with `k2(3) = 0` — `acc` still holding
the zero it was initialised with. So the inner handle contributed NOTHING: when the outer handler
resumes, the continuation runs back through the inner handle's BODY, and the inner `Handle`
instruction is gone — abandoned by the unwind that carried the outer perform home. Nothing was left
to turn the body's value into the handle's.

**ONE HYPOTHESIS DIED ON THE WAY** and is kept because it was the more alarming one: `1` looked like
the inner arm's `k(1)` leaking out, which would have meant two effects sharing an operation index.
The IR says `op 0` and `op 1` — distinct — so that was out after one command.

**THE FIX IS THE SHAPE EVERYTHING ELSE HERE USES.** `Handle` records a `PendingFrame` on the way in,
exactly as a region does, and that frame carries its handler and its destination register. When the
walk reaches it, it does what the instruction would have done next: apply the return clause to the
value the body left in that register. The frames BEFORE it in the segment are the body's own
remainder, so by then the value is there.

Found by building a probe for the position the refusal claimed was unreachable — not by any gate.

## v3-bridge-resume-nests-one-hundred-v2-frames-per-resume — measured, and deliberately not fixed

<!-- status: wontfix
     lane: v3
     area: codegen
     kind: perf
     gate: v3/effects-gate.sh
     fixed-in: -
     reported-by: claude-code
     reported-at: 2026-08-23
     repro: a `while` loop performing under `case op(k) => k(1) + 0`, run with `--bridge`
     impact: none -->

**THE NUMBER FIRST.** A resume on the v2 bridge costs **~101 v2 interpreter frames**, so a
non-tail-resumptive arm over a deep loop overflows between 5 000 and 8 000 iterations. The executor,
since `c29bb6c52`, has no such limit — 200 000 on a deliberately small 1 MB stack.

| shape | executor | v2 bridge |
|---|---|---|
| `case op(k) => k(1) + 0` over a loop | unbounded | **5 000–8 000** |
| `opts.flatMap(opt => resume(opt))` | 300 000+ | below 20 000 |
| `case op(k) => k(1)` (tail-resumptive) | unbounded | 200 000 |

**IT IS THE EMITTED RUNTIME, NOT THE PROGRAM.** Two programs of completely different shape — a
`while` loop with a split caller, and a recursion with none — cost **101 and 103** frames per resume.
So the cost is fixed and lives in the resume path itself: the arm closure, `effResume`'s three nested
`let`s, the composed lambda, `effRun`, and the continuation's own body, each contributing evaluation
frames that stay while the continuation runs.

**BOTH REPAIRS WERE PRICED AND BOTH WERE REJECTED, which is why this is `wontfix` rather than open.**

*Flattening the runtime* was the cheap one and it is not cheap enough. The frame histogram over one
cycle is 29 `Runtime.run`, 19 `go$1`, 19 `compile$$anonfun$22`, 13 `anonfun$6` — 80 of 103 are
GENERIC evaluation frames spread across the nested chain. The recursive helpers everyone would reach
for first (`effChain`, `effPushAll`, `effPopAll`) are single frames each. Rewriting them as loops
buys about 10%, not the 2x that was estimated before the histogram was read.

*The driver loop* — the mirror of what `c29bb6c52` did in the executor — would make it unbounded, and
the bridge has the piece that makes it easier than it sounds: its abort is a FLAG, not an exception,
so frames already return rather than unwind. It was rejected on a different ground: **the bridge is
the oracle.** Every wrong answer found in v3's effects over the last two days was caught by the
bridge answering independently and correctly — `1001`, `11`, `12`, `1`, `0`. Building the executor's
machine into it would make the two witnesses resemble each other, and the differential is worth more
than the depth.

**AND NOTHING REAL IS NEAR THE LIMIT**, which is the fact that decides it. Across both corpora the
deepest program using a non-tail-resumptive arm is `bench/corpus/effect-multishot` at **100**
iterations — fifty times under. `effect-stream` runs 10 000, and does not count: `runStream` is an
`extern def` answered by a plugin, so it never enters this machinery at all.

**WHAT WOULD REOPEN IT**, stated so the decision is re-decidable rather than permanent: a program that
performs more than a few thousand times under an arm with work after its resume, on the bridge lane.
Then the driver loop is the design, and the cost — a less independent oracle — is paid knowingly.

## scaffolded-project-cannot-resolve-its-sbt-plugin — `ssc new` then `sbt compile` fails for everyone who is not a contributor

<!-- status: fixed
     lane: apparatus
     area: cli
     kind: bug
     gate: tests/e2e/emitted-coordinate-is-published.sh
     fixed-in: 13b9f3e11
     reported-by: claude-code
     reported-at: 2026-08-19
     ssc-version: 3fc7ee265
     repro: `ssc new demo --template app` then `sbt -Dsbt.ivy.home=<empty> compile`
     impact: blocks -->

The first two commands a new user runs. Measured with a CLEAN ivy home, which is the whole point —
from a checkout it passes, because `install.sh --dev` publishes the plugin locally and its own
message says what happens otherwise ("`ssc new` will produce projects that cannot load"):

```text
[error] Error downloading org.scalascript:sbt-scalascript-interop;sbtVersion=1.0;scalaVersion=2.12:0.1.0-SNAPSHOT
[error]   not found: https://repo1.maven.org/maven2/org/scalascript/sbt-scalascript-interop_2.12_1.0/…
[error]   not found: https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/…
```

Five templates — `app`, `web-app`, `wasm-app`, `lib`, `dsl` — carried
`addSbtPlugin("org.scalascript" % "sbt-scalascript-interop" % "0.1.0-SNAPSHOT")` with no resolver,
and nothing published that plugin anywhere. So every scaffolded project was unbuildable for anyone
who installed a release.

**The gate beside it said so and could not act.** `emitted-coordinate-is-published` exempted the
plugin from the no-SNAPSHOT rule in as many words: "there is no published release at all — nothing
publishes it anywhere — so the rule is not merely unmet, it is unsatisfiable, and a gate that cannot
be satisfied stops being a gate… whether it should be published for real is open." That was an
accurate description of a hole, held open for want of somewhere to publish.

### Fixed by publishing it where the runtime backends now go

The static Maven tree built for `emitted-server-backend-coordinate-resolves-nowhere` answers this
too. The plugin build gets a real version (`0.2.0`) and a `publishTo` into `releases/maven`; the five
templates name that version AND carry the resolver, because a correct coordinate is still
unreachable when the tree is not Maven Central.

**Proven end to end, with none of the conditions that make it pass from a checkout**: a fresh
`ssc new demo --template app`, a clean `sbt.ivy.home`, the resolver pointed at the tree, and the
RELEASE binary on `PATH` rather than a dev launcher —

```text
[success] Total time: 1 s
```

The dev launcher is worth a note: `bin/ssc` is the standard tier and refuses `ssc build` /
`ssc generate-facade`, which the plugin shells out to, so the same test run against a checkout's
`bin/ssc` fails at a LATER step for an unrelated reason. The shipped binary is the full CLI and does
not.

**Gate**, replacing the exemption with the check it was standing in for: the templates must name the
version this build produces, that version must be present under `releases/maven`, and each template
must carry a resolver. Negative controls: removing the published pom fails all five templates;
removing one resolver line fails that one.

**Not gated end to end.** A gate that scaffolds and runs `sbt compile` costs minutes and needs sbt on
the runner; the three offline rows catch the regression that actually happened — a version bumped
without publishing, or a resolver dropped. The end-to-end run above is recorded here instead.

**Noticed while proving it, not fixed**: the plugin prints
`[error] [ssc] generate-facade: no legacy facade entries found; nothing written.` on a project that
has no facades — an informational line wearing `[error]`, in the output of the first build a new user
ever runs.

## emitted-coordinate-gate-red-on-every-release-commit — the check that allows a release version cannot see the tag that authorises it

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: tests/e2e/emitted-coordinate-is-published.sh
     fixed-in: 2855c187a
     reported-by: claude-code
     reported-at: 2026-08-18
     ssc-version: 3fc7ee265
     repro: the v0.1.1 release commit, run 31145893683
     impact: blocks -->

Found in the pre-release audit for 0.2.0, by asking what the release COMMIT would do to CI rather
than what the release workflow would do. It has already happened once:

```text
5dfad1c58  release: refresh the record after rebasing onto current main
  Publish qualified tag: success        (all three platforms qualified)
  smoke — the fast repo-wide suite: FAILURE
    FAIL emitted-coordinate-is-published
    emitted-coordinate: FAILED — ThisBuild / version is '0.1.1', not a SNAPSHOT,
```

The rule is right: between releases the build version must be a SNAPSHOT, or every intermediate
build calls itself the release; a plain version is allowed only on the commit that IS that release.
What the check could not do is establish the second half. It asked `git rev-parse refs/tags/vX`,
and a tag is absent from that checkout for two independent reasons — the v0.1.1 release was cut on a
`release/0.1.1` branch and the tag did not exist yet when smoke ran, and CI's `actions/checkout@v4`
is shallow and fetches no tags even when it does.

So the one commit whose colour anybody looks at was red, on a gate that was working exactly as
written.

**Fixed by asking the REMOTE when the local ref is missing**, which costs nothing on an ordinary
commit: those carry a SNAPSHOT and return before the tag branch is reached. The failure message now
distinguishes the two causes, because "not tagged yet" and "tagged, but this checkout cannot see it"
need different actions, and it names the sequence that avoids the first:

```text
git tag v0.2.0 && git push --atomic origin main v0.2.0
```

**An annotated tag answers its own object, not the commit, and that cost the first attempt.**
`git ls-remote --tags origin refs/tags/v0.1.1` returns `cdb84377…` — the tag OBJECT — while the
commit is `5dfad1c5…`, and the comparison then fails on a tag that is perfectly correct. The commit
sits on the dereferenced `^{}` line, which the exact pattern does not match; the trailing `*` is the
whole fix. Lightweight tags have no such line and are handled by the fallback.

Verified in a `--shared --no-tags` clone whose `origin` is the real repository, so the local refs are
genuinely absent while the remote has them — three states, each measured: an ordinary SNAPSHOT commit
passes without a network call; the v0.1.1 release commit (plain version, tag only on the remote, the
state that reddened the last release) now passes; a plain `0.9.9` with no tag anywhere still fails,
naming both causes.

## emitted-server-backend-coordinate-resolves-nowhere — the gate checks it is not a SNAPSHOT, and nobody checks it exists

<!-- status: fixed
     lane: apparatus
     area: cli
     kind: bug
     gate: tests/e2e/server-backend-resolvable-gate.sh
     fixed-in: 1d722602b
     reported-by: claude-code
     reported-at: 2026-08-18
     ssc-version: 5675ec8f9
     repro: `ssc compile --server-backend jetty <file>` then read the generated directive
     impact: blocks -->

`ssc compile --server-backend jetty|netty` writes this line into the user's script:

```text
//> using dep io.scalascript::scalascript-runtime-server-jvm-jetty:0.1.1
```

and no resolver directive beside it. Measured 2026-08-18:
`https://repo1.maven.org/maven2/io/scalascript/` is **404** — nothing from this project has ever been
published to Maven Central. So the coordinate resolves nowhere, and the failure lands on the user at
their first compile, in a file we wrote for them.

**The gate that owns this line cannot see it.** `tests/e2e/emitted-coordinate-is-published.sh` was
written for exactly this string and checks that it is not a `-SNAPSHOT` — true here — while its own
comment states the rule it cannot enforce: "must name the last PUBLISHED release". There is no
published release to name; the premise is unmet, not the check.

Found while auditing the release path for `install-channels-are-fiction`, which is the same shape one
layer out: that entry is about the commands we tell a user to RUN, this is about the coordinate we
write into their BUILD.

**Two ways out, and the choice is the project's, not a fix to guess at.** Publish the runtime-server
artifacts to Central (or anywhere `//> using dep` can reach) as part of the release, and let the
version constant follow it — or stop emitting the directive and inject the backend some other way.
Both are release decisions. What should NOT happen is a third release that ships the line unchanged
because the gate beside it is green.

### FIXED 2026-08-18 — a static Maven tree, and an offline jar beside it

The owner chose to publish. Central is still not the route — it verifies namespace ownership by DNS
and `scalascript.io` is NXDOMAIN — so the artifacts are served as a STATIC MAVEN TREE from the Pages
site this project already deploys. `sbt publishServerBackends` writes it, `releases/maven/` holds it,
`pages.yml` copies it into `_site/maven`, and the generated script gains the line that was missing:

```text
//> using repository https://sergey-scherbina.github.io/scalascript/maven
//> using dep io.scalascript::scalascript-runtime-server-jvm-jetty:0.1.1
```

**The tree is IN THE REPOSITORY and that is the load-bearing decision.** A Pages deployment replaces
the whole site, so a tree rebuilt at deploy time from the current version would delete every older
release the moment `main` moved to the next SNAPSHOT — and a coordinate that shipped has to keep
resolving. In git it is cumulative by construction and reviewable in a diff. It costs 752 KB per
version, after switching off sources and javadoc: with them a version is 16 MB, and 25 of the first
34 MB tree was javadoc that no `//> using dep` ever fetches.

**0.1.1 is backfilled**, built from the tag, so the coordinate the CLI emits today resolves rather
than only becoming true at the next release.

**The offline path, asked for alongside**: `SSC_SERVER_BACKEND_JAR=<path>` emits `//> using jar`
instead, and then nothing is resolved for the backend at all — for a proxied network, an air-gapped
builder, or pinning exact bytes. It has to be an ASSEMBLY (`sbt runtimeServerJvmJetty/assembly`,
~13 MB): `//> using jar` does not resolve transitives, so a thin jar compiles and then fails on the
first Jetty class. The assembly's merge strategy CONCATENATES `META-INF/services` — a first-wins
merge would drop the `ServiceLoader[HttpServerSpi]` registration and the program would quietly start
on `jdk` while the user believed they were on Jetty — and discards `module-info.class`, which Jetty
ships in every artifact.

### The gate was green three times before it could see anything, and each reason is worth keeping

1. **The first negative control never built.** Removing the repository directive left `repoUrl`
   unused, `-Werror` failed the compile, `install.sh --dev` exited 0 anyway, and the gate ran against
   the PREVIOUS jar. Verified since by reading the string out of `bin/lib/ssc.jar` at each step
   rather than trusting an exit code.
2. **The shared resolver cache.** With `~/.cache/coursier`, the artifact from an earlier run was
   already there, so the row passed with no repository at all. It now runs with `COURSIER_CACHE`
   pointed into its own sandbox — 19 s and 38 MB cold, and the only state in which "it resolved"
   means anything.
3. **A plant that compiles.** The control that finally worked replaces the directive with a COMMENT
   line carrying the same interpolation, so the code still compiles and the script still lacks a
   repository. With it, the gate fails; restored, it passes.

Verified: `tests/e2e/server-backend-resolvable-gate.sh` — the emitted version is present in
`releases/maven` for both backends, the default URL is this repository's Pages tree (derived from
`origin`, not restated), the generated script RUNS against a local server over that same tree, and
the offline jar path runs with zero `io.scalascript` fetch attempts. `cli/testOnly
ServerBackendInjectionTest` 8/8, including a row that the jar path and the dep path are mutually
exclusive.

## install-channels-are-fiction — every advertised way to install ScalaScript was one that cannot work

<!-- status: fixed
     lane: apparatus
     area: docs
     kind: bug
     gate: tests/e2e/install-channels-are-real.sh
     fixed-in: dc39442c8
     reported-by: claude-code
     reported-at: 2026-08-18
     ssc-version: 36c7e75d2
     repro: the four measurements below
     impact: blocks -->

Found while answering "can we release 0.1.2 today". Checking the release path meant reading what a
user is told to run, and **not one of the four documented routes could work.** Measured, not read:

```text
https://releases.scalascript.io/coursier.json    does not resolve
https://get.scalascript.io                       does not resolve
github.com/scalascript/homebrew-tap              404
repo1.maven.org/maven2/io/scalascript/           404   (nothing published, ever)
releases/install.sh                              downloads ssc.jar — NO release has one
```

The last line is the sharpest. `releases/install.sh` fetched `$BASE_URL/ssc.jar` at a hardcoded
`VERSION=0.1.0`, and both v0.1.0 and v0.1.1 publish **three native binaries plus their tarballs and
`.sha256` files, and nothing else**. So the script could not have installed anything since the first
release, and the version constant was stale on top of that. `releases/homebrew/ssc.rb` pointed at the
same non-existent jar and carried `sha256 "REPLACE_WITH_RELEASE_SHA256"` — a placeholder, shipped.

**`specs/arch-ssc-new.md` recorded the coursier channel as "✓ Landed 2026-05-29".** A descriptor FILE
was written that day. Nothing was ever published. That tick is why the three routes kept being
copied into new docs — `README.md`, `docs/user-guide.md` and `docs/getting-started-standalone.md`
each carried at least one, the last of them under the heading "Recommended release channels".

### What is landed instead

`releases/install.sh` rewritten against what the release actually publishes: pick the artifact for
`uname -s`/`uname -m` out of the three ids the release matrix builds, download the archive and the
`.sha256` beside it, VERIFY, unpack whole, link the launcher, and then run `ssc --version` before
printing success — the old script printed "Installed ssc 0.1.0" over a jar it had failed to fetch.

**No version constant.** GitHub serves `/releases/latest/download/<asset>`, so the default follows the
newest release and there is nothing to bump; `SSC_VERSION=0.1.1` still pins.

**The layout is load-bearing and the installer had to learn it.** `NativeImageInstallRoot` resolves
the staged front by `toRealPath()` on the running executable and then looks for
`bin/lib/standard/native-front` in its parent and grandparent. So the archive is unpacked WHOLE into
`~/.local/lib/scalascript` and `~/.local/bin/ssc` is a SYMLINK into it — which works precisely
because that resolution follows symlinks. Copying the bare `ssc` binary out of the archive, which is
what a naive installer does, produces a binary that cannot find its own front.

Verified end to end against the real published release, not against a fixture:

```text
PREFIX=<tmp> SSC_VERSION=0.1.1 sh releases/install.sh
  Downloading ssc-macos-arm64 from v0.1.1...
  Installed ssc 0.1.1 to <tmp>/bin/ssc
<tmp>/bin/ssc run p.ssc    ->  the program's output
```

**And that run turned up the reason this matters beyond tidiness**: the shipped v0.1.1 binary prints
the program's output TWICE for a file that ends in `main()` — `v2-front-fallback-runs-the-program-twice`,
fixed on main in `ae5b09418` eleven days after the tag. The newest release anyone can install today
doubles the most common file shape in this repository.

**Deleted rather than left looking ready**: `releases/coursier.json` and `releases/homebrew/ssc.rb`.
Neither channel exists and neither file could be made to work by editing it — the formula needs a
tap repository and a per-release sha256, the descriptor needs an actual publish to Central. What each
would take is above; git history holds both files.

**Gate**: `tests/e2e/install-channels-are-real.sh`, offline by construction — it compares the
`artifact_id`s `native-release.yml` publishes with the ones the installer can select, refuses an
`ssc.jar` fetch or a hardcoded version, and refuses any `cs`/`brew`/`curl` COMMAND naming a dead
host in `install.sh`, `README.md`, `releases/` or `docs/`. Prose explaining why a channel does not
exist stays legal, or this entry's own correction would trip it. It carries a self-test that plants
`brew install scalascript/tap/ssc` next to such a prose line and requires the scan to see exactly
one. The command scan found a leftover in `README.md` that I had already missed by hand.

## rust-any-parameter-does-not-lift-a-call-result — `show(n())` at a `v: Any` parameter, five lines

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-any-call-lift-gate.sh
     fixed-in: 3efa77b7b
     reported-by: claude-code
     reported-at: 2026-08-18
     ssc-version: 1496ba89c
     repro: inline below — five lines
     impact: blocks -->

Found while writing the gate for `rust-packaged-module-loses-every-argument-coercion`: the first
draft of the probe used this shape, failed, and the failure survived removing every object and every
frontmatter key from it. It is a DIFFERENT defect and is filed rather than folded in, because a fix
for the sibling-call one does not touch it.

```scalascript
def n(): Int = 7

def show(v: Any): String = "v=" + v

def main(): Unit = println(show(n()))
```

```text
run:        v=7
build-rust: error[E0308]: mismatched types — expected `Value`, found `i64`
            crate::runtime::_println(show(n()));
```

`show` renders as `pub fn show(v: crate::value::Value)`, correctly. What is missing is the lift at
the CALL: `Value::from(n())`.

`needsAnyCoercion` decides this, and for a `Value` target it answers yes for a case class, an
expression already known to hold a `Value`, a literal, or a closure parameter — a list that has been
widened twice already, each time by one shape. A CALL whose return type is known and is NOT `Value`
is the shape nobody added, and it is the ordinary one: any `def f(): Int` handed to any `def g(x:
Any)`.

The narrowing that comment documents argues for the general answer rather than a fifth shape:
`Value::from` is the identity on a `Value` and a lift on everything else. The reason to still be
careful is that `From` is not implemented for every Rust type this backend can produce — a
reference, a closure, `()` — so "always true" is not obviously safe, and the honest next step is to
widen to *an apply whose callee has a known, non-`Value` return type* and measure the survey, not to
widen to everything and hope.

### FIXED 2026-08-18 — one clause, bounded by what `mapType` can answer

`needsAnyCoercion` now also says yes for an apply whose callee has a KNOWN return type that is not
`Value`. Bounded rather than made total on purpose: `mapType` answers the empty string for `Unit`
and for a type it cannot resolve, and `From` is not implemented for every Rust type this backend can
produce — so an unresolved callee keeps today's behaviour instead of being handed a lift that may
not exist.

THE GATE CHECKS THREE RETURN TYPES AND ALL THREE WERE BROKEN, which is more than the entry above
claimed. `i64` and `String` were the expected pair; the third, a def returning a case class, was
expected to pass already because `argIsCaseClass` exists — it does NOT cover it, because that clause
matches a CONSTRUCTOR APPLICATION `Point(1, 2)` and `pt()` is a call to a def that returns one.
Measured, not assumed: the negative control fails with three E0308, not two.

Verified: `tests/e2e/rust-any-call-lift-gate.sh` PASS, all three rows compared against `run`
(`v=Point(1, 2)` included, so the Value variant is right and not merely present), plus a row that
fails if the oracle stops producing three lines. Negative control: with the clause removed and the
compiler rebuilt, `build-rust` fails with three E0308. `rust-std-survey` 77 REFUSED / 55 COMPILES
with BADRUST not grown; `v1-jit-size` PASS.

Not gated: no gate is filed with an open entry. The gate that closes this runs the five lines above
on `build-rust` and compares with `run` — a compile-only row would pass on a binary that prints the
wrong thing.

## rust-packaged-module-loses-every-argument-coercion — a call to a SIBLING object member is emitted uncoerced, and `package:` makes every def a sibling

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-sibling-arg-coercion-gate.sh
     fixed-in: 09b139ea4
     reported-by: claude-code
     reported-at: 2026-08-18
     ssc-version: 646fe53df
     repro: inline below — 20 lines and one frontmatter key
     impact: blocks -->

**THE TITLE THIS ENTRY WAS FILED UNDER NAMED THE SYMPTOM, NOT THE CAUSE, and the correction is the
useful part.** `package:` is not what turns coercion off. A call to a SIBLING MEMBER of the same
object is, and `Parser.wrapSectionInPackage` nests a packaged module's every code block in
`object std: object json: object core:` — so in a packaged module every top-level def is a sibling
member and the whole file loses its coercions at once. The prediction that fell out of that reading
was tested before anything was changed: a plain `object Hex: def digit; def first` with NO
frontmatter at all reproduces it exactly. The `package:` framing would have sent the next reader to
the manifest reader and the parser; the defect is in neither.

Found by reducing DOWN from `std/json-core.ssc` while working
`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds`. The same two defs, copied
byte for byte, coerce their arguments in one file and do not in another. The only difference is a
line of frontmatter:

```text
frontmatter: name + exports              coerced 4 times
frontmatter: name + exports + package:   coerced 0 times
```

Both emit `pub fn jsonCoreHexDigit(c: i64) -> i64`, so the signature is known either way. What stops
is `coercedArgs` in `renderTerm`'s apply arm — the block guarded by `_paramTypes.contains(calleeName)`
— and with it EVERY argument coercion this backend does: the `Value` lifting at an `Any` parameter,
the `coerceFromValue` narrowing, and the `charAt`→`i64` conversion added today.

**That is why json-core reports the families it does.** Of its 32 rustc errors, 8 are
`expected i64, found SscChar` and 6 are `expected i64 / Vec<i64>, found Value` — fourteen of them are
one cause: the coercion block never runs for a packaged module.

**Reduction, for whoever takes it.** Five hypotheses were falsified first, and they are listed so
nobody repeats them: the shape of the def (an `Any`-returning caller, a `val` inside an `else`
branch, two statements, three statements) coerces correctly; lib-vs-bin output makes no difference
(the same source without `main()` still coerces); frontmatter and code fences alone make no
difference. Only `package:` flips it.

```scalascript
---
name: probe-pkg
package: std.json.probe      # delete this line and the coercion returns
exports:
  - jsonCoreParseHex4
---

```scalascript
case class JsonCoreOk(value: Any, next: Int)
case class JsonCoreErr(message: String, offset: Int)

def jsonCoreHexDigit(c: Int): Int =
  if c >= 48 && c <= 57 then c - 48 else -1

def jsonCoreParseHex4(source: String, from: Int): Any =
  if from + 4 > source.length then JsonCoreErr("incomplete", from)
  else
    val a = jsonCoreHexDigit(source.charAt(from))
    JsonCoreOk(a, from + 4)
```
```

`emit-rust` it twice, with and without the `package:` line, and grep the generated file for `.0` on
the `charAt` call.

### FIXED 2026-08-18 — printing both is what found it, and the guess above was wrong

The paragraph below was the plan, and it is left standing because the plan was right and its guess
was not. `_paramTypes` DOES contain the bare name, with the right signature, for a packaged module —
printed, not assumed:

```text
nopkg: [dbg] callee=hexDigit known=true want=i64 args=source.charAt(from)=true
pkg:   [dbg] callee=hexDigit known=true want=i64 args=source.charAt(from)=true
```

Identical. The coercion is COMPUTED in both and then THROWN AWAY in one, which no amount of reading
the collection code would have shown. One more field in the same print named the culprit:

```text
nopkg: intr=None
pkg:   intr=Some(RuntimeCall(hexDigit))
```

`renderDef` registers every member of the owning object as an intrinsic pointing back at the def this
module generates — SITE 3 in its own comment, the machinery that lets a bare sibling call inside
`object Tool` reach `Tool_text`. That redirect sends the call into `applyNonListCtor`'s intrinsic
branch, which builds the call from the UNCOERCED `renderedArgs` while the ordinary user-def path
uses the coerced list. A rename took the runtime-call path and paid the runtime-call price.

**The fix is two lines and both are needed, because the feature has two spellings.** The intrinsic
branch takes the coerced list when the target is a def this module generates (`ctx.userDefs`
contains it — a real intrinsic's target is a `crate::runtime::…` path and never is), and
`calleeName` now also answers for `P.go(41)`, the QUALIFIED call from outside the object, which
resolves through the same redirect and had the same hole. The second was found by the gate, not by
reading: the object row failed on `P.go(41)` after the sibling row was already green.

**Verified.** `tests/e2e/rust-sibling-arg-coercion-gate.sh` covers both spellings and both error
families — `digit(s.charAt(1))` is the `SscChar` half, `take(v)` at a `v: Any` is the `Value`
narrowing half — each compared against `run` and with an oracle row pinning `digit('f') = 15` rather
than 102. Negative control: with the walker reverted and rebuilt, both rows fail with E0308.
`rust-std-survey` 77 REFUSED / 55 COMPILES with BADRUST not grown, and `v1-jit-size` PASS — the
change makes `renderTerm` SMALLER (a redundant `fn match` became an `if`), which is what let SITE 2
be answered there at all.

**What it does NOT do: it does not move std/json-core.ssc, and the 14 in this entry stays a
classification.** That file is still REFUSED for the paren-less `reverse`, so its 32 errors cannot
be re-counted without the cons-lowering bypass this project declined to land
(`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds` says why). Both families
this fix closes are the two that entry names, demonstrated on a reduction of it — but nobody should
quote a post-fix json-core number until someone measures one.

**Not fixed here, and the reason is scope rather than difficulty.** `_paramTypes` is keyed by
`d.name.value` and `calleeName` is the bare name, so the two look like they should agree — the next
step is to print both for a packaged module rather than to guess, and def collection is not somewhere
to edit on a hunch. What is landed today is the `charAt` half, which is correct on its own and
verified on a module without a package.

## two-fronts-number-generated-ui-signals-differently — same refusal, different counter

<!-- status: fixed
     lane: native
     area: front
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-16
     confirmed: yes
     fixed-at: 2026-08-17
     fixed-in: 6a3834920
     gate: tests/e2e/f-ui-signal-counter-gate.sh -->

Three corpus files refuse on BOTH fronts with the same diagnostic and a different generated number:

```
examples/frontend/busi-home-demo/busi-home-demo.ssc
  F    duplicate native UI signal '__computed__d346:fieldErr…
  ref  duplicate native UI signal '__computed__d364:fieldErr…
examples/frontend/form-demo/form-demo.ssc          F d342  /  ref d360
examples/frontend/std-ui/styled-primitives.ssc     F d263  /  ref d272
```

The refusal is the same defect on both lanes — a duplicate UI signal — so neither front is answering
wrongly. What differs is the COUNTER baked into the synthesized name, which means the two fronts walk
a different number of declarations before reaching the same one. That is a real divergence and it is
the kind that hides: as long as both lanes refuse, no output gate can see it, and the moment the
duplicate-signal defect is fixed the two fronts will start emitting differently-named globals for the
same program.

**Not the front cache**, checked rather than assumed: `SSC_FRONT_CACHE=off` reproduces F's number
exactly, on all three. Two further files joined the same bucket in the same run
(`tests/conformance/tkv2-busi-home.ssc`, `tkv2-tri-state.ssc`) and are also cache-neutral — their
first lines agree across all three lanes, so they diverge deeper than `head -1` and want their own
look.

### FIXED 2026-08-17 — it closed on its own, and the mechanism was not the one this entry guessed

**The entry guessed the two fronts walk a different NUMBER of declarations. Measured, they do not.**
`SSC_DUMP_DEFS` on both lanes for all three files:

```
form-demo          F 481   ref 481     identical in name AND order
busi-home-demo     F 509   ref 509     identical in name AND order
styled-primitives  F 377   ref 377     identical in name AND order
```

and the ids now agree — `__computed__d360` / `d364` / `__equality__d272` on both fronts, F having
moved UP to the reference's numbers from `d342` / `d346` / `d263`. It closed as a side effect of F's
coverage work (the guarded-arm fix landed between the two measurements and moved DECLINED 207 -> 194);
the attribution is circumstantial and is not claimed as more than that.

**The instrument had to be fixed before any of this could be measured.** `SSC_DUMP_DEFS` printed the
F lane always and the reference lane only inside the delegate-fallback — so for any file F compiles,
the reference side came back EMPTY. A first pass read that as "the reference emits 0 definitions",
which is the shape of `an-instrument-that-covers-one-path-reads-absence-as-evidence`: a 0 from an
unwired path is not data. `RunNativeV2` now dumps on the legacy lane too.

**What is NOT fixed is the fragility, and it is structural.** `NativeUiSites.annotate` builds the id
from `program.defs.zipWithIndex`, so the ordinal is positional: one dropped or reordered definition
renames every generated signal after it, for the same program, silently, on programs that RUN. The
id already carries the owner's name, so the ordinal adds nothing to uniqueness — but the format is
pinned by backend tests (`SwiftBackendTest` asserts `__computed__10:localeText:0`), and changing it
would paper over divergent IR rather than catch it. So the property is guarded instead:
`f-ui-signal-counter-gate` asserts both fronts emit the same definitions in the same ORDER, with a
zero-check first, because two empty lists compare equal and would report green while measuring
nothing. Both guard rows verified RED — the order row prints "the names match as a set, so a count
check alone would pass", which is the case it exists for.

**Where it came from.** The agreement gate's DISAGREE bucket went 4 -> 9 between two runs of mine.
The runs straddle ~80 sibling commits, and the five new rows are all cache-neutral, so they arrived
with that work rather than with the cache. Recorded here because the next person to read that bucket
will otherwise re-derive it: the ceiling the gate enforces is `F contradicted by BOTH other lanes`
(still 0), and DISAGREE is "both fail, different message" — informative, not failing.

**MOVED HERE FROM `tests/BUGS.md` 2026-08-18**, on `tests/e2e/area-map-gate.sh`'s verdict: the
`fixed-in` commit touches code this board owns (the root board is cross-module by design, and this defect spans two fronts). Filed-on-board is where the module's fixers read.

## smoke-red-for-everyone-on-coursier-jvm-index-429 — every push failed in `Setup Scala CLI`, before a test ran

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: -
     fixed-in: unrecorded
     reported-by: claude-code
     reported-at: 2026-08-17
     ssc-version: 5b767c82e
     repro: none — an external service outage; see the measurement below
     impact: blocks -->

From 14:19 on 2026-08-17, TWELVE consecutive `smoke.yml` runs by different agents failed, every one
in `Setup Scala CLI` or `Set up job` — before a single check executed. Last green: `8d58ef010`,
12:49.

**It was not anyone's change, and the proof is in the red list itself:** `692d13f0e` is in it, and
that commit edits `BUGS.md` and nothing else. A docs-only commit cannot break a toolchain setup step.

The step's own message pointed the wrong way:

```text
Error while getting https://github.com/coursier/jvm-index/raw/master/index.json:
coursier.cache.ArtifactError$NotFound: not found
```

**I first read that as "the file is gone". It is not.** The file is present in the upstream repo and
the branch was never renamed — checked both. Fetched directly:

```text
raw.githubusercontent.com/coursier/jvm-index/master/index.json   429   (later 503)
```

GitHub is rate-limiting / failing that URL, and coursier reports a 429 as `NotFound: not found`. A
message naming a missing file for a service that is merely refusing you is what cost the first hour.

**Why pinning did not help.** The workflows already said `jvm: temurin:21`. The pin fixes the
VERSION; coursier still has to translate that NAME into a download URL, and the translation is the
index fetch. Pinning was never the dependency — asking a rate-limited service to resolve the pin was.

**The fix, in all five workflows (seven Scala CLI steps):** install Java FIRST, then
`jvm: system`, so scala-cli uses the JVM already on the runner and the index is never consulted. The
`validate` job in `ci.yml` had no `setup-java` at all, so it gained one — otherwise `system` would
have meant "whatever the image happens to ship" rather than the Temurin 21 every other job asks for.

**This can only be verified by pushing, and that is stated rather than hidden.** A CI change has no
local test; the first run after the push IS the experiment. Pushing while CI is already red is a
bounded risk — the worst case is one more red in a sequence of twelve.

**The timing makes the attribution honest.** Checked immediately before the push: the index still
answers 503. So a green run cannot be the outage having quietly healed — the service is still
down, and the workflow simply no longer asks it anything. Had the limit cleared first, a green would
have proved nothing and this note would say so.

### The fix was pushed, FAILED, and was reverted — the diagnosis above is incomplete

`5be0e4883` carried it. Its own first run went red too, and where it failed is the finding:

```text
Set up job
  Failed to download action 'https://codeload.github.com/actions/setup-java/…'  503
  Failed to download action 'https://codeload.github.com/carabiner-dev/actions/…'  429
  ##[error]Response status code does not indicate success: 502 (Bad Gateway)
```

Not `Setup Scala CLI` any more — `Set up job`. GitHub cannot serve the ACTION TARBALLS themselves.
So this is a GitHub-wide incident, and the coursier index answering 429 was a SYMPTOM of it rather
than a separate cause. I diagnosed the first thing that broke and treated it as the thing that was
broken.

**No workflow change can help while that is true.** If action downloads fail, nothing in any
workflow runs, whatever it says.

**Reverted in the same claim, deliberately.** The change is defensible on its own merits — it removes
a real dependency on a flaky external index — but it is now UNVERIFIABLE, and parking an unverified
CI change in the shared path during an outage is a trap: when GitHub recovers, if `jvm: system` is
wrong in some job, everyone goes red with a cause that points at nothing they did. Re-land it when a
run can actually report, and verify it the way this note already described — check the index is
still failing immediately before the push, so a green attributes.

**What holds until then:** local `scripts/smoke-ci` is the only usable evidence, and `evidence
level 1` is unavailable to everybody. Releases in this window should say so rather than wait.

### RESOLVED 2026-08-18 by GitHub, not by us — and verified without a push

```text
14:19  first red        Setup Scala CLI / Set up job
15:47  twelfth red      (the window: every agent, every push)
16:16  index answers 200
16:2x  smoke RE-RUN on 5b767c82e — completed/success
```

The recovery was checked the honest way: `gh run rerun` on the SAME sha, no new commit. A green from
a fresh push would have carried a new tree and proved less; re-running an unchanged one isolates the
environment as the only thing that moved.

**`fixed-in: unrecorded` is the accurate marker.** The sentinel `specs/bugs-index.md` defines for
"fixed, provenance missing" fits an outage nobody here fixed: it ended upstream. Calling it `wontfix`
would be wrong (it is not still broken) and `unknown` would be wrong too (it is classified).

**The practical residue, and the reason this entry is worth keeping.** Twelve red runs in that window
belong to work that was fine. Anybody holding one should RE-RUN it — `gh run rerun <id>`, no commit —
rather than re-doing the work or hunting their own change. That is stated in the room as well.

**The workflow fix stays reverted, and re-landing it now cannot be sold as a cure.** Its verification
recipe required the index to be failing at push time so a green would attribute; the index answers
200, so a green today would only mean "nothing broke". If it is re-landed it should be filed as a
RESILIENCE change — it removes a dependency on an external service that failed twice in one
afternoon — with that weaker claim stated plainly, not as a fix for this entry.

## js-exec-import-is-a-syntax-error — `[exec, …](std/process.ssc)` emits a bundle that does not PARSE

<!-- status: fixed
     lane: js
     area: codegen
     kind: bug
     gate: tests/e2e/js-std-process-gate.sh
     fixed-in: d24a37f02
     reported-by: claude-code
     reported-at: 2026-08-17
     ssc-version: c314e871e
     repro: inline below
     impact: blocks -->

Found by CHECKING A CLAIM I HAD MADE WITHOUT MEASURING. I filed
`js-exec-ignores-every-processoptions-field-but-stdin` from READING `JsRuntimeFs.scala` — the js
`exec` takes `opts` and never touches it — and wrote in two gates that the js lane could not be
exercised here. Node is on this machine and `run-js` exists, so I tried it. The lane never got as
far as ignoring anything:

```text
[exec, ProcessOptions](std/process.ssc)
SyntaxError: Identifier 'exec' has already been declared
```

`JsRuntimeFs.source` defines `function exec` in the preamble; the import shim emitted
`const exec = std.process.exec` beside it, and a SyntaxError aborts the file before a line of it
runs. So `std/process` was not partly supported on this lane — it was UNUSABLE, and the first thing
a program using it saw was a node parse error naming a line the author never wrote.

**`JsGen.declaredBindings` exists for precisely this**, and its comment describes the failure in
advance: it lists every std/fs name so that importing `readFile` does not redeclare the preamble
function. `exec` was simply missing. `__spawnPid` was missing too, and is newer — the detached-spawn
work added it to the preamble without adding it here, which is this trap biting a second time inside
one week. `spawn` is deliberately NOT listed: it is an ordinary def in `std/process.ssc`, not a
preamble function, so its `const` is the real definition.

**What the fix made measurable, which is the part worth having.** With the bundle parsing, the js
lane runs `std/process` and the three pieces added this week work there: `exec`, `stdin` reaching a
child, and `spawn` returning a pid. The `stdin` implementation had been written BLIND — the lane
could not run when it landed — so this is the first evidence it was right.

**And it confirmed the sibling entry by measurement rather than by reading.** `cwd` answers `true` on
`run` and `false` on js, so `js-exec-ignores-every-processoptions-field-but-stdin` is now a measured
divergence instead of an inference from source. It stays open, and the gate here names it rather
than adding a row that would fail forever.

**THE WEAKNESS IS THE HAND-MAINTAINED LIST, not the two missing names.** Every function added to the
preamble that a user can import is another SyntaxError waiting for whoever imports it, and I created
one of the two myself. Filed as `js-preamble-binding-list-is-hand-maintained`. Deriving the set by
scanning the preamble for `function name(` is the obvious fix and is NOT done here: it would also
suppress a user module's own definition of a name the preamble happens to use, and that blast radius
wants measuring rather than guessing.

**Verified:** `tests/e2e/js-std-process-gate.sh` PASS — three rows compared against `run`, plus an
explicit check that the output carries no `SyntaxError` and an oracle row. Negative control with
`JsGen.scala` reverted and the launcher rebuilt: the parse check fires and all three rows go red with
empty js output, which is what a bundle that never ran looks like.

## js-preamble-binding-list-is-hand-maintained — every new preamble function is a SyntaxError waiting for whoever imports it

<!-- status: fixed
     lane: js
     area: codegen
     kind: apparatus
     gate: tests/e2e/js-preamble-collision-gate.sh
     fixed-in: 3440cab4c
     reported-by: claude-code
     reported-at: 2026-08-17
     ssc-version: c314e871e
     repro: none — see the two instances in `js-exec-import-is-a-syntax-error`
     impact: workaround -->

`JsGen.declaredBindings` is a hand-written set of names the js preamble already defines, so that an
import of the same name does not emit `const X = …` beside `function X` and break the bundle with a
SyntaxError. It works, and its comment explains itself well. The problem is that it must be updated
by hand every time `JsRuntimeFs.source` (or any other preamble source) gains a function a user can
import — and nothing checks that it was.

Measured cost so far: TWO instances, both found in one sitting.
`exec` had been missing for as long as `std/process` has existed on this lane, and `__spawnPid` was
missing because I added it to the preamble days earlier and did not know this list existed. Neither
produced a diagnostic anyone could act on — both produced `SyntaxError: Identifier 'x' has already
been declared`, pointing at generated code.

**The obvious fix is to derive the set** by scanning the preamble sources for `function name(`
instead of listing names. It is NOT obviously safe, which is why this is an entry rather than a
patch: `declaredBindings` does not merely prevent a redeclaration, it makes the import RESOLVE to the
preamble function. Seeding it from every preamble function would therefore also suppress a user
module's own definition of any name the preamble happens to use — `platform`, `cwd`, `exit`, `env`
are all preamble functions — and silently bind a user's `cwd` to the runtime's. That is a worse
failure than the one being fixed, because it compiles.

**Measure before choosing.** The question to answer first: how many names does the preamble define
that a `.ssc` module in this repository also defines at top level? If the answer is zero, deriving is
safe and the list can go. If it is not zero, the fix is a CHECK — a gate that fails when a preamble
function is not in the list — rather than a derivation.

### Measured 2026-08-17 — 30 missing, and the answer is the CHECK

```text
preamble top-level functions      224
std/**/*.ssc exported names      1452
overlap                            47
already listed                     17
MISSING                            30
```

And the 30 are not theoretical. One `emit-js` per name, then `node --check`:

```text
emit-js parses: 0   does NOT parse: 30
```

**Thirty of thirty.** `args cwd env envOrElse exit homedir hostname jsonParse jsonRead lookup
lookupOpt parseYaml pathBasename pathDirname pathExtname pathIsAbsolute pathJoin pathResolve platform
sep setLocale tempDir tempFile toYaml yamlArr yamlBool yamlGet yamlNum yamlStr yamlType` — every one
a `SyntaxError: Identifier 'x' has already been declared` for whoever imported it. The two instances
this entry was filed on were not a pair of oversights; they were the two that happened to be noticed.

**WHY NOBODY HIT THEM: `run-js` AND `emit-js` DISAGREE, PER PROGRAM.** `run-js` tree-shakes the
preamble, so a colliding `function` is sometimes dropped and the program runs. Measured: a one-line
program importing only `env` PASSES under `run-js` and fails to parse under `emit-js`; a four-import
program fails under both. My own first probe of four names used `run-js` and read a clean all-clear —
an artefact of the lane chosen and of how little that program imported. Neither command is the safe
one to test with, which is why the gate runs both.

**The 30 names are added, and the CHECK is the real fix.** `tests/e2e/js-preamble-collision-gate.sh`
recomputes the intersection from the tree every run, so the next preamble function added beside an
exported name fails there rather than for whoever imports it. The derivation this entry warned
against is still not done, and the warning stands: `declaredBindings` makes an import RESOLVE to the
preamble function, so seeding it from every `function name(` would bind a user module's own name to
the runtime's — a failure that compiles.

**One more thing the gate now carries, learned from its own first draft.** A row using
`jsonParse(…).toString` made the ORACLE stop after three of four lines, and the comparison loop
silently checked three rows while still printing PASS. The gate asserts its own row count now: a gate
must fail when its coverage shrinks, not quietly cover less than its header claims.

## v3-extension-vocab-gate-reads-a-comment-as-vocabulary — a prose example turned main RED

<!-- status: fixed
     lane: v3
     area: build
     kind: apparatus
     gate: v3/extension-gate.sh --self-test
     fixed-in: 754aff67a
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: cb740ecd3
     repro: inline below
     impact: blocks -->

Found because it turned my own push RED and I went looking for what I had broken. I had broken
nothing: `v3/extension-gate.sh` reads COMMENTS as vocabulary.

`f77b5c46f` documented the new String×Int lowering the obvious way:

```scala
// `"ab" * 3` IS "ababab" — string repetition, which Scala gives on `StringOps` …
```

Two quoted runs on one comment line. `exec_vocab` selects the `invoke` range, `grep -oE '"[^"]+"'`
takes `ab` and then `ababab`, and the identifier filter passes both — so the gate decided `Exec`
answers to a method called `ababab` and demanded `Lower` list it:

```text
RED  v3/src/Lower.scala's vocabulary is missing name(s) that v3/src/Exec.scala answers to:
       ababab
```

Nothing was wrong with the code, and nothing was wrong with the comment. The READER was wrong, and
the cost lands on everybody: main goes red, and the next person's green push reports a failure in a
file they never touched.

**Fixed by dropping whole-line `//` before extraction, on BOTH sides.** Deliberately narrow: a
trailing comment after real code would take the code with it if stripped naively, and a `//` inside a
string literal is exactly what this gate exists to read. Block comments are not stripped either —
`/* … */` does not appear in these ranges, and guessing at a shape that is not there is how the next
false positive arrives.

**Measured, both directions, because a filter that removes things must be shown to remove only the
right things.** On the Exec side it drops 8 names, every one of them example text from explanatory
prose — `A`, `ab`, `ababab`, `alice`, `any`, `carol`, `cd`, `x` — and adds none. 124 → 116.

**The LOWER side was the one worth fixing even though it changes nothing today**, and that asymmetry
is the finding. The check is `Lower ⊇ Exec`, so a quoted example in an Exec comment produces a false
ALARM, while one in a Lower comment SILENTLY SATISFIES the check for a name Lower does not handle —
the failure that does not announce itself. Measured before adding the filter: that block holds 17
comment lines and the vocabulary is 152 names with or without them, so nothing is masked today and
no name is lost. The filter is there for the comment somebody writes next. Fixing only the noisy
side would have left the dangerous twin.

**Verified:** `v3/extension-gate.sh --self-test` — baseline GREEN *and* a planted missing name
(`'++'`) still goes RED, which is the row that proves the filter did not blind the gate rather than
fix it. `scripts/smoke-ci` 112/112 (it was 111/112 with this red).

## v3-exec-has-no-string-repetition — `"ab" * 3` was `Mul on String ab and Int 3` on one lane of two

<!-- status: fixed
     fixed-in: f77b5c46f
     lane: v3
     area: runtime
     kind: bug
     gate: v3/front-gate.sh (v3/tests/front/string-repeat.ssc) -->

**THE CENSUS IS WHY THIS IS A DELETION AND NOT AN INVENTION:**

    native, interp, v3 --bridge   "ab" * 3  ->  ababab      "ab" * -1  ->  (empty)
    v3 exec, before               Mul on String ab and Int 3

Every lane that answers gives the same answer, and v3's OWN BRIDGE is among them — so the executor
was the only refusal, and the two lanes of one compiler disagreed (invariant I-3).

**NOT HYPOTHETICAL.** `tests/conformance/indent-block-statements.ssc` builds its indentation with
`" " * depth`. It was the corpus's last exec-side DIFF — correct on the bridge, dead on the
executor — and it now matches its expectation on both lanes. The corpus DIFF list is down to the two
effects cases (`v3-handler-arm-value-dropped-when-the-perform-is-a-statement`,
`v3-an-escaped-continuation-resumes-without-the-return-clause`), and N moved 235 → 237.

Zero and negative give the empty string, measured on the three answering lanes rather than taken
from Scala's documentation. One direction only: `3 * "ab"` is not written here, because Scala does
not have it either — a fourth answer to a question nobody asks.

## uniml-treats-a-hole-in-a-plain-string-as-interpolation — `"${" + x + "}"` collapses into one literal

<!-- status: fixed
     fixed-in: 94f0fb04c
     lane: v3
     area: front
     kind: bug
     gate: v3/front-diff.sh -->

**MEASURED — two lines:**

    val s = "<code>${" + x + "}</code>"

    native, int, v3's own front   <code>${X}</code>
    uniml (the DEFAULT front)     <code>${" + x + "}</code>       a WRONG ANSWER, exit 0

The whole expression collapses into ONE string literal: the scan takes the `${…}` hole branch, so
the closing quote of `"<code>${"` is read as content and everything up to the `}` is swallowed. A
well-formed tree of the wrong program.

**`${` IS SPECIAL ONLY IN AN INTERPOLATED STRING**, in Scala and here; in a plain one it is the two
characters `$` and `{`. The distinction is not structural — no amount of scanning tells the two
apart, since a nested string inside a real hole is legal — it is the character BEFORE the opening
quote: `s"…"`, `html"…"`, `f"…"` have an identifier there and a plain `"…"` does not. The fix gates
the hole branch on that.

**THE GUARD WAS ALREADY THERE AND MISSED BY ONE CONDITION.** `holeCloses` was added for this exact
failure and its comment describes it — "`"${"` followed by `"}"` two lines down SILENTLY SWALLOWED
the code between them" — but it stops its look-ahead at a NEWLINE, so the same swallow inside ONE
LINE still went through. A guard written against the case that was observed, not against the rule.

**HOW IT SURFACED, which is the part worth keeping.** `04d9e88e6` (the interpolator-adjacency fix)
moved `tests/conformance/markdown-html.ssc` from *refused by uniml* to *compiled by both fronts*.
`v3/front-diff.sh` compares the two fronts' AST OUTPUT with a ceiling of ZERO disagreements — and
what had been hiding behind the refusal was this: one `def`, one arm, a string that was not a
string. The gate went RED on `main` and named the file and the line.

**THE GATE THAT CAUGHT IT IS ONE I HAD NOT RUN.** I ran front, exec, capability and the corpus
report; `front-diff.sh` and `prelude-gate.sh` are also in the v3 workflow and I skipped both. The
capability gate compares ACCEPT/REFUSE and was green — it cannot see two fronts that both accept and
disagree, which is precisely what `front-diff` exists for. Unblocking a construct puts new files in
front of gates that had nothing to say about them before, so the gate set to run after a front fix
is the workflow's, not the one that looks related.

**Fixed** with all six green: front-diff (corpus 307 both print, agree 307, differ 0), front-gate 92,
exec-gate 88, capability OK, prelude GREEN, corpus N 234 → 235.

## v3-an-escaped-continuation-resumes-without-the-return-clause — `effect-deep-handler-state` calls 7 as a function

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh, v3/corpus-report.sh --exec
     fixed-in: de11d1380
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-16
     repro: tests/conformance/effect-deep-handler-state.ssc
     impact: wrong-answer -->

`tests/conformance/effect-deep-handler-state.ssc` expects `108`; v3 said `calling a non-function: 7`.

```scalascript
val f = handle(prog()) {
  case C.w(n, resume) => (s: Int) => resume(())(s + n)
  case Return(x)      => (s: Int) => x + s
}
println(f(0))              // native 8; v3 `calling a non-function: 7`
```

`Resume` applied the return clause through `handlers.headOption` — the DYNAMIC stack. Right while
the continuation is called inside the `handle`, wrong the moment it escapes: this arm RETURNS a
closure, the `handle` finishes, and `f(0)` resumes with nothing on the stack. `headOption` was
`None`, the lifting was skipped, and the program called the computation's bare `7`.

### The fix is the one the entry said was not one line, and it is not

The entry is right that the clause must be found from the CONTINUATION and that `k` was "an ordinary
`VClos` … and the executor has nowhere to put the frame today". So the continuation stopped being an
ordinary closure: `Value.VCont(clos, h, seg)` has somewhere to put it, and `resumeCont` reads the
clause from `c.h` and the perform counter from that same frame rather than from the dynamic head —
which also keeps working for the case the old comment protected, a `resume` inside a lambda running
in the lambda's frame.

**The second symptom went with it.** The full case's two ticks used to say `no handler for effect
operation 0`, because by the second perform the `handle` had returned. A resume now REINSTALLS its
handler for its own duration, so the second perform finds it.

## v1-exec-hangs-when-the-child-reads-stdin — `exec("cat", List(), ProcessOptions())` never returns

<!-- status: fixed
     lane: int
     area: runtime
     kind: bug
     gate: tests/e2e/process-stdin-gate.sh
     fixed-in: d6b77103f
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 3debf7393
     repro: inline below
     impact: blocks -->

Found while implementing `process-needs-a-stdin-pipe` (BACKLOG.md): a probe that ran `cat` with
nothing to give it never came back.

```scalascript
[exec, ProcessOptions](std/process.ssc)
def main(): Unit =
  val n = exec("cat", List(), ProcessOptions())
  println("returned: " + (n.stdout == ""))
main()
```

```text
bin/ssc run            returned: true
ssc-tools run --v1     (nothing; exit 124 under `timeout 60`)
```

`ProcessBuilder` PIPES stdin by default. Nothing wrote to that pipe and nothing closed it, so a
child reading to EOF never saw one and `exec` waited on a process that would never exit. There is no
timeout by default, so the program hangs rather than failing — the worst shape, because a hang has
no message to search for.

**The v2 os-plugin has closed that pipe since it was written**, with a comment saying exactly this:
`process.getOutputStream.close() // no stdin: a child that reads would otherwise hang`. The v1
plugin and the jvm runtime source did not. Two implementations of the same primitive, one of them
carrying the fix and the knowledge of why, and it never travelled — the same shape as every
two-front pair in this repository.

**Fixed:** both now close the pipe UNCONDITIONALLY — `try opts.stdin.foreach(write) finally close`
— so the None case behaves as v2 always did and the Some case gets its EOF after the write.

**Gated by a row that has to be able to hang, and therefore by a timeout.** `no stdin, no hang` in
`tests/e2e/process-stdin-gate.sh` runs every lane under `timeout`, because a gate without one does
not go red here — it never finishes, and a CI job that is killed at its cap reports `cancelled`,
which is not `failure`.

## js-exec-ignores-every-processoptions-field-but-stdin — `cwd`, `env`, `timeout` and `inheritEnv` are accepted and dropped on the js lane

<!-- status: fixed
     lane: js
     area: runtime
     kind: bug
     gate: tests/e2e/js-std-process-gate.sh
     fixed-in: ef6bf0a7c
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 3debf7393
     repro: none — needs a node harness
     impact: workaround -->

Found while adding `stdin` to every backend for `process-needs-a-stdin-pipe`. The js implementation
took `opts` and never read it:

```js
function exec(cmd, argsList, opts) {
  if (_nodeProc) {
    var result = _nodeProc.spawnSync(cmd, argsList, { encoding: 'utf8', shell: false });
```

`opts` is a parameter and appears nowhere in the body. So `cwd`, `env`, `timeout` and `inheritEnv`
are accepted by the type and silently dropped — exactly the shape the rust lane had until
`rust-exec-silently-ignores-every-processoptions-field` was fixed this week, and exactly as
invisible: no refusal, no warning, a child running in the wrong directory with the wrong
environment.

`stdin` was wired here (`spawnSync`'s `input`), so the lane now honours ONE of five fields, which is
recorded rather than tidy. The remaining four are one object literal away for `cwd`/`env`/`timeout`
— `spawnSync` takes all three — but `inheritEnv: false` is not: it means scrubbing the parent
environment and rebuilding it from `env`, which needs `{ env: {...} }` composed rather than passed
through, and getting that half-right is how a "scrubbed" child keeps its secrets.

**Not gated, and the reason is worth stating**: `tests/e2e/process-stdin-gate.sh` covers run, --v1
and build-rust and deliberately claims nothing about js.

### CONFIRMED BY MEASUREMENT 2026-08-17, and my reason for not measuring was wrong

I wrote above that exercising the js lane "needs a node harness this script does not have". Node is
on the machine and `run-js` exists, so that was an assumption, not a fact — and checking it turned up
something bigger: the lane could not run `std/process` AT ALL. `[exec, …](std/process.ssc)` emitted
`const exec` beside the preamble's `function exec` and the bundle failed to PARSE
(`js-exec-import-is-a-syntax-error`, fixed). So this entry described options being ignored by code
that never executed.

With that fixed, the divergence is now measured rather than inferred:

```text
                 run     js
cwd honoured     true    false
stdin reaches    piped   piped
spawn pid > 0    true    true
```

`stdin` and `spawn` work; `cwd` — and by the same reading of the source, `env`, `timeout` and
`inheritEnv` — do not, because `spawnSync` is called with no options object.

### Fixed the same day the measurement became possible

An options object is now built and passed. Three details, none of them optional:

* **`env` is a HAMT, not an object.** A ScalaScript `Map` on this lane is `_Map` -> `_hamtOf`, so
  `Object.keys` sees NOTHING and would hand the child an empty environment — silently, and a check
  that only asked whether the process ran would still be green. It is walked through the `entries()`
  the HAMT exposes.
* **`inheritEnv = false` scrubs BEFORE `env` is applied**, as on every other lane: the flag means the
  child sees only what the caller listed, and clearing afterwards throws those away too. The row
  pins `[][only]` rather than "HOME is gone", because a clear-after implementation passes the weaker
  check and prints `[][]`.
* **`timeout` is milliseconds**, and `spawnSync` reports the kill through `signal`, which the
  existing exitCode line already maps to -1 — the same answer `run`, `--v1`, jvm and build-rust give.
  Nothing new had to be decided.

`Option` is read with the `{_type: '_Some', value}` shape, not `$tag`/`_1` — the wrong one is two
functions up in the same file and would leave every option silently unset, which is the failure
these options exist to prevent.

**Verified:** `tests/e2e/js-std-process-gate.sh` PASS, 7 rows compared against `run` row by row, plus
an oracle row pinning stdin, the scrub and the timeout answer. Negative control with the runtime
reverted and the launcher rebuilt: the four new rows go red — `cwd false`, `env false`,
`[/Users/sergiy][]`, `timeout 0` — while the three earlier rows stay green, so the control separates
this fix from the SyntaxError one. `scripts/smoke-ci` green.

**This completes `std/process` across all five lanes**: `cwd`, `env`, `inheritEnv`, `timeout` and
`stdin` are now honoured on int, --v1, jvm, js and build-rust.

## rust-exec-silently-ignores-every-processoptions-field — `cwd`, `env` and `inheritEnv` were obeyed under `run` and dropped under `build-rust`, with no diagnostic

<!-- status: fixed
     lane: native
     area: runtime
     kind: bug
     gate: tests/e2e/rust-exec-options-gate.sh
     fixed-in: fa59b3493
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 6dce987e5
     repro: inline below
     impact: blocks -->

Found while sizing two rozum feature reports — `process-needs-a-detached-spawn` and
`process-needs-a-stdin-pipe` (BACKLOG.md) — which both want to EXTEND `ProcessOptions` on this lane.
Sizing them first is what turned this up, and it is the reason neither could have been implemented
honestly before it: a new field on a record the lane ignores is write-only metadata that passes
every check.

`_exec` took its options and threw them away:

```rust
// `_opts` is the std/process `ProcessOptions` (cwd/env/timeout) — accepted to match the
// 3-arg `exec(cmd, args, opts)` surface; cwd/env/timeout aren't applied yet.
pub fn _exec<O>(cmd: String, args: Vec<String>, _opts: O) -> ProcessResult {
```

That comment is why it survived: from inside the repository it reads as a known gap. From outside
it is nothing at all — no refusal, no warning, no `Unsupported`. Measured before the fix, the same
source on two lanes:

```text
                       run          build-rust
cwd honoured           true         false          <- compiled, ran, wrong answer
env honoured           true         false
inheritEnv=false       [][only]     [/Users/sergiy][]
```

The third row is the worst of the three and shows BOTH halves failing at once: the parent's `HOME`
leaked into a child the caller had asked to be scrubbed, AND the variable the caller did list never
arrived.

**Fixed.** The options are read through `Into<Value>` rather than by naming the struct — the struct
is GENERATED into the crate from the `case class`, and the runtime template cannot name it. Fields
are positional, in declaration order, the same access the v2 os-plugin already uses on this same
type and for the reason `Value::Obj`'s own comment gives. A shape the match does not recognise
leaves every option unset, which is exactly the old behaviour, so no unrelated caller can be broken
by this.

**`inheritEnv` scrubs BEFORE `env` is applied**, and the order is the implementation's only real
decision: the point of `inheritEnv = false` is that the child sees only what the caller listed, and
clearing afterwards would throw those away too. The gate's fourth row pins `[][only]` rather than
just "HOME is gone", because a clear-after implementation also passes the weaker check.

**`timeout` is still NOT enforced, deliberately.** `std::process::Command` has no timeout, so
honouring it means spawn, poll and kill — a different shape and a different failure contract. It is
filed as `rust-exec-ignores-processoptions-timeout` rather than half-done, because a timeout that is
accepted and not enforced is the same silent lie this entry is about. The gate asserts no row for
it, for the same reason.

**Verified:** `tests/e2e/rust-exec-options-gate.sh` PASS — four rows compared against `run` row by
row rather than against literals, plus a row asserting the oracle still answers `[][only]`. Negative
control with the runtime template reverted and the launcher rebuilt: FAIL on exactly the three rows
in the table above. Corpus unmoved: `rust-std-survey-gate` 77 REFUSED / 55 COMPILES, BADRUST not
grown; `v1-jit-size` PASS.

## rust-named-ctor-args-drop-the-defaulted-fields — `ProcessOptions(cwd = Some("/tmp"))` does not build; the positional form does

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-named-ctor-args-gate.sh
     fixed-in: 6f4b9e6f2
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 6dce987e5
     repro: inline below
     impact: workaround -->

Found while writing the probe for `rust-exec-silently-ignores-every-processoptions-field`. The
natural spelling of a case class with defaults does not compile on this lane:

```scalascript
exec("pwd", List(), ProcessOptions(cwd = Some("/tmp")))
```

```text
error[E0063]: missing fields `env`, `inheritEnv` and `timeout` in initializer of `ProcessOptions`
```

The positional form `ProcessOptions(Some("/tmp"), Map(), None, true)` builds. So a named argument
emits a struct literal carrying ONLY the named field, and the defaults are not filled in.

`RustCodeWalk` already carries a note about the neighbouring case — a positional call with FEWER
arguments than fields, `ProcessOptions(None, Map(), None)`, which was reported by a user and fixed
by filling the trailing defaults. Named arguments are the other cell of the same feature and were
left; this is the shape a `case class` with defaults is actually used with, and it is the shape the
std documentation shows.

The fix fills every unmentioned field from its declared default, exactly as the trailing-positional
path already does — not a refusal, since the program is legal and every other lane runs it. The gate
that closes this needs BOTH cells: a named argument for a middle field and one for the last, because
filling only the tail is what the existing code already does and would pass a one-row check.

### Fixed — by DELETING the second emitter rather than teaching it the same trick

A probe on a LATE field settled which half was broken before any code was written:
`ProcessOptions(inheritEnv = false)` emitted `ProcessOptions { inheritEnv: false }`, not
`cwd: false`. The names were already correct; only the other fields were missing.

The named branch was building the struct literal itself, from the named fields alone. The POSITIONAL
path had already learned to fill trailing defaults (`_ctorDefaults`, added for a user whose live
server called `ProcessOptions(None, Map(), None)`), and it also lifts an `Any` field to a `Value`,
`Box`es a recursive field and wraps a closure field in `Rc`. Two emitters means those four behaviours
have to be kept in step by hand — and this defect IS that drift, one behaviour deep.

So the named form is now DESUGARED to the positional one: reorder into declaration order, fill every
unnamed slot from its default, and hand the rewritten `Term.Apply` to the path that already existed.
One construction site, so the other three behaviours arrive without being re-implemented and cannot
diverge again.

**It declines rather than guesses, in two cases**, and the old emitter is kept for exactly those so
nothing that compiled before stops compiling: a name that is not a field of this constructor, and an
unnamed field with no default. Both are genuinely incomplete calls, and rustc's own "missing field"
is the message the caller needs — inventing a slot for a name we do not recognise is how a value
lands in the wrong field and COMPILES.

**Verified:** `tests/e2e/rust-named-ctor-args-gate.sh` PASS. Four rows compared against `run` row by
row, and the fourth is the one that cannot pass by accident: `Point(y = 1, x = 2)` names two fields
OUT of declaration order and omits a third, so a wrong reorder prints `1,2`, a dropped default does
not compile, and a mis-filled slot loses the `d` — three distinguishable failures in one row. Plus a
row asserting the oracle still answers `2,1,d`. Negative control with the walker reverted and the
launcher rebuilt: the build fails with three E0063s. `v1-jit-size` PASS — `renderTerm` did not grow;
the arm there is now two lines and the body lives in `namedCtorAsPositional`. `rust-std-survey-gate`
77 REFUSED / 55 COMPILES, BADRUST not grown.

## rust-exec-ignores-processoptions-timeout — `timeout` is accepted by the type and never enforced

<!-- status: fixed
     lane: native
     area: runtime
     kind: bug
     gate: tests/e2e/rust-exec-options-gate.sh
     fixed-in: fc666eda6
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 6dce987e5
     repro: none
     impact: workaround -->

Split out of `rust-exec-silently-ignores-every-processoptions-field` when that entry was fixed:
`cwd`, `env` and `inheritEnv` are now read from the record, `timeout` is not. It is left explicitly
unapplied, with the reason written at the site, rather than quietly rounded into "fixed".

`std::process::Command` has no timeout. Honouring one means `spawn()`, then polling `try_wait()` or
waiting on a channel, then `kill()` — which changes the function's shape and its failure contract
(what does a timed-out `exec` return? the partial stdout? a non-zero code? a panic?). That question
is unanswered here and must be answered against what `run` does before any code is written, or the
lanes will diverge in a new place while closing an old one.

**Implement this together with `process-needs-a-detached-spawn`** (BACKLOG.md), not separately: that
report asks for a spawn returning a handle, which is the same spawn/poll/kill machinery. Doing them
twice would leave two implementations of the same primitive on one lane.

Until then the type accepts a field the lane ignores — the same silent shape the parent entry was
about, kept only because the alternative is refusing `exec` outright for every caller that passes a
`ProcessOptions` with a timeout set, which would break working programs to fix a not-yet-supported
one.

### Fixed — and the measurement was worse than "unenforced"

The entry said the field was accepted and not applied. Measured, with the clock as part of the
evidence:

```text
                        exit code   wall time
run                     -1          killed at 600 ms
build-rust (before)      0          returned after the FULL 5 s
build-rust (after)      -1          killed at 600 ms
```

So a caller who bounded a request handler got neither the bound nor the signal: `exec` blocked for
the child's whole life AND reported SUCCESS. "Accepted and ignored" undersells it — the answer was
wrong, not merely absent.

**-1 on expiry is READ OFF THE OTHER LANES, not invented.** The v1 plugin and the jvm runtime both
answer -1 after `destroyForcibly`, so this closes a divergence instead of opening a fourth. Whatever
the child managed to write is still returned, which is also what they do.

**`.output()` cannot be used on this path** — it blocks until the child is done and hands back no
handle, so there is nothing to interrupt. The timeout path spawns, polls `try_wait` every 10 ms
against a deadline, then `kill`s. Polling rather than a channel keeps it dependency-free, and the
granularity that matters is "did it beat the deadline", not the exact millisecond of the kill.

**The pipes are drained on THREADS, and the gate has a row that fails only if they are not.** Reading
either stream to EOF on the polling thread blocks until the child exits — which deadlocks past a
pipe buffer AND defeats the very timeout being implemented, since the read cannot return before the
process it is timing has finished. Both JVM lanes carry that lesson in their own comments; row 8
(300 KB of child output, several times a pipe buffer) is what keeps the rust one honest, and it
would HANG rather than fail, which is why every lane in that gate now runs under a `timeout`.

**One matrix cell was nearly left behind.** The `stdin` path added last week also spawns and returns,
so the first version of this fix silently dropped the timeout whenever BOTH were set. The stdin
branch is now guarded on `timeout_ms.is_none()` and the timeout branch writes stdin itself; row 7
asserts the pair.

**Verified:** `tests/e2e/rust-exec-options-gate.sh` PASS, 8 rows compared against `run` row by row
plus an oracle row that now pins the timeout answer too. Negative control with the runtime template
reverted and the launcher rebuilt: FAIL on exactly one row, `timeout expired: rust '0', interpreter
'-1'`. Sibling gates re-run green (`process-stdin-gate`, `process-spawn-gate`);
`rust-std-survey-gate` 77 REFUSED / 55 COMPILES, BADRUST not grown; `v1-jit-size` PASS.

## uniml-reads-an-identifier-and-a-later-string-as-an-interpolator — the adjacency test never tested adjacency

<!-- status: fixed
     fixed-in: 04d9e88e6
     lane: v3
     area: front
     kind: bug
     gate: v3/front-capability-gate.sh (markdown-html, a row this closed) -->

**MEASURED — a two-line body, `a` on one line and `"b"` on the next:**

    native, int, v3's own front   b
    uniml (the DEFAULT front)     ssc3: …:4:3: the `a"…"` interpolator is outside SSC3 core Tier 0

One language, two answers, and the wrong one is on the front a user gets (invariant I-3). It is a
REFUSAL rather than a wrong answer, so it is the honest half of the failure spectrum — but the
program it refuses is ordinary Scala that three other lanes run.

**THE PREDICATE COMPARED THE IDENTIFIER WITH WHITESPACE.** `peekAbutsNext` took `toks(p + 1)` — the
RAW next token — and whitespace is a token in this lexer, so an identifier's neighbour is normally
the space or newline after it, which begins exactly where the identifier ends. The offsets matched
every time, so the test answered TRUE for an identifier followed by a string ANYWHERE in the file.
Its own doc comment said `foo "bar"` is not an interpolation, describing a rule it did not
implement.

`isInterpPrefix` accepts ANY word on purpose — `html"…"`, `uri"…"` are custom interpolators the
corpus writes — so adjacency was the only thing separating an interpolator from two statements.

**Fixed** by walking to the next SIGNIFICANT token, the same walk `peek2Kind` already does, before
comparing offsets.

**FOUND BY A PROBE THAT WAS SUPPOSED TO BE ABOUT SOMETHING ELSE.** Reducing
`v3-handler-arm-value-dropped-when-the-perform-is-a-statement`, my handler arm was

    seen = msg
    "H:" + msg + "|" + resume(())

and uniml refused it naming an interpolator `msg"…"` I had not written. A diagnostic that names a
construct absent from the source is worth chasing on the spot: it says the reader and the writer
disagree about what the text IS.

**THE GATE FOUND WHAT THE PROBE COULD NOT.** `front-capability-gate.sh` went RED on `markdown-html`
— a DECLARED divergence that this fix CLOSED — so its row came out in the same commit, which is
exactly what a self-maintaining list is for. Corpus N 233 → 234, UNSUPPORTED 129 → 128: a real
conformance file was being refused over this, and nobody had connected the two.

**Not affected, checked rather than assumed:** `s"x is $x"` still interpolates, and `html"…"` is
still RECOGNISED — refused by name as outside Tier 0 rather than silently becoming two statements.

## v3-handler-arm-value-dropped-when-the-perform-is-a-statement — `effects-handler` answers `List()`

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh, v3/corpus-report.sh --exec
     fixed-in: de11d1380
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-16
     repro: see below, four defs
     impact: wrong-answer -->

**A SILENT WRONG ANSWER ON BOTH v3 LANES.** In value position the arm's result flows out; with a
CALLER FRAME between the perform and the `handle` it was dropped and the caller carried on.

```scalascript
def prog(): Unit ! C =
  C.w("a")

def body(): String ! C =
  prog()
  "END"

val out = handle(body()) { case C.w(msg, resume) => "H:" + msg + "|" + resume(()) }
```

v3 answered `END`; native and v1 answer `H:a|END`. Two things were wrong at once: `resume(())` gave
back only the rest of `prog`, and the arm's value never became the `handle`'s.

### The recorded symptom was unreachable, and that had to be corrected first

The entry cited `tests/conformance/effects-handler.ssc` printing `List()`. It does not: that file is
written `handle { program(); List() }` and **v3 could not parse a `;` at all** outside a `for`
generator, so the case was a positioned refusal, not a wrong answer. Filed and fixed as
`v3-block-has-no-semicolon-statement-separator`; the repro above is the same defect in a shape v3
does parse.

### It does NOT need the reified stack this entry said it did

The old note — and `Cps.scala`'s header, and the executor's own refusal message — say a continuation
that crosses a call frame needs a machine whose stack can be copied, and that making v3's executor
that is "a rewrite of the kernel's largest file". What is actually needed is far less: the SEGMENT
between the handler and the perform, which the walker already holds at every call as an instruction
suffix plus a register array. Nothing of the host's stack is copied, which is exactly why it fits an
executor that keeps its call stack on the host's.

  * `PendingFrame` records that suffix at a call, and only while a `handle` is live.
  * `Value.VCont` carries the closure `Cps` built, the handler frame, and that segment.
  * `Resume` runs the closure and then each frame, innermost out, on a FRESH copy of its registers
    so a multi-shot arm starts each resume where the first one did.
  * The arm's value unwinds to the `handle` rather than returning: the frames in between are the
    continuation's now, and returning through them would both hand a caller a value it must not see
    and run its remainder twice.

**Two refinements the first version got wrong, both caught by measurement rather than review:**
a fall-through means unit for a FUNCTION body and the destination register for the `handle` body, and
a resume must install a delimiter of its OWN — without that, `k(1) + k(2)` over a function performing
twice answered 5 where the answer is 12, because the inner unwind flew past `+ k(2)`.

**Refused, not answered:** a call made inside an `if`, `loop` or `try`. Its remainder is a suffix of
the REGION, so a continuation captured there would silently skip everything after it.

## rust-type-pattern-on-a-local-val-matches-anything — a Map type pattern matches a JSON ARRAY when the scrutinee is a local val; the same match on a parameter is correct

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-type-pattern-local-val-gate.sh
     fixed-in: 0f8482f54
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from 539079f43
     repro: examples/reported/rust-type-pattern-on-a-local-val-matches-anything.ssc
     impact: blocks
     confirmed: yes -->

Routed from `INBOX.md` on 2026-08-16. Everything below is the reporter's, in their words.

Porting a small HTTP service from Rust to ScalaScript (rozum's UCC console), a reader that accepts
either `[...]` or `{"rooms": [...]}` answered "no such room" for every room in a file that plainly
listed them. Nothing failed — the answer was simply wrong, so it reached a running server and was
found later by comparing the two implementations byte for byte.

The discriminator turned out to be **where the matched value comes from**, and nothing else. The
same match expression is correct when it matches a PARAMETER and wrong when it matches a local
`val`. It does not depend on the JSON's source (a literal and a file behave the same), and it holds
when the `Map` arm actually uses its binding — `m.get("rooms")` on an array just returns nothing,
which is what made the wrong answer look plausible.

Measured with `bin/ssc` and `bin/ssc-tools build-rust` from the same tree:

```text
                       interpreter      rust lane
array via parameter    catch-all arm    catch-all arm
array via local val    catch-all arm    MAP arm        <- wrong
array, binding used    catch-all arm    MAP, no key    <- wrong
object via parameter   MAP arm          MAP arm
```

Repro prints exactly those four lines on each lane.

**Fixed, and the reporter's "where the value comes from, and nothing else" was literally the
mechanism.** `renderMatch` chooses the variant-testing path by asking whether the subject is an
`Any`, and `anyNames` was built from the def's PARAMETERS alone. A local `val` was therefore never
an `Any`, so the typed path ran — and that path DROPS the ascription, which is correct when the
ascription restates the subject's own type and catastrophic here:

```rust
pub fn viaParam(v: crate::value::Value) -> String {
    { let __any = v; if matches!(__any, crate::value::Value::Map(_)) { … } else { … } }   // right
}
pub fn viaLocal() -> String {
    let parsed = crate::runtime::_json_parse(&"[{\"name\":\"a\"}]".to_string());
    match parsed { m => "MAP arm", other => "catch-all arm" }                             // bind-all
}
```

`collectAnyLocals` adds the missing names: a `val`/`var` declared `: Any`, or bound to a call whose
def is declared to return `Any` (read from `_returnTypes` — never inferred from the body, because a
wrong `true` would put a genuinely typed local onto the `Value` path). `anyNames` is read at two
other sites and both get more correct: it widens the set that SUPPRESSES the "calls a name this
crate does not define" refusal, and it tells `needsAnyCoercion` that a local holding a `Value` needs
coercion at a typed parameter — a conclusion it already drew for the inline form `g(f(x))`, so
`val p = f(x); g(p)` now agrees with it.

**rustc had already said so, twice, and the build swallowed it.** The generated crate carried two
`warning: unreachable pattern` lines, one per broken function — the second arm of a bind-all first
arm is dead by construction. Nothing in this repository reads warnings out of generated code; filed
as `generated-rust-unreachable-pattern-is-an-unread-diagnostic` (BACKLOG.md). The gate asserts the
absence of that warning as one of its rows, so this crate at least cannot regress silently.

**Verified:** `tests/e2e/rust-type-pattern-local-val-gate.sh` PASS — the four reported rows now agree
with `run`, plus a row asserting the ORACLE is still right (an array is not a Map on the interpreter
either; agreement between two regressed lanes would otherwise pass every row) and the
no-unreachable-pattern row. Negative control with the walker reverted and rebuilt: FAIL on exactly
the two reported rows and the warning row, while BOTH parameter rows stay green — so the fix does
not trade one wrong answer for another. Corpus unmoved: `rust-std-survey-gate` 77 REFUSED /
55 COMPILES with BADRUST not grown, `v1-jit-size` PASS with no method new or grown.
## rust-serve-dies-permanently-after-one-handler-panic — one panic in one handler poisons the http runtime mutex, so the served program answers NOTHING afterwards while its process stays up

<!-- status: fixed
     lane: native
     area: runtime
     kind: bug
     gate: tests/e2e/rust-serve-panic-gate.sh
     fixed-in: b876ca0d8
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from 539079f43
     repro: examples/reported/rust-serve-dies-permanently-after-one-handler-panic.ssc
     impact: blocks
     confirmed: yes -->

Routed from `INBOX.md` on 2026-08-16. Everything below is the reporter's, in their words.

A handler read a `.jsonl` file line by line and passed each line to `jsonParse`. The file's last
line was empty — every file written by appending has one — so `jsonParse` aborted the thread. That
much is arguably fine.

What is not: the process **stayed up and stopped answering everything**, permanently. One panic is
followed by an unbounded run of

```text
thread 'tokio-rt-worker' panicked at src/runtime/http.rs:224:37:
called `Result::unwrap()` on an `Err` value: PoisonError { .. }
```

and every later request — including requests to unrelated routes — fails the same way. From
outside: healthy port, healthy process, no answers. That is the hardest failure shape to diagnose,
and one malformed byte from a caller is enough to cause it.

Measured with the attached repro (rust lane):

```text
curl /ok    -> alive
curl /boom  -> (nothing; the handler panicked)
curl /ok    -> (nothing, and never again; the process is still running)
```

Either of these would be enough on its own:

1. A handler panic must not take the server with it — answer 500 at the route boundary, or do not
   hold a lock across handler execution. A poisoned mutex turns one bad request into a permanent
   outage.
2. A fallible JSON parse. There is no `jsonParse` spelling returning `Option`/`Either`, so a server
   handling input it did not write cannot refuse it. Our workaround is to inspect the text first
   and only hand `jsonParse` something starting with `{` or `[`, which cannot be right in general.

On the interpreter the same input ends the program with `ssc: invalid JSON at 0: expected JSON
value` — a clean refusal rather than a panic, and there is no server left running to poison.

**Fixed — and the panic was never the mechanism.** `HttpRs` called the handler while still holding
`routes().lock()`. The unwind therefore travelled through the guard and POISONED the mutex, after
which every later request died on `lock().unwrap()` before it could look at its own path. That is
why an unrelated route stops answering too, and why the log fills with `PoisonError` rather than
with the handler's own panic: the second line onwards is a different failure from the first.

The dispatch now selects the route under the lock and clones the handler `Arc` out of it — a
refcount bump, nothing copied — then DROPS the guard before calling anything, and catches the unwind
at the route boundary. All three lock sites are poison-tolerant
(`unwrap_or_else(|p| p.into_inner())`), so a panic anywhere else in the runtime cannot bring the
outage back.

**A panic answers 500, not 404, and my first version got that wrong.** `catch_unwind(...).ok()`
collapsed "this route failed" into the same `None` as "no such route", so the gate went green while
the server told every caller the route did not exist — a second wrong answer standing in for the
first. A registered route that blew up says 500; a path that was never registered still says 404,
and the gate now asserts both in the same run.

**The report's point 2 is NOT fixed here, and measuring it turned up something the report could not
have seen.** `std/json.ssc` does have a total parse — `jsonValue` never fails — so the missing thing
is not totality but DISCRIMINATION: `""`, `"not json"` and the literal `"null"` all answer
`isNull`, so "was this text JSON at all?" has no spelling on any lane. Filed as
`json-parse-has-no-fallible-spelling` (BACKLOG.md).

And the reason the reporter concluded the spelling does not exist is that ON THEIR LANE IT DOES NOT:
`build-rust` refuses `std/json.ssc`'s tolerant path while the panicking strict path builds and runs,
so the only reachable parse on the rust lane is the one that kills the thread. That is a defect, not
an API decision, and is filed as
`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds`. What changed HERE is
that hitting either of them now costs one request instead of the server.

**Verified:** `tests/e2e/rust-serve-panic-gate.sh` PASS over a real socket — `/ok` 200, `/boom` 500,
`/ok` 200 again, `/nosuch` 404, `/ok` 200, process up, zero `PoisonError`. Negative control: with
`HttpRs` reverted and the launcher rebuilt, the same gate fails 5 rows and reproduces the report
exactly — `/boom` answers nothing, then so do `/ok` AND `/nosuch`, 3 `PoisonError` lines, process
still alive. `scripts/smoke-ci` 111/111.
## rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds — `jsonValue` cannot be lowered, so the only JSON parse reachable on this lane is the one that aborts the thread

<!-- status: open
     lane: native
     area: codegen
     kind: bug
     gate: -
     fixed-in: -
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: bcfe44e01
     repro: none
     impact: workaround -->

Found while fixing `rust-serve-dies-permanently-after-one-handler-panic`, and it is the part that
report could not contain: it explains why the reporter believed no non-panicking parse exists.

`std/json.ssc` offers a strict parse that aborts on bad input (`jsonParse`, `jsonRead`) and a
tolerant one that never fails (`jsonValue`). On the rust lane only the first kind builds. Measured
from the same tree, two files that differ only in which name they import:

```text
build-rust, imports jsonParse  → builds, runs
build-rust, imports jsonValue  → REFUSED:
  Generic(def `jsonCoreParseArrayItems` reads `reverse` without parentheses and this lane does not
  lower it here; it is a collection member, not a field, and would be emitted as a Rust FIELD access)
  Generic(def `jsonCoreContinueObjectValue` reads `reverse` without parentheses …)
```

So the advice one would normally give — "use the tolerant parse if you cannot trust the input" — is
not available to anyone on this lane, and the refusal names an internal helper rather than the
function the user actually imported, which is why it does not read as "jsonValue is unavailable".

The refusal itself is honest and is the kind this walker is supposed to make: a parenthesis-less
`reverse` would be emitted as a Rust field access. Both sites are in `std/json.ssc`'s tolerant path;
there may be more behind them, since a refusal short-circuits and every count from one is a lower
bound.

### Measured 2026-08-16, and the sentence above about "the fix is to LOWER it" was wrong

I wrote that the fix is to lower `reverse`. I then wrote that lowering, measured it, and REVERTED
it. What the experiment bought is the real shape of this entry, so it is recorded here rather than
discarded.

**The lowering itself is correct and small.** The refusing receiver is a CONS —
`(value :: reversed).reverse`, the accumulate-then-reverse idiom every recursive parser in `std/` is
written with — and the walker's is-this-a-Vec test asked only about names and applies. Teaching it
that `x :: xs` is a sequence exactly when `xs` is one (plus the same fact for a local BOUND to a
cons, which is a second spelling the first patch left behind and only probing found) makes three
ordinary programs build and agree with `run` byte for byte:

```text
("top" :: acc).reverse            cons receiver
val a = "a" :: "b" :: Nil; a.reverse   local bound to a cons
val base = Nil; ("x" :: base).reverse  chain rooted in a local Nil
```

**It cannot land, for two independent reasons, and either alone is enough.**

1. **It does not reach the goal.** With the refusal gone, a `jsonValue` program does not build — it
   stops at the NEXT blocker: `` `__jsonCoreWrap` is declared `extern` and the rust backend has no
   implementation for it (no `@rust(...)`, no intrinsic); called from `jsonValue` ``. `jsonParse`
   never meets that boundary because it is an INTRINSIC on this lane
   (`RustIntrinsics: jsonParse -> crate::runtime::_json_parse`, serde_json, panics on error), so it
   bypasses `std/json.ssc` entirely. The tolerant path has no intrinsic and needs the provider
   boundary — `__jsonCoreWrap`, `__jsonCoreWrapStrict`, `__jsonCoreRawStrict`,
   `__jsonCoreEncodeValue` — of which this lane implements none.
2. **It trades an actionable refusal for unreadable output.** `rust-std-survey-gate` goes red:
   `std/json-core.ssc` and `std/yaml-core.ssc` move REFUSED -> BADRUST. json-core alone then emits
   **43 rustc errors** — 22 × E0308, 5 × E0599, 4 × E0271, 2 × E0277, 2 × E0004. The gate's own rule
   applies and is right: "a refusal is a message the user can act on; bad generated code is not."

   **Re-measured 2026-08-17: 43 -> 32**, and the conclusion is unchanged. `679c66b17` (a sibling's
   `rust-multi-statement-match-arm-emitted-without-braces` fix, which also reads an Any-declared call
   as an Any subject) removed both syntax errors and nine type errors. json-core is still BADRUST, so
   the trade this entry refuses is still a bad one — but the number here is dated evidence and moves
   when other work lands. Re-measure before quoting it.

**Two of those 43 were not type errors — they were INVALID RUST SYNTAX**, and pulling that thread
is what re-measured this whole number. Filed as
`rust-multi-statement-match-arm-emitted-without-braces`, reduced to fourteen lines, and fixed in
`679c66b17`; the reduction also turned up a second defect behind it
(`rust-any-returning-call-scrutinee-keeps-the-typed-match-path`). Fixing those exposed a THIRD
syntax error at `json_core.rs:437` — `[_, _ @ ..]`, an invalid `@` pattern from `case Cons(_, _)` at
`std/json-core.ssc:474` — filed and fixed as
`rust-cons-pattern-with-a-wildcard-tail-emits-an-invalid-at-pattern`.

RE-COUNTED, refusal bypassed for diagnosis only, three trees:

| tree | errors | uncoded (SYNTAX) |
|---|---|---|
| as filed, 2026-08-16 | 43 | 2 |
| after the two match fixes | 36 | 2 (a different pair) |
| after the `@` fix | **32** | **0** |

**The crate now PARSES**, and every remaining error is a type error, which changes what a reader
should expect from this entry: the "unreadable output" argument below is still true, but it is
about 30 type errors rather than a file rustc will not even read. Two of the 32 are
`E0615: attempted to take value of method reverse` — this refusal's own justification, present only
because the bypass removed it.

Each syntax error hid the next one, which is the pattern worth carrying forward: rustc stops at the
first parse error, so a count taken behind one is a LOWER BOUND on the distinct defects and says
nothing about what follows.

**So the true shape is a chain of at least three, not one defect:** the cons refusal, then 43
codegen errors in json-core (two of them syntactic), then an unimplemented provider boundary. The
2026-08-13 note in `RustCodeWalk.scala` — "of the 16 modules refusing on these three names, zero
reach COMPILES" — was measuring the corpus and is confirmed here from the other direction.

Whoever takes this should land the cons lowering TOGETHER with the json-core work, not before it.
The patch is 12 lines in `isKnownVecReceiver` and `collectLocalSeqs`; re-deriving it is cheaper than
carrying it, so it is described rather than parked.

### 2026-08-18 — the 32 have a NAME now, and the first half of the fix cannot help json-core alone

Classified the 32 by rustc message rather than by count: **8 are `expected i64, found SscChar`, 6 are
`expected i64` / `expected Vec<i64>, found Value`, 3 are `expected Value, found JsonCoreField`.** The
first two families — fourteen errors — are ONE cause, and it is not in json-core at all:
`std/json-core.ssc` carries `package: std.json.core`, and a packaged module gets NO argument
coercion whatsoever. Filed as `rust-packaged-module-loses-every-argument-coercion` with a 20-line
repro; five other hypotheses are falsified there so nobody repeats them.

The `SscChar` -> `i64` arm those 8 errors ask for is LANDED (`51cbbbccb`, gated by
`tests/e2e/rust-charat-arg-gate.sh`) — **and it moves json-core's count by zero**, which is the
useful part of this note. The arm lives inside the very coercion block that `package:` switches off,
so it cannot fire here until the packaged-module defect is fixed. The two fixes only pay together:
one re-opens the coercion site, the other supplies the arm it needs. Anyone re-measuring json-core
after `51cbbbccb` and seeing 32 again should read that as expected, not as a failed fix.

The cons lowering was re-applied locally to reach json-core for this measurement and was NOT landed,
for the same two reasons as above — the survey still moves json-core and yaml-core REFUSED ->
BADRUST.

Not gated: no gate is filed with an open entry here. The gate that closes this asserts a
`jsonValue` program BUILDS and answers `isNull` for `""` — a compile-only row would pass on a
binary that then panics.
## rust-mkstring-on-a-non-string-list-emits-join — `List(1,2,3).mkString(",")` does not build, in five lines

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-mkstring-parity-gate.sh
     fixed-in: db8c38633
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 315059ad7
     repro: inline below — five lines
     impact: workaround -->

Found while probing the cons work in
`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds`: my first probe used a
`List[Int]` and failed for a reason that had nothing to do with what I was measuring. Reduced, it is
its own defect and a small one:

```scalascript
def main(): Unit =
  val ints = List(1, 2, 3)
  println("ints: " + ints.mkString(","))
  val strs = List("a", "b")
  println("strs: " + strs.mkString(","))

main()
```

```text
bin/ssc run       ints: 1,2,3   strs: a,b
build-rust        error[E0599]: the method `join` exists for struct `Vec<i64>`,
                  but its trait bounds were not satisfied
```

`mkString` lowers to Rust's `.join(sep)`, which is defined for `Vec<String>`/`Vec<&str>` and for
nothing else. The `String` case works, so the defect is invisible in exactly the programs people
write first — and `mkString` on a list of numbers is what a CSV line, a debug print or a joined id
list is written with.

The fix is to render each element before joining
(`…iter().map(|e| e.to_string()).collect::<Vec<_>>().join(sep)`) rather than to refuse; the element
rendering must agree with what `println` produces for the same value, or the lane will print
`1,2,3` here and something else one line down. The gate that closes this compares BOTH lanes'
output for a list of Int, Double, Boolean and String — not just that it compiles, because a join
that stringifies differently compiles fine and prints the wrong thing.

### Fixed — and the worse half of it was not the one in the title

Each element is now rendered through `format!("{}", …)` before joining. For a `Vec<String>` that
produces the SAME string, so nothing that compiled before changes; and it is what `"x = " + v`
already emits for every other value, which is the property that matters — two spellings of "show me
this value" must not disagree. `Value` implements `Display`, so a `List[Any]` joins too, which
`join` never could.

**The THREE-argument form was silently WRONG, not merely unsupported, and that is the more serious
half.** The separator was read with `headOption`, so `xs.mkString("[", ",", "]")` emitted
`join("[")`. Measured on the reverted walker, one line of source:

```text
bin/ssc run                 wrapped: [a,b,c]
build-rust (before)         wrapped: a[b[c      ← compiled, ran, printed the wrong answer
build-rust (after)          wrapped: [a,b,c]
```

A build-only check passes on that, which is why every row of the gate compares OUTPUT against `run`
rather than asserting a literal. Arity is now read explicitly and any other arity refuses rather
than guessing which argument was meant.

The body lives in `renderMkString`, not in the `renderTerm` arm: `renderTerm` is frozen by
`tests/e2e/v1-jit-size.sh` past HotSpot's `HugeMethodLimit`, so the arm is one line and the
20 it would have added stay out of a method the interpreter walks forever.

**Two neighbouring gaps this does NOT fix**, both hit while writing the probe and both still open —
`xs.mkString` written with NO parentheses is refused before it reaches this lowering (`mkString` is
in `CollectionOnlyMembers`), and `List[String]()`, a typed empty list literal, is "calls
`List[String]` which has no resolvable name", so the empty-list case is untested here.

**Verified:** `tests/e2e/rust-mkstring-parity-gate.sh` PASS — seven rows, Int/String/Boolean/Double,
empty separator, the three-argument form and a single-element list, each compared against `run`,
plus a row asserting the oracle itself still answers `[a,b,c]`. Negative control with the walker
reverted and rebuilt: the build FAILS with `join` on `Vec<i64>`, `Vec<bool>` and `Vec<f64>`, and the
String-only three-argument case compiles and prints `a[b[c`. Corpus unmoved: `rust-std-survey-gate`
77 REFUSED / 55 COMPILES with BADRUST not grown; `v1-jit-size` PASS, no method new or grown.

## rust-multi-statement-match-arm-emitted-without-braces — the walker emits Rust that does not PARSE, and only behind a refusal that hides it

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-match-arm-braces-gate.sh
     fixed-in: 679c66b17
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 315059ad7
     repro: tests/e2e/rust-match-arm-braces-gate.sh, row `tail`
     impact: workaround -->

**Reduced 2026-08-17, and the reduction corrects this heading: the defect is NOT behind a refusal.**
Fourteen lines of ordinary ScalaScript — no `reverse`, no `std/json-core.ssc`, no bypass of anything
— produce an unparseable crate on a clean tree:

```scalascript
def pick(k: String): String = k

def label(k: String): Any =
  pick(k) match {
    case "a" =>
      val n = 10
      n + 1
    case _ =>
      val m = 20
      m + 2
  }

def main(): Unit =
  println(label("a"))
```

```text
"a" => let n = 10i64;
error: expected expression, found `let` statement
```

`run` and `run --v1` both answer `11`. So the entry's own instruction — *do not take this until the
repro exists, a fix here cannot be falsified by anything that runs today* — is discharged: it is
falsifiable by a fourteen-line program, in both directions.

### The mechanism, and the three guesses it refutes

The trigger is **the declared return type of the enclosing def**, not anything about the match. A def
returning `Any` puts its body in a tail position that must produce a `Value`, so each arm renders
through `renderValueTail`, whose contract is to hand back a STATEMENT SEQUENCE. That contract is
right for every other caller — they splice it somewhere already bracketed, an `if` branch or a fn
body. A match arm is the one caller that splices it directly after `=>`.

Measured against the three candidates this entry listed as unmeasured:

| guess | verdict |
|---|---|
| the scrutinee is a call, not a parameter | **load-bearing, but not the cause** — a NAME scrutinee routes to `renderAnyMatch`, which brackets its own arms, so it hides the defect rather than causing it |
| a struct-ctor arm routes it through `renderAnyMatch` | **refuted** — `renderAnyMatch` is the path that is CORRECT here; the broken one is `renderMatch`, and the minimum has no case class at all |
| the arm sits in the tail of a recursive parse | **refuted** — recursion is irrelevant; `Any` as the return type is the whole of it |

The `case class` probe recorded above as "does not reproduce" was right for a reason nobody guessed:
it declares `def step(r: Any): String`. `String`, not `Any` — so no `Value` tail, no defect. One word
apart from the failing case, and the entry read it as evidence about case classes.

### The fix

`RustCodeWalk.renderMatch` braced an arm only for its `prefix` (the typed-binder `let`s). It now also
braces a body that is a `Block` of more than one statement and did not already come back braced. The
second half of that condition is what keeps it a no-op for the ordinary typed path, which
`renderTerm` already renders as a Rust block — verified by the gate's `typed` row, which passes
identically with and without the change.

Behind it sits a SECOND defect, invisible until this one was fixed and filed separately as
`rust-any-returning-call-scrutinee-keeps-the-typed-match-path`: the original json-core shape now
parses and then fails `E0308`, because `subjectIsAny` is asked only of a bare `Term.Name`.

## rust-any-returning-call-scrutinee-keeps-the-typed-match-path — E0308, and the parse error hid it

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-match-arm-braces-gate.sh
     fixed-in: 679c66b17
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/rust-match-arm-braces-gate.sh, row `anyctor`
     impact: workaround -->

Found by fixing `rust-multi-statement-match-arm-emitted-without-braces` and rebuilding: the crate
that used to be unparseable now parses, and this is the error standing behind it.

```scalascript
case class Ok(value: Int, next: Int)
case class Err(message: String)

def step(n: Int): Any = if n > 2 then Err("too big") else Ok(n, n + 1)

def label(n: Int): String =
  step(n) match {
    case Ok(value, next) => "ok " + (value + next)
    case Err(message)    => "err " + message
  }
```

```text
error[E0308]: mismatched types
   |     match step(n) {          this expression has type `Value`
   |         Ok { value, next } => …
   |         ^^^^^^^^^^^^^^^^^^ expected `Value`, found `Ok`
```

`renderMatch` diverts to `renderAnyMatch` — the `ssc_is`/`ssc_field` chain that can actually look
inside a `Value::Obj` — when the subject is an `Any` and some arm destructures a case class. The
first half was asked only of a bare name:

```scala
val subjectIsAny = subject match
  case m.Term.Name(n) => ctx.anyNames.contains(n)
  case _              => false        // a CALL lands here
```

So `step(n) match { … }` stayed on the typed path and emitted a struct pattern against a `Value`.

**Why it was invisible.** Both defects need the same conditions, and the parse error comes first:
rustc stops at `expected expression, found let` and never type-checks the file. `std/json-core.ssc`
carries both at once, which is why that entry's reduction attributed the whole thing to
`renderAnyMatch` — the one component that turns out to be the CORRECT path here.

### The fix

`_returnTypes` already answers this question for calls, and `collectAnyLocals` already asks it that
way — `_returnTypes.get(f).contains("crate::value::Value")`, read from the DECLARED type, never
inferred. `subjectIsAny` now uses the same test for `Apply(Name(f), _)`.

The neighbouring site, `needsAnyCoercion`, spells the same question a THIRD way and already handles
calls, signal reads and map applies. It was not touched: it is about argument coercion, not match
lowering, and its own comment records that widening it rewrites every emitted call. Worth knowing
that "is this an `Any`?" now has three implementations in `RustCodeWalk.scala`.

## backendRust-test-stood-at-276-of-278-while-two-entries-here-quoted-it-as-278 — stale assertions, not defects

<!-- status: fixed
     lane: native
     area: codegen
     kind: apparatus
     gate: sbt backendRust/test
     fixed-in: 679c66b17
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: sbt --client "backendRust/test"
     impact: none -->

Found while using `backendRust/test` as the regression control for a codegen change. It came back
276 succeeded / 2 failed, and the control — the same two classes on a clean tree — failed
IDENTICALLY, so the change was not the cause. The two reds were already standing.

That matters because two entries in this file quote this suite as `278/278` while arguing about a
defect's blast radius, and one of them (`two-suites-two-blind-spots`) leans on it as the half that
sees what the corpus cannot. A suite with two permanent reds cannot play that role: the next reader
runs it, sees red, and has no cheap way to tell an old red from their own.

Neither red was a product defect. Both are assertions that outlived a deliberate emission change:

| test | asserts | emits now |
|---|---|---|
| `RustGenControlFlowTest`, match case guard | `if (x > 0i64) =>` | `if ((x > 0i64)) =>` |
| `RustGenR23Test:230`, Map `getOrElse` | `.unwrap_or(0i64)` | `.unwrap_or(0i64.into())` |

**They were fixed on opposite sides, and which side is which is the whole judgement.** The double
parens are the EMITTER's fault: `renderTerm` already brackets a comparison, and the arm assembly
wrapped the user guard again so it could `&&` it with the typed-binder tests — a real feature, but
one that pays only when there are two conjuncts. Wrapping a lone guard bought nothing and cost an
`unused_parens` warning on every guarded arm in the corpus, so the emitter now parenthesises only
when it joins. The `.into()` is the opposite: a HashMap read hands back the map's value type, which
on this lane may be a `Value`, so coercing the default at the call site is correct and the ASSERTION
is what was stale. Note the Option `getOrElse` a few tests below emits a bare `.unwrap_or(0i64)` and
still asserts it — the two spellings differ for a reason, which is why neither was "fixed" by
matching on a shared prefix.

Suite is 278/278 again. What is NOT fixed by this entry is the reason it went unnoticed: nothing
reported the count, so the drop from 278 to 276 was silent and dateable only by reading git history.

## rust-toint-parity-gate-compiled-v2-output-with-a-bare-rustc — E0433, and the rows asserted nothing

<!-- status: fixed
     lane: native
     area: build
     kind: apparatus
     gate: tests/e2e/rust-toint-parity-gate.sh
     fixed-in: 679c66b17
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/rust-toint-parity-gate.sh, rows `v2 conv-toint` and `v2 conv-tolong`
     impact: none -->

Found in a 17-gate regression sweep for an unrelated codegen change: sixteen green, this one red on
two rows, and the control showed the red had nothing to do with the change under test.

```text
error[E0433]: failed to resolve: use of unresolved module or unlinked crate `num_bigint`
341 |         V::Int(n) => V::Big(num_bigint::BigInt::from(n)),
```

The v2 Rust generator's prelude reaches for `num_bigint::BigInt`, and this gate compiled its output
with `rustc -O one-file.rs`, which has no way to resolve a dependency. **The third instance of the
same migration gap** — `v2/backend/check.sh` moved to a cargo crate when `BigInt` landed
(`rust-bigint-is-an-i64`) and its own header says why; the callers it left behind then failed
`E0433` one by one, as each was next run. See `a-guard-dissolves-when-its-replacement-lands`.

Fixed by porting that file's `crate_init` verbatim in shape: one crate directory reused by both
rows, so `num-bigint` compiles once for the gate. The rows PASS and answer `8|8|8|8|7|` and `8|8|`,
which is the point of the repair — they were not merely red, they were asserting nothing about the
second generator's numeric conversions, the exact defect this gate exists for.

Cost moved 9 s → 15 s warm, and the header's claim that "the crates carry no external dependencies"
was corrected rather than left standing: it is now true of the v1 half only.

**What is not fixed.** Nothing looks for the NEXT caller of the v2 generator. Today there is exactly
one outside `v2/backend` (`grep -rln 'v2/backend/rust' tests/e2e/*.sh`), so a census is cheap; a gate
that pins that number would be cheaper than finding the fourth instance the way the first three were
found.

## rust-cons-pattern-with-a-wildcard-tail-emits-an-invalid-at-pattern — `[_, _ @ ..]` does not parse

<!-- status: fixed
     lane: native
     area: codegen
     kind: bug
     gate: tests/e2e/rust-list-pattern-gate.sh
     fixed-in: 1e5f68446
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/rust-list-pattern-gate.sh, rows `consw` and `infixw`
     impact: workaround -->

The tail of a cons pattern lowers to a slice rest-binding. Rust requires a BINDING left of `@` —
`x`, `mut x`, `ref x`, `ref mut x` — and `_` is a pattern, not a binding:

```scalascript
def describe(xs: List[Int]): String =
  xs match { case Cons(_, _) => "many" ; case _ => "none" }
```

```text
[_, _ @ ..] => "many".to_string(),
error: left-hand side of `@` must be a binding
```

The correct emission for an ignored tail is a bare `..`. Nine lines, a clean tree, no bypass.

**A PARSE error, which is the reason it is filed separately from the type errors around it.** rustc
stops, so nothing else in the crate is reported — the same property that kept
`rust-multi-statement-match-arm-emitted-without-braces` hidden, and this one was hidden BEHIND that:
it only became visible once the crate got far enough to reach line 437.

**Two spellings, and each built the tail string itself.** `case h :: t` (infix) and `case Cons(h, t)`
(extractor) are separate arms in `renderPattern`; a fix to one is half a fix. Both now call one
`sliceTail` helper. `std/json-core.ssc:474` uses the extractor spelling, which is where this came
from.

### Found by re-counting, and the count is the other half of the result

`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds` records **43 rustc errors**
for `std/json-core.ssc` behind its refusal. Re-measured with the refusal bypassed for diagnosis only:

| tree | errors | uncoded (SYNTAX) |
|---|---|---|
| as filed, 2026-08-16 | 43 | 2 — `expected expression, found let` |
| after the two match fixes (679c66b17) | 36 | 2 — `left-hand side of @ must be a binding` |
| after this fix | **32** | **0** |

So the crate PARSES for the first time, and every remaining error is a type error. Two of the 32 are
`E0615: attempted to take value of method reverse` — the refusal's own justification, present only
because the bypass removed it. The other 30 are the real queue that entry describes.

## v2-cons-pattern-with-a-wildcard-head-returns-a-closure — a wrong ANSWER, not a refusal

<!-- status: fixed
     lane: v2-jvm
     area: front
     kind: bug
     gate: tests/e2e/v2-cons-wildcard-head-gate.sh
     fixed-in: 7a693c9eb
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/v2-cons-wildcard-head-gate.sh, row `wildhead`
     impact: wrong-answer -->

Found while choosing rows for `rust-list-pattern-gate`: two lanes agreed and the default one did
not. On `bin/ssc run`, an infix cons pattern whose HEAD is `_` evaluates to a closure instead of the
arm body — silently, with no diagnostic.

| spelling | v2 (`ssc run`) | v1 | rust |
|---|---|---|---|
| `case _ :: _` | `<closure>` | A | A |
| `case _ :: t` | `<closure>` | B | B |
| `case h :: _` | C | C | C |
| `case h :: t` | D | D | D |
| `case h :: _ :: t` | ok | ok | ok |
| `case Cons(_, t)` | ok | ok | ok |

Every neighbouring spelling is correct and only this cell is wrong, which is why it survived: the
extractor spelling works, a named head works, and a wildcard in any NESTED position works.

### The cause is dispatcher ORDER, and it corrects the guess this entry was filed with

`parseArm` in the F front dispatched on the first token, wildcard first and cons LAST:

```
def parseArm(ts, menv, cx) =
  if isWild(hd(ts)) then parseWildArm(tl(ts), ...)
  else if ... else if ... else parseConsArm(ts, ...)     // unreachable for a wildcard head
```

So `case _ :: t => body` collapsed to a catch-all `_`, and the body was then parsed from the
LEFTOVER `:: t => body`. `t => body` is a LAMBDA — which is where the closure comes from, and why the
arm body never runs. The original guess here was "a leading `_` is being read as placeholder-lambda
syntax"; the lambda reading is real but it is the SYMPTOM of the arm never being seen as a cons arm,
not a placeholder rule firing.

**The other dispatcher over the same syntax already had it right.** `parseGenArm`, the ordered
resolver, tests `isConsArmHead` first. The fix mirrors that ordering and reuses that predicate, so
the two stay in step — and because the predicate requires an UNGUARDED arm, `case _ :: t if g` keeps
DECLINING (an honest fallback) instead of starting to answer wrongly. `parseHArm` was checked and is
a different syntax family (typed handler arms, no cons at all).

### Still wrong, measured, and left: a PARENTHESISED wildcard head

`case (_) :: t` answers the catch-all arm on v2 (`z`) where v1 answers correctly. `isConsArmHead`
peeks exactly two tokens, so it cannot see the `::` past `(_)`. The obvious repair — parse a full
atom first and test the RESULT — would also reroute every `case Cons(a, b)` from `parseCtorArm` to
`parseConsArm`, a different lowering path, which is a larger change than this one and wants its own
measurement. Not attempted here; the gate says so in its header rather than leaving it to be
rediscovered.

## v2-front-fallback-runs-the-program-twice — every side effect happens twice, silently

<!-- status: fixed
     lane: v2-jvm
     area: front
     kind: bug
     gate: tests/e2e/v2-entry-no-double-main-gate.sh
     fixed-in: ae5b09418
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/v2-entry-no-double-main-gate.sh, row `legacy / trailing main()`
     impact: wrong-answer -->

**The title is the symptom and it is not the cause — the fallback is innocent.** Filed as "when F
declines a file the program runs twice", with the mechanism explicitly left unmeasured. Measured
now, and it takes a 2x2 rather than a single probe:

|              | trailing `main()` | no trailing `main()` |
|---|---|---|
| F front      | once | once |
| legacy front | **TWICE** | once |

So the doubling needs BOTH halves: the legacy front AND an explicit top-level `main()`. The fallback
only ever supplied the first half, which is why it looked responsible.

### The cause: two features that landed apart and were never reconciled

`v2/lib/ssc1-lower.ssc0` builds its entry as `Seq(top-level exprs…, main() if a zero-arg main
exists)`. The appended call is the OLDER half. T5.7 later made top-level EXPRESSION statements run —
its own comment says they "used to be silently dropped (~190/194 real .ssc files became no-ops)".
From that day a file ending in `main()` got the call twice: once from the statements, once appended.

Most .ssc files in this repository end in `main()`, so this was not an exotic shape. It stayed
invisible because F became the default front and **F already guards it** —
`callsMain(docEntry)` in `specs/v2.2-p6.5-fsub.ssc` emits nothing when the doc-order entry already
calls main. A two-front pair in which F is the correct half.

**`println` is only the visible part.** The same entry runs whatever else the program does: a file
write, an HTTP call, a database insert would double identically, under a stderr notice reading "the
program still ran correctly".

### The fix, and how the gate reaches it

F's guard, mirrored — asked of the IR rather than of the rendered string, because this front has IR
at that point.

The gate SELECTS the front with `SSC_FRONT=legacy` (`RunNativeV2.frontIsF`) instead of provoking a
fallback through an F coverage gap. The first draft did the latter, and that row would have gone
quiet the day F learned the construct: still green, testing nothing. The `no trailing main()` rows
are the anti-rows — with no explicit call the APPENDED one is the only thing that runs the program,
so a fix that dropped it unconditionally prints nothing and fails there.

## f-curried-def-gate-red-on-varargs-after-a-fixed-parameter — red IN CI, and unfiled

<!-- status: fixed
     lane: v2-jvm
     area: front
     kind: bug
     gate: tests/e2e/f-curried-def-gate.sh
     fixed-in: 9d9d1b9bd
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/f-curried-def-gate.sh, rows `varargs-after-fixed` and `varargs-curried`
     impact: workaround -->

Found as an unexplained red in a regression sweep for an unrelated front change, and it was not that
change: the failing programs contain no `match` and no `case`, and `parseArm` is reachable only from
`parseArms0` behind a `case` token.

```scalascript
def f(n: Int, xs: String*): Int = n + xs.toList.length
def main(): Unit = println(f(1, "a", "b"))
```

```text
ssc: TYPEERR: in def main: cannot unify Int: Int vs (String -> t4)
```

### It is about ARITY, not about varargs

`registerParamTypes` records a def as soon as SOME parameter has a known type (`anyTy`). Recording a
signature fixes its ARITY as well as its types, and a vararg def has no fixed arity — so the
checker's curried application rule over-applies on the extra argument and unifies the RESULT type
with a function type.

**The failure needs a MIXED signature, which is why it lasted.** `xs: String*` is never a known type:
`simpleDeclTy` wants `,` or `)` immediately after the type name and finds `*`. A def whose ONLY
parameter is a vararg therefore fails `anyTy`, is never registered, and works at any arity. Put one
typed parameter in front of it and the cap becomes the parameter COUNT:

| def | accepts |
|---|---|
| `g(xs: String*)` | any number of arguments |
| `f(n: Int, xs: String*)` | at most 2 |
| `k(a: Int, b: Int, xs: String*)` | at most 3 |

**No two-front pair here, and that was checked rather than assumed.** F has no parameter-type
registry of its own; the CHECK phase always runs through the legacy front's parser
(`ssc1-check-run.ssc0`), which is why `SSC_FRONT=legacy` and the default F lane failed identically.

### The fix, and what it costs

The registration is skipped for a trailing-vararg def, on the principle TYPES-1 states a few lines
above it: "a partially-understood type is worse than an absent one, because it constrains where it
should not". Fixing an arity that is variable is exactly that.

The cost is that the FIXED parameters of a vararg def are no longer type-checked. That is the smaller
loss: today those defs cannot be CALLED with more than `n` arguments at all. The real repair is a
signature meaning "arity >= n", which this checker has no way to express yet, and that is the shape
the next person should reach for rather than re-deriving this.

**The gate gained an ANTI-ROW rather than a new gate**, since `f-curried-def-gate` already covered
the defect and was already wired into CI. `overapply-non-vararg` calls a two-parameter def with
three arguments and requires it to STAY a type error — the obvious way to pass the two red rows is to
weaken the registry for everyone, and this row is what that costs. Its expectation is deliberately
the v2 message rather than the other lane's answer: `--v1` prints `3` for that program, so on this
one point the reference lane is the permissive one.

## bugs-index-gate-hides-the-enum-it-rejects-against — three agents, one day, the same rejected value

<!-- status: fixed
     lane: apparatus
     area: docs
     kind: apparatus
     gate: tests/e2e/bugs-index-gate.sh --self-test
     fixed-in: af3775ab6
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: tests/e2e/bugs-index-gate.sh --self-test, fixtures `bad-lane` and `bad-area`
     impact: none -->

On 2026-08-17 three different agents wrote `lane: v2` in a new entry and turned `bugs-index-gate`
RED on main — me first, then `rust-unreachable-census`, then `v2-ui-forjsonview`, each within a few
hours of the last. Each had to open the script to find out what was allowed.

That is not three careless agents. **`v2` is what this project calls the thing in prose
everywhere** — "the v2 lane", "the v2 front" — while the enum splits it into `v2-jvm` and `v2-rust`,
and a plugin defect belongs to neither (`native`). The gate said only:

```text
lane `v2` not in the enum
```

`status` and `kind` in the very same function already print their allowed values
(`not in {sorted(STATUS)}`). Lane and area did not — an asymmetry nobody chose, and the one that
happened to cover the field people get wrong.

**The self-test had no fixture for either check**, which is why the message quality was never
exercised: the checks were known to REJECT, but nothing asserted they explained. Both now have one,
and the assertion demands a MEMBER of the enum in the output (`v2-jvm`, `conformance`) rather than
the substring `not in`, which passes either way and cannot tell a helpful message from a useless one.
Verified in the failing direction: with the old messages restored the self-test fails on
`expected a problem mentioning 'v2-jvm'`.

`v2` additionally gets a named hint, because it is the value that actually keeps being written:

```text
lane `v2` not in ['apparatus', 'int', … 'v2-jvm', 'v2-rust', 'v3'] — `v2` is not a lane here:
the v2 INTERPRETER on the JVM is `v2-jvm`, a v2 native/plugin defect is `native`
```

**The board was unstuck in the same commit.** `v2/BUGS.md`'s `v2-ui-provider-lacks-forJsonView…`
still carried `lane: v2` and no claim held that file any more. Set to `native` — it is a
`UiNativePlugin` registration gap, the same lane as the sibling entry about that plugin, rather than
to `v2-jvm` which I typed first and corrected after reading what the entry is actually about.

## v2-parenthesised-head-in-a-cons-pattern-misses-the-arm — and the obvious repair was TRIED

<!-- status: fixed
     lane: v2-jvm
     area: front
     kind: bug
     gate: tests/e2e/v2-paren-cons-arm-gate.sh
     fixed-in: 8d31a0d2e
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: four defs, below
     impact: wrong-answer -->

```scalascript
def paren(xs: List[Int]): String  = xs match { case (_) :: t => "paren"  ; case _ => "z" }
def parenN(xs: List[Int]): String = xs match { case (h) :: t => "parenN" ; case _ => "z" }
def tup(p: (Int, Int)): String    = p match { case (a, b) => "tup" }
def plain(xs: List[Int]): String  = xs match { case h :: t => "plain" ; case _ => "z" }
```

| spelling | v2 | v1 |
|---|---|---|
| `case (_) :: t` | `z` — the catch-all | `paren` |
| `case (h) :: t` | `z` — the catch-all | `parenN` |
| `case (a, b)` (tuple) | ok | ok |
| `case h :: t` | ok | ok |

**Both spellings miss, so this is about the PARENTHESES, not the wildcard** it was first noticed
with (in `v2-cons-pattern-with-a-wildcard-head-returns-a-closure`, now fixed). A silent wrong answer:
the arm simply does not match and the catch-all answers.

### The obvious repair is not enough, and that is measured rather than guessed

`isConsArmHead` peeks exactly two tokens, so it cannot see the `::` past `(_)`. Extending it to parse
a full pattern when the arm starts with `(` — narrowly, so every other arm keeps the cheap test — was
implemented and measured. **The arm is then recognised and the answer changes from `z` to
`<closure>`**: one wrong answer for another.

The reason is one line further on, and the source comment above `parseArms0` names it: `parseConsArm`
*"assumes the shape `h :: t` and indexes blind: head at token 0"*.

```
def parseConsArm(ts, menv, cx) = parseConsArm1(snd(hd(ts)), snd(hd(tl(tl(ts)))), tl(tl(tl(ts))), …)
```

Head = token 0's text, tail = token 2's text, body = from token 3. For `(_) :: t` that reads `(` as
the head binder and `)` as the tail. It works today only because a wildcard or a name is exactly ONE
token.

**So a real fix has to move `parseConsArm` off token positions and onto the parsed pattern** — and
then a parenthesised sub-pattern raises its own question, since `(_)` reaches `parsePatTuple` and
becomes a one-element tuple pattern rather than a bare `wpat`. The nested path `parseGenCons` ->
`parseNestedArm` already works from a parsed pattern and does not index blind; the promising shape is
to reuse it rather than to teach `parseConsArm` a second way of finding binders.

Not attempted here: two wrong answers for one spelling is worse than one, and choosing between "teach
`parseConsArm` the pattern" and "route these arms to the nested resolver" is a design call with a
lowering difference behind it. The dispatch change was reverted, so the tree carries the honest `z`
rather than a `<closure>` that looks closer to working.

### FIXED 2026-08-18 — routing, and the doubt above is settled by reading `tuplePat1`

The design call is answered: **route these arms to the pattern-based resolver**, and teach
`parseConsArm` nothing. The entry's own reservation about that route — that `(_)` "becomes a
one-element tuple pattern rather than a bare `wpat`" — does not hold for `parsePatF`, and the line
that settles it is `tuplePat1`:

```text
def tuplePat1(first, rest, subs) = rest match { case Nil => first case h :: t => tuplePat2(...) }
```

A ONE-element parenthesised pattern is unwrapped to the pattern itself, which is also Scala's rule
(there is no 1-tuple). So `parsePatF` already reads `(h) :: t` as `("cpat", ("Cons", [h, t]))`,
identical to `h :: t`, and the arm only ever had to REACH `parseGenCons`.

**Reaching it is a resolver choice, not an arm test**, and that is the shape of the fix. Which
resolver a match uses is decided for the WHOLE match, so the new predicate is asked the way
`hasNestedArms` is asked — over every arm — and `parseMatchArms` sends a match containing a
paren-cons arm to `parseGenMatch`. Inside it, `parseGenArm` routes that arm to the existing
`parseGenCons`. `parseArm`, the ordered resolver, is not touched at all: its `parseConsArm` still
handles only the plain `h :: t` it has always handled, so nothing that works today changes shape.

**The later-arm case was worse than the entry recorded.** With no catch-all after it, the program
did not answer `z` — it died:

```text
case Nil => "nil" ; case (h) :: t => "later"     ->  ssc: match: no arm for Cons/2
```

Eight rows, and three of them exist to catch the failure mode the first attempt produced: `binds`
pins the VALUE (42, not a closure and not -1), and `tup`/`plain` are anti-rows — the fix moves a
match containing a paren-cons arm to the other resolver, and an ordinary tuple arm and an ordinary
`h :: t` must not move with it. Guarded paren-cons arms were measured too and answer correctly
(`g+`), reached by the existing `firstArmHasGuard` route rather than by the new predicate, which
requires the `=>` exactly as `consArmUnguarded` does.

Verified: `tests/e2e/v2-paren-cons-arm-gate.sh` PASS, all eight rows compared against `--v1`, plus a
row asserting the oracle still discriminates and one that fails if the probe shrinks below eight.
Negative control with the front reverted and re-staged: six rows red, the run dying partway.

## v3-block-has-no-semicolon-statement-separator — twelve corpus cases refused for a `;`

<!-- status: fixed
     lane: v3
     area: front
     kind: bug
     gate: v3/front-capability-gate.sh
     fixed-in: de11d1380
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: `val x = { 1; 2 }`
     impact: workaround -->

`val x = { 1; 2 }` did not parse: `expected an expression, found ;`. The only `;` v3 knew was the one
between `for` generators. Ordinary Scala, and the corpus is written in it — `handle { program();
List() }` is `tests/conformance/effects-handler.ssc` line 30.

**Found while trying to reproduce another entry**, which had recorded that file as printing `List()`.
It could not have: the file never parsed. A defect in the effects machinery was filed against a
symptom that belonged to the parser, which is what a repro is for.

Four statement positions needed it and each was its own loop: the top level, a braced block, an
indented block, and a match ARM body. The arm body is the one with a distinction worth keeping —
`;` there separates STATEMENTS and does not end the arm, so `case A => val x = 1; x` keeps `x` as the
arm's value while `case A(s) => s; case _ => "?"` still ends at `case`.

Twelve conformance cases moved off this blocker: `actors-global-registry`, `mcp-types`,
`scljet-address-write`, `scljet-journal-recover`, `tkv2-button-size`, `tkv2-button-variant`,
`tkv2-keyed-for`, `tkv2-raw-html`, `tkv2-select`, `tkv2-select-reactive`,
`tkv2-textfield-reactive-label`, `webauthn-server-verify`. Most now stop on a DIFFERENT honest
blocker — a `catch` arm binding one name, a `[...]` literal in `std/ui/lower.ssc`, an unimplemented
host function — which is the shape of a parse refusal standing in front of everything else.

`v3/front-capability-gate.sh` recorded the effect in the same commit: six cases dropped from
`KNOWN_CONF_UNIML_ONLY` because the v3 front no longer diverges from uniml on them, and one added to
`KNOWN_CONF_V3_ONLY` — `direct-control-flow`, where the same change made v3 the more permissive of
the two.

## v3-trailing-main-call-runs-main-twice — the same shape as the v2 front's, on the other lane

<!-- status: fixed
     lane: v3
     area: front
     kind: bug
     gate: -
     fixed-in: 609b217f1
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17 -->

```scalascript
def main(): Unit = println("once")
main()
```

v3 printed `once` twice; without the trailing call, once. Native prints it once either way.

**Fixed by a sibling within the day, from the pointer this entry carried.** `609b217f1` mirrors F's
`callsMain(docEntry)` into `Lower.scala`'s entry synthesis, and its comment cites the v2 half
(`v2-front-fallback-runs-the-program-twice`, `ae5b09418`) as the same shape — which is what the entry
was written to make possible. It also had to look through `__autoOutput__`, since the trailing
`main()` is usually already wrapped by the time the guard sees it: matching the bare call would have
found nothing in exactly the files that had the bug.

Verified here rather than taken on trust: `def main` plus a trailing `main()` prints `once`.

## v3-bridge-lags-the-executor-on-cross-frame-effects — the two v3 lanes now disagree

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh, v3/corpus-report.sh
     fixed-in: 1015e3865
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-17
     repro: v3/tests/effects/escaped-continuation.ssc; the refused shape is below
     impact: workaround -->

Fixing the executor's two effects defects left the v2 BRIDGE answering the old way, so the two v3
lanes disagreed — and before that work they had agreed and been wrong together, which is why no
fixture caught it. Three symptoms, and they turned out to have three different natures.

| program | executor | bridge before | bridge now |
|---|---|---|---|
| an escaped continuation with a return clause | `8` | a Java stack trace | **`8`** |
| a perform in a callee whose caller has a remainder | `H:a|H:b|END` | `END` | **a named refusal** |
| `handle { program(); List() }` | `List(first, second)` | `List()` | **a named refusal** |

### Fixed: the continuation carries its own handler record

The bridge does not map effects onto v2 primitives — it re-emits the executor's machinery as v2
CoreIR, so the same defect was there in the same shape. `__ssc3_eff_after_resume__` read the return
clause off the TOP of the handler stack, which is right only while the continuation is resumed inside
its own `handle`. An arm that returns a closure is resumed with the stack empty, and the lifting was
silently skipped.

`k` is now the pair `(closure, record)` — `__ssc3_eff_find__` parks the record it is about to run an
arm under and `armClos` reads it in its FIRST binding, before anything can perform and park another.
`__ssc3_eff_resume__` reads the clause and the perform counter from that record and reinstalls the
handler for the resume's duration, which is what lets a deep handler's second tick find it.
`tests/conformance/effect-deep-handler-state` moved DIFF → PASS.

### Refused, because emitted v2 code cannot capture a caller's remainder

`Exec` captures the caller frames at run time; a v2 function has no way to hand over "the rest of
me". So the bridge now says so by name instead of answering. The static test took three narrowings,
each paid for by a measurement:

  * a call inside a `Handle`'s BODY is the normal shape and must not be refused — six of the
    fourteen effects fixtures went red on that;
  * a function that HANDLES an effect does not propagate "may perform" outward, or
    `println(workload())` is refused for an effect `workload` already absorbed;
  * only a NON-TAIL-RESUMPTIVE arm needs the rest. When the arm resumes once as its last act, "run
    the arm and use its value" and "capture the rest and resume it" are the same computation — the
    class this bridge was always right about. `head-field-effect-shadow` is exactly that, CPS-encoded
    and cross-frame, and refusing it cost a corpus PASS until the test learned the difference.

**A real fix is one pass and would serve both lanes:** split callers at such calls the way `Cps`
splits at performs, thread the caller's continuation and compose. That would also let `Exec`'s
run-time frame capture go, which is the only reason the two lanes need two mechanisms today.

### Measured, as a controlled A/B

Same tree, same classifier, only `BridgeV2.scala` reverted and restored:

| | PASS | DIFF | UNSUP | CRASH |
|---|---|---|---|---|
| before | 257 | 2 | 108 | 0 |
| after | **258** | **0** | 109 | 0 |

The DIFF bucket — the report's word for a silent wrong answer — is empty on the bridge lane.

## corpus-report-files-a-named-bridge-refusal-as-a-CRASH — the bucket that means "tells you nothing"

<!-- status: fixed
     lane: v3
     area: conformance
     kind: apparatus
     gate: v3/corpus-report.sh
     fixed-in: 1015e3865
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-18
     repro: v3/corpus-report.sh --list-crash on a clean checkout
     impact: none -->

`corpus-report.sh` sorts a failing case by its stderr: *"A positioned, named refusal is UNSUPPORTED.
A stack trace or a bare failure is a CRASH, because it tells the reader nothing they can act on."*
The test is `grep -qE ':[0-9]+:[0-9]+:'`.

**A bridge refusal is named and can never be positioned.** `BridgeV2.Unsupported` prints
`ssc3: <file>: v2 bridge V-0 does not translate <what>` — a sentence naming the construct — and v3's
IR carries no positions at all (`grep -c Pos v3/src/Ir.scala` is 0). So every one of them landed in
the bucket defined as telling the reader nothing, and CRASH is the report's own "worse than DIFF".

**Not noticed while making a change look good, and that is checkable:** on a clean checkout
`--list-crash` shows the single CRASH to be exactly this species —
`effects  ssc3: …: v2 bridge V-0 does not translate a handler for operation 2 …`. The mis-filing
predates any of the work that ran into it.

The classifier now recognises that one sentence, and only it, as UNSUPPORTED — a second, narrow test
rather than a loosening of the first, so a genuine stack trace still counts as a CRASH.

## v3-bridge-cannot-cross-a-call-frame — fixed by building the closure the target cannot hand over

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: bc78e963c
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-18
     repro: v3/tests/effects/cross-frame-statement.ssc, cross-frame-in-handle-body.ssc
     impact: workaround -->

The bridge refused a continuation that had to resume its CALLER — `Exec` captures those frames at
run time and emitted v2 code cannot, because a v2 function has no way to hand over "the rest of me".

**It does not have to.** The compiler can build that closure at the call site, which is what
selective CPS is: convert only the part of the program that may perform. Filinski's
*Representing Monads* (POPL 1994) is the same result from the other end — with `shift`/`reset` a
monad needs no rewriting at all, and where the target has neither, the mechanical selective CPS is
what remains. `handle` is the `reset`; an arm is the `shift`.

    def body(): String ! C =
      prog()          # performs
      "END"
    handle(body()) { case C.w(msg, resume) => "H:" + msg + "|" + resume(()) }

    bridge before   a named refusal
    bridge now      H:a|H:b|END

`tests/conformance/effects-handler.ssc` prints its checked-in `List(first, second, third)` on the
bridge for the first time. Corpus A/B on one tree with only `BridgeV2.scala` differing: N 260 -> 261,
DIFF 0, CRASH 0. Both cross-frame shapes are fixtures now, agreeing on BOTH lanes — before this they
could not be, because the bridge answered differently.

### The shape

`splitCallers` cuts a caller at a non-tail call to a function that may perform, exactly as `Cps` cuts
at a perform. Two bodies are cut: a function's, and a `handle`'s — `handle { program(); List() }`
puts the call in the second, and that is the shape the corpus uses.

    g:  before
        MkClos kc, g$c, <every register>
        push kc; r = f(args); pop
        if aborting then r else kc(r)

**Neither half works alone.** `kc(r)` is what keeps the non-performing path right — without it the
remainder is dropped whenever `f` happens not to perform. And it is only correct because an arm's
value does not return through here: `Perform` raises a flag each split caller hands on, so `kc` runs
exactly when `f` came back on its own.

### Four things measured wrong first

  * the `let` shift, twice. A binding expression is evaluated OUTSIDE its `let`, so its operands stay
    at `sh`; this file already records paying for that once, because reading register k one level
    deep is textually identical to reading register k+1 at the right depth.
  * a resumed computation may perform AGAIN, and that inner perform owes the caller frames this one
    has not run yet — so the continuation re-parks them while its first half runs. Without it
    `H:a|H:b|END` came out `H:a|END`: the first tick right and the rest of the program lost.
  * composition must be guarded by ENCODING. A tail-resumptive arm has no continuation, and a nullary
    operation has an empty argument array — composing blind read index -1 and `handle-tail-resumptive`
    died inside v2. The record now carries the encoding per operation, which the emitter knows
    statically.
  * the region refusal had to stop testing for a tail call: the splitter's own output tripped it and
    refused what had just been repaired.

### What is left, and what it needs

A call inside an `if`, `loop` or `try`. A region's remainder is not a suffix of any instruction list,
so there is nothing to make a closure of — `Cps.scala`'s header calls this step 3.

**The shape of the answer is join points made explicit:** outline the remainder after a region into a
continuation, park it around the region, and call it on the normal exit; each branch then ends in a
transfer to it, and a region's inside becomes a suffix again, which the splitter above already
handles.

**The one thing that is NOT mechanical there** is registers. This splitter captures them by value,
which is correct only because nothing returns to the original frame after a split. Code after an `if`
must see what the branch wrote, so a join continuation has to SHARE the frame rather than copy it —
expressible here, since `(lam 0 …)` does not shift locals on this lane and the `handle` body already
relies on that. The cost to state plainly when it is done: a frame-sharing continuation is not
multi-shot, so a multi-shot arm resuming through a region wants a clone, the way `Exec` clones a
frame per arm activation.

## v3-a-continuation-could-not-resume-into-a-region — `if`, `try` and a loop's back edge, crossed

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: 830efe318
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-19
     repro: see below, two shapes
     impact: workaround -->

A call to a performing function inside an `if`, a `loop` or a `try` was REFUSED: the frame captured
there has a remainder that is a suffix of the REGION, and everything after the region would be lost.
`Cps.scala`'s header calls this step 3.

```scalascript
def body(flag: Boolean): String ! C =
  if flag then
    prog()          # performs
    "yes"
  else
    "no"
handle(body(true)) { case C.w(msg, resume) => "H:" + msg + "|" + resume(()) }
```

Refused before; `H:a|yes` now, which is what v1 and native answer. A `while` whose body calls a
performing function answers `t0t1|0|1`, matching v1 exactly.

### It needed no pass, and that corrects what this entry was going to say

The plan written into `v3-bridge-cannot-cross-a-call-frame` was join-point outlining: move a region's
remainder into a function so the inside becomes a suffix again. For the EXECUTOR that turns out to be
unnecessary, and the reason is worth keeping — **a region does not open a frame**. It runs in the
frame around it, so both remainders share one register array and there is nothing to thread. All that
was missing was recording the enclosing level's remainder on the way IN:

  * `stepFramed` pushes a `PendingFrame` for a region as it does for a call. `d = -2` marks it: a
    region receives no value into a register the way a call does.
  * The chain a perform captures is then region-suffix, enclosing suffix, caller frames — the whole
    rest of the computation.

An outlined region would have needed a liveness analysis, because a called function writes its OWN
registers and the code after the region reads them. Nine lines and no analysis, because the frame is
shared.

**A LOOP NEEDED ONE THING MORE.** A resumed loop-body remainder ends in `Branch(0)` — the back edge —
and a frame that knows only "finish this list" cannot answer it. The frame recorded for a `loop`
carries its BODY, so the walk keeps iterating exactly as `Instr.Loop` does (fall off the end to exit,
`Branch(0)` to repeat) and only then runs what follows the loop. The fact travels one frame outward
rather than being decided where it arises, which is why the loop's own frame is the one that answers.

### Two things measured wrong first

  * a snapshot has to KEEP ITS SHARING. Region frames share the array of the level around them, and
    cloning each frame separately broke that: the branch wrote into its own copy and the enclosing
    suffix read a stale one, so `resume(())` answered `()` where the computation said `yes`. One
    clone per distinct array, by identity.
  * a `ret` INSIDE a region leaves the FUNCTION, so the region frames of that activation are dead
    and must be skipped, not run. Without it the value a returning branch produced was handed to an
    empty region frame and fell through to unit.

### What is still refused, by name

  * a branch PAST the enclosing loop — `break` out of more than one level. The chain records depth
    per frame, not per branch, so it does not know how many frames to unwind.
  * a `perform` standing DIRECTLY inside a region. That is `Cps`'s own step 3 and a different thing
    from this entry: such a perform is never split, so only the tail-resumptive fast path runs it.
    What this work crossed is a CALL to a performing function, not a perform in place.
  * the same shapes on the v2 BRIDGE. Its answer is not this one: a bridge continuation is built by
    `MkClos`, which captures registers by VALUE, so a region continuation there has to SHARE the
    frame instead — expressible, since `(lam 0 …)` does not shift locals on that lane, but it has to
    be built by the EMITTER rather than by an IR pass, which is a different piece of work.

## v3-spec-prescribes-a-loop-route-nobody-took — three descriptions of one thing, and the authoritative one is unbuilt

<!-- status: fixed
     lane: v3
     area: docs
     kind: apparatus
     gate: v3/effects-gate.sh
     fixed-in: be8c74275
     confirmed: yes
     reported-by: claude-code
     reported-at: 2026-08-19
     repro: v3/specs/10-ssc-ir.md §3, "The cost, stated because it is not small"
     impact: none -->

`v3/specs/10-ssc-ir.md` §3 prescribes how a region gets a continuation:

> a `Loop` containing a `Perform` becomes a recursive function, since a loop's remainder cannot be a
> closure without one. v3 has `TailCall`, so those recursions are constant-stack rather than a new
> leak.

**Nobody took that route, and two other routes were built instead.** Both are mine and both work:

| where | mechanism |
|---|---|
| `v3/specs/10-ssc-ir.md` §3 | the LOWERING turns a loop containing a perform into a recursive function — **not implemented** |
| `Exec.scala` (`830efe318`) | the executor records a `PendingFrame` on the way INTO a region; the loop frame carries its body and keeps iterating |
| `BridgeV2.scala` (`bc78e963c`) | the compiler splits callers so a caller's remainder is a closure; regions there are still refused |

So a reader who starts from the spec — which is where they are told to start — learns a design that
does not exist, and two implementations that do exist are described only at their own sites.

**Why the two differ is not stylistic and belongs in whatever replaces this.** In the executor a
region does NOT open a frame: it runs in the frame around it, so both remainders share one register
array and nothing has to be threaded. On the bridge a continuation is built by `MkClos`, which
captures registers by VALUE, so a region continuation there has to share the frame by other means and
be built by the emitter.

**AGREED IN THE ROOM AND WRITTEN.** `v3-compile-time-extension`'s owner confirmed their claim does
not hold `specs/10-ssc-ir.md` and asked for it directly, adding one requirement that improved the
shape: state not only WHAT a continuation must contain but **what checks each clause** — they had
hit three rules that day which were written at one site and missing at two siblings, each surviving
only because an unusual input never reached it.

§3 now ends with "WHAT A CONTINUATION MUST CONTAIN — one invariant, two realisations": the three
clauses, a table naming the fixture that pins each, the two lane-specific mechanisms with the reason
they differ (who owns the registers), and the loop-to-recursive-function route marked as sound and
unimplemented.

**One cell of that table was deliberately empty**: the back edge was pinned by no fixture, because
`effects-gate.sh` requires three-way agreement and the bridge refused regions. Recorded as a
decision rather than left to look like coverage.

Found by checking a claim made about `Cps.scala`'s header before answering it — the header was
half-stale (the "step 4" clause), and looking for the authoritative statement of the same thing
turned up a third.

**THE EMPTY CELL IS FILLED, and it is why the gap got closed rather than described.** `f0a6e4840`
taught the bridge to cross regions — the first of the two ways out this entry named — so clause 3 has
two rows now and a third arrived with it. A table entry a reader can see is missing is a different
object from the same fact in prose, which would have read as an account of coverage. Recording the
shape of the hole is what made someone go and fill it, one day later.

**THE ROUTE ITSELF IS STILL UNBUILT** and that is now a recorded decision rather than a spec reading
as an answer: §3 quotes it, marks it unimplemented on both lanes, and says what it would replace.
Anyone taking it up would collapse two mechanisms into one statement, which is the only argument for
doing it and a good one.

## uniml-markdown-left-the-portable-subset-while-its-guard-ran-nowhere

<!-- status: fixed
     kind: apparatus
     lane: multi
     area: runtime
     reported-by: claude-code
     reported-at: 2026-08-16
     fixed-in: d92def007
     confirmed: yes
     gate: uniml/lint-portable-subset.sh -->

`uniml/lint-portable-subset.sh` guards the Scala 3 ∩ ScalaScript-v2 **runtime** subset across
`uniml/core`, `uniml/json`, `uniml/yaml` and `uniml/markdown` — the constructs UniML was deliberately
cleaned of so the same sources compile and run on the self-hosted v2 compiler. **It exits 1 today**,
and it is invoked by nothing, so nobody has seen it:

```
VIOLATION in uniml/markdown/src/main/scala/scalascript/uniml/dialect/markdown/MarkdownLexer.scala:
    141:      val out = new StringBuilder(s.length)
    161:    val out = new StringBuilder(s.length)
    168:        else Character.toLowerCase(c))
VIOLATION in uniml/markdown/src/main/scala/scalascript/uniml/dialect/markdown/MarkdownBlocks.scala:
    1035:      val out = new StringBuilder
== portable-subset lint FAILED: the constructs above do not run on v2 ==
```

Two files, four lines, both in the **markdown dialect only** — `core`, `json` and `yaml` are clean.
`StringBuilder` and `Character.toLowerCase` are on the banned list because they do not run on v2, not
because they are unidiomatic; the script's own header is careful to say it deliberately does NOT flag
constructs that merely await v2 FRONTEND support.

**Found by census, not by symptom** (`orphan-detector-scans-one-directory-and-misses-real-gates`):
the gate lives outside `tests/e2e/`, so until 2026-08-16 the orphan detector could not see it either.
It costs **0 s** to run — a `find` plus a `grep` over four directories, no toolchain, no build.

**NOTHING RAN THE MARKDOWN DIALECT ON v2 — measured, which is exactly why it could drift.**
`uniml/corpus/markdown/run-strict.sh` takes `<jvm|js>` and has no v2 platform, and `ci.yml`'s `uniml`
job runs `sbt -batch test`, i.e. the Scala 3 build. Nothing was going to go red.

### Fixed on both sides, and wired

**The three buffers took the shape this project already decided on**: `Vector[String]` + `.mkString`,
the accumulator `JsonLexer` ships and `specs/uniml-portable-gapmap.md` settled. The char→String step
is `c.toString`. **`String.valueOf(c)` is NOT portable and fails SILENTLY** — a capitalized receiver
lowers to an effect operation, so it yields `Op("String.valueOf", B, <closure>)` instead of a string,
with no error and a zero exit. Probed, not assumed.

**`Character.toLowerCase`/`toUpperCase` took the v2-side option the gap map already allowed**
(`v2/src/Runtime.scala`, `characterFold`). The fold is the half nobody can hand-roll: it is defined
by the whole Unicode table, unlike `getType`'s flanking classes which UniML replaced with a BMP range
table. `foldCase`'s own comment records why it must be the locale-INDEPENDENT fold — ASCII folding
scores the official corpus at 606 instead of 607.

**ONE CASE IN ONE FILE, and the first version was five files.** The obvious design — bind a
`Character` global in the prelude like `math`, add a `__char_obj__` prim, teach the JS and Rust
backends — was written and then a control showed it INERT: with the prelude binding removed, the
direct calls still worked. `Character.toLowerCase(c)` never evaluates a global at all. **The front
lowers a capitalized receiver as a nullary constructor**, so it reaches `methodDispatch1` as
`DataV("Character", [])` and falls into the EFFECT-TAG case. Answering there, ahead of that case and
through one helper shared with the `ForeignV` shape, is the entire fix; the other four edits were
reverted.

**Wired, because it lands green.** `uniml/lint-portable-subset.sh` is now a smoke check and is out of
`no-orphan-gates.sh`'s frozen list (31 → 30) — drained, not exempted. It costs 0 s: a `find` and a
`grep` over four directories, no toolchain.

**Evidence.** `STRICT CASES=675 PASS=607` on the CommonMark corpus — the exact number `foldCase`'s
comment predicts, so the rewrite is behaviour-identical where it matters most; `unimlMarkdown/test`
53/53; `uniml/v2-smoke/gap-char.ssc` green on v2 and SELF-CHECKING, because this gap's failure mode
is a wrong value with a zero exit and `run.sh` classifies by grepping for exceptions; the lint green
with a planted `Character.getType` still caught.

**The JS lane — and this note's FIRST version was wrong, corrected the same day.** It said the lane
needs "the mirrored case in `$method`". It does not: `run-js --v2` never reaches `$method`, dying one
layer earlier on the char LITERAL (`println('a')` → `unimplemented primitive: char`). **That lane has
no Char value at all**, and giving it one is a representation decision, not two lines — filed as
`v2/BUGS.md js-v2-lane-has-no-Char-at-all`. Writing the mirrored case would have put dead code above
an unreachable path, which is exactly what the wrong note invited.

The Rust lane declines by design — `char::to_lowercase` yields an ITERATOR there (U+0130 lowers to
two chars), so a char-to-char fold needs a documented truncation nobody has asked for.

## rust-map-plus-pair-is-not-lowered — `map + (k -> v)` reaches rustc as an addition, and the `updated` that means the same thing is already lowered three lines away

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-map-plus-pair-gate.sh
     fixed-in: 5203c31c0
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes -->

**Found the moment `rust-case-class-method-cannot-read-its-own-fields` stopped hiding it.** With
case-class methods fixed, `std/http.ssc`'s `withHeader` gets as far as its own body and stops
there:

```scala
def withHeader(name: String, value: String): Response =
  Response(status, headers + (name -> value), body)
```

```
error[E0369]: cannot add `(String, String)` to `HashMap<String, String>`
```

`+` on a Map is the immutable "with this pair added". The walker HAS that lowering — the `updated`
arm renders exactly the right shape:

```rust
{ let mut m2 = q.clone(); m2.insert(k, v); m2 }
```

so `map + (k -> v)` needs to reach it. The argument shape settles the receiver without a type
check: `x + (a -> b)` is a Map update in every program that type-checks at all, because `->` builds
a pair and nothing else takes one on the right of `+`.

Filed rather than folded into the case-class fix, which is about where a method's receiver comes
from and has nothing to say about Map operators. It is the last thing standing between
`Response(...).withHeader(...)` — ordinary HTTP code, and the spelling `std/http.ssc` itself uses —
and a crate that compiles.

**FIXED 2026-08-15 under `rust-map-plus-pair`.** One arm, placed AHEAD of the string-concat `+` so
the operator is decided by the pair rather than by whether an operand happens to look like a string,
rendering the same `{ let mut m2 = q.clone(); m2.insert(k, v); m2 }` the `updated` arm does.

**THE CLONE IS THE WHOLE CORRECTNESS ARGUMENT, and it is why the gate has a third row.** `+` on a
Map is IMMUTABLE: after `val h2 = h + ("b" -> "2")`, `h` must still not contain `b`. A lowering that
inserted into the receiver would pass "the pair is there" and "the old key survived" and quietly
corrupt every caller that kept the original, so the gate asserts `h.getOrElse("b", "?")` is still
`?` — on all three lanes, which answer `1|2|?|xy|3|` identically.

**Two anti-rows guard what this arm now sits in front of:** `"x" + "y"` is still `xy` and `1 + 2` is
still `3`. If either moves, the arm is keying off the wrong half of the expression.

**And the chain finally runs.** `Response(201, …).withHeader("X-A", "b")` in an inline route handler
answers `HTTP 201` with `x-a: b` set, on a real socket — three fixes deep: the Request entry for
inline handlers (`rust-inline-route-handler-is-typed-as-a-string-handler`), a case-class method's
receiver (`rust-case-class-method-cannot-read-its-own-fields`), and this. Corpus control:
`rust-std-survey-gate.sh` over 132 std modules reads its committed baseline unchanged — REFUSED 78,
COMPILES 54, BADRUST 0. Watched failing with the arm reverted and the toolchain rebuilt: both Rust
rows red with the literal `E0369`, both reference lanes green.

## rust-case-class-method-cannot-read-its-own-fields — a method on a case class is lowered to a free fn with the fields unbound, so the simplest data type in the language does not compile

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-case-class-method-gate.sh
     fixed-in: 85dfe333a
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes -->

Found while fixing `rust-route-handler-shapes`, and it is why that gate has no builder-chain row:
`Response(…).withHeader(…)` selects the right runtime entry and the crate still does not compile.
The cause has nothing to do with routing.

```scala
case class P(x: Int, y: Int):
  def shifted(d: Int): P = P(x + d, y)
```

```
error[E0425]: cannot find value `x` in this scope
error[E0425]: cannot find value `y` in this scope
```

The method is emitted as a FREE function — `pub fn shifted(d: i64) -> P` — with no receiver, so the
field names in its body bind to nothing. Read off the emitted crate for `std/http.ssc` itself:

```rust
pub fn withHeader(name: String, value: String) -> Response {   // no `self`
    Response { status: status, … }                             // `status` unbound
}
```

**Only this lane.** `x`, `this.x` and a sibling call all answer `1` on `run` and on `--v1`; all
three fail here. `std/http.ssc`'s three builders (`withHeader`, `withSession`, `clearSession`) are
the ones a program actually reaches, which is how it surfaced, but nothing about the repro is
HTTP-shaped: it is two Ints.

Not fixed here because it is a codegen change of a different size from the routing one it was found
under, and folding it in would have made a claim about route handler SHAPES also about method
lowering.

**FIXED 2026-08-15 under `rust-case-class-methods-impl`.** The mechanism was one line of collection:
`contentDefs` gathers defs with a DEEP `.collect`, and a class body is just more tree, so a method
arrived at the emitter indistinguishable from a top-level `def`. It now renders through the SAME
`renderDef` — rendering it separately would be a second copy of "how a def becomes Rust", and the
two would drift — and lands in `impl <Owner>` with `&self`. Ownership is keyed on the def's POSITION,
not its name: two classes may declare the same member, and the name alone cannot say which class a
def came from.

**The receiver is bound, not substituted, and that is the design decision worth keeping.** Inside the
method, the fields the body reads are emitted as `let x = self.x.clone();` and the siblings it calls
as `let m = |__a0| self.m(__a0);`. The alternative was rewriting the body's tree — and a field appears
in patterns, in string interpolations and inside nested closures, so a rewrite that missed one of
those positions would emit a crate that COMPILES and reads the wrong value. A `let` covers every
position at once because it works the way the source already assumed: the name is in scope.
(scalameta's own `Transformer` would have been the tool, and this build does not ship one —
`scala/meta/transversers/` carries `SimpleTraverser` only.) Only what the body USES is bound: an
unused binding warns, and an unused closure does not even compile.

**Verified against the corpus, not only against the repro.** `tests/e2e/rust-std-survey-gate.sh` over
132 std modules reads exactly its committed baseline — REFUSED 78, COMPILES 54, BADRUST 0 — so no
module changed status in either direction. The gate's own three rows run on all three lanes and
compare ANSWERS rather than compilation, because a method that reads the wrong field compiles fine;
`P(1,2).shifted(5)` gives 6 and 2, `sum()` gives 3, and swapping the fields changes them. Watched
failing with the fix reverted and the toolchain rebuilt: the two method rows red with the literal
`E0425`, the no-method row still green — it is the anti-row, and a revert must not move it.

`Response(...).withHeader(...)` still does not compile, one defect further in:
`headers + (name -> value)` reaches rustc as an addition. Filed as `rust-map-plus-pair-is-not-lowered`.

## rust-absolute-import-path-does-not-resolve-a-case-class — the same program emits or refuses depending on how its import is spelled

<!-- status: duplicate
     lane: v2-rust
     area: codegen
     kind: bug
     gate: none — see the entry this duplicates
     duplicate-of: rust-absolute-import-path-inlines-nothing
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes -->

**DUPLICATE of `rust-absolute-import-path-inlines-nothing`, which was filed one day earlier and is
the broader statement of the same defect**: the absolute form contributes NO declarations at all —
not just the case classes I happened to reach for. I filed this one without searching the board
first, and the two evidence paragraphs this entry added that the older one did not have have been
moved there. Kept as a stub rather than deleted so the slug still resolves.

The original body follows, for the record.

Found while writing `tests/e2e/rust-route-handler-shapes-gate.sh`: the gate's first version put its
sandbox in `$TMPDIR` and therefore had to write the import with an absolute path. Every Response row
went red, and none of them were about routing.

```
[route, serve, Request, Response](../../std/http.ssc)   emits
[route, serve, Request, Response](/abs/…/std/http.ssc)  error: def `main` calls `Response`,
                                                        which this crate does not define
```

Byte-identical bodies; only the import path differs. The FUNCTIONS from the same import resolve
either way — `route` and `serve` are fine — so it is the case-class member of the import list that
is dropped.

**The emit-time refusal is only a partial view of the damage.** With the absolute spelling, a
handler whose body is directly `Response(…)` is refused at emit, while one that reaches the same
constructor through an `if` emits a crate — and that crate then fails at cargo:

```
error[E0412]: cannot find type `Request` in this scope
error[E0412]: cannot find type `Response` in this scope
```

So the types are not in the crate at all; the emit-time check merely catches one of the positions
they can appear in. Both imported case classes are dropped, not just the one named in the message.

Worked around in the gate the way `rust-http-lane-parity-gate.sh` already does it — the sandbox is
created inside `examples/` so the import can stay relative — and filed here rather than fixed,
because a gate is not the place to discover import resolution.

## string-length-counts-bytes-not-characters — `length` answered BYTES on the Rust lane while `substring` indexed code units, so the pair panicked on any non-ASCII string

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-string-length-gate.sh
     fixed-in: 808b8fd0e
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-14
     ssc-version: a8dc2120c
     repro: repro/string-length-counts-bytes-not-characters.ssc
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-15. The reporter's section below is theirs, in their words.

`String.length` answers BYTES on `build-rust` while `substring` and `indexOf` count CHARACTERS, so
the two cannot be combined — and the lanes do not agree on `length` either. One file, four lines:

```
val s: String = "aé·b"          // 4 characters, 6 UTF-8 bytes

                     run    build-rust
s.length              4         6
s.indexOf("b")        3         3
s.substring(0, 1)    "a"       "a"
s.substring(1, s.length)  "é·b"   PANIC: substring(1, 6): out of range for a string of 4 code units
```

Repro: `repro/string-length-counts-bytes-not-characters.ssc`. Measured on a toolchain staged from
`a8dc2120c` whose `bin/lib/.build-digest` equals `scripts/launcher-input-digest`, in a detached
worktree.

WHAT IT COSTS, and why this one is filed ahead of the other things found in the same hour. It is not
a compile error and not a wrong value: it is a PANIC on the request path. Found by porting a route
that patches a token into an HTML page — `html.substring(at, html.length)`, the obvious way to write
"from here to the end". `view.html` is 27,449 bytes of 26,332 characters, so the first real request
killed the tokio worker, and every request after it answered `PoisonError` — one non-ASCII character
anywhere in the served page takes the whole server down, permanently, with no bad input from the
caller. An ASCII test page hides it completely.

`indexOf` agreeing across the lanes while `length` disagrees is the part that makes it hard to
notice by reading: the index is right, the bound is wrong, and the two look symmetrical in the
source.

Two things ruled out before filing. Not the receiver: a literal, a parameter and a local all behave
the same. Not `substring` — it is self-consistent in characters; the disagreement is `length`.

No fix branch this time. Which unit `length` should answer is a language decision, not a patch: the
`run` lane says characters and every string method on both lanes indexes in characters, which is an
argument for characters — but `length` may have callers that mean bytes, and I cannot see them from
outside. Whichever way it goes, the two lanes agreeing matters more than which one moves.

**Reproduced on current main before anything changed**, byte for byte with the report: `6`, then a
panic — `substring(1, 6): out of range for a string of 4 code units`, exit 101.

**THE UNIT WAS MEASURED, NOT CHOSEN, and this is the part the reporter left open.** They wrote that
"which unit `length` should answer is a language decision" and that the `run` lane's characters
argue for characters. The measurement says something more specific: `"a😀b".length` is **4** on both
reference lanes, not 3 — a surrogate pair counts as two, so the answer is UTF-16 CODE UNITS, which is
exactly the basis `_str_char_at` and `_str_substring` on this lane already index in. So there was no
language decision left to make: the unit was already chosen and written down in the runtime, and
`length` was the one member of the family not connected to it. `chars().count()` — the intuitive
repair — would have agreed on the reported string and diverged on the astral one.

**Fixed as `_str_length` beside the other `_str_*` helpers**, not inline at the emission site: a
second copy of "what a code unit is" is how `length` and `substring` drifted apart in the first
place. The walker redirects only a receiver it can SEE is a String; anything else keeps `.len()`,
which is correct for a Vec — and the gate carries `List(10,20,30).length` as the row that fails if
someone widens that.

**The gate was watched failing** with the fix reverted and the toolchain rebuilt: `run` and `--v1`
unchanged, the Rust row red with the literal symptom.

```text
✗ rust: got '6|3||thread 'main' panicked … substring(1, 6): out of range for a string of 4 code units'
```

## tolong-on-a-string-answers-four-different-things — on a method the spec calls a synonym
<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: v2/conformance/tolong-on-a-string.coreir + tests/e2e/rust-toint-parity-gate.sh
     reported-by: sergiy
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: 0d9f601f9edeabd8ead185232d55c504d29aab51 -->

**Asked for directly after `v2-rust-backend-carries-the-same-silent-zero` closed with `toLong` left
alone.** That entry's note said there was no oracle to move toward, because `run-ir` answered
`<closure>`. Following the instruction to fix it found the reason: the VM had no
`(StrV, "toLong")` arm at all, so the `<closure>` was the DOWNSTREAM SYMPTOM of the missing arm and
not a decision anyone had taken. Leaving it had been the wrong call.

`specs/numeric-widths.md` §2.1 is explicit — *"Int and Long are the same runtime type, not merely the
same width"* — there is one `IntV`. So `"8".toInt` and `"8".toLong` are the same question. They were
not the same answer:

| lane | `"8".toLong` | `" 8 ".toLong` | `"abc".toInt` |
|---|---|---|---|
| `run-ir` (VM) | **refused** — "a method that does not exist survived as `<closure>`" | — | aborts |
| jvm generator | 8 | **0** — `toLongOption` with no `.trim` | **0**, and the program continues |
| js generator | **threw** `no dispatch for .toLong` | — | throws (correct) |
| rust generator | **0**, silently | **0** | fixed the day before |

Four lanes, four answers. The VM's refusal was the loudest and still misleading: it says the method
does not exist, when what did not exist was the arm.

**Two defects found by asking the same question of the siblings rather than only of the row that was
reported** — the same method that found three defects behind one in the Rust generator:

* the **JVM generator answers 0 for junk and keeps going** (`getOrElse(0L)`), where `run` aborts.
  Third generator carrying that shape, and in no entry.
* the **JVM generator had no `.trim`**, so `" 8 ".toLong` was 0 there while the VM answers 8.

**Semantics are copied, not chosen.** `toInt`'s arm is the model, including its deliberate divergence
from real Scala: `" 8 ".toLong` throws in Scala 3 (measured) and answers 8 here, because `toInt`
already did and the two spellings must not disagree with EACH OTHER. Which of the two is right
against Scala is a separate question and is not settled here. `toLongOption` ships with the throwing
form for the same reason `toIntOption` does — a throwing conversion with no total sibling is a gap
the fix would have created.

**Verified in both directions.**

* `v2/conformance/tolong-on-a-string.coreir` — ALL GREEN, 4 backends, with `run-ir` as oracle.
  Control: with the VM fixed and the three generators reverted, **all four columns fail** — jvm on
  the `" 8 "` row, js on all three (it produced no output at all), rust and wasm on rows 1-2.
* `tests/e2e/rust-toint-parity-gate.sh` — the junk rows, which the conformance harness cannot carry:
  it compares stdout and treats a VM abort as "run-ir failed", so "must abort" is inexpressible
  there. Two programs, because an abort ends the program and `toInt`/`toLong` were not the same code.
* Whole harness after the shared-VM change: 15 fixtures × 4 backends, ALL GREEN.

**Row `" 8 "` is the one that cannot be faked.** A backend that aliased `toLong` onto a raw parse
passes `"8"` and fails this — which is exactly what jvm did.

## serve-binds-all-interfaces — a service could not be kept off the LAN, on any lane

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/http-bind-address-gate.sh
     fixed-in: e5311346f
     reported-by: rozum
     reported-at: 2026-08-13
     ssc-version: 61eaefc57
     repro: none
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-14. Everything in the reporter's section below is theirs, in their
words; the measurement of the OTHER lanes and the decision about defaults are mine and are marked.

`serve(port)` binds `0.0.0.0` and there is no way to say otherwise, so every ScalaScript HTTP
service is reachable from the LAN whether or not it was meant to be.

EVIDENCE, from the machine this was found on — every listening socket, split by who built it:

```
rozum-gateway  127.0.0.1:8089   127.0.0.1:8401   127.0.0.1:8411   127.0.0.1:8779   (Rust)
rozum-meeting  *:8405   *:8406                                                     (.ssc)
ssc_program    *:8493   *:8497                                                     (.ssc)
```

Four live services listening on every interface. Not one of them chose that: `_http_serve` in the
Rust runtime template does `SocketAddr::from(([0, 0, 0, 0], port as u16))` and `serve` takes a port
and nothing else, so the program cannot express the narrower choice.

WHERE IT BIT: a rozum console route is moving from an in-process Rust handler to a .ssc program.
The Rust one binds loopback. The .ssc one cannot, so the move would widen exposure as a side effect
of a refactor that is supposed to be behaviour-preserving. That is the whole reason this is a report
and not a preference.

A BRANCH IS PUSHED: `feature/ssc-http-bind-address` (21e9ad9ba). It reads `SSC_HTTP_BIND` when set
and keeps `0.0.0.0` otherwise, so nothing that serves the network today stops doing so, and a value
that does not parse fails loudly at startup rather than silently binding wider than asked:

```
serve(8499): SSC_HTTP_BIND="не-адрес" is not an address: invalid socket address syntax
```

Measured with a four-line server built by that toolchain (`.build-digest` equals the tree digest,
STALE banner silent): unset → `*:8499`; `SSC_HTTP_BIND=127.0.0.1` → `127.0.0.1:8499` with the LAN
address refused; a bad value → the panic above.

An environment variable rather than a `serve(host, port)` overload on purpose — smallest change that
makes an existing deployment confinable, no typer or lowering change. The second arity is the better
API; take it instead if you prefer, the branch does not block it. Either way the default should stay
`0.0.0.0` so nobody's running service goes dark on upgrade.

**FIXED, and on more lanes than the branch touched.** The reporter's `feature/ssc-http-bind-address`
changed the Rust runtime; their commit is preserved here with their authorship. Taking only that
would have added a fourth behaviour, because the lanes already disagreed — measured with `lsof`, one
four-line server, one port:

| lane | before | with `SSC_HTTP_BIND=127.0.0.1` |
|---|---|---|
| `run` (v2 native) | `127.0.0.1` | `127.0.0.1` |
| `--v1` (interpreter) | `*` | `127.0.0.1` |
| `build-rust` | `*` | `127.0.0.1` |

So the variable is now read by `JdkServerBackend`, `FastServerBackend` and the native lane's host as
well, **including their TLS branches** — `createServerSocket(port)` is the wildcard overload, and a
program confined in plaintext while world-facing over HTTPS would be the worse half to get wrong.
An address the host cannot resolve stops the server rather than silently binding wider than asked.

**The defaults are unchanged and that is deliberate**, filed as
`http-lanes-disagree-on-the-default-bind-address`: which default is right is a product decision with
availability on one side and exposure on the other, not something to settle inside a bug fix.

**The second arity the reporter offered (`serve(host, port)`) was not taken here.** It is the better
API and the branch does not block it; it needs a `std/http.ssc` declaration plus a lowering on every
lane, and every lane that did not implement it would silently ignore the argument — the exact class
of divergence this entry is about.

## http-lanes-disagree-on-the-default-bind-address — `run` binds loopback where `--v1` and `build-rust` bind every interface

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/http-bind-address-gate.sh
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: 73e572730 -->

Found while fixing `serve-binds-all-interfaces`, and it is the part that report did not contain.
The same four-line server, one port, read from the OS with `lsof` rather than from any log line:

| lane | listening |
|---|---|
| `run` (v2 native) | `127.0.0.1` |
| `--v1` (interpreter) | `*` |
| `build-rust` | `*` |

**Nothing decided this.** `FastHttpServer` declares `host: String = "127.0.0.1"` and the native
host lets the engine bind, so that default applies there; `FastServerBackend` and `JdkServerBackend`
build their own `ServerSocket` with `InetSocketAddress(port)` — the WILDCARD constructor — and never
reach it. The difference falls out of which caller makes the socket. `git log -S` finds no commit
that argues for either.

**A program can now SAY where it binds on every lane** (`SSC_HTTP_BIND`, honoured by all three
including their TLS paths, unresolvable value fails at startup). What is still open is which default
is right, and that is a product decision with a security dimension on one side and an availability
one on the other:

- making everything loopback takes deployed services dark on upgrade — the reporter asked
  explicitly that this not happen;
- making everything wildcard puts `ssc run`, the dev-loop command, on the LAN of whatever café its
  user is sitting in.

Left as-is deliberately rather than decided in a bug fix. The gate asserts the KNOB on all three
lanes and each lane's CURRENT default, so whichever way this goes, the change will be visible as a
row that has to be edited on purpose.


### DECIDED 2026-08-15 by the owner — sort by what the COMMAND IS FOR, not by lane

```text
run-it-now commands  -> loopback     `ssc run`, `ssc-tools run --v1`
a built artifact     -> wildcard     `build-rust`
```

**Both blanket answers cost something, and that is why this sat open. Sorting by purpose pays
neither.** `build-rust` keeps wildcard, so nothing already deployed goes dark on upgrade — the thing
the reporter asked for explicitly. `ssc run` was already loopback. Only `--v1` moves, and it moves to
where the other run-it-now command already was.

**So the "odd one out" was named backwards.** The entry above, and the gate's own comment, read `run`
as the outlier because it was one loopback among two wildcards. Counted by purpose instead of by
tally, `--v1` is the outlier: it is a run-it-now command that behaved like a deployment artifact.

**One line per backend, and one decision site instead of two.** `FastServerBackend` and
`JdkServerBackend` each had a `case None => InetSocketAddress(port)` — the wildcard constructor —
AND a separate `case None` in their TLS branch reaching for `createServerSocket(port)`. Plaintext and
TLS therefore decided the default in different places and could drift apart; both now go through
`bindAddr`, which is the only thing that knows what the default is.

**The gate's control had to flip, and this is the part worth keeping.** It asserted
`SSC_HTTP_BIND=127.0.0.1 -> loopback` on `--v1`. With the default now loopback that row would pass
whether or not the knob works at all — a control indistinguishable from the default measures
nothing. It now asserts the knob can WIDEN: `SSC_HTTP_BIND=0.0.0.0 -> wildcard`.

**Verified:** `tests/e2e/http-bind-address-gate.sh` PASS, all six default/knob rows plus both
unresolvable-value refusals, reading the bound address from the OS with `lsof` rather than from a log
line. `runtimeServerJvmFast/test` 13/13 and `runtimeServerJvm/test` 33/33, which is what covers the
TLS branches the gate does not reach.
## route-handler-lowered-to-string — a handler declared `Request => Response` was lowered with a `-> String` callback, so no handler could answer anything but 200

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-http-lane-parity-gate.sh
     fixed-in: b34f10931
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-14
     ssc-version: a8dc2120c
     repro: repro/route-handler-lowered-to-string.ssc
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-14. The reporter's section is theirs, in their words.

`std/http.ssc` declares `extern def route(method: String, path: String, handler: Request => Response)`.
`build-rust` lowered the registration to a callback returning `String`, so any handler written to
that declaration failed to compile:

```text
error[E0308]: mismatched types
136 | ...ing + Send + Sync> = Box::new(move |req| { handler(req) });
    |                                               ^^^^^^^^^^^^ expected `String`, found `Response`
```

Reporter: "a handler that can only answer a `String` can only answer 200. There is no way to write
400, 404 or 410 — and no workaround on the caller's side, because the missing thing is the return
TYPE of the callback, not something the body can construct." A finished port of two rozum console
routes could not be built past it.

**Reproduced on current main before anything was changed**, and fixed from the branch the reporter
had pushed (`fix/http-response-status`), reviewed rather than merged on trust: it reads the handler
def's declared return type — the same three shapes the neighbouring `handlerDeclaresRequest` reads a
parameter from — and emits a wrapper that turns a `Response` into the runtime's `ResponseParts`,
keeping the String surface when it cannot prove otherwise, which is the safe direction.

**Verified ON THE WIRE, because "it compiles" was only half the report:**

```text
run   /ok=200 /gone=404 body=missing
rust  /ok=200 /gone=404 body=missing      (before: the crate did not build)
```

**What the fix does NOT cover, measured and filed separately:** a handler written as an INLINE
lambda (`route("GET", p, req => Response(404, …))`). See
`rust-inline-route-handler-is-typed-as-a-string-handler`.

## query-not-percent-decoded-on-build-rust — a query value reached the handler raw, so a route keyed on it matched nothing

<!-- status: fixed
     lane: v2-rust
     area: runtime
     kind: bug
     gate: tests/e2e/rust-http-lane-parity-gate.sh
     fixed-in: b34f10931
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-14
     ssc-version: a8dc2120c
     repro: repro/query-not-percent-decoded-on-build-rust.ssc
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-14. The reporter's section is theirs.

```text
GET /q?m=mlx-community%3AQwen3.5-4B-MLX-4bit&q=a+b%20c

ssc run                m=[mlx-community:Qwen3.5-4B-MLX-4bit]    q=[a b c]
ssc-tools build-rust   m=[mlx-community%3AQwen3.5-4B-MLX-4bit]  q=[a+b%20c]
```

Reporter: "Nothing fails. The handler is handed a string that looks like a value, and a route keyed
on it simply matches nothing. In rozum the key is a model spec, which a browser sends as
`mlx-community%3A…`, so the ported route answered 'no such cell' for every cell, while the Rust
server it replaces answered with the row. Wrong data, no error, no log line."

**The comment in the code argued FOR the old behaviour**, which is why the reporter quoted it rather
than just patching: `urlencoded_pairs` said decoding was "deliberately NOT done here… decoding
exactly one of them would be the only place a value silently changed shape". The premise is what
fails — the `run` lane on the same declaration DOES decode, so the lanes already disagreed and the
Rust one was alone; and the same function serves form bodies, where `+`-for-space is not optional.

**Fixed from the reporter's branch (`fix/query-percent-decoding`), reviewed rather than trusted.**
The decoder accumulates BYTES and converts once at the end, which is what makes a multi-byte `%D0%9F`
survive; a malformed escape is kept verbatim (`100%` is a user's text); the KEY is decoded too. The
branch also carried an unrelated 251-line `pm.ssc`, which was not taken.

**Verified on the wire:** both lanes now answer
`m=[mlx-community:Qwen3.5-4B-MLX-4bit] q=[a b c]`.

## rust-inline-route-handler-is-typed-as-a-string-handler — a bare lambda handler never reaches the Request/Response path at all

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-route-handler-shapes-gate.sh
     fixed-in: ea6eed905
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes -->

Found while fixing `route-handler-lowered-to-string`: that fix covers a handler written as a `def`
(or a lambda that CALLS one), and an inline lambda is a different defect one level earlier.

`handlerDeclaresRequest` cannot see a `Request` parameter in a bare lambda, so the call is emitted
against `_http_route` — the plain-string runtime entry — and the handler's return type is never
consulted at all. Read off the emitted crate rather than the source:

```rust
crate::runtime::http::_http_route("GET".to_string(), "/x".to_string(),
    move |req| { Response { status: 404i64, … } });
```

Three spellings, three distinct rustc errors, all on current main with both fixes in:

| handler | rustc |
|---|---|
| `req => Response(404, …)` | `error[E0308]: mismatched types` |
| `req => if … then Response(…) else Response(…)` | `error[E0609]: no field 'path' on type 'String'` |
| `req => Response(…).withHeader(…)` | `error[E0425]: cannot find value 'status' in this scope` |

The second one names the real cause: `req` is typed `String`, so the parameter side is wrong before
the return side is ever reached.

**AND THE BLOCK FORM IS THE SAME DEFECT, which makes this worse than a spelling preference.** Found
2026-08-14 while measuring something else: `route("GET", p) { req => handler(req) }` — a lambda by
another name — fails here with `expected Request, found String`, and that is **the only spelling the
`--v1` interpreter accepts**: it refuses the three-argument form outright with
`route(method, path) { handler }`. So there is currently NO way to write a route that builds on the
Rust lane and runs on the interpreter:

| spelling | run (v2) | --v1 | build-rust |
|---|---|---|---|
| `route(m, p, handler)` — what `std/http.ssc` DECLARES | works | refuses | works |
| `route(m, p) { handler }` | works | works | E0308 |

**An attempt is recorded so the next person does not repeat it.** I extended `handlerReturnsResponse`
to recognise `Response(…)`, its factories and its builders — then emitted the crate and found the
branch UNREACHABLE for those shapes, because the routing decision happens earlier. The extension was
removed rather than shipped with a comment claiming a coverage it does not have.

**Where the fix goes:** the parameter side. `route`'s own declaration in `std/http.ssc` says
`handler: Request => Response`; reading an extern's parameter types at the call site would settle
both halves for every spelling, and is the same shape as the extern-default work
(`v2-extern-default-argument-is-never-filled…`) — a declaration that exists and is not read.

**FIXED 2026-08-15, and NOT the way the line above proposed.** Reading the declaration would move
EVERY handler to the Request entry, including the untyped lambdas that answer a String today — and
a handler that uses its parameter AS a string (`req => "hello " + req`) would then be handed a
`Request`. The target chooser's own comment asks for the opposite, in as many words: an untyped
lambda keeps receiving the path/body string it has always received. So the recogniser keys off what
the BODY PROVES instead — a body that builds a `Response` cannot be a string handler, because it
does not compile as one, which IS the reported E0308. Nothing that compiles today changes shape.

One `producesResponse` now answers for both decisions — which runtime entry the call takes and what
the adapter says the handler returns — because they were two functions that could disagree, and the
attempt recorded above is what disagreeing looks like: a return-side extension that no entry ever
reached. It recognises the constructor, the `Response.x(…)` factories, the three declared builders,
`if`/`else`, a block's last expression, and a call to a `def` declared `: Response`.

**The block form was the same defect one layer down.** `route(m, p) { req => … }` reaches the
walker as `Block(List(Function))`, so a match on `Term.Function` alone fell through to the String
entry while the identical lambda written inline took the Request entry. Both decisions now read the
handler argument through one `handlerArgOf`, which unwraps a single-expression block.

| spelling | before | after |
|---|---|---|
| `req => Response(404, …)` | E0308 | `_http_route_req`, `-> Response` |
| `req => if … then Response(…) else Response(…)` | E0609 | `_http_route_req`, `-> Response` |
| `route(m, p) { req => Response(…) }` | E0308 | `_http_route_req`, `-> Response` |
| `req => "plain"` (control) | String entry | String entry |
| `def h(req: Request): String` (control) | `_http_route_req`, `-> String` | unchanged |

`req => Response(…).withHeader(…)` selects the right entry and still does not compile, for a reason
outside routing entirely — see `rust-case-class-method-cannot-read-its-own-fields`. That is why the
gate has no builder row: a red row there would be measuring the other defect.

**Verified on the wire:** `GET /refuse` against a handler written as an inline `Response(404, …)`
answers `404`. The gate was watched FAILING with the fix reverted and the toolchain rebuilt.

## build-rust-indexof-on-string — indexOf on a String took the LIST lowering, and String has no `iter`

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: d50eb34e1
     gate: tests/e2e/rust-toint-parity-gate.sh
     reported-by: rozum
     reported-at: 2026-08-13
     ssc-version: 61eaefc57
     repro: none
     confirmed: yes -->

Routed from `INBOX.md` on 2026-08-14. The reporter's section is theirs, in their words; the
mechanism and the line number are mine, read off the walker.

**Reproduced on current main** — the reporter could not re-check their own report ("a fresh detached
worktree does not bootstrap"), so this is the missing verification:

```scala
def main(): Unit =
  val s: String = "abc</head>def"
  val i = s.indexOf("</head>")
  println(i)
main()
```

- `ssc run` → `3`
- `ssc-tools emit-rust` → `let i = (s.iter().position(|__e| *__e == "</head>".to_string())…)`
- `ssc-tools build-rust` → rc=101, `error[E0599]: no method named iter found for struct String`

Reporter: "The list lowering for `indexOf` is being applied to a String receiver. Rust's own
suggestion (`chars()`) is not the fix — that would index by codepoint, not by the substring asked
for; the substring case wants `str::find`. WHERE IT BIT: injecting a `<script>` before `</head>` in
a served HTML page. Either lowering is fine by me. Refusing at compile time with 'indexOf on a
String is not supported' would also be an improvement over emitting code that cannot build."

**The site is `RustCodeWalk.scala:3148`.** The arm matches `x.indexOf(v)` for every receiver except
an Option or a Range, so a String receiver takes the Vec lowering. Adding `isStringExpr(qual)` as a
third exclusion and emitting the string form is the shape of the fix.

**FIXED with the trap handled, and the trap was worth more than the arm.** `str::find` answers a
BYTE offset while Scala's `indexOf` answers an index into UTF-16 code units; returning `find`'s
number would have been right for the reporter's ASCII HTML and silently wrong for anything else. The
emitted form converts the prefix — `__h[..__b].encode_utf16().count()` — and the gate pins it with a
NON-ASCII row: `"héllo</head>x".indexOf("</head>")` is **5** on both lanes, where the byte offset is
6. The bindings are `&str` rather than `let __h = $q`, because taking the value would move a String
local and the next read of it is E0382 — a defect this backend has shipped before.

A char argument and the two-argument form still take the old arm, unchanged: the new one requires
both the receiver and the argument to be string-shaped.

## build-rust-concat-list-element-with-toplevel-val — `parts(0) + SEP` emitted `String + String`, which Rust has no impl for

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: d50eb34e1
     gate: tests/e2e/rust-toint-parity-gate.sh
     reported-by: rozum
     reported-at: 2026-08-13
     ssc-version: 61eaefc57
     repro: none
     confirmed: yes -->

Routed from `INBOX.md` on 2026-08-14. The reporter's section, including their narrowing table, is
theirs; the mechanism and the line number are mine.

**Reproduced on current main:**

```scala
val SEP: String = "-"
def main(): Unit =
  val parts: List[String] = List("a", "b")
  println(parts(0) + SEP)
main()
```

- `ssc run` → `a-`
- `ssc-tools emit-rust` → `crate::runtime::_println((parts[(0i64) as usize].clone() + SEP));`
- `ssc-tools build-rust` → rc=101, `error[E0308]: expected &str, found String`

The reporter narrowed it themselves, and the table is the useful part — it is the ORIGIN of the left
operand that decides, not the arity and not the list:

| expression                                   | build-rust |
|---|---|
| `a + SEP` where `val a: String = "x"`        | ok — emitted as `format!` |
| `a + SEP` where `val a: String = parts(0)`   | FAILS |
| `parts(0) + SEP`                             | FAILS |
| `parts(0) + "-" + parts(1)` (literal middle) | ok |
| `a + SEP + b` (three plain locals)           | ok |

Reporter: "Workaround is to keep every concatenation to exactly two operands and avoid a top-level
`val` on the right — which is a rule no author could guess from the source."

**The site is `RustCodeWalk.scala:3650`.** The `format!` lowering fires when
`isStringExpr(t) || ctx.localStrings.contains(name)` holds for EITHER operand. `parts(0)` is a
list-index expression the walker does not classify as a string, and `SEP` is a TOP-LEVEL val, which
`ctx.localStrings` does not carry — so neither side qualifies, the arm is skipped, and the numeric
`+` emits `String + String`. That is exactly why the reporter's table moves with the origin of the
operands rather than with their count.

**FIXED, and there were TWO causes, not one — measured by emitting all five rows rather than
reasoning about them.** Both are the same mistake in different places: a DECLARATION was present in
the source and not read.

1. `collectLocalStrings` judged a local by its right-hand SIDE, so `val a: String = parts(0)` was
   not a string although the source says it is. It now takes a declared `String` type on its own,
   which is the discipline the neighbouring `stringParams` already used.
2. Top-level `val`s were never collected at all — that walker only descends a def BODY, so
   `val SEP: String = "-"` beside the defs was invisible. `collectModuleStrings` mirrors
   `collectModuleSignals`, which had solved the identical "declared beside the defs" problem.

**My earlier note in this entry said the second fix alone would leave row 2. That was wrong**, and
the emission table is why: with `SEP` recognised, row 2's `a + SEP` takes the `format!` arm through
its RIGHT operand. Both fixes are still right — each reads a declaration that was being ignored —
but the claim about which rows they cover was reasoning where measuring was available. All five rows
now emit `format!`, and the two that were broken are rows 2 and 3.

## v2-rust-backend-carries-the-same-silent-zero-and-nothing-runs-it — the twin of toint-on-a-non-integer-diverges, in a backend no suite exercises

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-toint-parity-gate.sh
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: 5b0b9a9bb1b864ed18cdf4e07a963a4bbd707d03 -->

> **FIXED 2026-08-15, and BOTH halves of this entry needed correcting.**
>
> **"Nothing runs this backend" is half wrong, and the half matters.** `v2/backend/check.sh` drives
> `v2/backend/rust/` on every fixture with the VM as oracle, through `run_rust` AND `run_wasm`. What
> is invoked by nothing is `check.sh` ITSELF (`orphaned-e2e-gates-52`). So the backend was not
> unexercised — but that harness compares STDOUT and treats a VM abort as "run-ir failed", so it
> **structurally cannot express "must abort"** and would never have caught this row however often it
> ran. That is why the check went to `tests/e2e/rust-toint-parity-gate.sh` instead: it reads the
> binary's exit code, already drives the v1 twin, and is wired in `ci.yml`. "Wiring this backend to
> something that runs" was therefore not the first task; asking the right harness was.
>
> **One defect was reported; three were there.** Asking the sibling methods the same question:
>
> | row | old v2 rust | run-ir |
> |---|---|---|
> | `"abc".toInt` | **0**, exit 0 | aborts — the reported one |
> | `"8".toDouble` | **0** | 8 — no String arm existed at all |
> | `" 8 ".toInt` | **0** | 8 — parsed without `.trim()`, the VM parses `s.trim` |
> | `"8".toFloat` · `7.toInt` | 8 · 7 | 8 · 7 — anti-rows, untouched |
>
> The two extra ones are worse than the reported defect: the input is VALID and the answer is
> silently wrong. The control prints all three at once — against the reverted generator the gate
> reads `got '8|0|0|8|7|0|' exit=0` against a wanted `8|8|8|8|7|` and a non-zero exit.
>
> **A correction I had to make to my own comment:** the first version said `.trim()` existed to stop
> the fix introducing a panic on `" 8 "`. The control showed `" 8 ".toInt` was already 0, so it fixes
> an existing divergence rather than avoiding a new one. The comment in the file now says that.
>
> **`toLong` was left untouched here, and that was WRONG — corrected 2026-08-15.** The note said
> `"8".toLong` answers `<closure>` on `run-ir`, so there was no oracle to move toward. The right
> question was not what the VM answers but why: it had no `(StrV, "toLong")` arm at all, so the
> `<closure>` was the symptom of a missing arm rather than a decision to defer to. Fixed in
> `tolong-on-a-string-answers-four-different-things`, which also found two more defects in the JVM
> generator by asking the same question of junk input.
>
> Semantics taken from the v1 twin's recorded decision (`run` is the oracle, the quiet lane moves,
> `toIntOption` is the total form); the panic message mirrors the v1 runtime's `ssc_parse_int`.
> `v2/backend/check.sh` after the change: ALL GREEN, 14 fixtures x 4 backends.

Found while fixing `toint-on-a-non-integer-diverges` in the v1 Rust backend, by grepping for the
defect's shape rather than for its file. `v2/backend/rust/RustBackend.scala` — the CoreIR → Rust
generator — emits its own runtime with the identical arms:

```
:1634   "toInt"   => V::Int(match recv { … V::Str(s) => s.parse::<i64>().unwrap_or(0), _ => 0 }),
:1636   "toFloat" => V::Float(match recv { … V::Str(s) => s.parse::<f64>().unwrap_or(0.0), _ => 0.0 }),
```

Same silent zero, same divergence from `run`, and the `_ => 0` arm answers 0 for a receiver of any
other type as well.

**NOTHING RUNS THIS BACKEND, and that is the more interesting half.** `grep` across
`scripts/smoke-ci.ssc`, `.github/workflows/ci.yml` and `tests/e2e/*.sh` finds no invocation; its own
header documents the usage as `scala-cli run v2/backend/rust/ -- <file>`. So no gate would have seen
this arm change behaviour, and none will see it if someone fixes it.

**Not fixed here on purpose.** A one-line change to an unexercised generator is unverifiable: there
is no lane to run the before/after on, and "it compiles" is not the property in question — the v1
twin was fixed against `run` as the oracle, with a gate that builds a binary and reads its exit
code. Wiring this backend to something that runs is the actual first task, and it is larger than the
arm.

## toint-on-a-non-integer-diverges — toInt on a non-integer aborts on run and silently yielded 0 on build-rust

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-toint-parity-gate.sh
     fixed-in: d50eb34e1
     reported-by: rozum
     reported-at: 2026-08-13
     ssc-version: 04b2f12fc
     repro: none
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-14. Everything in the reporter's section below is theirs, in their
words; the diagnosis and the split are mine and are marked as such.

`toInt` on a string that is not an integer does two different things, and the quiet one is the Rust
lane.

```scala
def isInt(s: String): Boolean =
  s.toInt.toString == s
def main(): Unit =
  println(isInt("8"))
  println(isInt("8.0"))
  println(isInt("abc"))
main()
```

- `ssc run` → `true`, then ABORTS: `ssc: String.toInt: invalid integer`
- `ssc-tools build-rust` → `true false false`

The Rust arm is `self.trim().parse::<i64>().unwrap_or(0)`, so a non-integer silently becomes 0.
Scala throws, and `run` matches Scala; the two lanes disagree on every non-integer input. Neither is
obviously the bug — that is your call — but the shapes are not equivalent and the difference is
invisible until the program moves lanes: a round-trip check (`s.toInt.toString == s`) is the natural
way to ask "is this an integer", and it WORKS on build-rust while it kills the program on run.
Reported because this is exactly the class that survives a test suite: same source, same inputs,
different answers, no diagnostic.

**Decided: `run` is the oracle and the quiet lane moves.** Scala throws, both run lanes throw, and a
fabricated 0 is a wrong answer that reaches production. `toIntOption` already exists on the run lane
as the total form.

**IT HAD TWO EMISSION PATHS, and the gate is what established that** — it went red on the first
attempt at this fix, which had only changed the runtime template:

| how the receiver reaches the walker | emitted before | now |
|---|---|---|
| type not statically known (a `def` parameter) | `_to_int(x)` → the template's `unwrap_or(0)` | panics |
| walker knows it is a String (a literal, a typed local) | `("abc".to_string().parse::<i64>().unwrap_or(0))` INLINE | `_to_int(&…)` |

Both spellings now route through the same helper, so there is ONE implementation of the semantics
instead of two that drifted. The gate carries a row for each, because a fix to either alone passes
half of them.

**A correction to my own first reading of this entry:** `RustCodeWalk.scala:4372-4373`
(`unwrap_or_default()`) is NOT this defect. Those two lines read a Signal's stored value and coerce
it by the signal's declared element type — an internal representation read, not a user-written
`toInt` — and they are deliberately left alone.

**The twin nobody reported is fixed with it:** `toDouble` sat one line below with the identical
`unwrap_or(0.0)`, and both run lanes throw for it too. Fixing the reported arm alone would have left
its neighbour.

## tolist-on-a-string-was-a-closure-on-the-run-lane — String had no `toList` arm, so the selection eta-expanded

<!-- status: fixed
     lane: native
     area: runtime
     kind: bug
     gate: tests/e2e/rust-toint-parity-gate.sh
     fixed-in: 82124f0a8
     reported-by: rozum
     reported-at: 2026-08-13
     ssc-version: 04b2f12fc
     repro: none
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-14, where it was part B of
`toint-on-a-char-and-tolist-on-a-string`. Part A — no `SscToInt` arm for a character on the Rust
lane — was fixed by the reporter themselves and is already on main as `2315f2ecf`; this is the half
they explicitly did not attempt ("I did not want to guess at interpreter internals").

The reporter's words:

```scala
def main(): Unit = println("ab".toList.length)
```

- `ssc run` → `<closure>`
- `ssc-tools build-rust` → `2`

Everything downstream then fails with `__method__: no dispatch for .map on <closure>`, which reads
like a user error in the lambda and is not one. Together with part A these made a program
unbuildable on BOTH lanes, and it is a program that is deployed: rozum's meeting PWA has not been
rebuildable from source since 2026-08-08.

**The cause is a missing arm, not the closure.** The v2 dispatcher has `toList` for a list, an
Option, a Set, a Map, a LazyList and an ArrayBuffer — and none for a String. With nothing matching,
`"ab".toList` fell into the `__method__` eta-expansion fallback and became the function value the
reporter saw. That fallback stopped being SILENT on 2026-08-14 (`2e7ad72dc`): the same program now
refuses loudly instead of printing `<closure>`. This entry is the missing feature behind that
refusal.

**Elements are `CharV`, not one-char strings, and the oracle is why.** `--v1` answers `List(a, b)`
and `"ab".toList.map(c => c.toInt).sum` = 195 = `'a' + 'b'`; `CharV extends IntV(c.toLong)`, so an
element renders as the character and converts to its code point, which is also what the Rust lane's
`SscToInt for char` arm answers. All three lanes now agree.

**Noticed and NOT changed:** the neighbouring `toCharArray` yields one-char STRINGS, whose `.toInt`
would throw rather than give a code point. That divergence is older than this fix and is not what
was reported; changing it here would have been an unrequested semantic change to a second method.

## rust-nested-typed-pattern-is-dropped — `case Some(s: String)` binds a raw Value, and the arm returns it where a String is declared

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/build-rust-refuses-loudly.sh
     found-by: claude-code
     found-at: 2026-08-14
     fixed-in: 7dcb3ba5e
     ssc-version: 79461dcfa
     repro: std/mcp/types.ssc requireString / requireInt
     confirmed: yes -->

**The last blocker under `std/mcp/types.ssc`, whose error count is now 6 and all six are this.**

    def requireString(args: Map[String, Any], key: String): String =
      args.get(key) match
        case Some(s: String) => s          // ← the ascription is dropped
        case Some(v)         => throw McpError(…)

`s` stays a `Value`, the arm returns it, and the declared return type is `String`: E0308. The
`Int` variant adds `_to_int(&n)` on a `&Value`, which no impl covers.

**The mechanism ALREADY EXISTS and the gap is POSITION.** `anyVariantTest(tpe, subj, ctx)` is
exactly the runtime test for `case x: T` against an `Any`, and its own comment records why it had
to exist: dropping the ascription made the first arm irrefutable, so `case l: List[Any]` matched a
Map and answered wrongly — "it compiled and ran, which is the worse failure". But it is applied
only at the TOP level of an `Any`-subject match. Nested inside an extractor — `Some(x: T)` — the
ascription is dropped by `renderPattern`, which turns a pattern into Rust pattern SYNTAX, and a
type test cannot be written there.

**Where the fix hooks, and it needs no signature change.** A Rust match guard can name the
bindings its pattern introduced, so `Some(s) if matches!(s, Value::Str(_))` is legal. A pure
`typedPatternGuards(pat, ctx): List[String]` can walk the pattern, collect `anyVariantTest` for
each nested `Pat.Typed`, and the arm builder at `RustCodeWalk.scala:~5175` — where `c.cond`
becomes the Rust guard — can AND them in. `renderPattern`'s 9 call sites stay untouched.

**THE RISK THAT STOPPED ME, and it is the reason this was a map before it was a patch.**
`matches!(s, Value::Str(_))` only compiles when the binder really IS a `Value`. For
`Map[String, Any]` it is; for `List[String]` the binder is a `String` and the same guard is a type
error. Telling those apart needs the element type, which the emitter does not track at this point.
Emitting the guard unconditionally would produce invalid Rust from valid source — the exact
failure this lane has been burned by three times this week, and the one its own comments keep
warning about. So the guard must be conditional on the binder being dynamic, and establishing that
is the actual work.

**FIXED — AND THE PLAN ABOVE IS WRONG ABOUT WHAT THE WORK WAS.** It concluded the emitter must
learn to tell a dynamic binder from a static one. It does not, and the way out was already written
down in this file's own comments. `coerceFromValue` documents the property that "removes the need
for type inference in the walker": `SscStr` is implemented for `Value` AND for `String`, so
`(x).ssc_str()` is correct whichever it gets. Applying that shape to the TEST rather than to the
coercion dissolves the question instead of answering it — six `ssc_is_*` traits, each with an impl
for `Value` (a real variant test) and one for the concrete type (statically true) — so the SAME
emitted guard is right either way and no discriminator is needed. **I had spent the previous
session looking for a way to establish a fact that did not need establishing.**

Both halves are emitted, and either alone is still broken: the GUARD (without it the first arm is
irrefutable) and the REBIND `let s = (s).ssc_str();` (without it the arm returns a `Value` where
the signature says `String`). Scope is nested ascriptions only — `nestedTypedBinders` starts at
depth 1 — so the top level keeps `renderPattern`'s drop and `renderAnyMatch`'s test untouched, and
an ascription with no total test keeps exactly today's emission rather than guessing.

Measured on both lanes from one tree, `pick(Map("k" -> …))` over String/Int/Double/Boolean:
identical, `hello 7 2 2.9 7 true`. **The discriminating row is the Double fed to the `Int`
extractor**: `2.9` must return `2` through the SECOND arm, which is what a dropped ascription gets
wrong — and it is wrong differently depending on which half is missing, silently with no rebind and
as a runtime panic with one. A probe that fed a String to the String arm would have passed in every
one of those states. Negative control: with the guards suppressed, the gate's own case fails.

**What it unblocks, in order:** these six errors, then the object-member qualification, then
`std/mcp/types.ssc` COMPILES, then `std/mcp/client.ssc`, then the Rust MCP client itself:
`Feature.McpClient`, nine intrinsic entries and a runtime mirroring `McpRs` over a spawned child's
stdio, with no new crate.

**THE ORDER ABOVE IS CORRECTED, and the correction matters more than the ordering.** An earlier
revision of this entry put types.ssc before the qualification and called the qualification "written
and verified, held". Measured today on the fixed build, `emit-rust std/mcp/types.ssc` still refuses
at `def text emits 2 times (overloading)` — the FLATTENING refusal, which fires before codegen. So
this fix is invisible to `tests/rust-std-survey-baseline.tsv`, whose row for types.ssc is unchanged
and correct at `REFUSED`; the gate case is the evidence here, not the survey. That is the
short-circuit property this file keeps re-learning: a refusal abandons the def at its first
unlowerable thing, so a fix behind one cannot be seen from outside.

And the qualification is NOT written: it lived only in a worktree that was removed without being
committed, and it is on no branch and in no commit. It has to be redone. Recorded plainly because
the previous sentence would otherwise have someone plan around code that does not exist.

## rust-qualified-enum-pattern-is-refused — `case Content.Text(t)` is unsupported while `Content.Text(s)` as a CONSTRUCTOR lowers

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/build-rust-refuses-loudly.sh
     found-by: claude-code
     found-at: 2026-08-15
     fixed-in: 5c715bfbb
     ssc-version: 28100232d
     repro: the two-line pair in the body
     confirmed: yes -->

**The twin of a defect already fixed, on the other side of the same feature.** `19ebadf00` made a
QUALIFIED enum constructor lower — `Content.Text(s)` emits `Content::Text{…}` rather than
`Content.Text(s)`. The PATTERN was not touched and is still refused outright:

    val c = Content.Text("x")          // lowers
    c match { case Content.Text(t) => … }
    // [error] Generic(def `main` has unsupported pattern: Pat.Extract (Content.Text(t)))

The unqualified `case Text(t)` works, so the gap is exactly the qualifier. A refusal rather than bad
code, which is the right direction — but it is a refusal for a form the constructor side accepts,
so a user who writes one naturally writes the other.

Found while gating the MCP client's `callTool`, whose `ToolResult.content` is a `List[Content]`;
the gate uses the unqualified form and says so.

**FIXED — AND IT WAS FIVE CELLS, NOT ONE.** The entry above names the qualified PATTERN. Enumerating
the matrix that the reported spelling belongs to — qualified vs bare, pattern vs constructor,
with-args vs field-less — found four more, of which `19ebadf00` had fixed exactly one:

    Shape.Circle(3)        constructor, qualified, with args    fixed 19ebadf00
    case Shape.Circle(r)   pattern,     qualified, with args    was refused
    case Shape.Dot         pattern,     qualified, field-less   was refused
    case Dot               pattern,     bare,      field-less   was refused
    Shape.Dot              constructor, qualified, field-less   emitted `Shape.Dot.clone()`
    Dot                    constructor, bare,      field-less   emitted `Dot` — E0425

**THE FIELD-LESS ROW HIDES FOR A STRUCTURAL REASON.** A variant with no fields has no argument list,
so it never reaches the extractor path at all: it arrives as a `Term.Select` or a bare `Term.Name`.
Different node, different code path, and in Rust a different syntax — `Shape::Dot`, not
`Shape::Dot {}`. The refusal message names the node kind, which is the tell.

**AND MY OWN PROBE MISSED ONE.** The probe covered five of the six; the GATE case, written to cover
the matrix rather than the probe, caught the sixth — bare `Dot` as a value — on its first run. That
is the argument for writing the gate from the matrix and not from the bug report.

Both pattern fixes DELEGATE to the bare spelling rather than formatting Rust here, for the reason
`19ebadf00` records: the unqualified path already knows tuple from struct variant, and duplicating
it produced `Content::Text(s)` for a struct variant, E0533. The two constructor fixes REPLACE an
existing expression rather than adding a match arm, because `renderTerm` is one of the five frozen
over-limit methods; v1-jit-size PASS confirms it did not grow.

Gate: `tests/e2e/build-rust-refuses-loudly.sh` — all six cells, differentially against the default
lane, `circle:3 / rect:2x5 / dot / circle:7 / dot`. Negative control: with the bare field-less
constructor suppressed the gate fails and names the case.

## rust-import-wrapper-depth-hides-an-owner — an imported module's objects sit shallower than the merged manifest describes, so they are not recognised as owners

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/build-rust-refuses-loudly.sh
     found-by: claude-code
     found-at: 2026-08-15
     fixed-in: 8b5a0336b
     ssc-version: febd37e88
     repro: std/agent-mcp.ssc
     confirmed: yes -->

**THE SAME IMPORT-DEPTH DEFECT AS `rust-any-valued-map-literal-not-lifted`'s neighbour, biting the
other way.** `collectObjectOwnership` decides which objects are USER objects by skipping as many
wrapper levels as the merged module's own `package:` has segments. An INLINED module brings its own
wrapper depth, and the two need not agree:

    std/agent-mcp.ssc     package: std.agent.mcp   → skip = 3
    std/mcp/types.ssc     package: std.mcp         → its blocks are wrapped 2 deep

So inside agent-mcp, `object Tool` sits at depth 2, `2 >= 3` is false, and it is NOT recognised as an
owner. `text` therefore reads as uncontested, is not qualified, and `object Tool: def text` collides
with `object Resource: def text` again — the very collision fixed in dc3cfeeef, back because the
OWNERS are invisible rather than because the fix regressed.

`std/agent-mcp.ssc` is the LAST module in the corpus still refusing on `overloading` — the count went
11 → 1 when given-instance members stopped being double-emitted, and this is the one.

**Earlier the same depth mismatch went the OTHER direction:** with `std/json.ssc` (`package:
std.json`) importing `std/json-core.ssc` (`package: std.json.core`), the inlined blocks were one
level DEEPER than the manifest described and `core` was mistaken FOR a user object, renaming defs
that nothing renamed at the call sites. That was worked around by qualifying only CONTESTED names,
which is correct but does not make the depth right — as this entry shows.

**Why it is not fixed here:** the AST carries no per-block provenance, so the wrapper depth of an
inlined block cannot be read off anything. A structural rule — count the leading pass-through
objects, each containing exactly one object and nothing else — identifies every wrapper except the
innermost, and cannot tell a genuine single-object file from a wrapped one. Doing it properly means
either recording the origin manifest on the block at inline time, or having the inliner strip its own
wrappers. Both are outside the Rust backend.

**FIXED, AND THE PARAGRAPH ABOVE ASKS THE WRONG QUESTION.** It assumes the depth has to be computed.
It does not have to be known at all. The skip existed to keep synthetic wrappers OUT of the owner
list back when EVERY object member was renamed; since only CONTESTED names are renamed, a wrapper
counted as an owner changes nothing for a name only it owns, and a name two owners DO share needs
qualifying whoever they are. **The heuristic was deleted, not repaired**, and both failure directions
went with it.

(One correction to it as well: the AST does carry a signal — a wrapped block is parsed from
`Input.VirtualFile("<package-wrap>", …)` while an ordinary one is parsed from a String, so
`pos.input` distinguishes them. It was not needed, but "no per-block provenance" was too strong.)

Measured: `overloading` refusals across the corpus **1 → 0** — this was the last one, and the whole
category of that false refusal is now gone (it stood at 11 before the given-member fix). No status
moved in the survey; three rows carry new reasons, agent-mcp's being the real blocker behind the
false one: `reads … on a List and the rust backend has no lowering for it`. std/json.ssc keeps
`jsonCoreParseArrayItems` unrenamed and std/ui/i18n.ssc and std/ui/state.ssc still emit clean — the
three modules the two earlier versions of this machinery broke, checked by name rather than assumed.

Gate: a TWO-FILE case — `package: pkg.lib` importing into `package: pkg.deeper.app`, two objects
sharing a member name. One file cannot express it: the defect IS the mismatch. Negative control:
re-imposing the manifest-derived skip brings back `E0425: cannot find value Tool`.

## setsid-is-not-on-macos-so-a-detached-gate-never-starts — and I read the empty log as "the gate died"

<!-- status: open
     lane: apparatus
     area: build
     kind: bug
     gate: none
     found-by: claude-code
     found-at: 2026-08-15
     ssc-version: febd37e88
     repro: `setsid nohup bash tests/e2e/rust-std-survey-gate.sh`
     confirmed: yes -->

**A CORRECTION TO A CLAIM ALREADY LANDED.** The release note for `rust-mcp-resources-prompts` and the
message of `58bb272f4` both say the std survey "could not be run — three attempts died after 1-3
minutes, the host is at load 22 with 86 worktrees". **That is wrong.** Two of the three attempts
never started: they were launched with `setsid`, which does not exist on macOS, and the log's single
line was

    (eval):1: command not found: setsid

which I read as "died" without reading it. Only ONE attempt actually started and stopped early.

**The survey runs fine.** Re-run with `nohup` alone and waited on the PROCESS rather than a timer:
45 minutes, REFUSED 78 / COMPILES 54, BADRUST not grown. The conclusion drawn from the broken
command — an environmental limitation of this host — was invented.

**What went wrong is not the missing binary.** It is that I asserted a limitation of the TOOLING from
a run whose output said, in plain text, what had actually happened. The same session had already
recorded twice that a green exit code is not evidence and that the output must be read; the third
time I broke my own rule, and it produced a false statement in a durable note. The note stands;
this entry is the correction, because a reader hitting a slow survey should not conclude from mine
that it is unrunnable here.

**Practical residue:** long gates on this host need `nohup` (not `setsid`) and a wait keyed on
`ps aux | grep` for the script, not a fixed sleep — the survey takes ~45 minutes under load, well
past any timeout I had been giving it.

## rust-anonymous-given-emits-one-name-for-all-of-them — two anonymous `given` instances are both `UnknownGiven`, which is E0428

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 830d836c6
     gate: tests/e2e/build-rust-refuses-loudly.sh
     found-by: claude-code
     found-at: 2026-08-15
     ssc-version: febd37e88
     repro: the four-line pair in the body
     confirmed: yes -->

An anonymous instance has no name for `givenStructName` to use, so every one of them emits as the
same struct:

    given Combiner[Int] with
      def combine(a: Int, b: Int): Int = a + b
    given Combiner[String] with
      def combine(a: String, b: String): String = a + b

    error[E0428]: the name `UnknownGiven` is defined multiple times

The NAMED form — `given intCombiner: Combiner[Int] with` — works, and both lanes agree on it. So the
gap is the anonymous spelling, which is the one `std/eq.ssc`, `std/hash.ssc`, `std/order.ssc`,
`std/show.ssc` and `std/semigroup-monoid.ssc` all use.

Found while writing a gate case for the given-member double emission: the first draft used the
anonymous form and went red on THIS instead, which would have made the case ambiguous between
causes. The case now uses named givens and says why.

## singleton-failover-test-imports-a-moved-path — three nodes that never started, read as flaky elections

<!-- status: fixed
     lane: apparatus
     kind: bug
     area: other
     gate: v1/tools/cli/src/test/scala/scalascript/cli/SingletonFailoverTest.scala
     reported-by: claude-code
     reported-at: 2026-08-26
     confirmed: yes
     fixed-in: a946e86b4 -->

**FIXED 2026-08-26 (`a946e86b4`).** The test writes a node script per cluster member and imports
the singleton module by a relative hop count:

```
[Singleton](../../../../runtime/std/cluster/singleton.ssc)
```

Both halves had rotted. `runtime/std/` became `std/` in `b433a41e4` (the v1→v2 migration), and the
scripts moved one directory deeper — `<repo>/v1/tools/cli/target/ssc-singleton-failover/` — so the
four hops were wrong as well. Every node process died before joining anything:

```
[ERROR] Import not found: ../../../../runtime/std/cluster/singleton.ssc
scalascript.interpreter.InterpretError: Import not found: …
```

**IT LOOKED LIKE CLUSTER FLAKINESS AND WAS NOT, and the transcript said so all along.** The
assertion reports `phase1` as `List(false, false, false)` — ALL three nodes, never a subset — and
the test's own retry harness prints `transient election-timing miss?` around it. Three attempts that
all report every node false are not a timing miss. The question mark in that message is the only
thing about it that was honest.

**NOT A REGRESSION FROM ANYTHING LANDED TODAY**, checked rather than assumed: a separate worktree at
`ccc95a5ff` — the commit the 08:13 nightly ran, before any of today's changes — fails identically,
three attempts, same all-false list. It became visible today because `sbt test shard 3/4` stopped
timing out at its 50-minute cap (`0efac4d61`) and the shards were re-enumerated.

`std/cluster/singleton.ssc` resolves against the std root from any directory — the form the other 22
examples in this repo use, and the one the comment on `pb.directory` in the same test already
assumed this line was using. Verified: three consecutive runs, `SENT1:true`, `SENT2:true`, 1/1 each.

**Its sibling was the same defect in a different file**: `examples/swift/appcore-nativeui.ssc`
imported `../../runtime/std/ui/primitives.ssc` and broke four `SwiftV2CliTest` rows (fixed
`ed3ef301c`). Two files were left behind by that migration; a grep for `runtime/std/` finds any third.

## mcp-v2-parked-elicit-answer-does-not-reach-the-handler-on-ci — the GATE raced the server, on both lanes

<!-- status: fixed
     lane: apparatus
     kind: bug
     area: other
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     reported-by: claude-code
     reported-at: 2026-08-26
     confirmed: yes
     fixed-in: e609d89a0 -->

**FIXED 2026-08-26 (`e609d89a0`), and this entry was WRONG in its title, its `lane`, its `area`
and its framing.** It is not a v2 defect, not a runtime defect, and not a CI-only one. The row's
driver wrote the elicit answer one second after `tools/call`, on a timer. When the server needed
longer than that to dispatch and put `elicitation/create` on the wire, the answer was read before any
request existed to match it, dropped, and the handler then waited out its whole 6-second budget.

**WHAT I GOT WRONG AND HOW.** I filed this as "green here, red on the runner" after ONE local pass of
the whole gate, and wrote "passes on this machine, whole-gate, repeatedly". Re-running it made it
fail here too — on `--v1`, the lane this entry claimed was unaffected. One pass of a row that fails
about half the time is not "repeatedly", and the lane it happened to pass on is not evidence about
the lane.

**THE MEASUREMENT THAT SETTLED IT**, and it is the one the entry's own step 1 asked for: withhold the
answer until `elicitation/create` actually appears in the transcript, and the row goes 3/3 on v2 and
green on v1, with no product change. Step 2 — raising the 6-second budget — would have been the
wrong fix and is now moot: the budget was never the constraint, the ordering was.

`elicit_answer_when_asked` replaces `elicit_driver`: it drives the server through a fifo and waits
for the request before answering, bounded at 20 s so a server that never asks still reaches the
assertion with a timed-out transcript instead of hanging. The row tests what it always meant to —
the answer arrives while the handler is parked — and now it does so by construction rather than by
hoping the timing holds. Verified: three consecutive whole-gate runs, all PASS.

**The last row of `v21-standard-mcp-smoke`, and it is the FIRST run in which anything reached it.**
The row above it — `elicit did not reach the wire on --v2` — had been failing since 2026-08-20, so
this one had never executed. Fixing that one (`22e27f1e3`) made it reachable and it went red on the
runner:

```
v21-standard-mcp-smoke: on --v2 a client answer sent while the handler was parked did
  not reach it — the serve loop stopped reading. Got:
{"jsonrpc":"2.0","method":"elicitation/create","params":{"message":"your name?",…},"id":1}
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":
  "srv.elicit: srv.request 'elicitation/create' timed out after 6000ms"}],"isError":true}}
```

**The registration works and the request reaches the wire** — `elicitation/create` is right there —
so this is downstream of everything the fix above was about. What fails is the READ side: the answer
the driver writes one second after the call does not reach the parked handler inside its 6-second
budget.

**IT PASSES ON THIS MACHINE, whole-gate, repeatedly**, which is the awkward part and why this is
filed rather than fixed. Both lanes run the identical program and driver; `--v1` passes on the runner
too. So the difference is not the program and not the protocol — it is scheduling, and a defect that
only appears under one is still a defect.

**WHAT TO MEASURE FIRST, in the order that costs least:**

1. Whether the answer is *buffered but unread* or *never written*. The driver sleeps 1 s after
   `tools/call`; on a runner the JVM may not have reached `serveMcp` before the driver finished, so
   every line sits in the pipe. A server that reads its whole input before dispatching would pass;
   one that parks mid-line might never come back for it. `strace`-free version: have the driver echo
   what it wrote to a side file and compare against what the server logged reading.
2. Whether 6000 ms is simply too small on a loaded runner — raise it to 30 s in a scratch copy and
   see if the row goes green. That decides "slow" vs "stuck" and nothing else does; the two look
   identical from the transcript.

**DO NOT raise the timeout in the gate as the fix** until step 2 says slow. The row exists to prove
the loop keeps reading while a handler is parked; a longer budget for a loop that never reads again
just moves the red later.

## mcp-v2-a-curried-plugin-native-yields-a-closure-instead-of-registering — `srv.tool(name)(handler)` registers nothing

<!-- status: fixed
     lane: v2-jvm
     kind: bug
     area: front
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     reported-by: claude-code
     reported-at: 2026-08-26
     confirmed: yes
     fixed-in: 22e27f1e3 -->

**FIXED 2026-08-26 (`22e27f1e3`) — and `area:` moved from `runtime` to `front`, because the subject was
neither the plugin nor the v2 lane.** F's `collectCurried` registers a name so `f(a)(b)` FLATTENS to
one call instead of nesting; it already skipped `extern`, and a member DECLARED in a trait is the
same thing without the keyword. `std/mcp/server.ssc` declares `def tool(name: String)(handler: …):
Unit` with no body, so every call site of that NAME flattened:

```
(prim __method__ (lit (str "tool")) (local 0) (lit (str "greet")) (lam 1 …))
```

One call carrying both clauses. The plugin's first clause took both arguments, returned the closure
that registers the tool, and nothing applied it — which is exactly the `<closure>` the section above
measured, and why nothing errored.

**THE TWO MEASUREMENTS THAT MOVED THE SUBJECT**, both cheap and both worth repeating before blaming a
runtime:

* splitting the call — `val f = srv.tool("greet"); f(handler)` — REGISTERS the tool, so the runtime
  applies that value correctly and the defect is in what the front emits;
* the reference front, which has no curried table, registers it correctly on the same program. One
  front, not the lane.

**A PROBE THAT LOOKED LIKE A REFUTATION WAS NOT ONE.** Replacing the handler with a string, to see
whether the first clause received two arguments, answered `tools:[]` under BOTH hypotheses — the v2
plugin's `tool` arm reads `first.lift(1)` without matching on the list's length, so registration
lives in the inner closure either way. The IR settled it; nothing observable from outside the plugin
could have.

**NOT the same root as `scljet-jdbc-v2-applies-a-foreign-value-as-a-function`**, which the section
above wondered about: that one is a case class colliding with its companion on the VM lane.

**The mirror defect is filed**: `v2/BUGS.md
reference-front-drops-a-curried-methods-second-clause` — the reference front gets a curried CASE-CLASS
method wrong where F gets it right. Found by running the same four programs on both fronts while
checking this fix did not take the neighbouring shapes with it.

**`sbt — compile and release gates` has been red in the nightly since 2026-08-20** on
`v21-standard-mcp-smoke: elicit did not reach the wire on --v2`. The elicit row is not the defect —
it is the first row that happens to use a tool. **No tool registers on the v2 lane at all.**

Six lines, and the two lanes disagree:

```scalascript
[mcpServer, serveMcp, Transport, Tool](std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.tool("greet")(args => Tool.text("hi")))
  serveMcp(Transport.Stdio)
```

`tools/list` on the same input:

| lane | answer |
|---|---|
| `--v1` (ssc-tools) | `{"tools":[{"name":"greet","inputSchema":{"type":"object"}}]}` |
| `--v2` (standard launcher) | `{"tools":[]}` |

and `tools/call` then answers `unknown tool: greet`.

**THE SERVER IS FINE AND THE REGISTRY IS EMPTY**, which is what makes this narrow. The protocol works
— `initialize` and `tools/list` both answer correctly on v2 — and `serveMcp` does NOT raise its
`no mcpServer { ... } configured first` error, so it found the builder that `mcpServer` put in
`Mcp.builderTL`. The setup callback runs on both lanes (a `println` inside it prints on both).

**WHAT THE APPLICATION ACTUALLY PRODUCES.** Bind the call and print it:

```scalascript
val z = srv.tool("greet")(args => Tool.text("hi"))
println("forced:" + z)      // v2 prints:  forced:<closure>
```

`McpServer.tool` is a curried plugin native — `tool(name)` returns a `PluginValue.nativeFn` that
takes `List(handler)` and calls `registerTool` (`McpIntrinsics.scala:495`). On v2 the second
application yields a **closure** instead of invoking that native, so `registerTool` never runs. The
declared return type is `Unit`; a `<closure>` is the tell that the application under-applied rather
than that the registration failed.

**SO THE SUBJECT IS THE LANE, NOT MCP.** Every `std/mcp` member with this shape is affected —
`tool`, `toolWithSchema`, `resource`, `resourceTemplate`, `prompt` are all declared
`def m(args…)(handler…)` — and the same is true of any other plugin native curried the same way.
The MCP smoke is simply where it shows first.

**NOT the Swift `__mk_method_obj__` gap** it was found beside (fixed 2026-08-25): that was a backend
refusing the primitive outright with a named error. This one runs, answers, and silently registers
nothing.

**Filed rather than fixed**: the repair is in how the v2 lane applies a second argument list to a
plugin native, and guessing at that from here would be a fix to the wrong site.

## mcp-v2-srv-prompt-missing — `prompt` is declared in std/mcp and the default lane had no such member

<!-- status: fixed
     lane: v2-jvm
     area: runtime
     kind: bug
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-16
     fixed-in: 48e4fe275
     ssc-version: 173d82a57
     repro: the driver in the body
     confirmed: yes -->

`std/mcp/server.ssc` DECLARES `srv.prompt(name[, description])(handler)`. The v2 native provider
implemented `tool` and `resource` and not `prompt`, so a program written against the declared
surface served prompts on the interpreter and died on `ssc run` — the DEFAULT lane — with:

```
ssc: __method__: no field 'prompt' on named-method-obj (None)
```

The provider does have prompt code: `prompts(...)` decoding a `prompts/list` REPLY. That is the
CLIENT half. Its presence is why a grep for "prompt" in the file reads as covered, and why this
survived a census that counted names rather than driving them.

Driver — `prompts/list` after `initialize`, same program both lanes:

```
{"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}
```

  * interpreter: `{"prompts":[{"name":"greet","description":"say hi"}]}`
  * v2 before:   `no field 'prompt'`
  * v2 after:    byte-identical to the interpreter, `prompts/get` included

FIXED by adding the member, curried and variadic-first exactly as `tool` is, registering through
`McpServerBuilder.prompt(name, desc, Nil, handler)` — `Nil` arguments matching v1, which also
registers with no argument list. Gated by the prompts/resource row in `v21-standard-mcp-smoke`;
control: disabling the arm reds it with the message above.

---

## mcp-v2-resource-body-is-show-output — `resources/read` answered with the rendered VALUE, not the body

<!-- status: fixed
     lane: v2-jvm
     area: runtime
     kind: bug
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-16
     fixed-in: 48e4fe275
     ssc-version: 173d82a57
     repro: the driver in the body
     confirmed: yes -->

v2's `srv.resource` never decoded its handler's result. It wrapped `Show.show(...)` of whatever the
handler returned, so a client reading a resource received the source-ish RENDERING of the
`ResourceResult` value as the resource body:

```
v2:          {"contents":[{"uri":"mem://a","text":"ResourceResult(\"mem://a\", List(Text(\"BODY-42\")))"}]}
interpreter: {"contents":[{"type":"text","text":"BODY-42"}]}
```

Note the shape differs too, not only the text: `{"uri","text"}` against `{"type","text"}`.

WHY NOTHING WAS RED. `resource` RESOLVED — the member existed and the call succeeded — and every
MCP case in the corpus asserts stdout or that a member resolves. Neither can see a member that
answers the wrong bytes. This is the second defect in two days whose whole disguise was that the
thing under it worked; a resolution check is not a behaviour check.

FIXED by a total `resourceHandlerResult(requested, value)`: decodes `ResourceResult(uri, contents)`
positionally through the existing `contentJson`, accepts a bare `String` as a text body, and falls
back to `Show.show` only for a shape that is neither. Gated by the same row, which compares the
BODIES both lanes put on the wire; control: making the `ResourceResult` arm unreachable restores
the rendering above and reds it, while leaving prompts correct — so the two assertions are
independent.

---

## interp-same-name-class-methods-collapse-to-the-last — a discarded DECLARATION reported at the call site

<!-- status: fixed
     lane: int
     area: runtime
     kind: bug
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/ClassMethodArityTest.scala
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-16
     fixed-in: bdd48657d
     repro: the body
     confirmed: yes -->

`StatRuntime` built the type-method table with a plain `.toMap`. Two definitions of one name
collapsed to the LAST; the other was dropped at REGISTRATION time and the loss surfaced as a
complaint about the CALL:

```
class Calc:
  def f(a: Int): Int = a * 10
  def f(a: Int, b: Int): Int = a + b

c.f(7)   ->  [line 6, col 27] missing argument for parameter 'b'
c.f(1,2) ->  3
```

FIXED by additionally registering each definition under `name#<arity>` for every argument count it
accepts — its required count (parameters minus those carrying defaults) through its parameter
count — and consulting those keys at resolution ONLY when the primary cannot accept the call's
count. The primary key is unchanged, `#` cannot occur in an identifier, and nothing is registered
unless a name is declared more than once, so a program without overloads gets a byte-identical
table.

FIVE RESOLUTION SITES, NOT FOUR. Three inline lookups, the parent-walking resolver, and — the one
the trait case in the test caught after the others were done — the INHERITED path in
`dispatchInstanceAfterMethods`, which is the only one a class taking all its methods from a parent
trait reaches.

---

## interp-curried-class-method-cannot-be-applied — `c.h(3)(4)` on `def h(a)(b)` asks for the second argument twice

<!-- status: fixed
     lane: int
     area: runtime
     kind: bug
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/ClassMethodArityTest.scala
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-18
     fixed-in: acbaf1c139079a75b5b829c388352bb332948e0f
     repro: the body
     confirmed: yes -->

```
class Calc:
  def h(a: Int)(b: Int): Int = a * b

c.h(3)(4)  ->  [line 5, col 26] missing argument for parameter 'b'
```

`StatRuntime` FLATTENS a method's parameter clauses (`paramClauseGroups.flatMap(_.paramClauses)`),
so `h` is registered as a two-parameter method and the first application is one argument short.

MEASURED AS PRE-EXISTING, not inherited from a neighbouring change: the same program fails
identically — same message, same position — with `interp-same-name-class-methods-collapse-to-the-last`
reverted. Recorded because that fix was landing in the same file and "it was already broken" is a
claim that has to be shown, not asserted.

FIXED — AND THE CAUSE ABOVE IS WRONG, which is the part worth keeping. The flattening is real, but it
is not the defect: a TOP-LEVEL `def top(a)(b)` is flattened by the same code and `top(3)(4)` answers
12. One program, both forms, measured together:

    top-level : 12
    method    : [ERROR] missing argument for parameter 'b'

So the difference was never in how the method is STORED — it was in how it is CALLED. `callFun`, the
top-level path, returns a PARTIAL CLOSURE when a call is one clause short. `callTypeMethod`, the
method path, had no such branch and fell through to `applyDefaults`, whose job is to fill defaults
and whose answer to a required parameter is that refusal. Two call paths, one of them missing a
branch the other has had all along.

THE FIX is that branch, on the method path. It captures `base` — the env with the instance's fields
already layered in — not `fn.closure`: a partial application that dropped the fields would fail on
the SECOND call rather than at the point of the mistake, which is worse, because by then the method
looks like it started working. Measured: `class Calc(base: Int) { def h(a)(b) = a * b + base }` with
`Calc(100).h(3)(4)` answers 112, so the field survived the split.

ONE GUARD MORE THAN `callFun` HAS, deliberately: a vararg method called with its varargs omitted
(`def f(a, xs: Int*)` as `f(1)`) is a COMPLETE call that packs an empty list, not a partial one.
`callFun` does not exclude that case. Copying the omission to be symmetrical would be copying a bug.

THE DEFAULTED TRAILING CLAUSE, which the first fix left failing and this entry then described as a
limit, is fixed too. `f(6)()` on `def f(a)(b = 5)` answered `Not callable: 30`: the flattening filled
`b` during the FIRST application, so the trailing `()` applied to a number. The rule is now the one
Scala uses — **one application consumes one clause** — and defaults belong to the clause being
APPLIED, never to a clause still ahead:

    def topD(a)(b = 5)          topD(6)()      -> 30      topD(6)(4)      -> 24
    def three(a)(b = 1)(c = 2)  three(9)()()   -> 12      three(9)(5)()   -> 16
    class C(k):  def m(a)(b = 5)   C(100).m(6)()  -> 130   C(100).m(6)(4) -> 124

WHAT MAKES THE TWO SHAPES SEPARABLE AT ALL: `def f(a)(b = 5)` and `def g(a, b = 5)` are stored
IDENTICALLY — params `[a, b]`, defaults `[None, Some(5)]` — and want opposite answers. `g(9)` is a
complete call that fills `b` (4); `f(6)` has a clause left. Nothing in the stored function could tell
them apart, so the clause boundaries are recorded at registration for defs with more than one clause,
keyed by the BODY rather than stored on the FunV — a non-constructor `var` is lost by `copy`, which
the section/import path applies to every bound function, the trap `Value.FunV.parameterless`
documents at length. The body Term is the same object across every copy.

THE ARRAY CARRIES THE TOTAL, NOT JUST THE STARTS, and the three-clause case is why. A partial closure
shares its body with the def it came from, so the recorded boundaries are in the ORIGINAL numbering;
the closure has to locate itself in them first (`total - paramsLeft`). Without that the SECOND
application of `three(9)()()` read the first clause's boundary and answered `Not callable: 12` — the
same defect one level deeper, which is how the first version of this fix was caught.

TWO BEHAVIOURS CHANGED, both deliberate, both measured:
  * `f(6)` with no trailing `()` now yields a function value where it used to yield 30. That is what
    Scala gives (the clause is not applied), and a census of 1650 `.ssc` files found ZERO defs with a
    fully-defaulted second clause, so nothing in the tree relied on the old answer.
  * A later clause's default expressions are no longer evaluated during an earlier application. A
    default is an expression; running it early runs its effects at the wrong time.

THE ANTI-ROWS matter more than the positive ones here. `plain(9)` and `C().g(9)` — single clause,
one default — must still answer 4, which a fix reading the defaults alone would turn into a closure.
And `needsOne()` on `def needsOne(a: Int)` must still REFUSE rather than become a closure: the empty
application was let into the partial path so a defaulted clause could be closed by `()`, and that is
gated on clause info existing, so an ordinary def is untouched.

The test that pinned the OLD agreement (`topD(6)` and `C().m(6)` both 30) was UPDATED, not deleted:
its subject is that the two call paths agree, and they still do — both now answer `<function(1)>`.
The answer moved; the property it guards did not.

Gate: five rows added to `ClassMethodArityTest` — one clause at a time, fields preserved, three
clauses, the default-clause agreement, and an anti-row asserting an ordinary two-parameter method
still returns a number rather than a closure, which a branch that partially applied everything would
break.

---

## v2-only-the-last-same-name-class-method-is-registered — an overload is unreachable, not merely unpicked

<!-- status: fixed
     lane: v2-jvm
     area: front
     kind: bug
     gate: tests/e2e/v2-unknown-member-refuses-gate.sh
     found-by: claude-code
     found-at: 2026-08-18
     fixed-at: 2026-08-18
     fixed-in: 7fcdc0dfc28d5e91622b5450dd3e26b30f23fd8c
     repro: the reversed pair in the body
     confirmed: yes -->

Two methods with one name and different parameter counts: v2 keeps ONE. The front emits a single
`__regmethod__` per name, so the second registration is all that exists at dispatch, and the other
overload cannot be reached by any call.

    class Calc:
      def f(a: Int): Int        = a * 10
      def f(a: Int, b: Int): Int = a + b
    c.f(1, 2)  ->  3                                      c.f(7)  ->  refuses
                                                          v1:         70

REVERSED, which is the measurement that makes this a registration fact rather than a dispatch
preference — declare the two-argument one FIRST:

    class Calc:
      def f(a: Int, b: Int): Int = a + b
      def f(a: Int): Int         = a * 10
    c.f(7)     ->  70                                     c.f(1, 2)  ->  refuses

Whichever is written last is the one that works. Nothing chooses between them; there is only ever
one to choose.

WHY IT READS AS NEW: until 2026-08-18 this was invisible, because a call that missed the surviving
overload did not refuse — it answered, wrongly, with the receiver shifted into the first parameter
(`v2-overloaded-class-method-returns-a-wrong-value-instead-of-refusing`, `Calc7` for `c.f(7)`). With
that fixed, the same programs now say `Calc.f: expected 2 argument(s), got 1`, and what remains is
this: a refusal where the interpreter answers.

An arity-qualified dispatch key was written and then deleted rather than shipped, because it cannot
fire while only one registration exists per name — it would have read as overload support that is
present. The fix belongs where the methods are CAPTURED: emit one `__regmethod__` per declaration
and key the registry by `(tag, name, arity)`, at which point the dispatch key becomes worth adding
back. That is the v2 twin of `interp-same-name-class-methods-collapse-to-the-last`, whose fix on the
interpreter took exactly this shape (arity keys plus a picker at each resolution site).

HOW MUCH THIS BLOCKS TODAY, counted before anyone spends a day on the front: **zero**. Across 1381
`.ssc` files in the repo — corpus, std, examples, tests, specs — not one class or case-class body
declares the same method name twice. Nothing in the tree is waiting on this.

THE COUNT CANNOT BE READ AS "NOBODY WANTS IT", and saying so is the whole point of writing it down.
Until 2026-08-16 same-name methods were broken on the INTERPRETER too
(`interp-same-name-class-methods-collapse-to-the-last`, fixed in `bdd48657d`), so every one of those
1381 files was written while the feature worked on no lane at all. A population that could never have
used it is not evidence of demand either way. What the count does establish is the risk side: fixing
this breaks nothing that exists.

WHAT IT WOULD COST, so the trade is visible rather than guessed. The capture is in the SELF-HOSTED
front — `caseMethodDefsFor` in `ssc1-lower.ssc0` emits one global per method named `Tag_method`, so
two overloads collide on that name before the registry is ever consulted. Making them distinct means
naming the global by arity, emitting one `__regmethod__` per declaration, keying the registry by
`(tag, name, arity)`, and restoring the dispatch key that was deleted here. That file compiles every
native program, and the last change in this area (the case-class body-method work, `2df8f6e3c`)
needed a layout-pass fix and full v2 conformance verification before it was trusted. The LEGACY front
has its own path and shows the same last-wins behaviour, so this is a two-front change
(`two-front-bug-pairs`), not one.

VERDICT: real divergence from both v1 and Scala, no occupant, delicate fix. Kept open and NOT
scheduled — worth doing for parity, not worth doing ahead of anything with a user behind it. Whoever
takes it starts from the reversed-declaration pair above; it is the whole test.

FIXED, and the verdict above was wrong about the cost — which is worth more than the fix. "Delicate"
came from the wrong end of the problem: the earlier work in this area needed a LAYOUT change, and I
carried that difficulty over to a change that turned out to be additive and local. Sizing a job by
the reputation of the file it lives in is not sizing it.

WHAT MADE IT SMALL: nothing had to be renamed. Each method now emits a SECOND global
`Tag_m_<arity>` beside the existing `Tag_m`, and a SECOND `__regmethod__` under the key
`m#<arity>` beside the bare one. Dispatch tries the arity key first and falls back to the name. The
bare global and its registration keep meaning exactly what they meant, so `caseSiblingGlobal` — the
third consumer of that mangled name, and the one that does NOT know a call's argument count — needed
no change at all. A rename would have had to thread the count through it; adding a name did not.

The runtime half was the code deleted when this entry was filed, restored unchanged. It could not
fire then because both registrations pointed at one global, so the runtime was handed the same
closure twice. Now they point at different globals and the same code works.

TWO FRONTS, because the collision was written twice: `caseMethodDefsFor` / `caseMethodRegsFor` in
`v2/lib/ssc1-lower.ssc0` (reference) and `ccMDefBody` / `regMethod1` in `specs/v2.2-p6.5-fsub.ssc`
(F). Fixing only the reference front made plain classes work and left case classes refusing, because
case-class bodies go through F — a two-front pair caught by measuring each front separately rather
than by trusting one green run.

MEASURED, both fronts and both declaration orders, against the interpreter:

    class/case class with def f(a) and def f(a, b)
      f(7)     -> 70     f(1, 2) -> 3      legacy, F, and v1 identical
    the same pair DECLARED IN THE OTHER ORDER
      f(7)     -> 70     f(1, 2) -> 3      identical again

The second line is the one that matters: before this, whichever overload was written LAST was the
only one that worked, so a single-order test passes on the broken build.

WHAT STILL DOES NOT RESOLVE AN OVERLOAD, stated because the gate deliberately does not assert it: a
SIBLING call — `g(3)` from inside another method of the same class — goes through the bare mangled
global and refuses when that global is the other overload (`arity: 3 expected, 2 given`). The
INTERPRETER refuses there too (`missing argument for parameter 'b'`), so the lanes agree and this is
a shared limit, not a v2 regression. Improving it means teaching `caseSiblingGlobal` the call's
argument count; freezing it in a test first would make that harder.

Gate: `tests/e2e/v2-unknown-member-refuses-gate.sh`, one row carrying BOTH declaration orders in one
program, beside the three refusal rows that guard the opposite direction.

---

## v2-overloaded-class-method-returns-a-wrong-value-instead-of-refusing — `Calc7` where `70` was asked for

<!-- status: fixed
     lane: v2-jvm
     area: runtime
     kind: bug
     gate: tests/e2e/v2-unknown-member-refuses-gate.sh
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-18
     fixed-in: e3f58daa8ee2a3daa6d762efbd1dd626d1253bba
     repro: the body
     confirmed: yes -->

The same overload program the interpreter answered with an error is answered by the standard
launcher with a WRONG VALUE and no diagnostic:

```
class Calc:
  def f(a: Int): Int = a * 10
  def f(a: Int, b: Int): Int = a + b

println("two-arg: " + c.f(1, 2).toString)   ->  two-arg: 3        (correct)
println("one-arg: " + c.f(7).toString)      ->  one-arg: Calc7    (expected 70)
```

Worse than the interpreter's failure, which at least named a parameter. Filed rather than fixed:
the interpreter half is `interp-same-name-class-methods-collapse-to-the-last`, this is a different
lane and belongs in its own claim. The two were found by one program run on both lanes — a habit
worth keeping, since the interpreter's version was loud and this one is silent.

RE-MEASURED 2026-08-17 ON A FRESH BUILD, and OVERLOADING TURNS OUT TO BE A SYMPTOM, not the defect.
The rule is simpler and much wider: **a class-method call missing exactly one argument silently binds
the RECEIVER as the first parameter and shifts the rest.** No overload is needed to trigger it.

    class D:
      def h(a: Int, b: Int): String = "H(" + a.toString + "," + b.toString + ")"
    d.h(7)        ->  v2: H(D,7)        v1: [ERROR] missing argument for parameter 'b'

    class R:
      def k(a: Int): String                 r.k()      ->  K(R)
      def t(a: Int, b: Int, c: Int): String r.t(1, 2)  ->  T(R,1,2)

That last pair is the confirmation of the rule rather than a second example: it was PREDICTED from
the one-argument case and then measured. `r.k()` — a no-argument call on a one-parameter method —
answering `K(R)` is the shape that will reach a user first.

THE ORIGINAL `Calc7` IS THIS: `c.f(7)` ran the TWO-argument overload with `a` = the receiver and
`b` = 7, so `a + b` concatenated `Calc` with `7`. Made visible by giving the two overloads different
bodies instead of arithmetic — `TWO(C,7)` where the entry first read only a wrong number.

AND THE CASE-CLASS HALF FAILS DIFFERENTLY, which is why the fix has to look at both:

    receiver      args       v2                                   v1 (the oracle)
    plain class   exact      P(1,2)                               same
    plain class   one short  P(P,1)          silent wrong value   refuses, NAMES the parameter
    case class    exact      Q(1,2)          same
    case class    one short  Index -1 out of bounds for length 2   refuses, NAMES the parameter
                                             internal, no source location, exit 1

ONE MECHANISM RULED OUT BY MEASUREMENT rather than by reading: it is NOT the extension-method
fallback (`__methodOrExt__` calling a global with the receiver prepended). With a real global
`def h(x: P, y: Int)` in scope alongside the class, the short call still runs the MEMBER body —
`MEMBER(P,1)`, not `GLOBAL(...)`. So the member's own closure is being invoked with the receiver
prepended, and the argument count then "fits" by accident.

Kept open with a wider title in mind: the entry's own name says `overloaded`, and the next reader
should not conclude that removing an overload avoids it.

FIXED — the SILENT WRONG ANSWER. `__regmethod__` now checks the arity it was always assumed to have.
The mechanism, read rather than guessed after the probes narrowed it: a body method is registered as
a tagged method and invoked with `self :: args`; `callClos` extends the closure's environment with
however many values it is handed, and the body reads its parameters by POSITION. Nothing anywhere
compared the two counts, so one value too few did not fail — it SHIFTED, and `self` became the first
parameter. Every case in the matrix above now refuses by name:

    D.h: expected 2 argument(s), got 1
    R.k: expected 1 argument(s), got 0
    T.t: expected 3 argument(s), got 2

Counts are reported as the source is written: `self` is a calling convention, and naming it would
send someone hunting a parameter their program does not have.

ONE FIX COVERED BOTH HALVES, which was not a given — the case-class half failed differently
(`Index -1 out of bounds for length 2`, internal, no source location) and could have needed its own
repair. Measured after: the same message on both fronts, legacy and F.

STILL OPEN, AND NOW LOUD INSTEAD OF WRONG: v2 cannot dispatch two same-named methods that differ in
parameter count, because only ONE `__regmethod__` arrives per name — the LAST declaration. So
`c.f(7)` against `def f(a)` + `def f(a, b)` refuses rather than answering 70 as the interpreter does.
Proven by reversing the declaration order: with `def f(a, b)` first, `c.f(7)` answers 70 and
`c.f(1, 2)` refuses — the exact mirror. This is the v2 twin of
`interp-same-name-class-methods-collapse-to-the-last`, and the fix has to start where the methods are
CAPTURED (the front), not at dispatch.

An arity-qualified registration key was written first and then DELETED, because it could never fire:
with one registration per name there is no second entry for it to find. A key that cannot be hit
reads as overload support that exists. The check stayed; the key went.

Gate: `tests/e2e/v2-unknown-member-refuses-gate.sh` — three refusal rows including the nullary call
(`r.k()`, the one a user meets first), and an anti-row asserting right-arity calls still answer,
because refusing every class-method call would satisfy all three.

---

## interp-declaring-a-plain-extern-class-member-breaks-it — a member works UNDECLARED and dies the moment you declare it

<!-- status: fixed
     lane: int
     area: runtime
     kind: bug
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-16
     fixed-in: bdd48657d
     repro: the two-sided control in the body
     confirmed: yes -->

Declaring a member in an `extern class` breaks it on the interpreter — the opposite direction from
every "declared but unimplemented" defect on this board.

TWO-SIDED CONTROL, same five members, same program, same lane, one variable:

```
UNDECLARED in std/mcp/server.ssc          DECLARED in std/mcp/server.ssc
  notifications/tools/list_changed          [line 72, col 44] Undefined: __extern__
  notifications/resources/list_changed      [line 73, col 48] Undefined: __extern__
  notifications/prompts/list_changed        [line 74, col 46] Undefined: __extern__
  notifications/resources/updated           [line 76, col 53] Undefined: __extern__
  level=info  (from currentLogLevel)        [line 87, col 39] Undefined: __extern__
```

THE PARTITION NAMES THE SHAPE, and it is why this was not obvious. Declaring these is fine:

  * `tool(name)(handler)`, `resource(uri)(handler)`, `prompt(name)(handler)` — CURRIED
  * `elicit(msg, schema, timeoutMs = 0)`, `notifyProgress(p, total = 0)`,
    `log(level, data, logger = "")`, `notify(method, params = Map())` — carry a DEFAULT
  * `onConnected(handler: () => Unit)` — FUNCTION-typed parameter

and declaring these breaks them:

  * `notifyToolsListChanged()`, `notifyResourcesListChanged()`, `notifyPromptsListChanged()`,
    `currentLogLevel(): String` — niladic
  * `notifyResourceUpdate(uri: String): Unit` — one plain scalar parameter

So: a declared extern-class member whose parameter list is empty or all plain scalars, with no
default, is served by the `__extern__` stub instead of the plugin binding. One with a default, a
function parameter, or a second parameter list is not. `Parser.preprocessExtern` rewrites EVERY
extern-class member body to `__extern__` alike, so the divergence is downstream of the rewrite, in
how a call on the plugin object chooses between the declaration and the plugin's own member.
`StatRuntime.scala:276` skips extern defs at STATEMENT level and is the only `isExternDef` consumer
on this lane — there is no equivalent for class members, which is the place to look.

HOW IT WAS FOUND, because the first reading was wrong: five members failed at once and I read it as
five separate defects. Two of them — `notifyProgress` and `log` — were a DIFFERENT bug: I had
declared them as two arities each, the interpreter collapsed same-name non-curried declarations to
the LAST one (`missing argument for parameter 'total'` on a one-argument call), and collapsing each
to a single signature with a default fixed those two and left the other five. A bisect over one
member per program, re-run after each fix, is what separated them.

CONSEQUENCE TODAY: five members of `std.mcp.McpServer` are implemented on every lane, reachable
from `.ssc`, and deliberately NOT declared, with the reason written where the declarations would
go. Adding a throwaway default parameter would dodge it and put a lie in the public signature.

FIXED — `StatRuntime` no longer registers extern members as class or trait methods, so the plugin's
binding is what a call reaches, declared or not. The skip is the one the `Defn.Def` arm above
already performed for extern defs at statement level. `Defn.Object` needed no change: it routes
members through `execStat`, which already had it. Verified by re-declaring all five members and
re-running the matrix — all eight now answer, and the MCP gate drives them end-to-end on both lanes.


---

## docs-readme-links-107-of-143-point-at-files-that-are-not-there

<!-- status: fixed
     lane: n/a
     area: docs
     kind: bug
     gate: tests/e2e/docs-links-resolve.sh
     fixed-in: f076a38a2
     found-by: claude-code
     found-at: 2026-08-17
     repro: the census in the body
     confirmed: yes -->

`docs/README.md` is the index for the whole documentation set. **107 of its 143 links resolve to
nothing.** Each names a file that exists — under `specs/` — while the link is written relative to
`docs/`:

```
docs/dsl.md               NO        specs/dsl.md               yes
docs/streams.md           NO        specs/streams.md           yes
docs/mcp-x402-wallet.md   NO        specs/mcp-x402-wallet.md   yes
```

ONE CAUSE, 107 INSTANCES. Every one of them is `X.md` where `../specs/X.md` was meant; NONE of the
143 is broken in any other way (the census checked: zero links point at a file that exists nowhere).
That uniformity is what makes it a defect rather than 107 typos, and it is also why the fix is
mechanical.

HOW IT WAS FOUND, and the part worth keeping: I set out to fix TWO links — the MCP ones my own work
had a stake in. `docs/mcp.md` did not exist (now written) and `mcp-x402-wallet.md` pointed nowhere.
Rather than fix the second one and move on, I counted the population. Fixing the two I cared about
and reporting "the README links are fixed" would have been true about my two and false about the
index.

The one link this session touches is the x402 one, repaired in place because it sits inside a claim
that already owns `docs/README.md`. The other 106 are left for a claim that can also add the thing
whose absence let this happen: **there is no gate that resolves the links in `docs/`.** A dozen
lines of `awk` plus `test -f` would have caught all 107 the day the first one broke.

### FIXED 2026-08-18 — and the population was two and a half times what this entry counted

Re-measured before fixing, which is the only reason the number here is right: the index was **108 of
143**, not 107 — one more link of the same shape had been added since. And the census the entry did
not do, over EVERY `.md` under `docs/`, found the same defect everywhere:

| | |
|---|---|
| file-looking relative links under `docs/` | 470 |
| broken | 195 |
| resolve under `specs/` (the reported cause) | 88 + the index's 108 |
| resolve at the REPO ROOT (`std/ui/content.ssc`, `../` missing) | 92 |
| resolve nowhere | 15 |

So "one cause, 107 instances" was one cause, 288 instances, and a SECOND cause of the same kind
underneath it. All 288 are rewritten mechanically; the fifteen are the interesting part.

**Six of the fifteen must stay broken, and a gate that did not know that would demand they be
"fixed".** `[names](./geometry.ssc)` in the user guide's import section is ScalaScript's OWN IMPORT
SYNTAX being demonstrated — the markdown link IS the example. Making those files exist would be
inventing sources to satisfy a scanner. Six more resolve once the moved directories are followed
(`frontend-examples/` -> `frontend/examples/`, `frontend-toolkit/` -> `frontend/toolkit/`,
`secret-resolvers.md` -> `specs/`) and are repaired. Three name files that were DELETED —
`ToolkitDemo.scala` and its cross-backend test — and are NOT repointed, because choosing among
CounterDemo/ShowHideDemo/TodoListDemo would be a guess about what the tutorial section now means.
That section needs a rewrite by someone who knows the demos.

**Gate**: `tests/e2e/docs-links-resolve.sh`, offline, sub-second. It checks only links that LOOK
like files — an extension — so `[List](std/collections)` and `[a form](toolkit:textField?signal=…)`,
which are markdown syntax used for a module name and a URI, are left alone rather than made into a
reason to rewrite prose. The nine survivors sit in a FROZEN list with one reason each, ratcheted the
way `no-orphan-gates` ratchets: a new broken link fails, an entry that starts resolving fails, and
an entry whose link is gone from the docs fails. Both directions have negative controls.

---

## mcp-v2-http-transport-has-no-sse — no server-to-client channel, so notify and the blocking asks stay one-way

<!-- status: fixed
     lane: v2-jvm
     area: runtime
     kind: feature
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     found-by: claude-code
     found-at: 2026-08-17
     fixed-at: 2026-08-17
     fixed-in: 5b80227fafe4509d15c1d4a49e946210533e30ed
     repro: the body
     confirmed: yes -->

`Transport.Http` on the native provider answers one JSON-RPC frame per POST. `Accept:
text/event-stream` gets that same single reply — there is no held-open stream.

CONSEQUENCE, stated because the transport landing makes it easy to assume otherwise: nothing the
SERVER initiates reaches an HTTP client. `srv.notify(...)` and the list-changed notifications go
nowhere, and `elicit` / `listRoots` / `request` still cannot receive an answer — over HTTP they fail
for a different reason than over stdio (no channel, rather than the deadlock in
`mcp-elicit-deadlocks-the-serve-loop`) but they fail either way.

v1's route HAS this: `McpIntrinsics` checks `Accept` for `text/event-stream`, returns a
`StreamResponse`, and wires `builder.addSubscriber` / `McpServerCore.openSubscription` to the
stream. So the server-side machinery exists in mcp-common; what the native provider lacks is the
streaming response, which `com.sun.net.httpserver` can do (`sendResponseHeaders(200, 0)` then keep
writing) but which needs the subscriber lifetime handled — a client that disappears is a
cancellation, not an error to swallow, and v1's route comments say so at length.

Deliberately left out of the transport claim rather than half-built: a stream whose teardown is
wrong is worse than no stream.

FIXED. `Accept: text/event-stream` now takes a different branch: `sendResponseHeaders(200, 0)`,
`builder.addSubscriber` for the lifetime of that one POST, every frame written as `data: ...`, and
the handler's own result written last. The subscriber is UNFILTERED because `McpServerCore.request`
refuses unless one exists with an empty filter — a filtered listen stream would have carried the
notifications and still left the asks unable to ask.

The claim to test was never "a stream appears". It was that `elicit` COMPLETES over HTTP, and the
gate row asserts it end to end: the tool parks inside `srv.elicit`, `elicitation/create` is read off
the open stream, the answer is posted on a SECOND connection, and the parked tool returns
`action=accept content=true`. A buffered-until-close implementation cannot produce that line — the
second POST would arrive too late and the row would see a timeout. The inbound half needed no work:
`handleHttpRequest` already routed a client Response into the pending map and answered 204. What was
missing was only the outbound channel.

WHY IT WORKS HERE AND DEADLOCKS OVER STDIO (`mcp-elicit-deadlocks-the-serve-loop`): each POST runs on
its own thread, so the loop that would deliver the reply is not the loop the call is blocking. The
control in the gate is the same server and the same tool with the `Accept` header removed: no stream,
no subscriber, and the call refuses with `no active client subscribers` — its own message, not the
timeout the other half would give, so the row cannot pass by having both halves break alike.

ON THE TEARDOWN THIS ENTRY DEMANDED. The subscriber is removed in a `finally`, so its lifetime is one
request and a dead client cannot accumulate in the broadcast set. A client that disappears while a
handler is parked is still not noticed PROMPTLY — `addSubscriber` swallows writer-side exceptions by
design so one dead peer cannot silence the others — but the wait is bounded rather than indefinite:
`request` always parks on `poll(timeoutMs, ...)`, where 0 returns at once instead of blocking
forever. Making the death observable needs a heartbeat frame, which is a separate change and is
recorded as such rather than half-done here.

TWO MEASUREMENT TRAPS, both mine, both recorded because each one imitated a different verdict.

`curl` without `-N` buffers, so the first probes read an empty file, failed to extract the request
id, and reported a timeout that looked exactly like the defect still being present. The gate reads
the id from the growing file with `-N` for that reason.

Then the row itself killed the gate SILENTLY: waiting for a frame means `grep` finds nothing on the
early passes, and under `set -euo pipefail` a bare `x=$(grep ... | head -1)` exits 1 on no-match and
takes the whole script down. Redirecting stderr hides the message, not the status. Two full runs
reported "rc=1, no output" — which reads like an early crash, not like a check that never got to
speak. Every command substitution in that row now ends in `|| true`.

A THIRD THING WAS GREEN BY ACCIDENT, and is worth more than the two above. The doc-example row picks
the block to run, and its key was "the first block with `def main` and `serveMcp(`". The SSE example
added to `docs/mcp.md` has BOTH, sits above the complete server, and never terminates — so that row
should have started running a server that parks forever. It did not, only because the new block
happened to be fenced ```ssc while the selector matches ```scalascript. Passing on a fence spelling
is not passing. The selector now reads an explicit `gate:runnable-example` marker in the page, so
the page can carry any number of illustrative programs; a missing marker is its own refusal, and a
marker moved onto the wrong block is caught by the surface checks, which stayed independent of it.

---

## mcp-v2-auth-cannot-be-ported-until-v2-serves-http — the members would set state nothing reads

<!-- status: fixed
     lane: v2-jvm
     area: runtime
     kind: feature
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-17
     fixed-in: d7212b37e
     repro: read the two call sites named below
     confirmed: yes -->

The last seven `srv` members missing from v2 are the auth group — `authEnabled`, `currentAuth`,
`setAuthRealm`, `setProtectedResourceMetadata`, `setTokenValidator`, `useHmacValidator`,
`useAuthServer`. They are NOT a porting task, and the difference matters because porting them is
the obvious thing to do and it would produce seven members that do nothing.

TWO INDEPENDENT BLOCKERS, both read off the code rather than inferred:

1. **AUTH IS ENFORCED ON THE HTTP ROUTE ONLY.** The single place the registered validator runs is
   `McpServerCore.authorizeHttp`, called from `handleHttpRequest`. The stdio loop —
   `McpServerCore.serve`, the one v2 uses — never consults `tokenValidator`.

2. **v2 SERVES STDIO AND NOTHING ELSE.** `McpNativePlugin`'s `serveMcp` handles `Transport.Stdio`
   and refuses everything else by name: *"the native provider supports Transport.Stdio, not
   'Http'"*. So on v2 there is no route on which auth could take effect.

Implemented today, all six setters would resolve, accept their arguments, mutate builder state, and
have ZERO observable effect on any transport v2 can serve — and no gate could be written for them,
because there is no wire to read. That is the exact failure shape three defects in this file were
just fixed for (`mcp-v2-resource-body-is-show-output`, and the census lesson in BACKLOG): a member
that RESOLVES is not a member that WORKS.

`useAuthServer` carries a SECOND, independent blocker: it resolves its argument through
`OAuthBridge.authServers`, a registry the v1 oauth-plugin owns. `v2/runtime/providers/` contains
graph-rdf4j, mcp, nfc, pdf and swift — there is no oauth plugin on that lane at all.

SO THE ORDER IS: v2 gains an HTTP transport first; the six validator/metadata members follow and
are gateable the day it exists; `useAuthServer` additionally waits on a v2 oauth plugin. Recorded
as a FEATURE gap rather than a bug: nothing is broken, and the six members are absent for a reason that
would not be improved by adding them.


FIXED — the order the entry named turned out to be the right one, and both halves landed together
because neither can be shown to work alone. `Transport.Http` now serves on the native provider, and
six of the seven members came with it: `setTokenValidator`, `useHmacValidator`, `setAuthRealm`,
`setProtectedResourceMetadata`, `authEnabled`, `currentAuth`. Measured against one running server —
no token 401 with `WWW-Authenticate`, a rejected token 401, an accepted token 200 and the tool's
answer.

`useAuthServer` is STILL absent, for the second blocker this entry named and which has not moved:
it resolves through `OAuthBridge.authServers`, and `v2/runtime/providers/` has no oauth plugin.

---

## mcp-elicit-deadlocks-the-serve-loop — the answer can only arrive through the loop `elicit` blocks

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/v21-standard-mcp-smoke.sh
     found-by: claude-code
     found-at: 2026-08-16
     fixed-at: 2026-08-17
     fixed-in: aae4ef34ccc90fe8941aab5de8327df150767e03
     ssc-version: c5615f9ca
     repro: the driver in the body
     confirmed: yes -->

`srv.elicit(...)` sends `elicitation/create` and then BLOCKS the calling handler waiting for the
answer. The serve loop is single-threaded: it reads a line, dispatches it, and only then reads the
next one. So while the handler waits, nothing is reading stdin — and the answer it waits for can
only arrive on stdin.

    srv.tool("ask")(args =>
      val r = srv.elicit("name?", Map("type" -> "object"))
      Tool.text("action=" + r.action))

    → {"jsonrpc":"2.0","method":"elicitation/create",…,"id":1}      the request DOES go out
    → 60 s later: {"content":[{"text":"srv.elicit: … timed out after 60000ms"}],"isError":true}

**ON BOTH LANES, identically** — the same program, the same driver, the same message on `ssc run`
and on `ssc run --v2`. This is not a v2 gap.

**IT IS THE LOOP, NOT THE DRIVER.** A client that answers the elicitation gets nothing; and a client
that instead sends an ordinary `tools/list` while the handler is blocked gets NO reply for the full
60 s, then only the `tools/call` answer. The server is deaf for the whole window, which is the
discriminator: a driver bug cannot make the server stop answering unrelated requests.

**THE MACHINERY TO FIX IT ALREADY EXISTS AND IS NOT BEING USED.** MRTR parks a request on a virtual
thread precisely so the loop keeps reading — `withRequestTracking` catches an `InputRequiredSignal`,
parks, and resumes when the reply lands. `elicit` waits synchronously instead of raising that signal,
so it never reaches the path built for it.

Found while implementing `elicit` on v2, which is why the backlog asked for the second measurement
there. That measurement came out positive on its own terms — a Throwable raised inside a v2 handler
DOES propagate through `context.invoke` to the shared core — so the park path is reachable from v2;
`elicit` simply does not take it on either lane.

FIXED, and the sentence above is where this entry was WRONG about its own cause. `elicit` does not
"wait synchronously instead of raising the signal": read it and it takes the MRTR path whenever
`mrtrTL` is set, parking on a virtual thread exactly as designed. What gates that path is
`withRequestTracking`, one line — `if !ctx.isModern then onThisThread(null)`. A request from a
2025-06-18 client is not modern, the scope is never installed, and the legacy blocking branch is the
only one it can reach. So the machinery was not "not being used" out of oversight; it was reachable
only from the modern era, and routing a legacy request through it would answer `input_required` to a
client that cannot read it. The park path was the wrong fix.

WHAT ACTUALLY LANDED is in the loop, where the entry's own title said the problem was. Reading and
handling are now separate in `McpServerCore.serve`: the calling thread reads and routes, and
requests are dispatched to ONE handler thread. The reader is therefore free to route an inbound
Response while a handler is parked. One file in `mcp/common`, so both lanes are fixed by it — which
the measurement below confirms rather than assumes.

    before, --v1 and --v2 byte for byte:
      → elicitation/create goes out
      → the client's answer, sent one second later, is IGNORED
      → {"text":"srv.elicit: srv.request 'elicitation/create' timed out after 6000ms"}
      → and an unrelated tools/list sent in that same second is answered only AFTER the timeout
    after, --v1 and --v2 byte for byte:
      → {"text":"action=accept"}

ONE HANDLER THREAD, NOT A POOL, and the difference is visible: with nobody answering, an unrelated
`tools/list` sent while the handler is parked is still not answered until that handler finishes —
measured at t=6s against a 5s elicit timeout. So the server is no longer DEAF (it hears and routes
the answer) but it is still SERIAL. That is deliberate: a pool would let unrelated requests answer
during a parked one, but replies could then leave out of order — legal in JSON-RPC, and a needless
break for every gate that compares a transcript. It is recorded here as a property rather than
asserted in the gate, so improving it later does not have to fight a test.

Two smaller things the change owes its shape to. Frames now originate on two threads, so `write` is
taken under a lock — two half-written lines spliced together are not two frames, and the gate parses
every output line to check it. And EOF now has to DRAIN: work queued before the client left is still
owed a reply. The drain is bounded at 90s rather than unbounded, because a handler parked on a
server-initiated ask after the client is gone is waiting for something that can never arrive, and
every such ask carries its own timeout (60s by default) — waiting forever would be a hang where the
old code merely lost the reply.

Over HTTP this same family works through a different mechanism entirely (`mcp-v2-http-transport-has-no-sse`):
each POST already runs on its own thread, so what was missing there was the outbound channel, not
the free reader.

## interp-summon-over-an-anonymous-given — the reference lane answers `unbound global` where Scala 3 resolves

<!-- status: open
     lane: int
     area: runtime
     kind: bug
     gate: none
     found-by: claude-code
     found-at: 2026-08-16
     ssc-version: c77678ed6
     repro: the six-line program in the body
     confirmed: yes -->

    trait Combiner[A]:
      def combine(a: A, b: A): A
    given Combiner[Int] with
      def combine(a: Int, b: Int): Int = a + b
    def main(): Unit =
      val c = summon[Combiner[Int]]
      println(c.combine(2, 3))

    ssc: unbound global: c

The NAMED form — `given intCombiner: Combiner[Int] with` — works on the interpreter and answers 5.
So the gap is the anonymous spelling, which is the idiomatic one in Scala 3 and the one every
typeclass module in `std/` uses.

**AN ATTEMPT WAS MADE AND RELEASED WITHOUT LANDING, 2026-08-19 to 2026-08-23, and what it learned is
worth more than what it wrote.** The approach was right: synthesise Scala's own name for the
anonymous form — `given Show[Int]` → `given_Show_Int` — so everything downstream, which is keyed by
name, reuses the NAMED path untouched instead of growing a parallel one. 122 lines of mangling and
trailing-underscore strip were written.

⚠️ **THEY WENT INTO `v2/lib/ssc1-front.ssc0`, AND THIS FILE IS COMPILED BY F.** Measured from that
worktree, built: the six-line reproducer above still answers `unbound global: c` on both the default
and `--native` lanes. The fix has to go into `specs/v2.2-p6.5-fsub.ssc`.

**THE SAME MISTAKE HAPPENED TWICE IN FOUR DAYS**, by two different agents on two different defects.
`ssc info --front-report <file>` answers `F` in one second and is the first thing to run before
editing any front. The attempt's patch is kept in the session salvage rather than in the tree; the
approach above is the part worth reusing.

**Found because the Rust lane now does this correctly and I needed an oracle.** The MCP-era rule
here is to compare lanes; when the reference lane is the one that is wrong, the honest move is to
say so rather than to weaken the case or match the gap. The Rust gate therefore asserts its own
output for this shape and explains why, and this entry is the other half of that explanation.

Not investigated further — the interpreter is outside the claim this was found under.

## rust-summon-lowers-to-an-empty-expression — `val x = summon[T]` emits `let x = ;`

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 830d836c6
     gate: tests/e2e/build-rust-refuses-loudly.sh
     found-by: claude-code
     found-at: 2026-08-15
     ssc-version: febd37e88
     repro: `val ints = summon[Combiner[Int]]`
     confirmed: yes -->

    let ints = ;
    error: expected expression, found `;`

Not a refusal — INVALID RUST, which is the worse direction. `std/semigroup-monoid.ssc` refuses
earlier with `cannot resolve summon[Semigroup[A]] — no matching given instance`, so the resolver
knows about `summon` and this is the path where resolution produced nothing and the emitter wrote
the nothing out rather than refusing.

Found the same way as the entry above, in the same discarded probe draft.

**FIXED — AND THE HEADING NAMES THE NARROWER HALF OF IT.** `summon` did not work on this lane AT
ALL. With an anonymous given it emitted `let x = ;`, which is what this entry saw; with a NAMED
given it emitted `let c = intCombiner;` and no binding — E0425, one error later and equally broken.
I filed from the anonymous symptom and assumed the named case was fine. It was not, and only
running it said so.

**THE CAUSE IS REACHABILITY, NOT RESOLUTION.** `topValsReferencedBy` decides which
`let name = …Given;` bindings a def needs by scanning its body for names THE USER TYPED. A summoned
instance name is supplied by the resolver and appears nowhere in the source, so the binding was
dropped as unreachable while the reference to it was still emitted. The scan now also collects what
each `summon` in the body resolves to.

Both spellings now answer `i=5`, matching the interpreter for the named one. Gate:
`tests/e2e/build-rust-refuses-loudly.sh`, both shapes; the named case is differential against the
default lane and the anonymous one is not, because the interpreter answers `unbound global` there —
filed as its own gap rather than used as an oracle.

## rust-absolute-import-path-inlines-nothing — `[names](/abs/path.ssc)` resolves to no declarations at all, silently, and the program still builds

<!-- status: fixed
     lane: v2-rust
     area: front
     kind: bug
     gate: tests/e2e/absolute-import-resolves-gate.sh
     fixed-in: fc7a455c4
     found-by: claude-code
     found-at: 2026-08-14
     ssc-version: dc3cfeeef
     repro: the two-form comparison in the body
     confirmed: yes -->

**Found while building a gate, and the gate is how it was found** — a case written with an absolute
import path passed its build and tested nothing.

The SAME program, differing only in the form of the import path:

    [McpClient, mcpConnectSpawn](/Users/…/std/mcp/client.ssc)   →  10 generated lines
    [McpClient, mcpConnectSpawn](../../Users/…/std/mcp/client.ssc) → 163 generated lines

The absolute form contributes NO declarations — no case classes, no `extern class`, nothing. The
relative form inlines the module.

**WHY IT IS WORSE THAN A BROKEN IMPORT.** The program still compiles. `mcpConnectSpawn(…)` lowers
because the intrinsic table is keyed by BARE NAME and does not care whether the declaration arrived;
only things that need the declarations — an `extern class` member call, a case class constructor —
go wrong, and they go wrong as a rustc error in generated code rather than as "import not found".
An import that resolves to nothing should say so.

Measured on `dc3cfeeef`. Not investigated further: the resolver is outside the Rust backend and
outside the claim this was found under. The gate that found it now uses a sibling copy instead
(`tests/e2e/build-rust-refuses-loudly.sh`, the MCP end-to-end case), with the reason recorded there.

**HIT AGAIN 2026-08-15 on `std/http.ssc`, from a different direction, and two things it adds are
worth keeping.** I filed it a second time as `rust-absolute-import-path-does-not-resolve-a-case-class`
before searching the board; that entry is now a duplicate stub, and this is what it measured:

*The emit-time refusal sees only ONE of the positions.* A handler whose body is directly
`Response(…)` is refused at emit with `def main calls Response, which this crate does not define`,
while one reaching the same constructor through an `if` emits a crate — which then fails at cargo:

```
error[E0412]: cannot find type `Request` in this scope
error[E0412]: cannot find type `Response` in this scope
```

So the refusal is a symptom of the resolution gap rather than a guard against it, and BOTH imported
case classes are gone, not just the one the message names.

*It costs gate-writing time, repeatedly.* A gate whose sandbox lives in `$TMPDIR` must write the
import absolutely, and every row that needs a case class then goes red for a reason that has nothing
to do with what the gate tests. Two gates have now worked around it by creating the sandbox inside
`examples/` (`rust-http-lane-parity-gate.sh`, `rust-route-handler-shapes-gate.sh`).

**FIXED 2026-08-16 — as a REFUSAL, which is the opposite of what the title suggests, and the other
lanes are why.** Before changing anything I ran the same program on all three:

| lane | absolute import |
|---|---|
| `run` | `ssc: native frontend import not found: /…/std/http.ssc from abs.ssc` |
| `--v1` | `Import /…/std/http.ssc: requirement failed: … is not a relative path` |
| `build-rust` | `Cargo crate written …`, **exit 0** |

An absolute import path is not supported by the language as implemented, and this lane was the only
one that did not say so. Making it RESOLVE would be a language change on three lanes and is not what
this entry asked for; what it asked for is in its own words — *an import that resolves to nothing
should say so*.

**The mechanism, exactly.** `inlineImportsRust` (`v1/tools/cli/.../Main.scala`) calls
`ImportResolver.resolve`, which builds `baseDir / os.RelPath(path)` — and os-lib REFUSES an absolute
string as a RelPath, so it throws. The throw was swallowed by a `catch { case _: Throwable => None }`
and the import contributed nothing; a `case _ => Nil` did the same for a path that resolved to a
file that does not exist.

**It was never about absolute paths.** A plainly MISSING relative import — `../../std/nosuchfile.ssc`
— went down the identical path and was dropped just as silently. Both are refused now, and the
second is the row in the gate that says so.

**Three skips stay silent, deliberately**, and separating them from the failure is most of the
change: a directory, a compiled `.sscc`, and a file already inlined elsewhere in the import graph
are not failures. Turning those into refusals would have broken working programs, which is the risk
this change carried.

Gate: `tests/e2e/absolute-import-resolves-gate.sh`, on the PUSH path (four `emit-rust` runs, no
cargo, 3.9 s measured in the suite). Watched failing with the fix reverted and the toolchain
rebuilt: both refusal rows came back `exit 0, Cargo crate written` — the literal symptom — while the
two anti-rows stayed green. Corpus control: `rust-std-survey-gate.sh` over 132 std modules unchanged
at REFUSED 78 / COMPILES 54 / BADRUST 0, so no std module was relying on an import being silently
dropped.

**Left for someone else, filed here rather than fixed:** `--v1` answers this with an `[ERROR]` line
followed by a raw `InterpretError` stack trace, where `run` prints one clean sentence. The refusal is
correct on both; only the presentation differs.

## rust-any-map-read-default-not-lifted — `m.getOrElse(k, "?")` on an `Any`-valued map passes a String where the map holds a `Value`

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-any-map-literal-gate.sh
     fixed-in: d407a264d
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes -->

**Found while closing `rust-any-valued-map-literal-not-lifted`, and it is what made that
measurement ambiguous.** With the literal now lifted correctly, the map is built as
`HashMap<String, Value>` — and then the READ fails:

```scala
val m: Map[String, Any] = Map("k" -> "hello")
println(m.getOrElse("k", "?"))
```

```rust
m.get(&"k".to_string()).cloned().unwrap_or("?".to_string())
//                                         ^^^^^^^^^^^^^^^ expected `Value`, found `String`
```

The write side of the `Any` boundary is handled at three sites (parameter, local annotation, and now
the literal itself); the READ side is not. The default of `getOrElse` is emitted as whatever it is
literally, so any `Any`-valued container that a program actually reads from meets this.

**Why the probe mattered:** my first attempt at re-measuring the literal defect used `getOrElse` to
observe the value, so the red I saw belonged to this defect and not to the one under test. The
lesson is the standing one — a probe must not reach its subject through a second unfixed defect.

`coerceFromValue` already has the arm this needs; it has to be applied to the default argument when
the receiver is a `Value`-valued container.

**FIXED 2026-08-16.** The default is emitted as `$d.into()` rather than as itself:

```rust
m.get(&k).cloned().unwrap_or(d.into())
```

**Total rather than type-directed, and that is the choice worth recording.** Knowing whether THIS
map holds `Value` would need the receiver's type at the call site, which this walker does not carry
— and threading it in means a new `Ctx` field, paid for at every copy site whether it is read or
not. `.into()` needs no such knowledge: the target type is already fixed by the `Option` that `get`
produced, and std's blanket `impl<T> From<T> for T` makes it the IDENTITY when the two types
already agree. So a `Map[String, String]` and a `Map[String, Int]` emit the same behaviour they did
before, which is what the two control rows in the gate assert.

Rows added to `tests/e2e/rust-any-map-literal-gate.sh` — the write side of this boundary was already
there, and the read side belongs beside it. Each reads TWICE, once with a present key and once with
an absent one: only the absent key takes the default, which is the path that was broken. Watched
failing with the fix reverted and the toolchain rebuilt: `anyread` red with the literal `E0308`,
`plainread` and `intread` still green. Corpus control unchanged at REFUSED 78 / COMPILES 54 /
BADRUST 0; `v1-jit-size` PASS, none grown.

## rust-any-valued-map-literal-not-lifted — a `val m: Map[String, Any] = Map("k" -> "x")` annotates `HashMap<String, Value>` and builds `HashMap<String, String>`

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-any-map-literal-gate.sh
     fixed-in: dfb796246
     found-by: claude-code
     found-at: 2026-08-14
     ssc-version: a1aea822a
     repro: val m: Map[String, Any] = Map("a" -> "x", "b" -> 1)
     confirmed: yes -->

**Found while building a probe for something else, and it is recorded separately for exactly that
reason** — routing that probe through this defect would have made its red ambiguous between two
causes.

    val m: Map[String, Any] = Map("k" -> "hello")

emits the annotation and the value independently:

    let m: std::collections::HashMap<String, crate::value::Value> =
      { let mut __m = HashMap::new(); __m.insert("k".to_string(), "hello".to_string()); __m };

→ `E0308: expected HashMap<_, Value>, found HashMap<_, String>`. A HETEROGENEOUS literal fails
twice and more confusingly: `Map("a" -> "x", "b" -> 1)` reports `expected String, found i64` at the
SECOND insert, because the first one fixed the element type — the message names the wrong element
and says nothing about `Any`.

**It is one hop from a site that already works.** The same literal written straight at a
`Map[String, Any]` PARAMETER is lifted correctly — `needsAnyCoercion` covers containers of `Any` at
a call boundary, and `coerceFromValue` already has the `HashMap<String, Value>` arm that does it.
So the machinery exists and the local-declaration site simply does not ask for it.

**Where it hooks:** `renderLetBinding` (`RustCodeWalk.scala:5572-5584`) computes `tyAnn` from
`decltpe` via `mapType`, renders the RHS separately, and puts the two next to each other — the
declared type is PRINTED, never APPLIED. The narrow fix is to coerce the RHS to the annotated type
when they can differ; the reason this is filed rather than done is that `renderLetBinding` is on
the path of every local in the repository, so the change needs the survey and the goldens to
measure it, not a one-line edit at the end of another claim.

**FIXED, and it was THREE shapes rather than the one reported.** Measured before touching anything:

    val m: Map[String, Any] = Map("k" -> "v")   HashMap<String, String> under a HashMap<_, Value>
    val xs: List[Any]       = [1, 2]            Vec<i64>                under a Vec<Value>
    val a: Any              = 5                 i64                     under a Value

Four cargo errors from three lines. Fixing only the Map — the shape the entry names — would have
left the same trap one keystroke away, which is the [[a-feature-has-a-spelling-matrix]] lesson
applied before rather than after.

**The ARGUMENT boundary already worked**, which is why this survived: `take(m)` coerces through
`needsAnyCoercion`, so a probe that only passed a literal to a function shows nothing. The local is
the one site that had been left out.

**Total, and deliberately narrow.** `Value::from` is the identity on a `Value` (Rust's blanket
`impl<T> From<T> for T`) and the element maps are the identity on a container already holding
`Value`s, so emitting the lift where the RHS is already right costs nothing. Every other annotation
is returned untouched — the concern this entry raised, that `renderLetBinding` is on the path of
EVERY local, is answered by narrowing rather than by hoping.

Gate: `tests/e2e/build-rust-refuses-loudly.sh`, all three shapes, differentially against the default
lane — `m=1 / xs=2 / a=5` on both. Negative control: with the plain-`Any` lift suppressed — the one
shape the report did NOT name — the gate fails, so the case discriminates each shape rather than
passing on the loudest. rust-std-survey 78/54 BADRUST 0, unmoved; v1-jit-size PASS.

**CLOSED 2026-08-15 — the HETEROGENEOUS literal was still broken, which is the half this entry
opened with.** The lift above coerces the FINISHED value, and that works only where the literal could
be built first. `Map("a" -> "x", "b" -> 1)` cannot: the first insert fixes the element type to
`String` and the second is `E0308: expected String, found i64` — the message names the wrong element
and never says `Any`, exactly as the report predicted. `List(1, "two")` at `List[Any]` fails the same
way. Re-measured before touching anything: of the four shapes, `homo`, `listany`-homogeneous and the
scalar `Any` already BUILT on main; only the heterogeneous ones did not.

**The fix goes where the literal is BUILT**, not after it: each value passes through `Value::from` as
its `insert` is emitted, so no element type is ever fixed. The Map renderer moved out of `renderTerm`
into `renderMapLiteral` to be shared by both call sites rather than copied — which also makes
`renderTerm` smaller, and it is frozen past HugeMethodLimit, so that direction is the only one
available.

**A row in the gate exists because of a mistake I made in this fix.** The first version decided
"were the elements already lifted?" from the SHAPE of the right-hand side, which calls
`val a: Any = List(1, 2)` lifted — a List literal at a SCALAR `Value` annotation, which takes the
ordinary path and still needs the whole-value wrapper. Dropping it emits E0308. The flag now comes
out of the branch that actually ran, and `scalarlist` is a gate row.

Watched failing with the fix reverted and the toolchain rebuilt: the two heterogeneous rows red with
the literal `E0308`, and the homogeneous, scalar and plainly-typed rows still GREEN — they were
working before this change and must not move. Corpus control: `rust-std-survey-gate.sh` over 132 std
modules unchanged at REFUSED 78 / COMPILES 54 / BADRUST 0; `v1-jit-size` PASS, none grown.

**One defect found next door and filed, not folded in:** `m.getOrElse("k", "?")` on an `Any`-valued
map emits `unwrap_or("?".to_string())` against a `HashMap<_, Value>` — the DEFAULT is not lifted. It
is the reading side of the same boundary and it is what made my first probe ambiguous: the program
built the map correctly and failed at the read. See `rust-any-map-read-default-not-lifted`.

## rust-object-member-call-emits-invalid-rust — `Tool.mk(x)` emits `Tool.mk(x)` while the def emits as bare `fn mk`, so rustc answers E0425 and the survey cannot see it

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/build-rust-refuses-loudly.sh
     found-by: claude-code
     found-at: 2026-08-13
     fixed-in: dc3cfeeef
     ssc-version: 2315f2ecf
     repro: the five-line program in the body
     confirmed: yes -->

**More fundamental than the collision defect below, and it absorbs it.** The definition side drops
the qualifier and the call side keeps it, so the two disagree. Five lines reproduce it:

```
object Tool:
  def mk(s: String): String = s

@main def run(): Unit = println(Tool.mk("x"))
```

`emit-rust` exits 0 and writes this:

    pub fn mk(s: String) -> String { s }
    pub fn run() { crate::runtime::_println(Tool.mk("x".to_string())); }

There is no `Tool` in the file — no struct, no module. **rustc, not my reading:**

    error[E0425]: cannot find value `Tool` in this scope
      --> src/generated/objprobe.rs:12:30

`emit-rust` returning 0 is not a contradiction: it writes a crate and never invokes cargo, so the
error lands on the user.

**Why no gate is red, and this is the part worth keeping.** The survey DOES run cargo — that is
what its BADRUST column means — and BADRUST is currently **0** across 81 REFUSED + 51 COMPILES.
That zero is not evidence of correctness here. Every module that calls an object member from
outside is REFUSED earlier for an unrelated reason and never reaches cargo: `std/agent-mcp.ssc`
calls `Tool.error(...)` and `Tool.text(...)` and is refused for `McpClient`; `std/actors.ssc`
calls `Supervisor.start(...)` and is refused generically. The instrument never crosses the
boundary, exactly as the MCP surface gap was invisible until a case crossed it.

**The fix is one change and it closes both defects.** Make the two sides AGREE, by qualifying
consistently: an object member emits as `Qual_member` at its definition and at every call. That
- makes ordinary object-member calls compile, which today they do not, and
- removes the name collisions that
  `rust-member-names-are-flattened-so-two-receivers-cannot-share-a-member` refuses, since
  `Tool_text` and `Resource_text` no longer share a name.

Qualifying only the colliding pairs — what that entry originally recommended — would leave THIS
defect in place for every non-colliding object. The recommendation there is superseded.

**ATTEMPTED 2026-08-14 AND REVERTED — the three-site plan below is NOT sufficient, and the reason
is one level above the emitter.** Recorded in full because the attempt bought the answer.

The change qualified every object member — `Owner_member` at the definition, at a qualified call
from outside, and at an unqualified sibling call — held in walker state rather than in `Ctx`, so
`renderTerm` did not grow and the JIT freeze was untouched. A purpose-written gate went from red
(`E0425` twice) to green, and `backendRust/test` stayed 278/278.

**The survey caught what the gate could not**, which is the whole reason its diff is the acceptance
instrument here:

    std/ui/i18n.ssc:  COMPILES -> BADRUST
    std/ui/state.ssc: COMPILES -> BADRUST
    error[E0425]: cannot find function `showWhen` in this scope

**The cause: a synthetic namespace object is indistinguishable from a real one.** `def showWhen`
in `std/ui/reactive.ssc` is declared at TOP LEVEL, with no object around it — yet it was qualified.
Normalize wraps every library module's statements in synthetic namespace objects (`object std {
object ui { object theme { … } } }`), and `RustCodeWalk` says so itself, six hundred lines above
where this change was made: its `descend` helper exists precisely to walk through them. So
"qualify object members" qualified every top-level library def, while their call sites stayed bare
— `[showWhen, signalText_](reactive.ssc)` imports a name and calls it unqualified.

**That also explains the original defect completely.** Definitions are flattened BECAUSE every
top-level object is treated as a namespace wrapper to descend through; the call site keeps its
qualifier because it is only syntax. The two sides disagree for one reason, not two.

**So the first piece of work is upstream:** the emitter needs to tell a synthetic wrapper from a
user-declared `object`. No marker was found on the tree; the wrapper names mirror the module path,
so the distinction has to come from Normalize marking them, or from comparing against the module's
declared package. Until that exists, BOTH shapes fail — qualify-always breaks bare-name callers of
top-level library defs, and qualify-on-collision cannot even identify which defs are members.

**The gate is written and is not landed**, because a gate that fails on main is not acceptable and
an unwired one trips `no-orphan-gates`. Its shape, for whoever resumes: build a program with
`object Tool` whose member is called BOTH from outside (`Tool.mk("out")`) and from a sibling
(`def viaSibling() = mk("sib")`), run the binary, require `out\nsib`; require cargo and exit 2
saying so if absent, rather than skipping quietly. It failed red on the unfixed tree with exactly
the E0425 above, so it discriminates.

**WHAT THE FIX HAS TO DO — three sites, and the third is the one that bites.** Measured, not
listed from memory: a sibling call inside an object emits as a BARE name today and works precisely
because both ends are flattened.

    object Tool:
      def a(): String = b()      // emits as `b()` — correct today
      def b(): String = "x"

So renaming definitions without site 3 breaks code that currently compiles:

1. **The definition** — `fn mk` becomes `fn Tool_mk`.
2. **The qualified call from outside** — `Tool.mk(x)` becomes `Tool_mk(x)`. Today it emits
   `Tool.mk(x)`, which is the defect.
3. **The unqualified sibling call from inside** — `b()` becomes `Tool_b()`.

Site 3 is the hard one, and it is why "just qualify the definition" is not a fix. The emitter must
tell a bare name that is a SIBLING MEMBER from a bare name that is a top-level def, a parameter or
a local. It cannot today: `userDefs` is a flat `Set[String]` of bare names. It needs the owner map
— the structural walk `collectEffectOps` already performs and `collectDefs` deliberately does not,
since the latter is a deep `collect` that lifts members out with no record of origin.

**Two conditions on accepting it, not optional.**

4. A gate that CROSSES THE BOUNDARY: a case calling an object member from outside that reaches
   cargo. Without it the change is unverifiable, because BADRUST reads 0 today for the reason
   above — not because the output is right.
5. Verification by the survey baseline DIFF, with the prediction stated first: the six REFUSED
   rows flip, BADRUST stays 0, and nothing else moves. The gate alone only asserts that BADRUST
   does not GROW, which is exactly what let three earlier regressions through.

**A gate has to cross the boundary or the fix is unverifiable.** The survey cannot show this while
the modules that exercise it are refused first, so the fix needs a case that calls an object
member from outside and reaches cargo — the same shape added to `v21-standard-mcp-smoke.sh` for
the MCP surface.

**FIXED, and the plan above was wrong in TWO places — both retired by measurement, both worth
keeping because each was a reasonable-sounding estimate.**

*First: "six REFUSED rows flip".* Only ONE does. A census of the eleven rows refusing on
`overloading`, taken BEFORE the change, says the owner kinds differ: five are `trait` members
(`SqliteCursor.close` against `SqliteStatement.close`, reaching five `std/scljet/*` modules through
`index.ssc`), five are `given … with` typeclass instances (`eqv`, `hash`, `compare`, `show`,
`combine`), and exactly one — `std/mcp/types.ssc`, `text` on `object Tool` versus `object
Resource` — is OBJECT-member flattening. The prediction was restated as "one row moves, BADRUST
stays 0, nothing else changes status" before any code was touched, and that is exactly what
happened: `std/mcp/types.ssc` REFUSED → COMPILES, REFUSED 81 → 80, COMPILES 51 → 52.

*Second: "qualify on collision only" had been retired earlier as insufficient.* It is not merely
sufficient, it is REQUIRED — and the thing that proves it is a defect this fix introduced. Renaming
every object member broke `std/json.ssc`: it declares `package: std.json` while the
`std/json-core.ssc` it imports declares `package: std.json.core`, so the INLINED blocks carry one
synthetic wrapper level more than the merged manifest describes, and `core` read as a user object.
Its defs emitted as `core_jsonCore…` while bare calls from the importing module's own blocks did
not move — E0425, waiting for the first module of that shape to get far enough to compile.

**AND THE SURVEY WAS BLIND TO IT.** `json.ssc` refuses earlier for an unrelated reason, so every
status stayed put and the gate stayed green. The only tell was the `sites` count moving 6 → 10. My
first explanation — "qualification splits names, so the counts got more accurate" — was FALSE, and
checking it is what found the defect: `std/json.ssc` contains no objects at all. The AST carries no
per-block provenance, so the wrapper depth cannot be repaired directly; it does not need to be,
because a member name that one owner owns and nothing else shares needs no qualification. Only a
CONTESTED name is renamed — two owners, or a collision with a top-level def.

**The three sites, and how each is closed without a new match arm.** The definition emits its
qualified name from `renderDef`, which also dissolves the overloading refusal for free: that check
groups on `GeneratedDef.name`. The QUALIFIED call and the SIBLING call both become INTRINSIC MAP
ENTRIES — the mechanism that has always lowered `Console.println` — the first module-wide, the
second built per def from its own owner, because with two objects owning a `text` a module-wide
entry could not say which one a bare call means. That routing was not only tidier: `renderTerm` is
one of the five known over-limit methods and `v1-jit-size` forbids it growing, so a new arm there
was not available at all. A real intrinsic still wins on a key clash — `intrinsics0` sits on the
right of `++` — so a user object named `Console` cannot shadow the real one.

**A rewrite of the source tree was the first design and it is impossible here:** scalameta 4.17 on
Scala 3 offers `traverse` and `collect` but no `transform`. The owner map is therefore keyed by def
POSITION, not by name — two owners sharing a member NAME is the defect itself, so the name cannot
identify which def belongs to whom.

**Counts moved on four other rows and it is the good direction.** `std/mapreduce/shuffle.ssc` goes
30 → 32 sites: it calls `ShuffleProtocol.handleMessages`, which used to emit VERBATIM — invalid
Rust and no refusal — and now lowers, so the walk reaches past it and finds genuine refusals that
were behind it. Silent garbage replaced by either correct code or an honest refusal.

Gate: `tests/e2e/build-rust-refuses-loudly.sh` — two objects sharing a member name, called from
outside AND from inside, compared against the default lane. The SIBLING call is in the case
deliberately: it works today precisely BECAUSE both ends are flattened, so it is the site a fix can
silently break. Negative control: with the qualified-call lowering suppressed the gate fails on
that case with `object members do not build` and cargo exit 101.

## rust-member-names-are-flattened-so-two-receivers-cannot-share-a-member — `SqliteCursor.close` and `SqliteStatement.close` are reported as overloading and refuse the whole module

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-member-qualify-gate.sh
     fixed-in: 1af25df98
     found-by: claude-code
     found-at: 2026-08-13
     ssc-version: db5dc1c3d
     repro: emit-rust std/mcp/types.ssc
     confirmed: no -->

**Found underneath `build-rust-mcp-client-unsupported` (BACKLOG), and it is the real blocker
there.** The Rust lane flattens members of distinct receivers into one namespace, then refuses
the module when two of them share a name, reporting it as *overloading*:

    def `text`  emits 2 times (overloading); Rust has no overloading and this lane
                does not mangle names

The name is a misnomer, and that is what hid this. In `std/mcp/types.ssc` the two are
`object Tool: def text(s)` and `object Resource: def text(s, mimeType)` — **different objects**.
In `std/scljet/index.ssc` there are three `def close`, on `trait SqliteCursor`,
`trait SqliteStatement` and `trait SqliteConnection` — **three different traits**. Neither is
overloading in any language sense; they are distinct qualified names, and
`SqliteCursor.close` is no more an overload of `SqliteStatement.close` than `Console.println` is
of `Bench.opaque`. The lane already keys intrinsics by qualified member name — `Console.println`,
`Bench.opaque` — so the qualification it needs is one it already speaks.

**Census, from the committed survey baseline rather than from my own run.** Six modules of the
81 REFUSED carry this exact reason, and they are not one area:

    std/mcp/types.ssc            Tool.text        vs Resource.text
    std/scljet/address.ssc       close, transitively
    std/scljet/jdbc.ssc          close, transitively
    std/scljet/mutate.ssc        close, transitively
    std/scljet/sql.ssc           close, transitively
    std/scljet/typedsql.ssc      close, transitively

The five scljet rows do not declare the clashing member themselves; they import the closure that
does, so a single qualification fix should move all six. That is a prediction and is written down
as one — the survey is the instrument that will settle it.

**SETTLED 2026-08-15, AND THE PREDICTION IS WRONG: the fix would move ZERO modules.** Re-measured by
emitting every module that mentions an overloading refusal and counting ALL its diagnostics, not just
the first one the baseline stores:

| module | diagnostics | of them overloading | other |
|---|---|---|---|
| `std/agent-mcp.ssc` | 4 | 1 | 3 |
| `std/eq.ssc` | 3 | 1 | 2 |
| `std/hash.ssc` | 2 | 1 | 1 |
| `std/order.ssc` | 8 | 1 | 7 |
| `std/semigroup-monoid.ssc` | 3 | 2 | 1 |
| `std/show.ssc` | 2 | 1 | 1 |
| `std/scljet/{address,jdbc,mutate,sql,typedsql}.ssc` | 308–608 each | 1 | the rest |

Eleven modules mention it now, not six — and not one of them is blocked by it alone. THE COUNT IN
THE BASELINE READS AS "ONLY REASON" AND IS NOT: the file stores the FIRST reason plus a shape count,
so a module whose other blockers share one shape looks single-cause.

**And the four typeclass modules name the real wall.** Beside `def eqv emits 5 times (overloading)`
sits `def eqv uses type Eq[A]; R.2 accepts primitives, enums, function types, tuple, and List/Vec` —
same for `Hash[A]`, `Show[A]`, `Order[A]`. The flattening is a SYMPTOM of having no typeclass
dispatch, not an independent defect: a typeclass declares one member name per instance, so five
instances flatten to five `fn eqv`. Qualifying the names leaves the dictionary type unlowerable and
the module still refused.

**The nearest module to COMPILES is `std/agent-mcp.ssc`** — two diagnostics, one of them this one.
That is the only place where this fix is one of a pair rather than one of a queue.

**Cross-check with the sibling measurement, which reached the same shape of answer by another route.**
`rust-no-paren-member-needs-receiver-type` is `wontfix` for exactly this reason: lowering the three
parenless members is four lines, and of the 16 modules refusing on them, zero reach COMPILES while
five turn into rustc errors (207 in `content-core`, 98 in `yaml-core`, 47 in `json-core`). Two
independent probes now say the same thing: **a walker-level refusal count is not a queue position.**
Clearing the reason a module reports first only exposes the next blocker, in the walker or in rustc.

So this entry stays open as a real defect — two receivers cannot share a member, and any `.ssc` API
that gives two types a `close` or a `text` meets it — but it should be taken for what a PROGRAM needs,
not for what the REFUSED column would show. It moves that column by nothing.

**Why this matters beyond the six.** It is what actually blocks the Rust MCP client. Fixing
`types.ssc` alone would not be enough: `std/mcp/client.ssc` declares `McpClient.close` while
`std/http.ssc` declares `SseStream.close`, so the client meets the same wall one step later. Any
future `.ssc` API that gives two types a `close`, a `text`, a `name` or a `size` meets it too —
which is most of them.

**WHERE IT HAPPENS, so the next person starts from the map and not the symptom.** Two sites, and
the second only reports what the first caused:

- `RustCodeWalk.scala:1571` — `case m.Term.Select(_, m.Term.Name(n)) => n`. The receiver is
  matched and **discarded**; only the bare member name survives. That is the flattening itself,
  and the emitter does have the receiver syntactically at that point.
- `RustCodeWalk.scala:~236` — `ok.filter(_.render.trim.nonEmpty).groupBy(_.name)` then refuses any
  group of more than one. **This check is CORRECT given the naming above**: if two members really
  do emit as `fn text`, rustc could not compile the crate either. Do not "fix" it by loosening the
  count — that would trade a clear refusal for a rustc error inside generated code the user never
  wrote, which is the failure mode the comments around it were written about.

**Two shapes the fix can take**, and the choice is a real one:

- *Qualify always* — every object member emits as `Object_member`. Uniform, but the emitter keys
  on the bare `d.name.value` in at least eight places (`_defBodies`, `userDefs`, `effectfulDefs`,
  `rustFnNamesInBlocks`, the default-argument map, the arity map) and every call site resolves
  through the same bare name. It is a wide change.
- *Qualify on collision only* — bare names stay, and only a colliding pair is disambiguated on
  both its definition and its call sites. Much narrower, and structurally possible because the
  receiver is present at line 1571 before being dropped. It needs a map of which qualifiers are
  OBJECTS rather than values, which the walk does not currently build.

**THE RECIPE, with the pieces the file already provides.** The narrow shape is the one to take,
and it does NOT touch the eight places keying on the bare `d.name.value` — reachability, defaults,
arities all keep working on bare names. Only two things get qualified:

1. **The definition.** In `renderDef`, a colliding member emits its `fn` header as `Qual_member`.
2. **The call.** `RustCodeWalk.scala:~4302`, the `plainName.filter(ctx.userDefs.contains)` branch —
   when the call term was `Select(Name(q), Name(n))` and `(q, n)` collides, emit `q_n`.

Then the refusal at ~236 groups by the EMITTED name, the collision is gone, and the check is left
exactly as it is — which matters, because it is correct.

Three pieces already exist and should be reused rather than reinvented:

- `qualifiedName` (line ~5462) already builds `QualifiedName("Tool.text")` from a `Select`. It is
  used for intrinsic lookup today; the call site needs the same value.
- `collectEffectOps` (line ~749) already walks objects STRUCTURALLY and keeps `o.name.value -> its
  members`. That is the qualifier map this needs.
- `collectDefs` (line ~563) is where the qualifier is lost: `node.tree.collect { case d: Defn.Def
  => d }` is a DEEP collect that lifts members out of objects and traits with no record of where
  they came from.

**One trap to handle while implementing.** Line ~194 builds `byName = defs.map(d => d.name.value
-> d).toMap`. On a collision that silently DROPS one def. It is harmless today because collisions
are refused before anything can depend on it; once they are allowed, the reachability walk starts
scanning only one of the two bodies and can under-approximate. The final filter is
`defs.filter(d => seen.contains(d.name.value))`, so both defs still reach the render list — the
risk is a missed reference, not a lost definition.

**How to know it worked, and it is not the gate.** Re-run the Rust std survey and DIFF
`tests/rust-std-survey-baseline.tsv`. The prediction to check against: the six REFUSED rows named
above should flip, and nothing else should move. The gate only asserts BADRUST does not grow,
which is exactly why it missed the three regressions below.

**Handle with care, and the file says why.** The comments around the refusal record three earlier
attempts at this area: counting declarations cost NINE modules their COMPILES status, counting
reachable ones still cost three, and the current rule counts what actually EMITS. Whoever takes
this should expect the survey baseline diff — not the gate — to be what catches a regression, as
it was those three times.

**Not fixed here.** Recorded with the measurement because the fix is in the emitter's naming
scheme and deserves its own claim, and because the report it was found under can now be sized
against the real obstacle instead of an assumed one.

**CLOSED 2026-08-16 — by two changes, neither of them a mangling scheme, and the remaining
collisions turn out not to be this defect.** Measured shape by shape, each on the reference lane
first:

| shape | before | now |
|---|---|---|
| two OBJECTS declaring `close` | `emits 2 times (overloading)` | both lanes print, member emits as `Owner_member` |
| two CASE CLASSES declaring `close` | `emits 2 times (overloading)` | both lanes print, method emits into `impl Owner` |
| a TYPECLASS with two instances | reported as overloading | refuses on `uses type Show[A]` — the DICTIONARY, not the name |

The case-class half was fixed under `rust-case-class-method-cannot-read-its-own-fields`: methods
moved into `impl <Owner>`, and a member inside an impl cannot collide with another type's. The
object half was already qualified at the definition site. So the *census* in this entry — six
modules, five of them scljet — was measuring a symptom that two unrelated fixes removed.

**THE TYPECLASS ROW IS THE BOUNDARY AND IT IS WHY THIS CAN CLOSE.** `std/show.ssc`, `std/eq.ssc`,
`std/hash.ssc` and `std/order.ssc` still refuse, and re-measuring them (2026-08-15, recorded above)
showed the overloading reason never stood alone there: beside it sits `uses type Show[A]; R.2
accepts primitives, enums, function types, tuple, and List/Vec`. A typeclass declares one member
name per instance, so the flattening was a SYMPTOM of having no dictionary type on this lane.
Qualifying the names would leave that type just as unlowerable — the work belongs to typeclass
dispatch, not here.

Gate: `tests/e2e/rust-member-qualify-gate.sh`. Two regression rows and one boundary row, which
asserts that the typeclass refusal is the DICTIONARY one — if it ever comes back as
`emits N times (overloading)`, the flattening has returned. Watched failing with the case-class
`impl` emission disabled and the toolchain rebuilt: the `classes` row reds with the literal
`def close emits 2 times (overloading)` while the object row stays green, so the two halves are
distinguished rather than sharing a cause.

**A probe that had to be thrown away, recorded because the mistake is easy to repeat:** my first
attempt used two TRAITS with an inherited method. `run` answers that with
`unhandled runtime effect: C1.close` — the oracle does not support the shape, so a "fix" measured
against it would have made this lane invent behaviour no other lane has.

## string-param-moved-by-toint — A String param read twice where one read is .toInt is E0382 — _to_int takes it by value and clone-insertion does not reach the second read

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-std-survey-gate.sh
     fixed-in: 9f89edf74
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-12
     ssc-version: 3ca08020e
     repro: repro/string-param-moved-by-toint.ssc
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-13. Everything below is the reporter's, in their words.

A `String` parameter read twice, where one of the reads is `.toInt`, does not compile. The control
is in the same file: the identical check written through two local copies DOES compile.

    ssc run     locals = true   direct = true       (both fine)
    build-rust  error[E0382]: borrow of moved value: `s`
                pub fn direct(s: String) -> bool {
                    (format!("{}", crate::runtime::_to_int(s)) == s)
                                                          -      ^ borrowed after move

`_to_int` takes the String BY VALUE, so the comparison that follows has nothing left to read. The
walker already inserts clones for names read more than once (`multiUse`), and this shape is not
reached — the second read is the plain parameter, not an argument position.

The control matters more than the defect: `val a = s; val b = s; a.toInt.toString == b` compiles,
so the capability is there and only this spelling loses it. That is also the workaround, and it is
in rozum's `public-matrix.ssc` with this reason beside it — a reader will otherwise "simplify" it
back and break the build.
## todouble-on-a-string-emits-a-cast — toDouble on a String lowers to 'String as f64', a cast Rust does not have — toInt on the line above compiles

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-std-survey-gate.sh
     fixed-in: 9f89edf74
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-12
     ssc-version: 3ca08020e
     repro: repro/todouble-on-a-string-emits-a-cast.ssc
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-13. Everything below is the reporter's, in their words.

Two lines, one works:

    ssc run     toInt = 121    toDouble = 2.5      (both fine)
    build-rust  error[E0605]: non-primitive cast: `String` as `f64`

`"120".toInt` lowers to a parse and compiles; `"1.5".toDouble` lowers to `String as f64`, which is
not a cast Rust has. `toInt` on the line above is the control.

Found writing a numeric check for rozum's UCC slice: I needed "does this CSV field parse as a
number", reached for `toDouble`, and had to fall back to splitting on the dot and using `toInt`
twice. That workaround is in our code with this reason written beside it.
## bigint-and-bigdecimal-do-not-widen-against-a-double — `BigInt(1) == 1.0` is `true` in Scala and `false` on interp and native

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/mixed-numeric-comparison-gate.sh (rows 12-19; row 20 is the frozen gap)
     fixed-in: deddb5527 -->

**CLOSED 2026-08-13.** All three defects are resolved: two landed on 2026-08-12 (below), the interp
arms landed today, and the third was never an omission — it is a contract decision, carried into
`bigdecimal-against-a-binary-float-is-a-contract-decision-nobody-has-taken` so that the question
stays asserted without holding this entry open. Read the closing section at the end.

The `gate:` field named two fixture files that do not exist and never did — the gate has always used
one shared source and sliced it by row. Corrected above while closing, since a `gate:` naming
something unfindable is worse than none.

**PARTLY FIXED 2026-08-12 — two of the three defects are closed, and the entry stays OPEN for the
third.** What landed, all on the v2 side (`v2-bignum-widen`):

- **`eqWidening` in `v2/src/Runtime.scala` gained the wide-type arms.** `BigInt(1) == 1` was FALSE
  where interp got it right, and `BigInt(1) == 1.0` was false on both. The comparison is delegated
  to Scala's own `BigInt.equals` rather than reimplemented, because that method IS the oracle this
  gate compares against — so a value too large to be a Double stays false without this file
  knowing the rule.
- **`Decimal(1)` crashed on native** with `dec.parse expects String, got 1` while interp answered
  `1`. Found while fixing the above; `dec.parse` now delegates to `construct`, which already
  accepted String/Int/BigInt/Decimal and refuses a binary float with the reason.
- **`BigDecimal` is now bound on the native front** (`ssc1-lower.ssc0`), an alias of `Decimal` —
  interp bound both spellings, this front only the second, so `BigDecimal(1)` was `unbound global`.

**Still open, and each for a different reason:**

- **interp `BigInt(1) == 1.0` / `1.0 == BigInt(1)` are still `false`.** The fix is the same shape as
  the Int/Double arms already in `infix2Eq` — but `DispatchRuntime.scala` is held by claim
  `split-dispatchlist`, and a one-line edit in a file someone else is working in is not worth the
  collision. Frozen in the gate's `big-gap.ssc` block so it fails the day it is fixed.
- **`BigDecimal(1) == 1.0` stays `false` on native/bytecode, and that is a DESIGN position, not an
  omission.** `PortableDecimal` refuses binary floating-point input in three separate places —
  `toJava` ("binary floating-point input is inexact"), `construct`, and `arith` ("Decimal and Double
  cannot be mixed"). Real Scala answers `true`. Making the lanes agree here means deciding that a
  binary float may be read as a decimal, which is exactly what that module declines to do; it is a
  decision for whoever owns that contract, not something to widen while passing through.

**Measured 2026-08-12** while fixing `lanes-disagree-on-mixed-numeric-comparison`, and found by that
fix's own gate refusing to judge: I froze `BigInt(1) == 1.0` as `false` from memory, the `run-jvm`
oracle answered `true`, and the gate declined to charge the difference to the other lanes.

Nine rows, `run-jvm` (real Scala) against the two wrong lanes:

| expression | jvm / js | interp | native |
|---|---|---|---|
| `BigInt(1) == 1.0` | `true` | **`false`** | **`false`** |
| `1.0 == BigInt(1)` | `true` | **`false`** | **`false`** |
| `BigInt(1) == 1` | `true` | `true` | **`false`** |
| `BigInt(2) == 1.0` | `false` | `false` | `false` |
| `BigDecimal(1) == 1.0` | `true` | **`false`** | `ssc: unbound global: BigDecimal` |
| `1.0 == BigDecimal(1)` | `true` | **`false`** | same |
| `BigDecimal(1) == 1` | `true` | `true` | same |
| `1 == 1.0` | `true` | `true` | `true` |
| `1.0 == 1` | `true` | `true` | `true` |

The last two rows are the CONTROL: they are what the sibling entry fixed, and they show the lanes
are current — this is a different pair set, not the same defect measured again.

**Three distinct defects here, deliberately kept in one entry because one measurement found them
and splitting would lose that:**

- ~~**interp — `BigInt`/`BigDecimal` against a `Double` fall to structural equality.**~~ **FIXED
  2026-08-13**, four arms each in `==` and `!=`, in the same place the Int/Double arms sit. See the
  closing section at the end of this entry — including why they DELEGATE rather than convert.
- **native — `BigInt(1) == 1` is `false`**, which interp gets right. So the v2 kernel's `__eq__`
  needs a BigV arm against IntV as well as against FloatV; only the Int/Float pair was widened.
- **native — `BigDecimal` is not bound at all** (`ssc: unbound global: BigDecimal`). That is a
  missing global rather than a comparison defect, and it is why the native column above has three
  cells that could not be measured. It has to be fixed FIRST or the other rows cannot be gated on
  that lane.

**Not fixed with the sibling on purpose.** The Int/Double fix was three sites across two kernels and
was verified per site; adding a second pair set with a different shape (and a missing global under
it) to the same change would have made it unreviewable. `tests/e2e/mixed-numeric-comparison-gate.sh`
says in its own header that these pairs are excluded and why, so the next person does not add them
to that gate without measuring the oracle first.

**The gate to write** is the same shape as that one — `run-jvm` as the oracle, never `--v1`, because
on this question the interpreter is again one of the wrong lanes.

---

### CLOSING 2026-08-13 — the interp arms landed; what is left is a DECISION, not an omission

The blocker this entry recorded had expired: `DispatchRuntime.scala` was held by claim
`split-dispatchlist` when the note was written, and that claim is gone. Re-measured before touching
anything — `BigInt(1) == 1.0` was still `false` on interp — because a deferral's REASON dates faster
than its subject.

**The arms DELEGATE, they do not convert**, and that is the whole of the design. `BigInt.equals` and
`BigDecimal.equals` already carry the rule, including the part that is easy to get wrong: a value
too large to be represented exactly as a `Double` must stay `false`, which `isValidDouble` decides.
`a.toDouble == b` agrees with the oracle on **every small number**, so it would have passed the gate
as it stood. Double-on-the-left is written `b == a` for the same reason — `Double == AnyRef` does not
route through the wide type's `equals`.

**That hole in the gate is closed with a row, not a comment.** Row 19 is
`BigInt(9007199254740993L) == 9.007199254740992e15` — 2^53+1 against the `Double` it rounds to —
which real Scala answers `false` and the naive implementation answers `true`. It was **verified as a
guard, not assumed to be one**: with the arms deliberately rewritten to `a.toDouble == b` and the
toolchain rebuilt, interp still answered every small row identically and the gate failed on **that
row alone**, printing the diagnosis the message was written to print. Row 19 avoids a `String`
literal on purpose — see the new entry below for why it has to.

Rows 17-18 left the frozen block and joined the all-lanes-agree group (`BIG_TO` 16→19, `GAP_FROM`
17→20), exactly as that block's instructions said to do when a row starts matching jvm.

**Two of the three defects in this entry are now closed** — the native `BigInt(1) == 1` arm and the
`BigDecimal` binding landed on 2026-08-12, this one today. **The third is not a defect anyone should
fix in passing** and it is carried out of here into its own entry, because leaving an entry open on
a question nobody has been asked to answer is how a board rots.

## bigdecimal-against-a-binary-float-is-a-contract-decision-nobody-has-taken — native says `false`, real Scala says `true`

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/mixed-numeric-comparison-gate.sh
     fixed-in: ba75ed5ee2cd95d91a1371563485405a1b89ddf1 -->

> **DECIDED BY THE OWNER 2026-08-15: the lanes answer, and the quiet ones moved.** `BigDecimal(1) ==
> 1.0` is now `true` on native and bytecode, as it already was on jvm/js/int and in real Scala.
>
> **This entry's reason for existing was wrong, and that is what made the decision takeable.** It
> said native's `false` was a design position, because `PortableDecimal` declines binary floating
> point in three places. Measured: all three of those refusals THROW — none of them answers `false`.
> The `false` came from the structural `case _ => false` in `DecimalV.equals` and from
> `eqWidening`'s `l == r` default, i.e. a fall-through nobody chose. And the module already ALLOWS
> the other direction: `(d: DecimalV, "toDouble")` converts through the very `toJava` that refuses
> Double input. The line it actually draws is *refuse where inexactness would be CAPTURED into a
> stored decimal, answer where it is only OBSERVED* — and `==` yields a Boolean, storing nothing.
>
> Implemented by delegating to Scala's `BigDecimal.equals`, exactly as the neighbouring `BigInt`
> arm delegates, because those methods are the oracle this is measured against. Measured on a REAL
> build (`install.sh --dev`, not a cached jar): native, bytecode and run-jvm all answer
> `true / false / true / true` across `==1.0`, the `==2.0` anti-row, the reversed form and `0.1`.
>
> **The frozen block is deleted rather than edited** — that deletion is the record — and rows 12-21
> are now one group.
>
> **A row added to catch a fake caught a different lane.** `BigDecimal("0.1") == 0.1` went in and the
> JS lane refused to run: `cannot mix Decimal and a fractional Number`. So js's `true` on the
> integer row was never agreement — `Number.isInteger(1.0)` is true and a fractional Double throws —
> and the table in this entry recorded a coincidence of values, not a shared rule. Filed as
> `js-refuses-a-decimal-against-a-fractional-double` (v1/runtime/backend/js/BUGS.md); the row is
> withdrawn from the gate until it closes.

**Carried out of `bigint-and-bigdecimal-do-not-widen-against-a-double` on 2026-08-13**, where it was
the one of three defects that is not an omission. Every other row in that entry is now green on
every lane.

    BigDecimal(1) == 1.0        jvm/js true · int true · native/bytecode FALSE

**Why it is not a bug to go and fix.** `PortableDecimal` refuses binary floating-point input in
three separate places — `toJava` ("binary floating-point input is inexact"), `construct`, and
`arith` ("Decimal and Double cannot be mixed"). That is a deliberate, argued position, stated three
times. Making the lanes agree means DECIDING that a binary float may be read as a decimal, which is
exactly what that module declines to do. Whoever owns that contract decides; nobody should widen it
in passing to get a gate green, and the gate says so in the message it prints.

**It is asserted, not merely noted.** Row 20 of `tests/e2e/mixed-numeric-comparison-gate.sh` freezes
each lane's CURRENT answer — jvm/js/int `true`, native/bytecode `false` — so the day the decision is
taken either way, the gate goes red and names this entry rather than letting the change land unread.

**The interp side of this row moved today** (it was `false` and is now `true`), which is why the
freeze is not what it was: this is now a two-lane divergence, not a four-lane one.

## bigint-from-a-string-is-unsupported-on-native-and-bytecode — `i->big: not Int`

<!-- status: fixed
     lane: v2-jvm
     area: runtime
     kind: bug
     gate: v2/conformance/bigint-from-string.coreir
     fixed-in: 58bdeb4c1 -->

### FIXED 2026-08-13 — `i->big` takes the value the fronts actually hand it

Both fronts lower every `BigInt(x)` to `(prim i->big x)` whatever `x` is; they are untyped, so they
cannot route a String to a different prim. The VM and the bytecode generator each accepted an Int
and nothing else. Both now go through one shared shape — Int, BigInt or String, with a binary float
REFUSED rather than truncated and `f->big` named as the operation for a caller who means truncation,
which is the position `PortableDecimal` already takes for `Decimal`.

```text
                                              before                    after
run-ir, i->big of "123456789012345678901234567890"   i->big: not Int    123456789012345678901234567890
   … × 2 (big.mul)                                   —                  246913578024691357802469135780
   … of (int 7), the spelling that always worked     7                  7
```

The multiply is in the fixture for a reason: a construction row alone can be satisfied by a lane
that quietly keeps the string, and arithmetic on the parsed value cannot.

**Two lanes fixed, two lanes measured and filed instead.** `v2/backend/check.sh` on the new fixture:

```text
ok   bigint-from-string   jvm      ← the bytecode lane
ok   bigint-from-string   js
FAIL bigint-from-string   rust     ← no output at all
FAIL bigint-from-string   wasm     ← reuses the Rust generator
```

The Rust generator has no big-integer type: `Lit(CBig(n))` emits `V::Int(${n.toLong}i64) /*big*/`
and its own comment says "lossy but handles common cases". That is a design gap, not a missing arm,
so it is filed as `rust-bigint-is-an-i64-so-a-value-beyond-long-range-cannot-exist` and the harness
SKIPS those two rows with the reason printed — dropping the fixture to keep a column green would
have hidden the defect it exists for.

**Found 2026-08-13** while choosing a value for the gate row above, and it is the reason that row
uses a `Long` literal instead of the obvious spelling.

    println((BigInt("123456789012345678901234567890") == 1.2345678901234568e29).toString)

    jvm       false          ← real Scala
    int       false
    js        false
    native    ssc: i->big: not Int
    bytecode  ssc: i->big: not Int

`BigInt(<Int>)` and `BigInt(<Long literal>)` both work on all five lanes; only the `String`
constructor is missing, and it is the ONLY spelling that can express a value beyond `Long` range.
So on native and bytecode there is currently no way to write a big integer that is actually big —
which is most of the point of the type.

~~**Not a gate row, deliberately.**~~ It was not one when this was filed — the mixed-numeric gate
runs one process per lane over one shared source, and a row that dies on two lanes takes their whole
column with it, so it needed the fix first. **It is one now.** With `58bdeb4c1` built in, all five
lanes answer `false` and the spelling is row 20 of
`tests/e2e/mixed-numeric-comparison-gate.sh`, beside the `Long`-literal row 19 rather than replacing
it: 19 is inside `Long` range and 20 is not, so together they say the equality is right for a value
the `Long` constructor can express and for one only the `String` constructor can.

**Worth recording about the re-measurement, not the fix.** The first re-check said the defect was
still there. It was reading a toolchain built from a commit **16 minutes older than the fix** — the
build stamp settled it in one command, and without that check this entry would have been reopened
against code that no longer exists. Same trap `c6bce74bd` records for the reference front.

## lanes-disagree-on-mixed-numeric-comparison — `1 == 1.0` is `false` on interp, `true` on the v2 runtime, and a TYPEERR through the native front

<!-- status: fixed
     fixed-in: 5fe14e3e1
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/mixed-numeric-comparison-gate.sh -->

**SUPERSEDED by the close below — the native front no longer refuses. Measured 2026-08-11**, while fixing the same defect in v3 (`v3/BUGS.md`
`v3-mixed-int-double-compare`). Three lanes, three different answers, and none of them is Scala's
on every row:

| expression | interp (`run --v1`) | native front (`bin/ssc run`) | v2 runtime (`ssc3 run --bridge`) | Scala |
|---|---|---|---|---|
| `1 < 2.0`  | `true`  | `TYPEERR: cannot unify Int: Int vs Float` | `true`  | `true`  |
| `1 <= 1.0` | `true`  | same TYPEERR | `true`  | `true`  |
| `1 == 1.0` | **`false`** | same TYPEERR | `true`  | `true`  |
| `1 != 1.0` | **`true`**  | same TYPEERR | `false` | `false` |

Two distinct defects share this entry because they are the same question asked of two components:

- **interp orders correctly but compares for equality WITHOUT widening.** `1 < 2.0` is `true`
  while `1 == 1.0` is `false`. That is a wrong answer, not a refusal — the program takes the other
  branch and nothing is reported. This is the one to fix first.
- **the native front refuses mixed comparison at type-check** while the v2 runtime it feeds
  computes it happily: the same `1 == 1.0` is a TYPEERR through `bin/ssc run` and `true` through
  the bridge, which reaches v2 without that checker. So v2's checker and v2's runtime disagree
  about whether the program is even legal.

Not fixed here, and deliberately not decided here either: whichever way it goes, one lane's ANSWERS
change, and this entry was opened from a v3 fix whose claim covered neither v1 nor the native front.

**Arithmetic is NOT affected** — `1 * 2.0`, `7 / 2.0` and the rest widen on interp, native and v2
alike. This entry is comparison only.

---

### FIXED 2026-08-12 — three sites, and the entry's own table was already out of date

**Re-measured first, on a build made from this tree, and two of the four columns had moved.** The
native front does NOT refuse any more — it computes, and computes the same wrong answer as interp.
And js, which the entry never measured, was already correct. So the shape was not "three lanes,
three answers": it was **ordering right everywhere, equality wrong on exactly the lanes that run the
two kernels**, with real Scala and js on the other side.

| | jvm (oracle) | js | interp | native | bytecode |
|---|---|---|---|---|---|
| `1 < 2.0`, `1 <= 1.0`, `2.0 > 1` | true | true | true | true | true |
| `1 == 1.0` | **true** | true | **false** | **false** | **false** |
| `1 != 1.0` | **false** | false | **true** | **true** | **true** |
| `case 1 =>` matching `1.0` | **one** | one | **other** | **other** | **other** |

**THE ORACLE IS `run-jvm`, NOT `--v1`.** Every other cross-lane gate here compares against the
interpreter. On this question the interpreter is one of the wrong lanes, so a gate written the usual
way would have frozen the defect as the reference. `run-jvm` compiles to real Scala and runs it.

**Three sites, and every one of them was the structural `case _ =>` at the bottom of a match:**

1. **interp `infix2Eq`** — listed `BigInt×Int`, `Decimal×Int`, `Decimal×BigInt`, even `List×Vector`,
   and **not** `Int×Double`. Its twin `infix2Ord` has widened `Int×Double` since it was written,
   which is why `1 < 2.0` was right and `1 == 1.0` was wrong on the same line.
2. **interp literal patterns** — `scrutV == litV`, in **six copies** across five fast paths and the
   general matcher. Fixing (1) alone left `lit(1.0)` answering `other` while every other lane said
   `one`. Fixed with ONE helper, `litMatches`, called from all six — this file's own comments record
   the same trap twice already: `compileLit` once had a second inline copy in the nested-pattern
   arm, so a `Lit.Char` fix reached the bare path and not the nested one.
3. **v2 `__eq__`** — `BoolV(a(0) == a(1))`. `arithOp` has carried the correct `(IntV, FloatV)` arm
   all along and was never consulted, because `==` lowers to this prim and not to `__arith__`
   (`ssc1-lower :2832`, deliberately: the VM's `i.eq` is Int-only and `"a" == "a"` crashed on it).
   The right code was adjacent and unreachable. On v2 the comparison and the literal pattern are the
   same prim, so one edit fixed both.

**The literal-pattern behaviour was measured, not assumed.** `(1.0: Any) match { case 1 => "one" }`
answers `one` in real Scala — boxed `==` widens and a literal pattern tests with `==`. Widening
therefore makes pattern matching MORE correct, not merely different.

### The control was scoped to one site, because a whole-fix revert would have proved less

Reverting everything makes every row red and shows only that the gate looks at something. Instead
the **pattern fix alone** was reverted and the toolchain rebuilt with the other two in place. The
gate went red on `int` ALONE, on row 8 ALONE (`one` → `other`), with rows 4-7 green — which is what
shows the literal-pattern row is not redundant with the equality rows, and that the failure names
the right lane. The other two sites have their own before/after from the successive builds.

### What this found and did NOT fix

`BigInt`/`BigDecimal` against a `Double` are wrong the same way, plus `BigInt(1) == 1` on native and
a missing `BigDecimal` global there. Filed as `bigint-and-bigdecimal-do-not-widen-against-a-double`
rather than folded in: a second pair set with a different shape, under a missing global, would have
made this change unreviewable. **That entry exists because the gate refused to judge** — I froze
`BigInt(1) == 1.0` as `false` from memory, the oracle said `true`, and the refuse-to-judge branch
declined to charge my mistake to the other lanes instead of quietly failing them.


## an-objects-defaults-are-taken-from-another-objects-member-of-the-same-name — `B.of(5)` is filled from `A.of`

<!-- status: fixed
     lane: v3
     area: front
     kind: bug
     gate: v3/tests/front/object-defaults-by-receiver.ssc
     fixed-in: e51b86956 -->

**Reproduced 2026-08-11, and declaration ORDER decides the answer**, which is what makes it
certain rather than suspected:

```scalascript
object A:
  def of(a: Int, b: Int = 2, c: Int = 3): Int = a + b + c
object B:
  def of(x: Int): Int = x * 10
def main(): Unit = println(B.of(5))
```

```text
A declared first -> ssc3: 7:28: call to 'B.of' passes 3 argument(s), it takes 1
B declared first -> 50
```

`Lower.fillDefaults` resolves an object member with `sigs.find((n, _) => n.endsWith("." + nm))`
(`v3/src/Lower.scala:1842`) — the FIRST signature in the whole program whose name ends in `.of`,
with the receiver `r` not consulted at all. So `A`'s two defaulted parameters are pasted into a
call to `B`, and `checkArity` — which resolves the receiver correctly, by exact `obj + "." + nm` —
then refuses the call it was handed.

**Found through the prelude, and it is not a prelude bug.** The prelude is loaded before every
program, so its `object Dataset: def of(…)` with SIXTEEN `DsAbsent()`-defaulted parameters was
first in `sigs` and captured every `.of` in every program:
`tests/conformance/companion-case-class-order` was refused with `call to 'B.of' passes 16
argument(s), it takes 1`. That case passes today only because the prelude now loads LAZILY
(`11ac84c43`) and that program never asks for it — the defect is untouched and fires for any two
objects.

**It is the family this compiler keeps producing** — `v3/PRELUDE-CORRECTNESS.md` P-1, P-2, P-4 and
P-6 are all one name resolved without regard to WHOSE it is. Here the owner is written right there
in the receiver and is discarded.

**FIXED in e51b86956: the receiver is consulted first when it is a NAME.** `obj + "." + nm` is then an exact
lookup — the one `checkArity` already did — and the suffix search is the fallback. Both orders now
answer 50, and the fixture `v3/tests/front/object-defaults-by-receiver.ssc` carries a CONTROL as
well as the regression: `A.of(1)` must still fill to `A.of(1, 2, 3)` and print 6, because a "fix"
that simply stopped filling defaults would pass a check that only looked at `B.of(5)`. Both lanes
agree; N unchanged at 206/369 with byte-identical histograms.

**Deliberately NOT fixed, and it is the same defect one level down:** two CLASSES with a same-named
method at different arities. There the receiver is an expression, the method is chosen by the tag at
run time, no name is available at this point, and the suffix search is all there is. It needs more
than a lookup and is not guessed at here.

## statements-after-a-try-catch-run-before-it — RETRACTED, the tree I measured on was half-edited

<!-- status: wontfix
     lane: v3
     area: codegen
     kind: bug
     gate: none -->

**RETRACTED 2026-08-13. THE PREMISE WAS MINE AND IT DOES NOT REPRODUCE.** The same probe files,
byte for byte, now print `A B-handler C D` — the correct order — ten runs out of ten, on both
lanes. It does not reproduce at `c87dc95cf~1` either, so nothing "fixed" it: the ordering I
reported was never there.

**WHAT I ACTUALLY DID.** I ran that probe while `v3/src/Exec.scala` was half-edited — mid-way
through adding `Value.VBytes` and its prims — and minutes after deleting `v3/.jars` wholesale to
force a rebuild. So the measurement came from a tree I was still changing, through a jar cache I
had just removed. I cannot say which of those produced the inversion and I am not going to guess;
what is certain is that three probes agreeing with each other is worth nothing when all three ran
against the same suspect build.

**THE RULE IT EARNS, and it is the one this repository keeps re-learning from the other side:** a
defect is filed from a CLEAN, COMMITTED tree. Every previous version of this lesson is about
measuring performance or N against a stale artifact; this is the same error producing a phantom
BUG, which is worse, because a wrong number gets re-measured and a filed bug gets hunted.

The original report follows, unedited, because the shape of the mistake is the useful part.

---

**Reported 2026-08-12 as reproduced on both v3 lanes, identically**, with the reasoning that nothing
in the tree could see it: the executor and the bridge share the lowering, so no differential
compares the two answers and the front differential compares trees rather than output.

```scalascript
def main(): Unit =
  println("A")
  try
    println(1 / 0)
  catch
    case e => println("B-handler")
  println("C")
  println("D")
```

```text
want   A  B-handler  C  D
got    C  D  A  B-handler          (ssc3 exec AND ssc3 run --bridge)
```

Everything AFTER the `try/catch` runs BEFORE everything up to and including it. Not just the
handler — `println("A")`, which precedes the `try` entirely, prints third.

**No IO is involved.** It was found while probing whether a failed `readFile` is catchable (it is,
on both lanes) and the ordering was wrong in the answer; `1 / 0` reproduces it with nothing but
arithmetic, so it is not the host boundary and not `ExecThrow`.

**Why the suite is blind to it.** `v3/tests/front/try-catch` passes — its `try/catch` is the last
thing its function does, so a reordering that moves the following statements has nothing to move.
The shape that breaks needs statements AFTER the `try/catch` in the same block. That is the fixture
this entry needs, and it is not written here because the fix should choose it.

**Where to look first:** `Cps.split` ends a performing function at the instruction that needs a
continuation, and a `try` is one of them (`v3/src/Exec.scala:756` narrows the caught type and says
that narrowing was tried and reverted). A block split into "before" and "after" halves that are then
run in the wrong order matches the symptom exactly, and would explain why the two lanes agree: both
execute the same mis-ordered IR.

**Not fixed here.** Found under `v3-host-io-bytes`, whose claim is two files and does not include
the CPS transform; filing it is the honest move and taking it would be claim creep. — And that
judgement was right about scope and wrong about the finding, which is the part worth keeping: I was
careful about whose file it was and careless about whether the tree I measured on was one anybody
would recognise.

## multi-effect-marker-is-lost-in-a-bare-ssc — the same program is multi-shot fenced and one-shot bare

<!-- status: fixed
     fixed-in: c926bb3e8
     lane: int
     area: front
     kind: bug
     gate: sbt backendInterpreter/testOnly *MultiEffectBareFileTest* -->

**Measured 2026-08-11 on a freshly built toolchain**, one program, two files that differ only in
whether the code sits inside a ```` ```scalascript ```` fence:

```
multi effect NonDet:
  def choose(options: List[Int]): Int

def program(): Int ! NonDet = NonDet.choose(List(1, 2)) + 10

def main(): Unit =
  val all = handle(program()) {
    case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
    case x => List(x)
  }
  println(all)
```

    fenced   List(11, 12)
    bare     [ERROR] One-shot violation: NonDet.choose resumed more than once

**The declaration is `multi effect` in both.** `Parser.preprocessEffects` injects
`val __multiShot__ = true` for it, `EffectAnalysis` collects the marker, and
`Interpreter.collectScalaTrees` filters code blocks with `Lang.isScalaScript(cb.lang)` — a NARROWER
predicate than the `Lang.isParseable` that `Content.isProgramCode` uses two files away. A bare file's
synthesised block is the suspect, but I did not confirm which `lang` it carries, so that is a lead
and not a diagnosis.

**Why it matters more than the one program:** a `multi effect` silently demoted to one-shot does not
misbehave quietly — it throws where a correct program should run, and the message names a
"violation" by the USER rather than a marker the front dropped.

**Fences are optional** (2026-07-09), so "bare" is not an exotic spelling: it is what every probe and
several gate fixtures in this repository use, and it is the second defect of this shape found in two
days — the other was a keyword-import scanner that only looked inside fences.

**FIXED 2026-08-12 in `c926bb3e8` — AT THE SOURCE.** A bare file is wrapped in a synthetic fence and
that fence said ```` ```scala ````. Measured on the same source: `List(scalascript)` fenced,
`List(scala)` bare. So every consumer asking `Lang.isScalaScript` answered NO for a whole file, and
this entry's one-shot violation was one symptom of that.

**My first fix was in the CONSUMER and Sergiy corrected it.** I had widened `collectScalaTrees` to
`isParseable`. It works, and it is wrong: EVERY filter on `isScalaScript` is wrong for a bare file,
so patching them one at a time is how this shape came back twice in three days — the other was an
import scanner that only looked inside fences. A bare `.ssc` IS ScalaScript; one line at the wrap
fixes all of them, and the consumer-side change was reverted.

**Blast radius measured**, because `Lang.isStandardScala` is a real distinction (`JsGen`,
`ScalaJsBackend`, `WasmGen`) and a bare file stops matching it: core 1162/0, testFast 1609/0,
testSlowJs 154/0, testSlowJvm 55/1 — the same single pre-existing failure, checked against the run
recorded while timing the lanes.

The test asserts both spellings give the SAME answer, not that the bare one gives a particular one:
testing bare alone would pass if the effect machinery broke in a way that made both wrong.


## multi-effect-in-braces-does-not-parse — `multi effect E { … }` is `Undefined: multi`

<!-- status: fixed
     fixed-in: 74c2983f6
     lane: int
     area: front
     kind: bug
     gate: sbt core/testOnly *EffectBracesTest* -->

**Measured 2026-08-11.** `Parser.preprocessEffects` matches

    ^(\s*)(multi\s+)?effect\s+(\w+)(\[[^\]]*\])?(\s+extends\s+[^:]+)?\s*:

— it requires a trailing `:`, so only the INDENTED form is rewritten. The braced form is left
untouched and `multi` survives as a bare identifier:

    multi effect NonDet { def choose(options: List[Int]): Int }
    → [ERROR] [line 1, col 1] Undefined: multi

Both spellings are ordinary Scala 3 syntax elsewhere in this language, and the braced one is what a
reader coming from `object E { … }` writes first. It is a one-line regex change plus the brace-body
scan the indented branch already does by indentation, but it is NOT free: the rewriter consumes the
declaration's body by INDENT, so a braced body needs a different terminator. Recorded rather than
guessed at.

**FIXED 2026-08-12 in `74c2983f6`.** `preprocessEffectBraces` normalises the braced form into the
colon form at priority 49, just before `effects` at 50 — ONE rewriter for both spellings rather than
a second to keep in step. This entry's own caution was the reason: the existing pass consumes a body
by INDENT and appends its own closing brace, so teaching it braces would have meant two body scanners
that must agree forever.

**Narrow on purpose, and asserted:** the `{` must end the declaration line and its matching `}` must
be alone at the declaration's indent. A one-line `effect E { def op(): Int }` and an unclosed brace
come out BYTE-IDENTICAL, so nothing that works today can start failing and the uncovered shapes fail
exactly as they already did.

End to end: `multi effect NonDet { … }` answers `List(11, 12)` — the same as the colon form and as
v3. core 1168/0.


## option-bound-to-a-val-is-not-tracked — an Option lowers correctly inline but not through a val, so getOrElse lands as unwrap_or on a Vec
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-11
     ssc-version: 62bf49839
     repro: repro/option-bound-to-a-val-is-not-tracked.ssc
     fixed-in: 8221c43dd
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**FIXED 2026-08-11.** Two lines differing only by a binding:

```scala
println("inline = " + xs.find(s => s.length > 1).map(s => s + "!").getOrElse("-"))   // compiled
val bound = xs.find(s => s.length > 1)
println("bound  = " + bound.map(s => s + "!").getOrElse("-"))                        // E0599
```

The emitted Rust says it plainly — inline took the Option path, bound took the LIST path:

```rust
… .find(…).map(move |s| { … }).unwrap_or("-".to_string())                       // inline
bound.iter().cloned().map(|s| { … }).collect::<Vec<_>>().unwrap_or("-".to_string())  // bound
```

`isOptionExpr` decided the shape from the EXPRESSION only, and had no case for a bare name, so a
binding erased the type. The walker already keeps `localSeqs` and `localStrings` for exactly this
purpose; there was no `localOptions` beside them. **The reporter diagnosed it to that line** and
said the shape was theirs to name but the fix mine to judge — which is what made this cheap.

**Built on `isOptionExpr` rather than a second list of Option-producing methods.** A copy would
answer differently the day one of them gains a lowering, and this defect IS one representation known
in two places disagreeing. The pre-pass therefore grows its set as it walks, so `val a = xs.find(p);
val b = a` tracks through the second binding too.

**`isOptionExpr` now REQUIRES the context rather than defaulting it**, and that decision found a
bug: the compiler named a twelfth call site (`renderInterpArg`, the arm that prints an Option the
Scala way) that a defaulted parameter would have left blind — the same defect, reachable through
string interpolation instead of `println`.

**The gate reads the emitted TEXT, not a cargo build.** The signal is `collect::<Vec<_>>()` followed
by `unwrap_or`: an Option unwrap applied to a collected Vec is exactly the shape of an Option that
was lost, and it names the defect CLASS rather than one method. Verified against both artifacts —
1 match in the emission before the fix, 0 after — and the gate also fails if NEITHER `getOrElse`
lowers, so a probe that has stopped testing anything cannot read as a pass.

Negative control, on a rebuilt toolchain: with the pre-pass still called but its result emptied,
`build-rust` returns the reported `E0599` again. The first attempt at that control was VACUOUS —
removing the call made the method unused, `-Werror` failed the build, and the `rc=0` I measured came
from the old binary.

This was the last error in rozum's `public-matrix.ssc`: 33 at the start of 2026-08-10, this one at
the end.

## rust-ui-form-six-shapes-behind-one-refusal — SIX gaps closed; the module still refuses, on regex
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: tests/e2e/rust-matches-regex-gate.sh
     fixed-in: 117ef187a -->

`std/ui/form.ssc` sat at `sites = 2` in `--roadmap` and was **nine rustc errors in six shapes** deep
once its refusal was lifted. Six are now fixed; the module is still REFUSED, and the reason is
`matches`, which needs a regex engine this crate does not depend on
([[rust-string-matches-is-not-rust-str-matches]]).

**What was actually wrong, each a gap any module with that shape would hit:**

| | |
|---|---|
| `f.drafts(k)` | Scala's Map APPLY on a struct FIELD, emitted verbatim → `no method named drafts` |
| `drafts(k)` | the same on a LOCAL bound to that field → `expected function, found HashMap` |
| `f.specs.filter(…).head` | `isKnownVecReceiver` stopped at the first combinator → `no field head on Vec` |
| `val d = f.drafts(k); d()` | a local bound to a map apply holds a `Value`; **fifth spelling** of the signal-read defect |
| `vf(sp, d())` | a call through a local alias coerced nothing, because coercion keys off the CALLEE'S NAME |
| `vf(s, drafts(k))` | a signal read and a map apply are `Value` sources `needsAnyCoercion` could not see |

**The two that are worth remembering.** A call through `val vf = validateField` was made *callable*
earlier today, and that turned out to be half the job: `_paramTypes` is keyed by name and `vf` has no
signature, so `Value` arguments went in uncoerced. `localFns` now carries the def each alias NAMES,
and the coercion path resolves through it. And `needsAnyCoercion` knew only two ways for an argument
to be a `Value` — an `anyName` or a call to a `Value`-returning def — while a signal read and a map
apply are two more, neither of them a call to a def.

**What is left, measured on 2026-08-13:** three shapes, all in one def —
`error[E0600]` on `matches` (regex), `String: Pattern`, and `&String == String` inside a `filter`
closure. The last two are ordinary borrow/pattern mismatches; the first is a dependency decision,
because a faithful `String.matches` needs the `regex` crate in every generated Cargo.toml that uses
it. **That decision is not made here.**

An investigation hatch (`SSC_RUST_ALLOW_MATCHES`) was used to see past the refusal and has been
REMOVED: shipping an environment variable that disables a guard against silently-wrong output is how
the guard stops meaning anything.

**CLOSED 2026-08-16 — `std/ui/form.ssc` COMPILES.** The survey moved REFUSED 78 / COMPILES 54 to
**77 / 55**, and that single module is the difference; the baseline is updated in the same commit.

`matches` lowering was the named blocker and it was not the last one. Lifting it exposed four more
rustc errors, each fixed here and each a shape any module can hit:

| | |
|---|---|
| `.filter(|e| e != "")` | `filter` hands the predicate a REFERENCE, so the body compared `&String` with `String`. `find` three lines above already rebound its parameter by clone; the twin did not — a fix that lands on one of a pair and not the other is how this survived. |
| `vf(s, drafts(k)())` | a signal read whose RECEIVER is a map apply is still a `Value`, and `needsAnyCoercion` could not see the nesting — `expected String, found Value`. |
| `ctxSignal(ctx, name, "")` | a parameter typed by the def's OWN type parameter fell through `mapType`'s unknown-name default and became `i64`. It is a `Value` now, in the signature AND in the map the call site coerces against — those two disagreeing is what turned `expected i64` into `expected Value` mid-fix. |
| `m.insert(s.name, f(… s.name …))` | Rust evaluates arguments left to right, so the key MOVED and the value then borrowed it. The key is cloned exactly when the value mentions it. |

The six shapes this entry was opened for were already fixed; these four were behind the one refusal
that remained, which is why the entry could not close until the refusal did.

## coord-release-note-executes-backticks — FIXED: `--note-file`, because a workaround everyone must know is not a fix
<!-- status: fixed
     lane: apparatus
     area: cli
     kind: bug
     fixed-in: 7bcfab999
     gate: tests/e2e/coord-release-note-file-gate.sh -->

A release note is the durable record of a piece of work. It is long, and it is full of
`identifiers in backticks`, because that is how this repo writes about code. Passed inline as
`--note "…`x`…"` the **shell** runs the backticked text as a command and substitutes its output, so
the note that lands is missing words.

**Five times.** Once it ate four words out of a note that had already been pushed, which then had to
be amended and force-pushed.

`--note "$(cat f)"` is safe — command substitution performs no further expansion on what it read —
and that was the workaround. But a workaround every caller has to know about is not a fix: the trap
is still there for whoever does not know. `--note-file <path>` removes it.

Both flags guard each other, an empty file is refused as loudly as a missing one, and the flag is
wired into `scripts/smoke-ci` rather than left as an orphan.

**THE FIRST VERSION OF THE GATE WAS VACUOUS, and that is the part worth keeping.** Its checks
asserted only that the command exited non-zero — but `coord-release` refuses to run outside the
shared checkout, so **every** invocation from a worktree exits non-zero anyway. Driven against three
deliberately broken implementations (readability check deleted, empty check deleted, both-flags
check deleted) it passed all three. The subject was reachable without the thing tested. Rewritten to
assert each complaint by its own MESSAGE, it now fails on all three, each naming its own defect —
and a reverse-order case was added once the experiment showed checks 4 and 4b exercise *different*
guards.

The gate also states what it does NOT prove: byte fidelity of the note is not observable through the
argument parser, so it rests on construction plus a CONTROL check that the same payload passed
inline **is** corrupted. A gate that overclaims is the thing this entry is about.

## rust-string-matches-is-not-rust-str-matches — FIXED by REFUSING: one name, two languages, opposite meanings
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 9da61181d
     gate: tests/e2e/rust-std-survey-gate.sh -->

Scala's `String.matches(regex)` is a **full-match regex test returning Boolean**. Rust's
`str::matches(pattern)` returns an **iterator over occurrences of a literal pattern**. There was no
arm for `matches`, so it fell through the generic member path and emitted
`value.matches(spec.pattern)` verbatim: the same call, a different language, a different answer.

**It was caught by luck, and that is the argument for refusing it.** The only site in std negates
the result, so rustc said `error[E0600]: cannot apply unary operator ! to Matches<'_, String>` and
the module failed loudly. In any context that consumes an iterator — or discards the result — the
same emission COMPILES and is silently wrong, which this backend treats as worse than not lowering
at all.

A correct lowering needs a regex engine this crate does not depend on. Refused by NAME, the same
device as `CollectionOnlyMembers`. One site in std, so the blast radius was checked before the rule
was written rather than after.

**SUPERSEDED 2026-08-16 — it LOWERS now, on the project owner's decision to depend on `regex`.**
The refusal was right for as long as the lane had no regex engine; the decision changed the
premise, not the analysis. What the analysis got right is now in the lowering: Scala matches the
WHOLE string, so the pattern is anchored as `^(?:…)$` — `"abcd".matches("abc")` is FALSE, where
Rust's `is_match` alone would say true — and an invalid pattern PANICS naming the pattern rather
than answering false, which is what `run` does.

The work is a runtime function `_str_matches`, not an expression built at the call site: the
anchoring and the failure behaviour belong in one place, and `renderTerm` is frozen past
HugeMethodLimit — an arm there was measured at +96 bytecodes and refused by the ratchet, which is
why the lowering hangs off `applyNonListCtor` instead.

THE DEPENDENCY AND THE HELPER ARE BOTH CONDITIONAL, and getting that wrong is recorded because it
cost twenty-odd modules: the first version emitted `_str_matches` into every crate while adding
`regex` only to crates that use `matches`, so every module that never mentions the member failed
with `error[E0433]: use of unresolved module or unlinked crate regex` — COMPILES → BADRUST across
std. The helper is now emitted under the same condition as the dependency, and the gate asserts
both halves agree.

Gate: `tests/e2e/rust-matches-regex-gate.sh`.

## rust-local-val-bound-to-a-def-is-not-callable — FIXED: the call-site guard had no set that could hold it
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 9da61181d
     gate: tests/e2e/rust-std-survey-gate.sh -->

`val vf = validateField` then `vf(sp, d())` refused with *"calls `vf`, which this crate does not
define — no def, no intrinsic, no local of that name"*: true of every set the guard knew, and false
about the program.

**The pattern is deliberate and the source says so.** The comment above it in `std/ui/form.ssc`
records why: a thunk invoked later from ANOTHER module's context does not resolve this module's
global names, so the function must be captured as a local first. Every other backend takes it.

**Nothing needed emitting differently.** Rust's `let vf = validateField;` binds the fn ITEM, the
call is ordinary, and a fn item is `Copy`, so capturing it in a `move` closure is free. The only
thing missing was permission.

**The module still does not compile, and that is recorded rather than hidden.** Removing this
refusal revealed **nine rustc errors in six shapes** — `f.drafts(name)` as a Map field then apply,
`.head` on a `Vec` emitted as a field, a `&String`/`String` comparison, `String: Pattern`, an E0308,
and the `matches` collision above. `ui/form.ssc` sat at `sites = 2` in `--roadmap`, the strongest
proximity signal there is, and was six defects deep — **the third time today** that a low site count
did not mean close ([[rust-survey-first-reason-hides-blocker-depth]] says exactly why: a refusal
short-circuits the walk).

What lands is the fix plus the `matches` refusal, so the module stays REFUSED on an honest reason
instead of turning into rustc errors. Depth moved `sites 2 → 1`.

## rust-parameterised-type-alias-is-not-resolved — FIXED: the binder was on the LEFT and both collectors read only the RHS
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: e4c86f5c0
     gate: tests/e2e/rust-std-survey-gate.sh -->

`infix type throws[A, E] = Either[E, A]` was collected by nothing. One collector takes
`type X = [A] =>> body` — a type LAMBDA, binder on the right. The other takes a placeholder alias
`type IntKey = Map[Int, _]`. An alias written the ordinary way, **with its parameters on the left**,
matched neither, so `A throws E` refused as "a type outside R.2" — a type the language plainly has,
declared eleven lines above the def that used it.

It is the same object as a type lambda with the binder moved, so it goes in the same table and **the
β-reducer needed no change at all**. Infix application (`A throws E`) is a new arm, guarded on the
name being a known two-parameter alias so that `!` and every other infix type keep theirs.

**NO MODULE CHANGED CLASS, and the depth columns are how the work is visible at all** — which is
what [[rust-survey-first-reason-hides-blocker-depth]] was built for this morning:

| module | sites | shapes |
|---|---|---|
| `std/error-handling.ssc` | 10 → **5** | 1 → 3 |
| `std/actors.ssc` | 37 → **31** | 7 → 7 |
| `std/dsl/passes.ssc` | 17 → 17 | 3 → **5** |

**Eleven defs stopped refusing.** `ActorRef[M]`, `ActorGroup[M]`, `LocalActorRef[M]`, `Pass[A, B]`,
`RemoteSource[A]`, `throws` and `throwsRaw` are the seven parameterised aliases in std, and they now
resolve everywhere they appear.

**`dsl/passes` going from 3 shapes to 5 is not a regression** and will look like one to anyone
reading `--roadmap` later. The same defs refuse; they simply get FURTHER before doing it, and report
what was behind the alias. A refusal short-circuits the walk, so removing an early one always
reveals more distinct reasons — the count going up is the fix working.

**Why land something that moved no module:** the refusals it leaves are TRUE. `error-handling.ssc`
now says it cannot lower `ArithmeticException`, a `Left(e)` extractor, and the union type
`A | E` — all three correct, all three previously hidden behind a complaint about a type alias that
this lane simply had not collected. This backend's contract is that a refusal is a message the user
can act on, and "your type alias is outside R.2" was not one. Unlike the `Set` mapping recorded in
[[rust-maptype-omits-array-and-set]], this leaves no trap: β-reduction is total, so there is no
half-supported type waiting to emit bad Rust once something else is fixed.

## rust-maptype-omits-array-and-set — FIXED for `Array`, MEASURED and left alone for `Set`
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 49167fb9c
     gate: tests/e2e/rust-std-survey-gate.sh -->

`Array[Byte]` in a struct field refused as "a type outside R.2" while an `Array(…)` EXPRESSION three
lines away lowered fine. **`mapType` was the only decision site in the file that did not group
`Array` with the list family** — the Copy-type test, both list-constructor tests and the
`Any`-variant test all already name it beside `List` and `Vector`. One word, and the reason it is
safe is that the other four sites had already made this choice.

`std/mapreduce/index.ssc` reaches COMPILES. `distributed.ssc` and `shuffle.ssc` moved past it to
their next blockers — an unsupported infix operator and a non-constructor extractor — which is
[[rust-survey-first-reason-hides-blocker-depth]] doing what it says.

**`Set[T]` was implemented, measured and REVERTED, and the reason is not the same as `Array`'s.**
`HashSet<T>` is four lines, and behind it `std/mapreduce/failure.ssc` refuses on `ProcessPartition`,
an actor message the crate does not define — a blocker in a different family entirely. So the type
mapping alone moves nothing. Landing it anyway would leave a TRAP: `pending - failedPart` is
Scala's set difference, this lane has no lowering for it, and `-` is in `ArithOps` — so the moment
someone closes the actor blocker they would get bad Rust instead of the clear refusal they have
today. Only two modules in std use `Set` and both are blocked deeper, so neither the type nor the
operator can be validated here. Recorded rather than shipped.

## rust-struct-field-named-like-a-rust-keyword — FIXED: `pub fn: …` is not a struct field
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 49167fb9c
     gate: tests/e2e/rust-std-survey-gate.sh -->

`case class NamedHandler[A, B](name: String, fn: A => B)` emitted

```rust
pub struct NamedHandler { pub name: String, pub fn: std::rc::Rc<dyn Fn(i64) -> i64>, }
```

— `error: expected identifier, found keyword fn`, with rustc itself suggesting `r#fn`.

`rustIdent` already existed and already knew the reserved list; it was applied to LOCAL names and to
none of the SIX places a field identifier is emitted — two struct declarations, two enum-variant
declarations, the struct literal, and the field read. **Escaping only some of them would be worse
than escaping none**, so all six move together; a declaration that says `r#fn` and a read that says
`.fn` do not meet.

**Zero blast radius by construction, and this time the claim holds:** `rustIdent` is the identity on
every name that is not a Rust keyword, so no existing emission changes at all. (The last "safe by
construction" claim in this backend — that every emitted type derives Clone — had a counterexample
within the hour, which is why this one is stated with its argument attached.)

Unmasked by [[rust-maptype-omits-array-and-set]]: with `Array` refusing, this struct was never
reached. A refusal short-circuits the walk, so a fix reveals whatever stood behind it.

**And it was nearly missed.** My own probe classified the module COMPILES because its regex was
`^error\[` — this failure is `error: expected identifier`, with no bracket. The survey gate's
classifier tests `^error\[E[0-9]+\]|^error: expected|^error: could not compile` and caught it.
Use the gate's classifier, never a hand-rolled one.

## rust-unary-minus-is-an-unsupported-expression — FIXED, and it was the FIRST of five links, not a fix on its own
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 3ae3258ce
     gate: tests/e2e/rust-std-survey-gate.sh -->

`val i = if n < 0 then -n else n` refused the whole def: Scala 3 parses negation as
`Term.ApplyUnary`, and the walker had an arm for `!` and none for `-`. It was the SOLE recorded
blocker of `std/i18n.ssc` and `std/ui/i18n.ssc` — both at `sites = 1` in `--roadmap`, the strongest
proximity signal the corpus has.

**And it was not enough, which is the finding.** A refusal SHORT-CIRCUITS the walk, so what stood
behind it had never been measured. Lowering `-` alone turned both modules REFUSED → BADRUST. What
was behind it, each found only once the one before it was fixed:

| | module | |
|---|---|---|
| 1 | both | `-x` unlowered — this entry |
| 2 | both | `Map.contains` emitting `str::contains` → [[rust-map-contains-emits-str-contains]] |
| 3 | ui/i18n | a MODULE-level signal val read as a call → [[rust-module-level-signal-val-reads-as-a-call]] |
| 4 | ui/i18n | a no-arg closure consuming its capture → [[rust-noarg-closure-moves-its-capture]] |
| 5 | ui/i18n | a closure param at an `Any` parameter → [[rust-closure-param-at-an-any-parameter]] |

Five distinct defects, one visible. `sites = 1` bounded how many DEFS refuse; it says nothing about
how many defects sit inside the one that does — which is exactly the lower-bound caveat
[[rust-survey-first-reason-hides-blocker-depth]] documents, now with a second measurement behind it.

Landed together, because separately each reads as a regression: REFUSED 84 → 82, COMPILES 48 → 50,
BADRUST still 0.

**No coercion on the operand**, deliberately: `ssc_int` is total but would TRUNCATE an `f64`, and a
wrong answer is worse than a refusal. Rust's `Neg` covers `i64` and `f64` exactly. A `Value` operand
would need `impl Neg for Value` and none exists in the corpus — the whole std tree holds one unary
minus, on an `n: Int`. Unary `+` and `~` were looked for in the same pass and do not occur.

## rust-map-contains-emits-str-contains — FIXED: one member name, two source types, and the arm knew only one
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 3ae3258ce
     gate: tests/e2e/rust-std-survey-gate.sh -->

`contains` was lowered by name into `str::contains`, which is right for a String and wrong for a
Map: Rust's `HashMap` has no `contains` at all. `if catalog.contains(locale)` emitted
`error[E0599]: no method named contains found for struct HashMap`.

The fix is NOT in the shared arm — it is a new arm before it, firing only when the receiver's
DECLARED type places it as a Map (`collectLocalMaps`: params by `decltpe`, locals by an RHS that can
only be a Map). A receiver this lane cannot place keeps the str lowering, so nothing that compiles
today moves. Modelled on `localSeqs` / `localStrings` / `localOptions`, which is the same discipline:
read declarations, never infer.

`getOrElse`'s default is read from the LAST argument — `Map.getOrElse(key, d)` has two and the first
is the KEY. `collectLocalStrings` records making exactly that mistake and printing 71 for 8.

## rust-module-level-signal-val-reads-as-a-call — FIXED, and it was the third and fourth spelling of one defect
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 3ae3258ce
     gate: tests/e2e/rust-std-survey-gate.sh -->

`val localeSignal: Signal[String] = signal("locale", "en")` declared beside the defs that read it —
`collectLocalSignals` walks a DEF'S BODY, so it could not see it. Every read lowered to a Rust call
on a `Value`: `error[E0618]: expected function, found Value`.

**A signal reaches a def by four routes and each was found only after the previous was closed:** a
local `val`, a signal in an `Any` FIELD (fixed earlier), a MODULE-level val, and a PARAMETER declared
`Signal[T]` — the last one surfaced in this same module the moment the third was fixed. Both new
routes read a DECLARATION. The module-level collector reuses the local one on a synthetic block
rather than restating what a signal is, so the two cannot drift apart.

## rust-noarg-closure-moves-its-capture — FIXED: "has parameters" was standing in for "is inside a closure"
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 3ae3258ce
     gate: tests/e2e/rust-std-survey-gate.sh -->

`cloneIfMoved` already knew that a captured non-Copy value must be cloned at the USE — an `Fn`
closure may run many times and cannot consume what it captured. Its test for "am I inside a closure"
was `closureParams.nonEmpty`, and **a zero-argument closure binds nothing**, so inside `() => …` the
rule read false. `computedSignal(() => translateIn(catalog, …, key, base))` produced eight
`error[E0507]: cannot move out of catalog, a captured variable in an Fn closure`.

Fixed by threading an explicit `inClosure` through all five closure-body contexts — the two questions
are different and one was standing in for the other.

**A wider fix was tried first and REVERTED, and the corpus is why.** Pre-cloning every captured
parameter into the closure looked safe under "every type this backend emits derives Clone" — false
for exactly one: a parameter whose declared type is a FUNCTION renders as `impl Fn(Ctx) -> i64`,
which carries no Clone bound, and `let body = body.clone();` took `std/ui/component.ssc` out of
COMPILES. `backendRust/test` stayed 278/278 through that; only the corpus gate saw it. The pre-clone
was also unnecessary — cloning at the use is what fixes an `Fn` closure, not cloning into it.

## rust-closure-param-at-an-any-parameter — FIXED: the one binding the Any-boundary rule could not recognise
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 3ae3258ce
     gate: tests/e2e/rust-std-survey-gate.sh -->

`locales.map(loc => signalButton(sig, loc, …))` — `loc` is bound by a closure with no declared type,
so `needsAnyCoercion` saw neither an `anyName`, nor a case class, nor a literal, and passed a
`String` at a parameter declared `Any`: `expected Value, found String`.

Added by NAME-BINDING (`closureParams`) rather than by widening the rule to every argument, which
keeps the narrowing that function documents and exists for: `Value::from` is total, so coercing
everything would be CORRECT but would rewrite every emitted call in the repository, and the goldens
are how this backend is reviewed.

## rust-survey-first-reason-hides-blocker-depth — FIXED: the refusal histogram could not tell "wants this feature" from "mentions it on the way past"
<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     fixed-in: 9cb848476
     gate: tests/e2e/rust-std-survey-gate.sh --roadmap -->

`--reasons` grouped 84 refusing modules by reason and read like a roadmap. **It is not one, and
following it cost a day.** It counts each module ONCE, by its FIRST refusal, so a module that
mentions a gap on its way past six other blockers is indistinguishable from one for which that gap
is the only thing left. The top line said sixteen modules wanted a no-paren collection member; the
feature was built and **zero of the sixteen compiled** ([[rust-no-paren-member-needs-receiver-type]]).

Two more columns per module, and `--roadmap` to rank by them:

| | |
|---|---|
| `sites` | how many defs refuse at all |
| `shapes` | how many DISTINCT reasons those refusals have |

**What it changes immediately.** `--reasons` now prints `mentions` beside `only` — modules with no
other refusal shape — and the two rankings disagree where it matters:

```
  16 mentions    7 only   a no-paren collection member          ← REFUTED above; even the 7 do not compile
  10 mentions    0 only   an unsupported infix operator         ← would have delivered NOTHING
   6 mentions    6 only   a declared extern with no @rust impl  ← every one blocked only by this
   4 mentions    4 only   a call this crate does not define
   4 mentions    0 only   overloading across 5 arities
```

The cluster ranked second by size delivers **zero** modules. The cluster ranked fifth delivers six.

**NEITHER NUMBER IS A COUNT OF WORK REMAINING, and this is the part to keep.** A refusal
SHORT-CIRCUITS the walk — a def is abandoned at its first unlowerable thing, so everything behind it
is unmeasured. `shapes` is a **lower bound**, never a total. What the pair genuinely separates is one
shape at one site from one shape at many, and that separation was validated against independent
ground truth — the rustc error counts measured when the no-paren blocker was actually lowered:

| module | sites | errors when lowered |
|---|---|---|
| `std/fs.ssc` | 1 | 2 |
| `std/litdoc.ssc` | 2 | 24 |
| `std/json-core.ssc` | 2 | 47 |
| `std/yaml-core.ssc` | 2 | 98 |
| `std/content-core.ssc` | 6 | 207 |

Rank by `sites` reproduces rank by errors exactly. All five carry `shapes = 1`, so shapes ALONE
would have called `content-core` as close as `fs` — which is precisely the mistake that was made.

**The columns check themselves before any baseline is written**, because a number nobody asserts is
free to be wrong and these two are read by a human deciding what to build. The self-test asserts
INVARIANTS, not fixture values — `shapes <= sites`, some module with `shapes < sites`, some module
with `sites > 1` — so it does not rot when a module gets fixed. Each fails under a different wrong
implementation, verified by driving it with all four. The two entangled checks are ordered
specific-first: a `sites` capped at 1 fails both, and reporting "dedup is broken" for that defect
would name the wrong thing.

A baseline written before these columns is refused by `--reasons` and `--roadmap` rather than
answered with a table of zeroes. `--reasons` also no longer demands a built launcher it never used.

## rust-no-paren-member-needs-receiver-type — MEASURED and deliberately NOT fixed: the lowering is four lines and buys nothing
<!-- status: wontfix
     lane: v2-rust
     area: codegen
     kind: feature
     gate: tests/e2e/rust-std-survey-gate.sh -->

**The second-largest entry in the refusal histogram, investigated 2026-08-13 and left alone. The
measurement is the deliverable.**

Sixteen modules refuse on `xs.reverse` / `xs.isEmpty` / `xs.nonEmpty` written without parentheses,
where the walker cannot type the receiver. That is **three member names over 114 sites** — 64
`reverse`, 30 `isEmpty`, 20 `nonEmpty` — and nothing else.

**The obvious reading was wrong.** It looks like a demand for receiver-type inference. It is not: a
TRAIT settles it with no type at all, which is the device this backend already uses for `SscInt` and
friends —

```rust
pub trait SscReverse { fn ssc_reverse(self) -> Self; }
impl<T> SscReverse for Vec<T> { fn ssc_reverse(mut self) -> Self { self.reverse(); self } }
impl SscReverse for String    { fn ssc_reverse(self) -> Self { self.chars().rev().collect() } }
```

— and `reverse` is the only one of the three whose two implementations differ at all.

**It was implemented, measured, and REVERTED.** Of the sixteen modules:

| | |
|---|---|
| reached COMPILES | **0** |
| turned into rustc errors | **5** — content-core, fs, json-core, litdoc, yaml-core |
| still refuse, for other reasons | 11 |

And the five are not close: **207 errors in `std/content-core.ssc`**, 98 in `yaml-core`, 47 in
`json-core`, 24 in `litdoc`. Only `std/fs.ssc` is near, at 2. Landing it would have traded one clear
message for two hundred rustc errors in code the user never wrote — exactly what the survey gate
exists to prevent, and it is what said so.

**So this refusal is not covering for a missing feature.** It is holding back modules with a queue
of blockers behind it, and removing it moves work from a column that reads as "not supported yet"
into one that reads as "broken". The four lines are recorded above so nobody has to re-derive them
the day the modules behind them are closer.

**The refusal's message was corrected** — it used to say "on a value this lane cannot type", which
is what sent me looking for an inference pass in the first place.

## rust-no-paren-member-becomes-a-field-silently — an unlowered no-paren member on a List/String is emitted as a Rust FIELD access, so the by-name refusal never sees it
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-10
     fixed-in: 17a140d8e
     gate: tests/e2e/rust-std-survey-gate.sh -->

**FIXED 2026-08-11 in `17a140d8e` by asking about the NAME instead of the receiver.** The first
refusal, added when this entry was filed, was scoped to receivers this lane can type — so a lambda
parameter slipped straight past it and `revParts.reverse` in `std/fs.ssc` still reached rustc as
`attempted to take value of method reverse`. No case class has a field called `reverse` or
`nonEmpty`, so the member NAME is enough to know it is not a field, whatever the receiver is.

The reporter said this half was the load-bearing one and they were right: giving `headOption` a
lowering fixes one name, and the corpus had four more behind it. Several now LOWER rather than
refuse — `nonEmpty`, `head`, `tail`, `reverse`, `sorted`, `distinct`, `isEmpty`, `size`, `length` —
because refusing what a `while xs.nonEmpty do` loop is written with would be honest and useless.

**THE HEADER SAT AT `open` FOR A DAY AFTER THE FIX LANDED, and the way it happened is worth the
line.** The closing edit was made, then discarded by a `git checkout HEAD -- BUGS.md` I ran to
escape the shared-board guard, and then "re-applied" from a copy of the file taken BEFORE that edit.
The re-apply asserted the old header was present, found it, and replaced it with itself — a no-op
that passed its own check. An assertion that the SOURCE state exists proves nothing about the
TARGET state; assert the result instead.

**The half of `no-paren-list-method-becomes-a-field` that giving `headOption` a lowering does not
close.** `rust-list-methods` makes an unlowered method on a known List/String receiver refuse BY
NAME instead of passing through — but it fires on a CALL. Written without parentheses the same
member arrives as a select, slips past the refusal, and becomes a field access, so the next
unlowered one reaches a user as `no field X on type Vec<…>` from rustc rather than as a refusal
naming the method and the receiver.

The reporter asked for exactly this and was explicit that it is the durable half: giving one name a
lowering fixes one name. `lastOption`, `head`, `tail`, `isEmpty` were named as worth checking for
the same reason — flagged by them as a guess, not a report, and unverified here.

**TRIAGED 2026-08-10 — reproduced, and the emitting line found.**

`bin/ssc-tools build-rust repro/no-paren-list-method-becomes-a-field.ssc` fails exactly as
reported: `error[E0609]: no field 'headOption' on type 'Vec<String>'`.

**The line is `RustCodeWalk.scala:1994`**, the catch-all of `renderTerm`:

    case m.Term.Select(qual, m.Term.Name(field)) =>
      renderTerm(qual, ctx).map(q => s"$q.$field")

Any no-paren select becomes a Rust FIELD access. That is right for a case-class field and wrong for
everything else, and nothing between it and the emitted text asks which the receiver is — so the
report's second point is the load-bearing one: whatever `headOption` gets, the REFUSAL has to reach
this line, or the next unlowered no-paren member arrives at a user as `no field X on type Vec<…>`.

**`headOption` itself is small**: on a `Vec<T>` it is `.first().cloned()`, which is an `Option<T>`,
and the `.getOrElse("-")` beside it already lowers to `unwrap_or`. The reporter's guess that
`lastOption`, `head`, `tail`, `isEmpty` want checking is worth taking seriously — they reach the
same line — but each needs its own check, since `isEmpty` is a method on `Vec` and would compile
today while `head` would not.

**NOT FIXED HERE: `RustCodeWalk.scala` is held by the live claim `rust-topval-intrinsic`**, taken
six minutes before I looked. Diagnosing without editing is what could be done honestly; whoever is
in that file next has the line number and the shape.

Filed separately from the report it came in with because one half is fixed (`f8e7f6ba8`) and one is
not, and a single entry cannot carry both statuses honestly.

## rust-last-eight-individual-badrust — three of the eight fixed, and the no-paren refusal finally reaches an untyped receiver
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 17a140d8e
     gate: tests/e2e/rust-std-survey-gate.sh -->

**BADRUST 8 → 5, COMPILES 39 → 42, nothing lost.** `std/auth`, `std/scljet/index` and `std/ui/theme`
compile now. Five fixes, each a different defect:

* **`format!` nesting.** `+` is left-associative and each concat nested one `format!`, so
  `std/ui/theme.ssc` — a stylesheet built from ~50 of them — hit `recursion limit reached while
  expanding format!`. Chains now FLATTEN into one call with N placeholders. The walk descends only
  while the inner node is itself a concat, so `1 + 2 + "x"` keeps `(1 + 2)` as one numeric operand.
* **The built-in `Either` could not become an `Any`.** Every user enum got `impl From<…> for Value`;
  the built-in one was written by hand and missed it — `Value: From<Either<VfsError, i64>>`
  (`std/scljet/index.ssc`). Now generic in both parameters.
* **The auth crate did not build at all.** `argon2 = "0.5"` without features: `password_hash::rand_core::OsRng`
  is re-exported only with `rand`, so `std/auth.ssc` emitted a crate that failed on an IMPORT.
* **Seq inference stopped at a name.** `var uRemaining = units` bound a `List[Int]` PARAM to a local
  and the local was not recorded, because the pre-pass computed param-seqs and local-seqs separately
  and unioned them afterwards — neither could see the other. The param set is the seed now.
* **`nonEmpty`/`head`/`tail`/`reverse`/… lower** on a known seq instead of refusing. Refusing them
  would have been honest and useless: `while xs.nonEmpty do { … xs.head … xs.tail }` is one line of
  `std/scljet/text.ssc`.

**AND THE OPEN HALF OF THE ORIGINAL REPORT IS CLOSED** — `rust-no-paren-member-becomes-a-field-silently`.
The old refusal asked about the RECEIVER, so a lambda parameter, whose type this lane does not know,
slipped past: `revParts.reverse` in `std/fs.ssc` reached rustc as `attempted to take value of method
reverse`. Asking about the NAME settles it — no case class has a field called `reverse` or
`nonEmpty` — so an unlowered collection member refuses wherever it appears and every other field
access passes through untouched.

**That fix was FORCED BY THE GATE, and the sequence is the lesson.** Lowering the common members
removed an early refusal from `std/fs` and `std/litdoc`, which then ran on and broke later:
REFUSED → BADRUST, twice, on modules I was not touching. The survey called it a regression and was
right — the user had traded a message for rustc noise. The name-based refusal is what paid for the
lowering.

**Still BADRUST, five, each a feature rather than a slip:**

| module | error | what it needs |
|---|---|---|
| `std/either`, `std/index` | `E0107`, `E0308` | generic enums — `rust-generic-enum-drops-its-parameters` |
| `std/scljet/text` | `E0308` | a return type declared in ANOTHER module maps to `i64` |
| `std/ui/component` | `E0382` | a `String` read twice needs a clone the multiUse pass misses |
| `std/ui/state` | `E0618` | a `Value`-typed field called as a function |

## rust-emits-a-reference-with-no-rust-side — an unresolved call was emitted as-is, so rustc blamed generated code the user never wrote
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: d0af1dee3
     gate: tests/e2e/rust-std-survey-gate.sh -->

**FIXED 2026-08-11. BADRUST 12 → 8, COMPILES 39 unchanged.** The apply fallback said it outright:

```scala
// Fallback: assume closure parameter or local binding;
// Cargo will reject if not.  See R.2.4.
```

`std/cluster/membership.ssc` calls `joinCluster` and `connectNode`, defined nowhere in the crate, so
the user got `error[E0425]: cannot find function joinCluster in this scope` pointing into generated
Rust. `std/money.ssc` did the same with `RoundingMode.HALF_UP`. It is the disease the extern refusal
two screens up already names: the backend omitting what it cannot provide and letting rustc talk.

**A DOCUMENTED CONTRACT WAS REVERSED, deliberately.** `RustGenR2Test` asserted "call to an unknown
free name passes through to Rust (cargo rejects)", and its own comment explained the reason: R.2.4
widened the apply path so `f(x)` works for a closure parameter, and pass-through was **the flip
side** of not knowing which names were parameters. That cause is gone — the walker now knows a def's
own params, closure params, locals, and the `fn`s a verbatim `rust` fence block defines — so the
flip side has none. The test asserts the refusal now, and that the message NAMES the call.

**THE KNOWN SET HAD TO BE COMPLETED IN TWO ROUNDS, and the second was invisible to the corpus.**
The first version knew locals but not the def's own parameters, so `def apply(f: Int => Int, x: Int)
= f(x)` was refused — four of this backend's own tests, one of them literally named "call to a
closure-typed parameter does not require user-def registration". The std corpus did NOT catch it:
BADRUST fell by six and no module lost COMPILES, because the modules it broke were already failing.
`backendRust/test` caught it. **Two suites, two blind spots** — the corpus cannot see a refusal that
is wrong about code that was already broken, and the unit tests cannot see a defect that only 131
real programs contain.

Then completing the set moved two modules back from REFUSED to BADRUST, and the survey gate refused
to let that pass as progress. Honest count: four modules improved, not six.

**Still BADRUST, and they are individual:** `std/auth` (E0432), `std/either` and `std/index`
(the generic-enum gap, filed separately), `std/scljet/index`, `std/scljet/text`,
`std/ui/component`, `std/ui/state`, `std/ui/theme`.

## rust-std-e0428-duplicate-definition — the built-in Either overrode a real one, and overloads emitted twice
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 6e31c4b17
     gate: tests/e2e/rust-std-survey-gate.sh -->

**FIXED 2026-08-11.** `error[E0428]: the name X is defined multiple times`, four modules, TWO causes:

* **The fallback `Either` overrode the real one.** It is added "once per crate when a source
  references `Either[L, R]`" — including in `std/either.ssc`, whose whole subject is that type, and
  `std/index.ssc`, which re-exports it. A fallback that overrides a real definition is not a
  fallback; it now yields when the source defines its own.
* **Overloading has no Rust.** `def text(s: String): ToolResult` beside
  `def text(s: String, mimeType: String = "text/plain"): ResourceResult`, and `extension` methods
  declared on several receivers, emitted two `pub fn text`. Refused by name, because it is a
  language gap rather than a codegen slip, and mangling is worse: every call site would have to
  agree on the mangled name and the one this lane cannot see is a call from another module.

**THE REFUSAL HAD TO BE SCOPED TWICE, and each miss cost working modules.** Written against
DECLARATIONS it took NINE modules out of COMPILES — `std/cluster/*`, `std/geo`, `std/nodes` — every
one of them compiling perfectly well with an unreachable overload in the file, because the emitter
walks a reachability graph. Scoped to REACHABLE defs it still cost three: `extern def
getCurrentPosition()` and `extern def getCurrentPosition(opts)` are both reachable and both render
to NOTHING, since an extern with no `@rust` has no Rust side. It is now counted on what actually
EMITS, which is the only thing rustc ever sees.

**The gate did not catch that; reading the baseline diff did.** It asserted only that BADRUST must
not grow, and BADRUST was falling while nine modules stopped compiling. It now guards the COMPILES
column too — a gate satisfiable by giving up capability is the failure it exists to prevent.

## rust-std-e0425-name-not-found — an extension member emits a body reading a receiver that was dropped
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 6e31c4b17
     gate: tests/e2e/rust-std-survey-gate.sh -->

**FIXED 2026-08-11 for the extension half.** `extension (u: Uuid) def asString: String = u` emitted
`pub fn asString() -> String { u }` — `error[E0425]: cannot find value u in this scope`. A parsed
statement list is FLAT: `collectDefs` deep-collects `Defn.Def`, so an extension member arrives as an
ordinary top-level def and the receiver is simply gone.

The CALL site already refuses these ("reads `shout` on a String and the rust backend has no lowering
for it"), so the definition is not merely broken, it is **uncallable**. Refused by name for the same
reason.

**Narrow on purpose: only a member whose body READS the receiver.** One that does not compiles
today, and refusing it as well would cost a module its COMPILES status for dead code that hurts
nobody. Measured before choosing — exactly one COMPILES module declares an extension.

**Still open in this class**, and they are individual rather than concentrated: `std/money.ssc`
(`RoundingMode.HALF_UP`, an unknown external constant emitted rather than refused) and
`std/cluster/membership.ssc` (`joinCluster` called but never emitted).

## rust-given-method-type-params-render-empty — a `given` method typed by a trait type parameter emits `e: ` and does not parse
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 189b8b111
     gate: tests/e2e/rust-std-survey-gate.sh -->

**FIXED 2026-08-12, and the cause was NOT what this entry guessed.** The parameter is not typed by a
trait type parameter at all — `std/monaderror.ssc` declares `def raise[A](e: Unit): Option[A]`, and
**`mapType` renders `Unit` as the EMPTY string**, which is right in return position (it is how "no
`-> T` clause" is expressed) and wrong everywhere else.

Two lines reproduce it with no typeclass in sight:

```scala
def ignore(u: Unit): Int = 1        // pub fn ignore(u: ) -> i64 {  ← does not parse
```

Rust's unit type is `()`, so that is what a parameter gets, at BOTH parameter renderers — the
ordinary def and the `given` method are separate code paths and fixing one would have left the
other emitting the same unparseable text.

**The guess in the original entry cost nothing only because I re-derived it from a probe** instead
of trusting it: `E` was a plausible reading of `def raise[A](e: E)` two declarations above the one
that actually reaches the corpus.

`std/index.ssc` emits `pub fn raise(&self, e: ) -> Option<i64> { None }` — `error: expected type,
found )`. The parameter is typed by a type parameter of the enclosing trait, `mapType` has no case
for it, and the given renderer emits the empty result bare instead of refusing.

Found under `rust-generic-enum-drops-its-parameters` once the enum half stopped masking it. Two
things are wrong and only one is the feature: the missing case is the feature, and emitting an EMPTY
type is the defect — a type that could not be mapped must refuse, the way every other unmappable
thing in this backend does, rather than producing Rust that does not parse.

## rust-generic-enum-drops-its-parameters — `enum Either[L, R]` emits `pub enum Either`, and call sites still say `Either<i64, i64>`
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     fixed-in: 11fa2d923
     gate: tests/e2e/rust-std-survey-gate.sh -->

**FIXED 2026-08-12. `std/either.ssc` compiles.** The declaration carries its parameters now, and
five separate things had to agree before that meant anything:

1. **Both enum surfaces.** `enum Either[L, R]` and `sealed trait Either[A, B]` + case classes are
   two renderers, and `std/either.ssc` uses the second. Fixing only the first left it emitting a
   parameterless declaration against parameterised uses — the identical bug, one renderer over.
2. **The variant field types need the parameter names in scope**, or a field typed `L` falls to the
   `i64` default. They go through `enumNames`, which maps a type name to itself.
3. **An applied user type** — `Either[String, Int]` — is a `Type.Apply` and had no case at all, so
   the USE fell to the default while the declaration was now generic: the same mismatch, inverted.
4. **`Into::into`, not `Value::from`, in the lift.** The bound an impl can state is
   `L: Into<Value>`; `Value::from(l)` asks for `Value: From<L>`, the other direction, which std's
   blanket impl does not give. `std/coroutine.ssc` said so as `the trait bound Value: From<Y> is not
   satisfied` — and it had been COMPILING before this work, so that was a regression I introduced
   and the survey caught.
5. **The hardcoded `Left`/`Right` arms** emit the built-in Either's TUPLE variants. A source that
   defines its own gets STRUCT variants, and `Either::Right(f(b))` was then `expected value, found
   struct variant`. They yield to a real definition now.

**STILL OPEN, one layer down, and filed as `rust-given-method-type-params-render-empty`:**
`std/index.ssc` emits `pub fn raise(&self, e: ) -> Option<i64>` — a `given` method whose parameter is
typed by a TYPE PARAMETER of the trait, which maps to the empty string. Same family, different
renderer.

**A measurement note worth keeping:** that module classifies differently depending on the working
directory the build runs from — COMPILES from the repo root, this error from a temp dir, because
relative imports resolve to a different module set. The survey runs from a temp dir, which is the
number that counts, and it is the one recorded.

A type-parameterised enum renders with its parameters silently DROPPED, so the declaration and its
use sites disagree: `error[E0107]: enum takes 0 generic arguments but 2 generic arguments were
supplied`. Surfaced in `std/either.ssc` and `std/index.ssc` once the duplicate-`Either` defect above
stopped masking it.

**Refusing it was tried and REVERTED, and that is the useful part.** `std/coroutine.ssc` declares
`enum Step[Y, R]`, never references it with type arguments, and compiles — so a refusal took a
WORKING module away in order to be more honest about one that was already failing. The survey gate's
COMPILES arm is what said so. The real answer is to emit the parameters, which is a feature: the
variants' field types reference `L`/`R`, so the type renderer needs the parameter names in scope,
and every def over such an enum needs generics too.

## rust-std-corpus-badrust-column — 25 of 131 std modules emit Rust that rustc rejects; two shapes account for most of it
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: feature
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-11
     ssc-version: 1334bea8f
     repro: repro/std-rust-survey.tsv
     fixed-in: b5cf2cc4f
     gate: tests/e2e/rust-std-survey-gate.sh -->

**BOTH SHAPES FIXED 2026-08-11. Measured on the corpus, which is the only number that means
anything here: BADRUST 25 → 17, COMPILES 31 → 39, REFUSED unchanged at 75.** Eight modules moved
BADRUST → COMPILES — `cluster/index`, `scljet/values`, and six of the eight `std/ui` — and NOTHING
moved into REFUSED, so no capability was traded away for the count.

**`E0277` — an enum could not become an `Any`.** A case-class struct has had
`impl From<Name> for Value` since `Any` first mapped to `Value`; an enum never did. So
`case class RouteEntry(body: List[TkNode])` emitted `Value::from(x.body)` and rustc said
`Value: From<TkNode> is not satisfied`. `enumLift` now emits the same `Value::Obj(ctor, fields)`
convention the struct uses. Fixing it immediately surfaced the next missing coercion — `Value` had a
`Tuple` variant that nothing could produce from a Rust tuple — so `From<(A, B)>` through `(A, B, C, D)`
joined the total-coercion set.

**`E0562` — a field cannot hold `impl Trait`.** `mapType` said so itself: *"R.2.4 only renders
function types at parameter positions; stored closure values (`Box<dyn Fn>` boxing) land in a later
slice."* This is that slice. A function type in FIELD position renders `Rc<dyn Fn…>` — `Rc`, not
`Box`, because the type derives `Clone` and `Box<dyn Fn>` is not `Clone`.

**Three consequences, each found by building rather than by reading, and each worth the record:**

1. **Neither `Rc` nor `Box` gives `Debug`,** so a closure-bearing type cannot derive it — and
   dropping the derive CASCADES: `RouteEntry` holds `Vec<TkNode>` and derives `Debug` too, so rustc
   then blamed a type with no closure in it. Every holder would have to drop it, and every holder of
   those. One generated `Debug` at the source ends the cascade; it prints the constructor name,
   which is honest, because the fields it would print are partly closures.
2. **A stored callback needs `+ 'static`.** `Rc::new(key)` on a bare `impl Fn` parameter is `E0310`,
   "may not live long enough". The closures this lane emits are `move |…|` over owned captures, so
   the bound states what was already true. Two goldens assert the parameter text and were updated
   with that reason written beside them.
3. **THREE renderers make fields, not one** — `renderEnumCase` (a `Defn.Enum` variant),
   `renderClassCtor` (a `sealed trait` + case class variant) and `renderClass` (a struct). Fixing
   the first two left `std/cluster/index.ssc` still failing on a `pub start:` field, and fixing the
   struct then re-ran the whole `Debug`/`From` consequence for structs. The position had to be
   threaded, not post-processed: a function nested in `List[A => B]` boxes too.

**A closure lifts into `Any` as `Value::Unit`.** A closure has no data representation, and refusing
to generate `From` for any type containing one would have taken `Any` away from eight modules for a
field nobody reads back.

**What is left, honestly:** 17 modules still emit bad Rust — `std/ui/form` (six error classes),
`std/ui/state` (`E0618`), `std/scljet/text` and `std/scljet/index` (`E0277` of other shapes), and
twelve more. The two shapes that were CONCENTRATED are gone; the rest are individual.



**A measurement nobody here had taken, and it reproduces.** rozum put all 131 `std/**/*.ssc` through
`build-rust`. Re-run independently with this gate's own classifier, the totals match their prose
exactly:

| | | |
|---|---|---|
| REFUSED | 75 (57%) | the backend says it cannot lower this — CORRECT, a coverage gap |
| COMPILES | 31 (24%) | lowers and rustc accepts it |
| BADRUST | 25 (19%) | emits Rust rustc REJECTS — the only column that is a defect by itself |

**Read the first two before the third.** 57% refused is the lane telling the truth about what it has
not implemented: `extracts X with 1 args` (11), `unsupported infix operator` (9), `Array[Byte]` (3),
`Parser[T]` (3). That is a roadmap.

**ONE CORRECTION TO THE REPORT, measured rather than argued.** It says the nine `E0562` modules are
"one fix, eight modules". The fix removes one SHAPE from eight modules; it unlocks **one**. Built
each of the nine and read the error classes:

| module | error classes |
|---|---|
| `std/cluster/index.ssc` | `E0562` only ← the one a fix unlocks |
| `std/ui/containers, display, input, layout, nodes` | `E0562` + `E0277` |
| `std/ui/state` | `E0562` + `E0277` + `E0618` |
| `std/ui/typography` | `E0562` + `E0277` |
| `std/ui/form` | `E0562` + `E0277` + `E0308` + `E0599` + `E0600` + `E0609` + `E0618` |

So there are **two** concentrated shapes, not one, and the second is the wider:

* **`E0562` — `impl Trait` in a field type.** `case KeyedForNode(key: Int => String, …)` emits
  `key: impl Fn(i64) -> String` in an enum variant, which Rust allows only in argument and return
  position. `mapType` says so itself: *"R.2.4 only renders function types at parameter positions;
  stored closure values (`Box<dyn Fn>` boxing) land in a later slice."* The later slice is this.
  Not a one-line swap: `#[derive(Debug, Clone)]` on the enum rejects both `Box<dyn Fn>` (neither)
  and `Rc<dyn Fn>` (no `Debug`), and the construction and call sites need the boxing too.
* **`E0277` — `Value: From<UserType>` is not satisfied.** `Value::from(x.body)` where `body` is a
  user enum. Present in all eight `std/ui` modules and absent from `std/cluster/index.ssc`, which is
  exactly why fixing `E0562` alone unlocks one module.

**The pattern the reporter drew is the most useful thing here.** Inside `std/ui`, three modules
compile — `primitives`, `offline`, `webauthn` — and nine do not. `primitives` is the one their real
program imports, and it builds a 1.7 MB binary that serves. **Coverage has been following use, one
program at a time**, which is why an outsider with one real program found five defects in two days.

**Gated as they suggested**, and the assertion is theirs: not "everything compiles" but "the BADRUST
column does not grow". A module moving REFUSED → BADRUST is a regression — a clear message replaced
by a rustc error in code the user did not write; BADRUST → REFUSED is progress and the gate asks you
to record it. Runs on the four-hourly job, not the push path: 131 modules is ~5 minutes against a
943 s whole-suite budget.

**The reporter's own correction is worth keeping**, because it is the shape of half the false
verdicts in this repo: their first pass reported 131/131 failing, an artefact of counting the EXIT
CODE — a library module has no `main`, so `build-rust` exits non-zero with "expected binary not
found" AFTER compiling it cleanly. They caught it themselves on the grounds that a 100% result is
likelier to mean a broken measurement than a broken world.

## no-paren-list-method-becomes-a-field — headOption on a list is emitted as a Rust field access, so it lands as 'no field headOption on Vec'
<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     reported-by: rozum / claude-opus-5
     reported-at: 2026-08-10
     ssc-version: 7fea7a711
     repro: repro/no-paren-list-method-becomes-a-field.ssc
     fixed-in: f8e7f6ba8
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**FIXED, and confirmed by the reporter.** `f8e7f6ba8` ("getOrElse is the UNWRAP, so it does not keep
the Option") split `getOrElse` out of the `isOptionExpr` case it shared with `map`/`flatMap`.
Re-measured 2026-08-11 on a fresh build: `build-rust repro/no-paren-list-method-becomes-a-field.ssc`
exits 0 and the binary prints `control = 2` / `defect = bb`, matching `ssc run`. rozum verified the
same independently before this was routed.

**What the report was.** `kept.headOption.unwrap_or(…)` — `headOption` written WITHOUT parentheses,
so it arrives as a select rather than an apply, and this lane emitted it as a Rust FIELD access on
the `Vec`: `error[E0609]: no field 'headOption' on type 'Vec<String>'`. `kept.length` on the line
above compiled, so list methods as such were fine.

**The reporter's own account of how they got there is worth keeping**: they read
`hit.map(...).getOrElse(...)` on a List, concluded `getOrElse` belongs to Option, and "fixed" their
source with `.headOption` — which made it WORSE (4 errors, rustc then calling the receiver
`Option<String>`), and that is what sent them to a minimal repro instead of a second guess.

**ONE HALF OF THIS REPORT IS NOT CLOSED, and it is filed separately as
`rust-no-paren-member-becomes-a-field-silently`**: `rust-list-methods` makes an unlowered method on
a known List/String receiver refuse BY NAME, but it fires on a CALL. A no-paren member access still
slips past it and becomes a field, so the next unlowered one lands on a user as
`no field X on type Vec<…>` rather than as a refusal naming the method. The reporter asked for
exactly that and it is the durable half — `headOption` having a lowering fixes one name.

Routed here rather than to a backend board because the Rust backend has no `BUGS.md`, and
labelled `lane: v2-rust` because that is what the enum calls this backend (see
`rust-lane-rejects-try-catch`, same reasons).

## v3-extension-unblocks-two-files-into-a-lane-DIFF — both files pass now, and the two causes were unrelated

<!-- status: fixed
     fixed-in: f77b5c46f
     lane: multi
     area: runtime
     kind: bug
     gate: v3/corpus-report.sh -->

**BOTH FILES PASS ON BOTH LANES, measured 2026-08-16**, and neither was fixed by anything aimed at
them — which is the useful part, because the entry read as one defect and was two.

`indent-config-format` matches its expectation on the executor and the bridge, and the lanes agree
byte for byte. `indent-block-statements` was the last exec-side DIFF and its cause was
`v3-exec-has-no-string-repetition`: the file indents with `" " * depth`, every other lane including
v3's own bridge answers that, and only the executor refused with `Mul on String ab and Int 3`. The
entry's own reading — "the executor's method table has no `map` for the value they build" — was the
symptom of the run stopping early, not the cause.

**The trade this entry recorded stands and improved.** Accepting `extension` moved N 171 → 194 and
bought two DIFFs; both are now closed, and the corpus is at 237 / 370 with two DIFFs left, both of
them effects cases with their own entries.

**What it says about reading a DIFF:** the pair was filed as one because they appeared together, and
they had nothing in common beyond the commit that made them visible. Unblocking a construct
surfaces whatever was behind it, and "behind it" is not one thing.

## interpreter-fast-lane-not-on-the-push-path-yet

<!-- status: fixed
     fixed-in: 53dbd0a2d
     lane: int
     area: build
     kind: apparatus
     gate: .github/workflows/ci.yml (interpreter-fast) -->

**The lane exists and is GREEN; registering it is one line and is blocked on a live claim.**

`backendInterpreter/testFast` — added 2026-08-10 in `2c52c99bd` — is **1608 tests, 0 failures, 163
seconds**. The suite it comes from runs NOWHERE: absent from `ci.yml`, absent from smoke, whose own
comment records that this directory's coverage is the corpus lanes alone. That is how thirteen red
tests sat unnoticed until the lanes made a verdict possible:

  - eleven were one stale path from the `v1-runtime-std` rename, across five files and three suites
    (`6e7f915ef`);
  - two were a disagreement about where an import resolves, between the corpus and a test suite
    (`23fc17895`).

**What is owed**, and it is deliberately not done here: a row in `scripts/smoke-ci.ssc`, e.g.

    Check("v1/runtime/backend/interpreter", "interpreter-fast",
          "sbt", List("-batch", "backendInterpreter/testFast"), 420000),

**Blocked:** `scripts/smoke-ci.ssc` is held by the live claim `smoke-budget-self-scaling` — and that
is the right owner for it anyway. 163 s is a real addition to a suite already over its 750 s budget
locally, so whether it goes in smoke (every push, locally) or in `ci.yml` (tier 2, dedicated runner)
is a budget decision, not a mechanical one.

**The three shell-out lanes are for deliberate runs and want their own time**, measured on this
host: `testSlowJs` 35 s / 154 tests, `testSlowJvm` 228 s / 55, `testSlowCross` >1600 s (property
tests over 74 generated programs). `testSlowJvm` has 1 failure and `testSlowCross` has not been run
to completion — neither is investigated here.

**CLOSED 2026-08-10 — in TIER 2 of `ci.yml`, not in smoke, and the correction matters.**

This entry proposed a smoke row. That was under-informed: smoke is **27 checks in ~157 s** and is the
per-push signal — adding a 163 s lane would have DOUBLED it. `ci.yml`'s own tiering comment spells
the sizes out; I proposed the row before reading it.

**And "runs nowhere" was too strong.** The suite does run — in `sbt-test`, which is sharded 4 ways
and **dispatch-only** (tier 3). So the real state was "only when somebody asks", which is why
thirteen reds could sit there, and the fix is a cadence rather than a first appearance.

`interpreter-fast` is a tier-2 job: nightly, PR and dispatch, like `validate` and `conformance`.
1608 tests, 0 failures, 163 s against that tier's ~14 min.

**It installs a JDK and nothing else, deliberately, and that is also a test of the heuristic.** The
fast lane is DEFINED as the tests that do not shell out, so it should need neither `node` nor
`scala-cli`. If a suite `build.sbt` classifies as fast quietly starts a process, this job is where it
surfaces — on a nightly, naming itself, instead of inside somebody's unrelated change.


## std-ui-fetch-plugin-two-red-tests-nobody-filed

<!-- status: fixed
     fixed-in: 6e7f915ef
     lane: int
     area: runtime
     kind: bug
     gate: sbt fetchPlugin/test -->

**Measured 2026-08-10 on a CLEAN tree** — `git status` empty, nothing of mine in it — while
verifying a different entry in the same suite:

```
FAIL  std/ui data imports rowEditAction for public rowEdit helper
FAIL  std/ui data exposes remoteTable composing fetchRowsSource + dataTableView (nested rowsPath)
```

`sbt fetchPlugin/test`: **14 passing, 2 failing.**

**NO ENTRY ON ANY BOARD NAMES EITHER**, which is the reason this exists rather than a diagnosis: a
suite that is red with nobody watching cannot tell the next person whether they broke something. I
did not investigate the cause — the tests are about `std/ui/data.ssc` exposing `rowEditAction` and
`remoteTable`, and both are named precisely enough to start from.

**FIXED 2026-08-10 — and they were not two, they were eleven.** Both read
`repoRoot / "runtime" / "std" / "ui" / "data.ssc"`, a path the `v1-runtime-std` rename removed: `.ssc`
sources moved to `std/` and the plugin projects to `v1/runtime/plugins/`. The same stale spelling
killed seven tests in `MoneyStdTest`, two in `MoneyCrossBackendTest`, and one probe each in
`StableSpiEnforcementTest` and `SingletonFailoverTest` — three suites, one cause.

`fetchPlugin/test`: 2 failures → **0**. `backendInterpreter/testFast`: 9 → 2.

**Not a blanket replace, and that mattered:** four of the five want the `.ssc` tree, but
`StableSpiEnforcementTest` scans for `*-plugin` DIRECTORIES and wants `v1/runtime/plugins`. Replacing
every occurrence identically would have left it passing over an empty list — a gate checking nothing.

**Why nobody saw it:** none of those suites runs anywhere — absent from `ci.yml` and from smoke. I
filed this entry yesterday having stumbled on the two while verifying something else, and only found
the other nine after splitting the interpreter suite into lanes that finish (`2c52c99bd`).


## v3-ci-gates-job-has-never-been-green — one red gate hid five others on every run

<!-- status: fixed
     kind: apparatus
     lane: v3
     area: build
     gate: .github/workflows/v3.yml
     fixed-in: d85fa7aed -->

**Measured 2026-08-09**: `gh run list --workflow v3.yml --limit 60` lists **48 runs and 0
successes**, back to at least 2026-08-08T14:47. The `v3 gates` job fails at its second gate on every
single run.

**Root cause, and it is two defects that compound.**

1. The job never registers the UniML front. `v3/exec-gate.sh` and `v3/front-gate.sh` both carry
   `.uniml-only` fixtures — programs v3's own parser refuses — and both go RED rather than skip when
   the front is absent. That refusal is deliberate and correct (a gate that goes green with fixtures
   unrun reports less than it claims), and on CI the front was *always* absent: the
   `Register the UniML front` step exists only in the second job, `front-capability`, whose comment
   reads "SEPARATE JOB because this one costs an sbt build". **That cost was never priced.** On run
   31321407887 the step takes 0.8 min, against a `gates` job that died at 2.2.
2. A failing step ends the job, so `bridge`, `parity`, `front`, `front report` and both `jit gate`
   steps **have never run at all**. Their results were not red; they were absent, on every run since
   each was added. A gate registered in a job that dies before reaching it is a gate nobody has.

Found while wiring `v3/jit-gate.sh` into this workflow (claim `ssc3-jit`): the new steps came back
`skipped`, which is what sent me to look at the job rather than at my own change.

**Fix (this commit):** register the front in the `gates` job — it is needed by two of its gates, so
the separation was not a saving — and put `if: ${{ !cancelled() }}` on every gate step so one red
gate reports without silencing the rest. The job is still red if any gate is red; what changes is
that all of them now say so.

**Confirmed 2026-08-09.** Run
[31321746483](https://github.com/sergey-scherbina/scalascript/actions/runs/31321746483) on
`d85fa7aed`: `success`, with all thirteen steps of `v3 gates` green — `bridge`, `parity`, `front`,
`front report` and both `jit gate` steps reporting a conclusion of their own **for the first time**,
and the second job green as before. The previous 48 runs had none of that.

## v3-handle-has-no-return-clause — `effect-multishot` runs and answers 0

<!-- status: fixed
     lane: v3
     area: front
     kind: feature
     gate: v3/corpus-report.sh
     fixed-in: ad741e91b
     confirmed: yes -->

**CLOSED 2026-08-23, AND ITS `Done when` IS MET IN SUBSTANCE RATHER THAN LITERALLY — which is worth
one paragraph, because the literal reading describes a mechanism that does not exist.**

The clause itself has worked for a while: `js-effect-multishot-long-fold` answers **204 on both v3
lanes** and `bench/corpus/effect-multishot` answers 10 290, where this entry recorded 0. What stayed
open was the second half — "declares **v3** among its `backends:`", so that the corpus runner becomes
the acceptance test and the divergence stops being invisible by construction.

**Nothing reads `v3` in `backends:`.** `v3/corpus-report.sh` asks that field about **`v2`**, because
the default lane executes through the v2 bridge and inherits its differences; `tests/conformance/run.sh`
does not read the field at all. Adding the token would have looked like a fix and changed nothing —
and requiring it would have been worse: no case in the corpus lists `v3`, so EVERYTHING would have
become excluded and the report would have read as healthy.

`ad741e91b` implements the intent instead: on `--exec` the report holds v3 to every case except one
declaring `known-red: v3`, because that lane borrows nothing from v2. This case is therefore held
now, and answers 204. The blind spot this entry was really about — a wrong answer counted as somebody
else's — is closed, and the report says the two lanes measure different populations rather than
printing two numbers that look comparable.


**THE CENSUS IS DONE, 2026-08-12, and it licenses the refusal.** `listOut` was instrumented to
REPORT rather than refuse and the whole corpus was run:

| | |
|---|---|
| swallows across 369 programs | **12** |
| at `flatMap` | 12 — `zip`, `++` and rendering: **zero** |
| distinct programs | **one** |
| other `flatMap` users | 9, none affected |

The one program is `tests/conformance/js-effect-multishot-long-fold.ssc`, and it **answers 0 where
its checked-in expectation is 204** — so the only thing the swallow supports today is a wrong
answer, and this is the SECOND program of that shape besides `effect-multishot`. It declares
`backends: [int, js]`, so v3 is not among its lanes and the corpus verdict never looked: the
divergence was invisible by construction rather than by accident.

**THE CONFORMANCE NUMBER WAS HALF THE ANSWER.** `bench/corpus` is a SEPARATE set that
`corpus-report.sh` does not read, and a first pass over it reported zero for the wrong reason: bench
files keep their work in a `def` the harness calls, so running one directly parses it and never
executes the body. With a driver that actually calls `workload`, `effect-multishot` shows **600**
swallows and answers `0`; `effect-oneshot` shows none and answers 881 correctly. So the full census
is 12 + 600 occurrences over **two** programs, and both were already wrong.

**So nothing working relies on the swallow.** Nine other `flatMap` users in conformance and 33 other
bench programs never reach it. Tightening changes no correct program and turns two silent wrong
answers into loud ones.

**LANDED as a refusal in `listOut`, and the price is stated.** A/B on one tree:
`PASS 206 → 206`, `DIFF 4 → 4`, `UNSUPPORTED 154 → 154`, **`CRASH 3 → 4`**, `LANE-EXCL 2 → 1`. One
case moved from LANE-EXCLUDED to CRASH — `js-effect-multishot-long-fold`, which declares
`backends: [int, js]` so v3 was never asked about it, and which was quietly answering 0. By the
report's ordering CRASH is worse than DIFF, so the scoreboard reads one worse; what actually changed
is that an unattributed silent wrong answer became an attributed diagnosable one. It cannot be
bucketed UNSUPPORTED instead: the condition is a run-time one, unknowable at compile time — which is
precisely why the checker does not subsume the refusal.

**NO NEW LANE DIVERGENCE, checked rather than assumed.** I-3 is about a program that agrees across
lanes beginning to disagree. Both affected programs already disagree — v3 says 0 where the others
say 204 — so this changes the shape of an existing divergence and creates none. v2's runtime still
swallows; that is filed with these numbers behind it rather than guessed at.

**THE MEASUREMENT NEARLY REPORTED A MEANINGLESS ZERO, which is worth more than the number.** The
census first wrote to stderr — and `v3/corpus-report.sh` runs each case as
`java … run-ir "$irf" 2>/dev/null`, discarding child stderr. It would have reported 0 occurrences
across the corpus, and a 0 there reads exactly like "nothing relies on it", i.e. like permission to
tighten. A count that cannot distinguish "never happens" from "never observed" is not evidence. It
writes to a file now, and the path was proven in both directions BEFORE the corpus run: a one-line
program whose `f` returns an Int produces three rows, and `List(1,2,3).flatMap(x => x * 2)`
silently returns an EMPTY list of length 0 with the census off.

**CORRECTION 2026-08-11 — v3 ALREADY ACCEPTS A RETURN CLAUSE, and the diagnosis below is stale on
that point.** Run on a rebuilt v3, both fronts:

    val all = handle(program()) {
      case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      case x => List(x)
    }

gives **`List(11, 12)`** — the clause parses, lowers and binds the computation's VALUE, which is what
it is for. So "v3's grammar has only operation arms" is no longer true, and the gap this entry is
named for is not where it says it is.

**Sergiy confirmed the design 2026-08-11: the explicit return clause, not an implicit lift.** The
reason, recorded because it outlives the decision: under an implicit rule a wrong program keeps
looking right — the type fits by construction and nothing asks the author what the answer IS — while
a written clause does not compile until they say.

**What actually blocks `effect-multishot`, and it is not the grammar.** The fixture writes no
clause, so it still answers 0; and it cannot simply be given one, because the LANES DISAGREE about
what the clause means — see `v1-handle-return-clause-binds-the-Return-wrapper`, where v1 binds `x`
to `Return(11)` instead of `11`. The bench corpus is shared, so writing the clause today would make
one row print two answers. Order: fix v1's binding, then the fixture can carry the clause.

**Measured 2026-08-09**, after `multi effect` was accepted and multi-shot continuations landed.
`bench/corpus/effect-multishot.ssc` now parses, lowers and RUNS on both fronts — and returns **0**,
which there is no reason to believe.

Its handler is `case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))`, and the
fixture's own prose says what that means: *"the List-monad handle"*. In that formulation `resume`
returns the HANDLED type — a `List` — so `flatMap` concatenates the branches. In v3 `resume(opt)`
returns the rest of the computation's own value, an `Int`, because `handle` has **no return clause**:
nothing lifts the final value into the handler's answer type.

So `flatMap` receives a non-list per element, yields nothing, and `foldLeft` over the empty result
gives 0. **A wrong answer that looks like an answer**, and it comes from two things at once:

  - `handle(e) { … }` cannot express `x => List(x)`. Scala 3 spells it `case x => …` beside the
    operation arms; v3's grammar has only operation arms.
  - `flatMap` on a list treats a non-list element as EMPTY rather than refusing. That is the second
    silent step, and it is what turns a missing feature into a plausible number.

**No oracle was available**, which is why this is filed rather than fixed on a guess: the v1
toolchain refused (stale build) and the v2 bridge has no effects, so nothing could say what the right
answer is. The fixture's prose says jvm, js and rust all run it, so those lanes are the oracle when
one is buildable.

**MEASURED SEPARATELY 2026-08-09, and it narrows this entry rather than widening it.** A handler
written IN THE LANGUAGE works, so the gap is only the answer-type lift, not effects generally:

```
effect Logger:  def log(msg: String): Int
def runLogger(body: => Int): Int = handle(body) { case log(msg, k) => k(0) }
```

With that prepended, `bench/corpus/effect-pure.ssc` runs and returns **49995000** — the sum 0..9999,
checked against `9999*10000/2` rather than against itself. So `effect-pure` is blocked on a LIBRARY
that provides `runLogger`, not on the language: a user can define the effect, the runner and a
by-name parameter and it composes, with the perform inside a `while` loop.

**Two ways out, and the choice is the language's:** give `handle` a return clause, or define the
continuation to return the handled type implicitly. Until then `effect-multishot` should not be
counted as a passing row — it is counted here instead.

**Gate named 2026-08-14: `tests/conformance/run.sh`** — and the fix has TWO halves, because the
census above found the divergence was *invisible by construction*.

**Done when** `tests/conformance/js-effect-multishot-long-fold.ssc` declares **v3 among its
`backends:`** and answers **204** there rather than 0. Declaring the lane is not a formality: the
case says `backends: [int, js]`, so the corpus verdict never looked at v3 and a wrong answer read as
a pass. Adding the lane first makes the corpus runner the acceptance test; fixing `handle` without
it leaves the same blind spot for the next divergence.

## rust-backend-two-tests-red-on-origin-main-after-the-Any-boundary-work

<!-- status: fixed
     fixed-in: c19cfbc0c
     lane: v2-rust
     area: codegen
     kind: bug
     gate: sbt backendRust/test -->

**Mine, and the reporter's attribution was right.** Both came from `3ac30f018`. One was a golden,
one was not, and the second is the one that mattered:

- the golden — the `Any` coercion-trait import went in the file HEADER, so every emitted crate
  carried it, hello-world included. Wrong on its own terms: `effectsImport`, three lines above,
  shows the shape — an import is emitted when the program needs it. Now conditional, decided from
  the emitted BODY rather than a flag threaded through the builders.
- **a real regression: omitted default arguments were being DROPPED.** `greet("hi")` emitted
  `greet("hi")` instead of `greet("hi", "!", false)`. The call-argument coercion did
  `argTerms.zip(renderedArgs)`, and `renderedArgs` is LONGER exactly when defaults have been filled
  in — `zip` truncates to the shorter list silently. Rust has no default parameters, so that is
  wrong code, not a cosmetic diff.

`backendRust/test` 271/271. The reporter also noted nobody was watching this suite: it is not in the
smoke set, so a red here survives every push. Touching this backend means running it.

**Measured 2026-08-09 on a CLEAN worktree** — my own change removed and restored — because two red
tests inside work I was doing is exactly the shape that gets misattributed:

```
FAIL  end-to-end golden — hello-world crate file set             (RustGenCodeWalkTest)
FAIL  a call omitting trailing default params fills the defaults (RustGenWebToolkitTest)
```

`backendRust/test` is otherwise **268 of 271**. Both diffs show the block beginning *"The `Any`
boundary"*, and `3ac30f018` — *"rust: `Any` can hold a case class — option A"*, landed the same day —
adds 11 lines mentioning it. That makes it the cause by strong circumstance. **I did not run the
suite at its parent to prove it**, and say so rather than assert it.

**Unowned:** the `rust-any-as-value` claim was released, so nobody is watching these. A golden test
is red on `origin/main` right now, which means the next person to touch this backend inherits a
suite that cannot tell them whether they broke something.

## v2-conformance-uses-FIXED-temp-filenames — a green suite goes red when a sibling runs it

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: v2/conformance/check.sh
     fixed-in: 4e03966b5 -->

**FIXED 2026-08-09, one line.** `check.sh` scoped its LOG directory by PID and then wrote **857
scratch paths under about 200 FIXED names** in the directory every process shares:
`${TMPDIR:-/tmp}/tp.rs`, `k51p.coreir`, `so-bin`, `hm-fact.js`. Two runs at once overwrote each
other's files between the write and the read.

`TMPDIR="$LOGDIR/tmp"` redirects all 857 at once, because every one of them already reads
`${TMPDIR:-/tmp}`. Rewriting 200 names by hand would have been the risky way to do the same thing.
It sits under `LOGDIR` so it inherits that directory's kept-not-cleaned policy, which puts the
scratch beside the stderr logs a failing run already points the reader at.

**PROVED BY RUNNING TWO SUITES CONCURRENTLY, before and after** — the experiment the defect is
about. A sequential re-run could never show it:

| | run A | run B |
|---|---|---|
| before, concurrent | **rc=1, 635 ok** | **rc=1, 636 ok** |
| after, concurrent | rc=0, **645 ok** | rc=0, **645 ok** |

645 is exactly the sequential baseline, so the fixed pair is indistinguishable from two runs that
never met. The failures before it are worth quoting, because not one of them looks like a race:

```
FAIL hm-length     got [] want [3]
FAIL hm-map JS     got []
FAIL hm-map Rust   got [(rustc err)]
FAIL json Rust     got [(rustc err)]
```

**It cost a wrong verdict the day before, and only a re-run caught it.** Testing a one-arm change to
`v2/src/Runtime.scala`, the suite came back rc=1; reverting gave rc=0 — green-without, red-with,
which reads as conclusive. Re-running the SAME change gave rc=0 and 645. The repository had 92
worktrees and three live claims that hour, one of them `rust-type-mapping`, on the very lane whose
failures appeared.

**The general rule this is an instance of:** before believing any red/green flip on a suite that is
supposed to be deterministic, run the failing side twice. A deterministic suite disagreeing with
itself is telling you the harness is not deterministic — and the failure will always appear to
indict the change you happen to be holding, because that is the difference you have in mind.

## coord-path-overlap-matches-a-SIBLING-directory-that-merely-shares-a-prefix

<!-- status: fixed
     lane: multi
     area: build
     kind: bug
     gate: .githooks/pre-push --self-test
     fixed-in: 365daa818 -->

**FIXED 2026-08-09.** The three copies of `case "$p_path" in "$q_path"*)` are now one function,
`path_rel a b` → `same | inside | outside`, which requires the separator: equal, or followed by `/`.

**It now has a `--self-test`, and that is the larger half of the fix.** An untested comparison is
how a two-character omission survived in three places at once. Eight cases: both incidents
(`front-capability` vs `front`, `core-bench` vs `core`), the containment it must still catch so the
fix is not "admit everything" (`v3/tests/front/x.ssc`, `v2/src/Runtime.scala`), equality, the
reverse direction (`a/b` vs `a/b/c` is outside — backwards here would refuse every related pair),
and `v3x` vs `v3`, the one-segment case a naive prefix-strip gets wrong.

**Proved to DISCRIMINATE:** restoring the bare-prefix match turns exactly three cases red —
`front-capability`, `core-bench`, `v3x` — and leaves the other five green.

**A placement trap worth keeping.** The self-test first went in below the hook's stdin loop and
HUNG: a pre-push hook reads its refs from stdin, so anything after that loop waits forever when run
by hand — and anything after the `[ -n "$local_tip" ] || exit 0` under it would exit 0 without
running, a self-test that passes by never executing. It sits above both now.

**STILL OWED, and blocked.** The self-test is not registered in `scripts/smoke-ci.ssc`, so it exists
but does not run on every push — the state that left `inbox-route` unexercised for a day. That file
is held by the live claim `scljet-tuple4-instrumentation`. One line:
`Check(".", "claim-overlap", ".githooks/pre-push", List("--self-test"), 60000)`.

## v3-exec-gate-ssc-differential-compared-the-EXECUTOR-WITH-ITSELF for six days

<!-- status: fixed
     lane: v3
     area: build
     kind: apparatus
     gate: v3/exec-gate.sh
     fixed-in: 60345372e -->

**FIXED 2026-08-08.** `v3/exec-gate.sh` line 73 read

```sh
via="$(v3/ssc3 run "$f" 2>/dev/null)"      # labelled "the v2 bridge"
```

and `ssc3 run <file>` is `exec "${SSC3[@]}" exec "$2"` — **v3's own executor**. The bridge needs
`run --bridge`. So the entire `.ssc` half of a gate whose first line of documentation is *"every
fixture runs on BOTH lanes … two independent implementations agreeing is evidence neither can
produce alone"* ran ONE lane and printed `(both lanes agree)` for every case. The `.ssir` half was
never affected: it uses `$V2 run-ir` and is a real differential.

**It was not written wrong — it ROTTED, and the commit that broke it never named it.**

| | |
|---|---|
| `2d270276a`, 2026-08-02 | wrote the `.ssc` differential. `ssc3 run` MEANT the bridge then. |
| `5cdf4a3c5`, 2026-08-07 | *"ssc3 run is v3's own runtime — self-sufficiency, on a measurement"*. Touched `v3/ssc3` and `v3/parity-gate.sh`, **which uses the same command** — and not `v3/exec-gate.sh`. |

One caller of the repointed command was updated and the other was missed. From 08-07 the gate
asserted the opposite of the truth.

**A second assertion in the same file had the same defect and made a POSITIVE claim on it:**
`bridge_mut="$(v3/ssc3 run v3/tests/mutual-recursion.ssc)"`, printing *"the bridge completes it too
now — mutual recursion is a loop in the IR, not a lane trick"*. Its own comment says *"It went red
the moment that changed, which is what an expiring assertion is for"* — but it could not go red for
the bridge, because it never ran it. Measured with `--bridge`: **the claim is true**. A check that
cannot fail is not evidence that the thing it claims is so.

**What the corrected gate sees — the first real reading of this differential since 08-07:** of 54
`.ssc` fixtures, **52 agree and 2 diverge**. Both are declared with a `.bridge-diverges` file naming
their entry, so they are COUNTED and printed rather than hidden, and the gate now goes red BOTH
ways: an undeclared divergence fails, and a declared one that starts agreeing fails as a stale
declaration.

## v3-bridge-cannot-apply-a-lifted-capture — `app: not a function`

<!-- status: fixed
     lane: v3
     area: codegen
     kind: bug
     gate: v3/exec-gate.sh
     fixed-in: 1499eab06 -->

**FIXED 2026-08-08, one line in `BridgeV2.MkClos`.** A closure's captures are emitted as a v2
`(let (e0 e1 …) (lam k …))`, and every capture expression read the executor frame at the SAME
de Bruijn depth `sh`:

```scala
val capBinds = caps.map(c => read(c, sh)).mkString(" ")          // before
val capBinds = caps.zipWithIndex.map((c, i) => read(c, sh + i))  // after
```

v2's `let` binds **sequentially** — `Runtime.appendOne(e, v)` per rhs — so the i-th expression is
evaluated in an env already extended by the i before it, and `Local(i)` is `env(env.length - 1 - i)`.
Capture i was therefore off by i. Capture 0 was always right, which is the whole reason this
survived: **one capture cannot expose it.**

```
def mk(g: Int => Int): Int => Int   = x => g(x)          one capture   — worked
def comp(f, g): Int => Int          = x => f(g(x))       two captures  — app: not a function: 2
def tri(f, g, h): Int => Int        = x => f(g(h(x)))    three         — off by two
```

**Measured before and after, both lanes**, and the fixture reverted to watch it fail: with the shift
removed the bridge prints `42/42` and dies on the third line exactly as reported; with it, both
lanes print five 42s. `v3/tests/front/captured-function-parameter.ssc` now carries the three-capture
case as well, because a two-capture fixture pins the case that broke rather than the rule.

**It existed the whole time and nothing could see it.** The program that exposes it only began
lowering on 2026-08-08 (`290e784b6`, `freeVars` dropped the callee), and `exec-gate.sh` was
comparing the executor with itself until the same day — so the defect needed BOTH a new front
capability and a repaired differential before anything could report it. There is exactly one
`(let (` emission site in `BridgeV2.scala`, so this has no twin.

## v3-uniml-jar-goes-stale-and-breaks-the-kernel-build — it does not, and the real cost is a Scala compile error printed OVER the diagnostic

<!-- status: fixed
     lane: v3
     area: build
     kind: apparatus
     gate: v3/uniml-classpath.sh --check
     fixed-in: a5a6e3e2f -->

**Fixed 2026-08-10 in `a5a6e3e2f`, and the fix is a DIAGNOSIS rather than a rebuild** — the driver
still may not run sbt, so it cannot repair the classpath; what it can do is stop misreporting it.
`uniml-classpath.sh` stamps a digest of UniML's own sources beside the classpath and gains
`--check` (0 current, 1 stale, 2 nothing cached). The driver captures the compile's stderr instead
of letting it through and asks why ONLY when that compile already failed: a stale stamp gets a
three-line message naming the cause and the command that fixes it, with the compiler's output
suppressed as the symptom it is, while a FRESH stamp still gets the compiler's full output, because
then it is a real error about this tree.

The check sits on the failure path deliberately: 0.09 s over 152 files, and `front-diff.sh` runs the
driver once per fixture, so paying it on every invocation would be a real cost for a rare condition.

Proven in both directions before landing: a planted stale stamp with a broken `uniml.cp` produces
the named cause, the fallback and exit 0 with no compiler noise; a planted TYPE ERROR in
`UniFront.scala` with a fresh stamp still shows the compiler's error in full. Both files restored
byte-identical.

**The entry's own second half — the misattributed gate — is what this cost me most.** Every fresh
worktree opened for SSC3-J0, J1, J2, J1c and J1d began with `exec-gate` or `front-gate` RED on a
sibling's fixture, and each time the first hypothesis was "my change broke it". That is five more
occurrences on top of the two recorded below, all of the same shape: the checkout is stale and the
diagnostic points at the code.

### Original report (superseded 2026-08-10)

**THE TITLE IS THE FIRST FRAMING AND IT IS WRONG, kept because the correction is the entry.**
`v3/.jars/uniml.cp` is not invalidated when UniML's sources change, so a tree whose classpath
predates a change to `SpikeAst` no longer satisfies `v3/uniml/UniFront.scala`. Seen twice on
2026-08-09, in a worktree and then in the shared checkout, after `Param.byName` landed:

    -- [E008] Not Found Error: v3/uniml/UniFront.scala:327:54
    value byName is not a member of …SpikeAst.Param

I read that as "the kernel does not compile" and stopped to rebuild. **Reproduced deliberately
before filing** — `printf '/nonexistent/stale-uniml.jar' > v3/.jars/uniml.cp` — and it is not what
happens. `ssc3 front` answers `front: v3  available: v3` and **exits 0**; `ssc3 ast <file> v3` exits
0 and is correct. The fallback works. What a stale classpath actually does is print the compiler's
full error to stderr on every invocation, where it sits ABOVE the tool's own one-line
`the uniml front is present but did not compile — using v3's own front`.

**The cost is a masked diagnostic, and it is not hypothetical.** Reading the first stderr line of
`ssc3 run bench/corpus/typeclass-monoid.ssc`, I recorded "the build is broken" and threw away a
measurement round. The run had in fact refused correctly, and after rebuilding, the same command
gives the real message: `` bench/corpus/typeclass-monoid.ssc:10:1: `given … with` ``. Same exit
code, same refusal, different first line — so anything reading stderr top-down, a person or a
script, gets the compiler's complaint instead of the program's.

**IT ALSO FAILS A GATE, which is worse than the noise above and was measured hours later on the
same day.** After rebasing SSC3-7i onto `origin/main`, `front-gate.sh` went RED on
`annotation-own-line` — `the expression 'spike.error' is outside …` — a fixture a sibling had
added 21 minutes earlier together with its fix IN UNIML (`ba23280b2`). The rebase brought the
fixture and the UniML SOURCE; `v3/.jars/uniml.cp` still pointed at a jar built before it, so the
fix was absent and the new fixture could not pass. Rebuilding the classpath turned the gate green
with no code change.

**So the failure mode is a RED GATE ATTRIBUTED TO THE WRONG CHANGE.** I was one step from reading
that as a regression in my own commit. The rule that follows is narrow and worth stating: a rebase
that moves `uniml/` requires `v3/uniml-classpath.sh` before any gate result is believed —
`git log --oneline ORIG_HEAD..HEAD -- uniml/` answers it in one command.

**Two things to decide, both small:** route the second front's compile output to a log file and
keep only the one-line notice on stderr; and give `uniml.cp` a staleness check against the sources
it was built from, so the notice says *rebuild* rather than leaving it to be inferred.

Distinct from `v3-exec-gate-and-front-gate-report-the-WORKING-TREE-and-blamed-a-sibling-for-it`
below, which is about a classpath being ABSENT and the silent front swap that follows; this is a
classpath being PRESENT and unusable, where the swap is the same but the noise is new.

## v3-exec-gate-and-front-gate-report-the-WORKING-TREE-and-blamed-a-sibling-for-it

<!-- status: fixed
     lane: v3
     area: build
     kind: apparatus
     gate: v3/exec-gate.sh v3/front-gate.sh
     fixed-in: 0b791be7b -->

**FIXED 2026-08-08.** Both gates now detect whether the uniml front is registered, refuse to run a
`.uniml-only` fixture without it, and say so in a line that names the cause and the command that
fixes it. Neither reports it as a failing fixture any more.

**What was wrong.** `v3/tests/front/object-nested-class.ssc` is marked `.uniml-only`: v3's own
parser refuses a `case class` inside an `object`, so only the second front can read it. Which
fronts are registered is a fact about the WORKING TREE — the uniml front exists only after
`v3/uniml-classpath.sh` has been run there, and a fresh worktree has not run it. `Front.default`
then falls back to `v3`, the fixture produces nothing, and both gates scored it as:

```
FAIL object-nested-class — executor [] bridge [] expected [at 0 of hello/at 2 of bye]
```

A failing FIXTURE. The gates were indicting the code for the state of the checkout.

**Measured both ways, by moving `v3/.jars/uniml.cp` aside and back** — `uniml_available()` is
`[ -s "$UNIML_CP_FILE" ]`, so that toggles exactly the condition:

| | front-gate | exec-gate |
|---|---|---|
| uniml present | **GREEN, 60 cases** | **GREEN, 61 cases** |
| uniml absent, before | RED — `FAIL object-nested-class` | RED — `FAIL object-nested-class` |
| uniml absent, after | RED — names the front and the command | RED — names the front and the command |

**I PUBLISHED A WRONG VERDICT ON A SIBLING'S COMMIT BECAUSE OF THIS, and that is the reason this
entry exists.** Twice on 2026-08-08 I wrote in a commit message that these gates were red on
`origin/main` from `c71b58e28`, "verified by stashing my change and seeing the identical failure".
The stash control could not have said anything else: the missing front is reachable WITHOUT my
change, so removing my change left the failure exactly where it was. **A control that cannot
distinguish the two states is not evidence, and this one was structurally incapable of it** — the
same lesson as a probe whose subject is reachable without the thing tested. `c71b58e28` is not
implicated; it hoists a `case class` out of an `object` in the projection and its own fixture
passes.

**Two changes, and the second matters as much as the first.** The gates also sent stderr to
`/dev/null`, so "the front refused this program" and "the program printed the wrong thing" were the
same observation — an empty string. The FAIL line now carries the first line of stderr. Had it done
so, this would have read `← ssc3: … case class inside an object` and been diagnosed in seconds
instead of costing a wrong report on a shared board.

**Red rather than skipped, deliberately.** A gate that goes green with fixtures unrun reports less
than it claims — the shape already recorded as a floor on the good number not being a guard. The
message says explicitly that nothing in the diff under test can fix it, so a reader is not sent
hunting through their own change.

Related: [`front-diff-cannot-finish-when-the-second-front-does-not-compile`] is the same family on
the third gate, fixed the same day by probing each declared front once before the corpus.
`front-report-gate.sh` was checked and is NOT affected — its comparison is already guarded by
`if [ "$auto" = "uniml" ]`. `parity-gate.sh`, `bridge-gate.sh` and `corpus-report.sh` do not run
these fixtures.

## v3-refuses-a-default-argument-inside-an-enum-case

<!-- status: fixed
     lane: v3
     area: front
     kind: bug
     gate: v3/front-gate.sh
     fixed-in: fd29eaeb4 -->

A default on a parameter of an `enum` case is a parse error, while the same default on a `def` or a
`case class` parameter works:

```
$ cat /tmp/c.ssc
enum Shape:
  case Circle(radius: Int = 1)
def main(): Unit = println(1)

$ v3/ssc3 exec /tmp/c.ssc
ssc3: /tmp/c.ssc:2:27: expected ')', found =
```

`Ast.Param` already carries `default` and documents both spellings — *"`def f(x: Int = 5)` and
`case class C(x: Int = 5)`"* — so this is the enum case's own parameter parser not reaching the
shared one, not a missing representation.

FOUND WHILE FIXING A DIFFERENT DEFECT IN THE SAME FILE. `tests/conformance/default-params.ssc`
reported `unknown name 'x'`, which was a default referencing an earlier parameter (fixed). The file
does not lower yet because of THIS second, unrelated failure at line 18 — worth stating, because a
corpus case that stays refused after a fix reads like the fix did not work.

**FIXED 2026-08-09 in `fd29eaeb4`, one call.** The `case class` field loop has always called
`parseDefault`; the enum-case loop went straight from the type annotation to `,` or `)`. Same helper,
so there is no second implementation to drift.

This entry's guess was right and is worth confirming rather than quietly replacing: *"this is the
enum case's own parameter parser not reaching the shared one, not a missing representation."* It was
exactly that.

    reference 1/3/8    v3 executor 1/3/8    v3 bridge 1/3/8

**It closes `tests/conformance/default-params.ssc`, which two fixes had been chasing.** That case
failed at line 13 — a default referencing an earlier parameter, fixed in `bf2cbfc06` — which moved
its first failure to line 18, this. It now lowers and matches its expected output exactly.

The fixture covers what a two-case example would miss: a parameterless case beside ones with
parameters, and a default referencing an EARLIER parameter of the same case. **Proved to
discriminate:** removing the `parseDefault` call returns the original `expected ')', found =`.

front-gate GREEN (67), exec-gate GREEN (66).


## v3-has-no-scala-style-import — its module system is markdown links, and 4 corpus cases use the other spelling

<!-- status: fixed
     lane: v3
     area: front
     kind: feature
     gate: v3/front-gate.sh
     fixed-in: 946afab39 -->

NOT A DEFECT, recorded so it is not filed as one a third time. `import actors.Overflow` reports
`unknown name 'import'` because v3's parser has no `import` keyword at all — `grep '"import"'
v3/src/*.scala` is empty. v3 composes modules through MARKDOWN LINKS, read by
`Loader.importsOf`, whose own comment says *"the names a file imports … link text is ignored, path
is what matters"*.

So the message is misleading rather than wrong: the word is parsed as an ordinary name because
nothing claims it. Four corpus cases use the Scala spelling — `actors-bounded-mailbox`,
`actors-process-info`, `curried-extern-import`, `std-process-import` — and 0 of the 4 lower.

Two ways out, and the choice belongs to whoever owns v3's module story: teach the parser the
keyword and map it onto the link mechanism, or refuse it BY NAME so the diagnostic says v3 uses
links instead of leaving a reader to guess from `unknown name`.

**FIXED 2026-08-09 — the first way.** `import actors.Overflow` now means what
`[Overflow](std/actors.ssc)` means: the LAST segment is a member and the rest is the module, `.*`
is the same with the member unnamed, and `std/` is prepended unless already there
(`Loader.scalaImportTarget`). Anything relative keeps using a link, where a `../` can be written
and a dotted path cannot. Fixtures `scala-import{,-doc-fence,-selector,-bare}` in
`v3/tests/front/`, run by `front-gate.sh`, which was already wired into `.github/workflows/v3.yml`.

**THE RULE IS FENCE-AWARE, and that is the whole of its difficulty.** Ten lines in the corpus and
the standard library begin with `import`; three are prose, and four more sit in ```` ```text ````
documentation blocks — `std/actors.ssc` and `std/nodes.ssc` each *show* `import actors.ChildSpec`
in a Quick-start example. Only 3 are code. Every one of the doc-fence four names its OWN file, so
reading them as imports would have been a harmless no-op that no test could have caught;
`scala-import-doc-fence.ssc` therefore names a module that DOES NOT EXIST, and setting
`inCode = true` makes it fail with `cannot find the import 'std/no_such_module.ssc'` — checked,
because a control that has never been seen to fail is not evidence.

**THE REFUSAL MOVED OUT OF THE PARSER, and the measurement is why.** `import a.{b, c}` and a bare
`import a` are refused — Tier 0 has no namespaces, so a selector list promises something the
language cannot keep. Put in `Parser.scala` that refusal covers ONE front: measured on these
fixtures, the uniml front DROPPED the line and printed, while v3's own front refused — invariant
I-3, on the default lane. It now lives in `Loader.importsOf`, the one place both fronts go through.

**WHAT THE ENTRY GOT WRONG:** "0 of the 4 lower". `std-process-import` was ALREADY accepted before
this change — verified in a control worktree at `origin/main`, not assumed. The other three do not
turn green either, and not because of imports: two now load `std/actors.ssc` and stop at
`extension [M](ref: ActorRef[M])`, a type-parameterised extension v3's parser does not take
(`v3-extension-type-params`), and `curried-extern-import` uses a LINK and fails on an indentation
in `std/json-core.ssc`. The import mechanism is done; those are two other gaps standing behind it.

**Measured while here:** 27 of 58 standard-library modules parse on v3's front.

## std-move-left-scljet-behind — 113 corpus cases lost their import, and N fell 188 → 75 on main

<!-- status: fixed
     lane: multi
     area: build
     kind: bug
     gate: v3/corpus-report.sh
     fixed-in: 8c33bf3d0 -->

**FIXED 2026-08-09 by `git mv scljet std/scljet`.** The move had put the subtree at the repo ROOT
while its six siblings — `cluster`, `dsl`, `mapreduce`, `mcp`, `parsing`, `ui` — went under `std/`,
so it was the odd one out rather than a decision. **229 links point at `std/scljet/…` and ZERO at
the root path**, and the modules inside link each other relatively (`](index.ssc)`), so the whole
directory moves without a single link edit. Nothing outside `.ssc` referenced the root location
either: the only matches are a plugin DIRECTORY called `scljet-jdbc-plugin` and prose in an old
BUGS entry.

**Verified by sample rather than by the full sweep, and the reason is recorded.** The host was at
load 70–88 with 15 JVMs and the memory guard killed two 368-case runs; a corpus sweep under that
load turns timeouts into false CRASHes. Of 12 affected cases, all 12 failed with `cannot find the
import` before, and after: **10 accept, 0 fail on the import**, 2 fail on their own unrelated
constructs. The full N re-measurement is owed on a quiet host.

**Measured 2026-08-09 on `origin/main`, by a sweep run for an unrelated change.**

    N = 75 / 368        (188 / 368 earlier the same day)
    UNSUPPORTED 287     (was 174)
    116  cannot find the import — v1/runtime/std/scljet/index.ssc,
         std/scljet/index.ssc, tests/conformance/std/scljet/index.ssc

The std relocation (`531a0e451`, *"the 108 shared .ssc std modules move to the repo-root"*) put the
scljet modules at **`scljet/…` in the repo root**, while every consumer writes the link
`std/scljet/index.ssc`. `Loader.candidates` tries `SSC_STD + target` (the old
`v1/runtime/std/…`, now deleted), the bare target, and the importing file's directory — none of
which is `scljet/`. So the modules exist and no path reaches them.

**DIFF stayed 1 and CRASH stayed 4**, which is what identifies this as a resolution failure rather
than a semantic one: nothing computes a wrong answer, 113 programs simply stop being reachable.

Not filed against the move itself, which is a claim in flight (`std-to-repo-root`), and not fixed
here for the same reason. What this entry is for is the NUMBER: anyone measuring v3 against the
corpus in the meantime will read 75 and think a compiler regressed. It did not.

## lower-has-six-hand-written-Expr-walkers-and-nothing-checks-they-agree

<!-- status: fixed
     lane: v3
     area: codegen
     kind: bug
     gate: v3/walker-gate.sh
     fixed-in: 36777e7f6 -->

**GATED 2026-08-09 by `v3/walker-gate.sh`, and it found a real bug on its first run.** The `Expr`
cases that carry child expressions are read from `Ast.scala` — 21 of 28 — so a new case is required
of every walker the day it is declared and the list cannot go stale. A function opts IN by carrying
`// EXPR-WALKER` above its `def`, rather than being guessed at by shape.
**`qualifyMembers` did not descend into `NamedArg`**, so an object's own `val` was never qualified
inside a named argument: `Box(v = secret, tag = "x")` inside `object Store` reported
`unknown name 'secret'`. Two lines to reproduce, and it is the SAME hole `mapDeep` had, in a
different walker — which is this entry's whole point. Fixed in the same commit.
**The rest are DECLARED per walker and per case**, not waved through: `freeVars` skips 3,
`selfCalls` 7, `assignedFree` 4, `boxLocals` 3. Whether each is safe depends on when that walker
runs and nobody has checked; a line comes out the day its walker learns the case, and the gate goes
red if one does and the declaration stays. My first declaration was over-broad — copied from a
truncated run — and the gate said so: *"selfCalls now handles Interp Update; drop it from KNOWN"*.
**Self-test first**, like the jit gate: an invented case must be missing from every walker, so a
green run cannot be a check that never reads the bodies.

`v3/src/Lower.scala` walks `Expr` in SIX separate hand-written recursions — `mapDeep`,
`qualifyMembers`, `freeVars`, the curried-call normaliser, `assignedFree`, and the lowering itself.
Adding one node to `Expr` means finding all six, and **nothing fails when you miss one**: the
compiler is satisfied because most of them end in a catch-all arm.

**Measured 2026-08-09 while adding `Expr.MethodRef`.** Three were missed, and each announced itself
differently, hours apart:

| walker | symptom | how it was found |
|---|---|---|
| `mapDeep` (receiver) | `call to unknown function '__summon__'` | first build |
| `mapDeep` (`NamedArg` — a hole that PREDATES this node) | 116 corpus cases refused | corpus sweep |
| `qualifyMembers` | `unknown name 'entries'`, exactly one row, N 188 → 187 | A/B against `origin/main` |

The middle one is the warning: `mapDeep` had no `NamedArg` case at all, so `rewriteByName` and
`resolveSummons` have been silently skipping the insides of `f(x = …)` for as long as they have
existed. A missed rewrite is a WRONG PROGRAM, not a refusal, which is why nothing caught it; the
new node was refused when unresolved, and that is the only reason the hole became visible.

**What would fix it** is one traversal the others are written in terms of, or a single `children`
function per node that every walker consumes. Either way the property to gate is *"every walker
handles every case"*, which a test can assert by construction — build one value of each `Expr` case
and require each walker to visit its children.

## v3-lowerfail-reports-the-root-path-with-an-imported-unit's-line

<!-- status: fixed
     lane: v3
     area: codegen
     kind: bug
     gate: v3/front-gate.sh (imported-lower-error)
     fixed-in: 255971bc4 -->

`ssc3 ir tests/conformance/tkv2-busi-home.ssc` reports
`tests/conformance/tkv2-busi-home.ssc:119:3: call to 'vstack' passes 1 argument(s), it takes 2`.
That file is **71 lines long and does not contain `vstack`**. The real site is
`std/ui/form.ssc:119`, reached through the import graph.

`Loader.closureWith` already solves this for the FRONT — a `ParseFail` inside an imported unit is
caught and re-thrown with that unit's path — and the same entry records why: *"the imported unit's
line number with the importer's path, pointing at a line that has nothing to do with the error, in
a file the reader did not write"*. A `LowerFail` happens after `Loader.merge`, where the unit
boundary is gone, so nothing can do the same.

Cost measured while chasing something else: three commands to discover the position pointed past
the end of the file it named. Fixing it means carrying the unit with each declaration into the
merged program — `Pos` would need a path, or `merge` would need to record a per-def origin.

**FIXED 2026-08-10 in `255971bc4`, by the second of the two options this entry named** — `merge`
records a per-def origin. `Pos` was left alone: a path on every position would have touched every
construction site in the front, and the failing DECLARATION is the unit of blame anyway.

    Program.origin     name → unit path, filled for NON-root units only
    LowerFail.origin   optional and DEFAULTED, so ~40 `throw` sites are untouched
    Lower              ONE try/catch around the per-def loop — every message passes through it
    Main               `e.origin.getOrElse(path)` at its six catch sites

**Measured with the attachment disabled and restored**, on a two-file program whose imported unit
calls an unknown function:

    without   /tmp/lf/app.ssc:2:3   ← the root, whose line 2 is blank
    with      /tmp/lf/lib.ssc:2:3

**This entry's own reproduction no longer isolates the change** and that is worth recording: it now
reports `std/ui/primitives.ssc:74:26`, correct — but as a PARSE failure, which `Loader.closureWith`
already handled. The front moved under the entry. The planted control above is the evidence.

**Not pinned by a gate:** `front-gate.sh` requires a positioned refusal and prints the message, but
does not assert the PATH — the new `imported-lower-error` fixture would still pass if the wrong file
were named. Pinning it needs an assertion the gate does not have today.


## v3-uniml-def-has-no-type-parameters — so the default front cannot resolve a `using` clause

<!-- status: fixed
     lane: v3
     area: front
     kind: feature
     gate: v3/front-capability-gate.sh
     fixed-in: 1bce01a88 -->

**FIXED 2026-08-09 (SSC3-U1).** `SpikeAst.Def` now carries `tparams` and `bounds`, and a `using`
parameter's TYPE ARGUMENTS survive as `def.usingtypearg` leaves. `tagless-resolution` runs on BOTH
fronts with identical output and identical trees; the declared probes `usingp` and `summon2` came
out of the capability gate in the same commit, which the gate demanded. **N rose 188 → 189**, which
is the point: the feature is now on the front a user actually gets.

**Three wrong guesses before a diagnostic settled it, and the third was the real one.** The first
`skipTypeParams` in `parseDef` — there for `def Source[A].method` — was eating a PLAIN def's `[A]`
before the collecting call saw it. A type parameter lexes as `spike.uid`, the uppercase kind, so
matching `spike.id` collected nothing. And the type of a `using` parameter arrived as `Show`, not
`Show[A]`, because the dialect erases type arguments — which is exactly the difference instance
resolution matches on. One `System.err.println` of what the projection received answered in a
single run what two rounds of reading had not.

**A measurement I got wrong and had to throw out:** two probes I recorded as passing on BOTH fronts
were run while `UniFront.scala` did not compile, so `Front.default` fell back to v3 and I was
comparing v3 with itself. Third time in one day for that trap — `ssc3 front` is the check, and it
is now the first thing I run after touching that file.

`SpikeAst.Def` is `(name, params, ret, body, span)`. There is nowhere for `[A]` to go, so UniML's
dialect discards a definition's type parameters — and telling a type VARIABLE from a type is the
whole of what instance resolution does: `A` in `Show[A]` is solved for, `Int` in `Show[Int]` is
matched. Without them the projection cannot do what v3's own parser now does, and it keeps refusing
`using` by name.

**Measured 2026-08-09, landing SSC3-G2 stage 2a.** `tests/conformance/tagless-resolution.ssc` runs
on `SSC3_FRONT=v3` and prints `42 / hello / equal: 7 / not-equal / 99`; on the DEFAULT front it
reports `a \`using\` parameter is outside SSC3 core Tier 0`. Declared in
`front-capability-gate.sh` as `usingp` and `summon2` — probes that already existed for exactly this
construct — so the gap is visible rather than silent.

**THE DEFAULT FRONT IS UNIML**, so until this closes the feature is reachable only with
`SSC3_FRONT=v3`, and the corpus number does not move: `N = 188/368` before and after.

Closing it is a change to a DIFFERENT artifact — `uniml/scala/.../SpikeAst.scala` and
`ScalaSpike.scala` — plus the projection, and a classpath rebuild. Worth its own claim.

## v1-handle-return-clause-binds-the-Return-wrapper — the same program answers differently on v1 and v3

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh
     fixed-in: c87dc95cf -->

**FIXED — in v3, which is where the gap actually was.** Controlled A/B over one tree: **N 206 →
211**, `PASS +5`, `DIFF 4 → 2`, `UNSUPPORTED 154 → 149`, `CRASH 4 → 6`.

Two defects, and the second was found by pricing the first.

**The `Return` clause.** v3 had the mechanism all along — a handler arm marked `op = -1`, an index no
operation can take — but not the spelling. `case Return(x)` arrived as a `PCtor`, fell into the
operation branch and was refused as an undeclared effect operation, a message naming a gap that does
not exist. It is recognised before that branch now and lowered to the same arm, so both spellings
are one thing downstream and the executor needs no second notion of a return clause.

**`handle { body }` returned the body UNAPPLIED.** The first brace group parses to a lambda exactly
as the second does, so `handle { prog() } { case ask(k) => k(5) }` printed `<closure 2>` where v1
prints `6` — a wrong answer at exit 0. `handle(expr) { … }` was never affected, which is how it
survived: the corpus writes both forms and only one was exercised.

**FOUND BY PRICING, NOT BY READING.** The `Return` fix alone unblocked 15 programs and I was about to
land on that number. Checking what those programs then DID showed three of them trading an honest
refusal for `<closure N>`. Two more were already doing it without the change, which is what
identified the second defect as pre-existing and orthogonal.

**CRASH +2 is the stated cost:** two programs now fail loudly where they were refused. A named
runtime failure beats a refusal that names an operation the program never declared.

**⚠ THE PREMISE DOES NOT REPRODUCE, 2026-08-12, on a freshly built v1 — and the real defect is the
other way round.** Measured on one tree, both toolchains built from it:

| spelling | v1 | v3 |
|---|---|---|
| `case Return(x)` | `List(11, 12)` ✓ | **REFUSED** — `'Return' is not a declared effect operation` |
| `case x` (the fixture above) | `List(11, 12)` ✓ | `List(11, 12)` ✓ |
| `examples/algebraic-effects.ssc` | `List(1, 2)` ✓ | **REFUSED** |

v1 answers correctly on BOTH spellings here; no `Return(11)` wrapper reaches the binder. Whatever
produced `List(Return(11), Return(12))` above is not reproducible from this tree — a stale toolchain
is the likeliest explanation, and `bin/ssc-tools` in the shared checkout was indeed stale when I
started.

**`case Return(x)` IS the language's return clause, not a leaked implementation detail.** It has
**15 programs** behind it and its own named interop probe
(`tests/interop-conformance/probes/08-return-clause-transform.ssc`), and
`examples/algebraic-effects.ssc` documents it in prose: *"the `case Return(_) => List()` RETURN
CLAUSE seeds the base case"*. v1, v2, JsGen and JvmGen all implement it —
`EffectsRuntime.scala:156`, `PortableEffects.scala:189`.

**So the gap is v3's, and it is measured: v3 refuses 14 programs over this** — the whole
interop-conformance effect probe suite (9), three conformance libraries, a v21-native fixture, and
the documented example. v3 accepts only the bare `case x`, which no established program writes.

**This inverts what to do.** "Fix v1 so the clause binds the value" would break 15 working programs
and the probe that exists to pin this behaviour. The work is the opposite: **teach v3 the `Return`
clause**, at the front check that currently rejects the name as an undeclared operation.

**Not a contradiction of Sergiy's design decision** (explicit return clause, not an implicit lift) —
it CONFIRMS it. The language already has the explicit clause; v3 is the lane missing it. What was
recorded under `v3-handle-has-no-return-clause` as "v3 already accepts a return clause" is true only
of the bare-binder spelling, which is not the one the corpus uses.

**Measured 2026-08-11 on one file, both toolchains rebuilt first.**

    multi effect NonDet:  def choose(options: List[Int]): Int
    def program(): Int ! NonDet = NonDet.choose(List(1, 2)) + 10
    val all = handle(program()) {
      case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      case x => List(x)
    }

    v3, both fronts   List(11, 12)
    v1                List(Return(11), Return(12))

**v1 accepts the return clause and gives its binder the WRAPPER.** `x` is bound to `Return(11)`
rather than to `11`, so the representation the runtime uses to mark a finished computation reaches
the program's own value. In every formulation of algebraic effects the return clause binds the
computation's VALUE — that is what it is for — and v3 does that.

**Why this was looked at.** Sergiy confirmed the explicit return clause as the right language
design (v3-handle-has-no-return-clause, fixed 2026-08-09), which raised the question left open
there: `bench/corpus/effect-multishot.ssc` answers 0 because it writes no clause, so should the
fixture write one? **It cannot yet.** The lanes disagree about what the clause MEANS, so adding it
would make the corpus row print two different answers rather than one — and the bench corpus is
shared by every lane.

So the order is: fix this, then the fixture can carry the clause and `effect-multishot` becomes a
real row instead of a 0 that looks like an answer.

**Not a front difference.** v3's own front and the UniML projection agree with each other; the
divergence is between VERSIONS, which no front differential can see.

⚠ **RE-MEASURED 2026-08-11 ON A FRESHLY BUILT TOOLCHAIN: THE DIVERGENCE DOES NOT REPRODUCE.** This
entry's own program, run through `bin/ssc-tools run --v1`, answers

    List(11, 12)

— the same as v3. No `Return` wrapper reaches the binder. Nothing in v1 CONSTRUCTS a `Return` value
either: `grep '"Return"'` across v1 finds three sites and all three are the PATTERN
(`Pat.Extract(Term.Name("Return"), _)`) in `EffectsRuntime`, `JsGen` and `JvmGen`.

**But the same program in a BARE `.ssc` dies**, and that is a real defect filed separately as
`multi-effect-marker-is-lost-in-a-bare-ssc`:

    fenced   List(11, 12)
    bare     [ERROR] One-shot violation: NonDet.choose resumed more than once

So the most likely reading is that the original measurement was taken on a bare file, where the
handler runs one-shot and the answer is not what either lane means. **Left OPEN rather than closed:**
I could not reproduce the reported output in any spelling, and "I cannot reproduce it" is not the
same as "it does not happen" — the entry was written the same day, and the difference may be a tree
state I no longer have.


## v3-no-handler-error-has-no-position, and no NAME either — the IR carries neither, so the refusal moved

<!-- status: fixed
     fixed-in: 6f28bc820
     lane: v3
     area: runtime
     kind: bug
     gate: v3/effects-gate.sh -->

**FIXED by moving the refusal to LOWERING, which is the cheaper of the two shapes this entry
proposed.** The measurement that filed it still holds — `Instr` has no position field, `Module` is
`(consts, types, globals, prims, funcs, entry)` with no operation-name table — so the executor
genuinely could not print either thing. What it could not know at run time, the lowering still has:

    before  ssc3: v3/tests/effects/perform-no-handler.ssc: no handler for effect operation 0
    after   ssc3: v3/tests/effects/perform-no-handler.ssc:4:23: no handler for the effect
            operation 'Bump.tick' — nothing in this program handles it

**WHY IT IS SOUND, stated because a refusal that fires too early is worse than a bad message.**
`handle` is the only construct that installs a handler; the module at that point is the MERGED
import closure; and `resolveHandles` has already run. So "no arm anywhere names this op" is a fact
about the whole program, not about the current call path. **A handler that exists but is not on the
path stays a run-time error, correctly** — this check never sees it, which is exactly the boundary
the entry drew.

It rides inside the `try` that attaches `p.origin`, because the passes there run before the body
lowering that sets it and a message without an origin names the file the USER typed rather than the
file the `perform` is in — P-2 of `v3/PRELUDE-CORRECTNESS.md`.

**`KNOWN_UNPOSITIONED` IN THE GATE IS NOW EMPTY, in the same commit.** A fixture belongs there only
while its refusal genuinely cannot carry a position; that stopped being true the moment the failure
moved out of the executor, and leaving the declaration behind would have made the gate green about a
rule it no longer tests.

**CONTROL, in the same tree rather than against an older measurement:** with the refusal removed and
`corpus-report.sh` re-run, the corpus is identical cell by cell — PASS 233, DIFF 4, UNSUPPORTED 129,
CRASH 1, same crash set. So nothing that used to run is refused, and the corpus cannot see this
defect in either direction; the fixture that motivated it lives in `v3/tests/effects`. (N had moved
229 → 233 since my previous measurement two hours earlier, and the control is what says that belongs
to other people's commits and not to this one.)

**Gates:** effects-gate OK, 13 fixtures on both lanes, with `perform-no-handler` now listed as
"refused on both"; front-gate GREEN 91, exec-gate GREEN 87, parity GREEN 65.

**The other shape stays unbuilt and is still the better message for the on-the-path case:** an
operation-name table in `Module`, which `Ir.scala`, `Lower.scala`, `BridgeV2` and the text codec
must agree on, with a checked-in golden to update.

## selfhost-front-given-with-swallows-the-rest-of-the-file — an anonymous `given … with` ate the statement after its body

<!-- status: fixed
     lane: native
     area: front
     kind: bug
     gate: tests/e2e/selfhost-front-gate.sh
     fixed-in: 69294ecbf -->

**FIXED 2026-08-13.** The slug says "the rest of the file" and that is not what it did — see the
correction below, which is the measurement that made the fix obvious. Kept under the original slug
because the claim, the gate cases and the commit all name it.

**The mechanism.** An anonymous given — an upper-case head, so there is no name to bind — is ERASED
by design, and `givenItem` erased it by calling `skipStmt`. `skipStmt` stops at the first depth-0
`;`, and the layout emits NO separator after a block's closing brace. So on `given S with` plus an
indented body the skip walked out of the block and kept going, taking the next statement with it.
The fix is to erase it with `skipGivenDecl` (`:2374`) instead — the skipper already in the file,
written for exactly this shape and used by `collectTopExtMethods` since it was added. All three
erasing branches (`givenItem`, `givenNamed`, `givenAfterColon`) now use it.

**THE CORRECTION, and it is the useful part of this entry.** The damage was ONE statement, not the
rest of the file. It was filed as total because the reproducer had exactly one statement after the
given:

| program | before | after | v1 |
|---|---|---|---|
| given, then one statement | `<nothing>` | `после` | `после` |
| given, then THREE statements | `2/3` | `1/2/3` | `1/2/3` |
| three back-to-back givens, then one statement | `<nothing>` | `ok` | `ok` |

The middle row is the one that names the mechanism: the first statement vanishes and the rest run.
The third shows why consecutive givens look total — each given's skip eats the next given's header,
so the loss compounds until something emits a depth-0 `;`.

**WHAT THIS DOES NOT FIX**, measured before it was claimed. `std/show`, `std/eq`, `std/hash` and
`std/order` hold 20 anonymous `given TC[T] with` between them, and they are NOT repaired. Both
fronts answer their probes identically — `unbound global: __missing_using_Eq` — because those givens
were being erased by design anyway, and a fenced-block boundary stops the skip before the helper
defs. Anonymous-given RESOLUTION is a separate unimplemented feature; this change only stops the
erasure destroying its neighbours. The `20 uses in std/` census looked like the payoff and was not.

**Evidence.** `selfhost-front-gate` 17/17 with five given cases — three flip old→new above, and the
value-form and named-form controls hold on BOTH sides, so the cases discriminate rather than merely
pass. `smoke-ci` 86/86, 564.8s of a 1027s budget. `native-front-corpus --standard` A/B with only the
staged front swapped (same tree, same jars, same corpus, each side sanity-probed before its run):
**byte-identical reports**, 214 rows, front-ok 126, run-ok 55 on both. Clean no-regression, and
explicitly NOT an improvement — the corpus holds no program of this shape, which is why 214 programs
never caught it.

<details><summary>The original filing, kept — its narrowing was right and its guess was not</summary>

**Measured 2026-08-13, narrowed to one construct.** On front F everything AFTER a `given X with`
and its body is lost — no diagnostic, no partial output, exit 0. v1 answers correctly.

    trait S:
      def z(): Int
    given S with
      def z(): Int = 7
    println("после")        ← never printed on F; v1 prints it

**The narrowing, one признак at a time — the method that found the one-line `while` this morning
after three readings of the code had not:**

| form | front F |
|---|---|
| `given X with` + body, then a statement | **statement LOST** |
| `given X = Impl()` (no `with`) | works |
| a `println` BEFORE the given | printed — only what FOLLOWS is lost |
| a `trait` carrying an `extension` member | works |
| a top-level `extension` | works |

So it is `given … with` alone, not `extension`, and not `given` in general. **24 programs across
tests, examples, std and bench write this form.**

**Where it points, not yet proven.** `givenItem` (`specs/v2.2-p6.5-fsub.ssc:2249`) tests
`fst(hd(tl(ts))) == 1` — the LOWER-CASE identifier kind — and an uppercase trait name is kind 3, so
`given S with` falls to the `else` arm and is handed to `skipStmt`. That is the same token-kind
confusion that made `isQualAssignHead` unable to fire on `Counter.n = 5`
(`selfhost-front-qualified-assignment-to-an-object-member-is-ignored`, fixed the same day): kind 1
is what a `var` is, kind 3 is what `parseAtom1` routes to `parseCtor`.

**What that does NOT yet explain**, and is why this is filed rather than fixed: `skipStmt`'s nesting
is balanced — `isOpenNest` is 21/28/50 and `isCloseNest` is 22/29/51 — so a layout block ought to be
entered and left cleanly and the skip ought to stop at the following `;`. Either the layout for
`with` does not emit that pair, or the loss happens after the skip. Whoever takes it should start by
printing the token stream around `with`, not by reading further.

**How I found it, which is worth recording:** looking for why front F is silent on an
extension-in-trait program. The extension was not the cause at all. The gate I added this morning
against exactly this class of defect — `tests/e2e/selfhost-front-gate.sh` — does not catch it, so it
is narrower than the class it was written for. A case is added there now.

*Scored afterwards.* The route was right — kind 3 does fall to the `else` arm and reach `skipStmt`.
The paragraph above that stopped short was right to stop and wrong about where to look next: the
nesting IS balanced, and that is not a reason to doubt the reading. What it missed is that balance
says nothing about where the skip STOPS — the pair is entered and left cleanly and then the walk
continues, because the thing it waits for (a depth-0 `;`) is not emitted after a layout brace. The
instruction it left for the next person — print the token stream around `with` — would have worked;
what actually settled it was cheaper, four one-line programs differing only in how many statements
follow the given.

</details>

## selfhost-front-accepts-a-parameterised-anonymous-given-the-language-rejects — F runs a program v1 refuses to parse

<!-- status: open
     lane: native
     area: front
     kind: bug
     gate: tests/e2e/f-output-agreement-gate.sh
     fixed-in: - -->

**Measured 2026-08-13, while fixing
`selfhost-front-given-with-swallows-the-rest-of-the-file`.** A parameterised anonymous given is not
in the subset — v1 refuses both spellings — and front F does not refuse either of them.

    trait S[A]:
      def z(a: A): Int
    given [A]: S[A] with        ← v1: error: `;` expected but `:` found  (also for `given [A] => S[A] with`)
      def z(a: A): Int = 7
    println("posle")

| front | before the skip fix | after it |
|---|---|---|
| v1 (oracle) | `error … :3:14` rc=1 | unchanged — refuses |
| F | empty output, rc=0 | `posle`, rc=0 |

**The fix did not cause this and does not settle it.** F failed to refuse the program both before and
after; what changed is only which wrong answer it gives — it used to drop the user's code along with
the given, and now it erases the given and runs the rest. Recording the shift matters because a
reader diffing the two would otherwise see F "start working" on a program that is not legal.

**Why F should DECLINE rather than accept.** F already has the right move for input it cannot
handle: it declines the file and the reference front compiles it (`ssc: F did not lower this file`,
visible in `ssc info --front-report`). That path is what keeps F's coverage honest, because a
declined file is counted as F's gap. Silently erasing an unsupported head instead spends the
program's meaning to keep F's number up.

**No gate case, deliberately.** `tests/e2e/selfhost-front-gate.sh` compares F against v1 on OUTPUT,
and v1 has no output here — it refuses. A case would need the gate to compare refusals, which it
does not do today. That is the work: either teach the gate a refuse-vs-refuse comparison, or make
`givenItem` decline a head it does not recognise instead of erasing it.

**Gate named 2026-08-14: `tests/e2e/f-output-agreement-gate.sh`** — the gate whose whole subject is
F agreeing with the v1 oracle, which is exactly the disagreement here: v1 refuses and F runs.

**RE-MEASURED 2026-08-19 before the 0.2.0 release, and the prescribed fix is a HALF-FIX.** Both
spellings still behave as recorded. What the 2026-08-13 measurement did not look at is the front the
fix would hand the file to: this entry offers *"make `givenItem` decline a head it does not
recognise instead of erasing it"*, and a declined file goes to `defaultRunner` — the LEGACY front
(`ssc1-run.ssc0`; `RunNativeV2.scala:665`), not to v1. The legacy front **erases the given exactly as
F does**: `SSC_FRONT=legacy` on both spellings prints `posle` and exits 0. So declining moves the
erasure one front over and the user sees no change. The other half of the plan — teaching
`f-output-agreement-gate.sh` a refuse-vs-refuse comparison — is unaffected by this.

The two fronts were confirmed to be genuinely distinct code paths before that conclusion was drawn:
with F's runner moved aside the default lane fails to start while `SSC_FRONT=legacy` still runs.
Three cheaper probes answered identically on both fronts and settled nothing.

**And anonymity is not the discriminator — the type-parameter clause is.** The NAMED parameterised
form `given anyS[A]: S[A] with` is legal Scala 3, v1 answers `7`, and both native fronts answer
`ssc: unbound global: __missing_using_S`. That is the opposite direction from this entry's subject —
the fronts REFUSING what the language and the oracle both accept — and it is filed separately as
`both-native-fronts-refuse-a-named-parameterised-given-the-oracle-runs`, with the mechanism named to
the line. A fix for this entry that does not read that one will re-derive the same parser shape.

**Done when** F refuses **both spellings** — `given [A]: S[A] with` and `given [A] => S[A] with` —
asserted there against the v1 oracle's own message. Both, because the entry measured both and a gate
that pins one leaves the other free to diverge. The anti-case is already the gate's design: a
program v1 accepts must still run on F.

## v3-trait-extension-member-refused — an `extension` inside a trait was called "not a `def`"; seven std modules are down to zero

<!-- status: fixed
     fixed-in: b8379d21f
     lane: multi
     area: front
     kind: bug
     gate: v3/front-capability-gate.sh (v3/tests/front-capability/externqual.ssc) -->

**THE ENTRY WAS STALE, AND ITS OWN CENSUS IS WHAT SAYS SO.** Re-run 2026-08-16 — the seven modules
it named, both fronts, one import apiece:

    bifunctor  foldable-traversable  functor-applicative-monad  index  monaderror  selective
      -> all six build on BOTH fronts
    streams-bridge
      -> still failed, for a DIFFERENT reason on each front

`tagless-multi-file`, the G2 acceptance row this entry said "cannot run for this reason and this
reason only", runs and prints its two lines. The `extension`-inside-a-trait refusal is gone; the
extensions work landed in `10024d732` / `57c8164fe` and this entry was not re-measured against it.
A `not fixable` or `blocked` verdict is dated evidence — this one aged out in four days.

**WHAT WAS LEFT WAS A DIFFERENT DEFECT WEARING THE SAME NUMBER, and it is fixed here.**
`std/streams-bridge.ssc:46` declares

    extern def Source[A].distributed(partitions: Int = 1): DStream[A]

and v3's own parser died on it with `expected an expression, found .` while UniML read it — so the
module parsed on the default front and not on v3's, which is invariant I-3 again. Isolated by
probe: BOTH `extern def Source.plain(…)` and `extern def Source[A].typed(…)` were refused, so the
gap was the QUALIFIED NAME and not the type parameters.

**Scope decided by counting, not by caution:** of every qualified `def` name in `std/` and the
conformance corpus, ZERO appear without `extern` and SIX appear with it (`Bench.opaque`,
`Source[A].distributed`, `DStream[A].local`, `DStream[A].localBounded`, `Source[A].remote`,
`DStream[A].remote`). So the dot is accepted only on the extern path; an ordinary `def` still
refuses it.

**GATED WHERE THE DEFECT LIVES, which is not `front-gate.sh`** — that runs the DEFAULT front, so a
fixture there would have been green without the fix and would have measured nothing. The probe went
into `v3/tests/front-capability/`, the directory built for the two-front axis, and the control is
decisive: with the parser change reverted the gate FAILS `externqual NEW divergence — accepted only
by uniml`.

Before reaching for a bigger change — widening that gate over all the front fixtures — the cost was
measured: of the 89 fixtures that are not `.uniml-only`, ZERO diverge today. The hole was in what is
COMPARED, and one probe in the right directory closes it for this construct.

**`streams-bridge` still does not run, and that is now the same honest answer on both fronts:**
`the host function 'remoteSourceLocal' is not implemented on this lane`, positioned. A host gap is
not a front defect.

**Gates:** front-gate GREEN 92, exec-gate GREEN 88, capability gate OK, corpus N = 233/370
unchanged.

## v3-method-as-a-value — `obj.m` passed as a function lowers to a CALL with no arguments

<!-- status: fixed
     lane: v3
     area: codegen
     kind: feature
     gate: v3/front-gate.sh
     fixed-in: 89f6f3e0a -->

`bench/corpus/typeclass-fold.ssc` — `xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)`
→ `refusing to run invalid IR: func combineAll #2: call to intSum.combine passes 0 arguments, it
takes 2`. The second `summon` is not being CALLED; it is being PASSED, and Scala calls that
eta-expansion. v3 lowers `obj.m` with no argument list to a call with no arguments, so a method
used as a value becomes a call that cannot type.

**Found by G2 stage 1 and worth separating from it, because it is not a typing question.** With
`summon` resolution landed, this row gets its instance — a single `Monoid` in its closure — and
then stops here. It is the only thing between `typeclass-fold` and running.

**The fix has a trap in it, which is why this is filed rather than done.** The rule would be: a
method reference with no argument list, where the method takes `n > 0` parameters, becomes
`(a₁ … aₙ) => obj.m(a₁ … aₙ)`. That is correct Scala semantics — but only if the AST distinguishes
`obj.m` from `obj.m()`. If both arrive as a method call with an empty argument list, then applying
the rule silently turns a genuine arity ERROR into a lambda, and a program that should be refused
starts running and printing something. Check that first; the answer decides whether the fix is
three lines or needs the front to carry the distinction.

**FIXED 2026-08-09 — and the trap was REAL, which is why the front now carries the distinction.**
Measured before writing any rule: `M.add` and `M.zero()` both printed `(send (name "M") …)`, so
"no arguments" could not tell a value from an error. UniML's dialect had kept them apart all along
(`Select` versus `Apply(Select, …)`) and the projection was collapsing them.

`Ast.Expr.MethodRef` is a selection with no argument list. Both fronts emit it, `AstText` prints it
as `(sel …)` — deliberately NOT as `send`, so `front-diff.sh` can see a front that stops emitting
it — and `Lower.resolveMethodRefs` turns every one into either an ordinary no-argument call or a
lambda calling the method with the arguments it declares. A separate case rather than a flag on
`MethodCall`: a `Boolean` would have said the same and added a wildcard to all THIRTY of its
pattern sites.

**Eta-expansion is restricted to a receiver that NAMES its target**, so it can only turn a refused
program into a running one, never change one that already ran: `Obj.m` resolves to one definition
and its arity is known, while `x.m` on a value is decided by the receiver's tag at run time and two
classes may declare `m` differently.

**`checkArity` grew the object-method case in the same commit**, because the two spellings now
differ and the failing one has to say where: `M.add()` was reaching the verifier, which reports
without a position and is classified CRASH. It now reads
`eta-arity-error.ssc:18:11: call to 'M.add' passes 0 argument(s), it takes 2`.

**`bench/corpus/typeclass-fold.ssc` runs — the first of §52's five rows.** `VInt(16500)`, checked
as 1+2+…+10 = 55 times 300 against the source rather than against itself. Fixtures
`eta-expansion` (10, 12, sum) and `eta-arity-error` (refused, both fronts).

## v3-extension-type-params — `extension [M](ref: ActorRef[M])` is refused, and it stands between two conformance cases and their module

<!-- status: fixed
     lane: v3
     area: front
     kind: feature
     gate: v3/extension-gate.sh
     fixed-in: 10024d732 -->

**Fixed 2026-08-10. N 171 → 194 of 368**, where §51's earlier attempt at the same feature moved it
188 → 130. Design: [`specs/ssc3-extensions.md`](specs/ssc3-extensions.md).

§51 concluded that *which method a receiver has is a fact about its runtime value, not its syntax*.
That is right about choosing BETWEEN candidates and too strong for this case: if a name is neither a
built-in nor declared by any class in the merged program, no receiver can answer to it, and the
rewrite is not a guess about a type but the absence of alternatives. Three conditions, each able
only to PREVENT a rewrite, so no call that works today can stop working.

Both fronts desugar an extension into ordinary lifted `Def`s — the receiver becomes the first
parameter — so `front-diff` compares trees that agree by construction. Both call shapes are covered:
`"a".boxed` arrives as a `MethodCall`, `3 ~ 4` as a `Bin`.

Three gaps closed on the way, each reachable only once extensions parsed: operator method names,
Scala's TOP precedence band (`prec` returned 0 for `~` — "not an operator" — and
`f-tilde-arrow-ext` checks the consequence: `1 <~ 2 ~ 3` is 1203), and numeric separators
(`30_000`). Prefix `~` desugars to `x ^ -1` on both fronts rather than adding an AST node.

**The costs are filed, not omitted:** `v3-extension-unblocks-two-files-into-a-lane-DIFF` (two files
now execute and the lanes fail differently) and three DECLARED capability rows, none of which is
about extensions — UniML now walks past `extension` into parts of those files v3's parser has never
handled.

**HALF DONE 2026-08-09, and the half matters: the DIAGNOSTIC, not the feature.** `extension` was
not refused by v3's own front, it was UNPARSEABLE — `extension (s: String)` reached the expression
parser and stopped at `expected ')', found :`, naming punctuation instead of the construct, and the
type-parameterised form did the same one column further along. It now refuses BY NAME, in the words
the projection has used all along: `` `extension` is outside SSC3 core Tier 0 ``, same message, same
position, both fronts.

**Guarded by what FOLLOWS it.** The first version refused the bare word and made `extension` a hard
keyword: a program with a value of that name stopped working, and the tell was that the refusal
pointed at the ASSIGNMENT rather than at a declaration. A declaration opens with `(` or `[`, and
nothing else is one. No gate would have caught this — the corpus has no such value, and the
capability gate compares accept/refuse rather than positions.

**THE FEATURE IS STILL OPEN and §51 already says how.** An attempt to make extensions work by
rewriting `v.m(a)` to `m(v, a)` wherever `m` is an extension name was reverted at
`N 188 → 130, CRASH 0 → 131`, because an extension called `map` rewrote every `.map` in the
program. The conclusion recorded there is that an extension belongs in `Lower`'s dynamic `Invoke`
default — the one point where the merged program AND the receiver's runtime tag are both known.
Doing that is what turns `actors-bounded-mailbox`, `actors-process-info` and `tagless-multi-file`.

`v1/runtime/std/actors.ssc:182:18: expected ')', found :` — v3's parser takes
`extension (ref: T)` and not `extension [M](ref: ActorRef[M])`, so the type-parameter list before
the receiver is what it stops on. Column 18 is the `:` of `ref:`, which says the `[M]` was consumed
as something else and the parser then wanted a plain parenthesised group.

**FOUND BY FIXING SOMETHING ELSE.** `import actors.Overflow` used to be refused at the import line,
so `actors-bounded-mailbox` and `actors-process-info` never reached the module they name; now that
imports work the error moved from line 11 of the consumer INTO `std/actors.ssc`. This is the whole
of what stands between those two cases and their module — worth stating because the previous entry
counted them as import failures for as long as the import failed first.

Not filed as a defect against `actors.ssc`: the file is ordinary Scala 3, and 27 of 58 std modules
parse on this front, so the gap is v3's.

## front-diff-cannot-finish-when-the-second-front-does-not-compile

<!-- status: fixed
     lane: multi
     area: build
     kind: apparatus
     gate: v3/front-diff.sh
     fixed-in: 31fba8706 -->

**FIXED 2026-08-08.** Each declared front is probed ONCE, before the corpus, on a `println(1)` the
gate writes itself; a front that cannot print stops the run immediately and says which one.

**RETRACTED TWICE ON THE WAY THERE, and both retractions are the entry.** I read the same
unfinished log twice and stated two contradictory things — "11 refusals, RED" and then "50 refusals,
exit 0, green while comparing nothing". The run had exit code **124**: `timeout` killed it at forty
minutes without a verdict. Neither statement was a reading of a finished run, and the second was
filed here as a defect that did not exist.

**What the finished run says**, and it vindicates the original report I had contradicted:

```
both fronts print: 269; they AGREE on 233, differ on 36
== v3 SSC3-11 gate: RED ==            (exit 1)
```

36 disagreements, every one `actors-*`, exactly as first reported. **The cause is not by-name**:
v3's own front does not understand `import actors.Overflow` and leaks it into the program as
`(do (name "import"))` plus `(do (send (name "actors") "Overflow"))`, while UniML handles it. That
is a separate defect and belongs to v3's parser.

**The real apparatus defect was the RUNTIME.** When the second front does not compile — as it did
not for a while today — the gate re-attempted the same failing compile once per fixture and was
still going at forty minutes. Forty minutes without a verdict is not a slow gate; it is a gate
nobody reads, and two people read it partially and drew opposite conclusions.

**Proved in both directions**, since a guard that cannot be made to fire is one nobody has checked:
`SSC3` is now overridable, and pointing the gate at a shim whose `uniml` front always fails gives
`FAIL front 'uniml' cannot print an Ast at all`; a healthy tree passes the probe untouched. The
probe writes its own `println(1)` rather than borrowing a fixture — my first version took
`ls | head -1`, and some fixtures are legitimately refused by one front (`object-nested-class` is
declared uniml-only), so it would have failed the gate for a CONSTRUCT rather than a broken front.

**Lesson, since it cost two wrong statements:** an exit code read from a wrapper ending in `echo` is
the echo's, and a log with no verdict line is a log of a run that did not finish. Neither is evidence
about a gate.

## v3-uniml-front-drops-by-name — one language, two evaluation orders, decided by the working tree

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: v3/front-diff.sh
     fixed-in: 119925dc5 -->

**FIXED 2026-08-08 in `119925dc5`.** Both fronts now agree, in behaviour and in text:

```
behaviour   uniml: 3 2              v3: 3 2
tree        uniml: (p "x" byname)   v3: (p "x" byname)
```

The fix was in the GRAMMAR, as this entry said it had to be. `ScalaSpike` keeps the arrow as a
`def.byname` LEAF instead of discarding it — a leaf and not a flag on the type, because the token is
real source and UniML's tree is the storage, so dropping it also broke reconstruction.
`SpikeAst.Param` carries the flag, defaulted so every other construction site compiled untouched.

**Original measurement, kept because it is what made the case:** `def twice(x: => Int): Int = x + x`, called with an argument that counts its
own evaluations:

```
SSC3_FRONT=v3        3     the argument is evaluated twice — by-name
default (uniml)      2     evaluated once — EAGER
```

`Param.byName` drives the lowering's `rewriteByName` (SSC3-7s). v3's own parser sets it;
`UniFront.param` never did, so the flag is `false` for every parameter this front produces and the
rewrite never fires. Which semantics a program gets depends on whether `v3/uniml-classpath.sh` has
been run in that tree.

**It cannot be fixed in the projection, which is why this is an entry and not a commit.** The type
would be the obvious place to read it from, and `ScalaSpike` consumes the arrow before the type is
captured — its own comment says so: *"A BY-NAME parameter … Erased with the type."* `TypeRef.text` is
therefore `Int`, never `=> Int`. **The fix belongs in the grammar:** keep the `=>` as a marked leaf,
then the projection is one field.

**Both differentials were blind to it, and one no longer is.** `front-diff.sh` compares AST TEXT and
`AstText` did not print the flag, so the two fronts printed identical trees for a program they
execute differently; `front-capability-gate.sh` compares accept/refuse and both fronts ACCEPT. The
printer now prints `(p "x" byname)`, so front-diff will see this one — which is how the divergence
was finally confirmed rather than argued about.

## v3-two-fronts-differ-in-CAPABILITY and every gate compares only OUTPUT

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: v3/front-capability-gate.sh
     fixed-in: 83fbc8c75 -->

**FIXED 2026-08-09 — both rows this entry names are closed, and the gate it produced is what
closed them.** `effect-oneshot` came out when the effect projection landed; `type-lambda-native`
comes out now with SSC3-7i. `front-capability-gate.sh` runs 50 programs on both fronts and its
declared-divergence lists are EMPTY in both directions — the two fronts accept and refuse exactly
the same programs, and the next entry added to those lists is a regression unless it arrives with
a reason.

**The gate did more than report.** It is what identified `[A] =>> …` as closeable: the sprint had
it filed behind the generics wall and gated on the type-checker decision, while this list recorded
that UniML already accepted the file. A construct one front takes at Tier 0 is expressible at
Tier 0 — so what was missing was three lines in v3's `skipType`, not a checker.

**Measured 2026-08-08, and the numbers are why it stayed invisible.** v3 has two fronts and
`Front.default` picks UniML whenever it is registered — which depends on the WORKING TREE, since
UniML needs `v3/uniml-classpath.sh`. A worktree without it runs v3's own front; the shared checkout
runs UniML. Both facts are documented in `Front.scala`; what is not is that the two do not accept the
same programs.

Corpus, same 36 files, same commit, one tree each:

```
UniML front   30 run,  6 refused
v3    front   30 run,  6 refused
```

**Identical counts, different sets.** They disagree on exactly two rows, in OPPOSITE directions:

```
effect-oneshot       uniml REFUSES     v3 RUNS
type-lambda-native   uniml RUNS        v3 REFUSES
```

So "30 of 36" is true of both and describes neither. Any report that quotes the count without naming
the front is not wrong so much as unfalsifiable.

**Why no gate catches it.** `front-diff.sh` and `front-report-gate.sh` compare the two fronts' AST
OUTPUT on programs BOTH accept, and `Front.scala`'s own comment says why that is the design: the two
agree on every fixture, so no fixture's output can distinguish them. That reasoning is sound and it
leaves a hole exactly one shape wide — **a program one front refuses and the other runs produces no
output to compare.** Capability divergence is invisible to an output differential by construction.

**How it bit, concretely.** SSC3-7a (algebraic effects) is front work. It was developed and verified
in a worktree, where v3's front is default, and `effect-oneshot` runs there and agrees with the v1
interpreter on the value. On the shared checkout it reports
`` `effect` is outside SSC3 core Tier 0 `` — UniML's refusal — so the feature is absent on the path
most people and every benchmark actually take. The benchmark's v3 column was measured with UniML.

**Smallest useful fix:** a gate that runs the corpus through BOTH fronts and asserts the ACCEPT/REFUSE
sets are equal, naming any row where they differ. It needs no oracle and no expected output — the
comparison is between the two fronts, which is the same technique the output differential already
uses, applied to the axis it cannot see.

## rust-toplevel-val-calling-an-intrinsic-does-not-compile

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: sbt backendRust/testOnly *RustGenTryCatchTest*
     fixed-in: 67cbccb20 -->

A top-level `val` whose initializer calls a `std` intrinsic emits an UNROUTED call, and the crate
does not compile:

```text
[exists](std/fs.ssc)
val ok = exists("/tmp")
def main(): Unit = println("ok " + ok.toString)
```

    error[E0425]: cannot find function `exists` in this scope
    help: use std::fs::exists;

The same call inside a def BODY routes correctly to `crate::runtime::_exists`. So the gap is the
`topVals` path — `contentTopVals` renders `v.rhs` with a context built for `<given>`, and intrinsic
routing does not happen there.

**Independent of the top-level-entry synthesis, and that was checked rather than assumed.** Found
while measuring the Rust column of `specs/std-fs-os.md` §2.1 through a synthesized entry, so the
first suspicion was that the synthesis caused it; the case above has an EXPLICIT `def main()` and no
synthesis is involved. What the synthesis changed is only that such a program now reaches cargo at
all — before, it emitted a `[lib]` and never got there.

**Second gap found in the same run, and it is separate:** `.toString` on a `List[String]` emits
`format!("{}", Vec<String>)`, and `Vec` has no `Display`:

    error[E0277]: `Vec<String>` doesn't implement `std::fmt::Display`


**FIXED 2026-08-10 in `67cbccb20`, and this entry's diagnosis was exactly right.** `collectTopVals`
built `Ctx(Map.empty, Set.empty, ctorMap, Nil, "<topval>")` — an empty intrinsic table and an empty
user-def set — so the initializer was rendered with no routing. It now receives the same
`intrinsics` and `userDefs` the rest of the walk uses.

**The first test I wrote for it was VACUOUS**, and the control is what said so: it probed with
`"abc".length`, a METHOD the walker renders identically with or without the table, so it passed in
both states. With `cwd()` — a registered intrinsic — it discriminates:

    table emptied   let here = cwd();      ← this defect, verbatim
    fixed           let here = …runtime::…

The test also REFERENCES the val on purpose: top-level vals are emitted as a `let` preamble only
inside the bodies that use them, so an unused one appears nowhere and would be vacuous a second way.

`backendRust/test`: 276 tests, 0 failures.


## parameterless-def-diverges-native-vs-interp — opposite conventions, no portable spelling

<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: tests/e2e/parameterless-def-import-gate.sh
     fixed-in: 7bcfdcadb -->

**FIXED on the interpreter side.** Scala settles which lane was right: `def mk: Box = …` declares a
*parameterless method*, so every mention invokes it and `mk()` is not how it is called. native, jvm
and js all did that; the interpreter — the lane the conformance corpus treats as golden — did not,
and handed the closure to the use site.

**The report's table was true only for a TOP-LEVEL def.** Measuring five declaration positions
instead of the one it named turned one divergence into a map. The local-def cell is a defect on the
other lane, filed above. A second cell I read as one was not: the extension row failed on v2 in my
probe, and the probe had wrapped its call in `def main()` — the conformance case put the same call
at top level, v2 passed, and the `known-red` I had declared for it went red as STALE. What is
actually broken there is `v2-extension-member-call-inside-a-def-body-fails-by-arity` in
`v2/BUGS.md`, which has nothing to do with parameterless defs.

| declaration | int (before) | int (after) | jvm | js | v2 / native |
| --- | --- | --- | --- | --- | --- |
| top-level | `FunV` | ✅ | ✅ | ✅ | ✅ |
| object member | ✅ | ✅ | ✅ | ✅ | ✅ |
| class member | ✅ | ✅ | ✅ | ✅ | ✅ |
| local, in a body | `FunV` | ✅ | ✅ | ❌ | ❌ silent `<closure>` |
| extension member | ✅ | ✅ | ✅ | ✅ | ✅ |

**The fix.** `params` cannot tell the two spellings apart — both are empty — so `FunV` gains a
`parameterless` constructor field, set at the def sites from an empty `paramClauseGroups`, and a
bare name that resolves to one is invoked instead of returned. Three positional `FunV(…)` patterns
had to become typed patterns for the added field; they are better that way anyway, since the next
field will not touch them.

**Two implementations passed every single-file shape and were still wrong**, and each was caught by
a control rather than by review:

1. *A non-constructor `var`, plus an `interp.parameterlessDefsPresent` gate* — modelled on
   `declaredReturnType` and `objectVarsPresent` right above it, and wrong twice over. `copy` starts
   a case class's non-constructor `var`s over and the section/import path copies every function it
   binds; and the gate is owned by the RUNNING program, so an imported def — which arrives from a
   child `Interpreter` — reads false and skips the check entirely.
2. *An identity-keyed map of parameterless def BODIES on the interpreter* — the shape `tcoCache`
   uses, and immune to `copy` for exactly the reason that cache is keyed that way. Still wrong: a
   module import runs the imported file in its own `Interpreter`, so the registration lands in the
   CHILD's map and the parent probes an empty one. Isolating gate from registry took one probe — put
   a parameterless def in the importing file too, so the gate reads true; the import still failed.

A constructor field is what survives both, because the value itself is the answer wherever it
travels. `tests/e2e/parameterless-def-import-gate.sh` is the two-file gate that says so, and it goes
red against the pre-fix binary while its control row stays green.

**One fix, two decision sites.** Patching the monadic `Term.Name` path alone made `mk.v` print `L` —
the report's own repro — while `println(mk)`, `f(mk)` and `val a = mk` still handed over the closure.
`EvalRuntime.fastValue` is a second site answering the same question: a Pure-free lane that reads a
name as a `Value` without going through `eval`. It cannot invoke anything (it returns a `Value`, not
a `Computation`), so it now DECLINES a parameterless def and lets the mention fall through to the
path that can. Had the gate carried only the reported repro, this would have shipped as a fix for
one position out of four.

**The controls are the case, not decoration.** `next` mentioned twice must print `1` then `2`, or a
lane that memoises like a `val` passes everything else. `def toFn: Int => Int` mentioned bare must
yield the lambda so `toFn(3)` is one call of each — the shape `v2/bin/ssc1-run.ssc0` depends on
(`def sscLibRoot = () => …` called as `sscLibRoot()`), and a census found all 13 same-file
paren-less-declaration/`name()`-call pairs in the repo to be exactly that shape, so no existing
program changed meaning.

The control I tried first — `val f = zero` for `def zero(): Int` — is a **compile error in real
Scala 3**, and the jvm lane said so, since it compiles to Scala. int, js and v2 all accepted it. The
looseness is not what this entry is about, so it is not in the case.

**Moved here from `tests/BUGS.md`.** `lane: multi` routes to the root file (AGENTS.md §"A bug goes
in the BUGS.md of the module that owns the FIX"); it was filed under the harness because that is
where the case that caught it lived, which is the mis-routing that rule exists to stop.

A `def` declared **without** parens (`def mk: Box = Box("L")`) is handled in exactly opposite ways
by the two lanes, and there is no way to write the call that satisfies both.

| expression | native (`bin/ssc run`) | interpreter (`ssc-tools run --v1`) |
| --- | --- | --- |
| `mk` (any position: `val`, field access, argument) | auto-invokes → `L` | yields the function → `No method 'v' on FunV(<function(0)>)` |
| `mk()` | `ssc: app: not a function: Box("L")` | `L` |

Minimal repro — no import, no module boundary, three lines:

```scalascript
case class Box(v: String)
def mk: Box = Box("L")
println(mk.v)
```

Native prints `L`; the interpreter fails with `No method 'v' on FunV(<function(0)>)`.

**Portable spellings do exist**, which is what keeps this from being a blocker: `def mk(): Box`
declared *with* parens and called as `mk()`, or `val mk: Box`. Both lanes agree on both forms. The
defect is confined to the paren-less declaration.

**Why it is worth an entry rather than a note.** The failure does not name its cause: it surfaces
as `No method '<field>' on FunV` at the *use* site, which reads as a missing field on a case class,
not as a def that was never invoked. It also cannot be found by running the program the usual way —
`bin/ssc run` is green on the very file the conformance INT lane rejects, so it looks like a bad
gate. It cost me roughly half an hour on `std/credential.ssc` before the lane map explained it.

Found while landing `credential-vocabulary`; that module now uses `val credentialNone` and carries a
comment pointing here.

## v3-bridge-lazylist-crashes-with-a-java-stack-trace — the executor gained a type the bridge cannot see

<!-- status: fixed
     lane: multi
     area: codegen
     kind: bug
     gate: none
     fixed-in: 400e0aa10 -->

**FIXED 2026-08-08.** `BridgeV2` now refuses BY NAME at lowering time, so all three triggers
produce one sentence and zero stack-trace lines:

```
ssc3: …: v2 bridge V-0 does not translate `until`, which v3's executor implements and v2 does not
      — run this program with `ssc3 run` rather than `ssc3 run --bridge`
```

The refusal list is MEASURED and deliberately short — `__lazyFrom__`, `to`, `until` — because
nothing in the bridge knows what v2 implements in general. Removing a name and running the program
through `--bridge` is how to re-measure it; if v2 gains the method, the name comes out. What it does
NOT cover is the receiver-dependent case: tuple `++` is a method v2 has for lists, so it cannot be
refused by name, and `v3-bridge-tuple-concat-emits-Stub` stays open on its own.

**Found 2026-08-07 while landing SSC3-7k.** v3's executor now has a real lazy sequence. The bridge
does not, and says so like this:

```
Exception in thread "main" java.lang.RuntimeException: __method__: no dispatch for .filter on <closure>
        at ... (full Java stack trace)
```

**It fails loudly, which is already better than `v3-bridge-tuple-concat-emits-Stub` on the same
board** — that one returns a placeholder and exits 0. But a raw `RuntimeException` with a JVM stack
trace is a CRASH by this project's own standard: `corpus-report.sh` has a bucket for exactly this,
"neither ran it nor refused it cleanly", and v3's executor is held to naming the method it cannot
do. The bridge should be too.

**Where it comes from:** `LazyList.from(n)` lowers to `Invoke(__lazyFrom__)` on an Int, and every
later `map`/`filter`/`take` is an ordinary method call on the value that produces. `BridgeV2` emits
those invokes for v2, which has no such value, so dispatch fails at the far end with no idea what
was asked for.

**THREE KNOWN TRIGGERS, one cause — measured 2026-08-07, and the count is the argument for a generic
fix rather than three specific ones:**

```
LazyList.from(0).filter(…)   __method__: no dispatch for .filter on <closure>
(0 until 5).map(…)           __method__: no dispatch for .until on 0
(1 to 10).map(…)             __method__: no dispatch for .to on 1
```

Each arrived the same way: v3's executor gained a method, `BridgeV2` forwarded the invoke unchanged,
and v2 — which has no such method — failed at the far end with no idea what was asked for. None is a
REGRESSION; before each feature landed the FRONT refused those programs, so nothing ever reached the
bridge with them. Every executor method v3 adds from here will do this again until the bridge checks.

**Smallest useful fix:** `BridgeV2` should refuse an invoke v2 does not have BY NAME at lowering time — "the v2
bridge has no LazyList" — rather than emitting an invoke that dies unexplained three layers down.
Supporting laziness in v2 is a much larger question and can stay open; a stack trace cannot.

**Invariant:** I-3, a program that works on one lane and not the other. Recorded rather than fixed
because `v3/src/BridgeV2.scala` was outside the claim that found it.

## v3-bridge-tuple-concat — v2 has no tuple `++`, and for six days no gate could see it

<!-- status: fixed
     fixed-in: 28a7b2a15
     lane: multi
     area: codegen
     kind: bug
     gate: v3/exec-gate.sh -->

**FIXED 2026-08-09 in `v2/src/Runtime.scala`, one arm.** v2 ALREADY concatenated tuples — just not
from the method dispatcher. The `sconcat` prim carries the identical arm (`arithRest` reaches it),
so `(a, b) ++ (c, d)` written where v2 reads an OPERATOR worked, while the same concat arriving as a
METHOD died in `methodOp` with *"a method that does not exist was called"*.

v3 lowers `++` to `Ir.Invoke`, which is dynamic dispatch by design: Tier 0 erases types, so the
front cannot know the receiver is a tuple and pick the other spelling. The fix is the one place the
two paths disagreed — a `Tuple`/`Tuple` arm beside the list `++` arm, mirroring `sconcat` exactly:

```scala
case (DataV(lt, lf), "++", List(DataV(rt, rf)))
    if lt.startsWith("Tuple") && rt.startsWith("Tuple") =>
  val combined = lf ++ rf
  DataV(s"Tuple${combined.length}", combined)
```

**Not a new capability — a SECOND SPELLING of one v2 already had.** That is the argument for putting
it in the kernel rather than teaching the bridge a special case: the alternative was for `BridgeV2`
to recognise tuple receivers, which it cannot do, since the types are gone by then.

**Both lanes, before and after**, on `v3/tests/front/tuple-concat.ssc` (tuple concat, all four
elements read, unequal widths, and a loop-carried left operand so neither lane can fold it):

```
before   executor 5/10/8/36     bridge  java.lang.RuntimeException: a method that does not exist …
after    executor 5/10/8/36     bridge  5/10/8/36
```

The `.bridge-diverges` declaration is deleted, which the gate requires — a declaration that no
longer applies silences a real future divergence. **This was the last declared I-3 divergence in
`exec-gate.sh`.**

**History worth keeping, because the entry was wrong in both directions before this.** It was filed
as *"emits Stub — a wrong answer that does not announce itself"*. The silence was fixed separately by
`3d1d92bbd` (*"a missing method must not become output — it reached an HTTP body as data"*), from an
unrelated symptom, so the bridge had been throwing rather than printing a placeholder for some time.
And the original repro no longer reproduced as written: `bench/corpus/tuple-monoid.ssc` defines only
`workload` with no `main`, so it prints nothing at exit 0 — the numbers in the entry came from a
harness. Meanwhile nobody could see the real divergence at all, because `exec-gate.sh` compared the
executor with itself from 2026-08-07 until it was repaired
(`v3-exec-gate-ssc-differential-compared-the-EXECUTOR-WITH-ITSELF`).

## rust-lane-rejects-try-catch — both entry-point halves are fixed; this one is not

<!-- status: fixed
     lane: v2-rust
     area: codegen
     kind: bug
     gate: v1/runtime/backend/rust/src/test/scala/scalascript/codegen/rust/RustGenCargoTomlTest.scala
     fixed-in: e7dcc5163 -->

⚠ **THE TITLE AND THE FIRST HALF OF THIS ENTRY WERE WRONG, AND I WROTE THEM.** It was filed as "the
rust lane produces no binary for a hello-world". The lane was working. Measured properly on
2026-08-08:

    @main def run()          runs, prints "hello from rust"
    def main()               no binary   <- FIXED 2026-08-08
    bare top-level statements no binary  <- the real remaining defect

My probe used a bare `println`, so the generator did exactly what its own tested rule says — "No
`@main` → emit a `[lib]` target" — and `run-rust` reported `expected binary not found at <temp
path>`, a message about a missing FILE. Cargo had exited 0; the crate built, as a LIBRARY. The
message named the consequence, I filed the consequence, and "the lane is dead" was three
measurements away from "the lane wants an entry point I did not give it".

**`def main()` is an entry point now** (`fceb807e1`) — it is what `ssc run` calls and what the
interpreter, jvm, js and native lanes all start from, and the backend recognised only `@main`.
Zero-arity is required and gated: `renderMainRs` emits `fn main() { generated::<crate>::<entry>(); }`,
so a `main` with parameters would emit a call missing its arguments.

**The message names the cause now**, since that is what cost the misfiling — it says the crate built
as a library, lists the two forms that are entry points, and points here.

**The remaining half is FIXED too, 2026-08-08 (`4ac80898c`).** Bare top-level statements become the
body of a synthesized `def main(): Unit`, so entry detection, `[[bin]]`, `src/main.rs` and top-val
inlining all apply unchanged rather than the walker learning a second shape. Measured end to end on
a rebuilt toolchain, all four forms:

    val x = 41 / println(x + 1)      42          <- top-level, and the top-val inlining works
    println("plain")                 plain
    def main(): Unit                 named
    @main def run()                  annotated

**The synthesis is CONDITIONAL and that is gated in both spellings.** A file that already has
`@main` or a zero-argument `def main` keeps it — synthesizing beside one would emit two candidates
and pick by accident. Top-level `val`s are deliberately NOT moved into the synthetic body: they are
collected as `topVals` and inlined into every def that references them, so moving them would take
them away from the other defs. The 42 above is that inlining working, which is why it is the first
row of the test rather than a bare println.

**Still open, and it is now this entry's only subject:** the backend rejects `try`/`catch` outright
(`def X contains an unsupported expression: Term.Try`). That is a real gap for a failure contract —
on this target "raises" has no recoverable form — and the entry-point work does not touch it.

Two independent things, both found 2026-08-07 while measuring the `std.fs` failure contract across
lanes for `std-fs-failure-contract`, and both meaning the Rust column of `specs/std-fs-os.md` §2.1
could not be filled in.

**1. `run-rust` emits no binary for a hello-world.** The crate builds with only `non_snake_case`
warnings and then:

    run-rust: expected binary not found at /var/folders/…/ssc-rust-…/target/release/r0

on a file whose entire program is `println("hello from rust")`. No `error` line appears anywhere in
the log, so this is not a compile failure being misreported — something between cargo's output path
and the runner's expectation. Toolchain built from `ca6cce2d0`.

**2. The Rust backend rejects `try`/`catch`:**

    [error] Generic(def `show` contains an unsupported expression: Term.Try (…),Some(rust))

That is worth recording beside a failure contract rather than as a lone codegen gap: on this target
the question "does this call raise, and can I recover" has no answer expressible in a program,
because there is nothing to catch with. Any `std` contract that says "raises" is, on Rust, "aborts".

Filed at the root as `lane: rust` rather than under a backend directory because the Rust backend has
no `BUGS.md`; move it if one appears.


**FIXED 2026-08-09 in `e7dcc5163` — `throw` AND `try`/`catch`, because separately neither works.**

ON THIS TARGET AN EXCEPTION IS ITS MESSAGE. Rust has no exception type to carry, so `throw` lowers
to `panic!("{}", …)`, whose payload is a `String`, and `catch` downcasts that payload back. A `catch`
without a matching `throw` would have had nothing catchable, so the two agree BY CONSTRUCTION. That
narrowing is what this entry predicted: *"the question 'does this call raise, and can I recover' has
no answer expressible in a program"* — the answer is now the message, and nothing else.

    try b catch case e => h   ->   match std::panic::catch_unwind(AssertUnwindSafe(|| { b })) {
                                     Ok(__v) => __v,
                                     Err(__p) => { let e = __p.downcast_ref::<String>()…; h }
                                   }

`throw new RuntimeException("bang")` contributes only `"bang"`.

**Refused BY NAME:** `finally` (needs the block on the unwinding path too — a second mechanism) and
more than one `catch` arm (choosing needs the exception's TYPE, which this target erases).

**Proved by compiling and running it**, not by matching strings: `RustGenCargoSmokeTest` builds a
crate with cargo and asserts `fine / caught:bang / recovered`.

**PART 1 OF THIS ENTRY REMAINS OPEN** — `run-rust` emitting no binary for bare top-level statements
is a separate defect and is not touched here.


## v3-executor-catches-a-string-where-the-bridge-catches-the-value

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: v3/exec-gate.sh (catch-binds-the-thrown-value)
     fixed-in: e16ca28e3 -->

Found 2026-08-08 while giving the UniML projection a multi-arm `catch`. The two v3 lanes bind
DIFFERENT THINGS to the caught name:

    v3/src/Exec.scala:482    regs(x) = Value.VStr(e.message)     the MESSAGE, as a string
    the bridge (v2)          the thrown VALUE                     a DataV, as thrown

So a typed arm works on one lane and not the other:

    case class Boom(msg: String)
    def risky(k: Int): String =
      try (if k == 1 then throw Boom("b") else "none")
      catch
        case e: Boom  => "caught-boom"
        case e: Other => "caught-other"

    reference (bin/ssc)   none / caught-boom / caught-other
    v3 bridge             none / caught-boom / caught-other
    v3 executor           none / **Boom(b)** — no arm matched, so the rethrow arm fired

`__isTag__` is asked whether a `VStr("Boom(b)")` is a `Boom`, and it correctly says no.

**It was invisible until now, and that is the interesting part.** A single `catch case e: T =>` used
to project as "bind the name and run the handler for anything", ignoring the type — so both lanes
answered the same and both were wrong, which no gate here can see. Making the type mean something
turned a shared wrong answer into a lane divergence, which the corpus CAN see.

The fix is one line in `Exec.scala` — bind the value, not its rendering — but that file belongs to
`ssc3-effect-protocol`, and `Try`/`Perform`/`Handle` are exactly what that claim is redesigning.
Filed rather than reached into.

**And the projection change that would exercise it is WRITTEN AND NOT LANDED,** because landing it
alone costs more than it gives. Measured on the corpus with the multi-arm/typed `catch` in place:
N 186 → 185, DIFF 0 → 1 and CRASH 0 → 1, the crash being
`try-catch-exception-delivery` failing with `/ by zero` — the arm that should have caught it no
longer matches, because the caught value is a string. So the order is: fix `Exec.scala:482` first,
then the projection can stop ignoring the type in one move. The diff is small — replace the
single-arm shortcut with a `match` on the caught value plus a rethrow arm.


**FIXED 2026-08-10 in `e16ca28e3`, and the loss was at the THROW rather than the catch.**
`__throw__` built `ExecError(showV(value))`, so the value was already gone by the time the catch arm
ran; binding `VStr(e.message)` was the second half of one mistake. `ExecThrow(value, rendered)`
carries it.

    before   method 'msg' on Boom(b)' is not implemented by v3's executor
    after    none / b:7 / plain      — same on the bridge

**A NARROWER FIRST CUT WAS REVERTED, recorded because the reasoning was wrong in an instructive
way.** Catching only `ExecThrow` looked right — the executor's own failures are not values a program
threw — and turned `exec-gate` red on `try-catch.ssc`, which catches a DIVISION BY ZERO. That is a
language-level exception Scala lets a program catch, not an internal error. **v3 does not
distinguish "the language raised" from "the executor failed": both are `ExecError`**, so refusing to
catch them refuses the first to be rid of the second. Both are caught; the BINDING differs. Drawing
that line properly is a real change and deserves its own entry.

**This entry's own reproduction no longer runs**, and it is left as written: it uses a MULTI-ARM
`catch`, which v3's front now refuses outright — *"a `catch` arm binds one name at Tier 0"* — so it
stops before the executor. The single-arm form above isolates the same defect.

The fixture pins both directions: a thrown case class arrives with its fields readable, and a thrown
STRING arrives as a string — a fix that wrapped every payload would have been just as wrong.


## v3-loses-a-mutation-to-a-captured-var — both v3 lanes answer 0 where the reference answers 6

<!-- status: fixed
     lane: multi
     area: codegen
     kind: bug
     gate: v3/tests/front/captured-var-mutation.ssc
     fixed-in: b17e7141b -->

**FIXED in `b17e7141b`** — "box a captured mutable local, a one-element array was already in the
vocabulary". Re-measured 2026-08-08 across four shapes, executor and bridge agreeing with the
reference on every one:

    List(1,2,3).foreach { n = n + i }     6      (was 0 on both v3 lanes)
    for i <- 0 until 5 do q = q + i       10     the shape that originally surfaced it
    nested lambdas both assigning         90
    a var CAPTURED but never assigned     14     the case that must NOT be boxed

**A correction to this entry's own closing advice.** It said "it belongs as a conformance case,
since only an output comparison against another lane can distinguish mutated from did-not". A
conformance case would NOT have caught this: `contract.sc`'s lanes are `int`, `js`, `jvm`, `v2` —
**v3 is not one of them**. The gate that fits is the v3 front fixture, and it is the one that
landed: `captured-var-mutation.expected` pins `6 / 10 / ab / 30 / 8`, and the defect made the first
row `0`, so the file discriminates the two states rather than merely running.

That distinction is not pedantry here. The entry's own headline is that BOTH v3 lanes agreed and
both were wrong, so agreement proved nothing and only an oracle could see it — and the oracle that
can is a pinned expected output on the lane that has the defect, not a corpus that never runs it.

Found 2026-08-08 while adding `to`/`until` to v3 — `for i <- 0 until 5 do n = n + i` printed 0, and
the range was not the cause.

    def main(): Unit =
      var n = 0
      List(1, 2, 3).foreach { i =>
        n = n + i
      }
      println("foreach: " + n)

    lane                    result
    reference (bin/ssc)     foreach: 6
    v3 executor             foreach: 0
    v3 bridge               foreach: 0

**BOTH v3 LANES AGREE, AND BOTH ARE WRONG** — so neither the lane parity gate nor the front
differential can see it. Agreement is not correctness; what caught it was the reference.

The cause is not `for`. v3's lambda lifting passes captures as leading PARAMETERS, so a captured
`var` is copied and the assignment inside the lambda mutates the copy. `for … do` desugars to
`foreach`, which is why the range work surfaced it, but any lambda assigning to an outer `var` has
it. The reference lane captures the environment by reference.

**A fix is not small.** A captured mutable variable has to become a CELL — v2 already exposes
`cell.new` / `cell.get` / `cell.set`, so the vocabulary exists on both lanes — and the lowering has
to decide which locals need boxing (those assigned inside a lambda that captures them) rather than
boxing every capture, which would cost every closure. Filed rather than attempted at the end of a
long session.

**No gate names this.** It belongs as a conformance case, since only an output comparison against
another lane can distinguish "mutated" from "did not".


## a-repeated-key-in-a-bugs-header-is-kept-silently-and-the-last-one-wins — main red on a closed entry

<!-- status: fixed
     lane: apparatus
     kind: apparatus
     area: build
     gate: tests/e2e/bugs-index-gate.sh --self-test
     found-by: claude-code
     found-at: 2026-08-19
     fixed-in: ef855d546 -->

**A HEADER FIELD WRITTEN TWICE PARSED TWICE, and the parser kept the second:**

    fields[fm.group(1)] = fm.group(2).split("·")[0].strip()

`an-undefined-name-in-a-pattern-means-three-different-things` was closed on 2026-08-18 by adding
`fixed-in: b427403ec` to the TOP of a header that still ended with the `fixed-in: -` placeholder from
when it was open. Both lines parsed; the placeholder won; and `smoke` went red on main with

    FAIL [an-undefined-name-in-a-pattern-means-three-different-thi] fixed-in `-` is not a commit sha

about an entry whose sha was correct and sitting two lines above. 118 of 119 checks were green — the
red was one stale line, and the message pointed at the wrong one of the two.

**THE CENSUS FOUND THE OTHER DIRECTION, which is what makes this a defect rather than a typo.**
Across 1137 headers exactly two carried a repeated key that a verdict is taken from. The second,
`f-char-literal-escape-without-a-named-branch-emits-the-character-not-its-code`, had TWO REAL SHAS —
`7dd1c17a7` and `39d99eed7` — and the gate passed it in silence, having discarded the first. One
defect, two symptoms: red about the wrong value, or green while losing one.

**FIXED by refusing a repeat of the keys a verdict is read from** — `status`, `lane`, `area`, `kind`,
`fixed-in` — and only those. `gate:` legitimately repeats where an entry is guarded by two gates, and
two entries in this tree are; nothing decides on it, so repeating it costs no verdict.

**THE CONTROL IS THE SILENT CASE, not the red one.** The old gate on the pre-fix `tests/BUGS.md`
reports `problems: 0` and OK; the new gate on the same file names the duplicate. The red entry would
have gone red either way — the guard is worth having because of the one that did not.

## an-undefined-name-in-a-pattern-means-three-different-things — two lanes ignore it, one throws

<!-- status: fixed
     fixed-in: b427403ec
     lane: multi
     area: front
     kind: bug
     gate: tests/e2e/pattern-undefined-name-gate.sh -->

> **THE `int` ROW IS FIXED, 2026-08-18 (`843b4c3e0`), and the entry stays open for the other two.**
> The matcher's `Term.Name` arm now refuses an unresolved capitalised name with v3's own sentence
> and a position — `[ERROR] [line 3, col 3] unknown constructor 'Nope' in a pattern` — instead of
> silently not matching. The precondition this waited on landed first: `c9b698622` put the typer at
> **0 false positives over all 400 corpus cases**, and the change's own oracle was the corpus with
> the refusal live — 369 / 0, with 238 cases declaring the int lane, plus `testFast` 1621 / 0. Enum
> cases, case objects and the lowercase binder are unchanged (probed; the gate's control row pins
> the last).
>
> The gate's `int` row moved from KNOWN-RED freeze to asserting the fix, exactly as its header
> instructs, and additionally asserts `NO MATCH` does not print.
>
> **THE `native` ROW IS FIXED TOO, 2026-08-18 (`99ac149fa`) — in BOTH fronts, which is the condition
> the parked map set.** F checks at its three arm-emission mouths (the third, `parseCtorArm`, was
> found by the probe still printing NO MATCH under `SSC_FRONT_STRICT` after the first two were
> wrapped); the reference lowering checks at `ctorPatternArms`, its single mouth, against the
> registry it already had. Both matter because the driver falls back to the reference front when F
> declines — a refusal in F alone would reroute into the old silence. The full-corpus census
> amended the known set three times (lowercase effect-op heads; the runtime's
> `Yielded`/`Errored`/`Exit`/`Return`; `case object` tags via a new `caseObjNamesCell`) and
> converged 11 fails → 3 → 0 over three full runs, 369/0 at the end. The gate's native row now
> asserts the fix.
>
> **THE `js` ROW — THE LAST — IS FIXED, 2026-08-19 (`b427403ec`), AND THE ENTRY CLOSES: three lanes,
> one sentence.** The emitter wrote the unresolved name into the test as a raw identifier, so the
> compile error became a run-time `ReferenceError` firing only when the match was REACHED. The first
> cut refused at EMIT time against every set the generator can consult, and the corpus stopped it at
> 97 failures and climbing — content values, UI nodes and plugin ADTs match on tags the RUNTIME
> constructs, visible in no emit-time set. Emit-time cannot know resolvability; the BUNDLE can: the
> test now reads `typeof N !== 'undefined' ? N : _unknownCtorPat('N')`, so a resolved name costs one
> `typeof` and behaves exactly as before, and an unresolved one throws the shared sentence at the
> same moment the ReferenceError used to fire. js cannot refuse earlier than the test — measured,
> not asserted. Full corpus 369/0. The gate's three rows all assert the refusal now, and its verdict
> line reads what is finally true: **three lanes, ONE meaning.**
>
> **THE NATIVE HALF IS MAPPED, 2026-08-18, AND PARKED — not abandoned — for a live claim conflict.**
> What the dig established, so the next taker starts here and not at the decoy:
>
> - **`bin/ssc run` compiles the probe with F** (`bin/ssc info --front-report` says `pat1.ssc F`),
>   NOT with `v2/lib/ssc1-front.ssc0`. The first hour of this dig went into ssc1-front/ssc1-lower —
>   the exact trap `ask-which-front-compiles-it-before-editing-a-front` records.
> - **In F** (`specs/v2.2-p6.5-fsub.ssc`): a bare uppercase name parses to `("cpat", (tag, Nil))`
>   (`finishCtorPatF` ~:2238) and is emitted UNCONDITIONALLY as a CoreIR text arm by
>   `dischCpat3`/`genArmStr` (~:2321) — an unknown tag becomes an arm that never fires. F keeps NO
>   declared-constructor registry today; the fix needs one (case classes + enum cases + imports).
> - **The twin in the reference lowering** (`v2/lib/ssc1-lower.ssc0`): `ctorPatternArms` (:3479),
>   equally unconditional — but there the registry ALREADY EXISTS: `collectCaseClassOrder` (:5391)
>   fills `caseFieldOrderCell` with every case class and enum case, objects included, BEFORE
>   `lowerStmts` runs. A fix must land in BOTH fronts or the divergence changes shape (two-front
>   pairs).
> - **The refusal channel on the self-hosted path** is `#__throw__` — the only error prim the ssc0
>   runtime exposes (`Runtime.scala:1725`); thrown DURING lowering it aborts compilation, but
>   UNPOSITIONED (pattern nodes carry no line:col), so whether that is acceptable, or positions get
>   threaded first, is the taker's first decision.
> - **Parked because** `v2-paren-cons-arm` (live heartbeat, 2026-08-18) holds
>   `specs/v2.2-p6.5-fsub.ssc` and is working in exactly F's pattern lowering.

Found 2026-08-07 while re-checking whether `case Ⅷ =>` still diverges across lanes — it does not,
and the correction is in `uniml/SPRINT.md` under UNIML-SSC3-ALPHABET. The probe was about Unicode;
the ASCII **control** is what carried a defect.

    def main() =
      val x = 1
      x match
        case Nope => println("BOUND, value=" + Nope)
        case _ => println("NO MATCH")

    lane      result
    int       NO MATCH                             silent, exit 0
    native    NO MATCH                             silent, exit 0
    js        ReferenceError: Nope is not defined  throws at run time, exit 1

**Scala 3 rejects this at compile time** — `not found: value Nope`. The name is capitalised, so it
is a stable-identifier pattern rather than a binding; no lane resolves it, and each invents its own
answer for the unresolvable case. The lowercase control binds on every lane, so the capitalisation
rule itself is fine — what is missing is the resolution check behind it.

js emits the reference verbatim and lets the host decide:

    if ((_t1 === Nope || (_t1 && _t1._type === 'Nope'))) {

so a compile error arrives as a run-time `ReferenceError`, and only when that `match` is REACHED —
a pattern in a cold branch ships and throws in production.

**int and native are not better, only quieter.** A silent non-match means a typo'd or renamed
constructor stops matching with no diagnostic: the arm simply never runs.

Same shape as `multi-name-val-binds-garbage-and-says-nothing` directly below — a construct no corpus
covers, each lane answering differently, and the quiet lanes being the dangerous ones. A fix belongs
in the fronts' resolution step rather than per backend: if the front refuses an unresolved
stable-id pattern, all three agree by construction.

**Toolchain, checked rather than disclaimed:** reproduced with a toolchain built from `ca6cce2d0`
while the repo was at `c5f59d368`. Of the 171 files changed across those 218 commits, none touches
pattern resolution or the typer, so the measurement holds for this question; confirm on a fresh
build before closing.

**v3 answers a FOURTH way, and it is the one this entry asks for.** Measured 2026-08-07 on both
v3 lanes and both v3 fronts:

    ssc3: nope.ssc:4:10: unknown constructor 'Nope' in a pattern

A positioned refusal at the pattern, before anything runs — which is exactly "a fix belongs in the
fronts' resolution step rather than per backend", implemented. So the recommendation above is not a
proposal any more; there is a working front that does it and two lanes that agree by construction
because the refusal happens before either is reached.

**And chasing it found the same defect in v3, for NON-ASCII names.** v3's parser decided
constructor-versus-binder with `>= 'A' && <= 'Z'`, so `case Éric =>` read as a BINDER that matches
everything and printed `BOUND 1` — the quiet, dangerous answer this entry warns about, in the lane
that otherwise gets it right. Fixed by `Character.isUpperCase`, chosen after comparing it against
`UniAlphabet.isTypeNameStart` — UniML's own curated table — over every code point in the BMP: they
disagree on ZERO, so the two fronts agree by construction rather than by keeping two tables in step.
Covered by `v3/tests/front/unicode-capitalisation.ssc` (runs, both fronts) and
`unicode-unresolved-ctor.ssc` (refused), and the fixture was observed failing with the ASCII rule
put back.

**GATED 2026-08-07** by `tests/e2e/pattern-undefined-name-gate.sh`, which pins the three rows above
plus the lowercase control, and fails in every direction: a lane that stops matching its row, and —
separately asserted — js coming to agree with the other two, which would otherwise leave three rows
passing individually while the divergence they exist to report had quietly gone. Proved by planting
each drift in turn and watching it go red; the baseline is green.

**It pins the DEFECT, not the fix, and that is deliberate.** The wanted behaviour is a compile error
on all three lanes and no lane produces one, so a gate asserting it would be red on arrival and
could not land. If a lane starts rejecting the program, its row is to be DELETED rather than
loosened — the row becoming wrong is the point of it.

**A conformance case was costed and rejected for now**, not overlooked: `CONTRACT.md` makes every
newly selected name RED as `NEW` for everyone until a full unsharded `int,js,v2` run refreshes the
paired freeze, over 392 cases and needing a toolchain rebuild first. That is the right home for this
row eventually — an expected-output comparison is what distinguishes "no match" from "did not run" —
but it belongs in a commit that is already refreshing the baseline.


**HALF OF IT LANDED 2026-08-10 in `5a194d2b8`, and the entry stays OPEN — deliberately.** The front
now HAS the check: `Typer.bindPatVars` had cases for `Pat.Var`, `Pat.Extract`, `Pat.Typed`,
`Pat.Bind` and then `case _ => Nil`, so a capitalised bare name resolved to nothing and fell through
in silence. It now reports `unknown constructor 'Nope' in a pattern` — the same sentence v3 already
produced, so the two fronts do not each invent one.

Four tests, three of them CONTROLS, because the capitalisation rule was never the defect: a lowercase
name still binds, a real constructor still matches, a locally declared `case object` is still
accepted. Proved to discriminate. `core/test` 1158 passing, 0 failing — no false positives in the
front's own suite.

**THE THREE ROWS ABOVE ARE UNCHANGED, and that is measured, not assumed.** `ssc run` is silent BY
DELIBERATE POLICY: `Main.scala` type-checks on the run path only under `SSC_JIT_TYPESTATS`, with the
comment *"best-effort (never fail the run on a type error)"*. After this change `run --v1` still
prints `NO MATCH`. `run-jvm` does fail, but on `E006 Not Found` from the generated Scala — not this
diagnostic.

**So the remaining half is not a missing check; it is a POLICY.** Every path that surfaces Typer
errors now reports this (the JVM build path exits 1 on `typed.hasErrors`, watch mode prints them).
Making `run` fail on a type error is a decision with an owner, exactly like the two import questions
settled this week, and the gate's rows stay as they are until someone takes it.


## multi-name-val-binds-garbage-and-says-nothing — `val a, b = 1` on four lanes, four answers

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: tests/e2e/multi-name-val-gate.sh
     fixed-in: 7eb8f1ec1 -->

**FIXED on int and js; the two SILENT WRONG answers are gone.** The table now reads:

| lane | `println(a)` | `println(b)` |
| --- | --- | --- |
| int | 1 | 1 |
| js | 1 | 1 |
| jvm | 1 | 1 |
| v2 / F | declines the file | declines the file |

Three lanes agree and the fourth refuses loudly — which this entry already called the only defensible
one of the wrong answers, since it cannot be mistaken for a value. `<native:a>` and `<function>`,
internal placeholders arriving at exit 0, no longer exist.

**The load-bearing measurement was made BEFORE writing either fix, and it changed the fix.** Scala's
`val p₁, …, pₙ = e` is `val p₁ = e; …; val pₙ = e` — the right-hand side is evaluated ONCE PER NAME.
The obvious implementation evaluates once and binds the value to every name, and it would have passed
every row this gate had. Asked of the jvm lane first, which gets the semantics free by emitting the
form verbatim to Scala:

```scalascript
var c = 0
def bump(): Int = { c = c + 1; c }
val a, b = bump()      // jvm: a=1, b=2, c=2
```

So both fixes evaluate per name, and the gate now pins that with its own row.

**Three code paths, not one, and the first two I fixed were the wrong ones.** A `val` at TOP LEVEL
goes through `StatRuntime.execStat`; a `val` in a `direct` block through `BlockRuntime.step`; and a
`val` in an ordinary body — the case in the repro — through `BlockRuntime.evalBlock`, which has its
own `Defn.Val` arm with a fast path for the single-`Pat.Var` shape. Patching the first two left the
repro printing `<native:a>` unchanged; the top-level probe is what separated them, because it started
working while the block one did not. js had a single site, and it was not missing a case at all: it
matched the shape and emitted `/* multi-pat val */`, a comment.

**The gate pinned the defect and refused to be loosened, which is why it is rewritten rather than
edited.** Its own failure message said: *"If this lane now binds BOTH names and prints 1, that is the
fix — delete this lane's row instead of loosening it."* Both `KNOWN-RED` rows for int and js are
deleted and replaced by assertions; native keeps its declared red; the per-name row is new; and the
jvm probe was asking `bin/ssc run-jvm`, the STANDARD tier, which refuses that subcommand — so the
lane the entry calls the reference had always reported UNMEASURED. It goes through `ssc-tools` now
and is checked. Verified in both directions: 6 failures against the pre-fix toolchain, 0 after, with
the single-name control rows green in both.

Found 2026-08-06 while writing a control for `uniml-block-stops-at-comma`: the control needed a
comma at paren-depth 0 inside a block, `val a, b = 1` was the obvious way to produce one, and it
did not parse. Asking the reference front — the step the UniML backlog entry prescribes before
implementing any construct — produced this instead.

    def main(): Unit =
      val a, b = 1
      println(a)      // and, separately, println(b)

    lane      println(a)      println(b)
    int       <native:a>      ERROR Undefined: b
    js        <function>      crashes Node
    v2 / F    1               rejects the file: "structural CoreIR contains parser sentinel _err"
    jvm       1               1

**Only jvm is right.** Two lanes print a VALUE that is not the value — `<native:a>` and
`<function>` are internal placeholders leaking into user output, with no error and exit 0, so a
program carries them forward until something else breaks somewhere unrelated. That is the whole
defect: not that the form is unimplemented, but that two lanes answer confidently and wrongly.

The second name is a separate axis from the first, which is why the table has two columns: `int`
and `js` bind SOMETHING to `a` and nothing to `b`, `v2/F` binds `a` correctly and declines only
once `b` is used, and jvm binds both. Three names is worse — on int, `val x, y, z = 7` leaves
`x` undefined too, so the count of names changes which of them exist.

**F's behaviour is the only defensible one of the three wrong ones** and worth keeping in mind if
this is fixed by removing rather than implementing the form: it refuses the file instead of
inventing a binding. UniML's ScalaScript dialect refuses too, with a diagnostic pointing at the
comma — that is what surfaced this.

**The prescribed next step was refuted, and that is the point.** `uniml/BACKLOG.md` said "Scala has
the form, so the likely answer is that it should parse". The oracle disagrees with itself across
lanes, so "what the reference front does" was not an answer here; the measurement was.

**GATED 2026-08-08** by `tests/e2e/multi-name-val-gate.sh`. Neither the conformance corpus nor the
examples corpus uses a multi-name `val`, which is why a construct that silently mis-binds on two
lanes went unnoticed for so long.

**RE-MEASURED before gating rather than inherited**, because an entry two days old had already
turned out to be a stale source once this week. The substance holds; two details moved. js on
`println(b)` now gives a clean `ReferenceError: b is not defined` rather than crashing Node, and
native refuses with `rejected incomplete parse` rather than the CoreIR sentinel message. Both are
the same behaviour under a different string.

**jvm is UNMEASURED and the gate says so out loud** at every run rather than omitting the lane.
`bin/ssc run-jvm` needs the optional tools/compatibility component, which a plain
`./install.sh --dev` does not install, so the one lane this entry records as CORRECT is the one that
cannot be confirmed here. A missing lane nobody mentions reads as a lane that passed.

**The gate asserts the silence separately from the values.** The three rows would still pass if a
lane began printing a placeholder *and* reporting failure — but exit 0 is what makes this travel, so
`int` and `js` returning 0 while printing `<native:a>` / `<function>` is its own check.

**Its own A/B found a defect in it, which is why the check functions are split in two.** Asserting
"native prints 1" with a substring match passed against native's REFUSAL, because the temp path in
that message contains a `1`. Rows claiming a lane got it RIGHT now compare the whole output; rows
claiming a lane FAILS in a particular way still match a substring, since a diagnostic legitimately
carries paths and line numbers. Verified by planting each drift in turn — native accepting, int no
longer leaking, and both single-`val` controls — all red, baseline green.

A conformance case remains the eventual home for the four-lane row, in a commit already refreshing
the paired freeze; the cost of doing it alone is recorded under
`an-undefined-name-in-a-pattern-means-three-different-things`.

## package-root-import-needs-an-exports-entry-on-int — and needs nothing on native

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: sbt backendInterpreter/testOnly *PackageRootImportTest*
     fixed-in: fa9249f0e -->

Found 2026-08-05 while implementing `native-front-has-no-package-namespace`, checking whether the
new namespace should honour `exports:`. It should not, and does not — **both lanes expose a
non-exported name through the package path**, because v1 wraps the whole module in the package
objects and gates only the flat import bindings. That part agrees.

What does not agree is importing a module BY ITS PACKAGE ROOT when the module declares `exports:`:

```
--- lib.ssc                     --- main.ssc
package: p                      [p](./lib.ssc)
exports:                        println(p.shown())
  - shown                       println(p.hidden())

def shown()  = "shown"
def hidden() = "hidden"
```

```
int     ->  [ERROR] 'p' is not exported by ./lib.ssc — add it to that module's `exports:` …
native  ->  shown / hidden      (both fronts)
```

Adding `p` to `exports:` makes both lanes print `shown / hidden`, so this is only about the ROOT
name. `SectionRuntime.runImport` checks every binding against `child.exportedNames`, and the link
`[p]` binds the MODULE, whose name here is the package root — not a member, so the check rejects it.
Real code mostly binds member names (`[jsonStringify](std/json.ssc)`), which is why nobody hit it.

**DECIDED 2026-08-09 by Sergiy — a package name is a NAMESPACE, so the native lane is right and
the interpreter is wrong.** You export names IN a namespace; you do not export the namespace. The
interpreter must stop requiring the package ROOT in `exports:`.

**Where it goes:** `SectionRuntime.runImport` checks every binding against `child.exportedNames`
(`SectionRuntime.scala:481`). The root is available beside it as `childPkg.head` — `childPkg` is
`child.exportedPkg`, already in scope at line 446 for `lookupExport`. Exempt a binding whose name is
that root; leave every other binding gated exactly as now.

**One measurement belongs with the decision, because it removes the strongest argument for the other
side.** This entry already records that adding `p` to `exports:` makes BOTH lanes print
`shown / hidden` — the non-exported member included. So `exports:` does not restrict what is
reachable through the package path either way; the interpreter's check gates only the ACT OF
ENTERING, not the contents. Choosing "the root is always importable" therefore gives up no
protection that exists today. It would be worth a separate entry that a package path bypasses
`exports:` on both lanes — that is the real hole, and it is not this one.

**Not implemented here:** `SectionRuntime.scala` is held by the live claim `v1-runtime-std-rename`.
The decision is recorded so whoever holds that file next does not have to re-open the question.

**Superseded reasoning, kept:** A package name is a namespace rather than a member, so *"the root is
always importable"* and *"declare what you export, including the root"* are both defensible; picking
one silently in a lane fix is how the two lanes drifted in the first place. No gate: writing one
before the decision would freeze whichever answer runs today.

**IMPLEMENTED 2026-08-09 in `fa9249f0e`.** `runImport` exempts a binding whose name is the child's
package root — `childPkg.headOption.contains(sourceName)`, the same root `packageMembers` walks from,
so the two agree by construction. Every other binding stays gated exactly as before.

`PackageRootImportTest` pins both directions, and the second test is the one that matters: an
ordinary non-exported member is STILL refused, so the exemption is the root and nothing else. Proved
to discriminate — disabling the exemption fails the first test and leaves the second green.

**Pre-existing red, stated so this is not misread:** `backendInterpreter/test` is 1854 passing and
**14 failing both before and after**, verified by removing the change and the new file and re-running
the four that could plausibly have been mine — the two content-toolkit transitive-import tests and
the JS/JVM "directly imported Markdown content namespaces" pair all fail on a clean tree, as do nine
money/currency ones. None is caused by this change and none is filed here.

**The hole this did NOT close, restated because it is now the only thing left in this area:** a
package path reaches non-exported members on BOTH lanes. `exports:` never restricted that, and this
change did not make it worse. It deserves its own entry.


## an-example-uses-a-syntax-the-language-does-not-have-and-no-gate-runs-it

<!-- status: fixed
     lane: multi
     area: conformance
     kind: bug
     fixed-in: 565079c54b7c81cbc819b59e6a75b06193f6c7e7
     gate: - -->

**FIXED 2026-08-08 — the three `@side = server` lines are gone, and removing them did more than
silence a diagnostic.** The directive made the whole BACKEND fence fail to parse, so its three
routes were invisible to the emitter. Measured by diffing `emit-spa --frontend react` before and
after:

    without the directive   `_ssc_typedRouteClients` with getApiEmployees,
                            postApiEmployeesDelete, postApiEmployeesPromote
    with the directive      absent — 16 KB smaller, no API clients at all

So the example was emitting a React frontend whose backend calls did not exist, at exit 0. That is
the cost of the invalid syntax, and it is not what the entry expected to find.

**The author's decision, taken 2026-08-08:** delete the directive, keep the example. `@side` remains
described in `specs/electron-jvm-rest-backend.md` §288 as something later phases may introduce; what
is removed is a use of it in code, three years ahead of the implementation.

**Not a runner's job, which is why no gate ever ran it.** The file is `frontend: react` — it is
exercised by `emit-spa`, not by `ssc run`, and `run --v1` on it fails at RUNTIME (`Instance is not
callable`) even with the parse fixed, because a frontend example is not a program. The entry's
framing, "no gate RUNS it", was looking for the wrong kind of gate.

~~STILL OWED: the file is not in `contract-roster.tsv`~~ — **that debt was wrong, and I wrote it.**
The file is out of the corpus contract's scope BY CONSTRUCTION, not by omission: `contract.sc`
collects with `os.list(dir)`, which is not recursive, so it walks `examples/*.ssc` and never
`examples/frontend/*/*.ssc`. Measured 2026-08-08: **zero of the six frontend examples are in the
roster**, and adding this one alone would make it the only runnable-by-nothing case in a corpus of
programs the contract RUNS.

Which is the same point the paragraph above already makes and I then contradicted a line later: the
gate that fits a `frontend: react` file is an emit comparison. Widening the corpus to
`examples/*/*.ssc` would pull in every frontend example, none of them runnable — a decision about
corpus scope, not a bookkeeping debt.

`examples/frontend/data-table/data-table.ssc` writes `@side = server` three times. The language
has no such construct: it appears in `specs/electron-jvm-rest-backend.md` §288 as something
"later phases **can** introduce", and the reference front rejects it —

    $ bin/ssc-tools run --v1 <the same three lines>
    error: failed to parse scalascript block: expected start of definition
      @side = server
            ^

so the file has never been valid ScalaScript. It is the only file in the repository using the
syntax, it is not in `tests/conformance/contract-roster.tsv`, and nothing under `tests/`,
`scripts/` or `.github/` names it. An example nobody runs, written in a syntax nobody implemented.

**Why this is filed rather than fixed here.** What the example SHOULD say is the author's call —
delete the directive, implement `@side`, or move the file out of `examples/` — and each answer
means something different about whether the partitioning idea is alive.

**The general shape is the more valuable half.** It was found by UniML's breadth probe, which
counted these three as parse gaps in the ScalaScript dialect. Teaching the dialect to accept
`@side = server` would have "improved" breadth by making it accept what the language rejects.
That is the third time in two days a corpus number has rewarded the wrong thing — first markdown
prose in untagged fences, then English sentences beginning with `class`, now invalid code — and
the lesson each time is the same: a corpus is only an oracle for constructs the language actually
has. `uniml/BACKLOG.md` carries the first two.

## multipart-upload-three-lanes-three-answers — only js parses a file part correctly
<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/upload-smoke.sh
     fixed-in: 7eb265347 -->

**Measured 2026-08-04**, `examples/uploads.ssc` served on each lane, one `curl -F` with a 256-byte
`application/octet-stream` payload:

| lane | `POST /upload` |
|---|---|
| int | `native HTTP handler failed: For input string: "-"` |
| js | `filename=payload.bin\|content-type=application/octet-stream\|size=256\|…` — **correct** |
| native | `missing 'file' part` |

**Three lanes, three different answers, and only one of them right.** Each fails in its own way:

- **int** throws a NUMBER-PARSE error on the string `"-"`. A multipart body's delimiters begin
  `--<boundary>`, so something in that path is reading a boundary fragment as an integer. Note the
  message says *native* HTTP handler while the program was run with `run --v1`; whether the
  interpreter lane is delegating to the native serving plugin is a separate question this entry
  does not answer.
- **native** answers `missing 'file' part` — at HTTP 200. That is the expensive shape: not an error,
  a WRONG ANSWER that a status-code check cannot see. `req.files` simply does not contain the part.

**Found by `upload-smoke`, which could not have said this.** That gate ran `$BIN/sscc` — the
JVM *compiler* — as one of its three "backends" and labelled `$BIN/ssc` (the native lane) `INT`, so
its report was one failure message attributed to the wrong lanes. With the runners corrected
(`orphaned-e2e-gates-52`) the three answers above are what it is actually looking at.

**Not reduced further.** The boundary-parse and the missing-part are probably two defects, not one,
and nothing here proves they share a cause.


**Re-measured 2026-08-05 — one of the three rows is stale, and the remaining one is not the defect
this entry describes.** Same `examples/uploads.ssc`, same 256-byte `curl -F`, both lanes booted on
the port the example actually binds (8766; it ignores `--port`, which is how the first attempt
measured nothing at all).

| lane | then | now |
|---|---|---|
| int | `native HTTP handler failed: For input string: "-"` | **`filename=payload.bin\|content-type=application/octet-stream\|size=256\|first=67\|last=29`** — correct |
| js | correct | correct |
| native | `missing 'file' part` | `missing 'file' part` — unchanged |

So the int row does not reproduce. Given this gate's own history — it ran the JVM *compiler* as a
"backend" and labelled the native launcher `INT` — the likeliest reading is that the `"-"` failure
was never the interpreter's: the message said *native* HTTP handler while the run was `run --v1`,
which the entry itself flagged as unexplained. It is not carried forward as an int defect.

**What is left is not a parse bug — the native lane has no multipart implementation at all.**
Searched the whole `v2/` tree: the only occurrence of the word `multipart` outside tests is a
`Content-Type` header being WRITTEN by the PDF/MIME plugin. Nothing reads a boundary anywhere.
`HttpFastNativePlugin` declares `files` in `registerFields("Request", …)` and never populates it, so
`req.files` is permanently empty and every upload handler takes its own else-branch. That is why the
answer is HTTP 200 with the user's own `missing 'file' part`: the lane is not failing, it is
answering a question it cannot answer.

**Which makes the remaining work a FEATURE, not a fix**, and it has an architectural fork worth
stating rather than deciding in passing:

- `v1/runtime/http-server/common/.../Multipart.scala` already exists and is the parser the correct
  lanes use. Reusing it means adding `httpServerCommon` to `v2NativeHttpFastPlugin`'s dependencies —
  least code, but it pulls a v1 module into the v2 native plugin, which cuts against the separation
  the v2 tree is built on.
- Otherwise a small parser lives in `httpFastEngine` (which already owns `HttpProtocol`), and the
  plugin fills `files` from it — more code, no cross-tree dependency.

Not started. The measurement is what this update is for: whoever picks it up should know they are
implementing multipart on that lane, not repairing it.


**FIXED 2026-08-05 — implemented, not repaired.** The re-measurement above found the native lane had
no multipart at all, so this was a feature slice: a parser now lives in the fast engine
(`MultipartFast`, beside `HttpProtocol`) and `NioNativeHttpServerHost.filesValue` fills the `files`
slot that had been a hardcoded empty map.

Own parser rather than reusing `scalascript.server.Multipart` — the owner's call, and the reason is
architectural: that parser is in the v1 `http-server/common` module, and depending on it from the v2
native plugin would pull a v1 module into the tree v2 exists to be independent of.

`UploadedFile` is registered with `registerFields` in the same order as the DataV built by the host
and as `extern class UploadedFile` in `v1/runtime/std/http.ssc`. That order is load-bearing: field
access on this lane is by INDEX, so a layout disagreement reads a NEIGHBOURING field instead of
failing — the shape of a whole known bug family.

**One blocker surfaced on the way and is filed separately**: `String.codePointAt` had no dispatch on
the native lane at all (`v2-string-codePointAt-not-dispatched`). The example reads upload bytes with
it, so multipart could not be demonstrated until it existed.

**Gate:** `tests/e2e/upload-smoke.sh` grew a fourth cell. It had covered INT/JVM/JS and its own
header said native was "not covered here" — the lane that was broken was the one nobody was
watching. All four now agree byte-for-byte on the 256-byte roundtrip, including the first and last
byte values, which is what proves the raw bytes survive the ISO-8859-1 view.

Known limit, stated rather than discovered later: this engine keeps every part in memory and always
reports `path = ""`. The other lanes spool parts over a threshold to a temp file and set `path`;
that threshold is configured through v1 server settings this engine does not have.

## triple-quoted-literal-ending-in-a-quote-is-not-a-string — three lanes agree, and all three are wrong
<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: tests/e2e/triple-quote-trailing-quote-gate.sh
     fixed-in: cb620c16a -->

**Measured 2026-08-04.** Four lines:

```scalascript
val a = """x""""          // content is  x"  — of the four closing quotes, one is content
println("[" + a + "]")
val b = """x"""           // control: no trailing quote in the content
println("[" + b + "]")
```

| lane | first line | control |
|---|---|---|
| int | `List(x")` | `[x]` |
| js | `List(x")` | `[x]` |
| jvm | `List(x")` | `[x]` |
| **native** | **`[x"]`** | `[x]` |

The concatenation does not produce a String at all — it produces a **List**. The control is right
everywhere, so this is the trailing quote and not triple-quoting in general. **Three lanes agree
with each other and all three are wrong**: int, js and jvm share the front that mis-lexes this, and
native has its own. A majority is not a verdict.

**How it was found, which matters more than the repro.** `std-ui-forms-smoke` failed with

```
InterpretError: [line 36, col 196] Undefined: impl
```

`impl` appears in no source file in this repository, and the reported position does not exist in the
file it names. Bisecting put it in one component, then in `examples/std-ui/input.ssc`'s `render`,
then in a single line:

```scalascript
val inv = if error.nonEmpty then """ aria-invalid="true"""" else ""
```

Everything downstream of that literal — three `${raw(...)}` inside one `html` interpolation — then
failed to resolve a name nobody wrote. **The diagnostic is not merely imprecise, it is unrelated to
the cause**, and it names a position that cannot be inspected. That is why the gate which hit this
sat red and unread: nothing in the message suggests a string literal.

**Blast radius is wider than one demo.** Any `.ssc` writing an HTML attribute the natural way —
`""" aria-invalid="true""""` — silently gets a non-String on three of four lanes.

**Declared, not left red.** The gate counts native and declares int/js/jvm as gaps; each cell
**fails if it starts passing**, so the declaration cannot outlive the defect.


**FIXED 2026-08-05.** One rule, in one place, replacing four copies of the wrong one.

Scala ends a multi-line literal at the **last** quote of a maximal run, not at the first `"""`: of
the four closing quotes in `"""x""""` the first is CONTENT. Every scanner in
`v1/lang/core/.../parser/Parser.scala` stopped at the first `"""`, which left a stray `"` loose in
the stream — and the pass each scanner serves then rewrote whatever followed. That is why the
symptom was a **List**: the `[` of the next literal, `"[" + a + "]"`, was read as a list literal.
It explains the impossible diagnostic too — `Undefined: impl` at a column that does not exist —
because by then the text being parsed was not the text on disk.

**Four call sites had it**, one per preprocessing pass (`preprocessListLiterals`' own
`skipTripleFrom`, its interpolation-splice skip, and two `code.indexOf("\"\"\"", …)` scanners).
They now share `tripleLiteralEnd`, so the next pass to need one cannot pick up a fifth copy of the
bug.

Verified on all four lanes, with `jvm` — real Scala — as the oracle:

| | before | after |
|---|---|---|
| `"""x""""` | int/js/jvm `List(x")`, native `[x"]` | all four **`[x"]`** |
| `"""x"""` (control) | `[x]` everywhere | unchanged |
| `""" aria-invalid="true""""` | broken on three lanes | all four `[ aria-invalid="true"]` |
| `"""a"b"""` (quote in the middle) | — | all four `[a"b]` |

**The gate announced its own removal, as designed.** Its int/js/jvm cells were declared gaps that
FAIL if they start passing; after the fix it failed with *"the gap closed — delete this block"*. All
four lanes now run through the same `check`.

**One thing the gate did that nobody should trust, recorded because it is unexplained.** The
declared cells used `producer | grep -q` where `check` uses a command substitution. With the pipe,
the `js` cell reported "still a gap" while the identical command captured to a variable printed the
right answer — reproducible inside the gate, not reproducible outside it, and not explained by
`pipefail`, by ordering, by the temp directory, or by the shell. It is gone now because all four
cells capture; if a cell here ever disagrees with a manual run again, start there.

## v2-three-parameter-clauses-fail-typecheck — `def f(a)(b)(c)` dies with "cannot unify Tuple with non-Tuple"

<!-- status: fixed
     kind: bug
     lane: native
     area: front
     gate: tests/conformance/curried-def-three-clauses.ssc
     fixed-in: 6d0066d14 -->

Found 2026-08-04 while fixing `v2-front-curried-def-second-clause`, by adding a three-clause row to
`tests/e2e/v2-front-coverage.sh` and watching it report `ERROR` instead of `F`:

```
def tri(a: Int)(b: Int)(c: Int): Int = a + b + c
println(tri(1)(2)(3))
  ssc: TYPEERR: cannot unify Tuple with non-Tuple
```

**Pre-existing, not a consequence of that fix** — measured on the unmodified shared toolchain, which
produces the identical message. TWO clauses work on all four lanes (`curried-def-clauses.ssc`); three
do not, and the failure is in the type checker, not in the front: `ERROR` means the file was lowered
and then rejected, where a front gap reports `GAP` and falls back.

Worth noting the failure SHAPE: `ERROR` is worse than `GAP` for a user, because there is no fallback
— the program does not run at all. A curried def is ordinary Scala, so this is a real hole, just a
narrower one than two clauses was.

Deliberately kept out of both gates rather than smuggled in as a known-red row.

**Narrowed 2026-08-04 — measured, and it is NOT a front bug.** Everything below was established by
probing; the entry was filed with one repro and now has a boundary.

| probe | result |
|---|---|
| `def tri(a)(b)(c)` + `tri(1)(2)(3)` | TYPEERR |
| the same def, NEVER CALLED | **fine** — so the def types; the CALL is what fails |
| `def q(a, b)(c, d)` + `q(1,2)(3,4)` — 4 args, 2 groups | **fine** |
| `def t3(a)(b)(c, d)` + `t3(1)(2)(3,4)` | TYPEERR |
| plain `p3(1,2,3)`, `p4(1,2,3,4)`, and a 3-arg lambda | **fine** |
| the same 3-group call on the UNMODIFIED toolchain (legacy front compiles it) | TYPEERR, identical |

So the trigger is the number of parameter CLAUSES (3+), not the number of arguments, and both fronts
produce it — which rules out the F flattening added in `v2-front-curried-def-second-clause`.

**The message now names both sides** (landed with this entry): `cannot unify tuple: () vs (Int -> t6)`.
That reading is the useful one and it is not the obvious one — the tuple is the EMPTY tuple, i.e.
Unit, so a ZERO-ARGUMENT application is meeting a ONE-ARGUMENT function. Whoever picks this up
should start from "which node lowers to an `(app X)` with no arguments", not from tuples.

Fixing it was not attempted here: with both fronts producing it and the def alone typing correctly,
the next step is inside `ssc1chkInferApp`/the def's type construction in `v2/lib/ssc1-check.ssc0`,
which is a session of its own.

**NARROWED AGAIN 2026-08-07 — it is the def's inferred TYPE, not the call syntax.** Four new probes
on the shared toolchain:

| probe | result |
|---|---|
| 2 clauses, called | **fine** |
| 3 clauses, NEVER called | **fine** — the def types |
| 3 clauses, called | TYPEERR |
| **4 clauses, called** | TYPEERR — so it is 3-or-more, not exactly 3 |
| **3 clauses, PARTIALLY applied**: `val g = f(1)` then `g(2)(3)` | **TYPEERR, identical** |

The last row is the one that moves this. Splitting the call across two statements fails the same
way, so the trigger is NOT the syntactic chain `f(1)(2)(3)` — the def's inferred type is already
wrong, and the first application past the second clause is merely where it surfaces.

**Where that type is built, and the shape of the mismatch.** `ssc1chkTopStmts`
(`v2/lib/ssc1-check.ssc0:746`) types every def as `ssc1inferLam(env, s, realParams, body)` over a
FLAT parameter list — **the checker has no notion of parameter clauses at all**. And `ssc1inferLam`
on an EMPTY list returns the BODY's type rather than a function type (`:302`, "0-param lambda: type =
body type"). That is the only place in the checker that can produce the `()` in the message, since
`()` is `TyTup(Nil)`, i.e. Unit.

So the reading to test next is: **for 3+ clauses the parse tree hands the checker an empty parameter
group**, which types as Unit and then meets a one-argument function. Two clauses never produce it,
which is why they work. Start by dumping `params` as `ssc1chkTopStmts` receives it for a 2-clause and
a 3-clause def and comparing — not by reading the unifier, which is behaving correctly on the input
it is given.

**FOUND 2026-08-08 — the checker is innocent and both earlier hypotheses are wrong.** The recorded
next step was the right one: dumping what `ssc1chkTopStmts` receives settles it in one run. Debug
built into the def-typing site of the staged tower copy (read at runtime, no rebuild), the def
returned via `TcErr` so the message surfaces:

```
def two(a: Int)(b: Int): Int      →  nparams=2  ty=(t1 -> (t1 -> t1))     ✅
def tri(a: Int)(b: Int)(c: Int)   →  nparams=2  ty=(t0 -> (t1 -> ()))     ❌
```

**The three-clause def arrives with TWO parameters and a Unit result.** There is no empty parameter
group — the hypothesis this entry recorded — and no zero-argument application to hunt for. The `()`
is not produced by the unifier at all: it is the def's own body type.

**Cause, in one comment.** `v2/lib/ssc1-front.ssc0:1958` reads *"KC8: handle (using p: T, ...) or a
SECOND regular param list"*, and that is the whole clause handling — first clause into `params`, one
more into `rp2`, no loop. For a third clause `toks5` then points at `(c: Int)`, `skipTypeAnnot`
finds no annotation, `kindIs("=", toks6)` is FALSE, and the def falls into the abstract-def branch
whose body is `mkTup(Nil)` — Unit. Everything else follows: the def types fine on its own because an
abstract def does; `tri(1)(2)` is Unit; applying `(3)` demands `Unit ~ (Int -> t6)`; and splitting the
call across statements fails identically because the TYPE was already wrong.

**Why fixing the checker or F would not have helped.** F's own def parser DOES loop over clauses —
`emitDefClause` recurses back into `emitDefU` — and `ssc info --front-report` names F for the
two-clause file. But the type check runs on the TOWER front's parse regardless of which front lowers,
which is why both fronts report the same error and why the fix belongs in `ssc1-front.ssc0`.

**FIXED.** The parse now folds every further plain clause after `rp2` and threads the result through
`callParams` and `toks5`; a `using` clause is left to the branch that already handles it. Measured:
3 clauses → 6, 4 clauses → 10, 2 clauses unchanged at 3. Four are in the gate as well as three,
because the old ceiling was "two" — a fix that special-cased a third clause would pass a three-clause
case and fail a four-clause one.

**Guard, since the walk keys on `(`:** nothing that merely starts with a paren may be eaten. A
parenthesised body (`def paren(a: Int): Int = (a + 1)`) and a tuple return annotation
(`def tup(a: Int): (Int, Int)`) both sit right after a parameter list; the walk stops at `:` and `=`,
and both are pinned in `tests/conformance/curried-def-three-clauses.ssc`.

**One probe in the narrowing above measured a different defect, and it is worth striking out.** The
row *"3 clauses, PARTIALLY applied: `val g = f(1)` then `g(2)(3)` → TYPEERR, identical"* was read as
evidence about clause count. It is not: with three clauses now working, that shape still fails with
`arity: 3 expected, 1 given`, and the TWO-clause equivalent fails the same way — on the unmodified
shared toolchain as well, so it predates everything here. Curried defs lower at their TOTAL arity
(`curried-def-clauses.ssc` says so in as many words), so partial application is unsupported at any
clause count. Filed separately as `v2-curried-def-partial-application-unsupported`.

## v2-package-frontmatter-hides-a-case-class-from-a-pattern-inside-a-class-method

<!-- status: fixed
     kind: bug
     lane: native
     area: front
     confirmed: yes
     gate: tests/conformance/run.sh
     fixed-in: b5e777cb8 -->

**FIXED 2026-08-25 in `b5e777cb8`** — the registries ACCUMULATE across the process instead of
replacing, so the class-method bodies that lower last still see the program's own constructors. The
title stays as filed for greppability; the dated section below records that `package:` is not the
cause and `case class` is not the scope.

```scalascript
---
name: t
package: demo
---
case class P(n: Int)

class Holder(h: Int):
  def read(v: Any): Int =
    v match
      case P(n) => n + h
      case _ => -1

println(Holder(10).read(P(5)))
// ssc: "unknown constructor 'P' in a pattern"
```

**DELETE THE `package: demo` LINE AND IT PRINTS 15.** That one frontmatter field is the whole
difference; every other field (`version`, `description`, `exports`, the prose) was removed in turn
and changed nothing.

**THE SECOND HALF IS WHERE THE PATTERN SITS, AND THE ASYMMETRY IS THE LEAD:**

| pattern lives in | with `package:` |
|---|---|
| a top-level `def` | works |
| an `object` member | works |
| a `class` method | **fails** |
| a `case class` method | **fails** |

A plain `class` fails as readily as a `case class`, and the pattern may be on the enclosing class
itself. Class-method bodies are emitted by `caseMethodDefsStr(cms, cx)` out of `compile4b`, while
object members are emitted during the ordinary top-level walk — the code comment beside
`ccMDefsCls` says it does this "the same way `objectItem2` does for an object", and that is the one
place the two demonstrably differ.

**THE MESSAGE COMES FROM THE REFERENCE LOWERER, NOT FROM F.** Both fronts have a check with this
wording; the quoted form (`"unknown constructor 'P' in a pattern"`) is `#__throw__` from
`v2/lib/ssc1-lower.ssc0` `ckCtorTag`, while F's `ckCtorF` throws unquoted. `ckCtorTag` accepts a name
that is in `caseFieldOrderCell` or `caseObjNamesCell`, both filled from `stmts` at the lowering reset
(`collectCaseClassOrder`, `collectCaseObjNames`).

**BOTH REGISTRIES LOSE IT, so this is not one table.** A `case object` matched in a class method
fails exactly like a `case class`:

```scalascript
sealed trait K
case object Alpha extends K
class Holder(h: Int):
  def read(v: K): Int = v match { case Alpha => 1 + h }
// ssc: "unknown constructor 'Alpha' in a pattern"
```

So the statement list those registries are built from is missing the module's own type declarations
by the time a class-method body is lowered — under `package:` and not otherwise.

`package:` is not a front feature: `ssc1-run-fsub.ssc0` synthesises a namespace CHAIN of flat objects
plus `__pkgref_…` aliases, appending them to the SOURCE for F (`sscConcatSources`) and to the parsed
DEFS for the reference lane (`sscApp(defs, nsDefs)`). **Hand-writing that generated text does not
reproduce the failure** — with the frontmatter and the fence, with and without them — so the trigger
is not the generated text itself but what declaring a package does to the statement list handed to
the lowering reset. That is the next thing to read, and it is why this is filed rather than fixed:
everything above is measured, and a fix guessed from here would be a fix to the wrong site.

**THIS IS SEVEN OF THE TWELVE CORPUS REDS** (`corpus-contract-is-red-on-twelve-rows-and-the-freeze-is-global`):
every `scljet-*` module declares `package: scljet`, and `std/scljet/jvm-vfs.ssc` pattern-matches its
own `JvmVfsRead` inside `JvmSqliteFile.readAt`. The whole module chain that imports it fails with it.

### 2026-08-25 — MEASURED. `package:` is not the cause, and the title was wrong twice over

**The trigger is not the frontmatter and the scope is not `case class`.** Under `SSC_FRONT=legacy`
the same program fails with `name:` alone and with **no front matter at all**, and a `case object`
fails on BOTH fronts either way:

| program | default lane | legacy lane |
|---|---|---|
| class-method pattern, no front matter | 15 | **refused** |
| class-method pattern, `name:` only | 15 | **refused** |
| class-method pattern, `package: demo` | **refused** | **refused** |
| `case object` in a class method, no front matter | **refused** | **refused** |

So on the legacy lane EVERY constructor pattern inside a class method was refused. `package:` earned
its place in the title only because it is a common reason for F to DECLINE — and the F4a fallback
lands in the reference lowerer, where the defect lives.

**THE MECHANISM, traced rather than inferred.** `--structural` — which is what `bin/ssc` always
passes — runs **three** lowerings in one process: the user's program, `sscParseFrontmatters`' yaml
module, and `sscParseContents`' content-core module. All three share the process-global
`caseFieldOrderCell` / `caseObjNamesCell`, and a program's defs lower **lazily**. Instrumenting the
registry set and the refusal site printed the order directly:

```
LOWERPROG ctors=[P Holder ]                       the user's program registers
LOWERPROG ctors=[YamlNull YamlBool YamlInteger…]  the front-matter parser REPLACES the registry
CKDBG     ctors=[YamlNull YamlBool YamlInteger…]  the class-method body is lowered NOW
ssc: "unknown constructor 'P' in a pattern"
```

The class-method bodies are the ones that reach the lowerer last, which is the whole reason the
asymmetry in the table above exists: a top-level `def` and an `object` member are lowered while the
registry is still the program's own.

**WHY THE OBVIOUS FIXES DO NOT WORK, each tried and refuted:**

| attempt | result |
|---|---|
| force the user's IR with `#coreir.encode` before the other parses | encode succeeded (`FORCED len=9083`) and the refusal still came later |
| reorder so the front-matter and contents are computed first | `let` is LAZY — the bindings were never evaluated and the order did not change |
| force those bindings through a length check | the user's `lowerProg` then never ran its resets at all |

An unused `let` binding is never evaluated here, which defeats every ordering fix from outside.

**FIXED at the coercion site**, with the real cause named: the two registries now ACCUMULATE across
the process (`append(collectCaseClassOrder(stmts), #cell.get(…))`) instead of replacing. What it
costs, stated rather than hidden: `ckCtorTag` asks "is this a declared constructor" and the union
makes it "…in ANY program this process lowered", so a pattern naming a yaml-module or content-core
constructor is now accepted where it would have been refused. That is strictly smaller than refusing
every valid one, and it cannot accept a name no program in the process declares.

**THE REAL REPAIR is re-entrancy** — a per-program registry, or a saved/restored one — and it is not
done here because every ordering-based attempt above was defeated by the evaluation strategy.

**NOTHING THAT ALREADY WORKED CHANGED:** a program with case classes and an enum lowers to
BYTE-IDENTICAL output before and after (12413 bytes under `--structural`).

**GATE** `tests/e2e/class-method-ctor-pattern-gate.sh`, new, wired into `scripts/smoke-ci.ssc`. Nine
rows, each asserted on BOTH fronts: 5 FAIL / 4 controls green before the fix, 9/9 after. The last two
rows are the control the union makes necessary — a genuinely undeclared constructor must still be
refused, and is.


**A REDUCTION THAT DELETED A MIDDLE RANGE MADE THE FILE ILLEGAL AND NEARLY SENT ME ELSEWHERE.**
Cutting lines 47–145 removed the `extern def`s the surviving body calls; the cut file still said
`unknown constructor`, but it could have said it for a different reason. The reduction that counted
kept every reference resolvable and shrank from a legal file each time.

**Done when** the ten-line case above prints 15 and a conformance case covers pattern-in-a-class-
method under `package:`, on the lanes that support it.

## js-transport-enum-alias-resolves-to-unit — `Method not found: Spawn on ()`

<!-- status: open
     kind: bug
     lane: js
     area: codegen
     confirmed: yes
     gate: tests/conformance/run.sh -->

```scalascript
[mcpConnect, Transport](std/mcp/client.ssc)
val client = mcpConnect(Transport.Spawn("node", List("examples/mcp-server-tools.js")))
// js: Error: Method not found: Spawn on ()
```

`Transport` is imported and its alias IS emitted (`const Transport = std.mcp.Transport;`), but the
right-hand side answers `()` — so selecting the enum case off it dispatches on unit. `Transport` is
NOT one of the runtime's declarations, so the alias-suppression guard of
`js-emit-declares-an-identifier-twice` is not involved; checked before filing.

**Found 2026-08-25 UNDERNEATH a fix.** `tests/conformance/mcp-client-invoke.ssc` never parsed on
`js` — the module declared `mcpConnect` twice — so this failure could not be seen until the parse
error was gone. That is the ordinary shape of a corpus row with two defects stacked, and the reason
the row stays red after a fix that was nevertheless correct.

**Done when** `Transport.Spawn(…)` builds the enum case on `js` and `mcp-client-invoke` runs.

## js-emit-declares-an-identifier-twice — an import alias collides with a RUNTIME declaration

<!-- status: open
     kind: bug
     lane: js
     area: codegen
     confirmed: yes
     gate: tests/conformance/run.sh -->

```text
direct-syntax-demo:  SyntaxError: Identifier 'serve' has already been declared
mcp-client-invoke:   SyntaxError: Identifier 'mcpConnect' has already been declared
```

TWO of the twelve corpus rows, ONE defect. The emitted module contains both the imported module's
own definition and an alias bound to the same name:

```javascript
function serve(port, _tlsCfg) { … }     // std/http.ssc, emitted flat
const serve = std.http.serve;           // the import alias — illegal in the same scope
```

**THE GUARD FOR THIS ALREADY EXISTS AND COVERS ONE SPELLING.** `JsGen` suppresses the alias when the
name is in `declaredBindings` or `topLevelConsts` — added when the very same `SyntaxError` was
reported for `org`. `topLevelConsts` holds names bound as `const`; a collision against a top-level
`function` is invisible to it.

**TWO CORRECTIONS TO MY OWN EARLIER ENTRY, both measurement errors rather than defects.** I first
filed `mcp-client-invoke` as a case whose FILE NO LONGER EXISTS: the file is
`tests/conformance/mcp-client-invoke.ssc` and I had looked only in `examples/`. The
`SyntaxError: Unexpected identifier 'BUILD'` I cited as evidence was the words "STALE BUILD" from a
warning on stderr, which my `2>&1 | node` fed to the parser as source. That entry is deleted; this
one replaces it. **A pipeline that merges stderr into an interpreter turns any warning into a syntax
error at a line number that means nothing.**

**FIXED 2026-08-25 — the SyntaxError is gone from both, and one of the two rows is green.**
`direct-syntax-demo` runs and has left the contract's regression list. The guard now also asks
`JsGen.runtimeTopLevelNames`, which is where `serve` and `mcpConnect` live and which the top-level
census deliberately SKIPS so that runtime names are never renamed — they were invisible to every set
the guard already consulted, by construction.

**ASKING `usedTopLevelJsNames` AS WELL WAS TRIED AND REVERTED IN THE SAME RUN.** It holds names from
every module WALKED, and a module can be walked without its definitions reaching this output, so the
alias is then the only binding there is. Both cases went from
`SyntaxError: Identifier 'serve' has already been declared` to
`ReferenceError: jsonCoreRender is not defined` — a parse error traded for a missing binding, which
is not a smaller failure. The runtime set has no such gap: what it names is always emitted.

`mcp-client-invoke` now PARSES and fails further in, at
`Error: Method not found: Spawn on ()` — a different defect, filed as
`js-transport-enum-alias-resolves-to-unit`. That row stays red for that reason, not this one.

**Done when** `mcp-client-invoke` also runs on `js`.

## corpus-contract-is-red-on-twelve-rows-and-the-freeze-is-global — adding one case ratifies the lot

<!-- status: open
     kind: apparatus
     lane: apparatus
     area: conformance
     gate: tests/e2e/freeze-consistency-gate.sh -->

`scala-cli tests/conformance/contract.sc` reports twelve REGRESSION rows against the frozen
baseline, measured 2026-08-23:

```text
agent-mcp-toolsource  js  FAIL      scljet-bytes             v2  FAIL
direct-syntax-demo    js  FAIL      scljet-crud              v2  FAIL
extensions            v2  FAIL      scljet-full              v2  FAIL
mcp-client-discover   js  FAIL      scljet-jdbc              v2  FAIL
mcp-client-invoke     js  FAIL      scljet-readonly-codecs   v2  FAIL
                                    scljet-text-projection   v2  FAIL
                                    scljet-write-table       v2  FAIL
```

**Measured twice, on two toolchains, so it is not one checkout's accident**: a full unsharded run on
a build of `main` plus one front change, and a `--only` run in the shared checkout on a toolchain
built from `79a888fb3`, which predates that change. Identical twelve.

**THREE OF THEM ARE A CHANGE IN KIND, NOT IN STATUS.** `agent-mcp-toolsource`, `mcp-client-discover`
and `mcp-client-invoke` are frozen as `* SKIP` and here they RAN and failed — the reference host
skipped them, this one did not. That smells of environment (a dependency present here, absent there)
rather than of code, and it is the first thing to check. The other nine were PASS at the freeze and
are FAIL now, which environment explains less comfortably.

> **AND THE THIRD ONE WAS NOT ENVIRONMENTAL.** `mcp-client-invoke` is frozen `* SKIP` like the other
> two and is a real js codegen defect — the emitted module declares an identifier twice, so it never
> parsed. `* SKIP` → `FAIL` narrowed twelve rows to three worth suspecting environment for, and then
> two of those three were environment and one was code. It is a filter, not a verdict, and this
> entry claimed more for it than it earns.

**THE PART THAT IS APPARATUS, AND THE REASON THIS IS FILED AT ALL:** the freeze is GLOBAL and its
only writer is a full-corpus run. Adding ONE conformance case makes `freeze-consistency` fail until
the roster gains a row — and `--update-baseline`, the sanctioned way to add it, rewrites
`corpus-baseline.tsv` from the current run, silently recording all twelve as expected. **Anyone
adding a corpus case on a host in this state ratifies twelve unrelated reds, and the commit looks
like a routine roster refresh.** It nearly happened here; `v2-curried-def-partial-application`
instead appended the single roster name and left `baseline-sha256` untouched, after reproducing both
existing digests byte-for-byte to prove the serialization matched the tool's own.

**ALL TWELVE ARE NOW ATTRIBUTED, 2026-08-23**, and they are FOUR causes rather than one state:

| rows | cause |
|---|---|
| 7 × `scljet-*` (v2) | `v2-package-frontmatter-hides-a-case-class-from-a-pattern-inside-a-class-method` — every scljet module declares `package: scljet`, and `jvm-vfs.ssc` matches its own `JvmVfsRead` inside a class method |
| 1 × `extensions` (v2) | `v2-extension-receiver-is-typed-from-the-parameter-list` — **FIXED 2026-08-25**, green |
| 2 × `agent-mcp-toolsource`, `mcp-client-discover` (js) | an MCP worker deadline expires — these need a server, which is why the reference host SKIPPED them and this one ran them |
| 2 × `direct-syntax-demo`, `mcp-client-invoke` (js) | `js-emit-declares-an-identifier-twice` — one defect, not two: an import alias collides with a RUNTIME declaration. **FIXED 2026-08-25**; `direct-syntax-demo` is green, `mcp-client-invoke` now parses and fails at `js-transport-enum-alias-resolves-to-unit` underneath |

**Only two of the twelve are environmental.** Both have a `* SKIP` baseline row — a case the
reference host never ran — but so does `mcp-client-invoke`, which is code. The `* SKIP` → `FAIL`
transition narrows where to look and decides nothing: three candidates, two of them environment.

**Ten of the twelve are real defects in this tree**, each filed with its own repro. Nothing about
them justifies re-freezing: a freeze would mark ten known defects as expected.

**TWO ARE FIXED, 2026-08-25** — `extensions` and `direct-syntax-demo` are green and off the
regression list. `mcp-client-invoke`'s parse error is fixed too and the row stays red on a second
defect underneath it, which is what a corpus row with two stacked causes does. Eight rows remain:
seven `scljet` on one unfixed cause, and this one.

**Done when** the ten coded causes are fixed or deliberately frozen with that reason recorded, and
the two environmental rows are either given their dependency or re-declared as SKIP by the case
itself rather than by the host. The apparatus half is done when adding a case no longer requires
re-freezing the corpus: a roster-only append is the operation that was actually needed, and
`contract.sc` has no flag for it.

## v1-interp-curried-def-with-using-clause-drops-the-second-clause — `missing argument for parameter 'b'`

<!-- status: open
     kind: bug
     lane: int
     area: runtime
     gate: tests/conformance/run.sh -->

```scalascript
def tag(a: Int)(b: Int)(using s: Show[Int]): Int = s.code(a + b)
println(tag(1)(2))
// int: [line 45, col 9] missing argument for parameter 'b'
//      at scalascript.interpreter.CallRuntime$.applyDefaults(CallRuntime.scala:940)
```

Runs on `v2`. The `int` lane is the v1 interpreter — a separate implementation with its own call
runtime — and it applies `tag(1)` and then finds `b` unfilled, so it never reaches the second clause.

**Measured 2026-08-23** while fixing `v2-curried-def-with-a-using-clause-cannot-be-called`: the v2
front now registers the using signature across every written clause and injects the given, which is
what lets this shape reach a backend at all. Not a regression — a gap the fix made visible. The
`int` lane is excluded from `curried-def-using-clause` by measurement, with this slug named there.

Note `int` DOES run every other curried member shape — object, class, extension and block-local all
pass on it — so this is specifically the trailing `using` clause.

**Done when** `tag(1)(2)` runs on `int` and `curried-def-using-clause` can add it.

## v2-curried-def-with-a-using-clause-cannot-be-called — the given is never injected

<!-- status: fixed
     kind: bug
     lane: native
     area: front
     fixed-in: 6fbafea93
     confirmed: yes
     gate: tests/conformance/run.sh
     repro: tests/conformance/curried-def-using-clause.ssc -->


**FIXED 2026-08-23.** `collectUS2` parsed exactly ONE parameter clause and then asked whether the
next was `using`. For `def tag(a: Int)(b: Int)(using s: Show[Int])` the next is `(b)`, so the def
registered nothing and no given was ever injected — the def lowered at three and the call passed the
two written arguments. It now folds every WRITTEN clause and stops at the `using` one, through
`paramsUntilU`; `collectUSCtx` gets the same treatment, so a context bound on a curried def works
for the same reason.

**THE FOLD HAS TO STOP AT THE `using` CLAUSE, not eat it** — the registry needs the count of
parameters the CALLER writes AND the clause itself, to read its type classes from. That is why this
is a second helper rather than a reuse of `paramsAllC`, which folds a `using` clause like any other
because a DECLARATION binds it like any other.

**The comment beside the code already said this was the third site with the same one-clause
assumption**, and named the two that had been fixed before it. It was written about a clause on a
CONTINUATION LINE; the same sentence covers a clause that is merely third, and nobody read it that
way — including me, until a probe failed.

Evidence: `curried-def-using-clause` on v2 — the `int` lane is the v1 interpreter and refuses the
shape with `missing argument for parameter 'b'`, filed as
`v1-interp-curried-def-with-using-clause-drops-the-second-clause`. The shapes that must NOT move —
one written clause plus `using`, a context bound, and a multi-param clause plus `using` — are rows
of `curried-def-every-spelling` on all four backends, and were measured unmoved before and after.



```scalascript
def tag(a: Int)(b: Int)(using s: Show[Int]): String = s.show(a + b)
println(tag(1)(2))       // ssc: arity: 3 expected, 2 given
```

A FULL application fails. The def lowers at arity 3 — two written params plus the injected given —
and the call supplies the two the caller writes, because the injection never happens.

**Two mechanisms that each work alone do not compose.** Given injection lives in `parseCallU`, which
`parseCallPlain` reaches via `hasUsingSig`; clause flattening happens EARLIER, in `scanArgsCur` /
`argClausesAll`, and a curried callee with a trailing `using` clause takes a path where the two never
meet. `curried1` deliberately excludes a second clause that IS a using clause (`def f(a)(using ev)`
is not curried, and that case works), so only the three-clause shape — written, written, using — is
uncovered.

**Measured 2026-08-23 on unmodified main**, while adding partial application for top-level curried
defs (`v2-curried-def-partial-application-unsupported`, fixed): the same probe fails identically in
the shared checkout, so it predates that work. The partial-application path refuses to fire on a
`hasUsingSig` callee for exactly this reason — its wrapper would call the global without the given
and under-apply it, turning a named arity error into a silently wrong program.

**Why nobody hit it:** no conformance case pairs a curried def with a using clause. `curried-*`
cases have no givens and the `using`/`summon` cases have one parameter clause, so each suite is
blind precisely where the other one looks.

**Done when** `tag(1)(2)` prints its value with a case covering the written-written-using shape, and
`curried-def-clauses` plus the existing using/summon cases still pass. Partial application of such a
def (`tag(1)`) should then be reconsidered together with it — the guard that refuses it today is
keyed on `hasUsingSig` and would lift with the same fix.

## js-backend-cannot-call-a-curried-member-method — a single clause runs, two do not

<!-- status: open
     kind: bug
     lane: js
     area: codegen
     gate: tests/conformance/run.sh -->

```scalascript
class Box(n: Int):
  def plus(a: Int)(b: Int): Int = n + a + b
println(Box(100).plus(1)(2))       // js: Error: Method not found: plus on Box(100)

extension (s: String)
  def rep(n: Int)(k: Int): Int = n + k + s.length
println("xy".rep(2)(3))            // js: Error: not callable: NaN
```

Both run on `int`, `jvm` and `v2`. A SINGLE-clause extension runs on `js` correctly
(`"x".cat("-")` → `x-`), so this is about the second clause and not about extensions.

**Measured 2026-08-23** while fixing `v2-curried-method-call-does-not-flatten-its-clauses`: the
front now folds every clause at the declaration and flattens the call, which is what makes these two
rows reach the js backend at all — before, F declined the file and the reference front compiled it.
So this is not a regression; it is a gap the front fix made VISIBLE, and the js lane is excluded from
`curried-def-member-methods` by measurement with this slug named in the case.

The two failures are different shapes — `Method not found` is name dispatch, `not callable: NaN` is
an applied value that is not a function — so expect two causes rather than one.

**Done when** both rows run on `js` and `curried-def-member-methods` can add `js` to its backends.

## v2-extension-receiver-is-typed-from-the-parameter-list — the receiver takes the first parameter's type

<!-- status: fixed
     kind: bug
     lane: native
     area: front
     fixed-in: e346240e2
     confirmed: yes
     gate: tests/conformance/run.sh
     repro: examples/extensions.ssc -->

**FIXED 2026-08-25, IN TWO STEPS, AND THE FIRST ONE LOOKED SUFFICIENT.** The registry that types a
def's parameters was fed `callParams` — the written clauses — while `mkDef` binds `allParams`, which
puts `ctxParams` and the extension RECEIVER in front of them. `ssc1inferLamTys` zips the two
positionally, so the receiver was typed from the first written parameter. Registering over
`allParams` fixed the position and `def widen(n: Int): String = s` started working.

**IT WAS STILL WRONG, AND MY PROBE WAS WEAKER THAN THE CORPUS ROW IT WAS WRITTEN FOR.**
`paramTypesCell` is cleared at the top of every def parse, so the receiver had a position and no
TYPE. An unknown unifies silently with `= s`; `def repeat(n: Int): String = s * n` — the actual
`examples/extensions.ssc` row — forces `*` to make the receiver numeric and the declared `String`
return then contradicts it. The receiver's types now live in `extensionParamTypesCell` beside its
names, cleared in the same two places, and merged BY NAME at registration so no position moves.

Evidence: `examples/extensions.ssc` runs on v2 and left the contract's regression list; a probe
covering receiver-with-differing-param-type, same-type, and a curried member answers `x`, `y-`, `7`;
smoke-ci 123/123.

```scalascript
extension (s: String)
  def widen(n: Int): String = s
println("x".widen(2))       // ssc: TYPEERR: in def widen: cannot unify Int: Int vs String
```

ONE clause. The body reads the RECEIVER, the parameter has a different type, and the checker types
the receiver from the parameter. `def cat(sep: String): String = s ++ sep` passes only because the
receiver and the parameter are both `String`.

**Found 2026-08-23 by reduction, and the first reading was wrong.** It surfaced as a curried
extension failing to typecheck, and looked like part of
`v2-curried-method-call-does-not-flatten-its-clauses`. Reducing it — one clause instead of two, then
a body that is just `s`, then a body that is just the parameter — showed the parameter body PASSES
and the receiver body FAILS, at one clause. Currying is not involved. Reproduced on unmodified main.

**Done when** an extension method whose parameter type differs from its receiver type can read the
receiver, with a case covering one and two clauses.

## v2-curried-method-call-does-not-flatten-its-clauses — the declaration is accepted, the call is not

<!-- status: fixed
     kind: bug
     lane: native
     area: front
     fixed-in: 6fbafea93
     confirmed: yes
     gate: tests/conformance/run.sh
     repro: tests/conformance/curried-def-member-methods.ssc, curried-def-every-spelling.ssc -->


**FIXED 2026-08-23, and the entry was wrong about the scope in both directions.** It named a class
method and reasoned about the CALL. The call was the smaller half, and the defect was not confined
to methods: **F dropped every parameter clause after the first at FIVE declaration sites**, because
only the top-level path loops clauses (inside `emitDefU`) and each member path called `parseParams`
once and then skipped to the `=`. The second clause's parameters were never bound, so the body's
reference to them became a free global.

| where | site |
|---|---|
| `object` member | `objDefE1` |
| `class` method | `ccMParams` |
| `extension` member | `extMember`, and `extDefE1` for the `given` form |
| `def` inside a block | `parseBlockDef1` |
| trailing `using` | `collectUS2` — see the sibling entry |

All five now fold clauses through one helper, `paramsAllC`.

**THE OBJECT ROW IS WHY THIS ENTRY EXISTED AT ALL AND IT LOOKED GREEN.** `Calc.add(1)(2)` printed
`3` on unmodified main. F declined the whole file — `GAP: unbound global: (global b)` — and the
REFERENCE front compiled it. The program was right and the measurement was meaningless, which is
the disguise `curried-def-clauses` documents in its own header for the top-level case. **Reading
`--front-report` rather than the program's output is what turned "works" into "GAP".**

**THE CALL HALF IS KEYED ON THE NAME, NOT ON THE EMITTED TEXT**, which is what this entry proposed
as the alternative and is the only version that scales: `isCurriedApp` reads `(app (global f) …)`,
a shape ONLY a top-level call emits. A class method is `(prim __method__ …)`, an extension is
`(prim __methodOrExt__ …)`, an object member is mangled to `Obj_m`, a block-local is `(local i)`.
Four patches to a text matcher would have left the fifth. `parseArgsCur` / `parseArgLCur` ask the
curried TABLE while the name is still in hand, and cover every spelling with one rule.

Evidence: `curried-def-every-spelling` (object at two and three clauses, block-local, and the
using-registry neighbours) on all four backends; `curried-def-member-methods` (class and extension)
on int, jvm and v2 — `js` is excluded by measurement and filed as
`js-backend-cannot-call-a-curried-member-method`. `curried-def-clauses`,
`curried-def-three-clauses`, `curried-def-partial-application`, `curried-extern-import` and
`fewer-braces-colon` all pass unchanged, and every new row runs on **F**.



```scalascript
class Box(n: Int):
  def add(a: Int)(b: Int): Int = n + a + b

val b = Box(100)
println(b.add(1)(2))     // ssc: Box.add: expected 2 argument(s), got 1
```

The DECLARATION is accepted — the checker knows `add` takes two arguments — and the CALL cannot be
written. `b.add(1)(2)` is parsed as `b.add(1)` followed by `(2)`, and nothing flattens it.

**The clause flattening is keyed on the emitted callee STRING.** `isCurriedApp` fires only when the
call reads `(app (global NAME)` and `NAME` is in the curried table; a method call emits a receiver
form instead (`calleeOf` returns `(global Box_add)` for an object method, and a class method's
receiver is prepended by `selfArgFor`), so the test reads false and the second clause nests against a
callee that was lowered at the total arity.

**Measured 2026-08-23 on unmodified main**, while adding partial application for top-level curried
defs (`v2-curried-def-partial-application-unsupported`, fixed): the same probe fails identically in
the shared checkout, so it predates that work and is not a consequence of it. The partial-application
path refuses to fire here on purpose — its guard compares `calleeOf` against the exact `(global nm)`
string its wrapper would emit — so this stays the arity error it already was rather than becoming a
silently wrong program.

**Why nobody hit it:** no conformance case declares a curried method inside a `class` or `object`.
Every curried case in the corpus is a top-level `def`, which is the one shape both the flattening and
its gate were written in.

**Done when** `b.add(1)(2)` prints 103 for a class method AND an object method, with a case covering
both — and the top-level rows of `curried-def-clauses` still pass. Note that a fix keyed on the
callee string would have to change what `isCurriedApp` reads, which is the same decision site
`v2-front-curried-def-second-clause` half 2 established; the alternative is to carry curried-ness
through the method-call path rather than re-deriving it from the emitted text.

## v2-curried-def-partial-application-unsupported — a curried def cannot be applied one clause at a time

<!-- status: fixed
     kind: feature
     lane: native
     area: front
     fixed-in: 2a7e14d6e
     confirmed: yes
     gate: tests/conformance/run.sh
     repro: tests/conformance/curried-def-partial-application.ssc -->

**FIXED 2026-08-23 by the second option — the front wrapper — and the entry had already ruled out
the first.** `def two(a: Int)(b: Int)` called `two(1)` now answers a value that takes `(2)`, and
`curried-def-clauses.ssc` is byte-identical: a FULL application never reaches the new path, because
the guard is `dlen(slices) < arity` and a full call is equal, not less.

It reuses `synthWrap` unchanged, which is what keeps it free of local-index arithmetic — each
supplied argument is bound OUTSIDE by an `(app (lam 1 …) v)` level and parsed in that level's own
env, so nothing needs shifting when the wrapper adds binders underneath.

**THE WRAPPER HAD TO BE CURRIED BY CLAUSE, AND A FLAT ONE LOOKED RIGHT UNTIL IT WAS RUN.** The first
version emitted a single `(lam k …)` for the missing arguments. `def two(a)(b)` cannot tell the
difference — one clause, one param — so both probes and the two-clause conformance case passed. The
matrix row `three(1)` then `h(2)(3)` did not: the value is applied ONE argument at a time, and a flat
`(lam 2 …)` dies at the first with the very `arity: 2 expected, 1 given` this entry is about. **The
two-clause case is the one shape that cannot see the difference, and it is the shape the entry, the
probes and the existing gate were all written in.**

That needed clause boundaries, which this front deliberately did not record — the note at
`argClausesAll` says inventing an n-clause registry would be "design ahead of a measured need". The
need is now measured, so each clause is registered under `<name> *k<i>`, with a SENTINEL element so
a zero-param clause (`def v()(c)`) is distinguishable from an absent one. `*c1` is untouched;
`padC1` reads it and expects the raw clause-one params.

**Two refusals are load-bearing, and the first is the anti-case this entry called the whole
difficulty.** An argument count that does not land on a clause boundary — `mix(1)` on
`def mix(a, b)(c)` — stays an arity error, as it is in Scala; without that walk it would have
silently become a closure. And a trailing `f(a) { … }` or `f(a): p => …` is a second clause the scan
does not collect, so the call is not under-applied at all and `blockArgApp` keeps flattening it.

Evidence: the new case passes on all four backends and `ssc info --front-report` says **F**, so it
measures the front that was edited rather than the fallback — the trap `curried-def-clauses` itself
documents. `curried-def-clauses`, `curried-def-three-clauses`, `curried-extern-import` and
`fewer-braces-colon` pass unchanged. The pre-fix tree gives `arity: 2 expected, 1 given` on the
case's first row, measured before any edit.

The original entry follows, unchanged.


```scalascript
def two(a: Int)(b: Int): Int = a + b
def main(): Unit =
  val g = two(1)
  println(g(2))
```

`ssc: arity: 2 expected, 1 given`. Three clauses give `arity: 3 expected, 1 given`. Measured on the
unmodified shared toolchain, so it is not a consequence of the clause-loop fix above.

A curried def lowers at its TOTAL arity and its call site flattens — `curried-def-clauses.ssc` pins
exactly that as intended behaviour — so `f(1)` is an under-applied call rather than a partial
application, and there is no closure to bind. Making it work means either lowering curried defs to
nested lambdas or synthesising a partial-application wrapper when a call supplies fewer arguments
than the arity, and the first would change what that conformance case pins.

Found while fixing `v2-three-parameter-clauses-fail-typecheck`, where this shape had been recorded as
a probe supporting a wrong reading of THAT defect: it fails identically at two clauses, so it says
nothing about clause count. No gate: it would have to be a `known-red`, and the behaviour it would
pin is a design question rather than a regression.



**ONE OF THE TWO OPTIONS IS UNSAFE, established 2026-08-09 by reading the runtime rather than by
trying it.** This entry offers *"synthesising a partial-application wrapper when a call supplies
fewer arguments than the arity"* as the alternative that would not disturb `curried-def-clauses.ssc`.
**It cannot be done in the runtime**, and the reason is structural:

    final class ClosV(var env: Env, val arity: Int, val code: Code)     Runtime.scala:114

A closure carries a TOTAL arity and nothing else. Both application sites — the trampoline's
`case Call(c, args)` and `completeStep` — see only `c.arity` against `args.length`. Since a curried
def lowers at its total arity and its call site flattens (this entry's own first paragraph), the
runtime cannot tell

    def two(a: Int)(b: Int)     called as `two(1)`   — legal in Scala, wants a closure
    def add(a: Int, b: Int)     called as `add(1)`   — NOT legal, wants an arity error

apart: both arrive as an arity-2 closure with one argument. A wrapper synthesised there would turn
every genuine arity error in the language into a silently returned closure — the fail-open shape,
traded for a feature.

**So the wrapper has to be synthesised where curried-ness is still known — in the FRONT.** That does
not make it the same as the other option (lowering to nested lambdas), and it does not disturb what
`curried-def-clauses.ssc` pins, so the decision still has two live answers. It has one fewer place to
put one of them.

**Not implemented.** I took this expecting an additive runtime change, found it was not additive, and
stopped rather than ship the unsafe version. Recorded here so the next person does not spend the same
hour discovering it.

**Gate named 2026-08-14: `tests/conformance/run.sh`**, with an anti-case that is the whole
difficulty of this entry.

**Done when** a case applying `def two(a: Int)(b: Int)` one clause at a time prints `3` — **and
`curried-def-clauses.ssc` still passes unchanged**. That second half is not caution: the entry
records that a curried def lowers at its TOTAL arity and its call site flattens, and that conformance
case pins exactly that as intended. Lowering to nested lambdas would turn the new case green by
changing what the old one asserts, which is a regression wearing a fix's clothes. The other route —
synthesising a partial-application wrapper when a call supplies fewer arguments than the arity —
leaves that pin intact, and choosing between them is the work.

## tui-cargo-deps-are-a-hand-maintained-disjunction — a new emitted feature can reference a crate nobody declared
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: build
     fixed-in: unrecorded
     gate: frontend/tui/src/test/scala/scalascript/frontend/tui/TuiEmitterTest.scala -->

**FIXED 2026-08-04** by deriving the manifest from the emitted source
(`specs/tui-cargo-deps-derived.md`): `ureq` iff the generated Rust contains `ureq::`, `serde_json`
iff it contains `serde_json::`. No feature-shaped condition survives, so the class is deleted rather
than gated. The gate pins all four directions; the negatives are the load-bearing half, since an
over-declaring manifest still compiles.


**Found 2026-08-04** implementing `tui-fetch-headers` (rozum's report). `TuiEmitter.cargoToml`
decides the `serde_json` dependency from a condition hand-written against the FEATURES that happen to
use it — until today `hasRemoteTable`, now `hasRemoteTable || any fetch has headers`. The emitted
`main.rs` and the emitted `Cargo.toml` are therefore two independent statements about the same fact,
kept in agreement by whoever remembers.

**The failure mode is a crate that does not compile**, and it is invisible to the fast tests: a
string-matching emitter test asserts the generated Rust *contains* `serde_json::from_str` and passes
happily while `Cargo.toml` omits the dependency. Only a cargo build catches it, and only for shapes
that have one.

**It will recur on the very next feature.** `tui-fetch-post` (the sibling report) has to encode a
request body — the obvious implementation reaches for `serde_json` and must remember to widen the
same disjunction a third time.

**Fix direction: derive, do not check.** The dependency set is a function of the emitted source —
after generating `main.rs`, declare `serde_json` iff the text references `serde_json::`. That
deletes the class rather than gating it, and it is smaller than the gate would be. A gate asserting
"references ⟹ declared" is the fallback if derivation turns out to need more context.

## tui-interactive-widgets-have-no-compile-coverage — the emitted focus ring is never built by a test
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: build
     fixed-in: unrecorded
     gate: frontend/tui/src/test/scala/scalascript/frontend/tui/TuiCargoSmokeTest.scala -->

**FIXED 2026-08-04** (`specs/tui-widget-compile-coverage.md`): a cargo smoke builds a view holding
`Button`, `TextInput` and `Toggle` and runs the crate's generated event tests. Proven live by
injecting a type error into `toggle_text` — the new test, and only it, fails with `E0308`.


**Found 2026-08-04** while adding a cargo gate for `tui-fetch-headers` and checking what the
existing ones cover. `TuiCargoSmokeTest` compiles six shapes: the base crate, `DataTable + TabBar`,
a fetch-bound signal, a headers fetch, `DataTable.Remote`, and a refresh tick.

**Slice 3 — `TextInput`, `Button`, `Toggle`, the focus ring, Tab traversal, typed-char editing — is
compiled by nothing.** Its emissions (`text_input_display`, `toggle_text`, the focusable table and
the event-handler arms) are asserted only by string matching in `TuiEmitterTest`, and a string test
cannot see Rust that does not compile.

**This is not hypothetical.** The headers work hit exactly that class one layer over: `load_fetch`
borrows the signal store mutably while `fetch_headers` borrows it immutably, so the natural inline
call is a borrow-checker error. It was caught because the fetch path HAS a cargo test. The widget
emissions touch the same signal store from the same kind of helper and have no such backstop — and
`tui-fetch-post`, which must write a signal from an event handler, walks straight into it.

**Fix direction:** one cargo smoke over a view containing a `TextInput`, a `Button` and a `Toggle`,
asserting the crate builds and that activating the button changes what the frame renders. The
existing `snapshotViaCargo` harness already does the hard part.

## keyword-import-of-a-missing-module-is-a-silent-no-op — the link form of the same import says "not found"

<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: f467a9ba8
     gate: tests/e2e/keyword-import-missing-module.sh -->

**GATED 2026-08-04 by `keyword-import-silent`, and deliberately NOT fixed.**
`tests/e2e/keyword-import-missing-module.sh` pins the divergence — keyword form SILENT, link form
reports — without asserting that either is right.

Making the keyword form fail is a **semantic decision with an owner, not a bug fix**: `import a.b.c`
that maps to no file on disk is legitimate in programs that work today, so turning it into an error
is a compatibility change. What was missing was not the decision but the ability to verify it; the
gate supplies that, and it names the failure it expects if someone takes it ("that may well be the
intended fix — update this gate and the entry together"). Until then the divergence cannot drift by
accident, which it could while `gate:` said `none`.

The gate encodes this entry's own lesson too: the module is `nosuchmodule_9d4f`, because a first
probe used `Response` — a BUILTIN — and the import appeared to work when the program worked without
any import at all.

A Scala-style import naming a module that does not exist produces NO diagnostic. The program runs
to completion. Measured 2026-08-02 on both lanes:

```
import std.nosuchmodule.anything

def main() =
  println("ran")
```

    $ bin/ssc run bm.ssc                 -> ran
    $ bin/ssc-tools run --v1 bm.ssc      -> ran

The Markdown link form of the same import reports it:

```
[anything](std/nosuchmodule.ssc)
```

    $ bin/ssc run bl.ssc
    ssc: native frontend import not found: std/nosuchmodule.ssc from bl.ssc

So the two import surfaces disagree about whether a missing module is an error, and the one that
stays quiet is the one that looks most like Scala. The cost is not the missing message but WHERE
you find out: a typo'd module path surfaces later as `unbound global: X` at the use site, pointing
at the use rather than at the import — or does not surface at all when the name also exists as a
builtin.

**That last case is not hypothetical; it invalidated my first probe.** Testing this with `Response`
showed the keyword import "working" — until the control ran: `Response.html(...)` prints the same
answer with NO import line at all, because `Response` is a builtin. A probe whose subject is
reachable without the thing being tested cannot measure it. The measurement above uses
`std/middleware.ssc`'s `withTiming`, which is `unbound global: withTiming` when nothing imports it.

**Related but different:** `v2-native-scala-import-parse-only-noop`
(`v1/runtime/backend/interpreter/BUGS.md`) is about keyword-imported names failing to BIND, and is
`status: fixed`, re-verified not reproducing. Here the names bind correctly — what is missing is
validation of the module path.

**Observed while measuring, NOT filed as a defect because nothing specifies otherwise:** neither
form's selector restricts. `import std.middleware.withTiming` and `[withTiming](std/middleware.ssc)`
both leave `withRequestId` — a different name in the same module — bound. Selection appears to be
documentation at module granularity in both surfaces. If that is intended, it is worth saying so in
`docs/user-guide.md`, since `import p.{a}` reads as a restriction to every Scala user.

**DECIDED 2026-08-09 by Sergiy: `import a.b.c` that maps to no file must say "not found", and
programs relying on the silence are to be fixed.** Recorded here with the measurement that changes
what "fixed" means, because taking the decision literally would break correct code.

**The rule cannot apply to every keyword import.** Of 192 keyword-form import lines in `.ssc` across
the repo, most name host namespaces — `scala.concurrent.Await`, `java.*`, `org.*`, `sttp.*` — which
map to no file BY DEFINITION and must stay silent. What can be held to "not found" is the namespace
the repository actually ships as modules: `std.`. There are **seven distinct `std.*` keyword
imports; five resolve and two do not.**

**And the two that do not are not the programs' fault.** `std.pdf.*` (three examples) and
`std.payments.swift.*` (one) name modules that do not exist — while the functions they bring in DO:
`pdfPageCount` / `pdfToMarkdown` come from `v1/runtime/std/pdf-plugin`. Every other plugin in this
repository has a matching declaring module — `std/actors`, `std/auth`, `std/bench`, `std/content`,
`std/dstreams`, `std/fs`, `std/graphql`, `std/http`, `std/json`, `std/mcp` are all present. **Counted:
39 plugins, 20 with a `std/` module, 19 without** — `cache clock deploy env fetch frontend graph
logger oauth pdf random request retry scljet-jdbc scljet-vfs sql state swing ws`.

So the imports in those four examples are RIGHT and the `std/` tree has a hole; the silence is what
hid it for as long as it has been there. Turning on the diagnostic first would make correct code red
and teach people to delete correct imports.

**Order this implies:** write the missing declaring modules for the names actually imported (`pdf`,
`payments/swift`) → then the diagnostic, scoped to `std.` → then update
`tests/e2e/keyword-import-missing-module.sh`, which already says in its own header that this is how
it expects to be retired, and this entry with it.


⚠ **CORRECTION 2026-08-09, same day: the measurement above used PATHS and the answer changes.** I
checked whether `std/pdf.ssc` or a `std/pdf/` directory existed. A package is not a path here — it is
declared in a file's front-matter and the file may sit anywhere. `std.pdf` IS declared, by
`std/pdf-gen.ssc`. So the three examples importing `std.pdf.*` are correct and nothing needs writing
for them.

**Re-measured against DECLARED packages** (`grep -rh '^package:' --include='*.ssc'`), which is the
set that actually exists:

    std.crypto  ok      std.geo  ok       std.graphql  ok    std.mapreduce  ok
    std.mime    ok      std.pdf  ok       std.payments.swift  MISSING

**One import in one file** — `examples/international-bank-rails.ssc`. That is the whole
compatibility cost of the decision, not four files and not a tree of missing modules.

The plugin count changes the same way and is restated rather than deleted: 39 plugins, 20 with a
declared `std.<name>` package, 19 without — `cache clock deploy env fetch frontend graph logger
oauth random request retry scljet-jdbc scljet-vfs sql state swing uuid ws`. **The 20/19 split is
identical to the path-based count by coincidence; the LIST is not** — `pdf` left it and `uuid`
entered. A number that survives a corrected method is not thereby confirmed.

**THE RULE, in the owner's words and now checkable:** an `import a.b.c` is "not found" only when
NOTHING provides `a.b.c` — not a declared `.ssc` package, and not a host package. That needs two
sources of truth, and the second is what keeps `scala.concurrent.Await`, `java.*` and `org.*` silent:
they resolve on the JVM classpath. **A plugin is NOT a third source** — measured: plugins register
BARE names (`QualifiedName("pdfPageCount")`, `appendFile`, `httpDelete`), never package-qualified
ones. The namespace comes from the `.ssc` module that declares `package: std.json`; the plugin only
supplies the implementation behind it. So "the plugin exports into that package" is not a case that
occurs, and the rule needs only the two.

**Order, corrected:** no missing modules block this. Implement the diagnostic against declared
packages plus host resolution, fix the single import in `international-bank-rails.ssc`, then retire
`tests/e2e/keyword-import-missing-module.sh` as its own header describes.


**IMPLEMENTED 2026-08-10 in `f467a9ba8`, per the decision above.** `NativeSourceClosure` now
recognises the keyword form and reports a module nothing declares:

```
ssc: native frontend import not found: std.nosuchmodule_9d4f.anything from kw.ssc
     — no module declares `package: std.nosuchmodule_9d4f.anything`
```

**Scoped to `std.`, from the count rather than from caution:** `std` is the only import root whose
names land in a declared package (18 of 19); `scalascript` (95 imports), `scala` (32), `actors` (11),
`org` (8), `nodes` (6), `java` (3) resolve to ZERO because they are host and plugin surfaces. The
gate pins that they stay silent, and that a real `std` package still imports — without the second
the check could pass by refusing everything.

**Resolution reads `package:` from front matter**, accepting a declared package, an ancestor of one,
or a member of one (`import std.json.parse`). Not paths: `std.pdf` is declared by `std/pdf-gen.ssc`,
so a path check calls three correct imports missing — the error in my own first measurement.

**THE FIRST IMPLEMENTATION COMPILED, LOOKED RIGHT, AND DID NOT FIRE**, and that is the part worth
keeping. It matched keyword imports only INSIDE a fence, while fences are optional — a bare `.ssc` is
code from line one, and both the probe and this gate's own fixture are exactly that shape. The
pre-flight sweep I had written to predict the blast radius shared the bug and therefore agreed with
it. Only the end-to-end run disagreed.

**One program needed fixing**, measured before and after across all 1569 `.ssc` files:
`examples/international-bank-rails.ssc` imported `std.payments.swift.*`, which nothing declares —
`SwiftProvider` comes from the swift plugin. The line is removed rather than repointed because there
is no module to point it at, and removing it cannot change behaviour: the front never saw it. After
the fix the sweep reports zero.


## new-array-n-builds-a-one-element-array — the allocate-n form is lowered as the factory form
<!-- status: fixed
     lane: multi
     kind: bug
     area: front
     fixed-in: 2c1c1c9cb
     gate: tests/conformance/generic-ctor-and-array-alloc.ssc -->

**FIXED on all four lanes.** int, js and jvm in `2c1c1c9cb`; the v2 legacy front in `2d29b3e71`;
the v2 F front — the default — in `ce637cafb`. The gate runs on all four with no declared red.

The entry named `v3/tests/array-floor.ssc` as its gate and that file was never written: the check
became a conformance case instead, which is the right home because it needs all four lanes and the
shared oracle. **The entry stayed `open` with `fixed-in: -` for six days after the fix landed** —
my own bookkeeping, and exactly the drift `bugs-status-drift` exists to catch.

**Found 2026-08-01** while scoping `v3`'s `SSC3-0`, by measuring an inherited assumption instead of
trusting it. Blocks `v3` `SSC3-1` and through it the whole IR implementation: an SSC IR frame is a
fixed-size mutable indexed vector, so `Array` is the floor the register machine stands on.

`new Array[T](n)` — *allocate `n` default slots* — is lowered as `Array(n)` — *a one-element array
containing `n`*:

```scalascript
def main(): Unit =
  val a = new Array[Int](3)
  println(a.length)          // prints 1, expected 3
```

Measured on both lanes at `ba97940c0`:

| probe | expected | `./bin/ssc run` | `./v2/ssc1` |
|---|---|---|---|
| `new Array[Int](3).length` | `3` | `1` | — |
| `new Array[Int](3)`, then `a(1) = 20` | `20` | `ssc: 1 is out of bounds (min 0, max 0)` | `IndexOutOfBounds` in `Prims$.methodDispatch4` |
| `Array(1,2,3).length` / `a(2)` | `3` / `3` | `3` / `3` ✓ | — |

`lane: multi` is measured, not assumed: the interpreter lane and the v2 lane are both wrong, which
is why this is here and not in `v2/`.

**Why it survived.** The factory form `Array(1,2,3)` is correct, and that is the form the corpus
exercises. `Typer.scala:236` defines `Array` as a one-argument function, and v2 lowers construction
through `_arr_fill` into an `ArrayBuffer` (`v2/lib/ssc1-lower.ssc0:4822`). The two forms share a
path, and only the one no test writes is wrong.

**Distinct from `v2-array-indexed-store-silently-dropped`** (`v2/BUGS.md`, fixed in `693f0f891`),
which is `a(i) = v` on the *factory* form. That one is closed and its gate
(`tests/conformance/array-indexed-store.ssc`) passes; it never constructs with `new`.

**No exit-code check could catch it:** `./bin/ssc run` on the failing program **exits 0**. The wrong
answer and the out-of-bounds message both leave the status clean — the same shape as the v2 `Stub`
sentinel. Compare output, never the exit code.

Also blocks `uniml-portable` phase 3 (`uniml/v2-smoke/gap-array.ssc`), where it has been open since
2026-07-13 recorded as a v2-only gap. It is not v2-only.

**Fix and gate:** `v3/SPRINT.md` `SSC3-1`. `v3/tests/array-floor.ssc` asserts length, indexed
read/write past index 0, and `Array.fill`, on int, js, jvm and v2. Must be observed failing on every
lane before the fix lands, with the red count in the commit message (P-6.1).

## ssc-tools-info-rejects-front-report-at-exit-0 — an unsupported flag becomes a silent empty report
<!-- status: fixed
     kind: bug
     lane: multi
     area: cli
     fixed-in: 8e145984b
     gate: tests/e2e/info-unknown-flag-gate.sh -->

**Found 2026-07-31** while sweeping the corpus for F front verdicts.

```
$ bin/ssc-tools info --front-report tests/conformance/std-index.ssc
Warning: ssc info currently inspects a single artifact; ignoring 1 extra path(s).
info: file not found: --front-report
$ echo $?
0
```

`bin/ssc info --front-report FILE` works and prints `FILE<TAB>VERDICT<TAB>reason`. The same
subcommand on `ssc-tools` does not know the flag, so it is consumed as a PATH — and the run still
exits 0.

**Why it is worth an entry rather than a shrug:** the two diagnostics are each individually
misleading and jointly point away from the cause. "ignoring 1 extra path(s)" describes the FILE as
the extra path, and "file not found: --front-report" names the flag as a file. Combined with exit 0,
a sweep built on this produces zero rows and reads as a clean result — I very nearly recorded
"F declines nothing" from it. A tool that cannot do what was asked must not exit 0.


**FIXED 2026-08-05, and one third of this entry had already gone stale.** Re-measured before
touching anything: the run exits **1**, not 0 — the headline "at exit 0" no longer holds, and the
title is left as filed rather than rewritten, since it is how the entry is cited elsewhere.

What DID reproduce is the part that mattered more, and the entry was right that it is the expensive
half: both diagnostics point away from the cause. `--front-report` fell through `InfoCmd`'s argument
loop into the catch-all `case f => files += f`, so a FLAG became a PATH; then "ignoring 1 extra
path(s)" described the real FILE as the extra one and "file not found: --front-report" described a
flag as a file.

Two changes, the second being the general case:

- `ssc-tools info --front-report` now does what `ssc info --front-report` does — it dispatches to the
  same `RunNativeV2.frontReport`. Verified byte-for-byte identical on both launchers.
- An unknown `--flag` is now REJECTED as a flag (`info: unknown flag '--x'`, plus the supported
  list) instead of being collected as a path. That is what the next unrecognised flag would have hit
  too; fixing only `--front-report` would have left the trap armed.

**Gate:** `tests/e2e/info-unknown-flag-gate.sh` asserts both properties, and the second asserts the
absence of the misdirection specifically — the message must NOT say "file not found" — because an
exit code alone would not have caught what this entry is about. On the unfixed toolchain both cells
fail: `--front-report` prints the two misleading lines and no report row, and an unknown flag prints
`file not found: --definitely-not-a-flag`.

NOT registered in `scripts/smoke-ci.ssc` / `.github/workflows/ci.yml`: both are held by a live claim
(`smoke-budget-600-and-tier2-triage`) and this repo names gates there by hand. An orphan gate is
visible to `orphaned-gates-runner-sweep`; editing another agent's claimed files is not.

## int-string-concat-operator-builds-a-pair — `"x" ++ "y"` was `(x, y)` on the golden lane
<!-- status: fixed
     kind: bug
     lane: int
     area: runtime
     fixed-in: unrecorded
     gate: tests/e2e/int-string-concat.sh -->

**FIXED 2026-07-31** on the owner's call. `"x" ++ "y"` returned the PAIR `(x, y)` on the
interpreter and `xy` on v2 and js. Scala concatenates — String is a `Seq[Char]` — so the corpus
GOLDEN was the wrong lane, the third time in one session.

`DispatchRuntime`'s binary-operator table for `++` enumerates List/Set/Map/Tuple/Unit shapes and
ends in `case _ => Pure(Value.TupleV(lhs :: rhs :: Nil))`. Two Strings match none of the listed
shapes, so they fell into the tuple arm. One `case (StringV(a), StringV(b))` fixes it.

**Blast radius MEASURED, not argued.** This moves the golden lane, so the full corpus contract was
run across int/js/v2 afterwards: **1064/1105 PASS, baseline 111, zero changes**. No case anywhere
relied on `++` producing a pair from strings, and no freeze update was needed.

FALSIFIED by `git checkout HEAD --` on the file and rebuilding: the gate fails with exactly
`(x, y)` / `(lit, eral)`.

⚠️ **I fixed the wrong site first, and the wrong fix was invisible.** `++` on strings never reaches
method dispatch, so a `case "++"` added to `dispatchString` compiles, looks right, and never fires.
Two `System.err.println` markers — one in the new branch, one in the fallback tuple arm — both stayed
silent, which is what proved the operator takes a THIRD path: the binary-operator table. That table
is the fourth "second copy of this logic" found today.

⚠️ The gate asserts `List ++ List` and `(1,2) ++ (3,4)` as well, and both pass before AND after. They
are the guard, not the evidence: the new String arm sits directly above the List arm, and `++` is
genuinely tuple-append for other shapes — a fix that swallowed either would still satisfy the string
assertion.

## typer-defines-sys-but-no-runtime-provides-it — `sys.env` type-checked, then died at runtime (three lanes when filed, one when fixed)
<!-- status: fixed
     kind: bug
     lane: native
     area: runtime
     fixed-in: d7ca80629
     gate: tests/conformance/sys-env.ssc -->

**FIXED 2026-08-02** by `v2-sys-env`, and the entry's own scope was wrong in the good direction.

**Re-measured first: ONE lane of four, not three.** `int` and `js` both answer today; only the
native lane still died with `unbound global: sys`. Four other entries this session were already
fixed and left open, so measuring before coding is now the default here.

**The fix is one registration, and the conformance case predicted three.** That case (`sys-env.ssc`)
recorded that native would need `sys` emitted by BOTH self-hosted fronts as well as backed in the
runtime — with one of those files held by another claim, which is why the `v2` row had been left
out. Wrong: `sys` is a VALUE, not a call, so `NativePluginContext.registerValue` plus a
`NamedMethodObj` is the whole thing, and the fronts never have to learn the name — v2 resolves the
field BY NAME through `ForeignV` (`Runtime.scala` consults `getField` on the selection path).

`registerGlobal` would not have worked: it registers FUNCTIONS. And a `DataV` would not either —
v2's field access is index-based over a compile-time name→index registry that an ad-hoc object
cannot have. `NamedMethodObj` is the one shape that fits.

The map is a snapshot taken at install, which is `sys.env`'s Scala semantics. The corpus row is
now `int, js, jvm, v2` and all three runnable lanes print the same output.

**FIXED on int and js 2026-07-31; native remains, and the three edits it needs are named below.**

**This entry's headline was wrong and the correction matters.** It said "No runtime provides it."
Measured on all four lanes:

| lane | before | after |
|---|---|---|
| `jvm` | **works** — it IS Scala | works |
| `int` | `Undefined: sys` | reads the environment |
| `js` | `ReferenceError: sys is not defined` | reads the environment |
| native / `v2` | `unbound global: sys` | **still `unbound global: sys`** |

One lane of four always worked, which is why the typer's definition was written in the first place —
and it means the JVM lane is the ORACLE here rather than a fourth opinion. That changed the fix from
"invent a semantics" to "match the one that already exists".

**Why not simply delete the typer's definition**, which would be one edit instead of three: eight
examples use `sys.env` (`x402-client`, `x402-cardano`, `bank-rails-sepa`, `bank-rails-pix`, …). They
type-check today and die when run. Deleting the symbol moves their failure to COMPILE time — honest,
but it turns CI's "Type-check examples" step red. Which exposes the sharper fact: **the false
promise is what keeps eight unrunnable examples green in CI.**

**What native still needs** (`specs/v2.2-p6.5-fsub.ssc` is held by a live claim, so this row is
handed over rather than half-done):

1. `v2/lib/ssc1-lower.ssc0:4549` — beside `mathDef`, a `sysDef = IrDef("sys", IrPrim("__sys_obj__", Nil))`;
2. `specs/v2.2-p6.5-fsub.ssc:2471` — the F prelude string gains `(def sys (prim __sys_obj__))`, exactly
   as it already carries `(def math (prim __math_obj__))`. **This changes F's own emitted prelude, so
   the fixpoint gate must be re-run** — `stage1 == stage2` should still hold, but it is not free;
3. `v2/src/Runtime.scala` — `case "__sys_obj__" => ForeignV("__sys__")` plus an `env` arm beside the
   existing `ForeignV("__math__")` dispatch.

Until then native fails LOUDLY, which is the honest state — a wrong answer would be worse than an
error.

**Found 2026-07-31** by `skip-reprobe-after-fixes`, while re-probing the corpus SKIP list.

`v1/lang/core/.../Typer.scala:249` defines the symbol:

```scala
s.define(Symbol("sys", SType.Named("sys", Nil), SymbolKind.Object))
```

No runtime provides it. One line, both lanes:

```scalascript
def main() = println(sys.env.getOrElse("HOME", "none").length > 0)
```

```
int  [ERROR] [line 1, col 22] Undefined: sys
v2   ssc: unbound global: sys
```

**The shape is what makes it worth filing:** the type system PROMISES a symbol that nothing delivers, so a
program using standard Scala `sys.env` passes type-checking and fails only when it runs. That is worse than
an honest "unknown name" at compile time, and it is not specific to `sys.env` — anything reached through
that symbol behaves the same way.

**Four corpus cases use it** (`x402-cardano`, `x402-cardano-scalus`, `x402-client`, `x402-server`) and it is
their FIRST blocker. ⚠️ Fixing it would not un-skip any of them: all four also need network, so they belong
to the "cannot run headless" bucket either way. Recording that here so nobody spends the work expecting a
coverage win.

**Two honest resolutions, and the choice is a decision:** provide `sys.env` (ssc's own environment surface
today is the `Env` effect, so this would be a second way to do one thing), or remove the typer symbol so the
failure moves to compile time where it belongs.

## std-has-no-stdin-primitive — nothing in std reads a line from stdin, so an interactive `.ssc` program cannot exist
<!-- status: fixed
     lane: multi
     area: runtime
     kind: feature
     reported-by: https://github.com/sergey-scherbina/scalascript/issues/76
     reported-at: 2026-07-31
     ssc-version: unknown
     confirmed: no
     fixed-in: 862a19adb
     gate: tests/conformance/std-os-readline.ssc -->

**Status: FIXED 2026-07-31 on the lanes where `std/os` exists** — `int` and the native/default tier.
Filed by `nadia-dev` (rozum side), blocking an external consumer; reported from outside as #76.

    extern def readLine(): Option[String]   // None at EOF

The reporter's own program now works on the lane users actually run:

```
$ printf 'sergiy\n' | bin/ssc run prompt.ssc
your name?
hello sergiy
```

**Scope is a MEASUREMENT, and it is narrower than this entry originally proposed** ("implement it per
lane: int, js, jvm, native"). `std/os` does not resolve on `js` or `jvm` today — measured on
`envOrElse`, which fails there with `not callable: ()` and `value envOrElse is not a member of object
std.os`. There is nothing on those lanes to add `readLine` TO; the gap is the whole `std/os` surface,
not this primitive, and filing it as "readLine is missing on js" would have pointed the next reader
at the wrong thing. `readLine` now works exactly where `env` works.

**One defect found by fixing this one, filed separately as `ssc-tools-swallows-piped-stdin`:** on the
`ssc-tools` route the CLI reads stdin to EOF before the program starts (`Main.scala:72` →
`Source.stdin.mkString`), so piped input never reaches the program there. It was undiscoverable
before — nothing could read stdin, so nothing could notice stdin was gone. It carries a real design
fork (the sops feature is built around that same pipe) and is raised in the room rather than decided
unilaterally.

**MERGED BY TRIAGE 2026-07-31, and this entry is the survivor.** Issue
[#76](https://github.com/sergey-scherbina/scalascript/issues/76) and this hand-written entry are one
report arriving twice: the issue through the front door, this one written straight onto the board.
Per its own note, triage owned the merge. What each half carried:

* the issue — the reporter fields, now in the header above. They are the point of the queue:
  `reported-by` is a URL that can be REPLIED to, so `confirmed: no` ("fixed, but the reporter has not
  confirmed") is answerable rather than decorative.
* this entry — the proposed surface and implementation order below, which the issue deliberately
  omits because the form asks reporters not to diagnose.

Neither half was discarded and there is no second copy: the `INBOX.md` entry was deleted on routing,
which is what P-3.10 means by "leaves the queue by MOVING". Confirmed independently against
`origin/main` before merging, rather than taken on trust — `readLine()` prints the prompt and then
exits **1** with `ssc: unbound global: readLine` on stderr, so the runtime fails CLOSED and the gap
is a missing capability, not a broken one.

`std.os` exports `env` · `envOrElse` · `args` · `exit` · `cwd` · … — the whole environment
surface **except input**. There is no `readLine`, no `stdin`, no console module: the only
occurrence of the word in the tree is a comment in `runtime/std/free.ssc:97` (“production:
println / readLine”). Output has `println`; input has nothing.

The consequence is categorical rather than cosmetic: **no `.ssc` program can prompt a user
and read the answer**, on any lane. A REPL, an interactive CLI, a confirmation prompt, a
wizard — none of them are expressible today. Everything else the shape needs is already
here (`std.process.exec`, `std.fs`, `std.actors`, `std.http`), which is what makes this the
single missing piece rather than one of several.

**Concrete blocked work.** `nadia` (see rozum's `REPOS.md`) is an agent whose interactive
mode is a line-based REPL over `std.agent`. Its batch half is written and works; the
interactive half cannot be written at all. The Rust twin (`rozum:crates/nadia`) ships the
same REPL in ~40 lines because `std::io::stdin().read_line` exists.

**No workaround on the default lane.** A ` ```scala ` passthrough block does not help:
the standard tier is a self-hosted VM, not Scala-on-JVM, so `java.io` is unbound there
(`ssc: unbound global: java`). Reading stdin through `exec` (`exec("head", ["-n1"], …)`)
inherits no terminal and returns immediately.

### Shape of the fix

Exactly the one landed a day earlier for the sibling gap — `f101312ed` *"implement `exec`
on the native tier — std.process was missing from the DEFAULT lane"* — and that commit is
the template, down to the ordering:

1. Declare the primitive in `runtime/std/os.ssc` (or a new `std/console.ssc` if input
   deserves its own module) and add it to `exports:`.
2. Implement it per lane: `int`, `js`, `jvm`, `native`. On the native tier it is the same
   `QualifiedName(...) -> native { … }` registration `exec` now uses.
3. A conformance case that reads a line and echoes it, driven with a here-string so it is
   deterministic — that is the `gate:` this entry currently lacks.

Suggested surface, with the EOF case explicit so callers are forced to handle it rather
than discovering it as an empty string:

```scala
extern def readLine(): Option[String]   // None at EOF
```

`readLine(prompt: String)` is deliberately **not** proposed: prompting is `print` +
`readLine`, and folding them together makes the primitive untestable without a terminal.

### Not to be confused with

`std.process.exec` being unbound on the default lane, which **is already fixed**
(`f101312ed`). It was re-reported from the rozum side on 2026-07-30 against a toolchain
built at `ff493301c` — i.e. before the fix, with `SSC_NO_BUILD_CHECK=1` silencing the
launcher's own staleness warning. That report was wrong; this entry is not the same gap.

## bench-wrapper-hardcodes-a-long-seed — two lanes read `n/a` for a defect in the harness, not in them
<!-- status: fixed
     lane: apparatus
     area: cli
     kind: apparatus
     gate: tests/e2e/bench-seed-type-gate.sh
     fixed-in: 5264ad6631ca2a11153e8c0b9876e35675da2489 -->

**Found 2026-07-31 by asking why `var-expr-init-int` was the one corpus row blank on TWO lanes.**
`bench.sh`'s table had `n/a` for both `jvm` and `js` there, which reads as "these backends cannot run
this workload". They can. The wrapper could not call it.

`ssc bench` feeds the workload an opaque `seed` to defeat constant-folding. It detected that
parameter by NAME — `def\s+workload\s*\(\s*seed\b` — and then hardcoded a `Long`:
`workload(_ssc_sink.get())` on the JVM lane, `var _ssc_seed: Long = 1L` on interp/js. Every other
corpus row writes `def workload(seed: Long)`, so nothing noticed. `var-expr-init-int` deliberately
declares `seed: Int` — it exists to reach F's typed regime, which recognises `Int` and not `Long` —
and for it the wrapper emitted a type error on both lanes:

```
jvm  -- [E007] Type Mismatch Error: … _ssc_sink.getAndAdd(workload(_ssc_sink.get()))
js   TypeError: Cannot mix BigInt and other types      (let s = (_imod(seed, 46341) + 1))
```

The js half is worth reading twice: JsGen was RIGHT. `seed` is declared `Int`, so it emitted a
native `+`; the harness then passed a BigInt at runtime. A backend cannot defend against a caller
that lies about the type it declared.

**This is the second time this wrapper has blamed a backend for its own defect.** The first is
recorded in `Main.scala`'s Double branch: a `0d` literal the self-hosted front cannot lex made the
harness report three float workloads as backend failures. Both have the same shape — *the
measurement apparatus produced a plausible-looking cell that was about itself*, which is the failure
[`AGENTS.md` §"measurement apparatus must COMPARE"](AGENTS.md) exists for.

**Fixed** by reading the seed's declared type and emitting a matching argument and declaration. The
JVM seed stays the opaque atomic load and only its width is adapted (`_ssc_sink.get().toInt`) — a
truncation of an opaque value is still opaque, so the anti-fold reasoning above it is unaffected.

- **Measured, the corpus row, all four lanes:** `ssc` 3.20 · `jvm` 3.41 (was `n/a`) · `js` 51.6 (was
  `n/a`) · `v2` 145.2 ms/iter.
- **Gate:** `tests/e2e/bench-seed-type-gate.sh`, in smoke-ci. Two fixtures differing ONLY in the
  seed's declared type, run through the real `ssc bench --machine` on `ssc,js,jvm`; the `Long`
  fixture is the control that fails if the gate itself breaks. Fail-first: 2 of 6 cells red before
  the fix, 6 of 6 green after.
- **Worth knowing:** the interpreter lane was green throughout, because it is dynamically typed.
  A green `ssc` column is not evidence that the wrapper is well-typed.

## bugs-headers-were-never-migrated-from-the-prose — 118 entries the canonical query called `unknown` announced the opposite in their own heading
<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: tests/e2e/bugs-status-drift-gate.sh
     fixed-in: d8a51c2c6 -->

**Found 2026-08-01 while reading `v1/runtime/backend/interpreter/BUGS.md` for an unrelated reason.**
Three consecutive entries had headings ending `— fixed (2026-06-21, 0d5e03b87)` and headers saying
`status: unknown`. It is not three; it is **118 of the 138 `unknown` entries**, across six boards.

They pre-date the header schema and were never migrated. The consequence is not cosmetic, because
this repo BANS the alternative: `AGENTS.md` says *"Do not grep the prose for status — run
`scripts/bugs-report`"*, after a hand-rolled query silently omitted 108 entries while answering a
direct question about remaining work. So the sanctioned query was itself wrong by about one entry in
six — `--status fixed` low by 118, `unknown` 86 % noise — and nothing on screen suggested it.

**What is fixed, and what is not.** `bugs-report` now prints the deficit (`bde28301e`), so the
canonical answer no longer lies silently. The 118 headers are still wrong, which is why this entry
is `open`.

**Why they were not auto-migrated — a decision is required, and it is not mine to take.**
`specs/bugs-index.md` requires `fixed-in: <sha>` whenever `status: fixed`. Only **8** of the 118
carry a sha in the heading. So the options are:

1. **Recover the shas** — `git log -S<slug>` per entry, 110 times. Expensive, and the failure mode
   is already recorded one entry below this one: a sha that resolves locally and dangles for
   everyone else is worse than no sha, because the gate goes green on it.
2. **Relax the schema for pre-schema entries** — allow `status: fixed` without `fixed-in` when the
   entry predates the schema, and say so in `specs/bugs-index.md`. Requiring a field that did not
   exist when the entry was written is a demand made of the past.

**FIXED 2026-08-01 (`d8a51c2c6`) — and neither option was needed.** `specs/bugs-index.md` already
sanctioned the answer at line 117: `fixed-in: unrecorded`, described there as being for exactly this
population, "the many older entries whose prose says a defect was fixed but never names the commit".
The question above was posed without reading the spec far enough; it had been decided before it was
asked. All 118 migrated to `status: fixed` + that sentinel, by `scripts/bugs-migrate-status.py`.

Counts: `fixed` 439 → 558, `unknown` 138 → **20** (the genuinely unclassified residue), drift → 0.

**Option 1 was attempted anyway and is recorded as REFUTED, because it is the plausible-looking one
somebody will try again.** Scraping a fix sha out of an entry's prose fails twice over:

  - a window of the entry's next N lines runs into the NEXT entry —
    `parser-trysplitparse-quadratic-hang` has no sha of its own and was handed `f2afd3378` from the
    neighbour below it;
  - and that sha is not a fix at all. In `rust-index-read-moves-noncopy` it names the commit that
    CAUSED the bug: "the bug only surfaced once `f2afd3378` made `.split`/`.toList` results
    indexable".

Position cannot separate a fix sha from a cause sha, a neighbour's, or a passing mention, and
`git log -S<slug>` has the same defect one level up — it finds the commit that edited the ENTRY. A
`fixed-in` naming the wrong commit reads as authoritative, which is worse than a sentinel that says
"fixed, provenance missing". Anyone who knows a real fix sha can still add it.

The migration script is kept rather than deleted: it is idempotent, selects on current state, and
its docstring is where the refutation above lives.

- **Repro:** `scripts/bugs-report` — read the `status drift` line under index coverage.
- **Gate:** `tests/e2e/bugs-status-drift-gate.sh` keeps the DETECTOR alive; it deliberately does
  NOT freeze the count 118, because every migration would falsify it (the mistake the negtc gate
  made when it froze corpus counts that breadth reclassifies).
- **This entry's own heading avoids the status word on purpose.** Written the obvious way it
  became the detector's third false positive within a minute of being saved — an entry ABOUT
  status drift trips a heuristic that looks for the status word. That is the honest limit of
  reading prose, and it is why the report calls its number approximate and offers `--drift` to
  list the rows instead of asking anyone to trust a count.
- **Two traps found building the detector**, both now fixtures in that gate: `\bfixed\b` matches
  the FIELD NAME inside the slug `bugs-index-fixed-in-checks-resolvable-not-reachable` (`-` is a
  word boundary), which shipped one permanent false positive; and `bugs-report --file` crashed with
  a traceback on any path outside the repo, which is exactly where a query's test fixture belongs.

## bugs-index-fixed-in-checks-resolvable-not-reachable — a rebased sha passes the gate and dangles for everyone else
<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: tests/e2e/bugs-index-gate.sh
     fixed-in: 19b9561b9 -->

**Found 2026-07-31 by doing it**, within ten minutes of writing the entry above. I recorded
`fixed-in: ddec573ae`, then `git rebase origin/main` before pushing rewrote that commit to
`862a19adb`. The gate stayed green:

```
$ git cat-file -e ddec573ae^{commit} && echo resolves        # resolves   ← what the gate asks
$ git merge-base --is-ancestor ddec573ae origin/main         # NO         ← what matters
```

`tests/e2e/bugs-index-gate.sh:99` asks `git cat-file -e <sha>^{commit}`. That is true for any object
in the LOCAL store — including one the rebase orphaned, which lives on only in my reflog. Push it and
the entry cites a commit nobody else can resolve. Same shape as
`submodule-pointer-not-a-real-commit`: a recorded sha that looks valid exactly where it was written.

It is not one line, which is why this is filed rather than fixed in passing. `merge-base
--is-ancestor <sha> origin/main` is the correct question but CI clones with `fetch-depth: 1`, where
almost nothing is reachable and the check would fail on every honest entry (the same shallow-clone
trap that made `<sha>~5` unresolvable in the CI-health job). A fix has to either fetch enough history
to answer, or answer a weaker question deliberately and say so. Both are decisions, and the second
needs the gate's header to state what green means — a gate that quietly checks less than its name
suggests is the failure this repo keeps paying for.

Cheap mitigation available today, independent of the gate: **record `fixed-in` AFTER the rebase, not
before.** The sha you commit with is not the sha you push when anything landed in between.


**CLOSED 2026-08-05 — fixed in `19b9561b9`** ("fixed-in must be REACHABLE, not merely present — 17
entries were not"), and the fix took both halves this entry said a fix would need:

- `tests/e2e/bugs-index-gate.sh:142` now asks `git merge-base --is-ancestor <sha> HEAD` instead of
  `git cat-file -e`, with the comment naming the distinction: "REACHABILITY, not existence".
- The shallow-clone problem is answered deliberately rather than papered over. The gate reads
  `git rev-parse --is-shallow-repository`, and in a shallow clone checks the SHAPE only while
  PRINTING that it did: *"note: shallow clone — `fixed-in` checked for SHAPE only; run in a full
  clone to verify each sha resolves."* That is exactly what this entry asked for — a gate that
  checks less than its name suggests has to say so.

**Confirmed by being caught by it**, which is better evidence than reading the code. On 2026-08-04 I
recorded `fixed-in: 7866d59a1`, rebased before pushing, and the gate refused:

```
FAIL [type-ascription-tuple-and-set-arms-missing] fixed-in `7866d59a1` exists locally but is
     NOT an ancestor of HEAD — a pre-rebase orphan, invisible in a fresh clone
```

That is the precise failure this entry was filed about, detected at the right moment — before the
push rather than after.

The mitigation this entry recommended is still the right working rule and cost me two more rounds
before I adopted it: **record `fixed-in` AFTER the final rebase.** A rebase rewrites the commit you
just measured, so any sha written before it is stale by construction; and if a push is rejected and
forces another rebase, the sha has to be rewritten again.

## ssc-tools-swallows-piped-stdin — every command except lsp/repl reads stdin to EOF before the program runs
<!-- status: fixed
     lane: apparatus
     area: cli
     kind: bug
     gate: tests/e2e/stdin-belongs-to-the-program.sh
     fixed-in: b44ff45ce -->

**FIXED — and BOTH of the options this entry offered were taken, in order.** `--secrets-file <path>`
landed first (S1) so there was a replacement before anything was removed, then a warning (S2), then
the implicit slurp became opt-in behind `SSC_SOPS_STDIN=1` (S3, `b44ff45ce`). Tracked as
`ssc-tools-stdin-belongs-to-the-program`; this entry is the report and was simply never closed.

Measured 2026-08-08 on the same program the report used, all three paths:

    printf 'ada\n' | bin/ssc run prompt.ssc                        -> reaches the program
    printf 'ada\n' | bin/ssc-tools run --v1 prompt.ssc             -> reaches the program  (was: swallowed)
    printf 'ada\n' | SSC_SOPS_STDIN=1 bin/ssc-tools run --v1 …     -> swallowed, as designed

The third line is why the middle one is trustworthy: the escape hatch still consumes stdin, so the
gate distinguishes two states rather than observing one. `tests/e2e/stdin-belongs-to-the-program.sh`
PASSES and pins all three, including `--secrets-file` accepting a process substitution.

The fork below is left as written. It was a real fork, it went to the room as P-5 requires, and the
answer was option 4 with option 2 as the safety net — which is worth more on record than a tidy
"fixed".

**Found 2026-07-31 while verifying the fix for `std-has-no-stdin-primitive`** — which is the only
reason it was findable: nothing could read stdin before, so nothing could notice that stdin was gone.

`Main.scala:72`:

```scala
if System.console() == null && stdinCommand != "lsp" && stdinCommand != "repl" then
  loadSopsSecrets()
```

and `loadSopsSecrets` is `scala.io.Source.stdin.mkString` — it reads the stream **to EOF**. So on the
`ssc-tools` route, any piped input is consumed by the CLI before the user's program starts. Measured
with the same program on both routes:

```
$ printf 'ada\n' | bin/ssc run prompt.ssc            # default lane
your name?
hello ada

$ printf 'ada\n' | bin/ssc-tools run --v1 prompt.ssc  # tools route
your name?
(no input)
```

The doc comment says the slurp is silent "so that scripts piped other content don't break
unexpectedly". They do break — the content is simply gone, and the program sees EOF, which is
indistinguishable from a user who typed nothing.

**This is a genuine fork, not an oversight, and it belongs in the room (P-5) rather than to whoever
gets there first.** The sops feature is DESIGNED around this pipe — its own comment gives
`sops -d secrets.enc.yaml | ssc myapp.ssc` as the typical invocation, i.e. "run a program" is exactly
the case it wants to capture. Two features now want the same stream:

1. **Exclude the commands that run user code**, as `lsp`/`repl` already are. Smallest change; the
   exclusion list then has to track every command that hands stdin to a program, and that list drifts.
2. **Make the slurp opt-in** (`SSC_SOPS_STDIN=1`, or a flag). Removes the surprise permanently and
   matches the DEFAULT lane, which does not slurp at all — the tools route is the outlier here. Costs
   a behaviour change for anyone relying on the implicit capture.
3. **Peek rather than consume.** Fragile: deciding "is this YAML secrets" from a prefix is a guess,
   and it is still destructive when the guess is wrong.
4. **Give secrets an explicit channel** — `--secrets-file <path>`, composing with
   `<(sops -d secrets.enc.yaml)` or an explicit `/dev/stdin`. Stdin stays the program's, which is
   what every other runtime does.

**DECIDED 2026-07-31 by Sergiy: option (4) — give secrets their own channel (`--secrets-file`), with
(2) as a one-release transition.** Queued with slices in `BACKLOG.md`
`ssc-tools-stdin-belongs-to-the-program`; the ordering there is load-bearing (the replacement lands
before the old path is discouraged, not after).

Option (4) was not in the first list above and is the reason this was worth raising rather than
deciding in passing: stdin belonging to the program is the universal convention, the DEFAULT lane
already never slurps, and so the tools route is the anomaly — which reframes the question from "who
gets the stream" to "why is one of them taking it implicitly at all".

**Not blocking the reported gap.** `std.os.readLine` works on the DEFAULT lane (`bin/ssc`), which is
what users run; this defect confines the tools route to the EOF branch. The conformance case
`std-os-readline` gates the EOF branch on both lanes, which is what a runner feeding empty stdin can
honestly test.

## std-ui-fetchUrlSignalTo-declared-never-implemented — a std primitive that exists only as documentation
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt fetchPlugin/test
     fixed-in: unrecorded -->

**Status:** OPEN. Found 2026-07-30 by `skip-triage-golden-lane`.

`std/ui/primitives.ssc:185` declares `extern def fetchUrlSignalTo(...)` with a nine-line doc comment
explaining how it differs from `fetchUrlSignal`, and lists it in `exports:` (line 31). It has **zero**
registrations in the entire tree:

* absent from the interpreter's `FetchIntrinsics` (`v1/runtime/std/fetch-plugin/...`)
* absent from the JS allow-list at `v1/runtime/backend/js/.../JsGen.scala:1406`
* absent from `RustTuiIntrinsics`

Its sibling `fetchUrlSignal`, declared nine lines above with the SAME `extern def` shape and the same
`headers: Signal[String] = emptyHeaders` default, has three registrations and imports fine. So this
is not about `extern` and not about default arguments — the symbol does not exist on any backend,
including the JS one its own comment describes.

**Blocks** `control-center-live` on every lane (hence a corpus SKIP, which hides v2 too).

**FIX DIRECTION SETTLED 2026-07-30 — implement it; do NOT delete the declaration.** Its WRITE-side
twin is fully shipped: `fetchActionTo(method, urlSig, body, onSuccessTick[, headers])` has an
interpreter intrinsic (`FetchIntrinsics.scala:147`) and JS runtime support (`signals.mjs:558`, `:1282`
— it resolves its URL from a signal at click time). The doc comment here calls `fetchUrlSignalTo`
"the read-side counterpart of fetchActionTo", and that counterpart exists. So this is an UNFINISHED
FEATURE, not dead weight, and deleting the declaration would discard a deliberate half of a pair.

**Three sites, mirroring the two neighbours it sits between:**
1. `FetchIntrinsics.scala` — an entry next to `fetchUrlSignal` (:47) that reads the URL from a signal
   the way `fetchActionTo` (:147) does.
2. `JsGen.scala:1406` — add the name to the emit allow-list.
3. `signals.mjs` — re-fetch when the URL signal changes, the read-side mirror of the `:1282` block.

**No corpus gain, and that is not a reason to skip it.** `control-center-live` also binds a port, so it
stays a SKIP either way — the value here is that `std/ui/primitives.ssc` stops advertising a primitive
that does not exist on any backend. Wants its own slice; sized, not started.
**VERIFIED IMPLEMENTED 2026-08-10 — all three sites this entry sized are done.** Measured rather
than read, because the entry's own inventory is what had gone stale:

1. **Interpreter intrinsic** — `fetchUrlSignalTo` has three registrations in `FetchIntrinsics.scala`,
   and the plugin's suite carries two tests for it, one of them named *"Fetch plugin still has a
   fetchUrlSignalTo site — it was deleted by accident once"*. `sbt fetchPlugin/test`: both pass.
2. **`signals.mjs`** — three sites, including the read-side re-fetch this entry asked for: a
   `collectSig(fg.urlSig)` dependency registration and *"resolve the URL from its signal (fresh
   reactive value) at fetch time"*.
3. **JsGen** — needs no edit, and this entry's framing of it was slightly off. `JsGen.scala:1511` is
   not an emit ALLOW-LIST but a capability TRIGGER, applied with `.exists(allText.contains)` —
   substring, so the neighbouring `fetchUrlSignal` already matches inside `fetchUrlSignalTo` and the
   signals runtime is included. Adding the longer name would change nothing.

**The plugin also moved** — `v1/runtime/plugins/fetch-plugin`, not `v1/runtime/std/fetch-plugin` as
cited here. Following the path in the entry finds nothing, which reads like the absence it reports.

**Two tests in that suite are RED and are not this**: `std/ui data imports rowEditAction for public
rowEdit helper` and `std/ui data exposes remoteTable composing fetchRowsSource + dataTableView`. The
tree was clean when measured — `git status` empty — so they are pre-existing, and **no entry on any
board names them**. `sbt fetchPlugin/test` is 14 passing, 2 failing.


## v2-front-curried-def-second-clause — F drops the second parameter clause of a curried `def`
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     gate: tests/conformance/curried-def-clauses.ssc
     fixed-in: ca8bb823e -->

**Status:** OPEN (found 2026-07-28 by `v2-front-colon-trailing-lambda` while verifying that fix;
**A/B'd against `origin/main` — byte-identical behaviour with that change reverted**, so it is
pre-existing and independent).

**Reproduce** — two lines:

```scalascript
def ap(n: Int)(f: Int => Int): Int = f(n)
println(ap(3)((i) => i * 2))
```

F emits `(def ap (lam 1 (app (global f) (local 0))))` — a ONE-parameter lambda whose body applies
`f` as a **free global**. The second clause `(f: Int => Int)` is gone, so F declines the file with
`unbound global: (global f) is neither a top-level def nor an @-cell` and the F4a fallback
recompiles it with the legacy front. Output is correct (`6`), at the cost of a diagnostic on every
run and of F not being the front that compiled it.

**Severity: moderate, and it hides.** Fails into the fallback rather than into an error, so it is
invisible unless you read stderr — the same shape as the `val`-position half of
`v2-front-for-yield-parse-gap`. It also means any corpus case using a curried `def` is measuring
the legacy front while appearing to measure F.

**Not a duplicate of `v2-native-front-multiline-curried-def`** (FIXED 2026-07-18, `d0722478e`),
which was about the second clause starting on a NEW LINE. This one is a single-line curried def.

**FIX DIRECTION, now measured rather than guessed** (attempted 2026-07-28 by `v2-front-curried-def`,
**reverted** — the half-fix is worse than the bug, see below).

It is **two halves, and the def half alone is harmful**:

1. **The def.** `emitDefU` handles a second clause only when it is `(using …)`; a plain `(f: …)`
   clause falls through to `skipToEq` and is dropped. Generalising it to consume any number of
   `(params)` clauses is ~2 lines and makes F emit the oracle's shape,
   `(def ap (lam 2 (app (local 0) (local 1))))`.
2. **The call site, which is the part that is missing.** The oracle also FLATTENS the application:
   it emits `(app (global ap) 3 (lam …))`, while F's `postfix` loop nests them as
   `(app (app (global ap) 3) (lam …))`. This runtime has **no partial application** — with only
   half 1 applied, `ap(3)(f)` dies at run time with `arity: 2 expected, 1 given`.

**Measured, and this is why it was reverted:** with only half 1, the file goes from *"F declines,
fallback compiles it, prints `6`"* to *"F accepts, program fails at run time"*. `front-report` flips
`GAP` → `F`, which looks like progress and is a regression. Baseline restored and re-verified:
`GAP` + `6`.

**What half 2 needs.** Flattening `f(a)(b)` requires knowing the callee's TOTAL arity across
clauses, and F's `cx` has no arity table — `collectRetTab` carries return types only. So the real
work is a new pre-pass collecting per-def total arity (alongside `collectPlessDefs`), threading it
through `mkCxE`, and having `postfix`'s `(`-branch append to the existing argument list instead of
nesting when the callee is a known multi-clause def. Non-curried calls and genuine
returns-a-function calls must stay nested, so the arity lookup is the discriminator, not a syntactic
guess.

**Gate note:** `tests/e2e/v2-front-coverage.sh` uses this bug as its `--self-test` anchor (a known
GAP must still report GAP). Whoever fixes it must swap that anchor for another known gap, or the
self-test starts failing for the right reason.


**FIXED 2026-08-04 — both halves, as this entry insisted they had to be.**

1. **The def.** `emitDefU` accepted a second clause only when it was `(using …)`. It now RECURSES,
   so any number of clauses and any mix of the two is consumed and the def lowers at the total
   arity. Split across three functions (`emitDefU` / `emitDefUsing` / `emitDefClause`) rather than
   nesting the second `match` inline.
2. **The call site.** `postApp` now flattens when the callee is a KNOWN curried def:
   `(app (global ap) 3 <lam>)`. The discriminator is a new pre-pass, `collectCurried`, carried in
   `cx` — not a syntactic guess — because `def mk(n): Int => Int` is called with the same
   `f(a)(b)` syntax and must stay nested. That row is in the gate.

**Three mistakes on the way, each worth more than the fix:**

- **An accessor with one paren too many.** `curriedOf` was written with 20 closing parens for 19
  `snd(`. The front's own source became unbalanced, the compiled F emitted `_err` for EVERY file,
  and a plain `def f(a: Int) = a + 1` stopped lowering — a symptom pointing nowhere near curried
  defs. Found by diffing the file against a reconstruction built edit-by-edit, not by reading it.
- **`args` is a STRING, not a list.** `parseArgs` returns the already-joined text that `emitApp`
  concatenates; calling `joinArgs` on it is a type error this front does not report.
- **A wrong hypothesis, held for two rebuilds.** I blamed the "one `match` per function" limit I
  had recorded earlier, split the function, rebuilt, and it changed nothing — the two-match form
  lowers fine (measured). Bisecting beat guessing: installing the front into
  `bin/lib/native-front/tower/bin/fsub.ssc` makes the loop instant, with no rebuild at all.

**Gate:** `tests/conformance/curried-def-clauses.ssc` (5 rows, four lanes identical) pins the
OUTPUT, and `tests/e2e/v2-front-coverage.sh` pins WHICH FRONT — an output comparison cannot see the
difference, which is exactly why this survived: every output test was green while the fallback
compiled the file.

**The coverage gate's `--self-test` anchored on THIS bug**, so fixing it broke that self-test for
the right reason. Rather than swap in another known gap — this was the last open F entry — the
self-test now asserts that a WRONG expectation is CAUGHT (a file F certainly lowers, declared `GAP`
on purpose). That proves the same thing and does not rot when a gap closes.

Not fixed, and not this entry's: `def tri(a)(b)(c)` dies with `TYPEERR: cannot unify Tuple with
non-Tuple` on the unmodified toolchain too. Filed as `v2-three-parameter-clauses-fail-typecheck`.

## v2-serve-banner-missing — three corpus DIVERGEs, one cause: the native lane prints no server banner
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: 611795277 -->


**Status:** **FIXED 2026-07-28** by `v2-stub-apply-and-serve-banner` (`eb82bd18e`), option (1):
the native serving host prints the same three-line banner. Contract-preserving — the three cases
became PASS and no golden anywhere changed. `diff` of `examples/rozum-agent.ssc` native vs INT is
now byte-identical, and the scoped contract run is 6/6 PASS cells with all three recorded as
IMPROVEMENT over the frozen baseline.

**A first attempt printed only on the BLOCKING path** and left all three DIVERGE. The reasoning
was that "Ctrl+C to stop." cannot be true of an async serve — plausible, and wrong: those examples
call `serveAsync(port)`, and the INT reference prints all three lines and then carries on, because
v1's `serveAsync` runs `WebServer.start` on a background thread which prints before blocking.
Corrected by diffing against INT instead of reasoning about what the banner ought to say.

Option (2) — move the banner to stderr in both lanes — is filed separately as
`v2-serve-banner-belongs-on-stderr`, with the measurement it must make first.

baseline; these three were `FAIL` before `d11fd7a92` and are now `DIVERGE`, i.e. they RUN and only
the output differs).

**Measured** — `rozum-agent`, `rozum-agent-pool`, `rozum-agent-streaming`, INT vs the default lane:

```
INT                                  native
ScalaScript web · http://localhost:19694/  (root: .)     (absent)
  (backend=fast)                                         (absent)
Ctrl+C to stop.                                          (absent)
Done                                 Done
Posted the transaction.              Posted the transaction.
1                                    1
```

The PROGRAM output is byte-identical. The entire divergence is a three-line banner that
`WebServer.start` prints to the interpreter's stdout
(`v1/runtime/backend/interpreter-server/.../WebServer.scala:134-138`); the native lane serves
through a different implementation that prints nothing.

**Two fixes, and they are not equivalent — pick deliberately.**

1. *Make the native lane print the same banner.* Contract-preserving: three DIVERGEs become PASS,
   no golden anywhere changes, no other lane is touched. Low risk, but it propagates developer
   chatter into program stdout on a second lane.
2. *Move the banner to stderr in BOTH lanes.* Arguably the right answer — a server banner is not
   program output, and putting it on stdout is what dragged it into the golden in the first place.
   But it changes v1's observable contract and rewrites the golden of every serving example, so it
   is not a drive-by: it needs its own claim and a full-corpus re-freeze.

Recommendation: (1) now to clear the three cases, (2) filed as the real cleanup. **Do not do (2)
opportunistically** — check first whether the harness compares stderr as well
(`run.sc` builds its comparison with `outputWithFailureContext(out, err, exitCode)`), because if it
does, moving the banner to stderr changes nothing and only churns the goldens.

## v2-native-program-tail-quotes-strings — a program's tail value printed as a debug dump, not as output
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by `v2-program-tail-string-render`. Found 2026-07-28 by
`v2-multiblock-auto-output` while designing the per-block auto-output fix.

**The divergence was WIDER than this entry first said.** It originally recorded "a String prints
with quotes" and proposed fixing the top level only, "since v1 quotes a nested `StrV` and so should
v2". **Measured, that guess was wrong** — v1 leaves nested strings bare here too:

| program tail | native (before) | v1 reference | native (after) |
|---|---|---|---|
| `"HELLO!"` | `"HELLO!"` | `HELLO!` | `HELLO!` |
| `List("a", "b")` | `List("a", "b")` | `List(a, b)` | `List(a, b)` |
| `Some("x")` | `Some("x")` | `Some(x)` | `Some(x)` |
| `Map("k" -> "v")` | `Map("k" -> "v")` | `Map(k -> v)` | `Map(k -> v)` |
| `42` | `42` | `42` | `42` |

**Root cause.** `V2Result.report` ended in `println(ssc.Show.show(other))`. `Show.show` is the
*debug* rendering — correct for a nested value in a diagnostic, wrong for the program's user-facing
output, and it quotes every string at every depth. v1's auto-output path does not.

**Fix.** One line: render through `ssc.Prims.display`, the renderer the kernel's own `println` uses
(`anyStr` — "containers with UNQUOTED nested strings"), so the program tail and an explicit
`println` of the same value now agree, and both agree with v1. An explicit `println` was already
correct on both lanes; only the tail path was wrong.

**Gate.** Four cases in `tests/e2e/v2-error-diagnostic.sh`, each comparing the native tail against
**`ssc-tools run --v1` on the same file** rather than against a hardcoded string, so the
expectation cannot drift away from the reference. A/B'd: against the previous binary all four fail
with expected/actual printed (`expected (v1): HELLO!` / `got (native): "HELLO!"`); against the fix
all four pass.

**Note for the auto-output work.** `v2-native-multiblock-auto-output-missing` routes *fenced*
documents through `println` rather than through `report`, so it hid this for those files; a
fenceless `.ssc` (whose whole body is one block) always went through `report`, which is why this
needed its own fix.

## v2-native-case-unit-pattern-matches-where-int-does-not — `case _: Unit` was true on native, false on INT and JS
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: c3490e939
     gate: tests/conformance/type-ascription-unit.ssc -->

**Fixed 2026-07-29.** `Unit` is an ordinary type and `case _: Unit` holds for the unit value. Both
the interpreter's and the JS backend's type-test tables listed every scalar type *except* this one
and ended in a catch-all `false`, so the pattern fell through to the wildcard arm.

```scalascript
def f(x: Any): Unit =
  x match
    case _: Unit => ()
    case _ => println(x)
f(println("side"))
```

| lane | before | after |
|---|---|---|
| `bin/ssc run` (native, default) | `side` | `side` |
| `ssc-tools run-jvm` | `side` | `side` |
| `ssc-tools run --v1` (INT) | `side` then **`()`** | `side` |
| JS via `emit-js` | `side` then **`()`** | `side` |

**Scope was wider than this entry first said.** It was filed as "an INT gap"; measuring all four
lanes showed **JS had the same hole**. That is the part that mattered: INT and JS are the two lanes
the conformance suite *grades* with — INT is its reference — while native and JVM are the two that
were right. So any case written around `case _: Unit` was being checked against the lanes that got
it wrong, and a native-vs-INT diff would have been read as a native defect.

One arm each: `UnitV => typeName == "Unit"` in `PatternRuntime.scala`, `Unit => v === undefined` in
`JsGenCpsCodegen.scala` (unit is `undefined` in the JS lane and `null` stays distinct, so the test
does not conflate the two).

**A/B.** Against `tests/conformance/type-ascription-unit.ssc`: before, INT and JS differ from
`expected/` on 3 of 6 lines; after, all four lanes print it byte-for-byte. Native and JVM printed it
byte-for-byte both times — which is what establishes `expected/` as the right answer rather than my
reading of it.

## type-ascription-tuple-and-set-arms-missing — `case _: Set` cannot be answered on two lanes: Set is not a type there
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: tests/conformance/set-distinct.ssc
     fixed-in: 2c0396a65 -->

**Tuple half FIXED 2026-07-29 in 7939f4c9e** (gate `tests/conformance/type-ascription-tuple.ssc`).
**Set half FIXED on `int` 2026-07-30 in 84c3b2cc7** — and NOT fixable the same way on two lanes,
because this entry's premise was wrong.

Measured across all four lanes and all three containers, rather than on the Set-only probe this
entry was filed from:

| container | `jvm` (oracle) | `int` | native / `v2` | `js` |
|---|---|---|---|---|
| `Set(1,2)` | `set` | `other` → **`set`** | `other` | `other` |
| `Map("a"->1)` | `map` | `map` | `map` | **`other`** |
| `List(1,2)` | `list` | `list` | **`other`** | **`other`** |

It called this a *uniform* gap where the self-hosted lanes agree. **They do not**, and the reason two
of them answer `other` is not a missing arm:

- on the native lane **`Set(1, 2)` prints `List(1, 2)`** — a Set *is* a List there;
- on the JS lane `List(...)` is `[...args]`, and `_setOf(...)` returns a plain array too.

So on two of four lanes `Set` is not a distinct runtime value and there is nothing to test for.
An arm there would assert that a List is a Set — a wrong answer replacing an honest one. This is a
**representation gap, not a table gap**: different size of work, different owner.

Three separable pieces remained. **Re-measured 2026-08-02 by `type-ascription-table-gaps`: two are
CLOSED, and the third was misclassified — there is no table work left in this entry.**

1. ~~`js` answers `other` for `Map`~~ — **fixed**, js answers `map`.
2. ~~native answers `other` for `List`~~ — **fixed**, native answers `list`.
3. **`Set` on native and `js` needs a distinct representation** — unchanged, and it now subsumes
   what was going to be a fourth piece.

| | `jvm` | `int` | native / `v2` | `js` |
|---|---|---|---|---|
| `Map("a"->1)` | `map` | `map` | `map` | `map` — was `other` |
| `List(1,2)` | `list` | `list` | `list` — was `other` | `other` |
| `Set(1,2)` | `set` | `set` | `other` | `other` |

**The remaining `js` `List` row is NOT a table gap, and filing it as one would have produced a wrong
answer.** Measured directly rather than inferred:

```
                                    int          js          v2
println(List(1,2))                  List(1, 2)   List(1, 2)  List(1, 2)
println(Set(1,2))                   Set(1, 2)    List(1, 2)  List(1, 2)
List(1,2).toString == Set(...)      false        TRUE        TRUE
```

Compared as STRINGS, and that is not incidental: `List(1,2) == Set(1,2)` is a **compile error** in
Scala 3 — *"Values of types List[Int] and Set[Int] cannot be compared with == or !="* — so the jvm
lane refuses to build it. This entry's first draft was rejected for exactly the same reason with
`case _: Tuple2`; the lane that runs real Scala keeps catching this file out, which is the argument
for keeping jvm in its backends.

On js and v2 a Set **is** a List — the same value, equal by `==`. So an arm keyed on "is an array"
would answer `list` for a Set, which is the mirror of the mistake this entry already refused to make
for `Set`. `other` stays the honest answer on both lanes until Set has its own representation, and
that is now the ONLY thing this entry is waiting on.

`type-ascription-set.ssc` gained `println(List(1, 2).toString == Set(1, 2).toString)` so the property is pinned as a
value test rather than as prose — it is what will prove a future Set representation before the
`case _:` arms are trusted.

**Original report (superseded 2026-07-30), Set half:** Found by the probe written for
[[v2-native-case-unit-pattern-matches-where-int-does-not]]; same two type-test tables, same missing
arm.

```scalascript
def kind(x: Any): String = x match
  case _: Tuple2[?, ?] => "pair"
  case _: Set[?] => "set"
  case _ => "other"
```

| | native | JVM (real Scala) | INT | JS |
|---|---|---|---|---|
| `case _: Tuple2[?, ?]` on `(1, 2)` | yes | yes | **no → yes** | **no → yes** |
| `case _: Tuple2[?, ?]` on `(1, 2, 3)` | no | no | no | no |
| `case _: Set[?]` on `Set(1, 2)` | **no** | yes | **no** | **no** |

**Correction to this entry's first version.** It said "Scala says yes" for bare `case _: Tuple2`.
It does not: that spelling is a *compile error* in Scala 3 — `Missing type parameter for
[T1, T2] =>> (T1, T2)` — and the JVM lane refused to build the first draft of the gate. The claim
holds for `Tuple2[?, ?]`, which is what the corrected table and the gate now use. Worth keeping the
mistake visible: the JVM lane is the only lane that runs real Scala, so it is the oracle for "what
should this do", and consulting it is cheap.

**Why the Set half is still open, rather than being one more line in the same commit.** Native gets
`case _: Set` wrong too. Fixing only INT and JS would *create* a lane divergence where all three
currently at least agree with each other — trading a uniform gap for a differential one, which is
strictly worse for a suite that grades by comparing lanes. The native table lives in `v2/src`
(`Runtime.scala`'s `__isTag__` primitive arm), held by another claim at the time. The fix is one arm
per lane: `Value.SetV => typeName == "Set" || typeName == "Iterable"`, the JS `_type`/marker
equivalent, and the `SetV` case in the native primitive table — all three together or none.

**The Set half is invisible to every differential gate we own**, which is the part worth
remembering: a cross-lane sweep compares lanes to *each other*, so a gap all three share reads as
green. It surfaced only because the probe asked what the JVM lane does instead of what another
self-hosted lane does.

---

**CLOSED 2026-08-04 — Set has a representation of its own on both remaining lanes.**
`Value.SetV` (an insertion-ordered `LinkedHashSet`) on v2, a `_kind: 'Set'` tagged array on js.
`case _: Set` and `case _: List` are both answerable now, on all four lanes, without either arm
lying about the other.

**The entry understated the defect, and measuring first is what showed it.** This was filed and
carried for a week as a *display* problem — "a Set prints as a List". Re-measured on 2026-08-04
before any code was written, it was a wrong VALUE:

| | `int` / `jvm` | `v2` before | `js` before |
|---|---|---|---|
| `Set(1, 1, 2)` | `Set(1, 2)` | `List(1, 1, 2)` — **duplicate kept** | `List(1, 2)` |
| `Set(1,2) == Set(2,1)` | `true` | `false` | `false` |
| `.union` / `.intersect` | `Set(1, 2, 3)` / `Set(2)` | **`Stub`** at exit 0 | **crash**: `Method not found: union` |
| `Set(1,2) + 3` | `Set(1, 2, 3)` | `Set(1, 2)3` (string) | `1,23` (string) |
| `Set(1,2) - 1` | `Set(2)` | `()` | `NaN` |

So a Set silently kept duplicates and compared by order, and half its operations either vanished
into the `Stub` sentinel or killed the program. None of that is visible in the type-test question
this entry was filed about. **A count I got wrong in the other direction, too**: I argued before
starting that the work served one corpus file (`dataset-shape.ssc` is the only non-test user of
`Set`) and was therefore poor value. That was the wrong unit — the defect is in a language type, and
the count of *today's* callers says nothing about a Set that does not deduplicate.

**Where the fix lives.**
- v2 front (`specs/v2.2-p6.5-fsub.ssc`): `Set(…)` had been lowered by the SAME `parseListLit` as
  `List(…)`, so the two were byte-identical in the IR. Now `parseSetLit` → `(prim set.of …)`.
- v2 runtime: `SetV` + the `set.of` prim, `show`/`anyStr`, the `__isTag__` arm, a Set method block,
  and `+`/`-`/`++` in `arithOp` (infix operators reach arithmetic, not the method dispatcher).
- The LEGACY front needed no edit: it already lowered `Set(a, b)` to `[a, b].toSet`, and pointing
  `toSet` at `SetV` made that lowering correct for free. Its comments said "v2 sets are DISTINCT
  lists" and were rewritten so they do not mislead the next reader.
- js: `_setOf` tags via the EXISTING `_seqKind` marker (the one `Vector` uses), so every array
  method keeps working; `_eq` compares sets by membership; `_dispatch` gained only the methods where
  a Set differs; `_arith` and `_tupleConcat` handle the infix forms, which never reach `_dispatch`.

**Gate:** `tests/conformance/set-distinct.ssc`, 22 rows on all four lanes. Verified to FAIL without
the fix by running it against the unfixed shared-main toolchain: **14 of 22 rows wrong on v2, and js
died before printing a single line**. `type-ascription-set.ssc` — this entry's own case — now also
runs on `js` and `v2`, and its `List(1,2).toString == Set(1,2).toString` row went `true` → `false`
on both, which is the row it was written to prove.

**Three defects found while measuring, filed separately rather than bundled** — each is a different
lane and a different cause: `int-set-apply-is-not-membership`,
`int-set-element-order-differs-from-scala`,
`v2-set-ops-and-or-coerce-to-int-and-double-minus-is-a-silent-no-op`.

## lint-markdown-unreachable-from-markdown-commits — the only job that lints `.md` cannot be triggered by a `.md` change
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: build
     fixed-in: 680181feb
     gate: tests/e2e/ci-status-guard.sh -->

**Status:** FIXED 2026-07-28 in `680181feb` — a second workflow,
`.github/workflows/lint-markdown.yml` (job `Lint Markdown (docs-only)`), triggered on
`paths: ['**.md']`. `ci.yml` and its `paths-ignore` are untouched.

**Why a second workflow and not a move.** Moving the job out of `ci.yml` would give exactly one
lint run per push instead of two on a mixed commit — but `scripts/ci-status` hard-codes a
`required_jobs` list containing `"Lint Markdown"` and reports a run that omits a required job as
RED, and `tests/e2e/ci-status-guard.sh` pins that. The move would therefore have turned every
future green `ci.yml` run into `missing required job: Lint Markdown`. Accepted consequence: a
commit touching both code and `.md` lints twice — same command, same whole-repo scope, so the two
cannot return contradictory verdicts.

**The defect.** `ci.yml` now carries, for both `push` and `pull_request`:

```yaml
paths-ignore:
  - '**.md'
  - '.work/**'
```

GitHub skips the run when EVERY changed path matches. A **markdown-only commit therefore produces no
run at all** — including no `Lint Markdown`. The job that exists to check `.md` is the one job a
`.md` change can never reach.

**Consequence, and it is not theoretical — it happened today.** `64a8a3339` was a docs-only commit
(`docs: NIG-0/NIG-1/NIG-3 done in SPRINT + CHANGELOG`). It introduced two literal hard tabs into
`SPRINT.md:137` and got no run. The breakage first surfaced hours later on `9f136e21f` — an
unrelated conformance commit — because `markdownlint '**/*.md'` lints the whole repository at
whatever SHA happens to trigger it:

```text
SPRINT.md:137:45 error MD010/no-hard-tabs Hard tabs [Column: 45]
SPRINT.md:137:48 error MD010/no-hard-tabs Hard tabs [Column: 48]
```

So the failure is attributed to a commit that did not cause it and does not contain the offending
path, and the commit that did cause it is reported green (in fact, reported nothing). The fix commit
has the mirror-image problem: `41541482d` touches only `SPRINT.md`, so **the repair is equally
unverifiable by CI** — it had to be checked by hand with the CI command
(`markdownlint '**/*.md' --ignore node_modules`, exit 0) and will only be confirmed by the next
unrelated code push.

**Why the paths-ignore itself is still right.** Its measurement stands: 43 of 58 non-`[skip ci]`
commits in one hour changed only `.md`/`.work/`, against an `sbt` job of 3h16m. The problem is not
the filter, it is that one job's *input* is exactly the filter's exclusion set.

**Fix direction.** Give `Lint Markdown` its own trigger rather than exempting it from the filter —
a second workflow (or a `push` entry with `paths: ['**.md']`) that runs *only* markdownlint. It is
seconds of runner time, so it does not reintroduce the queue pressure the filter was added to
relieve, and it restores the property that broke here: **the commit that breaks a check is the
commit the check reports on.**

## uniml-yaml-corpus-6ck3-percent-oracle-conflict — pinned event contradicts YAML 1.2.2 tag preservation
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: front
     gate: sbt unimlYaml/testOnly *YamlOfficialCorpusSpec*
     fixed-in: 024d80524 -->

**Status:** OPEN (found 2026-07-28 during independent UPR-2a.1 review; upstream
`yaml/yaml-test-suite#9` remains open).

**Reproduction.** YAML 1.2.2 section 5.6 requires percent-escaped tag characters to
be preserved and compared exactly as presented. The same specification's Example
6.26, copied into pinned case `6CK3`, expects `!e!tag%21` as parser event
`<tag:example.com,2000:app/tag!>`. The public representation and the official event
oracle therefore cannot share one tag string without violating one of the two
contracts.

**Measurement defect found after landing.** `3341a35a9` preserved the public
representation but called `parserEventTag` on the actual event before comparison.
That makes `%21` equal to `!`, collapses two normatively distinct tags, and falsely
counts 6CK3 as a semantic/strict pass. This violates the project's compare-first
measurement rule even though the vendored expectation itself was not changed.

**Fix acceptance.** Keep exact shorthand in CST and `%HH`-preserving handle-expanded
tags in `YamlValue`. Compare that normative actual event to the unchanged vendored
event first. Only afterward classify the exact 6CK3 mismatch as
`oracle-discrepancy yaml/yaml-test-suite#9`. Any decoded test-suite compatibility
projection must be test-only, separately named, and unable to contribute to the
existing semantics/strict counts. Fail-first coverage must distinguish `%21` from
`!`; a full 402-case candidate diff must prove 6CK3 is the only changed row.
Expected corrected census is semantics `137`, strict `125`, failures `277`, with
validity `214`, actual errors `216`, source/chunks `402/402`, and zero crashes.

⚠ **THAT CENSUS IS STALE — measured 2026-08-08 against the gate's own assertions**, which are the
authority because they are what goes red. Every count except source/chunks has moved, and all of
them upward, i.e. the corpus got BETTER after this entry was written:

| | this entry | `YamlOfficialCorpusSpec` today |
|---|---|---|
| semantics | 137 | **138** |
| strict | 125 | **126** |
| validity | 214 | **216** |
| actual errors | 216 | **218** |
| failures | 277 | **276** |
| source / chunks | 402 / 402 | 402 / 402 |
| crashes | 0 | 0 |

The numbers above are left as written rather than rewritten: they were the expectation attached to
the measurement repair, and a reader checking that repair against history needs them. What they are
NOT is a description of today, which is what a bare number in an open entry reads as. The gate is
the thing to re-read — `report.census` is asserted field by field there.
The tracker remains OPEN after the local measurement repair because the normative
text/example conflict and upstream issue remain unresolved.

**Local measurement repair landed.** `024d80524` removes pre-comparison decoding
from production, freezes the literal actual event, and adds a display-only
post-compare classifier guarded by the exact 6CK3 mismatch. The 402-row diff
changes only 6CK3; the corrected baseline/category SHA-256 values are
`563ec95401acfb8fab062b11408b5be8e5397a61c5d54676fff04865170fff95` and
`cc0d52c8f34207900a95afe6725d5f9a9265c1cb6ce10f6d82feb9bf21288c78`.

**VERIFIED FIXED 2026-08-09 — and it was fixed the same DAY this entry was written.**
`024d80524`, *"fix(uniml-yaml): compare percent tags before corpus classification"*, is an ancestor
of `origin/main`. The measurement defect this entry reports — calling a normalising function on the
actual event before comparison, so `%21` equalled `!` — is gone, and the acceptance is pinned by a
test rather than by a number:

```
test("6CK3 compares the normative percent-preserving tag before oracle classification")
  expectedTag  tag:example.com,2000:app/tag!      (the vendored oracle, unchanged)
  actualTag    tag:example.com,2000:app/tag%21    (normative, percent preserved)
  assert(!outcome.semanticsExact); assert(!outcome.strictExact)
  classification = oracle-discrepancy yaml/yaml-test-suite#9 (%21 decoded to ! in pinned test.event)
```

It also asserts the classification does NOT appear when the source differs, and not when a
deliberate extra event is spliced in — so the discrepancy label cannot be handed out to a genuine
failure. `sbt unimlYaml/testOnly *YamlOfficialCorpusSpec*`: **20 tests, 20 pass.**

**THE CENSUS IN THIS ENTRY LOOKS WRONG AND IS NOT, WHICH IS WHY THE NUMBER ALONE COULD NOT CLOSE
IT.** The entry predicts semantics **137**, strict **125**; the pinned baseline today reads **138**
and **126**. Both are correct, three weeks apart: `git log -S` on the baseline shows `024d80524`
landing exactly the predicted 137/125, and the LATER feature `74c722c13` (*"UPR-2a.2 —
parser-context tag/anchor property syntax"*) raising each by one on its own merits. A frozen expected
count is evidence with a shelf life; the test that names the property is not.


## uniml-yaml-projection-reorders-invalid-cst — semantic projection sorts tokens instead of validating source order
<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: uniml/yaml/src/test/scala/scalascript/uniml/dialect/yaml/YamlProjectionCstSpec.scala
     fixed-in: 44bf646c2 -->

**FIXED 2026-08-08 in `44bf646c2`.** `.sortBy(_.id)` is gone; the traversal order IS the source order
and is VALIDATED. `validateCst` runs before the tree is flattened and refuses with
`uniml.yaml.projection-invalid-cst` on any of: two source identities, duplicate ids, traversal order
disagreeing with ids, or spans that overlap or run backwards.

**Each invariant is separate on purpose, and the spec shows why.** Ids ascending does not imply spans
do — the overlapping-span case has ASCENDING ids, so only the span check catches it. One combined
predicate would have let it through.

**What is NOT checked, stated rather than implied:** this entry's "source slices" criterion. The
original text is not available to the projection, so a token's lexeme cannot be compared against a
slice of it. Span contiguity is the structural stand-in — a gap or an overlap means the tree is not a
partition of the source, which is the condition that made the old `sortBy` reachable at all.

**Falsifiability shown, and my first attempt at it was worthless.** Planting `.sortBy(_.id)` back did
not COMPILE — `validateCst` became unused and `-Werror` rejected it — so that run proved nothing.
Planted inside the validator instead: exactly the four refusal tests fail and both controls stay
green, the well-formed tree still projecting and an empty CST still not called invalid.

`JsonProjection` already refused rather than repaired, so this was the second decision site of one
rule and the wrong one. uniml green: 15 projects, yaml 61 tests including the official corpus gate.

**Status:** OPEN (found 2026-07-28 during UPR-2 architecture audit).

**Reproduction.** `YamlProjection.project` flattens every CST root and calls
`sortBy(_.id)` before rebuilding the source string. A caller-provided or composed `ParseResult`
whose traversal order and token ids disagree is therefore silently reordered and reparsed instead
of returning `uniml.yaml.projection-invalid-cst`.

**Impact.** Projection can manufacture a semantic success from an invalid CST. The result hides the
original tree/order defect, and anchor/directive meaning can change because YAML is source ordered.

**Fix acceptance.** Add a fail-first projection test with disagreeing traversal/id order. Validate
one source identity, unique ids, monotone spans, source slices, and traversal/source order; reject
the invalid tree without sorting or reparsing it.

## scljet-sql-double-equals-parser-gap — WHERE rejects SQLite's `==` equality alias
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: 94873f54d -->

**Re-verified 2026-08-02 at `1305736e1`**, both forms against the same table:
`WHERE salary = 250` and `WHERE salary == 250` each return the same row, neither errors.
`94873f54d` resolves and is an ancestor of `origin/main`.

**Status: FIXED 2026-07-28** in `94873f54d` (`scljet-sql-double-equals`). Normalized in the TOKENIZER.

**It was a LEXER gap, not a parser one — and that changes where the fix belongs.** `scljet/sql.ssc`
emitted one token per `=` CHARACTER (`c == 61`), so `==` became TWO `=` tokens and the parser saw
`v = = 2` -> `expected an expression operand`. The two places that already knew the alias —
`isCompareOp` (:869) accepting the string `"=="`, and `compareValue` (:1376) normalizing it — were
therefore UNREACHABLE. That is exactly the "internally inconsistent accepted operator set" the
original report named: the handling existed, the token never did.

**Fix.** `=` immediately followed by `=` consumes both characters and emits a single `=` token.
Normalizing at the tokenizer is what gets the alias into every downstream path at once — scalar,
WHERE/HAVING/ON, correlated scalar and index range. Emitting a `"=="` token instead would have
fixed the scan path and silently left the INDEXED one behind, because `isSargOp` (:2401) and the
index-range mapper (:4134) accept only `"="`. Only ADJACENT characters merge, so the fail-closed
cases hold: `a = = b` with a space is still an error, and so are `!==` and `<==`.

**Fail-first, measured** by reverting only `scljet/sql.ssc` and rebuilding: exactly the six `==`
probes fail with the reported error, and every `=` / `<>` / `!=` / spaced line is byte-identical
before and after — the case isolates the alias and nothing else.

**Regression** `tests/conformance/scljet-sql-double-equals.ssc` runs each query TWICE, once per
spelling, on a scanned table AND on an indexed one, and pins the spaced form as an error. INT + JS
green; rostered with the baseline digest reproduced before the new `roster-sha256` was written.

### Original report (kept for context)

**Status:** OPEN (found 2026-07-28 by `scljet-production-completion`;
reproduced on assembled `bin/lib/ssc.jar` from `b63206552` through
`bin/ssc-tools run --v1`).

**Real-harness reproduction.** `SELECT id FROM t WHERE v == 2 ORDER BY id`
returns `QUERY-ERROR:expected an expression operand`; reference SQLite 3.51.0
returns id `2`. Scalar-expression comparison already normalizes `==` to `=`,
so the accepted operator set is internally inconsistent.

**Root cause.** WHERE condition tokenization/parsing does not consume `==`,
while `compareValue` contains a local alias normalization. SC-8 must accept and
normalize the alias consistently in scalar, WHERE/HAVING/ON, correlated scalar,
and index-range paths, with complete-token fail-closed coverage.

## corpus-contract-delta-false-improvements — unobserved lanes and status changes look improved
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: conformance
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex review on `e124cc20f`.

**Reproduce.** In the pure classifier, freeze `x<TAB>js<TAB>FAIL` and observe
`x<TAB>js<TAB>DIVERGE`: row-level set subtraction reports both CHANGE and
IMPROVEMENT, although the cell never passed. A
`KNOWN-RED → FAIL` transition additionally prints that the declaration
"expired" because the lane now passes. Separately, if `backends:` stops
selecting a frozen red lane, requested-lane scoping treats the unobserved row
as an improvement.

**Root cause.** The delta compares whole `(case,lane,status)` strings instead
of mapping statuses by `(case,lane)`, and scopes the baseline by requested
lanes rather than the cells that actually ran.

**Fix / done-when.** Compare by cell key: a changed status is only CHANGE, and
IMPROVEMENT requires an observed frozen-red cell whose current row is absent
(therefore PASS/runnable). Backend-excluded cells stay out of scope. Synthetic
tests cover `FAIL → DIVERGE`, `KNOWN-RED → FAIL`, an unobserved lane, and a
real improvement.

## ci-status-blind-to-non-ci-workflows — the tool agents trust for a verdict could not see 4 of 5 workflows
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: front
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`ci-status-all-workflows`). Found while looking for work
after the bug list ran dry — this was not on the board, and that is the point: the check that would
have surfaced it did not exist.

**Symptom.** `scripts/ci-status` hardcoded `--workflow ci.yml --branch main --event push`. The repo
has five workflows. Four of them — `corpus-contract.yml`, `f4-front-swap.yml`, `native-release.yml`,
`pages.yml` — could not be queried by it at all, and the two that matter most run on a **schedule**,
which the `--event push` filter excludes by construction. So no automated check anyone ran ever
looked at them.

**Measured, which is what makes it a bug rather than a tidiness complaint:**

| workflow | last 12 runs |
|---|---|
| `corpus-contract.yml` | **0 success** — 4 `failure`, 7 `cancelled`, 1 running; every one `schedule` or `workflow_dispatch` |
| `f4-front-swap.yml` | 7 success, 1 failure |
| `native-release.yml` | **never run** (tag-triggered, `v*.*.*`) |
| `pages.yml` | 12 success |

A nightly that has never once been green, and a release workflow that has never executed, were both
invisible to every tool in the repo. This is the apparatus-lies-green shape again: not a wrong
answer, an unaskable question.

**Fix.** `--workflow`, `--event` (`any` disables the filter), `--branch`, `--latest` (a scheduled run
fires against whatever `main` was at the time, so an exact-SHA query almost never matches one), and
`--all-workflows` — one line per workflow file from its most recent run of any trigger, which is the
blind-spot sweep. Verdicts for a non-`ci.yml` workflow come from **its own jobs**: `ci.yml`'s four
required job names exist nowhere else, and inventing a required list for a workflow the script does
not know would be a guess.

**No-argument behaviour is byte-identical** — AGENTS.md §4c and the agents depend on it, and
`tests/e2e/ci-status-guard.sh` asserts the exact filter set it sends. That existing suite passes
unchanged.

**A/B'd, not just run.** Against the PREVIOUS `ci-status` the new cases fail
(`ci-status: unknown argument: --workflow`, `wf-green: expected exit=0 got=2`); against the fix the
whole suite passes. The new cases assert GREEN **and** RED for the same non-ci workflow, because a
one-sided check could not distinguish "judged its own jobs" from "recognised no job and defaulted to
pass", and the `--all-workflows` case asserts that a cancelled nightly makes the sweep exit 1 while a
never-run workflow is reported without being counted as a failure.

**Live output after the fix** (`scripts/ci-status --all-workflows`) shows `native-release.yml
NEVER-RUN` and each workflow's real verdict on one line.

**Not fixed here:** `corpus-contract.yml` being red is a real defect owned by the live
`corpus-contract-*` claims. This entry only makes it impossible to keep missing.

## scljet-ipk-update-numeric-affinity — INSERT auto-assigns invalid IPKs and UPDATE refuses valid affinity
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: a00db1967 -->

**Status:** FIXED in `a00db1967` (opened 2026-07-27 by
`scljet-ipk-rowid`, expanded the same day by
`scljet-production-completion` after assembled INSERT/UPDATE differential).
The fail-first affinity matrix is green on INT+JS; the independently reviewed
live sqlite-jdbc matrix, reference row reopen, and integrity check closed SC-1a
in `b39127f61`.

**Symptom** (reference sqlite3 3.51.0 vs scljet, same statements on `emp(id INTEGER PRIMARY KEY, …)`):

```
UPDATE emp SET id = 5     → both move the row                                    ✓
UPDATE emp SET id = 7.5   → both: datatype mismatch                              ✓
UPDATE emp SET id = NULL  → both: datatype mismatch                              ✓
UPDATE emp SET id = 'x'   → both: datatype mismatch                              ✓
UPDATE emp SET id = '5'   → sqlite: row moves to 5   scljet: datatype mismatch   ✗
UPDATE emp SET id = 7.0   → sqlite: row moves to 7   scljet: datatype mismatch   ✗
INSERT INTO emp VALUES ('5', ...)  → sqlite: rowid 5  scljet: next auto rowid    ✗
INSERT INTO emp VALUES (7.0, ...)  → sqlite: rowid 7  scljet: next auto rowid    ✗
INSERT INTO emp VALUES ('x', ...)  → sqlite: mismatch scljet: next auto rowid    ✗
INSERT INTO emp VALUES (7.5, ...)  → sqlite: mismatch scljet: next auto rowid    ✗
```

Measured directly: `sqlite3 c.db "UPDATE emp SET id='5' WHERE id=1; SELECT rowid,id,name FROM emp"`
→ `5|5|ann`, and the same with `7.0` → `7|7|ann`.

**Root cause.** `targetRowidOf` accepts only `SqlInteger`, while
`valueIntOrNone` feeds `assignInsertRowids` and maps every non-integer value to
the same `None` as SQL NULL. SQLite applies its rowid numeric conversion first:
valid numeric TEXT/REAL becomes an explicit integer, only actual NULL requests
automatic allocation, and every other value is `datatype mismatch`.

**Why it was left.** A faithful fix needs SQLite's exact text→integer affinity rules (leading and
trailing space, sign, overflow, prefix forms like `'5abc'`), and `sql.ssc` has no such helper — the
nearest one, `parseLongStr`, lives in `jdbc.ssc`, the wrong direction for the engine to depend on.
The integral-REAL half is a two-line fix and could land on its own. A loud rejection is strictly
better than the silent no-op that preceded it, so this is a completeness gap, not a correctness
risk.

**Fix sketch.** Add one target-neutral coercion returning
`Either[String, Option[Long]]`, use it from both INSERT and UPDATE, and make
`assignInsertRowids` return `Either` so the whole row batch is validated before
pager mutation. Extend `scljet-update-ipk-moves-rowid` and add bound-value
INSERT/UPDATE plus live sqlite-jdbc differential vectors for exact integer
TEXT, decimal/exponent TEXT, integral REAL, rounding boundaries, invalid values,
collisions, and indexed paths.

## scljet-ipk-move-indexed-corrupts-btree — an IPK move on an INDEXED table wrote an out-of-order b-tree
<!-- status: fixed
     kind: bug
     lane: multi
     area: build
     fixed-in: b65bbb637 -->

**Status:** FIXED (2026-07-27, `scljet-ipk-rowid`, commit `9cb9865e1`). Found the same day by
`scljet-ipk-rowid` while cross-checking the freshly landed IPK-move fix (`4a20a50b7`) against the
reference engine — i.e. it was live on `origin/main` for a few hours, not a long-standing defect.

**Symptom** (measured on `origin/main` `c85e484d7`; table with any index, `UPDATE emp SET id = 5
WHERE id = 1`):

```
scljet reads:  table rowids are not strictly increasing     <- our own reader refuses the table
sqlite3:       *** in database main ***
               Tree 2 page 2 cell 0: Rowid 5 out of order   <- reference: the FILE is corrupt
```

A rowid collision on the same path was additionally accepted in silence.

**Root cause.** `executeUpdate` has two branches. Unindexed deletes and reinserts through the
b-tree, so `leafInsertCell` places each cell by key and rejects duplicates. Indexed calls
`reindexTable`, which rebuilds table + indexes by writing the row list **in list order** and never
reaches `leafInsertCell`. The move fix gave the row a new KEY without changing its POSITION, and
`ipkMoveConflict` was wired only into the unindexed branch — so that path lost both guarantees.

**Fix.** The indexed branch now runs the same collision policy (`buildUpdateEdits` +
`ipkMoveConflict`, called to check only, so both paths refuse with identical wording) and then
`sortRowsByRowid` before the rebuild.

**Why it escaped a genuinely thorough verification** — worth keeping, this is the reusable part:

1. *The conformance case is a self-consistent oracle by design*, and its own header says so. But
   the failure here is not a wrong VALUE, it is a malformed b-tree — the file stays partly
   readable, so a self-consistent check can pass while the artifact is corrupt.
2. *The cross-engine differential existed and was thorough — but every case used an UNINDEXED
   table.* The bug lives on the other branch. Coverage of the right KIND (through a file, judged
   by the reference) is not coverage of the right PATH.
3. *The first gate written for it was itself fake.* It moved rowid 1 → 5 with rows at 1 and 7:
   `[5, 7]` is still ascending, so it passed with the ordering fix reverted. **An ordering bug is
   only observable when the fixture forces a reorder** — the test now moves 1 → 9, past the
   surviving row at 7. Both new gates were then confirmed to FAIL against `origin/main`'s exact
   code (2 failed / 7 passed) and pass with the fix.

**Gates:** `ScljetIpkRowidDifferentialTest` (indexed move + indexed collision, `PRAGMA
integrity_check` through a real file — it validates cell ordering AND cross-checks the index
against the table) and `tests/conformance/scljet-update-ipk-moves-rowid.ssc` (same two cases, int
+ JS).

## ci-vthread-carrier-starvation-hang — `Test via sbt` hangs to its 200-min timeout under CI load
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED 2026-07-22 by opus (build.sbt `-Djdk.virtualThreadScheduler.parallelism=16`). A major
contributor to the long-running ci-red-main saga: the `sbt — compile and test` job intermittently HANGS
(no output for hours → GitHub's 200-min action timeout), most often observed right after
`GeneratorNativePluginTest`.

**Root cause (measured, not guessed).** The generator/coroutine native plugin
(`v2/runtime/std/generator-plugin/.../GeneratorNativePlugin.scala`) hands values between a `Thread.ofVirtual()`
producer and the consumer via **UNBOUNDED `SynchronousQueue.put/take`** (no timeout). On **JDK 21** a
virtual thread that blocks inside a `synchronized` block **PINS its carrier** (fixed only in JDK 24,
JEP 491). Forked test JVMs run up to 4 suites in parallel (`Tags.limit(Tags.Test, 4)`) and a 2-core CI
runner has only **2 default virtual-thread carriers** (= availableProcessors). A few pinned virtual
threads exhaust the carriers, the generator/coroutine producer can never be scheduled, and the consumer's
`take()` blocks **forever** → the whole job hangs. The suite passes in **749 ms** standalone — it is
purely a carrier-starvation-under-contention hang.

**Deterministic repro (`/tmp/VThreadRepro.scala`, scala-cli, JDK 21):** pin 4 carriers with
`synchronized`+sleep virtual threads, then a `SynchronousQueue` handshake — `parallelism=2` →
`STARVED-HUNG` (poll times out); `parallelism=16` → `OK-42`.

**Fix.** Give the forked test JVM ample carriers so a handful of pinned VTs cannot exhaust them:
`ThisBuild / Test / javaOptions += "-Djdk.virtualThreadScheduler.parallelism=16"`. Test-only (no
production-runtime change), systemic (helps every virtual-thread-heavy suite: generators, coroutines, WS),
low-risk. Verified: option reaches the forked JVM (`show Test/javaOptions`), generator suite 9/9 green.
Follow-ups (BACKLOG-worthy): the plugin's unbounded `SynchronousQueue` handshakes are still a latent
liveness hazard if carriers are ever exhausted — a bounded `poll`/`offer` with a loud diagnostic would
convert a future hang into a re-runnable failure; and JDK 24 removes `synchronized` pinning entirely.

## frontend-tui-fetch-refresh-static-after-bootstrap — refresh ticks redraw stale fetched content
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: 6c6fcf21b -->

**Status:** FIXED 2026-07-20 by `frontend-tui-fetch-refresh` (`6c6fcf21b`). Found while
implementing rozum `ucc-poc-msglist`.

**Symptom/reproduce:** emit a terminal app containing a `FetchUrlSignal("messages", url,
tick.id)`, a `DataTable.Remote` bound to it, and a button whose handler is
`IncrementSignal(tick)`. The emitted crate performs one GET before its first frame. Activating
the button increments `tick` and redraws, but the table still contains the bootstrap response;
the HTTP server receives no second request. The React backend already treats the same tick as a
managed-fetch dependency.

**Root cause:** `frontend/tui/TuiEmitter.collectFetches` reduces every fetch binding to
`id -> url`, discarding `FetchUrlSignal.tickId`. Generated Rust has only `bootstrap(signals)`,
called once before the event loop; `handle_key` mutates signals but no runtime code compares the
refresh tick or re-runs the GET.

**Fix / done-when:** retain `(id, url, tickId)` in emitter metadata, snapshot observed ticks after
bootstrap, and re-fetch a binding before the next frame when (and only when) its tick changes.
Transport failure must preserve the last-good body. A local-HTTP cargo regression must prove an
initial JSON table row changes after the refresh action, while a non-fetch app still emits neither
`ureq` nor fetch state. Contract: `specs/frontend-tui-fetch-refresh.md`.

**Resolution:** `TuiEmitter` now retains `FetchInfo(url, tickId)`, captures each binding's
post-bootstrap tick, and runs `refresh_fetches` before every interactive frame. Only changed ticks
issue a GET; failed GET/body reads leave the destination signal's last-good value intact. The
generated-runtime regression performs bootstrap, a successful refresh, unchanged-tick no-ops, and
a failed refresh against a local HTTP server; it observes exactly three requests and preserves the
successful second body. `frontendTui/test` passes 36/36, including all emitted-Cargo smokes.

## scljet-update-ipk-column-silently-ignored — `UPDATE t SET <ipk> = …` does nothing, and reports success
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`v2-f5b-typed-locals` batch C), together with its sibling
`scljet-update-ipk-does-not-move-rowid` — they are one defect seen from two angles, so they were
fixed as one change. See that entry for the implementation and the measured A/B.

The part specific to THIS report was the second half of the root cause: `WHERE id = 1` matched
nothing, because `executeUpdate` filtered the RAW records while the read path materialises the rowid
into the IPK column. `executeUpdate` now applies `ipkNormalizeRows` **before** `whereHolds` runs —
the same rule `finishRows` already follows on the read side, and the reason the reporter's exact
statement looked like a silent no-op rather than a failed match.

**Historical report:** OPEN (found 2026-07-17 by `scljet-address-write` while probing the write path before
building on it). **Engine — the `scljet-m3-writes` lane.** Silent wrong behaviour: no error, no
change, `Right(image)`.

**Symptom/reproduce** — an `INTEGER PRIMARY KEY` column IS the rowid, so assigning it must RELOCATE
the row. The reference does; we ignore the assignment and say nothing:

```sql
CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO emp VALUES (1,'ann');
UPDATE emp SET id = 5 WHERE id = 1;
SELECT id, name, rowid FROM emp;
--   real sqlite3 3.51.0 → 5|ann|5     (the row moved: rowid is now 5)
--   scljet             → 1|ann|1     (assignment dropped, executeUpdate returned Right)
```

**Root cause (hypothesis).** `executeUpdate` builds the new record from the assignments and rewrites
the row **at its existing rowid**; the rowid is never recomputed from an assignment to the IPK
column. Since `finishRows`/`ipkNormalizeRows` now materialise the rowid INTO the IPK column on read
(`14f4da4ac`), an assignment to that column is written into a field the reader then overwrites — so
even if the record were updated, the read would still show the old rowid. The fix has to move the
row (delete + reinsert at the new rowid, or the equivalent), not just edit the field.

**Adjacent, same probe (correct, recorded so it is not "fixed" by mistake):** `UPDATE … WHERE
rowid = 999` on a missing row returns `Right` with no change. That IS standard SQL — `changes() = 0`,
the reference agrees. It is only wrong for an *address* write, where the address names one specific
cell; `scljet/address.ssc` therefore resolves the address before writing and refuses when it does
not exist, rather than changing the engine's SQL semantics.

## swift-renderer-inventory-missing-shipped-tag — backend inventory omits a lowerer tag
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-18, `swift-renderer-port`). The web lowerer had gained
`element("select")`/`element("option")` (2026-07-13) and CSS `flex-wrap` (2026-07-14) with no Swift
renderer equivalent. Ported them for real (chose port over declare-gap): `<select>` → menu-style
`Picker` (`NativeUiSelectControl`) two-way bound to its value `Signal`, `<option>` children decoded
into `(value,label)` entries, `<option>` alone → strict sourced `Unsupported`; `flex-wrap:wrap` on a
`flex-direction:row` `div` → real wrapping `NativeUiFlowLayout` (custom SwiftUI `Layout`). Also fixed
a co-latent bug the port surfaced: `width:100%`/`height:100%` (emitted by the shipped textField/table
styles too, not just select) hit `invalidDeclaration` → rendered a red `Unsupported` at runtime; now
mapped to `frame(maxWidth/maxHeight: .infinity)` (`width:90vw` and other non-px lengths stay rejected,
still pinned by the diagnostic test). Inventory + renderer + styles updated together in
`SwiftNativeUiApple.scala`. Added a runtime probe test (`select renders a real menu Picker and decodes
its options rather than a stub`) that compiles the generated renderer under `xcrun swiftc -swift-version
6 -strict-concurrency=complete -warnings-as-errors` and asserts `decodeSelectOptions` + Picker `.body`
construction — so the inventory entries are proven real, not stub-satisfied. `v2SwiftBackend/test` 59/0.

**Real-harness repro (was).** Run `v2SwiftBackend/testOnly ssc.swift.SwiftBackendTest -- -z "renderer
inventory"`. The assertion compares shipped lowerer tags with `SwiftNativeUiApple` inventory; do
not remove a tag or weaken subset comparison merely because Xcode execution tests are unavailable
on Linux.

## coreir-abi-int-width-declared-i32-actually-i64 — the v3 descriptor tells every foreign host that `Int` is 32-bit, when it is 64-bit
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: 9c49438d4 -->

**Status:** FIXED — `9c49438d4` (spec `4bdd5e986`, docs `ccc47efe1`), on `origin/main` 2026-07-17 (agent `int64-abi`). Sergiy decided **option (A)**
on 2026-07-16: `Int` → `I64`, make the descriptor truthful. Raised 2026-07-16 by `coreir-contract`,
who correctly escalated rather than fixing unilaterally, because the fix is a contract change.
Tracked in `SPRINT.md` §`control-interoperability`; spec `specs/numeric-width-reconciliation.md`.

**Root cause (the actual one, not the symptom).** Two distinct facts were being carried by one
field. `AbiType.Primitive` held only `value: AbiPrimitive` — the **wire width** — so the *source
spelling* (`Int` vs `Long`) had nowhere to live, and the only way the descriptor could tell two
same-name overloads apart was by giving them different widths. `Int → I32` was therefore doing two
jobs at once: declaring a width (wrongly — ssc `Int` is 64-bit) and carrying identity (accidentally).
That is why the bug could not be fixed by correcting the mapping alone: the bare one-line flip was
measured to produce `DUPLICATE_SYMBOL_ID at $.symbols: ssc:symbol:v1:5ddf0353…` for
`def widen(value: Int)` / `def widen(value: Long)` — the two overloads collapse onto one identity and
the module becomes unexportable. (It fails **closed**, which is why no silent corruption resulted from
the collision itself; the *silent* failure was always the declared width reaching foreign hosts.)

**The fix.** Split the two facts. `AbiType.Primitive(value, declaredWidth: Option[NumericWidthEvidence])`:
`value` is the wire width and is now truthful (`Int` and `Long` both → `I64`); `declaredWidth`
(`DeclaredInt`/`DeclaredLong`) retains the source spelling, carries identity, and never changes
marshalling. Both directions fail closed — an integer width without evidence is rejected as an
ambiguous legacy export (`AMBIGUOUS_NUMERIC_WIDTH`), evidence on a non-integer primitive is rejected
(`INVALID_NUMERIC_WIDTH_EVIDENCE`), and a legacy `{"tag":"Primitive","value":…}` node fails to decode
(`SCHEMA_MISMATCH … missing=[declaredWidth]`) instead of being guessed as `Long`. `AbiPrimitive` keeps
all nine cases; `I32` is now unreachable from ssc source and is reserved for option (C)'s explicit
narrowing ABI, so (A) is (C)'s first slice rather than a dead end.

**Verified.** Producer suite 83/83 (7 expectations flipped — the truth changed; the brief predicted 6),
descriptor suites 32/32 (2 normative vectors deliberately re-frozen: the symbol id moved
`453bfef3…` → `c6231fac…`, and the frozen wire fragment gained `"declaredWidth":[{"tag":"DeclaredInt"}]`),
`core/test` 1138/1138, interop 36/36, plugin-profile 23/23. P6.5 literal fixed point unchanged at
**89 ok / 0 FAIL**, `stage1 == stage2` byte-identical at **79,667 B** (output diff to baseline: empty).
New vectors `NumericWidthAbiVectorTest` were **proven non-vacuous**: reintroducing `Int → I32` makes
all 5 fail loudly with `vector overflow32: a host marshalling an ssc Int per the descriptor changed
the value from 2147483648 to -2147483648`; a sabotage probe on the validator likewise reddens the
3 rejection controls.

**Symptom.** `v1/lang/core/src/main/scala/scalascript/artifact/PreBodyApiDescriptorProducer.scala:2066`
maps source `Int` -> `AbiPrimitive.I32` (`:2067` maps `Long` -> `I64`). But ScalaScript's `Int` is
**64-bit**. So the `ssc-api-descriptor-v3` interop surface — the thing whose whole job is to tell
JS/TS, Rust, Swift and WASM-WASI hosts how to marshal our values — declares a 32-bit width for a
64-bit value. A host that believes the descriptor **silently truncates any value > 2^31-1 at the ABI
boundary**. It fails open, it is cross-language, and it is on the interop surface.

**Reproduce** (measured in the real runtime, not read off the source):

```bash
scala-cli --power package v2/src --assembly -o /tmp/ssc.jar
cat > /tmp/w.ssc0 <<'EOF'
def p = (label, s) => #io.print(#sconcat(label, s))
def main = () =>
  let a = p("2147483647 + 1        = ", #i->str(#i.add(2147483647, 1))) in
          p("9223372036854775807+1 = ", #i->str(#i.add(9223372036854775807, 1)))
EOF
java -jar /tmp/ssc.jar run /tmp/w.ssc0
# 2147483647 + 1        = 2147483648            <- did NOT wrap at 32 bits => Int is not I32
# 9223372036854775807+1 = -9223372036854775808   <- DID wrap at 64 bits     => Int is I64
```

Corroborated by `v2/specs/10-core-ir.md` §2 ("`Int` is 64-bit two's-complement, wrapping (matches
`ssc 1.0`'s `Int = Long`)") and by the durable memory note `project_interp_int64_and_entrypoint.md`
("ssc Int is 64-bit").

**Why it was not just fixed** (historical — resolved by Sergiy's 2026-07-16 decision, kept because it
explains the shape of the fix). `Int -> I32` was **not dead code**: it was asserted by live tests
(`PreBodyApiDescriptorProducerTest.scala:100,130,132,136,267,1212`), and `AbiPrimitive` is part of the
**frozen Slice A schema** that feeds `apiHash`. Changing the mapping changes the meaning *and the
hash* of every descriptor ever emitted. Three options were written up in full in `SPRINT.md`
(A: `Int`->`I64`; B: make surface `Int` genuinely 32-bit — a Core IR version bump; C: `I64` public
plus an explicit implemented narrowing ABI). **Sergiy chose (A)**, with (C) explicitly left reachable;
the contract change was announced in the rozum `scalascript` room before landing.

## coreir-compiler-unbounded-depth — a deep-but-well-formed capsule overflows the COMPILER at ~depth 500 on a 1m stack
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** FIXED 2026-07-20 by `coreir-compiler-depth` (reprioritized by Sergiy the same day, after
being deferred earlier that day). Found 2026-07-16 by `coreir-contract` while bounding the *reader*.
Two independent overflows hid behind one title; both are addressed, with one part deliberately left
open and re-scoped (see "What is NOT fixed" below).

**What the diagnosis actually separated.** The title says "the COMPILER", but reproducing both halves
showed two unrelated recursions with different cures:

| | Where | Frames/level | Driven by | Fix |
|---|---|---|---|---|
| **(A)** compile time | `tryFC` / `mayProduceAutoThreadOp` / `collectRegfields` | ~5 | Core IR nesting | depth bound → diagnostic |
| **(B)** run time | `evaluateRemainingAsStep` → `Runtime.value` (Runtime.scala:1512) | ~3 | the *interpreted program's* own non-tail recursion | explicitly sized VM thread |

(B) is not a bounded-input problem at all: `evaluateRemainingAsStep` recurses per *argument*
(bounded by arity), but evaluating an argument that is itself a call re-enters `Runtime.run`. That is
ordinary user recursion landing on the JVM stack, which no capsule bound can help.

**(A) — fixed by bounding.** `Compiler.MaxDepth` (default **250**, `-Dssc.compiler.maxDepth=N`) is
checked once at the top of `compileWithGlobals`, by an *iterative* worklist probe — a recursive probe
would overflow on the very input it exists to reject. `Compiler.subterms` is exhaustive with no
catch-all, so adding a `Term` case is a compile error rather than a silently under-reported depth
(which would make the guard fail OPEN). Measured to pick the number: the `tryFC` cycle costs ~5 frames
per level and a `(seq …)` chain compiles at depth 300 but overflows at 400 on `-Xss1m`, while
compiling all 85 `v2/examples` yields a maximum `Term` depth of **72** on artifacts up to 165 KB —
~3.5x headroom, below the cliff. The ladder is now: real programs run, 250–1000 hits the compiler
bound, deeper hits the existing `Reader.MaxDepth`. No `StackOverflowError` at any depth.

**(B) — fixed operationally, not architecturally.** The VM now runs on an explicitly sized thread
(`Main.onSizedStack`, default 64 MB, `-Dssc.stackSize=<bytes>`, `0` keeps the caller's thread). This
removes the dependence on an OS default that differs by platform — 1 MB Linux/CI vs 2 MB macOS, the
asymmetry that kept CI red for 192 runs while everything passed locally. Measured: `ssc0c` compiling
`examples/uselib.ssc0` overflows at 1m and 2m and needs **4m**, so the `-Xss512m` in the launchers was
a 128x overshoot. After the change that command succeeds with a bare `java -jar` and even under a
forced `-Xss1m`; user-level non-tail recursion at `-Xss1m` went from overflowing at 2 000 to running
20 000.

**What is NOT fixed — and why the playground still waits.** (B)'s cure is a bigger stack, and a
browser grants ~1 MB with no way to ask for more. A client-side VM therefore still needs an explicit
continuation stack (CEK-style) so non-tail calls live on the heap. That work is tracked in `BACKLOG`
under `site-playground`, now with the real number attached: the gap is 1 MB available vs 4 MB needed
for self-compilation — 4x, not the 512x the old launcher flag implied.

**Verification.** `v2/conformance/check.sh` 644 ok / 0 FAIL, exit 0, with each fix and with both. All
85 examples compile byte-identically before and after. Note this is a no-regression result, **not**
evidence that these fixes turned the 5 previously-documented FAILs green: `chk_compiler_diff` had
already been switched to the `sscx` 512m launcher before this work started, so those steps were
passing via that workaround. The sized thread is what makes the workaround unnecessary.

**Historical detail from the original report follows.**

**Symptom.** `Compiler.valuePositionsNeedEffectThreading` / `FastCode.tryFC` recurse without a bound.
A perfectly well-formed (nothing malformed — merely deeply nested) Core IR program overflows the JVM
stack at roughly **depth 500** on `-Xss1m`, which is the **Linux/CI default** main-thread stack;
macOS defaults to 2m, so this hides locally — the same asymmetry that kept CI red for 192 runs.

`StackOverflowError` is an `Error`, not a catchable failure: on an untrusted persisted capsule this is
a denial of service, not a diagnostic.

**Fresh gate baseline (2026-07-19, `5f39336a8`).** `v2/conformance/check.sh` naturally exits 1 with
637 ok / 5 FAIL. All five labels (`ssc0c uselib`; JS and Rust `quicksort-lib`/`zipwith`) have the same
default-stack compiler overflow, both initial run and retry, in
`Compiler.compileEffectAwareApplication` / `evaluateRemainingAsStep`; complete diagnostics are in
`$TMPDIR/ssc-conformance-logs-25835/failures.log`. The valid programs are structurally shallow (max
canonical S-expression depth: self compiler 28, JS/Rust generators 51), proving that reader depth alone
does not explain or guard this compiler recursion. The same `uselib` command succeeds at `-Xss512m`.

**Reproduce:**

```bash
python3 -c "n=500; print('(program (defs) (entry ' + '(seq '*n + '(lit unit)' + ')'*n + '))', end='')" > /tmp/d500.ir
java -Xss1m -jar /tmp/ssc.jar run-ir /tmp/d500.ir
# Exception in thread "main" java.lang.StackOverflowError
#   at ssc.Compiler$.valuePositionsNeedEffectThreading(Runtime.scala:654)
#   at ssc.FastCode$.tryFC(Runtime.scala:1886)
```

**Context / what is already done.** `Reader.MaxDepth` (default 1000, `-Dssc.coreir.maxDepth=N`) now
bounds the *decoder*, so the reader itself yields a diagnostic instead of crashing — see
`specs/coreir-codec-vectors.sh` §"bounded decoding", which tests at `-Xss1m` on purpose. But the
capsule path is only fully DoS-safe once the compiler is bounded too. Real Core IR is shallow
(measured: the 79,667 B X1 fixpoint IR is depth **25**; the `.coreir` fixtures are 6-12), so a
compiler-side bound has enormous headroom available.

**Current operational decision.** The normal-program F7 failures are handled by using the already
documented `sscx` 512m-stack launcher for stack-heavy tower generators. That restores shipped workloads
but intentionally does not change this bug's status or claim to protect adversarial capsules.

## irbin-v2bin-codec-fails-open — the deferred binary codec narrows BigInt, loses -0.0, and turns unknown tags into strings
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     gate: v2/conformance/check.sh
     fixed-in: ac59d7f54 -->

**Classified 2026-08-02 from the entry's own content:** it is `open`, not `unknown` — two of the four
sub-defects are marked STILL OPEN below (`-0.0` collapsing, and `IrBytes` having no representation),
and both are blocked on the same thing: a shared-kernel prim that does not exist.

⚠ **The sha this entry cites is not reachable.** `cb8ad2863` resolves in a local object store but
`git merge-base --is-ancestor cb8ad2863 origin/main` says NO — so for everyone else it dangles. That
is live evidence for [`bugs-index-fixed-in-checks-resolvable-not-reachable`](#bugs-index-fixed-in-checks-resolvable-not-reachable),
which predicted exactly this and is why nothing here records it as `fixed-in`. The prose citation is
left as written rather than deleted: it is a true record of what the author had, and the correct fix
is to find the landed equivalent, not to erase the evidence.

**Status:** **3 of 4 FIXED 2026-07-27** by opus (`irbin-fail-open`, `cb8ad2863`); two sub-defects
remain and are marked below.

- **(1) BigInt narrowing — FIXED.** `IrBig` now travels as its decimal string (`big->str`/`str->big`),
  arbitrary precision like the canonical codec. A/B measured: `2^64+1` round-trips **PRESERVED** with
  the fix and **CORRUPTED** without it.
- **(3) Unknown tag / unparseable float — FIXED.** Both now decode to a named unbound global
  (`_err_irbin_unknown_tag`, `_err_irbin_bad_float`) — the tower's loud-failure idiom, same shape as
  `ssc1-lower`'s `_err_int_range`. The old codec crashed with `IndexOutOfBoundsException` on the same
  input, so the new regression case discriminates rather than agreeing with prior behaviour.
- **(2) `-0.0` still collapses — STILL OPEN.** The encoder uses `#f->str`, the USER-visible renderer.
  The canonical form is `Writer.floatLit`, which is **not exposed as a kernel prim**, so irbin cannot
  reach it from ssc0. Fixing this needs either a `floatLit` prim or an exact-bits float prim — a
  shared-kernel change, deliberately not bundled into a codec fix.
- **`IrBytes` still has no representation — STILL OPEN.** Same shape: it needs a bytes↔hex prim to
  encode what the canonical codec writes as `(bytes HEX)`.

Frozen size note: the demo moved 108 → **110 bytes**, deliberately — two bytes to stop returning
wrong values for BigInts the old encoding could not represent. Recorded in `check.sh` next to the
expectation so the change reads as intentional. New regression case
`v2/examples/irbin-failclosed.ssc0` pins all three fixed properties; `v2/conformance/check.sh`
409 ok / 0 FAIL.

**Historical report:** OPEN (found 2026-07-16 by `coreir-contract`). **Not** the canonical codec — `lib/irbin.ssc0`
is the deferred `v2-bin` experiment (`12-ir-format.md` §"Open / deferred"); the canonical text codec is
unaffected. Filed so it is fixed *before* `v2-bin` is ever promoted to a real format.

Three independent fail-open defects in `v2/lib/irbin.ssc0`:

1. **BigInt is silently narrowed to 64 bits.** `:53` encodes `IrBig(n)` as `encSVar(a1, #big->i(n))`.
   `big->i` "may overflow" per `10-core-ir.md` §5, so any BigInt outside Int64 is **corrupted**, not
   rejected. The canonical text codec is correct here (vector: "const CBig -> arbitrary precision").
2. **`-0.0` is lost.** `:54` encodes `IrFloat(d)` as `encStr(a1, #f->str(d))` — the *user-visible*
   renderer, which collapses whole doubles, so `-0.0` becomes `"0"`. This is the same root cause as the
   canonical-codec bug fixed on 2026-07-16; `irbin` should use the new `Writer.floatLit` semantics.
3. **Unknown tags become `IrStr`, and unparseable floats become `0.0`.** `:88` maps a failed `#str->f`
   to `IrFloat(0.0)`; `:89` is a bare `else` that turns **any** unrecognised tag into `IrStr`. Corrupt
   input decodes to a plausible-looking wrong program instead of an error.

Also: `IrBytes` has no representation at all in `irbin` (`grep -c IrBytes lib/irbin.ssc0` = 0), so the
binary codec cannot round-trip a bytes literal that the canonical codec now encodes fine.

**BOTH REMAINING SUB-DEFECTS FIXED 2026-08-09 in `ac59d7f54`.** This entry said they were blocked on
"a shared-kernel prim that does not exist", and that was exactly right: `lib/irbin.ssc0` is written
in ssc0 and can only reach what the kernel exposes. Four prims were added beside the ones they pair
with — `f->lit` / `lit->f` and `bytes->hex` / `hex->bytes` — and `IrBytes` took tag 25.

- **(2) `-0.0`** — the encoder called `#f->str`, the USER-VISIBLE renderer, where
  `floatStr(-0.0) == "0" == floatStr(0.0)`. The sign bit was lost in the ENCODER, not the decoder.
  It now calls `#f->lit`, the canonical literal. `lit->f` is a separate door from `str->f` because
  the canonical form spells specials `nan` / `inf` / `-inf`, which `"nan".toDoubleOption` refuses.
- **`IrBytes`** — had no case at all. Carried as lowercase hex, the same spelling the canonical text
  codec writes in `(bytes HEX)`, so the two agree on the wire form rather than each inventing one.
  `hex->bytes` fails CLOSED on an odd length or a non-hex digit.

**The fixture pins seven properties now, up from three**, and the negative-zero one needed care to be
a pin at all: `-0.0 == 0.0` is TRUE in IEEE-754, so comparing the decoded VALUE passes in both states
and proves nothing. It compares `#coreir.encode`, which renders through `floatLit` and spells the two
zeros differently. **Proved by planting the old encoder back:** `#f->str` gives `negzero-COLLAPSED`,
`#f->lit` gives `negzero-preserved`.

⚠ **`inf-preserved` is green in BOTH states** and is recorded as such: `floatStr` and `floatLit`
spell the specials identically, so that row guards the `lit->f` pairing rather than the encoder
choice. A row that cannot fail for the bug it sits next to is worth naming before someone reads it
as coverage.

`v2/conformance/check.sh`: rc=0, **645 ok**, unchanged. `irbin-demo` stays at **110 bytes** — the
canonical literal cost this corpus nothing.

**The dangling sha this entry warns about is still dangling** and is left as written: `cb8ad2863`
resolves locally and is not an ancestor of `origin/main`. That remains live evidence for
`bugs-index-fixed-in-checks-resolvable-not-reachable`, which is why the header above cites the
commit that is reachable instead.


## descriptor-v3-nested-owner-identity-leak — nested private identities under non-object owners fall back external
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala
     fixed-in: f4d4c01ec -->
**RE-MEASURED 2026-08-08, and the symptom does not reproduce.** A private `class` / `trait` / `enum` / `object` owner's nested `type T` exposed as `Hidden.T` in a public signature is REFUSED with `UNSUPPORTED_PUBLIC_TYPE`, under every nominal owner the entry lists. Gated by `PreBodyApiDescriptorProducerTest` — "nested identities under private nominal owners cannot fall back external".

Measured, not inferred: the "landing SHA pending independent approval" this cluster cites,
`f4d4c01ec`, has been on `main` since the day the entries were written, and nothing since had asked
whether any of them still reproduces. Closing on a measurement rather than on the age of the report.


**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `f4d4c01ec`, landing SHA
pending independent approval.

**Symptom/reproduce:** declare `private class Hidden { type T }` and expose a public
signature containing `Hidden.T`. Strict managed production returns `Right` with an
external `AbiType.Named("Hidden.T")` instead of rejecting the effectively private
local identity. Equivalent nested identities under traits/enums and nested local
effects/aliases have the same un-audited owner-boundary risk.

**Root cause/plan:** `collectLocalTypes` descends namespace objects but not every
nominal owner, so the known-local identity inventory is incomplete before qualified
external fallback. Collect types, aliases, and effects recursively under class,
trait, enum, and object owners; carry inherited effective visibility and whether the
owner itself is representable. Resolve any known nested identity through that
inventory before external fallback and reject private/internal/nonrepresentable
owners with the stable local-visibility error. Audit `localEffects` and alias
expansion through the same owner traversal rather than adding a type-only exception.

**Done when:** faithful class/trait/enum/object nested-owner regressions fail on the
reviewed checkpoint, then pass with stable paths/codes; prior public nested-object
positives remain green; the full focused, descriptor/core/interop/IR/ABI, and
modules/import-dir plus forced effect conformance radius passes. Keep `open` until
fresh independent approval and landing on `origin/main`.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The nested-owner regression fails because the private-class case returns
`Right` with `Named("Hidden.T")`; all previous 63 producer tests remain green.

**Local verification:** `f4d4c01ec` replaces the object-only collectors with one
recursive owner-aware inventory covering class/trait/enum/object, abstract
`Decl.Type`, inherited visibility, and receiver representability. Audit-hardening
vectors cover public-class members, a nested object below a class, known-owner/
unknown-member fallback, and the positive public-object namespace. Focused producer
passes 82/82 and full core passes 1132/1132; keep `open` until fresh review and
landing.

## descriptor-v3-import-identity-laundering — selected/imported aliases bypass canonical identity resolution
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala
     fixed-in: f4d4c01ec -->
**RE-MEASURED 2026-08-08, and the symptom does not reproduce.** `import java.{lang as jl}` with a public `jl.String` parameter is REFUSED — `PLATFORM_TYPE_FORBIDDEN`. The code is recorded and deliberately NOT asserted anywhere: my first assertion demanded an `UNSUPPORTED*` code and failed against this one, which is a refusal with a more precise name than I guessed. Covered by the existing `as jl` tests in `PreBodyApiDescriptorProducerTest`.

Measured, not inferred: the "landing SHA pending independent approval" this cluster cites,
`f4d4c01ec`, has been on `main` since the day the entries were written, and nothing since had asked
whether any of them still reproduces. Closing on a measurement rather than on the age of the report.


**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `f4d4c01ec`, landing SHA
pending independent approval.

**Symptom/reproduce:** `import java.{lang as jl}` followed by a public `jl.String`
type succeeds as external `Named("jl.String")`; a chained `import jl.{Integer as
Int}` succeeds as `Named("jl.Integer")`. A local callback alias declared under
`object Types`, imported with `import Types.Callback`, is projected as an ordinary
parameter and receives no conservative callback policy. Selected types, importer
qualifiers, chained aliases, private identities, and platform roots therefore take
different resolution paths.

**Root cause/plan:** `ImportScope` models only final bare-name bindings and
`projectNamedApplication`/selected-name/callback-alias code bypasses expansion of
importer qualifiers. Implement one source-ordered lexical identity resolver used by
all bare and selected type projection plus callback classification. It must expand
direct/renamed importer prefixes and chained aliases, detect cycles/conflicts/
wildcards, retain exclusions/given-only behavior, apply platform-root checks after
expansion, and consult effective local visibility before external fallback. Remove
ad hoc paths that can disagree about the same spelling.

**Done when:** all three faithful repros fail on the reviewed checkpoint, then
platform chains reject and imported local callback aliases receive `ForeignBarrier`
policy; direct/rename/wildcard/exclusion/source-order positives remain green; the
full affected gates pass. Keep `open` until fresh independent approval and landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. Three resolver regressions fail: selected `jl.String` and chained
`jl.Integer` return `Right`, and an imported local function alias has no callback
policy. All previous 63 producer tests remain green.

**Local verification:** `f4d4c01ec` anchors exact targets under the preceding import
environment and shares one resolver across bare/selected type projection, effect
rows, callback classification, and later importer qualifiers. Transparent aliases
snapshot their declaration-time import scope. Platform chains, selected local
prefixes, imported callbacks/effects, wildcard prefixes, private identities, and
source-order controls are green; focused producer passes 82/82. Keep `open` until
fresh review and landing.

## descriptor-v3-dual-effect-evidence-mismatch — preprocessing hides effect/object carrier disagreement
<!-- status: fixed
     kind: bug
     lane: multi
     fixed-in: 21ae17ec0
     area: front -->
**NOT INDEPENDENTLY RE-VERIFIED 2026-08-08.** This entry was already `status: fixed` when five of its
cluster siblings were re-measured that day; it is recorded here only so a reader knows the difference
between "measured today" and "marked fixed by whoever fixed it". Its reproduction needs a module whose
RETAINED AST and RETAINED SOURCE disagree — the original review built that by parsing and then
MUTATING the module, and `PreBodyApiDescriptorProducerTest` drives the producer from a source string,
so there is no harness for the shape. Building one inside a re-measurement would have been a different
piece of work done badly.


**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"dual carriers distinguish an empty effect from an ordinary object"*, which swaps `effect Empty:` and `object Empty:` between the two carriers in BOTH directions and requires each to be rejected. Landed `21ae17ec0` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
frozen checkpoint `4cd2a4aaa` (rebased as `05e498a72`); fix SHA pending.

**Symptom/reproduce:** canonical section preprocessing rewrites raw `effect` syntax
into an object before correspondence. A CodeBlock containing an effect and a
Document carrier containing an ordinary object can consequently produce the same
stored declaration witness. The producer then silently chooses document text as
`rawSource`, so empty effect/object and multi/ordinary disagreements can pass or
change multiplicity evidence without the two retained carriers agreeing.

**Root cause/plan:** declaration witnesses compare only post-preprocess trees and
lose the raw effect-header distinction. Extract a deterministic semantic effect
evidence witness from each raw executable carrier before choosing one: effect versus
ordinary object kind, lexical name/order, plain versus multi multiplicity, and
unsupported generic/parent header shape are significant; source line offsets are
not. Package wrapping has already replaced `CodeBlock.source`, so preserve erased
empty-effect origin with reserved parser-internal `private type` sentinels rather
than runtime `val` fields or a broader new serialized source carrier. Require
CodeBlock and optional Document evidence to agree after their ordinary declaration
witnesses agree; filter the sentinels from API/runtime values and fail closed on a
packaged ordinary-object collision. Regress empty effect/object, multi/ordinary,
stale carrier positives/negatives, parser/EffectAnalysis invariance, and preserve
documentless fail-closed safety.

**Done when:** faithful red vectors fail on the current checkpoint, then pass with
the full affected gates. Keep `open` until fresh independent approval and landing.

**Red baseline:** regression commit `f08ab9943`; focused producer is exactly
50/60. Two raw-evidence tests fail: empty effect/object dual carriers return `Right`,
and a documentless empty effect silently becomes an ordinary value. Plain/multi
negatives, line-offset invariance, unsupported-shape rejection, and all prior effect
vectors remain green.

**Local fourth correction checkpoint:** implementation `43d41e88d` and spec
verification `38597ae85`, rebased on `origin/main@f63714680`, compare ordered raw
effect witnesses before carrier selection and retain erased origin only in filtered
private type sentinels. Focused producer/parser/effect tests pass 75/75 (producer
63/63), descriptor 27/27, core 1111/1111, interop 36/36, IR succeeds, artifact ABI
73/73, and affected conformance passes 2/2 modules/import-dir plus 9/9 effect cases.
Status remains `open` until fresh independent approval and landing; the local
commit is not a fix SHA on `origin/main`.

## descriptor-v3-array-byte-component-shadow — bytes shortcut ignores the `Byte` identity
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     gate: v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala
     fixed-in: f4d4c01ec -->
**RE-MEASURED 2026-08-08, and the symptom does not reproduce.** `type Byte = Int` followed by `Array[Byte]` in a public signature is REFUSED — `AMBIGUOUS_NAMED_TYPE`, not silently emitted as primitive `Bytes`. **This is the one whose falsifiability is proven**: with the local-type inventory emptied — the exact root cause the entry names, "the known-local identity inventory is incomplete" — the descriptor comes back `Right` with `Primitive(Bytes,None)`, which is the entry's symptom verbatim. Covered by the existing `Array[Byte]` shadow tests.

Measured, not inferred: the "landing SHA pending independent approval" this cluster cites,
`f4d4c01ec`, has been on `main` since the day the entries were written, and nothing since had asked
whether any of them still reproduces. Closing on a measurement rather than on the age of the report.


**Status:** open (2026-07-15). Reported as P1 by the fresh independent rereview of
frozen checkpoint `8a8886557` (rebased as `28535c87d`); fix SHA pending.

**Symptom/reproduce:** the producer recognizes the syntax `Array[Byte]` and emits
primitive `Bytes` after checking only whether `Array` is a local type. It therefore
also emits `Bytes` when `Byte` is a generic binder or a known `@internal`, private,
or public local type, even though none of those names denotes the built-in byte
element type.

**Root cause/plan:** the bytes fast path pattern-matches the leaf spelling before
normal lexical binder/local resolution of both components. Resolve/check both
`Array` and `Byte` first (including binders and effective local visibility), and use
the shortcut only when neither spelling is shadowed. A non-public component rejects
with the ordinary stable visibility error; a public/bound component follows ordinary
type projection and must never become primitive `Bytes`. Regress a `Byte` binder,
both private and `@internal` local `Byte`, and public local `Byte`, while preserving
the existing local-`Array` vectors.

**Baseline:** regression commit `387a10384`; the focused suite is 39/46 and all
four new shadowing tests fail because the producer returns a successful descriptor
containing primitive `Bytes`. The unshadowed built-in positive remains green.

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` resolves both component identities before the shortcut.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status remains `open`
until fresh independent approval and landing on `origin/main`.

## descriptor-v3-codeblock-source-bypass — documentless modules skip source/AST correspondence
<!-- status: fixed
     kind: bug
     lane: multi
     fixed-in: d80611194
     area: front -->
**NOT INDEPENDENTLY RE-VERIFIED 2026-08-08.** This entry was already `status: fixed` when five of its
cluster siblings were re-measured that day; it is recorded here only so a reader knows the difference
between "measured today" and "marked fixed by whoever fixed it". Its reproduction needs a module whose
RETAINED AST and RETAINED SOURCE disagree — the original review built that by parsing and then
MUTATING the module, and `PreBodyApiDescriptorProducerTest` drives the producer from a source string,
so there is no harness for the shape. Building one inside a re-measurement would have been a different
piece of work done badly.


**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"a documentless packaged module still verifies CodeBlock source against its AST"*, which is this entry's recipe exactly — it removes `document`, rewrites the retained `CodeBlock.source` header, keeps the old tree, and requires `UNSUPPORTED_PUBLIC_DECLARATION`. Landed `d80611194` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent rereview of
frozen checkpoint `8a8886557` (rebased as `28535c87d`); fix SHA pending.

**Symptom/reproduce:** parse a packaged module, remove `document`, then change a
retained `ast.Content.CodeBlock.source` declaration header while keeping its old
tree. `topLevelStats` assigns `rawSource = None` to section code blocks when no
document snapshot exists, so correspondence is skipped and the stale tree returns
`Right`. When both document and code-block sources exist, the document source is
paired by position and the code-block source is silently ignored, so the two retained
sources can disagree without a deterministic rule.

**Root cause/plan:** retained source evidence is collected only from `DocumentContent`.
Always retain and verify every parseable executable `CodeBlock.source`, including
legacy/documentless packaged modules. When both source carriers exist, neither may
override the other silently: canonically reparse each, unwrap the manifest package
chain where its parsed shape contains it, require equal body-erased declaration
witnesses against the stored AST (and therefore against each other), then use the
document source for effect-header evidence only after agreement; otherwise fall back
to the code-block source. Regress documentless stale source and dual-source header
disagreement while keeping body-only invariance.

**Baseline:** regression commit `387a10384`; both the faithful documentless stale
`CodeBlock.source` repro and the dual-source disagreement repro return `Right` from
the old tree. They account for two of seven failures in the exact 39/46 focused run.

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` verifies mandatory section source plus optional document
source against the stored AST. Focused producer 46/46, descriptor 27/27, core
1092/1092, interop 36/36, IR, artifact ABI 73/73, and affected conformance 2/2
are green. Status remains `open` until fresh independent approval and landing.

## descriptor-v3-package-wrapper-header-forgery — wrapper names match while wrapper semantics differ
<!-- status: fixed
     kind: bug
     lane: multi
     area: conformance
     gate: sbt core/testOnly *PreBodyApiDescriptorProducerTest*
     fixed-in: 3ea1efd88 -->
**NOT re-measured 2026-08-08, and this says so rather than leaving a gap in a green file.** Five
siblings in this cluster were re-measured that day and all five closed; three more were already
`fixed`. **This is the one that is still `open`**, and it could not be measured: its reproduction
needs a module whose RETAINED AST and RETAINED SOURCE disagree, which the original review built by
parsing and then MUTATING the module. `PreBodyApiDescriptorProducerTest` drives the producer from a
source string, so there is no harness for that shape, and inventing one inside a re-measurement would
have been a different piece of work done badly. The age of the report is not evidence either way.

⚠ The first version of this note was pasted onto all four un-measured entries and said each "stays
open and unmeasured". Three of the four were already `status: fixed`, so on those it contradicted
their own header. Corrected within the hour; the wrong claim is recorded rather than erased because
it is the same mistake the rest of this cluster's notes are about — asserting a state instead of
reading it.


**Status:** open (2026-07-15). Reported as P1 by the fresh independent rereview of
frozen checkpoint `8a8886557` (rebased as `28535c87d`); fix SHA pending.

**Symptom/reproduce:** for manifest package `demo.api`, replace the stored synthetic
wrapper with `object demo extends Serializable: object api: ...` while retained source
still has the plain declaration. `unwrapPackage` requires only one object and the
expected name, discards the wrapper header, and compares identical inner declarations,
so strict production returns `Right`.

**Root cause/plan:** the wrapper chain is structurally unique but not header-exact.
At every manifest package segment require the exact plain synthetic wrapper shape:
no modifiers, parents/inits, derives, self type, or any other non-body template/header
state, plus exactly one expected child wrapper until the leaf. Reject a forged wrapper
at the block path before inner declaration correspondence. Add the exact
`extends Serializable` stored-AST regression.

**Baseline:** regression commit `387a10384`; the forged-wrapper repro returns
`Right` and projects the inner API. It is one of seven failures in the exact 39/46
focused run.

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` requires the exact plain wrapper at every package segment.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status remains `open`
until fresh independent approval and landing.

**FIXED 2026-08-09 in `3ea1efd88`, harness and all.** `unwrapPackage` descended on
`obj.name.value == head` alone; it now requires the shape `wrapSectionInPackage` actually writes —
empty `mods`, `inits`, `derives` and no self type — and requires it BEFORE descending, so the forged
shell is rejected at the shell rather than surviving to an inner-declaration comparison that cannot
see it. Early initialisers are not checked, deliberately: `Template.early` is deprecated in
scalameta 4.9.9 and the construct does not exist in the Scala 3 syntax this front parses.

**THE MISSING HARNESS WAS THE WORK.** This entry's own note explains why the 2026-08-08 sweep could
not measure it: the reproduction needs a module whose retained AST and retained SOURCE disagree, and
`PreBodyApiDescriptorProducerTest` drives everything from a source string. The forgery cannot be
written as source at all — the generator builds the shell itself, plainly, from the manifest.
`withForgedTree` parses a normal module and swaps the retained tree of its code block for a
re-parsed forged one, which is the shape the original review built by mutation.

**The test carries its own control, and it runs first:** with the PLAIN wrapper spliced the same
way, `visible` IS surfaced. Without that line, "the export is absent" would also be satisfied by a
harness that surfaces nothing at all.

**Proved to discriminate:** with `isPlainWrapper` forced to admit everything, the new test fails and
the other 84 stay green. Deleting the guard outright is NOT a usable control — it makes the function
unused and `-Werror` fails the build before a test runs.

`sbt core/test`: **1155 tests, all passed** — the tightening rejects the forgery without rejecting
any real module in the suite.


## descriptor-v3-nonpublic-local-type-leak — private local types fall back to external names
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala
     fixed-in: f4d4c01ec -->
**RE-MEASURED 2026-08-08, and the symptom does not reproduce.** Same symptom as `descriptor-v3-nested-owner-identity-leak` in different words, and the same measurement closes it: `Hidden.T` under a private owner is refused, not accepted as an external `Named`. Gated by the same test.

Measured, not inferred: the "landing SHA pending independent approval" this cluster cites,
`f4d4c01ec`, has been on `main` since the day the entries were written, and nothing since had asked
whether any of them still reproduces. Closing on a measurement rather than on the age of the report.


**Status:** open (2026-07-15). Reported by the independent Slice B frozen-checkpoint
re-review; affected pre-integration commit `0f60205c5` (rebased as `59ca2898f`);
fix SHA pending.

**Symptom/reproduce:** a public signature using `Hidden.T`, where `Hidden` is a
private/internal local owner, is accepted as an external `AbiType.Named` instead of
being rejected. Likewise an `@internal` local alias for a callback type can be
exported without expanding to the callback shape, bypassing the mandatory callback
policy. The same bypass survives through a non-exported public wrapper alias, and
the built-in `Array[Byte]` fast path can outrun a private local `Array` declaration.
A qualified private local effect can likewise fall back to an external effect-row id.

**Root cause/plan:** the lexical type index records only public declarations and
recurses only through public owners. A known local declaration hidden by visibility
therefore becomes indistinguishable from an actually external qualified type. Index
all local type identities and aliases with effective owner visibility; if resolution
finds a known non-public local identity, fail with stable `UNSUPPORTED_PUBLIC_TYPE`
before external-name projection or callback classification. Regress both the private
qualified-owner and internal callback-alias shapes, a public alias chain, absolute
fully-qualified selection, the shadowed `Array[Byte]` fast path, and a private local
effect row. The bytes shortcut is valid only when lexical lookup finds no local
`Array`; a public local `Array` must retain ordinary local-constructor projection,
while a non-public one rejects before the shortcut.

**Baseline:** focused producer test accepts `Hidden.T` and `Callbacks.Hidden` as
external `AbiType.Named` values; the latter has no callback policy. These are two
of four expected failures in the `25/29` pre-fix run.

**Latest local checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` covers relative and absolute private owners, direct and
wrapped callback aliases, private/public local `Array` shadowing, and a private
local effect. Focused producer 46/46, descriptor 27/27, core 1092/1092, interop
36/36, IR, artifact ABI 73/73, and affected conformance 2/2 are green. Status stays
`open` until fresh independent approval and landing on `origin/main`.

## descriptor-v3-source-ast-correspondence-tamper — count-only retained-source check accepts stale declarations
<!-- status: fixed
     kind: bug
     lane: multi
     fixed-in: 52a193593
     area: front -->
**NOT INDEPENDENTLY RE-VERIFIED 2026-08-08.** This entry was already `status: fixed` when five of its
cluster siblings were re-measured that day; it is recorded here only so a reader knows the difference
between "measured today" and "marked fixed by whoever fixed it". Its reproduction needs a module whose
RETAINED AST and RETAINED SOURCE disagree — the original review built that by parsing and then
MUTATING the module, and `PreBodyApiDescriptorProducerTest` drives the producer from a source string,
so there is no harness for the shape. Building one inside a re-measurement would have been a different
piece of work done badly.


**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"retained declaration source must correspond exactly to its stored section AST"*, which truncates the retained executable source to `effect Real:\n` while preserving the old section AST, and requires rejection. Landed `52a193593` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported by the independent Slice B frozen-checkpoint
re-review; affected pre-integration commit `0f60205c5` (rebased as `59ca2898f`);
fix SHA pending.

**Symptom/reproduce:** parse an effect containing operation `read`, then copy the
module so its retained executable source is only `effect Real:\n` while preserving
the old section AST. The strict producer sees the same number of source and AST
containers and returns `Right`, projecting the stale `read` operation that no longer
exists in retained source.

**Root cause/plan:** `topLevelStats` checks only retained-block counts before pairing
source with AST by position. Reparse each retained executable block through the
canonical declaration preprocessor/parser and compare an exact declaration-header
shape with its paired section AST while deliberately ignoring executable bodies and
comments. Reject a mismatch with stable `UNSUPPORTED_PUBLIC_DECLARATION`; preserve
body-only descriptor/hash invariance and add the exact copy/tamper regression.
Require a unique synthetic package-wrapper chain, normalize placeholder type aliases
symmetrically on both trees, and prove that body/RHS/default-expression-only retained
source changes remain accepted against the same stored declaration AST. Witness all
current ScalaMeta definition headers explicitly, including template/alias givens,
extension groups, and macros; never use a product-prefix-only fallback that would
accept a changed unsupported declaration header.

**Baseline:** focused producer test returns `Right` and still exports
`demo.api.Real.read` after retained source removes that operation; this is one of
four expected failures in the `25/29` pre-fix run.

**Latest local checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` reparses and compares exact body-erased headers, normalizes
placeholder aliases, requires exact/plain wrappers, verifies both retained source
carriers, and conservatively covers every current ScalaMeta definition form.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status stays `open`
until fresh independent approval and landing on `origin/main`.

## scala-direct-polymorphic-value-select — moved structural apply retains `<none>`
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt scala3ControlApi/test
     fixed-in: b6d2cd262 -->

**Status:** open; remediation is green in feature commit `b6d2cd262` on frozen
`origin/main` base `6603e6c29`, but fresh independent review and the landing SHA
are pending. Reported as P1 by the fresh independent review of frozen candidate
`f4e860ed7..408f23c11` (2026-07-15).

**Faithful packaged reproduce:** compile with Scala CLI 3.8.3 against only the
packaged `scalascript-control_3` JAR. Inside `direct.reset`, place the ordinary
strict value
`val identity: [A] => A => A = [A] => (a: A) => a` before a block-level
`direct.shift`, then evaluate `selected + identity[Int](2)` in the captured suffix.
The direct source fails at the suffix call with raw compiler output
`undefined: identity.<none> ... TermRef(... val <none>)` twice. The explicit
`reset`/`shift` equivalent compiles, runs, and prints `42`.

**Root cause:** reference replacement rebuilt every moved `Select` with the source
`selection.symbol`. The structural `PolyFunction.apply` selection has
`Symbol.noSymbol`; copying that placeholder onto the moved qualifier constructed
the invalid `identity.<none>` term. A self-contained `PolyType`/`ParamRef` binder
graph is already closed when retained atomically. A rejected broad-cloning attempt
instead split rank-2/owner-dependent graphs, so only graphs that actually depend on
replaced owners are rebound.

**Implementation/verification:** moved selections first transform their qualifier.
When the source symbol is absent, `Select.unique` resolves the member from that
current qualifier; resolution failure becomes stable `DIRECT_STYLE_UNSUPPORTED`
before generated code is emitted. Independent polymorphic values, prefix/suffix
calls, explicit `.apply[Int]`, ordinary monomorphic function application, and
result/bound-only `ParamRef` cases execute; an owner-dependent nested polyfunction
fails closed at its declaration. Clean focused tests pass 51/51 (24 semantics and
27 diagnostics), the full leaf/package/POM gate passes 113/113, and the packaged
Scala CLI 3.8.3 consumer prints fourteen differential `42` values. Its packaged
negative reports only the stable unsupported diagnostic. Catalog validation is
26 vectors/9 lanes, validator negatives are 9/9, `scala-direct` is 3/3, and
affected conformance is 5/5. Keep this entry open until rereview approves and the
fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `b6d2cd262` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `prefix and explicit apply calls retain structural members`. **Read in full and confirmed to assert this entry's symptom.**

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-captured-type-owner — captured A keeps a stale prefix owner
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: a8f321d5c -->

**Status:** FIXED — closed 2026-08-09 by `verify-scala-direct-family` (release `3700867ba`), which confirmed `a8f321d5c` is an ancestor of `origin/main` and `sbt scala3ControlApi/test` 165/165. That release flipped the header to `fixed` without a `fixed-in:`, leaving `bugs-index-gate` RED on `main`; the sha here is the one THIS entry already named in its own prose. Original note, kept:

**Was:** open; remediation is green in feature commit `a8f321d5c`, but fresh
independent review and the landing SHA are pending. Reported as P1 by the fresh
independent review of frozen `scala3-control-macros` checkpoint `708dec2f1`
(2026-07-15).

**Symptom/reproduce:** declare a local owner before capture and use its singleton
type as the captured result, for example
`direct.shift[scope.Key, owner.type, Nothing, Int](...)`. The prefix owner is
freshened, but the captured `A` used to type the generated explicit continuation
still names the original owner. Scala emits raw E007/owner-versus-owner² output.
The same failure occurs for `A = Prompt[inner.Key, Int]`; the equivalent explicit
control program compiles and prints `42`.

**Observed packaged baseline:** Scala CLI 3.8.3 against only the packaged control
JAR fails the singleton case at the enclosing reset expansion with E007: found
`(owner : Object)`, required `(owner² : Object)`. This confirms a generated-type
owner split rather than a test-classpath artifact.

**Root cause/plan:** `explicitShift` opens `found.typeArguments(1).tpe.asType`
without applying the capture split's prefix replacement map, then moves and casts
the rank-2 body against that stale type. Rebind the captured type through the same
supported dependent/singleton type substitution used by prefix declarations and
use the rebound type consistently for the explicit shift and moved continuation
body. Prefer supporting both common singleton and local-prompt shapes; any truly
unsupported type must reject at the source marker with stable
`DIRECT_STYLE_UNSUPPORTED`, never a generated compiler error. Add faithful
packaged-consumer semantics for both shapes and the explicit differential.

**Implementation/verification:** the captured type is rebound through the active
term/type replacement graph before `asType`, and the rebuilt type is used by the
rank-2 body, explicit shift, and generated bind continuation. Source regressions
cover both `owner.type` and `Prompt[inner.Key, Int]`; the packaged Scala CLI 3.8.3
consumer executes their direct and explicit forms and prints `42` for each. These
cases are part of the clean 21/21 semantics suite and the 109/109 full leaf gate.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a captured owner singleton type is rebound through the rank-2 body`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-moved-term-type-owner — moved RHS and suffix symbols keep stale owners
<!-- status: fixed
     kind: bug
     lane: multi
     area: cli
     fixed-in: a8f321d5c -->

**Status:** FIXED — closed 2026-08-09 by `verify-scala-direct-family` (release `3700867ba`), which confirmed `a8f321d5c` is an ancestor of `origin/main` and `sbt scala3ControlApi/test` 165/165. That release flipped the header to `fixed` without a `fixed-in:`, leaving `bugs-index-gate` RED on `main`; the sha here is the one THIS entry already named in its own prose. Original note, kept:

**Was:** open; remediation is green in feature commit `a8f321d5c`, but fresh
independent review and the landing SHA are pending. Reported as P1 by the fresh
independent review of frozen `scala3-control-macros` checkpoint `708dec2f1`
(2026-07-15).

**Symptom/reproduce:** before a direct marker, declare
`val owner = new Object` and
`val f: () => owner.type = () => owner`, then use `f` after capture. Although the
outer `ValDef.tpt` is rebound, an inferred/nested symbol type inside the moved RHS
still points at the old owner and Scala reports raw E007/quote-owner output. A
declaration in the captured suffix can retain the same stale owner graph. The
equivalent explicit program compiles and prints `42`.

**Observed packaged baseline:** Scala CLI 3.8.3 against only the packaged control
JAR fails at the lambda RHS with E007: found `() => (owner : Object)`, required
`() => (owner² : Object)`.

**Root cause/plan:** term-reference substitution and the cloned declaration's top
type do not cover every owner-bearing type attached to nested moved definitions or
suffix terms. Audit the complete moved term after owner change, rebind the common
function/lambda and suffix declaration symbol/type graphs, and verify no replaced
old symbol remains. If Quotes cannot rebuild a particular graph soundly in M1,
reject it before constructing generated code with stable source-located
`DIRECT_STYLE_UNSUPPORTED`. Add prefix-RHS and suffix packaged regressions plus an
explicit differential; no raw E007 path is acceptable. Correct the spec and
CHANGELOG wording that currently claims dependent-owner completion too broadly.

**Implementation/verification:** definition-type rebinding reconstructs supported
method/poly binders and closure methods/parameters under the generated owner, then
audits moved terms for every replaced term/type symbol. Prefix and suffix
`() => owner.type` direct/explicit regressions all print `42` in the packaged
consumer. Unrepresentable richer graphs reject before code construction with the
stable unsupported diagnostic. These cases are green in feature commit
`a8f321d5c`, the clean focused 47/47 gate, and the full 109/109 leaf gate.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `strict local values, givens, and pattern binds cross capture`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-contextual-forward-reference — prefix cloning breaks lazy givens
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt scala3ControlApi/test
     fixed-in: a8f321d5c -->

**Status:** open; remediation is green in feature commit `a8f321d5c`, but fresh
independent review and the landing SHA are pending. Reported as P1 by the fresh
independent review of frozen `scala3-control-macros` checkpoint `708dec2f1`
(2026-07-15).

**Symptom/reproduce:** put compiler-lazy parameterless givens before a later marker,
including a forward/mutual pair such as `given first: TC = second` and
`given second: TC = first`. The givens remain unused, so the equivalent explicit
program compiles and prints `42`, but direct lowering moves the first RHS before a
fresh symbol for `second` exists and leaks an old owner/reference.

**Observed packaged baseline:** Scala CLI 3.8.3 against only the packaged control
JAR fails at `given first: TC = second` with raw macro output: `a reference to
given instance second was used outside the scope where it was defined`.

**Root cause/plan:** `preparePrefix` allocates and records each fresh symbol only
after moving that declaration's RHS, which is valid only for backward references.
Allocate the supported crossing value symbols in a first phase, preserving flags
and rebinding their types, then move every RHS with the complete replacement map.
Support ordinary compiler-lazy given forward/mutual term references as promised by
the current spec. Any dependent type cycle that cannot be allocated soundly must
fail closed at its declaration. Add a real packaged consumer for forward/mutual
givens and keep ordinary strict sequencing/shared mutable-cell regressions green.

**Implementation/verification:** prefix lowering now allocates every supported
fresh value symbol before moving any RHS, then moves initializers with the complete
replacement map while retaining compiler `Given`/`Lazy` flags. The unused
forward/mutual-given direct and explicit programs compile and each print `42` in
the packaged consumer. The regression is green in feature commit `a8f321d5c`, the
clean 21/21 semantics suite, and the full 109/109 leaf gate.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `unused forward and mutual parameterless givens retain laziness`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-nested-reset-prompt-marker — outer marker survives in eager nested-reset prompt
<!-- status: fixed
     kind: bug
     lane: multi
     area: cli
     fixed-in: a8f321d5c -->

**Status:** FIXED — closed 2026-08-09 by `verify-scala-direct-family` (release `3700867ba`), which confirmed `a8f321d5c` is an ancestor of `origin/main` and `sbt scala3ControlApi/test` 165/165. That release flipped the header to `fixed` without a `fixed-in:`, leaving `bugs-index-gate` RED on `main`; the sha here is the one THIS entry already named in its own prose. Original note, kept:

**Was:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported by the root
agent's adversarial pre-review of the `scala3-control-macros` feature checkpoint
`9c6850904` (2026-07-15).

**Symptom/reproduce:** inside an accepted outer `direct.shift` rank-2 `ShiftBody`,
use a second exact `direct.shift` targeting the outer scope as the prompt argument
of a nested `direct.reset`, while keeping the nested reset body ordinary. The prompt
expression is evaluated before entering the nested delimiter, but the current
survival audit skips the whole nested-reset `Inlined`/`Apply` tree. The outer marker
therefore survives macro lowering and can fail through a raw compile-time-only or
quote-owner path instead of the stable M1 diagnostic.

**Observed baseline:** a Scala CLI 3.8.3 compile against only the packaged
`scalascript-control_3` JAR reproduces the prompt-argument shape and fails on the
nested call's closing `)` with the raw compiler message `While expanding a macro,
a reference to parameter contextual$2 was used outside the scope where it was
defined`. This is a real packaged-consumer failure, not a source-inspection-only
hypothesis.

**Root cause/plan:** the nested-managed-reset exception is too broad. Refine the
specification first: only the nested reset's managed body/inline expansion belongs to
that nested transform; its eager prompt (and any other eager call arguments) remain
inside the enclosing `ShiftBody` audit. Parse the exact nested-reset call shape,
inspect those eager arguments for exact direct markers, and skip only the managed
body/expansion. Add an exact negative regression, while retaining positive coverage
for an ordinary nested managed reset body and explicit `scalascript.control.shift`.
Run the clean focused/full/package/consumer/catalog/conformance gates and freeze a
new checkpoint for independent review before landing.

**Implementation/verification:** the survival audit now parses the exact curried
nested-reset call, traverses its eager prompt, and skips only the contextual body
owned by the nested transform; an unknown call shape fails closed. The faithful
negative reports the inner marker's stable `DIRECT_STYLE_UNSUPPORTED`, while the
ordinary nested managed body and explicit `scalascript.control.shift` positives
remain executable. Clean focused suites pass 39/39, the full leaf passes 101/101,
and the rebuilt packaged-JAR consumer no longer emits raw owner output. Package,
POM, catalog 26/9, validator negatives 9/9, direct lane 3/3, and affected
conformance 5/5 are green. Keep this entry open until rereview approves and the
fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a direct marker in a nested reset prompt remains in the outer ShiftBody`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-boundary-break-escape — boundary break can outlive its delimiter
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt scala3ControlApi/test
     fixed-in: a8f321d5c -->

**Status:** open; behavior and the missing alias/provenance regressions are green at
feature checkpoint `a8f321d5c`, but fresh independent review and the landing SHA
are pending. Originally
reported as P1 by the rereview of frozen `scala3-control-macros` checkpoint
`ec4eb279e` (2026-07-15); regression gap reported as P2 on 2026-07-15.

**Symptom/reproduce:** place `scala.util.boundary.break(value)` in a
`direct.reset` body, including a pure prefix before a later `direct.shift` or a
captured suffix. The macro may move the call below `Eff.defer` or a generated
continuation. When the resulting computation runs, its source `boundary` delimiter
has already returned, so the break escapes through delayed code instead of
retaining Scala's lexical boundary semantics.

**Root cause/plan:** the current external-return audit recognizes `Return` trees
but not Scala's library-level boundary control marker. M1 cannot prove that any
`boundary.break` call remains dynamically enclosed after defer/CPS movement, so
reject every such call conservatively with a stable direct-style diagnostic at
the exact break invocation. Keep returns local to a nested method accepted, and
add pure-prefix/suffix negatives proving no raw boundary exception or quote error
leaks.

**Implementation/verification:** the pre-lowering control audit recognizes the
exact `scala.util.boundary.break` overload symbols both as ordinary calls and
through inline provenance, then rejects at the invocation before `Eff.defer` is
constructed. Pure-body, captured-suffix, imported method alias, explicit-label,
module-alias, and transparent-inline provenance regressions are committed and pass
in the clean 26/26 diagnostics suite; the full leaf is 109/109 and the complete
package/catalog/conformance gate is green. Keep this entry open until rereview
approves and the fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a captured suffix cannot defer boundary break`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-transparent-inline-position — wrapper diagnostic points at reset
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt scala3ControlApi/test
     fixed-in: a8f321d5c -->

**Status:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported as P1 by the
rereview of frozen `scala3-control-macros` checkpoint `ec4eb279e` (2026-07-15).

**Symptom/reproduce:** invoke a transparent-inline wrapper around
`direct.shift` inside `direct.reset`. The transform correctly rejects the inline
expansion, but its primary position is the enclosing `direct.reset` rather than
the wrapper invocation, so the diagnostic does not identify the unsupported
source construct.

**Root cause/plan:** inline-boundary rejection reports the moved/body tree span
after compiler wrapping has widened it instead of retaining the nearest
`Inlined.call` source position. Track the closest provenance-bearing inline call
while descending and report `DIRECT_STYLE_UNSUPPORTED` there. Preserve the
existing unexpanded-inline application path and freeze exact message, line
content, and zero-based column for both shapes.

**Implementation/verification:** inline traversal keeps only non-empty
`Inlined.call` positions on a nearest-first stack and falls back to the marker when
the compiler supplies no call span. The transparent-inline regression reports the
wrapper invocation exactly; the earlier unexpanded-inline path remains unchanged.
Both pass in the 21/21 clean diagnostic suite; keep this entry open until rereview
approves and the fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a transparent inline wrapper reports its invocation position`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-nested-shift-body-marker — direct marker survives inside ShiftBody
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: a8f321d5c -->

**Status:** FIXED — closed 2026-08-09 by `verify-scala-direct-family` (release `3700867ba`), which confirmed `a8f321d5c` is an ancestor of `origin/main` and `sbt scala3ControlApi/test` 165/165. That release flipped the header to `fixed` without a `fixed-in:`, leaving `bugs-index-gate` RED on `main`; the sha here is the one THIS entry already named in its own prose. Original note, kept:

**Was:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported as P1 by the
rereview of frozen `scala3-control-macros` checkpoint `ec4eb279e` (2026-07-15).

**Symptom/reproduce:** write an accepted block-level `direct.shift`, then place a
second exact `direct.shift` inside the outer marker's rank-2 `ShiftBody`. The outer
body is currently treated as wholly opaque, so the nested marker survives macro
lowering and later fails through a raw compile-time-only/ownership path rather
than a stable M1 diagnostic.

**Root cause/plan:** the shift-body exemption distinguishes its rank-2 lambda from
an ordinary crossed callback, but it also skips the invariant that no direct
marker may remain in the emitted tree. Scan the opaque body specifically for exact
managed direct markers and reject them at their source call with a stable
`CAPTURE_BARRIER`/`DIRECT_STYLE_UNSUPPORTED` diagnostic. Do not reject ordinary
explicit `scalascript.control.shift`/`Eff` code or a separately managed nested
`direct.reset`; add all three regression shapes.

**Implementation/verification:** every accepted marker now receives a narrow
ShiftBody survival scan. It rejects only the exact nested `direct.shift`, skips a
separately managed nested `direct.reset`, and leaves ordinary explicit
`scalascript.control.shift`/`Eff` code untouched. Exact negative plus both positive
families pass across the 16/16 semantics and 21/21 diagnostics suites; keep this
entry open until rereview approves and the fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a direct marker nested in ShiftBody fails at the inner call`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-dependent-prefix-type-owner — freshened values retain stale type refs
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: a8f321d5c -->

**Status:** FIXED — closed 2026-08-09 by `verify-scala-direct-family` (release `3700867ba`), which confirmed `a8f321d5c` is an ancestor of `origin/main` and `sbt scala3ControlApi/test` 165/165. That release flipped the header to `fixed` without a `fixed-in:`, leaving `bugs-index-gate` RED on `main`; the sha here is the one THIS entry already named in its own prose. Original note, kept:

**Was:** open; remediation remains green at feature checkpoint `a8f321d5c`, but
a fresh independent review and the landing SHA are pending. Reported as P1 by the
rereview of frozen `scala3-control-macros` checkpoint `ec4eb279e` (2026-07-15).

**Symptom/reproduce:** create a local prompt scope before capture, then declare a
dependent value such as `Prompt[innerScope.Key, Int]` (or a value typed with
`owner.type`) and use it after `direct.shift`. Term references in initializers are
freshened, but the declaration's `tpt.tpe` still refers to the old local symbol;
Scala emits a raw `E007`/owner error from generated code. The common nested local
prompt shape therefore fails despite ordinary strict locals being advertised as
supported.

**Root cause/plan:** prefix freshening substitutes term trees only and reuses the
original quoted type tree unchanged. Rebind dependent/singleton type references
to the fresh symbols before creating each new declaration, preserving declaration
order, mutable/given/pattern flags, and shared-cell behavior. Support the common
local nested-prompt case; if a type shape cannot be soundly rebound in M1, fail
closed at the declaration with stable `DIRECT_STYLE_UNSUPPORTED`, never a raw
quote/type error. Add semantic coverage for dependent prompt and owner-singleton
flow plus diagnostics for any deliberately unsupported shape.

**Implementation/verification:** prefix cloning now moves the initializer first,
rebuilds affected `Select` and type trees, recursively rebinds supported
dependent/singleton `TypeRepr` paths, and fails closed for a dependent lambda type
whose binder graph M1 does not clone. The semantic regression carries a local
prompt plus `owner.type`, dependent mutable/given/pattern values, and a suffix
ascription across capture; the diagnostic regression freezes the unsupported
polymorphic case. Focused suites pass 37/37 and the full leaf passes 99/99; keep
this entry open until rereview approves and the fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `a8f321d5c` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a generic wrapper cannot erase the owner's path-dependent type`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-lazy-marker-eager — lazy marker initializer is lowered eagerly
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt scala3ControlApi/test
     fixed-in: 71e30a599 -->

**Status:** open; remediation and regressions are green on the feature branch
(2026-07-15), but fresh independent rereview and the landing SHA are pending.
Reported as P1 by the independent `scala3-control-macros` review of frozen
checkpoint `fa992fd92`.

**Symptom/reproduce:** an unused `lazy val selected = direct.shift(...)` inside
`direct.reset` increments a counter in the shift body even though ordinary Scala
would never force the initializer. The computation returns its unrelated tail
value, but the counter is already one.

**Root cause/plan:** the block-level marker search accepts every non-mutable
`ValDef`, including `Flags.Lazy`, before the nested-marker traversal can classify
the lazy initializer as a capture barrier. Exclude lazy bindings from the accepted
marker form so the existing barrier walk reports exact `CAPTURE_BARRIER`. A strict
lazy declaration that would remain live across a later capture is separately
outside M1 and must fail closed rather than be moved or forced.

**Implementation/verification:** lazy marker declarations are excluded from the
accepted marker bind, so traversal reports the frozen lazy-initializer
`CAPTURE_BARRIER`; an ordinary lazy prefix before a later capture is rejected
without forcing it. Both regressions pass in the 16/16 clean-compiled diagnostic
suite; keep this entry open until rereview approves and the fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `71e30a599` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a marker in a lazy binding remains behind the lazy capture barrier`. **Read in full and confirmed to assert this entry's symptom.**

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## scala-direct-prefix-owner-split — local declarations lose ownership across capture
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: sbt scala3ControlApi/test
     fixed-in: 71e30a599 -->

**Status:** open; remediation and regressions are green on the feature branch
(2026-07-15), but fresh independent rereview and the landing SHA are pending.
Reported as P1 by the independent `scala3-control-macros` review of frozen
checkpoint `fa992fd92`.

**Symptom/reproduce:** declare a local `val`, `var`, `given`, destructuring bind,
method, class, or type before `direct.shift`, then reference it from the shift body
or captured suffix. The macro fails with a raw `reference ... was used outside the
scope where it was defined` compiler error. The same failure occurs for a value
between sequential shifts. Existing shared-heap coverage used an external variable
and did not exercise the local cell that actually crosses capture.

**Root cause/plan:** the lowering moves prefix statements separately from the
generated continuation and only substitutes the marker result; it neither clones
strict local value symbols into the generated owner nor rewrites later references.
Clone/rebind ordinary strict `val`/`var`/`given` symbols in declaration order,
including pattern-generated synthetic `ValDef`s, so Scala performs its normal
closure boxing for a shared mutable cell. Until M1 models richer definition
ownership, fail closed when a local method, class, type, or lazy cell crosses a
capture. Add semantic and exact-diagnostic regressions before rereview.

**Implementation/verification:** capture splits now clone strict local value
symbols in declaration order and carry their replacement map through prompt,
shift body, suffix, and sequential markers. Scala closure conversion therefore
shares one captured mutable cell. Local method/class/type/lazy crossings reject
with exact diagnostics. The expanded semantics suite passes 14/14 and diagnostics
16/16 after a clean test compilation; keep this entry open until rereview approves
and the fix lands.
**VERIFIED FIXED 2026-08-09.** This entry said *"remediation is green on the feature branch, but
fresh independent rereview and the landing SHA are pending"*, and it has said so since 2026-07-15.
**The landing happened.** `71e30a599` is an ancestor of `origin/main`
(`git merge-base --is-ancestor` succeeds), so the pending half that was about code is resolved.

Pinned by `a lazy local cannot cross a later capture`. Matched by SUBJECT, not read line by line — see the caveat below.

`sbt scala3ControlApi/test`: **165 tests, 165 passed, 0 failed**, on `origin/main` today.


## js-control-direct-shorthand-value-symbol-capture — property symbol hides suffix capture
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: npm test (v2/host/js/control-direct)
     fixed-in: ec95c4c65 -->

**Status:** open; repair candidate `ec95c4c65` is locally verified and awaits fresh
independent review plus landing. Reported as P1 by independent rereview of exact
frozen HEAD `c4377fabb` on 2026-07-15 (current rebased equivalent `58de23cf1`).

**Symptom/reproduce:** inside a shift body, save or evaluate a closure/expression
reading `({ later }).later`, then declare suffix `const later = 42`. The current
lexical scan calls `checker.getSymbolAtLocation` on the identifier in the shorthand
property and receives the property symbol rather than the referenced value symbol.
The crossing is missed; transformed execution throws `ReferenceError` instead of
the original lexical behavior.

**Required fix/verification:** centralize runtime-value symbol lookup and use
`checker.getShorthandAssignmentValueSymbol` for shorthand property names, falling
back to ordinary checker identity everywhere else. The shift-body shorthand must
fail file-atomically with one `JS_DIRECT_CAPTURE_BARRIER` on the source identifier;
ordinary property names, genuine shadowing, and type-only references remain
accepted. Add assignment-initializer shorthand coverage where the compiler AST
permits that form.

**Root cause/fix candidate:** `getSymbolAtLocation` returns the synthesized property
symbol for `ShorthandPropertyAssignment`. The candidate uses
`getShorthandAssignmentValueSymbol` in the same runtime-value resolver used by
marker ownership and continuation checks. Real JavaScript property and assignment-
initializer regressions now select `later`, emit one capture diagnostic, and retain
the untouched file when diagnostics are ignored.
**VERIFIED FIXED 2026-08-09.** The entry says the repair candidate *"is locally verified and awaits
fresh independent review plus landing"*. **The landing happened:** `ec95c4c65` is an ancestor of
`origin/main`. What dangles in the entry is the frozen review HEAD it cites, not the fix.

Pinned by `shorthand value symbols cannot hide a suffix capture`. **Read in full.** The test is this entry's reproduction verbatim — `({ later }).later`
inside a shift body followed by a suffix `const later = 42` — and it ALSO covers the
assignment-initializer form `({ later = 0 } = {})`, which this entry asked for separately under
*"add assignment-initializer shorthand coverage where the compiler AST permits that form"*.

`npm test` in `v2/host/js/control-direct`: **39 tests, 39 pass, 0 fail.**

⚠ **THAT SUITE LOOKS BROKEN IN A FRESH WORKTREE AND IS NOT.** `node_modules` is gitignored, so the
first run there fails with *"compatible TypeScript compiler API not found … Cannot find module
'typescript'"* — a fact about the checkout, not about the code, and it reads as a defect in whatever
you are holding. `npm ci` in that directory first.


## js-control-direct-forward-lexical-capture — shift body escapes declarations moved into the suffix
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: c19d42401 -->

**Status:** FIXED — closed 2026-08-09 by `verify-scala-direct-family` (release `3700867ba`), which confirmed `c19d42401` is an ancestor of `origin/main` and `sbt scala3ControlApi/test` 165/165. That release flipped the header to `fixed` without a `fixed-in:`, leaving `bugs-index-gate` RED on `main`; the sha here is the one THIS entry already named in its own prose. Original note, kept:

**Was:** open; cumulative repair candidate `c19d42401` with marker-layer closure
`4c6b8e2a9` is locally verified and awaits fresh independent review plus landing.
Reported as P1 on 2026-07-15 by the independent pre-integration review of frozen
direct-transform snapshot `f6fa34fac`.

**Symptom/reproduce:** inside `direct.reset`, let a shift body save a closure that
reads `const later = 42`, with `later` declared after the marker. TypeScript and the
transform report no diagnostics, but lowering leaves the shift body outside the
generated `.flatMap` callback while moving `later` inside it. Running the saved
closure throws `ReferenceError: later is not defined` instead of returning `42`.
References to the marker's own declaration region have the same scope hazard,
including references hidden in nested closures.

**Required fix/verification:** specify and implement a sound rule: either preserve
binding/evaluation semantics through exact dependency-aware rebinding, or fail closed
before emit. Cover forward and own-marker references, nested closures, shadowing,
and declaration-initializer evaluation order; the accepted cases must remain
prefix-once/suffix-per-resume.

**Root cause/fix candidate:** the closed grammar rejected structural frame barriers
but did not compare value references with bindings moved across generated
continuations. Checker-symbol scans now reject own/later references in each marker
layer, including nested syntax, while retaining type-only, preceding, and shadowed
cases.
**VERIFIED FIXED 2026-08-09.** The entry says the repair candidate *"is locally verified and awaits
fresh independent review plus landing"*. **The landing happened:** `c19d42401` is an ancestor of
`origin/main`. What dangles in the entry is the frozen review HEAD it cites, not the fix.

Pinned by `shift bodies fail closed on own or forward lexical capture`. Matched by subject rather than read line by line.

`npm test` in `v2/host/js/control-direct`: **39 tests, 39 pass, 0 fail.**

⚠ **THAT SUITE LOOKS BROKEN IN A FRESH WORKTREE AND IS NOT.** `node_modules` is gitignored, so the
first run there fails with *"compatible TypeScript compiler API not found … Cannot find module
'typescript'"* — a fact about the checkout, not about the code, and it reads as a defect in whatever
you are holding. `npm ci` in that directory first.


## scljet-update-ipk-does-not-move-rowid — `UPDATE t SET <ipk>=N` rewrites the column but leaves the rowid
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`v2-f5b-typed-locals` batch C). An `INTEGER PRIMARY KEY`
assignment now MOVES the row, as the reporter's "fix sketch" prescribed. Implementation
(`scljet/sql.ssc`):

- `EditRow` carries `newRowid` alongside `rowid`; `targetRowidOf` reads the assigned IPK value.
- `executeUpdate` `ipkNormalizeRows` **before** the WHERE (that half is the sibling entry
  `scljet-update-ipk-column-silently-ignored`), and passes `tableIpkIndex` down.
- `applyUpdates` deletes **every** old rowid before inserting **any** new one. Per-row
  delete-then-insert corrupts a swap (`1→2` together with `2→1`): the second delete would remove the
  row the first insert just placed.
- Collisions are refused **before** anything is written, so a rejected UPDATE leaves the image
  untouched — two moved rows landing on one rowid, or a move onto a row that is not itself moving:
  `UNIQUE constraint failed: rowid N already exists`. A non-integer target is refused
  (`datatype mismatch: INTEGER PRIMARY KEY must be assigned an integer`) rather than coerced.
- The indexed path (`updatedSqlRows` → `reindexTable`) applies the same target rowid, so the
  statement cannot move the row on an unindexed table and silently not move it on an indexed one.

**Measured A/B** (the case would otherwise have proved nothing — see the note below): with the move
disabled in the staged tree, `UPDATE emp SET id = 5 WHERE id = 1` gives
`SELECT rowid, id, name` → `1|1|ann`; with the fix → `5|5|ann`, matching real SQLite's `5|ann|5`.
New conformance case `tests/conformance/scljet-update-ipk-moves-rowid.ssc` covers both statement
forms, the non-IPK update, the collision, and the non-integer target.

⚠️ The case originally projected `id` alone and I read an A/B as "the test is blind to the move" —
**wrong on two counts**: the patch had gone into the unused staged copy (`bin/lib/native-front/…`
instead of the `standard/…` one the launcher prefers), and `id` does follow the rowid on read. The
case now selects `rowid` explicitly so the two cannot be confused.

**Semantics NOT changed here (deliberate, and still divergent from SQLite):** scljet stores the IPK
value in the record; real SQLite stores NULL there and lets the rowid carry it. The whole corpus
(and `physicalBytes` in the address cases) is built on the current convention, so switching it is a
separate, file-format-level change.

**Historical report:** OPEN (found 2026-07-16 by the `scljet-ipk-rowid` lane, while verifying that the read
substitution `14f4da4ac` does not regress `UPDATE`). **Pre-existing write-path gap — NOT a
regression from that fix.** Low severity relative to the read bug: it needs an explicit `UPDATE` of
an IPK column, which is rare.

**Symptom** (measured, both engines, same statements):

```
CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO emp VALUES (1,'ann')
UPDATE emp SET id=5 WHERE name='ann'
                      → (rowid|id|name)
real SQLite           → 5|5|ann     ✓ the row MOVES to rowid 5
scljet (post-14f4da4ac) → 1|1|ann   ✗ the rowid never moved
real SQLite reading scljet's file → 1|1|ann   (agrees with us about what the file says)
```

**Root cause.** In real SQLite an IPK column IS the rowid, so assigning to it moves the row to a new
rowid (and fails on a duplicate). scljet's `executeUpdate` treats the IPK as an ordinary column: it
rewrites the record field and leaves the rowid alone.

**Why the read fix improves this rather than breaking it.** Before `14f4da4ac` scljet reported the
stored column (`id=5`) while real SQLite reading the very same file reported the rowid (`id=1`) —
the two engines *disagreed about our own file*. Now both say `1`: we are honestly reporting what the
file contains. The remaining bug is that the file is not what SQLite would have written. Pinning
this in a test therefore requires deciding the intended semantics first (move the rowid), not just
asserting today's output.

**Fix sketch.** In `executeUpdate`, detect an assignment to the IPK column (`tableIpkIndex`) and
re-key the row: delete + reinsert under the new rowid, erroring on a duplicate exactly as a
duplicate `INTEGER PRIMARY KEY` does today via `leafInsertCell`. Also update any index entries,
which store the rowid as their tail.

## js-control-npm-license-omitted — package tarball lacks Apache license
<!-- status: fixed
     kind: apparatus
     lane: multi
     area: build
     fixed-in: 1497623b5 -->

**Status:** done in `0d0ffcfd3`; confirmed closed by independent second
pre-integration review. Originally reported as a P2 packaging defect on
2026-07-15; affected pre-land package commit `2a34d7ed3` and verification
`c53294fa7`.

**Symptom/reproduce:** run `npm pack --dry-run --json` in
`v2/host/js/control`. The package contains only `README.md`, `index.d.ts`,
`index.js`, and `package.json`; consumers do not receive the repository's Apache
2.0 license text.

**Root cause:** the package's explicit `files` allow-list omitted a package-local
copy of the repository license, and the original four-file pack oracle encoded the
omission as success.

**Fix/verification:** the repository Apache 2.0 text is copied byte-for-byte into
the package and included in the exact allow-list. The package test compares both
files; `npm pack --dry-run --json` reports exactly five entries, including the
10,837-byte `LICENSE`, with no bundled dependency.

## js-control-effect-owner-type-collision — descriptor ID is mistaken for owner identity
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: cf8f96200 -->

**Status:** done in reachable `origin/main` landing `cf8f96200`; the independent
rereviewer confirmed both inferred and explicit union-owner rejection. Reopened as
P1 on 2026-07-15 after the second pre-integration review rejected the first repair.

**Symptom/reproduce:** two `defineEffect("same.id")` calls create distinct runtime
owners, but both declarations currently produce `Effect<"same.id">`. TypeScript
therefore accepts handling an operation from the first key with the second key as
`Eff<never, A>`; runtime correctly forwards the request as unhandled.

**Root cause:** `Effect` carried only its stable descriptor literal. TypeScript
cannot generate a fresh phantom type for each ordinary function call, so distinct
runtime keys collapsed to the same declaration type.

**First fix (insufficient):** `Effect<Id, Owner>` carries a named `unique symbol`
owner supplied to `defineEffect(id, owner)`; inline and widened symbols are
rejected. Runtime registration is idempotent for one owner+descriptor and rejects
descriptor conflicts, aligning the phantom with authority. Positive handler
inference, cross-owner negative/residual typing, and same-ID runtime forwarding all
pass.

**Second-review repro:** let `CollapsedOwner` be
`typeof FirstOwner | typeof SecondOwner`, and let ordinary cast-free functions
return that union. The current guard rejects only the broad `symbol`, so inference
or an explicit `CollapsedOwner` type argument accepts both calls and gives them the
same `Effect<Id, CollapsedOwner>`. A wrong-owner handler again typechecks as
`Eff<never, A>` while runtime owner matching forwards the request.

**Second fix/verification:** private `IsUnion` and `SingleUniqueSymbol` guards now
reject both inference-only and explicit-generic union owners, while stable named
owner reuse remains positive. `npm run typecheck` passes all original fixtures and
the exact cast-free second-review repros.

## descriptor-v3-effect-header-evidence-misbinding — comments and same-name objects corrupt effect evidence
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     gate: v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala
     fixed-in: f4d4c01ec -->
**RE-MEASURED 2026-08-08, and the symptom does not reproduce.** A `/* effect Phantom: */` comment before a valid `effect Real` no longer changes the verdict — the projection is INVARIANT under it. Newly gated, and the only one of the four this suite did not already cover: "a comment before an effect does not change the verdict". The test asserts invariance rather than a verdict, because which way the projection goes is a separate question the entry does not settle.

Measured, not inferred: the "landing SHA pending independent approval" this cluster cites,
`f4d4c01ec`, has been on `main` since the day the entries were written, and nothing since had asked
whether any of them still reproduces. Closing on a measurement rather than on the age of the report.


**Status:** open (2026-07-15). Reported by the independent Slice B re-review
(`/root/descriptor_b_rereview`); fix SHA pending.

**Symptom/reproduce:** inserting `/* effect Phantom: */` before a valid
`effect Real` changes a successful projection into
`UNSUPPORTED_PUBLIC_DECLARATION`, so comments/body text are not invariant. An
ordinary unexported `Left.Choice` object encountered before an exported
`Right.Choice` effect can also consume the latter's erased source header and make
the real effect fail with missing evidence.

**Root cause/plan:** effect headers are found by a raw line regex and then assigned
to transformed objects by bare-name preorder. Replace the scan with a
comment/string-aware lexical projection, bind evidence to the structurally marked
effect candidate and lexical owner/order, and fail closed when an empty same-name
effect cannot be bound unambiguously. Add both faithful regressions.

**Baseline:** focused producer suite reproduces both failures: the comment/string
fixture reports the phantom header, and the ordinary object leaves the real effect
without evidence (`18/25` total green before the fix).

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` binds lexically scrubbed evidence to the exact effect owner.
Focused producer 46/46, descriptor 27/27, core 1092/1092, interop 36/36, IR,
artifact ABI 73/73, and affected conformance 2/2 are green. Status remains `open`
until fresh independent approval and landing on `origin/main`.

## jvm-bytegen-letrec-env-clobber — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: 956b42539
     confirmed: no -->

**Status:** fixed in `956b42539`; awaiting reporter confirmation. Found by the
stack-safety focused VM/direct-ASM vector while qualifying effectful `While`
lowering.

**Symptom:** a non-tail local `LetRec` can corrupt a surrounding expression's
lexical environment on direct ASM. The effectful-loop witness completes its
handler, then a sequence suffix reading the outer long cell fails with
`ClassCastException: Value$ClosV cannot be cast to Value$LongCellV` at generated
`Entry.lam$2`; the VM returns the expected cell value.

**Reproduce:** generate direct ASM for an outer `Let(lcell.new(0), ...)` whose
first non-tail expression contains a local `LetRec` and whose following suffix
evaluates `lcell.get(Local(0))`. The effectful-While lowering in
`PortableEffectsStackSafetyTest` is one faithful witness; add a smaller generic
non-tail-`LetRec` plus outer-local-read regression as the independent guard.

**Root cause:** `JvmByteGen.gen(Term.LetRec)` stores the tied
`captured ++ closures` frame into JVM local slot 0 while generating the LetRec
body. It restores the Scala emitter's slot/target metadata afterward, but never
restores the runtime env array. A later argument or sequence chain therefore
receives an extra closure at the end of its frame, changing every De Bruijn
lookup.

**Fix/verification:** direct ASM now saves the caller env in a private JVM local,
installs the tied frame only for the `LetRec` body, and restores slot 0 while
leaving the expression result on the operand stack. Both residual-forwarding
handler-root filters remain on the pending and body target maps. The generic
non-tail-`LetRec` outer-local regression and the 20,000-iteration effectful
`While` vector pass on VM/direct ASM; the installed axis-20 ASM lane also returns
exact `100000`, `100000`, `20007`, `20000`.

## scala3-control-effect-key-row-elimination — FIXED / awaiting confirmation (2026-07-14, Codex)
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: 528d73af3
     confirmed: no -->

**Status:** fixed in `528d73af3`; awaiting reporter confirmation. Found by the `api_type_design`
implementation audit against the uncommitted `scala3ControlApi` reference model;
reported by codex-interop in the `scalascript` rozum room.

**Symptom:** `EffectKey[+Fx]` and public `named[Fx](id, witness)` permit distinct
runtime keys for the same static `Fx`. `handle[Fx, Nothing, ...]` matches one key,
forwards an operation using the other, yet returns `Eff[Nothing, ...]`. Covariance
also permits one key to masquerade as a key for a union row and falsely remove every
member.

**Reproduce:** create `k1` and `k2` for one effect type, perform an operation whose
`effect = k2`, handle that effect type with `k1` and `Residual = Nothing`, then pass
the accepted result to `Eff.runPure`. The current implementation reaches the
forwarded request despite the empty static row.

**Fix/verification:** `EffectKey` and `Operation` are invariant, each public key is
owned by one exact singleton witness, and runtime matching uses that owner identity.
The same owner with a conflicting descriptor is rejected. Compile-time regressions
reject `Nothing`, generic-wrapper, and union-key narrowing; runtime regressions prove
same-owner equivalence and distinct-owner forwarding. The full 39-test suite is green.

## control-companion-relative-links — FIXED (2026-07-14, Codex)
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: 96fc5adfb -->

**Status:** fixed in `96fc5adfb`; found by `final_control_spec_audit` while
checking the Scala 3 bidirectional-control companion documents.

**Symptom:** links in `specs/algebraic-effects.md` and `specs/coroutines.md` target
`../direct-syntax.md` and, in the former, `../error-handling.md`. Those files are
under `docs/`, so rendered navigation from both specs is broken.

**Reproduce:** resolve each local Markdown target relative to its containing file;
`test -e specs/../direct-syntax.md` and `test -e specs/../error-handling.md` fail,
while the corresponding `docs/` paths exist.

**Fix/verification:** the targets now use `../docs/`; both destination files
exist, the changed companion/control links resolve, and Markdown lint passes.

## interp-jit-nested-match-duplicate-var — a nested `match` binding the same value-type miscompiles on the JIT
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-15, `JavacJitBackend.scala`). Two independent codegen
defects, both surfaced by a `match` nested inside another match's arm:

1. **Duplicate helper locals.** A nested match compiles to an IIFE lambda whose body
   re-declared the same helper locals (`inst`, `__fa_<ctor>`, `<bind>_a`, `tn`) as the
   enclosing match; Java forbids a lambda-body local from shadowing an enclosing-method
   local, so `javac` failed `variable inst is already defined`. FIX: a per-nesting-depth
   uniquifier (`GenCtx.nameSuffix` / `deeperMatch`) suffixes every emitted match helper
   local (`inst_1`, `__fa_Bin_1`, `a_a_1`, …). Depth 0 → empty suffix, so non-nested
   codegen is byte-identical. Depth strictly increases from an enclosing match to a
   nested one, so no ancestor/descendant scopes ever share a name.
2. **Unused ref binding extracted as `IntV`.** A named-but-unused pattern binding
   (`case Bin(l, r) => …` where `l`/`r` are unused `E` values) was eagerly extracted as
   `long l_a = ((IntV) __fa_Bin[0]).v()` — a `ClassCastException` on a ref field. Pre-fix
   this was masked in production (the runtime caught the CCE and fell back to tree-walk)
   but wasted every JIT attempt. FIX: `bindingReferenced` treats an unreferenced binding
   as a wildcard, so no local is emitted for it.

Empirically the javac error did NOT "error out the call" (see original report below) —
the runtime swallowed it and ran the tree-walk tier, so results were always correct; the
bug was a silent loss of JIT for nested matches. Verified: `SscVmTest` two new cases
(single-nested + triply-nested on the same param) JIT-compile as `ObjToLong` and return
the right values; full `backendInterpreter/test` green, 0 regressions.

<details><summary>original report (2026-07-14)</summary>
open. Interpreter JIT (JavaC backend, the default tier) codegen bug; workaround = avoid
the nested match.

**Symptom:** a function whose body has a `match` nested inside another `match`'s
case, where BOTH scrutinees are cast to the same runtime `Value` subtype (e.g.
`InstanceV`), makes the JIT emit two `InstanceV inst = (InstanceV) …;` locals in one
Java method → `javac` fails with `variable inst is already defined in method …`.
(The whole call was believed to error out; in fact the runtime bails to tree-walk.)
Minimal repro (SclJet `SqliteValue` = a sealed trait of case classes):

```scalascript
def cmp(a: SqliteValue, b: SqliteValue): Int =
  a match
    case SqlInteger(x) => b match      // <-- inner match on `b`, same subtype family
      case SqlInteger(y) => 0
      case _ => 1
    case _ => 2
```
(Note: `cmp`'s two ref params make it bail on the "both params ref-typed" cliff before
codegen; the collision reproduces on a single-ref-param function whose arm re-matches
the same param, e.g. `x match { case Bin(l,r) => x match { … } }`.)

**Workaround:** split each match into its own single-level helper so no method has
two same-subtype casts — see `integerOf`/`textOf` used by `compareKeys` in
`scljet/write.ssc`. The tree-walk tier (`SSC_JIT_BYTECODE=off`) is unaffected.
</details>

## compile-jvm-and-std-root-disagree-on-where-std-lives — one lane out of three refused a `std/` import
<!-- status: fixed
     lane: multi
     area: build
     kind: bug
     gate: tests/e2e/std-import-lanes-gate.sh
     fixed-in: 7e04a4775 -->

**FIXED 2026-08-03.** The same file, the same import, three lanes:

```
run --v1     imported ok
run-js       imported ok
compile-jvm  auto-resolve: cannot resolve import 'std/http.ssc' (looked at examples/std/http.ssc)
```

**Two layers, and the outer fix alone does nothing.**

1. `AutoResolve` — the JVM lane's dependency walker — resolved a bare `std/foo.ssc` that misses
   relative to the importing file under `ImportResolver.libPath` **only**. `libPath` is whatever the
   launcher passed as `-Dssc.lib.path`, and every `bin/ssc*` passes the REPO ROOT. A dev tree keeps
   its std at `v1/runtime/std`, so the root has no `std/` and the lookup failed. Fixed by consulting
   `stdPath` as well — `libPath` still goes first, so nothing that resolved before stops resolving.

2. That change was **inert**, because `stdPath` was `libPath`. `ImportResolver.discoverStdRoot`
   documents itself as returning "the directory that *contains* a `std/` subdirectory" and filters
   rules 4 and 6 with `hasStd` — but not rule 3, `lib`. So with the launcher always setting
   `ssc.lib.path`, rule 3 always won, and rules 4-6 were unreachable in every dev-tree run —
   including rule 5, the `runtime/std` ancestor walk that exists for exactly this layout. Measured
   before and after:

   ```
   before   stdPath = Some(<repo>)            ← has no std/
   after    stdPath = Some(<repo>/runtime)
   ```

**Why the unit tests did not catch layer 2.** `StdRootResolutionTest` has a dev-walk-up case, but it
passes `lib = None`; every other case builds `lib` with `withStd("lib")`. The only shape never
exercised was the production one — `lib` set, `std/` not under it — which is precisely the shape
where a filtered and an unfiltered rule 3 differ. Two cases added: a lib root WITHOUT `std/` must
lose to the dev tree, and one WITH `std/` must still win, so the fix cannot be read as "ignore lib".

**Blast radius.** This took out the JVM lane of `components-smoke`, `middleware-smoke` and
`upload-smoke`, all three of which reported `the server process EXITED before it listened` — a
serving symptom for a resolution cause. `components-smoke` now gets as far as
`JVM artifact written` and fails later, on something else.

**A THIRD copy of the same mistake is still open**, filed separately as
[[bundle-command-resolves-imports-relative-only]]: `BundleCommand` has no library fallback at all
and silently prints `[warn] import std/http.ssc … — not found, skipped`.

## bundle-command-resolves-imports-relative-only — CORRECTED: the behaviour was right, the diagnostic was wrong
<!-- status: fixed
     lane: multi
     area: build
     kind: bug
     gate: tests/e2e/bundle-smoke.sh
     fixed-in: f1032f1b3 -->

**CORRECTED AND FIXED 2026-08-04. The premise of this entry, as I first filed it, was wrong.** It
read the bundler's `[warn] import std/http.ssc … — not found, skipped` as a defect that dropped std
modules from the archive. Not packing them is **correct**: a platform import resolves at the
CONSUMER from their own ssc install, exactly as `run` resolves one for any file outside the tree.
Measured rather than argued — bundle a file that imports `std/http.ssc`, unpack to a temp dir with
no `std/` anywhere in the archive, run it there:

```
$ unzip -l p.sscpkg          manifest.yaml, sources/_bundle-probe.ssc      (no std/)
$ ssc-tools run --v1 …/sources/_bundle-probe.ssc
app ran, std import resolved
```

What was actually wrong is what the bundler SAID: `not found, skipped` reports a working platform
import as a broken one, and it is the reason this entry was filed at all. The warning now fires only
for genuinely unresolvable imports; platform ones are counted and reported as
`N platform import(s) not packed`, so the summary still says they exist instead of leaving silence
to be read as "there were none".

**Three further defects surfaced while reducing it, all fixed in the same commit:**

1. **`bundle-smoke` asserted an archive layout that has not existed since v1.7 Tier 2.** It expected
   `bundle.yaml` at the root with entries beside it; the format is `manifest.yaml` plus a `sources/`
   prefix. The gate failed at its FIRST assertion, so nothing after it had ever run. It is an
   orphaned gate — nobody was told. All three cases pass now.

2. **The command's own class docstring documented that same dead layout**, which is where I read it
   from and why the first diagnosis went looking in the wrong place.

3. **The Tier 2 manifest dropped `entries:`.** The pre-Tier-2 `bundle.yaml` recorded which sources
   are entry points; the new one does not, so a consumer unzipping a multi-entry bundle cannot tell
   an entry from a transitive dep — everything is under `sources/` with nothing to distinguish
   them. Restored in the manifest rather than weakened in the gate: asserting less is not the same
   as the property being gone. Additive, since `SscpkgManifest.parseString` ignores unknown keys.

4. **`id:` in the manifest was an absolute path from the build machine.** `bundleId` came from the
   whole `-o` argument, so `-o /tmp/build-1234/app.sscpkg` wrote `id: /tmp/build-1234/app` as the
   package's IDENTITY. `SscpkgLoader` parses that field and `ssc plugin install` prints it back, so
   the builder's directory layout travelled inside the artifact. Now the basename: `id: cards`.

**Original filing, kept because the shape of the mistake is worth keeping:**

**Found 2026-08-03** while fixing
[[compile-jvm-and-std-root-disagree-on-where-std-lives]]; NOT the same code, and not fixed by it.

`BundleCommand.visit` resolves each import as `(file / os.up) / RelPath(imp.path)` and, when that
misses, prints a warning and **continues**:

```scala
val resolved = (file / os.up) / os.RelPath(imp.path)
if os.exists(resolved) then visit(resolved)
else System.err.println(s"  [warn] import ${imp.path} from ${file.last} — not found, skipped")
```

There is no `libPath`/`stdPath` fallback, so every `std/…` import is dropped from the archive. The
failure is a WARNING on a successful exit, which is the shape that keeps a defect alive: the bundle
is produced, it is incomplete, and the exit code says fine.

`bundle-smoke` still fails after the resolution fix above, at `bundle.yaml missing` and
`missing in archive: components-demo.ssc` — those may or may not be this; nobody has reduced them
yet, and this entry does not claim them.

**Not fixed here on purpose.** It is a different file with different symptoms, and the entry it was
found under already carries two layers of fix plus a gate. Copying the candidate-list from
`AutoResolve` is the obvious shape of the fix; what it needs first is a decision about whether a
bundle SHOULD carry std modules at all, which the warning's author may have had a reason for.

## js-userspace-long-arith-native-operator-mixes-bigint — no longer reproduces; the userspace workaround is removable (verified 2026-08-02)
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: unrecorded -->

**VERIFIED NOT REPRODUCING 2026-08-02, in the real harness and against the golden.** The workaround
below was removed and the case still passes:

- control first — `tests/conformance/scljet-sql-expr.ssc` on the js lane WITH the workaround: green;
- then `arithValue`'s call sites un-workarounded (`longAdd(x, y)` → `x + y`, and the same for
  `-`/`*`/`/`), which is exactly the shape this entry says throws, since `x`/`y` come from
  `intPayload` and are not provably `Long`;
- result: **all 30 lines byte-identical to the INT golden**, no `TypeError`.

**The mechanism is worth stating, because the premise of this entry is still TRUE.** `.toLong` on a
non-Long still compiles to identity — `def signedByte(b: Int): Long = b.toLong` emits
`return (b);` today, so the value really is a JS Number. What changed is the OPERATOR routing: an
operand the emitter sees as `Long` now goes through `_arith`/`_larith`, which coerce Number↔BigInt
(`v1-js-long-precision-and-bitops`, and `js-long-arith-no-64bit-wrap` for the 64-bit mask). Where
neither operand is Long-typed the emitter still writes a NATIVE operator — that general shape is
unchanged, it simply is not reached by this code any more. Three synthetic repros aimed at it
(mixed-branch `if`, mixed-branch `match`, declared and inferred return types) all produced
js == INT, so nothing is filed for it: an entry for a defect nobody can reproduce is noise.

**Actionable for scljet:** `longAdd`/`longSub`/`longMul`/`longDiv`/`longMod` in `scljet/sql.ssc` are
now dead weight at the `arithValue` call sites — measured, not assumed. Removing them is a
simplification for whoever owns that file; this entry is the evidence.

<details><summary>original report (superseded 2026-08-02)</summary>

**Status:** WORKED AROUND in userspace (`scljet/sql.ssc` `arithValue`). When a plain (non-effectful)
`def` does Long arithmetic like `val x = f(a); val y = f(b); x * y` and the compiler cannot *prove*
both operands are `Long`, the ssc→JS backend emits a **native** `(x * y)` / `Math.trunc(x / y)`
rather than the BigInt-safe `_arith('*', x, y)`. That is fine when both are BigInt, but scljet decodes
small table integers to JS **Numbers** (`record.ssc` `signedByte`'s `.toLong` compiles to identity),
while integer literals are **BigInt** — so `Number * BigInt` throws `TypeError: Cannot mix BigInt and
other types`. What defeats the compiler's Long proof here is a helper with a mixed body: an
`asLong`-style function whose `SqlReal` branch emits `Math.trunc(x)` (Number) and `SqlInteger`/`_`
branches emit BigInt makes the *result* look non-Long, so downstream `x op y` uses native operators.
**Workaround (reliable):** accumulate through a `var` seeded from a `0L`/`1L` **literal** — exactly what
`sumValues` does (`var s = 0L; s = s + a; s = s + b`) — which forces the `_arith` path (`_arith('+', s,
a)`), and `_arith` coerces Number↔BigInt. `longAdd`/`longSub`/`longMul`/`longDiv`/`longIsZero` in
`sql.ssc` do this; `_arith('/', …)` truncates toward zero (matches sqlite integer division). Also
`.toLong`/`.toDouble` on a value can compile to a no-op, so convert via the same `var d = 0.0; d = d +
x.toDouble` accumulation (as AVG does), not a bare `x.toDouble`. Verified: conformance
`scljet-sql-expr` [int, js] green (incl. `250/100 = 2`). Proper fix belongs in JsGen (prove Long across
helper returns, or emit `_arith` for any not-provably-Int operand as the non-CPS path already does).
</details>

## v2-native-table-model-contract-gaps — first Apple model draft diverges at four strict seams
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: d54d02126 -->

**Status:** done (2026-07-11, `d54d02126`); found by
`apple_table_impl_map` during the read-only implementation audit and confirmed
fixed by `nativeui-reviewer` in the `scalascript` Rozum room after three rounds.

- **Draft repro:** evaluate a fetch table in `loading` with last-good rows and
  an empty/stale body; the draft reparses and can replace the retained rows.
  It also admits Float through ordinary display/payload scalar conversion,
  validates table colors separately from shipped CSS colors, accepts a generic
  signal as a fetch source, and accepts a read-only refresh signal.
- **Action/model audit extension:** refresh must also be an Int/non-overflowing
  capability before transport. When a committed source update removes a row,
  its in-flight task plus action/edit slots must be cancelled/pruned immediately
  rather than waiting for SwiftUI `onDisappear`.
- **Decoder audit extension:** Foundation numeric `NSNumber` must be classified
  before Bool bridging so JSON `0`/`1` stays numeric; exact `yyyy-MM-dd` parsing
  must reject any trailing/normalized input by round-trip, not trust
  `DateFormatter.isLenient = false` alone.
- **Expected:** loading immediately retains the last-good set; ordinary display
  excludes Float and Field payloads allow only String/Int/BigInt/Bool; one
  bounded native color grammar serves CSS and table status; fetch metadata and
  writable refresh capabilities reject at descriptor decode.
- **Reconciliation invariant:** old/new typed row identities reconcile
  transactionally; deleted identities synchronously release table task/state.
- **Rozum implementation review round 1 (BLOCKED):** the green 40/40 suite and
  real iOS 16 typecheck do not close seven residual groups:
  1. row transport needs canonical descriptor signatures/current-capability
     authentication; a same-site replacement must update/cancel rather than be
     frozen by `StateObject`;
  2. payload kinds are action-specific (link/delete/edit cannot accept arbitrary
     modes), top-level edit is not a button, and edit requires its field/value;
  3. link targets are current writable String cells and refresh targets are
     current writable non-overflowing Int cells at preflight;
  4. finite Float must reach money formatting without passing the stricter
     ordinary-display scalar conversion first;
  5. initial fetch error without rows must render visible `Error:` status;
  6. every row map key must be String and init failures are bounded and sourced;
  7. the six probes must add Fields, token/base/header/content-type/scalar/
     overflow negatives, edit dedupe, descriptor replacement, row/unmount/
     deinit cancellation, and stale-completion rejection.
- **Rozum review round 2 preliminary residual:** owner/site plus descriptor
  signature alone does not authenticate the supplied row/action/slot. The
  runner must match a canonical action signature at the current slot and the
  typed identity in the current committed row set; arbitrary actions under a
  live table signature and old rows after replacement/removal must reject.
  Replacement itself commits only after both descriptor decode and candidate
  snapshot succeed, preserving the previous capability/model on failure.
- **Rozum review round 2 final additions:** row transport must call the same
  extracted URLSession/generation runner as ordinary fetch actions rather than
  duplicate its Task loop. `rowsPath` is exactly empty for static/signal and a
  valid non-empty dotted path for fetch when supplied (`a..b` never aliases to
  `a.b`). Model appearance is tracked even when initial decode fails so a later
  valid descriptor mounts; replacement must never pair retained old row cells
  with a changed column layout or remove the old capability before a coherent
  candidate commits.
- **Plan/done-when:** close all four seams before the first Apple table code
  commit, add them to the six named executable gates, and obtain Rozum reviewer
  confirmation with the complete slice.
- **Fix/verification:** one strict decoder/model/Grid implementation now owns
  transactional source snapshots, exact row/action capabilities, shared
  URLSession generation, deterministic columns, and synchronous stale-row/task
  disposal. Round-3 review found no remaining blocker or lifecycle leak.
  Independent and local runs both passed the named 6/6 and full Swift backend
  40/40, including generated macOS execution and iOS 16 strict typecheck.

## v2-swiftui-fake-native-fallbacks — deferred semantics render misleading content
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

**TRIAGED 2026-08-06 — `unknown` → `fixed`, and the evidence is stated with its limit.**

Every slice this entry lists landed with its own verification at the time (swiftc under `-swift-version 6 -strict-concurrency=complete -warnings-as-errors`, `v2SwiftBackend/test` 58/58), and the invariant those slices exist to protect — an unmapped thing REFUSES rather than renders something plausible — was re-checked today at source: `SwiftNativeUiApple.scala` has **34 explicit `unsupported(...)` refusals and no `case _` / `case other` branch at all**, so there is no default path that could silently render.

**What was NOT done, said plainly:** a fresh end-to-end SwiftUI render. It is blocked by an unrelated regression found in the same triage — `v2/BUGS.md swift-macos-build-broken-by-forJsonView` — which fails the macOS build before any rendering happens. Marked `fixed` rather than `open` because nothing here is left to IMPLEMENT; what is missing is a confirmation run, and pretending otherwise would put phantom work on the board.

**Reactive-attribute slice landed (opus, 2026-07-13).** `renderElement` bound only 3
reactive (signal-bound) attributes — `style`/`value`/`checked` — so ANY other
`NativeUiSignal`-bound attribute fell into the `"reactive attribute X is not mapped"`
Unsupported stub. Extended the reactive allowlist to the accessibility/state attributes
with a faithful SwiftUI mapping: `disabled`→`.disabled()`, `aria-disabled`, `title`→`.help()`,
`aria-label`→`.accessibilityLabel`, `required`, `aria-modal` (via a new
`reactiveAttributes` set + `reactiveAttributeDiagnostic`; each is read as the signal's LIVE
value and re-applied on change, reusing the existing style/value/checked plumbing).
STRICT preserved: an attribute with no faithful native mapping, or a malformed signal
(e.g. an Int bound to `disabled`), still yields a sourced `NativeUiUnsupported`. Renderer-only
(`SwiftNativeUiApple.scala` + test) — did NOT touch `SwiftRuntime`/`SwiftNativeUiHost`/
content-toolkit/WebKit (the sibling's active files). Verified under
`swiftc -swift-version 6 -strict-concurrency=complete -warnings-as-errors`: `<div disabled={flag}>`
resolves the live `true` and applies `.disabled()`; a still-unmapped / Int-valued reactive
attribute stays Unsupported. `v2SwiftBackend/test` 58/58, 0 regressions.


**Semantic-table slice landed (opus, 2026-07-13).** The last inventoried element still
rendering as an Unsupported stub was the semantic HTML `<table>` family
(`table/thead/tbody/tr/th/td` — returned `unsupported("semantic table adapter pending")`).
Every other tag already rendered a real SwiftUI control (Text/heading, VStack/HStack,
Button, TextField/Toggle, Link, img, List, reactive DataTable→Grid). Now `<table>` renders
a real `ScrollView { Grid { GridRow … } }` (header row bold + `Divider`, cell CSS via the
existing `NativeUiStyles.apply`, `<th>` click events via `runEvents`), with strict decode:
malformed structure / a non-`th|td` cell / a bare `thead|tbody|tr|th|td` outside a table
still yields a sourced `NativeUiUnsupported`, never fake success. Renderer-only
(`SwiftNativeUiApple.scala`) — renders the `element("table", …, [thead,tbody])` shape both
`std/ui/lower.ssc` and content-toolkit already emit, touching neither. Verified end-to-end
under `swiftc -swift-version 6 -strict-concurrency=complete -warnings-as-errors`: the table
DataV decodes to real header/body Grid rows; malformed variants throw the strict diagnostic.
`v2SwiftBackend/test` 55/55 (was 54), 0 regressions.


**Status:** LARGELY FIXED by swift-sibling work since the report; one residual slice
closed by opus (2026-07-13). Audit (opus, 07-13, real Swift 6.3/Xcode 26.5): the fake
"Native data table" string is gone; the `render`/`renderElement` dispatch now routes every
unknown tag / malformed node / malformed list-map / unmapped reactive attr / unsupported
role / unsupported semantic attribute to a sourced `NativeUiUnsupported` (red Text +
accessibilityLabel), and `NativeUiStyles` validates unknown CSS keys AND invalid values.
Residual silent-ignore closed: 4 cosmetic props the std/ui toolkit emits — `box-sizing`,
`border-collapse`, `cursor`, `user-select` — were in `supportedProperties` but accepted ANY
value with no diagnostic (`cursor: banana` → silently swallowed). Now value-validated against
the standard keyword sets (else sourced Unsupported), mirroring the existing `overflow`
pattern (`SwiftNativeUiApple.scala`). Verified end-to-end: `emit-swift` → the generated
`NativeUiStyles.swift` carries `cosmeticNoOpValues`; compiled+run under
`swiftc -swift-version 6 -strict-concurrency=complete -warnings-as-errors` — bogus values now
diagnose, the 5 real toolkit values stay accepted; `v2SwiftBackend/test` 54/54, 0 regressions.
REMAINING (feature-scale, swift specialists): real actions/tables/WKWebView semantics + fetch
async lifecycle (the content-toolkit path deliberately `fatalError`s today — mid-refactor).
_Original report:_ found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** generate a root containing trusted HTML, a data table,
  an unknown semantic tag/style, or an unimplemented event. The draft renderer
  shows raw markup as `Text`, shows a fake “Native data table”, ignores the
  action/style, or converts a malformed list/map to empty output.
- **Expected:** implemented inventory entries have their exact native semantics;
  every deferred, malformed, or unknown semantic value becomes a deterministic
  sourced `NativeUiUnsupported` presentation. Silent ignore/fake success is
  forbidden.
- **Plan/done-when:** make the core renderer strict and use explicit Unsupported
  stubs for the separate actions/tables/WKWebView slice; add generated inventory
  and malformed-value gates before replacing each stub with real semantics.
  Inventory acceptance is behavioral, not string presence: CSS values,
  align/justify/position/inset/borders/white-space, semantic role/
  aria-disabled/required attributes, and malformed declarations must either
  map exactly or render sourced Unsupported. Fetch signals/actions also remain
  sourced Unsupported until the complete async lifecycle slice.

## v2-nativeui-root-transaction — failed Apple extraction leaks root/runtime state
<!-- status: fixed
     kind: bug
     lane: multi
     area: conformance
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** missing-root `__nativeUiTakeRoot` throws before clearing
  `appleContext`; duplicate registration retains the first root and signals.
  The current test reinstalls the plugin and masks leakage.
- **Expected/fix:** explicit begin/commit/abort transaction with cleanup or
  restoration for zero roots, duplicate roots, and evaluation failure.
- **Fresh review delta (Rozum 2026-07-11):** `emptyHeaders` is registered once
  at plugin install, but begin clears its `SignalKey` while the global retains
  the old cell. Make it root-local/lazy and test an omitted-header Apple root.
- **Fix/verified:** begin/take/abort and duplicate failure reset the same plugin
  instance; each Apple begin re-registers the constant header cell under its
  root key, including the omitted-header action path.
- **Done-when:** one plugin instance can fail then begin a clean extraction;
  zero/duplicate/evaluation-error tests prove rollback.

## v2-nativeui-portable-graph — canonicalization/equality can leak host values or miscompare cycles
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** DataV→MapV cycles point to an unconverted DataV; ClosV traversal
  mutates caller-owned environments; equality marks failed map-key candidates
  as visited/equal. Descriptor helpers retain raw values, and static table rows
  are not validated as String-keyed maps.
- **Expected/fix:** graph-safe non-mutating validation/canonicalization,
  tri-state or candidate-isolated cyclic equality, deep stable paths at every
  ABI constructor, and exact row/map validation.
- **Fresh review delta (Rozum 2026-07-11):** conversion still breaks a benign
  alias when an outer DataV and a closure env share the same portable MapV and
  an unrelated host map forces copying. Preserve that alias without changing
  ClosV identity or its environment.
- **Fix/verified:** validation is closure-context aware; conversion pins the
  closure-reachable portable subgraph, copies transitional host maps without
  alias loss, and equality backtracks cyclic unordered candidates soundly.
- **Done-when:** adversarial cycle/reorder negatives, nested ForeignV paths,
  closure non-mutation, and every descriptor family are green.

## parser-trysplitparse-quadratic-hang — `fixed` (2026-06-28)
<!-- status: fixed
     kind: perf
     lane: multi
     area: front
     fixed-in: unrecorded -->

- **Found by:** busi (phone-demo hub). A `/api/issue` route used `given` as a local val name: `val given = req.form.getOrElse("number", ""); val number = if given.length > 0 then given else …`. Loading the ~3500-line `demo_server.ssc` pegged one core at ~100% CPU and never bound (>90s); the *same* code in a tiny file instead fast-failed with `illegal start of definition`. (busi originally mis-attributed this to the `if <param> then <param> else …` shape and to a `View[Int]` — both red herrings; the trigger is purely the identifier name.)
- **Root cause:** `given` is a Scala-3 soft keyword, so scalameta rejects it as an identifier → in `Parser.parseScalaWithDiagnostic` BOTH the Source-mode parse and the `{…}` Term-mode parse fail → the `trySplitParse` fallback runs. That fallback tried EVERY split point (`lines.length - 1 to 1 by -1`), each re-parsing an O(N)-line `prefix` as `Source` plus a `suffix` as `Term`. For a large block that is O(N) parses over O(N)-line prefixes = **O(N²)** total. Confirmed size-driven, not single-parse-exponential: a 1010-line block ≈ 6s, a ~3500-line block ≈ 90s; a `jstack` mid-hang showed `main` in `Parser$.trySplitParse$…` → `…prefix.parse[Source]` → scalameta `argumentExprsInParens` recursion.
- **Minimal repro:** `val given = "x"; val number = if given.length > 0 then given else "z"` in a code block — a fast `illegal start` in a small file, a ~quadratic hang in a multi-thousand-line one. Renaming `given` → `gv` parses and runs fine.
- **FIXED (2026-06-28):** bounded `trySplitParse` to small trailing suffixes — `private val MaxSplitSuffixLines = 48`, range `lines.length - 1 to math.max(1, lines.length - MaxSplitSuffixLines) by -1`. The handler-file pattern this fallback targets (class defs + a trailing lambda) always has a short trailing term, so only the last few split points are useful; small blocks (≤48 lines) keep the original full-range behaviour. Turns the 90s hang into a fast diagnostic (busi hub: 90s → ~3s `illegal start`).
- **Guard:** `ParseErrorPositionTest` — "large block with `given` as an identifier yields a fast diagnostic, not a quadratic hang" (2500-line block; asserts a populated `parseError` in <15s). All 146 `scalascript.parser.*` tests pass; the handler-file trailing-lambda split still parses; busi `make v2-test` + `make v2-test-js` are 47/47 on both backends with the rebuilt jar.
- **Note:** verified against this branch's base (`origin/main` @ ce0554245) — `trySplitParse` is byte-identical to the commit busi pins (72d0196f3), where it was first reproduced + the fix built/tested. busi keeps its own workaround (no `given` val, `getOrElse` auto-number) so it is unaffected; this lands the parser-robustness fix for everyone.

## rust-index-read-moves-noncopy — `fixed` (2026-06-22)
<!-- status: fixed
     kind: bug
     lane: multi
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** mellow-shrew (self), via an end-to-end `cargo run` smoke against the just-landed rust-web-toolkit follow-ons (`origin/main` @ d0141a1d4). The `backendRust` unit suite is string-match only (no `cargo` compile), so it missed a generated-Rust move error.
- **Symptom:** an index *read* on a non-Copy element sequence panicked the Rust compiler, not the program — `error[E0507]: cannot move out of index of Vec<String>`. Minimal repro:
  ```scalascript
  @main def run(): Unit =
    val parts: List[String] = "a,b,c".split(",").toList
    println(parts(1))      // → parts[(1i64) as usize]  — moves the String out of the Vec
  ```
  `Vec<i64>` indexing was fine (i64 is `Copy`), so the bug only surfaced once `f2afd3378` made `.split`/`.toList` results indexable (`Vec<String>`, non-Copy).
- **Root cause:** the `seq(i)` index-read lowering (`RustCodeWalk.scala`) emitted a bare `seq[(i) as usize]`. Using a `Vec`'s `Index` output by value moves it; legal only for `Copy` elements.
- **FIXED (2026-06-22):** index *reads* now emit `seq[(i) as usize].clone()` — required for `Vec<String>`/structs, elided by rustc for `Copy` elements (i64/char/bool), so zero cost. The `seq(i) = v` *store* path is now handled explicitly in `Term.Assign` (new `asSeqIndexTarget` helper) so the assignment **target** stays bare — you can't assign to a clone.
- **Guard:** `RustGenCollectionTest` — "index read on a String seq clones the element" + "index store on a mutable array stays bare". Verified end-to-end with a throwaway `cargo run` smoke (all new collection/string ops compile + run): output `30 70 70 30 100 6 1 a-b-c true true true b 3`. `backendRust` 235/0.
- **Follow-up (filed in BACKLOG):** the rust backend has no `cargo`-compile coverage in its unit suite — this whole bug class (move/borrow errors in valid-looking generated Rust) is invisible to string-match tests.

## interp-js-string-map-nonchar — `fixed (interp + js)`
<!-- status: fixed
     kind: bug
     lane: multi
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** `"abc".map(c => c.toInt).sum` threw (`No method 'sum'`) on interp + JS — mapping a String's chars to a NON-Char value should yield a `Seq[Int]` (then `.sum`), but interp/JS `String.map` rebuild a String. JVM correct (294).
- **FIXED (interp, 2026-06-15):** `String.map` returns a `String` only when EVERY mapped element is a `Char` (interp has a real `CharV`); otherwise a `List` (`strMapResult`). `"abc".map(_.toInt)` → `List(97,98,99)`; char-to-char maps stay Strings.
- **FIXED (JS, 2026-06-21):** added a JS Char wrapper. A char produced by iterating a String (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) is now boxed as a `_Char(code)` (`JsRuntimePart2a`): `valueOf` returns the code point and `toString` the 1-char string, so concatenation/arithmetic/`_show` coerce naturally. `_dispatch` gains a `_Char` branch mirroring the interp's `dispatchChar` (`toInt`→code point, `isDigit`/`isLetter`/`toUpper`/`asDigit`/…), and `String.map` now returns a String only when every result is a `_Char` (else a Seq) — mirroring `strMapResult`. `_eq` bridges `_Char` to a 1-char String literal and to an Int (the interp allows `CharV == IntV`), so `c == 'a'` and predicates work even though char *literals* stay JS strings. Verified: interp == JS == JVM on `"abc".map(_.toInt).sum` (294) and char-method map/filter; `CrossBackendPropertyTest` "String.map char vs non-char cross-backend" now asserts all three agree.
- **Residual (minor, by design):** a char *literal*'s `.toInt` (`'5'.toInt` → 5 on JS vs 53 on interp/JVM) still diverges — char literals stay JS strings to avoid touching literal-pattern codegen (which compares with `===`, not `_eq`). The actionable bug (`String.map(nonChar)` + iterated-`Char` methods) is closed; literal coercion is left as a separate, lower-value follow-up.

## interp-cons-in-effect-handler — `fixed` (example) (2026-06-13, `721ee62b9`)
<!-- status: fixed
     kind: bug
     lane: multi
     area: front
     fixed-in: unrecorded -->

- **FINAL diagnosis (two earlier mis-diagnoses corrected):** NOT a `::` bug and NOT a
  "resume result not forced to ListV" bug. `resume(())` **correctly** returns the
  continuation's pure result `()` (Unit); `println(rest)` after `val rest = resume(())`
  prints `()`. The `algebraic-effects.ssc` Logger handler did `msg :: resume(())`, i.e.
  `msg :: ()` → "No method '::' on StringV" — it assumed `resume(())` of the final
  continuation would be `Nil`. That is the **deep-handler list-accumulation** pattern
  (Koka/Eff `return x => []`), which needs a handler **return clause**. ScalaScript's
  `handle` has **no return clause** (the spec's own Logger example just does `resume(())`,
  returning Unit), so the pattern is unsupported. **Example bug, not an interp bug.**
- **Fixed:** rewrote the Logger section to a working accumulator (append each msg + resume)
  producing the same `List(Hello, World!)`, with a comment on the return-clause gap.
  Also corrected the State section (stdlib `State` + `set`, dropped a broken parameterized
  redecl — see `interp-parameterized-effect-decl`).
- **Underlying language gap (future feature, not filed as a bug):** a handler **return
  clause** would make `msg :: resume(())` work (the spec types `resume` as returning the
  *handler body's* type, which requires bridging the pure/base case). Large feature
  (parser + typer + interp + 4 backends) — out of scope; noted in BACKLOG.

## selfhost-front-trailing-operator-continuation-prints-Stub

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: tests/e2e/selfhost-front-gate.sh
     fixed-in: d2edc53a8 -->

**NOT REPRODUCIBLE d2edc53a8 — closed on evidence.** `val x = 1 +` continued on the next line gives `3`
on F, the same as the interpreter. No `Stub` anywhere. Fixed by somebody with nothing to notice,
which is what `gate: none` buys.

**Kept as a case in the gate**, for the same reason as its sibling above.

Found 2026-08-06 by building v3's front and comparing lanes. **CORRECTED the same day: these four
entries first named v1, and v1 is CORRECT on all of them.** The defects are in the SELF-HOSTED
front, which `bin/ssc run` and `ssc-tools run --v2` both use; `ssc-tools run --v1` is the
tree-walking interpreter and answers correctly. The mislabelling was mine: I ran `bin/ssc run`
throughout and reported its answers as v1's, which is the lane map this repository already
documents — a measurement is evidence about the command you actually ran, and nothing else.

```scalascript
val xs = List(1) ++ List(2) ++
  List(3)
println(xs.mkString(","))
```

| lane | output | exit |
|---|---|---|
| `--v1` (interpreter) | `1,2,3` | 0 |
| `bin/ssc run` (native) | `Stub` | 0 |
| `--v2` | `Stub` | 0 |
| v3 (both lanes) | `1,2,3` | 0 |

An expression continued after a TRAILING binary operator is Scala's rule and ordinary in this
corpus. The self-hosted front prints the `Stub` sentinel — the failure mode this repository compares
OUTPUT rather than exit codes to catch, and here the output is plausible enough to read as a value.

Narrowed: `List(1) ++ List(2)` on ONE line is fine. Only the continuation fails.

## selfhost-front-while-with-an-assignment-body-runs-nothing

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: tests/e2e/selfhost-front-gate.sh
     fixed-in: d2edc53a8 -->

**FIXED d2edc53a8, and it was one word.** `parseWhileBody` routes a SINGLE-LINE body to
`parseWhileExpr`, which called `parseExpr` — and `parseExpr` stops at the `=`, taking the rest of the
file with it. It now calls `bodyExpr`, which tests for an indexed store and an assignment first.
`parseIf` has used `bodyExpr` all along, which is exactly why `if c then i = i + 1` worked while the
`while` did not: one decision, two sites, fixed at one of them.

**Third instance of this mechanism in the same file**, and the other two are documented twenty lines
above the site — `a(1) = 7` dropped the same way, and a store inside a block. That comment reads
"two paths, one of them silent, which is the shape this whole bug is made of."

**Three shapes, not the one reported.** `bodyExpr` also covers an indexed store and a compound
assignment, so `while c do a(0) = a(0) + 1` and `while c do i += 1` were broken identically and just
as quietly. Nobody had filed them; they are fixed and pinned in the gate too.

```scalascript
var i = 0
while i < 3 do i = i + 1
println(i)
println("after")
```

| lane | output | exit |
|---|---|---|
| `--v1` (interpreter) | `3` then `after` | 0 |
| `bin/ssc run` (native) | *(nothing at all)* | 0 |
| `--v2` | *(nothing at all)* | 0 |
| v3 (both lanes) | `3` then `after` | 0 |

**The self-hosted front prints NOTHING and exits 0** — not a diagnostic, not a partial answer; the
whole program disappears. A single-line body that is an ASSIGNMENT rather than an expression.

The silence is what makes it expensive: a program that prints nothing at exit 0 reads as a program
that had nothing to say.

## selfhost-front-alternative-pattern-matches-only-its-first-branch

<!-- status: fixed
     lane: multi
     area: front
     kind: bug
     gate: tests/e2e/selfhost-front-gate.sh
     fixed-in: d2edc53a8 -->

**NOT REPRODUCIBLE d2edc53a8 — closed on evidence, not on a guess.** F answers `ab` for BOTH `f(A)` and
`f(B)`, agreeing with the interpreter, and a three-arm variant (`case A | B` beside `case C`) is
right as well. Somebody fixed it and nothing recorded when.

**Kept as a case in the gate rather than deleted.** It was filed for a reason and nothing was
watching it, so it could return exactly as quietly as it went.

```scalascript
trait K
case object A extends K
case object B extends K
def f(k: K): String =
  k match
    case A | B => "ab"
println(f(A))
println(f(B))
```

| lane | output | exit |
|---|---|---|
| `--v1` (interpreter) | `ab` then `ab` | 0 |
| `bin/ssc run` (native) | `ab` then `match: no arm for B/0` | 0 |
| `--v2` | `ab` then a RuntimeException | 0 |

`case A | B =>` is accepted and then matches **only the first alternative**. Better than the two
above in that it says something when it goes wrong; worse in that the arm looked exhaustive to
whoever wrote it.

## selfhost-front-qualified-assignment-to-an-object-member-is-ignored

<!-- status: fixed
     lane: multi
     area: runtime
     kind: bug
     gate: tests/e2e/selfhost-front-gate.sh
     fixed-in: 842779d44 -->

**FIXED — and the plan recorded here yesterday was wrong on two counts, both found by building it.**

**It is SEVEN call sites, not five.** `bodyExpr`, `parseBlock0`, `armBodyExpr`, `armSeqStmt`,
`parseTopItem`, `parseDBlock0` and `isDirectBind` all ask `isAssignHead`. Widening the predicate is
still the right move for exactly the stated reason — a per-site test would manufacture a fourth
instance of this defect — but the count was taken from a grep that missed two.

**`isDirectBind` would have broken.** It calls `isAssignHead` and then takes `snd(hd(ts))` as the
name being bound. Widening the predicate blindly would have handed it `Counter` as a bind name. It
takes `cx` now and asks the corrected predicate.

**The predicate needs `cx`, and that turned out to be a guard rather than a cost.** Without it the
head cannot ask `isObjVarMember`, and `p.field = 5` on a case class — a feature this front does not
have — would have become a `cell.set` against a global that does not exist. The signature change
also forces the compiler to walk the author through all seven sites; none can be forgotten.

**THE BUG IN MY OWN FIX, which is the part worth reading.** The first build was clean and changed
nothing: `Counter.n = 5` still printed `0`. The head tested `fst(hd(ts)) == 1` — the identifier kind
that is right for `x = e`. **An object name is kind 3.** Kind 1 is a lower-case identifier, what a
`var` is; kind 3 is the upper-case one `parseAtom1` routes to `parseCtor`. So the predicate was
correct in shape and could never fire once, on any program — a check that silently never runs, which
is the same failure mode as the bug it was written for. Found by relaxing the test one clause at a
time until something changed, rather than by re-reading it.

**Read and write share one predicate and one cell name.** A qualified read goes through `postSel`,
which emits `(prim cell.get (global Obj_mem__cell))` under the same `isObjVarMember` test; the write
is the matching `cell.set`. They cannot drift apart.

Four cases in `tests/e2e/selfhost-front-gate.sh`, including the compound form (which exercises the
read side) and the same statement inside a `def` (a different dispatcher, working from the one
edit). Negative control: restoring `== 1` reddens exactly those three and leaves nine green.

**STILL OPEN d2edc53a8, and the cause is now known.** `object Counter: var n = 0` then `Counter.n = 5`
prints `0`; v3 prints `5` and is the oracle here because **the interpreter itself crashes on this
program** — which is why the gate names its oracle per case instead of once.

**Same mechanism as the `while` bug fixed alongside it, for the third time in this file.**
`isAssignHead` is a TWO-TOKEN peek — an identifier then an assign operator — and in `Counter.n = 5`
the second token is `.`. The statement is not recognised as an assignment at all, falls to
`parseExpr`, and `parseExpr` stops at the `=`.

**Where the fix goes, and where it must NOT go.** In `isAssignHead` itself, so the five sites that
already ask it — `parseTopItem`, `parseBlock0`, `bodyExpr`, `armBodyExpr`, `armSeqStmt` — get it at
once. A separate qualified-assignment test at each site would manufacture a fourth instance of the
very shape this entry is about. The emitter already exists in spirit: a qualified READ goes through
`postSel`, which emits `(prim cell.get (global Obj_mem__cell))` when `isObjVarMember` holds; the
write is the matching `cell.set`. The cost is a signature change — `isAssignHead` needs `cx` to
consult `isObjVarMember` — which is why it is not bundled with the one-word `while` fix.

```scalascript
object Cfg:
  var n = 0
Cfg.n = 7
println(Cfg.n)
```

| lane | output | exit |
|---|---|---|
| `--v1` (interpreter) | `[ERROR] Cannot eval: Term.Assign` — REFUSES, with a position | non-zero |
| `bin/ssc run` (native) | `0` | 0 |
| `--v2` | `0` | 0 |
| v3 (both lanes) | `7` | 0 |

The assignment is accepted and silently discarded on the self-hosted front. v1 refuses it outright,
which is the honest answer for a construct it does not implement. Mutation through a METHOD works on
every lane — `def bump(): Unit = n = n + 1`, which
`tests/conformance/object-var-member-scope.ssc` exercises — so this is specifically the qualified
form from outside the object.

---

**All four share one shape.** The self-hosted front ACCEPTS the syntax, runs, and produces a wrong
answer at exit 0. None was found by a test, because a test asserts what someone thought to assert;
they were found by running another implementation on the same source and diffing the output.

**And the correction is part of the finding.** The first version of these entries blamed v1, because
I ran `bin/ssc run` and called it v1 for a whole session. `bin/ssc run` is the NATIVE lane. The
interpreter is `ssc-tools run --v1`, and it is correct on every one of these. A differential is only
as good as knowing which two things it compared.

## the CDS archive is SHARED between every checkout and is not keyed on the build — FIXED

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: tests/e2e/cds-archive-per-build.sh
     fixed-in: 79521f93ced0e6e5d51018ae6e018050ae0b7785 -->

Found 2026-08-07 after roughly two hours of looking in the wrong place, which is the reason this
entry is long: the symptom points nowhere near the cause.

**Symptom.** `bin/ssc run scripts/smoke-ci.ssc` dies with

```
ssc: class scala.Tuple2 cannot be cast to class scala.collection.immutable.List
```

No position, no stack, and `scripts/smoke-ci` is a bash wrapper that `exec`s it — so the whole
suite prints one line and exits 1. A trivial program still runs, so the toolchain looks alive.

**Cause.** The launcher enables class-data sharing with

```bash
_SSC_CACHE="${SSC_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/scalascript}"
-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile="$_SSC_CACHE/ssc.jsa"
```

**One archive, at one path, for every checkout and every worktree on the machine.** Two trees at
different commits share it, and `AutoCreateSharedArchive` did not regenerate it when the jars
changed underneath. The JVM then loads STALE CLASS DEFINITIONS out of the archive in preference to
the jars on the classpath.

The cast error is what that looks like from the outside. `03887cefb` changed the self-hosted
front's OBJ-SCOPE payload from `(name, varNames)` to `(name, (varNames, defNames))`; the new jar
builds the pair and the archive supplies the old reader that expects a list.

**Why it took two hours.** Every ordinary hypothesis is wrong here, and each one costs a rebuild:

- `rm -rf bin/lib`, `target`, `project/target` and a full `install.sh --dev` — **no effect**, the
  archive is outside the repository;
- the same commit checked out in another copy of the repo — **works**, because its jars are older
  and happen to match the archive. That comparison says "your worktree is broken" and it is not;
- swapping the toolchains proves the source is fine and the toolchain is not, which is true and
  points at the jars, which are also fine.

**Reproduction and the one-line proof:**

```
SSC_NO_CDS=1 bin/ssc run scripts/smoke-ci.ssc    # works
bin/ssc      run scripts/smoke-ci.ssc            # ClassCastException
rm -f ~/.cache/scalascript/ssc.jsa               # and now both work
```

**Fix.** Key the archive on the build it belongs to. `bin/lib/.build-digest` already exists and is a
content digest of the build's inputs, so the launcher template in `build.sbt` (the
`standardLauncherScript` block, around line 1962) needs the archive path to carry it:

```bash
_SSC_DG="$(cat "$_SSC_BIN/lib/.build-digest" 2>/dev/null || echo none)"
-XX:SharedArchiveFile="$_SSC_CACHE/ssc-$_SSC_DG.jsa"
```

Then two trees cannot collide, and a rebuild gets a fresh archive instead of a stale one. Old
archives accumulate in the cache; a size cap or an age sweep is the follow-up, and is a much smaller
problem than a wrong answer.

**FIXED 2026-08-07** once `release-v0-1-1` released `build.sbt`. The launcher template now reads
`bin/lib/.build-digest` and names the archive `ssc-<digest>.jsa`, so two builds cannot collide and a
rebuild gets a fresh archive. Proven by A/B: with two different digests the launcher opens two
different archive files.

`tests/e2e/cds-archive-per-build.sh` holds it, and it checks the GENERATED launchers rather than the
template — a template that is right and a launcher that is stale is exactly the gap this class of
bug lives in. Observed failing: restoring the shared `ssc.jsa` path gives
`FAIL bin/ssc shares ONE archive for the machine`. Its own "no launcher with CDS found" guard fired
first and caught a wrong `ROOT` in the gate, which is what that guard is for.

Old archives accumulate in the cache under their digests; a size cap or an age sweep is the
follow-up, and is a much smaller problem than a wrong answer.

## v3-two-fronts-disagree-on-derives-and-summon-and-no-ci-job-runs-the-gate

<!-- status: fixed
     lane: v3
     area: front
     kind: bug
     gate: v3/front-diff.sh
     fixed-in: 89f6f3e0ae512d3b51fc059dc3e66a07637f6e1d -->

**Measured 2026-08-10 on a freshly rebased tree, twice, same numbers both times:**

```
both fronts print: 275; they AGREE on 273, differ on 2
  js-derives-segmented   10,13d9 <   (do (name "derives"))
  v2-mirror-surface      4c4     <   (val "mp" (call "__summon__" (str "Mirror")))
FAIL corpus DISAGREEMENTS rose to 2, above the ceiling 0
```

The disagreement ceiling is 0 and it is 0 for a reason: two fronts printing different trees for the
same program is the one thing this differential exists to forbid.

**`summon` is attributable.** `git log -S'__summon__'` names exactly one commit on the front
files — 634147b99, SSC3-G2 stage 1. That commit touched BOTH fronts (`Parser.scala` and
`UniFront.scala`), so this is not a case of one front being taught and the other forgotten; the two
implementations of the same stage disagree on what they emit.

**`derives` is stranger, and worth reading before fixing.** `git log -S'derives'` on the front
sources names NO commit: the word does not appear in either front. So neither front implements
`derives` — one parses it as an ordinary name and leaves a stray `(do (name "derives"))` statement
behind, the other drops it. They do not disagree about a feature; they disagree about **how to fail
at a feature neither has**, which is the shape that survives review because both sides look
reasonable in isolation.

**Why nothing caught it.** `v3/front-diff.sh` is not a step in `.github/workflows/v3.yml`. That
workflow runs selftest, exec, bridge, parity, front, front-report, jit (twice) and front-capability,
and its own header argues at length that a suite nobody runs is worse than a red one. The front
differential is the suite that decides the UniML front swap, and it is run by hand.

**Not wired here, deliberately.** Adding the step while the gate is red would turn `main` red for
everyone over a defect its author has not seen yet. The honest order is: fix the two, then wire it —
and wiring it is a one-line change to a file no claim holds.

Reported by the agent holding `ssc3-core`. Attributed by construction rather than assumed: on the
tree that carried the loader fix and nothing newer, the same gate read 270 print / 270 agree /
**0 differ**. The two appeared only after a rebase pulled in sibling commits, which is consistent
with 634147b99 for the `summon` half. The loader fix changes WHICH files load, never what a front
prints for a file it already loaded, and both disagreeing cases loaded before it.

**CLOSED 2026-08-10, both halves, and neither by waiting.**

The disagreements are gone: on 470e7ab03 the differential reads `both fronts print: 277; they AGREE
on 277, differ on 0`. `git log -S'derives'` on the front sources names 89f6f3e0a — the
`v3-method-as-a-value` work, which taught both fronts the same thing about a construct neither had
implemented. Two files also moved from "neither front prints" into agreement, so the count rose
rather than fell, which is how a real fix reads differently from a case quietly dropping out of the
comparison.

The second half — `v3/front-diff.sh` in no workflow — is closed by the same commit that closes this
entry: it is now a step in `.github/workflows/v3.yml`. Added AFTER the gate went green, which was
the whole reason it was not added when this was filed. The window in which a front-to-front
disagreement can sit unnoticed is no longer "until someone remembers to run it".

## v3-executor-still-returns-the-charAt-CODE-while-the-bridge-returns-the-character

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     gate: v3/exec-gate.sh
     fixed-in: b5fb6a9814f86a88fd1b09e8210da97f2fab689b -->

**`main` is red on this now.** v3's two lanes disagree, which invariant I-3 forbids:

```text
v3/tests/front/char-literals.ssc
  executor  x/121/A/98/true/sq      <- v3's own interpreter: the character CODE
  bridge    x/121/A/b/true/sq       <- the v2 runtime: the CHARACTER
```

Reproduced locally, not read off a CI log: `v3/ssc3 exec` against `v3/ssc3 run --bridge`.

**Cause, attributed by construction.** `f39448c96` — *charAt is a Char on every lane* — changed
`v2/src/Runtime.scala` and `v2/lib/string.ssc0`. v3's `--bridge` lane RUNS ON that runtime, so it
followed immediately; v3's own executor implements the same primitive separately and did not. The
claim behind the change (`charat-is-a-char`) scopes `v2/src/Runtime.scala`, the rust backend and
two test files — reasonably, because nothing in the tree says that v3's executor is a fourth
implementation of `charAt`. "Every lane" turned out to mean every lane the author could see.

**Why it sat for four commits.** `.github/workflows/v3.yml` triggered on `v3/**` only. A change to
the v2 runtime — half of what `exec-gate.sh` compares — could not turn this workflow red. It went
red when a push touching two gate SCRIPTS and a markdown file happened to match the path filter.
That half is fixed in the same commit that files this: the trigger now includes `v2/**`, at the
cost of v3's gates running on every v2 push, which is the price of the bridge lane being a bridge.

**Not fixed here, and not for want of trying.** `v3/src/Exec.scala` is held by
`v3-dataset-vertical-slice`; `charat-is-a-char` is live and mid-decision on its next item. The fix
is one of two things, and the choice belongs to those two agents rather than to a third party
reading the symptom: v3's executor adopts the Char, or the decision is narrowed to say which lanes
"every lane" covers.

Same shape as `v3-two-fronts-differ-in-CAPABILITY` and as the loader's stale search path, both
found today: a differential is blind to the component its two sides SHARE, and a path filter is
blind to the dependency it does not name.

**FIXED.** `v3/src/Exec.scala` now returns `Value.VChar` from `charAt`, and the fixture's
`.expected` moves with it. One constructor: `Value.VChar` was already the same
integer-that-prints-as-a-character, so `'x' + 1` stays 121 and `s.charAt(i) != 92` keeps meaning
what it meant.

The file was NOT actually held. `v3-dataset-vertical-slice` was released in 7a88d1049, which removed
its `.claim` file and left its LEDGER row — so the mutex still reserved `v3/src/Exec.scala` for work
nobody was doing, and that stale row is why this entry first said the fix belonged to someone else.
Row dropped on the strength of the release commit.

Eleven gates green, on a tree where the UniML front actually builds — which took a rebuild to
notice. Three cases failed at first and only one was mine; the other two are uniml-only fixtures
that failed because a stale `v3/.jars/uniml.cp` made the driver fall back to v3's own front. The CI
run on the same commit had exactly one FAIL, which is how that was attributed rather than guessed —
and it sent me after a hazard that turned out not to exist. I expected the fallback to make
`front-diff.sh` compare one front with itself and report perfect agreement. Measured instead of
built: with `uniml.cp` pointed at an empty directory, `ssc3 ast <file> uniml` exits **2** with
`could not compile the uniml front` and prints nothing, so the differential's own probe fails the
gate by name. The fallback applies to `Front.default` — which is what `exec` and `run` use, and why
`exec-gate` ran on v3's own front — never to an EXPLICIT front request. No guard needed.

## v3-a-parameter-does-not-shadow-a-top-level-function-in-the-arity-check

<!-- status: fixed
     lane: v3
     area: codegen
     kind: bug
     gate: v3/front-gate.sh
     fixed-in: 1334bea8f -->

**A library method whose PARAMETER is named `f` breaks when the user's program also defines a
top-level `f` of a different arity.** The arity check resolves the name against the global function
table instead of the enclosing parameter, so the parameter does not shadow.

```text
prelude   def map(f: Any => Any): Dataset = Dataset(items.map(x => f(x)))
user      def f(a: Int, b: Int): Int = a + b
result    ssc3: <user file>:38:60: call to 'f' passes 1 argument(s), it takes 2
```

Note the position: line 38 of the PRELUDE, reported against the user's file, which is three lines
long. That is a second defect and it is filed with this one.

**Why it appeared only now.** Until the prelude landed, v3 had no code that lives beside an
arbitrary user program and is reused by all of them. A prelude is exactly that, and it hit this the
day it gained a library — three fixtures (`bitwise`, `enum-qualified`, `operator-continuation`) went
red at once, all of them programs that happen to define `f`. The names at risk are the ordinary
ones: `f`, `p`, `n`, `k`, `x`.

**FIXED in 1334bea8f 2026-08-11, and the workaround below is GONE — its removal is what makes this
a fix rather than a claim.** `checkArity` carries the names bound inside the def — parameters,
lambda parameters, `Try`'s binder, local `val`s and local `def`s — and does not look a bound name up
in the global table. Conservative by construction: the set is collected over the WHOLE def rather
than per scope, so it can MISS an error and cannot invent one, and inventing one was the defect.
Verified today by re-running the entry's own repro: `f(1, 2)` answers 3 and `g(x => x)` answers 1.

The second defect filed with it — the prelude's line 38 reported against a three-line user file —
is also fixed, by attaching `LowerFail`'s origin in the early passes (`d0b15414a`), and is checked
by `v3/prelude-gate.sh` with a deliberately broken prelude.

Per-scope tracking remains as a follow-up in `v3/PRELUDE-CORRECTNESS.md` P-1.

**What the workaround WAS, kept because the distinction it draws is the useful part.** `v3/prelude/index.ssc` renames every
method parameter to `__fn`, `__pred`, `__x` and the like. That protects the library from the defect;
it does nothing about the defect, and any other module written to live beside user code will hit it
again. The real fix — a parameter shadows a top-level name in `checkArity` — is in `Lower.scala`,
held by five claims on the day this was written.

**Repro in three lines**, no prelude needed: a file defining `def f(a: Int, b: Int): Int = a + b`
and `def g(f: Any => Any): Any = f(1)` refuses `g` with the arity of the top-level `f`.
