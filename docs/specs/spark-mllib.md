# Spark backend — MLlib (machine learning) integration

> **Status**: M.1 (this spec) landed; M.2 (auto-emit dep) /
> M.3 (Vector encoder) / M.4 (Pipeline example) / M.5 (model
> save/load) follow.
> Tracker: "Speculative — Apache Spark backend" entry in
> `MILESTONES.md`, sub-section "MLlib track".
> Companion to SPEC § 9.5; same `.ssc` source, additive emit path.

## Goals

A `.ssc` program that uses Spark MLlib should "just work" through
the Spark backend:

```scalascript
import org.apache.spark.ml.feature.{Tokenizer, HashingTF}
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.Pipeline

case class LabeledDoc(label: Double, text: String)

val training = spark.createDataset(List(
  LabeledDoc(1.0, "spark hadoop scala"),
  LabeledDoc(0.0, "apple orange banana"),
  LabeledDoc(1.0, "spark sql streaming"),
  LabeledDoc(0.0, "kitten puppy bird")
))

val tokenizer = Tokenizer().setInputCol("text").setOutputCol("words")
val hashingTF = HashingTF().setInputCol("words").setOutputCol("features")
val lr        = LogisticRegression().setMaxIter(10).setRegParam(0.001)

val pipeline = Pipeline().setStages(Array(tokenizer, hashingTF, lr))
val model    = pipeline.fit(training)

model.transform(training).select("text", "prediction").show(false)
```

The user authors plain Spark `org.apache.spark.ml.*` calls.  `SparkGen`
detects the MLlib import (regex over the post-`extractSqlFns` source)
and arranges the rest:

1. The MLlib runtime dep is added as `//> using dep
   "org.apache.spark:spark-mllib_2.13:<sparkVersion>"` so `scala-cli
   run` resolves it via Coursier — no user-visible Maven coords, no
   manual classpath edits.
2. The Phase E Scala 3 Encoder shim gains an explicit
   `AgnosticEncoder[org.apache.spark.ml.linalg.Vector]` so case classes
   with `features: Vector` fields derive Encoder cleanly via the same
   inline `Mirror.ProductOf[T]` walk that already handles primitives,
   `Option`, collections, and nested case classes.
3. Everything else — Estimator / Transformer / Pipeline / PipelineModel
   semantics, parameter tuning, model evaluation — is MLlib's own
   concern; Spark talks to it directly through the dep.

The result: MLlib pipelines show up in `.ssc` source as "import the
classes you want", not "edit this build file + register this UDT +
fight the encoder derivation".

## Non-goals

- **Reinventing MLlib semantics.**  MLlib has its own body of
  documentation, algorithm choices, parameter tuning surface, and
  trade-offs.  ScalaScript surfaces the **dep detection + Vector
  encoder bridge**, not a wrapper API over each Estimator / Transformer.
  Users call `LogisticRegression()`, `RandomForestClassifier()`,
  `KMeans()`, etc. through MLlib's existing APIs verbatim.
- **Authoring custom Estimators / Transformers.**  Custom MLlib
  components require implementing `Estimator[M] extends Estimator[M]`
  (and its `transformSchema` / `fit` contract), which is fine in
  ordinary Scala but introduces a recursive type-parameter constraint
  (`E <: Estimator[E]`) that the ScalaScript surface doesn't yet
  cleanly express.  Rely on stock MLlib components only.  Users with
  custom components write them in a separate Scala module and import
  them from `.ssc`.
- **First-class typed pipelines.**  No `Pipeline[Input, Output]`
  typed wrapper.  MLlib's `Pipeline` works structurally over
  `Dataset[Row]` (specifically the input/output column-name strings)
  rather than the static type of `Dataset[T]`; preserving that
  surface keeps the spec close to MLlib documentation and lets users
  paste examples from the Spark docs directly into `.ssc`.
- **GPU / Rapids / distributed deep learning.**  Spark Rapids and
  similar GPU accelerators have their own runtime artifact set and
  driver-config story.  Out of scope; users opt in via front-matter
  `dependencies:` + `spark-config:`.
- **MLflow / model registry integration.**  Model persistence
  (M.5) covers Spark's own `model.save(path)` / `Model.load(path)`
  round-trip on filesystem paths.  MLflow / registry workflows
  layer on top of that via the user's own front-matter deps.
