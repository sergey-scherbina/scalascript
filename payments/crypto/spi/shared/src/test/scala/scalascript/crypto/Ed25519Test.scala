package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Cross-platform (JVM + Scala.js) vectors for the standard [[Ed25519]] (RFC 8032 §7.1). Pins derivePublicKey,
 *  sign and verify against the published vectors, so the suite locks correctness + JVM/JS byte-identity. */
class Ed25519Test extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] =
    if s.isEmpty then Array.emptyByteArray else s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  // ── RFC 8032 Test 1 (empty message) — verify ─────────────────────────────────
  test("RFC 8032 test 1: verify the published signature (empty message)") {
    val pub = unhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val sig = unhex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    Ed25519.verify(pub, Array.emptyByteArray, sig) shouldBe true
  }

  // ── RFC 8032 Test 2 (1-byte message) — derive + sign + verify ────────────────
  test("RFC 8032 test 2: derivePublicKey / sign / verify (1-byte message 0x72)") {
    val seed = unhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
    val pub  = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    val msg  = unhex("72")
    val sig  = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
               "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
    hex(Ed25519.derivePublicKey(seed)) shouldBe pub
    hex(Ed25519.sign(seed, msg))       shouldBe sig
    Ed25519.verify(unhex(pub), msg, unhex(sig)) shouldBe true
  }

  // ── RFC 8032 Test 3 (2-byte message) — derive + sign + verify ────────────────
  test("RFC 8032 test 3: derivePublicKey / sign / verify (2-byte message 0xaf82)") {
    val seed = unhex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
    val pub  = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"
    val msg  = unhex("af82")
    val sig  = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
               "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
    hex(Ed25519.derivePublicKey(seed)) shouldBe pub
    hex(Ed25519.sign(seed, msg))       shouldBe sig
    Ed25519.verify(unhex(pub), msg, unhex(sig)) shouldBe true
  }

  test("verify rejects a tampered message and a flipped signature byte") {
    val seed = unhex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
    val pub  = Ed25519.derivePublicKey(seed)
    val msg  = unhex("af82")
    val sig  = Ed25519.sign(seed, msg)
    Ed25519.verify(pub, unhex("af83"), sig) shouldBe false
    val bad = sig.clone(); bad(0) = (bad(0) ^ 0x01).toByte
    Ed25519.verify(pub, msg, bad) shouldBe false
  }

  test("sign → verify round-trip for several seeds") {
    for b <- Seq(0x00, 0x01, 0x7f, 0xff) do
      val seed = Array.fill[Byte](32)(b.toByte)
      val pub  = Ed25519.derivePublicKey(seed)
      val m    = Sha256.digest(s"ed25519 round trip $b".getBytes("UTF-8"))
      Ed25519.verify(pub, m, Ed25519.sign(seed, m)) shouldBe true
  }
