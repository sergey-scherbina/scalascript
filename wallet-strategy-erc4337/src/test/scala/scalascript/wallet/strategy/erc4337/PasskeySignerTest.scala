package scalascript.wallet.strategy.erc4337

import java.math.BigInteger
import java.security.{KeyPairGenerator, Signature}
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.abi.{AbiDecoder, AbiType}
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

/** Tests for the JVM side of the WebAuthn / Passkey signer.
 *
 *  We don't have a real authenticator in unit tests, so we inject a
 *  mock `assertChallenge` that:
 *    1) Builds a real clientDataJSON whose `challenge` field is the
 *       base64url encoding of the requested 32-byte challenge.
 *    2) Computes the WebAuthn digest sha256(authData || sha256(cdJson)).
 *    3) Signs that digest with JCA's "SHA256withECDSA" — yielding a real
 *       DER P-256 ECDSA signature, exactly like a passkey would.
 *
 *  The signer is then asked to package the assertion; we parse the
 *  blob back via the ABI decoder and check every field round-trips. */
class PasskeySignerTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  // ── P-256 keypair via JCA (mirrors what an authenticator holds) ──────
  private val keypair =
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec("secp256r1"))
    gen.generateKeyPair()

  private val jcaPriv = keypair.getPrivate
  private val jcaPub  = keypair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]
  private val pubXY: Array[Byte] =
    val w = jcaPub.getW
    val x = toUnsigned32(w.getAffineX)
    val y = toUnsigned32(w.getAffineY)
    x ++ y

  private val signerPubKey = PublicKey(Curve.P256, pubXY)

  private val sampleAuthData: Array[Byte] =
    // 32 B rpIdHash || 0x05 flags (UP + UV) || 4 B signCount = 0x00000001
    val rpIdHash = CryptoBackend.get().hash(HashAlgo.Sha256, "example.com".getBytes("UTF-8"))
    val signCount = Array[Byte](0, 0, 0, 1)
    rpIdHash ++ Array[Byte](0x05) ++ signCount

  /** A deterministic "authenticator": signs whatever digest WebAuthn
   *  would over the requested challenge, with the JCA keypair. */
  private def mockAuthenticator(challenge: Array[Byte]): Future[WebAuthnAssertion] =
    Future {
      val challengeB64 = Base64.getUrlEncoder.withoutPadding.encodeToString(challenge)
      val cdJson = s"""{"type":"webauthn.get","challenge":"$challengeB64","origin":"https://example.com","crossOrigin":false}""".getBytes("UTF-8")
      // WebAuthn signs authenticatorData || sha256(clientDataJSON). For
      // SHA256withECDSA the signer applies SHA-256 itself, so we feed
      // the concatenation directly.
      val cdHash = CryptoBackend.get().hash(HashAlgo.Sha256, cdJson)
      val signedData = sampleAuthData ++ cdHash
      val ecdsa = Signature.getInstance("SHA256withECDSA")
      ecdsa.initSign(jcaPriv)
      ecdsa.update(signedData)
      val derSig = ecdsa.sign()
      WebAuthnAssertion(sampleAuthData, cdJson, derSig)
    }

  // ── tests ────────────────────────────────────────────────────────────

  test("sign(challenge, HashAlgo.None) returns an ABI-encoded WebAuthn auth blob") {
    val signer    = new PasskeySigner(signerPubKey, mockAuthenticator)
    val challenge = Array.tabulate[Byte](32)(i => (i + 1).toByte)
    val blob      = Await.result(signer.sign(challenge, HashAlgo.None), 5.seconds)

    // Decode the ABI tuple back out.
    val types = Seq(
      AbiType.Bytes,
      AbiType.Bytes,
      AbiType.FixedBytes(32),
      AbiType.FixedBytes(32),
      AbiType.UInt(256),
      AbiType.UInt(256),
    )
    val decoded = AbiDecoder.decodeTuple(types, blob)
    assert(decoded.size == 6)

    val auth = decoded(0).asInstanceOf[scalascript.blockchain.evm.abi.AbiValue.Bytes].value
    val cdj  = decoded(1).asInstanceOf[scalascript.blockchain.evm.abi.AbiValue.Bytes].value
    val rBz  = decoded(2).asInstanceOf[scalascript.blockchain.evm.abi.AbiValue.FixedBytes].value
    val sBz  = decoded(3).asInstanceOf[scalascript.blockchain.evm.abi.AbiValue.FixedBytes].value
    val chIdx = decoded(4).asInstanceOf[scalascript.blockchain.evm.abi.AbiValue.UInt].value
    val tyIdx = decoded(5).asInstanceOf[scalascript.blockchain.evm.abi.AbiValue.UInt].value

    assert(auth.toSeq == sampleAuthData.toSeq, "authenticatorData round-trips")
    assert(rBz.length == 32 && sBz.length == 32)

    // clientDataJSON's challenge must encode the original 32-byte challenge.
    val embeddedChallenge = PasskeyAssertion.clientDataChallenge(cdj)
    assert(embeddedChallenge.toSeq == challenge.toSeq)

    // challengeIndex / typeIndex point at the right substrings.
    val asStr = String(cdj, "UTF-8")
    assert(asStr.substring(chIdx.toInt).startsWith("\"challenge\""))
    assert(asStr.substring(tyIdx.toInt).startsWith("\"type\""))

    // (r, s) must verify against the same digest the authenticator signed.
    val digest = PasskeyAssertion.digestForVerification(sampleAuthData, cdj)
    val rawSig = rBz ++ sBz
    val ok = CryptoBackend.get().verify(Curve.P256, pubXY, digest, rawSig, HashAlgo.None)
    assert(ok, "packed (r, s) must verify under the credential's public key")

    // (r, s) must be low-s.
    val sBig = BigInteger(1, sBz)
    val halfN = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16).shiftRight(1)
    assert(sBig.compareTo(halfN) <= 0, "s must be low-s normalised")
  }

  test("sign rejects non-None hash algorithms (smart contract digests itself)") {
    val signer = new PasskeySigner(signerPubKey, mockAuthenticator)
    val challenge = Array.fill[Byte](32)(0)
    // require() throws synchronously
    intercept[IllegalArgumentException] {
      signer.sign(challenge, HashAlgo.Sha256)
    }
  }

  test("sign refuses an assertion whose embedded challenge != requested challenge") {
    // Mock that returns an assertion built for a *different* challenge.
    val tamperedAuth: Array[Byte] => Future[WebAuthnAssertion] = _ => Future {
      val wrong = Array.fill[Byte](32)(0x42)
      val challengeB64 = Base64.getUrlEncoder.withoutPadding.encodeToString(wrong)
      val cdJson = s"""{"type":"webauthn.get","challenge":"$challengeB64","origin":"https://example.com"}""".getBytes("UTF-8")
      val cdHash = CryptoBackend.get().hash(HashAlgo.Sha256, cdJson)
      val signedData = sampleAuthData ++ cdHash
      val ecdsa = Signature.getInstance("SHA256withECDSA")
      ecdsa.initSign(jcaPriv)
      ecdsa.update(signedData)
      WebAuthnAssertion(sampleAuthData, cdJson, ecdsa.sign())
    }
    val signer = new PasskeySigner(signerPubKey, tamperedAuth)
    val challenge = Array.fill[Byte](32)(0x01)
    val ex = intercept[IllegalStateException] {
      Await.result(signer.sign(challenge, HashAlgo.None), 5.seconds)
    }
    assert(ex.getMessage.contains("challenge"))
  }

  test("derToRawLowS round-trips a known DER signature with low-s normalisation") {
    val signer = new PasskeySigner(signerPubKey, mockAuthenticator)
    // Sign a fixed digest several times — JCA ECDSA is non-deterministic,
    // so we just need *some* valid DER. Verify (r, s) recovers the right
    // numbers and that s is low.
    val digest = Array.tabulate[Byte](32)(i => (i + 5).toByte)
    val ecdsa = Signature.getInstance("NONEwithECDSA")
    ecdsa.initSign(jcaPriv)
    ecdsa.update(digest)
    val der = ecdsa.sign()

    val (rBz, sBz) = signer.derToRawLowS(der)
    assert(rBz.length == 32 && sBz.length == 32)

    // Verify (r, s) against the same digest. We may have flipped s for
    // low-s; the BC backend's P-256 verify accepts low-s pairs.
    val ok = CryptoBackend.get().verify(Curve.P256, pubXY, digest, rBz ++ sBz, HashAlgo.None)
    assert(ok, "DER → raw → verify round-trip must succeed under low-s")

    val sBig = BigInteger(1, sBz)
    val halfN = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16).shiftRight(1)
    assert(sBig.compareTo(halfN) <= 0, "s must be low-s normalised")
  }

  test("derToRawLowS rejects malformed DER") {
    val signer = new PasskeySigner(signerPubKey, mockAuthenticator)
    intercept[IllegalArgumentException] {
      signer.derToRawLowS(Array[Byte](0x42, 0x00))
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private def toUnsigned32(x: BigInteger): Array[Byte] =
    val raw = x.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then
      val padded = new Array[Byte](32)
      System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length)
      padded
    else throw new IllegalArgumentException("too large")
