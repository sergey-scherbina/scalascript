package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** Portable NIST P-256 group + ECDSA — self-contained anchors (JVM + Scala.js). The byte-for-byte
 *  correctness gate vs the BouncyCastle P-256 backend lives in the bouncycastle module; here we pin
 *  a base-point vector, ECDSA round-trips, and the fixed-length signature encoding. */
class P256Test extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  private val one  = unhex("0000000000000000000000000000000000000000000000000000000000000001")
  private val priv = unhex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721")
  private val pub  = P256Ecdsa.derivePublicCompressed(priv)

  test("private key 1 derives the base point G (03‖Gx, since Gy is odd)") {
    hex(P256Ecdsa.derivePublicCompressed(one)) shouldBe
      "03" + "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
    // compressed → decode → uncompressed exposes Gx‖Gy
    hex(P256Ecdsa.derivePublicUncompressed(one)) shouldBe
      "04" + "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
             "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
  }

  test("P-256 ECDSA signs + verifies; RFC-6979 is deterministic") {
    val hash = Sha256.digest(utf8("ES256 message"))
    val a = P256Ecdsa.sign(priv, hash)
    val b = P256Ecdsa.sign(priv, hash)
    a shouldBe b                                                      // deterministic
    P256Ecdsa.verify(pub, hash, a) shouldBe true
    // tampered hash / wrong key must not verify
    P256Ecdsa.verify(pub, Sha256.digest(utf8("other message")), a) shouldBe false
    P256Ecdsa.verify(P256Ecdsa.derivePublicCompressed(unhex(
      "0000000000000000000000000000000000000000000000000000000000000002")), hash, a) shouldBe false
  }

  test("derToRaw / rawToDer preserve the P-256 signature (64-byte R‖S ↔ DER)") {
    val hash = Sha256.digest(utf8("fixed-length"))
    val der  = P256Ecdsa.sign(priv, hash)
    val raw  = P256Ecdsa.derToRaw(der)
    raw.length shouldBe 64
    P256Ecdsa.rawToDer(raw) shouldBe der
    P256Ecdsa.verify(pub, hash, P256Ecdsa.rawToDer(raw)) shouldBe true
  }
