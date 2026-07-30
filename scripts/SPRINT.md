# Build, CI and coordination tooling — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `scripts/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

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