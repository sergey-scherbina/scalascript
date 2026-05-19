package scalascript.server.jvm

// Phase 3d — JVM-codegen Proxy + TLS runtime (Part2 lines 3241-3446 of
// the original serveRuntime string template, ~205 LOC).  Owns the
// blocking-accept loop, the HTTP/WS sniffing demux, the upgrade
// handshake driver (delegates to WsHandshake + the local WS slot
// reservation), and the TLS / HTTPS bootstrap path.

// BUILD-ONLY:start
import scalascript.server.*
// BUILD-ONLY:end

// ── Proxy: blocking accept + sniff + forward / upgrade ───────────────

private def _proxyConnection(client: java.net.Socket, internalPort: Int): Unit =
  // TCP keepalive lets the OS detect peers that vanished without
  // FIN (yanked cables, dropped mobile sessions).  Without it a
  // dead WS holds its FD for ~2 h before the TCP stack notices.
  try client.setKeepAlive(true) catch case _: Throwable => ()
  val cin  = java.io.BufferedInputStream(client.getInputStream)
  val cout = client.getOutputStream
  val head    = HttpHelpers.readHttpHead(cin)
  val parsed  = HttpHelpers.parseHttpHead(head)
  val headers = parsed.headers
  val path    = parsed.path
  if parsed.isUpgradeWebSocket then
    val segs = path.split('/').toList.filter(_.nonEmpty)
    val matched = _wsRoutes.iterator.flatMap { r =>
      _matchPath(r.pattern, segs).map(params => (r, params))
    }.nextOption()
    matched match
      case None =>
        cout.write(WsHandshake.rejectResponse(404, "Not Found"))
        cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet()
      case Some((r, params)) =>
        val key = headers.getOrElse("sec-websocket-key", "")
        if key.isEmpty then { client.close(); return }
        // Origin allowlist (CSRF guard) — empty list = unrestricted.
        if r.origins.nonEmpty then
          val origin = headers.getOrElse("origin", "")
          if !r.origins.contains(origin) then
            cout.write(WsHandshake.rejectResponse(403, "Forbidden"))
            cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
        // Pre-upgrade Request snapshot — same shape REST handlers
        // see (sans body / form / files; the upgrade is a GET with
        // no body).  Used for the auth hook AND, after the upgrade
        // succeeds, as `ws.request` so handlers can read cookies /
        // Authorization / Origin from `ws.request.headers`.  Built
        // once because the headers / path / params don't change
        // across the upgrade boundary.
        val _wsReq = Request(
          method  = "GET",
          path    = path,
          params  = params,
          query   = _parseQuery(parsed.rawQuery),
          headers = headers,
          body    = "",
          cookies = HttpHelpers.parseCookieHeader(headers.getOrElse("cookie", ""))
        )
        var _authPayload: Option[Any] = None
        var _authReject:  Boolean      = false
        r.auth.foreach { fn =>
          try fn(_wsReq) match
            case Some(v) => _authPayload = Some(v)
            case None    => _authReject  = true
          catch case e: Throwable =>
            System.err.println(s"WS auth hook: ${e.getMessage}")
            _authReject = true
        }
        if _authReject then
          cout.write(WsHandshake.rejectResponse(401, "Unauthorized"))
          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
        // Process-wide active-connection cap.  Reserved AFTER
        // the Origin check so a denied-Origin attempt doesn't
        // briefly consume a slot.  Released in the writer-VT's
        // `finally` after the channel closes.
        if !_wsTryReserve() then
          cout.write(WsHandshake.rejectResponse(503, "Service Unavailable"))
          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
        // Per-route active-connection cap.  Composes with the
        // process-wide cap above (both must permit).  0 = no
        // per-route limit.  Released by the writer-VT finally
        // via `r.release()`.
        if !r.tryReserve() then
          _wsActiveCount.decrementAndGet()
          cout.write(WsHandshake.rejectResponse(503, "Service Unavailable"))
          cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
        // Subprotocol negotiation (RFC 6455 §1.9) — delegate to
        // the shared `WsHandshake.negotiateSubprotocol`.  `None`
        // means the server has preferences but none overlap; we
        // refuse with 400 (and release the caps reserved above).
        val chosenProtocol: String = WsHandshake.negotiateSubprotocol(
          headers.getOrElse("sec-websocket-protocol", ""), r.protocols
        ) match
          case Some(p) => p
          case None    =>
            _wsActiveCount.decrementAndGet(); r.release()
            cout.write(WsHandshake.rejectResponse(400, "Bad Request"))
            cout.flush(); client.close(); _Metrics.wsRejected.incrementAndGet(); return
        cout.write(WsHandshake.upgradeResponse(key, chosenProtocol)); cout.flush()
        // Reuse the pre-upgrade `_wsReq` snapshot built above — the
        // headers / path / params / cookies haven't changed across
        // the upgrade boundary, so a second snapshot would be a
        // verbatim copy.
        _Metrics.wsUpgraded.incrementAndGet()
        val ws = WebSocket(client, _wsReq, subprotocol = chosenProtocol, _onTerminate = () => r.release(), _maxMessagesPerSec = r.maxMessagesPerSec, user = _authPayload)
        // Structured connect log (Sprint 4 #13).
        val _accessIp     = try client.getRemoteSocketAddress.toString catch case _: Throwable => "?"
        val _accessOrigin = headers.getOrElse("origin", "")
        println("ws.connect\tid=" + ws.id + "\tip=" + _accessIp +
          "\troute=" + path + "\torigin=" + _accessOrigin +
          "\tproto=" + chosenProtocol)
        // Run the user's `onWebSocket` block on the shared single-
        // thread executor so any state it touches (top-level `var`s,
        // route registry, etc.) is serial with HTTP handlers and
        // later `onMessage` / `onClose` callbacks.
        _serverExecutor.execute { () =>
          try r.handler(ws) catch case e: Throwable =>
            System.err.println(s"WS upgrade handler: ${e.getMessage}")
        }
        // Read-loop stays on this thread (cached pool) so a slow
        // socket can't block the executor; only the dispatched
        // callbacks above go through the single-thread queue.
        ws._startHeartbeat()
        ws._runReadLoop()
  else
    // Plain HTTP — open a backend socket to the internal HttpServer
    // and copy bytes both ways until either side EOFs.
    val back = java.net.Socket("127.0.0.1", internalPort)
    val bin  = java.io.BufferedInputStream(back.getInputStream)
    val bout = back.getOutputStream
    bout.write(head); bout.flush()
    // Virtual threads (Loom) on JDK 21+; plain Threads as a
    // fallback so the emit also compiles on Java 17.
    def _spawn(name: String, body: () => Unit): Thread =
      try
        val cls    = Class.forName("java.lang.Thread$Builder$OfVirtual")
        val of     = classOf[Thread].getMethod("ofVirtual").invoke(null)
        val named  = cls.getMethod("name", classOf[String]).invoke(of, name)
        cls.getMethod("start", classOf[Runnable])
          .invoke(named, (() => body()): Runnable).asInstanceOf[Thread]
      catch case _: Throwable =>
        val t = Thread(() => body(), name)
        t.start()
        t
    val pump1 = _spawn("ws-proxy-pump-c2b", () => _pump(cin, bout, back, client))
    val pump2 = _spawn("ws-proxy-pump-b2c", () => _pump(bin, cout, client, back))
    pump1.join(); pump2.join()

