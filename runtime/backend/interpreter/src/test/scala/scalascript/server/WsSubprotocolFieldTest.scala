package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Sprint 6 item #23 — `ws.subprotocol` surfaces the protocol the
 *  server selected during the upgrade negotiation.  The full client
 *  *offer* list is still readable via
 *  `ws.request.headers("sec-websocket-protocol")`. */
class WsSubprotocolFieldTest extends AnyFunSuite with Matchers:

  test("ws.subprotocol — set to server's pick after successful negotiation") {
    val captured = ByteArrayOutputStream()
    val out      = PrintStream(captured, true, "UTF-8")
    val interp   = Interpreter(out = out)
    val script = """# Test

```scala
onWebSocket("/proto", List(), List("v2.echo", "echo-protocol")) { ws =>
  println("PROTO=" + ws.subprotocol)
  ws.close()
}
onWebSocket("/free") { ws =>
  println("PROTO=" + ws.subprotocol)
  ws.close()
}
```
"""
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

    try
      upgrade(port, "/proto", Some("foo, echo-protocol, v2.echo"))
      upgrade(port, "/free",  Some("anything-here"))

      val deadline = System.nanoTime() + 5_000_000_000L
      while captured.toString("UTF-8").split('\n').count(_.startsWith("PROTO=")) < 2
            && System.nanoTime() < deadline do
        Thread.sleep(20)

      val seen = captured.toString("UTF-8").linesIterator
        .collect { case s if s.startsWith("PROTO=") => s.stripPrefix("PROTO=").trim }
        .toList
      seen should have size 2
      // Negotiated route picks server's first match.
      seen should contain ("v2.echo")
      // Unrestricted route surfaces empty string.
      seen should contain ("")
    finally
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
  }

  private def upgrade(port: Int, path: String, proto: Option[String]): Unit =
    val sock = Socket("127.0.0.1", port)
    sock.setSoTimeout(5000)
    try
      val protoLine = proto.map(p => s"Sec-WebSocket-Protocol: $p\r\n").getOrElse("")
      val req = (
        s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        protoLine +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII)
      sock.getOutputStream.write(req); sock.getOutputStream.flush()
      // Drain the 101 + headers (up to the blank line) so the handler
      // runs before we tear down the socket.
      val in = sock.getInputStream
      var prev = -1
      var crlfRun = 0
      var done = false
      while !done do
        val b = in.read()
        if b < 0 then done = true
        else
          if prev == '\r' && b == '\n' then
            crlfRun += 1
            if crlfRun >= 2 then done = true
          else if b != '\r' then crlfRun = 0
          prev = b
    finally try sock.close() catch case _: Throwable => ()