- **Streaming MLlib (`spark.ml` on streaming Datasets).**  MLlib's
  `transform` on a streaming Dataset works for stateless
  Transformers (e.g. `Tokenizer`, `HashingTF`) but not for any
  stateful fit/transform (training requires bounded input).
  Documented limitation — streaming inference on a pre-trained
  `PipelineModel` is fine; streaming training is not.

## Architecture

### Detection — regex on the raw source

Mirror the existing `@SqlFn` parser and lakehouse-format detection
paths.  After `extractSqlFns` strips `@SqlFn` annotations, the joined
user-block source is scanned once for the literal patterns
`\bimport\s+org\.apache\.spark\.ml\.` and `\bo\.a\.s\.ml\.` (the
abbreviated alias users sometimes type in tight code).  A single
boolean flag results — `usesMllib` — and the source is unchanged.

The detection is intentionally loose:

- Substring matching on the import header, not Scala parsing.  A
  `import org.apache.spark.ml.feature.Tokenizer` inside a comment
  would still match.  Accepted: a redundant Coursier resolve is
  cheap (MLlib is a fat artifact but Coursier caches aggressively),
  and properly parsing Scala fragments would require a full parser.
- Both the canonical `org.apache.spark.ml.` package prefix and the
  three-segment alias `o.a.s.ml.` are matched.  The latter shows
  up in compact import groups (`import o.a.s.ml.classification.*`)
  and in inline FQN references.
- A bare `import org.apache.spark.ml.linalg.Vector` (linalg only,
  no algorithms) STILL triggers the dep emit.  MLlib's linalg
  types live in the same artifact as the algorithm classes, so
  the dep is correct either way; there is no `spark-mllib-linalg`
  sub-artifact to special-case.
- A commented-out import (`// import org.apache.spark.ml.feature.X`)
  is matched by the simple substring check.  Documented limitation:
  users who genuinely don't want MLlib loaded delete the line
  entirely rather than commenting it out.

False negatives are the trade-off: if the user pulls in MLlib via a
front-matter `dependencies:` entry plus a non-`org.apache.spark.ml.`
re-export from another library, the regex misses it.  In that case
the user already declared the dep manually, so no harm done — they
just don't get the auto-emit convenience.

### MLlib coordinate

```
org.apache.spark:spark-mllib_2.13:<sparkVersion>
```

Same `_2.13` cross-build as `spark-core` and `spark-sql`.  Spark
publishes only `_2.13` for the 4.x line — no `_3` artifact — and
Scala 3 reads MLlib through the same TASTy bridge that handles the
rest of Spark.  Same version pin as the core JARs: a Spark 4.0.0
program uses `spark-mllib_2.13:4.0.0`, never a mismatched minor.

Verified on Maven Central before merge: `spark-mllib_2.13:4.0.0`
must exist.  If it doesn't, M.2 is blocked and surfaces as an open
question; the rest of the spec is unaffected (the encoder shim in
M.3 is dep-version-agnostic).

### Header emission flow

`genModule` already produces a header section before opening the
`@main def runSparkJob` wrapper.  The order matters and is left
unchanged:

```
// Generated by ScalaScript ...
//> using scala 3.7.1
//> using dep "org.apache.spark:spark-core_2.13:4.0.0"
//> using dep "org.apache.spark:spark-sql_2.13:4.0.0"
//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:4.0.0"  // F.4, if usesKafka
//> using dep "io.delta:delta-spark_2.13:3.2.0"                   // L.2, if usesDelta
                                          ↓ NEW (MLlib track)
//> using dep "org.apache.spark:spark-mllib_2.13:4.0.0"            // M.2, if usesMllib
                                          ↓
//> using javaOpt --add-opens=...        ← existing JDK 17+ opens
                                          ↓
[front-matter dependencies pass-through]
```

The MLlib `//> using dep` line sits with the other Spark deps,
before the JDK `--add-opens` opens and the front-matter
pass-through.  Same conflict-resolution: a user-declared
`org.apache.spark:spark-mllib_2.13:<v>` in front-matter
`dependencies:` overrides the auto-emit (scala-cli last-write
wins on duplicate dep keys).

### Vector encoder bridge (M.3)

Spark ML's linear algebra types live in `org.apache.spark.ml.linalg`:

