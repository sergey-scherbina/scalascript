package scalascript.server

import scalascript.interpreter.{Interpreter, Value}

/** Global WebSocket route table — populated by `onWebSocket(path) { ws => … }`
 *  calls inside the running interpreter and consulted by the NIO proxy on each
 *  HTTP `Upgrade: websocket` request.
 *
 *  Parallel to [[Routes]] but with simpler semantics: no method (WS handshake
 *  is always a GET), and the handler receives a `WebSocket` value rather than
 *  a `Request`.  A single in-process registry is intentional, same rationale
 *  as the REST table.
 */
object WsRoutes:

  final case class Entry(
      path:        String,
      pathPattern: List[Routes.Segment],
      handler:     Value,
      interpreter: Interpreter,
      /** Allow-list of `Origin` headers the upgrade is permitted from.
       *  Empty list = no restriction (any origin accepted, including
       *  none).  Matching is exact-string against the value of the
       *  request's `Origin:` header. */
      origins:     List[String] = Nil,
      /** Subprotocols this route understands (RFC 6455 §1.9).  The
       *  server picks the first one that also appears in the client's
       *  `Sec-WebSocket-Protocol` request header and echoes it in
       *  the 101 response.  Empty list = no negotiation; non-empty
       *  list with no client match = upgrade refused with 400. */
      protocols:   List[String] = Nil,
      /** Per-route active-connection cap.  0 (default) = no limit;
       *  composes with the process-wide `setMaxWsConnections` cap —
       *  both must permit the upgrade.  Refused upgrades return 503.
       *  See [[activeCount]] for the live counter. */
      maxConnections: Int = 0,
      /** Live count of WebSockets currently registered against this
       *  entry.  Incremented inside [[tryReserve]] when both
       *  process-wide and per-route caps allow the upgrade; decremented
       *  by `WsConnection.closeNow` via the callback wired in
       *  `WsProxy.tryUpgrade`. */
      activeCount: java.util.concurrent.atomic.AtomicInteger =
                   java.util.concurrent.atomic.AtomicInteger(0)
  ):
    /** Check-then-increment for the per-route cap.  Returns true if
     *  the caller may proceed.  Rollback on failure so the counter
     *  can't drift past the cap under contention. */
    def tryReserve(): Boolean =
      if maxConnections <= 0 then true
      else
        val after = activeCount.incrementAndGet()
        if after > maxConnections then
          activeCount.decrementAndGet()
          false
        else true

    def release(): Unit =
      if maxConnections > 0 then activeCount.decrementAndGet()

  private val entries = scala.collection.mutable.ArrayBuffer.empty[Entry]

  def clear(): Unit = entries.clear()

  def register(
      path:           String,
      handler:        Value,
      interp:         Interpreter,
      origins:        List[String] = Nil,
      protocols:      List[String] = Nil,
      maxConnections: Int          = 0
  ): Unit =
    entries += Entry(path, parsePath(path), handler, interp, origins, protocols, maxConnections)

  def all: List[Entry] = entries.toList

  /** Match a path against the registered WS routes.  Returns the first entry
   *  whose pattern matches together with any captured `:param` bindings. */
  def matchPath(path: String): Option[(Entry, Map[String, String])] =
    val segments = splitSegments(path)
    entries.iterator
      .flatMap(e => matchSegments(e.pathPattern, segments).map(params => (e, params)))
      .nextOption()

  // ─── Path parsing — shared logic with REST routes ──────────────────

  private def parsePath(path: String): List[Routes.Segment] =
    splitSegments(path).map { seg =>
      if seg.startsWith(":") then Routes.Segment.Capture(seg.tail)
      else Routes.Segment.Literal(seg)
    }

  private def splitSegments(path: String): List[String] =
    path.split('/').toList.filter(_.nonEmpty)

  private def matchSegments(
      pattern: List[Routes.Segment],
      actual:  List[String]
  ): Option[Map[String, String]] =
    if pattern.length != actual.length then None
    else
      val params = scala.collection.mutable.Map.empty[String, String]
      val ok = pattern.zip(actual).forall {
        case (Routes.Segment.Literal(p), a)    => p == a
        case (Routes.Segment.Capture(name), a) => params(name) = a; true
      }
      if ok then Some(params.toMap) else None
