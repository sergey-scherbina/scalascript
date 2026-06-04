# Interpreter Optimization — Recursive Eval Floor (`interp-opt-recursive-eval`)

**Date:** 2026-06-04  
**Status:** Open — Direction C prerequisite not started  
**BACKLOG entry:** `interp-opt-recursive-eval`  
**Tracked in:** WORK_QUEUE.md §"Interpreter Performance — Open Targets"  
**Prerequisite:** `docs/direct-style-eval-spec.md` (Direction C, Phase 1)

---

## Baseline

| Bench | ms/op | Notes |
|---|---|---|
| `recursiveEval` | 3.383 | 1021-node tree, pure Int |
| `recursiveEvalMixed` | 3.665 | same tree, mixed Int/Double/String |
| `recursiveEval` JIT-off | ~29 ms | 8.4× slower without JIT |

Run: `scripts/bench interp recursiveEval`, 2026-06-04.

---

## Benchmark fixture

```scalascript
// A recursive AST evaluator — archetypal interpreter pattern.
// Expr = Num(n) | Add(l, r) | Mul(l, r) | ...
def eval(e: Expr): Long = e match
  case Num(n) => n
  case Add(l, r) => eval(l) + eval(r)
  case Mul(l, r) => eval(l) * eval(r)
  ...
val tree = build(3)   // 1021-node tree
workload(): eval(tree)
```

The "Mixed" variant adds Double and String leaf types with coercion.

---

## Current Floor Analysis

The `jit-match-recursive-descent` work (landed 2026-06-04) confirmed that the BytecodeJIT already emits INVOKESTATIC self-calls for this pattern — both 1-param (`ObjToLong`: `eval(l)`) and 2-param (`LongObjToLong`: `gEval(scale, l)`) shapes. The JIT is correct and fully applied.

The floor is:

```
3.57 ms / 1021 nodes = 3.5 ns / node
```

At 3 GHz, 3.5 ns/node ≈ 10.5 clock cycles per recursive dispatch including:
- `typeTag` switch dispatch (O(1) since `phase-c-bytecode-int-tag`)
- `fieldsArr` extraction for constructor args
- INVOKESTATIC recursive call overhead
- JVM call stack push/pop

**Sub-3 ns/node is not achievable by optimizing the current `Expr` ADT walk.** Each node requires at least one polymorphic dispatch + two memory accesses (`typeTag`, `fieldsArr[0]`). The INVOKESTATIC instruction itself costs ~3–5 cycles on modern JVMs at steady state; there is no room left.

---

## Path to Improvement: Direction C (Direct-Style Eval)

The fundamental barrier is the `Expr` ADT representation: every node is a heap-allocated `InstanceV` that the evaluator visits via pattern match. Eliminating the ADT means eliminating the per-node allocation and dispatch entirely.

Direction C (specified in `docs/direct-style-eval-spec.md`) compiles the SSC function `eval(e: Expr): Long` into a JVM method that works directly on a compact data representation — either:

1. **Bytecode array** — the `Expr` tree is serialized to a `byte[]` / `int[]` (opcode + payload) during construction; `eval` becomes a tight while loop over the array with a switch; no allocation per evaluation.

2. **LExpr dual-bank** — the `Expr` tree is compiled into `LExpr` dual-bank bytecode (already present for simpler patterns); `eval(e)` dispatch becomes `LApplyR1(jitResult, e)` which calls the compiled Java method directly.

### Option 1 (`byte[]` bytecode): projected gain

A bytecode-array evaluator for a 1021-node tree runs in one JVM method with a tight `switch` over opcodes. No object allocation per node; cache-friendly sequential memory access.

Projected cost: ~0.5–1.5 cycles/node (integer switch + array reads) → ~0.17–0.5 ms total.
Gain: **5–20× over current 3.5 ms**.

### Option 2 (LExpr dual-bank): projected gain

The LExpr dual-bank approach compiles `eval` to a static Java method that receives the `InstanceV` root and recursively calls itself. This is what the current JIT already does — the gain from this option alone is zero beyond what's already landed.

The LExpr path is valuable for the _calling side_: a `while` loop calling `eval(node)` via `LApplyR1(jitResult, e)` avoids the per-call `invoke` overhead currently paid by the interpreter's EvalRuntime.

---

## Short-Term Target (without Direction C)

Without Direction C, the achievable near-term improvement is on the **calling side** rather than the eval core:

- If the `eval` call is inside a JIT-compiled while loop (`while i < n do acc = acc + eval(tree)`), the while-JIT can call the compiled `ObjToLong` method directly — reducing the per-call overhead from ~15 ns (interpreter dispatch + EvalRuntime) to ~3.5 ns (INVOKESTATIC).
- This is already implemented via `LApplyR1` patterns in the LExpr dual-bank.

The 3.5 ms floor applies to the isolated `recursiveEval` bench (single call evaluating a 1021-node tree). For programs that call `eval` in a hot loop, the amortized cost per outer-loop iteration is already near-optimal if the JIT handles the loop.

---

## Implementation Plan

### Phase 1 (short-term, no Direction C)
No code change needed — current floor IS the optimal point for ADT-based evaluation. Document and monitor.

### Phase 2 (medium-term, Direction C Phase 1)
Implement `docs/direct-style-eval-spec.md` Phase 1: dual-bank LExpr compilation for recursive ADT match functions. Target: the `recursiveEval` bench itself becomes the integration test for Direction C Phase 1 correctness.

### Phase 3 (longer-term, compact representation)
If Direction C Phase 1 reaches ≥2× on `recursiveEval`, proceed to the bytecode-array or compact-array representation for `Expr` construction to unlock 5–20×.

---

## Expected Results

| Phase | Bench | Before | After | Gain |
|---|---|---|---|---|
| Current (floor) | `recursiveEval` | 3.38 ms | — | at floor |
| Direction C Phase 1 | `recursiveEval` | 3.38 ms | ~1.5–2 ms | ~2× |
| Direction C Phase 2 | `recursiveEval` | 3.38 ms | ~0.2–0.5 ms | ~8–15× |

---

## Files to Change

**Phase 1:** no changes — status update only.

**Phase 2 (Direction C Phase 1):**
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/vm/jit/JavacJitBackend.scala`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/vm/LExpr.scala`
- See `docs/direct-style-eval-spec.md` for the full file list.
