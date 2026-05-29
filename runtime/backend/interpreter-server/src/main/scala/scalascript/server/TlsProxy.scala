package scalascript.server

import java.net.Socket
import java.util.concurrent.Executor
import scalascript.interpreter.Value

/** Blocking per-connection proxy for the interpreter's TLS mode.
 *
 *  Mirrors JvmGen's `_proxyConnection`: reads the HTTP head, detects
 *  `Upgrade: websocket`, and either forwards plain HTTP to the internal
 *  loopback server or performs a WS upgrade using the existing
 *  `WsRoutes` registry + the shared `scalascript.server.jvm.WebSocket`
 *  class (same blocking-IO + per-VT class WsProxy uses for non-TLS).
 *
 *  Called from `WebServer.start` when `certPath` / `keyPath` are
 *  provided.  Each accepted SSLSocket lands on its own virtual thread
 *  so blocking reads never stall the accept loop. */
object TlsProxy:

  def handleConnection(
      client:       Socket,
      internalPort: Int,
      wsExecutor:   Executor,
      log:          java.io.PrintStream,
      wsRoutes:     WsRoutes
  ): Unit =
    try
      client.setKeepAlive(true)
      val cin  = java.io.BufferedInputStream(client.getInputStream)
      val cout = client.getOutputStream
      val head = HttpHelpers.readHttpHead(cin)
      if head.isEmpty then { client.close(); return }

      val parsed = HttpHelpers.parseHttpHead(head)
      if parsed.isUpgradeWebSocket then
        handleWsUpgrade(client, cout, parsed.path, parsed.rawQuery, parsed.headers, wsExecutor, log, wsRoutes)
      else
        forwardHttp(client, cin, cout, head, internalPort)
    catch case _: Throwable => try client.close() catch case _: Throwable => ()

  // ── WebSocket upgrade ──────────────────────────────────────────────

  private def handleWsUpgrade(
      client:     Socket,
      // `cin` argument dropped post-Option-B: the shared WebSocket
      // reads from `socket.getInputStream` directly, no need for an
      // already-buffered stream to be threaded through.
      cout:       java.io.OutputStream,
      path:       String,
      rawQuery:   String,
      headers:    Map[String, String],
      wsExecutor: Executor,
      log:        java.io.PrintStream,
      wsRoutes:   WsRoutes
  ): Unit =
    wsRoutes.matchPath(path) match
      case None =>
        cout.write(httpResp(404, "Not Found", s"No WebSocket route for $path"))
        cout.flush(); client.close()
        Metrics.wsRejected.incrementAndGet()

      case Some((entry, params)) =>
        val key = headers.getOrElse("sec-websocket-key", "")
        if key.isEmpty then { client.close(); return }

        if entry.origins.nonEmpty then
          val origin = headers.getOrElse("origin", "")
          if !entry.origins.contains(origin) then
            cout.write(httpResp(403, "Forbidden", s"Origin '$origin' not permitted"))
            cout.flush(); client.close()
            Metrics.wsRejected.incrementAndGet(); return

        // Pre-upgrade Request snapshot — built once in two forms.  The
        // POJO `req` is what the shared `scalascript.server.jvm.WebSocket`
        // ctor expects; the Value form goes to the interpreter handler
        // as `ws.request`.  Same headers / path / params / cookies on
        // both sides.
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
        var userPayload: Option[Value] = None
        var authRejected               = false
        entry.auth.foreach { fn =>
          try entry.interpreter.invoke(fn, List(request)) match
            case ov: Value.OptionV if ov.inner != null => userPayload = Some(ov.inner)
            case _: Value.OptionV                     => authRejected = true
            case other                                => userPayload = Some(other)
          catch case e: Throwable =>
            log.println(s"TLS WS auth hook error: ${e.getMessage}")
            authRejected = true
        }
        if authRejected then
          cout.write(httpResp(401, "Unauthorized", "WebSocket upgrade denied: authentication required"))
          cout.flush(); client.close()
          Metrics.wsRejected.incrementAndGet(); return

        if !WsConnection.tryReserveSlot() then
          cout.write(httpResp(503, "Service Unavailable", "WebSocket connection limit reached"))
          cout.flush(); client.close()
          Metrics.wsRejected.incrementAndGet(); return

        if !entry.tryReserve() then
          WsConnection.releaseSlot()
          cout.write(httpResp(503, "Service Unavailable", s"Route $path at capacity"))
          cout.flush(); client.close()
          Metrics.wsRejected.incrementAndGet(); return

        // Subprotocol negotiation (RFC 6455 §1.9) and 101 upgrade
        // wire shape delegated to the shared `WsHandshake` — same
        // path WsProxy / JvmGen take since Phase 2d.
        val chosenProtocol: String = WsHandshake.negotiateSubprotocol(
          headers.getOrElse("sec-websocket-protocol", ""), entry.protocols
        ) match
          case Some(p) => p
          case None =>
            WsConnection.releaseSlot(); entry.release()
            cout.write(httpResp(400, "Bad Request",
              s"No matching Sec-WebSocket-Protocol; server offers: ${entry.protocols.mkString(", ")}"))
            cout.flush(); client.close()
            Metrics.wsRejected.incrementAndGet(); return

        cout.write(WsHandshake.upgradeResponse(key, chosenProtocol))
        cout.flush()

        Metrics.wsUpgraded.incrementAndGet()

        // Build the shared `scalascript.server.jvm.WebSocket` directly —
        // same per-VT blocking-IO class WsProxy uses for non-TLS.  The
        // ctor spawns the writer VT; we pass the interpreter's
        // single-thread `wsExecutor` so user-callback dispatch stays
        // serial with the rest of the interpreter.  Heartbeat scheduler
        // + timing default to the codegen-side `_wsHeartbeats` / 30 s
        // ping / 90 s dead-after (matches WsProxy when no explicit
        // override is needed).
        val ws = _root_.scalascript.server.jvm.WebSocket(
          socket             = client,
          request            = req,
          subprotocol        = chosenProtocol,
          _onTerminate       = () => entry.release(),
          _maxMessagesPerSec = entry.maxMessagesPerSec,
          user               = userPayload,
          _executor          = wsExecutor,
          _log               = log
        )
        log.println(s"ws.connect\tid=${ws.id}\tip=${client.getRemoteSocketAddress}\troute=$path\tproto=$chosenProtocol")
        val wsValue = WsConnection.asValue(ws, entry.interpreter, log, request)
        wsExecutor.execute { () =>
          try entry.interpreter.invoke(entry.handler, List(wsValue))
          catch case e: Throwable =>
            log.println(s"TLS WS upgrade handler error: ${e.getMessage}")
        }
        ws._startHeartbeat()
        ws._runReadLoop()

  // ── Plain HTTP forwarding ──────────────────────────────────────────

  private def forwardHttp(
      client:       Socket,
      cin:          java.io.InputStream,
      cout:         java.io.OutputStream,
      head:         Array[Byte],
      internalPort: Int
  ): Unit =
    val back  = Socket("127.0.0.1", internalPort)
    val bin   = java.io.BufferedInputStream(back.getInputStream)
    val bout  = back.getOutputStream
    bout.write(head); bout.flush()
    def pump(in: java.io.InputStream, out: java.io.OutputStream,
             a: Socket, b: Socket): Unit =
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
    val t1 = Thread(() => pump(cin, bout, back, client), "tls-pump-c2b")
    val t2 = Thread(() => pump(bin, cout, client, back), "tls-pump-b2c")
    t1.start(); t2.start()
    t1.join(); t2.join()

  // ── Helpers ────────────────────────────────────────────────────────

  private def httpResp(status: Int, reason: String, body: String): Array[Byte] =
    val bb = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val head =
      s"HTTP/1.1 $status $reason\r\n" +
      s"Content-Type: text/plain; charset=utf-8\r\n" +
      s"Content-Length: ${bb.length}\r\nConnection: close\r\n\r\n"
    head.getBytes(java.nio.charset.StandardCharsets.US_ASCII) ++ bb
