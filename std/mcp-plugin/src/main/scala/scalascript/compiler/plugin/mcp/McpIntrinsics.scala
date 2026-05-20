package scalascript.compiler.plugin.mcp

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation, OAuthBridge}
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
object McpIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

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
              ctx.startServerAsync(port, ".")
            case "Ws" =>
              // WebSocket transport: register a WS route that pipes every
              // incoming text frame through McpServerCore.handleHttpRequest
              // and pushes the reply back via ws.send.  Reuses the existing
              // WS server infrastructure via ctx.registerWsRoute.
              val (port, path) = Mcp.httpArgs(transport)
              Mcp.installWsRoute(builder, path, ctx)
              ctx.startServerAsync(port, ".")
            case other =>
              throw InterpretError(s"serveMcp: unsupported transport '$other'")
        case _ => throw InterpretError("serveMcp(transport)")
    ),

    // ─── mcpConnect(transport[, timeoutMs]) ────────────────────────────

    QualifiedName("mcpConnect") -> NativeImpl((ctx, args) =>
      // v1.17.x — final optional argument is the bearer token (String).
      // Position-based: `mcpConnect(transport)`,
      //                 `mcpConnect(transport, timeoutMs)`,
      //                 `mcpConnect(transport, bearerToken)` or
      //                 `mcpConnect(transport, timeoutMs, bearerToken)`.
      val (transport, timeoutMs, bearer) = args match
        case List(t)                              => (t, 30000L, None)
        case List(t, ms: Long)                    => (t, ms, None)
        case List(t, ms: Int)                     => (t, ms.toLong, None)
        case List(t, bt: String)                  => (t, 30000L, Some(bt))
        case List(t, ms: Long,   bt: String)      => (t, ms, Some(bt))
        case List(t, ms: Int,    bt: String)      => (t, ms.toLong, Some(bt))
        case _ => throw InterpretError("mcpConnect(transport[, timeoutMs][, bearerToken])")
      Mcp.transportTag(transport) match
        case "Spawn" =>
          val (cmd, cmdArgs) = Mcp.spawnArgs(transport)
          Mcp.makeSpawnClient(cmd, cmdArgs, timeoutMs, ctx)
        case "Http" =>
          val url = Mcp.httpClientUrl(transport)
          Mcp.makeHttpClient(url, timeoutMs, ctx, bearer)
        case "Ws" =>
          val url = Mcp.wsClientUrl(transport)
          Mcp.makeWsClient(url, timeoutMs, ctx, bearer)
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
   *  `serveMcp(Transport.Http(port, path))` before the server starts.
   *
   *  Also registers a GET `<path>/events` SSE endpoint: clients that
   *  open it subscribe to server→client notifications via the same
   *  `builder.addSubscriber` mechanism that Stdio/Spawn/Ws use.  Each
   *  notification is wrapped as `data: <json>\n\n`. */
  def installHttpRoute(builder: McpServerBuilder, path: String, ctx: NativeContext): Unit =
    val handler = Value.NativeFnV("mcp.http.handler", Computation.pureFn {
      case List(Value.InstanceV("Request", fields)) =>
        val body = fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
        // v1.17.x — auth gate.  Extract the bearer token from the request
        // headers and run it through the registered validator; on
        // reject, return 401 with WWW-Authenticate before any dispatch.
        // When no validator is registered, this is a no-op pass-through.
        val headerMap = Mcp.extractHeaderMap(fields)
        val bearer    = McpAuth.extractBearer(headerMap)
        McpServerCore.authorizeHttp(builder, bearer) match
          case McpServerCore.AuthOutcome.Reject(code, descr) =>
            Mcp.unauthorizedResponse(builder, code, descr)
          case McpServerCore.AuthOutcome.Allowed(claims) =>
            Mcp.dispatchAuthorized(builder, body, fields, claims, ctx)
      case _ => Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(400L),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute("POST", path, handler)

    // v1.17.x — RFC 9728 protected-resource metadata.  Exposed only if
    // the user populated it via `srv.setProtectedResourceMetadata(...)`.
    // Clients fetch this to discover the matching authorization server.
    val metadataPath = "/.well-known/oauth-protected-resource"
    val metadataHandler = Value.NativeFnV("mcp.auth.prm", Computation.pureFn {
      case List(Value.InstanceV("Request", _)) =>
        builder.protectedResourceMetadata match
          case Some(m) => Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200L),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json")
            )),
            "body"    -> Value.StringV(m.toJson.render())
          ))
          case None => Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(404L),
            "headers" -> Value.MapV(Map.empty),
            "body"    -> Value.StringV("")
          ))
      case _ => Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(400L),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute("GET", metadataPath, metadataHandler)
    installSseRoute(builder, path, ctx)

  /** v1.17.x — extract the headers MapV from a Request InstanceV into a
   *  plain `Map[String, String]` for the auth helpers.  Non-string
   *  entries fall through silently — defensive against weird header
   *  encodings (e.g. byte arrays). */
  def extractHeaderMap(fields: Map[String, Value]): Map[String, String] =
    fields.get("headers").collect {
      case Value.MapV(m) => m.iterator.collect {
        case (Value.StringV(k), Value.StringV(v)) => k -> v
      }.toMap
    }.getOrElse(Map.empty)

  /** v1.17.x — assemble a 401 Response with WWW-Authenticate per RFC 6750. */
  def unauthorizedResponse(builder: McpServerBuilder, code: String, descr: String): Value =
    val www = McpAuth.wwwAuthenticate(builder.authRealm, code, Some(descr))
    val body = ujson.Obj("error" -> code, "error_description" -> descr).render()
    Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(401L),
      "headers" -> Value.MapV(Map(
        (Value.StringV("WWW-Authenticate"): Value) -> (Value.StringV(www):                  Value),
        (Value.StringV("Content-Type"):    Value) -> (Value.StringV("application/json"):    Value)
      )),
      "body"    -> Value.StringV(body)
    ))

  /** v1.17.x — original dispatch body extracted into a helper.  Runs
   *  inside `withAuth(claims)` so handlers can read `srv.currentAuth`.
   *  Streamable-HTTP: when the client sets `Accept: text/event-stream`,
   *  the response body becomes an SSE stream — progress notifications
   *  emitted via `srv.notify(...)` during dispatch are interleaved with
   *  the final JSON-RPC reply. */
  def dispatchAuthorized(
    builder: McpServerBuilder,
    body:    String,
    fields:  Map[String, Value],
    claims:  Option[McpAuth.AuthClaims],
    ctx:     NativeContext
  ): Value = builder.withAuth(claims) {
    val acceptsSse = fields.get("headers").collect {
      case Value.MapV(m) =>
        m.iterator.exists {
          case (Value.StringV(k), Value.StringV(v)) =>
            k.equalsIgnoreCase("Accept") && v.toLowerCase.contains("text/event-stream")
          case _ => false
        }
    }.getOrElse(false)
    if acceptsSse then
      val sseHeaders = Value.MapV(Map(
        (Value.StringV("Content-Type"):  Value) -> (Value.StringV("text/event-stream"):  Value),
        (Value.StringV("Cache-Control"): Value) -> (Value.StringV("no-cache"):           Value)
      ))
      val callback = Value.NativeFnV("mcp.http.post.sse", Computation.pureFn {
        case List(writeFn) =>
          val unsubscribe = builder.addSubscriber { line =>
            try ctx.invokeCallback(writeFn,
              List(Value.StringV(s"data: ${line.stripSuffix("\n")}\n\n")))
            catch case _: Throwable => ()
          }
          try
            val reply = builder.withAuth(claims) {
              McpServerCore.handleHttpRequest(builder, body, "ssc-mcp-int", "1.0.0")
            }
            if reply.nonEmpty then
              ctx.invokeCallback(writeFn,
                List(Value.StringV(s"data: ${reply.stripSuffix("\n")}\n\n")))
          finally unsubscribe()
          Value.UnitV
        case _ => Value.UnitV
      })
      Value.InstanceV("StreamResponse", Map(
        "status"   -> Value.IntV(200L),
        "headers"  -> sseHeaders,
        "callback" -> callback
      ))
    else
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
  }

  /** SSE GET endpoint at `<path>/events` — clients subscribe here to
   *  receive server-pushed notifications.  Returns a StreamResponse
   *  whose callback registers a builder subscriber and keeps the
   *  connection open until either the client closes (writer throws)
   *  or the server shuts down.  v1.17.x: the GET is also auth-gated
   *  when a validator is registered — same WWW-Authenticate path as
   *  the POST handler. */
  def installSseRoute(builder: McpServerBuilder, path: String, ctx: NativeContext): Unit =
    val sseEndpoint = path + "/events"
    val sseHandler = Value.NativeFnV("mcp.http.sse", Computation.pureFn {
      case List(Value.InstanceV("Request", fields)) =>
        val headerMap = Mcp.extractHeaderMap(fields)
        val bearer    = McpAuth.extractBearer(headerMap)
        McpServerCore.authorizeHttp(builder, bearer) match
          case McpServerCore.AuthOutcome.Reject(code, descr) =>
            Mcp.unauthorizedResponse(builder, code, descr)
          case McpServerCore.AuthOutcome.Allowed(_) =>
            val sseHeaders = Value.MapV(Map(
              (Value.StringV("Content-Type"):  Value) -> (Value.StringV("text/event-stream"):  Value),
              (Value.StringV("Cache-Control"): Value) -> (Value.StringV("no-cache"):           Value),
              (Value.StringV("Connection"):    Value) -> (Value.StringV("keep-alive"):         Value)
            ))
            val callback = Value.NativeFnV("mcp.http.sse.writer", Computation.pureFn {
              case List(writeFn) =>
                val done = new java.util.concurrent.atomic.AtomicBoolean(false)
                val unsubscribe = builder.addSubscriber { line =>
                  if !done.get() then
                    try ctx.invokeCallback(writeFn, List(Value.StringV(s"data: ${line.stripSuffix("\n")}\n\n")))
                    catch case _: Throwable => done.set(true)
                }
                try
                  while !done.get() do Thread.sleep(100)
                finally unsubscribe()
                Value.UnitV
              case _ => Value.UnitV
            })
            Value.InstanceV("StreamResponse", Map(
              "status"   -> Value.IntV(200L),
              "headers"  -> sseHeaders,
              "callback" -> callback
            ))
      case _ => Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(400L),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute("GET", sseEndpoint, sseHandler)

  /** Build an `McpClient` Value backed by `McpHttpClient`. */
  def makeHttpClient(url: String, timeoutMs: Long, ctx: NativeContext,
                     bearerToken: Option[String] = None): Value =
    val client = new McpHttpClient(url, timeoutMs)
    bearerToken.foreach(t => client.setBearerToken(Some(t)))
    // Spec-mandated initialize handshake — same shape as the Spawn path.
    val initParams = ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "ssc-mcp-int", "version" -> "1.0.0")
    )
    client.request(McpProtocol.Method.Initialize, initParams) match
      case Left(e)  => throw InterpretError(s"mcpConnect(Http): initialize failed: ${e.message}")
      case Right(_) => client.notify("notifications/initialized", ujson.Obj())
    makeHttpClientInstance(client, timeoutMs, ctx)

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
        val sendV:      Option[Value] = wsFields.get("send")
        val onMessageV: Option[Value] = wsFields.get("onMessage")
        val onCloseV:   Option[Value] = wsFields.get("onClose")
        // Per-connection broadcaster slot: every ws.send that this
        // connection exposes joins the server's subscriber set, so
        // srv.notify(method, params) reaches this client too.  The
        // unsubscribe is wired through ws.onClose so a dropped client
        // doesn't leave a stale writer behind.
        val unsubscribe = sendV.map(sendFn =>
          builder.addSubscriber(line =>
            ctx.invokeCallback(sendFn, List(Value.StringV(line.stripSuffix("\n"))))
          )
        ).getOrElse(() => ())
        onCloseV.foreach { oc =>
          val onCloseCb = Value.NativeFnV("mcp.ws.onClose", Computation.pureFn {
            case _ => unsubscribe(); Value.UnitV
          })
          ctx.invokeCallback(oc, List(onCloseCb))
        }
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
  def makeWsClient(url: String, timeoutMs: Long, ctx: NativeContext,
                   bearerToken: Option[String] = None): Value =
    val client = new McpWsClient(url, timeoutMs, bearerToken)
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
            registerTool(builder, name, None, ujson.Obj("type" -> "object"), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.tool(name)(handler)")
        })
      case List(Value.StringV(name), Value.StringV(desc)) =>
        Value.NativeFnV(s"McpServer.tool.$name", Computation.pureFn {
          case List(handler) =>
            registerTool(builder, name, Some(desc), ujson.Obj("type" -> "object"), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.tool(name, desc)(handler)")
        })
      case _ => throw InterpretError("srv.tool(name[, desc])(handler)")
    })
    // v1.17.x — typed tool: takes an explicit JSON Schema for inputs.
    // Combined with `case class Args(...) derives McpSchema`, the user
    // can write:
    //
    //   srv.toolWithSchema("name", summon[McpSchema[Args]].schema)(handler)
    //
    // and have the tool advertise the auto-generated schema.  The handler
    // still receives `Map[String, Any]` — manual decode at the boundary;
    // a fully-typed `A => ToolResult` overload is a follow-up that needs
    // the v1.14 Mirror to expose field constructors. */
    def toolWithSchemaFn = Value.NativeFnV("McpServer.toolWithSchema", Computation.pureFn {
      case List(Value.StringV(name), schemaV) =>
        Value.NativeFnV(s"McpServer.toolWithSchema.$name", Computation.pureFn {
          case List(handler) =>
            registerTool(builder, name, None, Mcp.valueToJson(schemaV), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.toolWithSchema(name, schema)(handler)")
        })
      case List(Value.StringV(name), Value.StringV(desc), schemaV) =>
        Value.NativeFnV(s"McpServer.toolWithSchema.$name", Computation.pureFn {
          case List(handler) =>
            registerTool(builder, name, Some(desc), Mcp.valueToJson(schemaV), handler, ctx)
            Value.UnitV
          case _ => throw InterpretError("srv.toolWithSchema(name, desc, schema)(handler)")
        })
      case _ => throw InterpretError("srv.toolWithSchema(name[, desc], schema)(handler)")
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
    // v1.17.x — URI-template resources.  `srv.resourceTemplate(template[,
    // name[, description[, mimeType]]])(handler)` registers a parameterized
    // resource like `file:///{path}`.  Listed via resources/templates/list;
    // a concrete `resources/read` URI matching the template (RFC 6570
    // simplified: `{name}` → non-slash segment) flows to `handler`.
    def resourceTemplateFn = Value.NativeFnV("McpServer.resourceTemplate", Computation.pureFn {
      case List(Value.StringV(tpl)) =>
        Value.NativeFnV(s"McpServer.resourceTemplate.$tpl", Computation.pureFn {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, None, None, None, handler, ctx); Value.UnitV
          case _ => throw InterpretError("srv.resourceTemplate(template)(handler)")
        })
      case List(Value.StringV(tpl), Value.StringV(name)) =>
        Value.NativeFnV(s"McpServer.resourceTemplate.$tpl", Computation.pureFn {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, Some(name), None, None, handler, ctx); Value.UnitV
          case _ => throw InterpretError("srv.resourceTemplate(template, name)(handler)")
        })
      case List(Value.StringV(tpl), Value.StringV(name), Value.StringV(desc)) =>
        Value.NativeFnV(s"McpServer.resourceTemplate.$tpl", Computation.pureFn {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, Some(name), Some(desc), None, handler, ctx); Value.UnitV
          case _ => throw InterpretError("srv.resourceTemplate(template, name, description)(handler)")
        })
      case List(Value.StringV(tpl), Value.StringV(name), Value.StringV(desc), Value.StringV(mime)) =>
        Value.NativeFnV(s"McpServer.resourceTemplate.$tpl", Computation.pureFn {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, Some(name), Some(desc), Some(mime), handler, ctx); Value.UnitV
          case _ => throw InterpretError("srv.resourceTemplate(template, name, description, mimeType)(handler)")
        })
      case _ => throw InterpretError("srv.resourceTemplate(template[, name[, description[, mimeType]]])(handler)")
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
    // v1.17.x — resource subscriptions.  Hooks fire when the client
    // sends `resources/subscribe` / `resources/unsubscribe`; typical
    // wiring: spin up a file/DB watcher in the subscribe handler and
    // call `srv.notifyResourceUpdate(uri)` from its callback.
    def onResSubFn = Value.NativeFnV("McpServer.onResourceSubscribe", Computation.pureFn {
      case List(handler) =>
        builder.setOnResourceSubscribe { uri =>
          ctx.invokeCallback(handler, List(Value.StringV(uri))); ()
        }
        Value.UnitV
      case _ => throw InterpretError("srv.onResourceSubscribe(uri => ...)")
    })
    def onResUnsubFn = Value.NativeFnV("McpServer.onResourceUnsubscribe", Computation.pureFn {
      case List(handler) =>
        builder.setOnResourceUnsubscribe { uri =>
          ctx.invokeCallback(handler, List(Value.StringV(uri))); ()
        }
        Value.UnitV
      case _ => throw InterpretError("srv.onResourceUnsubscribe(uri => ...)")
    })
    def notifyResUpdateFn = Value.NativeFnV("McpServer.notifyResourceUpdate", Computation.pureFn {
      case List(Value.StringV(uri)) =>
        builder.notifyResourceUpdate(uri)
        Value.UnitV
      case _ => throw InterpretError("srv.notifyResourceUpdate(uri)")
    })
    // v1.17.x — list_changed notifications.  Servers call these after
    // registering or un-registering a tool / resource / prompt at runtime
    // to nudge clients to re-fetch the catalog.  Matching capability
    // flags are advertised in initialize.
    def notifyToolsLCFn = Value.NativeFnV("McpServer.notifyToolsListChanged",
      Computation.pureFn { _ => builder.notifyToolsListChanged(); Value.UnitV })
    def notifyResourcesLCFn = Value.NativeFnV("McpServer.notifyResourcesListChanged",
      Computation.pureFn { _ => builder.notifyResourcesListChanged(); Value.UnitV })
    def notifyPromptsLCFn = Value.NativeFnV("McpServer.notifyPromptsListChanged",
      Computation.pureFn { _ => builder.notifyPromptsListChanged(); Value.UnitV })
    // v1.17.x — cancellation.  Long-running tool handlers poll
    // `srv.isCancelled` at safe points and return early (typically with
    // an isError=true ToolResult) when the client has sent
    // notifications/cancelled for the current request.  MCP cancellation
    // is cooperative — honoring it is up to the handler.
    def isCancelledFn = Value.NativeFnV("McpServer.isCancelled",
      Computation.pureFn { _ => Value.BoolV(builder.isCancelled) })
    // v1.17.x — progress notifications.  Inside a tool/resource/prompt
    // handler, `srv.notifyProgress(progress[, total])` emits a
    // `notifications/progress` frame with the current handler's
    // progressToken (captured from the client's `_meta.progressToken`
    // on the originating request).  No-op when the client didn't ask
    // for progress.
    def notifyProgressFn = Value.NativeFnV("McpServer.notifyProgress", Computation.pureFn {
      case List(Value.DoubleV(p)) => builder.notifyProgress(p); Value.UnitV
      case List(Value.IntV(p))    => builder.notifyProgress(p.toDouble); Value.UnitV
      case List(Value.DoubleV(p), Value.DoubleV(t)) => builder.notifyProgress(p, Some(t)); Value.UnitV
      case List(Value.IntV(p),    Value.IntV(t))    => builder.notifyProgress(p.toDouble, Some(t.toDouble)); Value.UnitV
      case List(Value.DoubleV(p), Value.IntV(t))    => builder.notifyProgress(p, Some(t.toDouble)); Value.UnitV
      case List(Value.IntV(p),    Value.DoubleV(t)) => builder.notifyProgress(p.toDouble, Some(t)); Value.UnitV
      case _ => throw InterpretError("srv.notifyProgress(progress[, total])")
    })
    // v1.17.x — logging.  `srv.log(level, data[, logger])` emits a
    // `notifications/message` frame iff the client-set log floor (via
    // logging/setLevel) is at or below the line's severity.  Default
    // floor is "info" — debug is silenced until the client opts in.
    def logFn = Value.NativeFnV("McpServer.log", Computation.pureFn {
      case List(Value.StringV(level), data) =>
        builder.log(level, Mcp.valueToJson(data)); Value.UnitV
      case List(Value.StringV(level), data, Value.StringV(logger)) =>
        builder.log(level, Mcp.valueToJson(data), Some(logger)); Value.UnitV
      case _ => throw InterpretError("srv.log(level, data[, logger])")
    })
    def currentLogLevelFn = Value.NativeFnV("McpServer.currentLogLevel",
      Computation.pureFn { _ => Value.StringV(builder.loggingLevel) })
    // v1.17.x — server-initiated notifications.  `srv.notify(method, params)`
    // broadcasts a JSON-RPC notification frame to every currently-active
    // subscriber (Stdio/Spawn: one writer; Ws: one per connected client;
    // Http without SSE: no-op since no persistent push channel exists).
    def notifyFn = Value.NativeFnV("McpServer.notify", Computation.pureFn {
      case List(Value.StringV(method)) =>
        builder.notify(method, ujson.Obj())
        Value.UnitV
      case List(Value.StringV(method), paramsV) =>
        builder.notify(method, Mcp.valueToJson(paramsV))
        Value.UnitV
      case _ => throw InterpretError("srv.notify(method[, params])")
    })
    // v1.17.x — bidirectional sampling.  Server can call into a connected
    // client (typical use: `srv.request("sampling/createMessage", args)`
    // to ask the client's LLM to produce a completion).  Blocks until
    // the matching client.onRequest handler replies or `timeoutMs`
    // fires.  Returns the result Value on success, throws InterpretError
    // on timeout / error.
    def requestFn = Value.NativeFnV("McpServer.request", Computation.pureFn {
      case List(Value.StringV(method), paramsV, Value.IntV(timeoutMs)) =>
        builder.request(method, Mcp.valueToJson(paramsV), timeoutMs) match
          case Left(e)     => throw InterpretError(s"srv.request($method): ${e.message}")
          case Right(json) => Mcp.jsonToValue(json)
      case List(Value.StringV(method), paramsV) =>
        builder.request(method, Mcp.valueToJson(paramsV), 30_000L) match
          case Left(e)     => throw InterpretError(s"srv.request($method): ${e.message}")
          case Right(json) => Mcp.jsonToValue(json)
      case List(Value.StringV(method)) =>
        builder.request(method, ujson.Obj(), 30_000L) match
          case Left(e)     => throw InterpretError(s"srv.request($method): ${e.message}")
          case Right(json) => Mcp.jsonToValue(json)
      case _ => throw InterpretError("srv.request(method[, params[, timeoutMs]])")
    })
    // v1.17.x — roots (workspace info from the client).  Server pulls the
    // current root list on demand via `srv.listRoots()`; client pushes a
    // `notifications/roots/list_changed` when it changes, which we route
    // to the user's `srv.onRootsListChanged(...)` callback.  Returns a
    // List of Map(uri -> ..., name -> ...) for ergonomic destructuring
    // in user code.
    def listRootsFn = Value.NativeFnV("McpServer.listRoots", Computation.pureFn {
      case List(Value.IntV(timeoutMs)) =>
        builder.listRoots(timeoutMs) match
          case Left(e)      => throw InterpretError(s"srv.listRoots: ${e.message}")
          case Right(roots) => Mcp.rootsToValue(roots)
      case Nil =>
        builder.listRoots() match
          case Left(e)      => throw InterpretError(s"srv.listRoots: ${e.message}")
          case Right(roots) => Mcp.rootsToValue(roots)
      case _ => throw InterpretError("srv.listRoots([timeoutMs])")
    })
    def onRootsLCFn = Value.NativeFnV("McpServer.onRootsListChanged", Computation.pureFn {
      case List(handler) =>
        builder.setOnRootsListChanged(() => { ctx.invokeCallback(handler, Nil); () })
        Value.UnitV
      case _ => throw InterpretError("srv.onRootsListChanged(() => ...)")
    })
    def clientSupportsRootsFn = Value.NativeFnV("McpServer.clientSupportsRoots",
      Computation.pureFn { _ => Value.BoolV(builder.clientSupportsRoots) })
    // v1.17.x — elicitation.  `srv.elicit(message, schema[, timeoutMs])`
    // pops a prompt on the client side asking for user input matching
    // the supplied JSON Schema.  Returns an ElicitationResult instance:
    //   - Accept(content) → user filled in the form
    //   - Decline         → user clicked No
    //   - Cancel          → user dismissed the dialog
    // Treat the latter two as the safe "user didn't agree" branch.
    def elicitFn = Value.NativeFnV("McpServer.elicit", Computation.pureFn {
      case List(Value.StringV(message), schemaV, Value.IntV(timeoutMs)) =>
        builder.elicit(message, Mcp.valueToJson(schemaV), timeoutMs) match
          case Left(e)  => throw InterpretError(s"srv.elicit: ${e.message}")
          case Right(r) => Mcp.elicitationResultToValue(r)
      case List(Value.StringV(message), schemaV) =>
        builder.elicit(message, Mcp.valueToJson(schemaV)) match
          case Left(e)  => throw InterpretError(s"srv.elicit: ${e.message}")
          case Right(r) => Mcp.elicitationResultToValue(r)
      case _ => throw InterpretError("srv.elicit(message, schema[, timeoutMs])")
    })
    def clientSupportsElicitationFn =
      Value.NativeFnV("McpServer.clientSupportsElicitation",
        Computation.pureFn { _ => Value.BoolV(builder.clientSupportsElicitation) })
    // v1.17.x — completion handlers.  The handler is a function
    // `String => List[String]` (current partial value → suggestions).
    // Two registration entry points for the two ref shapes the spec
    // supports: `ref/prompt` keyed by prompt name, `ref/resource` keyed
    // by URI-template string.  Spec caps results at 100; the wire-layer
    // applies the cap so user handlers can return as many as they want.
    def completionForPromptFn = Value.NativeFnV("McpServer.completionForPrompt",
      Computation.pureFn {
        case List(Value.StringV(promptName), Value.StringV(argName), handler) =>
          builder.completionForPrompt(promptName, argName, value =>
            ctx.invokeCallback(handler, List(Value.StringV(value))) match
              case v: Value => Mcp.valueToStringList(v)
              case _        => Nil
          )
          Value.UnitV
        case _ => throw InterpretError("srv.completionForPrompt(promptName, argName, handler)")
      })
    // v1.17.x — pagination.  `srv.setPageSize(N)` caps every list
    // endpoint at N items per page; nextCursor opaque-encodes the offset
    // for the next page.  N <= 0 disables pagination (default).
    // v1.17.x — authorization.  `srv.setTokenValidator(handler)` wires
    // a `token: String => InstanceV("AuthResult", ...)` checker.  The
    // user-facing shape mirrors `McpAuth.AuthResult`: either
    //   InstanceV("Valid",   { subject, scopes: List[String], extra })
    // or
    //   InstanceV("Invalid", { code, description })
    // The HTTP route then gates every request through this validator.
    // `srv.useHmacValidator(secret)` is a convenience for tests +
    // trusted-internal deployments.
    def setTokenValidatorFn = Value.NativeFnV("McpServer.setTokenValidator",
      Computation.pureFn {
        case List(handler) =>
          builder.setTokenValidator(Some(token =>
            ctx.invokeCallback(handler, List(Value.StringV(token))) match
              case v: Value => Mcp.valueToAuthResult(v)
              case _        => McpAuth.AuthResult.Invalid("invalid_token", "validator returned non-Value")
          ))
          Value.UnitV
        case _ => throw InterpretError("srv.setTokenValidator(handler)")
      })
    def useHmacValidatorFn = Value.NativeFnV("McpServer.useHmacValidator",
      Computation.pureFn {
        case List(Value.StringV(secret)) =>
          builder.setTokenValidator(Some(McpAuth.hmacValidator(secret)))
          Value.UnitV
        case _ => throw InterpretError("srv.useHmacValidator(secret)")
      })
    // v1.17.x — bridge to the standalone OAuth Authorization Server.
    // Takes the InstanceV produced by `oauth.authServer(...)` and wires
    // its token validator + protected-resource metadata into this MCP
    // server.  Resolves via OAuthBridge (shared with the oauth-plugin).
    def useAuthServerFn = Value.NativeFnV("McpServer.useAuthServer",
      Computation.pureFn {
        case List(asValue) =>
          (asValue match
            case Value.InstanceV("AuthServer", fields) =>
              fields.get("_id").collect { case Value.StringV(id) => id }
                .flatMap(id => Option(OAuthBridge.authServers.get(id))
                  .collect { case as: scalascript.oauth.AuthServer => as })
            case _ => None
          ) match
            case Some(as) => builder.useAuthServer(as); Value.UnitV
            case None     => throw InterpretError(
              "srv.useAuthServer: argument is not an AuthServer instance (use oauth.authServer(...))")
        case _ => throw InterpretError("srv.useAuthServer(authServer)")
      })
    def setAuthRealmFn = Value.NativeFnV("McpServer.setAuthRealm",
      Computation.pureFn {
        case List(Value.StringV(realm)) => builder.setAuthRealm(realm); Value.UnitV
        case _ => throw InterpretError("srv.setAuthRealm(realm)")
      })
    def currentAuthFn = Value.NativeFnV("McpServer.currentAuth",
      Computation.pureFn { _ => Mcp.authClaimsToValueOpt(builder.currentAuth) })
    def authEnabledFn = Value.NativeFnV("McpServer.authEnabled",
      Computation.pureFn { _ => Value.BoolV(builder.authEnabled) })
    def setPrmFn = Value.NativeFnV("McpServer.setProtectedResourceMetadata",
      Computation.pureFn {
        case List(metadataV) =>
          builder.setProtectedResourceMetadata(Mcp.valueToPrm(metadataV))
          Value.UnitV
        case _ => throw InterpretError("srv.setProtectedResourceMetadata(metadata)")
      })
    def issueHmacTokenFn = Value.NativeFnV("McpServer.issueHmacToken",
      Computation.pureFn {
        case List(Value.StringV(secret), Value.StringV(subject), scopesV, Value.IntV(expSec)) =>
          Value.StringV(McpAuth.issueHmacToken(secret, subject,
            Mcp.valueToStringList(scopesV).toSet, expSec))
        case _ => throw InterpretError("srv.issueHmacToken(secret, subject, scopes, expiresInSeconds)")
      })
    def setPageSizeFn = Value.NativeFnV("McpServer.setPageSize",
      Computation.pureFn {
        case List(Value.IntV(n)) => builder.setPageSize(n.toInt); Value.UnitV
        case _ => throw InterpretError("srv.setPageSize(n)")
      })
    def currentPageSizeFn = Value.NativeFnV("McpServer.currentPageSize",
      Computation.pureFn { _ => Value.IntV(builder.currentPageSize) })
    def completionForResourceFn = Value.NativeFnV("McpServer.completionForResource",
      Computation.pureFn {
        case List(Value.StringV(uriTemplate), Value.StringV(argName), handler) =>
          builder.completionForResource(uriTemplate, argName, value =>
            ctx.invokeCallback(handler, List(Value.StringV(value))) match
              case v: Value => Mcp.valueToStringList(v)
              case _        => Nil
          )
          Value.UnitV
        case _ => throw InterpretError("srv.completionForResource(uriTemplate, argName, handler)")
      })
    Value.InstanceV("McpServer", Map(
      "tool"                          -> toolFn,
      "toolWithSchema"                -> toolWithSchemaFn,
      "resource"                      -> resourceFn,
      "resourceTemplate"              -> resourceTemplateFn,
      "prompt"                        -> promptFn,
      "notifyToolsListChanged"        -> notifyToolsLCFn,
      "notifyResourcesListChanged"    -> notifyResourcesLCFn,
      "notifyPromptsListChanged"      -> notifyPromptsLCFn,
      "isCancelled"                   -> isCancelledFn,
      "notifyProgress"                -> notifyProgressFn,
      "log"                           -> logFn,
      "currentLogLevel"               -> currentLogLevelFn,
      "onConnected"            -> onConnFn,
      "onDisconnected"         -> onDisconnFn,
      "onResourceSubscribe"    -> onResSubFn,
      "onResourceUnsubscribe"  -> onResUnsubFn,
      "notifyResourceUpdate"   -> notifyResUpdateFn,
      "notify"                 -> notifyFn,
      "request"                -> requestFn,
      "listRoots"                  -> listRootsFn,
      "onRootsListChanged"         -> onRootsLCFn,
      "clientSupportsRoots"        -> clientSupportsRootsFn,
      "elicit"                     -> elicitFn,
      "clientSupportsElicitation"  -> clientSupportsElicitationFn,
      "completionForPrompt"        -> completionForPromptFn,
      "completionForResource"      -> completionForResourceFn,
      "setPageSize"                -> setPageSizeFn,
      "currentPageSize"            -> currentPageSizeFn,
      "setTokenValidator"          -> setTokenValidatorFn,
      "useHmacValidator"           -> useHmacValidatorFn,
      "useAuthServer"              -> useAuthServerFn,
      "setAuthRealm"               -> setAuthRealmFn,
      "currentAuth"                -> currentAuthFn,
      "authEnabled"                -> authEnabledFn,
      "setProtectedResourceMetadata" -> setPrmFn,
      "issueHmacToken"             -> issueHmacTokenFn
    ))

  private def registerTool(
    builder: McpServerBuilder,
    name:    String,
    desc:    Option[String],
    schema:  ujson.Value,
    handler: Value,
    ctx:     NativeContext
  ): Unit =
    builder.tool(name, desc, schema, args =>
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

  private def registerResourceTemplate(
    builder:     McpServerBuilder,
    uriTemplate: String,
    name:        Option[String],
    description: Option[String],
    mimeType:    Option[String],
    handler:     Value,
    ctx:         NativeContext
  ): Unit =
    builder.resourceTemplate(uriTemplate, name, description, mimeType, requestedUri =>
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
    // v1.17.x — bidirectional sampling.  Handler returns the result Value;
    // exceptions become JSON-RPC error responses sent back via the writer
    // so the server-side pending map unblocks.
    fields("onRequest") = Value.NativeFnV("McpClient.onRequest", Computation.pureFn {
      case List(handler) =>
        client.setRequestHandler { (method, params) =>
          ctx.invokeCallback(handler, List(Value.StringV(method), Mcp.jsonToValue(params))) match
            case v: Value => Mcp.valueToJson(v)
            case other    => ujson.Str(String.valueOf(other))
        }
        Value.UnitV
      case _ => throw InterpretError("client.onRequest(handler)")
    })
    Value.InstanceV("McpClient", fields.toMap)

  /** Same shape as `makeClientInstance` but routes through `McpHttpClient`
   *  instead of the stdio-backed `McpClientCore`.  Code duplication is
   *  intentional: both clients expose identical method surfaces but the
   *  request bodies differ in transport semantics (stdio is async with a
   *  pending-id table; HTTP is synchronous request/response). */
  def makeHttpClientInstance(client: McpHttpClient, timeoutMs: Long, ctx: NativeContext): Value.InstanceV =
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
    // HTTP push via SSE GET `/events` — the client opens a daemon reader
    // thread that parses `data: <json>\n\n` frames and dispatches them
    // as notifications.  The server side (installHttpRoute) registers the
    // matching `/events` GET endpoint when `serveMcp(Transport.Http(...))`
    // is called.
    fields("onNotification") = Value.NativeFnV("McpClient.onNotification", Computation.pureFn {
      case List(handler) =>
        client.setNotificationHandler { (method, params) =>
          ctx.invokeCallback(handler, List(Value.StringV(method), Mcp.jsonToValue(params)))
        }
        Value.UnitV
      case _ => throw InterpretError("client.onNotification(handler)")
    })
    // Bidirectional sampling over HTTP: the SSE GET stream now also
    // delivers Request frames; McpHttpClient.setRequestHandler runs
    // the handler and POSTs the JSON-RPC Response back to the same
    // `/mcp` URL — handleHttpRequest server-side routes it through
    // builder.routeInboundResponse so the broadcaster's pending map
    // unblocks.  Same API shape as the Stdio/Spawn/Ws paths.
    fields("onRequest") = Value.NativeFnV("McpClient.onRequest", Computation.pureFn {
      case List(handler) =>
        client.setRequestHandler { (method, params) =>
          ctx.invokeCallback(handler, List(Value.StringV(method), Mcp.jsonToValue(params))) match
            case v: Value => Mcp.valueToJson(v)
            case other    => ujson.Str(String.valueOf(other))
        }
        Value.UnitV
      case _ => throw InterpretError("client.onRequest(handler)")
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
    fields("onRequest") = Value.NativeFnV("McpClient.onRequest", Computation.pureFn {
      case List(handler) =>
        client.setRequestHandler { (method, params) =>
          ctx.invokeCallback(handler, List(Value.StringV(method), Mcp.jsonToValue(params))) match
            case v: Value => Mcp.valueToJson(v)
            case other    => ujson.Str(String.valueOf(other))
        }
        Value.UnitV
      case _ => throw InterpretError("client.onRequest(handler)")
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

  /** v1.17.x — adapt a typed `Root` list into a `List[InstanceV]` so user
   *  scripts can pattern-match on `root.uri` / `root.name`.  `name` is
   *  modelled as `Option[String]` (None when the client didn't supply one). */
  def rootsToValue(roots: List[McpProtocol.Root]): Value =
    Value.ListV(roots.map { r =>
      Value.InstanceV("Root", Map(
        "uri"  -> Value.StringV(r.uri),
        "name" -> Value.OptionV(r.name.map(s => Value.StringV(s)))
      ))
    })

  /** v1.17.x — adapt the three-way ElicitationResult into a single
   *  InstanceV with two fields: `action` (one of "accept"/"decline"/"cancel")
   *  and `content` (`Option[Map[...]]` — `Some(...)` only for accept).
   *  User scripts pattern-match on `action` and read `content`
   *  conditionally, or use the spec-recommended pattern of treating
   *  decline+cancel identically. */
  /** Adapt a `ListV` of `StringV` (or anything renderable) into a
   *  `List[String]` for completion handlers.  Non-string elements
   *  fall back to their `show` representation — defensive but
   *  preserves user intent better than dropping them silently. */
  def valueToStringList(v: Value): List[String] = v match
    case Value.ListV(xs) => xs.map {
      case Value.StringV(s) => s
      case other            => Value.show(other)
    }
    case _ => Nil

  /** v1.17.x — auth helpers: adapt to/from the user-facing `AuthResult`
   *  / `AuthClaims` / `ProtectedResourceMetadata` shapes.
   *
   *  AuthResult discriminator:
   *    InstanceV("Valid",   { subject: String, scopes: List[String], extra: Map })
   *    InstanceV("Invalid", { code: String, description: String })
   *  Anything else collapses to Invalid("invalid_token", ...). */
  def valueToAuthResult(v: Value): McpAuth.AuthResult = v match
    case Value.InstanceV("Valid", fs) =>
      val sub = fs.get("subject").collect { case Value.StringV(s) => s }.getOrElse("")
      val scopes = fs.get("scopes").map(valueToStringList).getOrElse(Nil).toSet
      val extra  = fs.get("extra").map(valueToJson).getOrElse(ujson.Obj())
      McpAuth.AuthResult.Valid(McpAuth.AuthClaims(sub, scopes, extra))
    case Value.InstanceV("Invalid", fs) =>
      val code  = fs.get("code").collect { case Value.StringV(s) => s }.getOrElse("invalid_token")
      val descr = fs.get("description").collect { case Value.StringV(s) => s }.getOrElse("")
      McpAuth.AuthResult.Invalid(code, descr)
    case _ =>
      McpAuth.AuthResult.Invalid("invalid_token", s"validator returned unexpected: ${Value.show(v)}")

  def authClaimsToValueOpt(c: Option[McpAuth.AuthClaims]): Value = c match
    case None => Value.OptionV(None)
    case Some(claims) =>
      Value.OptionV(Some(Value.InstanceV("AuthClaims", Map(
        "subject" -> Value.StringV(claims.subject),
        "scopes"  -> Value.ListV(claims.scopes.toList.sorted.map(s => Value.StringV(s))),
        "extra"   -> jsonToValue(claims.extra)
      ))))

  /** Decode a user-supplied `Map` / `InstanceV` describing the metadata
   *  document.  Missing fields fall to spec defaults. */
  def valueToPrm(v: Value): McpAuth.ProtectedResourceMetadata =
    val obj = v match
      case Value.InstanceV(_, fs) => fs
      case Value.MapV(m)          => m.iterator.collect {
        case (Value.StringV(k), vv) => k -> vv
      }.toMap
      case _ => Map.empty[String, Value]
    val resource = obj.get("resource").collect { case Value.StringV(s) => s }.getOrElse("")
    val authSrvs = obj.get("authorizationServers").map(valueToStringList).getOrElse(Nil)
    val scopes   = obj.get("scopesSupported").map(valueToStringList).getOrElse(Nil)
    val doc      = obj.get("resourceDocumentation").collect { case Value.StringV(s) => s }
    McpAuth.ProtectedResourceMetadata(
      resource              = resource,
      authorizationServers  = authSrvs,
      scopesSupported       = scopes,
      resourceDocumentation = doc
    )

  def elicitationResultToValue(r: McpProtocol.ElicitationResult): Value = r match
    case McpProtocol.ElicitationResult.Accept(content) =>
      Value.InstanceV("ElicitationResult", Map(
        "action"  -> Value.StringV("accept"),
        "content" -> Value.OptionV(Some(jsonToValue(content)))
      ))
    case McpProtocol.ElicitationResult.Decline =>
      Value.InstanceV("ElicitationResult", Map(
        "action"  -> Value.StringV("decline"),
        "content" -> Value.OptionV(None)
      ))
    case McpProtocol.ElicitationResult.Cancel =>
      Value.InstanceV("ElicitationResult", Map(
        "action"  -> Value.StringV("cancel"),
        "content" -> Value.OptionV(None)
      ))

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
