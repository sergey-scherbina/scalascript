package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** ChaCha20-Poly1305 (RFC 8439) pinned to the RFC test vectors, on JVM and Scala.js. */
class ChaCha20Poly1305Test extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  test("ChaCha20 block function — RFC 8439 §2.3.2") {
    val key   = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    val nonce = unhex("000000090000004a00000000")
    hex(ChaCha20Poly1305.block(key, 1, nonce)) shouldBe
      "10f1e7e4d13b5915500fdd1fa32071c4" +
      "c7d1f4c733c068030422aa9ac3d46c4e" +
      "d2826446079faa0914c2d705d98b02a2" +
      "b5129cd1de164eb9cbd083e8a2503c4e"
  }

  test("Poly1305 MAC — RFC 8439 §2.5.2") {
    val key = unhex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
    hex(ChaCha20Poly1305.poly1305(key, utf8("Cryptographic Forum Research Group"))) shouldBe
      "a8061dc1305136c6c22b8baf0c0127a9"
  }

  test("AEAD ChaCha20-Poly1305 — RFC 8439 §2.8.2 (tag byte-exact) + open round-trip + tamper") {
    val key   = unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    val nonce = unhex("070000004041424344454647")
    val aad   = unhex("50515253c0c1c2c3c4c5c6c7")
    val pt    = utf8("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")

    val (ct, tag) = ChaCha20Poly1305.seal(key, nonce, pt, aad)
    hex(tag) shouldBe "1ae10b594f09e26a7e902ecbd0600691"        // the RFC tag — with the §2.3.2 block KAT this pins ct too
    ct.length shouldBe pt.length
    ChaCha20Poly1305.open(key, nonce, ct, tag, aad).map(new String(_, UTF_8)) shouldBe
      Some(new String(pt, UTF_8))

    // tamper: flipped tag, flipped ciphertext, and wrong AAD all fail authentication
    val badTag = tag.clone(); badTag(0) = (badTag(0) ^ 0x01).toByte
    ChaCha20Poly1305.open(key, nonce, ct, badTag, aad) shouldBe None
    val badCt = ct.clone(); badCt(0) = (badCt(0) ^ 0x01).toByte
    ChaCha20Poly1305.open(key, nonce, badCt, tag, aad) shouldBe None
    ChaCha20Poly1305.open(key, nonce, ct, tag, unhex("00")) shouldBe None
  }

  test("AEAD round-trips arbitrary sizes (empty, sub-block, multi-block)") {
    val key   = unhex("0000000000000000000000000000000000000000000000000000000000000001")
    val nonce = unhex("000000000000000000000002")
    for len <- Seq(0, 1, 16, 63, 64, 65, 200) do
      val pt = Array.tabulate[Byte](len)(i => (i * 7 + 3).toByte)
      val (ct, tag) = ChaCha20Poly1305.seal(key, nonce, pt, utf8("hdr"))
      ChaCha20Poly1305.open(key, nonce, ct, tag, utf8("hdr")).map(_.toSeq) shouldBe Some(pt.toSeq)
  }
