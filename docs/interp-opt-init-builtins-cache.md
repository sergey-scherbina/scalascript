# Interpreter Optimization — Shared Builtins Cache (`interp-opt-init-builtins-cache`)

**Date:** 2026-06-04  
**Status:** In implementation — L1 + env-map fix measured; shared builtins cache deferred
**BACKLOG entry:** `interp-opt-init-builtins-cache`  
**Tracked in:** WORK_QUEUE.md §"Interpreter Performance — Open Targets"

---

## Background and Motivation

Every `new Interpreter(out)` call currently re-creates the interpreter runtime
surface for a tiny program. The first hypothesis was that the dominant cost was
~260 `NativeFnV` objects from `BuiltinsRuntime.initBuiltins` +
`installNativeIntrinsics`. JFR profiling of the `effectPure` benchmark
(`runLogger { compute(10000) }`) revealed:

| Allocator | JFR samples | Weight |
|---|---|---|
| `scalascript.interpreter.Interpreter` | 92 | ~1 MB/sample |
| `java.util.concurrent.ConcurrentHashMap` | 115 | ~1 MB/sample |
| `java.lang.Object[]` | 460 | — |
| `int[]` | 388 | — |
| `scala.Tuple2` | 267 | — |

- **gc.alloc.rate.norm** = 32 KB/op at 0.010 ms/op → allocation rate ~3.2 GB/sec
- **gc.time** = 133 ms over 5 measurement seconds → ~5% of time in GC

The benchmark creates a fresh `Interpreter` per call (intentional cold-start
measurement). After JVM warmup, the JIT-compiled while-loop runs in ~5 µs;
Interpreter init takes another ~5 µs, setting the floor at ~0.010 ms. JS codegen
at 0.006 ms runs the same loop without recreating a runtime, so the 1.67× gap is
init-dominated.

During implementation, a second profile after making the obvious `Interpreter`
maps lazy showed that `ActorInterp.$init$` was also on the cold path. In
particular, `scala.sys.env` materialized a Scala environment `Map` for every
fresh interpreter just to check `SSC_CLUSTER_DEBUG` / `SSC_CLUSTER_TOKEN`.
Replacing those reads with direct `java.lang.System.getenv(name)` removed the
dominant residual allocation site.

---

## Root Cause

The refined root cause is a bundle of per-interpreter cold-start allocations:

1. `BuiltinsRuntime.initBuiltins` installs ~260 globals: math functions, type
   constructors, List/Set/Map companions, effect runners, Logger/Random/Cache/
   Parallel intrinsics, etc. Each entry creates at least one
   `Value.NativeFnV(name, closure)` object.
2. `installNativeIntrinsics` creates a fresh `NativeContext` anonymous class
   (capturing `this`) and wraps each `NativeImpl` in a new `args => ...` lambda,
   one per entry in `InterpreterIntrinsics + CoreIntrinsics` (~30 entries).
3. `Interpreter.scala` eagerly creates several `ConcurrentHashMap` instances for
   features that tiny pure/effect benchmarks never use: coroutines, native
   feature state, Cache effect, parallel async, and remote handlers.
4. `ActorInterp.scala` eagerly creates many cluster/actor maps, queues, and
   atomics even when the program never starts the actor/cluster runtime.
5. `scala.sys.env` in `ActorInterp.$init$` materializes the full process
   environment map per interpreter.

---

## Fix Approach

### Layer 1 — Lazy Cold State + Direct Env Reads (landed in this slice)

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

Also convert the cold actor/cluster state in `ActorInterp.scala` from eager
`val` to `lazy val`: peer maps, registries, queues, failure detector state,
leader/raft/drain/pubsub/metrics state, reconnect sets, and remote monitor/link
tables. Programs that never use coroutines, HTTP features, Cache, parallel,
remote handlers, actors, or cluster features pay no allocation cost for these
structures. Programs that do use them see a one-time init on first access.

Replace:

```scala
sys.env.get("SSC_CLUSTER_DEBUG")
sys.env.getOrElse("SSC_CLUSTER_TOKEN", "")
```

