package scalascript.wallet.vault.ledger.js

object WebHidFraming:
  val ChannelId: Int = 0x0101
  val CmdTag: Byte = 0x05
  val FrameSize: Int = 64
  val HeaderSize: Int = 5
  val LengthPrefixSize: Int = 2
  val FirstFramePayloadSize: Int = FrameSize - HeaderSize - LengthPrefixSize
  val ContFramePayloadSize: Int = FrameSize - HeaderSize

  def encode(apdu: Array[Byte]): Vector[Array[Byte]] =
    require(apdu.length <= 0xFFFF, s"APDU too long for WebHID framing: ${apdu.length}")
    val total =
      if apdu.length <= FirstFramePayloadSize then 1
      else 1 + math.ceil((apdu.length - FirstFramePayloadSize).toDouble / ContFramePayloadSize).toInt
    Vector.tabulate(total) { seq =>
      val frame = new Array[Byte](FrameSize)
      frame(0) = ((ChannelId >>> 8) & 0xff).toByte
      frame(1) = (ChannelId & 0xff).toByte
      frame(2) = CmdTag
      frame(3) = ((seq >>> 8) & 0xff).toByte
      frame(4) = (seq & 0xff).toByte
      val payloadStart = if seq == 0 then HeaderSize + LengthPrefixSize else HeaderSize
      if seq == 0 then
        frame(HeaderSize) = ((apdu.length >>> 8) & 0xff).toByte
        frame(HeaderSize + 1) = (apdu.length & 0xff).toByte
      val offset = if seq == 0 then 0 else FirstFramePayloadSize + (seq - 1) * ContFramePayloadSize
      val n = math.min(FrameSize - payloadStart, apdu.length - offset)
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
    val n0 = math.min(FirstFramePayloadSize, total)
    System.arraycopy(first, HeaderSize + LengthPrefixSize, out, 0, n0)
    var offset = n0
    var seq = 1
    while offset < total do
      require(iter.hasNext, s"decode: missing frame $seq")
      val frame = iter.next()
      checkHeader(frame, seq)
      val n = math.min(ContFramePayloadSize, total - offset)
      System.arraycopy(frame, HeaderSize, out, offset, n)
      offset += n
      seq += 1
    out

  def expectedFrameCount(first: Array[Byte]): Int =
    checkHeader(first, 0)
    val total = payloadLength(first)
    if total <= FirstFramePayloadSize then 1
    else 1 + math.ceil((total - FirstFramePayloadSize).toDouble / ContFramePayloadSize).toInt

  private def payloadLength(first: Array[Byte]): Int =
    require(first.length == FrameSize, s"frame size != $FrameSize (got ${first.length})")
    ((first(HeaderSize) & 0xff) << 8) | (first(HeaderSize + 1) & 0xff)

  private def checkHeader(frame: Array[Byte], expectedSeq: Int): Unit =
    require(frame.length == FrameSize, s"frame size != $FrameSize (got ${frame.length})")
    val cid = ((frame(0) & 0xff) << 8) | (frame(1) & 0xff)
    require(cid == ChannelId, s"Unexpected HID channel id: 0x${"%04X".format(cid)}")
    require(frame(2) == CmdTag, s"Unexpected HID command tag: 0x${"%02X".format(frame(2) & 0xff)}")
    val seq = ((frame(3) & 0xff) << 8) | (frame(4) & 0xff)
    require(seq == expectedSeq, s"Out-of-order HID frame: got seq=$seq, expected $expectedSeq")
