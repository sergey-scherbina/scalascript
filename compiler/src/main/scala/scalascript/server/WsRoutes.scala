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
      interpreter: Interpreter
  )

  private val entries = scala.collection.mutable.ArrayBuffer.empty[Entry]

  def clear(): Unit = entries.clear()

  def register(path: String, handler: Value, interp: Interpreter): Unit =
    entries += Entry(path, parsePath(path), handler, interp)

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
