package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Unit tests for KafkaStreamsGen — verify generated Scala 3 + Kafka Streams source structure.
 *
 *  These tests do NOT run a Kafka broker or TopologyTestDriver.  Instead they parse a
 *  ScalaScript snippet, generate the Kafka Streams Scala source, and assert the expected
 *  structural patterns are present.
 */
class KafkaStreamsGenTest extends AnyFunSuite:

  private def gen(ssc: String): String =
    val module = Parser.parse(ssc)
    KafkaStreamsGen.generate(module)

  private def dstreamSsc(body: String): String =
    s"# DStream test\n```scalascript\n$body\n```\n"

  // ── Basic structure ───────────────────────────────────────────────────────

  test("default Kafka Streams version is 3.7.1") {
    assert(KafkaStreamsGen.DefaultKafkaVersion == "3.7.1")
  }

  test("default app id is scalascript-kafka-streams") {
    assert(KafkaStreamsGen.DefaultAppId == "scalascript-kafka-streams")
  }

  test("generated file starts with scala-cli using directive") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("//> using scala"), s"scala-cli directive missing, got:\n$code")
    assert(code.contains("//> using dep"), s"dep directive missing, got:\n$code")
  }

  test("generated file includes kafka-streams dependency directive") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("org.apache.kafka:kafka-streams"),
      s"kafka-streams dep directive missing, got:\n$code")
    assert(code.contains("kafka-streams-test-utils"),
      s"test-utils dep directive missing, got:\n$code")
  }

  test("generated file includes @main entry point") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("@main def runKafkaStreamsJob"),
      s"@main entry point missing, got:\n$code")
  }

  test("generated file declares _ksAppId and _ksBrokers variables") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("_ksAppId"),   s"_ksAppId var missing, got:\n$code")
    assert(code.contains("_ksBrokers"), s"_ksBrokers var missing, got:\n$code")
  }

  // ── DStream shim detection ────────────────────────────────────────────────

  test("containsDStream detects Pipeline.create") {
    assert(KafkaStreamsGen.containsDStream("""Pipeline.create("wc")"""))
    assert(KafkaStreamsGen.containsDStream("InMemory.source(List(1, 2))"))
    assert(KafkaStreamsGen.containsDStream("stream.run(Backend.KafkaStreams)"))
    assert(KafkaStreamsGen.containsDStream("stream.run(Backend.Kafka)"))
    assert(!KafkaStreamsGen.containsDStream("val x = 1"))
  }

  test("containsKafkaSource detects Kafka.source and Kafka.changelog") {
    assert(KafkaStreamsGen.containsKafkaSource("Kafka.source(\"my-topic\")"))
    assert(KafkaStreamsGen.containsKafkaSource("Kafka.changelog(\"changes\")"))
    assert(!KafkaStreamsGen.containsKafkaSource("InMemory.source(List(1))"))
  }

  test("DStream shim is NOT emitted when no DStream code is present") {
    val code = gen("# Test\n```scalascript\nval x = 1\n```\n")
    assert(!code.contains("class DStream[T]"), "DStream shim must not appear for non-DStream modules")
    assert(!code.contains("object Pipeline"),  "Pipeline shim must not appear for non-DStream modules")
  }

  test("DStream shim is emitted when Pipeline.create is present") {
    val code = gen(dstreamSsc("""val s = Pipeline.create("wc").read(InMemory.source(List(1, 2, 3)))"""))
    assert(code.contains("class DStream[T]"),
      s"DStream class shim must be emitted, got:\n$code")
    assert(code.contains("object Pipeline"),
      s"Pipeline companion shim must be emitted, got:\n$code")
    assert(code.contains("object InMemory"),
      s"InMemory companion shim must be emitted, got:\n$code")
    assert(code.contains("object Backend"),
      s"Backend companion shim must be emitted, got:\n$code")
    assert(code.contains("case class KV[K, V]"),
      s"KV case class shim must be emitted, got:\n$code")
  }

  test("DStream shim includes Backend.KafkaStreams and Backend.Kafka aliases") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("KafkaStreams"),
      s"Backend.KafkaStreams alias must be in shim, got:\n$code")
    assert(code.contains("val Kafka"),
      s"Backend.Kafka alias must be in shim, got:\n$code")
  }

  test("DStream shim emits Window / Trigger / WatermarkStrategy companions") {
    val code = gen(dstreamSsc("val w = Window.fixed(60000L)"))
    assert(code.contains("object Window"),
      s"Window object shim must be emitted, got:\n$code")
    assert(code.contains("def fixed(ms: Long)"),
      s"Window.fixed must be present in shim, got:\n$code")
    assert(code.contains("object Trigger"),
      s"Trigger object shim must be emitted, got:\n$code")
    assert(code.contains("object WatermarkStrategy"),
      s"WatermarkStrategy object shim must be emitted, got:\n$code")
  }

  test("DStream shim emits PipelineResult and DSource type alias") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("case class PipelineResult"),
      s"PipelineResult case class must be in shim, got:\n$code")
    assert(code.contains("type DSource[T]"),
      s"DSource type alias must be in shim, got:\n$code")
  }

  test("DStream shim provides runToList, runFold, runForeach, runCount operators") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("def runToList()"),   s"runToList missing, got:\n$code")
    assert(code.contains("def runFold"),       s"runFold missing, got:\n$code")
    assert(code.contains("def runForeach"),    s"runForeach missing, got:\n$code")
    assert(code.contains("def runCount()"),    s"runCount missing, got:\n$code")
  }

  test("DStream shim provides window / withTrigger / withWatermark / timerProcessing") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("def window(fn: Any)"),    s"window missing, got:\n$code")
    assert(code.contains("def withTrigger"),         s"withTrigger missing, got:\n$code")
    assert(code.contains("def withWatermark"),        s"withWatermark missing, got:\n$code")
    assert(code.contains("def withAllowedLateness"), s"withAllowedLateness missing, got:\n$code")
    assert(code.contains("def timerProcessing"),     s"timerProcessing missing, got:\n$code")
  }

  test("DStream shim provides combinePerKey and keyBy") {
    val code = gen(dstreamSsc("""Pipeline.create("wc").read(InMemory.source(List("a")))"""))
    assert(code.contains("def keyBy"),         s"keyBy missing, got:\n$code")
    assert(code.contains("def combinePerKey"), s"combinePerKey missing, got:\n$code")
  }

  test("DStream shim provides merge and flatMap") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("def merge"),   s"merge missing, got:\n$code")
    assert(code.contains("def flatMap"), s"flatMap missing, got:\n$code")
  }

  test("DStream shim is emitted when Backend.KafkaStreams appears without Pipeline.create") {
    val code = gen(dstreamSsc("val b = Backend.KafkaStreams"))
    assert(code.contains("object Backend"),
      s"Backend shim must be emitted when Backend.KafkaStreams detected, got:\n$code")
  }

  test("DStream KafkaStreamsCapabilities includes DistributedStreams") {
    assert(KafkaStreamsCapabilities.features.contains(scalascript.backend.spi.Feature.DistributedStreams),
      "KafkaStreamsCapabilities must declare Feature.DistributedStreams")
  }

  test("word-count DStream pipeline generates runnable shim code") {
    val code = gen(
      dstreamSsc(
        """|val words = List("hello", "world", "hello", "scala", "world", "hello")
           |val result = Pipeline.create("word-count")
           |  .read(InMemory.source(words))
           |  .map(w => KV(w, 1))
           |  .combinePerKey((a, b) => a + b)
           |  .runToList()
           |println(result)""".stripMargin
      )
    )
    assert(code.contains("class DStream[T]"),    s"DStream shim missing, got:\n$code")
    assert(code.contains("case class KV[K, V]"), s"KV shim missing, got:\n$code")
    assert(code.contains("Pipeline.create(\"word-count\")"),
      s"user Pipeline.create call must survive in generated code, got:\n$code")
    assert(code.contains(".combinePerKey((a, b) => a + b)"),
      s"user combinePerKey call must survive in generated code, got:\n$code")
  }

  test("DStream shim emits DSource.fromLocalSource bridge") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("object DSource"),
      s"DSource companion shim must be emitted, got:\n$code")
    assert(code.contains("def fromLocalSource"),
      s"DSource.fromLocalSource must be in shim, got:\n$code")
  }

  test("DStream shim emits Kafka Streams topology builder helpers") {
    val code = gen(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("_buildTopology"),
      s"_buildTopology helper must be in shim, got:\n$code")
    assert(code.contains("_runWithTestDriver"),
      s"_runWithTestDriver helper must be in shim, got:\n$code")
    assert(code.contains("TopologyTestDriver"),
      s"TopologyTestDriver reference must be in shim, got:\n$code")
  }
