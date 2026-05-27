# Apache Spark Structured Streaming backend (v1.25 § 9.5 Phase F)

> Status: F.1–F.4 landed (2026-05-27). All phases complete.

Layered on top of the closed v1.25 § 9.5 Phases A–E Spark backend.
Adds Structured Streaming support to the same `.ssc → Scala 3 + Spark
source → scala-cli run` pipeline, with no new top-level dependencies
for the built-in sources (`rate`, `console`, `file/csv/json/parquet`,
`socket`) and an auto-emitted `org.apache.spark:spark-sql-kafka-0-10`
coord when the user writes `.format("kafka")`.

## Goals

- Users can write streaming pipelines in a `scalascript` block using
  Spark's existing `spark.readStream` / `df.writeStream` API and run
  them via `ssc run --backend spark <file>` (or `ssc-spark <file>`).
- Boilerplate that every streaming program needs — calling
  `awaitTermination()` on the started query so the driver doesn't
  exit before the streaming engine processes data — is auto-emitted
  when the user code starts a stream without it.
- Kafka source/sink works out of the box: writing `.format("kafka")`
  somewhere in the module triggers an auto-emitted `//> using dep
  "org.apache.spark:spark-sql-kafka-0-10_2.13:<sparkVersion>"`
  directive in the generated source header.
- Trigger / watermark / window syntax flows through unchanged — these
  are method calls on `DataStreamWriter` / `Dataset` and need no
  codegen support beyond the imports already in `sparkImports`.
- Existing batch examples (`spark-encoder-demo.ssc`, `spark-sql-demo.ssc`,
  `spark-collections-demo.ssc`, `spark-tuple-demo.ssc`, `spark-udf-demo.ssc`,
  `word-count.ssc`, etc.) continue to work bit-for-bit identically —
  Phase F is purely additive.

## Non-goals

- **Streaming type inference / DSL.**  This phase wires the existing
  Spark API through; it does NOT invent a higher-level streaming DSL
  on top.  Users write `spark.readStream.format("rate").load()` as in
  raw Spark.  A typed `StreamingDataset[T]` shim is potential future
  work but not Phase F.
- **Custom sources / sinks** beyond the built-in five (rate, file,
  socket, console, kafka) and `foreach`/`foreachBatch`.  Users who
  need Delta, Kinesis, or other niche connectors add their own
  `//> using dep` line in front-matter `dependencies:` per the
  existing v1.25 § 9.5 mechanism.
- **Driver-level orchestration** (multiple concurrent queries,
  `spark.streams.active`, query metric scraping, graceful shutdown
  hooks beyond plain `awaitTermination()`).  One query per `.ssc`
  module is the canonical case; multi-query programs work but the
  shim only adds `awaitTermination()` for the first started query.
- **Checkpoint dir auto-provisioning.**  The shim emits a guidance
  comment for file/kafka sinks (which require `checkpointLocation`)
  but doesn't create the directory or warn at codegen time.  Spark
  itself raises a clear error if the option is missing, which is
  good enough.
- **Continuous Processing mode.**  Spark's experimental low-latency
  trigger.  Users can still set `Trigger.Continuous(...)` explicitly
  — the shim doesn't filter triggers — but it isn't a tested path
  and the streaming examples use micro-batch only.

## Architecture

### Source / sink detection table

`SparkGen.genModule` scans the concatenated user-block source (after
`extractSqlFns` strips `@SqlFn` annotations) once for streaming
markers.  Detection is regex-based, deliberately syntactic so it
runs in O(N) over the source without needing a Scala parser.

| Pattern matched in user code                         | Effect                                                            |
|------------------------------------------------------|-------------------------------------------------------------------|
| `spark.readStream` / `.writeStream`                  | Module is "streaming".  Enables awaitTermination shim (F.2).      |
| `.format("kafka")`                                   | Auto-emit `//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"` (F.4). |
| `.format("rate")` / `.format("socket")`              | No extra dep; just confirms the streaming path is active.         |
| `.format("file")` / `.csv("…")`/`.json/.parquet` on a `readStream` | File source — emit a comment reminding users to set `option("path", …)` and (for sinks) `option("checkpointLocation", …)` (F.3). |
| `.format("console")`                                 | No extra dep; debug sink, no checkpoint dir needed.               |
| `.foreach(…)` / `.foreachBatch(…)` on writeStream    | Treated as a sink; no extra dep.                                  |

