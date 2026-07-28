# Spec: build / test / conformance / CI — RAM budget and speed

Written 2026-07-28 (`build-ram-budget-and-speed`) after Sergiy reported "было переполнение памяти и
они работают медленно". Supersedes nothing; it is the host-level counterpart to
[`conformance-perf.md`](conformance-perf.md), which covers the conformance runner specifically.

Everything below that says "measured" was measured on 2026-07-28 on the 36 GB dev host, with the
command named. Everything that is a hypothesis says so.

---

## 1. Measured: the memory ceiling we believed in does not exist

`~/.local/bin/jvm-mem-guard.sh` (launchd `com.sergiy.jvm-mem-guard`, every 20 s) is the host's
protection against the 2026-07-20 OOM kernel panic. It has never fired.

```
$ ls -la ~/Library/Logs/jvm-mem-guard.log
-rw-r--r--  1 sergiy  staff  0 Jul 21 06:17          # 0 bytes, one week later
```

Zero bytes **including through the 2026-07-28 03:00 event that recorded 139,831 pageouts and 526 MB
of swap in use.** The reason is in its first two executable lines:

```bash
fp=$(sysctl -n kern.memorystatus_level)     # measured: 93 on an idle host
[ "$fp" -ge "$REAP_PCT" ] && exit 0         # REAP_PCT=25
```

`kern.memorystatus_level` is a **jetsam** indicator. macOS holds it high while it compresses and
swaps; by the time it crosses 25 the machine is already in the zone where the 2026-07-20 watchdog
panic happened. The guard's cheap fast path reads "healthy" the entire way down.

Its `BUILD_RE` has a second, independent hole: it matches `sbt|bloop|scala-cli|ForkMain|zinc|metals`.
The processes behind the 2026-07-28 event were the conformance runner's per-case `java … -jar
bin/ssc.jar` forks and its `node` JS-lane forks. Neither matches. Even had the guard fired, it could
not have shed the load that caused the event.

**This is the AGENTS.md "apparatus fails GREEN" pattern in its purest form:** a safety mechanism
whose silence was read as "nothing happened", when it actually meant "nothing was ever looked at".

### What replaces the signal

`scripts/build-ram-report` and `scripts/build-guard` both read **available memory** — on macOS
`free + inactive + speculative + purgeable` from `vm_stat`, on Linux `MemAvailable` — and report swap
and compressor alongside. Those move early and they moved during the event. `memorystatus_level` is
still *printed* by the report, precisely so that the divergence between the two stays visible instead
of being rediscovered a third time.

## 2. Measured: nothing bounds the aggregate, and the aggregate is what overflows

Per-process caps are all sane. The sum is the problem:

| holder | per instance | ×13 worktrees |
|---|---|---|
| sbt server (`.jvmopts -Xmx4G`) | 4 GB declared, **2.7 GB resident idle**, 5.1 GB observed in the wild | 35–66 GB |
| forked test JVMs (`build.sbt`, `Tags.limit(Tags.Test, 4)` × `-Xmx2g`) | 8 GB declared | 104 GB |
| launchd bloop daemon (`JDK_JAVA_OPTIONS=-Xmx12g`) | 12 GB declared | 12 GB |

≈ **156 GB of declared ceiling on a 36 GB host.** Both OOM events are that arithmetic arriving.

Note `RESIDENT > Xmx` for a real server (5,114 MB resident against a 4,096 MB heap cap): metaspace,
code cache, GC structures and thread stacks are outside `-Xmx`. Budget a build JVM at ~1.25× its
declared heap, which is what `SSC_BUILD_SLOT_MB` (6 GB per slot) allows for.

### The largest uncapped surface: every `ssc` fork

Caught live by `build-ram-report` at 2026-07-28 05:57 while sibling agents were mid-run:

```
ROLE             PID    RSS_MB     XMX_MB  WORKTREE
ssc-fork       50034         0       9216  ?
ssc-fork       50014       126       9216  ?
ssc-fork       50013         1       9216  ?
ssc-fork       50009       150       9216  ?
ssc-fork       50008         1       9216  ?
ssc-fork       49981      1174       9216  ?
ssc-fork       42987      2091       9216  scalascript-wt-f-delegation-reason-census
…
  RESIDENT  :  14442 MB
  DECLARED  :  94208 MB       ← 2.6x the host
  HOST      :  36864 MB
  pressure  : swap_used_mb=1 compressor_mb=6943 pageouts=1719 memorystatus_level=74%
