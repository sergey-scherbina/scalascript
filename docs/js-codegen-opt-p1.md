# JS Codegen Optimisation — Phase 1

**Date:** 2026-06-03  
**Status:** Landed 2026-06-03 (`957a66f0`; all 1233 conformance tests passed)
**Branch:** `feature/js-codegen-opt-p1`  
**Baseline** (with `--warmup-time 1000ms`):

| Workload              | jvm (ms/iter) | js (ms/iter) | ratio |
| --------------------- | ------------- | ------------ | ----- |
| `arith-loop`          |         0.274 |        0.602 |  2.2× |
| `pattern-match-heavy` |         0.597 |         36.2 |  60×  |
| `recursion-fib`       |          1.35 |         4.32 |  3.2× |
| `recursion-tco`       |         0.027 |        0.112 |  4.1× |
| `tuple-monoid`        |         0.140 |         2.52 |  18×  |

**After Phase 1** (measured):

| Workload              | jvm (ms/iter) | js (ms/iter) | ratio | delta    |
| --------------------- | ------------- | ------------ | ----- | -------- |
| `arith-loop`          |         0.275 |        0.589 |  2.1× | −2%      |
| `pattern-match-heavy` |         0.574 |         35.6 |  62×  | −2%      |
| `recursion-fib`       |          1.28 |         4.37 |  3.4× | ≈        |
| `recursion-tco`       |         0.025 |        0.124 |  5.0× | ≈        |
| `tuple-monoid`        |         0.139 |         4.24 |  30×  | +68% ⚠  |

**Notes on results:**

- `arith-loop` / `recursion-*`: V8's JIT was already optimizing through IIFE and array-destructuring
  patterns. Measured gains are within noise.
- `pattern-match-heavy`: `area()` is now a clean if-else (no TCO wrapper, no IIFE). However the
  dominant cost was never `area()` — it's the `_dispatch(shapes, 'foreach', [closure])` in the
  hot loop: 100K dispatch lookups + 100K closure + Array allocations per workload call. Addressing
  this requires static dispatch elision (js-codegen-opt-p2).
- `tuple-monoid`: **Correctness fix**. Before Fix D the benchmark computed a wrong 3-tuple
  `(1,2,3)` instead of the correct 4-tuple `(1,2,3,4)`. The corrected computation does two
  `Object.assign` allocations per iteration instead of one; the extra allocation accounts for the
  regression. The correct result is now verified. Constant-folding this hot path is deferred to
  js-codegen-opt-p2.

---

## Root-Cause Analysis

### 1. TCO wrapper on non-recursive functions (`pattern-match-heavy`)

**Symptom:** `area(s: Shape): Double` — a pure pattern-match function with no
self-call — generates:

```javascript
function area(_s) {
  let s = _s;
  while(true) {
    return ((_t1 => { ... })(s));   // IIFE match + while(true) wrapper
  }
}
```

**Root cause:** `JsGen.scala:1688` emits the self-TCO trampoline when
`!hasNonTailSelfCall(d.body, fname, tailPos=true)` is true. But for a
**non-recursive** function, this predicate also returns `false` (there are
*zero* non-tail self-calls, because there are zero self-calls). The guard must
also require `anywhereContainsSelfCall(d.body, fname)`.

**Impact:** Every function call to `area` creates an arrow-function object and
invokes it (the IIFE). With 500K `area` calls in the hot loop this dominates.

**Fix A — TCO bypass guard:**  
Add `anywhereContainsSelfCall(d.body, fname)` to the TCO emit condition.
This gates the trampoline on the function actually calling itself.

---

### 2. Match IIFE in statement/tail context (`pattern-match-heavy`, general)

**Symptom:** `s match { case Circle(r) => ... }` in the body of `area`
generates `((_t1 => { if ... })(scrutExpr))` — an arrow function created and
immediately called.  Called 500K times.

**Root cause:** `genExpr(Term.Match)` always produces an IIFE because JS has
no block-expression syntax. But when the match appears as the *last term* in a
function body (expression context turned into a `return`), or inside
`genTcoBody`, we are in statement context and can emit an `if-else` chain with
explicit `return` / param-update + `continue` in each arm.

**Fix B — `genMatchAsStmts`:**  
New helper that emits a match as:
```javascript
const _t1 = scrutExpr;
if (cond1) { const r = _t1.r; return ...; }
else if (cond2) { ... }
else { throw new Error('Match failure: ...'); }
```

