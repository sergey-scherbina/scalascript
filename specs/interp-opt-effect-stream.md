# Interpreter Optimization — Effect-Stream Remaining Gap (`interp-opt-effect-stream`)

**Date:** 2026-06-04  
**Status:** Open — needs fresh JFR profile on new baseline  
**BACKLOG entry:** `interp-opt-effect-stream`  
**Prerequisite:** `effect-stream-opt2` ✓ landed 2026-06-04 (253×)  
**Prior JFR findings:** `docs/effect-stream-jfr-findings.md`

---

## Baseline

| Backend | ms/op (JMH) | ms/iter (bench.sh) | Notes |
|---|---|---|---|
| interp | 0.117 | 0.122 | after effect-stream-opt2 |
| jvm | — | 0.068 | compiled effect handler |
| js | — | 0.322 | JS runtime |

**Gap:** interp 1.8× slower than JVM wall-clock.

Pre-opt2 baseline was 28–30 ms (interp only, no JVM comparison available then). The 253× gain from opt2 brought the interpreter from being pathologically slow to near-JVM range.

---

## Benchmark fixture

```scalascript
def workload(): Int =
  val (src, _) = runStream {
    var i = 0
    while i < 10000 do
      Stream.emit(i)
      i = i + 1
  }
  val lst = src.runToList()
  lst.length
```

---

## What `effect-stream-opt2` fixed (and what it did not)

`effect-stream-opt2` implemented a **FastTier while-emit detector** in `EvalRuntime`: when the interpreter sees `runStream { while i < N do Stream.emit(expr); i = i + 1 }`, it compiles the body to a tight buffer-fill loop:

```scala
val buf = ListBuffer.empty[Value]
var i = 0
while i < n do
  buf += evalPure(emitExpr, env, interp)
  i += 1
// then wraps in Pure(TupleV(ListV(buf.toList), UnitV))
```

This bypasses the Free Monad trampoline entirely for the `while … emit` body. **253× gain.**

**Not covered by opt2:**
1. The `runStream { }` handler-boundary setup (entering/exiting `EffectsRuntime.evalHandle`)
2. `src.runToList()` — the list accumulation step that materializes the stream into a `List[Value]`
3. Any `Stream.emit` that is NOT directly inside a `while` body (e.g. in a helper function call)

The remaining 0.12 ms vs 0.068 ms JVM gap lives in items 1–3.

---

## Root Cause (hypothesis — needs JFR verification)

The JVM backend compiles `runStream { while … emit }` to a native Java method at build time. Its `runToList()` equivalent is likely an `ArrayList` copy, not a Scala `List` construction.

The interpreter's `src.runToList()` walks a `Value.ListV` linked structure. If `buf.toList` in opt2's buffer-fill path produces a Scala `List` (singly linked), then `runToList()` traverses it. At 10K elements, this is 10K cons cell allocations + 10K traversal steps.

**Hypotheses (to verify with JFR):**
1. `ListBuffer → toList` allocates 10K `scala.collection.immutable.::` cons cells per call.
2. The `runStream` handler setup still does `handledOps: Set[(String, String)]` construction per call (same issue as `interp-opt-effect-pure`).
3. There may be a remaining trampoline step at the `Pure(result)` boundary after the while-emit fast path exits.

---

## Investigation Plan

```bash
# Step 1: fresh JFR alloc profile on current 0.12ms baseline
scripts/bench profile effectStream

# Step 2: interpret the alloc.rate.norm output
# Look for: ListBuffer, $colon$colon (List cons), Set construction, Pure wrapping
```

Then, based on findings:

### If List cons allocation dominates (`runToList` path)

Replace `buf.toList` in the opt2 fast-path with a `Value.ListV` backed by a JVM `ArrayList` (or `Array`), and make `runToList()` return a pre-built `Value.ListV` without re-traversal. This is a 2-file change in `EvalRuntime` + `Value.scala`.

### If `handledOps` Set construction dominates (handler setup)

Apply the same `noperform`-style analysis from `interp-opt-effect-pure`: detect that `Stream.emit` IS a perform call and short-circuit to the fast-path without full handler setup.  
Alternatively, lazy `handledOps` construction (build only on first Perform).

### If trampoline remnant remains (Pure boundary)

The opt2 fast-path returns `Pure(TupleV(...))`. If `evalHandle` then enters the trampoline loop to unwrap this, add a `Pure(_) =>` early-exit guard at the top of `handleInterp` before the trampoline start (same fix as `interp-opt-effect-pure`'s approach).

---

## Expected Result

| Bench | Before | After | Gain |
|---|---|---|---|
| `effectStream` (JMH) | 0.117 ms | ≤ 0.080 ms | ~1.5× |
| `effect-stream` (wall) | 0.122 ms | ≤ 0.080 ms | ~1.5× |

JVM parity (0.068 ms) requires all three fixes above. Starting with the dominant allocator from JFR should close most of the gap in one focused commit.

---

## Files Likely to Change

- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/EvalRuntime.scala` — opt2 fast-path buffer flush, `runToList` integration
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/EffectsRuntime.scala` — lazy `handledOps` or early Pure exit
- `lang/core/src/main/scala/scalascript/interpreter/Value.scala` — `ListV` mutable-builder if chosen
