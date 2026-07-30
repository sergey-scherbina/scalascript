# Build, CI and coordination tooling — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `scripts/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] **smoke-ci-in-ssc** — the push path is now `scripts/smoke-ci`: 17 checks + a 13-case corpus
  slice, written in `.ssc` and run on the v2 native lane, 203 s of a 300 s enforced budget. Same
  runner locally and on GitHub (`.github/workflows/smoke.yml`); `validate`/`conformance`/
  `conformance-extras` moved off push, full suite replayable via `scripts/full-ci`. Writing the
  runner in `.ssc` surfaced a missing `exec` native on the v2 lane, fixed in the same batch.
