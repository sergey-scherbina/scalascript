package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Verifies the user-initiated `ws.ping(payload)` + `ws.onPong` flow:
 *    - Server pings the client (an opcode-0x9 frame with payload "hi").
 *    - Client masks-and-echoes that payload back as a Pong.
 *    - Server's `onPong` handler is invoked with the echoed payload
 *      and forwards it through the test channel via `ws.send`. */
class WsPingPongTest extends AnyFunSuite with Matchers:

  test("ws.ping + ws.onPong — payload echoes back to handler") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    Interpreter().run(Parser.parse("""# Test
```scala
onWebSocket("/pp") { ws =>
  ws.onPong { payload => ws.send("pong:" + payload) }
  ws.onMessage { _ => ws.ping("hi") }
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
        "GET /pp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII))
      out.flush()
      while readLine(in).nonEmpty do ()

      // Send a text frame to trigger the server's ping().
      val triggerPayload = "go".getBytes(StandardCharsets.UTF_8)
      val mask           = Array[Byte](1, 2, 3, 4)
      val masked         = triggerPayload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
      out.write(Array[Byte](0x81.toByte, (0x80 | triggerPayload.length).toByte) ++ mask ++ masked)
      out.flush()

      // The server pings us — read the Ping frame, then echo it
      // back as a masked Pong (opcode 0xA).  Standard browser
      // behaviour.
      val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
      val tmp = new Array[Byte](128)
      def readOneFrame(): WsFraming.Frame =
        while true do
          WsFraming.tryParse(buf.toArray, 0, buf.length) match
            case Some(f) =>
              buf.remove(0, f.consumed); return f
            case None =>
              val n = in.read(tmp); if n < 0 then throw RuntimeException("EOF")
              buf ++= tmp.iterator.take(n)
        ???
      val ping = readOneFrame()
      ping.opcode shouldBe WsFraming.Opcode.Ping
      // Echo the ping payload back as a masked Pong.
      val pongPayload = ping.payload
      val pmask       = Array[Byte](9, 8, 7, 6)
      val pmasked     = pongPayload.zipWithIndex.map { (b, i) => (b ^ pmask(i % 4)).toByte }
      out.write(Array[Byte]((0x80 | 0xA).toByte, (0x80 | pongPayload.length).toByte) ++ pmask ++ pmasked)
      out.flush()

      // The server's `onPong` handler relays the payload via
      // ws.send — read it.
      val reply = readOneFrame()
      reply.opcode shouldBe WsFraming.Opcode.Text
      reply.textPayload shouldBe "pong:hi"
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
