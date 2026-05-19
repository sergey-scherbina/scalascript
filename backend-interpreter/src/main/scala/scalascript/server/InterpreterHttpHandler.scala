package scalascript.server

import scalascript.interpreter.{Interpreter, Value, Computation}
import scalascript.server.spi.*

/** SPI-shaped adapter that drives the interpreter's `Routes` /
 *  `WsRoutes` registries on behalf of an `HttpServerSpi` impl
 *  (e.g. [[scalascript.server.jvm.JdkServerBackend]]).
 *
 *  Above-SPI responsibilities:
 *    - HTTP: route lookup, middleware chain, `Value.Closure` invocation,
 *      `Value` ↔ POJO `Response` / `StreamResponse` conversion at the
 *      dispatch boundary.
 *    - WS  : route lookup, origin allowlist, pre-upgrade auth hook,
 *      per-route & process-wide slot reservation, subprotocol
 *      negotiation, then handing the SPI a `WsListener` that
 *      dispatches each frame to the user-supplied `onWebSocket { ws =>
 *      … }` body via `Interpreter.invoke` on the shared single-thread
 *      executor.
 *
 *  Pre-SPI, the same logic lived inline inside [[WsProxy]] and
 *  [[scalascript.server.WebServer.dispatchRoute]]; here it's pulled
 *  behind the SPI so the network layer (sockets, accept loop, upgrade
 *  handshake bytes) is impl-agnostic and the application layer
 *  (Values, route registry, middleware) is backend-agnostic. */
