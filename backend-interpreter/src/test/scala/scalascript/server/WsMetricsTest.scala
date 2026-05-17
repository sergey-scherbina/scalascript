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

/** Sprint 4 item #14 — process-wide `metrics()` snapshot.
 *  Verifies that:
 *    - the `metrics()` native exposes the documented keys;
 *    - successful upgrades bump `ws.upgraded`;
 *    - inbound/outbound messages bump the message + byte counters;
 *    - 404 (no matching route) bumps `ws.rejected`. */
class WsMetricsTest extends AnyFunSuite with Matchers:

  test("metrics() — upgrade + message round-trip + 404 increments") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    Metrics.reset()

    val captured = ByteArrayOutputStream()
    val out      = PrintStream(captured, true, "UTF-8")
    val interp   = Interpreter(out = out)
    interp.run(Parser.parse("""# Test

```scala
onWebSocket("/m") { ws =>
  ws.onMessage { msg => ws.send("echo:" + msg) }
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

    try
      // 1. metrics() before any traffic — all zero, expected keys present.
      val before = Metrics.snapshot()
      before should contain key "ws.upgraded"
      before should contain key "ws.messages.in"
      before should contain key "ws.bytes.out"
      before should contain key "http.requests"
      before("ws.upgraded") shouldBe 0L

      // 2. Open a WS, send a frame, expect echo back; close.
      val sock = Socket("127.0.0.1", port)
      sock.setSoTimeout(5000)
      try
        val o = sock.getOutputStream; val i = sock.getInputStream
        val req = (
          "GET /m HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
          "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
          "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII)
        o.write(req); o.flush()
        // Drain headers.
        var prev = -1; var crlfRun = 0; var done = false
        while !done do
          val b = i.read()
          if b < 0 then done = true
          else
            if prev == '\r' && b == '\n' then
              crlfRun += 1; if crlfRun >= 2 then done = true
            else if b != '\r' then crlfRun = 0
            prev = b
        // Send "hi" masked text frame.
        val mask = Array[Byte](1, 2, 3, 4)
        val payload = "hi".getBytes(StandardCharsets.UTF_8)
        val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
        val frame   = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
        o.write(frame); o.flush()
        // Read at least 2 bytes of a reply frame.
        val rbuf = new Array[Byte](64)
        val deadline = System.nanoTime() + 5.seconds.toNanos
        var off = 0
        while off < 2 && System.nanoTime() < deadline do
          val n = i.read(rbuf, off, rbuf.length - off)
          if n < 0 then off = 2 else off += n
      finally try sock.close() catch case _: Throwable => ()

      // 3. Hit a non-existent WS route → should bump ws.rejected.
      val rejSock = Socket("127.0.0.1", port)
      rejSock.setSoTimeout(2000)
      try
        val rejReq = (
          "GET /missing HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
          "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
          "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII)
        rejSock.getOutputStream.write(rejReq); rejSock.getOutputStream.flush()
        // Read just the status line — the 404 itself is enough proof the
        // server processed the request and bumped the counter; whether
        // the NIO proxy actually closes the channel afterward is a
        // separate concern not under test here.
        val in = rejSock.getInputStream
        val sb = StringBuilder(); var prev = -1; var done = false
        while !done do
          val b = in.read()
          if b < 0 then done = true
          else if prev == '\r' && b == '\n' then done = true
          else { sb.append(b.toChar); prev = b }
        sb.toString should startWith ("HTTP/1.1 404")
      catch case _: java.net.SocketTimeoutException => ()
      finally try rejSock.close() catch case _: Throwable => ()

      // 4. Give the selector a moment to settle metrics state.
      val deadline = System.nanoTime() + 3.seconds.toNanos
      while (Metrics.wsUpgraded.get < 1 || Metrics.wsRejected.get < 1 || Metrics.wsMessagesIn.get < 1)
            && System.nanoTime() < deadline do
        Thread.sleep(20)

      val after = Metrics.snapshot()
      after("ws.upgraded")     should be >= 1L
      after("ws.messages.in")  should be >= 1L
      after("ws.messages.out") should be >= 1L
      after("ws.bytes.in")     should be >= 2L
      after("ws.bytes.out")    should be >= 7L  // "echo:hi" framed payload (≥7B)
      after("ws.rejected")     should be >= 1L
    finally
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsRoutes.clear()
      Metrics.reset()
    }
  }
