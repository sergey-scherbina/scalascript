package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WsReassemblerTest extends AnyFunSuite with Matchers:
  import WsFraming.Opcode
  import WsReassembler.Event

  private def frame(fin: Boolean, op: Opcode, payload: Array[Byte]): WsFraming.Frame =
    WsFraming.Frame(fin = fin, opcode = op, payload = payload, consumed = 0)

  test("feed — single Text frame with FIN=1 delivers immediately") {
    val r = new WsReassembler
    val f = frame(fin = true, op = Opcode.Text, payload = "hello".getBytes("UTF-8"))
    r.feed(f) match
      case Event.Deliver(op, bytes) =>
        op shouldBe Opcode.Text
        new String(bytes, "UTF-8") shouldBe "hello"
      case other => fail(s"expected Deliver, got $other")
  }

  test("feed — two-frame Text fragmentation delivers joined payload") {
    val r = new WsReassembler
    r.feed(frame(fin = false, op = Opcode.Text, payload = "He".getBytes("UTF-8"))) shouldBe Event.Buffered
    r.feed(frame(fin = true,  op = Opcode.Continuation, payload = "llo".getBytes("UTF-8"))) match
      case Event.Deliver(op, bytes) =>
        op shouldBe Opcode.Text
        new String(bytes, "UTF-8") shouldBe "Hello"
      case other => fail(s"expected Deliver, got $other")
  }

  test("feed — three-frame Binary fragmentation") {
    val r = new WsReassembler
    r.feed(frame(fin = false, op = Opcode.Binary,       payload = Array[Byte](0xCA.toByte))) shouldBe Event.Buffered
    r.feed(frame(fin = false, op = Opcode.Continuation, payload = Array[Byte](0xFE.toByte))) shouldBe Event.Buffered
    r.feed(frame(fin = true,  op = Opcode.Continuation, payload = Array[Byte](0xBA.toByte, 0xBE.toByte))) match
      case Event.Deliver(op, bytes) =>
        op shouldBe Opcode.Binary
        bytes.toList shouldBe List(0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte)
      case other => fail(s"expected Deliver, got $other")
  }

  test("feed — new Text data frame mid-fragment is a protocol error") {
    val r = new WsReassembler
    r.feed(frame(fin = false, op = Opcode.Text, payload = "x".getBytes)) shouldBe Event.Buffered
    r.feed(frame(fin = true,  op = Opcode.Text, payload = "y".getBytes)) shouldBe
      Event.ProtocolError(1002, "new data frame mid-fragment")
  }

  test("feed — Continuation without prior data frame is a protocol error") {
    val r = new WsReassembler
    r.feed(frame(fin = true, op = Opcode.Continuation, payload = "x".getBytes)) shouldBe
      Event.ProtocolError(1002, "continuation without prior data frame")
  }

  test("feed — oversize fragmented message → ProtocolError(1009)") {
    val r = new WsReassembler(maxFrameBytes = 16)
    r.feed(frame(fin = false, op = Opcode.Text, payload = ("x" * 20).getBytes)) shouldBe
      Event.ProtocolError(1009, "message too big")
  }

  test("feed — oversize via Continuation also fires 1009") {
    val r = new WsReassembler(maxFrameBytes = 8)
    r.feed(frame(fin = false, op = Opcode.Binary,       payload = ("a" * 4).getBytes)) shouldBe Event.Buffered
    r.feed(frame(fin = false, op = Opcode.Continuation, payload = ("b" * 5).getBytes)) shouldBe
      Event.ProtocolError(1009, "message too big")
  }

  test("feed — control opcodes (Ping/Pong/Close) throw IllegalArgumentException") {
    val r = new WsReassembler
    for op <- List(Opcode.Ping, Opcode.Pong, Opcode.Close) do
      val ex = intercept[IllegalArgumentException] {
        r.feed(frame(fin = true, op = op, payload = Array.emptyByteArray))
      }
      ex.getMessage should include ("control opcode")
  }

  test("feed — state resets after a delivered message; next fragment chain is independent") {
    val r = new WsReassembler
    r.feed(frame(fin = true, op = Opcode.Text, payload = "first".getBytes)) match
      case Event.Deliver(_, _) => ()
      case other => fail(s"expected Deliver, got $other")
    r.feed(frame(fin = false, op = Opcode.Binary, payload = "x".getBytes)) shouldBe Event.Buffered
    r.feed(frame(fin = true,  op = Opcode.Continuation, payload = "y".getBytes)) match
      case Event.Deliver(op, bytes) =>
        op shouldBe Opcode.Binary
        new String(bytes, "ISO-8859-1") shouldBe "xy"
      case other => fail(s"expected Deliver, got $other")
  }
