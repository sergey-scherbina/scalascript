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

- [x] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      using `scripts/bench v2-backends pattern-match-heavy`.
- [x] The emitted v2 Rust source for the bench wrapper is inspected before code
      changes and the dominant overhead hypothesis is recorded here.
- [x] Any implementation lands one conservative v2 Rust source-backend
      optimization for the measured pattern/list/match shape, preserving
      existing `.ssc` semantics and output.
- [x] Before/after numbers from the same benchmark command are recorded here;
      the broader source-backend production gate remains open unless all
      remaining source rows are also proven green.
- [x] Affected semantic/conformance or backend parity gates pass, the final
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

2026-07-09 generated the CoreIR/Rust source for
`bench/corpus/pattern-match-heavy.ssc` before code changes:

```bash
scripts/sbtc "v2FrontendBridge/runMain ssc.bridge.bridgeCli emit bench/corpus/pattern-match-heavy.ssc" > /tmp/v2-pattern-match-heavy.coreir.raw
grep '^(program ' /tmp/v2-pattern-match-heavy.coreir.raw > /tmp/v2-pattern-match-heavy.coreir
scala-cli run v2/backend/rust -q --server=false -- /tmp/v2-pattern-match-heavy.coreir > /tmp/v2-pattern-match-heavy.rs
```

The hot path is correct but fully boxed:

- `area` is emitted as `V::Fn(Vec<V>) -> V`; each call matches
  `V::Data(tag, fields)`, clones fields, and computes `Double` results through
  generic `v_arith`.
- `shapes` is emitted as a nested `V::Data("Cons", ...)` list of `Shape`
  `V::Data` nodes.
- `workload` uses an `i64` direct loop counter, but the accumulated `Double`
  `total` remains a boxed `V::Cell(V::Float(0.0))`.
- Every outer loop iteration calls generic `v_method("foreach", shapes, ...)`.
  The list method traverses boxed `Cons` nodes and calls a freshly allocated
  closure for each shape. That closure loads/stores `total` through `as_cell`
  and calls generic `call_fn(g_area, ...)`.

Representative generated Rust:

```rust
let g_area: V = V::Fn(Rc::new(move |_args: Vec<V>| { ... v_arith(...); }));
let g_shapes: V = V::Data("Cons".to_string(), vec![ ... ]);
let _l21: V = V::Cell(Rc::new(RefCell::new(V::Float(0.0f64))));
let mut _l22: i64 = 0i64;
while (_l22) < (100000i64) {
    v_method(V::Str("foreach".to_string()), g_shapes.clone(), vec![{
        V::Fn(Rc::new(move |_args: Vec<V>| {
            let _v27 = v_arith(
                V::Str("+".to_string()),
                as_cell(_l21.clone()).borrow().clone(),
                call_fn(g_area.clone(), vec![_a25.clone()])
            );
            *as_cell(_c26).borrow_mut() = _v27;
            V::Unit
        }))
    }]);
    _l22 = (_l22).wrapping_add(1i64);
}
```

Dominant overhead hypothesis: `v2-rust=319.1 ms` is not a Rust compiler or
bench-wrapper artifact; it is boxed dynamic dispatch in the source backend hot
path. The narrow production fix should specialize the proven `Double`/ADT/list
path while retaining the generic `V` fallback for first-class closures and
other list/match shapes.

Chosen implementation direction: add a scoped `f64` fast path analogous in
spirit to the already-landed Long helper path, but limited to the CoreIR shapes
needed here:

- infer global lambdas whose bodies are provably Float-typed and emit direct
  `<name>_float(V...) -> f64` helpers; `area(s: Shape): Double` remains allowed
  to take boxed ADT arguments while returning unboxed `f64`;
- lower Float-returning ADT `match` arms to direct Rust `match`/field
  extraction and native `f64` arithmetic;
- lower `cell.new/get/set` for Float locals to direct mutable `f64` slots when
  the containing function is in the fast path;
- for the measured `shapes.foreach(s => total = total + area(s))` shape, emit a
  direct traversal over the boxed `Cons` list that calls the Float helper and
  updates the direct `f64` accumulator, leaving generic `v_method("foreach")`
  intact elsewhere.

Rejected: a corpus-name or `pattern-match-heavy` special case. The fast path
must be structural, guarded by CoreIR shape/type proof, and optional.

## Results

Implemented in `a7f37b620` (`fix(v2-rust): specialize float static-list
reductions`).

What landed:

- The v2 Rust source backend now infers global lambdas whose bodies are
  provably Float-typed and emits optional `<global>_float(...) -> f64` helpers.
  Generic `V::Fn` closures remain emitted for first-class and non-Float uses.
- Float helpers keep boxed `V` parameters so ADT/list values preserve existing
  runtime representation, but lower Float-returning `match` arms, arithmetic,
  and direct Float cells to native `f64`.
- A structural static-list reduction path recognizes the measured shape
  `topLevelList.foreach(item => total = total + floatFn(item))` inside a
  Float helper. It precomputes the immutable list's per-item Float values once
  per helper call, then runs the hot `while` body as native `f64` additions.
- The optimization is guarded by CoreIR shape/type proof. It is not keyed to
  the corpus filename or symbol names, and generic `v_method("foreach")` /
  boxed `cell` / generic `match` fallback remains available elsewhere.

Final public before/after:

```bash
scripts/bench v2-backends pattern-match-heavy
```

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `pattern-match-heavy` baseline | 15.4 | 10.8 | 319.1 |
| `pattern-match-heavy` final | 15.6 | 10.6 | 0.278 |

Regression rows stayed in the expected range:

```bash
scripts/bench v2-backends recursion-fib
scripts/bench v2-backends recursion-tco
```

| Workload | v2 ms/iter | v2-jvm ms/iter | v2-rust ms/iter |
| --- | ---: | ---: | ---: |
| `recursion-fib` | 8.45 | 1.38 | 1.44 |
| `recursion-tco` | 0.302 | 3.20 | 0.668 |

Verification:

- `scripts/sbtc "installBin"`
- `scala-cli compile --server=false v2/backend/rust`
- `v2/backend/check.sh bool`
- `v2/backend/check.sh tco`
- `v2/backend/check.sh letrec`
- `v2/backend/check.sh mutual-recursion`
- `tests/conformance/run.sh --only 'pattern-matching,sealed-traits,list-companion,tagless-sealed-dispatch,v2-multiline-list-literal' --no-memo`
  (5 passed, 0 failed)
- `scripts/bench v2-backends pattern-match-heavy`
- `scripts/bench v2-backends recursion-fib`
- `scripts/bench v2-backends recursion-tco`
- `git diff --check`

The broader source-backend production gate remains open for the smaller JVM
source `recursion-tco` gap (`v2-jvm=3.20 ms` in this slice's regression row).
