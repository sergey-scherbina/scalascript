package scalascript.server.jvm.netty

import scalascript.server.*
import scalascript.server.spi.*

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.{
  Channel,
  ChannelFutureListener,
  ChannelHandlerContext,
  ChannelInitializer,
  SimpleChannelInboundHandler
}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  DefaultHttpResponse,
  DefaultHttpContent,
  FullHttpRequest,
  LastHttpContent,
  HttpHeaderNames,
  HttpHeaderValues,
  HttpObjectAggregator,
  HttpResponseStatus,
  HttpServerCodec,
  HttpUtil,
  HttpVersion
}
import io.netty.handler.codec.http.websocketx.{
  BinaryWebSocketFrame,
  CloseWebSocketFrame,
  PingWebSocketFrame,
  PongWebSocketFrame,
  TextWebSocketFrame,
  WebSocketFrame,
  WebSocketServerHandshaker,
  WebSocketServerHandshakerFactory
}
import io.netty.handler.ssl.{SslContext, SslContextBuilder}

import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Netty 4-based `HttpServerSpi` implementation.  Optional — pulls
 *  Netty (~4 MB) as a runtime dependency.  Highest throughput per
 *  core (event-loop model), HTTP/3 incubator support, and custom
 *  protocol extensibility.
 *
 *  Implementation: a `ServerBootstrap` wires a pipeline of
 *  `HttpServerCodec` + `HttpObjectAggregator` + our
 *  [[NettyDispatchHandler]].  The dispatch handler:
 *
 *    - For plain HTTP requests: translates Netty's `FullHttpRequest`
 *      into the POJO [[Request]], calls
 *      `HttpHandler.onHttpRequest`, then writes the result back
 *      through Netty's `Channel`.
 *    - For WS upgrades: builds a POJO `Request`, calls
 *      `HttpHandler.onWsUpgrade`, and either negotiates the handshake
 *      via `WebSocketServerHandshakerFactory` (Accept) or writes a
 *      direct status response (Reject).  On Accept it swaps itself
 *      out of the pipeline for a [[NettyWsFrameHandler]] that fans
 *      frames into the supplied `WsListener`, and exposes a
 *      [[NettyWsControls]] write-side handle through `onOpen`.
 *
 *  Wire-format-equivalent to `JdkServerBackend` and
 *  `JettyServerBackend` for the SPI contract (HTTP/1.1 + RFC 6455
 *  WS).  No HTTP/2 by default (matches the other two backends; the
 *  Netty H2 codec is in the dep tree but not wired in — out of scope
 *  for S3). */
class NettyServerBackend extends HttpServerSpi:

  override val name: String = "netty"

  @volatile private var _bossGroup:   NioEventLoopGroup | Null = null
  @volatile private var _workerGroup: NioEventLoopGroup | Null = null
  @volatile private var _channel:     Channel | Null           = null
  @volatile private var _running:     Boolean                  = false
  @volatile private var _localPort:   Int                      = 0

  override def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit =
    if _running then return

    val sslCtx: SslContext | Null = tls match
      case None      => null
      case Some(cfg) =>
        SslContextBuilder
          .forServer(new java.io.File(cfg.certPemPath), new java.io.File(cfg.keyPemPath))
          .build()

    val boss   = new NioEventLoopGroup(1)
    val worker = new NioEventLoopGroup()

    try
      val bootstrap = new ServerBootstrap()
      bootstrap
        .group(boss, worker)
        .channel(classOf[NioServerSocketChannel])
        .childHandler(new ChannelInitializer[SocketChannel]:
          override def initChannel(ch: SocketChannel): Unit =
            val p = ch.pipeline()
            if sslCtx != null then
              p.addLast(sslCtx.newHandler(ch.alloc()))
            p.addLast(new HttpServerCodec())
            p.addLast(new HttpObjectAggregator(16 * 1024 * 1024))
            p.addLast(new NettyDispatchHandler(handler, sslCtx != null))
        )

      val ch = bootstrap.bind(port).sync().channel()
      _bossGroup   = boss
      _workerGroup = worker
      _channel     = ch
      _localPort   = ch.localAddress().asInstanceOf[InetSocketAddress].getPort
      _running     = true
    catch
      case t: Throwable =>
        try boss.shutdownGracefully()   catch case _: Throwable => ()
        try worker.shutdownGracefully() catch case _: Throwable => ()
        throw t

  override def stop(): Unit =
    if !_running then return
    _running = false
    val ch = _channel
    if ch != null then try ch.close().sync() catch case _: Throwable => ()
    val w = _workerGroup
    if w != null then try w.shutdownGracefully() catch case _: Throwable => ()
    val b = _bossGroup
    if b != null then try b.shutdownGracefully() catch case _: Throwable => ()
    _channel     = null
    _bossGroup   = null
    _workerGroup = null

  override def isRunning: Boolean = _running
  override def localPort: Int     = _localPort

