package scalascript.oauth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** v1.17.x — standalone OAuth 2.1 toolkit.  Independent of MCP; usable
 *  by anything that needs:
 *    - Bearer token primitives (RFC 6750)
 *    - Token validation (Resource Server role)
 *    - Token issuance (Authorization Server role — see `AuthServer`)
 *    - Protected-resource metadata (RFC 9728)
 *
 *  Design: pure data + decision functions in this object; the
 *  `AuthServer` class adds stateful issuer machinery on top.  MCP wires
 *  in via `scalascript.mcp.McpAuth` which re-exports these symbols. */
object OAuth:

  /** Successfully validated bearer token.  `scopes` mirrors the OAuth
   *  scope claim (space-separated list, conventionally lowercased);
   *  `extra` carries the raw JWT payload (or whatever the validator
   *  chose to attach) so handlers can read additional claims. */
  case class AuthClaims(
    subject: String,
    scopes:  Set[String],
    extra:   ujson.Value = ujson.Obj()
  ):
    def hasScope(scope: String): Boolean = scopes.contains(scope)

  /** Result of a token validation attempt.  `Invalid` carries the
   *  RFC 6750 error code so we can map it to a precise
   *  `WWW-Authenticate` header on the 401 reply. */
  enum AuthResult:
    case Valid(claims: AuthClaims)
    case Invalid(code: String, description: String)

  /** Validator alias.  Implementations receive the raw bearer token
   *  string (no `Bearer ` prefix) and return either accepted claims
   *  or a typed error.  Validators MUST be side-effect-free and
   *  thread-safe — typical HTTP routes invoke them concurrently. */
  type TokenValidator = String => AuthResult

  /** RFC 9728 "OAuth 2.0 Protected Resource Metadata" — the JSON
   *  document the resource server exposes at
   *  `/.well-known/oauth-protected-resource`.  Clients fetch it to
   *  discover the matching authorization server(s). */
  case class ProtectedResourceMetadata(
    resource:               String,
    authorizationServers:   List[String]   = Nil,
    scopesSupported:        List[String]   = Nil,
    bearerMethodsSupported: List[String]   = List("header"),
    resourceDocumentation:  Option[String] = None
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

  // ─── Bearer token primitives (RFC 6750) ─────────────────────────────

  /** Extract the bearer token from a JSON-ish headers map.  Lookup is
   *  case-insensitive on the header name; values are trimmed.  Returns
   *  `Right(token)` on success, `Left(errorCode)` per RFC 6750 §3.1. */
  def extractBearer(headers: Map[String, String]): Either[String, String] =
    headers.iterator
      .find((k, _) => k.equalsIgnoreCase("Authorization"))
      .map((_, v) => v.trim) match
      case None => Left("invalid_request")
      case Some(v) =>
        if !v.toLowerCase.startsWith("bearer ") then Left("invalid_request")
        else
          val token = v.substring(7).trim
          if token.isEmpty then Left("invalid_request") else Right(token)

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

  // ─── HMAC JWT-ish tokens (test + trusted-internal use) ──────────────

  /** Build a JWT-style HMAC-SHA256 bearer token.  Layout:
   *    `<base64url(headerJson)>.<base64url(payloadJson)>.<base64url(hmac)>`
   *  Caller supplies all the standard claims (`sub`, `scope`, `iat`,
   *  `exp`) plus any extra fields they want signed.  Useful for tests
   *  and trusted-internal deployments; production AS use a real signer. */
  def issueHmacToken(
    secret:           String,
    subject:          String,
    scopes:           Set[String],
    expiresInSeconds: Long,
    extra:            ujson.Value = ujson.Obj(),
    issuer:           Option[String]      = None,
    audience:         Option[String]      = None,
    clientId:         Option[String]      = None
  ): String =
    val now = java.time.Instant.now.getEpochSecond
    val header  = ujson.Obj("alg" -> "HS256", "typ" -> "JWT")
    val payload = ujson.Obj(
      "sub"   -> subject,
      "scope" -> scopes.toList.sorted.mkString(" "),
      "iat"   -> ujson.Num(now.toDouble),
      "exp"   -> ujson.Num((now + expiresInSeconds).toDouble)
    )
    issuer.foreach   (i => payload("iss")       = i)
    audience.foreach (a => payload("aud")       = a)
    clientId.foreach (c => payload("client_id") = c)
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
    decodeHmacToken(secret, token) match
      case Right(payload) =>
        val sub    = payload.obj.get("sub").flatMap(_.strOpt).getOrElse("")
        val scope  = payload.obj.get("scope").flatMap(_.strOpt).getOrElse("")
        val scopes = scope.split(' ').iterator.filter(_.nonEmpty).toSet
        AuthResult.Valid(AuthClaims(sub, scopes, payload))
      case Left(reason) =>
        AuthResult.Invalid("invalid_token", reason)

  /** Lower-level decode that exposes the payload to callers that need
   *  more than `AuthClaims`.  Returns `Left(reason)` for any structural
   *  / cryptographic / expiry failure. */
  def decodeHmacToken(secret: String, token: String): Either[String, ujson.Value] =
    try
      val parts = token.split('.')
      if parts.length != 3 then Left("malformed token")
      else
        val signingInput = parts(0) + "." + parts(1)
        val expectedSig  = b64u(hmacSha256(secret, signingInput))
        if !constantTimeEquals(parts(2), expectedSig) then
          Left("signature mismatch")
        else
          val payloadJson = ujson.read(decodeB64u(parts(1)))
          val now         = java.time.Instant.now.getEpochSecond
          val exp         = payloadJson.obj.get("exp").flatMap(_.numOpt).getOrElse(0.0).toLong
          if exp != 0 && now > exp then Left("token expired")
          else Right(payloadJson)
    catch case _: Throwable => Left("validation failure")

  // ─── PKCE (RFC 7636) ────────────────────────────────────────────────

  /** Compute the S256 code challenge for a given verifier:
   *    `base64url(sha256(verifier))`.  Verifiers must be 43–128 chars
   *  of `[A-Z][a-z][0-9]-._~` per RFC 7636 — caller responsibility. */
  def pkceS256(verifier: String): String =
    val md   = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(verifier.getBytes(StandardCharsets.UTF_8))
    b64u(hash)

  /** Constant-time PKCE verification: matches the verifier against the
   *  recorded challenge given the method (`S256` mandatory, `plain`
   *  accepted but discouraged per OAuth 2.1).  Unknown method → false. */
  def pkceMatches(verifier: String, challenge: String, method: String): Boolean =
    method match
      case "S256"  => constantTimeEquals(pkceS256(verifier), challenge)
      case "plain" => constantTimeEquals(verifier,           challenge)
      case _       => false

  // ─── Random token generation ────────────────────────────────────────

  /** Cryptographically-secure random opaque token (default 32 bytes →
   *  43-char base64url).  Used for opaque access / refresh / auth codes
   *  when not using the signed JWT format. */
  def randomOpaqueToken(bytes: Int = 32): String =
    val rng = new java.security.SecureRandom
    val buf = new Array[Byte](bytes)
    rng.nextBytes(buf)
    b64u(buf)

  // ─── internal helpers ───────────────────────────────────────────────

  private[oauth] def hmacSha256(secret: String, message: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(message.getBytes(StandardCharsets.UTF_8))

  private[oauth] def b64u(s: String): String =
    b64u(s.getBytes(StandardCharsets.UTF_8))
  private[oauth] def b64u(bs: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bs)
  private[oauth] def decodeB64u(s: String): String =
    new String(Base64.getUrlDecoder.decode(s), StandardCharsets.UTF_8)

  /** Constant-time string compare; defeats timing side-channels on
   *  signature verification.  No early exit on first mismatch. */
  private[oauth] def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i    = 0
      while i < a.length do
        diff |= (a.charAt(i) ^ b.charAt(i))
        i += 1
      diff == 0

  private[oauth] def escapeQ(s: String): String = s.replace("\"", "\\\"")
