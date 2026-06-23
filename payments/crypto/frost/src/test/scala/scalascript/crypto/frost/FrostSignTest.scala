package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite
import java.math.BigInteger
import java.security.SecureRandom

class FrostSignTest extends AnyFunSuite:

  /** Verify with the reference (BouncyCastle) Ed25519 — the authoritative correctness gate. */
  private def bcVerify(pub: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    val signer = new org.bouncycastle.crypto.signers.Ed25519Signer()
    signer.init(false, new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pub, 0))
    signer.update(msg, 0, msg.length)
    signer.verifySignature(sig)

  private def frostSign(
      ks: FrostKeygen.KeyShares, signers: List[FrostKeygen.Share],
      msg: Array[Byte], rng: SecureRandom): Array[Byte] =
    val r1 = signers.map(s => s.id -> FrostSign.round1(s.id, rng)).toMap
    val commitments = signers.map(s => r1(s.id)._2)
    val partials = signers.map(s =>
      FrostSign.partialSign(r1(s.id)._1, s, msg, commitments, ks.groupPublicKey))
    FrostSign.aggregate(msg, commitments, partials)

  test("2-of-3 FROST signature verifies under standard (BouncyCastle) Ed25519"):
    val rng = new SecureRandom(); rng.setSeed(42L)
    val ks  = FrostKeygen.generate(2, 3, rng)
    val msg = "hello frost".getBytes("UTF-8")
    val sig = frostSign(ks, ks.shares.take(2), msg, rng)
    assert(sig.length == 64)
    assert(bcVerify(ks.groupPublicKey, msg, sig))

  test("every t-of-n signing subset verifies (t=3, n=5)"):
    val rng = new SecureRandom(); rng.setSeed(7L)
    val ks  = FrostKeygen.generate(3, 5, rng)
    val msg = "multi-subset".getBytes("UTF-8")
    ks.shares.combinations(3).foreach { sub =>
      val sig = frostSign(ks, sub, msg, rng)
      assert(bcVerify(ks.groupPublicKey, msg, sig), s"subset ${sub.map(_.id)} failed to verify")
    }

  test("a tampered partial signature fails verification"):
    val rng = new SecureRandom(); rng.setSeed(99L)
    val ks  = FrostKeygen.generate(2, 3, rng)
    val msg = "tamper".getBytes("UTF-8")
    val signers = ks.shares.take(2)
    val r1 = signers.map(s => s.id -> FrostSign.round1(s.id, rng)).toMap
    val commitments = signers.map(s => r1(s.id)._2)
    val partials = signers.map(s =>
      FrostSign.partialSign(r1(s.id)._1, s, msg, commitments, ks.groupPublicKey))
    val tampered = partials.head.add(BigInteger.ONE) :: partials.tail
    val sig = FrostSign.aggregate(msg, commitments, tampered)
    assert(!bcVerify(ks.groupPublicKey, msg, sig))

  test("wrong message fails verification"):
    val rng = new SecureRandom(); rng.setSeed(123L)
    val ks  = FrostKeygen.generate(2, 2, rng)
    val sig = frostSign(ks, ks.shares, "msg-A".getBytes("UTF-8"), rng)
    assert(bcVerify(ks.groupPublicKey, "msg-A".getBytes("UTF-8"), sig))
    assert(!bcVerify(ks.groupPublicKey, "msg-B".getBytes("UTF-8"), sig))