// ── HTTP / upgrade dispatch handler ────────────────────────────────────

/** Reads an aggregated `FullHttpRequest`, decides plain-HTTP vs. WS
 *  upgrade, and either drives the supplied `HttpHandler` or swaps
 *  itself out for a [[NettyWsFrameHandler]].
 *
 *  Lives at the tail of the channel pipeline (after `HttpServerCodec`
 *  + `HttpObjectAggregator`), one per connection. */
final class NettyDispatchHandler(handler: HttpHandler, isTls: Boolean)
    extends SimpleChannelInboundHandler[FullHttpRequest]:

  override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    if isWsUpgrade(req) then handleWsUpgrade(ctx, req)
    else                     handlePlainHttp(ctx, req)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    val _ = cause
    try
      val resp = new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.INTERNAL_SERVER_ERROR,
        Unpooled.wrappedBuffer("Internal Server Error".getBytes(StandardCharsets.UTF_8))
      )
      resp.headers.set(HttpHeaderNames.CONTENT_TYPE,   "text/plain; charset=utf-8")
      resp.headers.set(HttpHeaderNames.CONTENT_LENGTH, resp.content.readableBytes)
      ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE)
      ()
    catch case _: Throwable =>
      val _ = ctx.close()
      ()

  // ── Plain HTTP ───────────────────────────────────────────────────────

  private def handlePlainHttp(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    val pojo = NettyRequestAdapter.fromNetty(req)
    try
      handler.onHttpRequest(pojo) match
        case HttpResult.PlainResp(r)                  => writePlain(ctx, r)
        case HttpResult.StreamResp(sr)                => writeStream(ctx, sr)
        case HttpResult.Reject(status, body, ctType)  => writeReject(ctx, status, body, ctType)
    catch
      case t: Throwable => exceptionCaught(ctx, t)

  private def writePlain(ctx: ChannelHandlerContext, r: Response): Unit =
    val bytes = r.body.getBytes(StandardCharsets.UTF_8)
    val resp  = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(r.status),
      Unpooled.wrappedBuffer(bytes)
    )
    r.headers.foreach((k, v) => resp.headers.add(k, v))
    if !r.headers.exists((k, _) => k.equalsIgnoreCase("Content-Type")) then
      resp.headers.set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
    r.setSession.foreach { payload =>
      resp.headers.add(HttpHeaderNames.SET_COOKIE, SessionCookie.toSetCookie(payload, secureFlag = false))
    }
    resp.headers.set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
    ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE)

  private def writeStream(ctx: ChannelHandlerContext, sr: StreamResponse): Unit =
    val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(sr.status))
    sr.headers.foreach((k, v) => resp.headers.add(k, v))
    if !sr.headers.exists((k, _) => k.equalsIgnoreCase("Content-Type")) then
      resp.headers.set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
    HttpUtil.setTransferEncodingChunked(resp, true)
    ctx.writeAndFlush(resp)
    try
      sr.writer { chunk =>
        val bytes = chunk.getBytes(StandardCharsets.UTF_8)
        ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)))
        ()
      }
      ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
        .addListener(ChannelFutureListener.CLOSE)
    catch case t: Throwable => exceptionCaught(ctx, t)

  private def writeReject(ctx: ChannelHandlerContext, status: Int, body: String, ct: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    val resp  = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(status),
      Unpooled.wrappedBuffer(bytes)
    )
    resp.headers.set(HttpHeaderNames.CONTENT_TYPE,   ct)
    resp.headers.set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
    ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE)

  // ── WS upgrade ───────────────────────────────────────────────────────

  private def isWsUpgrade(req: FullHttpRequest): Boolean =
    val upgrade = req.headers.get(HttpHeaderNames.UPGRADE)
    upgrade != null && upgrade.toString.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET.toString)

  private def handleWsUpgrade(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    val pojo = NettyRequestAdapter.fromNetty(req)
    handler.onWsUpgrade(pojo) match
      case WsUpgradeResult.Reject(status, reason) =>
        val resp = new DefaultFullHttpResponse(
          HttpVersion.HTTP_1_1,
          HttpResponseStatus.valueOf(status)
        )
        resp.headers.set(HttpHeaderNames.CONTENT_LENGTH, 0)
        resp.headers.set(HttpHeaderNames.CONNECTION,     "close")
        if reason != null && reason.nonEmpty then
          resp.headers.add("X-WS-Reject-Reason", reason)
        Metrics.wsRejected.incrementAndGet()
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE)
        ()

      case WsUpgradeResult.Accept(subprotocol, listener) =>
        val scheme = if isTls then "wss" else "ws"
        val host   = Option(req.headers.get(HttpHeaderNames.HOST)).map(_.toString).getOrElse("localhost")
        val wsUrl  = s"$scheme://$host${req.uri}"
        val subp   = if subprotocol == null || subprotocol.isEmpty then null else subprotocol
        val factory = new WebSocketServerHandshakerFactory(wsUrl, subp, false)
        val shaker  = factory.newHandshaker(req)
        if shaker == null then
          WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel)
          ()
        else
          // shaker.handshake retains req internally as needed; it
          // mutates the pipeline by inserting the WS encoders/decoders.
          shaker.handshake(ctx.channel, req)
          Metrics.wsUpgraded.incrementAndGet()
          val frameHandler = new NettyWsFrameHandler(listener, shaker)
          ctx.pipeline.replace(this, "ws-frame", frameHandler)
          val effectiveSub =
            val negotiated = shaker.selectedSubprotocol()
            if negotiated == null then "" else negotiated
          val controls = new NettyWsControls(ctx.channel, shaker, effectiveSub)
          try listener.onOpen(controls)
          catch case t: Throwable =>
            try listener.onError(t) catch case _: Throwable => ()

