package scalascript.server.jvm

// Phase 3d — JVM-codegen Proxy + TLS runtime (Part2 lines 3241-3446 of
// the original serveRuntime string template, ~205 LOC).  Owns the
// blocking-accept loop, the HTTP/WS sniffing demux, the upgrade
// handshake driver (delegates to WsHandshake + the local WS slot
// reservation), and the TLS / HTTPS bootstrap path.
//
// v1.17.6 / Phase S1c — `serve(port, tlsCfg)` is now thin: it builds
// an `HttpHandler` that reads the top-level `_routes` / `_wsRoutes` /
// `_middlewares` state and hands control to
// `HttpServerBackends.current().start(...)`.  The accept loop +
// per-connection demux + WS read/write loops + TLS bootstrap all live
// in the SPI impl now (`JdkServerBackend` by default; Jetty / Netty
// if explicitly added to the user's build).  The legacy
// `_proxyConnection` body is retained below for tests / docs only —
// no codegen-emitted call site reaches it anymore.

// BUILD-ONLY:start
import scalascript.server.*
import scalascript.server.spi.*
// BUILD-ONLY:end

private val _proxyLog = org.slf4j.LoggerFactory.getLogger("scalascript.server")

// ── Proxy: blocking accept + sniff + forward / upgrade ───────────────
//
// Legacy entry point — pre-S1c the codegen `serve(port, tls)` opened
// the public ServerSocket itself and spawned this function per
// connection.  S1c routes through `HttpServerBackends.current().start`
// instead, so this helper is unused in newly-generated scripts.  Kept
// (with `@scala.annotation.unused`) because removing it would invalidate
// the existing snapshot of the inlined runtime that downstream tests
// reference; safe to delete in a later cleanup pass.
@scala.annotation.unused
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
            _proxyLog.warn(s"WS auth hook: ${e.getMessage}")
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
            _proxyLog.warn(s"WS upgrade handler: ${e.getMessage}")
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

@scala.annotation.unused
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

@scala.annotation.unused
def _buildSslContext(certPath: String, keyPath: String): javax.net.ssl.SSLContext =
  TlsContextBuilder.build(certPath, keyPath)
@scala.annotation.unused
def _vThreadPool(): java.util.concurrent.ExecutorService = TlsContextBuilder.vthreadPool()

private val _stopLatch = java.util.concurrent.CountDownLatch(1)

// SPI handle to the running backend — populated by `serve()` so
// `stop()` can shut it down cleanly without re-running discovery.
@volatile private var _spiBackend: HttpServerSpi | Null = null

def stop(): Unit =
  try { _spiBackend match { case b if b != null => b.stop(); case _ => () } } catch { case _: Throwable => () }
  _stopLatch.countDown()

/** Pick an HTTP server backend by short name (`"jdk"`, `"jetty"`,
 *  `"netty"`).  Subsequent `serve(port, …)` calls route through the
 *  chosen impl.  Default ssc distribution bundles only the JDK impl;
 *  compiled scripts that want Jetty / Netty must add the matching
 *  module via a `//> using lib io.scalascript::scalascript-runtime-server-jvm-jetty:VERSION`
 *  directive in their `.ssc` front-matter.  Wrong-name calls throw
 *  `IllegalStateException` (loud, fail-fast). */
def setHttpServerBackend(name: String): Unit =
  HttpServerBackends.setBackend(name)

// Single `serve` def with a defaulted tls config — collapsing two
// overloads into one avoids the v2.0 linker's same-name dedup pass
// dropping the 2-arg overload when multiple modules concatenate.
//
// v1.17.6 / Phase S1c — body is now a thin wrapper around
// `HttpServerBackends.current().start(port, tls, handler)`.  The
// inlined `JdkServerBackend` is registered eagerly so the default-classpath
// case works without a `META-INF/services` entry (the codegen pipeline
// inlines sources, not jars).  All accept-loop / sniff / WS-upgrade
// machinery now lives in the SPI impl.
def serve(port: Int, tlsCfg: _TlsConfig = null.asInstanceOf[_TlsConfig]): Unit =
  _registerHealthDefaults()
  // Seed the default JDK backend.  ServiceLoader would normally find
  // it via `META-INF/services/scalascript.server.spi.HttpServerSpi`,
  // but the codegen pipeline inlines `.scala` sources (no `META-INF`),
  // so we register programmatically.  Idempotent — repeated calls are
  // a no-op.
  HttpServerBackends.register(new JdkServerBackend)
  val handler  = _CodegenHttpHandler
  val tlsOpt: Option[TlsConfig] =
    if tlsCfg == null then None
    else Some(TlsConfig(tlsCfg.cert, tlsCfg.key))
  val backend = HttpServerBackends.current()
  _spiBackend = backend
  val scheme = if tlsCfg != null then "https" else "http"
  println(s"Listening on $scheme://localhost:$port/  (backend=${backend.name})")
  backend.start(port, tlsOpt, handler)
  _stopLatch.await()

