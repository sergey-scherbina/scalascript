package scalascript.server

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Computation, Interpreter, Value}
import scalascript.server.spi.HttpResult

class InterpreterHttpSerialDispatchTest extends AnyFunSuite:

  private def updateMax(maximum: AtomicInteger, value: Int): Unit =
    var previous = maximum.get()
    while value > previous && !maximum.compareAndSet(previous, value) do
      previous = maximum.get()

  private def concurrentMaximum(method: String): Int =
    val interpreter = Interpreter()
    val active = AtomicInteger(0)
    val maximum = AtomicInteger(0)
    val handlerValue = Value.NativeFnV("serial-http-regression", Computation.pureFn { _ =>
      val now = active.incrementAndGet()
      updateMax(maximum, now)
      try
        Thread.sleep(100)
        Value.StringV("ok")
      finally active.decrementAndGet()
    })
    val path = "/__serial-dispatch-regression"
    Routes.register(method, path, handlerValue, interpreter)

    val sink = PrintStream(ByteArrayOutputStream())
    val handler = InterpreterHttpHandler(
      log              = sink,
      wsExecutor       = (command: Runnable) => command.run(),
      routeRegistry    = Routes,
      wsRoutes         = interpreter.wsRoutes,
      fallbackRenderer = _ => None,
      maxBodySizeBytes = () => Long.MaxValue,
      spoolThreshold   = () => Long.MaxValue,
      uploadDir        = () => "",
      corsOrigins      = () => Nil,
      corsMethods      = () => Nil,
      corsHeaders      = () => Nil,
      gzipEnabled      = () => false)

    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    def request() = pool.submit(new java.util.concurrent.Callable[HttpResult]:
      def call(): HttpResult =
        ready.countDown()
        start.await()
        handler.onHttpRequest(Request(method, path, Map.empty, Map.empty, Map.empty, "")))

    try
      val first = request()
      val second = request()
      assert(ready.await(2, TimeUnit.SECONDS))
      start.countDown()
      List(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS)).foreach {
        case HttpResult.PlainResp(response) => assert(response.status == 200 && response.body == "ok")
        case other => fail(s"expected successful response, got $other")
      }
      maximum.get()
    finally
      pool.shutdownNow()
      Routes.remove(method, path)

  test("concurrent matched mutations never overlap one interpreter handler"):
    assert(concurrentMaximum("POST") == 1)

  test("concurrent matched safe reads remain parallel"):
    assert(concurrentMaximum("GET") == 2)
