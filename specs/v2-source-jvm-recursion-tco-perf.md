# v2 Source JVM Recursion TCO Performance

## Overview

This slice targets the remaining v2 JVM source-backend row in the Phase-3
source-backend production gate: `bench/corpus/recursion-tco.ssc`. After the
Rust source recursion and pattern-match slices, the latest recorded regression
row reports `v2-jvm=3.20 ms` for `recursion-tco`, while `v2=0.302 ms` and
`v2-rust=0.668 ms`.

## Interface

No user-facing language, CLI, file-format, or benchmark-workload interface
changes are planned. The public verification command for this slice is:

```bash
scripts/bench v2-backends recursion-tco
```

The workload under test remains the accumulator-style tail-recursive row:

```scalascript
def sumTco(n: Int, acc: Int): Int =
  if n <= 0 then acc
  else sumTco(n - 1, acc + n)

def workload(): Int = sumTco(100000, 0)
```

## Behavior

- [ ] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      using `scripts/bench v2-backends recursion-tco`.
- [ ] The emitted v2 JVM source for the bench wrapper is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [ ] Any implementation lands one conservative v2 JVM source-backend
      optimization for the measured self-tail-recursive accumulator shape,
      preserving existing `.ssc` semantics and output.
- [ ] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate remains open unless all
      remaining source rows are also proven green.
- [ ] Affected semantic/conformance or backend parity gates pass, the final
      public bench row demonstrates the result, and `git diff --check` passes.

## Out of Scope

- v2 VM/JIT performance work.
- v2 Rust source backend performance.
- Benchmark workload changes.
- Public JVM backend interface changes.
- Broad JVM backend rewrites not needed for this measured row.

## Design

Start with measurement and emitted-source inspection. The prior JVM
`recursion-fib` slice already landed Long-specialized global helpers, so this
slice must not assume the same missing helper is still the bottleneck. Inspect
the actual source generated for the timed wrapper and identify whether
`sumTco` still routes through generic `V` closure dispatch, uses a direct
helper but boxes loop-carried values, pays harness overhead, or is blocked by a
different source-backend shape.

If inspection confirms a source-backend gap, prefer the narrowest lowering that
removes the measured dispatch/boxing tax while preserving the generic fallback.
If the slow row is caused by benchmark warmup, scalar-evolution anti-folding,
scala-cli execution, or another measurement artifact, record that finding first
and do not land speculative code.

## Decisions

- **Scope one backend and one workload family first** - chosen because the
  source-backend gate now points at `v2-jvm recursion-tco` as the remaining
  recommended slice. Rejected: mixing this with VM/JIT, Rust, or broader
  source-backend rows.
- **Keep the public benchmark command fixed** - chosen so before/after numbers
  remain comparable with the production gate. Rejected: changing
  `bench/corpus/recursion-tco.ssc`, because that would move the gate.
- **Preserve generic fallback semantics** - chosen because any direct tail-loop
  fast path must be optional. Rejected: replacing generic function/closure
  behavior wholesale in this slice.

## Baseline

Seed baseline from the preceding Rust pattern-match slice regression row on
2026-07-09:

```bash
scripts/bench v2-backends recursion-tco
```

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-tco` | 0.302 | 3.20 | 0.668 |

Refresh this table in this worktree after `scripts/sbtc "installBin"` before
making code changes.

Fresh worktree baseline after `scripts/sbtc "installBin"` on 2026-07-09:

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-tco` | 0.298 | 3.09 | 0.704 |

## Inspection

Pending. Record the emitted v2 JVM source shape and dominant overhead before
code changes.

## Results

Pending. Fill after implementation and verification with exact commits,
before/after numbers, rejected alternatives, and gates.
