package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Verifies the `onWebSocket(path, origins)` two-arg form:
 *    - request with allowed Origin → 101 upgrade
 *    - request with disallowed Origin → 403 Forbidden, no upgrade
 *    - request with no Origin header → 403 Forbidden
 *  Restricted only the second registered route; the first stays open
 *  so we know the allowlist isn't applied globally. */
class WsOriginTest extends AnyFunSuite with Matchers:

  test("Origin allowlist — 101 on match, 403 on mismatch / missing") {
    WsTestLock.synchronized {
    WsRoutes.clear()
    val script = """# Test
```scala
onWebSocket("/open") { ws => () }
onWebSocket("/guarded", List("https://app.example.com")) { ws => () }
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

    try
      // 1) Unrestricted route → 101.
      attempt(port, "/open", None) should startWith("HTTP/1.1 101")

      // 2) Restricted route + allowed origin → 101.
      attempt(port, "/guarded", Some("https://app.example.com")) should startWith("HTTP/1.1 101")

      // 3) Restricted route + disallowed origin → 403.
      attempt(port, "/guarded", Some("https://evil.example.com")) should startWith("HTTP/1.1 403")

      // 4) Restricted route + no Origin header → 403.
      attempt(port, "/guarded", None) should startWith("HTTP/1.1 403")
    finally
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsRoutes.clear()
    }
  }

  /** Open a fresh socket, send a WS upgrade request, return the status
   *  line of the response so the caller can assert on it. */
  private def attempt(port: Int, path: String, origin: Option[String]): String =
    val sock = Socket("127.0.0.1", port)
    sock.setSoTimeout(5000)
    try
      val key = "dGhlIHNhbXBsZSBub25jZQ=="
      val originLine = origin.map(o => s"Origin: $o\r\n").getOrElse("")
      val req = (
        s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        originLine +
        s"Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
      ).getBytes(StandardCharsets.US_ASCII)
      sock.getOutputStream.write(req); sock.getOutputStream.flush()
      readLine(sock.getInputStream)
    finally try sock.close() catch case _: Throwable => ()

  private def readLine(in: java.io.InputStream): String =
    val sb = StringBuilder(); var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar); prev = b
    ""
