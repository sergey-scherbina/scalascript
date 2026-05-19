package scalascript.mcp

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

/** Transport-agnostic MCP client: tracks pending JSON-RPC requests by
 *  id, pushes outgoing frames through a writer, and routes inbound
 *  responses back to the waiting caller via a per-id BlockingQueue.
 *
 *  The reader thread (caller's responsibility) calls `dispatchResponse`
 *  for every line it pulls off the wire.  The calling thread calls
 *  `request(method, params, timeoutMs)` and blocks on the queue until
 *  the reader pushes the matching response (or the timeout fires).
 *
 *  Cleanup: `close()` interrupts every pending request with a fake
 *  error response so they unblock cleanly.  The actual transport
 *  shutdown (Process.destroy, socket close, …) is the caller's job. */
class McpClientCore(write: String => Unit):

  private val nextId  = AtomicLong(1L)
  private val pending = ConcurrentHashMap[Long, LinkedBlockingQueue[JsonRpc.Message.Response]]()
  @volatile private var closed = false

  // v1.17.x — server→client notification dispatch (no id).
  @volatile private var notificationHandler: ((String, ujson.Value) => Unit) | Null = null
  // v1.17.x — server→client request dispatch (with id, bidirectional
  // sampling).  Handler returns the result value; exceptions become
  // JSON-RPC error responses so the server-side pending map unblocks.
  @volatile private var requestHandler: ((String, ujson.Value) => ujson.Value) | Null = null

  /** Register a callback for server-initiated notifications.  Called
   *  on the reader thread for every inbound notification frame; the
   *  handler should be cheap and non-blocking.  Pass `null` to
   *  unregister.  Only the most recent handler is invoked. */
  def setNotificationHandler(h: ((String, ujson.Value) => Unit) | Null): Unit =
    notificationHandler = h

  /** Register a callback for server-initiated requests
   *  (e.g. `sampling/createMessage`).  The handler returns the
   *  result ujson value; the dispatcher serialises a Response back
   *  through the writer.  Exceptions from the handler convert to a
   *  JSON-RPC error response with `InternalError` code and the
   *  exception's message — the server-side pending map unblocks
   *  with a Left(error) instead of hanging.  Pass `null` to
   *  unregister; incoming requests then receive MethodNotFound. */
  def setRequestHandler(h: ((String, ujson.Value) => ujson.Value) | Null): Unit =
    requestHandler = h

  /** Send a request and block until the matching response arrives or
   *  the timeout fires.  Returns `Right(result)` on success, `Left(error)`
   *  on protocol error / timeout / handler-side McpError. */
  def request(method: String, params: ujson.Value, timeoutMs: Long): Either[JsonRpc.Error, ujson.Value] =
    if closed then return Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, "client closed"))
    val id = nextId.getAndIncrement()
    val q  = LinkedBlockingQueue[JsonRpc.Message.Response]()
    pending.put(id, q)
    try
      write(JsonRpc.encodeRequest(method, params, id))
      val resp = q.poll(timeoutMs, TimeUnit.MILLISECONDS)
      if resp == null then
        Left(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, s"request '$method' timed out after ${timeoutMs}ms"))
      else
        resp.error match
          case Some(e) => Left(e)
          case None    => Right(resp.result.getOrElse(ujson.Null))
    finally pending.remove(id)

  /** Send a notification (no response expected). */
  def notify(method: String, params: ujson.Value): Unit =
    if !closed then write(JsonRpc.encodeNotification(method, params))

  /** Called by the reader thread for every inbound line.  Routes:
   *    - Response frames → pending-id map for the matching `request()` caller
   *    - Notification frames → setNotificationHandler if registered
   *    - Request frames     → setRequestHandler if registered (reply sent
   *                            back via the writer); MethodNotFound otherwise
   *  Returns true when the line was successfully routed. */
  def dispatchResponse(line: String): Boolean =
    JsonRpc.parse(line) match
      case Right(resp @ JsonRpc.Message.Response(idJson, _, _)) =>
        idJson.numOpt.map(_.toLong) match
          case Some(id) =>
            val q = pending.get(id)
            if q != null then { q.offer(resp); true } else false
          case None => false
      case Right(JsonRpc.Message.Notification(method, params)) =>
        val h = notificationHandler
        if h != null then
          try { h(method, params); true }
          catch case _: Throwable => false  // handler exceptions don't kill the reader
        else false
      case Right(JsonRpc.Message.Request(method, params, id)) =>
        val h = requestHandler
        if h != null then
          try
            val result = h(method, params)
            write(JsonRpc.encodeResult(id, result))
            true
          catch case e: Throwable =>
            val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            write(JsonRpc.encodeError(id, JsonRpc.ErrorCode.InternalError, msg))
            true
        else
          // No handler — reply with MethodNotFound so the server's
          // pending map unblocks with a Left.
          write(JsonRpc.encodeError(id, JsonRpc.ErrorCode.MethodNotFound,
            s"client has no handler for server-initiated method: $method"))
          false
      case _ => false

  def close(): Unit =
    if !closed then
      closed = true
      // Unblock every pending caller with a synthetic error response
      // so they return from `request(...)` instead of hanging on
      // `BlockingQueue.poll`.
      pending.values().forEach { q =>
        q.offer(JsonRpc.Message.Response(
          ujson.Null,
          None,
          Some(JsonRpc.Error(JsonRpc.ErrorCode.InternalError, "client closed"))
        ))
      }
      pending.clear()

  def isClosed: Boolean = closed
