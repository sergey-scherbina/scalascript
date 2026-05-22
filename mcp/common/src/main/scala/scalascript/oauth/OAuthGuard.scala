package scalascript.oauth

/** v1.17.x — generic Resource Server SDK.  Anything that serves an
 *  HTTP API can wrap its route handlers in OAuth bearer-token
 *  validation with a single call:
 *
 *  ```
 *  val decision = OAuthGuard.check(req.headers, as.tokenValidator,
 *                                  requiredScopes = Set("read"),
 *                                  realm = "my-api")
 *  decision match
 *    case GuardDecision.Allow(claims)   => myHandler(req, claims)
 *    case GuardDecision.Deny(outcome)   => outcome  // wire 401/403 back
 *  ```
 *
 *  The MCP server uses the same decision logic internally; this module
 *  surfaces it so non-MCP services get the same affordances. */
object OAuthGuard:

  /** Outcome of a single bearer-token check. */
  enum GuardDecision:
    /** Token is valid + scopes satisfied — caller may invoke the handler. */
    case Allow(claims: OAuth.AuthClaims)
    /** Token is missing / invalid / lacks required scopes.  Carries the
     *  fully-shaped RouteOutcome so callers can return it directly. */
    case Deny(routeOutcome: OAuthRoutes.RouteOutcome)

  /** Decide whether the inbound request can proceed.  Pure function:
   *  no side effects, safe to call from any thread.  Inputs:
   *    - headers           — request headers (case-insensitive lookup)
   *    - validator         — bearer validator (typically as.tokenValidator)
   *    - requiredScopes    — scopes ALL of which the token MUST advertise
   *    - realm             — advertised in WWW-Authenticate on 401/403
   *
   *  Outcome:
   *    - Allow(claims)             when token validates + scopes pass
   *    - Deny(401 invalid_request) when no/malformed Authorization header
   *    - Deny(401 invalid_token)   when validator rejects the token
   *    - Deny(403 insufficient_scope) when scopes don't cover requiredScopes */
  def check(
    headers:           Map[String, String],
    validator:         OAuth.TokenValidator,
    requiredScopes:    Set[String]    = Set.empty,
    realm:             String          = "api",
    /** v1.17.x — expected `aud` (audience) claim.  When set, the token's
     *  `aud` MUST match — defeats the "token issued for another RS gets
     *  honoured at this RS" attack.  None = no audience check (the
     *  default, for tokens that don't carry an audience). */
    expectedAudience:  Option[String] = None
  ): GuardDecision =
    OAuth.extractBearer(headers) match
      case Left(code) =>
        GuardDecision.Deny(unauthorized(realm, code, "missing or malformed Authorization header"))
      case Right(token) => validator(token) match
        case OAuth.AuthResult.Invalid(code, descr) =>
          GuardDecision.Deny(unauthorized(realm, code, descr))
        case OAuth.AuthResult.Valid(claims) =>
          // v1.17.x — audience binding check.  Refuses tokens whose
          // `aud` doesn't match this RS's identifier.
          val audOk = expectedAudience match
            case Some(expected) => audienceOf(claims.extra).contains(expected)
            case None           => true
          if !audOk then
            GuardDecision.Deny(unauthorized(realm, "invalid_token",
              "token audience does not match this resource server"))
          else
            val missing = requiredScopes -- claims.scopes
            if missing.nonEmpty then
              GuardDecision.Deny(insufficientScope(realm, requiredScopes, missing))
            else
              GuardDecision.Allow(claims)

  /** Extract the set of audiences a JWT's payload declares.  Per RFC
   *  7519 §4.1.3, `aud` MAY be either a string or an array of strings;
   *  callers MUST accept both. */
  private def audienceOf(payload: ujson.Value): Set[String] =
    try payload.obj.get("aud") match
      case Some(ujson.Str(s))  => Set(s)
      case Some(ujson.Arr(xs)) => xs.iterator.flatMap(_.strOpt).toSet
      case _                    => Set.empty
    catch case _: Throwable => Set.empty

  /** Convenience: just say yes/no without surfacing the claims.  Useful
   *  for boolean middleware-style checks where the handler doesn't need
   *  the validated identity. */
  def allows(
    headers:        Map[String, String],
    validator:      OAuth.TokenValidator,
    requiredScopes: Set[String] = Set.empty
  ): Boolean = check(headers, validator, requiredScopes) match
    case GuardDecision.Allow(_) => true
    case _                       => false

  // ─── helpers ────────────────────────────────────────────────────────

  /** Build a 401 with `WWW-Authenticate` per RFC 6750 §3. */
  def unauthorized(realm: String, error: String, description: String): OAuthRoutes.RouteOutcome =
    OAuthRoutes.RouteOutcome.Json(401,
      ujson.Obj("error" -> error, "error_description" -> description),
      Map("WWW-Authenticate" -> OAuth.wwwAuthenticate(realm, error, Some(description))))

  /** Build a 403 `insufficient_scope` per RFC 6750 §3.1; `scope`
   *  parameter advertises the required scopes so the client can request
   *  a token with the right ones next time. */
  def insufficientScope(
    realm:          String,
    requiredScopes: Set[String],
    missing:        Set[String]
  ): OAuthRoutes.RouteOutcome =
    val descr = s"required scopes missing: ${missing.toList.sorted.mkString(" ")}"
    val www = OAuth.wwwAuthenticate(
      realm, "insufficient_scope",
      Some(descr),
      Some(requiredScopes.toList.sorted.mkString(" ")))
    OAuthRoutes.RouteOutcome.Json(403,
      ujson.Obj(
        "error"             -> "insufficient_scope",
        "error_description" -> descr,
        "scope"             -> requiredScopes.toList.sorted.mkString(" ")),
      Map("WWW-Authenticate" -> www))
