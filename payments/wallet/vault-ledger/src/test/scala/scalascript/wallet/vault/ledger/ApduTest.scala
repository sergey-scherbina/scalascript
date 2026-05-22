package scalascript.wallet.vault.ledger

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class ApduTest extends AnyFunSuite:

  test("command encodes a short APDU"):
    val apdu = Apdu.command(0xE0, 0x02, 0x00, 0x00, Array[Byte](1, 2, 3))
    assert(apdu.length == 5 + 3)
    assert((apdu(0) & 0xff) == 0xE0)
    assert((apdu(1) & 0xff) == 0x02)
    assert((apdu(2) & 0xff) == 0x00)
    assert((apdu(3) & 0xff) == 0x00)
    assert((apdu(4) & 0xff) == 0x03)
    assert(apdu(5) == 1 && apdu(6) == 2 && apdu(7) == 3)

  test("command encodes an empty APDU"):
    val apdu = Apdu.command(0xB0, 0x01, 0x00, 0x00, Array.emptyByteArray)
    assert(apdu.length == 5)
    assert((apdu(4) & 0xff) == 0)

  test("command rejects data > 255 B"):
    intercept[IllegalArgumentException]:
      Apdu.command(0xE0, 0x02, 0x00, 0x00, new Array[Byte](256))

  test("command rejects out-of-range cla / ins / p1 / p2"):
    intercept[IllegalArgumentException](Apdu.command(256, 0, 0, 0, Array.emptyByteArray))
    intercept[IllegalArgumentException](Apdu.command(0, -1, 0, 0, Array.emptyByteArray))
    intercept[IllegalArgumentException](Apdu.command(0, 0, 999, 0, Array.emptyByteArray))
    intercept[IllegalArgumentException](Apdu.command(0, 0, 0, 256, Array.emptyByteArray))

  test("parseResponse splits payload from status word"):
    val resp = Array[Byte](0x11, 0x22, 0x33, 0x90.toByte, 0x00)
    val (sw, payload) = Apdu.parseResponse(resp)
    assert(sw == 0x9000)
    assert(payload.sameElements(Array[Byte](0x11, 0x22, 0x33)))

  test("parseResponse handles empty payload"):
    val (sw, payload) = Apdu.parseResponse(Array[Byte](0x69.toByte, 0x85.toByte))
    assert(sw == 0x6985)
    assert(payload.isEmpty)

  test("parseResponse rejects truncated input"):
    intercept[IllegalArgumentException]:
      Apdu.parseResponse(Array[Byte](0x90.toByte))

  test("chunkedSend sends a single APDU for small payloads"):
    val t = MockTransport()
    t.queueOk(Array[Byte](0x42))
    val payload = Array[Byte](1, 2, 3, 4, 5)
    val (sw, resp) =
      Await.result(Apdu.chunkedSend(t, 0xE0, 0x04, 0x00, 0x80, 0x00, payload), 1.second)
    assert(sw == Apdu.Sw_Ok)
    assert(resp.sameElements(Array[Byte](0x42)))
    assert(t.recorded.size == 1)
    val sent = t.recorded.head
    assert((sent(0) & 0xff) == 0xE0)
    assert((sent(1) & 0xff) == 0x04)
    assert((sent(2) & 0xff) == 0x00) // p1 first
    assert((sent(4) & 0xff) == 5)

  test("chunkedSend splits payload across APDUs with correct p1"):
    val t = MockTransport()
    // 600-byte payload @ chunkSize 255 → 3 chunks
    t.queueOk(); t.queueOk(); t.queueOk(Array[Byte](0xAA.toByte))
    val payload = new Array[Byte](600)
    var i = 0
    while i < payload.length do { payload(i) = i.toByte; i += 1 }
    val (sw, resp) =
      Await.result(Apdu.chunkedSend(t, 0xE0, 0x04, 0x00, 0x80, 0x00, payload), 1.second)
    assert(sw == Apdu.Sw_Ok)
    assert(resp.sameElements(Array[Byte](0xAA.toByte)))
    assert(t.recorded.size == 3)
    assert((t.recorded(0)(2) & 0xff) == 0x00)
    assert((t.recorded(1)(2) & 0xff) == 0x80)
    assert((t.recorded(2)(2) & 0xff) == 0x80)
    assert((t.recorded(0)(4) & 0xff) == 255)
    assert((t.recorded(1)(4) & 0xff) == 255)
    assert((t.recorded(2)(4) & 0xff) == 600 - 510)
    // round-trip the actual data bytes
    val sentPayload = new Array[Byte](600)
    System.arraycopy(t.recorded(0), 5, sentPayload, 0,   255)
    System.arraycopy(t.recorded(1), 5, sentPayload, 255, 255)
    System.arraycopy(t.recorded(2), 5, sentPayload, 510, 600 - 510)
    assert(sentPayload.sameElements(payload))

  test("chunkedSend aborts on intermediate error status"):
    val t = MockTransport()
    t.queueStatus(0x6985) // user declined on first chunk
    // queue extra responses to ensure we don't send the second APDU
    t.queueOk()
    val payload = new Array[Byte](400)
    val (sw, _) =
      Await.result(Apdu.chunkedSend(t, 0xE0, 0x04, 0x00, 0x80, 0x00, payload), 1.second)
    assert(sw == 0x6985)
    assert(t.recorded.size == 1, "should not send the second chunk after error")

  test("swHex formats status words"):
    assert(Apdu.swHex(0x9000) == "0x9000")
    assert(Apdu.swHex(0x6A82) == "0x6A82")
