# v2 VM Pattern Match Heavy Fast Tier

## Overview

`bench/corpus/pattern-match-heavy.ssc` is now the largest measured v2 VM
production-performance gap after the scalar-loop slice. The workload is a
stable `List[Shape]` traversed inside a `while`, with each element dispatched
through a 5-arm ADT `match` returning `Double`. This slice measures the current
bridge-generated CoreIR shape, then lands at most one conservative v2 VM fast
path if a narrow safe shape is present.

## Interface

No user-facing language, CLI, or benchmark contract changes are intended.
Verification uses the existing production benchmark and conformance commands:

```bash
scripts/sbtc "installBin"
./bench.sh --warmup-time 500 --reps 20 pattern-match-heavy
./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy
tests/conformance/run.sh --only 'litdoc'
```

Focused probes may use shorter runs while iterating:

```bash
./bench.sh --backend v2 --warmup-time 100 --reps 3 pattern-match-heavy
./bench.sh --backend v2 --warmup-time 500 --reps 20 pattern-match-heavy
```

## Behavior

- [ ] Stage `bin/ssc` and reproduce the current `pattern-match-heavy` v2 VM
      baseline in this worktree, recording the exact command and numbers.
- [ ] Emit or inspect the bridge-generated CoreIR for
      `bench/corpus/pattern-match-heavy.ssc` and identify the hot shape before
      changing runtime code.
- [ ] Decide whether the shape admits one narrow VM/FastCode optimization in
      this slice. If not, record the blocker with the exact missed shape and
      stop without speculative code.
- [ ] If a fast path lands, add focused regression coverage for the recognized
      shape and rerun the production benchmark row before/after.
- [ ] Keep the production gate honest: do not mark the overall v2 VM 2x target
      green unless the measured four-row command justifies it.
- [ ] Run affected tests, `installBin`, and
      `tests/conformance/run.sh --only 'litdoc'` before push. If `Runtime.scala`
      generic FastCode/match/foreach behavior changes, also run
      `./v2/conformance/check.sh`.

## Out of scope

- Broad bytecode-codegen JIT Phase C / A.4 from `specs/vm-jit-next.md`.
- Source backend JVM/Rust performance gates.
- Further `arith-loop`, recursion, or effect-handler work.
- Rewriting the frontend bridge or changing benchmark source semantics.

## Design

Start from measurement, not assumption. The historical v1 interpreter notes show
that `patternMatchHeavy` wins came from cross-boundary `foreach`/match
threading, but this v2 VM already has several relevant pieces:

1. `FastCode.tryFC` for `__method__("foreach", list, Lam(1, body))`;
2. `FastCode.tryFC` for simple `Match` bodies using float-safe arm evaluation;
3. `Lam` creation in while bodies so a loop can keep the outer body on an FC
   path;
4. the recent `arith-loop` exact closed-form recognizer for a different Long
   cell shape.

The first implementation step is therefore to capture the actual bridge CoreIR
and identify which part misses: stable global list lookup, lambda body shape,
`area` global call, `Double` cell assignment, simple `Match`, or another
allocation hotspot. Prefer extending an existing narrow FastCode path over
adding a second ad-hoc closed-form recognizer. If the required change needs
cross-boundary unboxed accumulator threading or broad bytecode codegen, record
that as the result and leave implementation to a larger slice.

## Decisions

- **Measure and inspect before optimizing** — chosen because
  `pattern-match-heavy` combines `while`, `foreach`, lambdas, global calls,
  `Double` cells, and ADT `match`; optimizing the wrong layer risks a local
  win with broad runtime risk. Rejected: starting with a speculative closed
  form like the `arith-loop` slice.
- **One narrow runtime fast path maximum** — chosen to keep this safe under
  parallel-agent pressure. Rejected: broad Phase C/A.4 bytecode work in this
  slice.
- **Keep all gate claims tied to measured rows** — chosen because the previous
  scalar-loop slice improved one row massively while the overall VM gate stayed
  red.

## Results

Pending implementation.
