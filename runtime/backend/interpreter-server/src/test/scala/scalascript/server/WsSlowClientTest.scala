package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration.*

/** Regression guard for the slow-client head-of-line problem: a
 *  broadcast that touches every client must complete in bounded
 *  time regardless of how slow one of those clients reads.
 *
 *  Setup:
 *    - One `onWebSocket("/bus")` route that pushes every received
 *      message back to every connected client (a minimal pub/sub).
 *    - 30 "fast" clients that drain the socket immediately.
 *    - 1 "slow" client that completes the upgrade but never calls
 *      `recv` again — its kernel send-buffer fills up, future server
 *      writes to it would block (under the old blocking-IO design).
 *    - One fast client publishes a "go" message; under the new
 *      per-conn write-queue design every other fast client should
 *      see "go" within a couple of hundred milliseconds, even
 *      though the slow client is still blocking its own pipe. */
class WsSlowClientTest extends AnyFunSuite with Matchers:

  test("broadcast — slow peer does not stall the rest") {
    val script = """# Test
```scala
var clients: List[WebSocket] = List()

onWebSocket("/bus") { ws =>
  clients = ws :: clients
  ws.onMessage { msg =>
    clients.foreach { c => c.send(msg) }
  }
  ws.onClose { () =>
    clients = clients.filter(c => c != ws)
  }
}
```
"""
    val interp = Interpreter()
    interp.run(Parser.parse(script))

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

    val fastN   = 30
    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    val replies = ConcurrentLinkedQueue[String]()
    val gotReply = CountDownLatch(fastN)

    try
      // 1) Open the slow client (no reader thread — its socket buffer
      //    stays unread).
      val slow = handshake(port, "/bus")
      sockets += slow
      // Shrink its receive buffer so it fills quickly on the kernel
      // side and our writer would hit backpressure fast on a blocking
      // implementation.
      slow.setReceiveBufferSize(4096)

      // 2) Open `fastN` fast clients, each with a reader VT that
      //    collects every received text frame.
      val fasts = (1 to fastN).map { i =>
        val s = handshake(port, "/bus")
        sockets += s
        val in = s.getInputStream
        Thread.ofVirtual().name(s"fast-reader-$i").start { () =>
          val buf = new Array[Byte](256)
          // The server pushes "go" to every connected client once
          // the publisher sends it.  We only need the first frame.
          try
            val n = readAtLeast(in, buf, 2, 5.seconds)
            WsFraming.tryParse(buf, 0, n) match
              case Some(f) =>
                replies.add(f.textPayload)
                gotReply.countDown()
              case None => () // didn't make it in time
          catch case _: Throwable => ()
        }
        s
      }

      // Give the server a moment to register every connection on its
      // single-thread executor — the publish below needs `clients`
      // to contain all of them.
      Thread.sleep(200)

      // 3) Have the first fast client publish "go".
      sendText(fasts.head, "go")

      // 4) All 30 fast readers must observe "go" within 2 s.  Under
      //    the broken blocking-IO design the broadcast would stall on
      //    the slow client and most fast readers would time out.
      val arrivedInTime = gotReply.await(2, TimeUnit.SECONDS)
      withClue(s"only ${replies.size}/$fastN fast clients received the broadcast in time") {
        arrivedInTime shouldBe true
      }
      val texts = replies.toArray.toList.map(_.toString)
      texts.count(_ == "go") shouldBe fastN
    finally
      sockets.foreach { s => try s.close() catch case _: Throwable => () }
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
  }

  // ── Helpers ─────────────────────────────────────────────────────

  private def handshake(port: Int, path: String): Socket =
    val s = Socket("127.0.0.1", port)
    s.setSoTimeout(5000)
    val key = "dGhlIHNhbXBsZSBub25jZQ=="
    val req = (
      s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      s"Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII)
    s.getOutputStream.write(req)
    s.getOutputStream.flush()
    // Drain handshake response so the server doesn't see leftover
    // bytes in the OS buffer.
    val in = s.getInputStream
    while readLine(in).nonEmpty do ()
    s

  private def sendText(s: Socket, text: String): Unit =
    val payload = text.getBytes(StandardCharsets.UTF_8)
    val mask    = Array[Byte](1, 2, 3, 4)
    val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
    val frame   = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
    s.getOutputStream.write(frame)
    s.getOutputStream.flush()

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