The detection is per-module (a single regex pass over the joined
post-`extractSqlFns` source).  No per-block recompute, no IR rewrite.

### `awaitTermination()` shim (F.2)

If the module is streaming AND the user code does not already contain
the literal substring `awaitTermination`, the generated `@main def
runSparkJob` body appends:

```scala
// Phase F — auto-emitted streaming guard.  Without this the driver
// returns before the streaming engine has processed any data.
//
// If your code already calls `awaitTermination()` (or one of its
// timed variants) on the query, this shim is skipped; otherwise it
// pins the first active stream and waits.
spark.streams.active.headOption.foreach(_.awaitTermination())
```

right before the existing `spark.stop()` line.

The skip-when-already-present check is purely textual.  A user who
writes a string literal `"awaitTermination"` would bypass the shim,
but the trade-off — keeping the detection trivial — is intentional;
the false-positive cost is one missing shim line that the user can
add by hand.

### Kafka dep auto-emit (F.4)

If the module text contains `.format("kafka")` (in either reader or
writer position), the header gains:

```scala
//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<sparkVersion>"
```

right after the existing `spark-core` / `spark-sql` lines.  Same
version pin as the core JARs.

### Trigger / watermark / window passthrough

These are method calls in user code — `df.withWatermark("ts", "1
minute")`, `df.groupBy(window($"ts", "5 minutes"))`,
`query.trigger(Trigger.ProcessingTime("10 seconds"))`.  Spark exposes
them via `org.apache.spark.sql.streaming.{Trigger, …}` and `functions`
(already in `sparkImports`).  No codegen needed.

The streaming imports are surfaced in the emitted file header:

```scala
import org.apache.spark.sql.streaming.{Trigger, StreamingQuery, OutputMode}
```

Added alongside the existing `sparkImports`.

## Migration

Phase F is **additive** — no breaking changes:

- Existing batch examples continue to compile identically.  The
  source-scan regex for streaming markers misses non-streaming code,
  so the awaitTermination shim is not emitted and no extra deps land
  in the header.
- New streaming imports (`Trigger`, `StreamingQuery`, `OutputMode`)
  are unused but harmless in batch code — Scala 3 doesn't warn on
  unused imports from a wildcard or explicit imports in this style.
  If unused-import warnings ever become a CI gate they need a
  separate strip pass, but that's not in F's scope.
- Front-matter, CLI flags, and SPI surface unchanged.  No new keys.

## Phases

- **F.1 — Spec doc (this document).**  Lands first per AGENTS.md
  spec-driven-development rule. ✓ Landed (2026-05-27).

- **F.2 — Core streaming codegen + first example.** ✓ Landed (2026-05-27).
  - `SparkGen.containsStreaming` detects `spark.readStream` / `.writeStream`.
  - `SparkGen.containsAwaitTermination` detects user-supplied call (opt-out).
  - Auto-emitted `spark.streams.active.headOption.foreach(_.awaitTermination())`
    shim in `@main def runSparkJob` when `isStreaming && !containsAwaitTermination`.
  - Streaming imports (`Trigger`, `StreamingQuery`, `OutputMode`) always emitted.
  - `examples/spark-streaming-rate-console.ssc`: rate source → console sink
    with `Trigger.ProcessingTime("1 second")`.
  - 6 codegen tests in `SparkGenTest` (streaming imports always emitted;
    batch no shim; streaming shim emitted; user-supplied opt-out; writeStream
    alone triggers; non-Kafka no dep).

