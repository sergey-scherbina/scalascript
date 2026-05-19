package scalascript.server.jvm.jetty

import scalascript.server.*
import scalascript.server.spi.*

import org.eclipse.jetty.server.{
  Server as JServer,
  ServerConnector,
  HttpConfiguration,
  HttpConnectionFactory,
  SecureRequestCustomizer,
  Handler as JHandler,
  Request as JRequest,
  Response as JResponse
}
import org.eclipse.jetty.util.{Callback as JCallback}
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.websocket.api.{Callback as WsCallback, Session as JSession}
import org.eclipse.jetty.websocket.server.{
  ServerUpgradeRequest,
  ServerUpgradeResponse,
  ServerWebSocketContainer,
  WebSocketCreator,
  WebSocketUpgradeHandler
}

import java.nio.ByteBuffer
import java.util.UUID

/** Jetty 12-based `HttpServerSpi` implementation.  Optional — pulls
 *  Jetty (~3 MB) as a runtime dependency.  Enables HTTP/2,
 *  permessage-deflate WS compression, production-grade TLS
 *  (Conscrypt / BoringSSL integration), and other Jetty extensions.
 *
 *  Implementation maps each SPI surface onto Jetty 12's modern Handler
 *  API:
 *
 *    - HTTP: a `Handler.Abstract` that translates Jetty's `Request` ->
 *      our POJO `Request`, calls `HttpHandler.onHttpRequest`, then
 *      writes the result back through `Response.write` /
 *      `Content.Sink`.
 *    - WS:   a `WebSocketUpgradeHandler` (wraps the HTTP handler) that
 *      maps "/star" to a `WebSocketCreator`.  The creator builds a POJO
 *      `Request` from the upgrade headers, calls
 *      `HttpHandler.onWsUpgrade`, and either returns a per-connection
 *      `Session.Listener.AutoDemanding` endpoint (Accept) or sets
 *      a status code on the upgrade response (Reject).
 *    - TLS:  reuses runtime-server-common's `TlsContextBuilder` to
 *      build a JDK `SSLContext` from PEM cert+key, then hands it to
 *      Jetty's `SslContextFactory.Server` via `setSslContext`.
 *
 *  Wire-format-equivalent to `JdkServerBackend` for the SPI contract
 *  (HTTP 1.1 + RFC 6455 WS); Jetty additionally offers HTTP/2 +
 *  permessage-deflate when the connector / container is configured
 *  for it (out of scope for S2 — defaults are HTTP/1.1 + raw WS to
 *  stay drop-in compatible with the JDK backend). */
class JettyServerBackend extends HttpServerSpi:

  override val name: String = "jetty"

  @volatile private var _server:    JServer | Null      = null
  @scala.annotation.unused
  @volatile private var _connector: ServerConnector | Null = null
  @volatile private var _running:   Boolean             = false
  @volatile private var _localPort: Int                 = 0

  override def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit =
    if _running then return

    val srv = new JServer()

    // ── Connector (plain or TLS) ───────────────────────────────────────
    val httpCfg = new HttpConfiguration()
    val connector: ServerConnector = tls match
      case None =>
        val c = new ServerConnector(srv, new HttpConnectionFactory(httpCfg))
        c.setPort(port)
        c
      case Some(cfg) =>
        // SecureRequestCustomizer surfaces TLS attributes (SNI, peer
        // cert chain) on the Request — needed for code paths that
        // care, harmless otherwise.
        httpCfg.addCustomizer(new SecureRequestCustomizer())
        val sslCtx       = TlsContextBuilder.build(cfg.certPemPath, cfg.keyPemPath)
        val sslFactory   = new SslContextFactory.Server()
        sslFactory.setSslContext(sslCtx)
        val httpFactory  = new HttpConnectionFactory(httpCfg)
        val c = new ServerConnector(srv, sslFactory, httpFactory)
        c.setPort(port)
        c

    srv.addConnector(connector)

    // ── Wire handlers: WebSocketUpgradeHandler is a `Handler.Wrapper`
    //    that intercepts `Upgrade: websocket` requests and forwards
    //    everything else to its inner handler.  We attach our HTTP
    //    dispatch handler as the inner; the WS container's mapping is
    //    "/*" so any upgrade path lands on our `WsCreator`. ─────────
    val httpDispatch = new HttpDispatchHandler(handler)
    val wsHandler = WebSocketUpgradeHandler.from(srv, (container: ServerWebSocketContainer) =>
      container.addMapping("/*", new WsCreatorImpl(handler))
    )
    wsHandler.setHandler(httpDispatch)

    srv.setHandler(wsHandler)
    srv.start()

    _server    = srv
    _connector = connector
    _localPort = connector.getLocalPort
    _running   = true

  override def stop(): Unit =
    _running = false
    val s = _server
    if s != null then try s.stop() catch case _: Throwable => ()
    _server    = null
    _connector = null

  override def isRunning: Boolean = _running
  override def localPort: Int     = _localPort

