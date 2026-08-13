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
    // DEPRECATED by MCP 2026-07-28 (SEP-2577). Still fully functional for the
    // 12-month window and still served, but NEW code should not reach for it:
    // pass directories or files as tool parameters, resource URIs, or server
    // configuration instead. `notifications/roots/list_changed` is REMOVED in
    // the modern era outright, not merely deprecated.
    //
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
    // MCP 2026-07-28 — one long-lived request replaces both `resources/subscribe`
    // and the GET stream. Its RESPONSE is the stream.
    val SubscriptionsListen       = "subscriptions/listen"
    val SubscriptionsAcknowledged = "notifications/subscriptions/acknowledged"

    // ── Tasks extension `io.modelcontextprotocol/tasks` (P5c) ────────────
    // Polling, not blocking: `tasks/get` is the primary mechanism and there is
    // deliberately NO `tasks/list` — a task id is a capability, and an
    // enumeration endpoint would hand out every one of them.
    val TasksGet    = "tasks/get"
    val TasksUpdate = "tasks/update"
    val TasksCancel = "tasks/cancel"
    /** Optional push, when the client opted in through `subscriptions/listen`.
     *  Polling stays authoritative; this only saves latency. */
    val TasksNotification = "notifications/tasks"

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

    /** MRTR: the client came back with a `requestState` naming a parked handler
     *  this process does not have — a different instance, a restart, or an
     *  expired park.
     *
     *  This error is the WHOLE reason a parked thread can be the default. The
     *  alternative to answering it is to silently re-run the handler, which
     *  duplicates every effect it had already performed before asking; that is
     *  a real mode (`ParkThenReplay`) and it is opt-in precisely because it
     *  trades this loud failure for a quiet one. */
    val InputSessionNotFound = -32023

  /** Syslog levels per MCP spec, ordered by severity (low to high).
   *
   *  DEPRECATED FEATURE (MCP 2026-07-28, SEP-2577). Logging survives the
   *  deprecation window and the legacy path still serves `logging/setLevel`,
   *  but the modern era removed that method — the level rides per-request in
   *  `_meta` as `io.modelcontextprotocol/logLevel`. New code should log to
   *  stderr on stdio, or use OpenTelemetry. */
  val LogLevels: List[String] = List(
    "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency"
  )

  /** Numeric rank of a log level (-1 if unknown).  Levels >= rank pass
   *  through `notifyMessage`; lower ranks are filtered. */
  /** NOTIFICATIONS the modern era deleted (MCP 2026-07-28).
   *
   *  Kept apart from `RemovedInModern` because the two are enforced in
   *  different places by necessity: a removed request is answered with
   *  `MethodNotFound`, while a removed notification has no response at all —
   *  the only refusal available is to not act on it. */
  val RemovedNotificationsInModern: Set[String] = Set(Method.RootsListChanged)

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

  // ─── MCP 2026-07-28 — Streamable-HTTP request metadata headers ──────

  /** Header names the transport mirrors selected body fields into, so an
   *  intermediary can route without parsing the body.  RFC 9110 makes field
   *  NAMES case-insensitive — comparisons must be too — while the VALUES
   *  here are case-sensitive. */
  object Header:
    val ProtocolVersion = "MCP-Protocol-Version"
    val Method          = "Mcp-Method"
    val Name            = "Mcp-Name"
    val ParamPrefix     = "Mcp-Param-"

  /** The three methods whose `Mcp-Name` is required, and where its value
   *  comes from in the body. */
  val NameHeaderSource: Map[String, String] = Map(
    Method.ToolsCall      -> "name",
    Method.PromptsGet     -> "name",
    Method.ResourcesRead  -> "uri"
  )

  /** Decode the `=?base64?…?=` sentinel a client uses when a value cannot be
   *  carried as plain ASCII.  A value not in that form is returned as-is —
   *  the sentinel is exact and case-sensitive by spec, so anything else is
   *  a literal.  Servers MUST decode before comparing to the body. */
  def decodeHeaderValue(v: String): String =
    if v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11 then
      try String(java.util.Base64.getDecoder.decode(v.substring(9, v.length - 2)), "UTF-8")
      catch case _: Throwable => v      // malformed payload: compare literally, and fail loudly
    else v

  /** Encode for a header, using the sentinel only when the value cannot ride
   *  as plain ASCII — which per RFC 9110 means visible ASCII, space and tab,
   *  with no leading or trailing whitespace.  A plain value that happens to
   *  LOOK like the sentinel must also be encoded, or a server would decode
   *  something the client never encoded. */
  def encodeHeaderValue(v: String): String =
    val plain = v.nonEmpty && v.forall(c => c == '\t' || (c >= 0x20 && c <= 0x7E)) &&
                v == v.trim && !(v.startsWith("=?base64?") && v.endsWith("?="))
    if plain then v
    else "=?base64?" + java.util.Base64.getEncoder.encodeToString(v.getBytes("UTF-8")) + "?="

  // ─── MCP 2026-07-28 — which era does this server speak? ────────────

  /** A server speaks one of two eras, and a client must find out before it can
   *  say anything. The spec calls them modern and legacy; `Unknown` is only the
   *  state before the probe. */
  enum Era:
    case Unknown, Modern, Legacy

  /** The error codes that IDENTIFY a modern server even while refusing us.
   *
   *  This is the subtle half of the probe: a modern server may answer the
   *  probe with an ERROR — unsupported version, missing capability, header
   *  mismatch — and that error is still proof it speaks the revision. Treating
   *  every error as "legacy" would fall back to `initialize` against a server
   *  that has no such method. */
  val ModernErrorCodes: Set[Int] = Set(
    ErrorCode.UnsupportedProtocolVersion,
    ErrorCode.MissingRequiredClientCapability,
    ErrorCode.HeaderMismatch)

  /** Decide the era from a `server/discover` probe (the stdio rule).
   *
   *  A result means modern. A RECOGNISED modern error also means modern —
   *  the server refused this particular request while proving it speaks the
   *  revision. Anything else — `MethodNotFound`, a parse error, a timeout —
   *  means legacy, because a legacy server does not implement
   *  `server/discover` and will say so in its own way. */
  def eraFromProbe(probe: Either[JsonRpc.Error, ujson.Value]): Era = probe match
    case Right(_)                                       => Era.Modern
    case Left(e) if ModernErrorCodes.contains(e.code)   => Era.Modern
    case Left(_)                                        => Era.Legacy

  /** Decide the era from an HTTP attempt (the Streamable-HTTP rule).
   *
   *  A 2xx is modern. On a 4xx the BODY decides, and that is the whole point:
   *  a modern server uses 400 for its own errors, so status alone cannot
   *  distinguish "you asked wrongly" from "I have never heard of this". Only a
   *  recognised modern JSON-RPC error in the body identifies the era. */
  def eraFromHttp(status: Int, body: String): Era =
    if status >= 200 && status < 300 then Era.Modern
    else
      val code =
        try ujson.read(body.trim).obj.get("error").flatMap(_.objOpt)
              .flatMap(_.get("code")).flatMap(_.numOpt).map(_.toInt)
        catch case _: Throwable => None
      code match
        case Some(c) if ModernErrorCodes.contains(c) => Era.Modern
        case _                                       => Era.Legacy

  /** The client-side negotiation, in ONE place.
   *
   *  This existed three times — `McpClientCore`, `McpWsClient`, `McpHttpClient` —
   *  and the three bodies differed only in how the era is DECIDED. Two of them
   *  were byte-identical apart from whitespace. That is the shape that produced
   *  the P1b trap: one rule with several decision sites, where a spec change has
   *  to be applied N times and the copy nobody can execute is the one that rots.
   *  The WS copy was exactly that — unreachable from any test, because the JDK
   *  ships a WebSocket client and no server.
   *
   *  Parameterising the decision leaves each client with a one-line supplier and
   *  no branching of its own, so this function is where the behaviour lives and
   *  where it is tested.
   *
   *  `decideEra` is the only transport-specific part: stdio and WS issue
   *  `server/discover` and read the JSON-RPC answer, while HTTP must look at the
   *  raw status and body (its `request` collapses both into one opaque error).
   *
   *  A failed legacy handshake is REPORTED and leaves the era unsettled, so a
   *  later attempt retries rather than caching a state that was never reached. */
  def negotiateEra(
    clientName:      String,
    clientVersion:   String,
    decideEra:       () => Era,
    sendInitialize:  ujson.Value => Either[JsonRpc.Error, ujson.Value],
    sendInitialized: () => Unit
  ): Either[JsonRpc.Error, Era] =
    val decided = decideEra()
    if decided != Era.Legacy then Right(decided)
    else
      // Only here, and only now: `initialize` does not exist in the modern
      // revision, so sending it before the era is known is what breaks a client
      // against a modern-only server.
      sendInitialize(ujson.Obj(
        "protocolVersion" -> ProtocolVersion,
        "capabilities"    -> ujson.Obj(),
        "clientInfo"      -> ujson.Obj("name" -> clientName, "version" -> clientVersion))) match
        case Right(_) => sendInitialized(); Right(decided)
        case Left(e)  => Left(e)

  // ─── MCP 2026-07-28 — the CLIENT side of the envelope ──────────────

  /** The `_meta` a modern request must carry.
   *
   *  `protocolVersion` and `clientCapabilities` are REQUIRED on every request —
   *  a server rejects their absence with `-32602` — while `clientInfo` is a
   *  SHOULD. So capabilities default to an empty object rather than being
   *  omitted: an empty object means "I declare none", which is a statement,
   *  whereas leaving the key out is a malformed request. */
  def clientMeta(
    clientName:    String,
    clientVersion: String,
    capabilities:  ujson.Value    = ujson.Obj(),
    logLevel:      Option[String] = None
  ): ujson.Obj =
    val m = ujson.Obj(
      MetaKey.ProtocolVersion    -> ModernProtocolVersion,
      MetaKey.ClientInfo         -> ujson.Obj("name" -> clientName, "version" -> clientVersion),
      MetaKey.ClientCapabilities -> capabilities)
    logLevel.foreach(l => m(MetaKey.LogLevel) = l)
    m

  /** Merge the client `_meta` into a request's params.
   *
   *  Merges rather than replaces because `_meta` is a SHARED namespace: a caller
   *  may already have put `progressToken` there to opt into progress
   *  notifications, and overwriting it would silently switch that off. Keys we
   *  own win; everything else survives. */
  def withClientMeta(params: ujson.Value, meta: ujson.Obj): ujson.Value =
    val obj = params match
      case o: ujson.Obj => o
      case _            => ujson.Obj()
    val existing = obj.value.get("_meta") match
      case Some(m: ujson.Obj) => m
      case _                  => ujson.Obj()
    meta.value.foreach((k, v) => existing(k) = v)
    obj("_meta") = existing
    obj

  /** The standard headers a modern client MUST mirror onto a POST.
   *
   *  `Mcp-Name` only for the three methods that have a name to mirror, and
   *  encoded through the sentinel when it cannot ride as plain ASCII — the
   *  server decodes before comparing, so an unencoded non-ASCII name would be
   *  a mismatch against its own body. */
  def mirroredHeaders(method: String, params: ujson.Value): Map[String, String] =
    val base = Map(
      Header.ProtocolVersion -> ModernProtocolVersion,
      Header.Method          -> method)
    NameHeaderSource.get(method).flatMap { field =>
      try params.objOpt.flatMap(_.get(field)).flatMap(_.strOpt) catch case _: Throwable => None
    } match
      case Some(name) => base + (Header.Name -> encodeHeaderValue(name))
      case None       => base

  /** The `Mcp-Param-{Name}` headers for a `tools/call`, read off the tool's
   *  `x-mcp-header` annotations.
   *
   *  Returns empty for a schema that violates the annotation rules. A
   *  conforming CLIENT is required to exclude such a tool from `tools/list`
   *  entirely, so it should never be calling one — emitting no headers rather
   *  than guessing is the conservative half of that. */
  def mirroredParamHeaders(inputSchema: ujson.Value, arguments: ujson.Value): Map[String, String] =
    xMcpHeaderParams(inputSchema) match
      case Left(_)       => Map.empty
      case Right(params) =>
        params.iterator.flatMap { ph =>
          valueAt(arguments, ph.path).flatMap(headerTextOf)
            .map(v => (Header.ParamPrefix + ph.headerName) -> encodeHeaderValue(v))
        }.toMap

  /** Validate the mirrored headers against the body they claim to describe.
   *  Returns `Some(message)` naming the first violation, or `None` when they
   *  agree.
   *
   *  Only applies to MODERN requests. A legacy client never sent these
   *  headers and its revision never defined them, so rejecting it for their
   *  absence would break the era we promised to keep serving — the spec
   *  allows exactly this reading, letting a server treat a header-less
   *  request as `2025-03-26`.
   *
   *  The point of the check is not tidiness: a load balancer may route on
   *  the header while the server executes the body, so a request whose two
   *  copies disagree is a request two components will act on differently. */
  def validateRequestHeaders(
    headers: Map[String, String],
    method:  String,
    params:  ujson.Value,
    ctx:     RequestContext
  ): Option[String] =
    if !ctx.isModern then None
    else
      val lower = headers.map((k, v) => k.toLowerCase -> v)
      def get(name: String): Option[String] = lower.get(name.toLowerCase)
      val bodyVersion = ctx.protocolVersion.getOrElse("")
      get(Header.ProtocolVersion) match
        case None =>
          Some(s"missing required header ${Header.ProtocolVersion}")
        case Some(h) if h != bodyVersion =>
          Some(s"${Header.ProtocolVersion} header '$h' does not match body value '$bodyVersion'")
        case _ =>
          get(Header.Method) match
            case None =>
              Some(s"missing required header ${Header.Method}")
            case Some(h) if h != method =>
              Some(s"${Header.Method} header '$h' does not match body value '$method'")
            case _ =>
              NameHeaderSource.get(method) match
                case None => None      // this method mirrors no name
                case Some(field) =>
                  val bodyName = try params.objOpt.flatMap(_.get(field)).flatMap(_.strOpt) catch case _: Throwable => None
                  (get(Header.Name).map(decodeHeaderValue), bodyName) match
                    case (None, Some(_))            => Some(s"missing required header ${Header.Name}")
                    case (Some(h), Some(b)) if h != b =>
                      Some(s"${Header.Name} header '$h' does not match body value '$b'")
                    case _ => None

  // ─── MCP 2026-07-28 — x-mcp-header (Mcp-Param-{Name}) ──────────────

  /** One tool parameter a server has asked to see mirrored into a header.
   *  `path` is the chain of `properties` keys from the schema root to it. */
  case class ParamHeader(headerName: String, path: List[String])

  /** RFC 9110 §5.1 `tchar` — the characters a header field-name may contain. */
  private val TChar: Set[Char] =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toSet ++ "!#$%&'*+-.^_`|~".toSet

  /** Collect the `x-mcp-header` annotations in a tool's `inputSchema`.
   *
   *  Returns `Left(reason)` when the schema violates the annotation rules, and
   *  a rejection is the CORRECT outcome rather than a nuisance: the spec makes
   *  a conforming client exclude such a tool from `tools/list` entirely, so a
   *  server that emits one has published a tool nobody may call. Better to
   *  find that here than to have every client silently drop it.
   *
   *  The traversal only follows `properties` chains — never `items`, never a
   *  composition or conditional keyword, never `$ref`. That restriction is the
   *  spec's, and the reason is that a header must be extractable from the
   *  arguments by a fixed path; anything reachable only through `oneOf` or an
   *  array index has no single path to read. */
  def xMcpHeaderParams(inputSchema: ujson.Value): Either[String, List[ParamHeader]] =
    val found = scala.collection.mutable.ListBuffer.empty[ParamHeader]
    var error: Option[String] = None

    def walk(node: ujson.Value, path: List[String]): Unit =
      if error.isEmpty then node match
        case obj: ujson.Obj =>
          obj.value.get("properties").flatMap(_.objOpt).foreach { props =>
            props.foreach { (key, sub) =>
              if error.isEmpty then
                val here = path :+ key
                sub.objOpt.foreach { s =>
                  s.get("x-mcp-header").foreach { ann =>
                    ann.strOpt match
                      case None => error = Some(s"x-mcp-header on '${here.mkString(".")}' is not a string")
                      case Some(name) if name.isEmpty =>
                        error = Some(s"x-mcp-header on '${here.mkString(".")}' is empty")
                      case Some(name) if !name.forall(TChar.contains) =>
                        error = Some(s"x-mcp-header '$name' is not a valid HTTP field-name token")
                      case Some(name) =>
                        val ty = s.get("type").flatMap(_.strOpt).getOrElse("")
                        // `number` is excluded BY NAME in the spec, not as an oversight:
                        // a float has no single canonical string form, so header and body
                        // could never be compared without a rounding rule.
                        if ty == "number" then
                          error = Some(s"x-mcp-header '$name' is on a `number` parameter, which is not permitted")
                        else if !Set("string", "integer", "boolean").contains(ty) then
                          error = Some(s"x-mcp-header '$name' is on type '$ty'; only string, integer and boolean may be mirrored")
                        else if found.exists(_.headerName.equalsIgnoreCase(name)) then
                          error = Some(s"x-mcp-header '$name' is not case-insensitively unique")
                        else found += ParamHeader(name, here)
                  }
                  walk(sub, here)   // nested objects are fine — every step is a `properties` key
                }
            }
          }
        case _ => ()

    try walk(inputSchema, Nil) catch case e: Throwable => error = Some(s"malformed inputSchema: ${e.getMessage}")
    error.toLeft(found.toList)

  /** Read the value at a `properties` path out of the call arguments. */
  private def valueAt(args: ujson.Value, path: List[String]): Option[ujson.Value] =
    path.foldLeft(Option(args)) { (cur, key) =>
      cur.flatMap(_.objOpt).flatMap(_.get(key))
    }.filterNot(_ == ujson.Null)

  /** The canonical header text for a mirrored value, before sentinel encoding.
   *  Integers are decimal, booleans lowercase — the spec fixes both. */
  private def headerTextOf(v: ujson.Value): Option[String] = v match
    case ujson.Str(s)  => Some(s)
    case ujson.Bool(b) => Some(if b then "true" else "false")
    case ujson.Num(n)  => Some(if n == n.floor && !n.isInfinite then n.toLong.toString else n.toString)
    case _             => None

  /** Validate the `Mcp-Param-{Name}` headers of a `tools/call` against its
   *  arguments.
   *
   *  The asymmetry is the spec's and it matters: a value PRESENT in the body
   *  must have its header, a value absent or null must NOT, and either
   *  violation is `-32020`. A client that quietly omits a header while sending
   *  the value is non-conforming precisely because an intermediary routing on
   *  that header would then route on nothing. */
  def validateParamHeaders(
    headers:     Map[String, String],
    inputSchema: ujson.Value,
    arguments:   ujson.Value
  ): Option[String] =
    xMcpHeaderParams(inputSchema) match
      case Left(why) => Some(s"tool inputSchema is invalid: $why")
      case Right(Nil) => None
      case Right(params) =>
        val lower = headers.map((k, v) => k.toLowerCase -> v)
        params.iterator.map { p =>
          val hName = Header.ParamPrefix + p.headerName
          val sent  = lower.get(hName.toLowerCase).map(decodeHeaderValue)
          val body  = valueAt(arguments, p.path).flatMap(headerTextOf)
          (sent, body) match
            case (None, None)                 => None
            case (Some(h), None)              => Some(s"$hName sent as '$h' but the argument is absent")
            case (None, Some(b))              => Some(s"$hName is missing though the argument is present as '$b'")
            case (Some(h), Some(b)) if h != b => Some(s"$hName header '$h' does not match body value '$b'")
            case _                            => None
        }.collectFirst { case Some(why) => why }

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

  // ─── MCP 2026-07-28 — subscriptions/listen ─────────────────────────

  /** Which change notifications a client asked for.
   *
   *  Every field is opt-IN and the server **MUST NOT** send a type the client
   *  did not request — so an all-false filter is a legitimate subscription that
   *  delivers nothing but its own acknowledgement, not a malformed one. */
  case class NotificationFilter(
    toolsListChanged:      Boolean      = false,
    promptsListChanged:    Boolean      = false,
    resourcesListChanged:  Boolean      = false,
    resourceSubscriptions: List[String] = Nil,
    /** Tasks extension: the task ids this stream wants pushed to it.
     *
     *  Keyed by ID rather than by a blanket "all my tasks" flag, which is what
     *  the extension specifies and also the only shape that is safe here: a
     *  listen stream has no owner the server can check a task against, so a
     *  blanket flag would put every task's state onto every stream. */
    taskIds: List[String] = Nil
  ):
    def isEmpty: Boolean =
      !toolsListChanged && !promptsListChanged && !resourcesListChanged &&
        resourceSubscriptions.isEmpty && taskIds.isEmpty

    def toJson: ujson.Value =
      val o = ujson.Obj()
      if toolsListChanged     then o("toolsListChanged")     = true
      if promptsListChanged   then o("promptsListChanged")   = true
      if resourcesListChanged then o("resourcesListChanged") = true
      if resourceSubscriptions.nonEmpty then
        o("resourceSubscriptions") = ujson.Arr.from(resourceSubscriptions.map(ujson.Str(_)))
      if taskIds.nonEmpty then
        o("taskIds") = ujson.Arr.from(taskIds.map(ujson.Str(_)))
      o

    /** Does this filter admit `method` (for a resource update, `uri`)?
     *  The one place that decides what may go out on a stream. */
    def admits(method: String, uri: Option[String] = None): Boolean = method match
      case Method.ToolsListChanged     => toolsListChanged
      case Method.PromptsListChanged   => promptsListChanged
      case Method.ResourcesListChanged => resourcesListChanged
      case Method.ResourcesUpdated     => uri.exists(resourceSubscriptions.contains)
      // The tasks extension reuses this parameter for the task id: same
      // question — "is THIS one of the things you asked for" — so the same slot.
      case Method.TasksNotification    => uri.exists(taskIds.contains)
      case _                           => false

  /** Read the filter off a `subscriptions/listen` request. Absent or malformed
   *  reads as the empty filter — nothing is delivered, which is the safe
   *  direction: the failure mode of guessing wrong here is sending a client
   *  notifications it never asked for. */
  def parseNotificationFilter(params: ujson.Value): NotificationFilter =
    try
      params.objOpt.flatMap(_.get("notifications")).flatMap(_.objOpt) match
        case None => NotificationFilter()
        case Some(n) =>
          def flag(k: String) = n.get(k).flatMap(_.boolOpt).getOrElse(false)
          val uris = n.get("resourceSubscriptions").flatMap(_.arrOpt)
            .map(_.iterator.flatMap(_.strOpt).toList).getOrElse(Nil)
          val tids = n.get("taskIds").flatMap(_.arrOpt)
            .map(_.iterator.flatMap(_.strOpt).toList).getOrElse(Nil)
          NotificationFilter(flag("toolsListChanged"), flag("promptsListChanged"),
                             flag("resourcesListChanged"), uris, tids)
    catch case _: Throwable => NotificationFilter()

  /** Stamp a notification with the subscription it belongs to.
   *
   *  Mandatory on every message delivered on a listen stream: on stdio all
   *  subscriptions share one channel, so without this a client cannot tell
   *  which of its streams a notification came from. Merges into an existing
   *  `_meta` rather than replacing it. */
  def tagSubscription(notification: ujson.Value, subscriptionId: ujson.Value): ujson.Value =
    notification match
      case obj: ujson.Obj =>
        val params = obj.value.get("params") match
          case Some(p: ujson.Obj) => p
          case _                  => ujson.Obj()
        val meta = params.value.get("_meta") match
          case Some(m: ujson.Obj) => m
          case _                  => ujson.Obj()
        meta(MetaKey.SubscriptionId) = subscriptionId
        params("_meta") = meta
        obj("params")   = params
        obj
      case other => other

  /** The acknowledgement, which the server MUST send FIRST on a subscription
   *  and before any notification belonging to it.
   *
   *  `notifications` echoes the subset actually honoured — a type the server
   *  does not support is omitted rather than silently accepted, so a client can
   *  compare what it asked for against what it will get. */
  def subscriptionsAcknowledged(subscriptionId: ujson.Value, honoured: NotificationFilter): String =
    JsonRpc.encodeNotification(Method.SubscriptionsAcknowledged,
      tagSubscription(
        ujson.Obj("params" -> ujson.Obj("notifications" -> honoured.toJson)),
        subscriptionId)("params"))

  /** The graceful-closure response to the long-lived request itself: an empty
   *  result carrying the subscription id.
   *
   *  Its ABSENCE is the signal — a stream that drops without this told the
   *  client nothing, and the client may reconnect. So this is emitted on an
   *  orderly teardown and never on a transport failure. */
  def subscriptionClosedResult(subscriptionId: ujson.Value): ujson.Value =
    ujson.Obj(
      "resultType" -> ResultTypeComplete,
      "_meta"      -> ujson.Obj(MetaKey.SubscriptionId -> subscriptionId))

  // ─── MCP 2026-07-28 — Multi Round-Trip Requests (MRTR) ─────────────

  /** `resultType` values the core protocol defines. An unrecognised value is
   *  invalid to a client, so these are the only two we may emit. */
  val ResultTypeComplete      = "complete"
  val ResultTypeInputRequired = "input_required"

  /** Added by the Tasks EXTENSION, not by the core revision — which is why the
   *  core `resultType` set stays two values and this one is only legitimate
   *  once the client has advertised the extension. The spec is explicit that a
   *  `resultType` the client does not recognise is invalid, so emitting this
   *  to a client that never declared support is a protocol error on our side,
   *  not a nicety. */
  val ResultTypeTask = "task"

  // ── Tasks extension `io.modelcontextprotocol/tasks` (P5c) ───────────────

  /** The extension's identifier, used both as a capability key and as the
   *  `_meta` prefix its own keys live under. */
  val TasksExtension = "io.modelcontextprotocol/tasks"

  /** Task lifecycle. Two states are non-terminal and three are terminal, and
   *  the distinction is the whole polling contract: a client keeps calling
   *  `tasks/get` until it sees a terminal one. */
  val TaskWorking       = "working"
  val TaskInputRequired = "input_required"
  val TaskCompleted     = "completed"
  val TaskFailed        = "failed"
  val TaskCancelled     = "cancelled"

  val TerminalTaskStatuses: Set[String] =
    Set(TaskCompleted, TaskFailed, TaskCancelled)

  def isTerminalTaskStatus(status: String): Boolean =
    TerminalTaskStatuses.contains(status)

  /** Did this request's client advertise the Tasks extension?
   *
   *  Read per request, like everything else in this revision: there is no
   *  session to remember it in, and a server that assumed otherwise would be
   *  answering the SECOND client with the FIRST one's capabilities. */
  def clientSupportsTasks(ctx: RequestContext): Boolean =
    ctx.clientCapabilities
      .flatMap(_.objOpt).flatMap(_.get("extensions"))
      .flatMap(_.objOpt).exists(_.contains(TasksExtension))

  /** The error a server owes a client whose capabilities do not cover a task.
   *
   *  `-32021`, from the core revision's reserved range, and NOT the `-32003`
   *  that the extension's own page shows: that value sits in the `-32000`..
   *  `-32019` legacy sub-range the core spec forbids new implementations from
   *  using. Where two pages of one specification disagree, the core one that
   *  defines the allocation policy wins. */
  def missingTasksCapability(): ujson.Value =
    ujson.Obj("requiredCapabilities" -> ujson.Obj(
      "extensions" -> ujson.Obj(TasksExtension -> ujson.Obj())))

  /** The handle a `tools/call` returns instead of a result when the server
   *  decides to make the call a task. The server is the SOLE decider — the
   *  revision gives a client no way to ask — so this is built from what the
   *  handler chose, never from anything in the request. */
  def createTaskResult(
    taskId:         String,
    status:         String = TaskWorking,
    ttlMs:          Option[Long] = None,
    pollIntervalMs: Option[Long] = None
  ): ujson.Value =
    val o = ujson.Obj("resultType" -> ResultTypeTask, "taskId" -> taskId, "status" -> status)
    ttlMs.foreach(v => o("ttlMs") = ujson.Num(v.toDouble))
    pollIntervalMs.foreach(v => o("pollIntervalMs") = ujson.Num(v.toDouble))
    o

  /** The full task, as `tasks/get` returns it.
   *
   *  `result` and `error` and `inputRequests` are mutually exclusive by state,
   *  and this builder enforces that rather than trusting the caller: a task
   *  carrying both a result and an error would be unreadable by any client. */
  def taskResult(
    taskId:         String,
    status:         String,
    createdAt:      String,
    lastUpdatedAt:  String,
    statusMessage:  Option[String]              = None,
    ttlMs:          Option[Long]                = None,
    pollIntervalMs: Option[Long]                = None,
    result:         Option[ujson.Value]         = None,
    error:          Option[JsonRpc.Error]       = None,
    inputRequests:  Map[String, InputRequest]   = Map.empty
  ): ujson.Value =
    require(!(result.isDefined && error.isDefined),
      "a task carries a result or an error, never both")
    require(status != TaskCompleted || result.isDefined,
      "a completed task must carry its result")
    require(status != TaskFailed || error.isDefined,
      "a failed task must carry its error")
    require(status != TaskInputRequired || inputRequests.nonEmpty,
      "an input_required task must say what input it needs")
    val o = ujson.Obj(
      "resultType"    -> ResultTypeTask,
      "taskId"        -> taskId,
      "status"        -> status,
      "createdAt"     -> createdAt,
      "lastUpdatedAt" -> lastUpdatedAt)
    statusMessage.foreach(s => o("statusMessage") = s)
    ttlMs.foreach(v => o("ttlMs") = ujson.Num(v.toDouble))
    pollIntervalMs.foreach(v => o("pollIntervalMs") = ujson.Num(v.toDouble))
    result.foreach(r => o("result") = r)
    error.foreach(e => o("error") =
      ujson.Obj("code" -> ujson.Num(e.code.toDouble), "message" -> e.message))
    if inputRequests.nonEmpty then
      o("inputRequests") = ujson.Obj.from(inputRequests.map { (k, r) =>
        k -> (ujson.Obj("method" -> r.method, "params" -> r.params): ujson.Value)
      })
    o

  /** `taskId` out of a tasks-extension request. */
  def parseTaskId(params: ujson.Value): Option[String] =
    try params.objOpt.flatMap(_.get("taskId")).flatMap(_.strOpt)
    catch case _: Throwable => None

  /** The answers on a `tasks/update`.
   *
   *  Shaped `{key: {value: …}}` rather than MRTR's `{key: …}` — the same
   *  question answered through a different door, and the wrapper is the
   *  difference. Unwrapped here so the machinery below sees one shape. */
  def parseTaskInputs(params: ujson.Value): Map[String, ujson.Value] =
    try
      params.objOpt.flatMap(_.get("inputs")).flatMap(_.objOpt)
        .map(_.toMap.map { (k, v) =>
          k -> v.objOpt.flatMap(_.get("value")).getOrElse(v)
        }).getOrElse(Map.empty)
    catch case _: Throwable => Map.empty

  /** One server→client request, carried INSIDE a result instead of being sent
   *  as its own JSON-RPC request. That inversion is the whole of MRTR. */
  case class InputRequest(method: String, params: ujson.Value)

  /** The three client requests a server may answer with an `InputRequiredResult`.
   *  The spec says MUST NOT on anything else, so this is a closed set rather
   *  than a hint — answering `tools/list` with one would be a protocol error. */
  val MrtrCapableMethods: Set[String] =
    Set(Method.PromptsGet, Method.ResourcesRead, Method.ToolsCall)

  /** Build an `InputRequiredResult`.
   *
   *  `requestState` is opaque to the client and **attacker-controlled on the way
   *  back**: the spec requires integrity protection whenever it influences
   *  authorization, resource access or business logic, and replay defences
   *  (principal, TTL, originating-request identifier) inside the protected
   *  payload. This builder deliberately does not invent a format — it carries
   *  whatever the caller minted, so the protection lives with the code that
   *  knows what the state means.
   *
   *  Throws when both fields are empty: the spec requires at least one, and a
   *  result asking for nothing while claiming input is required would leave a
   *  conforming client retrying an identical request forever. */
  def inputRequiredResult(
    inputRequests: Map[String, InputRequest] = Map.empty,
    requestState:  Option[String]            = None
  ): ujson.Value =
    require(inputRequests.nonEmpty || requestState.isDefined,
      "InputRequiredResult must carry at least one of inputRequests or requestState")
    val obj = ujson.Obj("resultType" -> ResultTypeInputRequired)
    if inputRequests.nonEmpty then
      obj("inputRequests") = ujson.Obj.from(inputRequests.map { (key, r) =>
        key -> (ujson.Obj("method" -> r.method, "params" -> r.params): ujson.Value)
      })
    requestState.foreach(s => obj("requestState") = s)
    obj

  /** The client's answers on a retry, keyed by the identifiers the server
   *  assigned. Absent or malformed reads as empty — a server that needs an
   *  answer it did not get is told by the spec to ask AGAIN with a fresh
   *  `InputRequiredResult`, not to error, so there is nothing to throw about. */
  def parseInputResponses(params: ujson.Value): Map[String, ujson.Value] =
    try
      params.objOpt.flatMap(_.get("inputResponses")).flatMap(_.objOpt)
        .map(_.toMap).getOrElse(Map.empty)
    catch case _: Throwable => Map.empty

  /** The opaque state the client echoed back, if any. Never inspected here. */
  def parseRequestState(params: ujson.Value): Option[String] =
    try params.objOpt.flatMap(_.get("requestState")).flatMap(_.strOpt)
    catch case _: Throwable => None

  /** Methods `2026-07-28` deleted, and which therefore MUST NOT be answered on
   *  the modern path.
   *
   *  `ping` is gone outright; `logging/setLevel` is replaced by a per-request
   *  `io.modelcontextprotocol/logLevel` in `_meta`. They stay fully served on
   *  the LEGACY path — they exist in the revisions a legacy client negotiated,
   *  and removing them there would break the era we promised to keep.
   *
   *  A modern client calling one gets `MethodNotFound`, which is the honest
   *  answer: in the revision it asked for, the method does not exist. */
  /** REQUESTS the modern era deleted. Requests only — a notification has no
   *  response, so it cannot be refused with an error frame and is handled at
   *  the delivery site instead. See `RemovedNotificationsInModern`; the two
   *  sets together are the removal list, and neither is it alone. */
  val RemovedInModern: Set[String] = Set(
    Method.Ping, Method.LoggingSetLevel,
    // Replaced by `subscriptions/listen`, whose filter carries the resource URIs.
    Method.ResourcesSubscribe, Method.ResourcesUnsubscribe)

  /** True iff this request is a RETRY of an MRTR round trip.
   *
   *  Load-bearing for caching: the spec says a result produced from a request
   *  carrying `inputResponses` or `requestState` MUST NOT be cached, because it
   *  depends on inputs that are not part of the cache key. */
  /** `requestState` carries TWO things and belongs to neither alone: the
   *  server's park token and whatever the handler recorded for itself. The
   *  spec makes this the only field a client must echo back, so both ride in
   *  it, and the author sees only their own slice.
   *
   *  A value that does not parse as this envelope is treated as author state
   *  with no token — which is what a `Replay`-mode server produced before
   *  parking existed, and what a hand-written client will send. */
  def parkEnvelope(token: Option[String], authorState: Option[String]): Option[String] =
    if token.isEmpty && authorState.isEmpty then None
    else
      val o = ujson.Obj()
      token.foreach(t => o("park") = t)
      authorState.foreach(s => o("app") = s)
      Some(ujson.write(o))

  /** The park token, if this `requestState` is one of ours and carries one. */
  def parkToken(requestState: Option[String]): Option[String] =
    requestState.flatMap { s =>
      try ujson.read(s).objOpt.flatMap(_.get("park")).flatMap(_.strOpt)
      catch case _: Throwable => None
    }

  /** The author's own slice. An unparseable or token-less value is returned
   *  whole, so state written by a server that never parked still round-trips. */
  def authorState(requestState: Option[String]): Option[String] =
    requestState.flatMap { s =>
      try
        ujson.read(s).objOpt match
          case Some(o) if o.contains("park") || o.contains("app") =>
            o.get("app").flatMap(_.strOpt)
          case _ => Some(s)
      catch case _: Throwable => Some(s)
    }

  def isMrtrRetry(params: ujson.Value): Boolean =
    parseInputResponses(params).nonEmpty || parseRequestState(params).isDefined

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
  /** `context.arguments` off a `completion/complete` request (2025-06-18).
   *
   *  The variables the client has ALREADY resolved, so a completion for the
   *  second argument can depend on the first. Absent or malformed reads as
   *  empty, because a completion with no context is a completion, not an
   *  error — the field is additive and a 2025-03-26 client never sends it. */
  def parseCompletionContext(params: ujson.Value): Map[String, String] =
    try
      params.objOpt.flatMap(_.get("context")).flatMap(_.objOpt)
        .flatMap(_.get("arguments")).flatMap(_.objOpt)
        .map(_.toMap.flatMap { (k, v) => v.strOpt.map(k -> _) })
        .getOrElse(Map.empty)
    catch case _: Throwable => Map.empty

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
