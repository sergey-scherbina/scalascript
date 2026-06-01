# Streams with Backpressure

Status: **design / planning**. Implementation tracked starting from v1.51.1.
Companion to [`docs/coroutines.md`](coroutines.md) (coroutine primitive and
one-shot fast path), [`docs/algebraic-effects.md`](algebraic-effects.md)
(typed effect rows and capability passing), and [`docs/mapreduce.md`](mapreduce.md)
(pull-based generator pipelines).

This document is the source of truth for the backpressured stream system:
why it exists, how `Source[A]` / `Sink[A]` / `Flow[A, B]` appear in types
and programs, how the hybrid pull/push demand protocol works, and how the
existing coroutine substrate becomes the runtime fast path on all backends.

---

## 1. Motivation and relation to v1.10 generators

v1.10 shipped a fully working `Generator[T]` primitive:

- `generator { body }` creates a lazy producer; `suspend(v)` pauses it and
  yields `v` to the consumer.
- The runtime uses a `SynchronousQueue[Option[Value]]` on JVM and interpreter
  (`runtime/backend/interpreter/src/main/scala/scalascript/interpreter/CoroutineRuntime.scala:8`),
  so `queue.put(Some(v))` *blocks* the producer virtual thread until the
  consumer calls `queue.take()`.
- That blocking `put`/`take` rendezvous IS pull-based backpressure — the
  producer is suspended whenever the consumer is not ready.
- `Generator[T]` ships with `map`, `filter`, `flatMap`, `zip`, `take`, `drop`,
  `foreach`, `toList` (`runtime/std/generators.ssc:24`).
- On JS, `function*` generators are emitted natively by `_makeGenerator`
  (`runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala:6579-6602`).

What `Generator[T]` did **not** ship is a richer stream model. Specifically:

| Missing capability | Consequence |
|--------------------|-------------|
| No batched demand (`request(n)`) | Only `request(1)` rendezvous; high per-element overhead for fast producers |
| No async producer | VT producer can block on `queue.put` but cannot suspend on `Async` operations mid-emission |
| No multi-consumer | `Generator[T]` is single-consumer; no `broadcast`, `balance`, or shared-subscription |
| No consumer-side cancellation | `coroutineCancel` exists but `Generator[T]` doesn't surface `close()` / `cancel()` to the consumer |
| No error channel | Generator swallows producer exceptions silently (`CoroutineRuntime.scala:138: catch case _: Throwable => ()`) |
| JS has no async producer | `_makeGenerator` emits `function*`; there is no `async function*` / `Symbol.asyncIterator` path for async emission |

v1.51 closes all six gaps in a single unified abstraction: `Source[A]` /
`Sink[A]` / `Flow[A, B]`. This spec is the design. Implementation follows in
v1.51.1 through v1.51.5+ after sign-off.

**Relation to v1.12 algebraic effects.** Streams are a separate primitive on
top of the v1.9 coroutine substrate — the same relationship that `Generator[T]`
has. They do not depend on the `! Eff` effect row; a `Source[A]` may be used
inside effectful or pure code without modification. Optional `! Stream`
effect-row integration is deferred to a future milestone (§11, §12).

---

## 2. Conceptual model

Three roles make up every stream pipeline:

| Role | Type | What it writes |
|------|------|----------------|
| **Source** | `Source[A]` | `stream { emit(x); … }` block, or a factory |
| **Flow** (middleware) | `Flow[A, B]` | `.map(f)`, `.filter(p)`, `.buffer(n, …)` |
| **Sink** | `Sink[A]` | `.runForeach(f)`, `.runFold(z)(f)`, `.runDrain` |

`Stream[A]` is a type alias for `Source[A]`; the two names are interchangeable.

**Hybrid push / pull semantics.** The user-facing API is push-shaped:

```ssc
stream { emit(1); emit(2); emit(3) }
  .map(_ * 10)
  .runForeach(println)
```

The producer "pushes" elements with `emit`; the consumer "pulls" them with
`runForeach`. Underneath, the runtime uses a **credit-based pull protocol**:

1. On subscription the consumer issues an initial credit of **16 tokens**.
2. The producer may emit up to 16 elements before blocking (on JVM/interpreter:
   the VT parks on `queue.put`; on JS: the `async function*` suspends at
   `yield`).
3. The consumer tops up credit (issues more `request(n)` tokens) as it
   processes elements, unblocking the producer in batches.
4. The default credit window and buffer size is **16** (the Akka Streams
   default). This gives high throughput for the common case while still bounding
   in-flight elements.

Changing the default is explicit: `.buffer(1, OverflowStrategy.Block)` recovers
the old rendezvous semantics of `Generator[T]`.

**Cross-backend semantic anchor.** When no native fast path exists (Wasm,
Spark, ScalaJs), streams are reified through the existing
`Computation = Pure | Perform | FlatMap` Free-monad ADT
(`lang/core/src/main/scala/scalascript/interpreter/Value.scala:240-306`) as
`Perform("Stream", op, args)` nodes. The JVM/interpreter/JS fast paths are
observationally equivalent; they differ only in throughput.

---

## 3. Type-level surface

### 3.1 New types — no parser changes required

`Source[A]`, `Sink[A]`, `Flow[A, B]`, and `Stream[A]` are all standard
parametric type names. The existing type-checker represents them as
`SType.Named(name, args)` (`lang/core/src/main/scala/scalascript/typer/Types.scala:19-45`) —
the same encoding as `List[A]`, `Option[A]`, and every other stdlib type.

