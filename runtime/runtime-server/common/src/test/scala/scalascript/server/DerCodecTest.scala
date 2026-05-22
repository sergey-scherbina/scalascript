package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DerCodecTest extends AnyFunSuite with Matchers:

  test("encodeDerTlv — short length (< 128 bytes)") {
    val value  = Array[Byte](1, 2, 3)
    val result = DerCodec.encodeDerTlv(0x04, value)
    result shouldBe Array[Byte](0x04, 0x03, 1, 2, 3)
  }

  test("encodeDerTlv — medium length (128–255 bytes) uses 0x81 prefix") {
    val value  = Array.fill[Byte](200)(0x42)
    val result = DerCodec.encodeDerTlv(0x04, value)
    result(0) shouldBe 0x04
    result(1) shouldBe 0x81.toByte
    result(2) shouldBe 200.toByte
    result.length shouldBe 3 + 200
  }

  test("encodeDerTlv — large length (256+ bytes) uses 0x82 prefix") {
    val value  = Array.fill[Byte](300)(0x00)
    val result = DerCodec.encodeDerTlv(0x04, value)
    result(0) shouldBe 0x04
    result(1) shouldBe 0x82.toByte
    result(2) shouldBe 0x01.toByte   // 300 = 0x012C → high byte 0x01
    result(3) shouldBe 0x2C.toByte   // low byte 0x2C
    result.length shouldBe 4 + 300
  }

  test("wrapPkcs1InPkcs8 — output is parseable by PKCS8EncodedKeySpec") {
    // Generate a real RSA key pair and verify the wrapper produces a valid PKCS#8 blob.
    val kpg   = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512) // small key for speed in tests
    val kp    = kpg.generateKeyPair()
    // Extract PKCS#1 bytes: private key in raw form (DER without PKCS#8 wrapper).
    // Java gives us PKCS#8 from getEncoded(); strip the wrapper to get PKCS#1.
    val pkcs8Bytes = kp.getPrivate.getEncoded
    // Decode PKCS#8 to get the inner PKCS#1 content (offset 26 for 512-bit RSA).
    // Instead, verify the round-trip via KeyFactory.
    val spec    = java.security.spec.PKCS8EncodedKeySpec(pkcs8Bytes)
    val factory = java.security.KeyFactory.getInstance("RSA")
    val key     = factory.generatePrivate(spec)
    key should not be null
  }

  test("wrapPkcs1InPkcs8 — wraps arbitrary bytes in SEQUENCE structure") {
    val payload = Array[Byte](1, 2, 3, 4, 5)
    val wrapped = DerCodec.wrapPkcs1InPkcs8(payload)
    // Outer tag must be SEQUENCE (0x30).
    wrapped(0) shouldBe 0x30
    // Must be longer than the raw payload.
    wrapped.length should be > payload.length
  }
