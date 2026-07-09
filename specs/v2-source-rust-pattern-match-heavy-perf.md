# v2 Source Rust Pattern Match Heavy Performance

## Overview

This slice targets the largest remaining Rust source-backend row in the
Phase-3 source-backend production gate: `bench/corpus/pattern-match-heavy.ssc`.
The fresh post-recursion sweep reports `v2-rust=318.2 ms` while the same public
command reports `v2=14.8 ms` and `v2-jvm=10.7 ms`.

## Interface

No user-facing language, CLI, file-format, or benchmark-workload interface
changes are planned. The public verification command for this slice is:

```bash
scripts/bench v2-backends pattern-match-heavy
```

The workload under test remains the sealed-ADT/list-foreach/match corpus row:

```scalascript
sealed trait Shape
case class Circle(r: Double) extends Shape
case class Rect(w: Double, h: Double) extends Shape
case class Triangle(b: Double, h: Double) extends Shape
case class Point(x: Double, y: Double) extends Shape
case class Line(len: Double) extends Shape

def area(s: Shape): Double = s match
  case Circle(r)       => 3.14159 * r * r
  case Rect(w, h)      => w * h
  case Triangle(b, h)  => 0.5 * b * h
  case Point(_, _)     => 0.0
  case Line(_)         => 0.0

def workload(): Double =
  var total = 0.0
  var i = 0
  while i < 100000 do
    shapes.foreach(s => { total = total + area(s) })
    i = i + 1
  total
```

## Behavior

- [ ] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      using `scripts/bench v2-backends pattern-match-heavy`.
- [ ] The emitted v2 Rust source for the bench wrapper is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [ ] Any implementation lands one conservative v2 Rust source-backend
      optimization for the measured pattern/list/match shape, preserving
      existing `.ssc` semantics and output.
- [ ] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate remains open unless all
      remaining source rows are also proven green.
- [ ] Affected semantic/conformance or backend parity gates pass, the final
      public bench row demonstrates the result, and `git diff --check` passes.

## Out of Scope

- v2 VM/JIT performance work.
- v2 JVM source backend `recursion-tco` performance.
- Benchmark workload changes.
- Public `emit-rust` interface changes.
- Broad Rust backend rewrites not needed for this measured row.

## Design

Start with measurement and source inspection. The historical Rust roadmap says
legacy `emit-rust` coverage for `pattern-match-heavy` required sealed-trait ADT
support and multi-statement `foreach`; that roadmap is complete for the legacy
Rust backend, but this slice is about the v2 Rust source backend path used by
`scripts/bench v2-backends`.

If inspection shows the v2 Rust source still routes the hot loop through generic
`V` list iteration, match dispatch, dynamic calls, or boxed Float arithmetic,
prefer a local source-backend lowering that removes only the measured dispatch
tax while preserving the existing generic fallback. If the slow row is caused by
benchmark compilation, LLVM folding, or another measurement artifact, record
that finding first and do not land speculative code.

## Decisions

- **Scope one backend and one workload family first** - chosen because BACKLOG
  asks for one backend/workload slice at a time and the latest sweep isolates
  `v2-rust pattern-match-heavy` as the largest real source-backend blocker.
  Rejected: mixing in JVM source `recursion-tco` or VM/JIT work.
- **Keep the public benchmark command fixed** - chosen so before/after numbers
  are comparable with the production gate. Rejected: changing
  `bench/corpus/pattern-match-heavy.ssc`, because that would move the gate.
- **Preserve generic fallback semantics** - chosen because any typed or direct
  fast path must be optional. Rejected: replacing generic ADT/list/match
  behavior wholesale in this slice.

## Baseline

Seed baseline from the preceding sweep on 2026-07-09:

```bash
scripts/bench v2-backends pattern-match-heavy
```

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `pattern-match-heavy` | 14.8 | 10.7 | 318.2 |

Refresh this table in this worktree after `scripts/sbtc "installBin"` before
making code changes.

Fresh worktree baseline after `scripts/sbtc "installBin"` on 2026-07-09:

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `pattern-match-heavy` | 15.4 | 10.8 | 319.1 |

## Inspection

Pending. Record the emitted v2 Rust shape and dominant overhead before code
changes.

## Results

Pending. Fill after implementation and verification with exact commits,
before/after numbers, rejected alternatives, and gates.
