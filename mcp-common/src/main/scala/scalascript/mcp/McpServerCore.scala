package scalascript.mcp

import scala.collection.mutable

/** Transport-agnostic MCP server: holds the tool / resource / prompt
 *  registry and a dispatch loop that pumps lines from a reader and
 *  pushes responses to a writer.
 *
 *  The reader returns `Some(line)` per inbound JSON-RPC frame and
 *  `None` on EOF (server shuts down).  The writer takes one wire frame
 *  at a time (including its trailing `\n`); concurrent writes are the
 *  transport's problem to serialise — Phase 1 stdio writes from a
 *  single thread.
 *
 *  Handlers receive args as a `Map[String, Any]` (matching the
 *  `std/mcp/server.ssc` user-facing type) and return one of the result
 *  envelope shapes — left abstract here as `ujson.Value` so the
 *  intrinsic layer can adapt from interpreter values without this file
 *  depending on `scalascript.interpreter.Value`. */
class McpServerBuilder:
  // Tool registrations keyed by tool name.
  private[mcp] val tools = mutable.LinkedHashMap.empty[String, ToolRegistration]
  // Resource registrations keyed by exact uri.
  private[mcp] val resources = mutable.LinkedHashMap.empty[String, ResourceRegistration]
  // v1.17.x — URI-template resource registrations.  When `resources/read`
  // is called with a uri that doesn't exact-match `resources`, the
  // dispatcher iterates `resourceTemplates` and runs the handler of the
  // first template that matches (simplified RFC 6570: `{name}` placeholders
  // become `([^/]+)` segments).  Listed via `resources/templates/list`.
  private[mcp] val resourceTemplates =
    mutable.LinkedHashMap.empty[String, ResourceTemplateRegistration]
  // Prompt registrations keyed by name.
  private[mcp] val prompts = mutable.LinkedHashMap.empty[String, PromptRegistration]
  // Lifecycle hooks — fired after the `initialize` handshake / on EOF.
  private[mcp] var onConnected:    () => Unit = () => ()
  private[mcp] var onDisconnected: () => Unit = () => ()
  // v1.17.x — resource subscription hooks.  Fired when a client sends
  // `resources/subscribe` or `resources/unsubscribe`.  Default no-ops:
  // the server still accepts the request and replies success; the user
  // wires watchers (file system, DB triggers, …) inside the hook and
  // pushes `notifications/resources/updated` via `notifyResourceUpdate`
  // when content changes.  Tracks subscribed URIs server-side so
  // `notifyResourceUpdate(uri)` can no-op when nobody subscribed.
  private[mcp] var onResourceSubscribe:   String => Unit = _ => ()
  private[mcp] var onResourceUnsubscribe: String => Unit = _ => ()
  private[mcp] val subscribedResources =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  // v1.17.x — cancellation.  Each in-flight request (tools/call, etc.)
  // registers its id → AtomicBoolean(false) in `inflightCancel` for
  // the duration of handler execution.  `notifications/cancelled` with
  // `params.requestId` flips the matching flag to true; the user's
  // handler can poll `srv.isCancelled` (which reads currentReqIdTL +
  // looks up the flag) to bail out cooperatively.  MCP cancellation
  // is cooperative — the server may honour or ignore.
  private[mcp] val inflightCancel =
    java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean]()
  private[mcp] val currentReqIdTL: ThreadLocal[java.lang.Long] = new ThreadLocal[java.lang.Long]()

  // v1.17.x — progress notifications.  Clients opt in by including
  // `_meta.progressToken` on the original request; the server captures
  // it before invoking the handler.  `srv.notifyProgress(p[, total])`
  // reads this thread-local to assemble the matching
  // `notifications/progress` frame.  Null means the client didn't ask
  // for progress — notifyProgress is a no-op in that case.
  private[mcp] val currentProgressTokenTL: ThreadLocal[ujson.Value] = new ThreadLocal[ujson.Value]()

  // v1.17.x — logging.  Client sets the floor via `logging/setLevel`;
  // server emits `notifications/message` for log lines at or above that
  // level (syslog ranking).  Default "info" — `debug` is silenced until
  // the client opts in.
  @volatile private[mcp] var currentLogLevel: String = "info"

  /** Current log level — set via `logging/setLevel` by the client, or
   *  the default "info" until the client overrides. */
  def loggingLevel: String = currentLogLevel

  // v1.17.x — roots (workspace info from the client).  The server can
  // pull the current roots on demand via `srv.listRoots(...)` and react
  // to `notifications/roots/list_changed` via the registered callback.
  // `clientCapabilities` is captured from the inbound `initialize` so
  // user code can check `srv.clientSupportsRoots` before calling
  // `listRoots(...)` — saves a round-trip for clients that don't
  // advertise the capability at all.
  @volatile private[mcp] var clientCapabilities: ujson.Value = ujson.Obj()
  private[mcp] var onRootsListChanged: () => Unit = () => ()

  /** Register a callback fired when the connected client emits
   *  `notifications/roots/list_changed`.  Typical use: re-fetch via
   *  `srv.listRoots(...)` and update any cached workspace state. */
  def setOnRootsListChanged(f: () => Unit): Unit = onRootsListChanged = f

  /** Snapshot of the `capabilities` object the client sent on
   *  `initialize`.  Use `clientSupportsRoots` for the common case. */
  def currentClientCapabilities: ujson.Value = clientCapabilities

  /** True iff the connected client advertised `roots` in its initialize
   *  capabilities.  Calling `listRoots(...)` on a client that didn't
   *  advertise the capability is allowed but typically yields
   *  MethodNotFound. */
  def clientSupportsRoots: Boolean =
    try clientCapabilities.obj.contains("roots")
    catch case _: Throwable => false

  /** Send `roots/list` to the connected client and parse the typed
   *  response.  Returns `Left(error)` on timeout / MethodNotFound /
   *  client error; `Right(roots)` on success.  Convenience over the
   *  generic `request(...)` mechanism so user scripts don't have to
   *  hand-parse the response shape. */
  def listRoots(timeoutMs: Long = 5000L): Either[JsonRpc.Error, List[McpProtocol.Root]] =
    request(McpProtocol.Method.RootsList, ujson.Obj(), timeoutMs) match
      case Left(e)   => Left(e)
      case Right(js) => Right(McpProtocol.parseRootsListResult(js))

  /** Returns true iff the currently-executing handler's request has been
   *  cancelled via `notifications/cancelled`.  Read this at safe points
   *  inside long-running tool handlers and return early when set. */
  def isCancelled: Boolean =
    val id = currentReqIdTL.get()
    if id == null then false
    else
      val flag = inflightCancel.get(id.longValue())
      flag != null && flag.get()

  /** Push a `notifications/progress` frame for the current handler's
   *  progressToken.  No-op when the client didn't include
   *  `_meta.progressToken` on the originating request.  `progress` is a
   *  Double (the spec allows either int or fraction); `total` is the
   *  optional grand total. */
  def notifyProgress(progress: Double, total: Option[Double] = None): Unit =
    val tok = currentProgressTokenTL.get()
    if tok != null then
      val payload = ujson.Obj(
        "progressToken" -> tok,
        "progress"      -> ujson.Num(progress)
      )
      total.foreach(t => payload("total") = ujson.Num(t))
      notify(McpProtocol.Method.Progress, payload)

  /** Push a `notifications/message` log line.  Filtered against the
   *  client-supplied level floor (via `logging/setLevel`) — lines below
   *  the floor are silently dropped.  `data` is an arbitrary JSON value
   *  describing the log payload (string, structured object, etc.).
   *  Unknown levels fall through unfiltered. */
  def log(level: String, data: ujson.Value, logger: Option[String] = None): Unit =
    val floor = McpProtocol.logLevelRank(currentLogLevel)
    val rank  = McpProtocol.logLevelRank(level)
    if rank >= 0 && floor >= 0 && rank < floor then return
    val payload = ujson.Obj("level" -> level, "data" -> data)
    logger.foreach(l => payload("logger") = l)
    notify(McpProtocol.Method.LogMessage, payload)

  /** Active server→client subscribers — one per persistent connection.
   *  Stdio/Spawn keep exactly one (the writer captured by `serve()`); Ws
   *  adds/removes one per WS connection.  HTTP request/response transport
   *  doesn't add any (no persistent channel — push delivery for Http is
   *  deferred to the SSE GET stream variant). */
  private val subscribers =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String => Unit]()

  /** Wire a new push subscriber for the lifetime of one transport
   *  connection.  Returns a thunk the transport calls on disconnect
   *  to remove its writer from the broadcast set.
   *  Best-effort: writer-side exceptions are swallowed so a single
   *  dead connection doesn't tear the whole broadcaster down. */
  def addSubscriber(write: String => Unit): () => Unit =
    val wrap: String => Unit = s => try write(s) catch case _: Throwable => ()
    subscribers.add(wrap)
    () => { subscribers.remove(wrap); () }

  /** Broadcast a server-initiated notification to every active
   *  subscriber.  No id (notifications never expect a reply).  Frames
   *  are JSON-RPC notifications terminated by a newline — matches the
   *  framing every reader (`McpClientCore.dispatchResponse` /
   *  `McpWsClient.dispatchInboundLine`) already understands. */
  def notify(method: String, params: ujson.Value): Unit =
    val frame = JsonRpc.encodeNotification(method, params)
    subscribers.forEach { s => s(frame) }

  // v1.17.x — bidirectional sampling.  Server can issue a JSON-RPC
  // Request to a client (e.g. `sampling/createMessage`); the
  // client's onRequest handler computes a result and ships it back
  // as a Response frame on the same writer.  We track outstanding
  // ids in `serverPending` and the per-transport `dispatch...`
  // helpers route inbound Response frames into the matching queue.
  private val nextRequestId = java.util.concurrent.atomic.AtomicLong(1L)
  private val serverPending =
    java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.LinkedBlockingQueue[JsonRpc.Message.Response]]()

  /** Sends a server-initiated request to every connected subscriber
   *  and returns the first matching response.  Useful for single-
   *  client deployments (Stdio / Spawn / single-Ws) where "broadcast"
   *  has only one recipient anyway; for multi-client Ws the first
   *  response wins (semantically: any one of the clients can fulfill
   *  the request).  Timeout fires if no client replies in time. */
  def request(method: String, params: ujson.Value, timeoutMs: Long): Either[JsonRpc.Error, ujson.Value] =
    if subscribers.isEmpty then
      return Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
        "srv.request: no active client subscribers"))
    val id    = nextRequestId.getAndIncrement()
    val q     = java.util.concurrent.LinkedBlockingQueue[JsonRpc.Message.Response]()
    serverPending.put(id, q)
    try
      val frame = JsonRpc.encodeRequest(method, params, id)
      subscribers.forEach { s => s(frame) }
      val resp = q.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      if resp == null then
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
          s"srv.request '$method' timed out after ${timeoutMs}ms"))
      else
        resp.error match
          case Some(e) => Left(e)
          case None    => Right(resp.result.getOrElse(ujson.Null))
    finally serverPending.remove(id)

  /** Per-transport dispatchers call this when they parse an inbound
   *  Response frame so the matching `request(...)` caller unblocks.
   *  Returns true when the response was routed; false for stray
   *  responses (e.g. id we don't recognise — typically a client
   *  replying after our timeout fired and removed the entry). */
  def routeInboundResponse(resp: JsonRpc.Message.Response): Boolean =
    resp.id.numOpt.map(_.toLong) match
      case Some(id) =>
        val q = serverPending.get(id)
        if q != null then { q.offer(resp); true } else false
      case None => false

  def tool(
    name:        String,
    description: Option[String],
    inputSchema: ujson.Value,
    handler:     Map[String, Any] => ToolHandlerResult
  ): Unit =
    tools(name) = ToolRegistration(name, description, inputSchema, handler)

  def resource(
    uri:      String,
    name:     Option[String],
    mimeType: Option[String],
    handler:  String => ResourceHandlerResult
  ): Unit =
    resources(uri) = ResourceRegistration(uri, name, mimeType, handler)

  /** Register a URI-template resource.  Concrete `resources/read` URIs
   *  matching the template (simplified RFC 6570: `{name}` placeholders
   *  match any non-slash segment) flow into `handler`.  Listed via
   *  `resources/templates/list`. */
  def resourceTemplate(
    uriTemplate: String,
    name:        Option[String],
    description: Option[String],
    mimeType:    Option[String],
    handler:     String => ResourceHandlerResult
  ): Unit =
    resourceTemplates(uriTemplate) =
      ResourceTemplateRegistration(uriTemplate, name, description, mimeType, handler)

  def prompt(
    name:        String,
    description: Option[String],
    arguments:   List[McpProtocol.PromptArgument],
    handler:     Map[String, Any] => PromptHandlerResult
  ): Unit =
    prompts(name) = PromptRegistration(name, description, arguments, handler)

  def setOnConnected(f: () => Unit):    Unit = onConnected    = f
  def setOnDisconnected(f: () => Unit): Unit = onDisconnected = f

  /** Register a hook fired for every inbound `resources/subscribe`.  Typical
   *  use: start a file watcher / DB trigger for the given uri here, then
   *  call `notifyResourceUpdate(uri)` from the watcher's callback. */
  def setOnResourceSubscribe(f:   String => Unit): Unit = onResourceSubscribe   = f
  def setOnResourceUnsubscribe(f: String => Unit): Unit = onResourceUnsubscribe = f

  /** Push a `notifications/resources/updated` for `uri` to every active
   *  subscriber connection.  No-op if no client has subscribed to this
   *  uri — saves traffic for resources nobody is watching.  Bypass the
   *  filter via `notify(...)` directly if you want unconditional broadcast. */
  def notifyResourceUpdate(uri: String): Unit =
    if subscribedResources.contains(uri) then
      notify(McpProtocol.Method.ResourcesUpdated, ujson.Obj("uri" -> uri))

  /** Push `notifications/<category>/list_changed` — tells connected clients
   *  the catalog has changed and they should re-fetch via `<category>/list`.
   *  No payload per spec.  Servers call these after registering or
   *  un-registering a tool / resource / prompt at runtime; the matching
   *  `capabilities.<category>.listChanged: true` flag in initialize tells
   *  clients to expect these. */
  def notifyToolsListChanged():     Unit = notify(McpProtocol.Method.ToolsListChanged,     ujson.Obj())
  def notifyResourcesListChanged(): Unit = notify(McpProtocol.Method.ResourcesListChanged, ujson.Obj())
  def notifyPromptsListChanged():   Unit = notify(McpProtocol.Method.PromptsListChanged,   ujson.Obj())

