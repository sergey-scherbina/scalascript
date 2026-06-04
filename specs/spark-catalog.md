# Apache Spark Catalog DSL (v1.25 § 9.5 Phase G)

> Status: G.1 (this spec) landed; G.2–G.4 follow.
> Companion to SPEC § 9.5; same `.ssc` source, additive emit path.
> Tracker: "Speculative — Apache Spark backend" entry in `MILESTONES.md`,
> sub-section "Phase G — Catalog / Hive metastore DSL".

Layered on top of v1.25 § 9.5 Phases A–F (Spark backend) and the Lakehouse
L.1–L.2 track.  Adds first-class DSL for the Spark Catalog so `.ssc`
modules can:

1. Auto-register `Dataset[T]` values as temporary views (`@TempView`)
   without sprinkling `.createOrReplaceTempView(...)` calls through user
   code.
2. Wire Hive metastore + warehouse configuration via front-matter so
   `.ssc` jobs share a managed catalog with the rest of the org.
3. Read managed Hive / temp tables as typed Datasets through a single
   `Dataset.fromTable[T]("name")` shim.

Detection is regex-driven on the raw block source, mirroring the
existing `extractSqlFns` (Phase D) and `detectLakehouseFormats` (L.2)
patterns.

## Goals

- A scalascript block can register a Dataset as a temp view with a
  single annotation:

  ```scalascript
  @TempView("users")
  val users = Dataset.fromParquetAs[User]("/data/users.parquet")
  ```

  Subsequent `sql` blocks can then `SELECT * FROM users` without an
  explicit `.createOrReplaceTempView("users")` call.

- Front-matter declares the Hive metastore + warehouse so the
  generated `SparkSession.builder()` enables Hive support, points at
  the right Thrift URI, and uses the right warehouse path:

  ```yaml
  ---
  backend: spark
  spark-hive-metastore: thrift://metastore.example.com:9083
  spark-warehouse: /lake/warehouse
  ---
  ```

  `SparkGen` emits `.enableHiveSupport()` plus the corresponding
  `.config(...)` lines, and auto-adds the `spark-hive_2.13` runtime
  dep so the user doesn't have to remember the Maven coord.

- Typed table reads via `Dataset.fromTable[T]("name")` symmetrically
  cover Hive-catalog tables AND temp views — same shim, both shapes
  resolve through `spark.table("name").as[T]` using the Phase E
  encoder derivation.

The Spark Catalog is already usable today through `spark.catalog.*`
calls inline in scalascript blocks — Phase G is sugar that turns the
common patterns into front-matter / annotation-driven boilerplate,
not a replacement for those APIs.

## Non-goals

- **Reinventing the Spark Catalog API.**  `spark.catalog.listTables()`,
  `spark.catalog.tableExists(...)`, `spark.catalog.dropTempView(...)`,
  and friends remain reachable verbatim from scalascript blocks.  G
  surfaces the *registration* and *configuration* side; introspection
  and lifecycle stay on Spark's existing surface.

- **DDL replacement.**  `CREATE TABLE`, `ALTER TABLE`, `DROP TABLE` —
  all the catalog-mutating DDL — keeps flowing through `sql` blocks
  (Phase C.1).  `@TempView` is not a `CREATE TEMPORARY VIEW` rewrite;
  it's a wrapper around `Dataset.createOrReplaceTempView` that the
  emitter inserts after a regular `val` declaration.

- **Hive 1.x / 2.x metastore version negotiation.**  Spark 4 ships a
  hive-version shim that talks to the metastore protocol-version
  range it supports (Hive 2.3.x → 3.x → 4.x on the server side).  No
  ScalaScript-level `hive-metastore-version:` knob — users with
  custom metastore versions configure `spark.sql.hive.metastore.version`
  / `spark.sql.hive.metastore.jars` via the existing
  `spark-config:` front-matter map.  Documented in "Open questions"
  below.

- **Catalog topology beyond the single-Hive-metastore case.**
  Multi-catalog setups (Iceberg REST catalog + Hive + Delta in the
  same module) keep the existing escape hatch via `spark-config:` —
  users declare each `spark.sql.catalog.*` triplet themselves.  G
  optimises the single-Hive-metastore happy path only.

