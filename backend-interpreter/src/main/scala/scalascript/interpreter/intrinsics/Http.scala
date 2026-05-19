package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** HTTP server + client intrinsics for the tree-walking interpreter.
 *
 *  All operations previously registered via hardcoded `nativeP` calls in
 *  `Interpreter.initBuiltins` now flow through the shared `IntrinsicImpl`
 *  pipeline (Stage 5+/B).  `NativeContext` carries the hooks that
 *  `Interpreter.installNativeIntrinsics` overrides with live interpreter
 *  state so these implementations stay free of direct Interpreter references. */
val HttpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // ── HTTP server: route / tls / serve / stop ────────────────────────

  QualifiedName("route") -> NativeImpl((ctx, args) =>
    args match
      case List(method: String, path: String) =>
        Value.NativeFnV("route.handler", Computation.pureFn {
          case List(handler) =>
            ctx.registerRoute(method, path, handler)
            Value.UnitV
          case _ => throw InterpretError("route(method, path) { handler }")
        })
      case _ => throw InterpretError("route(method, path) { handler }")
  ),

  QualifiedName("tls") -> NativeImpl((_, args) =>
    args match
      case List(cert: String, key: String) =>
        Value.InstanceV("TlsContext", Map("cert" -> Value.StringV(cert), "key" -> Value.StringV(key)))
      case _ => throw InterpretError("tls(certPath, keyPath)")
  ),

  QualifiedName("serve") -> NativeImpl((ctx, args) =>
    args match
      case List(port: Long) =>
        ctx.registerHealthDefaults()
        if !ctx.headless then scalascript.server.WebServer.start(port.toInt, ".", ctx.out)
        ()
      case List(port: Long, dir: String) =>
        ctx.registerHealthDefaults()
        if !ctx.headless then scalascript.server.WebServer.start(port.toInt, dir, ctx.out)
        ()
      case List(port: Long, Value.InstanceV("TlsContext", tlsFields)) =>
        ctx.registerHealthDefaults()
        val cert = tlsFields.get("cert").collect { case Value.StringV(s) => s }.getOrElse("")
        val key  = tlsFields.get("key").collect  { case Value.StringV(s) => s }.getOrElse("")
        ctx.startTlsServer(port.toInt, ".", cert, key)
        ()
      case _ => throw InterpretError("serve(port), serve(port, dir), or serve(port, tls(cert, key))")
  ),

  QualifiedName("stop") -> NativeImpl((ctx, _) =>
    if !ctx.headless then scalascript.server.WebServer.stop()
  ),

  // ── Outbound HTTP client ───────────────────────────────────────────

  QualifiedName("httpGet") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String)        => doHttpRequest("GET", url, "", Map.empty, ctx)
      case List(url: String, hdrs)  => doHttpRequest("GET", url, "", headersArg(hdrs), ctx)
      case _ => throw InterpretError("httpGet(url[, headers])")
  ),

  QualifiedName("httpPost") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String, body: String)       => doHttpRequest("POST", url, body, Map.empty, ctx)
      case List(url: String, body: String, hdrs) => doHttpRequest("POST", url, body, headersArg(hdrs), ctx)
      case _ => throw InterpretError("httpPost(url, body[, headers])")
  ),

  QualifiedName("httpPut") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String, body: String)       => doHttpRequest("PUT", url, body, Map.empty, ctx)
      case List(url: String, body: String, hdrs) => doHttpRequest("PUT", url, body, headersArg(hdrs), ctx)
      case _ => throw InterpretError("httpPut(url, body[, headers])")
  ),

  QualifiedName("httpPatch") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String, body: String)       => doHttpRequest("PATCH", url, body, Map.empty, ctx)
      case List(url: String, body: String, hdrs) => doHttpRequest("PATCH", url, body, headersArg(hdrs), ctx)
      case _ => throw InterpretError("httpPatch(url, body[, headers])")
  ),

  QualifiedName("httpDelete") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String)       => doHttpRequest("DELETE", url, "", Map.empty, ctx)
      case List(url: String, hdrs) => doHttpRequest("DELETE", url, "", headersArg(hdrs), ctx)
      case _ => throw InterpretError("httpDelete(url[, headers])")
  ),

  // Streaming variants: httpGetStream(url[, headers])(handler)
  QualifiedName("httpGetStream") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String) =>
        Value.NativeFnV("httpGetStream.handler", Computation.pureFn {
          case List(handler) => doHttpRequestStream("GET", url, "", Map.empty, handler, ctx)
          case _ => throw InterpretError("httpGetStream(url)(handler)")
        })
      case List(url: String, hdrs) =>
        val h = headersArg(hdrs)
        Value.NativeFnV("httpGetStream.handler", Computation.pureFn {
          case List(handler) => doHttpRequestStream("GET", url, "", h, handler, ctx)
          case _ => throw InterpretError("httpGetStream(url, headers)(handler)")
        })
      case _ => throw InterpretError("httpGetStream(url[, headers])(handler)")
  ),

  QualifiedName("httpPostStream") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String, body: String) =>
        Value.NativeFnV("httpPostStream.handler", Computation.pureFn {
          case List(handler) => doHttpRequestStream("POST", url, body, Map.empty, handler, ctx)
          case _ => throw InterpretError("httpPostStream(url, body)(handler)")
        })
      case List(url: String, body: String, hdrs) =>
        val h = headersArg(hdrs)
        Value.NativeFnV("httpPostStream.handler", Computation.pureFn {
          case List(handler) => doHttpRequestStream("POST", url, body, h, handler, ctx)
          case _ => throw InterpretError("httpPostStream(url, body, headers)(handler)")
        })
      case _ => throw InterpretError("httpPostStream(url, body[, headers])(handler)")
  ),

  // httpClient(baseUrl) { block } stays as Term.Apply special form in eval.

  // ── WebSocket client ───────────────────────────────────────────────

  // wsConnect(url[, headers[, protocols]]) { ws => … }
  // Curried; blocks on the calling thread until the server closes.
  QualifiedName("wsConnect") -> NativeImpl((ctx, args) =>
    args match
      case List(url: String) =>
        Value.NativeFnV("wsConnect.handler", Computation.pureFn {
          case List(handler) => ctx.wsConnectSync(url, Map.empty, Nil, handler); Value.UnitV
          case _ => throw InterpretError("wsConnect(url) { ws => … }")
        })
      case List(url: String, hdrs) =>
        val headers = headersArg(hdrs)
        Value.NativeFnV("wsConnect.handler", Computation.pureFn {
          case List(handler) => ctx.wsConnectSync(url, headers, Nil, handler); Value.UnitV
          case _ => throw InterpretError("wsConnect(url, headers) { ws => … }")
        })
      case List(url: String, hdrs, Value.ListV(prots)) =>
        val headers   = headersArg(hdrs)
        val protocols = prots.collect { case Value.StringV(s) => s }
        Value.NativeFnV("wsConnect.handler", Computation.pureFn {
          case List(handler) => ctx.wsConnectSync(url, headers, protocols, handler); Value.UnitV
          case _ => throw InterpretError("wsConnect(url, headers, protocols) { ws => … }")
        })
      case _ => throw InterpretError("wsConnect(url[, headers[, protocols]]) { ws => … }")
  ),

  // ── CORS / gzip / cache ────────────────────────────────────────────

  QualifiedName("cors") -> NativeImpl((ctx, args) =>
    args match
      case List(Value.ListV(origins)) =>
        ctx.configureCors(
          origins.collect { case Value.StringV(s) => s },
          List("GET","POST","PUT","DELETE","OPTIONS","PATCH"), Nil)
        ()
      case List(Value.ListV(origins), Value.ListV(methods)) =>
        ctx.configureCors(
          origins.collect { case Value.StringV(s) => s },
          methods.collect { case Value.StringV(s) => s }, Nil)
        ()
      case List(Value.ListV(origins), Value.ListV(methods), Value.ListV(hdrs)) =>
        ctx.configureCors(
          origins.collect { case Value.StringV(s) => s },
          methods.collect { case Value.StringV(s) => s },
          hdrs.collect { case Value.StringV(s) => s })
        ()
      case _ => throw InterpretError("cors(origins[, methods[, headers]])")
  ),

  QualifiedName("useGzip") -> NativeImpl((ctx, args) =>
    args match
      case Nil => ctx.enableGzip(); ()
      case _   => throw InterpretError("useGzip()")
  ),

  QualifiedName("cacheable") -> NativeImpl((_, args) =>
    args match
      case List(resp, n: Long) =>
        addCacheHdrs(resp, Map("Cache-Control" -> s"public, max-age=$n"))
      case List(resp, n: Long, etag: String) =>
        addCacheHdrs(resp, Map("Cache-Control" -> s"public, max-age=$n", "ETag" -> etag))
      case _ => throw InterpretError("cacheable(response, maxAge[, etag])")
  ),

  QualifiedName("noCache") -> NativeImpl((_, args) =>
    args match
      case List(resp) =>
        addCacheHdrs(resp, Map("Cache-Control" -> "no-store, no-cache, must-revalidate"))
      case _ => throw InterpretError("noCache(response)")
  ),

  // ── Streaming / SSE ────────────────────────────────────────────────

  // streamResponse { write => ... } — chunked streaming from a route handler.
  // Returns a StreamResponse sentinel detected by WebServer.dispatchRoute.
  QualifiedName("streamResponse") -> NativeImpl((_, args) =>
    args match
      case List(n: Long) =>
        Value.NativeFnV("streamResponse.block", Computation.pureFn {
          case List(block) => Value.InstanceV("StreamResponse", Map(
            "status" -> Value.IntV(n), "headers" -> Value.MapV(Map.empty), "callback" -> block))
          case _ => throw InterpretError("streamResponse(status)(block)")
        })
      case List(n: Long, Value.MapV(hdrs)) =>
        Value.NativeFnV("streamResponse.block", Computation.pureFn {
          case List(block) => Value.InstanceV("StreamResponse", Map(
            "status" -> Value.IntV(n), "headers" -> Value.MapV(hdrs), "callback" -> block))
          case _ => throw InterpretError("streamResponse(status, headers)(block)")
        })
      case List(block) =>
        Value.InstanceV("StreamResponse", Map(
          "status" -> Value.IntV(200), "headers" -> Value.MapV(Map.empty), "callback" -> block.asInstanceOf[Value]))
      case _ => throw InterpretError("streamResponse(block)")
  ),

  // sse(req) { stream => stream.send(data) / stream.send(event, data) / stream.close() }
  QualifiedName("sse") -> NativeImpl((ctx, args) =>
    args match
      case List(_) =>
        val sseHeaders = Map(
          "Content-Type"      -> "text/event-stream",
          "Cache-Control"     -> "no-cache",
          "Connection"        -> "keep-alive",
          "X-Accel-Buffering" -> "no"
        )
        val headerMap = Value.MapV(sseHeaders.map((k, v) =>
          (Value.StringV(k): Value) -> (Value.StringV(v): Value)))
        Value.NativeFnV("sse.block", Computation.pureFn {
          case List(block) =>
            val callback = Value.NativeFnV("sse.writer", Computation.pureFn {
              case List(writeFn) =>
                val sseStream = Value.InstanceV("SseStream", Map(
                  "send" -> Value.NativeFnV("SseStream.send", Computation.pureFn {
                    case List(Value.StringV(data)) =>
                      ctx.invokeCallback(writeFn, List(Value.StringV(s"data: $data\n\n")))
                      Value.UnitV
                    case List(Value.StringV(event), Value.StringV(data)) =>
                      ctx.invokeCallback(writeFn, List(Value.StringV(s"event: $event\ndata: $data\n\n")))
                      Value.UnitV
                    case _ => throw InterpretError("SseStream.send(data) or send(event, data)")
                  }),
                  "close" -> Value.NativeFnV("SseStream.close", Computation.pureFn(_ => Value.UnitV))
                ))
                ctx.invokeCallback(block, List(sseStream))
                Value.UnitV
              case _ => throw InterpretError("sse internal writer error")
            })
            Value.InstanceV("StreamResponse", Map(
              "status"   -> Value.IntV(200),
              "headers"  -> headerMap,
              "callback" -> callback
            ))
          case _ => throw InterpretError("sse(req)(block)")
        })
      case _ => throw InterpretError("sse(req)(block)")
  ),

  // ── Body / upload limits ───────────────────────────────────────────

  QualifiedName("maxBodySize") -> NativeImpl((ctx, args) =>
    args match
      case List(n: Long) => ctx.setMaxBodySize(n); ()
      case _ => throw InterpretError("maxBodySize(bytes: Int)")
  ),

  QualifiedName("uploadSpoolThreshold") -> NativeImpl((ctx, args) =>
    args match
      case List(n: Long) => ctx.setSpoolThreshold(n); ()
      case _ => throw InterpretError("uploadSpoolThreshold(bytes: Int)")
  ),

  QualifiedName("uploadDir") -> NativeImpl((ctx, args) =>
    args match
      case List(dir: String) => ctx.setUploadDir(dir); ()
      case _ => throw InterpretError("uploadDir(path: String)")
  ),

  // ── Middleware ─────────────────────────────────────────────────────

  QualifiedName("use") -> NativeImpl((ctx, args) =>
    args match
      case List(fn) => ctx.registerMiddleware(fn); ()
      case _ => throw InterpretError("use(fn: (Request, () => Response) => Response)")
  ),

  // ── HTTP client config (scoped by httpClient{} block) ─────────────

  QualifiedName("httpTimeout") -> NativeImpl((ctx, args) =>
    args match
      case List(ms: Long) => ctx.setHttpTimeout(ms); ()
      case _ => throw InterpretError("httpTimeout(ms: Int)")
  ),

  QualifiedName("httpRetry") -> NativeImpl((ctx, args) =>
    args match
      case List(n: Long)         => ctx.setHttpRetry(n.toInt, ctx.httpRetryDelayMs); ()
      case List(n: Long, d: Long) => ctx.setHttpRetry(n.toInt, d); ()
      case _ => throw InterpretError("httpRetry(maxAttempts[, delayMs])")
  ),

  // ── WebSocket server ───────────────────────────────────────────────

  // onWebSocket(path[, origins[, protocols[, maxConnections[, maxMessagesPerSec]]]]) { ws => … }
  // Two-arg form restricts upgrades to the given Origin list (browser CSRF guard).
  QualifiedName("onWebSocket") -> NativeImpl((ctx, args) =>
    args match
      case List(path: String) =>
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) => ctx.registerWsRoute(path, Nil, Nil, 0, 0, handler); Value.UnitV
          case _ => throw InterpretError("onWebSocket(path) { ws => … }")
        })
      case List(path: String, Value.ListV(origins)) =>
        val origs = origins.collect { case Value.StringV(s) => s }
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) => ctx.registerWsRoute(path, origs, Nil, 0, 0, handler); Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins) { ws => … }")
        })
      case List(path: String, Value.ListV(origins), Value.ListV(protocols)) =>
        val origs  = origins.collect   { case Value.StringV(s) => s }
        val protos = protocols.collect { case Value.StringV(s) => s }
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) => ctx.registerWsRoute(path, origs, protos, 0, 0, handler); Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins, protocols) { ws => … }")
        })
      case List(path: String, Value.ListV(origins), Value.ListV(protocols), maxConn: Long) =>
        val origs  = origins.collect   { case Value.StringV(s) => s }
        val protos = protocols.collect { case Value.StringV(s) => s }
        val cap    = if maxConn > Int.MaxValue.toLong || maxConn < 0 then 0 else maxConn.toInt
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) => ctx.registerWsRoute(path, origs, protos, cap, 0, handler); Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins, protocols, maxConnections) { ws => … }")
        })
      case List(path: String, Value.ListV(origins), Value.ListV(protocols), maxConn: Long, maxRate: Long) =>
        val origs  = origins.collect   { case Value.StringV(s) => s }
        val protos = protocols.collect { case Value.StringV(s) => s }
        val cap    = if maxConn > Int.MaxValue.toLong || maxConn < 0 then 0 else maxConn.toInt
        val rate   = if maxRate > Int.MaxValue.toLong || maxRate < 0 then 0 else maxRate.toInt
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) => ctx.registerWsRoute(path, origs, protos, cap, rate, handler); Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins, protocols, maxConnections, maxMessagesPerSec) { ws => … }")
        })
      case _ => throw InterpretError("onWebSocket(path[, origins[, protocols[, maxConnections[, maxMessagesPerSec]]]]) { ws => … }")
  ),

  // onWebSocketAuth(path, authFn)(handler) — pre-upgrade auth hook.
  // authFn receives the Request, returns Option[Any]: None → 401, Some(user) → accepted.
  QualifiedName("onWebSocketAuth") -> NativeImpl((ctx, args) =>
    args match
      case List(path: String, authFn) =>
        Value.NativeFnV("onWebSocketAuth.handler", Computation.pureFn {
          case List(handler) => ctx.registerWsAuthRoute(path, authFn, handler); Value.UnitV
          case _ => throw InterpretError("onWebSocketAuth(path, authFn) { ws => … }")
        })
      case _ => throw InterpretError("onWebSocketAuth(path, authFn) { ws => … }")
  ),

  // ── Response builders (Stage 5+/E) ────────────────────────────────────

  QualifiedName("Response.html") -> NativeImpl((_, args) =>
    args match
      case List(v) =>
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV("text/html; charset=utf-8"))),
          "body"    -> Value.StringV(httpBodyOf(v))
        ))
      case _ => throw InterpretError("Response.html(body)")
  ),

  QualifiedName("Response.text") -> NativeImpl((_, args) =>
    args match
      case List(v) =>
        httpMkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("text/plain; charset=utf-8")), httpBodyOf(v))
      case _ => throw InterpretError("Response.text(body)")
  ),

  QualifiedName("Response.json") -> NativeImpl((_, args) =>
    args match
      case List(s: String) =>
        httpMkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("application/json")), s)
      case List(v) =>
        httpMkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("application/json")), jsonToJson(httpAnyToValue(v)))
      case _ => throw InterpretError("Response.json(body)")
  ),

  QualifiedName("Response.redirect") -> NativeImpl((_, args) =>
    args match
      case List(loc: String) => httpMkResponse(302, Map(Value.StringV("Location") -> Value.StringV(loc)), "")
      case _ => throw InterpretError("Response.redirect(url)")
  ),

  QualifiedName("Response.notFound") -> NativeImpl((_, args) =>
    args match
      case Nil     => httpMkResponse(404, body = "Not Found")
      case List(v) => httpMkResponse(404, body = httpBodyOf(v))
      case _       => throw InterpretError("Response.notFound([body])")
  ),

  QualifiedName("Response.status") -> NativeImpl((_, args) =>
    args match
      case List(s: Long)    => httpMkResponse(s.toInt)
      case List(s: Long, v) => httpMkResponse(s.toInt, body = httpBodyOf(v))
      case _ => throw InterpretError("Response.status(code[, body])")
  ),

)

