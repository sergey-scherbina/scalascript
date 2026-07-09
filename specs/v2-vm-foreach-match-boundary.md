# v2 VM Foreach/Match Boundary

## Overview

`v2-vm-pattern-match-heavy-fast-tier` reduced the v2 VM
`pattern-match-heavy` row from 35.1 ms to 16.4-17.0 ms by removing compact
match-arm env allocation, but the row is still far outside the Phase-3 2x
production gate. This slice inspects the remaining boundary between the
bridge-lowered `foreach` inline lambda, the `cell.set(total, total + area(s))`
body, and the `area` ADT `match`, then lands at most one further conservative
VM/FastCode optimization if a safe local shape is present.

## Interface

No user-facing language, CLI, or benchmark contract changes are intended.
Verification uses the existing production commands:

```bash
scripts/sbtc "installBin"
./bench.sh --warmup-time 500 --reps 20 pattern-match-heavy
./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy
tests/conformance/run.sh --only 'litdoc'
```

If `v2/src/Runtime.scala` generic FastCode, match, foreach, or cell behavior
changes, also run:

```bash
./v2/conformance/check.sh
```

Focused iteration may use:

```bash
bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3 bench/corpus/pattern-match-heavy.ssc
```

## Behavior

- [ ] Stage `bin/ssc` and reproduce the current post-scratch-env
      `pattern-match-heavy` baseline in this worktree.
- [ ] Inspect or profile the remaining bridge-generated hot path after the
      scratch-env match-arm fix, and record the exact missed boundary before
      changing runtime code.
- [ ] Decide whether the remaining boundary admits one narrow safe VM/FastCode
      optimization. If not, record the blocker and stop without speculative
      code.
- [ ] If a fast path lands, add focused regression coverage for the recognized
      shape and rerun the production row before/after.
- [ ] Keep the production gate honest: do not mark the v2 VM 2x target green
      unless the measured four-row command justifies it.
- [ ] Run affected tests, `installBin`, `tests/conformance/run.sh --only
      'litdoc'`, and full `./v2/conformance/check.sh` if runtime FastCode
      semantics changed.

## Out of scope

- Broad bytecode-codegen JIT Phase C / A.4 from `specs/vm-jit-next.md`.
- Source backend JVM/Rust performance gates.
- More than one hand-written FastCode optimization in this slice.
- Changing benchmark source semantics or adding benchmark-only shortcuts.

## Design

Start from measurement. The current known shape is:

```text
workload():
  var total = 0.0
  var i = 0
  while i < 100000 do
    shapes.foreach(s => total = total + area(s))
    i = i + 1
  total
```

Bridge CoreIR lowers this to a `while` over an `lcell`, an inline
`__method__("foreach", global shapes, Lam(1, ...))`, a `cell.set` of a
Double cell, an `App(Global(area), Local(0))`, and a compact arithmetic-only
`Match` inside `area`. Previous slices already proved:

1. `area` and `workload` expose `fcEntry`;
2. `cell.set` can call `area.fcEntry` from the hot path;
3. compact `Match` arms can safely reuse scratch env arrays only under
   `armBodyScratchSafe`.

Candidate areas to inspect, in order:

1. inline `foreach` still allocating per-element lambda env via
   `Runtime.appendOne(env, elem)`;
2. `area` match dispatch still doing generic `DataV` tag/field work;
3. list traversal through boxed `Cons`/`Nil` cells;
4. `Double` cell read/write and `FloatV` arithmetic allocation.

Any optimization must have a syntactic safety predicate as tight as
`armBodyScratchSafe`, or be rejected for this slice. Prefer a local reuse or
specialization that naturally falls back to existing code when the shape
differs.

## Decisions

- **Continue with the largest remaining VM row** — chosen because
  `pattern-match-heavy` remains roughly 288x slower than `ssc` after the
  scratch-env fix. Rejected: switching immediately to source-backend gates
  before exhausting one more measured VM-local boundary.
- **One narrow optimization maximum** — chosen to keep the runtime risk bounded
  under parallel-agent pressure. Rejected: a broad FastCode sweep across all
  list/HOF/match forms.
- **Stop on unsafe env reuse** — chosen because closure/env capture bugs are
  correctness regressions that conformance may miss unless the shape is
  explicit. Rejected: reusing lambda env arrays for arbitrary `foreach` bodies.

## Results

Pending implementation.
