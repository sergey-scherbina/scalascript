# v2 VM Production JIT Gate

## Overview

The v2 VM production performance gate remains red after the bounded recursion
hot-path fixes. This spec scopes the next work as measured, incremental slices
against the representative corpus rows instead of a broad rewrite.

## Interface

No user-facing language or CLI contract changes are intended. Verification uses
the existing production benchmark command:

```bash
./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy
```

Focused before/after checks may use:

```bash
scripts/bench v2-backends arith-loop
bin/ssc --backend v2 bench --warmup-time 100 --reps 3 bench/corpus/arith-loop.ssc
```

## Behavior

- [ ] Reproduce the current four-row v2 VM production-performance baseline in
      this worktree after staging `bin/ssc`.
- [ ] Identify the bridge-generated CoreIR shape for `bench/corpus/arith-loop.ssc`
      and document the exact recognizer scope before changing runtime code.
- [ ] Land one narrow v2 VM fast path for the `arith-loop` Long-cell while shape
      or record why that shape is not safe to optimize in this slice.
- [ ] Verify that existing recursion fixes stay green and the production
      performance checklist remains honest: mark the 2x gate closed only if the
      measured numbers justify it.
- [ ] Run affected unit/backend checks plus
      `tests/conformance/run.sh --only 'litdoc'` before push.

## Out of scope

- Broad bytecode-codegen JIT Phase C from `specs/vm-jit-next.md`.
- `pattern-match-heavy` HOF/foreach rearchitecture; that is a separate slice
  unless a small profile proves the same change helps it.
- Source backend JVM/Rust performance gates; those are tracked by
  `v2-source-backend-production-perf-gates`.

## Design

Start with the largest remaining simple gap: `arith-loop` is still around 40x
slower than the `ssc` column, while the recursion rows already received focused
fast paths. The candidate implementation is an exact-shape recognizer in the v2
VM compiler/runtime for the bridge-lowered local Long-cell loop:

1. local `var` declarations lowered to `lcell.new`;
2. `while i < literalLimit do` lowered to `While`;
3. body is a sequence of `lcell.set(sum, sum + i)` and
   `lcell.set(i, i + 1)`;
4. final expression reads the accumulator cell.

The recognizer must be conservative: if any part of the shape differs, runtime
falls back to the existing interpreter/JIT path. Use the already-present
`FastCode`/Long-cell helpers where possible instead of adding a new public
runtime mode.

## Decisions

- **Start with `arith-loop` closed-form/loop recognition** — chosen because it is
  the largest remaining scalar gap and has a compact, pure shape. Rejected:
  broad bytecode JIT Phase C for this slice because it is multi-day and harder
  to land safely under parallel-agent pressure.
- **Do not claim the Phase-3 performance gate from one local win** — chosen
  because the gate requires representative rows, not a single benchmark.
  Rejected: marking the gate green from `arith-loop` alone.

## Results

Pending implementation.
