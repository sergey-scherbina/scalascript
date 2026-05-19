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

  // ─── Content variants — the protocol's polymorphic value type ──────

  def textContent(text: String): ujson.Value =
    ujson.Obj("type" -> "text", "text" -> text)

  def imageContent(data: String, mimeType: String): ujson.Value =
    ujson.Obj("type" -> "image", "data" -> data, "mimeType" -> mimeType)

  def resourceContent(uri: String): ujson.Value =
    ujson.Obj("type" -> "resource", "resource" -> ujson.Obj("uri" -> uri))
