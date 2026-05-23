package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, Signal, ReactiveSignal}

/** v1.18 / Phase B — Frontend Toolkit lowering tests.  Verifies that
 *  each toolkit widget lowers to the expected `View` shape, that
 *  theme tokens reach the output, and that backend-agnosticity holds
 *  (no React / Vue / Solid-specific names appear in the lowering). */
class ToolkitTest extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // ─── Layout ────────────────────────────────────────────────────

  test("Stack: vertical layout produces flex column"):
    val node = StackNode(StackDirection.Vertical, gap = 16, align = Alignment.Center,
      children = Seq(TextNode("a"), TextNode("b")))
    Toolkit.lower(node, theme) match
      case View.Element("div", attrs, _, kids) =>
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include ("flex-direction: column")
        style should include ("gap: 16px")
        style should include ("align-items: center")
        kids.length shouldBe 2
      case other => fail(s"got $other")

  test("Stack: horizontal SpaceBetween picks up justifyContent"):
    val node = StackNode(StackDirection.Horizontal, gap = 8,
      align = Alignment.SpaceBetween, children = Seq.empty)
    Toolkit.lower(node, theme) match
      case View.Element("div", attrs, _, _) =>
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include ("flex-direction: row")
        style should include ("justify-content: space-between")
      case other => fail(s"got $other")

  test("Box: theme colour tokens resolve correctly"):
    val node = BoxNode(padding = 16, bg = Some("primary"), radius = Some("md"),
      shadow = Some("sm"), child = TextNode("inside"))
    Toolkit.lower(node, theme) match
      case View.Element("div", attrs, _, _) =>
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include (s"background: ${theme.colors.primary}")
        style should include (s"border-radius: ${theme.radii.md}px")
        style should include (s"box-shadow: ${theme.shadows.sm}")
        style should include ("padding: 16px")
      case other => fail(s"got $other")

  test("Box: raw hex color passes through unchanged"):
    val node = BoxNode(bg = Some("#abc123"), child = TextNode("x"))
    val style = lowerStyle(node)
    style should include ("background: #abc123")

  test("Spacer: grow=true emits flex:1; size emits explicit dims"):
    lowerStyle(SpacerNode(grow = true))  should include ("flex: 1")
    lowerStyle(SpacerNode(grow = false, size = 12)) should (include ("width: 12px") and include ("height: 12px"))

  test("Divider: horizontal vs vertical use top vs left border"):
    lowerStyle(DividerNode(StackDirection.Horizontal)) should include ("border-top")
    lowerStyle(DividerNode(StackDirection.Vertical))   should include ("border-left")

  // ─── Typography ────────────────────────────────────────────────

  test("Text: body variant emits span with body typography"):
    Toolkit.lower(TextNode("hi"), theme) match
      case View.Element("span", attrs, _, _) =>
        attrs("style").asInstanceOf[AttrValue.Str].value should include (s"font-size: ${theme.typography.body.fontSize}px")
      case other => fail(s"got $other")

  test("Text: Code variant emits <code> tag with monospace family"):
    Toolkit.lower(TextNode("x", TextVariant.Code), theme) match
      case View.Element("code", attrs, _, _) =>
        attrs("style").asInstanceOf[AttrValue.Str].value should include ("monospace")
      case other => fail(s"got $other")

  test("Heading: level 1-6 selects the right tag"):
    Toolkit.lower(HeadingNode(1, "h1"), theme).asInstanceOf[View.Element].tag shouldBe "h1"
    Toolkit.lower(HeadingNode(4, "h4"), theme).asInstanceOf[View.Element].tag shouldBe "h4"
    // out-of-range clamps to [1, 6]
    Toolkit.lower(HeadingNode(0, "h0"), theme).asInstanceOf[View.Element].tag shouldBe "h1"
    Toolkit.lower(HeadingNode(9, "h9"), theme).asInstanceOf[View.Element].tag shouldBe "h6"

  test("Heading: picks up theme typography for the level"):
    Toolkit.lower(HeadingNode(1, "x"), theme) match
      case View.Element(_, attrs, _, _) =>
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include (s"font-size: ${theme.typography.heading1.fontSize}px")
        style should include (s"font-weight: ${theme.typography.heading1.fontWeight}")
      case other => fail(s"got $other")

  // ─── Inputs ────────────────────────────────────────────────────

  test("Button: primary kind uses theme primary color + onPrimary text"):
    val clicks = new java.util.concurrent.atomic.AtomicInteger(0)
    val node = ButtonNode("Save", () => clicks.incrementAndGet(),
      kind = ButtonKind.Primary)
    Toolkit.lower(node, theme) match
      case View.Element("button", attrs, events, kids) =>
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include (s"background: ${theme.colors.primary}")
        style should include (s"color: ${theme.colors.onPrimary}")
        attrs("type").asInstanceOf[AttrValue.Str].value shouldBe "button"
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "button"
        events.contains("click") shouldBe true
        // Invoke the click handler
        events("click") match
          case EventHandler.Simple(f) => f()
          case other                   => fail(s"got $other")
        clicks.get shouldBe 1
        kids.length shouldBe 1
      case other => fail(s"got $other")

  test("Button: disabled drops the click handler + sets aria-disabled"):
    val node = ButtonNode("disabled", () => fail("should not click"),
      disabled = true)
    Toolkit.lower(node, theme) match
      case View.Element(_, attrs, events, _) =>
        attrs("aria-disabled").asInstanceOf[AttrValue.Bool].value shouldBe true
        events.contains("click") shouldBe false
      case other => fail(s"got $other")

  test("Button: formSubmit=true emits type=submit"):
    val node = ButtonNode("Save", () => (), formSubmit = true)
    Toolkit.lower(node, theme).asInstanceOf[View.Element]
      .attrs("type").asInstanceOf[AttrValue.Str].value shouldBe "submit"

  test("Button: Danger kind picks up theme danger color"):
    lowerStyle(ButtonNode("Del", () => (), kind = ButtonKind.Danger)) should
      include (s"background: ${theme.colors.danger}")

  test("Button: Subtle kind uses surface + text + border colors"):
    val style = lowerStyle(ButtonNode("Cancel", () => (), kind = ButtonKind.Subtle))
    style should include (s"background: ${theme.colors.surface}")
    style should include (s"color: ${theme.colors.text}")
    style should include (s"border: 1px solid ${theme.colors.border}")

  test("TextField: emits <input> + <label> with for/id binding"):
    val v = new ReactiveSignal[String]("init-name", "")
    val node = TextFieldNode(v, label = Some("Name"), required = true,
      placeholder = Some("Jane Doe"))
    Toolkit.lower(node, theme) match
      case View.Element("div", _, _, kids) =>
        // Expect: <label>, <input>
        kids.length shouldBe 2
        val label = kids(0).asInstanceOf[View.Element]
        val input = kids(1).asInstanceOf[View.Element]
        label.tag shouldBe "label"
        input.tag shouldBe "input"
        val labelFor = label.attrs("for").asInstanceOf[AttrValue.Str].value
        val inputId  = input.attrs("id").asInstanceOf[AttrValue.Str].value
        labelFor shouldBe inputId
        input.attrs("placeholder").asInstanceOf[AttrValue.Str].value shouldBe "Jane Doe"
        input.attrs("required").asInstanceOf[AttrValue.Bool].value shouldBe true
        // Label text includes the required asterisk
        label.children.head match
          case View.TextNode(thunk) => thunk() should include ("Name")
          case other                 => fail(s"got $other")
      case other => fail(s"got $other")

  test("TextField: error signal renders an alert message + aria-invalid"):
    val v   = new ReactiveSignal[String]("init", "")
    val err = new ReactiveSignal[Option[String]]("err", Some("Too short"))
    val node = TextFieldNode(v, label = Some("Name"),
      error = Some(err.asInstanceOf[Signal[Option[String]]]))
    Toolkit.lower(node, theme) match
      case View.Element("div", _, _, kids) =>
        // <label>, <input>, <span role="alert">
        kids.length shouldBe 3
        val input = kids(1).asInstanceOf[View.Element]
        input.attrs("aria-invalid").asInstanceOf[AttrValue.Bool].value shouldBe true
        val errSpan = kids(2).asInstanceOf[View.Element]
        errSpan.attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "alert"
        errSpan.children.head match
          case View.TextNode(thunk) => thunk() shouldBe "Too short"
          case other                 => fail(s"got $other")
      case other => fail(s"got $other")

  test("Checkbox: emits <label><input type=checkbox> wrap with for/id binding"):
    val v = new ReactiveSignal[Boolean]("cb", false)
    val node = CheckboxNode(v, "I agree")
    Toolkit.lower(node, theme) match
      case View.Element("label", attrs, _, kids) =>
        val input = kids(0).asInstanceOf[View.Element]
        input.tag shouldBe "input"
        input.attrs("type").asInstanceOf[AttrValue.Str].value shouldBe "checkbox"
        attrs("for").asInstanceOf[AttrValue.Str].value shouldBe
          input.attrs("id").asInstanceOf[AttrValue.Str].value
        kids(1) match
          case View.TextNode(thunk) => thunk() shouldBe "I agree"
          case other                 => fail(s"got $other")
      case other => fail(s"got $other")

  // ─── Display ───────────────────────────────────────────────────

  test("Alert: severity drives background + role"):
    val info = AlertNode(AlertSeverity.Info, child = TextNode("ok"))
    val err  = AlertNode(AlertSeverity.Error, child = TextNode("oops"))
    Toolkit.lower(info, theme) match
      case View.Element("div", attrs, _, _) =>
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "status"
      case other => fail(s"got $other")
    Toolkit.lower(err, theme) match
      case View.Element("div", attrs, _, _) =>
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "alert"
      case other => fail(s"got $other")

  test("Alert: title renders as a heading before the body"):
    val node = AlertNode(AlertSeverity.Warning, title = Some("Heads up"),
      child = TextNode("body"))
    Toolkit.lower(node, theme) match
      case View.Element(_, _, _, kids) =>
        kids.length shouldBe 2  // <h4>title</h4> + <span>body</span>
        kids(0).asInstanceOf[View.Element].tag shouldBe "h4"
      case other => fail(s"got $other")

  // ─── Containers ────────────────────────────────────────────────

  test("Card: header + body + footer separated by dividers"):
    val node = CardNode(
      header = Some(HeadingNode(2, "Title")),
      footer = Some(ButtonNode("OK", () => ())),
      body   = TextNode("body content"))
    Toolkit.lower(node, theme) match
      case View.Element("div", _, _, kids) =>
        // Expected layout: header + divider + body + divider + footer
        kids.length shouldBe 5
        kids(1).asInstanceOf[View.Element].tag shouldBe "hr"
        kids(3).asInstanceOf[View.Element].tag shouldBe "hr"
      case other => fail(s"got $other")

  test("Card: body only (no header/footer)"):
    val node = CardNode(body = TextNode("just body"))
    Toolkit.lower(node, theme).asInstanceOf[View.Element].children.length shouldBe 1

  // ─── Backend-agnosticity ────────────────────────────────────────

  test("Lowering output mentions no framework names"):
    // Smoke check: lower a varied tree + scan the rendered styles
    // for backend-specific identifiers that would leak the
    // abstraction.  None should appear.
    val v = new ReactiveSignal[String]("name", "")
    val cb = new ReactiveSignal[Boolean]("cb", false)
    val tree = StackNode(StackDirection.Vertical, gap = 16, align = Alignment.Stretch,
      children = Seq(
        HeadingNode(1, "App"),
        CardNode(body = TextFieldNode(v, label = Some("Name"))),
        CheckboxNode(cb, "I agree"),
        ButtonNode("Save", () => (), kind = ButtonKind.Primary),
        AlertNode(AlertSeverity.Success, child = TextNode("done"))
      ))
    val view = Toolkit.lower(tree, theme)
    val rendered = renderStructure(view)
    // Backend identifiers that must not leak into the lowering.
    // Match-as-token to avoid false positives (CSS `1px solid #ccc`
    // contains "solid"; "preact" contains "react"; etc.).
    val forbidden = List(
      "useState",        // React signal API
      "createSignal",    // Solid signal API
      "v-model",         // Vue directive
      "$:",              // Svelte reactive
      "React.createElement",
      "Vue.createApp",
      "Solid.render"
    )
    forbidden.foreach(token => rendered should not include (token))

  // ─── Theme variants ────────────────────────────────────────────

  test("Dark theme: same tree produces dark surface colour"):
    val node = BoxNode(bg = Some("background"), child = TextNode("x"))
    val light = lowerStyle(node)
    val darkStyle = lowerStyleWith(node, Theme.dark)
    light should include (Theme.default.colors.background)
    darkStyle should include (Theme.dark.colors.background)
    light should not equal darkStyle

  // ─── Tk facade ─────────────────────────────────────────────────

  test("Tk.vstack + Tk.heading + Tk.button compose into a real tree"):
    import Tk.*
    val tree = vstack(gap = 16, align = Alignment.Stretch)(
      heading(1, "Hello"),
      button("Click", () => ())
    )
    Toolkit.lower(tree, theme) match
      case View.Element("div", _, _, kids) =>
        kids.length shouldBe 2
        kids(0).asInstanceOf[View.Element].tag shouldBe "h1"
        kids(1).asInstanceOf[View.Element].tag shouldBe "button"
      case other => fail(s"got $other")

  // ─── helpers ────────────────────────────────────────────────────

  private def lowerStyle(node: ToolkitNode): String =
    lowerStyleWith(node, theme)

  private def lowerStyleWith(node: ToolkitNode, theme: Theme): String =
    Toolkit.lower(node, theme) match
      case View.Element(_, attrs, _, _) =>
        attrs.get("style") match
          case Some(AttrValue.Str(s)) => s
          case _                       => ""
      case _ => ""

  /** Recursively walk + concatenate every attribute string + child
   *  text — for smoke tests that scan the output. */
  private def renderStructure(v: View[?]): String = v match
    case View.Element(tag, attrs, _, kids) =>
      val attrStr = attrs.values.collect { case AttrValue.Str(s) => s }.mkString(" ")
      val kidStr  = kids.map(renderStructure).mkString(" ")
      s"$tag $attrStr $kidStr"
    case View.TextNode(thunk) => thunk()
    case _                     => ""
