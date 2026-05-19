package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.mcp.*
import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import scala.collection.mutable

/** v1.17 MCP own-implementation for the interpreter backend — covers
 *  `Transport.Stdio` server + `Transport.Spawn` client.  HTTP+SSE and
 *  WebSocket transports raise "not yet supported" (Phase 2/3 follow-ups).
 *
 *  User-facing API (from `std/mcp/server.ssc` and `std/mcp/client.ssc`):
 *
 *  ```
 *  mcpServer { srv =>
 *    srv.tool("echo") { args => Tool.text(requireString(args, "msg")) }
 *  }
 *  serveMcp(Transport.Stdio)
 *
 *  val client = mcpConnect(Transport.Spawn("node", List("server.js")))
 *  val result = client.callTool("echo", Map("msg" -> "hi"))
 *  client.close()
 *  ```
 *
 *  The handler closure is invoked back into the interpreter via
 *  `ctx.invokeCallback(fn, args)` — the result Value flows back through
 *  `valueToHandlerResult` which decodes the case-class shape into
 *  `ToolHandlerResult` / `ResourceHandlerResult` / `PromptHandlerResult`. */
val McpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // ─── mcpServer { srv => ... } ───────────────────────────────────────

  QualifiedName("mcpServer") -> NativeImpl((ctx, args) =>
    args match
      case List(setup) =>
        val builder = new McpServerBuilder
        Mcp.builderTL.set(builder)
        val srvInstance = Mcp.makeServerInstance(builder, ctx)
        ctx.invokeCallback(setup, List(srvInstance))
        ()
      case _ => throw InterpretError("mcpServer { srv => ... }")
  ),

  // ─── serveMcp(transport) ────────────────────────────────────────────

  QualifiedName("serveMcp") -> NativeImpl((ctx, args) =>
    args match
      case List(transport) =>
        val builder = Option(Mcp.builderTL.get).getOrElse(
          throw InterpretError("serveMcp(...): no mcpServer { ... } configured first")
        )
        Mcp.transportTag(transport) match
          case "Stdio" =>
            // Block on stdin until EOF; write to stdout.  Uses System.in/out
            // directly — the dispatch loop is synchronous and single-threaded.
            val reader = BufferedReader(InputStreamReader(java.lang.System.in, "UTF-8"))
            val writer = BufferedWriter(OutputStreamWriter(java.lang.System.out, "UTF-8"))
            def read(): Option[String] =
              val ln = reader.readLine(); if ln == null then None else Some(ln)
            def write(s: String): Unit =
              writer.write(s); writer.flush()
            McpServerCore.serve(builder, read, write, "ssc-mcp-int", "1.0.0")
          case "Http" =>
            // Phase 2: register a POST route on the existing WebServer.
            // The handler decodes req.body, calls handleHttpRequest, returns
            // the JSON-RPC reply as the HTTP response body.  Then start the
            // WebServer on the requested port.
            val (port, path) = Mcp.httpArgs(transport)
            Mcp.installHttpRoute(builder, path, ctx)
            if !ctx.headless then
              scalascript.server.WebServer.start(port, ".", ctx.out)
          case "Ws" =>
            // WebSocket transport: register a WS route that pipes every
            // incoming text frame through McpServerCore.handleHttpRequest
            // and pushes the reply back via ws.send.  Reuses the existing
            // WS server infrastructure via ctx.registerWsRoute.
            val (port, path) = Mcp.httpArgs(transport)
            Mcp.installWsRoute(builder, path, ctx)
            if !ctx.headless then
              scalascript.server.WebServer.start(port, ".", ctx.out)
          case other =>
            throw InterpretError(s"serveMcp: unsupported transport '$other'")
      case _ => throw InterpretError("serveMcp(transport)")
  ),

  // ─── mcpConnect(transport[, timeoutMs]) ────────────────────────────

  QualifiedName("mcpConnect") -> NativeImpl((ctx, args) =>
    val (transport, timeoutMs) = args match
      case List(t)                  => (t, 30000L)
      case List(t, ms: Long)        => (t, ms)
      case List(t, ms: Int)         => (t, ms.toLong)
      case _ => throw InterpretError("mcpConnect(transport[, timeoutMs])")
    Mcp.transportTag(transport) match
      case "Spawn" =>
        val (cmd, cmdArgs) = Mcp.spawnArgs(transport)
        Mcp.makeSpawnClient(cmd, cmdArgs, timeoutMs, ctx)
      case "Http" =>
        val url = Mcp.httpClientUrl(transport)
        Mcp.makeHttpClient(url, timeoutMs)
      case "Ws" =>
        val url = Mcp.wsClientUrl(transport)
        Mcp.makeWsClient(url, timeoutMs, ctx)
      case "Stdio" =>
        throw InterpretError("mcpConnect: Transport.Stdio makes sense for servers, not clients — use Transport.Spawn")
      case other =>
        throw InterpretError(s"mcpConnect: unsupported transport '$other'")
  )
)

