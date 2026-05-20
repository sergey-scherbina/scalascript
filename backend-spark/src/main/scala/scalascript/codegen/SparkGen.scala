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
      module:       Module,
      baseDir:      Option[os.Path]     = None,
      sparkVersion: String              = DefaultVersion,
      sparkMaster:  String              = DefaultMaster,
      extraConfig:  Map[String, String] = Map.empty,
      appName:      String              = DefaultAppName,
      scalaVersion: String              = DefaultScalaVersion
  ): String =
    SparkGen(baseDir, sparkVersion, sparkMaster, extraConfig, appName, scalaVersion).genModule(module)

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
    baseDir:      Option[os.Path]     = None,
    sparkVersion: String              = SparkGen.DefaultVersion,
    sparkMaster:  String              = SparkGen.DefaultMaster,
    extraConfig:  Map[String, String] = Map.empty,
    appName:      String              = SparkGen.DefaultAppName,
    scalaVersion: String              = SparkGen.DefaultScalaVersion
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

    val sb = StringBuilder()

    // Generated file header — documents the Spark version and run instructions.
    sb.append(s"// Generated by ScalaScript — Apache Spark $sparkVersion, Scala $scalaVersion\n")
    sb.append(s"// To run: scala-cli run this.scala\n\n")

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
    sb.append(phaseEShim)

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
    // User-supplied `spark-config:` front-matter map (v1.25 § 9.5 Phase C.3
    // slice 3) — emit one `.config(k, v)` per entry, sorted by key for
    // deterministic source.  Values are passed through verbatim and
    // escaped for embedding in a Scala string literal.  A user's entry
    // for `spark.ui.enabled` / `spark.sql.shuffle.partitions` overrides
    // the defaults above because Spark's builder takes last-write-wins.
    extraConfig.toList.sortBy(_._1).foreach { (k, v) =>
      sb.append(s"""    .config("${escape(k)}", "${escape(v)}")\n""")
    }
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
    sb.append(datasetShim)

    // User blocks — indented two spaces to sit inside `@main def`.
    //
    // Per-block, run `extractSqlFns` to find `@SqlFn`-marked defs
    // (v1.25 § 9.5 Phase D — UDF bridge); the helper returns the
    // source with the annotation stripped plus a list of
    // `SqlFnSig(name, paramTypes, returnType)`.  For each signature
    // emit a Java-`UDFN`-shaped `spark.udf.register` call right
    // after the cleaned source — Phase E revival sidesteps Spark's
    // TypeTag-bound typed `register[RT : TypeTag, ...]` overload
    // (which Scala 3 cannot satisfy) by using the Java functional-
    // interface form that takes an explicit `DataType`.
    //
    // Translated sql blocks contain no `@SqlFn` markers, so
    // `extractSqlFns` is a no-op on them.
    blocks.foreach { block =>
      val (cleaned, udfSigs) = SparkGen.extractSqlFns(block.src)
      val composed =
        if udfSigs.isEmpty then cleaned
        else
          val registrations = udfSigs.map(emitUdfRegistration).mkString("\n\n")
          cleaned.stripTrailing + "\n" + registrations
      val indented = indentBlock(composed)
      sb.append(indented.stripTrailing())
      sb.append("\n\n")
    }

    // Auto-call main entry if declared in front-matter.
    val mainEntry = module.manifest
      .flatMap(_.raw.get("main"))
      .collect { case s: String => s }
    mainEntry.foreach { name => sb.append(s"  $name()\n") }

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
   *  The shim assumes the runtime is **Scala 3.7.1 + Spark 4.x +
   *  JDK 17/21 with the standard Spark add-opens** — Scala 3.8.x has
   *  a regression in its TASTy bridge to Spark `_2.13` reflection
   *  that breaks every `ExpressionEncoder` (see SPEC § 9.5 "Scala 3
   *  / Spark 2.13 interop"). */
  private val phaseEShim: String =
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
       |
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
  private def datasetShim: String =
    """|  // ── Dataset shim — bridges ScalaScript Dataset API to Spark ──────────
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
       |    def schemaOf[T : Encoder]: StructType =
       |      summon[Encoder[T]].schema
       |
       |    def fromParquetAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] =
       |      spark.read.schema(schemaOf[T]).options(options.toMap).parquet(path).as[T]
       |
       |    def fromJsonAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] =
       |      spark.read.schema(schemaOf[T]).options(options.toMap).json(path).as[T]
       |
       |    def fromCsvAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] =
       |      spark.read.schema(schemaOf[T]).options(options.toMap).csv(path).as[T]
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
       |      ds.collect().toList.sorted(ord.reverse).take(n)
       |
       |    /** `.takeOrdered(n)` — n smallest by natural ordering, ascending. */
       |    def takeOrdered(n: Int)(using ord: Ordering[T]): List[T] =
       |      ds.collect().toList.sorted.take(n)
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
