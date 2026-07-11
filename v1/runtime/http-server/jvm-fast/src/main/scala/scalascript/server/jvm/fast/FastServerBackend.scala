package scalascript.server.jvm.fast

import scalascript.server.{Request, RequestBuilder, Response, StreamResponse, SessionCookie, TlsContextBuilder}
import scalascript.server.spi.*
import ssc.plugin.httpfast.{FastHttpServer, RawRequest, RawResponse, HttpReader, WsConnection, WebSocketFrames}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import javax.net.ssl.SSLServerSocket

/** `HttpServerSpi` backend on the from-scratch fast engine (NIO + virtual-thread-per-
  * connection HTTP/1.1 + RFC 6455 WebSocket). Selected with `setHttpServerBackend("fast")`;
  * gives the whole v1 `WebServer` framework — and thus the `--v2` lane — the fast transport
  * while every framework feature (routing, static, session, health, OpenAPI, WS) stays above
  * this SPI. Mirrors the Jetty backend: builds the POJO `Request` directly, maps `HttpResult`,
  * and drives the shared `WsListener`/`WsControls` for upgrades. */
class FastServerBackend extends HttpServerSpi:
  override val name: String = "fast"

  @volatile private var _engine:    FastHttpServer | Null = null
  @volatile private var _running:   Boolean = false
  @volatile private var _localPort: Int     = 0
  @volatile private var _secure:    Boolean = false // HTTPS → Secure session cookies

  override def start(port: Int, tls: Option[TlsConfig], handler: HttpHandler): Unit =
    if _running then return
    _secure = tls.isDefined
    val engine = new FastHttpServer(
      handler   = raw => httpDispatch(raw, handler),
      webSocket = Some(new WsBridge(handler)))
    val ss: ServerSocket = tls match
      case None =>
        val s = new ServerSocket()
        s.setReuseAddress(true)
        s.bind(new InetSocketAddress(port))
        s
      case Some(cfg) =>
        val ctx = TlsContextBuilder.build(cfg.certPemPath, cfg.keyPemPath)
        ctx.getServerSocketFactory.createServerSocket(port).asInstanceOf[SSLServerSocket]
    _engine   = engine
    _running  = true
    _localPort = engine.startOn(ss)

  override def stop(): Unit =
    _running = false
    val e = _engine
    if e != null then try e.stop() catch case _: Throwable => ()
    _engine = null

  override def isRunning: Boolean = _running
  override def localPort: Int     = _localPort

  // ── HTTP ──────────────────────────────────────────────────────────────

  private def httpDispatch(raw: RawRequest, handler: HttpHandler): RawResponse =
    val (request, _, spooledTmps) = toPojo(raw)
    try
      handler.onHttpRequest(request) match
        case HttpResult.PlainResp(r)   => plainToRaw(r)
        case HttpResult.StreamResp(sr) => streamToRaw(sr)
        case HttpResult.Reject(status, body, contentType) =>
          RawResponse(status, Map("Content-Type" -> contentType), body.getBytes(UTF_8))
    finally spooledTmps.foreach(f => try f.delete() catch case _: Throwable => ())

  /** Reuse the transport-neutral half of RequestBuilder so fast and JDK
    * backends expose identical form/session/auth/file semantics. */
  private def toPojo(raw: RawRequest): (Request, Map[String, String], List[java.io.File]) =
    RequestBuilder.parseRaw(raw.method, raw.path, Map.empty, raw.query,
      raw.headers, raw.body)

  private def plainToRaw(r: Response): RawResponse =
    var headers = r.headers
    if !headers.keysIterator.exists(_.equalsIgnoreCase("content-type")) then
      headers = headers + ("Content-Type" -> "text/plain; charset=utf-8")
    r.setSession.foreach { payload =>
      headers = headers + ("Set-Cookie" -> SessionCookie.toSetCookie(payload, secureFlag = _secure))
    }
    RawResponse(r.status, headers, r.body.getBytes(UTF_8))

  private def streamToRaw(sr: StreamResponse): RawResponse =
    var headers = sr.headers
    if !headers.keysIterator.exists(_.equalsIgnoreCase("content-type")) then
      headers = headers + ("Content-Type" -> "text/plain; charset=utf-8")
    RawResponse(sr.status, headers, Array.emptyByteArray,
      stream = Some { out =>
        try sr.writer { chunk => out.write(chunk.getBytes(UTF_8)); out.flush() }
        catch case _: Throwable => ()
      })

  // ── WebSocket ─────────────────────────────────────────────────────────

  private final class WsBridge(handler: HttpHandler) extends FastHttpServer.WebSocketDispatcher:
    // Any upgrade reaches the handler; accept/reject is the handler's decision (onWsUpgrade).
    def hasRoute(path: String): Boolean = true

    def onUpgrade(request: RawRequest, sock: Socket, reader: HttpReader, out: java.io.OutputStream): Unit =
      handler.onWsUpgrade(toPojo(request)._1) match
        case WsUpgradeResult.Reject(status, reason) =>
          try
            out.write(s"HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
              .getBytes(ISO_8859_1))
            out.flush()
          catch case _: Throwable => ()
          try sock.close() catch case _: Throwable => ()

        case WsUpgradeResult.Accept(subprotocol, listener) =>
          val key = request.headers.getOrElse("sec-websocket-key", "")
          val sub = if subprotocol == null || subprotocol.isEmpty then None else Some(subprotocol)
          WebSocketFrames.writeHandshake(out, key, sub)
          sock.setSoTimeout(0)
          val conn = new WsConnection(0L, sock, reader, out, request, sub)
          val controls = new FastWsControls(conn, sub.getOrElse(""))
          conn.onText   = s => try listener.onMessage(s)         catch case e: Throwable => listener.onError(e)
          conn.onBinary = b => try listener.onBinary(b)          catch case e: Throwable => listener.onError(e)
          conn.onPong   = p => try listener.onPong(p)            catch case _: Throwable => ()
          conn.onClose  = (code, reason) => try listener.onClose(code, reason) catch case _: Throwable => ()
          try listener.onOpen(controls) catch case e: Throwable => listener.onError(e)
          conn.readLoop() // blocks on this connection's vthread until close

/** `WsControls` adapter over a fast-engine [[WsConnection]]. */
final class FastWsControls(conn: WsConnection, negotiatedSubprotocol: String) extends WsControls:
  private val _id = java.util.UUID.randomUUID().toString
  override def id: String             = _id
  override def remoteAddress: String  = conn.remoteAddress
  override def subprotocol: String    = negotiatedSubprotocol
  override def send(text: String): Unit        = conn.sendText(text)
  override def sendBytes(bytes: Array[Byte]): Unit = conn.sendBytes(bytes)
  override def ping(payload: Array[Byte]): Unit =
    conn.ping(if payload == null then Array.emptyByteArray else payload)
  override def close(code: Int, reason: String): Unit = conn.close(code, reason)
  override def isClosed: Boolean       = conn.isClosed
  override def recv(): Option[String]  = conn.recv()
