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

/** Verifies the fragmented-message reassembly path on the interpreter
 *  WS proxy: two client→server frames (first with FIN=0, then a
 *  Continuation with FIN=1) should join into a single `onMessage`
 *  dispatch carrying the combined payload. */
class WsFragmentTest extends AnyFunSuite with Matchers:

  test("fragmented client message — opcode=text FIN=0 + continuation FIN=1") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    val script = """# Test
```scala
onWebSocket("/frag") { ws =>
  ws.onMessage { msg =>
    ws.send("got " + msg.length + ": " + msg)
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
      // Handshake
      val key = "dGhlIHNhbXBsZSBub25jZQ=="
      out.write((
        "GET /frag HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        s"Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      out.flush()
      readLine(in) should startWith("HTTP/1.1 101")
      while readLine(in).nonEmpty do ()

      // Send two client frames: "Hello " (FIN=0, opcode=text)
      //                        "world" (FIN=1, opcode=continuation)
      val mask = Array[Byte](1, 2, 3, 4)
      def frame(opcode: Int, payload: Array[Byte], fin: Boolean): Array[Byte] =
        val b0 = (if fin then 0x80 else 0x00) | (opcode & 0x0F)
        val masked = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
        Array[Byte](b0.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
      out.write(frame(0x1, "Hello ".getBytes(StandardCharsets.UTF_8), fin = false))
      out.write(frame(0x0, "world".getBytes(StandardCharsets.UTF_8),  fin = true))
      out.flush()

      val replyBuf = new Array[Byte](64)
      val n        = readAtLeast(in, replyBuf, 2, 5.seconds)
      val parsed   = WsFraming.tryParse(replyBuf, 0, n).get
      parsed.opcode shouldBe WsFraming.Opcode.Text
      parsed.textPayload shouldBe "got 11: Hello world"
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
