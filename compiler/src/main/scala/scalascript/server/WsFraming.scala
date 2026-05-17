package scalascript.server

import java.nio.charset.StandardCharsets

/** RFC-6455 frame parser and encoder.  Pure: no IO, no state held here —
 *  callers feed bytes in via [[tryParse]] and get back either a complete
 *  frame (with the count of bytes consumed) or `None` (need more bytes).
 *
 *  Supports the four wire-level pieces we need for an interactive WS:
 *    - text frames (opcode 0x1) — payload decoded as UTF-8
 *    - binary frames (opcode 0x2) — payload kept as raw bytes
 *    - ping  (0x9) / pong (0xA) — control frames
 *    - close (0x8) — connection-close request
 *
 *  Fragmentation is NOT handled here — every parsed frame is treated as
 *  self-contained.  A future revision can extend the parser to track
 *  partial messages across FIN=0 / opcode=0 continuation frames. */
object WsFraming:

  // GUID from RFC 6455 §1.3; concat with the client `Sec-WebSocket-Key`
  // and base64(SHA-1(·)) to produce `Sec-WebSocket-Accept`.
  val Magic: String = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** Hard cap on a single frame's payload — protects the server from
   *  hostile clients announcing a multi-gigabyte payload (which we'd
   *  otherwise try to allocate up front).  16 MB is well above any
   *  realistic browser-sent message; bigger payloads can still be
   *  delivered as multiple fragmented frames once continuation support
   *  lands. */
  val MaxFrameBytes: Int = 16 * 1024 * 1024

  enum Opcode(val code: Int):
    case Continuation extends Opcode(0x0)
    case Text         extends Opcode(0x1)
    case Binary       extends Opcode(0x2)
    case Close        extends Opcode(0x8)
    case Ping         extends Opcode(0x9)
    case Pong         extends Opcode(0xA)

  object Opcode:
    def fromCode(c: Int): Option[Opcode] = c match
      case 0x0 => Some(Continuation)
      case 0x1 => Some(Text)
      case 0x2 => Some(Binary)
      case 0x8 => Some(Close)
      case 0x9 => Some(Ping)
      case 0xA => Some(Pong)
      case _   => None

  /** A complete, demasked frame.  `payload` is the raw byte payload
   *  (already unmasked when the client side set MASK=1, as it always
   *  must).  `consumed` is the number of bytes from the input buffer
   *  that this frame occupied — callers slide their read buffer forward
   *  by exactly that many bytes. */
  final case class Frame(
      fin:      Boolean,
      opcode:   Opcode,
      payload:  Array[Byte],
      consumed: Int
  ):
    def textPayload: String = new String(payload, StandardCharsets.UTF_8)

  /** Attempt to parse one frame from `buf[offset, until)`.  Returns
   *  `Some(frame)` when a full frame is present, `None` when more bytes
   *  are needed.  Throws [[WsProtocolError]] if the bytes already in
   *  the buffer cannot form a valid frame (unknown opcode, oversized
   *  payload, …) — caller should close the connection. */
  def tryParse(buf: Array[Byte], offset: Int, until: Int): Option[Frame] =
    val avail = until - offset
    if avail < 2 then return None
    val b0  = buf(offset)     & 0xFF
    val b1  = buf(offset + 1) & 0xFF
    val fin = (b0 & 0x80) != 0
    val op  = b0 & 0x0F
    val masked = (b1 & 0x80) != 0
    val len7   = b1 & 0x7F
    val opcode = Opcode.fromCode(op).getOrElse(
      throw WsProtocolError(s"Unknown opcode 0x${op.toHexString}")
    )

    // Resolve the payload length.  RFC encodes it in three forms:
    //   len7 = 0..125    → that's the length
    //   len7 = 126       → next 2 bytes (big-endian) carry length
    //   len7 = 127       → next 8 bytes (big-endian); we cap at Int.MaxValue
    var hdrLen = 2
    val payloadLen: Long =
      if len7 <= 125 then len7.toLong
      else if len7 == 126 then
        if avail < hdrLen + 2 then return None
        hdrLen += 2
        ((buf(offset + 2) & 0xFF).toLong << 8) | (buf(offset + 3) & 0xFF).toLong
      else
        if avail < hdrLen + 8 then return None
        hdrLen += 8
        var v = 0L
        var i = 0
        while i < 8 do
          v = (v << 8) | (buf(offset + 2 + i) & 0xFF).toLong
          i += 1
        v

    if payloadLen > MaxFrameBytes.toLong then
      throw WsProtocolError(s"Frame too large: $payloadLen bytes (max $MaxFrameBytes)")

    // Mask key (4 bytes) is present iff MASK=1.  Client-to-server frames
    // MUST mask; server-to-client frames MUST NOT.  Both directions go
    // through the same parser — `tryParse` is happy either way.
    val maskLen = if masked then 4 else 0
    val totalLen = hdrLen + maskLen + payloadLen.toInt
    if avail < totalLen then return None

    val mask = if masked then
      Array.tabulate(4)(i => buf(offset + hdrLen + i))
    else null

    val payload = new Array[Byte](payloadLen.toInt)
    val payloadStart = offset + hdrLen + maskLen
    if masked then
      var i = 0
      while i < payloadLen.toInt do
        payload(i) = (buf(payloadStart + i) ^ mask(i % 4)).toByte
        i += 1
    else
      System.arraycopy(buf, payloadStart, payload, 0, payloadLen.toInt)

    Some(Frame(fin, opcode, payload, totalLen))

  /** Encode a server-side text frame (FIN=1, MASK=0). */
  def encodeText(s: String): Array[Byte] =
    encodeFrame(Opcode.Text, s.getBytes(StandardCharsets.UTF_8))

  /** Encode a server-side binary frame. */
  def encodeBinary(bytes: Array[Byte]): Array[Byte] =
    encodeFrame(Opcode.Binary, bytes)

  /** Encode a server-side pong (RFC 6455 §5.5.3 — echo back the ping
   *  payload). */
  def encodePong(payload: Array[Byte]): Array[Byte] =
    encodeFrame(Opcode.Pong, payload)

  /** Encode a server-initiated ping.  Payload is the timestamp-ish
   *  body the peer must echo verbatim in a Pong — empty by default
   *  for cheapest heartbeat. */
  def encodePing(payload: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    encodeFrame(Opcode.Ping, payload)

  /** Encode a server-side close (status + optional reason).  Status code
   *  is the 2-byte big-endian close code; 1000 = normal closure. */
  def encodeClose(status: Int, reason: String = ""): Array[Byte] =
    val rb = reason.getBytes(StandardCharsets.UTF_8)
    val payload = new Array[Byte](2 + rb.length)
    payload(0) = ((status >> 8) & 0xFF).toByte
    payload(1) = (status & 0xFF).toByte
    System.arraycopy(rb, 0, payload, 2, rb.length)
    encodeFrame(Opcode.Close, payload)

  private def encodeFrame(opcode: Opcode, payload: Array[Byte]): Array[Byte] =
    val len = payload.length
    val buf =
      if len <= 125 then
        val b = new Array[Byte](2 + len)
        b(0) = (0x80 | opcode.code).toByte // FIN=1
        b(1) = len.toByte                  // MASK=0
        System.arraycopy(payload, 0, b, 2, len)
        b
      else if len <= 0xFFFF then
        val b = new Array[Byte](4 + len)
        b(0) = (0x80 | opcode.code).toByte
        b(1) = 126.toByte
        b(2) = ((len >> 8) & 0xFF).toByte
        b(3) = (len & 0xFF).toByte
        System.arraycopy(payload, 0, b, 4, len)
        b
      else
        val b = new Array[Byte](10 + len)
        b(0) = (0x80 | opcode.code).toByte
        b(1) = 127.toByte
        var i = 0
        var v = len.toLong
        while i < 8 do
          b(9 - i) = (v & 0xFF).toByte
          v >>>= 8
          i += 1
        System.arraycopy(payload, 0, b, 10, len)
        b
    buf

  /** Build the `Sec-WebSocket-Accept` value for a given client key.
   *  base64(SHA-1(key + MAGIC_GUID)). */
  def acceptKey(clientKey: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val digest = md.digest((clientKey + Magic).getBytes(StandardCharsets.US_ASCII))
    java.util.Base64.getEncoder.encodeToString(digest)

  /** Indicates the bytes already in the parser buffer are syntactically
   *  invalid — the connection should be closed without waiting for more
   *  data.  Distinct from `None` (more bytes might fix things). */
  final class WsProtocolError(msg: String) extends Exception(msg)
