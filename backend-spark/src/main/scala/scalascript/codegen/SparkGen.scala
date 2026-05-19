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

  def generate(
      module:       Module,
      baseDir:      Option[os.Path]     = None,
      sparkVersion: String              = DefaultVersion,
      sparkMaster:  String              = DefaultMaster,
      extraConfig:  Map[String, String] = Map.empty,
      appName:      String              = DefaultAppName
  ): String =
    SparkGen(baseDir, sparkVersion, sparkMaster, extraConfig, appName).genModule(module)

  /** A collected ScalaScript code block ready for emission. */
  private[codegen] case class Block(src: String)

private class SparkGen(
    baseDir:      Option[os.Path]     = None,
    sparkVersion: String              = SparkGen.DefaultVersion,
    sparkMaster:  String              = SparkGen.DefaultMaster,
    extraConfig:  Map[String, String] = Map.empty,
    appName:      String              = SparkGen.DefaultAppName
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

    val sb = StringBuilder()

    // Generated file header — documents the Spark version and run instructions.
    sb.append(s"// Generated by ScalaScript — Apache Spark $sparkVersion\n")
    sb.append(s"// To run: scala-cli run this.scala")
    sb.append(s""" --dep "org.apache.spark::spark-core:$sparkVersion"""")
    sb.append(s""" --dep "org.apache.spark::spark-sql:$sparkVersion"""")
    sb.append("\n\n")

    // scala-cli `//> using dep` directives from front-matter (same pattern
    // as JvmGen).  Spark deps are added separately at call site via
    // `--dep` flags so we don't duplicate the giant JARs here.
    module.manifest.foreach { m =>
      m.dependencies.foreach { (dep, version) =>
        if !version.startsWith("http://") && !version.startsWith("https://") then
          sb.append(s"""//> using dep "$dep:$version"\n""")
      }
    }

    // Spark imports.
    sb.append(sparkImports)

    // @main wrapper opens here.
    sb.append("\n@main def runSparkJob(): Unit =\n")
    sb.append("  val spark = SparkSession.builder()\n")
    sb.append(s"""    .appName("${escape(appName)}")\n""")
    sb.append(s"""    .master("$sparkMaster")\n""")
    sb.append("    .config(\"spark.ui.enabled\", \"false\")\n")
    sb.append("    .config(\"spark.sql.shuffle.partitions\", \"4\")\n")
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
    sb.append("  import spark.implicits._\n")
    // Suppress verbose Spark logging in local mode.
    sb.append("  org.apache.log4j.Logger.getRootLogger.setLevel(org.apache.log4j.Level.WARN)\n\n")

    // Emit the Dataset companion shim so user code `Dataset.of(...)` /
    // `Dataset.fromList(...)` resolves without any user-visible changes.
    sb.append(datasetShim)

    // User blocks — indented two spaces to sit inside `@main def`.
    blocks.foreach { block =>
      val indented = indentBlock(block.src)
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
       |""".stripMargin
