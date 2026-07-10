package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, ReactiveSignal}

/** v1.18 Phase C — SSR (HTML stringifier) tests.  Verifies the
 *  `Ssr.renderToHtml` walker handles every View subtype, escapes
 *  hostile input, omits event handlers + falsy attrs, and snapshots
 *  Dynamic / Signal-bound values at render time. */
class SsrTest extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // ─── Basic shape ──────────────────────────────────────────────

  test("Element emits opening + closing tag with content"):
    Ssr.renderToHtml(View.Element("p",
      Map.empty, Map.empty,
      Seq(View.TextNode(() => "hi")))) shouldBe "<p>hi</p>"

  test("Void element emits self-closing form, no closing tag"):
    Ssr.renderToHtml(View.Element("br",
      Map.empty, Map.empty, Nil)) shouldBe "<br />"

  test("Void elements ignore children"):
    Ssr.renderToHtml(View.Element("img",
      Map("src" -> AttrValue.Str("a.png")), Map.empty,
      Seq(View.TextNode(() => "ignored")))) shouldBe
      "<img src=\"a.png\" />"

  test("Nested elements render recursively"):
    val tree = View.Element("div", Map.empty, Map.empty, Seq(
      View.Element("h1", Map.empty, Map.empty, Seq(View.TextNode(() => "Hi"))),
      View.Element("p",  Map.empty, Map.empty, Seq(View.TextNode(() => "body")))
    ))
    Ssr.renderToHtml(tree) shouldBe
      "<div><h1>Hi</h1><p>body</p></div>"

  // ─── Attributes ───────────────────────────────────────────────

  test("Str attribute emits quoted value"):
    val v = View.Element("a",
      Map("href" -> AttrValue.Str("/users")), Map.empty,
      Seq(View.TextNode(() => "Users")))
    Ssr.renderToHtml(v) shouldBe "<a href=\"/users\">Users</a>"

  test("Num attribute drops trailing .0 for integer values"):
    val v = View.Element("input",
      Map("min" -> AttrValue.Num(1.0), "step" -> AttrValue.Num(0.5)),
      Map.empty, Nil)
    val out = Ssr.renderToHtml(v)
    out should include ("min=\"1\"")
    out should include ("step=\"0.5\"")

  test("Bool(true) renders as bare attribute"):
    val v = View.Element("input",
      Map("type" -> AttrValue.Str("checkbox"),
          "checked" -> AttrValue.Bool(true)), Map.empty, Nil)
    Ssr.renderToHtml(v) should include (" checked")
    Ssr.renderToHtml(v) should not include ("checked=\"")

  test("Bool(false) is omitted entirely"):
    val v = View.Element("input",
      Map("checked" -> AttrValue.Bool(false)), Map.empty, Nil)
    Ssr.renderToHtml(v) should not include ("checked")

  test("Absent attribute is omitted"):
    val v = View.Element("input",
      Map("id" -> AttrValue.Absent), Map.empty, Nil)
    Ssr.renderToHtml(v) should not include ("id")

  test("Dynamic attribute snapshots the current value"):
    val sig = new ReactiveSignal[String]("name", "Alice")
    val v = View.Element("input",
      Map("value" -> AttrValue.Dynamic(() => sig())),
      Map.empty, Nil)
    Ssr.renderToHtml(v) should include ("value=\"Alice\"")
    sig.set("Bob")
    Ssr.renderToHtml(v) should include ("value=\"Bob\"")

  test("raw HTML sentinel emits trusted children and hides the sentinel attribute"):
    val v = View.Element("span",
      Map("style" -> AttrValue.Str("display:contents"),
          "data-ssc-raw-html" -> AttrValue.Str("<strong data-x=\"ok\">safe</strong>")),
      Map.empty,
      Seq(View.TextNode(() => "<em>ignored</em>")))
    val out = Ssr.renderToHtml(v)
    out shouldBe "<span style=\"display:contents\"><strong data-x=\"ok\">safe</strong></span>"
    out should not include ("data-ssc-raw-html")
    out should not include ("&lt;strong")
    out should not include ("ignored")

  // ─── Event handlers ───────────────────────────────────────────

  test("Event handlers are not emitted in HTML output"):
    val v = View.Element("button",
      Map.empty,
      Map("click" -> EventHandler.Simple(() => println("never"))),
      Seq(View.TextNode(() => "Click")))
    val out = Ssr.renderToHtml(v)
    out shouldBe "<button>Click</button>"
    out should not include ("onclick")
    out should not include ("on:click")

  // ─── Text nodes + escaping ────────────────────────────────────

  test("Text content with HTML special chars is escaped"):
    val v = View.Element("p", Map.empty, Map.empty,
      Seq(View.TextNode(() => "<script>alert('x')</script>")))
    val out = Ssr.renderToHtml(v)
    out should not include ("<script>")
    out should include ("&lt;script&gt;")
    out should include ("&lt;/script&gt;")

  test("Attribute values with quotes are escaped"):
    val v = View.Element("a",
      Map("title" -> AttrValue.Str("She said \"hi\"")), Map.empty, Nil)
    Ssr.renderToHtml(v) should include ("title=\"She said &quot;hi&quot;\"")

  test("Ampersands in text get escaped to &amp;"):
    val v = View.TextNode(() => "Salt & Pepper")
    Ssr.renderToHtml(v) shouldBe "Salt &amp; Pepper"

  test("SignalText reads + escapes signal current value"):
    val sig = new ReactiveSignal[String]("msg", "<bold>")
    Ssr.renderToHtml(View.SignalText(sig)) shouldBe "&lt;bold&gt;"

  // ─── Conditional + iterative views ────────────────────────────

  test("Fragment emits children with no wrapping tag"):
    val v = View.Fragment(Seq(
      View.TextNode(() => "a"),
      View.TextNode(() => "b")))
    Ssr.renderToHtml(v) shouldBe "ab"

  test("Show evaluates cond at render time"):
    var triggered = 0
    val tree = View.Show(
      cond      = () => { triggered += 1; true },
      whenTrue  = () => View.TextNode(() => "yes"),
      whenFalse = () => View.TextNode(() => "no"))
    Ssr.renderToHtml(tree) shouldBe "yes"
    triggered shouldBe 1

  test("Show false branch renders whenFalse"):
    val tree = View.Show(
      cond      = () => false,
      whenTrue  = () => View.TextNode(() => "yes"),
      whenFalse = () => View.TextNode(() => "no"))
    Ssr.renderToHtml(tree) shouldBe "no"

  test("ShowSignal reads signal + emits matching branch"):
    val flag = new ReactiveSignal[Boolean]("flag", true)
    val tree = View.ShowSignal(flag,
      whenTrue  = View.TextNode(() => "on"),
      whenFalse = View.TextNode(() => "off"))
    Ssr.renderToHtml(tree) shouldBe "on"
    flag.set(false)
    Ssr.renderToHtml(tree) shouldBe "off"

  test("For iterates current snapshot of items"):
    val xs = new ReactiveSignal[Seq[String]]("xs", Seq("a", "b", "c"))
    val tree = View.For[String](
      items  = () => xs(),
      render = s => View.Element("li", Map.empty, Map.empty,
        Seq(View.TextNode(() => s))))
    Ssr.renderToHtml(tree) shouldBe "<li>a</li><li>b</li><li>c</li>"

  test("For with empty signal emits nothing"):
    val tree = View.For[Int](
      items  = () => Seq.empty,
      render = i => View.TextNode(() => i.toString))
    Ssr.renderToHtml(tree) shouldBe ""

  // ─── Defensive paths ──────────────────────────────────────────

  test("TextNode whose thunk throws emits empty string"):
    val v = View.TextNode(() => throw new RuntimeException("boom"))
    Ssr.renderToHtml(v) shouldBe ""

  test("Dynamic attr whose thunk throws emits empty value, not the throw"):
    val v = View.Element("input",
      Map("value" -> AttrValue.Dynamic(() => throw new RuntimeException("x"))),
      Map.empty, Nil)
    Ssr.renderToHtml(v) shouldBe "<input value=\"\" />"

  test("For whose items signal throws emits empty list"):
    val tree = View.For[Int](
      items  = () => throw new RuntimeException("nope"),
      render = i => View.TextNode(() => i.toString))
    Ssr.renderToHtml(tree) shouldBe ""

  // ─── Through the toolkit ──────────────────────────────────────

  test("Toolkit widgets render through Ssr.renderToHtml"):
    val tree = StackNode(StackDirection.Vertical, gap = 8,
      align = Alignment.Stretch,
      children = Seq(
        HeadingNode(1, "Welcome"),
        TextNode("Some body text.", TextVariant.Body)))
    val html = Ssr.renderToHtml(tree, theme)
    html should include ("<div")  // Stack lowers to div
    html should include ("<h1")
    html should include (">Welcome</h1>")
    html should include (">Some body text.</span>")

  test("Toolkit Button renders sans onclick attribute"):
    val node = ButtonNode("Save", () => (), ButtonKind.Primary)
    val html = Ssr.renderToHtml(node, theme)
    html should include ("<button")
    html should include (">Save</button>")
    html should not include ("onclick")
    // Theme tokens reach the inline style:
    html should include (theme.colors.primary)

  test("Toolkit TextField renders <input> with current signal value"):
    val name = new ReactiveSignal[String]("name", "Alice")
    val node = TextFieldNode(name, label = Some("Name"), required = true)
    val html = Ssr.renderToHtml(node, theme)
    html should include ("<input")
    html should include ("value=\"Alice\"")
    html should include ("required")
    html should include (">Name *</label>")

  test("Toolkit Form renders <form> with body content"):
    val node = FormNode(
      onSubmit = _ => (),
      build    = ctx => {
        val name = ctx.field[String]("name", "", Validators.required)
        TextFieldNode(name.value, label = Some("Name"))
      })
    val html = Ssr.renderToHtml(node, theme)
    html should include ("<form")
    html should include ("role=\"form\"")
    html should include ("<input")
    html should include ("</form>")

  test("renderDocument wraps body in HTML5 shell with theme background"):
    val tree = TextNode("Hello world", TextVariant.Body)
    val doc  = Ssr.renderDocument(tree, title = "Demo", theme = theme)
    doc should startWith ("<!DOCTYPE html>")
    doc should include ("<title>Demo</title>")
    doc should include (s"background: ${theme.colors.background}")
    doc should include ("Hello world")

  test("renderDocument escapes hostile title"):
    val tree = TextNode("body", TextVariant.Body)
    val doc  = Ssr.renderDocument(tree, title = "<script>")
    doc should include ("<title>&lt;script&gt;</title>")
    doc should not include ("<title><script>")

  test("Dark theme document picks up dark background"):
    val tree = TextNode("body", TextVariant.Body)
    val doc  = Ssr.renderDocument(tree, theme = Theme.dark)
    doc should include (Theme.dark.colors.background)
