# Map-reduce — `Dataset[T]`, local and distributed

Status: **v1.21 ✓ Landed** (local sequential + parallel).
**v1.22 ✓ Landed** (distributed via v1.6 Phase 3 actors).
Implementation tracked in MILESTONES.md.  Companion to
[`specs/coroutines.md`](coroutines.md) (v1.10 generators for
streaming), `MILESTONES.md` v1.6 Phase 3 (distributed actors
— required for v1.22), and `MILESTONES.md` v1.3 (real-thread
async — required for v1.21 parallel local execution).

This document is the source of truth for ScalaScript's
map-reduce surface: the `Dataset[T]` API, local-parallel
execution model, distributed execution model, failure
semantics, and the boundary against cluster management
(which is `specs/cluster-management.md`).

## 1. Motivation

ScalaScript already has `List[T]` with `map` / `filter` /
`foldLeft` / `traverse` — that's sequential, single-core,
in-memory.  What's missing:

1. **Parallel local execution** — `xs.map(f)` across multiple
   cores when each `f(x)` is independent and the list is
   large.  Today the user writes `Async.parallel(...)`
   plumbing by hand.
2. **Distributed execution** — split a workload across remote
   nodes (v1.6 Phase 3 actors).  No standard library shape;
   each app would re-invent partition / dispatch / collect.
3. **Streaming aggregations** — reduce over a large stream
   without holding everything in memory.  v1.10 generators
   give the streaming primitive but no reducer composition.

`Dataset[T]` unifies these: same fluent API on top of three
execution backends (sequential, local-parallel, distributed),
selected by the user's `.runLocal` / `.runParallel` /
`.runDistributed(nodes)` choice.

## 2. The `Dataset[T]` surface

A single user-facing type with a familiar fluent API:

```scala
trait Dataset[T]:
  // Transformations (lazy — compose a plan)
  def map[U](f: T => U): Dataset[U]
  def filter(p: T => Boolean): Dataset[T]
  def flatMap[U](f: T => Iterable[U]): Dataset[U]
  def take(n: Int): Dataset[T]
  def drop(n: Int): Dataset[T]
  def distinct: Dataset[T]

  // Set-style binary ops between two datasets (v1.21 follow-up)
  def union(other: Dataset[T]): Dataset[T]      // concat; multiplicities preserved
  def intersect(other: Dataset[T]): Dataset[T]  // common elements; dedups; left order

  // Element-wise pairing (v1.21 follow-up)
  def zip[U](other: Dataset[U]): Dataset[(T, U)]  // stops at shorter side
  def zipWithIndex: Dataset[(T, Int)]

  // Key-based (introduces shuffle in distributed mode)
  def groupBy[K](key: T => K): Dataset[(K, List[T])]
  def reduceByKey[K](key: T => K)(combine: (T, T) => T): Dataset[(K, T)]
  def sortBy[K: Order](key: T => K): Dataset[T]

  // Terminal operations (force evaluation)
  def collect(): List[T]
  def count(): Long
  def reduce(combine: (T, T) => T): T
  def fold[U](z: U)(combine: (U, T) => U): U
  def foreach(action: T => Unit): Unit
  def first(): Option[T]
  def toGenerator: Generator[T]    // streaming output (v1.10)

  // Numeric / ordered aggregations (v1.21 follow-up)
  def min(): T           // throws on empty
  def max(): T           // throws on empty
  def sum(): T           // additive identity (0) on empty
  def avg(): Double      // throws on empty
  def top(n: Int): List[T]            // n largest, descending
  def takeOrdered(n: Int): List[T]    // n smallest, ascending
  def countByValue(): Map[T, Long]    // element-frequency histogram

object Dataset:
  // Constructors
  def of[T](items: T*): Dataset[T]              // from in-memory items
  def fromList[T](list: List[T]): Dataset[T]    // from a List
  def fromGenerator[T](gen: Generator[T]): Dataset[T]   // from a stream (v1.10)
  def fromFile(path: String): Dataset[String]   // line-by-line, lazy
```

### Execution mode

Three execution modes select how transformations + terminal
operations run.  Same API, different runner:

```scala
val ds = Dataset.of(1, 2, 3, 4, 5).map(_ * 2).filter(_ > 4)

ds.runLocal()                  // sequential single-threaded
ds.runParallel()               // local parallel (uses Async.parallel)
ds.runDistributed(nodes)       // distributed across actor cluster
```

