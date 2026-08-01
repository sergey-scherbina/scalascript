# Build, CI and coordination tooling — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `scripts/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] bench-seed-type — `ssc bench`'s wrapper detected the anti-fold `seed` by NAME and hardcoded a
  `Long`, so the corpus's one `def workload(seed: Int)` row was a type error on jvm AND js and read
  `n/a` on both — a harness defect wearing a backend's clothes, and the second of its kind here. Now
  reads the declared type; the JVM seed stays the opaque atomic load with only its width adapted.
  Gate `tests/e2e/bench-seed-type-gate.sh` in smoke-ci (2 of 6 cells red before the fix).
  var-expr-init-int, all four lanes: ssc 3.20 · jvm 3.41 · js 51.6 · v2 145.2 ms/iter.

- [x] **negtc-gate-shard-reduce** — the `ci.yml` wiring, the last piece (the gate side landed
      2026-07-30). An N-way `--sweeps-only --shard i/N` matrix uploading its two partial TSVs, then
      one `needs:` job that downloads, merges with `scripts/negtc-merge-reports` and runs `--reduce`.
      The gate runs SEQUENTIALLY ahead of `Test via sbt`, so the win is mostly NOT the sharding:
      measured on run 30597944542 (the last full run to reach a verdict) gate 3236 s + test 8088 s
      inside a 208-min job, which is the suite's critical path. Moving it out takes ~54 min off that
      job; the 4-way split then buys the negtc verdict at ~35-40 min against ~61 standalone.
      Landed 7aac5b9eb, after the row below — the step had been red since 06:25 that day, so until
      that was fixed the wiring could not be verified green.
      ⚠️ Do not read the 53-min `ci.yml` runs of 2026-07-31 as a fast suite: they died IN this gate
      and never reached `sbt test`.

- [x] **bc-parity-explicit-manifest-second-copy** — `scripts/bc-parity-sweep` carries a SECOND,
      weaker copy of the explicit-lane manifest contract, frozen at `total != 15` on 2026-07-12 and
      never updated. The manifest's OWNER, `tests/e2e/v21-explicit-lanes-gate.sh`, was maintained
      (15 -> 13 -> 12 today, MCP then NFC joining the standard graph) and is green in the same job
      where the duplicate refuses with a wordless `invalid explicit-lane manifest` and exit 2.
      Two gates over one fixture disagreeing — which is the exact pathology bc-parity-sweep's own
      comments complain about. Delete the duplicated membership assertion, keep what this script
      actually consumes, and name the condition that refused. DONE 1888a8d2b, with
      `tests/e2e/negtc-manifest-contract-gate.sh` in `scripts/smoke-ci` — verified red against the
      unfixed script (5 of 7 assertions).

- [~] **ci-crossbackend-differential-runtime** — first slice landed: ScalaTest's `assert(cond, clue)`
      evaluates the CLUE EAGERLY, and 31 sites in `CrossBackendPropertyTest` put a cold
      `scala-cli` run in both the condition and the clue. The slowest thing in CI ran TWICE on the
      GREEN path. Hoisted into vals; sharding the test phase is still open.

- [~] smoke-budget-drift — MEASURED, and the premise was wrong: nothing grew. Per check between
      the fastest and slowest of eight green runs the median ratio is x1.39 and the three checks
      that did NOT move are the ones dominated by waiting rather than CPU — i.e. the whole run
      inflates together, which this suite's own budget section already names as "a loaded host, not
      a slow suite". The real problem is that a fixed second-count cannot tell a slow day from a
      grown suite. Step 1 landed: a host-speed probe (100 process spawns, ~1.15 s locally, 2 %
      spread across repeats), INFORMATIONAL only. Step 2 waits for CI samples — a reference fitted
      to local numbers is exactly how the 300 s cap was set and went red on its first run.
      Original framing kept below for the record. Four green runs measured 358.3 / 269.6 / 318.3 / 359.8 s
      against a 420 s cap, where the same suite ran 194-227 s a day earlier. One of those was
      within 60 s of failing WITH EVERY CHECK GREEN — which is exactly the mode that made the
      push path useless before this suite existed. Read the per-check costs between a fast run
      and a slow one; then make the grown check faster or move it off the push path. NOT raise
      the number: the file's own rule is that the budget is the thing it protects.

- [x] **corpus-breadth-slice Bloop-server timeout — fixed and MEASURED closed** (1bd6b0984).
  `--server=false` on the parent scala-cli in both corpus checks and in the lanes gate: the breadth
  check has no JVM lane, so the build server was pure overhead and, on a 2-core runner, a coin flip
  (`Future timed out after [30 seconds]`). Six dispatches of one commit: 3/3 red before, **6/0 green
  after**. Separately attributable from the morning's Coursier fix because they were landed apart.
  Also here: the time budget now FAILS on CI and only WARNS locally. It is sized for a dedicated
  runner; on a dev host at load 19 `ci-status-guard` alone took 78 s against 4 s on CI, and failing
  on that is what made me push three times without a green local run.

- [x] **reaper-aborts-when-a-builder-exits-mid-scan** — second push-path flake found the same day.
  `kill-stale-builders` died whenever a builder exited between the `ps` snapshot and the per-pid
  `lsof`/`ps`; `set -euo pipefail` turned a routine race into a red gate, and the author's own
  "exited on its own" guards were unreachable. Three call sites fixed, deterministic regression test
  added (decoy exits inside the sample window), A/B'd 3/3.
  ⚠ Two convincing diagnoses were WRONG and were killed by testing them: empty-operand arithmetic
  does not abort, and the fake `stat` on PATH does not leak. The answer came from un-discarding the
  reaper's stderr — a check that throws away the output of what it checks cannot explain itself.

- [x] **corpus-breadth-slice flake — DIAGNOSED and fixed** (0767755cb). Cause: `Cache Coursier/sbt`
  was made conditional on a toolchain-cache miss in the launcher-cache change; coursier is
  scala-cli's RUN-time artifact cache, so every cache-HIT run re-downloaded Bloop from Maven and that
  download flakes. 3 of 15 runs. Caught deliberately with four `gh workflow run` dispatches rather
  than waited for. Fixed as ONE change so attribution stays clean.

- [x] **smoke-runner-truncates-the-cause** — the suite printed the LAST 8 lines of a failing check,
  which for a stack trace is eight frames of JVM plumbing; the error message is at the TOP and was
  discarded. Two CI failures (30606728752, 30606076538) were therefore undiagnosable and are not
  reproducible on demand. Now: exit code + first AND last 10 lines + the omitted count. The flake
  itself is `scripts/BUGS.md` → corpus-breadth-slice-crashes-scala-cli-on-ci, with the candidate
  causes ordered by what the evidence would separate — and an explicit "do not fix this by retrying".

- [x] **smoke-conformance-shards-partition-costs-49s** — one enumeration instead of seven.
  `run.sc --list --shard-all N` emits `# all` plus every shard section in ONE process; two
  invocations remain and are irreducible (a `--shard 9/4` that must FAIL, and the real `--shard 0/N`
  path). MEASURED 16.4 -> 7.0 s locally; it was 49 s on CI.
  ⚠ Two tempting fixes are wrong, both written into the commit: computing the shards in bash destroys
  the gate (it would test a re-implementation of `idx % N` against itself), and reusing the scala-cli
  server means stopping a HOST-WIDE bloop daemon a sibling agent is using — do not pkill it.
  The refactor's own risk, a second copy of the shard rule, is closed by a single `shardSlice` in
  run.sc plus check 5 asserting `--shard-all` shard 0 == `--shard 0/N` byte-for-byte.

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
