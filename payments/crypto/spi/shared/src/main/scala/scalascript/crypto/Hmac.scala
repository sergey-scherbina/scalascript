package scalascript.crypto

/** Portable generic HMAC (RFC 2104) over any of the pure hashes here. Parameterised by the digest function and
 *  its block size, so HOTP/TOTP can use HMAC-SHA1/SHA256/SHA512 with no platform crypto dependency. (The
 *  secp256k1 RFC-6979 path uses the specialised [[HmacSha256]].) Identical on JVM + Scala.js. */
object Hmac:

  /** `HMAC(key, data)` for the given `digest` with block size `blockSize` bytes (64 for SHA-1/256, 128 for
   *  SHA-512). */
  def mac(digest: Array[Byte] => Array[Byte], blockSize: Int, key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val k0    = new Array[Byte](blockSize)
    val kBase = if key.length > blockSize then digest(key) else key
    System.arraycopy(kBase, 0, k0, 0, kBase.length)
    val ipad = new Array[Byte](blockSize)
    val opad = new Array[Byte](blockSize)
    var i = 0
    while i < blockSize do
      ipad(i) = (k0(i) ^ 0x36).toByte
      opad(i) = (k0(i) ^ 0x5c).toByte
      i += 1
    digest(opad ++ digest(ipad ++ data))

  /** HMAC-SHA1 (20-byte tag). */
  def sha1(key: Array[Byte], data: Array[Byte]): Array[Byte] = mac(Sha1.digest, 64, key, data)

  /** HMAC-SHA256 (32-byte tag). */
  def sha256(key: Array[Byte], data: Array[Byte]): Array[Byte] = mac(Sha256.digest, 64, key, data)

  /** HMAC-SHA512 (64-byte tag). */
  def sha512(key: Array[Byte], data: Array[Byte]): Array[Byte] = mac(Sha512.digest, 128, key, data)
