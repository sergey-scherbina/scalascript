# JS Codegen Optimisation — Phase 2

**Date:** 2026-06-04  
**Status:** Landed 2026-06-04 (all 1236 conformance tests passed)  
**Branch:** `feature/js-codegen-opt-p2`  
**Baseline** (after Phase 1 correctness fix, which caused +68% regression):

| Workload         | jvm (ms/iter) | js (ms/iter) | ratio |
| ---------------- | ------------- | ------------ | ----- |
| `tuple-monoid`   |         0.139 |         4.24 |  30×  |

**After Phase 2:**

| Workload         | jvm (ms/iter) | js (ms/iter) | ratio | delta  |
| ---------------- | ------------- | ------------ | ----- | ------ |
| `tuple-monoid`   |        ~0.000 |        0.025 |   —   | −99%   |

The `tuple-monoid` workload calls `workload()` which does 100K iterations of `(1,2)++(3,4)`.
After p2, the constant result is hoisted as a frozen object before the loop — the loop body
becomes `last = _k0; i++` with zero heap allocations.

Note: The JVM number is essentially zero after JIT constant-folding, so the ratio is not
meaningful for this workload. The JS number of 0.025 ms represents pure loop overhead for
100K iterations with a single reference copy — no allocations.

---

## Root-Cause Analysis

### Constant tuple allocation in tight loops

**Symptom:** After the Phase 1 `++` bug fix, `(1, 2) ++ (3, 4)` generates:
```javascript
_tupleConcat(Object.assign([1, 2], {_isTuple: true}), Object.assign([3, 4], {_isTuple: true}))
```
In the `tuple-monoid` benchmark (100K iterations), this runs:
- 2× `Object.assign` per iteration = 200K allocations
- 1× `_tupleConcat` call per iteration (spread + Object.assign inside) = 100K calls

**Root cause:** `genExpr(Term.Tuple)` and `genExpr(ApplyInfix(_, "++", _))` always allocate
new objects. When both sides are compile-time constants (all-literal tuples), the result is
invariant across all loop iterations. The value could be computed once and reused.

**Fix — Loop-invariant constant-tuple hoisting:**  
When inside a while-loop body (signaled by `loopHoistBuf != null`), detect that the
`++` expression and both its arguments are all-literal constant tuples. Compile-time fold the
concatenation into a single frozen array, and hoist it as a `const _kN = Object.freeze(...)`
declaration before the `while` statement.

---

## Implementation

### New state in JsGen

```scala
private var loopHoistBuf: mutable.Buffer[(String, String)] | Null = null
private var hoistIdx: Int = 0
```

`loopHoistBuf` is `null` outside a while loop. Each while-loop codegen sets it to a fresh
buffer, generates the body (which may add entries), then restores the parent buffer (supporting
nested loops).

### Helper methods

```scala
private def isLiteralTerm(t: Term): Boolean           // Lit.Int/Double/Float/Long/String/Boolean/Unit
private def isConstantTupleExpr(t: Term): Boolean     // Tuple(lits) | ApplyInfix(constTuple, "++", _, lits)
private def collectConstantTupleElems(t: Term): List[String]  // flatten to JS literal strings
private def freshHoistConst(value: String): String    // register _k<n> in loopHoistBuf, return name
```

### Where while loops are instrumented

Three locations needed instrumentation (each calls `genWhileBodyInline` or `genExpr` for the body):

1. **`genFunctionBody`** — while in normal function body (most common case)
2. **`genBlockStats`** — while at top-level block scope
3. **`genBlockAsIife`** — while inside a multi-stat IIFE (nested block expression)

Each is wrapped with:
```scala
val savedBuf = loopHoistBuf
val newBuf   = mutable.Buffer.empty[(String, String)]
loopHoistBuf = newBuf
val body = genWhileBodyInline(tw.body)     // populates newBuf
val cond = genExpr(tw.expr)
loopHoistBuf = savedBuf
newBuf.foreach { (k, v) => line(s"const $k = $v;") }  // emitted BEFORE the while
line(s"while ($cond) { $body; }")
```

