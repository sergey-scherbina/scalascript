package scalascript.compiler.plugin.crypto

import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object CryptoIntrinsics:

  private def native(f: List[Any] => Value): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  // ── encryption helpers (JVM JCE) ─────────────────────────────────────────
  // AES-256-GCM framing: base64( iv[12] ++ ciphertext ++ tag[16] ).  The GCM
  // tag is appended to the ciphertext by JCE's doFinal.  RSA uses OAEP with
  // SHA-256 for BOTH the digest and MGF1.  Crypto failures throw with a clear
  // message rather than returning a silent wrong result.

  private def b64e(bytes: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(bytes)
  private def b64d(s: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(s)

  private val GcmIvLen   = 12
  private val GcmTagBits = 128

  private def aesGcmEncryptRaw(keyB64: String, plain: Array[Byte]): String =
    try
      val key = new javax.crypto.spec.SecretKeySpec(b64d(keyB64), "AES")
      val iv  = new Array[Byte](GcmIvLen)
      java.security.SecureRandom().nextBytes(iv)
      val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
        new javax.crypto.spec.GCMParameterSpec(GcmTagBits, iv))
      b64e(iv ++ cipher.doFinal(plain))
    catch case e: Throwable => throw new RuntimeException(s"aesGcmEncrypt: ${e.getMessage}")

  private def aesGcmDecryptRaw(keyB64: String, payloadB64: String): Array[Byte] =
    try
      val key = new javax.crypto.spec.SecretKeySpec(b64d(keyB64), "AES")
      val all = b64d(payloadB64)
      if all.length < GcmIvLen + GcmTagBits / 8 then
        throw new RuntimeException("payload shorter than iv+tag")
      val iv = java.util.Arrays.copyOfRange(all, 0, GcmIvLen)
      val ct = java.util.Arrays.copyOfRange(all, GcmIvLen, all.length)
      val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
        new javax.crypto.spec.GCMParameterSpec(GcmTagBits, iv))
      cipher.doFinal(ct)
    catch case e: Throwable => throw new RuntimeException(s"aesGcmDecrypt: ${e.getMessage}")

  private def rsaOaepEncryptRaw(pubKeyB64: String, plain: Array[Byte]): String =
    try
      val spec   = new java.security.spec.X509EncodedKeySpec(b64d(pubKeyB64))
      val pubKey = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
      val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
      val oaep   = new javax.crypto.spec.OAEPParameterSpec(
        "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
        javax.crypto.spec.PSource.PSpecified.DEFAULT)
      cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pubKey, oaep)
      b64e(cipher.doFinal(plain))
    catch case e: Throwable => throw new RuntimeException(s"rsaOaepEncrypt: ${e.getMessage}")

  private def x509PublicKeyRaw(certB64OrPem: String): String =
    try
      val der =
        if certB64OrPem.contains("BEGIN CERTIFICATE") then
          b64d(certB64OrPem
            .replaceAll("-----BEGIN CERTIFICATE-----", "")
            .replaceAll("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", ""))
        else b64d(certB64OrPem.replaceAll("\\s", ""))
      val cf   = java.security.cert.CertificateFactory.getInstance("X.509")
      val cert = cf.generateCertificate(new java.io.ByteArrayInputStream(der))
      b64e(cert.getPublicKey.getEncoded)
    catch case e: Throwable => throw new RuntimeException(s"x509PublicKey: ${e.getMessage}")

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("sha256") -> native {
      case List(s: String) =>
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Value.StringV(md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString)
      case _ =>
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Value.StringV(md.digest(Array.emptyByteArray).map("%02x".format(_)).mkString)
    },

    QualifiedName("hmacSha256") -> native {
      case List(key: String, data: String) =>
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"))
        Value.StringV(mac.doFinal(data.getBytes("UTF-8")).map("%02x".format(_)).mkString)
      case _ => Value.StringV("")
    },

    QualifiedName("base64Encode") -> native {
      case List(s: String) =>
        Value.StringV(java.util.Base64.getEncoder.encodeToString(s.getBytes("UTF-8")))
      case _ => Value.StringV("")
    },

    QualifiedName("base64Decode") -> native {
      case List(s: String) =>
        try Value.StringV(String(java.util.Base64.getDecoder.decode(s), "UTF-8"))
        catch case _: Throwable => Value.StringV("")
      case _ => Value.StringV("")
    },

    // ── AES-256-GCM symmetric encryption ────────────────────────────────────

    QualifiedName("aesGenKey") -> native { _ =>
      val kg = javax.crypto.KeyGenerator.getInstance("AES")
      kg.init(256)
      Value.StringV(b64e(kg.generateKey.getEncoded))
    },

    QualifiedName("aesGcmEncrypt") -> native {
      case List(keyB64: String, plaintext: String) =>
        Value.StringV(aesGcmEncryptRaw(keyB64, plaintext.getBytes("UTF-8")))
      case _ => throw new RuntimeException("aesGcmEncrypt(keyB64, plaintext)")
    },

    QualifiedName("aesGcmDecrypt") -> native {
      case List(keyB64: String, payloadB64: String) =>
        Value.StringV(new String(aesGcmDecryptRaw(keyB64, payloadB64), "UTF-8"))
      case _ => throw new RuntimeException("aesGcmDecrypt(keyB64, payloadB64)")
    },

    QualifiedName("aesGcmEncryptBytes") -> native {
      case List(keyB64: String, plaintextB64: String) =>
        Value.StringV(aesGcmEncryptRaw(keyB64, b64d(plaintextB64)))
      case _ => throw new RuntimeException("aesGcmEncryptBytes(keyB64, plaintextB64)")
    },

    QualifiedName("aesGcmDecryptBytes") -> native {
      case List(keyB64: String, payloadB64: String) =>
        Value.StringV(b64e(aesGcmDecryptRaw(keyB64, payloadB64)))
      case _ => throw new RuntimeException("aesGcmDecryptBytes(keyB64, payloadB64)")
    },

    // ── RSA-OAEP (SHA-256) public-key encryption ────────────────────────────

    QualifiedName("rsaOaepEncrypt") -> native {
      case List(publicKeyB64: String, plaintextB64: String) =>
        Value.StringV(rsaOaepEncryptRaw(publicKeyB64, b64d(plaintextB64)))
      case _ => throw new RuntimeException("rsaOaepEncrypt(publicKeyB64, plaintextB64)")
    },

    // ── X.509 certificate → SPKI public key ─────────────────────────────────

    QualifiedName("x509PublicKey") -> native {
      case List(certB64OrPem: String) =>
        Value.StringV(x509PublicKeyRaw(certB64OrPem))
      case _ => throw new RuntimeException("x509PublicKey(certB64OrPem)")
    },

  )
