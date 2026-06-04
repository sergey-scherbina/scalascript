# Distributed Streams — `DStream[T]` and the Beam-style Pipeline model

Status: **v2.1.0 spec — design sign-off required before implementation starts.**
Implementation tracked from v2.1.1 onward.

Companion documents:
- [`specs/streams.md`](streams.md) — in-process `Source[A]` with backpressure (v1.51, single-node)
- [`specs/mapreduce.md`](mapreduce.md) — `Dataset[T]` batch map-reduce, local + distributed (v1.21–v1.22)
- [`specs/spark-streaming.md`](spark-streaming.md) — raw Spark Structured Streaming pass-through (v1.25 §9.5 Phase F)
- [`specs/algebraic-effects.md`](algebraic-effects.md) — typed effect rows (v1.12)
- [`specs/coroutines.md`](coroutines.md) — coroutine substrate (v1.9 / v1.10)

This document is the source of truth for `DStream[T]` and `Pipeline`: a unified
Apache-Beam-style DSL that compiles to Apache Spark, Apache Kafka Streams, Apache
Flink, Apache Beam runners, and the native ScalaScript distributed actor cluster —
from the same user code, with formal capability negotiation that makes cross-engine
incompatibilities a compile-time error rather than a production surprise.

---

## 1. Motivation and relation to existing stream/batch surfaces

ScalaScript already has three related data-processing abstractions:

| Abstraction | Where defined | Bounded | Unbounded | Distributed | Event time | Exactly-once | Engines |
|-------------|---------------|---------|-----------|-------------|------------|--------------|---------|
| `Source[A]` | `specs/streams.md` | ✓ | ✓ | ✗ | ✗ | ✗ | JVM, interpreter, JS |
| `Dataset[T]` | `specs/mapreduce.md` | ✓ | ✗ | ✓ (v1.22 actors) | ✗ | ✗ | JVM, interpreter |
| Spark block | `specs/spark-streaming.md` | ✓ | ✓ | ✓ (Spark) | ✓ (pass-through) | opt-in (Spark) | Spark only |

What is missing is a **single abstraction** that:

1. **Is portable across engines.** Today, switching from native v1.22 to Kafka Streams or Spark
   Structured Streaming requires rewriting the pipeline in the target engine's API.
2. **Makes delivery and time-domain semantics explicit.** `Source[A]` does not say whether the
   stream is at-least-once or exactly-once. `Dataset[T]` is batch-only. The Spark pass-through
   inherits Spark's guarantees but only works on Spark.
3. **Covers the full Beam Model.** Event time, watermarks, windowed aggregations, triggers, late
   data, and panes are not available on `Source[A]` or `Dataset[T]`, and are only accessible in
   raw Spark API in the Spark pass-through (no ScalaScript-level declaration).
4. **Provides formal capability negotiation.** When a user writes `pipeline.groupByKey.window(...)`,
   the compiler should tell them at build time whether their chosen backend can execute that
   operator, rather than surfacing a runtime exception or silently emitting wrong results.

`DStream[T]` and `Pipeline` fill all four gaps. They sit **above** `Source[A]` and `Dataset[T]`
in the abstraction stack and **replace** the raw Spark pass-through for users who want
portability. The raw Spark pass-through remains available as an escape hatch for code that
requires Spark-specific operators not expressible in the `DStream` DSL.

### 1.1 Relation to v1.10 `Generator[T]`

`Generator[T]` is a single-threaded coroutine producer. `Source[A]` wraps it with a
credit-based backpressure buffer. `DStream[T]` sits above both: a `DStream` may be backed by
a local `Source[A]` in the native backend, or by a Kafka topic, Spark streaming dataset, or
Flink datastream in the appropriate backend. The user code sees the same operators regardless.

### 1.2 Coexistence of all four surfaces

The four surfaces are **additive, not competing**:

- Use `Source[A]` for in-process streaming (real-time UI, WebSocket pipelines, actor mailboxes).
- Use `Dataset[T]` for local or distributed batch processing with the familiar `map` / `filter`
  / `groupBy` / `collect` vocabulary.
- Use `DStream[T]` for cross-engine streaming pipelines that need event-time semantics,
  windowing, or portability across Spark / Kafka / Flink.
- Use the raw Spark `scalascript` block for Spark-specific features not covered by the DSL.

Bridges between all four are defined in §11.

---

## 2. Conceptual model

### 2.1 The element model (Beam-style)

Every element in a `DStream[T]` carries four pieces of metadata alongside its value:

```
Element[T] = (
  value:     T,           // the user-visible payload
  timestamp: Instant,     // event time (assigned by source or user)
  window:    Window,      // the window this element belongs to (after windowing)
  pane:      Pane,        // which pane within the window (EARLY, ON_TIME, LATE)
)
```

Elements flow through a **Pipeline** — a directed acyclic graph (DAG) of `PTransform` stages
compiled by the user via the fluent builder API (§4). The pipeline DAG is **lazy**: no
computation starts until `.run(backend)` is called, at which point the chosen backend translates
the DAG into its native job format and submits it.

This is the Apache Beam model adapted to ScalaScript syntax and integrated with existing
ScalaScript types.

### 2.2 Bounded vs unbounded

A `DStream[T]` is either:

- **Bounded**: finite, like a Parquet file, a Kafka topic segment, or a completed
  `Dataset[T]`. The backend processes all elements and terminates.
- **Unbounded**: infinite, like a live Kafka topic or a sensor feed. The backend runs
  continuously; terminal operators like `runToList` are not permitted on unbounded streams.

The `DStream` type is unified — boundedness is tracked at runtime and checked at `.run()`. A
compile-time `Bounded[T]` / `Unbounded[T]` distinction can be added in a later phase if the
type-level discipline proves useful; it is deferred to §16 Open Questions.

### 2.3 Pipeline as lazy DAG

```
                   ┌──────────────────────────────────────────────────────────────┐
                   │  Pipeline (lazy DAG)                                         │
                   │                                                              │
                   │   Source ──► map ──► filter ──► keyBy ──► window ──► agg    │
                   │                                    │                  │      │
                   │                                    └──► sideOutput    │      │
                   │                                                       ▼      │
                   │                                                      Sink    │
                   └──────────────────────────────────────────────────────────────┘
                                                      │
                                              pipeline.run(backend)
                                                      │
                                      ┌───────────────┼───────────────┐
                                      ▼               ▼               ▼
                               Native actors     Spark job      Kafka topology
```

`.run(backend)` triggers:
1. **Capability negotiation** (§8): verify that `Backend.provides` ⊇ `Pipeline.requires`.
2. **Coder resolution** (§10): assign `Coder[T]` to each edge in the DAG.
3. **Backend translation**: the backend walks the DAG and emits its native job representation
   (actor dispatch plan / Spark logical plan / Kafka Streams topology / Flink job / Beam pipeline).
4. **Submission**: the backend submits the job to its engine and returns a `PipelineResult` handle
   for monitoring and cancellation.

### 2.4 Runner-driven execution

Unlike `Source[A]`, which the caller drives by calling `runForeach` on the calling thread,
`DStream[T]` follows the Beam runner model: **the backend drives execution**. The caller submits
the pipeline and gets back a `PipelineResult`; the engine pulls elements from sources, routes
them through stages, and pushes results to sinks, all under the engine's own scheduler.

This is the key semantic difference between `Source[A]` (caller-driven pull) and `DStream[T]`
(engine-driven push-pull).

---

## 3. Type-level surface

### 3.1 New types — no parser or type-system changes required

All new types are standard parametric names, represented as `SType.Named(name, args)` in
`lang/core/src/main/scala/scalascript/typer/Types.scala` — the same encoding as `List[A]`,
`Option[A]`, `Source[A]`, and every other stdlib type. No new `SType` case is needed. No
new `TypeParser` production is needed.

