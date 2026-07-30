# Build, CI and coordination tooling — bugs

Scope: defects whose FIX goes in `scripts/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module scripts`, never by
grepping for status.

Newest first.

## submodule-pointer-not-a-real-commit — main records a `.agents/plugins` commit that does not exist
<!-- status: fixed
     fixed-in: d5149ee32
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-30.** `git submodule update` fails for everyone, the working tree stays permanently
dirty, and `scripts/coord-release` refuses to run because of it — so this blocks releasing any claim,
not just the submodule.

```
main records:      fe840592b21ca4e2f9d0aa8d69b5a3a9a2ff5ba0
actually on origin: fe84059ec273af52bef87dcbf5409f69262c5d80   (refs/heads/main of the plugins repo)

$ git submodule update --init .agents/plugins
fatal: remote error: upload-pack: not our ref fe840592b21ca4e2f9d0aa8d69b5a3a9a2ff5ba0
```

Both start with **`fe84059`**. The 40-character SHA was not taken from the repository — it was
extended by hand (or by something) from the 7-character abbreviation and diverged after it. The
bump commit is `8b9470e62` ("submodule: bump plugins to fe84059"), and its message describes exactly
the content of the real `fe84059ec`, so the intent is not in question — only the recorded bytes are
wrong.

**The reusable lesson, and it is about the command rather than this incident.**
`git update-index --cacheinfo 160000,<sha>,<path>` accepts **any** 40-character string and does not
check that the commit exists, in the submodule or anywhere. That is how a fabricated SHA reaches
`main` silently. It is the standard way to bump a pointer without a submodule checkout — I used it
three times myself today — and it is safe only when the SHA comes from
`$(git -C <submodule> rev-parse HEAD)`. An abbreviation is fatal.

**Gate: `tests/e2e/submodule-gitlink-resolves.sh`** (added 2026-07-30). Every mode-160000 entry in
the INDEX is probed with `git fetch --depth 1 <url> <sha>` into a scratch repo. 2.2 s for the whole
run including its self-test.

Two alternatives were rejected for a measured reason each, so nobody re-litigates them:
`git ls-remote <url> <sha>` matches only REF TIPS, so a legitimate pointer at a non-tip commit would
fail it; and `git -C <sub> cat-file -e` needs the submodule checked out, which **CI does not do** —
no workflow passes `submodules:` to `actions/checkout`, so an object-store check would have verified
nothing there while looking green. The fetch probe is the same request `git submodule update` makes,
so it fails exactly when the real operation would.

Verified against this incident's own two SHAs, in both directions:

```
fe840592b…  ->  fatal: remote error: upload-pack: not our ref     (the fabricated one)
fe84059ec…  ->  fetched                                           (the real one)
```

and mutation-checked by REPRODUCING the outage — `git update-index --cacheinfo 160000,fe840592b…`
puts the tree back into the 2026-07-30 state, and the gate exits 1 naming the path, the SHA, the
remote, and the repair (derived from `git -C <path> rev-parse origin/HEAD`, never typed). Its
`--self-test` proves the probe can fail before trusting it to pass: it flips one hex digit of the
recorded SHA — well-formed, only its existence differs — and asserts the refusal, then asserts the
real SHA is accepted.

**The pointer itself was repaired by `d5149ee32`**, so the dirty-tree/`coord-release` blockage is over.

**NOT DONE, handed over:** wiring the gate into a workflow. `scripts/smoke-ci` (the push path) is held
by the `smoke-launcher-cache` claim; at ~2 s it belongs there, and the current smoke budget has room
(a green run measured 259.3 s of 330 s). Until it is wired, the gate exists and passes but nothing
runs it — which is the same hole this entry is about.

## coord-release-drops-the-evidence-level — the tool swallows its own flags, and AGENTS.md requires that field
<!-- status: fixed
     fixed-in: f7e1e687e
     lane: apparatus
     area: build
     gate: tests/coord/coord-release-evidence-level.sh -->

**Found 2026-07-30** on the first real use of `scripts/coord-release`.

```
$ scripts/coord-release v2js-unit-pattern --level 3 --note "contract green"
$ git log -1 --format=%s
release-claim: v2js-unit-pattern — --level [skip ci]
```

