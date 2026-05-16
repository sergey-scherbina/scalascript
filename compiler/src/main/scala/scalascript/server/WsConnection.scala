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

  // Partial inbound frame buffer.  Bytes that didn't form a complete frame
  // last time around stay here for the next [[onBytes]] call.
  private var inBuf: Array[Byte] = new Array[Byte](4096)
  private var inLen: Int = 0

  // User-side callbacks — registered from the handler thread, fired from
  // the interpreter executor.  Volatile because the selector thread reads
  // them when scheduling dispatch.
  @volatile private var onMessageCb: Option[Value] = None
  @volatile private var onCloseCb:   Option[Value] = None
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

  /** Public, thread-safe write: parks bytes on the outbox and wakes the
   *  selector so it picks them up.  Called from the interpreter thread
   *  via the `ws.send` native. */
  def enqueue(bytes: Array[Byte]): Unit =
    if !closing && key.isValid then
      outbox.add(ByteBuffer.wrap(bytes))
      key.interestOpsOr(SelectionKey.OP_WRITE)
      selector.wakeup()

  /** Drain pending writes into the channel.  Called by the selector loop
   *  when the channel is writable.  Returns once the outbox is empty or
   *  the channel can't accept more (partial write). */
  def flush(): Unit =
    while !outbox.isEmpty do
      val buf = outbox.peek()
      channel.write(buf)
      if buf.hasRemaining then return
      outbox.poll()
    // Outbox empty: stop selecting for OP_WRITE.
    if key.isValid then key.interestOpsAnd(~SelectionKey.OP_WRITE)

  /** Send a Close control frame, then mark the connection as closing. */
  def sendClose(status: Int, reason: String): Unit =
    if !closing then
      closing = true
      enqueue(WsFraming.encodeClose(status, reason))

  /** Force-close immediately (after a fatal error or the peer's Close).
   *  Idempotent. */
  def closeNow(): Unit =
    if key.isValid then
      key.cancel()
      try channel.close() catch case _: Throwable => ()
      val cb = onCloseCb
      onCloseCb = None
      cb.foreach { c =>
        executor.execute { () =>
          try interp.invoke(c, Nil)
          catch case e: Throwable =>
            log.println(s"WS close handler error: ${e.getMessage}")
        }
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
        onCloseCb = Some(cb); Value.UnitV
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