`runLocal()` and `runParallel()` ship in v1.21.
`runDistributed(...)` ships in v1.22.

## 3. v1.21 — Local execution (sequential + parallel)

### 3.1 Sequential

Same semantics as `List[T].map(...).filter(...)` — but
**lazy**: a `Dataset[T]` accumulates the plan and only
executes on a terminal operation (`collect()`, `count()`,
`reduce(...)`, etc.).  Difference vs `List`:

- Plan is reusable: `val ds = …` can be terminated multiple
  times.
- Fusion across operations: `.map(f).filter(p).map(g)` runs
  as one pass, not three intermediate lists.

### 3.2 Parallel local

Same plan, parallel execution.  Built on **v1.3 real-thread
Async** (`runAsyncParallel` — already landed on JVM Loom,
in flight on Node):

```scala
val sumOfSquares = Dataset.of(1L to 1_000_000L: _*)
  .map(x => x * x)
  .reduce(_ + _)
  // ↑ runs across all available cores on Loom-JVM
```

Mechanics:

1. `runParallel` partitions the input into `numCores`
   chunks.
2. Each chunk runs the transformation chain on its own
   virtual thread.
3. The reducer (or `collect`) merges partial results.

Partition strategy:
- For `Dataset.of(...)` / `fromList(...)` — fixed-size
  chunks
- For `fromGenerator(...)` — work-stealing pull from the
  generator (one worker per core, all pulling)
- For `fromFile(...)` — line-aware splitting

Limitations of v1.21 (deliberate scope cap):
- No `groupBy` / `sortBy` parallelisation (they need a
  shuffle phase — defer to v1.22's distributed shuffle
  primitives, then re-use locally)
- No partial-failure handling — if any worker throws, the
  whole `Dataset` operation throws (use `throws[Result, E]`
  per v1.15 for typed channels)

### 3.3 Per-backend parallel implementation

| Backend | Mechanism | Status |
|---------|-----------|--------|
| JVM (Loom) | Virtual thread per partition; `Async.parallel` from v1.3 | Available |
| Interpreter (NIO) | Cooperative parallelism via continuation scheduling — single-threaded, partitioning gives concurrency but not true parallelism on a multi-core machine | Available; same observable semantics |
| JS (Node) | `worker_threads` + `Atomics.wait` (v1.3 stage 2) | Depends on v1.3 Node parallel — currently flagged as "deferred until Node fork-join shape resolved" |

Strategy for JS: ship v1.21 with sequential-fallback on JS
when v1.3 Node parallel isn't in.  Users still get the
`Dataset[T]` API; they get the parallel speedup later.

## 4. v1.22 — Distributed execution

Built on **v1.6 Phase 3 distributed actors**.  Each worker
is a registered actor on a remote node; the coordinator
(itself an actor) dispatches partitions, collects partial
reduces, drives the shuffle for `groupBy` / `sortBy`.

### 4.1 The cluster handle

```scala
val cluster = Cluster.connect(
  Node("worker-1@10.0.0.10:9100"),
  Node("worker-2@10.0.0.11:9100"),
  Node("worker-3@10.0.0.12:9100")
)

val result = Dataset.fromFile("/data/large.csv")
  .map(parseRow)
  .filter(_.amount > 100)
  .groupBy(_.region)
  .runDistributed(cluster)
  .collect()
```

`Cluster` is a thin handle over a set of `Pid`s on remote
nodes.  Lifecycle: `connect` opens the WS channels via
v1.6 Phase 3 `connectNode`; `cluster.close()` tears them
down.  See `specs/cluster-management.md` (deferred future
work) for fancier discovery / membership patterns.

### 4.2 Dispatch model

Single-coordinator, N-worker pattern:

```
       coordinator (local node)
       /     |       \      \
    worker1 worker2 worker3 ...
```

Steps for a `Dataset` operation:

1. **Plan compilation** — coordinator turns the lazy plan
   into a `Stage` graph (each stage is a map+filter chain
   between shuffles).
2. **Partition** — input split into N chunks; each chunk
   sent as a message to one worker.
3. **Local execution** — each worker runs the stage chain
   on its partition (using the v1.21 local-parallel
   machinery).
