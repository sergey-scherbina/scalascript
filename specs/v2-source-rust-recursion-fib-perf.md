# v2 Source Rust Recursion Fib Performance

## Overview

This slice improves the Phase-3 v2 Rust source backend performance gate for one
representative workload: `bench/corpus/recursion-fib.ssc`. The v2-backends
harness already exposes honest `v2`, `v2-jvm`, and `v2-rust` columns; JVM
`recursion-fib` is now closed, while Rust still shows a large measured gap on
the same workload.

## Interface

No user-facing language, CLI, file-format, or benchmark-workload interface
changes are planned. The public verification command for this slice is:

```bash
scripts/bench v2-backends recursion-fib
```

The workload under test remains:

```scalascript
def fib(n: Int): Int =
  if n <= 1 then n
  else fib(n - 1) + fib(n - 2)

def workload(): Int =
  fib(30)
```

## Behavior

- [x] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      using `scripts/bench v2-backends recursion-fib`.
- [x] The emitted v2 Rust source for `recursion-fib` is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [x] Any implementation lands one conservative v2 Rust source-backend
      optimization for the recursive function-call shape, preserving existing
      `.ssc` semantics and output.
- [x] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate remains open unless all
      remaining Rust/source rows are also proven green.
- [x] Affected semantic/conformance gates for recursion-shaped programs pass,
      backend parity gates covering Rust stay green, and `git diff --check`
      passes.

## Out of Scope

- v2 JVM source backend performance.
- v2 VM JIT/fast-tier performance.
- Unrelated Rust `arith-loop` anti-fold or corpus-wide harness fairness
  changes.
- Benchmark workload changes.
- New language constructs, CLI flags, or artifact formats.
- Broad Rust backend rewrites not needed for this measured recursion row.

## Design

Start with measurement and source inspection, mirroring the JVM recursion slice
but not assuming the same fix is correct for Rust. Stage the current worktree
CLI, run the public v2-backends benchmark for `recursion-fib`, and inspect the
exact Rust source emitted by `v2/backend/rust/RustBackend.scala`.

If the generated shape still routes self-recursive calls through generic
closure/vector dispatch, prefer a local source-backend lowering fix that emits a
direct typed Rust helper or otherwise removes the recursion-only dispatch tax
while preserving the existing public backend contract. If inspection shows that
the slow number is caused by Rust compilation/package startup, benchmark
anti-folding, or another measurement artifact, record that finding and stop
rather than landing speculative code.

During implementation this slice exposed a narrower measurement issue after the
direct helper shape was introduced: a zero-argument `workload()` helper such as
`g_workload_long(): i64 = g_fib_long(30i64)` can be constant-folded by
`rustc -O`. The fix belongs only in the v2-rust benchmark path
(`BenchCmd.timeV2Rust`) before the temporary Rust file is written. Public
`emit-rust` output must remain production-shaped and should not receive
benchmark-only `black_box` calls.

## Decisions

- **Scope one backend and one workload family first** - chosen because BACKLOG
  asks for one backend/workload slice at a time and the current measured row
  isolates `v2-rust recursion-fib` as a clear remaining source-backend gap.
  Rejected: working on all Rust source-backend rows together, because that
  would mix unrelated ownership, dispatch, and harness questions.
- **Keep the benchmark command fixed** - chosen so before/after numbers are
  comparable with the JVM recursion slice and reproducible by the next agent.
  Rejected: changing `bench/corpus/recursion-fib.ssc`, because that would move
  the gate instead of improving the backend.
- **Do not update the global language spec** - chosen because this slice changes
  backend quality only. The current global spec already requires target-
  independent semantics; no public syntax or semantic contract changes are
  intended.
- **Keep anti-folding benchmark-only** - chosen because `std::hint::black_box`
  is a measurement concern, not part of `.ssc` semantics or Rust production
  codegen. Rejected: adding `#[inline(never)]` or `black_box` to public backend
  output, because that would pessimize production artifacts and still does not
  fully solve zero-input helper folding.

## Baseline

Captured on 2026-07-09 from this worktree after staging the CLI:

```bash
scripts/sbtc "installBin"
scripts/bench v2-backends recursion-fib
```

Default harness settings:

```text
Corpus:   recursion-fib
Backends: v2, v2-jvm, v2-rust
Warmup:   2000ms (time-based)
Reps:     100
ssc:      /Users/sergiy/work/my/scalascript-wt-v2-source-rust-recursion-fib-perf/bin/ssc
```

