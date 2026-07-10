# v2 Four Row Route Policy Sweep

## Overview

The representative v2 production-performance rows have now each had focused
work: scalar loops, recursion, source backends, bytecode recursion, and
`pattern-match-heavy`. This slice reruns the bounded four-row route gate after
the VM `pattern-match-heavy` fix and records the production route policy:
which public route should be trusted for each representative workload family,
and whether a global default switch is justified.

## Interface

No language semantics change is intended. Verification uses the existing public
commands:

```bash
scripts/sbtc "installBin"
scripts/bench v2-bytecode <workload>
scripts/bench v2-backends <workload>
tests/conformance/run.sh --only '<affected-globs>' --no-memo
```

If this slice lands a route-wiring change, it must also run the focused CLI or
frontend bridge tests covering that route. If no code changes, the acceptance
gate is the measured rows plus `git diff --check`.

## Behavior

- [x] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      for `arith-loop`, `recursion-fib`, `recursion-tco`, and
      `pattern-match-heavy`.
- [x] `scripts/bench v2-bytecode` records VM vs bytecode rows for all four
      workloads.
- [x] `scripts/bench v2-backends` records VM vs JVM source vs Rust source rows
      for all four workloads.
- [x] The route policy is explicit: per row, record the fastest safe public
      route and whether the global default should remain VM or change.
- [x] Code is only changed if the measured policy exposes a narrow route-wiring
      gap that improves or preserves all four rows.
- [x] Tests/conformance relevant to any changed route, final bench rows, and
      `git diff --check` pass before push.

## Out of Scope

- New VM `FastCode` recognizers or backend source optimizations.
- Changing benchmark workload semantics.
- Reopening the active `v2-money-decimal-regression` work.
- Declaring all of v2 production-ready beyond the measured route-policy gate.

## Design

The previous bytecode sweep showed bytecode is a strong route for recursion but
bad for `pattern-match-heavy`. The latest VM slice made `pattern-match-heavy`
roughly tie Rust source speed, so the open question is no longer a single
workload blocker. It is whether the public v2 routes form a coherent production
policy.

Measurement order:

1. Stage `bin/ssc` with `scripts/sbtc "installBin"`.
2. Run `scripts/bench v2-bytecode` for each of the four rows.
3. Run `scripts/bench v2-backends` for each of the four rows.
4. Compare the route matrix:
   - VM route: always available, current default candidate.
   - Bytecode route: likely best for recursion rows, bad for pattern-heavy.
   - JVM source route: strong on `recursion-tco`, acceptable on recursion-fib.
   - Rust source route: strong on scalar/pattern rows, opt-in source backend.
5. If no single global default beats the current VM default across rows, record
   that as the policy and leave default wiring unchanged.

## Decisions

- **Measure route policy, not another optimizer** - chosen because the last
  precise blocker was closed and the next production decision is route
  selection. Rejected: adding speculative runtime/backend optimizations without
  a red measured row.
- **Keep default changes conservative** - chosen because bytecode and source
  routes have asymmetric strengths. Rejected: flipping the global default to a
  route that regresses any representative row.

## Results

All measurements were taken in worktree
`/Users/sergiy/work/my/scalascript-wt-v2-four-row-route-policy-sweep` after
`scripts/sbtc "installBin"` passed. No code changed in this slice.

`scripts/bench v2-bytecode`:

| Workload | VM `v2` | JVM bytecode `v2-bytecode` | Policy |
|---|---:|---:|---|
| `arith-loop` | 0.000016 ms | 0.595 ms | Keep VM default; bytecode is not a scalar-loop route. |
| `recursion-fib` | 5.93 ms | 1.19 ms | Use bytecode for recursion-heavy deployment when available. |
| `recursion-tco` | 0.255 ms | 0.028 ms | Bytecode is a good public route for TCO recursion. |
| `pattern-match-heavy` | 0.266 ms | 19.4 ms | Keep VM default; bytecode is not a pattern-heavy route. |

`scripts/bench v2-backends`:

| Workload | VM `v2` | JVM source `v2-jvm` | Rust source `v2-rust` | Policy |
|---|---:|---:|---:|---|
| `arith-loop` | 0.000016 ms | 0.267 ms | 0.000026 ms | VM and Rust are closed; VM stays default. |
| `recursion-fib` | 5.80 ms | 1.27 ms | 1.47 ms | JVM/Rust source are acceptable; bytecode remains fastest. |
| `recursion-tco` | 0.280 ms | 0.027 ms | 0.659 ms | JVM source and bytecode are the production routes. |
| `pattern-match-heavy` | 0.265 ms | 10.9 ms | 0.269 ms | VM and Rust source are closed; avoid JVM source/bytecode here. |

Global default decision: keep `ssc run --v2` on the VM route. No single
non-VM public route improves all four representative rows: bytecode wins the
recursion rows but badly regresses scalar and pattern-heavy rows; JVM source is
excellent for `recursion-tco` but not for pattern-heavy; Rust source ties the
scalar and pattern rows but is not the best recursion route. The production
policy is therefore explicit route selection by workload/deployment family
until an auto-router exists.

The older `./bench.sh --warmup-time 500 --reps 20 ...` legacy comparison was
not rerun for this closing slice. The current project rule requires
`scripts/bench` for perf A/B work, and this spec's acceptance surface is the
public v2 route matrix above; historical legacy rows in `BACKLOG.md` remain
blocker context rather than the route-policy gate.

Verification:

- `scripts/sbtc "installBin"` passed before measurement.
- `tests/conformance/run.sh --only 'list-companion' --no-memo` passed 1/1
  across INT/JS/JVM.
- `git diff --check` passed before commit.
