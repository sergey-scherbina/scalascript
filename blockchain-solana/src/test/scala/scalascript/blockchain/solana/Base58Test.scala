package scalascript.blockchain.solana

import org.scalatest.funsuite.AnyFunSuite

/** Base58 vectors from the published Bitcoin / Solana ecosystem
 *  reference suites. */
class Base58Test extends AnyFunSuite:

  test("empty input round-trips to empty string") {
    assert(Base58.encode(Array.emptyByteArray) == "")
    assert(Base58.decode("").length == 0)
  }

  test("\"hello\" round-trips") {
    val bytes = "hello".getBytes("UTF-8")
    val enc   = Base58.encode(bytes)
    assert(enc == "Cn8eVZg")
    assert(new String(Base58.decode(enc), "UTF-8") == "hello")
  }

  test("single zero byte encodes as \"1\"") {
    assert(Base58.encode(Array[Byte](0)) == "1")
    assert(Base58.decode("1").toSeq == Seq[Byte](0))
  }

  test("leading zero bytes preserve as leading '1's") {
    val bytes = Array[Byte](0, 0, 0, 1, 2)
    val enc   = Base58.encode(bytes)
    assert(enc.startsWith("111"))
    assert(Base58.decode(enc).toSeq == bytes.toSeq)
  }

  test("32-byte all-zero pubkey encodes to canonical Solana \"system program\" address") {
    // The Solana System Program ID is 11111111111111111111111111111111 — 32
    // base58 '1's encoding 32 zero bytes.
    val zeros = new Array[Byte](32)
    val enc   = Base58.encode(zeros)
    assert(enc == "1" * 32)
    assert(Base58.decode(enc).toSeq == zeros.toSeq)
  }

  test("USDC mint round-trips: EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v") {
    val mint  = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    val bytes = Base58.decode(mint)
    assert(bytes.length == 32)
    assert(Base58.encode(bytes) == mint)
  }

  test("invalid characters raise IllegalArgumentException") {
    intercept[IllegalArgumentException] { Base58.decode("0invalid") }
    intercept[IllegalArgumentException] { Base58.decode("not_b58") }
  }

  test("round-trip arbitrary 32-byte values") {
    val rnd = new java.security.SecureRandom()
    var i = 0
    while i < 50 do
      val bytes = new Array[Byte](32)
      rnd.nextBytes(bytes)
      val enc = Base58.encode(bytes)
      assert(Base58.decode(enc).toSeq == bytes.toSeq, s"round-trip failed for $enc")
      i += 1
  }
