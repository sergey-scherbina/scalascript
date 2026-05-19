package scalascript.oidc

import scalascript.oauth.*

/** v1.17.x — pure HTTP route handlers for the OIDC layer.  Wraps the
 *  AS routes where needed (`/token` injects `id_token`), adds two new
 *  endpoints:
 *
 *    POST <base>/token           — same shape as OAuthRoutes.handleToken
 *                                   but the response carries an
 *                                   `id_token` when the openid scope
 *                                   was granted.
 *    GET  <base>/userinfo        — bearer-validated; returns the
 *                                   subject's scope-filtered claims.
 *    GET  <base>/.well-known/openid-configuration
 *                                — OIDC discovery document. */
object OidcRoutes:

  /** Token endpoint with id_token injection.  Routes through
   *  `oidc.issueToken(...)` so the OAuth-only path stays unchanged. */
  def handleToken(oidc: OidcServer, body: String, headers: Map[String, String]): OAuthRoutes.RouteOutcome =
    // Re-use OAuthRoutes form parsing but swap out the issuance step.
    val form        = OAuthRoutes.parseForm(body)
    val basicCreds  = OAuthRoutes.extractBasicAuth(headers)
    val grantType   = form.getOrElse("grant_type", "")
    val clientId    = basicCreds.map(_._1).orElse(form.get("client_id")).getOrElse("")
    val clientSec   = basicCreds.map(_._2).orElse(form.get("client_secret"))
    if grantType.isEmpty || clientId.isEmpty then
      return jsonError(400, "invalid_request", "missing grant_type or client_id")
    val tr: TokenRequest = grantType match
      case "authorization_code" =>
        TokenRequest.AuthorizationCodeGrant(
          code         = form.getOrElse("code", ""),
          redirectUri  = form.getOrElse("redirect_uri", ""),
          clientId     = clientId,
          clientSecret = clientSec,
          codeVerifier = form.get("code_verifier")
        )
      case "refresh_token" =>
        TokenRequest.RefreshTokenGrant(
          refreshToken = form.getOrElse("refresh_token", ""),
          scope        = OAuthRoutes.parseScope(form.get("scope")),
          clientId     = clientId,
          clientSecret = clientSec
        )
      case "client_credentials" =>
        TokenRequest.ClientCredentialsGrant(
          clientId     = clientId,
          clientSecret = clientSec.getOrElse(""),
          scope        = OAuthRoutes.parseScope(form.get("scope"))
        )
      case other =>
        return jsonError(400, "unsupported_grant_type", s"unsupported grant: $other")
    oidc.issueToken(tr) match
      case TokenOutcome.Issued(resp) =>
        OAuthRoutes.RouteOutcome.Json(200, resp.toJson,
          Map("Cache-Control" -> "no-store", "Pragma" -> "no-cache"))
      case TokenOutcome.Error(err, descr) =>
        val status = if err == "invalid_client" then 401 else 400
        jsonError(status, err, descr)

  /** Bearer-validated user info endpoint.  Token from `Authorization:
   *  Bearer <t>` or `access_token` form field (POST) / query (GET). */
  def handleUserInfo(
    oidc:    OidcServer,
    body:    String,
    headers: Map[String, String],
    query:   Map[String, String] = Map.empty
  ): OAuthRoutes.RouteOutcome =
    extractAccessToken(headers, body, query) match
      case None =>
        OAuthRoutes.RouteOutcome.Json(401,
          ujson.Obj("error" -> "invalid_token", "error_description" -> "missing access token"),
          Map("WWW-Authenticate" -> OAuth.wwwAuthenticate(oidc.as.config.issuer, "invalid_token")))
      case Some(token) =>
        oidc.userInfoFor(token) match
          case UserInfoOutcome.Found(claims)        => OAuthRoutes.RouteOutcome.Json(200, claims)
          case UserInfoOutcome.Unauthorized(c, d)   =>
            OAuthRoutes.RouteOutcome.Json(401,
              ujson.Obj("error" -> c, "error_description" -> d),
              Map("WWW-Authenticate" -> OAuth.wwwAuthenticate(oidc.as.config.issuer, c, Some(d))))
          case UserInfoOutcome.NotFound(_) =>
            OAuthRoutes.RouteOutcome.Json(401,
              ujson.Obj("error" -> "invalid_token", "error_description" -> "subject not known to IdP"))

  /** OIDC discovery document — separate route from the OAuth
   *  metadata so both can coexist.  Spec: `/.well-known/openid-configuration`. */
  def handleDiscovery(oidc: OidcServer): OAuthRoutes.RouteOutcome =
    OAuthRoutes.RouteOutcome.Json(200, oidc.discoveryJson(),
      Map("Cache-Control" -> "public, max-age=3600"))

  // ─── helpers ────────────────────────────────────────────────────────

  /** Try Authorization header first (the spec-preferred way), then form
   *  body, then query param.  Spec §5.3: implementations MUST support
   *  Authorization header, SHOULD also accept the others. */
  private def extractAccessToken(
    headers: Map[String, String],
    body:    String,
    query:   Map[String, String]
  ): Option[String] =
    OAuth.extractBearer(headers).toOption
      .orElse(OAuthRoutes.parseForm(body).get("access_token"))
      .orElse(query.get("access_token"))

  private def jsonError(status: Int, code: String, descr: String): OAuthRoutes.RouteOutcome =
    OAuthRoutes.RouteOutcome.Json(status,
      ujson.Obj("error" -> code, "error_description" -> descr))
