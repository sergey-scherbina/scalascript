package scalascript.frontend.toolkit

import scalascript.frontend.Signal

/** Fluent facade — the entry point user code reaches for.  All
 *  widgets are exposed as plain functions that return
 *  `ToolkitNode`s, ready to compose:
 *
 *  ```scala
 *  import scalascript.frontend.toolkit.Tk.*
 *
 *  val name  = signal("")
 *  val agreed = signal(false)
 *
 *  val app = stack(direction = Vertical, gap = 16,
 *    children = Seq(
 *      heading(1, "Welcome"),
 *      textField(value = name, label = Some("Name"), required = true),
 *      checkbox(checked = agreed, label = "I accept the terms"),
 *      button("Save", onClick = () => save(name(), agreed()))
 *    ))
 *
 *  // Compile to View via lower(app, Theme.default)
 *  ```
 *
 *  `Tk` exists alongside the case-class node constructors because
 *  it keeps user code import-light + lets us evolve the case-class
 *  signatures (rename / split parameters) without breaking source. */
object Tk:

  // ─── Layout ─────────────────────────────────────────────────────

  def stack(
    direction: StackDirection  = StackDirection.Vertical,
    gap:       Int             = 0,
    align:     Alignment       = Alignment.Stretch,
    children:  Seq[ToolkitNode]
  ): ToolkitNode = StackNode(direction, gap, align, children)

  def vstack(gap: Int = 0, align: Alignment = Alignment.Stretch)(children: ToolkitNode*): ToolkitNode =
    StackNode(StackDirection.Vertical, gap, align, children)

  def hstack(gap: Int = 0, align: Alignment = Alignment.Stretch)(children: ToolkitNode*): ToolkitNode =
    StackNode(StackDirection.Horizontal, gap, align, children)

  def box(
    padding: Int             = 0,
    bg:      Option[String]  = None,
    radius:  Option[String]  = None,
    border:  Option[String]  = None,
    shadow:  Option[String]  = None,
    width:   Option[String]  = None
  )(child: ToolkitNode): ToolkitNode =
    BoxNode(padding, bg, radius, border, shadow, width, child)

  def spacer(grow: Boolean = false, size: Int = 0): ToolkitNode =
    SpacerNode(grow, size)

  def divider(orientation: StackDirection = StackDirection.Horizontal): ToolkitNode =
    DividerNode(orientation)

  // ─── Typography ────────────────────────────────────────────────

  def text(
    s:       String,
    variant: TextVariant      = TextVariant.Body,
    color:   Option[String]   = None,
    weight:  Option[Int]      = None
  ): ToolkitNode = TextNode(s, variant, color, weight)

  def heading(level: Int, s: String): ToolkitNode = HeadingNode(level, s)

  def code(s: String): ToolkitNode = TextNode(s, TextVariant.Code)

  // ─── Inputs ─────────────────────────────────────────────────────

  def button(
    label:      String,
    onClick:    () => Unit,
    kind:       ButtonKind  = ButtonKind.Primary,
    size:       WidgetSize  = WidgetSize.Md,
    disabled:   Boolean      = false,
    formSubmit: Boolean      = false
  ): ToolkitNode = ButtonNode(label, onClick, kind, size, disabled, formSubmit)

  def textField(
    value:       Signal[String],
    label:       Option[String]   = None,
    placeholder: Option[String]   = None,
    required:    Boolean           = false,
    disabled:    Boolean           = false,
    size:        WidgetSize        = WidgetSize.Md,
    inputType:   String            = "text",
    error:       Option[Signal[Option[String]]] = None
  ): ToolkitNode = TextFieldNode(value, label, placeholder, required, disabled, size, inputType, error)

  def checkbox(
    checked:  Signal[Boolean],
    label:    String,
    disabled: Boolean = false
  ): ToolkitNode = CheckboxNode(checked, label, disabled)

  // ─── Display ───────────────────────────────────────────────────

  def alert(
    severity: AlertSeverity,
    title:    Option[String] = None
  )(child: ToolkitNode): ToolkitNode =
    AlertNode(severity, title, child)

  // ─── Containers ────────────────────────────────────────────────

  def card(
    header: Option[ToolkitNode] = None,
    footer: Option[ToolkitNode] = None
  )(body: ToolkitNode): ToolkitNode =
    CardNode(header, footer, body)
