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

  test("capabilities: ExecutionResult output, Dataset feature, sparkVersion + sparkMaster options") {
    assert(backend.capabilities.outputs.contains(OutputKind.ExecutionResult))
    assert(backend.capabilities.features.contains(Feature.Dataset))
    assert(backend.capabilities.options.contains("sparkVersion"))
    // Phase B — Spark master URL parameterisation.
    assert(backend.capabilities.options.contains("sparkMaster"))
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
