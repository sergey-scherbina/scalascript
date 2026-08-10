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








## interpreter-fast-lane-not-on-the-push-path-yet

<!-- status: open
     lane: int
     area: build
     kind: apparatus
     gate: none -->

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

<!-- status: open
     lane: v3
     area: front
     kind: gap
     gate: none -->

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

<!-- status: open
     lane: v3
     area: build
     kind: apparatus
     gate: none -->

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
     kind: defect
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
     kind: gap
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
     fixed-in: PENDING -->

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

<!-- status: open
     lane: v3
     area: codegen
     kind: bug
     gate: none -->

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

## v3-uniml-def-has-no-type-parameters — so the default front cannot resolve a `using` clause

<!-- status: fixed
     lane: v3
     area: front
     kind: gap
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

## v3-method-as-a-value — `obj.m` passed as a function lowers to a CALL with no arguments

<!-- status: fixed
     lane: v3
     area: codegen
     kind: gap
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

<!-- status: open
     lane: v3
     area: front
     kind: gap
     gate: v3/front-gate.sh -->

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

<!-- status: open
     lane: v2-rust
     area: codegen
     kind: bug
     gate: none
     fixed-in: - -->

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


## parameterless-def-diverges-native-vs-interp — opposite conventions, no portable spelling

<!-- status: fixed
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

<!-- status: open
     lane: multi
     area: runtime
     kind: divergence
     gate: none
     fixed-in: - -->

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


## v3-loses-a-mutation-to-a-captured-var — both v3 lanes answer 0 where the reference answers 6

<!-- status: fixed
     lane: multi
     area: codegen
     kind: wrong-answer
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


## an-undefined-name-in-a-pattern-means-three-different-things — two lanes ignore it, one throws

<!-- status: open
     lane: multi
     area: front
     kind: divergence
     gate: tests/e2e/pattern-undefined-name-gate.sh
     fixed-in: - -->

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
     kind: divergence
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
     kind: divergence
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

## v2-curried-def-partial-application-unsupported — a curried def cannot be applied one clause at a time

<!-- status: open
     lane: native
     area: front
     gate: none -->

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


## tui-cargo-deps-are-a-hand-maintained-disjunction — a new emitted feature can reference a crate nobody declared
<!-- status: fixed
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

<!-- status: open
     lane: multi
     area: front
     kind: bug
     gate: none
     fixed-in: - -->

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

<!-- status: open
     lane: multi
     area: front
     kind: bug
     gate: none
     fixed-in: - -->

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

<!-- status: open
     lane: multi
     area: front
     kind: bug
     gate: none
     fixed-in: - -->

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

<!-- status: open
     lane: multi
     area: runtime
     kind: bug
     gate: none
     fixed-in: - -->

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

<!-- status: open
     lane: v3
     area: runtime
     kind: bug
     gate: v3/exec-gate.sh -->

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
