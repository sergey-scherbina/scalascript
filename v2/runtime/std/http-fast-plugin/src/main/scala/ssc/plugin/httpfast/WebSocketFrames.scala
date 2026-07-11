package ssc.plugin.httpfast

import java.io.OutputStream
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.security.MessageDigest
import java.util.Base64

/** RFC 6455 frame codec + handshake helpers (value-agnostic, no ssc types). */
object WebSocketFrames:
  val CONT   = 0x0
  val TEXT   = 0x1
  val BINARY = 0x2
  val CLOSE  = 0x8
  val PING   = 0x9
  val PONG   = 0xA

  private val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** `Sec-WebSocket-Accept` = base64(SHA-1(clientKey + GUID)). */
  def acceptKey(clientKey: String): String =
    val sha1 = MessageDigest.getInstance("SHA-1")
    val digest = sha1.digest((clientKey + GUID).getBytes(ISO_8859_1))
    Base64.getEncoder.encodeToString(digest)

  /** True when a request is a WebSocket upgrade (`Upgrade: websocket` + a key). */
  def isUpgrade(headers: Map[String, String]): Boolean =
    headers.get("upgrade").exists(_.toLowerCase(java.util.Locale.ROOT).contains("websocket")) &&
      headers.contains("sec-websocket-key")

  /** One decoded frame. Control frames (close/ping/pong) carry their (short) payload too. */
  final case class Frame(fin: Boolean, opcode: Int, payload: Array[Byte])

  /** Read a single frame off `reader`. Client→server frames MUST be masked (RFC 6455 §5.1);
    * an unmasked client frame is a protocol error. Enforces `maxPayload`. */
  def readFrame(reader: HttpReader, maxPayload: Long): Frame =
    val b0 = reader.readFully(1)(0) & 0xFF
    val b1 = reader.readFully(1)(0) & 0xFF
    val fin    = (b0 & 0x80) != 0
    val opcode = b0 & 0x0F
    val masked = (b1 & 0x80) != 0
    if !masked then throw new BadRequest("client frame not masked")
    var len: Long = (b1 & 0x7F).toLong
    if len == 126 then
      val ext = reader.readFully(2)
      len = ((ext(0) & 0xFF).toLong << 8) | (ext(1) & 0xFF).toLong
    else if len == 127 then
      val ext = reader.readFully(8)
      len = 0L
      var i = 0
      while i < 8 do { len = (len << 8) | (ext(i) & 0xFF).toLong; i += 1 }
    if len < 0 || len > maxPayload then throw new BadRequest(s"ws payload too large: $len")
    val mask    = reader.readFully(4)
    val payload = reader.readFully(len.toInt)
    var i = 0
    while i < payload.length do
      payload(i) = (payload(i) ^ mask(i & 3)).toByte
      i += 1
    Frame(fin, opcode, payload)

  /** Write a server frame (server→client frames are never masked). Synchronize externally. */
  def writeFrame(out: OutputStream, opcode: Int, payload: Array[Byte], fin: Boolean = true): Unit =
    val header = new java.io.ByteArrayOutputStream(10)
    header.write((if fin then 0x80 else 0x00) | (opcode & 0x0F))
    val len = payload.length
    if len <= 125 then header.write(len)
    else if len <= 0xFFFF then
      header.write(126)
      header.write((len >>> 8) & 0xFF)
      header.write(len & 0xFF)
    else
      header.write(127)
      var shift = 56
      while shift >= 0 do { header.write(((len.toLong >>> shift) & 0xFF).toInt); shift -= 8 }
    out.write(header.toByteArray)
    if len > 0 then out.write(payload)
    out.flush()

  def textPayload(s: String): Array[Byte] = s.getBytes(UTF_8)

  /** A close frame body: 2-byte status code + optional UTF-8 reason. */
  def closePayload(code: Int, reason: String = ""): Array[Byte] =
    val r = reason.getBytes(UTF_8)
    val out = new Array[Byte](2 + r.length)
    out(0) = ((code >>> 8) & 0xFF).toByte
    out(1) = (code & 0xFF).toByte
    System.arraycopy(r, 0, out, 2, r.length)
    out

  /** Status code from a received close payload (1005 = none present). */
  def closeCode(payload: Array[Byte]): Int =
    if payload.length >= 2 then ((payload(0) & 0xFF) << 8) | (payload(1) & 0xFF) else 1005
