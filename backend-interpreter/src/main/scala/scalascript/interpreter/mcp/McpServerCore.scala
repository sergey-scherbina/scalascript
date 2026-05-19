package scalascript.interpreter.mcp

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
  // Resource registrations keyed by uri (Phase 1 — no template/glob matching).
  private[mcp] val resources = mutable.LinkedHashMap.empty[String, ResourceRegistration]
  // Prompt registrations keyed by name.
  private[mcp] val prompts = mutable.LinkedHashMap.empty[String, PromptRegistration]
  // Lifecycle hooks — fired after the `initialize` handshake / on EOF.
  private[mcp] var onConnected:    () => Unit = () => ()
  private[mcp] var onDisconnected: () => Unit = () => ()

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

  def prompt(
    name:        String,
    description: Option[String],
    arguments:   List[McpProtocol.PromptArgument],
    handler:     Map[String, Any] => PromptHandlerResult
  ): Unit =
    prompts(name) = PromptRegistration(name, description, arguments, handler)

  def setOnConnected(f: () => Unit):    Unit = onConnected    = f
  def setOnDisconnected(f: () => Unit): Unit = onDisconnected = f

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
    var initialized = false
    var running     = true
    while running do
      read() match
        case None       => running = false
        case Some(line) =>
          JsonRpc.parse(line) match
            case Left(_)                                    => ()  // can't even reply (no id)
            case Right(JsonRpc.Message.Response(_, _, _))   => ()  // we're the server — drop stray responses
            case Right(JsonRpc.Message.Notification(m, _))  =>
              if m == McpProtocol.Method.Initialized && !initialized then
                initialized = true
                try builder.onConnected() catch case _: Throwable => ()
            case Right(JsonRpc.Message.Request(method, params, id)) =>
              write(dispatch(builder, method, params, id, serverName, serverVersion))
    try builder.onDisconnected() catch case _: Throwable => ()

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

      case McpProtocol.Method.ResourcesRead =>
        readResource(builder, params, id)

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

  private def readResource(builder: McpServerBuilder, params: ujson.Value, id: ujson.Value): String =
    params.objOpt match
      case None      => invalidParams(id, "resources/read params not an object")
      case Some(obj) => obj.get("uri").flatMap(_.strOpt) match
        case None      => invalidParams(id, "resources/read: missing 'uri'")
        case Some(uri) =>
          builder.resources.get(uri) match
            case None => JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound, s"unknown resource: $uri")
            case Some(reg) =>
              try
                val result = reg.handler(uri)
                JsonRpc.encodeResult(id, McpProtocol.resourcesReadResult(result.contents))
              catch case e: Throwable =>
                JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError,
                  Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

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
              try
                val result = reg.handler(args)
                JsonRpc.encodeResult(id, McpProtocol.promptsGetResult(result.description, result.messages))
              catch case e: Throwable =>
                JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError,
                  Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

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
