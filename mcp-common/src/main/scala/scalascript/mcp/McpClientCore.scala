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

  // v1.17.x — server→client notification dispatch.  When the inbound
  // line is a JSON-RPC notification (no id, no result) and a handler
  // is registered, the reader thread invokes it with the parsed
  // (method, params).  Server-initiated *requests* (those with an id
  // expecting a reply, e.g. bidirectional sampling) are still dropped
  // — Phase 1 scope.
  @volatile private var notificationHandler: ((String, ujson.Value) => Unit) | Null = null

  /** Register a callback for server-initiated notifications.  Called
   *  on the reader thread for every inbound notification frame; the
   *  handler should be cheap and non-blocking.  Pass `null` to
   *  unregister.  Only the most recent handler is invoked. */
  def setNotificationHandler(h: ((String, ujson.Value) => Unit) | Null): Unit =
    notificationHandler = h

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

  /** Called by the reader thread for every inbound line.  Returns true
   *  if the line was routed (response paired with pending request, or
   *  notification dispatched to a registered handler), false if it
   *  couldn't be matched (stray response, server-initiated request,
   *  or notification with no handler registered). */
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
