package ssc.plugin.httpfast

/** A compiled path router with literal, `:param`, and `*` wildcard segments.
  *
  * Routes are matched most-specific-first within a method: a literal segment beats a
  * `:param`, which beats a trailing `*`. Matching is allocation-light — it splits the
  * request path once and walks segments, capturing `:param` values into a small map only
  * when a route actually matches.
  *
  * Generic over the handler type `H` so the transport engine stays value-agnostic (the ssc
  * `Value` bridge lives in the host). Thread-safety: `add` happens during plugin install
  * (before `serve`); after that the table is read-only and matched concurrently. */
final class Router[H]:
  import Router.*

  private val routes = collection.mutable.ArrayBuffer.empty[Route[H]]
  @volatile private var frozen = false

  /** Register a route. `method` is upper-cased; `path` is split into segments. */
  def add(method: String, path: String, handler: H): Unit =
    if frozen then throw new IllegalStateException("route registration after serve is not supported")
    routes += Route(method.toUpperCase(java.util.Locale.ROOT), compile(path), handler)

  /** Freeze the table (called once before serving); further `add` throws. */
  def freeze(): Unit = frozen = true

  def isEmpty: Boolean = routes.isEmpty

  /** Find the best matching handler for `method`+`path`, with captured path params.
    * Returns `None` if no route matches (→ 404). A method mismatch on an otherwise
    * matching path still yields `None` here; callers may distinguish 404 vs 405 via
    * [[hasPath]]. */
  def find(method: String, path: String): Option[Match[H]] =
    val m = method.toUpperCase(java.util.Locale.ROOT)
    val segs = splitPath(path)
    var best: Match[H] | Null = null
    var bestScore = Int.MinValue
    var i = 0
    while i < routes.length do
      val r = routes(i)
      if r.method == m then
        matchRoute(r, segs) match
          case null =>
          case params =>
            val score = specificity(r.pattern)
            if score > bestScore then
              bestScore = score
              best = Match(r.handler, params)
      i += 1
    if best == null then None else Some(best.nn)

  /** Does ANY route (any method) match this path? Used to answer 405 vs 404. */
  def hasPath(path: String): Boolean =
    val segs = splitPath(path)
    routes.exists(r => matchRoute(r, segs) != null)

  /** Methods registered for a path (for a 405 `Allow` header). */
  def allowedMethods(path: String): Set[String] =
    val segs = splitPath(path)
    routes.iterator.filter(r => matchRoute(r, segs) != null).map(_.method).toSet

  private def compile(path: String): Vector[Seg] =
    splitPath(path).map { s =>
      if s.startsWith(":") && s.length > 1 then Param(s.substring(1))
      else if s == "*" then Wild
      else Lit(s)
    }

  /** Returns captured params (possibly empty) on match, or `null` on no-match. */
  private def matchRoute(r: Route[H], segs: Vector[String]): Map[String, String] | Null =
    val pat = r.pattern
    // A trailing `*` matches the remaining segments (including none).
    val hasWild = pat.nonEmpty && pat.last == Wild
    if !hasWild && pat.length != segs.length then return null
    if hasWild && segs.length < pat.length - 1 then return null
    var params: Map[String, String] = Map.empty
    var i = 0
    val n = if hasWild then pat.length - 1 else pat.length
    while i < n do
      pat(i) match
        case Lit(s)   => if segs(i) != s then return null
        case Param(k) => params = params.updated(k, segs(i))
        case Wild     => // unreachable in this range
      i += 1
    params

object Router:
  final case class Match[H](handler: H, params: Map[String, String])

  private sealed trait Seg
  private final case class Lit(s: String) extends Seg
  private final case class Param(name: String) extends Seg
  private case object Wild extends Seg

  private final case class Route[H](method: String, pattern: Vector[Seg], handler: H)

  /** Higher = more specific: literals weigh most, params less, a wildcard least. */
  private def specificity(pat: Vector[Seg]): Int =
    var score = 0
    var i = 0
    while i < pat.length do
      pat(i) match
        case _: Lit   => score += 100
        case _: Param => score += 10
        case Wild     => score -= 1000
      i += 1
    score

  /** Split a path into segments, dropping the leading `/` and any empty segments
    * ("/a//b/" → ["a","b"], "/" → []). */
  private[httpfast] def splitPath(path: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var i = 0
    val n = path.length
    while i < n do
      // skip slashes
      while i < n && path.charAt(i) == '/' do i += 1
      if i < n then
        val start = i
        while i < n && path.charAt(i) != '/' do i += 1
        out += path.substring(start, i)
    out.result()
