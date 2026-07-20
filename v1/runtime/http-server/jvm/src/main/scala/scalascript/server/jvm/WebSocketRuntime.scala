package scalascript.server.jvm

// Phase 3c — JVM-codegen WebSocket runtime (Part2 lines 3239-3698 of
// the original serveRuntime string template, ~460 LOC).  Asymmetric
// design vs the interpreter (which runs an NIO selector loop in front
// of the JDK HttpServer): here we use a blocking ServerSocket and one
// virtual thread per connection.  WsFrameDispatch + WsReassembler +
// WsRateLimiter come from runtime-server-common, this file owns the
// per-connection state, write loop, heartbeat, close orchestration,
// onWebSocket route registration, and the Metrics / Framing adapter
// shims that surrounding code calls.

// BUILD-ONLY:start
// At scala-cli inline time the script has Metrics, WsFraming,
// WsHandshake etc. in scope at top level (from runtime-server-common).
// For our standalone build we import them; the import is stripped at
// inline time.
import scalascript.server.*
import scalascript.server.WsFraming
import scalascript.server.spi.WsControls
import scalascript.logging.Logger
// BUILD-ONLY:end

private val _svcLog = Logger("scalascript.server")

// ── WebSocket support (RFC 6455) ───────────────────────────────────────
//
// Asymmetric design vs the interpreter: the interpreter runs an NIO
// selector loop in front of the JDK `HttpServer`; here we use a
// blocking-IO proxy with **one virtual thread per connection**
// (Project Loom, JDK 21+).  A parked virtual thread on a slow read
// costs ~few KB of heap rather than a 1 MB platform-thread stack,
// so the scale ceiling is now file descriptors, not threads.
// The user-facing `onWebSocket(path) { ws => … }` API is identical
// to the interpreter, so .ssc code is portable.  Full NIO migration
// will land alongside the HTTP NIO rewrite.
//
// Threading: user callbacks (`onWebSocket` body, `onMessage`,
// `onClose`) all dispatch through `_serverExecutor`, a single-
// platform-thread executor that also backs the internal HttpServer.
// That way mutations to top-level `var`s from HTTP handlers and WS
// callbacks are serial — no cross-handler races even though every
// WS read-loop runs on its own virtual thread.
//
// `synchronized` on the WebSocket write path is avoided in favour of
// `ReentrantLock`: on JDK 21 a `synchronized` block pins the carrier
// thread of any virtual thread that enters it.  (Pinning was removed
// in JDK 24; we keep the lock for portability.)

private val _serverExecutor: java.util.concurrent.ExecutorService =
  java.util.concurrent.Executors.newSingleThreadExecutor(r => {
    val t = Thread(r, "ssc-server"); t.setDaemon(true); t
  })

// Shared scheduler driving the periodic Ping heartbeat across every
// active WebSocket — single daemon thread, cheap.
private val _wsHeartbeats: java.util.concurrent.ScheduledExecutorService =
  java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
    val t = Thread(r, "ws-heartbeats"); t.setDaemon(true); t
  })