// ── HTTP dispatch handler ──────────────────────────────────────────────

/** Jetty `Handler.Abstract` that converts a Jetty `Request` into our
 *  POJO `Request`, runs `HttpHandler.onHttpRequest`, then writes the
 *  result back through Jetty's `Response.write`. */
final class HttpDispatchHandler(handler: HttpHandler) extends JHandler.Abstract:

  override def handle(
      req:      JRequest,
      resp:     JResponse,
      callback: JCallback
  ): Boolean =
    try
      val pojo = JettyRequestAdapter.fromJetty(req)
      handler.onHttpRequest(pojo) match
        case HttpResult.PlainResp(r) =>
          writePlain(resp, r, callback)
        case HttpResult.StreamResp(sr) =>
          writeStream(resp, sr, callback)
        case HttpResult.Reject(status, body, contentType) =>
          writeReject(resp, status, body, contentType, callback)
      true
    catch
      case t: Throwable =>
        try
          resp.setStatus(500)
          resp.write(true, ByteBuffer.wrap("Internal Server Error".getBytes("UTF-8")), JCallback.NOOP)
        catch case _: Throwable => ()
        callback.failed(t)
        true

  private def writePlain(resp: JResponse, r: Response, callback: JCallback): Unit =
    resp.setStatus(r.status)
    val headers = resp.getHeaders
    r.headers.foreach((k, v) => headers.add(k, v))
    if !r.headers.exists((k, _) => k.equalsIgnoreCase("Content-Type")) then
      headers.add("Content-Type", "text/plain; charset=utf-8")
    r.setSession.foreach { payload =>
      headers.add("Set-Cookie", SessionCookie.toSetCookie(payload, secureFlag = false))
    }
    val bytes = r.body.getBytes("UTF-8")
    resp.write(true, ByteBuffer.wrap(bytes), callback)

  private def writeStream(resp: JResponse, sr: StreamResponse, callback: JCallback): Unit =
    resp.setStatus(sr.status)
    val headers = resp.getHeaders
    sr.headers.foreach((k, v) => headers.add(k, v))
    if !sr.headers.exists((k, _) => k.equalsIgnoreCase("Content-Type")) then
      headers.add("Content-Type", "text/plain; charset=utf-8")
    // Drive the user-supplied writer.  Each emitted chunk is a non-last
    // write; we send a final empty last-write when the writer returns.
    try
      sr.writer { chunk =>
        val bytes = chunk.getBytes("UTF-8")
        resp.write(false, ByteBuffer.wrap(bytes), JCallback.NOOP)
      }
      resp.write(true, ByteBuffer.allocate(0), callback)
    catch case t: Throwable => callback.failed(t)

  private def writeReject(
      resp:        JResponse,
      status:      Int,
      body:        String,
      contentType: String,
      callback:    JCallback
  ): Unit =
    resp.setStatus(status)
    resp.getHeaders.add("Content-Type", contentType)
    val bytes = body.getBytes("UTF-8")
    resp.write(true, ByteBuffer.wrap(bytes), callback)

// ── Request adapter ────────────────────────────────────────────────────

