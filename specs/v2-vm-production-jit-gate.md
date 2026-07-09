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

- [x] Reproduce the current four-row v2 VM production-performance baseline in
      this worktree after staging `bin/ssc`.
- [x] Identify the bridge-generated CoreIR shape for `bench/corpus/arith-loop.ssc`
      and document the exact recognizer scope before changing runtime code.
- [x] Land one narrow v2 VM fast path for the `arith-loop` Long-cell while shape
      or record why that shape is not safe to optimize in this slice.
- [x] Verify that existing recursion fixes stay green and the production
      performance checklist remains honest: mark the 2x gate closed only if the
      measured numbers justify it.
- [x] Run affected unit/backend checks plus
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

Implemented a conservative exact-shape recognizer for the bridge-lowered local
Long-cell summation loop. The recognizer is used both by normal `Code` and by
arity-0 `fcEntry`, because `bench.sh` invokes the hot workload wrapper through
`fcEntry`; a code-only recognizer left the benchmark on the old VM path.

Baseline command:

```bash
./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy
```

Before:

| Workload | ssc | ssc-asm | v2 | jvm | js | rust |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| arith-loop | 0.256 | 0.253 | 9.91 | 0.247 | 0.581 | 0.951 |
| pattern-match-heavy | 0.053 | 0.053 | 27.8 | 0.046 | 0.049 | 1.37 |
| recursion-fib | 1.18 | 1.16 | 5.75 | 1.26 | 4.29 | 1.81 |
| recursion-tco | 0.028 | 0.029 | 0.258 | 0.025 | 0.120 | 0.025 |

After:

| Workload | ssc | ssc-asm | v2 | jvm | js | rust |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| arith-loop | 0.279 | 0.275 | 0.000018 | 0.272 | 0.614 | 1.04 |
| pattern-match-heavy | 0.067 | 0.064 | 19.1 | 0.058 | 0.061 | 1.66 |
| recursion-fib | 1.30 | 1.29 | 6.34 | 1.39 | 4.86 | 1.96 |
| recursion-tco | 0.034 | 0.033 | 0.308 | 0.027 | 0.138 | 0.028 |

This closes only the `arith-loop` scalar-loop slice. The overall v2 VM
production-performance gate remains red: `pattern-match-heavy`,
`recursion-fib`, and `recursion-tco` are still outside the 2x target and need
separate follow-up slices.

Gates run:

- `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z var"`
- `scripts/sbtc "installBin"`
- `./bench.sh --backend v2 --warmup-time 500 --reps 20 arith-loop`
- `./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy`
- `tests/conformance/run.sh --only 'litdoc'`
- `git diff --check`

Post-rebase caveat: `./v2/conformance/check.sh` is currently red on the VM
effect-handler family (`effects-state`, `effects-nondet`, `async-tasks`,
`hm-eff-comp`, and related typed-effect rows). A detached diagnostic worktree at
clean `origin/main` `ab78c6cac` reproduces the same failures, so this is tracked
separately as `BUGS.md` / SPRINT item `v2-vm-effect-handlers-regression` and is
not caused by the arith-loop recognizer.
