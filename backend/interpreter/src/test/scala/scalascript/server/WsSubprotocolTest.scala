package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Verifies `Sec-WebSocket-Protocol` negotiation (RFC 6455 §1.9):
 *    - Route registered with two server-side protocols.
 *    - Client offers a list overlapping with one of them → 101 echoes
 *      the chosen protocol back in `Sec-WebSocket-Protocol:`.
 *    - Client offers no overlap → 400 Bad Request.
 *    - Client doesn't send the header at all → 400 (registered
 *      protocols make negotiation required).
 *    - Route without server-side protocols ignores the header and
 *      doesn't echo anything (default behaviour). */
class WsSubprotocolTest extends AnyFunSuite with Matchers:

  test("subprotocol negotiation — picks first server proto in client's list") {
    val interp = Interpreter()
    interp.run(Parser.parse("""# Test
```scala
onWebSocket("/free") { ws => () }
onWebSocket("/proto", List(), List("v2.echo", "echo-protocol")) { ws => () }
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
      log          = devNull,
      wsRoutes     = interp.wsRoutes
    )
    proxy.start()
    val port = proxy.localPort

    try
      // 1) Free route ignores the protocol header entirely.
      val (status1, hdrs1) = attempt(port, "/free", Some("foo,bar"))
      status1 should startWith("HTTP/1.1 101")
      hdrs1.get("sec-websocket-protocol") shouldBe None

      // 2) Restricted route + matching protocol → 101 with the
      //    server's *first* match echoed back.  Client offers
      //    `foo, echo-protocol, v2.echo` — server has `v2.echo,
      //    echo-protocol`; first server match is `v2.echo`.
      val (status2, hdrs2) = attempt(port, "/proto", Some("foo, echo-protocol, v2.echo"))
      status2 should startWith("HTTP/1.1 101")
      hdrs2.get("sec-websocket-protocol") shouldBe Some("v2.echo")

      // 3) Restricted route + non-overlapping client offer → 400.
      val (status3, _) = attempt(port, "/proto", Some("unknown-thing"))
      status3 should startWith("HTTP/1.1 400")

      // 4) Restricted route + no header at all → 400.
      val (status4, _) = attempt(port, "/proto", None)
      status4 should startWith("HTTP/1.1 400")
    finally
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
  }

  /** Open a fresh socket, send a WS upgrade with optional
   *  Sec-WebSocket-Protocol, return (status line, lower-cased
   *  response headers). */
  private def attempt(port: Int, path: String, proto: Option[String]): (String, Map[String, String]) =
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
      val in     = sock.getInputStream
      val status = readLine(in)
      val hdrs   = scala.collection.mutable.Map.empty[String, String]
      var line   = readLine(in)
      while line != null && line.nonEmpty do
        val i = line.indexOf(':')
        if i >= 0 then hdrs(line.substring(0, i).trim.toLowerCase) = line.substring(i + 1).trim
        line = readLine(in)
      (status, hdrs.toMap)
    finally try sock.close() catch case _: Throwable => ()

  private def readLine(in: java.io.InputStream): String =
    val sb = StringBuilder(); var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar); prev = b
    ""