Result:

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-fib` | 5.93 | 1.42 | 226.7 |

This confirms the Rust source-backend row is still a real production gap on
fresh `origin/main`: roughly 38x slower than the v2 VM row and roughly 160x
slower than the optimized JVM source row on the same public benchmark command.

## Inspection

Captured on 2026-07-09 from the v2-rust bench wrapper path. Legacy
`bin/ssc emit-rust -o ... bench/corpus/recursion-fib.ssc` already emits direct
Rust:

```rust
pub fn fib(n: i64) -> i64 {
    if (n <= 1i64) { n } else { (fib((n - 1i64)) + fib((n - 2i64))) }
}
```

The slow row is therefore not the legacy Rust emitter. The public
`scripts/bench v2-backends recursion-fib` / `ssc --backend v2-rust bench`
path instead builds a v2 wrapper, lowers it to CoreIR through
`FrontendBridge`, and calls `v2/backend/rust/RustBackend.scala`. That generated
source boxed both `fib` and `workload` as `V::Fn(Rc<dyn Fn(Vec<V>) -> V>)`;
recursive calls went through `call_fn(g_fib.clone(), vec![...])`, and arithmetic
went through generic `v_arith` / `as_int` helpers. Dominant overhead hypothesis:
recursive `fib` pays closure allocation/clone, `Vec<V>` argument construction,
boxing/unboxing, and generic arithmetic dispatch at every call.

After adding direct Long-specialized helper generation locally, the emitted
source has the desired production shape:

```rust
fn g_fib_long(p0_g_fib_long: i64) -> i64 {
    if (p0_g_fib_long) <= (1i64) {
        p0_g_fib_long
    } else {
        (g_fib_long((p0_g_fib_long).wrapping_sub(1i64)))
            .wrapping_add(g_fib_long((p0_g_fib_long).wrapping_sub(2i64)))
    }
}

fn g_workload_long() -> i64 {
    g_fib_long(30i64)
}
```

The generic `V::Fn` closures must still be emitted for first-class and non-Long
uses. The direct helper is used only for statically proven `App(Global, args)`
sites whose arguments and result are Long-typed.

Benchmark gotcha: once the Rust source is this direct, LLVM can fold the
zero-argument `g_workload_long()` helper chain to a constant in the bench
binary. A manual bench-only patch to
`g_fib_long(std::hint::black_box(30i64))` restored an honest smoke result
(`BENCH_MS: 1.44545`, `BENCH_SINK: 1385346600`). This is tracked separately in
`BUGS.md#v2-rust-bench-zero-input-helper-fold` and must be fixed in the
benchmark harness before accepting final before/after numbers.

## Results

Implemented in `3d975bda7` on 2026-07-09.

`RustBackend.scala` now performs an optimistic fixed-point over top-level
global lambdas and emits a direct Rust `i64` helper for each lambda whose body
is provably Long-typed. Calls use those helpers only when the callee is a
known global and every argument is also statically Long-typed. The existing
generic `V::Fn(Rc<dyn Fn(Vec<V>) -> V>)` closure remains emitted and populated,
so first-class function values and non-Long calls keep the previous semantics.

`BenchCmd.timeV2Rust` now patches only its temporary benchmark Rust source:
zero-argument Long helpers have their first integer literal wrapped with
`std::hint::black_box(...)` before the file is passed to `rustc -O`. This is a
measurement-only guard against LLVM precomputing a zero-input helper chain; it
does not affect public `emit-rust` output or production artifacts.

Final benchmark:

```bash
scripts/bench v2-backends recursion-fib
```

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-fib` baseline | 5.93 | 1.42 | 226.7 |
| `recursion-fib` final | 6.03 | 1.25 | 1.44 |

Focused v2-rust smoke, proving the anti-folded benchmark is not near-zero:

```bash
bin/ssc --backend v2-rust bench --machine --warmup-time 10 --reps 1 bench/corpus/recursion-fib.ssc
# BENCH v2-rust 1.56
```

Verification:

- `scala-cli compile --server=false v2/backend/rust`
- `scripts/sbtc "installBin"`
- `v2/backend/check.sh bool`
- `v2/backend/check.sh mutual-recursion`
- `v2/backend/check.sh tco`
- `v2/backend/check.sh letrec`
- `tests/conformance/run.sh --only 'recursion,tail-recursion,mutual-recursion' --no-memo`
  (3/3 across INT/JS/JVM)
- `scripts/bench v2-backends recursion-fib`
- `git diff --check`
