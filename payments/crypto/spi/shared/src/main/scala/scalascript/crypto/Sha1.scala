package scalascript.crypto

/** Portable SHA-1 (FIPS 180-4), pure Scala over 32-bit `Int` — works identically on the JVM and Scala.js.
 *  Needed for HOTP/TOTP (RFC 4226 / 6238 default to HMAC-SHA1). SHA-1 is broken for collision resistance and
 *  MUST NOT be used for new signatures — it is here only for these legacy-but-ubiquitous HMAC-based standards.
 *  Mirrors [[Sha256]]; verified against the published vectors. */
object Sha1:

  private inline def rotl(x: Int, n: Int): Int = (x << n) | (x >>> (32 - n))

  /** SHA-1 digest (20 bytes). */
  def digest(msg: Array[Byte]): Array[Byte] =
    val padLen = ((56 - (msg.length + 1) % 64) + 64) % 64
    val total  = msg.length + 1 + padLen + 8
    val buf    = new Array[Byte](total)
    System.arraycopy(msg, 0, buf, 0, msg.length)
    buf(msg.length) = 0x80.toByte
    val bitLen = msg.length.toLong * 8
    var i = 0
    while i < 8 do
      buf(total - 1 - i) = ((bitLen >>> (8 * i)) & 0xffL).toByte
      i += 1

    var h0 = 0x67452301; var h1 = 0xefcdab89; var h2 = 0x98badcfe
    var h3 = 0x10325476; var h4 = 0xc3d2e1f0
    val w = new Array[Int](80)
    var off = 0
    while off < total do
      var t = 0
      while t < 16 do
        w(t) = ((buf(off + t*4) & 0xff) << 24) | ((buf(off + t*4 + 1) & 0xff) << 16) |
               ((buf(off + t*4 + 2) & 0xff) << 8) | (buf(off + t*4 + 3) & 0xff)
        t += 1
      while t < 80 do
        w(t) = rotl(w(t-3) ^ w(t-8) ^ w(t-14) ^ w(t-16), 1)
        t += 1
      var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
      t = 0
      while t < 80 do
        val (f, k) =
          if t < 20 then ((b & c) | (~b & d), 0x5a827999)
          else if t < 40 then (b ^ c ^ d, 0x6ed9eba1)
          else if t < 60 then ((b & c) | (b & d) | (c & d), 0x8f1bbcdc.toInt)
          else (b ^ c ^ d, 0xca62c1d6.toInt)
        val temp = rotl(a, 5) + f + e + k + w(t)
        e = d; d = c; c = rotl(b, 30); b = a; a = temp
        t += 1
      h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
      off += 64

    val hs = Array(h0, h1, h2, h3, h4)
    val out = new Array[Byte](20)
    var j = 0
    while j < 5 do
      out(j*4)     = ((hs(j) >>> 24) & 0xff).toByte
      out(j*4 + 1) = ((hs(j) >>> 16) & 0xff).toByte
      out(j*4 + 2) = ((hs(j) >>> 8) & 0xff).toByte
      out(j*4 + 3) = (hs(j) & 0xff).toByte
      j += 1
    out
