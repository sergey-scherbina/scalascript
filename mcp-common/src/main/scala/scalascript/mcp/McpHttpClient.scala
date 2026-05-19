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
  @volatile private var sseThread: Thread | Null = null

  // Provided for API parity with McpClientCore — HTTP transport is
  // request/response so there's no asynchronous dispatch table needed.
  // We still track pending counts so close() can wait briefly for
  // in-flight requests to complete (best-effort).
  private val pending = ConcurrentHashMap[Long, java.lang.Boolean]()

  /** Register a callback for server-initiated notifications.  Spins up a
   *  daemon reader thread that opens `<url>/events` as an SSE stream and
   *  dispatches each parsed `data: {jsonrpc...}\n\n` frame.  Subsequent
   *  calls replace the handler in place; the SSE thread keeps running.
   *  Pass `null` to unregister and tear the SSE thread down.
   *
   *  Best-effort connection: if the server doesn't expose `/events` the
   *  thread will keep trying to reconnect with a 1-second back-off until
   *  the client is closed. */
  def setNotificationHandler(h: ((String, ujson.Value) => Unit) | Null): Unit =
    notificationHandler = h
    if h != null && sseThread == null then
      val eventsUrl = url + "/events"
      val t = new Thread((() => {
        while !closed && notificationHandler != null do
          try
            val req = HttpRequest.newBuilder()
              .uri(URI.create(eventsUrl))
              .header("Accept", "text/event-stream")
              .GET()
              .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if resp.statusCode() == 200 then
              val reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), "UTF-8"))
              // Minimal SSE parse: collect `data: ...` lines until blank
              // line, concat, dispatch as one JSON-RPC frame.  Event
              // names (`event: foo`) are ignored — we only use `data:`.
              val buf = new StringBuilder()
              var line: String | Null = null
              while !closed && { line = reader.readLine(); line != null } do
                if line.isEmpty then
                  if buf.nonEmpty then
                    val h2 = notificationHandler
                    if h2 != null then
                      JsonRpc.parse(buf.toString()) match
                        case Right(JsonRpc.Message.Notification(m, p)) =>
                          try h2(m, p) catch case _: Throwable => ()
                        case _ => ()
                    buf.clear()
                else if line.startsWith("data: ") then
                  if buf.nonEmpty then buf.append('\n')
                  buf.append(line.substring(6))
                // else: ignore other SSE fields (event:, id:, retry:, comments)
          catch case _: Throwable =>
            try Thread.sleep(1_000L) catch case _: InterruptedException => Thread.currentThread().interrupt()
      }): Runnable, s"mcp-http-sse-reader")
      t.setDaemon(true); t.start()
      sseThread = t

  /** Send a request and block until the HTTP response arrives. */
  def request(method: String, params: ujson.Value, customTimeoutMs: Long = 0L): Either[JsonRpc.Error, ujson.Value] =
    if closed then return Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, "client closed"))
    val id   = nextId.getAndIncrement()
    val body = JsonRpc.encodeRequest(method, params, id)
    val rt   = if customTimeoutMs > 0 then customTimeoutMs else timeoutMs
    pending.put(id, java.lang.Boolean.TRUE)
    try
      val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofMillis(rt))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
          s"HTTP ${resp.statusCode()}: ${resp.body().take(200)}"))
      else
        JsonRpc.parse(resp.body()) match
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

  /** Fire-and-forget notification — POSTs the frame and ignores the
   *  HTTP response body. */
  def notify(method: String, params: ujson.Value): Unit =
    if closed then return
    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(JsonRpc.encodeNotification(method, params)))
      .build()
    try client.send(req, HttpResponse.BodyHandlers.discarding())
    catch case _: Throwable => ()  // best-effort

  def close(): Unit =
    closed = true
    notificationHandler = null
    // Wait briefly for in-flight requests; HttpClient itself doesn't
    // need explicit shutdown on JDK 21+ (background threads are daemon).
    val deadline = java.lang.System.currentTimeMillis() + 200L
    while !pending.isEmpty && java.lang.System.currentTimeMillis() < deadline do
      try Thread.sleep(10) catch case _: InterruptedException => Thread.currentThread().interrupt()
    // Interrupt the SSE reader so the blocking readLine returns.
    val t = sseThread; if t != null then try t.interrupt() catch case _: Throwable => ()

  def isClosed: Boolean = closed
