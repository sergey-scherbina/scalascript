package scalascript.server

import scalascript.interpreter.{Interpreter, Value}

/** Global REST route table — populated by `route(method, path)(handler)` calls
 *  and `mount(method, path, file)` calls inside the running interpreter and
 *  consulted by [[WebServer]] on each request before falling back to file rendering.
 *
 *  A single in-process registry is intentional: a `.ssc` document is a
 *  single program, and routes belong to that program for as long as the
 *  interpreter (or compiled server) keeps the JVM alive.  Subsequent
 *  invocations call [[clear]] before re-running.
 *
 *  Backed by `LinkedHashMap[(method, rawPath), Entry]` so that:
 *  - `register` with the same `(method, path)` key replaces in place
 *    (idempotent — e.g. hot-reload via `:reload file.ssc`).
 *  - Iteration order is stable (insertion order), matching the behaviour
 *    callers had with the old `ArrayBuffer`.
 */
object Routes extends RouteRegistry:

  /** A single registered route plus the interpreter session that owns it.
   *  We need the interpreter to invoke the handler closure with the right
   *  evaluation rules and global environment.
   *
   *  @param source   Canonical absolute path of the handler file.  `None`
   *                  for handlers registered via `route()` (inline closures).
   *                  Set by `mount()` to enable `removeBySource` / hot-reload.
   *  @param mountCtx Extra context map supplied by the `mount(method, path, file, ctx)`
   *                  overload.  Passed as the second argument to 2-arg handlers.
   *  @param style    How the route was registered: `"route"` (via `route()` intrinsic
   *                  or inline `:mount`), `"mount"` (via `:mount file.ssc`), or
   *                  `"load"` (via `:load file.ssc`).  Used by `:reload` to pick
   *                  the right re-registration path.
   */
  final case class Entry(
      method:      String,
      path:        String,
      pathPattern: List[Segment],
      handler:     Value,
      interpreter: Interpreter,
      source:      Option[String]        = None,
      mountCtx:    Map[String, Value]    = Map.empty,
      style:       String                = "route"
  )

  sealed trait Segment
  object Segment:
    case class Literal(s: String) extends Segment
    case class Capture(name: String) extends Segment

  private val entries      = scala.collection.mutable.LinkedHashMap.empty[(String, String), Entry]
  private val _middlewares = scala.collection.mutable.ArrayBuffer.empty[(Value, Interpreter)]

  def clear(): Unit = { entries.clear(); _middlewares.clear() }

  /** Register a route.  Replaces any existing entry with the same
   *  `(method.toUpperCase, path)` key (idempotent mount). */
  def register(
      method:   String,
      path:     String,
      handler:  Value,
      interp:   Interpreter,
      source:   Option[String]     = None,
      mountCtx: Map[String, Value] = Map.empty,
      style:    String             = "route"
  ): Unit =
    val m   = method.toUpperCase
    val key = (m, path)
    entries(key) = Entry(m, path, parsePath(path), handler, interp, source, mountCtx, style)

  /** Remove the entry with the given `(method.toUpperCase, path)` key.
   *  Returns `true` if an entry was removed, `false` if it was not found. */
  def remove(method: String, path: String): Boolean =
    val key = (method.toUpperCase, path)
    entries.remove(key).isDefined

  /** Remove all entries whose `source` matches `absPath`.
   *  Used by hot-reload (`:reload file.ssc`) to evict stale handlers
   *  before re-mounting. */
  def removeBySource(absPath: String): Unit =
    val toRemove = entries.collect { case (k, e) if e.source.contains(absPath) => k }.toList
    toRemove.foreach(entries.remove)

  def addMiddleware(fn: Value, interp: Interpreter): Unit = _middlewares += ((fn, interp))

  def middlewares: List[(Value, Interpreter)] = _middlewares.toList

  def all: List[Entry] = entries.values.toList

  /** Returns the first matching entry and the captured path parameters. */
  def matchRequest(method: String, path: String): Option[(Entry, Map[String, String])] =
    val m        = method.toUpperCase
    val segments = splitSegments(path)
    entries.valuesIterator
      .filter(_.method == m)
      .flatMap(e => matchSegments(e.pathPattern, segments).map(params => (e, params)))
      .nextOption()

  // ─── Path parsing ───────────────────────────────────────────────────

  private def parsePath(path: String): List[Segment] =
    splitSegments(path).map { seg =>
      if seg.startsWith(":") then Segment.Capture(seg.tail)
      else Segment.Literal(seg)
    }

  private def splitSegments(path: String): List[String] =
    path.split('/').toList.filter(_.nonEmpty)

  private def matchSegments(
      pattern: List[Segment],
      actual:  List[String]
  ): Option[Map[String, String]] =
    if pattern.length != actual.length then None
    else
      val params = scala.collection.mutable.Map.empty[String, String]
      val ok = pattern.zip(actual).forall {
        case (Segment.Literal(p), a)     => p == a
        case (Segment.Capture(name), a)  => params(name) = a; true
      }
      if ok then Some(params.toMap) else None
