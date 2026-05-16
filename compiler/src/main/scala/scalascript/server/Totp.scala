package scalascript.server

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** RFC 6238 time-based one-time passwords.  Compatible with Google
 *  Authenticator, Authy, 1Password, etc.: HMAC-SHA1, 30-second step,
 *  6-digit decimal code, base32-encoded shared secret.
 *
 *  Typical flow:
 *
 *    val secret = totpSecret()                                  // store per user
 *    val uri    = totpUri(secret, "alice@example.com", "MyApp") // show as QR
 *    // …user enrolls in authenticator app, types in current 6-digit code…
 *    if !totpValid(secret, code) then reject else accept */
object Totp:
  private val rng = SecureRandom()

  /** Generate a fresh 20-byte secret, encoded base32 (RFC 4648). */
  def secret(): String =
    val bytes = new Array[Byte](20)
    rng.nextBytes(bytes)
    base32Encode(bytes)

  /** Build the standard `otpauth://totp/...` URI suitable for a QR code.
   *  Authenticator apps recognise the `issuer:account` label format. */
  def uri(secret: String, account: String, issuer: String = ""): String =
    val labelIssuer = if issuer.isEmpty then "" else issuer + ":"
    val label   = java.net.URLEncoder.encode(labelIssuer + account, "UTF-8").replace("+", "%20")
    val params  = scala.collection.mutable.LinkedHashMap[String, String](
      "secret"    -> secret,
      "algorithm" -> "SHA1",
      "digits"    -> "6",
      "period"    -> "30",
    )
    if issuer.nonEmpty then params("issuer") = issuer
    val qs = params.iterator.map((k, v) =>
      s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
    ).mkString("&")
    s"otpauth://totp/$label?$qs"

  /** Current 6-digit TOTP code for the given secret, anchored to
   *  `nowSeconds()` (Unix seconds, UTC) modulo the 30-second step. */
  def code(secret: String, nowSeconds: Long = java.lang.System.currentTimeMillis() / 1000L): String =
    codeAt(secret, nowSeconds / 30L)

  /** Verify a user-supplied code against the secret.  `skew` allows
   *  matching codes from `skew` steps before or after the current one
   *  to absorb small clock drift between server and authenticator.
   *  Constant-time comparison; rejects malformed (non-digit) input. */
  def valid(secret: String, code: String, skew: Int = 1): Boolean =
    if code == null || code.length != 6 || !code.forall(_.isDigit) then false
    else
      val now = java.lang.System.currentTimeMillis() / 1000L / 30L
      var i = -skew
      var ok = false
      while i <= skew do
        if constEq(codeAt(secret, now + i), code) then ok = true
        i += 1
      ok

  private def codeAt(secret: String, counter: Long): String =
    val key = base32Decode(secret)
    val buf = new Array[Byte](8)
    var c = counter
    var i = 7
    while i >= 0 do { buf(i) = (c & 0xff).toByte; c >>>= 8; i -= 1 }
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    val h = mac.doFinal(buf)
    val off = h(h.length - 1) & 0x0f
    val bin = ((h(off)     & 0x7f) << 24) |
              ((h(off + 1) & 0xff) << 16) |
              ((h(off + 2) & 0xff) <<  8) |
               (h(off + 3) & 0xff)
    val n = bin % 1_000_000
    f"$n%06d"

  private def constEq(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i = 0
      while i < a.length do { diff |= a.charAt(i) ^ b.charAt(i); i += 1 }
      diff == 0

  // ── Base32 RFC 4648 ────────────────────────────────────────────────
  private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
  private val DecodeTable: Array[Int] =
    val t = Array.fill(128)(-1)
    Alphabet.zipWithIndex.foreach((c, i) => t(c.toInt) = i)
    t

  private def base32Encode(bytes: Array[Byte]): String =
    val sb = StringBuilder()
    var buf = 0L
    var bits = 0
    for b <- bytes do
      buf = (buf << 8) | (b & 0xffL)
      bits += 8
      while bits >= 5 do
        bits -= 5
        sb.append(Alphabet.charAt(((buf >> bits) & 0x1f).toInt))
    if bits > 0 then sb.append(Alphabet.charAt(((buf << (5 - bits)) & 0x1f).toInt))
    sb.toString

  private def base32Decode(s: String): Array[Byte] =
    val clean = s.toUpperCase.filter(c => c != '=' && c != ' ')
    val out   = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var buf   = 0L
    var bits  = 0
    for c <- clean do
      val v = if c.toInt < 128 then DecodeTable(c.toInt) else -1
      if v >= 0 then
        buf = (buf << 5) | v.toLong
        bits += 5
        if bits >= 8 then
          bits -= 8
          out += ((buf >> bits) & 0xff).toByte
    out.toArray
