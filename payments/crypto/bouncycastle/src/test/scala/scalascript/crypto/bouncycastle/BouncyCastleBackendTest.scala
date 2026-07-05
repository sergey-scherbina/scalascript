package scalascript.crypto.bouncycastle

import org.scalatest.funsuite.AnyFunSuite
import scalascript.crypto.*

/** Vector + round-trip tests for the JVM `CryptoBackend` impl. */
class BouncyCastleBackendTest extends AnyFunSuite:

  private val be = new BouncyCastleBackend

  // ── ServiceLoader discovery ────────────────────────────────────────────

  test("BouncyCastleBackend discoverable via ServiceLoader") {
    CryptoBackend.resetForTests()
    val discovered = CryptoBackend.all
    assert(discovered.nonEmpty, "ServiceLoader didn't find any backend")
    assert(discovered.exists(_.id == "bouncycastle-jvm"))
  }

  test("backend reports correct id and curve support") {
    assert(be.id == "bouncycastle-jvm")
    assert(be.supports(Curve.Secp256k1))
    assert(be.supports(Curve.Ed25519))
    assert(be.supports(Curve.P256))
    assert(!be.supports(Curve.Sr25519))
  }

  // ── hashes: vector tests ───────────────────────────────────────────────

  test("SHA-256 of empty string matches NIST vector") {
    val expected = hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assert(be.hash(HashAlgo.Sha256, Array.emptyByteArray).sameElements(expected))
  }

  test("SHA-256 of \"abc\" matches NIST vector") {
    val expected = hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assert(be.hash(HashAlgo.Sha256, "abc".getBytes("UTF-8")).sameElements(expected))
  }

  test("Keccak-256 of empty matches reference (Ethereum empty-data hash)") {
    val expected = hex("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
    assert(be.hash(HashAlgo.Keccak256, Array.emptyByteArray).sameElements(expected))
  }

  test("Keccak-256 of \"hello\" matches reference") {
    val expected = hex("1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8")
    assert(be.hash(HashAlgo.Keccak256, "hello".getBytes("UTF-8")).sameElements(expected))
  }

  test("portable Keccak256 reference == BouncyCastle backend (rate boundary + multi-block)") {
    val inputs = Seq(
      Array.emptyByteArray,
      "hello".getBytes("UTF-8"),
      "abc".getBytes("UTF-8"),
      Array.fill[Byte](135)('x'.toByte),   // rate - 1
      Array.fill[Byte](136)('y'.toByte),   // exactly the rate → forces an extra all-pad block
      Array.fill[Byte](137)('z'.toByte),   // rate + 1
      Array.fill[Byte](272)('w'.toByte),   // 2 * rate (multi-block)
      Array.tabulate[Byte](1000)(i => (i & 0xff).toByte)
    )
    inputs.foreach { in =>
      assert(Keccak256.hash(in).sameElements(be.hash(HashAlgo.Keccak256, in)),
        s"portable Keccak256 != BouncyCastle for input length ${in.length}")
    }
  }

  test("RIPEMD-160 of empty matches reference") {
    val expected = hex("9c1185a5c5e9fc54612808977ee8f548b2258d31")
    assert(be.hash(HashAlgo.Ripemd160, Array.emptyByteArray).sameElements(expected))
  }

  // ── BLAKE2b (RFC 7693) — both the BouncyCastle backend AND the pure-Scala reference ──

  test("BLAKE2b-256 matches reference vectors + the pure-Scala Blake2b reference") {
    val cases = List(
      ""    -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
      "abc" -> "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319",
    )
    for (in, out) <- cases do
      val data = in.getBytes("UTF-8")
      val exp  = hex(out)
      assert(be.hash(HashAlgo.Blake2b256, data).sameElements(exp), s"BC blake2b-256($in)")
      assert(Blake2b.hash256(data).sameElements(exp),              s"reference blake2b-256($in)")
    // 200 bytes — exercises the multi-block path; 128 bytes — the exact-one-block edge.
    val big = Array.tabulate(200)(i => (i & 0xff).toByte)
    val bigExp = hex("63c3d97a9f8894d5e043a707b0fee7f7ec4c049a23bbf1079df20b4165f9e22d")
    assert(be.hash(HashAlgo.Blake2b256, big).sameElements(bigExp), "BC blake2b-256(200B)")
    assert(Blake2b.hash256(big).sameElements(bigExp),              "reference blake2b-256(200B)")
    val blk = Array.fill(128)(0x41.toByte)
    val blkExp = hex("5db0e67323e93220e9602568a3c2c43f52dc843e4ea5b1e3deb9d5d80ed9cf2c")
    assert(be.hash(HashAlgo.Blake2b256, blk).sameElements(blkExp), "BC blake2b-256(128B)")
    assert(Blake2b.hash256(blk).sameElements(blkExp),              "reference blake2b-256(128B)")
  }

  test("BLAKE2b-224 matches reference vectors + the pure-Scala Blake2b reference") {
    val cases = List(
      ""    -> "836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07",
      "abc" -> "9bd237b02a29e43bdd6738afa5b53ff0eee178d6210b618e4511aec8",
    )
    for (in, out) <- cases do
      val data = in.getBytes("UTF-8")
      val exp  = hex(out)
      assert(be.hash(HashAlgo.Blake2b224, data).sameElements(exp), s"BC blake2b-224($in)")
      assert(Blake2b.hash224(data).sameElements(exp),              s"reference blake2b-224($in)")
  }

  test("HMAC-SHA-512 round-trips") {
    val mac1 = be.hmac(HashAlgo.HmacSha512, "key".getBytes, "data".getBytes)
    val mac2 = be.hmac(HashAlgo.HmacSha512, "key".getBytes, "data".getBytes)
    assert(mac1.sameElements(mac2))
    assert(mac1.length == 64)
  }

  // ── secp256k1 ──────────────────────────────────────────────────────────

  test("secp256k1 derivePublic from known private key (Vitalik's burner)") {
    // Private key from the EIP-712 reference docs:
    //   privkey  = 0x4646464646464646464646464646464646464646464646464646464646464646
    //   address  = 0x9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f
    val priv     = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub      = be.derivePublic(Curve.Secp256k1, priv)
    assert(pub.length == 64)
    // Public key x-coordinate must be non-zero
    assert(pub.take(32).exists(_ != 0))
  }

  test("secp256k1 sign + verify round-trip with HashAlgo.None") {
    val priv = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub  = be.derivePublic(Curve.Secp256k1, priv)
    val msg  = be.hash(HashAlgo.Keccak256, "hello world".getBytes("UTF-8"))
    val sig  = be.sign(Curve.Secp256k1, priv, msg, HashAlgo.None)
    assert(sig.length == 65)
    assert(be.verify(Curve.Secp256k1, pub, msg, sig, HashAlgo.None))
  }

  test("secp256k1 RFC-6979 deterministic — same input ⇒ identical signature") {
    val priv = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val msg  = hex("0000000000000000000000000000000000000000000000000000000000000001")
    val s1   = be.sign(Curve.Secp256k1, priv, msg, HashAlgo.None)
    val s2   = be.sign(Curve.Secp256k1, priv, msg, HashAlgo.None)
    assert(s1.sameElements(s2))
  }

  test("secp256k1 recoverPublic round-trips via sign") {
    val priv  = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub   = be.derivePublic(Curve.Secp256k1, priv)
    val msg   = be.hash(HashAlgo.Keccak256, "recover me".getBytes("UTF-8"))
    val sig   = be.sign(Curve.Secp256k1, priv, msg, HashAlgo.None)
    val recId = sig(64).toInt & 0xff
    val rec   = be.recoverPublic(Curve.Secp256k1, msg, sig, recId)
    assert(rec.sameElements(pub))
  }

  test("secp256k1 verify rejects tampered signature") {
    val priv = hex("4646464646464646464646464646464646464646464646464646464646464646")
    val pub  = be.derivePublic(Curve.Secp256k1, priv)
    val msg  = be.hash(HashAlgo.Sha256, "msg".getBytes("UTF-8"))
    val sig  = be.sign(Curve.Secp256k1, priv, msg, HashAlgo.None)
    sig(10) = (sig(10) ^ 0x01).toByte
    assert(!be.verify(Curve.Secp256k1, pub, msg, sig, HashAlgo.None))
  }

  // ── ed25519 ────────────────────────────────────────────────────────────

  test("ed25519 sign + verify round-trip") {
    val priv = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val pub  = be.derivePublic(Curve.Ed25519, priv)
    val msg  = Array.emptyByteArray
    val sig  = be.sign(Curve.Ed25519, priv, msg, HashAlgo.None)
    assert(sig.length == 64)
    assert(be.verify(Curve.Ed25519, pub, msg, sig, HashAlgo.None))
  }

  test("ed25519 RFC 8032 test vector 1") {
    // RFC 8032 §7.1 test vector 1: priv = 0x9d61...7f60, msg = "" (empty)
    // Expected sig = e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b
    val priv = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val expectedPub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val pub = be.derivePublic(Curve.Ed25519, priv)
    assert(pub.sameElements(expectedPub))
    val sig = be.sign(Curve.Ed25519, priv, Array.emptyByteArray, HashAlgo.None)
    val expected = hex(
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )
    assert(sig.sameElements(expected))
  }

  // ── P-256 ──────────────────────────────────────────────────────────────

  test("p256 sign + verify round-trip with SHA-256") {
    val priv = be.randomBytes(32)
    // ensure within curve order (very high prob with random 32 bytes)
    if priv(0) == 0 then priv(0) = 1
    val pub = be.derivePublic(Curve.P256, priv)
    val msg = "passkey-signed payload".getBytes("UTF-8")
    val sig = be.sign(Curve.P256, priv, msg, HashAlgo.Sha256)
    assert(sig.length == 64)
    assert(be.verify(Curve.P256, pub, msg, sig, HashAlgo.Sha256))
  }

  // ── BIP-32 / SLIP-0010 ─────────────────────────────────────────────────

  test("BIP-32 master from test-vector 1 seed (000102…0f)") {
    // BIP-32 appendix C, test vector 1, seed = 000102030405060708090a0b0c0d0e0f
    val seed = hex("000102030405060708090a0b0c0d0e0f")
    val master = be.deriveMaster(Curve.Secp256k1, seed)
    val expectedChainCode =
      hex("873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508")
    val expectedPriv =
      hex("e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35")
    assert(master.chainCode.sameElements(expectedChainCode))
    assert(master.privateKey.sameElements(expectedPriv))
  }

  test("BIP-32 hardened child derivation matches test-vector 1") {
    // m/0H from BIP-32 test vector 1
    val seed   = hex("000102030405060708090a0b0c0d0e0f")
    val master = be.deriveMaster(Curve.Secp256k1, seed)
    val child  = be.deriveChild(Curve.Secp256k1, master, 0L, hardened = true)
    val expectedPriv =
      hex("edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea")
    val expectedChainCode =
      hex("47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141")
    assert(child.privateKey.sameElements(expectedPriv))
    assert(child.chainCode.sameElements(expectedChainCode))
  }

  test("BIP-32 non-hardened child m/0H/1 matches test-vector 1 (exercises compressPublic)") {
    // m/0H/1 from BIP-32 appendix C test vector 1 (non-hardened — uses the parent public key).
    val seed   = hex("000102030405060708090a0b0c0d0e0f")
    val master = be.deriveMaster(Curve.Secp256k1, seed)
    val c0h    = be.deriveChild(Curve.Secp256k1, master, 0L, hardened = true)
    val c0h1   = be.deriveChild(Curve.Secp256k1, c0h, 1L, hardened = false)
    assert(c0h1.privateKey.sameElements(hex("3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368")))
    assert(c0h1.chainCode.sameElements(hex("2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19")))
  }

  test("SLIP-0010 ed25519 master from test-vector 1 (000102…0f)") {
    // SLIP-0010 appendix B test vector 1 for ed25519
    val seed = hex("000102030405060708090a0b0c0d0e0f")
    val master = be.deriveMaster(Curve.Ed25519, seed)
    val expectedPriv =
      hex("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7")
    val expectedChainCode =
      hex("90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb")
    assert(master.privateKey.sameElements(expectedPriv))
    assert(master.chainCode.sameElements(expectedChainCode))
  }

  test("SLIP-0010 ed25519 m/0H derives expected child") {
    val seed   = hex("000102030405060708090a0b0c0d0e0f")
    val master = be.deriveMaster(Curve.Ed25519, seed)
    val child  = be.deriveChild(Curve.Ed25519, master, 0L, hardened = true)
    val expectedPriv =
      hex("68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3")
    val expectedChainCode =
      hex("8b59aa11380b624e81507a27fedda59fea6d0b779a778918a2fd3590e16e9c69")
    assert(child.privateKey.sameElements(expectedPriv))
    assert(child.chainCode.sameElements(expectedChainCode))
  }

  // ── AES-GCM ────────────────────────────────────────────────────────────

  test("AES-GCM encrypt+decrypt round-trip") {
    val key   = be.randomBytes(32)
    val iv    = be.randomBytes(12)
    val plain = "secret seed".getBytes("UTF-8")
    val aad   = "context".getBytes("UTF-8")
    val ct    = be.aesGcmEncrypt(key, iv, plain, aad)
    val pt    = be.aesGcmDecrypt(key, iv, ct, aad)
    assert(pt.sameElements(plain))
  }

  test("AES-GCM tamper detection") {
    val key = be.randomBytes(32)
    val iv  = be.randomBytes(12)
    val ct  = be.aesGcmEncrypt(key, iv, "plaintext".getBytes("UTF-8"), Array.emptyByteArray)
    ct(0)   = (ct(0) ^ 0x01).toByte
    intercept[Exception] {
      be.aesGcmDecrypt(key, iv, ct, Array.emptyByteArray)
    }
  }

  // ── KDFs ───────────────────────────────────────────────────────────────

  test("PBKDF2 known test vector (RFC 6070 #2)") {
    // RFC 6070 test vector: P="password", S="salt", c=2, dkLen=20
    val out = be.pbkdf2("password".getBytes("UTF-8"), "salt".getBytes("UTF-8"), 2, 20, HashAlgo.Sha256)
    // SHA-256 variant (NIST SP 800-132 / RFC 8018):
    //   = ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43
    val expected = hex("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43")
    // ours is 20 bytes (we asked for 20); compare prefix
    assert(out.sameElements(expected.take(20)))
  }

  test("Argon2id produces 32 bytes deterministically for the same input") {
    val a = be.argon2id("pw".getBytes("UTF-8"), "salt-1234567890".getBytes("UTF-8"), 1024, 2, 1, 32)
    val b = be.argon2id("pw".getBytes("UTF-8"), "salt-1234567890".getBytes("UTF-8"), 1024, 2, 1, 32)
    assert(a.length == 32)
    assert(a.sameElements(b))
  }

  test("HKDF-SHA256 derives the right length") {
    val out = be.hkdf("ikm".getBytes("UTF-8"), "salt".getBytes("UTF-8"), "info".getBytes("UTF-8"), 42, HashAlgo.Sha256)
    assert(out.length == 42)
  }

  // ── utility ────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