Wildcard cases (`case _ => ...`, `case x => ...` with no guard) set `lastWasWildcard`
and close the block with `}` rather than `} else { throw ... }`, producing valid JS
when the exhaustive catch-all is at the end (e.g. sealed enums with a default arm).

Used in:
- `genFunctionBody` — last-term `Term.Match` instead of `return genExpr(match)`
- `genTcoBody` — `Term.Match` arm that routes to tail-call or `return`
- Non-TCO single-expression function with `Term.Match` body

---

### 3. TCO multi-param array allocation (`recursion-tco`)

**Symptom:** `sumTco(n-1, acc+n)` tail call generates:
```javascript
[n, acc] = [(n - 1), (acc + n)];
continue;
```

The destructuring creates a **temporary array** on every TCO iteration — 100K
allocations for `recursion-tco`.

**Root cause:** `genTcoBody` line 2178 / `genMutualTcoBody` line 2144 emit the
array form for multi-param reassignment.

**Fix C — temp-var swap:**  
Evaluate each RHS into individual `const` temps, then assign:
```javascript
const _tco0 = (n - 1), _tco1 = (acc + n); n = _tco0; acc = _tco1;
continue;
```
No heap allocation. Correct even when RHS exprs cross-reference the params (all
RHS evaluated before any LHS written).

**Note:** V8's JIT (scalar replacement / escape analysis) was already eliding the
temporary array in practice, so the measured benchmark improvement is within noise.
The emitted code is nonetheless cleaner and correct-by-construction on all engines.

---

### 4. Tuple `++` infix multi-arg bug (`tuple-monoid`)

**Symptom:** `(1, 2) ++ (3, 4)` generates:
```javascript
_tupleConcat(Object.assign([1, 2], {_isTuple: true}), 3)
```
`(3, 4)` is parsed by scalameta as two separate infix args `[3, 4]`; the
codegen takes only `args.head = 3`, silently dropping `4`. Result is a
**3-tuple** `(1,2,3)` instead of `(1,2,3,4)`.

**Root cause:** `JsGen.scala:3284`:
```scala
case "++" | ":::" => s"_tupleConcat($lhsJs, ${genExpr(args.head)})"
```
When `args.length > 1`, the extra args are dropped.

**Fix D — wrap multi-args as tuple:**
```scala
case "++" | ":::" =>
  val rhsArgJs = args match
    case List(single) => genExpr(single)
    case multi        => s"Object.assign([${multi.map(a => genExpr(a.asInstanceOf[Term])).mkString(", ")}], {_isTuple: true})"
  s"_tupleConcat($lhsJs, $rhsArgJs)"
```

Also fix the same issue in the CPS infix path (`genExpr` line 4042).

---

### 5. Constant tuple concat — deferred to Phase 2

`_tupleConcat(Object.assign([1,2],{_isTuple:true}), Object.assign([3,4],{_isTuple:true}))`
still runs 100K `Object.assign` + spread allocations per workload call after
the bug fix. Full elimination requires loop-invariant code motion or
compile-time constant folding with hoisting. Deferred to **js-codegen-opt-p2**.

### 6. `foreach` dispatch overhead — deferred to Phase 2

`_dispatch(shapes, 'foreach', [closure])` in the pattern-match-heavy hot loop is called
100K times per workload invocation. Each call performs a runtime method-table lookup and
allocates a new closure + single-element Array. After Fix A+B, `area()` itself is a clean
if-else — the dominant remaining cost is this dispatch overhead. Eliminating it requires
static-type-guided direct dispatch (e.g. emitting `shapes.forEach(...)` when the receiver
type is known). Deferred to **js-codegen-opt-p2**.

---

## Implementation Plan

| Slice | Fix | Files | Expected gain |
|-------|-----|-------|---------------|
| A     | TCO bypass guard for non-recursive fns | `JsGen.scala:1688` | cleaner codegen; pattern-match-heavy: removes while(true) wrapper |
| B     | `genMatchAsStmts` in `genFunctionBody` + `genTcoBody` | `JsGen.scala` | eliminates IIFE per match call; wildcard fix required for correctness |
| C     | TCO multi-param temp vars | `JsGen.scala:2178`, `2144` | cleaner code; V8 already elides array |
| D     | Fix `++` multi-arg bug | `JsGen.scala:3284`, `4042` | tuple-monoid: correctness fix |

All four landed in one commit to `feature/js-codegen-opt-p1`.
