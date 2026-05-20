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

/** Sprint 6 item #19 — per-route active-connection cap.
 *  `onWebSocket(path, origins, protocols, maxConnections)` lets a
 *  noisy route (`/chat`) cap independently of a quieter route
 *  (`/admin`).  Composes with the process-wide cap: both must
 *  permit the upgrade.  Releasing the slot on close lets a fresh
 *  upgrade succeed. */
class WsRouteCapTest extends AnyFunSuite with Matchers:

  test("per-route maxConnections — cap=2 admits two, refuses a third, recovers on close") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    WsConnection.activeCount.set(0)
    WsConnection.maxActive.set(Int.MaxValue)

    Interpreter().run(Parser.parse("""# Test
```scala
onWebSocket("/chat", List(), List(), 2) { ws => () }
onWebSocket("/admin") { ws => () }
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
      log          = devNull
    )
    proxy.start()
    val port = proxy.localPort

    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    try
      // Fill /chat up to its cap.
      val a = handshake(port, "/chat"); sockets += a
      readStatus(a) should startWith("HTTP/1.1 101"); drainHeaders(a)
      val b = handshake(port, "/chat"); sockets += b
      readStatus(b) should startWith("HTTP/1.1 101"); drainHeaders(b)

      // Third upgrade on /chat refused with 503.
      val c = handshake(port, "/chat"); sockets += c
      readStatus(c) should startWith("HTTP/1.1 503")

      // Other routes unaffected — /admin admits regardless.
      val d = handshake(port, "/admin"); sockets += d
      readStatus(d) should startWith("HTTP/1.1 101"); drainHeaders(d)

      // Close one /chat; next /chat upgrade succeeds.
      a.close()
      // Wait for the per-route counter to recover.
      val deadline = System.nanoTime() + 3.seconds.toNanos
      val chatEntry = WsRoutes.all.find(_.path == "/chat").get
      while chatEntry.activeCount.get > 1 && System.nanoTime() < deadline do Thread.sleep(20)
      val e = handshake(port, "/chat"); sockets += e
      readStatus(e) should startWith("HTTP/1.1 101")
    finally
      sockets.foreach { s => try s.close() catch case _: Throwable => () }
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsConnection.activeCount.set(0)
      WsConnection.maxActive.set(Int.MaxValue)
      WsRoutes.clear()
    }
  }

  private def handshake(port: Int, path: String): Socket =
    val s = Socket("127.0.0.1", port)
    s.setSoTimeout(5000)
    val req = (
      s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
      "Sec-WebSocket-Version: 13\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII)
    s.getOutputStream.write(req); s.getOutputStream.flush()
    s

  private def readStatus(s: Socket): String = readLine(s.getInputStream)
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
