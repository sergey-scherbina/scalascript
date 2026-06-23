package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.math.BigInteger

/** Cross-platform (JVM + Scala.js) vectors for [[Secp256k1Ecdsa]]: derivePublic anchors, the published RFC-6979
 *  (d=1) deterministic DER signature, low-S enforcement, high-S verification acceptance, determinism and
 *  tamper-rejection. */
class Secp256k1EcdsaTest extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def priv(n: Long): Array[Byte] = Secp256k1Group.to32(BigInteger.valueOf(n))

  // ── public-key derivation ────────────────────────────────────────────────────

  test("derivePublicCompressed(1) == G") {
    hex(Secp256k1Ecdsa.derivePublicCompressed(priv(1))) shouldBe
      "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  }

  test("derivePublicCompressed(2) == 2G") {
    hex(Secp256k1Ecdsa.derivePublicCompressed(priv(2))) shouldBe
      "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
  }

  test("derivePublicXonly drops the parity prefix") {
    hex(Secp256k1Ecdsa.derivePublicXonly(priv(1))) shouldBe
      "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  }

  // ── RFC-6979 deterministic signature (the famous d=1 vector) ──────────────────

  test("RFC-6979 deterministic DER signature for d=1, SHA256(message)") {
    val msg  = "Everything should be made as simple as possible, but not simpler."
    val hash = Sha256.digest(msg.getBytes("UTF-8"))
    val sig  = Secp256k1Ecdsa.sign(priv(1), hash)
    hex(sig) shouldBe
      "3044022033a69cd2065432a30f3d1ce4eb0d59b8ab58c74f27c41a7fdb5696ad4e6108c9" +
      "02206f807982866f785d3f6418d24163ddae117b7db4d5fdf0071de069fa54342262"
    Secp256k1Ecdsa.verify(Secp256k1Ecdsa.derivePublicCompressed(priv(1)), hash, sig) shouldBe true
  }

  // ── sign / verify behaviour ──────────────────────────────────────────────────

  test("sign is deterministic (same key+hash → identical bytes)") {
    val hash = Sha256.digest("sample".getBytes("UTF-8"))
    val a = Secp256k1Ecdsa.sign(priv(12345), hash)
    val b = Secp256k1Ecdsa.sign(priv(12345), hash)
    hex(a) shouldBe hex(b)
  }

  test("sign output is low-S (s ≤ n/2)") {
    val hash = Sha256.digest("low-s check".getBytes("UTF-8"))
    val (_, s) = Secp256k1Ecdsa.decodeDer(Secp256k1Ecdsa.sign(priv(777), hash))
    s.compareTo(Secp256k1Group.N.shiftRight(1)) <= 0 shouldBe true
  }

  test("sign → verify round-trip for several keys") {
    val hash = Sha256.digest("round trip".getBytes("UTF-8"))
    for d <- Seq(1L, 2L, 99L, 1000003L) do
      val sig = Secp256k1Ecdsa.sign(priv(d), hash)
      Secp256k1Ecdsa.verify(Secp256k1Ecdsa.derivePublicCompressed(priv(d)), hash, sig) shouldBe true
  }

  test("verify accepts a high-S (malleated) signature too") {
    val hash = Sha256.digest("malleable".getBytes("UTF-8"))
    val pub  = Secp256k1Ecdsa.derivePublicCompressed(priv(42))
    val (r, s) = Secp256k1Ecdsa.decodeDer(Secp256k1Ecdsa.sign(priv(42), hash))
    val highS  = Secp256k1Ecdsa.encodeDer(r, Secp256k1Group.N.subtract(s))
    Secp256k1Ecdsa.verify(pub, hash, highS) shouldBe true
  }

  test("verify rejects a tampered hash and the wrong public key") {
    val hash = Sha256.digest("authentic".getBytes("UTF-8"))
    val sig  = Secp256k1Ecdsa.sign(priv(8), hash)
    val pub  = Secp256k1Ecdsa.derivePublicCompressed(priv(8))
    Secp256k1Ecdsa.verify(pub, Sha256.digest("forged".getBytes("UTF-8")), sig) shouldBe false
    Secp256k1Ecdsa.verify(Secp256k1Ecdsa.derivePublicCompressed(priv(9)), hash, sig) shouldBe false
  }
