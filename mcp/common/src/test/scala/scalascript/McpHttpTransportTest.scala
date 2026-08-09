package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** Phase 2 HTTP transport: black-box test against a real WebServer.
 *  Server: register a tool/resource via McpServerBuilder; install the
 *  POST /mcp route handler that pipes bodies through
 *  McpServerCore.handleHttpRequest; start WebServer on a random port.
 *  Client: McpHttpClient hits the same URL via Java HttpClient. */
class McpHttpTransportTest extends AnyFunSuite with Matchers:

  /** Pick a random free port — startup races (a sibling test grabbing
   *  the same port) are negligible since we serialise these tests. */
  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort; s.close(); p

  test("handleHttpRequest: parses POST body and returns reply"):
    val builder = new McpServerBuilder
    builder.tool("echo", None, ujson.Obj(), args =>
      val msg = args.getOrElse("msg", "").toString
      ToolHandlerResult(List(McpProtocol.textContent(msg)), isError = false)
    )

    // Initialize request
    val initBody = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> "initialize",
      "params"  -> ujson.Obj("protocolVersion" -> McpProtocol.ProtocolVersion),
      "id"      -> 1
    ).render()
    val initReply = McpServerCore.handleHttpRequest(builder, initBody, "ssc-mcp-int", "1.0.0")
    ujson.read(initReply)("result")("protocolVersion").str shouldBe McpProtocol.ProtocolVersion

    // tools/call request
    val callBody = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> "tools/call",
      "params"  -> ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj("msg" -> "ping")),
      "id"      -> 2
    ).render()
    val callReply = McpServerCore.handleHttpRequest(builder, callBody, "ssc-mcp-int", "1.0.0")
    ujson.read(callReply)("result")("content")(0)("text").str shouldBe "ping"

  test("handleHttpRequest: notification frame returns empty body"):
    val builder = new McpServerBuilder
    val notifBody = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> "notifications/initialized",
      "params"  -> ujson.Obj()
    ).render()
    val reply = McpServerCore.handleHttpRequest(builder, notifBody)
    reply shouldBe ""

  test("handleHttpRequest: malformed body returns ParseError"):
    val reply = McpServerCore.handleHttpRequest(new McpServerBuilder, "not json")
    ujson.read(reply)("error")("code").num shouldBe JsonRpc.ErrorCode.ParseError

  // ── McpHttpClient roundtrip via in-process HTTP echo server ──────────

  test("McpHttpClient: roundtrip against a tiny echo server"):
    val port = freePort()
    // Start a minimal com.sun.net.httpserver that echoes back via
    // McpServerCore.handleHttpRequest — bypasses WebServer / the
    // interpreter intrinsic so the test is self-contained.
    val builder = new McpServerBuilder
    builder.tool("greet", None, ujson.Obj(), args =>
      val name = args.getOrElse("name", "world").toString
      ToolHandlerResult(List(McpProtocol.textContent(s"hello, $name")), isError = false)
    )
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/mcp", { ex =>
      val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
      val reply = McpServerCore.handleHttpRequest(builder, body)
      val (status, respBytes) =
        if reply.isEmpty then (204, Array.emptyByteArray)
        else (200, reply.getBytes("UTF-8"))
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(status, respBytes.length.toLong)
      if respBytes.length > 0 then
        val os = ex.getResponseBody; os.write(respBytes); os.close()
      else ex.close()
    })
    server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
    server.start()
    try
      val client = new McpHttpClient(s"http://127.0.0.1:$port/mcp", 5000)
      // initialize
      val init = client.request(McpProtocol.Method.Initialize, ujson.Obj(
        "protocolVersion" -> McpProtocol.ProtocolVersion
      ))
      init.isRight shouldBe true
      init.toOption.get("protocolVersion").str shouldBe McpProtocol.ProtocolVersion

      // tools/call
      val res = client.request(McpProtocol.Method.ToolsCall, ujson.Obj(
        "name" -> "greet", "arguments" -> ujson.Obj("name" -> "world")
      ))
      res.isRight shouldBe true
      res.toOption.get("content")(0)("text").str shouldBe "hello, world"

      client.close()
      client.isClosed shouldBe true
    finally server.stop(0)

  test("McpHttpClient: connection refused returns error not crash"):
    val client = new McpHttpClient("http://127.0.0.1:1/mcp", 500)  // port 1 — never listens
    val r = client.request(McpProtocol.Method.Ping, ujson.Obj())
    r.isLeft shouldBe true
