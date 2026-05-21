package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Sprint 6 item #21 — pre-upgrade auth hook.
 *  `onWebSocketAuth(path, authFn)(handler)`: the hook inspects the
 *  Request and returns `Some(userValue)` to accept the upgrade
 *  (carries `userValue` through to `ws.user`) or `None` to refuse
 *  with HTTP 401 (no handler invocation, no WebSocket created). */
class WsAuthHookTest extends AnyFunSuite with Matchers:

  test("onWebSocketAuth — accepts valid bearer, rejects missing / wrong with 401") {
    WsConnection.activeCount.set(0)
    WsConnection.maxActive.set(Int.MaxValue)

    val captured = ByteArrayOutputStream()
    val out      = PrintStream(captured, true, "UTF-8")
    val interp   = Interpreter(out = out)
    val script = """# Test

```scala
def auth(req: Request): Option[String] =
  val h = req.headers.getOrElse("authorization", "")
  if h == "Bearer secret" then Some("user-42") else None

onWebSocketAuth("/admin", auth) { ws =>
  println("USER=" + ws.user.getOrElse(""))
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
    val proxy = WsProxy(
      publicPort   = 0,
      internalAddr = InetSocketAddress("127.0.0.1", internal.getAddress.getPort),
      wsExecutor   = executor,
      log          = System.err,
      wsRoutes     = interp.wsRoutes
    )
    proxy.start()
    val port = proxy.localPort

    try
      // 1) Valid bearer → 101.
      val (status1, _) = attempt(port, "/admin", Some("Bearer secret"))
      status1 should startWith("HTTP/1.1 101")

      // 2) Wrong bearer → 401, no upgrade.
      val (status2, _) = attempt(port, "/admin", Some("Bearer wrong"))
      status2 should startWith("HTTP/1.1 401")

      // 3) Missing Authorization → 401.
      val (status3, _) = attempt(port, "/admin", None)
      status3 should startWith("HTTP/1.1 401")

      // 4) ws.user surfaces to the handler for accepted upgrades.
      val deadline = System.nanoTime() + 5_000_000_000L
      while !captured.toString("UTF-8").contains("USER=user-42") && System.nanoTime() < deadline do
        Thread.sleep(20)
      captured.toString("UTF-8") should include ("USER=user-42")
    finally
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
  }

  private def attempt(port: Int, path: String, auth: Option[String]): (String, Map[String, String]) =
    val sock = Socket("127.0.0.1", port)
    sock.setSoTimeout(5000)
    try
      val authLine = auth.map(v => s"Authorization: $v\r\n").getOrElse("")
      val req = (
        s"GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        authLine +
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
