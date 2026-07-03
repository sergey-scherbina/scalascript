package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.LinkedBlockingQueue

/** Round-trip test for the `std.agent.mcp` bridge (`runtime/std/agent-mcp.ssc`).
 *
 *  The bridge exposes two functions:
 *  - `serveAgentToolsMcp(tools, transport)` — wraps a list of `AgentTool`s as
 *    MCP server tools and serves them over a transport.
 *  - `mcpToolSource(client)` — connects to an MCP server and re-wraps its tools
 *    as `AgentTool`s usable in an agent loop.
 *
 *  Because `serveAgentToolsMcp` terminates with `serveMcp(Transport.Stdio)` which
 *  reads from `System.in`, the full `.ssc` function cannot be invoked in-process
 *  without redirecting the JVM's global stdin.  This test exercises the same bridge
 *  mapping logic directly in Scala, using the exact same JSON marshalling the `.ssc`
 *  functions perform, but wiring the transport via in-process
 *  `LinkedBlockingQueue` pipes — the same pattern as `McpEndToEndTest`.
 *
 *  What is covered:
 *  - Tool name and description survive the round-trip.
 *  - `contentJson` is preserved unchanged through the MCP text-content envelope.
 *  - `isError=false` response propagates correctly.
 *  - `isError=true` response propagates correctly.
 *  - Multiple tools are discoverable via `listTools`.
 *
 *  What is NOT covered:
 *  - The `serveMcp(Transport.Stdio)` I/O loop (covered by `McpEndToEndTest`).
 *  - Interpreter-level invocation of the `.ssc` bridge functions. */