object JettyRequestAdapter:

  /** Build a POJO `Request` from a Jetty `Request`.  Reads the whole
   *  body synchronously (acceptable for plain-HTTP request dispatch;
   *  the SPI contract gives `Request.body` as a `String`, so we eagerly
   *  buffer here). */
  def fromJetty(req: JRequest): Request =
    val method   = req.getMethod
    val uri      = req.getHttpURI
    val path     = uri.getPath
    val rawQuery = Option(uri.getQuery).getOrElse("")

    // Headers — lowercase keys, last value wins on collisions (same
    // convention the JDK backend uses through HttpHelpers.parseHttpHead).
    val headersBuilder = scala.collection.mutable.Map.empty[String, String]
    val it = req.getHeaders.iterator()
    while it.hasNext do
      val f = it.next()
      headersBuilder(f.getName.toLowerCase) = f.getValue
    val headers = headersBuilder.toMap

    val cookies = HttpHelpers.parseCookieHeader(headers.getOrElse("cookie", ""))
    val query   = HttpHelpers.parseQuery(rawQuery)

    // Body — drain Content.Source synchronously.  Bounded by Jetty's
    // internal HttpConfiguration max-form-content-size / max-request
    // limits; for the SPI we just read whatever is there.
    val body: String =
      try
        val bytes = Content.Source.asByteBuffer(req)
        if bytes.remaining() == 0 then ""
        else
          val arr = new Array[Byte](bytes.remaining())
          bytes.get(arr)
          new String(arr, "UTF-8")
      catch case _: Throwable => ""

    Request(
      method  = method,
      path    = path,
      params  = Map.empty,
      query   = query,
      headers = headers,
      body    = body,
      cookies = cookies
    )

  /** Build a POJO `Request` from a Jetty WS `ServerUpgradeRequest`.
   *  Same shape as `fromJetty` but body is empty (upgrades are GETs). */
  def fromUpgrade(req: ServerUpgradeRequest): Request =
    val method   = req.getMethod
    val uri      = req.getHttpURI
    val path     = uri.getPath
    val rawQuery = Option(uri.getQuery).getOrElse("")
    val headersBuilder = scala.collection.mutable.Map.empty[String, String]
    val it = req.getHeaders.iterator()
    while it.hasNext do
      val f = it.next()
      headersBuilder(f.getName.toLowerCase) = f.getValue
    val headers = headersBuilder.toMap
    val cookies = HttpHelpers.parseCookieHeader(headers.getOrElse("cookie", ""))
    val query   = HttpHelpers.parseQuery(rawQuery)
    Request(
      method  = method,
      path    = path,
      params  = Map.empty,
      query   = query,
      headers = headers,
      body    = "",
      cookies = cookies
    )

// ── WebSocket creator + endpoint + controls ────────────────────────────

/** Bridges Jetty's `WebSocketCreator` to our `HttpHandler.onWsUpgrade`.
 *  Returns either a [[JettyWsEndpoint]] (Accept) or `null` after
 *  setting an error status on the response (Reject). */
final class WsCreatorImpl(handler: HttpHandler) extends WebSocketCreator:

  override def createWebSocket(
      req:      ServerUpgradeRequest,
      resp:     ServerUpgradeResponse,
      @scala.annotation.unused callback: JCallback
  ): Object =
    val pojo = JettyRequestAdapter.fromUpgrade(req)
    handler.onWsUpgrade(pojo) match
      case WsUpgradeResult.Reject(status, reason) =>
        // Sets the HTTP status on the upgrade response so Jetty writes
        // a regular non-101 response (e.g. 403 / 404 / 503).
        resp.setStatus(status)
        if reason != null && reason.nonEmpty then
          resp.getHeaders.add("X-WS-Reject-Reason", reason)
        Metrics.wsRejected.incrementAndGet()
        null
      case WsUpgradeResult.Accept(subprotocol, listener) =>
        // Only set the accepted subprotocol if the client offered it
        // (Jetty validates this; passing an unoffered value would
        // fail the upgrade).
        if subprotocol != null && subprotocol.nonEmpty &&
           req.hasSubProtocol(subprotocol) then
          resp.setAcceptedSubProtocol(subprotocol)
        Metrics.wsUpgraded.incrementAndGet()
        new JettyWsEndpoint(listener, subprotocol)