`--level` landed in the message as a literal; the `3` and the `--note` vanished. Cosmetic on its own
— but **AGENTS.md §4c requires a release-claim to say which of the three evidence levels it has**,
and never to write "green" for a run that produced no verdict. A tool that silently drops that field
turns a required statement into an optional one, and the release record is where the next agent looks
to decide whether a thing is trustworthy.

Suggested shape: parse the flags, or **refuse without `--level`**. The second is stronger — it makes
forgetting impossible rather than merely discouraged.

**FIXED 2026-07-30 — both, since the second needs the first.** `coord-release` now parses
`--level <1|2|3>` and `--note "<text>"`, REQUIRES `--level` (the refusal cites §P-6.7 and prints the
three levels), fails CLOSED on an unknown flag instead of decorating the message with it, and keeps
the old positional-note form working so existing habits do not break. The level lands in a fixed
position — `release-claim: <slug> [evidence: level N] — <note>` — so the record is greppable:
`git log --grep='release-claim:'` now shows every release with the evidence it claimed.

Gate: `tests/coord/coord-release-evidence-level.sh`, a lab with a fake origin running the REAL
script (the `coord-claim-runs.sh` idiom — a text-inspecting test in this family once passed against a
tool that aborted on line 128). Against the OLD tool it fails **14 of its checks** and reproduces the
report's message verbatim: `release-claim: flagform — --level [skip ci]`.

⚠️ Worth copying: the first version of that gate had **two checks passing for the wrong reason**.
`--level 9 is refused` and `an unknown flag is refused` were green against the old tool — not because
it refused anything, but because the previous case had already consumed the claim and it exited 2
with `no such claim`. Same exit code, different reason, indistinguishable from a real pass. Found by
running the gate against the old tool and reading WHICH checks passed rather than counting them; the
fix is a fresh claim per case, and the failure count went 10 → 14.

## module-sprint-item-written-by-nobody — two of the three artefacts are automated, so the third is forgotten by construction
<!-- status: open
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-30**, in the same session that automated the second artefact.

`specs/work-tracking-layout.md` says a task appears in THREE places in one commit: the claim, the row
on the root board, and `[~]` in that module's `SPRINT.md`. `coord-claim` writes the claim and the
board row; `scripts/board` now derives the board from `.work/active/`. **Nobody writes the module
item** — `coord-release` even prints "STILL BY HAND: mark the finished item `[x]` in its module
SPRINT.md", and checking two of my own finished claims, the `[~]` had never been written either.

This is the same argument that justified automating the board row: a step that depends on every agent
remembering it is a wish, not a rule. Two options, and the second looks better on this project's
evidence — generated state has not drifted here, hand-maintained state has:

1. `coord-claim` / `coord-release` write the module item too (the module is derivable from
   `tests/fixtures/modules.tsv`);
2. the module sprint becomes DERIVED from the claims, exactly as the root board now is, and `[~]`/`[x]`
   stop being written by hand at all.

## coord-bookkeeping-needs-a-claim — the per-module split made FILING A BUG require a claim, and mid-migration nobody could file at all
<!-- status: open
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-30** by hitting it twice in a row, in a session that produced fourteen entries.

The claim scope guard exempts the shared bookkeeping files by BARE NAME — `SPRINT.md`, `BACKLOG.md`,
`BUGS.md`, `CHANGELOG.md`, `MILESTONES.md`, `README.md`. The per-module split (`5612b3d0c`) moved every bug
entry into `<module>/BUGS.md`, and those paths do not match that list. Consequences, both measured:

1. **Filing any bug now needs a claim widening.** `git add v1/runtime/backend/js/BUGS.md` is refused as
   "outside what claim … declared", so recording a finding costs a claim round-trip that recording it in
   the old flat file did not.
2. **During the migration itself, no js/jvm/v2 bug could be filed by anyone**, because the migrating claim
   held every `BUGS.md` in the repo at once. Two findings had to be parked in `specs/` instead
   (`specs/json-number-policy.md`, `specs/v2-char-is-an-int.md`) and routed into entries hours later, after
   that claim released.

