package scalascript.codegen

import scalascript.ast.*
import scala.collection.mutable

/** Generates a Scala 3 + Apache Spark program from a ScalaScript module.
 *
 *  Model: follows JvmGen's structure.  Each ScalaScript `.ssc` code block
 *  is emitted verbatim (the surface syntax is already Scala 3-compatible),
 *  but Dataset[T] operations are backed by Spark's Dataset API rather than
 *  the JVM-local `_Dataset[T]` pipeline class.
 *
 *  The generated program wraps user code in a `@main def runSparkJob()` that:
 *  1. Creates a local `SparkSession` (Phase 1 — always `master("local[*]")`).
 *  2. Imports `spark.implicits._` so primitive Encoders resolve.
 *  3. Runs the user code.
 *  4. Calls `spark.stop()`.
 *
 *  Key semantic differences from the JVM backend:
 *  - `Dataset.of(a, b, c)`    → `spark.createDataset(List(a, b, c))`
 *  - `Dataset.fromList(xs)`   → `spark.createDataset(xs)`
 *  - `Dataset.fromFile(path)` → `spark.read.textFile(path)`
 *  - `.take(n)` on Dataset    → `.limit(n).collect().toList` (terminal)
 *  - `.toList` / `.collect()` → `.collect().toList`
 *  - `.top(n)`                → `.orderBy(desc(...)).limit(n)`
 *  - `.count`                 → `.count()`
 *
 *  All other lazy transformations (map / filter / flatMap / distinct / union)
 *  pass through as-is — Spark Dataset has the same method names.
 *
 *  The `sparkVersion` parameter controls which Spark version is referenced in
 *  the generated header comment and the `scala-cli --dep` instructions.
 *  Priority: CLI `--spark-version` flag > front-matter `spark-version:` >
 *  `SparkGen.DefaultVersion`.
 *
 *  The `sparkMaster` parameter controls the URL passed to
 *  `SparkSession.builder().master(...)`.  Same three-level priority as
 *  `sparkVersion`: CLI `--spark-master` flag > front-matter
 *  `spark-master:` > `SparkGen.DefaultMaster` (= `local[*]`).  Phase B of
 *  the v1.25 § 9.5 plan: same source compiles to a local Spark session
 *  for development (`local[*]`), a worker-bounded local session for
 *  micro-benchmarking (`local[4]`), or a cluster session against an
 *  actual master URL (`spark://host:7077`, `yarn`, `k8s://...`).  Note
 *  that `--spark-master spark://...` / `yarn` / `k8s://...` still relies
 *  on `scala-cli run` to ship the driver — packaging a fat JAR and
 *  invoking `spark-submit` is a Phase B follow-up.
 */
