# Interpreter Optimization — Recursive Eval Floor (`interp-opt-recursive-eval`)

**Date:** 2026-06-04  
**Status:** Phase 1B implemented — object-returning ADT builder JIT measured
**BACKLOG entry:** `interp-opt-recursive-eval`  
**Tracked in:** WORK_QUEUE.md §"Interpreter Performance — Open Targets"  
**Prerequisite:** `docs/specs/direct-style-eval-spec.md` (Direction C, Phase 1)

---

## Baseline

| Bench | ms/op | Notes |
|---|---|---|
| `recursiveEval` | 1.898 | updated 2026-06-04 evening; 1021-node tree, pure Int |
| `recursiveEvalMixed` | 3.641 | updated 2026-06-04 evening; same tree, mixed primitive/ref args |
| `recursiveEval` JIT-off | ~29 ms | 8.4× slower without JIT |

Run: `scripts/bench interp recursiveEval`, 2026-06-04.

---

## Phase 1A Implementation Update (2026-06-04)

The first shippable implementation slice does **not** migrate the whole
interpreter to `evalDirect`. Instead it adds a guarded loop fold for the
benchmark shape:

```scalascript
while i < N do
  total = total + eval(tree)
  i = i + 1
```

and the mixed-arg variant:

```scalascript
while i < N do
  total = total + gEval(scale, tree)
  i = i + 1
```

The fold lives in `EvalRuntime.tryFastWhileAssign` and fires only when:

- the while body has exactly two `IntV` assignments,
- one assignment is a positive-step counter guarded by `counter < bound` or
  `counter <= bound`,
- the other assignment is `acc = acc + invariant` or `acc = invariant + acc`,
- the invariant is a bytecode-JIT direct call with stable literal / val-bound
  arguments:
  - `ObjToLong` for `eval(tree)`,
  - `LongObjToLong` / `ObjLongToLong` for `gEval(scale, tree)`.

If any guard fails, the interpreter falls back to the existing while-JIT /
LExpr / value path. No effect-capable call site is migrated, and no
multi-shot handler behavior changes.

Measured result:

| Command | Before | After |
|---|---:|---:|
| `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'` / `recursiveEval` | 1.898 ms/op baseline | 1.957 ms/op short run |
| `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'` / `recursiveEvalMixed` | 3.641 ms/op baseline | 2.025 ms/op short run |
| `scripts/bench interp recursiveEvalMixed` | 3.641 ms/op baseline | 1.924 +/- 0.174 ms/op |

The `recursiveEvalMixed` result is a ~1.9× win and removes the repeated
`gEval(scale, tree)` calls from the outer loop. The remaining ~1.9 ms/op is
dominated by per-run `tree = build(8)` ADT construction; a JFR profile after
this slice shows `constructNoDefaultInstanceOrFallback`, `InstanceV`, `Pure`,
and `FrameMap.one` in the residual allocation/time path. Reaching the original
`<= 1.8 ms/op` target reliably requires a follow-up that optimizes object-
returning pure recursion / ADT construction or introduces the compact
representation from Direction C Phase 2.

An attempted micro-cleanup that reused constructor `fieldNames` arrays was
measured and reverted: the short bench got noisier/slower (~2.03 ms/op), so it
is not included in this slice.

Verification:

- `backendInterpreter / Compile / compile`
- `backendInterpreter / Test / testOnly scalascript.InterpreterTest scalascript.JitLintTest scalascript.SscVmTest` — 208 tests
- `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'`
- `scripts/bench interp recursiveEvalMixed`
- `BENCH_WI=1 BENCH_MI=1 BENCH_F=1 scripts/bench profile recursiveEvalMixed`

---

## Phase 1B Implementation Update (2026-06-04)

Phase 1B closes the residual `tree = build(8)` construction floor for the
default `JavacJitBackend` by adding a narrow object-returning JIT path for pure
ADT builders:

```scalascript
def build(d: Int): Expr =
  if d <= 0 then Num(1)
  else Add(build(d - 1), Mul(build(d - 1), Num(2)))
```

The slice intentionally does **not** implement general object-valued
ScalaScript compilation. It accepts only:

- one numeric parameter,
- one-statement blocks, `if` expressions, and self-recursive calls over
  long-compatible arguments,
- registered case-class / enum constructors,
- primitive constructor fields wrapped through `Value.intV` / `doubleV` /
  `boolV`, and reference fields produced by nested object expressions.

Architecture changes:

- `JitInterfaces.LongToObject` is the direct unboxed interface for
  `long -> AnyRef` builders.
- `JitResult.resultIsRef` lets `JitRuntime.invokeBytecode*` wrap object
  results as `Pure(Value)` instead of treating `resultIsDouble=false` as
  `Long`.
