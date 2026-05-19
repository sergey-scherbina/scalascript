package scalascript.mcp

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** v1.17.x — MCP authorization layer.  The server is treated as an
 *  OAuth 2.1 Resource Server: every HTTP request must carry an
 *  `Authorization: Bearer <token>` header that the registered
 *  `TokenValidator` accepts.
 *
 *  Validator API is intentionally pluggable so users can wire it to
 *  whatever auth scheme they actually run:
 *    - JWT signature check (HMAC / RSA / EC)
 *    - Token introspection (RFC 7662) against an external AS
 *    - In-memory test tokens
 *    - Their own scheme entirely
 *
 *  We ship a built-in HMAC validator + issuer mainly for tests and
 *  small dev deployments.  Production setups SHOULD wire their real
 *  AS via `setTokenValidator(...)` directly. */
object McpAuth:

  /** Successfully validated bearer token.  `scopes` mirrors the OAuth
   *  scope claim (space-separated list, conventionally lowercased);
   *  `extra` carries the raw JWT payload (or whatever the validator
   *  chose to attach) so handlers can read additional claims. */
  case class AuthClaims(
    subject: String,
    scopes:  Set[String],
    extra:   ujson.Value = ujson.Obj()
  ):
    /** True iff the token authorizes the given scope. */
    def hasScope(scope: String): Boolean = scopes.contains(scope)

  /** Result of a token validation attempt.  `Invalid` carries the
   *  RFC 6750 error code so we can map it to a precise
   *  `WWW-Authenticate` header on the 401 reply. */
  enum AuthResult:
    case Valid(claims: AuthClaims)
    /** code: `invalid_token` (default), `insufficient_scope`,
     *  `invalid_request` — per RFC 6750. */
    case Invalid(code: String, description: String)

  /** Validator alias.  Implementations receive the raw bearer token
   *  string (no `Bearer ` prefix) and return either accepted claims
   *  or a typed error.  Validators MUST be side-effect-free and
   *  thread-safe — the HTTP route runs concurrent calls. */
  type TokenValidator = String => AuthResult

  /** RFC 9728 "OAuth 2.0 Protected Resource Metadata" — the JSON
   *  document the resource server exposes at
   *  `/.well-known/oauth-protected-resource`.  Clients fetch it to
   *  discover the matching authorization server(s).  All fields are
   *  optional in our model — user sets whichever they want exposed. */
  case class ProtectedResourceMetadata(
    resource:              String,
    authorizationServers:  List[String]   = Nil,
    scopesSupported:       List[String]   = Nil,
    bearerMethodsSupported: List[String]  = List("header"),
    resourceDocumentation: Option[String] = None
  ):
    def toJson: ujson.Value =
      val obj = ujson.Obj("resource" -> resource)
      if authorizationServers.nonEmpty then
        obj("authorization_servers") = ujson.Arr.from(authorizationServers.map(ujson.Str(_)))
      if scopesSupported.nonEmpty then
        obj("scopes_supported") = ujson.Arr.from(scopesSupported.map(ujson.Str(_)))
      if bearerMethodsSupported.nonEmpty then
        obj("bearer_methods_supported") =
          ujson.Arr.from(bearerMethodsSupported.map(ujson.Str(_)))
      resourceDocumentation.foreach(d => obj("resource_documentation") = d)
      obj

  // ─── HMAC test validator + issuer ───────────────────────────────────

  /** Build a JWT-style HMAC-SHA256 bearer token.  Useful for tests and
   *  trusted-internal deployments where rotating a shared secret is
   *  acceptable.  Production: use a real OAuth AS instead.
   *
   *  Layout: `<base64url(headerJson)>.<base64url(payloadJson)>.<base64url(hmac)>`
   *  where the signed message is `header.payload` (the part before the
   *  final dot).  `exp` and `iat` are unix-second timestamps. */
  def issueHmacToken(
    secret:           String,
    subject:          String,
    scopes:           Set[String],
    expiresInSeconds: Long,
    extra:            ujson.Value = ujson.Obj()
  ): String =
    val now = java.time.Instant.now.getEpochSecond
    val header  = ujson.Obj("alg" -> "HS256", "typ" -> "JWT")
    val payload = ujson.Obj(
      "sub"   -> subject,
      "scope" -> scopes.toList.sorted.mkString(" "),
      "iat"   -> ujson.Num(now.toDouble),
      "exp"   -> ujson.Num((now + expiresInSeconds).toDouble)
    )
    extra match
      case obj: ujson.Obj => obj.value.foreach((k, v) => payload(k) = v)
      case _              => ()
    val signingInput = b64u(header.render()) + "." + b64u(payload.render())
    val sig = hmacSha256(secret, signingInput)
    signingInput + "." + b64u(sig)

  /** Validator that accepts tokens issued by `issueHmacToken` with the
   *  same secret.  Rejects expired tokens (`exp` past current clock)
   *  and signature mismatches.  Returns Invalid("invalid_token", ...)
   *  for any structural / cryptographic failure — leaks no specifics
   *  per RFC 6750 §3.1 hardening guidance. */
  def hmacValidator(secret: String): TokenValidator = token =>
    try
      val parts = token.split('.')
      if parts.length != 3 then AuthResult.Invalid("invalid_token", "malformed token")
      else
        val signingInput = parts(0) + "." + parts(1)
        val expectedSig  = b64u(hmacSha256(secret, signingInput))
        if !constantTimeEquals(parts(2), expectedSig) then
          AuthResult.Invalid("invalid_token", "signature mismatch")
        else
          val payloadJson = ujson.read(decodeB64u(parts(1)))
          val now         = java.time.Instant.now.getEpochSecond
          val exp         = payloadJson.obj.get("exp").flatMap(_.numOpt).getOrElse(0.0).toLong
          if exp != 0 && now > exp then
            AuthResult.Invalid("invalid_token", "token expired")
          else
            val sub    = payloadJson.obj.get("sub").flatMap(_.strOpt).getOrElse("")
            val scope  = payloadJson.obj.get("scope").flatMap(_.strOpt).getOrElse("")
            val scopes = scope.split(' ').iterator.filter(_.nonEmpty).toSet
            AuthResult.Valid(AuthClaims(sub, scopes, payloadJson))
    catch case _: Throwable => AuthResult.Invalid("invalid_token", "validation failure")

  /** Build the `WWW-Authenticate` header value for a 401 reply.
   *  Per RFC 6750 §3 the scheme is `Bearer`; `realm`, `error`,
   *  `error_description`, and `scope` are the standard parameters. */
  def wwwAuthenticate(
    realm:            String,
    error:            String         = "invalid_token",
    errorDescription: Option[String] = None,
    scope:            Option[String] = None
  ): String =
    val sb = new StringBuilder(s"""Bearer realm="$realm", error="$error"""")
    errorDescription.foreach(d => sb.append(s""", error_description="${escapeQ(d)}""""))
    scope.foreach(s            => sb.append(s""", scope="$s""""))
    sb.toString

  /** Extract the bearer token from a JSON-ish headers map.  Lookup is
   *  case-insensitive on the header name; values are trimmed.  Returns
   *  `Right(token)` on success, `Left(errorCode)` per RFC 6750 §3.1. */
  def extractBearer(headers: Map[String, String]): Either[String, String] =
    headers.iterator
      .find((k, _) => k.equalsIgnoreCase("Authorization"))
      .map((_, v) => v.trim) match
      case None => Left("invalid_request")
      case Some(v) =>
        val lower = v.toLowerCase
        if !lower.startsWith("bearer ") then Left("invalid_request")
        else
          val token = v.substring(7).trim
          if token.isEmpty then Left("invalid_request") else Right(token)

  // ─── internal helpers ───────────────────────────────────────────────

  private def hmacSha256(secret: String, message: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(message.getBytes(StandardCharsets.UTF_8))

  private def b64u(s: String): String =
    b64u(s.getBytes(StandardCharsets.UTF_8))
  private def b64u(bs: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bs)
  private def decodeB64u(s: String): String =
    new String(Base64.getUrlDecoder.decode(s), StandardCharsets.UTF_8)

  /** Length-and-bit constant-time compare; defeats timing side-channels
   *  on signature verification.  No early exit on first mismatch. */
  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i    = 0
      while i < a.length do
        diff |= (a.charAt(i) ^ b.charAt(i))
        i += 1
      diff == 0

  private def escapeQ(s: String): String = s.replace("\"", "\\\"")
