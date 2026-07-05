package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** HKDF-SHA256 (RFC 5869) pinned byte-exact to the RFC test vectors, on JVM and Scala.js. */
class HkdfSha256Test extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString

  test("RFC 5869 A.1 — basic SHA-256 (with salt + info)") {
    val ikm  = unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    val salt = unhex("000102030405060708090a0b0c")
    val info = unhex("f0f1f2f3f4f5f6f7f8f9")
    val prk  = HkdfSha256.extract(salt, ikm)
    hex(prk) shouldBe "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
    hex(HkdfSha256.expand(prk, info, 42)) shouldBe
      "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    hex(HkdfSha256.derive(salt, ikm, info, 42)) shouldBe
      "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
  }

  test("RFC 5869 A.3 — SHA-256 with zero-length salt + info") {
    val ikm = unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    val prk = HkdfSha256.extract(Array.emptyByteArray, ikm)
    hex(prk) shouldBe "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"
    hex(HkdfSha256.expand(prk, Array.emptyByteArray, 42)) shouldBe
      "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
  }
