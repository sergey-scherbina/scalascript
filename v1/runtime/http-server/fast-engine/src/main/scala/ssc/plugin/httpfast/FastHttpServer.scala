package ssc.plugin.httpfast

import java.io.{BufferedOutputStream, IOException, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets.ISO_8859_1

/** A from-scratch, zero-dependency HTTP/1.1 server engine for the v2 JVM runtime.
  *
  * Transport is blocking `ServerSocket`/`Socket` with **one virtual thread per connection**
  * (JVM 21): `accept()` and `read()` park the carrier thread cheaply, so millions of idle
  * connections cost almost nothing and there is no selector event-loop complexity. Each
  * connection runs a keep-alive loop — parse a request, dispatch to `handler`, write the
  * response, repeat until `Connection: close`, an idle timeout, or EOF.
  *
  * The engine is value-agnostic: `handler: RawRequest => RawResponse`. The ssc `Value`
  * bridge (routing, Request/Response DataV) lives in the host. A handler that throws yields
  * a `500`; a malformed request yields a `400`. */
final class FastHttpServer(
    handler: RawRequest => RawResponse,
    host: String = "127.0.0.1",
    limits: HttpProtocol.Limits = HttpProtocol.Limits(),
    idleTimeoutMs: Int = 30_000,
    maxKeepAliveRequests: Int = 10_000,
    drainTimeoutMs: Int = 2_000,
    maxConnections: Int = 100_000,
    // Access-log / metrics hook, fired once per completed HTTP exchange:
    // (request, responseStatus, durationNanos). Default no-op.
    onExchange: (RawRequest, Int, Long) => Unit = (_, _, _) => (),
    webSocket: Option[FastHttpServer.WebSocketDispatcher] = None):

  @volatile private var server: ServerSocket | Null = null
  @volatile private var running = false
  private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet[Socket]()
  private val acceptThread = new java.util.concurrent.atomic.AtomicReference[Thread | Null](null)
  // In-flight HTTP request/response exchanges (excludes idle keep-alive waits + long-lived WS
  // read loops) — `stop()` drains on this so a graceful shutdown waits only for real work.
  private val activeRequests = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Bind + start accepting. Returns the actual bound port (useful with port 0). */
  def start(port: Int): Int =
    val ss = new ServerSocket()
    ss.setReuseAddress(true)
    ss.bind(new InetSocketAddress(host, port))
    startOn(ss)

  /** Start accepting on an already-bound `ServerSocket` (e.g. an `SSLServerSocket` for TLS, or
    * a socket the HttpServerSpi backend created). Returns the bound port. */
  def startOn(ss: ServerSocket): Int =
    server = ss
    running = true
    val t = Thread.ofVirtual().name("ssc-http-accept").start(() => acceptLoop(ss))
    acceptThread.set(t)
    ss.getLocalPort

  def port: Int = { val s = server; if s == null then -1 else s.nn.getLocalPort }

  private def acceptLoop(ss: ServerSocket): Unit =
    while running do
      val sock =
        try ss.accept()
        catch case _: IOException => null
      if sock != null then
        if connections.size() >= maxConnections then
          closeQuietly(sock) // over the connection cap → refuse
        else
          connections.add(sock)
          Thread.ofVirtual().name("ssc-http-conn").start(() => serveConnection(sock))

  private def serveConnection(sock: Socket): Unit =
    try
      sock.setTcpNoDelay(true)
      sock.setSoTimeout(idleTimeoutMs)
      val reader = new HttpReader(sock.getInputStream)
      val out    = new BufferedOutputStream(sock.getOutputStream, 16 * 1024)
      var open   = true
      var served = 0
      while open && running do
        val req =
          try HttpProtocol.parse(reader, out, limits)
          catch
            case _: SocketTimeoutException => null // idle → close quietly
            case b: BadRequest =>
              trySend(out, RawResponse(400, Map("Content-Type" -> "text/plain; charset=utf-8"),
                s"Bad Request: ${b.getMessage}".getBytes(ISO_8859_1)), keepAlive = false)
              open = false; null
        if req == null then open = false
        else if isWebSocketUpgrade(req.nn) then
          upgradeWebSocket(req.nn, sock, reader, out)
          open = false // the socket is now owned by the WS read loop (already returned/closed)
        else
          served += 1
          activeRequests.incrementAndGet()
          val startNs = System.nanoTime()
          try
            val resp =
              try handler(req.nn)
              catch case err: Throwable =>
                RawResponse(500, Map("Content-Type" -> "text/plain; charset=utf-8"),
                  s"native HTTP handler failed: ${msg(err)}".getBytes(ISO_8859_1))
            resp.stream match
              case Some(writeBody) =>
                // Open-ended stream (SSE / streamResponse): headers now, body over time, close.
                HttpProtocol.writeStreamHeaders(out, resp)
                fireExchange(req.nn, resp.status, startNs)
                try writeBody(out) catch case _: Throwable => ()
                open = false // connection consumed by the stream
              case None =>
                val keep = req.nn.keepAlive && running && served < maxKeepAliveRequests
                HttpProtocol.writeResponse(out, resp, keep)
                fireExchange(req.nn, resp.status, startNs)
                if !keep then open = false
          finally activeRequests.decrementAndGet()
    catch case _: IOException => () // client vanished mid-write
    finally
      connections.remove(sock)
      closeQuietly(sock)

  private def isWebSocketUpgrade(req: RawRequest): Boolean =
    webSocket.isDefined && WebSocketFrames.isUpgrade(req.headers) && webSocket.nn.get.hasRoute(req.path)

  /** The request is a WebSocket upgrade for a path the dispatcher owns — hand it the socket
    * for the full handshake (101 or reject) + frame read loop, on this connection's vthread. */
  private def upgradeWebSocket(req: RawRequest, sock: Socket, reader: HttpReader, out: OutputStream): Unit =
    webSocket.nn.get.onUpgrade(req, sock, reader, out)

  private def trySend(out: OutputStream, resp: RawResponse, keepAlive: Boolean): Unit =
    try HttpProtocol.writeResponse(out, resp, keepAlive)
    catch case _: IOException => ()

  private def msg(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.getClass.getSimpleName)

  private def fireExchange(req: RawRequest, status: Int, startNs: Long): Unit =
    try onExchange(req, status, System.nanoTime() - startNs) catch case _: Throwable => ()

  /** Stop accepting and shut down. Graceful: closes the listener, then gives in-flight
    * connections up to `drainTimeoutMs` to finish their current request (their keep-alive loop
    * sees `running == false` and closes after writing the response) before force-closing any
    * stragglers (idle keep-alive / long-lived WebSocket connections). Idempotent. */
  def stop(): Unit =
    running = false
    val s = server
    server = null
    if s != null then closeQuietly(s.nn) // stop accepting new connections
    val t = acceptThread.getAndSet(null)
    if t != null then t.nn.interrupt()
    // Drain: wait for in-flight request/response exchanges to complete (idle keep-alive waits
    // and long-lived WS loops aren't counted, so they don't hold shutdown open).
    if drainTimeoutMs > 0 then
      val deadline = System.nanoTime() + drainTimeoutMs.toLong * 1_000_000L
      while activeRequests.get() > 0 && System.nanoTime() < deadline do
        try Thread.sleep(10) catch case _: InterruptedException => ()
    connections.forEach(closeQuietly) // force-close idle keep-alive / WS / stragglers
    connections.clear()

  private def closeQuietly(c: AutoCloseable): Unit =
    try c.close() catch case _: Throwable => ()

object FastHttpServer:
  /** The engine's WebSocket seam. The ssc-value bridge implements it (routing to
    * `onWebSocket` handlers, building the `ws` value); the engine drives the handshake +
    * read loop. */
  trait WebSocketDispatcher:
    /** Does this path take WebSocket upgrades? `false` ⇒ the request 404s as normal HTTP. A
      * dispatcher that decides accept/reject itself (e.g. the HttpServerSpi backend) returns
      * `true` for any upgrade and rejects inside `onUpgrade`. */
    def hasRoute(path: String): Boolean
    /** Own the upgrade end-to-end: write the `101` (via [[WebSocketFrames.writeHandshake]]) or a
      * reject response, build a [[WsConnection]], wire its callbacks, and call `readLoop()`
      * (which blocks on this connection's vthread until close). */
    def onUpgrade(request: RawRequest, sock: java.net.Socket, reader: HttpReader,
                  out: java.io.OutputStream): Unit