The existing `TypeParser` in
`lang/core/src/main/scala/scalascript/artifact/InterfaceScope.scala:107-281`
handles parametric application via `parseNamedOrApp`. `Source[Int]` parses as
`Named("Source", List(Named("Int", Nil)))` with zero changes to the parser or
the type system.

No new `SType` case is needed. No new type-parser production is needed.

### 3.2 Type declarations (future `runtime/std/streams.ssc`)

```ssc
// Source — a producer of elements of type A
type Source[A]

// Sink — a consumer of elements of type A
type Sink[A]

// Flow — a stage that transforms A to B
type Flow[A, B]

// Stream is an alias for Source
type Stream[A] = Source[A]
```

The four types are **abstract** — their implementation is provided by the
runtime backend. User code only sees the combinators listed in §5.

### 3.3 Reference ADT for spec semantics

On backends without a native fast path, a stream is represented as a sequence
of `StreamOp` events threaded through the `Computation` Free monad:

```scala
// Spec-level ADT; not exposed to user code
enum StreamOp:
  case Emit(value: Value)      // producer emits an element
  case Complete                // producer has no more elements
  case Error(cause: String)    // producer terminated with an error
  case Cancel                  // consumer cancelled the subscription
  case Request(n: Int)         // consumer issues n more credit tokens
```

This encoding parallels how multi-shot algebraic effects reify continuations
through `Computation`; streams reify demand signals through the same carrier.

---

## 4. Stream creation and block syntax

### 4.1 Block syntax: `stream { emit(x) }`

```ssc
val numbers: Source[Int] =
  stream {
    var i = 0
    while i < 100 do
      emit(i)
      i += 1
  }
```

`stream { … }` is the primary construction primitive, parallel to
`generator { … suspend(v) … }`. The body runs as a coroutine:

- **JVM / Interpreter**: the body executes on a Loom virtual thread. `emit(x)`
  calls `queue.put(Some(x))` on the backing bounded `BlockingQueue[Option[Value]]`
  (initially sized 16). When the queue is full the producer VT parks until the
  consumer drains some elements.
- **JS**: the body compiles to an `async function*`. `emit(x)` lowers to
  `yield x`. The `async function*` naturally suspends at each `yield` and resumes
  when the consumer calls `iter.next()`. This is new emit code in `JsGen.scala`
  (§9 for details); it follows the same rewrite as `_makeGenerator`
  (`JsGen.scala:6579-6602`) but uses `async function*` to allow `await` inside
  the body.

The `stream` keyword is a block-constructor name, not a new parser production.
It parses the same way as `generator` — a function application with a block
argument.

### 4.2 Factory constructors (`Source` companion)

| Factory | Signature | Description |
|---------|-----------|-------------|
| `Source.from(iterable)` | `Source.from[A](xs: Iterable[A]): Source[A]` | Emits every element of `xs` in order |
| `Source.single(x)` | `Source.single[A](x: A): Source[A]` | Emits one element then completes |
| `Source.empty` | `Source.empty[A]: Source[A]` | Completes immediately with no elements |
| `Source.tick(d)` | `Source.tick(d: Duration): Source[Unit]` | Emits a unit every `d`; infinite |
| `Source.unfold(s)(f)` | `Source.unfold[S, A](s: S)(f: S => Option[(S, A)]): Source[A]` | Generates elements by evolving state |
| `Source.fromGenerator(gen)` | `Source.fromGenerator[A](gen: Generator[A]): Source[A]` | Wraps a v1.10 `Generator[T]` as a `Source` |
| `Source.fromCallback(register)` | `Source.fromCallback[A](register: (A => Unit) => Unit): Source[A]` | Push adapter: wraps a callback-registering function into a `Source` with a bounded buffer (size 16) |

`Source.fromCallback` is the bridge from push-based APIs (events, timers,
WebSocket messages) into the pull-based stream. The buffer absorbs bursts; when
the buffer is full, the callback invoker is either blocked (JVM/interpreter) or
drops elements according to the overflow strategy (JS).

---

## 5. Combinators and operator semantics

Operators fall into four families.

### 5.1 Transforming

| Operator | Signature | Notes |
|----------|-----------|-------|
| `map(f)` | `Source[A] => (A => B) => Source[B]` | Element-wise transform |
| `filter(p)` | `Source[A] => (A => Boolean) => Source[A]` | Drop non-matching |
| `flatMap(f)` | `Source[A] => (A => Source[B]) => Source[B]` | Per-element sub-stream; sub-streams run sequentially |
| `take(n)` | `Source[A] => Int => Source[A]` | Emit first `n`, then cancel upstream |
| `drop(n)` | `Source[A] => Int => Source[A]` | Skip first `n` elements |
| `scan(z)(f)` | `Source[A] => B => (B, A => B) => Source[B]` | Running aggregate |
| `fold(z)(f)` | `Source[A] => B => (B, A => B) => Source[B]` | Full aggregate; emits one result on completion |
| `mapAsync(n)(f)` | `Source[A] => Int => (A => Source[B]) => Source[B]` | `n` concurrent async sub-tasks; preserves order |
| `groupBy(key)` | `Source[A] => (A => K) => Source[(K, Source[A])]` | Groups into sub-streams by key |
| `mergeSubstreams` | `Source[Source[A]] => Source[A]` | Flattens a stream of streams concurrently |
| `throttle(rate)` | `Source[A] => Rate => Source[A]` | Emit at most `rate` elements per second |
| `debounce(d)` | `Source[A] => Duration => Source[A]` | Emit only after silence of duration `d` |
| `buffer(n, s)` | `Source[A] => Int => OverflowStrategy => Source[A]` | Insert a `n`-element buffer with overflow policy |

