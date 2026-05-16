package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WsFramingTest extends AnyFunSuite with Matchers:

  test("acceptKey — RFC 6455 §1.3 worked example") {
    // The RFC quotes:
    //   client key:  dGhlIHNhbXBsZSBub25jZQ==
    //   accept:      s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
    WsFraming.acceptKey("dGhlIHNhbXBsZSBub25jZQ==") shouldBe "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
  }

  test("tryParse — masked client text frame, short payload") {
    // FIN=1, opcode=text, MASK=1, len=5, mask=[1,2,3,4],
    // payload "Hello" XOR'd with the rotating mask.
    val payload = "Hello".getBytes("UTF-8")
    val mask    = Array[Byte](1, 2, 3, 4)
    val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
    val frame   = Array[Byte](0x81.toByte, 0x85.toByte) ++ mask ++ masked
    val parsed  = WsFraming.tryParse(frame, 0, frame.length).get
    parsed.fin shouldBe true
    parsed.opcode shouldBe WsFraming.Opcode.Text
    parsed.textPayload shouldBe "Hello"
    parsed.consumed shouldBe frame.length
  }

  test("tryParse — returns None when buffer is short") {
    val frame = Array[Byte](0x81.toByte, 0x85.toByte, 1, 2) // only 4 of 11 bytes
    WsFraming.tryParse(frame, 0, frame.length) shouldBe None
  }

  test("tryParse — 16-bit extended length") {
    val payload = ("x" * 300).getBytes("UTF-8") // > 125, fits in 16-bit
    val mask    = Array[Byte](0xA, 0xB, 0xC, 0xD)
    val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
    val len     = payload.length
    val hdr = Array[Byte](
      0x81.toByte,                       // FIN | text
      (0x80 | 126).toByte,               // MASK | len7=126
      ((len >> 8) & 0xFF).toByte,
      (len & 0xFF).toByte
    )
    val frame = hdr ++ mask ++ masked
    val parsed = WsFraming.tryParse(frame, 0, frame.length).get
    parsed.payload.length shouldBe len
    parsed.textPayload shouldBe ("x" * 300)
  }

  test("encodeText — round-trips through tryParse (server-side, no mask)") {
    val bytes  = WsFraming.encodeText("hi 👋")
    val parsed = WsFraming.tryParse(bytes, 0, bytes.length).get
    parsed.opcode shouldBe WsFraming.Opcode.Text
    parsed.textPayload shouldBe "hi 👋"
  }

  test("encodeClose — payload is 2-byte BE status + reason") {
    val bytes  = WsFraming.encodeClose(1001, "bye")
    val parsed = WsFraming.tryParse(bytes, 0, bytes.length).get
    parsed.opcode shouldBe WsFraming.Opcode.Close
    parsed.payload.length shouldBe 5
    ((parsed.payload(0) & 0xFF) << 8 | (parsed.payload(1) & 0xFF)) shouldBe 1001
    new String(parsed.payload, 2, 3, "UTF-8") shouldBe "bye"
  }

  test("tryParse — unknown opcode throws WsProtocolError") {
    // opcode 0x3 is reserved for future non-control frames
    val frame = Array[Byte](0x83.toByte, 0x00)
    intercept[WsFraming.WsProtocolError] {
      WsFraming.tryParse(frame, 0, frame.length)
    }
  }
