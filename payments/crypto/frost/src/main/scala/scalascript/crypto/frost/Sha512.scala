package scalascript.crypto.frost

/** Portable SHA-512 (FIPS 180-4), pure Scala over 64-bit `Long` — works identically on the JVM and Scala.js
 *  (and any backend with `Long`), so the FROST reference backend needs no platform crypto API
 *  (`java.security` is JVM-only). Verified against `java.security.MessageDigest` on the JVM. */
object Sha512:

  private val K: Array[Long] = Array(
    0x428a2f98d728ae22L, 0x7137449123ef65cdL, 0xb5c0fbcfec4d3b2fL, 0xe9b5dba58189dbbcL,
    0x3956c25bf348b538L, 0x59f111f1b605d019L, 0x923f82a4af194f9bL, 0xab1c5ed5da6d8118L,
    0xd807aa98a3030242L, 0x12835b0145706fbeL, 0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L,
    0x72be5d74f27b896fL, 0x80deb1fe3b1696b1L, 0x9bdc06a725c71235L, 0xc19bf174cf692694L,
    0xe49b69c19ef14ad2L, 0xefbe4786384f25e3L, 0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L,
    0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L, 0x5cb0a9dcbd41fbd4L, 0x76f988da831153b5L,
    0x983e5152ee66dfabL, 0xa831c66d2db43210L, 0xb00327c898fb213fL, 0xbf597fc7beef0ee4L,
    0xc6e00bf33da88fc2L, 0xd5a79147930aa725L, 0x06ca6351e003826fL, 0x142929670a0e6e70L,
    0x27b70a8546d22ffcL, 0x2e1b21385c26c926L, 0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL,
    0x650a73548baf63deL, 0x766a0abb3c77b2a8L, 0x81c2c92e47edaee6L, 0x92722c851482353bL,
    0xa2bfe8a14cf10364L, 0xa81a664bbc423001L, 0xc24b8b70d0f89791L, 0xc76c51a30654be30L,
    0xd192e819d6ef5218L, 0xd69906245565a910L, 0xf40e35855771202aL, 0x106aa07032bbd1b8L,
    0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L, 0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L,
    0x391c0cb3c5c95a63L, 0x4ed8aa4ae3418acbL, 0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L,
    0x748f82ee5defb2fcL, 0x78a5636f43172f60L, 0x84c87814a1f0ab72L, 0x8cc702081a6439ecL,
    0x90befffa23631e28L, 0xa4506cebde82bde9L, 0xbef9a3f7b2c67915L, 0xc67178f2e372532bL,
    0xca273eceea26619cL, 0xd186b8c721c0c207L, 0xeada7dd6cde0eb1eL, 0xf57d4f7fee6ed178L,
    0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L, 0x113f9804bef90daeL, 0x1b710b35131c471bL,
    0x28db77f523047d84L, 0x32caab7b40c72493L, 0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL,
    0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL, 0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L)

  private val H0: Array[Long] = Array(
    0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
    0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L)

  private inline def rotr(x: Long, n: Int): Long = (x >>> n) | (x << (64 - n))

  /** SHA-512 digest (64 bytes). */
  def digest(msg: Array[Byte]): Array[Byte] =
    // ── padding to a multiple of 128 bytes, last 16 = big-endian bit length ──
    val padLen = ((112 - (msg.length + 1) % 128) + 128) % 128
    val total  = msg.length + 1 + padLen + 16
    val buf    = new Array[Byte](total)
    System.arraycopy(msg, 0, buf, 0, msg.length)
    buf(msg.length) = 0x80.toByte
    val bitLen = msg.length.toLong * 8   // messages < 2^61 bytes → high 64 length bits are 0
    var i = 0
    while i < 8 do
      buf(total - 1 - i) = ((bitLen >>> (8 * i)) & 0xffL).toByte
      i += 1

    val h = H0.clone()
    val w = new Array[Long](80)
    var off = 0
    while off < total do
      var t = 0
      while t < 16 do
        var x = 0L; var j = 0
        while j < 8 do { x = (x << 8) | (buf(off + t * 8 + j) & 0xffL); j += 1 }
        w(t) = x; t += 1
      while t < 80 do
        val s0 = rotr(w(t-15), 1) ^ rotr(w(t-15), 8) ^ (w(t-15) >>> 7)
        val s1 = rotr(w(t-2), 19) ^ rotr(w(t-2), 61) ^ (w(t-2) >>> 6)
        w(t) = w(t-16) + s0 + w(t-7) + s1
        t += 1
      var a = h(0); var b = h(1); var c = h(2); var d = h(3)
      var e = h(4); var f = h(5); var g = h(6); var hh = h(7)
      t = 0
      while t < 80 do
        val S1 = rotr(e, 14) ^ rotr(e, 18) ^ rotr(e, 41)
        val ch = (e & f) ^ (~e & g)
        val t1 = hh + S1 + ch + K(t) + w(t)
        val S0 = rotr(a, 28) ^ rotr(a, 34) ^ rotr(a, 39)
        val maj = (a & b) ^ (a & c) ^ (b & c)
        val t2 = S0 + maj
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        t += 1
      h(0)+=a; h(1)+=b; h(2)+=c; h(3)+=d; h(4)+=e; h(5)+=f; h(6)+=g; h(7)+=hh
      off += 128

    val out = new Array[Byte](64)
    var k = 0
    while k < 8 do
      var j = 0
      while j < 8 do { out(k*8 + j) = ((h(k) >>> (56 - 8*j)) & 0xffL).toByte; j += 1 }
      k += 1
    out
