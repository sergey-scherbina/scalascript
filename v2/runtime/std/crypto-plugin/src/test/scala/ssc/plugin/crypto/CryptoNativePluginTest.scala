package ssc.plugin.crypto

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class CryptoNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def string(name: String, args: Value*): String = call(name, args*) match
    case Value.StrV(value) => value
    case other => fail(s"$name returned $other")

  private def boolean(name: String, args: Value*): Boolean = call(name, args*) match
    case Value.BoolV(value) => value
    case other => fail(s"$name returned $other")

  test("sha256 registers as a standard intrinsic and global") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    assert(V2PluginRegistry.lookup("sha256").map(_(List(Value.StrV("hello")))).contains(
      Value.StrV("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")))
    val global = V2PluginRegistry.lookupGlobal("sha256").get.asInstanceOf[Value.ClosV]
    assert(Runtime.run(global.code, Array(Value.StrV("hello"))) ==
      Value.StrV("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
  }

  test("base64 and HMAC helpers preserve the legacy surface") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val encode = V2PluginRegistry.lookup("base64Encode").get
    val decode = V2PluginRegistry.lookup("base64Decode").get
    assert(encode(List(Value.StrV("hello"))) == Value.StrV("aGVsbG8="))
    assert(decode(List(Value.StrV("aGVsbG8="))) == Value.StrV("hello"))
    assert(V2PluginRegistry.lookup("hmacSha256").get(List(Value.StrV("key"), Value.StrV("data"))) ==
      Value.StrV("5031fe3d989c6d1537a013fa6e739da23463fdaec3b70137d828e36ace221bd0"))
  }

  test("AES GCM and CBC preserve framing and arbitrary-byte round trips") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val key = string("aesGenKey")
    assert(java.util.Base64.getDecoder.decode(key).length == 32)

    val encrypted = string("aesGcmEncrypt", Value.StrV(key), Value.StrV("faktura — 1000.01 zł"))
    assert(java.util.Base64.getDecoder.decode(encrypted).length > 12 + 16)
    assert(string("aesGcmDecrypt", Value.StrV(key), Value.StrV(encrypted)) == "faktura — 1000.01 zł")

    val bytes = NativeCrypto.encode(Array[Byte](0, -1, 7, 42))
    val encryptedBytes = string("aesGcmEncryptBytes", Value.StrV(key), Value.StrV(bytes))
    assert(string("aesGcmDecryptBytes", Value.StrV(key), Value.StrV(encryptedBytes)) == bytes)

    val iv = string("aesGenIv")
    assert(java.util.Base64.getDecoder.decode(iv).length == 16)
    val cbc = string("aesCbcEncrypt", Value.StrV(key), Value.StrV(iv), Value.StrV(bytes))
    assert(string("aesCbcDecrypt", Value.StrV(key), Value.StrV(iv), Value.StrV(cbc)) == bytes)
  }

  test("RSA OAEP and PKCS1/PSS signatures interoperate with JCA") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val generator = java.security.KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    val publicKey = NativeCrypto.encode(pair.getPublic.getEncoded)
    val privateKey = NativeCrypto.encode(pair.getPrivate.getEncoded)

    for scheme <- List("PKCS1", "PSS") do
      val signature = string("rsaSignSha256",
        Value.StrV(privateKey), Value.StrV("checkpoint"), Value.StrV(scheme))
      assert(boolean("verifyRsaSha256",
        Value.StrV(publicKey), Value.StrV("checkpoint"), Value.StrV(signature), Value.StrV(scheme)))
      assert(!boolean("verifyRsaSha256",
        Value.StrV(publicKey), Value.StrV("tampered"), Value.StrV(signature), Value.StrV(scheme)))

    val plaintext = "session-key-material".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val ciphertext = string("rsaOaepEncrypt",
      Value.StrV(publicKey), Value.StrV(NativeCrypto.encode(plaintext)))
    val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    val parameters = javax.crypto.spec.OAEPParameterSpec(
      "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
      javax.crypto.spec.PSource.PSpecified.DEFAULT)
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, pair.getPrivate, parameters)
    assert(cipher.doFinal(NativeCrypto.decode(ciphertext)).sameElements(plaintext))
  }

  test("Ed25519 reproduces RFC 8032 and hostile verification input is total") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val publicKey = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
    val seed = "TM0Imyj/ltqdtsNG7BFOD1uKMZ81q6Yk2oz27U+4pvs="
    val expected = "kqAJqfDUyrhyDoILX2QlQKKye1QWUD+Ps3YiI+vbadoIWsHkPhWZbkWPNhPQ8R2MOHsurrQwKu6wDSkWErsMAA=="
    assert(string("ed25519Sign", Value.StrV(seed), Value.StrV("r")) == expected)
    assert(boolean("verifyEd25519",
      Value.StrV(publicKey), Value.StrV("r"), Value.StrV(expected)))
    assert(!boolean("verifyEd25519",
      Value.StrV(publicKey), Value.StrV("R"), Value.StrV(expected)))
    assert(!boolean("verifyEd25519",
      Value.StrV("not-a-key"), Value.StrV("r"), Value.StrV("bad")))
  }

  test("HOTP and TOTP match RFC vectors and validation honors its window") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val sha1 = NativeCrypto.encode("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val sha256 = NativeCrypto.encode("12345678901234567890123456789012".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val sha512 = NativeCrypto.encode(
      "1234567890123456789012345678901234567890123456789012345678901234"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
    assert(string("hotp", Value.StrV(sha1), Value.IntV(0), Value.IntV(6), Value.StrV("SHA1")) == "755224")
    assert(string("totp", Value.StrV(sha1), Value.IntV(59), Value.IntV(30), Value.IntV(8), Value.StrV("SHA1")) == "94287082")
    assert(string("totp", Value.StrV(sha256), Value.IntV(59), Value.IntV(30), Value.IntV(8), Value.StrV("SHA256")) == "46119246")
    assert(string("totp", Value.StrV(sha512), Value.IntV(59), Value.IntV(30), Value.IntV(8), Value.StrV("SHA512")) == "90693936")
    val code = string("totp", Value.StrV(sha1), Value.IntV(1111111111),
      Value.IntV(30), Value.IntV(8), Value.StrV("SHA1"))
    assert(boolean("totpValidate", Value.StrV(sha1), Value.StrV(code),
      Value.IntV(1111111111), Value.IntV(30), Value.IntV(8), Value.StrV("SHA1"), Value.IntV(1)))
  }

  test("Shamir recovers from every threshold subset") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val secret = NativeCrypto.encode("super secret seed phrase".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val shares = string("shamirSplit", Value.StrV(secret), Value.IntV(2), Value.IntV(3)).split(" ")
    assert(shares.length == 3)
    shares.combinations(2).foreach { subset =>
      assert(string("shamirRecover", Value.StrV(subset.mkString(" "))) == secret)
    }
    assert(string("shamirRecover", Value.StrV(shares.head)) != secret)
  }