class AgentMcpRoundTripTest extends AnyFunSuite with Matchers:

  // ─── Minimal mirrors of the case classes from std/agent.ssc ──────────────
  // These are not imported from the interpreter; they live here purely as
  // the bridge mapping type contract.

  case class ToolResult(contentJson: String, isError: Boolean = false)
  case class AgentTool(
    name:           String,
    description:    String,
    parametersJson: String,
    handler:        String => ToolResult
  )

  // ─── In-process transport pair (mirrors McpEndToEndTest.pair) ────────────

  private def pair(): (
    () => Option[String], String => Unit,   // server read/write
    () => Option[String], String => Unit    // client read/write
  ) =
    val toServer = new LinkedBlockingQueue[String]()
    val toClient = new LinkedBlockingQueue[String]()
    val eof = "__EOF__"
    def serverRead(): Option[String]      = Option(toServer.take()).flatMap(s => if s == eof then None else Some(s))
    def serverWrite(s: String): Unit      = toClient.put(s)
    def clientRead(): Option[String]      = Option(toClient.take()).flatMap(s => if s == eof then None else Some(s))
    def clientWrite(s: String): Unit      = toServer.put(s)
    (serverRead, serverWrite, clientRead, clientWrite)

  // ─── Bridge helpers ───────────────────────────────────────────────────────

  /** Mirrors the server side of `serveAgentToolsMcp`:
   *  for each `AgentTool`, register a matching MCP tool on `builder`.
   *  The handler converts `Map[String, Any]` args → JSON string (what
   *  `jsonStringify` does in the `.ssc` bridge), invokes the tool handler,
   *  and maps the `ToolResult` back to an MCP `ToolHandlerResult`. */
  private def populateBuilder(builder: McpServerBuilder, tools: List[AgentTool]): Unit =
    tools.foreach { t =>
      builder.tool(t.name, Some(t.description), ujson.Obj("type" -> "object"), args =>
        // jsonStringify(args) — convert Map[String, Any] to JSON string
        val argsJson = ujson.write(McpServerCore.scalaToJson(args))
        val result   = t.handler(argsJson)
        if result.isError then
          ToolHandlerResult(List(McpProtocol.textContent(result.contentJson)), isError = true)
        else
          ToolHandlerResult(List(McpProtocol.textContent(result.contentJson)), isError = false)
      )
    }

  /** Mirrors the client side of `mcpToolSource`:
   *  call `tools/list` on the MCP client, then wrap each descriptor as an
   *  `AgentTool` whose handler calls `tools/call` and extracts the text
   *  content as `contentJson`.
   *
   *  JSON schema field: MCP spec uses `inputSchema`; the core library may
   *  also emit `input_schema` — we prefer `inputSchema` with fallback. */
  private def buildAgentTools(client: McpClientCore, timeoutMs: Long): List[AgentTool] =
    client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
      case Left(e) => throw new RuntimeException(s"listTools failed: ${e.message}")
      case Right(json) =>
        json("tools").arr.toList.map { td =>
          val name  = td("name").str
          val desc  = td.obj.get("description")
                        .filter(_ != ujson.Null).map(_.str).getOrElse("")
          val schema = td.obj.get("inputSchema")
                         .orElse(td.obj.get("input_schema"))
                         .map(ujson.write(_)).getOrElse("{}")
          AgentTool(name, desc, schema, argsJson =>
            // jsonParse(argsJson) — convert JSON string back to Map for callTool
            val argsMap = ujson.read(argsJson).obj.toMap.map { case (k, v) =>
              k -> McpServerCore.jsonToScala(v)
            }
            client.request(McpProtocol.Method.ToolsCall,
              ujson.Obj("name" -> name, "arguments" -> McpServerCore.scalaToJson(argsMap)),
              timeoutMs) match
              case Left(e)    => ToolResult(s""""${e.message}"""", isError = true)
              case Right(res) =>
                // mcpResultText: extract text from the first Content.Text item
                val text    = res("content").arr.headOption
                                .flatMap(_.obj.get("text").map(_.str)).getOrElse("")
                val isError = res("isError").bool
                ToolResult(text, isError)
          )
        }

  // ─── Test helpers ─────────────────────────────────────────────────────────

  private def startServer(builder: McpServerBuilder,
                          sRead: () => Option[String],
                          sWrite: String => Unit,
                          name: String): Thread =
    val t = new Thread((() =>
      McpServerCore.serve(builder, sRead, sWrite, "ssc-mcp-int", "1.0.0")
    ): Runnable, name)
    t.setDaemon(true); t.start(); t

  private def startClientReader(client: McpClientCore,
                                cRead: () => Option[String],
                                name: String): Thread =
    val t = new Thread((() => {
      var alive = true
      while alive do
        cRead() match
          case None       => alive = false
          case Some(line) => client.dispatchResponse(line)
    }): Runnable, name)
    t.setDaemon(true); t.start(); t

  private def doHandshake(client: McpClientCore, timeoutMs: Long = 5000): Unit =
    val init = client.request(McpProtocol.Method.Initialize, ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "test-client", "version" -> "0.1")
    ), timeoutMs)
    init.isRight shouldBe true
    client.notify("notifications/initialized", ujson.Obj())

  private def shutdown(client: McpClientCore,
                       sWrite: String => Unit, cWrite: String => Unit,
                       threads: Thread*): Unit =
    client.close()
    sWrite("__EOF__")
    cWrite("__EOF__")
    threads.foreach(_.join(2000))
    threads.foreach(_.isAlive shouldBe false)

  // ─── Tests ────────────────────────────────────────────────────────────────

  test("round-trip: AgentTool handler result is returned unchanged through MCP"):
    // Server side: one AgentTool that echoes the 'msg' argument.
    val echoTool = AgentTool(
      name           = "echo",
      description    = "echo the msg argument back as contentJson",
      parametersJson = """{"type":"object","properties":{"msg":{"type":"string"}},"required":["msg"]}""",
      handler        = argsJson =>
        val msg = ujson.read(argsJson)("msg").str
        // The contentJson must survive the MCP text envelope unchanged.
        ToolResult(s""""$msg"""", isError = false)
    )

    val (sRead, sWrite, cRead, cWrite) = pair()
    val builder = new McpServerBuilder
    populateBuilder(builder, List(echoTool))

    val serverThread = startServer(builder, sRead, sWrite, "agent-mcp-rt-server")
    val client       = new McpClientCore(cWrite)
    val readerThread = startClientReader(client, cRead, "agent-mcp-rt-reader")

    doHandshake(client)

    // Client side: wrap MCP tools as AgentTools (mirrors mcpToolSource).
    val agentTools = buildAgentTools(client, 5000)
    agentTools should have length 1
    agentTools.head.name        shouldBe "echo"
    agentTools.head.description shouldBe "echo the msg argument back as contentJson"

    // Make a tool call — this is the full round-trip.
    val result = agentTools.head.handler("""{"msg":"hello from agent"}""")
    result.isError    shouldBe false
    result.contentJson shouldBe """"hello from agent""""

    shutdown(client, sWrite, cWrite, serverThread, readerThread)

  test("round-trip: isError=true propagates from server handler to client AgentTool"):
    val failTool = AgentTool(
      name           = "fail",
      description    = "always returns an error ToolResult",
      parametersJson = """{"type":"object"}""",
      handler        = _ => ToolResult(""""something went wrong"""", isError = true)
    )

    val (sRead, sWrite, cRead, cWrite) = pair()
    val builder = new McpServerBuilder
    populateBuilder(builder, List(failTool))

    val serverThread = startServer(builder, sRead, sWrite, "agent-mcp-err-server")
    val client       = new McpClientCore(cWrite)
    val readerThread = startClientReader(client, cRead, "agent-mcp-err-reader")

    doHandshake(client)

    val agentTools = buildAgentTools(client, 5000)
    agentTools should have length 1

    val result = agentTools.head.handler("{}")
    result.isError shouldBe true
    // contentJson carries the error message text.
    result.contentJson should include ("something went wrong")

    shutdown(client, sWrite, cWrite, serverThread, readerThread)

  test("round-trip: multiple AgentTools are all discoverable and callable"):
    val addTool = AgentTool(
      name           = "add",
      description    = "sum two integers a and b",
      parametersJson = """{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}""",
      handler        = argsJson =>
        val js = ujson.read(argsJson)
        val sum = js("a").num.toLong + js("b").num.toLong
        ToolResult(sum.toString, isError = false)
    )
    val greetTool = AgentTool(
      name           = "greet",
      description    = "return a greeting",
      parametersJson = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
      handler        = argsJson =>
        val name = ujson.read(argsJson)("name").str
        ToolResult(s""""Hello, $name!"""", isError = false)
    )

    val (sRead, sWrite, cRead, cWrite) = pair()
    val builder = new McpServerBuilder
    populateBuilder(builder, List(addTool, greetTool))

    val serverThread = startServer(builder, sRead, sWrite, "agent-mcp-multi-server")
    val client       = new McpClientCore(cWrite)
    val readerThread = startClientReader(client, cRead, "agent-mcp-multi-reader")

    doHandshake(client)

    val agentTools = buildAgentTools(client, 5000)
    agentTools should have length 2
    val byName = agentTools.map(t => t.name -> t).toMap

    // call 'add'
    val addResult = byName("add").handler("""{"a":3,"b":4}""")
    addResult.isError    shouldBe false
    addResult.contentJson shouldBe "7"

    // call 'greet'
    val greetResult = byName("greet").handler("""{"name":"World"}""")
    greetResult.isError    shouldBe false
    greetResult.contentJson shouldBe """"Hello, World!""""

    shutdown(client, sWrite, cWrite, serverThread, readerThread)
