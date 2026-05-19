package scalascript.interpreter.mcp

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
   *  if the line was routed to a waiting caller, false if it was a
   *  server-initiated request/notification (ignored by Phase 1 — we
   *  don't yet support bidirectional sampling). */
  def dispatchResponse(line: String): Boolean =
    JsonRpc.parse(line) match
      case Right(resp @ JsonRpc.Message.Response(idJson, _, _)) =>
        idJson.numOpt.map(_.toLong) match
          case Some(id) =>
            val q = pending.get(id)
            if q != null then { q.offer(resp); true } else false
          case None => false
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
