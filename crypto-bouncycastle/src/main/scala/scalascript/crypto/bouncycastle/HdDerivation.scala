package scalascript.crypto.bouncycastle

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves

import scalascript.crypto.{Curve, HashAlgo, HdKey}

/** BIP-32 (secp256k1) and SLIP-0010 (ed25519 / p256) hierarchical-
 *  deterministic key derivation.
 *
 *  - secp256k1: BIP-32 master seed key is "Bitcoin seed". Both normal
 *    (non-hardened) and hardened child derivation are supported.
 *  - ed25519: SLIP-0010 master seed key is "ed25519 seed". Only
 *    hardened derivation is supported per the standard.
 *  - p256:    SLIP-0010 master seed key is "Nist256p1 seed". Both
 *    normal and hardened derivation supported.
 *
 *  See BIP-32, SLIP-0010 for the algorithms. */
private[bouncycastle] object HdDerivation:

  private val HardenedOffset: Long = 0x80000000L

  // Secp256k1 / P-256 curve orders (used for modular reduction)
  private val Secp256k1Order =
    SECNamedCurves.getByName("secp256k1").getN
  private val P256Order =
    SECNamedCurves.getByName("secp256r1").getN

  def deriveMaster(curve: Curve, seed: Array[Byte]): HdKey =
    val key = curve match
      case Curve.Secp256k1 => "Bitcoin seed".getBytes("UTF-8")
      case Curve.Ed25519   => "ed25519 seed".getBytes("UTF-8")
      case Curve.P256      => "Nist256p1 seed".getBytes("UTF-8")
      case other           => throw new IllegalArgumentException(s"HD derivation not supported for $other")

    curve match
      case Curve.Secp256k1 | Curve.P256 => masterEcdsa(curve, key, seed)
      case Curve.Ed25519                => masterEd25519(key, seed)
      case other                        => throw new IllegalStateException(s"unreachable: $other")

  def deriveChild(curve: Curve, parent: HdKey, index: Long, hardened: Boolean): HdKey =
    val idx = if hardened then index | HardenedOffset else index
    curve match
      case Curve.Secp256k1 => childSecp256k1(parent, idx, hardened)
      case Curve.P256      => childP256(parent, idx, hardened)
      case Curve.Ed25519   => childEd25519(parent, idx, hardened)
      case other           => throw new IllegalArgumentException(s"HD derivation not supported for $other")

  // ── master ──────────────────────────────────────────────────────────────

  /** For ECDSA curves (BIP-32 / SLIP-0010): retry with `0x01 || I_L` if
   *  the derived private scalar is 0 or ≥ curve order. */
  private def masterEcdsa(curve: Curve, key: Array[Byte], seed: Array[Byte]): HdKey =
    var data = seed
    while true do
      val I  = Hashes.hmac(HashAlgo.Sha512, key, data)
      val IL = java.util.Arrays.copyOfRange(I, 0, 32)
      val IR = java.util.Arrays.copyOfRange(I, 32, 64)
      val k  = new BigInteger(1, IL)
      val n  = curveOrder(curve)
      if k.signum > 0 && k.compareTo(n) < 0 then return HdKey(IL, IR)
      data = (0x01.toByte +: IL) // retry on invalid scalar
    throw new IllegalStateException("unreachable")

  private def masterEd25519(key: Array[Byte], seed: Array[Byte]): HdKey =
    val I  = Hashes.hmac(HashAlgo.Sha512, key, seed)
    val IL = java.util.Arrays.copyOfRange(I, 0, 32)
    val IR = java.util.Arrays.copyOfRange(I, 32, 64)
    HdKey(IL, IR)

  // ── child ───────────────────────────────────────────────────────────────

  private def childSecp256k1(parent: HdKey, index: Long, hardened: Boolean): HdKey =
    childEcdsa(parent, index, hardened, Curve.Secp256k1)

  private def childP256(parent: HdKey, index: Long, hardened: Boolean): HdKey =
    childEcdsa(parent, index, hardened, Curve.P256)

  private def childEcdsa(parent: HdKey, index: Long, hardened: Boolean, curve: Curve): HdKey =
    val n     = curveOrder(curve)
    val data  =
      if hardened then
        Array[Byte](0x00) ++ leftPad32(parent.privateKey) ++ ser32(index)
      else
        // Non-hardened: use compressed parent public key
        val pubCompressed = compressPublic(curve, parent.privateKey)
        pubCompressed ++ ser32(index)
    val I  = Hashes.hmac(HashAlgo.Sha512, parent.chainCode, data)
    val IL = java.util.Arrays.copyOfRange(I, 0, 32)
    val IR = java.util.Arrays.copyOfRange(I, 32, 64)
    val left = new BigInteger(1, IL)
    if left.compareTo(n) >= 0 then
      // Vanishingly rare; per BIP-32 retry with next index. Caller handles by
      // selecting a different path — we surface as exception so behaviour is
      // explicit rather than silently skipping.
      throw new IllegalStateException("HD derivation: left >= n, choose another index")
    val parentScalar = new BigInteger(1, parent.privateKey)
    val childScalar  = left.add(parentScalar).mod(n)
    if childScalar.signum == 0 then
      throw new IllegalStateException("HD derivation: child scalar is zero, choose another index")
    HdKey(leftPad32(childScalar.toByteArray.dropWhile(_ == 0)), IR)

  private def childEd25519(parent: HdKey, index: Long, hardened: Boolean): HdKey =
    if !hardened then
      throw new IllegalArgumentException("SLIP-0010 ed25519 supports only hardened child derivation")
    val data = Array[Byte](0x00) ++ parent.privateKey ++ ser32(index)
    val I    = Hashes.hmac(HashAlgo.Sha512, parent.chainCode, data)
    HdKey(java.util.Arrays.copyOfRange(I, 0, 32), java.util.Arrays.copyOfRange(I, 32, 64))

  // ── encoding helpers ────────────────────────────────────────────────────

  private def ser32(i: Long): Array[Byte] =
    Array[Byte](
      ((i >> 24) & 0xff).toByte,
      ((i >> 16) & 0xff).toByte,
      ((i >>  8) & 0xff).toByte,
      ( i        & 0xff).toByte,
    )

  private def leftPad32(b: Array[Byte]): Array[Byte] =
    if b.length == 32 then b
    else if b.length < 32 then
      val out = new Array[Byte](32)
      System.arraycopy(b, 0, out, 32 - b.length, b.length)
      out
    else if b.length == 33 && b(0) == 0 then java.util.Arrays.copyOfRange(b, 1, 33)
    else throw new IllegalArgumentException(s"Cannot pad ${b.length}-byte value to 32 bytes")

  private def compressPublic(curve: Curve, priv: Array[Byte]): Array[Byte] =
    val uncompressed = curve match
      case Curve.Secp256k1 => Secp256k1.derivePublic(priv)
      case Curve.P256      => P256.derivePublic(priv)
      case other           => throw new IllegalArgumentException(s"compressPublic not supported for $other")
    val x = java.util.Arrays.copyOfRange(uncompressed, 0, 32)
    val y = java.util.Arrays.copyOfRange(uncompressed, 32, 64)
    val parity = (y(31) & 1) == 1
    (if parity then 0x03.toByte else 0x02.toByte) +: x

  private def curveOrder(curve: Curve): BigInteger =
    curve match
      case Curve.Secp256k1 => Secp256k1Order
      case Curve.P256      => P256Order
      case other           => throw new IllegalArgumentException(s"No curve order for $other")
