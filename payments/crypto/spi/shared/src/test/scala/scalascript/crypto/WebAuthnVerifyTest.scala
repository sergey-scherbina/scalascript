package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** WebAuthn assertion verification core — COSE_Key parsing + signature checks for ES256 (P-256) and
 *  EdDSA (Ed25519), on JVM and Scala.js. The underlying P-256 / Ed25519 primitives are already pinned
 *  to BouncyCastle / RFC vectors; here we pin the WebAuthn `authenticatorData ‖ SHA-256(clientDataJSON)`
 *  construction and the COSE_Key decode. */
class WebAuthnVerifyTest extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  // 37-byte authenticatorData: 32-byte rpIdHash ‖ flags (0x05 = UP|UV) ‖ 4-byte signCount.
  private val authData        = unhex("49960de5880e8c687434170f6476605b8fe4aeb9a28632c7995cf3ba831d97630500000001")
  private val clientDataJSON  = utf8("""{"type":"webauthn.get","challenge":"aGVsbG8","origin":"https://example.com"}""")

  private def es256Cose(x: Array[Byte], y: Array[Byte]): Array[Byte] =
    Cbor.encode(Cbor.Map(IndexedSeq(
      Cbor.int(1) -> Cbor.int(2), Cbor.int(3) -> Cbor.int(-7), Cbor.int(-1) -> Cbor.int(1),
      Cbor.int(-2) -> Cbor.Bytes(x), Cbor.int(-3) -> Cbor.Bytes(y))))

  private def edCose(x: Array[Byte]): Array[Byte] =
    Cbor.encode(Cbor.Map(IndexedSeq(
      Cbor.int(1) -> Cbor.int(1), Cbor.int(3) -> Cbor.int(-8), Cbor.int(-1) -> Cbor.int(6),
      Cbor.int(-2) -> Cbor.Bytes(x))))

  private val p256Priv = unhex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721")
  private val p256Pub  = P256Ecdsa.derivePublicUncompressed(p256Priv)
  private val p256X    = p256Pub.slice(1, 33)
  private val p256Y    = p256Pub.slice(33, 65)
  private val edSeed   = unhex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
  private val edPub    = Ed25519.derivePublicKey(edSeed)

  test("parseCoseKey extracts EC2/P-256 (ES256) and OKP/Ed25519 (EdDSA) public keys") {
    WebAuthnVerify.parseCoseKey(es256Cose(p256X, p256Y)) match
      case Some(WebAuthnVerify.Es256Key(x, y)) => x shouldBe p256X; y shouldBe p256Y
      case other => fail(s"expected Es256Key, got $other")
    WebAuthnVerify.parseCoseKey(edCose(edPub)) match
      case Some(WebAuthnVerify.EdDsaKey(x)) => x shouldBe edPub
      case other => fail(s"expected EdDsaKey, got $other")
    WebAuthnVerify.parseCoseKey(unhex("a10102")) shouldBe None   // an EC2 map with no coords → unsupported
  }

  test("ES256 assertion verifies (DER sig over authData‖SHA256(clientData)) and rejects tamper / wrong key") {
    val cose       = es256Cose(p256X, p256Y)
    val signedData = authData ++ Sha256.digest(clientDataJSON)
    val sig        = P256Ecdsa.sign(p256Priv, Sha256.digest(signedData))    // WebAuthn ES256 = DER
    WebAuthnVerify.verifyAssertion(cose, authData, clientDataJSON, sig) shouldBe true
    // tampered clientDataJSON (e.g. swapped challenge/origin) → different signedData → reject
    WebAuthnVerify.verifyAssertion(cose, authData,
      utf8("""{"type":"webauthn.get","challenge":"EVIL","origin":"https://evil.com"}"""), sig) shouldBe false
    // tampered authenticatorData → reject
    val badAuth = authData.clone(); badAuth(0) = (badAuth(0) ^ 0x01).toByte
    WebAuthnVerify.verifyAssertion(cose, badAuth, clientDataJSON, sig) shouldBe false
    // a different credential public key → reject
    val other = P256Ecdsa.derivePublicUncompressed(
      unhex("0000000000000000000000000000000000000000000000000000000000000002"))
    WebAuthnVerify.verifyAssertion(es256Cose(other.slice(1, 33), other.slice(33, 65)),
      authData, clientDataJSON, sig) shouldBe false
    // malformed signature → false (not an exception)
    WebAuthnVerify.verifyAssertion(cose, authData, clientDataJSON, unhex("deadbeef")) shouldBe false
  }

  test("EdDSA assertion verifies (raw sig) and rejects tamper") {
    val cose       = edCose(edPub)
    val signedData = authData ++ Sha256.digest(clientDataJSON)
    val sig        = Ed25519.sign(edSeed, signedData)                        // raw 64-byte
    WebAuthnVerify.verifyAssertion(cose, authData, clientDataJSON, sig) shouldBe true
    WebAuthnVerify.verifyAssertion(cose, authData, utf8("""{"type":"webauthn.get"}"""), sig) shouldBe false
    WebAuthnVerify.verifyAssertion(edCose(Ed25519.derivePublicKey(unhex(
      "00000000000000000000000000000000000000000000000000000000000000aa"))), authData, clientDataJSON, sig) shouldBe false
  }