/** Jetty `Session.Listener.AbstractAutoDemanding` endpoint that fans
 *  inbound frames out to our `WsListener` and exposes a `WsControls`
 *  write-side handle through `onWebSocketOpen`.
 *
 *  Extends `AbstractAutoDemanding` (not the bare `AutoDemanding`
 *  interface) because Jetty's annotation processor would otherwise
 *  see both the default `onWebSocketPartialText(String, Boolean)` and
 *  our override of `onWebSocketText(String)` and reject the endpoint
 *  with "Cannot replace previously assigned [TEXT Handler]". */
final class JettyWsEndpoint(listener: WsListener, subprotocol: String)
    extends JSession.Listener.AbstractAutoDemanding:

  @scala.annotation.unused
  @volatile private var _controls: JettyWsControls | Null = null

  override def onWebSocketOpen(session: JSession): Unit =
    val controls = new JettyWsControls(session, subprotocol)
    _controls = controls
    try listener.onOpen(controls)
    catch case e: Throwable => safeError(e)

  override def onWebSocketText(message: String): Unit =
    try listener.onMessage(message)
    catch case e: Throwable => safeError(e)

  override def onWebSocketBinary(payload: ByteBuffer, callback: WsCallback): Unit =
    try
      val arr = new Array[Byte](payload.remaining())
      payload.get(arr)
      listener.onBinary(arr)
      callback.succeed()
    catch case e: Throwable =>
      safeError(e)
      callback.fail(e)

  override def onWebSocketPong(payload: ByteBuffer): Unit =
    try
      val arr = new Array[Byte](payload.remaining())
      payload.get(arr)
      listener.onPong(arr)
    catch case e: Throwable => safeError(e)

  override def onWebSocketClose(statusCode: Int, reason: String): Unit =
    try listener.onClose(statusCode, if reason == null then "" else reason)
    catch case _: Throwable => ()

  override def onWebSocketError(cause: Throwable): Unit =
    safeError(cause)

  private def safeError(t: Throwable): Unit =
    try listener.onError(t) catch case _: Throwable => ()

/** `WsControls` adapter wrapping a Jetty `Session`.  Sends synchronously
 *  via `Callback.NOOP` — the SPI contract doesn't expose write-completion
 *  callbacks (`send` is fire-and-forget). */
final class JettyWsControls(session: JSession, _subprotocol: String) extends WsControls:

  private val _id: String = UUID.randomUUID().toString

  override def id: String = _id

  override def remoteAddress: String =
    try
      val addr = session.getRemoteSocketAddress
      if addr == null then "?"
      else addr.toString.stripPrefix("/")
    catch case _: Throwable => "?"

  override def subprotocol: String =
    if _subprotocol == null then "" else _subprotocol

  // Async-style recv() is unsupported on the Jetty path — Jetty's Session
  // is callback-driven, not pull-driven.  User code that wants pull
  // semantics should stick with the JDK backend.
  override def recv(): Option[String] = None

  override def send(text: String): Unit =
    try session.sendText(text, WsCallback.NOOP) catch case _: Throwable => ()

  override def sendBytes(bytes: Array[Byte]): Unit =
    try session.sendBinary(ByteBuffer.wrap(bytes), WsCallback.NOOP)
    catch case _: Throwable => ()

  override def ping(payload: Array[Byte]): Unit =
    val buf =
      if payload == null || payload.isEmpty then ByteBuffer.allocate(0)
      else ByteBuffer.wrap(payload)
    try session.sendPing(buf, WsCallback.NOOP) catch case _: Throwable => ()

  override def close(code: Int, reason: String): Unit =
    try session.close(code, reason, WsCallback.NOOP) catch case _: Throwable => ()

  override def isClosed: Boolean =
    try !session.isOpen catch case _: Throwable => true