### 3.2 Type declarations (future `runtime/std/dstreams.ssc`)

```ssc
// The primary distributed-stream type
type DStream[T]

// A keyed stream — elements are grouped by key K, value V
type KV[K, V]

// A compiled, runnable pipeline DAG
type Pipeline

// A pipeline execution result (handle for monitoring + cancellation)
type PipelineResult

// Abstract window shape — see §7 for concrete window types
type Window

// Trigger — controls when a pane fires — see §7
type Trigger

// Watermark assignment strategy — see §7
type WatermarkStrategy[T]

// Which pane this element belongs to within its window
type Pane   // EARLY | ON_TIME | LATE | ON_TIME_AND_LATE

// Backend selection — see §9
type Backend
```

### 3.3 Capability type

`Capability` is an enum at the ScalaScript level, **not** an effect-row type. The design
decision is explained in §16 (Open Questions). Short reason: capability negotiation happens at
`.run()` time (pipeline → backend), not at function-call-composition time; modelling it as an
effect row would require propagating `! Dist` through every `DStream` operator, which adds
annotation burden with no gain since the negotiation happens at one point (pipeline submission).

```ssc
enum Capability:
  case AtLeastOnce         // elements delivered ≥ 1 time; duplicates possible
  case ExactlyOnce         // elements delivered exactly 1 time end-to-end
  case EventTime           // backend tracks event-time timestamps per element
  case WatermarkPerfect    // watermarks are perfect (no late data expected)
  case KeyedState          // stateful operators keyed by a user-defined key
  case BroadcastState      // a read-only state broadcast to all partitions
  case SideInputs          // operators can read from a materialized side input
  case SideOutputs         // operators can write to additional tagged outputs
  case TimerEventTime      // event-time timers (fire at watermark advance)
  case TimerProcessingTime // processing-time timers (fire at wall-clock time)
  case WindowedJoins       // join two DStreams within a shared window
```

### 3.4 Reference API signatures (future `runtime/std/dstreams.ssc`)

```ssc
// Pipeline builder
object Pipeline:
  def create(name: String): Pipeline

// Terminal: submit the pipeline to a backend
extension (p: Pipeline)
  def run(backend: Backend): PipelineResult
  def run(backend: Backend, opts: PipelineOptions): PipelineResult
  def requires: Set[Capability]   // inferred from operator graph

// PipelineResult — monitoring + cancellation
extension (r: PipelineResult)
  def waitUntilFinish(): PipelineState
  def cancel(): Unit
  def state: PipelineState   // RUNNING | DONE | FAILED | CANCELLED

enum PipelineState:
  case Running, Done, Failed(cause: String), Cancelled
```

---

## 4. Pipeline construction — the fluent builder

A pipeline is built with a fluent immutable builder. Each method returns a new `DStream[T]`
(or `DStream[KV[K, V]]` for keyed operations) rather than mutating the receiver:

```ssc
// --- Kafka unbounded source → windowed word count → Kafka sink ---
Pipeline.create("word-count")
  .read(Kafka.source[String](
    brokers  = "kafka:9092",
    topic    = "input-text",
    groupId  = "word-count-app",
  ).withWatermark(WatermarkStrategy.boundedOutOfOrder(5.seconds)))
  .flatMap(line => line.split(" ").toList)
  .map(word => KV(word, 1))
  .window(Window.fixed(60.seconds))
  .combinePerKey(_ + _)
  .write(Kafka.sink[KV[String, Int]](
    brokers = "kafka:9092",
    topic   = "word-count-out",
  ))
  .run(Backend.KafkaStreams(KafkaConfig(brokers = "kafka:9092", appId = "word-count")))
```

The same pipeline runs unchanged on Spark Structured Streaming:

```ssc
.run(Backend.Spark(SparkConfig(appName = "word-count", master = "yarn")))
```

Or on the native distributed backend:

```ssc
.run(Backend.Native(cluster))
```

### 4.1 Source injection

`Pipeline.create(name)` returns a builder object. Elements enter the pipeline through a `.read(source)` call,
where `source` is a `DSource[T]` connector (§6). Multiple `.read` calls create independent source
branches; they can be joined later with `.join` or merged with `.merge`.

### 4.2 Sink attachment

`.write(sink)` terminates a branch and attaches a `DSink[T]` connector (§6). A pipeline may
have multiple sinks (one per output branch, plus side outputs via `.sideOutput`).

### 4.3 Execution options

`PipelineOptions` are backend-specific parameters passed as a second argument to `.run()`:

```ssc
case class PipelineOptions(
  parallelism:     Option[Int]             = None,   // hint to the backend
  checkpointDir:   Option[String]          = None,   // for exactly-once backends
  maxInFlight:     Option[Int]             = None,   // concurrent in-flight records
  backfillFrom:    Option[Instant]         = None,   // reprocess from a past timestamp
  extraProperties: Map[String, String]     = Map.empty,
)
```

---

## 5. Operators (PTransforms)

Operators are lazy — they build nodes in the pipeline DAG. No computation happens until `.run()`.

### 5.1 Element-wise transforms

| Operator | Signature | Notes |
|----------|-----------|-------|
| `map(f)` | `DStream[A] => (A => B) => DStream[B]` | Element-wise transform; preserves timestamp |
| `filter(p)` | `DStream[A] => (A => Boolean) => DStream[A]` | Drop non-matching; preserves order |
| `flatMap(f)` | `DStream[A] => (A => Iterable[B]) => DStream[B]` | Expand; all outputs inherit the input timestamp |
| `mapWithTimestamp(f)` | `DStream[A] => ((A, Instant) => B) => DStream[B]` | Access event-time in map |
| `assignTimestamps(f)` | `DStream[A] => (A => Instant) => DStream[A]` | Override event-time per element |

### 5.2 Keyed transforms (trigger partitioning in distributed backends)

| Operator | Signature | Notes |
|----------|-----------|-------|
| `keyBy(f)` | `DStream[A] => (A => K) => DStream[KV[K, A]]` | Partition by key; required before windowed keyed ops |
| `combinePerKey(f)` | `DStream[KV[K, A]] => ((A, A) => A) => DStream[KV[K, A]]` | Commutative+associative merge per key |
| `aggregatePerKey(z)(f)(g)` | `DStream[KV[K, A]] => B => ((B, A) => B) => ((B, B) => B) => DStream[KV[K, B]]` | Per-key aggregate with combiner |
| `groupPerKey` | `DStream[KV[K, A]] => DStream[KV[K, Iterable[A]]]` | Collect all values per key (bounded only) |

### 5.3 Windowing

Windowing divides the (potentially unbounded) element sequence into finite chunks for aggregation.

| Operator | Signature | Notes |
|----------|-----------|-------|
| `window(fn)` | `DStream[A] => WindowFn => DStream[A]` | Assign elements to windows per `fn` |
| `withTrigger(t)` | `DStream[A] => Trigger => DStream[A]` | Override default trigger for this window |
| `withAllowedLateness(d)` | `DStream[A] => Duration => DStream[A]` | Accept late elements for up to `d` after the watermark |
| `withAccumulationMode(m)` | `DStream[A] => AccumulationMode => DStream[A]` | Discarding / Accumulating / AccumulatingAndRetracting |

Window functions:

| WindowFn | Construction | Semantics |
|----------|-------------|-----------|
| `Window.fixed(size)` | `Window.fixed(60.seconds)` | Non-overlapping tumbling windows of fixed duration |
| `Window.sliding(size, period)` | `Window.sliding(60.seconds, 30.seconds)` | Overlapping; each element in `size/period` windows |
| `Window.session(gap)` | `Window.session(5.minutes)` | Activity-based; new window after `gap` of inactivity per key |
| `Window.global` | `Window.global` | One window; requires explicit trigger to fire |
| `Window.custom(fn)` | `Window.custom(myFn)` | User-defined `WindowFn` implementation |

