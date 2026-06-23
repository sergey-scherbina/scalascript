package scalascript.crypto.noble

import org.scalatest.funsuite.AnyFunSuite

import scalascript.crypto.{Blake2b, Curve, CryptoBackend, HashAlgo}

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

  // ── BLAKE2b (RFC 7693) — both the noble backend AND the pure-Scala reference, on JS ──

  test("BLAKE2b-256 matches reference vectors + the pure-Scala Blake2b reference"):
    val cases = List(
      ""    -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
      "abc" -> "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319",
    )
    for (in, out) <- cases do
      val data = in.getBytes("UTF-8")
      assert(hex(backend.hash(HashAlgo.Blake2b256, data)) == out, s"noble blake2b-256($in)")
      assert(hex(Blake2b.hash256(data)) == out,                   s"reference blake2b-256($in)")
    // 200 bytes — multi-block; 128 bytes — exact-one-block edge.
    val big = Array.tabulate(200)(i => (i & 0xff).toByte)
    assert(hex(backend.hash(HashAlgo.Blake2b256, big)) == "63c3d97a9f8894d5e043a707b0fee7f7ec4c049a23bbf1079df20b4165f9e22d")
    assert(hex(Blake2b.hash256(big))                   == "63c3d97a9f8894d5e043a707b0fee7f7ec4c049a23bbf1079df20b4165f9e22d")
    val blk = Array.fill(128)(0x41.toByte)
    assert(hex(backend.hash(HashAlgo.Blake2b256, blk)) == "5db0e67323e93220e9602568a3c2c43f52dc843e4ea5b1e3deb9d5d80ed9cf2c")
    assert(hex(Blake2b.hash256(blk))                   == "5db0e67323e93220e9602568a3c2c43f52dc843e4ea5b1e3deb9d5d80ed9cf2c")

  test("BLAKE2b-224 matches reference vectors + the pure-Scala Blake2b reference"):
    val cases = List(
      ""    -> "836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07",
      "abc" -> "9bd237b02a29e43bdd6738afa5b53ff0eee178d6210b618e4511aec8",
    )
    for (in, out) <- cases do
      val data = in.getBytes("UTF-8")
      assert(hex(backend.hash(HashAlgo.Blake2b224, data)) == out, s"noble blake2b-224($in)")
      assert(hex(Blake2b.hash224(data)) == out,                   s"reference blake2b-224($in)")

  // ── HD derivation (BIP-32 / SLIP-0010) — byte-for-byte equal to the JVM BouncyCastle backend ──

  test("BIP-32 secp256k1 master + hardened + non-hardened children (appendix C vector 1)"):
    val seed   = fromHex("000102030405060708090a0b0c0d0e0f")
    val master = backend.deriveMaster(Curve.Secp256k1, seed)
    assert(hex(master.privateKey) == "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35")
    assert(hex(master.chainCode)  == "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508")
    // m/0H (hardened)
    val c0h = backend.deriveChild(Curve.Secp256k1, master, 0L, hardened = true)
    assert(hex(c0h.privateKey) == "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea")
    assert(hex(c0h.chainCode)  == "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141")
    // m/0H/1 (non-hardened — exercises compressPublic via @noble/curves)
    val c0h1 = backend.deriveChild(Curve.Secp256k1, c0h, 1L, hardened = false)
    assert(hex(c0h1.privateKey) == "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368")
    assert(hex(c0h1.chainCode)  == "2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19")

  test("SLIP-0010 ed25519 master + m/0H (appendix B vector 1)"):
    val seed   = fromHex("000102030405060708090a0b0c0d0e0f")
    val master = backend.deriveMaster(Curve.Ed25519, seed)
    assert(hex(master.privateKey) == "2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7")
    assert(hex(master.chainCode)  == "90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb")
    val child = backend.deriveChild(Curve.Ed25519, master, 0L, hardened = true)
    assert(hex(child.privateKey) == "68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3")
    assert(hex(child.chainCode)  == "8b59aa11380b624e81507a27fedda59fea6d0b779a778918a2fd3590e16e9c69")

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

  // ── Stage 6 — ChaCha20-Poly1305 + X25519 parity with JVM ────────────────
  //
  // Same hex strings as CrossPlatformFixturesTest on the BouncyCastle side.

  test("ChaCha20-Poly1305 encrypt — key=0..1f nonce=0..0b plaintext='hello chacha' aad='wc-aad'"):
    val key   = (0 until 32).map(_.toByte).toArray
    val nonce = (0 until 12).map(_.toByte).toArray
    val aad   = "wc-aad".getBytes("UTF-8")
    val pt    = "hello chacha".getBytes("UTF-8")
    assert(hex(backend.chacha20Poly1305Encrypt(key, nonce, pt, aad)) ==
      "e19e646c4637c628d6e057926818605785ee61561639f152b034af31")

  test("ChaCha20-Poly1305 encrypt — no AAD differs from with-AAD"):
    val key   = (0 until 32).map(_.toByte).toArray
    val nonce = (0 until 12).map(_.toByte).toArray
    val pt    = "hello chacha".getBytes("UTF-8")
    assert(hex(backend.chacha20Poly1305Encrypt(key, nonce, pt, Array.emptyByteArray)) ==
      "e19e646c4637c628d6e05792005b3af4cb630f13c58863e558ab4b80")

  test("ChaCha20-Poly1305 encrypt — empty plaintext with aad='wc-aad' yields 16-byte tag"):
    val key   = (0 until 32).map(_.toByte).toArray
    val nonce = (0 until 12).map(_.toByte).toArray
    val aad   = "wc-aad".getBytes("UTF-8")
    assert(hex(backend.chacha20Poly1305Encrypt(key, nonce, Array.emptyByteArray, aad)) ==
      "ca698b7e1cb89fb733cb230b5bcc3b54")

  test("ChaCha20-Poly1305 round-trip — encrypt then decrypt recovers plaintext"):
    val key   = (0 until 32).map(_.toByte).toArray
    val nonce = (0 until 12).map(_.toByte).toArray
    val aad   = "wc-aad".getBytes("UTF-8")
    val pt    = "hello chacha".getBytes("UTF-8")
    val ct    = backend.chacha20Poly1305Encrypt(key, nonce, pt, aad)
    val back  = backend.chacha20Poly1305Decrypt(key, nonce, ct, aad)
    assert(back.toSeq == pt.toSeq)

  test("ChaCha20-Poly1305 decrypt rejects tag-tampered ciphertext with CryptoIntegrityException"):
    val key   = (0 until 32).map(_.toByte).toArray
    val nonce = (0 until 12).map(_.toByte).toArray
    val ct    = backend.chacha20Poly1305Encrypt(key, nonce, "hello".getBytes("UTF-8"), Array.emptyByteArray)
    ct(ct.length - 1) = (ct(ct.length - 1) ^ 0xff).toByte
    intercept[scalascript.crypto.CryptoIntegrityException](
      backend.chacha20Poly1305Decrypt(key, nonce, ct, Array.emptyByteArray)
    )

  test("X25519 publicKeyFromPrivate — fixed priv 0x77…11 → known pub"):
    val priv = fromHex("7700000000000000000000000000000000000000000000000000000000000011")
    val pub  = backend.x25519PublicKeyFromPrivate(priv)
    assert(hex(pub) == "e17edabbd5a6c59dc0be8c65fbf82fa5af7e3f713f8b8bb5d75a15a9f598124a")

  test("X25519 ECDH — fixed (priv 0x77…11) × (pub 0x6e05…34) = known shared secret"):
    val priv  = fromHex("7700000000000000000000000000000000000000000000000000000000000011")
    val peer  = fromHex("6e05dab301f42ee8e5692585e42eb6096ff1d6f4a9be4580e9341978d6a47234")
    val sec   = backend.x25519DeriveSharedSecret(priv, peer)
    assert(hex(sec) == "214ee1354d46a9aca229764106794327fe5ec847deba3aac523bbeee0a42db66")

  test("X25519 ECDH symmetry — dh(a.priv, b.pub) == dh(b.priv, a.pub)"):
    val (aPriv, aPub) = backend.x25519GenerateKeypair()
    val (bPriv, bPub) = backend.x25519GenerateKeypair()
    val ab = backend.x25519DeriveSharedSecret(aPriv, bPub)
    val ba = backend.x25519DeriveSharedSecret(bPriv, aPub)
    assert(ab.toSeq == ba.toSeq)
    assert(ab.length == 32)

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
