package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Unit tests for FlinkGen and BeamGen — verify generated Scala 3 + Flink/Beam source structure.
 *
 *  These tests do NOT run a Flink cluster or Beam runner.  Instead they parse a
 *  ScalaScript snippet, generate the Flink/Beam Scala source, and assert the expected
 *  structural patterns are present.
 */
class FlinkGenTest extends AnyFunSuite:

  private def genFlink(ssc: String): String =
    val module = Parser.parse(ssc)
    FlinkGen.generate(module)

  private def genBeam(ssc: String, runner: String = BeamGen.DefaultRunner): String =
    val module = Parser.parse(ssc)
    BeamGen.generate(module, runner = runner)

  private def dstreamSsc(body: String): String =
    s"# DStream test\n```scalascript\n$body\n```\n"

  // ── FlinkGen — Basic structure ────────────────────────────────────────────

  test("default Flink version is 1.20.1") {
    assert(FlinkGen.DefaultFlinkVersion == "1.20.1")
  }

  test("generated Flink file starts with scala-cli using directive") {
    val code = genFlink("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("//> using scala"), s"scala-cli directive missing, got:\n$code")
    assert(code.contains("//> using dep"), s"dep directive missing, got:\n$code")
  }

  test("generated Flink file includes flink-streaming-scala dependency") {
    val code = genFlink("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("flink-streaming-scala"),
      s"flink-streaming-scala dep directive missing, got:\n$code")
    assert(code.contains("flink-clients"),
      s"flink-clients dep directive missing, got:\n$code")
  }

  test("generated Flink file includes @main entry point") {
    val code = genFlink("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("@main def runFlinkJob"),
      s"@main entry point missing, got:\n$code")
  }

  test("generated Flink file declares _flinkMaster and _flinkParallelism variables") {
    val code = genFlink("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("_flinkMaster"),      s"_flinkMaster var missing, got:\n$code")
    assert(code.contains("_flinkParallelism"), s"_flinkParallelism var missing, got:\n$code")
  }

  // ── FlinkGen — DStream shim detection ────────────────────────────────────

  test("FlinkGen.containsDStream detects Pipeline.create") {
    assert(FlinkGen.containsDStream("""Pipeline.create("wc")"""))
    assert(FlinkGen.containsDStream("InMemory.source(List(1, 2))"))
    assert(FlinkGen.containsDStream("stream.run(Backend.Flink)"))
    assert(FlinkGen.containsDStream("val w = Window.fixed(5000L)"))
    assert(FlinkGen.containsDStream("WatermarkStrategy.atEnd"))
    assert(FlinkGen.containsDStream("Trigger.afterWatermark"))
    assert(!FlinkGen.containsDStream("val x = 1"))
  }

  test("FlinkGen DStream shim is NOT emitted when no DStream code is present") {
    val code = genFlink("# Test\n```scalascript\nval x = 1\n```\n")
    assert(!code.contains("class DStream[T]"), "DStream shim must not appear for non-DStream modules")
    assert(!code.contains("object Pipeline"),  "Pipeline shim must not appear for non-DStream modules")
  }

  test("FlinkGen DStream shim is emitted when Pipeline.create is present") {
    val code = genFlink(dstreamSsc("""val s = Pipeline.create("wc").read(InMemory.source(List(1, 2, 3)))"""))
    assert(code.contains("class DStream[T]"),    s"DStream class shim must be emitted, got:\n$code")
    assert(code.contains("object Pipeline"),     s"Pipeline shim must be emitted, got:\n$code")
    assert(code.contains("object InMemory"),     s"InMemory shim must be emitted, got:\n$code")
    assert(code.contains("object Backend"),      s"Backend shim must be emitted, got:\n$code")
    assert(code.contains("case class KV[K, V]"), s"KV case class shim must be emitted, got:\n$code")
  }

  test("FlinkGen DStream shim includes Backend.Flink and Backend.Beam aliases") {
    val code = genFlink(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("val Flink"), s"Backend.Flink alias must be in shim, got:\n$code")
    assert(code.contains("val Beam"),  s"Backend.Beam alias must be in shim, got:\n$code")
  }

  test("FlinkGen DStream shim emits Window / Trigger / WatermarkStrategy companions") {
    val code = genFlink(dstreamSsc("val w = Window.fixed(60000L)"))
    assert(code.contains("object Window"),           s"Window object shim must be emitted, got:\n$code")
    assert(code.contains("def fixed(ms: Long)"),     s"Window.fixed must be present, got:\n$code")
    assert(code.contains("object Trigger"),          s"Trigger object shim must be emitted, got:\n$code")
    assert(code.contains("object WatermarkStrategy"),s"WatermarkStrategy shim must be emitted, got:\n$code")
  }

  test("FlinkGen DStream shim emits PipelineResult and DSource type alias") {
    val code = genFlink(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("case class PipelineResult"), s"PipelineResult must be in shim, got:\n$code")
    assert(code.contains("type DSource[T]"),           s"DSource type alias must be in shim, got:\n$code")
  }

  test("FlinkGen DStream shim provides runToList, runFold, runForeach, runCount operators") {
    val code = genFlink(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("def runToList()"), s"runToList missing, got:\n$code")
    assert(code.contains("def runFold"),     s"runFold missing, got:\n$code")
    assert(code.contains("def runForeach"),  s"runForeach missing, got:\n$code")
    assert(code.contains("def runCount()"),  s"runCount missing, got:\n$code")
  }

  test("FlinkGen DStream shim provides window / withTrigger / withWatermark / timerProcessing") {
    val code = genFlink(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("def window(fn: Any)"),     s"window missing, got:\n$code")
    assert(code.contains("def withTrigger"),          s"withTrigger missing, got:\n$code")
    assert(code.contains("def withWatermark"),         s"withWatermark missing, got:\n$code")
    assert(code.contains("def withAllowedLateness"),  s"withAllowedLateness missing, got:\n$code")
    assert(code.contains("def timerProcessing"),      s"timerProcessing missing, got:\n$code")
  }

  test("FlinkGen DStream shim provides combinePerKey, keyBy, merge, flatMap") {
    val code = genFlink(dstreamSsc("""Pipeline.create("wc").read(InMemory.source(List("a")))"""))
    assert(code.contains("def keyBy"),         s"keyBy missing, got:\n$code")
    assert(code.contains("def combinePerKey"), s"combinePerKey missing, got:\n$code")
    assert(code.contains("def merge"),         s"merge missing, got:\n$code")
    assert(code.contains("def flatMap"),       s"flatMap missing, got:\n$code")
  }

  test("FlinkGen DStream shim is emitted when Backend.Flink appears without Pipeline.create") {
    val code = genFlink(dstreamSsc("val b = Backend.Flink"))
    assert(code.contains("object Backend"),
      s"Backend shim must be emitted when Backend.Flink detected, got:\n$code")
  }

  test("FlinkGen DStream shim emits Flink environment helper") {
    val code = genFlink(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("_flinkEnv"),
      s"_flinkEnv helper must be in shim, got:\n$code")
    assert(code.contains("StreamExecutionEnvironment"),
      s"StreamExecutionEnvironment reference must be in shim, got:\n$code")
  }

  test("FlinkGen DStream FlinkCapabilities includes DistributedStreams") {
    assert(FlinkCapabilities.features.contains(scalascript.backend.spi.Feature.DistributedStreams),
      "FlinkCapabilities must declare Feature.DistributedStreams")
  }

  test("FlinkGen word-count DStream pipeline generates runnable shim code") {
    val code = genFlink(
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

  test("FlinkGen DStream shim emits DSource.fromLocalSource bridge") {
    val code = genFlink(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("object DSource"),        s"DSource companion must be emitted, got:\n$code")
    assert(code.contains("def fromLocalSource"),   s"DSource.fromLocalSource must be in shim, got:\n$code")
  }

  // ── BeamGen — Basic structure ─────────────────────────────────────────────

  test("default Beam version is 2.62.0") {
    assert(BeamGen.DefaultBeamVersion == "2.62.0")
  }

  test("default Beam runner is DirectRunner") {
    assert(BeamGen.DefaultRunner == "DirectRunner")
  }

  test("generated Beam file starts with scala-cli using directive") {
    val code = genBeam("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("//> using scala"), s"scala-cli directive missing, got:\n$code")
    assert(code.contains("//> using dep"), s"dep directive missing, got:\n$code")
  }

  test("generated Beam file includes beam-sdks-java-core dependency") {
    val code = genBeam("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("beam-sdks-java-core"),
      s"beam-sdks-java-core dep directive missing, got:\n$code")
    assert(code.contains("beam-runners-direct-java"),
      s"beam-runners-direct-java dep directive missing for DirectRunner, got:\n$code")
  }

  test("generated Beam file includes flink runner dep when runner=FlinkRunner") {
    val code = genBeam("# Test\n```scalascript\nval x = 1\n```\n", runner = "FlinkRunner")
    assert(code.contains("beam-runners-flink"),
      s"beam-runners-flink dep directive must appear for FlinkRunner, got:\n$code")
  }

  test("generated Beam file includes @main entry point") {
    val code = genBeam("# Test\n```scalascript\nval x = 1\n```\n")
    assert(code.contains("@main def runBeamJob"),
      s"@main entry point missing, got:\n$code")
  }

  test("BeamGen DStream shim is emitted when Pipeline.create is present") {
    val code = genBeam(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1, 2, 3)))"""))
    assert(code.contains("class DStream[T]"),    s"DStream class shim must be emitted, got:\n$code")
    assert(code.contains("object Backend"),      s"Backend shim must be emitted, got:\n$code")
    assert(code.contains("val Beam"),            s"Backend.Beam alias must be in shim, got:\n$code")
    assert(code.contains("val Flink"),           s"Backend.Flink alias must be in shim, got:\n$code")
  }

  test("BeamGen DStream shim is emitted when Backend.Beam appears without Pipeline.create") {
    val code = genBeam(dstreamSsc("val b = Backend.Beam"))
    assert(code.contains("object Backend"),
      s"Backend shim must be emitted when Backend.Beam detected, got:\n$code")
  }

  test("BeamGen DStream shim emits Beam pipeline factory helper") {
    val code = genBeam(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("_createBeamPipeline"),
      s"_createBeamPipeline helper must be in shim, got:\n$code")
    assert(code.contains("BeamPipeline"),
      s"BeamPipeline reference must be in shim, got:\n$code")
  }

  test("BeamGen DStream shim emits PipelineOptions case class") {
    val code = genBeam(dstreamSsc("""Pipeline.create("t").read(InMemory.source(List(1)))"""))
    assert(code.contains("case class PipelineOptions"),
      s"PipelineOptions case class must be in shim, got:\n$code")
    assert(code.contains("extraProperties"),
      s"PipelineOptions.extraProperties must be in shim, got:\n$code")
  }

  test("BeamGen DStream BeamCapabilities includes DistributedStreams") {
    assert(BeamCapabilities.features.contains(scalascript.backend.spi.Feature.DistributedStreams),
      "BeamCapabilities must declare Feature.DistributedStreams")
  }

  // ── v2.1.6 — Production connectors ───────────────────────────────────────

  test("FlinkGen.containsConnector detects all connector types") {
    assert(FlinkGen.containsConnector("Kafka.source(b, t)"))
    assert(FlinkGen.containsConnector("Kafka.sink(b, t)"))
    assert(FlinkGen.containsConnector("Files.source(\"/data\", FileFormat.Parquet)"))
    assert(FlinkGen.containsConnector("Jdbc.source(url, \"tbl\")"))
    assert(FlinkGen.containsConnector("Pulsar.source(svc, t, sub)"))
    assert(FlinkGen.containsConnector("Kinesis.source(s, region)"))
    assert(!FlinkGen.containsConnector("InMemory.source(List(1))"))
  }

  test("FlinkGen connector stubs emitted when Kafka.source detected") {
    val code = genFlink(dstreamSsc("""val src = Kafka.source[String]("b", "t")"""))
    assert(code.contains("object Kafka"),   s"Kafka companion missing, got:\n$code")
    assert(code.contains("object Files"),   s"Files companion missing, got:\n$code")
    assert(code.contains("object Jdbc"),    s"Jdbc companion missing, got:\n$code")
    assert(code.contains("object Pulsar"),  s"Pulsar companion missing, got:\n$code")
    assert(code.contains("object Kinesis"), s"Kinesis companion missing, got:\n$code")
    assert(code.contains("type DSink[T]"),  s"DSink type alias missing, got:\n$code")
  }

  test("BeamGen containsConnector detects connectors") {
    assert(BeamGen.containsConnector("Kafka.source(b, t)"))
    assert(BeamGen.containsConnector("Files.source(\"/p\", FileFormat.Json)"))
    assert(!BeamGen.containsConnector("InMemory.source(List(1))"))
  }

  test("BeamGen connector stubs emitted when Jdbc.source detected") {
    val code = genBeam(dstreamSsc("""val src = Jdbc.source[String]("jdbc:pg://...", "events")"""))
    assert(code.contains("object Jdbc"),    s"Jdbc companion missing, got:\n$code")
    assert(code.contains("object Kafka"),   s"Kafka companion missing, got:\n$code")
    assert(code.contains("type DSink[T]"),  s"DSink type alias missing, got:\n$code")
  }

  // ── v2.1.7 — Stateful processing ─────────────────────────────────────────

  test("FlinkGen.containsDStream detects statefulMap / statefulFlatMap / broadcastState") {
    assert(FlinkGen.containsDStream("stream.statefulMap(0)((s, v) => (s + v, s + v))"))
    assert(FlinkGen.containsDStream("stream.statefulFlatMap(Nil)((s, v) => (s, List(v)))"))
    assert(FlinkGen.containsDStream("stream.broadcastState(products)"))
    assert(FlinkGen.containsDStream("val spec = KeyedStateSpec.value(0)"))
  }

  test("FlinkGen DStream shim emits statefulMap / statefulFlatMap / broadcastState operators") {
    val code = genFlink(dstreamSsc("""stream.statefulMap(0)((s, v) => (s, v))"""))
    assert(code.contains("def statefulMap"),     s"statefulMap missing from shim, got:\n$code")
    assert(code.contains("def statefulFlatMap"), s"statefulFlatMap missing from shim, got:\n$code")
    assert(code.contains("def broadcastState"),  s"broadcastState missing from shim, got:\n$code")
    assert(code.contains("def timerEventTime"),  s"timerEventTime missing from shim, got:\n$code")
  }

  test("FlinkGen DStream shim emits state types ValueState / MapState / ListState / BagState") {
    val code = genFlink(dstreamSsc("""stream.statefulMap(0)((s, v) => (s, v))"""))
    assert(code.contains("class ValueState[T]"), s"ValueState missing, got:\n$code")
    assert(code.contains("class MapState[K, V]"), s"MapState missing, got:\n$code")
    assert(code.contains("class ListState[T]"),   s"ListState missing, got:\n$code")
    assert(code.contains("class BagState[T]"),    s"BagState missing, got:\n$code")
  }

  test("FlinkGen DStream shim emits StateContext and KeyedStateSpec") {
    val code = genFlink(dstreamSsc("""val spec = KeyedStateSpec.value(0)"""))
    assert(code.contains("case class StateContext"),   s"StateContext missing, got:\n$code")
    assert(code.contains("case class KeyedStateSpec"), s"KeyedStateSpec missing, got:\n$code")
    assert(code.contains("object KeyedStateSpec"),     s"KeyedStateSpec companion missing, got:\n$code")
  }

  test("BeamGen.containsDStream detects stateful operators") {
    assert(BeamGen.containsDStream("stream.statefulMap(0)((s, v) => (s, v))"))
    assert(BeamGen.containsDStream("stream.broadcastState(ref)"))
    assert(BeamGen.containsDStream("val spec = KeyedStateSpec.value(0)"))
  }

  test("BeamGen DStream shim emits stateful operators and state types") {
    val code = genBeam(dstreamSsc("""stream.statefulMap(0)((s, v) => (s, v))"""))
    assert(code.contains("def statefulMap"),      s"statefulMap missing from Beam shim, got:\n$code")
    assert(code.contains("def broadcastState"),   s"broadcastState missing from Beam shim, got:\n$code")
    assert(code.contains("class ValueState[T]"),  s"ValueState missing from Beam shim, got:\n$code")
    assert(code.contains("case class StateContext"), s"StateContext missing from Beam shim, got:\n$code")
  }
