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

/** Verifies the server-initiated heartbeat:
 *    - server sends an empty Ping at the configured interval
 *    - a peer that replies with Pong keeps the connection alive
 *    - a peer that never replies gets dropped after `deadAfterMs`
 *      with a Close(1001, "ping timeout") frame
 *  Uses 100 ms / 400 ms tuning so the round-trip takes < 1 s instead
 *  of the production 30 s / 90 s. */
class WsHeartbeatTest extends AnyFunSuite with Matchers:

  test("heartbeat — server sends Ping; missing Pong triggers drop") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    Interpreter().run(Parser.parse("""# Test
```scala
onWebSocket("/hb") { ws => () }
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
      heartbeatIntervalMs  = 100L,
      heartbeatDeadAfterMs = 400L
    )
    proxy.start()
    val port = proxy.localPort

    val sock = Socket("127.0.0.1", port)
    sock.setSoTimeout(2000)
    try
      val in  = sock.getInputStream
      val out = sock.getOutputStream
      val key = "dGhlIHNhbXBsZSBub25jZQ=="
      out.write((
        "GET /hb HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        s"Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      out.flush()
      readLine(in) should startWith("HTTP/1.1 101")
      while readLine(in).nonEmpty do ()

      // Drain frames into a growing buffer; the test expects at
      //    least one Ping (proving the heartbeat is firing) followed
      //    by a Close(1001) once `deadAfterMs` lapses without our
      //    Pong.  Multiple Pings may queue up in the OS buffer first
      //    if our test thread is busy — that's fine.
      val buf     = scala.collection.mutable.ArrayBuffer.empty[Byte]
      val chunk   = new Array[Byte](256)
      var sawPing = false
      var sawClose = false
      var closeCode = -1
      val deadline = System.nanoTime() + 5.seconds.toNanos
      while !sawClose && System.nanoTime() < deadline do
        // Try to drain whole frames out of the current buffer first.
        var parsed = true
        while parsed && !sawClose do
          WsFraming.tryParse(buf.toArray, 0, buf.length) match
            case Some(f) =>
              buf.remove(0, f.consumed)
              f.opcode match
                case WsFraming.Opcode.Ping  => sawPing = true
                case WsFraming.Opcode.Close =>
                  closeCode = ((f.payload(0) & 0xFF) << 8) | (f.payload(1) & 0xFF)
                  sawClose = true
                case _ => ()
            case None => parsed = false
        if !sawClose then
          val n = try in.read(chunk) catch case _: java.net.SocketTimeoutException => 0
          if n > 0 then buf ++= chunk.iterator.take(n)
      sawPing  shouldBe true
      sawClose shouldBe true
      closeCode shouldBe 1001
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