**Why it reads as an oversight rather than a decision:** the guard prints *"SPRINT.md / BACKLOG.md /
CHANGELOG.md / BUGS.md … are SHARED and are never an overlap — if one of those is in the list above, this
hook has a bug."* It says the intent plainly; the pattern just no longer matches where the files live.

**Two ways to close it:**
1. match on the BASENAME (or `*/BUGS.md`), which restores the stated intent for the new layout;
2. or state in `specs/work-tracking-layout.md` that bug filing is claimed work now, and accept the
   round-trip — defensible, but it should be written down rather than discovered.

A related shape, worth deciding together: `SPRINT.md` is exempt as SHARED, yet the shared-main pre-commit
guard refuses anything outside `.work/`, so `coord-claim`'s own "claim and board row in ONE commit" contract
was unimplementable from the main checkout. That was found the same day (`bfbc42fe6`, backed out in
`0c8237e60`) and is the same disagreement between two guards about what counts as bookkeeping.

## coord-claim-second-positional-overwrites-slug — an unquoted `--items A B` claimed under a name the caller never typed
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: unrecorded
     gate: tests/coord/coord-claim-runs.sh -->

**FIXED 2026-07-30**, reported by Sergiy after I hit it.

`--items` and `--paths` each take exactly ONE argument — the usage line documents them as quoted lists
(`--items "<id> …"`). Written unquoted, the extra words stay positional, and the old catch-all was:

```bash
*) slug="$1"; shift ;;
```

No "already set" check, so the LAST bare word won. `coord-claim v2-diverge-dsl-parsers --items A B`
therefore assigned `slug=v2-diverge-dsl-parsers`, then overwrote it with `B` — creating
`.work/active/B.claim` with `items: A`, printing `✓ claimed B`, and exiting 0.

**Why it is worth a fix rather than a note in the usage text.** The tool did the right-looking thing: a
claim WAS created, the push succeeded, the ledger row matched. The only signal was a slug the caller never
typed, in a line that normally scrolls past. That is the same failure shape as the `$root` outage in
`bfbc42fe6` — quiet wrongness — except this one leaves a claim under the wrong name, which is precisely
what the mutex is supposed to make impossible to get wrong by accident.

**Fix:** a second positional argument is refused, naming both candidates and showing the quoted form.

**Gate:** `tests/coord/coord-claim-runs.sh` gained five checks — refused, no claim under the stray word, no
half-written claim under the intended slug, the message mentions quoting, and it names both candidates.
Proven fail-first by pointing the gate's own `COORD_CLAIM` override at the pre-fix copy: four checks fail,
and the decisive one is `it did NOT create a claim named after the stray word` -> **got=yes**, i.e. the lab
reproduces the incident rather than merely describing it.

## install-sh-exits-0-when-sbt-project-load-fails — a build that produced nothing reported success
<!-- status: open
     lane: apparatus
     area: build
     gate: none -->

**Found 2026-07-30** while adding the stale-build stamp. I put Scala 3 syntax (`then`, braceless
`catch case`) into `build.sbt`, which is **Scala 2.12**. sbt refused to load the project:

```
[error] [.../build.sbt]:1874: illegal start of simple expression
[warn] Project loading failed: (r)etry, (q)uit, (l)ast, or (i)gnore? (default: r)
```

It got EOF on that prompt, and **`./install.sh --dev` exited 0**. No launchers were written, no
`bin/lib` at all — and the script said success.

I noticed only because I grepped the produced artifact instead of trusting the exit code. Anyone who
trusts it gets something worse than a failed build: `bin/ssc` is TRACKED, so a checkout still has a
launcher, `git status` stays clean, and the next command runs **whatever was in `bin/lib` before** —
i.e. a silent fall back to an older toolchain, which is precisely the class of failure the stamp was
being added to close.

**Where to look.** `install.sh --dev` invokes sbt and does not propagate its exit status; the
interactive `Project loading failed:` prompt on EOF is the specific shape that escapes, because sbt
exits 0 after choosing the default. Two things to fix, not one: pass `-batch` (or set
`onLoadFailure`) so the prompt cannot appear, AND check the status. Better still, assert the artifact
exists afterwards — `bin/lib/.build-stamp` now makes that a one-line check, and an exit code is a
claim while a produced file is evidence.

