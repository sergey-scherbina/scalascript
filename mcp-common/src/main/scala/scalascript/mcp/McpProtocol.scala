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
    // v1.17.x — completion (autocomplete suggestions for prompt args and
    // resource-template parameters).  Client → server request with a
    // `ref` discriminating prompt vs resource and the current partial
    // value; server replies with up to 100 suggestions.
    val CompletionComplete    = "completion/complete"

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
        "tools"       -> ujson.Obj("listChanged" -> true),
        "resources"   -> ujson.Obj("subscribe" -> true, "listChanged" -> true),
        "prompts"     -> ujson.Obj("listChanged" -> true),
        "logging"     -> ujson.Obj(),
        "completions" -> ujson.Obj()
      ),
      "serverInfo" -> ujson.Obj(
        "name"    -> serverName,
        "version" -> serverVersion
      )
    )

  // ─── Envelope builders for result payloads ──────────────────────────

  /** `tools/list` result: `{tools: [{name, description, inputSchema,
   *  annotations?}, ...]}`.  Pass `nextCursor = Some(...)` to indicate
   *  there are more pages. */
  def toolsListResult(tools: List[ToolEntry], nextCursor: Option[String] = None): ujson.Value =
    val obj = ujson.Obj(
      "tools" -> ujson.Arr.from(tools.map { t =>
        val o = ujson.Obj("name" -> t.name)
        t.description.foreach(d => o("description") = d)
        o("inputSchema") = t.inputSchema
        t.annotations.filterNot(_.isEmpty).foreach(a => o("annotations") = a.toJson)
        o
      })
    )
    nextCursor.foreach(c => obj("nextCursor") = c)
    obj

  /** `tools/call` result: `{content: [...], isError: bool}` — content is
   *  a list of `Content` records (text / image / resource refs). */
  def toolsCallResult(content: List[ujson.Value], isError: Boolean): ujson.Value =
    ujson.Obj(
      "content" -> ujson.Arr.from(content),
      "isError" -> isError
    )

  def resourcesListResult(resources: List[ResourceEntry], nextCursor: Option[String] = None): ujson.Value =
    val obj = ujson.Obj(
      "resources" -> ujson.Arr.from(resources.map { r =>
        val o = ujson.Obj("uri" -> r.uri)
        r.name.foreach(n     => o("name")     = n)
        r.mimeType.foreach(m => o("mimeType") = m)
        r.annotations.filterNot(_.isEmpty).foreach(a => o("annotations") = a.toJson)
        o
      })
    )
    nextCursor.foreach(c => obj("nextCursor") = c)
    obj

  def resourcesTemplatesListResult(templates: List[ResourceTemplateEntry], nextCursor: Option[String] = None): ujson.Value =
    val obj = ujson.Obj(
      "resourceTemplates" -> ujson.Arr.from(templates.map { t =>
        val o = ujson.Obj("uriTemplate" -> t.uriTemplate)
        t.name.foreach(n        => o("name")        = n)
        t.description.foreach(d => o("description") = d)
        t.mimeType.foreach(m    => o("mimeType")    = m)
        t.annotations.filterNot(_.isEmpty).foreach(a => o("annotations") = a.toJson)
        o
      })
    )
    nextCursor.foreach(c => obj("nextCursor") = c)
    obj

  /** `resources/read` result: `{contents: [{uri, mimeType?, text? | blob?}, ...]}` */
  def resourcesReadResult(contents: List[ujson.Value]): ujson.Value =
    ujson.Obj("contents" -> ujson.Arr.from(contents))

  def promptsListResult(prompts: List[PromptEntry], nextCursor: Option[String] = None): ujson.Value =
    val obj = ujson.Obj(
      "prompts" -> ujson.Arr.from(prompts.map { p =>
        val o = ujson.Obj("name" -> p.name)
        p.description.foreach(d => o("description") = d)
        if p.arguments.nonEmpty then
          o("arguments") = ujson.Arr.from(p.arguments.map { a =>
            ujson.Obj(
              "name"        -> a.name,
              "description" -> a.description,
              "required"    -> a.required
            )
          })
        o
      })
    )
    nextCursor.foreach(c => obj("nextCursor") = c)
    obj

  /** `prompts/get` result: `{description?, messages: [{role, content}, ...]}` */
  def promptsGetResult(description: Option[String], messages: List[ujson.Value]): ujson.Value =
    val obj = ujson.Obj()
    description.foreach(d => obj("description") = d)
    obj("messages") = ujson.Arr.from(messages)
    obj

  // ─── Catalog entries the server registry holds ──────────────────────

  /** v1.17.x — MCP 2025-03 tool annotations.  Pure UI hints for the
   *  client; servers SHOULD set whichever are accurate.
   *    title           — display name shown to the user
   *    readOnlyHint    — tool does not modify the environment
   *    destructiveHint — tool may perform destructive updates
   *    idempotentHint  — repeat calls with same args produce no extra effect
   *    openWorldHint   — tool reaches uncontrolled / external systems */
  case class ToolAnnotations(
    title:           Option[String]  = None,
    readOnlyHint:    Option[Boolean] = None,
    destructiveHint: Option[Boolean] = None,
    idempotentHint:  Option[Boolean] = None,
    openWorldHint:   Option[Boolean] = None
  ):
    def toJson: ujson.Value =
      val obj = ujson.Obj()
      title.foreach          (t => obj("title")           = t)
      readOnlyHint.foreach   (b => obj("readOnlyHint")    = ujson.Bool(b))
      destructiveHint.foreach(b => obj("destructiveHint") = ujson.Bool(b))
      idempotentHint.foreach (b => obj("idempotentHint")  = ujson.Bool(b))
      openWorldHint.foreach  (b => obj("openWorldHint")   = ujson.Bool(b))
      obj
    def isEmpty: Boolean =
      title.isEmpty && readOnlyHint.isEmpty && destructiveHint.isEmpty &&
      idempotentHint.isEmpty && openWorldHint.isEmpty

  /** v1.17.x — MCP 2025-03 resource annotations.  `audience` is the
   *  intended consumer(s) ("user" / "assistant"); `priority` is a hint
   *  between 0.0 and 1.0 for ranking. */
  case class ResourceAnnotations(
    audience: List[String]  = Nil,
    priority: Option[Double] = None
  ):
    def toJson: ujson.Value =
      val obj = ujson.Obj()
      if audience.nonEmpty then obj("audience") = ujson.Arr.from(audience.map(ujson.Str(_)))
      priority.foreach(p => obj("priority") = ujson.Num(p))
      obj
    def isEmpty: Boolean = audience.isEmpty && priority.isEmpty

  case class ToolEntry(
    name:        String,
    description: Option[String],
    inputSchema: ujson.Value,
    annotations: Option[ToolAnnotations] = None
  )
  case class ResourceEntry(
    uri:         String,
    name:        Option[String],
    mimeType:    Option[String],
    annotations: Option[ResourceAnnotations] = None
  )
  case class ResourceTemplateEntry(
    uriTemplate: String,
    name:        Option[String],
    description: Option[String],
    mimeType:    Option[String],
    annotations: Option[ResourceAnnotations] = None
  )
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

  // ─── Pagination ─────────────────────────────────────────────────────

  /** v1.17.x — opaque cursor wire format.  Spec says cursor is an
   *  arbitrary string; we use the literal byte representation of the
   *  next offset to keep the implementation transparent for tests
   *  ("0", "10", "20", …).  Clients MUST treat cursors as opaque per
   *  spec, so any encoding is valid. */
  def encodeCursor(offset: Int): String = offset.toString

  /** Defensive decode: bad / non-numeric cursor strings map to offset 0
   *  (start of list) rather than crashing.  Per spec, an invalid cursor
   *  is an error — InvalidParams is the canonical reply — but the
   *  caller decides; this helper just parses. */
  def decodeCursor(cursor: String): Option[Int] =
    try Some(cursor.toInt) catch case _: Throwable => None

  /** Slice `items` for one page starting at the offset encoded by
   *  `cursor` (None → start at 0).  Returns the page slice plus
   *  `Some(nextCursor)` when more items remain past this page,
   *  `None` when this is the last page.  `pageSize <= 0` returns
   *  everything in one page (pagination disabled). */
  def paginate[A](items: List[A], cursor: Option[String], pageSize: Int): (List[A], Option[String]) =
    if pageSize <= 0 then (items, None)
    else
      val start = cursor.flatMap(decodeCursor).getOrElse(0).max(0)
      val end   = start + pageSize
      val slice = items.slice(start, end)
      val next  = if end < items.length then Some(encodeCursor(end)) else None
      (slice, next)

  // ─── Completion ─────────────────────────────────────────────────────

  /** Spec caps completion results at 100 entries.  Helper applies the
   *  cap consistently and computes the `hasMore` flag. */
  val CompletionMaxValues = 100

  /** Build a `completion/complete` result envelope from a raw list of
   *  suggestion strings.  Trims to `CompletionMaxValues`; sets `hasMore`
   *  iff the original list exceeded that cap; reports `total` so clients
   *  can show "X of Y" hints. */
  def completionResult(values: List[String]): ujson.Value =
    val total   = values.length
    val capped  = values.take(CompletionMaxValues)
    val hasMore = total > CompletionMaxValues
    ujson.Obj("completion" -> ujson.Obj(
      "values"  -> ujson.Arr.from(capped.map(ujson.Str(_))),
      "total"   -> ujson.Num(total.toDouble),
      "hasMore" -> ujson.Bool(hasMore)
    ))

  /** Discriminator for `completion/complete` requests.  Per spec, `ref`
   *  is either `{type: "ref/prompt", name}` (autocomplete a prompt
   *  argument) or `{type: "ref/resource", uri}` (autocomplete a URI-
   *  template variable). */
  enum CompletionRef:
    case PromptRef(name: String)
    case ResourceRef(uri:  String)

  /** Defensive parser for the `ref` object — unknown shapes return None
   *  so the server can reply MethodNotFound / InvalidParams instead of
   *  crashing. */
  def parseCompletionRef(js: ujson.Value): Option[CompletionRef] =
    try
      js.obj.get("type").flatMap(_.strOpt) match
        case Some("ref/prompt") =>
          js.obj.get("name").flatMap(_.strOpt).map(CompletionRef.PromptRef(_))
        case Some("ref/resource") =>
          js.obj.get("uri").flatMap(_.strOpt).map(CompletionRef.ResourceRef(_))
        case _ => None
    catch case _: Throwable => None

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
