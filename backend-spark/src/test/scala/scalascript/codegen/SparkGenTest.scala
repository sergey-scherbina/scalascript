package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Unit tests for SparkGen — verify generated Scala 3 + Spark source structure.
 *
 *  These tests do NOT run a SparkSession (too slow, requires Spark JARs at
 *  test time with `% "provided"` scope).  Instead they parse a ScalaScript
 *  snippet, generate the Spark Scala source, and assert the expected
 *  structural patterns are present.
 */
class SparkGenTest extends AnyFunSuite:

  private def gen(
      ssc:          String,
      sparkVersion: String = SparkGen.DefaultVersion,
      sparkMaster:  String = SparkGen.DefaultMaster
  ): String =
    val module = Parser.parse(ssc)
    SparkGen.generate(module, sparkVersion = sparkVersion, sparkMaster = sparkMaster)

  // ── Basic structure ───────────────────────────────────────────────────────

  test("default Spark version is 4.0.0") {
    assert(SparkGen.DefaultVersion == "4.0.0")
  }

  test("default Spark master is local[*]") {
    assert(SparkGen.DefaultMaster == "local[*]")
  }

  test("sparkMaster parameter overrides the emitted master URL (Phase B)") {
    val src =
      """|# Test
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin
    // local[4]: bounded executor count for micro-benchmarks
    assert(gen(src, sparkMaster = "local[4]").contains(""".master("local[4]")"""))
    // spark://: cluster master URL (the Spark backend ships the driver via
    // scala-cli; production cluster submission requires Phase B.2's
    // `ssc submit` + fat JAR).
    assert(gen(src, sparkMaster = "spark://prod.example.com:7077")
      .contains(""".master("spark://prod.example.com:7077")"""))
    // yarn / k8s: same plumbing, different runtime.
    assert(gen(src, sparkMaster = "yarn").contains(""".master("yarn")"""))
    assert(gen(src, sparkMaster = "k8s://cluster.local:6443")
      .contains(""".master("k8s://cluster.local:6443")"""))
  }

  test("default master local[*] still emitted when sparkMaster is not specified") {
    val src = "# Test\n```scalascript\nval x = 1\n```\n"
    assert(gen(src).contains(""".master("local[*]")"""))
  }

  // ── Phase C.3 slice 8: adaptive defaults (local-only) ────────────────────

  test("local[*] master emits laptop-friendly defaults") {
    // Two configs are local-mode opinions: disable UI (port), small
    // shuffle partitions count (multi-core laptop, small inputs).
    // Both arrive verbatim with the default master.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains(""".config("spark.ui.enabled", "false")"""),
      s"local[*] must keep spark.ui.enabled=false, got:\n$code")
    assert(code.contains(""".config("spark.sql.shuffle.partitions", "4")"""),
      s"local[*] must keep shuffle.partitions=4, got:\n$code")
    // Log level suppression is also local-only.
    assert(code.contains("org.apache.log4j.Logger.getRootLogger.setLevel(org.apache.log4j.Level.WARN)"),
      s"local[*] must suppress log4j WARN level, got:\n$code")
  }

  test("local[4] master also gets the local-mode defaults") {
    // Predicate is `startsWith("local")` so the worker-bounded
    // `local[N]` variant counts as local too.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n", sparkMaster = "local[4]")
    assert(code.contains(""".config("spark.ui.enabled", "false")"""))
    assert(code.contains(""".config("spark.sql.shuffle.partitions", "4")"""))
  }

  test("spark:// cluster master skips the local-mode defaults") {
    // Spark Standalone cluster — wrong defaults (production UI hidden,
    // parallelism capped at 4).  Adaptive emission drops both, leaving
    // Spark's own defaults (UI on, shuffle.partitions=200).  User
    // overrides via `spark-config:` still arrive.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n",
      sparkMaster = "spark://prod.example.com:7077")
    assert(!code.contains("spark.ui.enabled"),
      s"cluster master must NOT pin spark.ui.enabled=false, got:\n$code")
    assert(!code.contains("spark.sql.shuffle.partitions"),
      s"cluster master must NOT pin shuffle.partitions=4, got:\n$code")
    // log4j level suppression must also be skipped — operator's
    // own log config wins on a cluster.
    assert(!code.contains("org.apache.log4j.Logger.getRootLogger.setLevel"),
      s"cluster master must not force log4j WARN level, got:\n$code")
  }

  test("yarn master skips the local-mode defaults") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n", sparkMaster = "yarn")
    assert(!code.contains("spark.ui.enabled"))
    assert(!code.contains("spark.sql.shuffle.partitions"))
  }

  test("k8s:// master skips the local-mode defaults") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n",
      sparkMaster = "k8s://cluster.local:6443")
    assert(!code.contains("spark.ui.enabled"))
    assert(!code.contains("spark.sql.shuffle.partitions"))
  }

  test("cluster master still routes user spark-config entries verbatim") {
    // Adaptive default suppression must not also suppress user-supplied
    // values for the same keys.  A user who knows what they're doing
    // can re-enable the local-mode optimization on a cluster.
    val module = scalascript.parser.Parser.parse("# Test\n```scalascript\nval x = 1\n```\n")
    val code   = SparkGen.generate(
      module,
      sparkMaster = "yarn",
      extraConfig = Map("spark.ui.enabled" -> "false", "spark.executor.memory" -> "4g")
    )
    // User-set values reach the source even though adaptive defaults
    // are off.
    assert(code.contains(""".config("spark.ui.enabled", "false")"""),
      s"user spark.ui.enabled override must reach the source, got:\n$code")
    assert(code.contains(""".config("spark.executor.memory", "4g")"""))
  }

  // ── Phase C.3 slice 4: spark-app-name → .appName(...) ────────────────────

  test("default appName is scalascript-job") {
    assert(SparkGen.DefaultAppName == "scalascript-job")
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains(""".appName("scalascript-job")"""),
      s"default appName must be emitted unchanged, got:\n$code")
  }

  test("explicit appName argument reaches the .appName(...) line") {
    val module = Parser.parse("# Test\n```scalascript\nval x = 1\n```\n")
    val code   = SparkGen.generate(module, appName = "My Pipeline")
    assert(code.contains(""".appName("My Pipeline")"""),
      s"custom appName must show up in builder line, got:\n$code")
    // Default name must not leak when the caller supplied one.
    assert(!code.contains(""".appName("scalascript-job")"""),
      s"default appName must not coexist with custom override, got:\n$code")
  }

  test("appName with double quotes is escaped for the Scala literal") {
    // appName values reach a "..." string literal in the emitted
    // source.  A quote in the user's name would otherwise break the
    // literal and the whole `scala-cli run` invocation.
    val module = Parser.parse("# Test\n```scalascript\nval x = 1\n```\n")
    val code   = SparkGen.generate(module, appName = """Pipeline "X" v2""")
    assert(code.contains("""\"X\""""),
      s"quote in appName must be backslash-escaped, got:\n$code")
  }

  // ── Phase C.3 slice 3: spark-config front-matter → .config(k, v) ─────────

  private def genWithConfig(src: String, cfg: Map[String, String]): String =
    val module = Parser.parse(src)
    SparkGen.generate(module, extraConfig = cfg)

  test("extraConfig empty — no extra .config calls beyond the defaults") {
    val code = genWithConfig("# Test\n```scalascript\nval x = 1\n```\n", Map.empty)
    // The defaults are two: spark.ui.enabled, spark.sql.shuffle.partitions.
    val configCount = "\\.config\\(".r.findAllIn(code).size
    assert(configCount == 2,
      s"empty extraConfig should leave exactly the 2 defaults, got $configCount in:\n$code")
  }

  test("extraConfig entries become .config(k, v) lines in sorted order") {
    val code = genWithConfig(
      "# Test\n```scalascript\nval x = 1\n```\n",
      Map(
        "spark.executor.memory"          -> "4g",
        "spark.executor.cores"           -> "2",
        "spark.dynamicAllocation.enabled" -> "true"
      )
    )
    assert(code.contains(""".config("spark.dynamicAllocation.enabled", "true")"""))
    assert(code.contains(""".config("spark.executor.cores", "2")"""))
    assert(code.contains(""".config("spark.executor.memory", "4g")"""))
    // Order: sorted-by-key, which puts dynamicAllocation < executor.cores
    // < executor.memory alphabetically.
    val idxA = code.indexOf("spark.dynamicAllocation.enabled")
    val idxB = code.indexOf("spark.executor.cores")
    val idxC = code.indexOf("spark.executor.memory")
    assert(idxA >= 0 && idxA < idxB && idxB < idxC,
      s"extraConfig must emit in sorted-key order, got A=$idxA B=$idxB C=$idxC")
  }

  test("extraConfig values with special chars are escaped for the Scala literal") {
    val code = genWithConfig(
      "# Test\n```scalascript\nval x = 1\n```\n",
      Map("spark.driver.extraJavaOptions" -> """-Dfoo="bar" -Dbaz=qux""")
    )
    // The double quote in the value must end up backslash-escaped so
    // the surrounding Scala "..." literal is well-formed.  Without
    // escaping the emitted source would not even parse.
    assert(code.contains("""\"bar\""""),
      s"unescaped quote breaks Scala literal, got:\n$code")
  }

  test("user overriding a default key wins (Spark's last-write semantics)") {
    val code = genWithConfig(
      "# Test\n```scalascript\nval x = 1\n```\n",
      Map("spark.sql.shuffle.partitions" -> "200")
    )
    // The default `.config("spark.sql.shuffle.partitions", "4")` is
    // still emitted (we don't shadow defaults).  The user's "200"
    // entry appears AFTER it; Spark's builder takes last-write-wins
    // so 200 is the effective value at runtime.
    val defaultPos  = code.indexOf("shuffle.partitions\", \"4\"")
    val overridePos = code.indexOf("shuffle.partitions\", \"200\"")
    assert(defaultPos >= 0,  s"default line missing in:\n$code")
    assert(overridePos >= 0, s"override line missing in:\n$code")
    assert(overridePos > defaultPos,
      s"user override must come after default for last-write-wins, got default=$defaultPos override=$overridePos in:\n$code")
  }

  // ── Phase C: sql blocks → spark.sql(...) ─────────────────────────────────

  test("sql block with no binds emits single-arg spark.sql call") {
    val src =
      """|# Test
         |
         |```sql
         |SELECT 1 AS one, 2 AS two
         |```
         |""".stripMargin
    val code = gen(src)
    assert(code.contains("_sqlBlock_0"))
    assert(code.contains("spark.sql("))
    assert(code.contains("SELECT 1 AS one, 2 AS two"))
    // No-bind path must not emit the Map.of(...) argument.
    assert(!code.contains("java.util.Map.of"),
      s"no-bind sql block should not emit Map.of argument:\n$code")
  }

  test("sql block with binds emits named placeholders + java.util.Map.of") {
    val src =
      """|# Test
         |
         |```scalascript
         |val tenantId = 42
         |val status = "active"
         |```
         |
         |```sql
         |SELECT id, name FROM users
         |WHERE tenant_id = ${tenantId} AND status = ${status}
         |```
         |""".stripMargin
    val code = gen(src)
    // SqlBindRewriter rewrites to :bind0 / :bind1.
    assert(code.contains(":bind0"), s"expected :bind0 placeholder, got:\n$code")
    assert(code.contains(":bind1"), s"expected :bind1 placeholder, got:\n$code")
    // Named-args map captures the original Scala expressions.
    assert(code.contains("java.util.Map.of"))
    assert(code.contains(""""bind0", tenantId"""))
    assert(code.contains(""""bind1", status"""))
  }

  // ── Phase C.3: >10 binds switch to Map.ofEntries ─────────────────────────
  //
  // JDK's `java.util.Map.of` has overloads for 0..10 key/value pairs only.
  // An 11th bind would produce a call with 22 arguments that no `Map.of`
  // overload matches, so the emitter falls back to the varargs
  // `Map.ofEntries(Map.entry(...), ...)` form above the threshold.

  test("exactly 10 binds still use Map.of (boundary kept)") {
    val varDefs = (0 until 10).map(i => s"val v$i = $i").mkString("\n")
    val conds   = (0 until 10).map(i => s"c$i = $${v$i}").mkString(" AND ")
    val src =
      s"""|# Test
          |
          |```scalascript
          |$varDefs
          |```
          |
          |```sql
          |SELECT 1 WHERE $conds
          |```
          |""".stripMargin
    val code = gen(src)
    assert(code.contains("java.util.Map.of("),
      s"10 binds should still route through Map.of, got:\n$code")
    assert(!code.contains("java.util.Map.ofEntries"),
      s"10 binds should not trigger Map.ofEntries fallback:\n$code")
    assert(code.contains(""""bind9", v9"""))
  }

  test("11+ binds switch to Map.ofEntries fallback") {
    val varDefs = (0 until 11).map(i => s"val v$i = $i").mkString("\n")
    val conds   = (0 until 11).map(i => s"c$i = $${v$i}").mkString(" AND ")
    val src =
      s"""|# Test
          |
          |```scalascript
          |$varDefs
          |```
          |
          |```sql
          |SELECT 1 WHERE $conds
          |```
          |""".stripMargin
    val code = gen(src)
    assert(code.contains("java.util.Map.ofEntries[String, Object]("),
      s"11 binds should switch to Map.ofEntries, got:\n$code")
    // The narrow Map.of path must NOT appear — Scala would otherwise pick
    // an overload that does not exist (22-arg).
    assert(!code.contains("java.util.Map.of("),
      s"Map.ofEntries path must not also emit Map.of:\n$code")
    // Every bind survives as a Map.entry pair.
    for i <- 0 until 11 do
      assert(code.contains(s"""java.util.Map.entry("bind$i", v$i)"""),
        s"missing Map.entry for bind$i in:\n$code")
    // :bind<N> placeholders likewise reach the rewriter output.
    assert(code.contains(":bind10"), s"expected :bind10 placeholder, got:\n$code")
  }

  test("MapOfMaxPairs constant pins the Map.of upper bound to 10") {
    assert(SparkGen.MapOfMaxPairs == 10)
  }

  test("multiple sql blocks get sequential _sqlBlock_<N> names") {
    val src =
      """|# Test
         |
         |```sql
         |SELECT 1
         |```
         |
         |```sql
         |SELECT 2
         |```
         |
         |```sql
         |SELECT 3
         |```
         |""".stripMargin
    val code = gen(src)
    assert(code.contains("_sqlBlock_0"))
    assert(code.contains("_sqlBlock_1"))
    assert(code.contains("_sqlBlock_2"))
  }

  test("sql block bound to DataFrame type so scalascript blocks can use it") {
    val src =
      """|# Test
         |
         |```sql
         |SELECT id, name FROM users
         |```
         |
         |```scalascript
         |_sqlBlock_0.show()
         |val rows = _sqlBlock_0.collect().toList
         |```
         |""".stripMargin
    val code = gen(src)
    // Type annotation is essential — Spark's spark.sql returns DataFrame
    // and we want subsequent scalascript blocks to see that type explicitly.
    assert(code.contains("org.apache.spark.sql.DataFrame"))
    // The scalascript block referencing _sqlBlock_0 should appear later
    // in the same @main scope.
    val sqlIdx  = code.indexOf("val _sqlBlock_0:")
    val showIdx = code.indexOf("_sqlBlock_0.show()")
    assert(sqlIdx >= 0 && showIdx > sqlIdx,
      s"sql block (at $sqlIdx) must come before user reference (at $showIdx)")
  }

  test("sql + scalascript mixed in document order — order preserved") {
    val src =
      """|# Test
         |
         |```scalascript
         |val before = 1
         |```
         |
         |```sql
         |SELECT * FROM t
         |```
         |
         |```scalascript
         |val after = 2
         |```
         |""".stripMargin
    val code      = gen(src)
    val beforeIdx = code.indexOf("val before = 1")
    val sqlIdx    = code.indexOf("_sqlBlock_0")
    val afterIdx  = code.indexOf("val after = 2")
    assert(beforeIdx >= 0 && sqlIdx > beforeIdx && afterIdx > sqlIdx,
      s"document order broken: before@$beforeIdx, sql@$sqlIdx, after@$afterIdx")
  }

  // ── Phase C.2: section-based binding ─────────────────────────────────────

  test("single sql block in a section gets <sectionId>.sql alias") {
    val src =
      """|# Users
         |
         |```sql
         |SELECT id, name FROM users
         |```
         |""".stripMargin
    val code = gen(src)
    assert(code.contains("_sqlBlock_0"))
    // Friendly alias scoped under the section name.
    assert(code.contains("object Users:"))
    assert(code.contains("lazy val sql: org.apache.spark.sql.DataFrame = _sqlBlock_0"),
      s"expected Users.sql alias, got:\n$code")
  }

  test("section heading is camelCased into a valid identifier") {
    // Mirrors JvmGen.sectionIdent: the head word preserves its casing;
    // subsequent words are upper-cased.  `User Stats` → `UserStats`,
    // `user stats` → `userStats`, `Page` → `Page`.
    val capCode = gen("# User Stats\n\n```sql\nSELECT 1\n```\n")
    assert(capCode.contains("object UserStats:"),
      s"expected UserStats object for headcase head word, got:\n$capCode")
    val lowCode = gen("# user stats\n\n```sql\nSELECT 1\n```\n")
    assert(lowCode.contains("object userStats:"),
      s"expected userStats object for lowercase head word, got:\n$lowCode")
  }

  test("multiple sql blocks in one section — only the first gets the alias") {
    val src =
      """|# Reports
         |
         |```sql
         |SELECT 1
         |```
         |
         |```sql
         |SELECT 2
         |```
         |""".stripMargin
    val code = gen(src)
    // Both blocks still emit internal names.
    assert(code.contains("_sqlBlock_0"))
    assert(code.contains("_sqlBlock_1"))
    // Section alias must reference the FIRST block only — no duplicate
    // `lazy val sql` (which would be a Scala compile error inside one object).
    assert(code.contains("object Reports:"))
    val aliasOccurrences =
      "lazy val sql: org.apache.spark.sql.DataFrame".r.findAllIn(code).size
    assert(aliasOccurrences == 1,
      s"expected exactly one `lazy val sql` (only the first block aliases), got $aliasOccurrences")
    // The alias must point at _sqlBlock_0, not _sqlBlock_1.
    assert(code.contains("lazy val sql: org.apache.spark.sql.DataFrame = _sqlBlock_0"))
  }

  test("sql blocks in different sections get separate aliases") {
    val src =
      """|# Users
         |
         |```sql
         |SELECT id FROM users
         |```
         |
         |# Posts
         |
         |```sql
         |SELECT id FROM posts
         |```
         |""".stripMargin
    val code = gen(src)
    assert(code.contains("object Users:"))
    assert(code.contains("object Posts:"))
    // Users.sql → _sqlBlock_0, Posts.sql → _sqlBlock_1.
    val usersStart = code.indexOf("object Users:")
    val postsStart = code.indexOf("object Posts:")
    assert(usersStart < postsStart, "Users alias should come before Posts alias")
    assert(code.substring(usersStart, postsStart).contains("= _sqlBlock_0"))
    assert(code.substring(postsStart).contains("= _sqlBlock_1"))
  }

  test("punctuation-only section heading — no alias emitted, internal name only") {
    // `***` has no alphanumeric runs, so sectionIdent returns None.
    // The datasetShim contains its own `object Dataset` — what we're
    // pinning is that there's no NEW `object <X>:` emitted around the
    // sql block.
    val src =
      """|# ***
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    val code = gen(src)
    assert(code.contains("_sqlBlock_0"))
    // The only `object` declarations should be the datasetShim's
    // `object Dataset`.  No sql-block-driven alias must appear.
    val aliasMatches =
      """object \w+:\s*\n\s*lazy val sql:""".r.findAllIn(code).size
    assert(aliasMatches == 0,
      s"expected no sql section alias when sectionIdent returns None, got $aliasMatches:\n$code")
  }

  test("generated header documents Spark version and run instructions") {
    val code = gen(
      """|# Test
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin
    )
    assert(code.startsWith("// Generated by ScalaScript — Apache Spark 4.0.0, Scala 3.7.1"),
      s"expected version header at top of file, got first line:\n${code.linesIterator.next()}")
    assert(code.contains("To run: scala-cli run this.scala"),
      "expected run instructions in header")
    // Phase E (v1.25 § 9.5): scala-cli `//> using` directives bake the
    // exact Scala version, dep coords, and JVM `--add-opens` flags
    // into the emitted source so `scala-cli run <file>` works with
    // no extra command-line args.  `_2.13` suffix pinned explicitly —
    // Spark only publishes `_2.13` cross-builds.
    assert(code.contains("//> using scala 3.7.1"),
      "expected `//> using scala 3.7.1` directive")
    assert(code.contains("""//> using dep "org.apache.spark:spark-core_2.13:4.0.0""""),
      "expected spark-core_2.13 //> using dep")
    assert(code.contains("""//> using dep "org.apache.spark:spark-sql_2.13:4.0.0""""),
      "expected spark-sql_2.13 //> using dep")
    assert(code.contains("//> using javaOpt --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"),
      "expected sun.nio.ch add-opens directive")
  }

  // ── Phase E — Scala 3 native Spark Encoder derivation ──────────────────

  test("Phase E shim emits an SscSparkEncoders object at top level") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("object SscSparkEncoders:"),
      s"Phase E shim object missing, got:\n$code")
    // Primitive givens — replacement for `spark.implicits._` whose
    // TypeTag-bound product encoder Scala 3 cannot satisfy.
    assert(code.contains("given Encoder[String]  = Encoders.STRING"))
    assert(code.contains("given Encoder[Int]     = Encoders.scalaInt"))
    assert(code.contains("given Encoder[Boolean] = Encoders.scalaBoolean"))
  }

  test("Phase E shim provides Mirror-based product encoder derivation") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // `derived` is the user-facing `Encoder[T]` entry point — it
    // wraps the recursive `AgnosticEncoder[T]` resolution via
    // `ExpressionEncoder(ae)`.
    assert(code.contains("inline given derived[T <: Product]"),
      s"Phase E derived given missing, got:\n$code")
    // The recursive AgnosticEncoder layer that does the actual
    // work: builds `ProductEncoder[T](ct, fields, None)` from a
    // `Mirror.ProductOf[T]`.
    assert(code.contains("inline given aenc_Product[T <: Product]"),
      s"aenc_Product recursive given missing, got:\n$code")
    assert(code.contains("Mirror.ProductOf[T]"),
      "expected Mirror.ProductOf[T] in aenc_Product signature")
    assert(code.contains("ProductEncoder[T](ct, fields, None)"),
      "expected ProductEncoder[T](ct, fields, None) construction")
    assert(code.contains("ExpressionEncoder(ae)"),
      "expected ExpressionEncoder(ae) wrap in `derived`")
  }

  test("Phase E shim provides collection encoder givens (Seq/List/Vector/Set/Array/Map)") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // `Seq[E]`/`List[E]`/`Vector[E]`/`Set[E]` → IterableEncoder.
    assert(code.contains("given aenc_Seq[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Seq[E]]"),
      s"aenc_Seq given missing, got:\n$code")
    assert(code.contains("given aenc_List[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[List[E]]"))
    assert(code.contains("given aenc_Vector[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Vector[E]]"))
    assert(code.contains("given aenc_Set[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Set[E]]"))
    // All four delegate to IterableEncoder with the same shape.
    assert(code.contains("IterableEncoder(classTag[Seq[E]], inner, inner.nullable, lenientSerialization = false)"))

    // `Array[E]` → ArrayEncoder.  Note: ArrayEncoder doesn't take a
    // ClassTag (it stores Object internally and recovers the runtime
    // class via the element encoder's clsTag).
    assert(code.contains("given aenc_Array[E](using inner: AgnosticEncoder[E]): AgnosticEncoder[Array[E]]"),
      s"aenc_Array given missing, got:\n$code")
    assert(code.contains("ArrayEncoder(inner, inner.nullable)"))

    // `Map[K, V]` → MapEncoder with separate key/value encoders.
    assert(code.contains("given aenc_Map[K, V](using k: AgnosticEncoder[K], v: AgnosticEncoder[V]): AgnosticEncoder[Map[K, V]]"),
      s"aenc_Map given missing, got:\n$code")
    assert(code.contains("MapEncoder(classTag[Map[K, V]], k, v, valueContainsNull = v.nullable)"))

    // The `classTag` companion method (not just ClassTag the type) is
    // required for the IterableEncoder / MapEncoder constructors.
    assert(code.contains("import scala.reflect.{ClassTag, classTag}"),
      "expected ClassTag and classTag imported")
  }

  test("Phase E shim provides Option[U] recursive encoder") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // `aenc_Option[U]` is what makes `Option[Int]` etc. work as case
    // class fields.  Spark's `OptionEncoder` wraps the inner.
    assert(code.contains("given aenc_Option[U](using inner: AgnosticEncoder[U]): AgnosticEncoder[Option[U]]"),
      s"aenc_Option given missing, got:\n$code")
    assert(code.contains("OptionEncoder(inner)"),
      "expected OptionEncoder(inner) construction")
  }

  test("Phase E shim emits primitive AgnosticEncoder givens for field-level use") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // The aenc_* givens are how `summonInline[AgnosticEncoder[t]]`
    // resolves primitive field types during case-class derivation.
    // Spot-check the most common ones.
    assert(code.contains("given aenc_String : AgnosticEncoder[String]  = StringEncoder"))
    assert(code.contains("given aenc_Int    : AgnosticEncoder[Int]     = PrimitiveIntEncoder"))
    assert(code.contains("given aenc_Boolean: AgnosticEncoder[Boolean] = PrimitiveBooleanEncoder"))
  }

  test("Phase E shim uses recursive summonFieldEncoders, not eager primitives") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // Each field type is resolved via `summonInline[AgnosticEncoder[t]]`,
    // which routes through normal implicit search — primitives via
    // `aenc_*`, `Option[U]` via `aenc_Option`, nested case classes
    // via recursive `aenc_Product`.  The eager `primitiveEncoderOf`
    // approach from Phase E v1 is gone.
    assert(code.contains("summonInline[AgnosticEncoder[t]]"),
      "expected summonInline-based per-field encoder lookup")
    assert(!code.contains("inline def primitiveEncoderOf["),
      s"old eager primitiveEncoderOf must be replaced, got:\n$code")
  }

  test("@main scope imports SscSparkEncoders.given instead of spark.implicits._") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // `import spark.implicits._` poisons implicit search with a
    // TypeTag-bound `newProductEncoder`; we drop it (as an ACTIVE
    // import statement; the substring still appears in shim doc
    // comments) and bring in the Phase E givens instead.
    val isImportLine = (line: String) =>
      line.trim == "import spark.implicits._" ||
      line.trim.startsWith("import spark.implicits._ ")
    assert(!code.linesIterator.exists(isImportLine),
      s"spark.implicits._ must NOT be imported as a live statement (TypeTag conflict), got:\n$code")
    assert(code.contains("import SscSparkEncoders.given"),
      s"SscSparkEncoders.given import missing, got:\n$code")
  }

  test("`//> using scala 3.7.1` pin in generated source") {
    // Scala 3.8.x has a regression in TASTy-bridge to Spark `_2.13`
    // that breaks ExpressionEncoder runtime; 3.7.1 is the latest
    // series that works.  Pinning via `//> using scala` keeps
    // `scala-cli run` reproducible regardless of system default.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("//> using scala 3.7.1"),
      s"expected `//> using scala 3.7.1` directive, got:\n$code")
  }

  test("`//> using javaOpt` add-opens directives baked in for JDK 17+") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // The exact list is `SparkGen.DefaultJavaOpts`; spot-check a few
    // critical ones rather than re-listing all 13.
    assert(code.contains("//> using javaOpt --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"),
      "expected sun.nio.ch add-opens (block manager DirectBuffer access)")
    assert(code.contains("//> using javaOpt --add-opens=java.base/java.lang=ALL-UNNAMED"),
      "expected java.lang add-opens (Scala reflection internals)")
    assert(code.contains("//> using javaOpt --add-opens=java.base/java.nio=ALL-UNNAMED"),
      "expected java.nio add-opens (off-heap buffer access)")
  }

  test("DefaultScalaVersion is pinned at 3.7.1") {
    assert(SparkGen.DefaultScalaVersion == "3.7.1",
      "Phase E pins Scala to 3.7.1 due to 3.8.x regression — see SPEC § 9.5")
  }

  test("scalaVersion parameter override flows into //> using scala directive") {
    val module = scalascript.parser.Parser.parse(
      "# Test\n```scalascript\nval x = 1\n```\n"
    )
    val code = SparkGen.generate(module, scalaVersion = "3.6.4")
    assert(code.contains("//> using scala 3.6.4"),
      s"custom scalaVersion must flow into directive, got:\n$code")
    assert(!code.contains("//> using scala 3.7.1"),
      s"default Scala version must not coexist with override, got:\n$code")
  }

  test("sparkVersion parameter controls header version") {
    val code = gen(
      """|# Test
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin,
      sparkVersion = "3.5.1"
    )
    assert(code.startsWith("// Generated by ScalaScript — Apache Spark 3.5.1"),
      "expected 3.5.1 in header when explicitly specified")
    assert(code.contains("""spark-core_2.13:3.5.1"""), "expected 3.5.1 in dep instructions")
  }

  test("generated code contains SparkSession builder") {
    val code = gen(
      """|# Test
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin
    )
    assert(code.contains("SparkSession"), "expected SparkSession import/usage")
    assert(code.contains(".builder()"), "expected SparkSession.builder()")
    assert(code.contains("local[*]"), "expected local[*] master")
    assert(code.contains("spark.stop()"), "expected spark.stop() at end")
  }

  test("generated code imports Spark SQL packages") {
    val code = gen(
      """|# Test
         |```scalascript
         |val y = 1
         |```
         |""".stripMargin
    )
    // SparkSession / Dataset / Encoder remain in the same single-line
    // brace import for grep-friendliness; DataFrame and Row are added
    // alongside in Phase C.3 so user scalascript blocks can refer to
    // collected results without spelling out FQNs.
    assert(
      code.contains("import org.apache.spark.sql.{SparkSession, Dataset, DataFrame, Row, Encoder}"),
      s"expected SparkSession/Dataset/DataFrame/Row/Encoder import, got:\n$code"
    )
    assert(code.contains("import org.apache.spark.sql.functions._"))
  }

  // ── Phase C.3: user-visible Spark types ────────────────────────────────
  //
  // After C.1 (`sql` block → DataFrame) lands, the next reasonable user
  // move is `_sqlBlock_0.collect()` (returns Array[Row]) or
  // `_sqlBlock_0.schema` (returns StructType).  Both `Row` and the
  // `types` package — StructType / StructField / *Type leaves — must
  // resolve in user scalascript blocks without FQN gymnastics.

  test("Row is imported so `case Row(...)` works in user blocks") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains(", Row,") || code.contains(", Row}"),
      s"Row must appear in the SparkSession/Dataset/... brace import, got:\n$code")
  }

  test("DataFrame is imported so users can ascribe `val df: DataFrame = ...`") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("DataFrame"),
      s"DataFrame must appear in the brace import, got:\n$code")
  }

  test("types._ wildcard imported so StructType / StructField / *Type resolve") {
    // The wildcard import surfaces ~20 type leaves the user-facing
    // `spark.read.schema(...)` reader path needs (StructType,
    // StructField, StringType, IntegerType, …); a wildcard is cheaper
    // than enumerating each leaf and matches Spark's own examples.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("import org.apache.spark.sql.types._"),
      s"types._ wildcard import is required for Phase C.3 schema work, got:\n$code")
  }

  test("generated code contains Dataset shim object") {
    val code = gen(
      """|# Test
         |```scalascript
         |val ds = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    assert(code.contains("object Dataset:"), "expected Dataset companion shim")
    assert(code.contains("spark.createDataset"), "expected spark.createDataset in shim")
    assert(code.contains("def of[T"), "expected Dataset.of in shim")
    assert(code.contains("def fromList[T"), "expected Dataset.fromList in shim")
  }

  test("Dataset.of call passes through to shim") {
    val code = gen(
      """|# Word count
         |```scalascript
         |val words = Dataset.of("hello", "world", "hello")
         |val counted = words.map(w => (w, 1))
         |counted.foreach(println)
         |```
         |""".stripMargin
    )
    assert(code.contains("Dataset.of("), "expected Dataset.of call in user code")
    assert(code.contains(".map("), "expected .map(")
    assert(code.contains(".foreach("), "expected .foreach(")
  }

  test("Dataset.fromList emits createDataset") {
    val code = gen(
      """|# List source
         |```scalascript
         |val nums = Dataset.fromList(List(1, 2, 3, 4, 5))
         |val total = nums.count()
         |println(total)
         |```
         |""".stripMargin
    )
    assert(code.contains("Dataset.fromList("), "expected Dataset.fromList call")
    assert(code.contains("spark.createDataset(list)"), "expected spark.createDataset in fromList impl")
  }

  test("Dataset.fromFile emits spark.read.textFile") {
    val code = gen(
      """|# File source
         |```scalascript
         |val lines = Dataset.fromFile("data.txt")
         |val count = lines.count()
         |println(count)
         |```
         |""".stripMargin
    )
    assert(code.contains("Dataset.fromFile("), "expected Dataset.fromFile call")
    assert(code.contains("spark.read.textFile"), "expected spark.read.textFile in fromFile impl")
  }

  // ── Phase C.3 slices 5–6: typed reader convenience shims ────────────────

  test("Dataset.fromParquet shim delegates to spark.read.options(...).parquet") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromParquet(\"/data\")\n```\n")
    // Slice 6: signature gained `options: (String, String)*` varargs so
    // callers can pass `"mergeSchema" -> "true"` etc.  Zero options
    // collapses to `.options(Map())` which is a Spark no-op, so the
    // bare `Dataset.fromParquet(path)` call still works.
    assert(code.contains("def fromParquet(path: String, options: (String, String)*): DataFrame ="),
      s"missing fromParquet varargs signature in shim, got:\n$code")
    assert(code.contains("spark.read.options(options.toMap).parquet(path)"),
      s"fromParquet must delegate to spark.read.options(...).parquet, got:\n$code")
  }

  test("Dataset.fromJson shim delegates to spark.read.options(...).json") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromJson(\"/data\")\n```\n")
    assert(code.contains("def fromJson(path: String, options: (String, String)*): DataFrame ="))
    assert(code.contains("spark.read.options(options.toMap).json(path)"))
  }

  test("Dataset.fromCsv shim delegates to spark.read.options(...).csv") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromCsv(\"/data\")\n```\n")
    assert(code.contains("def fromCsv(path: String, options: (String, String)*): DataFrame ="))
    assert(code.contains("spark.read.options(options.toMap).csv(path)"))
  }

  // ── Phase C.3 slice 9: schema bridge (case class → StructType) ──────────

  test("Dataset.schemaOf[T] delegates to summon[Encoder[T]].schema") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    // `Encoder[T]` is the right constraint — `spark.implicits._` brings
    // the case-class encoder into scope, and `.schema` is part of the
    // Encoder ABI in Spark 3.x+.
    assert(code.contains("def schemaOf[T : Encoder]: StructType ="),
      s"missing schemaOf signature, got:\n$code")
    assert(code.contains("summon[Encoder[T]].schema"),
      s"schemaOf must delegate to Encoder[T].schema, got:\n$code")
  }

  test("Dataset.fromParquetAs[T] reads with explicit schema and returns Dataset[T]") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromParquetAs[Int](\"/data\")\n```\n")
    assert(code.contains("def fromParquetAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] ="),
      s"missing fromParquetAs signature, got:\n$code")
    // The pipeline: schema(...) → options(...) → parquet(path) → as[T].
    // Schema-first because Spark needs to know the projection before
    // opening the file (column pruning on Parquet).
    assert(code.contains("spark.read.schema(schemaOf[T]).options(options.toMap).parquet(path).as[T]"),
      s"fromParquetAs must chain schema → options → parquet → as[T], got:\n$code")
  }

  test("Dataset.fromJsonAs[T] reads with explicit schema and returns Dataset[T]") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromJsonAs[Int](\"/data\")\n```\n")
    assert(code.contains("def fromJsonAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] ="))
    assert(code.contains("spark.read.schema(schemaOf[T]).options(options.toMap).json(path).as[T]"))
  }

  test("Dataset.fromCsvAs[T] is the only correct way to read typed CSV") {
    // Spark's `.csv(path)` returns every column as String when no
    // schema is set.  The typed reader's schema() is the only way to
    // get a real `Dataset[T]` without a follow-up `.withColumn(...)
    // .cast(...)` chain.
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromCsvAs[Int](\"/data\", \"header\" -> \"true\")\n```\n")
    assert(code.contains("def fromCsvAs[T : Encoder](path: String, options: (String, String)*): Dataset[T] ="))
    assert(code.contains("spark.read.schema(schemaOf[T]).options(options.toMap).csv(path).as[T]"))
    // User's option pair survives verbatim (header=true is essential for
    // typed CSV reads — without it the first row is treated as data).
    assert(code.contains("""Dataset.fromCsvAs[Int]("/data", "header" -> "true")"""),
      s"user call must survive verbatim, got:\n$code")
  }

  test("schema bridge + typed readers coexist with the untyped fromX shims") {
    // The typed `fromXAs[T]` and untyped `fromX` shims live side-by-side
    // in the same `object Dataset`; both should be discoverable in a
    // single generated source.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("def fromParquet("))
    assert(code.contains("def fromParquetAs["))
    assert(code.contains("def fromJson("))
    assert(code.contains("def fromJsonAs["))
    assert(code.contains("def fromCsv("))
    assert(code.contains("def fromCsvAs["))
    assert(code.contains("def schemaOf["))
  }

  // ── Phase D/E: UDF bridge — @SqlFn → spark.udf.register via Java UDFN ───

  test("extractSqlFns helper: no @SqlFn → no sigs, source unchanged") {
    val src = "val x = 1\ndef plain(a: Int): Int = a + 1\n"
    val (cleaned, sigs) = SparkGen.extractSqlFns(src)
    assert(sigs.isEmpty)
    assert(cleaned == src, s"unchanged source expected, got:\n$cleaned")
  }

  test("extractSqlFns helper: single @SqlFn def captures name + params + return type") {
    val src = "@SqlFn\ndef toUpper(s: String): String = s.toUpperCase\n"
    val (cleaned, sigs) = SparkGen.extractSqlFns(src)
    assert(sigs.size == 1)
    val sig = sigs.head
    assert(sig.name == "toUpper")
    assert(sig.paramTypes == List("String"), s"expected [String], got ${sig.paramTypes}")
    assert(sig.returnType == "String")
    assert(!cleaned.contains("@SqlFn"), s"@SqlFn must be stripped, got:\n$cleaned")
    assert(cleaned.contains("def toUpper(s: String): String"))
  }

  test("extractSqlFns helper: arity-2 def with mixed param types") {
    val src = "@SqlFn\ndef pad(s: String, n: Int): String = s + (\" \" * n)\n"
    val (_, sigs) = SparkGen.extractSqlFns(src)
    assert(sigs.head.paramTypes == List("String", "Int"))
    assert(sigs.head.returnType == "String")
  }

  test("extractSqlFns helper: multiple @SqlFn defs captured in document order") {
    val src =
      """|@SqlFn
         |def a(x: Int): Int = x + 1
         |
         |val plain = 42
         |
         |@SqlFn
         |def c(x: Int, y: Int): Long = (x.toLong * y).toLong
         |""".stripMargin
    val (cleaned, sigs) = SparkGen.extractSqlFns(src)
    assert(sigs.map(_.name) == List("a", "c"))
    assert(sigs.map(_.returnType) == List("Int", "Long"))
    assert(!cleaned.contains("@SqlFn"))
    assert(cleaned.contains("val plain = 42"))
  }

  test("extractSqlFns helper: val form is NOT captured (defs only)") {
    // `val lenient = (s: String) => s.length` doesn't expose the
    // return type in a parsable position (it's inferred from the
    // function literal body), so Phase E revival declines to handle
    // it.  Users wanting a one-arg UDF rewrite as `def`.
    val src = "@SqlFn\nval lenient = (s: String) => s.length\n"
    val (_, sigs) = SparkGen.extractSqlFns(src)
    assert(sigs.isEmpty, s"val should not be captured, got: $sigs")
  }

  test("extractSqlFns helper: indented @SqlFn preserves the def's indentation") {
    val src = "  @SqlFn\n  def nested(x: Int): Int = x + 1\n"
    val (cleaned, sigs) = SparkGen.extractSqlFns(src)
    assert(sigs.head.name == "nested")
    assert(cleaned.contains("  def nested(x: Int)"))
  }

  test("SparkGen emits Java UDFN wrapper for each @SqlFn declaration") {
    val code = gen(
      """|# Test
         |
         |```scalascript
         |@SqlFn
         |def toUpper(s: String): String = s.toUpperCase
         |
         |@SqlFn
         |def addOne(x: Int): Int = x + 1
         |```
         |""".stripMargin
    )
    // Phase E revival: the emit uses Java's UDFN functional interface
    // form (TypeTag-free) plus an explicit return DataType.  The
    // typed `register[RT : TypeTag, ...]` overload would not compile.
    assert(code.contains("""spark.udf.register("toUpper","""),
      s"toUpper registration call missing, got:\n$code")
    assert(code.contains("new org.apache.spark.sql.api.java.UDF1[String, String]"),
      "expected UDF1[String, String] wrapper for toUpper")
    assert(code.contains("def call(a1: String): String = toUpper(a1)"),
      "expected call delegating to toUpper")
    assert(code.contains("org.apache.spark.sql.types.StringType"),
      "expected StringType for String-returning UDF")

    assert(code.contains("""spark.udf.register("addOne","""))
    assert(code.contains("new org.apache.spark.sql.api.java.UDF1[Int, Int]"))
    assert(code.contains("def call(a1: Int): Int = addOne(a1)"))
    assert(code.contains("org.apache.spark.sql.types.IntegerType"),
      "expected IntegerType for Int-returning UDF")

    // @SqlFn marker is stripped — Scala compiler would otherwise see
    // an unknown annotation.
    assert(!code.contains("@SqlFn"),
      s"@SqlFn marker must not survive into generated source, got:\n$code")
  }

  test("SparkGen emits arity-N UDFN wrapper based on def's param count") {
    val code = gen(
      """|# Test
         |```scalascript
         |@SqlFn
         |def pad(s: String, n: Int): String = s + (" " * n)
         |```
         |""".stripMargin
    )
    // Two-arg def → UDF2 wrapper.
    assert(code.contains("new org.apache.spark.sql.api.java.UDF2[String, Int, String]"),
      s"expected UDF2[String, Int, String], got:\n$code")
    assert(code.contains("def call(a1: String, a2: Int): String = pad(a1, a2)"))
  }

  test("Unknown return type degrades to StringType with a TODO comment") {
    // Generic / non-mapped return types (e.g. `Option[String]`) fall
    // outside the SqlFnDataType map.  We don't refuse the compile —
    // emit StringType + a `// TODO` comment so the user has a visible
    // cue without blocking the rest of the module.
    val code = gen(
      """|# Test
         |```scalascript
         |@SqlFn
         |def maybe(s: String): Option[String] = Some(s)
         |```
         |""".stripMargin
    )
    assert(code.contains("// TODO Phase E: no DataType mapping for return type 'Option[String]'"),
      s"expected TODO comment for unmapped return type, got:\n$code")
    assert(code.contains("org.apache.spark.sql.types.StringType"),
      "expected StringType fallback")
  }

  test("Registration ordering: UDF is registered before any sql block that uses it") {
    val code = gen(
      """|# Test
         |
         |```scalascript
         |@SqlFn
         |def toUpper(s: String): String = s.toUpperCase
         |```
         |
         |```sql
         |SELECT toUpper(name) AS upper FROM users
         |```
         |""".stripMargin
    )
    val regIdx = code.indexOf("""spark.udf.register("toUpper",""")
    val sqlIdx = code.indexOf("SELECT toUpper(name)")
    assert(regIdx >= 0 && sqlIdx >= 0,
      s"both registration and sql block must be present, got:\n$code")
    assert(regIdx < sqlIdx,
      s"UDF registration must precede the sql block that uses it (reg@$regIdx, sql@$sqlIdx)")
  }

  test("SqlFnDataType maps the common primitive returns to Spark DataType FQNs") {
    val map = SparkGen.SqlFnDataType
    assert(map("String")  == "org.apache.spark.sql.types.StringType")
    assert(map("Int")     == "org.apache.spark.sql.types.IntegerType")  // note: Int → IntegerType
    assert(map("Long")    == "org.apache.spark.sql.types.LongType")
    assert(map("Boolean") == "org.apache.spark.sql.types.BooleanType")
    assert(map("Double")  == "org.apache.spark.sql.types.DoubleType")
    // Generic / unsupported types are NOT in the map; emit falls
    // back to StringType + TODO comment (see test above).
    assert(!map.contains("Option[String]"))
  }

  // ── Phase E follow-up: tuple-as-field support ─────────────────────────

  test("Tuples in case-class fields use the existing aenc_Product given") {
    // Scala 3 synthesises `Mirror.ProductOf[(A, B)]` automatically;
    // labels are `("_1", "_2", ...)` and the element types come from
    // the tuple's type-level structure.  So `case class R(x: (String,
    // Int))` derives without any tuple-specific given — the existing
    // `aenc_Product[T <: Product]` handles it.  No code change needed;
    // this test just pins the path against future regressions.
    val code = gen(
      """|# Test
         |```scalascript
         |case class WithPair(id: Int, pair: (String, Int))
         |val xs = List(WithPair(1, ("hello", 7)))
         |val ds = spark.createDataset(xs)
         |```
         |""".stripMargin
    )
    // The user code survives verbatim — derivation happens at the
    // user's scala-cli compile, not in SparkGen.  Spot-check that the
    // shim is in place (the `aenc_Product` given is what makes the
    // tuple work end-to-end).
    assert(code.contains("inline given aenc_Product[T <: Product]"),
      "aenc_Product given (which handles tuples too) must be present")
    assert(code.contains("case class WithPair(id: Int, pair: (String, Int))"),
      "user case class survives verbatim")
  }

  test("Scalascript block without @SqlFn yields no registration calls") {
    val code = gen(
      """|# Test
         |
         |```scalascript
         |def plain(x: Int): Int = x + 1
         |val y = 42
         |```
         |""".stripMargin
    )
    assert(!code.contains("spark.udf.register"),
      s"no @SqlFn → no registration calls, got:\n$code")
    // The plain def itself still lands in the source untouched.
    assert(code.contains("def plain(x: Int): Int = x + 1"))
  }

  // ── Phase C.3 slice 7: writer extension methods ──────────────────────────

  test("Dataset.toParquet extension delegates to ds.write.options(...).parquet") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromList(List(1,2,3))\ndf.toParquet(\"/out\")\n```\n")
    assert(code.contains("def toParquet(path: String, options: (String, String)*): Unit ="),
      s"missing toParquet extension signature, got:\n$code")
    assert(code.contains("ds.write.options(options.toMap).parquet(path)"),
      s"toParquet must delegate to ds.write.options(...).parquet, got:\n$code")
  }

  test("Dataset.toJson extension delegates to ds.write.options(...).json") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromList(List(1,2,3))\ndf.toJson(\"/out\")\n```\n")
    assert(code.contains("def toJson(path: String, options: (String, String)*): Unit ="))
    assert(code.contains("ds.write.options(options.toMap).json(path)"))
  }

  test("Dataset.toCsv extension delegates to ds.write.options(...).csv") {
    val code = gen("# Test\n```scalascript\nval df = Dataset.fromList(List(1,2,3))\ndf.toCsv(\"/out\")\n```\n")
    assert(code.contains("def toCsv(path: String, options: (String, String)*): Unit ="))
    assert(code.contains("ds.write.options(options.toMap).csv(path)"))
  }

  test("writer extension methods accept inline option pairs verbatim") {
    val code = gen(
      """|# Round-trip
         |```scalascript
         |val df = Dataset.fromCsv("/in", "header" -> "true")
         |df.toCsv("/out", "header" -> "true", "compression" -> "gzip")
         |```
         |""".stripMargin
    )
    // The user's multi-option write call survives verbatim — varargs
    // resolution happens at scala-cli compile time, not in SparkGen.
    assert(code.contains("""df.toCsv("/out", "header" -> "true", "compression" -> "gzip")"""),
      s"user multi-option write call must survive verbatim, got:\n$code")
    // Reader + writer coexist in the same extension block.
    assert(code.contains("def fromCsv"))
    assert(code.contains("def toCsv"))
  }

  test("Reader user call with options pairs survives verbatim in user block") {
    // The varargs option pairs are user-written Scala; SparkGen passes
    // the source through, so a multi-option call lands in the generated
    // file unchanged.  The Spark compile then resolves `options.toMap`
    // to a real `Map[String, String]` at runtime.
    val code = gen(
      """|# Multi-format
         |```scalascript
         |val p = Dataset.fromParquet("/p")
         |val j = Dataset.fromJson("/j", "multiLine" -> "true")
         |val c = Dataset.fromCsv("/c", "header" -> "true", "inferSchema" -> "true")
         |```
         |""".stripMargin
    )
    assert(code.contains("def fromParquet"))
    assert(code.contains("def fromJson"))
    assert(code.contains("def fromCsv"))
    assert(code.contains("""Dataset.fromCsv("/c", "header" -> "true", "inferSchema" -> "true")"""),
      s"user multi-option call must survive verbatim, got:\n$code")
    assert(code.contains("""Dataset.fromJson("/j", "multiLine" -> "true")"""),
      s"user single-option call must survive verbatim, got:\n$code")
    // Bare 0-options form coexists with the variadic-options form.
    assert(code.contains("""Dataset.fromParquet("/p")"""),
      s"bare 0-options reader call must still work, got:\n$code")
  }

  test("filter and distinct pass through as Spark ops") {
    val code = gen(
      """|# Filter distinct
         |```scalascript
         |val ds = Dataset.of(1, 2, 2, 3)
         |val result = ds.filter(_ > 1).distinct
         |result.foreach(println)
         |```
         |""".stripMargin
    )
    assert(code.contains(".filter("), "expected .filter(")
    assert(code.contains(".distinct"), "expected .distinct")
  }

  test("flatMap passes through as Spark op") {
    val code = gen(
      """|# FlatMap
         |```scalascript
         |val words = Dataset.of("hello world", "foo bar")
         |val split = words.flatMap(line => line.split(" ").toList)
         |split.foreach(println)
         |```
         |""".stripMargin
    )
    assert(code.contains(".flatMap("), "expected .flatMap(")
  }

  test("union passes through as Spark op") {
    val code = gen(
      """|# Union
         |```scalascript
         |val a = Dataset.of(1, 2, 3)
         |val b = Dataset.of(4, 5, 6)
         |val both = a.union(b)
         |both.foreach(println)
         |```
         |""".stripMargin
    )
    assert(code.contains(".union("), "expected .union(")
  }

  test("reduce passes through as Spark op") {
    val code = gen(
      """|# Reduce
         |```scalascript
         |val nums = Dataset.of(1, 2, 3, 4, 5)
         |val sum = nums.reduce(_ + _)
         |println(sum)
         |```
         |""".stripMargin
    )
    assert(code.contains(".reduce("), "expected .reduce(")
  }

  test("count passes through as Spark terminal op") {
    val code = gen(
      """|# Count
         |```scalascript
         |val ds = Dataset.of("a", "b", "c")
         |val n = ds.count()
         |println(n)
         |```
         |""".stripMargin
    )
    assert(code.contains(".count()"), "expected .count()")
  }

  test("toList extension method is emitted") {
    val code = gen(
      """|# ToList
         |```scalascript
         |val ds = Dataset.of(10, 20, 30)
         |val lst = ds.toList
         |println(lst)
         |```
         |""".stripMargin
    )
    assert(code.contains("def toList"), "expected toList extension in shim")
  }

  test("top extension method is emitted") {
    val code = gen(
      """|# Top
         |```scalascript
         |val ds = Dataset.of(5, 3, 8, 1)
         |val topN = ds.top(3)
         |println(topN)
         |```
         |""".stripMargin
    )
    assert(code.contains("def top("), "expected top extension in shim")
  }

  test("@main wrapper is emitted") {
    val code = gen(
      """|# Main
         |```scalascript
         |println("hello spark")
         |```
         |""".stripMargin
    )
    assert(code.contains("@main def runSparkJob(): Unit ="),
      "expected @main def runSparkJob()")
  }

  test("spark.stop() is emitted at end") {
    val code = gen(
      """|# End
         |```scalascript
         |val x = 1
         |```
         |""".stripMargin
    )
    assert(code.endsWith("  spark.stop()\n"),
      "expected spark.stop() as the last statement")
  }

  test("user blocks are indented inside @main def") {
    val code = gen(
      """|# Indent test
         |```scalascript
         |val answer = 42
         |```
         |""".stripMargin
    )
    // Lines that are non-empty from user code should be indented 2 spaces
    val userLine = code.linesIterator.find(_.contains("val answer ="))
    assert(userLine.isDefined, "user code line not found")
    assert(userLine.get.startsWith("  "), "user code should be indented inside @main")
  }

  test("multi-block module emits all blocks") {
    val code = gen(
      """|# Section 1
         |```scalascript
         |val a = 1
         |```
         |
         |# Section 2
         |```scalascript
         |val b = 2
         |val c = a + b
         |```
         |""".stripMargin
    )
    assert(code.contains("val a = 1"), "expected block 1 content")
    assert(code.contains("val b = 2"), "expected block 2 content")
    assert(code.contains("val c = a + b"), "expected block 2 content")
  }

  test("word count pipeline generates coherent Spark code") {
    val code = gen(
      """|# Word Count
         |```scalascript
         |val words = Dataset.of("hello world", "hello spark", "world of spark")
         |  .flatMap(line => line.split(" ").toList)
         |  .map(word => (word, 1))
         |
         |words.foreach { case (w, n) => println(s"$w: $n") }
         |```
         |""".stripMargin
    )
    // All major structural elements present
    assert(code.contains("SparkSession"), "expected SparkSession")
    assert(code.contains("createDataset"), "expected createDataset in shim")
    assert(code.contains("Dataset.of("), "expected Dataset.of in user code")
    assert(code.contains(".flatMap("), "expected flatMap")
    assert(code.contains(".map("), "expected map")
    assert(code.contains(".foreach"), "expected foreach")
    assert(code.contains("spark.stop()"), "expected spark.stop()")
  }

  // ── L.2 — Lakehouse formats: Delta Lake ──────────────────────────────────
  //
  // See `docs/spark-lakehouse.md` for the design.  Detection runs over the
  // raw block sources (substring regex, case-insensitive) and toggles:
  //   (a) a `//> using dep "io.delta:delta-spark_2.13:<v>"` header line
  //   (b) two `.config(k, v)` lines on `SparkSession.builder()` — the
  //       Delta SQL extension + the spark_catalog override.
  // Both decisions are additive — modules that never mention Delta
  // produce identical headers / builder chains to today's baseline.

  test("delta detection — .format(\"delta\") in source triggers dep emit") {
    val code = gen(
      """|# Delta write
         |```scalascript
         |ds.write.format("delta").mode("overwrite").save("/tmp/out")
         |```
         |""".stripMargin
    )
    assert(code.contains(s"""//> using dep "io.delta:delta-spark_2.13:${SparkGen.DefaultDeltaVersion}""""),
      s"Delta dep must appear when .format(\"delta\") is present, got:\n$code")
  }

  test("delta detection — read path .format(\"delta\") also triggers dep emit") {
    val code = gen(
      """|# Delta read
         |```scalascript
         |val df = spark.read.format("delta").load("/tmp/out")
         |df.show()
         |```
         |""".stripMargin
    )
    // Read-side and write-side are symmetric — same regex, same emit.
    assert(code.contains(s"""//> using dep "io.delta:delta-spark_2.13:${SparkGen.DefaultDeltaVersion}""""),
      s"Delta dep must appear on read path too, got:\n$code")
  }

  test("delta detection — uppercase .format(\"DELTA\") still triggers dep emit") {
    // Spark's data-source registry is case-insensitive at the lookup
    // layer; our regex carries (?i) for the same reason.  A user typing
    // .format("Delta") / .format("DELTA") must still produce a working
    // emit, not a missing-dep failure at runtime.
    val code = gen(
      """|# Uppercase
         |```scalascript
         |ds.write.format("DELTA").save("/tmp/out")
         |```
         |""".stripMargin
    )
    assert(code.contains("delta-spark_2.13"),
      s"Delta dep must match case-insensitively, got:\n$code")
  }

  test("delta detection — module without .format(\"delta\") emits NO Delta dep") {
    val code = gen(
      """|# No Delta
         |```scalascript
         |val x = Dataset.of(1, 2, 3)
         |x.foreach(println)
         |```
         |""".stripMargin
    )
    assert(!code.contains("delta-spark_2.13"),
      s"Delta dep must NOT appear when format is absent, got:\n$code")
    // Negative also covers the config lines — neither should appear.
    assert(!code.contains("io.delta.sql.DeltaSparkSessionExtension"),
      s"Delta extension must NOT appear when format is absent, got:\n$code")
    assert(!code.contains("org.apache.spark.sql.delta.catalog.DeltaCatalog"),
      s"Delta catalog must NOT appear when format is absent, got:\n$code")
  }

  test("delta config — both extension + catalog lines present when detected") {
    val code = gen(
      """|# Delta config
         |```scalascript
         |ds.write.format("delta").save("/tmp/out")
         |```
         |""".stripMargin
    )
    assert(code.contains(""".config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")"""),
      s"Delta SQL extension config missing, got:\n$code")
    assert(code.contains(""".config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")"""),
      s"Delta catalog override config missing, got:\n$code")
  }

  test("delta config — sits between adaptive defaults and extraConfig (sort order)") {
    // The ordering rule (see `docs/spark-lakehouse.md` § Architecture):
    //   1. Adaptive `local*` defaults (spark.ui.enabled, spark.sql.shuffle.partitions)
    //   2. Lakehouse format configs
    //   3. User `spark-config:` map
    // Spark's builder is last-write-wins, so this layering means a user
    // override in `spark-config:` always wins over the lakehouse default.
    val module = Parser.parse(
      """|# Delta + spark-config
         |```scalascript
         |ds.write.format("delta").save("/tmp/out")
         |```
         |""".stripMargin
    )
    val code = SparkGen.generate(
      module,
      extraConfig = Map("spark.sql.catalog.spark_catalog" -> "com.example.MyCatalog")
    )
    val idxUi       = code.indexOf("spark.ui.enabled")
    val idxLakeExt  = code.indexOf("io.delta.sql.DeltaSparkSessionExtension")
    val idxLakeCat  = code.indexOf("org.apache.spark.sql.delta.catalog.DeltaCatalog")
    val idxOverride = code.indexOf("com.example.MyCatalog")
    assert(idxUi >= 0,       s"adaptive default missing in:\n$code")
    assert(idxLakeExt >= 0,  s"Delta extension missing in:\n$code")
    assert(idxLakeCat >= 0,  s"Delta catalog missing in:\n$code")
    assert(idxOverride >= 0, s"user override missing in:\n$code")
    assert(idxUi < idxLakeExt, s"adaptive defaults must precede lakehouse configs")
    assert(idxLakeCat < idxOverride,
      s"lakehouse defaults must precede user override (last-write-wins); got lakeCat=$idxLakeCat override=$idxOverride in:\n$code")
  }

  test("delta detection — .format(\"delta-stage\") substring does NOT match") {
    // The regex captures the whole `"delta"` quoted literal, not a
    // bare `delta` substring.  An adjacent name like `"delta-stage"`
    // is a different format string and must not trigger Delta dep
    // emission.
    val code = gen(
      """|# Other format
         |```scalascript
         |ds.write.format("delta-stage").save("/tmp/out")
         |```
         |""".stripMargin
    )
    assert(!code.contains("delta-spark_2.13"),
      s"`delta-stage` is a different format — must not pull Delta dep, got:\n$code")
  }

  test("delta default version constant pins 3.2.0") {
    // The version constant is the single source of truth for the emit
    // and the spec doc; lock it down with an explicit assertion so a
    // bump is a deliberate, reviewable change.
    assert(SparkGen.DefaultDeltaVersion == "3.2.0",
      s"DefaultDeltaVersion drift — update spec doc + bump intentionally, got ${SparkGen.DefaultDeltaVersion}")
  }

  test("detectLakehouseFormats — empty input returns empty flags") {
    val flags = SparkGen.detectLakehouseFormats(Nil)
    assert(!flags.usesDelta && !flags.usesIceberg && !flags.usesHudi)
    assert(!flags.any)
  }

  test("detectLakehouseFormats — Delta-only block sets only usesDelta") {
    val blocks = List(SparkGen.Block("""ds.write.format("delta").save("/p")"""))
    val flags = SparkGen.detectLakehouseFormats(blocks)
    assert(flags.usesDelta,    "usesDelta must be true")
    assert(!flags.usesIceberg, "usesIceberg must remain false")
    assert(!flags.usesHudi,    "usesHudi must remain false")
    assert(flags.any)
  }

  test("lakehouseConfigs — empty flags yield empty list") {
    assert(SparkGen.lakehouseConfigs(SparkGen.LakehouseFlags.Empty).isEmpty)
  }

  test("lakehouseConfigs — Delta-only yields two sorted pairs") {
    val pairs = SparkGen.lakehouseConfigs(SparkGen.LakehouseFlags(usesDelta = true))
    assert(pairs.size == 2, s"Delta should yield 2 config pairs, got: $pairs")
    // Sorted-by-key: `spark.sql.catalog.spark_catalog` < `spark.sql.extensions`.
    assert(pairs.head._1 == "spark.sql.catalog.spark_catalog")
    assert(pairs.head._2 == "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    assert(pairs(1)._1 == "spark.sql.extensions")
    assert(pairs(1)._2 == "io.delta.sql.DeltaSparkSessionExtension")
  }

  // ── Phase F.2: Structured Streaming codegen ──────────────────────────────

  test("streaming imports are always emitted (Phase F.2)") {
    // The Trigger / StreamingQuery / OutputMode imports cost nothing in
    // batch programs (Scala 3 doesn't warn on unused imports in this
    // style) and surface the streaming surface area for any module
    // that flips a single block to streaming.  Keeping them
    // unconditional avoids a second emit-time scan and saves the
    // user from a confusing "type not found" if they paste in a
    // Trigger expression as a quick test.
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("import org.apache.spark.sql.streaming.{Trigger, StreamingQuery, OutputMode}"),
      s"expected streaming imports to be present, got:\n$code")
  }

  test("non-streaming module does NOT emit awaitTermination shim (Phase F.2)") {
    // Detection key: presence of `spark.readStream` or `.writeStream`
    // in user code.  A pure batch module misses both, so no shim
    // lands; the @main body ends with the unchanged `spark.stop()`
    // line directly.
    val code = gen(
      """|# Batch only
         |```scalascript
         |val ds = Dataset.of(1, 2, 3)
         |ds.collect().foreach(println)
         |```
         |""".stripMargin
    )
    assert(!code.contains("spark.streams.active.headOption"),
      s"batch module must NOT emit awaitTermination shim, got:\n$code")
    assert(!code.contains("awaitTermination"),
      s"batch module must NOT emit awaitTermination shim, got:\n$code")
  }

  test("streaming module emits awaitTermination shim (Phase F.2)") {
    // `spark.readStream` is the canonical streaming entry point and
    // is detected verbatim.  The auto-emitted shim pins the first
    // active query and waits — without this the driver returns
    // before any micro-batch runs.
    val code = gen(
      """|# Streaming
         |```scalascript
         |val stream = spark.readStream.format("rate").load()
         |val query = stream.writeStream.format("console").start()
         |```
         |""".stripMargin
    )
    assert(code.contains("spark.streams.active.headOption.foreach(_.awaitTermination())"),
      s"streaming module must emit awaitTermination shim, got:\n$code")
    // Shim arrives before `spark.stop()` so the engine has a chance
    // to process data; if the order flipped, stop would race the
    // await and the program would exit immediately.
    val idxShim = code.indexOf("spark.streams.active.headOption")
    val idxStop = code.indexOf("spark.stop()")
    assert(idxShim > 0 && idxStop > idxShim,
      s"shim must precede spark.stop(); got shim=$idxShim stop=$idxStop:\n$code")
  }

  test("streaming module with user-supplied awaitTermination skips shim (Phase F.2)") {
    // Opt-out is purely textual: if the user code already mentions
    // `awaitTermination` (any form — `query.awaitTermination()`,
    // `spark.streams.awaitAnyTermination()`, even a timed variant
    // `awaitTermination(60000L)`) the shim is suppressed and user
    // intent wins.
    val code = gen(
      """|# Streaming user-controlled
         |```scalascript
         |val stream = spark.readStream.format("rate").load()
         |val query = stream.writeStream.format("console").start()
         |query.awaitTermination(60000L)
         |```
         |""".stripMargin
    )
    assert(!code.contains("spark.streams.active.headOption.foreach(_.awaitTermination())"),
      s"user-supplied awaitTermination should suppress the shim, got:\n$code")
    // User's own call still arrives in the output (verifies the
    // suppression isn't accidentally stripping the user's line).
    assert(code.contains("query.awaitTermination(60000L)"),
      s"user's awaitTermination call must remain in emit, got:\n$code")
  }

  test("writeStream alone (no readStream) still triggers the shim (Phase F.2)") {
    // Some pipelines build their streaming DataFrame via a Kafka
    // table or a temporary view and call `.writeStream` on a regular
    // DataFrame.  The substring detection picks both up so the shim
    // emits in either direction of stream construction.
    val code = gen(
      """|# Streaming writer
         |```scalascript
         |val df = spark.table("source_view")
         |df.writeStream.format("console").start()
         |```
         |""".stripMargin
    )
    assert(code.contains("spark.streams.active.headOption.foreach(_.awaitTermination())"),
      s".writeStream alone should trigger the shim, got:\n$code")
  }

  test("non-Kafka streaming module does NOT emit kafka dep (Phase F.4)") {
    // Detection key for the Kafka dep is the literal `.format("kafka")`.
    // A rate/console pipeline lacks it, so the optional dep line
    // stays absent and the header retains only the core+sql deps.
    val code = gen(
      """|# Rate/console
         |```scalascript
         |spark.readStream.format("rate").load()
         |  .writeStream.format("console").start()
         |```
         |""".stripMargin
    )
    assert(!code.contains("spark-sql-kafka-0-10"),
      s"non-Kafka module must not emit kafka dep, got:\n$code")
  }

  test("kafka format triggers spark-sql-kafka dep (Phase F.4)") {
    // `.format("kafka")` anywhere in user code adds the kafka
    // dep right after `spark-sql`.  Version pin matches the core
    // Spark version (`SparkGen.DefaultVersion`).
    val code = gen(
      """|# Kafka streaming
         |```scalascript
         |val ks = spark.readStream
         |  .format("kafka")
         |  .option("kafka.bootstrap.servers", "localhost:9092")
         |  .option("subscribe", "topic-in")
         |  .load()
         |ks.writeStream.format("console").start()
         |```
         |""".stripMargin
    )
    val expectedCoord =
      s"""//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:${SparkGen.DefaultVersion}""""
    assert(code.contains(expectedCoord),
      s"expected kafka dep line; got:\n$code")
    // Sanity: the awaitTermination shim also lands because Kafka
    // streaming pipelines always involve readStream/writeStream.
    assert(code.contains("spark.streams.active.headOption.foreach(_.awaitTermination())"),
      s"kafka streaming module should also get the awaitTermination shim, got:\n$code")
  }

  test("kafka dep version follows sparkVersion override (Phase F.4)") {
    // Same propagation as the core/sql JARs — a user pinning a
    // non-default Spark version sees the kafka coord track it.
    val code = gen(
      """|# Kafka old Spark
         |```scalascript
         |spark.readStream.format("kafka").load()
         |```
         |""".stripMargin,
      sparkVersion = "3.5.1"
    )
    assert(code.contains("""//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:3.5.1""""),
      s"kafka dep version must match sparkVersion arg, got:\n$code")
  }

  test("containsStreaming / containsAwaitTermination / containsKafkaFormat helpers (Phase F.2/F.4)") {
    // Pin the detection semantics directly — the helpers are part
    // of `SparkGen`'s public API surface (used inside genModule
    // here, but plausibly useful to tooling later).
    assert(SparkGen.containsStreaming("val s = spark.readStream.format(\"rate\").load()"))
    assert(SparkGen.containsStreaming("df.writeStream.start()"))
    assert(!SparkGen.containsStreaming("val ds = Dataset.of(1, 2, 3)"))

    assert(SparkGen.containsAwaitTermination("query.awaitTermination()"))
    assert(SparkGen.containsAwaitTermination("query.awaitTermination(60000L)"))
    assert(!SparkGen.containsAwaitTermination("val x = 1"))

    assert(SparkGen.containsKafkaFormat(""".format("kafka")"""))
    assert(!SparkGen.containsKafkaFormat(""".format("rate")"""))
    // Case sensitivity: Spark itself only accepts lowercase 'kafka',
    // so we don't bother matching `Kafka` / `KAFKA`.
    assert(!SparkGen.containsKafkaFormat(""".format("Kafka")"""))
  }

  // ── Phase F.3: file source/sink + checkpointing ──────────────────────────

  test("streaming + file format triggers checkpoint hint (Phase F.3)") {
    // File-sink streaming queries require `option("checkpointLocation",
    // …)`; missing it makes Spark refuse to `start()` at runtime.  The
    // codegen emits a `// NOTE Phase F.3` comment near the file header
    // when streaming + a file format is detected and the user code
    // hasn't already set the option.
    val code = gen(
      """|# Streaming file sink
         |```scalascript
         |val s = spark.readStream.schema(schema).parquet("/in")
         |s.writeStream.format("parquet").option("path", "/out").start()
         |```
         |""".stripMargin
    )
    assert(code.contains("NOTE Phase F.3"),
      s"streaming + file format must emit checkpoint hint, got:\n$code")
    assert(code.contains("checkpointLocation"),
      s"hint must mention checkpointLocation, got:\n$code")
  }

  test("streaming + user-supplied checkpointLocation suppresses hint (Phase F.3)") {
    // Same shape but the user has already set the option — the hint
    // is redundant and is suppressed.  Suppression key is the
    // literal string `checkpointLocation` anywhere in user code.
    val code = gen(
      """|# Streaming file sink with ckpt
         |```scalascript
         |val s = spark.readStream.schema(schema).parquet("/in")
         |s.writeStream.format("parquet")
         |  .option("path", "/out")
         |  .option("checkpointLocation", "/ckpt")
         |  .start()
         |```
         |""".stripMargin
    )
    assert(!code.contains("NOTE Phase F.3"),
      s"user-supplied checkpointLocation should suppress the hint, got:\n$code")
    // Sanity: the awaitTermination shim still lands.
    assert(code.contains("spark.streams.active.headOption.foreach(_.awaitTermination())"),
      s"awaitTermination shim must still emit, got:\n$code")
  }

  test("batch (non-streaming) file format does NOT emit checkpoint hint (Phase F.3)") {
    // The hint is gated on `containsStreaming` — pure batch
    // `.write.format("parquet")` doesn't need a checkpoint dir and
    // doesn't get the comment.
    val code = gen(
      """|# Batch parquet write
         |```scalascript
         |val ds = Dataset.of((1, "a"), (2, "b"))
         |ds.write.format("parquet").save("/out")
         |```
         |""".stripMargin
    )
    assert(!code.contains("NOTE Phase F.3"),
      s"batch module must NOT emit Phase F.3 hint, got:\n$code")
  }

  test("streaming console sink (non-file) does NOT emit checkpoint hint (Phase F.3)") {
    // `.format("console")` is not a file sink; the hint applies only
    // when a file format (parquet/csv/json/orc/text) is paired with
    // streaming.  This test pins that distinction.
    val code = gen(
      """|# Rate -> console
         |```scalascript
         |spark.readStream.format("rate").load()
         |  .writeStream.format("console").start()
         |```
         |""".stripMargin
    )
    assert(!code.contains("NOTE Phase F.3"),
      s"console-sink streaming must NOT emit checkpoint hint, got:\n$code")
  }

  test("containsFileStreamSink / containsCheckpointLocation helpers (Phase F.3)") {
    // Pin the detection helpers used by the F.3 logic.
    assert(SparkGen.containsFileStreamSink(""".format("parquet")"""))
    assert(SparkGen.containsFileStreamSink(""".format("CSV")"""))   // case-insensitive
    assert(SparkGen.containsFileStreamSink(""".format("json")"""))
    assert(SparkGen.containsFileStreamSink(""".format("orc")"""))
    assert(SparkGen.containsFileStreamSink(""".format("text")"""))
    // Non-file formats should not match — rate, console, kafka,
    // memory are streaming sinks/sources but not file-based.
    assert(!SparkGen.containsFileStreamSink(""".format("rate")"""))
    assert(!SparkGen.containsFileStreamSink(""".format("console")"""))
    assert(!SparkGen.containsFileStreamSink(""".format("kafka")"""))

    assert(SparkGen.containsCheckpointLocation("""option("checkpointLocation", "/ckpt")"""))
    assert(!SparkGen.containsCheckpointLocation("val x = 1"))
  }

  // ── Phase M.2 — MLlib auto-dep detection ─────────────────────────────────
  //
  // See `docs/spark-mllib.md` for the design.  Detection runs over the
  // joined post-`extractSqlFns` user-block source and triggers a single
  // `//> using dep "org.apache.spark:spark-mllib_2.13:<v>"` header line.
  // No `SparkSession.builder()` configs are needed — MLlib's
  // `VectorUDT` self-registers at class-load time.  Detection is purely
  // additive: modules that never import `org.apache.spark.ml.*` emit
  // byte-identical headers to the pre-M.2 baseline.

  test("mllib detection — import org.apache.spark.ml.feature triggers dep emit") {
    val code = gen(
      """|# MLlib feature extractor
         |```scalascript
         |import org.apache.spark.ml.feature.Tokenizer
         |val tok = Tokenizer().setInputCol("text").setOutputCol("words")
         |```
         |""".stripMargin
    )
    assert(code.contains(s"""//> using dep "org.apache.spark:spark-mllib_2.13:${SparkGen.DefaultVersion}""""),
      s"MLlib dep must appear when org.apache.spark.ml.feature is imported, got:\n$code")
  }

  test("mllib detection — import org.apache.spark.ml.classification triggers dep emit") {
    val code = gen(
      """|# Logistic Regression
         |```scalascript
         |import org.apache.spark.ml.classification.LogisticRegression
         |val lr = LogisticRegression().setMaxIter(10)
         |```
         |""".stripMargin
    )
    assert(code.contains("spark-mllib_2.13"),
      s"MLlib dep must appear for classification imports, got:\n$code")
  }

  test("mllib detection — import org.apache.spark.ml.Pipeline triggers dep emit") {
    // The top-level Pipeline class lives directly in `org.apache.spark.ml`
    // (not in a sub-package).  The regex still matches because the
    // package prefix is `org.apache.spark.ml.` — Pipeline is
    // `org.apache.spark.ml.Pipeline`.
    val code = gen(
      """|# Pipeline
         |```scalascript
         |import org.apache.spark.ml.Pipeline
         |val pipeline = Pipeline().setStages(Array.empty)
         |```
         |""".stripMargin
    )
    assert(code.contains("spark-mllib_2.13"),
      s"MLlib dep must appear for Pipeline imports, got:\n$code")
  }

  test("mllib detection — linalg-only import still triggers dep emit") {
    // `org.apache.spark.ml.linalg.{Vector, DenseVector, SparseVector}`
    // live in the `spark-mllib` JAR — there is no separate `spark-mllib-linalg`
    // sub-artifact.  A user who imports only the linalg types still
    // needs the MLlib dep, so detection must fire.
    val code = gen(
      """|# Linalg only
         |```scalascript
         |import org.apache.spark.ml.linalg.Vector
         |def featuresOf(v: Vector): Double = v.toArray.sum
         |```
         |""".stripMargin
    )
    assert(code.contains("spark-mllib_2.13"),
      s"MLlib dep must appear for linalg-only imports, got:\n$code")
  }

  test("mllib detection — three-segment o.a.s.ml.* alias triggers dep emit") {
    // Compact alias seen in tight import groups.  Regex carries this
    // form as an explicit alternative.
    val code = gen(
      """|# Alias import
         |```scalascript
         |import o.a.s.ml.classification.RandomForestClassifier
         |val rf = RandomForestClassifier()
         |```
         |""".stripMargin
    )
    assert(code.contains("spark-mllib_2.13"),
      s"MLlib dep must appear for o.a.s.ml.* alias imports, got:\n$code")
  }

  test("mllib detection — module without MLlib import emits NO MLlib dep") {
    // Pure batch / SQL module never imports `org.apache.spark.ml.*` —
    // the regex misses, no dep emit, no header bloat.
    val code = gen(
      """|# No MLlib
         |```scalascript
         |val x = Dataset.of(1, 2, 3)
         |x.foreach(println)
         |```
         |""".stripMargin
    )
    assert(!code.contains("spark-mllib_2.13"),
      s"MLlib dep must NOT appear when MLlib is not imported, got:\n$code")
  }

  test("mllib detection — commented-out import still triggers dep emit (documented)") {
    // Documented limitation in `docs/spark-mllib.md` § Architecture:
    // detection is a substring match, not a Scala parser, so a
    // commented-out `import org.apache.spark.ml.*` still matches.
    // The trade-off: regex stays trivial, and a redundant Coursier
    // resolve is cheap.  Users who genuinely don't want MLlib loaded
    // delete the line entirely rather than commenting it out.
    val code = gen(
      """|# Commented-out
         |```scalascript
         |// import org.apache.spark.ml.feature.Tokenizer
         |val x = 1
         |```
         |""".stripMargin
    )
    assert(code.contains("spark-mllib_2.13"),
      s"commented-out MLlib import still matches the substring regex (documented), got:\n$code")
  }

  test("mllib detection — substring `mllib` in a variable name does NOT trigger emit") {
    // The regex anchors on the `import` keyword + the package prefix,
    // not on a bare `mllib` substring.  A user variable named `mllibConfig`
    // (or similar) must NOT pull the MLlib dep.
    val code = gen(
      """|# Unrelated name
         |```scalascript
         |val mllibConfig = Map("a" -> "b")
         |println(mllibConfig)
         |```
         |""".stripMargin
    )
    assert(!code.contains("spark-mllib_2.13"),
      s"`mllibConfig` variable name must NOT pull MLlib dep, got:\n$code")
  }

  // ── Phase M.3 — Vector encoder shim ──────────────────────────────────────
  //
  // When `usesMllib` is true the Phase E `SscSparkEncoders` shim gains
  // an explicit `aenc_MLVector` AgnosticEncoder for
  // `org.apache.spark.ml.linalg.Vector`.  Spark ML's `Vector` is a
  // sealed trait (not a Product), so the Mirror-based `aenc_Product`
  // derivation can't reach it; the explicit given routes via Spark's
  // `VectorUDT` user-defined type so the wire-level column shape
  // matches what every MLlib operator expects.  Gated on `usesMllib`
  // so non-MLlib modules don't reference the `VectorUDT` class.

  test("mllib encoder — usesMllib emits aenc_MLVector given") {
    val code = gen(
      """|# MLlib Vector encoder
         |```scalascript
         |import org.apache.spark.ml.linalg.Vector
         |case class Sample(label: Double, features: Vector)
         |val ds = spark.createDataset(List(Sample(1.0, null)))
         |ds.show()
         |```
         |""".stripMargin
    )
    assert(code.contains("given aenc_MLVector: AgnosticEncoder[MLVector]"),
      s"aenc_MLVector given must be present when MLlib is imported, got:\n$code")
    // VectorUDT is `private[spark]` in Spark 4.0.0, so we route through
    // the public `SQLDataTypes.VectorType` singleton.  Pin both the
    // UDTEncoder wiring and the cast path.
    assert(code.contains("UDTEncoder[MLVector]"),
      s"aenc_MLVector must wire through UDTEncoder, got:\n$code")
    assert(code.contains("MLSQLDataTypes.VectorType.asInstanceOf[UserDefinedType[MLVector]]"),
      s"aenc_MLVector must obtain the UDT via SQLDataTypes.VectorType (private[spark] visibility workaround), got:\n$code")
    assert(code.contains("import org.apache.spark.ml.linalg.{Vector => MLVector, SQLDataTypes => MLSQLDataTypes}"),
      s"Vector / SQLDataTypes imports (aliased) must be in scope, got:\n$code")
  }

  test("mllib encoder — no MLlib usage means no aenc_MLVector given") {
    // Gating is critical: if the shim referenced `VectorUDT` in a
    // non-MLlib module, scala-cli compile would fail because the
    // class lives in the `spark-mllib` JAR (not pulled when MLlib
    // isn't imported).
    val code = gen(
      """|# Plain batch
         |```scalascript
         |val ds = Dataset.of(1, 2, 3)
         |ds.foreach(println)
         |```
         |""".stripMargin
    )
    assert(!code.contains("aenc_MLVector"),
      s"aenc_MLVector must NOT be emitted without MLlib usage, got:\n$code")
    assert(!code.contains("MLSQLDataTypes"),
      s"MLSQLDataTypes alias must NOT appear without MLlib usage, got:\n$code")
    assert(!code.contains("UDTEncoder"),
      s"UDTEncoder reference must NOT appear without MLlib usage, got:\n$code")
    // The shim's existing givens (primitives + collection encoders +
    // aenc_Product) must still be present — only the Vector block is gated.
    assert(code.contains("object SscSparkEncoders"),
      s"Phase E shim object must still be emitted, got:\n$code")
    assert(code.contains("inline given aenc_Product"),
      s"aenc_Product Mirror walk must still be emitted, got:\n$code")
  }

  test("mllib encoder — aliased MLVector type avoids clash with Scala collection Vector") {
    // The Phase E shim already has `aenc_Vector[E]` for the Scala
    // collection `scala.collection.immutable.Vector`.  The MLlib
    // Vector lives in `org.apache.spark.ml.linalg.Vector` and is
    // a sealed trait, not a parameterised collection.  We alias
    // the MLlib type to `MLVector` on import so both givens
    // coexist without ambiguity.
    val code = gen(
      """|# Both Vectors
         |```scalascript
         |import org.apache.spark.ml.linalg.Vector
         |case class Box(scores: Vector[Double])
         |```
         |""".stripMargin
    )
    // Scala collection Vector encoder still present.
    assert(code.contains("given aenc_Vector[E]"),
      s"Scala collection Vector encoder must remain, got:\n$code")
    // MLlib Vector encoder also emitted (because the import triggers
    // M.2 detection — the regex doesn't care that the user's case
    // class is actually using Scala collection Vector parameterised
    // on Double; the import statement is what flips the flag).
    assert(code.contains("given aenc_MLVector"),
      s"MLlib Vector encoder must also be emitted when ml.linalg.Vector is imported, got:\n$code")
  }

  test("mllib encoder — aenc_MLVector slots between collection encoders and aenc_Product") {
    // Source-ordering matters: the explicit `aenc_MLVector` must
    // appear BEFORE `aenc_Product`'s Mirror walk, so when a case
    // class has a `features: Vector` field, `summonInline[AgnosticEncoder[Vector]]`
    // resolves to the explicit given (and not to some structural
    // fallback that would fail).
    val code = gen(
      """|# Order check
         |```scalascript
         |import org.apache.spark.ml.linalg.Vector
         |```
         |""".stripMargin
    )
    val idxMap     = code.indexOf("given aenc_Map[K, V]")
    val idxMLVec   = code.indexOf("given aenc_MLVector")
    val idxProduct = code.indexOf("inline given aenc_Product")
    assert(idxMap >= 0,     s"aenc_Map missing in:\n$code")
    assert(idxMLVec >= 0,   s"aenc_MLVector missing in:\n$code")
    assert(idxProduct >= 0, s"aenc_Product missing in:\n$code")
    assert(idxMap < idxMLVec,
      s"aenc_MLVector must appear AFTER the collection encoders; got idxMap=$idxMap idxMLVec=$idxMLVec")
    assert(idxMLVec < idxProduct,
      s"aenc_MLVector must appear BEFORE aenc_Product; got idxMLVec=$idxMLVec idxProduct=$idxProduct")
  }

  // ── Phase M.4 — Pipeline example end-to-end ──────────────────────────────
  //
  // Codegen-level guard: parse the canonical `spark-mllib-pipeline.ssc`
  // example and verify the generated source emits the MLlib dep + the
  // M.3 Vector encoder shim.  Catches structural regressions in the
  // example without requiring `RUN_SPARK_INTEGRATION=1`.

  test("spark-mllib-pipeline.ssc — generated source emits MLlib dep + Vector encoder shim") {
    // Walk up from cwd looking for the repo root — mirrors
    // SparkRuntimeSmokeTest.locateRepoRoot.  Handles both running in
    // the main checkout and inside a worktree where examples/ lives
    // above the sbt working dir.
    def hasExamples(p: os.Path): Boolean =
      os.exists(p / "examples" / "spark-mllib-pipeline.ssc")
    val repoRoot = LazyList
      .iterate(os.pwd)(_ / os.up)
      .takeWhile(p => p.toString != "/")
      .find(hasExamples)
      .getOrElse(cancel(s"could not locate repo root with examples/spark-mllib-pipeline.ssc from ${os.pwd}"))
    val src  = os.read(repoRoot / "examples" / "spark-mllib-pipeline.ssc")
    val code = gen(src)
    assert(code.contains("spark-mllib_2.13"),
      s"MLlib dep must appear for the pipeline example, got:\n$code")
    assert(code.contains("given aenc_MLVector"),
      s"MLlib Vector encoder must appear for the pipeline example, got:\n$code")
    // Spot-check that the user-provided import is preserved verbatim
    // (`extractSqlFns` only strips `@SqlFn` lines, so MLlib imports
    // survive).
    assert(code.contains("import org.apache.spark.ml.Pipeline"),
      s"user MLlib import must be preserved in generated source, got:\n$code")
  }

  // ── Phase M.5 — Model save/load round-trip ───────────────────────────────
  //
  // Codegen-level guard for the M.5 example.  Verifies that the
  // generated source preserves the `model.write.overwrite().save(...)`
  // + `PipelineModel.load(...)` API surface — no SparkGen pre-pass
  // should rewrite or strip those calls.

  test("spark-mllib-model-save-load.ssc — generated source preserves save/load API surface") {
    def hasExamples(p: os.Path): Boolean =
      os.exists(p / "examples" / "spark-mllib-model-save-load.ssc")
    val repoRoot = LazyList
      .iterate(os.pwd)(_ / os.up)
      .takeWhile(p => p.toString != "/")
      .find(hasExamples)
      .getOrElse(cancel(s"example fixture missing from ${os.pwd}"))
    val src  = os.read(repoRoot / "examples" / "spark-mllib-model-save-load.ssc")
    val code = gen(src)
    // M.2 — dep emit must fire (PipelineModel import triggers MLlib).
    assert(code.contains("spark-mllib_2.13"),
      s"MLlib dep must appear for the save/load example, got:\n$code")
    // M.3 — Vector encoder shim emitted.
    assert(code.contains("given aenc_MLVector"),
      s"MLlib Vector encoder must appear for the save/load example, got:\n$code")
    // M.5 — save/load API surface preserved verbatim.
    assert(code.contains("""model.write.overwrite().save(modelPath)"""),
      s"model.write.overwrite().save must survive codegen, got:\n$code")
    assert(code.contains("""PipelineModel.load(modelPath)"""),
      s"PipelineModel.load must survive codegen, got:\n$code")
    assert(code.contains("import org.apache.spark.ml.{Pipeline, PipelineModel}"),
      s"PipelineModel import must be preserved, got:\n$code")
  }

  test("containsMllib helper — direct test cases") {
    // Pin the detection helper used by the M.2 logic.  Same shape as
    // the Phase F.3 `containsFileStreamSink` / `containsCheckpointLocation`
    // unit tests above.
    assert(SparkGen.containsMllib("import org.apache.spark.ml.feature.Tokenizer"))
    assert(SparkGen.containsMllib("import org.apache.spark.ml.classification.LogisticRegression"))
    assert(SparkGen.containsMllib("import org.apache.spark.ml.Pipeline"))
    assert(SparkGen.containsMllib("import org.apache.spark.ml.linalg.Vector"))
    assert(SparkGen.containsMllib("import o.a.s.ml.feature.HashingTF"))
    assert(SparkGen.containsMllib("import o.a.s.ml.linalg.SparseVector"))
    // Grouped import (`{A, B}`) — same `import org.apache.spark.ml.<...>`
    // prefix, the regex matches.
    assert(SparkGen.containsMllib("import org.apache.spark.ml.feature.{Tokenizer, HashingTF}"))
    // Wildcard import — same prefix.
    assert(SparkGen.containsMllib("import org.apache.spark.ml.feature.*"))

    // Negative cases — must not match.
    assert(!SparkGen.containsMllib("val x = 1"))
    assert(!SparkGen.containsMllib("import org.apache.spark.sql.SparkSession"))
    assert(!SparkGen.containsMllib("import org.apache.spark.streaming.Trigger"))
    // A variable named `mllibStuff` is not an import; the `\bimport\s+`
    // anchor saves us.
    assert(!SparkGen.containsMllib("val mllibStuff = 42"))
  }

  // ── Phase G.2 — Hive metastore / warehouse front-matter ──────────────────

  private def genHive(
      ssc:           String,
      hiveMetastore: Option[String]      = None,
      warehouse:     Option[String]      = None,
      sparkVersion:  String              = SparkGen.DefaultVersion,
      extraConfig:   Map[String, String] = Map.empty
  ): String =
    val module = Parser.parse(ssc)
    SparkGen.generate(
      module,
      sparkVersion  = sparkVersion,
      hiveMetastore = hiveMetastore,
      warehouse     = warehouse,
      extraConfig   = extraConfig
    )

  test("Phase G.2 — non-hive module emits no spark-hive dep or hive configs") {
    // Regression guard: every existing example (~141 SparkGenTest +
    // smoke examples) lacks the hive front-matter and never calls
    // `.enableHiveSupport()`.  The emit must be byte-identical to
    // today's baseline — no dep line, no catalogImplementation
    // config, no metastore URI, no warehouse dir, no
    // enableHiveSupport() chain.
    val code = genHive("# Test\n```scalascript\nval x = 1\n```\n")
    assert(!code.contains("spark-hive_2.13"),
      s"non-hive module must NOT emit spark-hive dep, got:\n$code")
    assert(!code.contains("spark.sql.catalogImplementation"),
      s"non-hive module must NOT emit catalogImplementation config, got:\n$code")
    assert(!code.contains("spark.hadoop.hive.metastore.uris"),
      s"non-hive module must NOT emit metastore URI config, got:\n$code")
    assert(!code.contains("spark.sql.warehouse.dir"),
      s"non-hive module must NOT emit warehouse dir config, got:\n$code")
    assert(!code.contains(".enableHiveSupport()"),
      s"non-hive module must NOT emit .enableHiveSupport(), got:\n$code")
  }

  test("Phase G.2 — spark-hive-metastore front-matter triggers spark-hive dep + hive wiring") {
    // Front-matter sets the Thrift URI; emitter wires the full chain:
    //   1. //> using dep "org.apache.spark:spark-hive_2.13:<v>"
    //   2. .config("spark.sql.catalogImplementation", "hive")
    //   3. .config("spark.hadoop.hive.metastore.uris", "<uri>")
    //   4. .enableHiveSupport() before .getOrCreate()
    val uri = "thrift://metastore.example.com:9083"
    val code = genHive(
      "# Test\n```scalascript\nval x = 1\n```\n",
      hiveMetastore = Some(uri)
    )
    val expectedDep =
      s"""//> using dep "org.apache.spark:spark-hive_2.13:${SparkGen.DefaultVersion}""""
    assert(code.contains(expectedDep),
      s"expected spark-hive dep:\n$expectedDep\n in:\n$code")
    assert(code.contains(""".config("spark.sql.catalogImplementation", "hive")"""),
      s"expected catalogImplementation=hive config, got:\n$code")
    assert(code.contains(s""".config("spark.hadoop.hive.metastore.uris", "$uri")"""),
      s"expected metastore URI config, got:\n$code")
    assert(code.contains(".enableHiveSupport()"),
      s"expected .enableHiveSupport() chain, got:\n$code")
    // Warehouse dir line should NOT appear when only the metastore
    // key is set — Spark falls back to its built-in default in that
    // case.
    assert(!code.contains("spark.sql.warehouse.dir"),
      s"metastore-only setup must NOT emit warehouse dir, got:\n$code")
  }

  test("Phase G.2 — spark-warehouse front-matter triggers spark-hive dep + warehouse wiring") {
    // Symmetric to the metastore case: a warehouse-only setup uses
    // Spark's embedded derby metastore at the warehouse path.  Same
    // dep + catalogImplementation switch, with the warehouse dir
    // config replacing the metastore URI line.
    val path = "/lake/warehouse"
    val code = genHive(
      "# Test\n```scalascript\nval x = 1\n```\n",
      warehouse = Some(path)
    )
    val expectedDep =
      s"""//> using dep "org.apache.spark:spark-hive_2.13:${SparkGen.DefaultVersion}""""
    assert(code.contains(expectedDep),
      s"expected spark-hive dep on warehouse-only setup, got:\n$code")
    assert(code.contains(""".config("spark.sql.catalogImplementation", "hive")"""),
      s"expected catalogImplementation=hive, got:\n$code")
    assert(code.contains(s""".config("spark.sql.warehouse.dir", "$path")"""),
      s"expected warehouse dir config, got:\n$code")
    assert(code.contains(".enableHiveSupport()"),
      s"expected .enableHiveSupport() chain on warehouse-only setup, got:\n$code")
    assert(!code.contains("spark.hadoop.hive.metastore.uris"),
      s"warehouse-only setup must NOT emit metastore URI config, got:\n$code")
  }

  test("Phase G.2 — both front-matter keys emit both .config lines + single dep + single enableHiveSupport") {
    // Real-world setup: a Thrift metastore service plus an explicit
    // warehouse directory pointing at the same shared lake.  Both
    // lines arrive; the dep + catalogImplementation switch + Hive
    // support chain are emitted exactly once.
    val uri  = "thrift://metastore.example.com:9083"
    val path = "/lake/warehouse"
    val code = genHive(
      "# Test\n```scalascript\nval x = 1\n```\n",
      hiveMetastore = Some(uri),
      warehouse     = Some(path)
    )
    assert(code.contains(s""".config("spark.hadoop.hive.metastore.uris", "$uri")"""),
      s"both-keys setup must emit metastore URI, got:\n$code")
    assert(code.contains(s""".config("spark.sql.warehouse.dir", "$path")"""),
      s"both-keys setup must emit warehouse dir, got:\n$code")
    // No double-emit on the dep / catalogImplementation / Hive
    // support chain.  Detection in `genModule` ORs the three
    // triggers so multiple flags fire one emission each.
    val depCount = "spark-hive_2.13".r.findAllIn(code).length
    assert(depCount == 1,
      s"spark-hive dep must appear exactly once, got $depCount times in:\n$code")
    val implCount = "spark.sql.catalogImplementation".r.findAllIn(code).length
    assert(implCount == 1,
      s"catalogImplementation config must appear exactly once, got $implCount times")
    val ehsCount = """\.enableHiveSupport\(\)""".r.findAllIn(code).length
    assert(ehsCount == 1,
      s".enableHiveSupport() must appear exactly once, got $ehsCount times")
  }

  test("Phase G.2 — user .enableHiveSupport() call in scalascript triggers spark-hive dep") {
    // Detection key is the textual `.enableHiveSupport()` literal
    // anywhere in user code.  No front-matter needed — the user has
    // already opted in by writing the call.  The generated builder
    // still emits the `spark-hive_2.13` dep + catalogImplementation
    // config so the JAR is on the classpath, but the
    // `.enableHiveSupport()` chain on the builder is suppressed (the
    // user's own call wins) to avoid a redundant emit.
    val code = genHive(
      """|# Test
         |```scalascript
         |val spark2 = SparkSession.builder().enableHiveSupport().getOrCreate()
         |```
         |""".stripMargin
    )
    assert(code.contains("spark-hive_2.13"),
      s"user .enableHiveSupport() must trigger spark-hive dep, got:\n$code")
    assert(code.contains(""".config("spark.sql.catalogImplementation", "hive")"""),
      s"user .enableHiveSupport() must trigger catalogImplementation=hive, got:\n$code")
    // The generated builder's own `.enableHiveSupport()` line is
    // suppressed when the user has already written their own.  The
    // user's call remains visible (we only suppress on the emitter
    // side, never strip user lines).
    val ehsCount = """\.enableHiveSupport\(\)""".r.findAllIn(code).length
    assert(ehsCount == 1,
      s".enableHiveSupport() must appear exactly once (user's own), got $ehsCount in:\n$code")
  }

  test("Phase G.2 — hive configs land after lakehouse configs and before user spark-config") {
    // Ordering contract:
    //   adaptive local defaults
    //   → lakehouse configs
    //   → Hive configs (catalogImplementation + URIs + warehouse)
    //   → user `spark-config:` map
    //   → .enableHiveSupport()
    //   → .getOrCreate()
    // The user override path is well-tested for `spark-config:` vs
    // lakehouse already (last-write-wins); this assertion pins that
    // Hive sits in the right slot between the two.
    val code = genHive(
      """|# Test
         |```scalascript
         |val df = spark.read.format("delta").load("/tmp/x")
         |```
         |""".stripMargin,
      hiveMetastore = Some("thrift://metastore.example.com:9083"),
      extraConfig   = Map("spark.foo.bar" -> "baz")
    )
    // Delta extension (lakehouse) must appear BEFORE catalogImplementation (Hive).
    val idxDelta = code.indexOf(""".config("spark.sql.extensions", "io.delta""")
    val idxHive  = code.indexOf(""".config("spark.sql.catalogImplementation", "hive")""")
    val idxUser  = code.indexOf(""".config("spark.foo.bar", "baz")""")
    val idxEhs   = code.indexOf(".enableHiveSupport()")
    val idxGet   = code.indexOf(".getOrCreate()")
    assert(idxDelta > 0 && idxHive > idxDelta,
      s"Hive catalogImplementation must come AFTER lakehouse extension; delta=$idxDelta hive=$idxHive")
    assert(idxHive > 0 && idxUser > idxHive,
      s"user spark-config must come AFTER Hive configs; hive=$idxHive user=$idxUser")
    assert(idxUser > 0 && idxEhs > idxUser,
      s".enableHiveSupport() must come AFTER user spark-config; user=$idxUser ehs=$idxEhs")
    assert(idxEhs > 0 && idxGet > idxEhs,
      s".enableHiveSupport() must come BEFORE .getOrCreate(); ehs=$idxEhs get=$idxGet")
  }

  test("Phase G.2 — spark-hive dep version tracks sparkVersion override") {
    // Same propagation as the kafka dep (F.4) and the core/sql JARs:
    // a user pinning a non-default Spark version sees the hive coord
    // track it.
    val code = genHive(
      "# Test\n```scalascript\nval x = 1\n```\n",
      hiveMetastore = Some("thrift://metastore.example.com:9083"),
      sparkVersion  = "3.5.1"
    )
    assert(code.contains("""//> using dep "org.apache.spark:spark-hive_2.13:3.5.1""""),
      s"spark-hive dep version must match sparkVersion arg, got:\n$code")
  }

  test("Phase G.2 — special characters in metastore URI / warehouse path are escaped") {
    // Values land in Scala double-quoted string literals; backslash
    // and quote must escape.  The `escape` helper covers \, ", \n,
    // \r, \t — same as the existing spark-config emitter.
    val code = genHive(
      "# Test\n```scalascript\nval x = 1\n```\n",
      hiveMetastore = Some("thrift://host:9083?key=\"value\""),
      warehouse     = Some("""C:\spark\warehouse""")
    )
    // The metastore URI's embedded quotes must be \"-escaped.
    assert(code.contains("""\"value\""""),
      s"embedded quotes in metastore URI must be escaped, got:\n$code")
    // Backslash in the warehouse path must be \\-escaped.
    assert(code.contains("""C:\\spark\\warehouse"""),
      s"backslashes in warehouse path must be escaped, got:\n$code")
  }

  test("Phase G.2 — containsEnableHiveSupport helper pins the detection contract") {
    // Pin the detection helper directly — used inside genModule and
    // potentially useful to tooling.
    assert(SparkGen.containsEnableHiveSupport(".enableHiveSupport()"))
    assert(SparkGen.containsEnableHiveSupport(
      "val spark = SparkSession.builder().enableHiveSupport().getOrCreate()"
    ))
    assert(!SparkGen.containsEnableHiveSupport("val x = 1"))
    assert(!SparkGen.containsEnableHiveSupport(".master(\"local[*]\")"))
  }

  // ── Phase G.3 — @TempView annotation ─────────────────────────────────────

  test("Phase G.3 — @TempView strips annotation and emits createOrReplaceTempView") {
    // The annotation marks a `val` declaration; the codegen strips
    // the `@TempView("name")` line and appends
    // `<varName>.createOrReplaceTempView("<viewName>")` so the
    // bound Dataset becomes queryable from any subsequent sql
    // block as `SELECT * FROM <viewName>`.
    val code = gen(
      """|# TempView basic
         |```scalascript
         |@TempView("users")
         |val users = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    // Annotation line is stripped (no `@TempView(` literal in emit).
    assert(!code.contains("@TempView("),
      s"@TempView annotation line must be stripped, got:\n$code")
    // Registration line appears with the bound var name + view name.
    assert(code.contains("""users.createOrReplaceTempView("users")"""),
      s"expected createOrReplaceTempView registration, got:\n$code")
    // The original `val` declaration survives verbatim.
    assert(code.contains("val users = Dataset.of(1, 2, 3)"),
      s"original val declaration must survive, got:\n$code")
  }

  test("Phase G.3 — @TempView with type ascription is recognised") {
    // The optional `: TypeAscription` between the var name and `=`
    // must not break detection.  The annotation parser captures-and-
    // discards it; the val declaration line lands in the output
    // with the type ascription preserved.
    val code = gen(
      """|# TempView typed
         |```scalascript
         |@TempView("orders")
         |val orders: Dataset[Order] = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    assert(!code.contains("@TempView("),
      s"@TempView annotation line must be stripped (typed val), got:\n$code")
    assert(code.contains("""orders.createOrReplaceTempView("orders")"""),
      s"typed val must still produce createOrReplaceTempView, got:\n$code")
    // Type ascription survives in emit.
    assert(code.contains("val orders: Dataset[Order] = Dataset.of(1, 2, 3)"),
      s"type ascription must survive, got:\n$code")
  }

  test("Phase G.3 — multiple @TempView annotations in one block emit multiple registrations") {
    // The regex passes over the whole block source and matches every
    // annotation independently.  Document-order preserved.  Each
    // annotation emits one registration line in the cleaned source's
    // tail.
    val code = gen(
      """|# Multiple temp views
         |```scalascript
         |@TempView("users")
         |val users = Dataset.of(1, 2, 3)
         |
         |@TempView("orders")
         |val orders = Dataset.of(10, 20, 30)
         |```
         |""".stripMargin
    )
    assert(code.contains("""users.createOrReplaceTempView("users")"""),
      s"first @TempView registration missing, got:\n$code")
    assert(code.contains("""orders.createOrReplaceTempView("orders")"""),
      s"second @TempView registration missing, got:\n$code")
    // Both annotation lines are stripped — emit must NOT contain
    // either `@TempView("...")` literal.
    assert(!code.contains("""@TempView("users")"""),
      s"first @TempView annotation must be stripped, got:\n$code")
    assert(!code.contains("""@TempView("orders")"""),
      s"second @TempView annotation must be stripped, got:\n$code")
  }

  test("Phase G.3 — @TempView composes with @SqlFn in the same block") {
    // The processing pipeline runs `extractSqlFns` first then
    // `extractTempViews` on the cleaned source.  Both annotations
    // strip in the right order; both registrations land after the
    // user code.  Verifies the chained-pass shape.
    val code = gen(
      """|# Combined
         |```scalascript
         |@SqlFn
         |def upper(s: String): String = s.toUpperCase
         |
         |@TempView("rows")
         |val rows = Dataset.of("a", "b", "c")
         |```
         |""".stripMargin
    )
    // @SqlFn → spark.udf.register
    assert(code.contains("""spark.udf.register("upper""""),
      s"@SqlFn UDF registration missing, got:\n$code")
    // @TempView → createOrReplaceTempView
    assert(code.contains("""rows.createOrReplaceTempView("rows")"""),
      s"@TempView registration missing, got:\n$code")
    // Both annotation lines stripped.
    assert(!code.contains("@SqlFn"),
      s"@SqlFn annotation must be stripped, got:\n$code")
    assert(!code.contains("@TempView("),
      s"@TempView annotation must be stripped, got:\n$code")
  }

  test("Phase G.3 — view name with hyphens / underscores survives literal quoting") {
    // The view name lands verbatim inside the emitted string literal
    // (no rewriting / escaping beyond the existing string-quote
    // semantics).  Hyphens, underscores, and digits are all legal
    // Spark view-name characters.
    val code = gen(
      """|# Special chars in view name
         |```scalascript
         |@TempView("my-table_v2")
         |val mt = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    assert(code.contains("""mt.createOrReplaceTempView("my-table_v2")"""),
      s"view name with hyphens / underscores must survive, got:\n$code")
  }

  test("Phase G.3 — module without @TempView produces byte-identical emit (regression guard)") {
    // The 141+ existing SparkGenTest cases all lack `@TempView`
    // annotations.  Their assertions must remain unchanged — the
    // regex on a substring-miss is O(n) and writes no output.
    val code = gen(
      """|# Plain
         |```scalascript
         |val users = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    assert(!code.contains("createOrReplaceTempView"),
      s"no @TempView module must NOT emit createOrReplaceTempView, got:\n$code")
    assert(!code.contains("@TempView"),
      s"no @TempView module must NOT mention @TempView, got:\n$code")
  }

  test("Phase G.3 — extractTempViews helper round-trip + signature list") {
    // Pin the helper directly — same shape as the
    // `containsEnableHiveSupport` / `extractSqlFns` helpers.
    val src =
      """|@TempView("users")
         |val users = Dataset.of(1, 2, 3)
         |
         |@TempView("orders")
         |val orders: Dataset[Order] = Dataset.of(10, 20, 30)
         |
         |val notAView = 42
         |""".stripMargin
    val (cleaned, sigs) = SparkGen.extractTempViews(src)
    // Two signatures captured in document order.
    assert(sigs == List(
      SparkGen.TempViewSig("users", "users"),
      SparkGen.TempViewSig("orders", "orders")
    ), s"expected two signatures in document order, got: $sigs")
    // Cleaned source: annotation lines gone, val lines preserved.
    assert(!cleaned.contains("@TempView"),
      s"cleaned source must drop annotation lines, got:\n$cleaned")
    assert(cleaned.contains("val users = Dataset.of(1, 2, 3)"),
      s"cleaned source must preserve users val, got:\n$cleaned")
    assert(cleaned.contains("val orders: Dataset[Order] = Dataset.of(10, 20, 30)"),
      s"cleaned source must preserve typed orders val, got:\n$cleaned")
    assert(cleaned.contains("val notAView = 42"),
      s"non-annotated val must remain untouched, got:\n$cleaned")
  }

  test("Phase G.3 — registration line lands after the val declaration (order check)") {
    // Order contract: cleaned val first, registration line after.
    // Reversed order would fail at runtime — the registration
    // references the var name, which must already be in scope.
    val code = gen(
      """|# Order check
         |```scalascript
         |@TempView("xs")
         |val xs = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    val idxVal = code.indexOf("val xs = Dataset.of(1, 2, 3)")
    val idxReg = code.indexOf("""xs.createOrReplaceTempView("xs")""")
    assert(idxVal > 0 && idxReg > idxVal,
      s"createOrReplaceTempView must come AFTER val declaration; val=$idxVal reg=$idxReg")
  }

  test("Phase G.3 — @TempView declaration with different var name than view name") {
    // Common pattern: the Scala-side var name is camelCase but the
    // SQL-side view name is snake_case or kebab-case.  Verifies the
    // captures are independent — view name comes from the
    // annotation string, var name from the val.
    val code = gen(
      """|# Decoupled names
         |```scalascript
         |@TempView("active_users")
         |val activeUsers = Dataset.of(1, 2, 3)
         |```
         |""".stripMargin
    )
    assert(code.contains("""activeUsers.createOrReplaceTempView("active_users")"""),
      s"var name and view name must be captured independently, got:\n$code")
  }