class WebSocket(
    /** Per-connection socket (TLS or plain).  `null` in SPI mode
     *  (`_spiControls != null`) — the SPI impl owns the socket and
     *  drives the read/write loops itself; this instance is just a
     *  thin user-facing delegator over `_spiControls`. */
    private val socket: java.net.Socket | Null,
    val request: Request,
    /** The subprotocol the server selected during upgrade
     *  negotiation (RFC 6455 §1.9), or "" when no negotiation
     *  took place.  `ws.request.headers("sec-websocket-protocol")`
     *  still carries the client's full offer list. */
    val subprotocol: String = "",
    /** Per-route cleanup callback fired exactly once when this
     *  connection terminates.  Used to decrement the route's
     *  active-connection counter so the per-route cap recovers. */
    private val _onTerminate: () => Unit = () => (),
    /** Cap on inbound messages per second on this connection.
     *  0 = unlimited.  Overrun closes with code 1008.  See
     *  the fixed-window counter in `_dispatchWsMessage`. */
    private val _maxMessagesPerSec: Int = 0,
    /** Payload returned by the route's auth hook (or None for
     *  routes without one).  Surfaced to handlers as
     *  `ws.user` so authenticated routes don't re-parse
     *  headers / claims / sessions inside the body. */
    val user: Option[Any] = None,
    /** Single-thread executor for user-callback dispatch.  Both
     *  backends pass a single-thread executor here so user-state
     *  mutations stay serial.  Defaults to the codegen-side
     *  `_serverExecutor`; the interpreter passes its own
     *  per-server executor so HTTP and WS handlers share the same
     *  serial queue. */
    private val _executor: java.util.concurrent.Executor = _serverExecutor,
    /** Scheduled executor for the periodic Ping heartbeat.
     *  Defaults to the codegen-side shared `_wsHeartbeats`; the
     *  interpreter passes its own scheduler so the WsProxy can
     *  shut it down cleanly between test runs. */
    private val _heartbeats: java.util.concurrent.ScheduledExecutorService = _wsHeartbeats,
    /** Heartbeat tuning, hoisted so tests can shrink the interval
     *  and assert dead-peer drop without sitting through the real
     *  30 s ping / 90 s timeout. */
    private val _heartbeatIntervalMs: Long = 30_000L,
    private val _deadAfterMs:         Long = 90_000L,
    /** Stream sink for the structured `ws.connect` / `ws.close`
     *  access log.  Defaults to `System.out` to match the
     *  codegen-side `println(...)` calls; the interpreter passes
     *  its server's `log: PrintStream` so test captures pick up
     *  the lines. */
    private val _log: java.io.PrintStream = System.out,
    /** v1.17.6 / Phase S1c — when non-null, this WebSocket is an
     *  SPI-mode delegator: every public method (send / sendBytes /
     *  close / ping / recv / isClosed) routes through the SPI's
     *  `WsControls` and the I/O loops in this class are inert.
     *  Used by the codegen-emitted `serve(port, tls)` to bridge an
     *  SPI-managed connection back into the `WebSocket` shape that
     *  user `onWebSocket { ws => … }` handlers expect.  `null` in
     *  socket-driven JdkServerBackend internal use + interpreter
     *  WsProxy.
     *
     *  The type is referenced unqualified (`WsControls`) so the
     *  loader's package-strip doesn't break the inlined script — the
     *  SPI's `WsControls` trait is inlined just above in the
     *  serveRuntime block and lives at top level in the generated
     *  script. */
    private val _spiControls: WsControls | Null = null):
  /** Stable per-connection identifier.  UUID-v4 generated at
   *  upgrade time; surfaced to user code as `ws.id` and used to
   *  tag every log line for a single session. */
  val id: String = java.util.UUID.randomUUID().toString
  /** Wall-clock time of upgrade — feeds `duration_ms` into the
   *  Sprint-4 close log. */
  private val _startedAtMs: Long = java.lang.System.currentTimeMillis()
  import java.io.{BufferedInputStream, OutputStream}
  import java.nio.charset.StandardCharsets
  import java.util.concurrent.{LinkedBlockingQueue, ScheduledFuture, TimeUnit}
  import java.util.concurrent.atomic.AtomicReference
  @volatile private var onMessageCb: String => Unit = null
  @volatile private var onPongCb:    String => Unit = null
  // AtomicReference so close-fires-once is enforced by a CAS,
  // not by a best-effort `var = null` read/write race between
  // the writer-VT's finally and any caller-side `close()`.
  private val onCloseCb = AtomicReference[() => Unit](null)
  @volatile private var closing:     Boolean        = false
  // Server-initiated heartbeat: empty Ping every 30 s, drop the
  // connection if no Pong arrives within 90 s.  Catches NAT-dropped
  // and silently-half-closed peers well before OS keepalive does.
  // Tunable via the `_heartbeatIntervalMs` / `_deadAfterMs` ctor
  // params so tests can drop both to the millisecond range.
  private val HeartbeatIntervalMs: Long = _heartbeatIntervalMs
  private val DeadAfterMs:         Long = _deadAfterMs
  @volatile private var lastPongAt: Long = java.lang.System.currentTimeMillis()
  @volatile private var heartbeatTask: ScheduledFuture[?] = null
  // Fragmented-message reassembly (RFC 6455 §5.4) delegated to
  // the shared `WsReassembler` (inlined from runtime-server-common).
  // The read-loop dispatches control frames (Ping/Pong/Close)
  // inline and routes Text/Binary/Continuation frames through
  // `_reassembler.feed(...)` — its `Event` enum is the one place
  // the FIN=0 / opcode=0 / oversize logic lives.  Touched only on
  // the read-loop thread, so no synchronisation needed.
  private val _reassembler = new WsReassembler(_WsMaxFrameBytes)
  // In socket mode (`_spiControls == null`) we grab the OutputStream
  // up front so the writer-VT can park on it.  In SPI mode the
  // `_spiControls` impl owns the socket, so `out` is null and the
  // writer-VT is never started.
  private val out: OutputStream | Null              =
    if _spiControls != null then null else socket.getOutputStream

  // Outbound write queue: every `send` / `close` / `pong` parks a
  // ready-encoded frame here and returns immediately.  A dedicated
  // writer virtual thread drains the queue and writes to the
  // socket — so a broadcast `clients.foreach { _.send(msg) }`
  // never blocks on the slowest peer.  The queue is bounded
  // (`MaxOutQDepth` frames): if a peer can't keep up the offers
  // start returning false, and we force-close.
  private val MaxOutQDepth = 1024
  private val outQ: LinkedBlockingQueue[Array[Byte]] = LinkedBlockingQueue(MaxOutQDepth)
  // Reference-identity sentinel for "drain and exit".  A zero-
  // length payload is distinguishable by `eq` (we never enqueue
  // an actual zero-length frame; the encoder always emits at
  // least 2 bytes of header).
  private val SENTINEL: Array[Byte] = new Array[Byte](0)
  // Writer virtual thread — cheap with Loom (~few KB stack).
  // Java 21 requirement: Thread.ofVirtual() is stable in JDK 21 LTS (Project Loom).
  // A parked write-loop costs ~few KB of heap vs ~1 MB for a platform thread stack.
  // In SPI mode the writer-VT is unnecessary (sends go through
  // `_spiControls.send(...)` directly); skip it to avoid a parked VT
  // that would never see traffic.
  @scala.annotation.unused private val writerThread: Thread | Null =
    if _spiControls != null then null
    else Thread.ofVirtual().name("ws-writer").start(() => _writeLoop())

  private def _writeLoop(): Unit =
    try
      var done = false
      while !done do
        val bytes = outQ.take()
        if bytes eq SENTINEL then done = true
        else
          // out.write is the only blocking op here, and we own
          // its only caller — no lock needed.  A slow peer parks
          // this VT (cheap); other connections' writers are
          // unaffected.
          out.write(bytes)
          out.flush()
    catch case _: Throwable => () // socket closed mid-write etc.
    finally
      // Stop the heartbeat first so it can't fire after we close.
      val t = heartbeatTask; heartbeatTask = null
      if t != null then t.cancel(false)
      try socket.close() catch case _: Throwable => ()
      // Release the global-cap slot reserved at upgrade time.
      _wsActiveCount.decrementAndGet()
      _Metrics.wsActive.decrementAndGet()
      // Structured close log (Sprint 4 #13).  One tab-separated
      // line per teardown, regardless of who initiated.
      val _durMs = java.lang.System.currentTimeMillis() - _startedAtMs
      _log.println("ws.close\tid=" + id + "\tduration_ms=" + _durMs)
      // Per-route cleanup (e.g. decrement the route's activeCount).
      // No-op when the route had no per-route cap.
      try _onTerminate() catch case _: Throwable => ()
      val cb = onCloseCb.getAndSet(null)
      if cb != null then
        // Guard the dispatch itself — tests race a `shutdownNow()`
        // against in-flight writer VTs that haven't finished teardown,
        // and a `RejectedExecutionException` here would spam stderr
        // for tests that have already torn down their executor.
        try _executor.execute { () =>
          try cb() catch case e: Throwable =>
            _svcLog.warn(s"WS close handler: ${e.getMessage}")
        }
        catch case _: java.util.concurrent.RejectedExecutionException => ()
      // Wake any parked recv consumers with a sentinel `null`.
      _deliverRecv(null)

  /** Force the writer loop to exit promptly when the queue can't
   *  drain.  Used by `send`-overflow and by the read-loop when EOF
   *  arrives.  Idempotent. */
  private def _forceShutdown(): Unit =
    if !closing then closing = true
    // Closing the socket breaks both `out.write` (in writer) and
    // `in.read` (in read-loop) with an IOException — both fall
    // into their `catch / finally` clauses and tidy up.
    try socket.close() catch case _: Throwable => ()

  def send(s: String): Unit =
    val c = _spiControls
    if c != null then c.send(s)
    else
      if closing then return
      val frame = _wsEncodeText(s)
      if !outQ.offer(frame) then _forceShutdown()
      else { _Metrics.wsMessagesOut.incrementAndGet(); _Metrics.wsBytesOut.addAndGet(frame.length.toLong) }

  // Binary frames take the Latin-1 byte-view convention the rest
  // of the runtime already uses (UploadedFile.bytes, inbound
  // binary frames): one Java char per wire byte.
  def sendBytes(s: String): Unit =
    val c = _spiControls
    if c != null then c.sendBytes(s.getBytes("ISO-8859-1"))
    else
      if closing then return
      val frame = WsFraming.encodeBinary(s.getBytes("ISO-8859-1"))
      if !outQ.offer(frame) then _forceShutdown()
      else { _Metrics.wsMessagesOut.incrementAndGet(); _Metrics.wsBytesOut.addAndGet(frame.length.toLong) }

  def close(code: Int = 1000, reason: String = ""): Unit =
    val c = _spiControls
    if c != null then c.close(code, reason)
    else
      if closing then return
      closing = true
      // Best-effort: enqueue the close frame, then a sentinel so
      // the writer drains and exits cleanly.  If the queue is full
      // (slow peer), fall through to a hard socket close — the
      // writer's finally handles onClose dispatch either way.
      val queued = outQ.offer(_wsEncodeClose(code, reason)) && outQ.offer(SENTINEL)
      if !queued then _forceShutdown()

  def onMessage(cb: String => Unit): Unit = onMessageCb = cb
  def onClose(cb: () => Unit): Unit       = onCloseCb.set(cb)
  def onPong(cb: String => Unit): Unit    = onPongCb   = cb

  /** SPI-mode dispatch hooks — called by `_CodegenWsListener` (in
   *  ProxyRuntime) when the SPI delivers an incoming message / pong /
   *  close.  They mirror the socket-mode `_dispatchWsMessage` /
   *  read-loop close path but skip the rate-limiting + metrics layer
   *  (the SPI impl already enforced those before delivery).  No-op in
   *  socket mode (the regular read loop drives the callbacks
   *  directly).
   *
   *  Public (rather than `private[jvm]`) because the loader rewrites
   *  the qualified-private form to bare `private` at inline time,
   *  which would prevent `_CodegenWsListener` from reaching them. */
  def _spiFireMessage(s: String): Unit =
    val cb = onMessageCb
    if cb != null then try cb(s) catch case e: Throwable =>
      _svcLog.warn(s"WS message handler: ${e.getMessage}")
  def _spiFirePong(p: String): Unit =
    val cb = onPongCb
    if cb != null then try cb(p) catch case e: Throwable =>
      _svcLog.warn(s"WS onPong handler: ${e.getMessage}")
  def _spiFireClose(): Unit =
    val cb = onCloseCb.getAndSet(null)
    if cb != null then try cb() catch case e: Throwable =>
      _svcLog.warn(s"WS close handler: ${e.getMessage}")

  // ── Async-style blocking recv (alternative to onMessage cb) ────
  //
  // Lets a handler read messages with a `while !ws.isClosed do …`
  // loop instead of inverting control through `onMessage`.  Works
  // because each WS connection runs the user handler on its own
  // virtual thread (Loom), so a parked recv blocks just that VT.
  // `_recvQueue` is lazily populated by `_deliverMessage` once
  // `recv` (or `recvBytes`) is first called.
  // Reference-identity sentinel for "close" on the recv path.
  // `LinkedBlockingQueue` rejects literal `null` with a NPE, so we
  // route the close signal through a distinguished `String` instance
  // and `eq`-check it on the consumer side.  An empty user message
  // is a *value-equal* "" String, never the same object.
  private val RECV_CLOSE_SENTINEL: String = new String("__ssc_ws_recv_close__")
  private val _recvQueue: LinkedBlockingQueue[String] = LinkedBlockingQueue()
  @volatile private var _recvEnabled: Boolean = false

  /** Block the calling thread until a message arrives or the WS
   *  closes.  Returns Some(msg) on a text/binary message, None on
   *  close. */
  def recv(): Option[String] =
    val c = _spiControls
    if c != null then c.recv()
    else
      _recvEnabled = true
      val v = _recvQueue.take()
      if v eq RECV_CLOSE_SENTINEL then None else Some(v)

  /** True once the close-frame has been sent or received. */
  def isClosed: Boolean =
    val c = _spiControls
    if c != null then c.isClosed else closing

  /** Called by the read-loop after each fully-reassembled message
   *  (and once with `null` on close).  Pushes into the recv-queue
   *  if any recv-style consumer has activated it; otherwise falls
   *  through to the callback-style API.  Cost when nobody calls
   *  recv: a single volatile read. */
  def _deliverRecv(payload: String | Null): Unit =
    if _recvEnabled then
      // Java's LinkedBlockingQueue.offer rejects null with a NPE,
      // so translate "close" (null payload) into the sentinel.
      val v: String = if payload == null then RECV_CLOSE_SENTINEL else payload
      _recvQueue.offer(v)

  /** ping([payload]): empty Ping or Latin-1-byte-view payload that
    * the peer echoes back as a Pong (delivered via `onPong`).
    * Doesn't interfere with the server-side 30 s heartbeat;
    * both call sites refresh the same `lastPongAt`. */
  def ping(): Unit = ping("")
  def ping(payload: String): Unit =
    val c = _spiControls
    val bytes = if payload.isEmpty then Array.emptyByteArray else payload.getBytes("ISO-8859-1")
    if c != null then c.ping(bytes)
    else
      if closing then return
      if !outQ.offer(_wsEncodePing(bytes)) then _forceShutdown()

  /** Arm the periodic Ping → Pong heartbeat.  Called once by
    * `_proxyConnection` right after the upgrade. */
  def _startHeartbeat(): Unit =
    lastPongAt = java.lang.System.currentTimeMillis()
    // Guard the submit against a shutdown race: a server `stop()` can
    // `shutdownNow()` this scheduler between the accept and here, so
    // `scheduleAtFixedRate` throws RejectedExecutionException.  The
    // connection is being torn down anyway (the read loop below hits the
    // closed socket and runs its teardown), so skip the heartbeat silently
    // rather than let the exception escape the per-connection thread and
    // print as a spurious `##[error]` in CI logs.
    try
      heartbeatTask = _heartbeats.scheduleAtFixedRate(() => {
        try
          if java.lang.System.currentTimeMillis() - lastPongAt > DeadAfterMs then
            close(1001, "ping timeout")
          else if !closing then
            // Empty Ping — _wsEncodePing's payload is unused except
            // for the wire byte.  Drop on full outQ (peer too slow).
            outQ.offer(_wsEncodePing(Array.emptyByteArray))
        catch case _: Throwable => ()
      }, HeartbeatIntervalMs, HeartbeatIntervalMs, TimeUnit.MILLISECONDS)
    catch case _: java.util.concurrent.RejectedExecutionException => ()

  // Read-loop entry point: called from the per-connection thread
  // after the handshake completes.  Pulls bytes through the parser,
  // dispatches frames synchronously on the same thread.  Returns
  // when the peer closes or a protocol error occurs.
  def _runReadLoop(): Unit =
    var buf  = new Array[Byte](4096)
    var len  = 0
    try
      // `socket.getInputStream` can itself throw "Socket is closed" when a
      // teardown races the accept (same shutdown race as the heartbeat
      // above); keep it inside the try so the finally still runs the
      // teardown and the exception never escapes the per-connection thread
      // as a spurious CI `##[error]`.
      val in   = BufferedInputStream(socket.getInputStream)
      while !closing && !socket.isClosed do
        if len == buf.length then
          val grown = new Array[Byte](buf.length * 2); System.arraycopy(buf, 0, grown, 0, len); buf = grown
        val n = in.read(buf, len, buf.length - len)
        if n < 0 then return
        len += n
        var offset = 0
        var more   = true
        while more do
          WsFraming.tryParse(buf, offset, len) match
            case None     => more = false
            case Some(fr) =>
              offset += fr.consumed
              val outcome = WsFrameDispatch.handle(fr, _reassembler,
                // Drop the pong if the write queue is full — peer is too
                // slow to keep up with the data flow we're trying to
                // send them anyway; the next ping will time out and the
                // writer will force-close.
                onPing      = p => { outQ.offer(_wsEncodePong(p)); () },
                onPong      = p => {
                  lastPongAt = java.lang.System.currentTimeMillis()
                  val cb = onPongCb
                  if cb != null then
                    val payload = new String(p, "ISO-8859-1")
                    _executor.execute { () =>
                      try cb(payload) catch case e: Throwable =>
                        _svcLog.warn(s"WS onPong handler: ${e.getMessage}")
                    }
                },
                onPeerClose = (status, _) => close(status, ""),
                onDeliver   = (op, bytes) => _dispatchWsMessage(op.code, bytes),
                onProtocolError = (code, reason) => close(code, reason))
              if outcome == WsFrameDispatch.Outcome.Stop then return
        if offset > 0 then
          System.arraycopy(buf, offset, buf, 0, len - offset)
          len -= offset
    catch case _: Throwable => ()
    finally
      // Tell the writer to drain and exit; its `finally` does the
      // actual `socket.close()` + onClose dispatch.  If the
      // sentinel can't be queued (full backlog) we force-close
      // the socket so the writer's blocking `out.write` throws
      // and runs its cleanup.
      closing = true
      if !outQ.offer(SENTINEL) then
        try socket.close() catch case _: Throwable => ()

  /** Hand a fully-reassembled text/binary payload to the user
   *  `onMessage` callback via the shared executor.  Queued
   *  unconditionally — the `onWebSocket` body also runs on the
   *  executor, so reading the callback inside the task gives us
   *  the up-to-date value. */
  // Rate-limit state — fixed 1-second window, delegated to the
  // shared `WsRateLimiter`.  Only the read loop touches it, so
  // no synchronisation needed.
  private val _rateLimiter = new WsRateLimiter(_maxMessagesPerSec)

  private def _dispatchWsMessage(opcode: Int, payload: Array[Byte]): Unit =
    if !_rateLimiter.admit(java.lang.System.currentTimeMillis()) then
      close(1008, "rate limit exceeded"); return
    _Metrics.wsMessagesIn.incrementAndGet()
    _Metrics.wsBytesIn.addAndGet(payload.length.toLong)
    val msg =
      if opcode == 0x1 then new String(payload, StandardCharsets.UTF_8)
      else                  new String(payload, "ISO-8859-1")
    // Async-style recv path: parked consumers wake on the next take.
    _deliverRecv(msg)
    _executor.execute { () =>
      val cb = onMessageCb
      if cb != null then try cb(msg) catch case e: Throwable =>
        _svcLog.warn(s"WS message handler: ${e.getMessage}")
    }

