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
    assert(code.startsWith("// Generated by ScalaScript — Apache Spark 4.0.0"),
      "expected version header at top of file")
    assert(code.contains("To run: scala-cli run this.scala"),
      "expected run instructions in header")
    assert(code.contains("""--dep "org.apache.spark::spark-core:4.0.0""""),
      "expected spark-core dep in header")
    assert(code.contains("""--dep "org.apache.spark::spark-sql:4.0.0""""),
      "expected spark-sql dep in header")
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
    assert(code.contains("""spark-core:3.5.1"""), "expected 3.5.1 in dep instructions")
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