### 5.2 Consuming (terminal operators)

Terminal operators materialise the stream and return a result. The `run` prefix
marks them.

| Operator | Signature | Notes |
|----------|-----------|-------|
| `runForeach(f)` | `Source[A] => (A => Unit) => Unit` | Execute `f` per element; blocks until complete |
| `runFold(z)(f)` | `Source[A] => B => (B, A => B) => B` | Aggregate to a single value |
| `runToList` | `Source[A] => List[A]` | Collect all elements |
| `runDrain` | `Source[A] => Unit` | Consume all elements and discard |
| `to(sink)` | `Source[A] => Sink[A] => Unit` | Connect to a `Sink` and run |

### 5.3 Combining

| Operator | Signature | Notes |
|----------|-----------|-------|
| `concat(other)` | `Source[A] => Source[A] => Source[A]` | Complete first, then second |
| `merge(other)` | `Source[A] => Source[A] => Source[A]` | Interleave two sources as elements arrive |
| `zip(other)` | `Source[A] => Source[B] => Source[(A, B)]` | Pair elements; terminates with the shorter |
| `zipWith(other)(f)` | `Source[A] => Source[B] => (A, B => C) => Source[C]` | Zip then map |
| `broadcast(n)` | `Source[A] => Int => (Source[A], …)` | Fan-out to `n` consumers; slowest controls demand |
| `balance(n)` | `Source[A] => Int => (Source[A], …)` | Fan-out round-robin; first-available consumer gets each element |

### 5.4 Error and cancellation

| Operator | Signature | Notes |
|----------|-----------|-------|
| `onError(f)` | `Source[A] => (String => Unit) => Source[A]` | Side-effect on error; does not recover |
| `recover(pf)` | `Source[A] => (String => Option[A]) => Source[A]` | Replace error with a fallback element |
| `mapError(f)` | `Source[A] => (String => String) => Source[A]` | Transform the error message |
| `cancellable` | `Source[A] => (Source[A], () => Unit)` | Returns a cancellation handle alongside the stream |

### 5.5 Convention: demand and element flow

Operators are stages between producer and consumer. The convention, consistent
with Reactive Streams and Akka Streams, is:

- **Demand flows upstream**: consumers issue credit; producers honour it.
- **Elements flow downstream**: producers emit; consumers receive.
- Each stage runs on its own virtual thread on JVM / interpreter (lightweight,
  ~1 KB stack). On JS each stage becomes a chained `async function*` with no
  dedicated thread.

---

## 6. Backpressure protocol

### 6.1 Credit semantics

Backpressure is implemented as a token-credit protocol:

1. When a consumer subscribes to a `Source`, it issues an initial credit of
   **16** tokens (the Akka Streams default).
2. The producer may emit at most 16 elements before pausing. On JVM/interpreter
   the producer virtual thread blocks on `queue.put`; on JS the `async function*`
   suspends at `yield`.
3. When the consumer processes elements it issues additional `request(k)` tokens,
   increasing the producer's credit and allowing it to resume.
4. The default buffer between every pair of adjacent stages is 16 elements.

### 6.2 Migration from `Generator[T]`

`Generator[T]` uses a `SynchronousQueue` (capacity 0) — every emit blocks until
the consumer takes. Migrating from `Generator[T]` to `Source[A]` increases
throughput because the default buffer (16) allows the producer to run ahead of
the consumer by up to 16 elements.

To recover the exact rendezvous semantics:

```ssc
Source.fromGenerator(gen).buffer(1, OverflowStrategy.Block)
```

Or write the source directly with a tight buffer:

```ssc
stream { … emit(x) … }.buffer(1, OverflowStrategy.Block)
```

### 6.3 Overflow strategies (aliased from `actors.ssc`)

The existing `Overflow` enum (`runtime/std/actors.ssc:121-125`) already defines
the four strategies ScalaScript uses for bounded mailboxes. Streams **alias the
same enum** rather than introducing a new type:

```ssc
// Existing in runtime/std/actors.ssc — reused by streams
enum Overflow:
  case Block      // Suspend producer until buffer has space (cooperative backpressure)
  case DropOldest // Evict oldest buffered element to make room
  case DropNewest // Discard the incoming element silently
  case Fail       // Terminate the stream with an error
```

`OverflowStrategy` is a type alias for `Overflow` in `runtime/std/streams.ssc` for
clarity in stream contexts.

### 6.4 `broadcast` and multi-consumer demand

`broadcast(n)` fans one `Source` out to `n` consumers. The producer's credit is
the **minimum credit across all active consumers**: the slowest consumer
controls how fast the producer runs. Each consumer gets its own bounded buffer
(size 16); overflow strategy applies per-subscriber.

This is the conservative default. Applications that can tolerate drift between
subscribers may use `broadcast(n).buffer(capacity, OverflowStrategy.DropOldest)`
on the slow-consumer path.

---

## 7. Error and cancellation

### 7.1 Error propagation

Errors flow **downstream** and cancel **upstream**:

1. When a stage throws an exception or `emit` is called with a failure, it emits
   an `Error(cause)` event to the next downstream stage.
2. Downstream stages receive the error. Without a `.recover` or `.mapError`, it
   propagates all the way to the terminal operator, which rethrows it to the
   calling thread (JVM / interpreter) or rejects the `Promise` (JS).
3. On receiving an error, the runtime sends a `Cancel` upstream, propagating
   stage by stage back to the producer. Each stage's `finally` block runs.
