package scalascript.oauth

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** v1.17.x — OAuth 2.1 client SDK.  Pairs with `AuthServer` (the AS
 *  side) + `OAuthGuard` (the RS side) to complete the three-role
 *  picture.  Use from any client app that needs to:
 *
 *    1. Discover an AS via `/.well-known/oauth-authorization-server`
 *       (or follow an MCP server's `WWW-Authenticate` →
 *       `/.well-known/oauth-protected-resource` chain)
 *    2. Run the authorization-code + PKCE flow (or the simpler
 *       client_credentials grant for backend services)
 *    3. Exchange refresh tokens for fresh access tokens
 *    4. Drive `mcpConnect(transport, bearerToken)` with the result
 *
 *  Pure-function decision logic lives in `OAuthClient.*`; the network
 *  I/O is a thin wrapper over Java's `HttpClient`. */
object OAuthClient:

  // ─── Discovery ─────────────────────────────────────────────────────

  /** Fetch the AS metadata (RFC 8414) at `<issuer>/.well-known/
   *  oauth-authorization-server`.  Returns the parsed JSON; throws
   *  on transport / parse failure (callers usually let this bubble
   *  during boot). */
  def discoverAs(issuer: String, timeoutMs: Long = 5000L): ujson.Value =
    val url = issuer.stripSuffix("/") + "/.well-known/oauth-authorization-server"
    fetchJson(url, timeoutMs)

  /** Fetch the protected-resource metadata (RFC 9728) the MCP/RS
   *  server publishes; the matching AS is named in the
   *  `authorization_servers` field. */
  def discoverRs(resourceUrl: String, timeoutMs: Long = 5000L): ujson.Value =
    val url = resourceUrl.stripSuffix("/") + "/.well-known/oauth-protected-resource"
    fetchJson(url, timeoutMs)

  // ─── PKCE pair ─────────────────────────────────────────────────────

  case class PkcePair(verifier: String, challenge: String, method: String = "S256")

  /** Generate a fresh PKCE verifier + S256 challenge per RFC 7636. */
  def freshPkce(): PkcePair =
    val v = OAuth.randomOpaqueToken(48)
    PkcePair(v, OAuth.pkceS256(v))

  // ─── Authorization endpoint URL ───────────────────────────────────

  /** Build the URL the user-agent navigates to for the
   *  authorization-code flow.  Pure: caller embeds in an
   *  `<a href="...">` or 302 redirect. */
  def authorizationUrl(
    authorizationEndpoint: String,
    clientId:              String,
    redirectUri:           String,
    scopes:                Set[String],
    state:                 String,
    pkce:                  PkcePair
  ): String =
    val q = Map(
      "response_type"         -> "code",
      "client_id"             -> clientId,
      "redirect_uri"          -> redirectUri,
      "state"                 -> state,
      "code_challenge"        -> pkce.challenge,
      "code_challenge_method" -> pkce.method
    ) ++ (if scopes.nonEmpty then Map("scope" -> scopes.toList.sorted.mkString(" ")) else Map.empty)
    val qs = OAuthRoutes.queryString(q)
    if authorizationEndpoint.contains("?")
    then authorizationEndpoint + "&" + qs
    else authorizationEndpoint + "?" + qs

  // ─── Token endpoint ────────────────────────────────────────────────

  /** Outcome of a token request.  Mirrors the AS-side TokenOutcome
   *  but lives on the client side (no AuthServer dependency). */
  case class Tokens(
    accessToken:  String,
    tokenType:    String,
    expiresIn:    Long,
    refreshToken: Option[String],
    idToken:      Option[String],
    scope:        Set[String]
  )

  enum TokenResult:
    case Issued(tokens: Tokens, raw: ujson.Value)
    case Error(error: String, description: String, raw: ujson.Value)

  /** Exchange an authorization code for tokens.  Caller already ran
   *  PKCE — supplies the original `verifier`. */
  def exchangeAuthorizationCode(
    tokenEndpoint: String,
    clientId:      String,
    redirectUri:   String,
    code:          String,
    verifier:      String,
    clientSecret:  Option[String] = None,
    timeoutMs:     Long           = 5000L
  ): TokenResult =
    val form = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "redirect_uri"  -> redirectUri,
      "client_id"     -> clientId,
      "code_verifier" -> verifier
    ) ++ clientSecret.map("client_secret" -> _).toMap
    postForm(tokenEndpoint, form, timeoutMs)

  /** Refresh an access token using a previously-issued refresh token. */
  def refresh(
    tokenEndpoint: String,
    clientId:      String,
    refreshToken:  String,
    scopes:        Set[String]    = Set.empty,
    clientSecret:  Option[String] = None,
    timeoutMs:     Long           = 5000L
  ): TokenResult =
    val form = Map(
      "grant_type"    -> "refresh_token",
      "refresh_token" -> refreshToken,
      "client_id"     -> clientId
    ) ++ (if scopes.nonEmpty then Map("scope" -> scopes.toList.sorted.mkString(" ")) else Map.empty)
      ++ clientSecret.map("client_secret" -> _).toMap
    postForm(tokenEndpoint, form, timeoutMs)

  /** Machine-to-machine: mint a token directly from client credentials. */
  def clientCredentials(
    tokenEndpoint: String,
    clientId:      String,
    clientSecret:  String,
    scopes:        Set[String] = Set.empty,
    timeoutMs:     Long        = 5000L
  ): TokenResult =
    val form = Map(
      "grant_type"    -> "client_credentials",
      "client_id"     -> clientId,
      "client_secret" -> clientSecret
    ) ++ (if scopes.nonEmpty then Map("scope" -> scopes.toList.sorted.mkString(" ")) else Map.empty)
    postForm(tokenEndpoint, form, timeoutMs)

  // ─── Stateful token store (auto-refresh) ──────────────────────────

  /** Lightweight client-side token holder.  Caller seeds initial
   *  tokens (e.g. from the auth-code exchange) and retrieves a fresh
   *  access token via `current()`; the holder refreshes lazily when
   *  the cached one is within `refreshLeadSeconds` of expiry. */
  class TokenHolder(
    val tokenEndpoint:     String,
    val clientId:          String,
    val clientSecret:      Option[String]  = None,
    val refreshLeadSeconds: Long           = 60L
  ):
    @volatile private var accessToken:  Option[String] = None
    @volatile private var refreshToken: Option[String] = None
    @volatile private var expiresAt:    Long           = 0L

    def seed(tokens: Tokens): Unit =
      accessToken  = Some(tokens.accessToken)
      refreshToken = tokens.refreshToken
      expiresAt    = java.time.Instant.now.getEpochSecond + tokens.expiresIn

    def current(): Option[String] =
      val now = java.time.Instant.now.getEpochSecond
      if accessToken.isDefined && expiresAt - now > refreshLeadSeconds then
        accessToken
      else
        // Try to refresh.  Returns the new access token on success,
        // None if no refresh token / refresh failed (caller will get
        // 401 from the RS and can drive re-auth).
        refreshToken.flatMap { rt =>
          refresh(tokenEndpoint, clientId, rt, clientSecret = clientSecret) match
            case TokenResult.Issued(t, _) => seed(t); accessToken
            case TokenResult.Error(_, _, _) => None
        }

    def clear(): Unit =
      accessToken  = None
      refreshToken = None
      expiresAt    = 0L

  // ─── internal helpers ──────────────────────────────────────────────

  private val httpClient: HttpClient = HttpClient.newBuilder().build()

  private def fetchJson(url: String, timeoutMs: Long): ujson.Value =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Accept", "application/json")
      .GET()
      .build()
    val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    ujson.read(resp.body())

  private def postForm(url: String, form: Map[String, String], timeoutMs: Long): TokenResult =
    val body = form.toList.sortBy(_._1).map { (k, v) =>
      java.net.URLEncoder.encode(k, "UTF-8") + "=" +
      java.net.URLEncoder.encode(v, "UTF-8")
    }.mkString("&")
    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("Accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    try
      val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
      val js   = ujson.read(resp.body())
      if resp.statusCode() >= 200 && resp.statusCode() < 300 then
        TokenResult.Issued(Tokens(
          accessToken  = js("access_token").str,
          tokenType    = js.obj.get("token_type").flatMap(_.strOpt).getOrElse("Bearer"),
          expiresIn    = js.obj.get("expires_in").flatMap(_.numOpt).getOrElse(3600.0).toLong,
          refreshToken = js.obj.get("refresh_token").flatMap(_.strOpt),
          idToken      = js.obj.get("id_token").flatMap(_.strOpt),
          scope        = js.obj.get("scope").flatMap(_.strOpt)
                          .map(_.split(' ').iterator.filter(_.nonEmpty).toSet)
                          .getOrElse(Set.empty)
        ), js)
      else
        TokenResult.Error(
          js.obj.get("error").flatMap(_.strOpt).getOrElse(s"http_${resp.statusCode()}"),
          js.obj.get("error_description").flatMap(_.strOpt).getOrElse(""),
          js)
    catch case e: Throwable =>
      TokenResult.Error("transport_error",
        Option(e.getMessage).getOrElse(e.getClass.getSimpleName),
        ujson.Obj())
