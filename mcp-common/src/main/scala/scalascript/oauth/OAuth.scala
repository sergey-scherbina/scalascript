package scalascript.oauth

import java.nio.charset.StandardCharsets
import java.security.{KeyPairGenerator, MessageDigest, Signature}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
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

  // ─── Pluggable JWT signers ─────────────────────────────────────────

  /** v1.17.x — JWT signing abstraction.  Default deployments use the
   *  HS256 (HMAC) signer; production cross-service deployments wire in
   *  RS256 (RSA-SHA256) so resource servers can validate via the AS's
   *  published JWKS without sharing a symmetric secret. */
  trait TokenSigner:
    def alg: String
    /** Optional key id — embedded in the JWT header so multi-key
     *  validators (JWKS clients) can pick the matching public key. */
    def kid: Option[String] = None
    /** Sign the supplied JWT claims payload, returning the wire-ready
     *  `header.payload.signature` string. */
    def sign(payload: ujson.Value): String
    /** Verify a presented JWT.  Returns Right(payload) on success;
     *  Left(reason) on bad signature / malformed / expired. */
    def verify(token: String): Either[String, ujson.Value]
    /** Public JWK representation for this signer.  None for symmetric
     *  signers — HMAC keys MUST NOT be published. */
    def publicJwk: Option[ujson.Value] = None

  /** HS256 HMAC-SHA256 signer.  Symmetric — both AS and RS must share
   *  the secret.  Suitable for single-process deployments and tests. */
  class HmacTokenSigner(
    val secret:       String,
    override val kid: Option[String] = None
  ) extends TokenSigner:
    val alg = "HS256"
    def sign(payload: ujson.Value): String =
      val header = ujson.Obj("alg" -> "HS256", "typ" -> "JWT")
      kid.foreach(k => header("kid") = k)
      val signingInput = b64u(header.render()) + "." + b64u(payload.render())
      val sig          = hmacSha256(secret, signingInput)
      signingInput + "." + b64u(sig)
    def verify(token: String): Either[String, ujson.Value] =
      decodeHmacToken(secret, token)

  /** RS256 RSA-SHA256 signer.  Asymmetric — only the AS holds the
   *  private key; resource servers fetch the public key via JWKS.
   *  Generate via `RsaTokenSigner.generate(kid)` for a fresh 2048-bit
   *  RSA key pair, or wire in your own KMS-managed pair. */
  class RsaTokenSigner(
    val privateKey:   RSAPrivateKey,
    val publicKey:    RSAPublicKey,
    override val kid: Option[String] = None
  ) extends TokenSigner:
    val alg = "RS256"
    def sign(payload: ujson.Value): String =
      val header = ujson.Obj("alg" -> "RS256", "typ" -> "JWT")
      kid.foreach(k => header("kid") = k)
      val signingInput = b64u(header.render()) + "." + b64u(payload.render())
      val sig          = rsaSign(privateKey, signingInput)
      signingInput + "." + b64u(sig)
    def verify(token: String): Either[String, ujson.Value] =
      try
        val parts = token.split('.')
        if parts.length != 3 then Left("malformed token")
        else
          val signingInput = parts(0) + "." + parts(1)
          val sigBytes     = Base64.getUrlDecoder.decode(parts(2))
          if !rsaVerify(publicKey, signingInput, sigBytes) then
            Left("signature mismatch")
          else
            val payload = ujson.read(decodeB64u(parts(1)))
            validateJwtTimestamps(payload, DefaultClockSkewSeconds) match
              case Some(reason) => Left(reason)
              case None         => Right(payload)
      catch case _: Throwable => Left("validation failure")
    override def publicJwk: Option[ujson.Value] =
      Some(rsaPublicJwk(publicKey, kid))

  object RsaTokenSigner:
    /** Generate a fresh 2048-bit RSA key pair and wrap as a signer.
     *  `kid` controls the key id embedded in the JWT header + JWK
     *  published via the JWKS endpoint. */
    def generate(kid: String = "rsa-key-1"): RsaTokenSigner =
      val gen = KeyPairGenerator.getInstance("RSA")
      gen.initialize(2048)
      val kp = gen.generateKeyPair()
      new RsaTokenSigner(
        kp.getPrivate.asInstanceOf[RSAPrivateKey],
        kp.getPublic.asInstanceOf[RSAPublicKey],
        Some(kid))

  /** JWKS (JSON Web Key Set) document — collects the public JWKs from
   *  one or more signers.  RFC 7517.  Symmetric signers (HMAC)
   *  contribute nothing — their keys MUST NOT leave the AS. */
  def jwksDocument(signers: List[TokenSigner]): ujson.Value =
    ujson.Obj("keys" -> ujson.Arr.from(signers.flatMap(_.publicJwk)))

  // ─── Standard JWT payload helpers ───────────────────────────────────

  /** Build the standard access-token claim set — the same shape used by
   *  the OAuth issuer regardless of signing algorithm.  Caller supplies
   *  the signer separately. */
  def buildAccessTokenPayload(
    subject:          String,
    scopes:           Set[String],
    expiresInSeconds: Long,
    issuer:           Option[String] = None,
    audience:         Option[String] = None,
    clientId:         Option[String] = None,
    extra:            ujson.Value    = ujson.Obj()
  ): ujson.Value =
    val now = java.time.Instant.now.getEpochSecond
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
    payload

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

  /** v1.17.x — default clock-skew tolerance window for JWT exp / iat /
   *  nbf checks.  Mirrors common library defaults (Okta / Auth0 / Keycloak
   *  all use ±60s).  Callers can override via the explicit decode
   *  helpers below. */
  val DefaultClockSkewSeconds: Long = 60L

  /** Lower-level decode that exposes the payload to callers that need
   *  more than `AuthClaims`.  Returns `Left(reason)` for any structural
   *  / cryptographic / expiry failure.  Honours `clockSkewSeconds`
   *  tolerance on the `exp` check so legitimate tokens don't silently
   *  fail across a tiny clock drift between AS and RS. */
  def decodeHmacToken(
    secret:           String,
    token:            String,
    clockSkewSeconds: Long = DefaultClockSkewSeconds
  ): Either[String, ujson.Value] =
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
          validateJwtTimestamps(payloadJson, clockSkewSeconds) match
            case Some(reason) => Left(reason)
            case None         => Right(payloadJson)
    catch case _: Throwable => Left("validation failure")

  /** Validate the standard JWT timestamp claims (`exp`, `nbf`, `iat`)
   *  against the current clock with a tolerance window.  Returns
   *  `Some(reason)` on any failure or `None` when the token is within
   *  bounds.  Missing claims are not enforced — only enforce what's
   *  present (matches typical OAuth ecosystem behaviour). */
  def validateJwtTimestamps(payload: ujson.Value, clockSkewSeconds: Long): Option[String] =
    try
      val now  = java.time.Instant.now.getEpochSecond
      val exp  = payload.obj.get("exp").flatMap(_.numOpt).map(_.toLong)
      val nbf  = payload.obj.get("nbf").flatMap(_.numOpt).map(_.toLong)
      val iat  = payload.obj.get("iat").flatMap(_.numOpt).map(_.toLong)
      // `exp` is past now + skew → expired.  Zero is treated as "not set".
      if exp.exists(e => e != 0 && now > e + clockSkewSeconds) then
        Some("token expired")
      // `nbf` is future, beyond the skew window → not-yet-valid.
      else if nbf.exists(n => now < n - clockSkewSeconds) then
        Some("token not yet valid (nbf in the future)")
      // `iat` is far in the future — a sign of a clock-skewed AS or a
      // forged token.  Allow 1h of grace before flagging.
      else if iat.exists(i => now < i - 3600L) then
        Some("token issued in the future")
      else None
    catch case _: Throwable => Some("malformed timestamps")

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

  // ─── Client secret hashing (PBKDF2) ─────────────────────────────────

  /** v1.17.x — derive a salted PBKDF2-HMAC-SHA256 hash for a client
   *  secret.  Output format: `pbkdf2:<iterations>:<base64url(salt)>:<base64url(hash)>`.
   *  Use `verifySecret(...)` to check on the AS side; storage shouldn't
   *  hold plaintext secrets.  100k iterations balances cost vs. response
   *  time on token-endpoint hot paths. */
  def hashSecret(secret: String, iterations: Int = 100_000): String =
    val rng  = new java.security.SecureRandom
    val salt = new Array[Byte](16)
    rng.nextBytes(salt)
    val hash = pbkdf2(secret, salt, iterations)
    s"pbkdf2:$iterations:${b64u(salt)}:${b64u(hash)}"

  /** Constant-time verification of a plaintext secret against a stored
   *  PBKDF2 hash.  Recognises the `pbkdf2:...` format produced by
   *  `hashSecret`; legacy plaintext entries (no prefix) are compared
   *  directly so existing stores keep working until rotated. */
  def verifySecret(presented: String, stored: String): Boolean =
    if stored.startsWith("pbkdf2:") then
      stored.split(':') match
        case Array(_, iterStr, saltB64, hashB64) =>
          try
            val iter = iterStr.toInt
            val salt = Base64.getUrlDecoder.decode(saltB64)
            val expected = Base64.getUrlDecoder.decode(hashB64)
            val actual   = pbkdf2(presented, salt, iter)
            constantTimeEqualsBytes(actual, expected)
          catch case _: Throwable => false
        case _ => false
    else
      constantTimeEquals(presented, stored)

  private def pbkdf2(secret: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new javax.crypto.spec.PBEKeySpec(
      secret.toCharArray, salt, iterations, 256)
    val skf  = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    skf.generateSecret(spec).getEncoded

  private def constantTimeEqualsBytes(a: Array[Byte], b: Array[Byte]): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i = 0
      while i < a.length do
        diff |= (a(i) ^ b(i))
        i += 1
      diff == 0

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

  private[oauth] def rsaSign(key: RSAPrivateKey, message: String): Array[Byte] =
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(key)
    sig.update(message.getBytes(StandardCharsets.UTF_8))
    sig.sign()

  private[oauth] def rsaVerify(key: RSAPublicKey, message: String, signature: Array[Byte]): Boolean =
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initVerify(key)
    sig.update(message.getBytes(StandardCharsets.UTF_8))
    sig.verify(signature)

  /** RFC 7517 JWK serialisation of an RSA public key.  `n` and `e` are
   *  unpadded base64url; `kty/use/alg` are constants per RFC 7518. */
  private[oauth] def rsaPublicJwk(key: RSAPublicKey, kid: Option[String]): ujson.Value =
    val obj = ujson.Obj(
      "kty" -> "RSA",
      "use" -> "sig",
      "alg" -> "RS256",
      "n"   -> b64u(stripLeadingZero(key.getModulus.toByteArray)),
      "e"   -> b64u(stripLeadingZero(key.getPublicExponent.toByteArray))
    )
    kid.foreach(k => obj("kid") = k)
    obj

  /** Java's BigInteger.toByteArray emits a leading zero byte when the
   *  high bit is set (so it stays non-negative); JWK n/e MUST NOT carry
   *  that padding (RFC 7518 §6.3.1). */
  private[oauth] def stripLeadingZero(bs: Array[Byte]): Array[Byte] =
    if bs.length > 1 && bs(0) == 0 then bs.drop(1) else bs

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
