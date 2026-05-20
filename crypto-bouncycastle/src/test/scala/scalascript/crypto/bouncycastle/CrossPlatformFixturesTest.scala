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
