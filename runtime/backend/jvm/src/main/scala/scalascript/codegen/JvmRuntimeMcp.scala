package scalascript.codegen

/** MCP (Model Context Protocol) JVM runtime preamble.
 *
 *  Emitted as Scala source in the generated script when `blocksUseMcp`
 *  detects `mcpServer` / `serveMcp` / `mcpConnect` call sites.
 *  Wraps `io.modelcontextprotocol.sdk:mcp` (the official Java MCP SDK).
 *  The `//> using dep` directive is prepended by `genModule` before the
 *  preamble so scala-cli resolves the artifact automatically.
 *
 *  v1.17.4 finalisation: imports / API shape aligned with the actual SDK
 *  jar layout — package root is `io.modelcontextprotocol.{server,client,
 *  spec,...}`, NOT `io.modelcontextprotocol.sdk.*`, and `McpServer.sync`
 *  accepts a `McpServerTransportProvider`, not a pre-built spec.  The
 *  Transport.Http / Transport.Ws arms wire custom providers through the
 *  consolidated `route()` / `sse()` / `onWebSocket()` helpers emitted by
 *  `serveRuntime` (now also emitted when MCP is used). */
val JvmMcpDep: String = "io.modelcontextprotocol.sdk:mcp:0.10.0"

val JvmRuntimeMcp: String =
  """|
     |// ── MCP server + client ────────────────────────────────────────────────
     |// Wraps io.modelcontextprotocol.sdk:mcp (the official Java MCP SDK).
     |// The API mirrors the JS-side mcpServer / serveMcp / mcpConnect surface.
     |
     |import io.modelcontextprotocol.server.{
     |  McpServer as JMcpServer,
     |  McpServerFeatures,
     |  McpSyncServer,
     |  McpSyncServerExchange
     |}
     |import io.modelcontextprotocol.client.{
     |  McpClient as JMcpClient,
     |  McpSyncClient
     |}
     |import io.modelcontextprotocol.client.transport.{
     |  StdioClientTransport,
     |  HttpClientSseClientTransport,
     |  ServerParameters
     |}
     |import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
     |import io.modelcontextprotocol.spec.{
     |  McpSchema,
     |  McpServerTransport,
     |  McpServerTransportProvider,
     |  McpServerSession
     |}
     |import com.fasterxml.jackson.databind.ObjectMapper
     |import com.fasterxml.jackson.core.`type`.TypeReference
     |import reactor.core.publisher.Mono
     |import scala.jdk.CollectionConverters.*
     |
     |case class McpError(message: String) extends RuntimeException(message)
     |
     |// Shared Jackson mapper for JSON-RPC marshalling on every server transport.
     |private val _mcpMapper: ObjectMapper = new ObjectMapper()
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
     |  // Public accessors for the connect/disconnect hooks — needed by the
     |  // Http/Ws transport providers (lifecycle hooks fire on session
     |  // create / close, not at McpServer.sync(...).build() time).
     |  def _mcpOnConnFn:    Option[() => Unit] = _onConn
     |  def _mcpOnDisconnFn: Option[() => Unit] = _onDisconn
     |
     |  private[this] def _toMcpContent(c: Any): McpSchema.TextContent =
     |    c match
     |      case m: scala.collection.immutable.Map[?, ?] =>
     |        val t = m.asInstanceOf[Map[String, Any]]
     |        t.getOrElse("_type", "") match
     |          case "Text"  => new McpSchema.TextContent(t.getOrElse("text", "").toString)
     |          case _       => new McpSchema.TextContent(c.toString)
     |      case _ => new McpSchema.TextContent(c.toString)
     |
     |  private[this] def _argsToMap(exchange: Any): Map[String, Any] =
     |    exchange match
     |      case m: java.util.Map[?, ?] =>
     |        m.asScala.map { case (k, v) => k.toString -> v.asInstanceOf[Any] }.toMap
     |      case m: scala.collection.immutable.Map[?, ?] =>
     |        m.asInstanceOf[Map[String, Any]]
     |      case _ => Map.empty
     |
     |  /** Convert accumulated tool registrations to SDK SyncToolSpecifications. */
     |  def toolSpecs: List[McpServerFeatures.SyncToolSpecification] =
     |    _tools.toList.map { (name, desc, handler) =>
     |      val tool = new McpSchema.Tool(name, desc, "{}") // empty JSON schema
     |      val fn: java.util.function.BiFunction[McpSyncServerExchange, java.util.Map[String, Object], McpSchema.CallToolResult] =
     |        (_, args) => {
     |          val argsMap = _argsToMap(args)
     |          val result =
     |            try handler(argsMap)
     |            catch case e: McpError =>
     |              Map("_type" -> "ToolResult", "content" -> List(Map("_type" -> "Text", "text" -> e.message)), "isError" -> true)
     |          result match
     |            case r: scala.collection.immutable.Map[?, ?] =>
     |              val rm = r.asInstanceOf[Map[String, Any]]
     |              val contents = rm.getOrElse("content", List.empty) match
     |                case lst: List[?] => lst.map(_toMcpContent).asJava.asInstanceOf[java.util.List[McpSchema.Content]]
     |                case _            => java.util.List.of[McpSchema.Content]()
     |              new McpSchema.CallToolResult(contents, rm.getOrElse("isError", false).asInstanceOf[Boolean])
     |            case _ =>
     |              new McpSchema.CallToolResult(
     |                java.util.List.of[McpSchema.Content](new McpSchema.TextContent(result.toString)),
     |                false
     |              )
     |        }
     |      new McpServerFeatures.SyncToolSpecification(tool, fn)
     |    }
     |
     |  /** Convert accumulated resource registrations to SDK SyncResourceSpecifications. */
     |  def resourceSpecs: List[McpServerFeatures.SyncResourceSpecification] =
     |    _resources.toList.map { (uri, name, mimeType, handler) =>
     |      val displayName = if name.nonEmpty then name else uri
     |      val resource = new McpSchema.Resource(uri, displayName, mimeType, null, null)
     |      val fn: java.util.function.BiFunction[McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult] =
     |        (_, req) => {
     |          val result =
     |            try handler(req.uri())
     |            catch case e: McpError =>
     |              Map("_type" -> "ResourceResult", "uri" -> req.uri(), "contents" -> List(Map("_type" -> "Text", "text" -> e.message)))
     |          val mt = if mimeType.nonEmpty then mimeType else "text/plain"
     |          result match
     |            case r: scala.collection.immutable.Map[?, ?] =>
     |              val rm = r.asInstanceOf[Map[String, Any]]
     |              val ruri = rm.getOrElse("uri", req.uri()).toString
     |              val contents = rm.getOrElse("contents", List.empty) match
     |                case lst: List[?] => lst.map { c =>
     |                  new McpSchema.TextResourceContents(
     |                    ruri, mt,
     |                    c.asInstanceOf[Map[String, Any]].getOrElse("text", "").toString
     |                  ): McpSchema.ResourceContents
     |                }.asJava
     |                case _ => java.util.List.of[McpSchema.ResourceContents]()
     |              new McpSchema.ReadResourceResult(contents)
     |            case _ =>
     |              new McpSchema.ReadResourceResult(java.util.List.of[McpSchema.ResourceContents](
     |                new McpSchema.TextResourceContents(uri, mt, result.toString)
     |              ))
     |        }
     |      new McpServerFeatures.SyncResourceSpecification(resource, fn)
     |    }
     |
     |  /** Convert accumulated prompt registrations to SDK SyncPromptSpecifications. */
     |  def promptSpecs: List[McpServerFeatures.SyncPromptSpecification] =
     |    _prompts.toList.map { (name, desc, handler) =>
     |      val descOrNull = if desc.nonEmpty then desc else null
     |      val prompt = new McpSchema.Prompt(name, descOrNull, java.util.List.of[McpSchema.PromptArgument]())
     |      val fn: java.util.function.BiFunction[McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult] =
     |        (_, req) => {
     |          val reqArgs: Map[String, Any] = req.arguments() match
     |            case m: java.util.Map[?, ?] =>
     |              m.asScala.map { case (k, v) => k.toString -> (v: Any) }.toMap
     |            case _ => Map.empty
     |          val result =
     |            try handler(reqArgs)
     |            catch case e: McpError =>
     |              Map("_type" -> "PromptResult", "messages" -> List.empty[Any])
     |          val messages: java.util.List[McpSchema.PromptMessage] = result match
     |            case r: scala.collection.immutable.Map[?, ?] =>
     |              val rm = r.asInstanceOf[Map[String, Any]]
     |              rm.getOrElse("messages", List.empty) match
     |                case lst: List[?] => lst.map { m =>
     |                  val msg = m.asInstanceOf[Map[String, Any]]
     |                  val roleStr = msg.getOrElse("role", Map("_type" -> "User")) match
     |                    case roleMap: scala.collection.immutable.Map[?, ?] =>
     |                      roleMap.asInstanceOf[Map[String, Any]].getOrElse("_type", "User").toString
     |                    case s: String => s
     |                    case _ => "User"
     |                  val role = roleStr.toLowerCase match
     |                    case "assistant" => McpSchema.Role.ASSISTANT
     |                    case _           => McpSchema.Role.USER
     |                  val content = msg.getOrElse("content", Map("_type" -> "Text", "text" -> "")) match
     |                    case c: scala.collection.immutable.Map[?, ?] =>
     |                      new McpSchema.TextContent(
     |                        c.asInstanceOf[Map[String, Any]].getOrElse("text", "").toString
     |                      )
     |                    case other =>
     |                      new McpSchema.TextContent(other.toString)
     |                  new McpSchema.PromptMessage(role, content)
     |                }.asJava
     |                case _ => java.util.List.of[McpSchema.PromptMessage]()
     |            case _ => java.util.List.of[McpSchema.PromptMessage]()
     |          new McpSchema.GetPromptResult(descOrNull, messages)
     |        }
     |      new McpServerFeatures.SyncPromptSpecification(prompt, fn)
     |    }
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
     |/** Apply the accumulated server spec (info + tools + resources + prompts)
     |  * to a McpServer.sync(provider) SyncSpecification builder. */
     |private def _applyMcpSpec(builder: JMcpServer.SyncSpecification): McpSyncServer =
     |  var b = builder.serverInfo(new McpSchema.Implementation("scalascript-mcp", "1.0.0"))
     |  val ts = _mcpBuilder.toolSpecs
     |  if ts.nonEmpty then b = b.tools(ts.asJava)
     |  val rs = _mcpBuilder.resourceSpecs
     |  if rs.nonEmpty then b = b.resources(rs.asJava)
     |  val ps = _mcpBuilder.promptSpecs
     |  if ps.nonEmpty then b = b.prompts(ps.asJava)
     |  b.build()
     |
     |def serveMcp(transport: Any): Unit =
     |  _mcpTransportType(transport) match
     |    case "Stdio" =>
     |      _applyMcpSpec(JMcpServer.sync(new StdioServerTransportProvider()))
     |      _mcpBuilder._mcpOnConnFn.foreach(_())
     |      // Stdio transport drives the read loop on a daemon thread; block
     |      // the calling thread so the JVM doesn't exit before any client
     |      // request arrives.  Interrupted by SIGINT / process termination.
     |      try
     |        while !Thread.currentThread.isInterrupted do Thread.sleep(60000L)
     |      catch case _: InterruptedException => ()
     |      _mcpBuilder._mcpOnDisconnFn.foreach(_())
     |    case "Http"  =>
     |      val port = _mcpTransportField(transport, "port").toString.toIntOption.getOrElse(3000)
     |      val path = _mcpTransportField(transport, "path").toString match
     |        case "" => "/mcp"
     |        case p  => p
     |      val provider = new _HttpSseMcpProvider(
     |        path, _mcpBuilder._mcpOnConnFn, _mcpBuilder._mcpOnDisconnFn
     |      )
     |      _applyMcpSpec(JMcpServer.sync(provider))
     |      serve(port)
     |    case "Ws" =>
     |      val port = _mcpTransportField(transport, "port").toString.toIntOption.getOrElse(3000)
     |      val path = _mcpTransportField(transport, "path").toString match
     |        case "" => "/mcp"
     |        case p  => p
     |      val provider = new _WsMcpProvider(
     |        path, _mcpBuilder._mcpOnConnFn, _mcpBuilder._mcpOnDisconnFn
     |      )
     |      _applyMcpSpec(JMcpServer.sync(provider))
     |      serve(port)
     |    case t => throw McpError(s"Unknown transport: $t")
     |
     |// Per-session SSE transport — writes outbound JSON-RPC as SSE `message`
     |// events into the stream held open by the GET route handler.
     |class _HttpSseSessionTransport(stream: _SseStream, mapper: ObjectMapper) extends McpServerTransport:
     |  override def sendMessage(msg: McpSchema.JSONRPCMessage): Mono[Void] =
     |    Mono.fromRunnable[Void] { () =>
     |      try stream.send("message", mapper.writeValueAsString(msg))
     |      catch case e: Throwable => System.err.println(s"MCP SSE send: ${e.getMessage}")
     |    }
     |  override def unmarshalFrom[T](data: Object, typeRef: TypeReference[T]): T =
     |    mapper.convertValue(data, typeRef)
     |  override def closeGracefully(): Mono[Void] = Mono.empty()
     |
     |class _HttpSseMcpProvider(
     |    basePath:  String,
     |    onConn:    Option[() => Unit],
     |    onDisconn: Option[() => Unit]
     |) extends McpServerTransportProvider:
     |  private val sessions = new java.util.concurrent.ConcurrentHashMap[String,
     |      (McpServerSession, _HttpSseSessionTransport)]()
     |  private var factory: McpServerSession.Factory = null
     |
     |  override def setSessionFactory(f: McpServerSession.Factory): Unit =
     |    factory = f
     |    // SSE upgrade — GET <basePath>: opens a stream, allocates a session,
     |    // sends the `endpoint` event with the per-session POST URL, then
     |    // blocks the route-handler virtual thread until the client closes.
     |    route("GET", basePath) { req =>
     |      val sid = java.util.UUID.randomUUID().toString
     |      sse(req) { stream =>
     |        val transport = new _HttpSseSessionTransport(stream, _mcpMapper)
     |        val session   = factory.create(transport)
     |        sessions.put(sid, (session, transport))
     |        try
     |          stream.send("endpoint", s"$basePath?sessionId=$sid")
     |          onConn.foreach(h => try h() catch case _: Throwable => ())
     |          while sessions.containsKey(sid) && !Thread.currentThread.isInterrupted do
     |            try Thread.sleep(1000L)
     |            catch case _: InterruptedException => sessions.remove(sid)
     |        finally
     |          sessions.remove(sid)
     |          onDisconn.foreach(h => try h() catch case _: Throwable => ())
     |      }
     |    }
     |    // Inbound JSON-RPC — POST <basePath>?sessionId=…: routes the message
     |    // to the matching session.handle(...) and returns 202 Accepted.
     |    route("POST", basePath) { req =>
     |      val sid = req.query.getOrElse("sessionId", "")
     |      val entry = sessions.get(sid)
     |      if entry == null then
     |        Response(400, Map("Content-Type" -> "text/plain"), s"Unknown session: $sid")
     |      else
     |        try
     |          val msg = McpSchema.deserializeJsonRpcMessage(_mcpMapper, req.body)
     |          entry._1.handle(msg).subscribe()
     |          Response(202, Map("Content-Type" -> "text/plain"), "")
     |        catch case e: Throwable =>
     |          Response(500, Map("Content-Type" -> "text/plain"), s"JSON-RPC error: ${e.getMessage}")
     |    }
     |
     |  override def notifyClients(method: String, params: Object): Mono[Void] =
     |    Mono.fromRunnable[Void] { () =>
     |      sessions.values.forEach { tup =>
     |        val notification = new McpSchema.JSONRPCNotification(
     |          McpSchema.JSONRPC_VERSION, method, params
     |        )
     |        tup._2.sendMessage(notification).subscribe()
     |      }
     |    }
     |  override def closeGracefully(): Mono[Void] = Mono.fromRunnable[Void] { () =>
     |    sessions.clear()
     |  }
     |
     |// Per-connection WebSocket transport — writes outbound JSON-RPC as text frames.
     |class _WsSessionTransport(ws: WebSocket, mapper: ObjectMapper) extends McpServerTransport:
     |  override def sendMessage(msg: McpSchema.JSONRPCMessage): Mono[Void] =
     |    Mono.fromRunnable[Void] { () =>
     |      try ws.send(mapper.writeValueAsString(msg))
     |      catch case e: Throwable => System.err.println(s"MCP WS send: ${e.getMessage}")
     |    }
     |  override def unmarshalFrom[T](data: Object, typeRef: TypeReference[T]): T =
     |    mapper.convertValue(data, typeRef)
     |  override def closeGracefully(): Mono[Void] =
     |    Mono.fromRunnable[Void] { () =>
     |      try ws.close(1000, "") catch case _: Throwable => ()
     |    }
     |
     |class _WsMcpProvider(
     |    basePath:  String,
     |    onConn:    Option[() => Unit],
     |    onDisconn: Option[() => Unit]
     |) extends McpServerTransportProvider:
     |  private val sessions = new java.util.concurrent.ConcurrentLinkedQueue[
     |      (McpServerSession, _WsSessionTransport)]()
     |  private var factory: McpServerSession.Factory = null
     |
     |  override def setSessionFactory(f: McpServerSession.Factory): Unit =
     |    factory = f
     |    onWebSocket(basePath) { ws =>
     |      val transport = new _WsSessionTransport(ws, _mcpMapper)
     |      val session   = factory.create(transport)
     |      val entry     = (session, transport)
     |      sessions.add(entry)
     |      onConn.foreach(h => try h() catch case _: Throwable => ())
     |      ws.onMessage { msg =>
     |        try
     |          val rpcMsg = McpSchema.deserializeJsonRpcMessage(_mcpMapper, msg)
     |          session.handle(rpcMsg).subscribe()
     |        catch case e: Throwable =>
     |          System.err.println(s"MCP WS recv: ${e.getMessage}")
     |      }
     |      ws.onClose { () =>
     |        sessions.remove(entry)
     |        onDisconn.foreach(h => try h() catch case _: Throwable => ())
     |      }
     |    }
     |
     |  override def notifyClients(method: String, params: Object): Mono[Void] =
     |    Mono.fromRunnable[Void] { () =>
     |      sessions.iterator().forEachRemaining { tup =>
     |        val notification = new McpSchema.JSONRPCNotification(
     |          McpSchema.JSONRPC_VERSION, method, params
     |        )
     |        tup._2.sendMessage(notification).subscribe()
     |      }
     |    }
     |  override def closeGracefully(): Mono[Void] = Mono.fromRunnable[Void] { () =>
     |    sessions.clear()
     |  }
     |
     |// ── MCP client ───────────────────────────────────────────────────────────
     |
     |class McpClientImpl(private val jClient: McpSyncClient):
     |  private var _closed = false
     |  def isClosed: Boolean = _closed
     |  def listTools(): List[Any] =
     |    jClient.listTools().tools().asScala.map(t =>
     |      Map("_type" -> "ToolDescriptor", "name" -> t.name(), "description" -> t.description(), "schema" -> Map.empty[String, Any])
     |    ).toList
     |  def listResources(): List[Any] =
     |    jClient.listResources().resources().asScala.map(r =>
     |      Map("_type" -> "ResourceDescriptor", "uri" -> r.uri(), "name" -> r.name(), "mimeType" -> r.mimeType())
     |    ).toList
     |  def listPrompts(): List[Any] =
     |    jClient.listPrompts().prompts().asScala.map(p =>
     |      Map("_type" -> "PromptDescriptor", "name" -> p.name(), "description" -> p.description(), "args" -> List.empty[Any])
     |    ).toList
     |  def callTool(name: String, args: Map[String, Any]): Any =
     |    val req = new McpSchema.CallToolRequest(name, args.asJava.asInstanceOf[java.util.Map[String, Object]])
     |    val result = jClient.callTool(req)
     |    val contents = result.content().asScala.map {
     |      case tc: McpSchema.TextContent =>
     |        Map("_type" -> "Text", "text" -> tc.text())
     |      case other => Map("_type" -> "Text", "text" -> other.toString)
     |    }.toList
     |    Map("_type" -> "ToolResult", "content" -> contents, "isError" -> result.isError)
     |  def readResource(uri: String): Any =
     |    val result = jClient.readResource(new McpSchema.ReadResourceRequest(uri))
     |    val contents = result.contents().asScala.map(c => Map("_type" -> "Text", "text" -> c.toString)).toList
     |    Map("_type" -> "ResourceResult", "uri" -> uri, "contents" -> contents)
     |  def getPrompt(name: String, args: Map[String, Any]): Any =
     |    val req = new McpSchema.GetPromptRequest(name, args.asJava.asInstanceOf[java.util.Map[String, Object]])
     |    val result = jClient.getPrompt(req)
     |    val messages = result.messages().asScala.map { m =>
     |      val role = m.role() match
     |        case McpSchema.Role.USER      => Map("_type" -> "User")
     |        case McpSchema.Role.ASSISTANT => Map("_type" -> "Assistant")
     |        case _                        => Map("_type" -> "System")
     |      val content = m.content() match
     |        case tc: McpSchema.TextContent =>
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
     |      val path = _mcpTransportField(transport, "path").toString match
     |        case "" => "/mcp"
     |        case p  => p
     |      HttpClientSseClientTransport.builder(s"http://localhost:$port").sseEndpoint(path).build()
     |    case t => throw McpError(s"Unsupported client transport: $t")
     |  val jClient = JMcpClient.sync(trans)
     |    .clientInfo(new McpSchema.Implementation("scalascript-mcp-client", "1.0.0"))
     |    .build()
     |  jClient.initialize()
     |  new McpClientImpl(jClient)
     |""".stripMargin
