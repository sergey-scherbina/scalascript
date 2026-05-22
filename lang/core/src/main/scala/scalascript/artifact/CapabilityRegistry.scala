package scalascript.artifact

/** Canonical mapping from intrinsic call names (the bare or qualified
 *  identifier that appears at a call site in user `.ssc` code) to the
 *  capability name surfaced in `.scim` artifacts (`CapabilityDecl`).
 *
 *  This table is the single source of truth for `InterfaceExtractor`'s
 *  AST-based capability detection.  When a backend adds a new intrinsic
 *  belonging to a known capability, extend the relevant set below; when
 *  a brand-new capability is introduced, add a new entry.
 *
 *  Naming conventions
 *  ------------------
 *  - Bare names (`serve`, `onWebSocket`) are matched against
 *    `Term.Apply(Term.Name(name), …)` and `Term.Select(…, Term.Name(name))`
 *    call sites.
 *  - Dotted names (`Dataset.of`, `Response.html`) match qualified call
 *    sites of the form `Term.Select(Term.Name(qual), Term.Name(name))`.
 *
 *  Capability strings (`"Http"`, `"WebSocket"`, …) are the legacy names
 *  the rest of the toolchain already consumes via `CapabilityDecl.name`.
 *  These do NOT have to match the `backend.spi.Feature` enum cases — the
 *  artifact layer predates the SPI Feature enum and round-trips with
 *  `.scim` JSON written by earlier versions. */
object CapabilityRegistry:

  /** Capability label → set of intrinsic call names that, when invoked,
   *  signal use of that capability in the module. */
  val capabilities: Map[String, Set[String]] = Map(
    "Http" -> Set(
      // HTTP server
      "route", "serve", "stop", "tls",
      "cors", "useGzip", "cacheable", "noCache",
      "streamResponse", "sse",
      "maxBodySize", "uploadSpoolThreshold", "uploadDir",
      "use",
      // HTTP client
      "httpGet", "httpPost", "httpPut", "httpPatch", "httpDelete",
      "httpGetStream", "httpPostStream",
      "httpTimeout", "httpRetry",
      // Response builders (qualified)
      "Response.html", "Response.text", "Response.json",
      "Response.redirect", "Response.notFound", "Response.status",
    ),
    "WebSocket" -> Set(
      "onWebSocket", "onWebSocketAuth",
      "wsConnect", "WsRoom",
      "setMaxWsConnections", "metrics",
    ),
    "Auth" -> Set(
      "hashPassword", "verifyPassword",
      "jwtSign", "jwtVerify", "jwtSignRsa", "jwtVerifyRsa",
      "csrfToken", "csrfValid",
      "base64UrlEncode", "base64UrlDecode",
      "webauthnChallenge", "webauthnConsumeChallenge",
      "webauthnStorePut", "webauthnStoreGet", "webauthnStoreFind",
      "webauthnUpdateSignCount",
      "webauthnVerifyAssertion", "webauthnVerifyRegistration",
      "rateLimit", "rateLimitReset",
      "totpSecret", "totpUri", "totpCode", "totpValid",
      "cookieConfig", "useSessionStore",
      "oauthAuthorizeUrl", "oauthExchangeCode",
      "oauthUserinfo", "oauthRefreshToken", "oauthRegisterProvider",
      "Response.basicAuthChallenge",
    ),
    "FileSystem" -> Set(
      // Legacy direct names retained for back-compat with text heuristic.
      "readFile", "writeFile",
      // Current intrinsic surface from std.fs.
      "os.read", "os.write", "os.list",
    ),
    "Crypto" -> Set(
      "sha256", "hashSha256",
      // qualified crypto.* members (matched on the `crypto` qualifier below)
    ),
    "Json" -> Set(
      "jsonStringify", "jsonParse", "jsonRead",
      "lookup", "lookupOpt",
    ),
    "Mcp" -> Set(
      "mcpServer", "serveMcp", "mcpConnect",
      "McpServer.tool", "McpServer.resource", "McpServer.prompt",
      "McpServer.onConnected", "McpServer.onDisconnected",
      "McpClient.listTools", "McpClient.listResources",
      "McpClient.listPrompts", "McpClient.callTool",
      "McpClient.readResource", "McpClient.getPrompt",
      "McpClient.close", "McpClient.isClosed",
    ),
    "Dataset" -> Set(
      "Dataset.of", "Dataset.fromList",
      "Dataset.fromGenerator", "Dataset.fromFile",
    ),
  )

  /** Reverse lookup: intrinsic call name → capability label.  Built once. */
  private val intrinsicToCapability: Map[String, String] =
    capabilities.iterator.flatMap { case (cap, names) =>
      names.iterator.map(n => n -> cap)
    }.toMap

  /** Qualifier-only prefixes that always belong to a capability
   *  irrespective of the member name.  Used for e.g. `crypto.<anything>`
   *  which historically signalled the Crypto capability. */
  private val qualifierToCapability: Map[String, String] = Map(
    "crypto" -> "Crypto",
  )

  /** Resolve a call-site name (bare or qualified) to its capability, if any. */
  def capabilityFor(name: String): Option[String] =
    intrinsicToCapability.get(name)

  /** Resolve the qualifier of a `Term.Select` (`crypto.foo` → `"crypto"`) to a
   *  capability when the entire qualifier denotes a capability surface. */
  def capabilityForQualifier(qualifier: String): Option[String] =
    qualifierToCapability.get(qualifier)
