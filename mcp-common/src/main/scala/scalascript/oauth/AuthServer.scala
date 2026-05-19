package scalascript.oauth

import java.util.concurrent.ConcurrentHashMap

/** v1.17.x — standalone OAuth 2.1 Authorization Server.  Independent of
 *  MCP: usable as the AS for any HTTP service that needs OAuth.
 *
 *  Scope:
 *    - Authorization-code grant with mandatory PKCE (OAuth 2.1 default)
 *    - Refresh-token grant
 *    - Client-credentials grant
 *    - Dynamic Client Registration (RFC 7591)
 *    - Token introspection (RFC 7662)
 *    - AS metadata (RFC 8414)
 *    - Signed JWT access tokens (HMAC-SHA256 via `OAuth.issueHmacToken`)
 *      so resource servers can validate without round-tripping
 *
 *  Out of scope (deferred):
 *    - Interactive UI for user auth + consent — caller supplies the
 *      `subject` after their own auth (`issueAuthorizationCode(...)`)
 *    - Implicit + password grants (OAuth 2.1 forbids them)
 *    - Token revocation (RFC 7009) — easy add when wanted
 *    - JWKS / RSA — stick to HMAC until a use-case demands asymmetric
 *
 *  Usage shape:
 *  ```
 *  val as = new AuthServer(AuthServerConfig(issuer = "https://auth.x"))
 *  as.registerClient(Client("my-app", None, Set("http://localhost/cb"), Set("read")))
 *  // user finished login + consent in your UI:
 *  val out = as.issueAuthorizationCode(req, subject = "alice")
 *  // out.redirectUri is what to redirect the browser to
 *  // client posts the code to the token endpoint:
 *  as.issueToken(TokenRequest.AuthorizationCodeGrant(...))
 *  // resource server validates a presented token:
 *  as.tokenValidator(presentedToken)
 *  ```
 */