// ── Private helpers ────────────────────────────────────────────────────

private def headersArg(v: Any): Map[String, String] = v match
  case Value.MapV(m) => m.collect {
    case (Value.StringV(k), Value.StringV(vv)) => k -> vv
  }.toMap
  case _ => Map.empty

private def addCacheHdrs(v: Any, extra: Map[String, String]): Value = v match
  case Value.InstanceV("Response", fields) =>
    val h = fields.get("headers") match
      case Some(Value.MapV(m)) => m
      case _                   => Map.empty[Value, Value]
    val merged = h ++ extra.map { (k, vv) => (Value.StringV(k): Value) -> (Value.StringV(vv): Value) }
    Value.InstanceV("Response", fields + ("headers" -> Value.MapV(merged)))
  case other: Value => other
  case _            => Value.UnitV

private def doHttpRequest(
    method:  String,
    rawUrl:  String,
    body:    String,
    headers: Map[String, String],
    ctx:     NativeContext
): Value =
  import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
  import scala.jdk.CollectionConverters.*
  val base    = ctx.httpBaseUrl
  val url     = if base.nonEmpty && !rawUrl.startsWith("http") then base + rawUrl else rawUrl
  val timeout = java.time.Duration.ofMillis(ctx.httpTimeoutMs)
  val client  = JHttpClient.newBuilder().connectTimeout(timeout).build()
  val builder = HttpRequest.newBuilder().uri(java.net.URI.create(url)).timeout(timeout)
  headers.foreach((k, v) => builder.header(k, v))
  val req = method match
    case "GET"    => builder.GET().build()
    case "POST"   => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
    case "PUT"    => builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
    case "DELETE" => builder.DELETE().build()
    case m        => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
  val maxTries = ctx.httpMaxRetries + 1
  val delayMs  = ctx.httpRetryDelayMs
  var attempt  = 0
  var lastResp: HttpResponse[String] | Null = null
  var lastErr:  Throwable | Null = null
  while attempt < maxTries do
    try { lastResp = client.send(req, HttpResponse.BodyHandlers.ofString()); lastErr = null }
    catch case e: Throwable => lastErr = e
    val shouldRetry = lastErr != null || (lastResp != null && lastResp.statusCode() >= 500)
    attempt += 1
    if shouldRetry && attempt < maxTries then Thread.sleep(delayMs)
    else attempt = maxTries
  if lastErr != null then throw lastErr
  val resp = lastResp.nn
  val hdrs: Map[Value, Value] = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
    if e.getValue.isEmpty then None
    else Some((Value.StringV(e.getKey): Value) -> (Value.StringV(e.getValue.get(0)): Value))
  }.toMap
  Value.InstanceV("Response", Map(
    "status"  -> Value.IntV(resp.statusCode().toLong),
    "body"    -> Value.StringV(resp.body()),
    "headers" -> Value.MapV(hdrs)
  ))

