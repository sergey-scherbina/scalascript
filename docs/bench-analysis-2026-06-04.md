# Benchmark Analysis — 2026-06-04

Full run after `install.sh` rebuild + overnight agent work (all tasks from
the 2026-06-03 analysis landed).  New baseline established; two new items
identified.

## Protocol used

```
bash install.sh          # rebuild ssc binary
bash bench.sh            # wall-clock cross-backend
scripts/bench interp     # JMH microbenchmarks (wi=3 mi=5 1 fork)
```

## Results

### bench.sh (wall-clock, ms/iter)

```
arith-loop:          interp 0.286   jvm 0.282   js 0.595
effect-pure:         interp 0.057   jvm n/a     js 0.005  ← JS 3× faster
effect-stream:       interp 30.1    jvm n/a     js n/a    ← interp only
hello-world:         interp 0.014   jvm 0.002   js 0.002
pattern-match-heavy: interp 0.554   jvm 0.577   js 35.2   ← interp BEATS JVM
recursion-fib:       interp 1.19    jvm 1.28    js 4.39   ← interp BEATS JVM
recursion-tco:       interp 0.042   jvm 0.029   js 0.126
tuple-monoid:        interp 0.230   jvm 0.137   js 0.027  ← js p2 landed (93×)
```

### scripts/bench interp (JMH, ms/op)

```
arithLoop            0.283 ± 0.020
effectPure           0.016 ± 0.001
instanceFieldAccess  0.041 ± 0.001
mapForeach           0.189 ± 0.021   ← while-jit-map-foreach 11.4× (was 2.14)
matchBodyBaseline    0.044 ± 0.002
nestedMatchExpr      0.044 ± 0.006
patternGuard         0.045 ± 0.005
patternMatchHeavy    0.414 ± 0.047
patternMatchSet      0.202 ± 0.020
patternMatchWide     1.414 ± 0.237   ← open target
pureCallSum          0.263 ± 0.050
pureCallSum2         0.260 ± 0.035
pureCallSumBlock     0.253 ± 0.007
pureCallSumIf        0.265 ± 0.004
recursionFib         1.237 ± 0.179
recursionFibD        1.462 ± 0.189
recursionFibMul      1.321 ± 0.185
recursionFibMulD     1.602 ± 0.223
recursionTco         0.033 ± 0.008
recursiveEval        3.739 ± 0.752   ← physical floor (see below)
recursiveEvalMixed   3.661 ± 0.365   ← physical floor
refChainArg          0.367 ± 0.006
refFieldArg          0.046 ± 0.007
tupleMonoid          0.212 ± 0.028   ← 55% slower than JVM (open target)
```

---

## What landed overnight (from 2026-06-03 analysis)

All five items from the prior analysis landed in a single session:

| Item | Result |
|---|---|
| `jit-match-recursive-descent` | INVOKESTATIC arm self-calls confirmed; 3.57ms IS the floor |
| `while-jit-map-foreach` | `mapForeach` 2.14 → 0.189 ms (11.4×) |
| `phase-c-bytecode-wider-match` | wildcard/catch-all arms; `patternMatchWide` unchanged (floor) |
| `js-codegen-opt-p2` | `tuple-monoid` JS 2.52 → 0.027 ms (93×) |
| `asm-jit-parity Phase 1+2` | AsmJitBackend at full Javac parity incl. while-backend |

---

## Physical floor — `recursiveEval` / `recursiveEvalMixed` ~3.7 ms

