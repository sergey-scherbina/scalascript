# JIT loop fusion — universal iterator-chain fusion

Status: in progress (2026-06-09)
Owner: ssc-jit-loop-fusion-universal
Related: [`specs/jit-universal-coverage.md`](jit-universal-coverage.md) §7 (HOF method dispatch)

## Problem

After Stage 7 (`jit-uc-stage7-hof-method`), the bytecode JIT compiles
iterator chains like

```scala
xs.map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0)((a, b) => a + b)
```

but it does so **step by step**: each `.map` / `.filter` materialises a
fresh intermediate `Value.ListV` via `JitHofDispatch.mapLong` /
`filterLong`, and only the final `.foldLeft` consumes it. The emitted code
is a nest of calls:

```
foldLeftLong(filterLong(mapLong(recv, MUL, 2), MODEQ, 3, 0), 0, ADD)
```

This is correct and already ~20× faster than tree-walking, but every call
allocates one `ListV` (+ boxed `IntV` elements) per intermediate stage.
Measured baseline (`InterpreterBench.hofPipeline`, Javac backend, 6-element
list): **240 B/op**, ~450 MB/s allocation rate, ~1 µs/op.

This is the JIT-level version of the bench-wrapper fused-loop hack landed
under `bench-gap-streams-pipeline-jvm`: move the fusion into the JIT so any
user program with a `map…filter…foldLeft` chain benefits, not just the
bench harness.

## Approach

Detect the chain shape at emit time and lower the whole
`recv.map(f).filter(g).foldLeft(init)(add)` (with optional `.map` / optional
`.filter`) to a **single** runtime helper that walks `recv` once with
primitive `long` accumulators — no intermediate `ListV`, no per-stage
boxing.

### Detection — `JitHofShape.fuseFoldChain`

Pure (no codegen) analyser shared by both backends. Given the `foldLeft`
receiver term, peel an optional outer `.filter(pred)` then an optional
`.map(unary)`, reusing the existing `predicateLong` / `unaryLong` shape
recognisers. Returns a `FoldChain(base, map?, filter?)` descriptor, or
`null` when neither stage is fusable (so the existing per-stage path runs
unchanged — no regression).

Only fuses when:
- the fold function is `foldAdd` (`(a, b) => a + b`), the only fold op
  currently supported by `foldLeftLong`;
- the map lambda matches `unaryLong` (arith on the element);
- the filter lambda matches `predicateLong` (`x % c1 == c2`).

`map` is applied before `filter` in the loop, matching
`.map(f).filter(g)` left-to-right semantics (the filter sees mapped values).

### Runtime — `JitHofDispatch.fusedFoldLong`

```
fusedFoldLong(recv, hasMap, mapOp, mapC, hasFilter, pred, fc1, fc2, init, foldOp): Long
```

Walks a `ListV` receiver once: per element `x = asLong(elem)`; if `hasMap`
then `x = applyUnaryLong(x, mapOp, mapC)`; if `hasFilter` and the predicate
on `x` is false, skip; else `acc += x` (FoldAdd). Unsupported receiver / op
throws so the outer JIT invocation falls back to the interpreter.

### Wiring

Both `JavacJitBackend.emitHofFoldLeftLong` and
`AsmJitBackend.emitHofFoldLeftLong` try `fuseFoldChain` first; on a hit they
emit one `fusedFoldLong` call against `walkRef(base)`. On a miss they fall
through to the existing `foldLeftLong` path.

## Acceptance

- `InterpreterBench.hofPipeline` allocation drops from ~240 B/op toward the
  single boxed-result floor (~16–32 B/op); wall-clock no worse.
- `InterpreterBench.rangeSum` (`(0 until n).map(+1).foldLeft`) loses its map
  intermediate list.
- Full `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` stays green on both
  Javac and ASM backends.
- No corpus miss-profile regression.

## Range-native fusion (landed 2026-06-09, `ssc-jit-range-fusion`)

Follow-up that removed the base range allocation. When the fold base is an
integer range (`lo until hi` / `lo to hi`), `JitHofShape.rangeBounds`
recognises it and the backends emit `JitHofDispatch.fusedRangeFoldLong`,
which iterates a primitive counter `from until until` (the caller passes
`hi + 1` for an inclusive `to`) applying the same optional map/filter and the
`+` fold — **no `ListV` for the range at all**. Covers a bare
`range.foldLeft(0)(+)` (no map/filter) as well. `walkRef` also learned to
compile `to` (inclusive) ranges (was `until`-only), emitting
`rangeUntil(lo, hi + 1)`.

Result: `InterpreterBench.rangeSum` 506 → 25.6 B/op (range base list gone;
1016 → 25.6 B/op vs the pre-fusion baseline). Tests: 4 SscVmTest cases
(Javac+ASM × {`to` map+filter, bare range Gauss sum}) + 3 JitLintTest
`rangeBounds` cases.

## Non-goals

- Fold ops other than `+`, non-numeric elements, `flatMap` stages.
- The SscVm engine (`VmCompiler`) — Javac + ASM only, mirroring how
  `jit-uc-stage7-hof-method` shipped.
- The aggressive `(1 to 10)…foldLeft < 100ns` literal-bound const-fold (the
  range loop now runs the iterations honestly; folding the closed form when
  both bounds are literals tracks with `ssc-jit-const-propagation` Stage 3).
