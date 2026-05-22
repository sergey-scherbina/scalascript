package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AnyFunSuite

import scalascript.crypto.CryptoIntegrityException

/** Cross-platform spec for [[WcEnvelope]] — exercises both Type-0 and
 *  Type-1 frames, tamper-detection, and base64 round-trip.  Requires
 *  `CryptoBackend.get()` to resolve to the platform's backend (JVM:
 *  ServiceLoader; JS: `Register.install`).  Concrete sub-classes in
 *  `jvm/` / `js/` provide that setup. */
abstract class WcEnvelopeTestBase extends AnyFunSuite:

  private val symKey   = Array.fill[Byte](32)(0x42.toByte)
  private val msg      = "hello walletconnect".getBytes("UTF-8")

  test("Type 0 round-trip recovers the plaintext") {
    val sealed_   = WcEnvelope.sealType0(symKey, msg)
    val opened    = WcEnvelope.open(symKey, sealed_)
    assert(opened.plaintext.toSeq == msg.toSeq)
    assert(opened.senderPublicKey.isEmpty)
  }

  test("Type 0 framing: [0x00, iv(12), ct+tag(plaintext+16)]") {
    val iv      = Array.fill[Byte](12)(0x10.toByte)
    val sealed_ = WcEnvelope.sealType0(symKey, msg, ivOverride = Some(iv))
    assert(sealed_(0) == WcEnvelope.Type0, "first byte = type 0")
    assert(sealed_.slice(1, 13).toSeq == iv.toSeq, "next 12 bytes = iv")
    val ctLen = sealed_.length - 1 - 12
    assert(ctLen == msg.length + WcEnvelope.TagBytes,
      s"ciphertext should be plaintext + 16-byte tag, got $ctLen")
  }

  test("Type 1 round-trip preserves senderPublicKey + plaintext") {
    val sender  = Array.fill[Byte](32)(0xa1.toByte)
    val sealed_ = WcEnvelope.sealType1(symKey, sender, msg)
    val opened  = WcEnvelope.open(symKey, sealed_)
    assert(opened.plaintext.toSeq == msg.toSeq)
    assert(opened.senderPublicKey.exists(_.toSeq == sender.toSeq))
  }

  test("Type 1 framing: [0x01, sender(32), iv(12), ct+tag]") {
    val sender  = Array.fill[Byte](32)(0xa1.toByte)
    val iv      = Array.fill[Byte](12)(0x20.toByte)
    val sealed_ = WcEnvelope.sealType1(symKey, sender, msg, ivOverride = Some(iv))
    assert(sealed_(0) == WcEnvelope.Type1)
    assert(sealed_.slice(1, 33).toSeq == sender.toSeq)
    assert(sealed_.slice(33, 45).toSeq == iv.toSeq)
    val ctLen = sealed_.length - 1 - 32 - 12
    assert(ctLen == msg.length + WcEnvelope.TagBytes)
  }

  test("decryption fails when symKey differs") {
    val sealed_ = WcEnvelope.sealType0(symKey, msg)
    val badKey  = Array.fill[Byte](32)(0xff.toByte)
    intercept[CryptoIntegrityException] {
      WcEnvelope.open(badKey, sealed_)
    }
  }

  test("decryption fails when a ciphertext byte is tampered") {
    val sealed_ = WcEnvelope.sealType0(symKey, msg)
    // Flip a byte in the ciphertext (somewhere past type + iv).
    sealed_(1 + 12) = (sealed_(1 + 12) ^ 0xff).toByte
    intercept[CryptoIntegrityException] {
      WcEnvelope.open(symKey, sealed_)
    }
  }

  test("base64 round-trip preserves the envelope") {
    val sealed_ = WcEnvelope.sealType0(symKey, msg)
    val b64     = WcEnvelope.encodeBase64(sealed_)
    val back    = WcEnvelope.decodeBase64(b64)
    assert(back.toSeq == sealed_.toSeq)
  }

  test("open rejects an unknown envelope type byte") {
    val bogus = new Array[Byte](1 + 12 + 16)
    bogus(0) = 0x05
    val ex = intercept[IllegalArgumentException] {
      WcEnvelope.open(symKey, bogus)
    }
    assert(ex.getMessage.toLowerCase.contains("unknown envelope type"))
  }