- **`@ManagedView` / `@CachedView` / persistent table sugar.**  Just
  `@TempView` in v1; persistent (`@ManagedTable("db.table")`) and
  cached views are conceivable follow-ups but require lifecycle
  semantics (re-creation, schema evolution, cache invalidation) that
  add scope without proportional payoff.  Punted.

- **Streaming-aware temp views.**  Spark allows
  `df.createOrReplaceTempView("v")` on streaming DataFrames; the
  view participates in streaming queries that read from it.  No
  special handling in `@TempView` — the annotation is structural,
  not aware of batch vs streaming.  Users combining `@TempView`
  with streaming code get the standard Spark semantics (the view
  is registered eagerly on the streaming DataFrame).

## Architecture

### Front-matter keys (G.2)

Two new keys land in the front-matter table, mirroring the existing
`spark-config:` / `spark-app-name:` precedent:

| Key | Type | Effect |
|-----|------|--------|
| `spark-hive-metastore` | String | Thrift URI of the Hive metastore service (e.g. `thrift://metastore.example.com:9083`).  When present, emits `.config("spark.hadoop.hive.metastore.uris", "<uri>")` plus `.config("spark.sql.catalogImplementation", "hive")` plus `.enableHiveSupport()` on the builder.  Auto-adds the `org.apache.spark:spark-hive_2.13:<sparkVersion>` runtime dep. |
| `spark-warehouse` | String | Warehouse directory passed via `.config("spark.sql.warehouse.dir", "<path>")`.  Local path or `hdfs://...` / `s3a://...`.  Independently enables Hive support (catalogue-implementation = hive) and adds the `spark-hive_2.13` dep so a warehouse-only setup (no Thrift URI) also works for embedded-metastore deployments. |

Both keys travel through `BackendOptions.extra` per the existing
pattern:

- CLI reads `module.manifest.raw.get("spark-hive-metastore")` /
  `.raw.get("spark-warehouse")` before Normalize strips the raw map.
- `BackendOptions.extra("sparkHiveMetastore")` /
  `.extra("sparkWarehouse")` carry the resolved strings to
  `SparkBackend.compile`, which forwards them as named arguments
  to `SparkGen.generate`.

`SparkGen` stores them as `Option[String]` constructor params.
`genModule` emits the builder lines and the dep conditionally —
modules without either key produce byte-identical emit to today's
baseline.

#### Header emission ordering

```
// Generated by ScalaScript ...
//> using scala 3.7.1
//> using dep "org.apache.spark:spark-core_2.13:4.0.0"
//> using dep "org.apache.spark:spark-sql_2.13:4.0.0"
//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:..."  // F.4, if kafka
//> using javaOpt --add-opens=...
//> using dep "io.delta:delta-spark_2.13:..."                   // L.2, if delta
//> using dep "org.apache.spark:spark-hive_2.13:4.0.0"          // G.2, if hive
                                                                  ↓
[front-matter dependencies pass-through]
```

The Hive dep sits between the lakehouse deps (L.2 / L.3 / L.4) and
the front-matter pass-through.  A user-declared
`org.apache.spark:spark-hive_2.13:<other>` in `dependencies:` wins
(scala-cli last-write on dep coord keys).

#### Builder ordering

```
SparkSession.builder()
  .appName("...")
  .master("...")
  .config("spark.ui.enabled", "false")             // adaptive local-only
  .config("spark.sql.shuffle.partitions", "4")     // adaptive local-only
  .config("spark.sql.extensions", "io.delta...")   // lakehouse (L.2)
  .config("spark.sql.catalog.spark_catalog", ...)  // lakehouse (L.2)
  .config("spark.sql.catalogImplementation", "hive")           // G.2, if hive*
  .config("spark.hadoop.hive.metastore.uris", "thrift://...")  // G.2, if metastore
  .config("spark.sql.warehouse.dir", "/lake/warehouse")        // G.2, if warehouse
  .config("<user-spark-config-key>", "<value>")    // sorted user overrides
  .enableHiveSupport()                                          // G.2, if hive*
  .getOrCreate()
```

