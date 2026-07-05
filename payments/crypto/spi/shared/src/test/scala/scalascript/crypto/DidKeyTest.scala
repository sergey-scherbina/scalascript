package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** did:key encode/resolve for Ed25519 + P-256, and the base58btc codec, on JVM and Scala.js. */
class DidKeyTest extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString

  test("base58btc encodes the hand-verifiable vectors and preserves leading zeros") {
    Base58.encode(Array.emptyByteArray)              shouldBe ""
    Base58.encode(Array[Byte](0))                    shouldBe "1"
    Base58.encode(Array[Byte](0, 0))                 shouldBe "11"
    Base58.encode(Array[Byte](1))                    shouldBe "2"     // alphabet[1]
    Base58.encode(Array[Byte](0, 1))                 shouldBe "12"    // one leading zero + value 1
    Base58.encode(Array[Byte](0xff.toByte))          shouldBe "5Q"    // 255 = 58*4 + 23
  }

  test("base58btc round-trips arbitrary bytes (incl. leading zeros)") {
    val samples = Seq("", "00", "0001", "ff", "0000deadbeef", "6b17d1f2e12c4247f8bce6e5")
    samples.foreach { h =>
      val b = unhex(h)
      hex(Base58.decode(Base58.encode(b))) shouldBe h
    }
  }

  private val edPub  = Ed25519.derivePublicKey(unhex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))
  private val p256   = P256Ecdsa.derivePublicCompressed(unhex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721"))

  test("Ed25519 did:key uses the z6Mk prefix and round-trips") {
    val did = DidKey.fromEd25519(edPub)
    did should startWith("did:key:z6Mk")                              // W3C multicodec-0xed01 invariant
    DidKey.resolve(did) match
      case Some(DidKey.Ed25519(pub)) => pub shouldBe edPub
      case other => fail(s"expected Ed25519, got $other")
  }

  test("P-256 did:key uses the zDn prefix and round-trips (compressed key)") {
    val did = DidKey.fromP256Compressed(p256)
    did should startWith("did:key:zDn")                               // W3C multicodec-0x1200 invariant
    DidKey.resolve(did) match
      case Some(DidKey.P256Compressed(pub)) => pub shouldBe p256
      case other => fail(s"expected P256Compressed, got $other")
  }

  test("resolve rejects a non-did:key string and unknown method-specific ids") {
    DidKey.resolve("did:web:example.com")     shouldBe None
    DidKey.resolve("did:key:zNotBase58_0OIl") shouldBe None           // invalid base58 chars
    DidKey.resolve("nonsense")                shouldBe None
  }