## uniml-root-standalone-target-cache-collision — prescribed root→standalone verification corrupts UniML compilation state
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 3e52e6909 -->

**Status:** FIXED in `3e52e6909` (found 2026-07-28 while qualifying UPR-1 after the root
JVM/Scala.js suites were green).

**Reproduction.** In one UniML worktree, first run the root build:

```bash
scripts/sbtc ";unimlYaml/test;unimlYamlJs/test"
```

Then run the standalone build exactly as required by the production gate:

```bash
cd uniml && sbt -batch test
```

The standalone build reuses target directories under `uniml/<module>/.jvm|.js/target` but loads
them through a different sbt build definition. The observed run failed with hundreds of false
`Not found: Limits` / `Not found: VmToken` compile errors plus JVM
`NoClassDefFoundError: scalascript/uniml/SourceId$` and
`NoClassDefFoundError: scalascript/uniml/VmInstruction`, even though the same root projects had
just passed. This is a real-harness failure; no clean was inserted between the two prescribed
gates.

**Impact.** Root and standalone verification are not composable in the documented order. A clean
workspace can hide the collision, so CI/local evidence depends on which build touched the shared
incremental products last.

**Fix acceptance.** Reproduce and isolate the exact target/analysis collision, then give the root
and standalone builds disjoint products (or prove their module definitions byte-identical) without
requiring `clean`. Run root YAML/Markdown JVM+Scala.js tests followed by standalone `sbt test`,
then repeat the root slice and standalone test a second time; all four transitions must be green.

**Root cause and verification.** The root build and `uniml/build.sbt` described the same nine
cross-project source trees with incompatible Zinc/export settings while writing into the same
`target` products. The standalone projects now use nine distinct `target/standalone` namespaces,
and their aggregate test is guarded by a fail-closed path-isolation check. Injecting one shared
target makes that check fail with the complete offending path set. The no-clean
`scripts/verify-uniml-dual-build` transition gate ran
root YAML/Markdown JVM+Scala.js → standalone aggregate twice; all four transitions passed and the
second round compiled zero sources. Independent review accepted the isolation and transition gate.

## ci-status-sha-misses-commits-covered-by-a-later-tip — a code commit can be TESTED and still report "no run"
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by opus (`ci-status-descendant-fallback`). Found 2026-07-28 by
`ci-bookkeeping-floods-verdicts` while verifying the `paths-ignore` change on live traffic; not
caused by it, only made easier to notice.

**Fix.** When the exact-SHA query finds nothing, `scripts/ci-status` now looks for the NEAREST run
whose head is a descendant of the requested commit — real `git merge-base --is-ancestor`, not string
matching — and reports its verdict labelled `(descendant)` with a `covered by: <sha>` line. The label
is in the HEADLINE, not a trailing note: "tested as part of a later tip" is genuinely weaker evidence
than "tested alone" and must never read as the latter. `--exact-only` restores the strict answer.

**Verified on the exact commit this entry names.** `scripts/ci-status --sha 2adeef250` went from
`CI UNKNOWN` to a real verdict, `covered by d11a7746…`.

**A second defect found by running it — in my own output.** That covering run is
`completed/cancelled` with **ZERO jobs** (queue eviction), and the report said
`missing required job: Lint Markdown` ×4 — which reads as "the workflow dropped its jobs" and sends
the reader after a config problem that does not exist. A run with no jobs now says so plainly:
`run completed/cancelled with ZERO jobs — it never started one, so it is evidence of nothing`, and
points at `ci-runs-cancelled-under-churn`.

**Gate.** Four new cases in `tests/e2e/ci-status-guard.sh`, using REAL commits from this repository
because the ancestry check is real git. The one that makes it a gate rather than a demo is the
NEGATIVE: when the only available run is an ANCESTOR, the answer must stay `CI UNKNOWN` — otherwise
the fallback would accept any recent run as evidence for anything. Plus `--exact-only` must return
UNKNOWN where the fallback returns GREEN, which proves the other verdicts came from the fallback and
not from some unrelated path, and a zero-jobs case asserting the run is RED **without** the
misleading missing-job list. A/B: against the previous script `desc-green` fails with
`expected exit=0 got=2` — exactly the false UNKNOWN this entry describes.