private final case class _WsRoute(
  pattern:   List[_Seg],
  handler:   WebSocket => Unit,
  origins:   List[String] = Nil,  // empty = no Origin restriction
  protocols: List[String] = Nil,  // empty = no subprotocol negotiation
  // Per-route active-connection cap.  0 = unlimited; positive
  // values refuse upgrades past the cap with 503.  Composes
  // with the process-wide `setMaxWsConnections` cap.
  maxConnections: Int = 0,
  // Per-connection inbound message rate cap (msgs/sec).
  // 0 = unlimited; overrun closes the offending client with
  // code 1008.  Applied per connection on this route.
  maxMessagesPerSec: Int = 0,
  // Pre-upgrade auth hook.  None = no check; Some(fn) =
  // invoke fn with the Request before reserving any slot.
  // fn returns Some(payload) to accept (payload becomes
  // ws.user) or None to reject with HTTP 401.  Runs on the
  // dispatch thread, so it must be read-only.
  auth: Option[Request => Option[Any]] = None,
  activeCount: java.util.concurrent.atomic.AtomicInteger =
               java.util.concurrent.atomic.AtomicInteger(0)
):
  def tryReserve(): Boolean =
    if maxConnections <= 0 then true
    else
      val after = activeCount.incrementAndGet()
      if after > maxConnections then { activeCount.decrementAndGet(); false }
      else true
  def release(): Unit =
    if maxConnections > 0 then activeCount.decrementAndGet()
