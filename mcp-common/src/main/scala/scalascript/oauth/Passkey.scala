package scalascript.oauth

import java.nio.charset.StandardCharsets
import java.security.{KeyFactory, PublicKey, Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** v1.17.x — passkey assertion support for the OAuth AS.  Minimal
 *  surface: store credentialId → (subject, publicKey), hand out
 *  challenges, verify signed assertions, exchange them for OAuth
 *  tokens via a new `TokenRequest.PasskeyAssertion` grant.
 *
 *  Out of scope (caller's responsibility — these vary by deployment
 *  and don't fit a single sensible default):
 *    - WebAuthn registration ceremony (`navigator.credentials.create`
 *      attestation) — server-side attestation policy is opinionated;
 *      do it out of band and call `passkeys.register(...)` to enroll
 *      a credential's public key.
 *    - clientDataJSON parsing — accept the pre-decoded `signedData`
 *      from the caller (typically `authenticatorData ||
 *      sha256(clientDataJSON)`).  Origin and rpId checks live with
 *      the caller; we just verify the cryptographic signature.
 *
 *  Supported public-key algorithms:
 *    - RS256 (RSA-SHA256)
 *    - ES256 (ECDSA on P-256 with SHA-256)
 *  The `alg` field on the stored credential controls which path runs. */
object Passkey:

  /** A stored passkey credential.  `publicKey` is the wire-decoded
   *  `java.security.PublicKey`; callers ingest base64url-encoded JWK
   *  / X.509 SPKI via the `decodePublicKey(...)` helpers below. */
  case class PasskeyCredential(
    credentialId: String,
    subject:      String,
    publicKey:    PublicKey,
    alg:          String         // "RS256" | "ES256"
  )

  /** Outcome of `exchangePasskey`.  `Verified` flows to AS token mint;
   *  the four reject codes mirror RFC 6749 token-endpoint error names
   *  so the AS can surface them on the wire unchanged. */
  enum AssertionOutcome:
    case Verified(subject: String, credentialId: String)
    case InvalidGrant(description: String)
    case UnknownCredential(credentialId: String)
    case ChallengeMismatch
    case SignatureInvalid

  // ─── Pluggable stores ──────────────────────────────────────────────

  trait PasskeyStore:
    def register(cred: PasskeyCredential): Unit
    def find(credentialId: String): Option[PasskeyCredential]
    def byUser(subject: String): List[PasskeyCredential]
    def delete(credentialId: String): Unit

  class InMemoryPasskeyStore extends PasskeyStore:
    private val byId = ConcurrentHashMap[String, PasskeyCredential]()
    def register(c: PasskeyCredential): Unit = byId.put(c.credentialId, c)
    def find(id: String): Option[PasskeyCredential] = Option(byId.get(id))
    def byUser(subject: String): List[PasskeyCredential] =
      scala.jdk.CollectionConverters.IteratorHasAsScala(byId.values().iterator())
        .asScala.filter(_.subject == subject).toList
    def delete(id: String): Unit = { byId.remove(id); () }

  /** Single-use challenge store.  Mirrors the AS's authorization-code
   *  pattern: server hands out an opaque random nonce, caller signs it
   *  via passkey, server consumes the nonce on first verification. */
  trait ChallengeStore:
    def issue(): String
    def consume(challenge: String): Boolean

  class InMemoryChallengeStore(ttlSeconds: Long = 300L) extends ChallengeStore:
    private val live = ConcurrentHashMap[String, Long]()
    def issue(): String =
      val c   = OAuth.randomOpaqueToken(32)
      val exp = java.time.Instant.now.getEpochSecond + ttlSeconds
      live.put(c, exp)
      c
    def consume(challenge: String): Boolean =
      Option(live.remove(challenge)) match
        case Some(exp) => exp >= java.time.Instant.now.getEpochSecond
        case None      => false

  // ─── Verification ──────────────────────────────────────────────────

  /** Verify the assertion signature against the stored public key.
   *  `signedData` is whatever the caller chose to sign — typically
   *  `authenticatorData || sha256(clientDataJSON)` for WebAuthn, or
   *  the raw challenge bytes for simpler passkey flows. */
  def verifySignature(
    cred:       PasskeyCredential,
    signedData: Array[Byte],
    signature:  Array[Byte]
  ): Boolean =
    try
      val sig = cred.alg match
        case "RS256" => Signature.getInstance("SHA256withRSA")
        case "ES256" => Signature.getInstance("SHA256withECDSA")
        case _       => return false
      sig.initVerify(cred.publicKey)
      sig.update(signedData)
      sig.verify(signature)
    catch case _: Throwable => false

  // ─── Public-key decoders ───────────────────────────────────────────

  /** Decode an X.509 SubjectPublicKeyInfo (the standard SPKI DER
   *  encoding) from base64.  The browser's WebAuthn registration
   *  payload exposes the credential public key in COSE format;
   *  callers can re-encode to SPKI via a small adapter before
   *  enrollment, or use the JWK variant below for RSA/EC keys. */
  def decodeSpki(alg: String, base64: String): PublicKey =
    val der = Base64.getDecoder.decode(base64)
    val kf  = alg match
      case "RS256" => KeyFactory.getInstance("RSA")
      case "ES256" => KeyFactory.getInstance("EC")
      case other   => throw new IllegalArgumentException(s"unsupported alg: $other")
    kf.generatePublic(new X509EncodedKeySpec(der))

  /** Decode an RSA public key from the JWK `n` / `e` fields
   *  (RFC 7518 §6.3.1).  Convenience for AS deployments that already
   *  ingest JWK-shaped credentials. */
  def decodeRsaJwk(n: String, e: String): PublicKey =
    val mod = new java.math.BigInteger(1,
      Base64.getUrlDecoder.decode(n))
    val exp = new java.math.BigInteger(1,
      Base64.getUrlDecoder.decode(e))
    val spec = new java.security.spec.RSAPublicKeySpec(mod, exp)
    KeyFactory.getInstance("RSA").generatePublic(spec)

  /** Decode an EC P-256 public key from the JWK `x` / `y` fields
   *  (RFC 7518 §6.2.1.2).  Mirrors `decodeRsaJwk`. */
  def decodeEcJwk(x: String, y: String): PublicKey =
    val xb = new java.math.BigInteger(1, Base64.getUrlDecoder.decode(x))
    val yb = new java.math.BigInteger(1, Base64.getUrlDecoder.decode(y))
    val point = new java.security.spec.ECPoint(xb, yb)
    val params = java.security.AlgorithmParameters.getInstance("EC")
    params.init(new java.security.spec.ECGenParameterSpec("secp256r1"))
    val ecSpec = params.getParameterSpec(classOf[java.security.spec.ECParameterSpec])
    val pubSpec = new java.security.spec.ECPublicKeySpec(point, ecSpec)
    KeyFactory.getInstance("EC").generatePublic(pubSpec)

  // ─── Helpers ───────────────────────────────────────────────────────

  /** Decode a base64url string (no padding) to a byte array.  Used to
   *  decode the assertion `signature` + `signedData` fields the
   *  browser delivers via WebAuthn. */
  def b64uDecode(s: String): Array[Byte] =
    Base64.getUrlDecoder.decode(s)

  /** SHA-256 of the input bytes.  Caller wiring helper for the WebAuthn
   *  `authenticatorData || sha256(clientDataJSON)` construction. */
  def sha256(bytes: Array[Byte]): Array[Byte] =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

  /** Convenience: build the signed payload from a clientDataJSON
   *  string + authenticatorData bytes per WebAuthn §6.3.3. */
  def webauthnSignedData(authenticatorData: Array[Byte], clientDataJson: String): Array[Byte] =
    authenticatorData ++ sha256(clientDataJson.getBytes(StandardCharsets.UTF_8))
