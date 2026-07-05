package scalascript.crypto

/** Portable HKDF-SHA256 (RFC 5869) — HMAC-based extract-and-expand key derivation over [[HmacSha256]].
 *  Pure Scala, identical on JVM and Scala.js, no platform crypto. The KDF used by Noise, `age`, TLS 1.3,
 *  and many others. */
object HkdfSha256:

  private val HashLen = 32

  /** Extract a pseudo-random key from the input keying material. An empty `salt` defaults to `HashLen`
   *  zero bytes (RFC 5869 §2.2). */
  def extract(salt: Array[Byte], ikm: Array[Byte]): Array[Byte] =
    val s = if salt.isEmpty then new Array[Byte](HashLen) else salt
    HmacSha256.mac(s, ikm)

  /** Expand a pseudo-random key to `length` output bytes bound to `info` (RFC 5869 §2.3). */
  def expand(prk: Array[Byte], info: Array[Byte], length: Int): Array[Byte] =
    require(length >= 0 && length <= 255 * HashLen, s"HKDF-SHA256 length must be 0..${255 * HashLen}")
    val out  = new Array[Byte](length)
    var t    = Array.emptyByteArray
    var pos  = 0
    var i    = 1
    while pos < length do
      t = HmacSha256.mac(prk, t ++ info ++ Array[Byte](i.toByte))
      val n = math.min(HashLen, length - pos)
      System.arraycopy(t, 0, out, pos, n)
      pos += n
      i += 1
    out

  /** Extract-then-expand in one call: `expand(extract(salt, ikm), info, length)`. */
  def derive(salt: Array[Byte], ikm: Array[Byte], info: Array[Byte], length: Int): Array[Byte] =
    expand(extract(salt, ikm), info, length)
