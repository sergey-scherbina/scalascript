package scalascript.server

import java.nio.ByteBuffer
import java.nio.channels.{SocketChannel, SelectionKey}
import java.util.concurrent.{ConcurrentLinkedQueue, Executor}
import scalascript.interpreter.{Interpreter, Value, Computation}

/** Live WebSocket session: parser state on the inbound side, write queue on
 *  the outbound side, plus the user-facing `WebSocket` value the handler
 *  receives.
 *
 *  Lifecycle:
 *    1. [[WsProxy]] finishes the HTTP upgrade and constructs this object.
 *    2. The user `onWebSocket` handler runs once on the interpreter
 *       executor, called with [[asValue]] — typically the handler registers
 *       `onMessage` / `onClose` callbacks via the methods exposed there.
 *    3. As frames arrive, the proxy calls [[onFrame]] from the selector
 *       thread; control-frame replies (pong) go straight out, app messages
 *       (text/binary) are dispatched to the interpreter executor.
 *    4. On EOF / close / error, [[closed]] is invoked exactly once.
 *
 *  Thread model: read-side ([[onFrame]]) runs on the proxy's selector
 *  thread.  Write-side ([[enqueue]]) can be called from anywhere — it
 *  parks bytes on a lock-free queue and asks the proxy to flush them.
 *  User callbacks always run on the interpreter executor. */
