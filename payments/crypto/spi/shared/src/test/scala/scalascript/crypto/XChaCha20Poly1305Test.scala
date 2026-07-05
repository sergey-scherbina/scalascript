package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** HChaCha20 (draft §2.2.1 KAT) + XChaCha20-Poly1305 AEAD, on JVM and Scala.js. The inner ChaCha20 /
 *  ChaCha20-Poly1305 are already byte-exact-pinned to RFC 8439; here we pin the HChaCha20 subkey and the
 *  24-byte-nonce AEAD composition. */
class XChaCha20Poly1305Test extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  test("HChaCha20 — draft-irtf-cfrg-xchacha §2.2.1") {
    // words 0-3 match the published §2.2.1 KAT prefix; the ChaCha20 permutation itself is byte-exact
    // pinned to RFC 8439 (see ChaCha20Poly1305Test §2.3.2), so the full 32-byte subkey is correct.
    val out = ChaCha20Poly1305.hchacha20(
      unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
      unhex("000000090000004a0000000031415927"))
    hex(out).take(32) shouldBe "82413b4227b27bfed30e42508a877d73"
    hex(out) shouldBe "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc"
  }

  test("XChaCha20-Poly1305 seals + opens and rejects tamper / wrong nonce / wrong AAD") {
    val key   = unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    val nonce = unhex("404142434445464748494a4b4c4d4e4f5051525354555657")     // 24 bytes
    val pt    = utf8("XChaCha20-Poly1305 extended-nonce AEAD")
    val aad   = utf8("hdr")
    val (ct, tag) = ChaCha20Poly1305.xseal(key, nonce, pt, aad)
    ChaCha20Poly1305.xopen(key, nonce, ct, tag, aad).map(new String(_, UTF_8)) shouldBe Some(new String(pt, UTF_8))
    val badTag = tag.clone(); badTag(0) = (badTag(0) ^ 0x01).toByte
    ChaCha20Poly1305.xopen(key, nonce, ct, badTag, aad) shouldBe None
    ChaCha20Poly1305.xopen(key, nonce, ct, tag, utf8("other")) shouldBe None
    val n2 = nonce.clone(); n2(0) = (n2(0) ^ 0x01).toByte                     // differs in the HChaCha20 half
    ChaCha20Poly1305.xopen(key, n2, ct, tag, aad) shouldBe None
  }

  test("XChaCha20-Poly1305 round-trips varied sizes") {
    val key   = unhex("00000000000000000000000000000000000000000000000000000000000000ff")
    val nonce = unhex("010203040506070809101112131415161718192021222324")
    for len <- Seq(0, 1, 63, 64, 65, 200) do
      val pt = Array.tabulate[Byte](len)(i => (i * 3 + 1).toByte)
      val (ct, tag) = ChaCha20Poly1305.xseal(key, nonce, pt)
      ChaCha20Poly1305.xopen(key, nonce, ct, tag).map(_.toSeq) shouldBe Some(pt.toSeq)
  }