**What happens.** GitHub creates ONE run per push, attributed to the push's TIP commit, and applies
`paths` filtering to the push as a whole. So a push of `code-commit` followed by `docs-commit`
produces a single run named after the DOCS commit — the code was fully tested, but
`scripts/ci-status --sha <code-sha>` finds nothing and reports `CI UNKNOWN`.

**Observed.** `100c20676` (md-only) → `2adeef250` (`specs/v2.2-p6.5-fsub.sh` + `.ssc`, real code) →
`d11a77460` (BUGS.md only) landed as one push. Exactly one CI run was created, on `d11a77460`.
Reading the per-commit table naively says "a docs commit triggered a run and a code commit did not",
which is wrong on both halves and was my first reading of it.

**Why it matters.** AGENTS.md §4c tells you to verify the newest CODE commit by exact SHA. Under
batched pushes that instruction can report UNKNOWN for a commit that was actually covered, which
pushes people toward re-running or toward accepting weaker evidence. The green-descendant ladder
already handles it — a descendant run covers its ancestors — but `ci-status --sha` does not say so.

**Fix direction.** Have `scripts/ci-status` fall back: when no run exists for the exact SHA, look
for the nearest run whose head is a DESCENDANT of it and report that explicitly as
"covered by <sha> (descendant)" rather than UNKNOWN. That is the evidence ladder AGENTS.md already
describes, made mechanical instead of manual.

## ci-runs-cancelled-under-churn — most commits get no verdict, and `cancelled` is RED
<!-- status: unknown
     lane: apparatus
     area: build -->

**Status:** **MECHANISM ESTABLISHED, fix landed 2026-07-27** (opus, `ci-queue-concurrency`,
`2f7052ba3`) — **verification deliberately still pending, see the criterion at the end.** Originally
recorded the same day while waiting on exact-SHA CI for four landed batches, without guessing a cause.

**Re-measured ~21:20Z, and it is worse than the first observation.** Of the last 60 CI runs **ZERO
had completed** — 55 `queued`, 5 `in_progress` — with the oldest queued run waiting since 18:13Z,
over three hours. Across the last 100 runs of all workflows, **16 CI runs were `in_progress`
simultaneously**.

**Mechanism.** 16 concurrent runs × 4 jobs ≈ 64 concurrent jobs, above the account's concurrent-job
budget. `ci.yml` had no `concurrency:` block, so nothing superseded anything: every push started a
fresh run, the budget stayed saturated by `sbt` jobs (`timeout-minutes: 300`), and newer runs queued
behind them until evicted or cancelled. The `cancelled` outcomes are queue eviction, not flaky gates.

**Fix.** `concurrency: {group: ci-${{ github.ref }}, cancel-in-progress: false}`. The `false` is the
point: a run already EXECUTING is never killed, so a long `sbt` job still reaches its verdict; what
GitHub cancels is the previously PENDING run in the group. The queue collapses from 55 deep to at
most one running plus one pending per ref, and the newest commit is always next to run.

**Cost, stated plainly:** intermediate commits get no verdict of their own — but they get none today
either, so this trades a silent red for an explicit supersede, which is what makes the
green-descendant ladder in AGENTS.md §4c usable.

**⚠️ NOT VERIFIED YET, and this must not be quietly dropped.** A workflow-level `concurrency` block
cannot be exercised locally; the file was checked structurally (top-level keys, block shape, no tabs)
and nothing more. **Falsifiable success criterion:** `gh run list --workflow=ci.yml` should show at
most one `in_progress` plus one `queued` run for `refs/heads/main`, and completed runs should start
appearing again. If the queue is still tens deep an hour after this landed, the mechanism above is
WRONG and this entry must be reopened, not closed.

**Interim observation, ~10 min after landing (recorded because a promise to verify is worthless
unless the check is actually written down).** `main` runs now read `pending: 1, cancelled: 1,
queued: 38`. The `pending` + `cancelled` pair is the new group engaging exactly as described — one
run held pending, a previously pending one superseded. The 38 are the PRE-EXISTING backlog: GitHub
runs each with the workflow definition from its own commit, so runs created before `2f7052ba3` keep
the old unbounded behaviour and must drain on their own. **This is not yet the success criterion
above** — that needs the backlog gone. They were deliberately NOT mass-cancelled: any one of them
may be the exact-SHA verdict another agent is waiting on, and destroying a sibling's evidence to
make my own fix look verified is the opposite of the point. Draining them is the CI owner's call.

