package scalascript.wallet.vault.ledger.js.ble

/** APDU framing for Ledger BLE transport.
 *
 *  Uses the same channel/tag/sequence-number header as the HID framing
 *  (`WebHidFraming`), but with a configurable MTU-driven frame size.
 *  BLE frames are NOT padded to a fixed size — only the actual payload
 *  bytes are sent, capped at `mtu - 3` (3 bytes for ATT header overhead).
 *
 *  Default MTU is 23 bytes (BLE minimum), giving a payload of 20 bytes
 *  per frame.  Ledger Nano X / Stax negotiate higher MTU at connection
 *  time; call `BleFraming(mtu)` with the negotiated value to get a
 *  framing instance sized appropriately.
 *
 *  Wire layout (identical to HID):
 *  - bytes 0–1: channel ID = 0x0101
 *  - byte  2  : command tag = 0x05
 *  - bytes 3–4: sequence number (big-endian, starts at 0)
 *  - bytes 5–6: total APDU length (first frame only, big-endian)
 *  - remaining: APDU payload bytes (no trailing padding) */
final class BleFraming(val mtu: Int = BleFraming.DefaultMtu):
  val ChannelId: Int  = 0x0101
  val CmdTag: Byte    = 0x05
  val HeaderSize: Int = 5
  val LengthPrefixSize: Int = 2
  val framePayload: Int = mtu - 3         // ATT header overhead
  val firstPayload: Int = framePayload - HeaderSize - LengthPrefixSize
  val contPayload: Int  = framePayload - HeaderSize

  require(firstPayload > 0,
    s"BLE MTU $mtu is too small — need at least ${3 + HeaderSize + LengthPrefixSize + 1}")

  def encode(apdu: Array[Byte]): Vector[Array[Byte]] =
    require(apdu.length <= 0xFFFF, s"APDU too long for BLE framing: ${apdu.length}")
    val total =
      if apdu.length <= firstPayload then 1
      else 1 + math.ceil((apdu.length - firstPayload).toDouble / contPayload).toInt
    Vector.tabulate(total) { seq =>
      val payloadStart = if seq == 0 then HeaderSize + LengthPrefixSize else HeaderSize
      val offset = if seq == 0 then 0 else firstPayload + (seq - 1) * contPayload
      val n = math.min(framePayload - payloadStart, apdu.length - offset)
      val frame = new Array[Byte](payloadStart + n)
      frame(0) = ((ChannelId >>> 8) & 0xff).toByte
      frame(1) = (ChannelId & 0xff).toByte
      frame(2) = CmdTag
      frame(3) = ((seq >>> 8) & 0xff).toByte
      frame(4) = (seq & 0xff).toByte
      if seq == 0 then
        frame(HeaderSize)     = ((apdu.length >>> 8) & 0xff).toByte
        frame(HeaderSize + 1) = (apdu.length & 0xff).toByte
      if n > 0 then System.arraycopy(apdu, offset, frame, payloadStart, n)
      frame
    }

  def decode(frames: Iterable[Array[Byte]]): Array[Byte] =
    val iter = frames.iterator
    require(iter.hasNext, "decode: empty frame sequence")
    val first = iter.next()
    checkHeader(first, 0)
    val total = payloadLength(first)
    val out = new Array[Byte](total)
    val n0 = math.min(firstPayload, total)
    System.arraycopy(first, HeaderSize + LengthPrefixSize, out, 0, n0)
    var written = n0
    var seq = 1
    while written < total do
      require(iter.hasNext, s"decode: missing frame $seq")
      val frame = iter.next()
      checkHeader(frame, seq)
      val n = math.min(contPayload, total - written)
      System.arraycopy(frame, HeaderSize, out, written, n)
      written += n
      seq += 1
    out

  def expectedFrameCount(first: Array[Byte]): Int =
    checkHeader(first, 0)
    val total = payloadLength(first)
    if total <= firstPayload then 1
    else 1 + math.ceil((total - firstPayload).toDouble / contPayload).toInt

  private def payloadLength(first: Array[Byte]): Int =
    require(first.length >= HeaderSize + LengthPrefixSize,
      s"first BLE frame too short: ${first.length}")
    ((first(HeaderSize) & 0xff) << 8) | (first(HeaderSize + 1) & 0xff)

  private def checkHeader(frame: Array[Byte], expectedSeq: Int): Unit =
    require(frame.length >= HeaderSize, s"BLE frame too short: ${frame.length}")
    val cid = ((frame(0) & 0xff) << 8) | (frame(1) & 0xff)
    require(cid == ChannelId, s"Unexpected BLE channel id: 0x${"%04X".format(cid)}")
    require(frame(2) == CmdTag, s"Unexpected BLE command tag: 0x${"%02X".format(frame(2) & 0xff)}")
    val seq = ((frame(3) & 0xff) << 8) | (frame(4) & 0xff)
    require(seq == expectedSeq, s"Out-of-order BLE frame: got seq=$seq, expected $expectedSeq")

object BleFraming:
  val DefaultMtu: Int = 23
  val default: BleFraming = BleFraming()
