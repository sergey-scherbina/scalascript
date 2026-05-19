package scalascript.server

import java.net.Socket
import java.util.concurrent.{Executor, LinkedBlockingQueue, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
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
    else
      // Mirror the cap-checked count into the metrics surface so
      // `metrics()["ws.active"]` agrees with `activeCount.get`.
      Metrics.wsActive.incrementAndGet()
      true

  def releaseSlot(): Unit =
    activeCount.decrementAndGet()
    Metrics.wsActive.decrementAndGet()

/** Live WebSocket session: blocking-IO model with one writer virtual
 *  thread draining an outbound frame queue, plus a read loop that the
 *  caller runs on its own per-connection virtual thread.  Mirrors
 *  the JvmGen `WebSocket` runtime — same shape, same close orchestration,
 *  same heartbeat scheduler.
 *
 *  Lifecycle:
 *    1. [[WsProxy.proxyConnection]] finishes the HTTP upgrade and
 *       constructs this object.  The constructor spawns one writer
 *       VT that drains [[outQ]] → `socket.getOutputStream`.
 *    2. The user `onWebSocket` handler runs once on the interpreter
 *       executor, called with [[asValue]] — typically the handler registers
 *       `onMessage` / `onClose` callbacks via the methods exposed there.
 *    3. The proxy then calls [[runReadLoop]] on its per-connection VT.
 *       Read-loop blocks on `socket.getInputStream`, parses frames
 *       via `WsFraming.tryParse`, dispatches via `WsFrameDispatch.handle`.
 *    4. On EOF / Close frame / error, the read loop's `finally` either
 *       enqueues a sentinel or hard-closes the socket; the writer VT's
 *       `finally` runs the cleanup (`closeNow`-equivalent) exactly once.
 *
 *  Thread model:
 *    - Read loop: per-connection VT, owned by [[runReadLoop]]'s caller.
 *    - Writer:    per-connection VT, spawned in the constructor.
 *    - Heartbeat: shared `ScheduledExecutorService` (one daemon thread).
 *    - User callbacks: dispatched via `executor` (a single-thread
 *      executor) so interpreter globals stay serial.
 *
 *  `synchronized` on the write path is avoided in favour of an
 *  asynchronous queue: on JDK 21 a `synchronized` block pins the carrier
 *  thread of any virtual thread that enters it.  The single-writer-VT
 *  design also lets a slow peer park its own writer without affecting
 *  any other connection.
 */
final class WsConnection(
    private val socket:     Socket,
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
     *  violation"). */
    private val maxMessagesPerSec: Int = 0,
    /** Payload returned by the route's auth hook (or `None` for
     *  routes without one).  Surfaced to the handler as
     *  `ws.user` so authenticated handlers can read claims /
     *  session info without re-parsing headers. */
    val user: Option[Value] = None
):
  import java.io.{BufferedInputStream, OutputStream}

  /** Stable per-connection identifier.  UUID-v4 generated at upgrade
   *  time so it's globally unique even across restarts, but short
   *  enough to grep for in logs.  Surfaced to the handler as `ws.id`
   *  and used by [[WsConnection]] / [[WsProxy]] to tag every log line
   *  for a single session. */
  val id: String = java.util.UUID.randomUUID().toString

  /** Wall-clock time the connection was upgraded.  Used by the
   *  writer-VT teardown path to compute the `duration_ms` field of
   *  the `ws.close` access log line. */
  private val startedAtMs: Long = System.currentTimeMillis()

  // Fragmented-message reassembly (RFC 6455 §5.4) — delegated to the
  // shared `WsReassembler` state machine in runtime-server-common.
  // Touched only on the read loop thread, so no synchronisation needed.
  private val reassembler = new WsReassembler(WsFraming.MaxFrameBytes)

  // User-side callbacks — registered from the handler thread, fired from
  // the interpreter executor.  `onCloseCb` uses AtomicReference so the
  // close-fires-once rule (against the read-loop / `close()` race) is
  // enforced by a single CAS, not by best-effort `var = null` patterns.
  @volatile private var onMessageCb: Option[Value] = None
  @volatile private var onPongCb:    Option[Value] = None
  // Async-style recv queue — populated by `dispatchMessage` once any
  // consumer flips `recvEnabled` via the first `ws.recv()` call.  Null
  // sentinel signals "WS closed".
  private val recvQueue: LinkedBlockingQueue[String | Null] =
    LinkedBlockingQueue[String | Null]()
  @volatile private var recvEnabled: Boolean = false
  private val onCloseCb: AtomicReference[Value | Null] =
    AtomicReference[Value | Null](null)
  @volatile private var closing: Boolean = false

  // ─── Server-initiated heartbeat ──────────────────────────────────
  // We send an empty Ping every `heartbeatIntervalMs` and expect a
  // Pong back within `deadAfterMs` of our last successful Pong (or
  // the upgrade time, for the first round).
  @volatile private var lastPongAt: Long = System.currentTimeMillis()
  @volatile private var heartbeatTask: ScheduledFuture[?] = null

  // ─── Outbound write queue (mirrors codegen WebSocketRuntime) ─────
  // Every `send` / `close` / `pong` parks a ready-encoded frame on
  // [[outQ]] and returns immediately.  A dedicated writer VT drains
  // the queue and writes to the socket — so a broadcast
  // `clients.foreach { _.send(msg) }` never blocks on the slowest
  // peer.  Bounded (`MaxOutQDepth` frames): if a peer can't keep up
  // the offers start returning false, and we force-close.
  private val MaxOutQDepth: Int = 1024
  private val outQ: LinkedBlockingQueue[Array[Byte]] =
    LinkedBlockingQueue(MaxOutQDepth)
  // Reference-identity sentinel for "drain and exit".  Zero-length
  // payload is distinguishable by `eq` (the encoder always emits
  // at least 2 bytes of header).
  private val SENTINEL: Array[Byte] = new Array[Byte](0)

  private val out: OutputStream = socket.getOutputStream

  // Writer virtual thread — cheap with Loom (~few KB stack).  A
  // parked write-loop costs ~few KB of heap vs ~1 MB for a platform
  // thread stack.  The writer owns the entire teardown sequence in
  // its `finally`, so closing the socket from anywhere unwinds
  // cleanly: both `out.write` (writer) and `in.read` (read loop)
  // throw IOException, both fall into their `finally` clauses, and
  // the writer's tidy-up runs exactly once.
  @scala.annotation.unused private val writerThread: Thread =
    Thread.ofVirtual().name(s"ws-writer-$id").start(() => writeLoop())

  private def writeLoop(): Unit =
    try
      var done = false
      while !done do
        val bytes = outQ.take()
        if bytes eq SENTINEL then done = true
        else
          out.write(bytes)
          out.flush()
          Metrics.wsBytesOut.addAndGet(bytes.length.toLong)
    catch case _: Throwable => () // socket closed mid-write etc.
    finally
      // Stop the heartbeat first so it can't fire after we close.
      val task = heartbeatTask; heartbeatTask = null
      if task != null then task.cancel(false)
      try socket.close() catch case _: Throwable => ()
      // Release the global-cap slot reserved at upgrade time.
      WsConnection.releaseSlot()
      // Per-route cleanup (e.g. decrement WsRoutes.Entry.activeCount).
      try onTerminate() catch case _: Throwable => ()
      // Structured close log (Sprint 4 #13) — one tab-separated line
      // per teardown, regardless of who initiated.
      val durMs = System.currentTimeMillis() - startedAtMs
      log.println(s"ws.close\tid=$id\tduration_ms=$durMs")
      // Atomic getAndSet — at most one caller can win the right to
      // fire onClose.  Guard the `execute` itself with a try because
      // tests can race a `shutdownNow()` against in-flight writer
      // VTs that haven't finished their teardown yet.
      val cb = onCloseCb.getAndSet(null)
      if cb != null then
        try executor.execute { () =>
          try interp.invoke(cb, Nil)
          catch case e: Throwable =>
            log.println(s"WS close handler error: ${e.getMessage}")
        }
        catch case _: java.util.concurrent.RejectedExecutionException => ()
      // Wake any parked recv consumers with a null sentinel.
      if recvEnabled then recvQueue.offer(null)

  /** Force the writer loop to exit promptly when the queue can't
   *  drain.  Used by `send`-overflow and by the read-loop when EOF
   *  arrives.  Idempotent. */
  private def forceShutdown(): Unit =
    if !closing then closing = true
    // Closing the socket breaks both `out.write` (in writer) and
    // `in.read` (in read-loop) with an IOException — both fall
    // into their `catch / finally` clauses and tidy up.
    try socket.close() catch case _: Throwable => ()

  /** Enqueue an already-encoded frame for the writer VT to send.
   *  No-op if the connection is already closing.  Returns silently
   *  on a full queue after a force-shutdown — the writer's finally
   *  is the single source of truth for teardown. */
  private def enqueue(bytes: Array[Byte]): Unit =
    if closing then return
    if !outQ.offer(bytes) then forceShutdown()

  /** Public, thread-safe write: enqueue a pre-encoded frame.
   *  Used by [[asValue]] natives (`ws.send`, `ws.sendBytes`, `ws.ping`). */
  def enqueueFrame(bytes: Array[Byte]): Unit = enqueue(bytes)

  /** Send a Close control frame, then mark the connection as closing.
   *  Enqueues the close frame followed by the SENTINEL so the writer
   *  drains and runs its teardown.  Idempotent. */
  def sendClose(status: Int, reason: String): Unit =
    if !closing then
      closing = true
      val frame = WsFraming.encodeClose(status, reason)
      val queued = outQ.offer(frame) && outQ.offer(SENTINEL)
      if !queued then forceShutdown()

  /** Arm the periodic Ping → Pong heartbeat.  Called by [[WsProxy]]
   *  right after the upgrade so the first ping lands one interval
   *  later. */
  def startHeartbeat(): Unit =
    lastPongAt = System.currentTimeMillis()
    heartbeatTask = scheduler.scheduleAtFixedRate(() => {
      try
        if !closing then
          val now = System.currentTimeMillis()
          if now - lastPongAt > deadAfterMs then
            // Peer hasn't echoed our pings — assume dead.  Queue a
            // Close(1001) + sentinel via sendClose; writer VT does
            // the rest.
            sendClose(1001, "ping timeout")
          else
            // Empty Ping — drop on full outQ (peer too slow); the
            // dead-after timer will then trigger close in a later
            // tick.
            outQ.offer(WsFraming.encodePing())
            ()
      catch case _: Throwable => ()
    }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)

  // Rate-limit state — fixed 1-second window, delegated to the shared
  // `WsRateLimiter`.  Only the read loop touches it, so no sync needed.
  private val rateLimiter = new WsRateLimiter(maxMessagesPerSec)

  /** Dispatch a fully-reassembled text/binary message to the user
   *  `onMessage` callback through the executor. */
  private def dispatchMessage(opcode: WsFraming.Opcode, payload: Array[Byte]): Unit =
    if !rateLimiter.admit(System.currentTimeMillis()) then
      sendClose(1008, "rate limit exceeded")
      return
    Metrics.wsMessagesIn.incrementAndGet()
    val s: String =
      if opcode == WsFraming.Opcode.Text then
        new String(payload, java.nio.charset.StandardCharsets.UTF_8)
      else new String(payload, "ISO-8859-1")
    if recvEnabled then recvQueue.offer(s)
    val v: Value = Value.StringV(s)
    executor.execute { () =>
      onMessageCb.foreach { cb =>
        try interp.invoke(cb, List(v))
        catch case e: Throwable =>
          log.println(s"WS handler error: ${e.getMessage}")
      }
    }

  /** Blocking read loop — called by the proxy on its per-connection
   *  virtual thread after the handshake completes.  Pulls bytes through
   *  the parser, dispatches frames inline.  Returns when the peer
   *  closes or a protocol error occurs.  The `finally` enqueues a
   *  SENTINEL so the writer VT runs the teardown sequence. */
  def runReadLoop(): Unit =
    val in   = BufferedInputStream(socket.getInputStream)
    var buf  = new Array[Byte](4096)
    var len  = 0
    try
      var stop = false
      while !stop && !closing && !socket.isClosed do
        if len == buf.length then
          val grown = new Array[Byte](buf.length * 2)
          System.arraycopy(buf, 0, grown, 0, len)
          buf = grown
        val n = in.read(buf, len, buf.length - len)
        if n < 0 then stop = true
        else
          len += n
          Metrics.wsBytesIn.addAndGet(n.toLong)
          var offset    = 0
          var more      = true
          while more && !stop do
            try WsFraming.tryParse(buf, offset, len) match
              case None     => more = false
              case Some(fr) =>
                offset += fr.consumed
                val outcome = WsFrameDispatch.handle(fr, reassembler,
                  onPing      = p => { outQ.offer(WsFraming.encodePong(p)); () },
                  onPong      = p => {
                    lastPongAt = System.currentTimeMillis()
                    onPongCb.foreach { cb =>
                      val payload = Value.StringV(new String(p, "ISO-8859-1"))
                      executor.execute { () =>
                        try interp.invoke(cb, List(payload))
                        catch case e: Throwable =>
                          log.println(s"WS onPong handler error: ${e.getMessage}")
                      }
                    }
                  },
                  onPeerClose = (status, _) =>
                    // Echo Close back if we haven't sent one yet
                    // (RFC 6455 §5.5.1).  sendClose is idempotent.
                    if !closing then sendClose(status, "") else (),
                  onDeliver   = (op, bytes) => dispatchMessage(op, bytes),
                  onProtocolError = (code, reason) => sendClose(code, reason))
                if outcome == WsFrameDispatch.Outcome.Stop then stop = true
            catch case _: WsFraming.WsProtocolError =>
              sendClose(1002, "protocol error")
              stop = true
          if offset > 0 then
            System.arraycopy(buf, offset, buf, 0, len - offset)
            len -= offset
    catch case _: Throwable => ()
    finally
      // Tell the writer VT to drain and exit; its `finally` runs the
      // actual `socket.close()` + onClose dispatch.  If the sentinel
      // can't be queued (full backlog) we force-close the socket so
      // the writer's blocking `out.write` throws and runs its cleanup.
      closing = true
      if !outQ.offer(SENTINEL) then
        try socket.close() catch case _: Throwable => ()

  /** The `WebSocket` Value passed to the user's handler.  All four methods
   *  capture `this` so callbacks fire on the live connection. */
  def asValue: Value =
    val send = Value.NativeFnV("WebSocket.send", Computation.pureFn {
      case List(Value.StringV(s)) =>
        Metrics.wsMessagesOut.incrementAndGet()
        enqueue(WsFraming.encodeText(s))
        Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.send(text)")
    })
    val sendBytes = Value.NativeFnV("WebSocket.sendBytes", Computation.pureFn {
      case List(Value.StringV(s)) =>
        Metrics.wsMessagesOut.incrementAndGet()
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
      "subprotocol" -> Value.StringV(subprotocol),
      "user"        -> Value.OptionV(user)
    ))