final class WsConnection(
    private val channel:    SocketChannel,
    private val key:        SelectionKey,
    private val selector:   java.nio.channels.Selector,
    private val interp:     Interpreter,
    private val executor:   Executor,
    private val log:        java.io.PrintStream
):
  // Outgoing frames waiting to be written.  Drained by the selector thread
  // in [[flush]]; new bytes appended from any thread via [[enqueue]].
  private val outbox: ConcurrentLinkedQueue[ByteBuffer] =
    new ConcurrentLinkedQueue[ByteBuffer]()
  // Cumulative bytes parked on the outbox waiting to be written.  When
  // the peer reads slowly we shouldn't queue indefinitely — a hostile or
  // dead client could otherwise let a chatty broadcaster eat the heap.
  private val outboxBytes: java.util.concurrent.atomic.AtomicLong =
    java.util.concurrent.atomic.AtomicLong(0L)

  // Partial inbound frame buffer.  Bytes that didn't form a complete frame
  // last time around stay here for the next [[onBytes]] call.
  private var inBuf: Array[Byte] = new Array[Byte](4096)
  private var inLen: Int = 0

  // User-side callbacks — registered from the handler thread, fired from
  // the interpreter executor.  `onCloseCb` uses AtomicReference so the
  // close-fires-once rule (against the read-loop / `close()` race) is
  // enforced by a single CAS, not by best-effort `var = null` patterns.
  @volatile private var onMessageCb: Option[Value] = None
  private val onCloseCb:
    java.util.concurrent.atomic.AtomicReference[Value | Null] =
      java.util.concurrent.atomic.AtomicReference[Value | Null](null)
  @volatile private var closing:     Boolean       = false

  /** Append more inbound bytes to the parser buffer and drain whole frames
   *  into [[onFrame]].  Called by the selector thread after a successful
   *  read; safe to call repeatedly. */
  def onBytes(src: ByteBuffer): Unit =
    val n = src.remaining
    ensureInCapacity(inLen + n)
    src.get(inBuf, inLen, n)
    inLen += n
    drainFrames()

  private def drainFrames(): Unit =
    var offset = 0
    var done   = false
    while !done do
      try WsFraming.tryParse(inBuf, offset, inLen) match
        case Some(frame) =>
          offset += frame.consumed
          onFrame(frame)
        case None =>
          done = true
      catch case _: WsFraming.WsProtocolError =>
        // Malformed frame on the wire: close the connection.
        sendClose(1002, "protocol error")
        done = true
    if offset > 0 then
      System.arraycopy(inBuf, offset, inBuf, 0, inLen - offset)
      inLen -= offset

  /** Dispatch a complete frame.  Control frames (ping / close) are handled
   *  on the spot; application frames (text / binary) are routed to the
   *  user's `onMessage` callback via the interpreter executor. */
  private def onFrame(frame: WsFraming.Frame): Unit =
    import WsFraming.Opcode
    frame.opcode match
      case Opcode.Ping =>
        enqueue(WsFraming.encodePong(frame.payload))
      case Opcode.Pong =>
        () // no app-level handler for pong
      case Opcode.Close =>
        // Echo a close back if we haven't sent one yet, then drop the
        // connection.  The 2-byte status (if present) is preserved.
        if !closing then
          val status =
            if frame.payload.length >= 2 then
              ((frame.payload(0) & 0xFF) << 8) | (frame.payload(1) & 0xFF)
            else 1000
          sendClose(status, "")
        closeNow()
      case Opcode.Text | Opcode.Binary =>
        // Always queue the dispatch — `onMessageCb` may not be set yet
        // when the frame is parsed on the selector thread (the user's
        // `onWebSocket` block runs on the executor, also FIFO).  By
        // queuing unconditionally we preserve message order and let the
        // executor task read the up-to-date callback.
        val payload: Value =
          if frame.opcode == Opcode.Text then Value.StringV(frame.textPayload)
          else Value.StringV(new String(frame.payload, "ISO-8859-1"))
        executor.execute { () =>
          onMessageCb.foreach { cb =>
            try interp.invoke(cb, List(payload))
            catch case e: Throwable =>
              log.println(s"WS handler error: ${e.getMessage}")
          }
        }
      case Opcode.Continuation =>
        // Fragmentation: not currently reassembled — treat as protocol error.
        sendClose(1003, "fragmented messages not supported")

  /** Soft cap on bytes parked on the outbox.  A slow client that fails
   *  to drain its socket would otherwise let a chatty broadcaster pile
   *  data into the heap unbounded — at the cap we drop the connection
   *  rather than risk OOM. */
  private val MaxOutboxBytes: Long = 4L * 1024L * 1024L

  /** Low-level queue + wake.  No `closing` check — used by [[sendClose]]
   *  too, which must be allowed to write the close control frame even
   *  after setting `closing = true`. */
  private def writeFrame(bytes: Array[Byte]): Unit =
    if !key.isValid then return
    outboxBytes.addAndGet(bytes.length.toLong)
    outbox.add(ByteBuffer.wrap(bytes))
    key.interestOpsOr(SelectionKey.OP_WRITE)
    selector.wakeup()

  /** Public, thread-safe write: parks bytes on the outbox and wakes the
   *  selector so it picks them up.  Called from the interpreter thread
   *  via the `ws.send` native.  When the outbox is already over
   *  [[MaxOutboxBytes]] we tear the connection down rather than queue
   *  yet another frame onto a backlog the peer can't drain. */
  def enqueue(bytes: Array[Byte]): Unit =
    if closing || !key.isValid then return
    if outboxBytes.get + bytes.length.toLong > MaxOutboxBytes then
      // Don't bother sending a Close frame — its bytes would just join
      // the same stalled outbox.  The peer is gone for our purposes.
      closeNow()
    else writeFrame(bytes)

  /** Drain pending writes into the channel.  Called by the selector loop
   *  when the channel is writable.  Returns once the outbox is empty or
   *  the channel can't accept more (partial write). */
  def flush(): Unit =
    while !outbox.isEmpty do
      val buf = outbox.peek()
      val before = buf.remaining
      channel.write(buf)
      // Decrement outboxBytes by the amount actually written this round,
      // so backpressure unwinds as the socket drains.
      val written = before - buf.remaining
      if written > 0 then outboxBytes.addAndGet(-written.toLong)
      if buf.hasRemaining then return
      outbox.poll()
    // Outbox empty: stop selecting for OP_WRITE.
    if key.isValid then key.interestOpsAnd(~SelectionKey.OP_WRITE)

  /** Send a Close control frame, then mark the connection as closing.
   *  Uses [[writeFrame]] directly (not [[enqueue]]) so the close frame
   *  itself isn't rejected by the `closing` flag we just set. */
  def sendClose(status: Int, reason: String): Unit =
    if !closing then
      closing = true
      writeFrame(WsFraming.encodeClose(status, reason))

  /** Force-close immediately (after a fatal error or the peer's Close).
   *  Idempotent. */
  def closeNow(): Unit =
    if key.isValid then
      key.cancel()
      try channel.close() catch case _: Throwable => ()
      // Atomic getAndSet — at most one caller can win the right to fire
      // onClose.  Without this both the read-loop's drainFrames (on EOF
      // or a peer-initiated close frame) and a user-side `ws.close()`
      // could read the same non-null and invoke the callback twice.
      val cb = onCloseCb.getAndSet(null)
      if cb != null then
        executor.execute { () =>
          try interp.invoke(cb, Nil)
          catch case e: Throwable =>
            log.println(s"WS close handler error: ${e.getMessage}")
        }

  /** The `WebSocket` Value passed to the user's handler.  All four methods
   *  capture `this` so callbacks fire on the live connection. */
  def asValue: Value =
    val send = Value.NativeFnV("WebSocket.send", Computation.pureFn {
      case List(Value.StringV(s)) =>
        enqueue(WsFraming.encodeText(s))
        Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.send(text)")
    })
    val close = Value.NativeFnV("WebSocket.close", Computation.pureFn {
      case Nil =>
        sendClose(1000, ""); Value.UnitV
      case List(Value.IntV(code)) =>
        sendClose(code.toInt, ""); Value.UnitV
      case List(Value.IntV(code), Value.StringV(reason)) =>
        sendClose(code.toInt, reason); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.close() or ws.close(code) or ws.close(code, reason)")
    })
    val onMessage = Value.NativeFnV("WebSocket.onMessage", Computation.pureFn {
      case List(cb) =>
        onMessageCb = Some(cb); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.onMessage { msg => … }")
    })
    val onClose = Value.NativeFnV("WebSocket.onClose", Computation.pureFn {
      case List(cb) =>
        onCloseCb.set(cb); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.onClose { () => … }")
    })
    Value.InstanceV("WebSocket", Map(
      "send"      -> send,
      "close"     -> close,
      "onMessage" -> onMessage,
      "onClose"   -> onClose
    ))

  private def ensureInCapacity(target: Int): Unit =
    if target > inBuf.length then
      var cap = inBuf.length
      while cap < target do cap *= 2
      val grown = new Array[Byte](cap)
      System.arraycopy(inBuf, 0, grown, 0, inLen)
      inBuf = grown