class AuthServer(
  val config: AuthServerConfig,
  val clients: ClientStore = new InMemoryClientStore,
  val tokens:  TokenStore  = new InMemoryTokenStore
):
  import OAuth._

  // ─── Authorization endpoint ─────────────────────────────────────────

  /** Decision logic for the authorization endpoint AFTER the user has
   *  authenticated + consented in your UI.  Validates the request
   *  against the registered client, generates an authorization code,
   *  and produces the redirect URL.  Caller's responsibility: actually
   *  performing the user authentication / consent prompt — this method
   *  is the post-consent half. */
  def issueAuthorizationCode(req: AuthorizationRequest, subject: String): AuthorizationOutcome =
    clients.find(req.clientId) match
      case None =>
        AuthorizationOutcome.ErrorResponse("invalid_client", s"unknown client_id: ${req.clientId}")
      case Some(client) =>
        if !client.redirectUris.contains(req.redirectUri) then
          AuthorizationOutcome.ErrorResponse("invalid_request",
            s"redirect_uri not registered for client ${client.id}")
        else if req.responseType != "code" then
          AuthorizationOutcome.ErrorRedirect(req.redirectUri,
            "unsupported_response_type", Some(s"only 'code' supported"), req.state)
        else if config.requirePkce && req.codeChallenge.isEmpty then
          AuthorizationOutcome.ErrorRedirect(req.redirectUri,
            "invalid_request", Some("code_challenge required (PKCE)"), req.state)
        else if !req.scope.subsetOf(client.scopes) then
          AuthorizationOutcome.ErrorRedirect(req.redirectUri,
            "invalid_scope", Some(s"requested scopes not granted to client"), req.state)
        else
          val code = randomOpaqueToken(24)
          val rec  = AuthorizationCodeRecord(
            code                = code,
            clientId            = client.id,
            redirectUri         = req.redirectUri,
            scope               = req.scope,
            subject             = subject,
            codeChallenge       = req.codeChallenge,
            codeChallengeMethod = req.codeChallengeMethod.orElse(req.codeChallenge.map(_ => "plain")),
            expiresAt           = java.time.Instant.now.getEpochSecond + config.authorizationCodeTtlSeconds
          )
          tokens.saveAuthorizationCode(rec)
          AuthorizationOutcome.CodeRedirect(req.redirectUri, code, req.state)

  // ─── Token endpoint ─────────────────────────────────────────────────

  /** Pure token-endpoint logic.  Caller decodes the form-encoded body
   *  into a `TokenRequest` and feeds it here; the returned `TokenOutcome`
   *  carries either the issuable response payload or the spec-shaped
   *  error pair. */
  def issueToken(req: TokenRequest): TokenOutcome = req match
    case g: TokenRequest.AuthorizationCodeGrant => handleAuthCode(g)
    case g: TokenRequest.RefreshTokenGrant      => handleRefresh(g)
    case g: TokenRequest.ClientCredentialsGrant => handleClientCreds(g)

  private def handleAuthCode(g: TokenRequest.AuthorizationCodeGrant): TokenOutcome =
    authenticateClient(g.clientId, g.clientSecret) match
      case Left(err)     => TokenOutcome.Error(err, "client authentication failed")
      case Right(client) =>
        tokens.consumeAuthorizationCode(g.code) match
          case None      => TokenOutcome.Error("invalid_grant", "code not found / already used / expired")
          case Some(rec) =>
            if rec.clientId != client.id then
              TokenOutcome.Error("invalid_grant", "code was issued to a different client")
            else if rec.redirectUri != g.redirectUri then
              TokenOutcome.Error("invalid_grant", "redirect_uri does not match the original request")
            else if rec.expiresAt < java.time.Instant.now.getEpochSecond then
              TokenOutcome.Error("invalid_grant", "code expired")
            else
              val pkceCheck = (rec.codeChallenge, g.codeVerifier) match
                case (None, _) if !config.requirePkce => Right(())   // PKCE optional + not used
                case (None, _)                        => Left("invalid_request: PKCE required but code has no challenge")
                case (Some(_), None)                  => Left("invalid_grant: code_verifier missing")
                case (Some(ch), Some(v))              =>
                  val m = rec.codeChallengeMethod.getOrElse("plain")
                  if pkceMatches(v, ch, m) then Right(()) else Left("invalid_grant: PKCE verification failed")
              pkceCheck match
                case Left(err) => TokenOutcome.Error(err.takeWhile(_ != ':'), err.dropWhile(_ != ':').stripPrefix(": "))
                case Right(_)  => TokenOutcome.Issued(mintTokens(client, rec.subject, rec.scope))

  private def handleRefresh(g: TokenRequest.RefreshTokenGrant): TokenOutcome =
    authenticateClient(g.clientId, g.clientSecret) match
      case Left(err)     => TokenOutcome.Error(err, "client authentication failed")
      case Right(client) =>
        tokens.findRefreshToken(g.refreshToken) match
          case None      => TokenOutcome.Error("invalid_grant", "refresh token not found")
          case Some(rec) =>
            if rec.clientId != client.id then
              TokenOutcome.Error("invalid_grant", "refresh token was issued to a different client")
            else if rec.expiresAt < java.time.Instant.now.getEpochSecond then
              TokenOutcome.Error("invalid_grant", "refresh token expired")
            else
              // Optional narrower scope on refresh (RFC 6749 §6).  Empty
              // request scope keeps the original.  Wider scopes rejected.
              val newScope =
                if g.scope.isEmpty then rec.scope
                else if g.scope.subsetOf(rec.scope) then g.scope
                else
                  return TokenOutcome.Error("invalid_scope", "refresh scope is wider than the original")
              // OAuth 2.1 §6.1: rotate the refresh token (single-use).
              tokens.revokeRefreshToken(g.refreshToken)
              TokenOutcome.Issued(mintTokens(client, rec.subject, newScope))

  private def handleClientCreds(g: TokenRequest.ClientCredentialsGrant): TokenOutcome =
    authenticateClient(g.clientId, Some(g.clientSecret)) match
      case Left(err)     => TokenOutcome.Error(err, "client authentication failed")
      case Right(client) =>
        if client.clientType != ClientType.Confidential then
          TokenOutcome.Error("invalid_client", "client_credentials requires a confidential client")
        else if !client.grantTypes.contains("client_credentials") then
          TokenOutcome.Error("unauthorized_client", "client not authorized for client_credentials")
        else if !g.scope.subsetOf(client.scopes) then
          TokenOutcome.Error("invalid_scope", "requested scopes not granted to client")
        else
          // Client-credentials issues an access token only (no refresh — RFC 6749 §4.4.3).
          val access  = issueHmacToken(
            secret           = config.signingSecret,
            subject          = client.id,
            scopes           = g.scope,
            expiresInSeconds = config.accessTokenTtlSeconds,
            issuer           = Some(config.issuer),
            clientId         = Some(client.id)
          )
          TokenOutcome.Issued(TokenResponse(
            accessToken  = access,
            expiresIn    = config.accessTokenTtlSeconds,
            refreshToken = None,
            scope        = g.scope
          ))

  private def mintTokens(client: Client, subject: String, scope: Set[String]): TokenResponse =
    // jti distinguishes back-to-back issuances that share iat/exp/scope —
    // matters for rotation tests + revocation lists.
    val access = issueHmacToken(
      secret           = config.signingSecret,
      subject          = subject,
      scopes           = scope,
      expiresInSeconds = config.accessTokenTtlSeconds,
      issuer           = Some(config.issuer),
      clientId         = Some(client.id),
      extra            = ujson.Obj("jti" -> randomOpaqueToken(12))
    )
    val refresh = randomOpaqueToken(32)
    tokens.saveRefreshToken(RefreshTokenRecord(
      token     = refresh,
      clientId  = client.id,
      subject   = subject,
      scope     = scope,
      expiresAt = java.time.Instant.now.getEpochSecond + config.refreshTokenTtlSeconds
    ))
    TokenResponse(
      accessToken  = access,
      expiresIn    = config.accessTokenTtlSeconds,
      refreshToken = Some(refresh),
      scope        = scope
    )

  /** Confidential clients must present `client_secret`; public clients
   *  use PKCE instead and do not present a secret.  Returns the
   *  resolved Client on success, RFC 6749 error code on failure. */
  private def authenticateClient(id: String, secret: Option[String]): Either[String, Client] =
    clients.find(id) match
      case None         => Left("invalid_client")
      case Some(client) =>
        client.clientType match
          case ClientType.Public =>
            // Public clients MUST NOT present a secret (spec) — accept either way.
            Right(client)
          case ClientType.Confidential =>
            (client.secret, secret) match
              case (Some(expected), Some(presented)) if OAuth.constantTimeEquals(expected, presented) =>
                Right(client)
              case _ => Left("invalid_client")

  // ─── Introspection (RFC 7662) ───────────────────────────────────────

  /** Decode a presented bearer token (signed by this server) and decide
   *  whether it is still active.  Implements both directions of RFC 7662
   *  shape: `active: false` for any unparseable/expired/revoked token,
   *  otherwise the full claim set. */
  def introspect(token: String): IntrospectionResponse =
    OAuth.decodeHmacToken(config.signingSecret, token) match
      case Left(_) => IntrospectionResponse(active = false)
      case Right(payload) =>
        val jti = payload.obj.get("jti").flatMap(_.strOpt).getOrElse(token)
        if tokens.isAccessRevoked(jti) then IntrospectionResponse(active = false)
        else
          IntrospectionResponse(
            active    = true,
            subject   = payload.obj.get("sub").flatMap(_.strOpt),
            scope     = payload.obj.get("scope").flatMap(_.strOpt),
            clientId  = payload.obj.get("client_id").flatMap(_.strOpt),
            exp       = payload.obj.get("exp").flatMap(_.numOpt).map(_.toLong),
            iat       = payload.obj.get("iat").flatMap(_.numOpt).map(_.toLong),
            tokenType = Some("Bearer")
          )

  /** A `TokenValidator` (Resource Server side) backed by this AS.
   *  Resource servers wire this in to validate inbound tokens locally
   *  (without a network round-trip), assuming they share the signing
   *  secret with the AS.  Honours the access-token revocation list.
   *  For cross-service deployments use the introspection endpoint
   *  instead. */
  def tokenValidator: OAuth.TokenValidator = token =>
    OAuth.decodeHmacToken(config.signingSecret, token) match
      case Left(reason) => OAuth.AuthResult.Invalid("invalid_token", reason)
      case Right(payload) =>
        val jti = payload.obj.get("jti").flatMap(_.strOpt).getOrElse(token)
        if tokens.isAccessRevoked(jti) then
          OAuth.AuthResult.Invalid("invalid_token", "token revoked")
        else
          val sub    = payload.obj.get("sub").flatMap(_.strOpt).getOrElse("")
          val scope  = payload.obj.get("scope").flatMap(_.strOpt).getOrElse("")
          val scopes = scope.split(' ').iterator.filter(_.nonEmpty).toSet
          OAuth.AuthResult.Valid(OAuth.AuthClaims(sub, scopes, payload))

  // ─── Token revocation (RFC 7009) ────────────────────────────────────

  /** Revoke a refresh OR access token.  `hint` lets clients optimise
   *  the lookup; we still fall back to trying the other type when the
   *  hint comes up empty (RFC 7009 §2.1). */
  def revokeToken(token: String, hint: TokenTypeHint = TokenTypeHint.Unknown): RevocationOutcome =
    def tryRefresh: Boolean =
      tokens.findRefreshToken(token) match
        case Some(_) => tokens.revokeRefreshToken(token); true
        case None    => false
    def tryAccess: Boolean =
      OAuth.decodeHmacToken(config.signingSecret, token) match
        case Right(payload) =>
          val jti = payload.obj.get("jti").flatMap(_.strOpt).getOrElse(token)
          tokens.revokeAccessToken(jti)
          true
        case Left(_) => false
    val ok = hint match
      case TokenTypeHint.RefreshToken => tryRefresh || tryAccess
      case TokenTypeHint.AccessToken  => tryAccess  || tryRefresh
      case TokenTypeHint.Unknown      => tryRefresh || tryAccess
    if ok then RevocationOutcome.Revoked else RevocationOutcome.Unknown

  // ─── Dynamic Client Registration (RFC 7591) ─────────────────────────

  /** Register a client from the wire metadata document the spec
   *  prescribes.  Fields recognised: `redirect_uris` (required),
   *  `token_endpoint_auth_method` (none → Public, else Confidential),
   *  `grant_types`, `response_types`, `scope` (space-separated),
   *  `client_name`.  Returns `Left("invalid_redirect_uri" | "invalid_client_metadata")`
   *  on bad input. */
  def registerClient(metadata: ujson.Value): Either[String, Client] =
    if !config.allowDynamicClientRegistration then
      Left("registration_disabled")
    else
      try
        val obj           = metadata.obj
        val redirectUris  = obj.get("redirect_uris").flatMap(_.arrOpt) match
          case None    => return Left("invalid_redirect_uri")
          case Some(a) => a.flatMap(_.strOpt).toSet
        if redirectUris.isEmpty then return Left("invalid_redirect_uri")
        val authMethod    = obj.get("token_endpoint_auth_method").flatMap(_.strOpt).getOrElse("client_secret_basic")
        val isPublic      = authMethod == "none"
        val grantTypes    = obj.get("grant_types").flatMap(_.arrOpt)
                              .map(_.flatMap(_.strOpt).toSet).getOrElse(Set("authorization_code", "refresh_token"))
        val responseTypes = obj.get("response_types").flatMap(_.arrOpt)
                              .map(_.flatMap(_.strOpt).toSet).getOrElse(Set("code"))
        val scopes        = obj.get("scope").flatMap(_.strOpt)
                              .map(_.split(' ').iterator.filter(_.nonEmpty).toSet)
                              .getOrElse(config.supportedScopes)
        val name          = obj.get("client_name").flatMap(_.strOpt)
        val id            = "client-" + OAuth.randomOpaqueToken(8)
        val secret        = if isPublic then None else Some(OAuth.randomOpaqueToken(24))
        val client        = Client(
          id            = id,
          secret        = secret,
          redirectUris  = redirectUris,
          scopes        = scopes,
          grantTypes    = grantTypes,
          responseTypes = responseTypes,
          clientType    = if isPublic then ClientType.Public else ClientType.Confidential,
          name          = name
        )
        clients.register(client)
        Right(client)
      catch case _: Throwable => Left("invalid_client_metadata")

  /** Wire-shape JSON for a successful registration response. */
  def registrationResponseJson(c: Client): ujson.Value =
    val obj = ujson.Obj(
      "client_id"      -> c.id,
      "client_id_issued_at"   -> ujson.Num(java.time.Instant.now.getEpochSecond.toDouble),
      "redirect_uris"  -> ujson.Arr.from(c.redirectUris.toList.sorted.map(ujson.Str(_))),
      "grant_types"    -> ujson.Arr.from(c.grantTypes.toList.sorted.map(ujson.Str(_))),
      "response_types" -> ujson.Arr.from(c.responseTypes.toList.sorted.map(ujson.Str(_))),
      "token_endpoint_auth_method" -> (if c.clientType == ClientType.Public then "none" else "client_secret_basic"),
      "scope"          -> c.scopes.toList.sorted.mkString(" ")
    )
    c.secret.foreach(s => obj("client_secret") = s)
    c.name.foreach  (n => obj("client_name")   = n)
    obj

  // ─── AS Metadata (RFC 8414) ─────────────────────────────────────────

  /** Build the `/.well-known/oauth-authorization-server` document.
   *  Endpoint paths use `config.issuer` as the prefix; users adjust by
   *  pointing reverse-proxies / route registration at the same prefix. */
  def metadataJson(
    authorizationEndpoint: String = "/authorize",
    tokenEndpoint:         String = "/token",
    introspectionEndpoint: String = "/introspect",
    registrationEndpoint:  String = "/register",
    revocationEndpoint:    String = "/revoke"
  ): ujson.Value =
    val base = config.issuer.stripSuffix("/")
    val obj = ujson.Obj(
      "issuer"                  -> config.issuer,
      "authorization_endpoint"  -> (base + authorizationEndpoint),
      "token_endpoint"          -> (base + tokenEndpoint),
      "introspection_endpoint"  -> (base + introspectionEndpoint),
      "revocation_endpoint"     -> (base + revocationEndpoint),
      "response_types_supported" -> ujson.Arr(ujson.Str("code")),
      "grant_types_supported"   -> ujson.Arr(
        ujson.Str("authorization_code"),
        ujson.Str("refresh_token"),
        ujson.Str("client_credentials")
      ),
      "token_endpoint_auth_methods_supported" -> ujson.Arr(
        ujson.Str("client_secret_basic"),
        ujson.Str("client_secret_post"),
        ujson.Str("none")
      ),
      "revocation_endpoint_auth_methods_supported" -> ujson.Arr(
        ujson.Str("client_secret_basic"),
        ujson.Str("client_secret_post"),
        ujson.Str("none")
      ),
      "code_challenge_methods_supported" -> ujson.Arr(
        ujson.Str("S256"), ujson.Str("plain")
      )
    )
    if config.supportedScopes.nonEmpty then
      obj("scopes_supported") = ujson.Arr.from(config.supportedScopes.toList.sorted.map(ujson.Str(_)))
    if config.allowDynamicClientRegistration then
      obj("registration_endpoint") = base + registrationEndpoint
    obj

