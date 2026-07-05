package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Cross-platform (JVM + Scala.js) vectors for the from-scratch [[Sha256]] / [[Ripemd160]] / [[HmacSha256]]
 *  portable hashes that back the secp256k1 stack. All values are the published NIST / RFC test vectors, so the
 *  suite pins correctness and JVM/JS byte-identity at once. */
class PortableHashesTest extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte] = s.getBytes("UTF-8")

  // ── SHA-256 (FIPS 180-4) ─────────────────────────────────────────────────────

  test("SHA-256(\"\")") {
    hex(Sha256.digest(Array.empty[Byte])) shouldBe
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }

  test("SHA-256(\"abc\")") {
    hex(Sha256.digest(utf8("abc"))) shouldBe
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  test("SHA-256 of a 56-byte message (multi-block edge)") {
    hex(Sha256.digest(utf8("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))) shouldBe
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
  }

  test("SHA-256 of 1,000,000 'a' (long-message stress)") {
    hex(Sha256.digest(Array.fill[Byte](1000000)('a'.toByte))) shouldBe
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
  }

  // ── RIPEMD-160 ────────────────────────────────────────────────────────────────

  test("RIPEMD-160(\"\")") {
    hex(Ripemd160.digest(Array.empty[Byte])) shouldBe "9c1185a5c5e9fc54612808977ee8f548b2258d31"
  }

  test("RIPEMD-160(\"abc\")") {
    hex(Ripemd160.digest(utf8("abc"))) shouldBe "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"
  }

  test("RIPEMD-160(\"a\")") {
    hex(Ripemd160.digest(utf8("a"))) shouldBe "0bdc9d2d256b3ee9daae347be6f4dc835a467ffe"
  }

  test("RIPEMD-160 of the alphabet") {
    hex(Ripemd160.digest(utf8("abcdefghijklmnopqrstuvwxyz"))) shouldBe
      "f71c27109c692c1b56bbdceb5b9d2865b3708dbc"
  }

  // ── HMAC-SHA256 (RFC 4231) ──────────────────────────────────────────────────

  test("HMAC-SHA256 RFC 4231 test case 2 (Jefe)") {
    hex(HmacSha256.mac(utf8("Jefe"), utf8("what do ya want for nothing?"))) shouldBe
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
  }

  test("HMAC-SHA256 RFC 4231 test case 1 (20-byte 0x0b key)") {
    hex(HmacSha256.mac(Array.fill[Byte](20)(0x0b.toByte), utf8("Hi There"))) shouldBe
      "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
  }

  test("HMAC-SHA256 with a >64-byte key (key gets pre-hashed)") {
    hex(HmacSha256.mac(Array.fill[Byte](131)(0xaa.toByte),
      utf8("Test Using Larger Than Block-Size Key - Hash Key First"))) shouldBe
      "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
  }

  // ── Bitcoin compositions ──────────────────────────────────────────────────────

  test("hash160 = RIPEMD160(SHA256(x)) on a known pubkey") {
    // hash160 of the 33-byte compressed pubkey 0x02..0x79be667e... (secp256k1 G)
    val gx = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    val pub = gx.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    hex(Ripemd160.digest(Sha256.digest(pub))) shouldBe "751e76e8199196d454941c45d1b3a323f1433bd6"
  }

  // ── Keccak-256 (Ethereum; original Keccak pad 0x01, not NIST SHA3) ──────────────

  test("Keccak-256(\"\") = Ethereum empty-data hash") {
    hex(Keccak256.hash(Array.empty[Byte])) shouldBe
      "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
  }

  test("Keccak-256(\"abc\")") {
    hex(Keccak256.hash(utf8("abc"))) shouldBe
      "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
  }

  test("Keccak-256(\"hello\")") {
    hex(Keccak256.hash(utf8("hello"))) shouldBe
      "1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8"
  }
