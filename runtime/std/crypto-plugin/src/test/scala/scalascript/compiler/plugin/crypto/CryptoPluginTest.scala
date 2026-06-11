package scalascript.compiler.plugin.crypto

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class CryptoPluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(CryptoInterpreterPlugin()))

  private def evalStr(snippet: String): String =
    interp.eval(snippet).asInstanceOf[String]

  // ── sha256 — NIST FIPS 180-4 test vectors ───────────────────────────────

  test("sha256 of empty string matches NIST vector"):
    assert(evalStr("""sha256("")""") ==
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

  test("sha256 of 'abc' matches JVM-computed reference value"):
    // Verified via java.security.MessageDigest.getInstance("SHA-256") directly.
    assert(evalStr("""sha256("abc")""") ==
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

  test("sha256 output is always 64 lowercase hex characters"):
    val result = evalStr("""sha256("hello world")""")
    assert(result.length == 64, s"expected 64 chars, got ${result.length}: $result")
    assert(result.matches("[0-9a-f]{64}"), s"not lowercase hex: $result")

  test("sha256 is deterministic"):
    val a = evalStr("""sha256("deterministic")""")
    val b = evalStr("""sha256("deterministic")""")
    assert(a == b)

  test("sha256 of distinct inputs produces distinct digests"):
    val a = evalStr("""sha256("foo")""")
    val b = evalStr("""sha256("bar")""")
    assert(a != b)

  // ── sha256Base64 — same digest, base64 (KSeF invoiceHash) ────────────────

  test("sha256Base64 of empty string matches the known base64 vector"):
    assert(evalStr("""sha256Base64("")""") == "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")

  test("sha256Base64 of 'abc' matches the known base64 vector"):
    assert(evalStr("""sha256Base64("abc")""") == "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=")

  test("sha256Base64 is the base64 of the same digest sha256 returns as hex"):
    val hex = evalStr("""sha256("KSeF invoice content")""")
    val b64 = evalStr("""sha256Base64("KSeF invoice content")""")
    val digestBytes = hex.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
    assert(b64 == java.util.Base64.getEncoder.encodeToString(digestBytes),
      s"sha256Base64 must equal base64(hex-decoded sha256); got $b64")
    assert(java.util.Base64.getDecoder.decode(b64).length == 32, "digest must be 32 bytes")

  // ── hmacSha256 — RFC 4231 test vector ────────────────────────────────────

  test("hmacSha256 output is always 64 lowercase hex characters"):
    val result = evalStr("""hmacSha256("key", "data")""")
    assert(result.length == 64, s"expected 64 chars, got ${result.length}: $result")
    assert(result.matches("[0-9a-f]{64}"))

  test("hmacSha256 matches known vector (RFC 4231 §4.2)"):
    // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
    assert(evalStr("""hmacSha256("key", "The quick brown fox jumps over the lazy dog")""") ==
      "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")

  test("hmacSha256 is key-sensitive"):
    val a = evalStr("""hmacSha256("key1", "data")""")
    val b = evalStr("""hmacSha256("key2", "data")""")
    assert(a != b)

  test("hmacSha256 is data-sensitive"):
    val a = evalStr("""hmacSha256("key", "data1")""")
    val b = evalStr("""hmacSha256("key", "data2")""")
    assert(a != b)

  // ── base64Encode ─────────────────────────────────────────────────────────

  test("base64Encode 'Man' → 'TWFu' (RFC 4648 example)"):
    assert(evalStr("""base64Encode("Man")""") == "TWFu")

  test("base64Encode 'a' → 'YQ==' (1-byte padding)"):
    assert(evalStr("""base64Encode("a")""") == "YQ==")

  test("base64Encode 'ab' → 'YWI=' (2-byte padding)"):
    assert(evalStr("""base64Encode("ab")""") == "YWI=")

  test("base64Encode 'abc' → 'YWJj' (no padding)"):
    assert(evalStr("""base64Encode("abc")""") == "YWJj")

  // ── base64Decode ─────────────────────────────────────────────────────────

  test("base64Decode 'aGVsbG8=' → 'hello'"):
    assert(evalStr("""base64Decode("aGVsbG8=")""") == "hello")

  test("base64Decode 'TWFu' → 'Man'"):
    assert(evalStr("""base64Decode("TWFu")""") == "Man")

  test("base64Encode / base64Decode round-trip"):
    assert(evalStr("""base64Decode(base64Encode("The quick brown fox jumps over the lazy dog"))""") ==
      "The quick brown fox jumps over the lazy dog")

  // ── AES-256-GCM ──────────────────────────────────────────────────────────

  test("aesGenKey returns a fresh 256-bit (32-byte) key each call"):
    val a = evalStr("aesGenKey()")
    val b = evalStr("aesGenKey()")
    assert(a != b, "two generated keys must differ")
    assert(java.util.Base64.getDecoder.decode(a).length == 32, "key must be 32 bytes")

  test("aesGcmEncrypt / aesGcmDecrypt round-trips ASCII + UTF-8"):
    assert(evalStr(
      """val k = aesGenKey()
        |aesGcmDecrypt(k, aesGcmEncrypt(k, "faktura — 1000.01 zł ąćęł"))""".stripMargin) ==
      "faktura — 1000.01 zł ąćęł")

  test("aesGcmEncrypt is non-deterministic (random IV per call)"):
    val out = evalStr(
      """val k = aesGenKey()
        |aesGcmEncrypt(k, "x") + "|" + aesGcmEncrypt(k, "x")""".stripMargin)
    val parts = out.split('|')
    assert(parts(0) != parts(1), "same plaintext+key must yield different ciphertext (IV)")

  test("aesGcmDecrypt of a tampered payload throws (GCM auth), not silent garbage"):
    val k   = evalStr("aesGenKey()")
    val enc = evalStr(s"""aesGcmEncrypt("$k", "secret")""")
    // Flip the last base64 char to corrupt the tag.
    val flipped = enc.dropRight(1) + (if enc.last == 'A' then 'B' else 'A')
    assertThrows[Throwable](evalStr(s"""aesGcmDecrypt("$k", "$flipped")"""))

  test("aesGcmEncryptBytes / aesGcmDecryptBytes round-trips binary (base64) payloads"):
    val ptB64 = java.util.Base64.getEncoder.encodeToString(Array[Byte](0, 1, 2, -1, -128, 127))
    val out = evalStr(
      s"""val k = aesGenKey()
         |aesGcmDecryptBytes(k, aesGcmEncryptBytes(k, "$ptB64"))""".stripMargin)
    assert(out == ptB64)

  // ── AES-256-CBC (external IV, PKCS#7) ────────────────────────────────────

  test("aesGenIv returns a fresh 16-byte IV each call"):
    val a = evalStr("aesGenIv()")
    val b = evalStr("aesGenIv()")
    assert(a != b, "two generated IVs must differ")
    assert(java.util.Base64.getDecoder.decode(a).length == 16, "IV must be 16 bytes")

  test("aesCbcEncrypt / aesCbcDecrypt round-trips binary (base64) payloads"):
    val ptB64 = java.util.Base64.getEncoder.encodeToString(Array[Byte](0, 1, 2, -1, -128, 127, 42))
    val out = evalStr(
      s"""val k  = aesGenKey()
         |val iv = aesGenIv()
         |aesCbcDecrypt(k, iv, aesCbcEncrypt(k, iv, "$ptB64"))""".stripMargin)
    assert(out == ptB64)

  test("aesCbcEncrypt is deterministic for a fixed key + IV (CBC has no nonce framing)"):
    val key = evalStr("aesGenKey()")
    val iv  = evalStr("aesGenIv()")
    val pt  = java.util.Base64.getEncoder.encodeToString("invoice".getBytes("UTF-8"))
    val a = evalStr(s"""aesCbcEncrypt("$key", "$iv", "$pt")""")
    val b = evalStr(s"""aesCbcEncrypt("$key", "$iv", "$pt")""")
    assert(a == b, "same key+IV+plaintext must yield identical ciphertext")

  test("aesCbcEncrypt output decrypts with a direct JCE AES/CBC/PKCS5Padding cipher (interop)"):
    val key = evalStr("aesGenKey()")
    val iv  = evalStr("aesGenIv()")
    val msg = "faktura — 1000.01 zł ąćęł"
    val ptB64 = java.util.Base64.getEncoder.encodeToString(msg.getBytes("UTF-8"))
    val ctB64 = evalStr(s"""aesCbcEncrypt("$key", "$iv", "$ptB64")""")
    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
      new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder.decode(key), "AES"),
      new javax.crypto.spec.IvParameterSpec(java.util.Base64.getDecoder.decode(iv)))
    val recovered = new String(cipher.doFinal(java.util.Base64.getDecoder.decode(ctB64)), "UTF-8")
    assert(recovered == msg, s"JCE interop mismatch: $recovered")

  test("aesCbcEncrypt rejects an IV that is not 16 bytes"):
    val key   = evalStr("aesGenKey()")
    val badIv = java.util.Base64.getEncoder.encodeToString(new Array[Byte](12)) // GCM-sized
    val pt    = java.util.Base64.getEncoder.encodeToString("x".getBytes("UTF-8"))
    assertThrows[Throwable](evalStr(s"""aesCbcEncrypt("$key", "$badIv", "$pt")"""))

  test("aesCbcDecrypt with the wrong key never returns the original plaintext"):
    // Wrong-key decrypt usually throws (PKCS#7 padding fails); on the rare run
    // where random bytes happen to carry valid padding it must still NOT recover
    // the plaintext. Either outcome is correct; silent-correct is the bug.
    val pt  = java.util.Base64.getEncoder.encodeToString("secret".getBytes("UTF-8"))
    val k1  = evalStr("aesGenKey()")
    val k2  = evalStr("aesGenKey()")
    val iv  = evalStr("aesGenIv()")
    val ct  = evalStr(s"""aesCbcEncrypt("$k1", "$iv", "$pt")""")
    val recovered =
      try Some(evalStr(s"""aesCbcDecrypt("$k2", "$iv", "$ct")"""))
      catch case _: Throwable => None
    assert(recovered.forall(_ != pt), "wrong key must not recover the plaintext")

  // ── RSA-OAEP (SHA-256) ───────────────────────────────────────────────────

  test("rsaOaepEncrypt output decrypts with the matching private key (JCE interop)"):
    val kp = java.security.KeyPairGenerator.getInstance("RSA")
    kp.initialize(2048)
    val pair  = kp.generateKeyPair()
    val spki  = java.util.Base64.getEncoder.encodeToString(pair.getPublic.getEncoded)
    val msg   = "session-key-material"
    val msgB64 = java.util.Base64.getEncoder.encodeToString(msg.getBytes("UTF-8"))
    val ctB64 = evalStr(s"""rsaOaepEncrypt("$spki", "$msgB64")""")
    // Decrypt in Scala with the same OAEP-SHA256 parameters.
    val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    val oaep   = new javax.crypto.spec.OAEPParameterSpec(
      "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
      javax.crypto.spec.PSource.PSpecified.DEFAULT)
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, pair.getPrivate, oaep)
    val recovered = new String(cipher.doFinal(java.util.Base64.getDecoder.decode(ctB64)), "UTF-8")
    assert(recovered == msg)

  // ── X.509 → SPKI public key ──────────────────────────────────────────────

  private val testCertPem =
    """-----BEGIN CERTIFICATE-----
      |MIIDCTCCAfGgAwIBAgIUWMdEsF30cV1JhlPMJt4DgHfA30QwDQYJKoZIhvcNAQEL
      |BQAwFDESMBAGA1UEAwwJa3NlZi10ZXN0MB4XDTI2MDYxMDAzMTY1NFoXDTM2MDYw
      |NzAzMTY1NFowFDESMBAGA1UEAwwJa3NlZi10ZXN0MIIBIjANBgkqhkiG9w0BAQEF
      |AAOCAQ8AMIIBCgKCAQEApQJZ1LKs2io8CtAKOPYV7fgeQ4/36twBEw7ty65Ps6hK
      |MnPB1u8B+vP+JJH6ssjMp6Z2sfaqdmjTEnaeM/QBm7et53Jy7zh6y6wGlc3JJtgR
      |jFa+7gLppy/28hJ5VgwXH+QxBLA9ubc9vSTrfU29DGlUvvN3ZbW0tKqwgLe+kEry
      |wZCID0nweXDq1wiDxQW8y0HvbXTUt44oh4qTu9P1HmY6ny/XWgkA5Z0iu+k+B20L
      |ICKkdfHYJ63ExbEjDqkptusxGsI3O0crqsCgIIplxVfYiWxpTGyNOydOnM8kDUA/
      |GckFg38icuspVK1399BSwdGymfyj8jn6+GnUabIymwIDAQABo1MwUTAdBgNVHQ4E
      |FgQUsGeqc9tBqASG3ke7Y47VkepwUZowHwYDVR0jBBgwFoAUsGeqc9tBqASG3ke7
      |Y47VkepwUZowDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAWzS3
      |VddXYtPlC9lY2gyPiqjwrKLqeF5RgYItlv2FYY9AynghawqtPu1xNhFJZBJIaNXR
      |rXMP0TxXHFiYLnaJszFcCWAaPOgeMczjP3uLfnMKVVf7Agu8F9wMdZV1uNHuWfr1
      |d3WypYK+JhxSrkXf7pqP/y85oCsogzMFmyxjroZefGfyWn7k+5a8PWxqb7duFH7W
      |Btewqu6a8jfyZp0E48ehT6aZaxybBLtd1Uc10AdylGgXQTN1cSVaS3WvCgmvJBx1
      |vcooVsBv6LeD1NZhDYZIVOFf35Jlm4Vu+eEV+RcKkoEFYxXa8SNvoL/o5FATmo1M
      |CQ6295wKcs5MVODZuw==
      |-----END CERTIFICATE-----""".stripMargin

  private val expectedSpki =
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApQJZ1LKs2io8CtAKOPYV7fgeQ4/36twBEw7ty65Ps6hK" +
    "MnPB1u8B+vP+JJH6ssjMp6Z2sfaqdmjTEnaeM/QBm7et53Jy7zh6y6wGlc3JJtgRjFa+7gLppy/28hJ5VgwXH+Qx" +
    "BLA9ubc9vSTrfU29DGlUvvN3ZbW0tKqwgLe+kErywZCID0nweXDq1wiDxQW8y0HvbXTUt44oh4qTu9P1HmY6ny/X" +
    "WgkA5Z0iu+k+B20LICKkdfHYJ63ExbEjDqkptusxGsI3O0crqsCgIIplxVfYiWxpTGyNOydOnM8kDUA/GckFg38i" +
    "cuspVK1399BSwdGymfyj8jn6+GnUabIymwIDAQAB"

  test("x509PublicKey extracts the SPKI public key from a PEM X.509 cert"):
    // Pass the PEM via base64 so it survives the single-line snippet interpolation.
    val pemB64 = java.util.Base64.getEncoder.encodeToString(testCertPem.getBytes("UTF-8"))
    val spki = evalStr(s"""x509PublicKey(base64Decode("$pemB64"))""")
    assert(spki == expectedSpki, s"unexpected SPKI:\n$spki")

  test("x509PublicKey output is a usable RSA public key (rsaOaepEncrypt accepts it)"):
    val pemB64 = java.util.Base64.getEncoder.encodeToString(testCertPem.getBytes("UTF-8"))
    val msgB64 = java.util.Base64.getEncoder.encodeToString("hi".getBytes("UTF-8"))
    val ct = evalStr(
      s"""val pk = x509PublicKey(base64Decode("$pemB64"))
         |rsaOaepEncrypt(pk, "$msgB64")""".stripMargin)
    assert(ct.nonEmpty && java.util.Base64.getDecoder.decode(ct).length == 256,
      "RSA-2048 OAEP ciphertext must be 256 bytes")

  // ── Ed25519 signature verification (RFC 8032 test vectors) ───────────────

  private def evalBool(snippet: String): Boolean =
    interp.eval(snippet).asInstanceOf[Boolean]
  private def hex(s: String): Array[Byte] =
    s.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
  private def b64(b: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(b)

  test("verifyEd25519 true for RFC 8032 test 1 (empty message)"):
    val pub = b64(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
    val sig = b64(hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"))
    assert(evalBool(s"""verifyEd25519("$pub", "", "$sig")"""))

  test("verifyEd25519 true for RFC 8032 test 2 (1-byte message 'r')"):
    val pub = b64(hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"))
    val sig = b64(hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"))
    assert(evalBool(s"""verifyEd25519("$pub", "r", "$sig")"""))

  test("verifyEd25519 false for tampered message / wrong sig / malformed input"):
    val pub = b64(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
    val sig = b64(hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"))
    assert(!evalBool(s"""verifyEd25519("$pub", "tampered", "$sig")"""), "tampered message must fail")
    assert(!evalBool(s"""verifyEd25519("$pub", "", "QUJD")"""), "wrong signature must fail")
    assert(!evalBool("""verifyEd25519("not-base64!!", "", "QUJD")"""), "malformed key must return false, not throw")

  test("verifyEd25519Url decodes base64url key + signature"):
    val pubRaw = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val sigRaw = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
    val pubUrl = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(pubRaw)
    val sigUrl = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(sigRaw)
    assert(evalBool(s"""verifyEd25519Url("$pubUrl", "", "$sigUrl")"""))

  // ── RSA signature verification (JCE-generated keypair, PKCS1 + PSS) ───────

  test("verifyRsaSha256 true for a PKCS1 signature, false when tampered"):
    val kp = java.security.KeyPairGenerator.getInstance("RSA"); kp.initialize(2048)
    val pair = kp.generateKeyPair()
    val spki = b64(pair.getPublic.getEncoded)
    val signer = java.security.Signature.getInstance("SHA256withRSA")
    signer.initSign(pair.getPrivate); signer.update("hello".getBytes("UTF-8"))
    val sig = b64(signer.sign())
    assert(evalBool(s"""verifyRsaSha256("$spki", "hello", "$sig", "PKCS1")"""))
    assert(!evalBool(s"""verifyRsaSha256("$spki", "HELLO", "$sig", "PKCS1")"""), "tampered must fail")

  test("verifyRsaSha256 true for a PSS signature"):
    val kp = java.security.KeyPairGenerator.getInstance("RSA"); kp.initialize(2048)
    val pair = kp.generateKeyPair()
    val spki = b64(pair.getPublic.getEncoded)
    val signer = java.security.Signature.getInstance("RSASSA-PSS")
    signer.setParameter(new java.security.spec.PSSParameterSpec(
      "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1))
    signer.initSign(pair.getPrivate); signer.update("envelope".getBytes("UTF-8"))
    val sig = b64(signer.sign())
    assert(evalBool(s"""verifyRsaSha256("$spki", "envelope", "$sig", "PSS")"""))
    // A PSS signature must NOT verify as PKCS1.
    assert(!evalBool(s"""verifyRsaSha256("$spki", "envelope", "$sig", "PKCS1")"""))

  // ── pbkdf2 / secureRandomBytesB64 (busi-auth password hashing) ──────────

  /** Independent reference: PBKDF2WithHmacSHA256 straight from the JCE. */
  private def jcePbkdf2(password: String, salt: Array[Byte], iter: Int, dkLenBytes: Int): String =
    val spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray, salt, iter, dkLenBytes * 8)
    val skf  = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    b64(skf.generateSecret(spec).getEncoded)

  test("pbkdf2 matches a direct PBKDF2WithHmacSHA256 reference (correct algo + dkLen in bytes)"):
    val salt = "salt".getBytes("UTF-8")
    val got  = evalStr(s"""pbkdf2("password", "${b64(salt)}", 4096, 32)""")
    assert(got == jcePbkdf2("password", salt, 4096, 32), s"pbkdf2 mismatch: $got")
    // dkLen is in bytes → 32 bytes decode
    assert(java.util.Base64.getDecoder.decode(got).length == 32)

  test("pbkdf2 is deterministic and honours dkLen"):
    val salt = b64("s".getBytes("UTF-8"))
    val a = evalStr(s"""pbkdf2("pw", "$salt", 1000, 16)""")
    val b = evalStr(s"""pbkdf2("pw", "$salt", 1000, 16)""")
    assert(a == b, "same inputs must derive the same key")
    assert(java.util.Base64.getDecoder.decode(a).length == 16, "dkLen=16 → 16 bytes")

  test("pbkdf2 is salt- and iteration-sensitive"):
    val s1 = evalStr(s"""pbkdf2("pw", "${b64("salt1".getBytes)}", 1000, 32)""")
    val s2 = evalStr(s"""pbkdf2("pw", "${b64("salt2".getBytes)}", 1000, 32)""")
    assert(s1 != s2, "different salt must derive a different key")
    val i1 = evalStr(s"""pbkdf2("pw", "${b64("salt".getBytes)}", 1000, 32)""")
    val i2 = evalStr(s"""pbkdf2("pw", "${b64("salt".getBytes)}", 2000, 32)""")
    assert(i1 != i2, "different iteration count must derive a different key")

  test("pbkdf2 round-trips a verify: re-derive with stored salt+iters matches"):
    val salt = evalStr("""secureRandomBytesB64(16)""")
    val stored = evalStr(s"""pbkdf2("hunter2", "$salt", 50000, 32)""")
    val rederived = evalStr(s"""pbkdf2("hunter2", "$salt", 50000, 32)""")
    val wrong = evalStr(s"""pbkdf2("hunter3", "$salt", 50000, 32)""")
    assert(stored == rederived, "correct password re-derives the stored key")
    assert(stored != wrong, "wrong password derives a different key")

  test("secureRandomBytesB64 returns n bytes and is non-repeating"):
    val a = evalStr("""secureRandomBytesB64(16)""")
    val b = evalStr("""secureRandomBytesB64(16)""")
    assert(java.util.Base64.getDecoder.decode(a).length == 16, "must be 16 bytes")
    assert(a != b, "two draws must differ (overwhelmingly)")
    assert(evalStr("""secureRandomBytesB64(0)""") == "", "0 bytes → empty base64")

  // ── public-key signing (ed25519Sign / rsaSignSha256 — round-trip verify) ──

  test("ed25519Sign round-trips with verifyEd25519 (PKCS#8 private key)"):
    val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val priv = b64(kp.getPrivate.getEncoded)   // PKCS#8 DER
    val pub  = b64(kp.getPublic.getEncoded)     // SPKI DER
    val sig = evalStr(s"""ed25519Sign("$priv", "month-close evidence")""")
    assert(evalBool(s"""verifyEd25519("$pub", "month-close evidence", "$sig")"""),
      "a fresh Ed25519 signature must verify")
    assert(!evalBool(s"""verifyEd25519("$pub", "tampered", "$sig")"""),
      "the same signature must not verify a tampered message")

  test("ed25519Sign reproduces the RFC 8032 test #2 signature (deterministic)"):
    // seed + public key + signature are RFC 8032 §7.1 test 2 (message = "r").
    val seed = b64(hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"))
    val expected = b64(hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"))
    assert(evalStr(s"""ed25519Sign("$seed", "r")""") == expected,
      "Ed25519 is deterministic — signing the test seed over 'r' must equal the vector")

  test("ed25519Sign accepts a raw 32-byte seed and verifies via the matching public key"):
    // Derive a keypair, extract the raw 32-byte seed from the PKCS#8 DER (last 32 bytes).
    val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val pkcs8 = kp.getPrivate.getEncoded
    val seed = b64(pkcs8.takeRight(32))
    val pub  = b64(kp.getPublic.getEncoded)
    val sig = evalStr(s"""ed25519Sign("$seed", "raw-seed")""")
    assert(evalBool(s"""verifyEd25519("$pub", "raw-seed", "$sig")"""),
      "signing with a raw 32-byte seed must produce a verifiable signature")

  test("ed25519SignUrl round-trips with verifyEd25519Url (base64url)"):
    val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val urlEnc = java.util.Base64.getUrlEncoder.withoutPadding
    val priv = urlEnc.encodeToString(kp.getPrivate.getEncoded)
    val pub  = urlEnc.encodeToString(kp.getPublic.getEncoded)
    val sig = evalStr(s"""ed25519SignUrl("$priv", "jws")""")
    assert(evalBool(s"""verifyEd25519Url("$pub", "jws", "$sig")"""),
      "a base64url Ed25519 signature must verify via the url verifier")

  test("rsaSignSha256 PKCS1 round-trips with verifyRsaSha256"):
    val kp = java.security.KeyPairGenerator.getInstance("RSA"); kp.initialize(2048)
    val pair = kp.generateKeyPair()
    val priv = b64(pair.getPrivate.getEncoded)  // PKCS#8 DER
    val pub  = b64(pair.getPublic.getEncoded)    // SPKI DER
    val sig = evalStr(s"""rsaSignSha256("$priv", "checkpoint", "PKCS1")""")
    assert(evalBool(s"""verifyRsaSha256("$pub", "checkpoint", "$sig", "PKCS1")"""),
      "a PKCS1 RSA signature must verify")
    assert(!evalBool(s"""verifyRsaSha256("$pub", "CHECKPOINT", "$sig", "PKCS1")"""),
      "tampered message must fail")

  test("rsaSignSha256 PSS round-trips with verifyRsaSha256"):
    val kp = java.security.KeyPairGenerator.getInstance("RSA"); kp.initialize(2048)
    val pair = kp.generateKeyPair()
    val priv = b64(pair.getPrivate.getEncoded)
    val pub  = b64(pair.getPublic.getEncoded)
    val sig = evalStr(s"""rsaSignSha256("$priv", "checkpoint", "PSS")""")
    assert(evalBool(s"""verifyRsaSha256("$pub", "checkpoint", "$sig", "PSS")"""),
      "a PSS RSA signature must verify")
