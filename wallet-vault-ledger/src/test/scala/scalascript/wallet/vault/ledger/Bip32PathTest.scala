package scalascript.wallet.vault.ledger

import org.scalatest.funsuite.AnyFunSuite

class Bip32PathTest extends AnyFunSuite:

  test("parse strips leading m/ and identifies hardened segments"):
    val segs = Bip32Path.parse("m/44'/60'/0'/0/0")
    assert(segs.length == 5)
    assert(segs(0) == (44L | Bip32Path.Hardened))
    assert(segs(1) == (60L | Bip32Path.Hardened))
    assert(segs(2) == (0L  | Bip32Path.Hardened))
    assert(segs(3) == 0L)
    assert(segs(4) == 0L)

  test("parse handles empty path"):
    assert(Bip32Path.parse("m/").isEmpty)
    assert(Bip32Path.parse("m").isEmpty)
    assert(Bip32Path.parse("").isEmpty)

  test("encode produces 1 + 4*N bytes"):
    val enc = Bip32Path.encode("m/44'/60'/0'/0/0")
    // 1 (count) + 5*4 (segments) = 21 bytes
    assert(enc.length == 21)
    assert(enc(0) == 5.toByte)
    // first segment: 44 | 0x80000000 = 0x8000002C  (big-endian)
    assert((enc(1) & 0xff) == 0x80)
    assert((enc(2) & 0xff) == 0x00)
    assert((enc(3) & 0xff) == 0x00)
    assert((enc(4) & 0xff) == 0x2C)
    // second segment: 60 | 0x80000000 = 0x8000003C
    assert((enc(5) & 0xff) == 0x80)
    assert((enc(6) & 0xff) == 0x00)
    assert((enc(7) & 0xff) == 0x00)
    assert((enc(8) & 0xff) == 0x3C)
    // third segment: 0 | 0x80000000 = 0x80000000
    assert((enc(9)  & 0xff) == 0x80)
    assert((enc(10) & 0xff) == 0x00)
    assert((enc(11) & 0xff) == 0x00)
    assert((enc(12) & 0xff) == 0x00)
    // fourth segment: 0
    assert(enc(13) == 0 && enc(14) == 0 && enc(15) == 0 && enc(16) == 0)
    // fifth segment: 0
    assert(enc(17) == 0 && enc(18) == 0 && enc(19) == 0 && enc(20) == 0)

  test("encode handles Solana default path"):
    val enc = Bip32Path.encode("m/44'/501'/0'/0'")
    assert(enc.length == 1 + 4 * 4)
    assert(enc(0) == 4.toByte)
    // segment[1]: 501 | hardened
    val s1 = ((enc(5) & 0xff) << 24) | ((enc(6) & 0xff) << 16) |
             ((enc(7) & 0xff) << 8)  |  (enc(8) & 0xff)
    assert((s1 & 0xffffffffL) == (501L | Bip32Path.Hardened))

  test("parse rejects out-of-range components"):
    intercept[IllegalArgumentException]:
      Bip32Path.parse("m/2147483648'/0/0")  // 2^31 ≥ 0x80000000

  test("encode rejects > 10 segments"):
    intercept[IllegalArgumentException]:
      Bip32Path.encode("m/" + (1 to 11).map(_.toString).mkString("/"))
