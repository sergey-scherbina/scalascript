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

  test("capabilities: blockLanguages is empty until Phase C") {
    // Phase C wires the existing `sql` fenced block to Spark SQL on
    // backend=spark.  Until then, Spark declares no opaque-exec block
    // languages.
    assert(backend.capabilities.blockLanguages.isEmpty)
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
