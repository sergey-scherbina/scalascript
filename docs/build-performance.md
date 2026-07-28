# Build performance and host RAM — the operator's page

Design and measurements: [`specs/build-ram-budget.md`](../specs/build-ram-budget.md).
Conformance-runner specifics: [`specs/conformance-perf.md`](../specs/conformance-perf.md).

This page is the short version: what to run, what the numbers mean, what to do when the machine
starts swapping.

---

## The one-line model

Every per-process memory cap in this repo is fine. **The sum has no ceiling**, and with a dozen
parallel worktree agents the sum is what exhausts the host — twice now (2026-07-20 kernel panic,
2026-07-28 139,831 pageouts).

## When the machine feels slow, run this first

```bash
scripts/build-ram-report
```

```
ROLE             PID    RSS_MB     XMX_MB  WORKTREE
ssc-fork       49981      1174       9216  ?
sbt-server     47487      4123       4096  scalascript-wt-uniml-production-completion
…
  RESIDENT  :  14442 MB  (what build tooling holds now)
  DECLARED  :  94208 MB  (what it may grow to — the number that overflows)
  HOST      :  36864 MB
  pressure  : swap_used_mb=1 compressor_mb=6943 pageouts=1719 memorystatus_level=74%
```

Read it in this order:

| line | means |
|---|---|
| `DECLARED` > `HOST` | the host has promised more memory than it has. Not yet a problem; it is the precondition for one. |
| `compressor_mb` climbing, `pageouts` non-zero | it has *become* a problem. macOS is compressing and paging to keep the promise. |
| `memorystatus_level` | **ignore for diagnosis.** Measured at 74–93 % through both OOM events. It is a jetsam indicator, not a pressure gauge — this is exactly why `jvm-mem-guard` has a 0-byte log. |
| `XMX_MB = 9216` | an **uncapped** JVM: no `-Xmx`, so it took the ergonomic ¼-of-RAM default. Every `bin/ssc` fork is one of these. |

## Getting memory back

```bash
scripts/kill-stale-builders                    # dry run: orphaned daemons (worktree deleted)
scripts/kill-stale-builders --kill
scripts/kill-stale-builders --idle 30          # dry run: ALSO daemons nobody is building in
scripts/kill-stale-builders --idle 30 --kill
```

Idleness is measured as **CPU time across a sample window**, not wall time, so a long compile can
never be mistaken for an idle server. Nothing is lost by killing one — the next `sbt` starts a fresh
server and pays the ~9–15 s build load again.

`scripts/rm-worktree <name>` already does this for the worktree it removes; use it rather than bare
`git worktree remove`, which leaks the sbt server.

## Not exhausting it in the first place

```bash
scripts/sbtc "core/test"                  # already guarded — this is the normal path
scripts/build-guard -- <any build command>
scripts/build-guard --print               # show the computed budget
scripts/conformance                       # the conformance-specific equivalent
```

`build-guard` admits at most `(HOST − 8 GB reserve) / 6 GB` concurrent guarded builds host-wide — 4
on a 36 GB machine — and refuses to start one while available memory is below 3 GB. It also appends
an `-Xmx` for children, which is what caps the otherwise-uncapped `ssc` forks.

| env | default | meaning |
|---|---|---|
| `SSC_BUILD_SLOTS` | derived | override the concurrent-build limit |
| `SSC_BUILD_XMX` | `2g` | heap cap appended for child JVMs |
| `SSC_BUILD_MIN_FREE_MB` | `3072` | refuse to start below this much available RAM (`0` disables) |
| `SSC_BUILD_NO_GUARD` | — | `=1` bypasses admission entirely (the heap cap still applies) |

If `sbtc` says *"N guarded build(s) already running host-wide — waiting…"*, that is the guard doing
its job. Watch with `scripts/build-ram-report --watch 15`.

## Why idle servers cost less than they used to

`.jvmopts` carries JEP 346 periodic GC. Measured with `scripts/build-ram-idle-ab`:

| arm | idle RSS | committed heap |
|---|---|---|
| baseline | 2698 MB | 2108 MB |
| periodic GC @60 s | **1553 MB** | 1158 MB |

−1145 MB per idle server (−42 %), ~15 GB across 13 worktrees. An actively compiling server is
unaffected: periodic GC only fires when no GC happened during the interval.

## The host guard (installed from the repo)

```bash
scripts/build-ram-guard --explain          # current reading and which tier it selects
scripts/build-ram-guard --self-test        # tier table + invariants, kills nothing
scripts/build-guards-install --status
scripts/build-guards-install --install     # dry-run by default; this edits launchd
```

Runs from launchd every 20 s. It escalates cheapest-to-lose first and stops as soon as the host
recovers:

| tier | what it kills | when |
|---|---|---|
| T1 | builders whose worktree was deleted | available < 8 GB **or** any thrashing |
| T2 | idle sbt/bloop servers (no CPU in the sample) | still short after T1 |
| T3 | the heaviest build JVM, repeatedly | available < 3 GB **and** actively paging |

Only T3 can interrupt running work, and it needs **both** conditions — low memory alone is the false
alarm that makes a guard untrustworthy enough to get disabled.

**Two liveness signals, deliberately separate.** The action log
(`~/Library/Logs/build-ram-guard.log`) records decisions; the state file
(`$TMPDIR/ssc-build-ram-guard.state`) carries a tick counter that advances every run. The predecessor
had neither, so its 0-byte log was indistinguishable from a dead process — and it *was* dead in
effect for a week, across two OOM events. If you want to know whether the guard is alive right now:

```bash
cat "${TMPDIR}/ssc-build-ram-guard.state"   # "<pageouts> <tick>" — tick must advance every 20 s
```

**Do not diagnose memory pressure from `kern.memorystatus_level`.** Measured: 93 % idle, 74 %
mid-event, 62 % while the host held 630 MB of swap and an 11.3 GB compressor. The guard reports it
and never triggers on it.

**The hourly reaper now uses `--idle 30 --kill`**, so an sbt server nobody has used for 30 minutes
gets stopped. Nothing is lost — the next `sbt` starts a fresh one and pays the ~9-15 s build load.

## Fast local loops

| want | command |
|---|---|
| repeated sbt commands | `scripts/sbtc "<cmd>"` — warm server, <1 s vs ~8 s cold |
| just the cases you touched | `tests/conformance/run.sh --only 'json*,optics-*'` |
| force a re-run of green cases | add `--no-memo` |
| one slice of the corpus | `--shard 0/4` (round-robin; `--list` prints the slice) |
| the RAM-bounded full corpus | `scripts/conformance` |

## CI shape, and why

| job | on | budget |
|---|---|---|
| Lint Markdown | push | 10 min (~0.4 min actual) |
| Validate ScalaScript | push | 15 min (~0.6 min) — also runs both gates below |
| **Conformance shard i/4** | push | 25 min (~13 min expected, was 37.7 unsharded) |
| Examples and launcher smokes | push | 20 min |
| sbt — compile and test | **schedule only** | 300 min (~76–196 min) |

Two gates protect the arrangement itself:

- `tests/e2e/build-conformance-shard-gate.sh` — byte-compares `union(shard 0/4 … 3/4)` against the
  unsharded corpus listing. A shard scheme that drops cases fails GREEN; this is what makes that
  impossible.
- `tests/e2e/build-ram-budget-gate.sh` — proves the semaphore serializes, the heap cap beats an
  inherited `-Xmx12g`, and a failing command still releases its slot.

**If a CI run shows `cancelled`, that is RED, not neutral** — a job timeout surfaces as `cancelled`,
which is how the corpus contract went 13 runs with zero verdicts while reading as benign.
