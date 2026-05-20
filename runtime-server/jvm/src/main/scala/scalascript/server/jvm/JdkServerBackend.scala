package scalascript.server.jvm

// BUILD-ONLY:start
// At scala-cli inline time the SPI traits + POJOs / helpers are
// all at top level in the generated script (inlined from the SPI
// and runtime-server-common bundles just above this file), so the
// imports must be stripped or they'd reference packages that no
// longer exist in the standalone script.
import scalascript.server.*
import scalascript.server.spi.*
// BUILD-ONLY:end
import com.sun.net.httpserver.{HttpServer as JHttpServer, HttpExchange}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import javax.net.ssl.SSLServerSocket

/** JDK-based `HttpServerSpi` implementation — the default backend
 *  with zero external dependencies.  Wraps the JDK
 *  `com.sun.net.httpserver.HttpServer` (for plain HTTP) plus a custom
 *  blocking-IO public accept loop + virtual-thread-per-connection
 *  proxy that demuxes plain HTTP (forwarded to the internal HttpServer)
 *  from `Upgrade: websocket` (handled in-place via the shared
 *  `scalascript.server.jvm.WebSocket` class).
 *
 *  Lifted from the pre-SPI `WsProxy` / `TlsProxy` in `backend-interpreter`
 *  (and the equivalent codegen `ProxyRuntime._proxyConnection` in this
 *  module): same wire format, same threading model, same heartbeat /
 *  rate-limit / slot-reservation semantics.  The SPI shape lets the
 *  interpreter and the codegen output share one network-layer impl
 *  rather than each carrying its own copy of the proxy. */
