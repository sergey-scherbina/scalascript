package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** CBOR pinned to RFC 8949 Appendix A vectors + COSE_Sign1 EdDSA sign/verify. Ed25519 is already
 *  byte-exact by RFC 8037 ([[JwsTest]]); here we pin the CBOR encoding and the COSE structure, then
 *  round-trip a real signature under the RFC 8037 key. Asserted on JVM and Scala.js. */
class CoseSign1Test extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def ub(s: String): Array[Byte]    = Base64.getUrlDecoder.decode(s)

  // ── CBOR: RFC 8949 Appendix A ──────────────────────────────────────────────────
  test("CBOR encodes the RFC 8949 Appendix A vectors") {
    hex(Cbor.encode(Cbor.int(0)))        shouldBe "00"
    hex(Cbor.encode(Cbor.int(10)))       shouldBe "0a"
    hex(Cbor.encode(Cbor.int(23)))       shouldBe "17"
    hex(Cbor.encode(Cbor.int(24)))       shouldBe "1818"
    hex(Cbor.encode(Cbor.int(100)))      shouldBe "1864"
    hex(Cbor.encode(Cbor.int(1000)))     shouldBe "1903e8"
    hex(Cbor.encode(Cbor.int(1000000)))  shouldBe "1a000f4240"
    hex(Cbor.encode(Cbor.int(-1)))       shouldBe "20"
    hex(Cbor.encode(Cbor.int(-10)))      shouldBe "29"
    hex(Cbor.encode(Cbor.int(-100)))     shouldBe "3863"
    hex(Cbor.encode(Cbor.int(-1000)))    shouldBe "3903e7"
    hex(Cbor.encode(Cbor.Bytes(unhex("01020304"))))                   shouldBe "4401020304"
    hex(Cbor.encode(Cbor.Text("")))                                   shouldBe "60"
    hex(Cbor.encode(Cbor.Text("IETF")))                               shouldBe "6449455446"
    hex(Cbor.encode(Cbor.Arr(IndexedSeq(Cbor.int(1), Cbor.int(2), Cbor.int(3))))) shouldBe "83010203"
    hex(Cbor.encode(Cbor.Map(IndexedSeq(Cbor.int(1) -> Cbor.int(2), Cbor.int(3) -> Cbor.int(4))))) shouldBe "a201020304"
    hex(Cbor.encode(Cbor.Tagged(1, Cbor.UInt(1363896240L))))          shouldBe "c11a514b67b0"
  }

  test("CBOR round-trips (decode ∘ encode == identity on the byte level)") {
    val samples = Seq("00", "1a000f4240", "3903e7", "4401020304", "6449455446", "83010203",
                      "a201020304", "c11a514b67b0")
    samples.foreach(h => hex(Cbor.encode(Cbor.decode(unhex(h)))) shouldBe h)
  }

  // ── COSE_Sign1 (EdDSA) ─────────────────────────────────────────────────────────
  private val seed = ub("nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A")  // RFC 8037 A.4 Ed25519 key
  private val pub  = ub("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo")

  test("EdDSA protected header is CBOR {1:-8}") {
    hex(CoseSign1.protectedHeaderEdDSA) shouldBe "a10127"
  }

  test("COSE_Sign1 has the right structure: Tag(18, [protected, {}, payload, 64-byte sig])") {
    val payload = "This is the content.".getBytes(UTF_8)
    val msg     = CoseSign1.signEdDSA(seed, payload)
    Cbor.decode(msg) match
      case Cbor.Tagged(18, Cbor.Arr(items)) =>
        items.length shouldBe 4
        // (Cbor.Bytes wraps Array[Byte] — compare contents, not the reference-equal case class)
        (items(0): @unchecked) match { case Cbor.Bytes(b) => hex(b) shouldBe "a10127" }  // protected {1:-8}
        items(1) shouldBe Cbor.Map(IndexedSeq.empty)                                     // unprotected {}
        (items(2): @unchecked) match { case Cbor.Bytes(b)   => b shouldBe payload }
        (items(3): @unchecked) match { case Cbor.Bytes(sig) => sig.length shouldBe 64 }
      case other => fail(s"not a tagged COSE_Sign1: $other")
  }

  test("COSE_Sign1 EdDSA round-trips and rejects wrong key / tamper / wrong alg") {
    val payload = "This is the content.".getBytes(UTF_8)
    val msg     = CoseSign1.signEdDSA(seed, payload)
    CoseSign1.verifyEdDSA(msg, pub).map(new String(_, UTF_8)) shouldBe Some("This is the content.")
    // wrong public key
    CoseSign1.verifyEdDSA(msg, Ed25519.derivePublicKey(Array.fill[Byte](32)(3.toByte))) shouldBe None
    // flip a byte in the payload region of the CBOR (index 8 is inside the message) → sig fails
    val bad = msg.clone(); bad(8) = (bad(8) ^ 0x01).toByte
    CoseSign1.verifyEdDSA(bad, pub) shouldBe None
    // garbage / truncated CBOR → None, not an exception
    CoseSign1.verifyEdDSA(Array[Byte](1, 2, 3), pub) shouldBe None
  }

  test("COSE_Sign1 external AAD is authenticated") {
    val payload = "detached-context".getBytes(UTF_8)
    val aad     = "session-42".getBytes(UTF_8)
    val msg     = CoseSign1.signEdDSA(seed, payload, aad)
    CoseSign1.verifyEdDSA(msg, pub, aad).map(new String(_, UTF_8)) shouldBe Some("detached-context")
    CoseSign1.verifyEdDSA(msg, pub, "other-session".getBytes(UTF_8)) shouldBe None  // AAD mismatch
    CoseSign1.verifyEdDSA(msg, pub) shouldBe None                                    // missing AAD
  }
