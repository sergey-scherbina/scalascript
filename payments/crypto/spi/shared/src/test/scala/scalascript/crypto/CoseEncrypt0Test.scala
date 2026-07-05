package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** COSE_Encrypt0 (alg 24 = ChaCha20-Poly1305) over the RFC-validated Cbor + AEAD, on JVM and Scala.js. */
class CoseEncrypt0Test extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  private val k32   = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
  private val nonce = unhex("070000004041424344454647")

  test("protected header is CBOR {1:24}") {
    hex(CoseEncrypt0.protectedHeader) shouldBe "a1011818"
  }

  test("COSE_Encrypt0 round-trips as Tag(16, [protected, {5:iv}, ct‖tag]) and rejects tamper / wrong k32 / wrong AAD") {
    val pt  = utf8("secret message for COSE_Encrypt0")
    val aad = utf8("ctx")
    val msg = CoseEncrypt0.encrypt(k32, nonce, pt, aad)
    Cbor.decode(msg) match
      case Cbor.Tagged(16, Cbor.Arr(items)) =>
        items.length shouldBe 3
        (items(0): @unchecked) match { case Cbor.Bytes(b) => hex(b) shouldBe "a1011818" }         // protected {1:24}
        (items(2): @unchecked) match { case Cbor.Bytes(c) => c.length shouldBe (pt.length + 16) } // ct ‖ 16-byte tag
      case other => fail(s"not a tagged COSE_Encrypt0: $other")
    CoseEncrypt0.decrypt(k32, msg, aad).map(new String(_, UTF_8)) shouldBe Some("secret message for COSE_Encrypt0")
    // wrong k32, wrong external AAD, flipped tag byte, malformed CBOR → all None
    CoseEncrypt0.decrypt(unhex("00" * 32), msg, aad)          shouldBe None
    CoseEncrypt0.decrypt(k32, msg, utf8("other-context"))     shouldBe None
    val bad = msg.clone(); bad(bad.length - 1) = (bad(bad.length - 1) ^ 0x01).toByte
    CoseEncrypt0.decrypt(k32, bad, aad)                       shouldBe None
    CoseEncrypt0.decrypt(k32, unhex("deadbeef"), aad)         shouldBe None
  }

  test("COSE_Encrypt0 round-trips varied plaintext sizes (no AAD)") {
    for len <- Seq(0, 15, 16, 64, 100) do
      val pt  = Array.tabulate[Byte](len)(i => (i + 1).toByte)
      val msg = CoseEncrypt0.encrypt(k32, nonce, pt)
      CoseEncrypt0.decrypt(k32, msg).map(_.toSeq) shouldBe Some(pt.toSeq)
  }
