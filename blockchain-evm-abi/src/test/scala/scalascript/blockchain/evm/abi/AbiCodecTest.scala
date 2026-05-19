package scalascript.blockchain.evm.abi

import org.scalatest.funsuite.AnyFunSuite

/** Encode/decode vectors for the Solidity ABI codec.
 *
 *  Reference values are sourced from the published examples on the
 *  Solidity docs and from common contract calldata (USDC, Uniswap
 *  V3, Multicall3) that anyone can sanity-check via etherscan. */
class AbiCodecTest extends AnyFunSuite:

  private def toHex(b: Array[Byte]): String =
    b.map(c => f"${c & 0xff}%02x").mkString

  private def hex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out

  // ── primitives ────────────────────────────────────────────────────────

  test("uint256(0)") {
    val enc = AbiEncoder.encode(AbiType.UInt(256), AbiValue.UInt(256, BigInt(0)))
    assert(toHex(enc) == "0000000000000000000000000000000000000000000000000000000000000000")
  }

  test("uint256(1)") {
    val enc = AbiEncoder.encode(AbiType.UInt(256), AbiValue.UInt(256, BigInt(1)))
    assert(toHex(enc) == "0000000000000000000000000000000000000000000000000000000000000001")
  }

  test("uint256(max)") {
    val max = (BigInt(1) << 256) - 1
    val enc = AbiEncoder.encode(AbiType.UInt(256), AbiValue.UInt(256, max))
    assert(toHex(enc) == "f" * 64)
  }

  test("bool(true) and bool(false)") {
    assert(toHex(AbiEncoder.encode(AbiType.Bool, AbiValue.Bool(true)))  == "0" * 63 + "1")
    assert(toHex(AbiEncoder.encode(AbiType.Bool, AbiValue.Bool(false))) == "0" * 64)
  }

  test("address pads to 32 bytes") {
    val enc = AbiEncoder.encode(
      AbiType.Address,
      AbiValue.Address("0x1234567890abcdef1234567890abcdef12345678"),
    )
    assert(toHex(enc) == "000000000000000000000000" + "1234567890abcdef1234567890abcdef12345678")
  }

  test("int256(-1) is two's-complement all-ones") {
    val enc = AbiEncoder.encode(AbiType.SInt(256), AbiValue.SInt(256, BigInt(-1)))
    assert(toHex(enc) == "f" * 64)
  }

  test("bytes32 right-pads with zeros") {
    val enc = AbiEncoder.encode(
      AbiType.FixedBytes(32),
      AbiValue.FixedBytes(32, hex("aa" * 32)),
    )
    assert(toHex(enc) == "aa" * 32)
  }

  // ── dynamic ───────────────────────────────────────────────────────────

  test("bytes encodes length-prefix + right-padded payload") {
    val data = hex("deadbeef")
    val enc  = AbiEncoder.encode(AbiType.Bytes, AbiValue.Bytes(data))
    // 32-byte length (4) || data padded to 32 bytes
    assert(toHex(enc) == "00" * 31 + "04" + "deadbeef" + "00" * 28)
  }

  test("string encodes as utf-8 with length prefix") {
    val enc = AbiEncoder.encode(AbiType.Str, AbiValue.Str("hi"))
    // length 2 || "hi" (0x6869) right-padded
    assert(toHex(enc) == "00" * 31 + "02" + "6869" + "00" * 30)
  }

  test("uint256[] encodes length + tightly packed elements") {
    val enc = AbiEncoder.encode(
      AbiType.DynArray(AbiType.UInt(256)),
      AbiValue.Arr(Seq(
        AbiValue.UInt(256, BigInt(0xaa)),
        AbiValue.UInt(256, BigInt(0xbb)),
      )),
    )
    val expected =
      "00" * 31 + "02" +    // length = 2
      "00" * 31 + "aa" +    // values[0]
      "00" * 31 + "bb"      // values[1]
    assert(toHex(enc) == expected)
  }

  // ── canonical reference: ERC-20 transfer ──────────────────────────────

  test("ERC-20 transfer(address,uint256) calldata is selector 0xa9059cbb || args") {
    val cd = Abi.encodeFunctionCall(
      "transfer",
      Seq(AbiType.Address, AbiType.UInt(256)),
      Seq(
        AbiValue.Address("0x2222222222222222222222222222222222222222"),
        AbiValue.UInt(256, BigInt(1_000_000)),
      ),
    )
    val expected =
      "a9059cbb" +
      "000000000000000000000000" + "2222222222222222222222222222222222222222" +
      "00000000000000000000000000000000000000000000000000000000000f4240"
    assert(toHex(cd) == expected)
  }

  test("ERC-3009 transferWithAuthorization selector is 0xe3ee160e") {
    val sel = Selector.forFunction(
      "transferWithAuthorization",
      Seq(
        AbiType.Address, AbiType.Address,
        AbiType.UInt(256), AbiType.UInt(256), AbiType.UInt(256),
        AbiType.FixedBytes(32),
        AbiType.UInt(8),
        AbiType.FixedBytes(32), AbiType.FixedBytes(32),
      ),
    )
    assert(toHex(sel) == "e3ee160e")
  }

  // ── head/tail layout for mixed static/dynamic ─────────────────────────

  test("tuple (uint256, bytes) lays out head[offset for bytes] || tail") {
    val enc = AbiEncoder.encodeTuple(
      Seq(AbiType.UInt(256), AbiType.Bytes),
      Seq(
        AbiValue.UInt(256, BigInt(42)),
        AbiValue.Bytes(hex("01020304")),
      ),
    )
    val expected =
      "0" * 62 + "2a" +                          // uint256(42)
      "0" * 62 + "40" +                          // offset to bytes = 0x40
      "0" * 63 + "4" +                           // bytes length = 4
      "01020304" + "0" * 56                      // payload padded
    assert(toHex(enc) == expected)
  }

  // ── round-trips ───────────────────────────────────────────────────────

  test("round-trip: uint256") {
    val v = AbiValue.UInt(256, BigInt(12345))
    val enc = Abi.encode(AbiType.UInt(256), v)
    assert(Abi.decode(AbiType.UInt(256), enc) == v)
  }

  test("round-trip: address") {
    val v = AbiValue.Address("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
    val enc = Abi.encode(AbiType.Address, v)
    val decoded = Abi.decode(AbiType.Address, enc).asInstanceOf[AbiValue.Address]
    assert(decoded.value.equalsIgnoreCase(v.value))
  }

  test("round-trip: bytes") {
    val v = AbiValue.Bytes(hex("01020304050607"))
    val enc = Abi.encode(AbiType.Bytes, v)
    val decoded = Abi.decode(AbiType.Bytes, enc).asInstanceOf[AbiValue.Bytes]
    assert(decoded.value.sameElements(v.value))
  }

  test("round-trip: string") {
    val v = AbiValue.Str("héllo, world! Привет 🌍")
    val enc = Abi.encode(AbiType.Str, v)
    val decoded = Abi.decode(AbiType.Str, enc).asInstanceOf[AbiValue.Str]
    assert(decoded.value == v.value)
  }

  test("round-trip: tuple (uint256, address, bytes)") {
    val types  = Seq(AbiType.UInt(256), AbiType.Address, AbiType.Bytes)
    val values = Seq(
      AbiValue.UInt(256, BigInt(99)),
      AbiValue.Address("0x1234567890abcdef1234567890abcdef12345678"),
      AbiValue.Bytes(hex("aabbccddee")),
    )
    val enc = AbiEncoder.encodeTuple(types, values)
    val decoded = AbiDecoder.decodeTuple(types, enc)
    assert(decoded.size == 3)
    assert(decoded(0) == values(0))
    val addr = decoded(1).asInstanceOf[AbiValue.Address]
    assert(addr.value.equalsIgnoreCase("0x1234567890abcdef1234567890abcdef12345678"))
    val bytesVal = decoded(2).asInstanceOf[AbiValue.Bytes]
    assert(bytesVal.value.sameElements(hex("aabbccddee")))
  }

  test("round-trip: dynamic array of uint256") {
    val typ = AbiType.DynArray(AbiType.UInt(256))
    val v   = AbiValue.Arr(Seq(
      AbiValue.UInt(256, BigInt(1)),
      AbiValue.UInt(256, BigInt(2)),
      AbiValue.UInt(256, BigInt(3)),
    ))
    val enc = Abi.encode(typ, v)
    val decoded = Abi.decode(typ, enc).asInstanceOf[AbiValue.Arr]
    assert(decoded.values.size == 3)
    assert(decoded.values == v.values)
  }