// ─── Configuration ────────────────────────────────────────────────────

case class AuthServerConfig(
  /** Stable, URL-shaped server identifier (RFC 8414).  Embedded as the
   *  `iss` claim on every issued token. */
  issuer:                          String,
  /** HMAC-SHA256 secret for signing access tokens.  Resource servers
   *  validate by sharing the same secret (or via introspection). */
  signingSecret:                   String,
  accessTokenTtlSeconds:           Long    = 3600,
  refreshTokenTtlSeconds:          Long    = 86400 * 30,
  authorizationCodeTtlSeconds:     Long    = 600,
  /** Scopes the AS recognises; empty means clients can ask for anything
   *  (the matching client.scopes is still enforced). */
  supportedScopes:                 Set[String] = Set.empty,
  /** OAuth 2.1 mandates PKCE on authorization code; set false only for
   *  legacy compatibility. */
  requirePkce:                     Boolean = true,
  /** Whether the /register endpoint accepts new clients at runtime. */
  allowDynamicClientRegistration:  Boolean = true
)

// ─── Domain types ─────────────────────────────────────────────────────

enum ClientType:
  /** No client secret; PKCE required. */
  case Public
  /** Has client_secret; can use client_credentials grant. */
  case Confidential

case class Client(
  id:            String,
  secret:        Option[String],
  redirectUris:  Set[String],
  scopes:        Set[String],
  grantTypes:    Set[String] = Set("authorization_code", "refresh_token"),
  responseTypes: Set[String] = Set("code"),
  clientType:    ClientType  = ClientType.Public,
  name:          Option[String] = None
)