private val _wsRoutes = scala.collection.mutable.ArrayBuffer.empty[_WsRoute]

// ── Process-wide metrics (Sprint 4 #14) ─────────────────────────
// Single source of truth lives in scalascript.server.Metrics
// (inlined from runtime-server-common).  `_Metrics` is kept as a
// local alias so existing internal call sites — wsActive,
// wsUpgraded, httpRequests, … — keep their `_Metrics.foo` form.
private val _Metrics = Metrics

/** Snapshot of process-wide counters — `Map[String, Long]`,
  * same key names as the interpreter's `metrics()` native. */
def metrics(): Map[String, Long] = _Metrics.snapshot()

// Process-wide cap on active WS sessions.  Tuned with
// `setMaxWsConnections(n)`; default = unlimited.  Upgrades past
// the cap are refused with 503.  Decremented in the writer-VT
// teardown via `_wsReleaseSlot` (interpreter side delegates here
// through `scalascript.server.WsConnection` so both backends
// share one process-wide cap).
val _wsMaxActive    = java.util.concurrent.atomic.AtomicInteger(Int.MaxValue)
val _wsActiveCount  = java.util.concurrent.atomic.AtomicInteger(0)
def setMaxWsConnections(n: Int): Unit =
  _wsMaxActive.set(if n < 0 then Int.MaxValue else n)
