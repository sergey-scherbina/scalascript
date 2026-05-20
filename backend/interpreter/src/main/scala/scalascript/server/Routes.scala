package scalascript.server

import scalascript.interpreter.{Interpreter, Value}

/** Global REST route table — populated by `route(method, path)(handler)` calls
 *  inside the running interpreter and consulted by [[WebServer]] on each
 *  request before falling back to file rendering.
 *
 *  A single in-process registry is intentional: a `.ssc` document is a
 *  single program, and routes belong to that program for as long as the
 *  interpreter (or compiled server) keeps the JVM alive.  Subsequent
 *  invocations call [[clear]] before re-running.
 */
object Routes:

  /** A single registered route plus the interpreter session that owns it.
   *  We need the interpreter to invoke the handler closure with the right
   *  evaluation rules and global environment. */
  final case class Entry(
      method:      String,
      path:        String,
      pathPattern: List[Segment],
      handler:     Value,
      interpreter: Interpreter
  )

  sealed trait Segment
  object Segment:
    case class Literal(s: String) extends Segment
    case class Capture(name: String) extends Segment

  private val entries     = scala.collection.mutable.ArrayBuffer.empty[Entry]
  private val _middlewares = scala.collection.mutable.ArrayBuffer.empty[(Value, Interpreter)]

  def clear(): Unit = { entries.clear(); _middlewares.clear() }

  def register(method: String, path: String, handler: Value, interp: Interpreter): Unit =
    entries += Entry(method.toUpperCase, path, parsePath(path), handler, interp)

  def addMiddleware(fn: Value, interp: Interpreter): Unit = _middlewares += ((fn, interp))

  def middlewares: List[(Value, Interpreter)] = _middlewares.toList

  def all: List[Entry] = entries.toList

  /** Returns the first matching entry and the captured path parameters. */
  def matchRequest(method: String, path: String): Option[(Entry, Map[String, String])] =
    val m = method.toUpperCase
    val segments = splitSegments(path)
    entries.iterator
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
