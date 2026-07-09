package scalascript.compiler.plugin.mcp

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginError, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, MapVal, Inst, Opt}
import scalascript.plugin.api.OAuthBridge
import scalascript.mcp.*
import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import scala.collection.mutable
import scalascript.plugin.api.PluginNative
import scalascript.plugin.api.PluginContext

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

    QualifiedName("mcpServer") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(setup) =>
          val builder = new McpServerBuilder
          Mcp.builderTL.set(builder)
          val srvInstance = Mcp.makeServerInstance(builder, ctx)
          ctx.invokeCallback(setup, List(srvInstance))
          ()
        case _ => PluginError.raise("mcpServer { srv => ... }")
    },

    // ─── serveMcp(transport) ────────────────────────────────────────────

    QualifiedName("serveMcp") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(transport) =>
          val builder = Option(Mcp.builderTL.get).getOrElse(
            PluginError.raise("serveMcp(...): no mcpServer { ... } configured first")
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
              PluginError.raise(s"serveMcp: unsupported transport '$other'")
        case _ => PluginError.raise("serveMcp(transport)")
    },

    // ─── mcpConnect(transport[, timeoutMs]) ────────────────────────────

    QualifiedName("mcpConnect") -> PluginNative.evalLegacy { (ctx, args) =>
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
        case _ => PluginError.raise("mcpConnect(transport[, timeoutMs][, bearerToken])")
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
          PluginError.raise("mcpConnect: Transport.Stdio makes sense for servers, not clients — use Transport.Spawn")
        case other =>
          PluginError.raise(s"mcpConnect: unsupported transport '$other'")
    }
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
    case Inst(tag, _) => tag
    case _                       => ""

  /** Extract `(cmd, args)` from a `Transport.Spawn(cmd, args: List[String])`. */
  def spawnArgs(v: Any): (String, List[String]) = v match
    case Inst("Spawn", fields) =>
      val cmd = fields.get("cmd").collect { case Str(s) => s }.getOrElse("")
      val args = fields.get("args").collect {
        case Lst(xs) => xs.collect { case Str(s) => s }
      }.getOrElse(Nil)
      (cmd, args)
    case _ => ("", Nil)

  /** Extract `(port, path)` from a `Transport.Http(port, path)` — server-side. */
  def httpArgs(v: Any): (Int, String) = v match
    case Inst("Http", fields) =>
      val port = fields.get("port").collect {
        case Num(i) => i.toInt
      }.getOrElse(8080)
      val path = fields.get("path").collect { case Str(s) => s }.getOrElse("/mcp")
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
    case Inst("Http", fields) =>
      val path = fields.get("path").collect { case Str(s) => s }.getOrElse("/mcp")
      if path.startsWith("http://") || path.startsWith("https://") then path
      else
        val port = fields.get("port").collect { case Num(i) => i.toInt }.getOrElse(8080)
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
  def installHttpRoute(builder: McpServerBuilder, path: String, ctx: PluginContext): Unit =
    val handler = PluginValue.nativeFn("mcp.http.handler", {
      case List(Inst("Request", fields)) =>
        val body = fields.get("body").collect { case Str(s) => s }.getOrElse("")
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
      case _ => PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(400L),
        "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
        "body"    -> PluginValue.string("expected Request")
      ))
    })
    ctx.registerRoute("POST", path, handler)

    // v1.17.x — RFC 9728 protected-resource metadata.  Exposed only if
    // the user populated it via `srv.setProtectedResourceMetadata(...)`.
    // Clients fetch this to discover the matching authorization server.
    val metadataPath = "/.well-known/oauth-protected-resource"
    val metadataHandler = PluginValue.nativeFn("mcp.auth.prm", {
      case List(Inst("Request", _)) =>
        builder.protectedResourceMetadata match
          case Some(m) => PluginValue.instance("Response", Map(
            "status"  -> PluginValue.int(200L),
            "headers" -> PluginValue.mapOf(Map(
              PluginValue.string("Content-Type") -> PluginValue.string("application/json")
            )),
            "body"    -> PluginValue.string(m.toJson.render())
          ))
          case None => PluginValue.instance("Response", Map(
            "status"  -> PluginValue.int(404L),
            "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
            "body"    -> PluginValue.string("")
          ))
      case _ => PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(400L),
        "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
        "body"    -> PluginValue.string("expected Request")
      ))
    })
    ctx.registerRoute("GET", metadataPath, metadataHandler)
    installSseRoute(builder, path, ctx)

  /** v1.17.x — extract the headers MapV from a Request InstanceV into a
   *  plain `Map[String, String]` for the auth helpers.  Non-string
   *  entries fall through silently — defensive against weird header
   *  encodings (e.g. byte arrays). */
  def extractHeaderMap(fields: Map[String, PluginValue]): Map[String, String] =
    fields.get("headers").collect {
      case MapVal(m) => m.iterator.collect {
        case (Str(k), Str(v)) => k -> v
      }.toMap
    }.getOrElse(Map.empty)

  /** v1.17.x — assemble a 401 Response with WWW-Authenticate per RFC 6750. */
  def unauthorizedResponse(builder: McpServerBuilder, code: String, descr: String): PluginValue =
    val www = McpAuth.wwwAuthenticate(builder.authRealm, code, Some(descr))
    val body = ujson.Obj("error" -> code, "error_description" -> descr).render()
    PluginValue.instance("Response", Map(
      "status"  -> PluginValue.int(401L),
      "headers" -> PluginValue.mapOf(Map(
        (PluginValue.string("WWW-Authenticate"): PluginValue) -> (PluginValue.string(www): PluginValue),
        (PluginValue.string("Content-Type"): PluginValue) -> (PluginValue.string("application/json"): PluginValue)
      )),
      "body"    -> PluginValue.string(body)
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
    fields:  Map[String, PluginValue],
    claims:  Option[McpAuth.AuthClaims],
    ctx: PluginContext
  ): PluginValue = builder.withAuth(claims) {
    val acceptsSse = fields.get("headers").collect {
      case MapVal(m) =>
        m.iterator.exists {
          case (Str(k), Str(v)) =>
            k.equalsIgnoreCase("Accept") && v.toLowerCase.contains("text/event-stream")
          case _ => false
        }
    }.getOrElse(false)
    if acceptsSse then
      val sseHeaders = PluginValue.mapOf(Map(
        (PluginValue.string("Content-Type"): PluginValue) -> (PluginValue.string("text/event-stream"): PluginValue),
        (PluginValue.string("Cache-Control"): PluginValue) -> (PluginValue.string("no-cache"): PluginValue)
      ))
      val callback = PluginValue.nativeFn("mcp.http.post.sse", {
        case List(writeFn) =>
          val unsubscribe = builder.addSubscriber { line =>
            try ctx.invokeCallback(writeFn,
              List(PluginValue.string(s"data: ${line.stripSuffix("\n")}\n\n")))
            catch case _: Throwable => ()
          }
          try
            val reply = builder.withAuth(claims) {
              McpServerCore.handleHttpRequest(builder, body, "ssc-mcp-int", "1.0.0")
            }
            if reply.nonEmpty then
              ctx.invokeCallback(writeFn,
                List(PluginValue.string(s"data: ${reply.stripSuffix("\n")}\n\n")))
          finally unsubscribe()
          PluginValue.unit
        case _ => PluginValue.unit
      })
      PluginValue.instance("StreamResponse", Map(
        "status"   -> PluginValue.int(200L),
        "headers"  -> sseHeaders,
        "callback" -> callback
      ))
    else
      val reply = McpServerCore.handleHttpRequest(builder, body, "ssc-mcp-int", "1.0.0")
      val (status, respBody) =
        if reply.isEmpty then (204, "")
        else (200, reply)
      PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(status.toLong),
        "headers" -> PluginValue.mapOf(Map(
          PluginValue.string("Content-Type") -> PluginValue.string("application/json")
        )),
        "body"    -> PluginValue.string(respBody)
      ))
  }

  /** SSE GET endpoint at `<path>/events` — clients subscribe here to
   *  receive server-pushed notifications.  Returns a StreamResponse
   *  whose callback registers a builder subscriber and keeps the
   *  connection open until either the client closes (writer throws)
   *  or the server shuts down.  v1.17.x: the GET is also auth-gated
   *  when a validator is registered — same WWW-Authenticate path as
   *  the POST handler. */
  def installSseRoute(builder: McpServerBuilder, path: String, ctx: PluginContext): Unit =
    val sseEndpoint = path + "/events"
    val sseHandler = PluginValue.nativeFn("mcp.http.sse", {
      case List(Inst("Request", fields)) =>
        val headerMap = Mcp.extractHeaderMap(fields)
        val bearer    = McpAuth.extractBearer(headerMap)
        McpServerCore.authorizeHttp(builder, bearer) match
          case McpServerCore.AuthOutcome.Reject(code, descr) =>
            Mcp.unauthorizedResponse(builder, code, descr)
          case McpServerCore.AuthOutcome.Allowed(_) =>
            val sseHeaders = PluginValue.mapOf(Map(
              (PluginValue.string("Content-Type"): PluginValue) -> (PluginValue.string("text/event-stream"): PluginValue),
              (PluginValue.string("Cache-Control"): PluginValue) -> (PluginValue.string("no-cache"): PluginValue),
              (PluginValue.string("Connection"): PluginValue) -> (PluginValue.string("keep-alive"): PluginValue)
            ))
            val callback = PluginValue.nativeFn("mcp.http.sse.writer", {
              case List(writeFn) =>
                val done = new java.util.concurrent.atomic.AtomicBoolean(false)
                val unsubscribe = builder.addSubscriber { line =>
                  if !done.get() then
                    try ctx.invokeCallback(writeFn, List(PluginValue.string(s"data: ${line.stripSuffix("\n")}\n\n")))
                    catch case _: Throwable => done.set(true)
                }
                try
                  while !done.get() do Thread.sleep(100)
                finally unsubscribe()
                PluginValue.unit
              case _ => PluginValue.unit
            })
            PluginValue.instance("StreamResponse", Map(
              "status"   -> PluginValue.int(200L),
              "headers"  -> sseHeaders,
              "callback" -> callback
            ))
      case _ => PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(400L),
        "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
        "body"    -> PluginValue.string("expected Request")
      ))
    })
    ctx.registerRoute("GET", sseEndpoint, sseHandler)

  /** Build an `McpClient` Value backed by `McpHttpClient`. */
  def makeHttpClient(url: String, timeoutMs: Long, ctx: PluginContext,
                     bearerToken: Option[String] = None): PluginValue =
    val client = new McpHttpClient(url, timeoutMs)
    bearerToken.foreach(t => client.setBearerToken(Some(t)))
    // Spec-mandated initialize handshake — same shape as the Spawn path.
    val initParams = ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "ssc-mcp-int", "version" -> "1.0.0")
    )
    client.request(McpProtocol.Method.Initialize, initParams) match
      case Left(e)  => PluginError.raise(s"mcpConnect(Http): initialize failed: ${e.message}")
      case Right(_) => client.notify("notifications/initialized", ujson.Obj())
    makeHttpClientInstance(client, timeoutMs, ctx)

  /** Transport.Ws(port, path) → `ws://localhost:port/path`.  Same `path`
   *  override rule as Http: a full ws://...  / wss://... URL passes through. */
  def wsClientUrl(v: Any): String = v match
    case Inst("Ws", fields) =>
      val path = fields.get("path").collect { case Str(s) => s }.getOrElse("/mcp")
      if path.startsWith("ws://") || path.startsWith("wss://") then path
      else
        val port = fields.get("port").collect { case Num(i) => i.toInt }.getOrElse(8080)
        s"ws://localhost:$port$path"
    case _ => "ws://localhost:8080/mcp"

  /** Register a WS route on the WebServer that dispatches every inbound
   *  text frame through `McpServerCore.handleHttpRequest` (which already
   *  handles request / notification / parse-error variants) and ships
   *  the reply back via `ws.send`.  The user-side ws value is what
   *  `ctx.registerWsRoute`'s handler receives. */
  def installWsRoute(builder: McpServerBuilder, path: String, ctx: PluginContext): Unit =
    val handler = PluginValue.nativeFn("mcp.ws.handler", {
      case List(Inst("WebSocket", wsFields)) =>
        val sendV:      Option[PluginValue] = wsFields.get("send")
        val onMessageV: Option[PluginValue] = wsFields.get("onMessage")
        val onCloseV:   Option[PluginValue] = wsFields.get("onClose")
        // Per-connection broadcaster slot: every ws.send that this
        // connection exposes joins the server's subscriber set, so
        // srv.notify(method, params) reaches this client too.  The
        // unsubscribe is wired through ws.onClose so a dropped client
        // doesn't leave a stale writer behind.
        val unsubscribe = sendV.map(sendFn =>
          builder.addSubscriber(line =>
            ctx.invokeCallback(sendFn, List(PluginValue.string(line.stripSuffix("\n"))))
          )
        ).getOrElse(() => ())
        onCloseV.foreach { oc =>
          val onCloseCb = PluginValue.nativeFn("mcp.ws.onClose", {
            case _ => unsubscribe(); PluginValue.unit
          })
          ctx.invokeCallback(oc, List(onCloseCb))
        }
        // Register a NativeFnV as the onMessage callback.  When called by
        // the WS infra with a PluginValue.string(line) payload, we feed it to
        // McpServerCore.handleHttpRequest and write the reply via ws.send.
        val onMessageCb = PluginValue.nativeFn("mcp.ws.onMessage", {
          case List(Str(line)) =>
            val reply = McpServerCore.handleHttpRequest(builder, line, "ssc-mcp-int", "1.0.0")
            if reply.nonEmpty then
              sendV.foreach(sendFn => ctx.invokeCallback(sendFn, List(PluginValue.string(reply.stripSuffix("\n")))))
            PluginValue.unit
          case _ => PluginValue.unit
        })
        onMessageV.foreach(om => ctx.invokeCallback(om, List(onMessageCb)))
        PluginValue.unit
      case _ => PluginValue.unit
    })
    ctx.registerWsRoute(path, Nil, Nil, 0, 0, handler)

  /** Build an `McpClient` Value backed by `McpWsClient`. */
  def makeWsClient(url: String, timeoutMs: Long, ctx: PluginContext,
                   bearerToken: Option[String] = None): PluginValue =
    val client = new McpWsClient(url, timeoutMs, bearerToken)
    val initParams = ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "ssc-mcp-int", "version" -> "1.0.0")
    )
    client.request(McpProtocol.Method.Initialize, initParams) match
      case Left(e)  =>
        client.close()
        PluginError.raise(s"mcpConnect(Ws): initialize failed: ${e.message}")
      case Right(_) => client.notify("notifications/initialized", ujson.Obj())
    makeWsClientInstance(client, timeoutMs, ctx)

  /** Build the `srv` instance the user-side `mcpServer { srv => ... }`
   *  block receives.  Each method-field is a `NativeFnV` that returns
   *  either a `Unit` (no-arg lifecycle hooks) or another `NativeFnV`
   *  (the curried `tool(name)(handler)` / `resource(uri)(handler)` /
   *  `prompt(name)(handler)` two-step). */
  def makeServerInstance(builder: McpServerBuilder, ctx: PluginContext): PluginValue =
    def toolFn = PluginValue.nativeFn("McpServer.tool", {
      case List(Str(name)) =>
        PluginValue.nativeFn(s"McpServer.tool.$name", {
          case List(handler) =>
            registerTool(builder, name, None, ujson.Obj("type" -> "object"), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.tool(name)(handler)")
        })
      case List(Str(name), Str(desc)) =>
        PluginValue.nativeFn(s"McpServer.tool.$name", {
          case List(handler) =>
            registerTool(builder, name, Some(desc), ujson.Obj("type" -> "object"), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.tool(name, desc)(handler)")
        })
      // name + desc + trailing tool-annotation hints (readOnlyHint /
      // idempotentHint / destructiveHint / openWorldHint = Bool), then the
      // curried (handler) step. Hints are MCP metadata — accepted so the
      // two-clause protocol fires. (Surfacing them in advertised annotations
      // is a follow-up.)
      case Str(name) :: Str(desc) :: hints if hints.nonEmpty && hints.forall { case Bool(_) => true; case _ => false } =>
        PluginValue.nativeFn(s"McpServer.tool.$name", {
          case List(handler) =>
            registerTool(builder, name, Some(desc), ujson.Obj("type" -> "object"), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.tool(name, desc, hints...)(handler)")
        })
      case _ => PluginError.raise("srv.tool(name[, desc])(handler)")
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
    def toolWithSchemaFn = PluginValue.nativeFn("McpServer.toolWithSchema", {
      case List(Str(name), schemaV) =>
        PluginValue.nativeFn(s"McpServer.toolWithSchema.$name", {
          case List(handler) =>
            registerTool(builder, name, None, Mcp.valueToJson(schemaV), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.toolWithSchema(name, schema)(handler)")
        })
      case List(Str(name), Str(desc), schemaV) =>
        PluginValue.nativeFn(s"McpServer.toolWithSchema.$name", {
          case List(handler) =>
            registerTool(builder, name, Some(desc), Mcp.valueToJson(schemaV), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.toolWithSchema(name, desc, schema)(handler)")
        })
      case _ => PluginError.raise("srv.toolWithSchema(name[, desc], schema)(handler)")
    })
    def resourceFn = PluginValue.nativeFn("McpServer.resource", {
      case List(Str(uri)) =>
        PluginValue.nativeFn(s"McpServer.resource.$uri", {
          case List(handler) =>
            registerResource(builder, uri, None, None, handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.resource(uri)(handler)")
        })
      case List(Str(uri), Str(name)) =>
        PluginValue.nativeFn(s"McpServer.resource.$uri", {
          case List(handler) =>
            registerResource(builder, uri, Some(name), None, handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.resource(uri, name)(handler)")
        })
      case List(Str(uri), Str(name), Str(mimeType)) =>
        PluginValue.nativeFn(s"McpServer.resource.$uri", {
          case List(handler) =>
            registerResource(builder, uri, Some(name), Some(mimeType), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.resource(uri, name, mimeType)(handler)")
        })
      case _ => PluginError.raise("srv.resource(uri[, name[, mimeType]])(handler)")
    })
    // v1.17.x — URI-template resources.  `srv.resourceTemplate(template[,
    // name[, description[, mimeType]]])(handler)` registers a parameterized
    // resource like `file:///{path}`.  Listed via resources/templates/list;
    // a concrete `resources/read` URI matching the template (RFC 6570
    // simplified: `{name}` → non-slash segment) flows to `handler`.
    def resourceTemplateFn = PluginValue.nativeFn("McpServer.resourceTemplate", {
      case List(Str(tpl)) =>
        PluginValue.nativeFn(s"McpServer.resourceTemplate.$tpl", {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, None, None, None, handler, ctx); PluginValue.unit
          case _ => PluginError.raise("srv.resourceTemplate(template)(handler)")
        })
      case List(Str(tpl), Str(name)) =>
        PluginValue.nativeFn(s"McpServer.resourceTemplate.$tpl", {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, Some(name), None, None, handler, ctx); PluginValue.unit
          case _ => PluginError.raise("srv.resourceTemplate(template, name)(handler)")
        })
      case List(Str(tpl), Str(name), Str(desc)) =>
        PluginValue.nativeFn(s"McpServer.resourceTemplate.$tpl", {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, Some(name), Some(desc), None, handler, ctx); PluginValue.unit
          case _ => PluginError.raise("srv.resourceTemplate(template, name, description)(handler)")
        })
      case List(Str(tpl), Str(name), Str(desc), Str(mime)) =>
        PluginValue.nativeFn(s"McpServer.resourceTemplate.$tpl", {
          case List(handler) =>
            registerResourceTemplate(builder, tpl, Some(name), Some(desc), Some(mime), handler, ctx); PluginValue.unit
          case _ => PluginError.raise("srv.resourceTemplate(template, name, description, mimeType)(handler)")
        })
      case _ => PluginError.raise("srv.resourceTemplate(template[, name[, description[, mimeType]]])(handler)")
    })
    def promptFn = PluginValue.nativeFn("McpServer.prompt", {
      case List(Str(name)) =>
        PluginValue.nativeFn(s"McpServer.prompt.$name", {
          case List(handler) =>
            registerPrompt(builder, name, None, handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.prompt(name)(handler)")
        })
      case List(Str(name), Str(desc)) =>
        PluginValue.nativeFn(s"McpServer.prompt.$name", {
          case List(handler) =>
            registerPrompt(builder, name, Some(desc), handler, ctx)
            PluginValue.unit
          case _ => PluginError.raise("srv.prompt(name, desc)(handler)")
        })
      case _ => PluginError.raise("srv.prompt(name[, desc])(handler)")
    })
    def onConnFn = PluginValue.nativeFn("McpServer.onConnected", {
      case List(handler) =>
        builder.setOnConnected(() => { ctx.invokeCallback(handler, Nil); () })
        PluginValue.unit
      case _ => PluginError.raise("srv.onConnected(() => ...)")
    })
    def onDisconnFn = PluginValue.nativeFn("McpServer.onDisconnected", {
      case List(handler) =>
        builder.setOnDisconnected(() => { ctx.invokeCallback(handler, Nil); () })
        PluginValue.unit
      case _ => PluginError.raise("srv.onDisconnected(() => ...)")
    })
    // v1.17.x — resource subscriptions.  Hooks fire when the client
    // sends `resources/subscribe` / `resources/unsubscribe`; typical
    // wiring: spin up a file/DB watcher in the subscribe handler and
    // call `srv.notifyResourceUpdate(uri)` from its callback.
    def onResSubFn = PluginValue.nativeFn("McpServer.onResourceSubscribe", {
      case List(handler) =>
        builder.setOnResourceSubscribe { uri =>
          ctx.invokeCallback(handler, List(PluginValue.string(uri))); ()
        }
        PluginValue.unit
      case _ => PluginError.raise("srv.onResourceSubscribe(uri => ...)")
    })
    def onResUnsubFn = PluginValue.nativeFn("McpServer.onResourceUnsubscribe", {
      case List(handler) =>
        builder.setOnResourceUnsubscribe { uri =>
          ctx.invokeCallback(handler, List(PluginValue.string(uri))); ()
        }
        PluginValue.unit
      case _ => PluginError.raise("srv.onResourceUnsubscribe(uri => ...)")
    })
    def notifyResUpdateFn = PluginValue.nativeFn("McpServer.notifyResourceUpdate", {
      case List(Str(uri)) =>
        builder.notifyResourceUpdate(uri)
        PluginValue.unit
      case _ => PluginError.raise("srv.notifyResourceUpdate(uri)")
    })
    // v1.17.x — list_changed notifications.  Servers call these after
    // registering or un-registering a tool / resource / prompt at runtime
    // to nudge clients to re-fetch the catalog.  Matching capability
    // flags are advertised in initialize.
    def notifyToolsLCFn = PluginValue.nativeFn("McpServer.notifyToolsListChanged",
      { _ => builder.notifyToolsListChanged(); PluginValue.unit })
    def notifyResourcesLCFn = PluginValue.nativeFn("McpServer.notifyResourcesListChanged",
      { _ => builder.notifyResourcesListChanged(); PluginValue.unit })
    def notifyPromptsLCFn = PluginValue.nativeFn("McpServer.notifyPromptsListChanged",
      { _ => builder.notifyPromptsListChanged(); PluginValue.unit })
    // v1.17.x — cancellation.  Long-running tool handlers poll
    // `srv.isCancelled` at safe points and return early (typically with
    // an isError=true ToolResult) when the client has sent
    // notifications/cancelled for the current request.  MCP cancellation
    // is cooperative — honoring it is up to the handler.
    def isCancelledFn = PluginValue.nativeFn("McpServer.isCancelled",
      { _ => PluginValue.bool(builder.isCancelled) })
    // v1.17.x — progress notifications.  Inside a tool/resource/prompt
    // handler, `srv.notifyProgress(progress[, total])` emits a
    // `notifications/progress` frame with the current handler's
    // progressToken (captured from the client's `_meta.progressToken`
    // on the originating request).  No-op when the client didn't ask
    // for progress.
    def notifyProgressFn = PluginValue.nativeFn("McpServer.notifyProgress", {
      case List(Dbl(p)) => builder.notifyProgress(p); PluginValue.unit
      case List(Num(p))    => builder.notifyProgress(p.toDouble); PluginValue.unit
      case List(Dbl(p), Dbl(t)) => builder.notifyProgress(p, Some(t)); PluginValue.unit
      case List(Num(p),    Num(t))    => builder.notifyProgress(p.toDouble, Some(t.toDouble)); PluginValue.unit
      case List(Dbl(p), Num(t))    => builder.notifyProgress(p, Some(t.toDouble)); PluginValue.unit
      case List(Num(p),    Dbl(t)) => builder.notifyProgress(p.toDouble, Some(t)); PluginValue.unit
      case _ => PluginError.raise("srv.notifyProgress(progress[, total])")
    })
    // v1.17.x — logging.  `srv.log(level, data[, logger])` emits a
    // `notifications/message` frame iff the client-set log floor (via
    // logging/setLevel) is at or below the line's severity.  Default
    // floor is "info" — debug is silenced until the client opts in.
    def logFn = PluginValue.nativeFn("McpServer.log", {
      case List(Str(level), data) =>
        builder.log(level, Mcp.valueToJson(data)); PluginValue.unit
      case List(Str(level), data, Str(logger)) =>
        builder.log(level, Mcp.valueToJson(data), Some(logger)); PluginValue.unit
      case _ => PluginError.raise("srv.log(level, data[, logger])")
    })
    def currentLogLevelFn = PluginValue.nativeFn("McpServer.currentLogLevel",
      { _ => PluginValue.string(builder.loggingLevel) })
    // v1.17.x — server-initiated notifications.  `srv.notify(method, params)`
    // broadcasts a JSON-RPC notification frame to every currently-active
    // subscriber (Stdio/Spawn: one writer; Ws: one per connected client;
    // Http without SSE: no-op since no persistent push channel exists).
    def notifyFn = PluginValue.nativeFn("McpServer.notify", {
      case List(Str(method)) =>
        builder.notify(method, ujson.Obj())
        PluginValue.unit
      case List(Str(method), paramsV) =>
        builder.notify(method, Mcp.valueToJson(paramsV))
        PluginValue.unit
      case _ => PluginError.raise("srv.notify(method[, params])")
    })
    // v1.17.x — bidirectional sampling.  Server can call into a connected
    // client (typical use: `srv.request("sampling/createMessage", args)`
    // to ask the client's LLM to produce a completion).  Blocks until
    // the matching client.onRequest handler replies or `timeoutMs`
    // fires.  Returns the result Value on success, throws InterpretError
    // on timeout / error.
    def requestFn = PluginValue.nativeFn("McpServer.request", {
      case List(Str(method), paramsV, Num(timeoutMs)) =>
        builder.request(method, Mcp.valueToJson(paramsV), timeoutMs) match
          case Left(e)     => PluginError.raise(s"srv.request($method): ${e.message}")
          case Right(json) => Mcp.jsonToValue(json)
      case List(Str(method), paramsV) =>
        builder.request(method, Mcp.valueToJson(paramsV), 30_000L) match
          case Left(e)     => PluginError.raise(s"srv.request($method): ${e.message}")
          case Right(json) => Mcp.jsonToValue(json)
      case List(Str(method)) =>
        builder.request(method, ujson.Obj(), 30_000L) match
          case Left(e)     => PluginError.raise(s"srv.request($method): ${e.message}")
          case Right(json) => Mcp.jsonToValue(json)
      case _ => PluginError.raise("srv.request(method[, params[, timeoutMs]])")
    })
    // v1.17.x — roots (workspace info from the client).  Server pulls the
    // current root list on demand via `srv.listRoots()`; client pushes a
    // `notifications/roots/list_changed` when it changes, which we route
    // to the user's `srv.onRootsListChanged(...)` callback.  Returns a
    // List of Map(uri -> ..., name -> ...) for ergonomic destructuring
    // in user code.
    def listRootsFn = PluginValue.nativeFn("McpServer.listRoots", {
      case List(Num(timeoutMs)) =>
        builder.listRoots(timeoutMs) match
          case Left(e)      => PluginError.raise(s"srv.listRoots: ${e.message}")
          case Right(roots) => Mcp.rootsToValue(roots)
      case Nil =>
        builder.listRoots() match
          case Left(e)      => PluginError.raise(s"srv.listRoots: ${e.message}")
          case Right(roots) => Mcp.rootsToValue(roots)
      case _ => PluginError.raise("srv.listRoots([timeoutMs])")
    })
    def onRootsLCFn = PluginValue.nativeFn("McpServer.onRootsListChanged", {
      case List(handler) =>
        builder.setOnRootsListChanged(() => { ctx.invokeCallback(handler, Nil); () })
        PluginValue.unit
      case _ => PluginError.raise("srv.onRootsListChanged(() => ...)")
    })
    def clientSupportsRootsFn = PluginValue.nativeFn("McpServer.clientSupportsRoots",
      { _ => PluginValue.bool(builder.clientSupportsRoots) })
    // v1.17.x — elicitation.  `srv.elicit(message, schema[, timeoutMs])`
    // pops a prompt on the client side asking for user input matching
    // the supplied JSON Schema.  Returns an ElicitationResult instance:
    //   - Accept(content) → user filled in the form
    //   - Decline         → user clicked No
    //   - Cancel          → user dismissed the dialog
    // Treat the latter two as the safe "user didn't agree" branch.
    def elicitFn = PluginValue.nativeFn("McpServer.elicit", {
      case List(Str(message), schemaV, Num(timeoutMs)) =>
        builder.elicit(message, Mcp.valueToJson(schemaV), timeoutMs) match
          case Left(e)  => PluginError.raise(s"srv.elicit: ${e.message}")
          case Right(r) => Mcp.elicitationResultToValue(r)
      case List(Str(message), schemaV) =>
        builder.elicit(message, Mcp.valueToJson(schemaV)) match
          case Left(e)  => PluginError.raise(s"srv.elicit: ${e.message}")
          case Right(r) => Mcp.elicitationResultToValue(r)
      case _ => PluginError.raise("srv.elicit(message, schema[, timeoutMs])")
    })
    def clientSupportsElicitationFn =
      PluginValue.nativeFn("McpServer.clientSupportsElicitation",
        { _ => PluginValue.bool(builder.clientSupportsElicitation) })
    // v1.17.x — completion handlers.  The handler is a function
    // `String => List[String]` (current partial value → suggestions).
    // Two registration entry points for the two ref shapes the spec
    // supports: `ref/prompt` keyed by prompt name, `ref/resource` keyed
    // by URI-template string.  Spec caps results at 100; the wire-layer
    // applies the cap so user handlers can return as many as they want.
    def completionForPromptFn = PluginValue.nativeFn("McpServer.completionForPrompt",
      {
        case List(Str(promptName), Str(argName), handler) =>
          builder.completionForPrompt(promptName, argName, value =>
            ctx.invokeCallback(handler, List(PluginValue.string(value))) match
              case v if PluginValue.isRuntimeValue(v) => Mcp.valueToStringList(v)
              case _        => Nil
          )
          PluginValue.unit
        case _ => PluginError.raise("srv.completionForPrompt(promptName, argName, handler)")
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
    def setTokenValidatorFn = PluginValue.nativeFn("McpServer.setTokenValidator",
      {
        case List(handler) =>
          builder.setTokenValidator(Some(token =>
            ctx.invokeCallback(handler, List(PluginValue.string(token))) match
              case v if PluginValue.isRuntimeValue(v) => Mcp.valueToAuthResult(v)
              case _        => McpAuth.AuthResult.Invalid("invalid_token", "validator returned non-Value")
          ))
          PluginValue.unit
        case _ => PluginError.raise("srv.setTokenValidator(handler)")
      })
    def useHmacValidatorFn = PluginValue.nativeFn("McpServer.useHmacValidator",
      {
        case List(Str(secret)) =>
          builder.setTokenValidator(Some(McpAuth.hmacValidator(secret)))
          PluginValue.unit
        case _ => PluginError.raise("srv.useHmacValidator(secret)")
      })
    // v1.17.x — bridge to the standalone OAuth Authorization Server.
    // Takes the InstanceV produced by `oauth.authServer(...)` and wires
    // its token validator + protected-resource metadata into this MCP
    // server.  Resolves via OAuthBridge (shared with the oauth-plugin).
    def useAuthServerFn = PluginValue.nativeFn("McpServer.useAuthServer",
      {
        case List(asValue) =>
          (asValue match
            case Inst("AuthServer", fields) =>
              fields.get("_id").collect { case Str(id) => id }
                .flatMap(id => Option(OAuthBridge.authServers.get(id))
                  .collect { case as: scalascript.oauth.AuthServer => as })
            case _ => None
          ) match
            case Some(as) => builder.useAuthServer(as); PluginValue.unit
            case None     => PluginError.raise(
              "srv.useAuthServer: argument is not an AuthServer instance (use oauth.authServer(...))")
        case _ => PluginError.raise("srv.useAuthServer(authServer)")
      })
    def setAuthRealmFn = PluginValue.nativeFn("McpServer.setAuthRealm",
      {
        case List(Str(realm)) => builder.setAuthRealm(realm); PluginValue.unit
        case _ => PluginError.raise("srv.setAuthRealm(realm)")
      })
    def currentAuthFn = PluginValue.nativeFn("McpServer.currentAuth",
      { _ => Mcp.authClaimsToValueOpt(builder.currentAuth) })
    def authEnabledFn = PluginValue.nativeFn("McpServer.authEnabled",
      { _ => PluginValue.bool(builder.authEnabled) })
    def setPrmFn = PluginValue.nativeFn("McpServer.setProtectedResourceMetadata",
      {
        case List(metadataV) =>
          builder.setProtectedResourceMetadata(Mcp.valueToPrm(metadataV))
          PluginValue.unit
        case _ => PluginError.raise("srv.setProtectedResourceMetadata(metadata)")
      })
    def issueHmacTokenFn = PluginValue.nativeFn("McpServer.issueHmacToken",
      {
        case List(Str(secret), Str(subject), scopesV, Num(expSec)) =>
          PluginValue.string(McpAuth.issueHmacToken(secret, subject,
            Mcp.valueToStringList(scopesV).toSet, expSec))
        case _ => PluginError.raise("srv.issueHmacToken(secret, subject, scopes, expiresInSeconds)")
      })
    def setPageSizeFn = PluginValue.nativeFn("McpServer.setPageSize",
      {
        case List(Num(n)) => builder.setPageSize(n.toInt); PluginValue.unit
        case _ => PluginError.raise("srv.setPageSize(n)")
      })
    def currentPageSizeFn = PluginValue.nativeFn("McpServer.currentPageSize",
      { _ => PluginValue.int(builder.currentPageSize) })
    def completionForResourceFn = PluginValue.nativeFn("McpServer.completionForResource",
      {
        case List(Str(uriTemplate), Str(argName), handler) =>
          builder.completionForResource(uriTemplate, argName, value =>
            ctx.invokeCallback(handler, List(PluginValue.string(value))) match
              case v if PluginValue.isRuntimeValue(v) => Mcp.valueToStringList(v)
              case _        => Nil
          )
          PluginValue.unit
        case _ => PluginError.raise("srv.completionForResource(uriTemplate, argName, handler)")
      })
    PluginValue.instance("McpServer", Map(
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
    handler: PluginValue,
    ctx: PluginContext
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
    handler: PluginValue,
    ctx: PluginContext
  ): Unit =
    builder.resource(uri, name, mimeType, requestedUri =>
      val result = ctx.invokeCallback(handler, List(PluginValue.string(requestedUri)))
      valueToResourceResult(result)
    )

  private def registerResourceTemplate(
    builder:     McpServerBuilder,
    uriTemplate: String,
    name:        Option[String],
    description: Option[String],
    mimeType:    Option[String],
    handler: PluginValue,
    ctx: PluginContext
  ): Unit =
    builder.resourceTemplate(uriTemplate, name, description, mimeType, requestedUri =>
      val result = ctx.invokeCallback(handler, List(PluginValue.string(requestedUri)))
      valueToResourceResult(result)
    )

  private def registerPrompt(
    builder: McpServerBuilder,
    name:    String,
    desc:    Option[String],
    handler: PluginValue,
    ctx: PluginContext
  ): Unit =
    builder.prompt(name, desc, Nil, args =>
      val argsValue = mapToValue(args)
      val result    = ctx.invokeCallback(handler, List(argsValue))
      valueToPromptResult(result)
    )

  // ─── Value → JSON marshallers for handler results ──────────────────

  /** A user `Tool.text("hi")` evaluates to `PluginValue.instance("ToolResult",
   *  Map("content" -> ListV(...), "isError" -> BoolV(...)))` in the
   *  interpreter.  We flatten that into the `ToolHandlerResult` the core
   *  expects. */
  def valueToToolResult(v: Any): ToolHandlerResult = v match
    case Inst("ToolResult", fields) =>
      val items = fields.get("content").collect {
        case Lst(xs) => xs.map(contentValueToJson)
      }.getOrElse(Nil)
      val isErr = fields.get("isError").collect { case Bool(b) => b }.getOrElse(false)
      ToolHandlerResult(items, isErr)
    case Str(s) =>
      ToolHandlerResult(List(McpProtocol.textContent(s)), isError = false)
    case other =>
      ToolHandlerResult(List(McpProtocol.textContent(PluginValue.showAny(other.asInstanceOf[PluginValue]))), isError = false)

  def valueToResourceResult(v: Any): ResourceHandlerResult = v match
    case Inst("ResourceResult", fields) =>
      val uri = fields.get("uri").collect { case Str(s) => s }.getOrElse("")
      val contents = fields.get("contents").collect {
        case Lst(xs) => xs.map(contentValueToJson)
      }.getOrElse(Nil)
      ResourceHandlerResult(uri, contents)
    case other =>
      ResourceHandlerResult("", List(McpProtocol.textContent(PluginValue.showAny(other.asInstanceOf[PluginValue]))))

  def valueToPromptResult(v: Any): PromptHandlerResult = v match
    case Inst("PromptResult", fields) =>
      val msgs = fields.get("messages").collect {
        case Lst(xs) => xs.map(messageValueToJson)
      }.getOrElse(Nil)
      PromptHandlerResult(None, msgs)
    case _ =>
      PromptHandlerResult(None, Nil)

  /** `Content.Text(s)` / `Content.Image(data, mime)` / `Content.Resource(uri)`
   *  → MCP wire JSON object. */
  def contentValueToJson(v: PluginValue): ujson.Value = v match
    case Inst("Text", fields) =>
      val s = fields.get("text").collect { case Str(s) => s }.getOrElse("")
      McpProtocol.textContent(s)
    case Inst("Image", fields) =>
      val data = fields.get("data").collect { case Str(s) => s }.getOrElse("")
      val mime = fields.get("mimeType").collect { case Str(s) => s }.getOrElse("application/octet-stream")
      McpProtocol.imageContent(data, mime)
    case Inst("Resource", fields) =>
      val uri = fields.get("uri").collect { case Str(s) => s }.getOrElse("")
      McpProtocol.resourceContent(uri)
    case Str(s) => McpProtocol.textContent(s)
    case other            => McpProtocol.textContent(PluginValue.showAny(other))

  /** `Message(role, content)` → `{role: "user|assistant|system", content: {...}}` */
  def messageValueToJson(v: PluginValue): ujson.Value = v match
    case Inst("Message", fields) =>
      val role = fields.get("role").collect {
        case Inst("User", _)      => "user"
        case Inst("Assistant", _) => "assistant"
        case Inst("System", _)    => "system"
      }.getOrElse("user")
      val content = fields.get("content").map(contentValueToJson).getOrElse(McpProtocol.textContent(""))
      ujson.Obj("role" -> role, "content" -> content)
    case _ => ujson.Obj("role" -> "user", "content" -> McpProtocol.textContent(""))

  /** Lift `Map[String, Any]` (decoded by McpServerCore.jsonToScala) to a
   *  Value.MapV the handler closure sees as the `args` parameter. */
  def mapToValue(m: Map[String, Any]): PluginValue =
    PluginValue.mapOf(m.map { case (k, v) => PluginValue.string(k) -> anyToValue(v) })

  def anyToValue(a: Any): PluginValue = a match
    case null            => PluginValue.none
    case b: Boolean      => PluginValue.bool(b)
    case s: String       => PluginValue.string(s)
    case d: Double       => PluginValue.double(d)
    case i: Int          => PluginValue.int(i.toLong)
    case l: Long         => PluginValue.int(l)
    case xs: List[?]     => PluginValue.list(xs.map(anyToValue))
    case m: Map[?, ?]    => PluginValue.mapOf(m.iterator.map((k, v) => PluginValue.string(k.toString) -> anyToValue(v)).toMap)
    case other           => PluginValue.string(other.toString)

  // ─── Spawn client construction ─────────────────────────────────────

  /** Spawn `cmd args*` as a subprocess and return a Value.InstanceV
   *  exposing the McpClient API — listTools / callTool / etc. */
  def makeSpawnClient(cmd: String, cmdArgs: List[String], timeoutMs: Long, ctx: PluginContext): PluginValue =
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
        PluginError.raise(s"mcpConnect: initialize failed: ${e.message}")
      case Right(_) =>
        client.notify("notifications/initialized", ujson.Obj())

    makeClientInstance(client, proc, timeoutMs, ctx)

  private def makeClientInstance(
    client:    McpClientCore,
    proc:      Process,
    timeoutMs: Long,
    ctx: PluginContext
  ): PluginValue =
    val fields = mutable.LinkedHashMap.empty[String, PluginValue]

    fields("listTools") = PluginValue.nativeFn("McpClient.listTools", { _ =>
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listTools: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "tools", Mcp.toolDescriptorFromJson)
    })
    fields("listResources") = PluginValue.nativeFn("McpClient.listResources", { _ =>
      client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listResources: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "resources", Mcp.resourceDescriptorFromJson)
    })
    fields("listPrompts") = PluginValue.nativeFn("McpClient.listPrompts", { _ =>
      client.request(McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listPrompts: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "prompts", Mcp.promptDescriptorFromJson)
    })
    fields("callTool") = PluginValue.nativeFn("McpClient.callTool", {
      case List(Str(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.ToolsCall, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"callTool: ${e.message}")
          case Right(json) => Mcp.toolResultFromJson(json)
      case _ => PluginError.raise("client.callTool(name, args)")
    })
    fields("readResource") = PluginValue.nativeFn("McpClient.readResource", {
      case List(Str(uri)) =>
        val params = ujson.Obj("uri" -> uri)
        client.request(McpProtocol.Method.ResourcesRead, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"readResource: ${e.message}")
          case Right(json) => Mcp.resourceResultFromJson(json, uri)
      case _ => PluginError.raise("client.readResource(uri)")
    })
    fields("getPrompt") = PluginValue.nativeFn("McpClient.getPrompt", {
      case List(Str(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.PromptsGet, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"getPrompt: ${e.message}")
          case Right(json) => Mcp.promptResultFromJson(json)
      case _ => PluginError.raise("client.getPrompt(name, args)")
    })
    fields("close") = PluginValue.nativeFn("McpClient.close", { _ =>
      client.close()
      try proc.destroy()    catch case _: Throwable => ()
      PluginValue.unit
    })
    fields("isClosed") = PluginValue.nativeFn("McpClient.isClosed", { _ =>
      PluginValue.bool(client.isClosed)
    })
    // v1.17.x — server→client notification subscription.  Handler receives
    // (method: String, params: PluginValue).  Replaces any previously-registered
    // handler.  Stdio/Spawn transports deliver notifications natively
    // (frames just arrive on the same stdout the reader thread pulls).
    fields("onNotification") = PluginValue.nativeFn("McpClient.onNotification", {
      case List(handler) =>
        client.setNotificationHandler { (method, params) =>
          ctx.invokeCallback(handler, List(PluginValue.string(method), Mcp.jsonToValue(params)))
        }
        PluginValue.unit
      case _ => PluginError.raise("client.onNotification(handler)")
    })
    // v1.17.x — bidirectional sampling.  Handler returns the result Value;
    // exceptions become JSON-RPC error responses sent back via the writer
    // so the server-side pending map unblocks.
    fields("onRequest") = PluginValue.nativeFn("McpClient.onRequest", {
      case List(handler) =>
        client.setRequestHandler { (method, params) =>
          ctx.invokeCallback(handler, List(PluginValue.string(method), Mcp.jsonToValue(params))) match
            case v if PluginValue.isRuntimeValue(v) => Mcp.valueToJson(v)
            case other    => ujson.Str(String.valueOf(other))
        }
        PluginValue.unit
      case _ => PluginError.raise("client.onRequest(handler)")
    })
    PluginValue.instance("McpClient", fields.toMap)

  /** Same shape as `makeClientInstance` but routes through `McpHttpClient`
   *  instead of the stdio-backed `McpClientCore`.  Code duplication is
   *  intentional: both clients expose identical method surfaces but the
   *  request bodies differ in transport semantics (stdio is async with a
   *  pending-id table; HTTP is synchronous request/response). */
  def makeHttpClientInstance(client: McpHttpClient, timeoutMs: Long, ctx: PluginContext): PluginValue =
    val fields = mutable.LinkedHashMap.empty[String, PluginValue]
    fields("listTools") = PluginValue.nativeFn("McpClient.listTools", { _ =>
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listTools: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "tools", Mcp.toolDescriptorFromJson)
    })
    fields("listResources") = PluginValue.nativeFn("McpClient.listResources", { _ =>
      client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listResources: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "resources", Mcp.resourceDescriptorFromJson)
    })
    fields("listPrompts") = PluginValue.nativeFn("McpClient.listPrompts", { _ =>
      client.request(McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listPrompts: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "prompts", Mcp.promptDescriptorFromJson)
    })
    fields("callTool") = PluginValue.nativeFn("McpClient.callTool", {
      case List(Str(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.ToolsCall, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"callTool: ${e.message}")
          case Right(json) => Mcp.toolResultFromJson(json)
      case _ => PluginError.raise("client.callTool(name, args)")
    })
    fields("readResource") = PluginValue.nativeFn("McpClient.readResource", {
      case List(Str(uri)) =>
        val params = ujson.Obj("uri" -> uri)
        client.request(McpProtocol.Method.ResourcesRead, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"readResource: ${e.message}")
          case Right(json) => Mcp.resourceResultFromJson(json, uri)
      case _ => PluginError.raise("client.readResource(uri)")
    })
    fields("getPrompt") = PluginValue.nativeFn("McpClient.getPrompt", {
      case List(Str(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.PromptsGet, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"getPrompt: ${e.message}")
          case Right(json) => Mcp.promptResultFromJson(json)
      case _ => PluginError.raise("client.getPrompt(name, args)")
    })
    fields("close") = PluginValue.nativeFn("McpClient.close", { _ =>
      client.close(); PluginValue.unit
    })
    fields("isClosed") = PluginValue.nativeFn("McpClient.isClosed", { _ =>
      PluginValue.bool(client.isClosed)
    })
    // HTTP push via SSE GET `/events` — the client opens a daemon reader
    // thread that parses `data: <json>\n\n` frames and dispatches them
    // as notifications.  The server side (installHttpRoute) registers the
    // matching `/events` GET endpoint when `serveMcp(Transport.Http(...))`
    // is called.
    fields("onNotification") = PluginValue.nativeFn("McpClient.onNotification", {
      case List(handler) =>
        client.setNotificationHandler { (method, params) =>
          ctx.invokeCallback(handler, List(PluginValue.string(method), Mcp.jsonToValue(params)))
        }
        PluginValue.unit
      case _ => PluginError.raise("client.onNotification(handler)")
    })
    // Bidirectional sampling over HTTP: the SSE GET stream now also
    // delivers Request frames; McpHttpClient.setRequestHandler runs
    // the handler and POSTs the JSON-RPC Response back to the same
    // `/mcp` URL — handleHttpRequest server-side routes it through
    // builder.routeInboundResponse so the broadcaster's pending map
    // unblocks.  Same API shape as the Stdio/Spawn/Ws paths.
    fields("onRequest") = PluginValue.nativeFn("McpClient.onRequest", {
      case List(handler) =>
        client.setRequestHandler { (method, params) =>
          ctx.invokeCallback(handler, List(PluginValue.string(method), Mcp.jsonToValue(params))) match
            case v if PluginValue.isRuntimeValue(v) => Mcp.valueToJson(v)
            case other    => ujson.Str(String.valueOf(other))
        }
        PluginValue.unit
      case _ => PluginError.raise("client.onRequest(handler)")
    })
    PluginValue.instance("McpClient", fields.toMap)

  /** Same shape as `makeHttpClientInstance` / `makeClientInstance` but
   *  routes through `McpWsClient`.  Persistent WS connection — the
   *  pending-request map handles id correlation server→client; the
   *  same channel delivers server→client notifications. */
  def makeWsClientInstance(client: McpWsClient, timeoutMs: Long, ctx: PluginContext): PluginValue =
    val fields = mutable.LinkedHashMap.empty[String, PluginValue]
    fields("listTools") = PluginValue.nativeFn("McpClient.listTools", { _ =>
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listTools: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "tools", Mcp.toolDescriptorFromJson)
    })
    fields("listResources") = PluginValue.nativeFn("McpClient.listResources", { _ =>
      client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listResources: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "resources", Mcp.resourceDescriptorFromJson)
    })
    fields("listPrompts") = PluginValue.nativeFn("McpClient.listPrompts", { _ =>
      client.request(McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs) match
        case Left(e)     => PluginError.raise(s"listPrompts: ${e.message}")
        case Right(json) => Mcp.descriptorsListFromJson(json, "prompts", Mcp.promptDescriptorFromJson)
    })
    fields("callTool") = PluginValue.nativeFn("McpClient.callTool", {
      case List(Str(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.ToolsCall, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"callTool: ${e.message}")
          case Right(json) => Mcp.toolResultFromJson(json)
      case _ => PluginError.raise("client.callTool(name, args)")
    })
    fields("readResource") = PluginValue.nativeFn("McpClient.readResource", {
      case List(Str(uri)) =>
        val params = ujson.Obj("uri" -> uri)
        client.request(McpProtocol.Method.ResourcesRead, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"readResource: ${e.message}")
          case Right(json) => Mcp.resourceResultFromJson(json, uri)
      case _ => PluginError.raise("client.readResource(uri)")
    })
    fields("getPrompt") = PluginValue.nativeFn("McpClient.getPrompt", {
      case List(Str(name), argsV) =>
        val params = ujson.Obj("name" -> name, "arguments" -> Mcp.valueToJson(argsV))
        client.request(McpProtocol.Method.PromptsGet, params, timeoutMs) match
          case Left(e)     => PluginError.raise(s"getPrompt: ${e.message}")
          case Right(json) => Mcp.promptResultFromJson(json)
      case _ => PluginError.raise("client.getPrompt(name, args)")
    })
    fields("close") = PluginValue.nativeFn("McpClient.close", { _ =>
      client.close(); PluginValue.unit
    })
    fields("isClosed") = PluginValue.nativeFn("McpClient.isClosed", { _ =>
      PluginValue.bool(client.isClosed)
    })
    // Same notification-subscription mechanism as the Spawn/Stdio path:
    // WS is a persistent bidirectional channel, server-initiated frames
    // dispatch through the reader thread.
    fields("onNotification") = PluginValue.nativeFn("McpClient.onNotification", {
      case List(handler) =>
        client.setNotificationHandler { (method, params) =>
          ctx.invokeCallback(handler, List(PluginValue.string(method), Mcp.jsonToValue(params)))
        }
        PluginValue.unit
      case _ => PluginError.raise("client.onNotification(handler)")
    })
    fields("onRequest") = PluginValue.nativeFn("McpClient.onRequest", {
      case List(handler) =>
        client.setRequestHandler { (method, params) =>
          ctx.invokeCallback(handler, List(PluginValue.string(method), Mcp.jsonToValue(params))) match
            case v if PluginValue.isRuntimeValue(v) => Mcp.valueToJson(v)
            case other    => ujson.Str(String.valueOf(other))
        }
        PluginValue.unit
      case _ => PluginError.raise("client.onRequest(handler)")
    })
    PluginValue.instance("McpClient", fields.toMap)

  // ─── JSON → Value adapters for client return values ────────────────

  def descriptorsListFromJson(json: ujson.Value, key: String, mk: ujson.Value => PluginValue): PluginValue =
    val list = json.objOpt.flatMap(_.get(key)).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    PluginValue.list(list.map(mk))

  def toolDescriptorFromJson(v: ujson.Value): PluginValue =
    val name   = v.objOpt.flatMap(_.get("name").flatMap(_.strOpt)).getOrElse("")
    val desc   = v.objOpt.flatMap(_.get("description").flatMap(_.strOpt)).getOrElse("")
    val schema = v.objOpt.flatMap(_.get("inputSchema")).getOrElse(ujson.Obj())
    PluginValue.instance("ToolDescriptor", Map(
      "name"        -> PluginValue.string(name),
      "description" -> PluginValue.string(desc),
      "schema"      -> jsonToValue(schema)
    ))

  def resourceDescriptorFromJson(v: ujson.Value): PluginValue =
    val uri  = v.objOpt.flatMap(_.get("uri").flatMap(_.strOpt)).getOrElse("")
    val name = v.objOpt.flatMap(_.get("name").flatMap(_.strOpt)).getOrElse("")
    val mime = v.objOpt.flatMap(_.get("mimeType").flatMap(_.strOpt)).getOrElse("")
    PluginValue.instance("ResourceDescriptor", Map(
      "uri"      -> PluginValue.string(uri),
      "name"     -> PluginValue.string(name),
      "mimeType" -> PluginValue.string(mime)
    ))

  def promptDescriptorFromJson(v: ujson.Value): PluginValue =
    val name = v.objOpt.flatMap(_.get("name").flatMap(_.strOpt)).getOrElse("")
    val desc = v.objOpt.flatMap(_.get("description").flatMap(_.strOpt)).getOrElse("")
    PluginValue.instance("PromptDescriptor", Map(
      "name"        -> PluginValue.string(name),
      "description" -> PluginValue.string(desc),
      "args"        -> PluginValue.list(Nil)
    ))

  def toolResultFromJson(v: ujson.Value): PluginValue =
    val items = v.objOpt.flatMap(_.get("content")).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    val isErr = v.objOpt.flatMap(_.get("isError")).flatMap(_.boolOpt).getOrElse(false)
    PluginValue.instance("ToolResult", Map(
      "content" -> PluginValue.list(items.map(contentJsonToValue)),
      "isError" -> PluginValue.bool(isErr)
    ))

  def resourceResultFromJson(v: ujson.Value, uri: String): PluginValue =
    val items = v.objOpt.flatMap(_.get("contents")).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    PluginValue.instance("ResourceResult", Map(
      "uri"      -> PluginValue.string(uri),
      "contents" -> PluginValue.list(items.map(contentJsonToValue))
    ))

  def promptResultFromJson(v: ujson.Value): PluginValue =
    val msgs = v.objOpt.flatMap(_.get("messages")).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    PluginValue.instance("PromptResult", Map(
      "messages" -> PluginValue.list(msgs.map(messageJsonToValue))
    ))

  def contentJsonToValue(v: ujson.Value): PluginValue =
    v.objOpt.flatMap(_.get("type").flatMap(_.strOpt)) match
      case Some("text") =>
        val s = v.objOpt.flatMap(_.get("text").flatMap(_.strOpt)).getOrElse("")
        PluginValue.instance("Text", Map("text" -> PluginValue.string(s)))
      case Some("image") =>
        val data = v.objOpt.flatMap(_.get("data").flatMap(_.strOpt)).getOrElse("")
        val mime = v.objOpt.flatMap(_.get("mimeType").flatMap(_.strOpt)).getOrElse("")
        PluginValue.instance("Image", Map("data" -> PluginValue.string(data), "mimeType" -> PluginValue.string(mime)))
      case _ =>
        PluginValue.instance("Text", Map("text" -> PluginValue.string(v.render())))

  def messageJsonToValue(v: ujson.Value): PluginValue =
    val role = v.objOpt.flatMap(_.get("role").flatMap(_.strOpt)).getOrElse("user")
    val roleVal = role match
      case "assistant" => PluginValue.instance("Assistant", Map.empty)
      case "system"    => PluginValue.instance("System",    Map.empty)
      case _           => PluginValue.instance("User",      Map.empty)
    val content = v.objOpt.flatMap(_.get("content")).map(contentJsonToValue).getOrElse(
      PluginValue.instance("Text", Map("text" -> PluginValue.string("")))
    )
    PluginValue.instance("Message", Map("role" -> roleVal, "content" -> content))

  /** v1.17.x — adapt a typed `Root` list into a `List[InstanceV]` so user
   *  scripts can pattern-match on `root.uri` / `root.name`.  `name` is
   *  modelled as `Option[String]` (None when the client didn't supply one). */
  def rootsToValue(roots: List[McpProtocol.Root]): PluginValue =
    PluginValue.list(roots.map { r =>
      PluginValue.instance("Root", Map(
        "uri"  -> PluginValue.string(r.uri),
        "name" -> PluginValue.option(r.name.map(s => PluginValue.string(s)))
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
  def valueToStringList(v: Any): List[String] = v match
    case Lst(xs) => xs.map {
      case Str(s) => s
      case other            => PluginValue.showAny(other)
    }
    case _ => Nil

  /** v1.17.x — auth helpers: adapt to/from the user-facing `AuthResult`
   *  / `AuthClaims` / `ProtectedResourceMetadata` shapes.
   *
   *  AuthResult discriminator:
   *    InstanceV("Valid",   { subject: String, scopes: List[String], extra: Map })
   *    InstanceV("Invalid", { code: String, description: String })
   *  Anything else collapses to Invalid("invalid_token", ...). */
  def valueToAuthResult(v: Any): McpAuth.AuthResult = v match
    case Inst("Valid", fs) =>
      val sub = fs.get("subject").collect { case Str(s) => s }.getOrElse("")
      val scopes = fs.get("scopes").map(valueToStringList).getOrElse(Nil).toSet
      val extra  = fs.get("extra").map(valueToJson).getOrElse(ujson.Obj())
      McpAuth.AuthResult.Valid(McpAuth.AuthClaims(sub, scopes, extra))
    case Inst("Invalid", fs) =>
      val code  = fs.get("code").collect { case Str(s) => s }.getOrElse("invalid_token")
      val descr = fs.get("description").collect { case Str(s) => s }.getOrElse("")
      McpAuth.AuthResult.Invalid(code, descr)
    case _ =>
      McpAuth.AuthResult.Invalid("invalid_token", s"validator returned unexpected: ${PluginValue.showAny(v)}")

  def authClaimsToValueOpt(c: Option[McpAuth.AuthClaims]): PluginValue = c match
    case None => PluginValue.none
    case Some(claims) =>
      PluginValue.some(PluginValue.instance("AuthClaims", Map(
        "subject" -> PluginValue.string(claims.subject),
        "scopes"  -> PluginValue.list(claims.scopes.toList.sorted.map(s => PluginValue.string(s))),
        "extra"   -> jsonToValue(claims.extra)
      )))

  /** Decode a user-supplied `Map` / `InstanceV` describing the metadata
   *  document.  Missing fields fall to spec defaults. */
  def valueToPrm(v: PluginValue): McpAuth.ProtectedResourceMetadata =
    val obj = v match
      case Inst(_, fs) => fs
      case MapVal(m)          => m.iterator.collect {
        case (Str(k), vv) => k -> vv
      }.toMap
      case _ => Map.empty[String, PluginValue]
    val resource = obj.get("resource").collect { case Str(s) => s }.getOrElse("")
    val authSrvs = obj.get("authorizationServers").map(valueToStringList).getOrElse(Nil)
    val scopes   = obj.get("scopesSupported").map(valueToStringList).getOrElse(Nil)
    val doc      = obj.get("resourceDocumentation").collect { case Str(s) => s }
    McpAuth.ProtectedResourceMetadata(
      resource              = resource,
      authorizationServers  = authSrvs,
      scopesSupported       = scopes,
      resourceDocumentation = doc
    )

  def elicitationResultToValue(r: McpProtocol.ElicitationResult): PluginValue = r match
    case McpProtocol.ElicitationResult.Accept(content) =>
      PluginValue.instance("ElicitationResult", Map(
        "action"  -> PluginValue.string("accept"),
        "content" -> PluginValue.some(jsonToValue(content))
      ))
    case McpProtocol.ElicitationResult.Decline =>
      PluginValue.instance("ElicitationResult", Map(
        "action"  -> PluginValue.string("decline"),
        "content" -> PluginValue.none
      ))
    case McpProtocol.ElicitationResult.Cancel =>
      PluginValue.instance("ElicitationResult", Map(
        "action"  -> PluginValue.string("cancel"),
        "content" -> PluginValue.none
      ))

  def jsonToValue(v: ujson.Value): PluginValue = v match
    case ujson.Null    => PluginValue.none
    case ujson.True    => PluginValue.bool(true)
    case ujson.False   => PluginValue.bool(false)
    case ujson.Str(s)  => PluginValue.string(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => PluginValue.int(n.toLong)
    case ujson.Num(n)  => PluginValue.double(n)
    case ujson.Arr(xs) => PluginValue.list(xs.iterator.map(jsonToValue).toList)
    case ujson.Obj(kv) => PluginValue.mapOf(kv.iterator.map((k, v) => PluginValue.string(k) -> jsonToValue(v)).toMap)

  def valueToJson(v: Any): ujson.Value = v match
    case PluginValue.none    => ujson.Null
    case Opt(Some(inner)) => valueToJson(inner)
    case Bool(b)         => if b then ujson.True else ujson.False
    case Str(s)       => ujson.Str(s)
    case Num(i)          => ujson.Num(i.toDouble)
    case Dbl(d)       => ujson.Num(d)
    case Lst(xs)        => ujson.Arr.from(xs.map(valueToJson))
    case MapVal(m)          =>
      val obj = ujson.Obj()
      m.foreach { (k, v) =>
        val key = k match { case Str(s) => s; case other => PluginValue.showAny(other) }
        obj(key) = valueToJson(v)
      }
      obj
    case other                  => ujson.Str(PluginValue.showAny(other))

  /** Small helper to convert a Scala List[String] to a java.util.List[String]
   *  for ProcessBuilder without pulling in scala.jdk imports here. */
  extension (xs: List[String])
    def asJavaList: java.util.List[String] =
      val al = java.util.ArrayList[String](xs.size)
      xs.foreach(al.add); al
