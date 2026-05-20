package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** `ws.id` (Sprint 6 — item #22): every accepted WebSocket exposes a
 *  stable UUID-v4 identifier on the `WebSocket` instance.  Verifies
 *  that two consecutive upgrades hand out different non-empty ids
 *  in the canonical UUID format. */
class WsIdTest extends AnyFunSuite with Matchers:

  test("ws.id — two consecutive upgrades get distinct UUID-v4 strings") {
    WsTestLock.synchronized {
    WsRoutes.clear()

    // Capture the interpreter's println output — that's how user code
    // surfaces `ws.id` to the test (no public API on Interpreter for
    // registering ad-hoc natives in-test).
    val captured = ByteArrayOutputStream()
    val out      = PrintStream(captured, true, "UTF-8")
    val interp   = Interpreter(out = out)

    val script = """# Test

```scala
onWebSocket("/id") { ws =>
  println("ID=" + ws.id)
  ws.close()
}
```
"""
    val module = Parser.parse(script)
    interp.run(module)

    val executor = Executors.newSingleThreadExecutor()
    val internal = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    internal.createContext("/", ex => { ex.sendResponseHeaders(404, -1); ex.close() })
    internal.setExecutor(executor)
    internal.start()

    val devNull = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
    val proxy   = WsProxy(
      publicPort   = 0,
      internalAddr = InetSocketAddress("127.0.0.1", internal.getAddress.getPort),
      wsExecutor   = executor,
      log          = devNull
    )
    proxy.start()
    val publicPort = proxy.localPort

    try
      def doUpgrade(): Unit =
        val sock = Socket("127.0.0.1", publicPort)
        sock.setSoTimeout(5000)
        try
          val o = sock.getOutputStream
          val i = sock.getInputStream
          val req =
            "GET /id HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
          o.write(req.getBytes(StandardCharsets.US_ASCII))
          o.flush()
          // Drain the 101 + headers up to the blank line so the upgrade
          // actually completes before we tear down the socket.
          var prev = -1
          var crlfRun = 0
          var done = false
          while !done do
            val b = i.read()
            if b < 0 then done = true
            else
              if prev == '\r' && b == '\n' then
                crlfRun += 1
                if crlfRun >= 2 then done = true
              else if b != '\r' then crlfRun = 0
              prev = b
        finally try sock.close() catch case _: Throwable => ()

      doUpgrade()
      doUpgrade()

      // Wait for both handler bodies to run.
      val deadline = System.nanoTime() + 5_000_000_000L
      while captured.toString("UTF-8").split('\n').count(_.startsWith("ID=")) < 2
            && System.nanoTime() < deadline do
        Thread.sleep(20)

      val ids = captured.toString("UTF-8").linesIterator
        .collect { case s if s.startsWith("ID=") => s.stripPrefix("ID=").trim }
        .toList
      ids should have size 2
      ids.foreach { id =>
        // UUID-v4 canonical form: 8-4-4-4-12 lowercase hex.
        id should fullyMatch regex
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
      }
      ids(0) should not equal ids(1)
    finally
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsRoutes.clear()
    }
  }