with:

```scala
Option(java.lang.System.getenv("SSC_CLUSTER_DEBUG"))
Option(java.lang.System.getenv("SSC_CLUSTER_TOKEN")).getOrElse("")
```

so interpreter construction does not allocate a Scala copy of the process
environment.

**Measured saving:** `effectPure` 0.010 ms/op → 0.005 ms/op in the short bench;
profile allocation 32,208 B/op → 8,728 B/op.

### Layer 2 — Shared Immutable Builtins Map (evaluated, deferred)

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

Implementation note: a smaller shared-cache experiment for pure builtin `Value`
instances saved only about 70 B/op in the `effectPure` profile and made the
short JMH run noisier. The full `TwoLayerGlobals` wrapper would still be
possible, but after Layer 1 + env reads the benchmark already beats the L1+L2
target, so the wrapper is not part of this slice.

### Layer 3 — Shared NativeContext for Pure Intrinsics (optional)

The 30 `CoreIntrinsics + InterpreterIntrinsics` entries each create an `args => ...` lambda
in `installNativeIntrinsics` that captures the per-Interpreter `ctx`. For intrinsics that
only use `ctx.out` (Console) or nothing (math), `ctx` could be passed via a thread-local
instead of captured, making the lambda sharable.

This is a more invasive change (touches the `NativeImpl`/`NativeContext` SPI). Defer unless
Layer 1+2 are insufficient.

---

## Expected Result

| Scenario | Before | After L1 + direct env |
|---|---:|---:|
| `BENCH_WI=1 BENCH_MI=2 BENCH_F=1 scripts/bench interp effectPure` | 0.010 ms/op | 0.005 ms/op |
| `BENCH_WI=1 BENCH_MI=1 BENCH_F=1 scripts/bench profile effectPure` | 0.013 ms/op | 0.006 ms/op |
| `gc.alloc.rate.norm` from profile bench | 32,208 B/op | 8,728 B/op |

The slice reaches the original `<= 0.008 ms/op` target without the shared
builtins layer. Full parity with the JVM reference may still require eliminating
more per-interpreter builtin/native wrapper allocations or introducing an
interpreter pool pattern.

---

## Implementation Plan

1. **Baseline** — run `scripts/bench interp effectPure` and
   `scripts/bench profile effectPure` with the short bench env overrides.
2. **Layer 1: Lazy cold state** — convert the unused-on-tiny-program
   `ConcurrentHashMap`, queue, deque, set, and atomic-reference state in
   `Interpreter.scala` and `ActorInterp.scala` to `lazy val`.
3. **Direct env reads** — replace `scala.sys.env` in actor/cluster init with
   `java.lang.System.getenv(name)`.
4. **Evaluate shared builtins cache** — measure before keeping it. Defer if the
   allocation/time saving is below noise or the L1 target is already met.
5. **Tests + bench** — `backendInterpreter / Compile / compile`, targeted
   actor/interpreter tests, then `scripts/bench interp effectPure` and
   `scripts/bench profile effectPure`.

---

## Files to Change

- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/Interpreter.scala`
  — lazy cold interpreter state
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/ActorInterp.scala`
  — lazy cold actor/cluster state and direct env reads
- Deferred: `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/BuiltinsRuntime.scala`
  — full `TwoLayerGlobals` / shared pure builtins layer, only if a future profile
  shows enough remaining cold-start cost to justify the extra globals abstraction.

---

## Risk

**Low-Medium.** This slice avoids the globals abstraction risk by not changing
lookup semantics. The main risk is that a lazily initialized actor/cluster field
was relied on for eager side effects. The selected fields are containers or
atomic references; their construction has no observable side effect beyond
allocation. Mitigation: run targeted actor/cluster tests plus compile, then
re-run the cold-start bench.

The lazy fields carry a one-time lazy-init check and a volatile read after first
access. For programs that do use those features, the overhead is small compared
with the feature work; for tiny programs that do not, the containers are never
initialized.
