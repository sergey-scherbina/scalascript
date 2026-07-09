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

- [x] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      for the remaining v2 VM production rows.
- [x] `scripts/bench v2-bytecode` is run for `arith-loop`,
      `recursion-fib`, `recursion-tco`, and `pattern-match-heavy`, and the
      numbers are recorded here alongside the current v2 VM rows.
- [x] If the bytecode lane is already fast and semantically green for a row,
      document the exact production-route implication instead of adding another
      speculative `FastCode` recognizer.
- [x] If a bytecode/runtime gap is found, inspect the generated CoreIR or
      bytecode-lane unsupported/profile signal and land at most one conservative
      implementation change for that measured gap.
- [x] The overall v2 production gate is only marked green if the measured rows
      justify it; otherwise this spec records the next precise blocker.
- [x] Affected tests/conformance, final bench rows, and `git diff --check`
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

Measurement slice only; no runtime/backend code changed. The implementation
decision is negative and intentional: the existing bytecode lane is useful for
the recursion family, but it is not a universal production default because it is
worse than the current VM on `pattern-match-heavy`.

Staging:

```bash
scripts/sbtc "installBin"
```

`scripts/bench v2-bytecode` results:

| Workload | v2 ms/iter | v2-bytecode ms/iter | Interpretation |
| --- | ---: | ---: | --- |
| `arith-loop` | 0.000015 | 0.609 | VM/Rust route already owns this row; bytecode is not the route. |
| `recursion-fib` | 5.89 | 1.16 | Bytecode closes the recursion-fib route. |
| `recursion-tco` | 0.258 | 0.028 | Bytecode closes the recursion-tco route. |
| `pattern-match-heavy` | 13.7 | 19.3 | Bytecode does not help the remaining pattern blocker. |

`scripts/bench v2-backends` results from the same worktree:

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `arith-loop` | 0.000016 | 0.271 | 0.000028 |
| `recursion-fib` | 6.22 | 1.25 | 1.44 |
| `recursion-tco` | 0.259 | 0.027 | 0.687 |
| `pattern-match-heavy` | 15.0 | 11.0 | 0.266 |

Route decision:

- The existing `ssc run --bytecode` / `ssc bench --backend v2-bytecode` lane is
  production-relevant for recursive workloads and stays part of the production
  route matrix.
- It should not become the default v2 production route yet because
  `pattern-match-heavy` is slower than the VM and much slower than the Rust
  source lane.
- The remaining precise blocker is the `pattern-match-heavy` family. The next
  slice should inspect/profile that row specifically and avoid adding another
  VM `FastCode` case unless the measured shape is explicit and the fallback is
  conservative.

Smoke:

- `bin/ssc run --v2 tests/conformance/list-companion.ssc`
- `bin/ssc run --bytecode tests/conformance/list-companion.ssc`

Both printed the same four lines:

```text
0, 0, 0, 0
0, 1, 4, 9, 16
1, 2, 3, 4, 5
0, 3, 6, 9
```

Final verification:

- `scripts/sbtc "installBin"`
- `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z bytecode"`
  (2 passed, 0 failed)
- `tests/conformance/run.sh --only 'list-companion' --no-memo` (1 passed,
  0 failed across INT/JS/JVM)
- `scripts/bench v2-bytecode arith-loop`
- `scripts/bench v2-bytecode recursion-fib`
- `scripts/bench v2-bytecode recursion-tco`
- `scripts/bench v2-bytecode pattern-match-heavy`
- `scripts/bench v2-backends arith-loop`
- `scripts/bench v2-backends recursion-fib`
- `scripts/bench v2-backends recursion-tco`
- `scripts/bench v2-backends pattern-match-heavy`
- `git diff --check`
