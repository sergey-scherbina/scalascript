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

  // ─── State parameter (CSRF defence) ────────────────────────────────

  /** v1.17.x — `state` parameter helpers for the authorization-code
   *  redirect (CSRF defence per RFC 6749 §10.12).  Caller generates
   *  a fresh state at /authorize-redirect time, stashes it in the
   *  user's session, then matches it against the `state` parameter
   *  echoed back to the redirect URI.  Constant-time compare to
   *  defeat timing attacks on the verification path. */
  def freshState(): String = OAuth.randomOpaqueToken(24)

  /** Constant-time comparison of an inbound state against the one
   *  originally generated.  Returns false on length mismatch +
   *  empty inputs as a defence-in-depth measure. */
  def verifyState(expected: String, presented: String): Boolean =
    if expected.isEmpty || presented.isEmpty then false
    else
      var diff = 0
      var i = 0
      if expected.length != presented.length then return false
      while i < expected.length do
        diff |= (expected.charAt(i) ^ presented.charAt(i))
        i += 1
      diff == 0

  // ─── JWKS-backed external JWT validation ───────────────────────────

  /** v1.17.x — bounded JWKS cache.  Fetches `<jwks_uri>` and refreshes
   *  every `ttlSeconds` (default 5 min).  Indexed by `kid` so verifiers
   *  can pick the right key when the AS rotates.  Multi-AS deployments
   *  use one `JwksCache` per AS URL. */
  class JwksCache(val jwksUri: String, val ttlSeconds: Long = 300L):
    @volatile private var keys: Map[String, java.security.PublicKey] = Map.empty
    @volatile private var fetchedAt: Long = 0L

    /** Force a fresh fetch.  Quietly tolerates transport errors by
     *  keeping the current cache (callers see stale-but-best-effort). */
    def refresh(): Unit =
      try
        val raw = fetchJson(jwksUri, 5000L)
        val parsed = raw.obj.get("keys").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
        keys = parsed.flatMap(jwkToKey).toMap
        fetchedAt = java.time.Instant.now.getEpochSecond
      catch case _: Throwable => ()  // keep stale cache

    /** Resolve a JWK by `kid`.  Triggers a refresh when the cache is
     *  stale OR the supplied kid is unknown (covers the rotation case
     *  where the AS swapped in a new key just before this verification). */
    def keyFor(kid: Option[String]): Option[java.security.PublicKey] =
      val now = java.time.Instant.now.getEpochSecond
      if keys.isEmpty || now - fetchedAt > ttlSeconds then refresh()
      kid.flatMap(keys.get) match
        case Some(k) => Some(k)
        case None =>
          // Unknown kid → maybe just rotated.  Refresh + retry once.
          if now - fetchedAt > 5L then  // throttle to once per 5s
            refresh()
            kid.flatMap(keys.get)
          else None

  /** Decode a single JWK entry into a JCA `PublicKey`.  Supports
   *  RS256 (RSA `n`/`e`) and ES256 (EC `x`/`y` on P-256).  Returns
   *  None for unsupported algorithms or malformed entries. */
  private def jwkToKey(jwk: ujson.Value): Option[(String, java.security.PublicKey)] =
    try
      val kid = jwk.obj.get("kid").flatMap(_.strOpt).getOrElse("")
      val kty = jwk.obj.get("kty").flatMap(_.strOpt).getOrElse("")
      kty match
        case "RSA" =>
          val n = jwk("n").str
          val e = jwk("e").str
          Some(kid -> Passkey.decodeRsaJwk(n, e))
        case "EC" =>
          val x = jwk("x").str
          val y = jwk("y").str
          Some(kid -> Passkey.decodeEcJwk(x, y))
        case _ => None
    catch case _: Throwable => None

  /** Validate a presented JWT (typically an access token from an
   *  external AS) using a JWKS cache.  Returns the parsed payload on
   *  success; `Left(reason)` on any structural / signature / timestamp
   *  failure.  Accepts both RS256 and ES256 algs; clock skew respects
   *  `OAuth.DefaultClockSkewSeconds`. */
  def validateJwt(token: String, jwks: JwksCache): Either[String, ujson.Value] =
    try
      val parts = token.split('.')
      if parts.length != 3 then Left("malformed token")
      else
        val headerJson = ujson.read(decodeB64uString(parts(0)))
        val alg        = headerJson.obj.get("alg").flatMap(_.strOpt).getOrElse("")
        val kid        = headerJson.obj.get("kid").flatMap(_.strOpt)
        jwks.keyFor(kid) match
          case None => Left(s"no matching JWKS key for kid=${kid.getOrElse("(none)")}")
          case Some(key) =>
            val signingInput = parts(0) + "." + parts(1)
            val sigBytes     = java.util.Base64.getUrlDecoder.decode(parts(2))
            val verifyOk = alg match
              case "RS256" =>
                key match
                  case rk: java.security.interfaces.RSAPublicKey =>
                    OAuth.rsaVerify(rk, signingInput, sigBytes)
                  case _ => false
              case "ES256" =>
                ecVerify(key, signingInput, sigBytes)
              case _ => false
            if !verifyOk then Left("signature mismatch")
            else
              val payload = ujson.read(decodeB64uString(parts(1)))
              OAuth.validateJwtTimestamps(payload, OAuth.DefaultClockSkewSeconds) match
                case Some(reason) => Left(reason)
                case None         => Right(payload)
    catch case _: Throwable => Left("validation failure")

  private def decodeB64uString(s: String): String =
    new String(java.util.Base64.getUrlDecoder.decode(s),
      java.nio.charset.StandardCharsets.UTF_8)

  private def ecVerify(key: java.security.PublicKey, message: String, signature: Array[Byte]): Boolean =
    try
      val sig = java.security.Signature.getInstance("SHA256withECDSA")
      sig.initVerify(key)
      sig.update(message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      sig.verify(signature)
    catch case _: Throwable => false

  // ─── id_token validation (OIDC) ────────────────────────────────────

  /** Outcome of an id_token validation.  `Valid(claims)` returns the
   *  parsed JWT payload (so callers can read `sub`, `email`, etc.);
   *  `Invalid(reason)` carries a human-readable error. */
  enum IdTokenResult:
    case Valid(claims: ujson.Value)
    case Invalid(reason: String)

  /** Validate an OIDC id_token against the expected issuer + audience
   *  + nonce.  Signature is verified via the supplied JWKS cache;
   *  timestamp checks honour the standard clock skew window. */
  def validateIdToken(
    idToken:          String,
    jwks:             JwksCache,
    expectedIssuer:   String,
    expectedAudience: String,
    expectedNonce:    Option[String]  = None
  ): IdTokenResult =
    validateJwt(idToken, jwks) match
      case Left(reason) => IdTokenResult.Invalid(reason)
      case Right(claims) =>
        // iss MUST match — defeats id_token mix-up attacks
        val iss = claims.obj.get("iss").flatMap(_.strOpt).getOrElse("")
        if iss != expectedIssuer then
          IdTokenResult.Invalid(s"iss mismatch: expected $expectedIssuer, got $iss")
        else
          // aud MUST contain expectedAudience (string or array form)
          val audOk = claims.obj.get("aud") match
            case Some(ujson.Str(a))  => a == expectedAudience
            case Some(ujson.Arr(xs)) => xs.exists(_.strOpt.contains(expectedAudience))
            case _                    => false
          if !audOk then
            IdTokenResult.Invalid(s"aud claim does not include $expectedAudience")
          else if expectedNonce.isDefined && claims.obj.get("nonce").flatMap(_.strOpt) != expectedNonce then
            IdTokenResult.Invalid("nonce mismatch")
          else
            IdTokenResult.Valid(claims)

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