**Fresh exact-SHA instance, Corpus Contract E7.** Run `30307158170` for final
SHA `9975a0c0c` was superseded and completed `cancelled` with zero jobs. Per
AGENTS.md this is RED/no verdict, never neutral: E7 released on evidence level
3 (its named local classifier, real-corpus, conformance, routing, and doc gates),
not on a fictional CI green.

**Fresh exact-SHA instance, V-6b.** Run `30308711327` for final SHA
`42c4f487f` was superseded and completed `cancelled` with zero jobs. This is
again RED/no verdict. V-6b releases on evidence level 3: focused unit tests
11/11, exact no-fallback and product parity gates, backend isolation,
fallback visibility, affected conformance 1/1, and full-repository markdownlint.

**Observed on `ci.yml` / `main`, 2026-07-27 ~16:59Z:**

- **6 of the last 14 runs ended `cancelled`** (`b672b0d41`, `bb78a98ea`, `309011c05`, `1ad34d8ec`,
  `4e0c17737`, `32214b697`, `adba80d36`). In the one inspected (`30286193700`) all four jobs
  cancelled together, ~8.5 min in — not an instant supersede.
- The queue was **8 runs deep**; the three oldest (`1ebb8bf1e`, `a02036602`, `e0529a53b`) had been
  `in_progress` for **80+ minutes**.
- **`ci.yml` has no `concurrency:` block**, so GitHub is not auto-cancelling superseded runs.
  `pages.yml` is the only workflow with one and it is `cancel-in-progress: false`.

**Why this is a bug and not just slowness.** `MILESTONES.md` already records that a gate reporting
`cancelled` is **RED, not neutral**. `AGENTS.md` §4c makes an exit-0 exact-SHA run the *only* green
verdict and says pending/unknown keeps a claim open. With four agents pushing, that verdict is
effectively unreachable: a claim either stays open forever or is released on evidence the rule does
not accept. Both happened today. This is the same shape as the entries in
`feedback_measurement_must_compare_not_prejudge` — the apparatus that is supposed to establish trust
is the thing that is broken, and it fails *quietly* (a cancelled run looks like "not red").

**Interim practice** (what this session actually did, stated so it is auditable rather than implied):
verify via a **green descendant** run plus `gh run view --json jobs` for the specific job that could
catch the change, and say in the release-claim which evidence exists. Example: for the scljet batch
the run was still `in_progress` while its `Conformance Suite` job already read `success` — that is
the job that would catch a scljet regression, and the 300-min `sbt` job is the long pole.

**Candidate fixes for whoever owns CI** (each needs the mechanism confirmed first): add a
`concurrency` group with `cancel-in-progress: false` so queued runs are not lost; or split the
300-min `sbt` job so a verdict arrives before the queue laps it; or gate the fast jobs
(Conformance / Validate / Lint) as the per-push verdict and run `sbt` on a schedule.

## swiftui-real-fixture-swift-without-swiftui — Linux `swift` is not a SwiftUI capability
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: c278b4b37 -->

**Status:** DONE (2026-07-21, `c278b4b37`; exact containing SHA `1f5e55b44`, run
`29805732016`; found and confirmed by codex while closing F7 exact CI). Earlier run `29775034983`
at `1fbe993b4` completed every preceding job/gate, then failed exactly one named test:
`SwiftUiRealFixtureBuildTest` tried to build the macOS package on Ubuntu and `swiftc` reported
`ContentView.swift:3:8: error: no such module 'SwiftUI'`. The final aggregate was 619 succeeded,
1 failed, 22 canceled; this was the final full-CI blocker.

**Root cause / repro.** The test's `swiftAvailable` predicate checks only `swift --version`. GitHub's
Linux image has a working Swift compiler but no Apple SwiftUI SDK, so that proxy passes and the
macOS-only build starts. The real observable is whether the compiler can typecheck `import SwiftUI`:
write a temporary `.swift` probe and run `swiftc -typecheck <probe>`, capturing exit/stdout/stderr.

