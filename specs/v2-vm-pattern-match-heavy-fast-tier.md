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

- [x] Stage `bin/ssc` and reproduce the current `pattern-match-heavy` v2 VM
      baseline in this worktree, recording the exact command and numbers.
- [x] Emit or inspect the bridge-generated CoreIR for
      `bench/corpus/pattern-match-heavy.ssc` and identify the hot shape before
      changing runtime code.
- [x] Decide whether the shape admits one narrow VM/FastCode optimization in
      this slice. If not, record the blocker with the exact missed shape and
      stop without speculative code.
- [x] If a fast path lands, add focused regression coverage for the recognized
      shape and rerun the production benchmark row before/after.
- [x] Keep the production gate honest: do not mark the overall v2 VM 2x target
      green unless the measured four-row command justifies it.
- [x] Run affected tests, `installBin`, and
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

Baseline in this worktree before runtime changes:

```text
scripts/sbtc "installBin"
./bench.sh --warmup-time 500 --reps 20 pattern-match-heavy

pattern-match-heavy: ssc 0.058, ssc-asm 0.058, v2 35.1, jvm 0.050, js 0.051, rust 1.44
```

Focused bridge/CoreIR inspection found that `area` and `workload` already get
VM `fcEntry` fast entries. The hot CoreIR shape is a `while` over an `lcell`
counter, a `shapes.foreach` inline lambda, `cell.set(total, total + area(s))`,
and `area` as a 5-arm ADT `match` with compact arithmetic-only arm bodies.
The narrow missed cost was per-dispatch compact arm env allocation
(`Array(fs(0))` / `Array(fs(0), fs(1))`) inside the fast `Match` path.

Implementation: `FastCode.tryFC(Match(...))` now reuses tiny scratch env arrays
only for compact arms whose bodies pass a stricter arithmetic-only
`armBodyScratchSafe` predicate. Arms that might capture env, call user/plugin
code, or need outer locals keep the previous allocation path.

Focused regression coverage:

```text
scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest'
```

Post-change measurement:

```text
bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3 bench/corpus/pattern-match-heavy.ssc
BENCH v2 17.3

./bench.sh --warmup-time 500 --reps 20 pattern-match-heavy
pattern-match-heavy: ssc 0.059, ssc-asm 0.060, v2 16.4, jvm 0.052, js 0.053, rust 1.48
```

Post-rebase four-row production gate:

```text
./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy

arith-loop:          ssc 0.283, v2 0.000018
pattern-match-heavy: ssc 0.059, v2 17.0
recursion-fib:       ssc 1.29,  v2 6.61
recursion-tco:       ssc 0.031, v2 0.275
```

This slice improves `pattern-match-heavy` v2 VM by about 2.1x on the full row
(35.1 ms to 16-17 ms) without regressing `arith-loop`, but the overall v2 VM
2x production target remains red. The next performance slice should target
the remaining `foreach`/match boundary costs or move to the broader bytecode
JIT/source-backend gate work; this spec does not claim the gate is green.

Final verification on the rebased worktree:

```text
scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest'
scripts/sbtc 'installBin'
./v2/conformance/check.sh
tests/conformance/run.sh --only 'litdoc'
git diff --check
```

`./v2/conformance/check.sh` was run twice after the runtime change: once after
the first post-change rebase, and again after the later `FrontendBridge.scala`
type-lambda rebase. Both full runs passed.
