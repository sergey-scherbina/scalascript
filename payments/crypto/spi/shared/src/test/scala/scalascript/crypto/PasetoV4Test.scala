package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** Portable PASETO v4.public — PAE against the PASETO spec vectors + sign/verify against the official
 *  `v4.json` "4-S-1" key material, asserted on JVM and Scala.js. Ed25519 itself is already pinned
 *  byte-exact by RFC 8037 (see [[JwsTest]]); here we pin the PAE framing and token structure. */
class PasetoV4Test extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  // ── PAE: PASETO spec vectors (§Common "PAE") ───────────────────────────────────
  test("PAE reproduces the PASETO spec vectors") {
    hex(PasetoV4.pae(Array()))                 shouldBe "0000000000000000"
    hex(PasetoV4.pae(Array(utf8(""))))         shouldBe "01000000000000000000000000000000"
    hex(PasetoV4.pae(Array(utf8("test"))))     shouldBe "0100000000000000" + "0400000000000000" + "74657374"
    hex(PasetoV4.pae(Array(utf8("a"), utf8("bb")))) shouldBe
      "0200000000000000" + "0100000000000000" + "61" + "0200000000000000" + "6262"
  }

  // Official v4.json "4-S-1" key material.
  private val seed4S1    = unhex("b4cbfb43df4ce210727d953e4a713307fa19bb7d9f85041438d9e11b942a3774")
  private val pub4S1     = unhex("1eb9dbbbbc047c03fd70604e0071f0987e16b28b757225c11f00415d0e20b1a2")
  private val payload4S1 = """{"data":"this is a signed message","exp":"2022-01-01T00:00:00+00:00"}"""

  test("4-S-1 seed derives the official Ed25519 public key") {
    hex(Ed25519.derivePublicKey(seed4S1)) shouldBe hex(pub4S1)
  }

  test("v4.public signs + verifies against the official public key (no footer)") {
    val token = PasetoV4.signPublic(seed4S1, utf8(payload4S1))
    token          should startWith("v4.public.")
    token.count(_ == '.') shouldBe 2                                   // v4 . public . body
    PasetoV4.verifyPublic(token, pub4S1).map(new String(_, UTF_8)) shouldBe Some(payload4S1)
    PasetoV4.verifyPublic(token, Ed25519.derivePublicKey(Array.fill[Byte](32)(9.toByte))) shouldBe None
  }

  test("v4.public footer is carried and authenticated") {
    val footer = utf8("""{"kid":"key-2024"}""")
    val token  = PasetoV4.signPublic(seed4S1, utf8(payload4S1), footer)
    token.count(_ == '.') shouldBe 3                                   // v4 . public . body . footer
    PasetoV4.verifyPublic(token, pub4S1).map(new String(_, UTF_8)) shouldBe Some(payload4S1)
    // corrupting the footer breaks the signature (footer is bound through PAE)
    PasetoV4.verifyPublic(token.dropRight(4) + "AAAA", pub4S1) shouldBe None
  }

  test("wrong version / purpose / tampered tokens are rejected, not thrown") {
    val token = PasetoV4.signPublic(seed4S1, utf8(payload4S1))
    PasetoV4.verifyPublic("v3.public." + token.drop(10), pub4S1) shouldBe None   // wrong version
    PasetoV4.verifyPublic("v4.local."  + token.drop(10), pub4S1) shouldBe None   // wrong purpose
    // flip a char well inside the body b64u (index 12) — changes real message/sig bytes, not padding bits
    val flipped = token.updated(12, if token(12) == 'A' then 'B' else 'A')
    PasetoV4.verifyPublic(flipped, pub4S1) shouldBe None
    PasetoV4.verifyPublic("garbage", pub4S1) shouldBe None
  }
