package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite
import java.math.BigInteger
import scalascript.crypto.{Secp256k1Group, Secp256k1Schnorr, Sha256}

/** FROST-secp256k1 (BIP-340 threshold Schnorr). The gate: a `t`-of-`n` quorum's aggregate signature verifies
 *  under the standard BIP-340 verifier ([[Secp256k1Schnorr.verify]]) against the x-only group key. Runs on
 *  JVM + Scala.js (portable group + entropy). */
class FrostSecp256k1Test extends AnyFunSuite:

  private def big(n: Long): BigInteger = BigInteger.valueOf(n)

  test("2-of-3: every 2-signer subset produces a valid BIP-340 signature") {
    val ks  = FrostSecp256k1.generate(2, 3)
    val msg = Sha256.digest("frost taproot key-path".getBytes("UTF-8"))
    for subset <- List(List(1, 2), List(1, 3), List(2, 3)) do
      val sig = FrostSecp256k1.thresholdSign(ks, subset, msg)
      assert(sig.length == 64, s"signature must be 64 bytes for $subset")
      assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig),
        s"subset $subset must verify under BIP-340")
  }

  test("3-of-5 and 5-of-5 and 1-of-1 thresholds all verify") {
    val msg = Sha256.digest("threshold custody".getBytes("UTF-8"))
    val cases = List((3, 5, List(1, 3, 5)), (5, 5, (1 to 5).toList), (1, 1, List(1)))
    for (t, n, signers) <- cases do
      val ks  = FrostSecp256k1.generate(t, n)
      val sig = FrostSecp256k1.thresholdSign(ks, signers, msg)
      assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig), s"$t-of-$n must verify")
  }

  test("more than t signers (over-quorum) still produces a valid signature") {
    val ks  = FrostSecp256k1.generate(2, 4)
    val msg = Sha256.digest("over quorum".getBytes("UTF-8"))
    val sig = FrostSecp256k1.thresholdSign(ks, List(1, 2, 3, 4), msg)
    assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig))
  }

  test("signature does not verify against a different message") {
    val ks  = FrostSecp256k1.generate(2, 3)
    val sig = FrostSecp256k1.thresholdSign(ks, List(1, 2), Sha256.digest("real".getBytes("UTF-8")))
    assert(!Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, Sha256.digest("forged".getBytes("UTF-8")), sig))
  }

  test("fewer than t signers is rejected") {
    val ks = FrostSecp256k1.generate(3, 5)
    assertThrows[IllegalArgumentException](
      FrostSecp256k1.thresholdSign(ks, List(1, 2), "x".getBytes("UTF-8")))
  }

  test("group key is even-y and equals reconstruct(secret)·G; reconstruction is subset-independent") {
    // deterministic polynomial so the assertions are exact
    val ks = FrostSecp256k1.generateFrom(Array(big(987654321L), big(11111), big(22222)), 5)
    val sk1 = FrostSecp256k1.reconstruct(ks.shares.filter(s => Set(1, 2, 3).contains(s.id)))
    val sk2 = FrostSecp256k1.reconstruct(ks.shares.filter(s => Set(2, 4, 5).contains(s.id)))
    assert(sk1 == sk2, "any t shares reconstruct the same secret")
    val (x, y) = Secp256k1Group.toAffine(Secp256k1Group.mulG(sk1)).getOrElse(fail())
    assert(!y.testBit(0), "group key Y must have even y (BIP-340)")
    assert(Secp256k1Group.to32(x).sameElements(ks.groupPublicKeyXonly), "x-only group key matches secret·G")
  }

  test("a standalone Schnorr sig under the reconstructed secret also verifies (sanity vs the threshold path)") {
    val ks  = FrostSecp256k1.generateFrom(Array(big(424242), big(7)), 3)
    val sk  = FrostSecp256k1.reconstruct(ks.shares.take(2))
    val msg = Sha256.digest("single-key equivalence".getBytes("UTF-8"))  // BIP-340 signs a 32-byte message
    val sig = Secp256k1Schnorr.sign(Secp256k1Group.to32(sk), msg)
    assert(Secp256k1Schnorr.verify(ks.groupPublicKeyXonly, msg, sig))
  }
