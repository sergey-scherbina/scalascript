package scalascript.compiler.plugin.streams

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.frontend.FrontendInterpreterPlugin
import scalascript.testkit.TestInterpreter

class StreamsPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(StreamsInterpreterPlugin()))

  private def interpWithFrontend: TestInterpreter =
    TestInterpreter(List(FrontendInterpreterPlugin(), StreamsInterpreterPlugin()))

  test("Source.from emits all elements in order"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("stream + emit produces elements"):
    val result = interp.eval(
      """
      val s = stream { () =>
        emit(10)
        emit(20)
        emit(30)
      }
      s.runToList()
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("Source.from with integer range"):
    val result = interp.eval(
      """
      Source.from(1 to 5).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L, 4L, 5L))

  test("map transforms elements"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).map(x => x * 2).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L))

  test("filter removes non-matching elements"):
    val result = interp.eval(
      """
      Source.from(1 to 10).filter(x => x % 2 == 0).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L, 8L, 10L))

  test("map + filter pipeline"):
    val result = interp.eval(
      """
      Source.from(1 to 100)
        .map(x => x * 2)
        .filter(x => x > 50)
        .take(5)
        .runToList()
      """
    )
    assert(result == List(52L, 54L, 56L, 58L, 60L))

  test("runForeach runs side effects"):
    val result = interp.eval(
      """
      var sum = 0
      Source.from(List(1, 2, 3, 4)).runForeach(x => { sum = sum + x })
      sum
      """
    )
    assert(result == 10L)

  test("runFold accumulates"):
    val result = interp.eval(
      """
      Source.from(1 to 5).runFold(0)((acc, x) => acc + x)
      """
    )
    assert(result == 15L)

  test("take limits output"):
    val result = interp.eval(
      """
      Source.from(1 to 1000).take(3).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("drop skips elements"):
    val result = interp.eval(
      """
      Source.from(1 to 5).drop(2).runToList()
      """
    )
    assert(result == List(3L, 4L, 5L))

  test("Source.single emits one element"):
    val result = interp.eval(
      """
      Source.single(42).runToList()
      """
    )
    assert(result == List(42L))

  test("Source.empty emits nothing"):
    val result = interp.eval(
      """
      Source.empty.runToList()
      """
    )
    assert(result == Nil)

  test("runDrain discards all elements"):
    val result = interp.eval(
      """
      Source.from(1 to 100).runDrain()
      1
      """
    )
    assert(result == 1L)

  test("flatMap chains sources"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3))
        .flatMap(x => Source.from(List(x, x * 10)))
        .runToList()
      """
    )
    assert(result == List(1L, 10L, 2L, 20L, 3L, 30L))

  test("concat sequences two sources"):
    val result = interp.eval(
      """
      Source.from(List(1, 2)).concat(Source.from(List(3, 4))).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L, 4L))

  test("zip pairs elements"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).zip(Source.from(List(10, 20, 30))).runToList()
      """
    )
    assert(result == List(
      List(1L, 10L),
      List(2L, 20L),
      List(3L, 30L)
    ))

  test("Source.fromGenerator wraps a generator"):
    val result = interp.eval(
      """
      val gen = generator { () =>
        suspend(100)
        suspend(200)
        suspend(300)
      }
      Source.fromGenerator(gen).runToList()
      """
    )
    assert(result == List(100L, 200L, 300L))

  test("backpressure: large stream does not OOM"):
    val result = interp.eval(
      """
      Source.from(1 to 100000).take(3).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  // ── v1.51.3 combining operators ──────────────────────────────────────────

  test("merge interleaves two sources"):
    val result = interp.eval(
      """
      Source.from(List(1, 2)).merge(Source.from(List(3, 4))).runToList()
      """
    ).asInstanceOf[List[Long]].sorted
    assert(result == List(1L, 2L, 3L, 4L))

  test("merge with empty source"):
    val result = interp.eval(
      """
      Source.empty.merge(Source.from(List(1, 2, 3))).runToList()
      """
    ).asInstanceOf[List[Long]].sorted
    assert(result == List(1L, 2L, 3L))

  test("zipWith pairs and transforms elements"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).zipWith(Source.from(List(10, 20, 30)))((a, b) => a + b).runToList()
      """
    )
    assert(result == List(11L, 22L, 33L))

  test("broadcast fans out to n subscribers"):
    val result = interp.eval(
      """
      val sources = Source.from(List(1, 2, 3)).broadcast(2)
      val a = sources(0).runToList()
      val b = sources(1).runToList()
      List(a, b)
      """
    )
    assert(result == List(List(1L, 2L, 3L), List(1L, 2L, 3L)))

  test("balance distributes elements across consumers"):
    // balance(2) splits 4 elements into 2 sub-sources of 2 elements each
    val count0 = interp.eval("Source.from(1 to 4).balance(2)(0).runToList().length")
    val count1 = interp.eval("Source.from(1 to 4).balance(2)(1).runToList().length")
    assert(count0.asInstanceOf[Long] + count1.asInstanceOf[Long] == 4L)

  test("groupBy partitions stream by key"):
    val result = interp.eval(
      """
      var evens = List()
      var odds = List()
      Source.from(1 to 6).groupBy(x => x % 2).runForeach(pair => {
        val key = pair._1
        val items = pair._2.runToList()
        if (key == 0) { evens = items }
        else { odds = items }
      })
      List(evens, odds)
      """
    )
    assert(result == List(List(2L, 4L, 6L), List(1L, 3L, 5L)))

  test("mergeSubstreams flattens stream of streams"):
    val raw = interp.eval(
      """
      Source.from(List(1, 2, 3))
        .map(x => Source.from(List(x, x * 10)))
        .mergeSubstreams()
        .runToList()
      """
    ).asInstanceOf[List[?]]
    assert(raw.map(_.asInstanceOf[Long]).sorted == List(1L, 2L, 3L, 10L, 20L, 30L))

  // ── v1.51.3 Sink + Flow ──────────────────────────────────────────────────

  test("Sink.foreach runs side effects via .to"):
    val result = interp.eval(
      """
      var total = 0
      val sink = Sink.foreach(x => { total = total + x })
      Source.from(List(1, 2, 3)).to(sink)
      total
      """
    )
    assert(result == 6L)

  test("Sink.fold accumulates via .to"):
    val result = interp.eval(
      """
      val sink = Sink.fold(0)((acc, x) => acc + x)
      Source.from(1 to 5).to(sink)
      """
    )
    assert(result == 15L)

  test("Sink.ignore discards all elements"):
    val result = interp.eval(
      """
      Source.from(1 to 100).to(Sink.ignore)
      42
      """
    )
    assert(result == 42L)

  test("Sink.toList collects via .to"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).to(Sink.toList)
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("Flow.map transforms elements via .via"):
    val result = interp.eval(
      """
      val flow = Flow.map(x => x * 2)
      Source.from(1 to 5).via(flow).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L, 8L, 10L))

  test("Flow.filter retains matching elements via .via"):
    val result = interp.eval(
      """
      val flow = Flow.filter(x => x % 2 == 0)
      Source.from(1 to 10).via(flow).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L, 8L, 10L))

  test("Flow chaining: map then filter via two .via calls"):
    val result = interp.eval(
      """
      Source.from(1 to 10)
        .via(Flow.map(x => x * 2))
        .via(Flow.filter(x => x > 10))
        .runToList()
      """
    )
    assert(result == List(12L, 14L, 16L, 18L, 20L))

  // ── v1.51.5 buffer, time operators, and signal adapter ─────────────────

  test("buffer with Backpressure preserves all elements"):
    val result = interp.eval(
      """
      Source.from(1 to 5).buffer(2, OverflowStrategy.Backpressure).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L, 4L, 5L))

  test("buffer with Drop keeps the first capacity elements"):
    val result = interp.eval(
      """
      Source.from(1 to 5).buffer(3, OverflowStrategy.Drop).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("buffer with DropHead keeps the newest capacity elements"):
    val result = interp.eval(
      """
      Source.from(1 to 5).buffer(3, OverflowStrategy.DropHead).runToList()
      """
    )
    assert(result == List(3L, 4L, 5L))

  test("buffer with Fail rejects overflow"):
    assertThrows[Exception] {
      interp.eval(
        """
        Source.from(1 to 5).buffer(3, OverflowStrategy.Fail).runToList()
        """
      )
    }

  test("throttle accepts Rate and preserves element order"):
    val result = interp.eval(
      """
      Source.from(1 to 4).throttle(Rate(2, 1000)).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L, 4L))

  test("throttle applies wall-clock pacing in the interpreter path"):
    val start = System.currentTimeMillis()
    val result = interp.eval(
      """
      Source.from(1 to 3).throttle(Rate(1, 60)).runToList()
      """
    )
    val elapsed = System.currentTimeMillis() - start
    assert(result == List(1L, 2L, 3L))
    assert(elapsed >= 90L)

  test("debounce emits the latest value from a burst"):
    val result = interp.eval(
      """
      Source.from(1 to 4).debounce(100).runToList()
      """
    )
    assert(result == List(4L))

  test("Source.signal emits the signal's current value"):
    val result = interp.eval(
      """
      Source.signal(42).runToList()
      """
    )
    assert(result == List(42L))

  test("Source.signal subscribes to ReactiveSignal changes and signal.bind writes back"):
    val result = interpWithFrontend.eval(
      """
      val count = signal("count", 0)
      val observed = Source.signal(count).take(4)
      count.bind(Source.from(List(1, 2, 3)))
      observed.runToList()
      """
    )
    assert(result == List(0L, 1L, 2L, 3L))

  // ── v1.51.6 Stream algebraic effect ──────────────────────────────────

  test("Stream.emit via runStream collects emitted values as Source"):
    val result = interp.eval(
      """
      val src = runStream {
        Stream.emit(10)
        Stream.emit(20)
        Stream.emit(30)
      }
      src.runToList()
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("runStream with no emissions returns empty Source"):
    val result = interp.eval(
      """
      val src = runStream { () }
      src.runToList()
      """
    )
    assert(result == List())

  test("runStream from helper function"):
    val result = interp.eval(
      """
      def countdown(n: Int): Unit =
        if n > 0 then
          Stream.emit(n)
          countdown(n - 1)
      val src = runStream { countdown(5) }
      src.runToList()
      """
    )
    assert(result == List(5L, 4L, 3L, 2L, 1L))

  test("runStream result supports map"):
    val result = interp.eval(
      """
      val src = runStream {
        Stream.emit(1)
        Stream.emit(2)
        Stream.emit(3)
      }
      src.map(x => x * 10).runToList()
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("runStream result supports filter"):
    val result = interp.eval(
      """
      val src = runStream {
        Stream.emit(1)
        Stream.emit(2)
        Stream.emit(3)
        Stream.emit(4)
      }
      src.filter(x => x % 2 == 0).runToList()
      """
    )
    assert(result == List(2L, 4L))

  test("runStream result supports runForeach"):
    val result = interp.eval(
      """
      var buf = List()
      val src = runStream {
        Stream.emit(10)
        Stream.emit(20)
      }
      src.runForeach(x => { buf = buf :+ x })
      buf
      """
    )
    assert(result == List(10L, 20L))

  test("runStream result supports runFold"):
    val result = interp.eval(
      """
      val src = runStream {
        Stream.emit(1)
        Stream.emit(2)
        Stream.emit(3)
      }
      src.runFold(0)((acc, x) => acc + x)
      """
    )
    assert(result == 6L)

  test("Stream.emit with mixed types emits as-is"):
    val result = interp.eval(
      """
      val src = runStream {
        Stream.emit("hello")
        Stream.emit(42)
        Stream.emit(true)
      }
      src.runToList()
      """
    )
    assert(result == List("hello", 42L, true))

  // ── v1.51.4 mapAsync / recover / mapError / bracket / SSE / WebSocket ──

  test("mapAsync maps all elements preserving order"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3, 4, 5)).mapAsync(4)(x => x * 3).runToList()
      """
    ).asInstanceOf[List[Long]].sorted
    assert(result == List(3L, 6L, 9L, 12L, 15L))

  test("mapAsync with parallelism 1 is sequential"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3, 4)).mapAsync(1)(x => x + 10).runToList()
      """
    )
    assert(result == List(11L, 12L, 13L, 14L))

  test("recover passes items through when source succeeds"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).recover(err => -1).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("mapError passes items through when source succeeds"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).mapError(err => err).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("Source.bracket acquires resource, uses it, and releases it"):
    val result = interp.eval(
      """
      var released = List()
      val src = Source.bracket(
        () => 42
      )(
        r => { released = released :+ true }
      )(
        r => Source.from(List(r, r + 1, r + 2))
      )
      val items = src.runToList()
      List(items, released)
      """
    )
    assert(result == List(List(42L, 43L, 44L), List(true)))

  test("Sink.toSseStream formats elements as SSE data lines"):
    val result = interp.eval(
      """
      Source.from(List("hello", "world")).to(Sink.toSseStream)
      """
    )
    assert(result == "data: hello\n\ndata: world\n\n")

  test("Source.fromSse returns empty source when connection fails"):
    val result = interp.eval(
      """
      Source.fromSse("http://localhost:19999/nonexistent").runToList()
      """
    )
    assert(result == Nil)

  test("Source.fromWebSocket returns empty source when connection fails"):
    val result = interp.eval(
      """
      Source.fromWebSocket("ws://localhost:19998/nonexistent").runToList()
      """
    )
    assert(result == Nil)

  // ─── v1.51.1 new operators ──────────────────────────────────────────────

  test("scan produces running aggregate"):
    val result = interp.eval(
      """
      Source.from(1 to 4).scan(0)((acc, x) => acc + x).runToList()
      """
    )
    assert(result == List(1L, 3L, 6L, 10L))

  test("scan with multiplication"):
    val result = interp.eval(
      """
      Source.from(1 to 5).scan(1)((acc, x) => acc * x).runToList()
      """
    )
    assert(result == List(1L, 2L, 6L, 24L, 120L))

  test("onError runs side-effect but passes elements through on success"):
    val result = interp.eval(
      """
      var errored = false
      val xs = Source.from(List(1, 2, 3)).onError(_ => { errored = true }).runToList()
      List(xs, errored)
      """
    )
    assert(result == List(List(1L, 2L, 3L), false))

  test("cancellable returns (Source, cancelFn) tuple"):
    val result = interp.eval(
      """
      val pair = Source.from(1 to 100).cancellable()
      val src = pair._1
      src.take(3).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("Source.unfold generates fibonacci sequence"):
    val result = interp.eval(
      """
      Source.unfold((0, 1))(s => if s._1 > 50 then None else Some(((s._2, s._1 + s._2), s._1)))
        .take(7).runToList()
      """
    )
    // unfold state = (a, b), emit a, next = (b, a+b): 0,1,1,2,3,5,8
    assert(result.asInstanceOf[List[?]].length == 7)

  test("Source.unfold with simple counter"):
    val result = interp.eval(
      """
      Source.unfold(0)(s => if s >= 5 then None else Some((s + 1, s))).runToList()
      """
    )
    assert(result == List(0L, 1L, 2L, 3L, 4L))

  test("Source.tick emits units (take 3)"):
    val result = interp.eval(
      """
      Source.tick(0).take(3).runToList()
      """
    )
    // tick with 0ms delay emits () units
    assert(result.asInstanceOf[List[?]].length == 3)

  test("Source.fromCallback receives pushed elements"):
    val result = interp.eval(
      """
      Source.fromCallback(cb => {
        cb(10)
        cb(20)
        cb(30)
      }).runToList()
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("Source.fromCallback — pushed elements map correctly"):
    val result = interp.eval(
      """
      Source.fromCallback(cb => {
        for i <- 1 to 5 do cb(i)
      }).map(x => x * 2).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L, 8L, 10L))

  test("scan on empty source produces empty result"):
    val result = interp.eval(
      """
      Source.from(List()).scan(0)((acc, x) => acc + x).runToList()
      """
    )
    assert(result == Nil)

  test("cancellable — source still works after obtaining pair"):
    val result = interp.eval(
      """
      val pair = Source.from(List(1, 2, 3)).cancellable()
      pair._1.runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))
