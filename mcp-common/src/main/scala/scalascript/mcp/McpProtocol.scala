package scalascript.mcp

/** MCP method names + result envelope shapes.  Built on top of
 *  `JsonRpc` — these are the *protocol* concerns above the framing.
 *
 *  Scope (Phase 1):
 *    - `initialize` — handshake, server announces capabilities + serverInfo.
 *    - `tools/list`, `tools/call`.
 *    - `resources/list`, `resources/read`.
 *    - `prompts/list`, `prompts/get`.
 *    - `ping` — round-trip liveness check.
 *
 *  Out of scope (deferred — would need bidirectional sampling, log
 *  notifications, progress callbacks): the `notifications`, `sampling`,
 *  `logging`, and `roots` method namespaces. */
object McpProtocol:

  /** Spec-locked method names — string-keyed because JSON-RPC dispatches by
   *  exact method name. */
  object Method:
    val Initialize            = "initialize"
    val Initialized           = "notifications/initialized"
    val Ping                  = "ping"
    val ToolsList             = "tools/list"
    val ToolsCall             = "tools/call"
    val ToolsListChanged      = "notifications/tools/list_changed"
    val ResourcesList         = "resources/list"
    val ResourcesRead         = "resources/read"
    val ResourcesSubscribe    = "resources/subscribe"
    val ResourcesUnsubscribe  = "resources/unsubscribe"
    val ResourcesUpdated      = "notifications/resources/updated"
    val ResourcesListChanged  = "notifications/resources/list_changed"
    val PromptsList           = "prompts/list"
    val PromptsGet            = "prompts/get"
    val PromptsListChanged    = "notifications/prompts/list_changed"
    val Cancelled             = "notifications/cancelled"
    val Progress              = "notifications/progress"
    val LoggingSetLevel       = "logging/setLevel"
    val LogMessage            = "notifications/message"
    val ResourcesTemplatesList = "resources/templates/list"
    // v1.17.x — roots (client → server workspace info).  `roots/list` is
    // a server-initiated request; the client replies with a list of
    // workspace roots.  `notifications/roots/list_changed` is a
    // client → server push when its roots change.
    val RootsList             = "roots/list"
    val RootsListChanged      = "notifications/roots/list_changed"
    // v1.17.x — elicitation (server → client request for user input).
    // Sent during a tool call to ask the user for additional info; the
    // client replies with one of: accept (content matches schema),
    // decline (user said no), cancel (user dismissed the dialog).
    val ElicitationCreate     = "elicitation/create"

  /** Syslog levels per MCP spec, ordered by severity (low to high). */
  val LogLevels: List[String] = List(
    "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency"
  )

  /** Numeric rank of a log level (-1 if unknown).  Levels >= rank pass
   *  through `notifyMessage`; lower ranks are filtered. */
  def logLevelRank(level: String): Int = LogLevels.indexOf(level)

  /** Protocol version we advertise.  MCP spec uses a date-stamped version
   *  string; bump when we add notification/sampling/etc. support. */
  val ProtocolVersion = "2024-11-05"

  /** `initialize` result — what the server tells the client about itself
   *  and which capabilities it offers.  All three primitive categories
   *  advertise `listChanged: true`; resources also `subscribe: true`;
   *  `logging: {}` flags client→server log-level control + matching
   *  server→client `notifications/message` push. */
  def initializeResult(serverName: String, serverVersion: String): ujson.Value =
    ujson.Obj(
      "protocolVersion" -> ProtocolVersion,
      "capabilities" -> ujson.Obj(
        "tools"     -> ujson.Obj("listChanged" -> true),
        "resources" -> ujson.Obj("subscribe" -> true, "listChanged" -> true),
        "prompts"   -> ujson.Obj("listChanged" -> true),
        "logging"   -> ujson.Obj()
      ),
      "serverInfo" -> ujson.Obj(
        "name"    -> serverName,
        "version" -> serverVersion
      )
    )

  // ─── Envelope builders for result payloads ──────────────────────────

  /** `tools/list` result: `{tools: [{name, description, inputSchema}, ...]}` */
  def toolsListResult(tools: List[ToolEntry]): ujson.Value =
    ujson.Obj(
      "tools" -> ujson.Arr.from(tools.map { t =>
        val obj = ujson.Obj("name" -> t.name)
        t.description.foreach(d => obj("description") = d)
        obj("inputSchema") = t.inputSchema
        obj
      })
    )

  /** `tools/call` result: `{content: [...], isError: bool}` — content is
   *  a list of `Content` records (text / image / resource refs). */
  def toolsCallResult(content: List[ujson.Value], isError: Boolean): ujson.Value =
    ujson.Obj(
      "content" -> ujson.Arr.from(content),
      "isError" -> isError
    )

  def resourcesListResult(resources: List[ResourceEntry]): ujson.Value =
    ujson.Obj(
      "resources" -> ujson.Arr.from(resources.map { r =>
        val obj = ujson.Obj("uri" -> r.uri)
        r.name.foreach(n     => obj("name")     = n)
        r.mimeType.foreach(m => obj("mimeType") = m)
        obj
      })
    )

  def resourcesTemplatesListResult(templates: List[ResourceTemplateEntry]): ujson.Value =
    ujson.Obj(
      "resourceTemplates" -> ujson.Arr.from(templates.map { t =>
        val obj = ujson.Obj("uriTemplate" -> t.uriTemplate)
        t.name.foreach(n        => obj("name")        = n)
        t.description.foreach(d => obj("description") = d)
        t.mimeType.foreach(m    => obj("mimeType")    = m)
        obj
      })
    )

  /** `resources/read` result: `{contents: [{uri, mimeType?, text? | blob?}, ...]}` */
  def resourcesReadResult(contents: List[ujson.Value]): ujson.Value =
    ujson.Obj("contents" -> ujson.Arr.from(contents))

  def promptsListResult(prompts: List[PromptEntry]): ujson.Value =
    ujson.Obj(
      "prompts" -> ujson.Arr.from(prompts.map { p =>
        val obj = ujson.Obj("name" -> p.name)
        p.description.foreach(d => obj("description") = d)
        if p.arguments.nonEmpty then
          obj("arguments") = ujson.Arr.from(p.arguments.map { a =>
            ujson.Obj(
              "name"        -> a.name,
              "description" -> a.description,
              "required"    -> a.required
            )
          })
        obj
      })
    )

  /** `prompts/get` result: `{description?, messages: [{role, content}, ...]}` */
  def promptsGetResult(description: Option[String], messages: List[ujson.Value]): ujson.Value =
    val obj = ujson.Obj()
    description.foreach(d => obj("description") = d)
    obj("messages") = ujson.Arr.from(messages)
    obj

  // ─── Catalog entries the server registry holds ──────────────────────

  case class ToolEntry(name: String, description: Option[String], inputSchema: ujson.Value)
  case class ResourceEntry(uri: String, name: Option[String], mimeType: Option[String])
  case class ResourceTemplateEntry(uriTemplate: String, name: Option[String], description: Option[String], mimeType: Option[String])
  case class PromptEntry(name: String, description: Option[String], arguments: List[PromptArgument])
  case class PromptArgument(name: String, description: String, required: Boolean)

  /** v1.17.x — workspace root advertised by the client during `roots/list`.
   *  Per spec, `uri` MUST be a `file://` URI; `name` is a display hint. */
  case class Root(uri: String, name: Option[String])

  /** Client-side `roots/list` response builder, for symmetry. */
  def rootsListResult(roots: List[Root]): ujson.Value =
    ujson.Obj(
      "roots" -> ujson.Arr.from(roots.map { r =>
        val obj = ujson.Obj("uri" -> r.uri)
        r.name.foreach(n => obj("name") = n)
        obj
      })
    )

  /** Parse a `roots/list` response into typed `Root` records.  Returns
   *  `Nil` when the shape doesn't match (defensive — bad clients shouldn't
   *  crash the server). */
  def parseRootsListResult(js: ujson.Value): List[Root] =
    try
      js.obj.get("roots") match
        case Some(arr) =>
          arr.arr.iterator.flatMap { v =>
            v.obj.get("uri").flatMap(_.strOpt).map { uri =>
              Root(uri, v.obj.get("name").flatMap(_.strOpt))
            }
          }.toList
        case None => Nil
    catch case _: Throwable => Nil

  // ─── Elicitation ────────────────────────────────────────────────────

  /** v1.17.x — three-way response shape for `elicitation/create`.
   *  `Accept(content)`: the user filled in the schema; payload is the
   *    structured ujson object — typed parsing is the caller's job.
   *  `Decline`: the user explicitly refused (e.g. clicked "No").
   *  `Cancel`:  the user dismissed the dialog without deciding (e.g.
   *    closed it, hit Escape).  Servers usually treat decline+cancel
   *    the same — see `isAccepted` / `acceptedContent`. */
  enum ElicitationResult:
    case Accept(content: ujson.Value)
    case Decline
    case Cancel

    def isAccepted: Boolean = this.isInstanceOf[Accept]
    def acceptedContent: Option[ujson.Value] = this match
      case Accept(c) => Some(c)
      case _         => None

  /** Build the params for an outgoing `elicitation/create` request. */
  def elicitationCreateParams(message: String, requestedSchema: ujson.Value): ujson.Value =
    ujson.Obj("message" -> message, "requestedSchema" -> requestedSchema)

  /** Parse the client's reply.  Unknown / malformed shapes resolve to
   *  `Cancel` so user code defaults to the safe "user didn't agree"
   *  branch instead of crashing. */
  def parseElicitationResult(js: ujson.Value): ElicitationResult =
    try
      js.obj.get("action").flatMap(_.strOpt) match
        case Some("accept") =>
          val content = js.obj.get("content").getOrElse(ujson.Obj())
          ElicitationResult.Accept(content)
        case Some("decline") => ElicitationResult.Decline
        case _               => ElicitationResult.Cancel
    catch case _: Throwable => ElicitationResult.Cancel

  // ─── Content variants — the protocol's polymorphic value type ──────

  def textContent(text: String): ujson.Value =
    ujson.Obj("type" -> "text", "text" -> text)

  def imageContent(data: String, mimeType: String): ujson.Value =
    ujson.Obj("type" -> "image", "data" -> data, "mimeType" -> mimeType)

  def resourceContent(uri: String): ujson.Value =
    ujson.Obj("type" -> "resource", "resource" -> ujson.Obj("uri" -> uri))
