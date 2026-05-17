package scalascript.server

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{Selector, SelectionKey, ServerSocketChannel, SocketChannel}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import scalascript.interpreter.Value

/** Single-threaded NIO proxy sitting in front of an internal JDK
 *  `HttpServer`.  Sniffs the request line + headers; on
 *  `Upgrade: websocket` it terminates the WS handshake itself and switches
 *  the channel into frame mode, on any other request it opens a backend
 *  connection to the internal HTTP server and pipes bytes both ways.
 *
 *  Why an extra hop in front of an existing HTTP server?  Because
 *  `com.sun.net.httpserver.HttpServer` hides the raw socket — there's no
 *  upgrade hook on it.  Rather than rewrite the whole HTTP stack to
 *  unblock WebSockets, this proxy lets the existing stack keep handling
 *  REST traffic untouched while WS lives alongside it.  When the rest of
 *  the server migrates to NIO/Netty (planned) the proxy can be folded in.
 *
 *  Threading: one selector thread runs the entire loop.  WS application
 *  callbacks (`onMessage`, `onClose`) are dispatched to `wsExecutor`,
 *  which the caller is expected to make consistent with the interpreter's
 *  HTTP executor — sharing a single-thread executor across both keeps
 *  interpreter globals serial. */
final class WsProxy(
    publicPort:   Int,
    internalAddr: InetSocketAddress,
    wsExecutor:   Executor,
    log:          java.io.PrintStream,
    /** Heartbeat tuning forwarded to every accepted [[WsConnection]].
     *  Defaults match the production policy (30 s ping, 90 s dead-after);
     *  tests may shrink both to assert the round-trip in seconds. */
    heartbeatIntervalMs: Long = 30_000L,
    heartbeatDeadAfterMs: Long = 90_000L
):
  private val selector: Selector              = Selector.open()
  private val serverCh: ServerSocketChannel   = ServerSocketChannel.open()
  @volatile private var running: Boolean      = false
  private var thread: Thread                  = null
  // One shared scheduler drives the periodic heartbeat across every
  // active WsConnection.  Single daemon thread — heartbeats are
  // every-30s lightweight tasks, no need for a pool.
  private val heartbeats: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r => {
      val t = Thread(r, "ws-heartbeats"); t.setDaemon(true); t
    })

  // ─── State attached to each SelectionKey ──────────────────────────

  /** What we're doing with a given channel.  A `Client` is the
   *  socket the browser opened; a `Backend` is our outbound socket to
   *  the internal HttpServer.  `Ws` is a client socket that has
   *  finished the upgrade. */
  private sealed trait Mode
  private case object ReadingHeaders                          extends Mode
  private final case class HttpClient(peer: SelectionKey)     extends Mode
  private final case class HttpBackend(peer: SelectionKey)    extends Mode
  private final case class Ws(conn: WsConnection)             extends Mode

  /** Per-channel state — the SelectionKey attachment. */
  private final class Conn(
      val key:  SelectionKey,
      val ch:   SocketChannel,
      var mode: Mode = ReadingHeaders,
      val inBuf:  ByteBuffer = ByteBuffer.allocate(16 * 1024),
      val outBufs: java.util.ArrayDeque[ByteBuffer] =
                   new java.util.ArrayDeque[ByteBuffer]()
  )

  // ─── Lifecycle ────────────────────────────────────────────────────

  def start(): Unit =
    serverCh.bind(InetSocketAddress(publicPort))
    serverCh.configureBlocking(false)
    serverCh.register(selector, SelectionKey.OP_ACCEPT)
    running = true
    thread = Thread(() => runLoop(), s"ws-proxy-$publicPort")
    thread.setDaemon(false)
    thread.start()

  def stop(): Unit =
    running = false
    try selector.wakeup() catch case _: Throwable => ()
    try heartbeats.shutdownNow() catch case _: Throwable => ()

  def localPort: Int =
    serverCh.socket().getLocalPort

  // ─── Selector loop ────────────────────────────────────────────────

  private def runLoop(): Unit =
    while running && selector.isOpen do
      try
        selector.select()
        val it = selector.selectedKeys.iterator
        while it.hasNext do
          val key = it.next()
          it.remove()
          if !key.isValid then ()
          else if key.isAcceptable then onAccept(key)
          else
            if key.isConnectable then onConnect(key)
            if key.isValid && key.isReadable then onRead(key)
            if key.isValid && key.isWritable then onWrite(key)
      catch case e: Throwable =>
        log.println(s"ws-proxy error: ${e.getMessage}")
    try serverCh.close() catch case _: Throwable => ()
    try selector.close() catch case _: Throwable => ()

  /** Finish a non-blocking connect on a backend socket and switch its
   *  interestOps to OP_READ | OP_WRITE so the next pass can drain the
   *  already-queued request bytes and start reading the response.
   *  The OS connect on loopback is usually instantaneous but on macOS
   *  `SocketChannel.connect()` returns false (async) far more often
   *  than the documentation hints. */
  private def onConnect(key: SelectionKey): Unit =
    val conn = key.attachment.asInstanceOf[Conn]
    try
      conn.ch.finishConnect()
      key.interestOps((SelectionKey.OP_READ | SelectionKey.OP_WRITE)
        & ~SelectionKey.OP_CONNECT)
    catch case _: java.io.IOException =>
      closeChain(key)

  private def onAccept(key: SelectionKey): Unit =
    val server = key.channel.asInstanceOf[ServerSocketChannel]
    val client = server.accept()
    if client != null then
      client.configureBlocking(false)
      client.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
      // TCP keepalive: lets the OS detect peers that vanished without
      // sending FIN (yanked cables, dropped mobile sessions).  Without it
      // a dead WS connection sits in the parser indefinitely, holding its
      // file descriptor until ~2 h.
      client.setOption(StandardSocketOptions.SO_KEEPALIVE, java.lang.Boolean.TRUE)
      val clientKey = client.register(selector, SelectionKey.OP_READ)
      clientKey.attach(Conn(clientKey, client))

  private def onRead(key: SelectionKey): Unit =
    val conn = key.attachment.asInstanceOf[Conn]
    val n = try conn.ch.read(conn.inBuf)
            catch case _: java.io.IOException => -1
    if n < 0 then
      closeChain(key); return
    if n == 0 then return

    conn.mode match
      case ReadingHeaders        => maybeRouteFirstRequest(conn)
      case HttpClient(peerKey)   => forwardTo(conn, peerKey)
      case HttpBackend(peerKey)  => forwardTo(conn, peerKey)
      case Ws(ws)                =>
        conn.inBuf.flip()
        ws.onBytes(conn.inBuf)
        conn.inBuf.compact()

  /** Forward whatever's in `conn.inBuf` to `peerKey`'s outBufs and ask
   *  the selector to drain it. */
  private def forwardTo(conn: Conn, peerKey: SelectionKey): Unit =
    if !peerKey.isValid then
      closeChain(conn.key); return
    val peer = peerKey.attachment.asInstanceOf[Conn]
    conn.inBuf.flip()
    if conn.inBuf.hasRemaining then
      val copy = ByteBuffer.allocate(conn.inBuf.remaining)
      copy.put(conn.inBuf)
      copy.flip()
      peer.outBufs.add(copy)
      peerKey.interestOpsOr(SelectionKey.OP_WRITE)
    conn.inBuf.clear()

  private def onWrite(key: SelectionKey): Unit =
    val conn = key.attachment.asInstanceOf[Conn]
    conn.mode match
      case Ws(ws) =>
        try ws.flush() catch case _: Throwable => closeChain(key)
      case _ =>
        // Drain queued buffers into the channel.
        while !conn.outBufs.isEmpty do
          val buf = conn.outBufs.peek()
          val written = try conn.ch.write(buf) catch case _: java.io.IOException => -1
          if written < 0 then
            closeChain(key); return
          if buf.hasRemaining then return
          conn.outBufs.poll()
        key.interestOpsAnd(~SelectionKey.OP_WRITE)

  // ─── Header sniffing ──────────────────────────────────────────────

  /** First inspection of a brand-new client channel: see whether we've
   *  buffered enough bytes for `\r\n\r\n`, and if so decide whether to
   *  upgrade (WS) or forward (HTTP).  Returns harmlessly when more bytes
   *  are needed. */
  private def maybeRouteFirstRequest(conn: Conn): Unit =
    val buf = conn.inBuf
    val view = buf.duplicate()
    view.flip()
    val avail = view.remaining
    if avail < 16 then return
    val bytes = new Array[Byte](avail)
    view.get(bytes)
    val endIdx = indexOfDoubleCrlf(bytes)
    if endIdx < 0 then
      if avail >= buf.capacity then
        // Headers exceeded our buffer — bail.
        closeChain(conn.key)
      return

    // We have the full request head.  Parse it.
    val headerText = new String(bytes, 0, endIdx, StandardCharsets.ISO_8859_1)
    val lines      = headerText.split("\r\n").toList
    val requestLine = lines.headOption.getOrElse("")
    val headers     = lines.drop(1).flatMap { l =>
      val i = l.indexOf(':')
      if i < 0 then None
      else Some(l.substring(0, i).trim.toLowerCase -> l.substring(i + 1).trim)
    }.toMap

    val pathFromLine = requestLine.split(' ').lift(1).getOrElse("/")
    val path         = pathFromLine.split('?').head
    val rawQuery     = pathFromLine.split('?').lift(1).getOrElse("")
    val isWsUpgrade  = headers.get("upgrade").exists(_.equalsIgnoreCase("websocket")) &&
                       headers.get("connection").exists(_.toLowerCase.contains("upgrade"))

    isWsUpgrade match
      case true  => tryUpgrade(conn, path, rawQuery, headers, endIdx + 4, bytes)
      case false => beginHttpForward(conn, bytes)

  /** Try to match the path against the WS registry and, on success,
   *  send the 101 response + transition to frame mode.  Anything beyond
   *  the request head in the buffer is fed to the WS parser. */
  private def tryUpgrade(
      conn:      Conn,
      path:      String,
      rawQuery:  String,
      headers:   Map[String, String],
      headEnd:   Int,
      bytesSnap: Array[Byte]
  ): Unit =
    WsRoutes.matchPath(path) match
      case None =>
        // No matching WS route — answer with a 404 and close.
        val body  = s"No WebSocket route for $path"
        val resp  = httpResponse(404, "Not Found", body)
        conn.outBufs.add(ByteBuffer.wrap(resp))
        conn.outBufs.add(null) // sentinel: close after drain
        conn.key.interestOpsOr(SelectionKey.OP_WRITE)
      case Some((entry, params)) =>
        val key = headers.getOrElse("sec-websocket-key", "")
        if key.isEmpty then
          closeChain(conn.key); return
        // Origin allowlist check (CSRF guard).  A browser sends `Origin:`
        // on every WS handshake; if the route was registered with a
        // non-empty list, reject anything not on it.  Empty list = no
        // restriction (default).
        if entry.origins.nonEmpty then
          val origin = headers.getOrElse("origin", "")
          if !entry.origins.contains(origin) then
            val body  = s"Origin '$origin' not permitted"
            val resp  = httpResponse(403, "Forbidden", body)
            conn.outBufs.add(ByteBuffer.wrap(resp))
            conn.key.interestOpsOr(SelectionKey.OP_WRITE)
            return
        // Pre-upgrade auth hook (RFC-agnostic).  Runs on the proxy
        // selector thread; must be read-only over interpreter
        // globals.  Build the same Request snapshot the handler
        // will eventually see and hand it to the hook; on `None`
        // reply 401 and abort; on `Some(userValue)` carry the
        // payload through to `WsConnection.user`.
        val rawQ0 = parseQueryString(rawQuery)
        val cookies0: Map[String, String] =
          headers.get("cookie") match
            case None => Map.empty
            case Some(raw) =>
              raw.split(';').iterator.flatMap { pair =>
                val t = pair.trim
                val i = t.indexOf('=')
                if i < 0 then None
                else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
              }.toMap
        val authReq: Value = Value.InstanceV("Request", Map(
          "method"  -> Value.StringV("GET"),
          "path"    -> Value.StringV(path),
          "params"  -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "query"   -> Value.MapV(rawQ0.map((k, v)  => Value.StringV(k) -> Value.StringV(v))),
          "headers" -> Value.MapV(headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "cookies" -> Value.MapV(cookies0.map((k, v) => Value.StringV(k) -> Value.StringV(v)))
        ))
        // Auth result threads through to `WsConnection.user`.  Three
        // states: no hook (None, accept), hook returned a payload
        // (Some(v), accept and carry v), hook returned None or
        // threw (rejected, 401).  We model "rejected" with a
        // separate boolean so the user payload type stays simple.
        var userPayload: Option[Value] = None
        var authRejected: Boolean      = false
        entry.auth.foreach { fn =>
          try entry.interpreter.invoke(fn, List(authReq)) match
            case Value.OptionV(Some(v)) => userPayload = Some(v)
            case Value.OptionV(None)    => authRejected = true
            case other                  => userPayload = Some(other)
          catch case e: Throwable =>
            log.println(s"WS auth hook error: ${e.getMessage}")
            authRejected = true
        }
        if authRejected then
          val resp = httpResponse(401, "Unauthorized",
            "WebSocket upgrade denied: authentication required")
          conn.outBufs.add(ByteBuffer.wrap(resp))
          conn.key.interestOpsOr(SelectionKey.OP_WRITE)
          return
        // Process-wide cap on active WS sessions — refuses upgrades
        // with 503 before allocating a WsConnection.  Slot is released
        // in `WsConnection.closeNow` on disconnect.  Reserved AFTER
        // the cheaper Origin check so a denied-Origin attempt doesn't
        // briefly consume a slot.
        if !WsConnection.tryReserveSlot() then
          val resp = httpResponse(503, "Service Unavailable",
            "WebSocket connection limit reached")
          conn.outBufs.add(ByteBuffer.wrap(resp))
          conn.key.interestOpsOr(SelectionKey.OP_WRITE)
          return
        // Per-route cap — composed with the process-wide cap (both
        // must permit the upgrade).  Tried AFTER the process-wide
        // reservation so a route-denied attempt releases the global
        // slot it just took (mirrors the protocol-mismatch path
        // below).  0 = no per-route limit.
        if !entry.tryReserve() then
          WsConnection.releaseSlot()
          val resp = httpResponse(503, "Service Unavailable",
            s"Route ${entry.path} at capacity (${entry.maxConnections})")
          conn.outBufs.add(ByteBuffer.wrap(resp))
          conn.key.interestOpsOr(SelectionKey.OP_WRITE)
          return
        // Subprotocol negotiation (RFC 6455 §1.9).  When the route
        // was registered with a non-empty `protocols` list, pick the
        // first server-side protocol that also appears in the
        // client's `Sec-WebSocket-Protocol` request header.  No
        // match → refuse with 400 (registering protocols makes them
        // required; if the user wants "optional", they register no
        // protocols and read the header themselves via `ws.request`).
        val chosenProtocol: String =
          if entry.protocols.isEmpty then ""
          else
            val offered = headers.getOrElse("sec-websocket-protocol", "")
              .split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
            entry.protocols.find(offered.contains) match
              case Some(p) => p
              case None    =>
                WsConnection.releaseSlot()
                entry.release()
                val resp = httpResponse(400, "Bad Request",
                  s"No matching Sec-WebSocket-Protocol; server offers: ${entry.protocols.mkString(", ")}")
                conn.outBufs.add(ByteBuffer.wrap(resp))
                conn.key.interestOpsOr(SelectionKey.OP_WRITE)
                return
        val accept = WsFraming.acceptKey(key)
        val protoHeader =
          if chosenProtocol.isEmpty then ""
          else s"Sec-WebSocket-Protocol: $chosenProtocol\r\n"
        val response =
          "HTTP/1.1 101 Switching Protocols\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          s"Sec-WebSocket-Accept: $accept\r\n" +
          protoHeader + "\r\n"
        // Write the handshake synchronously — it's a few hundred bytes
        // and the socket buffer is empty.
        val respBytes = response.getBytes(StandardCharsets.US_ASCII)
        var written   = 0
        while written < respBytes.length do
          val rem = ByteBuffer.wrap(respBytes, written, respBytes.length - written)
          val n = try conn.ch.write(rem) catch case _: java.io.IOException => -1
          if n < 0 then
            closeChain(conn.key); return
          written += n
        // Build a Request-shaped Value so the handler can read auth /
        // cookies / origin via `ws.request.headers(...)`.  Mirrors the
        // REST Request shape minus body/form/files (no body on a GET
        // upgrade) and session/JWT (no eager pre-parsing here — the
        // handler can derive them from the raw headers if it wants to).
        val query = parseQueryString(rawQuery)
        // Cookie header: `name1=value1; name2=value2; …` → Map.
        // Same convention REST handlers use.
        val cookies: Map[String, String] =
          headers.get("cookie") match
            case None => Map.empty
            case Some(raw) =>
              raw.split(';').iterator.flatMap { pair =>
                val t = pair.trim
                val i = t.indexOf('=')
                if i < 0 then None
                else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
              }.toMap
        val request = Value.InstanceV("Request", Map(
          "method"  -> Value.StringV("GET"),
          "path"    -> Value.StringV(path),
          "params"  -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "query"   -> Value.MapV(query.map((k, v)  => Value.StringV(k) -> Value.StringV(v))),
          "headers" -> Value.MapV(headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "cookies" -> Value.MapV(cookies.map((k, v) => Value.StringV(k) -> Value.StringV(v)))
        ))
        // Transition: build the WsConnection and feed any post-handshake
        // bytes already in the buffer through its parser.
        val ws = WsConnection(conn.ch, conn.key, selector, entry.interpreter, wsExecutor, log, request, heartbeats, heartbeatIntervalMs, heartbeatDeadAfterMs, subprotocol = chosenProtocol, onTerminate = () => entry.release(), maxMessagesPerSec = entry.maxMessagesPerSec, user = userPayload)
        conn.mode = Ws(ws)
        conn.inBuf.clear()
        ws.startHeartbeat()
        val tail = bytesSnap.length - headEnd
        if tail > 0 then
          ws.onBytes(ByteBuffer.wrap(bytesSnap, headEnd, tail))
        // Hand the WebSocket value to the user's onWebSocket block.
        // Runs on the interpreter executor so global state stays serial.
        wsExecutor.execute { () =>
          try entry.interpreter.invoke(entry.handler, List(ws.asValue))
          catch case e: Throwable =>
            log.println(s"WS upgrade handler error: ${e.getMessage}")
        }

  /** Open a backend socket to the internal HttpServer, copy the buffered
   *  request head to it, and pair the two channels for further forwarding. */
  private def beginHttpForward(conn: Conn, bytesSnap: Array[Byte]): Unit =
    val back = SocketChannel.open()
    back.configureBlocking(false)
    back.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
    val connected = back.connect(internalAddr)
    val backKey = back.register(
      selector,
      if connected then SelectionKey.OP_READ | SelectionKey.OP_WRITE
      else            SelectionKey.OP_CONNECT
    )
    val backConn = Conn(backKey, back, HttpBackend(conn.key))
    backKey.attach(backConn)
    conn.mode = HttpClient(backKey)
    // Park the buffered bytes for the backend; selector will write them
    // when the connection is ready.
    backConn.outBufs.add(ByteBuffer.wrap(bytesSnap))
    backKey.interestOpsOr(SelectionKey.OP_WRITE)
    conn.inBuf.clear()
    // If the connect was synchronous (loopback usually is), finish it now
    // so the very next pass can write.
    if connected then back.finishConnect()
    else
      // Wait for OP_CONNECT in the loop.
      ()

  // ─── Helpers ──────────────────────────────────────────────────────

  private def closeChain(key: SelectionKey): Unit =
    val conn = key.attachment.asInstanceOf[Conn]
    conn.mode match
      case HttpClient(peer)  => closeKey(peer)
      case HttpBackend(peer) => closeKey(peer)
      case Ws(ws)            => ws.closeNow()
      case _                 => ()
    closeKey(key)

  private def closeKey(key: SelectionKey): Unit =
    if key.isValid then
      key.cancel()
      try key.channel.close() catch case _: Throwable => ()

  private def indexOfDoubleCrlf(b: Array[Byte]): Int =
    var i = 0
    while i + 3 < b.length do
      if b(i) == 13 && b(i + 1) == 10 && b(i + 2) == 13 && b(i + 3) == 10 then
        return i
      i += 1
    -1

  private def parseQueryString(q: String): Map[String, String] =
    if q.isEmpty then Map.empty
    else q.split('&').iterator.flatMap { pair =>
      val i = pair.indexOf('=')
      if i < 0 then Some(java.net.URLDecoder.decode(pair, "UTF-8") -> "")
      else Some(
        java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
        java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
      )
    }.toMap

  private def httpResponse(status: Int, reason: String, body: String): Array[Byte] =
    val bb = body.getBytes(StandardCharsets.UTF_8)
    val head =
      s"HTTP/1.1 $status $reason\r\n" +
      s"Content-Type: text/plain; charset=utf-8\r\n" +
      s"Content-Length: ${bb.length}\r\n" +
      s"Connection: close\r\n\r\n"
    head.getBytes(StandardCharsets.US_ASCII) ++ bb