### 5.4 Triggers

Triggers control when the result of a window aggregation is emitted as a pane.

| Trigger | Construction | Fires when |
|---------|-------------|-----------|
| `Trigger.afterWatermark` | `Trigger.afterWatermark` | Watermark passes the end of the window (default) |
| `Trigger.afterProcessingTime(d)` | `Trigger.afterProcessingTime(1.minute)` | Wall-clock time `d` has elapsed since first element arrived |
| `Trigger.afterCount(n)` | `Trigger.afterCount(100)` | `n` elements have been accumulated |
| `Trigger.repeatedly(t)` | `Trigger.repeatedly(Trigger.afterProcessingTime(30.seconds))` | Fire `t` repeatedly |
| `Trigger.orFinally(t, u)` | `Trigger.orFinally(Trigger.repeatedly(...), Trigger.afterWatermark)` | Fire `t` until `u` fires, then close |
| `Trigger.composite(early, onTime, late)` | `Trigger.composite(early = Trigger.afterCount(10), onTime = Trigger.afterWatermark, late = Trigger.afterCount(1))` | Full Beam composite trigger |

### 5.5 Stateful processing

Stateful operators maintain per-key state across elements. State is backend-managed and
checkpointed per `PipelineOptions.checkpointDir`. All stateful operators require
`Capability.KeyedState`.

| Operator | Signature | Notes |
|----------|-----------|-------|
| `statefulMap(initState)(f)` | `DStream[KV[K, A]] => S => ((S, A) => (S, B)) => DStream[KV[K, B]]` | Per-key state; `f` maps (state, elem) → (new state, output) |
| `statefulFlatMap(initState)(f)` | `DStream[KV[K, A]] => S => ((S, A) => (S, Iterable[B])) => DStream[KV[K, B]]` | Zero or more outputs per input |
| `timer(eventTime)(f)` | `DStream[KV[K, A]] => Instant => (K => Iterable[B]) => DStream[KV[K, B]]` | Fires at given event time. Requires `Capability.TimerEventTime` |
| `timerProcessing(d)(f)` | `DStream[KV[K, A]] => Duration => (K => Iterable[B]) => DStream[KV[K, B]]` | Fires `d` after last element. Requires `Capability.TimerProcessingTime` |
| `broadcastState(stateStream)` | `DStream[KV[K, A]] => DStream[B] => DStream[C]` | Read-only broadcast state from `stateStream`. Requires `Capability.BroadcastState` |

### 5.6 Side inputs and side outputs

| Operator | Signature | Notes |
|----------|-----------|-------|
| `withSideInput(si)` | `DStream[A] => SideInput[B] => DStream[(A, B)]` | Augment each element with a materialized side collection. Requires `Capability.SideInputs` |
| `sideOutput(tag)` | `DStream[A] => OutputTag[B] => (DStream[A], DStream[B])` | Route some elements to a secondary output. Requires `Capability.SideOutputs` |

### 5.7 Combining streams

| Operator | Signature | Notes |
|----------|-----------|-------|
| `merge(other)` | `DStream[A] => DStream[A] => DStream[A]` | Merge two streams; order not guaranteed |
| `join(other)` | `DStream[KV[K, A]] => DStream[KV[K, B]] => DStream[KV[K, (A, B)]]` | Inner windowed join; requires `Capability.WindowedJoins` |
| `leftOuterJoin(other)` | `DStream[KV[K, A]] => DStream[KV[K, B]] => DStream[KV[K, (A, Option[B])]]` | |
| `rightOuterJoin(other)` | `DStream[KV[K, A]] => DStream[KV[K, B]] => DStream[KV[K, (Option[A], B)]]` | |
| `flatten` | `DStream[DStream[A]] => DStream[A]` | Flatten a stream of streams (bounded inner streams only) |

### 5.8 Consuming terminal operators (bounded streams only)

Not permitted on unbounded streams; cause a `UNBOUNDED_TERMINAL` error at `.run()`.

| Operator | Signature | Notes |
|----------|-----------|-------|
| `runToList` | `DStream[A] => List[A]` | Collect all elements; requires bounded stream |
| `runFold(z)(f)` | `DStream[A] => B => ((B, A) => B) => B` | Single aggregate |
| `runForeach(f)` | `DStream[A] => (A => Unit) => Unit` | Execute `f` per element |
| `runCount` | `DStream[A] => Long` | Count elements |

### 5.9 Backend support matrix

`✓` = full support; `~` = emulation (less efficient); `✗` = not supported (capability mismatch error).

| Operator family | Native | Spark | Kafka Streams | Flink | Beam |
|----------------|--------|-------|---------------|-------|------|
| map / filter / flatMap | ✓ | ✓ | ✓ | ✓ | ✓ |
| keyBy / combinePerKey | ✓ | ✓ | ✓ | ✓ | ✓ |
| Fixed / Sliding windows | ✓ | ✓ | ✓ | ✓ | ✓ |
| Session windows | ~ | ✓ | ✓ | ✓ | ✓ |
| Trigger.afterWatermark | ~ | ✓ | ✓ | ✓ | ✓ |
| Composite triggers | ✗ | ✓ | ✗ | ✓ | ✓ |
| Stateful keyed operators | ✓ | ✓ | ✓ | ✓ | ✓ |
| Event-time timers | ✗ | ✓ | ✓ | ✓ | ✓ |
| Processing-time timers | ✓ | ✓ | ✓ | ✓ | ✓ |
| Broadcast state | ✗ | ✓ | ✓ (KTable) | ✓ | ✓ |
| Side inputs | ✗ | ✓ | ~ | ✓ | ✓ |
| Side outputs | ~ | ✓ | ✗ | ✓ | ✓ |
| Windowed joins | ✓ | ✓ | ✓ | ✓ | ✓ |
| Bounded runToList | ✓ | ✓ | ✗ | ✓ | ✓ |

---

## 6. Sources and Sinks (connectors)

Connectors are typed by `DSource[T]` (produces elements) and `DSink[T]` (consumes elements).
Each connector is defined once with a backend-agnostic API; each backend provides an
implementation. Auto-emitted dependencies follow the pattern established by
`runtime/backend/spark/src/main/scala/scalascript/codegen/SparkGen.scala:308-321`
(v1.25.F Phase F.4): referencing a connector in a pipeline auto-emits the appropriate dep.

### 6.1 Kafka (priority 1)

```ssc
// Subscribe to one or more topics (unbounded by default)
Kafka.source[T](
  brokers:     String,
  topic:       String | List[String],
  groupId:     String,
  startOffset: KafkaOffset = KafkaOffset.Latest,
  coder:       Coder[T]    = Coder.derived,
): DSource[T]

// Assign specific partitions
Kafka.sourceAssigned[T](
  brokers:     String,
  assignments: Map[KafkaPartition, KafkaOffset],
  coder:       Coder[T] = Coder.derived,
): DSource[T]

// Changelog source — emitted as KTable on Kafka Streams backend
Kafka.changelog[T](
  brokers: String,
  topic:   String,
  coder:   Coder[T] = Coder.derived,
): DSource[T]

Kafka.sink[T](
  brokers: String,
  topic:   String,
  keyFn:   T => Array[Byte]  = _ => Array.emptyByteArray,
  coder:   Coder[T]           = Coder.derived,
): DSink[T]

enum KafkaOffset:
  case Latest              // start from the latest committed offset
  case Earliest            // start from the beginning of the topic
  case At(offset: Long)    // specific offset
  case AtTimestamp(t: Instant)   // seek to the offset at timestamp `t`
```

