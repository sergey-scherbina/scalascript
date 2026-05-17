package scalascript.server

import java.nio.ByteBuffer
import java.nio.channels.{SocketChannel, SelectionKey}
import java.util.concurrent.{ConcurrentLinkedQueue, Executor, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scalascript.interpreter.{Interpreter, Value, Computation}

/** Process-global counters for active WS connections + the configurable
 *  cap.  Lives on the companion (not in [[WsRoutes]]) so it can be set
 *  before any `serve(port)` call and persists across server restarts
 *  inside the same JVM. */
object WsConnection:
  /** Soft cap on simultaneously-open WS sessions process-wide.  Set
   *  via the `setMaxWsConnections(n)` native; default = unlimited.
   *  Beyond the cap, [[WsProxy]] rejects upgrades with 503 Service
   *  Unavailable before allocating a WebSocket value or invoking the
   *  user handler. */
  val maxActive:    AtomicInteger = AtomicInteger(Int.MaxValue)
  val activeCount:  AtomicInteger = AtomicInteger(0)

  /** Atomic check-then-increment.  Returns true if the caller may
   *  proceed (counter was below the cap), false if the cap is reached.
   *  Failed reservations roll the counter back to its prior value so
   *  the cap can't drift. */
  def tryReserveSlot(): Boolean =
    val after = activeCount.incrementAndGet()
    if after > maxActive.get then
      activeCount.decrementAndGet()
      false
    else true

  def releaseSlot(): Unit =
    activeCount.decrementAndGet()

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
    private val log:        java.io.PrintStream,
    /** Snapshot of the upgrade request (method, path, params, query,
     *  headers) — exposed to the handler as `ws.request` so WS-side
     *  auth (cookie / Authorization / Origin) can read the same data
     *  REST handlers see. */
    private val request:    Value,
    /** Shared scheduler driving the periodic Ping heartbeat — passed in
     *  from [[WsProxy]] so every connection shares one daemon thread
     *  instead of spinning up its own. */
    private val scheduler:  ScheduledExecutorService,
    /** Heartbeat tuning, hoisted to constructor params so tests can
     *  shrink the interval and assert dead-peer drop without sitting
     *  through a real 90-second timeout. */
    private val heartbeatIntervalMs: Long = 30_000L,
    private val deadAfterMs:         Long = 90_000L,
    /** The subprotocol the server selected during upgrade negotiation
     *  (RFC 6455 §1.9).  Empty when no negotiation took place
     *  (route registered with no `protocols:` allowlist).  Surfaced
     *  to the handler as `ws.subprotocol`; `ws.request.headers
     *  ("sec-websocket-protocol")` still carries the client's full
     *  offer list, so this distinguishes "what was offered" from
     *  "what was chosen". */
    val subprotocol: String = "",
    /** Per-route cleanup callback fired exactly once when this
     *  connection terminates.  Used to decrement the route's
     *  `activeCount` so the per-route cap recovers.  Default is a
     *  no-op for codepaths that don't have a route counter (tests,
     *  raw connections). */
    private val onTerminate: () => Unit = () => (),
    /** Cap on inbound messages per second on this connection.
     *  0 = unlimited.  Overrun closes with code 1008 ("policy
     *  violation").  Implemented as a fixed-window counter: every
     *  second of wall clock the bucket resets to 0; the
     *  `maxMessagesPerSec+1`-th message in any second trips the
     *  close. */
    private val maxMessagesPerSec: Int = 0
):
  /** Stable per-connection identifier.  UUID-v4 generated at upgrade
   *  time so it's globally unique even across restarts, but short
   *  enough to grep for in logs.  Surfaced to the handler as `ws.id`
   *  and used by [[WsConnection]] / [[WsProxy]] to tag every log line
   *  for a single session. */
  val id: String = java.util.UUID.randomUUID().toString
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

  // Fragmented-message reassembly (RFC 6455 §5.4).  Real browsers split
  // large text/binary messages across multiple frames: the first frame
  // carries the opcode with FIN=0, followed by Continuation frames
  // (opcode=0x0) with the rest, the last one with FIN=1.  Control frames
  // (Ping/Pong/Close) are never fragmented and may interleave freely.
  // Buffer until FIN=1 lands, then dispatch the joined payload using
  // the originating opcode.
  private var fragOpcode: WsFraming.Opcode | Null = null
  private val fragBuf:    java.io.ByteArrayOutputStream =
    new java.io.ByteArrayOutputStream()

  // User-side callbacks — registered from the handler thread, fired from
  // the interpreter executor.  `onCloseCb` uses AtomicReference so the
  // close-fires-once rule (against the read-loop / `close()` race) is
  // enforced by a single CAS, not by best-effort `var = null` patterns.
  @volatile private var onMessageCb: Option[Value] = None
  @volatile private var onPongCb:    Option[Value] = None
  // Async-style recv queue — populated by `dispatchMessage` once any
  // consumer flips `recvEnabled` via the first `ws.recv()` call.  Null
  // sentinel signals "WS closed".  Lets a user handler read messages
  // with a sync `while !ws.isClosed do ws.recv()` loop instead of
  // installing an inverted-control `onMessage` callback.
  private val recvQueue: java.util.concurrent.LinkedBlockingQueue[String | Null] =
    java.util.concurrent.LinkedBlockingQueue()
  @volatile private var recvEnabled: Boolean = false
  private val onCloseCb:
    java.util.concurrent.atomic.AtomicReference[Value | Null] =
      java.util.concurrent.atomic.AtomicReference[Value | Null](null)
  @volatile private var closing:     Boolean       = false

  // ─── Server-initiated heartbeat ──────────────────────────────────
  // We send an empty Ping every `heartbeatIntervalMs` and expect a
  // Pong back within `deadAfterMs` of our last successful Pong (or
  // the upgrade time, for the first round).  This catches connections
  // whose TCP path silently drops — NAT timeouts, mobile-to-WiFi
  // handoff, half-closed peers — well before OS keepalive (~2 h)
  // would notice.
  @volatile private var lastPongAt: Long = System.currentTimeMillis()
  // Held so we can cancel on close; volatile because the close-thread
  // reads it after the scheduler thread set it.
  @volatile private var heartbeatTask: ScheduledFuture[?] = null

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

  /** Dispatch a complete frame.  Control frames (ping / close) are
   *  handled on the spot; application frames (text / binary) are routed
   *  to the user's `onMessage` callback via the interpreter executor.
   *  Fragmented messages (FIN=0 followed by Continuations) buffer until
   *  the final fragment, then dispatch the joined payload. */
  private def onFrame(frame: WsFraming.Frame): Unit =
    import WsFraming.Opcode
    frame.opcode match
      case Opcode.Ping =>
        writeFrame(WsFraming.encodePong(frame.payload))
      case Opcode.Pong =>
        // Peer is alive — refresh the liveness timestamp so the
        // heartbeat task doesn't tear down the connection.
        lastPongAt = System.currentTimeMillis()
        // Also fire user-side `onPong` if registered — payload is the
        // bytes the peer echoed verbatim, surfaced as a Latin-1
        // byte-view String (same convention as `onMessage` binary).
        onPongCb.foreach { cb =>
          val payload = Value.StringV(new String(frame.payload, "ISO-8859-1"))
          executor.execute { () =>
            try interp.invoke(cb, List(payload))
            catch case e: Throwable =>
              log.println(s"WS onPong handler error: ${e.getMessage}")
          }
        }
      case Opcode.Close =>
        // Echo a close back if we haven't sent one yet (RFC 6455
        // §5.5.1).  `sendClose` schedules the channel teardown
        // after a short grace so the selector has time to flush our
        // echo onto the wire; if we already sent Close (we
        // initiated), tear down right away — the peer has just
        // acknowledged.  Either way `closeNow()` is idempotent.
        if !closing then
          val status =
            if frame.payload.length >= 2 then
              ((frame.payload(0) & 0xFF) << 8) | (frame.payload(1) & 0xFF)
            else 1000
          sendClose(status, "")
        else closeNow()
      case Opcode.Text | Opcode.Binary =>
        if !frame.fin then
          // Start of a fragmented message — stash the opcode and the
          // first chunk, wait for Continuations.
          if fragOpcode != null then
            sendClose(1002, "new data frame mid-fragment")
            return
          fragOpcode = frame.opcode
          fragBuf.reset()
          fragBuf.write(frame.payload)
          checkFragLimit()
        else dispatchMessage(frame.opcode, frame.payload)
      case Opcode.Continuation =>
        if fragOpcode == null then
          sendClose(1002, "continuation without prior data frame")
          return
        fragBuf.write(frame.payload)
        if !checkFragLimit() then return
        if frame.fin then
          val op    = fragOpcode.asInstanceOf[Opcode] // safe: non-null
          val bytes = fragBuf.toByteArray
          fragOpcode = null
          fragBuf.reset()
          dispatchMessage(op, bytes)

  // Rate-limit state — fixed 1-second window, reset on first message
  // of each second.  No synchronisation needed: `dispatchMessage` runs
  // on the selector thread, which is the only writer.
  private var rateWindowStartMs: Long = 0L
  private var rateMsgsInWindow:  Int  = 0

  /** Dispatch a fully-reassembled text/binary message to the user
   *  `onMessage` callback through the executor.  Queued
   *  unconditionally — the user's `onWebSocket` block runs on the same
   *  single-thread executor and may not have set the callback yet when
   *  the selector parses the first frame.  Reading `onMessageCb` inside
   *  the task gives us the latest value. */
  private def dispatchMessage(opcode: WsFraming.Opcode, payload: Array[Byte]): Unit =
    if maxMessagesPerSec > 0 then
      val now = System.currentTimeMillis()
      if now - rateWindowStartMs >= 1000L then
        rateWindowStartMs = now
        rateMsgsInWindow  = 0
      rateMsgsInWindow += 1
      if rateMsgsInWindow > maxMessagesPerSec then
        sendClose(1008, "rate limit exceeded")
        return
    val s: String =
      if opcode == WsFraming.Opcode.Text then
        new String(payload, java.nio.charset.StandardCharsets.UTF_8)
      else new String(payload, "ISO-8859-1")
    // Async-style recv path: parked consumers wake on the next take.
    if recvEnabled then recvQueue.offer(s)
    val v: Value = Value.StringV(s)
    executor.execute { () =>
      onMessageCb.foreach { cb =>
        try interp.invoke(cb, List(v))
        catch case e: Throwable =>
          log.println(s"WS handler error: ${e.getMessage}")
      }
    }

  /** Cap on the total reassembled-message size, same threshold as a
   *  single frame.  Returns true when still under budget. */
  private def checkFragLimit(): Boolean =
    if fragBuf.size > WsFraming.MaxFrameBytes then
      fragOpcode = null
      fragBuf.reset()
      sendClose(1009, "message too big")
      false
    else true

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
   *  itself isn't rejected by the `closing` flag we just set.
   *
   *  After queueing the Close frame, schedules a fallback `closeNow`
   *  on the heartbeat scheduler so the channel doesn't sit open
   *  indefinitely if the peer never echoes Close — RFC 6455 §7.1.1
   *  recommends a brief wait, not an infinite one.  The first
   *  trigger wins: peer echo (`onFrame(Close) → closeNow`), peer
   *  half-close (read EOF → closeChain → closeNow), or this 500 ms
   *  fallback.  `closeNow` is idempotent via `key.isValid`. */
  def sendClose(status: Int, reason: String): Unit =
    if !closing then
      closing = true
      writeFrame(WsFraming.encodeClose(status, reason))
      scheduler.schedule(
        new Runnable { def run(): Unit = closeNow() },
        500L, TimeUnit.MILLISECONDS
      )

  /** Arm the periodic Ping → Pong heartbeat.  Called by [[WsProxy]]
   *  right after the upgrade so the first ping lands one interval
   *  later.  Stays armed until [[closeNow]] cancels the task. */
  def startHeartbeat(): Unit =
    lastPongAt = System.currentTimeMillis()
    heartbeatTask = scheduler.scheduleAtFixedRate(() => {
      try
        if closing then return
        val now = System.currentTimeMillis()
        if now - lastPongAt > deadAfterMs then
          // Peer hasn't echoed our pings — assume dead.  Queue a Close
          // frame, then defer the hard channel teardown so the
          // selector has a chance to flush our Close onto the wire
          // first (otherwise we send FIN with the Close still in our
          // outbox and the peer sees an abrupt drop).
          sendClose(1001, "ping timeout")
          scheduler.schedule(
            new Runnable { def run(): Unit = closeNow() },
            200L, TimeUnit.MILLISECONDS
          )
        else
          writeFrame(WsFraming.encodePing())
      catch case _: Throwable => closeNow()
    }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)

  /** Force-close immediately (after a fatal error or the peer's Close).
   *  Idempotent. */
  def closeNow(): Unit =
    // Cancel the heartbeat task first — otherwise it might still fire
    // once after we've closed the channel and try to write to it.
    val task = heartbeatTask; heartbeatTask = null
    if task != null then task.cancel(false)
    if key.isValid then
      key.cancel()
      try channel.close() catch case _: Throwable => ()
      // Release the global-cap slot we reserved during the upgrade.
      // Guarded by `key.isValid` so duplicate `closeNow` calls (peer
      // close + reader EOF) don't double-decrement.
      WsConnection.releaseSlot()
      // Per-route cleanup (e.g. decrement WsRoutes.Entry.activeCount).
      // Same idempotency: only fires once per connection because the
      // `key.isValid` guard above keeps the close path single-shot.
      try onTerminate() catch case _: Throwable => ()
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
      // Wake any parked recv consumers with a null sentinel.
      if recvEnabled then recvQueue.offer(null)

  /** The `WebSocket` Value passed to the user's handler.  All four methods
   *  capture `this` so callbacks fire on the live connection. */
  def asValue: Value =
    val send = Value.NativeFnV("WebSocket.send", Computation.pureFn {
      case List(Value.StringV(s)) =>
        enqueue(WsFraming.encodeText(s))
        Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.send(text)")
    })
    // Binary frames take the same Latin-1 byte-view string convention
    // that `req.files(...).bytes` and inbound binary frames already use
    // — one Java `char` per wire byte, round-trips via
    // `bytes.getBytes("ISO-8859-1")` without escape-mangling.
    val sendBytes = Value.NativeFnV("WebSocket.sendBytes", Computation.pureFn {
      case List(Value.StringV(s)) =>
        enqueue(WsFraming.encodeBinary(s.getBytes("ISO-8859-1")))
        Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.sendBytes(bytes)")
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
    // ping / onPong: liveness probe.  ping() sends an empty Ping;
    // ping(s) sends with a Latin-1 byte-view payload that the peer
    // echoes verbatim in its Pong.  Doesn't interact with the
    // server's own 30 s heartbeat — both call sites refresh the
    // same lastPongAt.
    val ping = Value.NativeFnV("WebSocket.ping", Computation.pureFn {
      case Nil =>
        enqueue(WsFraming.encodePing()); Value.UnitV
      case List(Value.StringV(s)) =>
        enqueue(WsFraming.encodePing(s.getBytes("ISO-8859-1"))); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.ping() or ws.ping(payload)")
    })
    val onPong = Value.NativeFnV("WebSocket.onPong", Computation.pureFn {
      case List(cb) =>
        onPongCb = Some(cb); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.onPong { payload => … }")
    })
    // Async-style sync recv — block the calling thread until the next
    // message arrives (Some) or the WS closes (None).  Activates the
    // recv-queue on first use; before that messages flow only through
    // `onMessage`.  Calling thread must be off the WS executor (the
    // user's `onWebSocket` block runs there; recv there would deadlock
    // — fork a Thread / executor for the loop).
    val recv = Value.NativeFnV("WebSocket.recv", Computation.pureFn {
      case Nil =>
        recvEnabled = true
        val v = recvQueue.take()
        if v == null then Value.OptionV(None)
        else Value.OptionV(Some(Value.StringV(v)))
      case _ => throw scalascript.interpreter.InterpretError("ws.recv()")
    })
    val isClosed = Value.NativeFnV("WebSocket.isClosed", Computation.pureFn {
      case Nil => Value.BoolV(closing)
      case _   => throw scalascript.interpreter.InterpretError("ws.isClosed")
    })
    Value.InstanceV("WebSocket", Map(
      "send"      -> send,
      "sendBytes" -> sendBytes,
      "close"     -> close,
      "ping"      -> ping,
      "onMessage" -> onMessage,
      "onClose"   -> onClose,
      "onPong"    -> onPong,
      "recv"      -> recv,
      "isClosed"  -> isClosed,
      "request"     -> request,
      "id"          -> Value.StringV(id),
      "subprotocol" -> Value.StringV(subprotocol)
    ))

  private def ensureInCapacity(target: Int): Unit =
    if target > inBuf.length then
      var cap = inBuf.length
      while cap < target do cap *= 2
      val grown = new Array[Byte](cap)
      System.arraycopy(inBuf, 0, grown, 0, inLen)
      inBuf = grown