4. This is the **Stop** supervision strategy (Akka Streams default). Per-stage
   `.recover(pf)` installs a **Resume** strategy for that stage.

```ssc
stream {
  emit(1); emit(2); throw RuntimeException("oops"); emit(4)
}
  .recover { case "oops" => Some(0) }   // substitute 0 for the error
  .runToList
// => List(1, 2, 0)
```

### 7.2 Cancellation

Consumer-side cancellation propagates upstream:

- `.take(n)` auto-cancels upstream after emitting `n` elements.
- `.cancellable` returns a `(Source[A], () => Unit)` pair; calling the second
  element cancels the subscription from the consumer side.
- Cancellation sends a `Cancel` event upstream, propagating stage by stage. Each
  producer VT (JVM/interpreter) is interrupted via `coroutineCancel`
  (`runtime/std/coroutine.ssc:36`); `finally` blocks run.

On JS, cancellation calls `iter.return()` on the `async function*` iterator
object, which triggers the `finally` block inside the generator.

### 7.3 Resource safety

Any `Source` that acquires a resource (a file handle, network socket, database
connection) must wrap it in a `try / finally` inside the `stream { … }` block:

```ssc
val rows: Source[Row] = stream {
  val conn = db.connect()
  try
    for row <- conn.query("SELECT …") do emit(row)
  finally
    conn.close()
}
```

The `finally` block runs regardless of normal completion, error, or consumer
cancellation. This is the primary resource-safety idiom in v1.51.

A `Source.bracket(acquire)(release)(use)` combinator ships in v1.51.4 for
structured resource lifetimes when the acquire/release pattern is more complex.

---

## 8. Integration adapters

The following adapters bridge existing ScalaScript surfaces into the unified
stream model. Signatures only — implementations land in v1.51.4.

### 8.1 v1.10 `Generator[T]`

```ssc
// Wrap an existing Generator as a Source
Source.fromGenerator[A](gen: Generator[A]): Source[A]
```

The generator drives the source: each `gen.next()` call issues one credit token.
The default buffer (16) allows the generator to run up to 16 elements ahead.

### 8.2 SSE (server-sent events)

```ssc
// Turn an SSE request into a Source[Event]
Source.fromSse(req: SseRequest): Source[Event]

// Turn a Source[Event] into an SSE stream — use with streamResponse
Sink.toSseStream(stream: SseStream): Sink[Event]
```

`Source.fromSse` replaces the ad-hoc callback push in
`runtime/std/http-plugin/src/main/scala/scalascript/compiler/plugin/http/HttpIntrinsics.scala:218-282`.
The bounded buffer (16) absorbs bursts from clients; `OverflowStrategy.DropOldest`
applies by default for SSE (newest events are more relevant).

### 8.3 WebSocket

```ssc
// Incoming WS messages as a Source
Source.fromWebSocket[A](handle: WsHandle): Source[A]

// Outgoing — connect a Source to a WS room's broadcast
Sink.toWsRoom[A](room: WsRoom): Sink[A]
```

Replaces the fire-and-forget `WsRoom.broadcast` loop in
`runtime/std/ws-plugin/src/main/scala/scalascript/compiler/plugin/ws/WsIntrinsics.scala:51-86`.
The comment at
`runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala:1562`
already documents the hazard: "Backpressure: socket.write is async — Node will
buffer". The `Sink.toWsRoom` adapter resolves this by gating emission on the
socket's drain event.

### 8.4 Actor mailbox

```ssc
// Drain an actor's mailbox as a Source
Source.fromActorMailbox[A](pid: ActorPid): Source[A]

// Send stream elements into an actor
Sink.toActor[A](pid: ActorPid): Sink[A]
```

`Sink.toActor` respects the actor's bounded mailbox — if `spawnBounded` was
used (`runtime/std/actors.ssc:131`), the `Block` overflow strategy means the
stream naturally applies backpressure back to the producer.

### 8.5 UI signal adapter (`Source.signal` / `sig.bind` — v1.51.5b)

```ssc
// Treat a ReactiveSignal as a Source — emits initial value + each update
Source.signal[A](sig: ReactiveSignal[A]): Source[A]

// Bind a Source back to a ReactiveSignal — last-value-wins
def bind[A](source: Source[A]): Unit  // method on ReactiveSignal[A]
```

`ReactiveSignal[A]` is declared in
`frontend/core/src/main/scala/scalascript/frontend/Primitives.scala`.
v1.51.5b adds a shared `subscribe` hook, so interpreter streams can listen to
frontend signals without making UI runtimes depend on the streams plugin.
The SwiftUI lowering lives in
`frontend/swiftui/src/main/scala/scalascript/frontend/swiftui/SwiftUIEmitter.scala:13-22, 67-97`.
The JavaFX and Swing signal buses are in
`frontend/javafx/src/main/scala/scalascript/frontend/javafx/JavaFxRuntime.scala`
and
`frontend/swing/src/main/scala/scalascript/frontend/swing/SwingRuntime.scala`.

In-process Swing and JavaFX runtimes keep their local state maps synchronized
with the underlying `ReactiveSignal`, so a stream can update a UI signal and the
bound controls refresh on the UI thread. SwiftUI currently lowers signals to
native `@State` in generated Swift code; a platform-native stream bridge remains
planned before SwiftUI can consume JVM/interpreter `Source.signal` live updates.

**Important caveat.** Signals are still current-value state. `Source.signal`
emits each observed `set` call in interpreter/JVM desktop runs, but consumers
should not use UI signals as an audit/event-sourcing log; use an explicit
event stream with bounded overflow policy for that.

