# Interpreter Optimization — Effect-Pure Gap (`interp-opt-effect-pure`)

**Date:** 2026-06-04  
**Status:** Open — not started  
**BACKLOG entry:** `interp-opt-effect-pure`  
**Tracked in:** WORK_QUEUE.md §"Interpreter Performance — Open Targets"

---

## Baseline

| Backend | Bench | ms/op | Notes |
|---|---|---|---|
| interp | `effectPure` (JMH) | 0.010 | after `effect-pure-pure-path` landed |
| interp | `effect-pure` (bench.sh) | 0.015 | wall-clock |
| js | `effect-pure` (bench.sh) | 0.006 | V8, JS codegen |
| jvm | `effect-pure` (bench.sh) | 0.004 | HotSpot, JVM codegen |

**Gap:** interpreter is 2.5× slower than JS and 3.75× slower than JVM for the same workload.

Run: `bash bench.sh` + `scripts/bench interp effectPure`, 2026-06-04.

---

## Benchmark fixture (`bench/corpus/effect-pure.ssc`)

```scalascript
def compute(n: Int): Int ! Logger =
  var acc = 0
  var i = 0
  while i < n do
    acc = acc + i
    i = i + 1
  acc

def workload(): Int = runLogger { compute(10000) }
```

`compute` is typed as `Int ! Logger` but contains **zero `perform` calls**. The `runLogger` wrapper is required by the effect type but has no actual effect operations to intercept.

---

## Root Cause: Type Erasure Gap

### JS and JVM backends

Both codegen backends perform **effect type erasure at compile time**. Because `compute` has no `perform` call in its body, the compiler emits it as a plain function with no effect machinery:

```javascript
// JS output (approximate)
function compute(n) {
  let acc = 0, i = 0;
  while (i < n) { acc += i; i++; }
  return acc;
}
function workload() { return compute(10000); }
```

No `Computation` wrapper, no trampoline, no handler setup. V8 / HotSpot JIT-compile this to tight native code.

### Interpreter

The interpreter cannot erase effect types at parse/normalize time — it works from the AST and runs through `EffectsRuntime.evalHandle` for every `runLogger { … }` call, regardless of whether `perform` is ever called:

1. `evalHandle(body, cases, env, interp)` is entered
2. `handledOps: Set[(String, String)]` is built from the handler cases
3. `handleInterp(initial)` starts the trampoline while-loop
4. The body is evaluated: `compute(10000)` runs its while loop, returns `Pure(result)`
5. `handleInterp` sees `Pure(_)` → returns immediately
6. `evalHandle` returns `Pure(result)`, caller unwraps the value

Steps 2–3 and 6 are pure overhead. The `Set` allocation and one `Pure(_)` pattern match cost ~4–8 ns per `workload()` call. Over the 10K-iteration inner loop plus measurement overhead, this adds up.

The `effect-pure-pure-path` optimization (landed 2026-06-04) already eliminated the per-`FlatMap` overhead for the while-loop body — but the outer `evalHandle` setup cost remains.

---

## Fix Approach

### 1. Static `noperform` flag in `NormalizedDef`

Add a boolean `noperform: Boolean = false` field to the normalized IR definition (or a `FunctionFlags` bitfield). During `Normalize.apply`, scan the function body for any `Perform(...)` node. If none found, set `noperform = true`.

This is a one-pass structural fold over the normalized term; cost is O(body size), done once at parse time, not on the hot path.

### 2. Interpreter fast-path in `EffectsRuntime.evalHandle`

When the body thunk being handled calls only `noperform`-flagged functions, bypass the trampoline entirely:

```scala
def evalHandle(body: Term, cases: List[Case], env: Env, interp: Interpreter, ...): Computation =
  // Fast path: if body produces a Pure result without any Perform, skip machinery
  val result = interp.eval(body, env)
  result match
    case pure: Pure => return pure          // ← new fast path
    case _ =>
  // ... existing trampoline code
```

Note: this fast-path check is valid because `eval(body, env)` returning a non-`Pure` value means `body` did `perform` something, so we fall through to the existing handler machinery. No semantic change — just an early exit when unnecessary.

### 3. (Optional) Lazy `handledOps` construction

Defer building `handledOps: Set[…]` until the first `Perform` is actually encountered in `handleInterp`. For pure-bodied handlers this avoids the `Set` allocation entirely.

---

## Implementation Plan

1. **Add `noperform` to `NormalizedDef`** in `lang/core/src/main/scala/scalascript/ir/NormalizedModule.scala`.
2. **Populate in `Normalize.apply`** — add `hasPerform(body: IrExpr): Boolean` helper, set `noperform = !hasPerform(body)` on each function def.
3. **Update IR codecs** (upickle `ReadWriter`) — `noperform` has default `false` so existing serialized IR round-trips cleanly.
4. **Add fast-path in `EffectsRuntime.evalHandle`** — check `Pure` immediately after evaluating body, before touching `handledOps`.
5. **Tests:**
   - `NormalizeTest`: confirm `noperform = true` for `compute` in effect-pure fixture
   - `StdEffectsTest`: ensure existing effects still fire (regression gate)
   - Bench: `scripts/bench interp effectPure` and `bash bench.sh` confirm gap closes

---

## Expected Result

| Backend | Before | After | Gain |
|---|---|---|---|
| interp (JMH) | 0.010 ms | ≤ 0.005 ms | ~2× |
| interp (wall) | 0.015 ms | ~0.005 ms | ~3× |
| js (wall) | 0.006 ms | — (reference) | — |
| jvm (wall) | 0.004 ms | — (reference) | — |

Target: interpreter matches JVM parity (≤ 0.005 ms).

---

## Files to Change

- `lang/core/src/main/scala/scalascript/ir/NormalizedModule.scala` — add `noperform` field to `NormalizedDef`
- `lang/core/src/main/scala/scalascript/transform/Normalize.scala` — populate `noperform`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/EffectsRuntime.scala` — fast-path in `evalHandle`
- `lang/core/src/test/scala/scalascript/transform/NormalizeTest.scala` — coverage