- **F.3 — File source/sink + checkpointing.** ✓ Landed (2026-05-27).
  - `SparkGen.containsFileStreamSink` detects `.format("parquet"|"csv"|"json"|"orc"|"text")`.
  - `SparkGen.containsCheckpointLocation` detects user-supplied checkpoint option.
  - When `isStreaming && containsFileStreamSink && !containsCheckpointLocation`:
    emits `// NOTE Phase F.3` comment in generated file header.
  - `examples/spark-streaming-file-parquet.ssc` — parquet directory source +
    parquet sink with explicit checkpoint dir.
  - 4 codegen tests (streaming + file format triggers hint; user checkpoint
    suppresses hint; batch no hint; console-sink no hint).

- **F.4 — Kafka source/sink.** ✓ Landed (2026-05-27).
  - `SparkGen.containsKafkaFormat` detects `.format("kafka")`.
  - Auto-emits `//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"`
    in the file header when detected.
  - `examples/spark-streaming-kafka.ssc` — Kafka topic source → Kafka topic
    sink with explicit checkpoint; smoke test gated by `RUN_SPARK_KAFKA=1`.
  - 3 codegen tests (non-Kafka no dep; kafka dep emitted; dep tracks sparkVersion).

All phases merged to `origin/main` per AGENTS.md rule 3.

## Testing strategy

- **Codegen tests (always run):** Structural assertions in
  `SparkGenTest.scala`.  Each phase adds a small block of `test
  { … }` cases verifying the emitted source contains / doesn't
  contain the expected substrings.  These run in ~1s per test and
  block CI.

- **Smoke tests (opt-in, default cancelled):**

  - `RUN_SPARK_INTEGRATION=1` gates `scala-cli compile` against
    real Spark `_2.13` JARs — same as the Phase E smoke tests.  F.2
    + F.3 examples added here.
  - `RUN_SPARK_KAFKA=1` (new) gates the Kafka smoke test.  Requires
    a running Kafka broker on `localhost:9092` with topics
    `ssc-streaming-in` and `ssc-streaming-out` pre-provisioned.
    Default skip keeps `sbt test` green on machines without Kafka.

- **Manual end-to-end:** The rate-console example is small enough
  to run interactively (`scala-cli run /tmp/…rate-console….scala`)
  in under a minute and was the canonical sanity check during F.2
  development.

## Open questions

- **`awaitTermination()` timeout?**  The shim today calls the no-arg
  variant which blocks the driver indefinitely.  A timeout would
  bound CI runtime but also break long-running production streams.
  Decision: keep no-arg, document the trade-off.  Users wanting a
  timeout call `awaitTermination(millis)` themselves and the shim
  detects their literal and skips.

- **Multiple concurrent queries?**  `spark.streams.active.headOption`
  pins the first started query.  If a user starts two streams the
  shim only awaits one.  Decision: documented limitation; multi-
  query programs should call `awaitAnyTermination()` themselves
  (still satisfies the substring detection).

- **Checkpoint dir auto-provisioning?**  Could `mkdir -p /tmp/ssc-
  ckpt-<hash>` at runtime, but cluster deployments have very
  different requirements (HDFS path, S3 bucket, etc.).  Decision:
  no — leave to the user, just remind them via a comment in the
  generated source for file/kafka sinks.

- **Should streaming code be detected at IR level instead of
  regex?**  The IR doesn't yet distinguish streaming method calls
  from batch — the surface syntax `spark.readStream` is just a
  field access.  A proper IR-level signal needs the Spark API
  symbolised into the type system, which is far beyond Phase F's
  scope.  Regex is good enough for the detection table above and
  trades a tiny false-positive surface (user writes
  `"spark.readStream"` in a string literal) for a 50-line
  implementation.

- **Spark 4.x streaming + Scala 3.7.1 interop surprises?**  None
  expected — Structured Streaming reuses the same Catalyst /
  Encoder machinery the batch path uses, and Phase E already
  proved the Scala 3.7.1 + Spark `_2.13` round-trip is sound.
  Any TASTy / reflection regressions surface in the F.2 smoke
  test before merge.