// Non-blocking variant of `serve` — launches the HTTP/WS accept loop on
// a JDK 21+ virtual thread and returns immediately.  Required for
// multi-node clusters where a node must both bind its WS port AND drive
// its actor scheduler in the same process (see `docs/cluster-raft.md`
// §9 — `serve(port)` blocks the caller and stalls the scheduler).
//
// Wire-equivalent to the interpreter's `serveAsync` intrinsic in
// `backend-interpreter/src/main/scala/scalascript/interpreter/intrinsics/Http.scala`:
// same SPI-backed accept loop as `serve`, just dispatched on a virtual
// thread so control returns to the caller (an actor body, typically)
// after the bind completes.  Caller is responsible for keeping the
// process alive — `_stopLatch.await()` is NOT called here.
//
// The 2-arg `serveAsync(port, tls(cert, key))` form binds HTTPS / wss://;
// peers dialing the node use `wss://host:port/_ssc-actors` and the
// existing `java.net.http`-backed outbound WS client handles the TLS
// handshake transparently.
def serveAsync(port: Int, tlsCfg: _TlsConfig = null.asInstanceOf[_TlsConfig]): Unit =
  _registerHealthDefaults()
  // Same backend bootstrap as `serve` — register the inlined JDK impl
  // programmatically since the codegen pipeline doesn't carry
  // `META-INF/services` entries.
  HttpServerBackends.register(new JdkServerBackend)
  val handler  = _CodegenHttpHandler
  val tlsOpt: Option[TlsConfig] =
    if tlsCfg == null then None
    else Some(TlsConfig(tlsCfg.cert, tlsCfg.key))
  val backend = HttpServerBackends.current()
  _spiBackend = backend
  val scheme = if tlsCfg != null then "https" else "http"
  println(s"Listening on $scheme://localhost:$port/  (backend=${backend.name}, async)")
  // Launch the (blocking) backend start on a virtual thread so the
  // caller's thread is freed immediately.  The SPI's `start` typically
  // returns after binding (the read loops run on their own threads),
  // so this is belt-and-suspenders — if a future SPI impl blocks until
  // shutdown, the virtual thread absorbs that.
  Thread.ofVirtual().name(s"ssc-serve-async-$port").start { () =>
    try backend.start(port, tlsOpt, handler)
    catch case e: Throwable =>
      _proxyLog.error(s"serveAsync($port) failed: ${e.getMessage}", e)
  }
  ()

// ── Codegen HttpHandler — drives the SPI on behalf of the user's
//    top-level route registry (`_routes` / `_wsRoutes` / `_middlewares`).
//    Same shape as `InterpreterHttpHandler` in backend-interpreter, but
//    uses native Scala closures since codegen handlers are already
//    real functions (no `Value.Closure` wrap).
//
//    HTTP path: route lookup → middleware chain → handler → POJO
//    result (Plain / Stream / Reject).  CORS preflight short-circuits
//    to 204; unmatched routes fall through to a 404.  Static-asset
//    fallback isn't wired through the SPI yet (it requires HttpExchange
//    access); user code that needs it can register a catch-all route.
//
//    WS  path: route lookup → origin allowlist → auth hook →
//    process-wide slot reserve (via SPI impl) → per-route slot
//    reserve → subprotocol negotiate → Accept(subprotocol, listener)
//    or Reject(code, reason).  The listener bridges the SPI's
//    callback-shape into the user's `WebSocket => Unit` handler — see
//    `_CodegenWsListener` below for the per-connection state.