- `Vector` — sealed trait (NOT a `Product`).
- `DenseVector(values: Array[Double])` — case class implementing `Vector`.
- `SparseVector(size: Int, indices: Array[Int], values: Array[Double])` — case class.

The Phase E `aenc_Product[T <: Product]` Mirror-based derivation
cannot handle `Vector` because `Vector` is a sealed trait, not a
Product.  Even if it could, the on-disk representation of a Spark
ML Vector is NOT the structural Product layout — Spark registers
`org.apache.spark.ml.linalg.VectorUDT` via `UDTRegistration` so the
serializer emits a single 3-column struct (`type: byte`, `size: int`,
`indices: array<int>`, `values: array<double>`) that both Dense and
Sparse share.

To support `case class Sample(label: Double, features: Vector)`
end-to-end, we need an explicit `AgnosticEncoder[Vector]` that the
Mirror walk picks up via `summonInline` when it hits the `features`
field of `Sample`.  Two implementation options:

**Option 1: `UDTEncoder` over `VectorUDT`.**  Catalyst's encoder
registry exposes `UDTEncoder` (in
`org.apache.spark.sql.catalyst.encoders.AgnosticEncoders`) which
wraps a `UserDefinedType[T]` and routes serialization through it.
Concrete shape: `UDTEncoder(udt: UserDefinedType[Vector], udtClass:
Class[_ <: UserDefinedType[?]])`.  Spark itself uses this internally
for `org.apache.spark.ml.linalg.VectorUDT` and matrix types.  Wire-
level: emits a single column whose schema is exactly the
`VectorUDT.sqlType` struct — interoperable with any Spark SQL code
that reads Vector columns.

**Option 2: `Encoders.kryo[Vector]`.**  Fallback path that
serializes via Spark's generic Kryo encoder.  Wire-level: a single
binary column.  Loses interop with anything that expects the
Vector UDT struct (ML pipelines, SQL `VectorAssembler`, etc.)
because the bytes are Kryo-serialized Scala objects, not the
VectorUDT struct.  Functional for save/load round-trip but breaks
the `model.transform(ds)` path where the Vector column needs to
be readable by MLlib operators.

**Decision: ship Option 1 (`UDTEncoder` over `VectorUDT`) with
Option 2 as a documented fallback.**  The UDT path is what every
MLlib example expects at the wire level; Kryo would technically
compile and run but a `Dataset[Sample]` produced via Kryo can't be
fed into an MLlib transformer without a manual re-serialize step
that defeats the purpose of having the encoder.

The shim addition (concrete code, as landed in M.3 — revised under
M.4 once the `private[spark]` visibility of `VectorUDT` surfaced
during the first integration smoke run):

```scala
import org.apache.spark.ml.linalg.{Vector => MLVector, SQLDataTypes => MLSQLDataTypes}
import org.apache.spark.sql.types.UserDefinedType
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.UDTEncoder

// `VectorUDT` is `private[spark]` in Spark 4.0.0 — user code can't
// `new VectorUDT()` directly.  Go through the public
// `SQLDataTypes.VectorType` singleton (typed as `DataType` but always
// a `VectorUDT` instance at runtime) and recover the concrete
// `UserDefinedType[Vector]` via cast.
private val _mlVectorUDT: UserDefinedType[MLVector] =
  MLSQLDataTypes.VectorType.asInstanceOf[UserDefinedType[MLVector]]

given aenc_MLVector: AgnosticEncoder[MLVector] =
  UDTEncoder[MLVector](
    _mlVectorUDT,
    _mlVectorUDT.getClass.asInstanceOf[Class[_ <: UserDefinedType[_]]]
  )
```

Sits in `SscSparkEncoders` alongside the existing `aenc_Option` /
`aenc_Seq` / `aenc_Map` givens.  Because `Vector` is a sealed trait
and `AgnosticEncoder` is invariant, there's no conflict with the
existing `aenc_Product[T <: Product]` given — Mirror.ProductOf only
synthesises for actual Products, and `Vector` isn't one.

If `UDTEncoder` turns out to be private (Catalyst's encoder
internals occasionally lose `private[catalyst]` visibility
modifiers between minor versions), M.3 falls back to a workalike
that constructs the same in-memory shape via reflection, or to
Option 2 (`Encoders.kryo`) with a `// FIXME M.3` comment in the
emitted shim.  Either path keeps the M.4 / M.5 examples working;
the wire-level interop note then surfaces in M.4's open questions.