---

## 9. Runtime semantics and backend notes

### 9.1 `Feature.Streams`

A new `case Streams` is added to the platform-capabilities group in
`runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala:37`
(after `PaymentRequest`). It is advertised by each backend once the plugin
lands:

- `runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmCapabilities.scala`
- `runtime/backend/js/src/main/scala/scalascript/codegen/JsCapabilities.scala`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/InterpreterCapabilities.scala`
- And the `node`, `spark`, `wasm`, `scalajs` equivalents.

Backends that do not yet advertise `Feature.Streams` (Wasm, Spark, ScalaJs in
v1.51.1) fall back to the `Computation`-walk reference path.

### 9.2 Per-backend lowering

| Backend | `emit(x)` path | Demand signal | Multi-consumer `broadcast` |
|---------|---------------|---------------|---------------------------|
| **JVM** | VT parks on `ArrayBlockingQueue(16).put(x)` (extends `JvmGen.scala:6117-6265`) | Consumer takes from queue, credits accumulate | Queue-per-subscriber VT; slowest controls demand |
| **Interpreter** | VT parks on `ArrayBlockingQueue(16).put(x)` (extends `CoroutineRuntime.scala:8`) | Same | Same |
| **JS** | `yield x` inside `async function*`; `Symbol.asyncIterator` protocol (new emit path — `_makeAsyncStream` runtime helper) | `iter.next()` called per credit token | Tee'd `async function*` with per-subscriber ring buffer |
| **Wasm / Spark / ScalaJs** | `Perform("Stream", "emit", …)` through `Computation` Free monad | `Perform("Stream", "request", n)` | Free-monad interpreter handles fan-out |

### 9.3 JS `async function*` emit path

`JsGen.scala:6579-6602` already emits synchronous `function*` for `Generator[T]`
via `_makeGenerator`. v1.51.2 adds an `_makeAsyncStream` helper alongside it.
The key differences:

- `stream { body }` compiles to `_makeAsyncStream(async function*() { body })`.
- `emit(x)` lowers to `yield x` (same rewrite as `suspend(x)` today).
- `await asyncOp()` inside `stream { … }` lowers to the standard `await` inside
  the `async function*` — async producers are natural.
- Consumer iteration uses `for await (const x of asyncStream)`, which implements
  the `Symbol.asyncIterator` protocol.

This closes the JS gap for async producers. Sync sources (`Source.from(iterable)`)
fall back to the existing sync `function*` path.

### 9.4 Runtime plugin layout

Per the `runtime/std/` plugin rule (AGENTS.md §76-100), the streams runtime lives
in `runtime/std/streams-plugin/` following the same four-file layout as
`runtime/std/http-plugin/` and `runtime/std/ws-plugin/`:

- `StreamsIntrinsics.scala` — registers stream ops as intrinsics.
- `StreamsInterpreterPlugin.scala` — interpreter-side dispatch.
- `runtime/std/streams.ssc` — extern type and function declarations.
- `src/test/scala/.../StreamsPluginInterpreterTest.scala` — unit tests.

---

## 10. Diagnostics

### 10.1 Backpressure deadlock

When a circular demand chain causes a deadlock (all parties waiting for
credit), the runtime detects the deadlock at the VT scheduling level:

```
error [STREAM_DEADLOCK]: stream pipeline deadlocked
  → Source at examples/pipeline.ssc:14 is waiting for credit
  → consumer at examples/pipeline.ssc:22 is waiting for an element
  → both are blocked: possible circular dependency in broadcast or zip
  Hint: insert .buffer(n, OverflowStrategy.DropOldest) to break the cycle,
        or verify that all branches of a broadcast have active consumers
```

### 10.2 Downstream cancelled while emitting

When `emit(x)` is called after the consumer has cancelled:

```
error [STREAM_CANCELLED]: emit called on a cancelled stream
  → emit at examples/pipeline.ssc:8:5
  → consumer cancelled at examples/pipeline.ssc:31:3 via take(5)
  Hint: check for consumer cancellation with isCancelled(), or
        wrap resource acquisition in try/finally to ensure cleanup
```

This is a **warning** in v1.51.1 (common pattern with `take(n)`) and becomes
a configurable error in v1.51.3.

### 10.3 Sink type mismatch

```
error [TYPE_MISMATCH]: cannot connect Source[Int] to Sink[String]
  → Source[Int] defined at examples/pipeline.ssc:5
  → Sink[String] defined at examples/pipeline.ssc:18
  → .to(sink) at examples/pipeline.ssc:22:12
  Hint: insert a .map(_.toString) stage to convert Int to String
