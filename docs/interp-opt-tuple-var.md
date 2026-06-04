# Interpreter Optimization — Tuple Variable Mutation (`interp-opt-tuple-var`)

**Date:** 2026-06-04  
**Status:** Open — not started  
**BACKLOG entry:** `interp-opt-tuple-var`  
**Tracked in:** WORK_QUEUE.md §"Interpreter Performance — Open Targets"

---

## Baseline

| Bench | ms/op | Comparison |
|---|---|---|
| `counterWithTupleVar` | 58.751 | **230× slower than `arithLoop`** |
| `arithLoop` | 0.256 | reference |
| `tupleMonoid` | 0.013 | constant-RHS hoisted (fast path) |

Run: `scripts/bench interp counterWithTupleVar`, 2026-06-04.

---

## Benchmark fixture

```scalascript
var i = 0
var last = (0, 0, 0, 0)
while i < 1000000 do
  last = last        // RHS reads a var — not hoistable
  i = i + 1
last
```

The bench is the minimal representative of the general pattern: `tupleVar = <expr-involving-tupleVar>`. Real programs hit this with `point = (point.x + dx, point.y + dy)` or `state = (state.count + 1, state.sum + v)`.

---

## Root Cause

The while-loop JIT optimizer (`tryLongWhileAssign` / `tryMixedLongWhile` in `JavacJitBackend`) compiles loops only when the assignment RHS is a **pure constant expression** — no free var reads, no side effects. When the RHS references the loop-body var itself (here `last = last`), or more generally any `Ref`-typed slot, the JIT bails out and falls through to the value-space interpreter loop.

The value-space loop evaluates `last` → reads the `TupleV` object → writes it back unchanged. Even for this identity case, the Scala path goes through:

1. `EvalRuntime.eval(Term.Name("last"), env)` — lookup in env HashMap
2. `EvalRuntime.eval(Term.Assign(...), env)` — write back to env
3. **No** `TupleV` allocation for identity, but full interpreter overhead (HashMap reads/writes) per iteration

For the more general `point = (point.x + dx, point.y + dy)` case, each iteration additionally allocates a new `TupleV`.

At 1M iterations, the interpreter overhead alone (~60 ns/iter overhead vs ~0.26 ns/iter for the JIT-compiled `arithLoop`) dominates entirely.

---

## Fix Approach

### Phase 1 — Recognize var-reads as JIT-eligible RHS

Relax the "pure constant" constraint in `tryMixedLongWhile` (and its companions) to allow RHS expressions whose only free variables are **already-tracked slots in the current `SlotTable`**.

A RHS is "slot-pure" if:
- It is a literal (`IntV`, `DoubleV`, `StringV`, `TupleV` of literals) — already handled
- It is a `Term.Name` that resolves to a tracked slot — **NEW**
- It is a tuple concat `(a) ++ (b)` where `a` and `b` are slot-pure — **NEW**
- It is a tuple literal `(e1, e2, ...)` where each `ei` is slot-pure — **NEW**

For the `last = last` case specifically: the RHS slot equals the LHS slot. This is an identity assignment and can be emitted as a no-op (or elided entirely) in the generated Java.

For the general `last = (last._1 + 1, last._2)` case: the tuple fields are extracted as Long slots, a new `TupleV` is constructed once per JIT-compiled iteration (still allocating, but inside JIT-compiled Java, which HotSpot can EA-optimize for loop-invariant sub-expressions).

### Phase 2 — EA-friendly TupleV construction in JIT output

When the JIT emits `TupleV` allocation in the generated Java method body, structure it so HotSpot's escape analysis can eliminate the allocation when the tuple doesn't escape. This requires:
- Store tuple fields in local `long` / `Object` variables
- Construct `TupleV` only at the assignment point (not in a helper method)
- Avoid boxing through `List.apply` — use direct `TupleV(Array(v1, v2, ...))` constructor if available

---

## Implementation Plan

1. **Profile first:** `scripts/bench profile counterWithTupleVar` — confirm `TupleV` and `HashMap$Node` dominate the allocation profile.
2. **Add `isSlotPure(expr, slotTable)` predicate** to `JavacJitBackend`. Returns `true` for literals, tracked slot reads, and slot-pure composite exprs.
3. **Extend `tryMixedLongWhile`** to accept RHS matching `isSlotPure`. Emit slot reads as Java local variable references.
4. **Handle identity case** `last = last` → emit no-op (or skip the `TupleV` write entirely when slot hasn't changed).
5. **Test:** add `counterWithTupleVar`-shaped test to `SscVmTest` (or `WhileJitTest` if it exists). Verify functional correctness first, then bench.

---

## Expected Result

| Bench | Before | After | Target gain |
|---|---|---|---|
| `counterWithTupleVar` | 58.751 ms | ≤ 6 ms | ≥ 10× |

The identity `last = last` case should become essentially free (JIT no-op). The general `last = (expr1, expr2)` case reaches JVM-compiled loop speed (~0.3–1 ms for 1M iters depending on HotSpot EA success).

---

## Files to Change

- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/vm/jit/JavacJitBackend.scala` — `tryMixedLongWhile`, `isSlotPure`
- `runtime/backend/interpreter-bench/src/main/scala/scalascript/bench/InterpreterBench.scala` — update `counterWithTupleVar` note once target is achieved
- `WORK_QUEUE.md` — table entry, BACKLOG checkbox