object SparkGen:

  /** Default Spark version emitted when neither front-matter nor CLI flag
   *  specifies one.  Targets Spark 4.x (Scala 2.13+ / Scala 3 support). */
  val DefaultVersion: String = "4.0.0"

  /** Default Spark master URL — local mode, all available cores.  Phase B
   *  of v1.25 § 9.5 lets the CLI override this via `--spark-master <url>`
   *  or the front-matter `spark-master:` key. */
  val DefaultMaster: String = "local[*]"

  /** Default Delta Lake version emitted as the `delta-spark_2.13` dep
   *  coordinate when `.format("delta")` is detected anywhere in the
   *  module source.  Lakehouse track L.2 (see
   *  `docs/spark-lakehouse.md`).
   *
   *  Pinned to **3.2.0** — the first Delta release with confirmed
   *  Spark 4.x support against the `_2.13` cross-build.  Earlier
   *  Delta lines target Spark 3.4/3.5 and fail at session creation
   *  under Spark 4 (binary-incompatible Catalyst symbols).  Verify
   *  on Maven Central before bumping. */
  val DefaultDeltaVersion: String = "3.2.0"

  /** Maximum number of `${expr}` binds in a single `sql` block that the
   *  emitter routes through `java.util.Map.of(...)`.  Above this threshold
   *  (Phase C.3, v1.25 § 9.5) it switches to `java.util.Map.ofEntries(...)`
   *  because the JDK `Map.of` overloads only go up to 10 key/value pairs. */
  val MapOfMaxPairs: Int = 10

  /** Default application name emitted on `SparkSession.builder().appName(...)`.
   *  Phase C.3 slice 4 of v1.25 § 9.5 lets a module override this via the
   *  `spark-app-name:` front-matter key — the value shows up verbatim in
   *  the Spark UI, history server, and driver / executor log lines, so a
   *  human-readable per-job name is worth surfacing. */
  val DefaultAppName: String = "scalascript-job"

  /** Default Scala version emitted in the `//> using scala <v>` directive
   *  and the `--scala <v>` argument to `scala-cli` / `spark-submit`.
   *
   *  Pinned to **Scala 3.7.1** because Scala 3.8.x has a regression
   *  in its read-Scala-2.13-TASTy path that breaks Spark's runtime
   *  reflection (`scala.reflect.internal.FatalError: class Array
   *  does not have a member apply`).  3.7.1 is the latest series
   *  that round-trips cleanly with Spark `_2.13` 4.0.0.  See SPEC § 9.5
   *  "Scala 3 / Spark 2.13 interop" for the underlying issue. */
  val DefaultScalaVersion: String = "3.7.1"

  /** JVM `--add-opens` / `--add-exports` flags Spark needs on JDK 17+
   *  to access internal classes (`sun.nio.ch.DirectBuffer`,
   *  `java.lang.reflect`, etc.).  Without these every Spark 3.5+ run
   *  dies at SparkContext init.  Emitted as `//> using javaOpt`
   *  scala-cli directives so the generated source is runnable
   *  out-of-the-box with `scala-cli run`. */
  val DefaultJavaOpts: List[String] = List(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
  )

  def generate(
      module:        Module,
      baseDir:       Option[os.Path]     = None,
      sparkVersion:  String              = DefaultVersion,
      sparkMaster:   String              = DefaultMaster,
      extraConfig:   Map[String, String] = Map.empty,
      appName:       String              = DefaultAppName,
      scalaVersion:  String              = DefaultScalaVersion,
      hiveMetastore: Option[String]      = None,
      warehouse:     Option[String]      = None
  ): String =
    SparkGen(
      baseDir, sparkVersion, sparkMaster, extraConfig, appName, scalaVersion,
      hiveMetastore, warehouse
    ).genModule(module)

  /** A collected ScalaScript code block ready for emission. */
  private[codegen] case class Block(src: String)

  /** A `@SqlFn`-marked function's signature extracted from the
   *  source — enough information for `genModule` to emit a Java
   *  `UDFN`-shaped `spark.udf.register` call that bypasses Spark's
   *  TypeTag-bound register overload.
   *
   *  See `extractSqlFns` for the regex contract that populates this. */
  case class SqlFnSig(
      name:       String,
      paramTypes: List[String],
      returnType: String
  )

  /** Mapping from Scala type name (as it appears in a `def`'s return /
   *  param ascription) to the canonical Spark `DataType` constant.
   *  Used by Phase D's UDF auto-emit to provide the explicit
   *  `returnType` argument that Spark's Java `UDFN.register` overload
   *  requires (Phase E revival, v1.25 § 9.5).
   *
   *  Unknown types fall back to `StringType` at codegen with a `// TODO`
   *  comment — Spark will likely fail at runtime, but the compile is
   *  preserved so the rest of the module isn't blocked. */
  val SqlFnDataType: Map[String, String] = Map(
    "String"  -> "org.apache.spark.sql.types.StringType",
    "Boolean" -> "org.apache.spark.sql.types.BooleanType",
    "Byte"    -> "org.apache.spark.sql.types.ByteType",
    "Short"   -> "org.apache.spark.sql.types.ShortType",
    "Int"     -> "org.apache.spark.sql.types.IntegerType",
    "Long"    -> "org.apache.spark.sql.types.LongType",
    "Float"   -> "org.apache.spark.sql.types.FloatType",
    "Double"  -> "org.apache.spark.sql.types.DoubleType"
  )

  /** A `@TempView`-marked `val` declaration captured from source.
   *
   *  Phase G.3 (v1.25 § 9.5) — see `docs/spark-catalog.md`.  The
   *  caller emits `<varName>.createOrReplaceTempView("<viewName>")`
   *  after the cleaned `val` so subsequent `sql` blocks can
   *  `SELECT * FROM <viewName>` without manual catalog wiring.
   *
   *  See `extractTempViews` for the regex contract that populates this. */
  case class TempViewSig(
      viewName: String,
      varName:  String
  )

  /** Scan a `scalascript` block source for `@TempView("name")`-marked
   *  `val` declarations and extract their signatures.  Returns the
   *  source with the annotation lines stripped, plus a list of
   *  `TempViewSig(viewName, varName)` in document order.
   *
   *  Caller (`genModule`) emits a
   *  `<varName>.createOrReplaceTempView("<viewName>")` line per
   *  signature so the bound Dataset becomes queryable from any
   *  subsequent `sql` block in the same module.
   *
   *  Pattern: `@TempView("name")` on its own line (any indent),
   *  then a `val NAME = expr` or `val NAME: Type = expr` declaration
   *  on the next non-blank line.  The optional type ascription is
   *  preserved verbatim — the regex captures-and-discards it
   *  through the optional `(?::\s*[^=]+)?` group.
   *
   *  Limitations:
   *    - Single-line `val name = expr` declarations only.  Multi-line
   *      RHS expressions are fine (no inspection past `=`), but the
   *      `val` keyword and the `=` token must sit on the same source
   *      line.  Same limitation shape as `@SqlFn`.
   *    - `def`, `lazy val`, `var` not supported — temp views are
   *      registered eagerly on a concrete Dataset value.  Users
   *      wanting a re-registerable shape write the explicit
   *      `xs.createOrReplaceTempView("xs")` themselves.
   *    - The view name must be a literal string in the annotation.
   *      `@TempView(viewName)` where `viewName` is a Scala val is
   *      not recognised (the annotation parser is text-only).
   *
   *  False-positive risk: `@TempView("…")` appearing inside a string
   *  literal followed by a `val` would still match.  Accepted —
   *  collisions with user prose are vanishingly unlikely. */
  def extractTempViews(source: String): (String, List[TempViewSig]) =
    // Capture: 1=outer indent, 2=view name, 3=val indent, 4=var name.
    // Optional `: TypeAscription` between `name` and `=` is matched
    // but not captured — the whole `val` line is preserved verbatim
    // by the replacement (only the @TempView line is dropped).
    // Pull `\s*` out of the optional ascription group so it always
    // consumes the space before `=` regardless of whether the
    // `: TypeAscription` part matches.  Otherwise the greedy `[^=]+`
    // inside the optional group eats the trailing space and the
    // outer `=` anchor fails (Java regex doesn't backtrack into a
    // non-matching optional group).
    val pat = """(?m)^(\s*)@TempView\(\s*"([^"]+)"\s*\)\s*\r?\n(\s*)val\s+(\w+)\s*(?::\s*[^=]+)?=""".r
    val sigs = scala.collection.mutable.ListBuffer.empty[TempViewSig]
    val cleaned = pat.replaceAllIn(source, m =>
      val viewName = m.group(2)
      val varName  = m.group(4)
      sigs += TempViewSig(viewName, varName)
      // Drop the `@TempView(...)` annotation line; keep the val
      // declaration line verbatim.  We rebuild the val line from
      // the captured indent + var name + (optionally) type
      // ascription + `=`.  Anything after `=` (the RHS expression)
      // belongs to the rest of the line/block and is OUTSIDE the
      // regex match, so it stays untouched in the output.
      val tail = m.matched.substring(
        m.matched.indexOf("val ")
      )
      java.util.regex.Matcher.quoteReplacement(tail)
    )
    (cleaned, sigs.toList)

  /** Scan a `scalascript` block source for `@SqlFn`-marked `def`
   *  declarations and extract their signatures.  Returns the source
   *  with the annotation lines stripped, plus a list of
   *  `SqlFnSig(name, paramTypes, returnType)` in document order.
   *
   *  Caller (`genModule`) emits a `spark.udf.register("name",
   *  new org.apache.spark.sql.api.java.UDF<N>[...] { def call(...) = name(...) },
   *  <returnDataType>)` line per signature — Phase D's typed
   *  `register[RT : TypeTag, ...]` overload doesn't compile under
   *  Scala 3 + Spark `_2.13`, but the Java UDF interface form works
   *  because it takes the `DataType` explicitly and needs no TypeTag.
   *
   *  Pattern: `@SqlFn` on its own line (any indent), then a
   *  `def NAME(p1: T1, p2: T2, ...): RT = ...` declaration on the
   *  next non-blank line.  Anything after `RT =` is preserved
   *  verbatim (the body lands in the emitted source unchanged).
   *
   *  Limitations:
   *    - `val NAME = (a: T) => ...` form is NOT recognised (the param
   *      and return types aren't extractable from a function literal
   *      without proper parsing).  Authors who need a `val` UDF
   *      should rewrite as `def`.
   *    - The return type must be a simple identifier — generic types
   *      (`Option[String]`, `List[Int]`, etc.) fall outside the
   *      `SqlFnDataType` map and degrade to StringType + a `// TODO`.
   *
   *  False-positive risk: `@SqlFn` appearing inside a string literal
   *  followed by a def would still match.  Accepted — collisions with
   *  user prose are vanishingly unlikely. */
  def extractSqlFns(source: String): (String, List[SqlFnSig]) =
    // Capture: 1=outer indent, 2=def indent, 3=name, 4=raw params, 5=return type.
    val pat = """(?m)^(\s*)@SqlFn\s*\r?\n(\s*)def\s+(\w+)\s*\(([^)]*)\)\s*:\s*(\S+)\s*=""".r
    val sigs = scala.collection.mutable.ListBuffer.empty[SqlFnSig]
    val cleaned = pat.replaceAllIn(source, m =>
      val name        = m.group(3)
      val paramTypes  = parseParamTypes(m.group(4))
      val returnType  = m.group(5)
      sigs += SqlFnSig(name, paramTypes, returnType)
      // Drop the `@SqlFn` line; keep the def line verbatim.
      java.util.regex.Matcher.quoteReplacement(
        s"${m.group(2)}def ${name}(${m.group(4)}): ${returnType} ="
      )
    )
    (cleaned, sigs.toList)

  // ── v2.1.3 — DStream detection ───────────────────────────────────────────
  //
  // Detect `Pipeline.create` / `Backend.Spark` / `InMemory.source` in the
  // joined code-block source.  When true, `genModule` emits the DStream
  // Spark shim inside `@main` (after `datasetShim`) so user DStream code
  // compiles and runs correctly on the Spark target without any changes.

  /** Does the joined source contain a DStream pipeline entry point? */
  def containsDStream(source: String): Boolean =
    source.contains("Pipeline.create")    ||
    source.contains("InMemory.source")    ||
    source.contains("Backend.Spark")      ||
    source.contains("Window.fixed")       ||
    source.contains("Window.sliding")     ||
    source.contains("Window.session")     ||
    source.contains("Window.global")      ||
    source.contains("WatermarkStrategy.") ||
    source.contains("Trigger.")           ||
    source.contains("statefulMap")        ||
    source.contains("statefulFlatMap")    ||
    source.contains("broadcastState")     ||
    source.contains("KeyedStateSpec")     ||
    source.contains("withSideInput")      ||
    source.contains("sideOutput")         ||
    source.contains("SideInput.")         ||
    source.contains("OutputTag")          ||
    source.contains(".join(")             ||
    source.contains("leftOuterJoin")      ||
    source.contains("rightOuterJoin")     ||
    source.contains(".flatten")           ||
    containsConnector(source)

  /** Does the joined source contain a production connector call? */
  def containsConnector(source: String): Boolean =
    source.contains("Kafka.source")   || source.contains("Kafka.sink")   ||
    source.contains("Kafka.changelog")||
    source.contains("Files.source")   || source.contains("Files.sink")   ||
    source.contains("Jdbc.source")    || source.contains("Jdbc.sink")    ||
    source.contains("Pulsar.source")  || source.contains("Pulsar.sink")  ||
    source.contains("Kinesis.source") || source.contains("Kinesis.sink")

  // ── Phase F — Structured Streaming detection helpers ─────────────────────
  //
  // Single regex pass over the post-`extractSqlFns` user-block source
  // identifies streaming markers and triggers downstream emission
  // changes:
  //
  //   - `spark.readStream` / `.writeStream` literal substring →
  //     module is streaming → enables `awaitTermination()` shim.
  //   - `.format("kafka")` literal substring → auto-emit
  //     `//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"`
  //     in the file header (Phase F.4).
  //
  // Detection is syntactic — no Scala parser involved.  False positives
  // (e.g. the literal `"spark.readStream"` inside a string) are
  // possible but vanishingly rare in practice; users hitting them can
  // disable the shim by writing `awaitTermination()` themselves and
  // (for the Kafka case) by editing the YAML `dependencies:` map.

  /** Does the joined module source contain a streaming entry point? */
  def containsStreaming(source: String): Boolean =
    source.contains("spark.readStream") || source.contains(".writeStream")

  /** Does the joined module source already call `awaitTermination` on
   *  a query?  When true the auto-emit shim is suppressed so user
   *  control wins. */
  def containsAwaitTermination(source: String): Boolean =
    source.contains("awaitTermination")

  /** Does the joined module source use Kafka as a source or sink?
   *  Matches the literal `.format("kafka")` to keep the detection
   *  trivial.  Case-insensitive match isn't worth the complexity —
   *  Spark itself accepts only the lowercase form. */
  def containsKafkaFormat(source: String): Boolean =
    source.contains(""".format("kafka")""")

  /** Pre-compiled patterns for file-based stream sinks (Phase F.3).
   *  Match the canonical write-side file sink shapes:
   *
   *    .format("parquet") / .format("csv") / .format("json") /
   *    .format("orc") / .format("text")
   *
   *  AND the bare typed `.parquet(path)` / `.csv(path)` / `.json(path)`
   *  shapes that scoped DataFrameWriter exposes.  Both shapes are
   *  followed at runtime by `.start(...)` on a `writeStream`.
   *
   *  Detection runs jointly with the streaming detection above; the
   *  file-sink predicate fires only when `containsStreaming` is also
   *  true.  Spark itself refuses to start a file-sink streaming query
   *  without `option("checkpointLocation", …)` so the codegen emits
   *  a `// note:` comment near the writer to remind users (Phase F.3
   *  guidance — see `docs/spark-streaming.md`). */
  private val FileSinkFormatPattern = """(?i)\.format\(\s*"(?:parquet|csv|json|orc|text)"\s*\)""".r

  /** Does the joined module source contain a file-based streaming
   *  format selector — `.format("parquet"|"csv"|"json"|"orc"|"text")`?
   *  Used by Phase F.3 to emit checkpoint-location guidance.  Note:
   *  callers should AND this with `containsStreaming` before acting
   *  on it — a batch `.write.format("parquet")` is the same regex
   *  match but doesn't need checkpoint guidance. */
  def containsFileStreamSink(source: String): Boolean =
    FileSinkFormatPattern.findFirstIn(source).isDefined

  /** Does the joined module source already mention
   *  `checkpointLocation`?  Used by the F.3 guidance emitter to
   *  suppress the reminder once the user has set the option
   *  themselves. */
  def containsCheckpointLocation(source: String): Boolean =
    source.contains("checkpointLocation")

  // ── Phase M — MLlib detection ────────────────────────────────────────────
  //
  // Auto-emit the `spark-mllib_2.13` runtime dep when the user imports
  // any `org.apache.spark.ml.*` class.  Detection mirrors the Streaming
  // and Lakehouse paths — a single regex pass over the joined post-
  // `extractSqlFns` source.  False positives (the import substring
  // appearing inside a string literal or a commented-out line) are
  // accepted: a redundant Coursier resolve is cheap, while properly
  // parsing user Scala fragments would require a Scala 3 parser —
  // out of scope.  See `docs/spark-mllib.md` § Architecture for the
  // false-negative trade-off (dynamic / re-exported imports).
  //
  // The regex matches two surface forms:
  //   1. `import org.apache.spark.ml.<...>` — canonical FQN.
  //   2. `import o.a.s.ml.<...>` — three-segment alias users write in
  //                                tight import groups.
  //
  // Both `import` forms can appear at the top of a `scalascript` block
  // OR as a grouped multi-import line (`import o.a.s.ml.feature.*`);
  // the simple substring after `import` covers all of them.  The
  // regex anchors on a word boundary before `import` so the literal
  // `// import ...` form (commented-out) still matches — documented
  // limitation.

  /** Pre-compiled patterns for detecting MLlib imports.  Two
   *  alternatives so a single `findFirstIn` covers both the canonical
   *  `org.apache.spark.ml.` package prefix and the three-segment
   *  `o.a.s.ml.` alias (used in compact import groups).  No `(?i)`
   *  flag — Scala package names are case-sensitive, and Spark itself
   *  rejects a mistyped `Org.Apache.Spark.Ml.*` at compile time. */
  private val MllibImportPattern =
    """\bimport\s+(?:org\.apache\.spark\.ml\.|o\.a\.s\.ml\.)""".r

  /** Does the joined module source import any class from
   *  `org.apache.spark.ml.*` (canonical or three-segment alias)?
   *  When true, `genModule` emits
   *  `//> using dep "org.apache.spark:spark-mllib_2.13:<v>"` in the
   *  header so scala-cli resolves the MLlib JAR via Coursier.
   *  Phase M.2 — see `docs/spark-mllib.md`. */
  def containsMllib(source: String): Boolean =
    MllibImportPattern.findFirstIn(source).isDefined

  // ── Phase G.2 — Hive metastore / catalog detection helpers ──────────────
  //
  // Two triggers, ORed together, decide whether the generated source
  // wires Hive support on the SparkSession:
  //
  //   1. Front-matter `spark-hive-metastore:` or `spark-warehouse:`
  //      set in the module manifest (threaded into SparkGen via the
  //      `hiveMetastore` / `warehouse` constructor params).
  //   2. The user's scalascript code literally calls
  //      `.enableHiveSupport()` on `SparkSession.builder()`.  Detected
  //      by substring match on the joined post-`extractSqlFns` source.
  //
  // When either trigger fires, `genModule` emits:
  //   - `//> using dep "org.apache.spark:spark-hive_2.13:<sparkVersion>"`
  //     in the file header (sits after the lakehouse deps, before
  //     front-matter pass-through — see docs/spark-catalog.md).
  //   - `.config("spark.sql.catalogImplementation", "hive")` on the
  //     builder, BEFORE the user `spark-config:` map so a user
  //     override still wins.
  //   - `.config("spark.hadoop.hive.metastore.uris", "<uri>")` when
  //     the front-matter `spark-hive-metastore:` is set.
  //   - `.config("spark.sql.warehouse.dir", "<path>")` when the
  //     front-matter `spark-warehouse:` is set.
  //   - `.enableHiveSupport()` immediately before `.getOrCreate()`.
  //
  // Detection is purely substring — false positives (e.g. a literal
  // `"enableHiveSupport"` inside a string) are rare in practice and
  // would just add a redundant dep + flag.

  /** Does the joined module source explicitly call
   *  `enableHiveSupport()` on the builder?  Used to trigger the Hive
   *  dep + builder wiring even when no front-matter key is set.  Same
   *  textual detection shape as `containsAwaitTermination`. */
  def containsEnableHiveSupport(source: String): Boolean =
    source.contains("enableHiveSupport")

  /** Result of `detectLakehouseFormats`: which lakehouse format names
   *  appear as the literal argument of `.format("…")` anywhere in the
   *  collected block sources.
   *
   *  Track L.2 / L.3 / L.4 in `docs/spark-lakehouse.md`.  Each flag
   *  triggers two independent emission decisions in `genModule`:
   *
   *    1. A `//> using dep "<group>:<artifact>_2.13:<version>"` header
   *       line so `scala-cli` resolves the runtime JAR via Coursier.
   *    2. A set of `.config(k, v)` lines on `SparkSession.builder()`
   *       to register the format's SQL extension and (where required)
   *       override the default catalog.
   *
   *  Detection is intentionally a substring match on the format
   *  string literal — same shape as `extractSqlFns`'s regex pass.
   *  False positives (the literal `.format("delta")` inside a string
   *  or a comment) are accepted: a redundant Coursier resolve is
   *  cheap; properly parsing user Scala expressions would require a
   *  full fragment parser.  False negatives (dynamic format strings,
   *  e.g. `val fmt = if cond then "delta" else "parquet"; ds.write.format(fmt)`)
   *  are documented: the user declares the dep manually via
   *  front-matter `dependencies:` in that case. */
  case class LakehouseFlags(
      usesDelta:   Boolean = false,
      usesIceberg: Boolean = false,
      usesHudi:    Boolean = false
  ):
    def any: Boolean = usesDelta || usesIceberg || usesHudi

  object LakehouseFlags:
    val Empty: LakehouseFlags = LakehouseFlags()

  /** Pre-compiled `\.format("<name>")` patterns — one per supported
   *  format.  Case-insensitive flag (`(?i)`) because Spark's data-source
   *  registry is itself case-insensitive — users may legitimately
   *  type `.format("Delta")` or `.format("DELTA")` and the runtime
   *  accepts all variants.  Capturing the literal `.format(` prefix
   *  (and not just the name) avoids matching `.format("delta-stage")`
   *  or other arbitrary string occurrences. */
  private val DeltaFormatPattern   = """(?i)\.format\(\s*"delta"\s*\)""".r
  private val IcebergFormatPattern = """(?i)\.format\(\s*"iceberg"\s*\)""".r
  private val HudiFormatPattern    = """(?i)\.format\(\s*"hudi"\s*\)""".r

  /** Scan all collected block sources for `.format("delta" | "iceberg"
   *  | "hudi")` literals and return the union of detected formats.
   *  Blocks are scanned by simple regex find — O(n) per block, no
   *  parsing or AST traversal, so the cost is negligible against
   *  the rest of code generation. */
  def detectLakehouseFormats(blocks: List[Block]): LakehouseFlags =
    blocks.foldLeft(LakehouseFlags.Empty) { (acc, b) =>
      LakehouseFlags(
        usesDelta   = acc.usesDelta   || DeltaFormatPattern.findFirstIn(b.src).isDefined,
        usesIceberg = acc.usesIceberg || IcebergFormatPattern.findFirstIn(b.src).isDefined,
        usesHudi    = acc.usesHudi    || HudiFormatPattern.findFirstIn(b.src).isDefined
      )
    }

  /** Configuration pairs the lakehouse formats need installed on
   *  `SparkSession.builder()` at session-creation time.  Returned in
   *  the same order they're emitted in the builder chain, which
   *  matters because:
   *
   *    1. Lakehouse defaults sit BETWEEN the adaptive `local*`
   *       defaults and the user `spark-config:` map.  Order in this
   *       list controls within-block order; the cross-block order
   *       is fixed by `genModule`.
   *    2. A user `spark-config:` entry for the same key OVERRIDES
   *       the lakehouse default (Spark's builder takes last-write
   *       wins).
   *
   *  When multiple lakehouse formats are detected (e.g. both Delta
   *  and Iceberg in the same module), their config blocks coexist.
   *  Where two formats want to set the same key
   *  (`spark.sql.extensions` is the obvious one — both Delta and
   *  Iceberg register SQL extensions there), Spark accepts a
   *  comma-separated list, so we concatenate the values for that
   *  key rather than letting the second write clobber the first. */
  def lakehouseConfigs(flags: LakehouseFlags): List[(String, String)] =
    if !flags.any then Nil
    else
      val raw = scala.collection.mutable.ListBuffer.empty[(String, String)]
      if flags.usesDelta then
        raw += "spark.sql.extensions" -> "io.delta.sql.DeltaSparkSessionExtension"
        raw += "spark.sql.catalog.spark_catalog" -> "org.apache.spark.sql.delta.catalog.DeltaCatalog"
      // L.3 / L.4 will append their pairs here.
      // Merge entries sharing a key by joining values with ',' so
      // multi-format extensions register together.
      raw.toList
        .groupBy(_._1)
        .toList
        .map { case (k, vs) => k -> vs.map(_._2).distinct.mkString(",") }
        .sortBy(_._1)

  /** Parse a Scala parameter list (the raw text between `(` and `)`)
   *  into a list of type names, in declaration order.  Each entry
   *  is the bare type identifier from a `name: Type` pair.
   *
   *  `"s: String, n: Int"` → `List("String", "Int")`.
   *  Empty list (`""`) → `Nil`.
   *
   *  Doesn't handle default values, by-name (`=>`), or curried
   *  parameter groups — UDFs are simple value functions and this
   *  is enough for the cases ScalaScript needs to register. */
  private def parseParamTypes(raw: String): List[String] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Nil
    else
      trimmed.split(',').toList.map { part =>
        val idx = part.indexOf(':')
        if idx < 0 then part.trim
        else part.substring(idx + 1).trim
      }

