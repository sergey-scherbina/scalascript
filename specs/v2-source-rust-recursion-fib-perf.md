# v2 Source Rust Recursion Fib Performance

## Overview

This slice improves the Phase-3 v2 Rust source backend performance gate for one
representative workload: `bench/corpus/recursion-fib.ssc`. The v2-backends
harness already exposes honest `v2`, `v2-jvm`, and `v2-rust` columns; JVM
`recursion-fib` is now closed, while Rust still shows a large measured gap on
the same workload.

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
- [ ] The emitted v2 Rust source for `recursion-fib` is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [ ] Any implementation lands one conservative v2 Rust source-backend
      optimization for the recursive function-call shape, preserving existing
      `.ssc` semantics and output.
- [ ] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate remains open unless all
      remaining Rust/source rows are also proven green.
- [ ] Affected semantic/conformance gates for recursion-shaped programs pass,
      backend parity gates covering Rust stay green, and `git diff --check`
      passes.

## Out of Scope

- v2 JVM source backend performance.
- v2 VM JIT/fast-tier performance.
- Rust `arith-loop` anti-fold or harness fairness changes.
- Benchmark workload changes.
- New language constructs, CLI flags, or artifact formats.
- Broad Rust backend rewrites not needed for this measured recursion row.

## Design

Start with measurement and source inspection, mirroring the JVM recursion slice
but not assuming the same fix is correct for Rust. Stage the current worktree
CLI, run the public v2-backends benchmark for `recursion-fib`, and inspect the
exact Rust source emitted by `v2/backend/rust/RustBackend.scala`.

If the generated shape still routes self-recursive calls through generic
closure/vector dispatch, prefer a local source-backend lowering fix that emits a
direct typed Rust helper or otherwise removes the recursion-only dispatch tax
while preserving the existing public backend contract. If inspection shows that
the slow number is caused by Rust compilation/package startup, benchmark
anti-folding, or another measurement artifact, record that finding and stop
rather than landing speculative code.

## Decisions

- **Scope one backend and one workload family first** - chosen because BACKLOG
  asks for one backend/workload slice at a time and the current measured row
  isolates `v2-rust recursion-fib` as a clear remaining source-backend gap.
  Rejected: working on all Rust source-backend rows together, because that
  would mix unrelated ownership, dispatch, and harness questions.
- **Keep the benchmark command fixed** - chosen so before/after numbers are
  comparable with the JVM recursion slice and reproducible by the next agent.
  Rejected: changing `bench/corpus/recursion-fib.ssc`, because that would move
  the gate instead of improving the backend.
- **Do not update the global language spec** - chosen because this slice changes
  backend quality only. The current global spec already requires target-
  independent semantics; no public syntax or semantic contract changes are
  intended.

## Baseline

Pending. Fill after:

```bash
scripts/sbtc "installBin"
scripts/bench v2-backends recursion-fib
```

## Inspection

Pending. Record the emitted Rust recursion shape and dominant overhead
hypothesis before code changes.

## Results

Pending. Fill after implementation and verification with exact commits,
before/after numbers, rejected alternatives, and gates.