`Matrix` (`org.apache.spark.ml.linalg.Matrix`) is the second-priority
encoder type — used by Estimators that surface coefficient matrices
(`LogisticRegressionModel.coefficientMatrix`, etc.).  Same pattern
as Vector: sealed trait, `DenseMatrix` / `SparseMatrix` impls,
`MatrixUDT` registered via `UDTRegistration`.  Documented but
**deferred to M.6** — most user `.ssc` programs never put a Matrix
in a Dataset column (the matrices show up on Model objects, not in
the Dataset's row schema), so the encoder for it isn't on the
critical path.

### Composition with existing tracks

MLlib detection lands in `SparkGen.genModule` next to the
Streaming (`containsStreaming`) and Lakehouse (`detectLakehouseFormats`)
detection passes.  Conflict surface during rebase:

- **Header `//> using dep` lines.**  MLlib's line sits alongside
  Kafka and Delta — different `if` guards, distinct dep coords,
  emit-order independent.  Lines may interleave in any order
  without affecting scala-cli's resolution (it deduplicates by
  coord).
- **Phase E shim.**  The MLlib track adds **one new given**
  (`aenc_Vector`) inside `SscSparkEncoders`.  No conflict with the
  existing primitive / Option / collection givens — distinct type
  parameter, no overlap with Mirror.ProductOf resolution.