class JdkServerBackend extends HttpServerSpi:

  override val name: String = "jdk"

  @volatile private var _running:   Boolean = false
  @volatile private var _localPort: Int     = 0

  @volatile private var _serverSocket: ServerSocket | Null      = null
  @volatile private var _internalHttp: JHttpServer | Null       = null
  @scala.annotation.unused
  @volatile private var _acceptThread: Thread | Null            = null
  @volatile private var _internalExec: java.util.concurrent.ExecutorService | Null = null
  @volatile private var _connPool:     java.util.concurrent.ExecutorService | Null = null
  @volatile private var _heartbeats:   java.util.concurrent.ScheduledExecutorService | Null = null

  /** Heartbeat tuning — exposed for tests that need to shrink the
   *  ping cadence; production callers leave them at the defaults
   *  (30 s ping, 90 s dead-after). */
  @volatile private var _heartbeatIntervalMs: Long = 30_000L
  @volatile private var _heartbeatDeadAfterMs: Long = 90_000L

  def setHeartbeatTuning(intervalMs: Long, deadAfterMs: Long): Unit =
    _heartbeatIntervalMs  = intervalMs
    _heartbeatDeadAfterMs = deadAfterMs

  override def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit =
    if _running then return

    // ── Internal JDK HttpServer for plain-HTTP forwarding ──────────────
    // Loopback ephemeral port.  Its handler translates the JDK
    // HttpExchange into a POJO Request, calls `handler.onHttpRequest`,
    // and writes the result back through the shared writers.
    val internalExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    val internal     = JHttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    internal.createContext("/", (ex: HttpExchange) => dispatchHttp(ex, handler))
    internal.setExecutor(internalExec)
    internal.start()
    _internalHttp = internal
    _internalExec = internalExec
    val internalPort = internal.getAddress.getPort

    // ── Public server socket (HTTP or HTTPS) ───────────────────────────
    val pubSocket: ServerSocket = tls match
      case None      =>
        val s = ServerSocket()
        s.setReuseAddress(true)
        s.bind(InetSocketAddress(port))
        s
      case Some(cfg) =>
        val ctx = TlsContextBuilder.build(cfg.certPemPath, cfg.keyPemPath)
        val ss  = ctx.getServerSocketFactory.createServerSocket(port)
          .asInstanceOf[SSLServerSocket]
        ss
    _serverSocket = pubSocket
    _localPort    = pubSocket.getLocalPort

    // ── Per-connection virtual-thread pool ─────────────────────────────
    val pool = java.util.concurrent.Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("jdk-backend-conn-", 0L).factory()
    )
    _connPool = pool

    // ── Heartbeat scheduler (single daemon thread) ─────────────────────
    val hb = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
      val t = Thread(r, "jdk-backend-ws-heartbeats"); t.setDaemon(true); t
    })
    _heartbeats = hb

    // ── Accept loop on a daemon platform thread ────────────────────────
    val accept = Thread({ () =>
      while _running && !pubSocket.isClosed do
        try
          val client = pubSocket.accept()
          pool.execute(() => proxyConnection(client, internalPort, handler))
        catch
          case _: java.net.SocketException if !_running => () // shutting down
          case _: Throwable if _running                 => ()
    }, s"jdk-backend-accept-$port")
    accept.setDaemon(false)
    _acceptThread = accept

    _running = true
    accept.start()

  override def stop(): Unit =
    _running = false
    val s = _serverSocket
    if s != null then try s.close() catch case _: Throwable => ()
    val h = _internalHttp
    if h != null then try h.stop(0) catch case _: Throwable => ()
    val ie = _internalExec
    if ie != null then try ie.shutdownNow() catch case _: Throwable => ()
    val cp = _connPool
    if cp != null then try cp.shutdownNow() catch case _: Throwable => ()
    val hb = _heartbeats
    if hb != null then try hb.shutdownNow() catch case _: Throwable => ()

  override def isRunning: Boolean = _running

  override def localPort: Int = _localPort

  // ── Per-connection driver (runs on a virtual thread) ────────────────

  private def proxyConnection(
      client:       Socket,
      internalPort: Int,
      handler:      HttpHandler
  ): Unit =
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
      handleWsUpgrade(client, cout, parsed, handler)
    else
      forwardHttp(client, cin, cout, head, internalPort)

  // ── WebSocket upgrade path ───────────────────────────────────────────

  private def handleWsUpgrade(
      client:  Socket,
      cout:    java.io.OutputStream,
      parsed:  HttpHelpers.HttpRequestHead,
      handler: HttpHandler
  ): Unit =
    val headers = parsed.headers
    val key     = headers.getOrElse("sec-websocket-key", "")
    if key.isEmpty then
      try client.close() catch case _: Throwable => ()
      return

    // Build POJO Request from the head (no body — WS upgrades are GETs).
    val req = Request(
      method  = "GET",
      path    = parsed.path,
      params  = Map.empty,
      query   = HttpHelpers.parseQuery(parsed.rawQuery),
      headers = headers,
      body    = "",
      cookies = HttpHelpers.parseCookieHeader(headers.getOrElse("cookie", ""))
    )

    handler.onWsUpgrade(req) match
      case WsUpgradeResult.Reject(status, reason) =>
        try
          cout.write(WsHandshake.rejectResponse(status, reason))
          cout.flush()
        catch case _: Throwable => ()
        Metrics.wsRejected.incrementAndGet()
        try client.close() catch case _: Throwable => ()

      case WsUpgradeResult.Accept(subprotocol, listener) =>
        // Reserve the process-wide slot — composes with whatever the
        // listener-side application logic already decided.  The shared
        // WebSocket class's writer-VT will release it in its finally.
        if !_wsTryReserve() then
          try
            cout.write(WsHandshake.rejectResponse(503, "Service Unavailable"))
            cout.flush()
          catch case _: Throwable => ()
          Metrics.wsRejected.incrementAndGet()
          try client.close() catch case _: Throwable => ()
          return

        try
          cout.write(WsHandshake.upgradeResponse(key, subprotocol))
          cout.flush()
        catch case _: Throwable =>
          _wsReleaseSlot()
          try client.close() catch case _: Throwable => ()
          return

        Metrics.wsUpgraded.incrementAndGet()

        // Build the shared WebSocket — its writer VT handles the
        // outbound queue, heartbeat scheduling, and the global-slot
        // decrement in its `finally`.  We re-emit the user-callback
        // dispatch through the listener so this code path is
        // backend-agnostic above this point.
        val ieRef = _internalExec
        val execForWs: java.util.concurrent.Executor =
          if ieRef != null then ieRef
          else (r: Runnable) => r.run()
        val hbRef = _heartbeats
        val hbForWs: java.util.concurrent.ScheduledExecutorService =
          if hbRef != null then hbRef
          // Should never happen — we always allocate one in start().
          else java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val ws = WebSocket(
          socket               = client,
          request              = req,
          subprotocol          = subprotocol,
          _onTerminate         = () => (),
          _maxMessagesPerSec   = 0,
          user                 = None,
          _executor            = execForWs,
          _heartbeats          = hbForWs,
          _heartbeatIntervalMs = _heartbeatIntervalMs,
          _deadAfterMs         = _heartbeatDeadAfterMs,
          _log                 = System.out
        )
        val controls = JdkWsControls(ws)
        // Fire onOpen first, then wire onMessage / onClose / onPong
        // into the listener so each frame the read loop reassembles
        // hits the SPI consumer.
        try listener.onOpen(controls)
        catch case e: Throwable => listener.onError(e)
        ws.onMessage(s => try listener.onMessage(s) catch case e: Throwable => listener.onError(e))
        ws.onClose(()  => try listener.onClose(1000, "") catch case _: Throwable => ())
        ws.onPong(p    => try listener.onPong(p.getBytes("ISO-8859-1")) catch case _: Throwable => ())
        ws._startHeartbeat()
        ws._runReadLoop()

  // ── Plain HTTP forwarding path ───────────────────────────────────────

  private def forwardHttp(
      client:       Socket,
      cin:          java.io.InputStream,
      cout:         java.io.OutputStream,
      head:         Array[Byte],
      internalPort: Int
  ): Unit =
    val back =
      try Socket("127.0.0.1", internalPort)
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
    val pump1 = Thread.ofVirtual().name("jdk-backend-pump-c2b").start { () =>
      pump(cin, bout, back, client)
    }
    val pump2 = Thread.ofVirtual().name("jdk-backend-pump-b2c").start { () =>
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

  // ── Plain-HTTP dispatch via the SPI handler ──────────────────────────

  private def dispatchHttp(ex: HttpExchange, handler: HttpHandler): Unit =
    // Default per-request RequestBuilder / ResponseWriter config is
    // adequate for SPI-level dispatch — application-specific tuning
    // (max body size, CORS origins, session-store integration) lives
    // above the SPI in the user's `HttpHandler` impl, which can build
    // its own POJO Request directly if needed.  The simple path:
    // parse → call handler.onHttpRequest → write result.
    try
      val method = ex.getRequestMethod
      val path   = ex.getRequestURI.getPath
      val (req, rawCookieSession, spooledTmps) =
        RequestBuilder.parse(ex, method, path, Map.empty)
      try
        handler.onHttpRequest(req) match
          case HttpResult.PlainResp(resp) =>
            ResponseWriter.write(ex, resp, rawCookieSession, ResponseWriter.Config())
          case HttpResult.StreamResp(sr) =>
            StreamResponseWriter.write(ex, sr.status, sr.headers,
              ResponseWriter.Config(),
              write => { sr.writer(write); () })
          case HttpResult.Reject(status, body, contentType) =>
            val bytes = body.getBytes("UTF-8")
            ex.getResponseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, bytes.length.toLong)
            if bytes.nonEmpty then ex.getResponseBody.write(bytes)
      finally
        spooledTmps.foreach(f => try f.delete() catch case _: Throwable => ())
    catch
      case _: RequestBuilder.BodyTooLargeError =>
        val msg = "Request Entity Too Large".getBytes("UTF-8")
        try
          ex.sendResponseHeaders(413, msg.length.toLong)
          ex.getResponseBody.write(msg)
        catch case _: Throwable => ()
      case _: Throwable =>
        try ex.sendResponseHeaders(500, -1) catch case _: Throwable => ()
    finally
      try ex.close() catch case _: Throwable => ()

/** `WsControls` adapter wrapping the shared `WebSocket` runtime class.
 *  Pure delegation — every method forwards to the corresponding shared
 *  method.  Used by `JdkServerBackend` to hand a write-side handle to
 *  the application-layer `WsListener`. */
final class JdkWsControls(ws: WebSocket) extends WsControls:
  override def id: String            = ws.id
  override def remoteAddress: String = "?"  // socket is private to WebSocket
  override def subprotocol: String   = ws.subprotocol
  override def send(text: String): Unit = ws.send(text)
  override def sendBytes(bytes: Array[Byte]): Unit =
    ws.sendBytes(new String(bytes, "ISO-8859-1"))
  override def ping(payload: Array[Byte]): Unit =
    if payload == null || payload.isEmpty then ws.ping()
    else ws.ping(new String(payload, "ISO-8859-1"))
  override def close(code: Int, reason: String): Unit = ws.close(code, reason)
  override def isClosed: Boolean = ws.isClosed
  override def recv(): Option[String] = ws.recv()
