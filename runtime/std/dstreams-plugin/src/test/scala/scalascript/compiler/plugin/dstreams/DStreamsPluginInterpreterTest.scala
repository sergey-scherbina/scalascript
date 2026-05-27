package scalascript.compiler.plugin.dstreams

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter
import scalascript.interpreter.Value

class DStreamsPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(DStreamsInterpreterPlugin()))

  // ── Pipeline.create + InMemory.source ─────────────────────────────────────

  test("InMemory.runAndCollect — basic list source"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test").read(InMemory.source(List(1, 2, 3)))
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("map transforms elements"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3)))
        .map(x => x * 10)
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("filter removes non-matching elements"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3, 4, 5)))
        .filter(x => x % 2 == 0)
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(2L, 4L))

  test("map + filter pipeline"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3, 4, 5)))
        .map(x => x * 2)
        .filter(x => x > 4)
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(6L, 8L, 10L))

  test("flatMap expands elements"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3)))
        .flatMap(n => List(n, n * 10))
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(1L, 10L, 2L, 20L, 3L, 30L))

  // ── keyBy + combinePerKey ─────────────────────────────────────────────────

  test("keyBy produces KV pairs"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test")
        .read(InMemory.source(List("a", "b", "a", "c", "b", "a")))
        .keyBy(s => s)
        .combinePerKey((a, b) => a)
      InMemory.runAndCollect(stream)
      """
    )
    assert(result.asInstanceOf[List[?]].length == 3)

  test("word count with combinePerKey"):
    val result = interp.eval(
      """
      val words = List("hello", "world", "hello", "scala", "world", "hello")
      val stream = Pipeline.create("word-count")
        .read(InMemory.source(words))
        .map(w => KV(w, 1))
        .combinePerKey((a, b) => a + b)
      InMemory.runAndCollect(stream)
      """
    )
    val list = result.asInstanceOf[List[?]]
    assert(list.length == 3)
    val kvMap = list.collect {
      case v: Value.InstanceV if v.typeName == "KV" =>
        v.fields("key").toString -> v.fields("value")
    }.toMap
    assert(kvMap("StringV(hello)") == Value.IntV(3L))
    assert(kvMap("StringV(world)") == Value.IntV(2L))
    assert(kvMap("StringV(scala)") == Value.IntV(1L))

  // ── merge ─────────────────────────────────────────────────────────────────

  test("merge combines two streams"):
    val result = interp.eval(
      """
      val s1 = Pipeline.create("t").read(InMemory.source(List(1, 2, 3)))
      val s2 = Pipeline.create("t").read(InMemory.source(List(4, 5, 6)))
      InMemory.runAndCollect(s1.merge(s2))
      """
    )
    assert(result.asInstanceOf[List[?]].toSet == Set(1L, 2L, 3L, 4L, 5L, 6L))

  // ── Terminal operators ────────────────────────────────────────────────────

  test("runToList collects bounded stream"):
    val result = interp.eval(
      """
      Pipeline.create("test")
        .read(InMemory.source(List(10, 20, 30)))
        .runToList()
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("runFold sums elements"):
    val result = interp.eval(
      """
      Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3, 4, 5)))
        .runFold(0)((acc, x) => acc + x)
      """
    )
    assert(result == 15L)

  test("runForeach runs side effects"):
    val result = interp.eval(
      """
      var sum = 0
      Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3)))
        .runForeach(x => { sum = sum + x })
      sum
      """
    )
    assert(result == 6L)

  test("runCount returns element count"):
    val result = interp.eval(
      """
      Pipeline.create("test")
        .read(InMemory.source(List("a", "b", "c", "d")))
        .runCount()
      """
    )
    assert(result == 4L)

  // ── pipeline.run(Backend.Direct) ─────────────────────────────────────────

  test("pipeline.run(Backend.Direct) returns PipelineResult"):
    val result = interp.eval(
      """
      val result = Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3)))
        .map(x => x * 2)
        .run(Backend.Direct)
      result
      """
    )
    assert(result.isInstanceOf[Value.InstanceV])

  test("pipeline.run collects results accessible via __results"):
    val result = interp.eval(
      """
      val pr = Pipeline.create("test")
        .read(InMemory.source(List(1, 2, 3)))
        .map(x => x + 100)
        .run(Backend.Direct)
      pr
      """
    )
    result match
      case inst: Value.InstanceV =>
        assert(inst.typeName == "PipelineResult")
        inst.fields.get("__results") match
          case Some(Value.ListV(xs)) =>
            assert(xs == List(Value.IntV(101L), Value.IntV(102L), Value.IntV(103L)))
          case _ => fail("PipelineResult missing __results")
      case _ => fail(s"Expected PipelineResult InstanceV, got: $result")

  // ── Capability negotiation ────────────────────────────────────────────────

  test("pipeline.requires returns AtLeastOnce for simple pipeline"):
    val result = interp.eval(
      """
      Pipeline.create("test")
        .read(InMemory.source(List(1, 2)))
        .map(x => x)
        .requires()
      """
    )
    result match
      case caps: List[?] =>
        assert(caps.contains("AtLeastOnce"))
      case _ => fail(s"Expected List of capabilities, got: $result")

  // ── DSource.fromLocalSource bridge ───────────────────────────────────────

  test("DSource.fromLocalSource bridges Source[A] into DStream"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("test")
        .read(InMemory.source(List(7, 8, 9)))
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(7L, 8L, 9L))

  // ── InMemory.sourceWithTimestamps ─────────────────────────────────────────

  test("InMemory.sourceWithTimestamps extracts values"):
    val result = interp.eval(
      """
      val pairs = List((10, 1000L), (20, 2000L), (30, 3000L))
      val stream = Pipeline.create("test").read(InMemory.sourceWithTimestamps(pairs))
      InMemory.runAndCollect(stream)
      """
    )
    assert(result == List(10L, 20L, 30L))

  // ── KV constructor ────────────────────────────────────────────────────────

  test("KV constructor creates keyed pair"):
    val result = interp.eval(
      """
      val kv = KV("hello", 42)
      kv
      """
    )
    result match
      case inst: Value.InstanceV =>
        assert(inst.typeName == "KV")
        assert(inst.fields("key")   == Value.StringV("hello"))
        assert(inst.fields("value") == Value.IntV(42L))
      case _ => fail(s"Expected KV InstanceV, got: $result")

  // ── Backend singletons ────────────────────────────────────────────────────

  test("Backend.Direct is an InstanceV"):
    val result = interp.eval("Backend.Direct")
    assert(result.isInstanceOf[Value.InstanceV])

  test("Backend.Native is an InstanceV"):
    val result = interp.eval("Backend.Native")
    assert(result.isInstanceOf[Value.InstanceV])

  // ── Window / Trigger constructors ─────────────────────────────────────────

  test("Window.fixed creates window value"):
    val result = interp.eval("Window.fixed(60000)")
    assert(result.isInstanceOf[Value.InstanceV])

  test("Trigger.afterWatermark is a string tag"):
    val result = interp.eval("Trigger.afterWatermark")
    assert(result == "Trigger.AfterWatermark")

  // ── Empty source ─────────────────────────────────────────────────────────

  test("empty InMemory.source produces empty list"):
    val result = interp.eval(
      """
      InMemory.runAndCollect(Pipeline.create("t").read(InMemory.source(List())))
      """
    )
    assert(result == List.empty)

  // ── v2.1.2 — window / watermark / timerProcessing ────────────────────────

  test("window(Window.fixed) + combinePerKey aggregates bounded source"):
    // DirectRunner: bounded source is exhausted synchronously → all elements land in one
    // processing-time window → combinePerKey sees all elements → same as word-count test.
    val result = interp.eval(
      """
      val words = List("hello", "world", "hello", "scala", "world", "hello")
      val stream = Pipeline.create("window-wc")
        .read(InMemory.source(words))
        .map(w => KV(w, 1))
        .window(Window.fixed(60000))
        .combinePerKey((a, b) => a + b)
      InMemory.runAndCollect(stream)
      """
    )
    val kvMap = result.asInstanceOf[List[?]].collect {
      case v: Value.InstanceV if v.typeName == "KV" =>
        v.fields("key").toString -> v.fields("value")
    }.toMap
    assert(kvMap("StringV(hello)") == Value.IntV(3L))
    assert(kvMap("StringV(world)") == Value.IntV(2L))
    assert(kvMap("StringV(scala)") == Value.IntV(1L))

  test("withWatermark(atEnd) + window + combinePerKey"):
    val result = interp.eval(
      """
      val pairs = List(("hello", 1000L), ("world", 2000L), ("hello", 3000L))
      val stream = Pipeline.create("wm-test")
        .read(InMemory.sourceWithTimestamps(pairs))
        .map(w => KV(w, 1))
        .withWatermark(WatermarkStrategy.atEnd)
        .window(Window.fixed(60000))
        .combinePerKey((a, b) => a + b)
      InMemory.runAndCollect(stream)
      """
    )
    val kvMap = result.asInstanceOf[List[?]].collect {
      case v: Value.InstanceV if v.typeName == "KV" =>
        v.fields("key").toString -> v.fields("value")
    }.toMap
    assert(kvMap("StringV(hello)") == Value.IntV(2L))
    assert(kvMap("StringV(world)") == Value.IntV(1L))

  test("withTrigger pass-through preserves elements"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("trigger-test")
        .read(InMemory.source(List(1, 2, 3)))
        .map(x => KV(x, x * 10))
        .window(Window.global)
        .withTrigger(Trigger.afterCount(2))
        .combinePerKey((a, b) => a + b)
      InMemory.runAndCollect(stream)
      """
    )
    assert(result.asInstanceOf[List[?]].length == 3)

  test("withAllowedLateness pass-through preserves elements"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("lateness-test")
        .read(InMemory.source(List(1, 2, 3, 4, 5)))
        .filter(x => x % 2 == 0)
        .map(x => KV(x, 1))
        .window(Window.fixed(60000))
        .withAllowedLateness(5000)
        .combinePerKey((a, b) => a + b)
      InMemory.runAndCollect(stream)
      """
    )
    assert(result.asInstanceOf[List[?]].length == 2)

  test("timerProcessing fires callback for each unique key"):
    // f: K => Iterable[B]; result is DStream[KV[K, B]]
    val result = interp.eval(
      """
      val stream = Pipeline.create("timer-test")
        .read(InMemory.source(List("a", "b", "a", "c", "b", "a")))
        .keyBy(s => s)
        .combinePerKey((acc, _) => acc)
        .timerProcessing(1000)(k => List(42))
      InMemory.runAndCollect(stream)
      """
    )
    val list = result.asInstanceOf[List[?]]
    assert(list.length == 3)
    val kvs = list.collect {
      case v: Value.InstanceV if v.typeName == "KV" =>
        v.fields("key").toString -> v.fields("value")
    }.toMap
    assert(kvs.values.forall(_ == Value.IntV(42L)))

  test("window requires EventTime capability — provided by Backend.Direct"):
    // window(Window.fixed) triggers EventTime; Direct provides it in v2.1.2
    val result = interp.eval(
      """
      Pipeline.create("cap-test")
        .read(InMemory.source(List(1, 2)))
        .map(x => KV(x, 1))
        .window(Window.fixed(60000))
        .requires()
      """
    )
    result match
      case caps: List[?] =>
        assert(caps.contains("EventTime"))
        assert(caps.contains("AtLeastOnce"))
      case _ => fail(s"Expected List of capabilities, got: $result")

  test("window sliding + combinePerKey on bounded source"):
    val result = interp.eval(
      """
      val stream = Pipeline.create("sliding-test")
        .read(InMemory.source(List("x", "y", "x")))
        .map(w => KV(w, 1))
        .window(Window.sliding(60000, 30000))
        .combinePerKey((a, b) => a + b)
      InMemory.runAndCollect(stream)
      """
    )
    val kvMap = result.asInstanceOf[List[?]].collect {
      case v: Value.InstanceV if v.typeName == "KV" =>
        v.fields("key").toString -> v.fields("value")
    }.toMap
    assert(kvMap("StringV(x)") == Value.IntV(2L))
    assert(kvMap("StringV(y)") == Value.IntV(1L))