private def _buildCorsHeaders(req: Request): Map[String, String] =
  // Mirror `CorsHelpers.apply` but emit a plain Map[String, String]
  // instead of mutating an HttpExchange (the SPI handler returns POJO
  // Responses — the impl writes the wire bytes).  Same allow-origin /
  // methods / headers rules.
  val origin  = req.headers.getOrElse("origin", "")
  val allowed =
    if _corsOrigins.contains("*")         then "*"
    else if _corsOrigins.contains(origin) then origin
    else                                       ""
  if allowed.isEmpty then Map.empty
  else
    val base = scala.collection.mutable.LinkedHashMap.empty[String, String]
    base("Access-Control-Allow-Origin") = allowed
    if _corsMethods.nonEmpty then base("Access-Control-Allow-Methods") = _corsMethods.mkString(", ")
    if _corsHeaders.nonEmpty then base("Access-Control-Allow-Headers") = _corsHeaders.mkString(", ")
    base("Vary") = "Origin"
    base.toMap

private object _CodegenHttpHandler extends HttpHandler:
  override def onHttpRequest(req: Request): HttpResult =
    // CORS preflight — short-circuit before route matching.
    if req.method.equalsIgnoreCase("OPTIONS") && _corsOrigins.nonEmpty then
      return HttpResult.PlainResp(Response(204, _buildCorsHeaders(req), ""))
    val method = req.method.toUpperCase
    val segs   = req.path.split('/').toList.filter(_.nonEmpty)
    val matched = _routes.iterator
      .filter(_.method == method)
      .flatMap(r => _matchPath(r.pattern, segs).map(p => (r, p)))
      .nextOption()
    matched match
      case None =>
        // Fall through to static files (e.g. SPA emitted by serve(view, port))
        // before 404'ing.  Only GET requests can serve static assets.
        if method == "GET" then
          StaticAssetServer.resolve(_ssc_static_root, req.path) match
            case Some(file) =>
              val bytes = java.nio.file.Files.readAllBytes(file.toPath)
              val ct    = HttpHelpers.contentTypeFor(file.getName)
              HttpResult.PlainResp(Response(200, Map("Content-Type" -> ct), new String(bytes, "UTF-8")))
            case None =>
              HttpResult.Reject(404, s"Not Found: ${req.path}")
        else
          HttpResult.Reject(404, s"Not Found: ${req.path}")
      case Some((r, params)) =>
        val reqWithParams = req.copy(params = params)
        def baseHandler(): Any =
          try r.handler(reqWithParams)
          catch case ve: RestValidationError =>
            Response(400,
              Map("Content-Type" -> "text/plain; charset=utf-8"),
              ve.getMessage)
        var chain: () => Any = () => baseHandler()
        _middlewares.reverseIterator.foreach { mw =>
          val inner = chain
          chain = () => mw(reqWithParams, inner)
        }
        try chain() match
          case sr: StreamResponse =>
            HttpResult.StreamResp(sr)
          case r2: Response =>
            HttpResult.PlainResp(r2)
          case other =>
            HttpResult.PlainResp(Response(200,
              Map("Content-Type" -> "text/plain; charset=utf-8"),
              _show(other)))
        catch case e: Throwable =>
          _proxyLog.error(s"route error: ${e.getMessage}", e)
          _Metrics.http5xx.incrementAndGet()
          HttpResult.Reject(500, "Internal Server Error")

  override def onWsUpgrade(req: Request): WsUpgradeResult =
    val segs = req.path.split('/').toList.filter(_.nonEmpty)
    val matched = _wsRoutes.iterator.flatMap { r =>
      _matchPath(r.pattern, segs).map(params => (r, params))
    }.nextOption()
    matched match
      case None =>
        WsUpgradeResult.Reject(404, "Not Found")
      case Some((r, params)) =>
        val headers = req.headers
        // Origin allowlist (CSRF guard).
        if r.origins.nonEmpty then
          val origin = headers.getOrElse("origin", "")
          if !r.origins.contains(origin) then
            return WsUpgradeResult.Reject(403, "Forbidden")
        val wsReq = req.copy(params = params)
        // Pre-upgrade auth hook.
        var authPayload: Option[Any] = None
        var authReject:  Boolean      = false
        r.auth.foreach { fn =>
          try fn(wsReq) match
            case Some(v) => authPayload = Some(v)
            case None    => authReject  = true
          catch case e: Throwable =>
            _proxyLog.warn(s"WS auth hook: ${e.getMessage}")
            authReject = true
        }
        if authReject then
          return WsUpgradeResult.Reject(401, "Unauthorized")
        // Per-route cap (process-wide cap is reserved by the SPI impl
        // after the listener is returned).
        if !r.tryReserve() then
          return WsUpgradeResult.Reject(503, "Service Unavailable")
        // Subprotocol negotiation.
        val chosen: String = WsHandshake.negotiateSubprotocol(
          headers.getOrElse("sec-websocket-protocol", ""), r.protocols
        ) match
          case Some(p) => p
          case None    =>
            r.release()
            return WsUpgradeResult.Reject(400, "Bad Request")
        // Bridge the SPI listener callbacks into the user's
        // `onWebSocket { ws => … }` body via the shared executor.
        val listener: WsListener = new _CodegenWsListener(
          route        = r,
          request      = wsReq,
          subprotocol  = chosen,
          authPayload  = authPayload
        )
        WsUpgradeResult.Accept(chosen, listener)

