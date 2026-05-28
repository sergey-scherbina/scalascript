package scalascript.wallet.vault.ledger.js.ble

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.ledger.Apdu

class WebBleTransportTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  // ── BleFraming (default MTU=23, payload=20) ─────────────────────────────────

  test("BleFraming encodes a short APDU into one frame (no trailing padding)"):
    val framing = BleFraming()
    val apdu    = Array[Byte](1, 2, 3)
    val frames  = framing.encode(apdu)
    assert(frames.size == 1)
    val f = frames.head
    assert((f(0) & 0xff) == 0x01)
    assert((f(1) & 0xff) == 0x01)
    assert((f(2) & 0xff) == 0x05)
    assert(f(3) == 0 && f(4) == 0)           // seq = 0
    assert(f(5) == 0 && f(6) == 3)           // totalLength = 3
    assert(f(7) == 1 && f(8) == 2 && f(9) == 3)
    assert(f.length == 10)                    // no padding

  test("BleFraming round-trips a short APDU"):
    val framing = BleFraming()
    val apdu    = Array.tabulate[Byte](10)(_.toByte)
    assert(framing.decode(framing.encode(apdu)).sameElements(apdu))

  test("BleFraming splits a 30-byte APDU into 3 frames at default MTU=23"):
    // firstPayload = 20 - 5 - 2 = 13, contPayload = 20 - 5 = 15
    // 30 bytes: first frame 13 + second frame 15 + third frame 2 = 30
    val framing = BleFraming()
    val apdu    = Array.tabulate[Byte](30)(_.toByte)
    val frames  = framing.encode(apdu)
    assert(frames.size == 3)
    assert(frames(1)(4) == 1.toByte)          // seq=1 in continuation frame
    assert(frames(2)(4) == 2.toByte)          // seq=2

  test("BleFraming round-trips a multi-frame APDU"):
    val framing = BleFraming()
    val apdu    = Array.tabulate[Byte](80)(i => (i ^ 0xAB).toByte)
    assert(framing.decode(framing.encode(apdu)).sameElements(apdu))

  test("BleFraming.expectedFrameCount matches encode output size"):
    val framing = BleFraming()
    val apdu    = Array.tabulate[Byte](50)(_.toByte)
    val frames  = framing.encode(apdu)
    assert(framing.expectedFrameCount(frames.head) == frames.size)

  test("BleFraming rejects out-of-order frames"):
    val framing = BleFraming()
    val frames  = framing.encode(Array.tabulate[Byte](30)(_.toByte))
    assertThrows[IllegalArgumentException](framing.decode(Vector(frames(1), frames(0))))

  test("BleFraming with negotiated MTU=100 fits large APDU in fewer frames"):
    val framing = BleFraming(100)
    // firstPayload = 97 - 5 - 2 = 90; a 90-byte APDU should fit in one frame
    val apdu    = Array.tabulate[Byte](90)(_.toByte)
    val frames  = framing.encode(apdu)
    assert(frames.size == 1)
    assert(framing.decode(frames).sameElements(apdu))

  // ── WebBleTransport ──────────────────────────────────────────────────────────

  test("transport refuses exchange before connect"):
    val device = MockBluetoothDevice()
    val t      = WebBleTransport(device)
    t.exchange(Array[Byte](1)).failed.map(ex => assert(ex.getMessage.contains("not open")))

  test("transport sends frames and decodes response APDU"):
    val device = MockBluetoothDevice()
    val response = Array[Byte](0x42, 0x90.toByte, 0x00)
    device.queueApdu(response)
    val t = WebBleTransport(device)
    for
      _ <- t.open()
      resp <- t.exchange(Apdu.command(0xE0, 0x02, 0, 0, Array[Byte](1, 2, 3)))
    yield
      assert(resp.sameElements(response))
      assert(device.writtenFrames.nonEmpty)

  test("transport is open after connect and closed after disconnect"):
    val device = MockBluetoothDevice()
    val t      = WebBleTransport(device)
    for
      _ <- t.open()
      _ = assert(t.isOpen)
      _ <- t.close()
    yield assert(!t.isOpen)

  test("transport is idempotent for open and close"):
    val device = MockBluetoothDevice()
    val t      = WebBleTransport(device)
    for
      _ <- t.open()
      _ <- t.open()
      _ = assert(t.isOpen)
      _ <- t.close()
      _ <- t.close()
    yield assert(!t.isOpen)

  test("transport reassembles multi-frame response"):
    val device   = MockBluetoothDevice()
    val bigApdu  = Array.tabulate[Byte](80)(i => (i + 1).toByte)
    device.queueApdu(bigApdu)
    val t = WebBleTransport(device)
    for
      _ <- t.open()
      resp <- t.exchange(Apdu.command(0xE0, 0x10, 0, 0, Array[Byte]()))
    yield assert(resp.sameElements(bigApdu))
