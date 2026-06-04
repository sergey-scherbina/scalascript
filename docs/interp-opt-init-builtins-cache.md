# Interpreter Optimization — Shared Builtins Cache (`interp-opt-init-builtins-cache`)

**Date:** 2026-06-04  
**Status:** Open — not started  
**BACKLOG entry:** `interp-opt-init-builtins-cache`  
**Tracked in:** WORK_QUEUE.md §"Interpreter Performance — Open Targets"

---

## Background and Motivation

Every `new Interpreter(out)` call currently re-creates ~260 `NativeFnV` objects
via `BuiltinsRuntime.initBuiltins` + `installNativeIntrinsics`. JFR profiling of
the `effectPure` benchmark (`runLogger { compute(10000) }`) revealed:

| Allocator | JFR samples | Weight |
|---|---|---|
| `scalascript.interpreter.Interpreter` | 92 | ~1 MB/sample |
| `java.util.concurrent.ConcurrentHashMap` | 115 | ~1 MB/sample |
| `java.lang.Object[]` | 460 | — |
| `int[]` | 388 | — |
| `scala.Tuple2` | 267 | — |

- **gc.alloc.rate.norm** = 32 KB/op at 0.010 ms/op → allocation rate ~3.2 GB/sec
- **gc.time** = 133 ms over 5 measurement seconds → ~5% of time in GC

The benchmark creates a fresh `Interpreter` per call (intentional cold-start measurement).
After JVM warmup, the JIT-compiled while-loop runs in ~5 µs; Interpreter init takes another
~5 µs, setting the floor at ~0.010 ms. JS codegen at 0.006 ms runs the same loop without
recreating a runtime, so the 1.67× gap is entirely init-dominated.

---

## Root Cause

`BuiltinsRuntime.initBuiltins` is called once per Interpreter instance. It installs
~260 globals: math functions, type constructors, List/Set/Map companions, effect
runners, Logger/Random/Cache/Parallel intrinsics, etc. Each entry creates at least
one `Value.NativeFnV(name, closure)` object.

Additionally, `installNativeIntrinsics` creates a fresh `NativeContext` anonymous class
(capturing `this`) and wraps each `NativeImpl` in a new `args => ...` lambda — one per
entry in `InterpreterIntrinsics + CoreIntrinsics` (~30 entries).

Six `ConcurrentHashMap` instances are also created unconditionally, even for programs
that never use coroutines, Cache effect, parallel async, or remote handlers.

---

## Fix Approach

### Layer 1 — Lazy ConcurrentHashMaps (quick win)

Convert the following fields from eager `val` to `lazy val`:

```scala
// Before
private[interpreter] val coHandles    = new java.util.concurrent.ConcurrentHashMap[Long, CoHandle]()
private val nativeFeatureState        = new java.util.concurrent.ConcurrentHashMap[String, Any]()
private val nativeFeatureLocalState   = new java.util.concurrent.ConcurrentHashMap[String, ThreadLocal[Any]]()
private[interpreter] val _cacheStore  = new java.util.concurrent.ConcurrentHashMap[String, (Long, Value)]()
private[interpreter] val parallelFutures = new java.util.concurrent.ConcurrentHashMap[...]()
private val remoteHandlerRegistry     = new java.util.concurrent.ConcurrentHashMap[...]()

// After
private[interpreter] lazy val coHandles    = new java.util.concurrent.ConcurrentHashMap[...]()
// … etc.
```

Programs that never use coroutines, HTTP features, Cache, parallel, or remote handlers
pay no allocation cost for these maps. Programs that do use them see a one-time init on
first access (volatile read overhead is negligible on the hot path).

**Estimated saving:** ~6 CHM × 300 bytes = ~1.8 KB per Interpreter, ~0.3–0.5 µs.

### Layer 2 — Shared Immutable Builtins Map

The key structural change: split globals into two layers:

```
baseGlobals: immutable.Map[String, Value]   // static, built once per JVM, shared
userGlobals: mutable.HashMap[String, Value] // per-Interpreter, initially empty
```

Lookup order: `userGlobals` first (user-defined or overridden values), then `baseGlobals`
(standard library). Writes always go to `userGlobals`.

For this to work, all `NativeFnV` entries in `baseGlobals` must **not capture a specific
`Interpreter` instance**. Functions that DO need the Interpreter are installed per-instance
into `userGlobals` as before.

#### Classifying globals

**Can go in `baseGlobals` (no per-Interpreter state):**
- Math intrinsics: `math.sqrt`, `math.floor`, `math.ceil`, `math.abs`, `math.min`, `math.max`,
  `math.pow`, `math.log`, `math.exp`, `math.round`, etc.
- Pure type constructors: `Some(v)`, `None`, `Left(v)`, `Right(v)`, `Nil`, `EmptyList`
- Pure collection operations: `List.fill(n)(elem)`, `List.range(from, until)`, `List.empty`,
  `Set.empty`, `Map.empty`, `Set(args)`, etc.
- Effect Perform wrappers: `Logger.info`, `Logger.warn`, `Logger.error`, `Logger.debug`,
  `Counter.tick`, etc. — these only create `Perform(...)` values, no Interpreter needed
- Numeric type coercions: `Int(s)`, `Long(s)`, `Double(s)`, `BigInt(s)` — pure conversions
- CoreIntrinsics that are pure: `System.currentTimeMillis`, `System.nanoTime`, `nowMillis`

