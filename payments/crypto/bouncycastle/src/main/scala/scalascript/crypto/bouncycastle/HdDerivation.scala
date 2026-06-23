package scalascript.crypto.bouncycastle

import scalascript.crypto.{Curve, HashAlgo, HdDerivationCore, HdKey}

/** JVM binding of the shared [[scalascript.crypto.HdDerivationCore]] (BIP-32 / SLIP-0010): supplies
 *  BouncyCastle's HMAC-SHA-512 and compressed-public-key primitives; the derivation algorithm itself
 *  lives in the platform-free core so the JVM and Scala.js backends produce byte-for-byte identical
 *  keys for the same seed/path. */
private[bouncycastle] object HdDerivation:

  private def hmac512(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    Hashes.hmac(HashAlgo.Sha512, key, data)

  def deriveMaster(curve: Curve, seed: Array[Byte]): HdKey =
    HdDerivationCore.deriveMaster(curve, seed, hmac512)

  def deriveChild(curve: Curve, parent: HdKey, index: Long, hardened: Boolean): HdKey =
    HdDerivationCore.deriveChild(curve, parent, index, hardened, hmac512, compressPublic)

  /** 33-byte compressed public key (0x02/0x03 || x), via BouncyCastle base-point multiplication. */
  private def compressPublic(curve: Curve, priv: Array[Byte]): Array[Byte] =
    val uncompressed = curve match
      case Curve.Secp256k1 => Secp256k1.derivePublic(priv)
      case Curve.P256      => P256.derivePublic(priv)
      case other           => throw new IllegalArgumentException(s"compressPublic not supported for $other")
    val x = java.util.Arrays.copyOfRange(uncompressed, 0, 32)
    val y = java.util.Arrays.copyOfRange(uncompressed, 32, 64)
    val parity = (y(31) & 1) == 1
    (if parity then 0x03.toByte else 0x02.toByte) +: x
