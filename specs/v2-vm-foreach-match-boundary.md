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

- [x] Stage `bin/ssc` and reproduce the current post-scratch-env
      `pattern-match-heavy` baseline in this worktree.
- [x] Inspect or profile the remaining bridge-generated hot path after the
      scratch-env match-arm fix, and record the exact missed boundary before
      changing runtime code.
- [x] Decide whether the remaining boundary admits one narrow safe VM/FastCode
      optimization. If not, record the blocker and stop without speculative
      code.
- [x] If a fast path lands, add focused regression coverage for the recognized
      shape and rerun the production row before/after.
- [x] Keep the production gate honest: do not mark the v2 VM 2x target green
      unless the measured four-row command justifies it.
- [x] Run affected tests, `installBin`, `tests/conformance/run.sh --only
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

Implemented a local `FastCode.tryFCAppended` lane for `foreach` inline
`Lam(1, body)` calls. The lane evaluates supported lambda bodies against a
virtual `appendOne(baseEnv, element)` view: `Local(0)` is the current element
and `Local(k>0)` reads the shifted base env, so the hot path no longer allocates
`Runtime.appendOne(env, elem)` per list element. Complex binders (`Lam`, `Let`,
`Match`, etc.) are rejected and fall back to the existing materialized-env
path; the focused regression stores an escaping nested lambda from a `foreach`
body and verifies it still captures a fresh per-element env.

Inspection before code:

- `BridgeCli.emit bench/corpus/pattern-match-heavy.ssc` showed the hot lambda
  as `cell.set(local 2, cell.get(local 2) + app(global area, local 0))`.
- Current baseline after rebasing over K62.4/K62.5:
  - `bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3
    bench/corpus/pattern-match-heavy.ssc` -> `BENCH v2 18.5`
  - `./bench.sh --warmup-time 500 --reps 20 pattern-match-heavy` ->
    `ssc 0.065`, `ssc-asm 0.061`, `v2 18.2`, `jvm 0.052`, `js 0.052`,
    `rust 1.55`.

Final measurements after the runtime change and rebase:

- `bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3
  bench/corpus/pattern-match-heavy.ssc` -> `BENCH v2 15.3`.
- `./bench.sh --warmup-time 500 --reps 20 pattern-match-heavy` ->
  `ssc 0.058`, `ssc-asm 0.059`, `v2 14.4`, `jvm 0.051`, `js 0.052`,
  `rust 1.53`.
- Four-row production probe
  `./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib
  recursion-tco pattern-match-heavy`:

| Workload | ssc | ssc-asm | v2 | jvm | js | rust |
|---|---:|---:|---:|---:|---:|---:|
| `arith-loop` | 0.274 | 0.273 | 0.000019 | 0.263 | 0.586 | 0.992 |
| `pattern-match-heavy` | 0.058 | 0.058 | 15.2 | 0.051 | 0.052 | 1.53 |
| `recursion-fib` | 1.18 | 1.19 | 5.80 | 1.27 | 4.34 | 1.88 |
| `recursion-tco` | 0.031 | 0.030 | 0.272 | 0.026 | 0.124 | 0.025 |

The `foreach` boundary is real (`pattern-match-heavy` improved from 18.2 ms to
14.4-15.2 ms), but the Phase-3 v2 VM 2x target remains red. Remaining work
should not add speculative hand-written `FastCode` cases without a fresh
profile; likely next choices are a broader bytecode/JIT path or a separate
recursion-family gate slice.

Verification:

- `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest'`
  passed, 36 tests.
- `scripts/sbtc 'installBin'` passed.
- `./v2/conformance/check.sh` passed.
- `tests/conformance/run.sh --only 'litdoc'` passed INT/JS/JVM.
- `git diff --check` passed.
