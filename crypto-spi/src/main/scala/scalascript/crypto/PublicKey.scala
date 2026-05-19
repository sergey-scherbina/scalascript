package scalascript.crypto

/** A public key with its curve. Byte encoding is curve-specific:
 *  - Secp256k1: uncompressed (64B, no `0x04` prefix) or compressed (33B)
 *  - Ed25519: 32B raw
 *  - P256: uncompressed (64B) or compressed (33B)
 *
 *  Consumers should document which encoding they accept / produce. */
case class PublicKey(curve: Curve, bytes: Array[Byte]):
  // Identity-based equality on bytes is needed for tests and registry lookups.
  override def equals(other: Any): Boolean = other match
    case that: PublicKey =>
      this.curve == that.curve && this.bytes.sameElements(that.bytes)
    case _ => false

  override def hashCode: Int =
    curve.hashCode * 31 + java.util.Arrays.hashCode(bytes)

  override def toString: String =
    s"PublicKey($curve, ${bytes.length}B)"
