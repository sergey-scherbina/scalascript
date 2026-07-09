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

- [x] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      using `scripts/bench v2-backends recursion-tco`.
- [x] The emitted v2 JVM source for the bench wrapper is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [x] Any implementation lands one conservative v2 JVM source-backend
      optimization for the measured self-tail-recursive accumulator shape,
      preserving existing `.ssc` semantics and output.
- [x] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate remains open unless all
      remaining source rows are also proven green.
- [x] Affected semantic/conformance or backend parity gates pass, the final
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

2026-07-09 generated the CoreIR and v2 JVM source for
`bench/corpus/recursion-tco.ssc` before code changes:

```bash
scripts/sbtc "v2FrontendBridge/runMain ssc.bridge.bridgeCli emit bench/corpus/recursion-tco.ssc" > /tmp/v2-recursion-tco.coreir.raw
grep '^(program ' /tmp/v2-recursion-tco.coreir.raw > /tmp/v2-recursion-tco.coreir
scala-cli run v2/backend/jvm -q --server=false < /tmp/v2-recursion-tco.coreir > /tmp/v2-recursion-tco.scala
```

The generated source already contains both helper families:

```scala
def sumTco_long(p0_5: Long, p1_5: Long): Long =
  if p0_5 <= 0L then p1_5 else sumTco_long(p0_5 - 1L, p1_5 + p0_5)

@tailrec def sumTco_direct(p0_5: V, p1_5: V): V =
  if R.prim3("__arith__", "<=": V, p0_5, 0L: V).asInstanceOf[Boolean] then p1_5
  else sumTco_direct(
    R.prim3("__arith__", "-": V, p0_5, 1L: V),
    R.prim3("__arith__", "+": V, p1_5, p0_5))

lazy val workload: V =
  ((_a6: Array[V]) => { val _u6 = _a6; sumTco_direct(100000L: V, 0L: V) }): V
```

Dominant overhead hypothesis: the backend already proves `sumTco` is Long-
typed, but global application lowering checks `directDefs` before
`longGlobalDefs`. Because `sumTco` is also safe tail-recursive,
`workload()` calls the boxed `sumTco_direct(V,V): V` path instead of
`sumTco_long(Long,Long): Long`. That keeps every loop-carried comparison,
subtraction, and addition on `R.prim3("__arith__", ...)` despite the available
Long helper.

Implementation direction: prefer the Long helper whenever a global call's
arguments are statically Long, even if that global also has a boxed
`@tailrec` direct method. Keep the boxed direct method for non-Long tail-rec
functions and generic fallback calls.

## Results

Implemented in `1e7598394` (`fix(v2-jvm): prefer long helpers for tail-rec
globals`).

What landed:

- `JvmBackend.scala` now checks `longGlobalDefs` before boxed `directDefs` when
  lowering `App(Global(...))`, so statically Long calls use the existing
  `<name>_long` helper even when the function also has a boxed `@tailrec`
  direct method.
- Long-typed tail-recursive globals now annotate their Long helpers with
  `@tailrec`, and their closure wrappers call the Long helper with `_asLong`
  arguments. The boxed direct method remains available for non-Long tail-rec
  fallback calls.

Final target row:

```bash
scripts/bench v2-backends recursion-tco
```

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-tco` baseline | 0.298 | 3.09 | 0.704 |
| `recursion-tco` final | 0.253 | 0.027 | 0.658 |

Source-backend regression/sweep rows in this worktree:

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `arith-loop` | 0.000016 | 0.267 | 0.000026 |
| `recursion-fib` | 11.0 | 1.71 | 1.53 |
| `pattern-match-heavy` | 14.0 | 10.7 | 0.265 |

Verification:

- `scripts/sbtc "installBin"`
- `scala-cli compile --server=false v2/backend/jvm`
- `v2/backend/check.sh tco`
- `v2/backend/check.sh letrec`
- `tests/conformance/run.sh --only 'recursion,tail-recursion,mutual-recursion' --no-memo`
  (3 passed, 0 failed)
- `scripts/bench v2-backends recursion-tco`
- `scripts/bench v2-backends recursion-fib`
- `scripts/bench v2-backends arith-loop`
- `scripts/bench v2-backends pattern-match-heavy`
- `git diff --check`

This closes the known Phase-3 JVM/Rust source-backend performance rows. The
separate v2 VM production-performance gate remains open and is tracked by
`v2-vm-production-jit-gate`.
