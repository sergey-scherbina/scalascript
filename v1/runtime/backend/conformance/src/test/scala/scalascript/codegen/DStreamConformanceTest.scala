package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Cross-backend DStream conformance suite (§14.3).
 *
 *  Each test runs the same pipeline SSC through all 4 code generators
 *  (Spark, KafkaStreams, Flink, Beam) and asserts structural conformance:
 *  every generator must recognise the pipeline as DStream code, emit the
 *  shim, and include the same operator surface in its output.
 *
 *  Actual execution-level conformance (equal output values) is covered by
 *  DStreamsPluginInterpreterTest via Backend.Direct (all operators backed
 *  by the same Seq[Any] in all 4 shims).
 */
class DStreamConformanceTest extends AnyFunSuite:

  private def genAll(ssc: String): Map[String, String] =
    val module = Parser.parse(ssc)
    Map(
      "Spark"        -> SparkGen.generate(module),
      "KafkaStreams" -> KafkaStreamsGen.generate(module),
      "Flink"        -> FlinkGen.generate(module),
      "Beam"         -> BeamGen.generate(module),
    )

  private def assertAllContain(gens: Map[String, String], needle: String): Unit =
    gens.foreach { case (name, code) =>
      assert(code.contains(needle), s"$name shim missing «$needle»")
    }

  private def assertAllDetect(ssc: String): Unit =
    assert(SparkGen.containsDStream(ssc),        s"SparkGen.containsDStream returned false")
    assert(KafkaStreamsGen.containsDStream(ssc),  s"KafkaStreamsGen.containsDStream returned false")
    assert(FlinkGen.containsDStream(ssc),         s"FlinkGen.containsDStream returned false")
    assert(BeamGen.containsDStream(ssc),          s"BeamGen.containsDStream returned false")

  private def dstreamSsc(body: String): String =
    s"# Conformance test\n```scalascript\n$body\n```\n"

  // ── 1. Word count — bounded, no event time ────────────────────────────────

  test("conformance: word count — all backends detect + emit map/combinePerKey") {
    val ssc = dstreamSsc(
      """Pipeline.create("word-count")
        |  .read(InMemory.source(List("hello world", "hello")))
        |  .flatMap(_.split(" ").toList)
        |  .map(w => KV(w, 1))
        |  .combinePerKey((a, b) => a + b)
        |  .runToList()""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    assertAllContain(gens, "class DStream[T]")
    assertAllContain(gens, "def map")
    assertAllContain(gens, "def flatMap")
    assertAllContain(gens, "def combinePerKey")
    assertAllContain(gens, "def runToList")
    assertAllContain(gens, "case class KV[K, V]")
  }

  // ── 2. Windowed word count — event-time window ────────────────────────────

  test("conformance: windowed word count — all backends emit window/withTrigger/withWatermark") {
    val ssc = dstreamSsc(
      """Pipeline.create("windowed-wc")
        |  .read(InMemory.sourceWithTimestamps(List(("hello world", 1000L))))
        |  .flatMap(_.split(" ").toList)
        |  .map(w => KV(w, 1))
        |  .withWatermark(WatermarkStrategy.atEnd)
        |  .window(Window.fixed(60000L))
        |  .withTrigger(Trigger.afterWatermark)
        |  .combinePerKey((a, b) => a + b)
        |  .runToList()""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    assertAllContain(gens, "def window")
    assertAllContain(gens, "def withTrigger")
    assertAllContain(gens, "def withWatermark")
    assertAllContain(gens, "object Window")
    assertAllContain(gens, "object Trigger")
    assertAllContain(gens, "object WatermarkStrategy")
  }

  // ── 3. Stateful running sum — keyed state, no window ─────────────────────

  test("conformance: stateful running sum — all backends emit statefulMap + state types") {
    val ssc = dstreamSsc(
      """Pipeline.create("stateful-sum")
        |  .read(InMemory.source(List(KV("a", 1), KV("a", 2), KV("b", 10))))
        |  .statefulMap(0)((state, kv) => (state + kv.value, KV(kv.key, state + kv.value)))
        |  .runToList()""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    assertAllContain(gens, "def statefulMap")
    assertAllContain(gens, "def statefulFlatMap")
    assertAllContain(gens, "def broadcastState")
    assertAllContain(gens, "class ValueState[T]")
    assertAllContain(gens, "case class StateContext")
    assertAllContain(gens, "case class KeyedStateSpec")
  }

  // ── 4. Side inputs — cross-join enrichment ────────────────────────────────

  test("conformance: side inputs — all backends emit withSideInput + SideInput/OutputTag types") {
    val ssc = dstreamSsc(
      """val si = SideInput.singleton(42)
        |Pipeline.create("side-input-enrich")
        |  .read(InMemory.source(List("a", "b")))
        |  .withSideInput(si)
        |  .map { case (elem, n) => s"$elem-$n" }
        |  .runToList()""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    assertAllContain(gens, "def withSideInput")
    assertAllContain(gens, "def sideOutput")
    assertAllContain(gens, "case class SideInput[T]")
    assertAllContain(gens, "object SideInput")
    assertAllContain(gens, "case class OutputTag[B]")
    assertAllContain(gens, "object OutputTag")
  }

  // ── 5. Windowed joins — inner join on KV keys ─────────────────────────────

  test("conformance: windowed joins — all backends emit join/leftOuterJoin/rightOuterJoin/flatten") {
    val ssc = dstreamSsc(
      """val left  = InMemory.source(List(KV("a", 1), KV("b", 2)))
        |val right = InMemory.source(List(KV("a", 10), KV("c", 30)))
        |Pipeline.create("join-test")
        |  .read(left)
        |  .join(right)
        |  .map { case KV(k, (l, r)) => KV(k, l + r) }
        |  .runToList()""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    assertAllContain(gens, "def join")
    assertAllContain(gens, "def leftOuterJoin")
    assertAllContain(gens, "def rightOuterJoin")
    assertAllContain(gens, "def flatten")
  }

  // ── 6. Connector stubs — all backends emit Kafka/Files/Jdbc stubs ─────────

  test("conformance: connectors — all backends emit Kafka/Files/Jdbc/Pulsar/Kinesis stubs") {
    val ssc = dstreamSsc(
      """val src = Kafka.source[String]("kafka:9092", "input-topic", "my-app")
        |Pipeline.create("connector-pipeline")
        |  .read(src)
        |  .map(_.toUpperCase)
        |  .write(Kafka.sink[String]("kafka:9092", "output-topic"))
        |  .run(Backend.Spark())""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    assertAllContain(gens, "object Kafka")
    assertAllContain(gens, "object Files")
    assertAllContain(gens, "object Jdbc")
    assertAllContain(gens, "object Pulsar")
    assertAllContain(gens, "object Kinesis")
    assertAllContain(gens, "type DSink[T]")
  }

  // ── 7. Backend declarations — all backends declare Backend.* aliases ───────

  test("conformance: all backends declare Backend.Spark / Backend.KafkaStreams / Backend.Flink / Backend.Beam") {
    val ssc = dstreamSsc(
      """Pipeline.create("backend-test")
        |  .read(InMemory.source(List(1, 2, 3)))
        |  .map(_ * 2)
        |  .run(Backend.Direct)""".stripMargin
    )
    assertAllDetect(ssc)
    val gens = genAll(ssc)
    // Each shim defines `object Backend` with all backend val aliases.
    assertAllContain(gens, "object Backend")
    assertAllContain(gens, "val Spark")
    assertAllContain(gens, "val KafkaStreams")
    assertAllContain(gens, "val Flink")
    assertAllContain(gens, "val Beam")
    assertAllContain(gens, "val Direct")
    assertAllContain(gens, "val Native")
  }

  // ── 8. Shim completeness — all backends emit full DStream operator surface ─

  test("conformance: all backends emit complete DStream operator surface") {
    val ssc = dstreamSsc(
      """Pipeline.create("full-surface")
        |  .read(InMemory.source(List(1)))
        |  .map(_ + 1)
        |  .run(Backend.Direct)""".stripMargin
    )
    val gens = genAll(ssc)
    val coreOps = List(
      "def map", "def filter", "def flatMap", "def keyBy",
      "def combinePerKey", "def merge", "def window",
      "def withTrigger", "def withWatermark", "def withAllowedLateness",
      "def timerProcessing", "def timerEventTime",
      "def statefulMap", "def statefulFlatMap", "def broadcastState",
      "def withSideInput", "def sideOutput",
      "def join", "def leftOuterJoin", "def rightOuterJoin", "def flatten",
      "def runToList", "def runFold", "def runForeach", "def runCount",
    )
    coreOps.foreach(op => assertAllContain(gens, op))
  }