case class AuthorizationRequest(
  responseType:        String,                       // "code"
  clientId:            String,
  redirectUri:         String,
  scope:               Set[String]    = Set.empty,
  state:               Option[String] = None,
  codeChallenge:       Option[String] = None,
  codeChallengeMethod: Option[String] = None
)

enum AuthorizationOutcome:
  /** Redirect the user-agent to redirect_uri?code=...&state=... */
  case CodeRedirect(redirectUri: String, code: String, state: Option[String])
  /** Redirect to redirect_uri with error parameters (RFC 6749 §4.1.2.1). */
  case ErrorRedirect(redirectUri: String, error: String, description: Option[String], state: Option[String])
  /** Cannot safely redirect (bad client_id / redirect_uri) — render
   *  an error page server-side. */
  case ErrorResponse(error: String, description: String)

object AuthorizationOutcome:
  /** Build the actual redirect URL for any redirect-shaped outcome.
   *  Returns `None` for `ErrorResponse` (cannot redirect — caller
   *  renders an error page server-side instead). */
  def redirectUrlFor(o: AuthorizationOutcome): Option[String] = o match
    case CodeRedirect(uri, code, state) =>
      val sb = new StringBuilder(uri)
      sb.append(if uri.contains("?") then "&" else "?")
      sb.append("code=").append(urlEncode(code))
      state.foreach(s => sb.append("&state=").append(urlEncode(s)))
      Some(sb.toString)
    case ErrorRedirect(uri, err, descr, state) =>
      val sb = new StringBuilder(uri)
      sb.append(if uri.contains("?") then "&" else "?")
      sb.append("error=").append(urlEncode(err))
      descr.foreach(d => sb.append("&error_description=").append(urlEncode(d)))
      state.foreach(s => sb.append("&state=").append(urlEncode(s)))
      Some(sb.toString)
    case ErrorResponse(_, _) => None

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

