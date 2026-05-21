package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}

/** Verifies the built-in `WsRoom` type:
 *    - Three clients join a room, the first publishes a message,
 *      all three (including the publisher) receive it.
 *    - One client disconnects; `room.size` drops to 2; a second
 *      broadcast lands on exactly the remaining two. */
class WsRoomTest extends AnyFunSuite with Matchers:

  test("WsRoom — add / broadcast / remove via onClose") {
    val interp = Interpreter()
    interp.run(Parser.parse("""# Test
```scala
val room = WsRoom()
onWebSocket("/room") { ws =>
  room.add(ws)
  ws.onMessage { msg =>
    room.broadcast(msg + " [size=" + room.size() + "]")
  }
  ws.onClose { () =>
    room.remove(ws)
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
      wsRoutes             = interp.wsRoutes,
      heartbeatIntervalMs  = 600_000L,
      heartbeatDeadAfterMs = 1_800_000L
    )
    proxy.start()
    val port = proxy.localPort

    val sockets = scala.collection.mutable.ArrayBuffer.empty[Socket]
    try
      // 1) Three clients join.
      val socks = (1 to 3).map { _ => sockets += handshake(port); sockets.last }.toList
      // Give the server a moment to register all of them.
      Thread.sleep(200)

      // 2) First client publishes; everyone reads.
      val replies1 = ConcurrentLinkedQueue[String]()
      val latch1 = CountDownLatch(3)
      socks.foreach { s =>
        Thread.ofVirtual().start { () =>
          try
            val fr = readFrame(s)
            replies1.add(fr.textPayload)
            latch1.countDown()
          catch case _: Throwable => latch1.countDown()
        }
      }
      sendText(socks.head, "hello")
      latch1.await(5, TimeUnit.SECONDS) shouldBe true
      replies1.toArray.toList.map(_.toString) shouldBe List(
        "hello [size=3]", "hello [size=3]", "hello [size=3]"
      )

      // 3) Disconnect socks(2); leave two.
      socks(2).close()
      // Wait for the proxy to register the close on its executor.
      Thread.sleep(300)

      // 4) Re-broadcast; remaining two see size=2.
      val replies2 = ConcurrentLinkedQueue[String]()
      val latch2 = CountDownLatch(2)
      socks.take(2).foreach { s =>
        Thread.ofVirtual().start { () =>
          try
            val fr = readFrame(s)
            replies2.add(fr.textPayload)
            latch2.countDown()
          catch case _: Throwable => latch2.countDown()
        }
      }
      sendText(socks.head, "again")
      latch2.await(5, TimeUnit.SECONDS) shouldBe true
      replies2.toArray.toList.map(_.toString) shouldBe List(
        "again [size=2]", "again [size=2]"
      )
    finally
      sockets.foreach { s => try s.close() catch case _: Throwable => () }
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
  }

  private def handshake(port: Int): Socket =
    val s = Socket("127.0.0.1", port)
    s.setSoTimeout(5000)
    val req = (
      "GET /room HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII)
    s.getOutputStream.write(req); s.getOutputStream.flush()
    while readLine(s.getInputStream).nonEmpty do ()
    s

  private def sendText(s: Socket, text: String): Unit =
    val payload = text.getBytes(StandardCharsets.UTF_8)
    val mask    = Array[Byte](1, 2, 3, 4)
    val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
    val frame   = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
    s.getOutputStream.write(frame); s.getOutputStream.flush()

  private def readFrame(s: Socket): WsFraming.Frame =
    val in  = s.getInputStream
    val acc = scala.collection.mutable.ArrayBuffer.empty[Byte]
    val tmp = new Array[Byte](128)
    while true do
      WsFraming.tryParse(acc.toArray, 0, acc.length) match
        case Some(f) => return f
        case None =>
          val n = in.read(tmp)
          if n < 0 then throw RuntimeException("EOF")
          acc ++= tmp.iterator.take(n)
    throw RuntimeException("unreachable")

  private def readLine(in: java.io.InputStream): String =
    val sb = StringBuilder(); var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar); prev = b
    ""