- **`SparkSession.builder()` configs.**  MLlib does NOT require any
  builder configs.  The `org.apache.spark.ml.linalg.VectorUDT`
  registration happens at class-load time (a `UDTRegistration.register`
  call in MLlib's own initialiser); the builder chain stays
  untouched.  Zero overlap with the Lakehouse-track config lines.
- **`SparkRuntimeSmokeTest` gating.**  M.4's smoke test gates on
  `RUN_SPARK_MLLIB=1` on top of `RUN_SPARK_INTEGRATION=1` — same
  shape as the Delta and Kafka gates.  MLlib resolves a fairly
  large artifact set (~50MB cold), so the separate gate keeps
  unrelated smoke tests fast on CI environments without an MLlib
  cache.

## Migration

**Additive only.**  No existing `.ssc` example or test changes
behaviour:

- Modules that don't mention `org.apache.spark.ml.` produce
  byte-identical emit to today's `SparkGen`.  The detection regex
  on a substring-miss is O(n) and writes no output.
- The 141+ existing `SparkGenTest` cases all parse modules that do
  not match any MLlib import.  Their assertions on emitted source
  (`createDataset`, `.config(...)`, `_sqlBlock_<N>`, ...) are
  unaffected by the new code paths.
- The existing `SparkRuntimeSmokeTest` examples (word-count,
  spark-sql-demo, spark-encoder-demo, spark-nested-demo,
  spark-collections-demo, spark-tuple-demo, spark-udf-demo,
  spark-config-demo, streaming + lakehouse demos) similarly do
  not exercise any MLlib class.

New `examples/spark-mllib-pipeline.ssc` (M.4) and
`examples/spark-mllib-model-save-load.ssc` (M.5) seed the runtime
smoke tests for the MLlib path — each gated separately so a
missing MLlib JAR doesn't break the unrelated smoke tests:

| Test name                                                          | Env gate                                            |
|--------------------------------------------------------------------|-----------------------------------------------------|
| `spark-mllib-pipeline.ssc compiles under scala-cli + Spark _2.13`  | `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_MLLIB=1`   |
| `spark-mllib-model-save-load.ssc compiles under scala-cli + Spark _2.13` | `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_MLLIB=1` |

`RUN_SPARK_INTEGRATION=1` alone is not enough — the MLlib JAR adds
~50MB to a cold Coursier cache and many CI environments pre-cache
only the base Spark dep set.

## Phases

### M.1 — Spec doc (this file)

`docs/specs/spark-mllib.md` covering goals / non-goals / detection
mechanism / encoder bridge design / phases M.2–M.5 / testing
strategy / open questions.  Plus a `MILESTONES.md` entry under
the "Speculative — Apache Spark backend" section, mirroring how
Phase F + Lakehouse L.x were recorded.  No code changes;
independently shippable.

### M.2 — Auto-emit `spark-mllib_2.13` dep

1. Add `SparkGen.containsMllib(source: String): Boolean` — regex on
   `\bimport\s+org\.apache\.spark\.ml\.` OR `\bo\.a\.s\.ml\.`.
2. Detection runs alongside the existing Phase F streaming +
   Lakehouse detection in `genModule`.
3. When `usesMllib` is true, emit
   `//> using dep "org.apache.spark:spark-mllib_2.13:<sparkVersion>"`
   in the header right after the existing `spark-core` / `spark-sql`
   lines and before the Kafka / Delta / JDK opens.
4. New tests in `SparkGenTest.scala`:
   - `mllib detection — import o.a.s.ml.feature triggers dep emit`
   - `mllib detection — import org.apache.spark.ml.classification triggers dep emit`
   - `mllib detection — import org.apache.spark.ml.Pipeline triggers dep emit`
   - `mllib detection — import o.a.s.ml.linalg.Vector (linalg-only) triggers dep emit`
   - `mllib detection — module without MLlib import emits NO MLlib dep`
   - `mllib detection — commented-out import still triggers dep emit (documented)`
   - `containsMllib helper — direct test cases`

Independently shippable.  No example needed yet — M.2 just wires
the dep emit.  Examples land with M.4.

### M.3 — Vector encoder

1. Extend `SscSparkEncoders` (in `SparkGen.phaseEShim`) with an
   explicit `aenc_Vector` given for `org.apache.spark.ml.linalg.Vector`,
   routed through `UDTEncoder(new VectorUDT(), classOf[VectorUDT])`.
2. The shim string in `SparkGen.phaseEShim` always emits this given
   (gated by `usesMllib` would save ~5 lines of emit but introduce
   per-module conditional shim text — not worth the complexity since
   the import is harmless when MLlib isn't on the classpath... wait,
   it's not harmless — the import references classes from the
   MLlib JAR.  Gate the emit on `usesMllib` after all.)
3. New tests in `SparkGenTest.scala`:
   - `mllib encoder — usesMllib emits aenc_Vector given`
   - `mllib encoder — no MLlib usage means no aenc_Vector given (no spurious classes)`
   - `mllib encoder — aenc_Vector wires through UDTEncoder + VectorUDT`
4. New smoke test (gated): a tiny case class
   `case class Sample(label: Double, features: Vector)` lifted into
   a `Dataset[Sample]` via `spark.createDataset`.  If this compiles,
   the encoder bridge is wired end-to-end.

Independently shippable.  Lays the groundwork for M.4's Pipeline
example.

### M.4 — Pipeline example end-to-end

1. New `examples/spark-mllib-pipeline.ssc` — a small Tokenizer +
   HashingTF + LogisticRegression pipeline trained on a tiny inline
   dataset.  Mirrors the canonical MLlib quick-start example from
   the Spark documentation.
2. Gated smoke test in `SparkRuntimeSmokeTest.scala` under
   `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_MLLIB=1` —
   `requireMllib()` helper symmetric to the existing `requireDelta()`
   and `requireKafka()` gates.
3. The example exercises:
   - MLlib `Pipeline` / `Tokenizer` / `HashingTF` / `LogisticRegression`
     imports → triggers M.2 dep emit.
   - Phase E case-class encoder for `LabeledDoc(label: Double, text: String)`
     — string + double, no Vector field (the Vector lives in the
     intermediate output column after `HashingTF.transform`, not in
     the Dataset's row type at construction time).
   - `pipeline.fit(training)` → `PipelineModel` → `model.transform(test)`.
   - `df.select(...).show()` for visible output.

Independently shippable assuming M.2 and M.3 landed.

### M.5 — Model save/load

1. Extend `examples/spark-mllib-pipeline.ssc` (or split into
   `examples/spark-mllib-model-save-load.ssc` — likely the latter
   to keep each example focused) with a save/load round-trip:

   ```scala
   import org.apache.spark.ml.PipelineModel

   model.write.overwrite().save("/tmp/ssc-mllib-model")
   val loaded = PipelineModel.load("/tmp/ssc-mllib-model")
   loaded.transform(testData).show()
   ```

2. Gated smoke test under the same `RUN_SPARK_MLLIB=1` flag as M.4.

3. Document any caveats discovered during implementation:
   - `model.save(path)` is a directory write (multi-file format),
     not a single-file write.  The path must NOT already exist
     unless `.overwrite()` is set on the writer.
   - The Spark version that loaded the model must be compatible
     with the version that wrote it.  Cross-version persistence
     is MLlib's own concern; we just surface the API.
   - Custom Estimators (out of M's scope) require their own
     `MLWritable` / `MLReadable` impls to participate; stock
     MLlib classes all implement these by default.

Independently shippable.  Closes M.

## Testing strategy

- **Codegen tests (always run):** Structural assertions in
  `SparkGenTest.scala`.  Each phase adds a small block of `test
  { … }` cases verifying the emitted source contains / doesn't
  contain the expected substrings.  These run in ~1s per test
  and block CI.  Target: 6–10 new tests per phase.

- **Smoke tests (opt-in, default cancelled):**

  - `RUN_SPARK_INTEGRATION=1` gates `scala-cli compile` against
    real Spark `_2.13` JARs — same as the Phase E smoke tests.
  - `RUN_SPARK_MLLIB=1` (new) gates the MLlib smoke tests.  Stacks
    on top of `RUN_SPARK_INTEGRATION` so a developer running
    integration but without an MLlib Coursier cache skips just
    the MLlib subset.

- **Manual end-to-end:** The M.4 pipeline example is small enough
  to run interactively (`scala-cli run /tmp/ssc-smoke-<hash>.scala`)
  in under a minute and serves as the canonical sanity check during
  M.4 / M.5 development.

## Open questions

- **Maven Central artifact availability.**  Verified at M.2
  implementation time that `org.apache.spark:spark-mllib_2.13:4.0.0`
  is published.  If a future Spark version moves MLlib to a different
  artifact ID (`spark-ml_2.13`?), the constant gets bumped in
  `SparkGen`.

- **`UDTEncoder` / `VectorUDT` visibility under Scala 3 + Spark 4
  (resolved during M.4).**  `UDTEncoder` is a public case class in
  Spark 4.0.0 — verified empirically against the OSS Spark source —
  but `VectorUDT` itself is `private[spark]`, so user code can't
  `new VectorUDT()`.  The shim therefore goes through the public
  `org.apache.spark.ml.linalg.SQLDataTypes.VectorType` singleton
  (typed as `DataType` but always a `VectorUDT` instance at runtime)
  and recovers the concrete `UserDefinedType[Vector]` via cast.  Same
  wire-level interop with downstream MLlib operators as a direct
  `new VectorUDT()` construction.  Discovered when the first M.4
  integration smoke run failed with `Not found: type VectorUDT`;
  fix landed alongside M.4.

- **`Matrix` encoder.**  Deferred to a hypothetical M.6.  Most user
  programs never put a Matrix in a Dataset row, so the encoder for
  it isn't on the critical path.  Same UDTEncoder pattern, distinct
  given.  Re-opens if a real user request surfaces.

- **Streaming ML (`PipelineModel.transform` on a streaming
  Dataset).**  Works for stateless Transformers, breaks for stateful
  training.  Out of M's scope — users who want streaming inference
  on a pre-trained model write the explicit stream pipeline and use
  `model.transform(streamingDf)` directly.  No codegen support
  needed; the Phase F streaming detection already handles
  `spark.readStream` / `.writeStream` independently.

- **Param-tuning DSL (`ParamGridBuilder`, `CrossValidator`).**
  Stock MLlib classes — work through M.2 (dep emit) without any
  additional encoder work because their inputs/outputs are Datasets
  whose row types are already covered by Phase E.  Documented but
  not exercised by the M.4 example to keep the example small.

- **Generated source size with always-emit `aenc_Vector`.**  An
  alternative to M.3's gated-emit design is to ALWAYS emit the
  `aenc_Vector` given in the shim.  Pros: simpler emit (no
  per-module branch), shim stays a static string.  Cons: the import
  `import org.apache.spark.ml.linalg.{Vector, VectorUDT}` would
  fail to resolve at scala-cli compile time when MLlib isn't on the
  classpath — every non-MLlib `.ssc` program would break.  M.3
  gates the emit on `usesMllib` for this reason.

- **MLlib Pipeline persistence + Spark version drift.**  MLlib
  persists Pipeline / PipelineModel objects as a directory tree with
  versioned metadata.  Loading a model saved on Spark 3.x into Spark
  4.x sometimes works (when the underlying Estimator's params didn't
  change schema) and sometimes doesn't.  Out of M's scope — we
  surface the API, document the caveat, and let users handle
  version compatibility through their own deployment story.
