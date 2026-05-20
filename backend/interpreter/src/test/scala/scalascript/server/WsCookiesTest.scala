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

/** Verifies that the WS upgrade parses `Cookie:` into
 *  `ws.request.cookies: Map[String, String]` — same convention REST
 *  handlers already see — so per-user auth can read e.g. a session
 *  token without re-implementing the split.  Sends two cookies plus
 *  the throwaway one used by the existing signed-session helper. */
class WsCookiesTest extends AnyFunSuite with Matchers:

  test("ws.request.cookies — multi-cookie parsing") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    Interpreter().run(Parser.parse("""# Test
```scala
onWebSocket("/c") { ws =>
  ws.onMessage { _ =>
    val a = ws.request.cookies.getOrElse("a", "?")
    val b = ws.request.cookies.getOrElse("b", "?")
    val n = ws.request.cookies.size
    ws.send("a=" + a + " b=" + b + " n=" + n)
  }
}
```
"""))

    val executor = Executors.newSingleThreadExecutor()
    val internal = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    internal.createContext("/", ex => { ex.sendResponseHeaders(404, -1); ex.close() })
    internal.setExecutor(executor)
    internal.start()
    val devNull = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
    val proxy = WsProxy(
      publicPort           = 0,
      internalAddr         = InetSocketAddress("127.0.0.1", internal.getAddress.getPort),
      wsExecutor           = executor,
      log                  = devNull,
      heartbeatIntervalMs  = 600_000L,
      heartbeatDeadAfterMs = 1_800_000L
    )
    proxy.start()
    val port = proxy.localPort

    val sock = Socket("127.0.0.1", port)
    sock.setSoTimeout(5000)
    try
      val out = sock.getOutputStream
      val in  = sock.getInputStream
      out.write((
        "GET /c HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Cookie: a=alpha; b=bravo; session=ignored\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      out.flush()
      while readLine(in).nonEmpty do ()

      // Kick the handler.
      val payload = "go".getBytes(StandardCharsets.UTF_8)
      val mask    = Array[Byte](1, 2, 3, 4)
      val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
      out.write(Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked)
      out.flush()

      val buf = new Array[Byte](128)
      val n   = readAtLeast(in, buf, 2, 5.seconds)
      val fr  = WsFraming.tryParse(buf, 0, n).get
      fr.opcode shouldBe WsFraming.Opcode.Text
      fr.textPayload shouldBe "a=alpha b=bravo n=3"
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
