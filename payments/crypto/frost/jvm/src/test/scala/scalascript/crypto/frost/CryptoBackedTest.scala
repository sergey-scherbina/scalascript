package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo}

/** Slice 7: FROST transparently using the platform-native crypto provider. On the JVM the registered
 *  `CryptoBackend` is BouncyCastle (ServiceLoader), so a `CryptoBackedEd25519Ops` makes FROST's SHA-512 + RNG
 *  run on BouncyCastle while the (BigInteger) group math stays the reference — and the signature still verifies. */
class CryptoBackedTest extends AnyFunSuite:

  test("FROST runs through a CryptoBackend-backed Ed25519Ops (native SHA-512 + RNG), signature verifies"):
    val backed = new CryptoBackedEd25519Ops()
    assert(backed.id.startsWith("crypto-backed"))
    // the native (BouncyCastle) SHA-512 must equal our portable reference SHA-512
    val data = "frost-native".getBytes("UTF-8")
    assert(backed.sha512(data).sameElements(Ed25519Ops.Reference.sha512(data)))
    try
      Ed25519Ops.register(backed)
      assert(Ed25519Ops.current.id.startsWith("crypto-backed"))
      val ks = FrostKeygen.generate(2, 3)
      val msg = "signed via the platform crypto provider".getBytes("UTF-8")
      val signers = ks.shares.take(2)
      val r1 = signers.map(s => s.id -> FrostSign.round1(s.id)).toMap
      val commitments = signers.map(s => r1(s.id)._2)
      val partials = signers.map(s =>
        FrostSign.partialSign(r1(s.id)._1, s, msg, commitments, ks.groupPublicKey))
      val sig = FrostSign.aggregate(msg, commitments, partials)
      // verify with the SAME platform crypto provider
      assert(CryptoBackend.get().verify(Curve.Ed25519, ks.groupPublicKey, msg, sig, HashAlgo.None))
    finally Ed25519Ops.reset()
    assert(Ed25519Ops.current.id == "reference-bigint")