**Fix / verification.** `SwiftUiRealFixtureBuildTest` now typechecks a temporary `import SwiftUI`
probe with `swiftc` and reports exit/stdout/stderr when the real package build is canceled. A direct
impossible-module regression proves the probe compares module availability. The deliberately-invalid
staged `.ssc` regression remains outside that gate, so Linux still catches generated-Scala failures.
The focused suite is 3/3 on macOS (including the real `swift build`); with a Linux-shaped `swiftc`
that cannot import SwiftUI it is 2 passed / 1 named canceled, and the generated-Scala regression still
runs. The complete v2 gate is 644 ok / 0 FAIL and shared `v2-*` conformance is 11/11. Exact run
`29805732016` for containing SHA `1f5e55b447f6a2e28c2fd3efe2e5599d99f6a8bd` completed all four
jobs successfully, including the full Linux sbt test; `scripts/ci-status` returned 0.

## scljet-vfs-exclusive-lock-subprocess-exits-linux — official SQLite does not wait on host lock
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-18, `scljet-xprocess-lock`). It was a **test bug, not a lock-interop bug** —
the cross-process lock protocol in `SclJetJvmVfsHost.scala` is correct and needed no change. Root cause:
the subprocess probe (`SclJetSqliteLockProbe`) set `busy_timeout=0`, which tells SQLite to return
`SQLITE_BUSY` *immediately* on a lock conflict and never wait; the test then asserted `process.isAlive`
after a 500 ms sleep, expecting the query to be blocked. With `busy_timeout=0` it can never block — it
returns in ~2 ms and the subprocess exits, so `process.isAlive()==false`.

**Evidence (macOS, instrumented `LockDiag` harness).** With scljet holding the Exclusive host lock:
- `busy_timeout=0` → subprocess prints `busy after 2ms: [SQLITE_BUSY] The database file is locked` —
  SQLite **detects** scljet's lock but returns instantly.
- `busy_timeout=5000` → subprocess **blocks** for the whole window the lock is held (`exited within
  1500ms? false`), then prints `ok after 1266ms` **only after** scljet releases.

So the official xerial `sqlite-jdbc` driver *does* genuinely wait on scljet's fcntl POSIX write-lock
(PENDING byte + SHARED range) cross-process — the JVM `FileChannel.tryLock` and SQLite's Unix VFS
`fcntl(F_SETLK)` locks interoperate exactly as designed.

**Fix.** In the test only: the probe now sets `busy_timeout=30000` (so SQLite enters its busy-retry
loop and waits), and prints a `querying` signal immediately before the blocking read. The test
synchronizes on that signal (no sleep), then asserts `!process.waitFor(2, SECONDS)` — proving the query
stays blocked while the lock is held — releases the lock, and asserts the query completes with `ok`.
Deterministic: `busy_timeout` (30 s) ≫ the 2 s window, and the query cannot return until scljet releases.
`scljetVfsPlugin/test` 6/0 (×3), `scljetJdbcPlugin/test` 57/0. No production code changed.

## ci-sbt-outer-timeout-cancels-bounded-test-step — job budget expires before the suite can report
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 90c5599dc
     confirmed: no -->

**Status:** FIXED (revised 2026-07-17 in `884832696`; awaiting Linux confirmation). The first fix
`90c5599dc` raised only the outer cap; completed Linux run `29545769651` then proved the 60-minute
step cap independently insufficient. Originally found by `ci-red-main` in completed run
`29544412767`, SHA `73407430457effd61bb96307c4bb41c6d3df3179`, job `87773372863`. The job-level
timeout cancelled the test suite before the separately bounded test step could finish, so CI could
not reveal the complete failure set or prove the sbt job green.

**Real-harness repro.** The sbt job started at `00:18:32Z`. Setup, compile/assembly, and all six
v2.1 release gates ran through `00:53:54Z`; `Test via sbt` then ran until `01:48:45Z`, when GitHub
cancelled the whole job exactly at its 90-minute budget. The test step therefore received only
54m51s of its explicit 60-minute allowance and was still executing. `gh run view 29544412767
--json jobs` records the outer job as `cancelled` and the test step as `cancelled`, not as a test
failure or success.