`Capability.ExactlyOnce` is provided when using `Kafka.source` + `Kafka.sink` with the Kafka
Streams backend and `PipelineOptions(checkpointDir = ...)` (enables Kafka transaction protocol).

### 6.2 Files (local and distributed filesystems)

```ssc
Files.source[T](
  path:   String,      // file path or glob; supports s3://, gs://, hdfs://
  format: FileFormat,
  coder:  Coder[T] = Coder.derived,
): DSource[T]

Files.sink[T](
  path:   String,
  format: FileFormat,
  coder:  Coder[T] = Coder.derived,
): DSink[T]

enum FileFormat:
  case Text
  case Json
  case Csv(header: Boolean = true)
  case Parquet   // columnar; requires schema from Coder[T]
  case Avro      // requires Avro schema from Coder[T]
```

Object-storage paths (`s3://`, `gs://`, `hdfs://`) are handled transparently by the backend
via URI-scheme detection, auto-emitting the appropriate Hadoop / cloud-storage dep.

### 6.3 JDBC

```ssc
Jdbc.source[T](
  url:        String,
  table:      String,
  query:      Option[String] = None,
  partitions: Int            = 1,   // reader-task parallelism hint
  coder:      Coder[T]       = Coder.derived,
): DSource[T]

Jdbc.sink[T](
  url:     String,
  table:   String,
  batchSz: Int     = 1000,
  coder:   Coder[T] = Coder.derived,
): DSink[T]
```

### 6.4 Pulsar

```ssc
Pulsar.source[T](serviceUrl: String, topic: String, subscription: String,
                 coder: Coder[T] = Coder.derived): DSource[T]
Pulsar.sink[T](serviceUrl: String, topic: String,
               coder: Coder[T] = Coder.derived): DSink[T]
```

### 6.5 Amazon Kinesis

```ssc
Kinesis.source[T](stream: String, region: String,
                  coder: Coder[T] = Coder.derived): DSource[T]
Kinesis.sink[T](stream: String, region: String,
                coder: Coder[T] = Coder.derived): DSink[T]
```

### 6.6 In-memory (testing)

```ssc
// Bounded source from a fixed in-memory list
InMemory.source[T](elements: List[T]): DSource[T]
InMemory.sourceWithTimestamps[T](elements: List[(T, Instant)]): DSource[T]

// Sink that materialises to a List after pipeline.run()
InMemory.sink[T](): (DSink[T], () => List[T])

// Convenience: run with DirectRunner and collect all results
InMemory.runAndCollect[T](stream: DStream[T]): List[T]
```

### 6.7 Bridge sources — lift existing ScalaScript types into DStream

```ssc
// Lift a local Source[A] into a bounded DStream (native backend only)
DSource.fromLocalSource[A](src: Source[A]): DSource[A]

// Lift a Dataset[T] into a bounded DStream
DSource.fromDataset[T](ds: Dataset[T]): DSource[T]
```

---

## 7. Beam Model semantics

### 7.1 Event time and processing time

Every element carries two timestamps:
- **Event time** — when the event actually occurred; assigned by `assignTimestamps` or by the
  source connector (e.g. Kafka record timestamp). If not assigned, defaults to processing time.
- **Processing time** — when the element was processed by the pipeline stage; always wall-clock.

Event-time-based operators require `Capability.EventTime`. Backends that do not provide it will
fail capability negotiation with a diagnostic pointing to the operator.

### 7.2 Watermarks

A **watermark** is a lower bound on event-time progress: a watermark of `t` tells the backend
that no future element will arrive with an event time < `t`. When the watermark advances past
the end of a window, `Trigger.afterWatermark` fires and emits an ON_TIME pane.

Watermark assignment strategies:

| Strategy | Construction | When to use |
|----------|-------------|-------------|
| `WatermarkStrategy.monotonicallyIncreasing` | default for ordered bounded sources | Event times are in order |
| `WatermarkStrategy.boundedOutOfOrder(lag)` | `WatermarkStrategy.boundedOutOfOrder(30.seconds)` | Elements arrive up to `lag` out of order |
| `WatermarkStrategy.atEnd` | for bounded sources | Watermark set to `Instant.MAX` after the last element |
| `WatermarkStrategy.custom(fn)` | `WatermarkStrategy.custom(elem => elem.arrivalTime - 10.seconds)` | User-controlled extraction |

Watermarks are set on the source:

```ssc
Kafka.source[Event]("kafka:9092", "events", "my-app")
  .withWatermark(WatermarkStrategy.boundedOutOfOrder(30.seconds))
```

### 7.3 Window functions

**Fixed (tumbling) windows:** each element belongs to exactly one window. A fixed window of
60 seconds divides the event-time axis into `[0,60)`, `[60,120)`, etc.

**Sliding windows:** each element belongs to `size / period` windows. A 60-second window with a
30-second slide means elements appear in both `[0,60)` and `[30,90)`.

**Session windows:** windows are determined by activity; a new window starts when a gap of
`gap` duration occurs in the event-time stream for a given key. Session windows require
`keyBy` before windowing.

**Global window:** a single window covering all elements. Useful for accumulate-until-fired
patterns with a custom trigger.

### 7.4 Panes and accumulation modes

A **pane** is one emission of a window's results in response to a trigger firing.

| Pane type | When fired | Contents |
|-----------|-----------|---------|
| `EARLY` | Before watermark passes the window end | Partial results; may fire multiple times |
| `ON_TIME` | When watermark passes the window end | Final (or near-final) results |
| `LATE` | After the watermark, within allowed lateness | Updates for late-arriving elements |

**Accumulation modes** control what a pane contains relative to previous panes:

| Mode | Each pane contains |
|------|-------------------|
| `AccumulationMode.Discarding` | Only elements accumulated since the previous pane fired |
| `AccumulationMode.Accumulating` | All elements accumulated so far in this window |
| `AccumulationMode.AccumulatingAndRetracting` | The accumulation plus a retraction of the previous pane (for downstream idempotency) |

### 7.5 Late data and allowed lateness

Elements that arrive after the watermark has passed their window end are **late**. The backend
routes them to a LATE pane (if `withAllowedLateness(d)` is set and the element arrives within
`d` of the watermark) or discards them.

```ssc
pipeline
  .read(kafkaSource)
  .window(Window.fixed(1.hour))
  .withTrigger(Trigger.composite(
    early  = Trigger.repeatedly(Trigger.afterCount(100)),
    onTime = Trigger.afterWatermark,
    late   = Trigger.afterCount(1),
  ))
  .withAllowedLateness(30.minutes)
  .withAccumulationMode(AccumulationMode.Accumulating)
  .combinePerKey(_ + _)
  .write(...)
```

---

## 8. Capability system

### 8.1 Pipeline capability inference

A pipeline's required capabilities are inferred by a single DAG walk:

| Operator / feature | Capability required |
|--------------------|--------------------|
| `window(...)` with event-time `WindowFn` | `EventTime` |
| `Trigger.afterWatermark` | `EventTime` |
| `WatermarkStrategy.*` | `EventTime` |
| `statefulMap`, `statefulFlatMap` | `KeyedState` |
| `broadcastState(...)` | `BroadcastState` |
| `withSideInput(...)` | `SideInputs` |
| `sideOutput(...)` | `SideOutputs` |
| `timer(eventTime)(...)` | `TimerEventTime`, `EventTime` |
| `timerProcessing(...)` | `TimerProcessingTime` |
| `join`, `leftOuterJoin`, `rightOuterJoin` | `WindowedJoins` |
| `Kafka.sink` + checkpointDir + Kafka Streams backend | `ExactlyOnce` |
| (default, always) | `AtLeastOnce` |

`pipeline.requires` returns the inferred `Set[Capability]` before `.run()` is called.

### 8.2 Backend capability declarations

