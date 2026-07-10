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

- [ ] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      for `arith-loop`, `recursion-fib`, `recursion-tco`, and
      `pattern-match-heavy`.
- [ ] `scripts/bench v2-bytecode` records VM vs bytecode rows for all four
      workloads.
- [ ] `scripts/bench v2-backends` records VM vs JVM source vs Rust source rows
      for all four workloads.
- [ ] The route policy is explicit: per row, record the fastest safe public
      route and whether the global default should remain VM or change.
- [ ] Code is only changed if the measured policy exposes a narrow route-wiring
      gap that improves or preserves all four rows.
- [ ] Tests/conformance relevant to any changed route, final bench rows, and
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

Pending. Fill with exact commands, rows, route policy, tests, and SHAs after
verification.
