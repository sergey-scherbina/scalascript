package ssc.plugin.httpfast

import java.io.{ByteArrayOutputStream, EOFException, OutputStream}
import java.net.{Socket, SocketException, SocketTimeoutException}
import java.nio.charset.StandardCharsets.{UTF_8, ISO_8859_1}
import java.util.concurrent.atomic.AtomicBoolean
import WebSocketFrames.*

/** A live WebSocket connection (post-handshake). Owns one socket; its read loop runs on the
  * connection's virtual thread, assembling fragmented messages and answering control frames
  * (auto-pong, close handshake). Write methods are thread-safe (a `WsRoom` broadcast or a
  * timer may write from another thread) via a single write lock. Value-agnostic: callbacks
  * are plain Scala functions set by the ssc bridge. */
final class WsConnection(
    val id: Long,
    sock: Socket,
    reader: HttpReader,
    out: OutputStream,
    val request: RawRequest,
    val subprotocol: Option[String],
    maxPayload: Long = 1L << 20,
    permessageDeflate: Boolean = false):

  @volatile var onText:   String => Unit       = _ => ()
  @volatile var onBinary: Array[Byte] => Unit  = _ => ()
  @volatile var onClose:  (Int, String) => Unit = (_, _) => ()
  @volatile var onPong:   Array[Byte] => Unit  = _ => ()

  /** Optional application identity (set by `onWebSocketAuth`). */
  @volatile var user: Option[String] = None

  /** Runs exactly once at read-loop teardown, independent of the user `onClose` (used by the
    * bridge to drop the connection from its registry + rooms). */
  @volatile var onTeardown: () => Unit = () => ()

  private val writeLock     = new Object
  @volatile private var closedFlag = false
  private val closeNotified = new AtomicBoolean(false)

  // Blocking receive support (HttpServerSpi WsControls.recv — cluster handshake flows). The
  // read loop feeds text messages here only once recv() has been used, so non-recv
  // connections don't accumulate. Both recv() AND onText fire per the SPI contract.
  @volatile private var recvActive = false
  private val recvQueue = new java.util.concurrent.LinkedBlockingQueue[java.util.Optional[String]]()

  def isClosed: Boolean = closedFlag

  /** The peer address as `host:port` (`"?"` if unavailable) — for WsControls.remoteAddress. */
  def remoteAddress: String =
    sock.getRemoteSocketAddress match
      case a: java.net.InetSocketAddress => s"${a.getHostString}:${a.getPort}"
      case other => Option(other).map(_.toString).getOrElse("?")

  /** Blocking receive of the next text frame; `None` when the peer closes. Parks the calling
    * (virtual) thread until a frame arrives or the connection closes. */
  def recv(): Option[String] =
    recvActive = true
    try
      val v = recvQueue.take()
      if v.isPresent then Some(v.get) else None
    catch case _: InterruptedException => None

  def sendText(s: String): Unit       = send(TEXT, textPayload(s))
  def sendBytes(b: Array[Byte]): Unit = send(BINARY, b)
  def ping(b: Array[Byte] = Array.emptyByteArray): Unit = send(PING, b)

  private def send(opcode: Int, payload: Array[Byte]): Unit =
    writeLock.synchronized {
      if !closedFlag then
        // permessage-deflate compresses data frames only (never control frames).
        val (data, rsv1) =
          if permessageDeflate && (opcode == TEXT || opcode == BINARY) && payload.nonEmpty then
            (WebSocketFrames.deflate(payload), true)
          else (payload, false)
        try writeFrame(out, opcode, data, rsv1 = rsv1)
        catch case _: java.io.IOException => forceClosed()
    }

  /** Send a close frame (if still open) and shut the socket. Idempotent. */
  def close(code: Int = 1000, reason: String = ""): Unit =
    writeLock.synchronized {
      if !closedFlag then
        try writeFrame(out, CLOSE, closePayload(code, reason)) catch case _: Throwable => ()
        closedFlag = true
    }
    closeSocket()

  /** Blocking read loop; returns when the peer closes, on EOF, timeout, or protocol error. */
  def readLoop(): Unit =
    var fragmentOpcode = -1
    var fragmentDeflated = false // permessage-deflate: RSV1 is set only on the first frame
    val assembling = new ByteArrayOutputStream()
    try
      var continue = true
      while continue && !closedFlag do
        val frame = readFrame(reader, maxPayload)
        frame.opcode match
          case PING =>
            writeLock.synchronized { if !closedFlag then try writeFrame(out, PONG, frame.payload) catch case _: Throwable => () }
          case PONG =>
            safe(onPong(frame.payload))
          case CLOSE =>
            val code = closeCode(frame.payload)
            writeLock.synchronized {
              if !closedFlag then
                try writeFrame(out, CLOSE, closePayload(if code == 1005 then 1000 else code)) catch case _: Throwable => ()
                closedFlag = true
            }
            notifyClose(code, "")
            continue = false
          case TEXT | BINARY =>
            if frame.fin then dispatch(frame.opcode, inflateIf(frame.payload, frame.rsv1))
            else
              fragmentOpcode = frame.opcode
              fragmentDeflated = frame.rsv1
              assembling.reset()
              assembling.write(frame.payload)
          case CONT =>
            assembling.write(frame.payload)
            if assembling.size().toLong > maxPayload then throw new BadRequest("fragmented ws message too large")
            if frame.fin then
              val full = assembling.toByteArray
              val op = fragmentOpcode
              fragmentOpcode = -1
              dispatch(op, inflateIf(full, fragmentDeflated))
          case _ => () // reserved opcode — ignore
    catch
      case _: EOFException | _: SocketException | _: SocketTimeoutException => ()
      case _: BadRequest => () // protocol error → just tear down
    finally
      notifyClose(1006, "")
      closeSocket()
      safe(onTeardown())

  /** Inflate a permessage-deflate data message if the connection negotiated it and the frame's
    * RSV1 was set; otherwise pass through unchanged. */
  private def inflateIf(bytes: Array[Byte], deflated: Boolean): Array[Byte] =
    if permessageDeflate && deflated then WebSocketFrames.inflate(bytes, maxPayload) else bytes

  private def dispatch(opcode: Int, bytes: Array[Byte]): Unit =
    if opcode == TEXT then
      val s = new String(bytes, UTF_8)
      if recvActive then recvQueue.offer(java.util.Optional.of(s))
      safe(onText(s))
    else
      // Binary frames must also reach the blocking recv() queue, surfaced as a
      // Latin-1 byte-view string — the same convention the WS client's recv()
      // and the onBinary→onMessage bridge use. Without this, a server-inbound
      // consumer that pulls via recv() (the v2 binary cluster protocol:
      // ssc-actors-v2.cbor/msgpack) never sees post-handshake frames, so Bully
      // coordinator/election replies delivered over a node's *server* socket are
      // silently dropped and leader election cannot converge.
      if recvActive then recvQueue.offer(java.util.Optional.of(new String(bytes, ISO_8859_1)))
      safe(onBinary(bytes))

  private def notifyClose(code: Int, reason: String): Unit =
    if closeNotified.compareAndSet(false, true) then
      if recvActive then recvQueue.offer(java.util.Optional.empty[String]())
      safe(onClose(code, reason))

  private def forceClosed(): Unit = closedFlag = true

  private def closeSocket(): Unit =
    closedFlag = true
    try sock.close() catch case _: Throwable => ()

  private def safe(body: => Unit): Unit =
    try body catch case _: Throwable => ()
