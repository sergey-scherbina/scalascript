# v2 Bytecode Production Gate Sweep

## Overview

The v2 VM production-performance gate remains red after the scalar-loop,
pattern-match, foreach-boundary, and source-backend slices. This slice measures
the existing v2 JVM bytecode lane against the same representative rows and uses
the result to choose the next production route: promote the bytecode lane where
it is already correct and fast, or record a profile-backed blocker for the next
implementation slice.

## Interface

No user-facing language semantics change in this slice. Existing production
commands remain the verification surface:

```bash
scripts/sbtc "installBin"
scripts/bench v2-bytecode <workload>
scripts/bench v2-backends <workload>
./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy
tests/conformance/run.sh --only '<affected-globs>' --no-memo
```

If this slice changes the bytecode lane or runtime bridge, focused checks should
cover `v2FrontendBridge` bytecode tests and `./v2/conformance/check.sh` when
generic VM/FastCode behavior changes.

## Behavior

- [ ] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      for the remaining v2 VM production rows.
- [ ] `scripts/bench v2-bytecode` is run for `arith-loop`,
      `recursion-fib`, `recursion-tco`, and `pattern-match-heavy`, and the
      numbers are recorded here alongside the current v2 VM rows.
- [ ] If the bytecode lane is already fast and semantically green for a row,
      document the exact production-route implication instead of adding another
      speculative `FastCode` recognizer.
- [ ] If a bytecode/runtime gap is found, inspect the generated CoreIR or
      bytecode-lane unsupported/profile signal and land at most one conservative
      implementation change for that measured gap.
- [ ] The overall v2 production gate is only marked green if the measured rows
      justify it; otherwise this spec records the next precise blocker.
- [ ] Affected tests/conformance, final bench rows, and `git diff --check`
      pass before pushing.

## Out of Scope

- Changing `.ssc` language semantics or public source syntax.
- Source-backend JVM/Rust performance work already closed by
  `v2-source-backend-production-perf-gates`.
- Broad VM/JIT rewrites without a measured row and a focused acceptance gate.
- Benchmark-only source changes that make the workload less representative.

## Design

The backlog now says the next VM production slice should be profile-backed and
likely move toward broader bytecode-JIT/source-backend gate work rather than new
speculative `FastCode` cases. The repository already has a v2 JVM bytecode lane
and a public benchmark wrapper, `scripts/bench v2-bytecode`, so the first
production question is whether that lane already closes the remaining gap for
the representative rows.

Measurement order:

1. Stage the CLI with `scripts/sbtc "installBin"`.
2. Capture current row numbers using the existing public benchmark wrappers.
3. If the bytecode lane is fast but not wired into the production route, record
   the smallest promotion/wiring step as the next implementation target.
4. If the bytecode lane is slow or unsupported on a row, inspect the emitted
   CoreIR and `JvmByteGen` unsupported/profile signal before code changes.
5. Only then implement one conservative fix, if the measured blocker is narrow.

## Decisions

- **Measure the bytecode lane before adding more VM hand paths** - chosen
  because recent VM slices have reduced local costs but the gate is still red,
  and `scripts/bench v2-bytecode` is the existing broader production candidate.
  Rejected: adding another `FastCode` recognizer without a fresh profile.
- **Keep this slice route-oriented** - chosen because production readiness may
  require choosing the correct execution lane, not only making the current VM
  interpreter faster. Rejected: treating the pure v2 VM row as the only possible
  production route if bytecode is already green.

## Results

Pending. Fill after measurement and implementation with exact commit SHAs,
before/after numbers, route decision, blockers, and gates.
