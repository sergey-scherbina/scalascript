## v2-swift-cli-fails-silently-without-a-swift-toolchain — and my own fix put a live backtick in it

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: tests/e2e/v2-swift-cli.sh
     fixed-in: 29b205902 -->

**`v2-swift-cli.sh` compiles and runs Swift, and it was wired into a Linux job.** GitHub's ubuntu
images ship no Swift, so `run-swift` died under `set -euo pipefail` with its output redirected to a
temp file. The step printed `KNOWN GAP …` as its last word and exited 1 with **nothing attributable
to it** — while the two gates sharing that step reported `15 ok, 0 FAIL` and `all checks passed`.

Third instance today of one shape: **a gate wired where it cannot run, failing in a way that does
not say so.** The other two were `validate` having no toolchain for 19 gates, and
`board-ownership-check` needing history a depth-1 checkout does not have.

**Fixed** with a toolchain guard that SKIPS loudly — the same shape the rust gates use for `cargo` —
and by keeping `run-swift`'s stderr so a genuine failure on a Swift host prints its reason instead of
an exit code. The guard names its binary (`SSC_SWIFT`, default `swift`) so the branch can be
exercised; both were observed: with `SSC_SWIFT=/definitely/not/a/toolchain` it prints

```text
  [skip] v2-swift-cli: no swift on PATH — the emit checks above ran, the build/run checks
         below need a Swift toolchain. NOT a pass: this host cannot test them.
```

**AND I PUT A LIVE COMMAND SUBSTITUTION IN THAT SKIP MESSAGE.** The first draft read
``no `swift` on PATH`` inside a double-quoted `echo` — backticks, so on a host WITH swift it would
have RUN the compiler to build its own error message. Caught by grepping the line I had just written,
before it was committed.

**That is the ninth occurrence of this class here, by me, hours after fixing the eighth** in
`.githooks/pre-push` and landing `tests/e2e/no-live-backticks-in-heredocs.sh` for it. **The ratchet
does not cover this case**: it checks heredoc bodies, and this was a double-quoted string. That gap
is real and is not closed by widening the rule, because `` `cmd` `` inside a double-quoted string is
legitimate substitution most of the time — a checker cannot tell intent apart from a message. What
the ninth occurrence actually shows is that the *typing habit* is the failure point, and the only
reliable answer found so far is the one already in use for durable text: write it somewhere that
cannot expand, then pass it as data.

## smoke-job-cap-no-longer-looser-than-the-suite — 17 of 100 pushes reached no verdict, every one killed at the 30-minute job cap

<!-- status: open
     lane: apparatus
     area: build
     kind: bug
     gate: .github/workflows/smoke.yml
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes -->

**The cap has a rule written beside it, and the rule is now broken.** `.github/workflows/smoke.yml`
sets `timeout-minutes: 30`, and the comment above that line states what it must obey:

> 30 min leaves ~11 min over the worst observed case. The rule this cap should obey from now on: it
> must stay STRICTLY LOOSER than the suite's own budget plus build time, or the guard with the
> measurement behind it cannot speak.

When that was written the successes ran a median of 16.8 min, max 20.0. They no longer do.

**Census, 100 runs over 24 h** (`gh run list --workflow=smoke.yml --limit 100`, window
2026-08-14T17:09 → 2026-08-15T17:42):

| conclusion | n | min | median | max |
|---|---|---|---|---|
| success | 62 | 16.7 | **26.0** | 60.5 |
| failure | 18 | 18.8 | 27.6 | 29.9 |
| cancelled | 17 | **30.1** | 30.3 | 38.7 |
| (empty) | 3 | 0.1 | 0.1 | 0.1 |

**Every single cancelled run is at or past the cap and none is below it.** That is what makes this a
cap rather than a race: a newer push cancelling an older run would cancel at arbitrary durations.
The successes above 30 min are not a contradiction — the timeout applies to RUNNING time while these
figures are `createdAt → updatedAt`, which includes queueing.

**Why it costs more than the 17 runs.** `cancelled` is RED by policy P-6.7, so a push that hits the
cap yields no evidence at all: not a pass, not a fail, and nothing to act on. It also mislabels
working code — I hit it on `f97ff254c`, saw `CI RED`, and the run went GREEN on a rerun of the same
sha with nothing changed. An agent who reads only the colour will hunt a defect that is not there;
an agent who learns to shrug at RED will miss one that is.

**Measured, not decided.** Raising or lowering this cap has been the project owner's call three
times (see `smoke-suite-over-its-own-budget`, and the comment block in the workflow), so this entry
stops at the number.

### 2026-08-15 — WHY the duration moved, measured. The budget cannot see more than half the suite

The entry above says "either the cap moves, or the suite's real duration does". Here is the second
half, and it needs nobody's decision to establish.

From a GREEN run (`5ef9c49af`, run 31900764370), the suite's own header and rollup:

| | |
|---|---|
| checks run | 113 |
| budget | **952 s**, derived from **82** per-check baselines totalling 699 s |
| actual | **1467.4 s** |
| checks with NO baseline row | **30**, charged their measured **804.6 s** so they cannot fail the run |

**So 55 % of the suite's runtime is invisible to the budget that is supposed to govern it.** 952 s
cannot be met by a 1467 s suite, and the gap is not drift — it is thirty checks the budget never
counted.

**The thirty are exactly the checks added since the baseline was last harvested**, and that
correspondence is exact rather than approximate. `tests/smoke-baseline.tsv` was last refreshed in
`78710cf86` on 2026-08-12. Between that commit and today, `scripts/smoke-ci.ssc` was touched by 23
commits carrying **+36 `Check(` lines and −6 — net +30.**

**The cost is concentrated, not spread.** The three most expensive checks in the whole run are all
un-baselined F-front checks: `f-at-bind-pattern` 102.5 s, `f-bodyless-object` 83.6 s,
`f-cons-nil-tail` 82.6 s — **269 s between them, 18 % of the run.** Average cost of a baselined
check is ~8.5 s (699 s over 82); of an un-baselined one, ~27 s (804.6 over 30). The additions are
three times the price of what was there.

**The mechanism that let it drift is that nothing couples the two.** Adding a check to
`smoke-ci.ssc` is one line and lands with its gate; refreshing `tests/smoke-baseline.tsv` is a
separate deliberate act (`tests/smoke-baseline-harvest.sh`). Every agent does the first. In 23
commits nobody did the second, and the suite's own budget quietly stopped being a statement about
the suite.

**What this changes about the decision, which is still the owner's.** Refreshing the baseline does
not make the suite faster — it makes the budget honest, so the inner guard can speak again instead
of being outrun by checks it cannot see.

### 2026-08-15 — the baseline IS refreshed, and the cap tension is now a number

Regenerated from twelve real CI runs with `tests/smoke-baseline-harvest.sh` — the action the file's
own header prescribes, and the only one: `do not hand-edit a row`.

| | before | after |
|---|---|---|
| rows | 82 | **112** |
| sum at the reference host | 699.0 s | **1425.5 s** |
| suites the runs held | 79, 81, 82 | **109, 110, 112** |

**The obstacle I claimed in the census above was imaginary and is retracted here.** I wrote that a
harvest on a contended host would freeze the contention. It would not: the harvester reads CI run
LOGS and normalises each check to a reference host with `smoke-ci`'s own fit. Local load never
enters it. The census stopped one step short of the fix for a reason that was not there.

**What the refresh does to the budget, from `budgetFor` rather than from a ratio:**

    budget = baseline × (436000 + 1263 × probe) / (436000 + 1263 × 224) + 250

It is AFFINE, not multiplicative — the observed 699 → 952 at probe 227 is mostly the +250 margin,
not a factor, and reading it as a factor would have overestimated the new budget by four minutes.
Checked against the run: 699 × 1.0053 + 250 = 952, exactly what the suite printed.

| host probe | derived budget |
|---|---|
| 227 ms (the run measured above) | **1682 s ≈ 28.0 min** |
| 288 ms (the clamp's upper end) | **1835 s ≈ 30.6 min** |

**So the guard can speak again: against the observed 1467.4 s the budget now has 215 s of headroom
instead of being 515 s under the suite it governs.** That was the half that needed no decision, and
it is done.

**And the cap tension stops being a hypothesis.** `.github/workflows/smoke.yml` caps the job at 30
minutes and requires that cap to stay STRICTLY LOOSER than the suite's budget plus build time. At a
typical host the budget alone is now 28.0 min; on a slow-but-in-range host it is 30.6 min, past the
cap outright, before any build time is added. The rule is violated at the typical host and
impossible at the slow one — which is what the 17 cancelled runs in 100 were.

**Still not decided here, and deliberately.** Two levers, both the owner's: move the cap, or take
work off the push path — the three un-baselined F-front checks at 102.5, 83.6 and 82.6 s are 269 s,
18 % of the run, and are the same kind of candidate the 2026-08-01 restructure moved to the nightly. The duration question then has a name and a lever: three
F-front checks at ~90 s each on the push path. Whether they belong there, or belong in the nightly,
is the same kind of call the 2026-08-01 restructure already made once. What the number says: the margin the cap was sized to give — ~11 min over the
worst case — is gone, and the suite's median has moved from 16.8 to 26.0. Either the cap moves, or
the suite's real duration does; the inner budget cannot report on this one, because a job killed at
30 min never reaches the line that prints it.

## f-trait-parent-list-comma — `extends A, B` left the second parent in the token stream

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: 872500b18
     gate: tests/e2e/f-foldable-grade-gate.sh -->

```scalascript
trait Traversable[T[_]] extends Functor[T], Foldable[T]
```

`F: unbound global: (global Foldable)` · reference: compiles. Scala 3 accepts a COMMA as well as
`with` between parents; F's chain knew only `with`, so the comma and everything after it stayed in
the token stream and the second parent was read as an EXPRESSION — which is why the diagnostic names
the trait declared one line above, and why nothing about the message points at `extends`.

One line: the chain recurses on `,` exactly as it already did on `with`. Two corpus files —
`std/foldable-traversable.ssc` and `tests/conformance/std-foldable-traversable.ssc` — now lower
under F and agree with the reference.

Three of the gate's seven rows are separators that ALREADY worked (single parent, `with`, a class
`extends`), because the fix extends a recursion and a broken recursion breaks what used to pass.

### The corpus gate could not measure this, twice, and that is worth recording

Two runs at the default `-P 8` came back RED with 121 and 107 files of 639 — and **zero timeouts**
both times. Zero is the tell: under load the timeout bucket normally holds 90–130, so a short list
with no timeouts means workers were being KILLED, not slowed. The same host had already killed a
census at 570 of 771 earlier the same day.

**Re-running at `JOBS=2` measured 422 files — more than any run all day** (the usual figure at `-P 8`
is 290–320), with 6 timeouts and F-wrong 0. So the default parallelism was not merely slower here,
it was LOSING WORK, and the loss looked exactly like a regression in a one-line change that cannot
affect how many files an instrument gets through.

What made that legible in seconds rather than an hour was the gate freezing THREE numbers instead of
one. With only an agreement floor, 33-of-50 reads as a plausible red. `f-output-agreement-gate`'s
own header says a gate that reports 0 == 0 as green is worse than no gate; the same argument is why
it reports a shrunken subject set as untrustworthy rather than as a verdict.

## f-for-generator-tuple-pattern — `for (a, b) <- xs` loses the second binder

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: none — open defect -->

```scalascript
val names = List("a", "b")
val grades = List(90, 75)
for (name, grade) <- names.zip(grades) do
  println(name + ":" + grade)
```

`F: unbound global: (global grade)` · `ref: a:90`. The `yield` form fails identically
(`for (a, b) <- List((1, 2)) yield a + b`); a single binder (`for n <- xs`) is fine.

**The cause is a genuine ambiguity F resolves the wrong way.** `skipForOpen` skips a leading `(`
because `for (n <- xs)` may parenthesise the whole generator — so for `for (a, b) <- …` it skips the
tuple pattern's own paren, takes `name` as the entire binder, and the rest desynchronises. The two
forms are told apart one token later: `( ident <-` is a parenthesised generator, `( ident ,` is a
tuple pattern.

**Not fixed here because the binder is threaded as a single NAME.** `forGen`, `forGuard`,
`forGuardG`, `forSep`, `forFlatMap`, `forEnd`, `forYield`, `forDo` and `forLam` all carry `nm` as one
string and push it as `nm :: env`; a tuple pattern needs a list of names, an env push in field order,
and `forLam` emitting a destructuring lambda — `(lam 1 (match (local 0) ((arm Pair 2 …) (arm Tuple2
2 …))))`, the Pair/Tuple2 duality `genPairArm` already encodes. That is nine signatures in a subset
language that allows one `match` per function, for ONE corpus file
(`examples/extensions.ssc`). Worth doing, not worth doing quickly.

## board-routing-debt-191-entries-sit-where-their-fix-does-not — the heuristic routed on prose

<!-- status: open
     lane: apparatus
     area: docs
     kind: apparatus
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: tests/e2e/area-map-gate.sh -->

**`scripts/board-ownership-check` now freezes 191 entries whose `fixed-in` commit touched code owned
by a different board.** 24 were added to that freeze on 2026-08-15 to unblock `validate`, and the
freeze is the gate's own sanctioned route — but a frozen misfiling is still a misfiling, so the debt
is recorded here rather than absorbed silently.

**The 24, and the pattern is the finding:** 20 of them sit in `tests` (or another wrong module) while
the fix lives in a product module.

```text
tests  -> rust  (7)   type-lost-across-a-boundary, map-get-lowers-to-an-owned-key,
                      build-rust-std-imports-unlowerable, zipwithindex-result-is-not-indexable,
                      map-getorelse-emits-copied-on-a-string, json-core-emitted-rust-does-not-compile,
                      typed-pattern-against-an-any-does-not-test-the-type,
                      jsonparse-returns-a-string-on-rust-and-a-value-everywhere-else
tests  -> v2    (7)   no-two-type-checkers-in-this-repo-agree, ref-front-drops-every-block-tail-but-the-last,
                      f-effect-declarations-and-handlers-unsupported, f-front-exit-code-replaces-the-real-diagnostic,
                      charat-returns-char-on-v1-and-int-everywhere-else,
                      ref-front-drops-all-but-one-vararg-when-an-earlier-p…,
                      reference-front-answers-three-conformance-files-diff…
tests  -> cli   (2)   cli-errors-are-messages-guard-is-18-percent-above-it,
                      jvm-artifact-stack-trace-never-names-the-users-own-file
tests  -> other (2)   jsonparse-null-is-none-on-every-lane (json-plugin), f-gap-census-refresh (scripts)
v3     -> v2    (2)   v2-f-round-is-three-different-roundings…, v3-workflow-does-not-trigger-on-uniml…
v2     -> rust  (1)   rust-list-concat-moves-its-operands
v1/js  -> v2    (1)   js-long-arith-no-64bit-wrap
```

**This is the exact failure the `bugs` skill warns about, and the gate's own comment says so:** the
`lane:` field came from a keyword heuristic over prose, and *"an entry names its GATE far more often
than its cause"*. An entry found by a conformance case gets filed in `tests`; its fix goes to rust or
v2. Routing on the module that owns the FIX is the rule, and 20 of 24 break it the same way.

**Why frozen rather than moved.** Moving 24 entries across boards other agents own is a large,
conflict-prone edit, and it is not what was blocking anything: `validate` was red on this one check
and had 31 other gates behind it. The freeze is the gate's documented remedy — *"record it with
`--update-baseline` and say why"* — and this entry is the "why".

**Acceptance test:** move an entry to the board that owns its `fixed-in` paths, delete its baseline
row, and `tests/e2e/area-map-gate.sh` must still pass — the checker verifies the other direction too
("a baseline row that is no longer misfiled" fails the gate), so the two halves cannot drift.

**A stale row was cleared in the same refresh:** `ci-status-guard-races-the-shared-repo-index-lock`
stopped being misfiled when it was fixed on 2026-08-14, and the checker had been flagging it since.

## ci-gates-wired-into-jobs-that-cannot-run-them — 19 gates with no launcher, 28 steps behind a timeout

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: .github/workflows/ci.yml
     fixed-in: ef6c81bfa -->

**Two jobs in `ci.yml`, one shape: a gate wired where it cannot possibly run.** Both were invisible
because neither failed in a way that says so.

### 1. `validate` had no toolchain, and 19 of its 31 gate invocations need one

Visible only after `07d770988` made the job stop hiding its own steps. The first full run reported
18 failures, most of them the same sentence:

```text
http-bind-address-gate:      no launcher at …/bin/ssc-tools — run ./install.sh --dev
rust-toint-parity-gate:      no launcher at …
build-smoke.sh: line 30:     …/bin/ssc-tools: No such file or directory
conformance-lanes-flag.sh:   bin/ssc or bin/ssc-tools not found. Build them first
```

The job had `Checkout` and `Setup Scala CLI` and nothing else. **Those gates could never have passed
there**, on any commit, since the day each was added.

**Fixed** by giving the job what its gates need — Java 21, sbt, the Coursier and zinc caches, and
`bash install.sh --dev` — rather than moving the gates out, because the other job with a launcher
(`conformance-extras`) was itself timing out and had no room to take them.

**Verified with a launcher present, all 32 gate steps run individually: 31 pass, 1 fails** — and the
one failure is `area-map-gate` (board-ownership drift), which is a content backlog and not a
hosting problem. Elapsed 851 s; with the ~246 s build that is ~18.3 min against the new 25-minute
budget.

### 2. `conformance-extras` was CANCELLED on every run, and one step was eating the job

```text
started 03:42:14  ended 04:02:45     (20m31s)
started 01:03:07  ended 01:23:37     (20m30s)
started 16:24:27  ended 16:44:53     (20m26s)
```

`timeout-minutes: 20`, firing every time. **A GH job timeout surfaces as `cancelled`, not
`failure`** — so it read as an infrastructure hiccup rather than as *47 steps, 18 completed, 28
skipped*. Per-step timings name the cause exactly:

```text
737s  step 14  Every wired gate can fail (stub-launcher evidence audit)
246s  step 10  Build ssc launcher (sbt-assembly)
117s  step 12  Integer-literal fail-open regression
 59s  step 13  Scala-fence width parity
```

The audit is expensive BY DESIGN — it re-verifies every blind candidate ALONE against a stub
launcher, which is what makes its verdict reproducible. **Moved to its own `gate-evidence-audit`
job** with its own budget; jobs run in parallel, so this costs no wall-clock.

**AND REMOVING IT WAS NOT ENOUGH, which is the part worth keeping.** The 18 steps that did run cost
~491 s, and this job's own header documents ~11 further minutes for the 17 orphan gates it was
skipping. 491 + 660 + 246 lands ON the old 20-minute line — a job "fixed" by deleting one step goes
straight back to `cancelled`. Budget raised to 30 with that arithmetic written into the file, not a
round number.

**The generalisable half:** both instances are a gate wired into a job that cannot run it, and in
both the failure mode disguised itself — one behind a fail-fast red, the other behind `cancelled`.
Neither is discoverable by reading the workflow; both are obvious the moment the steps actually run.


### 3. `area-map-gate` cannot work on a shallow checkout — and that is why it was red on CI alone

Found after the first two fixes, when `validate` finally ran to completion and step 16 was still red
while the same gate passed locally on the same commit.

`scripts/board-ownership-check` resolves every entry's `fixed-in:` sha with
`git show --format= --name-only <sha>` to learn which board owns that code. `actions/checkout@v4`
defaults to **depth 1**, so almost none of those commits exist in the clone. Measured on ONE tree,
two checkouts:

```text
full history    470 entries resolved from a source path   exit 0
depth-1 clone    83 entries resolved                      exit 1
```

**387 entries silently unresolvable**, which changes the misfiling set, makes baseline rows look
stale, and fails the gate — while a developer with a full clone sees green. That is also why
refreshing the baseline from a local clone did not fix CI: **the two were not looking at the same
data.**

**Fixed with `fetch-depth: 0` on this job's checkout**, with the measurement in the workflow so the
next person does not delete it as a slow default. The other ten jobs stay shallow deliberately —
none of them resolves a historical sha.

**The generalisable half:** a gate whose verdict depends on CLONE DEPTH has to say which it needs.
This one asked git for history it had not requested and reported the shortfall as a content
failure — the same shape as the rest of this entry, where a check ran somewhere it could not work
and the failure did not say so.

## f-gap-tail-2026-08-15 — the crash is fixed; three narrowed defects behind it

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: tests/e2e/f-gap-tail-gate.sh -->

Working the small GAP buckets left after effects and `handler` landed. **One fixed, three narrowed
to minimal reproducers and left open.** Each was measured, and in each case the bucket's NAME was a
poor guide to its cause.

### FIXED — `f"${"x"}"` crashed the compiler (`f9031bd4f`)

`Range [3, 3) out of bounds for length 2`, not a decline. Every string was scanned with the plain
rule, so an interpolated literal ended at its inner quote. Fixed by the reference's three-way split;
the plain-string rows in the gate guard behaviour F already had right. See `f-gap-tail-gate.sh`.

### OPEN 1 — a guarded tuple-of-cons arm drops its binders

```scalascript
(List(5), List(2)) match
  case (ah :: at, bh :: bt) if ah <= bh => println("h " + ah + at)
  case _ => println("no")
```

`F: unbound global: (global at)` · `ref: h 52`. **Remove the guard and it compiles.**

`isTupArmOk` requires `=>` immediately after the pattern, so a guard sends the arm to the var
fallback, which reads `(` as a variable name. The comment above it says "Non-cpat/guarded -> keep the
old var fallback (no corpus need, no regression)" — there is a corpus need now:
`tests/conformance/standard-scala-multifence.ssc`, which reached this defect only once the crash
above was fixed.

Fixing it means giving the nested-arm machinery the guard treatment `parseGenCtorGuard` already has
(parse the guard on the arm scope, then the rest TWICE — once as the default, once on a failed
scope). That machinery carries the obligation/fail-scope logic, and a mistake there is a silent
wrong answer rather than a decline, which is why this is filed rather than rushed.

### OPEN 2 — a placeholder in a CONSTRUCTOR application

```scalascript
def main(): Unit = println(List(1, 2).map(Some(_)))
```

`F: unbound global: (global _)` · `ref: List(Some(1), Some(2))`.

**The def path handles the same shapes.** Measured side by side:

```
g(_, 10)   def call, placeholder + second arg   F fine
g(_)       def call, bare placeholder           F fine
P(_, 9)    ctor call, placeholder + second arg  F: (global _)
Some(_)    ctor call, bare placeholder          F: (global _)
```

Two corpus files: `tests/conformance/dsl-multi-pass.ssc` (`env.get(name).map(Right(_))`) and
`examples/wasm-primes.ssc` (`(2 to limit).filter(!composite(_))` — that one is under a prefix `!`,
which may be a third sub-shape and is NOT yet separated from this one).

### OPEN 3 — `__u0` is NOT the same defect as `_`

Worth stating because I nearly recorded it as one. Both buckets are placeholder-related, so the
tempting reading is "one feature, five files". They report DIFFERENT names — `(global _)` above
versus `(global __u0)` for `examples/content-live-rows.ssc`,
`examples/markdown-toolkit-links.ssc` and `std/ui/content.ssc` — and `__u0` is the name F assigns
when it HAS decided to wrap a placeholder and renamed it. So the `_` files never reached the
renaming and the `__u0` files did: different stages, and not necessarily the same fix.
`f-placeholder-u0-reduced-but-not-solved` holds that half.

## f-curried-clause-param-lost-in-std-agent — `(global handler)`, and it is not what it looks like

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: fdb90875d
     gate: tests/e2e/f-handler-param-gate.sh -->

### FIXED 2026-08-15 — a curried clause on a CONTINUATION LINE, and three sites tested for it

The trigger is the line break, not the parameter:

```scalascript
def mk(a: Int)
      (b: Int): Int =
  a + b
```

`F: unbound global: (global b)` · `ref: 3`. On one line F was always fine.

The layout inserts a separator at that newline — a line beginning with `(` passes `canStartLine`
and fails `isCont`, so it reads as a new statement — and THREE independent places tested `hd(r)`
for the next `(`, found the separator, and each drew a different wrong conclusion:

| site | what it dropped | symptom |
| --- | --- | --- |
| `emitDefU` | the clause itself | `unbound global: (global b)` |
| `curried1` | the def's entry in the curried registry, so `f(a)(b)` NESTED instead of flattening — and this runtime has no partial application | `arity: 2 expected, 1 given` |
| `collectUS3` | a continued `(using …)` clause, so the call site injected no given | the SAME message, from a different registry |

Fixing them one at a time walked the symptom from `unbound global` to that arity message twice.

**The fix is in the def parser, not the layout, deliberately.** Making a leading `(` a continuation
would change how every statement beginning with a tuple is read. Skipping separators is safe at
these three sites and nowhere else, because all three run MID-DECLARATION — the def has not reached
its `=`, so nothing between the clauses can start a new statement. `tuple-statement-after-def` in
the gate is the row that would catch a layout-level fix.

**All eight files now lower under F and agree with the reference.**

### What this says about the instrument

The three synthetic reconstructions recorded below as "refuted" were green for a reason worth
keeping: **they were written on one line**, and the trigger is the line break. Building UP from a
synthetic reproduces the shape you were thinking of, not the shape in the file.

The automated reducer's fixpoint was **not minimal** either. Its declaration slice ran to the end of
the file, so removing a TRAILING `def` also removed the closing fence and always broke the
candidate — three declarations that looked load-bearing came off by hand afterwards. A reducer that
reduces by declaration has to know where the code region ends.

**8 corpus files**, second-largest bucket in `f-gap-census-refresh` once effects landed:
`std/agent.ssc`, `std/agent-mcp.ssc` and the six `examples/rozum-agent*` / `agent-mcp*` that import
them. Every one reports `unbound global: (global handler)` — a name that is plainly a PARAMETER in
the source.

**I first mis-grouped this as part of the effects feature**, purely because `handler` reads like one.
It is an ordinary parameter name. Effects was 11 files, not 19.

### The reduction, at a fixpoint — 637 lines to 57

Reduced BY DECLARATION with well-formedness inside the predicate: a candidate survives only if the
REFERENCE front still RUNS it (rc 0) **and** F still reports `(global handler)`. Without the first
half the reducer simply deletes `handler`'s own declaration and declares victory. It ran against a
COPY at the repo root — verified to keep both properties — so `std/agent.ssc` was never swapped in
and out, and an interrupted run leaves nothing behind.

The 57-line fixpoint, code only (frontmatter and `exports:` retained, prose dropped):

```scalascript
[httpPost, httpPostStream, Response](std/http.ssc)
[JsonValue, jsonValue, jStr, jNum, jBool, jField, jObj, jArr](std/json.ssc)

case class AgentTool(
  name: String,
  description: String,
  parametersJson: String,
  handler: String => ToolResult
)

def agentTool(name: String, description: String, parametersJson: String)
             (handler: String => ToolResult): AgentTool =
  AgentTool(name, description, parametersJson, handler)

def toolResultMessage(callId: String, result: ToolResult): String =
  jObj(List(
    jField("role", jStr("tool")),
    jField("tool_call_id", jStr(callId)),
    jField("content", jStr(result.contentJson))
  ))
```

Every remaining declaration is load-bearing: removing any one flips the verdict.

### Refuted, so nobody re-walks it

**`package:` is NOT involved.** Deleting `package: std.agent` from the reduction leaves the verdict
unchanged — still `GAP (global handler)`. That is worth stating loudly because the same field WAS
the standing explanation for `f-package-namespace-breaks-on-an-object-with-extends`, where it also
turned out to be a correlate rather than a cause.

**Synthetic reconstruction fails to reproduce it.** Three built-up probes are all GREEN on F: a
curried second-clause parameter used in the body; the same captured inside a trailing-brace lambda
passed to another curried def; and the same with a generic `[A]` on the outer def. So the shape is
narrower than "curried clause parameter", and building UP from synthetics does not find it —
reduce DOWN from the file, which is what produced the fixpoint above.

### Where to start

Note what survived: a `case class` whose FIELD is named `handler` and typed `String => ToolResult`,
AND a curried def whose second-clause parameter has the same name and type, AND a third def that
uses neither. `ToolResult` itself is not declared in the reduction — the reference erases types, so
it still runs. Whether the collision between the field name and the parameter name matters is the
first thing to test, and it is a guess: the obvious suspect in this front has been wrong on three
consecutive days.

## no-two-type-checkers-in-this-repo-agree — the census, six lanes, seven programs

<!-- status: open
     lane: multi
     area: front
     kind: bug
     confirmed: yes
     gate: tests/e2e/declared-type-agreement.sh -->

**Measured 2026-08-15 on one freshly built toolchain.** Seven programs, each isolating ONE typing
question, run through every lane and every checker entry point this repository has. `✗` is a
rejection, anything else is what the program PRINTED.

| program | v2 native | v1 interp | `ssc-tools check` | js | jvm | v3 |
|---|---|---|---|---|---|---|
| `def f(a: Int) = a; f("x")` | `x` | `x` | OK | **`120`** | ✗ scalac | `x` |
| `def g(a: Int): String = a; g(1)` | `1` | `1` | **✗ expected String, found Int** | `1` | ✗ scalac | `1` |
| `val x: Int = "s"` | `s` | `s` | **✗ expected Int, found String** | `s` | ✗ scalac | `s` |
| `def h(a: Int) = a + 1; h("x")` | **✗ TYPEERR** | **`x1`** | OK | **`121`** | ✗ scalac | **`x1`** |
| `def z(): Int = 1; z(5)` | **✗ TYPEERR** | ✗ runtime | OK | **`1`** | ✗ scalac | ✗ runtime |
| `def two(a)(b); two(1)(2)(3)` | **✗ TYPEERR** | ✗ runtime | OK | **`3`** | ✗ scalac | ✗ runtime |
| control: `ok(41)` → 42 | `42` | `42` | OK | `42` | `42` | `42` |

**NO TWO COLUMNS AGREE ON ANY ILL-TYPED ROW.** That is the finding. Six of the seven programs are
ill-typed under any reading, and each of them is treated differently by at least three lanes.

**The single worst cell, and it is not a rejection — it is two different wrong ANSWERS.**
`def h(a: Int): Int = a + 1; h("x")` gives:

    v2 native  ✗ TYPEERR: cannot unify Int: Int vs String
    v1 interp  x1        ← String concatenation
    js         121       ← char code of 'x', plus 1
    jvm        ✗ scalac
    v3         x1

Nothing warns. A program with a genuine type conflict silently produces `x1` on two lanes and `121`
on a third.

**js NEVER rejects anything.** Six of six ill-typed programs run and print, including the two wrong
values above and `z(5)` → `1`, where an argument to a zero-parameter function is silently discarded.
It is the only lane with no rejection in the whole table.

**AND THE TWO CHECKERS THIS REPO OWNS ARE COMPLEMENTARY HALVES OF ONE CHECKER**, which is the most
useful thing the census says:

| | reads declarations | infers from use |
|---|---|---|
| `v2/lib/ssc1-check.ssc0` (809 lines, Algorithm W, ON the native run path) | **no** — `parseParam` calls `skipTypeAnnot` | **yes** — the only lane that rejects `h("x")`, `z(5)`, `two(1)(2)(3)` |
| `v1/…/typer/Typer.scala` (2084 lines, only under `check`/`watch`/`compile-*`) | **partly** — return types and `val` types, NOT parameters | **no** — passes all three of those |

Neither is wrong-headed and neither needs replacing. **v2 has inference without declarations; v1 has
declarations without inference.** The union of the two is a type checker; each alone is half of one.

**Two consequences that need no contract decision:**

1. **`ssc-tools check` passes programs that then crash on its own lane.** `z(5)` and `two(1)(2)(3)`
   are OK to `check` and die at runtime under `run --v1`. A check that green-lights a program its own
   runtime refuses is worse than no check, because it is consulted instead of the runtime.
2. **The v1 Typer is not on the `run` path at all** — `run` never type-checks on the interpreter
   lane. Whatever the contract turns out to be, "the checker exists but nothing runs it" is not it.

**What still needs Sergiy's decision** (`tests/SPRINT.md`, TYPES MUST BE RIGHT, step 3): whether a
declared type is documentation, a coercion hint, or a constraint. The census does not decide it — it
prices it, and it shows that the *disagreement* is a separate defect from the *choice*.

**Done when** `tests/e2e/declared-type-agreement.sh` runs this battery and requires ONE verdict per
row across every lane. Agreement is required by all three possible contracts, so the gate can be
built before the decision and will encode it afterwards.


### The mechanism, and it is not "the annotation is discarded" — THE CHECKER AND THE LOWERER USE DIFFERENT PARSERS

**Measured 2026-08-15 by reading both fronts.** There are two parsers in the native tier and they do
NOT agree about type annotations:

| parser | what it does with `p: Int` | who uses it |
|---|---|---|
| `v2/lib/ssc1-front.ssc0` | **erases it** — `parseParam` calls `skipTypeAnnot` | **the CHECKER**: `ssc1-check.ssc0` imports this front |
| F, `specs/v2.2-p6.5-fsub.ssc` | **captures it** — `pushParamTy` embeds the name as `name:Type`, and `localTyOf` reads it back | the DEFAULT native LOWERER since 2026-07-23 |

So the annotation is not universally thrown away: **the lowerer keeps it and the checker never sees
it**, because they read the file with different parsers. That is a sharper and more fixable statement
than "declared types are dropped".

**F's capture is deliberately narrow, and the narrowness is documented in F itself:**

    def knownTyName(tn) = if tn == "Int" then true else (if tn == "String" then true else tn == "BigInt")

Only `Int`, `String`, `BigInt`, and only when the type is *immediately* closed by `,` or `)`. Its own
comment: *"Generic/tuple/arrow/vararg/defaulted types don't match knownTyName+paramEndTok so they
push the bare name (stays `?`)."* And the reference lowerer erases types entirely — *"Types are
ERASED by the reference lowerer (measured: `def g(a: String, b: String)` -> `(lam 2 ...)`), so
skipping them is byte-faithful."*

**What this changes about the work.** Making a declared type a CONSTRAINT on the native lane does not
start from nothing: the extraction already exists for the three commonest types in the commonest
syntactic position, and it is already on the default path. The missing link is that the checker
parses with the OTHER front. Whoever implements step 2 should measure that link first — feeding the
checker what F already captures may be a far smaller change than teaching `ssc1-front.ssc0` to keep
annotations, and it avoids a second, divergent extraction.

**Carry the warning that comes with it:** `scripts/BUGS.md` records that `Long` could not be added to
`knownTyName` without finding every consumer of the embedded `name:Type` form, and that the failure
mode there is a SILENT COVERAGE LOSS rather than a compile error. Any widening of that set needs the
consumer census first.

## f-effect-declarations-and-handlers-unsupported — FIXED, and it was a translation

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: 454efb1a1
     gate: tests/e2e/f-effects-gate.sh -->

The census ranked algebraic effects as F's largest single gap. **All eleven files now lower under F
and agree with the reference front**, including both multi-shot ones.

**It is a translation, not a feature, because the runtime owns the continuations.** The reference
lowerer spends seventeen mentions on effects and its `multi_effect` case returns `Nil`. So `multi`
costs nothing beyond choosing a different prim and a different label shape, and splitting the work
along that boundary — which was considered — would have bought nothing.

```
effect E:        def op(a, b)   ->  (def E_op (lam 2 (prim effect.perform.oneshot "E" "op" a b)))
multi effect E:  def op(a)      ->  (def E_op (lam 1 (prim effect.perform "E.op" a)))
```

**Most of it already existed**, measured before a line was written: F already turns a `{ case … }`
block argument into a case lambda, chains trailing block arguments into a curried application, and
joins a dotted constructor tag. `handle(body) { case E.op(a, resume) => … }` needed no new parsing.

### The marker, which is the part worth reading

A handler root is identified by the MARKER the front emits, **not by the lambda's shape**
(`v2/src/CoreIR.scala` `HandlerDispatchShape`). The shape test used to be
`arity == 1 && Match(Local(0), …)` — which is also every ordinary `x => x match { … }`, so each of
those was paying a ThreadLocal read, two allocations and a try/finally per call; `range-sum`, a
program with no effects at all, spent 36 profile samples on it. Removing the shape test is what
makes the marker load-bearing.

Both plausible fixes are wrong, and each was measured:

```
no marker at all         match: no arm for Return/1     the runtime never opens a dispatch, so the
                                                        Return(v) it delivers at the end finds no arm
miss-only default        match: no matching case        handlerDispatchMiss RAISES unless an exact
                                                        root dispatch is already pending
mark every arm body,
add NO default           works                          the absent default IS the Unhandled path
                                                        for a recognised root
```

That mirrors `lowerDirectHandlerMatch` (`ssc1-lower.ssc0`). The marking is applied to the emitted
arm string rather than inside `parseArmBody`, because that function is shared with every other match
in the language and the handler is its only caller that wants this; only top-level arms are marked,
so a nested match inside an arm keeps its own arms untouched.

### What was NOT needed

An IR dump. I built one — fsub's own source plus an appended `compile(<src>)` — and it died with
`TYPEERR: in def parsePatAtom1: if branches must have the same type` before printing anything. The
fix came from reading the runtime contract instead. That `TYPEERR` is the one unexplained entry in
the census's ERROR bucket and is left for someone to chase.

## ci-validate-fails-early-and-skips-every-later-step — 25 checks, 16 with no other home

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: .github/workflows/ci.yml
     fixed-in: 07d770988 -->

**`ci.yml`'s `validate` job is fail-fast, so the FIRST red step hides every step after it.** Measured
over the last twelve `ci.yml` runs — the failing step is read from the job's own step list, not
guessed from the log:

```text
date        failed at                                    steps skipped
2026-08-05  13: benchmark wrapper seed typing            11
2026-08-06  13: benchmark wrapper seed typing            12
2026-08-07  13: benchmark wrapper seed typing            12
2026-08-08  13: benchmark wrapper seed typing            12
2026-08-09   8: BUGS.md machine-readable header          19
2026-08-10   8: BUGS.md machine-readable header          20
2026-08-11  10: path→area map                            18
2026-08-12  10: path→area map                            20
2026-08-13   9: TASK/ generated regions                  21
2026-08-14  10: path→area map                            21
2026-08-14  10: path→area map                            22
2026-08-15  10: path→area map                            25
```

**Red on every run for eleven days, with a MOVING cause** — which is why it never looked like one
bug anyone owned, and why the skipped count grew as people kept adding steps behind it.

**THE JOB HEADER'S COVERAGE CLAIM IS FALSE, and that is what makes this cost something.** It states
*"Every gate this job runs is also in the smoke suite (scripts/smoke-ci.ssc)"*, offered as the reason
taking the job off push was *"safe rather than a coverage cut"*. Measured against
`scripts/smoke-ci.ssc`: **13 of 29 steps are in smoke, 16 are NOT** —

```text
area-map-gate            bench-seed-type-gate        std-import-lanes-gate
package-keyword-smoke    parameterless-def-import    legacy-front-parameterless
build-rust-refuses-loudly  rust-toint-parity         rust-http-lane-parity
http-bind-address-gate   rust-string-length          rust-route-handler-shapes
bytecode-fallback-visible  launcher-digest-gate      claim-mutex ×2
```

Those sixteen have no home anywhere else. Taking `validate` off push WAS a coverage cut for them,
and the comment arguing otherwise is the thing that made it invisible.

**Found by adding a gate to this job and getting no verdict.** `http-bind-address-gate` was wired
into `validate` on 2026-08-15 and skipped on its first CI run — step 53 behind a step-10 red.

**FIXED by `if: always()` on the 33 verification steps**, leaving `Checkout` and `Setup Scala CLI`
fail-fast because nothing can run without them.

**Safe, and measured before landing rather than hoped:** all 29 single-command steps were run locally
on this tree — **28 pass, 1 fails** (the path→area map, board-ownership drift). So this reveals no
hidden pile; it restores 25 checks and leaves the one red that is already visible today. The
board-ownership backlog is the current OCCUPANT of the first slot, not this defect, and is left to
whoever owns that routing.

## f-summon-and-context-bounds-are-unresolved — and the one-line fix is WRONG

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     gate: none — open defect -->

**Filed, not fixed, deliberately.** The obvious fix is already half-written in the file and it
produces silent wrong answers. This entry exists mainly to say so before somebody ships it.

`summon[TC[A]]` inside a context-bound def is unresolved on F:

```scalascript
trait M[A]:
  def empty: A
given intM: M[Int] with
  def empty: Int = 0
def total[A: M](xs: List[A]): A = summon[M[A]].empty
def main(): Unit = println(total(List(1, 2)))
```

```
F     ssc: unbound global: (global summon)
ref   0
```

A CONCRETE type argument already works — `summon[M[Int]].empty` is fine since `29eb7dec4`. What
fails is the abstract one, where `parseSummon`'s `findGivenF` cannot match `A` and `summonEmit`
falls through to the bare `(global summon)`.

**Five files**: `std/semigroup-monoid.ssc`, `std/index.ssc`, `tests/conformance/std-index.ssc`,
`std-semigroup-monoid.ssc`, `tagless-context-bounds.ssc`. Three of them arrived here TODAY, each
after its first cause was cleared — the bucket grows as others shrink, which is the census's
short-circuit warning working as designed.

### Why the obvious fix is wrong, measured

`findAnyGivenF` already exists in `specs/v2.2-p6.5-fsub.ssc` and is already used by
`usingGivenFor` for `using`-parameter injection. Making `summonEmit`'s `None` branch fall back to it
is a one-line change, and it mirrors what the reference front's `computeActiveCtx` appears to do
(`findGiven(tab, tc, "*")`, else `findAnyGiven`). **It is still wrong**, because the resolution is
not static:

```scalascript
given intM: M[Int] with    def empty: Int = 0
given strM: M[String] with def empty: String = "x"
def z[A: M](xs: List[A]): A = summon[M[A]].empty
z(List("a", "b"))   ->  x
z(List(1, 2))       ->  0
```

Reference front AND v1 interpreter both answer in CALL ORDER — `x` then `0` — from a single
definition of `z`. A static pick would answer the same value twice. So `summon` in an abstract
context is resolved per call, and the one-liner would turn a clean decline into a silent wrong
answer on any program with two instances of one typeclass. `std/semigroup-monoid.ssc`, the heaviest
file in this bucket, declares THREE `Monoid` instances.

**A sound partial exists and is not worth taking**: resolving statically only when the table holds
exactly ONE given for that TC is unambiguous and would fix `tagless-context-bounds.ssc`. It fixes at
most one of the five, and adds a special case the real fix deletes.

### What the real fix looks like

The reference lowers `summon[TC].m` to `(app (var <gname>_m) args)` via `lookupActiveCtx` — static —
yet behaves dynamically, so the dispatch is happening through the value it builds for a given set:
`summon[TC]` in VALUE position lowers to `IrGlobal("__summon_value_" ++ tc)` (`ssc1-lower.ssc0`).
That is a runtime dispatcher over the instances, and F already has the analogous machinery for
extension methods (`emitOneExtDispatcher`, the `extdisp` node). The work is to build the same
dispatcher for given instances and route the abstract-`summon` case through it.

**Not verified**: I have not read how `__summon_value_TC` is constructed, only established from
three lanes that the behaviour is per-call. Whoever takes this should start there, and should keep
the two-instance probe above as the acceptance test — it is the one that fails for the tempting fix.

## f-front-exit-code-replaces-the-real-diagnostic — and the defect it hid

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: d2976b1b5
     gate: tests/e2e/f-front-exit-reason-gate.sh -->

**Two halves. The first is why the second went unread, and only the second is fixed here.**

### The defect (fixed)

`ssc run std/index.ssc` exited 1 on F and 0 on the reference front. Five corpus files, one
mechanism — all of them markdown whose only fences are ```` ```sh ```` / ```` ```text ````:
`std/index.ssc`, `std/graphql.ssc`, `examples/deploy.ssc`,
`examples/frontend/dashboard/dashboard.ssc`, `examples/frontend/busi-dashboard/busi-dashboard.ssc`.

F's runner has an ordering pass the reference front **does not have at all** — `sscOrderRoot`,
`v2/bin/ssc1-run-fsub.ssc0` — because F assembles its closure from SOURCE and therefore has to order
the module graph first. That pass called `#io.exit(1)` when a root had no `scalascript` fences.
`sscLoadMod`, a few lines above and IDENTICAL in both runners, does the opposite, and its comment
states the principle the exit violated:

> *"Short-circuiting to an empty program instead only trades `exit 1` for an ABI error."*

Because the reference runner has no ordering pass, the exit had no counterpart to disagree with, and
the divergence was F's alone.

The stderr note is KEPT deliberately — "this file has no code" is worth saying, and it was the EXIT
that diverged, not the message. `note-still-printed` fails if a later change silences it too.

Four of the five now lower under F. The fifth, `std/index.ssc`, ADVANCES to `(global summon)` — the
context-bound bucket — so its gate row asserts *the old failure is gone* rather than asserting `F`,
which would be asserting somebody else's fix and would go red when nothing had regressed.

### The instrument hole (NOT fixed — filed, with the reason)

The census had exactly one bucket that named a **number** instead of a mechanism:
`native frontend exited with 1`. The real diagnostic existed the whole time and went to the console;
`RunNativeV2.lowerNative` throws the exit code alone, and that string is what `ssc info
--front-report` records as the reason. **A bucket that names no mechanism cannot be ranked**, which
is how five files sat at the bottom of the census list for a week.

I wrote that fix and reverted it. `TowerResult.output` is the tower's captured **stdout**;
`#io.eprint` resolves to `Console.err` (`v2/src/Runtime.scala`), and `runTower` wraps only
`Console.withOut`. Appending `output` would have attached unrelated IR text and labelled it "the
reason" — a worse lie than the exit code, and one that would have looked right in a gate.

Doing it properly means **teeing** stderr — capture *and* pass through — or every normal run loses
its front diagnostics. That is a larger change, and after the fix above there is no reachable
subject left to gate it with: of F's three `#io.exit` sites the other two are usage errors
`front-report` cannot reach. Worth doing when something else makes the tower exit non-zero; not
worth bodging now.

**The general point, since this is the third instrument defect this week:** an instrument that
reports a status code where it could report a reason silently demotes a whole class of defects to
unrankable. The census header already says every count is a lower bound; this says the *labels* can
be lower bounds too.

## inbox-gate-selftest-fixtures-age-out — smoke went red for everyone at a date boundary, with no commit to blame

<!-- status: fixed
     fixed-in: bab09473e
     lane: apparatus
     area: other
     gate: tests/e2e/inbox-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**`inbox-queue` (`tests/e2e/inbox-gate.sh --self-test`) failed on plain `origin/main`, and no change
caused it.** The queue's age bound is 14 days; every fixture the self-test expects to be ACCEPTED
carried the literal `reported-at: 2026-07-31`. On 2026-08-15 those fixtures turned 15 days old, the
queue refused them as stale, and the ACCEPT half of the suite inverted:

    FAIL  --self-test: a reporter diagnosis was REJECTED — the queue is refusing information again
    FAIL  --self-test: a WELL-FORMED entry was rejected
    FAIL  --self-test: a body heading was read as an entry — one report becomes several

Three assertions about the entry PARSER, all failing for a reason that has nothing to do with
parsing, and each with a message pointing at a defect that did not exist. Whoever read them would
have gone looking in the heading regex.

**Diagnosed by control before anything was touched:** the same suite, same tree, same commit, under
`SSC_INBOX_MAX_AGE_DAYS=30` — `inbox-gate: OK`. One environment variable separated red from green,
which named the mechanism without a bisect.

**The asymmetry is the bug.** The "older than the limit" fixture was ALREADY relative —
`old="$(date -u -j -v-400d …)"` — so the file's author knew a fixture must not be pinned to the
calendar. Only the fresh ones were absolute. The fix gives `old` its missing twin, `fresh`
(yesterday, computed the same way), and uses it for every fixture date.

**The refuse cases were re-dated too, and that is not tidiness.** An `expect_red` fixture that has
aged out is red for the WRONG reason: today, all five would have kept passing while no longer
testing `triage: routed`, a missing `reported-by`, a missing `ssc-version`, `needs-info` without
`waiting-on`, or a `lane:` left behind. The suite's refuse half was already coasting on age, and the
green would have outlived the checks.

**Verified across the bound rather than at it.** 13 ok / 0 FAIL at `MAX_AGE_DAYS` of 14, 30, 100 and
365 — every refusal fires for its own reason across a 26x range — while the 400-day fixture is still
refused, which is the one case that SHOULD depend on age.

**Worth generalising:** a self-test whose fixtures carry absolute dates has a scheduled failure in
it. Nothing was wrong with the code on 2026-08-14 and nothing was fixed on 2026-08-15; the gate was
counting down the whole time. `git grep -n "reported-at: 20" tests/` is the cheap sweep for the rest.

## f-compiler-crashes-with-no-arm-for-cons2 — a member call on a `given … with` object

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: yes
     fixed-in: 29eb7dec4
     gate: tests/e2e/f-cons2-no-arm-gate.sh -->

### FIXED 2026-08-15 — two registrars, one reader, and one was never widened

`objReg` maps an object name to its members, and its payload used to be a bare list of names. It
became a PAIR — `(memberNames, varMemberNames)` — because a `var` member lowers to `O_v__cell` and
the selection site has to know that from outside the object. `collectObjReg` was updated;
**`collectGivenObj`, which registers `given … with` bodies into the SAME table, kept emitting the
bare list.**

`objMemsOf` reads it as `fst(snd(h))`. On an object entry that is the first half of a pair; on a
given entry it applied `fst` to a LIST — and a `Cons` arriving at a Tuple2-only match is exactly
`match: no arm for Cons/2`. The suspect this entry named (the given TABLE, `findGivenF1`) was the
wrong table: it is the objReg, one lookup further along.

**The ruled-out table above is what solved it.** `summon[M]` alone fine, `val x = summon[M]` then
`x.empty` fine, `summon[M].empty` crash, `intM.empty` with no `summon` in the file crash — the
receiver being the given's own global NAME is the whole condition, and `summon` was incidental
because it resolves *to* that name.

**Where the five files went.** Three now lower under F and agree with the reference —
`examples/typeclass.ssc`, `tests/conformance/std-monaderror.ssc`, `tagless-multi-file.ssc`. The
other two, `std-index.ssc` and `std-semigroup-monoid.ssc`, ADVANCE to a different cause,
`unbound global: (global summon)` — the context-bound bucket. That is the census's short-circuit
warning behaving exactly as it says: every count in it is a lower bound.

`f-output-agreement-gate` over 639 files after the fix: F-wrong 0, agree 283, measured 318.

**F's COMPILER crashes**, rather than F declining a construct. Five lines:

```scalascript
trait M:
  def empty: Int
given intM: M with
  def empty: Int = 7
def main(): Unit = println(intM.empty)
```

```
F     ssc: match: no arm for Cons/2
ref   7
```

`intM.f(4)` — a member WITH arguments — fails identically, so it is not about the parameterless
form. There is no `summon` anywhere in the reproducer.

**Third in the census's GAP ranking**, and all five files report this exact string:
`examples/typeclass.ssc`, `tests/conformance/std-index.ssc`, `std-monaderror.ssc`,
`std-semigroup-monoid.ssc`, `tagless-multi-file.ssc`. Every one of them calls a given by NAME —
`intShow.show(42)`, `intSum.combine(…)` — which is the shape above.

**NOT a regression from 2026-08-14's four F fixes.** Reproduced on the toolchain built from
`348579049`, which predates all of them: same message, same two shapes.

### What is ruled out, measured rather than assumed

| shape | F |
| --- | --- |
| `intM.empty` — given by name | **crash** |
| `intM.f(4)` — given by name, with args | **crash** |
| `summon[M].empty` | **crash** |
| `summon[M]` with no member | fine (`<foreign>`, same as the reference) |
| `val x = summon[M]` then `x.empty` | **fine** — 0, agrees |
| `def z[A](xs: List[A])(using m: M[A]) = m.empty` | fine — the receiver is a local |
| `z[Int].toString` — a parameterless generic def | fine |
| `mk[Int](3).length` — type application then member | fine |

So it is neither `summon` nor type application: **the receiver being the given's own global name is
what breaks it.** Bind the same value to a local first and the member call compiles.

### Where to start looking, and what the message means

`no arm for Cons/2` is F's runtime rejecting a `Cons` value at a match whose arms are `Pair`/`Tuple2`
— `genPairArm` emits `(arm Pair 2 …) (arm Tuple2 2 …)` for a 2-tuple pattern and no `Cons` arm. So
some list is reaching a site that destructures a PAIR. The given-table lookups are built out of
nested pairs and matched that way — `findGivenF1` does `h match { case (gtype, gname) => gtype match
{ case (gtc, gt) => … } }` — and one of those entries is arriving as a list.

**Not verified.** The obvious suspect has been wrong twice in this area on consecutive days
(`parseConsArm1` yesterday, `(global Parser)` the day before), so this names a place to instrument,
not a cause. The cheap next step is to print the table shape from a driver rather than to read
further: the crash is inside F's own execution, so an F0 IR dump of `collectGivenReg`/`givenTabOf`
output on this five-line file answers it directly.

## f-cons-pattern-with-a-nil-tail-loses-the-head-binder — `case h :: Nil` declines, `case h :: t` does not

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: 6eb863449
     gate: tests/e2e/f-cons-nil-tail-gate.sh -->

### FIXED 2026-08-14 — and the suspect this entry named was WRONG

`parseConsArm1`'s positional read is innocent. It is never reached for this shape: `parseMatchArms`
sends a match to the ORDERED resolver when any of its arms is nested, and a `Nil` tail counts as
nested — so `case h :: Nil` leaves the ctor path entirely and lands in `parseGenArm`, **which had no
cons rule at all**. It fell through to `parseGenVar`, which reads `h` as a catch-all VAR arm bound to
the whole scrutinee and takes `:: Nil => …` as the body.

That makes the real defect worse than this entry described. It is not only a mis-bound name:

```
xs match { case h :: Nil => "one:" + h  case _ => "other" }

  f(List(5))     F: one:List(5)      ref: one:5
  f(List(4, 5))  F: one:List(4, 5)   ref: other      <- the WRONG ARM
```

A catch-all tests nothing, so every list took the one-element branch. The nine scljet files
declined rather than answering wrongly only because their helpers are recursive and the bad arm
desynchronised the parse; a non-recursive one answers, silently.

**Fix:** route unguarded cons arms in the ordered resolver through `parseNestedArm`, which already
binds fields, collects the obligations refined fields impose, discharges them against a fail-scope
and makes the remaining arms the default. `parsePatF` already reads `h :: Nil` as
`("cpat", ("Cons", [h, Nil]))`, so this is routing, not new machinery — and it covers
`a :: b :: Nil` without a second case.

**Left undone deliberately:** a GUARDED cons arm (`case h :: t if g`) still declines.
`parseNestedArm` assumes the token after the pattern is `=>` and would swallow the guard as the
body, turning an honest decline into a wrong answer, so `isConsArmHead` requires the `=>`.

**The warning in this entry was the useful part.** It said to check the other resolver before
believing a green gate — and the other resolver turned out to be where the whole defect lived.

Found by ranking F's refusals rather than by anyone hitting it: it is the second-largest bucket in
`f-gap-census-refresh`, **9 files, all `tests/conformance/scljet-*`**, every one of them reporting
`unbound global: (global h)` — a name that is right there in the pattern.

Two lines, and the control is the line beside it:

```scalascript
def show(xs: List[Int]): String = xs match { case h :: Nil => "one:" + h  case h :: t => "many:" + h  case _ => "empty" }
def main(): Unit = println(show(List(5)))
```

```
F     ssc: unbound global: (global h)
ref   one:5
```

Delete the `case h :: Nil` arm and F answers `many:5`. So the cons pattern itself is fine; what F
cannot do is a cons pattern whose TAIL is a constructor rather than a variable.

**Where to start looking, not what to fix.** `parseConsArm1` (`specs/v2.2-p6.5-fsub.ssc`) reads the
pattern POSITIONALLY:

```
def parseConsArm(ts, menv, cx) = parseConsArm1(snd(hd(ts)), snd(hd(tl(tl(ts)))), tl(tl(tl(ts))), menv, cx)
def parseConsArm1(h, t, ts, menv, cx) = parseArmBody("Cons", 2, t :: h :: Nil, ts, menv, cx)
```

head token, then the token two along taken as the TAIL BINDER'S NAME, then the body. For
`h :: Nil` the token two along is the constructor `Nil` (kind 3), so a binder literally named `Nil`
is pushed and the arm is emitted as `(arm Cons 2 …)` — which is why the diagnostic names `h` and not
`Nil`: whatever goes wrong, the head is not where the body looks for it. **Not verified beyond the
reproducer** — the positional read is the obvious suspect and it should be confirmed before it is
believed, because today `(global Parser)` pointed three imports away from its cause.

**Check the OTHER resolver before believing a green gate.** `parseArm` and `parseGenArm` are two
independent arm parsers chosen by the match's FIRST pattern, and a fix to one is invisible to rows
that only reach the other — that is exactly how `f-at-bind-pattern-emits-unbound-underscore` nearly
shipped half-done today. A gate here needs rows whose first arm is a scalar as well as rows whose
first arm is the cons pattern.

**Nine files, one shape.** `scljet-correlated-dml`, `scljet-sql-value-compare`, `scljet-insert-null`,
`scljet-ipk-numeric-affinity`, `scljet-sql-correlated-join`, `scljet-large-page`,
`scljet-update-ipk-moves-rowid`, `scljet-sql-null-semantics`, `scljet-sql-double-equals` — all carry
`case h :: Nil` in a `showRow`/`showValue` helper.

## f-gap-census-refresh — 771 files, and the largest F gap is a FEATURE, not a defect

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: eb88be5fc
     gate: scripts/f-gap-census -->

Measured 2026-08-14 after the day's three F fixes, over **771 files** — `examples/`,
`examples/frontend/`, `tests/conformance/` and all of `std/`. The board's two standing census entries
were taken on a 140-file sample in early August and both are superseded here, not contradicted.

```
771 subjects     F 496     BOTH-UNBOUND 198     GAP 57     ERROR 20
```

**F's real denominator.** 198 `BOTH-UNBOUND` and 20 `ERROR` are outside F's decision — the first is
the reference front's static validator objecting too, the second is 12 files the reference front
cannot parse at all plus 5 with broken import paths in the example itself. So F's lowering decides
**553** files and handles **496 — 89.7%**. Quoted against the raw 771 it reads 64%, and the earlier
140-file sample read 76%.

**RE-MEASURED after `6eb863449` (the `h` bucket's fix), same 771 subjects:**

```
before   F 496   BOTH-UNBOUND 198   GAP 57   ERROR 20      F decides 553, handles 496 — 89.7%
after    F 505   BOTH-UNBOUND 198   GAP 48   ERROR 20      F decides 553, handles 505 — 91.3%
```

The `h` bucket went to zero and **nothing moved to a different reason** — the nine files went
straight to `F`, so there was no second cause queued behind that one. Worth stating because the
opposite is what happened earlier the same day: fixing `(global Parser)` sent six files to `F` and
two to `(global _)`. Every other bucket is byte-identical, which is also the check that the fix
touched nothing else.

### The GAP bucket, 57 files, ranked by mechanism

The reason string embeds the offending NAME, so the raw strings scatter; grouped by name they are
three real groups and a tail.

```
19  effect (9) · handler (8) · multi (2)    the algebraic-effects feature
 9  h                                        `case h :: Nil` — a cons pattern whose TAIL is a ctor
 5  —                                        native frontend exited with N
 5  —                                        match: no arm for T/N
 3  __u0                                     the known placeholder binder
 2  summon · 2 Foldable · 2 _                typeclass machinery, placeholder
 8  singletons                               row, path, of, K, grade, fixed, C, at, approxBytes
 1  —                                        Range out of bounds (standard-scala-multifence)
```

**The largest gap is a missing FEATURE, and that is worth saying plainly.** `effect` / `handler` /
`multi` is algebraic effects: nine `tests/conformance/effect-*` cases, `std/agent.ssc`,
`std/agent-mcp.ssc`, and six `examples/rozum-agent*` / `agent-mcp*` that import those two std
modules. That is 19 of 57 — a third of everything F still refuses — and it is not a defect anyone
can fix in an afternoon; it is a feature F does not implement.

**The second group is a two-line defect holding nine files**, all `tests/conformance/scljet-*`:

```
def show(xs: List[Int]): String = xs match { case h :: Nil => "one:" + h  case h :: t => "many:" + h  case _ => "empty" }
def main(): Unit = println(show(List(5)))

F     ssc: unbound global: (global h)
ref   one:5
```

`case h :: t` compiles; `case h :: Nil` does not. `parseConsArm1` reads the cons pattern
POSITIONALLY — head token, then the token two along as the tail's binder NAME — so a tail that is a
constructor rather than a variable is bound as if it were one, and the real binder falls out of the
arm's env. Filed separately as `f-cons-pattern-with-a-nil-tail-loses-the-head-binder`.

### The two standing entries, re-measured on 5.5× the sample

**`both-unbound-is-mostly-plugin-intrinsics-not-user-error` — CONFIRMED and widened.** All 198 rows
carry the reference front's own objection, and it is dominated by names the RUNTIME binds:

```
21 runActors  15 __yamlSection__  14 sqlSection  12 spark  10 self  8 scope  7 oauth  6 route
 5 awaitClient  4 generator  3 spawn/signal/runAsync/runAsyncParallel/htmlToPdfBase64/NamedHandler
10+ *_derived  (JsonCodec_derived, ObjectCodec_derived, VertexCodec_derived, SparkSchemaCodec_derived)
```

83 distinct names, and the head of the distribution is plugin intrinsics and synthesised typeclass
instances — not user error. The label still blames the program.

**`error-bucket-holds-no-F-gaps` — CONFIRMED.** 17 of the 20 are not F's decision at all: 12
`native frontend rejected incomplete parse` (the REFERENCE front), 5 `import not found` (the example
is wrong). The remaining three are one `TYPEERR`, one `match`, one `if-then-no-else-after-while`.

### How to read this table, and how not to

Three ways it lies, all measured rather than supposed, and all now in `scripts/f-gap-census`'s
header:

- **a refusal SHORT-CIRCUITS**, so every count is a LOWER BOUND. Today eight files said
  `(global Parser)`; after the fix six became F and two moved to `(global _)`. Re-run after every
  fix — this is a ranking, not a work plan with fixed sizes.
- **the reason is not the cause.** `(global Parser)` was reported against files three imports from
  the bodyless `object` that caused it.
- **transitivity inflates the count.** The 8 `handler` files are 2 std roots and 6 dependents —
  verified: `examples/rozum-agent.ssc` and `agent-mcp-server.ssc` both carry `](std/agent.ssc)`.

Shipped as a script rather than a sweep for exactly the first reason. `scripts/f-gap-census
--self-test` asserts the bucketing collapses NAMES and keeps MECHANISMS in both directions, and the
run refuses to report unless it got a verdict for every subject — an empty result is a broken run,
never data, which twice today it silently was.

## v2-jit-size-measured-the-neighbours-and-called-it-green — the module carrying `ssc.Prims` was dropped from the target list whenever its class dir was absent

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: 86eeb16f0
     gate: tests/e2e/v2-jit-size.sh -->

**Found while verifying my own fix, which is the only reason it was found at all.** `v2-jit-size.sh`
built its target list from three module class directories and fell back to the staged jars **only if
ALL THREE were missing**. After a plain `./install.sh --dev` the ordinary state is `v2/src/target/
scala-*/classes` absent and the two small modules' present. The list is then NON-EMPTY and does not
contain `ssc.Prims` — so the gate censused two minor modules, found nothing over the limit, and
printed `PASS`.

Measured, not reasoned:

```text
rm -rf v2/src/target/scala-*/classes && ./install.sh --dev && tests/e2e/v2-jit-size.sh
  ok    v2/backend-jvm-bytecode/…/classes — no method >= 6000
  ok    v2/jvm-runtime/…/classes          — no method >= 6000
  v2-jit-size: PASS
```

…while `ssc.Prims.methodDispatch8` was **8406 bytecodes**, over HotSpot's 8000 limit and therefore
never JIT-compiled — the exact defect this gate owns, invisible to it.

**Not "no measurement" but a measurement of the WRONG THING wearing a green**, which its own header
warns about one level up: *"Refusing to report green on an empty measurement — that is the failure
mode this whole gate exists to prevent."* The list was not empty. It was incomplete.

**Fixed by resolving each module independently** — its class directory, else its staged jar, else
the gate REFUSES and names the module. `bytecode-size-census` reads a jar as readily as a directory,
and the jar path is the one that runs after `install.sh`; proved non-vacuous by censusing that jar
at a lower threshold, which lists 13 methods including `methodDispatch8` at 4921.

**And the gate is now wired into smoke beside its v1 twin.** `scripts/smoke-ci.ssc` already said the
v2 twin "sits in ci.yml's `sbt` job, which is workflow_dispatch only" — noticed, written down and
left there. That is what let an 8406-byte method sit on the hot path of the lane `ssc run` uses.

## f-at-bind-pattern-emits-unbound-underscore — and the SECOND resolver answered `<closure>`

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: 56155766b
     gate: tests/e2e/f-at-bind-pattern-gate.sh -->

F had no rule for `case q @ Ctor(…)` in EITHER of its two match resolvers, and the two failed
differently — which is the whole reason this entry is worth reading.

```
ctor-first match     ssc: unbound global: (global _)
ordered match        <closure>                          -- runs, no diagnostic, wrong value
```

**Neither message names the construct.** On the ctor path the arm fell through to `parseConsArm`,
which reads `h :: t` positionally, so the pattern's `_`s ended up in EXPRESSION position — `_` became
a global the user never wrote, reported against whichever file the import chain surfaced it in. On
the ordered path it fell through to `parseGenVar`, which read `q` as a catch-all VAR arm and took
`Ctor(…) => body` as that arm's BODY, so the match evaluated to a lambda.

**THE SECOND RESOLVER IS THE LESSON, and the source file predicted it.** Forty lines below the code
I edited first, `specs/v2.2-p6.5-fsub.ssc` says: *"Which resolver a match uses is decided by its
FIRST pattern … fixing only one leaves the other silently wrong — which is exactly what the first
version of this change did, with a green gate, because every test in it matched on a String and never
reached the code being edited."* I then wrote an eleven-row gate, watched it go from six red to all
green, and had touched only one resolver — because every row matched on a sealed trait, and a
ctor-first match never enters `parseGenArm`. Three rows exist now for no other purpose than to reach
it, and each was red until the second half landed.

**What made the miss visible was not the gate.** It was running the two files this was supposed to
unblock and finding F alone against the reference AND the v1 interpreter: `dsl-sql-recovery.ssc`
printed `0` and died on `match: no arm for PParseAll/1` where both other lanes print the parse. Had
the first half been pushed on the strength of a green gate, it would have turned an honest decline —
F falls back and the program runs correctly — into a silent wrong answer, and breached
`f-output-agreement-gate`'s ceiling.

**The bind is scoped to the arm BODY in both paths.** `bindScrut` renames the synthetic `__m`
scrutinee slot, and both `parseArmBody` and `parseGenCtorPlain2` hand the same menv to the arms that
FOLLOW. Threading a renamed env down would leave later arms with no `__m` at all: a later
`case other =>` would silently stop binding, and `genScrut`'s `lookup("__m", menv)` would emit
`(local -1)`. Guarded and nested @-binds route to the ordinary paths without the bind — structurally
right, `q` unbound, F declines honestly rather than answering wrongly.

**The `@` is not a token.** `opCode` maps unknown punctuation to 0 and `lexOp1` drops it, so
`q @ P(a, b)` reaches the parser as `q P ( a , b )` — an ident directly followed by a ctor, a
sequence no legal arm can otherwise produce. Keying on that is deliberate rather than lazy: adding
`@` to the lexer would materialise a token in the 49 corpus files carrying `@main` / `@model` /
`@key` annotations, all of which currently rely on it vanishing.

**F twin of `v2/BUGS.md v21-native-sql-recovery-parser-sentinel`**, fixed on the other front in
`1bf9c7c06` on 2026-07-11 — the same three `case ok @ ParseOk(_, _, _)` lines of
`std/parsing/recovery.ssc`, five weeks and one front apart.

**Together with `88c5741f6` this completes the parsing family**: `std/parsing/{core,combinators,
layout,recovery}` and `examples/dsl-{calc-parser,json-parser,yaml-like,sql-recovery}` all compile
under F and all agree with the reference front.

## ci-smoke-red-streak-nobody-stopped — the gate worked; nobody read it

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     gate: tests/coord/coord-release-evidence-level.sh
     fixed-in: 7e62996066892438f40038bda60021cc263c8c80 -->

> **CLOSED 2026-08-14 by taking the proposal below — and its premise was wrong in a way that moved
> the fix.** The entry says "`coord-release --level 1` runs `scripts/ci-status --sha <landed-sha>`
> and refuses on non-zero, so 'read the CI you triggered' is enforced at exactly one level and
> nowhere else." It is enforced at NO level. `ci-status` occurs in `coord-release` exactly once, in
> the usage text; `--level` is validated as one of `1|2|3` and written into the commit message and
> never checked against anything. `--level 1` has always been a claim about CI that no code reads.
> So there was no mechanism to narrow: the check had to be written, and it runs at every level.
>
> **`coord-release` now REFUSES (exit 3) when `origin/main`'s latest `smoke.yml` run is RED.** Red,
> never "not green" — which is §P-6.7's own argument, not a preference: the ladder exists because
> level 1 became unreachable under churn, and "a rule nobody can satisfy does not gate, it relocates
> the lying". `ci-status` already separates the three answers by exit code — 0 green, 1 completed
> red or missing a required job, 2 pending / absent / unqueryable — and only **1** refuses. No
> `ci-status`, no network, no `gh`, a timeout: all proceed.
>
> **`--on-red "<why>"` releases anyway and writes the reason into the release commit** in a fixed
> position, `[released onto RED main: …]`. A red main is usually somebody else's breakage and the
> queue must not deadlock on it; an escape that left no trace would recreate this entry in one move.
> The design goal is a visible act, not a hard one — `bugs-index-gate` already records why failing on
> a judgement call "would train people to ignore it".
>
> **The three anti-cases are what make the four-case set worth anything.** Run against the previous
> copy of the script, the six red-related assertions fail (including
> `coord-release: unknown option: --on-red`) while GREEN, PENDING/unqueryable and no-ci-status pass
> unchanged. Had the guard been written as "require a green", those three would have failed against
> the old script too and a passing lab would have proved nothing. 31 PASS / 0 FAIL, 4 s against a
> 120 s budget, wired at `scripts/smoke-ci.ssc:562`.
>
> **What is NOT claimed:** main was green while this was built (`ci-status --latest` exit 0), so the
> refusal is demonstrated in the lab and has never fired on live main. And the habit finding stands
> on its own — this makes the act visible and recorded; it cannot make anyone read a red.

**Measured from the CI run list on 2026-08-14, not inferred, and now SETTLED — bounded by a green on
each side.** Twelve consecutive `smoke.yml` runs failed, and every one of them failed on
`freeze-consistency` **and nothing else** (each checked individually, not assumed from the first):

```text
835c3cc44 07:41  success                             ← the last green before it
4e93b86b9 07:47  failure   freeze-consistency        ← first red
1a850406b 07:48  failure   freeze-consistency
4129a265f 07:51  failure   freeze-consistency
bd7df2895 08:00  failure   freeze-consistency
9654de5fc 08:12  failure   freeze-consistency
88c5741f6 08:14  failure   freeze-consistency
68f86d89c 08:15  failure   freeze-consistency
b4aa57ec9 08:53  failure   freeze-consistency
330afc23c 08:55  failure   freeze-consistency
02dc57a8b 08:55  failure   freeze-consistency
eb314c99c 08:57  failure   freeze-consistency
45a67cfaa 08:58  failure   freeze-consistency        ← still red 71 minutes after the first
55d4e5554 09:00  success   ok freeze-consistency 1.6s ← the repair; 97/97 green, 873.6s of 980s
```

Each red run reported `95/96 green`. Different agents, twelve pushes, seventy-one minutes.

**THE COUNT WAS EIGHT WHEN FIRST WRITTEN, THEN NINE, AND IS TWELVE — and that history is the most
useful thing here.** Each time, the runs I had not yet seen were still `in_progress`, so they were
simply absent from the list being counted. **A census taken over a QUEUE is a lower bound until the
queue drains.** It only became a number worth quoting once there was a success on BOTH sides of the
streak. The entry's slug deliberately carries no count, because the first two versions of it did and
both went stale within the hour — and renaming a slug breaks every reference to it.

**The gate did its job.** `41ae217cd` removed the `known-red:` from a conformance case's front-matter
and left the matching `KNOWN-RED` row in `corpus-baseline.tsv`; `freeze-consistency-gate` exists to
refuse exactly that and did, immediately, with a message that names both halves and says *"removing a
known-red means removing BOTH halves"*. Nothing about the detection failed.

**What failed is that twelve agents pushed on top of a red CI without looking.** This is the same
shape as `lint-markdown-standing-red`, closed the same morning, but strictly worse: that one was
invisible to `scripts/ci-status` because it asks about `smoke.yml`. **This one WAS `smoke.yml`** — the
one workflow every agent's own tooling reads — and it still went unnoticed for twelve commits.

**Two mechanisms, and they are separable:**

1. **A release at evidence level 3 cannot see it.** `41ae217cd` was released with local gates named.
   `freeze-consistency` is not in the set anyone naturally runs after fixing a front, so a truthful
   level-3 release passed over the one gate that mattered. The full `scripts/smoke-ci` would have
   caught it, which is the argument for level 1 rather than for a longer level-3 checklist.
2. **Nobody ran `scripts/ci-status` after pushing.** It is one command, it is what POLICY P-6.7 calls
   the gold standard, and twelve consecutive agents did not run it — myself included until I hit the
   red locally and went looking for its history.

**Not filed as "add a check".** A check already existed and was already red. The actionable part is
the second mechanism, and it is a habit rather than a script: *after a push, read the CI you just
triggered.* Recorded here so the next person who finds a twelve-commit red has the census rather
than the impression.

**The instance is closed, and the prediction it was closed on was written down BEFORE the evidence
arrived.** This paragraph previously read "CI is *expected* to return to green from that commit
onward … a green `smoke.yml` immediately after, on a sha nobody else had to repair." That is exactly
what happened: `55d4e5554`, `scripts/ci-status` exit 0, `ok freeze-consistency 1.6s`, **97/97 green,
873.6 s of a 980 s budget on the runner**. Twelve reds, then the first green is the repair.

**The ENTRY stays open, because the instance was never its subject.** What is unfixed is the second
mechanism — twelve agents pushing onto a red `smoke.yml` without reading it — and no commit here
changes that. Closing on the strength of the freeze repair would be closing a habit finding because
its example went away.

**Gate named 2026-08-14 — and the entry is right that "add a check" is the wrong reading, so this is
not one.** The check existed and was red; what nothing does is STOP anyone. But the repo already
owns the mechanism: `coord-release --level 1` runs `scripts/ci-status --sha <landed-sha>` and
refuses on non-zero, so "read the CI you triggered" is enforced at exactly one level and nowhere
else. The checkable form of the habit is therefore a narrowing, not a new tool: **`coord-release`
refuses at ANY level when `origin/main`'s latest `smoke.yml` run is RED**, because releasing onto a
red main is the act the twelve pushes have in common.

`tests/coord/coord-release-evidence-level.sh` is the lab that already drives that script through its
level logic against a fake origin, so the case goes there.

**Done when** that gate has a red-main case and its anti-case (green main releases normally), and
the case FAILS against the current script. **This is a proposal, not a decision** — the alternative
close is to write in `POLICY.md` that pushing onto a red smoke is allowed and why. Either settles it;
what cannot stay is a finding whose whole content is "people should look".

## pre-push-refusal-message-executes-its-own-backticks — the coordination guard runs `scripts/coord-claim` while refusing you

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     gate: tests/e2e/no-live-backticks-in-heredocs.sh
     fixed-in: f9258a011 -->

**Met as a reader on 2026-08-14, not found by reading code.** A claim of mine was correctly refused
— a sibling had claimed the same two items two minutes earlier, which is exactly what the overlap
guard is for — and the refusal came out like this:

```text
✋ claim REFUSED — it overlaps a live claim. This is NOT a race; retrying will not help.

.githooks/pre-push: line 413: items:: command not found
.githooks/pre-push: line 413: --items: command not found
usage: scripts/coord-claim <slug> --items "<id> …" --paths "<prefix> …" [--agent <id>]
  … twenty more lines of coord-claim's usage …
.githooks/pre-push: line 413: slug: No such file or directory
```

**`.githooks/pre-push:413` opened its message with an unquoted `<<EOF`**, so the six backticked
identifiers in the prose were command substitutions. `items:` and `--items` were run and reported
missing; `` `scripts/coord-claim` `` **was executed**, and its usage went to stdout, i.e. it printed
*before* the message rather than into it; `` `git show origin/main:.work/active/<slug>.claim` `` ran
with `<slug>` as a redirection. The five sentences that explain *why* a claim was refused lost every
identifier they named.

**The cost is not cosmetic, and it is not the missing words.** This message is read at exactly one
moment: when an agent has just been refused and is asking "did I get the command wrong?" Being shown
a command-usage block is the single most misleading answer available, and it worked on me — I
re-checked my own invocation and ran `--dry-run` before looking at the hook.

**Radius, measured as a differential** of the message block with a synthetic `$problems`, old vs
new: 16 lines of `coord-claim` usage gone, 4 lines of `command not found` gone, 5 lines restored to
carrying their identifiers, everything else byte-identical.

**Census before the fix, because "the same thing everywhere" is an assumption.** 19 backticked lines
sit inside unquoted heredocs across `.githooks/`, `scripts/` and `tests/`; **15 are already escaped**
`\``, in five files. `.githooks/pre-push` itself escapes correctly at lines 133 and 140. So this was
one prose block, 421-425 — a single regression, not rot to sweep.

**Fixed** by moving the only expansion the block ever needed (`$problems`) out into a `printf` and
quoting the delimiter. Escaping the six backticks was the other option and was rejected: seven prior
occurrences of this class in this project were all slips inside prose that was otherwise clean,
which is what a rule applied by hand at 95% looks like.

**Gate: `tests/e2e/no-live-backticks-in-heredocs.sh`**, wired into smoke, 1.8 s. An unescaped
backtick inside an unquoted heredoc body, over 348 tracked shell files. Four controls, including the
pre-fix spelling verbatim (P-6.1b) — and the fix cannot be verified by the fixture alone, so the
real file is reverted and the gate is observed RED on it, naming lines 421, 422, 423 and 425, before
the fix is trusted.

**Its population was wrong first, which is the failure this project keeps having.** The scan set
began as a path allow-list — `*.sh` plus `scripts/` plus `tests/` plus `.githooks/` — and covered
**314 of the repository's 348 tracked shell files**. The 34 it could not see were the extensionless
tools outside those directories: `bin/ssc`, `v2/ssc`, `v2/ssc1`, `v3/ssc3`, the
`v1/tools/scripts/launchers/*`. **The launcher every agent runs was outside the check.** The
population is now a property — tracked, and shell by extension or by shebang — rather than a list of
places. Widening it found no new defect, which is the useful part of the answer: the fix really is
one site.

**The gate was wrong on its first run, in the direction its sibling warned about.** It read
`printf '<<encode-error: %s>>'` and `echo "… (\`<<EOF\`) …"` as heredoc openers — a `<<WORD` inside a
STRING — found no closing delimiter, ran to end-of-file and reported 18 backticked *comments* as
findings, 16 of them in `specs/coreir-codec-vectors.sh` and 2 in itself. Same shape as
`no-gnu-only-shell-constructs` matching the comments that explain the construct it removed, and
arrived at the same way: by the check failing on arrival and its output making no sense. The scanner
now masks quoted spans and comments before looking for an opener, and both spellings are kept as
controls.

**This is the eighth occurrence of the class in this project and the first in checked-in code.** The
other seven were an agent's own typing — `git commit -m`, `coord-release --note` — and were answered
by `--note-file` (`7bcfab999`) and by a written rule. Neither of those reaches a heredoc that has
been sitting in a hook for weeks, which is the argument for a mechanical check over one that is
plainly a two-character fix.


## policy-selftest-stages-into-the-shared-index — an interrupted gate blocks the NEXT agent's rebase

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     fixed-in: 36c9d1445
     gate: tests/e2e/policy-selftest-residue-gate.sh -->

**Reported 2026-08-14 in the coordination room by the agent it blocked**, who could not rebase and
found ` D specs/_policy-single-source-selftest.md` in a checkout where nobody had touched that path.

`tests/e2e/policy-single-source.sh --self-test` needs its fixture to be VISIBLE to `git ls-files` —
that is how the gate builds its document list, so a file merely on disk is invisible to the check it
exists to trip. It got there with `git add -N` into the checkout's own index and was unstaged three
lines later. **Between those two lines the state lives in the one place every agent in this checkout
shares.** A run that never reaches the third line leaves an intent-to-add entry for a file that is no
longer on disk, and the next `git rebase` refuses to start:

```
error: cannot rebase: You have unstaged changes.
error: additionally, your index contains uncommitted changes.
```

Reproduced in four lines, before any of this was written:

```sh
git add -N specs/_fixture.md && rm -f specs/_fixture.md
git status --porcelain     #  D specs/_fixture.md
git rebase HEAD            # error: cannot rebase: You have unstaged changes.
```

The reporter's run died on an unrelated check under host load, which is the ordinary way this
happens — smoke kills a check on its own timeout and the killed process runs no cleanup.

**"Clean up in a trap" is the reflex and it is not sufficient.** The interruption that actually
occurs here is a killed process group; a SIGKILL runs no trap, so a trap-only fix would still leave
the entry whenever the suite is the thing that does the killing. Three mechanisms, weakest last:

1. **The shared index is never written.** `GIT_INDEX_FILE` points the self-test, and the child run
   it drives, at a COPY of the index. `git ls-files` reads that copy, so the fixture is visible to
   the check and invisible to everything else. No signal can undo this.
2. **A trap** (`EXIT INT TERM HUP`) removes the file on disk when the process is allowed to run code.
3. **`.gitignore` carries the fixture path**, so even a SIGKILL leftover is inert: it does not make
   `git status` dirty and cannot be swept into a sibling's `git add -A`.

**The gate interrupts a real run rather than asserting on the source.**
`tests/e2e/policy-selftest-residue-gate.sh` builds throwaway repositories, puts a `git` shim first
on `PATH` that parks the run at the first `ls-files` after the fixture appears — the child run
inside the self-test, i.e. the exact window — and kills the process group with SIGTERM and then with
SIGKILL. What it asserts is the reported symptom itself, `git rebase` must still start, plus a clean
`status` and an index with no entry. Its own self-test reruns both interruptions against **the same
script with the fix stripped by an asserted-non-empty transform**, and both are caught, so a green
cannot mean "the checker does not look".

**Census, because the interesting question is whether this is a class.** `grep -rln 'git add' tests/
scripts/` finds nine gates; the eight in `tests/coord/` all build `mktemp -d` labs, and
`tests/e2e/launcher-digest-gate.sh` — the one other gate that edits tracked files — already works in
a temporary git worktree under `trap cleanup EXIT`, and says in its header why. This was the only
gate writing into the shared index.

**What is NOT fixed** is the general property: nothing stops the next gate from doing the same
thing. A checker for "a gate must not modify the checkout it runs in" would need to snapshot
`git status` plus the index around every gate in the suite, which is a suite-runner change, not a
gate. Filed here rather than done silently.

## coord-labs-inherit-the-dev-boxs-git-config-so-a-runner-gap-cannot-be-seen-here

<!-- status: open
     lane: apparatus
     area: build
     kind: apparatus
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     gate: tests/coord/labs-are-hermetic.sh -->

**Found 2026-08-14 by turning `main` red.** I wired six `tests/coord/*.sh` gates into smoke after
measuring all six green here; **three went red on the runner within the hour**. Not a defect in the
gates: a CI runner has no global git identity and cannot derive one from `user@host`, so the REAL
script under test exits 128 with `Author identity unknown`. The labs set `-c user.email` only for
the commits they make THEMSELVES; the tool's own `git commit` ran with ambient config, which on a
dev box silently works.

**The property that is missing is hermeticity: a lab inherits whatever git config the person
running it happens to have.** Anything the dev box provides and the runner does not is invisible
until it lands. Identity is simply the instance that happened to bite.

**Reproducing a runner is harder than it looks, and getting this wrong sends you after a phantom.**
`HOME=$(mktemp -d) GIT_CONFIG_GLOBAL=/dev/null` is NOT enough — git still derives `user@host` and
all three labs pass. The environment that reproduces it exactly:

```bash
HOME=$(mktemp -d) GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true \
bash tests/coord/<lab>.sh
```

Same three fail (2, 6 and 12 identity errors), same three pass — an exact match with the runner.

**Acceptance test for the mechanism fix**, which is why this is filed rather than patched: make each
lab set that environment FOR ITSELF at the top, so it is hermetic no matter who runs it and a lab
that forgets to configure identity fails on a dev box too.

1. Every `tests/coord/*.sh` exports the block above before creating its first repo.
2. **Deleting the repo-local `git config user.email` from any one lab must make it RED HERE.** That
   is the whole point; without that assertion the change is decoration.
3. All 11 still pass unchanged on this host and on a runner.
4. Not done under time pressure with `main` red, and not narrowed to the three I had already
   claimed: eleven gate files other agents may be editing is a deliberate change, not a drive-by.

**Immediate damage is repaired** — the three labs now set a repo-local identity (`coord-claim-broad`,
wired earlier, always did, which is why it never failed). This entry is about the class.

**Gate named 2026-08-14: `tests/coord/labs-are-hermetic.sh`, which does not exist yet.** The four
numbered requirements above ARE its cases, and requirement 2 is the anti-constant one — deleting a
lab's repo-local identity must turn it red HERE, or the change is decoration. One gate over all
eleven labs beats a line in each: the property is "no lab reads ambient git config", and one place
to assert it is one place to keep true.

**Done when** that gate passes under the reproduction environment above
(`HOME=$(mktemp -d) GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null`), and requirement 2
fails against a lab with its identity removed. Note the entry's own warning: the weaker environment
without `GIT_CONFIG_SYSTEM` does NOT reproduce, so a gate built on it would be green and blind.

## f-extension-call-in-a-def-body-refuses-with-a-wrong-arity

<!-- status: duplicate
     duplicate-of: v2-extension-member-call-inside-a-def-body-fails-by-arity
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     gate: tests/e2e/single-line-extension-gate.sh -->

**DUPLICATE, and I should have found that before filing.** The defect was already root-caused in
`v2/BUGS.md v2-extension-member-call-inside-a-def-body-fails-by-arity`, which even carried the gate
that pinned F as known-red. What I did here was file the symptom again from the corpus gate's
output without grepping the other board — the conformance file's own frontmatter named the entry, in
the `known-red:` line, and I read the file without reading that line. Fixed 2026-08-14 in
`41ae217cd`; the four gate rows are `both()` now.

**What is NOT duplicated is why the gate went red on 2026-08-13**, so that stays below: the F defect
was three days old and unchanged, and what moved was the OTHER lane. Keep this entry for the
unmasking, not for the bug.

**`f-output-agreement-gate` is RED on origin/main, ceiling 0.** On
`tests/conformance/extension-call-in-a-def-body.ssc`:

```
F               ssc: arity: 1 expected, 0 given
эталон          body-a
интерпретатор   body-a
```

**Unmasked, not introduced.** The defect is old — the corpus survey recorded it on 2026-08-11 as
*"F: `arity: 1 expected, 0 given`; the reference runs"*. What changed is the OTHER lane: until
`374f6ce01` (2026-08-13) the reference front printed nothing for this file, because it emitted only
the whole program's final value instead of each block's tail. With all three lanes differing the
file sat in the `all three differ` bucket, which the ceiling ignores. Now the reference is right,
the interpreter agrees with it, and F is alone — so the same defect is charged to F for the first
time.

That is the gate working as designed: a divergence needs a third opinion, and the third opinion has
only just become available for this file. It is also a clean instance of two defects masking each
other — fixing the reference one is what made the F one visible.

**Not caused by the arm-body assignment fix** (`0ea4a4a83`, same day). Reproduced with that change
reverted and the tree rebuilt: identical output on all three lanes.

**Not narrowed.** F refuses rather than answering wrongly, so this is a coverage defect, not a
silent one; `arity: 1 expected, 0 given` on a call with an extension method in a def body points at
the extension-dispatch path rather than at the arm machinery. The file is small and the reproducer
is the file itself.

## f-package-namespace-breaks-on-an-object-with-extends

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     fixed-in: 88c5741f6
     gate: tests/e2e/f-bodyless-object-gate.sh -->

### FIXED 2026-08-14 — and this entry's title, law and next step were all wrong

**The package namespace has nothing to do with it.** `objBodyToks`
(`specs/v2.2-p6.5-fsub.ssc`) advanced from an object header to its body `{` with NO stop condition,
so a BODYLESS `object A` ran on to whatever brace came next anywhere in the file — and when the file
had none, it consumed the file. The oracle has always had the missing arm: `ssc1-front.ssc0
skipToBrace :2714` stops at `{`, EOF **or `;`**. One arm, six callers, three symptoms.

Every clause of the law below is refuted by a reproducer with NO frontmatter at all:

| the entry said | measured 2026-08-14 |
| --- | --- |
| `package:` is required | not required — `object A` + `def h` + `def main` fails with no frontmatter |
| an `extends` clause is required | not required — `bodyless-plain` has none and fails |
| `case object` vs `object` makes no difference | it makes a difference in `objectItem` and NONE in the four raw-token collectors, which scan for the WORD `object` — one token along in `case object A` |
| the fault is downstream of generation, in `parse(nsSrc)` or the splice | the generator and the splice are both innocent; the module's OWN tokens were already mis-scanned |

**Why `package:` looked necessary.** Deleting it made `std/parsing/core.ssc` compile, and that was
a true measurement of a false cause. The misattribution happens either way; the generated namespace
is what adds `def __pkgref_std_parsing__Parser = Parser`, i.e. the REFERENCE that turns a silently
mis-scanned object into a fatal `unbound global: (global Parser)`. Remove the reference and the
defect stops being visible in that file — which is not the same as stopping.

**What it unblocked**, front verdicts before and after on real builds:

```
std/parsing/core.ssc          GAP (global Parser) -> F
std/parsing/combinators.ssc   GAP (global Parser) -> F
std/parsing/layout.ssc        GAP (global Parser) -> F
examples/dsl-calc-parser.ssc  GAP (global Parser) -> F
examples/dsl-json-parser.ssc  GAP (global Parser) -> F
examples/dsl-yaml-like.ssc    GAP (global Parser) -> F
std/parsing/recovery.ssc      GAP (global Parser) -> GAP (global _)    [advanced, other cause]
examples/dsl-sql-recovery.ssc GAP (global Parser) -> GAP (global _)    [advanced, other cause]
```

All five dsl examples F now compiles answer IDENTICALLY to the reference front, so unblocking this
path revealed no second defect behind it. The two remaining GAPs moved to `(global _)` — a
placeholder cause, and the same one `f-placeholder-u0-reduced-but-not-solved` is about.

**The 91-line reduction was at a fixpoint and still could not see this**, which is worth recording:
it pinned `sealed trait Parser`, `object Parser` and `trait ParserContext`, and reduced by
declaration until every survivor was load-bearing — but `case object NoContext extends
ParserContext` is one of the survivors and reads as an ordinary declaration. Nothing about the
reduction says "the object above the failing one is the culprit". What found it was asking a
different question: not "which declarations are needed" but "what does F do with the SIMPLEST
possible object", and the answer was that a five-line file without frontmatter already failed.

**An `object … extends …` in a module with `package:` breaks the generated package namespace: the
next generated name comes out unbound.** This is the root of the largest remaining F coverage hole —
`std/parsing/core.ssc` and the four `dsl-*` examples that import it.

Minimal reproducer, 4 lines of code plus the frontmatter:

```
---
package: std.parsing
---

```scalascript
trait T
object A extends T
object B:
  def g(): Int = 2
```
```
→ `GAP  unbound global: (global B)`. Delete `extends T` and it compiles. Delete `object B` and put a
plain `def helper(): Int = 1` there instead, and the failure moves to
`unbound global: (global __pkgref_std_parsing__helper)`.

**The law, from a variant sweep:**
* `package:` is required — no package, no generated namespace, no failure;
* an object with an `extends` clause is required. `case object` vs plain `object` makes no
  difference, and the trait may be declared before OR after the object;
* objects WITHOUT `extends` are fine at any count — one, two and three all compile;
* what breaks is not the offending object but the NEXT generated name, which is why the real file
  reports `(global Parser)`: `case object NoContext extends ParserContext` sits in front of
  `object Parser`.

**The generated source is CORRECT — this was dumped, not assumed.** A driver built from the F runner
(replace its `main`, call `sscPkgNsSource(sscPkgName(fileStr), sscDefsOnly(parse(modSrc)))`) prints:

```
def __pkgref_std_parsing__helper = helper
object std:
  def __pkg = 0
object std_parsing:
  def __pkg = 0
  def helper = __pkgref_std_parsing__helper
object std_parsing_A:
  def __pkg = 0
```

The alias F calls unbound is defined on the FIRST line of that text. So the fault is downstream of
generation — in `nsDefs = sscDefsOnly(parse(nsSrc))` or in the splice that follows, not in
`sscPkgObjBlocks`/`sscPkgMemberNames`, which produce exactly the right lines.

**Next step:** dump `parse(nsSrc)` and `sscDefsOnly` of it for the failing case and see which
statements survive. The generator is exonerated; the parse or the splice is not.

**Ruled out earlier by measurement** (recorded so nobody re-walks them): the number of case classes
extending a trait, the number of objects, the number of code fences, the `exports:` list, the
trait/companion same-name pair, a generic case class, a generic method inside an object, and a
type-parameterised extension. Supersedes the `package:`-is-necessary finding in
[[f-parser-gap-needs-the-package-field]], which this narrows from "necessary but not sufficient" to
a four-line reproducer.

**A trap that cost me several rounds, twice:** `bin/ssc` in a fresh worktree is not built until
`install.sh --dev` runs, and `ssc info --front-report` then prints
`Could not find or load main class scalascript.cli.StandardMain` on stderr and NO tsv line — so a
verdict extracted with `cut -f2` comes back EMPTY, which reads as "no verdict" rather than "no
binary". I collected a whole table of empty verdicts before noticing. Build first, and treat an
empty verdict as a broken run, never as data.

## v2-lane-does-not-serve-the-content-introspection-view — `--v1` answers 200, `--v2` answers nothing

<!-- status: open
     lane: native
     area: runtime
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     gate: tests/e2e/serve-view-frontend-v2-smoke.sh -->

**Measured 2026-08-13**, and it was hidden behind a mechanical defect until today. The gate drives
`examples/content-introspection.ssc` through both lanes and compares:

    --v1: http=200  frontend=react  swiftui-crash=0
    --v2: http=000                  swiftui-crash=0

`http=000` is curl's "nothing answered" — the v2 lane never starts listening. The v1 side is
healthy, so the fixture, the port and the harness are all fine.

**Why nobody saw it.** The gate set `BIN` to the STANDARD `bin/ssc` and passed `--v1`, which that
tier refuses by design ("`--v1` requires the optional ScalaScript tools/compatibility tier; run
ssc-tools explicitly"). BOTH lanes therefore came back empty and the gate reported *"--v1 baseline
broke"* — a false accusation against the v1 lane that masked a real defect in v2. The gate was also
invoked by nothing, so it had never once been read. Fixing the launcher is what surfaced this.

**Acceptance test:** `tests/e2e/serve-view-frontend-v2-smoke.sh` — it already asserts the comparison
and is green on the v1 side. It goes green when `--v2` answers 200 with the same `frontend=react`.

> **MECHANISM CORRECTED 2026-08-14 — it never reaches `serve`, and there are THREE blockers, not
> one.** "Where to start" below is wrong and is kept so the correction has something to point at.
> Running the lane instead of reading the gate:
>
> ```
> $ bin/ssc-tools --v2 examples/content-introspection.ssc
> ssc: unbound global: contentToolkitBlock          rc 1
> ```
>
> The program does not start, so `http=000` is not "serve failed to listen" — nothing got that far.
> The gate only grepped its own log for `frontend=` and a swiftui string, so the one line naming the
> cause sat in `/tmp/serve-view---v2.log` unread on every run. The gate now prints that log's tail on
> failure (`864eaefd8`).
>
> **A refusal short-circuits, so the first blocker is never the count.** Each of these was probed on
> its own, not inferred from the one after it:
>
> | # | blocker | state |
> |---|---|---|
> | 1 | `contentToolkitBlock` was never registered on the v2 content plugin, though `toolkitBlockNode` was already there | **FIXED** `864eaefd8` |
> | 2 | an `extern`'s declared default argument is not filled on v2, so the one-arg `contentToolkitBlock("team-controls")` the example uses dies with `arity: 2 expected, 1 given` | open — `v2/BUGS.md` → `v2-extern-default-argument-is-never-filled-so-a-plugin-native-needs-full-arity` |
> | 3 | `contentCurrentSection()`, which the example calls twice, is a deliberate throw on native ("unavailable on native 2.1 without source-aware call identity") | open — `v2/BUGS.md` → `content-current-section-native-unavailable`, and as of 2026-08-15 it is the ONLY blocker left: 1 and 2 are both fixed and the example now runs to exactly that throw |
>
> Blocker 2 is NOT this fix's doing: the untouched twin `contentToolkitSection("team")` fails
> identically on the shared toolchain, and an ordinary `def` with a default is filled correctly on
> the same lane. Blocker 3 needs source-aware call identity, i.e. a design decision.
>
> **So this entry stays OPEN and its gate stays red**, with the reason changed from "unknown" to a
> chain of three, two of which have their own entries. Fixing blocker 1 alone would have let someone
> read the still-red gate as "the fix did not work".

**Where to start:** the v2 lane's `serve` path for a view-bearing program. The two sibling gates
fixed in the same batch (`route-params-v2-smoke`, `req-type-collision-v2-smoke`) DO serve on `--v2`,
so v2's `serve` works in general — the difference is this fixture's view/frontend content.

## wired-gates-share-hard-coded-tcp-ports — CAUSE RETRACTED; the measured defect is a LEAKED SERVER — a gate can pass against a NEIGHBOUR's server

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     fixed-in: eb314c99c
     gate: tests/e2e/no-leaked-servers.sh --self-test -->

### CLOSED 2026-08-14 — both items of the revised acceptance test now exist, and the ratchet found its own author out

The revised acceptance test in the correction below has two items. Item 1, *a gate must not leave a
listener behind*, landed on 2026-08-13. **Item 2, the port-uniqueness check, landed today** — as a
second half of `no-leaked-servers.sh` rather than a new file, because that gate is already wired into
`ci.yml` with `if: always()`, so the ratchet needs no new wiring and creates no orphan.

**`ports_of` was already sitting in that file, defined and called by nothing** — the last remnant of
the retracted first design, which tried to find *leaks* by reading ports out of gate sources. Reading
sources was the wrong instrument for leaks and the right one for collisions: a collision is a
property of the sources, true whether or not either gate is running. So the dead function is finally
called, for the one question it can answer.

**THE COUNT IN THIS ENTRY IS CORRECT AND I CONFIRMED IT BY GETTING IT WRONG THREE TIMES.** Widening
the pattern to any bare `[89][0-9]{3}` reports six collisions. Three of the six are fiction:

| number | looks like | actually is |
|---|---|---|
| `8000` ×2 | `v1-jit-size.sh`, `v2-jit-size.sh` | HotSpot's `HugeMethodLimit` — not a port at all |
| `8080` ×6 | six `v21-*` gates | a line inside a YAML fixture's **expected output** |
| `9999` ×2 | `bundle-smoke.sh`, `nested-build-smoke.sh` | `serve(9999)` in a heredoc of source those gates `bundle`/`render`/`build` and **never run** |

And narrowing it too far loses a real one: `request-validation-family-gate.sh` writes
`PORT="${SSC_REQUEST_VALIDATION_PORT:-8797}"`, which a plain `PORT=NNNN` pattern misses. The
surviving predicate — the number must appear in a syntax that BINDS — reproduces this entry's
original three exactly. **Widen it only with a counterexample in hand.**

**THE CHECK'S FIRST WORKING RUN REPORTED ITSELF.** The self-test plants needed the strings
`PORT=8769` and `PORT=8797`, those are real code lines rather than comments, and `ports_of` scans
`tests/e2e/*.sh` — which contains the scanner. So the ratchet named `no-leaked-servers.sh` as
colliding with the two gates on each port. **That is a mention counted as a use, committed by the
checker written to enforce the distinction** — the fourth instance of the shape this entry already
records three times. Fixed twice over: the file skips itself, *and* both plants are now assembled
(`printf 'PORT=%s' "$dup"`) so no binding-syntax port literal exists in it at all. The exclusion is
deliberately no longer load-bearing, because a check that is correct only while one filename stays
spelled the same way is one rename from lying.

**AND THEN IT BROKE A NEIGHBOUR THE SAME WAY, which is the instance worth keeping.** The frozen list
first named its gates as `render-smoke.sh`, `std-ui-forms-smoke.sh` and so on. `no-orphan-gates.sh`
decides whether a gate is wired by searching `.github`, `scripts` and `tests` for its basename and
keeping the matches that survive having the comment tail stripped — so a filename in a **string
literal** is indistinguishable from a call. That gate went red with *"frozen orphan is now invoked —
DELETE it from FROZEN: render-smoke.sh"*, and it was right to: by its rule, `render-smoke.sh` had
just acquired a caller.

Its header already warns twice about this shape — *it matches itself*, and *a comment is not a caller
either*. **A DATA STRING IS THE THIRD VARIANT AND THE ONE IT CANNOT DEFEND AGAINST**, because a
comment can be stripped and a data string has nothing about it to strip. Fixed by storing stems
without the `.sh`, which breaks the match and costs nothing in readability; a `.md` file is the other
safe home, since `callers_of` excludes those. Verified by running that gate's own `callers_of`
predicate against the change: `render-smoke.sh` matches only comment lines, whose stripped code is
empty, so it stays the orphan it is.

**THE THREE FROZEN COLLISIONS ARE NOT AN ACTIVE HAZARD, which is why this is a ratchet and not a
fix.** Two gates can only talk to each other's server on one machine. Measured 2026-08-14:

| port | gates | wired? |
|---|---|---|
| 8768 | `components-smoke.sh`, `render-smoke.sh`, `v21-native-entry-smoke.sh` | only the first; the other two are orphans |
| 8769 | `health-defaults-smoke.sh`, `std-ui-forms-smoke.sh` | both — but `ci.yml` vs `smoke-ci.ssc`, different runners |
| 8797 | `route-params-v2-smoke.sh`, `request-validation-family-gate.sh` | both — again different suites |

**No two colliding gates share a suite.** The residual risk is a dev box running both suites at once,
and the day someone wires a second gate onto one of these ports into the suite that already has one —
which is the day this check goes red first. They are frozen rather than renamed because a port is
pinned by number in workflows and renaming one is a different blast radius than this task owns.

**Proven to discriminate rather than asserted.** Two deliberate breaks on a copy: neutering
`check_collisions` to always return 0 fails with *"a planted third gate on port 8769 was NOT reported
— the ratchet is blind"*, and removing only the went-away direction fails with *"a frozen collision
was fixed and the list still passed — one-way ratchet"*. Each break trips the rule meant for it and
not the other one.

**Why this closes, and exactly how far.** The acceptance test that governs is the REVISED one in the
correction below, which superseded the original when the cause was retracted; both of its items now
exist and both are wired. What closes is this entry's claim, not the whole subject: the standing
question further down — *does any gate in this suite leak a listener* — is still answered only by
"nothing measured says yes", and the wired check will name the first real instance itself, with its
age and cwd.

The original acceptance test's item 2 — *a gate that starts a server must prove the server answering
is the one it started* — was dropped by that revision and is not a debt of this entry. It is a real
improvement and a separable one, so it is filed on its own rather than left implied here:
`a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own`.

**Wired gates hard-code TCP ports and several share one.** Counted 2026-08-13 across `tests/e2e`:

| port | wired gates using it |
|---|---|
| 8768 | **3** |
| 8766, 8767, 8769 | 2 each |

**A gate can therefore pass by talking to a server another gate left listening**, and it was found
that way rather than by reading the code. While making `no-orphan-gates --evidence` reproducible,
two gates kept moving between verdicts inside the sweep — `response-transforms-gate.sh` and
`route-handler-shapes-gate.sh` — and the obvious diagnosis, a flaky gate, is WRONG:

    run alone, launcher deliberately broken, 5 rounds each
      route-handler-shapes-gate.sh   failed 5/5   61s 61s 61s 61s 61s
      response-transforms-gate.sh    failed 5/5   60s 60s 60s 60s 60s

Steady to the second, and correct every time. They only ever pass INSIDE the sweep — where a
neighbour's server is still listening on the port they are about to use. So the gate whose own
launcher could not start anything still gets meaningful HTTP answers back and reports success.

**This is not confined to the audit.** The same collision exists in the ordinary `smoke` run: any
of these gates can pass against a neighbour's server, and nothing would show it, because everything
is green. This project already recorded the shape once — *a probe measures the PORT, not the lane* —
and this is a new instance of it.

**Acceptance test, so this is a task and not a note.** Two parts, and the first is the cheap one:

1. No two wired gates may hard-code the same port. A check over `tests/e2e/*.sh` that collects
   `PORT=`/`localhost:NNNN` literals and fails on a duplicate — seconds to run, and it is the
   ratchet that stops the next one being added.
2. A gate that starts a server must prove the server answering is the one it started (a nonce on a
   health route, or a port it allocated rather than chose). Per-gate work; start with the ones
   sharing 8768.

`no-orphan-gates --evidence` works around the symptom by re-verifying every blind candidate ALONE
before classifying it — which is why its verdict is now reproducible (105 invoked / 14 blind, twice
consecutively). That workaround is not a fix: it makes the audit honest, not the suite.

---

### CORRECTION 2026-08-13, same day — the cause above is REFUTED by a tighter measurement

**The damage stands; the explanation does not.** This entry blamed the audit's non-reproducible
verdict on wired gates sharing hard-coded ports. Re-measured with comments stripped and **restricted
to the population that actually runs** — the audit sweeps WIRED gates only:

    collisions among ALL gates (incl. orphans)   3   8768 x3, 8769 x2, 8797 x2
    collisions among WIRED gates                 0

**Zero.** The three collisions each involve at least one gate from the frozen orphan list, and an
orphan is by definition not in the sweep. So a neighbour on the same port cannot be what happened.
The first count also named 8766 and 8767, which the comment-stripped pass does not reproduce — it
was matching port numbers inside comments, which is the third time in two days that a search for a
STRING has been mistaken for a search for a FACT.

**What IS established, and it is a real defect:** gates leak servers. Measured after the sweeps, two
orphaned `ssc_progr` processes were still LISTENING on 8493 and 8497 with no gate running. A gate
that exits without stopping its server leaves a listener that a later run of the SAME gate can talk
to — which would explain a verdict that passes in a sweep and fails in isolation, since an isolated
first run has nothing left over to answer it.

**NOT established:** that this is what caused the 15-vs-14 variance. The two suspects use 8793 and
8795 and neither was listening when checked. Stated plainly rather than assumed, because assuming it
is how the shared-port explanation got written in the first place.

**The acceptance test changes accordingly** — from the unprovable to the measurable:

1. **A gate must not leave a listener behind.** Run each server-using gate, then assert nothing is
   listening on its port. That is a check that can be written today and cannot be argued with.
2. The port-uniqueness check from the original entry is still worth having, but as HYGIENE for the
   orphans, not as the fix for anything: 8768 is written into three gates and will collide the day
   two of them are wired.

`no-orphan-gates --evidence` is unaffected — it re-verifies every blind candidate ALONE, which makes
it reproducible whatever the mechanism, and that workaround was justified by measurement (5/5 stable
in isolation) rather than by this diagnosis.


### 2026-08-13 — the leak is REAL, it is the RUST lane, and the first detector could not see it

`tests/e2e/no-leaked-servers.sh` landed. Two orphaned listeners were found and named:

    pid 44704   ssc_progr   *:8497   ./target/debug/ssc_program
    pid 48753   ssc_progr   *:8493   ./target/debug/ssc_program

Still listening HOURS after their test. They come from the Rust lane's built binaries, **not from
any gate** — which is why the check's first version reported PASS while looking straight at them: it
enumerated ports out of `tests/e2e/*.sh` sources, and no gate source mentions 8493 or 8497 because
the binary picks them itself.

**That is the third time in two days the POPULATION was the defect rather than the check** — a
mention counted as a caller on the orphan axis, a mention counted as an execution on the evidence
axis, and now ports read out of source instead of processes read out of the machine. The rewritten
check asks the machine: is anything of ours listening. No guessing about who could be.

**Wired LAST in `conformance-extras`, with `if: always()`** — last because the question is only
meaningful once everything that starts servers has finished, and `always()` so a failing suite
still gets its leak reported rather than hiding it behind an earlier red.

**CI only, and the gate says why in its own header.** On a dev machine a sibling agent's live test
is a listening server indistinguishable from a leak; on an isolated runner the reading is exact.
The failure text prints pids so a human can tell in one look.

**Still open:** the two pids above were left alone rather than killed — they are not mine to
reap and killing by name is how a live sibling's test dies. The remaining work is the Rust lane's
cleanup path, which must stop its server on every exit, not only the happy one.

### 2026-08-13, later — the two pids are DATED AND PLACED, and they are not ours

The same two processes were still listening, so they were asked where they came from instead of
being described again:

```text
pid 44704  ssc_program  *:8497  started Sat Aug  8 19:25  age 5d03h  ppid 1
           cwd /private/tmp/claude-501/-Users-sergiy-work-my-rozum/…/scratchpad/probe3-rust
pid 48753  ssc_program  *:8493  started Sat Aug  8 19:32  age 5d03h  ppid 1
           cwd …/-Users-sergiy-work-my-rozum/…/scratchpad/probe6-rust
```

**FIVE DAYS old, reparented to init, and started from ANOTHER PROJECT's scratchpad** — ad-hoc
`probe3-rust` / `probe6-rust` binaries whose launcher was killed. Not this suite's gates, and not
from this repository at all. The section above says "hours" because nothing had asked for the start
time; `ps -o lstart,etime` and the process's own `cwd` answered both questions in one call.

**Which retires the stated remaining work, and the reasoning matters more than the verdict.**
"The Rust lane's cleanup path must stop its server on every exit" was inferred from these two
processes. `RunRustCmd` already spawns the binary with a shutdown hook that kills the process tree
and cleans up in its `catch`; what no hook survives is SIGKILL, which is exactly what killing an
agent's probe does. So these two are evidence about how a probe was killed, not about a missing
cleanup path — and there is currently NO measured leak attributable to this repository's gates.

**The check now dates and places every hit, and scopes its verdict**: on CI every listening process
on the runner is that job's, so any hit fails; locally a hit fails only when its `cwd` is inside
this checkout, and anything else is printed as a NOTE with age and provenance. A check that is
permanently red because of a five-day-old process in another repository stops being read, and
"ours by binary" turned out not to mean "ours to fix". Verified in both directions: `CI=true` exits
1 on those same two, a plain local run exits 0 while naming them, and a server started from inside
this checkout fails the local run as before.

**What remains is a QUESTION, not a task**, and it is stated so nobody re-derives the same answer:
does any gate in this suite leak a listener? Nothing measured says yes. The check is wired in CI
with `if: always()`, so the first real instance will name itself, with its age and its cwd.



### 2026-08-14 — the collision is REAL and OBSERVED, in an ordinary smoke run

The entry closed on the port-uniqueness ratchet (`eb314c99c`). A full `scripts/smoke-ci` the same
evening produced the thing the original entry predicted and the correction had downgraded to
hygiene:

```text
  FAIL std-ui-forms                      50.4s
      [PASS] INT
      [FAIL] JVM: :8769 is held by a process this gate did not start
      [PASS] JS
      [FAIL] JVM: :8769 is answering, but NOT from the process this gate started (pid 43041).
```

**This is not contention and not a leak.** `std-ui-forms-smoke.sh` passes 3/3 standalone at the SAME
load (43) that it failed under in the suite, and nothing holds 8769 afterwards — so the holder was a
sibling gate inside the same run. The gate did not guess: its own identity check ("answering, but NOT
from the process this gate started") named the mechanism, which is why three passing runs are
corroboration here rather than the inference-from-absence that would normally be worthless.

**SEVEN files hard-code 8769**, five of them gates that actually start a server:

```text
health-defaults-smoke.sh   request-validation-family-gate.sh   serve-view-frontend-v2-smoke.sh
std-ui-forms-smoke.sh      route-params-v2-smoke.sh
(no-orphan-gates.sh and no-leaked-servers.sh mention the number as data, not as a port they bind)
```

The 2026-08-13 correction measured "collisions among WIRED gates: 0" and concluded the shared-port
explanation could not be what happened. That measurement was true when taken and is now stale: the
wired set has grown since — six coordination gates on 08-14 and several more the same day — and
growth in the wired set is exactly what converts an orphan-only collision into a live one. **A
"0 collisions" measurement over a population that is actively growing is dated evidence, not a
property.**

**So item 2 of the revised acceptance test — port uniqueness — was the load-bearing one after all,**
and the ratchet that closed it must be checked against the population it now governs: if five wired
gates still share 8769, either the ratchet does not cover concurrently-wired gates or these were
wired past it. Not diagnosed here; observed, dated, and handed over with the run that shows it.


### 2026-08-15 — 8769 RETIRED, and the fix was wrong the first time in an instructive way

`std-ui-forms-smoke.sh` moved to **8771**, so the frozen pair is gone: `no-leaked-servers.sh` now
reports **2 frozen, none new** (was 3). Both gates verified concurrently from a clean port state —
`std-ui rc=0 (0 skip, 0 fail)`, `health-defaults rc=0`, **0 foreign-server complaints**.

**MY FIRST ATTEMPT PASSED WHILE TESTING NOTHING, and that is the part worth keeping.** Changing only
`PORT=8769` → `PORT=8771` in the gate left `examples/std-ui/demo.ssc` serving on `serve(8769)` — the
port lives in the PROGRAM as well as in the harness. The gate then polled a port nothing served,
`wait_for_server` timed out, and the timeout path is a `[skip]` that RETURNS 0:

```text
  [PASS] INT
  [skip] JVM: server did not start within 90s
  [skip] JS:  server did not start within 90s        exit 0
```

I read `rc=0` three times over and called it verified. The rows say two of the three lanes had
stopped being tested. **A gate that skips on "server did not start" cannot tell a broken launcher
from a port its subject never binds** — its own comment calls those skips "environmental", and a
port mismatch is not environmental. Fixed by moving the port in both places; the rows now read
`[PASS] INT / [PASS] JVM / [PASS] JS`.

**Not fixed here, and separable:** `health-defaults-smoke.sh` can trip on its own predecessor. Run
back-to-back, the previous run's server is occasionally still dying when the next one starts, and
the new run reports `:8769 is held by a process this gate did not start` naming a holder ~26 s old.
From a verified-clean port it passes all four lanes with no leak, so this is a race between the EXIT
trap's `kill -9` and the next start, not a missing cleanup.

## a-gate-that-starts-a-server-cannot-prove-it-is-talking-to-its-own

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     reported-by: claude-code
     reported-at: 2026-08-14
     confirmed: yes
     gate: tests/e2e/no-leaked-servers.sh --self-test
     fixed-in: a6c7eac8296d4ae3f40fbba8aca7f7622a8a0928 -->

> **CONFIRMED AND FIXED 2026-08-14. The instance this was filed without is `serve-view-frontend-v2-smoke`.**
> It asserts `http=200` plus a `frontend=` string scraped from its OWN LOG — neither from the
> response body — so any foreign 200 satisfies it. Planted with the launcher replaced by a script
> that prints the frontend line and sleeps, and a python server holding :8099:
>
> ```
> --v2: http=200 frontend=react swiftui-crash=0      ← the exact string this gate calls a PASS
> ```
>
> Nothing of ours had bound. `confirmed: no` was right when written and is now answered.
>
> **Ownership, which is the entry's option (1) done without touching the served program.** The port
> is written in the EXAMPLE (`examples/health-defaults.ssc` ends in `serve(8769)`), so both a nonce
> and an OS-allocated port edit `examples/` to fix a harness problem. Asking the OS which process
> holds the socket needs nothing from the program: `tests/e2e/lib/own-server.sh` compares the
> listener against the pid the gate started, **as a process TREE** — `bin/ssc` is a launcher script
> and whether the JVM is the same pid or a child differs by lane, so a pid equality would have been
> a test of `exec` versus `&`.
>
> Wired into all five gates that boot on a fixed port, both frozen collision pairs included. The
> plant is asserted in `no-leaked-servers.sh --self-test`, which `ci.yml` already runs, in both
> directions — a foreign holder must be refused AND a server started underneath the given pid must
> be accepted (the positive case is deliberately a grandchild).
>
> | gate | after wiring |
> |---|---|
> | `health-defaults-smoke` | 4/4 PASS, unchanged from baseline — four different launchers all pass the tree comparison |
> | `std-ui-forms-smoke` | 3/3 PASS |
> | `request-validation-family-gate` | PASS, both boots; launched detached, so the pid is recorded to a file |
> | `route-params-v2-smoke` | PASS |
> | `serve-view-frontend-v2-smoke` | v1 a real pass; v2 still red on `v2-lane-does-not-serve-the-content-introspection-view`, and the check correctly stays silent — "no listener visible" |
>
> **Two faults found by running it, both apparatus faults that read as product faults** — the same
> family as the defect itself. Diagnostics had to move to STDERR, because gates call their lane
> function inside `V1=$(run_lane --v1)` and stdout is captured into the verdict string. And a
> forgotten `source` line made every call "command not found" — non-zero — which the call sites read
> as "foreign", so one gate accused two healthy lanes; the source is now guarded and refuses to run
> rather than mis-report.
>
> **Option (2), the OS-allocated port, is NOT done and the `FROZEN_COLLISIONS` list stands.** It
> needs the served program to report the port it got, and the ports live in `examples/`. Ownership
> makes a collision detectable; it does not make one impossible.

Split out of `wired-gates-share-hard-coded-tcp-ports` when that entry closed, so a real improvement
its revision had dropped does not vanish with it.

**A gate that starts a server on a fixed port and then curls that port cannot tell its own server
from anybody else's.** It polls `http://localhost:$PORT` until something answers and treats an answer
as proof its launcher worked. Anything already listening satisfies that — a leaked process, a
sibling agent's run, a second gate on the same number. The failure is silent and it is
green-coloured, which is the worst pairing: a gate whose launcher is completely broken still reports
success. This project has recorded the shape before as *a probe measures the PORT, not the lane*.

**Not confirmed, and the honest state is that nobody has caught it happening.** Its parent entry
first blamed exactly this for a verdict that moved between runs, then withdrew the explanation on a
tighter measurement, and the three port collisions that remain are each either orphan-involving or
split across two suites that never share a runner — see the table in that entry. So the mechanism is
real and the instance is hypothetical. `confirmed: no` says which of those two this is.

**Acceptance test, since without one this is a note and not a task.** A gate must identify its own
server, not merely find one. Either is enough and the second is strictly better:

1. **A nonce.** The launcher passes a value the gate generated; a health route echoes it; the gate
   requires the echo to match. Somebody else's server answers with the wrong nonce and the gate
   fails, which is the correct outcome and currently an impossible one.
2. **An allocated port instead of a chosen one.** Bind port 0, read back what the OS gave, hand that
   to the client half. There is then nothing to collide with and nothing to freeze — and it retires
   the `FROZEN_COLLISIONS` list in `no-leaked-servers.sh` rather than adding to it.

Start with the two gates whose ports are shared and both wired — 8769 (`health-defaults-smoke.sh`,
`std-ui-forms-smoke.sh`) and 8797 (`route-params-v2-smoke.sh`,
`request-validation-family-gate.sh`) — because those are the pairs a single dev box running both
suites can actually cross.

**The check that proves it is done must plant the failure, not observe the success:** start a
foreign listener on the gate's port, run the gate with its own launcher deliberately broken, and
require RED. A gate that passes that plant today is the evidence this entry is missing.

## f-parser-gap-needs-the-package-field

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     fixed-in: 88c5741f6
     gate: tests/e2e/f-bodyless-object-gate.sh -->

### FIXED 2026-08-14 — and the title names a correlate, not a cause

`std/parsing/core.ssc` is `F` now, with its `package:` field untouched. The cause was
`objBodyToks`' missing `;` stop — see `f-package-namespace-breaks-on-an-object-with-extends` for
the derivation, the refuted law and the before/after verdicts. `package:` was necessary only to make
the defect FATAL: the generated namespace is what emits `def __pkgref_std_parsing__Parser = Parser`,
the reference that turns a silently misfiled object into an unbound global. The measurement recorded
below — "delete `package:` and the file compiles" — is true and was read as a cause.

The ruled-out list below stays: every item on it is still ruled out, and none of them was ever going
to be it, because the trigger is not a declaration at all — it is a bodyless `object` header being
followed by any brace at all.

`std/parsing/core.ssc` is `GAP` with `unbound global: (global Parser)`, and it is the root of the
largest remaining F coverage hole: four corpus files (`examples/dsl-calc-parser.ssc`,
`dsl-json-parser`, `dsl-sql-recovery`, `dsl-yaml-like`) decline for the same reason.

**Still open** as of 2026-08-13 — re-measured, because the previous census predates three front
fixes that landed the same week. Note for whoever measures next: `ssc info --front-report` prints a
PREAMBLE containing the word "F" ("**F** did not lower this file"), so `grep -oE '\b(F|GAP)\b' | head -1`
reads GAP as F. Parse the TSV line (`cut -f2`); I reported "the gap closed itself" for one turn on
exactly that mistake.

**The one necessary condition found: the `package:` field in the module frontmatter.** Delete it
and the file compiles under F. Delete ANY other frontmatter field — `name`, `version`,
`description`, the 15-entry `exports` list — and it still declines. Prose without frontmatter: F.
Frontmatter without prose: GAP. Neither: F.

It is necessary but NOT sufficient: a small module with `package: std.parsing`, a sealed trait, a
case class and a companion object compiles fine. So the trigger is `package:` plus something else
in the file that is not any single declaration.

**A 91-line well-formed reduction exists** (from 118), at a fixpoint: every remaining declaration
is load-bearing — removing any one flips the verdict to F. It is `std/parsing/core.ssc` minus nine
declarations, with `sealed trait Parser`, `object Parser` and `trait ParserContext` PINNED. The
pinning matters: an unpinned reducer deletes the trait, which satisfies "GAP with (global Parser)"
trivially and converges on a program that proves nothing — that is how the previous attempt failed,
and my first pass repeated it by deleting `trait ParserContext` while keeping
`case object NoContext extends ParserContext`. Always re-check the reduction still RUNS on the
reference front before believing a verdict from it.

**Ruled out by measurement, so the next attempt need not re-walk these:**
* the number of case classes extending the trait — 7 synthetic ones compile fine;
* the fence count — the one-fence version of the reduction is still GAP (I briefly concluded the
  opposite from a variant that dropped the frontmatter AND the fences at once, and attributed the
  effect to the wrong one);
* the `exports:` list naming a type;
* the trait/companion-object same-name pair;
* a generic case class (`case class PSucceed[A](value: A) extends Parser[A]`);
* a generic method inside the object (`def succeed[A](value: A): Parser[A]`);
* a type-parameterised extension (`extension [A](p: Parser[A])`).

~~**Next step:** with `package:` known necessary, bisect what it interacts with — the natural
candidate is whatever F does with package-qualified declaration names, since the symptom is a
declaration that is REFERENCED but never EMITTED.~~ — The reasoning was sound and the premise was
not: `package:` is not necessary, and the declaration was never emitted because a bodyless `object`
above it had already swallowed the scan. See the FIXED note at the top.

## f-leaks-its-own-block-end-sentinel-as-an-unbound-global

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     fixed-in: 7055b8168
     gate: tests/e2e/f-blockend-sentinel-gate.sh -->

On one variant of the `std/parsing/core.ssc` reduction (the code and prose, frontmatter stripped),
F declines with:

```
unbound global: (global __sscBlockEnd__) is neither a top-level def nor an @-cell
```

`__sscBlockEnd__` is F's OWN sentinel: `ssc1-run-fsub.ssc0:188` appends it to each code fence so the
block boundary survives the join, and `walkTop` is supposed to consume it. Here one reaches the
program as a value reference instead.

**Not caused by the reference-front block-tail fix that landed the same day** (`374f6ce01`). That
change touched `ssc1-run.ssc0` and the reference lowerer only; F's runner has emitted this sentinel
since the native lane was fixed, and `--front-report` asks F about source prepared by F's own
runner.

**Not general:** ordinary two-block documents are fine — a `val`+expression block followed by a
`def` block, a trait block followed by a case-class block, and a `main` block followed by a helper
block all report F. Narrowing which shape leaves a sentinel unconsumed is not done.

**Fixed in `7055b8168`. The shape is an EMPTY code fence** — five lines reproduce it:

```
```scalascript
def main() = println("a")
```

```scalascript
```
```

An empty fence still gets a sentinel appended, and F consumed sentinels only in `walkTopStep2`,
i.e. AFTER `parseTopItem` had run. With no item in front of it the sentinel was parsed as an
ordinary expression and reached the program as a value reference. `walkTop0` now skips a leading
sentinel, mirroring the trailing case; an empty block has no tail to auto-print, so nothing is lost.

Position does not matter — first, last, middle, and two in a row all reproduced, and all are rows in
the gate. The reference front and the v1 interpreter were never affected: the reference consumes the
sentinel in `topExprs` by matching the statement wherever it appears.

**Why it hid:** F declining means a silent FALLBACK to the reference front, so the program still ran
correctly and only F's coverage was lost. An output gate cannot see which front ran, so the gate
added here asserts the FRONT via `ssc info --front-report`.

## ref-front-drops-every-block-tail-but-the-last

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     fixed-in: 374f6ce01
     gate: tests/e2e/ref-front-multiblock-gate.sh -->

A `.ssc` document's contract is that the last non-Unit expression **of each** top-level code block
is printed, in source order. The reference front printed only the whole PROGRAM's final value, so
every earlier block's tail vanished without a diagnostic.

```
tests/conformance/multiblock-auto-output.ssc
  эталон  explicit            ← три хвоста, напечатан один
  F       2 | 20 | explicit
  интерп  2 | 20 | explicit
```

**Why it survived.** A ONE-block document is correct either way — the program's final value IS that
block's tail — and most documents have one block. The corpus survey that first noticed the
divergence recorded it as *"F prints two EXTRA lines the reference does not"*, i.e. blamed F for
being right.

**Mechanism.** The runner joins every code fence into ONE source before the lexer runs, so the block
boundary survives only as the `__sscBlockEnd__` sentinel appended per fence. `ssc1-run-fsub.ssc0:188`
has emitted it for F since the native lane was fixed; `ssc1-run.ssc0` never did, and the reference
lowerer had no idea it existed.

**Fix**, mirroring F line for line: the runner appends the sentinel, and `topExprs` intercepts it
BEFORE lowering — so it never reaches `resolveE` as an unbound global — and wraps the statement it
follows in the `__autoOutput__` prim. A definition is not an `"expr"` statement at all, so it is
never wrapped: v1's rule, for free. Unit-ness stays a RUNTIME property decided inside the prim; a
source-level helper is not portable across consumers, which F learned first and the corpus caught
on the JS/v2 lane.

Corpus gate: agree 254 → 273, measured 297 → 314, F-wrong still 0. The "where the REFERENCE is
wrong" bucket is now empty — this was the last file in it.

## orphan-detector-counts-a-comment-as-a-caller — the ratchet was blind to three real orphans

<!-- status: fixed
     lane: apparatus
     area: build
     reported-by: claude-code
     reported-at: 2026-08-13
     confirmed: yes
     fixed-in: b97c36f2e
     gate: tests/e2e/no-orphan-gates.sh -->

`no-orphan-gates.sh` exists so that a gate nobody runs cannot report green by not running. It landed
2026-08-13 counting a reference from any non-`.md` file as an invocation — and prose does not only
live in `.md`. A `#` line inside a sibling gate, or inside `scripts/*`, read exactly like a call.

```text
before   183 scripts, 35 invoked by nothing, 35 frozen   PASS
after    183 scripts, 38 invoked by nothing, 38 frozen   PASS
```

Three gates were held "wired" by a sentence that merely MENTIONS them:

| gate | what was holding it "wired" |
|---|---|
| `bytecode-fallback-visible.sh` | a comment in `tests/e2e/f-alternative-pattern-gate.sh` |
| `negtc-shard-gate.sh` | a comment in `scripts/native-front-corpus` and `scripts/bc-parity-sweep` |
| `ssc1-front-annotation.sh` | a comment in `tests/e2e/silent-assertion-gate.sh` |

**Two of the three are named in a `gate:` field on the v2 board** (`v2/BUGS.md`), so those entries
claim coverage from a script nothing runs — the exact harm this ratchet exists to prevent, sitting
inside the ratchet itself.

**The tell, worth stating generally:** the search is for a STRING while the thing being detected is
an INVOCATION. Every commented-out call, and every sentence describing one, is indistinguishable
from the real thing under `grep -l`.

### The fix, and the control

`callers_of` strips the comment tail (`#`, `//`) before deciding, so the basename must survive as
CODE. A real call with a TRAILING comment still counts — asserted in the self-test, because the
over-strict version would silently reclassify working gates as debt to be frozen.

**Verified by reverting the fix in a copy of the tree and re-running the self-test: it FAILS,
naming the comment case.** An assertion never observed failing is not an assertion.

### Two holes in the self-test, and the second is why the first survived

- The "a `.md` mention does not count" case used `$TMP/prose.md` at the tree ROOT, while
  `callers_of` reads only `.github`, `scripts` and `tests`. The file was never opened, so that
  assertion passed whether or not the `.md` filter existed. Moved under `$TMP/tests/`.
- There was no comment case at all. Both directions added.

### FOLLOW-UP: three gates that still do not run

Frozen, not wired — they are not new debt, they are the debt that was always there and could not be
seen, and freezing is what keeps the list monotone. Each needs its own decision:

- ~~`bytecode-fallback-visible.sh`~~ **WIRED 0c7de52f5** into `ci.yml`'s `validate` job (PR +
  nightly + dispatch, checked — not the smoke suite, at 52 s). The concern that it needed the
  unpinned marker resolved first was wrong: the marker's remaining source is deliberately unpinned
  and the gate says so in a `note:` line, while everything it DOES assert passes. Removed from
  FROZEN, 38 to 37. The override that ran its oversized subject on the legacy front is gone too, on
  the condition the gate itself stated — `f-front-compile-cost-7x-on-scljet` was fixed in
  `ee53eff5d`, and `scljet-hello` now takes 20 s per lane on the default front against >1800 s
  before, so the subject covers the lane users actually get.
- `negtc-shard-gate.sh` — **STAYS FROZEN. Sergiy's decision, 2026-08-13: do not wire it.** Recorded
  here so the next reader does not re-open it as unfinished work — it is a deliberate exemption, not
  an oversight, and this is the one entry in FROZEN that is not expected to shrink.
- ~~`ssc1-front-annotation.sh`~~ **WIRED** into the NIGHTLY `f4-front-swap.yml` (cron 04:30 UTC +
  dispatch), explicitly not the push path. It went there rather than into `ci.yml` because that
  workflow already packages the v2 kernel jar one step above, which drops the gate from a ~4-minute
  self-built assembly to **2 s** with `SSC_JAR` — and because the subject is that workflow's own
  premise: its gates compare F against the untyped legacy ORACLE, and this asserts the oracle still
  lowers an own-line annotation. A broken oracle would make every comparison there meaningless.
  Run with `--self-test`, which proves the probe can fail before the real checks run — necessary
  when the assertion is the ABSENCE of an `_err` sentinel, the same answer a probe looking at
  nothing would give. FROZEN 37 to 36.

## ref-front-drops-all-but-one-vararg-when-an-earlier-param-is-named

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-12
     confirmed: yes
     fixed-in: 85d305116
     gate: tests/e2e/ref-front-three-defects-gate.sh -->

In the reference front, a call passing an argument **by name** to a def with a **trailing vararg**
kept only the FIRST vararg argument and silently dropped the rest.
`tests/conformance/tkv2-hstack-wrap.ssc` is the real instance — a row of widgets built with one
child instead of two:

```
def hstack(gap: Int = 0, wrap: Boolean = false)(children: TkNode*)
hstack(gap = 8)(a, b)     эталон 8:1:false → 8:2:false     F и интерпретатор 8:2:false
```

**Mechanism, from IR dumps rather than inference.** The front flattens the call's clauses AND
pre-fills the first clause's unnamed params with their defaults as positionals, so
`expandNamedDefaultCall` sees roughly `[narg(gap,8), false, a, b]`. `nargBindings` walks params and
positionals one-for-one: `gap` takes the named 8, `wrap` takes the positional `false` (correct),
`children` takes `a` — and the recursion ends. `b` is dropped before anything downstream can see
it. No diagnostic; the program just gets a shorter collection.

```
до:    (app (global hstack) (local 2) (local 1) (ctor Cons (local 0) (ctor Nil)))
после: (app (global hstack) (local 1) (local 0) (ctor Cons a (ctor Cons b (ctor Nil))))
```

**The fix** leaves packing where it belongs. `packVarargsArgs` folds everything past `arity-1` into
a Cons-list, so the expansion hands it MORE args than there are params: the lead bindings plus every
unconsumed positional, SPREAD. `nargPosUsed` counts what the lead walk consumed, mirroring
`nargBindings`' per-param choice so the two cannot disagree about where the leading params stop.
It fires only when the callee has a trailing vararg, the call carries a named argument, and the
vararg slot is not itself named.

**Two dead repairs, both measured:**
1. *Build the list inside `nargBindings`.* Double-packs — `packVarargsArgs` folds it a second time
   and the length is 1 again for a different reason. The comment above that call already recorded
   this failure ("packing there compounded into a nested list"): a paid-for experiment, ignored.
2. *Split the positionals at the first named argument* (Scala requires positionals to precede named
   ones, so the boundary looks exact). Wrong here because the front has ALREADY inserted clause-1
   defaults as positionals — the split hands `wrap`'s own `false` to the children, giving three.

**What made this expensive was the probe, not the defect.** I reduced to a synthetic
`def h(gap: Int = 0, wrap: Boolean = false, kids: Int*)` — a SINGLE clause — and called it
`h(gap = 8)(1, 2)`. That is not valid Scala and does not have the real shape, and it disagreed with
the real one at every step: repair 2 looked correct on it and produced three children on `hstack`,
and the repair that actually works looked like a no-op on it. The real callee is CURRIED, and
currying is the whole reason the defaults arrive as positionals. Reduce toward the smallest program
that still has the SHAPE, and check the reduction against the real input before trusting it.

An unrelated pre-existing failure was found while verifying this and is filed separately —
[[f-miscompiles-scljet-record-fields-to-the-fallback-arm]].

## f-miscompiles-scljet-record-fields-to-the-fallback-arm

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-12
     confirmed: yes
     fixed-in: b56c38ce9
     gate: tests/e2e/f-nested-pattern-lambda-gate.sh -->

**FIXED in b56c38ce9.** `f-output-agreement-gate` was RED on origin/main. F is contradicted by BOTH other lanes on
`examples/scljet-readonly.ssc` — the bucket whose ceiling is 0 and which the gate itself calls
"a REGRESSION, not a coverage gap".

**Narrowed to four legal lines.** F answers `-1`; the reference front and the v1 interpreter both
answer `7`:

```scala
case class Inner(v: Int)
def main() =
  val f = (x: Option[Inner]) => x match { case Some(Inner(v)) => v case _ => -1 }
  println(f(Some(Inner(7))))
```

**The law, from a variant sweep.** Two things must hold together; neither alone reproduces.

1. The pattern must be a NESTED constructor pattern. `case Some(v)` is fine, `case Inner(v)` is
   fine, `case Outer(a, _, _, _)` is fine — `case Some(Inner(v))` is not.
2. The enclosing scope must have EXACTLY ONE binder, and it must be a lambda:

| enclosing scope                          | F   |
|------------------------------------------|-----|
| lambda, 1 param                          | ✗   |
| lambda, 2 params                         | ✓   |
| lambda, 3 params                         | ✓   |
| lambda, 1 param + any `val` before the match | ✓ |
| `def`, 1 param                           | ✓   |
| `def`, 1 param + a `val`                 | ✓   |

Adding ONE binder of any kind fixes it, which makes this an off-by-one: the nested machinery emits
`(local pos)` as though there were one more enclosing binder than there is. `val s = x; s match …`
also fixes it, so it is the binder count and not the identity of the scrutinee.

**Two symptoms, same cause.** With the nested field LAST the arm is silently missed and the default
is taken — a wrong answer at exit 0, which is how it survived to main. With the nested field NOT
last, F refuses outright: `unbound global: (global q) is neither a top-level def nor an @-cell` for
the binder that follows the nested one (`case Two(Some(Inner(v)), q)`), so the shift also loses the
later binders.

**Where it is NOT.** F's discharge chain was compared line by line against the oracle it claims to
mirror (`dischargeObsOr`, ssc1-lower.ssc0:3487) and matches on every part that feeds the SUCCESS
path: inner success scope (`revL(fldBinders(subs))`), sub-obligations (`fldRefinedObligations(subs, m)`),
and the shift of the remaining obligations (`shiftObs(rest, m)`) are all present and identical.
The one structural difference is that the oracle threads a separate `scrutPos`, advanced by `m` at
each level, while F's `dischargeF` has no such parameter — but the oracle uses `scrutPos` only for
the FAILURE path (`lowerOrderedGuardArms`), and this defect takes the success path wrongly. So the
next place to look is the OUTER arm emission and the scope base a lambda body starts from, not the
discharge.

**Next step, and why it did not happen here:** dump F's IR for the four-line reproducer via the F0
bootstrap and read the emitted `(match (local N) …)`. That needs a freshly built kernel
(`scala-cli --power package v2/src --assembly`) — the cached `/tmp/tfd-kernel.jar` is older than
`v2/src/*.scala`, and a stale kernel has already produced one false "the two Fs disagree" finding
in this repo.

**Not caused by the vararg fix landed the same day** — established by revert + rebuild, and it
reproduces on a clean checkout of origin/main with nothing applied.

**The cause, read off F's own emitted IR.** `tryLamDirect` -- the fast path for `(x) => x match { … }`
-- emits `(lam 1 (match (local 0) …))` directly and parses the arms with `parseArms`, which has no
nested machinery. Its own comment already scoped it to "only SIMPLE ctor arms", but `isLamSelfMatch`
gated on nothing beyond arity 1 plus a self scrutinee:

```
F:      (lam 1 (match (local 0) ((arm Some 3 (let ((lit (int 0))) (local 1)))) (default …)))
oracle: (lam 1 (let ((local 0)) (match (local 0) ((arm Some 1
                  (match (local 0) ((arm Inner 1 …)) (default …)))) (default …))))
```

Wrong arity, no let-wrap, no inner match. The fast path is gated on `ar == 1`, which is exactly why
a second binder of any kind made it work and why the whole variant table above reads as an
off-by-one when it is really a routing miss. The fix declines nested arms there and falls through
to `parseLamBodyG`, mirroring the `hasNestedArms` clause `parseMatchArms` already carries (:1716).

Corpus gate, before → after: F-wrong 1 → 0, agree 227 → 254, measured 270 → 297. Twenty-seven more
files compile under F and agree with the other two lanes.

**The reproducer battery is a gate:** `tests/e2e/f-nested-pattern-lambda-gate.sh`, carrying the
working shapes (two-param lambda, `def`, flat ctor arm, shallow `Some(v)`) alongside the failing
one -- every probe with a second binder passes on the bug, and a fix that merely disabled
tryLamDirect would pass a gate built only from the failure.

## renderTerm-is-two-and-a-half-times-the-jit-limit

<!-- status: open
     lane: v2-rust
     area: codegen
     reported-by: claude-code
     reported-at: 2026-08-12
     confirmed: yes
     gate: tests/e2e/v1-jit-size.sh -->

`scalascript.codegen.rust.RustCodeWalk$::renderTerm` is **20333 bytecodes — 2.54× the 8000-byte
HotSpot `HugeMethodLimit`**, past which a method is never JIT-compiled. It has been over that line
for a long time and keeps growing: 16346 → 19550 over the period `v1-jit-size` was wired to nothing,
then 19550 → 19630 → 20042 → 20333, the last three raises all inside two days and all announced.

**THE JIT FRAMING WAS WRONG AND IS RETIRED HERE. Measured 2026-08-13, because three raises in one
day had turned "2.5× the JIT limit" into a phrase nobody had checked.** The instrument was validated
before the result was believed: `-XX:-DontCompileHugeMethods` must actually change the thing under
test, and on a single module it does not — renderTerm is submitted zero times with the flag AND
without, because it never gets hot. Only across 51 modules in ONE JVM does the flag bite (0 without,
1 with, tier 3). On that workload — the most favourable one that exists — the A/B says:

| | |
|---|---|
| capped, renderTerm never JIT-compiled | 2.25 s |
| uncapped, renderTerm JIT-compiled | 2.29 s |

**+0.040 s, in the wrong direction, inside the spread.** The work is in the ARMS — 402
`RustCodeWalk` methods do get compiled, as separate lambda and anon-class methods — not in the
dispatch. And that is generous: in production this compiler runs one short-lived JVM per module,
where renderTerm is never submitted even with the flag.

**So "the difference between interpreted and compiled for every emit", which this entry used to
claim, is false for this method.** It stays open, because the debt is real — but the debt is
**198 arms in one 2000-line match where ORDER IS SEMANTICS**. A `Map.contains` arm must precede the
str-receiver arm; an `extension` call arm must come first among the `Select` arms; a signal read
must be decided before the generic call arms. Every one of those was a defect before it was a rule.

**What closing it looks like — and the first answer here was WRONG.** This entry used to say
"fewer arms, not smaller ones", on the strength of one measurement: extracting two arms' bodies into
helpers moved 20333 → 20325, eight bytecodes of 291. That was true and it was the wrong conclusion.

**The bytecode is in the CONTEXT RECORD'S WIDTH, not in the arms.** Proved by control on
2026-08-13, after three wrong guesses in a row: adding a single field to `Ctx` grows this method by
**12 bytecodes even when the field is never read anywhere** — a second, deliberately unused field
took it 20333 → 20357. `renderTerm` holds five `ctx.copy(...)` call sites and each one materialises
every field.

Four restructurings measured the same day, all of them local, none of them working:

| | |
|---|---|
| extract two arms' BODIES into helpers | 8 bytecodes of 291 |
| fold a new arm into an existing dispatch | 8 of 96 |
| both together | 36 of 96 |
| lift a nine-set guard out of the method | **0** |

So the lever was **the five `copy` sites factored into one helper** — not splitting the term cases,
which is what this entry recommended for two days and which would have moved arms around while every
copy site kept paying for the same record.

**THE OTHER HALF OF THAT ADVICE — "fewer fields on `Ctx`" — IS NOW WRONG, and was checked before
being acted on.** With the copies folded, two probe fields added to the 24-field record and measured:

| | |
|---|---|
| `renderTerm` | 20085 → 20085 — **zero** |
| `walk` | 2378 → 2402 — +24, the same 12 per field |

The per-field cost is alive but has RELOCATED to `walk`, where `Ctx` is constructed, and `walk` is
2.4 KB against an 8000-byte limit. **Narrowing the record would buy nothing where it matters.** The
work that was worth doing has been done; anyone sent here to remove fields should read this table
first and spend the cycle elsewhere.

**DONE, AND IT IS THE FIRST TIME THIS NUMBER HAS GONE DOWN: 20345 → 20085.** Five of the six
`ctx.copy(...)` sites in the method were the SAME SHAPE — bind a closure's parameters, set
`inClosure` — and each materialised all 24 fields. One helper, `enteringClosure`, replaced all five:
**−260 bytecodes**, no behaviour change (`backendRust/test` 278/278, the std corpus unmoved at
REFUSED 81 / COMPILES 51 / BADRUST 0). The frozen number was LOWERED to match rather than left as
headroom, and the marginal cost of the next `Ctx` field falls with it — one copy site in the method
instead of six.

Still 2.51× the JIT limit, and by the measurement above that costs nothing here. What it buys is the
ratchet: a change that adds a field now moves this number by about 2, not 12.

**This does not generalise to `handleActorOp`**, the other big frozen method, and the distinction is
the point: that one is the actor scheduler, running inside the user's long-lived program millions of
times, which is exactly the hazard the limit describes — see `v1-interpreter-hot-path-never-jits`.
Same list, same number, opposite meaning.

**Who grew it and why they could not have known:** `189b8b111`, *fix(rust): the last four, and the
BADRUST column reaches zero*, at 10:10 — thirty-two minutes after `d7c158fbf` made this gate capable
of catching anything for the first time. It had been referenced by no workflow and no suite. Nobody
did anything wrong; the gate simply did not exist yet in any sense that could have warned them.

## derived-budget-underpredicts-ci-and-reds-main — a harvester that filtered out its own evidence

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: tests/e2e/smoke-budget-gate.sh
     fixed-in: 78710cf86 -->

Raising the job cap (`job-timeout-fires-before-the-suite-budget-can`) made every push reach a verdict
for the first time — and the verdict was that **72 % of them FAILED**, where before they were
`cancelled` and counted as nothing. One of the two causes was mine: `OVER BUDGET — 916.6 s of 613 s`.

**Cause 1: the harvester took only `success` runs, which is a self-reinforcing selection bias.** What
most often makes a smoke run unsuccessful is exceeding the budget derived from this very table. So
the slow runs — precisely the ones a budget must cover — were filtered out of their own evidence:
low budget → slow runs fail → only fast runs are harvested → the budget stays low. Refreshing the
table under the old filter made it *worse*, 738.5 s → 636.7 s, while CI was taking 916.6 s. `failure`
runs are now included; their per-check timings are real measurements. `cancelled` and `timed_out`
stay out — those reached no verdict and may be truncated.

**Cause 2: the 123 s margin was fitted on that biased sample.** Unbiased over 16 runs the model
predicts the TYPICAL run almost exactly — median residual **−0.8 s** — and misses a tail of three
runs at +220 to +231 s.

**The tail was diagnosed rather than absorbed.** Those runs are slow UNIFORMLY: median per-check
ratio **1.72 across 59 checks** against the fastest run, and the three biggest individual blow-ups
account for 23 s of a 400 s difference. Host contention, not a grown check — and the probe cannot see
it, reporting 222–234 ms, its normal range. That is exactly what a margin is for. 250 s gives **0 of
16** runs red, against 3 of 16 at both 123 and 200.

A budget that reds three runs in sixteen on host noise is worse than no budget, because it teaches
everyone to ignore it. The guard against a GROWN CHECK is not this number — it is the per-check
share report, which is scale-free and unaffected by a uniformly slow host.

**The gate no longer pins the margin, it READS it.** `smoke-budget-gate` failed the moment 123 moved,
correctly — it is an arithmetic check — and the tempting repair is to type the new number in, which
after two rounds leaves a gate asserting whatever the code does. It now greps the margin out of
`scripts/smoke-ci.ssc` and dies loudly if that shape moved. A/B'd both ways: moving the margin
250 → 300 leaves it PASSING (it follows), while breaking `den` fails it with
`derived budget is not the documented arithmetic`.

**If the probe is ever taught to see a 1.7x uniform slowdown, this margin should come back down.**

## the-agreement-gate-calls-the-reference-front-an-oracle-and-it-is-not-one

<!-- status: fixed
     lane: apparatus
     area: front
     reported-by: claude-code
     reported-at: 2026-08-11
     confirmed: yes
     fixed-in: 9bf1df0da
     gate: tests/e2e/f-output-agreement-gate.sh -->

**`tests/e2e/f-output-agreement-gate.sh` reports a number called "F WORSE than the reference". It
measures "F DIFFERS from the reference", and those are not the same claim** — because the reference
front is not always the correct one.

Measured on the four conformance divergences the sweep turned up, with the **v1 interpreter** as an
independent third lane:

```
                              F              reference        interpreter
set-ops-infix                 Set(1, 3)  ✓   Set(1, 2, 3) ✗   Set(1, 3)  ✓
tkv2-hstack-wrap              8:2:false  ✓   8:1:false    ✗   8:2:false  ✓
multiblock-auto-output        2|20|expl  ✓   explicit     ✗   2|20|expl  ✓
extension-call-in-a-def-body  arity err  ✗   (empty)      ✗   body-a     ✓
```

The fourth row is the one where the interpreter stood alone, and it stayed that way for three days
because the two front columns look like two independent defects. They were ONE: a single-line
`extension (r) def m` never opened its member block, so the receiver was still live for every `def`
below it. The reference lost `main` to the absorption and printed nothing; F kept `main` but gave it
the receiver as a parameter and called it with none. Fixed on both fronts 2026-08-13/14 — the row
now reads `body-a ✓` in all three columns.

**Three of the four are REFERENCE-front defects with F in the right.** The gate counted all four
against F. `set-ops-infix` even documents its own expected values in a table — `int`/`jvm` give
`Set(1, 3)` and the reference's answer is named there as the *before* state of a fixed bug.

**What to do about it is a design decision, not an oversight to patch quietly.** A third lane per
file doubles the gate's runtime, which is already the reason conformance is excluded. The cheap
half is honest labelling: the number is a DIVERGENCE count, and a divergence needs a third opinion
before anyone calls it F's fault. The expensive half is running the interpreter as tie-breaker on
divergent rows only — which is cheap in practice, since divergences are the small bucket.

Filed rather than fixed here because the gate's thresholds are frozen against the current meaning,
and changing what the number means changes what the freeze is worth.

**Fixed in `9bf1df0da` (2026-08-12), "the agreement check gets a third lane, and its number stops
lying"** — filed 2026-08-11, addressed the next day, and this entry was simply never closed. Both
halves the entry asked for are in the gate now:

* the label is honest — the header says the divergences are *arbitrated by the v1 interpreter*, and
  the buckets are named `where F is WRONG (interpreter agrees with the reference)`, `where the
  REFERENCE is wrong (interpreter agrees with F) — not F's work`, and `all three lanes differ`;
* the third lane runs on divergent rows only (`f-output-agreement-gate.sh:126-128`), and the frozen
  ceiling is on the tie-broken count — `worse=$fwrong`, described in the gate as "contradicted BY
  BOTH OTHER LANES. Nothing else belongs in a ceiling."

The three reference-front defects this entry was built on were all fixed on 2026-08-13
(`050fb98d2`, `85d305116`, `374f6ce01`), and in every one the classification the new third lane
produces is the correct one: it put them in the `REFERENCE is wrong` bucket while they were open,
which is exactly the misattribution the entry existed to stop.

Closed after reading the gate rather than re-implementing it. The entry's own prescription was
detailed enough that it was tempting to just build it again.

## job-timeout-fires-before-the-suite-budget-can — the outer cap was tighter than the measured one

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: none
     fixed-in: 138335a54 -->

**73 % of pushes reached no verdict, and the cause is not what I first said it was.** I diagnosed it
as a newer push superseding the run and proposed removing `cancel-in-progress`. `smoke.yml` has no
`concurrency:` block at all — there was nothing to remove. `cancelled` is how GitHub reports an
exhausted **job timeout**.

Measured over the last 38 completed runs:

| conclusion | n | median | range |
| --- | --- | --- | --- |
| cancelled | 28 | 20.4 min | **20.1 – 20.6** |
| success | 7 | 16.8 min | 14.3 – 20.0 |
| failure | 3 | 16.1 min | 14.8 – 19.5 |

Every cancelled run sits inside half a minute of `timeout-minutes: 20`. Not a race, not a hang — the
cap.

**And the cap had become tighter than the guard it sits outside of.** Step timings from a successful
run: launcher build 3.3 min, suite 12.5 min, job 17.2 min. The suite's OWN budget — derived from
per-check baselines since 2026-08-10 and scaling with what the suite contains — runs 719–875 s on CI,
so the worst case is ~19 min against a 20 min cap. **The measured guard could never fire; the crude
one always got there first.**

Raised to 30, which leaves ~11 min over the worst observed case, and the rule is now written beside
it: this cap must stay strictly looser than the suite's budget plus build time, or the guard with the
measurement behind it is dead code.

**This argues with a sentence that stood in the file** — *"read the per-check timings the runner
prints — do not raise it. Raising a cap instead of reading the growth is exactly how
`corpus-contract.yml` went 13 runs with zero green."* Correct when written, and the correct instinct
in general. What changed is that reading the growth is now what the suite's own budget does
automatically, on every run, with a per-check table behind it. That budget still must not be raised
without measuring — `smoke-suite-over-its-own-budget` is about exactly that — and it is not what was
raised here.

## cancelled-exact-run-preempts-the-descendant-search — 73% of pushes got no verdict at all

<!-- status: fixed
     lane: apparatus
     area: build
     kind: bug
     gate: tests/e2e/ci-status-guard.sh
     fixed-in: 225060ee2 -->

**Measured 2026-08-11 over the last 60 smoke runs: cancelled 44, success 9, failure 7.** Three
quarters of pushes never reach a verdict, because a newer push supersedes the run. `cancelled` is RED
by POLICY P-6.7 and rightly so — but it meant evidence level 1 was close to unobtainable, and every
release that day settled for level 3.

`scripts/ci-status` already had the answer: `find_covering_descendant` asks whether a later run whose
commit CONTAINS this one reached a verdict. It was gated on `[[ -z "$run_payload" ]]` — *no run for
this SHA at all* — so in the 73 % case, where a run exists but was cancelled, **it never ran**, and
five commits that four separate green runs demonstrably contained all answered RED. The fallback was
disabled in exactly the case it was written for.

It now also fires when the exact-SHA run reached **no verdict** — `cancelled`, `timed_out`,
`startup_failure`. Not on `failure`: that is a real verdict about this commit and a later green must
not paper over it. Verified live — `1184b6e58`, which genuinely failed, still reports RED while
having green descendants.

**Two mistakes on the way, both caught by the gate rather than by reading.** Filtering descendants on
`success` alone turned a red descendant into `UNKNOWN`, and `ci-status-guard[desc-red]` failed at
once with `expected=1 got=2`; a descendant is evidence if it reached a verdict either way, and only
no-verdict outcomes are skipped. Raising the descendant window 40 → 120 also failed the gate, because
the guard's fake `gh` encodes that argument; reverted, with the limitation stated in the code — it
affects only the advisory NOTE, never the verdict.

**NEAREST and LATEST disagree, and picking one silently lies in one direction.** A run containing
`8c39d9df1` FAILED at 08:48 while six later runs containing the same commit were green. The green
ones answer what a release actually asks — *does main work with my change in it* — so they decide,
and a failing descendant inside the window is NAMED in the output rather than dropped.

A/B: with the old trigger that commit reads `CI RED`; with the fix, `CI GREEN (descendant)`.

## reference-front-answers-three-conformance-files-differently-from-both-other-lanes

<!-- status: fixed
     lane: v2-jvm
     area: front
     reported-by: claude-code
     reported-at: 2026-08-11
     confirmed: yes
     fixed-in: 374f6ce01
     gate: none -->

Three files where **F and the v1 interpreter agree and the reference front does not**. Found while
triaging the conformance sweep; none was the F defect it was first recorded as.

```
tests/conformance/set-ops-infix.ssc        Set(1,2,3) -- Set(2)
    F / interpreter   Set(1, 3)        reference   Set(1, 2, 3)
```

The file's own table names `Set(1, 2, 3)` as the *before* state of a fixed bug, and
`v2/BUGS.md` `v2-set-ops-and-or-coerce-to-int-and-double-minus-is-a-silent-no-op` says `--` was
repaired to raise on a non-Set "matching the interpreter". The reference front still answers the old
way, so either that fix never reached this lane or it regressed — I did not determine which, and the
difference matters for whoever picks it up.

```
tests/conformance/tkv2-hstack-wrap.ssc     F / interpreter  8:2:false    reference  8:1:false
tests/conformance/multiblock-auto-output.ssc
    F / interpreter   2 | 20 | explicit          reference   explicit
```

The last one is the loudest: the reference front emits **no output at all** for two blocks the other
two lanes evaluate and print.

**All three fixed, 2026-08-13, and in all three the REFERENCE front was the wrong one** — which is
what the entry suspected and could not yet prove. Verified after a rebuild: reference, F and the v1
interpreter now produce byte-identical output on each file.

* `set-ops-infix.ssc` — `050fb98d2`. `--` had no token in the reference lexer at all; the two minus
  signs lexed as unary minus applied twice, so the expression quietly evaluated to something else.
  Lexer and lowering had to land together: with only the first half the token reaches the runtime as
  `unbound global: --`, which is worse than the silent no-op it replaces.
* `tkv2-hstack-wrap.ssc` — `85d305116`. A named argument cost a trailing-vararg call every child but
  one. The front pre-fills the first clause's defaults as POSITIONALS, and the one-positional-per-param
  walk then handed the vararg slot a default instead of the real arguments.
* `multiblock-auto-output.ssc` — `374f6ce01`. Each code block's tail must print; the reference front
  printed only the whole program's final value. The block boundary survives the fence join only as
  the `__sscBlockEnd__` sentinel, which this front never emitted.

Gates: `tests/e2e/ref-front-three-defects-gate.sh` (operators and the vararg shapes) and
`tests/e2e/ref-front-multiblock-gate.sh`, both verified in BOTH directions and registered in CI.

## shipped-F-and-F-bootstrapped-from-source-disagree — RETRACTED, it was my stale kernel

<!-- status: wontfix
     lane: apparatus
     area: front
     reported-by: claude-code
     reported-at: 2026-08-11
     confirmed: no
     gate: tests/e2e/f-char-escape-gate.sh -->

**This entry was WRONG and is retracted the day it was filed.** It claimed the F inside `bin/ssc`
and the F bootstrapped from `specs/v2.2-p6.5-fsub.ssc` are different programs, on the evidence that
F0 lowered `'\\'.toInt` to `(prim char (lit (int 92)))` while `bin/ssc` answered 0.

**F0 never produced that IR.** The measurement ran through a kernel jar built days earlier from an
older `v2/src`. Rebuilt from current sources, F0 does not lower that program at all — it emits
`(lit (int '\'))` and the runtime refuses it: `int literal: not a canonical INT`. The two Fs were
never disagreeing; they were both wrong, in the one way this entry did not consider, and my
instrument was stale in the other.

**Measured properly, they AGREE.** Fifteen self-contained probes covering every construct touched
this week — arm-body sequences, an inline `val` in an arm, curried defaults, an omitted clause,
varargs, an extern vararg, `++` chains over lists and strings, placeholders, `${` literals,
interpolation, a sibling call — **14 of 15 identical**, and the fifteenth is the char-escape bug
below, present in both. So the week's verifications stand.

**What it cost and what it bought.** An afternoon of alarm about the soundness of every measurement,
and one real defect that the alarm led to. The rule it earns: **an instrument built from sources has
a version, and a jar in `/tmp` from two days ago is a different compiler.** `install.sh` has a
staleness guard for exactly this and my hand-built kernel had none.

## f-char-literal-escape-without-a-named-branch-emits-the-character-not-its-code

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-11
     confirmed: yes
     fixed-in: 7dd1c17a7
     fixed-in: 39d99eed7
     gate: tests/e2e/f-char-escape-gate.sh -->

`escCharCode` maps `\n`, `\t`, `\r` and `\0` to Int literals and fell through returning `e`
unconverted — but `e` comes from `charAt`, so it is a **Char**. The two escapes with no named branch,
`'\\'` and `'\''`, reached emitInt as characters:

```
'\\'.toInt              F   0    reference  92
"a\\b".indexOf('\\')    F  -1    reference   1
'\''.toInt              F   0    reference  39
```

The emitted literal is `(lit (int '\'))`, which is not an integer at all. Through F0 the runtime
says so — `int literal: not a canonical INT (0|-?[1-9][0-9]*)` — and through `bin/ssc` it is silent
and answers 0. It survived because **every escape anyone had tested has a named branch**; the two
that do not are exactly the two that were wrong. The non-escape path one line above already wrote
`.toInt` explicitly.

Fixed: the fall-through returns `e.toInt`. The four named escapes are controls in the gate — they
were always right, and they are what a "call .toInt everywhere" change would break if the branches
went instead of the fall-through.

Found by sweeping `tests/conformance` with the output-agreement comparison, which the gate excludes
for runtime (`f-worse-than-the-reference-on-five-conformance-files`). One of the five is now closed.
## f-worse-than-the-reference-on-five-conformance-files

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-11
     confirmed: yes
     fixed-in: 41ae217cd
     gate: tests/e2e/f-output-agreement-gate.sh -->

### CLOSED 2026-08-14 — both of this entry's claims, and only one of them was about F

The entry makes two claims and they were repaired separately, so both are stated separately here.

**The damage: all five rows agree now, and only ONE of them was F's fault.** Re-measured on the
current build, F against the reference on each file:

```
char-literal-escapes          1|1|1                       AGREE   F was wrong   7dd1c17a7
set-ops-infix                 Set(1,2,3,4)|Set(1,3)|Set(2) AGREE   reference     050fb98d2
tkv2-hstack-wrap              8:2:false|default-built|…    AGREE   reference     85d305116
multiblock-auto-output        2|20|explicit               AGREE   reference     374f6ce01
extension-call-in-a-def-body  body-a                      AGREE   BOTH fronts   0428861ff + 41ae217cd
```

Four of the five rows were counted against F by an instrument that treats the reference front as an
oracle; three of those were reference-front defects with F in the right, and the fifth was both
fronts failing the same way for the same reason. The title's "F worse on five" was true of one file.

**The cause: the exclusion this entry was written to protest is gone.** "The gate is NOT widened
here" below was overtaken the very next day — `tests/conformance` has been in the subject list since
2026-08-12 (`f-output-agreement-gate.sh:69`), which is how the last of the five rows was charged to
F at all: the file only became a ceiling row once the corpus included it AND the reference lane
became right about it. The 45s cap and `-P 8` are what made widening affordable; the timeouts the
paragraph below feared are real and are absorbed by the TIMEOUT bucket, which can only remove rows
from the ceiling.

`tests/e2e/f-output-agreement-gate.sh` deliberately excludes `tests/conformance/` — 398 files —
for runtime. This is the check on that decision, and it says the exclusion was hiding things: the
gate's 240-file `examples/` set shows F worse than the reference on **1** file; the excluded set
shows **5**.

```
398 files: AGREE 213 · DECLINED 97 · TIMEOUT 73 (load, not judged) · BOTHFAIL 8 · WORSE 5 · DISAGREE 2

  char-literal-escapes.ssc          '\\'.toInt is 0 under F and 92 under the reference, so
                                    "a\\b".indexOf('\\') is -1 instead of 1. '\n' and '\t' are fine.
                                    See shipped-F-and-F-bootstrapped-from-source-disagree above:
                                    F0 lowers this CORRECTLY, so the two Fs differ here.
  extension-call-in-a-def-body.ssc  F: `arity: 1 expected, 0 given`; the reference runs.
                                    SETTLED 2026-08-14: BOTH fronts were wrong, one root cause —
                                    a single-line `extension (r) def m` absorbed every following
                                    `def`. "The reference runs" was measured when it printed
                                    nothing at all and exited 0, which is the reference's own
                                    symptom of the same bug. Both halves fixed.
  multiblock-auto-output.ssc        F prints two EXTRA lines (`2`, `20`) the reference does not —
                                    an auto-output decision, both fronts running.
                                    SETTLED 2026-08-13: F was RIGHT. The two lines are the contract
                                    (each block's tail prints); the REFERENCE was dropping them.
                                    Fixed in 374f6ce01, see ref-front-drops-every-block-tail-but-the-last.
  set-ops-infix.ssc                 not yet examined
                                    SETTLED 2026-08-13: F was RIGHT. `--` had no token in the
                                    reference lexer at all. Fixed, see the `--` entry.
  tkv2-hstack-wrap.ssc              not yet examined
                                    SETTLED 2026-08-13: F was RIGHT. A named argument cost the
                                    reference front all but one vararg child. Fixed, see
                                    ref-front-drops-all-but-one-vararg-when-an-earlier-param-is-named.
```

Three of the five are silent wrong answers rather than declines: the program runs and the answer
differs. That is the class every worthwhile F fix this week came from.

~~**The gate is NOT widened here.**~~ (Overtaken 2026-08-12 — see the closing note above.) 73 of 398 timed out at `-P 4` on a contended host, so folding
conformance in at the current cap would triple the runtime and add a load-dependent bucket to a gate
whose thresholds are frozen. Widening it needs the timeout question answered first — a higher cap,
more parallelism, or a curated subset — and that is a separate decision from recording what the
sweep found.


### 2026-08-11 — TRIAGED, and the headline was wrong

Of the five, exactly **one** was an F defect: the char-escape bug, fixed in `7dd1c17a7`.
**Three are REFERENCE-front defects with F in the right** — checked against the v1 interpreter as an
independent third lane, which agrees with F on all three:
`reference-front-answers-three-conformance-files-differently-from-both-other-lanes`.
The fifth, `extension-call-in-a-def-body`, is wrong on BOTH fronts and already carries a
`known-red:` marker with ten probes behind it — the interpreter is the only lane that answers.
SETTLED 2026-08-14: **both fronts were wrong for the same reason and both are fixed.** A single-line
`extension (r) def m` opened no member block, so the receiver stayed live and absorbed every `def`
after it — the reference lost `main` (silence, rc 0), F prepended the receiver to it (`arity: 1
expected, 0 given`). Reference half `0428861ff`, F half `layoutCloseX`; the `known-red:` and its
ten-probe table are gone from the case, which is green on every backend. See
`v2/BUGS.md v2-extension-member-call-inside-a-def-body-fails-by-arity`.

The count in this entry's title came from a comparison that treats the reference front as the
oracle. It is not one; see
`the-agreement-gate-calls-the-reference-front-an-oracle-and-it-is-not-one`. The sweep was still
worth running — it found a real, silent F bug that nothing else had — but four of its five rows
pointed the wrong way.
## a-reduction-predicate-naming-an-unbound-name-will-just-delete-its-declaration

<!-- status: fixed
     fixed-in: 2c441b1ce
     lane: apparatus
     area: other
     reported-by: claude-code
     reported-at: 2026-08-10
     confirmed: yes
     gate: .agents/plugins/isolate/commands/isolate.md -->

**Method, not code — and it cost two investigations in one day.**

The natural predicate when chasing an F coverage gap is "front-report says `GAP` with `(global X)`".
It has a trivial solution the reducer finds immediately: **delete the declaration of `X`.** Then the
name really is unbound, the predicate holds, and every further cut is measured against a module that
is broken in a way the original was not.

Measured twice, both today:

- **`(global Parser)`**, `std/parsing/core.ssc`. The reduction converged on three declarations, one
  of them `case class PReadContext(f: Any) extends Parser[Any]` with `sealed trait Parser[A]`
  REMOVED. Rebuilt well-formed — traits restored — the same three declarations lower to **`F`**. The
  artifact reproduced nothing; it was the trivial solution.
- **`(global __u0)`**, `std/ui/content.ssc`. A line-level reduction converged on a file with an empty
  lambda body and an unterminated parameter list. Three refuted hypotheses were read off it before
  anyone noticed.

**The rule, in two parts.** Reduce by DECLARATION, never by line — already written down in this
repository, and violated. And when the predicate names an unbound IDENTIFIER, **pin that
identifier's declaration** so the reducer cannot take it. With `sealed trait Parser[A]` and
`trait ParserContext` pinned, the same reduction stops at twelve declarations instead of three, and
the twelve are all well-formed.

A cheaper check that would have caught both: before trusting a reduced artifact, REBUILD it
well-formed and confirm it still reproduces. It takes one run.

**This is a METHOD record, and its end state is a rule an agent reads before reducing — not a gate
over code.** Named 2026-08-14 so it stops being unclaimable:
`.agents/plugins/isolate/commands/isolate.md` is the skill every reduction in this repo runs from,
and it is where the two rules belong.

**Done when** that skill says both of them: **pin the declaration of any identifier the predicate
names**, so the reducer cannot take the trivial solution of deleting it; and **rebuild the reduced
artifact well-formed and confirm it still reproduces** before trusting it. The second costs one run
and would have caught both instances above.

Deliberately not a gate over code: there is no reducer script in this repository to assert against,
and inventing one so that there is something to test would be the tail wagging the dog. The
measurable claim is the skill's text.

### CLOSED 2026-08-15 — `2c441b1ce` (submodule `547ef08`), and the entry's own aside was the real bug

Both required rules are now in the skill, in a new §3 — reduction is bisection applied to the
INPUT, and the document previously covered bisecting COMPONENTS only, which is why there was
nowhere for them to live. Verbatim, so this is checkable by grep rather than by reading:

| # | the rule, as the entry demanded it | in the skill |
|---|---|---|
| 1 | pin the declaration of any identifier the predicate names | `isolate.md:75` **Pin the declaration of any identifier the predicate names.** |
| 2 | rebuild well-formed and confirm it still reproduces | `isolate.md:84` **Rebuild the reduced artifact well-formed and confirm it still reproduces** |
| 3 | reduce by declaration, never by line (stated above, not in *Done when*) | `isolate.md:69` **Reduce by DECLARATION, never by line.** |

A fourth was added from a third incident, because it is the same failure with a different cause:
**name the structural feature the defect depends on and check the reduction still has it.** On
2026-08-12 a vararg defect whose real callee was *curried* was reduced to a single clause; one
candidate repair looked correct on the reduction and produced three children on the real call,
while the repair that actually works looked like a no-op. The reduced call was not legal Scala,
and an illegal program has no defined right answer — so "the lanes disagree" stopped being
evidence. Rules 3 and 4 are two ways to leave the reduction unable to answer.

**But the premise of this entry was false, and that was the finding.** It says
`isolate.md` "is the skill every reduction in this repo runs from". It was not, because nothing
pointed at it. Measured: `isolate` appeared in **neither** skill index — not
`.agents/plugins/AGENTS.md`, not `.agents/plugins/README.md` — while every other substantive
skill (`bugs`, `policy`, `performance`, `scrumban`, `spec-dev`, `multi-agent`, `multi-repo`,
`rozum`) is in both, and even the one-line `plan-mode-bypass` hook is listed. It was on disk and
in `marketplace.json`; those are not discovery surfaces.

That matters because `AGENTS.md` §"MANDATORY: required skills" does not enumerate skills **by
design** — it sends the reader to `.agents/plugins/AGENTS.md` and states that new skills "appear
in that index automatically — no edit here, no per-skill install". For this skill the promise was
false, and silently: a skill missing from the index is indistinguishable from a skill that does
not exist. So writing the two rules into an unlisted file and closing this entry would have been
write-only — it would have passed every gate and changed nothing an agent does.

It also explains this entry's own aside that "reduce by declaration, never by line" was **already
written down in this repository, and violated**. A repo-wide grep finds that rule written nowhere
else today, so whatever earlier copy existed, the reason it did not take is the reason found here:
it was written where nobody is pointed. The fix therefore has two halves — the rules, and the two
index rows that make them reachable — and only the second half explains the recurrence.

Not made a gate over code, as the entry directed. The checks that were run: `markdownlint` with
this repo's config on all three changed files (rc=0), `marketplace.json` re-parsed as JSON (10
plugins, isolate's description updated to match the new section), the heading sequence confirmed
1-6 after the insertion, and the three rules asserted present by grep as tabulated above.

**Follow-up, deliberately not taken here** (different subject, outside this claim): four skills —
`isolate`, `multi-repo`, `rozum`, `spec-dev` — have no `<skill>/.claude-plugin/plugin.json` while
the other six do. Filed as `four-skills-have-no-plugin-manifest-and-nothing-notices`.

## four-skills-have-no-plugin-manifest-and-nothing-notices

<!-- status: open
     lane: apparatus
     area: other
     reported-by: claude-code
     reported-at: 2026-08-15
     confirmed: no
     gate: none -->

Found while closing `a-reduction-predicate-naming-an-unbound-name-will-just-delete-its-declaration`,
and deliberately left open rather than fixed blind, because I have measured an inconsistency and
NOT that it breaks anything.

In the `.agents/plugins` submodule, `.claude-plugin/marketplace.json` lists ten plugins. Six carry a
`<skill>/.claude-plugin/plugin.json`; four do not:

| has `plugin.json` | `bugs`, `multi-agent`, `performance`, `plan-mode-bypass`, `policy`, `scrumban` |
|---|---|
| **missing** | **`isolate`, `multi-repo`, `rozum`, `spec-dev`** |

All ten are named in the marketplace with a `source` pointing at their directory. So either the
manifest is optional and six of them are carrying a file nobody reads, or it is required and four
marketplace entries resolve to a plugin directory without one — and *which* of those is true is
exactly what has never been checked. Nothing in this repo asserts either way, which is why a 6/4
split could sit there unnoticed.

This does not affect the agent-independent path at all: skills are read as plain markdown via
`.agents/plugins/AGENTS.md`, and that index is now correct for all ten. The manifest only matters
for the optional Claude Code marketplace layer.

**Acceptance test.** Install the marketplace and ask it to resolve each of the ten plugins by name.
If the four without a manifest fail to resolve or are silently skipped, add the four manifests and
freeze the count with a gate that fails when `marketplace.json` names a plugin whose directory has
no `plugin.json`. If all ten resolve, the manifest is decorative — then delete the six or record in
the submodule's README that it is optional, and still freeze whichever rule was chosen. Either way
the outcome is a stated rule plus something that notices when it is broken; "add four files so the
column looks even" without running that probe would be a guess.

## f-parser-gap-reduced-but-not-solved

<!-- status: duplicate
     duplicate-of: f-parser-gap-needs-the-package-field
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-10
     confirmed: yes
     gate: none -->

`(global Parser)` is F's decline reason on 4 corpus GAP files — `examples/dsl-calc-parser.ssc`,
`dsl-json-parser`, `dsl-sql-recovery`, `dsl-yaml-like` — the largest single cause left in the GAP
tail. Asking each module of the import closure for its own verdict puts the origin at
`std/parsing/core.ssc`, which is `GAP` on its own, is **self-contained** (no imports) and is 118
lines. `combinators`, `helpers` and `recovery` inherit it; `std/dsl/pretty.ssc` is `F`.

**NOT SOLVED.** Banked, so the next attempt starts here:

With the trait declarations PINNED (see the entry above — without that the reduction just deletes
`sealed trait Parser[A]`), the module reduces to **twelve** declarations, all well-formed: the three
traits, seven case classes, `case object NoContext`, and `object Parser` carrying **all seven** of
its methods. **No single method is removable** — which is what makes the shape interesting, since
the methods are near-identical one-liners.

**Refuted by measurement, all lowering correctly under F:** a companion object sharing its name with
a trait, generic and non-generic; a companion under a different name; no companion at all; an
`extension` on the trait; and an object with 3, 5, 6, 7 or 8 near-identical methods, in case the
count was a threshold.

The next thing to try is not another guess about the shape: it is to reduce WITHIN the surviving
twelve — the case-class bodies and the method bodies — with the traits pinned throughout.

**Superseded 2026-08-13 by [[f-parser-gap-needs-the-package-field]]**, which carries strictly more:
the same files re-measured after three front fixes landed the same week, a 91-line well-formed
reduction at a declaration fixpoint, seven shapes ruled out by measurement, and the first necessary
condition found — the `package:` field in the module frontmatter. Kept rather than deleted because
its account of how the reduction predicate deletes the very declaration it names is the reason the
newer attempt pinned the trait.

## orphan-fixedin-lands-red — a rewritten `fixed-in:` turns SMOKE red for everyone, three times in two days

<!-- status: fixed
     lane: apparatus
     area: build
     gate: .githooks/pre-push
     fixed-in: c4b13d4f6 -->

`tests/e2e/bugs-index-gate.sh` refuses a `fixed-in:` sha that is not an ancestor of HEAD, and it is
right to — such a sha is invisible in a fresh clone. But it runs in SMOKE, **after** the push, so the
first people to learn are everyone else. Three entries did it on 2026-08-09/10:
`rust-backend-two-tests-red-on-origin-main-after-the-Any-boundary-work`,
`lower-has-six-hand-written-Expr-walkers-and-nothing-checks-they-agree`, and
`smoke-suite-over-its-own-budget`. Each turned the suite red repo-wide until its owner noticed.

**The cause is structural rather than careless, and that is why the rule that existed did not help.**
The sha goes into the entry in the SAME commit as the fix; the rebase-and-push loop then rewrites
that commit. The value was true when it was typed. What survives is closing the entry in a SECOND
commit, after the fix has landed — which is what the refusal now tells you to do, because a guard
that only refuses teaches `--no-verify`.

Checked in `.githooks/pre-push`, on the `+` side of the diff only, so a pre-existing orphan belongs
to another entry and cannot block unrelated work.

**Two defects of my own in the guard, both found by controls rather than by reading it.** The first
version sat below `[ -n "$incoming" ] || exit 0` and therefore ran only when the push also added a
claim file — *a guard downstream of an early exit is a guard that does not run*, and its first
"it refuses" test passed only because that branch happened to carry a claim-update commit. The
second was a FALSE refusal, the worse direction: from a branch not yet rebased, every sha already on
main looks like an orphan of that branch, so a valid ancestor was refused. It now judges only a push
that could actually land.

Three controls, all observed: orphan → exit 1 naming the sha; valid ancestor → exit 0; no `BUGS.md`
in the push → exit 0.

**This entry is itself the demonstration**: the guard landed in one commit, and the sha above was
read back with `git rev-parse` afterwards and written in this, the second one.

## typed-pattern-against-an-any-does-not-test-the-type

<!-- status: fixed
     fixed-in: 333a7ddc8
     lane: v2-rust
     area: codegen
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**Found while reviewing a colleague's branch, and it is the sharper of the two things that review
turned up.** `Pat.Typed` is dropped everywhere in the Rust walker, with a comment explaining why:
the ascription is normally the subject's OWN type, so it carries nothing. Against an `Any` it
carries all of it — the arms are discriminating — and dropping it made the FIRST typed arm
irrefutable:

```
def describe(x: Any): String = x match
  case l: List[Any]        => "list:" + l.length
  case m: Map[String, Any] => "map:"  + m.get("k")

describe(Map("k" -> 9))    rust: list:1     bin/ssc: map:Some(9)
```

It compiled and it ran. That is worse than the build error it replaced, and it only became
reachable because I had just widened an argument coercion — so the sequence is worth stating: a fix
that makes more programs COMPILE can move a defect from loud to silent, and the way to notice is to
compare lanes rather than to check that the error went away.

Now each typed arm emits a runtime test on the variant (`matches!(v, Value::List(_))`, `ssc_is` for
a case class), and a type with no test I can name is a REFUSAL rather than a bind-all — a bind-all
is exactly how this got here. The binding stays a `Value`, which composes with the dynamic
accessors landed the same day: a body that says `l.length` reaches the contents without this having
to decide how each type is represented to user code.

## map-getorelse-emits-copied-on-a-string

<!-- status: fixed
     fixed-in: 333a7ddc8
     lane: v2-rust
     area: codegen
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

`Map.getOrElse` lowered to `.get(&k).copied().unwrap_or(d)`, and `copied` requires `Copy`, which
`String` does not implement — so `Map[String,String].getOrElse` did not compile at all (E0277).
`.cloned()` accepts everything `.copied()` did and costs the same for a `Copy` type, so there is no
case where the narrower one was right.

A test asserted the old form, which is to say it froze the defect; it now asserts `.cloned()` and
says which bug the previous wording was protecting.

Found while building a probe for something else, where it MASKED the defect being hunted — which is
its own argument for fixing it rather than routing around it.

## jsonparse-null-is-none-on-every-lane

<!-- status: fixed
     fixed-in: a38e3d835
     lane: multi
     area: runtime
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**Settled 2026-08-10 by the project owner: JSON `null` is `None`.** It was `None` on `bin/ssc` and
`()` on `--v1` and on the Rust lane — filed while fixing the JSON contract and left open because it
is a language question, not a defect.

```
jsonParse("{\"a\": 1, \"b\": [true, null, 2.5]}")
bin/ssc  Map(a -> 1, b -> List(true, None, 2.5))
--v1     Map(a -> 1, b -> List(true, None, 2.5))
rust     Map(a -> 1, b -> List(true, None, 2.5))
```

The reason to prefer it over `()`: a program can DO something with `None` — `.getOrElse(default)`,
`case None =>` — whereas `()` is indistinguishable from "a function returned nothing", so a null in
the data and a missing result read the same.

Two lanes changed. `--v1`: `V1JsonCore.toRaw` mapped `JsonCoreNull` to `PluginValue.unit`; its
`isNullish` already accepted both, so nothing internal moved. Rust: `_json_to_value` produces the
`Some`/`None` representation, and `_value_to_json` sends `None` back out as `null` and unwraps
`Some(x)` — so the round trip is closed rather than one-way.

## type-lost-across-a-boundary

<!-- status: fixed
     fixed-in: 8e6545753
     lane: v2-rust
     area: codegen
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-10
     ssc-version: 502b9f181
     repro: repro/type-lost-across-a-boundary.ssc
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

Two shapes, **each paired with a CONTROL in the same file** — and the pairing is what made them
cheap to fix, because it ruled out "inference limit" before I looked at anything:

**1. A declared `List[String]` return was not remembered as a list.** `val direct = ["a","b"];
direct(0)` compiles, so plain indexing is fine; `def rowsOf(s: String): List[String]` … `rows(0)`
emitted a CALL — `expected function, found Vec<String>`. The annotation is in the signature, so
nothing had to be inferred: `collectLocalSeqs` decides whether `xs(i)` is an index or a call, and
its rules covered literals and seq-producing METHODS but never asked what a called def DECLARES.
`_returnTypes` has held that since the `Any` boundary work; this is its first consumer.

The reporter also predicted the shape of the wrong fix, correctly: a patch aimed only at
`zipWithIndex`'s return type (yesterday's entry) would not have covered this one.

**2. `toInt` on a lambda parameter emitted `s as i32`.** `"120".toInt` compiles — control — because
a string LITERAL receiver is recognised. A lambda parameter is not, and the fallback was an `as`
cast: `String as i32` is not a cast Rust has (E0605).

Now a TOTAL helper, `crate::runtime::_to_int`, implemented for `i64`/`f64`/`String`/`&str`, so the
generator can emit it knowing nothing about the receiver — the same trick that made the `Any`
coercions work without inference. A receiver still known to be numeric (a literal, arithmetic over
literals, or a parameter DECLARED `Int`/`Long`/…) keeps the direct `as i32 as i64`: the helper is
correct there too, but the direct form is what the emitted crate reads like, and a golden says so.
The i64 arm truncates through i32 and widens back, so the helper cannot quietly mean something
different from the cast it replaces.

Verified against the default lane, all four lines: `control-index = a`, `declared-index = x`,
`control-toInt = 121`, `lambda-toInt = 7`.

**On their framing** — "a type the walker WAS TOLD, lost at a boundary" — they suggested a refusal
for "I am emitting an index/cast for a receiver whose type I no longer know". For (1) recording the
type is strictly better than refusing, and for (2) so is a total helper: both keep the program
working instead of stopping it. The refusal stays where it belongs — a method with no lowering at
all.

## trailing-main-call-runs-the-program-twice

<!-- status: fixed
     fixed-in: 0e9eb28db
     gate: tests/e2e/entry-auto-invoke-once.sh
     lane: multi
     area: runtime
     confirmed: yes
     gate: none -->

**Settled 2026-08-10 by the project owner: the entry is auto-invoked ONLY when the program does not
call it itself, and N explicit calls mean N runs.** All three lanes now agree:

```
explicit main() calls    0     1     2
bin/ssc (F)              1     1     2
--bytecode               1     1     2
--v1                     1     1     2
```

The third column is the one that keeps the fix honest: suppressing every explicit call would satisfy
the complaint and quietly break a program that means to run `main` twice. The gate asserts all
three.

Implemented in the F front, where `mainItem` appended `(app (global main))` unconditionally whenever
the token stream declared `def main`. It now asks whether the ALREADY-EMITTED doc entry contains
that same call — a top-level `main()` lowers to exactly it — so the test is on what was emitted
rather than re-derived from tokens. `nItems3` counts what `mainItem` appends and had to move with
it.

Original finding follows.

A file that ends with a bare `main()` call runs its body TWICE on two lanes and once on the third.
Isolated against a control in the same shape:

```
def main(): Unit =
  println("once")
main()                        ← the trailing call

bin/ssc run            once | once
ssc-tools run --bytecode      once | once
ssc-tools run --v1     once

and with the trailing call REMOVED, every lane prints it once.
```

`ssc run` invokes `def main()` as the entry point, so an explicit top-level `main()` is a second
call — literally correct on the native and bytecode lanes, and the interpreter suppresses one.

**Why it is filed rather than fixed by me:** the two readings are both defensible — "the entry point
is auto-invoked, so your call is a second one" against "an explicit call means the program says how
to start itself" — and picking one silently changes what every existing script does. It needs a
decision, not a patch.

**It is not academic.** Every repro in `repro/` that a user has sent us ends with `main()`, and each
time I have compared lanes I have had to mentally discount a doubled block; I watched it three times
across two days before writing it down, which is exactly how a real divergence becomes background
noise. Whichever way it is settled, the lanes should agree.

Recommendation, if it helps: the interpreter's reading. A script that calls `main()` and gets two
runs is the surprising one, and users are writing that file shape today.

## zipwithindex-result-is-not-indexable

<!-- status: fixed
     fixed-in: c825079d0
     lane: v2-rust
     area: codegen
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-09
     ssc-version: 4a93c440c
     repro: repro/zipwithindex-result-is-not-indexable.ssc
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**The reporter's control is what made this quick, and it is worth copying.** In the same five-line
file: a LITERAL list of tuples indexes fine (`lit(0)._1`), plain `Vec<String>` indexes fine, only
the `zipWithIndex` RESULT does not. That says the lowering landed a day earlier is correct — the
`(element, index)` order, `._1`/`._2`, all of it — and what is missing is the *is-a-list fact* about
the local it was bound to.

`collectLocalSeqs` records which locals hold an indexable seq, so `xs(i)` lowers to `xs[i as usize]`
rather than a call. Its `SeqMethods` set knew `toList`, `split`, `toArray`… and not `zipWithIndex`,
so `zwi(0)` lowered to a CALL: `expected function, found Vec<(String, i64)>`.

Added `zipWithIndex`, and `sorted`/`reverse`/`distinct` beside it — same property, same bug waiting.
The gate keeps the reporter's control: literal, `zipWithIndex` result and `sorted` result, all
compared against `bin/ssc`.

## map-get-lowers-to-an-owned-key

<!-- status: fixed
     fixed-in: 3c7b2ff27
     lane: v2-rust
     area: codegen
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-09
     ssc-version: ba40b376a
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

Already fixed when it was filed, by `3c7b2ff27` — the report is against `ba40b376a`, which is the
CLAIM commit for that work, so it predates the arm by hours. `m.get(k)` lowers to
`q.get(&k).cloned()`: the `&` is the borrow they identified, and `.cloned()` is the other half they
named, `Option<&String>` → `Option<String>`, which is why it now prints as well as compiles.

Re-measured rather than assumed: their repro builds and prints `get = Some(1)` / `size = 2`, the
same as `bin/ssc`.

Their wider point stands and was worth filing: on a real 184-line file, 15 of 17 mismatches were
this ONE shape, not a long tail of sites. That is the argument for fixing shapes, and it is the
second time this week it has paid.

## build-rust-std-imports-unlowerable

<!-- status: fixed
     fixed-in: 3c7b2ff27
     lane: v2-rust
     area: codegen
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-08
     ssc-version: 7eecad50a
     repro: repro/std-imports-lower-and-run.ssc
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**Closed on the reporter's own answer:** they re-measured on a current toolchain and it does not
reproduce — 0 errors, 0 `::`/Cons/Nil occurrences, a 446912-byte binary — and said so rather than
leaving the question open. Confirmed here independently: the repro builds and prints `registered
true`, matching `bin/ssc`.

Neither of us can name the commit that fixed it; the report predates a fortnight of rust-lane work
(`::`/Cons/Nil lowering, reachability, the intrinsic contract). `fixed-in` points at the last of
those rather than claiming a cause — the honest statement is "does not reproduce", which is what
both measurements say.

## list-methods-pass-through-to-rust

<!-- status: fixed
     fixed-in: fb9ac0923
     lane: v2-rust
     area: codegen
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-09
     ssc-version: 041a05328
     repro: repro/list-methods-pass-through-to-rust.ssc
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

The reporter's framing is the important part: **"the same shape as the json externs you just fixed
and as `list-join-stub-serialises` before it — the backend emits a call for something it has no
lowering for, and rustc blames the user's code. Third instance, which is why I am reporting the
SHAPE and not just the three names."** They were right, and the fix has two halves for that reason.

**The three methods lower**, checked against the default lane rather than against expected text —
`indexOf = 1`, `find = Some(b)`, `zipWithIndex = 3`, and `-1` for a miss:

- `indexOf` → `iter().position(...)` as an `i64`, **-1 when absent**: Scala's contract, not Rust's
  `Option<usize>`.
- `find` → `iter().cloned().find(...)`, parameter rebound by clone — `Iterator::find` hands the
  predicate a REFERENCE, so a body written `s == "b"` is otherwise `&String == String`.
- `zipWithIndex` → `enumerate()` with the pair FLIPPED: Scala gives (element, index), Rust gives
  (index, element). Only a differential catches that.

Printing an `Option` came with it: Rust's `Option` implements no `Display`, so `"find = " +
xs.find(p)` did not compile at all. It renders as Scala prints it now — on the interpolation path
AND on the `+` path, which is a separate site; fixing only the first is why the first attempt looked
complete and was not.

**The SHAPE half.** A method with no lowering, on a receiver the walker KNOWS is a `List` or a
`String`, is a refusal that names it:

```
def `main` calls `sliding` on a List and the rust backend has no lowering for it —
the name would be emitted as a Rust method that does not exist
```

Scoped deliberately: the verbatim pass-through is the fallback for EVERY method call in the language
and is CORRECT for a user's own type. So it fires only where the receiver's type is known, and the
gate asserts the other direction too.

Their slice needs more of this class (`Value.get`, `Value.exists`, a `Value: From<Option<Value>>`
bound). Those now announce themselves by name instead of arriving as rustc errors in generated code.

## f-placeholder-u0-reduced-but-not-solved

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-09
     ssc-version: 6b9fc4352
     confirmed: yes
     gate: tests/e2e/f-front-delegation-visible.sh -->

`(global __u0)` is **F's own** decline reason on 11 corpus files — 2 `GAP` and 9 where the reference
declines for its own, different reason (`__yamlSection__`). It originates in one module,
`runtime/std/ui/content.ssc`, which is `GAP` on its own. `__u0` is the name F's placeholder machinery
invents when it renames `_` in a call argument, so the file is being refused over a name the user
never wrote.

**NOT SOLVED. What is banked, so the next attempt starts here rather than at the beginning:**

A **well-formed** minimal reproducer, reached by reducing whole DECLARATIONS and then whole `case`
ARMS — never by line:

    19 defs  -> 5:  contentViewSection, contentViewBlock, contentInlineView (mutually recursive),
                    tableCellStyle, contentComputed
    20 arms  -> 1:  case ContentInline.Expr(source) => textNode("${" + source + "}")

124 lines, still `GAP` with `__u0`. That single arm is load-bearing: with it removed the verdict
changes.

**TEN HYPOTHESES REFUTED BY MEASUREMENT**, all of these lower correctly under F and agree with the
reference: `xs.map(g(_, o))`; two such maps joined by `++`; three operands with a list literal first;
the same through `val` bindings; a `_` wildcard PATTERN in the same arm as a `_` placeholder
argument, and each of those alone; a placeholder as first or second argument; a nested call; and the
`"${" …` literal itself, alone, before a placeholder, after one, and in the same def as one.

**A METHOD FAILURE WORTH MORE THAN THE HYPOTHESES.** The first reduction ran line by line and
converged on a MALFORMED file — `items.map { item => }` with an empty body, an unterminated parameter
list — because the predicate (F says `GAP` with `__u0`) is satisfied by a gutted module, and gutting
is always the smaller change. Three of the ten refuted hypotheses came from reading that garbage.
The rule this repository already had written down is exact: **reduce by declaration, never by line.**
Re-run at declaration and arm granularity, the reduction converged on something real in a quarter of
the checks.

**2026-08-13 — the reduction is now REPRODUCIBLE, and its well-formedness is enforced rather than
eyeballed.** The 124-line file described above was never stored, so every attempt re-derived it.
This one comes from a script, and the script is here:

```bash
W=std/ui/.u0-red.ssc                      # NOT /tmp: the module has relative imports, and a copy
cp std/ui/content.ssc "$W"                # outside its own directory fails to resolve them, which
DECL='^(sealed trait|trait|case class|case object|object|def|extension|val|enum) '
ok() {                                    # makes the ORIGINAL fail the predicate and the run abort
  local l; l=$(./bin/ssc info --front-report "$W" 2>&1 | grep -F "$W	" | head -1)
  [ -n "$l" ] || return 1                 # empty line = bin/ssc not built, NOT a verdict
  [ "$(printf '%s' "$l" | cut -f2)" = "GAP" ] || return 1
  printf '%s' "$l" | cut -f3 | grep -q '__u0' || return 1
  ./bin/ssc run "$W" >/dev/null 2>&1      # ← well-formedness, INSIDE the predicate
}
# then: repeatedly try deleting each declaration (last to first), keep the deletion iff ok()
```

The last line of `ok()` is the point. The method failure recorded above — converging on a gutted
module — cannot be fixed by pinning declarations by name, because you do not know in advance which
ones the reducer will hollow out. Requiring the REFERENCE front to still compile and run the file
expresses "well-formed" directly, and the reducer then cannot cheat.

Result: **327 lines / 24 declarations → 191 lines / 6**, still `GAP` with `__u0`, still runnable on
the reference front. The six survivors:

```
contentViewSection      contentViewBlock       tableCellStyle
contentInlineView       ContentToolkitOptions  contentToolkitOptionsWithComponents
```

Larger than the 124-line figure above because this pass removes whole DECLARATIONS only; the earlier
one also reduced `case` ARMS, which is the obvious next pass and needs the same predicate.

**Do not re-test `"${" + x + "}"` in isolation.** It is in the refuted list above, and I re-ran it
anyway before reading that list to the end — the `${` looked like an interpolation the front might
mis-lex, which is a good hypothesis and a measured-dead one. Read the refuted list before probing.

**Gate named 2026-08-14: `tests/e2e/f-front-delegation-visible.sh`** — the gate that already owns
F's decline behaviour end to end (a file F compiles prints nothing; a user typo delegates silently;
a real gap announces). A per-file verdict row belongs beside those three rather than in a new file.

**Done when** `ssc info --front-report runtime/std/ui/content.ssc` answers **F** rather than
`GAP … (global __u0)`, asserted there, with the 124-line reproducer above kept as the fixture so the
row stays cheap. That reproducer is already well-formed and was reduced by DECLARATION and by `case`
ARM — see `a-reduction-predicate-naming-an-unbound-name-will-just-delete-its-declaration` for why
that matters — so whoever takes this starts from it rather than from the module.

### 2026-08-15 — WHERE THE CODE IS, and it is not where a grep puts you

Static reading only, no build, so none of this depends on host load. Reproduced first on the
installed launcher (stale, built from `9dd11248a`) to confirm the entry is still live:

```bash
SSC_NO_BUILD_CHECK=1 ./bin/ssc info --front-report <worktree>/std/ui/content.ssc
# …/std/ui/content.ssc	GAP	unbound global: (global __u0) is neither a top-level def nor an @-cell
```

**F is `specs/v2.2-p6.5-fsub.ssc`** — staged as `tower/bin/fsub.ssc`, run through
`tower/bin/ssc1-run-fsub.ssc0`, selected by `frontIsF` in
`v1/tools/cli/src/main/scala/scalascript/cli/RunNativeV2.scala` (`nativeFrontLayout`, ~line 900).
Its placeholder machinery is **token-level**, lines 1421-1478: `parseArgExpr` → `phArgWrap` →
`wrapPh`, with `phScanArg`/`phScan` counting and `renameArgPh`/`renamePhD` renaming.

**`v3/src/Lower.scala` IS NOT F, and it is where the obvious search lands you.** It declares
`package ssc3`; the CLI calls `_root_.ssc.Lower`. It is `ssc3`'s own front, AST-level, with a
completely different implementation of the same rule (`hasPh`/`replacePh`/`wrapPhArg`/
`expandPlaceholders`, ~1974-2034). A repo grep for `__u` restricted to `v2/ v3/` finds ONLY that
file — `specs/` is not in the path — so the natural first move puts you in the wrong front. Two
things confirm it is not the lane under test: `v3/ssc3 ir std/ui/content.ssc` does not even reach
lowering (`std/ui/primitives.ssc:74:26: expected an expression, found =` — its parser has no
`opaque type`), and its two walks are symmetric anyway (see below). **Ask which front compiles the
file before reading any front.**

**Three directions closed by reading, so nobody re-opens them:**

1. **`v3/src/Lower.scala` is irrelevant** — wrong front, above.
2. **In that file, `hasPh` and `replacePh` cover exactly the same node set** (`Name`, `Bin`, `Neg`,
   `Not`, `Call`→args only, `MethodCall`→receiver+args, `Apply`→fn+args). "The detector and the
   rewriter drifted apart" is refuted there. It also means `__u0` cannot be born unbound in that
   front: `wrapPhArg` builds its `Lambda` binder from the same `n` it just counted.
3. **The verdict path is not the bug.** `GAP` is decided in `RunNativeV2` ~line 273: F's IR is run
   through `validateNoReader` (~756-766), which walks a de Bruijn IR and reports
   `(global X)` for any name that resolved to a global and matches no def. The reference front then
   compiles the same file, which is what turns `BOTH-UNBOUND` into `GAP`. So `__u0` really is
   emitted as a GLOBAL by F — the name was not in the local environment at emit time.

**Where to look next, and the one asymmetry found so far.** `wrapPh` does two walks over what must
be the same token region:

```
def wrapPh(ts, n, env, cx) = parseExpr(renameArgPh(ts), 0, pushU(0, n, env), cx) …"(lam " ++ n ++ …
```

`renameArgPh` renames each `_` to `__u<k>`, k left-to-right; `pushU(0, n, env)` binds exactly
`__u0..__u(n-1)`; the emitted lambda is `(lam n …)`. **Any token region the renamer covers but the
counter does not produces a `__u<k>` with no binder — which is exactly this symptom.** The two
walks share `isArgEnd`, `isOpenB`, `isCloseB`, `isWild` and agree on all of them except one:

- `phScanTok` treats **token 28** as `depth + 1` (and sets `binder`).
- `renamePhTok` has **no case for token 28**, so depth is unchanged there — while both treat
  **token 29** as a closer through `isCloseB` (which lists 22, 51 and 29; `isOpenB` lists only 21
  and 50, so 29's opener is handled nowhere but in `phScanTok`).

A renamer whose depth is one low after a `28 … 29` region stops at the wrong `isArgEnd` and can run
past this argument, renaming a `_` that belongs to a later one — outside the lambda it emits.
**That is a candidate, not a finding, and it looks MASKED:** token 28 also sets `binder = true`, and
`phArgWrap` bails to plain `parseExpr` whenever `binder`, so `wrapPh` should never run on a region
containing a 28. Confirm or kill the mask before building on it — it is the only structural
asymmetry between the two walks, so if the mask holds, the divergence is in the region's EXTENT
rather than its depth arithmetic, and the next probe is to print what `phScanArg` counts versus
what `renameArgPh` rewrites for the failing argument.

**Not claimed further:** `specs/v2.2-p6.5-fsub.ssc` is held by `f-cons2-no-arm`, so the fix belongs
to whoever holds F. This section is the handoff.

### 2026-08-15 — a 24-line SELF-CONTAINED reproducer, five ingredients eliminated, and a correction to this entry's own method

**The reproducer is now self-contained and a quarter of the size.** 327 lines with relative imports
became 24 non-blank lines needing nothing but three `extern def` stubs, so it can live in a gate's
sandbox instead of inside `std/ui/`. Reduced mechanically — declarations, then `case` arms, then
lines — and verified after every stage:

```scalascript
extern def element(tag: String, attrs: Map[String, Any], style: Map[String, Any], kids: List[Any]): Any
extern def textNode(s: String): Any
extern def fragment(kids: List[Any]): Any

def contentViewSection(section: SectionContent,
                       options: ContentRenderOptions = ContentRenderOptions()): View =
  val tag = if section.level >= 1 && section.level <= 6 then s"h${section.level}" else "h2"
  val headingAttrs =
    if options.sectionIdsAsAnchors then Map("id" -> section.id) else Map()
  val heading = element(tag, headingAttrs, Map(), [textNode(section.title)])
  val body = section.blocks.map(contentViewBlock(_, options))
  val children = section.children.map(contentViewSection(_, options))
  fragment([heading] ++ body ++ children)

def contentViewBlock(block: ContentBlock,
                     options: ContentRenderOptions = ContentRenderOptions()): View =
  block match
    case ContentBlock.Paragraph(inlines, _) =>
      element("p", Map(), Map(), inlines.map(contentInlineView(_, options)))

def contentInlineView(inline: ContentInline,
                      options: ContentRenderOptions): View =
  inline match
    case ContentInline.Text(value) =>
      textNode(value)
```

The declaration pass reproduced this entry's banked figures exactly — 191 lines / 6 declarations,
then 124 lines — which is independent evidence that the apparatus is sound before any new claim is
read off it.

**⚠️ THE METHOD CORRECTION, and it invalidates a sentence written above.** This entry prescribes
putting `./bin/ssc run` INSIDE the predicate and says that "requiring the REFERENCE front to still
compile and run the file expresses 'well-formed' directly, and the reducer then cannot cheat."
**It can. `ssc run` exiting 0 is not a well-formedness oracle.** A span-deleting pass converged on a
file whose last line was

    def contentViewBlock(block: ContentBlock,

— an unclosed parameter list with no body — and `ssc run` returned **0**. Worse, `SSC_DUMP_DEFS`
shows the reference front emitting `REF-DEF contentViewBlock` for that truncated header: it does not
merely tolerate the wreck, it manufactures a def from it. That is the same trivial-solution failure
this entry was written about, reached through the predicate the entry offers as the cure.

**A cheap check that does catch it:** bracket balance over the fenced block. Measured on the three
artifacts — the 31-line one `()[]{} = 0,0,0`; the self-contained one `0,0,0`; the corrupted one
`() = +1`. Add it to the predicate beside `ssc run`; it costs no process at all.

**Five more ingredients eliminated, mechanically rather than by probe.** Each was removed by the
reducer while the defect survived, which is stronger than a probe that fails to reproduce it:

| eliminated | how |
|---|---|
| defaulted arguments / high arity | the 13-parameter `ContentToolkitOptions` and `contentToolkitOptionsWithComponents` are gone |
| the `"${"` string literal | gone with the `ContentInline.Expr` arm |
| brace-lambdas `{ (a, b) => … }` | gone with the `Table` arm |
| `s"h${…}"` interpolation | probed separately, lowers to `F` |
| a `_` in a PATTERN | naming it changes nothing; the defect stays |

Also checked: the two fronts emit **identical def-name sets** for the reproducer, 64 and 64, empty
both ways. The divergence is inside a body, not in what gets declared.

**The observation that should drive the next attempt.** The defect MOVES WITH THE TOKEN STREAM, and
that rules out "construct X is mis-parsed". Deleting the `body` line entirely leaves the defect;
replacing that same line's `_` with an explicit `b => …` lambda REMOVES it — and the second edit
lengthens the stream where the first shortens it. Symmetrically, the `children` line reproduces on
its own once `body` is deleted, but not while `body` sits there de-placeholdered. So no single line
is "the" cause; what matters is where an argument ENDS relative to what a walk over it consumes.

That is the same extent question left above, now with a 24-line subject: `wrapPh` calls
`parseExpr(renameArgPh(ts), 0, pushU(0, n, env), cx)` and keeps only `b` inside `(lam n …)`, while
the remainder `r` is parsed by the caller OUTSIDE that lambda. **A `__u0` that the renamer produced
beyond where `parseExpr` stopped therefore lands outside its own binder — which is precisely
`(global __u0)`.** Not confirmed: F is written one expression per `def`, so printing `n` against the
number of tokens actually renamed needs the staged tower edited (it is read at RUNTIME from
`bin/lib/**/tower/bin/fsub.ssc`, so that iterates in seconds without a rebuild — but revert BOTH
staged copies afterwards).

**Nine further hypotheses refuted by probe** and not worth re-running: `"${"` across a declaration
boundary, the same inside a `match` arm, its `"$" + "{"` control, a placeholder in a call made from
a brace-lambda, one inside a brace-lambda plus another after it, defaults beside a placeholder in
both declaration orders, an `s"…"` interpolation beside a placeholder, and its control. All lower to
`F`. Combined with the ten already listed, that is nineteen negative probes: **isolated shapes do
not reproduce this defect, and the next person should reduce rather than guess.**

## jsonparse-returns-a-string-on-rust-and-a-value-everywhere-else

<!-- status: fixed
     fixed-in: 052e00997
     lane: v2-rust
     area: runtime
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-09
     confirmed: yes
     gate: none -->

**Fixed, and re-measured 2026-08-10 rather than remembered — this entry sat `open` after the work
landed, which is the failure it exists to prevent:**

```
jsonParse("[1,2]")     rust: List(1, 2)     bin/ssc: List(1, 2)
```

Found while clearing the last error out of rozum's six-line repro, and it is worse than the error
it was hiding: **the two rust JSON intrinsics implement a different contract from the language.**

```
[jsonParse](std/json.ssc)
val v = jsonParse("[1,2]"); println(v)

build-rust  ->  [1,2]        a String
bin/ssc     ->  List(1, 2)   a List
```

Both compile. Both run. Nothing is reported. A program that then does `v(0)` gets a character on
one lane and an element on the other.

`std/json.ssc` declares `def jsonParse(s: String): Any` and `def jsonStringify(v: Any): String`.
The rust intrinsics (`RustIntrinsics.scala`, `JsonRs`) are `_json_parse(&str) -> String` — parse and
re-emit compact — and `_json_stringify(&str) -> String` — pretty-print. That is a JSON *formatter*,
not the language's parse/render pair. `jsonStringify(Map("a" -> "b"))` does not even typecheck, which
is how this surfaced; `jsonParse` is the dangerous half because it compiles.

**The reference shape, measured on the other lanes** — an object is a `Map`:

```
jsonParse("{\"a\": 1, \"b\": [true, null, 2.5]}")
bin/ssc            Map(a -> 1, b -> List(true, None, 2.5))
ssc-tools --v1     Map(a -> 1, b -> List(true, (), 2.5))
```

(Those two disagree about `null` — `None` against `()` — which is a second, smaller divergence
worth its own look.)

**What it needs:** the emitted `Value` has no `Map` variant, so making the intrinsics faithful means
adding one (`specs/rust-backend.md` §7 lists a `Map` in the DESIGN enum already), then
`_json_parse -> Value` and `_json_stringify(Value) -> String` over it. Bounded, but it is a runtime
type change rather than a codegen patch, so it is filed rather than done in the same pass as the
reachability work that surfaced it.

This is what still stands between rozum and `clients/meeting`: their slice 1 reads a field out of a
parsed JSON file, and on this lane parsing gives them a string.

## two-examples-import-a-sibling-by-a-path-that-cannot-resolve

<!-- status: fixed
     lane: n/a
     area: docs
     reported-by: claude-code
     reported-at: 2026-08-09
     ssc-version: fd8304965
     confirmed: yes
     fixed-in: 6b9fc4352
     gate: none -->

`examples/content-toolkit-transitive/app.ssc` and its `-register` twin import their sibling as

    [studioPreview](content-toolkit-transitive/studio.ssc)

— a path relative to `examples/`, from a file that already lives in that directory. Nothing resolves
it, and **both fronts fail identically**, so this was never a front gap:

    ssc: native frontend import not found: content-toolkit-transitive/studio.ssc from app.ssc

**The files are wrong, not the resolver, and the corpus says so.** Every other multi-file example
imports a sibling as `./b.ssc` or `b.ssc` — `js-transitive-iife`, `js-transitive-iife-4`,
`js-imported-int-div`, `js-transitive-iife-nopkg`, `std-ui`, `_enumxmod`, `site`. These two are the
only files in `examples/` using the directory-prefixed form, and the only ones failing on it. Their
own prose agrees: both describe themselves as importing "a child (studio.ssc)".

Fixed to `./studio.ssc`. Both then move `ERROR` → `BOTH-UNBOUND` — they still do not run, on the
`__u0` cause both fronts share, but the import layer is no longer the reason.

## error-bucket-holds-no-F-gaps

<!-- status: open
     lane: apparatus
     area: front
     reported-by: claude-code
     reported-at: 2026-08-09
     ssc-version: fd8304965
     confirmed: yes
     gate: tests/conformance/run.sh -->

**RE-MEASURED 2026-08-14 on 771 files and CONFIRMED — see `f-gap-census-refresh`.** 20 `ERROR`
files now, and 17 of them are still not F's decision: 12 `native frontend rejected incomplete parse`
(the REFERENCE front cannot parse the file) and 5 `import not found` (the example's own path is
wrong). The remaining three are one `TYPEERR`, one `match`, one `if-then-no-else-after-while`. F's
real denominator on the wider set is 553, of which F handles 496 — **89.7%**, against the 76% this
entry computed in August and the 64% the raw count reads.

A census of the five `ERROR` files in the 140-file front-report sample — the one bucket nobody had
looked at. **None of them is an F lowering gap.**

    2  the example imports a sibling by an unresolvable path — both fronts fail identically,
       and the files were simply wrong (fixed; see the entry above)
    3  the REFERENCE front cannot parse the file at all:
         ssc: native frontend rejected incomplete parse in examples/graph-fullstack.ssc: struct…
       F declines these separately, on its own `(global JsonCodec_derived)` gap, so neither front
       compiles them and the ERROR label reflects whichever failed first.

**What this does to F's denominator, which is the point of censusing a bucket at all.** With the
65 `BOTH-UNBOUND` (already censused, `both-unbound-is-mostly-plugin-intrinsics-not-user-error`) and
these five all outside F's decision:

    140 corpus files − 67 BOTH-UNBOUND − 3 unparseable = 70 where F's lowering decides
    F handles 53 of those 70 — 76%

Against the raw 140 it reads 38%. It has been quoted that way all week, including by me. The
remaining work is the 17 `GAP` files, and that is the whole of it.

**Still open** because the three unparseable files are a real defect in the native frontend's parser,
just not F's: `struct…` truncated in the message above is where the parse stops, and no one has
reduced it. Filed here rather than left in a census note so it has a slug to be found by.

**The census half is DELIVERED and is what this entry was for**: the ERROR bucket holds no F gaps,
and F's real denominator is 70 rather than 140 — 53 of 70, 76%, against the 38% that had been quoted
all week. Nothing further is owed on that.

**Gate named 2026-08-14 for the remainder, which is a different defect than the title:**
`tests/conformance/run.sh`. Three files are rejected by the REFERENCE front's parser
(`ssc: native frontend rejected incomplete parse … struct…`), nobody has reduced them, and that is a
native-frontend parser bug rather than an F one.

**Done when** those three files parse — the corpus runner accepts them — or when the refusal is
shown to be correct and the files are fixed instead, which is the outcome the two sibling ERROR
files already had. Whoever takes it should split the remainder into its own slug: this title will
otherwise keep the entry open forever for a reason it does not describe.

## json-core-emitted-rust-does-not-compile

<!-- status: fixed
     fixed-in: 052e00997
     lane: v2-rust
     area: codegen
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-09
     ssc-version: b652840fa
     repro: repro/json-core-emitted-rust.ssc
     confirmed: yes
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

**Closed 2026-08-10 on a re-measurement, with the residual named rather than waved at.** The
reported program builds and runs — `{"a":"b"}` / `parsed`, matching `bin/ssc`. What remains is a
different, smaller thing and it has its own numbers: a program that calls `json-core`'s defs
DIRECTLY (rather than reaching the `jsonParse`/`jsonStringify` intrinsics) went 41 -> 13 -> **8**
rustc errors, the last step being the borrow class below.

**The borrow class is gone, and it was one rule, exactly as the reporter predicted.** They wrote:
*"15 of 17 mismatches are this one shape… if it is one lowering rule, it is worth knowing before
someone budgets for individual sites."* It was. `"\\u" + hex(a) + hex(b)` lowered its FIRST link to
`format!` and the rest to Rust's `+`, and `String + String` does not compile — `Add<&str> for
String` is the only impl. The walker did not recognise a call to a def DECLARED to return `String`
as a string expression, nor a concat as being one itself; both now come from `_returnTypes`, the
same "a declared type must survive to the emitter" as the `List` return. With the whole chain a
`format!`, no `+` on Strings is emitted at all.

The 8 that remain are NOT that shape: 2 x E0599, 2 x E0004 (non-exhaustive match), 1 x E0282, and 3
without a code. A different tail, for whoever takes it next.

Original report follows.

Six lines — import `std/json`, call `jsonStringify` once and `jsonParse` once — lowered cleanly and
then failed `cargo build` with 155 errors. Two of the reported classes are FIXED, one was already
fixed before the report was written, and the remainder is now a single honest refusal.

**`charAt` / `substring` had no arm at all** (32 + 5 errors). They fell through to the generic
method-call rendering and came out as Rust `String` methods that do not exist. They now route to
`_str_char_at` / `_str_substring` in the runtime, in UTF-16 CODE UNITS, matching the kernel's
`IntV(s.charAt(i.toInt).toLong)`. Indexing `chars()` would agree on ASCII and disagree on every
astral character — the kind of difference that shows up as one wrong emoji in production and in no
test, so the gate probes `"aé漢"`.

**The reported 33×E0223 and 8×E0423 were already gone** when the report was written — they were
measured on a build predating `b652840fa` (case classes: unit structs, and patterns no longer
qualified as enum variants). Re-measured here rather than assumed either way.

**What is left is one refusal, not 131 errors.** `std/json.ssc` declares four `extern def`s —
`__jsonCoreEncodeValue`, `__jsonCoreRawStrict`, `__jsonCoreWrap`, `__jsonCoreWrapStrict` — and an
extern with no `@rust(...)` and no intrinsic rendered to NOTHING while its CALL was still emitted.
Third instance of the same disease in this family: the backend omitting what it cannot provide and
leaving rustc to blame the user. It now refuses by name:

```
`__jsonCoreEncodeValue` is declared `extern` and the rust backend has no implementation for it
(no `@rust(...)`, no intrinsic); called from `jsonStringify`
```

An extern nobody calls is still silent — otherwise the check would break most of std, and the gate
asserts that half too.

**Still blocked, and on the same question as the sibling entry.** Implementing those four externs
means representing json-core's case-class nodes in `Any`, which maps to a CLOSED `Value` enum with
no variant for a user struct. So rozum's slice 1 waits on the `Any` decision recorded in
`build-rust-drops-defs-it-cannot-lower-without-saying-so`, not on more codegen.

Counts on the six-line repro: 155 (as reported) -> 131 (on current main, before this change) -> 4
refusals naming their cause.

## charat-returns-char-on-v1-and-int-everywhere-else

<!-- status: fixed
     fixed-in: f39448c96
     lane: multi
     area: runtime
     confirmed: yes
     gate: none -->

**Settled 2026-08-10 by the project owner: `println(s.charAt(0))` must print the CHARACTER on every
backend, and everything that treats the result as a number must keep working — coerce where needed
rather than convert the world.** All four lanes now agree:

```
val s = "aé"     charAt(0)   charAt(0) == 97   charAt(0) + 1
bin/ssc (F)         a             true              98
--bytecode          a             true              98
--v1                a             true              98
build-rust          a             true              98
```

**The consensus already existed in the kernel** and only `charAt` had not been moved onto it:
`CharV extends IntV`, so a Char IS its code point at all 273 numeric match sites and carries the
character at the few text sites (`v2-char-is-an-int` established that for char LITERALS). The change
is a second primitive, `scharAt`, beside `scodeAt` — NOT a change to `scodeAt`, because that one is
what the compiler's own passes use to build escapes (`escChar(#scodeAt(s, i))`), where the value must
render as a NUMBER.

**Two things this cost, both worth recording:**

1. The active front is F, whose lowering lives in `specs/v2.2-p6.5-fsub.ssc` — not in
   `v2/lib/ssc1-lower.ssc0`, which is the legacy front. Editing the legacy one changed nothing and
   `SSC_FRONT=legacy` printing `a` while the default printed `97` is what showed it.
2. **F is written in ScalaScript and its own lexer calls `charAt`.** Its char-literal token carried
   the raw result, and that token is later FORMATTED INTO TEXT as `(lit (int <value>))` — so with a
   Char there it emitted `(lit (int x))`, which parses as 0: `'x' == 120` went false and `'a' + 1`
   went 1. One `.toInt` at that single site fixes it; the other 79 uses of `charAt` in the front are
   comparisons and arithmetic and are unaffected by construction. `tests/e2e/v2-char-numeric-position.sh`
   caught it, which is exactly the control half it was written to be.

On the Rust lane there is no inheritance, so the property is spelled out: `SscChar` Displays as the
character and carries `PartialEq<i64>`, `PartialOrd<i64>`, `Add`/`Sub` to i64, `From<SscChar> for
i64` and the `SscInt` coercion — every numeric use degrades to the code unit exactly as Scala's Char
does.

Original finding follows.

Found by a DIFFERENTIAL, while gating the Rust backend's new `charAt`: the gate compared the Rust
binary against another lane and they disagreed. Three lanes, one program, `val s = "aé漢"`:

```
bin/ssc run           97    28450    é      ← default lane
ssc-tools run --bytecode   97    28450    é
ssc-tools run --v1     a     漢      é      ← the odd one out
```

`substring` agrees everywhere; `charAt` does not. Two lanes yield a UTF-16 CODE UNIT as an `Int`
(`Runtime.scala`: `case (StrV(s), "charAt", List(IntV(i))) => IntV(s.charAt(i.toInt).toLong)`), the
interpreter yields a `Char`.

**Neither side is obviously wrong, which is why this is filed rather than fixed.** Scala's
`String.charAt` returns `Char`, so the interpreter follows Scala — and `'\' == 92` still works
there through numeric promotion, so code comparing to an Int is not broken by it. But
`std/json-core.ssc` is written against the Int reading: it stores strings as `List[Int]` and
compares `source.charAt(next) != 92` directly, and it PRINTS differently on the two readings.

The Rust backend was implemented to match the majority (Int, code unit) — that keeps it consistent
with the default lane and with what the standard library expects. Whichever way this is settled, it
should be settled once, in the kernel and the interpreter together, not per backend.

Related: `build-rust-drops-defs-it-cannot-lower-without-saying-so` (where the differential came
from).

## f-std-ui-gaps-behind-the-curried-def-fix

<!-- status: open
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: 1a13d17ea
     confirmed: yes
     gate: tests/e2e/f-std-ui-gate.sh -->

### 2026-08-09 — gap 2 of 3 is FIXED, and gap 1 is narrowed but NOT solved

**Gap 2, `element children expected a valid List`: fixed.** The card was never the subject.
`runtime/std/ui/lower.ssc:510` builds one as `headerParts ++ [bodyEl] ++ footerParts`, and F turned
that chain into a TUPLE — `isStrExprCode` read a generic `(prim __arith__ "++" ..)` as evidence of a
String, which is exactly the node F emits when the type was NOT proven. Every second `++` of a chain
became `sconcat`. Gate: `tests/e2e/f-std-ui-gate.sh`. This is a whole class, not one widget: any
chained `++` over lists was affected.

**Gap 1, `unbound global: selectFromView`: narrowed, cause still unknown.** Recording what is now
measured so the next attempt does not repeat it:

- The name is an `extern def` in `std/ui/primitives.ssc`. **Neither front emits a def for it** —
  `SSC_DUMP_DEFS=1` shows 488 defs on F's side and none containing it — so it is bound, or not, by
  the runtime.
- **A standalone probe fails under BOTH fronts**, even copied verbatim from the example, with the
  same imports, the same signal-typed choices list and the same front-matter. Three separate
  attempts. So the asymmetry is not in the `selectFrom` call itself.
- **The reference front needs a SECOND widget in the `vstack` to succeed.** With `selectFrom` alone
  both fronts fail; with any one of `heading`, `textField`, `checkbox` or `select` beside it, the
  reference prints `smoke:ok` and F still fails. It is the arity of the children list that flips the
  reference, not which widget is added.
- A greedy reduction of the 90-line section file converged on nothing, because the first trim left a
  TRAILING COMMA in the `vstack` and broke the predicate before the loop began — visible in its own
  output as `после обрезки дерева: False`, and a reminder that a reduction must assert its base case
  before it starts removing things.

The thread to pull is that widget-count dependence in the REFERENCE front: it is the reference that
behaves oddly here, and F may simply be failing to reproduce an accident.

**Gap 3, `duplicate native UI signal`: untouched.** Still looks shared — the reference front produces
the same message on `examples/control-center-live.ssc`.

Three separate gaps that `examples/frontend/std-ui/smoke-test.ssc` reaches now that F lowers its
curried and vararg calls (`f-multi-parameter-clause-def-is-not-lowered`, fixed in `1a13d17ea`).
**All three are pre-existing** — each measured identical on the pre-fix binary — and none is caused
by that fix. Filed together because they are what stands between that corpus file and `smoke:ok`.

Bisected by widget section on the fixed build. **Four of seven sections now produce `smoke:ok`
under F** — layout, reactive, display, data. The three that do not:

```
inputSection       ssc: unbound global: selectFromView
containersSection  ssc: element children expected a valid List
routingSection     ssc: duplicate native UI signal '__equality_…'
```

**1. `selectFromView` is not an F gap at all.** Reduced to a five-line program calling
`selectFrom`, BOTH fronts fail identically with the same message — it is a runtime binding gap for an
`extern def` declared in `std/ui/primitives.ssc`. What is unexplained, and is the actual question
here, is why the reference front reaches `smoke:ok` on the whole file while F stops: the same call
exists in both programs, so something differs in whether it is REACHED. That is the thread to pull.

**2. `element children expected a valid List`** comes from `card`, measured directly:
`card(text("a"))` fails under F and succeeds under the reference, identically before and after the
vararg fix. `modal(open, title)(body*)` works, `card(body*)` and `cardWithHeader(header)(body*)` do
not — and the distinguishing feature is not the vararg position but the body: the failing two build
`CardNode(List(), body.toList, List())` and the working one `ModalNode(open, title, body.toList)`.
An empty `List()` literal on its own lowers correctly under F (checked), so the fault is further in.

**3. `duplicate native UI signal '__equality_…'`** is the same message the reference front produces
on `examples/control-center-live.ssc`, so it is likely shared rather than F's.

None fixed here on purpose: this claim was curried defs and varargs, all three are independent of it,
and two of them are not even on F's side. Recorded so that the next person does not re-derive the
bisection — the section-level split above is the expensive part, and it is done.

## f-drops-a-trailing-block-argument-without-running-it

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: 071ded545
     confirmed: yes
     fixed-in: 924adc86d
     gate: tests/e2e/f-trailing-block-gate.sh -->

**FIXED in `ce140eb69`, and the block was never the cause.** F was treating a curried `extern def`
as an ordinary curried def and FLATTENING the call onto the total arity, so the native
implementation — which takes one argument and returns the applier — received the thunk as a second
argument and discarded it without complaint:

```
extern def httpClient(url: String)(block: => Unit)

F    (app (global httpClient) "u" (lam 0 …))          flattened
REF  (app (app (global httpClient) "u") (lam 0 …))    nested
```

**The rule turned out to be general:** the oracle does nothing at all to an extern call site — no
clause flattening, no default synthesis, no vararg list. The native implementation owns its calling
convention, and rewriting the call to match a SOURCE signature it never sees is the defect. Externs
are now excluded from both registries that drive those rewrites.

The second of those was a divergence **I had introduced myself** with the vararg work: `va(1, 2)` on
`extern def va(xs: Int*)` had started emitting a Cons list where the oracle passes the arguments
individually. No test caught it and no corpus file had reached it; dumping F's own IR beside the
oracle's did — bootstrapping F0 the way `specs/v2.2-p6.5-corpus.sh` does, which is the instrument
this whole line of work had been missing and which answered in one run what eight hypotheses had not.

Output agreement over the 53 F-verdict corpus files: **F is now observably worse than the reference
on 1 of them, down from 3**; agreement 34→36, disagreements 8→6.

F discards a trailing block argument. The block is never executed and nothing is reported.

```
examples/_bug1b.ssc (and _bug1c.ssc, same shape)

def main(): Unit =
  httpClient("http://example.invalid") {
    println("inside-block")
    httpTimeout(2000)
    httpRetry(2, 500)
  }
  println("after")

F                 after            <- the block never ran
reference front   inside-block …
```

**Pre-existing** — measured identical on the pre-fix binary — and NOT caused by
`f-ordered-match-arm-body-is-not-a-statement-sequence`. Filed because of how it was found, which is
the part worth keeping.

**How it was found: the coverage number does not measure correctness.** After that fix F claimed 53
of the 140 corpus files. Asking a second question of those 53 — not "did F lower it" but "does F
PRINT what the reference front prints" — gives:

```
34  agree, both ran, identical output
 3  both fail identically (environment, not the front)
16  disagree
```

Of the 16: thirteen fail under BOTH fronts with different messages (F hits
`f-multi-parameter-clause-def-is-not-lowered`, the reference hits a TLS or duplicate-signal
blocker), one is the `smoke-test.ssc` regression recorded in that entry, and **two are these — the
only rows where both fronts RUN to completion and disagree on the answer.** Those two are the worst
category on the board: not a decline, not a crash, a different result.

So of 53 files F claims, it is observably worse than the reference on **three**. That ratio belongs
next to the coverage number every time the coverage number is quoted; on its own, 53-of-140 reads
as progress that is 3 files less real than it looks.

## stub-rendered-as-data-reached-an-http-body

<!-- status: fixed
     fixed-in: 3d1d92bbd
     lane: v2-jvm
     area: runtime
     reported-by: rozum / claude-opus-5, meeting room 'scalascript'
     reported-at: 2026-08-08
     ssc-version: 55e868c17
     repro: repro/list-join-stub.ssc
     confirmed: no
     gate: tests/e2e/stub-does-not-serialise.sh -->

Reported as `list-join-stub-serialises`. A missing method rendered as the text `Stub` and flowed on
as a value: in rozum's `.ssc` server it reached an HTTP response body as `{"cell":{Stub}}` — status
200, wrong data, **not one line in the log**.

```
ssc-tools run --v1 lj.ssc   ->  [ERROR] No method 'join' on ListV(...)      loud, correct
ssc-tools run       lj.ssc   ->  map = Stub                                  silent
```

**Cause.** `Runtime.anyStr` — the coercion behind string interpolation and `+`, which is the path
rozum's server took — had an explicit arm rendering the sentinel as `"Stub"`, deliberately, so stubs
would "look the same" in user-visible strings. `Show.show` reached the same result through its
generic `DataV` case.

**Fix: fatal at the OUTPUT boundary, not in dispatch.** `Stub` still exists as the soft landing for
an unknown method — that design is untouched. It is stopped where it would ESCAPE into a string.
Chosen over making dispatch fatal (breaks everything relying on the soft landing) and over
`SSC_FRONT_STRICT=1` (opt-in leaves the default corrupting, and the default is what rozum shipped).

**Second defect, found on the way:** the sentinel carries a breadcrumb `Stub(Tag.method)`, and the
list path blanked it — which is why the repro printed a bare `Stub` rather than naming `join`. Now
preserved, so the error reads:

```
ssc: `Cons.join` was called but does not exist, and the result reached output.
```

`join` does not exist on **either** lane — v1 says so too. The reporter's code was wrong; the defect
is that one lane said nothing.

Full suite 72/72: `anyStr` and `Show` are shared by every lane, and this repository has been burned
before by a targeted gate passing while a shared renderer broke ~28 checks elsewhere.

## f-multi-parameter-clause-def-is-not-lowered

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: d7546f299
     confirmed: yes
     fixed-in: b32c9e663
     gate: tests/e2e/f-curried-def-gate.sh -->

**FIXED in `1a13d17ea`,** and the fix turned out to be two constructs rather than one: the curried
clauses AND varargs, which the four measured forms below had confounded. Varargs were missing
ENTIRELY — `def f(xs: String*)` called `f("a")` answered `<closure>` with no currying involved.
Defaults across clauses, named arguments, an omitted first clause (`vstack()(children)`, which
`examples/frontend/ios-hello` writes) and a partially-supplied one are all in the gate.

The owner's decision on the policy question this entry ends with, 2026-08-08: **F must be able to
lower every construct that is needed** — so the alternative of teaching F to refuse what it cannot
lower was not taken, and is not the plan of record.

`examples/frontend/std-ui/smoke-test.ssc` still does not print `smoke:ok`: it now clears four of its
seven widget sections under F and stops on three unrelated pre-existing gaps, recorded separately in
`f-std-ui-gaps-behind-the-curried-def-fix`.

Front F does not lower a `def` with two parameter clauses. **Pre-existing** — measured identical on
the pre-fix binary — but newly REACHABLE, and that is why it is filed now: it used to be masked by
an unrelated decline, and half of its failure modes are silent.

```
def v(gap: Int = 0)(children: String*): Int = gap + children.toList.length
def main(): Unit = println(v(gap = 24)("a"))

                                             F                        reference
default + varargs, named arg                 ssc: arity: 2 expected   25
varargs, named arg (no default)              24<closure>              25
default, plain second clause                 ssc: arity: 2 expected   25
default + varargs, positional arg            24<closure>              25
```

Two of the four are **silent wrong answers**: F applies the first clause, never applies the second,
and stringifies the resulting closure into the result.

**How it surfaced, and the one regression it caused.** Fixing
`f-ordered-match-arm-body-is-not-a-statement-sequence` (`d7546f299`) flipped
`runtime/std/ui/lower.ssc` from GAP to F. A decline is program-scoped, so before that flip EVERY
program importing that module went to the reference front — which is what had been hiding this.
Of the 15 corpus files that moved GAP→F, 14 fail identically under both fronts (they are demos that
want a server, not `ssc run`), and **one regressed from working to failing**:
`examples/frontend/std-ui/smoke-test.ssc` printed `smoke:ok` and now reports
`ssc: arity: 2 expected, 1 given`, because `std/ui/layout.ssc` declares
`def vstack(gap: Int = 0)(children: TkNode*)`.

Five std modules declare curried defs: `ui/content.ssc`, `ui/reactive.ssc`, `ui/display.ssc`,
`ui/containers.ssc`, `ui/layout.ssc`.

**The question this raises is not "fix the arity error" but a policy one, and it is the owner's:**
should F REFUSE a file whose closure contains a construct it cannot lower, the way
`validateNoReader` already refuses an unbound global? That would restore the fallback for
`smoke-test.ssc`, convert the two silent-wrong-answer rows above into honest declines, and LOWER the
F coverage number — trading a metric for a guarantee. The alternative is implementing multi-clause
lowering in F, which is feature-sized work and overlaps the area of the active
`v2-three-param-clauses` claim on the reference front.

Not decided here on purpose. What is decided: leaving a construct that F answers WRONGLY reachable
without a written record would repeat the mistake this file exists to prevent.

## f-assignment-headed-arm-body-drops-the-rest-and-returns-a-closure

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: d7546f299
     confirmed: yes
     fixed-in: 0ea4a4a83
     gate: tests/e2e/f-assign-arm-body-gate.sh -->

Sibling of `f-ordered-match-arm-body-is-not-a-statement-sequence`, found while probing that fix for
regressions, and **pre-existing** — measured identical before and after it, so it is not fallout.

An arm body that STARTS with an assignment is parsed by `parseAssign`, which takes the assignment
and nothing else, so the rest of the body is dropped:

```
def f(k: String): Int =
  var acc = 0
  k match
    case "a" => acc = 5; acc
    case _   => 0
def main() = println(f("a"))

F (native lane)          <closure>    <- wrong, and not even the right shape
reference front (legacy) 5
v1 interpreter           5
```

Another silent wrong answer, not a decline. It is deliberate as far as it goes — `armBodyExpr`
routes an assign head away from the sequence path, and the comment at :1603 records why: the block
form of a leading assign is `(seq ..)`, not `(let ..)`, so it is a different lowering rather than
one more statement. What is missing is the CONTINUATION: after the `(seq ..)` the remaining
statements still have to be parsed, and today they are discarded.

Not fixed with its sibling on purpose: that claim was about `(global v)`, this needs the seq-form
lowering reasoned through separately, and no corpus file currently hits it. Worth doing before any
corpus file does, because the failure mode is a wrong answer rather than a refusal.

**Fixed in `0ea4a4a83`.** The continuation the entry says is missing is now there, and it is
the BLOCK path's, mirrored rather than re-derived: a simple `x = e` continues as `(seq set rest)`
with the scope unchanged, a compound `x += e` as `(let (set) rest)` pushing an anon slot — the
latter being exactly what `armSeqCont`/`armSeqMore` already do for a bare expression, so the
compound case needed no new code at all.

Changed at BOTH dispatch sites. `armSeqStmt` is the second one, reached by `a; acc = 5; acc`, and
the file warns about it three lines from the edit: leaving it out keeps the defect alive behind a
leading statement while a gate built on the leading form goes green.

Five shapes verified across all three lanes — simple-then-expr, compound-then-expr, two assigns in
a row, a `val` before the assign, and an arm that is ONLY an assignment. The last is the shape that
always worked and is carried in the gate so that a fix appending a sequence unconditionally would
not pass.

## f-ordered-match-arm-body-is-not-a-statement-sequence

<!-- status: fixed
     lane: native
     area: front
     reported-by: claude-code
     reported-at: 2026-08-08
     ssc-version: 59f2145df
     confirmed: yes
     fixed-in: d7546f299
     gate: tests/e2e/f-global-v-gate.sh -->

Front F treated the body of an **ordered-resolver** match arm as a single expression. Two defects
followed from that one gap, and the quieter one is the worse one.

**1. A multi-statement arm body silently returned its FIRST statement.** No decline, no diagnostic,
wrong answer. Measured across three lanes on the same file:

```
def f(k: String): String = k match
  case "a" => "xy".length; 3.toString
  case _   => ""
def main() = println(f("a"))

F (native lane)          2      <- wrong
reference front (legacy) 3
v1 interpreter           3
```

**2. A `val` written inline in an arm body leaked out of the arm.** `parseExpr` consumed no `val`,
so the remaining tokens were read by the TOP-LEVEL item parser as a top-level val. The entry then
emitted `(prim cell.set (global n__cell) …)` while `collectTopVals` — which scans top level only,
correctly — never declared that cell, and F declined the whole file over an unbound global **it had
invented itself**.

**The reported name never named the cause, and differed by initializer.** With a closed initializer
(`val n = 1`) the invented cell surfaced: `unbound global (global n__cell)`. With an initializer
mentioning an enclosing parameter (`val n = _lenOf(v, theme)`) that parameter is unbound at top
level and surfaced *first*: `unbound global (global v)`. One mechanism, two symptoms, neither
naming the construct at fault — and `v` is what the corpus reported, which is why this was filed
against sixteen frontend examples that do not contain the construct at all.

**Where it actually was: one module.** `runtime/std/ui/lower.ssc`, in `_propCss`:

```
case "paddingX" => val n = _lenOf(v, theme); s"padding-left:${n}px;padding-right:${n}px;"
```

Every frontend example imports it, so every one inherited the decline. Asking each module for its
own `--front-report` verdict is what located it; the sixteen consumers were noise.

**Why the first fix did nothing, and the lesson worth keeping.** The obvious repair — teach
`armBodyExpr` about `val` — built clean, changed no behaviour whatsoever, and I nearly read that as
"the mechanism story is wrong". It was not: `armBodyExpr` belongs to `parseCtorMatch`, and **which
resolver a match uses is decided by its FIRST pattern** (`parseMatchArms` :1556). A string literal
routes to `parseGenMatch`, whose ten arm-body sites each called a bare `parseExpr`. So `case "a" =>`
and `case Some(n) =>` are the same construct through two parsers with two separate body handlers.
Every test in the first version of the gate matched on a String and never reached the code I had
edited — a green gate over an untouched path.

**Fix:** the ordered resolver's arm bodies go through `armBodyExpr`, the ctor path's existing body
parser, so the two families agree instead of drifting — `parseGenCtorPlain`, `parseGenInt`,
`parseGenLit`, `parseGenUnit`, `parseGenVarPlain`, `parseGenVarGuard2`, `parseGenWildPlain`,
`parseGenWildGuard2`, `parseTypedWild`, `parseTypedBind`. Guards are deliberately NOT sequences.
A single-expression body is byte-unchanged (`armSeqCont` hits the arm boundary immediately). New
`isArmValHead`/`parseArmVal` handle the val head, shaped after `parseDBlockVal` (:2441), and are
wired into BOTH `armBodyExpr` and `armSeqStmt` — the second is the same construct one statement
later (`a; val n = e; b`), and without it the leak survives behind a leading statement.

The guard was taught a statement, not widened: an arm body naming something undefined is still
refused, and now names it — before the fix the invented `n__cell` masked the real undefined name.

## build-rust-drops-defs-it-cannot-lower-without-saying-so

<!-- status: open
     lane: v2-rust
     area: codegen
     reported-by: rozum (claude-code), meeting room 'scalascript'
     reported-at: 2026-08-08
     ssc-version: bde14b2eb
     confirmed: no
     gate: tests/e2e/build-rust-refuses-loudly.sh -->

Routed from `INBOX.md` `build-rust-std-json-cons`. `build-rust` could not compile any program
touching `std/json`; downstream a production PWA (`clients/meeting`, :8405) has a binary from
2026-06-29 that **cannot be rebuilt**.

**The silent drop is FIXED. The reported program still does not compile, for a different and now
visible reason.** Three findings, in the order they were reached:

**1. The cause was not lowering at all — it was the import.** `[names](path.ssc)` reaches the AST as
a `Content.Import` only when it is a Markdown LINK. Fences have been optional since 2026-07-09, so
in a bare `.ssc` the whole file is code and that identical line stays INSIDE the code block, where
`preprocessInlineImports` turns it into a `// list-import:` marker. Every other lane scans for that
marker; `inlineImportsRust` looked only at `Content.Import`. Measured with a two-line probe that has
nothing to do with json:

```
lib.ssc:   def twice(n: Int): Int = n * 2
bare.ssc:  [twice](lib.ssc)
           def main(): Unit = println(twice(21))

before:  crate contains `pub fn main` only  →  rustc: cannot find function `twice`
after:   crate contains `pub fn twice` + `pub fn main`  →  binary prints 42
```

That also explains finding 2 of the original filing — the missing diagnostics. The defs never
reached `renderDef`, so its refusals never ran. With the import fixed they print again, verbatim as
rozum first saw them.

**2. The list vocabulary now lowers.** `Nil`, `h :: t`, `Cons(h, t)` and unary `!` were four
refusals; a `List` is a `Vec`, so the patterns lower to SLICE patterns with the subject switched to
`.as_slice()` and the binders rebound to owned values (`h.clone()`, `t.to_vec()`) — a slice binds
`&T`/`&[T]` and bodies are written against `T`/`List[T]`. Verified end-to-end through cargo, not
just emitted: `sum(1 :: List(2, 3))` prints `6`.

**3. Case classes were broken on this backend entirely — not just in json.** Two emission
defects, found by following the remaining errors:

- `case class Marker()` (no fields) emitted `pub struct Marker {` / `,` / `}` — a body consisting
  of one comma. That is not Rust at any level. Field-less case classes are UNIT structs
  (`pub struct Marker;`), which is also the shape the rest of the walker already assumed: it
  renders the value as the bare name.
- every standalone case-class PATTERN came out as `Point::Point { x, y }` — an ambiguous
  associated type (E0223). A standalone `case class` is its own struct, not a variant of anything;
  only a real enum variant is qualified. `EnumCtor` now carries `isStruct` rather than comparing
  `enumName == ctor`, because `enum X { case X }` is legal and would read as a struct.

Verified on a two-struct probe independent of json: pre-fix, cargo rejects the emitted crate with
4 errors; post-fix it builds and prints 7. **Any program with a case class could not be compiled by
`build-rust`** — a much wider break than the report that surfaced it.

**4. `Any` now carries a case class — option A, chosen by the project owner and implemented.**

`Any` still maps to `crate::value::Value`; that enum gains one variant, `Obj(name, fields)`, and a
case-class value crossing into an `Any` becomes one. The reason A was first rejected — "reading a
value back binds every field as `Value` while bodies use them as `i64`, and coercing needs types
the walker does not have" — was WRONG, and it is worth writing down why, because the correction is
the whole design:

**the types are declared.** `case class JsonCoreOk(value: Any, next: Int)` says what field 1 is;
`def f(x: Int): Any` says what its parameter and result are. The walker never has to infer what an
expression IS — only what the place it is going to WANTS, and that is always written down.

The second half is making the coercions TOTAL, so even that much knowledge is not needed at the
source end: `Value::from(x)` is the identity when `x` is already a `Value` (std's reflexive `From`),
and `x.ssc_int()` is implemented for `Value` AND for `i64`. So the same emitted call compiles
whichever side of the boundary the expression was on.

Five boundaries, each measured separately:

| boundary | rustc errors on `std/json-core.ssc` |
| --- | --- |
| (start, after the case-class fixes) | 160 |
| `match` on an `Any` → `ssc_is`/`ssc_field` chain | 98 |
| return of an `Any`-returning def | 68 |
| tail positions inside it (`if` branches, match arms) | 56 |
| call arguments | 52 |
| case-class construction | **41** |

The tail-position step is the one worth explaining: `if p then JsonCoreOk(…) else JsonCoreErr(…)`
is two different Rust structs, so the `if` does not typecheck and wrapping the whole body in
`Value::from` never gets a chance. In Scala the expression's type is `Any`; in Rust it has to BECOME
a `Value` in each branch.

Proven end to end, not just emitted — construct into an `Any`, return it through both branches of
an `if`, match it back out, and compare against the DEFAULT lane rather than against expected text:

```
case class Ok(value: Any, next: Int) / case class Err(message: String)
rust:  ok:42 / err:no      bin/ssc:  ok:42 / err:no
```

**Still 41 errors on `std/json-core.ssc`, and they are a long tail rather than a class.** The
largest group is 8 × `expected &str, found String`, which is a borrow question unrelated to `Any`;
the rest are single-site shapes (a `val` bound to two different case classes, `Vec<i64>` against
`Vec<Value>` at a nested position). rozum cannot rebuild `clients/meeting` yet. What changed is
that the remaining work is now a list of individual sites rather than a design question.

## scaffolded-projects-cannot-load-their-build

<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 6979fd870a189a6d71dd92756c0a31e125bbae63
     gate: tests/e2e/scaffold-loads-its-build.sh -->

`ssc new <name> --template app` produces a project that fails on its first command. Reproduced, not
inferred:

```
$ ssc-tools new demo --template app
Created app project: /tmp/scaffold-check/demo
$ cd demo && sbt compile
[error] sbt.librarymanagement.ResolveException: Error downloading
        org.scalascript:sbt-scalascript-interop;sbtVersion=1.0;scalaVersion=2.12
[error]   Not found
```

**Five of six templates** (`app`, `web-app`, `wasm-app`, `lib`, `dsl`) both request the plugin in
`project/plugins.sbt` and call `enablePlugins(ScalascriptInteropPlugin)` in `build.sbt`, so the
dependency is real — removing the line would break the build differently. Only `plugin` is unaffected.

**Three facts that do not line up:**

| | |
| --- | --- |
| templates ask for | `org.scalascript % sbt-scalascript-interop % **0.1.0**` |
| the plugin build produces | `0.1.0-SNAPSHOT` |
| published anywhere | **nothing** — no workflow, no script publishes it |

So aligning the version alone does not fix this: `0.1.0-SNAPSHOT` is equally unresolvable. The
question underneath is whether this plugin is meant to be published at all, which is a product
decision rather than a defect to patch.

**Fixed that way, on Sergiy's decision:** `install.sh` now `publishLocal`s the plugin, and the five
templates name `0.1.0-SNAPSHOT` — the version that build actually produces, instead of a `0.1.0`
nothing ever made. Gated by `tests/e2e/scaffold-loads-its-build.sh`, which scaffolds a project and
runs `sbt update` in it: checking the template FILES would not have caught the original defect,
because the coordinate was well-formed and simply named an artifact that did not exist.

Falsifiable, measured: remove the local publish from `~/.ivy2` and the gate fails with the
resolution error; restore it and it passes.

**Still open, deliberately:** whether this plugin should be published for real. Until it is,
`ssc new` works for people who build ssc from source and not for anyone else.

Related: this is the third site in the same family — `Main.scala`'s emitted coordinate and
`uniml/build.sbt` were the other two, both fixed and now gated by
`tests/e2e/emitted-coordinate-is-published.sh`. That gate deliberately does not cover the plugin,
because until it publishes there is no correct version for it to name.

## smoke-sbt-setup-skipped-on-cache-hit — every cache-HIT smoke run is red, and only those

<!-- status: fixed
     lane: apparatus
     area: build
     gate: .github/workflows/smoke.yml
     fixed-in: fb66987bc -->

`.github/workflows/smoke.yml` gated `Setup sbt` on `steps.toolchain.outputs.cache-hit != 'true'`,
with the reason written next to it: *"sbt is only needed to BUILD. On a toolchain-cache hit it is
pure setup cost for a step that will not run."* Two smoke CHECKS shell out to `sbt` at RUN time —
`tests sbt-plugin-scripted` and `tests scaffold-loads-its-build` — so on a cache hit both failed with
`sbt: command not found`.

**Measured on two runs twenty minutes apart, same cache key:**

| run | cache | `sbt-plugin-scripted` | verdict |
| --- | --- | --- | --- |
| 31251679562 (b86af8375) | `Cache not found for` | passed, 104.8s | 71/72, red on an unrelated check |
| 31252689448 (a3896e549) | `Cache restored from key` | **FAIL in 0.0s** | 70/72 |

**Why it survived.** A push that changes compiler sources changes the launcher digest and therefore
MISSES the cache — verified, not assumed: appending a byte to
`v1/.../interpreter/EvalRuntime.scala` moves `scripts/launcher-input-digest` from `6e4a0a15…` to
`6d88c30f…`. So every run that exercised the build had sbt and was green, and the runs that went red
were docs and bookkeeping pushes, which is the class nobody re-reads. The failure is deterministic,
not flaky; it just selects for the commits least likely to be investigated.

**Same shape as the comment directly below it**, which records the coursier version of this mistake
("NOT cached — only coursier/ivy/sbt were… making it conditional was a real regression that took
three red runs to find"). Both are a setup step gated on *"is this a build?"* when what it feeds is
the SUITE.

**Budget risk, stated rather than discovered later.** On a cache hit the two checks now RUN instead
of failing instantly, which adds roughly 105 s + 25 s to a suite that used 528 s of its 600 s cap on
that run. If the cap starts firing, that is `smoke-suite-over-its-own-budget`, not this — and the
suite reports its own budget line, so it will say so.

## sbt-plugin-fixtures-deleted-by-an-unrelated-commit-and-unrestorable

<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 30113bf08e46c4818d3d443a838770588ab8ca6a
     gate: v1/tools/sbt-plugin scripted -->

The plugin is in no build — not aggregated, not in CI, not in smoke — so its ten `scripted`
scenarios had never been run by anything. Running them: **two red, and a third green for the wrong
reason.**

**In order.**

1. The `.ssc-artifacts/*.scim` fixtures existed and **survived** the `tools/ → v1/tools/` move in
   `b433a41e4`.
2. `1fea89a79` — *`fix(js): record leaderHistory on every accepted claim`* — **deleted all four**.
   A JS change, nothing to do with sbt. Almost certainly a stray `git add -A`.
3. Nothing runs these tests, so it went unnoticed for three weeks.
4. They could not simply be restored: `.gitignore` ignores `.ssc-artifacts/`, and its exception
   still read `!tools/sbt-plugin/…` — anchored at the repo root, so it stopped matching at step 1.
   The rule meant to protect the fixtures had rotted in the very move they survived.

**The symptom.** `sscGenerateFacade` skips with a warning when `sscArtifactDir` is absent:

| scenario | was | why |
| --- | --- | --- |
| `basic` | FAIL | asserts the facade exists; generation was skipped |
| `multi-module` | FAIL | same |
| `identity` | **PASS, blind** | asserts the facade does NOT exist — true, because nothing ran |

`identity` claimed "generation succeeds and emits no files" while observing "generation never
happened". It now also asserts the output DIRECTORY exists — created by the task before it invokes
the binary, which separates "ran, wrote nothing" from "never ran".

**Fix:** the four original fixtures restored from `b433a41e4` — real artifacts (`"magic": "SSCART"`,
`abiVersion 2.0`), not stand-ins that would satisfy the guard without testing the format — and the
ignore exception made path-independent (`!**/src/sbt-test/**/.ssc-artifacts/`).

10/10 green. Falsifiable: remove `basic`'s fixture → rc=1; restore → rc=0.

**Still open, and the reason this was invisible: nothing builds or tests this plugin.** Wiring it
into a suite is the follow-up — `scripts/smoke-ci.ssc` is held by another claim.

# Test harness — bugs

Scope: defects whose FIX goes in `tests/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module tests`, never by
grepping for status.

Newest first.



## both-unbound-is-mostly-plugin-intrinsics-not-user-error — the label blames the program; the names say otherwise

<!-- status: open
     lane: apparatus
     area: front
     gate: tests/e2e/f-front-delegation-visible.sh -->

**RE-MEASURED 2026-08-14 on 771 files (5.5× this sample) and CONFIRMED — see
`f-gap-census-refresh`.** 198 `BOTH-UNBOUND` rows, all carrying the reference front's own objection,
83 distinct names, and the head of the distribution is still plugin intrinsics and synthesised
typeclass instances: `runActors` 21, `__yamlSection__` 15, `sqlSection` 14, `spark` 12, `self` 10,
`scope` 8, `oauth` 7, `route` 6, `awaitClient` 5, plus 10+ `*_derived`. The reading below holds at
scale; the label still blames the program.

`ssc info --front-report` classifies a file `BOTH-UNBOUND` when the REFERENCE front also fails
`validateNoReader`, and the surrounding comment reads that as "the file is broken whichever front
reads it" — the user's own program. On a 140-file sample that is 64 files, the largest single group.

**It reported the wrong front's reason, which is why nobody could check that reading.** The verdict
is decided by the reference front's failure and the row printed `fFailure`, F's reason. Fixed here:
a `BOTH-UNBOUND` row now carries both, F's first and then `||REF: <reason>`.

With the reference front's own objections visible, the 64 break down as:

    10  (global __yamlSection__)      4  (global _println)       3  (global NamedHandler)
     5  (global JsonCodec_derived)    3  (global self)           2  (global VertexCodec_derived)
     4  (global scope)                3  (global route)          2  (global sqlSection)
     4  (global awaitClient)

These are not typos. `__yamlSection__`, `_println`, `sqlSection` and `route` are plugin-provided
intrinsics, and `*_derived` are synthesised typeclass instances — names the RUNTIME binds, in the
same family as the declared `extern def`s that `f-validateNoReader-rejects-plugin-externs` covered
and that `validateNoReader` now accepts.

So the largest group in the front report is mostly NOT user error and NOT F's fault; it is the same
validator blind spot one layer further out. Two consequences: the `BOTH-UNBOUND` label as documented
is misleading, and the corpus coverage picture is better than the numbers suggest, because these
files run correctly — the reference front compiles and executes them, it is only the static
pre-check that objects.

### CENSUS, 2026-08-08 — and it CORRECTS the paragraph above

"These files run correctly" was asserted, not measured. Measured now, by running all 65 of them:

    20 of 65   run cleanly            -> the static pre-check refused them WRONGLY
    45 of 65   do not run

So the claim holds for **less than a third**. The 45 break down as:

    21  unbound global AT RUNTIME     -> the pre-check was RIGHT: nothing binds the name in this lane
    10  unhandled runtime effect      -> needs a handler/plugin; unrelated to the check
     2  native TLS server             -> environment
    12  other, incl. partial output

The runtime-unbound names are the same family the entry above calls "names the RUNTIME binds" —
`graphqlQuery`, `htmlToPdfBase64`, `Cluster_connect`, `RdfCodec_derived`, `_println`,
`contentToolkitBlock`, `_ssc_frontend_name`. For 21 files the runtime does **not** bind them. That
does not make the label "user error" right either; it makes it a missing PLUGIN in this lane. But it
does mean a registry of runtime-bound names, the fix this entry points at, would have accepted
programs that then die anyway — which is worth knowing before building it.

**What the census does settle, and it is the useful part:** none of the 65 is an F LOWERING gap.
Neither front's decision is at issue in any of them, so they do not belong in F's denominator:

    140 corpus files
    -65 BOTH-UNBOUND   not F's decision either way
    = 75 files where F's lowering decides the outcome — 53 F, 17 GAP, 5 ERROR

**F handles 53 of the 75 files that are actually its responsibility — 71%, not the 38% that
53-of-140 suggests.** Quote the 75 denominator, or F's coverage reads as half of what it is.

The classifier that was tried first and thrown away: grepping each unbound NAME for its origin
(declared extern / runner-synthesised / runtime). Names like `s`, `db`, `doc` and `self` match
almost any file, so it answered confidently and wrongly for a third of the list. Running the program
is unambiguous and costs the same.

**ONE MECHANISM WAS AUTHORISED, IMPLEMENTED, MEASURED AND REVERTED — record it so it is not tried
again.** The idea: since `BOTH-UNBOUND` means the REFERENCE front fails the same check, F's program
is "no worse" and should be kept instead of delegating. It is wrong, and cheaply so.

Built it (keep F's rejected program, use it when `userErrorNotGap`), then RAN eight of the affected
files instead of trusting the reasoning:

    3 of 8   ran as before
    5 of 8   died with `unbound global: msg / v / of / s` — names F alone cannot bind

And the decisive pair, same file both ways:

    examples/frontend/components-demo   F: unbound global: s      legacy: runs

So both fronts failing the STATIC check does not make them equal: the reference front's failure is
on a runtime-provided name it then binds at execution, while F's program carries ADDITIONAL unbound
names that are real gaps. "Both fail validation" and "both are equally usable" are different
statements, and this conflated them.

Reverted, nothing landed. What a real fix needs is what the entry above already said — the actual
registry of names the runtime binds, consulted per name, not a same-verdict shortcut. `pluginNativeNames`
exists in the interpreter (`Interpreter.scala:232`) but is `private[interpreter]` and belongs to the
wrong lane; the native side has no equivalent table, and `__yamlSection__` is not a plugin name at
all — the RUNNER synthesises it (`v2/bin/ssc1-run.ssc0:187`). Those three sources would have to be
unified first.

Not fixed here, and the reason is the same one the extern entry recorded: widening the guard to
accept plugin intrinsics trades a loud misclassification for a silent wrong answer if the name is
genuinely absent, so it wants the registry consulted rather than a pattern match on leading
underscores. Owner call, like its predecessor.

**Gate named 2026-08-14: `tests/e2e/f-front-delegation-visible.sh`**, the same gate that owns the
delegation verdict this census is about — and it already carries the anti-case shape this needs, a
user typo that must delegate SILENTLY.

**Done when** a name the runtime actually binds stops counting as `BOTH-UNBOUND`, asserted there,
**with the anti-case that a genuinely absent name still does**. That pairing is the whole difficulty
and the entry already says so: widening the guard trades a loud misclassification for a silent wrong
answer unless the registry is consulted per name.

**The blocker is named and is not this entry's alone to solve:** three sources disagree —
`pluginNativeNames` is `private[interpreter]` and in the wrong lane, the native side has no
equivalent table, and `__yamlSection__` is synthesised by the RUNNER (`v2/bin/ssc1-run.ssc0:187`),
not by any plugin. Unifying those is the work; the gate above is how you know it landed.

## uniml-version-drifted-from-root — the standalone build would publish two versions of one artifact

<!-- status: fixed
     lane: apparatus
     area: build
     gate: uniml/scala/.../UnimlCoordinatesSpec.scala
     fixed-in: f060e9b9fd9f2ac40598fc029fd4bf45e8e72af0 -->

**FIXED 2026-08-06.** `uniml/build.sbt` now carries `0.2.0-SNAPSHOT`, matching the root. Sergiy's
call, which is why it was filed rather than guessed: the bumping commit said the emitted coordinate
deliberately stays at the release, so the version UniML carries was a policy decision and not
something for whoever tripped over the red to pick. Asked, answered, applied.


`cd uniml && sbt -batch test` is RED on `origin/main`, and it is the check that exists for exactly
this:

    version drifted: standalone '0.1.0' vs root '0.2.0-SNAPSHOT'.
    The same sources would publish as two versions of one artifact.

`24581733e` ("build: main to 0.2.0-SNAPSHOT, and the emitted coordinate stays at the release")
bumped `ThisBuild / version` in the ROOT `build.sbt` and left `uniml/build.sbt` at `0.1.0`. UniML is
built standalone by the nightly `UniML — standalone build` job, which runs that exact command, so
this fails the whole job for everyone until it is reconciled.

**Isolated rather than assumed.** Found while landing an unrelated parser fix; stashing that change
and re-running `uniml/testOnly *UnimlCoordinatesSpec` fails identically, so it is inherited and
nothing in the ScalaScript dialect is involved.

**NOT fixed here on purpose, and the one-line fix is the trap.** Setting `uniml/build.sbt` to
`0.2.0-SNAPSHOT` makes the check green in one move — but that commit's own message says the emitted
coordinate deliberately STAYS at the release, so which version UniML should carry is a decision
belonging to the release work, not a guess to be made by whoever trips over the red. Reconcile it
where the version policy lives.

## smoke-check-guards-sized-by-local-time — two checks killed by their own guard, and the runner will not say so

<!-- status: fixed
     lane: apparatus
     area: build
     gate: scripts/smoke-ci.ssc
     fixed-in: c1d29e7cc -->

**FIXED 2026-08-06 — both remaining items.** `freeze-consistency` was closed earlier (`d0df30a89`,
and the diagnosis corrected with it); the two the entry left open because `scripts/smoke-ci.ssc` sat
inside another claim are done now that it is free.

**1. The guard is sized by MEASUREMENT, not by a dev host.** `run-lane-flags-are-flags` spawns
TWELVE JVMs — two launchers × five lane-flag combinations, plus two unknown-flag probes — each a
cold start plus a compile. On CI, across GREEN runs:

```
45d6148b5  51.3s     59dcabf82  51.3s     5a4ea14ab  47.2s     5903776db  54.0s
4d8b5ae7b  60.1s  ->  TIMED OUT against a 60 000 ms guard, main RED
```

so the guard sat at ~85 % of the check's ordinary cost and ordinary runner variance tipped it over.
`60000 -> 180000`, which is what the other multi-spawn checks here use, and it **costs the budget
nothing**: `timeoutMs` is a ceiling, not a cost.

The file's own rule — *"a check that approaches it is a check to look at, never a number to raise"* —
is right, and it was followed rather than waived: the twelve spawns ARE the assertions, and the flag
PAIRING is the point of the gate (the missing `--interpret` was found only because its partner
existed), so there is nothing to cut without dropping coverage. The check is honest; the guard was
sized against a dev host, which is this entry's title.

**2. A killed check now says it was killed.** `-1` was never in the runner's legend (124 / 127 /
137), so a timeout printed a bare `exit code -1` with no stdout and no stderr — three facts that read
as a crash. Exercised rather than assumed, with a one-check suite and a deliberate sleep:

```
FAIL deliberate-timeout-probe          1.0s
       | exit code -1 — TIMED OUT against its 1s guard
```

127 and 137 got the same treatment while the branch was open.

**Note the probe that did NOT work, because it looked like it did.** `ssc info --front-report` on
the modified suite reports `BOTH-UNBOUND: unbound global: readFile` — and reports exactly the same
for the UNMODIFIED file, because `readFile` is a plugin extern a static pre-check cannot see. Without
that control it reads as "my edit broke the file". The real check is running the suite.

Full suite after the change: 69/69 green, 281.2 s of 600 s.

### Original report (superseded 2026-08-06)

Main has been red since 6f8e3a98f (runs 31060534903, 31060671890 — the same two failures on both,
so the second commit inherited them). `66/68 green`, and the two reds are not logic failures:

    check                      local   CI      guard        what CI shows
    freeze-consistency           2.0s   94.5s  120000 ms    exit 1, 4 stdout lines, NO stderr
    run-lane-flags-are-flags    24.0s   60.1s   60000 ms    exit -1, no stdout, no stderr

`run-lane-flags-are-flags` is unambiguous: 60.1 s measured against a 60 000 ms guard is the guard
firing, and `exit -1` is what the runner reports for it — a value its own legend does not cover
("124 is the timeout guard firing, 127 a command that could not start, 137 a kill").

**CORRECTION, after fixing it (d0df30a89): `freeze-consistency` was NOT a guard-sizing failure.**
It exited 1 at 94.5 s against a 120 000 ms guard — inside its budget. The cause was `set -euo
pipefail` plus an assignment ending in `grep -v '^$'`, which exits 1 when nothing survives the
filter. `set -e` then killed the gate at that line, and the statement immediately after it is the
`if [ -z "$listed" ]` branch that exists to report exactly this case — unreachable code. Repro:
`set -euo pipefail; x="$(printf '' | grep -v '^$')"; echo unreachable` prints nothing, exits 1.
Emulating a `scala-cli` that exits 1 reproduced the CI signature down to which stdout line is last.
Fixed with `|| true` inside the substitution. The timing below still stands and still matters — it
is WHY scala-cli fails on a runner and never here — but it was the trigger, not the defect.

`freeze-consistency` is the sharper number: **2 s locally, 94.5 s on CI, a 47x ratio.** The
previous worst known in this repo was 19x (`launchers-not-dead`, 5.5 s → 104.5 s). Cause is not
mysterious — the gate shells out to `scala-cli tests/conformance/contract.sc -- --list`, which is
0 s here against a warm cache and a cold resolve-and-compile on a runner. Both its internal
`timeout 120` and the runner's 120 000 ms guard were sized as if local timing were indicative of
anything.

**The part that costs the most is neither: the runner cannot tell you why a check failed.** For
both reds the reason is absent from the FULL log, not just `--log-failed`. `exec` is not at fault —
probed directly, it captures stdout and stderr separately and correctly:

    exitCode = 1
    stdout   = [on stdout: line one]
    stderr   = [on stderr: THE REASON \n on stderr: second reason line]

So a check that is KILLED loses its stderr entirely, and `freeze-consistency` writes every one of
its failure messages to stderr — including its own summary line. A red that says only `exit code -1`
sends the next person to reproduce locally, where both checks pass in 2 s and 24 s.

Three things worth doing, smallest first:

1. Name the timeout. When the elapsed time reaches `timeoutMs`, or the exit code is negative, print
   `TIMED OUT after Ns (guard Ms)` instead of a bare number. This alone turns both reds into
   self-explaining ones.
2. Size the two guards off CI numbers, not local ones. A guard at 2.5x the CI observation, not 2.5x
   the local one.
3. ~~`freeze-consistency` should print findings as it makes them.~~ **DONE, d0df30a89** — and it
   turned out to be the actual defect rather than a diagnosability nicety, see the correction above.
   It also now prints a breadcrumb before the slow `scala-cli` step, which is the only thing that
   survives when the runner's guard kills a check.

**Residual, and it is a real one: the skip d0df30a89 introduced cannot be seen.** The runner prints
output only for a check that FAILS, so a passing `freeze-consistency` produces no lines at all —
and "I5 ran and the roster is clean" is therefore indistinguishable from "I5 skipped because
`contract.sc --list` produced nothing". The fix turned a silent RED into a silent SKIP, which is
strictly better (main is green, and the other five invariants still run) but not yet honest.

What the logs can and cannot settle, so nobody re-derives it:

    run 31060671890  toolchain cache HIT   freeze-consistency 94.5s  died silently (the set -e bug)
    run 31082837072  toolchain cache MISS  freeze-consistency 11.1s  passed, branch UNKNOWN

**Third observation 2026-08-06, and it settles the SHAPE if not the cause.** `freeze-consistency`
timed 94.5 s (run 31060671890), 11.1 s (run 31093483796), 94.8 s (run 31094926802) — it swings
between roughly 11 s and 95 s from run to run, so this is not a monotonic cache story at all and the
hit/miss correlation in the table below is coincidence on two samples. The 95 s runs are consistent
with the intermittent Bloop download already documented in
`corpus-breadth-slice-crashes-scala-cli-on-ci` ("three of the last fifteen runs").

Worth noting for the budget question: that 84 s swing is most of the 482 s -> 634 s jump between
those last two runs, so a suite that measures 634 s and blames the change in that push is usually
blaming the wrong thing. And on the 94.8 s run `freeze-consistency` PASSED — before d0df30a89 the
same condition killed it silently with no reason in the log.

The obvious reading — cache hit skips the coursier step, so scala-cli has to fetch Bloop — is
WRONG here: `Cache Coursier/sbt` in smoke.yml carries no `if:`, it was made unconditional by
5d397bd26 precisely because of `corpus-breadth-slice-crashes-scala-cli-on-ci`, and that is still
the case. The 8.5x timing difference is unexplained, and the intermittent scala-cli download
failure documented in that entry ("three of the last fifteen runs") is enough to produce it without
any cache story.

To settle it, the gate has to report the branch through the only channel a passing check has —
which is none. So either I5's outcome moves into the check NAME the runner prints, or the gate
fails when I5 cannot run under `CI`. The second is defensible (the workflow installs scala-cli
deliberately, so an inability to list is a real defect there and a legitimate skip on a dev host
without it) but it puts main red for an environment flake, so it is a decision, not a cleanup.

Items 1 and 2 NOT DONE HERE because `scripts/smoke-ci.ssc` is inside the `release-graalvm-pin`
claim. They remain worth doing: `run-lane-flags-are-flags` is still a genuine guard failure (24 s
local, 60.1 s against a 60 000 ms guard), and a killed check still reports a bare `exit code -1`.

Note this is NOT the same defect as `smoke-suite-over-its-own-budget`, though both runs are also
over budget (627.6 s and 629.1 s of 600 s). That one is about total wall time with every check
green; this is two checks going red for being sized against the wrong host.

## f-front-cannot-lower-a-typeclass-dictionary-and-says-so-only-on-stderr

<!-- status: open
     lane: native
     area: front
     kind: bug
     confirmed: yes
     gate: tests/e2e/v21-typeclass-dictionary-smoke.sh -->

**Measured 2026-08-14.** The F front declines both typeclass-dictionary programs and the reference
front compiles them instead:

```
$ ssc info --front-report tests/fixtures/v21-native/typeclass-dictionary.ssc
… /tests/fixtures/v21-native/typeclass-dictionary.ssc   GAP   match: no arm for Cons/2
```

Same gap on `examples/typeclass.ssc`. Both run correctly and exit 0 — the fallback is the design,
and it announces itself on stderr exactly as `f-front-silent-delegation-hides-coverage-gaps`
arranged. Nothing is silently wrong; what is wrong is that **nobody was reading that stderr**, for
two compounding reasons:

1. The only gate that asserts on it, `tests/e2e/v21-typeclass-dictionary-smoke.sh`, is invoked by
   nothing (`orphaned-e2e-gates-52`) — and until today it failed while printing nothing at all.
2. **The existing F-gap census could not see these files.** `f-front-silent-delegation-hides-coverage-gaps`
   measured 26 of 329 delegating cases, and its reproduce command is
   `for f in tests/conformance/*.ssc`. Neither `tests/fixtures/v21-native/` nor `examples/` is in
   that population, so "26 of 329" is a true statement about the conformance corpus and not about
   the repo. A census answers only its own question.

**Done when** `bin/ssc info --front-report` reports F rather than `GAP` for both files and
`tests/e2e/v21-typeclass-dictionary-smoke.sh` is green with a silent stderr — the gate asserts
exactly that, and it now prints the stderr it rejected, so the next reader gets the message instead
of a bare exit code.

**Next measurement for whoever takes this**: re-run the census over `examples/` and
`tests/fixtures/` as well as `tests/conformance/`, because `no arm for Cons/2` is a lowering gap in
a pattern arm and is unlikely to be reachable from only two files.

## jvm-artifact-stack-trace-never-names-the-users-own-file

<!-- status: fixed
     lane: v2-jvm
     area: codegen
     kind: bug
     confirmed: yes
     fixed-in: e2f5812e1
     gate: tests/e2e/v21-build-jvm-smoke.sh -->

**Measured 2026-08-14.** A jar built by `ssc build-jvm` from a two-line program throws, and **not one
of its 29 stack frames names the file the user wrote.**

The fixture is the whole reproduction:

```
tests/fixtures/v21-native/source-map-failure.ssc
  4:  def explode() = jsonParse("{")
  5:  explode()
```

```
$ ssc build-jvm tests/fixtures/v21-native/source-map-failure.ssc -o smf.jar
$ java -jar smf.jar
Exception in thread "main" java.lang.RuntimeException: invalid JSON at 1: unterminated object
        at ssc.plugin.json.NativeJsonCodec$.unwrapStrict(NativeJsonCodec.scala:122)
        …
        at ssc.gen.Entry.lam$303(json.ssc:65)
        at ssc.gen.Entry.lam$302(json.ssc:65)
```

Every `.ssc` attribution in the trace points into the standard library — `json.ssc` at lines 38, 49,
52, 64, 65, 68, 72, 76, 88, 90, 93, 95, 100, 135, 136 — and `source-map-failure.ssc` appears
**zero** times. The `ssc.gen.Entry` frames are there, so the source map is being emitted; it is
attributing the user's frames to the callee's file.

This is user-facing in the way that matters: the debug metadata exists precisely so a crash points
at the line somebody can fix, and a user of a built artifact is shown line numbers in code they have
never seen. The rest of the artifact's metadata is right — `javap` confirms
`SourceFile: "argv.ssc"`, a `SourceDebugExtension`, a `LineNumberTable`, and no absolute checkout
path — so this is one wrong attribution, not missing debug info.

**Done when** the trace carries a frame matching `ssc.gen.Entry.*(source-map-failure.ssc:4)`. That
is the assertion `tests/e2e/v21-build-jvm-smoke.sh` has always made, at the line that has been
failing; it now prints the frames it rejected and a histogram of the files they do name.

**Why nobody knew:** the gate is invoked by nothing, and it consisted of twenty bare
`grep … >/dev/null` under `set -e`, so its failure was an exit code with no stdout, no stderr and a
deleted sandbox (`orphaned-e2e-gates-52`, batch 4, the "fails without saying why" group).

### FIXED 2026-08-14 — the prelude was sitting in the user's seat

**Root cause, found before any edit was made.** `RunNativeV2` builds its root list as

    ambientPrelude(userFiles, stdRoot) ++ dottedStdImportPrelude(userFiles, stdRoot) ++ userFiles

— the AMBIENT PRELUDE first, by design: a program that calls `jsonParse` without importing json gets
`std/json.ssc` injected as a leading source so the runner's single program scope matches INT and JS.
`NativeJvmSourceMap.build` then took file 1 = *the first explicit root*, and a prelude IS an explicit
root. So the program being compiled became file 2, and since the JVM stores ONE `SourceFile` per
class and prints it on every frame, every frame named `json.ssc`.

**Proven by experiment rather than by reading, and the experiment is one line:** write the import out
by hand — `[jsonParse](std/json.ssc)` — and the prelude does not fire, `SourceFile` becomes the
user's file, and 21 frames name it. Same compiler, same fixture, one line of difference.

**The fix is in the debug metadata, not in the prelude order.** The prelude must stay leading — that
is a scope requirement with its own entry (`v2-native-ambient-prelude`) — so what changes is that
`NativeSourceUnit` stops conflating two different things:

| field | means |
|---|---|
| `explicitRoot` | named as a root of the source closure — includes compiler-injected preludes |
| `userRoot` | named by the USER on the command line |

`NativeSourceClosure.resolve` takes the user's paths and marks them; `NativeJvmSourceMap.build`
orders user roots, then prelude roots, then imports. Three files, 35 lines.

**Measured, on the same fixture:**

| | before | after |
|---|---|---|
| `SourceFile` | `json.ssc` | `source-map-failure.ssc` |
| SMAP file 1 | `json.ssc` | `source-map-failure.ssc` |
| frames naming the user's file | **0 of 29** | all `.ssc` frames |
| frame at the call site | none | `source-map-failure.ssc:5` — `explode()` |

**The gate's old assertion asked for `:4` and could not get it, and that is stated rather than
quietly relaxed.** Line 4 is `def explode() = jsonParse("{")`, and it IS in the LineNumberTable —
`javap -l -p` puts it in `lam$181`, the def's body. That method is not a JVM frame when the throw
happens: the body is a single call, the runtime trampolines into the json plugin, and what unwinds
is the call site and the lambdas around it. `:4` is therefore a claim about LOWERING, not about
debug metadata, and asserting it kept the gate red while telling nobody which of the two problems it
had. It is replaced by two assertions that are checkable and that both fail on the original defect:
the SMAP's file 1 must be the program being compiled, and at least one frame must name that file at
a line the file actually has (a six-line fixture reporting `:53` is a number, not an attribution).

**Negative control run, because a gate that has only been seen green is a gate nobody has watched
work:** reverting `orderedUnits` to the old expression and rebuilding makes it fail with
*"the SMAP's file 1 is 'json.ssc', not the program being compiled"*. Restored, rebuilt, green again.

**Two apparatus faults found while doing it**, both in the reporting added to this gate earlier
today, and both the same shape as the defect itself — a check failing on its own plumbing:
`awk '…{exit}'` closed the pipe while `printf` was still writing, and the SIGPIPE (141) tripped the
ERR trap; and a SECOND `set +e` block expecting a non-zero exit needed the trap disarmed, exactly
like the first. Both sites are now marked; a third would be worth a helper.

**Wired into `conformance-extras` at 26 s, and off the frozen orphan list: 12 -> 11.**

## a-smoke-guard-under-3-5x-its-own-baseline-is-a-flake-generator — measured over 75 checks

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     confirmed: yes
     fixed-in: a833f9b77
     gate: tests/e2e/smoke-guard-headroom.sh -->

**The successor to `smoke-check-guards-sized-by-local-time`, which is FIXED and whose fix was not
enough.** That entry raised `run-lane-flags-are-flags` from a 60 s guard to 180 s because 60 s sat at
~85 % of the check's own cost. Correct, and it moved the check from *certain* flake to *coin flip*:
its baseline is 56.5 s, so the new guard is **3.2×**, and on 2026-08-14 it timed out again.

**The number, computed from the repo's own two sources** — `timeoutMs` in `scripts/smoke-ci.ssc` and
the measured cost in `tests/smoke-baseline.tsv` (column 1 is deciseconds; the column sums to 699.3 s,
which is the `sum-seconds` header, so the unit is not a guess). 75 checks carry both:

| ratio | check | baseline | guard | 2026-08-14 |
|---|---|---|---|---|
| **2.7×** | `stub-does-not-serialise` | 22.6 s | 60 s | **TIMED OUT in 3 of 4 runs** |
| 3.0× | `mixed-numeric-comparison` | 59.5 s | 180 s | — |
| 3.0× | `import-alias` | 39.4 s | 120 s | — |
| **3.2×** | `run-lane-flags-are-flags` | 56.5 s | 180 s | **TIMED OUT in 2 of 4** |
| **3.3×** | `entry-auto-invoke-once` | 36.6 s | 120 s | **TIMED OUT in 1 of 4** |
| 3.4× | `v2-indent-layout` | 17.8 s | 60 s | — |
| 5.3×–… | the other 69 | | | none timed out |

**The three that timed out are ranked 1st, 4th and 5th tightest out of 75.** Nothing above 3.4× timed
out at all. That is the finding: the guards are ceilings on a check's *standalone* time, but the
suite runs on a host where ten agents are building, and the inflation is measured at **2.5–7×**.

Standalone on the same tree, load 23–25, against their guards: `stub-does-not-serialise` 17 s and
24 s (guard 60), `entry-auto-invoke-once` 29 s (120), `run-lane-flags-are-flags` 47 s (180),
`request-validation-family` 5 s (300). Every one comfortable. Inside the suite, three of them hit
100 % of their guard exactly.

**Four full runs, and no two failed the same way** — which is the operational cost, because each
false red costs a re-run of ~25–35 minutes:

| run | tree | wall / budget | failed |
|---|---|---|---|
| 1 | branch | 1951 s / 1027 s | `run-lane-flags-are-flags` (180.0 = its guard), `stub-does-not-serialise` |
| 2 | branch | 1636 s / 1027 s | `std-ui-forms`, `stub-does-not-serialise` (60.0 = its guard) |
| 3 | **pristine `origin/main`** | 1261 s / 1027 s | `freeze-consistency` (a REAL red, fixed 20 min later by `a8cde6ecd`), `response-transforms` |
| 4 | branch, rebased | 2203 s / 1027 s | `request-validation-family`, plus all three TIMED OUT |

Run 3 is the control and it matters twice: it says the reds are not a property of any branch, and it
caught main genuinely red for an unrelated reason inside the same hour.

**The total budget is already handled and is NOT this.** The runner prints *"over budget LOCALLY —
reported, not failed"* and means it. The per-check guards get no such treatment: they are absolute
milliseconds compared against a wall clock that a neighbouring build stretches by 2–4×.

**Why this is worth a gate rather than another round of raising numbers.** Both halves of the ratio
already live in the repo and neither needs the suite to run, so the check costs milliseconds:
for every `Check`, `timeoutMs / baseline` must clear a threshold; freeze today's six that do not,
fail on a new one. The same ratchet shape as `no-orphan-gates` and `v1-jit-size`. Raising a guard
costs the budget **nothing** — `timeoutMs` is a ceiling, not a cost — which is the sentence
`scripts/smoke-ci.ssc` already carries and which makes the fix cheap once the gate names the rows.

**And the fourth failure is a different defect, recorded so it is not swept in here.**
`request-validation-family` did not time out — 15.1 s against a 300 s guard, i.e. 26× headroom. It
failed an assertion: `optional*, fields absent — '' lacks 's=None'`, an **empty response body**,
while the three checks before it in the same run got their 400s. Standalone it passes in 5 s. That is
the signature of `wired-gates-share-hard-coded-tcp-ports`, not of a guard.

Found while trying to get a clean pre-push verdict for `orphaned-e2e-gates-52` batch 4. It took four
suite runs and ~90 minutes to establish that none of the reds were mine, and that cost is the point:
a suite that fails at random teaches agents to push past red, which is exactly what
`tests/BUGS.md` records as having happened on eight consecutive commits the same morning.

### FIXED the same day — six ceilings raised, a gate added, and NO frozen list

**The threshold was chosen by the gap, not by taste.** Sorted by guard ÷ baseline the checks are six
between 2.7x and 3.4x, then **nothing until 5.3x**. Any threshold from 3.5x to 5x selects exactly the
same six, so the line goes at **4x** — inside an empty band, with no check near it in either
direction. Putting it at 5x would have made `cli-errors-are-messages` (5.3x) the new marginal case,
one baseline refresh away from a red on a check nobody touched.

| check | costs | was | now | now at |
|---|---|---|---|---|
| `stub-does-not-serialise` | 22.6 s | 60 s | 180 s | 8.0x |
| `mixed-numeric-comparison` | 59.5 s | 180 s | 420 s | 7.1x |
| `import-alias` | 39.4 s | 120 s | 240 s | 6.1x |
| `run-lane-flags-are-flags` | 56.5 s | 180 s | 420 s | 7.4x |
| `entry-auto-invoke-once` | 36.6 s | 120 s | 240 s | 6.6x |
| `v2-indent-layout` | 17.8 s | 60 s | 120 s | 6.7x |

Raised in the same commit that added the gate, so the frozen list is **empty** and there is no debt
to rot into a permanent exemption. The budget is untouched — `smoke-budget-derivation` sums
BASELINES, not ceilings, and `timeoutMs` is a ceiling rather than a cost.

**`tests/e2e/smoke-guard-headroom.sh`**, wired into `scripts/smoke-ci` next to
`smoke-budget-derivation` — the other half of this suite's own arithmetic. It runs nothing: pure
arithmetic over the CheckList and `tests/smoke-baseline.tsv`, in milliseconds. When it fails it
prints the ratio and **the number to raise the guard to**.

**Three ways it got its own population wrong before it worked, and all three are now self-tests:**

| parser | saw | what it lost |
|---|---|---|
| whole-file lazy regex | 100 of 101 | `corpus-lane-breadth`, swallowed into a neighbour's match |
| per-line regex | 93 of 101 | every `Check` written across two lines |
| comments not stripped | 103 of 102 | counted the literal quoted in **its own wiring comment** |

The third is `no-orphan-gates`' "a mention is not a caller" in a new place, and it is the one that
would have been easiest to make go away by loosening the count. Instead the count is **asserted**:
every `Check("` literal must parse, and a mismatch FAILS the gate rather than shrinking its survey.
The 26 checks with no `tests/smoke-baseline.tsv` row yet are printed BY NAME as "not judged", for
the same reason — a survey that quietly drops its unmeasurable rows reads as complete.

**Verified in every direction** (`--self-test`, 7 cases): a 3.0x guard fails and the message names it
and the number to use; raising it to 6.0x passes; an unparseable guard (a named constant instead of a
literal) fails on the count; a `Check("` inside a comment is not counted; a `//` inside a string
literal is not treated as a comment; a check with no baseline row is named rather than swallowed.

## launcher-digest-gate-is-225s-of-a-500s-budget — two processes per line, 7272 lines

<!-- status: fixed
     lane: apparatus
     area: build
     gate: tests/e2e/launcher-digest-gate.sh
     fixed-in: 99f335023 -->

`scripts/launcher-input-digest` extracted the sha from each `git ls-tree` line with

```
printf '%s\t%s\n' "$(printf '%s' "$meta" | awk '{print $3}')" "$path"
```

— a command substitution spawning **two processes per line, over 7272 lines**: roughly 15,000
process spawns per digest. The git commands themselves cost 0.01–0.08 s; the spawning was the entire
runtime.

| | before | after |
|---|---|---|
| one `launcher-input-digest` run (local) | 11.3 s | **0.60 s** |
| `tests/e2e/launcher-digest-gate.sh` (local) | 5 m 34 s | **19.7 s** |
| the same check on CI | 225.5 s / 227.0 s | expected ~13 s |

The fix is `${meta##* }` — ls-tree meta is `<mode> <type> <sha>`, so the sha is the last
space-separated field and bash takes it with no process at all. **Verified equivalent on real data
rather than by reading**: for all 7271 `ls-tree` lines in this repo, `${meta##* }` and
`awk '{print $3}'` agree on every one.

**Why this mattered beyond being slow.** At 227 s this single check was 27% of the smoke suite and
3× the next one, and the suite ran at **467.6 s against its own 500 s cap** — 32 s of headroom on a
path whose measured variance is larger than that. One CI instance took 823 s and the job went red
with all 60 checks green, which reads to every agent as "main is broken". Removing ~214 s turns a
suite that flaps at its ceiling into one with roughly half the budget spare. Related, and left to
its owner: `smoke-suite-over-its-own-budget`.

The digest VALUE changes with this commit, which is correct and worth stating: the script is one of
its own inputs (verified), so editing it must change the digest — that is the property the digest
exists to have.

Loops 2 and 3 (`git diff HEAD` / `git ls-files --others`) still run one `git hash-object` per file.
Left alone deliberately: both lists are empty in a clean checkout, so they cost nothing today, and
batching them via `git hash-object --stdin-paths` is a change with no measurement behind it here.

## launcher-digest-gate-backslash-t-is-not-a-tab-in-ere — green on this machine, red on CI, main blocked for two hours

<!-- status: fixed
     lane: apparatus
     area: build
     gate: tests/e2e/launcher-digest-gate.sh
     fixed-in: 4cccc6ce4 -->

`tests/e2e/launcher-digest-gate.sh` asserted that every digest input line looks like
`<sha>TAB<path>`:

```
grep -vE '^  ([0-9a-f]{40}|deleted)\t'
```

**`\t` is not a tab in POSIX ERE.** GNU grep reads it as the letter `t`, so the pattern became
`^  ([0-9a-f]{40}|deleted)t`, NOTHING matched, and `grep -v` handed back EVERY line as malformed —
reported as `the digest still depends on git state`, which is the opposite of what was wrong.

**It passed locally because this machine's `grep` is ugrep 7.5.0**, which does accept `\t`. Measured
side by side on the same line: ugrep matches with `\t` and with a literal tab; GNU grep only with a
literal tab. So the gate was green for its author and red on CI from 15:09 to 17:20 — and a red
`main` costs every agent evidence level 1, not just the one who pushed.

**Fixed** by writing a real tab through bash `$'...'`, which both implementations expand before grep
ever sees it.

**Guard added, and it is the interesting half.** This gate cannot catch the bug by RUNNING itself —
on ugrep the broken pattern works fine. So the guard asserts the property that holds on every host:
no `grep -E` pattern in this file may contain a backslash-t, discriminating `$'...\t'` (bash expands
it, correct) from `'...\t'` (grep sees two characters, broken). Comment lines are skipped so the
paragraph explaining the trap does not trip it. Verified both ways: clean file passes, and removing
the `$` from the fixed line makes the gate fail with `ere-backslash-t`.

Found while investigating a CI red on my own push (`0f3f7c540`). It was NOT mine — the first red was
`45babaee6`, an hour earlier — but my local smoke had reported this same check as a 240s TIMEOUT
under host load, which masked the real failure. Two lessons in one: a timeout hides a content
failure, and "passes locally" is worth nothing when the tool is a different implementation from the
one CI runs.

## smoke-runner-cannot-run — a poisoned classpath cache, and a control that shared it

<!-- status: fixed
     lane: multi
     area: runtime
     fixed-in: e714c31ab
     gate: scripts/smoke-ci -->

`scripts/smoke-ci` died before its first check with

```
ssc: class scala.Tuple2 cannot be cast to class scala.collection.immutable.List
```

**No longer reproduces.** On current main: the suite runs 70/70 green.

**The cause was not a runtime regression, which is what I filed.** `e714c31ab` describes it exactly:
`uniml_classpath` cached a *directory path*, and scala-cli rewrites that directory on every compile
of the same inputs — so

```
A -> compile -> cache[A] = P, P holds A
B -> compile -> cache[B] = P, P now holds B     (shared, overwritten)
back to A -> HIT on cache[A] -> serves P, holding B
```

A class file from a different compilation state is precisely how you get a cast error between two
unrelated types. It hit me because I had been rebuilding across tree states all session — release
branch, main, revert, main — which is the A → B → A pattern that triggers it.

**The instructive part is why my control did not catch it.** I reverted my change, rebuilt, re-ran,
got the identical failure, and concluded "main is broken, not me". That inference needs the two arms
to differ only in my change — and they did not: both shared the same poisoned cache, so the control
could not exonerate anything. A control that shares the contaminated resource is not a control, it
is the same measurement twice.

What did hold up: the narrowing. Running the previous `smoke-ci.ssc` under the failing toolchain
showed the script was innocent, and testing all three fronts showed it was below the front. Both
were true and both pointed at shared state — I read them as pointing at shared *code*.

## compile-jvm-shipped-broken-in-the-native-binary — resources a native image never carried

<!-- status: fixed
     lane: native
     fixed-in: 1bd9374d444b8c7cbc0ee1b3e644166a0ce6b2dd
     area: build
     gate: scripts/native-release-qualify -->

`ssc compile-jvm` worked on the JVM launcher and failed in the **native binary on all three
platforms**, including the published v0.1.0:

```
compile-jvm error: JVM runtime resource not found on classpath:
  /scalascript/jvm-runtime/stubServeRuntime
```

The command inlines Scala source fragments that live as classpath RESOURCES. A native image carries
only the resources named in `resource-config.json`, and none of these were named.

**Three families were missing, and each surfaced only as the next failure of the same command** —
one per 2.5-minute image build: `scalascript/jvm-runtime`, then `http-server-spi-sources`, then
`http-server-common-sources`. They are produced by `resourceGenerators`, so they never appear under
`src/main/resources` and a scan of the tree does not find them. Registered as one pattern,
`[A-Za-z0-9-]+-sources/.*`, rather than three entries: build.sbt generates four such roots today and
the wildcard covers the fifth nobody has written yet.

**How it stayed invisible.** Nothing exercised `compile-jvm` on the native lane. I nearly filed it as
noise myself: seeing the identical failure on two differently-built binaries, I concluded it was my
probe environment. That inference was wrong in a specific way worth remembering — the failure was
identical because **neither** image had the resource, not because my setup was at fault. A control
answers the question it was asked (did my change break this?) and not the wider one (does this work
at all?).

Now qualified: `compile-jvm` runs in the release qualifier and must leave a non-empty `.scjvm`
artifact, with two self-test mutations (`cjvm-exit`, `cjvm-artifact`). Self-test 64 cases.

## native-release-native-image-three-defects — the stage nothing had ever reached

<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: afe93a9b3
     gate: .github/workflows/native-release.yml -->

With the pipelining blocker fixed (`native-release-blocked-by-testutils-clean-compile`), run
`30954908133` became the first ever to reach `native-image`. All three runners fail there, and the
log separates into **three independent defects** — the memory one masks the others, so they must be
fixed in order.

**1. `build.sbt`'s native-image config paths point at a directory that does not exist.**
The configs live at the repo root, `./native-image-configs/`. `build.sbt:2347-2348` builds
`(cli / baseDirectory) / ".." / ".."`, and cli's base is `v1/tools/cli`, so that resolves to
`v1/native-image-configs` — **missing**. The workflow's `set` line supplies `".." / ".." / ".."`
(the root), which is correct. That override is not a diagnostic hack to be deleted, as its comment
implies: it is the only reason the configs load at all. Fix `build.sbt` FIRST, or removing the
override silently drops reflection and resource configuration.

**2. The forced heap is below what the tool asks for, on every runner.**
The same `set` line strips `-J-Xmx` and forces `-J-Xmx5g` (build.sbt asks for `8g`). native-image
states its own requirement in the log:

| runner | system RAM | given | native-image asks for |
| --- | --- | --- | --- |
| ubuntu-latest | 15.61 GB | 4.44 GB | > 6.04 GB |
| macos-15-intel | 14.00 GB | 4.44 GB | > 5.69 GB |
| macos-latest (arm64) | **7.00 GB** | 4.44 GB (63.5%) | not printed |

linux and arm64 die as `Image generator watchdog detected no activity` with used == maximum heap
(4700 and 4918 MB) — starvation, not deadlock. **`8g` is not the answer either:** it fits linux and
intel but is 114% of the arm64 runner's 7 GB. The heap has to vary per runner, or arm64 needs a
bigger machine.

**3. A genuine image-heap defect, visible only on the runner that got furthest.**
macos-15-intel had enough memory to finish analysis and then failed differently:

```
Image heap writing found a class not seen during static analysis
  object: DirectSubstrateObjectConstant[Object]
  reachable through: java.util.concurrent.ConcurrentHashMap$Node[]
  object: {sun.util.resources.LocaleNames-en-US=…, sun.text.resources.cldr.FormatData-nb-NO=…}
  root: sun.util.resources.LocaleData$LocaleDataStrategy.getCandidateLocales(String, Locale)
```

JDK locale data cached at build time and not seen by static analysis. **Expect this to become the
blocker for all three once memory is fixed** — intel is not special, it just got there first.

**4 (found once 1 and 2 let two runners reach the qualifier): `run` parses `--interpret` as a FILE.**
`Main.scala`'s `run` parser ends in `case f => fileArgs += f`, so a flag it does not know becomes a
path. `--bytecode` was handled; its opposite `--interpret` / `--vm` was not, though `StandardMain`
has accepted both all along (`StandardMain.scala:52-53`). The native binary enters through
`@main def ssc` in `Main.scala`, so the qualifier's `ssc run --v2 --interpret release-probe.ssc`
died as:

```
java.io.FileNotFoundException: native frontend input not found: --interpret
  at scalascript.cli.RunNativeV2$.$anonfun$5(RunNativeV2.scala:133)
```

Fixed, with gate `tests/e2e/run-lane-flags-are-flags.sh` (in smoke, ~2 s) asserting all five lane
flags on BOTH launchers — `bin/ssc` (StandardMain) and `bin/ssc-tools` (`scalascript.cli.ssc`, the
native binary's entry). The first draft tested only `bin/ssc` and passed against the unfixed tree,
because that entry never had the bug: a probe whose subject is reachable without the thing under
test measures nothing.

**5 (FIXED 2026-08-06): `run` printed its diagnostics to STDOUT.** Measured before:

```
ssc-tools run --no-such-flag-xyzzy p.ssc  2>&1 >/dev/null   ->  (empty)
ssc-tools run --no-such-flag-xyzzy p.ssc  2>/dev/null       ->  Error: File not found: …
```

An error on the data stream: a caller capturing program output got the message mixed into it, and a
caller reading stderr saw nothing.

It was filed unfixed on the theory that moving the stream might break consumers matching on stdout.
A survey found none — every hit was prose inside a gate, not an assertion — and the same file
already contradicted itself: `EmitCommands.scala` printed this identical message to stdout at four
sites and to **stderr** at a fifth. That is not a contract, it is an oversight, which made the fix
safe rather than risky.

22 sites moved across `Main.scala` and `EmitCommands.scala`; zero `println("Error…")` remain in the
CLI. Verified: stdout is now empty and the message is on stderr. `run-lane-flags-are-flags` asserts
BOTH halves, so a regression fails rather than being noticed by someone's broken pipeline. smoke
69/69.

**Do not treat this entry as "the release is nearly done".** Defect 3 has never been attempted and
is the substantive one; 1 and 2 are bookkeeping in front of it.

**All three fixed; `v0.1.0` and `v0.1.1` are published and the last two `Native Release` runs are
green.**

* **1 (config path)** and **2 (heap)** — `afe93a9b3` (2026-08-05), *"anchor native-image configs on
  the build root, size the heap per runner"*. `build.sbt` now builds the path from
  `(ThisBuild / baseDirectory)` rather than from cli's base, and carries a comment naming the old
  `v1/native-image-configs` mistake so it cannot be reintroduced quietly. The heap is sized per
  runner, which is what the entry argued for — a single `8g` would have been 114% of the arm64
  runner's 7 GB.
* **3 (image heap)** — resolved by `9acde80f4` *"invoke native-image without sbt"* and `d10c9460b`
  *"the native image gets a narrower classpath"*. The `set cli / graalVMNativeImageOptions := …`
  line the entry analysed no longer exists at all; native-image is invoked directly.

Closed on the RECORD, not on reading the code: two published releases and two green runs. The
entry's line numbers had also drifted — `build.sbt:2347-2348` is now unrelated code, which is worth
knowing before anyone tries to verify one of these from the numbers alone.


## native-release-blocked-by-testutils-clean-compile — the release workflow has never produced a release
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 6d0fba784
     gate: .github/workflows/native-release.yml -->

**Found 2026-08-04** cutting `v0.1.0`, the repository's first tag. The `Native Release` workflow
fires on `v*.*.*`, proves its refusal contract, then builds three GraalVM binaries. All three
`Qualify …` jobs fail in the **`Build native binary, frontend data, and plugin host`** step, and
`Publish qualified tag` is skipped — so **no release has ever been published** (0 tags before this
one; the only earlier run, `30316338197` on 2026-07-28, failed identically).

**The whole failure is one file.** 6 errors on Linux, 6 in each macOS job, nothing else:

```
v1/runtime/backend/test-utils/src/main/scala/scalascript/testkit/TestInterpreter.scala:3
  value backend is not a member of scalascript
  value interpreter is not a member of scalascript
  value parser is not a member of scalascript
```

All three imports missing means testUtils compiled with **none of its project dependencies on the
classpath**, even though `build.sbt:1394` declares `.dependsOn(backendSpi, backendInterpreter)`, and
the log confirms the target is testUtils' own (`compiling 1 Scala source to
v1/runtime/backend/test-utils/target/scala-3.8.3/classes`).

**It does not reproduce locally.** `sbt "testUtils/clean" "testUtils/compile"` in a worktree at the
tagged commit exits 0. So the difference is in *how CI builds*, not in the source: CI runs one
invocation — `sbt 'set cli / graalVMNativeImageOptions := …' "cli/installBin"
"cli/graalvm-native-image:packageBin" "pluginHost/assembly"` — and the `set` rewrites a setting on a
reloaded build state. Reproducing means running THAT command, not an approximation of it; every
local shortcut tried so far passes and therefore proves nothing.

**Not caused by the release commit.** `5c32e60bf` only drops `-SNAPSHOT`; the identical failure
predates it by a week. Worth stating because a version change is exactly the kind of thing that
*could* break resolution if any module depended on a published coordinate rather than `dependsOn` —
it does not, but that hypothesis should be retired explicitly rather than left hanging.

**Why it survived a week unnoticed:** the per-push suite is `smoke.yml`, which measures a staged
launcher and never builds the sbt graph from clean; the full `sbt — compile and test` job lives in
`ci.yml`, which on a push is `Lint Markdown` alone. A clean-build breakage is therefore invisible
until someone dispatches the full suite — or tags a release, which is how this surfaced.

**Done since, and it did not reproduce.** The exact CI invocation was run locally on a fully clean
tree (`sbt clean` + the `set` + `cli/installBin` + `pluginHost/assembly`): **rc 0**. So did
`testUtils/clean + compile`, and a clean of the whole dependency chain (`core`, `backendSpi`,
`backendInterpreter`). `testUtils` IS in the root aggregate, so `clean` does reach it; the tag's tree
and the local tree are identical for `build.sbt` and `test-utils` (nothing touched them after the
tag). The only difference left is the JDK: CI compiles under **GraalVM JDK 21.0.12**
(`JAVA_HOME=/opt/hostedtoolcache/graalvm-jdk-21…`), locally only Temurin 21.0.7/21.0.11 are
available, so the environment cannot be reproduced on this machine.

**Attempted fix, stated as a hypothesis because it has no local reproduction:** `testUtils` now
declares `core` directly. It already arrived transitively (`testUtils → backendInterpreter → core`),
so this changes nothing locally and cannot break — but it removes the only structural difference
between the failing imports and the declaration, and a module importing `scalascript.parser.Parser`
should name the project that provides it instead of relying on a chain. **The verification is the CI
run itself**; if the release still fails on the same three imports, transitivity was not the cause
and the next step is a diagnostic job printing `testUtils/dependencyClasspath` on a runner, since
that is the one thing no local run can answer.

**REFUTED 2026-08-04, run `30907972567`.** With `core` declared directly, the release fails
identically — `(testUtils / Compile / compileIncremental) Compilation failed`, same three imports.
Transitivity was not the cause. The declaration is kept because it is correct on its own merits (a
module should name the project providing a package it imports), but it fixes nothing and must not be
read as the fix.

**So the next step is now the only step:** a diagnostic job on a runner printing
`testUtils/dependencyClasspath` (and `show testUtils/internalDependencyClasspath`) before the build.
Every local avenue is exhausted — module clean, dependency-chain clean, full clean, and the exact CI
invocation all pass on this machine, and the environment differs only in the JDK (GraalVM 21.0.12 in
CI, Temurin locally). Nothing further can be learned without asking the runner what classpath it
actually assembled.

### 2026-08-04 — root cause: `usePipelining`, and why every local reproduction missed it

**Cause.** `ThisBuild / usePipelining := true` (build.sbt:54). Under pipelining, a module compiles
against its dependencies' *early* TASTy output. In the branch that builds the full artifact set —
the one that writes 35 `.pom` files and runs scaladoc — testUtils is compiled with that early output
absent, so all three of its imports resolve to nothing:

```
value backend is not a member of scalascript
value interpreter is not a member of scalascript
value parser is not a member of scalascript
```

testUtils is the module that shows it because **nothing depends on it in Compile scope**; it is only
ever reached by the artifact-collection path, never by a normal dependency walk.

**Proof — A/B, both sides from `git clean -xdf` (0 `target/` dirs), differing only in the flag:**

| run | result |
| --- | --- |
| full 3-command sequence, pipelining **ON** | `rc=1`, the E008 triple — CI's failure, reproduced |
| full 3-command sequence, pipelining **OFF** | 0 E008; testUtils compiles, scaladoc runs, build proceeds past it |

**Why every previous local reproduction passed.** The CI step is *three* sbt commands:

```
sbt 'set cli / graalVMNativeImageOptions := ...' \
    "cli/installBin" "cli/graalvm-native-image:packageBin" "pluginHost/assembly"
```

Local reproductions ran only the first. `cli/installBin` succeeds in 13 s and never compiles
testUtils — the poms start with the *second* command. The tell was in the logs all along: the
passing local run wrote **0** poms, the failing CI run wrote **35**.

**The JDK hypothesis is closed.** The failure reproduces on Temurin 21 with no GraalVM involved; the
environments never "differed only in the JDK", they ran different task graphs.

**Two wrong turns worth keeping**, because both are the same mistake:

- "testUtils does not compile from clean" — false. On a genuinely clean tree `testUtils/compile`
  passes *with* pipelining. That claim came from a tree already mutated by the earlier failed run.
- A control run "with pipelining back on" passed and appeared to refute the cause — but the
  preceding no-pipelining run had itself built the dependencies' full jars, so the control was
  measuring a repaired tree. **A control taken after the experiment is not a control.**

**Fix (corrected 2026-08-05): `ThisBuild / usePipelining := false`, build-wide.**

The first fix scoped it to testUtils, on the reasoning that testUtils is singular because nothing
depends on it in Compile scope. That reasoning was wrong — or rather, it explained why testUtils
fails *first*, not why it fails *alone*. `core` fails the same way and far harder:

| module | pipelining ON | OFF |
| --- | --- | --- |
| `testUtils/compile` | 27 errors | 0 |
| `core/compile` | **627 errors across 25 files** | 0 |

Same cleaned target on both sides, flag the only variable. `core` reports `Not found: SimpleYaml`
and friends — again at the *use* site, again reading like deleted code rather than a missing
classpath. It surfaced only because the scoped fix let the build get far enough to reach it.

The scoped setting is removed; keeping both would leave a second, redundant statement of the same
decision.

**This is state dependent, and that is the trap.** On a fully clean tree `testUtils/compile` passes
with pipelining ON. So a green build proves nothing about re-enabling it, and neither does a control
run taken after a repairing build — that mistake was made twice here already.

**Fixed in `6d0fba784` (2026-08-05), "pipelining off build-wide — the scoped fix was treating a
symptom".** The entry's headline — *no release has ever been published* — is refuted by the record:
`v0.1.0` published 2026-08-06, `v0.1.1` on 2026-08-07, and the last two `Native Release` runs
succeeded.

Verified rather than taken from the prose. In run `30954908133`, which the sibling entry cites,
`is not a member` appears **0 times** in the Linux log, and the failure has moved to
`(cli / Graalvm-native-image / packageBin) Failed to run` — past compilation, inside native-image.
The compile blocker this entry is about is gone; what remained was the image build, tracked in
[[native-release-native-image-three-defects]] and since fixed too.


## coord-claim-items-tokenised-so-prose-collides-on-stop-words — a claim refused over the word "the"
<!-- status: duplicate
     lane: apparatus
     area: build
     gate: none
     duplicate-of: coord-claim-items-prose-reserves-english-words -->

**DUPLICATE 2026-08-10 — of `scripts/BUGS.md coord-claim-items-prose-reserves-english-words`, and
the evidence above has been folded into it rather than left here.** That entry is canonical because
the fix is in `scripts/coord-claim` and `.githooks/pre-push`: routing is by the module that owns the
FIX, not by where the reporter was working when they hit it.

Worth naming what happened, because it is the same failure this bug describes. Two agents filed the
same defect six days apart and the overlap guard did not notice, since one claim's `items` said
`coord-claim-items-prose-reserves-english-words` and the other's said something else entirely — the
guard compares tokens, and two honest descriptions of one defect share no tokens. A guard that
mistakes `the` for a work item also misses two real duplicates; those are the same flaw seen from
its two sides.

The evidence kept from this report and now living in the canonical entry: the actual refusal text
from 2026-08-04, the four attempts misdiagnosed as contention, and the observation that prose
`--items` is ACCEPTED whenever its words happen not to collide — so the trap fires at random and
gets rarer as claims are released.


**Found 2026-08-04** claiming `v2-emitter-outline` with
`--items "E-4 outline the arm fallback, gate on emitted size"`:

```
✋ claim REFUSED — it overlaps a live claim. This is NOT a race; retrying will not help.
  item 'the' is already claimed by 'uniml-ssc3-frontend-readiness'
```

`--items` is **tokenised on whitespace** and each token is compared against every live claim's
tokens, so a prose description collides on any common word — `the`, `a`, `in`, `and`. The guard is
behaving exactly as designed (`--items` is documented as *"SPRINT ids / BUGS slugs"*, i.e. tokens);
prose is the misuse. Retrying with `--items E-4-outline-arm-fallback` succeeded immediately.

**Why it is still worth fixing:** the failure costs several minutes to diagnose because everything
about it points elsewhere. The message asserts "this is NOT a race" while the observable symptom is
a push rejection — the same symptom a race produces — and four earlier attempts in this session
were mis-diagnosed as contention and retried, which the message explicitly says will not help.
Naming `'the'` as the colliding item reads as nonsense until you know items are tokenised, and
nothing in the output says that.

Earlier claims in this session used prose `--items` too and were accepted, purely because their
words happened not to collide with a live claim — so the trap fires at random and gets rarer as
claims are released, which is the worst distribution for learning it.

**Fix directions** (small, not attempted here — `scripts/coord-claim` and `.githooks/pre-push` are
owned by whoever next touches the mutex): reject a whitespace-containing `--items` argument up
front with "items are ids, not prose"; or ignore tokens shorter than N characters when comparing;
or say "items are compared as whitespace-separated tokens" in the refusal message so the reader can
see the cause from the output alone.


## smoke-suite-over-its-own-budget — every check green, the suite red, main red for everyone
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 7fea7a711
     gate: scripts/smoke-ci -->

**FIXED 2026-08-10 — the budget is no longer a number, it is a sum over what the suite contains.**
`scripts/smoke-ci.ssc` now derives it from `tests/smoke-baseline.tsv`: one row per check, normalised
to a reference host, regenerated from real CI runs by `tests/smoke-baseline-harvest.sh`. Registering
a check raises the budget by THAT check's cost, so there is no fifth raise to make.

**What did NOT change, deliberately: how strict the push path is.** Raising or lowering the cap has
been the project owner's decision three times and stays theirs. The margin is the knob; it is sized
by the residuals, `actual - sum x factor`, which run −33.6 s to +68.8 s over nine replayed CI runs.
123 leaves 54 s over the worst observed and 0 of the 9 would have gone red — the same false-red rate
as the rule it replaces.

An earlier draft justified that 123 differently: "it equals the old rule at the reference host". True
of the table it was calibrated against, and false two hours later when the table was regenerated from
twelve runs instead of nine. **A margin defended by a coincidence goes stale silently**, so the
justification is now the residuals, which survive a refresh. Against today's suite the derived budget
is about 30 s LOOSER than the constant — not slack: the suite grew 78 → 80 checks while the constant
stayed at 78, which is the defect.

**Fail-safe in three directions, because this file can now turn `main` red for everyone:**

| state | budget |
| --- | --- |
| no table, or an unreadable one | falls back to the constant it replaced, exactly |
| a check with no row | charged its MEASURED cost at the end, and named in the output |
| a pinned `SSC_SMOKE_BUDGET` | unchanged |

The second one matters most: registering a check must never be the thing that turns main red, so an
unknown check is charged what it actually took and the run says `N check(s) have no baseline row …
refresh with tests/smoke-baseline-harvest.sh`.

**And the per-check share is now printed against its baseline**, which is the half a total-time cap
cannot do at all: on a median probe the budget is 791 s and the suite 647.9 s, so doubling the most
expensive check reaches 767.5 s and stays green, while its share moves 18.5 % → 31.2 %. Reported,
not enforced — the owner's call, since the spread of a share across hosts is not yet measured and a
new way to redden main before its false-positive rate is known is how a gate gets ignored.

**Gated by `tests/e2e/smoke-budget-gate.sh`, registered in smoke as `smoke-budget-derivation`.** It
asserts the ARITHMETIC from the inputs `--budget` prints rather than a pinned number, so refreshing
the table cannot make it stale — a pinned expectation gets "fixed" by updating it, which is how a
gate stops asserting anything.

**Two things the controls found that reading would not have.**

*One control passed on deliberately broken code.* Dropping the reader's `row.length >= 3` filter left
the gate GREEN, because the garbage fixture was lines with no tabs — and a line with no tabs matches
no check NAME, so the filter never mattered to it. The row that matters is a TRUNCATED one for a
*real* check: `sbt-plugin-scripted` with nothing after it, which is what reaches `row(1)` on a
one-element list and kills the runner. Added as a sixth case, and the control now fails — by the
suite dying, which is the failure mode predicted.

*The registration comment claimed a cost the check did not have.* Written as "about a second", it
measured 49.8 s: the gate needed five answers and each `--budget` invocation boots the toolchain, at
10 s a boot. `--budget` now answers for every table given in ONE invocation and the check is 7 s.
Registering something on the push path with a guessed cost, in the very suite whose budget this
entry is about, is the joke that writes itself.

Six cases: no table, the real table, a doubled table, a dropped row, a garbage table, a truncated
row. A/B'd against four deliberate breakages — wrong fallback, wrong margin, rows matched on the
wrong column, no column-count filter — each failing with its own message.

**The GREW marker is CI-only**, and that too came from running it: the first local run flagged
`ci-status-guard` at +9pp, baseline 3.1 s against 81.5 s measured. This file already records that the
same check takes 4.0 s on a runner and 72-78 s here at load 19. A share cancels UNIFORM inflation,
and a dev host under other agents' builds does not inflate uniformly — it inflates whatever is
spawning processes. The baseline share is still printed locally; what is suppressed is the
accusation.

**A defect the tool found in itself:** the first real harvest wrote a table from THREE runs without
complaint, because the search window was tied to the number of runs wanted and CI was churning
cancelled builds that day. A median over three points is not a median. It now refuses below
`--min-runs` (default 6) and leaves the existing table untouched when it does.

**MEASURED 2026-08-10 — the budget cannot do the job it is documented to do, and this is the number
that says so.** Nine successful CI runs harvested, per check. On a median probe the derived budget is
791 s and the suite's median total is 647.9 s. **Double the most expensive check** — `sbt-plugin-scripted`,
126.0 s normalised — and the total reaches 767.5 s: still UNDER budget, still green. A check can
double and the guard whose stated purpose is "one check that grew is the signal this budget exists
for" will not notice. Its SHARE, meanwhile, goes 18.5 % -> 31.2 %, +12.7 pp.

**And the two suite sizes this entry said were missing now exist: 78 and 79.** The 79th check to be
registered is `launcher-set-complete`, and it costs **0.0 s**. A rule that scaled the budget by the
NUMBER of checks would have credited it the average, 8.5 s — **164x its real cost**. Nine of the 79
checks cost under 0.05 s while one costs 126.0 s. Checks are not interchangeable, so counting them
cannot be the fix.

**A share is the more stable unit, measured rather than assumed: across those nine runs the share is
tighter than the second-count for 61 of 78 checks.** Host speed inflates every check together — the
suite total's spread is 74.3 s while the per-check spreads in quadrature come to only 25.2 s, which
is what a common multiplier looks like — so a share cancels it and a second-count does not.

**Honest negative, recorded because it kills the obvious version of the fix.** Replacing the
hand-fitted intercept with the sum of per-check baselines does NOT predict better: mean absolute
error 37.8 s derived versus 34.0 s fitted (max 64.9 versus 80.1). The residual is host variance the
probe does not capture — the same r² = 0.56 as before. So the win from a per-check table is NOT a
sharper total. It is that growth becomes self-accounting (a 0.0 s check adds 0.0 s) and that a single
grown check becomes visible at all.

**Landed 2026-08-10:** `tests/smoke-baseline.tsv`, 79 rows normalised to a reference host, and
`tests/smoke-baseline-harvest.sh` that regenerates it from CI. The tool READS the fit constants out
of `scripts/smoke-ci.ssc` rather than copying them, and dies if their shape moved, because a stale
normalisation biases every row silently. It reads the PRINTED probe rather than inverting the budget:
inversion is exact only inside the clamp, and one run in nine was outside it — real probe 141 ms, and
the inversion answered 160.7 with no sign anything was wrong. Self-test A/B'd against three
deliberate breakages (no clamp, accept a pinned-budget run, impute an average for a 0 s check); each
fails with its own message, and the first attempt at those controls proved nothing because the copies
sat outside the repo and died before reaching the assertion.

**Not yet done, and deliberately:** wiring the share comparison into `scripts/smoke-ci.ssc`. That
file is held by a live claim, `interpreter-test-fast-slow-split`, whose whole purpose is to change
which interpreter tests run on the push path — that is, to change the suite's SHAPE. Refitting a
baseline to a shape that is about to change is the same mistake as fitting it at one size. The table
regenerates in one command once that lands. Decided with the project owner: the budget self-scales
and the per-check share is reported but does NOT fail the suite until there is enough data to know
its real spread.

**MEASURED AGAIN 2026-08-08, and this time the cause is neither of the two below. Run 31253238252:
72/72 green, 634.9 s of the 600 s cap, `freeze-consistency` 3.0 s.** The cold-resolve cause recorded
below is dead — that check is 3 s, not 95 s. What changed is that `sbt-plugin-scripted` went from
**0.0 s to 102.9 s on this path**, because `smoke-sbt-setup-skipped-on-cache-hit` was fixed: on a
cache HIT the sbt setup used to be skipped and both sbt checks failed instantly. The suite's real
cost on a cache-hit push was always ~635 s; the breakage made it look like 528 s by not doing the
work.

Which means the cap is doing exactly the job this file's own note describes — *"One check that grew
is the signal this budget exists for"* — and the honest reading is that the push path has been over
its stated budget for as long as that step has been broken.

| module | this run |
| --- | --- |
| `tests` | 384.2 s of 634.9 s (60 %), of which `sbt-plugin-scripted` 102.9 s |
| everything else | 250.7 s |

**AND 750 s AGED THE SAME WAY, in two days. STEP 2 IS DONE 2026-08-10: the budget is no longer a
number.** The suite grew 72 → 78 checks and started failing runs with every check GREEN — 755.5 s,
760.3 s, 775.5 s against 750. A fourth raise buys the same few weeks.

Twelve CI samples now exist, which is what step 1 said to wait for. All at 78 checks, so the spread
is host speed and nothing else:

```
probe ms   161   166   168   172   184   191   218   218   234   247   259   288
suite  s  597.7 695.3 683.4 594.1 669.4 600.2 748.4 775.5 760.3 771.6 743.2 755.5
```

Least squares `suite ≈ 435.7 + 1.263 × probe`, r² = 0.56 — the probe explains just over half the
spread, which is why the rule carries an 80 s margin rather than trusting the line.

**Why relative, when a bigger absolute also stops the flapping.** Both cost the same in false reds on
those twelve runs — absolute 850 and relative+80 each fail 0/12, where today's 750 fails 4/12. They
differ in what they let PAST, which is the only thing a budget is for: absolute 850 is blind to
growth of 74–256 s (median 155), relative+80 to 16–157 s (median 79). It tightens to 719 s on a fast
runner instead of carrying slow-day slack everywhere.

**CLAMPED TO THE SAMPLED RANGE, a correction that only running it produced.** The samples span
161–288 ms; a dev host under sibling builds probed 1289 ms and the un-clamped fit answered 2144 s — a
budget so large it stops being a guard exactly when a bloated suite could hide behind a slow machine.
Beyond the range the line is extrapolation, not measurement, so the rule never exceeds 879 s.
`SSC_SMOKE_BUDGET` still pins an absolute number.

**This entry stays OPEN.** The rule is fitted to twelve samples at ONE suite size; the next honest
step is refitting when the suite changes shape, and nothing yet forces that.

**Previously: decided by the project owner, asked with the numbers in hand: RAISED to 750 s on
2026-08-08.** `scripts/smoke-ci.ssc` says a raise "was refused twice before by agents including me,
and correctly: an agent lifting a cap on its own work is how the cap stops meaning anything", and
both prior raises (500 s, 600 s) were the owner's, so this one was put to them rather than applied.
The alternative offered and not taken was moving `sbt-plugin-scripted` (16 % of the run) to the sbt
job, which would have held the suite at ~532 s at the cost of catching a plugin regression off the
push path.

750 s sits ~115 s above the observed maximum (580.4 s cache miss, 634.9 s cache hit), which is the
same "above the maximum with room for the spread" rule the 420 s cap was fitted by. **This entry
stays OPEN:** 750 s is headroom for 72 checks, not an answer to what the push path should contain.

**MEASURED 2026-08-07, and the framing below is WRONG. It is not "the suite is too big" — it is ONE
check and ONE missing cache path.** Thirteen consecutive runs, suite total against
`freeze-consistency` alone:

```
suite 376.5 421.6 463.7 469.3 471.8 473.0 474.7 479.1 482.9 484.8 │ 625.4 625.5 626.5
fc      8.1   8.1  10.6  11.0  11.4  11.9  12.3  12.4  12.8  10.6 │  95.5  95.6  96.3
```

**A perfect split with nothing in between.** Every run under budget has that check at 8–13 s; every
run over it has the same check at 95–96 s, clustered within 1.1 s of each other. Across the same
pairs the other 68 checks differ by an ordinary ~15 % host factor (`run-lane-flags` 54.1→63.9,
`import-alias` 39.1→44.0). 84 s of one check IS the breach.

Two hypotheses tested and REFUTED before the third was accepted: the slow runs did NOT touch
`build.sbt` or `project/*` (so it is not the cache KEY), and they are NOT a contiguous window —
03:57 fast, 03:58 slow, 04:01 slow, 04:02 fast, minutes apart. It varies per run.

**Cause.** `freeze-consistency`'s invariant I5 shells out to
`scala-cli tests/conformance/contract.sc -- --list`, and `tests/conformance/.scala-build` — where
scala-cli keeps that compiled script — **was in no cache path**. Only `~/.cache/coursier`,
`~/.ivy2/cache` and `~/.sbt` were. So the script is resolved and compiled per run, at ~11 s when
coursier covers the dependencies and ~96 s when it does not.

The cost was known and written down at the call site ("0.49 s warm … a cold resolve-and-compile on
a runner … a 47x ratio") when I5 was added. It was documented and not removed, and the entry below
then spent four rounds attributing the breach to the suite's size.

**Fix (this claim):** cache `tests/conformance/.scala-build`, and compile `contract.sc` once in a
named workflow step AFTER the cache restore — where a dependency resolve is visible setup rather
than 84 unlabelled seconds inside a check being timed against a 600 s budget. `|| true`, so a
warm-up that cannot run degrades to today's behaviour instead of failing the push.

**CONFIRMED ON CI (run 31154784901, `25607a5d2`), and on the hard case:**

```
Cache Coursier/sbt                    1 s   <- a MISS, nothing restored
Warm the conformance contract build 131 s   <- the cold resolve, paid as visible SETUP
  ok  freeze-consistency             12.1 s <- was 95-96 s on exactly this kind of run
checks: 69/69 green    470.1 s of 600 s budget
```

That run's cache missed, which is precisely the condition that used to produce ~96 s and a ~625 s
suite. It came in at 470.1 s. The 84 s did not vanish — it moved out of the budget and acquired a
name.

**Honest limits of this measurement.** One completed run carries the new step so far, so the
mechanism is demonstrated and the DURABILITY is not. And the warm-up itself varies 40–131 s across
runs, because caching `.scala-build` does not help when the coursier cache is the thing that missed;
that variance is now in setup, where it belongs, rather than in a budgeted check.

**What this does NOT settle:** whether 600 s is the right cap for 69 checks. That question is real
and is what the entry below is about — but it was never what turned main red, and the entry stays
open for it rather than being closed on this.

---


**RAISED TO 500 s ON 2026-08-04, by the project owner's instruction, and this entry STAYS OPEN.**
The 420 s cap was fitted when the suite held 18 checks; it holds 58, and with runner variance at
±14 s it had no headroom against its own noise — it failed run 30905783511 at 428.8 s with every
check green. 500 s buys room for what the suite contains today and answers nothing about what it
should contain, which is what this entry is actually about.

Recorded rather than applied quietly because raising it was refused twice before, including by me,
and that refusal was right: an agent lifting a cap on its own work is how a cap stops meaning
anything. The structural work that belongs to this entry was done separately and is not replaced by
the raise — three of the five most expensive checks were mine and are now 88 s → 10 s and
104.5 s → 3 s, the last measured on CI where it cost 104.5 s against 5.5 s locally.

**REOPENED the same day. Closing this was premature and the reason is worth more than the fix
was.** Four runs, 2026-08-03:

| run | total | verdict | what changed since the previous row |
|---|---:|---|---|
| 30798878836 | 437.9 s | RED | — |
| 30799937285 | **355.8 s** | green | `bench-seed-type` (45.4 s) moved to tier 2 |
| 30839675049 | 418.6 s | green | +`std-import-lanes` 17.7 s, +`build-smoke` 4.4 s, +`route-handler-shapes` 45.9 s |
| 30840744973 | **425.6 s** | RED | `std-import-lanes` trimmed 17.7 s → 10.9 s |

**Read the last two rows together: the suite got 7 s SLOWER across a change that only removed
work.** That is the finding. Runner variance here is ±14 s, so a cap with less than ~20 s of
headroom flaps regardless of what is in the suite — and 420 s for 60 checks does not have 20 s of
headroom. Every "fix" that shaves ten seconds off the newest arrival is inside the noise.

**What was done anyway, because main was red:** `std-import-lanes` and `build-smoke` — the two
newest checks, both mine, both added that morning — moved to tier 2, which is also per-push.
Rule applied: *when a shared budget is exhausted, the additions that arrived last leave first.* It
is the only rule an author can apply to their own work without a negotiation, which is what made it
possible to act at all while everyone's pushes were failing. It buys ~15 s. It is not a fix.

**What this actually needs, and it is not another trim.** The five most expensive checks are
`route-handler-shapes` 45.9 s, `render-lane-builtins` 42.0 s, `corpus-lane-breadth` 34.1 s,
`launchers-not-dead` 30.9 s, `no-test-reaches-an-exiting-cli` 27.9 s — 180.8 s of 425.6 s between
them, each with an owner. Either several move to tier 2, or the suite's design point is restated
honestly: it was built for 27 checks and ~157 s, it now has 60. Raising `SSC_SMOKE_BUDGET` is still
refused for the reason this file has always given, but "the cap is correct and the suite is too
big" is a claim someone has to actually decide, rather than each of us shaving our own newest gate.

---

**The first close, kept because its measurement is still valid:**

| run | sha | checks | total |
|---|---|---|---:|
| 30798878836 | `cbecbec42` | 58/58 green | **437.9 s** — RED |
| 30799937285 | `8b0ee9bb3` | 58/58 green | **355.8 s** — green |

`bench-seed-type` moved to tier 2 and `js-selfcall` was registered in the same commit, so the check
COUNT is unchanged at 58 and the suite still answers the same questions per push. The measured
saving is 82.1 s, against the 37 s I projected — the difference is runner variance, and it is the
reason the projection was written as a projection.

**Found 2026-08-03** by `legacy-object-apply`, from a CI red on a commit whose diff was one front's
uid chain. Nothing was broken:

```
checks: 57/57 green    433.0s of 420s budget
OVER BUDGET — the push path is what this suite exists to keep short.
##[error]Process completed with exit code 1.
```

**All 57 checks passed and the run still failed**, so every push fails until the total comes down.
Locally the same tree measured **300.2s**; the CI runner is slower, which means the local reading
cannot warn anyone — the budget is only ever breached where it is enforced.

**The suite grew from 32 checks to 57 during 2026-08-01/03**, several agents adding gates in
parallel, each one individually cheap and correct. No single commit is at fault, and that is the
point: a per-push budget is a SHARED resource with no owner, so it is spent until it runs out.

Slowest checks in that run:

| s | check |
|---:|---|
| 45.4 | `bench-seed-type` |
| 45.3 | `submodule-gitlinks-resolve` (network — one `git fetch` per gitlink) |
| 42.5 | `render-lane-builtins` |
| 33.9 | `corpus-lane-breadth` |
| 31.8 | `launchers-not-dead` |
| 28.3 | `no-test-reaches-an-exiting-cli` |

Those six are 227s — over half the run.

**Deliberately not fixed by raising `SSC_SMOKE_BUDGET`.** The suite's own message says that is how
the old 13.4-minute path happened, and it is right: the budget is the only thing that has been
holding the line. The fix is to cut or speed up a check, and WHICH check is a decision with an
owner, not something to take unilaterally while everyone's pushes are red.

**TAKEN 2026-08-03 by the owner of candidate 2's first half.** `bench-seed-type` is mine — added in
that same 2026-08-01/03 window — and it was the most expensive check in the suite. It now runs in
tier 2 of `ci.yml`, next to `bench-wrapper-gate.sh`, which is **also per-push**: the per-commit
answer is unchanged, it simply comes out of a 14-minute budget instead of a 7-minute one.

Measured per lane before deciding, rather than cutting the cheapest thing to cut:

| lane | local |
|---|---:|
| `ssc` | 2.2 s |
| `js`  | 2.2 s |
| `jvm` | 11.6 s |

The jvm cells are 72% of it and are **not** separable from the property — the jvm wrapper is where
the defect was, and the Long fixture on that lane is the control that says the gate still works. So
the honest choice was WHERE it runs, not whether. The argument for moving it is not that it is
expensive but that what it gates is a MEASUREMENT APPARATUS: a regression there costs a benchmark
table, not a build, and 45.4 s is 29% of a signal designed to take ~157 s.

Landing this frees 45.4 s of the 433.0 s run, and spends ~8 s of it on `js-selfcall`
(`js/BUGS.md js-worker-source-joined-with-literal-backslash-n`), which is registered in the same
commit. Projected ~395 s. **That is a ~25 s margin, which is not much** — the structural finding in
this entry stands untouched: a per-push budget is a shared resource with no owner, and one gate
moving out does not give it one.

**Not touched, deliberately:** the local/CI gap (300.2 s vs 433.0 s, x1.44). `scripts/smoke-ci.ssc`
already decided this question in writing — the budget fails on CI and only warns locally, the host
probe prints and is explicitly `informational — the budget is still absolute`, and the file records
what happened the last time a cap was fitted to local numbers (300 s, guessed from a dev machine,
red on the first run). Adding a calibration factor on top of that would be overruling a documented
decision from outside, on one data point.

**The remaining candidates**, still offered rather than taken:

1. `submodule-gitlinks-resolve` is 45s of NETWORK and its own comment says a slow network must read
   as "this run could not tell". It is a strong candidate for the nightly rather than the push path.
2. `render-lane-builtins` (42.5 s) — `bench-seed-type` was the other half of this line and is done.
   One correction to the original wording: it is not true that `bench-seed-type` gates nothing the
   corpus lanes cover, because the corpus lanes do not run `ssc bench` at all. It was moved on cost
   and proportion, not on redundancy.

I added two gates in that window (`js-shaker-effectful-binding` 0.7s, `v2-char-numeric-position`
4.6s, 5.3s together). Naming that because "someone else's checks are the slow ones" is exactly the
reasoning that spends a shared budget.

**The suite now names its own worst check (2026-08-05).** Every overrun printed "read the per-check
timings above: one check that grew is the signal this exists for", and then left the reader to scan
sixty lines for it. It now prints the three most expensive checks with their SHARE of the run:

```
most expensive checks
  tests run-lane-flags-are-flags       51.6s   8% of the run
  tests import-alias                   42.2s   6% of the run
  scripts claim-scope-hierarchy        28.0s   4% of the run
```

A share, not a comparison with a previous run, and not a raw second-count: this file's own host-speed
probe measured the same runner varying up to 1.4x day to day, so "slower than last time" is usually
the host. A share is scale-free — uniform inflation leaves it unchanged, one check growing moves it.

The first run with it printed 8% at the top, which reads immediately as "nothing dominates, the host
is loaded". Compare `launcher-input-digest` earlier the same day: 227 s, **27%** of the run and 3x
the next check. Finding that took ranking a CI log by hand; the ranking now costs nothing.

This entry stays open: what the suite should CONTAIN is still the question, and the budget number is
still a proxy for it.

## orphaned-e2e-gates-52 — 52 of 126 gates were invoked by nothing, and 33 of those do not pass
<!-- status: open
     lane: apparatus
     area: build
     kind: apparatus
     gate: tests/e2e/no-orphan-gates.sh -->

> **THE DETECTOR'S OWN BLIND SPOT, found 2026-08-14 while landing a fixture into one of these.**
> `no-orphan-gates.sh` scans `tests/e2e/` and nothing else — its first line says so. `v2/backend/check.sh`
> is a gate by every definition the entry uses (it runs every `v2/conformance/*.coreir` fixture through
> the VM and diffs four generators against it, and it exits non-zero on a mismatch), it is invoked by
> NO workflow, NO suite and NO other script, and the detector cannot see it because of where it lives.
> So the count above is a floor for `tests/e2e/`, not a census of the repository's gates.
>
> Not fixed here, and the reason is a real cost rather than a shrug: the harness compiles a Rust crate
> and a wasm module per fixture and takes minutes, so wiring it into `scripts/smoke-ci.ssc` spends the
> smoke budget this project already treats as a cap. It wants either its own scheduled workflow or a
> `--fast` subset — a decision, not an oversight. Recorded so the next person counting orphans knows
> the number is scoped to one directory.

**Measured 2026-08-02.** `tests/e2e` holds 126 scripts. 52 of them are referenced by no workflow, no
suite, and no other script — only, at most, by prose in a `.md`. Running all 52 with a built
launcher, 180 s timeout each, 1604 s total:

| outcome | count |
|---|---|
| pass | 19 |
| fail | 26 |
| hang past 180 s | 6 |
| usage error | 1 |

The 19 that pass are now wired — the twelve cheapest (≤7 s) into `scripts/smoke-ci`, the seven
costlier (14-59 s) into tier 2 in `ci.yml`. This entry is the remaining 33.

**Why they are not simply deleted.** Only two referenced paths that are all gone, and one of THOSE
passes (`indent-layout-v2-smoke.sh`) — so "the subject is gone" is not the explanation. A first
count said 33 gates had dead subjects; that was a bug in the counting loop, and the real number is
two. Nor are they wired red: a gate nobody asked for that fails on arrival is how a suite becomes
noise people learn to ignore.

**RE-TRIAGED 2026-08-02 — the first split above was wrong in two places, and both errors pointed
away from the cause.** The gates did not rot from one product bug. They rot from TWO mechanical
repo changes that landed in front of everything else, and the second one hid a genuine regression.

**Layer 1 — `ROOT` is one `..` short, in 22 of them.** `ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." …)"`
resolves to `tests/`, so `$ROOT/bin/ssc` is `tests/bin/ssc`, which has never existed. `d0665660a`
("group top-level dirs into … tests/") moved the gates a level down and did not adjust it. This is
the whole of the "EMPTY result where content was expected" category — the command was never found,
so stdout was empty. Not, as this entry read, "the pipeline stopped producing".

**Layer 2 — four of the five shipped launchers were DEAD.** `sscc`, `jssc`, `ssc-js` and `ssc-wasm`
each resolved `SSC="$SCRIPT_DIR/ssc"` and exec'd a subcommand the STANDARD ssc refuses
(`compile-jvm`, `emit-js`, `emit-wasm`); `ssc-spark` failed a step later on
`unknown standard run option: --backend`. On a two-line hello-world, every JVM lane and every JS
lane in `tests/e2e` died at its first call. Fixed: all five resolve `bin/ssc-tools`. Guarded by
`tests/e2e/launchers-are-not-dead-on-arrival.sh`, wired into `scripts/smoke-ci`.

Thirteen gates also called `render` / `bundle` / `build` / `site` through `bin/ssc` after those
moved to the optional tier; they now call `bin/ssc-tools`, which is what `StandardMain` tells you
to do when it declines.

**The six filed as HANGING do not hang.** Each has a `wait_for_server` deadline of 60-90 s and runs
three backends, so the worst case is 180-270 s — past the 180 s census timeout, which cut them off
mid-run and made a slow failure look like a hang. That single mislabel is why nobody read the
actual error for six gates. They now also FAIL FAST: `wait_for_server` takes the server's pid and
returns immediately once the process is gone, instead of polling a corpse for a minute. Measured
against a deliberately-killed launcher, `fm-routes-smoke` went 128 s -> 10 s, and the message says
"the server process EXITED before it listened" rather than "did not start within 60s", which was
the lie that produced the hang diagnosis in the first place.

**Layer 3 — what is left under both is real, and small.** One product bug now accounts for
`build-smoke`, `bundle-smoke`, `render-smoke` and `components-smoke`'s INT lane: filed as
`int-v1-lane-loses-a-builtin-companion-to-its-own-case-class` reduced to four lines.
On the v1 interpreter lane the imported `case class Response` overwrites the builtin companion, so
`Response.html` dies while `Response(200, Map(), "x")` works. NOT a render bug and NOT invoke-time:
`ssc-tools run --v1` reproduces it at a file's top level with no route and no handler. The first
filing said otherwise on both counts and is corrected in the entry.

**NOW WIRED (9).** `nested-build`, `site`, and the seven `std-ui-*` smokes pass under both fixes
and are in `scripts/smoke-ci`, at <=1 s each. Each was checked NOT to pass vacuously — mutating the
subject (`NavBar` -> `NavBarXX` in `examples/std-ui/nav-demo.ssc`) turns `std-ui-nav` red.

**STILL RED (13), and now for readable reasons.** All 22 together run in 89 s, where the six
"hangers" alone used to consume 180-270 s each. `build` / `bundle` / `render` / `components`(INT)
are the one filed interpreter bug; the rest report real product differences at their first
assertion rather than dying on a missing path.

**RE-CENSUS 2026-08-03 — the table below is a snapshot of 2026-08-02 and is now wrong in five
rows.** Re-ran all 13 against HEAD with a freshly built launcher:

| gate | then | now |
|---|---|---|
| `build` | red (interpreter companion bug) | **PASSES** — wired into `scripts/smoke-ci`, ~2 s |
| `components`, `middleware`, `upload` (JVM) | `the server process EXITED before it listened` | that was a RESOLUTION failure, not a serving one — fixed, see below; `components` now reaches `JVM artifact written` and fails later |
| `middleware` (INT) | `native callback arity` | `body=DataV(Stub,Vector(StrV(Response.withHeader)))` — the sibling-filed `native-Response-withHeader-is-a-Stub` |
| `validation` (INT) | `native callback arity` | `unbound global: requireInt` — the sibling-filed `native-requireInt-unbound-in-a-route-handler` |
| the other 8 | unchanged | unchanged |

**The shared cause under three of them was NOT what any of them reported.** `compile-jvm` refused
`[httpGet](std/http.ssc)` from a file that `run --v1` and `run-js` both accepted — a std-root
resolution defect in two layers, filed and fixed as
`BUGS.md compile-jvm-and-std-root-disagree-on-where-std-lives`, gated by
`tests/e2e/std-import-lanes-gate.sh`. Every one of the three reported it as a SERVING failure,
because the gate's own harness could only see that the server never listened. A third copy of the
same mistake lives in `BundleCommand` and is filed separately as
`bundle-command-resolves-imports-relative-only`.

**Method note, since this entry is partly about counting:** the previous table's rows for
`middleware` and `validation` were accurate when written and were invalidated by a SIBLING's fix
landing in between, not by an error. A census of failure messages has a shelf life measured in
commits; re-run it before acting on it.

**RE-CENSUS 2026-08-06 — thirteen red became ZERO.** Re-ran every gate this entry has ever listed as
red, against HEAD with a fresh build:

| gate | now |
|---|---|
| `build`, `bundle`, `components`, `import-alias`, `std-ui-forms`, `upload`, `url-import`, `validation` | **green** |
| `middleware` | **green 2026-08-06** — the signature mismatch is fixed; see `v1/runtime/backend/jvm/BUGS.md` |

Everything except `middleware` was closed by the causes this entry chased down rather than by the
gates being changed: the jvm `Cons` lowering and its scope/`__extern__` siblings, the json and
session host bridges, the extern-import filter, the triple-quoted-literal lexer fix, and native
multipart. `middleware` is the one filed cause still open —
`v1/runtime/backend/jvm/BUGS.md`'s middleware section: `std/http.ssc` declares
`use(fn: (Request, () => Response) => Response)` while the jvm host defines
`(Request, () => Any) => Any`, so `next()` infers `Any` and `.withHeader` is unreachable.

**The shelf-life warning this entry already carried proved itself twice.** The table below is the
2026-08-02 snapshot; it was wrong by 2026-08-04 and is wrong again now. Read it as history, not as
state — the row that matters is the one above.

**WHAT THE 13 RED ONES SAID ON 2026-08-02** — kept for the record:

| gate | first failure |
|---|---|
| `build`, `bundle`, `render`, `components`(INT) | `int-v1-lane-loses-a-builtin-companion-to-its-own-case-class` — filed, reduced to 4 lines |
| `middleware`, `validation` | `500 native HTTP handler failed: native callback arity` |
| `fm-routes`, `health-defaults` | **RESOLVED 2026-08-04 — three causes, only one of them the product.** Both gates now exit 0. (a) They ran `$BIN/sscc` as "the JVM backend"; `sscc` is `ssc-tools compile-jvm`, which COMPILES and exits — "the server process EXITED before it listened" was the literal truth about a compiler, and the JVM lane works fine via `run-jvm`. (b) They labelled `$BIN/ssc` INT; it is `StandardMain`, the NATIVE lane, so failures went to the wrong owner. (c) Body extraction used `head -n -2`, a GNU extension that BSD/macOS rejects outright, so `body` was ALWAYS empty and every body assertion failed on every lane regardless of what the server sent. What remains after fixing the instrument is ONE product gap on ONE lane, filed as `v2/BUGS.md native-lane-ignores-declarative-route-registration` and declared `[KNOWN GAP]` in both gates. |
| `package-keyword` | `ssc: unbound global: org` — the `[org](./cards.ssc)` import does not bind |
| `import-alias`, `url-import`, `std-ui-forms`, `upload` | INT-lane content mismatches, each unreduced |

`v21-native-http-request-source-arity` in this file is the nearest existing entry to the
`native HTTP handler failed` pair, but it is `status: fixed` / `confirmed: no` and its symptom is
`match: no arm for`, not `native callback arity`. Close enough to check first, NOT close enough to
call the same bug — three of these gates were mis-diagnosed once already by assuming a family
resemblance was an identity.

Note `package-keyword` hid its own error behind `2>/dev/null`, which is why the census recorded it
as "empty output". Bare `ssc file.ssc` still works; the emptiness was the interpreter erroring on
stderr.

**RUNNER SWEEP 2026-08-04 — the same three apparatus defects ran through the whole set.** After
`fm-routes` and `health-defaults` turned out to be measuring a compiler, the census asked how far
that went: **8 gates ran `$BIN/sscc` as a server** and **6 labelled `$BIN/ssc` as INT**. Seven were
free to fix (`package-keyword` is held elsewhere). Corrected runners, then re-measured:

| gate | after |
|---|---|
| `import-alias` | **green** |
| `url-import` | **green** |
| `validation` | **green** — INT/JVM/JS all listen, verified distinct |
| `components`, `middleware` | jvm fails to COMPILE — `jvm-lane-cannot-compile-a-json-import`, filed |
| `std-ui-forms` | **REDUCED 2026-08-04 to four lines and filed** — not a rendering defect at all. `ssc render` died with `InterpretError: Undefined: impl`, a name in no source file, at a position that does not exist in the file it names. It is a triple-quoted literal whose CONTENT ends with a quote (`""" aria-invalid="true""""` in `input.ssc`): int, js and jvm all turn it into a **List** instead of a String, native alone is right. `BUGS.md triple-quoted-literal-ending-in-a-quote-is-not-a-string`, gated. |
| `upload` | **MEASURED 2026-08-04 per lane and filed.** Three lanes, three different answers: int throws `For input string: "-"` (a number parse on a multipart boundary), native answers `missing 'file' part` AT HTTP 200 — a wrong answer, not an error — and js is the only one that round-trips the file correctly. `BUGS.md multipart-upload-three-lanes-three-answers`. |

**The finding under two of them is bigger than the gates.** `run-jvm` cannot compile *any* program
importing `std/json.ssc` — 14 `[E007]` in the emitted Scala, every one a destructured binder typed
`Any`. `std/http.ssc` imports json, so the jvm lane could not compile an HTTP server either. Both
gates reported it as `the server process EXITED before it listened`.

**A false green I produced and had to withdraw, worth recording.** The first sweep was one blanket
regex, and it broke two ways at once. Helpers taking positional args got `expected` shifted into the
wrong slot (`expected: --v1`); helpers invoking `"$launcher"` **dropped the added words entirely**,
so INT, JVM and JS all ran the same default lane — and `middleware` and `validation` went green for
no reason at all. Caught by checking that the per-lane logs DIFFER, which is now the thing to check
after any change to how a gate picks its lane. Reverted and redone file by file.

**Full list, by outcome.**

failing (26):
  - `actors-pingpong-smoke.sh`
  - `area-map-gate.sh`
  - `bundle-smoke.sh`
  - `import-alias-smoke.sh`
  - `nested-build-smoke.sh`
  - `package-keyword-smoke.sh`
  - `render-smoke.sh`
  - `req-type-collision-v2-smoke.sh`
  - `route-params-v2-smoke.sh`
  - `serve-view-frontend-v2-smoke.sh`
  - `site-smoke.sh`
  - `std-ui-content-smoke.sh`
  - `std-ui-data-smoke.sh`
  - `std-ui-feedback-smoke.sh`
  - `std-ui-forms-smoke.sh`
  - `std-ui-layout-smoke.sh`
  - `std-ui-nav-smoke.sh`
  - `std-ui-theming-smoke.sh`
  - `std-ui-widgets-smoke.sh`
  - `url-import-smoke.sh`
  - `v21-build-jvm-smoke.sh`
  - `v21-native-content-smoke.sh`
  - `v21-native-doc-render-smoke.sh`
  - `v21-typeclass-dictionary-smoke.sh`
  - `v21-unhandled-effect-smoke.sh`
  - `wc-card-smoke.sh`

filed as hanging (6) — they are not; see the re-triage above, they now fail in ~10s:
  - `components-smoke.sh`
  - `fm-routes-smoke.sh`
  - `health-defaults-smoke.sh`
  - `middleware-smoke.sh`
  - `upload-smoke.sh`
  - `validation-smoke.sh`

usage error (1):
  - `v21-portable-gates-smoke.sh`

**The lesson worth keeping separately from the list:** two of these were wired by hand earlier this
week and immediately caught real defects — `negtc-mapreduce-gate` proved the property the whole
negtc shard rests on and had never run, and `v21-negative-toolchain-release-gate-smoke` died at its
first line so none of its seven drift rejections ever executed. A gate that runs nowhere is not
neutral; it is a claim of coverage that is not being made.

### 2026-08-13 — THE SWEEP DID NOT HOLD, so the leak is now ratcheted shut

**Re-censused today: 183 scripts, 35 invoked by nothing.** On 2026-08-02 it was 126 and 52. The pile
was drained by 13 and refilled by new arrivals — **gates are written faster than they are wired**,
so a one-off cleanup buys a few weeks and this entry would have to be re-opened every month.

`tests/e2e/no-orphan-gates.sh` freezes today's 35 by name, fails on a NEW one, and fails when a
frozen entry stops being an orphan, so the list can only shrink. Wired into `scripts/smoke-ci`, per
push, ~21 s. Same shape as `v1-jit-size.sh`'s frozen debt.

**Verified in both directions, on real files rather than fixtures:** a planted unwired script turns
it RED naming the script; adding one reference to it turns it GREEN again; both artifacts removed.

**Three defects it caught in itself while being written, all the same family — a search that lies:**

1. **`grep` exits nonzero when a search PATH does not exist, while still printing the matches it
   found.** Under `set -euo pipefail` that made a correctly-wired script report as an orphan, with
   the evidence sitting in output the script had just discarded. The helper now decides on OUTPUT,
   never on exit status, and the self-test and the census share it so they cannot drift.
2. **IT MATCHED ITSELF.** Every frozen name is a literal string inside the gate's own file, so the
   search found that file and called the orphan wired. The first run reported **1 orphan out of 183
   and demanded 38 frozen entries be deleted as "now invoked"** — the detector had become a rubber
   stamp for exactly the list it exists to hold. Same shape as a `pgrep -f` waiter matching its own
   command line. `SELF` is excluded via `BASH_SOURCE` and the self-test asserts it.
3. **My hand census said 39, the gate says 35, and the gate was right.** Four `v21-explicit-*`
   gates are invoked through `tests/fixtures/v21-explicit-lanes/manifest.tsv` — a data file a runner
   reads — which a search of `tests/e2e` alone cannot see. A caller is not always a call site.

### The remaining 35, triaged — none of them is dead

| | count |
|---|---|
| subject file still present | 22 |
| builds its own fixture inline (no file subject) | 13 |
| subject genuinely gone | **0** |

The one that looked gone is `keyword-import-missing-module.sh`, whose subject is
`std/nosuchmodule_9d4f.ssc` — **absent on purpose**, because the gate asserts that importing a
missing module errors. A liveness heuristic reports exactly this as dead; do not delete on that
signal without reading the gate.

So "delete the rot" is not the answer for any of them: these are live gates nobody runs. The next
batch is the 2026-08-02 procedure repeated — run them against a fresh launcher, wire the cheap
passers, file the failures — now bounded, because nothing new can join the list.


### 2026-08-13, batch 1 — all 36 RUN against a fresh launcher, and a vacuity control on every passer

**20 pass, 16 fail, and the costs decide where each can live.** Ran with a 180 s cap each:

| passing, by cost | |
|---|---|
| ≤7 s | `info-unknown-flag` 6, `keyword-import-missing-module` 6, `js-char-classification-parity` 7 |
| 12-44 s | `pattern-undefined-name` 12, `member-beats-toplevel` 18, `components-smoke` 24, `middleware-smoke` 24, `health-defaults` 25, `import-parse-error` 25, `fm-routes` 26, `int-imported-registry` 30, `jvm-json-import` 34, `multi-name-val` 35, `f-front-delegation-visible` 36, `object-var-mutation` 42, `triple-quote-trailing-quote` 42, `f-char-escape` 44 |
| 76-180 s | `validation-smoke` 76, `upload-smoke` 84, `no-paren-sibling` 180 |

| failing | rc | | failing | rc |
|---|---|---|---|---|
| `wc-card-smoke` | 1 | | `serve-view-frontend-v2` | 1 |
| `actors-pingpong-smoke` | 1 | | `typeerr-names-both-types` | 1 |
| `install-sh-reports-failure` | 1 | | `v21-build-jvm-smoke` | 1 |
| `render-smoke` | 1 | | `v21-typeclass-dictionary` | 1 |
| `req-type-collision-v2` | 1 | | `v21-unhandled-effect` | 1 |
| `route-params-v2` | 1 | | `v21-portable-gates` | 2 |
| `negtc-shard-gate` | 124 | | `selfhost-front-gate` | 124 |
| `v21-native-content` | 124 | | `v21-native-doc-render` | 124 |

The failures are REAL differences, not the mechanical breakage the 2026-08-02 sweep cleared — e.g.
`wc-card-smoke` reports three missing needles (`shadow.innerHTML`, `Card.render(...)`,
`attributeChangedCallback()`), `typeerr-names-both-types` is 1 ok / 1 FAIL. Each needs its own
diagnosis; none is wired, because a gate red on arrival is how a suite becomes noise.

### The vacuity control, and the one thing it found (which was not what it looked like)

A gate that passes without asserting anything is worse than an absent one, so before wiring anything
**every passer was re-run with both launcher jars moved aside**. One mutation, all 20 answered:
**19 went RED**, as a gate that actually runs a program must.

**One stayed green: `f-char-escape-gate.sh` — and it is NOT vacuous.** It calls
`ssc_usable_or_skip`, which prints `SKIP` and exits 0 when the launcher cannot run a probe. So the
control cannot tell "asserts nothing" from "declines to judge", and reporting it as vacuous would
have been wrong. What IS worth knowing: a skip-guarded gate reports SUCCESS while testing nothing,
so it may only be wired where a launcher is guaranteed. It is the only one of the twenty with that
guard.

### Wired (3), and the list is 36 -> 33

The three ≤7 s passers went into `scripts/smoke-ci`, per push. The 12-180 s passers are **not**
homeless but they are not smoke's: smoke measured 897.7 s of a 1027 s budget on 2026-08-13, so
~600 s of extra checks does not fit. Their home is `conformance-extras` or `conformance` in
`ci.yml` — the only tier-2 jobs that build a launcher (`validate`, `lint`, `uniml` and
`interpreter-fast` do not) — and that is batch 2.


### 2026-08-13, batch 2 — the 17 verified passers are wired; the list is 33 -> 16

All seventeen went into `conformance-extras`, each with its measured cost on the line. **Not smoke**,
and the reason is arithmetic rather than taste: they total ~11 minutes and smoke was already at
765 s of a 1027 s budget. `conformance-extras` is tier 2 — no `if:`, and `ci.yml` runs on
`pull_request` plus the nightly schedule — and it builds a launcher, which they need. Cadence
checked, not assumed.

`f-char-escape-gate.sh` is wired **here and nowhere else on purpose**: it is the one gate of the
twenty that SKIPS rather than fails when the launcher cannot run (`ssc_usable_or_skip`), so on a
host without one it would report success having tested nothing. This job guarantees a launcher.

**What is left is 16, and every one of them FAILS** — they were measured in batch 1 and none is
wired, because a gate red on arrival is how a suite becomes noise. Their diagnosis is the next
batch, expected to be 3-4 shared causes rather than 16 separate ones: the 2026-08-02 sweep found two
mechanical causes explaining 22 of 26.


### 2026-08-13, batch 3 — the 16 failures diagnosed as a batch; 16 -> 13

**The prefix hypothesis was wrong and is recorded as wrong.** Six of the sixteen are `v21-*` and it
looked like one era, one cause. Their first divergences are all different — a content-binding
mismatch, "loaded a forbidden", zero mismatches with rc=2, and one with an EMPTY log. Grouping by
NAME is not grouping by CAUSE.

**One mechanical cause did explain three, and it is the same family the 2026-08-02 sweep found.**
`route-params-v2-smoke`, `req-type-collision-v2-smoke` and `serve-view-frontend-v2-smoke` set
`BIN="$ROOT/../bin/ssc"` — the STANDARD launcher — and drive `--v1`, which that tier refuses by
design:

    ssc: '--v1' requires the optional ScalaScript tools/compatibility tier; run ssc-tools explicitly

Both lanes therefore returned nothing and each gate reported **"--v1 baseline broke"** — a false
accusation against the v1 lane. The path was never wrong (`ROOT` is `tests/`, so `$ROOT/../bin` is
the repo's `bin/`); only the launcher was. One word each.

**Two are green and wired** (33 s, 34 s). **The third exposed a real defect underneath**: with the
launcher fixed, `serve-view-frontend-v2-smoke` reports `--v1: http=200 frontend=react` and
`--v2: http=000`. The v2 lane never starts listening. Filed as
`v2-lane-does-not-serve-the-content-introspection-view` with that gate as its acceptance test. It is
NOT wired, because it is red — and it was invisible for as long as the mechanical defect made both
lanes fail together.

**And one gate was never broken at all.** `selfhost-front-gate.sh` takes **289 s**; the census cap
was 180, so it was recorded as failing when it had simply been cut off. Re-run with room: **17/17**.
A timeout is not a verdict — the same lesson the `--evidence` audit had to learn about itself the
same day.

| | |
|---|---|
| explained by one mechanical cause | 3 |
| green and wired after it | 2 |
| real defect revealed underneath, filed | 1 |
| never broken, only cut off | 1 |
| still failing, causes not shared | 13 |


### 2026-08-14, batch 4 — the 12 remaining ran ALONE at a 420 s cap, and they are SIX groups

**Batch 3 left "13, causes not shared" and that was the right reading, but it is not the useful
one.** Ran all twelve still-failing frozen orphans one at a time — never inside a sweep, because
wired gates share hard-coded ports and answer each other's requests — with the cap raised to 420 s
so a cut-off could not be mistaken for a failure again. What comes back is not thirteen unrelated
product bugs. It is **seven apparatus/measurement defects and five product differences**, and only
one mechanical cause is shared by more than one gate.

| group | gates | what it is |
|---|---|---|
| the gate outlived its runner | `actors-pingpong-smoke`, `wc-card-smoke` | apparatus, shared cause |
| infrastructure moved in front of the subject | `install-sh-reports-failure-gate` | apparatus — **fixed and wired** |
| fails without saying why | `v21-build-jvm-smoke`, `v21-typeclass-dictionary-smoke` | apparatus |
| a precondition the tree does not satisfy | `v21-portable-gates-smoke` | apparatus/environment |
| ~100x more expensive than its own header claims | `negtc-shard-gate` | measurement |
| a real product difference, one each | `render-smoke`, `typeerr-names-both-types`, `v21-native-content-smoke`, `v21-native-doc-render-smoke`, `v21-unhandled-effect-smoke` | product |

**THE ONE FINDING WORTH THE WHOLE BATCH: a gate can stop reaching its subject without anything
changing in the gate or in the subject.** `install-sh-reports-failure-gate` guards `scripts/BUGS.md
install-sh-exits-0-when-sbt-project-load-fails` — install.sh must not report success for a build
that produced nothing. The witness is **intact**. What broke is the route to it: the toolchain cache
landed in install.sh on 2026-08-09, three days after the gate, and on a `launcher-input-digest` HIT
it restores `bin/lib` and skips `sbt cli/installBin` entirely, which is where the witness lives.

| arm (throwaway clone, one variable) | install.sh | exit | the gate concluded |
|---|---|---|---|
| cache ON, stub sbt exits **0** | HIT, sbt never called | 0 | "the defect is BACK" — **false red** |
| cache ON, stub sbt exits **1** | HIT, sbt never called, died at the LATER `sbt-plugin publishLocal` | 1 | "row 1 holds" — **false green**, right answer about the wrong command |
| cache OFF, stub sbt exits **0** | built, witness fired | 1 | the subject, intact |

One row a false red and the other a false green, from one cause, and neither had anything to say
about the witness. **The exit code cannot separate the two situations; the mechanism string can** —
so the gate now asserts, before returning any verdict, that the run printed `Staging ssc …` and not
`cache HIT`, and each row asserts the words of the guard it claims to exercise. It also asserts it
left `bin/lib/.build-stamp` untouched: the HIT path does `rm -rf bin/lib` plus a 176 MB restore, in
whichever tree invoked the gate, twice per run — this gate was mutating the shared checkout and
nothing said so. Deleting the witness from install.sh turns row 2 red and leaves row 1 green, which
is the control that says the gate covers the defect and not merely the environment. Wired into
`conformance-extras` at 2 s; frozen list **13 -> 12**.

**The shared mechanical cause explains two, and it is the oldest one in this entry.**
`actors-pingpong-smoke` and `wc-card-smoke` still drive `scala-cli run "$ROOT/compiler"
--main-class scalascript.cli.ssc`, a project that no longer exists anywhere in the repo, with
stderr sent to `/dev/null`. So every assertion is made against an EMPTY string: `actors-pingpong`
reports all three lanes (INT, JS, JVM) producing nothing, and `wc-card` misses **all nine** needles.
They are the only two scripts in `tests/e2e` that still reference it — measured, `grep -rl
'ROOT/compiler\|main-class scalascript.cli.ssc' tests/e2e` returns exactly these two — and they are
both orphans, which is *why* the rot survived: nothing ran them.

**Two gates fail and do not say why, which is its own defect.** `v21-typeclass-dictionary-smoke`
exits 1 having printed **nothing at all**. `v21-build-jvm-smoke` writes its three JVM artifacts and
then dies silently at a bare `grep -E … >/dev/null` under `set -e` — the assertion is that a JVM
stack trace carries `ssc.gen.Entry…(source-map-failure.ssc:4)`, and when it does not, the gate has
no message to give. A gate whose failure is unreadable costs a diagnosis every time it fires.

**Three of batch 1's rows are corrected by re-measurement, all in the same direction.**

| gate | batch 1 (180 s cap) | today (420 s, run alone) |
|---|---|---|
| `v21-native-doc-render-smoke` | rc 124 — recorded as a hang | **fails in 259 s** with a message: `standard run loaded a forbidden compatibility/parser class` |
| `wc-card-smoke` | "three missing needles" | **nine of nine** miss — the bundle is empty, the compiler never ran |
| `negtc-shard-gate` | rc 124 at 180 s | **still no verdict at 420 s**, checks passing throughout — and its own header says *"Cheap by construction … runs in seconds"*. This is not a slow gate, it is a gate that has quietly become two orders of magnitude more expensive than what is written at the top of it, and nobody could see that because nothing runs it |

The first two were misread because a cap was treated as a verdict, which is the third time that
mistake appears in this entry (the 2026-08-02 "six hangers", `selfhost-front-gate` in batch 3, and
now these). **A census cap belongs in the output as a third outcome, never folded into "red".**

**The five product differences, at their first divergence**, each needing its own entry and none of
them wired while red:

| gate | first divergence |
|---|---|
| `render-smoke` | headless render 4076 bytes vs served 54 — the served path answers with something that is not the page |
| `typeerr-names-both-types` | 1 ok / 1 FAIL: `curried-three-clauses` wanted `cannot unify tuple: () vs (Int -> t…)` and **got `6`** — the program ran and printed a value where a type error was expected |
| `v21-native-content-smoke` | `unexpected binding output` — the content-binding render disagrees with its golden |
| `v21-native-doc-render-smoke` | `standard run loaded a forbidden compatibility/parser class` (the standard-tier class allowlist) |
| `v21-unhandled-effect-smoke` | 2 ok / 1 FAIL: `bridge ASM x402 Op` gives `ssc: unbound global: Network` where an `unhandled runtime effect: Wallets.metaMask` rejection was expected |

| | count |
|---|---|
| apparatus, fixed and wired this batch | 1 |
| apparatus, one shared mechanical cause | 2 |
| apparatus, fails without a message | 2 |
| apparatus/environment precondition | 1 |
| no verdict — a duration to measure | 1 |
| real product differences, one each | 5 |
| **frozen orphans after this batch** | **12** |

### batch 4, part 2 — the two SILENT gates now speak, and both were RIGHT

Neither needed a product fix to become useful; they needed to say what they saw. Both were
diagnosed by re-running under `bash -x`, which is the cost a mute gate imposes on every reader.

**`v21-typeclass-dictionary-smoke`** died at `[[ ! -s "$tmp/focused.vm.err" ]]` — stderr was not
empty. What was in it:

    ssc: F did not lower this file; compiled with the default front instead — … [match: no arm for Cons/2]

Both programs it runs (the fixture AND `examples/typeclass.ssc`) are declined by F and compiled by
the reference front; `ssc info --front-report` says `GAP  match: no arm for Cons/2`. The assertion
was correct and the finding is real. Filed as
`f-front-cannot-lower-a-typeclass-dictionary-and-says-so-only-on-stderr` — **and note what it says
about the existing census**: `f-front-silent-delegation-hides-coverage-gaps` measured 26 of 329 by
looping over `tests/conformance/*.ssc`, a population that contains neither `examples/` nor
`tests/fixtures/`. Its number is true about the conformance corpus and was read as true about the
repo.

**`v21-build-jvm-smoke`** died at the source-map assertion, and the defect under it is user-facing:
a jar built from a two-line program throws, and **not one of the 29 frames names that program**.
Every `.ssc` attribution points into `json.ssc` (15 distinct lines of it). The `ssc.gen.Entry`
frames are present, so the map is emitted — it attributes the user's frames to the callee's file.
Filed as `jvm-artifact-stack-trace-never-names-the-users-own-file`.

**What changed in the two gates**, and one trap worth copying:

* an `ERR` trap in each, naming the line and the command — one line covering twenty bare assertions,
  instead of twenty hand-written messages that would drift from what they assert;
* the stderr assertions PRINT the stderr they reject; the source-map assertion prints the frames and
  a histogram of the files they name;
* **`set +e` is not enough once an ERR trap exists.** `v21-build-jvm-smoke` runs a jar it EXPECTS to
  exit non-zero, inside a `set +e` block. An `ERR` trap fires on any non-zero command independently
  of `-e`, so adding the trap turned a deliberate failure into a gate failure on its first run. The
  trap is disarmed and re-armed around that block, and the comment says why.

Neither gate is wired: both are red on a real defect, and a gate red on arrival is how a suite
becomes noise. They are the acceptance tests of the two entries filed above, which is the state that
makes them claimable.

| after part 2 | |
|---|---|
| silent gates that now report | 2 |
| product defects they were hiding, filed with a gate | 2 |
| wired | 0 — both red on a real defect |
| frozen orphans | 12 |

### batch 4, part 4 — the one SHARED mechanical cause, and it hid no product defect at all

`actors-pingpong-smoke` and `wc-card-smoke` were the whole of the "outlived its runner" group, and
they were the only two scripts in `tests/e2e` still naming a project that does not exist:
`scala-cli run "$ROOT/compiler" --main-class scalascript.cli.ssc`, with stderr sent to `/dev/null`
and `ROOT` one `..` short. Every assertion therefore ran against the EMPTY STRING — three backends
"producing nothing", nine needles "missing". **One dead command reads as nine product defects.**

**Pointed at `bin/ssc-tools`, both pass first try.** All nine `emit-wc` needles are present in a
149 KB bundle; all three actor backends print identical output. There was nothing underneath.

**Two expectations were corrected with evidence rather than to make a gate pass**, which is the
distinction worth keeping:

* `[exit] actor=3 reason=kill` is gone from the actors expectation. **Nothing in the tree emits
  it** — no producer in any `.scala`, `.ssc` or `.ssc0` — and the program never links or traps
  exits, so nothing should print on `exit(w, "kill")`. It is a v1.6-era trace that outlived its
  emitter. What the gate now asserts instead is the observable consequence: the killed worker's
  `receive` must NOT run, checked per lane rather than left to a diff.
* The JS arm's private expectation is gone because the divergence it existed for is FIXED:
  `"after timeout: " + None` printed `[object Object]` on JS and now prints `None` on all three
  lanes. One expectation for three lanes is the stronger assertion, since agreement is what this
  gate is for.

**And a third instance of a trap this repository has now paid for three times in one day, with a new
and nastier property.** The rewritten `wc-card` gate reported its FIRST needle missing while
`grep -qF` on the same bytes matched. Cause: `printf '%s' "$bundle" | grep -qF …` under
`set -o pipefail`. `grep -q` exits at the first hit and closes the pipe, `printf` takes SIGPIPE with
147 KB still to write, and `pipefail` makes the pipeline 141 — which `if` reads as NOT FOUND.

**The property that makes it dangerous is that the answer depends on WHERE the match is.** The eight
needles further down all passed, because `printf` had finished by the time `grep` exited. An EARLY
match is the one most likely to be reported as a miss — the worst possible schedule for noticing.
The fix is to write the bytes to a file and grep the file; the same day's other two instances were
an `awk '…{exit}'` closing a pipe under `printf`, and an `ERR` trap firing on a deliberate non-zero
exit inside `set +e`.

| after part 4 | |
|---|---|
| gates modernised and wired | 2 |
| product defects underneath them | **0** |
| stale expectations corrected with evidence | 2 |
| apparatus traps found in the rewrite | 1 (SIGPIPE under pipefail, position-dependent) |
| frozen orphans | **9** |

### batch 4, part 5 — `render-smoke`, and a known-gap note that aged into a wrong explanation

**The cause this gate documented has been FIXED for nine days, and the gate kept explaining it.** Its
comment blamed `native-lane-ignores-declarative-route-registration` — *"the served half 404s … 'Not
Found' (9 bytes, measured 2026-08-04)"*. That entry is `status: fixed`, `debe22715`, **2026-08-06**.
Because the gate is invoked by nothing, nobody re-ran it, and a dated measurement kept reading as a
current diagnosis.

**What is there now**, on a freshly built toolchain (the first reading was taken against a stale
launcher and re-taken for that reason):

    served: 54 bytes — native HTTP handler failed: arity: 3 expected, 2 given

Nothing in the program has three parameters at that call: `Alert.render(title, body, level: String =
"info")` is called with two and **the default is not applied**. Filed as
`v2/BUGS.md native-serve-does-not-apply-a-default-argument-so-every-short-call-fails`.

**One edit made the diagnosis decisive, and it is the kind worth copying.** Supplying the omitted
argument at the first call site does not fix the page — the error **MOVES** to
`arity: 2 expected, 1 given`, the next defaulted call. So it is not one bad call site; it is every
short call on that lane, surfacing one at a time.

**And the controls refute the obvious reading.** "Defaults are broken" is wrong: on `bin/ssc run` —
same native lane, no HTTP — a defaulted method called short works, in the same file and from an
imported module, at top level and inside a `def` body. The failing path is SERVE specifically
(`backend=fast` in the server log), which is where the next measurement goes.

**A minimal serve reproducer was attempted and is NOT recorded**, because it never started and
printed nothing — which means it was probably malformed rather than reproducing anything. An
artifact that fails for an unknown reason is not evidence, and the real example reproduces reliably
in 16 s.

**Not wired**, for the same reason as the two gates above it: it is red on a real defect, and it goes
green when that entry is fixed.


### batch 4, part 6 — a gate that ENCODED a defect, and predicted its own rot in prose

`typeerr-names-both-types` asserts that the type checker's unify errors name BOTH sides rather than
just the constructor. Its `curried-three-clauses` case ran `def tri(a)(b)(c)` and required
`cannot unify tuple: () vs (Int -> t6)` — a program that was **expected to fail type checking**.
`v2-three-parameter-clauses-fail-typecheck` is `status: fixed` (`6d0066d14`); the program now prints
**6**, and the case has been asserting an error that no longer exists.

**The author predicted this exactly and then wrote the opposite**, which is the part worth keeping.
The note above the case read:

> this gate asserts the QUALITY OF THE MESSAGE, not that the program works — so it keeps passing
> once that bug is fixed only if the message shape survives, **which is why the row below is the
> generic one rather than this specific text**

The row below was the specific text. **The mitigation existed in prose and not in the assertion.**

**And the self-test had gone vacuous the same way.** It planted `tri` and required the gate to FAIL;
once `tri` started working, that failure came from the program SUCCEEDING rather than from the
message being uninformative — so it would have passed against a checker that had stopped
type-checking altogether, which is the one hole a self-test here exists to close.

**Rewritten so neither can rot:** every case asserts the generic shape (`cannot unify [^:]+: .+ vs
.+` — a name, a colon, two sides), and the self-test now exercises the MATCHER on literal text in
three directions — the old uninformative message must be REJECTED, a real both-sides message
ACCEPTED, and a program's ordinary output (`6`) must not read as a type error. None of that depends
on any program staying broken.

**Two sources, both measured today rather than assumed**, and both still fail unification:

    def g(): Int = 1;            println(g(5))          ->  cannot unify Int: Int vs (Int -> t0)
    def two(a: Int)(b: Int) …;   println(two(1)(2)(3))  ->  cannot unify Int: Int vs (Int -> t5)

**Negative control on the real path:** make the first case's program legal (`println(g())`) and the
gate fails that case by name while the other still passes — so it can fail, and it says which.

Wired into `conformance-extras` with `--self-test`, 11 s. Frozen orphans **9 -> 8**.

**Noticed while choosing sources, NOT filed as a defect because it may be deliberate:**
`def f(a: Int): Int = a; println(f("x"))` prints `x` — a String reaches a parameter declared `Int`
with no complaint. Every unify error I could produce comes from application SHAPE (arity/currying),
never from an argument's type. That is either gradual typing by design or a hole, and saying which
is a language-contract question rather than something to file from a probe.

## f4-dualrun-gate-compares-F-with-ITSELF-since-the-front-flip
<!-- status: fixed
     lane: apparatus
     kind: apparatus
     area: conformance
     fixed-in: d19d5fc38
     gate: specs/v2.2-p6.5-dualrun.sh DR_SELFTEST=1 -->

**FIXED.** The baseline side now sets `SSC_FRONT=legacy` explicitly, and NEITHER side is called
"the default" any more — the default IS F, and naming the thing under test that way is how the gate
came to compare F with itself.

`DR_SELFTEST=1` plants a divergence and the gate must go RED; verified. And the corrected gate is
GREEN for a real reason: **45/45 EQUAL between `SSC_FRONT=legacy` and `SSC_FRONT=F`**, which is
information the vacuous version never produced — it could not tell agreement from self-comparison.

**Found 2026-08-01** under `ssc3-core`, while looking for the gate that would catch a change to the
F front. The gate whose entire purpose is to compare the two fronts has been comparing one front
against itself since the F4 flip, and has therefore been **vacuously green**.

`specs/v2.2-p6.5-dualrun.sh:53` runs the two sides as:

```sh
if [ "$front" = F ]; then SSC_FRONT=F "${tb[@]}" "$SSC_BIN" run "$f" > "$CAP.out"
else                                  "${tb[@]}" "$SSC_BIN" run "$f" > "$CAP.out"
```

and the launcher resolves the front as:

```scala
private def frontIsF: Boolean =                                  // RunNativeV2.scala:791
  !sys.env.get("SSC_FRONT").exists(_.equalsIgnoreCase("legacy"))
```

`SSC_FRONT=F` → F. `SSC_FRONT` unset → **also F**. Both sides are the same front. The opt-out is
`legacy`, and no side of this gate ever sets it.

**Why it went unnoticed:** the script's own header still describes the pre-flip world —
*"def = (exit, stdout) of `bin/ssc run P` — DEFAULT front (ssc1-front + ssc1-lower)"*. That was true
until `56d7d705f` made F the default (`frontIsF` opt-OUT). The gate was not changed with it, and
comparing F to F cannot fail, so nothing ever complained.

**Proof it cannot see a real divergence**, from today rather than in principle: `2d29b3e71` fixed
`new Array[T](n)` on the legacy front only. Between that commit and the F fix, the two fronts
genuinely disagreed —

```text
SSC_FRONT=legacy  ./bin/ssc run --v2   new Array[Int](3).length   ->  3
default (F)                                                       ->  1
```

— and this gate would have reported GREEN throughout, because both of its sides were the `1`.

**Fix:** the baseline side must set `SSC_FRONT=legacy` explicitly. Rename the labels while doing it,
because "default" now names the thing under test rather than the reference, and that ambiguity is
what made the wrong comparison read as the right one. `--self-test` should plant a divergence and
require the gate to fail (P-6.1): a gate nobody has watched fail is a hypothesis, and this one had
been a hypothesis for the entire life of the flipped tree.

Note the interaction with the F4a delegate-fallback, which is what makes this expensive rather than
merely wrong: where F cannot lower a file it silently delegates to the reference front and the
program still prints the right answer. So a broken F shows up as neither a wrong answer nor a
failing gate — only as a trace line under `SSC_FRONT_TRACE=1`, which nobody sets.

## ci-status-guard-races-the-shared-repo-index-lock — a smoke check that fails on a busy host
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 7f6c81efb
     gate: tests/e2e/ci-status-guard.sh -->

**Fixed 2026-08-13 in `7f6c81efb` — and the root cause this entry stated is REFUTED.** Both halves
matter, so both are recorded.

**What was refuted.** The entry said `git worktree add` takes the shared index lock. It does not:
it writes `.git/worktrees/<name>/`, and the lock in the reported error is that new worktree's OWN
index lock, not the repository's. Three mechanisms that could produce the reported `File exists`
were built and run, and none reproduced it:

| manufactured occupant | iterations | failures |
|---|---|---|
| two concurrent `worktree add`, same basename, empty repo | 20 | 0 |
| the same, in a full 7 688-file checkout (a wide window) | 5 | 0 |
| a **prunable leftover** holding the basename, then two racing adds | 5 | 0 |
| one `add` against a 400-iteration `worktree prune` loop | 6 | 0 |

git allocates `claim-wt`, `claim-wt1`, `claim-wt2`… without colliding, and `worktree prune` cannot
be the racer either — it honours `gc.worktreePruneExpire`, three months by default, so it never
touches a registration made seconds ago. **The 2026-07-31 failure remains unexplained.**

**What was proven instead, and is why this closes anyway.** The shared-state dependence the entry
points at is real and cost something every day, independently of the race:

- **The check littered the shared repository permanently.** Found on 2026-08-13: `claim-wt`,
  `claim-wt1`, `claim-wt2` registered from 09/08 and 12/08, plus three orphan
  `feature/ci-red-main-final-fixture-*` branches. The cleanup trap only runs on a normal exit, and
  `git worktree prune` will not collect them for three months. The success path was measured and is
  clean — **only interrupted runs leak**, one registration each, forever.
- **The check's own cost scaled with the litter.** `coord-status` walks `git worktree list` and this
  file calls it seven times; this host carried 110 registrations. Moving the fixture into a
  throwaway clone took the guard from **67 s to 33 s**. The same effect is recorded in
  `scripts/coord-status` from 2026-08-01: pruning 46 leftovers took it from 74 s to 12 s.
- **While the fixture was live it was visible to every sibling** as an unclaimed branch in their
  `coord-status`.

**The fix.** The fixture builds its worktree in a `--local --no-checkout` clone under `$TMP`
(0.066 s — git hardlinks the objects, so this is *cheaper* than what it replaces), the tree's
`scripts/` is copied over the clone's committed copy so the code under test stays the WORKING
TREE's and not `HEAD`'s, and the guard asserts at both ends of the fixture that the shared repo's
`claim-wt*` registrations and `feature/ci-red-main-*` branches are unchanged. Reverting the one
`git -C` makes that assertion fire naming the exact worktree and branch that appeared, so it is a
check that can fail (P-6.1b). Cleanup is now one `rm -rf "$TMP"`.

**It also exposed a real defect one layer down**, filed as
`coord-status-activity-lookup-reads-the-callers-cwd` in `scripts/BUGS.md`: the commit-activity rule
resolved `git log` against the caller's working directory, which only worked because the fixture
branch used to be in the shared repo.

### Original report (superseded 2026-08-13)

**Found 2026-07-31** by `scripts/smoke-ci` going red on `tests / ci-status-guard` during the
`v2-wide-jit-j0` claim, on a diff (`v2/src/Jit.scala`, `RunNativeV2.scala`) that could not reach it:

```
FAIL ci-status-guard    50.1s
   | exit code 128
   | fatal: Unable to create '/…/scalascript/.git/worktrees/claim-wt1/index.lock': File exists.
   | Another git process seems to be running in this repository, or the lock file may be stale
```

A re-run alone immediately afterwards gave `ci-status-guard: PASS`, rc 0, so it was read as
contention rather than a regression. The diagnosis at the time — since refuted — was that
`git worktree add` takes the shared index lock, making the check's success depend on nobody else
running git for that instant.

**Why it mattered more than one red run:** a pre-push suite that fails for reasons unrelated to the
diff teaches agents to re-run until green and then to stop reading it — which is how a real red gets
waved through. That one cost a full re-run of a 256 s suite to attribute.

**Fix directions** (not attempted here — `scripts/ci-status` and `tests/e2e/ci-status-guard.sh` are
held by the live `negtc-gate-shard-reduce` claim): give the check its own throwaway clone
(`git clone --depth 1` of the repo into `$TMPDIR`) instead of a worktree in the shared repo, or
retry the `worktree add` on lock contention with a bounded backoff. The clone is preferable — it
removes the shared-state dependency rather than making the race rarer.

## nul-byte-in-tracked-source — one NUL makes `grep` answer "nothing" for both match and no-match
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: unrecorded
     gate: tests/e2e/no-nul-in-sources.sh -->

**Found 2026-07-31** while asking why `grep` kept returning nothing on `v2/src/Runtime.scala`.

A single NUL byte makes `grep` treat the **whole file** as binary, and it then prints nothing — the
same output it prints for "no matches". Two different states, one answer. `Runtime.scala` is 227 KB,
the largest runtime source, and every `grep` over it had been silently answering "nothing" for
anyone who did not know to pass `-a`. I nearly drew a conclusion from that silence, with the hazard
already recorded in my own notes.

**Three tracked files carried one, not the one I noticed by hand** — the gate found the other two on
its first run:

| file | what the NUL was |
|---|---|
| `v2/src/Runtime.scala:1961` | `case _ => '<NUL>'` — a Char literal default |
| `v2/backend/js/JsBackend.scala:634` | `tag+'<NUL>'+name` — a key separator inside generated JS |
| `…/ScljetVfsNativePluginTest.scala:50` | `"SQLite format 3<NUL>"` — the SQLite magic header |

All three are INTENTIONAL NUL semantics written as a raw byte instead of an escape, so all three fix
without changing behaviour: `\u0000` in the emitted JS, `("SQLite format 3" + 0.toChar)` for the
header, and the escape for the Char literal.

**The gate found a defect in ITSELF on its first run, and that is why it is worth having.** Invoked
from outside a repository, `git ls-files` failed, the file list came back empty, and it printed
`OK` — scanning nothing reported as a pass, which is exactly the failure it exists to prevent. An
empty list now refuses loudly. `--self-test` plants a NUL and requires it to be caught.

**Class, not incident.** Same shape as five others the same week: `grep -c` exits 1 on zero matches
(a successful build reported as failed), `coord-release` silently dropped `--level`,
`update-index --cacheinfo` accepted a sha with no commit behind it, a stale build answered about the
wrong code. **Not being able to answer must not look like answering "no".**

## shared-main-is-one-working-tree-for-every-agent — bookkeeping pushes from it are flaky, and `git push` can report success while pushing nothing
<!-- status: open
     lane: apparatus
     area: other
     gate: tests/coord/coord-update-rolls-back.sh -->

**Found 2026-07-30** across four attempts to land ONE two-file claim-release commit. Not a theory — each
symptom below was observed, and the last two cost real work.

Every agent runs `scripts/coord-claim` / `coord-release` in the SAME shared checkout, so `.git/index`,
`HEAD` and the working tree are shared state with no locking above git's own.

| observed | consequence |
|---|---|
| another agent committed into the checkout mid-operation | my `git commit --amend` landed on THEIR commit, and my release commit vanished from local `main` |
| `.git/index.lock` present from another agent's git | `coord-claim` aborted with "Another git process seems to be running" |
| `git push` printed success | **nothing was pushed** — the claim file was still on origin |
| `git push` refused over `file '.agents/plugins' is already claimed` | my commit contained two `.work/` files and no claim; see below |

**The rule this produces, and it is cheap:** verify a release against the remote, never against the push
output — `git ls-tree origin/main .work/active/`. I now do this after every release and it has caught a
silent no-op once already.

**About that last refusal — a narrowing, not a diagnosis.** I first told the room that
`.githooks/pre-push:148` judges ownership from "the working tree". That was WRONG and I retracted it:
`owner_holds_file()` inspects the OWNER's worktree, which is a reasonable "is that agent editing this file"
heuristic, and in my case it never ran because `plugins-registration-bump` declares `file:.agents/plugins`
explicitly. The refusal comes from the `$new_items` / `$new_paths` loops, which validate an INCOMING claim —
yet my commit added no claim file. The remaining explanation is that pushing shared `main` can carry ANOTHER
agent's claim commit inside `remote_tip..local_tip`, so the guard validates a foreign claim and refuses a
push the pusher cannot fix. **Unproven.** To settle it: instrument `incoming` in the hook and push twice from
shared main while another agent claims something.

I deliberately did not change the mutex on an unproven reading. Weakening it to gain convenience is how two
agents end up doing the same work, which is what `specs/claim-mutex.md` exists to prevent.

**CONFIRMED 2026-08-07 — the "unproven" reading above is exactly right, observed three times in one
day, twice with the whole queue stopped.** No instrumentation was needed in the end; the refusal
names the foreign claim itself:

```
✋ pre-push: this claim overlaps a live claim on origin/main.
  file 'tests/e2e/f-bare-member-call-gate.sh' is already claimed by 'f-name-the-failing-module'
```

— raised against a claim of mine whose paths were `tests/conformance/run.sc` and a BUGS file, which
touch neither that file nor that agent. `git log origin/main..HEAD` in the shared checkout then
showed two commits belonging to other agents, and the named file was in ONE OF THEIRS.

**The three occurrences, because the shape differs each time and the cause does not:**

| what was parked | why it could not be pushed |
|---|---|
| a claim file widened, its LEDGER row not yet updated | the guard refuses when the two copies disagree — the owner was mid-operation |
| a claim widened onto a file another live claim holds | a real overlap; the owner has to drop the path |
| a release for a claim ALREADY released on origin under a different sha | a pure duplicate: the owner had re-landed it from a worktree, and the local copy was left behind |

Every one of them blocked EVERY agent's next claim, because `coord-claim` pushes the whole branch
from the shared checkout and the hook validates every claim in `remote_tip..local_tip`.

**The workaround, and it is already in use.** Build the coordination commit on a branch off
`origin/main` in a worktree and `git push origin HEAD:main`. That carries only your commit, so a
stranger's parked one cannot refuse it. A sibling landed `442e8e308` that way while the queue was
stuck, and I took `batch-lane-keeps-stderr` the same way. The ledger's generation bump has to be
replicated by hand, which is the part that makes this a workaround and not a fix.

**What NOT to do, stated because it is the tempting one.** `git reset --hard origin/main` in the
shared checkout clears it instantly, and twice today I could PROVE the parked commits were dead —
one duplicated something already on origin, one contradicted its own owner's state of record. I did
not, because the newest was eleven minutes old with its owner active, and "I proved it is dead" is
what people think immediately before destroying someone's work. Report it, name the fix, and let the
owner act.

**The rule this produces, for the owner rather than the bystander:** a coordination commit that is
committed and not pushed is a landmine for everyone. If your `coord-claim` or `coord-release` push
is refused, either fix it and push, or reset your own commit — do not leave it parked. `coord-claim`
already rolls its own commit back on refusal; `coord-release` and hand-made claim-updates do not, and
all three of today's landmines were of the second kind.

**2026-08-13 — one of the two remaining producers is now mechanically incapable of it, and STAYS
OPEN for the other.** The rule above asked the owner to remember; `scripts/coord-release` no longer
needs them to. Reproduced first, in a throwaway lab with a fake origin and a `pre-push` hook that
refuses — standing in for the overlap guard rejecting a stranger's parked claim, which cannot be
summoned on demand:

| | rc | commits parked on local main | claim file | ledger row |
|---|---|---|---|---|
| before | 1 | **1** | GONE locally, alive on origin | removed |
| after | 1 | **0** | present | present |

So a refused release now leaves the shared checkout exactly as it found it, and the claim still
stands. The second half of the same three lines was as bad: the refusal ASSERTED `main moved. Re-run
after: git fetch && git merge --ff-only` without looking, and that is the wrong advice for the
refusal that actually happens here — fetching does nothing for a guard rejecting somebody else's
claim, so the reader circles while everyone stays blocked. It now diagnoses three ways: main moved ·
the pre-push guard refused, naming `git log origin/main..HEAD --oneline` to see what is riding
along · anything else printed verbatim with **no cause invented**. Pinned by cases 6 and 7 of
`tests/coord/coord-release-refuses-unpushed-work.sh`, which against the pre-fix copy fail 9 times
and nowhere else.

**Why the entry stays open:** the structural fact is unchanged — the checkout is still one working
tree for every agent — and the OTHER producer is untouched. Heartbeats, scope widenings and
`next:` edits are still hand-written `git add && git commit && git push`, with no tool and so no
rollback; that is the largest remaining source by volume (five of them in one session while this
fix was being made). Filed with an acceptance test as
`hand-made-claim-updates-have-no-tool-and-so-no-rollback` in `scripts/BUGS.md`, so it is claimable
instead of buried in this entry's prose.

### 2026-08-14 — the hand-made update's SECOND hazard, and it is worse than a lost edit

The entry above says a hand-made claim update has no rollback. It has a sharper failure than that,
met today. A hand-written `git add && git commit && git push origin main` from the shared checkout
was rejected:

```text
! [remote rejected]  main -> main (cannot lock ref 'refs/heads/main':
                     is at 30f2da842 but expected 9a4f4d5a3)
error: failed to push some refs
```

**The obvious recovery — `git reset --mixed HEAD~1`, undo my commit, fast-forward, redo it — undid
a SIBLING's commit instead.** The reflog is the whole story, and it is a reflog of one checkout that
five agents commit into:

```text
09:30:05  30f2da842  commit: claim-update: js-big-dynamic-arith            (another agent)
09:30:06  b1a999a4d  commit: claim-update: pre-push-message-backticks      (mine)
09:30:09  027545331  commit: claim-update: bench-b4-three-version-table    (another agent)
09:30:35  b1a999a4d  reset: moving to HEAD~1                               (mine — ate 027545331)
```

`HEAD~1` was not my commit. Three seconds after mine, a sibling had committed on top of it, so
`HEAD~1` was **theirs**, and the file that then showed as ` M` in `git status` was not "a sibling's
uncommitted edit" — it was the content of the commit I had just destroyed. I read it as the former
and said so, which is the wrong diagnosis with the right conclusion: leave it alone.

**No damage, and only because they had already pushed.** `git diff origin/main -- <file>` was empty,
so `git checkout origin/main -- <file>` restored it byte-for-byte and the fast-forward carried the
commit back. Had that sibling not pushed in those 29 seconds, their work would have been gone with
no trace outside the reflog.

**Two things follow, and the second is the one worth keeping.**

- **Never `git reset` in the shared checkout.** Not `--mixed`, not `--soft`. `HEAD~1` there means
  "whatever the last agent did", not "what I did". The recovery from a rejected push is
  `git fetch && git merge --ff-only`, and nothing else.
- **A rejected push does not mean the commit did not land.** Mine is on `origin/main` as
  `b1a999a4d` — carried there by the SIBLING's push, because in a shared checkout their commit was
  built on mine. So "my push failed" and "my commit landed" are both true at once, and the
  recovery that assumes otherwise is what causes the damage. Check with
  `git merge-base --is-ancestor <sha> origin/main` BEFORE undoing anything.

This is the same root as the entry's title — one working tree, many agents — but it is a distinct
mechanism from the two already recorded, and the only one of the three that can destroy work rather
than lose a bookkeeping edit.

**Gate named 2026-08-14: `tests/coord/coord-update-rolls-back.sh`** — deliberately the SAME gate as
`scripts/BUGS.md hand-made-claim-updates-have-no-tool-and-so-no-rollback`, because once `coord-claim`
and `coord-release` learned to roll their commits back, the hand-made claim-update is the last
remaining way to produce the landmine this entry describes. One mechanism left, one gate.

**Done when** `scripts/coord-update` exists with rollback and that gate's anti-case fails against a
version without it. The two recovery rules this entry establishes stay as prose, because they are
things a reader must know rather than things code can hold: **never `git reset` in the shared
checkout** — it undoes whatever the last agent did — and **a rejected push does not mean your commit
did not land**, so check `git merge-base --is-ancestor <sha> origin/main` before undoing anything.

## backend-jvm-cases-have-no-verdict-on-any-backend-they-name — `backend: jvm` now gates a case to INT alone
<!-- status: fixed
     lane: apparatus
     area: conformance
     gate: none
     fixed-in: 57cd14ce9 -->

**CLOSED 2026-08-13 — and the check that mattered was whether the instance still existed.** All eight
named cases now live in `examples/` and are not conformance cases at all; **no case anywhere still
uses the singular `backend:` declaration**. So the concrete hole this entry described has no
occupant.

**That is not a reason to close it as stale — it makes the mechanism worse.** `canonicalLanes` is
`int,js,v2`, `parseTargetBackend("jvm")` yields `{int,jvm}`, the intersection is `{int}`. A case
declaring `backend: jvm` is measured on the interpreter ALONE, with no verdict on the backend it
names and not one word said about it. With the eight gone, that path is now completely unexercised —
so the next author to write `backend: jvm` gets the silence with nobody around to notice.

**Fixed by making it visible, not by failing.** Every run — green or red, and BEFORE the verdict —
now prints the cases whose declared backend no lane measured, what they declared and what was
actually measured. A hard failure would be wrong: `--lanes` legitimately narrows a run. What must not
happen is a declared coverage claim evaporating without a line, and it evaporates in a green run.

Proven with a temporary fixture and then cleaned up:

```
⚠ 1 case(s) DECLARE a backend no lane in this run measured:
    zz-declared-backend-probe  declares jvm  — measured lanes: int,js,v2
```

Removing the report block made it silent again; the fixture was deleted, because a permanent case
that always warns is noise.

**HONEST LIMIT, stated rather than glossed:** with no case declaring a backend today this report has
**no standing gate**. It is proven by the A/B above and then unexercised until someone writes such a
declaration — which is exactly the situation it exists for. If a case ever declares one, that case
becomes the gate. `contract --self-test` PASS (29 checks).

**A method note for the next reader:** the first probe of this measured nothing. I edited
`contract.sc` and tested with `tests/conformance/run.sh` — two different programs, and
`parseTargetBackend` lives only in the first. The run was green-ish and told me nothing, which is the
same shape as every other "probe never reached the subject" in this repository.


**Found 2026-07-30** while refreshing the paired freeze, and recorded here because it was previously only in
a commit message.

`parseTargetBackend("jvm")` yields `{int, jvm}` (`contract.sc:647`), and the contract's default lanes are
`int,js,v2`. The intersection is **`{int}`**. So the eight cases declaring `backend: jvm` —
`dataset-typed-mapping`, `distributed-dataset-{codec,typed-helpers,wire-protocol,wire-shuffle}`,
`graph-codecs`, `object-store-jdbc`, `typed-object-codec` — are measured on the interpreter only, and have no
verdict at all on the backend they name.

That is worse than it sounds in combination with the jvm lane's own state: `run-jvm` printed nothing and
exited 0 for `println(1+1)` when this was found (`run-jvm-silent-success`, since fixed by
`jvm-lane-never-calls-main`). So even switching `jvm` into the default lanes would have reported every case
as DIVERGE-by-empty-output rather than as a broken lane.

**It also explains ~20 rows that vanished from the freeze**, which could easily read as fixes: refreshing the
baseline after `baa55cdb9` (contract honours `backend:`) removed the js/v2 rows of those cases. They were not
fixed — they stopped being measured. Worth stating in the layout spec either way.

**Decide, do not patch:** either put `jvm` in the default lanes now that the lane runs, or say plainly that
`backend: jvm` means int-only today so nobody reads those green rows as backend coverage.

### CORRECTION 2026-08-14 — the premise this was closed on is FALSE, and its own output said so

This entry closed on *"no case anywhere still uses the singular `backend:` declaration"* and
concluded the path was **"now completely unexercised"**. Measured today:

```text
tests/conformance/*.ssc declaring `backend:`   0
examples/*.ssc         declaring `backend:`   33
```

**Thirty-three occupants, in the directory the census did not look in.** `examples/` cases are
rostered — `distributed-dataset-wire-shuffle` has been in `contract-roster.tsv` since 2026-07-27 —
so they are corpus cases in every sense that matters here.

**The report this entry added has been firing on every nightly since, unread**, which is the part
worth keeping: the evidence against the closing premise was in the output of the very runs it was
closed against.

```text
⚠ 2 case(s) DECLARE a backend no lane in this run measured:
    distributed-dataset-codec  declares jvm  — measured lanes: int,js,v2
    graph-codecs               declares jvm  — measured lanes: int,js,v2
```

So the "HONEST LIMIT" above — *"no standing gate … unexercised until someone writes such a
declaration"* — was wrong in both halves: the declarations existed, and the report was exercised
nightly.

**Status left `fixed`, deliberately.** The mechanism this entry added — say it rather than swallow
it — works, and is doing its job. What was wrong is the closing claim about coverage, and rewriting
that claim is the fix; reopening would suggest the report is broken, and it is not.

**The generalisable part, and it is the second instance this week.** A census answers only the
question it asked, and both misses were the same shape: this one counted `tests/conformance/` and
missed `examples/`; `js-jvm-codegen-in-fence-imports-not-followed` counted GENERATORS and missed the
CLI's own import discovery, so its fix passed locally and failed the corpus. Before closing an entry
on "no occupant remains", state the population that was searched — in the entry — so the next reader
can see what it excluded.

## ci-status-guard-selftest-two-stacked-defects — `Validate ScalaScript` is red on the guard's OWN self-test, twice over
<!-- status: fixed
     lane: apparatus
     area: build
     gate: tests/e2e/ci-status-guard.sh
     fixed-in: 65746b35d -->

**CLOSED 2026-08-13 — the SYMPTOM was already gone, the CAUSE was not, and the cause is what was
fixed.** `tests/e2e/ci-status-guard.sh` passes today: the fixture collision that made defect 1
deterministic resolved itself when the real `ci-red-main` claim was released and the dates moved.
Defect 2 (`invalid-heartbeat`) does not reproduce either — the whole gate is green.

**But the clamp this entry argued about was still in `scripts/coord-status`, in BOTH places** — the
heartbeat field and the commit activity — turning a negative age into `0`. This entry's own sentence
was the specification and nobody had acted on it: *"a commit dated after now is a clock or fixture
anomaly, and reporting it as 'just committed' is the least safe reading available."*

The consequence is not cosmetic. A claim whose timestamp sits in the future reads as **freshly alive
forever**, so it is never reaped and the files it holds are never released. And it is exactly how
this entry's defect 1 worked: the fixture's pinned clock sat before a real commit, the age went
negative, and the assertion the test existed to make became unreachable — deterministically dead, as
recorded.

Fixed: skew below `SKEW_TOLERANCE` (300 s) stays silent, because seconds of drift between machines
are ordinary; beyond it the line is printed by slug and the claim is **not** counted as fresh.

**No second copy of the staleness threshold**, because check 4 of the same test forbids exactly that
— *"two numbers deciding the same question will drift"*. My first version wrote `2700 + 1` at both
sites; it is a flag now, and the tolerance is its own constant answering a different question.

**Three checks added, each A/B'd — and the first one proved nothing until it was fixed.** It grepped
for any `ANOMALY:.*FUTURE`, so deleting the ACTIVITY report left it green on the surviving heartbeat
line: the control that should have failed passed. It now requires the anomaly named on BOTH paths,
which is what the two clamps were. Coord suite: 11 tests, 0 failing.

**Left alone deliberately:** defect 2's `invalid-heartbeat` diagnosis. It does not reproduce, and
inventing a fix for a green check would be guessing.


**Found 2026-07-30.** `Validate ScalaScript` fails on every push at
`tests/e2e/ci-status-guard.sh`. Nothing in the repository is broken — the guard's own fixtures are.
While it is red **no agent can obtain level-1 evidence for anything**, which is the currency this
project releases work on; two of my claims closed at level 3 today for exactly this reason.

**Defect 1 — the fixture slug collides with a REAL claim, and a negative age is clamped to zero.**

`coord-status` rightly treats a stale heartbeat FIELD as live when the owner committed recently
(`live by COMMIT activity (stale heartbeat field, ignored)`), taking the newest of the fixture branch
tip **and** `git log -1 origin/main -- .work/active/<slug>.claim`. The fixture calls itself
`ci-red-main`, and a real claim by that name was committed on main:

```
real   .work/active/ci-red-main.claim   last touched  epoch 1784468534  (2026-07-19)
test   SSC_COORD_NOW_EPOCH (pinned)                   epoch 1784249101  (2026-07-17 + 45m)
```

Real history is **newer than the test's pinned fake now**, so `activity_age_seconds` is negative,
`coord-status` clamps negative to 0, the claim reads as "last commit 0m ago" forever, and the
assertion the test exists to make is unreachable. Not flaky — deterministically dead since that
claim landed.

The clamp is the part worth fixing in the tool rather than the fixture: **a commit dated after
"now" is a clock or fixture anomaly, and reporting it as "just committed" is the least safe reading
available.** `scripts/coord-status:446`.

**Defect 2 — `invalid-heartbeat` fails too, and it was hidden behind defect 1.** With defect 1's
assertion neutralised in a scratch copy of the file from `origin/main`, the run gets past
`missing-claim` (which passes) and dies at `invalid-heartbeat`. Same file, same shape as
2026-07-28's finding about this very job: **stacked defects in a job's own self-tests, where the
first hides the second.** Not diagnosed further.

**What I tried, and why it is not landed.** Dating the fixture commits before the pinned clock, plus
renaming the fixture slug so it cannot collide, does fix defect 1 — measured. But the rename makes
`missing-claim` fail, a case that passes today, so it trades one red for another and I reverted it.

**The fix I would try next, for whoever owns this** (`scripts/coord-status` is another live claim's,
which is the other reason I stopped): stop hardcoding `2026-07-17T00:00:00Z`. Derive the fixture's
heartbeat and its commit dates from the REAL current time — heartbeat at `now - (threshold + 1)`,
commit dates the same, `SSC_COORD_NOW_EPOCH=now`. Then the fixture is stale by both signals, the real
claim's history is 11 days old and cannot rescue it, no rename is needed, and nothing depends on a
literal date drifting past real history again.

## heartbeat-stale-while-active — the staleness check called a committing agent orphaned
<!-- status: fixed
     lane: apparatus
     area: docs
     gate: tests/coord/claim-activity-overrides-heartbeat.sh
     fixed-in: c24ca1c08 -->

**Re-verified 2026-08-02:** the named gate passes — `claim-activity-overrides-heartbeat: OK (commit
evidence outranks a stale heartbeat field)`. Both cited shas (`c24ca1c08`, and `0c7bba624` for the
gate) resolve and are ancestors of `origin/main`.

**Status: FIXED 2026-07-30** in `c24ca1c08`, gated by
`tests/coord/claim-activity-overrides-heartbeat.sh` (`0c7bba624`).

**Observed.** `v2-backend-matrix-gaps` carried `heartbeat: 2026-07-29T17:12:00Z` while committing to
`main` every few minutes — **13 commits in the very hour** `scripts/coord-status` reported it as
`potentially stale heartbeat` (field age 10.7 h). I triaged it as orphaned **twice** and only a
manual `git log` on its branch stopped me editing `v2/src` underneath an agent actively working
there. A second claim, `int-case-unit-type-pattern`, was in the same state (field 507 min old, last
commit 26 min ago).

**Why the field was not at fault.** AGENTS.md tells agents to heartbeat on a **material status
change, not as running commentary**, and the threshold was raised 20 → 45 min on 2026-07-28 for
exactly that reason: 202 of 253 commits in one 6-hour window carried no code. So "heartbeat more
often" is the wrong prescription — it would undo that fix and re-flood the log. The defect was that
the check treated a hand-maintained field as the ONLY evidence of life, while the cheapest and most
reliable evidence — commits — sat unread in git.

**Fix.** `claim_activity_epoch` takes the newest of the claim branch's tip (local *and* remote) and
the last commit touching `.work/active/<slug>.claim`; a claim with commit activity inside the same
45-minute window is reported live, **naming its stale field** rather than silently ignoring it. The
field still matters for an agent with nothing to commit yet (planning, a long build) — it just stops
being the only evidence.

**Verified on the live board, both directions in ONE run** — which is the comparison the old check
could not make at all:

```
live by COMMIT activity (stale heartbeat field, ignored): int-case-unit-type-pattern
    (heartbeat age 507m, last commit 26m ago)
potentially stale heartbeat: bugs-index-machine-readable
    (heartbeat age 518m, no commit inside the threshold)
```

**Still to do — the prose copies.** `AGENTS.md:1140` and the multi-agent skill's triage table both
still present heartbeat age as the verdict. They are not wrong so much as incomplete, and both were
held by other claims (`AGENTS.md` by `bugs-index-machine-readable`; the skill lives in the
`.agents/plugins` submodule). Queued in SPRINT as `HBL-2`. Note
`heartbeat-threshold-stated-in-two-repos` already pins the NUMBER across all three copies — this is
the same duplication one level up, now about the RULE rather than the number.

## v2-front-for-yield-remaining-layouts — two of four `for`/`yield` layouts still miss F, one silently wrong
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded
     gate: tests/e2e/v2-front-coverage.sh -->


**Status:** **FIXED 2026-07-28** by `v2-front-for-yield-layouts`. `yield` joins `catch`/`finally`
in `canStartLineId`/`isContId`: it can never START a statement, it CONTINUES the `for` above it.
All four layouts now compile under F and match the INT reference — including the one that used to
compile into a program that ran and was wrong.

**Gate: `tests/e2e/v2-front-coverage.sh`, asserting on the `ssc info --front-report` COLUMN rather
than on output** — because output cannot see this class: when F declines, the F4a fallback
recompiles with the legacy front and the program prints the right answer. The pre-existing
`tests/conformance/for-yield-layout.ssc` contains the def-body layout and was green throughout.
A/B'd 2-of-6 FAIL against the unfixed front; its `--self-test` requires a known GAP to report GAP.

`wasm-scalascript`). **A/B'd against `cd14a97d5~1`: identical before and after that fix, so NOT a
regression from it** — but it does mean the fix's reach is narrower than its commit message sounds,
and the gate could not see the difference.

**Four layouts, measured on the default lane:**

| layout | F | result |
|---|---|---|
| `val r = for` ⏎ gens ⏎ `yield e` at **col 0** | ✅ compiles | `2` — correct |
| `val r = for` ⏎ gens ⏎ **indented** `yield e` | ⚠️ compiles | **`__method__: no dispatch for .foreach on 1` — a WRONG program that runs** |
| `val r =` ⏎ **indented** `for` ⏎ gens ⏎ `yield e` | declines | correct output via the F4a fallback |
| `def f() =` ⏎ **indented** `for` ⏎ gens ⏎ `yield e` | declines | correct output via the F4a fallback |

**Why the existing gate is blind to this.** `tests/conformance/for-yield-layout.ssc` compares
OUTPUT, and rows 3–4 produce the *correct* output — the fallback recompiles them with the legacy
front. So the case is green while F still cannot lower two of the four shapes. Row 4 is literally a
shape that gate contains, and the gate passes. **An output gate cannot measure which front
compiled the file**; that needs `ssc info --front-report` or the dualrun gate.

**Row 2 is the serious one** and it is the shape this project cares most about: F emits a program
that *runs* and is wrong, rather than failing. It is not new — it predates the layout-opener fix —
but it is now the only one of the four that is silently wrong rather than loudly delegated.

**Fix direction.** Rows 3–4: `for` as the first token of a layout block opened by `=` — the opener
fires on the `=`, so `for` on the next line is inside that block and its own opener never runs.
Row 2: `yield` indented to the same level as the generators is being absorbed INTO the generator
list instead of closing it, so the comprehension desugars with the yield expression as a generator
(hence `.foreach` on an Int).

**Do not gate this with a conformance case alone** — see above. Pair it with
`ssc info --front-report`, which answers "did F actually compile this file" directly.

## v2-front-for-comprehension-guard-line — a guard on its own line is parsed as a generator
<!-- status: fixed
     lane: apparatus
     area: front
     gate: tests/conformance/for-yield-layout.ssc
     fixed-in: bccdc6c4f -->

**FIXED 2026-08-11, and the gate that named this entry could not have caught it.**

`tests/conformance/for-yield-layout.ssc` was this entry's `gate:` and it was GREEN on all four lanes
while the defect was live — because that case says, in its own prose, that a guard on its own line is
*"deliberately absent … including it would make this gate red for a reason it does not test"*. Correct
when written; but the entry pointed at it anyway, so anyone triaging by gate colour would close this
on evidence that could not fail. **A `gate:` that cannot fail for its defect is worse than
`gate: none`** — it invites exactly that closure, and it nearly got one out of me today.

**Reproduced first, on a clean build, front confirmed as F:**

```
for x <- xs if x % 2 == 0 yield x     native List(2, 4)   interp List(2, 4)
braced, guard on its own line         native ssc: __method__: no dispatch for .map on false
braceless, guard on its own line      native ssc: __method__: no dispatch for .map on false
```

**Mechanism — the discriminator is the SEPARATOR, not braces.** `forGuard` tests for `if`
*immediately* after the generator. With the guard on its own line a separator arrives first, so the
guard branch is skipped and `forSep` reads that separator as "another generator follows" — the
guard's boolean is then handed to `flatMap` as a collection, which is the `.map on false`.

The fix routes a separator FOLLOWED BY `if` into the same `forGuardG` the one-line form uses, so both
layouts desugar identically; repeated guards keep working because `forGuardG` returns to `forSep`.

**Evidence.** Both layouts added to the case and the "deliberately absent" note replaced, since its
reason is gone — all four lanes green. The gate was then observed FAILING without the fix, by
reverting it in the staged front (`bin/lib/*/native-front/tower/bin/fsub.ssc`, byte-identical to the
source, so no rebuild is needed for an A/B): `FAIL [V2] line 10: expected=7 got=ssc: __method__: no
dispatch for .map on false` — the exact symptom recorded above. Full conformance with the memo
disabled: **367 passed, 0 failed**, six declared known-red lanes, none of them this.


**Status:** OPEN (found 2026-07-28 by `v2-front-for-yield` — it was MASKED until then: the whole
enclosing shape produced `_err`, so the guard never got far enough to misbehave).

**Symptom.** `ssc: __method__: no dispatch for .map on false` — the guard's boolean is being treated
as the collection of a generator, so the desugarer calls `.map` on `false`.

**Measured** — the discriminator is *one line vs its own line*, NOT braces:

| shape | native | INT |
|---|---|---|
| one line: `for x <- xs if x % 2 == 0 yield x` | `List(2, 4)` | `List(2, 4)` |
| **braced, guard on its own line** | **`.map on false`** | `List(2, 4)` |
| **braceless, guard on its own line** | **`.map on false`** | `List(2, 4)` |
| braceless, no guard | `List(1, 2, 3, 4)` | same |

**Pre-existing, and A/B'd rather than assumed.** The braced form already worked before
`v2-front-for-yield-parse-gap` was fixed, and the guard fails there identically — verified by
reverting that fix, rebuilding, and re-running: `.map on false` on both sides. So it is independent
of the layout-opener change and was simply invisible behind it.

**Deliberately excluded from `tests/conformance/for-yield-layout.ssc`**, with a note in the case
saying why: including it would make that gate red for a reason it does not test.

**Fix direction.** The generator loop in `parseFor` treats every statement in the comprehension body
as `pat <- expr`. A statement starting with `if` and containing no `<-` is a GUARD and must desugar
to `.withFilter`/`.filter` on the accumulated source, not to a new generator. Both self-hosted
fronts, presumably — the one-line path clearly has the guard branch already, so the gap is in the
per-statement (layout-block) path.

## v2-serve-banner-belongs-on-stderr — a server banner is not program output
<!-- status: fixed
     fixed-in: 84bab666e
     lane: apparatus
     area: runtime
     gate: tests/conformance/run.sc -->

**Status:** OPEN, **deliberately deferred** (filed 2026-07-28 by `v2-stub-apply-and-serve-banner`
as option (2) of `v2-serve-banner-missing`, which took option (1)).

`WebServer.start`'s three-line startup banner goes to **stdout** on both lanes, which is how it got
captured into the goldens of every serving example in the first place. A banner is developer
chatter, not program output; on stderr it would not be part of any observable contract.

**Not done opportunistically, and the reason is a measurement, not taste.** It rewrites the golden
of every serving example and needs its own claim plus a full-corpus re-freeze. **Whoever takes it
must check first:** `tests/conformance/run.sc` builds its comparison with
`outputWithFailureContext(out, err, exitCode)`. If that folds stderr into the compared text
unconditionally, moving the banner to stderr changes **nothing** observable and only churns the
goldens — so establish that before touching anything.


**NEW EVIDENCE 2026-07-28 — the deferral now costs a RED RELEASE GATE, which it did not when it was
filed.** `ScalaScript 2.1 standard-only negative toolchain release gate` fails on this, and it is
the last red on that job after a chain of six unrelated ones was cleared:

```
v21-negative-toolchain-release-gate: http-server-provider (vm) MISMATCH (exit 0)
  expected=$'203\npong:/ping'
  got=$'ScalaScript web · http://localhost:35093/  (root: .)\n  (backend=fast)\nCtrl+C to stop.\n203\npong:/ping'
```

Note `exit 0` and the expected text present and correct — the banner is the entire difference, which
is exactly this entry's argument. Runs 30384832575 and 30388521933 (per-push jobs all green in both).

It had been failing SILENTLY: the assertion was a bare `[[ $(cmd) == … ]]` under `set -e`, which
aborts printing nothing, and the whole section is unreachable while an earlier gate step fails. It
became visible only once those steps were fixed and the assertions were made to print
expected/got (`1cde350c7`, corrected to stdout-only in `364c3b228`).

**Deliberately NOT worked around here.** Three options were considered:
1. update the gate's expectation to include the banner — blesses stdout as contract and has to be
   reverted when this entry is actioned;
2. loosen the assertion to ignore a prefix — weakening a release gate to accommodate behaviour this
   entry already calls wrong;
3. move the banner to stderr — what this entry proposes, and the only one that leaves the gate
   correct without blessing anything.

(3) is the owner's call, not a passing agent's: this entry already assessed the blast radius across
serving goldens, which is precisely the analysis a drive-by fix would skip. Raising the priority with
the measurement rather than acting on it.

**RESOLVED for the v2 lane 2026-07-29 — and the deferral's premise was wrong.**

The reason this was deferred was an assumed blast radius: "it rewrites the golden of every serving
example and needs its own claim plus a full-corpus re-freeze". Measured, that is not what it costs:

```
$ grep -rl 'ScalaScript web ·' tests/conformance/expected/ examples/
tests/conformance/expected/tkv2-pwa.txt          # exactly ONE
```

One golden, because the other serving examples are skipped by the runner as un-runnable standalone,
so the banner never reached their goldens.

The precondition this entry asked for was also checked, and it came out the *other* way:
`outputWithFailureContext` is `if exitCode == 0 then out` — stdout ONLY on success, stderr folded in
only as failure context. So moving the banner is NOT observationally neutral; it is exactly what
removes it from the compared text.

Fixed on the v2 lane (`NioNativeHttpServerHost`: `Console.out` → `Console.err`). Verified with the
real launcher against the exact assertion that was failing:

```
stdout: $'203\npong:/ping'      # byte-identical to the gate's expectation
stderr: ScalaScript web · … / (backend=fast) / Ctrl+C to stop.
```

Conformance unaffected: `tkv2-pwa` still PASSES (it is `backends: [int]`, produced by v1's
`WebServer`, untouched), and the serving/http slice is 2 passed / 0 failed.

**STILL OPEN: the v1 lane.** `WebServer.start(port, root, log)` uses `log` for the banner and for
nothing else; the CLI passes `System.out` at `v1/tools/cli/.../Main.scala:566`, so one word there
(`System.out` → `System.err`) completes it. NOT done here because that file is held by the
`v2-backend-matrix-gaps` claim — it is a one-line change waiting on a claim, not on a difficulty.
Note that doing it *will* require dropping the three banner lines from `tkv2-pwa.txt`, which also
removes a hardcoded port number (`localhost:18631`) from a golden — a latent flake in its own right.

**v1 CLI half done 2026-07-30** (`ssc serve` now passes `System.err`). **The program-facing path is
NOT done, and it is the one that matters more** — recording it so the entry is not mistaken for
closed:

An `.ssc` program calling `serve()` reaches `WebServer.start` through
`Interpreter.scala` → `InterpreterServerSupport.startServer(..., Interpreter.this.out, ...)`, i.e.
the banner is written to **the interpreter's own stdout — the program's output contract**. Nothing
captures it today (the serving conformance cases are skipped, and `tkv2-pwa` went through the v2 fast
backend, fixed in `fdb239e34`), so this is latent rather than active.

The clean fix is one level up and wider than the CLI: `log` carries ONLY the banner — those are its
three uses in `WebServer.scala` — so the parameter should be retired and the banner printed to
`System.err` unconditionally. That touches `WebServer.scala` plus every caller
(`Main.scala`, `ReplCommands.scala`, `InterpreterServerSupportImpl.scala`), which is why it is filed
rather than smuggled into a one-word change.

**CLOSED 2026-07-30 by `84bab666e`** ("the startup banner goes to stderr on BOTH lanes"), verified by
reading the code rather than by trusting this entry: the three banner lines are
`System.err.println` at `WebServer.scala:144-148`, so the program-facing path this entry called "the
one that matters more" is done. Recording it because a stale OPEN sends the next agent to retire a
parameter that no longer needs retiring.

The prescription above is now WRONG in its premise and should not be followed: `log` no longer
carries the banner, so it is not dead. Its remaining uses in `InterpreterHttpHandler` are all
EXCEPTION diagnostics — `Error: …`, `WS upgrade handler error: …`, `WS handler error: …` (lines 120,
152, 334-379). Whether those belong on stderr too is a real question, but a different and much
smaller one: they fire only on a thrown exception, so unlike the banner they are not in the output of
every serving program. Filed as its own line rather than folded in here, since the argument that
carried this entry ("a banner is developer chatter, not program output") does not transfer unchanged
to a handler's error report — a program may legitimately want to see that.

## ci-status-guard-desc-green-always-red — the CI-health job was red on its own self-test, not on the repo
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 083c78fd6 -->

**Status:** FIXED 2026-07-28 in `083c78fd6`.

**What it looked like.** `Validate ScalaScript` failed on **every** run, so the job that exists to
report health was itself a permanent red — and it named a SHA that does not exist:

```text
ci-status-guard[desc-green]: expected exit=0 got=2
CI UNKNOWN d684e68971c75ac11042f19f84fc32c1070fb064~5
d684e68971c75ac11042f19f84fc32c1070fb064
  reason: no push ci.yml run found for the exact SHA
```

The `~5` suffix is unresolved in the output, and the SHA is printed **twice, on two lines**. Both
are the symptom.

**Cause — two defects one line apart, neither in the production script.** `scripts/ci-status`
already uses `rev-parse --verify` correctly; only the guard's fixture did not:

```bash
DESC_SHA="$(git rev-parse origin/main 2>/dev/null || git rev-parse HEAD)"
ANC_SHA="$(git rev-parse "${DESC_SHA}~5" 2>/dev/null || echo "$DESC_SHA")"
```

1. `actions/checkout` clones at `fetch-depth: 1`, so `<tip>~5` does not exist in CI. Locally, with
   full history, it always did — which is why this passed on every developer machine.
2. **`git rev-parse` without `--verify` does not fail cleanly.** On an unresolvable revision it
   ECHOES the argument to stdout *and* exits 128. So `||` did not replace the value, it **appended**
   one: `ANC_SHA` became two lines. `ci-status` was then asked about the literal string `<sha>~5`.

The second defect is the transferable one: `cmd 2>/dev/null || echo fallback` is only a fallback if
`cmd` prints nothing on failure. `rev-parse` is a trap here; `--verify -q` is the fix.

**Fix.** Build the pair with `git commit-tree` — two real commits with a real parent edge written
straight into the object database: no refs, no index, no working tree, nothing to clean up, and no
dependence on checkout depth. The ancestry stays real, which was the reason the original used real
commits at all. Identity is passed by env because a CI checkout has no configured `user.email`.

Three assertions were added so the fixture cannot degenerate quietly (both SHAs present and
distinct, ancestry real, ancestry **not** symmetric — otherwise the `desc-none` negative proves
nothing). **Both were verified to fire**, by collapsing the pair and by dropping the parent edge.

**Evidence.** Reproduced on a `git clone --depth 1` of this repository, which reproduces CI exactly:
red with the message above before, `ci-status-guard: PASS` after; full clone also PASS.

## v2-native-error-printed-but-exit-zero — reported, NOT reproducible after the Mirror fix, now guarded
<!-- status: fixed
     lane: apparatus
     area: front
     gate: tests/e2e/v2-error-diagnostic.sh
     fixed-in: 4f5ecf261 -->

**Re-verified 2026-08-02 at `f9af6728d`** (the classification was `unknown`, which means nobody had
checked): `tests/e2e/v2-error-diagnostic.sh` runs **15 ok, 0 FAIL**. The invariant it pins — if the
run prints `ssc: <error>` the exit code must be non-zero — holds.

**Status:** **NOT REPRODUCIBLE** on `origin/main` after `4f5ecf261`; the invariant is now gated so
a return cannot be silent. Raised 2026-07-28 in rozum by the agent that fixed
`v2-mirror-isproduct-stub`: *"that example EXITS 0 while printing its error, so an exit-status check
sees success."*

**Why it deserved a look even though the observable is gone.** This is the worst failure shape
there is: every `if ssc run …; then`, every CI step, every script that checks a status reads a
printed error as success. It is invisible to exactly the machinery meant to catch it.

**Measured.** `examples/rozum-agent-schema-derived.ssc` now prints `Done / Derived posted. /
Explicit posted. / 2` and exits 0 **correctly** — the `Stub("Mirror.isProduct")` reaching an `if`
condition was the bug and it is fixed, so the trigger no longer exists. Ten shapes probed with the
status captured directly (never through a pipe, which yields the last command's status):
uncaught throw, unbound name, index-out-of-bounds, unbound qualified call, parse error, `10 / 0`,
unresolved method in an `if` condition, unresolved method in a plain call, unresolved predicate on
an Int, malformed `derives Mirror`. **All ten print `ssc: …` and exit 1.**

**Decision.** No fix invented — the trigger is gone and a fix for an unreproducible failure is a
guess. What lasts is the invariant: **if the run prints `ssc: <error>`, the exit code must be
non-zero.** Six cases now assert it in `tests/e2e/v2-error-diagnostic.sh`, and the assertion is
mutation-tested (injecting a runner that prints a diagnostic and returns 0 produces
`FAIL … printed a diagnostic but exited 0`). Writing it also caught the classic trap in my own
gate: `set -e` aborted the script the moment a probe exited non-zero — the expected outcome — so
`rc=$?` never ran and the whole section silently vanished from the output. `set +e` around exactly
those two lines.

**Still open, deferred to BACKLOG with the reasoning:** auditing the *nested* runner paths (the
ASM→VM link-time fallback and the F-delegation re-run) for a swallowed non-zero status. That is
where a fail-open of this shape would most plausibly hide, but it is a real audit with no live
symptom to anchor it.

## v2-list-apply-method-stub — `xs.apply(i)` is `Stub` on the native lane while `xs(i)` works
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 59e912e7f
     gate: tests/conformance/list-apply-method.ssc -->


**Status:** **FIXED 2026-07-28** by `v2-stub-apply-and-serve-banner` (`e34938737`). Two VM dispatch
arms next to the existing Map/Array `apply` arms route a cons/nil receiver to `Prims.listIndex` —
the SAME helper the `App` form uses, so the two spellings cannot drift, including the
out-of-bounds message. A/B on one worktree, arm reverted and restored: `xs.apply(1)` went `Stub` →
`20` while `xs(1)` stayed `20`.

**Deliberately not fixed in the frontend**, which this entry's roadmap line warned about: lowering
`.apply` to an application there breaks `object O { def apply(x) }`, whose `O.apply(1)` must reach
the user's method. That shape was A/B'd and is unchanged — it fails identically before and after,
and it fails LOUD, so it is a pre-existing gap now filed as `v2-object-apply-unbound`.

**Gate:** `tests/conformance/list-apply-method.ssc` — literal and computed indices, a `String`
list, and an index inside a HOF, since dispatch happens at run time and a constant-folded index
would not exercise it. PASS on INT/JS/JVM/V2; the golden's first line is `10` where the pre-fix
build printed `Stub`.

**Status:** OPEN (found 2026-07-28 by `corpus-contract-refresh-freeze` while probing list indexing
for a different fix).

**Reproduction** (real harness, fresh `sbt installBin`):

```scalascript
val xs = List(10, 20, 30)
println(xs(1))
println(xs.apply(1))
```

| lane | `xs(1)` | `xs.apply(1)` |
|---|---|---|
| `bin/ssc-tools run --v1` | `20` | `20` |
| `bin/ssc run` (default, native) | `20` | **`Stub`** |

**Root cause.** The two spellings lower differently and only one is implemented. Dumped IR:

```
xs(1)        -> (app (prim cell.get (global xs__cell)) (lit (int 1)))
xs.apply(1)  -> (prim __method__ (lit (str "apply")) (prim cell.get (global xs__cell)) (lit (int 1)))
```

The application form is handled; `__method__("apply", <list>, i)` has no arm — `Runtime.scala` has
`apply` arms for `MapV` and two `ForeignV` shapes and nothing else — so it falls through to the
ambient path and yields a `Stub` sentinel, at exit 0. `.apply` is *by definition* application, so
the two must agree for every receiver, closures included.

**Where the fix goes.** A late fallback arm in the VM (`case (recv, "apply", args) => callValue(recv,
args)`, after the Map/Foreign arms) is one line and covers every receiver. A front-side rewrite of
`.apply(a)` into `(app recv a)` looks tempting but is WRONG in general: `object O { def apply(x) }`
lowers to a mangled `O_apply` global, and `O` itself is not applicable — the rewrite would break it.
So: VM, not front. `v2/src` was held by a live claim when this was found.

## scljet-write-freelist-errors-are-swallowed — reclaiming pager accepts corrupt staged graphs
<!-- status: fixed
     lane: apparatus
     area: front
     gate: tests/conformance/run.sh
     fixed-in: b9b060e6e -->

**CLOSED 2026-08-13 — fixed by `b9b060e6e` and never marked. The opposite failure to the two entries
closed beside it today:** there the instance had vanished while the mechanism lived; here the
mechanism was repaired and the entry outlived it. That costs differently and it cost today: a
sibling picked up work I had already scoped, and only a hand-off in the room stopped it being done
twice.

**The mechanism this entry named is gone.** `readExistingFree` returned a plain `List[Int]`; it
returns `Either[ByteError, List[Int]]` since `b9b060e6e`, which also added `validateFreedPages`, and
`applyFreelist` now rejects a caller page-size disagreement before reading anything and propagates
every `Left`.

**Checked by BEHAVIOUR, not only by reading — because a passing conformance case proves nothing on
its own.** A case passes when it matches its EXPECTED file, and that file could have recorded the
buggy answers. It does not: of 27 observables it records **24 as `left stable=true`** — every
negative this entry lists, head/count disagreement through trunk cycle, duplicate and overlapping
pages, page 1, reserved-byte and auto-vacuum modes, caller page-size mismatch — and the 3 `right`
answers are explicit `accept(...)` calls, including `valid-staged=right count=12`.

So the acceptance criterion written here — *"every negative case must fail before returning a changed
pager"* — is met and asserted, on INT and JS.

**What the entry described as `right` on all 16 negatives is now `left` on 24.** The count grew
because the fix added observables the original report did not have.


**Status:** OPEN (found 2026-07-28 during SPRINT `SC-2a.1`;
reporter: Codex production-completion audit; baseline `b6967a79f`).

**Real-harness reproduction.** All 16 negative observables in
`tests/conformance/run.sh --only 'scljet-freelist-write-corrupt' --no-memo`
return `Right` on INT and JS. They include head/count disagreement, range and
exact-count failures, out-of-range next/leaf pointers, a trunk cycle, duplicate
and overlapping pages, an excessive leaf count, a short staged trunk, page 1,
unsupported reserved-byte/auto-vacuum modes, and a caller page-size mismatch.
The apparatus deliberately deletes through a different B-tree leaf from the
staged trunk, so an eventual `Left` cannot be a proxy caused by corrupting the
target data page.

**Root cause / impact.** `readExistingFree` returns a plain `List[Int]`, maps
every header/trunk read failure to an empty or partial graph, narrows unchecked
u32 values to `Int`, and never verifies roles, cycles, duplicates, or the exact
header count. `applyFreelist` also returns early for an empty newly-freed list.
A direct staged-helper caller can therefore discard freelist metadata, overwrite
page 1, or stage an out-of-range trunk that extends the database at commit.

**Fix acceptance.** Validate the complete staged graph through an
`Either[ByteError, ...]` preflight at every `pagerDeleteRebalanced` entry, reject
unsupported layout modes and page-size disagreement, validate the union with
newly freed pages, and prove two pre-commit reclaim batches read the staged
header/trunks. Every negative case must fail before returning a changed pager.

## corpus-contract-roster-drift-48-cases — the always-on differential gate exits 1 for bookkeeping, not for a regression
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: tests/conformance/contract-roster.tsv
     gate: tests/e2e/freeze-consistency-gate.sh
     fixed-in: dd04074bd -->

**Status:** OPEN — **measured, not mine to fix** (found 2026-07-28 by `f-try-multistmt-def-body`
while rostering one new conformance case; the cases span at least six live claims, so a single
agent classifying all of them would be stepping on other people's work).

**What was measured.** `tests/conformance/contract-roster.tsv` has exactly one commit
(`fc5f07f28`, the freeze that introduced it). Since then **48 cases** were added to the corpus
without a roster row. `contract.sc` treats "a case absent from the frozen roster" as RED and
`System.exit(1)`s on it (contract.sc:788-820), so the Corpus Contract is currently red on
`origin/main` for pure bookkeeping.

Reproduce (seconds, runs nothing):

```bash
scala-cli --server=false tests/conformance/contract.sc -- --list | sort > /tmp/selected
tail -n +2 tests/conformance/contract-roster.tsv | sort > /tmp/roster
comm -23 /tmp/selected /tmp/roster          # 48 names; the reverse direction is EMPTY
```

The 48 (2026-07-28): `coroutine-demo`, `coroutine-native-lifecycle`,
`distributed-callback-user-throw`, `durable-save-run`, `effects-handler`, `fence-attr-code`,
`fence-doc-block`, `generator-callback-user-throw`, `int-literal`, `int-width`,
`js-int-boundary-const-lambda`, `js-int-division-by-zero`, `json-self-hosted-import`,
`list-combinators`, `map-ops`, `markdown-html`, 20 × `scljet-*`, 8 × `std-ui-native-*`,
`try-catch-exception-delivery`, `try-catch-io-failure`, `v2-system-clock`,
`w5-scala-fence-width-parity`.

**Why it matters more than a stale file.** This is the SAME failure mode as
`corpus-contract-never-green`, one step downstream. A gate that is red for a reason nobody acts on
stops being read, and the next *real* regression lands inside that noise. The gate is not lying —
it is honestly red — but the effect on behaviour is identical to a lie.

**Not a licence to skip.** The remedy the tool itself prints is the right one: *classify each new
case, then refresh the paired freeze with one full run* (`--update-baseline`, which rewrites the
whole non-PASS matrix AND the roster, and which the tool already refuses to run on a scoped
selection — `corpus-baseline-update-scoped-run-truncates`). That means one agent, one unsharded
full-corpus run, on a quiet machine.

**Already partly discharged:** `try-multistmt-body` was rostered by the finder with evidence
(scoped contract run, 9/9 PASS cells on int/js/v2), and the digest arithmetic was verified by
reproducing the recorded `baseline-sha256` before writing the new `roster-sha256`. The other 47 are
untouched on purpose — each needs its own classification, and several belong to claims that are
live right now.


**CLOSED 2026-08-05 — the drift was 2 cases, not 48, and that is the first thing worth recording.**
Between 07-28 and now the corpus absorbed almost all of it: `contract.sc --list` says 558 cases,
the roster had 556, and the two missing were `credential-vocabulary` and
`generic-ctor-and-array-alloc`. Both were run through the contract before being rostered — 5/5 PASS
cells — so a roster row with NO baseline row is their correct classification ("absent = expected
PASS"). Scoped run afterwards: `✓ contract GREEN`.

So this never needed the six-claim coordination the entry (rightly, at the time) declined to do.

**A count I got wrong on the way, which is why the prevention is shaped as it is.** My first
measurement globbed `tests/conformance/*.ssc examples/*.ssc` and reported **37** missing. The tool
says 2. The glob was wrong by 35 because `contract.sc` applies rules a directory listing does not
know. Re-deriving the case set is precisely the mistake that lets drift back in.

**Prevention — invariant I5 in `tests/e2e/freeze-consistency-gate.sh`, which runs per PUSH.** It
ASKS `contract.sc --list` for the case set and diffs it against the roster, in both directions
(missing rows, and rows naming a case that no longer exists). Two properties make it maintainable:

- **Derived, never frozen.** It compares two sets; a frozen COUNT would go stale on the next case
  added — which is how the roster drifted in the first place.
- **Cheap enough for the push path**: `--list` is 0.49 s warm, less than the rest of that gate
  (1.1 s). Drift used to surface only in the nightly, long after the push that caused it.

Verified both ways: green on the true tree, and planting a missing row — with the header rehashed,
so the existing pairing invariant could NOT be what fired — makes it fail naming the case. If
`scala-cli` is absent the gate says so and skips, rather than passing silently.

## uniml-yaml-tag-percent-decoder-quadratic — legal long tags trigger repeated prefix copies
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 3341a35a9 -->

**Status:** FIXED 2026-07-28 in `3341a35a9` (found during independent
UPR-2a.1 review; SPRINT `UPR-2a.1`).

**Measured reproduction.** At local feature commit `7ba41ae36`,
`YamlTagEnvironment.decodePercentEscapes` appends every literal/decoded code point
with `result = result + piece`. On JDK 21, projecting otherwise equivalent primary
tags took approximately 314 ms at 128 KiB, 830 ms at 256 KiB, 3.19 s at 512 KiB,
and 12.17 s at 1,000,000 characters; an undefined-handle control which skipped the
decoder took 2–11 ms. A source may contain many maximum-line tag spellings.

**Impact.** The new semantic path introduces a bounded but exploitable CPU/allocation
amplifier before the broader UPR-2d hardening slice.

**Fix and verification.** Representation expansion now performs one monotone
percent-syntax scan and preserves `%HH` verbatim. The temporary parser-event
decoder was removed entirely by `024d80524`; the normative event path now reuses
the preserved representation instead of allocating a second decoded spelling.
JVM/Scala.js suites, portable lint, the two-round build-isolation gate, and the
exact 402-case corpus baseline all pass; independent reviews accepted the linear
portable path.

## uniml-yaml-corpus-gate-exception-isolation — post-capture hashing can erase observed axes
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 70068fd7b -->

**Status:** FIXED in `70068fd7b` (found 2026-07-28 by the independent
`uniml_code_test_audit` sub-review while qualifying UPR-1a). The affected gate existed only in the
local YAML corpus commit `0720175d`; its push was stopped when the review retracted ACCEPT.

**Reproduction.** `YamlScheduleEvaluation.observation` computes the SHA-256 of reconstructed text
after `YamlCaptured[YamlReconstruction]` has returned. A parse hook that reconstructs a lone UTF-16
surrogate makes `YamlCorpusUtf8.encode` throw there. The outer per-case `safely` then replaces the
whole result with `crashedOutcome`: already collected parse/snapshot/reconstruction observations
are lost and the semantic hook is never attempted. Separately, `YamlCorpusOutcome.baselineRow` and
`YamlCorpusReport.baselineRows` canonicalize outside the per-case capture, so an unpaired surrogate
inside an observed semantic value can make the report itself throw instead of recording a red row.

**Impact.** The official pinned inputs are valid UTF-8, so the frozen 402-case numbers are stable,
but the apparatus fails its hostile-input and exception-isolation contract. A future parser
regression could turn a detailed per-axis red into an opaque outer crash or prevent later cases/axes
from being measured.

**Fix acceptance.** Add fail-first malformed-UTF-16 tests. Digest/canonicalization failures must
become deterministic axis errors without discarding already observed values; semantic evaluation
and later cases must still run; baseline rows/digests must remain total and distinguish the failure;
`LinkageError` and other fatal VM errors must still propagate. Re-run YAML JVM+Scala.js suites,
the 402-case census/expected-red strict gate, standalone `uniml`, portable lint, and affected
conformance before requesting renewed independent acceptance.

**Root cause and verification.** Hashing and canonical rendering were moved inside explicit
per-axis/per-row/per-case capture. Deterministic ASCII fallbacks retain every observable and escape
malformed UTF-16; semantic evaluation and later cases continue, while `LinkageError` and other
fatal errors still propagate. The fail-first regression passed on JVM and Scala.js (17/17 each),
the official census remained 402 cases / 94 expected errors / zero crashes, and the strict gate
remained expected-red on exactly 290 cases with unchanged baseline/category digests. Portable lint,
the standalone build, affected conformance, and renewed independent acceptance all passed.

## f-validateNoReader-rejects-plugin-externs — the F-vs-legacy guard counts a legitimate `extern def` as a coverage gap
<!-- status: open
     lane: apparatus
     area: front
     gate: tests/conformance/scljet-mutate-update.ssc -->

**Status:** OPEN (found 2026-07-28 by `v2-board-and-f5b` while measuring why F still delegates).
Affects `RunNativeV2.validateNoReader`, i.e. the F4a delegate-fallback decision — not F itself.

**What happens.** `validateNoReader` accepts a global only if it is a top-level `def` in the same
program or starts with `@`:

```scala
def globalOk(g: String): Boolean = defNames.contains(g) || g.startsWith("@")
```

A plugin intrinsic declared as `extern def` (e.g. `extern def jvmVfsOpen(path: String, readOnly:
Boolean, create: Boolean): JvmVfsResult` in `scljet/jvm-vfs.ssc`) is neither, so F's program is
rejected and the run silently delegates to the legacy front.

**The evidence that this is a mis-classification and not an F gap:** the LEGACY front emits exactly
the same thing. Measured on `tests/conformance/scljet-mutate-update.ssc`, both fronts produce
`(global jvmVfsOpen)` with no corresponding `(def …)` — the name is resolved by the plugin registry
at run time, which is the design. Legacy is never validated, so only F is punished for it.

**Impact.** Every program touching a plugin extern delegates unconditionally, regardless of what F
can actually compile. That is a permanent, invisible tax: double lowering on each run, F's output
discarded, and the program counted against F's breadth. It is a strong candidate for the dominant
delegation cause, but **that is NOT yet measured** — see the caveat below, and measure it before
repeating the claim.

**Fix direction (a decision, not a mechanical change).** Do not simply loosen the check: it exists
because genuine F gaps also surface as unbound globals, and loosening it trades a loud delegation for
a silent wrong answer. The information needed to tell them apart exists — the program's own `extern
def` declarations, and the plugin registry — so the guard should accept *declared* externs and keep
rejecting unknown names. Owner call, since it changes the F4a fallback contract.

**MEASURED 2026-07-28 — the caveat above is now resolved, and the hypothesis holds.**
Full corpus census via the new `ssc info --front-report` (`d684e6897`), 347 files,
one decision per file, no execution:

| decision | files | meaning |
|---|---:|---|
| `F` | 95 | F compiled it; no delegation |
| `BOTH-UNBOUND` | 213 | both fronts emit the same unbound global — the mis-classification in this entry |
| `GAP` | 33 | F's own coverage hole |
| `ERROR` | 6 | 5 parse failures (`_err` sentinel), 1 malformed fence |

So of the **246 delegations, 213 — 87% — are not F's fault at all**, and F's real
coverage hole is **33 files (~10% of the corpus)**, not 246. The entry called the
extern class "a strong candidate for the dominant delegation cause"; it is the
dominant cause, by a wide margin.

**One extern accounts for over half of it.** Grouping the 213 by the unbound name:

```text
115  jvmVfsOpen        ← the exact `extern def` this entry names (scljet/jvm-vfs.ssc)
 19  runActors
  7  self
  7  sc
  7  jsonCoreParseTolerant
  5  element
  5  __yamlSection__
```

`jvmVfsOpen` alone is 115/213 (54%) of the mis-classified delegations and 33% of
the whole corpus. Accepting *declared* externs — the fix direction already written
above — would move roughly 213 files from "delegated" to "F", changing F's measured
breadth from 95/341 (28%) to ~308/341 (90%) **without touching F itself**. That
reframes the remaining work: the 33 `GAP` files are the actual F breadth backlog,
and their causes are already grouped (`q` ×6, `handle` ×6, `html` ×4, `summon` ×3,
`effect` ×3, `x` ×3, then singles).

Method note: the census ran in two parts from two worktrees (the first was
interrupted at 145/347 when its worktree was removed mid-run), so absolute paths in
the raw rows differ. Classification is per-file and unaffected. Reproduce with
`ls tests/conformance/*.ssc | xargs bin/ssc info --front-report`.

---

## heartbeat-threshold-stated-in-two-repos — AGENTS.md and the multi-agent skill can drift apart
<!-- status: fixed
     lane: apparatus
     area: plugin
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by opus (`heartbeat-threshold-drift-gate`) — gate landed here, and
the submodule copy corrected in its own repo (commit `242501d` in `agent-plugins`, **local, not
pushed**: pushing to another remote is the owner's call). Originally flagged, deliberately not fixed,
by `heartbeat-cadence` the same day.

**The drift was live and harmful, not cosmetic.** The skill said 20 minutes in **seven** places while
`scripts/coord-status` enforced 45. An agent following the skill would declare a claim orphaned at
minute 21 and take work AGENTS.md says is still live — the exact collision the claim mutex exists to
prevent, reached by READING the rules rather than by ignoring them.

**Fix, in two parts.** The number in the skill now matches, and the skill says `scripts/coord-status`
is the source of truth rather than only restating a figure. More durably,
`tests/coord/heartbeat-threshold-single-source.sh` reads the threshold **from the code that performs
the comparison** — not from the comment beside it, which could itself have drifted — and fails if any
prose copy disagrees.

**Three drafts of the matcher, recorded because each failed in a way worth knowing:**
1. "any minute-figure on a line mentioning heartbeat" flagged the heartbeat CADENCE ("sitting for
   more than ~10 minutes") — a different quantity that is legitimately not 45.
2. Widening the trigger to any `<`/`>` beside a figure then flagged `can't separate confidently in
   <5 minutes` in AGENTS.md — prose with nothing to do with claims.
3. The deciding case is one line in the skill carrying BOTH: *"sits for more than ~10 minutes. Older
   than ~20 minutes → treat as potentially stale."* One clause right, one wrong, so **no line-level
   rule can be correct and complete**. The gate matches PHRASES.

A gate that cries about a correct line is one people learn to ignore, so this mattered more than the
number did.

**Two self-checks, because a checker only ever seen passing is not a checker.** It asserts a
deliberately-wrong fixture is REJECTED and a matching one ACCEPTED, and their output is suppressed —
a green run must not contain the word FAIL, or every human and every `grep -c FAIL` misreads it.
A/B: restoring the old submodule text makes the gate exit 1; with the fix it exits 0.

**Not wired into `ci.yml`** — that file is held by the live `ci-bookkeeping-floods-verdicts` claim.
It belongs beside the other two `tests/coord/` gates in the `Validate` job.

The claim-staleness threshold is written down twice: `AGENTS.md` + `scripts/coord-status` in THIS
repo, and `.agents/plugins/multi-agent/commands/multi-agent.md` in the **agent-plugins submodule**
(a separate repo). Raising it 20 min -> 45 min here leaves the submodule saying 20, so an agent
reading the skill and an agent reading AGENTS.md disagree about when a claim is orphaned.

Not edited from this claim on purpose: committing into another repository to keep a number in sync
is exactly the scope drift `claim-mutex` exists to catch, and it would land unreviewed in a repo
this claim does not own. Someone with the submodule in scope should update it, or — better — the
skill should stop restating the number and point at `scripts/coord-status` as the single source.

## ci-sbt-job-is-28x-the-code-push-interval — the arithmetic no queue policy can fix
<!-- status: open
     lane: apparatus
     area: runtime
     gate: .github/workflows/ci.yml -->

**Status:** OPEN — **measurement, handed to whoever owns the `sbt` job** (2026-07-28,
`ci-bookkeeping-floods-verdicts`). Not mine to fix: job structure and `timeout-minutes` belong to
`ci-runs-cancelled-under-churn`.

**The numbers** (6 hours on `main`, measured 2026-07-28):

| quantity | value |
|---|---|
| commits | 253 |
| of those, changing code (i.e. creating a run after `paths-ignore`) | **51** |
| mean interval between CODE pushes | **7 min** |
| `sbt — compile and test` duration | **196 min** (17:55:20→21:11:40) |
| ratio | **28** |

Twenty-eight code pushes arrive while one `sbt` job runs. With GitHub's one-pending-run-per-
concurrency-group rule, at most 1 commit in 28 can reach a verdict, and only if a runner is free.

**What this rules out.** Queue management is not the lever. `paths-ignore` removed the 74% of load
that carried no code and drained the backlog from 62 runs to 1 — necessary, and visible — but the
ratio above is unchanged by it, because it was never about the docs commits.

**What is left.** Either the verdict-carrying job gets shorter than the push interval, or the
per-push verdict comes from something that already is. The other three jobs measured
`Validate` 34 s, `Lint` 28 s, `Conformance Suite` 38 min — so the candidate already listed under
`ci-runs-cancelled-under-churn` ("gate the fast jobs as the per-push verdict and run `sbt` on a
schedule") is not one option among several; it is the only shape that fits the arithmetic without
making the tests faster. A scheduled `sbt` still catches everything, just later and in batches.

**Gate named 2026-08-14: `.github/workflows/ci.yml`** — the change IS the workflow, so the file is
the acceptance test, in the same sense as `corpus-contract.yml` is for the corpus entries.

**Done when** the per-push verdict comes from jobs that fit inside the push interval — `Validate`
34 s and `Lint` 28 s are already measured — and `sbt` runs on a schedule instead. The entry's own
arithmetic rules the alternatives out: queue management was necessary and changed this ratio not at
all, because it was never the docs commits.

**The measurement that says it worked** is the one that produced the finding: median
push-to-verdict interval against the job's duration. Re-run it after the change rather than letting
the shape imply the number.

## scljet-jdbc-nan-binding-diverges — PreparedStatement binds NaN as REAL instead of SQLite NULL
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: b39127f61 -->

**Status:** FIXED in `b39127f61` (found 2026-07-28 by the independently
reviewed live sqlite-jdbc SC-1a differential).

**Real-harness reproduction.** Bind `Double.NaN` with `setDouble` to the
`INTEGER PRIMARY KEY` parameter of an INSERT. Pinned Xerial sqlite-jdbc
canonicalises the value through `sqlite3_bind_double` to SQL NULL and assigns
the next automatic rowid; `jdbc:scljet:` passes `SqlReal(NaN)` into IPK
affinity and returns datatype mismatch. The compare-first matrix reports the
phase/category difference and the missing row explicitly.

**Root cause / fix.** `PreparedStatementHandler` mapped every
`setDouble`, `setFloat`, boxed `Double`, and boxed `Float` directly to
`SqlReal`. Its JDBC boundary now maps NaN to `SqlNull`, while preserving
positive and negative infinity as REAL values (which the IPK affinity layer
then rejects). Keep the pure engine contract distinct: an explicitly
constructed `SqlReal(NaN)` is still a non-integral REAL and must fail IPK
coercion. The phase-aware compare-first matrix passes for all four JDBC binding
routes and verifies the reference-persisted row.

## scljet-ipk-insert-indexed-out-of-order — indexed INSERT rebuild preserves statement order, not rowid order
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: b39127f61 -->

**Status:** FIXED in `b39127f61` (found 2026-07-28 by
`scljet-production-completion` in the live sqlite-jdbc SC-1a differential).

**Real-harness reproduction.** Through `jdbc:scljet:`, create one
`INTEGER PRIMARY KEY` table plus an index, seed rowid 50, then execute prepared
INSERTs whose bound IPKs arrive out of order (for example 4, 51, 7, and -5).
The mutations report success, but the next
`SELECT ... ORDER BY id` fails with `table rowids are not strictly increasing`.
The same bound-value matrix succeeds through Xerial `jdbc:sqlite:`.

**Root cause / fix.** The indexed branch of `executeInsertRows` passed
`existingRows ++ newRows` straight to `reindexTable`, which writes table cells
in list order. INSERT now uses the same `sortRowsByRowid` ordering/duplicate
backstop as indexed UPDATE before rebuilding the table and its indexes. The
portable regression forces a new key between two existing keys on INT+JS; the
live differential compares outcomes and rows with SQLite, reopens the
SclJet-written file, and gets `PRAGMA integrity_check = ok`.

## scljet-sql-numeric-literal-grammar-gaps — signed VALUES, exponent, and hex literals are incomplete
<!-- status: open
     lane: apparatus
     area: front
     gate: tests/e2e/scljet-m2-corpus-smoke.sh -->

**Status:** OPEN (found 2026-07-27 by `scljet-production-completion` while
running the assembled IPK-affinity matrix). This is a declared SC-8 grammar
slice; SC-1a must make every unsupported form fail before mutation.

**Real-harness reproduction.** `INSERT INTO t VALUES (-5)` fails with
`expected a literal`; bare `2e2` is split into integer `2` plus identifier
`e2`; bare `0x10` is split into integer `0` plus identifier `x10`. Reference
SQLite inserts rowids `-5`, `200`, and `16`. TEXT `'0x10'` is deliberately
different and must remain a datatype mismatch under affinity.

**Root cause / required slice.** The numeric lexer starts only on a decimal
digit, supports only `digits` and `digits.digits`, and INSERT `VALUES` accepts
one unsigned literal token rather than a signed expression. SC-8 must add the
full SQLite signed decimal/exponent/hex source-literal grammar and differential
vectors without changing the distinct TEXT-affinity grammar.

**Gate named 2026-08-14: `tests/e2e/scljet-m2-corpus-smoke.sh`** — the scljet corpus gate, already
wired into `ci.yml` and already the home of scljet's differential vectors against reference SQLite.

**Done when** `INSERT INTO t VALUES (-5)`, bare `2e2` and bare `0x10` produce rowids `-5`, `200` and
`16` there, matching reference SQLite — **and TEXT `'0x10'` still fails as a datatype mismatch under
affinity**. That second half is not a detail: the entry states the TEXT-affinity grammar is
deliberately different, so a change that makes all four succeed has broken something while turning
the first three green.

## scljet-update-ignores-unconsumed-numeric-tail — mutation parsers can execute a recognized prefix
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a00db1967 -->

**Status:** FIXED in `a00db1967` (found 2026-07-27 by
`scljet-production-completion`; reproduced through the assembled v1 runner and
real `jdbc:scljet:` driver). The fail-first INT+JS regression and the
post-rebase SC-1a sqlite-jdbc/file matrix now pass.

**Real-harness reproduction.** From rowid 100,
`UPDATE t SET id = 2e2 WHERE id = 100` moves the row to 2, while
`UPDATE t SET id = 0x10 WHERE id = 100` moves it to 0. The lexer leaves `e2`
or `x10` plus the intended WHERE tokens, but UPDATE execution ignores that
unconsumed tail. Reference SQLite moves to 200 and 16 respectively.

The fail-first portable gate also proved sibling paths on both INT and JS:
`INSERT INTO t SELECT 2e2` inserts rowid 2 after discarding `e2`;
`CREATE TABLE rogue(a) garbage`, a body-less/unclosed CREATE TABLE,
`CREATE INDEX idx ON t(a) garbage`, a partial-index `WHERE`, and
`DROP INDEX idx garbage` all return success after executing only the recognized
prefix. The immutable input image is not modified in place, but the API returns
a changed image for a statement it never parsed completely.

**Root cause / safety gate.** Mutation parsers do not all require an empty
remaining token stream before execution. UPDATE/DELETE/VALUES INSERT expose a
remainder directly; SELECT currently drops its remainder, and the CREATE
TABLE/INDEX plus DROP INDEX parsers ignore their trailing tokens. SC-1a must
reject any unconsumed or structurally incomplete mutation before opening a
mutable pager; SC-8 then implements the missing literal families. A syntax gap
may fail loud, but it may never partially execute.

## scljet-sql-integer-literal-overflow-wraps-rowid — decimal 2^63 becomes Long.MinValue
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a00db1967 -->

**Status:** FIXED in `a00db1967` (found 2026-07-27 by
`scljet-production-completion`; reproduced through assembled INT and
`jdbc:scljet:` paths). `scljet-ipk-numeric-affinity` is green on INT+JS, and
the reference sqlite-jdbc/file closure landed green in `b39127f61`.

**Real-harness reproduction.** Bare decimal literal `9223372036854775808` in an
IPK INSERT or UPDATE succeeds as rowid `-9223372036854775808`. Reference SQLite
represents the positive literal as binary64 `2^63` and the IPK assignment
returns `datatype mismatch`.

**Root cause.** `tokenize` accumulates `num = num * 10 + digit` in `Long`
without overflow detection. SC-1a must parse the unsigned decimal magnitude
exactly, keep in-range integers as `SqlInteger`, and represent an out-of-range
decimal token through SQLite's binary64 path instead of wrapping.

Independent pre-push review caught the adjacent signed-boundary hazard:
reclassifying the unsigned magnitude as REAL must still fold exactly
`-9223372036854775808` (including leading-zero spellings) to integer
`Long.MinValue`, without accepting the binary64-rounded
`-9223372036854775809` or decimal `-9223372036854775808.0`. The regression gate
pins all three.

## scljet-jdbc-prepare-defers-query-errors — PreparedStatement accepts invalid correlated SELECT
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: ac33456bc -->

**Status:** FIXED (found 2026-07-28 by the live differential on `ac33456bc`
plus the in-flight correlated fix; fixed in `71d8a6f0e`).

**Real-harness reproduction.** Prepare joined SELECT statements containing a
missing correlated subquery table or malformed correlated subquery through
both `jdbc:scljet:` and Xerial sqlite-jdbc. Xerial rejects each statement
during prepare; SclJet originally returned a `PreparedStatement` proxy and
failed only on execute.

**Root cause.** `ConnectionHandler.prepareStatement` constructed the proxy
without invoking the parser or resolving the current schema. Query validation
first happened inside `executeQuery`.

**Fix:** `71d8a6f0e` adds `validatePreparedQuery`, binds typed NULL placeholders
for the validation pass, reads the current transaction image, and validates
query syntax plus named tables/subqueries before returning the JDBC proxy.
The live differential now observes the same prepare phase and semantic
category for the named cases. This is deliberately not a full compiled or
cached prepared plan: unknown-column and nested-scope resolution remain
partial, and execution still reparses the current image. SC-4a owns that
larger preparation contract.

## v2-f-small-vm-admission-loads-asm — F admission classification breaks VM backend isolation
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 198a9245f
     gate: tests/e2e/v21-plugin-backend-isolation-smoke.sh -->

**Status:** DONE (found 2026-07-27 by `codex` while verifying unlanded
`15b99b5ee`; fixed in `389b36e0f`). The reporter confirmed the fix by rerunning
the exact real-harness isolation gate successfully.

**Pre-fix reproduce.** Build the staged product with
`scripts/sbtc "installBin"`, then run
`tests/e2e/v21-plugin-backend-isolation-smoke.sh`. The VM control exits 1:

```text
native VM loaded a backend ASM or external parser/codec class
```

The pre-fix selective F path asked
`JvmByteGen.requiresStringChunking(firstNestedProgram)` even when the program
was small and would stay on VM. Initializing `JvmByteGen` initialized its ASM
`Handle`/emitter state, so the classification probe itself loaded
`org.objectweb.asm.*`. That violated the existing closed-native VM contract
even though no class was emitted.

**Root cause / fix.** Admission lived on an ASM-owning singleton, so a
supposedly data-only classification initialized the backend. Modified-UTF8
accounting, program-constant walking, and chunk splitting now live in the
ASM-free `JvmBytecodeAdmission`; both `RunNativeV2` and
`JvmByteGen.loadString` use it so selection and emission cannot drift.
Focused tests passed 11/11, the hello/SClJet product gate passed, and
`v21-plugin-backend-isolation-smoke.sh` now passes.

## corpus-contract-usage-missing-arg-separator — documented commands do not reach the script
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28`, with the operator forms
verified and documented in `2a796b258` (reporter confirmation pending). Found
by Codex re-review on `a7ef5749c`.

**Reproduce.** The usage block and feature spec showed
`scala-cli ... contract.sc --self-test`. scala-cli consumes that as its own
option and exits 1 with `Unrecognized argument: --self-test`; the script never
runs. The working form is `scala-cli ... contract.sc -- --self-test`.

**Root cause / fix.** Every example omitted scala-cli's argument separator.
Add the load-bearing `--` to all commands that pass Corpus Contract options and
pin the exact working forms in both operator docs.

## corpus-contract-skip-transition-false-improvement — runnable-but-failing is called PASS
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fc5f07f28 -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex re-review on `a7ef5749c`.

**Reproduce.** Freeze `x<TAB>*<TAB>SKIP`, then make `x` runnable with current
`x<TAB>js<TAB>FAIL`. The wildcard is in scope and absent from the current
non-PASS rows, so the first key-based classifier emitted both a JS regression
and `IMPROVEMENT — now PASS` for the old SKIP. The same false improvement
appears when the case becomes runnable but has zero eligible requested lanes.

**Root cause.** A missing lane-specific non-PASS row means PASS only because
that exact lane key was observed. A wildcard SKIP is case-level; its absence
means merely "not skipped", not "all lanes passed".

**Fix / done-when.** A frozen wildcard SKIP improves only when at least one
eligible lane cell ran and every observed cell passed. Runnable-with-failure
and zero-eligible-cell transitions do not claim improvement. Synthetic tests
cover all three branches.

## corpus-contract-shards-miss-removals — every production shard suppresses coverage loss
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: fc5f07f28
     gate: tests/conformance/contract.sc -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex re-review on `a7ef5749c`.

**Reproduce.** `.github/workflows/corpus-contract.yml` runs only four
`--shard i/4` jobs. The first roster implementation computed removals only
when `shard.isEmpty`, so deleting/renaming a rostered PASS-only case produced
an empty delta in every production job.

**Root cause.** The design treated a shard's executed `cases` as its only
knowledge, but `selected` is already the complete unsharded, skip-filtered
universe and is computed before `cases` is sliced.

**Fix / done-when.** Every run without `--only`, including every shard,
compares `R - selectedCurrentCases`; an `--only` diagnostic slice suppresses
global removal inference. A synthetic/selection check proves a shard sees a
removed roster case.

## corpus-contract-zero-evidence-green — an empty selection can pass without comparing anything
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: fc5f07f28
     gate: tests/conformance/contract.sc -->

**Status:** **FIXED 2026-07-27** in `fc5f07f28` (reporter confirmation
pending). Found by Codex review on `e124cc20f`.

**Reproduce.** With a built toolchain, either a misspelled filter such as
`scala-cli --server=false tests/conformance/contract.sc -- --only definitely-not-a-case`
or an empty lane value such as `--only arithmetic --lanes ''` reaches the
normal gate with zero cases/cells. Removal inference is correctly suppressed
for a subset, so the empty delta prints GREEN (`PASS cells: 0/0`) even though
no observable was compared.

**Root cause.** CLI values and the selected case/cell counts are trusted
without a positive-evidence check. An empty slice is represented by the same
empty sets as a fully matching slice.

**Fix / done-when.** Validate option arity, lane names, non-empty lane sets,
duplicates, and positive numeric values; reject a normal gate with zero
selected cases or zero observed lane/skip cells. `--list` remains an
enumeration operation, not evidence. Regression checks must assert the exact
exit-2 diagnostic so a different early failure cannot masquerade as the fix.

## ir-normalize-drops-code-fence-attrs — no SPI backend can see a fence attribute on a code block
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 63b9470c8 -->

**Status:** FIXED for the CARRY (2026-07-28, `ir-fence-attrs-carry`, `63b9470c8`); **the ACT-ON-IT
half is a declared follow-up.** `ir.Content.CodeBlock` now has `attrs`, `Normalize` passes the
attributes it was already holding, and `Denormalize` restores them, so an SPI backend CAN now see
`@side=server`. Pinned by a boundary test in `NormalizeRoundTripTest` that fails without the fix
(`got attrs=List(Map())`); `core/test` 1151/0.

**Still open, and deliberately not claimed here:** no backend yet ACTS on `side`, so a
`@side=server` block is still emitted into the JS bundle — the difference is that the information
now exists at the boundary instead of being unrepresentable. Asserting the acting-on behaviour in
the carry test would have made it pass for a reason it does not verify.

**Also unchanged, on purpose:** `ir.Content.EmbeddedBlock` still has no `attrs`. Foreign fences have
attributes too, but that is a second slot on a second type with its own consumers.

**Historical status:** OPEN — by design today, and the design is the problem (found 2026-07-27 by
`corpus-gate-remaining-reds` while implementing `@doc`).

**Symptom.** `@side=server` on a ```` ```scalascript ```` block is honoured by INT and ignored by
every SPI backend (JS, JVM, Rust, Spark…), so a block meant to be server-only is emitted into the JS
bundle.

**Root cause.** `ir.Content.CodeBlock` has no `attrs` field at all, and `Denormalize.content`
rebuilds `ast.Content.CodeBlock(lang, source, tree, span)` without one. SQL attributes survive only
because `db` and `side` were promoted to dedicated IR fields on `ir.Content.SqlBlock`; the generic
`@key=value` surface has nowhere to live. INT keeps them because it interprets the `ast.Module`
directly, never crossing the SPI.

**Not blocking `@doc`,** which sidesteps it: `Normalize` now DROPS `@doc` blocks instead of trying to
carry the marker across the boundary — a documentation block has no business in a normalized program
IR anyway. Anything that must be *visible* to a backend still needs a real IR field or an `attrs`
map on `ir.Content.CodeBlock`; that is a format change (`SsccFormat` V3 writes IR) and is why it is
filed rather than fixed here.

## v21-explicit-lanes-gate-swift-em-dash-red — JVM launcher stdout is locale-dependent (non-ASCII → `?` on CI)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 54eae3197
     gate: tests/e2e/v21-explicit-lanes-gate.sh -->

**Status:** FIXED (`54eae3197`, 2026-07-22). Last remaining CI-baseline red (the sbt
`ScalaScript 2.1 explicit provider and target lanes` step, `tests/e2e/v21-explicit-lanes-gate.sh`).
Orphaned pre-existing work — the scljet-VFS siblings it was attributed to were done/released.

**Repro (Linux / C locale):** `LANG= tests/e2e/v21-explicit-swift-provider-smoke.sh` after
`installBin` →  exit 1, silent (bare `[[ ]]`). Under `bash -x` / with the smoke hardened, the check
`'pacs.008 transfer output'` fails: expected `GPI hop: DEUTDEFF — ACCC …`, got
`GPI hop: DEUTDEFF ? ACCC …`. Passes on macOS (UTF-8 locale) — a "local green ≠ CI green" trap.
Reproduced in `catthehacker/ubuntu:act-latest` + `openjdk-21` mounting the built worktree.

**Root cause — NOT scljet-VFS (the claim's prime suspect).** The swift lane runs the `.ssc` example
through `bin/ssc-provider` → `java scalascript.cli.StandardMain`, which `println`s an em-dash (U+2014).
`file.encoding` is UTF-8 by default since JDK 18 (JEP 400), but `System.out`/`System.err` still use
`native.encoding`, which is locale-derived. On the CI runner `LANG` is unset (C locale) and stdout is
redirected to a file (no console), so `native.encoding = ANSI_X3.4-1968` (ASCII) and the em-dash is
replaced with `?`. `java -XshowSettings:properties` in the C-locale container confirms
`stdout.encoding = ANSI_X3.4-1968` while `file.encoding = UTF-8`.

**Fix:** added `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` to all three installBin launcher
templates in `build.sbt` (`ssc`/`ssc-standard`, `ssc-tools`, `ssc-provider`) — the same "on every
launcher" precedent as `-Xss`. `.ssc` observable output is now deterministic across locales; ASCII
output is byte-identical (UTF-8 ⊇ ASCII), so no other lane's expected changed (verified: both
scljet-VFS smokes still pass on Linux/C-locale, full gate 3/3 on macOS). Also hardened
`v21-explicit-swift-provider-smoke.sh` to route every assertion through named `fail`/`expect_out`
helpers (its bare `[[ ]]`/`grep`/`cmp`/redirected-run checks exited 1 printing NOTHING — the exact
silent-gate anti-pattern AGENTS.md warns about, and why this needed a manual Docker bisect).

## durable-save-run-verifier-red — effect verifier mis-flags a def that fully handles its own effect
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: af46212c3 -->

**Status:** FIXED (`durable-save-run-verifier-red`, 2026-07-22). Pre-existing CI Conformance red
since `examples/durable-save-run.ssc` landed (`af46212c3`) — the `ssc-tools check examples/*.ssc`
step (the CI "Conformance Suite" job) failed, blocking ALL flip/switch CI verdicts on the repo.
The durable-continuation example was ORPHANED (its sibling task was done/released).

**Repro:** `./bin/ssc-tools check examples/durable-save-run.ssc` →
`examples/durable-save-run.ssc: error: [effect-verifier] 'capture' appears effectful (reaches
Suspend) but declares no effect row (!)`.

**Root cause — verifier FALSE-POSITIVE, not an example leak.** `capture(): Int => Int =
handle { … Suspend.point() … } { case Suspend.point(saved) => saved }` fully DISCHARGES the
`Suspend` multi-effect at the `handle` boundary, so `capture` is genuinely pure (`Int => Int`,
exactly as the spec documents) and correctly declares no effect row. `EffectAnalysis.analyze`'s
name-reachability sees `Suspend.point` lexically inside `capture` and marks it effectful, ignoring
the enclosing `handle`. It only manifested here because durable-save-run puts the `multi effect
Suspend` declaration AND `capture` in ONE fenced block (the analysis runs per-block; effects.ssc /
algebraic-effects.ssc split the effect decl from the discharging code across blocks, so their
per-block `effectOps` set is empty in the def's block → never flagged). Annotating the example
with a row would be a LIE (`capture()`'s caller does not, and must not, handle `Suspend`).

**Fix:** added a discharge-aware `EffectAnalysis.leakingFuns(trees, effectOps)` — a handle-scoped
leak set: an op `Eff.op` counts as leaked only when reached OUTSIDE a `handle` that discharges
`Eff` (effect names taken from the handler's `case Eff.op(...)` patterns, the same extraction the
runtime's `EffectsRuntime.handledOps` uses, plus any explicit `handle[Eff]` type arg). The Typer's
effect-row verifier now consults `leakingFuns` instead of the coarse `effectfulFuns`. The coarse
`effectfulFuns` (consumed by JvmGen/JsGen CPS codegen) and the interpreter's `multiShotEffects` are
left UNCHANGED, so execution/codegen are unaffected — this is a verifier-only refinement.
Files: `v1/lang/core/src/main/scala/scalascript/transform/EffectAnalysis.scala`,
`v1/lang/core/src/main/scala/scalascript/typer/Typer.scala`. Regression tests in
`EffectTyperTest` (discharge accepted; op performed OUTSIDE the handle still flagged — proving
lexical scoping, not blanket suppression).

**Verify:** `ssc-tools check examples/durable-save-run.ssc` OK; example still runs
(run(1)=10 / run(5)=50 / run(42)=420 / prefix 1 time); full `ssc-tools check examples/*.ssc`
= 0 errors (220 examples, only pre-existing shadowing/quoted-macro warnings). EffectTyperTest +
EffectAnalysisVerifierTest green (incl. the existing "still flags a real leak" test); transform +
typer test packages 302/302.

## scljet-unique-index-not-supported — `CREATE UNIQUE INDEX` is rejected and uniqueness is not modelled
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 50d2ca5bc -->

**Status:** FIXED `50d2ca5bc` (landed 2026-07-22; reporter confirmation pending). Found
2026-07-16 by the `scljet-ipk-rowid` differential lane; claimed by Codex on 2026-07-22 as
`scljet-unique-index-not-supported`. Reporter/context: project SPRINT U1-U4; no Rozum
sequence was the original report.

**Repro:** against a SclJet image containing `CREATE TABLE t(a INTEGER, b TEXT)`,
`executeMutation(db, "CREATE UNIQUE INDEX ux ON t(a)")` returns `expected TABLE`.
The tokenizer succeeds, but both mutation dispatchers recognize only `CREATE INDEX`.
Reference SQLite accepts the statement. Reference duplicate probes establish the required
failure shape: `UNIQUE constraint failed: t.a` (or `t.a, t.b` for a composite index).

**Root cause:** `parseCreateIndex` requires `CREATE INDEX`; `executeMutation` and
`executeMutationCountedParams` route CREATE statements by checking only whether the second
token is `INDEX`. The stored/rebuilt `IndexInfo` carries key positions but no uniqueness bit,
so INSERT/UPDATE cannot enforce a unique index even if parsing alone is added.

The implementation audit also found a directly blocking writer defect:
`write.ssc::compareKeys` extracts only `SqlInteger` payloads from the numeric class (so every
`SqlReal` sorts as zero) and returns equality for every BLOB pair. A parser/enforcement fix that
left this comparator intact could still emit a physically misordered unique index. The feature
therefore uses one corrected exact comparator for both B-tree ordering and duplicate detection.

**Safety note:** a parser-only fix is forbidden. It would store `CREATE UNIQUE INDEX` in
`sqlite_schema` while allowing duplicate keys, producing a file whose declared constraint is
false and whose reference `PRAGMA integrity_check` can report a non-unique index entry.
The fix must cover CREATE over existing rows plus INSERT/UPDATE, distinct NULL semantics,
a cross-engine file differential, and the real conformance/JDBC harnesses. Feature contract:
`specs/scljet-unique-index.md`.

**Fix:** `parseCreateIndex` and both mutation dispatchers now carry the `unique` bit;
stored schema SQL is re-parsed into `IndexInfo`, and CREATE plus full-rowset DML validation
run before any rebuilt image is returned. The shared writer comparator now orders INTEGER/REAL
exactly (including above 2^53), TEXT by BINARY order, and BLOB byte-for-byte, so physical B-tree
ordering and duplicate equality cannot diverge. Verification: forced SclJet conformance 103/103
on INT+JS; `scljetJdbcPlugin/test` 63/63 in 6 suites; sqlite-jdbc matches the three rejection
paths and reference `PRAGMA integrity_check` returns `ok` for the SclJet-written REAL/BLOB file.

## v2-coroutine-example-tools-check-resolution — runnable native demo fails static check
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 3cb6209a4 -->

**Status:** DONE (2026-07-21, fix `3cb6209a4`, verification/spec `e2bad7262`; found and confirmed by
codex-q4 in exact-SHA CI run `29858870257` and the same assembled checker path locally; reported with
root cause to `@scalascript` / `@claude-code` in Rozum; SPRINT Q4.4c).

**Reproduce:** build the assembled launchers at the exact SHA, then run
`./bin/ssc-tools check examples/coroutine-demo.ssc` (the CI job runs
`./bin/ssc-tools check examples/*.ssc`). The checker reports `Reference to undefined name` for
`coroutineCreate` at lines 1/2 and `coroutineCancel` at lines 7/8 and exits 1. In the same job, the
full conformance corpus and all 17 selected examples on VM, direct ASM, and `build-jvm` pass, so
successful execution is not evidence that the static-check surface resolves the linked Coroutine
API.

**Root cause / fix:** execution made the Coroutine globals available through the runtime/prelude, but
the standalone document omitted the explicit `std/coroutine.ssc` link required by the static checker.
The demo now imports `Step`, create/resume/suspend/cancel from that module. The exact focused and
all-examples assembled checker commands pass; provider tests remain 9/9, focused conformance remains
3/3, and native VM/direct-ASM/standalone-JAR stdout is byte-identical. The existing all-examples CI
step is the real-path regression and still fails if the link is removed.

## v21-scljet-vfs-standard-gate-inventory-drift — staged provider JARs evade one gate and break another
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: c6cf03634
     gate: tests/e2e/v21-plugin-backend-isolation-smoke.sh -->

**Status:** DONE (2026-07-21, `c6cf03634`; found and confirmed by codex-q4 in the real final
provider-isolation gate; reported with root cause to `@scalascript` / `@claude-code` in Rozum;
SPRINT Q4.4a).

**Reproduce:** after `scripts/sbtc "installBin"`, run
`tests/e2e/v21-plugin-backend-isolation-smoke.sh`. It exits 1 because
`scripts/v21-core-dependency-gate` reports both
`scalascript-scljet-vfs-host_3-0.1.0-SNAPSHOT.jar` and
`scalascript-v2-native-scljet-vfs-plugin_3-0.1.0-SNAPSHOT.jar` as unclassified in the closed standard
layout. In the same staged image, `tests/e2e/v21-native-plugin-boundary-smoke.sh` passes without
inventorying either JAR, so its green result proves nothing about their dependencies or service
boundary.

**Root cause / fix:** feature commit `6131e17a3` added the native SclJet VFS plugin and shared
zero-dependency host to `installBin`, but the two explicit measurement inventories were not updated.
Both now have closed-layout ownership and native-boundary `jdeps` checks; only the plugin must carry
the native ServiceLoader entry, while the host is required not to. Dependency self-test,
closed-layout smoke (27 roots / 129 edges / 43 classified JARs / 0 violations), backend isolation,
and native plugin boundary all pass in the staged distribution.

## coroutine-contract-doc-drift — normative and feature specs disagree with the shipped surface
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 6a9f434e4
     gate: tests/conformance/run.sh -->

**Status:** DONE (2026-07-21, contract `6a9f434e4`, verification `54ebca43d`; found and confirmed by
codex-q4 while specifying `v2-native-coroutine-provider`; SPRINT Q4.1/Q4.4).

**Reproduce:** `SPEC.md` §7.5 says "Three primitive operations" but lists four functions, models
`Step` without the shipped/public `Errored(message)` case, and declares a by-name body while
`v1/runtime/std/coroutine.ssc` and both conformance cases use an explicit `() => T` thunk.
`specs/coroutines.md` separately repeats the old three-intrinsic pre-cancellation wording even though
its own later sections say cancellation landed.

**Fix / verification:** `SPEC.md`, `specs/coroutines.md`, and the new dedicated
`specs/v2.1-native-coroutine-provider.md` now define four functions,
`Yielded | Returned | Errored | Cancelled`, an explicit lazy thunk, terminal error/cancellation
semantics, and one shared Generator/Coroutine `suspend` owner. The feature spec landed before code;
markdownlint and `tests/conformance/run.sh --only 'coroutine-*'` pass.

## coroutine-demo-readme-link-missing — README points to an absent runnable example
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b8fd4a31c -->

**Status:** DONE (2026-07-21, example `b8fd4a31c`, docs `bfc893d99`; found and confirmed by codex-q4
while specifying `v2-native-coroutine-provider`; SPRINT Q4.3).

**Reproduce:** from the repository root, `test -e examples/coroutine-demo.ssc` exits 1 while
`README.md` lists `[coroutine-demo.ssc](examples/coroutine-demo.ssc)` in its runnable examples table.
The missing file makes the public Coroutines entry a broken local link and leaves the documented
low-level API without the required self-contained example.

**Fix / verification:** the linked example now demonstrates lazy two-way exchange, nesting, and
idempotent pre-start cancellation. Its stdout is byte-identical through the assembled standard VM,
direct ASM, and a freshly built `build-jvm` JAR; README, User Guide, and the feature spec link to the
same checked-in file.

## f-extension-instance-dispatcher-arity — F miscompiles a same-named extension method across 2 instances
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: bcfc47ec8 -->

**Status:** FIXED for the standalone+given collision class (2026-07-21). Root cause was a NAME COLLISION:
a standalone `extension` method `m` was emitted as the bare global `m`, colliding with the bare `m`
DISPATCHER synthesised for the given-body instances — so the runtime resolved `recv.m` to the wrong def
(wrong arity / `Stub`). **Fix (mirrors ssc1-lower :4101/:5458):** a method that has BOTH a top-level ext
and a given-body dispatcher has its standalone impl emitted as `m__ext_default` (extEmit mangle, driven by
a new `mangleExtMethods` = collectTopExtMethods ∩ dispatcher-method-names threaded through cx), and the
bare `m` dispatcher delegates its fallthrough to `m__ext_default` (`edFallback`) instead of returning the
receiver. `collectTopExtMethods` skips `given`/`trait` bodies AND requires a concrete `=` body (`=>` is
token 2,30, body `=` is 2,20) so trait-ABSTRACT ext members never count as top-level (else the dispatcher
would delegate to a non-existent `m__ext_default` → TYPEERR). **Fixes tagless-sealed-dispatch** and the
std-monaderror arity crash; verified fixpoint stage1==stage2 byte-identical, semantic 246/246, dualrun
default 43/43. TWO residual cases were filed (separate bugs, below); tagless-multi-file now FIXED, one open:

- **f-monaderror-none-match** — FIXED 2026-07-21 (`bcfc47ec8`). Root cause: the bare extension-method
  DISPATCHER for a multi-param typeclass given (`MonadError[Option, Unit]`) derived its receiver type-head
  from `innerTypeStr` = the WHOLE joined type-arg string `"Option,Unit"` and fed that to `extTypeTags`. No
  runtime value carries tag `"Option,Unit"`, so the `__isTag__` test ALWAYS failed: with 1 instance the
  dispatcher returned the receiver (None instead of Some(99)); with 2 instances a None receiver fell through
  to the standalone Either impl's `match {Right|Left}` → runtime `match: no arm for None/0`. Fix (mirror
  ssc1-lower firstTypeArg :5339 / collectExtDispatch :5399): apply `firstTypeArg` (depth-0 prefix up to the
  first top-level comma, bracket-aware) to the TC type-args in `collectED2` before `extTypeTags`, so the
  dispatcher tests the CONTAINER type's ctor tags (Some/None). No-op for single-param TCs. Verified: X1
  fixpoint stage1==stage2 byte-identical, semantic 247/247, minimal repro (top-level-statement form, the
  shape the conformance harness wraps) now byte-identical to the reference front + prints Some(99). Real
  multi-file std-monaderror end-to-end equality is EXPECTED (the default front already prints 192 B → the
  fenced input does not trigger extension-group swallowing; F's dispatcher is now byte-identical to default)
  but was NOT observed via `SSC_FRONT=F bin/ssc run` (no staged install in this worktree) — a follow-up
  should confirm it and flip the std-monaderror GAP in `specs/v2.2-p6.5-dualrun.expected`.
- **tagless-multi-file `map`** — FIXED 2026-07-21 (`1a04abdc8`). NOT actually a `map`/builtin-collision case:
  the crash was on Monad's PLAIN `def log`/`def pure` (non-`extension` members) that immediately FOLLOW an
  `extension …` block inside a `given … with` body. Root cause: `skipExtBodyZ` (member-collection helper)
  CONSUMED the depth-0 `;` separator, so `collectOMExtMs` could not tell a glued extension member (deeper
  indent, NO `;`) from a dedented `;`-separated plain `def`, and mis-glued the plain `def` as an extension
  member → its impl emitted receiver-prepended (`lam 2`, arity N+1) + a spurious bare `m` dispatcher →
  `g.m(a)` / `recv.m(a)` crash `arity: 2 expected, 1 given`. Fix: KEEP the `;` (`Cons t rest`) so
  `collectOMExtMs` reads it as the extension-block boundary; `collectEMs`/`collectTopEMs` (top-level ext
  registry, whose members ARE `;`-separated) wrap `skipExtBody` in `skipSemisF`, so keeping the `;` is
  byte-identical for them. Verified: fixpoint stage1==stage2 byte-identical, semantic 247/247 (0 mismatch),
  and F-vs-reference byte-identical stdout on the REAL multi-file path (`ssc1-run-fsub.ssc0` — fence
  extraction + import closure) — OLD fsub crashed there, NEW fsub prints all 4 lines matching default.

(original characterization retained below)

Original status: OPEN, F-lane only (found 2026-07-21, characterized while fixing the extension-member-
collection bug). PRE-EXISTING (fails on pre-fix F too). Distinct from the member-collection fix.

When the SAME extension method name is defined by TWO instances — one inside a `given … with` body and one
as a standalone `extension` — F synthesises a bare dispatcher that type-tests the receiver and forwards to
the matching `g_m`/global, but the arity is wrong: F errors `arity: N expected, N-1 given` at the call site
(or F crashes compiling the pattern). Minimal repro (bare):

```
trait ME[F[_]]:
  extension [A](fa: F[A]) def handleError(h: Int => F[A]): F[A]
given optME: ME[Option] with
  extension [A](fa: Option[A]) def handleError(h: Int => Option[A]): Option[A] = fa match { case None => h(0) case Some(x) => Some(x) }
extension [A](fa: Either[String, A])
  def handleError(h: String => Either[String, A]): Either[String, A] = fa match { case Left(e) => h(e) case Right(x) => Right(x) }
def main(): Unit = { val o: Option[Int] = None; println(o.handleError((n: Int) => Some(99))) }
```
default prints `Some(99)`; `SSC_FRONT=F` → `arity: 1 expected, 0 given`. A SINGLE instance works
(`mono3`); adding the second (standalone Either) `handleError` breaks it — so the fault is the
2-instance dispatcher synthesis (`collectExtDisp`/`emitExtDispatchers`/`edBuild` + the standalone
`extension` emitting a bare `handleError` global that collides with the dispatcher name). Likely fix:
the dispatcher must own the bare method name and the standalone impl must be prefixed (or the dispatcher
must fold the standalone instance in), with a consistent receiver-prepended arity.

**Blocks 3 residuals** in `v2.2-p6.5-dualrun.expected`: std-monaderror (`arity: 3 expected, 2 given`),
tagless-sealed-dispatch (rc=0 SILENT-WRONG, lines 8-9), tagless-multi-file (`arity: 2 expected, 1 given`
— EXPOSED once the extension-member fix removed its unbound-global fallback).

## cli-command-System.exit-kills-the-test-fork — a whole CLASS of green-looking CI reds
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: dcd00d1ab
     gate: tests/e2e/cli-exit-reachability-guard.sh -->

**Status:** FIXED as a class (2026-07-28, `cli-exit-reachability-guard`, `dcd00d1ab`). The class is now
GATED, not merely detected: `tests/e2e/cli-exit-reachability-guard.sh` fails the build if any test can
reach a CLI boundary that ends the process, and it runs in the validate job — part of the per-push
verdict. `scripts/detect-fork-exit` remains the after-the-fact half; this is the before-the-fact half.

**The class was already half-armed when the guard was written.** At object granularity 10 test-to-object
pairs exist, so an object-level rule would have been red from birth and switched off within the day. At
MEMBER granularity — direct exit calls plus a fixpoint over each object's own call edges — exactly one
survived: `OAuthCliTest` called `OAuthCli.run` five times. Not fatal today, and that is precisely the
point: `run` is `status` plus `if rc != 0 then sys.exit(rc)`, so whether it kills the fork depends on
the ARGUMENTS at run time, and all five calls happen to take success paths. The suite was one added
failure-path case away from the exact signature this entry describes. Moved to `status` (13/13
unchanged), which gives the guard a zero baseline and makes it a gate rather than a tolerated warning.

**Proven in three directions**, because a detector only ever observed staying quiet is not a detector:
`--self-test` asserts it FIRES on a synthetic test calling `run` and STAYS QUIET on the same call
against `status`; and reverting the real `OAuthCliTest` back to `run` makes it fire on the real file,
naming it. Known bound, recorded rather than left implicit: reachability is intra-object — a
cross-object call graph needs a real front end, and over-reaching produces the noise that gets guards
disabled.

**Historical status:** OPEN as a class — but **no longer invisible** (2026-07-27). `scripts/detect-fork-exit`
recognises the signature (a fork-exit line with ZERO reported failures), explains what it means, and
points at the suite that was running instead of at a failing assertion that does not exist. Wired
into CI behind `if: failure()` so it speaks only in the situation it is about, and behind `|| true`
so it can never turn a red step green. It ships with a `--self-test` asserting BOTH verdicts — fires
on a silent fork exit, stays quiet on an ordinary reported failure and on a clean run — because a
detector only ever observed staying quiet is not a detector; also checked against a real sbt log.

**The live instance is CLOSED 2026-07-27** by opus (`cli-oauth-exit-in-tests`), and the "~200 sites"
figure turns out to overstate today's exposure by two orders of magnitude.

**Measured, not assumed.** The count that matters is not `System.exit` sites but sites REACHABLE
from a test in-process. Of the six helper files this entry tabulates, tests reference exactly one:
`OAuthCli.run`, from `OAuthCliTest`. No test invokes `Main.main` or `StandardMain` in-process either,
so nothing else in `tools/cli` is currently reachable. `EmitCommands`, `ClusterCommands`,
`PluginCommands`, `SwiftUiCommands` and `LockCommands` — 105 sites between them — have zero test
callers today. They stay latent (a new test calling one re-arms them), but they are not live.

**Fix.** `OAuthCli` now computes an exit code: `status(args): Int` holds all the logic and every
helper returns 0/1/2, while `run` — the single boundary `Main.scala` calls — is the only thing that
may end the process. 13 `sys.exit` sites became 1, and it is not reachable from a test.

**The old test file was itself evidence.** Its header said it tested only "the offline paths that
don't call sys.exit" — the defect had shaped the tests to avoid every path that could catch it. The
seven failure-path cases that were impossible before now assert the status: unknown subcommand,
missing args for mint/introspect/discover/jwks/dcr-register, and a token that does not verify. A
success-side case keeps the suite from passing by returning non-zero for everything. 13/13.

**A/B — the signature reproduced deliberately, then removed.** With a throwaway test calling the
exiting boundary `run`, the run printed `Tests: succeeded 13, failed 0` and `All tests passed.` and
then died with `sbt.ForkMain … failed with exit code 2`; the probe test itself never appeared in the
report, having been killed mid-flight. That is this entry's signature exactly, produced on demand.
`scripts/detect-fork-exit` was then pointed at that REAL log — not a synthetic one — and correctly
reported the silent fork exit and named `OAuthCliTest` as the suite that was running.

**Historical status:** OPEN as a class (2026-07-20). One instance is fixed —
`swiftui-real-fixture-system-exit-hides-failure`, closed the same day by `frontend-tui-fetch-refresh`
(`deb5e6c90`). This entry exists so the next instance is recognised in minutes instead of hours.

**The shape.** A CLI *command helper* calls `System.exit(n)` on failure. A test invokes that helper
**in-process**. The exit kills the forked test JVM before ScalaTest can attach exit/stdout/stderr to
the test, so the run shows:

- every suite printing `All tests passed.`
- **no** `*** FAILED ***` anywhere in the log
- and then `Error during tests: … sbt.ForkMain <n> failed with exit code 1`

That is the project's most expensive failure mode in its purest form: the apparatus reports success
while hiding a real defect. In the proven instance the *actual* bug was generated Scala failing on
`selected()` / missing `selectFromView` — invisible until the exit path was removed.

**Why it will recur.** `System.exit`/`sys.exit` appears **459** times in v1 production sources.
259 are in `tools/cli/Main.scala`, where a CLI entry point exiting is legitimate. The remaining ~200
are in *command helper* files, which are exactly what tests call in-process:

| File | exit sites |
|---|---|
| `EmitCommands.scala` | 32 |
| `ClusterCommands.scala` | 23 |
| `PluginCommands.scala` | 21 |
| `SwiftUiCommands.scala` | 16 ← the one that bit |
| `OAuthCli.scala` | 13 |
| `LockCommands.scala` | 13 |

**How to recognise it fast.** `ForkMain … exit code 1` **plus** zero `*** FAILED ***` in the whole
log means "something called System.exit", not "a test failed". Do not go looking for a failing
assertion — find which suite was running when the JVM died, then find the exit call it reached.

**Fix direction (do not paper over the non-zero exit).** A helper reachable from a test must return a
status instead of exiting; the test then asserts on exit/stdout/stderr. That is what the SwiftUI fix
did — it switched to invoking the staged `ssc-tools package --v1 --target macos` command and
capturing its result. Reproduce:
`java -jar … ` style in-process invocation, or run the suite and watch for a fork exit with no
reported failure.

**Done-when:** command helpers reachable from tests no longer terminate the JVM, and a deliberately
failing fixture produces a reported test failure rather than a silent fork exit.

## ssc0c-multifile-uselib-ir-divergence — self-hosted compiler disagrees with the Scala seed across an import
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 3056aa3b8
     confirmed: no -->

**Status:** FIXED 2026-07-19 by `v2-f7-internal-gate` (`3056aa3b8`; awaiting full F7 exact-SHA CI).
Found by the 2026-07-18 v2-state audit at `358facd8e`; re-measured after both the compiler and gate moved.

**Real-harness repro:** run `bash v2/conformance/check.sh` from a clean worktree and preserve its real
exit code (never `| tail`). The focused comparison is the same one the gate performs:

```bash
scala-front:  ssc compile examples/uselib.ssc0
self-hosted:  ssc run bin/ssc0c.ssc0 examples/uselib.ssc0
```

At the audited SHA the gate reported differing Core IR while the single-file and multi-file self-fixpoints
still passed. Fresh full-gate evidence shows that label was secondary and misleading: its default-stack
self-hosted command exits with `StackOverflowError`, retries, and never supplies a valid comparison side.
Running the same self-hosted command on `-Xss512m` succeeds; then the semantic/canonical payload has
converged. `/tmp/v2-f7-uselib-seed.ir` and `/tmp/v2-f7-uselib-self.ir` share all first 2865 bytes. The
remaining exact mismatch is one trailing LF: seed output is 2866 bytes and ends `29 0a`, while self-hosted
output is 2865 bytes and ends `29`. SHA-256 values are respectively
`924205b198d594fdd9683a25dcce48b21f2207ea930d86f988f1ebf652f04975` and
`d435633db63812dc39cf613d50b9cbeffc8dc9eaa080da10dbf748f6d5464a96`.

**Apparatus defect:** `v2/conformance/check.sh` captures both programs with command substitution
(`ua=$(...)`; `ub=$(...)`), and POSIX command substitution strips trailing newlines before comparison.
The gate therefore pre-processes away the only current byte mismatch and can print a false byte-identical
result. The gate must materialize both complete streams and `cmp` them before classification.

**Plan / done-when:** make both compiler CLIs obey one line-termination contract, save and compare complete
streams before classification, and add a real two-file regression that deliberately proves a trailing-byte
mismatch is caught with paths/sizes/diff. Done requires exact bytes for `uselib`, both fixpoints, and the
complete v2 gate; command-substitution normalization, weakening the comparison, or refreshing an expected
blob is forbidden.

**Resolution:** both CLIs now emit one trailing LF, and every compiler differential/fixpoint writes complete
streams to named artifacts before `cmp`. Empty streams fail even when equal; mismatches report paths, sizes,
the first 16 byte differences, and tail hex. A self-test proves `x` differs from `x\n` and that two empty
streams are not success. The persistent two-file fixture and `uselib` compare exactly at 259/259 and
2866/2866 bytes; `CONF_FAST=1 bash v2/conformance/check.sh` exits 0 with 408 ok / 0 FAIL.

## newfront-scala-spike-jvm-test-links-on-js — shared filesystem suite breaks Scala.js
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: aca439fcc -->

**Status:** FIXED (2026-07-17, `ci-last-red`, coordinated with the stale `v3-newfront-p1-toplevel`
owner in rozum; no objection). `ScalaSpikeSpec.scala` moved out of the shared crossProject test dir
`uniml/core/src/test/scala/...` into the JVM-only `uniml/core/src/test-jvm/scala/...`, wired via
`unimlCross`'s new `.jvmSettings(Test / unmanagedSourceDirectories += .../src/test-jvm/scala)` — the
same convention `unimlYaml` already uses. The filesystem-bound suite is no longer linked for
Scala.js. VERIFIED: `unimlJs/Test/fastLinkJS` succeeds (was 13 linking errors) and
`uniml/testOnly ...ScalaSpikeSpec` runs 60/0 on JVM. The pure spike helper `ScalaSpike.scala`
(no filesystem refs) stays shared and links for JS unchanged. Was: `unimlJS / Test / fastLinkJS`
failed its core module analyzer on `ScalaSpikeSpec.testFun$proxy59/60` with non-existent
`java.io.File` / `java.nio.file.Files` classes/methods (reconfirmed 2026-07-17 by `ci-red-main` in
`scripts/sbtc "test"` at `aca439fcc`).

**Root cause / real-harness evidence.** The corpus projection/batch/output tests use host files and
live in `uniml/core/src/test/scala/.../ScalaSpikeSpec.scala`, a source directory shared by the JVM
and Scala.js sides of `unimlCross`. Scala.js therefore links JVM-only test bodies even though the
harness is intrinsically filesystem-bound. Current diagnostics name `Files.deleteIfExists`,
`Files.readAllBytes`, `Files.writeString`, `Files.createDirectories`, `File.listFiles`, and
`File.toPath`; this is a linker failure before JS tests execute, not an assertion to skip.

**Expected/fix plan.** Move the filesystem-bound spike suite (or split only its host-file tests) to
the crossProject JVM test source, preserving the parser/projection tests on every backend only where
they remain platform-neutral. The aggregate must link and execute its JS tests, while the JVM suite
must still run the real C_min and projection assertions. Do not add fake `java.io` shims or silence
the linker. This overlaps the stale clean newfront claim; `ci-red-main` records and consumes the
owner fix but does not edit it without takeover authority.

## coord-status-ignores-heartbeat-age — old claims with live worktrees look current
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 52e1d0814
     confirmed: no -->

**Status:** FIXED (2026-07-17, `52e1d0814`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` at `5d932f6a4`. Project coordination rules
define a missing or older-than-20-minute `heartbeat:` as potentially orphaned and name
`scripts/coord-status` as the preferred status check. The script's `stale-claim check` instead tests
only whether a slug heuristically matches any worktree/branch. Consequently the current output says
`no stale-looking claims` while `v3-newfront-p1-toplevel` is about 18 hours old,
`scljet-m3-writes` about 41 hours old, and `v2-swift-nativeui-i18n-json` several days old.

**Root cause / real-harness evidence.** The stale loop never parses `heartbeat:` or computes age;
any matching worktree suppresses the row forever. This is independent of the zero-token branch bug
fixed in `8ad5f4d1e`: exact branch identity answers whether a worktree exists, while heartbeat age
answers whether its owner is still live. Neither observable may pre-classify the other.

**Expected/fix plan.** Parse strict UTC heartbeat timestamps portably on macOS and Linux, compare
against a test-overridable current epoch, and report a distinct potentially-stale heartbeat row at
age greater than 20 minutes even when the declared worktree branch exists. Missing/invalid
heartbeats are stale with a named reason; a fresh heartbeat stays live; a fresh claim whose branch
is absent retains the separate missing-worktree warning. Hermetic tests must fix time and compare all
three outcomes with timestamp/age/branch diagnostics.

**Fix/verification.** `coord-status` now parses strict `...Z` timestamps with GNU/BSD `date`, uses
an injectable epoch only for deterministic checks, and evaluates heartbeat age independently from
the exact branch/worktree match. The hermetic gate covers fresh/live, 1201-second stale/live,
fresh/missing-worktree, invalid heartbeat, and missing heartbeat. The real status now reports all
five old claims with exact timestamp, seconds/minutes, reason, and live/missing branch while keeping
the current `ci-red-main` claim fresh. Shell syntax, the complete gate, and focused conformance pass.

## coord-status-stopword-slug-false-stale — live claim is reported as stale
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 8ad5f4d1e
     confirmed: no -->

**Status:** FIXED (2026-07-17, `8ad5f4d1e`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` at `39feb9cc3`. A fresh authoritative
`.work/active/ci-red-main.claim` names the live branch `feature/ci-red-main-final`, and that exact
clean worktree exists, but `scripts/coord-status --no-fetch` prints:

```text
maybe stale: ci-red-main (... heartbeat: 2026-07-17T04:25:56Z ...)
```

**Root cause / real-harness evidence.** `significant_tokens("ci-red-main")` yields no tokens: `ci`
is shorter than three characters, while `red` and `main` are explicit stop words. Therefore
`slug_matches_record` has `needed=0` but no loop iteration in which it can return success. The
exact-key fallback also misses because the worktree key is `feature/ci-red-main-final`, not the
claim slug. This is a false stale classification in the coordination apparatus, not a stale claim.

**Expected/fix plan.** Prefer an exact live-worktree branch match from a claim's explicit `branch:`
metadata, retaining the current slug heuristic only for legacy claims without that field. Add a
hermetic regression whose slug has zero significant tokens and whose declared branch is live; it
must reject the old `maybe stale` output while preserving detection of a genuinely missing
worktree. Every mismatch must print the expected claim branch and observed worktree branches.

**Fix/verification.** `coord-status` now parses a claim's explicit `branch:` and compares it exactly
with the collected live worktree branch keys before invoking legacy slug heuristics. The e2e gate
creates a temporary live branch/worktree for `ci-red-main`, proves the old false-stale row is absent,
then points the same claim at a missing branch and requires that exact stale row. Both failure paths
print the expected branch and observed branches. The gate passes and cleans its temporary Git state;
the real current claim now reports `no stale-looking claims`.

## ci-example-typecheck-uses-compiler-free-launcher — green examples fail at the workflow command
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: a421d9077
     confirmed: no -->

**Status:** FIXED (2026-07-17, `a421d9077`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` in Linux run `29549382274`, SHA `d5492a129`. The conformance job passes the full
corpus 282/282 and all-examples backend parity, then its `Type-check examples (ssc check)` step ran
`./bin/ssc check examples/*.ssc`. The standard launcher correctly rejected that compiler command:

```text
ssc: 'check' requires the optional ScalaScript tools/compatibility tier; run ssc-tools explicitly
or install the full distribution
```

The same staged checkout's `./bin/ssc-tools check examples/*.ssc` exits 0 and reports every example
OK (with only documented warnings). This is launcher routing in `.github/workflows/ci.yml`, not a
typechecker failure and not a reason to put compiler tooling back into the standard distribution.

**Expected/fix plan.** Route only this compiler-bearing CI step through installed `bin/ssc-tools`,
preserve the standard launcher's negative contract, and rerun a focused conformance slice before
push. Exact-SHA Linux confirmation must show the type-check step green and continue to later steps.

**Fix/verification.** `a421d9077` names and invokes `ssc-tools check` in the workflow. Locally the
old standard command still exits 1 with the intended tier message, while the replacement checks the
complete examples glob successfully (documented warnings only). The two actor leader conformance
cases remain 2/2 across INT/JS/JVM before push.

## actor-leader-conformance-has-no-expected — tracked leader cases always skip
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: f403cb952
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** FIXED (2026-07-17, `f403cb952`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` while selecting the affected gate for the multi-backend actor failure.
`tests/conformance/run.sh --only 'actors-leader-protocol' --no-memo` found the tracked case but
printed `SKIP (no expected/actors-leader-protocol.txt)` and finished with `0 passed, 0 failed out of
0 tests`. Therefore the case compared no backend output and could not detect a broken protocol.

The same family has at least one more empty gate:
`tests/conformance/run.sh --only 'actors-cluster-leader' --no-memo` also finds its source, reports
`SKIP (no expected/actors-cluster-leader.txt)`, and ends 0/0. Neither tracked leader scenario
currently compares any backend.

**Expected/fix plan.** After checking actor ownership, run the source on every declared backend,
compare the actual observable output, and add the expected fixture only when they agree; a guessed
file or an expected value copied from one broken lane would pre-judge the result. Keep this separate
from the `BigInt` runtime fix so activation can expose rather than conceal that bug.

**Activation result.** Compare-first execution exposed and blocked on the separate JVM history bug
above. After `34685277c`, normalized INT/JS/JVM bytes agree for both sources. `f403cb952` adds only
those measured observables; a forced wrapper run executes 2/2 cases, with PASS on INT, JS, and JVM
for each rather than the former 0/0 skips.

## jvm-bytecode-sibling-tests-ignore-installed-cli — thirteen assertions cancel before comparison
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 11a9e80e2
     confirmed: no -->

**Status:** FIXED (2026-07-17, `11a9e80e2`; awaiting CI confirmation). Found by `ci-red-main` while
auditing the Linux test tail after `JvmBytecodeRuntimeSeparationTest` was repaired. With the current
installed distribution already built, run:

```text
scripts/sbtc "cli/testOnly scalascript.cli.JvmBytecodeLinkCliTest scalascript.cli.JvmDirectDriverTest scalascript.cli.ReproducibilityTest scalascript.cli.JvmSmapStackTraceTest scalascript.cli.SourceMapJvmTest"
```

The five suites report 6 executed/pass and **13 `CANCELED`**. The split is exact:
`JvmBytecodeLinkCliTest` 1/4, `JvmDirectDriverTest` 0/3, `ReproducibilityTest` 4/1,
`JvmSmapStackTraceTest` 1/2, and `SourceMapJvmTest` 0/3 (executed/cancelled). Eight cancellations
look for compiler-driver jars through `ImportResolver.libPath` / the old `cli/stage` layout even
though `installBin` populated `bin/lib/compiler/jars`; five invoke the fat JAR without the installed
launcher's `-Dssc.lib.path`, so `CompilerLoader` aborts before bytecode or source-map observables are
compared.

**Expected/fix plan.** Make only the reproduced suites consume the supported installed
`bin/ssc-tools`/library tree and derive Scala runtime classpaths from classes loaded by the test JVM,
not a macOS Coursier cache. Preserve an explicit cancellation when a developer truly has not run
`installBin`; once staged, every command must execute and failures must include exit/stdout/stderr.
Decode current binary `.scjvm` artifacts with production `JvmArtifactIO` rather than the stale JSON
readers found behind the cancelled gates. Audit `ClusterMultiBackendMatrixTest` separately before
editing it because it was not part of this baseline.

**Additional apparatus defects exposed by source inspection.** `ReproducibilityTest` currently
declares two outputs equal when their SHA-256 strings match instead of comparing the bytes first,
and converts its ordered ZIP-entry buffer with `.toMap` before asserting entry order. Repair both so
the observable bytes/order drive the verdict and hashes are diagnostics only. `JvmSmapStackTraceTest`
and `SourceMapJvmTest` also turn any non-zero compile result into `CANCELED`; after the installed
launcher and compiler jars are present, those are real failures and must retain exit/stdout/stderr.

**First faithful staged result.** After switching to installed `ssc-tools`, all **19** tests execute:
15 pass, 4 fail, and 0 cancel. `JvmDirectDriverTest` is 3/3, `ReproducibilityTest` 5/5,
`SourceMapJvmTest` 3/3, `JvmSmapStackTraceTest` 2/3, and `JvmBytecodeLinkCliTest` 2/5. Three failures
are one helper defect: its current code-source classpath does not actually make Scala runtime
classes visible to the spawned `java` process (`scala.Predef$` / `scala.Option` missing). Measure
the loaded locations/test classpath and select the real runtime JARs, then retain the end-to-end JVM
execution assertions. The fourth failure is a contract question, not yet pre-classified: linked JARs
now contain `_ssc_runtime.tasty`, `a_sc.tasty`, and `b_sc.tasty` while the old test demands no TASTY.
Read the linker spec/history and test downstream separate-compilation needs before deciding whether
production or the assertion is stale.

**Fix/result.** The five suites share a bounded installed-distribution locator and invoke real
`bin/ssc-tools`; compiler commands now fail with exit/stdout/stderr once staging is present. Binary
artifacts use `JvmArtifactIO`. Reproducibility compares actual bytes and ordered ZIP entries before
printing SHA diagnostics. Scala runtime JARs come from loaded class resource URLs (Coursier reports
only a Maven directory through protection-domain code source on this host), and `CompilerLoader`
honours the live supported property before its cached fallback for embedded direct-driver calls.
The TASTY assertion was stale: Tier 5 commit `e401aa566` and `specs/v2.0-artifact-format.md` require
linked JARs to retain module/runtime TASTY for downstream Scala 3 compilation, so the test now
requires all three observed entries. Final focused result is 19/19 with zero failures/cancellations;
runtime-separation plus facade regressions are 12/12, and `dataset-parallel-jvm` passes.

## swiftui-real-fixture-system-exit-hides-failure — compiler error kills the forked test JVM
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-20, `frontend-tui-fetch-refresh`). Found by `ci-red-main` in Linux run
`29545769651`, job `87777659720`, and reproduced again by exact run `29728630760`. Under
`SwiftUiRealFixtureBuildTest`, generated Scala failed on `selected()` and missing `selectFromView`,
but ScalaTest printed no `*** FAILED ***` row for the fixture before another suite began.

**Root cause / real-harness evidence.** The test calls `buildSwiftUIPackage` in-process. Its own
comment records that this production helper calls `System.exit(1)` when bytecode compilation fails,
which terminates the forked test JVM before ScalaTest can attach exit/stdout/stderr to the test. The
old guard only cancels when `scala-cli` is absent; it does not contain an actual compiler failure.

**Fix/result.** `JvmRuntimeUiPrimitives` now supplies the missing JVM snapshot implementation and the
hoisted primitive import includes it. The erased `Signal[T]()` bridge is scoped only to
`std.ui.lower`; the initially tested file-level extension was rejected because it shadowed ordinary
`String.apply(Int)` calls. The test now invokes the supported staged
`ssc-tools package --v1 --target macos` command, captures exit/stdout/stderr, and retains all
generated-package and executable
assertions. A deliberately invalid fixture returns non-zero with its compiler diagnostic and the
second named test continues. Focused result: real harness 2/2, SwiftUI 118/118, TUI 36/36, browser
select reconciliation 1/1.

## newfront-scala-spike-fixture-paths-linux — tracked C_min and output root depend on host CWD
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-17, `ci-last-red`, coordinated with the stale `v3-newfront-p1-toplevel`
owner). Both failures were CWD/host-path assumptions in the (now JVM-only) `ScalaSpikeSpec`, not
spike-logic regressions — the guard was wrong, the self-host it guards is green. Fixes: (1) a
CWD-independent `repoRoot` resolver (walks up from `user.dir` to the `build.sbt` + `specs/` ancestor,
same shape as `SwiftBackendTest`) now anchors the C_min fixture at `repoRoot/specs/v2.2-p6.6-cmin.L`
(the `CMIN_L` env override is kept); (2) the `emit projections` test defaults `SPIKE_OUT` to the
always-writable `repoRoot/target/uniml-spike-out` instead of a hardcoded macOS `/private/tmp/...`
scratchpad. VERIFIED: `uniml/testOnly ...ScalaSpikeSpec` runs 60/0 (P6.21 C_min + emit both green).
Was: two deterministic filesystem failures — tracked `specs/v2.2-p6.6-cmin.L` not found from the sbt
module CWD, and `emit projections + toys for the diff harness` attempted to create `/private`, which
Ubuntu rejects with `java.nio.file.AccessDeniedException` (found by `ci-red-main` in Linux runs
`29544412767` / `29545769651`, job `87777659720`).

**Real-harness repro.** Run the JVM `uniml` `ScalaSpikeSpec` from aggregate `sbt test` on Ubuntu (or
set its process CWD to the module base). The C_min case must resolve the tracked repository fixture;
the diff-output case must use an explicit temporary/output directory inside the runner workspace,
not a macOS-root path. Missing tracked input is failure, while optional batch env inputs may remain
explicitly classified.

**Coordination.** `ScalaSpike*` is the authoritative live newfront claim's scope. `ci-red-main`
records both exact failures and waits for that lane to land; no concurrent test/source edit is safe.

## registry-seed-test-cwd-cancel — tracked packages.yaml validation silently skips on Linux CI
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a99973c16
     confirmed: no -->

**Status:** FIXED (2026-07-17, `a99973c16`; awaiting CI confirmation). Found by `ci-red-main` in
Linux run `29545769651`, SHA `893bf2632`, job `87777659720`. `RegistrySchemaTest` cancelled
`seed registry/packages.yaml parses and validates without errors` with
`registry/packages.yaml not found — skipping seed validation`.

**Real-harness evidence.** GitHub checks out the complete repository before `sbt test`; the seed is
tracked at repository-root `registry/packages.yaml`. The suite nevertheless treats its own missing
path as an optional capability and returns green/cancelled because it searches relative to an sbt
module CWD. Run the focused `scalascript.imports.RegistrySchemaTest` from the normal aggregate build
to reproduce the path classification.

**Expected/fix plan.** Resolve the repository root robustly across shared checkout, external Git
worktree, and sbt project working directories; validate the tracked seed through that path. If the
fixture truly is missing, fail and print every searched location instead of cancelling. Keep the
existing parse/schema assertions unchanged. Done means the focused suite executes the seed case on
macOS and Linux-shaped paths rather than pre-judging it as unavailable.

**Fix/result.** The locator walks ancestors from process CWD, `user.dir`, and the loaded test class,
bounded by each path's segment count so it includes filesystem root once without walking above it.
The regression explicitly starts at `v1/lang/core`, matching aggregate sbt's module CWD. A missing
tracked seed now fails with all searched candidates. The suite executes 15/15 with zero cancellations
and passes; focused cross-backend conformance stays green.

## jvm-bytecode-runtime-tests-ignore-installed-drivers — five CI assertions cancel before comparing
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1c109e49e
     confirmed: no -->

**Status:** FIXED (2026-07-17, `1c109e49e`; awaiting CI confirmation). Found by `ci-red-main` in
Linux run `29545769651`, SHA `893bf2632`, job `87777659720`.
`JvmBytecodeRuntimeSeparationTest` reported five `CANCELED` cases rather than success/failure because
it claimed compiler-driver jars were not staged.

**Real-harness evidence.** The same job's preceding `Compile and assemble ssc.jar` step runs
`sbt compile cli/assembly installBin` and explicitly logs `bin/lib/compiler/jars/ (6 JARs incl.
compiler-driver)`. During `sbt test`, however, all five runtime-separation cases cancel with
`compiler-driver jars not staged (run sbt cli/stage)`. Reproduce locally with
`scripts/sbtc "cli/assembly; installBin; cli/testOnly
scalascript.cli.JvmBytecodeRuntimeSeparationTest"` and compare the test's path detection with the
installed launcher/library tree. A cancellation is not a green comparison.

**Expected/fix plan.** Make the suite consume the same staged tools/compiler-driver location that
the supported `installBin` task creates (prefer the real installed launcher contract over a fat-jar
proxy). Keep capability union, unchanged-runtime timestamp, link, and standalone-runtime assertions
intact. Missing staging may cancel for an ad-hoc developer invocation, but after `installBin` all
five cases must execute and report their real stdout/stderr/exit mismatch instead of pre-judging.
Source inspection found the next hidden platform skip before implementing: the final test locates
Scala libraries only under macOS `~/Library/Caches/Coursier`, whereas GitHub uses Linux cache paths.
Use code-source locations already loaded by the test JVM for Scala 3 and 2.13 libraries; do not
replace one guessed cache path with another.

**Next layer exposed by the faithful run.** After routing through staged `ssc-tools` and resolving
Scala libraries from the test JVM, all five cases execute and all five fail. Four use `ujson.read`
directly on current `.scjvm` / `.scjvm-runtime` files and abort at byte zero because those artifacts
are now binary, not JSON. The link/run case reaches its size assertion and measures 762,184 bytes
against a stale `<400,000` bound. The fix must use the canonical production artifact decoder and
compare the real runtime-separation invariant; merely raising the old number would pre-judge the
result and could preserve duplicated runtime payloads.

**Fix/result.** The suite invokes installed `bin/ssc-tools`, verifies its actual
`bin/lib/compiler/jars` tree, and resolves Scala 3/2.13 runtime locations from classes already loaded
by the test JVM instead of an OS-specific cache. Current binary artifacts are decoded through
production `JvmArtifactIO`. The stale absolute JAR threshold is replaced by an in-run comparison:
the shared runtime bundle/artifact must be at least ten times the trivial module, directly proving
the runtime was not duplicated. All five cases execute and pass with zero cancellations; focused
JVM conformance passes.

**Related family audit queued as SPRINT 5r.** Several bytecode/link/source-map suites that the old
Linux step never reached contain similar `ImportResolver.libPath`, `cli/stage`, or macOS Coursier
assumptions. They are not pre-labelled broken: run them after the real `installBin` first, then
extend this apparatus fix only where a cancellation reproduces.

## v21-slim-distribution-gate-silent-assertions — Linux gate exits 1 with no failed check or diff
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 68ff5dacd
     confirmed: no
     gate: tests/e2e/v21-slim-distribution-gate.sh -->

**Status:** FIXED (2026-07-17, `68ff5dacd`; awaiting its queued Linux run `29548820854`). Found by
`ci-red-main` in run `29547476776`, SHA `0018dbf0c`, job `sbt — compile and test`. The release and
explicit-lanes gates pass, then
`v21-slim-distribution-gate.sh` runs for 70 seconds and exits `1`; the complete job log contains no
output between the step header and `Process completed with exit code 1`.

**Real-harness repro.** Run `tests/e2e/v21-slim-distribution-gate.sh --report
target/v21-slim-distribution.tsv` after `scripts/sbtc "installBin"`. The script contains dozens of
bare `[[ $(run_standard ...) == expected ]]` and file-state `[[ ... ]]` assertions under
`set -euo pipefail`. Any mismatch aborts before naming the check, expected value, actual value, exit
code, or diff. The CI log therefore cannot identify whether the underlying problem is semantic,
platform-specific, or stale expectation.

**Expected/fix plan.** Compare each real observable first through named helpers that preserve
stdout/stderr/exit, print expected/actual and a diff on mismatch, and only then classify failure.
Reproduce the current Linux failure from the newly diagnostic run; do not guess at or refresh an
expectation while the apparatus is blind.

**Fix/result.** Every semantic assertion now routes through named helpers that preserve stdout,
stderr, and exit status, and prints expected/actual plus a unified diff before failure. File-state
and negative checks likewise identify themselves. The complete gate passes locally. A later Linux
run, `29547740771` at `b829c8264`, passes the same slim step, proving the earlier semantic/platform
mismatch was not persistent; the first run containing the diagnostic implementation is still
queued, so Linux confirmation of the apparatus itself remains pending.

## ci-status-fixture-accepts-invalid-jq — fake-gh green hid a real CLI parse failure
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: c43d8f523
     confirmed: no
     gate: tests/e2e/ci-status-guard.sh -->

**Status:** FIXED (2026-07-17, `c43d8f523`; awaiting final CI confirmation). Found by
`ci-red-main` before the exact-SHA guard landed. The new fixture matrix passed 6/6, but the first
real `scripts/ci-status` invocation exited `2` with `failed to parse jq expression ... unexpected
token "\\"` instead of reporting the run.

**Reproduce.** `tests/e2e/ci-status-guard.sh` passes because its fake `gh` returns canned output and
ignores the `--jq` expression. Running `scripts/ci-status` against authenticated GitHub parses the
same expression and rejects the escaped quotes in `(.conclusion // \"\")`. The fake is therefore a
proxy, not proof that the real query is accepted.

**Expected/fix plan.** Use valid gojq syntax, keep the fixture result matrix, and add the real
authenticated invocation to the implementation verification. The guard is not shippable until the
exact current SHA produces a genuine `PENDING`, `RED`, or `GREEN` result with named jobs; `UNKNOWN`
from a query/parser defect is red for this task.

**Root cause/fix.** The shell single-quoted jq program unnecessarily escaped the empty string inside
an interpolation; fake `gh` returned canned records without parsing the supplied program. The jq is
now accepted by the real CLI. The fixture matrix still passes, and authenticated queries return the
exact run URL plus all four named jobs. Spec verification records the proxy failure so a fixture-only
green is never used as release evidence again.

## install-dev-rewrites-tracked-ssc-launcher — successful staging leaves the checkout dirty
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: b829c8264
     confirmed: no -->

**Status:** FIXED (2026-07-17, `b829c8264`; awaiting Sergiy confirmation). Found by `ci-red-main`
immediately after the worktree-isolation fix `0018dbf0c`. This was a generator-authority drift: the
build succeeded and the launcher semantics were unchanged, but a documented developer command
rewrote a tracked file on every clean checkout.

**Real-harness repro.** From a clean linked worktree, run `bash install.sh --dev`, then
`git diff -- bin/ssc`. The diff adds only the AppCDS explanatory comment and two blank lines.
`install.sh` first runs `sbt cli/installBin` (whose `build.sbt` template writes one spelling) and then
overwrites the same launcher with its own non-byte-identical heredoc. A green build is therefore an
insufficient oracle; the required observable is a clean byte comparison after both generators run.

**Expected.** Both supported staging paths must produce the same tracked launcher bytes. The
regression must compare the output and print the real diff on mismatch; it must not classify a
comments-only change as harmless before comparing.

**Root cause/fix.** `install.sh` invoked `cli/installBin` and then duplicated three launcher
templates in heredocs, overwriting the fresh canonical output; the templates had already drifted.
The duplicate generator is gone. `cli/installBin` is the sole authority and the installer now
fails if any expected launcher is not executable. `tests/e2e/staged-launchers-clean.sh` runs in CI
immediately after the full install and lets `git diff --exit-code` print the exact patch before it
classifies failure. The gate was proven red against the old output, then green after the fix; the
full install and both focused conformance cases pass.

## install-dev-initializes-skills-submodule-inside-worktree — documented local build violates the worktree contract
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 0018dbf0c
     confirmed: no -->

**Status:** FIXED (2026-07-17, `0018dbf0c`; awaiting Sergiy confirmation). Found by `ci-red-main` on
`origin/main` `771b67d45`. This was a
workflow correctness bug, not merely redundant network work: project rules require
`.agents/plugins` to be initialized only in shared main, while the command printed by the
conformance wrapper for a missing launcher is `bash install.sh --dev`.

**Real-worktree repro.** Create a fresh worktree with `scripts/new-worktree <name>` and run the
printed command. Before any build output, `install.sh:54-57` announces `Updating git submodules...`
and unconditionally runs `git submodule update --init --remote --recursive`; git starts cloning
`.agents/plugins` into the worktree. The 2026-07-17 run was interrupted immediately, leaving only
an uninitialized empty submodule directory (`git submodule status` begins with `-`).

**Expected.** A worktree build must not initialize or update the skills submodule. `install.sh`
should detect a `.git` worktree file, use the shared-main skill checkout only for agent reads, and
continue building because the submodule is not a compiler input. A main-checkout install may retain
the explicit update. The regression gate must exercise both classifications without cloning or
running the expensive build.

**Fix/result.** `install.sh` detects the linked-worktree `.git` file, resolves the shared checkout
through `git rev-parse --git-common-dir`, and skips every submodule mutation while retaining the main
checkout update path. `tests/e2e/install-worktree-submodule-guard.sh` creates a real detached
worktree, compares both classifications, verifies the submodule gitlink remains uninitialized, and
runs in CI. The exact documented `bash install.sh --dev` command then completed staging in 4 seconds
from the feature worktree; both focused JavaScript conformance cases remained green on INT/JS/JVM.

## tower-thread-hardcoded-64m-stack — `run --bytecode` StackOverflowErrors on big programs, and `-Xss` cannot help
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-16, `RunNativeV2.scala` tower stack 64m → 512m). Found by running
`v21-negative-toolchain-release-gate.sh` locally — a CI step that had **never once run**, because
every CI run died at an earlier step. Not a scljet bug despite only scljet examples showing it.

**Symptom.** `bin/ssc-standard run --bytecode examples/scljet-bytes.ssc` (and `scljet-full.ssc`)
fails ~80% of runs with `Exception in thread "main" java.lang.StackOverflowError`, trace inside
`ssc.Compiler$.compile`. Nondeterministic; stdout is empty when it fails (it dies during compile,
before the program runs). Surfaces as `v21-negative-toolchain-release-gate` `parity.one-sided` ≠ 0,
naming a *different* scljet case each run — which looks exactly like flaky infra.

**Root cause.** `RunNativeV2.runTower` created its thread with a **hardcoded 64 MB stack**
(`new Thread(null, task, threadName, 64L * 1024L * 1024L)`). That thread runs the self-hosted front
**and** `Compiler.compile`, so it — not `main` — is where a deep compile overflows. Compiling a big
program recurses much deeper than running it, and 64m was not enough for the scljet examples.
Flaky because stack frame sizes depend on how much the JIT has compiled, which depends on machine
load: same input, different outcome.

**Two traps that cost real time here:**

1. **`-Xss` looked inert.** Raising the launcher's stack changed nothing — 64m, 128m, 256m and even
   `-Xss1g` all failed at the same rate (measured: 2/8 pass at 64m, 2/8 at 256m, 1/6 at `-Xss1g`).
   That non-correlation is what wrongly suggested "unbounded recursion / a race". The stack the
   overflowing thread uses was simply never `-Xss`.
2. **"thread main" is a lie.** `runTower` catches `Throwable` into a cell and rethrows it on the
   joining thread, so a **tower** StackOverflowError prints under main's banner while carrying the
   tower's trace. Do not conclude from the banner that `-Xss` should have applied.

**Reproduce** (before the fix; ~80% of runs):

```bash
for i in 1 2 3 4 5 6 7 8; do
  bin/ssc-standard run --bytecode examples/scljet-bytes.ssc >/dev/null 2>&1; printf '%s ' $?
done   # 1 1 0 0 1 1 1 1   → after the fix: 0 0 0 0 0 0 0 0
```

**The fix, and why the stack sizes are deliberately NOT one knob.** `-Xss` / `SSC_XSS` bounds the
**user program** (compiled and run on the calling thread); `RunNativeV2.TowerStackBytes` bounds the
**compiler**. Different jobs, very different depth needs. Sharing a knob breaks
`v21-direct-asm-recursion-smoke`, which pins 256k to prove the compiled lanes need no big stack —
that 256k must not starve the compiler that gets them there (tried it; the gate went red). Stack is
reserved address space, not committed memory, so 512m is cheap.

## descriptor-v3-body-local-effect-evidence — raw effect scan makes descriptors depend on method bodies
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: sbt core/testOnly *PreBodyApiDescriptorProducerTest*
     fixed-in: ff0e2580b -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `ff0e2580b`, landing SHA
pending independent approval.

**Symptom/reproduce:** put a local `effect Local:` declaration inside the body of an
exported method whose public header is otherwise unchanged. The raw carrier regex
classifies the body-local header as top-level effect evidence and strict production
returns a section-level `Left`. Removing only that method body declaration changes
the result, violating the pre-body descriptor/body-invariance contract.

**Root cause/plan:** raw effect-header extraction is lexically string-aware but not
declaration-scope-aware, and `bindEffectHeaders` performs a second unscoped
interpretation. Correlate raw headers only with declaration-scope marked AST
candidates using validated positions/owners/order, structurally exclude headers in
method/value/template bodies that are not part of the projected declaration scope,
and pass the one validated evidence model into binding. Do not re-scan the chosen
carrier independently after correspondence.

**Done when:** a faithful body-local-effect regression fails on the reviewed
checkpoint, body-only add/remove edits preserve canonical descriptor bytes and
`apiHash`, genuine top-level plain/multi/empty effects retain their evidence, and
the full affected gates pass. Keep `open` until fresh independent approval and
landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The body-local-effect regression fails with the section-level
`UNSUPPORTED_PUBLIC_DECLARATION` reported by the unscoped raw scan; all previous
63 producer tests remain green.

**Local verification:** `ff0e2580b` correlates sanitized raw headers only with
declaration-scope AST candidates, accounts for the parser's deterministic line
insertions, ignores body-local candidates, and stores the validated bindings for
later projection instead of rescanning. Body-local-only and same-name-before-real-
effect vectors are green; focused producer passes 82/82 and forced effect
conformance passes 9/9. Keep `open` until fresh review and landing.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. `ff0e2580b` is an ancestor of `origin/main`.

Pinned by:
  - `effect header evidence is scoped to executable blocks and missing erased evidence fails closed`

`sbt core/testOnly *PreBodyApiDescriptorProducerTest*`: **85 tests, 85 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## descriptor-v3-effect-sentinel-duplicate-collision — injected and user effect markers coexist
<!-- status: fixed
     lane: apparatus
     fixed-in: ab7678d0f
     area: front -->

**GATED AND FIXED — verified 2026-08-07 by reading the test against this entry's own recipe, not by matching names.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"a real effect cannot duplicate its parser-owned origin sentinel"*. It reproduces this entry's recipe verbatim — `effect Stable:` carrying
`private type __effectDecl__ = true` — and requires `UNSUPPORTED_PUBLIC_DECLARATION`.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `ff0e2580b`, landing SHA
pending independent approval.

**Symptom/reproduce:** source an actual `effect Stable:` whose body declares
`private type __effectDecl__ = true`. Parser preprocessing injects its own marker,
leaving duplicate reserved aliases, but strict managed production still returns
`Right`. The same collision class applies to `__effectUnsupportedShape__` and to
non-canonical marker aliases.

**Root cause/plan:** the producer treats marker presence as evidence and filters all
matching names without validating cardinality or exact parser-owned shape. For each
effect object require exactly one canonical `private type __effectDecl__ = true`,
require the unsupported-shape marker exactly when preprocessing the raw header calls
for it, reject duplicates/wrong RHS/modifiers/bounds/parameters, and reject either
reserved name anywhere it could be user-authored. Preserve marker filtering only
after validation, for both Document-backed and documentless packaged carriers.

**Done when:** faithful duplicate, malformed, and unsupported-marker collision
vectors fail on the reviewed checkpoint, then reject at stable managed-production
paths without descriptor/runtime members; parser and EffectAnalysis invariance plus
the full affected gates pass. Keep `open` until independent approval and landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The duplicate-sentinel regression fails because strict production returns
`Right` for an actual effect containing the colliding user alias; all previous 63
producer tests remain green.

**Local verification:** `ff0e2580b` validates both reserved names before filtering:
each marker must be the sole canonical unscoped-private, parameterless, unbounded
`type ... = true` declaration, and unsupported-shape evidence requires the origin
marker and raw-header agreement. Duplicate, malformed, non-type, ordinary-object,
and unexpected-unsupported vectors are green; focused producer passes 82/82.
Keep `open` until fresh review and landing.

## descriptor-v3-import-witness-omission — retained carrier import mutations evade correspondence
<!-- status: fixed
     lane: apparatus
     fixed-in: ab7678d0f
     area: front -->

**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"ordered imports participate in retained source and AST correspondence"*, which mutates the Document carrier `import foo.Int` → `import bar.Int` while the stored AST keeps the original, and requires `UNSUPPORTED_PUBLIC_DECLARATION`. Landed `ab7678d0f` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
exact frozen checkpoint `0cb46c3cd`; local correction `c55ac86e9`, landing SHA
pending independent approval.

**Symptom/reproduce:** parse a module containing `import foo.Int` and a public use of
`Int`, then mutate only the retained Document carrier to `import bar.Int` while the
stored AST and CodeBlock carrier still contain `foo.Int`. Strict production returns
`Right` and projects `Named("foo.Int")` because carrier correspondence treats both
sources as the same declaration surface.

**Root cause/plan:** imports now influence projection, but `declarationWitness`
omits `Import`, so the exact source/AST contract does not cover that semantic input.
Add ordered import witnesses at their lexical owner, preserving importer syntax and
every selector kind (direct, rename, wildcard, unimport/exclusion, given, given-all)
while remaining position/format/body invariant. Require stored AST, CodeBlock, and
optional Document witnesses to agree before constructing import scope.

**Done when:** the faithful dual-carrier mutation fails on the reviewed checkpoint,
same-header formatting/body edits stay invariant, nested/source-ordered import
positives remain green, and the full focused, descriptor/core/interop/IR/ABI plus
affected conformance radius passes. Keep `open` until fresh independent approval
and landing.

**Red baseline:** regression commit `c1f57d99f`; focused producer is exactly
63/70. The carrier-import mutation returns `Right` with `Named("foo.Int")`; all
previous 63 producer tests remain green.

**Local verification:** `c55ac86e9` adds ordered import witnesses containing the
importer reference plus direct/rename/unimport/wildcard/given/given-all selector
shape. The faithful carrier mutation is green and all later focused/full gates
remain green. Keep `open` until fresh review and landing.

## descriptor-v3-nominal-derives-early-loss — derives and early initializers disappear from nominal APIs
<!-- status: fixed
     lane: apparatus
     fixed-in: 21ae17ec0
     area: front -->

**GATED AND FIXED — and I got this wrong yesterday.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"early initializer headers participate in both retained source carriers"*, which changes the early-initializer header in the Document carrier AND in the CodeBlock carrier, and requires BOTH to be rejected. The `derives` half is covered separately by *"derives clauses reject on every directly parseable nominal form"*. Landed `21ae17ec0` (2026-07-15).

**My previous note here said STILL UNGATED, and the method was the defect.** I grepped for the identifiers this entry uses to describe the PRODUCER's internals — `earlyClause`, `rawSource` — which by construction do not appear in a test: a test is written in the vocabulary of its INPUT and its ASSERTION, tampering with source text and requiring a rejection, never naming the AST field it exercises. I also took only the FIRST grep hit, which for `effect Real` landed on an unrelated comment-handling test 150 lines above the real one. Both errors point the same way: search for the RECIPE, not for the mechanism.

**Status:** open (2026-07-15). Reported as P1 by the fresh independent review of
frozen checkpoint `4cd2a4aaa` (rebased as `05e498a72`); fix SHA pending.

**Symptom/reproduce:** `templateHeaderWitness` and `rejectUnsupportedParents` do
not inspect `Template.derives` or `Template.earlyClause`. A class, trait, enum, or
object can therefore retain an unrepresentable derives/early-initializer surface,
or one retained source carrier can change that surface, while strict production
still returns `Right` or accepts stale source/AST correspondence.

**Root cause/plan:** the declaration witness and nominal losslessness gate cover
parents/self/members but omit two current ScalaMeta template-header fields. Include
both fields in exact body-erased correspondence. Reject every actual public nominal
declaration with a non-empty derives or early clause until descriptor metadata can
represent it. Add direct class/trait/enum/object vectors for every shape that the
parser accepts plus stale Document/CodeBlock mismatches; require stable
`UNSUPPORTED_PUBLIC_DECLARATION` paths and retain all earlier wrapper/header tests.

**Done when:** faithful red vectors fail on the current checkpoint, then pass with
the full focused, descriptor/core/interop/IR/ABI, and affected conformance radius.
Keep `open` until fresh independent approval and landing on `origin/main`.

**Red baseline:** regression commit `f08ab9943`; focused producer is exactly
50/60. Three nominal tests fail: direct derives is accepted, derives carrier
tampering returns `Right`, and early-clause tampering reaches the later symbol-level
inheritance rejection instead of failing correspondence at the section path. All
previous nominal regressions remain green.

**Local fourth correction checkpoint:** implementation `43d41e88d` and spec
verification `38597ae85`, rebased on `origin/main@f63714680`, include derives and
early clauses in both exact declaration witnesses and the nominal rejection gate.
Focused producer/parser/effect tests pass 75/75 (producer 63/63), descriptor
27/27, core 1111/1111, interop 36/36, IR succeeds, artifact ABI 73/73, and affected
conformance passes 2/2 modules/import-dir plus 9/9 effect cases. Status remains
`open` until fresh independent approval and landing; the local commit is not a fix
SHA on `origin/main`.

## descriptor-v3-imported-builtin-shadow — imports are ignored before bare builtin projection
<!-- status: fixed
     fixed-in: unrecorded
     lane: apparatus
     area: runtime
     gate: sbt core/testOnly *PreBodyApiDescriptorProducerTest* -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open (2026-07-15). Reported as P1 by the fresh independent review of
frozen checkpoint `4cd2a4aaa` (rebased as `05e498a72`); fix SHA pending.

**Symptom/reproduce:** `projectStat` ignores imports. Sources such as
`import foo.Byte` followed by `Array[Byte]` still project as primitive `Bytes`;
renamed imports and wildcards can likewise make a bare spelling resolve somewhere
else while the producer guesses a frozen builtin. The same gap affects every bare
builtin mapping (`Int`, frozen collection constructors, and so on) and can bypass
the platform-type isolation rule.

**Root cause/plan:** lexical binder/local lookup is import-blind, so absence of a
local identity is treated as proof of the builtin. Collect source-ordered import
bindings at `projectStat` and fail closed before every bare builtin mapping whenever
a direct import, rename-to-that-name, or wildcard could supply the spelling. Preserve
renamed-away and unimport semantics only when absence is provable. Add direct,
rename, wildcard and exclusion vectors for both Array/Byte components and at least
representative `Int`/`List`; retain qualified builtin/external positives and assert
stable declaration paths/codes, including platform-root cases.

**Done when:** faithful red vectors fail on the current checkpoint, then pass with
all prior 46 focused tests and the full affected gates. Keep `open` until fresh
independent approval and landing on `origin/main`.

**Red baseline:** regression commit `f08ab9943`; focused producer is exactly
50/60. Five import tests fail: exact Array/Byte and rename-to-Byte still become
primitive `Bytes`, wildcard Array/Byte returns `Right`, exact Int remains `I32`, and
an exact platform rename remains `I32`. The combined Int/List test stops at its
first exact-Int assertion; its wildcard-List assertion is retained for the fix.
Rename-away/unimport and qualified positives already pass, as do all prior 46 tests.

**Local fourth correction checkpoint:** implementation `43d41e88d` and spec
verification `38597ae85`, rebased on `origin/main@f63714680`, add source-ordered
import scope and resolve direct/renamed/wildcard/excluded bindings before every bare
builtin. Focused producer/parser/effect tests pass 75/75 (producer 63/63),
descriptor 27/27, core 1111/1111, interop 36/36, IR succeeds, artifact ABI 73/73,
and affected conformance passes 2/2 modules/import-dir plus 9/9 effect cases. Status
remains `open` until fresh independent approval and landing; the local commit is
not a fix SHA on `origin/main`.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. **No cited sha landed under that hash** — the commits here are frozen review checkpoints, which is what made this look unlanded. The BEHAVIOUR is implemented and pinned.

Pinned by:
  - `direct imports resolve both Array and Byte before the Bytes shortcut`
  - `rename-to-name imports resolve before the Bytes shortcut`
  - `a wildcard import makes Array Byte ambiguous instead of primitive Bytes`
  - `exact and wildcard imports precede representative Int and List builtins`
  - `imports are source ordered and nested import scopes do not leak`

`sbt core/testOnly *PreBodyApiDescriptorProducerTest*`: **85 tests, 85 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## descriptor-v3-mutable-export-loss — exported val and var collapse to one immutable descriptor
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: sbt core/testOnly *PreBodyApiDescriptorProducerTest*
     fixed-in: 790366a9d -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open (2026-07-15). Reported by the independent Slice B frozen-checkpoint
re-review; affected pre-integration commit `0f60205c5` (rebased as `59ca2898f`);
fix SHA pending.

**Symptom/reproduce:** project otherwise identical public `val current: Int` and
`var current: Int` declarations. The strict pre-body producer returns the same
`ApiSymbolKind.Value`, descriptor JSON, and `apiHash` for both, so the descriptor
silently discards public mutability.

**Root cause/plan:** `projectValues` receives the declaration's `mutable` flag but
does not encode or reject it, while the frozen Slice A schema has no mutability
field. Reject every selected public/exported `Defn.Var` with stable
`UNSUPPORTED_PUBLIC_DECLARATION` until a future additive schema represents
mutability. Keep `val` positive and add a faithful val-versus-var regression without
changing the descriptor leaf.

**Baseline:** focused producer test accepts the `var` as the same `Value(I32)`
shape as the positive `val`; this is one of four expected failures in the
`25/29` pre-fix run.

**Latest local checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` rejects the faithful repro. Focused producer 46/46,
descriptor 27/27, core 1092/1092, interop 36/36, IR, artifact ABI 73/73, and
affected conformance 2/2 are green. Status stays `open` until fresh independent
approval and landing on `origin/main`.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. `790366a9d` is an ancestor of `origin/main`.

Pinned by:
  - `selected mutable vars reject until descriptor v3 represents mutability` — the entry's plan verbatim ("reject every selected public/exported `Defn.Var` … until a future additive schema represents mutability")

`sbt core/testOnly *PreBodyApiDescriptorProducerTest*`: **85 tests, 85 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## js-control-direct-import-only-eval-erasure — unused marker removal changes direct-eval scope
<!-- status: fixed
     fixed-in: unrecorded
     lane: apparatus
     area: codegen -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open; repair candidate `4c6b8e2a9` is locally verified and awaits fresh
independent review plus landing. Found by parent adversarial pre-rereview on
2026-07-15; the original independent-review snapshot was `f6fa34fac`.

**Symptom/reproduce:** compile a file that imports named `direct`, contains no
`reset`/`shift`, and evaluates `eval("typeof direct")`. The repair candidate selects
the file solely to erase the otherwise unused build-time marker import, but its
direct-eval scan is gated on `filesWithMarkerCalls`. Emit therefore removes the
lexical binding without a diagnostic and changes the eval result from `"object"` to
`"undefined"`.

**Required fix/verification:** intrinsic direct eval is a barrier for every source
file that the transform would rewrite, including import-only marker erasure. Keep
unused marker removal for eval-free files; do not reintroduce a production marker
dependency. Add an exact import-only direct-eval regression proving one stable
`JS_DIRECT_CAPTURE_BARRIER`, no `transformedFiles` entry, and byte-semantic
file-atomic emit when a programmatic caller ignores diagnostics.

**Root cause/fix candidate:** eval scanning was gated by the presence of marker
calls even though an import-only file was also a rewrite candidate. The candidate
now scans every selected file, retains the original import on diagnostic, and has an
executing regression proving that `eval("typeof direct")` still observes `"object"`.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. `4c6b8e2a9` is an ancestor of `origin/main`.

Pinned by:
  - `import-only marker erasure is also protected from intrinsic direct eval`
  - `intrinsic direct eval is a transparent-wrapper-aware file barrier`

`npm test (v2/host/js/control-direct)`: **39 tests, 39 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## js-control-direct-typescript-version-ungated — unsupported compiler APIs are accepted
<!-- status: fixed
     fixed-in: unrecorded
     lane: apparatus
     area: cli -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open; cumulative repair candidate `c19d42401` is locally verified and
awaits fresh independent review plus landing. Reported as P2 on 2026-07-15 by the
independent pre-integration review of frozen direct-transform snapshot `f6fa34fac`.

**Symptom/reproduce:** `createDirectTransform` accepts any object with enough
TypeScript-shaped members and the CLI loads any consumer `typescript` version.
The package therefore has no deterministic boundary for compiler-factory and AST
API compatibility, although its declarations and tests were built only against
TypeScript 5.9.3.

**Required fix/verification:** the feature spec must pin the supported compiler API
version policy, both the programmatic entrypoint and CLI must reject an unsupported
version before transforming, and positive/negative version-gate tests must preserve
an actionable stable failure. Keep the published package free of a bundled compiler.

**Root cause/fix candidate:** the transform validated only a TypeScript-shaped
object and never bounded compiler AST/factory compatibility. The candidate gates
both programmatic and CLI entrypoints on `versionMajorMinor === "5.9"`, with 5.9.3
as the qualification pin and deterministic rejection tests outside that line.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. `c19d42401` is an ancestor of `origin/main`.

Pinned by:
  - the gate itself, at BOTH entrypoints the entry names: `transform.js:22` and `cli.js:39` compare `ts.versionMajorMinor` against `SupportedTypeScriptMajorMinor`, and both are exercised by tests that inject a fake TypeScript 6.0 (`transform.test.js:1142`, `cli.test.js:394`)

`npm test (v2/host/js/control-direct)`: **39 tests, 39 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## js-control-direct-eval-capture-unsound — direct eval can observe rewritten lexical frames
<!-- status: fixed
     fixed-in: unrecorded
     lane: apparatus
     area: codegen -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open; cumulative repair candidate `c19d42401` plus selected-file closure
`4c6b8e2a9` are locally verified and await fresh independent review plus landing.
Reported as P1 on 2026-07-15 by the independent pre-integration review of frozen
direct-transform snapshot `f6fa34fac`.

**Symptom/reproduce:** a file containing an otherwise transformable direct reset can
also contain direct `eval(...)`. The current region checks do not reject every
file-wide direct-eval occurrence or transparent callee wrappers such as parentheses,
`as`, non-null, and type assertions. Emit can therefore change which lexical frame
the evaluated source observes or mutates.

**Required fix/verification:** reject intrinsic direct eval anywhere in each file
that would be transformed, after unwrapping only transparent syntax, and emit
nothing for that file. The feature spec must explicitly pin indirect-eval and
`Function`-constructor policy. Tests must cover top-level, reset-local, nested
closure, wrapped, indirect, and `Function` cases with stable diagnostics.

**Root cause/fix candidate:** analysis had no file-wide intrinsic-eval ownership
pass. The repair resolves the unshadowed global binding through transparent wrappers
and cancels every rewrite in that selected file; the follow-up extends the same gate
to import-only erasure while leaving indirect eval and `Function` global-only.

## scljet-ipk-rowid-alias-not-substituted — reading a REAL SQLite file returns 0 for every `INTEGER PRIMARY KEY`
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 14f4da4ac -->

**Status:** **FIXED (2026-07-16)** — `14f4da4ac` (read substitution) + `2fc0a0fd1`
(`lastInsertRowid`). Found 2026-07-15 by `scljet-jdbc-j4-introspection` while probing whether the
JDBC shim can read a file written by the reference driver. **Engine bug — belongs to the
`scljet-m3-writes` lane, NOT the JDBC shim** (the shim only forwards `queryImage` rows).
Found on `origin/main` `727ea5e12`. **Silent wrong data, not an error** — the severity is that
nothing fails; the client just gets zeros.

**ACTUAL root cause (confirmed, not hypothesis).** Exactly the read-side hypothesis below, and
*only* that: `fieldValueAt` returned the record's stored field for the IPK column. A
reference-written file stores NULL there (the value lives in the rowid), so the engine returned
NULL — which the typed getters coerce to `0`. Verified through a file in both directions before
any fix: `SELECT id, name FROM emp` gave `null|ann, null|bob`, and `WHERE id = 7` matched *nothing*
(the filter reads the record too — a projection-only fix would have left this broken).

**The fix (read-only, `scljet/sql.ssc`).** Materialise the rowid into the IPK column once per
query, on the decoded row set, so every downstream reader — projection, WHERE, ORDER BY, GROUP BY,
aggregates, joins, DISTINCT — sees it through the existing `fieldValueAt` without knowing about the
alias. Three choke points cover every SELECT path: `finishRows` (full scan + rowid seek + index
seek), `executeSelectLimited` (LIMIT pushdown applies its WHERE *during* the scan, so
`collectRowsLimited` takes `ipkIdx` and normalizes before matching), and `joinTableRows` (the one
row source for both the 2-table and the N-table join). Each derives the index from the existing
`tableIpkIndex` — no new analysis, and no signature churn beyond `collectRowsLimited`.
**Byte-safety:** only the DECODED value is replaced; `serialType`/`encoded` carry over from the
original field, so `reconstructRecordBytes` still rebuilds the original on-disk payload
byte-for-byte and a normalized record cannot corrupt a re-encode.

**Regression cover:** `ScljetIpkRowidDifferentialTest` (new) crosses the two engines **through a
file** in both directions; `ScljetIntrospectionTest`'s deliberately-pinned `getLong(1) == 0` now
asserts `1`. Gates: `scljet-*` conformance 97/97 (`--no-memo`), `scljetJdbcPlugin/test` 48/48.

**Symptom/reproduce** (three-way differential; the JDBC lane is only the harness — the same read
goes through `queryImage`, so a pure `.ssc` repro should reproduce it too):

```scala
// 1. a file written by the REFERENCE driver (org.xerial:sqlite-jdbc)
CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT); INSERT INTO emp VALUES (1,'ann'),(7,'bob')
// 2. read it back:
reference reading its own file        → 1|ann, 7|bob     ✓
scljet   reading the reference file   → 0|ann, 0|bob     ✗  ← BUG
scljet   reading a scljet-created db  → 1|ann, 7|bob     ✓  (masks the bug in our own tests)
```

**Root cause (hypothesis, needs engine confirmation).** In real SQLite an `INTEGER PRIMARY KEY`
column is an *alias for the rowid*: the record stores NULL for it and the value lives in the
rowid. scljet's read path does not substitute the rowid for the IPK column, so it returns the
stored NULL, which the getters coerce to `0`. The engine already models the concept — `isIpkType`
(`scljet/sql.ssc:1086`), `ipkColumnIndex(sql)` (`~:1098`), `tableIpkIndex(db, table)` (`~:4291`) —
so the fix is likely to apply the existing IPK index in the row-projection path, not new analysis.

**~~A second, opposite-direction divergence~~ — MEASURED AND DISPROVED (2026-07-16).** The
earlier suspicion here (that our WRITE stores the IPK value in the column with a *sequential*
rowid, so real SQLite would misread our files) is **WRONG**. Probed directly — write with
scljet, read with `org.xerial:sqlite-jdbc`:

```
scljet: INSERT INTO emp VALUES (7,'bob')  → the row's actual rowid = 7   (NOT sequential)
REAL SQLite reading OUR file → id=1|ann|rowid=1, id=7|bob|rowid=7        ✓ correct
REAL SQLite `PRAGMA integrity_check` on our file → ok                    ✓
```

**So the WRITE side is sound and our files are valid, correctly-readable SQLite.** What we
actually do is store the IPK value *redundantly*: in the rowid (correct) **and** in the record's
column (real SQLite stores NULL there). Real SQLite tolerates that because it always takes an IPK
column's value from the rowid and ignores what is stored. Our two inaccuracies cancel out on our
own files, which is why every existing test passes.

**Consequence for the fix — it is READ-ONLY and does not regress our own files.** Since our write
already sets `rowid = 7`, teaching the read path to take the IPK column from the rowid yields `7`
on *both* file flavours: ours (rowid 7, column 7) and the reference's (rowid 7, column NULL).
There is no need to change the write path to make the read correct. (Writing a canonical NULL in
the column is a separate, optional tidy-up — byte-level canonicity vs real SQLite — NOT required
for correctness, and it would be a storage-format change worth its own slice.)

**A REAL second bug found by the same probe — `lastInsertRowid` is wrong for an IPK table —
FIXED `2fc0a0fd1`:**

```
scljet: INSERT INTO emp VALUES (7,'bob')  → the row's rowid IS 7, but
                                             MutationResult.lastInsertRowid reports 1
reference sqlite-jdbc for the same INSERT  → last_insert_rowid() = 7
```

i.e. the counted-mutation path reports a sequential counter instead of the rowid actually
assigned. This makes the JDBC shim's `getGeneratedKeys` (J2) return the wrong key for exactly the
tables where generated keys matter most. The existing `getGeneratedKeys` tests use a *plain*
`INTEGER` column (not an IPK), where a sequential rowid IS the right answer — which is why they
pass, and why the reference cross-check passes too.

**Root cause:** `insertChangesRowid` derived the rowid as `maxRowid + #rows` *independently of*
`assignInsertRowids`, the function that actually places the rows — two derivations of one fact,
which agreed only while no IPK was involved. **Fix:** reuse `assignInsertRowids` and report the
last rowid it assigns, so both callers share one source of truth by construction.
`ScljetDriverTest` now covers an IPK table (explicit 7 → auto 8 → explicit 3), cross-checked
against the reference's `last_insert_rowid()`. Verified RED first: reported `(1, 8, 9)` vs the
correct `(7, 8, 3)`.

**A THIRD divergence, still OPEN — see `scljet-update-ipk-does-not-move-rowid` below.** Found while
verifying that the read fix does not regress `UPDATE`. It is a *pre-existing write* gap, not a
regression, and the read fix strictly improves cross-engine agreement on it.

**Method note that generalises.** A test whose oracle is "scljet reads back what scljet wrote"
cannot see any of this: it is self-consistent by construction. The differential must cross the
two engines **through a FILE**, in *both* directions (they-write/we-read AND we-write/they-read) —
only the second direction could have disproved the write-side hypothesis above.

**Notes.** Reading a reference-written file otherwise works (schema, indexes incl. `UNIQUE`,
non-IPK columns, TEXT) — see `ScljetIntrospectionTest` "reads a database created by the
reference driver", which pins the parts that DO hold. Related engine gaps found the same way:
`CREATE UNIQUE INDEX` is not parsed at all (`parseCreateIndex` requires `CREATE INDEX`;
`CREATE UNIQUE INDEX` falls through to `parseCreate` → "expected TABLE"), and
`INSERT INTO t SELECT …` is not parsed ("expected VALUES").

## js-control-prompt-key-extraction-never — invariant answer type breaks PromptKeyOf
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 1497623b5 -->

**Status:** done in `0d0ffcfd3`; confirmed closed by independent second
pre-integration review. Originally reported as P1 on 2026-07-15; affected pre-land
declaration commit `2a34d7ed3`.

**Symptom/reproduce:** `PromptKeyOf<Prompt<P, ConcreteAnswer>>` evaluates to
`never` because the conditional matches `Prompt<infer P, unknown>` while the
private prompt brand intentionally makes the answer type invariant.

**Root cause:** the conditional fixed the answer parameter to `unknown`; invariant
branding makes `Prompt<P, ConcreteAnswer>` intentionally non-assignable to that
shape, so inference never reached `P`.

**Fix/verification:** `PromptKeyOf` now infers both `P` and the concrete answer
parameter, then returns `P`. A concrete-answer positive compile assertion passes,
while nested-prompt incompatibility, answer invariance, and forged-prompt negative
gates remain green.

## descriptor-v3-nominal-surface-loss — strict pre-body projection drops public nominal semantics
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: sbt core/testOnly *PreBodyApiDescriptorProducerTest*
     fixed-in: 790366a9d -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open (2026-07-15). Reported by the independent Slice B re-review
(`/root/descriptor_b_rereview`); fix SHA pending.

**Symptom/reproduce:** the in-progress descriptor-v3 producer accepts nominal
declarations whose public surface cannot be represented by the current descriptor:
`trait Configured(value: Int)` loses its trait constructor, a trait self type such
as `self: Base =>` loses the self constraint, a template `export delegate.exposed`
loses a public receiver member, and constructor `val`/`var` parameters lose their
generated public accessors. Each input currently produces a successful bare
`Type`/`Constructor` projection instead of failing closed.

**Root cause/plan:** the nominal losslessness gates inspect parents and most
`Stat.WithMods` members, but do not inspect trait constructor clauses, template
self types, `scala.meta.Export`, or constructor accessor modifiers. Freeze these
shapes as unsupported until receiver/member metadata exists, reject them with
stable `UNSUPPORTED_PUBLIC_DECLARATION`, and add one regression per shape before
requesting another independent review.

**Baseline:** focused producer suite reproduces all four nominal shapes as
unexpected successful descriptors (`18/25` total green before the fix).

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` rejects all four shapes. Focused producer 46/46,
descriptor 27/27, core 1092/1092, interop 36/36, IR, artifact ABI 73/73, and
affected conformance 2/2 are green. Status remains `open` until fresh independent
approval and landing on `origin/main`.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. `790366a9d` is an ancestor of `origin/main`.

Pinned by:
  - `nominal declarations, aliases, typed values and plain/multi effects project without bodies`

`sbt core/testOnly *PreBodyApiDescriptorProducerTest*`: **85 tests, 85 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## descriptor-v3-lost-ast-container-fail-open — retained declarations can project as an empty API
<!-- status: fixed
     lane: apparatus
     fixed-in: e6df78ce5
     area: front -->

**GATED AND FIXED — verified 2026-08-07 by reading the test against this entry's own recipe, not by matching names.** `v1/lang/core/src/test/scala/scalascript/artifact/PreBodyApiDescriptorProducerTest.scala` contains *"retained executable document source cannot silently lose its section AST"*. It does exactly what this entry describes — asserts `parsed.document.nonEmpty` and
`parsed.sections.nonEmpty`, then requires `descriptor(parsed.copy(sections = Nil))` to FAIL with
`UNSUPPORTED_PUBLIC_DECLARATION` at `$.sections`. The fail-open is closed.

**Status:** open (2026-07-15). Reported by the independent Slice B re-review
(`/root/descriptor_b_rereview`); fix SHA pending.

**Symptom/reproduce:** parse a valid declaration-bearing module, then copy it with
`sections = Nil` while retaining the original `document`/`sourceText` and an empty
manifest export list. The in-progress strict producer returns a valid descriptor
with zero symbols instead of rejecting the lost declaration AST.

**Root cause/plan:** `topLevelStats` folds only `module.sections`; it has no
completeness cross-check against retained parseable document blocks. Compare the
retained declaration-source containers with section code blocks and return stable
`UNSUPPORTED_PUBLIC_DECLARATION` whenever parsed declaration structure is missing.
Cover the exact copy-based repro.
**Baseline:** focused producer suite reproduces the copied module as an unexpected
successful empty `ApiDescriptor` (`18/25` total green before the fix).

**Local correction checkpoint:** implementation `72e6a2897` on
`origin/main@790366a9d` fails closed when retained declaration containers lose
their section AST. Focused producer 46/46, descriptor 27/27, core 1092/1092,
interop 36/36, IR, artifact ABI 73/73, and affected conformance 2/2 are green.
Status remains `open` until fresh independent approval and landing on
`origin/main`.

## control-interop-residual-forwarding-absent — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d764c2ebe
     confirmed: no -->

**Status:** fixed in `d764c2ebe`, with self-hosted frontend qualification in
`84ad12651` and selected-only total-handler hardening in `9273ae0f6`; awaiting
reporter confirmation after the feature branch lands.

**Symptom:** an operation unhandled by the nearest inner handler is sent to that
handler anyway and fails with `match: no arm`, instead of remaining an explicit
`Op` for the next enclosing handler.

**Reproduce:** run
`tests/interop-conformance/probes/19-residual-forwarding-nested-handlers.ssc`:
it nests an inner `Rd` handler inside an outer `Wr` handler, then performs
`Wr.wr` inside the inner scope. Before the fix, portable VM/direct ASM reported
`no arm for wr/2`; both installed lanes now print exact `57` with empty stderr.

**Root cause:** the handler fold called the nearest generated partial-function
closure as an ordinary total function. Its missing arm became an immediate
textual match failure, so the fold could not distinguish a recoverable miss from
a failure thrown inside a selected arm or continue an effectful guard decision.

**Fix/verification:** qualified handler roots now return a private structured
`Matched | Unhandled | Suspended` decision with exact-event, owner, and activation
provenance. A miss rebuilds the existing three-field `Op` around the same deep
continuation and base multiplicity gate; no public CoreIR/data ABI changed and no
exception text is parsed. Axis 19 is measurable-now (`57`), focused JVM tests are
17/17 + 4/4 + 6/6, native e2e and stage2 source-exact fixed points pass, affected
conformance is 6/6, all 11 interop axes pass, and focused JVM/JS/Rust/Swift marker
checks are green. Full evidence is recorded in
`specs/control-residual-forwarding.md`.

## swift-effect-handler-implicit-return-fallback — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: f21abfcc8
     confirmed: no -->

**Status:** fixed in `f21abfcc8`; awaiting confirmation. Found by the real Swift
checked-source regression while implementing the portable one-shot guard.

**Symptom:** a Swift AOT handler that omits `case Return(value)` fails on its
first `resume` with `match: no arm for Return(1)`. JVM VM/ASM implement the
documented convenience semantics: if the handler has no `Return` arm, a normal
returned value passes through unchanged. The mismatch prevents the exact same
minimal handler source from reaching a second resume on Swift.

**Reproduce:** compile and run on Swift:
`handle(One.op()) { case One.op(resume) => resume(1) + resume(2) }`. With a
plain one-shot effect, Swift reports the missing `Return` arm before testing the
second claim; adding `case Return(value) => value` reaches the intended stable
`ONESHOT_VIOLATION`. The same source without a Return arm reaches that violation
on JVM VM/ASM.

**Root cause:** generated Swift `handleEffect` directly calls the handler with
`Return(value)`, while a no-match in the generic Swift evaluator is an immediate
`fatalError`. JVM `PortableEffects.handle` recognizes only a missing Return arm
and returns the value; Swift has no equivalent recoverable no-arm signal.

**Fix/verification:** Swift now uses a private `matched | noMatch` result only for
the directly invoked handler partial-function match. An absent `Return` arm maps
to identity; selected arms and fallbacks execute through the ordinary evaluator,
so nested match/runtime/control failures remain failures. The same no-`Return`
fixtures now reach the stable one-shot violation and reusable result `3` on Swift
and JVM VM/direct ASM. Swift focused tests, native effect e2e, `installBin`, and
fresh affected conformance (6/6) pass.

## v21-stage2-gate-ignores-symlinked-std-sources — FIXED / awaiting confirmation (2026-07-15, Codex)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 13b29852e
     confirmed: no -->

**Status:** fixed in `13b29852e`; awaiting reporter confirmation. Found while
running the mandatory self-hosted fixed-point gate for `control-one-shot-guard`.

**Symptom:** `scripts/v21-stage2-bootstrap-gate` rejects a freshly generated
native-front image because all `runtime/std/scljet/*.ssc` files exist only in
the staged manifest. `installBin` correctly follows the tracked
`v1/runtime/std/scljet -> ../../../scljet` compatibility symlink, but the gate's
source manifest silently omits that directory.

**Reproduce:** run `scripts/sbtc "installBin"`, then
`scripts/v21-stage2-bootstrap-gate`. The fixpoint compiler checks complete, but
the source-exact comparison reports the 19 current SclJet `.ssc` files as staged
additions. `find v1/runtime/std -type f -name '*.ssc'` counts 105 while the same
command with `-L` and the installer both count 124.

**Root cause:** the gate builds its source manifest with `find` without `-L`,
unlike sbt's recursive glob used by `installBin`. The two sides therefore apply
different source-tree semantics to a documented, tracked compatibility symlink.

**Fix/verification:** the gate now uses symlink-following enumeration for the
source manifest, matching `installBin`. After a fresh install on current `main`,
`scripts/v21-stage2-bootstrap-gate` reports both single/multi fixed points true,
131 compiler-image files, and `compiler.image.source-exact=true`.

## scala3-control-capability-jvm-visibility — FIXED / awaiting confirmation (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 528d73af3
     confirmed: no -->

**Status:** fixed in `528d73af3`; awaiting reporter confirmation. Found by `api_type_design` with
`javap -public` against the uncommitted leaf; reported by codex-interop in the
`scalascript` rozum room.

**Symptom:** Scala `private[control]` and several plain-private cross-companion
bridges compile to public JVM methods/constructors. The draft exposed
`Eff.request`, continuation factories, key/prompt constructors and prompt internals,
so Java or same-package Scala could bypass the intended construction boundary.

**Reproduce:** run `javap -public` on the draft classes; it lists `Eff.request`,
`Continuation.runtime`, `OneShotContinuation.runtime/delegate`, and public JVM
constructors for `EffectKey`, `Continuation`, and `Prompt`.

**Fix/verification:** raw pending-request construction is absent. Private nested
implementations are used where possible; every unavoidable JVM-visible constructor
or factory requires an identity-validated private authority, and null/freshly forged
tokens fail before construction. The complete compiled-class `javap -public`
inventory is an executable test and reports no unguarded request, prompt, key,
resumption gate, authority issuer, or successful saved-continuation constructor.

## scala3-control-shift-row-widening — DONE (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 06b4e4be1 -->

**Status:** done in `06b4e4be1` (tier-1 API type-safety blocker). Found by the
`prompt_source_design` delegated compile audit while implementing the ABI landed at
`98e9645e1`; reported by codex-interop in the `scalascript` rozum room.

**Symptom:** the frozen `shift[P,A,Fx,R]` body receives
`Continuation[A,Fx,R]`, but the covariant result `Eff[Fx | Control[P],A]` can be
widened by a later `flatMap` before `reset`. The actual captured suffix may therefore
perform `Fx2 >: Fx` while the shift body still sees the narrower `Fx`; choosing
`Fx = Nothing` can incorrectly pass the effectful continuation to `Eff.runPure`.

**Reproduce:** construct `shift` with inferred minimum row `Nothing`; in its body,
call `Eff.runPure(k.resume(value))`; append a nominal effect operation with
`flatMap`; then enclose the combined computation in the matching `reset`. The old
types accept the program even though `k.resume` reaches that appended request.

**Fix/verification:** `ShiftBody` is now rank-2 over every
`Residual >: Fx`, so the shift body receives the actual widened continuation row.
Scala 3.8.3 compile probes accept ordinary/nested prompt use and reject the old
`runPure(k.resume(...))` repro at the typer. The reporting audit confirmed the
positive and negative probes after the spec correction.

## control-interop-effect-recursion-stack-unsafe — FIXED / awaiting confirmation (2026-07-14, claude)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 3de5020c5
     confirmed: no -->

**Status:** fixed by the shared driver in `3de5020c5` and completed by JVM
lowering fixes in `956b42539`; awaiting reporter confirmation. Reported by
codex-interop (rozum #interoperability, 2026-07-14): "legacy deep-handler/return
paths appear recursively stack-unsafe".

**Symptom:** effect-performing recursion grows the native stack and overflows; pure tail
recursion is unaffected.

**Reproduce:** a recursion that performs one effect per frame overflows between depth 500 and
2000 (`java.lang.StackOverflowError` at 2000) on BOTH portable-VM tiers (`ssc run` and `ssc
run --bytecode`, 2026-07-14); the same shape with no effect (pure TCO) runs to 2,000,000
(conformance axis 03 ✓).

**Fix/verification:** handler-facing resume now returns an unforgeable private
deferred request, and one iterative two-mode driver consumes typed heap frames.
Only declared managed program/host boundaries drain escaped requests; public
`Op/3`, one-shot gates, and residual handler ownership remain unchanged.

**Secondary root cause found during the fix (2026-07-15):** after the private
resume carrier was moved to a managed-boundary driver, the installed VM still
printed that carrier in the escaped state-thread vector while direct ASM
completed it. `Compiler.C.compile` evaluated every `Term.Prim` argument and
invoked the primitive immediately; it did not mirror direct ASM's
`OpAnfNative` argument lifting. Thus `println(escapedResume(0))` consumed the
private `Op` before the managed program root could drain it. Spec update
`241c5dcd5` requires left-to-right per-argument `Runtime.letThreadOp` for every
non-effect-substrate primitive, exact exclusions for `effect.handle`,
`effect.perform`, `effect.perform.oneshot`, and `effect.pure`, and a FastCode
guard plus VM/ASM multiple-Op ordering regression. The combined implementation
verification is complete and axis 20 is now measurable.

**Additional correctness holes found during focused verification (2026-07-15):**

- `FastCode.tryFBc` guarded only consumed value positions. A raw CoreIR
  `If(Prim("cell.get", List(cellHoldingAutoOp)), yes, no)` therefore took the
  specialized getter, received an `Op`, and coerced that non-`BoolV` value to
  `false` instead of letting the normal `If` compiler thread it. The focused
  fix is for `tryFBc` to decline any complete condition satisfying
  `Compiler.mayProduceAutoThreadOp`; globally disabling `tryFC` is explicitly
  not required.
- The direct-ASM emitter's pending-method fixpoint omitted `letChains` from its
  outer loop condition. A curried `handle(computation)(handler)` whose entry
  lowering enqueued only an effect-aware `Let` emitted a call to `lam$1` but no
  corresponding method, failing with `NoSuchMethodError`. The queue must drain
  while any pending lambda, sequence chain, or let chain remains.
- After residual forwarding was rebased onto the always-deferred resume driver,
  its multiplicity test called an escaped forwarded continuation directly with
  `Prims.runClos1` and observed the private request. That call models an
  unmanaged host callback; the driver contract intentionally drains only at an
  explicit managed boundary. The integrated regression must invoke those
  continuations through `Runtime.runManaged`, then cover residual forwarding,
  a nested request entering `Handle` mode (`Rehandle`), and exact inner/outer
  `Return` ordering together on VM/direct ASM.

All integration holes above are closed. Focused stack/one-shot/residual suites
pass 39/39; installed VM and direct ASM both return exact `100000`, `100000`,
`20007`, `20000`; full interop is 12/12, affected conformance is 6/6, and the
133-file stage2 compiler image remains source-exact. Axis 20 is promoted from
`pending-runtime`; the verified evidence is in
`specs/control-effect-stack-safety.md`.

## spec-grammar-schema-links — FIXED (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 96fc5adfb -->

**Status:** fixed in `96fc5adfb`; found while mechanically checking local links
after the bidirectional-control update to canonical `SPEC.md`.

**Symptom:** `SPEC.md` links to `grammar/scalascript.ebnf` twice and
`schemas/frontmatter.yaml` once, but the tracked files now live under
`v1/lang/grammar/` and `v1/lang/schemas/`.

**Reproduce:** both old targets fail `test -e`; `rg --files` finds the grammar and
schema only at the `v1/lang/` paths.

**Fix/verification:** all three links now target the tracked `v1/lang/` files;
both destinations exist and the changed documentation passes Markdown lint.

## backlog-active-queue-link — FIXED (2026-07-14, Codex)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 37c1a69c8 -->

**Status:** fixed in `37c1a69c8`; found by `final_control_spec_audit` while
verifying local links in the bidirectional-control planning slice.

**Symptom:** `BACKLOG.md` links active work to nonexistent `ACTIVE.md`, while the
repository's binding workflow and `SPRINT.md` define the active queue as
`SPRINT.md` plus authoritative `.work/active/*.claim` files on `origin/main`.

**Reproduce:** `test -e ACTIVE.md` fails; compare `BACKLOG.md` lines 3–4 with
`SPRINT.md` lines 3–5 and `AGENTS.md`'s claiming protocol.

**Fix/verification:** the header now links to `SPRINT.md` and names
`.work/active/*.claim` on `origin/main` as the ownership authority; Markdown lint
and the target-existence check pass.

## v2-swift-e2e-standard-launcher-stale — assembled scripts invoke the wrong tier
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b3a4cea28
     confirmed: no
     gate: tests/e2e/v2-swift-cli.sh -->

**Status:** fixed (2026-07-12, `b3a4cea28`), awaiting Sergiy confirmation;
found by codex after fresh `installBin` and reported in the `scalascript` Rozum
room.

- **Real-harness repro:** `tests/e2e/v2-swift-cli.sh` exits on its first
  `bin/ssc emit-swift` with the bounded message that `emit-swift` requires the
  optional tools/compatibility tier. The Apple script has the same stale
  launcher assignment for `build`.
- **Root cause:** `e28560761` intentionally made `bin/ssc` StandardMain and
  installed the full compatibility CLI as `bin/ssc-tools`; the Swift e2e
  scripts still assumed the former single launcher.
- **Fix/done-when:** point both scripts at freshly installed `bin/ssc-tools`,
  update only their missing-binary hints, and pass the complete assembled CLI
  plus production-shaped macOS/iOS Apple gates. StandardMain remains narrow.
- **Fix/result:** both assembled scripts use `ssc-tools`; fresh CLI and Apple
  gates pass without expanding StandardMain.

## v2-swift-nativeui-standard-pipeline-parity — real Swift cannot run standard lower/serve + locale/JSON
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 11f1e68dc
     confirmed: no -->

**Status:** fixed (2026-07-12, `11f1e68dc`), awaiting `brave-newt` / Sergiy
confirmation; reported by `claude-code` / `brave-newt` in the `scalascript`
Rozum room from busi's production-shaped fixture and accepted by
`scalascript-codex`. Independent final verdicts from
`nativeui-try-reviewer` and `nativeui-json-reviewer`: `APPROVE`.

- **Real-harness repro:** compile the checked busi
  `src/v2/clients/swift-nativeui-smoke/pipeline-smoke.ssc` shape (three keyed
  JSON lists, per-row signals, nested visibility, i18n), or a minimal checked
  `.ssc` importing `text`, `heading`, `styled`, `defaultTheme`, `lower` and
  `serve`. The production pipeline fails first on module `val localeSignal`,
  then `__jsonCoreWrap`, and a minimal standard UI reaches unsupported
  primitive `__try__` through `lower.ssc::_lenOf` (`try v.toInt catch ...`).
- **Coverage hole:** the previously approved
  `examples/swift/appcore-nativeui.ssc` called `emit(fragment(...))` directly.
  It bypassed `lower`, theme-token conversion and the `__try__` path, so green
  Swift/Xcode gates did not prove the standard toolkit contract.
- **Root causes:** Swift validation sees definition names but not the
  compiler-generated module-init `global.reg`; Swift has no canonical
  PluginBridge-equivalent `__try__`/`__throw__` value/error distinction; and
  the new JSON draft differs from the reference renderer/facade in UTF-16,
  huge-number conversions, optional numeric coercion, deterministic/bounded
  failure and encoding behavior. A broad `global.reg` tree scan is unsafe
  because registrations inside lambdas/dead branches never execute.
- **Entrypoint root cause (real Swift discovery):** the checked metadata passed
  to `SwiftV2Cli.emit` omits front-matter `main`. FrontendBridge automatically
  invokes only a function literally named `main`, so a production-shaped
  `main: run` program initializes imported module globals but never calls
  `run()`; `NativeUiHost.evaluate` then fails with `native UI program did not
  register a root`. This is not a fixture issue: the assembled Swift command
  uses the same `convertSourceWithMetadata` result.
- **Builder root cause (next real-runtime boundary):** once `main: run` is
  invoked, `vstack`/`styled` reach `__method__("toList", children)` with an
  already proper `Cons/Nil` value. The v2 shared runtime returns that list
  unchanged, but Swift lacks the method case and terminates with `method not
  found: toList on List(TextNode_(...))` before `lower` can produce a root.
  After `toList` identity, the same gate advances to
  `method not found: mkString on List("padding-left:16px;...")`; Swift also
  omitted the shared List string-join overloads used by `_styleCss`.
  After `mkString`, `lower` reaches `element` but attrs arrive as the checked
  frontend's proper `List(Tuple2(String, Value))`; `nativeUiStringMap` accepts
  only `.map` and fails with `NativeUiElement.attrs expected Map[String,
  Value]`. The ABI must normalize the frontend representation rather than
  changing the standard lowerer fixture.
- **Rejected WIP behavior:** collapsing explicit throw and runtime failure to
  one description String loses the thrown ADT; catching every normalized host
  `Error` hides runtime bugs; `Int64(value)` omits VM/v1 trimming. The current
  v2 `String.toInt` also leaks `NumberFormatException`, so PluginBridge does not
  yet provide its claimed recoverable-error oracle. JSON surrogate
  halves are dropped and astral scalars render as invalid `\\u1f600`; renderer
  installation is ignored; huge integer/`optInt`/`asInt` and malformed-value
  behavior diverge. Spec review further found that installed self-hosted JSON
  emits uppercase escapes while the native fallback is lowercase; non-string
  map keys use the reference `Value.toString`; huge integral `optInt` uses
  `BigDecimal.longValue` low bits; and the exact unsafe validator case is a
  nested registration inside an outer registration's value expression.
- **Done-when:** the feature spec freezes exact semantics and receives Rozum
  pre-code approval; real Swift proves exact throw payload, nested handler
  propagation, recoverable trimmed `toInt`, non-catchable host negative, safe
  init-only global registration, complete JSON facade/Unicode/number/error
  parity, and the checked busi fixture runs through
  `serve(lower(view(), defaultTheme), ...)`. Full existing Swift/CLI/Apple and
  conformance gates remain green. Move to `fixed` only with landed SHA, and to
  `done` only after reporter/reviewer confirmation. The standard fixture must
  retain and invoke `main: run` exactly once after module initialization; an
  absent target is a checked error, not a root-registration runtime crash.
  Its unchanged curried builders must also prove `List.toList` identity under
  real Swift and the resulting CSS list must join through `mkString("")`;
  replacing either with direct constructors/string literals would not close
  the bug.
  `element` must then accept the association-list attrs and retain the produced
  styles; malformed list/tuple/key shapes remain bounded failures.
  The next observed failure is currently opaque (`app: not a function`); Swift
  omitted the non-callable value that shared v2 Runtime includes. Add that
  deterministic value text before classifying/fixing the next seam.
  With the value included, the failure is `List(32, 24, 20, 18, 16, 14)` from
  `lower.ssc`'s `sizes(level - 1)`. Shared `Runtime.applyFallback` supports
  proper-List indexed application; Swift evaluator does not.
- **Anonymous signal identity root cause:** after the standard lower fixture is
  green, the production locale/JSON fixture fails with `duplicate native UI
  signal '__computed__...localeText...' in scope 'root' has conflicting
  kind/default`. `computedSignal` and `eqSignal` derive ids from lexical site
  alone and ignore the spec's per-owner occurrence, so multiple calls to the
  same imported component alias one signal.
- **List concatenation root cause:** after anonymous ids are qualified, the
  production card lowerer fails on `headerParts ++ [bodyEl] ++ footerParts`.
  Shared v2 `arithOp` concatenates proper lists for `+`/`++`; Swift dynamic
  arithmetic implements only String concatenation.
- **Post-code review status:** runtime structure passed inspection, but closure
  remains blocked on faithful coverage. The first green regression checkpoint
  is `82e10647e`; reviewers require complete per-operator List matrices,
  `mkString` rejection negatives, anonymous-derived transactional lifecycle
  execution, and direct-map/events/array/nonzero-source NativeUi normalization
  before the bug may move from `open` to `fixed`.
- **Nested-owner lifecycle root cause (real Swift discovery):** an outer keyed
  render can provision an owner-scoped anonymous derived signal and then run an
  inner `reconcileKeyed`. The inner commit calls global scope disposal before
  the outer `ownerScopes` entry exists, so it deletes the still-live outer
  signal. The faithful probe observes four distinct outer/inner sibling ids but
  only three total cells instead of empty-header baseline plus four. Disposal
  must be deferred to the outermost reconciliation commit; the outer snapshot
  remains the rollback boundary and recursively removed owner subtrees are
  collected before the single disposal pass.
- **Final nested-delete proof still required:** keep the outer key and remove
  only its inner keyed child. The inner call must expose an empty deferred
  disposal list; the enclosing commit must dispose exactly the inner derived
  cell while preserving the outer cell, and reinsertion must not resurrect the
  deleted cell object even though its structural id is reused.
- **Fix/result:** the landed Swift evaluator/runtime now executes the standard
  lower/serve + locale/JSON pipeline, exact manifest/try/conversion/List/map
  boundaries, and owner-transactional anonymous derived signals. The final
  retained-outer nested-delete proof is green, both reviewers approved, Swift
  backend is 54/54, combined CLI is 54/54, PluginBridge is 33/33, assembled
  Swift CLI passes, and the same production app builds/verifies on macOS and
  iPhone 16 Pro Simulator. `tkv2-*` conformance is 12/12.

## v21-module-gate-misses-jca-provider — derived JRE omits Ed25519 module
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 43fded0f9
     confirmed: no
     gate: tests/e2e/v21-negative-toolchain-release-gate.sh -->

**Status:** fixed (2026-07-12, `43fded0f9`), awaiting Sergiy confirmation;
found by codex in the standard-only negative release gate.

- **Real-harness repro:** run `tests/e2e/v21-negative-toolchain-release-gate.sh`.
  The copied standard graph with the current `jdeps`-derived module set changes
  `crypto-verify-demo.ssc` from identical to `both-fail`; focused VM and ASM
  both exit 1 after three lines with `ssc: Ed25519 Signature not available`.
- **Root cause:** `Signature.getInstance("Ed25519")` discovers its JCA provider
  dynamically from `jdk.crypto.ec`, so static `jdeps --print-module-deps` cannot
  see the edge. The existing JRE-shaped gate did not exercise signature APIs
  and therefore reported an incomplete runtime module set as green.
- **Done-when:** the allowed standard runtime module set explicitly retains the
  JDK crypto provider module, focused Ed25519 VM/ASM checks pass under
  `--limit-modules`, and the negative exhaustive parity returns to 53/13 with
  zero unclassified or blocking rows. Compiler modules must remain absent.
- **Fix/result:** both module-limited gates add the reflective `jdk.crypto.ec`
  provider edge and run exact Ed25519 VM/ASM checks. The combined negative
  environment returns to 53/13/129, compiler modules remain unresolvable, and
  the consolidated release gate passes.

## v21-native-sql-fence-token-activates-client-code — SQL token parsing widened code fences
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: e3632db14
     confirmed: no -->

**Status:** fixed (2026-07-12, `e3632db14`), awaiting Sergiy confirmation;
found by codex in the full SQL-fence release gate before push.

- **Real-harness repro:** run
  `scripts/v21-self-hosted-core-release-gate --skip-install` after native SQL
  fence lowering. `sql-h2-quickstart.ssc` becomes standard-green, but
  `derived-route-clients.ssc` newly becomes `both-fail` on VM/ASM with
  `unbound global: awaitClient`, leaving parity at 51/15 and the taxonomy row
  unclassified.
- **Root cause:** `sscFenceSource` reused the new attribute-stripped SQL fence
  token for ordinary ScalaScript fences. That changed the existing standard
  JVM side contract by executing `scalascript @side=client`, which is a
  browser/JS-only block and was previously excluded.
- **Done-when:** attribute tokenization remains available for `sql @db` and
  `sql @side`, while ordinary code/YAML fence selection retains its prior exact
  language-tag behavior. Full parity must advance to 52 identical / 14
  both-fail with zero mismatch, one-sided, or unclassified rows.
- **Fix/result:** only SQL dispatch uses the attribute-stripped token; ordinary
  code/YAML fences keep exact tag matching. `derived-route-clients.ssc` is
  standard-green again, the SQL quickstart stays exact, and the exhaustive
  release gate reaches the required 52/14 classification.

## v21-native-typed-sql-crud-missing — standard provider lacks typed Db writes/read
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 50d01a136
     confirmed: no -->

**Status:** fixed (2026-07-12, implementation `50d01a136`, taxonomy
`f92ca4fcb`, boundaries `333d0a9bd`), awaiting Sergiy confirmation; accepted
from the final TI-8.2d runtime taxonomy and owned by codex.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run examples/typed-sql-crud.ssc` and repeat with
  `--bytecode`; both are reviewed `both-fail` rows while explicit
  `bin/ssc-tools run --compat-frontend` supplies the reference behavior.
- **Root cause boundary:** the self-hosted document extractor drops the schema
  SQL fence; independently, derives initialization calls missing
  `RowCodec_derived`, and `Db.query[Todo]` loses `Todo` before CoreIR. Insert and
  update survive as `Db` method calls over a portable `Todo/3`, so provider
  writes can use registered field metadata once the missing boundaries land.
- **Done-when:** schema execution plus typed insert/update/read produce the
  public exact output on standard VM/direct ASM/reproducible build-jvm through
  the core-free SQL provider, with negative conversion coverage and no v1 or
  compiler fallback.
- **Fix/result:** the parser retains the exact `Db.query[A]` nominal tag,
  `RowCodec_derived` registers portable Mirror schemas, and the provider owns
  fully-bound query/insert/update. The public row is exact on VM/ASM/slim/JRE/
  build-jvm; focused errors are bounded before stdout, and exhaustive runtime
  taxonomy reaches zero blocking rows.

## v21-native-sql-fence-binding-missing — SQL section result is not native
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 97c7d3e00
     confirmed: no -->

**Status:** fixed (2026-07-12, implementation `97c7d3e00`, taxonomy
`721490e99`, side correction `e3632db14`), awaiting Sergiy confirmation;
accepted from the final TI-8.2d runtime taxonomy and owned by codex.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run examples/sql-h2-quickstart.ssc` and repeat with
  `--bytecode`; both are reviewed `both-fail` rows while explicit compatibility
  supplies the reference behavior.
- **Root cause boundary:** `sscProgramSource` recognizes ScalaScript and YAML
  fences only. Every SQL fence is absent from CoreIR, so no DDL/DML/query runs;
  surviving code lowers `ActiveUsers.sql` and `Headcount.sql` as method calls on
  unbound heading globals. The current core-free provider already owns named
  connections plus `Db.query/execute`; it needs one generic raw SQL result
  operation and section-value registration, not a v1 SQL parser/runner.
- **Done-when:** DDL/DML/query fences, `${expr}` binds, and generic section
  result binding are exact on standard VM/direct ASM/reproducible build-jvm,
  including bounded malformed/config errors and no transparent fallback.
- **Fix/result:** the document projection injects source-ordered `Db.sql`
  calls, `_sqlBlock_N`, and the first structural `<Section>.sql`; the core-free
  provider returns portable row maps or update counts. Public quickstart and
  focused bind/order fixtures are exact on VM/ASM/slim/JRE/build-jvm, negative
  sentinels fail before stdout, and release parity advances to 52/14 with one
  typed CRUD blocker remaining.

## v21-native-tuple-lambda-destructuring — collection callbacks expect two arguments
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 12d3d9cf2
     confirmed: no -->

**Status:** fixed (2026-07-12, language `12d3d9cf2`, taxonomy `06a518685`),
awaiting Sergiy confirmation; found by codex after parameterless-def value
semantics advanced the mini-language pipeline report.

- **Real-harness repro:** standard VM/direct ASM now print the first nine
  canonical `dsl-mini-language.ssc` lines, then both fail after the pipeline
  report heading with `match: no arm for Pair/2`; explicit compatibility prints
  four additional `[phase] ok` lines.
- **Root cause:** source `(name, pass) => ...` and `(phase, outcome) => ...`
  callbacks lower as `lam 2`, but `List.map`/`flatMap` pass one portable
  `Pair/2` element. The body therefore receives the wrong environment shape and
  later tries to match a Pair as an Either outcome.
- **Done-when:** a tuple-pattern lambda accepts one Pair/Tuple2 value,
  destructures it once in declaration order, and works across imports and
  collection callbacks on VM/ASM/build-jvm. Ordinary `(a, b) =>` callables that
  are invoked with two arguments must retain their existing arity; no runtime
  arity retry, reflection, or DSL special case.
- **Fix/result:** the shared collection callback seam recognizes only exact
  `Pair`/`TupleN` tags whose field count matches the closure arity. The full
  public pipeline is exact on VM/ASM/build-jvm; release parity is 48/18 with
  five blockers and zero mismatch/one-sided rows.

## v21-native-focus-optics-unlowered — Focus/Prism remain globals
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a3d5abde0
     confirmed: no -->

**Status:** fixed (2026-07-12, frontend/provider `a3d5abde0`, artifact
`16a4b9f8f`, taxonomy `5557ddf61`), awaiting Sergiy confirmation; found by
codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM run `examples/lenses.ssc`,
  print only the two pre-Focus rows, then fail `unbound global: Focus`;
  explicit `--compat-frontend` prints all 23 canonical rows.
- **Root boundary:** CoreIR retains `app(global Focus, lam ...)` and a bare
  `global Prism`; no structural path or optic value is synthesized.
- **Done-when:** portable Lens/Optional/Traversal/Prism values implement the
  public structural paths and composition exactly on VM/ASM/build-jvm without
  compiler macros, reflection, example-specific globals, or fallback.
- **Fix/result:** the self-hosted frontend serializes field/`.some`/`.each`
  selector steps and exact Prism variants to portable CoreIR; a required
  core-free ServiceLoader provider performs immutable get/set/modify/compose.
  The first release run exposed a separate strict `build-jvm` provider-prefix
  omission, fixed in `16a4b9f8f`. All 23 public rows are now exact on standard
  VM/direct ASM/reproducible JAR; arbitrary getter arithmetic fails before
  stdout with `__unsupported_focus_path`. Full release parity is 51/15 with
  zero language-runtime rows and two remaining SQL-provider blockers.

## v21-native-named-copy-labels-dropped — case-class copy overrides by position
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d01d2e9f1
     confirmed: no -->

**Status:** fixed (2026-07-12, `d01d2e9f1`), awaiting Sergiy confirmation;
found by codex in TI-8.2d3m audit.

- **Real-harness repro:** before the Focus failure, `alice.copy(age = 31)`
  prints `older   : 31, 30` instead of `older   : Alice, 31` on both standard
  lanes. Explicit compatibility is correct.
- **Root cause boundary:** named-argument labels are erased before CoreIR;
  `copy(age = 31)` becomes `__method__("copy", alice, 31)` and replaces field
  zero. Multi-label copies happen to work only when labels follow declaration
  order.
- **Done-when:** generic structural copy preserves/reorders labels by the
  registered case-class layout, evaluates receiver/overrides once, and keeps
  positional copy behavior unchanged on VM/ASM/build-jvm.
- **Fix/result:** the native lowerer binds receiver and named overrides once in
  source order, then passes label/local pairs through existing portable copy
  dispatch. The generic focused fixture is exact on standard VM, direct ASM,
  and reproducible build-jvm (`RCN` proves ordering); release stays 50/16 with
  three blockers because the independently tracked Focus/Prism gap remains.

## v21-native-derives-mirror-unsynthesized — product evidence is absent
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: db97ad954
     confirmed: no -->

**Status:** fixed (2026-07-12, language `db97ad954`, taxonomy `151fd65b1`),
awaiting Sergiy confirmation; found by codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM fail before stdout with
  `unbound global: summon`; explicit compatibility prints
  `Person`, `name|age`, `String|Int`, `name,age`.
- **Root boundary:** the case-class layout is registered and `Csv.derived` is
  retained, but `derives Csv` creates no `Mirror.Of[Person]` value or exact
  `Csv[Person]` dictionary; both summons remain `global summon` in CoreIR.
- **Done-when:** portable product metadata and derived evidence are synthesized
  from declarations, exact summon lookup resolves both values, and unsupported
  evidence remains a bounded compile/runtime failure without reflection.
- **Fix/result:** the self-hosted AST retains ordered product types and derives
  names; the lowerer emits portable Mirror data plus one source-ordered cached
  `TC.derived` result under exact given keys. Focused aliases/caching and the
  public VM/ASM/build-jvm output are exact; release is 49/17 with four blockers.

## v21-native-parameterless-def-value — nullary method is passed as a function
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 12d3d9cf2
     confirmed: no -->

**Status:** fixed (2026-07-12, language `12d3d9cf2`, taxonomy `06a518685`),
awaiting Sergiy confirmation; found by codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM print the mini-language success
  heading then fail `arity: 0 expected, 1 given`; explicit
  `--compat-frontend` prints all 13 canonical lines.
- **Root cause:** `def typeCheckPass: Pass[Any, Any] = ast => ...` lowers to
  `lam 0(lam 1(...))`. Bare value use in
  `parsePass.andThen(...).andThen(typeCheckPass)` passes the nullary closure
  itself; the composed pipeline later calls it with the AST.
- **Done-when:** an ordinary reference to a declared parameterless def evaluates
  it once at the use site, while explicit `def f()` and higher-arity function
  values retain their current semantics; focused and public VM/ASM/build-jvm
  output is exact.
- **Fix/result:** source clause shape survives the self-hosted AST and lowering;
  a bare declared reference emits one zero-argument CoreIR application while
  explicit callees are not double-applied. The imported regression and all 13
  public lines are exact on VM/ASM/build-jvm.

## v21-native-distributed-loopback-provider-missing — NamedHandler is absent from standard runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 31d730c1e
     confirmed: no -->

**Status:** fixed (2026-07-12, provider `31d730c1e`, language `2b87c57df`,
taxonomy `e0e7e98c3`), awaiting Sergiy confirmation; found by codex after the
core-free Actors slice landed while continuing TI-8.2d3.

- **Real-harness repro:** run `bin/ssc-standard run` and repeat with
  `--bytecode` on `examples/distributed-join.ssc` and
  `examples/distributed-log-aggregation.ssc`.
- **Observed:** all four runs exit 1 before file I/O at
  `ssc: unbound global: NamedHandler`; no stdout.
- **Second boundary after provider install:** `NamedHandler` resolves, but all
  four real runs now exit at `unhandled runtime effect:
  HandlerRegistry.register`. The self-hosted lowerer routes the imported object
  method through the effect ABI rather than the registered method-object field.
  Resolve that exact structural/effect ownership in the core-free provider or
  lowerer; do not install a catch-all effect fallback.
- **Third boundary after exact ABI binding:** log aggregation is byte-exact on
  VM/ASM. Join exits zero but exposes separately tracked
  `v21-native-tuple-field-patterns`; its literal and typed tuple fields are not
  enforced by self-hosted lowering.
- **Expected:** a required core-free provider owns the exact named-handler,
  stage, local-loopback cluster, distributed map/filter, shuffle group/reduce,
  result, and close contracts used by these two standard examples. Fixed input
  fixtures produce deterministic output on VM/ASM/build-jvm without actor
  network transport, the v1 scheduler, or compatibility fallback.
- **Plan/done-when:** specify portable values and deterministic partition/order/
  error semantics, implement and unit-test a ServiceLoader provider, prove both
  public programs with fixed inputs on all standard JVM paths, pass the full
  release/dependency gates, and retire only the two distributed taxonomy rows.
- **Fix/result:** the required core-free provider owns exact structural
  `HandlerRegistry.*` operations plus portable local-loopback stage/shuffle
  values. Both public examples are exact on VM/ASM/slim/build-jvm; parity moves
  to 46 identical / 20 both-fail with zero mismatch/one-sided rows and the two
  distributed taxonomy rows are retired.

## v21-native-actors-provider-missing — runActors is absent from standard native runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 289b828b9
     confirmed: no -->

**Status:** fixed (2026-07-12, provider `289b828b9`, language/runtime
`ac30dd778`, taxonomy `2230ebc8a`), awaiting Sergiy confirmation; found by
codex after Async landed while continuing TI-8.2d3.

- **Real-harness repro:** run `bin/ssc-standard run --native` and
  `--native --bytecode` on `examples/actors-pingpong.ssc` and
  `examples/actors-typed-remote-spawn.ssc`.
- **Observed:** all four runs exit 1 at the first block with
  `ssc: unbound global: runActors`; no stdout.
- **Second boundary after provider install:** ServiceLoader rejects the complete
  standard provider set before evaluation with
  `native plugin ownership conflict for global 'exit': 20-os and 60-actors`.
  Actor `exit(pid, reason)` and process `exit(code)` therefore need one explicit
  arity/shape dispatch owner or a lowering-owned namespace; disabling ownership
  checks or falling back to compatibility code is not acceptable.
- **Third boundary after explicit exit composition:** typed loopback is exact on
  VM/ASM, but ping-pong is not. VM prints only `after timeout: None`,
  `before timeout: None`, `done`; direct ASM fails with
  `Actors scope failed: if: condition not Bool: "one"`. This points at the
  provider callback ABI/concurrent source-closure execution rather than mailbox
  registration. Diagnose against emitted CoreIR/closure invocation and pin the
  multi-actor source shape in the real launcher before retiring either row.
- **Expected:** a required core-free Actors provider owns local virtual-thread
  mailboxes, timeout receive, send/self/exit, quiescent runner shutdown, and the
  typed named-behavior loopback surface. Canonical outputs match the explicit
  compatibility tier without loading its bridge or interpreter.
- **Plan/done-when:** specify lifecycle/quiescence/error semantics, implement a
  ServiceLoader provider with unit and assembled regressions, prove both public
  examples exact on VM/ASM/build-jvm, pass full release/dependency gates, and
  retire only the two proven actor taxonomy rows.
- **Root cause/fix:** the standard tier had no Actors provider. Adding it exposed
  two independent boundaries: OS and Actors both claimed bare `exit`, and the
  self-hosted frontend treated infix `pid ! msg` as a later prefix negation while
  primitive typed patterns never matched `String`. OS now solely owns bare
  `exit` and explicitly dispatches actor-shaped calls to `actor.exit`; the front
  emits portable actor send and `__isTag__` recognizes primitive nominal types.
  The required Actors provider supplies FIFO virtual-thread mailboxes,
  quiescence/error propagation, timeout/self/exit, and typed named loopback.
  Provider unit is 4/4 and OS dispatch is 3/3; focused and public outputs are
  exact on VM/ASM/build-jvm. Full parity is 44 identical / 22 both-fail / 129
  skipped with zero mismatch/one-sided rows; taxonomy is 10 blockers / 22 total
  and the complete release gate passes.

## v21-native-async-provider-missing — runAsync is absent from standard native runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 4a5caa0ae
     confirmed: no -->

**Status:** fixed (2026-07-12, `4a5caa0ae`, taxonomy `7ac63130d`), awaiting
Sergiy confirmation; found by codex after the Generator provider landed while
continuing TI-8.2d3.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/async-demo.ssc`, then repeat with
  `--bytecode`.
- **Observed:** both engines exit 1 at the first block with
  `ssc: unbound global: runAsync`; no demo output is produced.
- **Expected:** a core-free standard provider owns deterministic `runAsync`,
  virtual-thread `runAsyncParallel`, and `Async.delay/async/await/parallel`
  handling with ordered results and propagated failures on VM, ASM, and
  `build-jvm` without the compatibility bridge.
- **Plan/done-when:** specify the Future/error/lifecycle contract, extend the
  native effect-runners provider, cover sequential and parallel behavior in
  unit and real assembled fixtures, prove the complete public demo exact, pass
  every release/dependency gate, and retire only `async-demo.ssc` from runtime
  taxonomy.
- **Fix/evidence:** the required effect-runners provider now owns deterministic
  `runAsync`, virtual-thread `runAsyncParallel`, opaque futures, delay,
  async/await/parallel, nested scope restoration, ordered joins, explicit
  failures, and the bounded named-method `recvFrom` bridge. Unit is 4/4 with
  latch-proved concurrent start and reverse completion. Focused/public demos
  are exact on VM/direct ASM/build-jvm; full parity is 42 identical / 24
  both-fail / 129 skipped with zero mismatch/one-sided rows, and taxonomy falls
  to 12 blockers / 24 total.

## v21-native-generator-provider-missing — generator is absent from standard native runtime
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fa265325f
     confirmed: no -->

**Status:** fixed (2026-07-11, `fa265325f`, taxonomy `6f3c398e5`), awaiting
Sergiy confirmation; found by codex while continuing TI-8.2d3 after the Dataset
provider landed.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/generators.ssc`, then repeat with
  `--bytecode`.
- **Observed:** both engines exit 1 at the first expression with
  `ssc: unbound global: generator`; no example output is produced.
- **Expected:** a core-free standard provider owns `generator`, `suspend`, and
  the local Generator combinators with pull backpressure. Finite streams are
  ordered; `take` cancels an abandoned infinite upstream; VM, ASM, and
  `build-jvm` are exact without compatibility code.
- **Plan/done-when:** specify the queue/lifecycle contract, implement a required
  ServiceLoader provider over JDK 21 virtual threads and synchronous handoff,
  add unit and assembled regressions including infinite Fibonacci and nested
  flatMap, pass every dependency/distribution/corpus gate, and retire only the
  proved generator taxonomy row.
- **Fix/evidence:** a required core-free provider now owns `generator`,
  dynamically scoped `suspend`, single-consumer synchronous pull, explicit
  producer failures, and all local combinators. Unit coverage is 5/5 including
  100k stack safety and latch-proved infinite-source cancellation. The focused
  lifecycle fixture and all thirteen public lines are exact on VM/direct ASM;
  `build-jvm` is exact and compiler-free. Full parity is 41 identical / 25
  both-fail / 129 skipped with zero mismatch/one-sided rows; taxonomy falls to
  13 blockers / 25 total and the release gate is ready.

## v21-native-dataset-provider-missing — standard Dataset calls escape as effects
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 17cee1805
     confirmed: no -->

**Status:** fixed (2026-07-11, `17cee1805`, taxonomy `9feff81a8`), awaiting
Sergiy confirmation; found by codex while starting queued TI-8.2d3g.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run each of
  `examples/dataset-{stats,word-count,parallel-sum}.ssc` through
  `bin/ssc-standard run --native`, then repeat with `--bytecode`.
- **Observed:** VM rejects `Dataset.of`, `Dataset.fromFile`, and
  `Dataset.fromList` as unhandled effects. Direct ASM leaks the same missing
  statics as `Op/3`; rendering the 100k-element `fromList` payload additionally
  overflows `Show.ul` before the real program can run.
- **Second boundary after provider install:** `dataset-word-count.ssc` still
  fails before any lazy stage executes. Structural CoreIR proves the lowerer
  sends opaque Dataset receivers through `_sel_flatMap`, `_sel_filter`,
  `_sel_map`, and `_sel_take`; those helpers match only List/Option/Either and
  try to destructure `ForeignV` as data instead of falling through to
  `__method__`. Split-val Dataset calls work, confirming provider values and
  tuple callbacks are sound.
- **Expected:** the standard native provider owns local `Dataset` constructors,
  lazy transformations, and terminals on both execution engines, with
  deterministic values and stack-safe large-list conversion. Spark and
  distributed execution remain explicit backend/provider surfaces.
- **Plan/done-when:** specify and add a core-free ServiceLoader provider with no
  `PluginBridge`, v1 interpreter, Scalameta, Scala compiler, or Java compiler
  dependency; prove provider unit behavior, exact assembled VM/ASM/build-jvm
  output for all three public examples, 100k-element stack safety, strict
  dependency/class-load gates, full corpus/parity/taxonomy, and fresh
  conformance before retiring the three rows.
- **Fix/evidence:** a required native Dataset provider now owns the lazy local
  plan, deterministic operations, UTF-8 files, iterative list conversion, and
  ordered virtual-thread pointwise stages. Structural selector helpers retain
  their List/Option/Either fast arms and dynamically fall through for opaque
  receivers. Provider unit is 4/4; all three public examples and the complete
  fixture are exact on VM/direct ASM/build-jvm. The full release gate is ready
  at 50 runtime successes, 40 identical / 26 both-fail / 129 skipped, zero
  mismatch/one-sided rows, and 14 remaining blockers.

## v21-native-doc-nested-render — nested documents leak the runtime tag
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: fe279650d
     confirmed: no -->

**Status:** fixed (2026-07-11, `fe279650d`), awaiting Sergiy confirmation;
found by codex when native `md` unblocked the complete public
`examples/content.ssc` execution.

- **Real-harness repro:** after a current-source `scripts/sbtc "installBin"`,
  run `bin/ssc-standard run --native examples/content.ssc` (or add
  `--bytecode`). Both lanes complete, but the `render(doc(table(...),
  table(...)))` section prints `NativeDoc(=== Fruits ===, ...)` and
  `NativeDoc(=== Numbers ===, ...)` rather than the nested document text.
- **Expected:** a `NativeDoc` nested as a part of another `NativeDoc` is rendered
  recursively in source order with the same newline contract; the reserved
  runtime representation never leaks into user output. VM, ASM, and
  `build-jvm` remain byte-identical.
- **Root cause:** the core-free host provider recursively recognizes only the
  outer document. Each inner part is passed to the generic runtime display
  function, which correctly exposes an arbitrary ADT tag but does not know the
  provider-owned `NativeDoc` representation.
- **Plan/done-when:** queue a separate provider-owned spec/fix after the
  language-owned `md` slice lands; recursively flatten only `NativeDoc` values,
  retain ordinary value display, add nested/empty provider and assembled
  regressions, then rerun the full release/dependency/content gates.
- **Fix/verification:** provider-local recursion now flattens only `NativeDoc`
  leaves, skips empty nested documents, and preserves shared display semantics
  for every ordinary value. Host unit is 3/3; focused and full content output is
  exact on VM/direct ASM/build-jvm with no leaked tag; plugin/dependency and
  standard/slim/JRE/build-jvm gates pass; no-memo conformance is 17/17.

## v21-native-multiline-markdown-import-dropped — std parser companion stays unloaded
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 96a1fa9dc
     confirmed: no -->

**Status:** fixed (2026-07-11, `96a1fa9dc`), awaiting Sergiy confirmation; found by codex while reproducing the queued
TI-8.2d2x `dsl-sql-recovery.ssc` blocker in the installed standard tier.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/dsl-sql-recovery.ssc` and repeat with
  `--bytecode`. VM fails `unhandled runtime effect: Parser.regex`; ASM fails
  `match: no arm for Op/3`. Structural compilation contains no `Parser_*`
  definitions and its source closure contains only the root file.
- **Root cause:** `ssc1-run.ssc0` scans Markdown imports one physical line at a
  time. Every import in the example wraps its link label before `](path.ssc)`,
  so none enters the DFS module closure; selected `Parser.regex` therefore
  looks like an unknown uppercase provider call and becomes the fallback `Op`.
- **Expected:** a standalone Markdown import link may wrap its label across
  physical lines outside fences. The scanner joins only that bounded label,
  preserves existing multiple-links-per-line behavior, and loads the ordinary
  `.ssc` declarations so `Parser.regex` resolves statically to `PRegex`.
- **Plan/done-when:** add a multi-file wrapped-import regression, fix the pure
  self-hosted import scanner without source rewriting or compatibility
  fallback, require the installed VM/direct-ASM public example to advance
  identically, then pass module-loading/native-entry/full release and fresh
  `v2-*` conformance gates before retiring only the proved taxonomy row.
- **Fix/verification:** a bounded pending-label state joins only complete
  standalone links outside fences and resets at paragraph/heading/fence
  boundaries. Focused VM/ASM output is `82`; the real four-module recovery
  closure loads exactly and all release gates pass.

## v21-native-dynamic-bigint-tostring — selected conversion is Int-only
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: e2511c6ad
     gate: tests/conformance/content-linked-namespaces.ssc -->

**Status:** done (2026-07-11, `e2511c6ad`); found by codex after the native structural
content provider exposed the next failure in `content-linked-namespaces.ssc`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native tests/conformance/content-linked-namespaces.ssc`
  and `--bytecode` print the imported section title, then fail at
  `(copyVersion() + fallbackVersion()).toString`: VM reports `i->str: not Int`
  and ASM reports `expected Int, got 1234`.
- **Expected:** selected zero-argument `toString` uses dynamic method dispatch
  unless the receiver is proven Int; BigInt renders `1234` identically on
  native VM/direct ASM and deterministic `build-jvm`.
- **Plan/done-when:** retain the `i->str` fast path for literal Int receivers,
  route every unproven receiver through `__method__("toString", value)`, add a
  focused structural regression for Int/BigInt/Float/String, and make the full
  linked-content example exact without weakening runtime errors.
- **Verification:** stage-2 single/multi fixpoints, native-entry, focused
  VM/ASM/build-jvm output, affected conformance 17/17, and the 195-row strict
  corpus/parity sweep pass; runtime successes improve from 44 to 45.

## v21-coreir-curried-closure-underapplication — nested parameter lists fail at runtime
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: e23bff273
     confirmed: no -->

**Status:** fixed (2026-07-11, `e23bff273`), awaiting Sergiy confirmation; found by codex while advancing the installed
`algebraic-effects.ssc` VM/direct-ASM regression after removing hidden
multi-effect CPS.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native examples/algebraic-effects.ssc` and its
  `--bytecode` twin reach `withLogger(println) { () => "done" }`, then fail
  `arity: 2 expected, 1 given`. Structural CoreIR correctly represents the
  source as nested applications of a two-argument closure; the runtime rejects
  the first, intentionally partial, application.
- **Expected:** the lowerer reconciles the source's nested parameter-list call
  with the known definition's flattened total arity before emitting CoreIR.
  VM/direct ASM retain exact closure arity; an invalid single-clause
  `required()` call must still fail rather than become a closure.
- **Plan/done-when:** pre-scan definition arities, flatten only a nested call
  whose combined arguments exactly satisfy a known definition, retain the
  existing under/over-arity negative gates, then require exact installed
  VM/ASM output and the affected conformance/release gates.
- **Root cause/fix:** the parser preserves parameter clauses as nested calls,
  while definitions use one flattened CoreIR lambda. The lowerer now pre-scans
  definition arities and combines only a known nested call whose total exactly
  satisfies that definition; strict runtime arity remains unchanged.
- **Verification:** installed VM/direct ASM complete all eleven
  `algebraic-effects.ssc` lines, native-entry retains under/over-arity failures,
  and the consolidated release gate plus fresh conformance 11/11 pass.

## v21-native-multi-effect-hidden-cps — declared operation gains a hidden argument
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 2a66b1221
     confirmed: no -->

**Status:** fixed (2026-07-11, `2a66b1221`), awaiting Sergiy confirmation; found by codex while running the installed
VM/direct-ASM regression for TI-8.2d2w2 standard effect runners.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native examples/algebraic-effects.ssc` (and repeat
  with `--bytecode`). Both lanes print through the Logger/State section and
  then fail with `arity: 1 expected, 0 given` at `handle(program())`. Structural
  CoreIR shows source `def program(): Int ! NonDet` as `program = lam 1 ...`
  instead of a zero-argument function whose `NonDet.choose` calls produce
  portable `Op` values.
- **Root cause:** the pre-portable KV9 `blockHasMultiShotResolved` branch still
  rewrites functions containing a declared `multi effect` operation into a
  private list-specific CPS convention with a hidden continuation argument.
  This conflicts with the now-canonical `effect.perform` / `effect.handle`
  contract and makes ordinary source calls arity-invalid.
- **Plan/done-when:** remove the hidden CPS rewrite so multi-shotness comes only
  from reusable portable resume closures, add the exact `handle(program())`
  shape to the installed VM/ASM regression, and require both the focused effect
  smoke and the full release gates before closing the bug.
- **Fix/verification:** the KV9 hidden-parameter branch is removed; declared
  multi-effect operations use the same portable `Op` and reusable resume
  closures as ordinary effects. The focused VM/ASM regression, exact public
  example, consolidated release gate, and fresh conformance 11/11 all pass.

## v21-runtime-taxonomy-stale-after-front-fixes — reviewed blockers lag parity
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 05454dd1c
     confirmed: no -->

**Status:** fixed (2026-07-11, `05454dd1c`), awaiting Sergiy confirmation; found by codex while running the exhaustive
post-rebase release gate for pure native content binding.

- **Real-harness repro:** fresh `scripts/native-front-corpus` reports 57
  sentinels and `scripts/bc-parity-sweep --strict` reports 57 identical / 9
  both-fail / 129 skipped, but `scripts/v21-sentinel-taxonomy` first rejects the
  stale `agent-mcp-toolsource.ssc` override and `scripts/v21-runtime-taxonomy`
  then rejects the older 34-row manifest (including rows now identical).
- **Expected:** both reviewed taxonomies describe the same fresh corpus/parity
  reports, retain zero sentinel `standard-gap` rows, and remove/reclassify only
  rows proven by exact installed VM/ASM output.
- **Plan/done-when:** join every stale/unclassified row to its exact fresh
  output and owning feature, update overrides/manifests/ceilings atomically,
  then rerun corpus, strict parity, both taxonomy gates, and the consolidated
  release gate. This reconciliation is independent of `contentBind`, whose
  focused and distribution gates are green.

## v21-build-jvm-content-path-nondeterminism — content.bin leaks source roots
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 82d27b896
     confirmed: no
     gate: tests/e2e/v21-build-jvm-release-gate.sh -->

**Status:** fixed (2026-07-11, `82d27b896`), awaiting Sergiy confirmation; found by codex in the exhaustive post-effect
release gate after the structural content provider landed.

- **Real-harness repro:** `tests/e2e/v21-build-jvm-release-gate.sh` builds the
  same `argv.ssc`/`std-crypto.ssc` closure under sibling `a/src` and `b/src`
  directories, then fails the first `cmp`. Extracted JARs differ only in
  `META-INF/scalascript/content.bin`; its module `source` strings contain the
  two canonical temporary paths (and absolute staged-library paths).
- **Expected:** build-jvm is byte-reproducible and checkout/source-location
  independent. Embedded content keeps the same documents and module graph but
  uses the already-frozen stable `NativeSourceUnit.displayPath` identities.
- **Plan/done-when:** map content module sources and direct imports through the
  linked source-unit display table only during artifact packaging, then make
  the full build-jvm reproducibility gate and exhaustive release gate pass.

## v21-native-http-request-source-arity — canonical Request omits provider fields
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 794cb6e7c
     confirmed: no
     gate: tests/e2e/v21-native-entry-smoke.sh -->

**Status:** fixed (2026-07-11, `794cb6e7c`), awaiting Sergiy confirmation; found by codex after fixing extern-class layout
ownership and rerunning the installed native-entry gate.

- **Real-harness repro:** `tests/e2e/v21-native-entry-smoke.sh` reaches the HTTP
  server fixture but returns `500 native HTTP handler failed: match: no arm for
  Request/11`. The canonical `std.http.Request` declaration generates 9-field
  accessors, while the portable HTTP host intentionally appends `params` and
  `query` and registers the same 11-field order used by existing examples.
- **Expected:** `std.http.Request` is the authority for the provider value shape
  and includes `params` and `query`; `req.path`, route parameters, and query
  access work through the same 11-field contract on VM/direct ASM.
- **Plan/done-when:** add the two missing fields to the canonical case class,
  retain the provider's established order, make the installed server fixture
  exact again, and rerun native-entry, release, and fresh conformance gates.

## v21-native-extern-class-members-escape — abstract fields become parser sentinels
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: fd36ee87e
     confirmed: no -->

**Status:** fixed (2026-07-11, `fd36ee87e`), awaiting Sergiy confirmation; found by codex in the exhaustive post-effect
release gate after the content-provider main rebase.

- **Real-harness repro:** `scripts/v21-self-hosted-core-release-gate
  --skip-install` fails in the HTTP response provider fixture because imported
  `std/http.ssc` lowers `UploadedFile`'s abstract `name`, `filename`,
  `contentType`, `size`, `bytes`, and `path` fields into top-level cell writes
  from `_err`; the native entry rejects that structural CoreIR in both lanes.
- **Expected:** `extern class C:` owns and erases exactly its abstract member
  body. Members cannot leak into the module's top-level executable declaration
  stream, while the following Request/Response declarations remain visible.
- **Plan/done-when:** extend declaration-layout ownership to class headers,
  consume a braced/layout extern-class body atomically, add a focused imported
  VM/ASM regression around the existing HTTP fixture, and rerun the exhaustive
  gate before retiring any effect taxonomy row.

## v21-native-explicit-effect-handler-erasure — declarations and handlers disappear
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 4c0435f4b
     confirmed: no -->

**Status:** fixed (2026-07-11, `4c0435f4b`), awaiting Sergiy confirmation; found by codex while reducing the reviewed
`effects.ssc` standard-language/runtime blocker on the installed compiler-free
binary.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, both
  `bin/ssc-standard run --native examples/effects.ssc` and its `--bytecode`
  lane exit 1 with `unbound global: greet` and no stdout. A smaller staged
  source shows two independent losses: layout `effect E:` handling can consume
  following top-level declarations, and `resolveE` recognizes
  `handle(computation)(handler)` only to return the computation and discard the
  parsed partial-function handler.
- **Expected:** an effect declaration owns exactly its abstract member body;
  each member performs the portable `E.member` operation; explicit handlers
  receive source arguments plus a reusable resume closure and implement
  one-shot, early-return, deep/nested, `Return`, and multi-shot semantics
  identically on native VM/direct ASM.
- **Plan/done-when:** add an installed-binary VM/ASM fixture covering layout
  ownership plus zero/one/many arguments and every handler mode; retain an
  `effect_decl` through imports; lower operations to `effect.perform` and both
  handle operands to `effect.handle`; then make the focused fixture and all six
  compatibility lines of `examples/effects.ssc` exact before running the full
  release gate. No provider, v1 bridge, transparent fallback, or backend branch
  is allowed.

## v21-native-content-markdown-error-swallowed — malformed roots become empty content
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b6fe50ef2
     gate: tests/e2e/v21-self-hosted-markdown-frontend-smoke.sh -->

**Status:** done (2026-07-11, `b6fe50ef2`); found by codex in the consolidated
self-hosted release gate while verifying the new structural content projection.

- **Real-harness repro:** after `scripts/sbtc "installBin"`,
  `tests/e2e/v21-self-hosted-markdown-frontend-smoke.sh` reports
  `unterminated Markdown fence unexpectedly compiled`. The exact malformed
  root reaches `contentProjectModule`, whose default `contentDocument` branch
  replaces `MarkdownError(message, offset, line, column)` with an empty
  `DocumentContent` before `NativeV2Structural` can reject it.
- **Expected:** the canonical self-hosted `MarkdownError` crosses the projection
  boundary unchanged and becomes the established source-located compile error
  before native provider installation; no fallback document is fabricated.
- **Plan/done-when:** preserve `MarkdownError/4` in `NativeContentModule`, teach
  the Scala seed validator to rethrow its existing `source:line:column`
  diagnostic without reparsing, extend the structural regression test, then
  rerun the exact frontend smoke and consolidated quick release gate.
- **Root cause/fix prepared:** `contentDocument` intentionally had a defensive
  empty-document default, but `contentProjectModule` called it for every
  non-`MarkdownDocument`, including the canonical error ADT. Commit
  `b6fe50ef2` now preserves `MarkdownError/4`; the seed converts it back to the
  established source-located failure. Structural tests are 8/8 and both the
  exact Markdown frontend repro and native content e2e pass after the final
  rebase; affected conformance is 16/16, and the fix is on `origin/main`.

## tkv2-pwa-stale-default-backend — expected output pins retired JDK default
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: b060951ce
     gate: tests/conformance/run.sh -->

**Status:** done (2026-07-11, `b060951ce`); found by codex while running the mandatory
`tkv2-*` landing gate for `v2-swiftui-xcode-project`.

- **Real-harness repro:** after a fresh `scripts/sbtc installBin`, run
  `tests/conformance/run.sh --only 'tkv2-*' --no-memo`. Eleven cases pass, but
  `tkv2-pwa` fails only on line 2: expected `  (backend=jdk)`, got
  `  (backend=fast)`; all eight functional PWA assertions remain `true`.
- **Root cause:** `HttpServerBackends` now deliberately prefers the
  installed `fast` provider when no backend is selected, while the corpus
  expected file still pins the former JDK default. The fixture tests PWA
  manifest/service-worker behavior, not transport selection.
- **Expected/fix:** update the real-harness expected banner to the current
  deterministic default, rerun the isolated case and full `tkv2-*` gate, and
  report the result in Rozum. Do not weaken or filter the remaining output.
- **Fix/result:** only the obsolete expected banner changed. Isolated
  `tkv2-pwa` passed 1/1 and the complete no-memo `tkv2-*` corpus passed 12/12
  after the final rebase; all eight functional PWA assertions remain exact.

## v21-runtime-taxonomy-stale-http-mount — resolved standard row still blocks freeze
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 77da8e8e2 -->

**Status:** done (2026-07-11, taxonomy `77da8e8e2`); found by codex while
joining the fresh 195-row typeclass release report to the reviewed runtime
taxonomy. Provider implementation already landed in `608d63425`.

- **Real-harness repro:** fresh strict parity classifies
  `derived-route-clients.ssc` as `identical` with VM/ASM exit 0, and direct
  installed `ssc-standard run --native` plus `--bytecode` both exit 0 with
  identical empty output/stderr. `scripts/v21-runtime-taxonomy` nevertheless
  fails `stale or reclassified manifest row: derived-route-clients.ssc` because
  the manifest still says standard HTTP `mount` is missing.
- **Expected:** completed standard-provider rows leave the reviewed blocker
  manifest and tighten category/total ceilings; the core-only no-provider
  corpus diagnostic remains visible but cannot override the plugin-enabled
  standard parity contract.
- **Fix/result:** the stale row alone was removed after exact installed VM/ASM
  exit/output evidence. Standard-provider, blocker, and total ceilings tightened
  to 13, 23, and 35; the real taxonomy and exhaustive compiler-free release
  gate pass. No HTTP implementation changed, and the core-only no-provider
  diagnostic remains visible in the native-front report.

## v2-native-table-urlprotocol-harness-race — strict action probe mutates shared Set concurrently
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 400931f68 -->

**Status:** done (2026-07-11, `400931f68`); found by codex in the mandatory
post-rebase six-test rerun and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum room.

- **Real-harness repro:** run
  `scripts/sbtc 'v2SwiftBackend/testOnly ssc.swift.SwiftBackendTest -- -z "native table"'`.
  The generated action probe can exit 134 after `actions:edit` with
  `NSInvalidArgumentException` in `Set.contains`: `TableURLProtocol.stopLoading`
  inserts into the static `stopped` Set on a URLSession callback while the main
  actor calls `wasStopped` without synchronization. Static `instances`, request
  reads, and response lookup are likewise unsynchronized.
- **Expected:** the controllable URLProtocol probe is data-race-free under
  strict Swift 6; cancellation polling and synthetic response delivery observe
  coherent snapshots and never crash independently of Store behavior.
- **Plan/done-when:** guard all `TableURLProtocol` shared state with one lock,
  copy the selected instance/request under that lock before doing callback/body
  work, then pass the action probe repeatedly, all six named tests, and the full
  40-test backend suite. Ask `nativeui-reviewer` to confirm the harness-only
  root cause in Rozum before closing.
- **Fix/verification:** one `NSLock` now owns `instances` and `stopped` plus
  every lookup; body stream reads and URLProtocol client callbacks happen after
  the selected value is copied outside the critical section. The reviewer
  confirmed production sources are unchanged and approved action 5/5, named
  6/6, and full backend 40/40.

## v2-swiftui-ios16-onchange-availability — generated renderer requires iOS 17 accidentally
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: d54d02126 -->

**Status:** done (2026-07-11, `d54d02126`); found by codex in the real iOS
Simulator strict typecheck and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum room.

- **Real-harness repro:** typecheck all generated AppCore/AppleApp sources with
  `xcrun swiftc -target arm64-apple-ios16.0-simulator` against the installed
  Simulator SDK. The two-argument `onChange(of:) { old, new in ... }` overload
  in keyed rendering and editable table cells is iOS 17-only.
- **Expected:** the frozen iOS 16 deployment compiles without availability
  annotations or runtime branching; both observers use the compatible
  one-argument overload.
- **Plan/done-when:** update both generated call sites, retain their behavior,
  and pass the exact strict iOS Simulator typecheck in the sixth named table
  gate plus the full Swift backend suite. The one-argument overload compiles on
  iOS 16 but is deprecated under the installed macOS 14+ SDK and therefore also
  fails warnings-as-errors; use deployment-compatible `task(id:)` observation
  instead of choosing either incompatible `onChange` overload.
- **Fix/verification:** both generated observers use `task(id:)`. The sixth
  named table gate typechecks the complete generated Apple source set for the
  installed iOS 16 Simulator target under strict Swift 6; the full Swift backend
  is 40/40.

## v2-native-table-request-url-untested — Swift URL resolver and CLI routing lack executable coverage
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1ecbc80ca -->

**Status:** done (2026-07-11, `1ecbc80ca`); reported and confirmed by
`nativeui-reviewer` in the `scalascript` Rozum room after executable review.

- **Real-harness repro:** current JVM generator tests normalize/embed a base and
  CLI tests call `SwiftV2Cli.emit` directly, but no generated Swift execution
  resolves absolute, root-relative, and base-relative request URLs or rejects
  scheme-relative/credential/fragment/hostless forms. No application command
  proves `--server-url` reaches the emitted Store configuration.
- **Expected:** executable Swift uses the sole normalized Apple base exactly as
  specified, and the real public command route threads the same value into the
  generated package.
- **Plan/done-when:** execute the resolver matrix through generated strict Swift
  plus controllable URLProtocol and invoke the actual CLI command path with
  `--server-url`; pin accepted URLs, rejected forms, and emitted configuration.
- **Fix/verification:** generated Swift executes the accepted/rejected URL
  matrix and the real `BuildCmd` threads normalized `--server-url` into Store
  configuration. Swift 34/34 and CLI 6/6 passed before the reviewer APPROVE.

## v2-native-table-rowkey-adapter-drop — non-default row identity is silently lost outside Swift
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1ecbc80ca -->

**Status:** done (2026-07-11, `1ecbc80ca`); reported and confirmed by
`nativeui-reviewer` in the `scalascript` Rozum room after three review rounds.

- **Real-harness repro:** compile `dataTableView(..., rowKeyPath = "meta.key")`
  through every adapter. JS emits a DOM attribute that its mount runtime never
  reads, while Rust/TUI accept the fourth argument as `_row_key_path` and ignore
  it, so the target-independent public selection has no effect.
- **Expected:** every adapter preserves and consumes the exact dotted row key,
  or deterministically rejects a target that cannot implement it; silent
  fallback to `id`, index, or object identity is forbidden.
- **Plan/done-when:** make JS mount and Rust/TUI runtime consume strict row
  identity, add non-default/missing/empty/compound/duplicate adapter gates, and
  obtain the implementation reviewer's confirmation.
- **Review round 2:** JS must reject non-object rows rather than accepting an
  array via a numeric dotted segment. TUI/Rust must execute the complete
  missing/empty/compound/duplicate runtime matrix, not rely on compile smoke.
- **Fix/verification:** JS mount consumes the typed canonical identity and
  rejects arrays/missing/empty/compound/duplicates; TUI consumes the selected
  path and generic Rust rejects unsupported tables explicitly. JS 52/52, TUI
  35/35, and Rust 261/261 include executable invalid-key matrices.

## v21-functional-vm-asm-mkstring-parity — functional demo diverges on final dispatch
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 4c5254eed -->

**Status:** done (2026-07-11, `4c5254eed`); found and confirmed by codex
during the post-`PMapped` full strict parity sweep.

- **Real-harness repro:** `SSC=bin/ssc-standard scripts/bc-parity-sweep
  --strict` reports the only mismatch at `functional.ssc`. Both lanes exit 0
  and agree through `440`; the native VM prints
  `Op("Stub.mkString", ", ", <closure>)`, while direct ASM prints `Stub`.
- **Expected:** portable functional dispatch either produces the same real
  value on both lanes or fails identically under an owned runtime blocker;
  strict parity must have zero mismatch/one-sided rows.
- **Root cause:** self-hosted CoreIR lowers block-form
  `xs.foldLeft(z) { (acc, x) => ... }` as
  `App(__method__("foldLeft", xs, z), lambda)`. Portable method dispatch only
  accepts both `z` and `lambda` in one call, so the first application fabricates
  an unresolved `Op`; VM later exposes `Op("Stub.mkString", ...)`, while ASM
  collapses the same path to `Stub`.
- **Fix/result:** one-argument `foldLeft(z)` now returns a portable arity-one
  closure which completes the existing effect-aware fold when applied. The
  exact list/array fixture prints `1, 3, 6, 10, 15` and `10`; the functional
  example is canonical and byte-identical. Full parity is 26/0/40/129 with zero
  mismatch/one-sided rows; runtime blockers fall to 28.

## fast-http-session-cookie — successful setSession response loses Set-Cookie
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: d202d2abf -->

**Status:** fixed (2026-07-11); reported and confirmed by busi immediately
after pinning the hf-7 `--v2` fast backend. Fix commit: `d202d2abf`.

- **Real-harness repro:** assemble `bin/ssc`, boot busi's `src/v2/http/hub.ssc`
  on `--v2`, and submit the displayed code to `POST /pair`. The response is 200
  HTML but has no `Set-Cookie`; the cookie jar stays empty and the next
  `GET /api/vault` returns `{"error":"unpaired"}`. The same flow passed on the
  previous JDK transport.
- **Expected:** the correct pairing code reaches `req.form`; the resulting
  response's explicit `busi_device` cookie reaches the wire. Generic cookies,
  signed sessions, form/multipart fields and auth match the JDK SPI backend.
- **Impact:** every passwordless pairing/session flow is unusable on the new
  default `--v2` transport despite an apparently successful login response.
- **Root cause:** `FastServerBackend.toPojo` copied body/query/cookies but left
  `Request.form`, signed `session`, bearer/basic auth, JWT claims and files at
  defaults, bypassing the shared `RequestBuilder` used by the JDK backend. The
  correct code was therefore handled as an invalid form and no cookie response
  was created; `RawResponse` header serialization was not the fault.
- **Plan/done-when:** add a raw-input shared-builder path, reproduce through a
  real fast socket, run module/assembled/conformance gates, and obtain reporter
  confirmation from busi Vault plus canonical browser E2E.
- **Verification:** common 150/150, fast backend 5/5, interpreter-server 58/58,
  `rest-validate` INT/JS/JVM, assembled paired Vault 11-step/restart/leakage
  check, and canonical busi fast-backend Chromium 6/6 in 1.9 minutes.

## v2-swiftui-persisted-stale-wrapper-disposal — disposed wrapper can write disk or crash
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** retain a scoped persisted signal wrapper, dispose its
  owner/scope, then call the old native `set` closure. It still captures the
  tombstone cell strongly and stages UserDefaults through the live Host. Retain
  it past Host/Store deinit and the persisted `afterWrite` force-unwraps weak
  `self`, which can crash.
- **Expected:** every wrapper mutation authenticates the exact current live
  Host cell; disposed/replaced wrappers fail deterministically and never touch
  disk. A retained closure after root disposal fails without a crash. A fresh
  reinserted wrapper works normally.
- **Plan/done-when:** weakly guard Host plus `signals[key] === cell` before any
  mutation/side effect, remove force unwraps from persisted callbacks, and gate
  committed write, scope deletion, stale-old versus fresh-reinsert behavior,
  and invocation after Store/session deinit.
- **Root cause/fix:** generated wrappers trusted their captured cell forever and
  persisted callbacks force-unwrapped a weak Host. Reads/writes now require the
  exact current Host cell; disposed, replaced, and post-deinit closures fail
  without mutation, while a fresh reinserted wrapper commits normally.

## v2-swiftui-online-component-scope-split — onlineSignal is not process-wide
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** call `onlineSignal()` from two component/keyed scopes.
  Host creates two scope-local `__online__` cells. After the first owner receives
  `false`, a later owner of the second copy starts at default `true`; because the
  monitor is already active it receives no immediate replay and stays wrong until
  the next path transition.
- **Expected:** `onlineSignal` is one process/root-scoped signal, matching the
  frozen spec and JS/JVM singleton behavior; every owner observes current state,
  and only the last exact token cancels the single monitor.
- **Plan/done-when:** force online construction to the root signal key (or an
  equivalent singleton identity) and gate two component/keyed owners, late-owner
  current-value visibility, one monitor, and exact last-owner cancellation.
- **Root cause/fix:** online creation inherited `scopes.last`. It now explicitly
  uses the root key/cell, so component/keyed wrappers share current state and one
  target monitor.

## v2-swiftui-persisted-cell-dependent-journal — persisted writes can miss UserDefaults
<!-- status: fixed
     lane: apparatus
     area: other
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** the strict generated-Swift platform-signals probe
  currently materializes `store.cell(for: persisted)` before writing. A live
  persisted signal written by a retained AppCore closure without that cell, or
  during successful root evaluation before `built.observe`, updates Host memory
  but not `UserDefaults`.
- **Expected:** every successful committed persisted String write reaches the
  configured defaults suite, independent of renderer/cell materialization;
  root-evaluation failure and keyed rollback never escape, and disposal does
  not drop an already committed write.
- **Plan/done-when:** add a Host-owned commit journal/callback independent of
  Store cells, flush only at successful root/keyed commit, and executable gates
  for no-cell post-init, successful/failed root evaluation, failed keyed
  rollback, and committed disposal.
- **Root cause/fix:** Store persistence looked up `cells[key]` after Host writes.
  A Host-owned String journal now flushes at successful root/outer keyed commit,
  restores on abort/rollback, and writes independently of rendered cells.

## v2-swiftui-online-stale-monitor-generation — cancelled callback can mutate a restarted monitor
<!-- status: fixed
     lane: apparatus
     area: other
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** capture the old monitor callback, unsubscribe the last
  token, subscribe again, then deliver the captured callback. The Store has no
  monitor identity/generation guard, so the old callback sees the newly live
  online signal set and mutates the restarted generation.
- **Expected:** only the callback belonging to the current monitor generation
  may publish. A callback queued before cancel is inert after cancel/restart.
- **Plan/done-when:** bind callbacks to an opaque generation/token, invalidate it
  before cancel, and gate stale-versus-current delivery through the strict Swift
  fake monitor.
- **Root cause/fix:** callbacks had no identity. Every monitor start now owns a
  UUID token invalidated before cancel; only the current token may publish.

## v2-swiftui-persisted-wrong-type-corruption — rejected write can corrupt Host memory
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 0ade8bf7c
     gate: tests/e2e/v21-native-entry-smoke.sh -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** call the public persisted signal `set` closure with a
  non-String. Generic write currently assigns `current`/`dirty` before the
  persisted `afterWrite` type check throws, leaving a non-String in memory while
  disk/onWrite did not commit.
- **Expected:** rejected writes are atomic: the live value and defaults remain
  the prior String and the error is deterministic.
- **Plan/done-when:** prevalidate persisted values or restore the complete cell
  snapshot on `afterWrite` failure; add a real generated-Swift wrapper-set gate.
- **Root cause/fix:** generic writes mutated `current` before persisted String
  validation. The wrapper now prevalidates and restores its cell snapshot on any
  side-effect failure, leaving memory and defaults unchanged.

## v21-storage-container-print-gates — release fixtures expect obsolete quoted children
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: befc249d4
     gate: tests/e2e/v21-native-entry-smoke.sh -->

**Status:** done (2026-07-11, `befc249d4`; release confirmation
`d503cf856`); found and confirmed by codex in the mandatory post-rebase
native-entry run after K62.22 (`d1a7b5451`) intentionally aligned native
container printing with the parity renderer.

- **Real-harness repro:** `tests/e2e/v21-native-entry-smoke.sh` fails on
  `storage-demo.ssc`: actual `Some(alice)` / `List(user, role)` versus stale
  expected `Some("alice")` / `List("user", "role")`. The conformance golden
  `tests/conformance/expected/storage.txt` already owns the unquoted form.
- **Expected:** native-entry, native-provider class-load, and standalone
  build-jvm storage fixtures assert the current user-visible parity renderer.
- **Plan/done-when:** update all three stale exact/grep expectations and the
  storage feature spec, retain the value/order checks, then rerun native-entry,
  plugin-boundary, build-jvm smoke/release, slim/JRE, and conformance gates.
- **Fix/result:** the three assembled gates now assert `Some(alice)` and
  `List(user, role)`, matching conformance and
  `specs/v2.1-native-storage-effect.md`. Native-entry, provider boundary,
  build-jvm smoke/release, slim, JRE, and fresh conformance all pass.

## v21-http-fast-standard-tier-cutover — standard image lost its HTTP provider
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: d503cf856
     gate: tests/e2e/v21-native-plugin-boundary-smoke.sh -->

**Status:** done (2026-07-11, `d503cf856`); found and confirmed by codex while
running the v2.1 release gates after the HTTP-fast default swap (`67158c185`).

- **Real-harness repro:** after `scripts/sbtc "installBin"`,
  `tests/e2e/v21-native-plugin-boundary-smoke.sh` reports a missing staged
  `scalascript-v2-native-http-plugin`; `tests/e2e/v21-core-dependency-gate-smoke.sh`
  expects the same retired JAR. `tests/e2e/v21-slim-distribution-gate.sh` and
  `tests/e2e/v21-jre-module-gate.sh` both fail with `unhandled runtime effect:
  Response.text` because `http-fast-plugin` is present in tools `jars/` but not
  standard `jars/`. After the fast provider became runnable in the tools image,
  `tests/e2e/v21-native-entry-smoke.sh` also reaches its stale
  `http-server-feature-unavailable.ssc` assertion: `useGzip` now exits 0, while
  the gate still requires the retired "native HTTP server unavailable" error.
- **Expected:** the default HTTP-fast provider is staged into both tools and
  standard images; boundary/dependency gates discover its new artifact name;
  slim and module-limited HTTP response fixtures pass without the retired
  provider.
- **Plan/done-when:** update standard staging and all provider/dependency gate
  ownership from `http-plugin` to `http-fast-plugin`, replace the obsolete
  feature-unavailable assertion with a positive fast-provider feature check,
  retain the forbidden dependency/class-load scans, and rerun native provider,
  core dependency, slim, JRE, standard, build-jvm, native-entry, and
  conformance gates.
- **Root cause/fix:** the hf-5 module replacement updated the CLI dependency
  graph but left explicit standard/build-jvm allowlists and artifact-discovery
  globs on the removed JAR name. Those surfaces now own the fast provider plus
  its non-provider engine; VM/ASM positively execute `useGzip()`. All focused
  gates and the quick consolidated self-hosted-core release gate pass.

## v21-yaml-unit-global — native layout parser emits an unbound `Unit` value
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: aef599a80 -->

**Status:** done (2026-07-11, `aef599a80`); found and confirmed by codex after symbolic extension dispatch
advanced `dsl-yaml-like.ssc` beyond the former numeric `PChar(10)` failure.

- **Real-harness repro:** `bin/ssc-standard run --native
  examples/dsl-yaml-like.ssc` fails identically on VM/ASM with `unbound global:
  Unit` after the imported parser `|` extension is selected correctly.
- **Expected:** source-level unit types/literals used by the imported layout
  parser lower to the portable unit value and never become a value-level global
  named `Unit`.
- **Root cause:** `parseMatchArm` reuses the general `skipTypeAnnot`, whose
  depth-zero stop set does not contain `=>` or a guard `if`. For
  `case ic: IndentContext => ic.currentLevel`, it consumes the arrow and body up
  to `}`; `parseArmBody` then sees no statements and synthesizes `uid Unit`.
- **Plan/done-when:** isolate the owning imported declaration in a multi-file
  typed-pattern fixture, specify a pattern-specific type boundary that preserves
  nested delimiters but stops at `=>`/guard, eliminate the false `Unit` global,
  and rerun the YAML-like example plus release gates.
- **Root cause/fix:** the general scanner intentionally accepts function arrows;
  a dedicated pattern scanner now stops at depth-zero `if`/`=>` while preserving
  nested delimiters. The exact import fixture passes VM/ASM, and YAML advances
  identically to the separately tracked parser-context arity gap.

## v21-yaml-parser-context-arity — YAML parser calls a nullary value with one argument
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 878474b8d -->

**Status:** done (2026-07-11, `878474b8d`); found and confirmed by codex after the typed-pattern boundary
fix advanced `dsl-yaml-like.ssc` beyond the false `Unit` global.

- **Real-harness repro:** `bin/ssc-standard run --native
  examples/dsl-yaml-like.ssc` and the same command with `--bytecode` now fail
  identically with `arity: 0 expected, 1 given` after the imported layout
  parser enters its first typed arm.
- **Expected:** the imported parser/context operation selects the intended
  callable definition and completes identically on VM/ASM.
- **Root cause:** assembled CoreIR proves `IndentContext_at` is correctly
  `lam 1`; the first failing call is `seqItem.block`. The extension starts with
  receiver `p`; `withIndent(n)` lowers as `lam 2` and `sameIndent` as `lam 1`,
  but the nested layout close after `sameIndent` clears
  `extensionParamsCell`. Later receiver-only members `deeperIndent`, `block`,
  and `line` therefore lower as `lam 0`, retain unbound `(global p)` bodies,
  and are invoked with one receiver argument.
- **Plan/done-when:** identify the exact callee and declaration ownership from
  the assembled CoreIR, add an import-boundary regression whose earlier member
  has a nested layout body and whose later receiver-only/parameterized members
  retain the receiver, distinguish the extension's real dedent from nested
  virtual closes, and rerun all parser DSLs plus release gates.
- **Fix/result:** extension-specific layout/brace frames now own the only token
  that clears receiver state; nested `parseOneStmt` calls cannot clear it.
  Layout/braced multi-file output is exact on VM/ASM, and YAML advances
  identically to the already tracked `PMapped/2` gap.

## v21-case-object-no-context-unbound — native frontend drops `case object`
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 500ba1668 -->

**Status:** done (2026-07-11, `500ba1668`, taxonomy `9411ebf0e`); found and
confirmed by codex after imported extension dispatch advanced
`dsl-json-parser.ssc` beyond the `PRegex/1` failure.

- **Real-harness repro:** `bin/ssc-standard run examples/dsl-json-parser.ssc`
  fails identically on VM/ASM with `unbound global: NoContext`; the declaration
  is `case object NoContext extends ParserContext` in `std/parsing/core.ssc`.
- **Expected:** a nullary case object lowers to one stable constructor value and
  is usable as the default parser context without a host provider.
- **Plan/done-when:** specify native `case object` parsing/lowering, add an
  isolated import-boundary VM/ASM regression, and rerun the JSON/YAML parser
  examples plus release gates.
- **Root cause/fix:** the top-level `case` branch recognized only `case class`,
  so `case object` was parsed as an expression and never entered the imported
  declaration closure. An explicit `caseobj` AST tag now survives module
  filtering and lowers to one `IrCtor(Name, Nil)` value definition.
- **Verified:** imported value/alias/pattern/equality print
  `Empty/empty/true` on VM/ASM; calculator becomes identical, JSON advances to
  the separately tracked `PMapped/2` gap, YAML remains at `Unit`. Every release
  gate and fresh conformance 11/11 pass.

## v21-symbolic-extension-infix-precedence — `Parser.|` becomes numeric `i.or`
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 4a336ddec -->

**Status:** done (2026-07-11, `4a336ddec`); found and confirmed by codex after
imported extension dispatch advanced calculator/YAML-like parser examples
beyond `PRegex/1`.

- **Real-harness repro:** `bin/ssc-standard run examples/dsl-calc-parser.ssc`
  and `dsl-yaml-like.ssc` fail identically on VM/ASM with `expected Int, got
  PChar(42)` / `PChar(10)`. Their `Parser[A] | Parser[A]` calls lower through
  the hard-coded numeric `i.or` path instead of imported extension `|`.
- **Expected:** a registered symbolic extension method on the receiver wins
  over primitive numeric lowering; ordinary integer bitwise OR stays `i.or`.
- **Plan/done-when:** make infix resolution consult durable extension identity
  before primitive dispatch, cover Parser-like and Int receivers on VM/ASM, and
  rerun both DSLs plus release gates.
- **Root cause/fix:** the self-hosted lowerer hard-coded `|` to `i.or` before
  consulting its durable extension registry. Registered `|` now carries its
  exact closure through `__arithExt__`; only `IntV/IntV` keeps primitive OR.
- **Verified:** the imported two-file fixture prints `a|b`, `a|b|c`, `7` on
  VM/ASM; a no-extension String misuse fails honestly; calculator/YAML advance
  to separately tracked `NoContext`/`Unit` gaps. Full release gates and fresh
  conformance 11/11 pass.

## v21-match-pregex-constructor — extension body captures the following top-level def
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: f7ff66a1f -->

**Status:** done (2026-07-11, `f7ff66a1f`, taxonomy `4feb715ea`);
found and confirmed by codex after the layout-object fix advanced all three
parser-combinator examples past their missing owned members.

- **Real-harness repro:** run
  `bin/ssc-standard run examples/dsl-calc-parser.ssc` after the
  `v21-layout-object-members-unprefixed` fix. VM and direct ASM both fail with
  `ssc: match: no arm for PRegex/1`; `dsl-json-parser.ssc` and
  `dsl-yaml-like.ssc` reach the same failure.
- **Expected:** the dedent/code-block boundary after an indented `extension`
  closes its member group. The following top-level `def runParser` has exactly
  its four declared parameters, so its existing `PRegex(pattern)` arm matches.
- **Root cause:** constructor metadata and the emitted `arm PRegex 1` are
  correct. The native parser represents an extension group as mutable
  `extensionParamsCell` state and currently keeps that state across the
  physical dedent/code-block boundary when the next statement is another
  `def`. It therefore prepends the stale receiver `p` to `runParser`, emitting
  `lam 5`; four-argument calls shift the scrutinee and surface the misleading
  match failure. After closing the layout boundary, `runParser` correctly
  becomes `lam 4`, exposing the companion cross-module defect: extension method
  names live only in the parser's transient `extensionMethodsCell`. Per-module
  parsing resets that cell before the combined module closure is lowered, so
  imported `Parser.map` becomes the built-in `_sel_map(PRegex, ...)`; that
  list/option helper has no `PRegex` arm and produces the same diagnostic.
- **Plan/done-when:** give an indented extension declaration a real layout
  boundary and persist extension start/end ownership in the parsed AST so the
  combined lowerer reconstructs imported extension dispatch deterministically.
  Clear receiver state at virtual close, preserve all members inside the body,
  and verify a following top-level function's arity. Add a multi-file VM/ASM
  regression and rerun all three examples; keep any later independent failures
  separately classified.
- **Resolution:** contextual receiver delimiters now open/close a virtual
  extension body, and explicit AST markers preserve imported member identity
  through module filtering into the combined lowerer. `runParser` is `lam 4`,
  imported `map` is `(global map)`, the two-file fixture is exact on VM/ASM,
  and every release gate plus fresh conformance 11/11 passes. All three DSLs
  leave `PRegex/1`; their symbolic-infix and `case object` gaps are tracked
  separately above.

## v21-layout-object-members-unprefixed — colon object loses its first member and owner prefix
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: afe902ec8 -->

**Status:** done (2026-07-11, `afe902ec8`, property completion
`b703a6bf0`); found and confirmed by codex while selecting the next
toolchain-independence runtime blocker after core-free YAML.

- **Real-harness repro:** run
  `bin/ssc-standard run examples/dsl-calc-parser.ssc`. The assembled native
  route fails with `unbound global: Parser_regex`. Its emitted CoreIR contains
  `PRegex` and unprefixed `regex`, but no `Parser_regex`; `Parser.char` also
  points at missing `Parser_char`.
- **Expected:** `object Parser:` owns all contiguous indented members, emits
  `Parser_<member>` definitions, and runs identically on native VM/direct ASM.
- **Root cause:** the layout pass recognizes a trailing colon only while inside
  a `trait` header. For `object Parser:` it emits no virtual braces;
  `skipToBrace` consumes the first member and the remaining definitions are
  parsed as unrelated top-level declarations, while selector lowering still
  treats `Parser` as a known object.
- **Plan/done-when:** make colon layout opening declaration-contextual for
  object headers without treating ordinary type-ascription colons as blocks;
  add focused braced/layout VM/ASM coverage, rerun the three parser-combinator
  rows, then update taxonomy only for examples that fully complete. Require
  native-entry, corpus/parity/taxonomy, and fresh affected conformance gates.
- **Resolution:** object and trait headers now share contextual layout state;
  owned methods and parameterless properties lower under one prefix for both
  UID selectors and sibling references. The exact layout/braced fixture passes
  VM/direct ASM, all three real DSLs leave the missing-global boundary, and all
  release gates plus fresh conformance 11/11 pass. Their next independent
  `PRegex/1` failure is tracked above.

## v2-swiftui-unsourced-malformed-seams — malformed nodes and events lose site provenance
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** render malformed `NativeUiElement` attrs/events/
  children or `NativeUiForKeyed`, dispatch a malformed/unsupported ordinary
  event, or pass a non-boolean `aria-disabled`/`required`. Several paths omit
  the available site source, while semantic booleans silently become false.
- **Expected:** every malformed/unsupported diagnostic names the owning lexical
  site; semantic boolean attributes accept only their frozen value forms.
- **Plan/done-when:** pass site/source through the renderer and action seams,
  validate semantic booleans before modifiers run, and add executable exact-
  source negative gates for element, keyed, event, aria-disabled, and required.
  Fourth review found the remaining shapes: invalid `aria-modal` must also be
  rejected, and every `NativeUiEvent` kind must validate its target/payload
  before mutation so increment-on-non-Int and malformed set/input/toggle cannot
  silently no-op or surface an unsourced runtime failure.
  Fifth review narrowed the remaining event shape to field 3: metadata must be
  a portable Map (as every constructor emits), and the target must be the full
  six-field `NativeUiSignal`, not merely a matching tag/kind string.
  Sixth review adds the adversarial boundary: signal kind must be one of the
  eight frozen values and every event metadata key must be String.
  Seventh review leaves one final full-shape case: field 5 must match its kind
  (`mutable` String-key Map; exact `NativeUiSignalMeta*` tag/arity for seed,
  computed, equality, hash, fetch, online, and persisted).
  Eighth review requires typed nested fields too: seed/equality sources and the
  fetch refresh/headers/phase/error fields are valid signals; fetch URL is
  String or signal. Recursive validation is cycle-safe by `SscFields` identity.

## v2-swift-session-sticky-callback-failure — one caught render error poisons the retained runtime
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by codex in the executable Swift keyed
rollback probe and announced to `@scalascript` /
`@nativeui-reviewer` in Rozum.

- **Real-harness repro:** invoke a keyed render closure that throws after a
  previously committed owner tree, catch the error, then reconcile the prior
  clean item set through the same `NativeUiSession`. Host signal/owner state
  rolls back, but `Machine.failure` remains sticky and the clean call rethrows
  the old error.
- **Expected:** failure is sticky across every nested subterm within initial
  program evaluation or one callback invocation, so placeholder Unit is never
  consumed. Once a post-evaluation caller catches that callback failure, the
  retained session is reusable and the last committed UI tree remains valid.
- **Plan/done-when:** distinguish initial evaluation from retained callback
  boundaries; consume/clear the failure only while returning it from a
  post-evaluation `invokeResult`/host-bound callback. Pin nested short-circuit
  plus same-session keyed rollback/recovery under real Swift.

## v2-swiftui-dependent-double-publish — one dependency write advances a computed cell twice
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by codex while implementing the generated
SwiftUI observation store, announced to `@scalascript` /
`@nativeui-reviewer` in Rozum.

- **Real-harness repro:** generate the NativeUi Apple sources, mount stable cells
  for a mutable signal and a computed/equality signal that reads it, then write
  one semantically different value. The draft `NativeUiStore.publish` calls
  `changed()` on the dependent and recursively publishes that same dependent,
  so its revision advances twice for one source transition.
- **Expected:** the source and each transitively dependent signal publish at
  most once per write transaction; a semantic-equal write publishes nothing,
  and cycles are bounded by the visited set.
- **Plan/done-when:** centralize the revision increment in one graph traversal,
  add a real generated-Swift runtime probe covering stable cell identity,
  semantic-equal suppression, direct/transitive invalidation, opaque
  subscribe/unsubscribe tokens, and obtain independent Rozum approval before
  landing the store slice.

## v2-swift-nativeui-descriptor-proof — debug root summary hides ABI field drift
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** `v2SwiftBackend/test` executes real generated packages,
  but `nativeUiDebug` prints only root version/tag/operation. A wrong descriptor
  field/default/source can therefore pass every current Swift assertion.
- **Expected/fix:** add a deterministic structural ABI digest/test seam and real
  Swift programs that pin shortened columns, fetch defaults, POST/id delete,
  raw sentinel, mobile CSS, and source provenance without flattening closures.
- **Done-when:** exact descriptor fields/defaults and source refs are asserted by
  real Swift execution and the reviewer approves; keep `fixed` until Sergiy confirms.
- **Fix/verified:** real AppCore probes inspect the exact table source, shortened
  column/options, POST/id delete, post request/payload, unsupported provenance,
  and trusted HTML; the reviewer approved the final diff.

## v2-swift-nativeui-duplicate-root-source — diagnostic omits both source refs
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** register `emit` and then `serve`; `registerRoot` stores
  both `NativeUiSourceRef` values but the fatal message renders only configs.
- **Expected/fix:** the bounded duplicate diagnostic names both operations and
  both source refs, with a negative generated-Swift process gate.
- **Done-when:** the exact diagnostic is pinned and reviewer-approved; keep
  `fixed` until Sergiy confirms.
- **Fix/verified:** the negative real-Swift process names both operations and
  exact file/line/column/source-operation refs.

## v2-swift-nativeui-mobile-css-regex — valid shipped override is rejected
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** pass the exact `mobileOverrideCss` grammar to generated
  `serve`; the Swift raw regex contains doubled backslashes and returns
  `NativeUiUnsupported` instead of the original root.
- **Expected/fix:** match the frozen JVM grammar exactly and reject a near miss;
  prove both branches through real Swift execution.
- **Done-when:** valid/invalid CSS gates pass and reviewer approves; keep `fixed`
  until Sergiy confirms.
- **Fix/verified:** the Swift raw regex now matches the JVM grammar; exact CSS
  retains the root and a one-character near miss becomes sourced Unsupported.

## v2-swift-nativeui-flat-name-detection — domain globals trigger UI mode
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** generate a domain `Program` defining and calling its
  own `signal` or `emit`; flat-name scanning emits `NativeUiHost.swift` and later
  fails because the user function registered no UI root.
- **Expected/fix:** select UI mode only from reserved, provenance-annotated ABI
  globals (or otherwise exclude user definitions); same-named domain definitions
  remain host-free and run under real Swift.
- **Done-when:** same-name domain regression is byte-for-byte host-free and green;
  keep `fixed` until Sergiy confirms.
- **Fix/verified:** mode detection honors reserved ABI provenance and excludes
  program definitions; a user `signal` remains a normal host-free Swift package.

## v2-swift-nativeui-evaluation-rollback — arbitrary failure cannot reuse session
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** begin a host, register provisional signal/root state,
  then trigger any runtime validation failure other than missing/duplicate root.
  AppCore uses `fatalError`, so no recoverable boundary calls `abort` and the same
  host cannot be proven clean on a second evaluation.
- **Expected/fix:** introduce a catchable Swift runtime failure boundary,
  abort-on-error, and same-host recovery without weakening bounded diagnostics.
- **Fresh review delta (Rozum 2026-07-11):** a native failure currently records
  `SscRuntimeFailure` and substitutes `Unit`, but an enclosing application/
  primitive/guard can inspect that placeholder and hit a second `fatalError`.
  Short-circuit every enclosing evaluation step as soon as failure is recorded;
  gate an invalid NativeUi call in outer-function position plus same-host reuse.
- **Done-when:** a real Swift test fails after provisional state, recovers on the
  same host, and extracts a clean root; keep `fixed` until Sergiy confirms.
- **Fix/verified:** extension failures are catchable and sticky; all enclosing
  evaluated subterms short-circuit. A nested invalid function position aborts,
  then the same host accepts a conflicting-default signal and clean root.

## v2-swift-nativeui-root-session-lifetime — extracted ABI loses callbacks/store
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 9ef73ac81 -->

**Status:** fixed (2026-07-11, `9ef73ac81`); found by `nativeui-reviewer` in Rozum during the
uncommitted Swift AppCore ABI-v1 review.

- **Real-harness repro:** `takeRoot()` calls `abort()` and clears signals while
  `Machine` is weakly captured by the host and deallocated when `evaluate`
  returns. Invoking an extracted signal/computed/keyed/user closure then sees an
  empty store or `native UI runtime released`.
- **Expected/fix:** expose `makeNativeUiRoot` backed by a retained evaluation
  session/store lifetime; successful handoff detaches provisional transaction
  bookkeeping without destroying live cells/Machine.
- **Fresh review delta (Rozum 2026-07-11):** successful extraction retains the
  signal map but replaces `emptyHeaders` with `Unit`; post-root render closures
  using short fetch/row-action arities then fail because extern defaults are not
  synthesized. Keep the root-scoped header signal until session disposal and
  invoke a short-arity action from an extracted render closure.
- **Done-when:** real Swift extracts a root and subsequently invokes signal get/
  set, computed, and user/render closures successfully; reviewer approves and
  the entry stays `fixed` until Sergiy confirms.
- **Fix/verified:** retained sessions own Machine/store until disposal; real
  post-root probes call mutable/computed/key/render closures and construct a
  short-arity fetch action through the still-live root `emptyHeaders` signal.

## v21-list-mkstring-capture — separator slot points at the source list
<!-- status: fixed
     lane: apparatus
     area: cli
     fixed-in: 23fddc6a2
     confirmed: no -->

**Status:** fixed (2026-07-11, `23fddc6a2`); found by codex when nested-pattern
fallback made `typed-data.ssc` execute through its Adults section; waiting for
Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, run
  `bin/ssc-standard run examples/typed-data.ssc` and inspect the line after
  `=== Adults ===`. It prints `AliceList(Alice, Charlie)Charlie` instead of
  `Alice, Charlie` on both VM and direct ASM.
- **Expected:** `_sel_mkString(List("Alice", "Charlie"), ", ")` inserts the
  supplied separator exactly once between adjacent elements.
- **Root cause:** inside the recursive `go` lambda and its `Cons/2` arm, the
  captured separator is de Bruijn local 4 and the original list is local 5;
  `selMkStringDef` reads local 5 as the separator.
- **Planned fix:** change the generated separator reference to local 4, add a
  direct multi-element regression on VM/ASM, rerun every native-entry/corpus/
  parity/taxonomy/conformance gate, and keep the entry `fixed` until Sergiy
  confirms.
- **Fix/verified:** the regression covers empty, singleton, multi-element, and
  numeric lists on both lanes; `typed-data.ssc` now prints `Alice, Charlie`.
  Native-entry, corpus, strict parity, both taxonomies, portable smokes, and
  fresh 11/11 conformance pass.

## v21-parity-mixed-scala-fence — native math exposes one-sided compiler surface
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: ee8467442
     confirmed: no -->

**Status:** fixed (2026-07-11, `ee8467442`); found by codex while implementing
TI-8.2d2i, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after staging the native `math` global, run
  `scripts/bc-parity-sweep --ssc bin/ssc-standard --only 'lang-split.ssc'
  --strict`. The VM exits zero after printing `Stub`-derived Scala-fence output,
  while direct ASM fails on the later mixed numeric `%` expression.
- **Expected:** a document whose front matter explicitly opts into
  `runScalaFences: true` is a compiler/tools surface on the compiler-free
  standard lane, even when it also contains `scalascript` fences. It must be
  source-classified before either backend runs, not compared as portable CoreIR.
- **Root cause:** the parity classifier skips backend-specific fences only when
  no standard block exists. It ignores the explicit mixed-fence execution flag,
  so the old shared `math` failure hid divergent unsupported Scala semantics.
- **Fix/verified:** `runScalaFences: true` is classified as `skipped-backend`,
  `lang-split.ssc` is pinned in the portable-gates smoke, and its stale
  runtime-taxonomy row is removed. Focused and full strict parity have zero
  one-sided rows; runtime/sentinel taxonomy and conformance gates pass.
- **Done-when:** classify `runScalaFences: true` as `skipped-backend`, pin
  `lang-split.ssc` in the portable-gates smoke, remove its stale runtime-taxonomy
  row, keep mismatch/one-sided counts at zero, and retain `fixed` until Sergiy
  confirms.

## v2-nativeui-rust-component-scope-proof — Rust adapter lacks a real compiler gate
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum while
reviewing the uncommitted NativeUi ABI-v1 migration.

- **Repro:** `RustGenWebToolkitTest` only string-matches the emitted generic
  `FnOnce` adapter; it never runs `cargo check`/`rustc` on a program calling
  `componentScope`.
- **Expected/fix:** compile a generated Rust package containing the generic
  identity call and retain the exact-return/exact-once contract.
- **Fix/verified:** the toolkit test now writes the generated crate and runs
  real `cargo run`; the generic `FnOnce` adapter compiles and prints `ok`.
- **Done-when:** a real Rust toolchain gate passes and its landed SHA is reported
  in Rozum; keep `fixed` until Sergiy confirms.

## v2-nativeui-transitive-native-provenance — childCtx rebind can replace user NativeFnV
<!-- status: fixed
     lane: apparatus
     area: plugin
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum while
reviewing the `componentScope` compatibility fix.

- **Repro:** every raw `childCtx` `NativeFnV` currently enters
  `rebindPluginNative`; a same-named user/case-constructor native can be
  replaced whenever the parent owns a plugin native of that name.
- **Expected/fix:** require child plugin provenance and identity with the child
  plugin binding before rebinding to the parent.
- **Fix/verified:** transitive rebinding now requires both the child's recorded
  plugin name and object identity with its live global; a same-named user case
  constructor remains callable through an exported facade.
- **Done-when:** component callbacks stay green, a same-name non-plugin
  regression is preserved, and the SHA is reported in Rozum.

## v2-nativeui-keyed-scope-ownership — JVM ABI lacks transactional keyed lifecycle
<!-- status: fixed
     lane: apparatus
     area: plugin
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum against the
frozen first JVM NativeUi gate.

- **Repro:** `UiNativePlugin` has only a scope stack; signals have no owner
  references/disposal, and `NativeUiForKeyed` static evaluation neither rejects
  duplicate keys nor commits/rolls back insert/move/delete ownership.
- **Expected/fix:** implement the frozen root/owner/scope/signal keys,
  provisional owner transactions, duplicate diagnostics, stable surviving
  scopes, deleted-key disposal, and rollback on render failure.
- **Fresh review delta (Rozum 2026-07-11):** `currentOwnerPath` still omits
  enclosing component scopes and lexical occurrence. Two component/repeated
  instances at the same `forKeyed` site/key can collide; add that collision
  repro plus shared-scope refcount/delete coverage before re-review.
- **Final retention delta (Rozum 2026-07-11):** strong component-result identity
  bindings survive keyed refresh/deletion even after `ownerScopes` is pruned,
  retaining old view→signal-closure→cell graphs. Prune bindings in the same
  owner transaction, restore on rollback, and gate bounded counts/deletion.
- **Fix/verified:** structural owners include component and site occurrence;
  insert/move/update/delete, duplicate, shared-scope refcounts, rollback, and
  25-refresh bounded-retention gates pass. The reviewer approved the result.
- **Done-when:** insert/move/update/delete/duplicate/rollback tests pass and the
  reviewer approves.

## v2-nativeui-descriptor-contract — public UI descriptors diverge from ABI-v1
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 1f3ca3962 -->

**Status:** fixed (2026-07-11, `1f3ca3962`); found by `nativeui-reviewer` in Rozum.

- **Repro:** shortened column arities are rejected; raw HTML does not require
  the exact sentinel; seed first-write can stay pristine; row-delete encodes
  DELETE/Unit instead of shipped POST/id payload; tagged signal dispatch omits
  `id`.
- **Expected/fix:** fill every public default, enforce the exact sentinel,
  dirty seed on the first user write, restore POST/id semantics, and register
  tag-qualified `id`.
- **Fix/verified:** every short column form, the two-attribute raw sentinel,
  first seed write, POST/id delete request, and tagged `id` are covered; the
  affected assembled conformance cases remain green.
- **Done-when:** focused tests plus `std-ui-jobpanel` and toolkit conformance pass.

## v21-layout-given-after-abstract-def — abstract return type consumes the next given
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 2a223d060
     confirmed: no -->

**Status:** fixed (2026-07-11, `2a223d060`); found by codex while implementing
TI-8.2d2h, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc installBin`, run
  `bin/ssc-standard run --native tests/fixtures/v21-native/layout-given-objects.ssc`.
  A `trait` with an abstract `def ...: String` immediately followed by
  `given intRender: Render[Int] with` leaves the first given body as an orphan
  block and fails with `ssc: unbound global: intRender` on both VM and direct
  ASM lanes.
- **Expected:** each named layout given is preserved as its own `given_obj`, its
  methods receive the static given prefix, sibling members resolve within that
  prefix, and both execution lanes agree.
- **Root cause:** `with` was not a layout opener, and a trait-header colon did
  not preserve a balanced body. The trait parser therefore returned at its
  first abstract `def`; generic return-type scanning then consumed the next
  given header until the newly inserted body brace.
- **Fix/verified:** `with` opens the existing generic layout path, while a
  narrow trait-header state makes only its trailing colon open a virtual block.
  Static member lowering prefixes bare sibling references after lexical lookup.
  Both a global `skipTypeAt` semicolon stop and a narrower `def` return-type
  stop were rejected because they regressed the real `std.http` fixture by
  exposing abstract class fields as top-level parser sentinels. The final
  fixture passes VM/direct ASM; `typeclass.ssc` reaches only `summon`; corpus,
  parity, taxonomy, native-entry, and fresh conformance are green.
- **Done-when:** the focused fixture passes VM/direct ASM, `typeclass.ssc`
  advances only to its independent `summon[...]` boundary, and the full native
  corpus, parity, taxonomy, native-entry, and affected conformance gates remain
  green. Keep `fixed` until Sergiy confirms.

## v21-parity-external-http-flake — live httpbin makes VM/ASM parity one-sided
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 2769bc479
     confirmed: no -->

**Status:** fixed (2026-07-11, `2769bc479`); found by codex while verifying
native extension dispatch, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** run `scripts/bc-parity-sweep --ssc bin/ssc-standard
  --report target/v21-standard-bc-parity-current.tsv`. The sweep executed
  `examples/v2-http-sql-demo.ssc` twice against live
  `https://httpbin.org/status/200`; the VM request timed out while the ASM run
  succeeded, producing forbidden `vm-error 1/0` despite identical compiler and
  runtime semantics.
- **Expected:** release parity must be deterministic and must not compare two
  independent public-network outcomes. The live HTTP demo belongs to the
  reviewed nondeterministic/server skip lane unless supplied a local fixture.
- **Observed root cause:** the parity skip classifier does not recognize this
  front-clean network example, so external availability can turn a skipped or
  symmetric row into a one-sided release failure.
- **Fix/verified:** `v2-http-sql-demo.ssc` is source-classified in the reviewed
  nondeterministic lane before either backend runs. The portable-gates smoke
  pins the row, and a fresh strict 195-row sweep is 12 identical / 57 both-fail /
  126 skipped with zero mismatch or one-sided error.
- **Done-when:** add a source-derived deterministic skip classification with a
  synthetic regression, rerun the real 195-row parity report to zero mismatch /
  one-sided rows, and keep the entry `fixed` until Sergiy confirms.

## v21-runtime-taxonomy-content-owner — content extern gaps assigned to module linker
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 6b736d078
     confirmed: no -->

**Status:** fixed (2026-07-11, `6b736d078`); found by codex while starting
TI-8.2d2 from the real `target/v21-runtime-taxonomy-current.tsv` report at
`df84e8acd`, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** run `scripts/v21-runtime-taxonomy`, then inspect
  `content-linked-namespaces.ssc`, `content-to-markdown.ssc`, and `content.ssc`.
  The manifest classifies them as `language-runtime/module-linker`, while
  `runtime/std/content.ssc` declares `contentModuleSection` and `contentSection`
  as `extern def` and the content plugin owns the `md` surface.
- **Expected:** all three rows are `standard-provider` blockers owned by the
  core-free content-provider migration. The total blocker ceiling remains 48.
- **Root cause:** the initial 60-row review grouped unbound imported names by
  their visible error without checking whether the imported declaration was
  pure ScalaScript or an extern/provider contract.
- **Fix/verified:** all three rows now belong to `standard-provider/content`;
  exact ceilings are 20 language-runtime / 25 standard-provider / 48 blockers.
  Synthetic smoke, real taxonomy, and fresh conformance 10/10 pass.
- **Done-when:** move the three rows to `standard-provider`, tighten exact
  category limits from 23/22 to 20 language-runtime / 25 standard-provider,
  update the recorded baseline, and rerun taxonomy smoke, the real report, and
  affected conformance. Keep `fixed` until Sergiy confirms the taxonomy.

## v21-standard-markdown-abi-packaging — slim launcher omits structural Markdown ABI class
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: 36d5ef3b6
     confirmed: no -->

**Status:** fixed (2026-07-11, `36d5ef3b6`); found by codex while verifying the
self-hosted Markdown frontend cutover, waiting for Sergiy confirmation before
`done`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run --native tests/fixtures/v21-native/sql-provider.ssc`.
  The full launcher works, but the assembled slim launcher throws
  `NoClassDefFoundError: scalascript/cli/NativeSourceMarkdown` while decoding
  `NativeCompilation/4`.
- **Expected:** every structural ABI class reachable from `RunNativeV2` is in
  `bin/lib/standard/ssc.jar`; the slim launcher validates Markdown and runs the
  program without a compatibility/tools JAR.
- **Root cause:** `build.sbt` builds the slim CLI with an explicit class-prefix
  allowlist. The new top-level `NativeSourceMarkdown` product was not added to
  that list, so only the full `ssc.jar` contained it.
- **Fix:** the class is now in the standard allowlist. The assembled
  `v21-native-plugin-boundary-smoke.sh` and Markdown frontend smoke are the
  faithful regressions; both the full and slim launchers pass.
- **Done-when:** both `bin/ssc-standard` and `bin/ssc` pass native Markdown/SQL,
  the slim JAR contains the ABI class, and the landed fix SHA is recorded here.
  Keep `fixed` until Sergiy confirms the assembled distribution.

## v2-swiftui-event-increment-overflow-readonly — ordinary event dispatch can trap or lose source
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: f062a9184 -->

**Status:** done (2026-07-11; fixed in `f062a9184`, `9ae1a130b`, and
`12fae35e7`; `nativeui-reviewer` confirmed with APPROVE in the `scalascript`
Rozum room).

- **Real-harness repro:** dispatch an ordinary `NativeUiEvent(kind=increment)`
  against a live Int signal holding `Int64.max`; the generated Swift performs
  trapping `value + delta`. A forged/live read-only signal can also pass the
  current kind-shape guard and fail later through an unsourced write path.
- **Expected:** event preflight requires the exact live target to be user-writable,
  checked addition reports overflow without trapping or mutation, and every
  malformed/read-only/overflow rejection includes the owning element site/source.
- **Plan/done-when:** harden `NativeUiActions.run`/Store target validation and add
  strict generated-Swift regressions for max-value increment, read-only target,
  zero mutation, no process trap, and exact source diagnostics. Validation and
  mutation must bind to the same current Host cell: a forged otherwise-valid
  wrapper with a marker write closure cannot execute that closure or resurrect
  a disposed tombstone. Authenticated reads also use the current Host cell's
  `dynamicRead` (not a forged closure or stale `current` default), preserving
  pristine seed-source semantics before the event makes the seed dirty. This is outside
  the already approved async fetch/action slice; keep `fixed` until the Rozum
  reporter confirms it.

## v2-swiftui-surviving-owner-action-task-leak — removed action can finish under a surviving keyed owner
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: 5c0b38ad9 -->

**Status:** done (2026-07-11; fixed in `5c0b38ad9`, `068e8b62d`, and
`03f2f1fcf`; `nativeui-reviewer` confirmed with final APPROVE in the
`scalascript` Rozum room).

- **Real-harness repro:** mount a keyed row containing a fetch action, start its
  controllable `URLProtocol` request, then reconcile the same key to a row that
  no longer contains that action. The Host disposes the old owner-owned
  phase/error signal keys, but the Store only cancels task owners returned as
  deleted keyed paths. Because the keyed owner itself survives, the request can
  remain current until a non-guaranteed SwiftUI `onDisappear`; a late 2xx may
  commit capture/clear/effects before the eventual status write detects the
  disposed signals.
- **Expected/root-cause direction:** bind every active action task generation to
  the exact phase/error signal capability that authorized it. Disposing either
  key during Host reconciliation must synchronously invalidate and cancel that
  task, even while its containing keyed owner survives; late completion must
  become inert before any user mutation.
- **Plan/done-when:** return or publish exact disposed action status keys through
  the Host/Session reconciliation seam, cancel matching Store tasks without
  view lifecycle callbacks, and add generated strict-concurrency Swift gates for
  same-key action removal/replacement, stopped transport, no late 2xx mutation,
  and a freshly idle/error-empty action after reinsertion. Explicit cancellation
  must reset a unique/last task to idle+empty after generation invalidation while
  preserving loading if another mounted task shares the exact phase/error
  capability. A delayed lifecycle callback holding an obsolete action descriptor
  must fail exact `validActionStatus` capability validation before it can cancel
  or reset a fresh replacement at the same structural owner. Navigation and
  `openJson` share one preflight/response URL policy: http/https need a non-empty
  host, mailto a non-empty target, and unsafe or hostless templates cannot start
  transport. Keep `fixed` until
  the Rozum reporter confirms the regression.

## v2-bigint-dynamic-arith-money — std/money allocation feeds Unit to an if condition on v2
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: ff3a52eba
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** fixed (2026-07-10, `ff3a52eba`); waiting for reporter confirmation
before `done`. Found by codex in assembled conformance while implementing
`v2-portable-decimal-money-effects`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `tests/conformance/run.sh --only 'money-*,effect-*' --no-memo`.
  `money-portable-v2` prints five correct exact-Decimal rows, then exits 1 in
  `std/money.ssc` with `if: condition not Bool: ()` while evaluating
  `BigInt(i) < remainder` inside `allocate`.
- **Expected:** bridge-emitted dynamic arithmetic on `BigInt` implements the
  same exact arithmetic/comparison contract as named `big.*` primitives, so
  Money allocation returns `$0.02, $0.02, $0.01` instead of host `Unit`.
- **Root cause:** `Prims.resolve` implements named `big.add`/`big.lt` primitives,
  but bridge code emits `__arith__`; `arithRest` has no `BigV`/`BigV` or mixed
  `BigV`/`IntV` arms, so relational operators fall through to the generic
  plugin/declaration fallback and become `UnitV`.
- **Fix:** dynamic `__arith__` now delegates `BigV`/`BigV` and mixed
  `BigV`/`IntV` operations to exact BigInt arithmetic/comparison semantics.
- **Verified:** the focused frontend-bridge regression passed, `installBin`
  assembled the real distribution, and
  `tests/conformance/run.sh --only 'money-*,effect-*,effects' --no-memo`
  passed 6/6 including unchanged `std/money.allocate` behavior.

## v21-standard-index-vm-asm-divergence — index example fails VM and succeeds with malformed ASM output
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 86a2de03a
     confirmed: no -->

**Status:** fixed (2026-07-10, `86a2de03a`); waiting for human confirmation
before `done`.

- **Found by:** codex from `scripts/bc-parity-sweep --ssc bin/ssc-standard`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc-standard run examples/index.ssc` and the same command with
  `--bytecode`. VM prints only `ScalaScript 0.1 is running!` then fails
  `arity: 1 expected, 2 given`; direct ASM exits zero but prints the malformed
  second line `)}` instead of `Squares: 1, 4, 9, 16, 25`.
- **Expected:** both lanes execute the checked program, print the same complete
  two-line result, and either both reject or both succeed; malformed output may
  not count as bytecode success.
- **Root-cause direction:** inspect lowering/dispatch for the for-yield result
  and `mkString(", ")`; compare VM `App` arity with the generated direct-ASM
  dispatch path before changing the example.
- **Done-when:** assembled VM/ASM output is byte-identical and semantically
  correct, the focused parity row is `identical`, and affected conformance is
  green.
- **Root cause:** the self-hosted outer string scanner stopped at the quote in
  `${nums.mkString(", ")}`. Its interpolation splitter only understood a bare
  identifier and reclassified the remaining selector/call text as string
  literals. VM then failed on malformed CoreIR while ASM stringified it into a
  meaningless successful `)}`.
- **Fix:** the lexer now balances braced interpolation bodies and nested quoted
  strings, and the interpolation builder parses the complete inner expression
  through the normal expression grammar.
- **Verified:** `index.ssc` and a focused two-expression fixture print the exact
  expected text byte-for-byte on assembled VM/direct ASM; standard parity is
  1 identical/0 errors, native-entry and standard-tier smokes pass, KC12/KC13
  simple interpolation remains green, and affected conformance is 8/8.

## v21-standard-direct-asm-recursion-stack — direct ASM lacks the VM recursion trampoline
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 3153fb2db
     confirmed: no -->

**Status:** fixed (2026-07-10, `3153fb2db`); waiting for human confirmation
before `done`.

- **Found by:** codex from the standard VM/ASM parity report.
- **Real-harness repro:** `bin/ssc-standard run examples/recursion.ssc` prints
  all 13 expected rows, including 100,000-call self/mutual-tail recursion;
  `bin/ssc-standard run --bytecode examples/recursion.ssc` prints only the
  first four rows and then throws `StackOverflowError` through
  `ssc.gen.Entry.lam$77 -> ssc.Emit.letrec -> ssc.Runtime.run`.
- **Expected:** direct ASM implements the checked CoreIR recursion semantics and
  the same stack-safety contract as the VM, including self and mutual tail
  calls.
- **Root-cause direction:** retain the current direct emitter but route tail
  calls in generated `LetRec` groups through a bounded trampoline/loop; do not
  fall back to a compiler or the VM execution backend.
- **Done-when:** the full example is byte-identical on VM/ASM with a small JVM
  stack, focused recursion/TCO tests cover self/mutual groups, and affected
  conformance is green.
- **Root cause:** top-level recursive targets already had self-loop/mutual
  `Bounce` lowering, but local `LetRec` lambdas were emitted as anonymous
  methods without peer identity. Their tail `App(Local(...))` therefore entered
  `Emit.app -> Runtime.run` recursively. The first failure was `_sel_to`, not the
  user-level top-level `length` function.
- **Fix:** each local recursion body carries environment-relative peer
  method/arity metadata. Tail calls preserve `captured ++ tied-group`, replace
  the current argument suffix, and return a trampoline bounce; generic local
  closure invocation unrolls it iteratively.
- **Verified:** focused bytecode tests pass arithmetic/non-tail recursion plus
  100,000-call local self/mutual TCO (3/3). The real `recursion.ssc` produces all
  13 expected rows identically through VM, in-memory ASM, and `build-jvm` JAR at
  `-Xss256k`; strict focused parity is 1 identical/0 errors; native-entry,
  standard, slim, JRE-module, artifact, and affected conformance gates pass.

## v21-standard-ui-fetch-json-vm-arity — native VM rejects a five-argument UI helper accepted by ASM
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: d6b9ae9ce
     confirmed: no -->

**Status:** fixed (2026-07-10, `d6b9ae9ce`); waiting for human confirmation
before `done`.

- **Found by:** codex from the TI-8.2 standard corpus sweep.
- **Real-harness repro:** `bin/ssc-standard run examples/ui-fetch-json.ssc`
  prints the structured body then fails `arity: 3 expected, 5 given`; the same
  command with `--bytecode` prints `fetch-json:ok` and exits zero.
- **Expected:** the public five-argument `fetchJsonAction` helper has identical
  checked arity and behavior on VM and direct ASM.
- **Root cause:** the self-hosted type skipper balanced `[...]` but not nested
  `(...)`. In a multiline parameter list, the inner `)` of `() => String`
  prematurely ended the outer list, so imported fetch helpers lost parameters
  and their bodies. The VM then honestly rejected the malformed call while
  direct ASM omitted the VM's closure-arity check and falsely succeeded. After
  both compiler defects were corrected, the standard UI provider also needed
  explicit core-free `fetchUrlSignal`, `fetchAction`, and `emptyHeaders`
  declarative values instead of a v1 fallback.
- **Fix:** balance parentheses while skipping function types, enforce closure
  arity in `Emit.app`, and construct readable static fetch signals/actions in
  the native UI provider while leaving actual network execution to an emitted
  browser runtime.
- **Verified:** both assembled lanes print the identical structured body plus
  `fetch-json:ok`; focused strict parity is 1 identical/0 errors. A multiline
  function-parameter fixture, bytecode arity negative test, UI provider test,
  native-entry, standard, slim, JRE-module, artifact, JSON-cutover, and affected
  conformance gates all pass.

## v21-native-front-missing-ui-table-import — corpus import closure references a deleted std module
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d4513cb8a
     confirmed: no -->

**Status:** fixed (2026-07-10, `d4513cb8a`, diagnostic gate
`ac441ef62`); waiting for human confirmation before `done`.

- **Found by:** codex from `scripts/native-front-corpus`.
- **Real-harness repro:** compiling `examples/graph-fullstack-rdf.ssc` aborts
  with `NoSuchFileException: v1/runtime/std/ui/table.ssc`; the document imports
  `table`, `tableHeader`, `tableRow`, and `tableCell` from that path, but the
  staged std tree has no such module.
- **Expected:** every checked-in example import resolves deterministically, or
  a removed API is migrated with an explicit source-level diagnostic rather
  than a host filesystem exception.
- **Root cause:** the example retained the removed `std/ui/table.ssc` path and
  old `tableHeader`/`tableCell` wrappers after the toolkit consolidated tables
  under `std/ui/data.ssc`.
- **Fix:** import `tableCol`/`tableRow`/`table` from the current module and build
  the three columns/row cells through that API; import resolution remains strict.
- **Verified:** the real row is frontend/checker OK and any remaining unsupported
  backend-specific surface is a filename-bearing bounded sentinel diagnostic.
  Native-entry rejects `NoSuchFileException`/host matcher leakage explicitly;
  the full frontend corpus has 194 successes, 0 host errors, 0 timeouts, and 1
  non-code document.

## v21-standard-h2-java-compiler-edge — slim gate misses compiler classes inside dependency JARs
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: e4cd55b36
     confirmed: no
     gate: tests/e2e/v21-slim-distribution-gate.sh -->

**Status:** fixed (2026-07-10, `e4cd55b36`); waiting for human confirmation
before `done`.

- **Found by:** codex while implementing the TI-8 JRE-shaped module gate.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `jdeps --multi-release base --ignore-missing-deps -verbose:class
  bin/lib/standard/jars/h2-2.2.224.jar`. The staged standard H2 JAR reports
  `org.h2.util.SourceCompiler -> javax.tools.*` and a `java.compiler` module
  dependency even though `tests/e2e/v21-slim-distribution-gate.sh` reports zero
  forbidden references.
- **Root cause:** the TI-7 static gate starts recursive `jdeps` only from the
  class-filtered standard CLI JAR. Service-loaded providers and their JDBC
  drivers are not statically reachable from that entry, so dependency JARs are
  not scanned as roots. `build-jvm` already excludes H2's optional source
  compiler classes, but `installBin` copies the complete H2 JAR into the
  standard tier.
- **Expected:** every standard-tier dependency JAR is a scan root and the
  complete staged tier has no class/reference/module edge to `javax.tools`,
  `java.compiler`, or `jdk.compiler`; normal H2 SQL remains functional on a
  module-limited JRE-shaped runtime.
- **Fix direction:** stage a deterministic H2 runtime-only JAR in
  `lib/standard/jars` with the optional `org/h2/util/SourceCompiler*` family
  removed, retain the unmodified driver only in the tools tier, and strengthen
  the slim/JRE gates to inspect every standard dependency root.
- **Done-when:** full standard-tier `jdeps` reports no compiler module, the
  compiler modules are unresolvable under `java --limit-modules`, native H2 SQL
  passes on VM/direct ASM and as a generated JAR, and affected conformance is
  green.
- **Fix:** `installBin` deterministically repacks only the standard-tier H2 JAR
  and omits its eight optional `SourceCompiler*` classes; the tools-tier copy is
  unchanged. Slim and JRE gates merge every standard dependency into a
  scan-only archive so ServiceLoader/JDBC classes are static roots.
- **Verified:** derived runtime modules exclude `java.compiler`/`jdk.compiler`
  and both fail `--describe-module` under the limit; VM, direct ASM, FS/OS,
  JSON, HTTP, SQL, UI, State, and generated SQL JAR pass. Strengthened slim and
  core-dependency gates pass. At the H2 fix boundary the artifact SHA remained
  `1d078c3ffe330eae72a809f98794333c123d715bbf19012fbdc4f0c686715173`;
  subsequent self-hosted JSON and local-recursion runtime changes intentionally
  advanced the reproducible baseline. Affected conformance is 8/8.

## ui-fetch-get-offline-rejection — managed SPA GET rejects as an unhandled promise offline
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: a0d45ad44 -->

**Status:** done (2026-07-10, fix `a0d45ad44`, reporter confirmation in busi
`77399254`).

- **Found by:** codex while running busi Gate 1 canonical `/app` offline QA.
- **Real-harness repro:** emit and serve busi `src/v2/clients/ssc/app.ssc`, load
  paired `/app` online, stop the local hub, then reload from the installed PWA
  cache. The shell and local facts remain usable, but each mounted
  `fetchUrlSignal` logs an app-origin `TypeError: Failed to fetch`; hidden
  routes are mounted too, so one outage produces repeated console errors.
- **Generated root cause:** `_mountFetchGet` emits
  `fetch(...).then(responseText).then(setSignal)` without a rejection handler.
  A network failure therefore escapes as an unhandled promise rejection even
  though absence of the optional hub is a normal offline state.
- **Expected:** a rejected managed GET keeps its last-good signal value, emits
  no unhandled rejection, and remains eligible for the next tick-driven fetch.
  HTTP response semantics are unchanged.
- **Fix direction:** change the owning custom-SPA runtime generator, not emitted
  busi HTML; add a faithful generated-runtime test with a rejected `fetch` and
  a subsequent successful refresh.
- **Done-when:** focused frontend tests, assembled custom-SPA emission, and
  affected conformance are green; busi rebuild confirms a clean app-origin
  offline console.
- **Fix:** the shared `_mountFetchGet` promise chain now consumes transport and
  response-body rejection without writing the signal. Tick and reactive-URL
  subscriptions stay installed, so a later refresh can recover.
- **Verified:** real `JsRuntimeSignals` Node regression 1/1 plus existing
  `FetchUrlSignalToTest` 1/1; assembled `emit-spa --frontend custom` contains
  the rejection boundary; focused `std-ui-jobpanel`, `tkv2-busi-home`, and
  `tkv2-offline` conformance passes 3/3 on INT and JS.
- **Reporter confirmation:** busi rebuilt and published its canonical owner SPA
  with this runtime, loaded an existing installed profile online, stopped the
  hub, and reloaded cached `/app`. Last-good/local facts remained visible and
  the browser console contained zero app-origin `Failed to fetch` entries; the
  only remaining URL-less inspector-frame error was unrelated.

## v21-build-jvm-import-source-identity-gap — artifact metadata omits resolved imports
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: e4f16baaf
     confirmed: no -->

**Status:** fixed (2026-07-10, `e4f16baaf`); waiting for human confirmation
before `done`.

- **Found by:** codex during the direct-ASM artifact source-map review.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run `bin/ssc
  build-jvm tests/fixtures/v21-native/relative-main.ssc -o /tmp/import.jar`
  and inspect `META-INF/scalascript/artifact.properties`. The program prints
  `42`, proving `relative-helper.ssc` was linked, but `source.count=1` and only
  the explicit root identity is recorded.
- **Expected:** every source whose declarations contribute to the linked
  checked `Program` has a deterministic name/hash identity; debug SMAP includes
  the same closure while still guaranteeing that every explicit root appears.
- **Root cause:** `RunNativeV2.compile` retained only canonical command-line
  roots after the self-hosted loader resolved imports internally. The artifact
  writer therefore had no import closure to hash or map.
- **Fix direction:** mirror the native loader's standalone-link DFS in a small
  JDK-only host resolver, preserve explicit-root order plus deterministic import
  order, and pass separate `roots` and `sources` collections to artifact debug
  and metadata generation. Do not load the v1 parser/Scalameta.
- **Done-when:** the relative-import JAR metadata and SMAP name both
  `relative-main.ssc` and `relative-helper.ssc`, the helper hash changes when
  its source changes, two builds remain byte-identical, and the assembled
  artifact/conformance gates stay green.
- **Fix:** a JDK-only standalone-link resolver mirrors the self-hosted loader's
  DFS/postorder and retains stable display paths for explicit roots plus the
  linked import closure. Artifact metadata hashes those units, and the lexical
  fenced-source scanner assigns the same units to the SMAP file table.
- **Verified:** relative-import metadata contains helper + root with the
  helper's exact SHA-256; `javap -l -v` names both in SMAP; the runtime prints
  `42`; two base builds remain byte-identical; artifact/conformance gates pass.

## v2-frontendbridge-sqlite-timeout — SQLite conformance exceeds the 15-second bridge-test limit
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: b55811bf9
     confirmed: no
     gate: tests/conformance/v2-db-url-scheme-not-jdbc.ssc -->

**Status:** fixed (2026-07-12, `b55811bf9`), awaiting Sergiy confirmation.

- **Found by:** codex while running the broad post-TI-6.1 regression suite.
- **Real-harness repro:** `scripts/sbtc "v2FrontendBridge/test"`; all other 151
  executed tests pass, but `v2-conformance: v2-db-url-scheme-not-jdbc` returns
  `(timeout)` instead of `1` after the suite's 15-second `Await` bound.
- **Expected:** `tests/conformance/v2-db-url-scheme-not-jdbc.ssc` opens its
  `sqlite::memory:` database and prints `1` within the normal test bound.
- **Root cause:** sqlite-jdbc was present and Hikari was not involved. A live
  blocked-thread trace showed Xerial's first connection in
  `SQLiteJDBCLoader.cleanup → Files.list → readdir0`, scanning every entry in
  the large shared macOS `java.io.tmpdir` before extracting its native library.
- **Fix:** SQLite registration assigns Xerial a private, per-process native
  temp directory unless the host explicitly sets `org.sqlite.tmpdir`. The
  focused regression asserts that isolation and the real conformance fixture
  still performs an actual in-memory JDBC round trip.
- **Verified:** the named bridge case fell from timeout to 1.7 seconds;
  `v2PluginBridge/test` is 32/32 and affected conformance is 1/1. The broad
  bridge suite is 195/196 with this row green; the sole `tkv2-pwa`
  `backend=jdk`/`backend=fast` banner red is the separately tracked provider
  selection issue.

## ssr-forsignal-duplicate-attrs - SSR `ForSignal` fallback duplicated static attrs
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: bb5342f08
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** fixed (2026-07-10, source fix `bb5342f08`; regression
`4291a7239`); waiting for human confirmation before `done`.

- **Found by:** codex during `tkv2-raw-html`.
- **Repro:** before `bb5342f08`, render
  `View.ForSignal[String](items = new ReactiveSignalList[String]("rows", Seq("a",
  "b")), tag = "li", attrs = Map("class" -> AttrValue.Str("row"),
  "data-id" -> AttrValue.Str("x")), itemTemplate = None)` through
  `Ssr.renderToHtml`.
- **Observed failure:** each fallback `<li>` serialized the same static attrs
  twice, producing duplicate `class`/`data-id` attributes for every row.
- **Expected:** fallback SSR should serialize the supplied attrs once per
  repeated item.
- **Root cause:** the `View.ForSignal(..., itemTemplate = None)` fallback
  branch called `writeAttrs(sb, attrs)` twice before closing the start tag.
- **Fix:** the duplicate call was removed as part of the raw-html SSR renderer
  patch in `bb5342f08`; `4291a7239` adds the focused regression that counts the
  serialized attrs for two repeated rows.
- **Verified:** `scripts/sbtc "frontendToolkit/testOnly
  scalascript.frontend.toolkit.SsrTest"` passes 33/33; `scripts/sbtc
  "installBin"` passes; `tests/conformance/run.sh --only 'tkv2-raw-html'
  --no-memo` passes 1/1 after staging a fresh CLI in the worktree.

## v2-backend-check-ssc1c-wrapper-app-lit — `fixed` (2026-07-09)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex while verifying the `v2-source-jvm-recursion-fib-perf`
  source-backend slice.
- **Repro:** from a current ScalaScript worktree, run
  `v2/backend/check.sh bool` or `v2/backend/check.sh mutual-recursion`.
- **Observed failure:** both backend-check fixture rows fail before any JVM/JS/Rust
  source generator runs:
  `FAIL bool-predicate: run-ir failed` and
  `FAIL mutual-recursion: run-ir failed`.
- **Detailed bool repro:** generate the backend-check `bool-predicate` ssc1c
  wrapper (`def main(): Unit = { println(workload(42L)); () }`) and run the
  resulting CoreIR through `run-ir`. The emitted IR contains
  `(app (lit (int 1000)) (lam 0 ...))` inside the workload loop condition, and
  `run-ir` aborts with `java.lang.RuntimeException: app: not a function: 1000`.
- **Expected:** the ssc1c wrapper fixtures for `bool-predicate` and
  `mutual-recursion` should produce valid CoreIR so `v2/backend/check.sh bool`
  and `v2/backend/check.sh mutual-recursion` can be used as source-backend
  parity gates again.
- **Impact:** source-backend work touching `v2/backend/*` cannot currently use
  those two generated ssc1c rows as acceptance gates. CoreIR-only backend
  fixtures such as `tco` and `letrec` remain usable and green for the current
  JVM source-backend recursion slice.
- **Notes:** this is independent of `v2/backend/jvm/JvmBackend.scala`; the
  failure happens in the VM `run-ir` oracle before source generation. It may be
  another ssc1c precedence/lowering issue around the synthetic main wrapper or
  the `until`/loop desugaring in the corpus fixture. Track/fix as its own
  ssc1c/backend-check task rather than folding it into JVM source codegen work.
- **Root cause:** `v2/scripts/indent2braces.py` converted `while i < 1000 do`
  to `while i < 1000 { ... }`. `v2/lib/ssc1-front.ssc0` expects
  `while (cond) body`; without parentheses, the condition parser consumed the
  following block as an argument to literal `1000`, producing the invalid
  app-lit CoreIR.
- **Fix:** `043039b61` parenthesizes converted while conditions, e.g.
  `while (i < 1000) { ... }`, preserving the corpus workloads and all source
  generators.
- **Verified:** `v2/backend/check.sh bool`, `v2/backend/check.sh
  mutual-recursion`, `v2/backend/check.sh tco`, `v2/backend/check.sh letrec`,
  `scripts/sbtc "installBin"`, affected conformance
  `tests/conformance/run.sh --only 'mutual-recursion,variables' --no-memo`
  (2/2 across INT/JS/JVM), and `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## std-auth-webauthn-signature-drift — `fixed` (2026-07-09)
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: tests/conformance/webauthn-server-verify.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, during `tkv2-webauthn` spec/implementation prep.
- **Repro:** compare `v1/runtime/std/auth.ssc` declarations with the existing
  implementations in `v1/runtime/std/auth-plugin/.../AuthIntrinsics.scala` and
  `v1/runtime/backend/js/.../JsRuntimeWebAuthn.scala`, or run the existing
  `tests/conformance/webauthn-server-verify.ssc` / `examples/webauthn-demo.ssc`
  call shapes. The implementations and shipped examples use:
  `webauthnStoreFind(userId, credentialId)`,
  `webauthnUpdateSignCount(userId, credentialId, newSignCount)`,
  `webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64, expectedOrigin)`,
  and `webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64,
  signatureB64, credentialIdB64, expectedOrigin)`.
- **Observed failure:** the public std declarations still document older/wrong
  arities and return types for those four WebAuthn helpers, so new user code
  can be guided into calls the runtime does not implement.
- **Impact:** WebAuthn is production-sensitive; browser-client helpers would be
  confusing if the adjacent server verifier declarations stay stale.
- **Fix direction:** update `std/auth.ssc` declarations to the runtime-backed
  arities/return shapes without changing the verifier semantics, then keep
  `webauthn-server-verify` green on INT+JS.
- **Root cause:** `std/auth.ssc` lagged behind the already-shipped JVM/JS
  WebAuthn verifier/store implementations; examples and runtime code had moved
  to user-scoped credential lookup, boolean sign-count updates, and verifier
  inputs split into browser response fields.
- **Fixed in:** `e61a89b4c` (`feat: add tkv2 webauthn browser actions`).
- **Gates:** `scripts/sbtc "backendJs/compile; frontendPlugin/compile; backendInterpreter/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsRuntimeWebAuthnClientTest scalascript.JsGenStdImportTest"` (43 tests);
  `tests/conformance/run.sh --only 'tkv2-webauthn,webauthn-server-verify' --no-memo`
  (2/2 cases, INT+JS pass); `emit-spa --frontend custom` smoke for
  `examples/frontend/webauthn-toolkit-demo/webauthn-toolkit-demo.ssc`.
- **Done-when:** the declaration file, examples, runtime intrinsics, and
  conformance call shapes agree; fixed SHA and gates are recorded here.

## v2-ssc0-rust-float-literal-emits-int — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `p4-rust-wasm-lanes` baseline.
- **Repro:** run `./v2/conformance/check.sh` and inspect the diagnostics log.
- **Observed failure:** several Rust target rows fail at `rustc` with
  `error[E0308]: mismatched types`, because the self-hosted Rust backend emits
  collapsed whole-float literals such as `V::Fl(2)` / `V::Fl(1)` after
  `#f->str`, but Rust's `V::Fl` variant requires `f64`. Representative rows:
  `numops Rust`, `numcmp Rust`, `div Rust`, `float math Rust`, `mathx* Rust`,
  `letrec poly Rust`, `dict-passing Rust`, `dict ord Rust`.
- **Impact:** real Rust target compilation is broken for typed numeric programs
  that contain whole-valued float constants. WASM inherits this through
  `ssc0-wasm` because it compiles the same Rust source to `wasm32-wasip1`.
- **Fix direction:** normalize generated Rust float literals in
  `v2/lib/backend-rust-gen.ssc0`: whole finite values need a Rust float suffix
  or decimal (`2.0`), while `nan`/`inf`/`-inf` should map to valid Rust
  constants if they surface.
- **Done-when:** the Rust rows above compile and pass in
  `./v2/conformance/check.sh`, and the WASM quicksort/TCO gate remains green.
- **FIXED (2026-07-08, `84d7ac77f`):** the self-hosted Rust backend normalizes
  `IrFloat` literals after `#f->str`: whole finite values get a decimal
  (`2.0`), existing decimal/exponent spellings are preserved, and
  `nan`/`inf`/`-inf` map to Rust `f64` constants.
- **Verified:** full `./v2/conformance/check.sh`; Rust numeric rows including
  `hm-numops`, `hm-numcmp`, `hm-div`, mathx, rounding, dict-passing, and
  method-poly/self compile and pass; WASM quicksort and 1e6-tail-call TCO remain
  green.

## root-test-cli-spark-submit-dry-run-deps — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` focused
  `scripts/sbtc "cli/test"` after the Electron fork-exit blocker was fixed.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`.
- **Observed failure:** `SubmitCommandTest` reports two failed assertions:
  `--dry-run prints package + submit argv with default master` no longer includes
  `org.apache.spark::spark-core:4.0.0`, and `--spark-version threads through to
  both deps` no longer includes `spark-core:3.5.1`.
- **Impact:** the CLI aggregate remains red; Spark submit dry-run output may have
  intentionally moved dependency information or stopped emitting it. The test and
  command contract must agree before root `test` can be a production gate.
- **Fix direction:** inspect `submit` dry-run output generation and the current
  intended Spark dependency surface. If deps are intentionally no longer present in
  the package argv, update the test to assert the current contract; otherwise
  restore dependency lines/options.
- **Done-when:** focused `SubmitCommandTest` is green and full `cli/test` no
  longer reports this suite.
- **FIXED (2026-07-08, `cea0c3aed`):** the dry-run contract is the generated
  package source, not inline `spark-submit --dep` argv. `SubmitCommandTest` now
  parses the `# source:` path from dry-run output and asserts Spark dependency
  directives in that generated source.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-cli-toolkit-electron-duplicate-seqmap — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` focused
  `scripts/sbtc "cli/test"` after the Electron fork-exit blocker was fixed.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`.
- **Observed failure:** `ToolkitElectronSmokeTest` case
  `toolkit-demo Electron bundle renders, routes Add, and persists after restart`
  fails with renderer error
  `Uncaught SyntaxError: Identifier '_seqMap' has already been declared`; the
  smoke then reports `SMOKE_FAIL initial render missing`.
- **Impact:** toolkit Electron smoke is a real browser/Electron bundle execution
  gate, not just a string assertion. Duplicate JS helper declarations in emitted
  bundles can blank desktop UI startup.
- **Fix direction:** reproduce focused, inspect the generated Electron bundle, and
  deduplicate or scope duplicate helper preamble emission (`_seqMap`) so runtime
  helpers are emitted once per bundle.
- **Done-when:** focused `ToolkitElectronSmokeTest` is green and full `cli/test`
  no longer reports this suite.
- **FIXED (2026-07-08, `cea0c3aed`):** the renderer bundle had a broader
  strict-mode duplicate/binding chain: collection sequencing helpers existed in
  both `core-collections.mjs` and `async.mjs`; session HMAC reused the core
  crypto helper name; the typed JSON facade could be included twice; browser
  patch assignments lacked stable bindings; and `_ssc_frontend_name` was split
  between ws/server and injected frontend code. The runtime now has a single
  collection helper source, repeat-safe typed JSON facade bindings, distinct
  session HMAC name, and a base frontend-name binding.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-cli-fork-exit-after-green — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after bounded sbt Test concurrency was added.
- **Repro observed in root gate:** `cli / Test / test` reported
  `Total number of tests run: 488`, `Tests: succeeded 488, failed 0, canceled 19`,
  and `All tests passed.`, then sbt still failed the task with
  `Error during tests: Running java with options ... sbt.ForkMain ... failed with exit code 1`.
- **Impact:** the CLI aggregate is red even though ScalaTest reports no failing
  test. Root `test` cannot be treated as a production gate until the forked JVM
  exits cleanly or the late exit is traced to a real failing resource cleanup path.
- **Fix direction:** reproduce with focused `cli/testOnly` suites first, starting
  from the last emitted suite in the root stream and then widening to `cli/test` if
  needed. Inspect late JVM/process cleanup and generated files such as
  `v1/tools/cli/ssc-storage.json`; do not paper over the non-zero fork exit.
- **Done-when:** targeted repro is understood and fixed, `scripts/sbtc "cli/test"`
  exits 0, and the final root-equivalent gate no longer reports this task failure.
- **Progress (2026-07-08, uncommitted worktree):** minimal
  `ElectronJvmRestCliTest` fork exit was caused by stale fake-Electron greps in
  the typed-route client smoke. The generated client now accepts
  `headers, cancelToken` and the HTTP runtime assigns `response = await fetch(...)`
  in a retry loop. Updating those smoke assertions made focused
  `ElectronJvmRestCliTest` pass with fork exit 0. Full `cli/test` now reaches
  ordinary assertion failures tracked separately above instead of the old
  after-green fork exit.
- **FIXED (2026-07-08, `cea0c3aed`):** after updating the stale Electron typed
  client smoke assertions and fixing the later deterministic CLI/runtime
  blockers, the full forked `cli/test` exits 0.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ElectronJvmRestCliTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-verify-default-srcdir-parent-scan — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: runtime
     gate: tests/conformance/run.sh
     fixed-in: unrecorded -->

- **Found by:** codex, during the same full root `scripts/sbtc "test"` gate.
- **Repro observed in root gate:** `VerifyCliTest` cases such as
  `verify .../ssc-verify-noruntime-* --strict` and `verify
  .../ssc-verify-json-* --json` spent about 1-2 minutes each on tiny temp
  directories. Thread dump showed the child process hot in
  `runVerify(Main.scala:4125)` at `os.walk(srcDir).filter(os.isFile)`; default
  `srcDir` was `artifactDir / os.up`, so temp sandboxes scanned the entire
  `/var/.../T` parent containing many other root-suite temp directories.
- **Impact:** root `sbt test` becomes needlessly slow and can look hung after the
  first real failures; production `ssc verify <dir>` can also scan far outside
  the requested artifact set by default.
- **Root cause:** default `srcDir` was always `artifactDir / os.up`; custom
  artifact directories such as temp `out/` folders therefore indexed every
  sibling `.ssc` file in the parent tree before checking a tiny artifact set.
- **Fix:** `6c996bd63` changes the implicit default to the artifact directory
  itself, preserving parent lookup only for conventional `.ssc-artifacts`
  output dirs, and adds a subprocess regression for a custom `out/` directory.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"` 8/8
  green; `tests/conformance/run.sh --only 'std-process-import' --no-memo` 1/1
  green.

## root-test-command-registry-other-category — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: 631ed8052
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** `CommandRegistryTest` failed
  `every command category is in the help ordering` with `List("Other") was not
  empty` at `CommandRegistryTest.scala:57`.
- **Targeted repro to run before fixing:** `scripts/sbtc "cli/testOnly
  scalascript.cli.CommandRegistryTest"`.
- **Initial hypothesis:** at least one command provider now reports or defaults to
  category `Other`, but the help category ordering omits it. Either assign the
  provider a real existing category or deliberately add `Other` to the ordering
  if it is now a supported category; do not silence the test without preserving
  deterministic help grouping.
- **Status:** fixed in `631ed8052`. Root cause was `VersionCmd` explicitly using
  the fallback-style `Other` category. `Other` is the default for unclassified
  commands, while `CommandRegistryTest` intentionally requires every visible
  command to be placed into an ordered help bucket. `version` is metadata/help
  output, so it now uses the existing `Help` category instead of normalising
  `Other` as a public bucket.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`
  (**8/8 green**) and `tests/conformance/run.sh --only 'std-semigroup-monoid'
  --no-memo` (**1/1 green**).

## v2-args-global-shadowed-by-native — `fixed` (2026-07-08)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** claude-fable-5, unmasked while testing OpAnf (entry below): the
  If-cond Let-wrap re-routed `if args.length > 0` from the length FastCode (whose
  tolerant `case _ => 0L` swallowed the wrong receiver) to the honest generic
  dispatch — which crashed `.length on <closure>` (dataset-word-count et al).
- **Root cause:** `loadAll()`'s SPI bridging registers a native FUNCTION global
  under "args"; the args VALUE-list registration was guarded by `if isEmpty` and
  never fired — `args` was a closure everywhere on v2. `args.length`/`args(0)`
  (the documented semantics, examples/dataset-word-count.ssc) only "worked" via
  the FastCode accident. Pre-existing on origin/main, INDEPENDENT of OpAnf; the
  v1 lane has the same gap (`No method 'length' on NativeFnV(<native:args>)`) —
  v1 side left open (BACKLOG note).
- **Fix:** register the args Cons/Nil list AFTER the plugin loop (same
  post-plugins override pattern as cwd/sep/platform), built from `Runtime.argv`
  (now set BEFORE loadAll in RunV2/bridgeCli); `scalascript.args` prop stays as
  the embedder fallback.
- **Verified:** `println(args)` → `List()`, `args.length` → `0` through the
  generic dispatch; dataset-word-count PASSES honestly (not via `0L` tolerance).

## jvm-multishot-result-type — `fixed` (2026-06-21, `39b7c665f`)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **SHA at filing:** `0ee00a29f` (`feature/jvm-multishot-result-type` worktree, after
  `sbt -no-colors cli/installBin`).
- **Symptom:** `bench/corpus/effect-multishot.ssc` reports `n/a` on the JVM backend even though the
  source declares `def workload(seed: Long): Long`. The bench wrapper uses an `AtomicLong` sink and
  emits `_ssc_sink.getAndAdd(workload(_ssc_sink.get()))`, but `emit-scala` currently lowers the CPS
  effectful `workload` as `def workload(seed: Long): Any`, so `scala-cli` rejects the wrapper with
  `Found: Any; Required: Long`.
- **Repro (real harness):** `./bench.sh effect-multishot --backend jvm` -> `n/a`; then
  `scala-cli --java-opt -XX:CompileThreshold=100 --java-opt -XX:-BackgroundCompilation --server=false
  /tmp/ssc-bench-jvm-effect-multishot.sc` shows the three `getAndAdd(workload(...))` type errors.
- **Root cause:** the top-level CPS def emitter always generated `def f(...): Any = ...` for any
  transitively effectful function. That is correct for effect-row defs (`A ! Eff`) that may return a
  Free computation, but wrong for total wrappers such as `def workload(seed: Long): Long` that handle
  their effects internally. The earlier handle-result fixes made `all.foldLeft(...)` compile, but the
  def boundary still widened the declared `Long` to `Any`.
- **FIXED (2026-06-21, `39b7c665f`):** JVM CPS def emission now keeps declared non-effect-row result
  types and casts the final CPS result at the boundary; `A ! Eff` defs still emit `Any`. The same helper
  is used for nested CPS defs inside CPS blocks. Regression guard: `JvmGenEffectsRuntimeTest` proves
  `addLong(workload(0L))` compiles and runs, so the total CPS def has static type `Long`.
- **Verified:** `sbt -no-colors "backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest"` =
  34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` = 0.075 ms/iter
  (was `n/a`); `./bench.sh effect-oneshot --backend jvm` = 0.160 ms/iter (same root cause).

## rust-foreach-list-realloc — `fixed` (2026-06-21, `abbc98eee`)
<!-- status: fixed
     lane: apparatus
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **Symptom:** Rust codegen re-inlines a top-level collection `val` at each use site instead of referencing
  the `let` binding emitted in each def preamble. In hot loops this rebuilds the whole `vec![...]` every
  iteration: `pattern-match-heavy` emits `for s in vec![Circle { .. }, Rect { .. }, ..].iter().cloned()`
  inside `while i < 100000`, leaving the preamble `let shapes = vec![...]` dead. `list-fold` has the same
  shape for `xs`.
- **Repro:** inspect generated Rust for `pattern-match-heavy` / `list-fold` with the real Rust emitter, then
  run `./bench.sh pattern-match-heavy list-fold --backend rust`.
- **FIXED (2026-06-21):** `RustCodeWalk` now references top-level vals by their generated `let` binding
  instead of re-inlining the initializer at every use site, and only injects a top-val preamble into defs
  that actually reference it. `collectMultiUse` also stops counting lambda/def parameter binders as reads,
  removing the spurious `area(s.clone())` for a single-use foreach parameter. Guard:
  `RustGenCollectionTest` asserts one `let xs = vec![...]`, `for x in xs.iter()`, no `for x in vec!`, and
  no `inc(x.clone())`. Verified emitted Rust: `area` has no dead `shapes` preamble, `workload` builds
  `shapes`/`xs` once and iterates the binding. Bench: `./bench.sh pattern-match-heavy list-fold --backend rust`
  improved `list-fold` 0.153→0.044 ms and `pattern-match-heavy` 4.16→1.37 ms.

## v2-arith-split-jit-size — `fixed` (2026-07-09)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/head-field-shadow.ssc -->

- **Found by:** claude-fable-5 while gating the head-field fix: pattern-match-heavy
  at ~354 ms/iter on clean origin/main vs 23.6 the evening before (15×). Bisected
  to `a2985d911 fix(v2): unify dynamic arith dispatch`.
- **Root cause:** the unification merged the whole `__arith__` dispatch table into
  `Prims.arithOp` — semantically right (it closed the table/arithOp divergence),
  but the merged method blew past the JVM JIT size limits, so EVERY arith op
  (literal-name hot loops included) ran interpreted. Reordering patterns bought
  only 350→300; splitting restored 26.
- **Fix:** `arithOp` keeps only the hot head (`->`, Int/Float/mixed/Str×Str pairs,
  Op-lifting) and delegates everything else to `private arithRest`. Bench:
  pattern-match-heavy 26.0/26.1, arith-loop 9.56, nested 15.3, effect-multishot
  5.12 — all at baseline. FrontendBridgeTest 25/25 (incl. the unification tests).

## v2-user-type-shadows-plugin-type — a user case class named "Request" (or any plugin-owned tag) has its fields clobbered on v2
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: d5f9ce486
     gate: tests/conformance/v2-user-type-shadows-plugin-type.ssc -->

**Status:** FIXED 2026-07-09 (8feeda99f) — the conditional-Request-lock +
snapshot/restore revert. Verified: the conformance repro passes (r1/task),
AND the "any plugin-owned tag" generalization did NOT hold empirically —
only `Request` was in `FrontendBridge.runtimeShapedTypes`, so ONLY it was
lockable/clobbered; user case classes named KV/Rate/Response/etc. always
went through registerCaseClass normally and already won (verified KV→7,
Rate→x in a batch). d5f9ce486 (snapshot/restore) was reverted; the real
root was the GLOBAL "Request" reservation, now conditional on the exact
std/http.ssc lib shape. v1 lanes correct, guarded by
tests/conformance/v2-user-type-shadows-plugin-type.ssc

`V2PluginRegistry.fieldNames` is a single GLOBAL, tag-keyed map shared by
EVERY case class in a program (user-declared or plugin-owned) — this is
the same root design (global, receiver-blind field-name registry) behind
`v2-head-field-dispatch-shadow` and `v2-route-params-stub`. Plugin load
registers a baseline entry for the runtime-owned `Request` tag (the
`std/http.ssc` `Request` type: method/path/headers/body/params/query/…,
needed for `req.params` to resolve — see `v2-route-params-stub`, now
fixed). `d5f9ce486` made `snapshot()` capture that baseline and `restore()`
reset `fieldNames` back to it. Any program that ALSO declares its OWN
`case class Request(...)` — a completely unrelated type, sharing nothing
but the tag name — gets that registration silently clobbered: field access
on the user's `Request` instances resolves against the HTTP-shaped field
list instead of the user's own, the user's field name isn't found there,
and the value comes back as `Stub`.

Repro (self-contained, no `std/http.ssc` import at all):
```
case class Request(id: String, kind: String)
val items = List(Request("r1", "task"), Request("r2", "investor"))
val hit = items.filter(r => r.id == "r1").head   // → RuntimeException: head on empty list
```
`items.filter(r => r.id == "r1")` comes back EMPTY (both items' `.id`
reads as `Stub`, so nothing matches `"r1"`), then `.head` on that empty
list crashes. v1 is correct: `println` shows `r1` / `task`.

busi hit this for real via `src/v2/domain/requests.ssc`'s OWN
`case class Request(id, partyId, kind, subject, body, channel, …)` (an
inbox-request domain type, nothing to do with HTTP) — this regressed
`tests/v2/requests.ssc` from passing to `RuntimeException: head on empty
list` the same afternoon the route-params fix landed (busi's full v2
domain sweep went 61/61 → 60/61).

Likely fix direction: `fieldNames` (and the runtime-baseline snapshot
introduced by `d5f9ce486`) needs to key on something that disambiguates
plugin-owned/builtin tags from user-declared ones (or restore() needs to
only reset entries that WEREN'T re-registered by the user's own compile
unit), not just the bare tag string "Request" — any user type name that
happens to collide with a plugin-owned runtime tag (Request is a very
natural, common domain name — busi is not the only likely victim) will hit
this. Found+minimized 2026-07-09 by busi (fable), same day as the
route-params-stub fix.

## v2-req-form-stub-in-hub — req.form(name) returns Stub inside busi's real hub.ssc (isolated minimization did NOT reproduce)
<!-- status: fixed
     lane: apparatus
     area: front
     fixed-in: unrecorded
     gate: tests/e2e/req-type-collision-v2-smoke.sh. -->

**Status:** FIXED 2026-07-09 (renamed root cause: **v2-req-form-type-collision**).
MINIMIZED: the hub imports TWO different `Request` types — `std/http.ssc`
`Request` (http, 14-field runtime layout with form/params) AND
`../domain/requests.ssc` `Request` (a business request: id/partyId/kind/…).
v2's field registry is keyed by tag NAME → the last-registered layout (the
domain `Request`) wins, so http `req.form`/`req.params` resolved against a
layout with no such field → `Stub`. (busi couldn't minimize it because a
single-Request fixture has no collision.) v1 tolerates it via fully-dynamic
by-name field lookup on the value. Fix: **arity-matched field resolution** — a
secondary `(tag, arity)` index in `V2PluginRegistry`; field access (both the
bare and field-with-args paths: `__method__`/`__methodOrExt__`/methodOp
fallback/`fieldAt` 3-arg/`.copy`) and `v1ToV2`/`v2ToV1` DataV building now
resolve against the layout whose arity == the receiver's field count. Fixes
BOTH collision directions. Verified: busi hub `POST /pair` correct code now
sets the auth cookie; corpus 154/8; gated by
tests/e2e/req-type-collision-v2-smoke.sh. Reported by busi (fable), minimized
+ fixed by lucky-perch.

busi's live hub (`src/v2/http/hub.ssc`), booted on `--v2`, has its
`POST /pair` route (`req.form.getOrElse("code", "") == pairCode`) always
read `req.form.getOrElse("code", "")` as the literal string `"Stub"`,
confirmed by temporarily instrumenting the route directly:
`DEBUG-PAIR formCode=[Stub] pairCode=[019f47] rawBody=[code=019f47]` — the
raw POST body IS received correctly (`code=019f47`), but `req.form`
parsing/field-access yields `Stub` instead of the parsed map. v1 (same
`busi.conf`, same route, same request) pairs successfully on the first
try. Since `POST /pair` is the ONLY way into every cookie-gated flow, this
alone blocks driving busi's live hub end to end on `--v2` (a live
money-loop pass could only proceed via a break-glass device-token seeded
directly into `tokens.txt` on disk, bypassing `/pair` entirely).

Two independent minimization attempts did NOT reproduce this in isolation:
1. A trivial `route("POST","/echo"){ req => ...req.form.getOrElse("code","<empty>") }`
   fixture, alone — `req.form` parses correctly (`code=hello`).
2. The same fixture PLUS a colocated `case class Subject(id, displayName,
   form, data, from)` — deliberately colliding the FIELD NAME "form" with
   `std/http.ssc`'s `Request.form` at a different index (2 vs the
   Request's declared index 4), the same class of bug as
   `v2-user-type-shadows-plugin-type`/`v2-head-field-dispatch-shadow` —
   still parses correctly.

So the trigger is something about `hub.ssc`'s actual scale/import graph
(it is one of the largest files in busi, importing dozens of modules) that
neither of those two isolation attempts captured — possibly import COUNT,
a DIFFERENT specific field-name collision elsewhere in the graph, or
route-registration-order sensitivity. Repro (needs a busi checkout):
boot `SSC_LANE_FLAG=--v2 scripts/ssc src/v2/http/hub.ssc` with a
`busi.conf` pointing at a scratch `dataDir`, read the printed pairing
code (or `cat /code.txt`), `curl -X POST http://localhost:/pair
-d "code="` — response is always "Неверный код" (v1: succeeds
first try). Found 2026-07-09 by busi (fable), same session as
`v2-option-exists`; flagging for whoever has better tooling to bisect a
large real file (busi did this successfully before for
`v2-head-field-dispatch-shadow` by copying+halving the failing module,
but hub.ssc's own internal complexity — not just its import graph — may
need a different bisection approach, e.g. commenting out route
registrations in blocks).

## v2-string-split-limit-overload — String.split(delimiter, limit) unimplemented on v2 (CAUSED A REAL PRODUCTION OUTAGE)
<!-- status: fixed
     lane: apparatus
     area: runtime
     fixed-in: unrecorded
     gate: tests/conformance/v2-string-split-limit- -->

**Status:** FIXED 2026-07-10 — `Runtime.scala`'s `String` method dispatch had
`(StrV(s), "split", List(StrV(d)))` (one-arg) but no two-arg case. Added a
sibling `(StrV(s), "split", List(StrV(d), IntV(limit)))` arm right next to it,
calling the SAME underlying `s.split(d, limit.toInt)` the one-arg case already
used internally (with `-1` hardcoded) — just parameterizing the limit instead.
Mirrors the existing `substring`/`substring(i,j)` sibling-arm pattern in the
same match. Verified: the repro below now matches v1 exactly; edge cases
across the full Java/Scala `split` limit semantics (positive/zero/negative)
are byte-identical v1 vs v2; 8+ busi domain tests exercising this code path
(durable, ledger, requests, bank_reconcile, social, sync, plus the exact
production trigger shape) pass cleanly on the patched binary. The full
175-test conformance suite could not be run to completion in this session
(scala-cli's compilation server died early under heavy concurrent load from a
sibling agent's work on the same machine — confirmed environmental, not
code-related: smaller batches through the same real harness passed cleanly).
Whoever lands this should re-run the full suite once on a quieter machine as
final confirmation. Fixed by busi (fable), same session as the report.

**Status (superseded, kept for history):** OPEN — v1 correct, guarded by
tests/conformance/v2-string-split-limit-overload.ssc. **Severity: this
exact gap took down a real, live production service** (busi, 2026-07-10)
within minutes of a routine v1→v2 default flip + deploy — not a test
failure, an actual customer-facing outage (two systemd services
crash-looped, restart counter 4+, both sites returning connection-refused
until a live rollback).

The one-arg overload (`s.split(delimiter)`) works correctly on v2. The
TWO-arg overload (`s.split(delimiter, limit)` — delimiter + a limit,
e.g. `-1` to keep trailing empty fields, the standard idiom for parsing
TSV/CSV rows that may have a blank last column) is not dispatched at all:

```
val s = "a\tb\tc\t"
s.split("\t")       // works on v2
s.split("\t", -1)   // RuntimeException: __method__: no dispatch for .split
```

busi's real trigger: `identity.ssc`'s `readTsv()` parses a real TSV
sessions file via `line.split("\t", -1)` at hub boot (loading the
sessions store for WebAuthn/email-login identity resolution). This file
only exists on an instance with real prior login history — every
pre-flip verification (a full v1-vs-v2 A/B harness covering an entire
money-loop end to end, a 30-route sweep over a seeded demo dataset, and a
real-browser e2e suite) used a fresh data directory with no sessions
file, so the two-arg `.split` call was never reached before the flip hit
production. Repro: `bin/ssc --v2
tests/conformance/v2-string-split-limit-overload.ssc` (v1 lanes below
pin the correct, passing behavior).

Found+minimized 2026-07-10 by busi (fable) immediately after diagnosing
and rolling back the live incident. Given the severity (a working
default-flip reached real production and broke it), this — and auditing
for other unimplemented String/collection method overloads with the same
shape (single-arg works, multi-arg silently missing) — should be
high-priority for v2 parity work.

## cli-errors-are-messages-guard-is-18-percent-above-its-own-runtime — a 60 s cap on a 49 s check

<!-- status: fixed
     lane: apparatus
     area: conformance
     kind: apparatus
     gate: scripts/smoke-ci
     fixed-in: 9ba8cbef8 -->

### CLOSED 2026-08-13 — both halves answered, and the second one is a NO

**The premise is stale.** The cap is no longer 60 s: `9ba8cbef8` set it to 180 000 ms and attached
the number, which is exactly what this entry demanded — *"a budget moved with a number attached, not
a guess"*. The comment beside it records 83.6 s standalone, six JVM launches, ~10 s a launch.

Re-measured today, same host, `tests/e2e/cli-errors-are-messages.sh` standalone:

```text
load 22    30 s    exit 0    against a 180 s cap  ->  6x headroom
```

The worst figure ever recorded for this check is the 83.6 s in that comment, which still leaves
better than 2x. The 18 % this entry is named after does not exist any more.

**The harder half, which is why this entry was right not to be closed by raising a number: ARE THE
SIX SPAWNS NECESSARY? Measured, and the answer is yes.** They are three assertions per launcher:

| spawn | asserts |
| --- | --- |
| `run boom.ssc` | a runtime error prints `ssc: <message>`, not a Java stack trace |
| `SSC_STACKTRACE=1 run boom.ssc` | and the trace is still reachable when asked for |
| `run ok.ssc` | a program that SUCCEEDS still succeeds — the anti-row, without which a launcher that failed at everything would pass |

times two launchers, `bin/ssc` and `bin/ssc-tools`, which is the whole point: the defect this check
exists for was present in ONE of them and absent in the other. Drop any spawn and an assertion goes
with it.

**And the ~50 s is not the check's work — it is launcher start-up, measured rather than assumed.** A
one-line `println(1)` costs, at load 35:

```text
bin/ssc         5194 ms / 5484 ms
bin/ssc-tools   4934 ms / 3492 ms
```

Six of those is 21-33 s before the check does anything. So this row is start-up-bound, and the only
way to make it faster is to stop asserting something. The suite already carries the same finding for
its twelve-spawn sibling — `scripts/smoke-ci.ssc` L345-358, *"the twelve spawns are the assertions"*.

**What this entry is really evidence for, and it outlives the entry:** per-spawn launcher start-up
is the dominant cost of the e2e suite, not the checks. Anyone hunting suite time should measure
there, not here.

**Status:** OPEN (measured 2026-08-09, after it was the SINGLE red of two consecutive smoke runs).

`scripts/smoke-ci.ssc` registers it with a 60 000 ms guard. Measured on this host, same checkout,
same day:

    in-suite, run A     FAIL  60.0s   exit code -1 — TIMED OUT against its 60s guard
    in-suite, run B     ok    32.5s
    in-suite, run C     FAIL  60.0s   TIMED OUT
    standalone, quiet   ok    31.8s
    standalone, loaded  ok    49.1s   (14 java processes)

**49.1 s against a 60 s cap is 18 % of headroom**, and the suite runs its checks in parallel, so
in-suite it is routinely slower than the loaded standalone number. That is not a check that
sometimes fails — it is a cap sitting inside its own variance.

**Why this matters more than one red row.** `smoke-ci` is the ONLY thing a push runs. A check that
goes red for being on a busy machine teaches everyone to read `FAILED:` and shrug, which is the
exact death `tests/conformance/contract.sc`'s own header describes: *"A correctness gate that
reports perf as TIMEOUT noise trains people to ignore it — which is how this one died."* Two agents
have now spent a standalone re-run proving the same non-defect.

**Do NOT just raise the number.** The check spawns both launchers several times and each pays JVM
start-up; the question worth asking first is whether it needs that many spawns, because ~50 s for
"a runtime error prints a message, not a stack trace" is itself the finding. If the spawns are
irreducible, the cap should be set from a measurement on a LOADED host with headroom stated, the
same discipline `corpus-contract-scljet-jdbc-v2-timeout` asks for — a budget moved with a number
attached, not a guess.

**Not fixed here** because `scripts/smoke-ci.ssc` is not in this claim's scope and the right cap is
a judgement about the whole suite's budget, not this row alone.

### A SECOND INSTANCE 2026-08-10 — `build-rust-refuses-loudly`, 162 s against a 180 s cap

Same shape, found the same way: it went red in-suite as a TIMEOUT and passes standalone.

    in-suite     FAIL 180.1s   exit code -1 — TIMED OUT against its 180s guard
    standalone   ok   162s

**10 % of headroom.** So this is a FAMILY, not one badly-chosen number, and the entry is worth
reading as such: caps in this suite are being set from an unloaded host and the suite itself runs
its checks in parallel, so the loaded number is the one that matters and it is not the one used.

`entry-auto-invoke-once` failed in the same run and is NOT part of the family — 49 s against a 120 s
cap standalone, comfortable, and its in-suite timeout was contention on a host that had been
building all day. The difference between the two is the margin, which is the whole point: 10 % is a
cap inside its variance and 60 % is a bad afternoon.

## install-dev-does-not-restage-the-F-front — an edit to `fsub.ssc` is invisible after installing

<!-- status: fixed
     lane: apparatus
     area: build
     kind: apparatus
     gate: scripts/launcher-input-digest
     fixed-in: 8265d8208 -->

**Status:** OPEN (found 2026-08-10 while fixing `case A | B` in F — the fix appeared not to work,
and the reason was that it had never been installed).

`build.sbt` maps the F front into the toolchain:

```scala
root / "specs" / "v2.2-p6.5-fsub.ssc" -> nativeFrontDir / "tower" / "bin" / "fsub.ssc",
```

**and `./install.sh --dev` exits 0 without re-staging it.** Measured: edit
`specs/v2.2-p6.5-fsub.ssc`, run `./install.sh --dev` (exit 0, zero `[error]` lines), then

```console
$ grep -c parseAltArm specs/v2.2-p6.5-fsub.ssc                              3
$ grep -c parseAltArm bin/lib/standard/native-front/tower/bin/fsub.ssc      0
$ grep -c parseAltArm bin/lib/native-front/tower/bin/fsub.ssc               0
```

Copying the file into both staged paths by hand made the same fix work immediately.

**Why this is worse than a stale build.** The launcher's own staleness check compares a digest of
the build's inputs and did not fire, so nothing warned. So the loop "edit F → install → measure"
silently measures the OLD front, and the natural conclusion is *the fix does not work* — which is
a wrong conclusion reached through a correct-looking procedure. Two of this repository's standing
rules exist for exactly this shape (`validate the probe reaches the shipped artifact`, and the
`--dev exits 0 on a failed compile` note), and neither covers a file that is COPIED rather than
compiled.

**Both staged trees are affected**, which matters: `bin/lib/native-front/` and
`bin/lib/standard/native-front/` are separate copies and a fix that updates one still leaves the
other stale for whichever launcher reads it.

**Not diagnosed further here.** Whether `--dev` skips that mapping deliberately (it is a "dev"
install) or the mapping sits in a task `--dev` does not run is the first thing to establish; the
fix is only interesting after that. Until then the workaround is one line, and anyone touching F
needs it:

```sh
for f in $(find bin -name fsub.ssc); do cp specs/v2.2-p6.5-fsub.ssc "$f"; done
```

### FIXED 2026-08-09/10 by a sibling — `8265d8208`, and VERIFIED here rather than taken on trust

The mechanism was not `--dev` skipping a task. `install.sh` keeps a toolchain CACHE keyed on
`scripts/launcher-input-digest`; on a HIT it restores `bin/lib` and skips `sbt cli/installBin`,
which is the task that stages `fsub.ssc`. The digest excluded `specs/` as a top-level tree, so
editing the F front did not change the key — cache HIT, old front restored, exit 0.

`8265d8208` ("the launcher digest did not cover the F front") adds the exception:
`specs/*.ssc` and `specs/*.ssc0` are included even though `specs/` is otherwise excluded.

**Verified on `origin/main`, not assumed** — the same probe that found the defect, run against the
fix:

```console
digest before        d216f84d252199b8…
digest after F edit  1c139d1eb6990b27…      <- differs, so the cache key moves
digest after restore d216f84d252199b8…      <- and the change is reversible
```

A changed key is a cache MISS, a MISS runs `cli/installBin`, and `installBin` is what copies
`specs/v2.2-p6.5-fsub.ssc` into both staged trees. So the loop "edit F → install → measure" now
measures what was edited.

**Closed on the mechanism, and here is the limit of that.** What was measured is the DIGEST, which
is the whole cache key; a full `install.sh --dev` was not re-run end to end to watch the staged file
change. If a future edit to F still fails to reach `bin/`, this entry is the first thing to
disbelieve.

**How long this could have been true is worth measuring** — `case A | B` has been broken in F for
as long as `parseCtorArm1` has existed, and a front whose edits do not reach the artifact is a
plausible reason a defect that size survived.
