package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, ReactiveSignal}

/** v1.18 / Phase B — Frontend Toolkit Router tests.  Verifies the
 *  path-matching algorithm, the `RouterNode` first-match-wins
 *  dispatch, the `LinkNode` lowering (both SPA + plain anchor
 *  variants), and the Tk facade composition. */
class RouterTest extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // ─── pathMatches ───────────────────────────────────────────────

  test("pathMatches: exact static match returns Some(empty map)"):
    Router.pathMatches("/static/path", "/static/path") shouldBe Some(Map.empty)

  test("pathMatches: root path matches"):
    Router.pathMatches("/", "/") shouldBe Some(Map.empty)

  test("pathMatches: single param extracts"):
    Router.pathMatches("/users/:id", "/users/42") shouldBe Some(Map("id" -> "42"))

  test("pathMatches: multiple params extract"):
    Router.pathMatches("/orgs/:org/users/:id", "/orgs/acme/users/42") shouldBe
      Some(Map("org" -> "acme", "id" -> "42"))

  test("pathMatches: too few segments returns None"):
    Router.pathMatches("/users/:id", "/users") shouldBe None

  test("pathMatches: too many segments returns None"):
    Router.pathMatches("/users", "/users/42") shouldBe None

  test("pathMatches: literal segment mismatch returns None"):
    Router.pathMatches("/users/:id", "/admins/42") shouldBe None

  test("pathMatches: trailing slash on actual is normalised"):
    Router.pathMatches("/foo", "/foo/")         shouldBe Some(Map.empty)
    Router.pathMatches("/foo/:x", "/foo/bar/")  shouldBe Some(Map("x" -> "bar"))

  test("pathMatches: trailing slash on pattern is normalised"):
    Router.pathMatches("/foo/", "/foo") shouldBe Some(Map.empty)

  test("pathMatches: query string on actual is ignored"):
    Router.pathMatches("/users", "/users?x=1")        shouldBe Some(Map.empty)
    Router.pathMatches("/users/:id", "/users/42?q=x") shouldBe Some(Map("id" -> "42"))

  // ─── RouterNode lowering ───────────────────────────────────────

  test("Router renders the matching route's content"):
    val currentPath = new ReactiveSignal[String]("path", "/about")
    val node = RouterNode(
      routes = List(
        Route("/",      _ => TextNode("home")),
        Route("/about", _ => TextNode("about-page"))
      ),
      currentPath = currentPath,
      notFound    = TextNode("404")
    )
    Toolkit.lower(node, theme) match
      case View.Element("span", _, _, kids) =>
        kids.head match
          case View.TextNode(thunk) => thunk() shouldBe "about-page"
          case other                 => fail(s"got $other")
      case other => fail(s"got $other")

  test("Router renders notFound when nothing matches"):
    val currentPath = new ReactiveSignal[String]("path", "/missing")
    val node = RouterNode(
      routes = List(Route("/", _ => TextNode("home"))),
      currentPath = currentPath,
      notFound    = TextNode("not-found")
    )
    Toolkit.lower(node, theme).asInstanceOf[View.Element].children.head match
      case View.TextNode(thunk) => thunk() shouldBe "not-found"
      case other                 => fail(s"got $other")

  test("Router: first match wins (only one route renders)"):
    val rendered = scala.collection.mutable.ListBuffer.empty[String]
    val currentPath = new ReactiveSignal[String]("path", "/users/42")
    val node = RouterNode(
      routes = List(
        Route("/users/:id", params => { rendered += s"first-${params("id")}"; TextNode("first") }),
        Route("/users/:id", params => { rendered += s"second-${params("id")}"; TextNode("second") })
      ),
      currentPath = currentPath,
      notFound    = TextNode("404")
    )
    Toolkit.lower(node, theme)
    rendered.toList shouldBe List("first-42")

  test("Router: extracted params reach the render function"):
    val currentPath = new ReactiveSignal[String]("path", "/users/abc")
    val node = RouterNode(
      routes = List(
        Route("/users/:id", params => TextNode(s"id=${params("id")}"))
      ),
      currentPath = currentPath,
      notFound    = TextNode("404")
    )
    Toolkit.lower(node, theme).asInstanceOf[View.Element].children.head match
      case View.TextNode(thunk) => thunk() shouldBe "id=abc"
      case other                 => fail(s"got $other")

  // ─── LinkNode lowering ─────────────────────────────────────────

  test("Link without currentPath lowers to a plain <a href=...>"):
    val node = LinkNode("/about", TextNode("About"))
    Toolkit.lower(node, theme) match
      case View.Element("a", attrs, events, kids) =>
        attrs("href").asInstanceOf[AttrValue.Str].value shouldBe "/about"
        events shouldBe Map.empty
        kids.length shouldBe 1
      case other => fail(s"got $other")

  test("Link with currentPath: clicking updates the signal"):
    val currentPath = new ReactiveSignal[String]("path", "/")
    val node = LinkNode("/about", TextNode("About"), currentPath = Some(currentPath))
    Toolkit.lower(node, theme) match
      case View.Element("a", attrs, events, _) =>
        attrs("href").asInstanceOf[AttrValue.Str].value shouldBe "/about"
        events("click") match
          case EventHandler.WithEvent(action) =>
            // Pass any event-shaped value; the lambda ignores it
            // and writes the new path into the signal.
            action(())
            currentPath() shouldBe "/about"
          case other => fail(s"got $other")
      case other => fail(s"got $other")

  // ─── Tk facade ─────────────────────────────────────────────────

  test("Tk.router + Tk.route + Tk.link facade composition"):
    import Tk.*
    val currentPath = new ReactiveSignal[String]("path", "/users/7")
    val tree = router(currentPath, notFound = text("404"))(
      route("/")             { _      => text("home") },
      route("/users/:id")    { params => text(s"user-${params("id")}") },
      route("/about")        { _      => link("/", currentPath = Some(currentPath))(text("Home")) }
    )
    Toolkit.lower(tree, theme).asInstanceOf[View.Element].children.head match
      case View.TextNode(thunk) => thunk() shouldBe "user-7"
      case other                 => fail(s"got $other")

  test("Tk.link facade: clicking with currentPath writes the signal"):
    import Tk.*
    val currentPath = new ReactiveSignal[String]("path", "/")
    val node = link("/settings", currentPath = Some(currentPath))(text("Settings"))
    Toolkit.lower(node, theme) match
      case View.Element("a", _, events, _) =>
        events("click") match
          case EventHandler.WithEvent(action) =>
            action(())
            currentPath() shouldBe "/settings"
          case other => fail(s"got $other")
      case other => fail(s"got $other")

  // ─── Nested routers ────────────────────────────────────────────

  test("Nested routers: an outer Route can render an inner Router"):
    // The user navigates to `/section/users/9`.  The outer router
    // matches `/section/*-style` (we use `/section/:sub/:id` since
    // we don't have wildcard support) and the inner router would
    // re-match on the same currentPath — but here we model nesting
    // as "outer route renders inner router with a different table".
    val currentPath = new ReactiveSignal[String]("path", "/section/users/9")
    val inner = (_: Map[String, String]) =>
      RouterNode(
        routes = List(
          Route("/section/users/:id", innerParams => TextNode(s"section-user-${innerParams("id")}")),
          Route("/section/posts/:id", innerParams => TextNode(s"section-post-${innerParams("id")}"))
        ),
        currentPath = currentPath,
        notFound    = TextNode("inner-404")
      )
    val outer = RouterNode(
      routes = List(
        Route("/section/:sub/:id", inner)
      ),
      currentPath = currentPath,
      notFound    = TextNode("outer-404")
    )
    Toolkit.lower(outer, theme).asInstanceOf[View.Element].children.head match
      case View.TextNode(thunk) => thunk() shouldBe "section-user-9"
      case other                 => fail(s"got $other")