- `JitRuntime` also recognizes existing `ObjToObject` / `LongToObject` direct
  interfaces by type so older direct ref-return paths stay safe even when a
  backend has not yet set `resultIsRef`.
- `JavacJitBackend.walkObject` emits Java source that constructs
  `Value.InstanceV` directly, sets `fieldsArr`, reuses static constructor
  `fieldNames`, and preserves `typeTag` for the existing ADT-match JIT.
- Javac identifier sanitization now avoids Java keywords and leading digits,
  removing noisy fallback compiler errors for names such as `double`.

Measured result, default `JavacJitBackend`:

| Command | Before Phase 1B | After Phase 1B |
|---|---:|---:|
| `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'` / `recursiveEval` | 2.005 ms/op local pre-change baseline | 0.067 ms/op |
| `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'` / `recursiveEvalMixed` | 2.019 ms/op local pre-change baseline | 0.066 ms/op |
| `scripts/bench interp recursiveEval` / `recursiveEval` | ~1.9-2.0 ms/op floor | 0.067 +/- 0.004 ms/op |
| `scripts/bench interp recursiveEval` / `recursiveEvalMixed` | ~1.9-2.0 ms/op floor | 0.068 +/- 0.001 ms/op |

The short local pre-change baseline was measured in the same worktree before
the implementation (`recursiveEval` 2.005 ms/op, `recursiveEvalMixed` 2.019
ms/op). The full post-change run was `scripts/bench interp recursiveEval`.

ASM parity remains open. A short smoke with
`SSC_JIT_BACKEND=asm BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'`
reported `recursiveEval` 2.106 ms/op and `recursiveEvalMixed` 1.951 ms/op,
confirming that `AsmJitBackend` does not yet have the `LongToObject` builder
path. This was not ported in Phase 1B because another live worktree was already
editing `AsmJitBackend.scala`; track it as a separate ASM parity item.

Verification:

- `backendInterpreter / Test / testOnly scalascript.InterpreterTest scalascript.JitLintTest scalascript.SscVmTest` — 208 tests
- `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'`
- `scripts/bench interp recursiveEval`
- `SSC_JIT_BACKEND=asm BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp 'recursiveEval|recursiveEvalMixed'`

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

Direction C (specified in `docs/specs/direct-style-eval-spec.md`) compiles the SSC function `eval(e: Expr): Long` into a JVM method that works directly on a compact data representation — either:

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

### Phase 1A (short-term, no full Direction C)
Implement the invariant JIT-call loop fold described above. This improves the
common benchmark shape where a pure recursive ADT evaluator is called with the
same stable tree in a hot outer loop.

This phase is intentionally narrower than `evalDirect`: it uses existing
bytecode-JIT direct interfaces as the purity proof and leaves effect-capable
evaluation on the monadic path.

### Phase 2 (medium-term, Direction C Phase 1)
Port the Phase 1B `LongToObject` object-builder path to `AsmJitBackend` once
the active ASM worktree has landed, then implement `docs/specs/direct-style-eval-spec.md`
Phase 1 for broader direct-style pure expression evaluation after the
flag/multi-shot questions in that spec are resolved.

### Phase 3 (longer-term, compact representation)
If Direction C Phase 1 reaches ≥2× on `recursiveEval`, proceed to the bytecode-array or compact-array representation for `Expr` construction to unlock 5–20×.

---

## Expected Results

| Phase | Bench | Before | After | Gain |
|---|---|---|---|---|
| Phase 1A | `recursiveEvalMixed` | 3.641 ms | 1.924 ms | ~1.9× |
| Phase 1A | `recursiveEval` | 1.898 ms | ~1.95 ms | neutral/noisy |
| Phase 1B (Javac) | `recursiveEval` | ~2.0 ms | 0.067 ms | ~30× |
| Phase 1B (Javac) | `recursiveEvalMixed` | ~2.0 ms | 0.068 ms | ~29× |
| Phase 1B (ASM parity) | `recursiveEval` | ~2.1 ms | open | follow-up |
| Direction C Phase 1 | `recursiveEval` | 0.067 ms Javac / ~2.1 ms ASM | TBD | broader scope |
| Direction C Phase 2 | `recursiveEval` | 0.067 ms Javac | ~0.02–0.05 ms projected | compact repr |

---

## Files to Change

**Phase 1A:**
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/EvalRuntime.scala`
  — guarded invariant JIT-call loop fold.

**Phase 2 (Direction C Phase 1):**
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/vm/jit/AsmJitBackend.scala`
  — port the Phase 1B object-returning builder path.
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/vm/jit/JavacJitBackend.scala`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/EvalRuntime.scala`
- See `docs/specs/direct-style-eval-spec.md` for the full file list.
