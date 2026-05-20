package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AnyFunSuite

/** Cross-platform spec for [[WcKeyAgreement]] — X25519 ECDH + HKDF +
 *  sha256(symKey) topic + `wc:` pairing-URI parser. */
abstract class WcKeyAgreementTestBase extends AnyFunSuite:

  test("generateKeypair yields 32-byte halves and matches publicKeyFromPrivate") {
    val kp = WcKeyAgreement.generateKeypair()
    assert(kp.privateKey.length == 32)
    assert(kp.publicKey.length  == 32)
    assert(WcKeyAgreement.publicKeyFromPrivate(kp.privateKey).toSeq == kp.publicKey.toSeq)
  }

  test("two-party ECDH is symmetric: dh(a.priv, b.pub) == dh(b.priv, a.pub)") {
    val a = WcKeyAgreement.generateKeypair()
    val b = WcKeyAgreement.generateKeypair()
    val ab = WcKeyAgreement.deriveSharedSecret(a.privateKey, b.publicKey)
    val ba = WcKeyAgreement.deriveSharedSecret(b.privateKey, a.publicKey)
    assert(ab.length == 32)
    assert(ab.toSeq == ba.toSeq, "X25519 must be symmetric for the WC handshake to work")
  }

  test("deriveSymKey is deterministic + 32 bytes") {
    val sharedSecret = Array.fill[Byte](32)(0x42.toByte)
    val sk1 = WcKeyAgreement.deriveSymKey(sharedSecret)
    val sk2 = WcKeyAgreement.deriveSymKey(sharedSecret)
    assert(sk1.length == 32)
    assert(sk1.toSeq == sk2.toSeq)
  }

  test("deriveSymKey of distinct shared secrets gives distinct symKeys") {
    val sk1 = WcKeyAgreement.deriveSymKey(Array.fill[Byte](32)(0x11.toByte))
    val sk2 = WcKeyAgreement.deriveSymKey(Array.fill[Byte](32)(0x22.toByte))
    assert(sk1.toSeq != sk2.toSeq)
  }

  test("topicFromSymKey returns sha256(symKey)") {
    val symKey = Array.fill[Byte](32)(0x99.toByte)
    val topic  = WcKeyAgreement.topicFromSymKey(symKey)
    // Known SHA-256 of (0x99 ×32) — cross-checked via Python's hashlib.
    // Stays platform-neutral: no java.security imports in shared tests.
    val expectedHex = "af834b2357bae6ad7eccd35c0a050538af38b19023275f58d1f3b39e4d1a0435"
    assert(WcKeyAgreement.hexEncode(topic) == expectedHex)
  }

  test("parsePairingUri returns the symKey + topic + relay protocol") {
    val symKey  = Array.fill[Byte](32)(0xab.toByte)
    val topic   = WcKeyAgreement.topicFromSymKey(symKey)
    val symHex  = WcKeyAgreement.hexEncode(symKey)
    val topHex  = WcKeyAgreement.hexEncode(topic)
    val uri     = s"wc:$topHex@2?relay-protocol=irn&symKey=$symHex"
    val parsed  = WcKeyAgreement.parsePairingUri(uri)
    assert(parsed.isDefined)
    val p = parsed.get
    assert(p.symKey.toSeq        == symKey.toSeq)
    assert(p.topic.toSeq         == topic.toSeq)
    assert(p.relayProtocol       == "irn")
    assert(p.relayData.isEmpty)
  }

  test("parsePairingUri rejects topic ≠ sha256(symKey)") {
    val symHex = WcKeyAgreement.hexEncode(Array.fill[Byte](32)(0x01.toByte))
    val badTop = WcKeyAgreement.hexEncode(Array.fill[Byte](32)(0xff.toByte))
    val uri    = s"wc:$badTop@2?relay-protocol=irn&symKey=$symHex"
    assert(WcKeyAgreement.parsePairingUri(uri).isEmpty,
      "URI must be rejected when the topic doesn't match sha256(symKey)")
  }

  test("parsePairingUri rejects WC v1 (or unknown versions)") {
    val symHex = WcKeyAgreement.hexEncode(Array.fill[Byte](32)(0x01.toByte))
    val topHex = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(Array.fill[Byte](32)(0x01.toByte)))
    val uri    = s"wc:$topHex@1?relay-protocol=irn&symKey=$symHex"
    assert(WcKeyAgreement.parsePairingUri(uri).isEmpty)
  }
