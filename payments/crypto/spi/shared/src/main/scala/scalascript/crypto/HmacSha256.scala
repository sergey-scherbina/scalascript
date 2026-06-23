package scalascript.crypto

/** Portable HMAC-SHA256 (RFC 2104) over the pure [[Sha256]] — works identically on the JVM and Scala.js. The
 *  from-scratch secp256k1 ECDSA uses it for the RFC-6979 deterministic nonce. Verified against the published
 *  HMAC-SHA256 vectors. */
object HmacSha256:

  private val BlockSize = 64

  /** HMAC-SHA256(key, data) — 32 bytes. */
  def mac(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val k0 = new Array[Byte](BlockSize)
    val kBase = if key.length > BlockSize then Sha256.digest(key) else key
    System.arraycopy(kBase, 0, k0, 0, kBase.length)
    val ipad = new Array[Byte](BlockSize)
    val opad = new Array[Byte](BlockSize)
    var i = 0
    while i < BlockSize do
      ipad(i) = (k0(i) ^ 0x36).toByte
      opad(i) = (k0(i) ^ 0x5c).toByte
      i += 1
    val inner = Sha256.digest(ipad ++ data)
    Sha256.digest(opad ++ inner)
