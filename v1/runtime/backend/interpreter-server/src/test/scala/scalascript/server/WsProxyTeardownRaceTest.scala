package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, RejectedExecutionException}

/** Regression for the WS-proxy teardown race.
 *
 *  A connection accepted just before shutdown reaches the upgrade-handler
 *  dispatch (`wsExecutor.execute`) after the interpreter/test has already
 *  stopped `wsExecutor`.  Before the fix that raced submit threw an
 *  uncaught `RejectedExecutionException` on the `ws-proxy-conn` virtual
 *  thread — which surfaces as a stray "Exception in thread ws-proxy-conn-N"
 *  and fails the sbt test step.  The proxy is closing and the connection is
 *  being torn down anyway, so the rejection is benign and must not escape.
 *
 *  The race is made deterministic here by shutting `wsExecutor` down
 *  *before* the client connects: the per-connection VT then always reaches
 *  the dispatch with the executor already terminated. */
class WsProxyTeardownRaceTest extends AnyFunSuite with Matchers:

  test("upgrade dispatch after wsExecutor shutdown does not throw on ws-proxy-conn") {
    val script = """# Test

```scala
onWebSocket("/echo") { ws =>
  ws.onMessage { msg => ws.send(msg) }
}
```
"""
    val module = Parser.parse(script)
    val interp = Interpreter()
    interp.run(module)

    val executor = Executors.newSingleThreadExecutor()
    val internal = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    internal.createContext("/", ex => { ex.sendResponseHeaders(404, -1); ex.close() })
    internal.setExecutor(executor)
    internal.start()

    val devNull = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
    val proxy = WsProxy(
      publicPort   = 0,
      internalAddr = InetSocketAddress("127.0.0.1", internal.getAddress.getPort),
      wsExecutor   = executor,
      log          = devNull,
      wsRoutes     = interp.wsRoutes
    )
    proxy.start()
    val publicPort = proxy.localPort
    publicPort should be > 0

    // Record uncaught RejectedExecutionExceptions raised on any
    // `ws-proxy-conn` virtual thread.  Delegate everything else to the
    // previously-installed handler so we don't swallow unrelated failures.
    val leaked = ConcurrentLinkedQueue[Throwable]()
    val prev   = Thread.getDefaultUncaughtExceptionHandler
    Thread.setDefaultUncaughtExceptionHandler { (t, e) =>
      if t.getName.startsWith("ws-proxy-conn") && e.isInstanceOf[RejectedExecutionException] then
        leaked.add(e)
      else if prev != null then prev.uncaughtException(t, e)
    }

    try
      // The teardown race, forced: stop the executor before the upgrade so
      // the per-connection VT always reaches the dispatch post-shutdown.
      executor.shutdownNow()

      val sock = Socket("127.0.0.1", publicPort)
      sock.setSoTimeout(5000)
      try
        val out = sock.getOutputStream
        val in  = sock.getInputStream
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val req =
          "GET /echo HTTP/1.1\r\n" +
          "Host: 127.0.0.1\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          s"Sec-WebSocket-Key: $key\r\n" +
          "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(req.getBytes(StandardCharsets.US_ASCII))
        out.flush()
        // Drain until the peer closes — the 101 is written before the
        // racing dispatch, so by the time the stream ends the VT has run
        // through the dispatch site (throwing, pre-fix).
        val buf = new Array[Byte](256)
        try while in.read(buf) >= 0 do () catch case _: Throwable => ()
      finally
        try sock.close() catch case _: Throwable => ()

      // Give the VT a beat to finish unwinding, then assert nothing leaked.
      val deadline = System.nanoTime() + 1_000_000_000L
      while leaked.isEmpty && System.nanoTime() < deadline do Thread.sleep(20)
      leaked.isEmpty shouldBe true
    finally
      Thread.setDefaultUncaughtExceptionHandler(prev)
      proxy.stop()
      internal.stop(0)
  }
