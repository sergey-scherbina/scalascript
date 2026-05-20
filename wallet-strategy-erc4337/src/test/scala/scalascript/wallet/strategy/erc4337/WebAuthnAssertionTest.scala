package scalascript.wallet.strategy.erc4337

import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scalascript.crypto.{CryptoBackend, HashAlgo}

class WebAuthnAssertionTest extends AnyFunSuite:

  private val be     = CryptoBackend.get()
  private val urlEnc = Base64.getUrlEncoder.withoutPadding

  test("clientDataChallenge extracts the base64url-decoded challenge field") {
    val challengeBytes = Array.tabulate[Byte](32)(i => (i * 7).toByte)
    val challengeB64   = urlEnc.encodeToString(challengeBytes)
    val cdJson = s"""{"type":"webauthn.get","challenge":"$challengeB64","origin":"https://example.com","crossOrigin":false}"""
    val extracted = PasskeyAssertion.clientDataChallenge(cdJson.getBytes("UTF-8"))
    assert(extracted.toSeq == challengeBytes.toSeq,
      "clientDataChallenge must base64url-decode the challenge field exactly")
  }

  test("clientDataChallenge tolerates whitespace and field reordering") {
    val challengeBytes = Array[Byte](1, 2, 3, 4, 5)
    val challengeB64   = urlEnc.encodeToString(challengeBytes)
    val cdJson = s"""{ "origin" : "https://x" , "type" : "webauthn.get" , "challenge"  :  "$challengeB64" }"""
    val extracted = PasskeyAssertion.clientDataChallenge(cdJson.getBytes("UTF-8"))
    assert(extracted.toSeq == challengeBytes.toSeq)
  }

  test("clientDataChallenge rejects clientDataJSON missing the challenge field") {
    val cdJson = """{"type":"webauthn.get","origin":"https://x"}"""
    val ex = intercept[IllegalArgumentException] {
      PasskeyAssertion.clientDataChallenge(cdJson.getBytes("UTF-8"))
    }
    assert(ex.getMessage.contains("challenge"))
  }

  test("digestForVerification = sha256(authData || sha256(clientDataJson))") {
    // Hand-crafted authData (37 B minimum) + a clientDataJSON. Compute
    // the digest manually and compare.
    val authData = Array.tabulate[Byte](64)(i => (i + 0x10).toByte)
    val cdJson   = """{"type":"webauthn.get","challenge":"YWJj","origin":"https://x"}""".getBytes("UTF-8")

    val cdHash = be.hash(HashAlgo.Sha256, cdJson)
    val concat = authData ++ cdHash
    val expected = be.hash(HashAlgo.Sha256, concat)

    val actual = PasskeyAssertion.digestForVerification(authData, cdJson)
    assert(actual.toSeq == expected.toSeq,
      "digestForVerification must equal sha256(authData || sha256(clientDataJson))")
    assert(actual.length == 32)
  }

  test("base64UrlDecode accepts padded and unpadded forms") {
    val payload = Array[Byte](0x10, 0x20, 0x30, 0x40, 0x50)
    val padded   = Base64.getUrlEncoder.encodeToString(payload)
    val unpadded = Base64.getUrlEncoder.withoutPadding.encodeToString(payload)
    assert(PasskeyAssertion.base64UrlDecode(padded).toSeq   == payload.toSeq)
    assert(PasskeyAssertion.base64UrlDecode(unpadded).toSeq == payload.toSeq)
  }

  test("fieldByteOffset locates the field key inside the JSON byte buffer") {
    val cdJson = """{"type":"webauthn.get","challenge":"AAA","origin":"x"}"""
    val typeIdx = PasskeyAssertion.fieldByteOffset(cdJson.getBytes("UTF-8"), "type")
    val chIdx   = PasskeyAssertion.fieldByteOffset(cdJson.getBytes("UTF-8"), "challenge")
    assert(typeIdx >= 0 && cdJson.substring(typeIdx).startsWith("\"type\""))
    assert(chIdx   >= 0 && cdJson.substring(chIdx).startsWith("\"challenge\""))
    assert(PasskeyAssertion.fieldByteOffset(cdJson.getBytes("UTF-8"), "missing") == -1)
  }
