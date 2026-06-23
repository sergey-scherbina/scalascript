package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.math.BigInteger

/** Cross-platform (JVM + Scala.js) vectors for [[Secp256k1Schnorr]]: the published BIP-340 test vector (exact
 *  sign + verify), sign/verify round-trips, tamper rejection and the BIP-341 Taproot priv↔pub tweak invariant. */
class Secp256k1SchnorrTest extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def priv(n: Long): Array[Byte] = Secp256k1Group.to32(BigInteger.valueOf(n))

  // ── BIP-340 official test vector (index 1) ───────────────────────────────────
  private val sk  = unhex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
  private val pk  = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"
  private val aux = unhex("0000000000000000000000000000000000000000000000000000000000000001")
  private val msg = unhex("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
  private val sig = "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de3341" +
                    "8906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a"

  test("BIP-340 vector 1: x-only public key derivation") {
    hex(Secp256k1Ecdsa.derivePublicXonly(sk)) shouldBe pk
  }

  test("BIP-340 vector 1: sign produces the exact published signature") {
    hex(Secp256k1Schnorr.sign(sk, msg, aux)) shouldBe sig
  }

  test("BIP-340 vector 1: verify accepts the published signature") {
    Secp256k1Schnorr.verify(unhex(pk), msg, unhex(sig)) shouldBe true
  }

  test("verify rejects a tampered message and a flipped signature byte") {
    val m2 = msg.clone(); m2(0) = (m2(0) ^ 0x01).toByte
    Secp256k1Schnorr.verify(unhex(pk), m2, unhex(sig)) shouldBe false
    val s2 = unhex(sig); s2(63) = (s2(63) ^ 0x01).toByte
    Secp256k1Schnorr.verify(unhex(pk), msg, s2) shouldBe false
  }

  test("sign → verify round-trip for several keys (default zero auxRand)") {
    val m = Sha256.digest("schnorr round trip".getBytes("UTF-8"))
    for d <- Seq(1L, 2L, 7L, 424242L) do
      val xonly = Secp256k1Ecdsa.derivePublicXonly(priv(d))
      val s     = Secp256k1Schnorr.sign(priv(d), m)
      Secp256k1Schnorr.verify(xonly, m, s) shouldBe true
  }

  // ── BIP-341 Taproot tweak invariant ──────────────────────────────────────────

  test("Taproot key-path: pubkey of tweaked priv equals tweaked internal pubkey (empty merkle root)") {
    for d <- Seq(3L, 99L, 7777L) do
      val xonly      = Secp256k1Ecdsa.derivePublicXonly(priv(d))
      val tweakedPub = Secp256k1Schnorr.tweakedKey(xonly)
      val tweakedPriv = Secp256k1Schnorr.tweakedPrivateKey(priv(d))
      hex(Secp256k1Ecdsa.derivePublicCompressed(tweakedPriv)) shouldBe hex(tweakedPub)
  }

  test("Taproot: a Schnorr sig under the tweaked key verifies against the tweaked x-only output key") {
    val d           = priv(12345)
    val tweakedPriv = Secp256k1Schnorr.tweakedPrivateKey(d)
    val outputKey   = Secp256k1Schnorr.tweakedKey(Secp256k1Ecdsa.derivePublicXonly(d)).drop(1) // x-only
    val m           = Sha256.digest("taproot key-path spend".getBytes("UTF-8"))
    val s           = Secp256k1Schnorr.sign(tweakedPriv, m)
    Secp256k1Schnorr.verify(outputKey, m, s) shouldBe true
  }

  test("tapTweakHash is the tagged hash of x-only || merkleRoot") {
    val xonly = Secp256k1Ecdsa.derivePublicXonly(priv(5))
    hex(Secp256k1Schnorr.tapTweakHash(xonly)) shouldBe
      hex(Secp256k1Schnorr.taggedHash("TapTweak", xonly))
  }
