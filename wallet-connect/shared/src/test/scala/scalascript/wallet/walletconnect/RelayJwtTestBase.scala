package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AnyFunSuite

import scalascript.crypto.CryptoBackend

/** Cross-platform spec for [[RelayJwt]] — EdDSA(ed25519) JWT signing,
 *  did:key encoding, base64url-no-pad framing.  Mid-level random keys
 *  come through `CryptoBackend.randomBytes` so the test stays
 *  platform-neutral. */
abstract class RelayJwtTestBase extends AnyFunSuite:

  // RFC 8032 test vector 1 — deterministic ed25519 keypair.  Lazy so
  // the `RelayJwt.publicKeyFromPrivate` call (which needs
  // `CryptoBackend.get()`) doesn't run until after `beforeAll`
  // registers the platform backend.
  private lazy val priv  = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
  private lazy val pub   = RelayJwt.publicKeyFromPrivate(priv)
  // Per RFC 8032 the matching pubkey is this exact value.
  private lazy val knownPub =
    hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

  test("publicKeyFromPrivate matches the RFC 8032 test vector") {
    assert(pub.toSeq == knownPub.toSeq)
  }

  test("didKey starts with z6Mk for ed25519 (multicodec ed01 prefix property)") {
    // Per W3C did:key spec, an ed25519 pubkey multicodec-prefixed with
    // 0xed01 always produces a base58btc encoding that begins with
    // "z6Mk" because the high bits of `ed01` map to that 4-char prefix
    // independently of the trailing pubkey bytes. Verify the property
    // holds for our RFC 8032 vector + a random key.
    val d1 = RelayJwt.didKey(pub)
    assert(d1.startsWith("did:key:z6Mk"), s"got $d1")
    val randPriv = CryptoBackend.get().randomBytes(32)
    val randPub  = RelayJwt.publicKeyFromPrivate(randPriv)
    val d2 = RelayJwt.didKey(randPub)
    assert(d2.startsWith("did:key:z6Mk"), s"got $d2")
  }

  test("didKey round-trip: base58btc decode of the suffix recovers ed01 || pubkey") {
    val didKey = RelayJwt.didKey(pub)
    val suffix = didKey.stripPrefix("did:key:z")
    val decoded = base58btcDecode(suffix)
    assert(decoded.length == 2 + 32, s"expected 34 B (ed01 + pubkey), got ${decoded.length}")
    assert(decoded(0) == 0xed.toByte && decoded(1) == 0x01.toByte,
      "multicodec prefix must be 0xed01 for ed25519")
    val recovered = decoded.drop(2)
    assert(recovered.toSeq == pub.toSeq, "trailing 32 B must round-trip back to the pubkey")
  }

  test("sign produces a three-segment JWT (header.payload.signature)") {
    val jwt = RelayJwt.sign(priv, pub, nowSeconds = 1700000000L)
    val parts = jwt.split('.')
    assert(parts.length == 3, s"expected 3 segments, got ${parts.length}")
    parts.foreach { p =>
      assert(!p.contains('='), "base64url must be unpadded")
      assert(!p.contains('+') && !p.contains('/'), "base64url alphabet only")
    }
  }

  test("JWT header decodes to {alg:EdDSA, typ:JWT}") {
    val jwt = RelayJwt.sign(priv, pub, nowSeconds = 1700000000L)
    val header = new String(b64uDecode(jwt.split('.')(0)))
    val obj    = ujson.read(header).obj
    assert(obj("alg").str == "EdDSA")
    assert(obj("typ").str == "JWT")
  }

  test("JWT payload includes iss/aud/iat/exp/sub claims") {
    val jwt = RelayJwt.sign(priv, pub, nowSeconds = 1700000000L,
                            ttlSeconds = 3600, subject = "topic-x")
    val payload = ujson.read(new String(b64uDecode(jwt.split('.')(1)))).obj
    assert(payload("iss").str.startsWith("did:key:z6Mk"))
    assert(payload("aud").str == RelayJwt.DefaultAud)
    assert(payload("iat").num.toLong == 1700000000L)
    assert(payload("exp").num.toLong == 1700000000L + 3600)
    assert(payload("sub").str == "topic-x")
  }

  test("JWT signature verifies against the issuer's public key") {
    val jwt   = RelayJwt.sign(priv, pub, nowSeconds = 1700000000L)
    val parts = jwt.split('.')
    val unsigned = parts(0) + "." + parts(1)
    val sig      = b64uDecode(parts(2))
    assert(RelayJwt.verify(unsigned, sig, pub),
      "ed25519 signature must verify against the public key from the iss claim")
  }

  test("a different private key produces a different signature") {
    val other = CryptoBackend.get().randomBytes(32)
    val a = RelayJwt.sign(priv,  pub,                              nowSeconds = 1700000000L)
    val b = RelayJwt.sign(other, RelayJwt.publicKeyFromPrivate(other), nowSeconds = 1700000000L)
    val aSig = a.split('.')(2)
    val bSig = b.split('.')(2)
    assert(aSig != bSig)
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    require(clean.length % 2 == 0)
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  private def b64uDecode(s: String): Array[Byte] =
    java.util.Base64.getUrlDecoder.decode(s)

  private val Base58Alphabet =
    "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

  private def base58btcDecode(s: String): Array[Byte] =
    val leadingZeros = s.takeWhile(_ == Base58Alphabet.head).length
    var n = BigInt(0)
    for ch <- s do
      val idx = Base58Alphabet.indexOf(ch)
      require(idx >= 0, s"non-base58 char: $ch")
      n = n * 58 + idx
    val raw = if n == 0 then Array.emptyByteArray else n.toByteArray.dropWhile(_ == 0)
    Array.fill[Byte](leadingZeros)(0) ++ raw