enum TokenRequest:
  case AuthorizationCodeGrant(
    code:         String,
    redirectUri:  String,
    clientId:     String,
    clientSecret: Option[String] = None,
    codeVerifier: Option[String] = None
  )
  case RefreshTokenGrant(
    refreshToken: String,
    scope:        Set[String]    = Set.empty,
    clientId:     String,
    clientSecret: Option[String] = None
  )
  case ClientCredentialsGrant(
    clientId:     String,
    clientSecret: String,
    scope:        Set[String]    = Set.empty
  )

case class TokenResponse(
  accessToken:  String,
  tokenType:    String        = "Bearer",
  expiresIn:    Long,
  refreshToken: Option[String] = None,
  scope:        Set[String]    = Set.empty
):
  def toJson: ujson.Value =
    val obj = ujson.Obj(
      "access_token" -> accessToken,
      "token_type"   -> tokenType,
      "expires_in"   -> ujson.Num(expiresIn.toDouble)
    )
    refreshToken.foreach(r => obj("refresh_token") = r)
    if scope.nonEmpty then
      obj("scope") = scope.toList.sorted.mkString(" ")
    obj

enum TokenOutcome:
  case Issued(response: TokenResponse)
  case Error(error: String, description: String)

/** Hint provided by clients on `/revoke` (`token_type_hint=` form field). */
enum TokenTypeHint:
  case AccessToken, RefreshToken, Unknown

