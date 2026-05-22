package scalascript.mcp

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** HTTP client transport for `mcpConnect(Transport.Http(url))` on the
 *  interpreter (Phase 2).  Wraps Java's `HttpClient` and exposes the
 *  same `request(...)` / `notify(...)` / `close()` / `isClosed` shape
 *  as `McpClientCore`.
 *
 *  Wire pattern — simple Streamable-HTTP synchronous variant: every
 *  outbound request becomes one POST to `<url>` with the JSON-RPC
 *  frame as the body; the HTTP response body is the JSON-RPC reply.
 *  Notifications go out as POST but don't read the response.
 *
 *  SSE-streamed server→client notifications are out of scope for
 *  Phase 2 — would require a separate persistent GET stream and a
 *  reader thread.  Adding that later is additive (a second transport
 *  variant `Transport.HttpSse(url)` once the use-case surfaces). */
class McpHttpClient(url: String, timeoutMs: Long):

  private val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(timeoutMs))
    .build()

  private val nextId  = AtomicLong(1L)
  @volatile private var closed = false
  @volatile private var notificationHandler: ((String, ujson.Value) => Unit) | Null = null
  @volatile private var requestHandler:      ((String, ujson.Value) => ujson.Value) | Null = null
  @volatile private var sseThread: Thread | Null = null

  /** v1.17.x — bearer token attached to every outgoing POST + SSE GET.
   *  None means no Authorization header.  Tokens rotate via
   *  `setBearerToken(...)` (e.g. after a refresh) — already-flying
   *  requests use whatever was set when their HttpRequest was built. */
  @volatile private var bearerToken: Option[String] = None

  /** Attach (or rotate) the bearer token applied to every outbound
   *  request.  Pass `None` to drop it. */
  def setBearerToken(t: Option[String]): Unit = bearerToken = t

  /** v1.17.x — re-auth handler.  Called when a request comes back
   *  401; the handler returns either a fresh bearer (the client
   *  updates `bearerToken` + retries once) or None (the original
   *  401 propagates to the caller).  Typical wiring: the user's
   *  OAuthClient.TokenHolder.current() — refreshes the access
   *  token via the registered refresh token + AS endpoint. */
  @volatile private var on401Handler: () => Option[String] = () => None

  def setOn401Handler(h: () => Option[String]): Unit = on401Handler = h

  /** Add the `Authorization: Bearer <token>` header to the builder
   *  when a token is set.  Re-reads the volatile field so token
   *  rotation takes effect on the next request without rebuild. */
  private def withBearer(b: HttpRequest.Builder): HttpRequest.Builder =
    bearerToken match
      case Some(t) => b.header("Authorization", s"Bearer $t")
      case None    => b

  /** v1.17.x — single POST of the JSON-RPC body.  Shared between the
   *  initial attempt and the 401-retry path; rebuilds the request so
   *  the latest `bearerToken` is picked up after re-auth. */
  private def sendOnce(body: String, rt: Long): HttpResponse[java.io.InputStream] =
    val req = withBearer(HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(rt))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .POST(HttpRequest.BodyPublishers.ofString(body)))
      .build()
    client.send(req, HttpResponse.BodyHandlers.ofInputStream())

  // Provided for API parity with McpClientCore — HTTP transport is
  // request/response so there's no asynchronous dispatch table needed.
  // We still track pending counts so close() can wait briefly for
  // in-flight requests to complete (best-effort).
  private val pending = ConcurrentHashMap[Long, java.lang.Boolean]()

  /** Register a callback for server-initiated notifications.  Lazily
   *  spins up the SSE reader thread on the first registration; the
   *  thread also handles inbound Request frames if
   *  `setRequestHandler` is registered. */
  def setNotificationHandler(h: ((String, ujson.Value) => Unit) | Null): Unit =
    notificationHandler = h
    ensureSseReader()

  /** Register a callback for server-initiated Requests (bidirectional
   *  sampling).  The SSE reader thread dispatches inbound Request
   *  frames to the handler and POSTs the JSON-RPC Response back to
   *  the same `/mcp` URL — `handleHttpRequest` server-side routes
   *  inbound Response frames into the broadcaster's pending map.
   *
   *  Handler exceptions become InternalError responses; if no handler
   *  is registered the SSE reader sends a MethodNotFound response so
   *  the server unblocks. */
  def setRequestHandler(h: ((String, ujson.Value) => ujson.Value) | Null): Unit =
    requestHandler = h
    ensureSseReader()

  /** Lazily start the SSE reader thread; idempotent across multiple
   *  setNotificationHandler / setRequestHandler calls.  Reconnects
   *  with 1s back-off on transient transport errors. */
  private def ensureSseReader(): Unit =
    if sseThread != null then return
    if notificationHandler == null && requestHandler == null then return
    val eventsUrl = url + "/events"
    val t = new Thread((() => {
      while !closed && (notificationHandler != null || requestHandler != null) do
        try
          val req = withBearer(HttpRequest.newBuilder()
            .uri(URI.create(eventsUrl))
            .header("Accept", "text/event-stream")
            .GET())
            .build()
          val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
          if resp.statusCode() == 200 then
            val reader = new java.io.BufferedReader(
              new java.io.InputStreamReader(resp.body(), "UTF-8"))
            val buf = new StringBuilder()
            var line: String | Null = null
            while !closed && { line = reader.readLine(); line != null } do
              if line.isEmpty then
                if buf.nonEmpty then
                  dispatchSseFrame(buf.toString())
                  buf.clear()
              else if line.startsWith("data: ") then
                if buf.nonEmpty then buf.append('\n')
                buf.append(line.substring(6))
              // ignore other SSE fields (event:, id:, retry:, comments)
        catch case _: Throwable =>
          try Thread.sleep(1_000L) catch case _: InterruptedException => Thread.currentThread().interrupt()
    }): Runnable, "mcp-http-sse-reader")
    t.setDaemon(true); t.start()
    sseThread = t

  /** Route one parsed SSE-data payload (a single JSON-RPC frame).
   *  Notification → notification handler if any.
   *  Request      → request handler, result/error POSTed back as
   *                 a Response frame to the same `/mcp` URL. */
  private def dispatchSseFrame(payload: String): Unit =
    JsonRpc.parse(payload) match
      case Right(JsonRpc.Message.Notification(method, params)) =>
        val h = notificationHandler
        if h != null then try h(method, params) catch case _: Throwable => ()
      case Right(JsonRpc.Message.Request(method, params, id)) =>
        val h = requestHandler
        val responseFrame =
          if h != null then
            try
              val result = h(method, params)
              JsonRpc.encodeResult(id, result)
            catch case e: Throwable =>
              JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError,
                Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
          else
            JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound,
              s"client has no handler for server-initiated method: $method")
        postResponseBack(responseFrame)
      case _ => ()  // stray Response / parse error — drop

  /** POST a JSON-RPC Response frame to the server.  Fire-and-forget:
   *  the server's `handleHttpRequest` routes responses through
   *  `routeInboundResponse(resp)` and replies 204; we don't read
   *  the HTTP response body. */
  private def postResponseBack(frame: String): Unit =
    try
      val req = withBearer(HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(frame)))
        .build()
      client.send(req, HttpResponse.BodyHandlers.discarding())
    catch case _: Throwable => ()  // best-effort

  /** Send a request and block until the HTTP response arrives.
   *
   *  Server can answer in either of two formats:
   *    - `Content-Type: application/json` — single JSON frame in the body.
   *    - `Content-Type: text/event-stream` — Streamable-HTTP: SSE frames
   *       carry zero+ notifications (dispatched to setNotificationHandler
   *       on arrival) followed by the final response frame.  Lets servers
   *       interleave progress updates with the final tools/call result. */
  def request(method: String, params: ujson.Value, customTimeoutMs: Long = 0L): Either[JsonRpc.Error, ujson.Value] =
    if closed then return Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, "client closed"))
    val id   = nextId.getAndIncrement()
    val body = JsonRpc.encodeRequest(method, params, id)
    val rt   = if customTimeoutMs > 0 then customTimeoutMs else timeoutMs
    pending.put(id, java.lang.Boolean.TRUE)
    try
      val resp = sendOnce(body, rt)
      // v1.17.x — auto re-auth: when we get 401 + the user wired a
      // re-auth handler, ask it for a fresh bearer + retry once.
      // Single retry only — preserves the timeout budget + avoids
      // tight loops against a permanently-401 endpoint.
      val finalResp =
        if resp.statusCode() == 401 then
          on401Handler() match
            case Some(newToken) =>
              bearerToken = Some(newToken)
              sendOnce(body, rt)
            case None => resp
        else resp
      val resp2 = finalResp
      if resp2.statusCode() < 200 || resp2.statusCode() >= 300 then
        // Best-effort body read for the error message — read a small
        // chunk so we don't hang on a slow stream.
        val errBody = try
          val r = new java.io.BufferedReader(new java.io.InputStreamReader(resp2.body(), "UTF-8"))
          val sb = new StringBuilder
          var i = 0; var line: String | Null = r.readLine()
          while line != null && i < 5 do { sb.append(line).append('\n'); i += 1; line = r.readLine() }
          sb.toString()
        catch case _: Throwable => ""
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
          s"HTTP ${resp2.statusCode()}: ${errBody.take(200)}"))
      else
        val ct = Option(resp2.headers().firstValue("Content-Type").orElse(null)).getOrElse("")
        if ct.toLowerCase.contains("text/event-stream") then
          parseSseResponseStream(resp2.body(), id)
        else
          val bodyStr = new String(resp2.body().readAllBytes(), "UTF-8")
          JsonRpc.parse(bodyStr) match
            case Right(JsonRpc.Message.Response(_, Some(result), None)) => Right(result)
            case Right(JsonRpc.Message.Response(_, None, Some(err)))    => Left(err)
            case Right(_) => Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, "unexpected response shape"))
            case Left(e)  => Left(JsonRpc.Error(JsonRpc.ErrorCode.ParseError, e))
    catch
      case _: java.net.http.HttpTimeoutException =>
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, s"request '$method' timed out after ${rt}ms"))
      case e: Throwable =>
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    finally pending.remove(id)

  /** Parse an SSE-streamed response body inline.  Notification frames
   *  fan out to the registered notification handler; the matching
   *  Response frame (id == expectedId) ends the loop and is returned.
   *  Stops on stream EOF — returns InternalError if no matching
   *  response arrived. */
  private def parseSseResponseStream(in: java.io.InputStream, expectedId: Long): Either[JsonRpc.Error, ujson.Value] =
    val reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"))
    val buf = new StringBuilder
    var line: String | Null = null
    try
      while { line = reader.readLine(); line != null } do
        if line.isEmpty then
          if buf.nonEmpty then
            val frame = buf.toString(); buf.clear()
            JsonRpc.parse(frame) match
              case Right(JsonRpc.Message.Notification(m, p)) =>
                val h = notificationHandler
                if h != null then try h(m, p) catch case _: Throwable => ()
              case Right(JsonRpc.Message.Response(idJson, result, err)) =>
                val idMatches = idJson.numOpt.exists(_.toLong == expectedId)
                if idMatches then
                  return err match
                    case Some(e) => Left(e)
                    case None    => Right(result.getOrElse(ujson.Null))
              case _ => ()  // ignore stray Requests / parse errors mid-stream
        else if line.startsWith("data: ") then
          if buf.nonEmpty then buf.append('\n')
          buf.append(line.substring(6))
    catch case _: Throwable => ()
    Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
      "SSE response stream ended without a matching response frame"))

  /** Fire-and-forget notification — POSTs the frame and ignores the
   *  HTTP response body. */
  def notify(method: String, params: ujson.Value): Unit =
    if closed then return
    val req = withBearer(HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(JsonRpc.encodeNotification(method, params))))
      .build()
    try client.send(req, HttpResponse.BodyHandlers.discarding())
    catch case _: Throwable => ()  // best-effort

  def close(): Unit =
    closed = true
    notificationHandler = null
    requestHandler      = null
    // Wait briefly for in-flight requests; HttpClient itself doesn't
    // need explicit shutdown on JDK 21+ (background threads are daemon).
    val deadline = java.lang.System.currentTimeMillis() + 200L
    while !pending.isEmpty && java.lang.System.currentTimeMillis() < deadline do
      try Thread.sleep(10) catch case _: InterruptedException => Thread.currentThread().interrupt()
    // Interrupt the SSE reader so the blocking readLine returns.
    val t = sseThread; if t != null then try t.interrupt() catch case _: Throwable => ()

  def isClosed: Boolean = closed
