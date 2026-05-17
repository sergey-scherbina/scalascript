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

/** Round-trip a binary frame:
 *    1. Client sends a masked binary (opcode 0x2) frame whose payload
 *       contains non-printable bytes (0xFF, 0x00, 0x01).
 *    2. Server's `onMessage` receives it as a Latin-1 byte-view
 *       String — the same convention `req.files(...).bytes` uses.
 *    3. Server echoes it back via `ws.sendBytes(msg)`.
 *    4. Client reads an unmasked binary frame whose payload bytes
 *       match the originals byte-for-byte. */
class WsBinaryTest extends AnyFunSuite with Matchers:

  test("ws.sendBytes — binary round-trip with non-UTF-8 bytes") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    Interpreter().run(Parser.parse("""# Test
```scala
onWebSocket("/bin") { ws =>
  ws.onMessage { msg =>
    ws.sendBytes(msg)
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
      out.write((
        "GET /bin HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      out.flush()
      // Drain handshake — read byte-by-byte until \r\n\r\n.
      var p3, p2, p1 = -1
      var done = false
      while !done do
        val b = in.read()
        if b < 0 then done = true
        else if p3 == '\r' && p2 == '\n' && p1 == '\r' && b == '\n' then done = true
        else { p3 = p2; p2 = p1; p1 = b }

      // Binary frame: opcode 0x2, FIN=1, payload = three bytes 0xFF, 0x00, 0x01.
      val payload = Array[Byte](0xFF.toByte, 0x00.toByte, 0x01.toByte)
      val mask    = Array[Byte](7, 6, 5, 4)
      val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
      val frame   = Array[Byte](0x82.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
      out.write(frame); out.flush()

      val buf = new Array[Byte](64)
      val n   = readAtLeast(in, buf, 2, 5.seconds)
      val fr  = WsFraming.tryParse(buf, 0, n).get
      fr.opcode shouldBe WsFraming.Opcode.Binary
      fr.payload.toList shouldBe payload.toList
    finally
      try sock.close() catch case _: Throwable => ()
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsRoutes.clear()
    }
  }

  private def readAtLeast(in: java.io.InputStream, buf: Array[Byte], minBytes: Int, within: FiniteDuration): Int =
    val deadline = System.nanoTime() + within.toNanos
    var off = 0
    while off < minBytes && System.nanoTime() < deadline do
      val n = in.read(buf, off, buf.length - off)
      if n < 0 then return off
      off += n
    off