/** Revocation outcome.  Per RFC 7009 §2.2 servers MUST respond 200 OK
 *  even when the token is unknown or already revoked — so the wire
 *  reply is uniform.  This enum lets callers log what actually
 *  happened for telemetry without exposing it on the wire. */
enum RevocationOutcome:
  case Revoked
  case Unknown
  case ClientError(code: String, description: String)

object TokenOutcome:
  extension (o: Error)
    def toJson: ujson.Value =
      ujson.Obj("error" -> o.error, "error_description" -> o.description)

case class IntrospectionResponse(
  active:    Boolean,
  subject:   Option[String] = None,
  scope:     Option[String] = None,
  clientId:  Option[String] = None,
  exp:       Option[Long]   = None,
  iat:       Option[Long]   = None,
  tokenType: Option[String] = None
):
  def toJson: ujson.Value =
    if !active then ujson.Obj("active" -> false)
    else
      val obj = ujson.Obj("active" -> true)
      subject.foreach  (s => obj("sub")        = s)
      scope.foreach    (s => obj("scope")      = s)
      clientId.foreach (c => obj("client_id")  = c)
      exp.foreach      (e => obj("exp")        = ujson.Num(e.toDouble))
      iat.foreach      (i => obj("iat")        = ujson.Num(i.toDouble))
      tokenType.foreach(t => obj("token_type") = t)
      obj