final class InterpreterHttpHandler(
    log:                java.io.PrintStream,
    /** Reused for HTTP handler bodies AND WS user callbacks so global
     *  state mutations stay serial across both protocols. */
    wsExecutor:         java.util.concurrent.Executor,
    /** Fallback `.ssc` / static-asset renderer.  Called when no route
     *  matches `req.path`.  Returns `None` to fall through to a 404. */
    fallbackRenderer:   Request => Option[Response],
    @scala.annotation.unused maxBodySizeBytes:   () => Long,
    @scala.annotation.unused spoolThreshold:     () => Long,
    @scala.annotation.unused uploadDir:          () => String,
    @scala.annotation.unused corsOrigins:        () => List[String],
    @scala.annotation.unused corsMethods:        () => List[String],
    @scala.annotation.unused corsHeaders:        () => List[String],
    @scala.annotation.unused gzipEnabled:        () => Boolean
) extends HttpHandler:

  // ── HTTP — same logic as the pre-SPI WebServer.dispatchRoute, but
  // shaped as POJO → POJO so the SPI impl writes the wire bytes. ──────

  override def onHttpRequest(req: Request): HttpResult =
    Routes.matchRequest(req.method, req.path) match
      case None =>
        fallbackRenderer(req) match
          case Some(resp) => HttpResult.PlainResp(resp)
          case None       => HttpResult.Reject(404, s"Not Found: ${req.path}")
      case Some((entry, params)) =>
        val request = liftRequest(req.copy(params = params))
        val interp  = entry.interpreter
        val baseHandler: () => Any =
          () =>
            try unwrap(interp.invoke(entry.handler, List(request)))
            catch case ve: RestValidationError =>
              Response(400,
                Map("Content-Type" -> "text/plain; charset=utf-8"),
                ve.getMessage)
        var chain: () => Any = baseHandler
        Routes.middlewares.reverseIterator.foreach { case (fn, mwInterp) =>
          val inner = chain
          val nextFn = Value.NativeFnV("next",
            Computation.pureFn(_ => reliftAnyToValue(inner())))
          val cur: () => Any = () =>
            unwrap(mwInterp.invoke(fn, List(request, nextFn)))
          chain = cur
        }
        try chain() match
          case sr: StreamResponse => HttpResult.StreamResp(sr)
          case r:  Response       => HttpResult.PlainResp(r)
          case other              =>
            HttpResult.PlainResp(Response(200,
              Map("Content-Type" -> "text/plain; charset=utf-8"),
              String.valueOf(other)))
        catch case e: Throwable =>
          log.println(s"Error: ${e.getMessage}")
          Metrics.http5xx.incrementAndGet()
          HttpResult.Reject(500, "Internal Server Error")

  // ── WS upgrade decision — origin / auth / slot / subprotocol — and
  // build a per-connection listener that dispatches to the registered
  // onWebSocket handler via the shared executor. ─────────────────────

  override def onWsUpgrade(req: Request): WsUpgradeResult =
    WsRoutes.matchPath(req.path) match
      case None =>
        WsUpgradeResult.Reject(404, "Not Found")
      case Some((entry, params)) =>
        val headers = req.headers
        // Origin allowlist (CSRF guard).
        if entry.origins.nonEmpty then
          val origin = headers.getOrElse("origin", "")
          if !entry.origins.contains(origin) then
            return WsUpgradeResult.Reject(403, "Forbidden")
        // Build a Value-shape request for the auth hook / handler.
        val requestPojo  = req.copy(params = params)
        val requestValue = liftWsRequest(requestPojo)
        // Pre-upgrade auth hook.
        var userPayload: Option[Value] = None
        var authRejected: Boolean      = false
        entry.auth.foreach { fn =>
          try entry.interpreter.invoke(fn, List(requestValue)) match
            case Value.OptionV(Some(v)) => userPayload = Some(v)
            case Value.OptionV(None)    => authRejected = true
            case other                  => userPayload = Some(other)
          catch case e: Throwable =>
            log.println(s"WS auth hook error: ${e.getMessage}")
            authRejected = true
        }
        if authRejected then
          return WsUpgradeResult.Reject(401, "Unauthorized")
        // Per-route cap (process-wide cap is reserved by the SPI impl).
        if !entry.tryReserve() then
          return WsUpgradeResult.Reject(503, "Service Unavailable")
        // Subprotocol negotiation.
        val chosenProtocol = WsHandshake.negotiateSubprotocol(
          headers.getOrElse("sec-websocket-protocol", ""), entry.protocols
        ) match
          case Some(p) => p
          case None    =>
            entry.release()
            return WsUpgradeResult.Reject(400, "Bad Request")

        // Build the listener.  Holds onto `controls` from onOpen so
        // user-side `ws.send` etc. has a target.  Dispatches user
        // callbacks via `wsExecutor` so handler bodies see serial
        // access to interpreter globals.
        val listener: WsListener = new InterpreterWsListener(
          entry        = entry,
          requestValue = requestValue,
          userPayload  = userPayload,
          executor     = wsExecutor,
          log          = log
        )
        WsUpgradeResult.Accept(chosenProtocol, listener)

  // ── Value conversion helpers — same shape as the pre-SPI dispatcher.

  private def liftRequest(r: Request): Value =
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV(r.method),
      "path"    -> Value.StringV(r.path),
      "params"  -> Value.MapV(r.params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "query"   -> Value.MapV(r.query.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "headers" -> Value.MapV(r.headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "body"    -> Value.StringV(r.body),
      "json"    -> Value.OptionV(
        if r.body.isEmpty then None
        else scalascript.interpreter.JsonParser.parseOption(r.body)
      ),
      "form"    -> Value.MapV(r.form.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "files"   -> Value.MapV(r.files.map { case (k, f) =>
        Value.StringV(k) -> Value.InstanceV("UploadedFile", Map(
          "name"        -> Value.StringV(f.name),
          "filename"    -> Value.StringV(f.filename),
          "contentType" -> Value.StringV(f.contentType),
          "size"        -> Value.IntV(f.size),
          "bytes"       -> Value.StringV(f.bytes),
          "path"        -> Value.StringV(f.path)
        ))
      }),
      "session" -> Value.MapV(r.session.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "cookies" -> Value.MapV(r.cookies.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "bearerToken" -> r.bearerToken.map(t => Value.OptionV(Some(Value.StringV(t))))
        .getOrElse(Value.OptionV(None)),
      "jwtClaims"   -> r.jwtClaims.map(c =>
          Value.OptionV(Some(Value.MapV(c.map((k, v) => Value.StringV(k) -> Value.StringV(v))))))
        .getOrElse(Value.OptionV(None)),
      "basicAuth"   -> r.basicAuth.map((u, p) =>
          Value.OptionV(Some(Value.TupleV(List(Value.StringV(u), Value.StringV(p))))))
        .getOrElse(Value.OptionV(None))
    ))

  private def liftWsRequest(r: Request): Value =
    // WS upgrade Request — same shape pre-SPI WsProxy used for the
    // auth hook / `ws.request`.  No body / form / files / session.
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("GET"),
      "path"    -> Value.StringV(r.path),
      "params"  -> Value.MapV(r.params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "query"   -> Value.MapV(r.query.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "headers" -> Value.MapV(r.headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "cookies" -> Value.MapV(r.cookies.map((k, v) => Value.StringV(k) -> Value.StringV(v)))
    ))

  private def unwrap(v: Value): Any = v match
    case Value.InstanceV("StreamResponse", fields) =>
      val status = fields.get("status") match
        case Some(Value.IntV(n)) => n.toInt
        case _                   => 200
      val hdrs = fields.get("headers") match
        case Some(Value.MapV(m)) =>
          m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
        case _ => Map.empty[String, String]
      val callback = fields.getOrElse("callback",
        throw new RuntimeException("StreamResponse missing callback"))
      val interp = inferStreamInterpreter()
      StreamResponse(status, hdrs, { write =>
        val writeNative = Value.NativeFnV("streamWrite",
          Computation.pureFn { args =>
            val chunk = args match
              case List(Value.StringV(s)) => s
              case List(other)            => Value.show(other)
              case _                      => ""
            write(chunk)
            Value.UnitV
          })
        interp.invoke(callback, List(writeNative))
      })
    case Value.InstanceV("Response", fields) =>
      val s = fields.get("status") match
        case Some(Value.IntV(n)) => n.toInt
        case _                   => 200
      val h = fields.get("headers") match
        case Some(Value.MapV(m)) =>
          m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
        case _ => Map.empty[String, String]
      val b = fields.get("body") match
        case Some(Value.StringV(s)) => s
        case Some(other)            => Value.show(other)
        case None                   => ""
      val ss = fields.get("setSession") match
        case Some(Value.MapV(m)) =>
          Some(m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap)
        case _ => None
      Response(s, h, b, ss)
    case Value.StringV(s) =>
      Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), s)
    case Value.UnitV =>
      Response(204, Map.empty, "")
    case other =>
      Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), Value.show(other))

  private def reliftAnyToValue(any: Any): Value = any match
    case sr: StreamResponse =>
      Value.InstanceV("StreamResponse", Map(
        "status"  -> Value.IntV(sr.status),
        "headers" -> Value.MapV(sr.headers.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
    case r: Response =>
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(r.status),
        "headers" -> Value.MapV(r.headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
        "body"    -> Value.StringV(r.body)))
    case other => Value.StringV(String.valueOf(other))

  /** When the user's handler returns a `StreamResponse`, the writer
   *  callback needs an interpreter to invoke through.  In the
   *  pre-SPI loop we captured `entry.interpreter` at the call site;
   *  here we don't (Routes.matchRequest is what we use, but by the
   *  time the StreamResponse unwrap happens we've lost the entry).
   *  Pick any registered interpreter — they all share global state
   *  in the test rig, and production use registers exactly one. */
  private def inferStreamInterpreter(): Interpreter =
    Routes.all.headOption.map(_.interpreter).getOrElse(
      throw new RuntimeException("No registered route — cannot dispatch StreamResponse"))

/** Per-connection listener — bridges the SPI's frame-callback shape
 *  back into the interpreter's `Value.Closure` handler model.  The
 *  user's `onWebSocket { ws => … }` body runs once on `onOpen`; later
 *  callbacks (`ws.onMessage`, `ws.onClose`, `ws.onPong`) are also
 *  driven via `Interpreter.invoke` on the shared executor.
 *
 *  Slot management: the SPI impl owns the process-wide slot (reserved
 *  at upgrade time, released in the shared WebSocket's writer-VT
 *  `finally`).  The per-route slot was reserved by
 *  `InterpreterHttpHandler.onWsUpgrade` and is released here when the
 *  connection closes. */
private final class InterpreterWsListener(
    entry:        WsRoutes.Entry,
    requestValue: Value,
    userPayload:  Option[Value],
    executor:     java.util.concurrent.Executor,
    log:          java.io.PrintStream
) extends WsListener:
  @volatile private var _wsValue:  Value             = Value.UnitV
  @volatile private var _onMessageCb: Value | Null   = null
  @volatile private var _onCloseCb:   Value | Null   = null
  @volatile private var _onPongCb:    Value | Null   = null

  override def onOpen(controls: WsControls): Unit =
    _wsValue  = buildWsValue(controls)
    executor.execute { () =>
      try entry.interpreter.invoke(entry.handler, List(_wsValue))
      catch case e: Throwable =>
        log.println(s"WS upgrade handler error: ${e.getMessage}")
    }

  override def onMessage(text: String): Unit =
    val cb = _onMessageCb
    if cb != null then
      executor.execute { () =>
        try entry.interpreter.invoke(cb, List(Value.StringV(text)))
        catch case e: Throwable =>
          log.println(s"WS handler error: ${e.getMessage}")
      }

  override def onBinary(bytes: Array[Byte]): Unit =
    // Pre-SPI WsConnection.asValue routes binary frames through
    // onMessage with a Latin-1 byte-view encoding; preserve that.
    onMessage(new String(bytes, "ISO-8859-1"))

  override def onPong(payload: Array[Byte]): Unit =
    val cb = _onPongCb
    if cb != null then
      val s = new String(payload, "ISO-8859-1")
      executor.execute { () =>
        try entry.interpreter.invoke(cb, List(Value.StringV(s)))
        catch case e: Throwable =>
          log.println(s"WS onPong handler error: ${e.getMessage}")
      }

  override def onClose(code: Int, reason: String): Unit =
    val cb = _onCloseCb
    entry.release()
    if cb != null then
      executor.execute { () =>
        try entry.interpreter.invoke(cb, Nil)
        catch case e: Throwable =>
          log.println(s"WS close handler error: ${e.getMessage}")
      }

  override def onError(t: Throwable): Unit =
    log.println(s"WS error: ${t.getMessage}")

  /** Construct the `Value.InstanceV("WebSocket", …)` the user-side
   *  `onWebSocket { ws => … }` body works against.  Same field shape
   *  as the pre-SPI `WsConnection.asValue`. */
  private def buildWsValue(controls: WsControls): Value =
    val send = Value.NativeFnV("WebSocket.send", Computation.pureFn {
      case List(Value.StringV(s)) => controls.send(s); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.send(text)")
    })
    val sendBytes = Value.NativeFnV("WebSocket.sendBytes", Computation.pureFn {
      case List(Value.StringV(s)) =>
        controls.sendBytes(s.getBytes("ISO-8859-1")); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.sendBytes(bytes)")
    })
    val closeFn = Value.NativeFnV("WebSocket.close", Computation.pureFn {
      case Nil =>
        controls.close(); Value.UnitV
      case List(Value.IntV(code)) =>
        controls.close(code.toInt, ""); Value.UnitV
      case List(Value.IntV(code), Value.StringV(reason)) =>
        controls.close(code.toInt, reason); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError(
        "ws.close() or ws.close(code) or ws.close(code, reason)")
    })
    val onMessage = Value.NativeFnV("WebSocket.onMessage", Computation.pureFn {
      case List(cb) => _onMessageCb = cb; Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.onMessage { msg => … }")
    })
    val onClose = Value.NativeFnV("WebSocket.onClose", Computation.pureFn {
      case List(cb) => _onCloseCb = cb; Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.onClose { () => … }")
    })
    val ping = Value.NativeFnV("WebSocket.ping", Computation.pureFn {
      case Nil =>
        controls.ping(Array.emptyByteArray); Value.UnitV
      case List(Value.StringV(s)) =>
        controls.ping(s.getBytes("ISO-8859-1")); Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.ping() or ws.ping(payload)")
    })
    val onPong = Value.NativeFnV("WebSocket.onPong", Computation.pureFn {
      case List(cb) => _onPongCb = cb; Value.UnitV
      case _ => throw scalascript.interpreter.InterpretError("ws.onPong { payload => … }")
    })
    val isClosed = Value.NativeFnV("WebSocket.isClosed", Computation.pureFn {
      case Nil => Value.BoolV(controls.isClosed)
      case _   => throw scalascript.interpreter.InterpretError("ws.isClosed")
    })
    val userValue: Value = userPayload match
      case Some(v) => Value.OptionV(Some(v))
      case None    => Value.OptionV(None)
    Value.InstanceV("WebSocket", Map(
      "send"        -> send,
      "sendBytes"   -> sendBytes,
      "close"       -> closeFn,
      "ping"        -> ping,
      "onMessage"   -> onMessage,
      "onClose"     -> onClose,
      "onPong"      -> onPong,
      "isClosed"    -> isClosed,
      "request"     -> requestValue,
      "id"          -> Value.StringV(controls.id),
      "subprotocol" -> Value.StringV(controls.subprotocol),
      "user"        -> userValue
    ))