private def doHttpRequestStream(
    method:  String,
    rawUrl:  String,
    body:    String,
    headers: Map[String, String],
    handler: Value,
    ctx:     NativeContext
): Value =
  import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
  import scala.jdk.CollectionConverters.*
  val base    = ctx.httpBaseUrl
  val url     = if base.nonEmpty && !rawUrl.startsWith("http") then base + rawUrl else rawUrl
  val timeout = java.time.Duration.ofMillis(ctx.httpTimeoutMs)
  val client  = JHttpClient.newBuilder().connectTimeout(timeout).build()
  val builder = HttpRequest.newBuilder().uri(java.net.URI.create(url)).timeout(timeout)
  headers.foreach((k, v) => builder.header(k, v))
  val req = method match
    case "GET"  => builder.GET().build()
    case "POST" => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
    case m      => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
  val resp = client.send(req, HttpResponse.BodyHandlers.ofLines())
  val hdrs: Map[Value, Value] = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
    if e.getValue.isEmpty then None
    else Some((Value.StringV(e.getKey): Value) -> (Value.StringV(e.getValue.get(0)): Value))
  }.toMap
  resp.body().forEach { line => ctx.invokeCallback(handler, List(Value.StringV(line))) }
  Value.InstanceV("Response", Map(
    "status"  -> Value.IntV(resp.statusCode().toLong),
    "body"    -> Value.StringV(""),
    "headers" -> Value.MapV(hdrs)
  ))

private def httpMkResponse(status: Int, headers: Map[Value, Value] = Map.empty, body: String = ""): Value =
  Value.InstanceV("Response", Map(
    "status"  -> Value.IntV(status),
    "headers" -> Value.MapV(headers),
    "body"    -> Value.StringV(body)
  ))

private def httpBodyOf(v: Any): String = v match
  case s: String                           => s
  case Value.InstanceV("_Raw", fields)    => fields.get("html").map(Value.show).getOrElse("")
  case other: Value                        => Value.show(other)
  case other                               => other.toString

private def httpAnyToValue(a: Any): Value = a match
  case n: Long    => Value.IntV(n)
  case i: Int     => Value.IntV(i.toLong)
  case d: Double  => Value.DoubleV(d)
  case s: String  => Value.StringV(s)
  case b: Boolean => Value.BoolV(b)
  case ()         => Value.UnitV
  case v: Value   => v
  case other      => Value.StringV(other.toString)
