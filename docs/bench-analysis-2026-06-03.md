# Benchmark Analysis — 2026-06-03

Full `scripts/bench interp` run after landing `while-jit-inline-match` merge
and all prior dual-bank / Phase C-D work.  Identifies the three remaining slow
spots and proposes concrete JIT extensions for each.

## Current baseline (2026-06-03, default flags ON)

```
InterpreterBench.arithLoop            0.270 ± 0.036  ms/op   ← reference (parity JVM)
InterpreterBench.effectPure           0.015 ± 0.003  ms/op
InterpreterBench.instanceFieldAccess  0.042 ± 0.006  ms/op   ← 195× win from while-jit-inline-match
InterpreterBench.mapForeach           2.142 ± 0.230  ms/op   ← SLOW #2
InterpreterBench.matchBodyBaseline    0.045 ± 0.006  ms/op
InterpreterBench.nestedMatchExpr      0.043 ± 0.003  ms/op
InterpreterBench.patternGuard         0.046 ± 0.008  ms/op
InterpreterBench.patternMatchHeavy    0.405 ± 0.010  ms/op
InterpreterBench.patternMatchSet      0.201 ± 0.026  ms/op
InterpreterBench.patternMatchWide     1.527 ± 0.165  ms/op   ← SLOW #3
InterpreterBench.pureCallSum          0.273 ± 0.002  ms/op
InterpreterBench.pureCallSum2         0.281 ± 0.046  ms/op
InterpreterBench.pureCallSumBlock     0.278 ± 0.030  ms/op
InterpreterBench.pureCallSumIf        0.287 ± 0.043  ms/op
InterpreterBench.recursionFib         1.262 ± 0.144  ms/op
InterpreterBench.recursionFibD        1.504 ± 0.200  ms/op
InterpreterBench.recursionFibMul      1.330 ± 0.106  ms/op
InterpreterBench.recursionFibMulD     1.666 ± 0.210  ms/op
InterpreterBench.recursionTco         0.034 ± 0.002  ms/op
InterpreterBench.recursiveEval        3.570 ± 0.054  ms/op   ← SLOW #1
InterpreterBench.recursiveEvalMixed   3.600 ± 0.388  ms/op   ← SLOW #1b
InterpreterBench.refChainArg          0.375 ± 0.048  ms/op
InterpreterBench.refFieldArg          0.046 ± 0.006  ms/op
InterpreterBench.tupleMonoid          0.202 ± 0.034  ms/op
```

`bench.sh` wall-clock (interp vs jvm):

```
arith-loop:          0.358  vs jvm 0.268
effect-pure:         0.055  vs jvm n/a
hello-world:         0.011  vs jvm 0.002
pattern-match-heavy: 0.674  vs jvm 0.652   ← near-parity
recursion-fib:       1.20   vs jvm 1.42    ← interp BEATS JVM
recursion-tco:       0.040  vs jvm 0.028
tuple-monoid:        0.198  vs jvm 0.139
```

---

## Slow spot #1 — `recursiveEval` / `recursiveEvalMixed` (~3.57 ms)

### Workloads

```scala
// recursiveEval
def eval(e: Expr): Int = e match
  case Num(n)    => n
  case Add(l, r) => eval(l) + eval(r)    // recursive — arm-bound l, r
  case Mul(l, r) => eval(l) * eval(r)

// recursiveEvalMixed
def gEval(scale: Int, e: Expr): Int = e match
  case Num(n)    => n * scale
  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
```

Both call `build(8)` to construct a tree of 511 nodes once, then loop 1000 times.

### Root cause

The outer `while i < 1000 do total += eval(tree)` is compiled by
`tryLongWhileAssign` via `LApplyR1(LRefConst(tree), jitResult)`.  The JIT
emits a static `ObjToLong` method for `eval`.  **Inside that method**, the
recursive calls `eval(l)` and `eval(r)` — where `l` and `r` are arm-bound
`Object` locals from the match destructuring — are **not** compiled to bytecode.
`walkMatchBody` processes the arm body `eval(l) + eval(r)` and bails because
`walkRefArgCtx` only handles `LRefConst` (TLS-preloaded globals) and
`LRefFieldGet` (field selects on globals), not arm-local Object variables.

Each recursive `eval(l)` call crosses the JVM→interpreter boundary, running
`Interpreter.interp()` for every node in the 511-node tree × 1000 iters = 511 K
interpreter re-entries per bench op.

### Fix: `jit-match-recursive-descent`

In `JavacJitBackend.walkMatchBody`, add a case for `Term.Apply(fnName, List(arg))`
(and the 2-arg variant for `gEval`) where:
- `fnName` resolves to the same function currently being JIT-compiled
- `arg` is a `Term.Name` that refers to an arm-bound variable

Arm-bound variables are already `Object` locals in the generated Java method
(extracted from `inst.fieldsArr[i]` at arm entry).  The recursive call becomes
a direct INVOKESTATIC to the same static method:

```java
// For: case Add(l, r) => eval(l) + eval(r)
// Generated arm body:
Object _l = inst.fieldsArr[0];
Object _r = inst.fieldsArr[1];
return _evalFn(_l) + _evalFn(_r);   // same static method, INVOKESTATIC
```

For `recursiveEvalMixed` — 2-arg `gEval(scale, l)` where `scale` is a `long`
loop variable and `l` is an arm-local `Object`:

```java
return _gEvalFn(_scale, _l) + _gEvalFn(_scale, _r);
```

`scale` is already in scope as a `long` param; arm variables as `Object` params.