**Expected/fix plan.** Keep the 60-minute test-step cap as the hang detector, but raise the outer
job budget to 120 minutes so measured setup/gates plus the bounded test have headroom for runner
variance. Document the timing next to the workflow setting. Acceptance requires a current Linux
run to reach the test step's natural verdict; extending the timeout alone is not evidence of green.

**Fix/result.** The sbt job now has a 120-minute outer budget while `Test via sbt` retains its
60-minute cap. Workflow YAML parses locally. Status remains awaiting confirmation until a run
containing `90c5599dc` or later reaches a natural test success/failure instead of cancellation.

**Correction from the next completed Linux tail.** Run `29545769651`, SHA `893bf2632`, job
`87777659720`, reached `Test via sbt` at `01:17:59Z` and GitHub stopped that step at `02:18:11Z`
with the explicit diagnostic `The action 'Test via sbt' has timed out after 60 minutes`. The suite
was not hung: it was still reporting green `CrossBackendPropertyTest` cases, but had completed only
12 of that suite's 16 ordered cases; the uncompleted tail includes both generated-program matrices.
Thus the landed 120/60 configuration remains deterministically red. The next fix is 150 minutes for
the outer job and 90 for the test step, retaining bounded hang detection while giving the measured
tail 30 additional minutes. That revision landed in `884832696`; only a later Linux natural verdict
closes confirmation.

## v2-swiftui-online-derived-owner-gap — computed online readers do not own monitoring
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 0ade8bf7c -->

**Status:** done (2026-07-11, `0ade8bf7c`); reported and confirmed fixed by `nativeui-reviewer` in the
`scalascript` Rozum review of the persisted/online Apple slice.

- **Real-harness repro:** subscribe only to a computed/equality signal whose
  closure reads `onlineSignal`. Dependency commit owns fetch families but has
  no online-family ownership, so no monitor starts and the derived cell cannot
  react to connectivity changes.
- **Expected:** active transitive readers acquire exactly one refcounted online
  owner; callbacks recompute/publish the derived cell, and last derived
  unsubscribe cancels the monitor.
- **Plan/done-when:** mirror dependency fetch ownership for online dependencies
  and gate computed-only first/last subscription plus publication.
- **Root cause/fix:** dependency commits tracked fetch families only. Online
  dependencies now have symmetric acquire/release/disposal ownership, so a
  computed-only subscriber starts and stops the shared monitor correctly.

## v2-swiftui-keyed-fetch-metadata-stale — surviving fetch keeps its first request descriptor
<!-- status: fixed
     lane: apparatus
     area: build
     fixed-in: 5c0b38ad9 -->

**Status:** done (2026-07-11; fixed in `5c0b38ad9`, `068e8b62d`, and
`03f2f1fcf`; `nativeui-reviewer` confirmed with final APPROVE in the
`scalascript` Rozum room).

- **Real-harness repro:** reconstruct a fetch with the same keyed/component
  `(scope,id,kind,default)` but change its literal URL, or swap a literal URL for
  a scoped URL signal. Host reuses the live cell while ignoring new metadata;
  Store retains the first wrapper in its stable observable cell. An active A
  request is therefore neither cancelled nor restarted as B, and later refresh
  dependencies continue to use stale A metadata. Conversely, comparing the new
  wrappers with ordinary equality treats regenerated read/write closures as a
  false change and restarts identical fetches.
- **Expected/root-cause direction:** canonicalize every nested NativeUiSignal
  reference in request metadata to validated `(scope,id,kind)`, retain current
  metadata for the live family, and restart an observed family only after a
  committed structural metadata change. Generation checks make late A inert;
  identical registration preserves one stable cell/task.
- **Plan/done-when:** strict generated Swift drives same-key scoped fetch
  reconstruction through identical A, literal A→B, and literal→signal-ref B;
  one transaction that registers the same live key as intermediate A then final
  B must coalesce to one B restart (and no restart when final B equals the
  pre-transaction descriptor);
  assert exact cancellation/request counts, late-A inertness, current value,
  dependency ownership, and bounded task metadata. Keep `fixed` until the Rozum
  reporter confirms the regression.
