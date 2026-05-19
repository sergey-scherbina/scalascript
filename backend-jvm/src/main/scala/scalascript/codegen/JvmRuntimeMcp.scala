package scalascript.codegen

/** MCP (Model Context Protocol) JVM runtime preamble.
 *
 *  Emitted as Scala source in the generated script when `blocksUseMcp`
 *  detects `mcpServer` / `serveMcp` / `mcpConnect` call sites.
 *  Wraps `io.modelcontextprotocol.sdk:mcp` (the official Java SDK).
 *  The `//> using dep` directive is prepended by `genModule` before the
 *  preamble so scala-cli resolves the artifact automatically. */
val JvmMcpDep: String = "io.modelcontextprotocol.sdk:mcp:0.10.0"

val JvmRuntimeMcp: String =
  """|
     |// ── MCP server + client ────────────────────────────────────────────────
     |// Wraps io.modelcontextprotocol.sdk:mcp (the official Java MCP SDK).
     |// The API mirrors the JS-side mcpServer / serveMcp / mcpConnect surface.
     |
     |import io.modelcontextprotocol.sdk.McpServer as JMcpServer
     |import io.modelcontextprotocol.sdk.McpClient as JMcpClient
     |import io.modelcontextprotocol.sdk.server.McpSyncServerSpec
     |import io.modelcontextprotocol.sdk.client.McpSyncClientSpec
     |import io.modelcontextprotocol.sdk.server.transport.StdioServerTransport
     |import io.modelcontextprotocol.sdk.client.transport.{StdioClientTransport, HttpSseClientTransport}
     |import io.modelcontextprotocol.sdk.messages.*
     |import io.modelcontextprotocol.sdk.model.*
     |import scala.jdk.CollectionConverters.*
     |
     |case class McpError(message: String) extends RuntimeException(message)
     |
     |// Builder object passed to `mcpServer { srv => ... }`.
     |// Accumulates tool / resource / prompt registrations before serveMcp() fires.
     |class McpServerBuilder:
     |  private[this] val _tools     = scala.collection.mutable.ListBuffer[(String, String, Map[String, Any] => Any)]()
     |  private[this] val _resources = scala.collection.mutable.ListBuffer[(String, String, String, String => Any)]()
     |  private[this] val _prompts   = scala.collection.mutable.ListBuffer[(String, String, Map[String, Any] => Any)]()
     |  private[this] var _onConn:    Option[() => Unit] = None
     |  private[this] var _onDisconn: Option[() => Unit] = None
     |
     |  def tool(name: String)(handler: Map[String, Any] => Any): Unit =
     |    _tools += ((name, "", handler))
     |  def tool(name: String, description: String)(handler: Map[String, Any] => Any): Unit =
     |    _tools += ((name, description, handler))
     |  def resource(uri: String)(handler: String => Any): Unit =
     |    _resources += ((uri, "", "", handler))
     |  def resource(uri: String, name: String)(handler: String => Any): Unit =
     |    _resources += ((uri, name, "", handler))
     |  def resource(uri: String, name: String, mimeType: String)(handler: String => Any): Unit =
     |    _resources += ((uri, name, mimeType, handler))
     |  def prompt(name: String)(handler: Map[String, Any] => Any): Unit =
     |    _prompts += ((name, "", handler))
     |  def prompt(name: String, description: String)(handler: Map[String, Any] => Any): Unit =
     |    _prompts += ((name, description, handler))
     |  def onConnected(handler: () => Unit): Unit    = _onConn    = Some(handler)
     |  def onDisconnected(handler: () => Unit): Unit = _onDisconn = Some(handler)
     |
     |  private[this] def _toMcpContent(c: Any): io.modelcontextprotocol.sdk.messages.TextContent =
     |    c match
     |      case m: scala.collection.immutable.Map[?, ?] =>
     |        val t = m.asInstanceOf[Map[String, Any]]
     |        t.getOrElse("_type", "") match
     |          case "Text"  => new io.modelcontextprotocol.sdk.messages.TextContent(t.getOrElse("text", "").toString)
     |          case _       => new io.modelcontextprotocol.sdk.messages.TextContent(c.toString)
     |      case _ => new io.modelcontextprotocol.sdk.messages.TextContent(c.toString)
     |
     |  private[this] def _argsToMap(exchange: Any): Map[String, Any] =
     |    exchange match
     |      case m: java.util.Map[?, ?] =>
     |        m.asScala.map { case (k, v) => k.toString -> v.asInstanceOf[Any] }.toMap
     |      case m: scala.collection.immutable.Map[?, ?] =>
     |        m.asInstanceOf[Map[String, Any]]
     |      case _ => Map.empty
     |
     |  def buildSpec(): McpSyncServerSpec =
     |    val specBuilder = McpSyncServerSpec.builder()
     |      .serverInfo(new Implementation("scalascript-mcp", "1.0.0"))
     |    _onConn.foreach(h => specBuilder.onClientConnect(_ => h()))
     |    _onDisconn.foreach(h => specBuilder.onClientDisconnect(_ => h()))
     |    // Tools
     |    val toolSpecs = _tools.map { (name, desc, handler) =>
     |      val tool = new Tool(name, desc, "{}") // empty JSON schema
     |      new McpSyncServerSpec.SyncToolSpecification(tool, (_, args) => {
     |        val argsMap = _argsToMap(args.arguments())
     |        val result =
     |          try handler(argsMap)
     |          catch case e: McpError => Map("_type" -> "ToolResult", "content" -> List(Map("_type" -> "Text", "text" -> e.message)), "isError" -> true)
     |        result match
     |          case r: scala.collection.immutable.Map[?, ?] =>
     |            val rm = r.asInstanceOf[Map[String, Any]]
     |            val contents = rm.getOrElse("content", List.empty) match
     |              case lst: List[?] => lst.map(_toMcpContent).asJava
     |              case _            => java.util.List.of()
     |            new CallToolResult(contents, rm.getOrElse("isError", false).asInstanceOf[Boolean])
     |          case _ =>
     |            new CallToolResult(java.util.List.of(new io.modelcontextprotocol.sdk.messages.TextContent(result.toString)), false)
     |      })
     |    }
     |    specBuilder.tools(toolSpecs.toList.asJava)
     |    // Resources
     |    val resourceSpecs = _resources.map { (uri, name, mimeType, handler) =>
     |      val resource = new Resource(uri, name.nonEmpty match { case true => name; case _ => uri }, mimeType, null, null)
     |      new McpSyncServerSpec.SyncResourceSpecification(resource, (_, req) => {
     |        val result =
     |          try handler(req.uri())
     |          catch case e: McpError => Map("_type" -> "ResourceResult", "uri" -> req.uri(), "contents" -> List(Map("_type" -> "Text", "text" -> e.message)))
     |        result match
     |          case r: scala.collection.immutable.Map[?, ?] =>
     |            val rm = r.asInstanceOf[Map[String, Any]]
     |            val contents = rm.getOrElse("contents", List.empty) match
     |              case lst: List[?] => lst.map(c =>
     |                new io.modelcontextprotocol.sdk.messages.TextResourceContents(
     |                  rm.getOrElse("uri", req.uri()).toString, mimeType.nonEmpty match { case true => mimeType; case _ => "text/plain" },
     |                  c.asInstanceOf[Map[String, Any]].getOrElse("text", "").toString
     |                )
     |              ).asJava
     |              case _ => java.util.List.of()
     |            new ReadResourceResult(contents)
     |          case _ =>
     |            new ReadResourceResult(java.util.List.of(
     |              new io.modelcontextprotocol.sdk.messages.TextResourceContents(uri, "text/plain", result.toString)
     |            ))
     |      })
     |    }
     |    specBuilder.resources(resourceSpecs.toList.asJava)
     |    specBuilder.build()
     |
     |// Global server builder state
     |private val _mcpBuilder = new McpServerBuilder()
     |
     |def mcpServer(setup: McpServerBuilder => Unit): Unit = setup(_mcpBuilder)
     |
     |// Enum-like Transport value check helpers
     |private def _mcpTransportType(t: Any): String = t match
     |  case m: scala.collection.immutable.Map[?, ?] => m.asInstanceOf[Map[String, Any]].getOrElse("_type", "").toString
     |  case _ => ""
     |private def _mcpTransportField(t: Any, field: String): Any = t match
     |  case m: scala.collection.immutable.Map[?, ?] => m.asInstanceOf[Map[String, Any]].getOrElse(field, "")
     |  case _ => ""
     |
     |def serveMcp(transport: Any): Unit =
     |  val spec  = _mcpBuilder.buildSpec()
     |  val jSrv  = JMcpServer.sync(spec)
     |  val trans = _mcpTransportType(transport) match
     |    case "Stdio" => new StdioServerTransport()
     |    case "Http"  =>
     |      throw McpError("Transport.Http not yet supported on JVM (use Transport.Stdio)")
     |    case "Ws" =>
     |      throw McpError("Transport.Ws not yet supported on JVM (use Transport.Stdio)")
     |    case t => throw McpError(s"Unknown transport: $t")
     |  jSrv.connect(trans)
     |
     |// ── MCP client ───────────────────────────────────────────────────────────
     |
     |class McpClientImpl(private val jClient: io.modelcontextprotocol.sdk.McpSyncClient):
     |  private var _closed = false
     |  def isClosed: Boolean = _closed
     |  def listTools(): List[Any] =
     |    jClient.listTools(null).tools().asScala.map(t =>
     |      Map("_type" -> "ToolDescriptor", "name" -> t.name(), "description" -> t.description(), "schema" -> Map.empty[String, Any])
     |    ).toList
     |  def listResources(): List[Any] =
     |    jClient.listResources(null).resources().asScala.map(r =>
     |      Map("_type" -> "ResourceDescriptor", "uri" -> r.uri(), "name" -> r.name(), "mimeType" -> r.mimeType())
     |    ).toList
     |  def listPrompts(): List[Any] =
     |    jClient.listPrompts(null).prompts().asScala.map(p =>
     |      Map("_type" -> "PromptDescriptor", "name" -> p.name(), "description" -> p.description(), "args" -> List.empty[Any])
     |    ).toList
     |  def callTool(name: String, args: Map[String, Any]): Any =
     |    val result = jClient.callTool(new CallToolRequest(name, args.asJava.asInstanceOf[java.util.Map[String, Object]]))
     |    val contents = result.content().asScala.map {
     |      case tc: io.modelcontextprotocol.sdk.messages.TextContent =>
     |        Map("_type" -> "Text", "text" -> tc.text())
     |      case other => Map("_type" -> "Text", "text" -> other.toString)
     |    }.toList
     |    Map("_type" -> "ToolResult", "content" -> contents, "isError" -> result.isError)
     |  def readResource(uri: String): Any =
     |    val result = jClient.readResource(new ReadResourceRequest(uri))
     |    val contents = result.contents().asScala.map(c => Map("_type" -> "Text", "text" -> c.toString)).toList
     |    Map("_type" -> "ResourceResult", "uri" -> uri, "contents" -> contents)
     |  def getPrompt(name: String, args: Map[String, Any]): Any =
     |    val result = jClient.getPrompt(new GetPromptRequest(name, args.asJava.asInstanceOf[java.util.Map[String, String]]))
     |    val messages = result.messages().asScala.map { m =>
     |      val role = m.role().toString.toLowerCase match
     |        case "user"      => Map("_type" -> "User")
     |        case "assistant" => Map("_type" -> "Assistant")
     |        case _           => Map("_type" -> "System")
     |      val content = m.content() match
     |        case tc: io.modelcontextprotocol.sdk.messages.TextContent =>
     |          Map("_type" -> "Text", "text" -> tc.text())
     |        case other => Map("_type" -> "Text", "text" -> other.toString)
     |      Map("_type" -> "Message", "role" -> role, "content" -> content)
     |    }.toList
     |    Map("_type" -> "PromptResult", "messages" -> messages)
     |  def close(): Unit =
     |    if !_closed then { _closed = true; jClient.closeGracefully() }
     |
     |def mcpConnect(transport: Any, timeoutMs: Long = 10000L): McpClientImpl =
     |  val trans = _mcpTransportType(transport) match
     |    case "Spawn" =>
     |      val cmd  = _mcpTransportField(transport, "cmd").toString
     |      val args = _mcpTransportField(transport, "args") match
     |        case lst: List[?] => lst.map(_.toString)
     |        case _ => List.empty[String]
     |      val params = ServerParameters.builder(cmd).args(args.asJava).build()
     |      new StdioClientTransport(params)
     |    case "Http" =>
     |      val port = _mcpTransportField(transport, "port").toString.toIntOption.getOrElse(3000)
     |      val path = _mcpTransportField(transport, "path").toString
     |      new HttpSseClientTransport(s"http://localhost:$port$path")
     |    case t => throw McpError(s"Unsupported client transport: $t")
     |  val spec = McpSyncClientSpec.builder()
     |    .clientInfo(new Implementation("scalascript-mcp-client", "1.0.0"))
     |    .transport(trans)
     |    .build()
     |  val jClient = JMcpClient.sync(spec)
     |  jClient.initialize()
     |  new McpClientImpl(jClient)
     |""".stripMargin
