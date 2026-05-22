package scalascript.wallet.vault.ledger.jvm

/** Ledger's USB HID framing for APDUs.
 *
 *  An APDU is too large to fit in a single 64-byte HID report, so
 *  Ledger fragments it into a sequence of 64-byte HID frames each
 *  carrying a 5-byte header:
 *  {{{
 *  +-------------+------+--------+--------- payload -----------+
 *  | CID  (2B BE)| 0x05 | seq(2B)|        up to 59 / 57 B      |
 *  +-------------+------+--------+-----------------------------+
 *  }}}
 *  - `CID` — channel id; Ledger uses the fixed value `0x0101`.
 *  - `0x05` — command tag.
 *  - `seq` — 16-bit big-endian sequence number. The *first* frame
 *    additionally carries a 2-byte big-endian APDU length right
 *    after the header (i.e. seq=0 frame has 57 B of payload; seq>0
 *    frames have 59 B).
 *
 *  The host pads the trailing frame with zeros up to 64 B. The
 *  device replies with the same framing in reverse.
 *
 *  This object is pure (no I/O) so it can be unit-tested without a
 *  real device — every wire-format assumption is exercised by
 *  [[HidFramingTest]] against a recorded layout. */
object HidFraming:

  /** Fixed Ledger USB HID channel id. */
  val ChannelId: Int = 0x0101

  /** USB HID command tag. */
  val CmdTag: Byte = 0x05

  /** Total bytes per HID frame. */
  val FrameSize: Int = 64

  /** Bytes of header on every frame: [CID(2)][tag(1)][seq(2)] = 5. */
  val HeaderSize: Int = 5

  /** Bytes of length-prefix that the seq=0 frame carries after the
   *  header — 16-bit big-endian total APDU length. */
  val LengthPrefixSize: Int = 2

  /** Payload capacity of the seq=0 frame. */
  val FirstFramePayloadSize: Int = FrameSize - HeaderSize - LengthPrefixSize  // 57

  /** Payload capacity of every seq>0 frame. */
  val ContFramePayloadSize: Int = FrameSize - HeaderSize  // 59

  /** Pack an APDU into the wire sequence of 64-byte HID frames. The
   *  last frame is zero-padded to a full 64 B. */
  def encode(apdu: Array[Byte]): Array[Array[Byte]] =
    require(apdu.length <= 0xFFFF,
      s"APDU too long for 16-bit length prefix: ${apdu.length}")
    val total =
      if apdu.length <= FirstFramePayloadSize then 1
      else 1 + math.ceil((apdu.length - FirstFramePayloadSize).toDouble / ContFramePayloadSize).toInt
    val out = Array.ofDim[Array[Byte]](total)
    var offset = 0
    var seq    = 0
    while seq < total do
      val frame = new Array[Byte](FrameSize)
      // Header (CID big-endian || 0x05 || seq big-endian)
      frame(0) = ((ChannelId >>> 8) & 0xff).toByte
      frame(1) = ( ChannelId        & 0xff).toByte
      frame(2) = CmdTag
      frame(3) = ((seq      >>> 8) & 0xff).toByte
      frame(4) = ( seq             & 0xff).toByte
      val payloadStart = if seq == 0 then HeaderSize + LengthPrefixSize else HeaderSize
      if seq == 0 then
        frame(HeaderSize)     = ((apdu.length >>> 8) & 0xff).toByte
        frame(HeaderSize + 1) = ( apdu.length        & 0xff).toByte
      val capacity = FrameSize - payloadStart
      val n        = math.min(capacity, apdu.length - offset)
      System.arraycopy(apdu, offset, frame, payloadStart, n)
      out(seq) = frame
      offset += n
      seq    += 1
    out

  /** Reassemble a sequence of frames produced by the device into the
   *  original APDU payload. Validates the channel id, command tag,
   *  and monotonic sequence numbers. */
  def decode(frames: Iterable[Array[Byte]]): Array[Byte] =
    val iter   = frames.iterator
    require(iter.hasNext, "decode: empty frame sequence")
    val first  = iter.next()
    require(first.length == FrameSize, s"frame size != $FrameSize (got ${first.length})")
    checkHeader(first, expectedSeq = 0)
    val total  = ((first(HeaderSize) & 0xff) << 8) | (first(HeaderSize + 1) & 0xff)
    require(total >= 0, s"decoded length is negative ($total)")
    val out    = new Array[Byte](total)
    val n0     = math.min(FirstFramePayloadSize, total)
    System.arraycopy(first, HeaderSize + LengthPrefixSize, out, 0, n0)
    var offset = n0
    var expSeq = 1
    while offset < total do
      require(iter.hasNext,
        s"decode: payload truncated after $offset / $total B (missing frame $expSeq)")
      val f = iter.next()
      require(f.length == FrameSize, s"frame $expSeq size != $FrameSize (got ${f.length})")
      checkHeader(f, expSeq)
      val n = math.min(ContFramePayloadSize, total - offset)
      System.arraycopy(f, HeaderSize, out, offset, n)
      offset += n
      expSeq += 1
    out

  private def checkHeader(frame: Array[Byte], expectedSeq: Int): Unit =
    val cid = ((frame(0) & 0xff) << 8) | (frame(1) & 0xff)
    require(cid == ChannelId,
      s"Unexpected HID channel id: 0x${"%04X".format(cid)} (want 0x${"%04X".format(ChannelId)})")
    require(frame(2) == CmdTag,
      s"Unexpected HID command tag: 0x${"%02X".format(frame(2) & 0xff)} (want 0x05)")
    val seq = ((frame(3) & 0xff) << 8) | (frame(4) & 0xff)
    require(seq == expectedSeq,
      s"Out-of-order HID frame: got seq=$seq, expected $expectedSeq")
