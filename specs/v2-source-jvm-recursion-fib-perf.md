# v2 Source JVM Recursion Fib Performance

## Overview

This slice improves the Phase-3 v2 JVM source backend performance gate for one
representative workload: `bench/corpus/recursion-fib.ssc`. The separate-backend
harness already exposes honest `v2`, `v2-jvm`, and `v2-rust` columns; the next
production step is to close one measured backend/workload gap without changing
language semantics or hiding the remaining source-backend work.

## Interface

No user-facing language, CLI, file-format, or benchmark-workload interface
changes are planned. The public verification command for this slice is:

```bash
scripts/bench v2-backends recursion-fib
```

The workload under test remains:

```scalascript
def fib(n: Int): Int =
  if n <= 1 then n
  else fib(n - 1) + fib(n - 2)

def workload(): Int =
  fib(30)
```

## Behavior

- [ ] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      using `scripts/bench v2-backends recursion-fib`.
- [ ] The emitted v2 JVM source for `recursion-fib` is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [ ] The implementation lands one conservative v2 JVM source-backend
      optimization for recursive function-call shape, preserving the existing
      `.ssc` semantics and output.
- [ ] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate is claimed only if the measured
      threshold justifies it.
- [ ] Affected semantic/conformance gates for recursion-shaped programs pass,
      along with `git diff --check`.

## Out of Scope

- v2 Rust source backend performance.
- v2 VM JIT/fast-tier performance.
- Benchmark workload changes or anti-fold strategy changes.
- New language constructs, CLI flags, or artifact formats.
- Broad source-backend rewrites not needed for this measured recursion row.

## Design

Start with measurement, not a guessed rewrite. Stage the current worktree CLI,
run the public v2-backends benchmark for `recursion-fib`, and inspect the exact
Scala source emitted by the v2 JVM source backend. If the generated shape still
routes self-recursive calls through generic closure/lazy-val dispatch, prefer a
local source-backend lowering fix that emits a direct method or otherwise removes
the recursion-only dispatch tax while preserving the existing public backend
contract.

Any optimization must stay narrow enough that the existing interpreter/v2 VM
semantics remain the oracle. If inspection shows that the slow number is caused
by harness cold-start, scala-cli packaging, or another measurement artifact, this
slice should record that finding and stop rather than landing speculative code.

## Decisions

- **Scope one backend and one workload family first** - chosen because BACKLOG
  asks for one backend/workload slice at a time and the current bounded numbers
  isolate `v2-jvm recursion-fib` as a clear production gap. Rejected: working on
  all v2 JVM/Rust source-backend rows together, because that would mix unrelated
  codegen and ownership problems.
- **Keep the benchmark command fixed** - chosen so before/after numbers are
  comparable and reproducible by the next agent. Rejected: changing
  `bench/corpus/recursion-fib.ssc`, because that would move the gate instead of
  improving the backend.
- **Do not update the global language spec** - chosen because this slice changes
  backend quality only. The current global spec already requires target-
  independent semantics and stack-safe recursion; no public syntax or semantic
  contract changes are intended.

## Baseline

Captured on 2026-07-09 from this worktree after staging the CLI:

```bash
scripts/sbtc "installBin"
scripts/bench v2-backends recursion-fib
```

Default harness settings:

```text
Corpus:   recursion-fib
Backends: v2, v2-jvm, v2-rust
Warmup:   2000ms (time-based)
Reps:     100
ssc:      /Users/sergiy/work/my/scalascript-wt-v2-source-jvm-recursion-fib-perf/bin/ssc
```

Result:

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-fib` | 12.9 | 67.5 | 240.2 |

This is better than the older short bounded `v2-jvm=104.5 ms` probe, but it
still leaves `v2-jvm` roughly 5.2x slower than the current v2 VM row for this
single workload and remains a real source-backend production gap.

## Results

Pending. Fill this section after the baseline, implementation, and verification
steps with the exact commands and observed numbers.