/** Private helpers — kept inside an object so the public intrinsic map
 *  stays a single `val`.  Thread-local stash for the builder so
 *  parallel scalatest suites don't trample each other. */
private object Mcp:

  /** Thread-local holds the currently-configured builder between
   *  `mcpServer { ... }` and `serveMcp(...)` calls. */
  val builderTL: ThreadLocal[McpServerBuilder] = new ThreadLocal[McpServerBuilder]()

  /** Read the Transport.* variant tag from a Value.InstanceV. */
  def transportTag(v: Any): String = v match
    case Value.InstanceV(tag, _) => tag
    case _                       => ""

  /** Extract `(cmd, args)` from a `Transport.Spawn(cmd, args: List[String])`. */
  def spawnArgs(v: Any): (String, List[String]) = v match
    case Value.InstanceV("Spawn", fields) =>
      val cmd = fields.get("cmd").collect { case Value.StringV(s) => s }.getOrElse("")
      val args = fields.get("args").collect {
        case Value.ListV(xs) => xs.collect { case Value.StringV(s) => s }
      }.getOrElse(Nil)
      (cmd, args)
    case _ => ("", Nil)

  /** Extract `(port, path)` from a `Transport.Http(port, path)` — server-side. */
  def httpArgs(v: Any): (Int, String) = v match
    case Value.InstanceV("Http", fields) =>
      val port = fields.get("port").collect {
        case Value.IntV(i) => i.toInt
      }.getOrElse(8080)
      val path = fields.get("path").collect { case Value.StringV(s) => s }.getOrElse("/mcp")
      (port, path)
    case _ => (8080, "/mcp")

  /** Client-side: a Transport.Http on the client side carries `url`
   *  (not port+path).  std/mcp/types.ssc keeps a single `Http(port, path)`
   *  variant for symmetry; client code passes the full URL via `path`
   *  (e.g. `Transport.Http(0, "http://localhost:8080/mcp")`) — port is
   *  ignored on the client.  Alternatively the user can construct a
   *  one-off Transport.Http(port, "/mcp") and rely on default host
   *  resolution; we look at `path` first and fall back to `http://localhost:<port><path>`. */
  def httpClientUrl(v: Any): String = v match
    case Value.InstanceV("Http", fields) =>
      val path = fields.get("path").collect { case Value.StringV(s) => s }.getOrElse("/mcp")
      if path.startsWith("http://") || path.startsWith("https://") then path
      else
        val port = fields.get("port").collect { case Value.IntV(i) => i.toInt }.getOrElse(8080)
        s"http://localhost:$port$path"
    case _ => "http://localhost:8080/mcp"

  /** Register a POST handler for `<path>` on the existing WebServer that
   *  pipes the body through `McpServerCore.handleHttpRequest`.  Called by
   *  `serveMcp(Transport.Http(port, path))` before the server starts. */
  def installHttpRoute(builder: McpServerBuilder, path: String, ctx: NativeContext): Unit =
    val handler = Value.NativeFnV("mcp.http.handler", Computation.pureFn {
      case List(Value.InstanceV("Request", fields)) =>
        val body = fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
        val reply = McpServerCore.handleHttpRequest(builder, body, "ssc-mcp-int", "1.0.0")
        val (status, respBody) =
          if reply.isEmpty then (204, "")
          else (200, reply)
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(status.toLong),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("application/json")
          )),
          "body"    -> Value.StringV(respBody)
        ))
      case _ => Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(400L),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute("POST", path, handler)

  /** Build an `McpClient` Value backed by `McpHttpClient`. */
  def makeHttpClient(url: String, timeoutMs: Long): Value =
    val client = new McpHttpClient(url, timeoutMs)
    // Spec-mandated initialize handshake — same shape as the Spawn path.
    val initParams = ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "ssc-mcp-int", "version" -> "1.0.0")
    )
    client.request(McpProtocol.Method.Initialize, initParams) match
      case Left(e)  => throw InterpretError(s"mcpConnect(Http): initialize failed: ${e.message}")
      case Right(_) => client.notify("notifications/initialized", ujson.Obj())
    makeHttpClientInstance(client, timeoutMs)

  /** Transport.Ws(port, path) → `ws://localhost:port/path`.  Same `path`
   *  override rule as Http: a full ws://...  / wss://... URL passes through. */
  def wsClientUrl(v: Any): String = v match
    case Value.InstanceV("Ws", fields) =>
      val path = fields.get("path").collect { case Value.StringV(s) => s }.getOrElse("/mcp")
      if path.startsWith("ws://") || path.startsWith("wss://") then path
      else
        val port = fields.get("port").collect { case Value.IntV(i) => i.toInt }.getOrElse(8080)
        s"ws://localhost:$port$path"
    case _ => "ws://localhost:8080/mcp"

  /** Register a WS route on the WebServer that dispatches every inbound
   *  text frame through `McpServerCore.handleHttpRequest` (which already
   *  handles request / notification / parse-error variants) and ships
   *  the reply back via `ws.send`.  The user-side ws value is what
   *  `ctx.registerWsRoute`'s handler receives. */
  def installWsRoute(builder: McpServerBuilder, path: String, ctx: NativeContext): Unit =
    val handler = Value.NativeFnV("mcp.ws.handler", Computation.pureFn {
      case List(Value.InstanceV("WebSocket", wsFields)) =>
        val sendV: Option[Value]      = wsFields.get("send")
        val onMessageV: Option[Value] = wsFields.get("onMessage")
        // Register a NativeFnV as the onMessage callback.  When called by
        // the WS infra with a Value.StringV(line) payload, we feed it to
        // McpServerCore.handleHttpRequest and write the reply via ws.send.
        val onMessageCb = Value.NativeFnV("mcp.ws.onMessage", Computation.pureFn {
          case List(Value.StringV(line)) =>
            val reply = McpServerCore.handleHttpRequest(builder, line, "ssc-mcp-int", "1.0.0")
            if reply.nonEmpty then
              sendV.foreach(sendFn => ctx.invokeCallback(sendFn, List(Value.StringV(reply.stripSuffix("\n")))))
            Value.UnitV
          case _ => Value.UnitV
        })
        onMessageV.foreach(om => ctx.invokeCallback(om, List(onMessageCb)))
        Value.UnitV
      case _ => Value.UnitV
    })
    ctx.registerWsRoute(path, Nil, Nil, 0, 0, handler)

  /** Build an `McpClient` Value backed by `McpWsClient`. */
  def makeWsClient(url: String, timeoutMs: Long, ctx: NativeContext): Value =
    val client = new McpWsClient(url, timeoutMs)
    val initParams = ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "ssc-mcp-int", "version" -> "1.0.0")
    )
    client.request(McpProtocol.Method.Initialize, initParams) match
      case Left(e)  =>
        client.close()
        throw InterpretError(s"mcpConnect(Ws): initialize failed: ${e.message}")
      case Right(_) => client.notify("notifications/initialized", ujson.Obj())
    makeWsClientInstance(client, timeoutMs, ctx)

  /** Build the `srv` instance the user-side `mcpServer { srv => ... }`
   *  block receives.  Each method-field is a `NativeFnV` that returns
   *  either a `Unit` (no-arg lifecycle hooks) or another `NativeFnV`
   *  (the curried `tool(name)(handler)` / `resource(uri)(handler)` /
   *  `prompt(name)(handler)` two-step). */
  def makeServerInstance(builder: McpServerBuilder, ctx: NativeContext): Value.InstanceV =
    def toolFn = Value.NativeFnV("McpServer.tool", Computation.pureFn {
      case List(Value.StringV(name)) =>
        Value.NativeFnV(s"McpServer.tool.$name", Computation.pureFn {
          case List(handler) =>
            registerTool(builder, name, None, handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.tool(name)(handler)")
        })
      case List(Value.StringV(name), Value.StringV(desc)) =>
        Value.NativeFnV(s"McpServer.tool.$name", Computation.pureFn {
          case List(handler) =>
            registerTool(builder, name, Some(desc), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.tool(name, desc)(handler)")
        })
      case _ => throw InterpretError("srv.tool(name[, desc])(handler)")
    })
    def resourceFn = Value.NativeFnV("McpServer.resource", Computation.pureFn {
      case List(Value.StringV(uri)) =>
        Value.NativeFnV(s"McpServer.resource.$uri", Computation.pureFn {
          case List(handler) =>
            registerResource(builder, uri, None, None, handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.resource(uri)(handler)")
        })
      case List(Value.StringV(uri), Value.StringV(name)) =>
        Value.NativeFnV(s"McpServer.resource.$uri", Computation.pureFn {
          case List(handler) =>
            registerResource(builder, uri, Some(name), None, handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.resource(uri, name)(handler)")
        })
      case List(Value.StringV(uri), Value.StringV(name), Value.StringV(mimeType)) =>
        Value.NativeFnV(s"McpServer.resource.$uri", Computation.pureFn {
          case List(handler) =>
            registerResource(builder, uri, Some(name), Some(mimeType), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.resource(uri, name, mimeType)(handler)")
        })
      case _ => throw InterpretError("srv.resource(uri[, name[, mimeType]])(handler)")
    })
    def promptFn = Value.NativeFnV("McpServer.prompt", Computation.pureFn {
      case List(Value.StringV(name)) =>
        Value.NativeFnV(s"McpServer.prompt.$name", Computation.pureFn {
          case List(handler) =>
            registerPrompt(builder, name, None, handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.prompt(name)(handler)")
        })
      case List(Value.StringV(name), Value.StringV(desc)) =>
        Value.NativeFnV(s"McpServer.prompt.$name", Computation.pureFn {
          case List(handler) =>
            registerPrompt(builder, name, Some(desc), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.prompt(name, desc)(handler)")
        })
      case _ => throw InterpretError("srv.prompt(name[, desc])(handler)")
    })
    def onConnFn = Value.NativeFnV("McpServer.onConnected", Computation.pureFn {
      case List(handler) =>
        builder.setOnConnected(() => { ctx.invokeCallback(handler, Nil); () })
        Value.UnitV
      case _ => throw InterpretError("srv.onConnected(() => ...)")
    })
    def onDisconnFn = Value.NativeFnV("McpServer.onDisconnected", Computation.pureFn {
      case List(handler) =>
        builder.setOnDisconnected(() => { ctx.invokeCallback(handler, Nil); () })
        Value.UnitV
      case _ => throw InterpretError("srv.onDisconnected(() => ...)")
    })
    Value.InstanceV("McpServer", Map(
      "tool"           -> toolFn,
      "resource"       -> resourceFn,
      "prompt"         -> promptFn,
      "onConnected"    -> onConnFn,
      "onDisconnected" -> onDisconnFn
    ))

  private def registerTool(
    builder: McpServerBuilder,
    name:    String,
    desc:    Option[String],
    handler: Value,
    ctx:     NativeContext
  ): Unit =
    builder.tool(name, desc, ujson.Obj("type" -> "object"), args =>
      val argsValue = mapToValue(args)
      val result    = ctx.invokeCallback(handler, List(argsValue))
      valueToToolResult(result)
    )

  private def registerResource(
    builder:  McpServerBuilder,
    uri:      String,
    name:     Option[String],
    mimeType: Option[String],
    handler:  Value,
    ctx:      NativeContext
  ): Unit =
    builder.resource(uri, name, mimeType, requestedUri =>
      val result = ctx.invokeCallback(handler, List(Value.StringV(requestedUri)))
      valueToResourceResult(result)
    )

  private def registerPrompt(
    builder: McpServerBuilder,
    name:    String,
    desc:    Option[String],
    handler: Value,
    ctx:     NativeContext
  ): Unit =
    builder.prompt(name, desc, Nil, args =>
      val argsValue = mapToValue(args)
      val result    = ctx.invokeCallback(handler, List(argsValue))
      valueToPromptResult(result)
    )

  // ─── Value → JSON marshallers for handler results ──────────────────

  /** A user `Tool.text("hi")` evaluates to `Value.InstanceV("ToolResult",
   *  Map("content" -> ListV(...), "isError" -> BoolV(...)))` in the
   *  interpreter.  We flatten that into the `ToolHandlerResult` the core
   *  expects. */
  def valueToToolResult(v: Any): ToolHandlerResult = v match
    case Value.InstanceV("ToolResult", fields) =>
      val items = fields.get("content").collect {
        case Value.ListV(xs) => xs.map(contentValueToJson)
      }.getOrElse(Nil)
      val isErr = fields.get("isError").collect { case Value.BoolV(b) => b }.getOrElse(false)
      ToolHandlerResult(items, isErr)
    case Value.StringV(s) =>
      ToolHandlerResult(List(McpProtocol.textContent(s)), isError = false)
    case other =>
      ToolHandlerResult(List(McpProtocol.textContent(Value.show(other.asInstanceOf[Value]))), isError = false)

  def valueToResourceResult(v: Any): ResourceHandlerResult = v match
    case Value.InstanceV("ResourceResult", fields) =>
      val uri = fields.get("uri").collect { case Value.StringV(s) => s }.getOrElse("")
      val contents = fields.get("contents").collect {
        case Value.ListV(xs) => xs.map(contentValueToJson)
      }.getOrElse(Nil)
      ResourceHandlerResult(uri, contents)
    case other =>
      ResourceHandlerResult("", List(McpProtocol.textContent(Value.show(other.asInstanceOf[Value]))))

  def valueToPromptResult(v: Any): PromptHandlerResult = v match
    case Value.InstanceV("PromptResult", fields) =>
      val msgs = fields.get("messages").collect {
        case Value.ListV(xs) => xs.map(messageValueToJson)
      }.getOrElse(Nil)
      PromptHandlerResult(None, msgs)
    case _ =>
      PromptHandlerResult(None, Nil)

  /** `Content.Text(s)` / `Content.Image(data, mime)` / `Content.Resource(uri)`
   *  → MCP wire JSON object. */
  def contentValueToJson(v: Value): ujson.Value = v match
    case Value.InstanceV("Text", fields) =>
      val s = fields.get("text").collect { case Value.StringV(s) => s }.getOrElse("")
      McpProtocol.textContent(s)
    case Value.InstanceV("Image", fields) =>
      val data = fields.get("data").collect { case Value.StringV(s) => s }.getOrElse("")
      val mime = fields.get("mimeType").collect { case Value.StringV(s) => s }.getOrElse("application/octet-stream")
      McpProtocol.imageContent(data, mime)
    case Value.InstanceV("Resource", fields) =>
      val uri = fields.get("uri").collect { case Value.StringV(s) => s }.getOrElse("")
      McpProtocol.resourceContent(uri)
    case Value.StringV(s) => McpProtocol.textContent(s)
    case other            => McpProtocol.textContent(Value.show(other))

  /** `Message(role, content)` → `{role: "user|assistant|system", content: {...}}` */
  def messageValueToJson(v: Value): ujson.Value = v match
    case Value.InstanceV("Message", fields) =>
      val role = fields.get("role").collect {
        case Value.InstanceV("User", _)      => "user"
        case Value.InstanceV("Assistant", _) => "assistant"
        case Value.InstanceV("System", _)    => "system"
      }.getOrElse("user")
      val content = fields.get("content").map(contentValueToJson).getOrElse(McpProtocol.textContent(""))
      ujson.Obj("role" -> role, "content" -> content)
    case _ => ujson.Obj("role" -> "user", "content" -> McpProtocol.textContent(""))

  /** Lift `Map[String, Any]` (decoded by McpServerCore.jsonToScala) to a
   *  Value.MapV the handler closure sees as the `args` parameter. */
  def mapToValue(m: Map[String, Any]): Value =
    Value.MapV(m.map { case (k, v) => Value.StringV(k) -> anyToValue(v) })

  def anyToValue(a: Any): Value = a match
    case null            => Value.OptionV(None)
    case b: Boolean      => Value.BoolV(b)
    case s: String       => Value.StringV(s)
    case d: Double       => Value.DoubleV(d)
    case i: Int          => Value.IntV(i.toLong)
    case l: Long         => Value.IntV(l)
    case xs: List[?]     => Value.ListV(xs.map(anyToValue))
    case m: Map[?, ?]    => Value.MapV(m.iterator.map((k, v) => Value.StringV(k.toString) -> anyToValue(v)).toMap)
    case other           => Value.StringV(other.toString)

  // ─── Spawn client construction ─────────────────────────────────────

  /** Spawn `cmd args*` as a subprocess and return a Value.InstanceV
   *  exposing the McpClient API — listTools / callTool / etc. */
  def makeSpawnClient(cmd: String, cmdArgs: List[String], timeoutMs: Long, ctx: NativeContext): Value =
    val pb = new ProcessBuilder((cmd :: cmdArgs).asJavaList).redirectErrorStream(false)
    val proc = pb.start()
    val stdin  = BufferedWriter(OutputStreamWriter(proc.getOutputStream, "UTF-8"))
    val stdout = BufferedReader(InputStreamReader(proc.getInputStream,  "UTF-8"))
    val client = new McpClientCore(line => { stdin.write(line); stdin.flush() })

    // Reader thread: pump every line from the subprocess into the client
    // dispatch table.  Dies when the subprocess closes its stdout.
    val readerThread = new Thread(() => {
      try
        var alive = true
        while alive do
          val ln = stdout.readLine()
          if ln == null then alive = false else client.dispatchResponse(ln)
      catch case _: Throwable => ()
      client.close()
    }, s"mcp-spawn-reader-${proc.pid()}")
    readerThread.setDaemon(true)
    readerThread.start()

    // Handshake — send `initialize` and wait for the server's reply.  We
    // don't fail on its content; just record we tried.
    val initParams = ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "ssc-mcp-int", "version" -> "1.0.0")
    )
    val initResult = client.request(McpProtocol.Method.Initialize, initParams, timeoutMs)
    initResult match
      case Left(e) =>
        proc.destroy()
        throw InterpretError(s"mcpConnect: initialize failed: ${e.message}")
      case Right(_) =>
        client.notify("notifications/initialized", ujson.Obj())

    makeClientInstance(client, proc, timeoutMs, ctx)

  private def makeClientInstance(
    client:    McpClientCore,
    proc:      Process,
    timeoutMs: Long,
    ctx:       NativeContext
  ): Value.InstanceV =
    val fields = mutable.LinkedHashMap.empty[String, Value]

    fields("listTools") = Value.NativeFnV("McpClient.listTools", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listTools: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "tools", Mcp.toolDescriptorFromJson)
    })
    fields("listResources") = Value.NativeFnV("McpClient.listResources", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listResources: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "resources", Mcp.resourceDescriptorFromJson)
    })
    fields("listPrompts") = Value.NativeFnV("McpClient.listPrompts", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listPrompts: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "prompts", Mcp.promptDescriptorFromJson)
    })
    fields("callTool") = Value.NativeFnV("McpClient.callTool", Computation.pureFn {
      case List(Value.StringV(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.ToolsCall, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"callTool: ${e.message}")
          case Right(json) => Mcp.toolResultFromJson(json)
      case _ => throw InterpretError("client.callTool(name, args)")
    })
    fields("readResource") = Value.NativeFnV("McpClient.readResource", Computation.pureFn {
      case List(Value.StringV(uri)) =>
        val params = ujson.Obj("uri" -> uri)
        client.request(McpProtocol.Method.ResourcesRead, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"readResource: ${e.message}")
          case Right(json) => Mcp.resourceResultFromJson(json, uri)
      case _ => throw InterpretError("client.readResource(uri)")
    })
    fields("getPrompt") = Value.NativeFnV("McpClient.getPrompt", Computation.pureFn {
      case List(Value.StringV(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.PromptsGet, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"getPrompt: ${e.message}")
          case Right(json) => Mcp.promptResultFromJson(json)
      case _ => throw InterpretError("client.getPrompt(name, args)")
    })
    fields("close") = Value.NativeFnV("McpClient.close", Computation.pureFn { _ =>
      client.close()
      try proc.destroy()    catch case _: Throwable => ()
      Value.UnitV
    })
    fields("isClosed") = Value.NativeFnV("McpClient.isClosed", Computation.pureFn { _ =>
      Value.BoolV(client.isClosed)
    })
    // v1.17.x — server→client notification subscription.  Handler receives
    // (method: String, params: Value).  Replaces any previously-registered
    // handler.  Stdio/Spawn transports deliver notifications natively
    // (frames just arrive on the same stdout the reader thread pulls).
    fields("onNotification") = Value.NativeFnV("McpClient.onNotification", Computation.pureFn {
      case List(handler) =>
        client.setNotificationHandler { (method, params) =>
          ctx.invokeCallback(handler, List(Value.StringV(method), Mcp.jsonToValue(params)))
        }
        Value.UnitV
      case _ => throw InterpretError("client.onNotification(handler)")
    })
    Value.InstanceV("McpClient", fields.toMap)

  /** Same shape as `makeClientInstance` but routes through `McpHttpClient`
   *  instead of the stdio-backed `McpClientCore`.  Code duplication is
   *  intentional: both clients expose identical method surfaces but the
   *  request bodies differ in transport semantics (stdio is async with a
   *  pending-id table; HTTP is synchronous request/response). */
  def makeHttpClientInstance(client: McpHttpClient, timeoutMs: Long): Value.InstanceV =
    val fields = mutable.LinkedHashMap.empty[String, Value]
    fields("listTools") = Value.NativeFnV("McpClient.listTools", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listTools: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "tools", Mcp.toolDescriptorFromJson)
    })
    fields("listResources") = Value.NativeFnV("McpClient.listResources", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listResources: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "resources", Mcp.resourceDescriptorFromJson)
    })
    fields("listPrompts") = Value.NativeFnV("McpClient.listPrompts", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listPrompts: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "prompts", Mcp.promptDescriptorFromJson)
    })
    fields("callTool") = Value.NativeFnV("McpClient.callTool", Computation.pureFn {
      case List(Value.StringV(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.ToolsCall, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"callTool: ${e.message}")
          case Right(json) => Mcp.toolResultFromJson(json)
      case _ => throw InterpretError("client.callTool(name, args)")
    })
    fields("readResource") = Value.NativeFnV("McpClient.readResource", Computation.pureFn {
      case List(Value.StringV(uri)) =>
        val params = ujson.Obj("uri" -> uri)
        client.request(McpProtocol.Method.ResourcesRead, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"readResource: ${e.message}")
          case Right(json) => Mcp.resourceResultFromJson(json, uri)
      case _ => throw InterpretError("client.readResource(uri)")
    })
    fields("getPrompt") = Value.NativeFnV("McpClient.getPrompt", Computation.pureFn {
      case List(Value.StringV(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.PromptsGet, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"getPrompt: ${e.message}")
          case Right(json) => Mcp.promptResultFromJson(json)
      case _ => throw InterpretError("client.getPrompt(name, args)")
    })
    fields("close") = Value.NativeFnV("McpClient.close", Computation.pureFn { _ =>
      client.close(); Value.UnitV
    })
    fields("isClosed") = Value.NativeFnV("McpClient.isClosed", Computation.pureFn { _ =>
      Value.BoolV(client.isClosed)
    })
    // HTTP transport has no persistent server→client channel — there's no
    // place to push notifications.  Accept the handler so user code is
    // portable across transports, but never invoke it.  Real SSE-based
    // notifications would need a separate persistent GET /mcp/events
    // stream — that's a future iteration.
    fields("onNotification") = Value.NativeFnV("McpClient.onNotification", Computation.pureFn {
      case List(_) => Value.UnitV
      case _       => throw InterpretError("client.onNotification(handler)")
    })
    Value.InstanceV("McpClient", fields.toMap)

  /** Same shape as `makeHttpClientInstance` / `makeClientInstance` but
   *  routes through `McpWsClient`.  Persistent WS connection — the
   *  pending-request map handles id correlation server→client; the
   *  same channel delivers server→client notifications. */
  def makeWsClientInstance(client: McpWsClient, timeoutMs: Long, ctx: NativeContext): Value.InstanceV =
    val fields = mutable.LinkedHashMap.empty[String, Value]
    fields("listTools") = Value.NativeFnV("McpClient.listTools", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listTools: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "tools", Mcp.toolDescriptorFromJson)
    })
    fields("listResources") = Value.NativeFnV("McpClient.listResources", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listResources: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "resources", Mcp.resourceDescriptorFromJson)
    })
    fields("listPrompts") = Value.NativeFnV("McpClient.listPrompts", Computation.pureFn { _ =>
      client.request(McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => throw InterpretError(s"listPrompts: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "prompts", Mcp.promptDescriptorFromJson)
    })
    fields("callTool") = Value.NativeFnV("McpClient.callTool", Computation.pureFn {
      case List(Value.StringV(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.ToolsCall, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"callTool: ${e.message}")
          case Right(json) => Mcp.toolResultFromJson(json)
      case _ => throw InterpretError("client.callTool(name, args)")
    })
    fields("readResource") = Value.NativeFnV("McpClient.readResource", Computation.pureFn {
      case List(Value.StringV(uri)) =>
        val params = ujson.Obj("uri" -> uri)
        client.request(McpProtocol.Method.ResourcesRead, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"readResource: ${e.message}")
          case Right(json) => Mcp.resourceResultFromJson(json, uri)
      case _ => throw InterpretError("client.readResource(uri)")
    })
    fields("getPrompt") = Value.NativeFnV("McpClient.getPrompt", Computation.pureFn {
      case List(Value.StringV(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.PromptsGet, params, timeoutMs) match
          case Left(e)     => throw InterpretError(s"getPrompt: ${e.message}")
          case Right(json) => Mcp.promptResultFromJson(json)
      case _ => throw InterpretError("client.getPrompt(name, args)")
    })
    fields("close") = Value.NativeFnV("McpClient.close", Computation.pureFn { _ =>
      client.close(); Value.UnitV
    })
    fields("isClosed") = Value.NativeFnV("McpClient.isClosed", Computation.pureFn { _ =>
      Value.BoolV(client.isClosed)
    })
    // Same notification-subscription mechanism as the Spawn/Stdio path:
    // WS is a persistent bidirectional channel, server-initiated frames
    // dispatch through the reader thread.
    fields("onNotification") = Value.NativeFnV("McpClient.onNotification", Computation.pureFn {
      case List(handler) =>
        client.setNotificationHandler { (method, params) =>
          ctx.invokeCallback(handler, List(Value.StringV(method), Mcp.jsonToValue(params)))
        }
        Value.UnitV
      case _ => throw InterpretError("client.onNotification(handler)")
    })
    Value.InstanceV("McpClient", fields.toMap)

  // ─── JSON → Value adapters for client return values ────────────────

  def descriptorsListFromJson(json: ujson.Value, key: String, mk: ujson.Value => Value): Value =
    val list = json.objOpt.flatMap(_.get(key)).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    Value.ListV(list.map(mk))

  def toolDescriptorFromJson(v: ujson.Value): Value =
    val name   = v.objOpt.flatMap(_.get("name").flatMap(_.strOpt)).getOrElse("")
    val desc   = v.objOpt.flatMap(_.get("description").flatMap(_.strOpt)).getOrElse("")
    val schema = v.objOpt.flatMap(_.get("inputSchema")).getOrElse(ujson.Obj())
    Value.InstanceV("ToolDescriptor", Map(
      "name"        -> Value.StringV(name),
      "description" -> Value.StringV(desc),
      "schema"      -> jsonToValue(schema)
    ))

  def resourceDescriptorFromJson(v: ujson.Value): Value =
    val uri  = v.objOpt.flatMap(_.get("uri").flatMap(_.strOpt)).getOrElse("")
    val name = v.objOpt.flatMap(_.get("name").flatMap(_.strOpt)).getOrElse("")
    val mime = v.objOpt.flatMap(_.get("mimeType").flatMap(_.strOpt)).getOrElse("")
    Value.InstanceV("ResourceDescriptor", Map(
      "uri"      -> Value.StringV(uri),
      "name"     -> Value.StringV(name),
      "mimeType" -> Value.StringV(mime)
    ))

  def promptDescriptorFromJson(v: ujson.Value): Value =
    val name = v.objOpt.flatMap(_.get("name").flatMap(_.strOpt)).getOrElse("")
    val desc = v.objOpt.flatMap(_.get("description").flatMap(_.strOpt)).getOrElse("")
    Value.InstanceV("PromptDescriptor", Map(
      "name"        -> Value.StringV(name),
      "description" -> Value.StringV(desc),
      "args"        -> Value.ListV(Nil)
    ))

  def toolResultFromJson(v: ujson.Value): Value =
    val items = v.objOpt.flatMap(_.get("content")).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    val isErr = v.objOpt.flatMap(_.get("isError")).flatMap(_.boolOpt).getOrElse(false)
    Value.InstanceV("ToolResult", Map(
      "content" -> Value.ListV(items.map(contentJsonToValue)),
      "isError" -> Value.BoolV(isErr)
    ))

  def resourceResultFromJson(v: ujson.Value, uri: String): Value =
    val items = v.objOpt.flatMap(_.get("contents")).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    Value.InstanceV("ResourceResult", Map(
      "uri"      -> Value.StringV(uri),
      "contents" -> Value.ListV(items.map(contentJsonToValue))
    ))

  def promptResultFromJson(v: ujson.Value): Value =
    val msgs = v.objOpt.flatMap(_.get("messages")).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    Value.InstanceV("PromptResult", Map(
      "messages" -> Value.ListV(msgs.map(messageJsonToValue))
    ))

  def contentJsonToValue(v: ujson.Value): Value =
    v.objOpt.flatMap(_.get("type").flatMap(_.strOpt)) match
      case Some("text") =>
        val s = v.objOpt.flatMap(_.get("text").flatMap(_.strOpt)).getOrElse("")
        Value.InstanceV("Text", Map("text" -> Value.StringV(s)))
      case Some("image") =>
        val data = v.objOpt.flatMap(_.get("data").flatMap(_.strOpt)).getOrElse("")
        val mime = v.objOpt.flatMap(_.get("mimeType").flatMap(_.strOpt)).getOrElse("")
        Value.InstanceV("Image", Map("data" -> Value.StringV(data), "mimeType" -> Value.StringV(mime)))
      case _ =>
        Value.InstanceV("Text", Map("text" -> Value.StringV(v.render())))

  def messageJsonToValue(v: ujson.Value): Value =
    val role = v.objOpt.flatMap(_.get("role").flatMap(_.strOpt)).getOrElse("user")
    val roleVal = role match
      case "assistant" => Value.InstanceV("Assistant", Map.empty)
      case "system"    => Value.InstanceV("System",    Map.empty)
      case _           => Value.InstanceV("User",      Map.empty)
    val content = v.objOpt.flatMap(_.get("content")).map(contentJsonToValue).getOrElse(
      Value.InstanceV("Text", Map("text" -> Value.StringV("")))
    )
    Value.InstanceV("Message", Map("role" -> roleVal, "content" -> content))

  def jsonToValue(v: ujson.Value): Value = v match
    case ujson.Null    => Value.OptionV(None)
    case ujson.True    => Value.BoolV(true)
    case ujson.False   => Value.BoolV(false)
    case ujson.Str(s)  => Value.StringV(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => Value.IntV(n.toLong)
    case ujson.Num(n)  => Value.DoubleV(n)
    case ujson.Arr(xs) => Value.ListV(xs.iterator.map(jsonToValue).toList)
    case ujson.Obj(kv) => Value.MapV(kv.iterator.map((k, v) => Value.StringV(k) -> jsonToValue(v)).toMap)

  def valueToJson(v: Value): ujson.Value = v match
    case Value.OptionV(None)    => ujson.Null
    case Value.OptionV(Some(x)) => valueToJson(x)
    case Value.BoolV(b)         => if b then ujson.True else ujson.False
    case Value.StringV(s)       => ujson.Str(s)
    case Value.IntV(i)          => ujson.Num(i.toDouble)
    case Value.DoubleV(d)       => ujson.Num(d)
    case Value.ListV(xs)        => ujson.Arr.from(xs.map(valueToJson))
    case Value.MapV(m)          =>
      val obj = ujson.Obj()
      m.foreach { (k, v) =>
        val key = k match { case Value.StringV(s) => s; case other => Value.show(other) }
        obj(key) = valueToJson(v)
      }
      obj
    case other                  => ujson.Str(Value.show(other))

  /** Small helper to convert a Scala List[String] to a java.util.List[String]
   *  for ProcessBuilder without pulling in scala.jdk imports here. */
  extension (xs: List[String])
    def asJavaList: java.util.List[String] =
      val al = java.util.ArrayList[String](xs.size)
      xs.foreach(al.add); al