### Constant `++` interception

In `genExpr(Term.ApplyInfix)`, before calling `genExpr(lhs)`, check if the whole `++`
expression is a constant-tuple-concat. If so, fold and hoist:

```scala
val tupleHoist: Option[String] =
  if (op.value == "++" || op.value == ":::") && loopHoistBuf != null &&
     isConstantTupleExpr(lhs) &&
     args.asInstanceOf[List[Term]].forall(e => isLiteralTerm(e) || isConstantTupleExpr(e)) then
    val elems = collectConstantTupleElems(lhs) ++ args.asInstanceOf[List[Term]].flatMap {
      case te: Term.Tuple => collectConstantTupleElems(te)
      case other          => List(genExpr(other))
    }
    Some(freshHoistConst(s"Object.freeze(Object.assign([${elems.mkString(", ")}], {_isTuple: true}))"))
  else None
tupleHoist.orElse(constResult).getOrElse { ... }
```

### Standalone literal tuples

`genExpr(Term.Tuple)` also hoists when inside a loop and all elements are literals:
```scala
if loopHoistBuf != null && elems.forall(isLiteralTerm) then
  freshHoistConst(s"Object.freeze(Object.assign([$elemsJs], {_isTuple: true}))")
else
  s"Object.assign([$elemsJs], {_isTuple: true})"
```

### Generated output (before vs after)

**Before:**
```javascript
function workload() {
  let i = 0;
  let last = Object.assign([0, 0, 0, 0], {_isTuple: true});
  while ((i < 100000)) {
    last = _tupleConcat(Object.assign([1, 2], {_isTuple: true}), Object.assign([3, 4], {_isTuple: true}));
    i = (i + 1);
  }
  return last;
}
```

**After:**
```javascript
function workload() {
  let i = 0;
  let last = Object.assign([0, 0, 0, 0], {_isTuple: true});
  const _k0 = Object.freeze(Object.assign([1, 2, 3, 4], {_isTuple: true}));
  while ((i < 100000)) { last = _k0; i = (i + 1); }
  return last;
}
```

Zero allocations per loop iteration. `Object.freeze` is a hint to V8 that the object is
immutable, enabling additional optimizations.

---

## Measured Results

Direct Node.js measurement (10 iterations, median):

| Implementation       | ms/iter | notes                              |
| -------------------- | ------- | ---------------------------------- |
| Old (Object.assign)  | 5.079   | 200K allocs + 100K _tupleConcat    |
| New (frozen const)   | 0.026   | zero allocs, pure loop overhead    |

JMH RuntimeBench (`-wi 2 -i 3 -f 1`):

| Benchmark              | Score (µs/op) | vs JVM  |
| ---------------------- | ------------- | ------- |
| `js_tupleMonoid`       | 24.677 µs     | —       |
| `jvm_tupleMonoid`      |  0.010 µs     | 2468×   |

JS at 24.677 µs/op = 0.025 ms per `workload()` call (100K iterations). The JVM number is
effectively zero after JIT constant-fold; ratio is not informative for this workload.

---

## Scope and Limitations

**In scope (what p2 handles):**
- `(a, b) ++ (c, d)` where all four elements are integer/double/string/boolean literals
- `(a, b) ++ (c, d, e, f) ++ (g, h)` — arbitrary-depth constant tuple concat chains
- Standalone literal tuples `(a, b, c)` in while bodies

**Not in scope (deferred):**
- Non-literal elements: `(x, 2) ++ (3, 4)` where `x` is a loop variable → not hoisted (correct)
- Loop-invariant non-constant expressions (requires alias analysis) — not addressed
- `foreach` dispatch overhead in `pattern-match-heavy` — still ~35 ms (deferred to p3 if needed)
