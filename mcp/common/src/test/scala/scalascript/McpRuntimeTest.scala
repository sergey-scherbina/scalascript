package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** Unit tests for the transport-agnostic MCP runtime: JSON-RPC framing,
 *  server-side dispatch, client-side request/response routing.  No
 *  subprocesses spawned — Phase 1d's end-to-end test exercises the
 *  spawn client via an in-process pipe. */
class McpRuntimeTest extends AnyFunSuite with Matchers:

  // ── JsonRpc.parse — request / notification / response ────────────────

  test("JsonRpc: parses a well-formed request"):
    val frame = """{"jsonrpc":"2.0","method":"ping","params":{},"id":7}"""
    JsonRpc.parse(frame) match
      case Right(JsonRpc.Message.Request(m, _, id)) =>
        m shouldBe "ping"
        id.num shouldBe 7
      case other => fail(s"expected Request, got $other")

  test("JsonRpc: parses a notification (no id)"):
    val frame = """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}"""
    JsonRpc.parse(frame) match
      case Right(JsonRpc.Message.Notification(m, _)) => m shouldBe "notifications/initialized"
      case other                                     => fail(s"expected Notification, got $other")

  test("JsonRpc: parses a success response"):
    val frame = """{"jsonrpc":"2.0","id":3,"result":{"x":42}}"""
    JsonRpc.parse(frame) match
      case Right(JsonRpc.Message.Response(id, Some(result), None)) =>
        id.num shouldBe 3
        result("x").num shouldBe 42
      case other => fail(s"expected Response, got $other")

  test("JsonRpc: parses an error response"):
    val frame = """{"jsonrpc":"2.0","id":3,"error":{"code":-32601,"message":"method not found"}}"""
    JsonRpc.parse(frame) match
      case Right(JsonRpc.Message.Response(_, None, Some(err))) =>
        err.code shouldBe -32601
        err.message shouldBe "method not found"
      case other => fail(s"expected Response with error, got $other")

  test("JsonRpc: rejects malformed JSON"):
    JsonRpc.parse("not json").isLeft shouldBe true

  test("JsonRpc: encodes request with line terminator"):
    val s = JsonRpc.encodeRequest("ping", ujson.Obj(), 1L)
    s should endWith ("\n")
    ujson.read(s.trim)("method").str shouldBe "ping"
    ujson.read(s.trim)("id").num shouldBe 1

  // ── McpServerCore.dispatch — initialize / ping / tools/list / call ──

  test("dispatch: initialize returns protocolVersion + capabilities"):
    val builder = new McpServerBuilder
    val reply   = McpServerCore.dispatch(builder, McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(1))
    val js      = ujson.read(reply.trim)
    js("id").num shouldBe 1
    js("result")("protocolVersion").str shouldBe McpProtocol.ProtocolVersion
    js("result")("capabilities").obj.keySet should contain ("tools")

  test("dispatch: ping returns empty result"):
    val reply = McpServerCore.dispatch(new McpServerBuilder, McpProtocol.Method.Ping, ujson.Obj(), ujson.Num(2))
    val js    = ujson.read(reply.trim)
    js("id").num shouldBe 2
    js("result").obj shouldBe empty

  test("dispatch: tools/list returns registered tools in declaration order"):
    val builder = new McpServerBuilder
    builder.tool("alpha", Some("first"),  ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    builder.tool("beta",  Some("second"), ujson.Obj(), _ => ToolHandlerResult(Nil, isError = false))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(3))
    val tools = ujson.read(reply.trim)("result")("tools").arr.toList
    tools.length shouldBe 2
    tools(0)("name").str shouldBe "alpha"
    tools(1)("name").str shouldBe "beta"

  test("dispatch: tools/call invokes the matching handler with decoded args"):
    val builder = new McpServerBuilder
    builder.tool("echo", None, ujson.Obj(), args =>
      val msg = args.getOrElse("msg", "").toString
      ToolHandlerResult(List(McpProtocol.textContent(msg)), isError = false)
    )
    val params = ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj("msg" -> "hello"))
    val reply  = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall, params, ujson.Num(4))
    val js     = ujson.read(reply.trim)
    js("result")("isError").bool shouldBe false
    js("result")("content")(0)("text").str shouldBe "hello"

  test("dispatch: tools/call on unknown tool returns MethodNotFound error"):
    val params = ujson.Obj("name" -> "missing", "arguments" -> ujson.Obj())
    val reply  = McpServerCore.dispatch(new McpServerBuilder, McpProtocol.Method.ToolsCall, params, ujson.Num(5))
    val js     = ujson.read(reply.trim)
    js("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound
    js("error")("message").str should include ("unknown tool: missing")

  test("dispatch: handler exception becomes isError=true ToolResult"):
    val builder = new McpServerBuilder
    builder.tool("boom", None, ujson.Obj(), _ => throw new RuntimeException("kaboom"))
    val params = ujson.Obj("name" -> "boom", "arguments" -> ujson.Obj())
    val reply  = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall, params, ujson.Num(6))
    val js     = ujson.read(reply.trim)
    js("result")("isError").bool shouldBe true
    js("result")("content")(0)("text").str shouldBe "kaboom"

  test("dispatch: resources/list + resources/read"):
    val builder = new McpServerBuilder
    builder.resource("file:///readme.md", Some("README"), Some("text/markdown"),
      uri => ResourceHandlerResult(uri, List(McpProtocol.textContent("# Hi"))))
    val listed = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesList, ujson.Obj(), ujson.Num(7)).trim
    )
    listed("result")("resources").arr.head("uri").str shouldBe "file:///readme.md"

    val read = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesRead,
        ujson.Obj("uri" -> "file:///readme.md"), ujson.Num(8)).trim
    )
    read("result")("contents")(0)("text").str shouldBe "# Hi"

  test("dispatch: prompts/list + prompts/get"):
    val builder = new McpServerBuilder
    builder.prompt("greet", Some("greeting prompt"), Nil, _ =>
      PromptHandlerResult(None, List(
        ujson.Obj("role" -> "user", "content" -> McpProtocol.textContent("hi"))
      ))
    )
    val listed = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.PromptsList, ujson.Obj(), ujson.Num(9)).trim
    )
    listed("result")("prompts").arr.head("name").str shouldBe "greet"

    val got = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.PromptsGet,
        ujson.Obj("name" -> "greet", "arguments" -> ujson.Obj()), ujson.Num(10)).trim
    )
    got("result")("messages")(0)("role").str shouldBe "user"

  test("dispatch: unknown method returns MethodNotFound"):
    val reply = McpServerCore.dispatch(new McpServerBuilder, "unknown/method", ujson.Obj(), ujson.Num(11))
    ujson.read(reply.trim)("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound

  // ── McpClientCore — pending-request routing ─────────────────────────

  test("client: request blocks until reader pushes matching response"):
    val sent = scala.collection.mutable.ArrayBuffer.empty[String]
    val client = new McpClientCore(s => sent += s)
    // Spawn a "fake server" thread that reads the id off `sent` and
    // pushes a synthetic response back to the client.
    val t = new Thread((() => {
      Thread.sleep(50)
      val frame = sent.head.trim
      val id    = ujson.read(frame)("id").num.toLong
      client.dispatchResponse(s"""{"jsonrpc":"2.0","id":$id,"result":{"pong":true}}""")
    }): Runnable, "fake-mcp-reader")
    t.setDaemon(true); t.start()
    val r = client.request("ping", ujson.Obj(), 2000)
    r.isRight shouldBe true
    r.toOption.get("pong").bool shouldBe true

  test("client: request times out when no response arrives"):
    val client = new McpClientCore(_ => ())
    val r = client.request("ping", ujson.Obj(), 50)
    r.isLeft shouldBe true
    r.swap.toOption.get.message should include ("timed out")

  test("client: close unblocks pending requests with synthetic error"):
    val client = new McpClientCore(_ => ())
    val t = new Thread((() => { Thread.sleep(20); client.close() }): Runnable, "close-trigger")
    t.setDaemon(true); t.start()
    val r = client.request("ping", ujson.Obj(), 5000)
    r.isLeft shouldBe true
    client.isClosed shouldBe true
