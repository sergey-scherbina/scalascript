package scalascript.server

import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.Executor
import scalascript.interpreter.Value

/** Blocking-IO proxy sitting in front of an internal JDK `HttpServer`.
 *  One accept thread + one virtual thread per connection.  Each
 *  per-connection VT sniffs the request head, then either:
 *
 *    - Upgrade: websocket — performs the RFC 6455 handshake, builds a
 *      [[WsConnection]] (which spawns its own writer VT) and runs the
 *      blocking read-loop on this same VT.  Falls through to the
 *      writer VT's `finally` for teardown.
 *
 *    - Plain HTTP — opens a backend socket to the internal HttpServer
 *      and pumps bytes both ways through two more VTs until either
 *      side EOFs.
 *
 *  Why an extra hop in front of an existing HTTP server?  Because
 *  `com.sun.net.httpserver.HttpServer` hides the raw socket — there's no
 *  upgrade hook on it.  Rather than rewrite the whole HTTP stack to
 *  unblock WebSockets, this proxy lets the existing stack keep handling
 *  REST traffic untouched while WS lives alongside it.
 *
 *  Threading: per-connection VT (Loom) means a parked read costs a few KB
 *  of heap rather than a 1 MB platform-thread stack.  WS application
 *  callbacks (`onMessage`, `onClose`, `onPong`) still dispatch to
 *  `wsExecutor`, a single-thread executor shared with the interpreter's
 *  HTTP handlers — interpreter globals stay serial.  This mirrors the
 *  codegen `serveRuntime._proxyConnection` exactly. */
