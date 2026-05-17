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

/** Sprint 6 item #20 — per-connection rate limit.  A route registered
 *  with `onWebSocket(path, [], [], 0, n)` caps inbound messages at
 *  `n` per second per connection; overrun triggers a Close with
 *  RFC 6455 code 1008 ("policy violation"). */
class WsRateLimitTest extends AnyFunSuite with Matchers:

  test("maxMessagesPerSec — flooding past cap closes the connection with 1008") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    WsConnection.activeCount.set(0)
    WsConnection.maxActive.set(Int.MaxValue)

    // cap = 5 msgs/sec; we send 20 in a tight loop and expect a
    // server-initiated Close with status 1008.
    Interpreter().run(Parser.parse("""# Test
```scala
onWebSocket("/r", List(), List(), 0, 5) { ws =>
  ws.onMessage { _ => () }
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
      val o = sock.getOutputStream
      val i = sock.getInputStream
      val req = (
        "GET /r HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII)
      o.write(req); o.flush()
      // Drain 101 + headers up to the blank line.
      var prev = -1; var crlfRun = 0; var done = false
      while !done do
        val b = i.read()
        if b < 0 then done = true
        else
          if prev == '\r' && b == '\n' then
            crlfRun += 1; if crlfRun >= 2 then done = true
          else if b != '\r' then crlfRun = 0
          prev = b

      // Send 20 masked text frames "x" as fast as possible.
      val mask = Array[Byte](1, 2, 3, 4)
      val payload = Array[Byte]('x'.toByte)
      val masked  = Array[Byte]((payload(0) ^ mask(0)).toByte)
      val frame   = Array[Byte](0x81.toByte, (0x80 | 1).toByte) ++ mask ++ masked
      for _ <- 0 until 20 do o.write(frame)
      o.flush()

      // Read server reply.  After cap=5 messages the server should
      // queue a Close(1008).  Receive bytes until we parse the Close
      // frame (opcode=0x8) and extract the status code.
      val buf = new Array[Byte](256)
      val deadline = System.nanoTime() + 5.seconds.toNanos
      var total = 0
      var closeStatus: Option[Int] = None
      while closeStatus.isEmpty && System.nanoTime() < deadline do
        val n = i.read(buf, total, buf.length - total)
        if n < 0 then closeStatus = Some(-1) // EOF
        else
          total += n
          // Walk frames in the buffer looking for opcode 0x8.
          var offset = 0
          while offset < total && closeStatus.isEmpty do
            WsFraming.tryParse(buf, offset, total) match
              case Some(fr) =>
                offset += fr.consumed
                if fr.opcode == WsFraming.Opcode.Close then
                  closeStatus = Some(
                    if fr.payload.length >= 2 then
                      ((fr.payload(0) & 0xFF) << 8) | (fr.payload(1) & 0xFF)
                    else 0
                  )
              case None => offset = total // need more bytes
      closeStatus shouldBe Some(1008)
    finally
      try sock.close() catch case _: Throwable => ()
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsConnection.activeCount.set(0)
      WsConnection.maxActive.set(Int.MaxValue)
      WsRoutes.clear()
    }
  }
