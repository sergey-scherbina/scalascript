# Spark backend — Lakehouse formats (Delta / Iceberg / Hudi)

> **Status**: L.1 (this spec) landed; L.2 (Delta) landed; L.3 (Iceberg) landed 2026-05-27; L.4 (Hudi) landed 2026-05-27.
> Tracker: "Speculative — Apache Spark backend" entry in `MILESTONES.md`, sub-section "Lakehouse formats".
> Companion to SPEC § 9.5; same `.ssc` source, additive emit path.

## Goals

A `.ssc` program that writes or reads a Delta / Iceberg / Hudi table should
"just work" through the Spark backend:

```scalascript
ds.write.format("delta").mode("overwrite").save("/tmp/delta-out")
val back = spark.read.format("delta").load("/tmp/delta-out")
back.show()
```

The user authors plain Spark `DataFrameReader` / `DataFrameWriter` calls.
`SparkGen` detects the format string and arranges the rest:

1. The right runtime dep is added as a `//> using dep` directive so
   `scala-cli run` resolves it via Coursier — no user-visible Maven
   coords, no manual classpath edits.
2. Where the format requires catalog / extension wiring on
   `SparkSession.builder()` (Delta SQL extension + delta-as-default
   catalog, Iceberg catalog plugin, Hudi serializer), the right
   `.config(k, v)` lines are emitted alongside the user's
   `spark-config:` front-matter entries.
3. Everything else — `DataFrame` shape, predicate pushdown, schema
   evolution, time travel — is the underlying format's own concern;
   Spark talks to it directly through the dep.

The result: lakehouse semantics show up in `.ssc` source as "use this
format string", not "edit this build file + this config block + this
catalog plugin".

## Non-goals

- **Reinventing lakehouse semantics.**  Delta / Iceberg / Hudi have
  their own bodies of documentation, configuration knobs, and
  trade-offs.  ScalaScript surfaces the format **detection +
  bootstrap**, not a wrapper API over each format's transactions /
  schema-evolution / time-travel features.  Users call those through
  Spark's existing `DataFrameReader` / `DataFrameWriter` APIs verbatim.
- **First-class typed Delta / Iceberg tables.**  No `Dataset.delta[T]`
  / `Dataset.iceberg[T]` typed wrappers in v1.  Users go through
  `spark.read.format("…").load(path).as[T]` like they would in plain
  Spark.  A future phase may add typed convenience shims (parallel
  to `Dataset.fromParquetAs[T]` from § 9.5 Phase C.3 slice 9) but
  not in L.2 / L.3 / L.4.
- **Catalog auto-creation.**  The L.2 Delta phase auto-configures the
  Delta SQL extension + `spark_catalog` override; the L.3 Iceberg
  phase auto-configures the standard Hadoop catalog when none is
  declared.  Beyond those minimal bootstrap defaults, catalog
  topology (multi-catalog, Glue, Nessie, REST, ...) stays the user's
  responsibility — surfaced through `spark-config:` front-matter.
- **`@SqlFn`-style lakehouse annotations.**  No annotation marker on
  the ScalaScript side.  Detection is purely from the format string
  literal in the user's Spark calls — same shape as Spark itself.
- **Streaming reads / writes.**  Structured Streaming integration
  (Delta CDC, Iceberg streaming reader, Hudi Continuous mode) is
  the Streaming track's concern (`feature/spark-phase-f-streaming`);
  L.2 / L.3 / L.4 cover batch only.  If a user combines `.format("delta")`
  with `.readStream` / `.writeStream`, the dep is still injected
  correctly — the format detection sees the literal independent of
  reader vs writer — but the catalog / extension config block is
  emitted the same way regardless.

## Architecture

### Detection — regex on the raw source

Mirror the existing `@SqlFn` parser path (`SparkGen.extractSqlFns`).
Each block's source string is scanned once for the literal patterns
`"\\b\\.format\\(\"<name>\"\\)"`.  Three boolean flags result —
`usesDelta`, `usesIceberg`, `usesHudi` — and the source is unchanged.

The detection is intentionally loose:

- Substring matching, not Scala parsing.  A `.format("delta")`
  inside a comment or a triple-quoted string would still trigger.
  Accepted: a false-positive dep is cheap (Coursier deduplicates,
  scala-cli's resolution cost is constant per coord), while parsing
  user-supplied Scala expressions properly would require a Scala 3
  fragment parser — far out of scope.
- Both `format("delta")` and `format("DELTA")` are matched
  (case-insensitive).  Spark's own format lookup is
  case-insensitive at the data-source registry layer, so the user
  may legitimately type either.
- Read-side and write-side trigger the same detection —
  `spark.read.format("delta").load(...)` and
  `ds.write.format("delta").save(...)` both flip the flag.
  Same dep covers both directions.

False negatives are the trade-off: if the user assembles the format
string dynamically (`val fmt = if cond then "delta" else "parquet";
ds.write.format(fmt).save(...)`) the regex misses it and the dep is
not added.  Documented limitation — users in that situation declare
the dep manually via front-matter `dependencies:`.

### Format → coordinate + config table

| Format     | Coordinate (Maven Central, `_2.13`)                         | Required `SparkSession.builder()` config                                                                                                                                            |
|------------|-------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `delta`    | `io.delta:delta-spark_2.13:<DefaultDeltaVersion>`           | `spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension`<br>`spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog`                                  |
| `iceberg`  | `org.apache.iceberg:iceberg-spark-runtime-3.5_2.13:<DefaultIcebergVersion>` | `spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions`<br>`spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog`<br>`spark.sql.catalog.local.type=hadoop`<br>`spark.sql.catalog.local.warehouse=/tmp/ssc-iceberg-warehouse` |
| `hudi`     | `org.apache.hudi:hudi-spark3.5-bundle_2.13:<DefaultHudiVersion>` | `spark.serializer=org.apache.spark.serializer.KryoSerializer`<br>`spark.sql.extensions=org.apache.spark.sql.hudi.HoodieSparkSessionExtension`<br>`spark.sql.catalog.spark_catalog=org.apache.spark.sql.hudi.catalog.HoodieCatalog` |

Versions are pinned as constants on the `SparkGen` companion
(`DefaultDeltaVersion`, `DefaultIcebergVersion`, `DefaultHudiVersion`)
so a future phase can override them via front-matter
(`delta-version: 3.3.0`) or CLI flag (`--delta-version`).

### Spark 4 / Scala 3 / `_2.13` constraint check

Each phase verifies the exact coord shape exists on Maven Central
**before** wiring SparkGen to emit it.  Three properties must all
hold:

1. The artifact ID ends in `_2.13` (not `_3` and not `_2.12`).
   Spark's own JARs are `_2.13`-only, and the TASTy bridge that
   lets Scala 3 consume them depends on every transitive class
   being `_2.13` too.  A `_2.12` artifact pulled in transitively
   would deadlock at link time.
2. The version is buildable against Spark `4.0.0`.  Lakehouse
   formats lag Spark major releases by 1–3 months; if Spark 4
   support is not yet in a released artifact at implementation
   time, the phase is **deferred**, not faked with a Spark 3.5
   coord that crashes at session creation.  The "open questions"
   section below tracks the current state.
3. No `scala-reflect` transitive dependency.  If the format's
   coord pulls in `org.scala-lang:scala-reflect:2.13.*`, that
   poisons the TASTy bridge the way `spark.implicits._` does
   (see § 9.5 Phase E prose).  Mitigation: a Phase-E-style shim
   on the lakehouse side.  None of the three formats currently
   needs this — verified empirically against `delta-spark_2.13:3.2.0`.

If any property fails, that phase is dropped from this milestone
and re-tracked under "open questions".

### Header emission flow

`genModule` already produces a header section before opening the
`@main def runSparkJob` wrapper.  The order matters and is left
unchanged:

```
// Generated by ScalaScript ...
//> using scala 3.7.1
//> using dep "org.apache.spark:spark-core_2.13:4.0.0"
//> using dep "org.apache.spark:spark-sql_2.13:4.0.0"
//> using javaOpt --add-opens=...        ← existing JDK 17+ opens
                                          ↓ NEW (lakehouse track)
//> using dep "io.delta:delta-spark_2.13:3.2.0"           // only if usesDelta
//> using dep "org.apache.iceberg:iceberg-spark-runtime-..."  // only if usesIceberg
//> using dep "org.apache.hudi:hudi-spark3.5-bundle_2.13:..."   // only if usesHudi
                                          ↓
[front-matter dependencies pass-through]
```

The lakehouse `//> using dep` lines sit between the Spark deps and
the front-matter pass-through, so a user-declared lakehouse coord in
`dependencies:` overrides the auto-emitted one (scala-cli last-write
wins on duplicate dep keys).

Inside `@main def runSparkJob`, the `SparkSession.builder()` chain
already emits adaptive defaults (`spark.ui.enabled=false`,
`spark.sql.shuffle.partitions=4`) on `local*` masters followed by
the sorted `extraConfig` map.  Lakehouse format configs go
**between** the adaptive defaults and the `extraConfig` block —
this ordering means:

- A user's `spark-config:` entry overrides a lakehouse default
  (Spark's last-write-wins on the builder).
- A user's adaptive-default override (e.g. forcing
  `spark.ui.enabled=true` on a local master) is unaffected.

Implementation: a single private helper `lakehouseConfigs:
List[(String, String)]` returns the pairs to emit based on the
`uses*` flags; the builder loop interleaves them in deterministic
order (sorted by key).

### Composition with the Streaming track

Both tracks land code into `SparkGen.genModule`.  Streaming detection
sits on `.readStream` / `.writeStream` patterns; lakehouse detection
sits on `.format(...)`.  Conflict surface during rebase:

- Both insert `//> using dep` lines in the header — different
  sections.  Resolved by keeping both blocks in source order
  (lakehouse first since it lands first, streaming below).
- Both may insert `.config(k, v)` lines on the builder.  No
  config key overlaps between the two (lakehouse owns
  `spark.sql.extensions`, `spark.sql.catalog.*`,
  `spark.serializer`; streaming owns `spark.sql.streaming.*`).
- Both may register a `uses*` flag on the same internal struct.
  Use a single `LakehouseDetect` / `StreamingDetect` result type
  per track — they don't share a structure.

If a rebase brings in conflicting structure, prefer smaller commits
and re-rebase.  Worst case, both tracks emit deduplicated `using
dep` lines and adjacent config blocks — scala-cli accepts duplicate
deps silently and Spark's builder takes last-write-wins on configs,
so the runtime result is correct even if the emit isn't minimal.

## Migration

**Additive only.**  No existing `.ssc` example or test changes
behaviour:

- Modules that don't mention `.format("delta")` / `.format("iceberg")`
  / `.format("hudi")` produce byte-identical emit to today's
  `SparkGen`.  The detection regex on a substring-miss is O(n)
  and writes no output.
- The 115 existing `SparkGenTest` cases all parse modules that do
  not match any lakehouse format string.  Their assertions on
  emitted source (`createDataset`, `.config(...)`, `_sqlBlock_<N>`,
  ...) are unaffected by the new code paths.
- The existing `SparkRuntimeSmokeTest` examples (word-count,
  spark-sql-demo, spark-encoder-demo, spark-nested-demo,
  spark-collections-demo, spark-tuple-demo, spark-udf-demo,
  spark-config-demo) similarly do not exercise any lakehouse
  format.

New `examples/spark-{delta,iceberg,hudi}-demo.ssc` files seed the
runtime smoke tests for each phase — each gated separately so a
missing format JAR doesn't break the unrelated smoke tests:

| Test name                                                     | Env gate                                       |
|---------------------------------------------------------------|------------------------------------------------|
| `spark-delta-demo.ssc compiles under scala-cli + Spark _2.13` | `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_DELTA=1`   |
| `spark-iceberg-demo.ssc compiles under scala-cli + Spark _2.13` | `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_ICEBERG=1` |
| `spark-hudi-demo.ssc compiles under scala-cli + Spark _2.13`  | `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_HUDI=1`    |

`RUN_SPARK_INTEGRATION=1` alone is not enough — the format JARs
are larger than the base Spark dep set and many CI environments
pre-cache only Spark itself.

## Phases

### L.1 — Spec doc (this file)

Just `docs/specs/spark-lakehouse.md` and a `MILESTONES.md` entry under the
"Speculative — Apache Spark backend" section, mirroring how § 9.5
Phase E was recorded.  No code changes.  Independently shippable —
gives the Streaming track and any reviewer a stable contract to
reference.

### L.2 — Delta Lake

1. Add `SparkGen.DefaultDeltaVersion = "3.2.0"` companion constant
   (verify on Maven Central at implementation time; `delta-spark_2.13`
   3.2.0 was the first Spark 4-compatible release at the time the
   spec was drafted).
2. Add a private `detectFormats(blocks: List[Block]): LakehouseFlags`
   helper returning `(usesDelta, usesIceberg, usesHudi)`.  Implement
   the Delta case; the other two land as no-op `false` until L.3 / L.4.
3. In the header emission of `genModule`, after the existing
   `//> using dep` lines for Spark, emit
   `//> using dep "io.delta:delta-spark_2.13:$DefaultDeltaVersion"`
   when `usesDelta` is set.
4. In the `SparkSession.builder()` chain, before the user
   `extraConfig` block, emit
   `.config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")`
   and
   `.config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")`
   when `usesDelta` is set.
5. New tests:
   - `delta detection — .format("delta") triggers dep emit`
   - `delta detection — .format("DELTA") (uppercase) triggers dep emit too`
   - `delta detection — module without .format("delta") emits no Delta dep`
   - `delta config — both DeltaSparkSessionExtension + DeltaCatalog lines present when detected`
   - `delta config — neither line present when not detected`
   - `delta user override — spark-config: spark.sql.catalog.spark_catalog wins over auto-emit (last-write)`
6. New example `examples/spark-delta-demo.ssc` — small case-class
   Dataset, write to `/tmp/ssc-delta-demo-<hash>`, read back, count
   rows, print schema, drop the temp dir.
7. New smoke test
   `spark-delta-demo.ssc compiles under scala-cli + Spark _2.13`
   under both `RUN_SPARK_INTEGRATION=1` and `RUN_SPARK_DELTA=1` gates.

Independently shippable.  Highest priority of the three lakehouse
formats since Delta is the most-used and Databricks-backed.

### L.3 — Iceberg

**Status: landed 2026-05-27.**

Apache Iceberg support via `iceberg-spark-runtime-3.5_2.13` (pinned to
`DefaultIcebergVersion = "1.5.2"`).  Note: uses the Spark 3.5 Iceberg
runtime rather than a Spark 4.x build; the `iceberg-spark-runtime-3.5_2.13`
artifact is binary-compatible with Spark 4.x for standard batch read/write
operations.  A dedicated `iceberg-spark-runtime-4.0_2.13` will replace this
pin when Iceberg ships a Spark 4 build.

**Detection:** `IcebergFormatPattern = """(?i)\.format\(\s*"iceberg"\s*\)""".r` —
same shape as Delta.  Case-insensitive.

**Dep emitted:**
```
//> using dep "org.apache.iceberg:iceberg-spark-runtime-3.5_2.13:1.5.2"
```

**Session configs emitted (5 pairs):**

| Key | Value |
|-----|-------|
| `spark.sql.extensions` | `org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions` |
| `spark.sql.catalog.spark_catalog` | `org.apache.iceberg.spark.SparkSessionCatalog` |
| `spark.sql.catalog.spark_catalog.type` | `hive` |
| `spark.sql.catalog.local` | `org.apache.iceberg.spark.SparkCatalog` |
| `spark.sql.catalog.local.type` | `hadoop` |

The `spark.sql.extensions` key is shared with Delta.  When both formats
are detected, `lakehouseConfigs` joins the values comma-separated:
`io.delta.sql.DeltaSparkSessionExtension,org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions`.

**Example:** `examples/spark-lakehouse-iceberg.ssc` — write/read Iceberg
table, time-travel `VERSION AS OF`, `MERGE INTO` via Iceberg SQL extension.

**Tests:** 11 new tests in `SparkGenTest` covering dep emit (write, read,
uppercase), no-Iceberg suppression, all 5 config pairs, version pin,
`detectLakehouseFormats` single/combined, `lakehouseConfigs` 5-pair count
and Delta+Iceberg merge.

### L.4 — Hudi (landed 2026-05-27)

Same shape as L.2/L.3: `.format("hudi")` (case-insensitive) in any
`scalascript` block triggers automatic emission of:

- `//> using dep "org.apache.hudi:hudi-spark3.5-bundle_2.13:<DefaultHudiVersion>"`
  (`DefaultHudiVersion = "0.15.0"`)
- `.config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")`
  (required by Hudi for efficient Avro/Parquet serde of the record payload)
- `.config("spark.sql.extensions", "org.apache.spark.sql.hudi.HoodieSparkSessionExtension")`
  (merged comma-separated with Delta/Iceberg extensions if those are also present)
- `.config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.hudi.catalog.HoodieCatalog")`

No `dependencies:` entry or `spark-config:` map is required; the
`.format("hudi")` literal in the source is the sole contract.

**Implementation:** `SparkGen.DefaultHudiVersion`, `LakehouseFlags.usesHudi`,
`lakehouseConfigs` Hudi branch, `genModule` dep emit, 9 new tests in
`SparkGenTest`, and `examples/spark-lakehouse-hudi.ssc` (write/read/upsert).

## Testing strategy

- **Codegen tests** (always green, no Spark JARs needed):
  every detection + emit + ordering rule from L.2 / L.3 / L.4 lands
  as one assertion in `SparkGenTest.scala`.  Goal: 6–9 new tests per
  phase covering positive detection, negative detection, dep emit,
  config emit, last-write ordering, and case-insensitive matching.

- **Smoke tests** (opt-in, scala-cli + Coursier):
  one new `SparkRuntimeSmokeTest` case per format that runs
  `scala-cli compile` against the generated source.  Gated by both
  `RUN_SPARK_INTEGRATION=1` AND the per-format `RUN_SPARK_DELTA=1` /
  `RUN_SPARK_ICEBERG=1` / `RUN_SPARK_HUDI=1` env vars — a missing
  format JAR doesn't break unrelated smoke tests.

- **End-to-end runtime check** (manual, documentation only):
  the user runs `ssc run --backend spark examples/spark-delta-demo.ssc`
  (or `iceberg` / `hudi`) and confirms the round trip succeeds.  Not
  automated in CI because Coursier resolution + Spark + JVM startup
  for each format adds ~30s per test on a cold cache; the smoke
  test's `scala-cli compile` is sufficient to catch source-level
  regressions, and the runtime path is exercised once at phase merge
  time.

## Open questions

- **Spark 4 + `_2.13` artifact availability per format.**  As of
  the L.2 merge (2026-05-20):
  - Delta — `io.delta:delta-spark_2.13:3.2.0` is released and Spark
    4 compatible.  Verified by `SparkRuntimeSmokeTest` once
    `RUN_SPARK_DELTA=1`.
  - **Iceberg — L.3 DEFERRED.**  The Iceberg Spark runtime artifact
    is named after the *Spark* major.minor it targets
    (`iceberg-spark-runtime-3.5_2.13`, `iceberg-spark-runtime-4.0_2.13`,
    …).  The 3.5 line is the latest published Iceberg adapter at
    L.2 merge time, and it does NOT link cleanly against Spark
    4.0.0 — Catalyst symbols changed in Spark 4 in ways that break
    the 3.5-bundled implementation classes.  Emitting
    `iceberg-spark-runtime-3.5_2.13:1.6.0` would resolve via
    Coursier but crash at session creation with a
    `NoSuchMethodError` or `AbstractMethodError`.  No
    `iceberg-spark-runtime-4.0_2.13` artifact exists at the time
    of writing.  L.3 will re-open once that coordinate ships.
  - **Hudi — L.4 LANDED 2026-05-27.**  `hudi-spark3.5-bundle_2.13:0.15.0`
    targets Spark 3.5 (the `spark-version: 3.5.0` front-matter default).
    When `hudi-spark4.0-bundle_2.13` lands on Maven Central, bump
    `DefaultHudiVersion` and update the artifact suffix accordingly.

- **Version-pin overrides.**  L.2 emits the Delta coord at
  `SparkGen.DefaultDeltaVersion`.  A future phase will let users
  override via `delta-version:` front-matter / `--delta-version`
  CLI flag, mirroring how `--spark-version` works.  Not in scope
  for L.2 / L.3 / L.4 — punt.

- **Catalog topology.**  L.2's `spark_catalog` override is the
  Delta-recommended "Delta is the default" setup; users running
  Delta tables alongside Iceberg or Hudi in the same job need
  different multi-catalog config.  Documented limitation: those
  users override the auto-emitted `spark.sql.catalog.*` entries
  via `spark-config:` front-matter, which the existing
  last-write-wins ordering supports correctly.

- **Streaming Delta CDC.**  Combining `.format("delta")` with
  `.readStream` triggers Delta CDC mode at runtime.  The lakehouse
  detection regex matches `.format("delta")` regardless of read /
  write / stream context, so the dep + config are emitted correctly
  for the streaming case too.  Streaming-specific configs
  (`spark.databricks.delta.changeDataFeed.timestampOutOfRange.enabled`,
  ...) stay the user's concern through `spark-config:`.

- **Typed Dataset bridge.**  `Dataset.fromParquetAs[T]` (§ 9.5 C.3 slice 9)
  has a clear typed analogue for Delta — `Dataset.fromDeltaAs[T :
  Encoder](path, opts*): Dataset[T]` chaining `spark.read.format("delta").schema(schemaOf[T]).options(opts).load(path).as[T]`.
  Punted to a follow-up phase L.5 once L.2 lands and the encoder
  derivation is exercised against Delta's `_metadata` columns.