```

---

## 11. Non-goals for v1.51

- **Reactive Streams TCK certification.** The `Source`/`Sink` semantics are
  compatible with the Reactive Streams specification (Reactive Streams 1.0.4),
  but formal TCK compliance testing is not in scope for v1.51.

- **Effect-row integration (`A ! Stream`).** Streams are a separate primitive
  in v1.51; they do not participate in the `! Eff` effect row from v1.12. A
  `Source[A]` may be used inside effectful or pure code without any annotation.
  §12 sketches what `! Stream` integration would look like.

- **JDK Flow / `java.util.concurrent.Flow.Publisher` interop adapter.** The JVM
  implementation uses VT + `ArrayBlockingQueue` rather than the JDK Flow API.
  A `Source.fromFlowPublisher` / `Sink.toFlowSubscriber` bridge is deferred.

- **Cross-process / network streams.** `Source` and `Sink` are intra-process
  primitives in v1.51. Distributing a stream over a WebSocket, actor boundary,
  or gRPC channel is future work.

---

## 12. Future work

### 12.1 Effect-row integration

Implemented in v1.51.6 — see §14.6 for the normative specification.

### 12.2 JDK Flow + Reactive Streams TCK adapter

Implement `Source[A] → java.util.concurrent.Flow.Publisher<A>` and the reverse
bridge in `runtime/std/streams-plugin/`. Run the Reactive Streams TCK
(Technology Compatibility Kit) and track conformance.

### 12.3 Cross-process streams

Wire a `Source[A]` over a network boundary:

- Over WebSocket: framing + per-message credit re-negotiation at the wire layer.
- Over actors: pass a `Source` handle across an actor channel by sending a
  subscription token; the remote side issues `request(n)` as actor messages.
- Over gRPC / HTTP/2: bidirectional stream lowering (future).

### 12.4 Capability-passing for `Scope` and `Cancel`

Thread a cancellation token through producer bodies without explicit plumbing,
using the v1.12 capability mechanism:

```ssc
def bigStream(scope: Scope ?=> Source[Item]): Source[Item]
```

When the consumer cancels, the `Scope` capability is automatically resolved to
a cancelled state; the producer body's `isCancelled()` check returns `true`
without requiring an explicit `CancellationToken` parameter.

### 12.5 Adaptive credit / flow control tuning

Replace the fixed credit window (16) with an adaptive algorithm that increases
or decreases the credit window based on observed producer throughput and
consumer processing latency. This is an optimisation that would not change
observable stream semantics.

---

## 13. Examples

The following snippets illustrate the stream surface. Runnable examples ship in
`examples/streams.ssc` in the v1.51.1 implementation milestone.

```ssc
// 1. Backend pipeline
Source.from(1 to 100)
  .map(_ * 2)
  .filter(_ > 50)
  .runForeach(println)
// → 52, 54, 56, … 200

// 2. Async producer — awaits network calls between emits
val items: Source[Response] = stream {
  var page = 0
  var done = false
  while !done do
    val resp = await(http.get(s"/api/items?page=$page"))
    if resp.items.isEmpty then done = true
    else
      for item <- resp.items do emit(item)
      page += 1
}

// 3. SSE bridge with throttle
val events: Source[Event] = Source.fromSse(req)
events
  .throttle(Rate(100, 1.second))
  .runForeach(write)

// 4. WS round-trip
val incoming: Source[String] = Source.fromWebSocket(ws)
val replies: Sink[String]     = Sink.toWsRoom(room)
incoming
  .filter(msg => !msg.startsWith("ping"))
  .map(transform)
  .to(replies)

// 5. Fan-out via broadcast
val src = Source.from(0 to 1000)
val (analytics, archive) = src.broadcast(2)
analytics.map(summarise).runForeach(dashboardPush)
archive.runFold(List.empty)((acc, x) => x :: acc)

// 6. Buffer with drop-oldest overflow
fastSensor
  .buffer(256, OverflowStrategy.DropOldest)
  .debounce(50.millis)
  .runForeach(slowUiUpdate)

// 7. UI signal adapter (v1.51.5)
val temperatureSource: Source[Double] = Source.signal(tempSignal)
temperatureSource
  .map(celsius => celsius * 9.0 / 5.0 + 32)
  .runForeach(f => fahrenheitSignal := f)