**Must stay in `userGlobals` (capture Interpreter or per-instance state):**
- `Console.println` / `Console.print` — use `interp.out`
- `List.tabulate(n)(f)` — calls `interp.callValue1(f, ...)`
- Effect runners (`runLogger`, `runStream`, etc.) — handled as `Term.Apply` special cases
  in `EvalRuntime.eval`, not via globals at all; no change needed
- Any NativeFnV that calls `interp.eval`, `interp.callValue`, `interp.globals`, etc.
- HTTP / SQL / plugin intrinsics — use `ctx` (per-Interpreter context)

**Estimate:** 60–80 of the 260 globals are pure and can move to `baseGlobals`.

#### Implementation sketch

```scala
// BuiltinsRuntime companion — built once at JVM startup
object BuiltinsRuntime:
  val baseGlobals: Map[String, Value] = buildBaseGlobals()

  private def buildBaseGlobals(): Map[String, Value] =
    val m = mutable.HashMap.empty[String, Value]
    m("None")       = Value.NoneV
    m("Nil")        = Value.EmptyList
    m("math.sqrt")  = Value.NativeFnV("math.sqrt",
      Computation.pureFn { case List(Value.DoubleV(d)) => Value.doubleV(math.sqrt(d)); case _ => throw ... })
    // … ~60-80 pure entries …
    m.toMap  // freeze to immutable
```

```scala
// Interpreter class
private[interpreter] val globals = new TwoLayerGlobals(BuiltinsRuntime.baseGlobals)

class TwoLayerGlobals(base: Map[String, Value]):
  private val user = mutable.HashMap.empty[String, Value]
  def apply(name: String): Value = user.getOrElse(name, base(name))
  def get(name: String): Option[Value] = user.get(name).orElse(base.get(name))
  def getOrElse(name: String, default: => Value): Value = user.getOrElse(name, base.getOrElse(name, default))
  def update(name: String, v: Value): Unit = user(name) = v
  // … ++=, iterator, keys, etc. as needed
```

All existing `interp.globals("name") = v` writes in BuiltinsRuntime go to `user`; reads
first check `user`, then `base`. The 60–80 entries that move to `baseGlobals` are never
re-allocated.

**Estimated saving:** 60–80 NativeFnV objects × ~80 bytes = ~5–6 KB per Interpreter,
~1–2 µs saved. Combined with Layer 1: ~1.5–2.5 µs total.

### Layer 3 — Shared NativeContext for Pure Intrinsics (optional)

The 30 `CoreIntrinsics + InterpreterIntrinsics` entries each create an `args => ...` lambda
in `installNativeIntrinsics` that captures the per-Interpreter `ctx`. For intrinsics that
only use `ctx.out` (Console) or nothing (math), `ctx` could be passed via a thread-local
instead of captured, making the lambda sharable.

This is a more invasive change (touches the `NativeImpl`/`NativeContext` SPI). Defer unless
Layer 1+2 are insufficient.

---

## Expected Result

| Scenario | Before | After (L1+L2) | After (L1+L2+L3) |
|---|---|---|---|
| effectPure interp (JMH) | 0.010 ms | ~0.008 ms | ~0.007 ms |
| JS reference | 0.006 ms | — | — |
| JVM reference | 0.004 ms | — | — |
| alloc/op | 32 KB | ~22 KB | ~16 KB |

Full parity with JS (0.006 ms) may require eliminating all per-Interpreter builtin
allocations — possibly achievable with Layer 3 or an Interpreter pool pattern.

---

## Implementation Plan

1. **Layer 1: Lazy CHMs** — convert 6 `ConcurrentHashMap` `val` fields to `lazy val` in
   `Interpreter.scala`. Low risk. All 1292 tests must still pass.

2. **Layer 2: Classify builtins** — read all 234 globals in `BuiltinsRuntime.initBuiltins`
   and mark each as pure or interp-capturing. Move pure ones to a static `buildBaseGlobals()`
   in the `BuiltinsRuntime` companion object.

3. **Layer 2: TwoLayerGlobals** — implement the `TwoLayerGlobals` wrapper in `Interpreter.scala`.
   Verify that all usages (`globals("x")`, `globals.get("x")`, `globals.getOrElse(...)`,
   `globals += (...)`, `globals.keys`, `globals.toMap`, etc.) are handled.

4. **Tests + bench** — `sbt backendInterpreter/test` (1292 tests), then
   `bash bench.sh` + `scripts/bench interp effectPure`.

---

## Files to Change

- `lang/core/src/main/scala/scalascript/interpreter/Interpreter.scala` — lazy CHMs,
  TwoLayerGlobals wrapper
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/BuiltinsRuntime.scala`
  — extract pure globals into `buildBaseGlobals()` companion method
- (Optional) `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/Interpreter.scala`
  — thread-local NativeContext for Layer 3

---

## Risk

**Medium.** The main risk is missing a `globals` access pattern in `TwoLayerGlobals` that
causes a `NoSuchElementException` or silent wrong-value at runtime. Mitigation: run the full
1292-test suite; add a test that verifies `interp.globals.keys.size` after `initBuiltins`.

The lazy CHM fields (Layer 1) carry a `volatile` read overhead on every access. For programs
that DO use those features (coroutines, Cache, parallel), the overhead is negligible (~2 ns
per access). For programs that don't, it's zero (field never initialized).
