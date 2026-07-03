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

/** End-to-end: spin up the WS proxy in front of a no-op HttpServer,
 *  register an echo `onWebSocket("/echo")` via the interpreter, then
 *  perform the WS handshake from a raw TCP client and exchange one
 *  text frame.  Verifies the whole pipeline: registration, upgrade
 *  detection, RFC-6455 handshake, frame parsing, and the dispatch of
 *  `onMessage` / `ws.send` back through the interpreter executor. */
class WsEchoTest extends AnyFunSuite with Matchers:

  test("echo round-trip — onWebSocket → handshake → send → onMessage → reply") {
    // 1.  Register the echo handler via the interpreter — same code path
    //     the user would hit from a `.ssc` script.
    val script = """# Test

```scala
onWebSocket("/echo") { ws =>
  ws.onMessage { msg =>
    ws.send("echo: " + msg)
  }
}
```
"""
    val module = Parser.parse(script)
    val interp = Interpreter()
    interp.run(module)

    // 2.  Start an internal HttpServer + WsProxy on an ephemeral port.
    //     We're not testing HTTP forwarding here so the backend handler
    //     just 404s.
    val executor = Executors.newSingleThreadExecutor()
    val internal = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    internal.createContext("/", ex => {
      ex.sendResponseHeaders(404, -1); ex.close()
    })
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
    val publicPort = proxy.localPort
    publicPort should be > 0

    // 3.  Raw TCP client — speak WS by hand.
    val sock = Socket("127.0.0.1", publicPort)
    sock.setSoTimeout(5000)
    try
      val out = sock.getOutputStream
      val in  = sock.getInputStream

      val key = "dGhlIHNhbXBsZSBub25jZQ==" // RFC §1.3 example key
      val req =
        "GET /echo HTTP/1.1\r\n" +
        "Host: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        s"Sec-WebSocket-Key: $key\r\n" +
        "Sec-WebSocket-Version: 13\r\n\r\n"
      out.write(req.getBytes(StandardCharsets.US_ASCII))
      out.flush()

      // Read response head (lines until blank).  Read byte-by-byte
      // to avoid the BufferedReader swallowing post-handshake bytes
      // (the WS frame reply) into its 8 KB internal buffer.
      val statusLine = readHttpLine(in)
      statusLine should startWith("HTTP/1.1 101")
      var accept: String = null
      var line  = readHttpLine(in)
      while line != null && line.nonEmpty do
        if line.toLowerCase.startsWith("sec-websocket-accept:") then
          accept = line.split(":", 2)(1).trim
        line = readHttpLine(in)
      accept shouldBe WsFraming.acceptKey(key)

      // 4.  Send a masked client text frame, read the unmasked server reply.
      val payload = "hi".getBytes(StandardCharsets.UTF_8)
      val mask    = Array[Byte](1, 2, 3, 4)
      val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
      val frame   = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
      out.write(frame)
      out.flush()

      // Read the server's response frame — wait up to a few seconds.
      val replyBuf = new Array[Byte](64)
      val read     = readAtLeast(in, replyBuf, 2, 5.seconds)
      val parsed   = WsFraming.tryParse(replyBuf, 0, read).get
      parsed.opcode shouldBe WsFraming.Opcode.Text
      parsed.textPayload shouldBe "echo: hi"

    finally
      try sock.close()       catch case _: Throwable => ()
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
  }

  /** Read one CRLF-terminated line directly from the InputStream so
   *  bytes past the line don't get sucked into a Reader buffer. */
  private def readHttpLine(in: java.io.InputStream): String =
    val sb = StringBuilder()
    var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar)
      prev = b
    ""

  private def readAtLeast(
      in:       java.io.InputStream,
      buf:      Array[Byte],
      minBytes: Int,
      within:   FiniteDuration
  ): Int =
    val deadline = System.nanoTime() + within.toNanos
    var off      = 0
    while off < minBytes && System.nanoTime() < deadline do
      val n = in.read(buf, off, buf.length - off)
      if n < 0 then return off
      off += n
    off
