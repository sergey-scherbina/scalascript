package scalascript.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** Portable JSON Web Signature (RFC 7515, compact serialization) over the from-scratch crypto
 *  primitives in this package — works identically on the JVM and Scala.js, no platform crypto API.
 *
 *  A compact JWS is `b64u(header) "." b64u(payload) "." b64u(signature)`, where the signature covers
 *  the ASCII *signing input* `b64u(header) "." b64u(payload)` and `b64u` is base64url **without**
 *  padding (RFC 7515 §2). Header and payload are opaque bytes here — the caller supplies the JSON
 *  (see [[Jwt]] for the standard header + claims convenience).
 *
 *  Algorithms: **HS256** (HMAC-SHA256, via [[HmacSha256]]) and **EdDSA** (Ed25519, via [[Ed25519]]).
 *  Both are deterministic, so the RFC vectors reproduce byte-exact. ES256/ES256K (ECDSA with a fixed
 *  R‖S encoding), plus PASETO and COSE, are follow-ups. */
object Jws:

  private def b64u(b: Array[Byte]): String   = Base64.getUrlEncoder.withoutPadding.encodeToString(b)
  private def unb64u(s: String): Array[Byte] = Base64.getUrlDecoder.decode(s)

  private def signingInput(header: Array[Byte], payload: Array[Byte]): String =
    s"${b64u(header)}.${b64u(payload)}"

  /** Constant-time byte-array equality (avoids a timing oracle on the MAC compare). */
  private def constEq(a: Array[Byte], b: Array[Byte]): Boolean =
    if a.length != b.length then false
    else
      var r = 0; var i = 0
      while i < a.length do { r |= (a(i) ^ b(i)); i += 1 }
      r == 0

  // ── HS256 (HMAC-SHA256) ─────────────────────────────────────────────────────

  /** Sign `payload` under HS256 with the given HMAC `secret`; `header` is the raw protected-header
   *  JSON bytes (must declare `"alg":"HS256"`). Returns the compact JWS. */
  def signHs256(secret: Array[Byte], header: Array[Byte], payload: Array[Byte]): String =
    val si  = signingInput(header, payload)
    val sig = HmacSha256.mac(secret, si.getBytes(UTF_8))
    s"$si.${b64u(sig)}"

  /** Verify a compact HS256 JWS. Returns the decoded payload bytes iff the MAC matches; `None` for a
   *  bad MAC or a malformed token (wrong part count / invalid base64url). */
  def verifyHs256(token: String, secret: Array[Byte]): Option[Array[Byte]] =
    try
      token.split("\\.", -1) match
        case Array(h, p, s) =>
          val expected = HmacSha256.mac(secret, s"$h.$p".getBytes(UTF_8))
          if constEq(unb64u(s), expected) then Some(unb64u(p)) else None
        case _ => None
    catch case _: IllegalArgumentException => None

  // ── EdDSA (Ed25519) ─────────────────────────────────────────────────────────

  /** Sign `payload` under EdDSA with the 32-byte Ed25519 `seed` (private key); `header` is the raw
   *  protected-header JSON bytes (must declare `"alg":"EdDSA"`). Returns the compact JWS. */
  def signEdDSA(seed: Array[Byte], header: Array[Byte], payload: Array[Byte]): String =
    require(seed.length == 32, s"Ed25519 seed must be 32 bytes, got ${seed.length}")
    val si  = signingInput(header, payload)
    val sig = Ed25519.sign(seed, si.getBytes(UTF_8))
    s"$si.${b64u(sig)}"

  /** Verify a compact EdDSA JWS against the 32-byte Ed25519 public key. Returns the payload iff valid;
   *  `None` for a bad signature or a malformed token (wrong part count / invalid base64url). */
  def verifyEdDSA(token: String, ed25519Pub: Array[Byte]): Option[Array[Byte]] =
    try
      token.split("\\.", -1) match
        case Array(h, p, s) =>
          if Ed25519.verify(ed25519Pub, s"$h.$p".getBytes(UTF_8), unb64u(s)) then Some(unb64u(p)) else None
        case _ => None
    catch case _: IllegalArgumentException => None

  // ── ES256K (ECDSA secp256k1 + SHA-256, RFC 8812) ────────────────────────────────

  /** Sign `payload` under ES256K with a 32-byte secp256k1 private key; `header` must declare
   *  `"alg":"ES256K"`. The JWS signature is the fixed 64-byte R‖S (not DER), base64url-encoded. */
  def signES256K(privKey: Array[Byte], header: Array[Byte], payload: Array[Byte]): String =
    val si   = signingInput(header, payload)
    val hash = Sha256.digest(si.getBytes(UTF_8))
    val raw  = Secp256k1Ecdsa.derToRaw(Secp256k1Ecdsa.sign(privKey, hash))   // R‖S, 64 bytes
    s"$si.${b64u(raw)}"

  /** Verify a compact ES256K JWS against a secp256k1 public key (compressed or uncompressed). Returns
   *  the payload iff the R‖S signature over SHA-256 of the signing input is valid. */
  def verifyES256K(token: String, pubKey: Array[Byte]): Option[Array[Byte]] =
    try
      token.split("\\.", -1) match
        case Array(h, p, s) =>
          val raw = unb64u(s)
          if raw.length != 64 then None
          else
            val hash = Sha256.digest(s"$h.$p".getBytes(UTF_8))
            if Secp256k1Ecdsa.verify(pubKey, hash, Secp256k1Ecdsa.rawToDer(raw)) then Some(unb64u(p)) else None
        case _ => None
    catch case _: Exception => None

  // ── ES256 (ECDSA P-256 + SHA-256, RFC 7518) ─────────────────────────────────────

  /** Sign `payload` under ES256 with a 32-byte P-256 private key; `header` must declare `"alg":"ES256"`.
   *  The JWS signature is the fixed 64-byte R‖S, base64url-encoded. */
  def signES256(privKey: Array[Byte], header: Array[Byte], payload: Array[Byte]): String =
    val si   = signingInput(header, payload)
    val hash = Sha256.digest(si.getBytes(UTF_8))
    val raw  = P256Ecdsa.derToRaw(P256Ecdsa.sign(privKey, hash))
    s"$si.${b64u(raw)}"

  /** Verify a compact ES256 JWS against a P-256 public key (compressed or uncompressed SEC1). */
  def verifyES256(token: String, pubKey: Array[Byte]): Option[Array[Byte]] =
    try
      token.split("\\.", -1) match
        case Array(h, p, s) =>
          val raw = unb64u(s)
          if raw.length != 64 then None
          else
            val hash = Sha256.digest(s"$h.$p".getBytes(UTF_8))
            if P256Ecdsa.verify(pubKey, hash, P256Ecdsa.rawToDer(raw)) then Some(unb64u(p)) else None
        case _ => None
    catch case _: Exception => None