private class SparkGen(
    baseDir:       Option[os.Path]     = None,
    sparkVersion:  String              = SparkGen.DefaultVersion,
    sparkMaster:   String              = SparkGen.DefaultMaster,
    extraConfig:   Map[String, String] = Map.empty,
    appName:       String              = SparkGen.DefaultAppName,
    scalaVersion:  String              = SparkGen.DefaultScalaVersion,
    hiveMetastore: Option[String]      = None,
    warehouse:     Option[String]      = None
):

  // Resolved paths already inlined via Content.Import (diamond-safe).
  private val importedFiles = mutable.Set.empty[String]
  private var moduleDeps: Map[String, String] = Map.empty

  /** Escape a string for embedding inside a Scala double-quoted literal.
   *  Used by the `spark-config:` front-matter emitter (§ 9.5 C.3) — keys
   *  are dotted identifiers (no special chars) but values can contain
   *  backslashes, double quotes, or newlines (e.g. file paths on
   *  Windows-style classpath configs), and we don't want to break the
   *  emitted source on those.  Conservative: escape `\`, `"`, `\n`,
   *  `\r`, `\t`; everything else passes through. */
  private def escape(s: String): String =
    val sb = StringBuilder(s.length + 4)
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"'  => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.toString

  // Resolve an internal ScalaScript artifact to an absolute scala-cli
  // `//> using jar` directive when running from a staged or dev checkout.
  // Falls back to the snapshot coordinate for installed builds that publish
  // internal runtime jars instead of keeping them under target/.
  private def sscJarDirective(artifactBase: String): String =
    import scala.jdk.CollectionConverters.*
    def findIn(dir: java.nio.file.Path): Option[java.nio.file.Path] =
      if java.nio.file.Files.isDirectory(dir) then
        val stream = java.nio.file.Files.list(dir)
        try
          stream.iterator.asScala.find { p =>
            val name = p.getFileName.toString
            name.startsWith(s"${artifactBase}_") && name.endsWith(".jar") && !name.endsWith("-tests.jar")
          }
        finally stream.close()
      else None
    def findInDevTree(root: java.nio.file.Path): Option[java.nio.file.Path] =
      if java.nio.file.Files.isDirectory(root) then
        val stream = java.nio.file.Files.walk(root, 7)
        try
          stream.iterator.asScala
            .filterNot(p => p.toString.contains(s"${java.io.File.separator}target${java.io.File.separator}bg-jobs${java.io.File.separator}"))
            .filter { p =>
              val name = p.getFileName.toString
              name.startsWith(s"${artifactBase}_") && name.endsWith(".jar") && !name.endsWith("-tests.jar")
            }
            .toVector
            .sortBy(_.toString)
            .headOption
        finally stream.close()
      else None
    val libPath = Option(System.getProperty("ssc.lib.path"))
    val installed = libPath.flatMap(path => findIn(java.nio.file.Paths.get(path, "bin", "lib", "jars")))
    val cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize()
    val staged = findIn(cwd.resolve("bin").resolve("lib").resolve("jars"))
    val devTarget = findInDevTree(cwd)
    installed.orElse(staged).orElse(devTarget)
      .map(p => s"""//> using jar "${p.toAbsolutePath}"\n""")
      .getOrElse(s"""//> using dep "io.scalascript::$artifactBase:0.1.0-SNAPSHOT"\n""")

  private def sscTypedDataRuntimeDirective(): String =
    import scala.jdk.CollectionConverters.*
    val cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize()
    val sourceRoot =
      cwd.resolve("backend").resolve("typed-data").resolve("src").resolve("main").resolve("scala")
    if java.nio.file.Files.isDirectory(sourceRoot) then
      val stream = java.nio.file.Files.walk(sourceRoot)
      try
        stream.iterator.asScala
          .filter(p => java.nio.file.Files.isRegularFile(p) && p.getFileName.toString.endsWith(".scala"))
          .toVector
          .sortBy(_.toString)
          .map(p => s"""//> using file "${p.toAbsolutePath}"\n""")
          .mkString
      finally stream.close()
    else
      sscJarDirective("scalascript-backend-typed-data-runtime")

  private def blocksUseSharedSparkSchema(blocks: List[SparkGen.Block]): Boolean =
    val names = Set("SparkSchemaCodec", "SparkSchema", "SparkSchemaField", "SparkSchemaType")
    blocks.exists { b =>
      b.src.contains("scalascript.typeddata") || names.exists(name => b.src.contains(name))
    }

  // ── Module entry ──────────────────────────────────────────────────────────

  def genModule(module: Module): String =
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    val blocks = collectBlocks(module.sections)

    // Lakehouse track L.2 — scan blocks for `.format("delta"/"iceberg"/
    // "hudi")` literals.  Each detected format triggers (a) a
    // `//> using dep` line for its runtime JAR and (b) the
    // `SparkSession.builder()` configs the format needs at session
    // creation time.  Detection is purely on source-text substring;
    // see `LakehouseFlags` Scaladoc for the false-positive /
    // false-negative trade-off.
    val lakehouse = SparkGen.detectLakehouseFormats(blocks)

    // Phase F — Structured Streaming detection.  Walk every block once
    // through `extractSqlFns` (cheap regex; same call we'd make later
    // when composing the @main body) and join the cleaned sources so
    // header- and footer-level decisions (kafka dep, awaitTermination
    // shim) see the entire module text in one pass.  The composed
    // `(cleaned, sigs)` pairs are reused when emitting the body, so
    // the second pass below doesn't re-run the regex.
    // Phase G.3 — extend the per-block processing pass to also run
    // `extractTempViews` after `extractSqlFns`.  The two annotations
    // are sibling — both anchor on a line-start `@Marker` and strip
    // the annotation line — so chaining them through the cleaned
    // source is the natural composition.  Result per block:
    //   (cleaned-source-after-both-passes, sqlFnSigs, tempViewSigs).
    // Block bodies emit UDF registrations first, then temp-view
    // registrations, both right after the cleaned user code.
    val processed: List[(String, List[SparkGen.SqlFnSig], List[SparkGen.TempViewSig])] =
      blocks.map { b =>
        val (afterSql, sqlSigs)   = SparkGen.extractSqlFns(b.src)
        val (afterView, viewSigs) = SparkGen.extractTempViews(afterSql)
        (afterView, sqlSigs, viewSigs)
      }
    val joinedUserSrc: String =
      processed.iterator.map(_._1).mkString("\n")
    val isStreaming: Boolean =
      SparkGen.containsStreaming(joinedUserSrc)
    val needsDStream: Boolean =
      SparkGen.containsDStream(joinedUserSrc)
    val needsAwaitShim: Boolean =
      isStreaming && !SparkGen.containsAwaitTermination(joinedUserSrc)
    val needsKafkaDep: Boolean =
      SparkGen.containsKafkaFormat(joinedUserSrc) ||
      (needsDStream && SparkGen.containsConnector(joinedUserSrc) &&
        (joinedUserSrc.contains("Kafka.source") || joinedUserSrc.contains("Kafka.sink") ||
         joinedUserSrc.contains("Kafka.changelog")))
    // Phase M.2 — MLlib detection.  When the user imports any
    // `org.apache.spark.ml.*` class (or the abbreviated `o.a.s.ml.`
    // alias), auto-emit `//> using dep "org.apache.spark:spark-mllib_2.13:<v>"`
    // in the header so scala-cli resolves the MLlib JAR via Coursier.
    // See `docs/spark-mllib.md` for the design and the M.3 / M.4 / M.5
    // follow-ons.
    val needsMllibDep: Boolean =
      SparkGen.containsMllib(joinedUserSrc)
    val needsSharedSparkSchema: Boolean =
      blocksUseSharedSparkSchema(blocks)
    // Phase F.3 — file source/sink checkpoint guidance.  Streaming
    // queries that write to a file sink (parquet/csv/json/orc/text)
    // refuse to start without `option("checkpointLocation", …)`; emit
    // a `// note:` comment near the file header when the user has
    // declared a streaming + file-format combo but hasn't already
    // mentioned `checkpointLocation` anywhere in their code.
    val needsCheckpointHint: Boolean =
      isStreaming &&
      SparkGen.containsFileStreamSink(joinedUserSrc) &&
      !SparkGen.containsCheckpointLocation(joinedUserSrc)

    // Phase G.2 — Hive metastore / catalog detection.  Triggered
    // by either front-matter (`spark-hive-metastore:` or
    // `spark-warehouse:`) OR a textual `.enableHiveSupport()` in
    // user code.  When fired, the emitter adds the `spark-hive_2.13`
    // runtime dep, the `spark.sql.catalogImplementation=hive` config
    // line, the per-key configs (metastore URI, warehouse dir), and
    // `.enableHiveSupport()` immediately before `.getOrCreate()`.
    val needsHive: Boolean =
      hiveMetastore.isDefined || warehouse.isDefined ||
      SparkGen.containsEnableHiveSupport(joinedUserSrc)

    val sb = StringBuilder()

    // Generated file header — documents the Spark version and run instructions.
    sb.append(s"// Generated by ScalaScript — Apache Spark $sparkVersion, Scala $scalaVersion\n")
    sb.append(s"// To run: scala-cli run this.scala\n")
    // Phase F.3 — checkpoint-location reminder for file-sink streams.
    // Spark refuses to `start()` a file-sink streaming query without
    // a checkpoint dir; emit a hint in the generated source so the
    // user sees the requirement before Spark throws at runtime.
    if needsCheckpointHint then
      sb.append("// NOTE Phase F.3: file-sink streaming detected — Spark requires\n")
      sb.append("//   .option(\"checkpointLocation\", \"/tmp/ssc-spark-ckpt-<name>\")\n")
      sb.append("// on your writeStream call (or set it via spark-config:\n")
      sb.append("// spark.sql.streaming.checkpointLocation in front-matter).\n")
    sb.append("\n")

    // scala-cli `//> using` directives — make the generated source
    // runnable straight from disk via `scala-cli run`.  Phase E
    // (v1.25 § 9.5) found that Scala 3.8.x has a regression in Spark
    // `_2.13` interop, so we pin Scala 3.7.1; Spark also requires
    // a battery of `--add-opens` flags on JDK 17+ which we bake in
    // here.  Same coord shape in `SparkBackend` and `SparkSubmit` —
    // kept in lock-step.
    sb.append(s"""//> using scala $scalaVersion\n""")
    // Spark JARs are published as `_2.13` cross-builds; no `_3`
    // artifact exists.  Scala 3 reads them via the TASTy bridge.
    sb.append(s"""//> using dep "org.apache.spark:spark-core_2.13:$sparkVersion"\n""")
    sb.append(s"""//> using dep "org.apache.spark:spark-sql_2.13:$sparkVersion"\n""")
    // Phase F.4 — Kafka source/sink auto-dep.  Emitted only when
    // `.format("kafka")` appears in user code.  Pinned to the same
    // Spark version as `spark-core` / `spark-sql` to match Spark's
    // own cross-build compatibility requirements.
    if needsKafkaDep then
      sb.append(s"""//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:$sparkVersion"\n""")
    // Phase M.2 — MLlib auto-dep.  Emitted only when the user imports
    // any `org.apache.spark.ml.*` class.  Same `_2.13` cross-build and
    // version pin as `spark-core` / `spark-sql` — Spark publishes only
    // a single `spark-mllib_2.13` artifact per release.  Sits with the
    // other Spark deps so the header layout stays in source order:
    // core → sql → kafka (if streaming Kafka) → mllib (if ML).
    if needsMllibDep then
      sb.append(s"""//> using dep "org.apache.spark:spark-mllib_2.13:$sparkVersion"\n""")
    if needsSharedSparkSchema then
      sb.append(sscTypedDataRuntimeDirective())
    SparkGen.DefaultJavaOpts.foreach { opt =>
      sb.append(s"""//> using javaOpt $opt\n""")
    }

    // Lakehouse track L.2 — auto-emitted runtime deps for detected
    // formats (see `docs/spark-lakehouse.md`).  Sits BEFORE the
    // front-matter `dependencies:` pass-through so a user-declared
    // override in front-matter wins on scala-cli's last-write
    // semantics for duplicate coord keys.  Each line is gated on
    // the corresponding `usesX` flag; modules that don't touch
    // lakehouse formats emit byte-identical headers to today's
    // baseline.
    if lakehouse.usesDelta then
      sb.append(s"""//> using dep "io.delta:delta-spark_2.13:${SparkGen.DefaultDeltaVersion}"\n""")

    // Phase G.2 — Hive metastore / warehouse runtime dep.  Emitted
    // when the front-matter `spark-hive-metastore:` or
    // `spark-warehouse:` is set, OR when user code textually invokes
    // `.enableHiveSupport()`.  Pinned to the same Spark version as
    // `spark-core` / `spark-sql` because Spark publishes the hive
    // shim alongside its own JARs (the major.minor matches Spark's
    // own).  Sits between the lakehouse deps and the front-matter
    // pass-through so a user-declared override in `dependencies:`
    // wins on scala-cli's last-write semantics.
    if needsHive then
      sb.append(s"""//> using dep "org.apache.spark:spark-hive_2.13:$sparkVersion"\n""")

    // scala-cli `//> using dep` directives from front-matter (same pattern
    // as JvmGen).  Spark deps are added separately at call site via
    // the directives above so we don't duplicate the giant JARs here.
    module.manifest.foreach { m =>
      m.dependencies.foreach { (dep, version) =>
        if !version.startsWith("http://") && !version.startsWith("https://") then
          sb.append(s"""//> using dep "$dep:$version"\n""")
      }
    }
    sb.append("\n")

    // Spark imports.
    sb.append(sparkImports)

    // Phase E — Scala 3 native `Encoder` derivation shim (v1.25 § 9.5).
    // Emitted at top level so its `inline given derived[T <: Product]`
    // and primitive `given Encoder[X]` instances are in scope inside
    // `@main def runSparkJob` once the user `import SscSparkEncoders.given`s.
    //
    // Phase M.3 — when the user imports MLlib, the shim also surfaces
    // an explicit `aenc_Vector` AgnosticEncoder for
    // `org.apache.spark.ml.linalg.Vector` so case classes with a
    // `features: Vector` field derive cleanly via the same Mirror walk
    // that handles primitives, Option, collections, and nested case
    // classes.  Gated on `needsMllibDep` so non-MLlib modules don't
    // reference the `VectorUDT` class (which lives in the MLlib JAR
    // and would fail to resolve otherwise).
    sb.append(phaseEShim(needsMllibDep))

    // @main wrapper opens here.
    sb.append("\n@main def runSparkJob(): Unit =\n")
    sb.append("  val spark = SparkSession.builder()\n")
    sb.append(s"""    .appName("${escape(appName)}")\n""")
    sb.append(s"""    .master("$sparkMaster")\n""")
    // Adaptive default configs (v1.25 § 9.5 Phase C.3 slice 8) — emit
    // ONLY when running against a `local*` master.  These were chosen
    // for the developer-laptop experience:
    //   - `spark.ui.enabled=false`         — no port held for the UI;
    //                                        terminal stays clean.
    //   - `spark.sql.shuffle.partitions=4` — sensible at single-host scale;
    //                                        the Spark default of 200 thrashes
    //                                        a 4-core laptop on small inputs.
    // On cluster masters (`spark://...`, `yarn`, `k8s://...`) both
    // values are wrong-by-default: disabling the UI hides debugging
    // surface in production, and `4` shuffle partitions caps
    // parallelism brutally on multi-node clusters.  Spark's defaults
    // are the right starting point there; users override with
    // `spark-config:` front-matter as needed.
    if sparkMaster.startsWith("local") then
      sb.append("    .config(\"spark.ui.enabled\", \"false\")\n")
      sb.append("    .config(\"spark.sql.shuffle.partitions\", \"4\")\n")
    // Lakehouse track L.2 — format-specific `.config(k, v)` lines for
    // each detected format.  Emitted AFTER the adaptive `local*`
    // defaults and BEFORE the user `spark-config:` map so:
    //
    //   1. The lakehouse defaults override the adaptive defaults
    //      where there's overlap (none today, but the layering is
    //      the right shape for future format-specific shuffle /
    //      memory tweaks).
    //   2. A user `spark-config:` entry for the same key overrides
    //      the lakehouse default (Spark builder is last-write-wins).
    //      Documented for cases like multi-catalog setups where the
    //      Delta-default `spark_catalog` override is wrong.
    //
    // Sorted by key for deterministic emit; multi-format key
    // collisions are joined comma-separated by `lakehouseConfigs`.
    SparkGen.lakehouseConfigs(lakehouse).foreach { (k, v) =>
      sb.append(s"""    .config("${escape(k)}", "${escape(v)}")\n""")
    }
    // Phase G.2 — Hive metastore + warehouse configs.  Emitted AFTER
    // the lakehouse defaults (Delta's `spark_catalog` override sits
    // alongside the Hive catalogImplementation switch; Spark merges
    // them at runtime) and BEFORE the user `spark-config:` map so
    // overrides still win.  Each line is independently gated:
    //
    //   - `spark.sql.catalogImplementation=hive` fires whenever
    //     `needsHive` is true (front-matter set OR user code calls
    //     enableHiveSupport).
    //   - The metastore URI line fires only when
    //     `hiveMetastore.isDefined` — a warehouse-only setup uses
    //     Spark's embedded derby metastore at the warehouse path.
    //   - The warehouse dir line fires only when `warehouse.isDefined`.
    if needsHive then
      sb.append("""    .config("spark.sql.catalogImplementation", "hive")""" + "\n")
    hiveMetastore.foreach { uri =>
      sb.append(s"""    .config("spark.hadoop.hive.metastore.uris", "${escape(uri)}")\n""")
    }
    warehouse.foreach { path =>
      sb.append(s"""    .config("spark.sql.warehouse.dir", "${escape(path)}")\n""")
    }
    // User-supplied `spark-config:` front-matter map (v1.25 § 9.5 Phase C.3
    // slice 3) — emit one `.config(k, v)` per entry, sorted by key for
    // deterministic source.  Values are passed through verbatim and
    // escaped for embedding in a Scala string literal.  A user's entry
    // for `spark.ui.enabled` / `spark.sql.shuffle.partitions` overrides
    // the defaults above because Spark's builder takes last-write-wins.
    extraConfig.toList.sortBy(_._1).foreach { (k, v) =>
      sb.append(s"""    .config("${escape(k)}", "${escape(v)}")\n""")
    }
    // Phase G.2 — `.enableHiveSupport()` chained immediately before
    // `.getOrCreate()` when Hive support is needed.  Spark's builder
    // accepts this at any point in the chain but the convention is
    // to call it just before resolution.  Suppressed when the user's
    // own scalascript code already includes the call — the textual
    // detection in `needsHive` is via OR, but emitting two
    // `enableHiveSupport()` calls is harmless (Spark accepts the
    // duplicate); we suppress for cleanliness.
    if needsHive && !SparkGen.containsEnableHiveSupport(joinedUserSrc) then
      sb.append("    .enableHiveSupport()\n")
    sb.append("    .getOrCreate()\n")
    // Bring Phase E encoder givens (primitive + case-class derivation) into
    // scope.  Replaces `import spark.implicits._` which triggers TypeTag-
    // based encoder lookups that Scala 3 cannot satisfy.  See SPEC § 9.5
    // "Scala 3 / Spark 2.13 interop" for the underlying issue.
    sb.append("  import SscSparkEncoders.given\n")
    // Suppress verbose Spark logging — same local-vs-cluster reasoning
    // as the adaptive configs above.  On a cluster the operator's own
    // log4j config decides what level Spark emits at; we don't
    // second-guess it.
    if sparkMaster.startsWith("local") then
      sb.append("  org.apache.log4j.Logger.getRootLogger.setLevel(org.apache.log4j.Level.WARN)\n\n")
    else
      sb.append("\n")

    // Emit the Dataset companion shim so user code `Dataset.of(...)` /
    // `Dataset.fromList(...)` resolves without any user-visible changes.
    sb.append(datasetShim(needsSharedSparkSchema))

    // v2.1.3 — DStream Spark shim.  Emitted when the user's code contains
    // `Pipeline.create` / `InMemory.source` / `Backend.Spark`.  Provides
    // the full DStream API backed by driver-local `Seq[Any]` for bounded
    // sources; distributed Spark Dataset backing lands in v2.1.3+.
    if needsDStream then sb.append(dstreamSparkShim)

    // User blocks — indented two spaces to sit inside `@main def`.
    //
    // The `(cleaned, udfSigs)` pairs were pre-computed at the top of
    // `genModule` so the streaming-detection scan and this emission
    // pass share a single `extractSqlFns` invocation per block.  The
    // helper returns the source with `@SqlFn` annotations stripped
    // (v1.25 § 9.5 Phase D — UDF bridge) plus a list of
    // `SqlFnSig(name, paramTypes, returnType)`.  For each signature
    // we emit a Java-`UDFN`-shaped `spark.udf.register` call right
    // after the cleaned source — Phase E revival sidesteps Spark's
    // TypeTag-bound typed `register[RT : TypeTag, ...]` overload
    // (which Scala 3 cannot satisfy) by using the Java functional-
    // interface form that takes an explicit `DataType`.
    //
    // Translated sql blocks contain no `@SqlFn` markers, so
    // `extractSqlFns` is a no-op on them.
    processed.foreach { case (cleaned, udfSigs, viewSigs) =>
      // Compose the emitted block source: cleaned user code, then
      // UDF registrations (Phase D), then temp-view registrations
      // (Phase G.3).  Order matters — UDFs need to land on the
      // session catalog BEFORE any temp view that references them
      // in its body would be queried.  In practice the two are
      // emitted in document order per block, so the ordering is
      // automatic.
      val udfRegistrations =
        if udfSigs.isEmpty then ""
        else "\n" + udfSigs.map(emitUdfRegistration).mkString("\n\n")
      val viewRegistrations =
        if viewSigs.isEmpty then ""
        else "\n" + viewSigs.map(emitTempViewRegistration).mkString("\n")
      val composed =
        if udfRegistrations.isEmpty && viewRegistrations.isEmpty then cleaned
        else cleaned.stripTrailing + udfRegistrations + viewRegistrations
      val indented = indentBlock(composed)
      sb.append(indented.stripTrailing())
      sb.append("\n\n")
    }

    // Auto-call main entry if declared in front-matter.
    val mainEntry = module.manifest
      .flatMap(_.raw.get("main"))
      .collect { case s: String => s }
    mainEntry.foreach { name => sb.append(s"  $name()\n") }

    // Phase F.2 — Structured Streaming guard.  When the module starts
    // a streaming query (`spark.readStream` / `.writeStream`) but
    // doesn't call `awaitTermination` itself, pin the first active
    // query and block until it finishes.  Without this the driver
    // returns the moment `start()` schedules the query and the
    // streaming engine never gets to process any data.  Users who
    // want a timeout, `awaitAnyTermination`, or multi-query
    // orchestration write the call themselves and this shim is
    // suppressed (the textual presence of `awaitTermination` is
    // enough to opt out).
    if needsAwaitShim then
      sb.append(
        """|
           |  // Phase F — auto-emitted streaming guard.  Without this the driver
           |  // returns before the streaming engine has processed any data.
           |  spark.streams.active.headOption.foreach(_.awaitTermination())
           |""".stripMargin
      )

    sb.append("\n  spark.stop()\n")

    sb.toString.stripTrailing() + "\n"

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Sequential counter for sql blocks — drives the emitted value
   *  name `_sqlBlock_<N>` so multiple sql blocks in one module each
   *  bind to a distinct DataFrame in the @main scope. */
  private var sqlBlockCounter: Int = 0

  /** Tracks how many sql blocks each section identifier has already
   *  consumed — only the first one in a section gets the friendly
   *  `<sectionId>.sql` alias (per Phase C.2 contract).  Subsequent
   *  sql blocks in the same section are reachable only by their
   *  internal `_sqlBlock_<N>` name. */
  private val sqlPerSection = scala.collection.mutable.Map.empty[String, Int]

  private def collectBlocks(sections: List[Section]): List[SparkGen.Block] =
    sections.flatMap { s =>
      val secId = sectionIdent(s.heading.text)
      val own = s.content.flatMap {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          List(SparkGen.Block(cb.source))
        // Phase C: `sql` block → val _sqlBlock_<N> = spark.sql(...).  The
        // rewriter walks the source once and produces a (sqlText, binds)
        // pair; the emitted Scala captures the DataFrame so subsequent
        // scalascript blocks in the @main body can reference it.
        //
        // Phase C.2: if the enclosing section has a usable identifier
        // and this is the first sql block in that section, also emit a
        // `object <sectionId>: lazy val sql: DataFrame = _sqlBlock_<N>`
        // alias for friendly user-facing access (e.g. `Users.sql.show()`).
        // Second-and-later sql blocks in the same section retain only
        // their internal name — a `lazy val sql` re-declaration would be
        // a Scala compile error.
        case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
          val n = sqlBlockCounter
          sqlBlockCounter += 1
          val aliasable = secId.filter { id =>
            val prior = sqlPerSection.getOrElse(id, 0)
            sqlPerSection(id) = prior + 1
            prior == 0
          }
          List(SparkGen.Block(sqlBlockToScala(cb.source, n, aliasable)))
        case imp: Content.Import =>
          inlineImport(imp.path)._1
        case _ => Nil
      }
      own ++ collectBlocks(s.subsections)
    }

  /** Mirror Interpreter / JsGen / JvmGen `sectionIdent`: words
   *  (alphanumeric runs) become camelCase; the first word preserves
   *  its original casing.  Returns None when the heading is empty
   *  or all-punctuation. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  /** Translate an `sql` fenced block to its Scala equivalent.  Runs the
   *  shared `SqlBindRewriter` to produce a `:bind<i>` placeholdered SQL
   *  string and an ordered list of expression sources, then emits:
   *
   *    val _sqlBlock_<n>: org.apache.spark.sql.DataFrame =
   *      spark.sql("<sqlText>", java.util.Map.of(
   *        "bind0", <expr0>,
   *        "bind1", <expr1>
   *      ))
   *
   *  When the block has no binds, the `Map.of(...)` argument is omitted
   *  and the single-argument `spark.sql(query)` overload is used.
   *
   *  When `sectionAlias` is `Some("Users")` the emitted snippet ALSO
   *  appends a friendly object alias:
   *
   *    object Users:
   *      lazy val sql: org.apache.spark.sql.DataFrame = _sqlBlock_<n>
   *
   *  Subsequent sql blocks in the same section pass `None` here (the
   *  caller's `sqlPerSection` book-keeping decides), so we never
   *  generate a duplicate `lazy val sql` inside the same `object`.
   *
   *  The triple-quoted Scala string literal contains the rewritten SQL
   *  verbatim; embedded `"""` would break the literal (rare in practice
   *  — Spark SQL doesn't use triple quotes). */
  private def sqlBlockToScala(source: String, n: Int, sectionAlias: Option[String]): String =
    import scalascript.transform.SqlBindRewriter
    val r       = SqlBindRewriter.rewriteSparkSql(source)
    val valName = s"_sqlBlock_$n"
    val sqlLit  = "\"\"\"" + r.sql + "\"\"\""
    val defDecl =
      if r.binds.isEmpty then
        s"val $valName: org.apache.spark.sql.DataFrame = spark.sql($sqlLit)"
      else if r.binds.size <= SparkGen.MapOfMaxPairs then
        val pairs = r.binds.zipWithIndex.map { (expr, idx) =>
          s"""  "bind$idx", $expr"""
        }.mkString(",\n")
        s"""|val $valName: org.apache.spark.sql.DataFrame = spark.sql(
            |  $sqlLit,
            |  java.util.Map.of(
            |$pairs
            |  )
            |)""".stripMargin
      else
        val entries = r.binds.zipWithIndex.map { (expr, idx) =>
          s"""  java.util.Map.entry("bind$idx", $expr)"""
        }.mkString(",\n")
        s"""|val $valName: org.apache.spark.sql.DataFrame = spark.sql(
            |  $sqlLit,
            |  java.util.Map.ofEntries[String, Object](
            |$entries
            |  )
            |)""".stripMargin
    sectionAlias match
      case None        => defDecl
      case Some(secId) =>
        defDecl +
        s"""
           |object $secId:
           |  lazy val sql: org.apache.spark.sql.DataFrame = $valName""".stripMargin

  private def inlineImport(path: String): (List[SparkGen.Block], List[String]) =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val resolved =
      try scalascript.imports.ImportResolver.resolve(path, base, moduleDeps, None)
      catch case e: Throwable => throw new RuntimeException(s"Import $path: ${e.getMessage}")
    val key = resolved.toString
    if importedFiles.contains(key) then (Nil, Nil)
    else if !os.exists(resolved) then
      throw new RuntimeException(s"Import not found: $path")
    else
      importedFiles += key
      val importedModule = Parser.parse(os.read(resolved))
      val pkg = importedModule.manifest.flatMap(_.pkg).getOrElse(Nil)
      val nested = new SparkGen(Some(resolved / os.up), sparkVersion)
      nested.importedFiles ++= importedFiles
      (nested.collectBlocks(importedModule.sections), pkg)

  /** Indent every non-empty line by two spaces so it sits inside
   *  `@main def runSparkJob(): Unit =`. */
  private def indentBlock(src: String): String =
    src.linesIterator
      .map(l => if l.nonEmpty then "  " + l else l)
      .mkString("\n")

  /** Emit a Java-UDFN-shaped `spark.udf.register("name", new UDF<N>[...] {
   *  def call(...) = name(...) }, returnDataType)` call for a single
   *  `@SqlFn`-marked declaration (v1.25 § 9.5 Phase D revival under
   *  Phase E).
   *
   *  Spark's typed `register[RT : TypeTag, A1 : TypeTag, …](name,
   *  func)` overload won't compile under Scala 3 + Spark `_2.13`
   *  (no TypeTag synthesis), so we route through the Java functional-
   *  interface form which takes an explicit DataType and is
   *  TypeTag-free.  The wrapper class delegates back to the user's
   *  Scala `def NAME` so the body of the function is authored once
   *  in normal Scala and is callable from both Scala code and SQL.
   *
   *  Return DataType is looked up in `SparkGen.SqlFnDataType`.
   *  Unknown types degrade to `StringType` with a `// TODO` comment —
   *  the compile is preserved but Spark will likely throw at runtime;
   *  authors get a visible cue to either narrow the return type or
   *  register the UDF manually. */
  private def emitUdfRegistration(sig: SparkGen.SqlFnSig): String =
    val arity   = sig.paramTypes.size
    val argDecl =
      sig.paramTypes.zipWithIndex
        .map { case (t, i) => s"a${i + 1}: $t" }
        .mkString(", ")
    val argRef  = (1 to arity).map(i => s"a$i").mkString(", ")
    val typeArgs =
      if arity == 0 then sig.returnType
      else (sig.paramTypes :+ sig.returnType).mkString(", ")
    val (rtFqn, todo) =
      SparkGen.SqlFnDataType.get(sig.returnType) match
        case Some(fqn) => (fqn, "")
        case None      =>
          ("org.apache.spark.sql.types.StringType",
           s"  // TODO Phase E: no DataType mapping for return type '${sig.returnType}'\n")
    s"""${todo}spark.udf.register("${sig.name}",
       |  new org.apache.spark.sql.api.java.UDF$arity[$typeArgs] {
       |    def call($argDecl): ${sig.returnType} = ${sig.name}($argRef)
       |  },
       |  $rtFqn
       |)""".stripMargin

  /** Emit `<varName>.createOrReplaceTempView("<viewName>")` for a
   *  single `@TempView`-marked declaration (Phase G.3).
   *
   *  Lands right after the cleaned `val` declaration so the bound
   *  Dataset becomes queryable from any subsequent `sql` block in
   *  the same module (and from any `Dataset.fromTable[T](viewName)`
   *  read once G.4 lands).  No collision detection — two
   *  `@TempView("orders")` annotations in the same module emit
   *  two registration lines; the second one overrides the first
   *  at runtime (it's `createOrReplaceTempView`, not
   *  `createTempView`).  Documented limitation in the spec. */
  private def emitTempViewRegistration(sig: SparkGen.TempViewSig): String =
    s"""${sig.varName}.createOrReplaceTempView("${sig.viewName}")"""

  // ── Emitted Scala 3 + Spark source fragments ──────────────────────────────

  // Imports exposed to user `scalascript` blocks.  Beyond the bare
  // SparkSession / Dataset / Encoder essentials, we surface:
  //   - `Row`        — what `df.collect()` and the Phase C.1 `_sqlBlock_<N>`
  //                    DataFrame yield when materialised; pattern matching
  //                    `case Row(id, name, _) =>` is the standard way to
  //                    deconstruct results in user code.
  //   - `DataFrame`  — already used FQ in sql-block type ascriptions; the
  //                    import lets user code write `val df: DataFrame = …`
  //                    in scalascript blocks without spelling out the FQN.
  //   - `types._`    — `StructType`, `StructField`, `StringType`,
  //                    `IntegerType`, etc.  Needed for the
  //                    `spark.read.schema(...)` reader path and for the
  //                    forthcoming C.3 `std/parsing` → `StructType` bridge.
  private val sparkImports: String =
    """|import org.apache.spark.sql.{SparkSession, Dataset, DataFrame, Row, Encoder}
       |import org.apache.spark.sql.Encoders
       |import org.apache.spark.sql.functions._
       |import org.apache.spark.sql.types._
       |import org.apache.spark.sql.streaming.{Trigger, StreamingQuery, OutputMode}
       |""".stripMargin

  /** Phase E — Scala 3 native Spark `Encoder` derivation.
   *
   *  Drops in as an `object SscSparkEncoders` at the top of the emitted
   *  source.  Two layers:
   *
   *  1. Primitive `given Encoder[String / Int / Long / Double / Boolean / …]`
   *     instances that wrap Spark's pre-baked `Encoders.STRING` /
   *     `Encoders.scalaInt` / etc.  These are needed because we **drop**
   *     `import spark.implicits._` — that import brings in
   *     `newProductEncoder[T : TypeTag]` whose `TypeTag` constraint
   *     Scala 3 cannot synthesise (no Scala 2 macros) and which
   *     therefore poisons every implicit search for `Encoder[T]`.
   *
   *  2. `inline given derived[T <: Product](using Mirror.ProductOf[T],
   *     ClassTag[T]): Encoder[T]` — synthesises an
   *     `AgnosticEncoders.ProductEncoder[T]` from Scala 3's `Mirror`
   *     (no TypeTag needed) and wraps it via `ExpressionEncoder(...)`.
   *     Works for case classes whose fields are primitives or `String`
   *     today; nested case classes and `Option[T]` are explicit
   *     next-step work.
   *
   *  Phase M.3 — when `usesMllib` is true, the shim also surfaces an
   *  explicit `aenc_MLVector: AgnosticEncoder[org.apache.spark.ml.linalg.Vector]`
   *  given that wraps `UDTEncoder(new VectorUDT(), classOf[VectorUDT])`.
   *  Spark ML's `Vector` is a sealed trait (not a `Product`), so the
   *  Mirror-based `aenc_Product[T <: Product]` derivation can't reach
   *  it; the explicit given routes via Spark's own UserDefinedType
   *  registry so the wire-level representation matches what every
   *  MLlib operator expects (a `VectorUDT.sqlType` struct, NOT a Kryo
   *  blob).  Gated on `usesMllib` so non-MLlib modules don't reference
   *  the `VectorUDT` class (which lives in the MLlib JAR and would
   *  fail to resolve otherwise).
   *
   *  The shim assumes the runtime is **Scala 3.7.1 + Spark 4.x +
   *  JDK 17/21 with the standard Spark add-opens** — Scala 3.8.x has
   *  a regression in its TASTy bridge to Spark `_2.13` reflection
   *  that breaks every `ExpressionEncoder` (see SPEC § 9.5 "Scala 3
   *  / Spark 2.13 interop"). */
  private def phaseEShim(usesMllib: Boolean): String =
    val mllibBlock =
      if !usesMllib then ""
      else
        """|
           |  // ── Phase M.3 — MLlib Vector encoder ─────────────────────────────────
           |  //
           |  // Spark ML's `org.apache.spark.ml.linalg.Vector` is a sealed
           |  // trait (NOT a Product), with `DenseVector` and `SparseVector`
           |  // case-class impls.  The Mirror-based `aenc_Product[T <: Product]`
           |  // derivation can't synthesise an encoder for it; the explicit
           |  // given below routes via Spark's own `VectorUDT` user-defined
           |  // type so the wire-level column shape matches what every MLlib
           |  // operator expects.  See `docs/spark-mllib.md` § Architecture.
           |  //
           |  // Visibility note: `VectorUDT` is `private[spark]` in Spark 4.0.0,
           |  // so user code can't `new VectorUDT()` directly.  We go through
           |  // the public `SQLDataTypes.VectorType` singleton (typed as
           |  // `DataType` but always a `VectorUDT` instance at runtime) and
           |  // recover the concrete `UserDefinedType[Vector]` via cast.  Same
           |  // trick supplies the class-token UDTEncoder's second parameter
           |  // expects.  See `docs/spark-mllib.md` open question on UDT
           |  // visibility.
           |  //
           |  // Aliased to `MLVector` so it doesn't clash with
           |  // `scala.collection.immutable.Vector` (which the `aenc_Vector[E]`
           |  // given above already handles).
           |  import org.apache.spark.ml.linalg.{Vector => MLVector, SQLDataTypes => MLSQLDataTypes}
           |  import org.apache.spark.sql.types.UserDefinedType
           |  import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.UDTEncoder
           |
           |  private val _mlVectorUDT: UserDefinedType[MLVector] =
           |    MLSQLDataTypes.VectorType.asInstanceOf[UserDefinedType[MLVector]]
           |
           |  given aenc_MLVector: AgnosticEncoder[MLVector] =
           |    UDTEncoder[MLVector](
           |      _mlVectorUDT,
           |      _mlVectorUDT.getClass.asInstanceOf[Class[_ <: UserDefinedType[_]]]
           |    )
           |""".stripMargin
    phaseEShimHead + mllibBlock + phaseEShimTail

  /** Phase E shim head — everything up to and including the collection
   *  encoders, but before the `aenc_Product[T]` Mirror walk.  When
   *  Phase M.3 is active the MLlib `aenc_MLVector` given slots in
   *  between this head and the `phaseEShimTail` below, so Mirror.derive
   *  sees the explicit `AgnosticEncoder[MLVector]` via `summonInline`
   *  before falling back to the generic Product path. */
  private val phaseEShimHead: String =
    """|
       |// ── Phase E — Scala 3 native Spark Encoder derivation ──────────────────
       |//
       |// Replaces `import spark.implicits._` which under Scala 3 + Spark
       |// 2.13 binaries cannot synthesise the `TypeTag` instances Spark's
       |// product/tuple encoder derivation requires.  See SPEC § 9.5.
       |object SscSparkEncoders:
       |  import scala.deriving.Mirror
       |  import scala.compiletime.{erasedValue, constValueTuple}
       |  import scala.reflect.{ClassTag, classTag}
       |  import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
       |  import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.*
       |  import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
       |  import org.apache.spark.sql.types.Metadata
       |
       |  // Pre-baked primitive encoders.  Spark's own `Encoders.STRING` /
       |  // `Encoders.scalaInt` etc. work at runtime under Scala 3.7.1; what
       |  // breaks is the *implicit search* through `spark.implicits._` which
       |  // is TypeTag-bound.  Surfacing them here as plain `given`s gets
       |  // them into implicit scope without TypeTag.
       |  given Encoder[String]  = Encoders.STRING
       |  given Encoder[Int]     = Encoders.scalaInt
       |  given Encoder[Long]    = Encoders.scalaLong
       |  given Encoder[Double]  = Encoders.scalaDouble
       |  given Encoder[Float]   = Encoders.scalaFloat
       |  given Encoder[Short]   = Encoders.scalaShort
       |  given Encoder[Byte]    = Encoders.scalaByte
       |  given Encoder[Boolean] = Encoders.scalaBoolean
       |
       |  import scala.compiletime.summonInline
       |
       |  // ── Field-level AgnosticEncoder givens ────────────────────────────────
       |  //
       |  // The encoder for a case-class field is found via normal Scala 3
       |  // implicit search.  Primitive types resolve to pre-baked
       |  // AgnosticEncoders; `Option[U]` recurses through `aenc_Option`;
       |  // nested case classes recurse through `aenc_Product` via Mirror.
       |  // `Option` is a sealed sum (not a Product), so the Option vs
       |  // Product givens are unambiguous — Option types have only
       |  // `Mirror.SumOf`, not `Mirror.ProductOf`.
       |
       |  given aenc_String : AgnosticEncoder[String]  = StringEncoder
       |  given aenc_Boolean: AgnosticEncoder[Boolean] = PrimitiveBooleanEncoder
       |  given aenc_Byte   : AgnosticEncoder[Byte]    = PrimitiveByteEncoder
       |  given aenc_Short  : AgnosticEncoder[Short]   = PrimitiveShortEncoder
       |  given aenc_Int    : AgnosticEncoder[Int]     = PrimitiveIntEncoder
       |  given aenc_Long   : AgnosticEncoder[Long]    = PrimitiveLongEncoder
       |  given aenc_Float  : AgnosticEncoder[Float]   = PrimitiveFloatEncoder
       |  given aenc_Double : AgnosticEncoder[Double]  = PrimitiveDoubleEncoder
       |
       |  /** `Option[U]` → Spark `OptionEncoder` wrapping the inner. */
       |  given aenc_Option[U](using inner: AgnosticEncoder[U]): AgnosticEncoder[Option[U]] =
       |    OptionEncoder(inner)
       |
       |  // ── Collection encoders ──────────────────────────────────────────────
       |  //
       |  // `Seq` / `List` / `Vector` all funnel through Spark's
       |  // `IterableEncoder[C, E]` — same wire shape (an `array` column),
       |  // different runtime container class.  `containsNull` is read off
       |  // the element encoder's `nullable` so `Seq[Option[String]]` says
       |  // `containsNull = true` automatically.
       |  // `Array[E]` uses `ArrayEncoder[E]`; `Map[K, V]` uses
       |  // `MapEncoder[Map[K, V], K, V]`.  Spark's bytecode-level
       |  // serializer reads these structurally — no TypeTag needed.
       |
       |  given aenc_Seq[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Seq[E]] =
       |    IterableEncoder(classTag[Seq[E]], inner, inner.nullable, lenientSerialization = false)
       |
       |  given aenc_List[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[List[E]] =
       |    IterableEncoder(classTag[List[E]], inner, inner.nullable, lenientSerialization = false)
       |
       |  given aenc_Vector[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Vector[E]] =
       |    IterableEncoder(classTag[Vector[E]], inner, inner.nullable, lenientSerialization = false)
       |
       |  given aenc_Set[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Set[E]] =
       |    IterableEncoder(classTag[Set[E]], inner, inner.nullable, lenientSerialization = false)
       |
       |  given aenc_Array[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Array[E]] =
       |    ArrayEncoder(inner, inner.nullable)
       |
       |  given aenc_Map[K, V](using k: AgnosticEncoder[K], v: AgnosticEncoder[V]): AgnosticEncoder[Map[K, V]] =
       |    MapEncoder(classTag[Map[K, V]], k, v, valueContainsNull = v.nullable)
       |""".stripMargin

  /** Phase E shim tail — the `aenc_Product[T <: Product]` Mirror walk
   *  plus the top-level `derived[T]` Encoder given.  Spliced after the
   *  optional Phase M.3 `aenc_MLVector` block so the Mirror walk sees
   *  it via `summonInline[AgnosticEncoder[t]]` for any `features: Vector`
   *  field. */
  private val phaseEShimTail: String =
    """|
       |  /** Nested case-class encoder.  The field walk recursively
       |   *  `summonInline[AgnosticEncoder[t]]` for each element type —
       |   *  primitives resolve from the givens above, `Option` from
       |   *  `aenc_Option`, and nested case classes recurse back here. */
       |  inline given aenc_Product[T <: Product](
       |      using m: Mirror.ProductOf[T], ct: ClassTag[T]
       |  ): AgnosticEncoder[T] =
       |    val labels = constValueTuple[m.MirroredElemLabels].toList.map(_.asInstanceOf[String])
       |    val encs   = summonFieldEncoders[m.MirroredElemTypes]
       |    val fields = labels.zip(encs).map { case (n, e) =>
       |      EncoderField(n, e, e.nullable, Metadata.empty)
       |    }
       |    ProductEncoder[T](ct, fields, None)
       |
       |  inline def summonFieldEncoders[Ts <: Tuple]: List[AgnosticEncoder[?]] =
       |    inline erasedValue[Ts] match
       |      case _: EmptyTuple => Nil
       |      case _: (t *: ts)  => summonInline[AgnosticEncoder[t]] :: summonFieldEncoders[ts]
       |
       |  /** Top-level `Encoder[T]` for case classes — wraps the
       |   *  AgnosticEncoder the recursive product derivation produces. */
       |  inline given derived[T <: Product](using ae: AgnosticEncoder[T]): Encoder[T] =
       |    ExpressionEncoder(ae)
       |""".stripMargin

  /** Shim that makes `Dataset.of(...)`, `Dataset.fromList(...)`, and
   *  `Dataset.fromFile(...)` work inside user blocks.
   *
   *  The shim is emitted INSIDE `@main def runSparkJob()` after the
   *  `spark` val and `import spark.implicits._` so the Spark implicits
   *  are in scope for the Encoder resolution.
   *
   *  `Dataset.of` and `Dataset.fromList` delegate to
   *  `spark.createDataset`; `Dataset.fromFile` delegates to
   *  `spark.read.textFile`.  The returned `Dataset[T]` is a real Spark
   *  `Dataset` so all subsequent `.map` / `.filter` / `.flatMap` / etc.
   *  calls are Spark Dataset operations — no wrapper class needed.
   *
   *  Spark-specific terminal op shims:
   *  - `.take(n)` on Spark Dataset returns `Array[T]` not `Dataset[T]`
   *    so we keep it as-is (Spark's own `.take`).
   *  - `.toList` is not a Spark method; user code calling `.toList` will
   *    need `.collect().toList`, which is idiomatic.  We add a Scala
   *    extension method for convenience.
   *  - `.top(n)` is emitted as a helper function `_topDataset`.
   *  - `.count` is already a Spark Dataset method, returns `Long`.
   */
  private def datasetShim(useSharedSparkSchema: Boolean): String =
    val schemaBridge =
      if useSharedSparkSchema then
        """|    trait SscSparkSchemaProvider[T]:
           |      def schema: StructType
           |      def project(df: DataFrame): DataFrame = df
           |
           |    trait LowPrioritySscSparkSchemaProvider:
           |      given fromEncoder[T](using encoder: Encoder[T]): SscSparkSchemaProvider[T] with
           |        def schema: StructType = encoder.schema
           |
           |    object SscSparkSchemaProvider extends LowPrioritySscSparkSchemaProvider:
           |      given fromSparkSchemaCodec[T](using codec: scalascript.typeddata.SparkSchemaCodec[T]): SscSparkSchemaProvider[T] with
           |        def schema: StructType = _sscSparkStructType(codec.schema)
           |        override def project(df: DataFrame): DataFrame =
           |          val fields = codec.schema.fields
           |          if fields.exists(f => f.name != f.scalaFieldName) then
           |            df.select(fields.map(f => df.col(f.name).as(f.scalaFieldName))*)
           |          else df
           |
           |    private def _sscSparkStructType(schema: scalascript.typeddata.SparkSchema): StructType =
           |      StructType(schema.fields.map { f =>
           |        StructField(f.name, _sscSparkDataType(f.dataType), f.nullable)
           |      }.toArray)
           |
           |    private def _sscSparkDataType(t: scalascript.typeddata.SparkSchemaType): DataType =
           |      t match
           |        case scalascript.typeddata.SparkSchemaType.StringType  => StringType
           |        case scalascript.typeddata.SparkSchemaType.BooleanType => BooleanType
           |        case scalascript.typeddata.SparkSchemaType.ByteType    => ByteType
           |        case scalascript.typeddata.SparkSchemaType.ShortType   => ShortType
           |        case scalascript.typeddata.SparkSchemaType.IntegerType => IntegerType
           |        case scalascript.typeddata.SparkSchemaType.LongType    => LongType
           |        case scalascript.typeddata.SparkSchemaType.FloatType   => FloatType
           |        case scalascript.typeddata.SparkSchemaType.DoubleType  => DoubleType
           |        case scalascript.typeddata.SparkSchemaType.DecimalType => DecimalType.SYSTEM_DEFAULT
           |        case scalascript.typeddata.SparkSchemaType.ArrayType(element, containsNull) =>
           |          org.apache.spark.sql.types.ArrayType(_sscSparkDataType(element), containsNull)
           |        case scalascript.typeddata.SparkSchemaType.MapType(key, value, valueContainsNull) =>
           |          org.apache.spark.sql.types.MapType(_sscSparkDataType(key), _sscSparkDataType(value), valueContainsNull)
           |        case scalascript.typeddata.SparkSchemaType.StructType(_) =>
           |          StructType(Nil)
           |
           |    def schemaOf[T](using provider: SscSparkSchemaProvider[T]): StructType =
           |      provider.schema
           |
           |    def fromParquetAs[T](path: String, options: (String, String)*)(using Encoder[T], SscSparkSchemaProvider[T]): Dataset[T] =
           |      summon[SscSparkSchemaProvider[T]].project(spark.read.schema(schemaOf[T]).options(options.toMap).parquet(path)).as[T]
           |
           |    // Catalog read (v1.25 § 9.5 Phase G.4).  Resolves through
           |    // the Spark session catalog: temp views (registered via
           |    // `@TempView` or explicit `.createOrReplaceTempView`),
           |    // global temp views, and managed Hive tables (when the
           |    // `spark-hive-metastore:` or `spark-warehouse:` front-matter
           |    // is set — Phase G.2).  `.as[T]` uses the Phase E encoder
           |    // derivation that's already in scope, so primitives + case
           |    // classes + Option + nested + collections all work without
           |    // further plumbing.
           |    def fromTable[T](name: String)(using Encoder[T], SscSparkSchemaProvider[T]): Dataset[T] =
           |      summon[SscSparkSchemaProvider[T]].project(spark.table(name)).as[T]
           |
           |    def fromJsonAs[T](path: String, options: (String, String)*)(using Encoder[T], SscSparkSchemaProvider[T]): Dataset[T] =
           |      summon[SscSparkSchemaProvider[T]].project(spark.read.schema(schemaOf[T]).options(options.toMap).json(path)).as[T]
           |
           |    def fromCsvAs[T](path: String, options: (String, String)*)(using Encoder[T], SscSparkSchemaProvider[T]): Dataset[T] =
           |      summon[SscSparkSchemaProvider[T]].project(spark.read.schema(schemaOf[T]).options(options.toMap).csv(path)).as[T]
           |""".stripMargin
      else
        """|    def schemaOf[T : Encoder]: StructType =
           |      summon[Encoder[T]].schema
           |
           |    def fromParquetAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] =
           |      spark.read.schema(schemaOf[T]).options(options.toMap).parquet(path).as[T]
           |
           |    // Catalog read (v1.25 § 9.5 Phase G.4).  Resolves through
           |    // the Spark session catalog: temp views (registered via
           |    // `@TempView` or explicit `.createOrReplaceTempView`),
           |    // global temp views, and managed Hive tables (when the
           |    // `spark-hive-metastore:` or `spark-warehouse:` front-matter
           |    // is set — Phase G.2).  `.as[T]` uses the Phase E encoder
           |    // derivation that's already in scope, so primitives + case
           |    // classes + Option + nested + collections all work without
           |    // further plumbing.
           |    def fromTable[T : Encoder](name: String): Dataset[T] =
           |      spark.table(name).as[T]
           |
           |    def fromJsonAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] =
           |      spark.read.schema(schemaOf[T]).options(options.toMap).json(path).as[T]
           |
           |    def fromCsvAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] =
           |      spark.read.schema(schemaOf[T]).options(options.toMap).csv(path).as[T]
           |""".stripMargin
    s"""|  // ── Dataset shim — bridges ScalaScript Dataset API to Spark ──────────
       |  object Dataset:
       |    def of[T : Encoder](items: T*): Dataset[T] =
       |      spark.createDataset(items.toList)
       |
       |    def fromList[T : Encoder](list: List[T]): Dataset[T] =
       |      spark.createDataset(list)
       |
       |    def fromFile(path: String): Dataset[String] =
       |      spark.read.textFile(path)
       |
       |    def fromPath[T : Encoder](glob: String)(implicit ev: String => T): Dataset[T] =
       |      spark.read.textFile(glob).map(ev)
       |
       |    // Reader convenience shims (v1.25 § 9.5 Phase C.3 slices 5–6).
       |    // Each delegates to Spark's typed reader for a well-known
       |    // serialization format.  Return type is `DataFrame` (= `Dataset[Row]`)
       |    // because Spark's readers infer schema at load time; users who want
       |    // a typed view chain `.as[CaseClass]` themselves after inspecting
       |    // `.schema`.
       |    //
       |    // Variadic `(String, String)*` option pairs let users set common
       |    // reader flags inline:
       |    //   Dataset.fromCsv("/data.csv", "header" -> "true", "inferSchema" -> "true")
       |    // Zero options collapses to the bare reader — `Dataset.fromCsv(path)`
       |    // still works exactly like the pre-slice-6 shape.
       |    def fromParquet(path: String, options: (String, String)*): DataFrame =
       |      spark.read.options(options.toMap).parquet(path)
       |
       |    def fromJson(path: String, options: (String, String)*): DataFrame =
       |      spark.read.options(options.toMap).json(path)
       |
       |    def fromCsv(path: String, options: (String, String)*): DataFrame =
       |      spark.read.options(options.toMap).csv(path)
       |
       |    // Schema bridge — closes v1.25 § 9.5 Phase C.3 slice 9.
       |    //
       |    // `schemaOf[T]` derives a Spark `StructType` from a case-class
       |    // declaration via the standard `Encoder[T]` instance that
       |    // `spark.implicits._` (in scope inside `@main def runSparkJob`)
       |    // already brings in.  Equivalent to writing
       |    // `Encoders.product[T].schema` directly, but discoverable
       |    // alongside the `Dataset` constructors and parametric on
       |    // any Encoder, not just `Product` types.
       |    //
       |    // `fromXAs[T]` are the typed cousins of `fromX`: instead of
       |    // returning a schema-less DataFrame and forcing the caller to
       |    // chain `.as[T]`, they pin the schema at read time
       |    // (`.schema(schemaOf[T])`) AND apply `.as[T]` so the result
       |    // is a real typed `Dataset[T]`.  For CSV the explicit schema
       |    // is the only correct path (Spark otherwise reads every
       |    // column as `String`); for JSON it bypasses Spark's two-pass
       |    // schema-inference scan; for Parquet it acts as a column
       |    // projection (only the case-class fields are read off disk).
       |$schemaBridge
       |
       |  // Extension methods that bridge ScalaScript Dataset idioms to Spark
       |  // Dataset ops.  Defined here so they're in scope throughout the @main
       |  // body without requiring any import by the user.
       |  extension [T](ds: Dataset[T])
       |    /** `.toList` on a Spark Dataset — collect to driver and convert. */
       |    def toList: List[T] = ds.collect().toList
       |
       |    /** `.top(n)` — n largest by natural ordering, descending.
       |     *  For simple element types (Int, Long, Double, String) this
       |     *  compiles; complex types need an explicit Ordering. */
       |    def top(n: Int)(using ord: Ordering[T]): List[T] =
       |      ds.collect().toList.sorted(using ord.reverse).take(n)
       |
       |    /** `.takeOrdered(n)` — n smallest by natural ordering, ascending. */
       |    def takeOrdered(n: Int)(using ord: Ordering[T]): List[T] =
       |      ds.collect().toList.sorted(using ord).take(n)
       |
       |    /** `.toScalaList` alias for when `.toList` conflicts. */
       |    def toScalaList: List[T] = ds.collect().toList
       |
       |    // Writer convenience shims (v1.25 § 9.5 Phase C.3 slice 7).
       |    // Symmetric to the `Dataset.fromX(path, opts*)` readers from
       |    // slices 5–6 — same name pattern, same option-pair tail.
       |    // Each delegates to Spark's `DataFrameWriter.options(...).X(path)`
       |    // and returns `Unit` since the actual side-effect is the write.
       |    //
       |    // The `mode` knob (overwrite / append / error / ignore) is NOT
       |    // exposed through the options map — Spark treats `mode` as a
       |    // dedicated writer method.  Users who need it chain
       |    // `ds.write.mode("overwrite").parquet(path)` directly; the shim
       |    // covers the 80%-case ad-hoc dump.
       |    def toParquet(path: String, options: (String, String)*): Unit =
       |      ds.write.options(options.toMap).parquet(path)
       |
       |    def toJson(path: String, options: (String, String)*): Unit =
       |      ds.write.options(options.toMap).json(path)
       |
       |    def toCsv(path: String, options: (String, String)*): Unit =
       |      ds.write.options(options.toMap).csv(path)
       |
       |""".stripMargin

  // ── v2.1.3 — DStream Spark shim ───────────────────────────────────────────
  //
  // Emitted inside `@main def runSparkJob()` when `needsDStream` is true.
  // Provides the full DStream pipeline DSL (v2.1.1 + v2.1.2 operators) backed
  // by driver-local `Seq[Any]` for bounded `InMemory.source` inputs.
  //
  // For distributed execution against real unbounded sources (Kafka, Spark
  // Structured Streaming), a Spark-Dataset-backed DStream class will be
  // provided in a future iteration; for bounded conformance tests this shim
  // produces the same results as `Backend.Direct` / `Backend.Native`.
  //
  // Indentation: every line carries two leading spaces so the block sits
  // correctly inside `@main def runSparkJob(): Unit =`.
  private val dstreamSparkShim: String =
    """|
       |  // ── DStream Spark shim (v2.1.3) ────────────────────────────────────
       |  // Provides the DStream pipeline DSL backed by driver-local Seq[Any].
       |  // Bounded InMemory sources run on the driver; results are identical
       |  // to Backend.Direct / Backend.Native.
       |
       |  case class KV[K, V](key: K, value: V)
       |
       |  object Window:
       |    def fixed(ms: Long): Any            = ("fixed", ms)
       |    def sliding(ms: Long, p: Long): Any  = ("sliding", ms, p)
       |    def session(gapMs: Long): Any        = ("session", gapMs)
       |    val global: Any                      = "global"
       |
       |  object Trigger:
       |    val afterWatermark: Any                        = "afterWatermark"
       |    def afterProcessingTime(ms: Long): Any         = ("afterProcessingTime", ms)
       |    def afterCount(n: Int): Any                    = ("afterCount", n)
       |    def repeatedly(t: Any): Any                    = ("repeatedly", t)
       |
       |  object WatermarkStrategy:
       |    val monotonicallyIncreasing: Any               = "monotonicallyIncreasing"
       |    val atEnd: Any                                 = "atEnd"
       |    def boundedOutOfOrder(lagMs: Long): Any        = ("boundedOutOfOrder", lagMs)
       |
       |  object AccumulationMode:
       |    val Discarding: String   = "Discarding"
       |    val Accumulating: String = "Accumulating"
       |
       |  object Backend:
       |    val Direct: String       = "Direct"
       |    val Native: String       = "Native"
       |    val Spark: String        = "Spark"
       |    val KafkaStreams: String  = "KafkaStreams"
       |    val Kafka: String        = "KafkaStreams"
       |    val Flink: String        = "Flink"
       |    val Beam: String         = "Beam"
       |
       |  case class PipelineResult(state: String, __results: Seq[Any]):
       |    def waitUntilFinish(): String = state
       |    def cancel(): Unit = ()
       |
       |  type DSource[T] = Seq[T]
       |
       |  class DStream[T](private val _elems: Seq[T]):
       |    def map[U](f: T => U): DStream[U]           = new DStream(_elems.map(f))
       |    def filter(p: T => Boolean): DStream[T]      = new DStream(_elems.filter(p))
       |    def flatMap[U](f: T => IterableOnce[U]): DStream[U] =
       |      new DStream(_elems.flatMap(f))
       |    def keyBy[K](keyFn: T => K): DStream[KV[K, T]] =
       |      new DStream(_elems.map(v => KV(keyFn(v), v)))
       |    def combinePerKey(f: (Any, Any) => Any): DStream[Any] =
       |      val groups = collection.mutable.LinkedHashMap[Any, Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, v) =>
       |          groups.get(k) match
       |            case Some(acc) => groups(k) = f(acc, v)
       |            case None      => groups(k) = v
       |        case _ =>
       |      new DStream(groups.toSeq.map((k, v) => KV(k, v)))
       |    def merge(other: DStream[T]): DStream[T]    = new DStream(_elems ++ other._elems)
       |    def mapWithTimestamp[U](f: (T, Long) => U): DStream[U] =
       |      new DStream(_elems.map(v => f(v, System.currentTimeMillis())))
       |    def assignTimestamps(f: T => Long): DStream[T] = this
       |    def window(fn: Any): DStream[T]              = this
       |    def withTrigger(t: Any): DStream[T]          = this
       |    def withAllowedLateness(d: Any): DStream[T]  = this
       |    def withWatermark(s: Any): DStream[T]        = this
       |    def timerProcessing(dMs: Long)(f: Any => Iterable[Any]): DStream[Any] =
       |      val keys = collection.mutable.LinkedHashSet[Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, _) => keys += k
       |        case _        =>
       |      new DStream(keys.toSeq.flatMap(k => f(k).map(v => KV(k, v))))
       |    def timerEventTime(tsMs: Long)(f: Any => Iterable[Any]): DStream[Any] =
       |      val keys = collection.mutable.LinkedHashSet[Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, _) => keys += k
       |        case _        =>
       |      new DStream(keys.toSeq.flatMap(k => f(k).map(v => KV(k, v))))
       |    def statefulMap(initState: Any)(f: (Any, Any) => (Any, Any)): DStream[Any] =
       |      val states = collection.mutable.LinkedHashMap[Any, Any]()
       |      val results = collection.mutable.ListBuffer[Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, v) =>
       |          val s = states.getOrElse(k, initState)
       |          val (ns, out) = f(s, v)
       |          states(k) = ns
       |          results += KV(k, out)
       |        case _ =>
       |      new DStream(results.toSeq)
       |    def statefulFlatMap(initState: Any)(f: (Any, Any) => (Any, Iterable[Any])): DStream[Any] =
       |      val states = collection.mutable.LinkedHashMap[Any, Any]()
       |      val results = collection.mutable.ListBuffer[Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, v) =>
       |          val s = states.getOrElse(k, initState)
       |          val (ns, outs) = f(s, v)
       |          states(k) = ns
       |          results ++= outs.map(o => KV(k, o))
       |        case _ =>
       |      new DStream(results.toSeq)
       |    def broadcastState(stateStream: DStream[Any]): DStream[Any] =
       |      val stateMap = collection.mutable.LinkedHashMap[Any, Any]()
       |      for elem <- stateStream._elems do elem match
       |        case KV(k, v) => stateMap(k) = v
       |        case v        => stateMap(v) = v
       |      new DStream(_elems.map(elem => (elem, stateMap.toMap)))
       |    // ── v2.1.8 — Side inputs / side outputs ──────────────────────────
       |    def withSideInput(si: SideInput[Any]): DStream[Any] =
       |      new DStream(_elems.flatMap(e => si.elements.map(b => (e, b))))
       |    def sideOutput(tag: Any): (DStream[Any], DStream[Any]) =
       |      val sideFn: Any => Option[Any] = tag match
       |        case OutputTag(_, fn) => fn
       |        case f: Function1[?, ?] => f.asInstanceOf[Any => Option[Any]]
       |        case _ => _ => None
       |      val sideElems = _elems.flatMap(e => sideFn(e))
       |      (this.asInstanceOf[DStream[Any]], new DStream(sideElems))
       |    // ── v2.1.9 — Windowed joins + flatten ────────────────────────────
       |    def join(other: DStream[Any]): DStream[Any] =
       |      val rightMap = collection.mutable.LinkedHashMap[Any, List[Any]]()
       |      for elem <- other._elems do elem match
       |        case KV(k, v) => rightMap(k) = rightMap.getOrElse(k, Nil) :+ v
       |        case _ =>
       |      val results = collection.mutable.ListBuffer[Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, v) => rightMap.getOrElse(k, Nil).foreach(r => results += KV(k, (v, r)))
       |        case _ =>
       |      new DStream(results.toSeq)
       |    def leftOuterJoin(other: DStream[Any]): DStream[Any] =
       |      val rightMap = collection.mutable.LinkedHashMap[Any, List[Any]]()
       |      for elem <- other._elems do elem match
       |        case KV(k, v) => rightMap(k) = rightMap.getOrElse(k, Nil) :+ v
       |        case _ =>
       |      val results = collection.mutable.ListBuffer[Any]()
       |      for elem <- _elems do elem match
       |        case KV(k, v) =>
       |          val rs = rightMap.getOrElse(k, Nil)
       |          if rs.isEmpty then results += KV(k, (v, None))
       |          else rs.foreach(r => results += KV(k, (v, Some(r))))
       |        case _ =>
       |      new DStream(results.toSeq)
       |    def rightOuterJoin(other: DStream[Any]): DStream[Any] =
       |      val leftMap = collection.mutable.LinkedHashMap[Any, List[Any]]()
       |      for elem <- _elems do elem match
       |        case KV(k, v) => leftMap(k) = leftMap.getOrElse(k, Nil) :+ v
       |        case _ =>
       |      val results = collection.mutable.ListBuffer[Any]()
       |      for elem <- other._elems do elem match
       |        case KV(k, v) =>
       |          val ls = leftMap.getOrElse(k, Nil)
       |          if ls.isEmpty then results += KV(k, (None, v))
       |          else ls.foreach(l => results += KV(k, (Some(l), v)))
       |        case _ =>
       |      new DStream(results.toSeq)
       |    def flatten: DStream[Any] =
       |      new DStream(_elems.flatMap {
       |        case s: DStream[?] => s._elems
       |        case s: Seq[?]     => s.asInstanceOf[Seq[Any]]
       |        case other         => List(other)
       |      })
       |    def write(sink: Any): DStream[T]             = this
       |    def run(backend: Any): PipelineResult        = PipelineResult("Done", _elems)
       |    def runOpts(b: Any, o: Any): PipelineResult  = PipelineResult("Done", _elems)
       |    def runToList(): Seq[T]                      = _elems
       |    def runFold[U](z: U)(f: (U, T) => U): U     = _elems.foldLeft(z)(f)
       |    def runForeach(f: T => Unit): Unit           = _elems.foreach(f)
       |    def runCount(): Long                         = _elems.size.toLong
       |    def requires(): List[String] =
       |      List("AtLeastOnce", "EventTime", "WatermarkPerfect", "KeyedState")
       |
       |  object Pipeline:
       |    def create(name: String): PipelineBuilder = new PipelineBuilder(name)
       |
       |  class PipelineBuilder(val name: String):
       |    def read[T](src: DSource[T]): DStream[T] = new DStream(src)
       |
       |  object InMemory:
       |    def source[T](elements: Seq[T]): DSource[T] = elements
       |    def sourceWithTimestamps[T](elements: Seq[(T, Long)]): DSource[T] =
       |      elements.map(_._1)
       |    def sink[T](): Any = ()
       |    def runAndCollect[T](stream: DStream[T]): Seq[T] = stream.runToList()
       |
       |  object DSource:
       |    def fromLocalSource[A](src: Any): DSource[A] = Seq.empty[A]
       |
       |  def runPipeline[T](stream: DStream[T], backend: Any): PipelineResult =
       |    stream.run(backend)
       |  def runPipelineOpts[T](stream: DStream[T], b: Any, o: Any): PipelineResult =
       |    stream.run(b)
       |
       |  // ── v2.1.6 — Production connector stubs ──────────────────────────────
       |  // Kafka.source / Files.source / Jdbc.source / Pulsar.source / Kinesis.source
       |  // return empty Seq[T] in the bounded shim (no cluster required).
       |  // Live sources require the appropriate connector dep and cluster env var.
       |
       |  object Kafka:
       |    def source[T](brokers: String = "", topic: String = "", groupId: String = "ssc",
       |                  startOffset: Any = "Latest"): DSource[T]              = Seq.empty[T]
       |    def sourceAssigned[T](brokers: String, assignments: Any): DSource[T] = Seq.empty[T]
       |    def changelog[T](brokers: String, topic: String): DSource[T]         = Seq.empty[T]
       |    def sink[T](brokers: String, topic: String): Any                      = ()
       |
       |  object Files:
       |    def source[T](path: String, format: Any = "Text"): DSource[T] = Seq.empty[T]
       |    def sink[T](path: String, format: Any = "Text"): Any          = ()
       |
       |  object FileFormat:
       |    val Text: String    = "Text"
       |    val Json: String    = "Json"
       |    def Csv(header: Boolean = true): Any = ("Csv", header)
       |    val Parquet: String = "Parquet"
       |    val Avro: String    = "Avro"
       |
       |  object Jdbc:
       |    def source[T](url: String, table: String, query: Option[String] = None,
       |                  partitions: Int = 1): DSource[T] = Seq.empty[T]
       |    def sink[T](url: String, table: String, batchSz: Int = 1000): Any = ()
       |
       |  object Pulsar:
       |    def source[T](serviceUrl: String, topic: String,
       |                  subscription: String): DSource[T] = Seq.empty[T]
       |    def sink[T](serviceUrl: String, topic: String): Any = ()
       |
       |  object Kinesis:
       |    def source[T](stream: String, region: String): DSource[T] = Seq.empty[T]
       |    def sink[T](stream: String, region: String): Any          = ()
       |
       |  type DSink[T] = Any
       |
       |  // ── v2.1.7 — Stateful processing state types ───────────────────────────
       |  class ValueState[T](private var _v: Option[T] = None):
       |    def get(): Option[T] = _v
       |    def set(v: T): Unit  = _v = Some(v)
       |    def clear(): Unit    = _v = None
       |    def isEmpty: Boolean = _v.isEmpty
       |
       |  class MapState[K, V]:
       |    private val _m = collection.mutable.HashMap.empty[K, V]
       |    def get(k: K): Option[V]        = _m.get(k)
       |    def put(k: K, v: V): Unit       = _m(k) = v
       |    def remove(k: K): Unit          = { _m.remove(k); () }
       |    def contains(k: K): Boolean     = _m.contains(k)
       |    def entries(): Iterable[(K, V)] = _m.toList
       |    def isEmpty: Boolean            = _m.isEmpty
       |    def clear(): Unit               = _m.clear()
       |
       |  class ListState[T]:
       |    private val _l = collection.mutable.ListBuffer.empty[T]
       |    def add(v: T): Unit    = _l += v
       |    def get(): Iterable[T] = _l.toList
       |    def clear(): Unit      = _l.clear()
       |    def isEmpty: Boolean   = _l.isEmpty
       |
       |  class BagState[T]:
       |    private val _b = collection.mutable.ListBuffer.empty[T]
       |    def add(v: T): Unit    = _b += v
       |    def get(): Iterable[T] = _b.toList
       |    def clear(): Unit      = _b.clear()
       |    def isEmpty: Boolean   = _b.isEmpty
       |
       |  case class StateContext[K, S](key: K, state: S)
       |
       |  case class KeyedStateSpec[K, S](init: S)
       |  object KeyedStateSpec:
       |    def value[K, S](init: S): KeyedStateSpec[K, S] = KeyedStateSpec(init)
       |
       |  // ── v2.1.8 — Side inputs / side outputs ────────────────────────────────
       |  case class SideInput[T](elements: Seq[T])
       |  object SideInput:
       |    def of[T](stream: DStream[T]): SideInput[T]       = SideInput(stream.runToList())
       |    def singleton[T](value: T): SideInput[T]          = SideInput(Seq(value))
       |    def asMap[K, V](stream: DStream[Any]): SideInput[Any] =
       |      val m = stream.runToList().collect { case KV(k, v) => (k, v) }.toMap
       |      SideInput(Seq(m))
       |
       |  case class OutputTag[B](name: String, filter: Any => Option[Any] = _ => None)
       |  object OutputTag:
       |    def apply[B](name: String): OutputTag[B]                       = new OutputTag(name, _ => None)
       |    def withFilter[B](name: String)(fn: Any => Option[Any]): OutputTag[B] =
       |      new OutputTag(name, fn)
       |
       |""".stripMargin
