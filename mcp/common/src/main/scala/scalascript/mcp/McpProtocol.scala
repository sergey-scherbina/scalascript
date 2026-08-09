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
 *  `logging`, and `roots` method namespaces.
 *
 *  DUAL-ERA (MCP 2026-07-28).  Everything above describes the LEGACY era,
 *  which is still served unchanged.  Alongside it this object carries the
 *  stateless revision: `server/discover`, the reserved `_meta` keys that
 *  replace the handshake (`MetaKey`), the spec-allocated error codes
 *  (`ErrorCode`), and `stampComplete` — the single site that turns a
 *  legacy envelope into a modern one.  Which era a request belongs to is
 *  decided by whether it carries `MetaKey.ProtocolVersion`.
 *  Design and phasing: `specs/mcp-2026-07-28.md`. */
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
    // MCP 2026-07-28 — the one method a stateless server MUST implement.
    // Answers supported versions + capabilities + identity in a single
    // round trip, and doubles as the stdio probe a dual-era client uses
    // to tell a modern server from a legacy one.
    val ServerDiscover        = "server/discover"

  /** MCP 2026-07-28 — reserved `_meta` keys.  The revision moved protocol
   *  version, client identity and client capabilities out of the
   *  `initialize` handshake and onto every individual request, keyed
   *  under the reserved `io.modelcontextprotocol/` prefix. */
  object MetaKey:
    val ProtocolVersion    = "io.modelcontextprotocol/protocolVersion"
    val ClientInfo         = "io.modelcontextprotocol/clientInfo"
    val ClientCapabilities = "io.modelcontextprotocol/clientCapabilities"
    val LogLevel           = "io.modelcontextprotocol/logLevel"
    val ServerInfo         = "io.modelcontextprotocol/serverInfo"
    val SubscriptionId     = "io.modelcontextprotocol/subscriptionId"

  /** MCP 2026-07-28 — the codes the spec allocates to itself.  The
   *  revision partitions JSON-RPC's implementation-defined server-error
   *  range: -32000..-32019 is grandfathered legacy, -32020..-32099 is
   *  the spec's, and an implementation MUST NOT emit a code from the
   *  latter that the spec has not defined.  These three are all of them
   *  as of this revision. */
  object ErrorCode:
    val HeaderMismatch                  = -32020
    val MissingRequiredClientCapability = -32021
    val UnsupportedProtocolVersion      = -32022

  /** Syslog levels per MCP spec, ordered by severity (low to high). */
  val LogLevels: List[String] = List(
    "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency"
  )

  /** Numeric rank of a log level (-1 if unknown).  Levels >= rank pass
   *  through `notifyMessage`; lower ranks are filtered. */
  def logLevelRank(level: String): Int = LogLevels.indexOf(level)

  /** The best LEGACY revision we actually implement — what an `initialize`
   *  handshake asks for as a client, and falls back to as a server.
   *
   *  `2025-03-26`, raised from `2024-11-05` by P1b after an audit rather than
   *  a guess. Everything that revision added is present: tool annotations,
   *  audio content, the completions capability, Streamable HTTP, the OAuth
   *  framework, resource templates, and `message` on progress.
   *
   *  **It is deliberately NOT `2025-06-18`,** even though we have that
   *  revision's headline features (structured tool output, elicitation,
   *  resource links, RFC 9728 metadata). Two of its requirements are simply
   *  absent — the `MCP-Protocol-Version` HTTP header and the `context` field
   *  on `completion/complete`, both measured at zero occurrences on
   *  2026-08-09. Advertising it would swap a four-revision UNDER-claim for an
   *  over-claim, which is the same defect pointing the other way. P2 owns the
   *  header; when both land, this constant and the list below move together. */
  val ProtocolVersion = "2025-03-26"

  /** MCP 2026-07-28 — the stateless revision, and what we prefer to speak.
   *  There is no handshake in this era: a client states this version in the
   *  `_meta` of every request. */
  val ModernProtocolVersion = "2026-07-28"

  /** Every revision this server will accept on a modern request, newest
   *  first.  A request naming anything else is rejected with
   *  `UnsupportedProtocolVersion` carrying this list, which is how a client
   *  discovers what to retry with. */
  val SupportedProtocolVersions: List[String] =
    List(ModernProtocolVersion, ProtocolVersion, "2024-11-05")

  /** True iff `v` is a revision that uses per-request `_meta` rather than an
   *  `initialize` handshake.  Version strings are ISO dates, so a lexical
   *  compare is a chronological one. */
  def isModernVersion(v: String): Boolean = v >= ModernProtocolVersion

  /** The revisions an `initialize` handshake may settle on.  The modern one
   *  is excluded BY CONSTRUCTION rather than by hand: `2026-07-28` deleted the
   *  handshake, so echoing it back to a client that just sent `initialize`
   *  would agree to speak a revision in which the message they sent does not
   *  exist. */
  val LegacyProtocolVersions: List[String] =
    SupportedProtocolVersions.filterNot(isModernVersion)

  /** Pre-2026 lifecycle negotiation: honour the client's requested revision
   *  when we implement it, otherwise answer with our best and let the client
   *  decide whether it can live with that.
   *
   *  Until P1b this did not exist — `initialize` ignored `params` entirely and
   *  always replied with one hardcoded string, which is not negotiation at
   *  all. A client asking for `2024-11-05` was told `2024-11-05` only because
   *  that happened to be the constant. */
  def negotiateLegacyVersion(requested: Option[String]): String =
    requested.filter(LegacyProtocolVersions.contains).getOrElse(ProtocolVersion)

  // ─── MCP 2026-07-28 — per-request protocol metadata ─────────────────

  /** What a modern request carries in `params._meta` in place of the
   *  handshake that used to establish it once per session.
   *
   *  `protocolVersion` is the era discriminator: the spec says a request
   *  carrying modern per-request `_meta` is served statelessly, and an
   *  `initialize` request selects legacy semantics.  Its absence therefore
   *  means "legacy", never "malformed" — a dual-era server cannot reject a
   *  legacy client for failing to send fields its revision never had. */
  case class RequestContext(
    protocolVersion:    Option[String]      = None,
    clientInfo:         Option[ujson.Value] = None,
    clientCapabilities: Option[ujson.Value] = None,
    logLevel:           Option[String]      = None
  ):
    def isModern: Boolean = protocolVersion.isDefined

  /** Read `params._meta` into a `RequestContext`.  Defensive by design: a
   *  missing, non-object or otherwise malformed `_meta` yields the empty
   *  context, i.e. the request is treated as legacy.  Never throws. */
  def parseRequestMeta(params: ujson.Value): RequestContext =
    try
      params.objOpt.flatMap(_.get("_meta")).flatMap(_.objOpt) match
        case None => RequestContext()
        case Some(meta) =>
          RequestContext(
            protocolVersion    = meta.get(MetaKey.ProtocolVersion).flatMap(_.strOpt),
            clientInfo         = meta.get(MetaKey.ClientInfo),
            clientCapabilities = meta.get(MetaKey.ClientCapabilities),
            logLevel           = meta.get(MetaKey.LogLevel).flatMap(_.strOpt)
          )
    catch case _: Throwable => RequestContext()

  /** `data` payload for `UnsupportedProtocolVersion` (-32022): the versions
   *  we do support, and the one that was asked for, so the client can pick
   *  an intersection and retry rather than just fail. */
  def unsupportedVersionData(requested: String): ujson.Value =
    ujson.Obj(
      "supported" -> ujson.Arr.from(SupportedProtocolVersions.map(ujson.Str(_))),
      "requested" -> requested
    )

  /** `data` payload for `MissingRequiredClientCapability` (-32021). */
  def missingCapabilityData(required: List[String]): ujson.Value =
    ujson.Obj("requiredCapabilities" -> ujson.Arr.from(required.map(ujson.Str(_))))

  /** Stamp a legacy-shaped result envelope with the two things every modern
   *  result carries: `resultType` and the server's identity under
   *  `_meta`.
   *
   *  Post-processing rather than a parameter on each builder is the whole
   *  point — there are fifteen builders and one of these, so the legacy
   *  bytes are unchanged BY CONSTRUCTION and a gate can assert it.  An
   *  existing `_meta` is merged into, not replaced: a builder that already
   *  wrote one keeps its keys. */
  def stampComplete(
    result:        ujson.Value,
    serverName:    String,
    serverVersion: String,
    resultType:    String        = "complete",
    cache:         Option[CacheHints] = None
  ): ujson.Value =
    result match
      case obj: ujson.Obj =>
        obj("resultType") = resultType
        val meta = obj.value.get("_meta") match
          case Some(m: ujson.Obj) => m           // merge — keep what the builder wrote
          case _                  => ujson.Obj()
        meta(MetaKey.ServerInfo) = ujson.Obj("name" -> serverName, "version" -> serverVersion)
        obj("_meta") = meta
        cache.foreach { c =>
          obj("ttlMs")      = ujson.Num(c.ttlMs.toDouble)
          obj("cacheScope") = c.cacheScope
        }
        obj
      case other => other   // non-object result: nothing to stamp onto

  // ─── MCP 2026-07-28 — CacheableResult ───────────────────────────────

  /** `ttlMs` + `cacheScope`, which the revision makes MANDATORY on the six
   *  cacheable operations' `resultType: "complete"` results.
   *
   *  `ttlMs` is a freshness hint in milliseconds and the spec requires it to
   *  be `>= 0`; `0` means "immediately stale". `cacheScope` is `"public"` (no
   *  user-specific data, any shared proxy may serve it to anyone) or
   *  `"private"` (reusable only within the same authorization context). */
  case class CacheHints(ttlMs: Long, cacheScope: String):
    require(ttlMs >= 0, s"ttlMs must be >= 0, got $ttlMs")
    require(cacheScope == "public" || cacheScope == "private", s"bad cacheScope: $cacheScope")

  /** The six operations the spec names. Anything else carries no hints —
   *  and MUST NOT, since a client would then cache something the spec never
   *  said was cacheable. */
  val CacheableMethods: Set[String] = Set(
    Method.ServerDiscover, Method.ToolsList, Method.PromptsList,
    Method.ResourcesList, Method.ResourcesTemplatesList, Method.ResourcesRead
  )

  /** Default freshness for a catalogue that only changes when the server
   *  mutates its own registry — one minute.
   *
   *  Not plucked from nothing: our servers advertise `listChanged: true` on
   *  tools, resources and prompts, and the spec blesses TTL and notifications
   *  together — the notification is an immediate invalidation, the TTL just
   *  saves refetches in between. A minute is short enough that a client which
   *  MISSES a notification is wrong only briefly. */
  val DefaultListTtlMs: Long = 60_000

  /** `resources/read` defaults to 0 — immediately stale.
   *
   *  Deliberately the conservative end. A resource's content is produced by a
   *  user handler we know nothing about; it may be a file, a query, a clock.
   *  Guessing a lifetime for it would make the server assert a freshness it
   *  cannot know, and the failure mode is a client serving stale data with no
   *  way to tell. Servers that DO know set it per-registration. */
  val DefaultReadTtlMs: Long = 0

  /** Cache hints for `method`, or `None` when it is not a cacheable operation.
   *
   *  `authenticated` decides the scope, and it is the one bit of information
   *  we actually have rather than a guess: with a token validator registered,
   *  every result is produced in some caller's authorization context and
   *  MUST NOT be shared across contexts, so `private`. With auth off there is
   *  only one context and the catalogue is identical for everyone, so
   *  `public`. Over-sharing here leaks between callers, so the ambiguous case
   *  resolves to `private`. */
  def cacheHintsFor(method: String, authenticated: Boolean, listTtlMs: Long = DefaultListTtlMs,
                    readTtlMs: Long = DefaultReadTtlMs): Option[CacheHints] =
    if !CacheableMethods.contains(method) then None
    else
      val scope = if authenticated then "private" else "public"
      val ttl   = if method == Method.ResourcesRead then readTtlMs else listTtlMs
      Some(CacheHints(ttl, scope))

  /** `server/discover` result — supported versions, capabilities, identity.
   *  Reuses the capability block the legacy `initialize` result advertises
   *  so the two can never disagree about what this server can do.
   *
   *  `instructions` is omitted (we have no server-level instructions field);
   *  `ttlMs`/`cacheScope` arrive with the rest of `CacheableResult` in P2. */
  def discoverResult(serverName: String, serverVersion: String): ujson.Value =
    val capabilities = initializeResult(serverName, serverVersion)("capabilities")
    stampComplete(
      ujson.Obj(
        "supportedVersions" -> ujson.Arr.from(SupportedProtocolVersions.map(ujson.Str(_))),
        "capabilities"      -> capabilities
      ),
      serverName, serverVersion
    )

  /** `initialize` result — what the server tells the client about itself
   *  and which capabilities it offers.  All three primitive categories
   *  advertise `listChanged: true`; resources also `subscribe: true`;
   *  `logging: {}` flags client→server log-level control + matching
   *  server→client `notifications/message` push. */
  def initializeResult(
    serverName:    String,
    serverVersion: String,
    requested:     Option[String] = None
  ): ujson.Value =
    ujson.Obj(
      "protocolVersion" -> negotiateLegacyVersion(requested),
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
        t.title.foreach       (s => o("title")        = s)
        t.description.foreach (d => o("description")  = d)
        o("inputSchema") = t.inputSchema
        t.outputSchema.foreach(s => o("outputSchema") = s)
        t.annotations.filterNot(_.isEmpty).foreach(a => o("annotations") = a.toJson)
        t.meta.filter(metaNonEmpty).foreach(m => o("_meta") = m)
        o
      })
    )
    nextCursor.foreach(c => obj("nextCursor") = c)
    obj

  /** `tools/call` result: `{content: [...], isError: bool,
   *  structuredContent?: ...}`.  `content` is the list of `Content`
   *  records (text / image / audio / resource refs / resource_link)
   *  that humans render.  `structuredContent` is the optional typed
   *  payload that matches the tool's declared `outputSchema` — clients
   *  prefer it when present for machine-readable downstream use. */
  def toolsCallResult(
    content:           List[ujson.Value],
    isError:           Boolean,
    structuredContent: Option[ujson.Value] = None
  ): ujson.Value =
    val obj = ujson.Obj(
      "content" -> ujson.Arr.from(content),
      "isError" -> isError
    )
    structuredContent.foreach(s => obj("structuredContent") = s)
    obj

  def resourcesListResult(resources: List[ResourceEntry], nextCursor: Option[String] = None): ujson.Value =
    val obj = ujson.Obj(
      "resources" -> ujson.Arr.from(resources.map { r =>
        val o = ujson.Obj("uri" -> r.uri)
        r.name.foreach(n     => o("name")     = n)
        r.title.foreach(t    => o("title")    = t)
        r.mimeType.foreach(m => o("mimeType") = m)
        r.annotations.filterNot(_.isEmpty).foreach(a => o("annotations") = a.toJson)
        r.meta.filter(metaNonEmpty).foreach(m => o("_meta") = m)
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
        t.title.foreach(s       => o("title")       = s)
        t.description.foreach(d => o("description") = d)
        t.mimeType.foreach(m    => o("mimeType")    = m)
        t.annotations.filterNot(_.isEmpty).foreach(a => o("annotations") = a.toJson)
        t.meta.filter(metaNonEmpty).foreach(m => o("_meta") = m)
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
        p.title.foreach      (t => o("title")       = t)
        p.description.foreach(d => o("description") = d)
        if p.arguments.nonEmpty then
          o("arguments") = ujson.Arr.from(p.arguments.map { a =>
            ujson.Obj(
              "name"        -> a.name,
              "description" -> a.description,
              "required"    -> a.required
            )
          })
        p.meta.filter(metaNonEmpty).foreach(m => o("_meta") = m)
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
    annotations: Option[ToolAnnotations] = None,
    /** v1.17.x — MCP generic `_meta` field: implementation-defined
     *  metadata.  Per spec, MAY be attached to any object; clients
     *  ignore keys they don't recognise. */
    meta:        Option[ujson.Value]     = None,
    /** v1.17.x late-2025: human-readable display title.  Distinct from
     *  the machine name, distinct from `annotations.title` (clients
     *  may prefer the entry-level field when both are set). */
    title:       Option[String]          = None,
    /** v1.17.x late-2025: JSON Schema describing the structured shape
     *  the tool's `structuredContent` will conform to.  Optional —
     *  unstructured `content` tools omit it. */
    outputSchema: Option[ujson.Value]    = None
  )
  case class ResourceEntry(
    uri:         String,
    name:        Option[String],
    mimeType:    Option[String],
    annotations: Option[ResourceAnnotations] = None,
    meta:        Option[ujson.Value]         = None,
    title:       Option[String]              = None
  )
  case class ResourceTemplateEntry(
    uriTemplate: String,
    name:        Option[String],
    description: Option[String],
    mimeType:    Option[String],
    annotations: Option[ResourceAnnotations] = None,
    meta:        Option[ujson.Value]         = None,
    title:       Option[String]              = None
  )
  case class PromptEntry(
    name:        String,
    description: Option[String],
    arguments:   List[PromptArgument],
    meta:        Option[ujson.Value] = None,
    title:       Option[String]      = None
  )
  case class PromptArgument(name: String, description: String, required: Boolean)

  /** True iff a `_meta` payload has content worth emitting on the wire.
   *  Empty objects collapse to suppressed (matches annotation-emission
   *  policy for tool/resource hints). */
  private def metaNonEmpty(m: ujson.Value): Boolean = m match
    case obj: ujson.Obj => obj.value.nonEmpty
    case _              => false

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

  /** v1.17.x — late-2025 MCP additions:
   *
   *  - `audio` content: base64-encoded audio bytes + mimeType, parallel
   *    shape to imageContent.  Surfaces voice / audio model outputs.
   *  - `resource_link` content: lightweight reference to a known
   *    resource (uri + optional name/description/mimeType) — clients
   *    look up content via resources/read instead of inlining.
   *    Avoids ballooning tool result payloads with large blobs. */
  def audioContent(data: String, mimeType: String): ujson.Value =
    ujson.Obj("type" -> "audio", "data" -> data, "mimeType" -> mimeType)

  def resourceLinkContent(
    uri:         String,
    name:        Option[String] = None,
    description: Option[String] = None,
    mimeType:    Option[String] = None
  ): ujson.Value =
    val obj = ujson.Obj("type" -> "resource_link", "uri" -> uri)
    name.foreach       (n => obj("name")        = n)
    description.foreach(d => obj("description") = d)
    mimeType.foreach   (m => obj("mimeType")    = m)
    obj
