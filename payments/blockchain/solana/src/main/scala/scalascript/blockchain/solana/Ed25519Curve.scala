package scalascript.blockchain.solana

import java.math.BigInteger

/** Tiny ed25519 on-curve check, used by [[Pda]] to skip bump
 *  candidates whose hash happens to land on the curve (i.e. would
 *  collide with a real ed25519 public key). We don't need full
 *  point decompression — only "is this 32-byte y coordinate
 *  consistent with some on-curve point?". */
private[solana] object Ed25519Curve:

  /** Field prime p = 2^255 − 19. */
  private val P: BigInteger =
    BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

  /** Curve constant d = −121665/121666 mod p (per RFC 8032 §5.1). */
  private val D: BigInteger = new BigInteger(
    "37095705934669439343138083508754565189542113879843219016388785533085940283555"
  )

  private val PMinus1Div2: BigInteger =
    P.subtract(BigInteger.ONE).shiftRight(1)

  /** Return true iff the 32-byte little-endian compressed encoding
   *  in `point` corresponds to a point on the ed25519 curve. The
   *  sign bit (high bit of byte 31) is ignored — both possible x
   *  signs share the same on-curve verdict. */
  def isOnCurve(point: Array[Byte]): Boolean =
    if point.length != 32 then false
    else
      val masked = point.clone()
      masked(31) = (masked(31) & 0x7f).toByte
      // Convert little-endian bytes → BigInteger.
      val y = new BigInteger(1, masked.reverse)
      if y.compareTo(P) >= 0 then false
      else
        // x² = (y² − 1) / (d·y² + 1) mod p. Point is on-curve iff x² is
        // a quadratic residue mod p (Euler's criterion).
        val y2 = y.multiply(y).mod(P)
        val u  = y2.subtract(BigInteger.ONE).mod(P)
        val v  = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if v.signum() == 0 then false
        else
          val x2 = u.multiply(v.modInverse(P)).mod(P)
          if x2.signum() == 0 then true
          else x2.modPow(PMinus1Div2, P).equals(BigInteger.ONE)
