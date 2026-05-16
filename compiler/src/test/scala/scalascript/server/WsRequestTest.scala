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

/** Verifies that the upgrade handler receives a `ws.request` carrying
 *  the original HTTP headers, query, and captured path params — without
 *  this WS-side auth (Authorization / Cookie / Origin) is impossible.
 *  Uses a path with a `:room` capture and a query string so all three
 *  pieces are exercised end-to-end. */
class WsRequestTest extends AnyFunSuite with Matchers:

  test("ws.request — headers, params, query reach the handler") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    val script = """# Test
```scala
onWebSocket("/room/:room") { ws =>
  ws.onMessage { _ =>
    val auth  = ws.request.headers.getOrElse("authorization", "?")
    val room  = ws.request.params.getOrElse("room",  "?")
    val mode  = ws.request.query.getOrElse("mode",   "?")
    ws.send("auth=" + auth + " room=" + room + " mode=" + mode)
  }
}
```
"""
    Interpreter().run(Parser.parse(script))

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

    val sock = Socket("127.0.0.1", port)
    sock.setSoTimeout(5000)
    try
      val out = sock.getOutputStream
      val in  = sock.getInputStream
      val key = "dGhlIHNhbXBsZSBub25jZQ=="
      out.write((
        "GET /room/lobby?mode=chat HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Authorization: Bearer xyz\r\n" +
        s"Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      out.flush()
      readLine(in) should startWith("HTTP/1.1 101")
      while readLine(in).nonEmpty do ()

      // Kick the handler with any message so it dispatches `onMessage`.
      val payload = "go".getBytes(StandardCharsets.UTF_8)
      val mask    = Array[Byte](1, 2, 3, 4)
      val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
      out.write(Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked)
      out.flush()

      val replyBuf = new Array[Byte](128)
      val n        = readAtLeast(in, replyBuf, 2, 5.seconds)
      val parsed   = WsFraming.tryParse(replyBuf, 0, n).get
      parsed.textPayload shouldBe "auth=Bearer xyz room=lobby mode=chat"
    finally
      try sock.close() catch case _: Throwable => ()
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsRoutes.clear()
    }
  }

  private def readLine(in: java.io.InputStream): String =
    val sb = StringBuilder(); var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar); prev = b
    ""

  private def readAtLeast(in: java.io.InputStream, buf: Array[Byte], minBytes: Int, within: FiniteDuration): Int =
    val deadline = System.nanoTime() + within.toNanos
    var off = 0
    while off < minBytes && System.nanoTime() < deadline do
      val n = in.read(buf, off, buf.length - off)
      if n < 0 then return off
      off += n
    off