/** Result of a tool handler — list of content items + isError flag.
 *  `Map[String, Any]` flows through user code; we lift to `ujson.Value`
 *  at the boundary so this file stays free of interpreter types. */
case class ToolHandlerResult(content: List[ujson.Value], isError: Boolean)

case class ResourceHandlerResult(uri: String, contents: List[ujson.Value])

case class PromptHandlerResult(description: Option[String], messages: List[ujson.Value])

case class ToolRegistration(
  name:        String,
  description: Option[String],
  inputSchema: ujson.Value,
  handler:     Map[String, Any] => ToolHandlerResult
)

case class ResourceRegistration(
  uri:      String,
  name:     Option[String],
  mimeType: Option[String],
  handler:  String => ResourceHandlerResult
)

case class ResourceTemplateRegistration(
  uriTemplate: String,
  name:        Option[String],
  description: Option[String],
  mimeType:    Option[String],
  handler:     String => ResourceHandlerResult
)

case class PromptRegistration(
  name:        String,
  description: Option[String],
  arguments:   List[McpProtocol.PromptArgument],
  handler:     Map[String, Any] => PromptHandlerResult
)

object McpServerCore:

  /** Run the dispatch loop until `read()` returns `None` (EOF).
   *  All errors thrown by handlers are caught and marshalled into
   *  JSON-RPC `error` responses if the inbound message had an id —
   *  notifications drop their errors silently per spec. */
  def serve(
    builder:       McpServerBuilder,
    read:          () => Option[String],
    write:         String => Unit,
    serverName:    String,
    serverVersion: String
  ): Unit =
    // Register the writer as a notification subscriber for the lifetime
    // of this serve loop so `builder.notify(method, params)` reaches the
    // connected peer.  Stdio is single-connection — exactly one
    // subscriber active while serve() runs.
    val unsubscribe = builder.addSubscriber(write)
    try
      var initialized = false
      var running     = true
      while running do
        read() match
          case None       => running = false
          case Some(line) =>
            JsonRpc.parse(line) match
              case Left(_)                                    => ()  // can't even reply (no id)
              case Right(resp: JsonRpc.Message.Response)      =>
                // v1.17.x bidirectional sampling — client replying to a
                // server-initiated `srv.request(...)`.  Route into the
                // server-side pending map so the caller unblocks.
                builder.routeInboundResponse(resp)
              case Right(JsonRpc.Message.Notification(m, p))  =>
                if m == McpProtocol.Method.Initialized && !initialized then
                  initialized = true
                  try builder.onConnected() catch case _: Throwable => ()
                else if m == McpProtocol.Method.Cancelled then
                  routeCancelled(builder, p)
                else if m == McpProtocol.Method.RootsListChanged then
                  try builder.onRootsListChanged() catch case _: Throwable => ()
              case Right(JsonRpc.Message.Request(method, params, id)) =>
                write(dispatch(builder, method, params, id, serverName, serverVersion))
      try builder.onDisconnected() catch case _: Throwable => ()
    finally unsubscribe()

  /** One POST body containing one JSON-RPC frame → one response body
   *  (or empty string when the frame was a notification that needs no
   *  reply).  Used by Phase 2's `Transport.Http` server: each POST /mcp
   *  request feeds its body to this helper and writes the result back
   *  as the HTTP response body.
   *
   *  Bypasses the long-running `serve()` loop — `Transport.Http` is
   *  request/response oriented; the WebServer's existing thread pool
   *  handles concurrency, no per-server-loop state needed. */
  def handleHttpRequest(
    builder:       McpServerBuilder,
    body:          String,
    serverName:    String = "ssc-mcp-server",
    serverVersion: String = "1.0.0"
  ): String =
    JsonRpc.parse(body) match
      case Left(err) =>
        // Per JSON-RPC: parse errors return a response with id=null.
        JsonRpc.encodeError(ujson.Null, JsonRpc.ErrorCode.ParseError, err)
      case Right(JsonRpc.Message.Notification(method, params)) =>
        // Spec-mandated initialized notification fires the connected hook
        // once per "session"; for HTTP, "session" = first notif we see.
        if method == McpProtocol.Method.Initialized then
          try builder.onConnected() catch case _: Throwable => ()
        else if method == McpProtocol.Method.Cancelled then
          routeCancelled(builder, params)
        else if method == McpProtocol.Method.RootsListChanged then
          try builder.onRootsListChanged() catch case _: Throwable => ()
        ""
      case Right(resp: JsonRpc.Message.Response) =>
        // v1.17.x bidirectional sampling over HTTP: a client may POST a
        // response back to a server-initiated request.  Route it into
        // the pending map; the HTTP response itself is empty 204.
        builder.routeInboundResponse(resp)
        ""
      case Right(JsonRpc.Message.Request(method, params, id)) =>
        dispatch(builder, method, params, id, serverName, serverVersion)

  /** One request → one wire-ready response frame (with trailing `\n`).
   *  Exposed for tests so they can drive dispatch without spinning a
   *  reader/writer pair. */
  def dispatch(
    builder:       McpServerBuilder,
    method:        String,
    params:        ujson.Value,
    id:            ujson.Value,
    serverName:    String = "ssc-mcp-server",
    serverVersion: String = "1.0.0"
  ): String =
    method match
      case McpProtocol.Method.Initialize =>
        // v1.17.x — record the client's advertised capabilities so user
        // code can guard server→client requests like `listRoots()` /
        // `elicitation/create` on the matching feature flag.  Defensive
        // parsing: malformed `params` simply leaves capabilities empty.
        try
          params.obj.get("capabilities").foreach(c => builder.clientCapabilities = c)
        catch case _: Throwable => ()
        JsonRpc.encodeResult(id, McpProtocol.initializeResult(serverName, serverVersion))

      case McpProtocol.Method.Ping =>
        JsonRpc.encodeResult(id, ujson.Obj())

      case McpProtocol.Method.ToolsList =>
        val entries = builder.tools.values.toList.map { r =>
          McpProtocol.ToolEntry(r.name, r.description, r.inputSchema)
        }
        JsonRpc.encodeResult(id, McpProtocol.toolsListResult(entries))

      case McpProtocol.Method.ToolsCall =>
        callTool(builder, params, id)

      case McpProtocol.Method.ResourcesList =>
        val entries = builder.resources.values.toList.map { r =>
          McpProtocol.ResourceEntry(r.uri, r.name, r.mimeType)
        }
        JsonRpc.encodeResult(id, McpProtocol.resourcesListResult(entries))

      case McpProtocol.Method.ResourcesTemplatesList =>
        val entries = builder.resourceTemplates.values.toList.map { t =>
          McpProtocol.ResourceTemplateEntry(t.uriTemplate, t.name, t.description, t.mimeType)
        }
        JsonRpc.encodeResult(id, McpProtocol.resourcesTemplatesListResult(entries))

      case McpProtocol.Method.ResourcesRead =>
        readResource(builder, params, id)

      case McpProtocol.Method.ResourcesSubscribe =>
        subscribeResource(builder, params, id)

      case McpProtocol.Method.ResourcesUnsubscribe =>
        unsubscribeResource(builder, params, id)

      case McpProtocol.Method.LoggingSetLevel =>
        params.objOpt.flatMap(_.get("level").flatMap(_.strOpt)) match
          case None        => invalidParams(id, "logging/setLevel: missing 'level'")
          case Some(level) =>
            if McpProtocol.logLevelRank(level) < 0 then
              invalidParams(id, s"logging/setLevel: unknown level '$level'")
            else
              builder.currentLogLevel = level
              JsonRpc.encodeResult(id, ujson.Obj())

      case McpProtocol.Method.PromptsList =>
        val entries = builder.prompts.values.toList.map { r =>
          McpProtocol.PromptEntry(r.name, r.description, r.arguments)
        }
        JsonRpc.encodeResult(id, McpProtocol.promptsListResult(entries))

      case McpProtocol.Method.PromptsGet =>
        getPrompt(builder, params, id)

      case unknown =>
        JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, s"method not found: $unknown")

  /** Decode `tools/call` params + run the registered handler.  Exceptions
   *  from the handler convert to an `isError=true` ToolResult — that's the
   *  MCP convention (errors are *data*, not transport-level failures). */
  private def callTool(builder: McpServerBuilder, params: ujson.Value, id: ujson.Value): String =
    params.objOpt match
      case None      => invalidParams(id, "tools/call params not an object")
      case Some(obj) => obj.get("name").flatMap(_.strOpt) match
        case None       => invalidParams(id, "tools/call: missing 'name'")
        case Some(name) =>
          val args = obj.get("arguments").map(jsonToScala).getOrElse(Map.empty[String, Any]) match
            case m: Map[String, Any] @unchecked => m
            case _                              => Map.empty[String, Any]
          builder.tools.get(name) match
            case None => JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, s"unknown tool: $name")
            case Some(reg) =>
              withRequestTracking(builder, id, params) {
                try
                  val result = reg.handler(args)
                  JsonRpc.encodeResult(id, McpProtocol.toolsCallResult(result.content, result.isError))
                catch case e: Throwable =>
                  // Handler threw — wrap the message into an isError=true result so
                  // the client surfaces it like the JS/JVM SDKs do.
                  JsonRpc.encodeResult(id, McpProtocol.toolsCallResult(
                    List(McpProtocol.textContent(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))),
                    isError = true
                  ))
              }

  private def readResource(builder: McpServerBuilder, params: ujson.Value, id: ujson.Value): String =
    params.objOpt match
      case None      => invalidParams(id, "resources/read params not an object")
      case Some(obj) => obj.get("uri").flatMap(_.strOpt) match
        case None      => invalidParams(id, "resources/read: missing 'uri'")
        case Some(uri) =>
          // Try exact match first; fall through to template matching
          // (first template whose pattern matches the concrete uri wins).
          val handler: Option[String => ResourceHandlerResult] =
            builder.resources.get(uri).map(_.handler)
              .orElse(builder.resourceTemplates.values.iterator
                .find(t => uriMatchesTemplate(t.uriTemplate, uri))
                .map(_.handler))
          handler match
            case None => JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, s"unknown resource: $uri")
            case Some(h) =>
              withRequestTracking(builder, id, params) {
                try
                  val result = h(uri)
                  JsonRpc.encodeResult(id, McpProtocol.resourcesReadResult(result.contents))
                catch case e: Throwable =>
                  JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError,
                    Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
              }

  /** Simplified RFC 6570 template matcher: `{name}` placeholders match
   *  any non-slash run of characters; everything else is matched
   *  literally.  Strict-enough for typical MCP template use (file paths,
   *  resource ids) without pulling in a full URI-template library. */
  def uriMatchesTemplate(template: String, uri: String): Boolean =
    val sb = new StringBuilder("^")
    val placeholder = """\{[A-Za-z_][A-Za-z0-9_]*\}""".r
    var idx = 0
    placeholder.findAllMatchIn(template).foreach { m =>
      sb.append(java.util.regex.Pattern.quote(template.substring(idx, m.start)))
      sb.append("([^/]+)")
      idx = m.end
    }
    sb.append(java.util.regex.Pattern.quote(template.substring(idx)))
    sb.append("$")
    uri.matches(sb.toString)

  /** Find the in-flight entry matching `params.requestId` and set its
   *  cancel flag.  Best-effort: unknown / completed ids are silently
   *  ignored — matches the spec's "cancellation may not arrive in time". */
  private def routeCancelled(builder: McpServerBuilder, params: ujson.Value): Unit =
    params.objOpt.flatMap(_.get("requestId")).flatMap(_.numOpt).map(_.toLong) match
      case Some(id) =>
        val flag = builder.inflightCancel.get(id)
        if flag != null then flag.set(true)
      case None => ()

  /** Run a tool/resource/prompt handler with cancellation + progress
   *  plumbing wired in.  Reads:
   *   - the numeric id off the JSON-RPC frame → register a cancel flag +
   *     thread the id into `currentReqIdTL` (so `srv.isCancelled` works).
   *   - the `_meta.progressToken` off the params (if any) → thread into
   *     `currentProgressTokenTL` (so `srv.notifyProgress` reaches the
   *     matching client request).
   *  Tears all thread-locals down regardless of handler outcome. */
  private inline def withRequestTracking[A](
    builder: McpServerBuilder,
    id:      ujson.Value,
    params:  ujson.Value
  )(body: => A): A =
    val numId = id.numOpt.map(_.toLong)
    val progressToken = params.objOpt
      .flatMap(_.get("_meta"))
      .flatMap(_.objOpt)
      .flatMap(_.get("progressToken"))
    numId.foreach { n =>
      builder.inflightCancel.put(n, new java.util.concurrent.atomic.AtomicBoolean(false))
      builder.currentReqIdTL.set(java.lang.Long.valueOf(n))
    }
    progressToken.foreach(t => builder.currentProgressTokenTL.set(t))
    try body
    finally
      numId.foreach { n => builder.inflightCancel.remove(n) }
      builder.currentReqIdTL.remove()
      builder.currentProgressTokenTL.remove()

  private def subscribeResource(builder: McpServerBuilder, params: ujson.Value, id: ujson.Value): String =
    params.objOpt.flatMap(_.get("uri").flatMap(_.strOpt)) match
      case None      => invalidParams(id, "resources/subscribe: missing 'uri'")
      case Some(uri) =>
        builder.subscribedResources.add(uri)
        try builder.onResourceSubscribe(uri) catch case _: Throwable => ()
        JsonRpc.encodeResult(id, ujson.Obj())

  private def unsubscribeResource(builder: McpServerBuilder, params: ujson.Value, id: ujson.Value): String =
    params.objOpt.flatMap(_.get("uri").flatMap(_.strOpt)) match
      case None      => invalidParams(id, "resources/unsubscribe: missing 'uri'")
      case Some(uri) =>
        builder.subscribedResources.remove(uri)
        try builder.onResourceUnsubscribe(uri) catch case _: Throwable => ()
        JsonRpc.encodeResult(id, ujson.Obj())

  private def getPrompt(builder: McpServerBuilder, params: ujson.Value, id: ujson.Value): String =
    params.objOpt match
      case None      => invalidParams(id, "prompts/get params not an object")
      case Some(obj) => obj.get("name").flatMap(_.strOpt) match
        case None       => invalidParams(id, "prompts/get: missing 'name'")
        case Some(name) =>
          val args = obj.get("arguments").map(jsonToScala).getOrElse(Map.empty[String, Any]) match
            case m: Map[String, Any] @unchecked => m
            case _                              => Map.empty[String, Any]
          builder.prompts.get(name) match
            case None => JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, s"unknown prompt: $name")
            case Some(reg) =>
              withRequestTracking(builder, id, params) {
                try
                  val result = reg.handler(args)
                  JsonRpc.encodeResult(id, McpProtocol.promptsGetResult(result.description, result.messages))
                catch case e: Throwable =>
                  JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError,
                    Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
              }

  private def invalidParams(id: ujson.Value, msg: String): String =
    JsonRpc.encodeError(id, JsonRpc.ErrorCode.InvalidParams, msg)

  /** Convert a `ujson.Value` payload into a plain-Scala tree (`Map[String, Any]`
   *  / `List[Any]` / primitives) so handlers don't need ujson knowledge.
   *
   *  Numbers come back as `Double` — handlers convert via the
   *  `requireInt` / `requireDouble` extractors from `std/mcp/types.ssc`. */
  def jsonToScala(v: ujson.Value): Any = v match
    case ujson.Null    => null
    case ujson.True    => true
    case ujson.False   => false
    case ujson.Num(n)  => n
    case ujson.Str(s)  => s
    case ujson.Arr(xs) => xs.iterator.map(jsonToScala).toList
    case ujson.Obj(kv) => kv.iterator.map((k, v) => k -> jsonToScala(v)).toMap

  /** Inverse of `jsonToScala` — used by the client core to encode user-supplied
   *  args before sending them.  Doubles whose toLong round-trips losslessly
   *  serialise as integers to match the typical JS/Python clients. */
  def scalaToJson(a: Any): ujson.Value = a match
    case null                                     => ujson.Null
    case b: Boolean                               => if b then ujson.True else ujson.False
    case s: String                                => ujson.Str(s)
    case i: Int                                   => ujson.Num(i.toDouble)
    case l: Long                                  => ujson.Num(l.toDouble)
    case d: Double                                => ujson.Num(d)
    case m: scala.collection.Map[?, ?] @unchecked =>
      val obj = ujson.Obj()
      m.foreach { case (k, v) => obj(k.toString) = scalaToJson(v) }
      obj
    case xs: Iterable[?]                          =>
      ujson.Arr.from(xs.iterator.map(scalaToJson).toSeq)
    case other                                    => ujson.Str(other.toString)
