package ssc.plugin.httpfast

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import ssc.{Done, Runtime, Show, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}
import ssc.plugin.json.NativeJsonCodec

/** Super-optimal from-scratch HTTP server for the v2 JVM runtime (drop-in replacement for
  * the `com.sun.net.httpserver`-based plugin). Server = NIO/blocking `ServerSocket` +
  * virtual-thread-per-connection + a hand-written HTTP/1.1 parser and path-param router
  * ([[FastHttpServer]] / [[NioNativeHttpServerHost]]). Client stays on `java.net.http`.
  *
  * Registers the same intrinsic surface as `ssc.plugin.http.HttpNativePlugin` (same `id`),
  * so it replaces it when swapped onto the CLI classpath. */
final class HttpFastNativePlugin extends NativePlugin:
  def id: String = "50-http"

  private final case class ClientSettings(
      baseUrl: String = "",
      timeoutMs: Long = 30000L,
      maxAttempts: Int = 1,
      retryDelayMs: Long = 1000L)

  private val clientSettings = ThreadLocal.withInitial(() => ClientSettings())

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def integer(args: List[Value], index: Int, operation: String): Long = args.lift(index) match
    case Some(Value.IntV(value)) => value
    case Some(Value.StrV(value)) => value.toLongOption.getOrElse(
      throw new RuntimeException(s"$operation argument ${index + 1} must be Int"))
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be Int")

  private def valueText(value: Value): String = value match
    case Value.StrV(text) => text
    case Value.IntV(number) => number.toString
    case Value.FloatV(number) => number.toString
    case Value.DecimalV(text) => text
    case Value.BoolV(boolean) => boolean.toString
    case Value.ForeignV(decimal: java.math.BigDecimal) => decimal.toPlainString
    case other => Show.show(other)

  private def valueMap(entries: Iterable[(String, String)]): Value =
    val result = Value.MapV.empty
    entries.toList.sortBy(_._1.toLowerCase).foreach { case (key, value) =>
      result.entries(Value.StrV(key)) = Value.StrV(value)
    }
    result

  private def headers(value: Value): Map[String, String] = value match
    case Value.MapV(map) => map.iterator.collect {
      case (Value.StrV(key), Value.StrV(headerValue)) => key -> headerValue
    }.toMap
    case Value.ForeignV(map: collection.Map[?, ?]) if map.keysIterator.forall(_.isInstanceOf[Value]) =>
      map.asInstanceOf[collection.Map[Value, Value]].iterator.collect {
        case (Value.StrV(key), Value.StrV(headerValue)) => key -> headerValue
      }.toMap
    case _ => Map.empty

  private def response(status: Int, headers: Map[String, String] = Map.empty, body: String = ""): Value =
    Value.DataV("Response", Vector(Value.IntV(status.toLong), valueMap(headers), Value.StrV(body)))

  private def responseParts(value: Value, operation: String): (Int, Map[String, String], String) = value match
    case Value.DataV("Response", Seq(Value.IntV(status), headerValue, Value.StrV(body))) =>
      (status.toInt, headers(headerValue), body)
    case _ => throw new RuntimeException(s"$operation argument 1 must be Response")

  private def resolveUrl(rawUrl: String, settings: ClientSettings): String =
    if rawUrl.startsWith("http://") || rawUrl.startsWith("https://") then rawUrl
    else if settings.baseUrl.nonEmpty then
      settings.baseUrl.stripSuffix("/") + "/" + rawUrl.stripPrefix("/")
    else throw new RuntimeException(s"relative HTTP URL requires httpClient(baseUrl): $rawUrl")

  private def request(method: String, rawUrl: String, body: String, requestHeaders: Map[String, String]): Value =
    val settings = clientSettings.get()
    val timeout = Duration.ofMillis(settings.timeoutMs.max(1L))
    val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    val builder = HttpRequest.newBuilder().uri(URI.create(resolveUrl(rawUrl, settings))).timeout(timeout)
    requestHeaders.toList.sortBy(_._1.toLowerCase).foreach { case (key, value) => builder.header(key, value) }
    val httpRequest = method match
      case "GET" => builder.GET().build()
      case "DELETE" => builder.DELETE().build()
      case "POST" => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      case "PUT" => builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
      case other => builder.method(other, HttpRequest.BodyPublishers.ofString(body)).build()

    var attempt = 0
    var lastResponse: HttpResponse[String] | Null = null
    var lastError: Throwable | Null = null
    while attempt < settings.maxAttempts.max(1) do
      try
        lastResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        lastError = null
      catch case error: Throwable => lastError = error
      attempt += 1
      val retry = lastError != null || (lastResponse != null && lastResponse.nn.statusCode() >= 500)
      if retry && attempt < settings.maxAttempts.max(1) then Thread.sleep(settings.retryDelayMs.max(0L))
      else attempt = settings.maxAttempts.max(1)
    if lastError != null then
      val detail = Option(lastError.nn.getMessage).getOrElse(lastError.nn.getClass.getSimpleName)
      throw new RuntimeException(s"HTTP $method failed: $detail", lastError)
    val result = lastResponse.nn
    val responseHeaders = result.headers().map().entrySet().iterator().asScala.flatMap { entry =>
      entry.getValue.asScala.headOption.map(entry.getKey -> _)
    }.toMap
    response(result.statusCode(), responseHeaders, result.body())

  private def requestArgs(method: String, args: List[Value], hasBody: Boolean): Value =
    val url = text(args, 0, s"http${method.toLowerCase.capitalize}")
    val body = if hasBody then text(args, 1, s"http${method.toLowerCase.capitalize}") else ""
    val headersIndex = if hasBody then 2 else 1
    val requestHeaders = args.lift(headersIndex).map(headers).getOrElse(Map.empty)
    request(method, url, body, requestHeaders)

  private def streamRequest(context: NativePluginContext, method: String, rawUrl: String, body: String, requestHeaders: Map[String, String], handler: Value): Value =
    val settings = clientSettings.get()
    val timeout = Duration.ofMillis(settings.timeoutMs.max(1L))
    val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    val builder = HttpRequest.newBuilder().uri(URI.create(resolveUrl(rawUrl, settings))).timeout(timeout)
    requestHeaders.toList.sortBy(_._1.toLowerCase).foreach { case (key, value) => builder.header(key, value) }
    val httpRequest = method match
      case "GET" => builder.GET().build()
      case "POST" => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      case other => builder.method(other, HttpRequest.BodyPublishers.ofString(body)).build()
    val result = client.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
    try result.body().forEach(line => context.invoke(handler, List(Value.StrV(line))))
    finally result.body().close()
    val responseHeaders = result.headers().map().entrySet().iterator().asScala.flatMap { entry =>
      entry.getValue.asScala.headOption.map(entry.getKey -> _)
    }.toMap
    response(result.statusCode(), responseHeaders, "")

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private def unsupported(operation: String): List[Value] => Value = _ =>
    throw new RuntimeException(s"native HTTP server unavailable: $operation requires the standard server-host SPI")

  def install(context: NativePluginContext): Unit =
    context.registerFields("Response", Vector("status", "headers", "body"))
    context.registerFields("TlsContext", Vector("certPath", "keyPath"))
    context.registerFields("Request", Vector("method", "path", "headers", "body", "form", "files", "cookies", "session", "json", "params", "query"))
    val serverHost = NioNativeHttpServerHost(context)

    val httpGet: List[Value] => Value = args => requestArgs("GET", args, hasBody = false)
    val httpPost: List[Value] => Value = args => requestArgs("POST", args, hasBody = true)
    val httpPut: List[Value] => Value = args => requestArgs("PUT", args, hasBody = true)
    val httpPatch: List[Value] => Value = args => requestArgs("PATCH", args, hasBody = true)
    val httpDelete: List[Value] => Value = args => requestArgs("DELETE", args, hasBody = false)
    native(context, "httpGet")(httpGet)
    native(context, "httpPost")(httpPost)
    native(context, "httpPut")(httpPut)
    native(context, "httpPatch")(httpPatch)
    native(context, "httpDelete")(httpDelete)

    native(context, "httpClient") { args =>
      val baseUrl = text(args, 0, "httpClient")
      closure(1) {
        case List(block) =>
          val previous = clientSettings.get()
          clientSettings.set(previous.copy(baseUrl = baseUrl))
          try context.invoke(block, Nil)
          finally clientSettings.set(previous)
        case _ => throw new RuntimeException("httpClient(baseUrl)(block)")
      }
    }
    native(context, "httpTimeout") { args =>
      val timeout = integer(args, 0, "httpTimeout")
      if timeout <= 0 then throw new RuntimeException("httpTimeout must be positive")
      clientSettings.set(clientSettings.get().copy(timeoutMs = timeout))
      Value.UnitV
    }
    native(context, "httpRetry") { args =>
      val attempts = integer(args, 0, "httpRetry")
      val delay = args.lift(1) match
        case Some(Value.IntV(value)) => value
        case None => 1000L
        case _ => throw new RuntimeException("httpRetry argument 2 must be Int")
      if attempts <= 0 || attempts > Int.MaxValue then
        throw new RuntimeException("httpRetry maxAttempts must be positive")
      clientSettings.set(clientSettings.get().copy(maxAttempts = attempts.toInt, retryDelayMs = delay.max(0L)))
      Value.UnitV
    }
    native(context, "httpGetStream") { args =>
      val url = text(args, 0, "httpGetStream")
      val requestHeaders = args.lift(1).map(headers).getOrElse(Map.empty)
      closure(1) {
        case List(handler) => streamRequest(context, "GET", url, "", requestHeaders, handler)
        case _ => throw new RuntimeException("httpGetStream(url[, headers])(handler)")
      }
    }
    native(context, "httpPostStream") { args =>
      val url = text(args, 0, "httpPostStream")
      val body = text(args, 1, "httpPostStream")
      val requestHeaders = args.lift(2).map(headers).getOrElse(Map.empty)
      closure(1) {
        case List(handler) => streamRequest(context, "POST", url, body, requestHeaders, handler)
        case _ => throw new RuntimeException("httpPostStream(url, body[, headers])(handler)")
      }
    }

    val responseApply: List[Value] => Value = args =>
      val status = integer(args, 0, "Response.apply").toInt
      val responseHeaders = args.lift(1).map(headers).getOrElse(Map.empty)
      response(status, responseHeaders, text(args, 2, "Response.apply"))
    val responseHtml: List[Value] => Value = args =>
      response(200, Map("Content-Type" -> "text/html; charset=utf-8"), valueText(args.headOption.getOrElse(Value.UnitV)))
    val responseText: List[Value] => Value = args =>
      val status = args.lift(1).collect { case Value.IntV(value) => value.toInt }.getOrElse(200)
      response(status, Map("Content-Type" -> "text/plain; charset=utf-8"), valueText(args.headOption.getOrElse(Value.UnitV)))
    val responseJson: List[Value] => Value = args =>
      val body = args.headOption match
        case Some(Value.StrV(text)) => text
        case Some(value) => NativeJsonCodec.stringify(value)
        case None => throw new RuntimeException("Response.json(body)")
      response(200, Map("Content-Type" -> "application/json"), body)
    val responseRedirect: List[Value] => Value = args => response(302, Map("Location" -> text(args, 0, "Response.redirect")))
    val responseNotFound: List[Value] => Value = args => response(404, body = args.headOption.map(valueText).getOrElse("Not Found"))
    val responseStatus: List[Value] => Value = args => response(
      integer(args, 0, "Response.status").toInt,
      body = args.lift(1).map(valueText).getOrElse(""))

    native(context, "Response.apply")(responseApply)
    native(context, "Response.html")(responseHtml)
    native(context, "Response.text")(responseText)
    native(context, "Response.json")(responseJson)
    native(context, "Response.redirect")(responseRedirect)
    native(context, "Response.notFound")(responseNotFound)
    native(context, "Response.status")(responseStatus)
    val responseMethods = Map(
      "apply" -> closure(-1)(responseApply),
      "html" -> closure(-1)(responseHtml),
      "text" -> closure(-1)(responseText),
      "json" -> closure(-1)(responseJson),
      "redirect" -> closure(-1)(responseRedirect),
      "notFound" -> closure(-1)(responseNotFound),
      "status" -> closure(-1)(responseStatus))
    context.registerValue("Response", Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = responseMethods
      def getField(name: String): Option[Value] = responseMethods.get(name)))

    native(context, "cacheable") { args =>
      val (status, existing, body) = responseParts(args.headOption.getOrElse(Value.UnitV), "cacheable")
      val maxAge = integer(args, 1, "cacheable")
      val etag = args.lift(2).collect { case Value.StrV(value) => value }
      response(status, existing ++ Map("Cache-Control" -> s"public, max-age=$maxAge") ++ etag.map("ETag" -> _), body)
    }
    native(context, "noCache") { args =>
      val (status, existing, body) = responseParts(args.headOption.getOrElse(Value.UnitV), "noCache")
      response(status, existing + ("Cache-Control" -> "no-store"), body)
    }
    native(context, "tls") { args =>
      Value.DataV("TlsContext", Vector(Value.StrV(text(args, 0, "tls")), Value.StrV(text(args, 1, "tls"))))
    }
    native(context, "requestCookie")(_ => Value.StrV(""))

    native(context, "route") { args =>
      val method = text(args, 0, "route")
      val path = text(args, 1, "route")
      args.lift(2) match
        case Some(handler) => serverHost.register(method, path, handler); Value.UnitV
        case None => closure(1) {
          case List(handler) => serverHost.register(method, path, handler); Value.UnitV
          case _ => throw new RuntimeException("route(method, path)(handler)")
        }
    }
    native(context, "serveAsync") { args =>
      if args.length != 1 then throw new RuntimeException("native TLS server requires a future server-host extension")
      serverHost.serve(integer(args, 0, "serveAsync").toInt, asynchronous = true)
      Value.UnitV
    }
    native(context, "serve") { args =>
      if args.length != 1 then throw new RuntimeException("native TLS server requires a future server-host extension")
      serverHost.serve(integer(args, 0, "serve").toInt, asynchronous = false)
      Value.UnitV
    }
    native(context, "stop") { _ => serverHost.stop(); Value.UnitV }

    // Real intrinsic (the engine supports it) — set the max request-body size in bytes.
    native(context, "maxBodySize") { args =>
      serverHost.setMaxBodyBytes(integer(args, 0, "maxBodySize"))
      Value.UnitV
    }

    // ---- WebSocket (RFC 6455) ----
    context.registerFields("WebSocket", Vector("id"))
    context.registerFields("WsRoom", Vector("id"))

    def bytesOf(v: Value): Array[Byte] = v match
      case Value.BytesV(b) => b.toArray
      case Value.StrV(s)   => s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      case _ => throw new RuntimeException("expected Bytes")

    context.registerTaggedMethod("WebSocket", "send") {
      case recv :: Value.StrV(msg) :: Nil => serverHost.wsSend(recv, msg); Value.UnitV
      case _ => throw new RuntimeException("ws.send(text)")
    }
    context.registerTaggedMethod("WebSocket", "sendBytes") {
      case recv :: payload :: Nil => serverHost.wsSendBytes(recv, bytesOf(payload)); Value.UnitV
      case _ => throw new RuntimeException("ws.sendBytes(bytes)")
    }
    context.registerTaggedMethod("WebSocket", "ping") {
      case recv :: Nil => serverHost.wsPing(recv); Value.UnitV
      case recv :: _   => serverHost.wsPing(recv); Value.UnitV
      case _ => throw new RuntimeException("ws.ping()")
    }
    context.registerTaggedMethod("WebSocket", "close") {
      case recv :: Nil => serverHost.wsClose(recv, 1000, ""); Value.UnitV
      case recv :: Value.IntV(code) :: rest =>
        val reason = rest.headOption.collect { case Value.StrV(s) => s }.getOrElse("")
        serverHost.wsClose(recv, code.toInt, reason); Value.UnitV
      case recv :: _ => serverHost.wsClose(recv, 1000, ""); Value.UnitV
      case _ => throw new RuntimeException("ws.close([code, reason])")
    }
    context.registerTaggedMethod("WebSocket", "onMessage") {
      case recv :: cb :: Nil => serverHost.wsOnMessage(recv, cb); Value.UnitV
      case _ => throw new RuntimeException("ws.onMessage { msg => … }")
    }
    context.registerTaggedMethod("WebSocket", "onClose") {
      case recv :: cb :: Nil => serverHost.wsOnClose(recv, cb); Value.UnitV
      case _ => throw new RuntimeException("ws.onClose { code => … }")
    }
    context.registerTaggedMethod("WebSocket", "onPong") {
      case recv :: cb :: Nil => serverHost.wsOnPong(recv, cb); Value.UnitV
      case _ => throw new RuntimeException("ws.onPong { bytes => … }")
    }
    context.registerTaggedMethod("WebSocket", "isClosed") {
      case recv :: Nil => Value.BoolV(serverHost.wsIsClosed(recv))
      case _ => throw new RuntimeException("ws.isClosed")
    }
    context.registerTaggedMethod("WebSocket", "request") {
      case recv :: Nil => serverHost.wsRequest(recv)
      case _ => throw new RuntimeException("ws.request")
    }
    context.registerTaggedMethod("WebSocket", "subprotocol") {
      case recv :: Nil => serverHost.wsSubprotocol(recv)
      case _ => throw new RuntimeException("ws.subprotocol")
    }
    context.registerTaggedMethod("WebSocket", "user") {
      case recv :: Nil => serverHost.wsUser(recv)
      case _ => throw new RuntimeException("ws.user")
    }

    context.registerTaggedMethod("WsRoom", "add") {
      case recv :: ws :: Nil => serverHost.roomAdd(recv, ws); Value.UnitV
      case _ => throw new RuntimeException("room.add(ws)")
    }
    context.registerTaggedMethod("WsRoom", "remove") {
      case recv :: ws :: Nil => serverHost.roomRemove(recv, ws); Value.UnitV
      case _ => throw new RuntimeException("room.remove(ws)")
    }
    context.registerTaggedMethod("WsRoom", "broadcast") {
      case recv :: Value.StrV(msg) :: Nil => serverHost.roomBroadcast(recv, msg); Value.UnitV
      case _ => throw new RuntimeException("room.broadcast(text)")
    }
    context.registerTaggedMethod("WsRoom", "size") {
      case recv :: Nil => Value.IntV(serverHost.roomSize(recv).toLong)
      case _ => throw new RuntimeException("room.size")
    }

    native(context, "onWebSocket") { args =>
      val path = text(args, 0, "onWebSocket")
      closure(1) {
        case List(handler) => serverHost.registerWs(path, handler, None, Nil); Value.UnitV
        case _ => throw new RuntimeException("onWebSocket(path)(handler)")
      }
    }
    native(context, "onWebSocketAuth") { args =>
      val path = text(args, 0, "onWebSocketAuth")
      val authFn = args.lift(1).getOrElse(throw new RuntimeException("onWebSocketAuth(path, authFn)(handler)"))
      closure(1) {
        case List(handler) => serverHost.registerWs(path, handler, Some(authFn), Nil); Value.UnitV
        case _ => throw new RuntimeException("onWebSocketAuth(path, authFn)(handler)")
      }
    }
    native(context, "wsConnect") { args =>
      val url = text(args, 0, "wsConnect")
      val requestHeaders = args.lift(1).map(headers).getOrElse(Map.empty)
      closure(1) {
        case List(handler) => serverHost.connectClient(url, requestHeaders, Nil, handler)
        case _ => throw new RuntimeException("wsConnect(url)(handler)")
      }
    }
    native(context, "WsRoom") { _ => serverHost.roomCreate() }

    // ---- middleware + streaming ----
    context.registerFields("HttpStream", Vector("id"))

    native(context, "use") { args =>
      val mw = args.headOption.getOrElse(throw new RuntimeException("use(middleware)"))
      serverHost.addMiddleware(mw); Value.UnitV
    }
    native(context, "cors") { args =>
      val origin  = args.lift(0).collect { case Value.StrV(s) => s }.getOrElse("*")
      val methods = args.lift(1).collect { case Value.StrV(s) => s }.getOrElse("GET, POST, PUT, PATCH, DELETE, OPTIONS")
      val hdrs    = args.lift(2).collect { case Value.StrV(s) => s }.getOrElse("Content-Type, Authorization")
      serverHost.enableCors(origin, methods, hdrs); Value.UnitV
    }
    native(context, "useGzip") { _ => serverHost.enableGzip(); Value.UnitV }

    native(context, "sse") { args =>
      if args.isEmpty then throw new RuntimeException("sse(req) { stream => … }")
      closure(1) {
        case List(bodyHandler) => Value.DataV("__SseStream__", Vector(bodyHandler))
        case _ => throw new RuntimeException("sse(req) { stream => … }")
      }
    }
    native(context, "streamResponse") { args =>
      val contentType = args.lift(0).collect { case Value.StrV(s) => s }.getOrElse("text/plain; charset=utf-8")
      closure(1) {
        case List(bodyHandler) => Value.DataV("__RawStream__", Vector(Value.StrV(contentType), bodyHandler))
        case _ => throw new RuntimeException("streamResponse([contentType]) { stream => … }")
      }
    }

    context.registerTaggedMethod("HttpStream", "send") {
      case recv :: Value.StrV(data) :: Nil               => serverHost.streamWriterOf(recv).event(data); Value.UnitV
      case recv :: Value.StrV(event) :: Value.StrV(d) :: Nil => serverHost.streamWriterOf(recv).event(event, d); Value.UnitV
      case _ => throw new RuntimeException("stream.send(data) | stream.send(event, data)")
    }
    context.registerTaggedMethod("HttpStream", "write") {
      case recv :: Value.StrV(raw) :: Nil => serverHost.streamWriterOf(recv).write(raw); Value.UnitV
      case _ => throw new RuntimeException("stream.write(text)")
    }
    context.registerTaggedMethod("HttpStream", "comment") {
      case recv :: Value.StrV(c) :: Nil => serverHost.streamWriterOf(recv).comment(c); Value.UnitV
      case _ => throw new RuntimeException("stream.comment(text)")
    }
    context.registerTaggedMethod("HttpStream", "close") {
      case recv :: Nil => serverHost.streamWriterOf(recv).close(); Value.UnitV
      case _ => throw new RuntimeException("stream.close()")
    }
    context.registerTaggedMethod("HttpStream", "isClosed") {
      case recv :: Nil => Value.BoolV(serverHost.streamWriterOf(recv).isClosed)
      case _ => throw new RuntimeException("stream.isClosed")
    }

    // Still stubbed — file-upload spooling + static mounting (future).
    List("uploadSpoolThreshold", "uploadDir", "mount").foreach { name =>
      native(context, name)(unsupported(name))
    }