// ── Request adapter ────────────────────────────────────────────────────

object NettyRequestAdapter:

  /** Build a POJO `Request` from a Netty `FullHttpRequest`.  Headers are
   *  lowercased on the way in; the body is read out of the aggregated
   *  `content()` as UTF-8 (the SPI contract gives `Request.body` as a
   *  `String`). */
  def fromNetty(req: FullHttpRequest): Request =
    val method = req.method.name
    val uri    = URI.create(req.uri)
    val path   = Option(uri.getRawPath).getOrElse(req.uri)
    val rawQ   = Option(uri.getRawQuery).getOrElse("")

    val hb = scala.collection.mutable.Map.empty[String, String]
    val it = req.headers.iteratorAsString()
    while it.hasNext do
      val e = it.next()
      hb(e.getKey.toLowerCase) = e.getValue
    val headers = hb.toMap

    val cookies = HttpHelpers.parseCookieHeader(headers.getOrElse("cookie", ""))
    val query   = HttpHelpers.parseQuery(rawQ)

    val body: String =
      val buf = req.content
      if buf == null || buf.readableBytes == 0 then ""
      else
        val arr = new Array[Byte](buf.readableBytes)
        buf.getBytes(buf.readerIndex, arr)
        new String(arr, StandardCharsets.UTF_8)

    Request(
      method  = method,
      path    = path,
      params  = Map.empty,
      query   = query,
      headers = headers,
      body    = body,
      cookies = cookies
    )

