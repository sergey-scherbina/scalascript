package scalascript.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** Portable PASETO **v4.public** (signed, not encrypted) over the from-scratch [[Ed25519]] — works
 *  identically on the JVM and Scala.js, no platform crypto API.
 *
 *  A v4.public token is `v4.public.<b64u(message ‖ sig)>` with an optional `.<b64u(footer)>`, where
 *  `sig` is the 64-byte Ed25519 signature over the **Pre-Authentication Encoding** (PAE, PASETO spec
 *  §Common) of `[ "v4.public.", message, footer, implicit ]`, and `b64u` is base64url without padding.
 *  PAE binds the header + footer + implicit-assertion into the signature, so a token cannot be
 *  stripped of its footer or replayed under a different purpose.
 *
 *  Scope: v4.public sign+verify. v4.local (XChaCha20 + BLAKE2b keyed) and v2/v3 are follow-ups. */
object PasetoV4:

  private val PublicHeader = "v4.public."
  private val empty        = Array.emptyByteArray

  private def b64u(b: Array[Byte]): String   = Base64.getUrlEncoder.withoutPadding.encodeToString(b)
  private def unb64u(s: String): Array[Byte] = Base64.getUrlDecoder.decode(s)

  /** PASETO Pre-Authentication Encoding: `LE64(n) ‖ (LE64(len(p)) ‖ p)*`, where LE64 is a little-endian
   *  unsigned 64-bit with the top bit cleared (§Common "PAE"). */
  def pae(pieces: Array[Array[Byte]]): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream()
    writeLE64(out, pieces.length.toLong)
    var i = 0
    while i < pieces.length do
      writeLE64(out, pieces(i).length.toLong)
      out.write(pieces(i))
      i += 1
    out.toByteArray

  private def writeLE64(out: java.io.ByteArrayOutputStream, n: Long): Unit =
    var i = 0
    while i < 8 do
      val b = (n >>> (8 * i)) & 0xffL
      out.write((if i == 7 then b & 0x7fL else b).toInt)   // clear the top bit of the last byte
      i += 1

  /** Sign a v4.public token. `seed` is the 32-byte Ed25519 private key. */
  def signPublic(
    seed: Array[Byte],
    message: Array[Byte],
    footer: Array[Byte]            = empty,
    implicitAssertion: Array[Byte] = empty,
  ): String =
    require(seed.length == 32, s"Ed25519 seed must be 32 bytes, got ${seed.length}")
    val m2  = pae(Array(PublicHeader.getBytes(UTF_8), message, footer, implicitAssertion))
    val sig = Ed25519.sign(seed, m2)                         // 64 bytes
    val body = b64u(concat(message, sig))
    if footer.length == 0 then s"$PublicHeader$body"
    else s"$PublicHeader$body.${b64u(footer)}"

  /** Verify a v4.public token against the 32-byte Ed25519 public key. Returns the message payload iff
   *  the signature (over header + message + the token's footer + `implicitAssertion`) is valid; `None`
   *  for a bad signature, wrong header, or malformed token. */
  def verifyPublic(
    token: String,
    pub: Array[Byte],
    implicitAssertion: Array[Byte] = empty,
  ): Option[Array[Byte]] =
    try
      val parts = token.split("\\.", -1)
      if parts.length < 3 || parts.length > 4 || parts(0) != "v4" || parts(1) != "public" then None
      else
        val raw = unb64u(parts(2))
        if raw.length < 64 then None
        else
          val message = java.util.Arrays.copyOfRange(raw, 0, raw.length - 64)
          val sig     = java.util.Arrays.copyOfRange(raw, raw.length - 64, raw.length)
          val footer  = if parts.length == 4 then unb64u(parts(3)) else empty
          val m2 = pae(Array(PublicHeader.getBytes(UTF_8), message, footer, implicitAssertion))
          if Ed25519.verify(pub, m2, sig) then Some(message) else None
    catch case _: IllegalArgumentException => None

  private def concat(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](a.length + b.length)
    System.arraycopy(a, 0, r, 0, a.length)
    System.arraycopy(b, 0, r, a.length, b.length)
    r
