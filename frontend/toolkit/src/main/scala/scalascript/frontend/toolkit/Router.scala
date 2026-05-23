package scalascript.frontend.toolkit

import scalascript.frontend.{Signal, View, AttrValue, EventHandler}

/** v1.18 / Phase B — client-side routing layer for the toolkit.
 *
 *  A `RouterNode` holds a list of `Route`s plus a `Signal[String]`
 *  carrying the current path.  Lowering walks the routes in order
 *  and renders the first whose pattern matches `currentPath()`.
 *  Path patterns use the conventional `:name` placeholder syntax
 *  (`/users/:id`); the extracted params are passed to the route's
 *  `render` function as `Map[String, String]`.
 *
 *  Trailing slashes are normalised — `/foo/` matches `/foo` — and
 *  query strings are stripped before matching, so `/users?x=1`
 *  matches the `/users` pattern.
 *
 *  `LinkNode` lowers to a regular `<a href="...">`.  When supplied
 *  with the same `Signal[String]` the router watches, clicking the
 *  link writes the new path into the signal AND prevents the
 *  browser's default full-page navigation (SPA behaviour).  Without
 *  a `currentPath`, the link behaves like a plain anchor — useful
 *  for genuinely external URLs.
 *
 *  See `docs/frontend-toolkit-spec.md` "Layer 7 — Navigation +
 *  routing" for the intended user-facing surface. */

/** Single route — a path pattern + a renderer.  `path` may contain
 *  `:param` placeholders (e.g. `/users/:id`); the extracted map
 *  is handed to `render` at lower time. */
final case class Route(
  path:   String,
  render: Map[String, String] => ToolkitNode
)

/** Container holding the route table.  `currentPath` is reactive —
 *  the surrounding backend re-renders / patches the router subtree
 *  when the signal changes (the toolkit's lowering is pure; the
 *  reactivity is the backend's job, same as every other Signal
 *  binding in the toolkit). */
final case class RouterNode(
  routes:      List[Route],
  currentPath: Signal[String],
  notFound:    ToolkitNode
) extends ToolkitNode

object RouterNode:
  def lower(n: RouterNode, theme: Theme): View[?] =
    val path = n.currentPath()
    val matched = n.routes.iterator
      .map(r => Router.pathMatches(r.path, path).map(r -> _))
      .collectFirst { case Some(pair) => pair }
    matched match
      case Some((route, params)) => Toolkit.lower(route.render(params), theme)
      case None                  => Toolkit.lower(n.notFound, theme)

/** Navigation link.  When `currentPath` is `Some(sig)`, clicking
 *  writes `to` into the signal AND prevents the browser's default
 *  navigation, giving SPA behaviour.  When `None`, the link is a
 *  plain `<a href="...">` — the browser handles the navigation. */
final case class LinkNode(
  to:          String,
  child:       ToolkitNode,
  currentPath: Option[Signal[String]] = None
) extends ToolkitNode

object LinkNode:
  def lower(n: LinkNode, theme: Theme): View[?] =
    val attrs = Map[String, AttrValue]("href" -> AttrValue.Str(n.to))
    val events: Map[String, EventHandler] = n.currentPath match
      case Some(sig) =>
        // Pass the raw event through `WithEvent`; the backend's
        // adapter handles `preventDefault` when the user-supplied
        // lambda accepts it.  Our lambda just writes the signal.
        Map("click" -> EventHandler.WithEvent { _ => sig.set(n.to) })
      case None =>
        Map.empty
    View.Element(
      tag      = "a",
      attrs    = attrs,
      events   = events,
      children = Seq(Toolkit.lower(n.child, theme))
    )

/** Path-matching helpers.  Pure functions — same inputs always
 *  produce the same outputs, no DOM / `window.location` access. */
object Router:
  /** Match `actualPath` against a pattern containing optional
   *  `:name` placeholders.  Returns `Some(params)` on success
   *  (empty map if the pattern has no placeholders) and `None`
   *  on miss.
   *
   *  Normalisations:
   *   - trailing slashes are stripped from both pattern + actual
   *     (so `/foo/` matches `/foo`)
   *   - query strings are stripped from `actualPath` (so
   *     `/users?x=1` matches `/users`) */
  def pathMatches(pattern: String, actualPath: String): Option[Map[String, String]] =
    val cleanActual = stripQuery(stripTrailingSlash(actualPath))
    val cleanPattern = stripTrailingSlash(pattern)
    val patternParts = splitPath(cleanPattern)
    val actualParts  = splitPath(cleanActual)
    if patternParts.length != actualParts.length then None
    else
      val params = scala.collection.mutable.Map.empty[String, String]
      var i = 0
      var ok = true
      while ok && i < patternParts.length do
        val p = patternParts(i)
        val a = actualParts(i)
        if p.startsWith(":") then
          params(p.drop(1)) = a
        else if p != a then
          ok = false
        i += 1
      if ok then Some(params.toMap) else None

  private def stripTrailingSlash(p: String): String =
    if p.length > 1 && p.endsWith("/") then p.dropRight(1) else p

  private def stripQuery(p: String): String =
    val q = p.indexOf('?')
    if q >= 0 then p.substring(0, q) else p

  /** Split `/a/b/c` into `Array("a", "b", "c")`.  The leading slash
   *  is dropped; empty segments (e.g. from a bare `"/"`) collapse to
   *  the empty array so two `"/"` paths compare equal. */
  private def splitPath(p: String): Array[String] =
    val trimmed = if p.startsWith("/") then p.drop(1) else p
    if trimmed.isEmpty then Array.empty else trimmed.split("/", -1)