/** Per-connection listener — bridges the SPI's frame-callback shape
 *  into the codegen's `WebSocket => Unit` user handler.  The user's
 *  body runs once on `onOpen`; subsequent frames invoke the stashed
 *  `ws.onMessage` / `ws.onClose` / `ws.onPong` callbacks via
 *  `_serverExecutor` so global-state mutations stay serial with HTTP
 *  handlers.
 *
 *  The user-facing `WebSocket` is built by `_buildSpiWebSocketView`
 *  below — a `WebSocket`-shaped delegator over `WsControls`.  Slot
 *  management: per-route was reserved in `onWsUpgrade`; we release it
 *  on close.  The SPI impl owns the process-wide slot. */
private final class _CodegenWsListener(
    route:       _WsRoute,
    request:     Request,
    subprotocol: String,
    authPayload: Option[Any]
) extends WsListener:
  // The SPI-mode `WebSocket` view built once in `onOpen` — handed to
  // the user `route.handler` and used by every subsequent callback to
  // dispatch through the WebSocket's own `_spiFireMessage` etc.  That
  // keeps the user-facing `ws.onMessage(cb)` / `ws.onClose(cb)`
  // setters (defined on the WebSocket class) in the dispatch path so
  // a callback registered AFTER `route.handler` returns still fires
  // for later frames.
  @volatile private var _view: WebSocket | Null = null

  override def onOpen(controls: WsControls): Unit =
    val view = WebSocket(
      socket               = null,
      request              = request,
      subprotocol          = subprotocol,
      _onTerminate         = () => (),
      _maxMessagesPerSec   = 0,
      user                 = authPayload,
      _executor            = _serverExecutor,
      _heartbeats          = _wsHeartbeats,
      _heartbeatIntervalMs = 30_000L,
      _deadAfterMs         = 90_000L,
      _log                 = System.out,
      _spiControls         = controls
    )
    _view = view
    _serverExecutor.execute { () =>
      try route.handler(view)
      catch case e: Throwable =>
        _proxyLog.warn(s"WS upgrade handler: ${e.getMessage}")
    }

  override def onMessage(text: String): Unit =
    val v = _view
    if v != null then
      _serverExecutor.execute { () => v._spiFireMessage(text) }

  override def onBinary(bytes: Array[Byte]): Unit =
    onMessage(new String(bytes, "ISO-8859-1"))

  override def onPong(payload: Array[Byte]): Unit =
    val v = _view
    if v != null then
      val s = new String(payload, "ISO-8859-1")
      _serverExecutor.execute { () => v._spiFirePong(s) }

  override def onClose(code: Int, reason: String): Unit =
    route.release()
    val v = _view
    if v != null then
      _serverExecutor.execute { () => v._spiFireClose() }

  override def onError(t: Throwable): Unit =
    _proxyLog.error(s"WS error: ${t.getMessage}", t)

