package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Apache Spark target (target id `"spark"`).
 *
 *  Phase A of v1.25's Spark roadmap (§ 9.5 of `SPEC.md`): replaces the
 *  `Main.runViaSparkBackend` side-path with a regular `Backend` so the
 *  target shows up in `--list-backends`, can be selected via
 *  `--backend spark`, participates in `CapabilityCheck`, and accepts
 *  options through `BackendOptions.extra` instead of bespoke CLI
 *  plumbing.
 *
 *  Pipeline (mirrors the historical `runViaSparkBackend`):
 *
 *    1. Denormalize the IR module back to `ast.Module` — `SparkGen`
 *       consumes the AST shape.
 *    2. Generate the Scala 3 + Spark source via
 *       `SparkGen.generate(astModule, baseDir, sparkVersion)`.
 *    3. Persist to a deterministic temp file (`/tmp/ssc-spark-<hash>.scala`)
 *       so `scala-cli` can read it and so the user can inspect it.
 *    4. Shell out to `scala-cli run <tempfile>
 *         --dep org.apache.spark:spark-core_2.13:<version>
 *         --dep org.apache.spark:spark-sql_2.13:<version>
 *         --scala 3`.
 *       (Spark publishes only `_2.13` JARs on Maven Central — Scala 3
 *       reads them via the TASTy bridge, so the suffix is pinned
 *       explicitly rather than using `::` which would expand to a
 *       non-existent `_3` artifact.)
 *    5. Return `CompileResult.Executed(stdout, stderr, exit)`.
 *
 *  Resolution priority for both `sparkVersion` and `sparkMaster` —
 *  the front-matter side (`spark-version:`, `spark-master:`) is read
 *  at the call site *before* Normalize strips `Manifest.raw`; the
 *  resolved strings are threaded into `BackendOptions.extra`.  This
 *  backend therefore sees them as single strings in `extras`, falling
 *  back to defaults only when no caller supplied a value:
 *
 *    `sparkVersion` — opts.extra.get(...) → SparkGen.DefaultVersion
 *    `sparkMaster`  — opts.extra.get(...) → SparkGen.DefaultMaster
 *
 *  Phase B (this iteration) — `sparkMaster` parameterisation enables
 *  `--spark-master local[4]`, `--spark-master spark://host:7077`, etc.
 *  Phase B.2 (open) — `ssc submit` packages a fat JAR and invokes
 *  `spark-submit`; today cluster master URLs still go through
 *  `scala-cli run` and rely on the driver being able to ship classes
 *  to executors itself (works for thin-classpath Spark Standalone but
 *  not YARN / K8s in production). */
/** Codec for the `sparkConfig` entry in `BackendOptions.extra`.
 *
 *  Phase C.3 slice 3 lets users declare ad-hoc Spark configuration in
 *  front-matter:
 *
 *    ---
 *    backend: spark
 *    spark-config:
 *      spark.executor.memory: 4g
 *      spark.sql.shuffle.partitions: 200
 *    ---
 *
 *  `BackendOptions.extra` is `Map[String, String]` (free-form, per the
 *  SPI), so the whole config map travels as a single value under the
 *  `sparkConfig` key.  Encoding is one entry per newline, key and value
 *  separated by the first `=` — robust because Spark config keys are
 *  dotted identifiers that never contain `=` and the format survives
 *  values with `=` in them (rare but legal). */
object SparkBackend:

  val SparkConfigOption:        String = "sparkConfig"
  val SparkAppNameOption:       String = "sparkAppName"
  // Phase G.2 — Hive metastore + warehouse front-matter keys threaded
  // through `BackendOptions.extra`.  Resolution order at the CLI layer:
  // front-matter `spark-hive-metastore:` / `spark-warehouse:` →
  // SparkGen `hiveMetastore` / `warehouse` constructor params → emit
  // the corresponding `.config(...)` lines + `.enableHiveSupport()` +
  // `spark-hive_2.13` dep.
  val SparkHiveMetastoreOption: String = "sparkHiveMetastore"
  val SparkWarehouseOption:     String = "sparkWarehouse"

  def encodeSparkConfig(m: Map[String, String]): String =
    m.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("\n")

  def decodeSparkConfig(encoded: String): Map[String, String] =
    if encoded.isEmpty then Map.empty
    else
      encoded.linesIterator.flatMap { line =>
        val idx = line.indexOf('=')
        if idx <= 0 then None
        else Some(line.substring(0, idx) -> line.substring(idx + 1))
      }.toMap

  /** Convert a `spark-config:` YAML map (as it lands in
   *  `Manifest.raw["spark-config"]` — `java.util.Map[?, ?]` from
   *  SnakeYAML) into the `Map[String, String]` shape this codec
   *  understands.  Non-string keys are dropped; non-string values are
   *  coerced via `toString` so numeric YAML literals
   *  (`spark.sql.shuffle.partitions: 200`) survive intact. */
  def fromYamlMap(raw: Any): Map[String, String] =
    raw match
      case m: java.util.Map[?, ?] =>
        import scala.jdk.CollectionConverters.*
        m.asScala.iterator.collect {
          case (k: String, v) if v != null => k -> v.toString
        }.toMap
      case _ => Map.empty