```

`9216 MB` is not a configured value — it is the JVM's ergonomic default, ¼ of 36 GB. The `bin/ssc`
launcher template (`build.sbt`) passes `-Xss64m` and **no `-Xmx`**, so every one of the ~1,669 cold
`ssc` starts a corpus contract run performs is individually entitled to 9 GB. Six of them were live
in that snapshot, declaring 55 GB between them.

Two things to take from that snapshot. First, it is the memory event in progress: compressor already
at 6.9 GB, pageouts started. Second — `memorystatus_level` reads **74 %**. Healthy. `jvm-mem-guard`
would not have logged a line, exactly as it did not on 2026-07-28 03:00.

`build-guard` caps these through `JDK_JAVA_OPTIONS`, which those forks *do* honour precisely because
they set no `-Xmx` of their own — and that is exactly why the durable fix is **not** an `-Xmx` in the
launcher template: adding one would beat `JDK_JAVA_OPTIONS` and break the cap that currently works.
What to do instead, and what to measure first, is `ssc-fork-heap-entitlement` in `BACKLOG.md`.

## 3. Measured: idle servers never give the memory back — and now they do

`scripts/build-ram-idle-ab`, same build, same command, RSS + committed heap every 15 s over 180 idle
seconds:

| arm | idle RSS | committed heap | when it drops |
|---|---|---|---|
| baseline (`-Xmx4G -XX:+UseG1GC`) | 2698 MB | 2108 MB | never — flat for the full 180 s |
| `+ G1PeriodicGCInterval=15000` | 1578 MB | 1160 MB | T+30 s |
| `+ G1PeriodicGCInterval=60000` | 1553 MB | 1158 MB | T+120 s |

**−1145 MB per idle server, −42 %.** Both intervals converge on the same floor, so 60 s ships: same
reclaim, 4× fewer idle full GCs. Across 13 live worktrees that is ~15 GB of a 36 GB host handed back.

`-XX:-G1PeriodicGCInvokesConcurrent` is load-bearing and is the easy thing to drop by accident: with
the default `+`, G1 runs a *concurrent* cycle, which collects but does not resize the heap — RSS
never falls and the entire change measures as a no-op. The **full** GC is what shrinks.

Periodic GC only fires when no GC happened during the interval, so an actively compiling server never
pays for it.

## 4. Prevention: `scripts/build-guard`

`scripts/conformance` already solved admission control for one entrypoint. `build-guard` is the same
counting semaphore (atomic `mkdir`, shared dir, stale-slot reclaim by dead PID / age) with two
differences:

1. **Slots are derived from host RAM**, `(HOST − RESERVE) / SLOT_MB`, = 4 on this host. A hardcoded
   constant is wrong on the next machine, and "1" (the conformance default) is too strict for a
   general build limit.
2. **A slot is not granted while memory is actually short**, and is *yielded* rather than held while
   waiting — holding it would block a build that could have run once memory freed. A free slot and
   free memory are different facts, and it was the second one that ran out.

`scripts/sbtc` routes through it, because sbt is the most-invoked heavy entrypoint in the repo and a
wrapper nobody types protects nothing. `SSC_BUILD_NO_GUARD=1` opts out.

## 5. Measured: CI is not slow, it is absent

Last 100 `ci.yml` runs: **83 cancelled · 4 failure · 0 success · 13 unfinished.**

Where the wall clock goes, from run 30305919516 — the last one that reached completion:

| job | total | dominant step |
|---|---|---|
| Conformance Suite | 37.7 min | `Run conformance tests` **33.6 min (89 %)** |
| sbt — compile and test | 75.6 min | negative-toolchain gate **58.1 min (77 %)** |
| Validate | 0.6 min | — |
| Lint | 0.4 min | — |

The push interval on `main` is ~3–7 min. GitHub keeps at most one *pending* run per concurrency
group, so a 38-minute verdict job means roughly one commit in ten is ever tested and the rest are
superseded. `cancelled` reads as RED (MILESTONES.md), so the apparatus manufactures reds for commits
that are fine.

### The fix: shard the verdict path

`tests/conformance/run.sc` gains `--shard i/N` — round-robin by index, the same convention (and for
the same reason) as `contract.sc`: the corpus is name-sorted and slow cases cluster by name, so
contiguous blocks give wildly uneven shards. Measured partition on 345 cases: 87/86/86/86.

`ci.yml` runs the conformance job as a 4-way matrix, and the steps that do **not** shard (examples on
three backends, `ssc check`, the launcher smokes — ~1 min) move to a sibling `conformance-extras` job
so they are not repeated four times. Expected verdict path **37.7 min → ~13 min** (setup + 3.6 min
assembly + 33.6/4).

### The sharding is proven, not asserted

Sharding a correctness suite has exactly one catastrophic failure mode and it fails GREEN: a scheme
that drops cases reports "all tests passed" over less than it claims. So
`tests/e2e/build-conformance-shard-gate.sh` runs the real runner N+1 times and byte-compares:

```
union(shard 0/N … shard N-1/N)  ==  no-shard
```

plus disjointness, balance (spread ≤ 1), rejection of an out-of-range `i/N`, and — the specific
regression that the old positional-argument filter would have caused — that `0/4` is never mistaken
for the corpus *directory*, which would have made a shard silently test nothing.

It runs in the 34-second `Validate` job, not behind the 3.6-minute assembly, because `--list`
enumerates the corpus without needing a built launcher.

## 5b. Confirmed in the wild, same session (2026-07-28 06:26)

Three hours of sibling-agent work later, with the changes live:

**The periodic GC works on other agents' servers, not just the A/B rig.** Same PID, two observations:
`sbt-server 43453` was **2,308 MB** at 05:57 and **33 MB** at 06:26. Two more sat at 50 MB and 35 MB.
Before this change the floor for an sbt server that had loaded the build was ~2,700 MB.

**And `memorystatus_level` still lies.** At that moment the host had **630 MB of swap in use and an
11.3 GB compressor** — genuinely paging — while `kern.memorystatus_level` read **62 %**. Against
`jvm-mem-guard`'s `REAP_PCT=25` fast path, that is "healthy": it would not have logged a line, in a
state that is objectively worse than idle. This is the second independent observation of §1, now with
swap actually in use rather than inferred after the fact.

**The reaper's relief is measurable.** Two servers whose worktrees had been deleted (`rm-worktree`
leaked them, exactly as AGENTS.md warns) were reaped with `scripts/kill-stale-builders --kill`:

| | before | after |
|---|---|---|
| swap in use | 630 MB | **227 MB** |
| compressor | 11,299 MB | **8,357 MB** |
| `memorystatus_level` | 63 % | 71 % |

**What did NOT improve, and why it is the right next item.** `DECLARED` stayed ~102 GB, because it is
dominated by the uncapped `ssc` forks, one of which was resident at **8,090 MB** against its 9,216 MB
ergonomic ceiling — a single conformance fork holding 22 % of the host. Bounding *that* is
`ssc-fork-heap-entitlement` in `BACKLOG.md`, and this is the measurement that says it is the top
one — though note the fix is NOT simply an `-Xmx` in the template: these forks honour the harness's
`JDK_JAVA_OPTIONS` cap precisely BECAUSE they set none, so adding one would break the mechanism that
already works. That entry says what to measure first.

## 6. Known-remaining, deliberately not done here

Queued in `BACKLOG.md`; each falls inside another agent's live claim or is a separate arc.

- **The 58-min negative-toolchain gate** (`tests/e2e/v21-negative-toolchain-release-gate.sh`) is 77 %
  of the 75-min `sbt` job. It re-lowers the full corpus through F twice. Same shardability argument
  as above, but the file belongs to the v21 release-gate arc.
- **`build.sbt` test-fork budget** — `Tags.limit(Tags.Test, 4)` × `-Xmx2g` declares 8 GB per
  worktree with no host-wide coordination. Held by `uniml-production-completion`.
- **The launchd bloop daemon** pinned at `-Xmx12g` in `~/Library/LaunchAgents/bloop.compilation.daemon.plist`,
  outside the repo. It should carry the same periodic-GC flags as `.jvmopts`.
- **`jvm-mem-guard.sh` itself** lives in `~/.local/bin`, outside version control, and its signal is
  wrong (§1). It should move into the repo and read available memory.
- **`~/.local/bin/kill-stale-builders`** is a *copy* of `scripts/kill-stale-builders` that launchd
  runs once a day at 03:00. It is byte-identical today and will not stay that way; launchd should
  point at the repo file, hourly, with `--idle`.

## One line

The per-process caps were never the problem: the **sum** had no ceiling, the guard watching for it
read a sysctl that stays green through an OOM, and the CI job meant to catch regressions was
superseded before it could run. Print the sum, bound admission by real free memory, let idle servers
shrink, and shard the verdict path.
