package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, ReactiveSignal}

/** v1.17 — Widgets v2 pack lowering tests.  Mirrors `ToolkitTest`'s
 *  style: each widget gets a "lowers to the right `View.Element` shape"
 *  test, plus targeted checks for theme-token use, accessibility
 *  attributes, signal-driven behaviour, and the empty/closed states. */
class Widgets2Test extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // ─── helpers ──────────────────────────────────────────────────

  private def styleOf(v: View): String = v match
    case View.Element(_, attrs, _, _) =>
      attrs.get("style") match
        case Some(AttrValue.Str(s)) => s
        case _                       => ""
    case _ => ""

  private def renderStructure(v: View): String = v match
    case View.Element(tag, attrs, _, kids) =>
      val attrStr = attrs.values.collect { case AttrValue.Str(s) => s }.mkString(" ")
      val kidStr  = kids.map(renderStructure).mkString(" ")
      s"$tag $attrStr $kidStr"
    case View.TextNode(thunk) => thunk()
    case View.Fragment(kids)  => kids.map(renderStructure).mkString(" ")
    case _                     => ""

  // ─── Slider ────────────────────────────────────────────────────

  test("Slider: lowers to <input type=range> with min/max/step attrs"):
    val v = new ReactiveSignal[Double]("vol", 50.0)
    val node = SliderNode(v, min = 0, max = 100, step = 5, label = Some("Volume"))
    val view = Toolkit.lower(node, theme)
    // With a label we get a wrapping <div> containing <label> + <input>.
    view match
      case View.Element("div", _, _, kids) =>
        val label = kids(0).asInstanceOf[View.Element]
        val input = kids(1).asInstanceOf[View.Element]
        label.tag shouldBe "label"
        input.tag shouldBe "input"
        input.attrs("type").asInstanceOf[AttrValue.Str].value shouldBe "range"
        input.attrs("min").asInstanceOf[AttrValue.Num].value shouldBe 0.0
        input.attrs("max").asInstanceOf[AttrValue.Num].value shouldBe 100.0
        input.attrs("step").asInstanceOf[AttrValue.Num].value shouldBe 5.0
      case other => fail(s"got $other")

  test("Slider: applies the theme primary accent colour"):
    val v = new ReactiveSignal[Double]("v", 0.0)
    val node = SliderNode(v, min = 0, max = 1, label = None)
    styleOf(Toolkit.lower(node, theme)) should include (theme.colors.primary)

  test("Slider: oninput handler updates the bound signal (Double payload)"):
    val v = new ReactiveSignal[Double]("v", 0.0)
    val node = SliderNode(v, min = 0, max = 100)
    val input = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    input.events("input") match
      case EventHandler.WithEvent(f) =>
        f(42.0)
        v() shouldBe 42.0
        // String payload (some backends serialise) should also parse.
        f("17.5")
        v() shouldBe 17.5
      case other => fail(s"got $other")

  test("Slider: ARIA role + aria-value* attrs are present"):
    val v = new ReactiveSignal[Double]("v", 25.0)
    val node = SliderNode(v, min = 0, max = 100, label = Some("Vol"))
    val input = Toolkit.lower(node, theme).asInstanceOf[View.Element].children(1).asInstanceOf[View.Element]
    input.attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "slider"
    input.attrs("aria-valuemin").asInstanceOf[AttrValue.Num].value shouldBe 0.0
    input.attrs("aria-valuemax").asInstanceOf[AttrValue.Num].value shouldBe 100.0

  test("Slider: disabled drops the input handler and adds the attribute"):
    val v = new ReactiveSignal[Double]("v", 0.0)
    val node = SliderNode(v, min = 0, max = 100, disabled = true)
    val input = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    input.attrs("disabled").asInstanceOf[AttrValue.Bool].value shouldBe true
    input.events.contains("input") shouldBe false

  // ─── Tabs ──────────────────────────────────────────────────────

  test("Tabs: emits role=tablist + role=tab buttons with aria-selected"):
    val active = new ReactiveSignal[String]("tab", "a")
    val node = TabsNode(active, Seq(
      TabEntry("a", "Alpha", TextNode("body-a")),
      TabEntry("b", "Beta",  TextNode("body-b"))
    ))
    Toolkit.lower(node, theme) match
      case View.Element("div", _, _, kids) =>
        val tablist = kids.head.asInstanceOf[View.Element]
        tablist.attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "tablist"
        val buttons = tablist.children.map(_.asInstanceOf[View.Element])
        buttons.length shouldBe 2
        buttons(0).tag shouldBe "button"
        buttons(0).attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "tab"
        buttons(0).attrs("aria-selected").asInstanceOf[AttrValue.Bool].value shouldBe true
        buttons(1).attrs("aria-selected").asInstanceOf[AttrValue.Bool].value shouldBe false
      case other => fail(s"got $other")

  test("Tabs: only the active tab's content is rendered in the panel"):
    val active = new ReactiveSignal[String]("tab", "b")
    val node = TabsNode(active, Seq(
      TabEntry("a", "Alpha", TextNode("body-a")),
      TabEntry("b", "Beta",  TextNode("body-b"))
    ))
    val rendered = renderStructure(Toolkit.lower(node, theme))
    rendered should include ("body-b")
    rendered should not include ("body-a")

  test("Tabs: clicking a tab button calls active.set(id)"):
    val active = new ReactiveSignal[String]("tab", "a")
    val node = TabsNode(active, Seq(
      TabEntry("a", "Alpha", TextNode("A")),
      TabEntry("b", "Beta",  TextNode("B"))
    ))
    val root = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tablist = root.children.head.asInstanceOf[View.Element]
    val betaBtn = tablist.children(1).asInstanceOf[View.Element]
    betaBtn.events("click") match
      case EventHandler.Simple(f) => f()
      case other                   => fail(s"got $other")
    active() shouldBe "b"

  test("Tabs: unknown active id falls through to the first tab"):
    val active = new ReactiveSignal[String]("tab", "missing")
    val node = TabsNode(active, Seq(
      TabEntry("a", "Alpha", TextNode("body-a")),
      TabEntry("b", "Beta",  TextNode("body-b"))
    ))
    val rendered = renderStructure(Toolkit.lower(node, theme))
    rendered should include ("body-a")
    rendered should not include ("body-b")

  test("Tabs: uses theme primary for the active tab underline"):
    val active = new ReactiveSignal[String]("tab", "a")
    val node = TabsNode(active, Seq(TabEntry("a", "Alpha", TextNode("x"))))
    val rendered = renderStructure(Toolkit.lower(node, theme))
    rendered should include (theme.colors.primary)

  // ─── Modal ─────────────────────────────────────────────────────

  test("Modal: closed lowers to an empty Fragment"):
    val open = new ReactiveSignal[Boolean]("mo", false)
    val node = ModalNode(open, child = TextNode("body"))
    Toolkit.lower(node, theme) match
      case View.Fragment(kids) => kids shouldBe empty
      case other                => fail(s"got $other")

  test("Modal: open emits role=dialog + aria-modal=true"):
    val open = new ReactiveSignal[Boolean]("mo", true)
    val node = ModalNode(open, title = Some("Confirm"), child = TextNode("body"))
    Toolkit.lower(node, theme) match
      case View.Element("div", _, _, kids) =>
        val dialog = kids.head.asInstanceOf[View.Element]
        dialog.attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "dialog"
        dialog.attrs("aria-modal").asInstanceOf[AttrValue.Bool].value shouldBe true
      case other => fail(s"got $other")

  test("Modal: backdrop uses fixed-position semitransparent overlay"):
    val open = new ReactiveSignal[Boolean]("mo", true)
    val node = ModalNode(open, child = TextNode("body"))
    val backdropStyle = styleOf(Toolkit.lower(node, theme))
    backdropStyle should include ("position: fixed")
    backdropStyle should include ("rgba(0,0,0,0.5)")

  test("Modal: backdrop click invokes onClose"):
    val open = new ReactiveSignal[Boolean]("mo", true)
    var closed = 0
    val node = ModalNode(open, onClose = () => closed += 1,
      child = TextNode("body"))
    val root = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    root.events("click") match
      case EventHandler.Simple(f) => f()
      case other                   => fail(s"got $other")
    closed shouldBe 1

  test("Modal: Escape keydown invokes onClose"):
    val open = new ReactiveSignal[Boolean]("mo", true)
    var closed = 0
    val node = ModalNode(open, onClose = () => closed += 1,
      child = TextNode("body"))
    val root = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    root.events("keydown") match
      case EventHandler.WithEvent(f) =>
        f("Escape")
        closed shouldBe 1
        f("a")
        closed shouldBe 1  // non-Escape ignored
      case other => fail(s"got $other")

  test("Modal: title renders as a heading inside the dialog"):
    val open = new ReactiveSignal[Boolean]("mo", true)
    val node = ModalNode(open, title = Some("Delete?"),
      child = TextNode("body"))
    val rendered = renderStructure(Toolkit.lower(node, theme))
    rendered should include ("Delete?")

  test("Modal: signal flip from closed → open changes lowering"):
    val open = new ReactiveSignal[Boolean]("mo", false)
    val node = ModalNode(open, child = TextNode("body"))
    Toolkit.lower(node, theme).isInstanceOf[View.Fragment] shouldBe true
    open.set(true)
    Toolkit.lower(node, theme).isInstanceOf[View.Element] shouldBe true

  // ─── Drawer ────────────────────────────────────────────────────

  test("Drawer: closed lowers to an empty Fragment"):
    val open = new ReactiveSignal[Boolean]("dr", false)
    val node = DrawerNode(open, child = TextNode("body"))
    Toolkit.lower(node, theme) match
      case View.Fragment(kids) => kids shouldBe empty
      case other                => fail(s"got $other")

  test("Drawer: side=Left anchors panel to left edge"):
    val open = new ReactiveSignal[Boolean]("dr", true)
    val node = DrawerNode(open, side = DrawerSide.Left,
      child = TextNode("body"))
    val root  = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val panel = root.children.head.asInstanceOf[View.Element]
    val pStyle = panel.attrs("style").asInstanceOf[AttrValue.Str].value
    pStyle should include ("left: 0")

  test("Drawer: side=Right anchors panel to right edge"):
    val open = new ReactiveSignal[Boolean]("dr", true)
    val node = DrawerNode(open, side = DrawerSide.Right,
      child = TextNode("body"))
    val pStyle = Toolkit.lower(node, theme).asInstanceOf[View.Element]
      .children.head.asInstanceOf[View.Element]
      .attrs("style").asInstanceOf[AttrValue.Str].value
    pStyle should include ("right: 0")

  test("Drawer: panel emits role=dialog + aria-modal"):
    val open = new ReactiveSignal[Boolean]("dr", true)
    val node = DrawerNode(open, child = TextNode("body"))
    val panel = Toolkit.lower(node, theme).asInstanceOf[View.Element]
      .children.head.asInstanceOf[View.Element]
    panel.attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "dialog"
    panel.attrs("aria-modal").asInstanceOf[AttrValue.Bool].value shouldBe true

  test("Drawer: backdrop click invokes onClose"):
    val open = new ReactiveSignal[Boolean]("dr", true)
    var closed = 0
    val node = DrawerNode(open, onClose = () => closed += 1,
      child = TextNode("body"))
    val root = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    root.events("click") match
      case EventHandler.Simple(f) => f()
      case other                   => fail(s"got $other")
    closed shouldBe 1

  // ─── Tooltip ───────────────────────────────────────────────────

  test("Tooltip: wraps child in <span title=...>"):
    val node = TooltipNode("Save to library", ButtonNode("Save", () => ()))
    Toolkit.lower(node, theme) match
      case View.Element("span", attrs, _, kids) =>
        attrs("title").asInstanceOf[AttrValue.Str].value shouldBe "Save to library"
        kids.head.asInstanceOf[View.Element].tag shouldBe "button"
      case other => fail(s"got $other")

  test("Tooltip: emits aria-label mirroring the title text"):
    val node = TooltipNode("Help text", TextNode("?"))
    Toolkit.lower(node, theme) match
      case View.Element("span", attrs, _, _) =>
        attrs("aria-label").asInstanceOf[AttrValue.Str].value shouldBe "Help text"
      case other => fail(s"got $other")

  // ─── Badge ─────────────────────────────────────────────────────

  test("Badge: notification variant uses theme primary background"):
    val node = BadgeNode("3", BadgeVariant.Notification)
    Toolkit.lower(node, theme) match
      case View.Element("span", attrs, _, kids) =>
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include (s"background: ${theme.colors.primary}")
        style should include (s"color: ${theme.colors.onPrimary}")
        kids.head match
          case View.TextNode(thunk) => thunk() shouldBe "3"
          case other                 => fail(s"got $other")
      case other => fail(s"got $other")

  test("Badge: variants pick up matching theme colours"):
    styleOf(Toolkit.lower(BadgeNode("ok",  BadgeVariant.Success), theme)) should include (theme.colors.success)
    styleOf(Toolkit.lower(BadgeNode("!",   BadgeVariant.Warning), theme)) should include (theme.colors.warning)
    styleOf(Toolkit.lower(BadgeNode("err", BadgeVariant.Danger),  theme)) should include (theme.colors.danger)
    styleOf(Toolkit.lower(BadgeNode("n",   BadgeVariant.Default), theme)) should include (theme.colors.surface)

  test("Badge: uses the pill radius (full)"):
    val style = styleOf(Toolkit.lower(BadgeNode("x"), theme))
    style should include (s"border-radius: ${theme.radii.full}px")

  // ─── Avatar ────────────────────────────────────────────────────

  test("Avatar: src=Some renders an <img> with src + alt"):
    val node = AvatarNode("Jane Doe", src = Some("/jane.png"), size = 48)
    Toolkit.lower(node, theme) match
      case View.Element("img", attrs, _, kids) =>
        attrs("src").asInstanceOf[AttrValue.Str].value shouldBe "/jane.png"
        attrs("alt").asInstanceOf[AttrValue.Str].value shouldBe "Jane Doe"
        kids shouldBe empty
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include ("width: 48px")
        style should include ("height: 48px")
      case other => fail(s"got $other")

  test("Avatar: src=None renders initials in a primary-colored circle"):
    val node = AvatarNode("Jane Doe", src = None, size = 40)
    Toolkit.lower(node, theme) match
      case View.Element("span", attrs, _, kids) =>
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "img"
        attrs("aria-label").asInstanceOf[AttrValue.Str].value shouldBe "Jane Doe"
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include (s"background: ${theme.colors.primary}")
        style should include (s"color: ${theme.colors.onPrimary}")
        kids.head match
          case View.TextNode(thunk) => thunk() shouldBe "JD"
          case other                 => fail(s"got $other")
      case other => fail(s"got $other")

  test("Avatar: single-word name uses the first two letters"):
    AvatarNode.initialsOf("Carmichael") shouldBe "CA"
    AvatarNode.initialsOf("a")          shouldBe "A"

  test("Avatar: uses theme radii.full to produce a circle"):
    val node = AvatarNode("X", src = None)
    styleOf(Toolkit.lower(node, theme)) should include (s"border-radius: ${theme.radii.full}px")

  // ─── Icon ──────────────────────────────────────────────────────

  test("Icon: emits <span class=icon data-icon=...> placeholder"):
    val node = IconNode("trash")
    Toolkit.lower(node, theme) match
      case View.Element("span", attrs, _, _) =>
        attrs("class").asInstanceOf[AttrValue.Str].value shouldBe "icon"
        attrs("data-icon").asInstanceOf[AttrValue.Str].value shouldBe "trash"
        attrs("aria-hidden").asInstanceOf[AttrValue.Bool].value shouldBe true
      case other => fail(s"got $other")

  test("Icon: sizes pull from theme spacing tokens"):
    val sm = styleOf(Toolkit.lower(IconNode("x", WidgetSize.Sm), theme))
    val md = styleOf(Toolkit.lower(IconNode("x", WidgetSize.Md), theme))
    val lg = styleOf(Toolkit.lower(IconNode("x", WidgetSize.Lg), theme))
    sm should include (s"width: ${theme.spacing.md}px")
    md should include (s"width: ${theme.spacing.lg}px")
    lg should include (s"width: ${theme.spacing.xl}px")

  // ─── Spinner ───────────────────────────────────────────────────

  test("Spinner: emits role=status + aria-label=Loading"):
    Toolkit.lower(SpinnerNode(), theme) match
      case View.Element("div", attrs, _, _) =>
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "status"
        attrs("aria-label").asInstanceOf[AttrValue.Str].value shouldBe "Loading"
      case other => fail(s"got $other")

  test("Spinner: emits animation + circular border-radius"):
    val style = styleOf(Toolkit.lower(SpinnerNode(), theme))
    style should include ("animation: spin 1s linear infinite")
    style should include ("border-top: 2px solid")
    style should include (s"border-radius: ${theme.radii.full}px")
    style should include (theme.colors.primary)

  // ─── Progress ──────────────────────────────────────────────────

  test("Progress: lowers to <progress> with max + value attrs"):
    val v = new ReactiveSignal[Double]("up", 30.0)
    val node = ProgressNode(v, max = 100, label = None)
    Toolkit.lower(node, theme) match
      case View.Element("progress", attrs, _, _) =>
        attrs("max").asInstanceOf[AttrValue.Num].value shouldBe 100.0
        attrs("value") shouldBe an [AttrValue.Dynamic[?]]
        attrs("aria-label").asInstanceOf[AttrValue.Str].value shouldBe "Progress"
      case other => fail(s"got $other")

  test("Progress: with label wraps progress in <div> with <label> above"):
    val v = new ReactiveSignal[Double]("up", 30.0)
    val node = ProgressNode(v, max = 100, label = Some("Upload"))
    Toolkit.lower(node, theme) match
      case View.Element("div", _, _, kids) =>
        val label    = kids(0).asInstanceOf[View.Element]
        val progress = kids(1).asInstanceOf[View.Element]
        label.tag    shouldBe "label"
        progress.tag shouldBe "progress"
        label.attrs("for").asInstanceOf[AttrValue.Str].value shouldBe
          progress.attrs("id").asInstanceOf[AttrValue.Str].value
      case other => fail(s"got $other")

  test("Progress: value reads through signal at render"):
    val v = new ReactiveSignal[Double]("up", 10.0)
    val node = ProgressNode(v, max = 100, label = None)
    val progress = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val dyn = progress.attrs("value").asInstanceOf[AttrValue.Dynamic[Double]]
    dyn.read() shouldBe 10.0
    v.set(75.0)
    dyn.read() shouldBe 75.0

  // ─── Tk facade ─────────────────────────────────────────────────

  test("Tk.slider + Tk.badge + Tk.spinner compose"):
    import Tk.*
    val v = new ReactiveSignal[Double]("vv", 0.0)
    val tree = vstack(gap = 8)(
      slider(value = v, min = 0, max = 10),
      badge("3", variant = BadgeVariant.Notification),
      spinner()
    )
    Toolkit.lower(tree, theme) match
      case View.Element("div", _, _, kids) =>
        kids.length shouldBe 3
      case other => fail(s"got $other")

  test("Tk.tabs + Tk.tab build a TabsNode"):
    import Tk.*
    val active = new ReactiveSignal[String]("t", "a")
    val tree = tabs(active)(
      tab("a", "A", text("body-a")),
      tab("b", "B", text("body-b"))
    )
    tree shouldBe a [TabsNode]
    tree.asInstanceOf[TabsNode].tabs.length shouldBe 2

  test("Tk.modal + Tk.drawer + Tk.tooltip build the right nodes"):
    import Tk.*
    val open = new ReactiveSignal[Boolean]("o", false)
    modal(open, title = Some("t"))(text("body"))   shouldBe a [ModalNode]
    drawer(open, side = DrawerSide.Right)(text("body")) shouldBe a [DrawerNode]
    tooltip("hi")(text("?")) shouldBe a [TooltipNode]

  test("Tk.avatar + Tk.icon + Tk.progress build the right nodes"):
    import Tk.*
    val v = new ReactiveSignal[Double]("p", 0.0)
    avatar("Jane")                 shouldBe a [AvatarNode]
    icon("trash")                  shouldBe a [IconNode]
    progress(v, max = 100)         shouldBe a [ProgressNode]
