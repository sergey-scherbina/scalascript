package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v1.25 § 9.5 Phase A — SPI integration for the Spark backend.
 *
 *  These tests cover the SPI surface only — they do **not** run
 *  scala-cli or Spark, since either would take minutes (Coursier
 *  resolves Spark JARs on first call) and may fail in environments
 *  without `scala-cli`.  End-to-end execution belongs in a separate
 *  smoke suite. */
class SparkBackendTest extends AnyFunSuite:

  private val backend = SparkBackend()

  test("backend identity exposed via Backend trait") {
    assert(backend.id == "spark")
    assert(backend.displayName.contains("Spark"))
    assert(backend.spiVersion == SpiVersion.Current)
  }

  test("capabilities: ExecutionResult output, Dataset feature, sparkVersion + sparkMaster + sparkConfig options") {
    assert(backend.capabilities.outputs.contains(OutputKind.ExecutionResult))
    assert(backend.capabilities.features.contains(Feature.Dataset))
    assert(backend.capabilities.options.contains("sparkVersion"))
    // Phase B — Spark master URL parameterisation.
    assert(backend.capabilities.options.contains("sparkMaster"))
    // Phase C.3 slice 3 — encoded spark-config front-matter map.
    assert(backend.capabilities.options.contains("sparkConfig"))
  }

  // ── Phase C.3 slice 3: spark-config codec ────────────────────────────────

  test("encodeSparkConfig sorts entries for stable encoding") {
    val out = SparkBackend.encodeSparkConfig(Map(
      "spark.executor.memory" -> "4g",
      "spark.executor.cores"  -> "2"
    ))
    // Same input map iterated in different orders by a JVM HashSet
    // would otherwise emit a different `extras("sparkConfig")` value,
    // breaking command de-duplication and the temp-file hash.
    assert(out == "spark.executor.cores=2\nspark.executor.memory=4g",
      s"expected sorted-key newline-separated encoding, got: $out")
  }

  test("decodeSparkConfig is the inverse of encodeSparkConfig") {
    val m = Map(
      "spark.executor.memory"           -> "4g",
      "spark.dynamicAllocation.enabled" -> "true",
      "spark.sql.shuffle.partitions"    -> "200"
    )
    assert(SparkBackend.decodeSparkConfig(SparkBackend.encodeSparkConfig(m)) == m)
  }

  test("decodeSparkConfig handles empty string") {
    assert(SparkBackend.decodeSparkConfig("") == Map.empty)
  }

  test("decodeSparkConfig preserves `=` in values (split on first `=` only)") {
    // Some Spark JVM options legitimately contain `=` in the value
    // (e.g. `-Dprop=value`); the codec must not eat them.
    val decoded = SparkBackend.decodeSparkConfig("spark.driver.extraJavaOptions=-Dfoo=bar")
    assert(decoded == Map("spark.driver.extraJavaOptions" -> "-Dfoo=bar"),
      s"expected `=` preserved in value, got: $decoded")
  }

  test("decodeSparkConfig drops lines without `=` and lines starting with `=`") {
    // Defensive: malformed entries (missing separator, or empty key)
    // are silently dropped rather than throwing — preserves
    // forward-compat if we ever add a different control-prefix.
    val decoded = SparkBackend.decodeSparkConfig("ok=value\nnoseparator\n=emptykey\nok2=val2")
    assert(decoded == Map("ok" -> "value", "ok2" -> "val2"),
      s"expected malformed lines dropped, got: $decoded")
  }

  test("fromYamlMap converts a java.util.Map[String, Any] into Map[String, String]") {
    val ju = new java.util.LinkedHashMap[String, Object]()
    ju.put("spark.executor.memory", "4g")
    ju.put("spark.executor.cores", java.lang.Integer.valueOf(2))     // numeric YAML
    ju.put("spark.dynamicAllocation.enabled", java.lang.Boolean.TRUE) // boolean YAML
    val m = SparkBackend.fromYamlMap(ju)
    assert(m == Map(
      "spark.executor.memory" -> "4g",
      "spark.executor.cores" -> "2",
      "spark.dynamicAllocation.enabled" -> "true"
    ), s"expected non-string YAML scalars coerced via toString, got: $m")
  }

  test("fromYamlMap returns empty for non-map input (defensive)") {
    assert(SparkBackend.fromYamlMap("not a map") == Map.empty)
    assert(SparkBackend.fromYamlMap(42)           == Map.empty)
    assert(SparkBackend.fromYamlMap(null)         == Map.empty)
  }

  test("capabilities: blockLanguages = {sql} (Phase C — wires sql block to spark.sql)") {
    // Phase C: `sql` fenced blocks compile to `spark.sql(text, namedParams)`
    // on backend=spark.  `node.js` is intentionally NOT in the set —
    // Spark consumes scalascript / scala / sql only.
    assert(backend.capabilities.blockLanguages == Set("sql"))
  }

  test("acceptedSources is empty — Spark consumes scalascript / scala only") {
    // `acceptedSources` is for source-language plugins (foreign fence
    // tags translated to IR fragments).  Spark consumes scalascript via
    // SparkGen + scala blocks pass through to scala-cli; no plugin
    // languages are bridged.
    assert(backend.acceptedSources.isEmpty)
  }

  test("intrinsics map is empty — Spark inherits Scala 3 semantics directly") {
    // Unlike JsBackend (which routes println→_println etc.) and JvmBackend,
    // Spark relies on Scala 3 standard library calls compiled by scala-cli.
    assert(backend.intrinsics.isEmpty)
  }

  test("CapabilityCheck: typical Spark Dataset module produces no diagnostics") {
    val src =
      """|---
         |name: word-count
         |backend: spark
         |---
         |
         |# Word Count
         |
         |```scalascript
         |val lines = Dataset.fromList(List("hello", "world"))
         |val words = lines.flatMap(_.split(" ").toList).count
         |```
         |""".stripMargin
    val ir    = Normalize(Parser.parse(src))
    val diags = scalascript.validate.CapabilityCheck.validate(ir, backend.capabilities, backend.id)
    assert(diags.isEmpty, s"expected no diagnostics on typical Spark module, got: $diags")
  }

  test("registered via ServiceLoader — discoverable through BackendRegistry") {
    import scalascript.plugin.BackendRegistry
    val ids = BackendRegistry.all.map(_.id).toSet
    assert(ids.contains("spark"),
      s"SparkBackend must be discoverable in BackendRegistry, got: $ids")
  }

  test("CapabilityCheck: sql blocks on Spark backend produce no diagnostic (Phase C)") {
    val src =
      """|---
         |name: sql-demo
         |backend: spark
         |---
         |
         |# Demo
         |
         |```sql
         |SELECT id, name FROM users WHERE id = ${userId}
         |```
         |""".stripMargin
    val ir    = Normalize(Parser.parse(src))
    val diags = scalascript.validate.CapabilityCheck.validate(ir, backend.capabilities, backend.id)
    assert(diags.isEmpty,
      s"sql block on Spark backend must not trigger UnknownBlockLanguage, got: $diags")
  }

  test("CapabilityCheck: sql blocks on a backend that doesn't declare them — rejected") {
    val src =
      """|# Test
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    val ir = Normalize(Parser.parse(src))
    val capsWithoutSql = Capabilities(
      features       = Set.empty,
      outputs        = Set(OutputKind.ExecutionResult),
      options        = Set.empty,
      spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
      blockLanguages = Set.empty
    )
    val diags = scalascript.validate.CapabilityCheck.validate(ir, capsWithoutSql, "other")
    assert(diags.exists {
      case Diagnostic.UnknownBlockLanguage("sql") => true
      case _ => false
    }, s"expected UnknownBlockLanguage(sql), got: $diags")
  }