`* if hive` = if either `spark-hive-metastore` or `spark-warehouse`
is set in front-matter, OR the user's scalascript code explicitly
calls `.enableHiveSupport()` already (textual detection).  The Hive
configs land BEFORE the user `spark-config:` map so a manual
override still wins (Spark builder is last-write).  The
`.enableHiveSupport()` call goes LAST before `.getOrCreate()` —
Spark's builder accepts it at any point but the convention is to
chain it just before resolution.

### `@TempView("name")` annotation (G.3)

Mirror the existing `@SqlFn` parser (`SparkGen.extractSqlFns`).
A single regex pass over each block source captures:

```
(?m)^(\s*)@TempView\("([^"]+)"\)\s*\r?\n(\s*)val\s+(\w+)\s*(?::\s*[^=]+)?=
```

Capture groups:
1. Outer indent of the annotation line.
2. View name (the string inside the double quotes).
3. Indent of the `val` line.
4. Bound variable name.

The emitter strips the annotation line and inserts a
`createOrReplaceTempView` call immediately after the `val`
expression, using the bound variable name:

```scalascript
@TempView("users")
val users = Dataset.fromParquetAs[User]("/data/users.parquet")
```

becomes

```scala
val users = Dataset.fromParquetAs[User]("/data/users.parquet")
users.createOrReplaceTempView("users")
```

A subsequent `sql` block can then `SELECT * FROM users` and the
DataFrame resolves through Spark's session-local view registry.

#### Type-ascription form

The regex also matches the `val name: Type = expr` shape so
explicitly-typed declarations work:

```scalascript
@TempView("orders")
val orders: Dataset[Order] = Dataset.fromTable[Order]("raw.orders").filter(_.amount > 0)
```

The type ascription is preserved verbatim — the regex's optional
`(?::\s*[^=]+)?` group captures-and-discards it without rewriting.

#### Limitations

