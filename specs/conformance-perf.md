# Spec: lighter / faster / more reliable conformance tests

Written 2026-07-06 by a rozum-side agent after `tests/conformance` builds saturated shared host RAM and
blocked a co-tenant (rozum) GPU run. Grounded in `build.sbt` + `tests/conformance/run.sc`. Ships one safe,
additive, opt-in piece now (`scripts/conformance`); the rest is the plan for the owning agents.

---

## Problem (measured)

`tests/conformance/run.sc` runs, **per `.ssc` case**, three lanes — INT (a `bin/ssc` JVM), JS (a Node
process), and JVM (a **cold scala-cli Scala-3 compile** + run) — as **separate subprocesses**, iterated
**sequentially**. So a full run is ≈ `3 × corpus` cold process spawns, the JVM lane being a fresh scalac
compile each time. Run bare in ~15 parallel worktrees with **uncapped forked test JVMs** (`ThisBuild /
Test / fork := true` and no `-Xmx` → the JVM default ≈ ¼ RAM ≈ 9 GB ceiling each), the **aggregate** —
sbt daemons (~2.5 GB) + scala-cli/bloop compile servers + forked test JVMs — saturates the host. Killed
daemons refill on the next `sbt`/`scala-cli` invocation.

Root cost drivers: (1) cold fork-per-run, (2) uncapped heaps, (3) subprocess-per-case × 3 lanes,
(4) full-corpus re-run each iteration, (5) worktree fan-out with nothing bounding the total.

## Shipped now — `scripts/conformance` (opt-in, additive, changes no build/runner behaviour)

A canonical, RAM-bounded entrypoint. It (a) bounds how many conformance runs execute **at once
host-wide** via a portable atomic mkdir counting-semaphore in a shared dir (default `MAX=1` = serialize
across worktrees; stale slots reclaimed by dead-PID / age), and (b) caps the child JVM heap by appending
`-Xmx` (last-wins) to `JDK_JAVA_OPTIONS`/`JAVA_OPTS` (default `4g`). Then runs the real `run.sc`, passing
args through. Knobs: `SSC_CONFORMANCE_MAX`, `SSC_TEST_XMX`, `SSC_CONFORMANCE_NO_GUARD`. Fully bash-tested
(semaphore cap, slot release on success/failure, heap-cap-wins, exit-code passthrough). **This alone
stops the host-RAM saturation** — the thing that blocked the co-tenant — without touching anyone's builds.
Adopt by running conformance through it; enforce via a pre-commit / CI hook if desired.

## Plan (owning agents — needs scala-cli/sbt to implement + verify)

Ordered by value / effort:

- **F1 — affected-only (biggest iteration win).** Add `run.sc --only <glob|file…>` (and/or a
  change→case map) so the fix→test loop runs just the touched cases, not the full corpus. Full corpus
  stays for CI. A localized fix then tests in seconds and touches a fraction of RAM/CPU.
- **F2 — memoize by content hash.** Skip a case whose `(input .ssc, ssc/compiler version, expected)` hash
  is unchanged since the last green. On a localized change the unchanged 99% skip.
- **L1 — heap cap in the build itself.** Give the forked test JVMs a sane default `-Xmx` instead of the
  ~9 GB inherited default — e.g. an env-gated `ThisBuild / Test / javaOptions ++= sys.env.get("SSC_TEST_XMX")…`
  so it is off by default and the wrapper/CI can set it. (Measure real peak first.)
- **F3 — warm runner (replace fork-per-run).** Hold a resident JVM with the compiler loaded + JIT-warmed
  (a small conformance server / BSP session); for the JVM lane, reuse one warm compiler instead of a cold
  scala-cli compile per case. `conformance / Test / fork := false` if the suite is pure.
- **F4 — batch, don't subprocess-per-case.** Compile the (affected) corpus with one loaded compiler
  in-process and run in one JVM per lane, rather than a process per case.
- **L2/L3 — shared build server + bounded parallel workers.** One bloop/BSP server for all worktrees; a
  bounded warm worker pool (`min(cores, RAM/footprint)`) for parallelism that stays within RAM.

## Reliability angle

The forked-JVM-under-RAM-pressure timeout is a real flake class (it is exactly how the co-tenant run was
refused — a correct build starved by the host). The semaphore + heap cap remove resource contention as a
flake source, so a red is a real red. Longer term, isolation via a classloader boundary / worker pool
(instead of a cold fork per test) also removes cold-JVM GC/timing variance.

## One-line
Conformance is expensive because it **cold-forks big-heap JVMs and re-runs the full corpus × 3 lanes per
iteration, times ~15 worktrees, unbounded.** Bound concurrency + cap heap (shipped, today) → run
**affected-only + memoized** in a **warm** runner (this week) → share one build server (steady-state).