// ─── Storage records ──────────────────────────────────────────────────

case class AuthorizationCodeRecord(
  code:                String,
  clientId:            String,
  redirectUri:         String,
  scope:               Set[String],
  subject:             String,
  codeChallenge:       Option[String],
  codeChallengeMethod: Option[String],
  expiresAt:           Long
)

case class RefreshTokenRecord(
  token:     String,
  clientId:  String,
  subject:   String,
  scope:     Set[String],
  expiresAt: Long
)

// ─── Pluggable stores ─────────────────────────────────────────────────

trait ClientStore:
  def find(id: String): Option[Client]
  def register(c: Client): Unit
  def all: List[Client]

class InMemoryClientStore extends ClientStore:
  private val m = ConcurrentHashMap[String, Client]()
  def find(id: String): Option[Client] = Option(m.get(id))
  def register(c: Client): Unit        = m.put(c.id, c)
  def all: List[Client]                = scala.jdk.CollectionConverters.IteratorHasAsScala(
                                          m.values().iterator()).asScala.toList

trait TokenStore:
  def saveAuthorizationCode(rec: AuthorizationCodeRecord): Unit
  /** One-shot consumption — succeeds at most once per code (RFC 6749 §4.1.2). */
  def consumeAuthorizationCode(code: String): Option[AuthorizationCodeRecord]
  def saveRefreshToken(rec: RefreshTokenRecord): Unit
  def findRefreshToken(token: String): Option[RefreshTokenRecord]
  def revokeRefreshToken(token: String): Unit
  /** v1.17.x — access-token blacklist for RFC 7009.  Access tokens are
   *  stateless JWTs in our design; revocation adds the token's `jti`
   *  claim (or full token when no jti) to a deny-list that
   *  introspection / validation consult.  Empty by default. */
  def revokeAccessToken(jtiOrToken: String): Unit
  /** True iff the supplied identifier was previously passed to
   *  `revokeAccessToken`.  Callers normally lift the `jti` claim from
   *  the JWT payload before calling. */
  def isAccessRevoked(jtiOrToken: String): Boolean

class InMemoryTokenStore extends TokenStore:
  private val codes        = ConcurrentHashMap[String, AuthorizationCodeRecord]()
  private val refresh      = ConcurrentHashMap[String, RefreshTokenRecord]()
  private val accessDeny   = ConcurrentHashMap.newKeySet[String]()
  def saveAuthorizationCode(rec: AuthorizationCodeRecord): Unit = codes.put(rec.code, rec)
  def consumeAuthorizationCode(code: String): Option[AuthorizationCodeRecord] =
    Option(codes.remove(code))
  def saveRefreshToken(rec: RefreshTokenRecord): Unit  = refresh.put(rec.token, rec)
  def findRefreshToken(token: String): Option[RefreshTokenRecord] =
    Option(refresh.get(token))
  def revokeRefreshToken(token: String): Unit = { refresh.remove(token); () }
  def revokeAccessToken(jti: String): Unit    = { accessDeny.add(jti); () }
  def isAccessRevoked(jti: String): Boolean   = accessDeny.contains(jti)
