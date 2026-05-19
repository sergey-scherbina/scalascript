package scalascript.server

import java.net.Socket
import java.util.concurrent.Executor
import scalascript.interpreter.Value

/** Blocking per-connection proxy for the interpreter's TLS mode.
 *
 *  Mirrors JvmGen's `_proxyConnection`: reads the HTTP head, detects
 *  `Upgrade: websocket`, and either forwards plain HTTP to the internal
 *  loopback server or performs a WS upgrade using the existing
 *  `WsRoutes` / `WsConnection` infrastructure.
 *
 *  Called from `WebServer.start` when `certPath` / `keyPath` are
 *  provided.  Each accepted SSLSocket lands on its own virtual thread
 *  so blocking reads never stall the accept loop. */
object TlsProxy:

  def handleConnection(
      client:       Socket,
      internalPort: Int,
      wsExecutor:   Executor,
      log:          java.io.PrintStream
  ): Unit =
    try
      client.setKeepAlive(true)
      val cin  = java.io.BufferedInputStream(client.getInputStream)
      val cout = client.getOutputStream
      val head = readHttpHead(cin)
      if head.isEmpty then { client.close(); return }

      val headText = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1)
      val lines    = headText.split("\r\n").toList
      val request  = lines.headOption.getOrElse("")
      val headers: Map[String, String] = lines.drop(1).flatMap { l =>
        val i = l.indexOf(':')
        if i < 0 then None
        else Some(l.substring(0, i).trim.toLowerCase -> l.substring(i + 1).trim)
      }.toMap

      val pathWithQuery = request.split(' ').lift(1).getOrElse("/")
      val path          = pathWithQuery.split('?').head
      val rawQuery      = if pathWithQuery.contains('?') then pathWithQuery.split('?').lift(1).getOrElse("") else ""
      val isWs = headers.get("upgrade").exists(_.equalsIgnoreCase("websocket")) &&
                 headers.get("connection").exists(_.toLowerCase.contains("upgrade"))

      if isWs then handleWsUpgrade(client, cin, cout, path, rawQuery, headers, wsExecutor, log)
      else forwardHttp(client, cin, cout, head, internalPort)
    catch case _: Throwable => try client.close() catch case _: Throwable => ()

  // ── WebSocket upgrade ──────────────────────────────────────────────

  private def handleWsUpgrade(
      client:     Socket,
      cin:        java.io.InputStream,
      cout:       java.io.OutputStream,
      path:       String,
      rawQuery:   String,
      headers:    Map[String, String],
      wsExecutor: Executor,
      log:        java.io.PrintStream
  ): Unit =
    WsRoutes.matchPath(path) match
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

        val q0 = parseQuery(rawQuery)
        val cookies0 = parseCookies(headers.getOrElse("cookie", ""))
        val authReq: Value = Value.InstanceV("Request", Map(
          "method"  -> Value.StringV("GET"),
          "path"    -> Value.StringV(path),
          "params"  -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "query"   -> Value.MapV(q0.map((k, v)  => Value.StringV(k) -> Value.StringV(v))),
          "headers" -> Value.MapV(headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "cookies" -> Value.MapV(cookies0.map((k, v) => Value.StringV(k) -> Value.StringV(v)))
        ))
        var userPayload: Option[Value] = None
        var authRejected               = false
        entry.auth.foreach { fn =>
          try entry.interpreter.invoke(fn, List(authReq)) match
            case Value.OptionV(Some(v)) => userPayload = Some(v)
            case Value.OptionV(None)    => authRejected = true
            case other                  => userPayload = Some(other)
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

        val chosenProtocol: String =
          if entry.protocols.isEmpty then ""
          else
            val offered = headers.getOrElse("sec-websocket-protocol", "")
              .split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet
            entry.protocols.find(offered.contains) match
              case Some(p) => p
              case None =>
                WsConnection.releaseSlot(); entry.release()
                cout.write(httpResp(400, "Bad Request",
                  s"No matching Sec-WebSocket-Protocol; server offers: ${entry.protocols.mkString(", ")}"))
                cout.flush(); client.close()
                Metrics.wsRejected.incrementAndGet(); return

        val accept   = WsFraming.acceptKey(key)
        val protoHdr = if chosenProtocol.isEmpty then "" else s"Sec-WebSocket-Protocol: $chosenProtocol\r\n"
        val respLine =
          "HTTP/1.1 101 Switching Protocols\r\n" +
          "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
          s"Sec-WebSocket-Accept: $accept\r\n" + protoHdr + "\r\n"
        cout.write(respLine.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        cout.flush()

        val query   = parseQuery(rawQuery)
        val cookies = parseCookies(headers.getOrElse("cookie", ""))
        val request = Value.InstanceV("Request", Map(
          "method"  -> Value.StringV("GET"),
          "path"    -> Value.StringV(path),
          "params"  -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "query"   -> Value.MapV(query.map((k, v)  => Value.StringV(k) -> Value.StringV(v))),
          "headers" -> Value.MapV(headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
          "cookies" -> Value.MapV(cookies.map((k, v) => Value.StringV(k) -> Value.StringV(v)))
        ))
        Metrics.wsUpgraded.incrementAndGet()

        // Build a NIO SocketChannel wrapping the existing Socket so we can
        // hand it to WsConnection, which expects a SelectionKey + Selector.
        // Since WsConnection uses NIO internally, we adapt via a SocketChannel
        // obtained from the socket (available on JDK 9+ via Socket.getChannel;
        // for SSLSocket there's no channel — use a thin adaptor).
        //
        // Fallback: wrap in a pair of virtual threads doing read + write,
        // bridged through an in-process ByteBuffer queue.  This avoids a
        // dependency on undocumented APIs.
        // For now, use the simpler blocking WsSession adaptor.
        val ws = BlockingWsSession(
          socket     = client,
          in         = cin,
          out        = cout,
          interpreter = entry.interpreter,
          request    = request,
          onTerminate = () => entry.release(),
          maxMessagesPerSec = entry.maxMessagesPerSec,
          user       = userPayload,
          subprotocol = chosenProtocol,
          log        = log
        )
        log.println(s"ws.connect\tid=${ws.id}\tip=${client.getRemoteSocketAddress}\troute=$path\tproto=$chosenProtocol")
        wsExecutor.execute { () =>
          try entry.interpreter.invoke(entry.handler, List(ws.asValue))
          catch case e: Throwable =>
            log.println(s"TLS WS upgrade handler error: ${e.getMessage}")
        }
        ws.runReadLoop()

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

  private def readHttpHead(in: java.io.InputStream): Array[Byte] =
    val sb   = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var prev3, prev2, prev1 = 0
    var done = false
    while !done do
      val b = in.read()
      if b < 0 then return sb.toArray
      sb += b.toByte
      if prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10 then done = true
      prev3 = prev2; prev2 = prev1; prev1 = b
    sb.toArray

  private def parseQuery(q: String): Map[String, String] =
    if q.isEmpty then Map.empty
    else q.split('&').iterator.flatMap { pair =>
      val i = pair.indexOf('=')
      if i < 0 then Some(java.net.URLDecoder.decode(pair, "UTF-8") -> "")
      else Some(
        java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
        java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
      )
    }.toMap

  private def parseCookies(raw: String): Map[String, String] =
    if raw.isEmpty then Map.empty
    else raw.split(';').iterator.flatMap { pair =>
      val t = pair.trim; val i = t.indexOf('=')
      if i < 0 then None else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
    }.toMap

  private def httpResp(status: Int, reason: String, body: String): Array[Byte] =
    val bb = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val head =
      s"HTTP/1.1 $status $reason\r\n" +
      s"Content-Type: text/plain; charset=utf-8\r\n" +
      s"Content-Length: ${bb.length}\r\nConnection: close\r\n\r\n"
    head.getBytes(java.nio.charset.StandardCharsets.US_ASCII) ++ bb
