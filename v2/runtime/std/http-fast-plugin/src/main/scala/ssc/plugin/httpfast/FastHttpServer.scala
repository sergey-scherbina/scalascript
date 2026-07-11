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
    maxKeepAliveRequests: Int = 10_000):

  @volatile private var server: ServerSocket | Null = null
  @volatile private var running = false
  private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet[Socket]()
  private val acceptThread = new java.util.concurrent.atomic.AtomicReference[Thread | Null](null)

  /** Bind + start accepting. Returns the actual bound port (useful with port 0). */
  def start(port: Int): Int =
    val ss = new ServerSocket()
    ss.setReuseAddress(true)
    ss.bind(new InetSocketAddress(host, port))
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
        else
          served += 1
          val resp =
            try handler(req.nn)
            catch case err: Throwable =>
              RawResponse(500, Map("Content-Type" -> "text/plain; charset=utf-8"),
                s"native HTTP handler failed: ${msg(err)}".getBytes(ISO_8859_1))
          val keep = req.nn.keepAlive && running && served < maxKeepAliveRequests
          HttpProtocol.writeResponse(out, resp, keep)
          if !keep then open = false
    catch case _: IOException => () // client vanished mid-write
    finally
      connections.remove(sock)
      closeQuietly(sock)

  private def trySend(out: OutputStream, resp: RawResponse, keepAlive: Boolean): Unit =
    try HttpProtocol.writeResponse(out, resp, keepAlive)
    catch case _: IOException => ()

  private def msg(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.getClass.getSimpleName)

  /** Stop accepting, close the listener, and drop all live connections. Idempotent. */
  def stop(): Unit =
    running = false
    val s = server
    server = null
    if s != null then closeQuietly(s.nn)
    val t = acceptThread.getAndSet(null)
    if t != null then t.nn.interrupt()
    connections.forEach(closeQuietly)
    connections.clear()

  private def closeQuietly(c: AutoCloseable): Unit =
    try c.close() catch case _: Throwable => ()