/** RFC 7519 JWT convenience over [[Jws]]: emits the standard protected header for an algorithm and
 *  carries a JSON claims string as the payload. The claims are opaque here — validate them (exp/iss/…)
 *  after verification in the caller. */
object Jwt:

  /** HS256-signed JWT: header `{"alg":"HS256","typ":"JWT"}`, `claimsJson` as the payload. */
  def hs256(secret: Array[Byte], claimsJson: String): String =
    Jws.signHs256(secret, """{"alg":"HS256","typ":"JWT"}""".getBytes(UTF_8), claimsJson.getBytes(UTF_8))

  /** EdDSA-signed JWT: header `{"alg":"EdDSA","typ":"JWT"}`, `claimsJson` as the payload. */
  def eddsa(seed32: Array[Byte], claimsJson: String): String =
    Jws.signEdDSA(seed32, """{"alg":"EdDSA","typ":"JWT"}""".getBytes(UTF_8), claimsJson.getBytes(UTF_8))

  /** Verify + return the claims JSON string of an HS256 JWT (does NOT check exp/iss/aud). */
  def verifyHs256(token: String, secret: Array[Byte]): Option[String] =
    Jws.verifyHs256(token, secret).map(p => new String(p, UTF_8))

  /** Verify + return the claims JSON string of an EdDSA JWT (does NOT check exp/iss/aud). */
  def verifyEdDSA(token: String, ed25519Pub: Array[Byte]): Option[String] =
    Jws.verifyEdDSA(token, ed25519Pub).map(p => new String(p, UTF_8))

  /** ES256K-signed JWT: header `{"alg":"ES256K","typ":"JWT"}`, `claimsJson` as the payload. */
  def es256k(secp256k1Priv: Array[Byte], claimsJson: String): String =
    Jws.signES256K(secp256k1Priv, """{"alg":"ES256K","typ":"JWT"}""".getBytes(UTF_8), claimsJson.getBytes(UTF_8))

  /** Verify + return the claims JSON string of an ES256K JWT (does NOT check exp/iss/aud). */
  def verifyES256K(token: String, secp256k1Pub: Array[Byte]): Option[String] =
    Jws.verifyES256K(token, secp256k1Pub).map(p => new String(p, UTF_8))

  /** ES256-signed JWT: header `{"alg":"ES256","typ":"JWT"}`, `claimsJson` as the payload. */
  def es256(p256Priv: Array[Byte], claimsJson: String): String =
    Jws.signES256(p256Priv, """{"alg":"ES256","typ":"JWT"}""".getBytes(UTF_8), claimsJson.getBytes(UTF_8))

  /** Verify + return the claims JSON string of an ES256 JWT (does NOT check exp/iss/aud). */
  def verifyES256(token: String, p256Pub: Array[Byte]): Option[String] =
    Jws.verifyES256(token, p256Pub).map(p => new String(p, UTF_8))
