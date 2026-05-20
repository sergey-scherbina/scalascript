package scalascript.wallet.vault.ledger.jvm

import org.scalatest.funsuite.AnyFunSuite

class HidFramingTest extends AnyFunSuite:

  test("encode produces a single 64-byte frame for short APDUs"):
    val apdu = Array.tabulate[Byte](7)(i => i.toByte)
    val out  = HidFraming.encode(apdu)
    assert(out.length == 1)
    val f = out(0)
    assert(f.length == 64)
    // Header: CID 0x0101, tag 0x05, seq 0x0000
    assert((f(0) & 0xff) == 0x01 && (f(1) & 0xff) == 0x01)
    assert(f(2) == 0x05)
    assert(f(3) == 0 && f(4) == 0)
    // Length prefix: 0x0007
    assert(f(5) == 0 && f(6) == 7)
    // Payload follows
    var i = 0
    while i < 7 do
      assert(f(7 + i) == i.toByte)
      i += 1

  test("encode splits a long APDU into multiple frames with seq numbers"):
    // 200 B payload: frame 0 carries 57 B, frames 1-3 carry 59 each = 234 capacity ≥ 200 ⇒ need ceil((200-57)/59)+1 = 4 frames
    val apdu = Array.tabulate[Byte](200)(i => (i % 256).toByte)
    val out  = HidFraming.encode(apdu)
    assert(out.length == 4, s"expected 4 frames, got ${out.length}")
    // each is 64 B
    assert(out.forall(_.length == 64))
    // sequence numbers are 0, 1, 2, 3
    var i = 0
    while i < out.length do
      assert((out(i)(3) & 0xff) == 0)
      assert((out(i)(4) & 0xff) == i)
      i += 1
    // length prefix on first frame
    assert((out(0)(5) & 0xff) == 0 && (out(0)(6) & 0xff) == 200)

  test("encode/decode round-trips short APDUs"):
    val apdu = "Hello Ledger".getBytes("UTF-8")
    val frames = HidFraming.encode(apdu)
    val back   = HidFraming.decode(frames.toSeq)
    assert(back.sameElements(apdu))

  test("encode/decode round-trips long APDUs"):
    val apdu = new Array[Byte](1000)
    var i = 0
    while i < apdu.length do { apdu(i) = ((i * 17) % 256).toByte; i += 1 }
    val frames = HidFraming.encode(apdu)
    val back   = HidFraming.decode(frames.toSeq)
    assert(back.length == apdu.length)
    assert(back.sameElements(apdu))

  test("encode pads the last frame to 64 B"):
    val apdu   = Array.tabulate[Byte](58)(_.toByte)  // first-frame capacity is 57 → 2 frames
    val frames = HidFraming.encode(apdu)
    assert(frames.length == 2)
    assert(frames(1).length == 64)
    // trailing bytes after the 1-byte real payload should be zero-padded
    var i = HidFraming.HeaderSize + 1
    while i < HidFraming.FrameSize do
      assert(frames(1)(i) == 0, s"byte $i not zero-padded")
      i += 1

  test("decode rejects wrong channel id"):
    val good = HidFraming.encode(Array[Byte](1, 2, 3))(0)
    val bad  = good.clone()
    bad(0) = 0xAB.toByte
    intercept[IllegalArgumentException]:
      HidFraming.decode(Seq(bad))

  test("decode rejects wrong command tag"):
    val good = HidFraming.encode(Array[Byte](1, 2, 3))(0)
    val bad  = good.clone()
    bad(2) = 0x04
    intercept[IllegalArgumentException]:
      HidFraming.decode(Seq(bad))

  test("decode rejects out-of-order sequence numbers"):
    val frames = HidFraming.encode(new Array[Byte](200))
    val swapped = Seq(frames(0), frames(2), frames(1), frames(3))
    intercept[IllegalArgumentException]:
      HidFraming.decode(swapped)

  test("decode rejects truncated frame sequence"):
    val frames = HidFraming.encode(new Array[Byte](200))
    intercept[IllegalArgumentException]:
      HidFraming.decode(frames.take(2).toSeq)

  test("first-frame and continuation payload sizes match spec"):
    assert(HidFraming.FirstFramePayloadSize == 57)
    assert(HidFraming.ContFramePayloadSize  == 59)
    assert(HidFraming.HeaderSize            == 5)
    assert(HidFraming.FrameSize             == 64)
