package ssc.plugin.crypto

import java.nio.charset.StandardCharsets.UTF_8

private[crypto] object NativeCrypto:
  private val GcmIvLength = 12
  private val GcmTagBits = 128
  private val CbcIvLength = 16

  def encode(bytes: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(bytes)

  def decode(value: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(value)

  def encodeUrl(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  def decodeUrl(value: String): Array[Byte] =
    java.util.Base64.getUrlDecoder.decode(value)

  def generateAesKey(): String =
    val generator = javax.crypto.KeyGenerator.getInstance("AES")
    generator.init(256)
    encode(generator.generateKey().getEncoded)

  def generateAesIv(): String =
    val iv = new Array[Byte](CbcIvLength)
    java.security.SecureRandom().nextBytes(iv)
    encode(iv)

  def aesGcmEncrypt(keyBase64: String, plain: Array[Byte]): String =
    try
      val key = javax.crypto.spec.SecretKeySpec(decode(keyBase64), "AES")
      val iv = new Array[Byte](GcmIvLength)
      java.security.SecureRandom().nextBytes(iv)
      val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
        javax.crypto.spec.GCMParameterSpec(GcmTagBits, iv))
      encode(iv ++ cipher.doFinal(plain))
    catch case error: Throwable =>
      throw new RuntimeException(s"aesGcmEncrypt: ${error.getMessage}")

  def aesGcmDecrypt(keyBase64: String, payloadBase64: String): Array[Byte] =
    try
      val key = javax.crypto.spec.SecretKeySpec(decode(keyBase64), "AES")
      val all = decode(payloadBase64)
      if all.length < GcmIvLength + GcmTagBits / 8 then
        throw new RuntimeException("payload shorter than iv+tag")
      val iv = java.util.Arrays.copyOfRange(all, 0, GcmIvLength)
      val ciphertext = java.util.Arrays.copyOfRange(all, GcmIvLength, all.length)
      val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
        javax.crypto.spec.GCMParameterSpec(GcmTagBits, iv))
      cipher.doFinal(ciphertext)
    catch case error: Throwable =>
      throw new RuntimeException(s"aesGcmDecrypt: ${error.getMessage}")

  private def aesCbcCipher(mode: Int, keyBase64: String, ivBase64: String): javax.crypto.Cipher =
    val key = javax.crypto.spec.SecretKeySpec(decode(keyBase64), "AES")
    val iv = decode(ivBase64)
    if iv.length != CbcIvLength then
      throw new RuntimeException(s"iv must be $CbcIvLength bytes, got ${iv.length}")
    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(mode, key, javax.crypto.spec.IvParameterSpec(iv))
    cipher

  def aesCbcEncrypt(keyBase64: String, ivBase64: String, plain: Array[Byte]): String =
    try encode(aesCbcCipher(javax.crypto.Cipher.ENCRYPT_MODE, keyBase64, ivBase64).doFinal(plain))
    catch case error: Throwable =>
      throw new RuntimeException(s"aesCbcEncrypt: ${error.getMessage}")

  def aesCbcDecrypt(keyBase64: String, ivBase64: String, ciphertextBase64: String): Array[Byte] =
    try aesCbcCipher(javax.crypto.Cipher.DECRYPT_MODE, keyBase64, ivBase64)
      .doFinal(decode(ciphertextBase64))
    catch case error: Throwable =>
      throw new RuntimeException(s"aesCbcDecrypt: ${error.getMessage}")

  def rsaOaepEncrypt(publicKeyBase64: String, plain: Array[Byte]): String =
    try
      val key = java.security.KeyFactory.getInstance("RSA").generatePublic(
        java.security.spec.X509EncodedKeySpec(decode(publicKeyBase64)))
      val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
      val parameters = javax.crypto.spec.OAEPParameterSpec(
        "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
        javax.crypto.spec.PSource.PSpecified.DEFAULT)
      cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, parameters)
      encode(cipher.doFinal(plain))
    catch case error: Throwable =>
      throw new RuntimeException(s"rsaOaepEncrypt: ${error.getMessage}")

  def x509PublicKey(certificateBase64OrPem: String): String =
    try
      val encoded =
        if certificateBase64OrPem.contains("BEGIN CERTIFICATE") then
          certificateBase64OrPem
            .replaceAll("-----BEGIN CERTIFICATE-----", "")
            .replaceAll("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "")
        else certificateBase64OrPem.replaceAll("\\s", "")
      val factory = java.security.cert.CertificateFactory.getInstance("X.509")
      val certificate = factory.generateCertificate(java.io.ByteArrayInputStream(decode(encoded)))
      encode(certificate.getPublicKey.getEncoded)
    catch case error: Throwable =>
      throw new RuntimeException(s"x509PublicKey: ${error.getMessage}")

  private def ed25519PublicKey(bytes: Array[Byte]): java.security.PublicKey =
    val encoded =
      if bytes.length == 32 then
        Array(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
          .map(_.toByte) ++ bytes
      else bytes
    java.security.KeyFactory.getInstance("Ed25519")
      .generatePublic(java.security.spec.X509EncodedKeySpec(encoded))

  private def ed25519PrivateKey(bytes: Array[Byte]): java.security.PrivateKey =
    val encoded =
      if bytes.length == 32 then
        Array(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03,
          0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20).map(_.toByte) ++ bytes
      else bytes
    java.security.KeyFactory.getInstance("Ed25519")
      .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(encoded))

  def verifyEd25519(
      publicKey: String,
      message: String,
      signature: String,
      decoder: String => Array[Byte]): Boolean =
    try
      val verifier = java.security.Signature.getInstance("Ed25519")
      verifier.initVerify(ed25519PublicKey(decoder(publicKey)))
      verifier.update(message.getBytes(UTF_8))
      verifier.verify(decoder(signature))
    catch case _: Throwable => false

  def signEd25519(
      privateKey: String,
      message: String,
      decoder: String => Array[Byte],
      encoder: Array[Byte] => String): String =
    val signer = java.security.Signature.getInstance("Ed25519")
    signer.initSign(ed25519PrivateKey(decoder(privateKey)))
    signer.update(message.getBytes(UTF_8))
    encoder(signer.sign())

  private def rsaSignature(scheme: String): java.security.Signature =
    if scheme == "PSS" then
      val signature = java.security.Signature.getInstance("RSASSA-PSS")
      signature.setParameter(java.security.spec.PSSParameterSpec(
        "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1))
      signature
    else java.security.Signature.getInstance("SHA256withRSA")

  def signRsaSha256(privateKeyBase64: String, message: String, scheme: String): String =
    val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(
      java.security.spec.PKCS8EncodedKeySpec(decode(privateKeyBase64)))
    val signer = rsaSignature(scheme)
    signer.initSign(key)
    signer.update(message.getBytes(UTF_8))
    encode(signer.sign())

  def verifyRsaSha256(
      publicKeyBase64: String,
      message: String,
      signatureBase64: String,
      scheme: String): Boolean =
    try
      val key = java.security.KeyFactory.getInstance("RSA").generatePublic(
        java.security.spec.X509EncodedKeySpec(decode(publicKeyBase64)))
      val verifier = rsaSignature(scheme)
      verifier.initVerify(key)
      verifier.update(message.getBytes(UTF_8))
      verifier.verify(decode(signatureBase64))
    catch case _: Throwable => false