```

---

## 14. Implementation phasing (post-v1.51)

### v1.51.1 — Plugin scaffolding and `Source` core

**Scope:** interpreter + JVM only. No JS yet.

1. Create `runtime/std/streams-plugin/` with the four-file plugin layout
   (mirroring `runtime/std/http-plugin/` and `runtime/std/ws-plugin/`):
   `StreamsIntrinsics.scala`, `StreamsInterpreterPlugin.scala`,
   `src/test/scala/.../StreamsPluginInterpreterTest.scala`.
2. Create `runtime/std/streams.ssc` declaring `Source[A]`, `Sink[A]`,
   `Flow[A, B]`, `Stream[A]`, the `stream { emit }` extern, and the
   minimum operator set: `map`, `filter`, `runForeach`, `runFold`, `runToList`.
3. Extend `CoroutineRuntime.scala:8` to use `ArrayBlockingQueue(16)` instead of
   `SynchronousQueue` for stream sources (generator sources retain their existing
   `SynchronousQueue` behaviour).
4. Add `Feature.Streams` to `Feature.scala:37` and advertise it in the
   interpreter and JVM capabilities files.
5. Deliver `examples/streams.ssc` with the six examples from §13.

### v1.51.2 — JS backend

**Scope:** JS `async function*` emit path.

1. Add `_makeAsyncStream(asyncGenFn)` runtime helper to the JS preamble in
   `JsGen.scala` alongside `_makeGenerator` (`JsGen.scala:6579-6602`).
2. Compile `stream { body }` to `_makeAsyncStream(async function*() { body })`;
   lower `emit(x)` to `yield x` in the async generator body.
3. Compile consumer iteration (`runForeach`, `for x <- source`) to
   `for await (const x of asyncStream)`.
4. Add `Feature.Streams` to `JsCapabilities.scala`.
5. Run the `StreamsPluginInterpreterTest` suite on the JS codegen output to
   verify behavioural parity with the interpreter.

### v1.51.3 — Flow, Sink, combining operators

**Scope:** multi-consumer and combination primitives.

1. Implement `Flow[A, B]` (stateful transformation pipeline) and `Sink[A]`
   (typed consumer).
2. Implement combining operators: `zip`, `merge`, `concat`, `broadcast(n)`,
   `balance(n)`.
3. `broadcast(n)` uses queue-per-subscriber; slowest consumer controls demand
   (see §6.4).
4. Add `groupBy(key)` + `mergeSubstreams` for partitioned processing.

### v1.51.4 — SSE/WS adapters, `mapAsync`, error recovery

**Scope:** network adapters and error handling.

1. Implement `Source.fromSse`, `Sink.toSseStream` in
   `runtime/std/http-plugin/src/main/scala/scalascript/compiler/plugin/http/HttpIntrinsics.scala:218-282`.
2. Implement `Source.fromWebSocket`, `Sink.toWsRoom` in
   `runtime/std/ws-plugin/src/main/scala/scalascript/compiler/plugin/ws/WsIntrinsics.scala:51-86`.
3. Implement `mapAsync(n)(f)` with configurable parallelism.
4. Implement error-recovery operators: `.recover(pf)`, `.mapError(f)`.
5. Add `Source.bracket(acquire)(release)(use)` for structured resource lifetimes.
6. Promote the "emit on cancelled stream" warning (§10.2) to a configurable
   error.

### v1.51.5 — Buffer strategies, time-based operators, UI signal adapter

**Scope:** advanced backpressure and the first interpreter-side UI reactivity
bridge.

1. Implement `.buffer(n, OverflowStrategy)` with all interpreter strategies
   (`Backpressure`/`Block`, `Drop`, `DropHead`/`DropOldest`, `Fail`).
   **Landed (2026-05-27)** in `streams-plugin`.
2. Implement time-based operators: `.throttle(Rate)`, `.debounce(Duration)`.
   **Landed (2026-05-27)** as interpreter wall-clock scheduling:
   throttle preserves order and paces by `Rate(elements, perMillis)`; debounce
   emits the latest value from a burst after the debounce duration.
3. Implement the UI signal adapter:
   - `Source.signal[A](sig): Source[A]` **landed (2026-05-27)** for
     frontend `ReactiveSignal` live subscriptions plus generic current-value
     instances.
   - `sig.bind(source: Source[A]): Unit` **landed (2026-05-27)** for
     frontend `ReactiveSignal` in the interpreter/JVM desktop path.
   - JavaFX/Swing in-process signal-bus wiring **landed (2026-05-27)**.
   - SwiftUI platform-native stream bridging remains planned.

### v1.51.6 — Effect-row integration (Landed 2026-05-28)

Full algebraic-effect integration for streams. `Stream[A]` is a parameterized
effect that unifies with the emit calls inside a `runStream` body, enforcing
type safety at compile time.

#### Surface API

```ssc
// Emit one element (typed ! Stream[A])
extern def Stream.emit[A](value: A): Unit ! Stream[A]

// Terminate the stream early — subsequent emits are dropped
extern def Stream.complete[A](): Nothing ! Stream[A]

// Fail the stream with a message — downstream sees the error on first pull
extern def Stream.error[A](msg: String): Nothing ! Stream[A]

// Advisory demand hint — no-op in v1.51.6; reserved for future backpressure
extern def Stream.request[A](n: Int): Unit ! Stream[A]

// Discharge the Stream effect.  Returns (Source[A], R): emitted source + body result.
extern def runStream[A, R](body: => R): (Source[A], R)
```

#### Parameterized effect ops

`Stream[A]` is represented as `EffectOp("Stream", List(A))` inside `EffectRow`.
All existing effects (`Logger`, `Clock`, etc.) remain as `EffectOp(name, Nil)`.
Effects may carry 0, 1, or N type args — the `EffectOp(name, args: List[SType])`
shape supports arbitrary arity. Future effects like `State[S, V]` or `Reader[R]`
can carry multiple type parameters without any additional type-system changes.

Unification of two `EffectOp`s: same name AND pairwise-unifiable arg lists.
A `Stream[Int]` and `Stream[String]` in the same effect row is a compile error.

#### Canonical function shape

```ssc
// Producer — emits via effect, no meaningful return value
def readLines(): Unit ! Stream[String] =
  Stream.emit("hello")
  Stream.emit("world")

val (src, _) = runStream { readLines() }
src.runToList()  // List("hello", "world")

// Producer + result — emits via effect AND returns a value
def readLines(): Int ! Stream[String] =
  Stream.emit("hello")
  Stream.emit("world")
  2  // count of emitted lines

