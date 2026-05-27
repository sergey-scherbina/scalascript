package scalascript.wallet.vault.ledger.js

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.ledger.Apdu

class WebHidLedgerTransportTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  test("WebHidFraming encodes one 64-byte frame for a short APDU"):
    val frames = WebHidFraming.encode(Array[Byte](1, 2, 3))
    assert(frames.size == 1)
    assert(frames.head.length == 64)
    assert((frames.head(0) & 0xff) == 0x01)
    assert((frames.head(1) & 0xff) == 0x01)
    assert((frames.head(2) & 0xff) == 0x05)
    assert(WebHidFraming.decode(frames).sameElements(Array[Byte](1, 2, 3)))

  test("WebHidFraming splits and decodes long APDUs"):
    val apdu = Array.tabulate[Byte](180)(_.toByte)
    val frames = WebHidFraming.encode(apdu)
    assert(frames.size == 4)
    assert(WebHidFraming.expectedFrameCount(frames.head) == 4)
    assert(WebHidFraming.decode(frames).sameElements(apdu))

  test("WebHidFraming rejects out-of-order frames"):
    val frames = WebHidFraming.encode(Array.tabulate[Byte](80)(_.toByte))
    assertThrows[IllegalArgumentException](WebHidFraming.decode(Vector(frames(1), frames(0))))

  test("transport refuses exchange before open"):
    val t = WebHidLedgerTransport(MockWebHidDevice())
    t.exchange(Array[Byte](1)).failed.map(ex => assert(ex.getMessage.contains("not open")))

  test("transport sends encoded reports and decodes response APDU"):
    val device = MockWebHidDevice()
    val responseApdu = Array[Byte](0x42, 0x90.toByte, 0x00)
    device.queueApdu(responseApdu)
    val t = WebHidLedgerTransport(device)
    for
      _ <- t.open()
      resp <- t.exchange(Apdu.command(0xE0, 0x02, 0, 0, Array[Byte](1, 2, 3)))
    yield
      assert(resp.sameElements(responseApdu))
      assert(device.sentReports.nonEmpty)
      assert(device.sentReports.forall(_._1 == 0))

  test("transport is idempotent for open and close"):
    val device = MockWebHidDevice()
    val t = WebHidLedgerTransport(device)
    for
      _ <- t.open()
      _ <- t.open()
      _ = assert(t.isOpen)
      _ <- t.close()
      _ <- t.close()
    yield assert(!t.isOpen)