WORK_QUEUE records: "3.57 ms represents the achievable floor for 1021-node
INVOKESTATIC tree traversal at ~3.5 ns/node."  The INVOKESTATIC recursive
self-calls were already present in the JIT (`walkLong`'s self-call case) —
the spec's root cause was incorrect.  At 3 GHz, 3.5 ns/node = ~10.5 cycles
per recursive call including typeTag dispatch and fieldsArr extraction.
Nothing to optimize here short of Direction C (direct-style eval).

---

## Remaining targets

### 1. `patternMatchWide` 1.414 ms → ~0.2–0.4 ms

**Item:** `phase-d-patternmatch-fused-foreach` (already open in WORK_QUEUE).

12-constructor ADT, 50K × 12 = 600K `eval` calls via `while-jit-mixed`.
The fused path calls `ObjToLong.apply(o)` per element — one indirect JVM call
per ADT node.  If `JavacJitBackend` compiled the entire `foreach` body inline
(i.e. emitted `switch(o.typeTag) { ... }` inside the fused Java method), HotSpot
could devirtualize and eliminate the indirect call entirely.

Current cost: 1.414ms / 600K = 2.36 ns/call.  A fully fused inline match body
should reach ~0.3–0.5 ns/call (similar to the tight `arithLoop` path).

**Implementation:** extend `tryCompileWhileMixed` — when the inner foreach body
is `o => { acc = acc + fn(o) }` and `fn` compiles to an `ObjToLong` whose body
is a match, emit the entire `switch(inst.typeTag) { ... }` inline in the fused
Java method rather than calling `_fn0.apply(o)`.

### 2. `tupleMonoid` interp 0.212 ms vs jvm 0.137 ms (55% gap)

**Item:** `jit-tuple-concat-hoist` (NEW — not yet in BACKLOG/WORK_QUEUE).

The bench: `while i < 100000 do last = (1,2)++(3,4)`.  Every iteration:
- Allocates `TupleV(List(IntV(1), IntV(2)))` → literal `(1,2)`
- Allocates `TupleV(List(IntV(3), IntV(4)))` → literal `(3,4)`
- Calls `_tupleConcat` → allocates `TupleV(List(IntV(1),IntV(2),IntV(3),IntV(4)))`
- Writes to `last` slot

The JVM backend constant-folds this to a single pre-built object; the interpreter
allocates three times per iteration (100K allocs).

The while-JIT already handles the `i` counter; the `last = ...` assignment
falls through to `EvalRuntime` because its RHS is not a Long expression.

**Fix:** in `tryLongWhileAssign` (or a companion `tryConstAssignHoist`), detect
assignments of the form `name = expr` where `expr` is a **pure constant
expression** — no free names, no side effects, no loop-variable references.
Pre-evaluate the RHS once before the JIT loop and store the `Value` in a
pre-computed ref slot.  The loop body becomes a single ref-store per iteration.

This generalises beyond tuples: any `last = <pure-constant-expr>` inside a
while loop body that the JIT is already handling could benefit.

**Bench target:** `tupleMonoid` 0.212 → ~0.140 ms (JVM parity, ~1.5×).

### 3. `effect-stream` 30.1 ms — investigation needed

**Item:** `effect-stream-jfr` (NEW).

Only runs on interp; JVM/JS have no implementation.  30.1 ms vs `effect-pure`'s
0.016 ms = 1880× slower for the stream variant.  Before proposing any
optimization, run `scripts/bench profile effectStream` (once the bench exists
as a `@Benchmark`) to identify whether the cost is in:
- Continuation tree construction / allocation (stream effects need multi-shot conts)
- Per-step dispatch in `EffectsRuntime`
- GC pressure from `Computation.FlatMap` chains

This is a JFR investigation task, not a direct implementation.

### 4. `effect-pure` JS 3× faster than interp (minor)

`effect-pure`: interp 0.016 ms vs JS 0.005 ms.  JS compiles the inner
`while i < 10000` loop to native V8 bytecode.  Interp routes through
`runLogger` handler overhead.  Low priority — absolute numbers are small.
Worth noting but not worth a dedicated item.

---

## Action items

| Slug | Status | Bench target |
|---|---|---|
| `phase-d-patternmatch-fused-foreach` | open in WORK_QUEUE | `patternMatchWide` 1.41 → ~0.3 ms |
| `jit-tuple-concat-hoist` | **NEW** | `tupleMonoid` 0.212 → ~0.14 ms |
| `effect-stream-jfr` | **NEW** | investigation only |
| `direct-style-eval-spec` | open, deferred | long-term Direction C |
