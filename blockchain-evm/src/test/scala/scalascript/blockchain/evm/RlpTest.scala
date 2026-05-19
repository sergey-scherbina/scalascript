package scalascript.blockchain.evm

import org.scalatest.funsuite.AnyFunSuite

/** RLP encoder vectors from the Ethereum Yellow Paper appendix B and
 *  the published reference tests. */
class RlpTest extends AnyFunSuite:

  private def toHex(b: Array[Byte]): String =
    b.map(c => f"${c & 0xff}%02x").mkString

  test("single byte < 0x80 encodes as itself") {
    assert(toHex(Rlp.encodeBytes(Array(0x00.toByte))) == "00")
    assert(toHex(Rlp.encodeBytes(Array(0x7f.toByte))) == "7f")
    assert(toHex(Rlp.encodeBytes(Array(0x01.toByte))) == "01")
  }

  test("single byte >= 0x80 encodes with 0x81 prefix") {
    assert(toHex(Rlp.encodeBytes(Array(0x80.toByte))) == "8180")
    assert(toHex(Rlp.encodeBytes(Array(0xff.toByte))) == "81ff")
  }

  test("empty string encodes as 0x80") {
    assert(toHex(Rlp.encodeBytes(Array.emptyByteArray)) == "80")
  }

  test("\"dog\" encodes per Yellow Paper") {
    // \"dog\" → 0x83 || \"dog\"
    val out = Rlp.encodeBytes("dog".getBytes("UTF-8"))
    assert(toHex(out) == "83646f67")
  }

  test("[\"cat\", \"dog\"] list encodes per Yellow Paper") {
    // 0xc8 || 0x83 \"cat\" || 0x83 \"dog\"
    val out = Rlp.encode(Rlp.list(
      Rlp.bytes("cat".getBytes("UTF-8")),
      Rlp.bytes("dog".getBytes("UTF-8")),
    ))
    assert(toHex(out) == "c88363617483646f67")
  }

  test("empty list encodes as 0xc0") {
    assert(toHex(Rlp.encode(Rlp.Lst(Seq.empty))) == "c0")
  }

  test("uint 0 encodes as empty string (0x80)") {
    assert(toHex(Rlp.encodeUint(BigInt(0))) == "80")
  }

  test("uint 15 (0x0f) encodes as 0x0f") {
    assert(toHex(Rlp.encodeUint(BigInt(15))) == "0f")
  }

  test("uint 1024 (0x0400) encodes as 82 04 00") {
    assert(toHex(Rlp.encodeUint(BigInt(1024))) == "820400")
  }

  test("long string (>55 bytes) gets a length-of-length prefix") {
    // The lorem-ipsum example from the YP — first segment is 56 bytes
    val data = "Lorem ipsum dolor sit amet, consectetur adipisicing eli".getBytes("UTF-8")
    assert(data.length == 55)   // sanity check on length
    val r55 = Rlp.encodeBytes(data)
    // 55-byte string → 0xb7 (0x80 + 0x37) byte prefix
    assert((r55(0) & 0xff) == 0xb7)

    val data56 = (data :+ 'x'.toByte)
    assert(data56.length == 56)
    val r56 = Rlp.encodeBytes(data56)
    // 56-byte string → 0xb8 0x38 (length-of-length=1, len=56)
    assert((r56(0) & 0xff) == 0xb8)
    assert((r56(1) & 0xff) == 56)
  }
