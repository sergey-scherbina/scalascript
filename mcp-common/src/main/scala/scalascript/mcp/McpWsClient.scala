package scalascript.mcp

import java.net.URI
import java.net.http.{HttpClient, WebSocket as JWs}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{
  CompletableFuture, ConcurrentHashMap, CountDownLatch,
  LinkedBlockingQueue, TimeUnit
}

/** WebSocket transport for `mcpConnect(Transport.Ws(url))` on the
 *  interpreter (Phase 2 follow-up).  Symmetric bidirectional channel —
 *  client sends JSON-RPC frames as text WS messages; server replies
 *  on the same socket.
 *
 *  Threading model — mirrors `McpClientCore`:
 *
 *    - The JDK `HttpClient` callback thread parses each incoming frame
 *      and pushes responses into the per-id `BlockingQueue` so the
 *      caller's `request(...)` unblocks.
 *    - `request(method, params, timeoutMs)` posts a frame via
 *      `ws.sendText(...)` and blocks the calling thread on the queue.
 *    - `notify(...)` is fire-and-forget.
 *    - `close()` initiates a 1000 (normal) close frame and unblocks
 *      every pending caller with a synthetic error response. */
class McpWsClient(url: String, timeoutMs: Long):

  private val nextId  = AtomicLong(1L)
  private val pending = ConcurrentHashMap[Long, LinkedBlockingQueue[JsonRpc.Message.Response]]()
  @volatile private var closed: Boolean = false
  @volatile private var notificationHandler: ((String, ujson.Value) => Unit) | Null = null
  @volatile private var requestHandler: ((String, ujson.Value) => ujson.Value) | Null = null

  /** Register a callback for server-initiated notifications (`notify` method
   *  frames with no id).  Same shape / semantics as
   *  `McpClientCore.setNotificationHandler`.  Pass `null` to unregister. */
  def setNotificationHandler(h: ((String, ujson.Value) => Unit) | Null): Unit =
    notificationHandler = h

  /** Register a callback for server-initiated requests (e.g.
   *  `sampling/createMessage`).  Handler returns the result value;
   *  exceptions become JSON-RPC error responses sent back via the
   *  WS writer so the server's pending map unblocks. */
  def setRequestHandler(h: ((String, ujson.Value) => ujson.Value) | Null): Unit =
    requestHandler = h

  private val textBuf  = new StringBuilder()
  private val handshakeLatch = CountDownLatch(1)
  @volatile private var handshakeError: Throwable | Null = null

  private val listener: JWs.Listener = new JWs.Listener:
    override def onOpen(ws: JWs): Unit =
      handshakeLatch.countDown()
      ws.request(1)

    override def onText(ws: JWs, data: CharSequence, last: Boolean): CompletableFuture[?] =
      textBuf.append(data)
      if last then
        val msg = textBuf.toString(); textBuf.setLength(0)
        dispatchInboundLine(msg)
      ws.request(1)
      CompletableFuture.completedFuture(null)

    override def onClose(ws: JWs, statusCode: Int, reason: String): CompletableFuture[?] =
      closeAllPending(s"WebSocket closed: $statusCode $reason")
      closed = true
      CompletableFuture.completedFuture(null)

    override def onError(ws: JWs | Null, error: Throwable): Unit =
      // Surface handshake-time errors via the latch path; runtime errors
      // also drain pending callers so request() returns instead of hanging.
      if handshakeLatch.getCount > 0 then
        handshakeError = error
        handshakeLatch.countDown()
      closeAllPending(s"WebSocket error: ${error.getMessage}")
      closed = true

  private val httpClient = HttpClient.newBuilder().build()
  private val webSocket: JWs =
    val builder = httpClient.newWebSocketBuilder()
    val fut = builder.buildAsync(URI.create(url), listener)
    val ws = fut.get(timeoutMs, TimeUnit.MILLISECONDS)
    handshakeLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    if handshakeError != null then
      throw RuntimeException(s"McpWsClient handshake failed: ${handshakeError.getMessage}")
    ws

  /** One inbound line from the WS — could be a response, notification, or
   *  server-initiated request.  Responses pair with pending requests
   *  by id; notifications dispatch to the notification handler;
   *  requests dispatch to the request handler and the result/error
   *  is sent back via the WS writer. */
  private def dispatchInboundLine(line: String): Unit =
    JsonRpc.parse(line) match
      case Right(resp @ JsonRpc.Message.Response(idJson, _, _)) =>
        idJson.numOpt.map(_.toLong) match
          case Some(id) =>
            val q = pending.get(id)
            if q != null then q.offer(resp)
          case None => ()
      case Right(JsonRpc.Message.Notification(method, params)) =>
        val h = notificationHandler
        if h != null then try h(method, params) catch case _: Throwable => ()
      case Right(JsonRpc.Message.Request(method, params, id)) =>
        val h = requestHandler
        val frame =
          if h != null then
            try
              val result = h(method, params)
              JsonRpc.encodeResult(id, result).stripSuffix("\n")
            catch case e: Throwable =>
              JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError,
                Option(e.getMessage).getOrElse(e.getClass.getSimpleName)).stripSuffix("\n")
          else
            JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound,
              s"client has no handler for server-initiated method: $method").stripSuffix("\n")
        try webSocket.sendText(frame, true)
        catch case _: Throwable => ()
      case _ => ()

  private def closeAllPending(message: String): Unit =
    pending.values().forEach { q =>
      q.offer(JsonRpc.Message.Response(
        ujson.Null,
        None,
        Some(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, message))
      ))
    }
    pending.clear()

  /** Send a request and block until the response arrives. */
  def request(method: String, params: ujson.Value, customTimeoutMs: Long = 0L): Either[JsonRpc.Error, ujson.Value] =
    if closed then return Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, "client closed"))
    val id   = nextId.getAndIncrement()
    val frame = JsonRpc.encodeRequest(method, params, id).stripSuffix("\n")
    val q     = LinkedBlockingQueue[JsonRpc.Message.Response]()
    pending.put(id, q)
    try
      val sendFut = webSocket.sendText(frame, true)
      // sendText returns CompletableFuture<WebSocket>; wait briefly for
      // it so we surface backpressure errors as a request failure
      // instead of silently dropping the frame.
      sendFut.get(2_000L, TimeUnit.MILLISECONDS)
      val rt   = if customTimeoutMs > 0 then customTimeoutMs else timeoutMs
      val resp = q.poll(rt, TimeUnit.MILLISECONDS)
      if resp == null then
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, s"request '$method' timed out after ${rt}ms"))
      else
        resp.error match
          case Some(e) => Left(e)
          case None    => Right(resp.result.getOrElse(ujson.Null))
    catch
      case e: Throwable =>
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    finally pending.remove(id)

  def notify(method: String, params: ujson.Value): Unit =
    if closed then return
    val frame = JsonRpc.encodeNotification(method, params).stripSuffix("\n")
    try webSocket.sendText(frame, true)
    catch case _: Throwable => ()  // best-effort

  def close(): Unit =
    if !closed then
      closed = true
      try webSocket.sendClose(JWs.NORMAL_CLOSURE, "client close").get(1_000L, TimeUnit.MILLISECONDS)
      catch case _: Throwable => ()
      closeAllPending("client closed")

  def isClosed: Boolean = closed