// ── WebSocket frame handler ────────────────────────────────────────────

/** Netty `SimpleChannelInboundHandler[WebSocketFrame]` that fans
 *  inbound frames out to our `WsListener`.  Installed after the
 *  handshake completes; replaces [[NettyDispatchHandler]] in the
 *  pipeline. */
final class NettyWsFrameHandler(listener: WsListener, shaker: WebSocketServerHandshaker)
    extends SimpleChannelInboundHandler[WebSocketFrame]:

  override def channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame): Unit =
    frame match
      case t: TextWebSocketFrame =>
        try listener.onMessage(t.text)
        catch case e: Throwable => safeError(e)

      case b: BinaryWebSocketFrame =>
        try
          val buf = b.content
          val arr = new Array[Byte](buf.readableBytes)
          buf.getBytes(buf.readerIndex, arr)
          listener.onBinary(arr)
        catch case e: Throwable => safeError(e)

      case p: PongWebSocketFrame =>
        try
          val buf = p.content
          val arr = new Array[Byte](buf.readableBytes)
          buf.getBytes(buf.readerIndex, arr)
          listener.onPong(arr)
        catch case e: Throwable => safeError(e)

      case _: PingWebSocketFrame =>
        // Netty's WS codec auto-replies to pings.  Nothing to do.
        ()

      case c: CloseWebSocketFrame =>
        val code   = c.statusCode
        val reason = Option(c.reasonText).getOrElse("")
        try listener.onClose(code, reason) catch case _: Throwable => ()
        try shaker.close(ctx.channel, c.retain()) catch case _: Throwable => ()

      case _ => ()

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    safeError(cause)
    val _ = ctx.close()
    ()

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    // If the channel dies without a Close frame, surface that to the
    // listener as a 1006 abnormal closure (mirrors the JDK backend).
    try listener.onClose(1006, "") catch case _: Throwable => ()
    super.channelInactive(ctx)

  private def safeError(t: Throwable): Unit =
    try listener.onError(t) catch case _: Throwable => ()

// ── WS controls ────────────────────────────────────────────────────────

/** `WsControls` adapter wrapping a Netty `Channel`.  Writes are
 *  fire-and-forget (the SPI contract doesn't expose per-write
 *  callbacks).  `close` uses the handshaker so the codec sends a
 *  proper RFC 6455 close frame. */
final class NettyWsControls(
    ch:           Channel,
    shaker:       WebSocketServerHandshaker,
    _subprotocol: String
) extends WsControls:

  private val _id: String = UUID.randomUUID().toString

  override def id: String = _id

  override def remoteAddress: String =
    try
      val addr = ch.remoteAddress()
      if addr == null then "?" else addr.toString.stripPrefix("/")
    catch case _: Throwable => "?"

  override def subprotocol: String =
    if _subprotocol == null then "" else _subprotocol

  // Async-style recv() is unsupported on the Netty path — the channel
  // is event-loop-driven, not pull-driven.  User code that wants pull
  // semantics should stick with the JDK backend.
  override def recv(): Option[String] = None

  override def send(text: String): Unit =
    try
      ch.writeAndFlush(new TextWebSocketFrame(text))
      ()
    catch case _: Throwable => ()

  override def sendBytes(bytes: Array[Byte]): Unit =
    try
      ch.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)))
      ()
    catch case _: Throwable => ()

  override def ping(payload: Array[Byte]): Unit =
    try
      val buf =
        if payload == null || payload.isEmpty then Unpooled.EMPTY_BUFFER
        else Unpooled.wrappedBuffer(payload)
      ch.writeAndFlush(new PingWebSocketFrame(buf))
      ()
    catch case _: Throwable => ()

  override def close(code: Int = 1000, reason: String = ""): Unit =
    try
      shaker.close(ch, new CloseWebSocketFrame(code, reason))
      ()
    catch case _: Throwable => ()

  override def isClosed: Boolean =
    try !ch.isOpen catch case _: Throwable => true