4. **Shuffle** (if `groupBy` / `reduceByKey` is present) —
   workers exchange data by key; the protocol is
   `Coordinator-mediated all-to-all` for v1.22 (each
   worker sends key-grouped data back to the coordinator,
   which redistributes — simple but with a coordinator
   bottleneck.  Worker-to-worker direct exchange is a
   v1.22.x optimization).
5. **Collect** — coordinator gathers final results from
   workers.

### 4.3 Failure handling

Each `worker.process(partition)` is wrapped in a v1.6
supervision link.  On worker failure:

- **Default — fail the whole operation**: coordinator
  detects via `Down(workerPid, reason)` message; aborts;
  surfaces `DistributedError(failedNode, reason)` to caller.
- **Opt-in retry** — `.runDistributed(cluster, retries =
  3)` re-sends the failed partition to a healthy worker
  up to N times before giving up.
- **Opt-in partial** — `.runDistributed(cluster, allowPartial
  = true)` collects results from successful workers,
  surfaces failure summary alongside.

### 4.4 Serialisation

Worker functions and typed partition payloads need to cross node boundaries.
Options:

- **Named handlers** — register reusable mappers /
  reducers on every node ahead of time; dispatch by name
  (Erlang-style; doesn't serialise the closure).  v1.22
  ships this.
- **Typed partition payloads** — `DatasetCodec[A]` provides
  `DatasetWirePartition` plus `encodePartition` /
  `decodePartition` and batch `encodePartitions` /
  `decodePartitions` helpers.  They encode each worker
  partition as `Vector[JsonValue]` with a stable `partitionId`,
  preserving decode-error paths such as
  `$.partition[2].5.amount`.  `runDistributedWire` now moves
  these payloads through `WireProcessPartition` /
  `WirePartitionResult`; stage handlers on this path consume and
  produce `JsonValue`, while callers keep domain typing at the
  `DatasetCodec.encodePartitions[A]` / `decodePartitions[B]`
  boundary. `runDistributedShuffleWire` extends the same representation to
  coordinator-mediated `groupBy` / `reduceByKey`: Phase A emits
  `WireShufflePartial` buckets keyed by `JsonValue`, Phase B processes
  `WireProcessKeyPartition` messages, and the final output is a
  `DatasetWirePartition`. `DistributedDataset.encode/decode[A]` wraps the
  common typed boundary, and `DistributedDataset.run/runShuffle[A, B]` wrap
  the actor-effect map and shuffle calls for JVM generated code.
  `DatasetWire` now wraps the same `DatasetWirePartition` in a shared
  `WireEnvelope(protocol = "dataset")`, supports JSON/MsgPack/CBOR
  encode/decode, and chunks large partitions at element boundaries before
  reassembly. `runDistributedWire`, `runDistributedShuffleWire`, and
  `DistributedDataset.run/runShuffle` accept `wireFormat`; JSON keeps the
  existing object-message fallback, while MsgPack/CBOR send `DatasetWire`
  envelope bytes in partition, shuffle-bucket, and key-result actor messages.
- **Closure serialisation** — serialise the `T => U`
  closure including its captured environment.  Deferred to
  v1.22.x; requires v1.14 `derives` + bytecode shenanigans;
  brittle in practice.

For v1.22: **named handlers only for functions**.  Users register `def
parseRow(line: String): Row = …` on every node (via the
shared codebase deployment); the API uses the function name
in messages.  Spark-like inline closures wait for closure
serialisation. Typed data movement can use `DistributedDataset.encode[A]`,
`runDistributedWire` / `runDistributedShuffleWire`, and
`DistributedDataset.decode[B]` where a distributed worker boundary needs a
stable representation for domain values. For the common case,
`DistributedDataset.run/runShuffle[A, B]` combine the encode/decode boundary
with the actor-effect call.

### 4.5 Backend support

Distributed execution only works on backends that support
v1.6 Phase 3 distributed actors — that is, **JVM and INT
post-Phase-3**.  JS as a worker is plausible (Node + WS),
JS as a coordinator works too.

| Backend | Worker | Coordinator |
|---------|--------|-------------|
| JVM | ✅ | ✅ |
| INT | ✅ | ✅ |
| JS (Node) | ✅ | ✅ |
| JS (browser SPA) | ❌ (no inbound WS) | ❌ |

Browser-SPA can use `Dataset.runLocal` / `runParallel` only.

### 4.6 Known issue — distributed conformance tests are `pending:`

The six v1.22 phase-6 conformance tests
(`conformance/distributed-{map,shuffle,failure-retry,failure-partial,
heterogeneous}.ssc` plus `cluster-connect.ssc`) currently fail to
compile on JVM because `JvmGen.inlineImport` doesn't rewrite the
dep code's bare-name calls (`self()`, `connectNode`,
`receiveWithTimeout`, `pid ! msg`, …) the same way `genModule`
rewrites user code. When `std/mapreduce/cluster.ssc`'s
`Cluster.healthCheck` body lands inside the emitted
`object std { object mapreduce { … } }` wrapper, those bare names
can't be resolved.