- **Single-line `val` declarations only.**  Multi-line expressions
  on the RHS are fine (parser doesn't care), but the `val` keyword
  and the `=` must sit on the same source line as the annotation
  target.  A pre-formatted `val name\n    : Type\n    =` block
  would slip past the regex.  Same limitation as `@SqlFn`.
- **`def`, `lazy val`, `var` not supported.**  Temp views are
  registered eagerly on a concrete Dataset value; a `def` would
  re-register on every call.  A user wanting a re-registerable
  shape writes the explicit
  `xs.createOrReplaceTempView("xs")` themselves.
- **No collision detection at codegen.**  Two `@TempView("users")`
  annotations in the same module emit two
  `createOrReplaceTempView("users")` calls — the second overrides
  the first at runtime (it's `createOrReplaceTempView`, not
  `createTempView`).  Documented; users responsible for unique
  view names.
- **String literal only.**  The view name must be a literal in
  the annotation — `@TempView(viewName)` where `viewName` is a
  Scala val doesn't compile because the annotation parser is
  text-only.  This is symmetric with `@SqlFn` and is intentional
  (allows the codegen to know the view name without running the
  module).

### `Dataset.fromTable[T](name)` shim (G.4)

Add one helper to the `Dataset` companion shim emitted by `SparkGen`
in `datasetShim`:

```scala
def fromTable[T : Encoder](name: String): Dataset[T] =
  spark.table(name).as[T]
```

`spark.table("name")` resolves through Spark's session catalog —
the same lookup that `SELECT * FROM <name>` performs in a SQL
block.  The lookup hits, in order:

1. Session-local temp views (registered via `@TempView` or
   explicit `.createOrReplaceTempView`).
2. Global temp views (registered via `.createOrReplaceGlobalTempView`).
3. The configured catalog (Hive metastore if `spark-hive-metastore:`
   or `spark-warehouse:` is set, otherwise the in-memory default).

The `.as[T]` chain uses the Phase E encoder derivation that's already
in scope inside `@main def runSparkJob` — case classes, primitives,
`Option`, collections, nested products all work without further
plumbing.

Composes with both G.2 (Hive-managed tables) and G.3 (temp views)
symmetrically — the caller doesn't care whether the table sits in
the Hive metastore or was registered five lines earlier as a temp
view.

### Composition with existing phases

- **Phase C.1 / C.2 (sql blocks)** — `sql` blocks already resolve
  views and Hive tables through `spark.sql(...)` against the
  session catalog.  Phase G adds the *registration* and
  *configuration* paths that make those names appear in the
  catalog in the first place.  Existing `sql`-block tests are
  unaffected (no DDL rewrite).

- **Phase E (encoders)** — `Dataset.fromTable[T]` uses the same
  `Encoder[T]` machinery as `Dataset.fromParquetAs[T]` /
  `Dataset.fromJsonAs[T]` / `Dataset.fromCsvAs[T]`.  Identical
  resolution path — primitives + case classes + Option + nested
  - collections all work.

- **Phase F (streaming)** — streaming DataFrames can be registered
  as temp views; the `@TempView` annotation doesn't care.  Spark's
  streaming engine handles the catalog interaction.  No new
  detection / emit for streaming + catalog.

- **Lakehouse L.2 (Delta)** — Delta's `DeltaCatalog` is registered
  as the `spark_catalog` override.  With G.2's `enableHiveSupport()`
  also turned on, the catalog implementation flips to "hive" and
  Delta tables land in the Hive metastore as managed Delta tables
  (the standard Databricks integration shape).  No new emit
  needed — both code paths set their own keys, and Spark's
  builder is last-write so the user `spark-config:` map can
  resolve any cross-format conflict.

## Migration

**Additive only.**  No existing behaviour changes:

- Modules without `spark-hive-metastore:` / `spark-warehouse:`
  front-matter AND without any `@TempView` annotation produce
  byte-identical emit to today's `SparkGen`.  All ~141 existing
  `SparkGenTest` cases remain unmodified.
- The `spark-hive_2.13` dep is gated on the presence of those
  front-matter keys (or on a textual `.enableHiveSupport()` in
  user code) — modules that don't touch Hive don't pull the JAR.
- `Dataset.fromTable[T]` is one additional method on the existing
  `Dataset` companion shim.  No name collision with existing
  helpers (`of`, `fromList`, `fromFile`, `fromPath`, `fromCsv`,
  `fromJson`, `fromParquet`, `schemaOf`, `fromCsvAs`, `fromJsonAs`,
  `fromParquetAs`).

The existing `SparkRuntimeSmokeTest` examples (word-count,
spark-sql-demo, spark-encoder-demo, spark-nested-demo,
spark-collections-demo, spark-tuple-demo, spark-udf-demo,
spark-config-demo, spark-delta-demo, streaming examples) do not
exercise Hive / temp-view paths — their assertions are unaffected.

A new `examples/spark-hive-demo.ssc` lands with G.4 to exercise the
combined surface end-to-end:

| Test name | Env gate |
|-----------|----------|
| `spark-hive-demo.ssc compiles under scala-cli + Spark _2.13` | `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_HIVE=1` |

`RUN_SPARK_HIVE=1` joins the per-format env-gate pattern from L.2
(`RUN_SPARK_DELTA=1`) — the `spark-hive_2.13` JAR is large
enough that CI environments without a pre-warmed Coursier cache
should opt in.

## Phases

### G.1 — Spec doc (this file)

Just `specs/spark-catalog.md` and a `MILESTONES.md` entry under the
"Speculative — Apache Spark backend" section, mirroring how L.1 was
recorded.  No code changes.  Independently shippable.

### G.2 — Front-matter for metastore + warehouse

1. Add two new front-matter keys to the `Module.manifest.raw` read
   path in `Main.runCommand`:
   - `spark-hive-metastore: <thrift-uri>` → threaded via
     `BackendOptions.extra(SparkBackend.SparkHiveMetastoreOption)`.
   - `spark-warehouse: <path>` → threaded via
     `BackendOptions.extra(SparkBackend.SparkWarehouseOption)`.
2. Extend `SparkBackend.compile` to read both keys and forward
   them as `hiveMetastore: Option[String]` / `warehouse:
   Option[String]` parameters to `SparkGen.generate`.
3. In `SparkGen.genModule`:
   - Compute `needsHive: Boolean = hiveMetastore.isDefined ||
     warehouse.isDefined || joinedUserSrc.contains("enableHiveSupport")`.
   - When `needsHive`, emit
     `//> using dep "org.apache.spark:spark-hive_2.13:$sparkVersion"`
     in the header, after lakehouse deps and before
     front-matter pass-through.
   - When `needsHive`, emit
     `.config("spark.sql.catalogImplementation", "hive")` on the
     builder, between lakehouse configs and the user
     `spark-config:` map.
   - When `hiveMetastore.isDefined`, emit
     `.config("spark.hadoop.hive.metastore.uris", "<uri>")`.
   - When `warehouse.isDefined`, emit
     `.config("spark.sql.warehouse.dir", "<path>")`.
   - When `needsHive`, emit `.enableHiveSupport()` immediately
     before `.getOrCreate()`.
4. SPEC.md front-matter table: add the two keys with one-line
   descriptions, same shape as `spark-config:` / `spark-app-name:`.
5. Tests (in `SparkGenTest.scala`):
   - `hive — no spark-hive-metastore / spark-warehouse / .enableHiveSupport() emits no hive dep`
   - `hive — spark-hive-metastore front-matter triggers spark-hive dep + .enableHiveSupport()`
   - `hive — spark-warehouse front-matter triggers spark-hive dep + .enableHiveSupport()`
   - `hive — both front-matter keys present emit both .config lines`
   - `hive — user .enableHiveSupport() in scalascript triggers spark-hive dep`
   - `hive — config ordering: hive configs after lakehouse, before user spark-config`
   - `hive — spark-hive dep version follows sparkVersion override`
   - `hive — non-hive module emits NO catalogImplementation / warehouse / metastore / enableHiveSupport`

Independently shippable.

### G.3 — `@TempView("name")` annotation

1. Add `SparkGen.extractTempViews(source: String): (String, List[TempViewSig])`
   helper.  `TempViewSig(viewName: String, varName: String)`.
   Regex pass strips the annotation line; returns the cleaned
   source plus the captured signatures in document order.
2. In `SparkGen.genModule`, run `extractTempViews` AFTER
   `extractSqlFns` on each block's cleaned source.  Compose
   the emitted source: cleaned-source-after-both-passes plus,
   appended after the existing UDF-registration block,
   `<varName>.createOrReplaceTempView("<viewName>")` lines.
3. Tests (in `SparkGenTest.scala`):
   - `@TempView — basic val → createOrReplaceTempView emitted after declaration`
   - `@TempView — typed val name: Dataset[T] = expr form is recognised`
   - `@TempView — annotation line is stripped (not emitted as @-prefixed Scala)`
   - `@TempView — multiple annotations in one block emit multiple registrations`
   - `@TempView — composes with @SqlFn in the same block (both passes run)`
   - `@TempView — view name with hyphens / underscores survives literal quoting`
   - `@TempView — module without @TempView produces byte-identical emit (regression guard)`
   - `extractTempViews helper round-trips and returns correct signatures`

Independently shippable.

### G.4 — `Dataset.fromTable[T]` shim + Hive example

1. Add one method to the `Dataset` companion in `datasetShim`:
   ```scala
   def fromTable[T : Encoder](name: String): Dataset[T] =
     spark.table(name).as[T]
   ```
2. Example `examples/spark-hive-demo.ssc` — combines G.2 + G.3 +
   G.4:
   - Front-matter sets `spark-warehouse: /tmp/ssc-hive-warehouse-<hash>`
     (local embedded metastore so the example runs without an
     external Hive service).
   - One `@TempView("orders")` annotation on a small Dataset.
   - One `Dataset.fromTable[Order]("orders")` read on the
     registered view.
   - One `sql` block selecting from `orders` to verify the
     view participates in SQL.
   - Drops the warehouse on exit.
3. `SparkRuntimeSmokeTest.spark-hive-demo` gated on
   `RUN_SPARK_INTEGRATION=1 && RUN_SPARK_HIVE=1`.  Uses the
   embedded-derby metastore so no external service is needed.
4. Test (in `SparkGenTest.scala`):
   - `fromTable shim emitted in datasetShim`
   - `fromTable usage compiles in emitted source (structural)`

Independently shippable.  After G.4 lands the milestone is closed.

### G.5 (optional, low priority) — Catalog introspection helpers

`Dataset.listTables(): DataFrame` and
`Dataset.describeTable(name: String): DataFrame` wrap
`spark.catalog.listTables()` and `spark.sql(s"DESCRIBE TABLE $name")`.
Skip if any conflict surfaces with the other shims; punt to a
follow-up otherwise.  Phase G is considered closed after G.4.

## Testing strategy

- **Codegen tests** (always green, no Spark JARs needed):
  every new front-matter key + annotation rule lands as one
  assertion in `SparkGenTest.scala`.  Goal: 8–10 tests for G.2,
  8 for G.3, 2 for G.4 — covers positive detection, negative
  detection, dep emit, config emit, ordering, helper round-trip,
  and composition with the existing `@SqlFn` / lakehouse paths.

- **Smoke tests** (opt-in, scala-cli + Coursier):
  one new `SparkRuntimeSmokeTest` case for `spark-hive-demo.ssc`
  gated by `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_HIVE=1`.  The
  embedded-derby metastore that Spark ships in `spark-hive_2.13`
  means the smoke test runs without a Hive server — just
  `scala-cli compile` against the generated source.

- **Manual end-to-end** (documentation only): the user runs
  `ssc run --backend spark examples/spark-hive-demo.ssc` to
  confirm temp-view registration, Hive-managed table read, and
  warehouse-dir routing all work in one job.  Not automated in
  CI because Coursier + Spark + Hive JAR startup adds ~30s per
  test on a cold cache; the smoke compile is enough to catch
  source-level regressions.

## Open questions

- **Hive metastore version pin.**  Spark 4 ships a hive-version
  shim that handles Hive 2.3.x / 3.x / 4.x metastores
  transparently — no `hive-metastore-version:` knob is needed in
  v1.  Users with custom metastore versions override
  `spark.sql.hive.metastore.version` /
  `spark.sql.hive.metastore.jars` via `spark-config:`.
  Documented; revisit if a real user hits a version mismatch.

- **Default warehouse path.**  When `spark-warehouse:` is absent
  but `spark-hive-metastore:` is set, no warehouse line is
  emitted — Spark falls back to its built-in default
  (`spark-warehouse` in the working directory).  Users who want
  a per-job warehouse declare it explicitly.

- **Kerberos / SASL auth.**  Production Hive metastores often
  require Kerberos.  No auto-emit in G — users set
  `spark.hadoop.hive.metastore.sasl.enabled=true` /
  `spark.hadoop.hive.metastore.kerberos.principal=...` /
  `spark.security.credentials.hive.enabled=true` via
  `spark-config:`.  Documented limitation; if a recurring
  pattern emerges, a `spark-hive-auth: kerberos` shortcut could
  ship in a follow-up.

- **`@ManagedTable` / `@CachedView`.**  Temp views cover ~80% of
  the registration-sugar wins.  Managed Hive tables and cached
  views need lifecycle semantics (re-creation, schema evolution,
  cache invalidation) that add design surface without
  proportional payoff.  Punted to a future phase if user demand
  surfaces.

- **`Dataset.fromTable[T]` vs `spark.table("name").as[T]`.**
  The shim is a one-line wrapper; users who prefer the explicit
  Spark form keep it.  No deprecation warning either way — both
  shapes resolve through the same Catalyst plan.

- **Multi-catalog topology.**  Single-Hive-metastore is the
  happy path G.2 optimises for.  Iceberg REST catalog + Hive +
  Delta in one module requires multiple `spark.sql.catalog.*`
  triplets — users declare them via `spark-config:`, and the
  existing last-write ordering supports that case correctly.
