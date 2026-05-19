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
    assert(code.contains("import org.apache.spark.sql.{SparkSession, Dataset, Encoder}"))
    assert(code.contains("import org.apache.spark.sql.functions._"))
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
