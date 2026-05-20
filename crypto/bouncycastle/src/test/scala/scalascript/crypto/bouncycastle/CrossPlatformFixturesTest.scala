package scalascript.crypto.bouncycastle

import org.scalatest.funsuite.AnyFunSuite
import scalascript.crypto.{Curve, HashAlgo}

/** Cross-platform conformance guard: asserts that the JVM BouncyCastle
 *  backend produces byte-identical output to the Scala.js `crypto-noble-js`
 *  backend for a shared set of fixtures. The matching JS-side spec lives
 *  in `crypto-noble-js/src/test/scala/.../NobleCryptoBackendTest.scala`;
 *  the two files MUST stay in sync — any divergence in hex strings
 *  between them is a contract bug. */
class CrossPlatformFixturesTest extends AnyFunSuite:

  private val backend = new BouncyCastleBackend

  private def hex(b: Array[Byte]): String =
    val sb = new StringBuilder(b.length * 2)
    for x <- b do sb.append(f"${x & 0xff}%02x")
    sb.result()

  private def fromHex(s: String): Array[Byte] =
    val out = new Array[Byte](s.length / 2)
    var i   = 0
    while i < out.length do
      out(i) = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  test("Sha256 of empty matches noble fixture"):
    assert(hex(backend.hash(HashAlgo.Sha256, Array.emptyByteArray)) ==
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

  test("Keccak256 of empty matches noble fixture"):
    assert(hex(backend.hash(HashAlgo.Keccak256, Array.emptyByteArray)) ==
      "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")

  test("Ed25519 RFC 8032 vector 1 — derivePublic"):
    val priv = fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    assert(hex(backend.derivePublic(Curve.Ed25519, priv)) ==
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

  test("Ed25519 RFC 8032 vector 1 — sign empty msg"):
    val priv = fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val sig  = backend.sign(Curve.Ed25519, priv, Array.emptyByteArray, HashAlgo.None)
    assert(hex(sig) ==
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f" +
      "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")

  test("secp256k1 derive — matches noble fixture for 0x1234... privkey"):
    val priv = fromHex("1234567890123456789012345678901234567890123456789012345678901234")
    val pub  = backend.derivePublic(Curve.Secp256k1, priv)
    assert(pub.length == 64)
    assert(hex(pub) ==
      "e90c7d3640a1568839c31b70a893ab6714ef8415b9de90cedfc1c8f353a6983e" +
      "625529392df7fa514bdd65a2003f6619567d79bee89830e63e932dbd42362d34")

  test("EVM ecrecover address — privkey 0x4646... → 0x9d8a62f656..."):
    val priv    = fromHex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub     = backend.derivePublic(Curve.Secp256k1, priv)
    val addrHex = hex(backend.hash(HashAlgo.Keccak256, pub).drop(12))
    assert(addrHex == "9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f")

  test("P256 derive — matches noble fixture for 0x0011...ff privkey"):
    val priv = fromHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
    val pub  = backend.derivePublic(Curve.P256, priv)
    assert(pub.length == 64)
    assert(hex(pub) ==
      "798953e7e8134fdf3c139f63d3fbccc252a28b6ca5059e618374a81231240f3f" +
      "c83267aec725e18b66176c3685d1257201a67033819585a22a296350159ae70b")

  // ── Stage 5 fixtures — KDF + AEAD parity ────────────────────────────────
  //
  // These hex strings MUST match
  // crypto-noble-js/src/test/scala/.../NobleCryptoBackendTest.scala
  // (and `wallet-vault-encrypted/shared/src/test/.../`) byte-for-byte —
  // they are the cross-platform contract for the Stage 5 KDF + AEAD
  // primitives.  Generated from BouncyCastle once; reproduced here so a
  // green run on each platform asserts the same bytes.

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
    assert(ct.length == 16 * 1024 + 16)  // plaintext + 16-byte GCM tag
    val pt = backend.aesGcmDecrypt(key, iv, ct, Array.empty[Byte])
    assert(java.util.Arrays.equals(pt, big))

  test("AES-GCM decrypt rejects ciphertext tampered in the auth tag"):
    val key = (0 until 32).map(_.toByte).toArray
    val iv  = (0 until 12).map(_.toByte).toArray
    val ct  = backend.aesGcmEncrypt(key, iv, "hello".getBytes("UTF-8"), Array.empty[Byte])
    ct(ct.length - 1) = (ct(ct.length - 1) ^ 0xff).toByte
    val ex = intercept[Exception](backend.aesGcmDecrypt(key, iv, ct, Array.empty[Byte]))
    assert(ex != null)

  // ── Stage 6 fixtures — ChaCha20-Poly1305 + X25519 parity ─────────────────
  //
  // Same contract as the Stage 5 fixtures above: hex strings MUST stay
  // bit-for-bit in sync between this file and the noble-side
  // NobleCryptoBackendTest.  Generated once from BouncyCastle / JCE and
  // reproduced on the @noble/ciphers + @noble/curves side.

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