private def _pump(in: java.io.InputStream, out: java.io.OutputStream, a: java.net.Socket, b: java.net.Socket): Unit =
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

// ── TLS / HTTPS support ────────────────────────────────────────────────
case class _TlsConfig(cert: String, key: String)

def tls(cert: String, key: String): _TlsConfig = _TlsConfig(cert, key)

def _buildSslContext(certPath: String, keyPath: String): javax.net.ssl.SSLContext =
  TlsContextBuilder.build(certPath, keyPath)
def _vThreadPool(): java.util.concurrent.ExecutorService = TlsContextBuilder.vthreadPool()

private val _stopLatch = java.util.concurrent.CountDownLatch(1)
@volatile private var _pubSocket: java.net.ServerSocket | Null = null
@volatile private var _internalHttp: com.sun.net.httpserver.HttpServer | Null = null

def stop(): Unit =
  try { _pubSocket  match { case s if s != null => s.close();  case _ => () } } catch { case _: Throwable => () }
  try { _internalHttp match { case h if h != null => h.stop(0); case _ => () } } catch { case _: Throwable => () }
  _stopLatch.countDown()

// Single `serve` def with a defaulted tls config — collapsing two
// overloads into one avoids the v2.0 linker's same-name dedup pass
// dropping the 2-arg overload when multiple modules concatenate.
def serve(port: Int, tlsCfg: _TlsConfig = null.asInstanceOf[_TlsConfig]): Unit =
  _registerHealthDefaults()
  val internal = com.sun.net.httpserver.HttpServer.create(
    java.net.InetSocketAddress("127.0.0.1", 0), 0)
  internal.createContext("/", ex => _handle(ex))
  internal.setExecutor(_serverExecutor)
  internal.start()
  _internalHttp = internal
  val internalPort = internal.getAddress.getPort
  val pool = _vThreadPool()
  if tlsCfg != null then
    val sslCtx = _buildSslContext(tlsCfg.cert, tlsCfg.key)
    val pub = sslCtx.getServerSocketFactory.createServerSocket(port)
      .asInstanceOf[javax.net.ssl.SSLServerSocket]
    _pubSocket = pub
    println(s"Listening on https://localhost:$port/  (proxy → 127.0.0.1:$internalPort)")
    Thread(() => {
      while !pub.isClosed do
        try
          val c = pub.accept()
          pool.execute { () => _proxyConnection(c, internalPort) }
        catch case _: Throwable => ()
    }, "tls-proxy-accept").start()
  else
    val pub = java.net.ServerSocket(port)
    _pubSocket = pub
    println(s"Listening on http://localhost:$port/  (proxy → 127.0.0.1:$internalPort)")
    Thread(() => {
      while !pub.isClosed do
        try
          val c = pub.accept()
          pool.execute { () => _proxyConnection(c, internalPort) }
        catch case _: Throwable => ()
    }, "ws-proxy-accept").start()
  _stopLatch.await()