def _wsTryReserve(): Boolean =
  val after = _wsActiveCount.incrementAndGet()
  if after > _wsMaxActive.get then { _wsActiveCount.decrementAndGet(); false }
  else { _Metrics.wsActive.incrementAndGet(); true }
def _wsReleaseSlot(): Unit =
  _wsActiveCount.decrementAndGet()
  _Metrics.wsActive.decrementAndGet()

/** WsRoom — thread-safe registry of WebSocket clients with a
  * built-in `broadcast(msg)` helper.  Spawn one per logical
  * channel (e.g. one room per chat room) and let the handler
  * `add` / `remove` itself in onMessage / onClose. */
class WsRoom:
  private val members = java.util.concurrent.CopyOnWriteArrayList[WebSocket]()
  def add(ws: WebSocket): Unit    = members.add(ws)
  def remove(ws: WebSocket): Unit = members.remove(ws)
  def broadcast(msg: String): Unit =
    val it = members.iterator()
    while it.hasNext do
      try it.next().send(msg)
      catch case _: Throwable => () // dead client, will be reaped via onClose
  def size: Int = members.size

/** Companion object so user code reads naturally as `WsRoom()`. */
object WsRoom:
  def apply(): WsRoom = new WsRoom

/** Single `onWebSocket` def with defaulted args — the trailing
  * `origins`, `protocols`, `maxConnections`, `maxMessagesPerSec`
  * arguments default to "no restriction".  Collapsing five
  * overloads into one avoids the v2.0 linker's same-name dedup
  * pass dropping all but the first when modules concatenate.
  *
  *  - `origins` (default `Nil`)        — only accept upgrades whose
  *    `Origin:` header is in the list.  Browser CSRF guard.
  *  - `protocols` (default `Nil`)      — Sec-WebSocket-Protocol
  *    negotiation; server picks the first protocol in its list
  *    that's in the request, no match → 400.
  *  - `maxConnections` (default `0`)   — per-route active-connection
  *    cap; 0 = unlimited; positive values refuse upgrades past
  *    the cap with 503.  Composes with the process-wide
  *    `setMaxWsConnections`.
  *  - `maxMessagesPerSec` (default `0`) — per-connection inbound
  *    message rate cap; overrun closes the offending client with
  *    code 1008 ("policy violation").  0 = unlimited. */