| Capability | Native | Spark | Kafka Streams | Flink | Beam |
|-----------|--------|-------|---------------|-------|------|
| `AtLeastOnce` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `ExactlyOnce` | ✗ | ✓ (micro-batch) | ✓ (transactions) | ✓ | runner-dep |
| `EventTime` | ✓ (v2.1.2+) | ✓ | ✓ | ✓ | ✓ |
| `WatermarkPerfect` | ✓ (bounded) | ✗ | ✗ | ✗ | ✗ |
| `KeyedState` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `BroadcastState` | ✗ | ✓ | ✓ (KTable) | ✓ | ✓ |
| `SideInputs` | ✗ | ✓ | ~ | ✓ | ✓ |
| `SideOutputs` | ~ | ✓ | ✗ | ✓ | ✓ |
| `TimerEventTime` | ✗ | ✓ | ✓ | ✓ | ✓ |
| `TimerProcessingTime` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `WindowedJoins` | ✓ | ✓ | ✓ | ✓ | ✓ |

### 8.3 Negotiation at `.run()`

When the user calls `pipeline.run(backend)`:

1. Compute `pipeline.requires` from the DAG.
2. If `pipeline.requires ⊄ backend.provides`, emit a `CAPABILITY_MISMATCH` diagnostic listing
   each missing capability, the operator(s) that require it, and suggested alternative backends.

Example diagnostic:

```
error [CAPABILITY_MISMATCH]: pipeline "word-count" cannot run on Backend.Native
  Missing capabilities:
    EventTime — required by:
      .window(Window.fixed(60.seconds))    at examples/word-count.ssc:14
      .withTrigger(Trigger.afterWatermark) at examples/word-count.ssc:15
  Suggested alternatives:
    Backend.Spark, Backend.KafkaStreams, Backend.Flink, Backend.Beam
  Hint: for native distributed execution without event time, use
        Window.global with Trigger.afterCount(n) (requires only AtLeastOnce).
```

### 8.4 Optional opt-in capabilities

Some capabilities are declared per-connector rather than inferred from the operator type:

```ssc
Kafka.source[T](...).exactlyOnce()   // opt into ExactlyOnce; added to pipeline.requires
Jdbc.sink[T](...).upsert(pk = "id")  // idempotent write; effective even with AtLeastOnce
```

---

## 9. Backends

### 9.1 Native ScalaScript (extension of v1.22 distributed actors)

The native backend lowers a `DStream[T]` pipeline onto the v1.22 actor cluster
(`specs/mapreduce.md §4`, `runtime/std/actors.ssc:197`). Each pipeline stage becomes a
coordinator / worker actor pair; elements flow as actor messages; partitioning follows the `keyBy` key.

**Capabilities provided:** `AtLeastOnce`, `EventTime` (v2.1.2+), `WatermarkPerfect` (bounded
sources only), `KeyedState`, `TimerProcessingTime`, `WindowedJoins`.

**Not provided in v2.1.1:** `ExactlyOnce`, `BroadcastState`, `SideInputs`, `TimerEventTime`.

**Lowering sketch:**
- `source(DSource[T])` → coordinator actor sends partition messages to worker actors.
- `map(f)` → inline transform in the worker's message handler.
- `keyBy(k)` → worker hashes `k(elem)` to a partition id and forwards to the responsible worker.
- `window(Window.fixed(d))` (processing-time only in v2.1.1) → per-worker accumulator;
  flush after `d` wall-clock time.
- `combinePerKey(f)` → per-key in-memory accumulator, flushed on window close.
- `write(sink)` → final worker forwards elements to a `DSink` adapter actor.

No external cluster manager required — uses the existing `connectNode` / `joinCluster` mechanism.

### 9.2 Apache Spark

The Spark backend lowers:
- **Unbounded** `DStream[T]` → Spark Structured Streaming `Dataset[T]` (via `spark.readStream`).
  Extends `runtime/backend/spark/src/main/scala/scalascript/codegen/SparkGen.scala:308`.
- **Bounded** `DStream[T]` → Spark batch `Dataset[T]` (via `spark.read`).

Key operator mappings:

| DStream operator | Spark translation |
|-----------------|-------------------|
| `map(f)` | `.map(f)` on `Dataset` |
| `filter(p)` | `.filter(p)` |
| `keyBy(k)` + `combinePerKey(f)` | `.groupByKey(k).reduceGroups(f)` |
| `window(Window.fixed(d))` | `.groupBy(window(col("timestamp"), d.toString))` |
| `window(Window.sliding(sz, step))` | `.groupBy(window(col("timestamp"), sz, step))` |
| `statefulMap(z)(f)` | `mapGroupsWithState(f)` |
| `write(Kafka.sink(...))` | `.writeStream.format("kafka")...` + auto-emitted dep |

The `awaitTermination()` shim from `SparkGen.scala:314` is auto-emitted for unbounded sinks.
`Kafka.source` / `Kafka.sink` triggers auto-emit of
`"org.apache.spark:spark-sql-kafka-0-10_2.13:<sparkVersion>"` (same mechanism as
`SparkGen.scala:321`).

### 9.3 Apache Kafka Streams

The Kafka Streams backend lowers the DAG to a `StreamsBuilder` topology:

| DStream operator | Kafka Streams translation |
|-----------------|--------------------------|
| `source(Kafka.source[T](...))` | `builder.stream(topic, consumed)` |
| `source(Kafka.changelog[T](...))` | `builder.table(topic, consumed)` |
| `map(f)` | `.mapValues(f)` / `.map((k, v) => ...)` |
| `filter(p)` | `.filter((k, v) => p(v))` |
| `keyBy(k)` | `.selectKey((_, v) => k(v))` |
| `combinePerKey(f)` | `.groupByKey.reduce(f)` → `KTable` |
| `window(Window.fixed(d))` | `.groupByKey.windowedBy(TimeWindows.ofSizeWithNoGrace(d))` |
| `window(Window.session(gap))` | `.groupByKey.windowedBy(SessionWindows.ofInactivityGapWithNoGrace(gap))` |
| `statefulMap(z)(f)` | `.groupByKey.aggregate(z, f, Materialized.with(...))` |
| `write(Kafka.sink(...))` | `.to(topic, produced)` |
| `merge(other)` | `builder.merge(left, right)` |
| `join(other)` | `.join(other, joiner, JoinWindows.of(duration))` |

`KStream` / `KTable` duality: operators that produce per-key aggregates without windowing emit
a `KTable`; per-element transforms emit a `KStream`. This is resolved transparently by the
backend; user code does not distinguish the two types. The `Kafka.changelog` source factory
explicitly signals `KTable` semantics for topics that are compacted changelogs.

Exactly-once: setting `PipelineOptions(checkpointDir = ...)` causes the backend to configure
`processing.guarantee = exactly_once_v2` in the Kafka Streams config.

### 9.4 Apache Flink

The Flink backend lowers to a `DataStream[T]`:

| DStream operator | Flink translation |
|-----------------|------------------|
| `source(Kafka.source[T](...))` | `env.fromSource(KafkaSource.builder()..., watermarkStrategy, "name")` |
| `map(f)` | `.map(f)` |
| `keyBy(k)` | `.keyBy(k)` |
| `window(Window.fixed(d))` | `.window(TumblingEventTimeWindows.of(Time.seconds(d)))` |
| `window(Window.session(gap))` | `.window(EventTimeSessionWindows.withGap(...))` |
| `combinePerKey(f)` | `.reduce(f)` on `WindowedStream` |
| `statefulMap(z)(f)` | `.process(new KeyedProcessFunction(...))` |
| `timer(eventTime)(f)` | `ctx.timerService().registerEventTimeTimer(t)` |
| `write(Kafka.sink(...))` | `.sinkTo(KafkaSink.builder()...)` |

