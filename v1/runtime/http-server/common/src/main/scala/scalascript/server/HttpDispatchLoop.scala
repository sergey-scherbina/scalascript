package scalascript.server

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicLong

/** Per-request HTTP dispatch envelope, shared by the interpreter
 *  `WebServer.dispatchRoute` and the codegen `serveRuntime._handle`.
 *  Both backends parse the same POJO `Request` (via [[RequestBuilder]]),
 *  build the same `(req, () => Any) => Any` middleware chain, write
 *  the result through [[ResponseWriter]] / [[StreamResponseWriter]],
 *  and recover from the same `RestValidationError` / oversize-body /
 *  generic-exception paths.  Phases 2a-2d already extracted every
 *  protocol primitive the envelope leans on; this object pulls the
 *  glue between them into one place.
 *
 *  The shared loop runs entirely in POJOs.  The interpreter side
 *  adapts its `Value.Closure` handlers + middleware into uniform
 *  `Request => Any` / `(Request, () => Any) => Any` shapes before
 *  calling [[run]]; the codegen output passes its native Scala
 *  functions through unchanged. */
object HttpDispatchLoop:

  /** Backend-supplied dependencies that are stable for the lifetime
   *  of a server.  Built once per request by the caller — the field
   *  values read from per-server mutable settings (max body size,
   *  CORS origins, gzip flag, session-store callbacks, …) so the
   *  shared loop never reads global state directly. */
  final case class Config(
      reqBuilder:    RequestBuilder.Config,
      respWriter:    ResponseWriter.Config,
      fiveXxCounter: AtomicLong
  )

  /** Run one HTTP request through the shared envelope.  The caller
   *  has already (a) matched the route → handler + path params and
   *  (b) lifted any backend-specific middleware closures into the
   *  uniform `(Request, () => Any) => Any` shape.
   *
   *  Side effects this function owns:
   *    - 413 + immediate return on `RequestBuilder.BodyTooLargeError`
   *    - inner `RestValidationError` → 400 text response
   *    - outer generic-exception → `onError` callback + 5xx metric
   *    - cleanup of any multipart-spooled temp files in `finally`
   *
   *  Side effects the caller still owns (so this function stays
   *  exchange-shape agnostic): access-log line, `ex.close()`, and
   *  the per-request CORS-preflight short-circuit. */
  def run(
      ex:           HttpExchange,
      method:       String,
      path:         String,
      pathParams:   Map[String, String],
      handler:      Request => Any,
      middlewares:  Seq[(Request, () => RouteResult) => RouteResult],
      cfg:          Config,
      onError:      Throwable => Unit = _ => ()
  ): Unit =
    val parsed =
      try Some(RequestBuilder.parse(ex, method, path, pathParams, cfg.reqBuilder))
      catch case _: RequestBuilder.BodyTooLargeError =>
        val msg = "Request Entity Too Large".getBytes("UTF-8")
        ex.sendResponseHeaders(413, msg.length.toLong)
        ex.getResponseBody.write(msg)
        None
    parsed match
      case None => ()
      case Some((req, rawCookieSession, spooledTmps)) =>
        // Coerce ONCE, here, so the chain can be typed. The rule is the `case other` arm this
        // match used to end with — String.valueOf, 200, text/plain — and a Response built that way
        // then takes the `case resp: Response` arm below and writes byte-identically. That equality
        // is the whole safety argument for moving the conversion earlier; it was checked against
        // both arms, not assumed.
        def asResult(v: Any): RouteResult = v match
          case r: RouteResult => r
          case other =>
            Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), String.valueOf(other))
        def baseHandler(): RouteResult =
          try asResult(handler(req))
          catch case ve: RestValidationError =>
            Response(400,
              Map("Content-Type" -> "text/plain; charset=utf-8"),
              ve.getMessage)
        var chain: () => RouteResult = () => baseHandler()
        middlewares.reverseIterator.foreach { mw =>
          val inner = chain
          chain = () => mw(req, inner)
        }
        try
          chain() match
            case sr: StreamResponse =>
              StreamResponseWriter.write(ex, sr.status, sr.headers,
                cfg.respWriter,
                write => { sr.writer(write); () })
            case resp: Response =>
              ResponseWriter.write(ex, resp, rawCookieSession, cfg.respWriter)

        catch case e: Exception =>
          onError(e)
          cfg.fiveXxCounter.incrementAndGet()
        finally
          spooledTmps.foreach { f => try f.delete() catch case _: Throwable => () }