def onWebSocket(
    path:              String,
    origins:           List[String] = Nil,
    protocols:         List[String] = Nil,
    maxConnections:    Int          = 0,
    maxMessagesPerSec: Int          = 0
): (WebSocket => Unit) => Unit = (handler) => {
  _wsRoutes += _WsRoute(_parsePath(path), handler, origins, protocols, maxConnections, maxMessagesPerSec)
}

/** Pre-upgrade auth hook.  `authFn(req)` returns `Some(payload)`
  * to accept the upgrade (payload becomes `ws.user`) or `None`
  * to reject with HTTP 401 before the WebSocket is even built.
  * Hook runs on the dispatch thread — must be read-only over
  * mutable state. */
def onWebSocketAuth(path: String, authFn: Request => Option[Any]): (WebSocket => Unit) => Unit = (handler) => {
  _wsRoutes += _WsRoute(_parsePath(path), handler, auth = Some(authFn))
}

// ── Framing — adapter shims around WsFraming ─────────────────────────
// RFC 6455 frame codec + per-frame dispatch live in
// scalascript.server.WsFraming / WsFrameDispatch (inlined from
// runtime-server-common).  The shims here just re-export the
// frequently-used `_wsEncode*` / `_wsAcceptKey` names; the read
// loop calls `WsFraming.tryParse` + `WsFrameDispatch.handle`
// directly so it carries no legacy bare-Int opcode matches.
private val _WsMaxFrameBytes: Int = WsFraming.MaxFrameBytes
def _wsAcceptKey(clientKey: String): String = WsFraming.acceptKey(clientKey)
def _wsEncodeText(s: String): Array[Byte]      = WsFraming.encodeText(s)
def _wsEncodePong(p: Array[Byte]): Array[Byte] = WsFraming.encodePong(p)
def _wsEncodePing(p: Array[Byte]): Array[Byte] = WsFraming.encodePing(p)
def _wsEncodeClose(code: Int, reason: String): Array[Byte] = WsFraming.encodeClose(code, reason)

