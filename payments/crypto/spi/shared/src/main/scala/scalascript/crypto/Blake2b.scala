package scalascript.crypto

/** Portable BLAKE2b (RFC 7693), pure Scala over 64-bit `Long` — works identically on the JVM and
 *  Scala.js, so it serves as the reference / fallback hash for the crypto SPI without any platform
 *  crypto API (mirrors the FROST [[scalascript.crypto.frost.Sha512]] reference).  Unkeyed, no salt /
 *  personalization; the digest length (in bytes) is a parameter (28 for Blake2b-224, 32 for
 *  Blake2b-256, up to 64).  Cross-checked against BouncyCastle's `Blake2bDigest` and `@noble/hashes`. */
object Blake2b:

  /** BLAKE2b-224 (28-byte digest). */
  def hash224(data: Array[Byte]): Array[Byte] = hash(data, 28)

  /** BLAKE2b-256 (32-byte digest) — Cardano's address hash. */
  def hash256(data: Array[Byte]): Array[Byte] = hash(data, 32)

  private val IV: Array[Long] = Array(
    0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
    0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
  )

  // Message-word permutation schedule (12 rounds; rounds 10/11 reuse 0/1).
  private val SIGMA: Array[Array[Int]] = Array(
    Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
    Array(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    Array(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
    Array(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
    Array(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
    Array(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
    Array(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
    Array(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
    Array(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
    Array(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
    Array(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
  )

  private inline def rotr64(x: Long, n: Int): Long = (x >>> n) | (x << (64 - n))

  /** Unkeyed BLAKE2b with `digestLen` output bytes (1..64). */
  def hash(data: Array[Byte], digestLen: Int): Array[Byte] =
    require(digestLen >= 1 && digestLen <= 64, s"BLAKE2b digestLen must be 1..64, got $digestLen")

    val h = IV.clone()
    // Parameter block (unkeyed): digest length | key length (0) | fanout 1 | depth 1.
    h(0) ^= 0x01010000L ^ digestLen.toLong

    val v = new Array[Long](16)
    val m = new Array[Long](16)

    def g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long): Unit =
      v(a) = v(a) + v(b) + x
      v(d) = rotr64(v(d) ^ v(a), 32)
      v(c) = v(c) + v(d)
      v(b) = rotr64(v(b) ^ v(c), 24)
      v(a) = v(a) + v(b) + y
      v(d) = rotr64(v(d) ^ v(a), 16)
      v(c) = v(c) + v(d)
      v(b) = rotr64(v(b) ^ v(c), 63)

    // Compress one 128-byte block held in `m`, with byte counter `t` and last-block flag.
    def compress(t: Long, last: Boolean): Unit =
      var i = 0
      while i < 8 do { v(i) = h(i); v(i + 8) = IV(i); i += 1 }
      v(12) ^= t                // low 64 bits of the counter (inputs here are << 2^64 bytes)
      // v(13) ^= 0             // high 64 bits — always 0 for our sizes
      if last then v(14) ^= 0xffffffffffffffffL
      var r = 0
      while r < 12 do
        val s = SIGMA(r)
        g(0, 4, 8, 12, m(s(0)), m(s(1)))
        g(1, 5, 9, 13, m(s(2)), m(s(3)))
        g(2, 6, 10, 14, m(s(4)), m(s(5)))
        g(3, 7, 11, 15, m(s(6)), m(s(7)))
        g(0, 5, 10, 15, m(s(8)), m(s(9)))
        g(1, 6, 11, 12, m(s(10)), m(s(11)))
        g(2, 7, 8, 13, m(s(12)), m(s(13)))
        g(3, 4, 9, 14, m(s(14)), m(s(15)))
        r += 1
      i = 0
      while i < 8 do { h(i) ^= v(i) ^ v(i + 8); i += 1 }

    // Load 16 little-endian 64-bit words from `block` at offset `off` into `m`.
    def loadBlock(block: Array[Byte], off: Int): Unit =
      var i = 0
      while i < 16 do
        var w = 0L
        var j = 0
        while j < 8 do { w |= (block(off + i * 8 + j) & 0xffL) << (8 * j); j += 1 }
        m(i) = w
        i += 1

    val len = data.length
    // Process all but the final block (BLAKE2b never compresses an empty input mid-stream;
    // the last block — even of an empty message — carries the last-block flag).
    var processed = 0
    while len - processed > 128 do
      loadBlock(data, processed)
      processed += 128
      compress(processed.toLong, last = false)

    // Final block: copy the remaining bytes into a zero-padded 128-byte buffer.
    val finalBlock = new Array[Byte](128)
    val remaining  = len - processed
    System.arraycopy(data, processed, finalBlock, 0, remaining)
    loadBlock(finalBlock, 0)
    compress(len.toLong, last = true)

    // Output the first `digestLen` bytes of `h`, little-endian.
    val out = new Array[Byte](digestLen)
    var i = 0
    while i < digestLen do
      out(i) = ((h(i >>> 3) >>> (8 * (i & 7))) & 0xffL).toByte
      i += 1
    out