The Flink backend can also be used via the Apache Beam Flink Runner (§9.5) for full portability.
Direct Flink lowering is preferred for lower overhead when Flink is the sole target.

### 9.5 Apache Beam

The Beam backend emits an Apache Beam Java SDK `Pipeline` and runs it via a Beam
`PipelineRunner`. The runner is specified in `PipelineOptions.extraProperties`:

```ssc
pipeline.run(
  Backend.Beam,
  PipelineOptions(
    extraProperties = Map(
      "runner"    -> "FlinkRunner",
      "flinkMaster" -> "localhost:8081",
    )
  )
)
```

Operator lowering maps `DStream[T]` operators to `PCollection<T>` transforms:

| DStream operator | Beam translation |
|-----------------|-----------------|
| `map(f)` | `MapElements.via(SerializableFunction { ... })` |
| `filter(p)` | `Filter.by(...)` |
| `keyBy(k)` + `combinePerKey(f)` | `WithKeys.of(k)` → `Combine.perKey(f)` |
| `window(fn)` | `.apply(Window.into(fn))` |
| `statefulMap` | `ParDo.of(DoFn with @StateId)` |
| `sideOutput(tag)` | `ParDo.of(DoFn with TupleTag)` |
| `withSideInput(si)` | `.apply(ParDo.of(...).withSideInput(si))` |

Closure serialisation for Beam requires functions to be `java.io.Serializable`. The ScalaScript
compiler emits `@SerialVersionUID` and implements `Serializable` on all lambdas passed to
DStream operators when `Backend.Beam` is detected. See §16 for risks.

---

## 10. Coders and schemas

### 10.1 `Coder[T]` — element serialisation

A `Coder[T]` knows how to serialise and deserialise elements of type `T` for transmission
across network boundaries (Kafka partitions, Spark executors, Flink task managers, etc.):

```ssc
trait Coder[T]:
  def encode(value: T): Array[Byte]
  def decode(bytes: Array[Byte]): T
  def schema: Option[Schema]   // Avro / Parquet schema, if applicable
```

Default coders are derived via v1.14 `derives`:

```ssc
case class Event(id: String, amount: Int, ts: Instant) derives Coder
```

Schema-aware coders for Avro and Parquet are requested explicitly:

```ssc
given Coder[Event] = Coder.avro[Event](schema = Event.avroSchema)
given Coder[Event] = Coder.parquet[Event]
```

### 10.2 Relation to existing serialisation

`Coder[T]` supersedes `DatasetCodec[A]` from v1.22 (`specs/mapreduce.md §4.4`) for the DStream
context. The bridge:

```ssc
given [T: DatasetCodec]: Coder[T] = Coder.fromDatasetCodec(summon[DatasetCodec[T]])
```

Per-backend serde adaptation:

| Backend | Serde resolution |
|---------|-----------------|
| Native | JSON over actor messages (v1.22 wire format) |
| Spark | `ExpressionEncoder[T]` derived from `Coder[T].schema` |
| Kafka Streams | `Serdes.serdeFrom(Coder[T])` bridge |
| Flink | `TypeInformation.of[T]` via Flink's POJO / Avro / Kryo hierarchy |
| Beam | `AvroCoder[T]` or `SerializableCoder[T]` |

### 10.3 Schema evolution

`Coder.avro[T]` and `Coder.parquet[T]` carry an explicit schema version. Schema registry
integration (Confluent Schema Registry, Glue Schema Registry) is designed into the
`Coder[T].schema: Option[Schema]` slot and added in v2.2.

---

## 11. Integration with existing types

### 11.1 `DStream[T]` ↔ `Source[A]` (in-process)

```ssc
// Run a bounded DStream on the native backend and consume it as a local Source
extension [T] (stream: DStream[T])
  def toLocalSource(using cluster: Cluster): Source[T]

// Lift a local Source[A] into a bounded DStream (native backend only)
extension [A] (src: Source[A])
  def toDStream: DStream[A]
```

`toLocalSource` submits the pipeline on the native backend and wraps the output as a `Source[T]`
with a bounded `ArrayBlockingQueue(16)` — the same backpressure queue used by the v1.51 streams
plugin
(`runtime/std/streams-plugin/src/main/scala/scalascript/compiler/plugin/streams/StreamsInterpreterPlugin.scala`).

### 11.2 `DStream[T]` ↔ `Dataset[T]` (bounded batch)

```ssc
extension [T] (stream: DStream[T])
  def toDataset(using Backend): Dataset[T]   // collect pipeline output into Dataset

extension [T] (ds: Dataset[T])
  def toDStream: DStream[T]                  // wrap Dataset as a bounded DStream
```

On Spark, `dataset.toDStream` wraps the underlying `RDD[T]` as a static `DataStreamSource`;
`dstream.toDataset` collects the pipeline output into a Spark `Dataset[T]`.

### 11.3 Coexistence with raw Spark blocks (v1.25.F escape hatch)

The Spark pass-through (`specs/spark-streaming.md`) remains available and is **not deprecated**.
The two paths are mutually exclusive within one `.ssc` module; mixing them triggers:

```
warning [MIXED_DSTREAM_RAW_SPARK]: DStream pipeline and raw spark.readStream detected in the
same module. Consider migrating the raw block to DStream for portability.
```

### 11.4 Relation to algebraic effects (v1.12)

`DStream[T]` is independent of the `! Eff` effect row from
[`specs/algebraic-effects.md`](algebraic-effects.md). A `DStream` operator that performs effects
must handle them before the element crosses a backend boundary, because backends like Spark and
Kafka do not know about ScalaScript effect handlers. Unhandled effects at `.run()` time trigger
a compile-time error via the `EffectAnalysis.verify` wiring (v1.12.1).

---

## 12. Diagnostics

### 12.1 Capability mismatch

```
error [CAPABILITY_MISMATCH]: pipeline "sensor-agg" cannot run on Backend.Native
  Missing capabilities:
    EventTime — required by:
      .window(Window.fixed(30.seconds))    at examples/sensor-agg.ssc:22
      .withTrigger(Trigger.afterWatermark) at examples/sensor-agg.ssc:23
    BroadcastState — required by:
      .broadcastState(thresholds)          at examples/sensor-agg.ssc:30
  Backends that provide all required capabilities:
    Backend.Spark, Backend.Flink, Backend.Beam
```

### 12.2 Window / trigger configuration errors

```
error [TRIGGER_WITHOUT_WINDOW]: Trigger.afterWatermark requires a windowed DStream
  → .withTrigger(Trigger.afterWatermark) at examples/sensor-agg.ssc:10
  Hint: insert .window(Window.fixed(duration)) before .withTrigger(...)

error [SESSION_WINDOW_ON_UNKEYED_STREAM]: Session windows require a keyed stream
  → .window(Window.session(5.minutes)) at examples/session.ssc:8
  Hint: insert .keyBy(elem => elem.userId) before .window(Window.session(...))
```

### 12.3 Coder mismatch

```
error [CODER_MISSING]: No Coder[User] found for DStream[KV[String, User]]
  → at examples/user-agg.ssc:15: .keyBy(_.userId)
  Hint: add `given Coder[User] = Coder.derived[User]`
```

### 12.4 Unbounded stream in bounded terminal operator

```
error [UNBOUNDED_TERMINAL]: runToList requires a bounded DStream
  → .runToList at examples/streaming.ssc:25
  → source is unbounded: Kafka.source[Event](...) at examples/streaming.ssc:10
  Hint: .take(n) to bound the stream, or use .write(sink) to drain continuously
```

### 12.5 Closure serialisation failure (Beam backend)

```
error [CLOSURE_NOT_SERIALIZABLE]: lambda at examples/etl.ssc:18 captures a non-serializable
  value: `myConfig: Config` (field `logger: Logger` is not java.io.Serializable)
  Hint: make `Config` implement Serializable, or extract the closure to a top-level def.
```

