package scalascript.crypto.frost

import java.math.BigInteger
import scalascript.crypto.Ed25519Group

/** Shamir secret sharing for **arbitrary byte secrets** — `t`-of-`n` split / recover of seed phrases, private
 *  keys, or any blob (social-recovery / split-backup). Generalizes FROST's scalar-field Shamir
 *  ([[FrostKeygen]]) from a single field element to a length-prefixed, chunked byte string over the prime field
 *  `GF(p)` with `p = 2^255 − 19` ([[Ed25519Group.P]]).
 *
 *  Each 31-byte chunk (`< 2^248 < p`) is split by an independent degree-`(t-1)` polynomial; any `t` shares
 *  Lagrange-interpolate every chunk back, fewer than `t` reveal nothing (information-theoretic Shamir).
 *  Identical on JVM + Scala.js, no platform crypto dependency beyond [[PlatformEntropy]].
 *
 *  Layout per share: `id (1-based) ‖ (32-byte field element per chunk)`. Not SLIP-0039 wire-compatible
 *  (SLIP-0039 uses `GF(256)` + mnemonics); this is the prime-field generalization the roadmap calls for. */
object ShamirSecretSharing:

  private val P = Ed25519Group.P
  private val ChunkBytes = 31              // 31·8 = 248 < 255, so every chunk is a valid field element
  private val ElemBytes  = 32              // a field element (< p < 2^255) fits in 32 bytes
  private val HeaderBytes = 4              // big-endian secret length prefix
  private val ChunkModulus = BigInteger.valueOf(2).pow(8 * ChunkBytes)  // 2^248

  /** One participant's share: `id` (1-based, never 0) and the concatenated 32-byte chunk evaluations. */
  final case class Share(id: Int, data: Array[Byte])

  /** Split `secret` into `total` shares, any `threshold` of which recover it. */
  def split(secret: Array[Byte], threshold: Int, total: Int): List[Share] =
    require(threshold >= 1 && threshold <= total, s"need 1 <= t($threshold) <= n($total)")
    require(total <= 255, s"at most 255 shares, got $total")
    require(secret.length <= 0xFFFFFF, "secret too large (max 16 MiB)")
    val chunks = toChunks(secret)
    // For each chunk, a degree-(t-1) polynomial with f(0) = chunk; share i gets f(i) for every chunk.
    val polys = chunks.map(c => c +: Array.fill(threshold - 1)(randomFieldElement()))
    (1 to total).map { id =>
      val x = BigInteger.valueOf(id.toLong)
      val data = polys.foldLeft(Array.emptyByteArray)((acc, coeffs) => acc ++ toFixed(evalPoly(coeffs, x), ElemBytes))
      Share(id, data)
    }.toList

  /** Recover the secret from `>= t` shares (any `t` of the original `n`). Fewer than `t` yields a different
   *  value (reveals nothing about the secret). */
  def recover(shares: List[Share]): Array[Byte] =
    require(shares.nonEmpty, "no shares")
    require(shares.map(_.id).distinct.size == shares.size, "duplicate share ids")
    val len = shares.head.data.length
    require(shares.forall(_.data.length == len), "shares have inconsistent length")
    require(len % ElemBytes == 0, "corrupt share length")
    val nChunks = len / ElemBytes
    val ids = shares.map(_.id)
    val lambdas = ids.map(i => i -> lagrangeAtZero(i, ids)).toMap
    val payload = (0 until nChunks).foldLeft(Array.emptyByteArray) { (acc, k) =>
      val chunk = shares.foldLeft(BigInteger.ZERO) { (sum, s) =>
        val yi = new BigInteger(1, s.data.slice(k * ElemBytes, (k + 1) * ElemBytes))
        sum.add(yi.multiply(lambdas(s.id))).mod(P)
      }
      // Truncate to the chunk's low 31 bytes: a valid chunk is already < 2^248 (identity); invalid/under-quorum
      // shares reconstruct an arbitrary field element — recover stays total (raw Shamir has no integrity check).
      acc ++ toFixed(chunk.mod(ChunkModulus), ChunkBytes)
    }
    fromChunks(payload)

  // ── encoding ──────────────────────────────────────────────────────────────────

  /** `[4-byte length][secret]`, zero-padded to a multiple of 31, as field-element BigIntegers. */
  private def toChunks(secret: Array[Byte]): Array[BigInteger] =
    val header = Array[Byte](
      ((secret.length >>> 16) & 0xff).toByte, ((secret.length >>> 8) & 0xff).toByte, (secret.length & 0xff).toByte)
    // 3-byte length is enough (max 16 MiB); a 4th reserved byte keeps the header byte-aligned to nothing special
    val framed = (Array[Byte](0) ++ header) ++ secret
    val padded = framed ++ new Array[Byte]((ChunkBytes - framed.length % ChunkBytes) % ChunkBytes)
    padded.grouped(ChunkBytes).map(g => new BigInteger(1, g)).toArray

  private def fromChunks(payload: Array[Byte]): Array[Byte] =
    val len = ((payload(1) & 0xff) << 16) | ((payload(2) & 0xff) << 8) | (payload(3) & 0xff)
    payload.slice(HeaderBytes, HeaderBytes + len)

  /** Big-endian fixed-length unsigned encoding (left zero-padded). */
  private def toFixed(n: BigInteger, len: Int): Array[Byte] =
    val raw = n.toByteArray
    val unsigned = if raw.length > 1 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length) else raw
    require(unsigned.length <= len, s"value too large for $len bytes")
    val out = new Array[Byte](len)
    System.arraycopy(unsigned, 0, out, len - unsigned.length, unsigned.length)
    out

  // ── field arithmetic (mod p) ────────────────────────────────────────────────

  private def evalPoly(coeffs: Array[BigInteger], x: BigInteger): BigInteger =
    var acc = BigInteger.ZERO
    var j = coeffs.length - 1
    while j >= 0 do { acc = acc.multiply(x).add(coeffs(j)).mod(P); j -= 1 }
    acc

  private def lagrangeAtZero(i: Int, ids: List[Int]): BigInteger =
    val xi = BigInteger.valueOf(i.toLong)
    ids.filter(_ != i).foldLeft(BigInteger.ONE) { (acc, j) =>
      val xj = BigInteger.valueOf(j.toLong)
      acc.multiply(xj).multiply(sInv(xj.subtract(xi).mod(P))).mod(P)
    }

  private def sInv(a: BigInteger): BigInteger = a.modPow(P.subtract(BigInteger.valueOf(2)), P)

  private def randomFieldElement(): BigInteger = new BigInteger(1, PlatformEntropy.bytes(40)).mod(P)
