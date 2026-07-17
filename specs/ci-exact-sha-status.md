# Exact-SHA CI Status Guard

## Overview

ScalaScript work is pushed directly to `origin/main`, while the full GitHub Actions suite is much
slower than the focused local gates. Local success therefore cannot prove that a landed revision is
green. This guard makes the CI state of one exact commit a cheap, named observable in the normal
coordination flow so a red or still-pending run cannot be silently reported as complete.

## Interface

`scripts/ci-status [--sha <git-sha>]` inspects the push-triggered `ci.yml` run for exactly one SHA.
Without `--sha`, it resolves `origin/main`. It requires the GitHub CLI and never starts, cancels,
reruns, or otherwise mutates a workflow.

The required jobs are:

- `Lint Markdown`
- `Validate ScalaScript`
- `Conformance Suite`
- `sbt — compile and test`

The first output line is one of `CI GREEN`, `CI RED`, `CI PENDING`, or `CI UNKNOWN`, followed by the
SHA and run URL when available. Every discovered required job is printed with its GitHub status and
conclusion. Exit codes are `0` for complete green, `1` for a completed red/incomplete job set, and
`2` when the exact run is pending, absent, or cannot be queried.

`scripts/coord-status` invokes the guard for its authoritative remote SHA and displays the result,
but retains its own read-only status output even when CI is red or pending. Project workflow rules
require exact-SHA green evidence before a task is called CI-green or its final claim is released.
The README exposes the ordinary `main` workflow badge as the human-visible companion signal.

## Behavior

- [x] A completed exact-SHA run with all four required jobs successful exits `0` and prints
      `CI GREEN` plus all four job results.
- [x] Any failed, cancelled, timed-out, or missing required job in a completed run exits `1`, prints
      `CI RED`, and names the non-successful/missing jobs.
- [x] A queued or in-progress exact-SHA run exits `2`, prints `CI PENDING`, and never treats an older
      green run as evidence for the requested SHA.
- [x] An absent run, unavailable GitHub CLI, authentication/network failure, or malformed response
      exits `2` with a diagnostic `CI UNKNOWN` rather than a false green.
- [x] The shell regression uses a fake GitHub CLI to cover green, red, pending, missing-job,
      no-run, and query-failure cases and prints expected/actual output on mismatch.
- [x] `scripts/coord-status` shows the exact remote SHA's CI result without losing claims/worktrees
      output when the guard returns non-zero.
- [ ] The guard and README badge run in CI so their own wiring cannot silently rot.

## Out of scope

- Branch protection or changing the project's direct-to-`main` delivery model.
- Cancelling, serializing, waiting for, or rerunning GitHub workflows.
- Treating a newer or older commit's result as proof for the requested SHA.
- Replacing focused local tests or fixing failures reported by the workflow.

## Design

The script asks `gh run list` for `ci.yml`, branch `main`, event `push`, and the exact SHA, then asks
`gh run view` for that run's job set. It compares the four names only after collecting the real
results. Tests replace the `gh` executable through `SSC_CI_GH`; production defaults to `gh`.

`coord-status` treats the guard as an informative child command: a red/pending result is loud but
does not abort claim/worktree inspection. Completion policy consumes the same exit code explicitly.

## Decisions

- **Exact SHA, not "latest CI"** — chosen because frequent parallel pushes make the latest run a
  different program. Rejected: accepting the latest completed workflow (can falsely bless an
  untested descendant or predecessor).
- **Visible status plus completion rule** — chosen because CI repair work must remain claimable even
  while main is red. Rejected: making `coord-status` itself fail fast (would hide the coordination
  information needed to repair CI).
- **One workflow query, no GitHub mutation** — chosen to keep the guard safe in every agent session.
  Rejected: automatic reruns/cancellation and branch-protection changes (different operational
  policy and authority).

## Results

Implementation `c43d8f523` adds the guard, fake-gh matrix, `coord-status` integration, and CI step;
documentation `0fe5e5f0d` adds the badge and release rule. The fixture gate covers six result classes
plus the non-aborting coordination integration and passes. A real authenticated query for
`f99150aa8` returned `CI PENDING` with all four queued jobs and the exact run URL; real
`coord-status --no-fetch` printed the same result followed by active claims.

The first fixture-only version exposed an apparatus defect before landing: fake `gh` ignored the
supplied `--jq`, while real gojq rejected its quote escaping. That proxy/real mismatch is recorded in
`BUGS.md`; the corrected real query is now part of verification. The final CI-wiring checkbox remains
open until a workflow run containing the implementation completes.