---

## 13. Implementation phases

### v2.1.1 — Core types + native backend (bounded)

**Scope:** first runnable DStream pipelines on the native backend with bounded sources
(in-memory, files, Dataset bridge). No event time, no Kafka, no Spark.

1. Create `runtime/std/dstreams-plugin/` (four-file plugin layout per AGENTS.md §76–100).
2. Implement `Pipeline.create`, `.read`, `.map`, `.filter`, `.flatMap`, `.keyBy`,
   `.combinePerKey`, `.write`, `.run(Backend.Native)` for bounded sources.
3. Implement `InMemory.source`, `InMemory.sink`, `InMemory.runAndCollect`.
4. Implement `DSource.fromDataset`, `DSource.fromLocalSource`.
5. Implement capability negotiation at `.run()` with `CAPABILITY_MISMATCH` diagnostic.
6. Add `Feature.DistributedStreams` to `Feature.scala`.
7. Deliver `examples/distributed-streams.ssc` — three bounded examples.
8. Update `BACKLOG.md`, `WORK_QUEUE.md`, `MILESTONES.md`, `CHANGELOG.md`.

### v2.1.2 — Native backend (unbounded) + processing-time windows + watermarks

**Scope:** processing-time windows on native backend; watermarks for bounded sources.

1. Extend native backend to handle unbounded `DSource[T]` (timer-based emission via `timerProcessing`).
2. Implement `Window.fixed(d)` and `Window.sliding(sz, step)` using processing-time wall-clock.
3. Implement `WatermarkStrategy.atEnd` for bounded sources; add `Capability.EventTime` for those.
4. Implement `timerProcessing` on native backend (`Capability.TimerProcessingTime`).
5. Extend conformance tests with window + watermark cases.

### v2.1.3 — Spark backend

**Scope:** full DStream-to-Spark translation; unbounded (Structured Streaming) + bounded (batch).

1. Implement `DStreamsSparkPlugin.scala` in `runtime/backend/spark/`.
2. Translate all operators per §9.2.
3. Auto-emit `spark-sql-kafka-0-10` dep when Kafka connectors are used.
4. Implement `DSource.fromDataset` Spark bridge.
5. Integration tests gated by `SPARK_MASTER` environment variable.
6. Conformance test: word count on native (v2.1.1) + Spark with same bounded input.

### v2.1.4 — Kafka Streams backend

**Scope:** full DStream-to-Kafka-Streams topology builder; exactly-once via transactions.

1. Implement `DStreamsKafkaPlugin.scala` lowering `Pipeline` DAG to `StreamsBuilder`.
2. Implement KTable / KStream duality resolution per §9.3.
3. Enable exactly-once when `PipelineOptions(checkpointDir = ...)` is set.
4. Integration tests gated by `KAFKA_BROKERS` environment variable.
5. Conformance test: word count on native + Spark + Kafka Streams.

### v2.1.5 — Flink / Beam backends

**Scope:** Flink DataStream API (direct); Beam as portability escape hatch.

1. Implement `DStreamsFlinkPlugin.scala` lowering to Flink DataStream API (§9.4).
2. Implement `DStreamsBeamPlugin.scala` lowering to Apache Beam Java SDK (§9.5).
3. Emit `@SerialVersionUID` + `Serializable` on DStream lambdas for Beam backend.
4. Integration tests gated by `FLINK_MASTER` / `BEAM_RUNNER`.
5. Full 4-backend conformance test (word count).

### v2.1.6 — Connectors

**Scope:** all `DSource` / `DSink` connectors from §6.

1. Kafka, Files (Parquet/JSON/CSV/Avro), JDBC, S3/GCS/HDFS, Pulsar, Kinesis.
2. Auto-emitted dep detection per connector.
3. Schema-aware `Coder[T]` for Parquet and Avro.
4. Integration tests per connector, gated by env-var presence.

### v2.1.7 — Stateful processing and timers

**Scope:** `statefulMap`, `statefulFlatMap`, event-time timers, broadcast state, side IO.

1. `statefulMap` / `statefulFlatMap` on all backends.
2. Event-time timers on Kafka Streams, Flink, Beam.
3. `broadcastState` on Spark, Kafka Streams (KTable), Flink, Beam.
4. `withSideInput` / `sideOutput` on Spark, Flink, Beam.
5. Extended conformance suite with stateful cases.
6. Re-evaluate `Capability.ExactlyOnce` on native backend — go/no-go in v2.1.7 planning.

---

## 14. Testing strategy

### 14.1 DirectRunner (in-process test backend)

Every pipeline unit test uses `Backend.Direct`, an in-process test backend that:
- Runs the pipeline synchronously on the calling thread.
- Does not require any external cluster, broker, or cluster manager.
- Provides all capabilities (so capability mismatch errors can be tested via a partial-capability
  backend stub).
- Supports `InMemory.source` + `InMemory.runAndCollect` for round-trip assertions.

```ssc
test("window word count") {
  val result = InMemory.runAndCollect(
    Pipeline.create("test")
      .read(InMemory.sourceWithTimestamps(List(
        ("hello world", Instant.parse("2024-01-01T00:00:10Z")),
        ("hello scala", Instant.parse("2024-01-01T00:00:20Z")),
        ("world",       Instant.parse("2024-01-01T00:01:05Z")),
      )))
      .flatMap(_.split(" ").toList)
      .map(KV(_, 1))
      .window(Window.fixed(60.seconds))
      .combinePerKey(_ + _)
  )
  assert(result.toSet == Set(KV("hello", 2), KV("world", 2), KV("scala", 1)))
}
```

### 14.2 Per-backend integration tests

| Backend | Gate env var | What it covers |
|---------|-------------|---------------|
| Native | (always on) | bounded + processing-time windows |
| Spark | `SPARK_MASTER` | unbounded Structured Streaming + Kafka source |
| Kafka Streams | `KAFKA_BROKERS` | topology build + exactly-once |
| Flink | `FLINK_MASTER` | DataStream + event-time timers |
| Beam | `BEAM_RUNNER` | portability; DirectRunner gated tests always on |

### 14.3 Cross-backend conformance suite

A shared suite runs the same pipelines on all available backends and asserts equal output
(modulo ordering for unordered pipelines):

1. **Word count** (bounded, no event time) — basic operator conformance.
2. **Windowed word count** (unbounded, fixed event-time window) — event-time conformance.
3. **Session grouping** (unbounded, session window, late data) — late-data conformance.
4. **Stateful running sum** (keyed state, no window) — stateful conformance.
5. **Kafka round-trip** (Kafka source + Kafka sink, exactly-once) — delivery conformance.

The conformance suite is the gating criterion for marking a backend "first-class" in `BACKLOG.md`.

---

## 15. Non-goals for v2.1

- **Custom engine implementations.** Proprietary engine support (AWS Kinesis Data Analytics,
  Google Dataflow-native) is out of scope; users build their own `Backend` against the DAG
  visitor API.
- **Full Apache Beam Java SDK parity.** Beam-specific operators not in the DSL are accessible
  via `Backend.Beam` with raw Beam SDK transforms; interleaving with DStream stages is v2.1.5+.
- **DataFrames / SQL surface.** Spark SQL (v1.25), Flink SQL, KSQL are separate axes.
- **ML stages.** Streaming ML is covered by `specs/spark-mllib.md`; DStream / MLlib integration
  is deferred.
- **Streaming joins across arbitrary state.** Only windowed joins (§5.7) in v2.1.
- **Schema registry integration.** `Coder[T].schema` provides the hook; registry integrations
  (Confluent, Glue) land in v2.2.
- **Sink-level deduplication / idempotent writes.** Exactly-once at the delivery layer is
  provided by engines that support it; sink idempotency (e.g. JDBC upsert) is per-connector.

