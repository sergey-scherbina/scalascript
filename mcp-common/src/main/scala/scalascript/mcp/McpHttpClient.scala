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

  // Provided for API parity with McpClientCore — HTTP transport is
  // request/response so there's no asynchronous dispatch table needed.
  // We still track pending counts so close() can wait briefly for
  // in-flight requests to complete (best-effort).
  private val pending = ConcurrentHashMap[Long, java.lang.Boolean]()

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
    // Wait briefly for in-flight requests; HttpClient itself doesn't
    // need explicit shutdown on JDK 21+ (background threads are daemon).
    val deadline = java.lang.System.currentTimeMillis() + 200L
    while !pending.isEmpty && java.lang.System.currentTimeMillis() < deadline do
      try Thread.sleep(10) catch case _: InterruptedException => Thread.currentThread().interrupt()

  def isClosed: Boolean = closed
