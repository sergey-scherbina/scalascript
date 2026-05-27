package scalascript.oauth

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.interfaces.{ECPublicKey, RSAPublicKey}
import java.security.spec.{ECGenParameterSpec, ECPoint, ECPublicKeySpec, RSAPublicKeySpec}
import java.security.{KeyFactory, KeyPairGenerator, MessageDigest, Signature}
import java.util.Base64

/** DPoP (RFC 9449) — Demonstrating Proof-of-Possession at the Application Layer.
 *
 *  Clients generate an asymmetric key pair and attach a signed DPoP proof JWT
 *  to each `/token` request.  The AS validates the proof, then binds the issued
 *  access token to the key by injecting `cnf.jkt` (RFC 7638 JWK thumbprint of
 *  the DPoP key) into the token payload.  Resource servers validate the proof
 *  on every API call and confirm the `cnf.jkt` matches.
 *
 *  Supported proof algorithms: RS256 / RS384 / RS512 (RSA),
 *  ES256 / ES384 / ES512 (EC P-256 / P-384 / P-521).
 *  Symmetric algorithms (HS*) are forbidden — the DPoP key must be asymmetric. */
object DPoP:

  /** Supported asymmetric alg values for DPoP proofs (RFC 9449 §4.1). */
  val SupportedAlgorithms: Set[String] =
    Set("RS256", "RS384", "RS512", "ES256", "ES384", "ES512")

  /** Default maximum age of a DPoP proof's `iat` claim (RFC 9449 §11.1).
   *  5 minutes is the commonly-recommended window. */
  val DefaultProofMaxAgeSeconds: Long = 300L

  enum ProofResult:
    case Valid(jwkThumbprint: String)
    case Invalid(reason: String)

  // ─── Replay prevention ────────────────────────────────────────────────────

  /** Store that records DPoP `jti` values to prevent proof replay. */
  trait JtiStore:
    /** Record `jti` if unseen; return true if the jti is fresh (first use).
     *  Implementations MUST be thread-safe. */
    def checkAndRecord(jti: String, lifetimeSeconds: Long): Boolean

  /** No-op store — replay prevention disabled (tests / trusted contexts). */
  object NoOpJtiStore extends JtiStore:
    def checkAndRecord(jti: String, lifetimeSeconds: Long): Boolean = true

  /** Thread-safe in-memory JTI store.  Entries expire after `lifetimeSeconds`;
   *  a periodic purge runs on each call to keep memory bounded. */
  class InMemoryJtiStore extends JtiStore:
    private val store = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Long]()
    def checkAndRecord(jti: String, lifetimeSeconds: Long): Boolean =
      purgeExpired()
      val expiresAt: java.lang.Long = java.time.Instant.now.getEpochSecond + lifetimeSeconds
      // putIfAbsent returns null if key was absent (first use) → fresh
      store.putIfAbsent(jti, expiresAt) == null
    private def purgeExpired(): Unit =
      val now = java.time.Instant.now.getEpochSecond
      store.entrySet().removeIf(_.getValue < now)

  // ─── Core proof validation (RFC 9449 §4.3) ───────────────────────────────

  /** Validate a DPoP proof JWT.
   *
   *  @param proofJwt         Raw DPoP proof JWT string from the `DPoP` header.
   *  @param htm              Expected HTTP method (e.g. "POST").  Case-insensitive match.
   *  @param htu              Expected HTTP target URI.  Fragment + query stripped before compare.
   *  @param clockSkewSeconds Tolerance for clock drift between client and server.
   *  @param maxAgeSeconds    Maximum age of the proof's `iat` claim.
   *  @param expectedNonce    When set, the proof's `nonce` claim must match exactly.
   *  @param expectedAth      When set (resource-server path), the proof's `ath` claim must match.
   *  @param jtiStore         Replay-prevention store; defaults to no-op.
   *  @return                 `ProofResult.Valid(jwkThumbprint)` or `ProofResult.Invalid(reason)`.
   */
  def verifyProof(
    proofJwt:         String,
    htm:              String,
    htu:              String,
    clockSkewSeconds: Long        = 60L,
    maxAgeSeconds:    Long        = DefaultProofMaxAgeSeconds,
    expectedNonce:    Option[String] = None,
    expectedAth:      Option[String] = None,
    jtiStore:         JtiStore    = NoOpJtiStore
  ): ProofResult =
    try
      val parts = proofJwt.split('.')
      if parts.length != 3 then
        return ProofResult.Invalid("DPoP proof JWT must have exactly 3 parts")

      // 1. Decode header
      val header = ujson.read(decodeB64u(parts(0)))

      // typ must be "dpop+jwt" (case-insensitive per RFC 7515 §4.1.9)
      val typ = header.obj.get("typ").flatMap(_.strOpt).getOrElse("")
      if !typ.equalsIgnoreCase("dpop+jwt") then
        return ProofResult.Invalid(s"DPoP proof typ must be dpop+jwt, got: $typ")

      // alg must be an allowed asymmetric algorithm
      val alg = header.obj.get("alg").flatMap(_.strOpt).getOrElse("")
      if !SupportedAlgorithms.contains(alg) then
        return ProofResult.Invalid(
          s"DPoP proof alg must be one of ${SupportedAlgorithms.mkString(", ")}, got: $alg")

      // jwk must be present and be the public key
      val jwk = header.obj.get("jwk") match
        case None    => return ProofResult.Invalid("DPoP proof header missing jwk claim")
        case Some(j) => j

      // 2. Verify signature using the embedded public key
      val signingInput = parts(0) + "." + parts(1)
      val sigBytes     = Base64.getUrlDecoder.decode(parts(2))
      if !verifyJwtSignature(alg, jwk, signingInput, sigBytes) then
        return ProofResult.Invalid("DPoP proof signature verification failed")

      // 3. Decode payload
      val payload = ujson.read(decodeB64u(parts(1)))

      // htm check (case-insensitive per RFC 9110 §9.1 method names)
      val proofHtm = payload.obj.get("htm").flatMap(_.strOpt).getOrElse("")
      if !proofHtm.equalsIgnoreCase(htm) then
        return ProofResult.Invalid(s"DPoP htm mismatch: expected $htm, got $proofHtm")

      // htu check (strip query+fragment before comparing)
      val proofHtu = payload.obj.get("htu").flatMap(_.strOpt).getOrElse("")
      if !htuMatches(proofHtu, htu) then
        return ProofResult.Invalid(s"DPoP htu mismatch: expected $htu, got $proofHtu")

      // iat check — must be recent
      val iatOpt = payload.obj.get("iat").flatMap(_.numOpt).map(_.toLong)
      if iatOpt.isEmpty then
        return ProofResult.Invalid("DPoP proof missing iat claim")
      val iat = iatOpt.get
      val now = java.time.Instant.now.getEpochSecond
      if now > iat + maxAgeSeconds + clockSkewSeconds then
        return ProofResult.Invalid("DPoP proof has expired (iat too old)")
      if now < iat - clockSkewSeconds then
        return ProofResult.Invalid("DPoP proof iat is in the future")

      // jti check — required, unique per use
      val jti = payload.obj.get("jti").flatMap(_.strOpt) match
        case None    => return ProofResult.Invalid("DPoP proof missing jti claim")
        case Some(j) => j
      if !jtiStore.checkAndRecord(jti, maxAgeSeconds + clockSkewSeconds) then
        return ProofResult.Invalid("DPoP proof jti replayed — proof already used")

      // nonce check — required when server issued one
      expectedNonce match
        case Some(wantNonce) =>
          val gotNonce = payload.obj.get("nonce").flatMap(_.strOpt).getOrElse("")
          if gotNonce != wantNonce then
            return ProofResult.Invalid("DPoP proof nonce missing or invalid")
        case None => ()

      // ath check — required at resource server (hash of the access token)
      expectedAth match
        case Some(wantAth) =>
          val gotAth = payload.obj.get("ath").flatMap(_.strOpt).getOrElse("")
          if gotAth != wantAth then
            return ProofResult.Invalid("DPoP proof ath mismatch (access token hash)")
        case None => ()

      // All checks passed
      ProofResult.Valid(jwkThumbprint(jwk))

    catch case e: Throwable =>
      ProofResult.Invalid(s"DPoP proof validation error: ${e.getMessage}")

  // ─── JWK helpers (RFC 7638, RFC 7518) ────────────────────────────────────

  /** RFC 7638 JWK Thumbprint: SHA-256 of the canonical required-member JSON,
   *  base64url-encoded.  Used as the `cnf.jkt` claim in access tokens. */
  def jwkThumbprint(jwk: ujson.Value): String =
    val kty = jwk.obj.get("kty").flatMap(_.strOpt).getOrElse("")
    val canonical = kty match
      case "RSA" =>
        // Required members in lexicographic order: e, kty, n (RFC 7638 §3.2)
        val e = jwk.obj.get("e").flatMap(_.strOpt).getOrElse("")
        val n = jwk.obj.get("n").flatMap(_.strOpt).getOrElse("")
        ujson.Obj("e" -> e, "kty" -> "RSA", "n" -> n).render()
      case "EC" =>
        // Required members: crv, kty, x, y
        val crv = jwk.obj.get("crv").flatMap(_.strOpt).getOrElse("")
        val x   = jwk.obj.get("x").flatMap(_.strOpt).getOrElse("")
        val y   = jwk.obj.get("y").flatMap(_.strOpt).getOrElse("")
        ujson.Obj("crv" -> crv, "kty" -> "EC", "x" -> x, "y" -> y).render()
      case _ =>
        jwk.render()
    b64u(sha256(canonical.getBytes(StandardCharsets.UTF_8)))

  /** Compute the `ath` (access token hash) claim for resource-server DPoP proofs:
   *  `base64url(SHA-256(accessToken_ASCII))`.  RFC 9449 §4.2. */
  def accessTokenHash(accessToken: String): String =
    b64u(sha256(accessToken.getBytes(StandardCharsets.US_ASCII)))

  // ─── Internal helpers ─────────────────────────────────────────────────────

  private def htuMatches(proofHtu: String, expectedHtu: String): Boolean =
    def strip(uri: String): String =
      val q = uri.indexOf('?')
      val f = uri.indexOf('#')
      val end = List(q, f).filter(_ >= 0).minOption.getOrElse(uri.length)
      uri.substring(0, end)
    strip(proofHtu) == strip(expectedHtu)

  private def verifyJwtSignature(
    alg: String, jwk: ujson.Value,
    signingInput: String, sigBytes: Array[Byte]
  ): Boolean =
    try alg match
      case a if a.startsWith("RS") =>
        val key    = rsaPublicKeyFromJwk(jwk)
        val jdkAlg = a match
          case "RS256" => "SHA256withRSA"
          case "RS384" => "SHA384withRSA"
          case _       => "SHA512withRSA"
        val sig = Signature.getInstance(jdkAlg)
        sig.initVerify(key)
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
        sig.verify(sigBytes)
      case a if a.startsWith("ES") =>
        val key    = ecPublicKeyFromJwk(jwk)
        val jdkAlg = a match
          case "ES256" => "SHA256withECDSA"
          case "ES384" => "SHA384withECDSA"
          case _       => "SHA512withECDSA"
        val sig = Signature.getInstance(jdkAlg)
        sig.initVerify(key)
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
        sig.verify(jwtEcSigToDer(sigBytes))
      case _ => false
    catch case _: Throwable => false

  private def rsaPublicKeyFromJwk(jwk: ujson.Value): RSAPublicKey =
    val n = new BigInteger(1, Base64.getUrlDecoder.decode(jwk("n").str))
    val e = new BigInteger(1, Base64.getUrlDecoder.decode(jwk("e").str))
    KeyFactory.getInstance("RSA")
      .generatePublic(RSAPublicKeySpec(n, e))
      .asInstanceOf[RSAPublicKey]

  private def ecPublicKeyFromJwk(jwk: ujson.Value): ECPublicKey =
    val crv  = jwk.obj.get("crv").flatMap(_.strOpt).getOrElse("P-256")
    val x    = new BigInteger(1, Base64.getUrlDecoder.decode(jwk("x").str))
    val y    = new BigInteger(1, Base64.getUrlDecoder.decode(jwk("y").str))
    val name = crv match
      case "P-256" => "secp256r1"
      case "P-384" => "secp384r1"
      case "P-521" => "secp521r1"
      case other   => other
    // Borrow curve params from a freshly-generated key to avoid hard-coding
    // the explicit ECParameterSpec constants.
    val kpg    = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec(name))
    val params = kpg.generateKeyPair().getPublic.asInstanceOf[ECPublicKey].getParams
    KeyFactory.getInstance("EC")
      .generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
      .asInstanceOf[ECPublicKey]

  /** Convert JWT/JWA fixed-size R||S EC signature to the ASN.1 DER SEQUENCE
   *  that Java's `Signature.verify()` expects (RFC 7518 §3.4 ↔ X9.62). */
  private def jwtEcSigToDer(raw: Array[Byte]): Array[Byte] =
    val half = raw.length / 2
    def pad(bs: Array[Byte]): Array[Byte] =
      // Prepend 0x00 if high bit set (DER INTEGER must be non-negative)
      if bs.nonEmpty && (bs(0) & 0x80) != 0 then 0.toByte +: bs else bs
    val r   = pad(raw.take(half))
    val s   = pad(raw.drop(half))
    val seqBody = Array(0x02.toByte, r.length.toByte) ++ r ++
                  Array(0x02.toByte, s.length.toByte) ++ s
    Array(0x30.toByte, seqBody.length.toByte) ++ seqBody

  private def sha256(input: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(input)

  private def b64u(bs: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bs)

  private def decodeB64u(s: String): String =
    new String(Base64.getUrlDecoder.decode(s), StandardCharsets.UTF_8)