---

## 16. Risks, open questions, and go / no-go recommendation

### Closure serialisation

Spark, Flink, and Beam require lambda functions to be `java.io.Serializable`. ScalaScript lambdas
that capture mutable values, loggers, or non-serialisable objects will fail at job submission.

**Mitigation:** compiler emits `@SerialVersionUID` + `Serializable` on DStream lambdas when Beam
backend is detected; `CLOSURE_NOT_SERIALIZABLE` diagnostic (§12.5) surfaces the offending field
with a hint before runtime. Testing against `Backend.Beam` on a DirectRunner checks
serialisability eagerly.

**Residual risk:** dynamically-composed non-serialisable captures may not be caught statically.

### Capability as `Set` vs type-level row

**Decision (for v2.1):** runtime `Set[Capability]` checked at `.run()`.

**Rationale:** capability negotiation happens at pipeline-submission time, not at composition
time. A type-level approach (e.g. `DStream[T] ! {EventTime, ExactlyOnce}`) would propagate
annotations through every operator, adding friction with no gain since the backend is only known
at `.run()`. The runtime-Set approach keeps user code clean and localises errors at the one
decision point where context is available.

**Future option:** a compile-time `RequiresCapability[C]` constraint on operator return types
could be added in v2.2 if tooling matures sufficiently.

### `Coder[T]` vs per-backend serde unification

Each backend has its own serialisation concept (Spark `Encoder`, Kafka `Serde`, Flink
`TypeInformation`, Beam `Coder`). The `Coder[T]` abstraction provides per-backend adapter
bridges (§10.2). Risk: the abstraction leaks for complex types (Avro unions, Protobuf oneofs,
Kryo-registered custom types).

**Mitigation:** JSON-based `Coder.derived` is sufficient for v2.1.1–v2.1.4; schema-aware coders
(Avro, Parquet) cover v2.1.6; full unification is v2.2.

### Kafka `KStream` / `KTable` duality

The `DStream` DSL does not expose the KStream / KTable distinction. The Kafka Streams backend
infers it from operator context (§9.3). Risk: users who want explicit changelog semantics have
no way to express that intent without `Kafka.changelog`.

**Mitigation:** `Kafka.changelog(...)` source factory (§6.1) added in v2.1.4 covers the
explicit use case. The implicit inference handles 90%+ of pipelines.

### Backpressure across the DStream / Source boundary

`toLocalSource` (§11.1) wraps DStream output in an `ArrayBlockingQueue(16)`, matching the queue
used by the v1.51 streams plugin
(`runtime/backend/interpreter/src/main/scala/scalascript/interpreter/CoroutineRuntime.scala:8`).
If the consuming `Source[A]` is slower than the DStream produces, native backend workers park
on `queue.put` — correct backpressure semantics, but potentially high latency for slow consumers.

**Mitigation:** `toLocalSource(bufferSize: Int)` overload lets the user tune queue depth.

### Go / no-go recommendation

**Go.** The case for a unified Beam-style distributed stream surface is strong:

1. **No new type-system infrastructure.** All types fit `SType.Named`; no parser or typer changes.
2. **Each phase is independently shippable.** v2.1.1 delivers a working product; v2.1.2–v2.1.7
   are strictly additive.
3. **Existing backends are in place.** The Spark backend (`SparkGen.scala`), the v1.22 native
   cluster, and the v1.51 `Source[A]` substrate are all production-ready. `DStream[T]` composes
   them under one DSL rather than adding new runtime machinery.
4. **Capability negotiation is the key differentiator.** No other stream abstraction in the
   ScalaScript ecosystem makes backend capability mismatches a build-time diagnostic.
5. **Risks are bounded.** Closure serialisation, serde unification, and KStream/KTable duality
   each have clear mitigations and do not block v2.1.1.

**Recommendation: begin v2.1.1 immediately after spec sign-off (= this document merged to `main`).**

---

## 17. Examples

### 17.1 Windowed word count — Kafka → Kafka

```ssc
// examples/distributed-streams.ssc (excerpt)
Pipeline.create("word-count")
  .read(
    Kafka.source[String]("kafka:9092", "input-text", "word-count-app")
      .withWatermark(WatermarkStrategy.boundedOutOfOrder(5.seconds))
  )
  .flatMap(_.split(" ").toList)
  .map(KV(_, 1))
  .window(Window.fixed(60.seconds))
  .withTrigger(Trigger.composite(
    early  = Trigger.repeatedly(Trigger.afterCount(100)),
    onTime = Trigger.afterWatermark,
    late   = Trigger.afterCount(1),
  ))
  .withAllowedLateness(10.seconds)
  .combinePerKey(_ + _)
  .write(Kafka.sink[KV[String, Int]]("kafka:9092", "word-count-out"))
  .run(Backend.KafkaStreams(KafkaConfig(brokers = "kafka:9092", appId = "word-count")))
```

Same pipeline on Spark Structured Streaming — no user-code changes:

```ssc
.run(Backend.Spark(SparkConfig(appName = "word-count", master = "yarn")))
```

### 17.2 Session-windowed user activity — Flink

```ssc
Pipeline.create("session-activity")
  .read(
    Kafka.source[UserEvent]("kafka:9092", "user-events", "sessions")
      .withWatermark(WatermarkStrategy.boundedOutOfOrder(30.seconds))
  )
  .keyBy(_.userId)
  .window(Window.session(10.minutes))
  .aggregatePerKey(SessionAccum.zero)(SessionAccum.add)(SessionAccum.merge)
  .map(kv => SessionSummary(kv.key, kv.value.clickCount, kv.value.duration))
  .write(Jdbc.sink[SessionSummary]("jdbc:postgresql://db/sessions", "user_sessions"))
  .run(Backend.Flink(FlinkConfig(jobManager = "flink:8081")))
```

### 17.3 Bounded ETL — Parquet → JDBC (native backend, no external cluster manager)

```ssc
val cluster = Cluster.connect(
  Node("worker-1@10.0.0.10:9100"),
  Node("worker-2@10.0.0.11:9100"),
)
Pipeline.create("parquet-to-db")
  .read(Files.source[SalesRow]("s3://bucket/sales/2024-01/*.parquet", FileFormat.Parquet))
  .filter(_.amount > 100)
  .keyBy(_.region)
  .combinePerKey((a, b) => a.copy(amount = a.amount + b.amount))
  .write(Jdbc.sink[SalesRow]("jdbc:postgresql://db/sales", "regional_totals"))
  .run(Backend.Native(cluster))
```

### 17.4 Broadcast state — enrichment with reference data (Flink)

```ssc
val orders   = Kafka.source[Order]("kafka:9092", "orders", "enricher")
val products = Kafka.changelog[Product]("kafka:9092", "products")

Pipeline.create("order-enrichment")
  .read(orders.withWatermark(WatermarkStrategy.boundedOutOfOrder(10.seconds)))
  .keyBy(_.productId)
  .broadcastState(products)
  .map { (order, productMap) =>
    order.copy(category = productMap.get(order.productId).map(_.category).getOrElse("unknown"))
  }
  .write(Kafka.sink[Order]("kafka:9092", "enriched-orders"))
  .run(Backend.Flink(FlinkConfig(jobManager = "flink:8081")))
```

### 17.5 Unit test with DirectRunner

```ssc
test("word count — bounded in-memory") {
  val result = InMemory.runAndCollect(
    Pipeline.create("test-wc")
      .read(InMemory.source(List("hello world", "hello scala", "world")))
      .flatMap(_.split(" ").toList)
      .map(KV(_, 1))
      .combinePerKey(_ + _)
  )
  assert(result.toSet == Set(KV("hello", 2), KV("world", 2), KV("scala", 1)))
}
```