class SparkBackend extends Backend:
  def id:              String                               = "spark"
  def displayName:     String                               = "Apache Spark (Scala 3 + scala-cli)"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = SparkCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set.empty

  // core-min check-autoload: a file importing `scalascript.spark.*` maps to this backend (it's also
  // on the CLI classpath, so its preludeSymbols already resolve — declared for consistency/future opt-in).
  override def providesImports: List[String] = List("scalascript.spark")

  /** core-min-advanced-optin: the Spark surface names, moved off the hardcoded Typer
   *  `pluginObjects`/`pluginBuiltins`. The Spark backend is on the CLI classpath, so these
   *  resolve for `ssc check` whenever Spark codegen is available. */
  override def preludeSymbols: List[ir.ExportedSymbol] = List(
    ir.ExportedSymbol("spark", "spark", "object", "Any"),
    ir.ExportedSymbol("PipelineModel", "PipelineModel", "def", "Any"),
  )

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule    = Denormalize(module)
    val sparkVersion = opts.extra.getOrElse("sparkVersion", SparkGen.DefaultVersion)
    val sparkMaster  = opts.extra.getOrElse("sparkMaster",  SparkGen.DefaultMaster)
    val extraConfig  = opts.extra.get(SparkBackend.SparkConfigOption)
      .map(SparkBackend.decodeSparkConfig)
      .getOrElse(Map.empty)
    val appName      = opts.extra.getOrElse(SparkBackend.SparkAppNameOption, SparkGen.DefaultAppName)
    // Phase G.2 — Hive front-matter keys travel through `extra` as
    // single optional strings.  `Option`-valued so a downstream `None`
    // unambiguously means "no key set in front-matter"; an empty
    // string would be ambiguous (could mean "intentional empty
    // metastore URI", which Spark would later interpret as no URI
    // anyway, but the Option shape keeps codegen layered cleanly).
    val hiveMetastore = opts.extra.get(SparkBackend.SparkHiveMetastoreOption)
    val warehouse     = opts.extra.get(SparkBackend.SparkWarehouseOption)
    val baseDir      = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code         = SparkGen.generate(
      astModule,
      baseDir       = baseDir,
      sparkVersion  = sparkVersion,
      sparkMaster   = sparkMaster,
      extraConfig   = extraConfig,
      appName       = appName,
      hiveMetastore = hiveMetastore,
      warehouse     = warehouse
    )
    val hash         = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val tmpFile      = os.Path(s"/tmp/ssc-spark-$hash.scala")
    os.write.over(tmpFile, code)
    System.err.println(s"[spark] Spark $sparkVersion (master=$sparkMaster) — generated: $tmpFile")
    // The emitted source carries `//> using scala`, `//> using dep`, and
    // `//> using javaOpt` directives (v1.25 § 9.5 Phase E) — scala-cli
    // picks up everything from the file itself, so the command line
    // stays empty of dep/scala overrides.  Means `ssc run --backend spark`
    // and `scala-cli run /tmp/ssc-spark-<hash>.scala` behave identically.
    val cmd = List("scala-cli", "run", tmpFile.toString)
    // Run scala-cli inheriting stdout/stderr — Spark's own logging and the
    // user program's println go straight to the terminal; capturing into
    // CompileResult.Executed strings would buffer indefinitely and lose
    // the interactive feel of `ssc run`.  Return empty stdout/stderr and
    // just the exit code; the CLI prints nothing on top of the inherited
    // output.
    val exit = scala.sys.process.Process(cmd).!
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)
