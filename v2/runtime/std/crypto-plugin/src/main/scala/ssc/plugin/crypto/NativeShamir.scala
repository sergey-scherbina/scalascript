package ssc.plugin.crypto

import java.math.BigInteger

private[crypto] object NativeShamir:
  private val Prime = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
  private val ChunkBytes = 31
  private val ElementBytes = 32
  private val HeaderBytes = 4
  private val ChunkModulus = BigInteger.valueOf(2).pow(8 * ChunkBytes)

  final case class Share(id: Int, data: Array[Byte])

  def split(secret: Array[Byte], threshold: Int, total: Int): List[Share] =
    require(threshold >= 1 && threshold <= total,
      s"need 1 <= t($threshold) <= n($total)")
    require(total <= 255, s"at most 255 shares, got $total")
    require(secret.length <= 0xffffff, "secret too large (max 16 MiB)")
    val polynomials = chunks(secret).map { constant =>
      constant +: Array.fill(threshold - 1)(randomElement())
    }
    (1 to total).map { id =>
      val x = BigInteger.valueOf(id.toLong)
      val data = polynomials.foldLeft(Array.emptyByteArray) { (result, coefficients) =>
        result ++ fixed(evaluate(coefficients, x), ElementBytes)
      }
      Share(id, data)
    }.toList

  def recover(shares: List[Share]): Array[Byte] =
    require(shares.nonEmpty, "no shares")
    require(shares.map(_.id).distinct.size == shares.size, "duplicate share ids")
    require(shares.forall(share => share.id >= 1 && share.id <= 255), "invalid share id")
    val length = shares.head.data.length
    require(shares.forall(_.data.length == length), "shares have inconsistent length")
    require(length >= ElementBytes && length % ElementBytes == 0, "corrupt share length")
    val ids = shares.map(_.id)
    val weights = ids.map(id => id -> lagrangeAtZero(id, ids)).toMap
    val payload = (0 until length / ElementBytes).foldLeft(Array.emptyByteArray) { (result, chunk) =>
      val value = shares.foldLeft(BigInteger.ZERO) { (sum, share) =>
        val offset = chunk * ElementBytes
        val y = BigInteger(1, share.data.slice(offset, offset + ElementBytes))
        sum.add(y.multiply(weights(share.id))).mod(Prime)
      }
      result ++ fixed(value.mod(ChunkModulus), ChunkBytes)
    }
    fromChunks(payload)

  def encode(share: Share): String =
    NativeCrypto.encode(Array(share.id.toByte) ++ share.data)

  def decode(encoded: String): Share =
    val bytes = NativeCrypto.decode(encoded)
    require(bytes.length > 1, "corrupt share")
    Share(bytes.head & 0xff, java.util.Arrays.copyOfRange(bytes, 1, bytes.length))

  private def chunks(secret: Array[Byte]): Array[BigInteger] =
    val header = Array[Byte](
      0,
      ((secret.length >>> 16) & 0xff).toByte,
      ((secret.length >>> 8) & 0xff).toByte,
      (secret.length & 0xff).toByte)
    val framed = header ++ secret
    val padded = framed ++ new Array[Byte]((ChunkBytes - framed.length % ChunkBytes) % ChunkBytes)
    padded.grouped(ChunkBytes).map(bytes => BigInteger(1, bytes)).toArray

  private def fromChunks(payload: Array[Byte]): Array[Byte] =
    require(payload.length >= HeaderBytes, "corrupt share payload")
    val length =
      ((payload(1) & 0xff) << 16) |
        ((payload(2) & 0xff) << 8) |
        (payload(3) & 0xff)
    payload.slice(HeaderBytes, HeaderBytes + length)

  private def fixed(value: BigInteger, length: Int): Array[Byte] =
    val raw = value.toByteArray
    val unsigned =
      if raw.length > 1 && raw.head == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length)
      else raw
    require(unsigned.length <= length, s"value too large for $length bytes")
    val result = new Array[Byte](length)
    System.arraycopy(unsigned, 0, result, length - unsigned.length, unsigned.length)
    result

  private def evaluate(coefficients: Array[BigInteger], x: BigInteger): BigInteger =
    var result = BigInteger.ZERO
    var index = coefficients.length - 1
    while index >= 0 do
      result = result.multiply(x).add(coefficients(index)).mod(Prime)
      index -= 1
    result

  private def lagrangeAtZero(id: Int, ids: List[Int]): BigInteger =
    val x = BigInteger.valueOf(id.toLong)
    ids.filter(_ != id).foldLeft(BigInteger.ONE) { (result, other) =>
      val y = BigInteger.valueOf(other.toLong)
      result.multiply(y).multiply(inverse(y.subtract(x).mod(Prime))).mod(Prime)
    }

  private def inverse(value: BigInteger): BigInteger =
    value.modPow(Prime.subtract(BigInteger.valueOf(2)), Prime)

  private def randomElement(): BigInteger =
    val bytes = new Array[Byte](40)
    java.security.SecureRandom().nextBytes(bytes)
    BigInteger(1, bytes).mod(Prime)
