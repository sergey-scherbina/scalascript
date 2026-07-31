# Build, CI and coordination tooling — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `scripts/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] **smoke-verdict-on-every-push** — concurrency group removed from `smoke.yml`. MEASURED cadence
  that forced it: 57 commits/hour on main, 32 `[skip ci]`, so ~25 runs created against a job that
  completed ~7 — 7 success / 12 cancelled over 20 runs, i.e. 37 % of commits got a verdict. Safe now
  and not before because smoke is ONE ~6 min job that skips sbt on a cache hit, against ci.yml's 4+
  jobs of 13-38 min that saturated the account budget. Cost stated in the file: ~150 runner-min/hour
  against ~45. Owner's decision, taken on the numbers. Verified: two runs executing in PARALLEL
  minutes after the change (structurally impossible before), run 30604850251 GREEN 21/21.
  Watch for: runs sitting in `queued` — that means the account budget is the limit again, and the
  answer is a group with `cancel-in-progress: false`, not a bigger timeout.

- [x] **smoke-corpus-slice-dominates-and-varies** — the corpus check is split by LANE. Measured on
  the 13-case slice, three alternating rounds: all four lanes 33.7-37.6 s, the same cases without
  `jvm` 11.9-15.8 s — the JVM lane was ~65 % of the check. Now two invocations: breadth on
  int,js,v2 (13.5 s) plus `--lanes jvm` over the four cases where the JVM backend is the point
  (11.5 s). No lane goes dark; the cost stated is that a JVM regression in the other ten cases waits
  for the PR/4-hourly run. Needed two new run.sc behaviours, both gated by
  `tests/e2e/conformance-lanes-flag.sh`: `--lanes`, and a zero-match `--only` now ERRORS instead of
  reporting a green run over zero cases (the push-path check names 13 cases by hand — one rename
  would have shrunk it silently). The per-module rollup caught its own regression: v2 fell 4 -> 3
  after the first split because `int-width` carries the only `also-codegen: v2` in the list, so the
  `JVM/v2` sub-lane had vanished.
  ⚠ First A/B said "no benefit" and was contaminated by a scala-cli recompile of the edited run.sc;
  re-measuring on a warm tree gave the 2.8x. P-6.6 twice in one task.

- [x] **smoke-budget-set-from-one-sample** — the 330 s budget was fitted to a single CI observation
  (250.2 s) and went red at 338.6 s with all 18 checks green. The dominant check, the corpus slice,
  varies ±25 % between runs on a shared runner (115.5 / 175.2 / 233.9 s). Budget → 420 s, sized above
  the observed spread, with the three-row history in `scripts/smoke-ci.ssc` so nobody re-derives it.
  P-6.6 applies to budgets too: one measurement is a hypothesis. Follow-up on narrowing the spread
  rather than widening the cap: `scripts/BACKLOG.md` → smoke-corpus-slice-dominates-and-varies.

- [x] **smoke-ci-launcher-build-dominates-the-job** — CI now SKIPS the ~3.5 min launcher build when
  nothing that feeds it changed. `scripts/launcher-input-digest` hashes the build's inputs (an
  EXCLUSION list, so a mistake costs a rebuild rather than a stale toolchain); `installBin` writes it
  to `bin/lib/.build-digest`; `smoke.yml` keys a `bin/` cache on it with NO restore-keys; and both
  `scripts/smoke-ci` and `bin/ssc`'s STALE warning now ask about INPUTS instead of the HEAD sha — so
  a docs-only commit stops claiming the toolchain is stale. Gated by
  `tests/e2e/launcher-digest-gate.sh`, A/B'd in both directions, and in the smoke suite.
  The exclusion is by BOARD NAME, never by `.md` extension: `src/main/resources/templates/*/README.md`
  is packaged into the jar, so an extension rule would have dropped a real build input — the gate
  pins that case, and the two board fixtures must be tracked files or it refuses to run.
  Also documented at last: POLICY.md P-1.4 (the rule), AGENTS.md "Before the push" (the mechanics),
  README.md "Running the tests" (for humans).

- [x] **smoke-ci-in-ssc** — the push path is `scripts/smoke-ci`: 17 checks + a 13-case corpus slice,
  written in `.ssc` and run on the v2 native lane, with a per-module rollup and an enforced budget.
  Same runner locally and on GitHub (`.github/workflows/smoke.yml`); `validate`/`conformance`/
  `conformance-extras` moved off push; full suite replayable with `scripts/full-ci`, which reads its
  steps out of `ci.yml` rather than copying them.
  **CI-VERIFIED GREEN** (run 30545125102): 17/17 checks, 250.2 s of the 330 s budget, job 9.4 min.
  Three things only CI could measure, each fixed rather than accommodated: the corpus slice cost
  233.9 s on a runner vs 68 s locally (fixed with `SSC_CONF_WARM_JVM=1`, -51% there); the first
  budget guess of 300 s was wrong and the guard caught it at 378.5 s; `cancel-in-progress: true`
  produced four cancelled runs and zero verdicts before being changed to `false`.
  Found and fixed on the way: no `exec` native on the v2 lane at all, and `scripts/ci-status`
  demanding jobs that had just moved off push (would have reported every green push run as RED).