val (src, count) = runStream { readLines() }
src.runToList()  // List("hello", "world")
count            // 2
```

#### Cross-backend parity

All three backends return the `(Source[A], R)` tuple from `runStream`:

- **Interpreter**: `TupleV(List(sourceV, bodyResultV))` — `Source.from(emitted)` or
  `Source.failed(msg)` for the error path
- **JS**: `[_makeAsyncStream(...), bodyResult]` — standard 2-element JS array;
  the stream side uses an async generator; the error path yields a generator
  that `throw`s on first iteration
- **JVM**: `(emitted.toList, bodyResult)` — a plain Scala tuple; error path
  throws `RuntimeException(msg)` immediately

#### Unified runner signature

`runStream` return type `(Source[A], R)` is an instance of the general
`Out(E) ++ (R,)` pattern from the v1.60 tuple monoid:

```
Out(Stream[A])           = (Source[A],)
Out(Stream[A]) ++ (R,)   = (Source[A], R)
```

See `docs/tuple-monoid.md` for the full algebraic specification and the
`Out(E)` table covering all built-in effects.

**v1.60.4 — bare-value concat.** `++` accepts bare (non-tuple) operands:
`(Source[A],) ++ R = (Source[A], R)` where `R` is any type, not just a tuple.
The identity holds for bare values too: `() ++ v = v` (not `(v,)`).
This is automatically correct in the `Out(E) ++ (R,)` formula since `(R,)` is
already a 1-tuple, but it also means that `Out(State[S]) ++ R` works even when
`R` is a bare type like `Int`.

#### Known open

Consumer-side `handle[Stream[A]] { case Stream.emit(x, resume) => … }` multi-shot
continuation is deferred to v1.51.7+.

---

## 15. Risks and open questions

### Multi-consumer broadcast memory cost

`broadcast(n)` allocates a queue-per-subscriber. With `n` slow subscribers and
a fast producer, up to `n × 16` elements may be resident in memory at once
before backpressure kicks in. For very high-cardinality fan-out (`n > 100`),
this is a concern. **Mitigation:** document the cost at the `broadcast` call
site; recommend explicit `.buffer(capacity, OverflowStrategy.DropOldest)` on
slow-subscriber paths; provide a `Sink.fanOut(n, strategy)` combinator in
v1.51.3 with customisable per-subscriber policy.

### JS `async function*` lowering

`async function*` is ES2018 and is natively supported in all modern JS engines
(V8 ≥ 6.3, SpiderMonkey ≥ Firefox 57, JavaScriptCore ≥ Safari 12). No
transpilation is needed for ScalaScript's target environments (Node.js,
modern browsers). The complexity is moderate: a new code path in `JsGen.scala`
for `stream { … }` blocks, plus `_makeAsyncStream` in the JS preamble. Sync
`Source.from(iterable)` continues to use the existing sync `function*` path.
**Mitigation:** prototype `_makeAsyncStream` against the existing
`_makeGenerator` template (`JsGen.scala:6579-6602`) in v1.51.2; share the
`_IterStep` result type between sync and async variants.

### Interaction with v1.12 algebraic effects

If a stream stage performs algebraic effects (e.g. `Logger.log` inside a
`stream { … }` block), those effects must be handled. The recommended semantics
is that **stages run in their caller's effect context**: the handler installed
before `runForeach` handles effects emitted by all stages in the pipeline.

On JVM and interpreter this requires producer VTs to inherit the calling
thread's effect handler stack. This is minor work in `CoroutineRuntime.scala` —
thread-local handler state must be copied to the producer VT on creation.

**Mitigation:** document the semantics explicitly; add a test case with a
`handle[Logger]` wrapping a `stream { … emit(x) … Logger.log(…) … }.runForeach`.

### `mapAsync` cancellation

`mapAsync(n)(f)` launches `n` concurrent sub-tasks. When the stream is
cancelled mid-flight, in-flight `f` invocations may still be running. The
v1.51.4 implementation treats `mapAsync` as best-effort cancel: in-flight tasks
run to completion but their results are discarded. **For hard cancel**, wrap `f`
in a `cancellable` scope (v1.51.4). Document this explicitly at `mapAsync`.

### Resource safety under cancellation

Files and sockets opened inside `stream { … }` must be closed when the stream
terminates, whether by normal completion, error, or consumer cancellation. The
`try / finally` idiom (§7.3) is the canonical mechanism. The `Source.bracket`
combinator (v1.51.4) provides a higher-level alternative.

**Mitigation:** lint rule (warn-on-open-resource-outside-try) in v1.51.3; track
as a standard code-review checklist item.

### UI signal adapter: last-value-wins vs order preservation

`Source.signal(sig)` observes frontend `ReactiveSignal.set` calls in
interpreter/JVM desktop runs. This is useful for UI coordination, but it is not
a durable event log: subscribers start from the current value, updates are
process-local, and platform codegen backends may have native signal stores. For
event sourcing or audit logging, use `Source.fromCallback` with a bounded
buffer and `OverflowStrategy.Fail` instead.

---

## 16. Go / no-go recommendation

**Go.** The architectural fit is unusually clean:

- The coroutine VT substrate (`CoroutineRuntime.scala:8`, `JvmGen.scala:6117-6265`)
  gives the JVM and interpreter fast path for free — `ArrayBlockingQueue(16)`
  replaces `SynchronousQueue` and the credit protocol is implicit.
- JS native `async function*` (ES2018) gives the JS fast path — the template is
  the existing `_makeGenerator` (`JsGen.scala:6579-6602`); the new helper is
  `_makeAsyncStream`.
- The `Computation = Pure | Perform | FlatMap` Free monad already provides the
  cross-backend reference semantics; streaming events become `Perform` nodes.
- The type system needs zero changes: `Source[A]`, `Sink[A]`, `Flow[A, B]` are
  `SType.Named` applications handled by the existing `TypeParser`.
- The overflow strategy enum is already defined in `runtime/std/actors.ssc:121-125`;
  streams alias it rather than duplicating.
- `Feature.Streams` (one new case in `Feature.scala:37`) is the only change to
  existing infrastructure before the plugin scaffolding begins.

The risk surface is bounded and each milestone is independently shippable. There
are no blockers.

**Implementation sequence:**

- **v1.51.1** immediately after spec sign-off (plugin scaffolding + interpreter
  - JVM `Source` core).
- **v1.51.2** and **v1.51.3** can run in parallel after v1.51.1 lands (JS
  backend and combining operators are independent work streams).
- **v1.51.4** and **v1.51.5** follow sequentially.
- **v1.51.6** is open — revisit after v1.51.5.

**Recommendation: start v1.51.1 immediately after spec review.**
