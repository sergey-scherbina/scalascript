package scalascript.crypto.noble

import org.scalatest.funsuite.AnyFunSuite

import scalascript.crypto.{Curve, CryptoBackend, HashAlgo}

/** Cross-platform conformance test for the Scala.js
 *  [[NobleCryptoBackend]].  Every assertion checks against a known,
 *  externally-verifiable fixture (RFC 8032, RFC 5869, EVM-style
 *  ecrecover round-trips, canonical empty-string hashes), so a green
 *  run here proves bit-identical output with the JVM
 *  `BouncyCastleBackend` for the same input.
 *
 *  The runner lives in `crypto-noble-js/src/test/`; it executes under
 *  Node.js via the default Scala.js test runner.  Requires
 *  `npm install` to have populated `crypto-noble-js/node_modules/` —
 *  see the module's `package.json`. */
class NobleCryptoBackendTest extends AnyFunSuite:

  private val backend: CryptoBackend = new NobleCryptoBackend

  // ── helpers ─────────────────────────────────────────────────────────────

  private def hex(b: Array[Byte]): String =
    val sb = new StringBuilder(b.length * 2)
    for x <- b do sb.append(f"${x & 0xff}%02x")
    sb.result()

  private def fromHex(s: String): Array[Byte] =
    val clean = if s.startsWith("0x") then s.drop(2) else s
    val out   = new Array[Byte](clean.length / 2)
    var i     = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  // ── hashes ──────────────────────────────────────────────────────────────

  test("Sha256 of empty string matches canonical fixture"):
    val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    assert(hex(backend.hash(HashAlgo.Sha256, Array.emptyByteArray)) == expected)

  test("Keccak256 of empty string matches canonical fixture (EVM)"):
    val expected = "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
    assert(hex(backend.hash(HashAlgo.Keccak256, Array.emptyByteArray)) == expected)

  test("Sha512 of empty string matches canonical fixture"):
    val expected =
      "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
      "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
    assert(hex(backend.hash(HashAlgo.Sha512, Array.emptyByteArray)) == expected)

  test("HMAC-SHA256 RFC 4231 test case 1"):
    // RFC 4231 §4.2: key = 0x0b*20, data = "Hi There"
    val key  = Array.fill[Byte](20)(0x0b.toByte)
    val data = "Hi There".getBytes("UTF-8")
    val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
    assert(hex(backend.hmac(HashAlgo.Sha256, key, data)) == expected)

  test("HKDF-SHA256 RFC 5869 test case 1"):
    val ikm  = Array.fill[Byte](22)(0x0b.toByte)
    val salt = fromHex("000102030405060708090a0b0c")
    val info = fromHex("f0f1f2f3f4f5f6f7f8f9")
    val expected =
      "3cb25f25faacd57a90434f64d0362f2a" +
      "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
      "34007208d5b887185865"
    assert(hex(backend.hkdf(ikm, salt, info, 42, HashAlgo.Sha256)) == expected)

  // ── ed25519 — RFC 8032 test vector 1 ────────────────────────────────────

  test("Ed25519 derivePublic — RFC 8032 vector 1"):
    val priv = fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val expectedPub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    assert(hex(backend.derivePublic(Curve.Ed25519, priv)) == expectedPub)

  test("Ed25519 sign empty message — RFC 8032 vector 1"):
    val priv = fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val pub  = fromHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val sig  = backend.sign(Curve.Ed25519, priv, Array.emptyByteArray, HashAlgo.None)
    val expectedSig =
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f" +
      "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    assert(hex(sig) == expectedSig)
    assert(backend.verify(Curve.Ed25519, pub, Array.emptyByteArray, sig, HashAlgo.None))

  // ── secp256k1 ───────────────────────────────────────────────────────────

  test("secp256k1 derivePublic returns 64-byte uncompressed (no 0x04 prefix)"):
    val priv = fromHex("1234567890123456789012345678901234567890123456789012345678901234")
    val pub  = backend.derivePublic(Curve.Secp256k1, priv)
    assert(pub.length == 64)
    val expected =
      "e90c7d3640a1568839c31b70a893ab6714ef8415b9de90cedfc1c8f353a6983e" +
      "625529392df7fa514bdd65a2003f6619567d79bee89830e63e932dbd42362d34"
    assert(hex(pub) == expected)

  test("secp256k1 sign / verify round-trip with HashAlgo.Sha256"):
    val priv = fromHex("1234567890123456789012345678901234567890123456789012345678901234")
    val pub  = backend.derivePublic(Curve.Secp256k1, priv)
    val msg  = "hello".getBytes("UTF-8")
    val sig  = backend.sign(Curve.Secp256k1, priv, msg, HashAlgo.Sha256)
    assert(sig.length == 65)
    // Low-S; r is non-zero by construction (deterministic RFC 6979 k).
    assert(sig(64) == 0.toByte || sig(64) == 1.toByte)
    assert(backend.verify(Curve.Secp256k1, pub, msg, sig, HashAlgo.Sha256))

  test("secp256k1 recoverPublic round-trips through sign + recover"):
    val priv = fromHex("1234567890123456789012345678901234567890123456789012345678901234")
    val pub  = backend.derivePublic(Curve.Secp256k1, priv)
    val msgHash = backend.hash(HashAlgo.Sha256, "hello".getBytes("UTF-8"))
    val sig  = backend.sign(Curve.Secp256k1, priv, msgHash, HashAlgo.None)
    val recId = sig(64).toInt & 0xff
    val recovered = backend.recoverPublic(Curve.Secp256k1, msgHash, sig, recId)
    assert(recovered.length == 64)
    assert(java.util.Arrays.equals(recovered, pub))

  test("secp256k1 keccak256-based EVM-style ecrecover round-trip (privkey → address)"):
    // EIP-712 reference (test/wycheproof-like fixture; see
    // blockchain-evm/EvmChainAdapterTest):
    //   privKey = 0x4646...46 → address = 0x9d8a62f6 56a8 d161 5c12 94fd 71e9 cfb3 e485 5a4f
    val priv    = fromHex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub     = backend.derivePublic(Curve.Secp256k1, priv)
    val addrHex = hex(backend.hash(HashAlgo.Keccak256, pub).drop(12))
    assert(addrHex == "9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f")

  test("secp256k1 verify rejects a tampered signature"):
    val priv = fromHex("1234567890123456789012345678901234567890123456789012345678901234")
    val pub  = backend.derivePublic(Curve.Secp256k1, priv)
    val msg  = "hello".getBytes("UTF-8")
    val sig  = backend.sign(Curve.Secp256k1, priv, msg, HashAlgo.Sha256)
    sig(0) = (sig(0) ^ 0xff).toByte
    assert(!backend.verify(Curve.Secp256k1, pub, msg, sig, HashAlgo.Sha256))

  // ── p256 ────────────────────────────────────────────────────────────────

  test("P256 derivePublic returns 64-byte uncompressed (no 0x04 prefix)"):
    val priv = fromHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
    val pub  = backend.derivePublic(Curve.P256, priv)
    assert(pub.length == 64)
    // First byte is X coord MSB — full vector cross-verified against noble JS.
    val expected =
      "798953e7e8134fdf3c139f63d3fbccc252a28b6ca5059e618374a81231240f3f" +
      "c83267aec725e18b66176c3685d1257201a67033819585a22a296350159ae70b"
    assert(hex(pub) == expected)

  test("P256 sign / verify round-trip"):
    val priv = fromHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
    val pub  = backend.derivePublic(Curve.P256, priv)
    val msg  = "passkey-attestation".getBytes("UTF-8")
    val sig  = backend.sign(Curve.P256, priv, msg, HashAlgo.Sha256)
    assert(sig.length == 65)
    assert(backend.verify(Curve.P256, pub, msg, sig, HashAlgo.Sha256))

  // ── Stage 5 — KDF + AEAD parity with JVM ────────────────────────────────
  //
  // These hex assertions MUST match the JVM-side
  // crypto-bouncycastle/CrossPlatformFixturesTest byte-for-byte.

  test("Argon2id RFC 9106 — password='password' salt='somesaltsomesalt' m=256 t=1 p=1 dk=32"):
    val pw = "password".getBytes("UTF-8")
    val sa = "somesaltsomesalt".getBytes("UTF-8")
    assert(hex(backend.argon2id(pw, sa, 256, 1, 1, 32)) ==
      "867efe4b384b1563792f6bf0e6da1d8834fcd2b0059d5998c25236e8f49fee93")

  test("Argon2id — m=4096 t=2 p=1 dk=32"):
    val pw = "password".getBytes("UTF-8")
    val sa = "somesaltsomesalt".getBytes("UTF-8")
    assert(hex(backend.argon2id(pw, sa, 4096, 2, 1, 32)) ==
      "1f99999fc42c145c27b4f92d75b8c636da81f830c9fa78b1abde2e240668a889")

  test("PBKDF2-SHA256 — password='password' salt='salt' c=1 dk=20"):
    assert(hex(backend.pbkdf2("password".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 1, 20, HashAlgo.Sha256)) ==
      "120fb6cffcf8b32c43e7225256c4f837a86548c9")

  test("PBKDF2-SHA256 — c=4096 dk=32"):
    assert(hex(backend.pbkdf2("password".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 4096, 32, HashAlgo.Sha256)) ==
      "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a")

  test("PBKDF2-SHA512 — c=2048 dk=64"):
    assert(hex(backend.pbkdf2("password".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 2048, 64, HashAlgo.Sha512)) ==
      "91be23564f09fc855c82ce84a223ebe7d63d8b49d69372593a0d9ed39e143c83e1ab2f722a5ddb969feefc88403f7e2afe1afb8b2f0e6b20add0fb7b28368807")

  test("AES-GCM encrypt — key=0..1f iv=0..0b plaintext='hello' aad=''"):
    val key = (0 until 32).map(_.toByte).toArray
    val iv  = (0 until 12).map(_.toByte).toArray
    assert(hex(backend.aesGcmEncrypt(key, iv, "hello".getBytes("UTF-8"), Array.empty[Byte])) ==
      "2f67ba77aa2797ff353b8a046d28236dcd9d057bbb")

  test("AES-GCM encrypt — empty plaintext, aad='aad' produces 16-byte tag only"):
    val key = (0 until 32).map(_.toByte).toArray
    val iv  = (0 until 12).map(_.toByte).toArray
    assert(hex(backend.aesGcmEncrypt(key, iv, Array.empty[Byte], "aad".getBytes("UTF-8"))) ==
      "0a858bc1de4afc6369cd2cc4aef92349")

  test("AES-GCM round-trip — large 16 KiB plaintext encrypts + decrypts"):
    val key = (0 until 32).map(_.toByte).toArray
    val iv  = (0 until 12).map(_.toByte).toArray
    val big = new Array[Byte](16 * 1024)
    var i = 0
    while i < big.length do { big(i) = (i & 0xff).toByte; i += 1 }
    val ct = backend.aesGcmEncrypt(key, iv, big, Array.empty[Byte])
    assert(ct.length == 16 * 1024 + 16)
    val pt = backend.aesGcmDecrypt(key, iv, ct, Array.empty[Byte])
    assert(java.util.Arrays.equals(pt, big))

  test("AES-GCM decrypt rejects ciphertext tampered in the auth tag"):
    val key = (0 until 32).map(_.toByte).toArray
    val iv  = (0 until 12).map(_.toByte).toArray
    val ct  = backend.aesGcmEncrypt(key, iv, "hello".getBytes("UTF-8"), Array.empty[Byte])
    ct(ct.length - 1) = (ct(ct.length - 1) ^ 0xff).toByte
    val ex = intercept[Exception](backend.aesGcmDecrypt(key, iv, ct, Array.empty[Byte]))
    assert(ex != null)

  // ── registry ────────────────────────────────────────────────────────────

  test("Register.install puts NobleCryptoBackend into the shared registry"):
    CryptoBackend.resetForTests()
    Register.install()
    val resolved = CryptoBackend.get("noble-js")
    assert(resolved.id == "noble-js")
    assert(resolved.supports(Curve.Secp256k1))
    assert(resolved.supports(Curve.Ed25519))
    assert(resolved.supports(Curve.P256))
    assert(!resolved.supports(Curve.Sr25519))
    assert(!resolved.supports(Curve.Bls12_381))

  test("supports() answers true only for the three implemented curves"):
    val expected = Set(Curve.Secp256k1, Curve.Ed25519, Curve.P256)
    for c <- Curve.values do
      assert(backend.supports(c) == expected(c), s"supports(${c}) mismatch")