**Implementation sites:**
- `JavacJitBackend.walkMatchBody` — add `Term.Apply` self-recursive arm case
- `JavacJitBackend.walkLong` — already handles the outer while + LApplyR1/R2 paths;
  the recursive self-call emits the static method name stored in `emitCtx.selfMethod`

**Expected gain:** `recursiveEval` 3.57ms → ~0.05–0.2ms (fully in-bytecode tree walk,
511 nodes × 1000 iters = 511K INVOKESTATIC calls per op at ~1–3 ns each).

---

## Slow spot #2 — `mapForeach` (~2.14 ms)

### Workload

```scala
val m = Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
while i < 100000 do
  m.foreach((k, v) => { total = total + v })
```

500 K per-entry accumulate operations (5 entries × 100 K outer iters).

### Root cause

`fasttier-2arg-callentry` (landed 2026-06-02) added `tryLongAccumForeachMap`
which detects `(p1, p2) => acc += param` and pre-resolves the accumulator.
`fast-map-foreach-preresolved` (landed 2026-06-03) gave another ~10% by
caching the pre-resolved slot.  These wins brought `mapForeach` from 532ms
down to ~2.14ms.

The remaining bottleneck is HashMap **iteration** overhead.  At 500K iterations
/ 2.14ms = 4.28 ns/iter, vs `patternMatchHeavy`'s ~1.35 ns/iter (List.foreach
via `while-jit-mixed`).  The `while-jit-mixed` path fuses the outer while +
inner `xs.foreach(fn)` into a single Java static method where HotSpot can
devirtualize the per-element call; `Map.foreach` has no such fused path and
still calls Scala's `HashMap.foreach` each outer iteration.

### Fix: `while-jit-map-foreach`

Extend `tryCompileWhileMixed` (or add `tryCompileWhileMapForeach`) to recognise:

```scala
while i < N do
  m.foreach((k, v) => { acc = acc + v })
  i = i + 1
```

where `m` is a val-bound `MapV`.  Generate Java that calls `HashMap.entrySet()`
once per outer iter and iterates natively:

```java
// inner part of the fused outer-while static method
for (java.util.Map.Entry<String, Object> entry :
        ((Value.MapV) _refs[0]).javaMap().entrySet()) {
    _slot_acc += asLong(entry.getValue());
}
```

The `k` param is unused in the accumulator case (common pattern); when `k` is
used the same `entry.getKey()` extraction applies.

**Implementation sites:**
- `WhileJitEntry` — add a `mapLongFns` slot carrying a `(String, Object) => Long`
  closure (or just an `accSlot` int and a flag for the common "use value" case)
- `JitGlobals.getMapLongFns` — parallel to existing `getRefLongFns` / `getRefDoubleFns`
- `JavacJitBackend.tryWhileJitMap` — new method; recognises `DispatchMap.foreach`
  call as the inner body and emits an `entrySet()` for-loop

**Expected gain:** `mapForeach` 2.14ms → ~0.15–0.35ms (comparable to
`patternMatchHeavy` per-element cost once HashMap iteration overhead removed).

---

## Slow spot #3 — `patternMatchWide` (~1.53 ms)

### Workload

```scala
val ops = List(A(1), B(1), …, L(1))   // 12 constructors
while i < 50000 do
  ops.foreach(o => { total = total + eval(o) })
```

12 constructors × 50K outer iters = 600K `eval` calls.

### Root cause

At 1.527ms / 600K = 2.54 ns/call this is already very close to native speed.
`while-jit-mixed-foreach` (landed 2026-06-03) fuses the outer while + inner
List.foreach into a single Java method; `phase-c-bytecode-int-tag` added
tableswitch O(1) dispatch.  The remaining gap vs `matchBodyBaseline` (0.045ms,
1M iters of a 2-arm match, ~0.045ns/iter) is partly HotSpot constant-folding
the single val-bound item in `matchBodyBaseline` and partly the 12-arm vs 2-arm
switch width.

**This spot is covered by the existing open items:**
- `phase-c-bytecode-wider-match` — larger pattern subset (guards, Bind,
  Alternative); wider match coverage reduces bail-outs and may unlock further
  HotSpot devirtualization.
- `phase-d-patternmatch-fused-foreach` — BytecodeJit fused foreach that
  compiles `area(s) match` directly against the `fieldsArr` representation.

No new item needed here; prioritise `phase-d-patternmatch-fused-foreach` after
the two new items above.

---

## Minor observation — `recursionFibD` / `recursionFibMulD` overhead (~20%)

`recursionFibD` (1.504ms) and `recursionFibMulD` (1.666ms) run ~19% and ~32%
slower than their Int counterparts (1.262ms / 1.330ms).  This is expected: the
Double JIT path uses `double` Java primitives which incur wider operand encoding
in JVM bytecode and slightly larger stack frames.  Not a blocker; well within
JVM-parity range.  No new item.

---

## Action items

| Slug | Bench target | Est. gain |
|---|---|---|
| **`jit-match-recursive-descent`** (NEW) | recursiveEval 3.57→~0.1ms, recursiveEvalMixed 3.60→~0.15ms | ~30-35× |
| **`while-jit-map-foreach`** (NEW) | mapForeach 2.14→~0.2ms | ~10× |
| `phase-d-patternmatch-fused-foreach` (existing) | patternMatchWide 1.53→~0.3ms | ~5× |
| `phase-c-bytecode-wider-match` (existing) | coverage cleanup | incremental |
| `js-codegen-opt-p2` (existing) | tupleMonoid JS 2.52→<0.1ms | ~25× |
