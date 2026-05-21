package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.concurrent.duration.*

/** Verifies the `setMaxWsConnections(n)` cap: the (n+1)-th
 *  concurrent upgrade is refused with HTTP 503 before any handler
 *  runs.  Closing one of the active sessions frees a slot, after
 *  which a fresh upgrade succeeds. */
class WsMaxConnectionsTest extends AnyFunSuite with Matchers:

  test("setMaxWsConnections — 503 past the cap; slot reclaims on close") {
    // Reset the global counters in case a previous test left them
    // populated (closeNow runs async and can lag).
    WsConnection.activeCount.set(0)
    val interp = Interpreter()
    interp.run(Parser.parse("""# Test
```scala
setMaxWsConnections(2)
onWebSocket("/cap") { ws => () }
```
"""))

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
    val port = proxy.localPort

    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    try
      // 1) Two upgrades succeed.
      val s1 = handshake(port, "/cap"); sockets += s1
      readStatus(s1) should startWith("HTTP/1.1 101")
      drainHeaders(s1)
      val s2 = handshake(port, "/cap"); sockets += s2
      readStatus(s2) should startWith("HTTP/1.1 101")
      drainHeaders(s2)

      // 2) Third upgrade is refused with 503.
      val s3 = handshake(port, "/cap"); sockets += s3
      readStatus(s3) should startWith("HTTP/1.1 503")

      // 3) Close one of the active sessions; the next upgrade succeeds.
      s1.close()
      // Give the selector / executor a moment to fire `closeNow` and
      // run the `releaseSlot` decrement.
      val deadline = System.nanoTime() + 2.seconds.toNanos
      while WsConnection.activeCount.get > 1 && System.nanoTime() < deadline do
        Thread.sleep(20)
      WsConnection.activeCount.get should be <= 1
      val s4 = handshake(port, "/cap"); sockets += s4
      readStatus(s4) should startWith("HTTP/1.1 101")
    finally
      sockets.foreach { s => try s.close() catch case _: Throwable => () }
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      // Reset globals for the next test in the suite.
      WsConnection.activeCount.set(0)
      WsConnection.maxActive.set(Int.MaxValue)
  }

  private def handshake(port: Int, path: String): Socket =
    val s = Socket("127.0.0.1", port)
    s.setSoTimeout(5000)
    val key = "dGhlIHNhbXBsZSBub25jZQ=="
    val req = (
      s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      s"Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII)
    s.getOutputStream.write(req); s.getOutputStream.flush()
    s

  private def readStatus(s: Socket): String =
    readLine(s.getInputStream)

  private def drainHeaders(s: Socket): Unit =
    while readLine(s.getInputStream).nonEmpty do ()

  private def readLine(in: java.io.InputStream): String =
    val sb = StringBuilder(); var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar); prev = b
    ""
