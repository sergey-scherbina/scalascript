package scalascript.oauth

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** v1.17.x — pure-function HTTP handlers for the standard OAuth 2.1
 *  endpoints (`/token`, `/introspect`, `/register`, `/authorize`, plus
 *  the `/.well-known/oauth-authorization-server` discovery doc).
 *
 *  Each handler returns a typed `RouteOutcome` so the caller (any HTTP
 *  framework — Jdk HTTPServer, Netty, our own WebServer, …) can render
 *  it through its own response API.  No interpreter / framework
 *  coupling lives in this object.
 *
 *  Outline:
 *  ```
 *  // body / headers come from your HTTP layer
 *  val out = OAuthRoutes.handleToken(as, requestBody, requestHeaders)
 *  // out is a RouteOutcome.Json(...) or RouteOutcome.Redirect(...)
 *  ``` */
object OAuthRoutes:

  /** Wire-ready result of running one OAuth route. */
  enum RouteOutcome:
    case Json(status: Int, body: ujson.Value, extraHeaders: Map[String, String] = Map.empty)
    case Redirect(status: Int, location: String)
    case Empty(status: Int)

  // ─── /token ─────────────────────────────────────────────────────────

  /** RFC 6749 §3.2 token endpoint.  Body is `application/x-www-form-urlencoded`;
   *  decoded fields drive the choice of grant type.  Client authentication
   *  may come via HTTP Basic in `Authorization` (RFC 6749 §2.3.1) or via
   *  `client_id` + `client_secret` form fields (§2.3.1 alternative). */
  def handleToken(as: AuthServer, body: String, headers: Map[String, String]): RouteOutcome =
    val form        = parseForm(body)
    val basicCreds  = extractBasicAuth(headers)
    val grantType   = form.getOrElse("grant_type", "")
    val clientId    = basicCreds.map(_._1).orElse(form.get("client_id")).getOrElse("")
    val clientSec   = basicCreds.map(_._2).orElse(form.get("client_secret"))
    if grantType.isEmpty || clientId.isEmpty then
      return jsonError(400, "invalid_request", "missing grant_type or client_id")
    val outcome: TokenOutcome = grantType match
      case "authorization_code" =>
        as.issueToken(TokenRequest.AuthorizationCodeGrant(
          code         = form.getOrElse("code", ""),
          redirectUri  = form.getOrElse("redirect_uri", ""),
          clientId     = clientId,
          clientSecret = clientSec,
          codeVerifier = form.get("code_verifier")
        ))
      case "refresh_token" =>
        as.issueToken(TokenRequest.RefreshTokenGrant(
          refreshToken = form.getOrElse("refresh_token", ""),
          scope        = parseScope(form.get("scope")),
          clientId     = clientId,
          clientSecret = clientSec
        ))
      case "client_credentials" =>
        as.issueToken(TokenRequest.ClientCredentialsGrant(
          clientId     = clientId,
          clientSecret = clientSec.getOrElse(""),
          scope        = parseScope(form.get("scope"))
        ))
      case TokenRequest.PasskeyGrantType =>
        // Passkey assertion grant — all binary fields arrive as
        // base64url strings on the wire.
        try
          as.issueToken(TokenRequest.PasskeyAssertionGrant(
            credentialId = form.getOrElse("credential_id", ""),
            challenge    = form.getOrElse("challenge", ""),
            signedData   = Passkey.b64uDecode(form.getOrElse("signed_data", "")),
            signature    = Passkey.b64uDecode(form.getOrElse("signature", "")),
            scope        = parseScope(form.get("scope")),
            clientId     = clientId,
            clientSecret = clientSec
          ))
        catch case _: Throwable =>
          TokenOutcome.Error("invalid_request",
            "passkey grant: missing or malformed signed_data / signature (expected base64url)")
      case other =>
        TokenOutcome.Error("unsupported_grant_type", s"unsupported grant: $other")
    outcome match
      case TokenOutcome.Issued(resp) =>
        RouteOutcome.Json(200, resp.toJson,
          // RFC 6749 §5.1: prevent intermediaries from caching token responses
          Map("Cache-Control" -> "no-store", "Pragma" -> "no-cache"))
      case TokenOutcome.Error(err, descr) =>
        val status = if err == "invalid_client" then 401 else 400
        jsonError(status, err, descr)

  // ─── /introspect (RFC 7662) ─────────────────────────────────────────

  /** Token introspection endpoint.  Spec says it MUST be authenticated;
   *  for v1 we trust callers behind whatever auth they have wired up.
   *  Add resource-server auth via the `validateCaller` hook if needed. */
  def handleIntrospect(
    as:              AuthServer,
    body:            String,
    headers:         Map[String, String],
    validateCaller:  Map[String, String] => Boolean = _ => true
  ): RouteOutcome =
    if !validateCaller(headers) then
      jsonError(401, "invalid_client", "introspection endpoint requires authentication")
    else
      val form  = parseForm(body)
      form.get("token") match
        case None        => jsonError(400, "invalid_request", "missing 'token' parameter")
        case Some(token) => RouteOutcome.Json(200, as.introspect(token).toJson)

  // ─── /register (RFC 7591 Dynamic Client Registration) ──────────────

  /** Body is `application/json`.  On success returns 201 with the
   *  registration response document; otherwise 400 with the spec error. */
  def handleRegister(as: AuthServer, body: String, @annotation.unused headers: Map[String, String]): RouteOutcome =
    val metadata =
      try ujson.read(body)
      catch case _: Throwable =>
        return jsonError(400, "invalid_client_metadata", "request body is not valid JSON")
    as.registerClient(metadata) match
      case Right(client) => RouteOutcome.Json(201, as.registrationResponseJson(client))
      case Left("registration_disabled") =>
        jsonError(403, "registration_disabled", "dynamic client registration is disabled")
      case Left(err) => jsonError(400, err, s"client metadata rejected: $err")

  // ─── /passkey/challenge — server-issued nonce for assertion flow ───

  /** Hand out a single-use challenge for the user's browser to feed
   *  into `navigator.credentials.get(...)`.  Returns 200 with
   *  `{"challenge": "<base64url>"}`; the challenge is short-lived
   *  (default 5 minutes) and consumed on first verification. */
  def handlePasskeyChallenge(as: AuthServer): RouteOutcome =
    RouteOutcome.Json(200,
      ujson.Obj("challenge" -> as.passkeyChallenge()),
      Map("Cache-Control" -> "no-store"))

  // ─── /revoke (RFC 7009) ─────────────────────────────────────────────

  /** Revocation endpoint.  Body is form-urlencoded with `token` (req'd)
   *  and optional `token_type_hint` (`access_token` | `refresh_token`).
   *  Per RFC 7009 §2.2 we MUST reply 200 OK even when the token is
   *  unknown, so callers can't probe for token validity through this
   *  endpoint.  Missing `token` param → 400 invalid_request. */
  def handleRevoke(as: AuthServer, body: String, headers: Map[String, String]): RouteOutcome =
    val form = parseForm(body)
    form.get("token") match
      case None        => jsonError(400, "invalid_request", "missing 'token' parameter")
      case Some(token) =>
        val hint = form.get("token_type_hint") match
          case Some("access_token")  => TokenTypeHint.AccessToken
          case Some("refresh_token") => TokenTypeHint.RefreshToken
          case _                      => TokenTypeHint.Unknown
        as.revokeToken(token, hint)  // outcome is intentionally ignored on the wire
        // Client authentication SHOULD be required (RFC 7009 §2.1) — we
        // leave that policy to the caller via headers; revoking a token
        // they don't own is harmless in practice (single-use rotation
        // already invalidates stolen tokens).
        val _ = headers
        RouteOutcome.Empty(200)

  // ─── /.well-known/oauth-authorization-server (RFC 8414) ────────────

  /** Discovery document — always 200, plain GET.  The Cache-Control
   *  hint matches what most ASs send so clients don't hammer it. */
  def handleMetadata(as: AuthServer): RouteOutcome =
    RouteOutcome.Json(200, as.metadataJson(),
      Map("Cache-Control" -> "public, max-age=3600"))

  // ─── /.well-known/jwks.json (RFC 7517) ──────────────────────────────

  /** Publishes the AS's public keys as a JSON Web Key Set.  Symmetric
   *  signers (HS256) produce an empty `keys` array — they have no
   *  public material to share.  RS256 / ES256 signers contribute their
   *  public JWK so resource servers can validate tokens locally.
   *  Cache-Control matches typical AS practice. */
  def handleJwks(as: AuthServer): RouteOutcome =
    RouteOutcome.Json(200, as.jwksJson,
      Map("Cache-Control" -> "public, max-age=3600"))

  // ─── /authorize ─────────────────────────────────────────────────────

  /** Authorization endpoint.  RFC 6749 §4.1.1.  Query params drive
   *  every decision; `subjectFor(headers)` resolves the currently-
   *  authenticated user (None → caller hasn't authenticated yet).
   *
   *  Behaviour:
   *    - No subject  → `Redirect` to `loginUrl(originalUrl)` so the
   *      caller's UI can run login + bounce back here.
   *    - Subject + valid request → 302 Redirect to client redirect_uri
   *      with `?code=...&state=...`
   *    - Subject + recoverable error → 302 Redirect to redirect_uri
   *      with `?error=...&state=...`
   *    - Unrecoverable error (bad client/redirect_uri) → 400 JSON */
  def handleAuthorize(
    as:           AuthServer,
    query:        Map[String, String],
    headers:      Map[String, String],
    subjectFor:   Map[String, String] => Option[String],
    loginUrl:     Option[String => String] = None,
    selfUrl:      Option[String]           = None
  ): RouteOutcome =
    val req = AuthorizationRequest(
      responseType        = query.getOrElse("response_type", ""),
      clientId            = query.getOrElse("client_id", ""),
      redirectUri         = query.getOrElse("redirect_uri", ""),
      scope               = parseScope(query.get("scope")),
      state               = query.get("state"),
      codeChallenge       = query.get("code_challenge"),
      codeChallengeMethod = query.get("code_challenge_method"),
      nonce               = query.get("nonce")
    )
    subjectFor(headers) match
      case None =>
        // User not authenticated yet — bounce to login, preserving the
        // current authorize URL so the UI can come back to it.
        (loginUrl, selfUrl) match
          case (Some(builder), Some(self)) =>
            RouteOutcome.Redirect(302, builder(self + "?" + queryString(query)))
          case _ =>
            jsonError(401, "login_required",
              "user authentication required (configure loginUrl + selfUrl)")
      case Some(subject) =>
        as.issueAuthorizationCode(req, subject) match
          case o: AuthorizationOutcome.CodeRedirect =>
            RouteOutcome.Redirect(302, AuthorizationOutcome.redirectUrlFor(o).get)
          case o: AuthorizationOutcome.ErrorRedirect =>
            RouteOutcome.Redirect(302, AuthorizationOutcome.redirectUrlFor(o).get)
          case AuthorizationOutcome.ErrorResponse(err, descr) =>
            jsonError(400, err, descr)

  // ─── internal helpers ───────────────────────────────────────────────

  private def jsonError(status: Int, error: String, description: String): RouteOutcome =
    RouteOutcome.Json(status,
      ujson.Obj("error" -> error, "error_description" -> description))

  /** Parse `application/x-www-form-urlencoded` into a `Map[String, String]`.
   *  Multi-value keys collapse to the LAST occurrence per RFC 6749 §3.2
   *  recommendation.  Empty input → empty map. */
  def parseForm(body: String): Map[String, String] =
    if body == null || body.isEmpty then Map.empty
    else
      body.split('&').iterator.flatMap { pair =>
        val eq = pair.indexOf('=')
        if eq < 0 then None
        else
          val k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8)
          val v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
          Some(k -> v)
      }.toMap

  /** Parse space-separated scope string into a Set; None → empty. */
  def parseScope(s: Option[String]): Set[String] =
    s.map(_.split(' ').iterator.filter(_.nonEmpty).toSet).getOrElse(Set.empty)

  /** Encode a query map back to a `k=v&…` string for redirect URLs.
   *  Stable ordering for testability. */
  def queryString(q: Map[String, String]): String =
    q.toList.sortBy(_._1).iterator.map { (k, v) =>
      val ek = java.net.URLEncoder.encode(k, StandardCharsets.UTF_8)
      val ev = java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)
      s"$ek=$ev"
    }.mkString("&")

  /** Decode HTTP Basic credentials from the `Authorization` header.
   *  Returns `Some((clientId, clientSecret))` on a well-formed header;
   *  None for missing / non-Basic / malformed base64.  Per RFC 6749
   *  §2.3.1 the username is the client_id, password is client_secret. */
  def extractBasicAuth(headers: Map[String, String]): Option[(String, String)] =
    headers.iterator
      .find((k, _) => k.equalsIgnoreCase("Authorization"))
      .map((_, v) => v.trim)
      .flatMap { v =>
        if !v.toLowerCase.startsWith("basic ") then None
        else
          try
            val raw = new String(
              Base64.getDecoder.decode(v.substring(6).trim),
              StandardCharsets.UTF_8)
            val colon = raw.indexOf(':')
            if colon < 0 then None
            else Some((raw.substring(0, colon), raw.substring(colon + 1)))
          catch case _: Throwable => None
      }