final class WsProxy(
    publicPort:   Int,
    internalAddr: InetSocketAddress,
    wsExecutor:   Executor,
    log:          java.io.PrintStream,
    /** Per-interpreter WS route table.  Each `Interpreter` owns one
     *  `WsRoutes` instance; passing it here isolates this proxy's route
     *  lookups from any other interpreter running in the same JVM. */
    wsRoutes:     WsRoutes,
    /** Heartbeat tuning forwarded to every accepted [[WsConnection]].
     *  Defaults match the production policy (30 s ping, 90 s dead-after);
     *  tests may shrink both to assert the round-trip in seconds. */
    heartbeatIntervalMs: Long = 30_000L,
    heartbeatDeadAfterMs: Long = 90_000L
):
  private val server: ServerSocket = ServerSocket()
  @volatile private var running: Boolean = false
  @scala.annotation.unused private var acceptThread: Thread = null
  // One shared scheduler drives the periodic heartbeat across every
  // active WsConnection.  Single daemon thread — heartbeats are
  // every-30s lightweight tasks, no need for a pool.
  private val heartbeats: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
      val t = Thread(r, "ws-heartbeats"); t.setDaemon(true); t
    })
  // Per-connection virtual thread pool.  Loom-backed: a parked read
  // costs ~few KB heap rather than a 1 MB platform thread stack.
  private val connPool: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("ws-proxy-conn-", 0L).factory()
    )

  // ─── Lifecycle ────────────────────────────────────────────────────

  def start(): Unit =
    server.setReuseAddress(true)
    server.bind(InetSocketAddress(publicPort))
    running = true
    val t = Thread(() => acceptLoop(), s"ws-proxy-accept-$publicPort")
    t.setDaemon(true)
    acceptThread = t
    t.start()

  def stop(): Unit =
    running = false
    try server.close() catch case _: Throwable => ()
    try heartbeats.shutdownNow() catch case _: Throwable => ()
    try connPool.shutdownNow() catch case _: Throwable => ()

  def localPort: Int = server.getLocalPort

  /** True once this proxy — or the interpreter-owned `wsExecutor` — has
   *  begun shutting down.  `wsExecutor` is not owned here (the interpreter
   *  / test creates and stops it), so a dispatch onto it can lose a race
   *  with that shutdown; a `RejectedExecutionException` is only benign in
   *  this state.  Anything else is a real bug and must propagate. */
  private def tearingDown: Boolean =
    !running || (wsExecutor match
      case es: java.util.concurrent.ExecutorService => es.isShutdown
      case _                                        => false)

  // ─── Accept loop ──────────────────────────────────────────────────

  private def acceptLoop(): Unit =
    while running && !server.isClosed do
      try
        val client = server.accept()
        connPool.execute { () => proxyConnection(client) }
      catch
        case _: java.net.SocketException if !running => () // shutting down
        case e: Throwable                            =>
          if running then log.println(s"ws-proxy accept error: ${e.getMessage}")

  // ─── Per-connection driver (runs on a virtual thread) ─────────────

  private def proxyConnection(client: Socket): Unit =
    try client.setKeepAlive(true) catch case _: Throwable => ()
    try client.setTcpNoDelay(true) catch case _: Throwable => ()
    val cin  = java.io.BufferedInputStream(client.getInputStream)
    val cout = client.getOutputStream
    val head =
      try HttpHelpers.readHttpHead(cin)
      catch case _: Throwable => Array.emptyByteArray
    if head.isEmpty then
      try client.close() catch case _: Throwable => ()
      return
    val parsed = HttpHelpers.parseHttpHead(head)
    if parsed.isUpgradeWebSocket then
      tryUpgrade(client, cin, cout, parsed.path, parsed.rawQuery, parsed.headers)
    else
      forwardHttp(client, cin, cout, head)

  // ─── WebSocket upgrade path ───────────────────────────────────────

  private def tryUpgrade(
      client:   Socket,
      @scala.annotation.unused cin: java.io.InputStream,
      cout:     java.io.OutputStream,
      path:     String,
      rawQuery: String,
      headers:  Map[String, String]
  ): Unit =
    wsRoutes.matchPath(path) match
      case None =>
        try
          cout.write(WsHandshake.rejectResponse(404, "Not Found"))
          cout.flush()
        catch case _: Throwable => ()
        Metrics.wsRejected.incrementAndGet()
        try client.close() catch case _: Throwable => ()

      case Some((entry, params)) =>
        val key = headers.getOrElse("sec-websocket-key", "")
        if key.isEmpty then
          try client.close() catch case _: Throwable => ()
          return
        // Origin allowlist (CSRF guard).  Empty list = no restriction.
        if entry.origins.nonEmpty then
          val origin = headers.getOrElse("origin", "")
          if !entry.origins.contains(origin) then
            try
              cout.write(WsHandshake.rejectResponse(403, "Forbidden"))
              cout.flush()
            catch case _: Throwable => ()
            Metrics.wsRejected.incrementAndGet()
            try client.close() catch case _: Throwable => ()
            return
        // Pre-upgrade Request snapshot — same shape REST handlers see
        // (sans body / form / files; the upgrade is a GET with no body).
        // Built once and shared: the POJO `req` feeds the shared
        // `scalascript.server.jvm.WebSocket` ctor (which expects a
        // `Request` case class), while the Value form goes to the
        // interpreter handler as `ws.request`.
        val query   = HttpHelpers.parseQuery(rawQuery)
        val cookies = HttpHelpers.parseCookieHeader(headers.getOrElse("cookie", ""))
        val req = Request(
          method  = "GET",
          path    = path,
          params  = params,
          query   = query,
          headers = headers,
          body    = "",
          cookies = cookies
        )
        val request: Value = Value.InstanceV("Request", Map(
          "method"  -> Value.StringV("GET"),
          "path"    -> Value.StringV(path),
          "params"  -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "query"   -> Value.MapV(query.map((k, v)  => Value.StringV(k) -> Value.StringV(v))),
          "headers" -> Value.MapV(headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "cookies" -> Value.MapV(cookies.map((k, v) => Value.StringV(k) -> Value.StringV(v)))
        ))
        // Auth result threads through to `WsConnection.user`.  Three
        // states: no hook (None, accept), hook returned a payload
        // (Some(v), accept and carry v), hook returned None or threw
        // (rejected, 401).
        var userPayload: Option[Value] = None
        var authRejected: Boolean      = false
        entry.auth.foreach { fn =>
          try entry.interpreter.invoke(fn, List(request)) match
            case ov: Value.OptionV if ov.inner != null => userPayload = Some(ov.inner)
            case _: Value.OptionV                     => authRejected = true
            case other                                => userPayload = Some(other)
          catch case e: Throwable =>
            log.println(s"WS auth hook error: ${e.getMessage}")
            authRejected = true
        }
        if authRejected then
          try
            cout.write(WsHandshake.rejectResponse(401, "Unauthorized"))
            cout.flush()
          catch case _: Throwable => ()
          Metrics.wsRejected.incrementAndGet()
          try client.close() catch case _: Throwable => ()
          return
        // Process-wide cap on active WS sessions — refuses upgrades
        // with 503 before allocating a WsConnection.
        if !WsConnection.tryReserveSlot() then
          try
            cout.write(WsHandshake.rejectResponse(503, "Service Unavailable"))
            cout.flush()
          catch case _: Throwable => ()
          Metrics.wsRejected.incrementAndGet()
          try client.close() catch case _: Throwable => ()
          return
        // Per-route cap — composed with the process-wide cap.
        if !entry.tryReserve() then
          WsConnection.releaseSlot()
          try
            cout.write(WsHandshake.rejectResponse(503, "Service Unavailable"))
            cout.flush()
          catch case _: Throwable => ()
          Metrics.wsRejected.incrementAndGet()
          try client.close() catch case _: Throwable => ()
          return
        // Subprotocol negotiation (RFC 6455 §1.9).
        val chosenProtocol: String = WsHandshake.negotiateSubprotocol(
          headers.getOrElse("sec-websocket-protocol", ""), entry.protocols
        ) match
          case Some(p) => p
          case None    =>
            WsConnection.releaseSlot()
            entry.release()
            try
              cout.write(WsHandshake.rejectResponse(400, "Bad Request"))
              cout.flush()
            catch case _: Throwable => ()
            Metrics.wsRejected.incrementAndGet()
            try client.close() catch case _: Throwable => ()
            return
        // Write the 101 handshake synchronously — it's a few hundred
        // bytes and the socket buffer is empty at this point.
        try
          cout.write(WsHandshake.upgradeResponse(key, chosenProtocol))
          cout.flush()
        catch case _: Throwable =>
          WsConnection.releaseSlot()
          entry.release()
          try client.close() catch case _: Throwable => ()
          return
        Metrics.wsUpgraded.incrementAndGet()
        // Build the shared `scalascript.server.jvm.WebSocket`.  Its
        // ctor spawns the writer VT; we hand it the interpreter's
        // single-thread `wsExecutor` and the proxy's `heartbeats`
        // scheduler so user-callback dispatch + ping cadence stay
        // serial with the rest of the interpreter.
        val ws = _root_.scalascript.server.jvm.WebSocket(
          socket              = client,
          request             = req,
          subprotocol         = chosenProtocol,
          _onTerminate        = () => entry.release(),
          _maxMessagesPerSec  = entry.maxMessagesPerSec,
          user                = userPayload,
          _executor           = wsExecutor,
          _heartbeats         = heartbeats,
          _heartbeatIntervalMs = heartbeatIntervalMs,
          _deadAfterMs        = heartbeatDeadAfterMs,
          _log                = log
        )
        // Structured connect log (Sprint 4 #13).
        val accessIp     = try client.getRemoteSocketAddress.toString catch case _: Throwable => "?"
        val accessOrigin = headers.getOrElse("origin", "")
        log.println(s"ws.connect\tid=${ws.id}\tip=$accessIp\troute=$path\torigin=$accessOrigin\tproto=$chosenProtocol")
        // Build the user-facing Value via the bridge, then hand it to
        // the user's onWebSocket block.  Runs on the interpreter
        // executor so global state stays serial.
        val wsValue = WsConnection.asValue(ws, entry.interpreter, log, request)
        // Dispatch the user's onWebSocket block on the interpreter
        // executor so global state stays serial.  This submit can lose a
        // race with teardown: `wsExecutor` is owned by the interpreter /
        // test, and a connection accepted just before shutdown reaches
        // here after the executor is already stopping (e.g. a test's
        // `executor.shutdownNow()` following `proxy.stop()`).  A
        // `RejectedExecutionException` in that window is benign — the
        // proxy is closing and this connection is being torn down anyway —
        // so abandon the upgrade instead of letting it surface as an
        // uncaught exception on the `ws-proxy-conn` virtual thread (which
        // would fail the test run).  Mirrors the same guard the shared
        // `WebSocket` runtime already applies to its callback dispatches.
        val dispatched =
          try
            wsExecutor.execute { () =>
              try entry.interpreter.invoke(entry.handler, List(wsValue))
              catch case e: Throwable =>
                log.println(s"WS upgrade handler error: ${e.getMessage}")
            }
            true
          catch case _: java.util.concurrent.RejectedExecutionException if tearingDown =>
            false
        if !dispatched then
          // Executor gone before the handler could run — tear this
          // connection down cleanly (releases the reserved slots via the
          // writer VT) and skip the heartbeat + read loop.
          try ws.close(1001, "server shutting down") catch case _: Throwable => ()
          return
        // Arm the heartbeat and block in the read loop on this VT.
        // When the read loop returns (peer close / EOF / protocol error)
        // it enqueues a SENTINEL on the writer's queue; the writer VT
        // owns the teardown sequence.
        ws._startHeartbeat()
        ws._runReadLoop()

  // ─── Plain HTTP forwarding path ───────────────────────────────────

  private def forwardHttp(
      client: Socket,
      cin:    java.io.InputStream,
      cout:   java.io.OutputStream,
      head:   Array[Byte]
  ): Unit =
    val back =
      try Socket(internalAddr.getAddress, internalAddr.getPort)
      catch case _: Throwable =>
        try client.close() catch case _: Throwable => ()
        return
    val bin  = java.io.BufferedInputStream(back.getInputStream)
    val bout = back.getOutputStream
    val wrote =
      try { bout.write(head); bout.flush(); true }
      catch case _: Throwable => false
    if !wrote then
      try client.close() catch case _: Throwable => ()
      try back.close()   catch case _: Throwable => ()
      return
    val pump1 = Thread.ofVirtual().name("ws-proxy-pump-c2b").start { () =>
      pump(cin, bout, back, client)
    }
    val pump2 = Thread.ofVirtual().name("ws-proxy-pump-b2c").start { () =>
      pump(bin, cout, client, back)
    }
    try pump1.join() catch case _: InterruptedException => ()
    try pump2.join() catch case _: InterruptedException => ()

  private def pump(
      in:  java.io.InputStream,
      out: java.io.OutputStream,
      a:   Socket,
      b:   Socket
  ): Unit =
    val buf = new Array[Byte](8192)
    try
      var n = in.read(buf)
      while n >= 0 do
        out.write(buf, 0, n); out.flush()
        n = in.read(buf)
    catch case _: Throwable => ()
    finally
      try a.close() catch case _: Throwable => ()
      try b.close() catch case _: Throwable => ()
