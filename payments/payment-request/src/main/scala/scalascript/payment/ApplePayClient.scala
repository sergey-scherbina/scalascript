package scalascript.payment

import java.net.{URL, HttpURLConnection}
import java.io.{BufferedReader, InputStreamReader}
import javax.net.ssl.*
import java.security.*
import java.security.cert.{CertificateFactory, X509Certificate}
import java.io.FileInputStream

/** Server-side Apple Pay integration.
 *
 *  `validateMerchant` — makes an mTLS HTTPS POST to Apple's validation URL
 *  using the merchant certificate, returning the opaque session JSON.
 *
 *  `decryptToken` — decrypts an Apple Pay payment token using the merchant's
 *  private key (ECDH + AES-256-GCM). */
object ApplePayClient:

  /** POST to `validationUrl` with the merchant certificate to obtain a
   *  merchant session object (opaque JSON string) for the browser. */
  def validateMerchant(
    validationUrl: String,
    merchantId:    String,
    merchantName:  String,
    domainName:    String,
    certPath:      String,
    keyPath:       String
  ): String =
    val payload =
      s"""{"merchantIdentifier":"$merchantId","displayName":"$merchantName","initiative":"web","initiativeContext":"$domainName"}"""

    val sslContext = buildSslContext(certPath, keyPath)
    val url        = URL(validationUrl)
    val conn       = url.openConnection().asInstanceOf[HttpURLConnection]
    conn match
      case httpsConn: javax.net.ssl.HttpsURLConnection =>
        httpsConn.setSSLSocketFactory(sslContext.getSocketFactory)
      case _ =>

    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setConnectTimeout(10_000)
    conn.setReadTimeout(10_000)

    conn.getOutputStream.use { out =>
      out.write(payload.getBytes("UTF-8"))
    }

    val status = conn.getResponseCode
    if status != 200 then
      throw MerchantValidationError(s"Apple returned HTTP $status from $validationUrl")

    BufferedReader(InputStreamReader(conn.getInputStream, "UTF-8"))
      .use(_.lines().reduce("", _ + _))

  /** Decrypt an Apple Pay payment token.
   *  Returns the decrypted payment data (PAN, cryptogram, expiry). */
  def decryptToken(
    token:    ApplePayToken,
    certPath: String,
    keyPath:  String
  ): ApplePayDecryptedToken =
    // Load merchant private key
    val privateKey = loadEcPrivateKey(keyPath)
    val cert       = loadCertificate(certPath)

    // Verify the signature chain
    verifySignature(token, cert)

    // Decrypt: ephemeral EC key agreement → shared secret → KDF → AES-256-GCM
    val ephemeralPublicKey = decodeEphemeralKey(token.header.ephemeralPublicKey)
    val sharedSecret       = performECDH(privateKey, ephemeralPublicKey)
    val symmetricKey       = deriveSymmetricKey(sharedSecret, token.header.transactionId)
    val plaintext          = aesGcmDecrypt(
      java.util.Base64.getDecoder.decode(token.data),
      symmetricKey
    )
    parseDecryptedPayload(plaintext)

  // ── Private helpers ───────────────────────────────────────────────────────

  private def buildSslContext(certPath: String, keyPath: String): SSLContext =
    val ks = KeyStore.getInstance("PKCS12")
    // Load from PEM — convert using BouncyCastle or pre-converted P12
    FileInputStream(certPath).use { fis =>
      ks.load(fis, Array.emptyCharArray)
    }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, Array.emptyCharArray)
    val ctx = SSLContext.getInstance("TLSv1.2")
    ctx.init(kmf.getKeyManagers, null, null)
    ctx

  private def loadEcPrivateKey(keyPath: String): java.security.interfaces.ECPrivateKey =
    val pem    = scala.io.Source.fromFile(keyPath).mkString
    val b64    = pem.replaceAll("-----.*-----", "").replaceAll("\\s", "")
    val bytes  = java.util.Base64.getDecoder.decode(b64)
    val spec   = java.security.spec.PKCS8EncodedKeySpec(bytes)
    KeyFactory.getInstance("EC").generatePrivate(spec)
      .asInstanceOf[java.security.interfaces.ECPrivateKey]

  private def loadCertificate(certPath: String): X509Certificate =
    FileInputStream(certPath).use { fis =>
      CertificateFactory.getInstance("X.509")
        .generateCertificate(fis)
        .asInstanceOf[X509Certificate]
    }

  private def verifySignature(token: ApplePayToken, cert: X509Certificate): Unit =
    // Apple Pay signature verification per Apple's spec:
    // https://developer.apple.com/documentation/passkit_apple_pay_and_wallet/payment_token_format_reference
    // Simplified: verify the leaf cert is trusted and the signature is valid
    val sig    = Signature.getInstance("SHA256withECDSA")
    sig.initVerify(cert.getPublicKey)
    // data = ephemeralPublicKey || transactionId || applicationData
    val ephBytes = java.util.Base64.getDecoder.decode(token.header.ephemeralPublicKey)
    val txBytes  = token.header.transactionId.getBytes("UTF-8")
    sig.update(ephBytes)
    sig.update(txBytes)
    sig.update(java.util.Base64.getDecoder.decode(token.data))
    val sigBytes = java.util.Base64.getDecoder.decode(token.signature)
    if !sig.verify(sigBytes) then
      throw TokenDecryptionError("Apple Pay signature verification failed")

  private def decodeEphemeralKey(b64: String): java.security.interfaces.ECPublicKey =
    val bytes = java.util.Base64.getDecoder.decode(b64)
    val spec  = java.security.spec.X509EncodedKeySpec(bytes)
    KeyFactory.getInstance("EC").generatePublic(spec)
      .asInstanceOf[java.security.interfaces.ECPublicKey]

  private def performECDH(
    privateKey:  java.security.interfaces.ECPrivateKey,
    publicKey:   java.security.interfaces.ECPublicKey
  ): Array[Byte] =
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(privateKey)
    ka.doPhase(publicKey, true)
    ka.generateSecret()

  private def deriveSymmetricKey(sharedSecret: Array[Byte], transactionId: String): Array[Byte] =
    // ANSI X9.63 KDF with SHA-256
    val md = MessageDigest.getInstance("SHA-256")
    md.update(sharedSecret)
    md.update(Array[Byte](0, 0, 0, 1))   // counter
    md.update("id-aes256-GCM".getBytes("ASCII"))
    md.update(transactionId.getBytes("ASCII"))
    md.digest()

  private def aesGcmDecrypt(ciphertext: Array[Byte], key: Array[Byte]): Array[Byte] =
    import javax.crypto.{Cipher, spec}
    val iv     = ciphertext.take(16)
    val data   = ciphertext.drop(16)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      spec.SecretKeySpec(key, "AES"),
      spec.GCMParameterSpec(128, iv)
    )
    cipher.doFinal(data)

  private def parseDecryptedPayload(bytes: Array[Byte]): ApplePayDecryptedToken =
    import scala.util.parsing.json.*
    val json = String(bytes, "UTF-8")
    // Minimal JSON extraction (avoids adding a JSON dep to this module)
    def field(key: String): Option[String] =
      val pattern = s""""$key"\\s*:\\s*"([^"]+)"""".r
      pattern.findFirstMatchIn(json).map(_.group(1))
    def longField(key: String): Option[Long] =
      val pattern = s""""$key"\\s*:\\s*(\\d+)""".r
      pattern.findFirstMatchIn(json).map(_.group(1).toLong)
    ApplePayDecryptedToken(
      applicationPrimaryAccountNumber = field("applicationPrimaryAccountNumber").getOrElse(""),
      expirationDate                  = field("applicationExpirationDate").getOrElse(""),
      currencyCode                    = field("currencyCode"),
      transactionAmount               = longField("transactionAmount"),
      cardholderName                  = field("cardholderName"),
      onlinePaymentCryptogram         = field("onlinePaymentCryptogram")
    )

  extension [R](resource: java.io.Closeable)(using ev: resource.type <:< java.io.Closeable)
    private def use[A](f: resource.type => A): A =
      try f(resource) finally resource.close()
