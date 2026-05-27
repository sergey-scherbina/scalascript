package scalascript.payment

import java.security.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*

/** Server-side Google Pay token decryption (ECv2 protocol).
 *
 *  Reference: https://developers.google.com/pay/api/web/guides/resources/payment-data-cryptography */
object GooglePayDecryptor:

  // Google Pay root signing keys (ECv2) — hardcoded per spec
  private val GoogleRootSigningKeys: List[String] = List(
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/1+3HBVSbdv+j7NaArdgMyoSAM43yRydzqdg1TxodSzA96Dj4Mc1EiKroxxunavVIvdxGnJeFViTzFvzFRxyCw=="
  )

  /** Verify the signature chain and decrypt the Google Pay token.
   *  Returns the decrypted card details. */
  def decrypt(
    token:         GooglePayToken,
    privateKeyPem: String,
    recipientId:   String          // "merchant:<merchantId>"
  ): GooglePayDecryptedCard =
    verifyIntermediateKey(token)
    verifyMessageSignature(token, recipientId)
    val signedMsg = parseSignedMessage(token.signedMessage)
    val merchantKey = loadEcPrivateKey(privateKeyPem)
    val sharedSecret = performECDH(merchantKey, decodePublicKey(signedMsg.ephemeralPublicKey))
    val (encKey, macKey) = deriveKeys(sharedSecret, token.signedMessage.getBytes("UTF-8"), recipientId)
    verifyHmac(signedMsg.encryptedMessage, signedMsg.tag, macKey)
    val plaintext = aesDecrypt(
      java.util.Base64.getDecoder.decode(signedMsg.encryptedMessage),
      encKey
    )
    parseDecryptedCard(String(plaintext, "UTF-8"))

  // ── Internal types ────────────────────────────────────────────────────────

  private case class SignedMessage(
    encryptedMessage:  String,
    ephemeralPublicKey: String,
    tag:               String
  )

  // ── Verification ──────────────────────────────────────────────────────────

  private def verifyIntermediateKey(token: GooglePayToken): Unit =
    val _ = token.intermediateSigningKey.signedKey.getBytes("UTF-8")
    val verified = GoogleRootSigningKeys.exists { rootKeyB64 =>
      try
        val rootKey = decodePublicKey(rootKeyB64)
        token.intermediateSigningKey.signatures.exists { sigB64 =>
          try
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(rootKey)
            sig.update(s""""ECv2"${token.intermediateSigningKey.signedKey}""".getBytes("UTF-8"))
            sig.verify(java.util.Base64.getDecoder.decode(sigB64))
          catch case _: Exception => false
        }
      catch case _: Exception => false
    }
    if !verified then
      throw TokenDecryptionError("Google Pay intermediate signing key verification failed")

  private def verifyMessageSignature(token: GooglePayToken, recipientId: String): Unit =
    val intermediateKeyJson = token.intermediateSigningKey.signedKey
    val intermediateKey     = extractIntermediatePublicKey(intermediateKeyJson)
    val signedData =
      s""""ECv2""""                  +
      s""""$recipientId""""          +
      s""""${token.signedMessage}""""
    val sig = Signature.getInstance("SHA256withECDSA")
    sig.initVerify(intermediateKey)
    sig.update(signedData.getBytes("UTF-8"))
    if !sig.verify(java.util.Base64.getDecoder.decode(token.signature)) then
      throw TokenDecryptionError("Google Pay message signature verification failed")

  private def verifyHmac(encryptedMessage: String, tag: String, macKey: Array[Byte]): Unit =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(macKey, "HmacSHA256"))
    val computed = mac.doFinal(encryptedMessage.getBytes("UTF-8"))
    val expected = java.util.Base64.getDecoder.decode(tag)
    if !MessageDigest.isEqual(computed, expected) then
      throw TokenDecryptionError("Google Pay HMAC verification failed")

  // ── Key derivation ────────────────────────────────────────────────────────

  private def deriveKeys(
    sharedSecret:  Array[Byte],
    signedMessage: Array[Byte],
    recipientId:   String
  ): (Array[Byte], Array[Byte]) =
    // HKDF-SHA256 with info = "Google" || recipientId || signedMessage
    val info   = "Google".getBytes("UTF-8") ++ recipientId.getBytes("UTF-8") ++ signedMessage
    val prk    = hkdfExtract(Array.fill(32)(0), sharedSecret)
    val okm    = hkdfExpand(prk, info, 64)
    (okm.take(32), okm.drop(32))   // AES-256 key, HMAC-SHA256 key

  private def hkdfExtract(salt: Array[Byte], ikm: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    mac.doFinal(ikm)

  private def hkdfExpand(prk: Array[Byte], info: Array[Byte], length: Int): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    var t       = Array.emptyByteArray
    var okm     = Array.emptyByteArray
    var counter = 1.toByte
    while okm.length < length do
      mac.reset()
      mac.update(t)
      mac.update(info)
      mac.update(counter)
      t = mac.doFinal()
      okm = okm ++ t
      counter = (counter + 1).toByte
    okm.take(length)

  // ── Crypto primitives ─────────────────────────────────────────────────────

  private def performECDH(
    privateKey: java.security.interfaces.ECPrivateKey,
    publicKey:  java.security.interfaces.ECPublicKey
  ): Array[Byte] =
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(privateKey)
    ka.doPhase(publicKey, true)
    ka.generateSecret()

  private def aesDecrypt(ciphertext: Array[Byte], key: Array[Byte]): Array[Byte] =
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(Array.fill(16)(0)))
    cipher.doFinal(ciphertext)

  private def loadEcPrivateKey(pem: String): java.security.interfaces.ECPrivateKey =
    val b64   = pem.replaceAll("-----.*-----", "").replaceAll("\\s", "")
    val bytes = java.util.Base64.getDecoder.decode(b64)
    KeyFactory.getInstance("EC")
      .generatePrivate(PKCS8EncodedKeySpec(bytes))
      .asInstanceOf[java.security.interfaces.ECPrivateKey]

  private def decodePublicKey(b64: String): java.security.interfaces.ECPublicKey =
    val bytes = java.util.Base64.getDecoder.decode(b64)
    KeyFactory.getInstance("EC")
      .generatePublic(X509EncodedKeySpec(bytes))
      .asInstanceOf[java.security.interfaces.ECPublicKey]

  private def extractIntermediatePublicKey(signedKeyJson: String): java.security.interfaces.ECPublicKey =
    val pattern = """"keyValue"\s*:\s*"([^"]+)"""".r
    val b64 = pattern.findFirstMatchIn(signedKeyJson)
      .map(_.group(1))
      .getOrElse(throw TokenDecryptionError("Cannot extract keyValue from intermediate signing key"))
    decodePublicKey(b64)

  // ── Payload parsing ───────────────────────────────────────────────────────

  private def parseSignedMessage(json: String): SignedMessage =
    def field(key: String): String =
      val pattern = s""""$key"\\s*:\\s*"([^"]+)"""".r
      pattern.findFirstMatchIn(json).map(_.group(1))
        .getOrElse(throw TokenDecryptionError(s"Missing field '$key' in signedMessage"))
    SignedMessage(
      encryptedMessage   = field("encryptedMessage"),
      ephemeralPublicKey = field("ephemeralPublicKey"),
      tag                = field("tag")
    )

  private def parseDecryptedCard(json: String): GooglePayDecryptedCard =
    def field(key: String): Option[String] =
      val pattern = s""""$key"\\s*:\\s*"([^"]+)"""".r
      pattern.findFirstMatchIn(json).map(_.group(1))
    GooglePayDecryptedCard(
      pan          = field("pan").getOrElse(""),
      expiryMonth  = field("expirationMonth").getOrElse(""),
      expiryYear   = field("expirationYear").getOrElse(""),
      authMethod   = field("authMethod").getOrElse("PAN_ONLY"),
      cryptogram   = field("3dsCryptogram"),
      eciIndicator = field("eciIndicator")
    )