The tests are marked `pending: needs std/mapreduce/* auto-resolution
in 'ssc compile'` in their frontmatter so the conformance suite
reports `PENDING` (separate from `FAIL`). The intended API is
preserved as documentation. Partial fixes landed 2026-05-19 —
`AutoResolve` cycle detection + a brace-balanced duplicate-`object`
merger — but the bare-name rewriting in dep blocks is a separate
JvmGen change. Full design in
[`specs/modularity.md` §12](modularity.md#12-technical-debt--jvmgen-inlineimport-bare-name-leakage)
and the open item in `MILESTONES.md` "Known issues / latent flakes".

## 5. Worked examples

### 5.1 Local — word count

```scala
import std/io.{readLines}

val counts = Dataset.fromFile("./big-text.txt")
  .flatMap(_.split("\\s+"))
  .filter(_.nonEmpty)
  .map(_.toLowerCase)
  .groupBy(identity)
  .map { case (word, occurrences) => (word, occurrences.length) }
  .sortBy(_._2)(using Order[Int].reversed)
  .take(10)
  .runParallel()
  .collect()

counts.foreach { case (w, c) => println(s"$w: $c") }
```

### 5.2 Distributed — log aggregation

```scala
val cluster = Cluster.connect(
  Node.fromConfig()    // reads from std/config or env
)

val errorsByService = Dataset.fromGenerator(LogReader.streamFiles(args))
  .filter(_.level == "ERROR")
  .map(log => (log.service, 1L))
  .reduceByKey(_._1)((a, b) => (a._1, a._2 + b._2))
  .runDistributed(cluster, retries = 3)
  .collect()

cluster.close()
```

### 5.3 With `throws` (v1.15 integration)

```scala
def loadAndParse(path: String): Dataset[Row] throws ParseError =
  Dataset.fromFile(path).map(parseRowOrFail)

// `parseRowOrFail: String => Row throws ParseError`
// Dataset.map auto-lifts via std typeclasses; result Dataset
// carries the throws-typed potential failure into terminal
// operations.
```

Integration with v1.15 `throws` lives in
`std/mapreduce/throws.ssc` as a sub-module — provides
`.runLocalThrowing` etc. variants that propagate `throws[T,
E]` through the pipeline.  Out of v1.21 v1 scope; add when
v1.15 is firm.

## 6. Coexistence

| Feature | Relationship |
|---------|--------------|
| **v1.3 real-thread Async** | v1.21 parallel local execution rides `runAsyncParallel`.  JS path waits for v1.3 Node parallel. |
| **v1.6 Phase 3 distributed actors** | v1.22 dispatch + supervision built directly on `connectNode` / actor mailboxes / link supervision. |
| **v1.10 Generators** | `Dataset.fromGenerator` + `.toGenerator` give streaming interop.  Backpressure: pull-based; coordinator pulls from generators on workers. |
| **v1.11.5 Free monad** | A `Dataset[T]` plan IS a Free-monad-like value (data describing computation).  Could be expressed as `Free[DatasetOp, A]` for inspection / optimization / testing.  Defer to v1.21.x. |
| **v1.13 Final Tagless** | Typeclass-shaped `Dataset[F[_], T]` for testing in different effect monads.  Defer to v1.21.x. |
| **v1.15 `throws[A, E]`** | Errors in worker functions propagate via `throws`; `.runX` variants surface the channel.  Module: `std/mapreduce/throws.ssc`. |
| **v1.17 MCP** | A `Dataset` operation can be invoked as an MCP tool; the result becomes a `ToolResult.text`.  Out of scope for v1.21/v1.22; user-implementable. |
| **Cluster management** | The `Cluster` handle is a thin wrapper for v1.22; the larger story (discovery, membership, leader election) lives in `specs/cluster-management.md` deferred milestone. |

## 7. Implementation phases

### v1.21 — Local map-reduce (~2 weeks)

**Phase 1 — `Dataset[T]` API + sequential runner (~3 days)**

`std/mapreduce/{types, dataset, sequential, index}.ssc`.
Lazy plan accumulation, terminal-op evaluation via simple
List backing.

**Phase 2 — Parallel runner on JVM (~3 days)**

`std/mapreduce/parallel.ssc`.  Partition input into N
chunks; spawn N virtual threads via v1.3 `Async.parallel`;
merge partial reduces.  Conformance: parallel-sum,
parallel-collect parity with sequential.

**Phase 3 — Parallel runner on INT (~2 days)**

Same shape but with NIO continuation scheduling instead of
Loom.  Same observable semantics; no real parallelism on
multi-core but concurrency for IO-bound workloads.

**Phase 4 — JS sequential fallback (~1 day)**

`runParallel()` on JS falls back to sequential until v1.3
Node parallel lands.  Warn once at startup.

**Phase 5 — Streaming integration (~2 days)**

`Dataset.fromGenerator` / `.toGenerator` (depends on v1.10).
Backpressure-aware partitioning.

**Phase 6 — Conformance + benchmarks (~2 days)**

10 conformance tests (see §9).  Microbenchmarks comparing
sequential vs parallel on JVM (target: 3-4× speedup on
8-core workloads for embarrassingly parallel `.map`).

### v1.22 — Distributed map-reduce (~3 weeks)

**Phase 1 — `Cluster` handle (~3 days)**

`std/mapreduce/cluster.ssc`.  Wraps v1.6 Phase 3
`connectNode` calls.  `Cluster.connect(nodes...)`,
`.close()`, health-check probe.

**Phase 2 — Named-handler registry (~3 days)**

`std/mapreduce/handlers.ssc`.  Each node registers
mappers / reducers by name at startup; coordinator
dispatches by name in messages.  No closure serialisation.

**Phase 3 — Coordinator + worker actors (~5 days)**

`std/mapreduce/distributed.ssc`.  Coordinator spawns one
worker actor per node; worker processes partitions
sequentially (using v1.21 local-parallel inside the
worker's stage chain).  Standard request-reply patterns
over v1.6 mailboxes.

**Phase 4 — Shuffle (`groupBy` / `reduceByKey`) (~5 days)**

Coordinator-mediated all-to-all in v1.22; worker-to-worker
direct exchange is v1.22.x.  Each worker emits
key-bucketed partial results back to the coordinator,
which redistributes by key to the second-stage workers.

**Phase 5 — Failure handling (~3 days)**

Worker `Down(reason)` → retry (configurable) or fail-whole
or allow-partial.  Surface `DistributedError` to caller.
Conformance: kill a worker mid-job, verify retry + correct
result.

**Phase 6 — Conformance + docs (~3 days)**

6 conformance tests (see §9).  Run on a 3-node cluster (2
JVM + 1 INT for cross-backend coverage).  Example
applications: word-count, log-aggregation, join.

## 8. Hard-no list (locked by design)

| Feature | Reason |
|---------|--------|
| **Closure serialisation in v1.22** | Named handlers ship in v1.22; closure serialisation is v1.22.x; needs v1.14 + bytecode shenanigans; brittle |
| **Streaming map-reduce as the default** | v1.21 ships in-memory `Dataset[T]` first; streaming via `fromGenerator` opt-in.  Real apps usually have a known dataset size |
| **Spark-like RDD lineage / recomputation** | Coordinator dispatches to workers and collects; if a worker dies the *partition* is retried, not the whole upstream chain.  Lineage adds compile-time complexity for marginal robustness win |
| **Implicit cluster discovery** | v1.22 takes an explicit `Cluster.connect(nodes…)`; auto-discovery is `specs/cluster-management.md` territory |
| **Cross-language workers** (run a Python mapper on a JVM coordinator) | Out of scope; named handlers are typed in ScalaScript |
| **Persistent intermediate results / caching** | `cache()` / `persist()` à la Spark — defer to v1.22.x once a real workload demands it |
| **SQL frontend over `Dataset`** | `Dataset.sql("SELECT ...")` — out of scope; user can layer on top via v1.20 DSL infrastructure |

## 9. Conformance plan

### v1.21 (local) — 10 tests

| Test | Exercises |
|------|-----------|
| `dataset-of.ssc` | `Dataset.of(...).collect()` round-trip |
| `dataset-map-filter.ssc` | Fluent chain; lazy evaluation; reuse |
| `dataset-reduce.ssc` | Sequential reduce of int / string concat / max |
| `dataset-groupBy.ssc` | Word count via groupBy + map |
| `dataset-sortBy.ssc` | sortBy with Order from v1.14 derives |
| `dataset-parallel-jvm.ssc` | Parallel runner produces same result as sequential |
| `dataset-parallel-int.ssc` | Same on interpreter (cooperative concurrency) |
| `dataset-from-generator.ssc` | Streaming source from v1.10 generator |
| `dataset-from-file.ssc` | Read large file line-by-line; lazy |
| `dataset-error.ssc` | Worker throws → operation throws on JVM; clean error path |

### v1.22 (distributed) — 6 tests

| Test | Exercises |
|------|-----------|
| `cluster-connect.ssc` | Open 3-node cluster, health-check, close |
| `distributed-map.ssc` | `.map(named("parseRow")).collect()` round-trip across 3 nodes |
| `distributed-shuffle.ssc` | `.groupBy(...)` with shuffle across 3 nodes |
| `distributed-failure-retry.ssc` | Kill worker mid-job; retry succeeds |
| `distributed-failure-partial.ssc` | Same, with `allowPartial = true`, get partial result |
| `distributed-heterogeneous.ssc` | 2 JVM + 1 INT worker — cross-backend round-trip |

Each test runs on all three backends where applicable;
observable output is identical across them.

## 10. Open questions

- **Streaming write to file** — `.foreach(write)` is fine for
  in-memory; large outputs need `.writeToFile(...)` that
  streams to disk.  Defer to v1.21.x when streaming demand
  surfaces.
- **`Order` instance for `sortBy`** — depends on v1.14
  `derives Order`.  If v1.14 isn't ready when v1.21 ships,
  user passes a `(T, T) => Int` comparator explicitly.
- **Map-reduce on signals** — could feed v0.8 reactive
  signals through `Dataset`.  Probably out of scope; the
  streaming primitives are for batch-shaped data.
- **Speculative execution** — Spark/MR runs duplicate tasks
  for slow workers; whichever finishes first wins.  Defer
  to v1.22.x once a workload shows tail-latency pain.
- **Local-parallel `groupBy` / `sortBy`** — v1.21 marks
  these as sequential-fallback (no shuffle).  Once v1.22
  ships the shuffle primitives, retrofit them for local.
- **Worker registration UX** — every node registers all
  named handlers at startup.  How do we keep the named-
  handler list in sync across nodes?  Probably ship as
  a separate `Workers.register("name", fn)` boilerplate
  pattern, document in v1.22 example.
- **Distributed `Dataset[T]` over Free monad** — if v1.11.5
  ships and `Free.foldMap[DatasetOp, M]` exists, can we
  expose `Dataset` as a Free DSL?  Speculative; v1.22.x.
- **MapReduce-MCP integration** — would a long-running
  `Dataset.runDistributed` be a `serveMcp` tool?  Probably
  not directly; the use case is "tell the LLM to do bulk
  processing".  User-implementable.

## 11. Boundary against cluster management

This document covers **map-reduce on a fixed cluster
handle**.  Specifically, NOT in scope:

- Auto-discovery of worker nodes
- Dynamic add / remove of workers mid-job
- Leader election among workers
- Persistent worker state
- Multi-cluster federation
- Container orchestration (K8s style)

All of those live in
[`specs/cluster-management.md`](cluster-management.md), which
is a deferred design — not committed for any v1.x version.
For v1.22 the user constructs `Cluster.connect(nodes...)`
with an explicit list of known nodes; cluster management is
out-of-band (manually maintained, or auto-discovery layered
on top later).
