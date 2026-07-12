package scalascript.compiler.plugin.http

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{HttpCap, PluginComputation, PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Bool, Lst, MapVal, Inst}

/** HTTP server + client intrinsics for the tree-walking interpreter.
 *
 *  All operations previously registered via hardcoded `nativeP` calls in
 *  `Interpreter.initBuiltins` now flow through the shared `IntrinsicImpl`
 *  pipeline (Stage 5+/B).  `NativeContext` carries the hooks that
 *  `Interpreter.installNativeIntrinsics` overrides with live interpreter
 *  state so these implementations stay free of direct Interpreter references. */
object HttpIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── HTTP server: route / tls / serve / stop ────────────────────────

    QualifiedName("route") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(method: String, path: String) =>
          PluginValue.nativeFn("route.handler", {
            case List(handler) =>
              ctx.featureLocalRemove(NativeContextFeatureKeys.OpenApiPending) match
                case Some(metadata: OpenApiGenerator.OpenApiMetadata) =>
                  ctx.registerRouteWithOpenApi(method, path, handler, metadata)
                case _ =>
                  ctx.registerRoute(method, path, handler)
              PluginValue.unit
            case _ => PluginError.raise("route(method, path) { handler }")
          })
        case _ => PluginError.raise("route(method, path) { handler }")
    },

    QualifiedName("openapi") -> PluginNative.evalLegacy { (ctx, args) =>
      def asString(v: Any): String = v match
        case s: String        => s
        case Str(s) => s
        case PluginValue.unit      => ""
        case null             => ""
        case other            => String.valueOf(other)
      def asBool(v: Any): Boolean = v match
        case b: Boolean      => b
        case Bool(b)  => b
        case s: String       => s.equalsIgnoreCase("true")
        case Str(s) => s.equalsIgnoreCase("true")
        case _               => false
      def asTags(v: Any): List[String] = v match
        case xs: List[?]    => xs.map(asString).filter(_.nonEmpty)
        case Lst(xs) => xs.map(asString).filter(_.nonEmpty)
        case PluginValue.unit     => Nil
        case s: String if s.nonEmpty => List(s)
        case _             => Nil
      val padded = args.padTo(5, "")
      val metadata = OpenApiGenerator.OpenApiMetadata(
        summary     = Option(asString(padded(0))).filter(_.nonEmpty),
        description = Option(asString(padded(1))).filter(_.nonEmpty),
        tags        = asTags(padded(2)),
        deprecated  = asBool(padded(3)),
        security    = asTags(padded(4))
      )
      ctx.featureLocalSet(NativeContextFeatureKeys.OpenApiPending, metadata)
      PluginValue.unit
    },

    QualifiedName("openApiSecurity") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String, scheme: String, format: String) =>
          val next = ctx.featureGet(NativeContextFeatureKeys.OpenApiSecuritySchemes)
            .collect { case xs: List[?] => xs.collect { case s: OpenApiGenerator.OpenApiSecurityScheme => s } }
            .getOrElse(Nil)
            .filterNot(_.name == name) :+ OpenApiGenerator.OpenApiSecurityScheme(name, scheme, format)
          ctx.featureSet(NativeContextFeatureKeys.OpenApiSecuritySchemes, next)
          PluginValue.unit
        case List(name: String, scheme: String) =>
          val next = ctx.featureGet(NativeContextFeatureKeys.OpenApiSecuritySchemes)
            .collect { case xs: List[?] => xs.collect { case s: OpenApiGenerator.OpenApiSecurityScheme => s } }
            .getOrElse(Nil)
            .filterNot(_.name == name) :+ OpenApiGenerator.OpenApiSecurityScheme(name, scheme, "")
          ctx.featureSet(NativeContextFeatureKeys.OpenApiSecuritySchemes, next)
          PluginValue.unit
        case _ => PluginError.raise("openApiSecurity(name, scheme, format)")
    },

    // openApiRegisterSchema(name, properties, required) — Phase 6 named schemas.
    // Registers a named object schema in components.schemas.
    QualifiedName("openApiRegisterSchema") -> PluginNative.evalLegacy { (ctx, args) =>
      def extractStrMap(v: Any): Map[String, String] = v match
        case MapVal(m) => m.collect { case (Str(k), Str(vv)) => k -> vv }.toMap
        case _             => Map.empty
      def extractStrList(v: Any): List[String] = v match
        case Lst(xs) => xs.collect { case Str(s) => s }
        case _               => Nil
      def parseNode(typeName: String): OpenApiGenerator.SchemaNode =
        OpenApiGenerator.SchemaNode.fromTypeName(typeName)

      args match
        case List(name: String) =>
          registerSchema(ctx, name, OpenApiGenerator.SchemaNode.ObjNode())
          PluginValue.unit
        case List(name: String, propsV) =>
          val props = extractStrMap(propsV).map { (k, t) => k -> parseNode(t) }
          registerSchema(ctx, name, OpenApiGenerator.SchemaNode.ObjNode(props))
          PluginValue.unit
        case List(name: String, propsV, reqV) =>
          val props    = extractStrMap(propsV).map { (k, t) => k -> parseNode(t) }
          val required = extractStrList(reqV)
          registerSchema(ctx, name, OpenApiGenerator.SchemaNode.ObjNode(props, required))
          PluginValue.unit
        case _ => PluginError.raise("openApiRegisterSchema(name, properties, required?)")
    },

    QualifiedName("tls") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(cert: String, key: String) =>
          PluginValue.instance("TlsContext", Map("cert" -> PluginValue.string(cert), "key" -> PluginValue.string(key)))
        case _ => PluginError.raise("tls(certPath, keyPath)")
    },

    // Non-blocking variant of `serve` — fires the WS/HTTP server on a
    // virtual thread and returns immediately, so the caller (an actor
    // body, typically) can keep doing useful work.  Required for
    // multi-node clusters where each node needs to both bind its WS
    // server AND run an actor scheduler in the same process.
    //
    // The 2-arg `serveAsync(port, tls(cert, key))` form binds HTTPS /
    // wss:// — peers dialing the node use `wss://host:port/_ssc-actors`
    // and the existing `java.net.http`-backed outbound WS client
    // handles the TLS handshake transparently.
    QualifiedName("serveAsync") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(port: Long) =>
          ctx.registerHealthDefaults()
          ctx.registerOpenApiDefaults()
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          ctx.startServerAsync(port.toInt, ".")
          ()
        case List(port: Long, Inst("TlsContext", tlsFields)) =>
          ctx.registerHealthDefaults()
          ctx.registerOpenApiDefaults()
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          val cert = tlsFields.get("cert").collect { case Str(s) => s }.getOrElse("")
          val key  = tlsFields.get("key").collect  { case Str(s) => s }.getOrElse("")
          // Block until the TLS socket is bound (same readiness contract as the
          // plain serveAsync(port) form) so a client connecting immediately
          // afterwards does not race the bind.  Headless is guarded inside the
          // startTlsServerAsync override, mirroring startServerAsync above.
          ctx.startTlsServerAsync(port.toInt, ".", cert, key)
          ()
        case _ => PluginError.raise("serveAsync(port) or serveAsync(port, tls(cert, key))")
    },

    // serve is handled by UiPrimitives (covers both frontend + REST variants)

    QualifiedName("stop") -> PluginNative.eval((ctx: HttpCap) => ctx) { (ctx, _) =>
      ctx.stopServer()
      PluginComputation.pure(PluginValue.wrap(()))
    },

    // ── Outbound HTTP client ───────────────────────────────────────────

    QualifiedName("httpGet") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String)        => doHttpRequest("GET", url, "", Map.empty, ctx)
        case List(url: String, hdrs)  => doHttpRequest("GET", url, "", headersArg(hdrs), ctx)
        case _ => PluginError.raise("httpGet(url[, headers])")
    },

    QualifiedName("httpPost") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String, body: String)       => doHttpRequest("POST", url, body, Map.empty, ctx)
        case List(url: String, body: String, hdrs) => doHttpRequest("POST", url, body, headersArg(hdrs), ctx)
        case _ => PluginError.raise("httpPost(url, body[, headers])")
    },

    QualifiedName("httpPut") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String, body: String)       => doHttpRequest("PUT", url, body, Map.empty, ctx)
        case List(url: String, body: String, hdrs) => doHttpRequest("PUT", url, body, headersArg(hdrs), ctx)
        case _ => PluginError.raise("httpPut(url, body[, headers])")
    },

    QualifiedName("httpPatch") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String, body: String)       => doHttpRequest("PATCH", url, body, Map.empty, ctx)
        case List(url: String, body: String, hdrs) => doHttpRequest("PATCH", url, body, headersArg(hdrs), ctx)
        case _ => PluginError.raise("httpPatch(url, body[, headers])")
    },

    QualifiedName("httpDelete") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String)       => doHttpRequest("DELETE", url, "", Map.empty, ctx)
        case List(url: String, hdrs) => doHttpRequest("DELETE", url, "", headersArg(hdrs), ctx)
        case _ => PluginError.raise("httpDelete(url[, headers])")
    },

    // Streaming variants: httpGetStream(url[, headers])(handler)
    QualifiedName("httpGetStream") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String) =>
          PluginValue.nativeFn("httpGetStream.handler", {
            case List(handler) => doHttpRequestStream("GET", url, "", Map.empty, handler, ctx)
            case _ => PluginError.raise("httpGetStream(url)(handler)")
          })
        case List(url: String, hdrs) =>
          val h = headersArg(hdrs)
          PluginValue.nativeFn("httpGetStream.handler", {
            case List(handler) => doHttpRequestStream("GET", url, "", h, handler, ctx)
            case _ => PluginError.raise("httpGetStream(url, headers)(handler)")
          })
        case _ => PluginError.raise("httpGetStream(url[, headers])(handler)")
    },

    QualifiedName("httpPostStream") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String, body: String) =>
          PluginValue.nativeFn("httpPostStream.handler", {
            case List(handler) => doHttpRequestStream("POST", url, body, Map.empty, handler, ctx)
            case _ => PluginError.raise("httpPostStream(url, body)(handler)")
          })
        case List(url: String, body: String, hdrs) =>
          val h = headersArg(hdrs)
          PluginValue.nativeFn("httpPostStream.handler", {
            case List(handler) => doHttpRequestStream("POST", url, body, h, handler, ctx)
            case _ => PluginError.raise("httpPostStream(url, body, headers)(handler)")
          })
        case _ => PluginError.raise("httpPostStream(url, body[, headers])(handler)")
    },

    // httpClient(baseUrl) { block } stays as Term.Apply special form in eval.

    // ── WebSocket client ───────────────────────────────────────────────

    // wsConnect(url[, headers[, protocols]]) { ws => … }
    // Curried; blocks on the calling thread until the server closes.
    QualifiedName("wsConnect") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(url: String) =>
          PluginValue.nativeFn("wsConnect.handler", {
            case List(handler) => ctx.wsConnectSync(url, Map.empty, Nil, handler); PluginValue.unit
            case _ => PluginError.raise("wsConnect(url) { ws => … }")
          })
        case List(url: String, hdrs) =>
          val headers = headersArg(hdrs)
          PluginValue.nativeFn("wsConnect.handler", {
            case List(handler) => ctx.wsConnectSync(url, headers, Nil, handler); PluginValue.unit
            case _ => PluginError.raise("wsConnect(url, headers) { ws => … }")
          })
        case List(url: String, hdrs, Lst(prots)) =>
          val headers   = headersArg(hdrs)
          val protocols = prots.collect { case Str(s) => s }
          PluginValue.nativeFn("wsConnect.handler", {
            case List(handler) => ctx.wsConnectSync(url, headers, protocols, handler); PluginValue.unit
            case _ => PluginError.raise("wsConnect(url, headers, protocols) { ws => … }")
          })
        case _ => PluginError.raise("wsConnect(url[, headers[, protocols]]) { ws => … }")
    },

    // ── CORS / gzip / cache ────────────────────────────────────────────

    QualifiedName("cors") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Lst(origins)) =>
          ctx.configureCors(
            origins.collect { case Str(s) => s },
            List("GET","POST","PUT","DELETE","OPTIONS","PATCH"), Nil)
          ()
        case List(Lst(origins), Lst(methods)) =>
          ctx.configureCors(
            origins.collect { case Str(s) => s },
            methods.collect { case Str(s) => s }, Nil)
          ()
        case List(Lst(origins), Lst(methods), Lst(hdrs)) =>
          ctx.configureCors(
            origins.collect { case Str(s) => s },
            methods.collect { case Str(s) => s },
            hdrs.collect { case Str(s) => s })
          ()
        case _ => PluginError.raise("cors(origins[, methods[, headers]])")
    },

    QualifiedName("useGzip") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil => ctx.enableGzip(); ()
        case _   => PluginError.raise("useGzip()")
    },

    QualifiedName("cacheable") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(resp, n: Long) =>
          addCacheHdrs(resp, Map("Cache-Control" -> s"public, max-age=$n"))
        case List(resp, n: Long, etag: String) =>
          addCacheHdrs(resp, Map("Cache-Control" -> s"public, max-age=$n", "ETag" -> etag))
        case _ => PluginError.raise("cacheable(response, maxAge[, etag])")
    },

    QualifiedName("noCache") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(resp) =>
          addCacheHdrs(resp, Map("Cache-Control" -> "no-store, no-cache, must-revalidate"))
        case _ => PluginError.raise("noCache(response)")
    },

    // ── Streaming / SSE ────────────────────────────────────────────────

    // streamResponse { write => ... } — chunked streaming from a route handler.
    // Returns a StreamResponse sentinel detected by WebServer.dispatchRoute.
    QualifiedName("streamResponse") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(n: Long) =>
          PluginValue.nativeFn("streamResponse.block", {
            case List(block) => PluginValue.instance("StreamResponse", Map(
              "status" -> PluginValue.int(n), "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]), "callback" -> block))
            case _ => PluginError.raise("streamResponse(status)(block)")
          })
        case List(n: Long, MapVal(hdrs)) =>
          PluginValue.nativeFn("streamResponse.block", {
            case List(block) => PluginValue.instance("StreamResponse", Map(
              "status" -> PluginValue.int(n), "headers" -> PluginValue.mapOf(hdrs), "callback" -> block))
            case _ => PluginError.raise("streamResponse(status, headers)(block)")
          })
        case List(block) =>
          PluginValue.instance("StreamResponse", Map(
            "status" -> PluginValue.int(200), "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]), "callback" -> PluginValue.wrap(block)))
        case _ => PluginError.raise("streamResponse(block)")
    },

    // sse(req) { stream => stream.send(data) / stream.send(event, data) / stream.close() }
    QualifiedName("sse") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(_) =>
          val sseHeaders = Map(
            "Content-Type"      -> "text/event-stream",
            "Cache-Control"     -> "no-cache",
            "Connection"        -> "keep-alive",
            "X-Accel-Buffering" -> "no"
          )
          val headerMap = PluginValue.mapOf(sseHeaders.map((k, v) =>
            (PluginValue.string(k)) -> (PluginValue.string(v))))
          PluginValue.nativeFn("sse.block", {
            case List(block) =>
              val callback = PluginValue.nativeFn("sse.writer", {
                case List(writeFn) =>
                  val sseStream = PluginValue.instance("SseStream", Map(
                    "send" -> PluginValue.nativeFn("SseStream.send", {
                      case List(Str(data)) =>
                        ctx.invokeCallback(writeFn, List(PluginValue.string(s"data: $data\n\n")))
                        PluginValue.unit
                      case List(Str(event), Str(data)) =>
                        ctx.invokeCallback(writeFn, List(PluginValue.string(s"event: $event\ndata: $data\n\n")))
                        PluginValue.unit
                      case _ => PluginError.raise("SseStream.send(data) or send(event, data)")
                    }),
                    "close" -> PluginValue.nativeFn("SseStream.close", (_ => PluginValue.unit))
                  ))
                  ctx.invokeCallback(block, List(sseStream))
                  PluginValue.unit
                case _ => PluginError.raise("sse internal writer error")
              })
              PluginValue.instance("StreamResponse", Map(
                "status"   -> PluginValue.int(200),
                "headers"  -> headerMap,
                "callback" -> callback
              ))
            case _ => PluginError.raise("sse(req)(block)")
          })
        case _ => PluginError.raise("sse(req)(block)")
    },

    // ── Body / upload limits ───────────────────────────────────────────

    QualifiedName("maxBodySize") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n: Long) => ctx.setMaxBodySize(n); ()
        case _ => PluginError.raise("maxBodySize(bytes: Int)")
    },

    QualifiedName("uploadSpoolThreshold") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n: Long) => ctx.setSpoolThreshold(n); ()
        case _ => PluginError.raise("uploadSpoolThreshold(bytes: Int)")
    },

    QualifiedName("uploadDir") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dir: String) => ctx.setUploadDir(dir); ()
        case _ => PluginError.raise("uploadDir(path: String)")
    },

    // ── Middleware ─────────────────────────────────────────────────────

    QualifiedName("use") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(fn) => ctx.registerMiddleware(fn); ()
        case _ => PluginError.raise("use(fn: (Request, () => Response) => Response)")
    },

    // ── HTTP client config (scoped by httpClient{} block) ─────────────

    QualifiedName("httpTimeout") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(ms: Long) => ctx.setHttpTimeout(ms); ()
        case _ => PluginError.raise("httpTimeout(ms: Int)")
    },

    QualifiedName("httpRetry") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n: Long)         => ctx.setHttpRetry(n.toInt, ctx.httpRetryDelayMs); ()
        case List(n: Long, d: Long) => ctx.setHttpRetry(n.toInt, d); ()
        case _ => PluginError.raise("httpRetry(maxAttempts[, delayMs])")
    },

    // ── WebSocket server ───────────────────────────────────────────────

    // onWebSocket(path[, origins[, protocols[, maxConnections[, maxMessagesPerSec]]]]) { ws => … }
    // Two-arg form restricts upgrades to the given Origin list (browser CSRF guard).
    QualifiedName("onWebSocket") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(path: String) =>
          PluginValue.nativeFn("onWebSocket.handler", {
            case List(handler) => ctx.registerWsRoute(path, Nil, Nil, 0, 0, handler); PluginValue.unit
            case _ => PluginError.raise("onWebSocket(path) { ws => … }")
          })
        case List(path: String, Lst(origins)) =>
          val origs = origins.collect { case Str(s) => s }
          PluginValue.nativeFn("onWebSocket.handler", {
            case List(handler) => ctx.registerWsRoute(path, origs, Nil, 0, 0, handler); PluginValue.unit
            case _ => PluginError.raise("onWebSocket(path, origins) { ws => … }")
          })
        case List(path: String, Lst(origins), Lst(protocols)) =>
          val origs  = origins.collect   { case Str(s) => s }
          val protos = protocols.collect { case Str(s) => s }
          PluginValue.nativeFn("onWebSocket.handler", {
            case List(handler) => ctx.registerWsRoute(path, origs, protos, 0, 0, handler); PluginValue.unit
            case _ => PluginError.raise("onWebSocket(path, origins, protocols) { ws => … }")
          })
        case List(path: String, Lst(origins), Lst(protocols), maxConn: Long) =>
          val origs  = origins.collect   { case Str(s) => s }
          val protos = protocols.collect { case Str(s) => s }
          val cap    = if maxConn > Int.MaxValue.toLong || maxConn < 0 then 0 else maxConn.toInt
          PluginValue.nativeFn("onWebSocket.handler", {
            case List(handler) => ctx.registerWsRoute(path, origs, protos, cap, 0, handler); PluginValue.unit
            case _ => PluginError.raise("onWebSocket(path, origins, protocols, maxConnections) { ws => … }")
          })
        case List(path: String, Lst(origins), Lst(protocols), maxConn: Long, maxRate: Long) =>
          val origs  = origins.collect   { case Str(s) => s }
          val protos = protocols.collect { case Str(s) => s }
          val cap    = if maxConn > Int.MaxValue.toLong || maxConn < 0 then 0 else maxConn.toInt
          val rate   = if maxRate > Int.MaxValue.toLong || maxRate < 0 then 0 else maxRate.toInt
          PluginValue.nativeFn("onWebSocket.handler", {
            case List(handler) => ctx.registerWsRoute(path, origs, protos, cap, rate, handler); PluginValue.unit
            case _ => PluginError.raise("onWebSocket(path, origins, protocols, maxConnections, maxMessagesPerSec) { ws => … }")
          })
        case _ => PluginError.raise("onWebSocket(path[, origins[, protocols[, maxConnections[, maxMessagesPerSec]]]]) { ws => … }")
    },

    // onWebSocketAuth(path, authFn)(handler) — pre-upgrade auth hook.
    // authFn receives the Request, returns Option[Any]: None → 401, Some(user) → accepted.
    QualifiedName("onWebSocketAuth") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(path: String, authFn) =>
          PluginValue.nativeFn("onWebSocketAuth.handler", {
            case List(handler) => ctx.registerWsAuthRoute(path, authFn, handler); PluginValue.unit
            case _ => PluginError.raise("onWebSocketAuth(path, authFn) { ws => … }")
          })
        case _ => PluginError.raise("onWebSocketAuth(path, authFn) { ws => … }")
    },

    // ── mount(method, path, file[, ctx]) ────────────────────────────────

    QualifiedName("mount") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(method: String, path: String, file: String) =>
          mountFile(ctx, method, path, file, Map.empty)
        case List(method: String, path: String, file: String, MapVal(rawCtx)) =>
          val mountCtx: Map[String, Any] = rawCtx.collect {
            case (Str(k), v) => k -> (v: Any)
          }.toMap
          mountFile(ctx, method, path, file, mountCtx)
        case _ =>
          PluginError.raise("mount(method, path, file[, ctx: Map[String, Any]])")
    },

    // ── Response builders (Stage 5+/E) ────────────────────────────────────

    QualifiedName("Response.html") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v) =>
          PluginValue.instance("Response", Map(
            "status"  -> PluginValue.int(200),
            "headers" -> PluginValue.mapOf(Map(PluginValue.string("Content-Type") -> PluginValue.string("text/html; charset=utf-8"))),
            "body"    -> PluginValue.string(httpBodyOf(v))
          ))
        case _ => PluginError.raise("Response.html(body)")
    },

    QualifiedName("Response.text") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v) =>
          httpMkResponse(200, Map(PluginValue.string("Content-Type") -> PluginValue.string("text/plain; charset=utf-8")), httpBodyOf(v))
        case List(v, statusV) =>
          val code = statusV match
            case n: Long   => n.toInt
            case n: Int    => n
            case Num(n) => n.toInt
            case s: String => s.toIntOption.getOrElse(200)
            case Str(s) => s.toIntOption.getOrElse(200)
            case _ => 200
          httpMkResponse(code, Map(PluginValue.string("Content-Type") -> PluginValue.string("text/plain; charset=utf-8")), httpBodyOf(v))
        case _ => PluginError.raise("Response.text(body)")
    },

    QualifiedName("Response.json") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String) =>
          httpMkResponse(200, Map(PluginValue.string("Content-Type") -> PluginValue.string("application/json")), s)
        case List(v) =>
          httpMkResponse(200, Map(PluginValue.string("Content-Type") -> PluginValue.string("application/json")), PluginValue.jsonEncode(PluginValue.fromHostAny(v)))
        case _ => PluginError.raise("Response.json(body)")
    },

    QualifiedName("Response.redirect") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(loc: String) => httpMkResponse(302, Map(PluginValue.string("Location") -> PluginValue.string(loc)), "")
        case _ => PluginError.raise("Response.redirect(url)")
    },

    QualifiedName("Response.notFound") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case Nil     => httpMkResponse(404, body = "Not Found")
        case List(v) => httpMkResponse(404, body = httpBodyOf(v))
        case _       => PluginError.raise("Response.notFound([body])")
    },

    QualifiedName("Response.status") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: Long)    => httpMkResponse(s.toInt)
        case List(s: Long, v) => httpMkResponse(s.toInt, body = httpBodyOf(v))
        case _ => PluginError.raise("Response.status(code[, body])")
    },

  )

  // ── Private helpers ────────────────────────────────────────────────────

  /** Evaluate a handler file and register it as a route.
   *
   *  1. Resolve `file` relative to `ctx.baseDirPath` (same as import resolution).
   *  2. Evaluate the file once via `ctx.evalFileGetResult`; take the last value.
   *  3. Detect handler shape:
   *     - `FunV` with 1 param → use directly as `req => response`
   *     - `FunV` with 2 params → use as `(req, ctx) => response`
   *     - Any other value → auto-wrap as `_ => value` (static response)
   *  4. Register via `ctx.registerMountedRoute` with source + mountCtx. */
  private def mountFile(
      ctx:      PluginContext,
      method:   String,
      path:     String,
      file:     String,
      mountCtx: Map[String, Any]
  ): PluginValue =
    val baseDir = ctx.baseDirPath match
      case Some(d) => java.nio.file.Paths.get(d)
      case None    => java.nio.file.Paths.get(System.getProperty("user.dir"))
    // Parse optional #functionName suffix
    val (actualFile, fnNameOpt) = if file.contains("#") then
      val i = file.lastIndexOf('#')
      (file.substring(0, i), Some(file.substring(i + 1)))
    else (file, None)
    val absPath = baseDir.resolve(actualFile).normalize().toAbsolutePath.toString
    val rawResult: PluginValue = fnNameOpt match
      case Some(fn) => PluginValue.wrap(ctx.evalFileGetNamedResult(absPath, fn))
      case None     => PluginValue.wrap(ctx.evalFileGetResult(absPath))
    // Shape detection: wrap bare values into a constant handler
    val baseHandler: PluginValue = PluginValue.funArity(rawResult) match
      case Some(n) if n >= 1 => rawResult  // 1-/2-param FunV used as-is; dispatcher passes ctx for 2-param
      case _                 => PluginValue.nativeFn("mount.static", _ => rawResult)  // static → constant handler
    // Wrap typed handlers: auto-deser/ser if the handler uses typed params.
    val invoke: (PluginValue, List[PluginValue]) => PluginValue = (fn, callArgs) =>
      PluginValue.wrap(ctx.invokeCallback(fn, callArgs))
    val handler = PluginValue.wrapTypedHandler(
      baseHandler, invoke, Map.empty, path, errorDetails = true)
    ctx.registerMountedRoute(method, path, handler, source = Some(absPath), mountCtx = mountCtx)
    PluginValue.unit

  private def headersArg(v: Any): Map[String, String] = v match
    case MapVal(m) => m.collect {
      case (Str(k), Str(vv)) => k -> vv
    }.toMap
    case _ => Map.empty

  private def addCacheHdrs(v: Any, extra: Map[String, String]): PluginValue = v match
    case Inst("Response", fields) =>
      val h = fields.get("headers") match
        case Some(MapVal(m)) => m
        case _                   => Map.empty[PluginValue, PluginValue]
      val merged = h ++ extra.map { (k, vv) => (PluginValue.string(k)) -> (PluginValue.string(vv)) }
      PluginValue.instance("Response", fields + ("headers" -> PluginValue.mapOf(merged)))
    case other if PluginValue.isRuntimeValue(other) => PluginValue.wrap(other)
    case _            => PluginValue.unit

  // H3 join + H2 opt-in SSRF guard, shared by doHttpRequest and doHttpRequestStream.
  private def resolveAndGuard(base: String, rawUrl: String): String =
    val url =
      if base.isEmpty || rawUrl.startsWith("http://") || rawUrl.startsWith("https://") then rawUrl
      else if rawUrl.startsWith("/") then base.stripSuffix("/") + rawUrl
      else base.stripSuffix("/") + "/" + rawUrl
    if sys.env.get("SSC_HTTP_BLOCK_INTERNAL").exists(v => v == "1" || v == "true") then
      val host = try java.net.URI.create(url).getHost catch case _: Throwable => null
      if host != null then
        val addrs = try java.net.InetAddress.getAllByName(host)
                    catch case _: Throwable => Array.empty[java.net.InetAddress]
        if addrs.exists(a => a.isLoopbackAddress || a.isLinkLocalAddress
                          || a.isSiteLocalAddress || a.isAnyLocalAddress) then
          throw new RuntimeException(
            s"SSRF blocked: host '$host' resolves to an internal address (SSC_HTTP_BLOCK_INTERNAL)")
    url

  private def doHttpRequest(
      method:  String,
      rawUrl:  String,
      body:    String,
      headers: Map[String, String],
      ctx: PluginContext
  ): PluginValue =
    import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
    import scala.jdk.CollectionConverters.*
    val base    = ctx.httpBaseUrl
    val url     = resolveAndGuard(base, rawUrl)
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
    val hdrs: Map[PluginValue, PluginValue] = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
      if e.getValue.isEmpty then None
      else Some((PluginValue.string(e.getKey)) -> (PluginValue.string(e.getValue.get(0))))
    }.toMap
    PluginValue.instance("Response", Map(
      "status"  -> PluginValue.int(resp.statusCode().toLong),
      "body"    -> PluginValue.string(resp.body()),
      "headers" -> PluginValue.mapOf(hdrs)
    ))

  private def doHttpRequestStream(
      method:  String,
      rawUrl:  String,
      body:    String,
      headers: Map[String, String],
      handler: Any,
      ctx: PluginContext
  ): PluginValue =
    import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
    import scala.jdk.CollectionConverters.*
    val base    = ctx.httpBaseUrl
    val url     = resolveAndGuard(base, rawUrl)
    val timeout = java.time.Duration.ofMillis(ctx.httpTimeoutMs)
    val client  = JHttpClient.newBuilder().connectTimeout(timeout).build()
    val builder = HttpRequest.newBuilder().uri(java.net.URI.create(url)).timeout(timeout)
    headers.foreach((k, v) => builder.header(k, v))
    val req = method match
      case "GET"  => builder.GET().build()
      case "POST" => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      case m      => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofLines())
    val hdrs: Map[PluginValue, PluginValue] = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
      if e.getValue.isEmpty then None
      else Some((PluginValue.string(e.getKey)) -> (PluginValue.string(e.getValue.get(0))))
    }.toMap
    resp.body().forEach { line => ctx.invokeCallback(handler, List(PluginValue.string(line))) }
    PluginValue.instance("Response", Map(
      "status"  -> PluginValue.int(resp.statusCode().toLong),
      "body"    -> PluginValue.string(""),
      "headers" -> PluginValue.mapOf(hdrs)
    ))

  private def httpMkResponse(status: Int, headers: Map[PluginValue, PluginValue] = Map.empty, body: String = ""): PluginValue =
    PluginValue.instance("Response", Map(
      "status"  -> PluginValue.int(status),
      "headers" -> PluginValue.mapOf(headers),
      "body"    -> PluginValue.string(body)
    ))

  private def httpBodyOf(v: Any): String = v match
    case s: String                           => s
    case Inst("_Raw", fields)    => fields.get("html").map(_.show).getOrElse("")
    case other if PluginValue.isRuntimeValue(other) => PluginValue.showAny(other)
    case other                               => other.toString

  private def registerSchema(ctx: PluginContext, name: String, node: OpenApiGenerator.SchemaNode): Unit =
    val current: Map[String, OpenApiGenerator.SchemaNode] =
      ctx.featureGet(NativeContextFeatureKeys.OpenApiSchemaComponents)
        .collect { case m: Map[?, ?] =>
          m.collect { case (k: String, v: OpenApiGenerator.SchemaNode) => k -> v }
           .toMap[String, OpenApiGenerator.SchemaNode]
        }
        .getOrElse(Map.empty[String, OpenApiGenerator.SchemaNode])
    ctx.featureSet(NativeContextFeatureKeys.OpenApiSchemaComponents, current + (name -> node))
