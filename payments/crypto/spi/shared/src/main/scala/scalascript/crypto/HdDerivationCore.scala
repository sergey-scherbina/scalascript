package scalascript.crypto

import java.math.BigInteger

/** Platform-free BIP-32 (secp256k1 / P-256) + SLIP-0010 (ed25519) hierarchical-deterministic key
 *  derivation.  The algorithm is identical on every backend; only two primitives differ by platform
 *  and are injected:
 *    - `hmacSha512(key, data)` — HMAC-SHA-512 (BouncyCastle on the JVM, `@noble/hashes` on JS);
 *    - `compressPublic(curve, priv)` — the 33-byte compressed public key (needed for non-hardened
 *      ECDSA child derivation).
 *
 *  Sharing the exact algorithm guarantees byte-for-byte identical keys across backends (the FROST
 *  template: reference → seam → gate → native).  `java.math.BigInteger` is available on both the JVM
 *  and Scala.js, so the scalar arithmetic is host-neutral.  See BIP-32, SLIP-0010. */
object HdDerivationCore:

  val HardenedOffset: Long = 0x80000000L

  // Curve group orders (n) as host-neutral constants (the JVM previously read these from
  // BouncyCastle's SECNamedCurves, which is JVM-only).
  val Secp256k1Order: BigInteger =
    new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
  val P256Order: BigInteger =
    new BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

  private def curveOrder(curve: Curve): BigInteger = curve match
    case Curve.Secp256k1 => Secp256k1Order
    case Curve.P256      => P256Order
    case other           => throw new IllegalArgumentException(s"No curve order for $other")

  private def masterSeedKey(curve: Curve): Array[Byte] = curve match
    case Curve.Secp256k1 => "Bitcoin seed".getBytes("UTF-8")
    case Curve.Ed25519   => "ed25519 seed".getBytes("UTF-8")
    case Curve.P256      => "Nist256p1 seed".getBytes("UTF-8")
    case other           => throw new IllegalArgumentException(s"HD derivation not supported for $other")

  def deriveMaster(
      curve: Curve,
      seed: Array[Byte],
      hmacSha512: (Array[Byte], Array[Byte]) => Array[Byte],
  ): HdKey =
    val key = masterSeedKey(curve)
    curve match
      case Curve.Ed25519 =>
        val I = hmacSha512(key, seed)
        HdKey(java.util.Arrays.copyOfRange(I, 0, 32), java.util.Arrays.copyOfRange(I, 32, 64))
      case Curve.Secp256k1 | Curve.P256 =>
        // BIP-32 / SLIP-0010: retry with `0x01 || I_L` if the scalar is 0 or >= n.
        val n = curveOrder(curve)
        var data = seed
        var result: HdKey = null
        while result == null do
          val I  = hmacSha512(key, data)
          val IL = java.util.Arrays.copyOfRange(I, 0, 32)
          val IR = java.util.Arrays.copyOfRange(I, 32, 64)
          val k  = new BigInteger(1, IL)
          if k.signum > 0 && k.compareTo(n) < 0 then result = HdKey(IL, IR)
          else data = 0x01.toByte +: IL
        result
      case other => throw new IllegalArgumentException(s"HD derivation not supported for $other")

  def deriveChild(
      curve: Curve,
      parent: HdKey,
      index: Long,
      hardened: Boolean,
      hmacSha512: (Array[Byte], Array[Byte]) => Array[Byte],
      compressPublic: (Curve, Array[Byte]) => Array[Byte],
  ): HdKey =
    val idx = if hardened then index | HardenedOffset else index
    curve match
      case Curve.Ed25519 =>
        if !hardened then
          throw new IllegalArgumentException("SLIP-0010 ed25519 supports only hardened child derivation")
        val data = Array[Byte](0x00) ++ parent.privateKey ++ ser32(idx)
        val I    = hmacSha512(parent.chainCode, data)
        HdKey(java.util.Arrays.copyOfRange(I, 0, 32), java.util.Arrays.copyOfRange(I, 32, 64))
      case Curve.Secp256k1 | Curve.P256 =>
        val n = curveOrder(curve)
        val data =
          if hardened then Array[Byte](0x00) ++ leftPad32(parent.privateKey) ++ ser32(idx)
          else compressPublic(curve, parent.privateKey) ++ ser32(idx)
        val I    = hmacSha512(parent.chainCode, data)
        val IL   = java.util.Arrays.copyOfRange(I, 0, 32)
        val IR   = java.util.Arrays.copyOfRange(I, 32, 64)
        val left = new BigInteger(1, IL)
        if left.compareTo(n) >= 0 then
          throw new IllegalStateException("HD derivation: left >= n, choose another index")
        val childScalar = left.add(new BigInteger(1, parent.privateKey)).mod(n)
        if childScalar.signum == 0 then
          throw new IllegalStateException("HD derivation: child scalar is zero, choose another index")
        HdKey(leftPad32(childScalar.toByteArray.dropWhile(_ == 0)), IR)
      case other => throw new IllegalArgumentException(s"HD derivation not supported for $other")

  // ── encoding helpers ────────────────────────────────────────────────────

  /** Big-endian 4-byte serialization of a 32-bit child index (BIP-32 `ser32`). */
  def ser32(i: Long): Array[Byte] =
    Array[Byte](
      ((i >> 24) & 0xff).toByte,
      ((i >> 16) & 0xff).toByte,
      ((i >>  8) & 0xff).toByte,
      ( i        & 0xff).toByte,
    )

  /** Left-pad (or trim a leading sign byte from) a byte string to exactly 32 bytes. */
  def leftPad32(b: Array[Byte]): Array[Byte] =
    if b.length == 32 then b
    else if b.length < 32 then
      val out = new Array[Byte](32)
      System.arraycopy(b, 0, out, 32 - b.length, b.length)
      out
    else if b.length == 33 && b(0) == 0 then java.util.Arrays.copyOfRange(b, 1, 33)
    else throw new IllegalArgumentException(s"Cannot pad ${b.length}-byte value to 32 bytes")
