package scalascript.frontend.toolkit

import scalascript.frontend.{View, AttrValue, EventHandler, Signal, ReactiveSignal}

/** v1.18 / Phase B — high-level declarative UI toolkit.  Sits on
 *  top of `scalascript.frontend.View` (the low-level SPI primitives)
 *  and presents a semantic widget vocabulary (`Stack`, `Text`,
 *  `Button`, `TextField`, …) instead of raw HTML elements.  All
 *  widgets lower to View through `Toolkit.lower(node, theme)`.
 *
 *  Not `sealed` — the toolkit's vocabulary is open-ended.  Widget
 *  packs (Form, Router, Widgets2, …) live in their own files and
 *  extend this trait directly; `Toolkit.lower` adds a `case` per
 *  pack.  See `docs/frontend-toolkit-spec.md` for the design intent. */
trait ToolkitNode

/** Lowering — toolkit → View.  Pure function: same toolkit tree +
 *  same theme = same View.  No DOM access; backends consume the
 *  resulting View through the regular SPI path. */
object Toolkit:
  def lower(node: ToolkitNode, theme: Theme): View = node match
    case n: StackNode      => StackNode.lower(n, theme)
    case n: BoxNode        => BoxNode.lower(n, theme)
    case n: SpacerNode     => SpacerNode.lower(n, theme)
    case n: DividerNode    => DividerNode.lower(n, theme)
    case n: TextNode       => TextNode.lower(n, theme)
    case n: HeadingNode    => HeadingNode.lower(n, theme)
    case n: ButtonNode     => ButtonNode.lower(n, theme)
    case n: TextFieldNode  => TextFieldNode.lower(n, theme)
    case n: CheckboxNode   => CheckboxNode.lower(n, theme)
    case n: AlertNode      => AlertNode.lower(n, theme)
    case n: CardNode       => CardNode.lower(n, theme)
    case n: FormNode       => FormNode.lower(n, theme)
    case n: RouterNode     => RouterNode.lower(n, theme)
    case n: LinkNode       => LinkNode.lower(n, theme)
    case n: TableNode[?]   => TableNode.lower(n, theme)
    // v1.17 / Widgets v2 pack — Slider, Tabs, Modal, Drawer, Tooltip,
    // Badge, Avatar, Icon, Spinner, Progress.  Each lives in
    // Widgets2.scala with its own `*.lower` companion.
    case n: SliderNode     => SliderNode.lower(n, theme)
    case n: TabsNode       => TabsNode.lower(n, theme)
    case n: ModalNode      => ModalNode.lower(n, theme)
    case n: DrawerNode     => DrawerNode.lower(n, theme)
    case n: TooltipNode    => TooltipNode.lower(n, theme)
    case n: BadgeNode      => BadgeNode.lower(n, theme)
    case n: AvatarNode     => AvatarNode.lower(n, theme)
    case n: IconNode       => IconNode.lower(n, theme)
    case n: SpinnerNode    => SpinnerNode.lower(n, theme)
    case n: ProgressNode   => ProgressNode.lower(n, theme)
    // FormInputs pack — Select, RadioGroup, Textarea, DatePicker,
    // NumberInput.  See FormInputs.scala.
    case n: SelectNode[?]     => SelectNode.lower(n, theme)
    case n: RadioGroupNode[?] => RadioGroupNode.lower(n, theme)
    case n: TextareaNode      => TextareaNode.lower(n, theme)
    case n: DatePickerNode    => DatePickerNode.lower(n, theme)
    case n: NumberInputNode   => NumberInputNode.lower(n, theme)
    case n: RawViewNode    => n.view  // escape hatch — direct embed

/** Escape hatch — wrap a low-level `View` so it composes alongside
 *  toolkit widgets when an exotic need arises (custom SVG, third-
 *  party component, etc.). */
final case class RawViewNode(view: View) extends ToolkitNode

// ─── Layout ─────────────────────────────────────────────────────────

enum StackDirection:
  case Vertical, Horizontal

enum Alignment:
  case Start, Center, End, Stretch, SpaceBetween, SpaceAround

/** Flexbox-based directional container.  The toolkit's workhorse
 *  layout primitive — every multi-child UI starts with a Stack. */
final case class StackNode(
  direction: StackDirection = StackDirection.Vertical,
  gap:       Int            = 0,
  align:     Alignment      = Alignment.Stretch,
  children:  Seq[ToolkitNode]
) extends ToolkitNode

object StackNode:
  def lower(n: StackNode, theme: Theme): View =
    val flexDir = n.direction match
      case StackDirection.Vertical   => "column"
      case StackDirection.Horizontal => "row"
    val alignItems = n.align match
      case Alignment.Start        => "flex-start"
      case Alignment.Center       => "center"
      case Alignment.End          => "flex-end"
      case Alignment.Stretch      => "stretch"
      case Alignment.SpaceBetween => "stretch"  // alignItems; justifyContent handles main axis
      case Alignment.SpaceAround  => "stretch"
    val justify = n.align match
      case Alignment.SpaceBetween => "; justify-content: space-between"
      case Alignment.SpaceAround  => "; justify-content: space-around"
      case _                       => ""
    val style = s"display: flex; flex-direction: $flexDir; gap: ${n.gap}px; align-items: $alignItems$justify"
    View.Element(
      tag      = "div",
      attrs    = Map("style" -> AttrValue.Str(style)),
      events   = Map.empty,
      children = n.children.map(Toolkit.lower(_, theme))
    )

/** Single-child container with optional padding / background /
 *  border / radius.  The toolkit's "give this thing a box" primitive. */
final case class BoxNode(
  padding: Int             = 0,
  bg:      Option[String]  = None,        // raw color or theme token name
  radius:  Option[String]  = None,        // theme radius name ("md")
  border:  Option[String]  = None,        // raw border (e.g. "1px solid #ccc")
  shadow:  Option[String]  = None,        // theme shadow name ("sm")
  width:   Option[String]  = None,        // raw CSS width ("100%", "300px")
  child:   ToolkitNode
) extends ToolkitNode

object BoxNode:
  def lower(n: BoxNode, theme: Theme): View =
    val sb = new StringBuilder
    if n.padding > 0 then sb.append(s"padding: ${n.padding}px;")
    n.bg.foreach    (c => sb.append(s"background: ${themeColor(theme, c)};"))
    n.radius.foreach(r => sb.append(s"border-radius: ${theme.radii.named(r)}px;"))
    n.border.foreach(b => sb.append(s"border: $b;"))
    n.shadow.foreach(s => sb.append(s"box-shadow: ${theme.shadows.named(s)};"))
    n.width.foreach (w => sb.append(s"width: $w;"))
    View.Element(
      tag      = "div",
      attrs    = Map("style" -> AttrValue.Str(sb.toString)),
      events   = Map.empty,
      children = Seq(Toolkit.lower(n.child, theme))
    )

  /** Resolve a colour ref — accepts both raw "#abc" hex AND the six
   *  semantic theme names (primary / secondary / success / warning /
   *  danger / surface / background). */
  private[toolkit] def themeColor(theme: Theme, ref: String): String = ref match
    case "primary"    => theme.colors.primary
    case "secondary"  => theme.colors.secondary
    case "success"    => theme.colors.success
    case "warning"    => theme.colors.warning
    case "danger"     => theme.colors.danger
    case "surface"    => theme.colors.surface
    case "background" => theme.colors.background
    case "border"     => theme.colors.border
    case "text"       => theme.colors.text
    case "textMuted"  => theme.colors.textMuted
    case other         => other  // assume raw CSS color

/** Flexible filler — `Spacer(grow = true)` inside a Stack absorbs
 *  remaining space; useful for "push to one edge" layouts. */
final case class SpacerNode(grow: Boolean = false, size: Int = 0) extends ToolkitNode

object SpacerNode:
  def lower(n: SpacerNode, @annotation.unused theme: Theme): View =
    val style =
      if n.grow then "flex: 1 0 auto;"
      else s"width: ${n.size}px; height: ${n.size}px;"
    View.Element("div",
      attrs = Map("style" -> AttrValue.Str(style)),
      events = Map.empty, children = Nil)

/** Horizontal or vertical rule.  Picks up the theme `border` colour. */
final case class DividerNode(
  orientation: StackDirection = StackDirection.Horizontal
) extends ToolkitNode

object DividerNode:
  def lower(n: DividerNode, theme: Theme): View =
    val style = n.orientation match
      case StackDirection.Horizontal =>
        s"border: 0; border-top: 1px solid ${theme.colors.border}; margin: 0;"
      case StackDirection.Vertical =>
        s"border: 0; border-left: 1px solid ${theme.colors.border}; align-self: stretch; width: 0; margin: 0;"
    View.Element("hr",
      attrs = Map("style" -> AttrValue.Str(style)),
      events = Map.empty, children = Nil)

// ─── Typography ────────────────────────────────────────────────────

enum TextVariant:
  case Body, BodySmall, Caption, Code

/** Plain text node with semantic variant + optional theme color.  */
final case class TextNode(
  text:    String,
  variant: TextVariant     = TextVariant.Body,
  color:   Option[String]  = None,        // theme name or raw CSS
  weight:  Option[Int]     = None         // override variant's default
) extends ToolkitNode

object TextNode:
  def lower(n: TextNode, theme: Theme): View =
    val ts = n.variant match
      case TextVariant.Body      => theme.typography.body
      case TextVariant.BodySmall => theme.typography.bodySmall
      case TextVariant.Caption   => theme.typography.caption
      case TextVariant.Code      => theme.typography.code
    val sb = new StringBuilder
    sb.append(s"font-size: ${ts.fontSize}px; line-height: ${ts.lineHeight}; ")
    sb.append(s"font-weight: ${n.weight.getOrElse(ts.fontWeight)}; ")
    sb.append(s"font-family: ${ts.fontFamily}; ")
    n.color.foreach(c => sb.append(s"color: ${BoxNode.themeColor(theme, c)};"))
    val tag = n.variant match
      case TextVariant.Code => "code"
      case _                 => "span"
    View.Element(
      tag      = tag,
      attrs    = Map("style" -> AttrValue.Str(sb.toString)),
      events   = Map.empty,
      children = Seq(View.TextNode(() => n.text))
    )

/** Semantic heading (h1 — h6).  Picks up the matching theme typography. */
final case class HeadingNode(
  level: Int,
  text:  String
) extends ToolkitNode

object HeadingNode:
  def lower(n: HeadingNode, theme: Theme): View =
    val ts  = theme.typography.heading(n.level)
    val tag = s"h${n.level.max(1).min(6)}"
    val style =
      s"font-size: ${ts.fontSize}px; line-height: ${ts.lineHeight}; " +
      s"font-weight: ${ts.fontWeight}; font-family: ${ts.fontFamily}; " +
      s"color: ${theme.colors.text}; margin: 0;"
    View.Element(
      tag      = tag,
      attrs    = Map("style" -> AttrValue.Str(style)),
      events   = Map.empty,
      children = Seq(View.TextNode(() => n.text))
    )

// ─── Inputs ────────────────────────────────────────────────────────

enum ButtonKind:
  case Primary, Secondary, Subtle, Danger, Ghost

enum WidgetSize:
  case Sm, Md, Lg

final case class ButtonNode(
  label:    String,
  onClick:  () => Unit,
  kind:     ButtonKind  = ButtonKind.Primary,
  size:     WidgetSize  = WidgetSize.Md,
  disabled: Boolean      = false,
  // Hint for Form integration — when true the button triggers form
  // submit (handled at Form lowering time).  For now we emit a
  // matching `type="submit"` so plain HTML forms work.
  formSubmit: Boolean   = false
) extends ToolkitNode

object ButtonNode:
  def lower(n: ButtonNode, theme: Theme): View =
    val (bg, fg, border) = n.kind match
      case ButtonKind.Primary   => (theme.colors.primary, theme.colors.onPrimary, "none")
      case ButtonKind.Secondary => (theme.colors.secondary, theme.colors.onPrimary, "none")
      case ButtonKind.Subtle    => (theme.colors.surface, theme.colors.text, s"1px solid ${theme.colors.border}")
      case ButtonKind.Danger    => (theme.colors.danger, theme.colors.onPrimary, "none")
      case ButtonKind.Ghost     => ("transparent", theme.colors.primary, "none")
    val (px, py, fs) = n.size match
      case WidgetSize.Sm => (theme.spacing.sm, theme.spacing.xs, theme.typography.bodySmall.fontSize)
      case WidgetSize.Md => (theme.spacing.md, theme.spacing.sm, theme.typography.body.fontSize)
      case WidgetSize.Lg => (theme.spacing.lg, theme.spacing.md, theme.typography.heading4.fontSize)
    val opacity = if n.disabled then "0.5" else "1"
    val cursor  = if n.disabled then "not-allowed" else "pointer"
    val style =
      s"background: $bg; color: $fg; border: $border; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"border-radius: ${theme.radii.md}px; cursor: $cursor; opacity: $opacity; " +
      s"font-weight: 500; font-family: ${theme.typography.body.fontFamily};"
    val attrs = Map(
      "style"          -> AttrValue.Str(style),
      "type"           -> AttrValue.Str(if n.formSubmit then "submit" else "button"),
      "role"           -> AttrValue.Str("button"),
      "aria-disabled"  -> AttrValue.Bool(n.disabled)
    )
    val events =
      if n.disabled then Map.empty[String, EventHandler]
      else Map("click" -> EventHandler.Simple(n.onClick))
    View.Element(
      tag      = "button",
      attrs    = attrs,
      events   = events,
      children = Seq(View.TextNode(() => n.label))
    )

/** Single-line text input.  Binds to a `Signal[String]` two-way.
 *  Form integration: when wrapped in a `Form`, the field reports
 *  validation state up. */
final case class TextFieldNode(
  value:       Signal[String],
  label:       Option[String]   = None,
  placeholder: Option[String]   = None,
  required:    Boolean           = false,
  disabled:    Boolean           = false,
  size:        WidgetSize        = WidgetSize.Md,
  inputType:   String            = "text",      // text / email / password / url
  error:       Option[Signal[Option[String]]] = None
) extends ToolkitNode

object TextFieldNode:
  def lower(n: TextFieldNode, theme: Theme): View =
    // Stable id so the <label for=...> binding works for screen readers
    val id = s"tf-${System.identityHashCode(n.value).toHexString}"
    val errMsg: Option[String] =
      try n.error.flatMap(_.apply()) catch case _: Throwable => None
    val borderColor = errMsg match
      case Some(_) => theme.colors.danger
      case None    => theme.colors.border
    val (px, py, fs) = n.size match
      case WidgetSize.Sm => (theme.spacing.sm, theme.spacing.xs, theme.typography.bodySmall.fontSize)
      case WidgetSize.Md => (theme.spacing.md, theme.spacing.sm, theme.typography.body.fontSize)
      case WidgetSize.Lg => (theme.spacing.lg, theme.spacing.md, theme.typography.heading4.fontSize)

    val inputStyle =
      s"border: 1px solid $borderColor; border-radius: ${theme.radii.sm}px; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"font-family: ${theme.typography.body.fontFamily}; " +
      s"background: ${theme.colors.background}; color: ${theme.colors.text}; " +
      s"width: 100%; box-sizing: border-box;"

    val valueAttr = n.value match
      case rs: ReactiveSignal[?] => AttrValue.Reactive(rs)
      case _                     => AttrValue.Dynamic(() => n.value())
    val inputChangeHandler = n.value match
      case rs: ReactiveSignal[?] => EventHandler.InputChange(rs.asInstanceOf[ReactiveSignal[String]])
      case _                     => EventHandler.WithEvent { e => e match { case s: String => n.value.set(s); case _ => () } }

    val inputAttrs = scala.collection.mutable.Map[String, AttrValue](
      "id"    -> AttrValue.Str(id),
      "type"  -> AttrValue.Str(n.inputType),
      "style" -> AttrValue.Str(inputStyle),
      "value" -> valueAttr
    )
    n.placeholder.foreach(p => inputAttrs("placeholder") = AttrValue.Str(p))
    if n.required then inputAttrs("required")      = AttrValue.Bool(true)
    if n.disabled then inputAttrs("disabled")      = AttrValue.Bool(true)
    if errMsg.isDefined then inputAttrs("aria-invalid") = AttrValue.Bool(true)

    val input = View.Element(
      tag      = "input",
      attrs    = inputAttrs.toMap,
      events   = if n.disabled then Map.empty else Map("input" -> inputChangeHandler),
      children = Nil
    )

    // Wrap input in a label container when there's a label or error.
    val labelStyle = s"color: ${theme.colors.text}; font-size: ${theme.typography.bodySmall.fontSize}px; " +
      s"display: block; margin-bottom: ${theme.spacing.xs}px; font-weight: 500;"
    val errorStyle = s"color: ${theme.colors.danger}; font-size: ${theme.typography.caption.fontSize}px; " +
      s"margin-top: ${theme.spacing.xs}px;"
    val parts = scala.collection.mutable.ArrayBuffer.empty[View]
    n.label.foreach { l =>
      parts += View.Element("label",
        attrs    = Map("for" -> AttrValue.Str(id), "style" -> AttrValue.Str(labelStyle)),
        events   = Map.empty,
        children = Seq(View.TextNode(() => l + (if n.required then " *" else ""))))
    }
    parts += input
    errMsg.foreach { msg =>
      parts += View.Element("span",
        attrs    = Map("role" -> AttrValue.Str("alert"), "style" -> AttrValue.Str(errorStyle)),
        events   = Map.empty,
        children = Seq(View.TextNode(() => msg)))
    }
    View.Element(
      tag      = "div",
      attrs    = Map("style" -> AttrValue.Str("display: flex; flex-direction: column;")),
      events   = Map.empty,
      children = parts.toSeq
    )

/** Checkbox with label.  Binds to a `Signal[Boolean]` two-way. */
final case class CheckboxNode(
  checked:  Signal[Boolean],
  label:    String,
  disabled: Boolean = false
) extends ToolkitNode

object CheckboxNode:
  def lower(n: CheckboxNode, theme: Theme): View =
    val id = s"cb-${System.identityHashCode(n.checked).toHexString}"
    val checkedAttr = n.checked match
      case rs: ReactiveSignal[?] => AttrValue.Reactive(rs)
      case _                     => AttrValue.Dynamic(() => n.checked())
    val changeHandler = n.checked match
      case rs: ReactiveSignal[?] => EventHandler.ToggleSignal(rs.asInstanceOf[ReactiveSignal[Boolean]])
      case _                     => EventHandler.WithEvent { case b: Boolean => n.checked.set(b); case _ => () }
    val input = View.Element("input",
      attrs = Map(
        "id"       -> AttrValue.Str(id),
        "type"     -> AttrValue.Str("checkbox"),
        "checked"  -> checkedAttr,
        "disabled" -> (if n.disabled then AttrValue.Bool(true) else AttrValue.Absent),
        "style"    -> AttrValue.Str(s"margin-right: ${theme.spacing.sm}px;")
      ),
      events   = if n.disabled then Map.empty else Map("change" -> changeHandler),
      children = Nil)
    val labelStyle = s"display: flex; align-items: center; cursor: pointer; " +
      s"font-family: ${theme.typography.body.fontFamily}; color: ${theme.colors.text};"
    View.Element(
      tag    = "label",
      attrs  = Map("for" -> AttrValue.Str(id), "style" -> AttrValue.Str(labelStyle)),
      events = Map.empty,
      children = Seq(input, View.TextNode(() => n.label))
    )

// ─── Display ───────────────────────────────────────────────────────

enum AlertSeverity:
  case Info, Success, Warning, Error

final case class AlertNode(
  severity: AlertSeverity,
  title:    Option[String]    = None,
  child:    ToolkitNode
) extends ToolkitNode

object AlertNode:
  def lower(n: AlertNode, theme: Theme): View =
    val (bg, fg) = n.severity match
      case AlertSeverity.Info    => ("#dbeafe", theme.colors.text)  // blue-100
      case AlertSeverity.Success => ("#dcfce7", theme.colors.text)  // green-100
      case AlertSeverity.Warning => ("#fef3c7", theme.colors.text)  // amber-100
      case AlertSeverity.Error   => ("#fee2e2", theme.colors.text)  // red-100
    val borderColor = n.severity match
      case AlertSeverity.Info    => "#93c5fd"  // blue-300
      case AlertSeverity.Success => "#86efac"  // green-300
      case AlertSeverity.Warning => "#fcd34d"  // amber-300
      case AlertSeverity.Error   => "#fca5a5"  // red-300
    val style =
      s"background: $bg; color: $fg; padding: ${theme.spacing.md}px; " +
      s"border: 1px solid $borderColor; border-radius: ${theme.radii.md}px;"
    val children = scala.collection.mutable.ArrayBuffer.empty[View]
    n.title.foreach { t =>
      children += Toolkit.lower(HeadingNode(4, t), theme)
    }
    children += Toolkit.lower(n.child, theme)
    val role = n.severity match
      case AlertSeverity.Error | AlertSeverity.Warning => "alert"
      case _                                            => "status"
    View.Element(
      tag    = "div",
      attrs  = Map(
        "role"  -> AttrValue.Str(role),
        "style" -> AttrValue.Str(style)
      ),
      events   = Map.empty,
      children = children.toSeq
    )

// ─── Containers ────────────────────────────────────────────────────

/** Card — bordered surface with optional header / footer.  The
 *  toolkit's main "thing in its own visual unit" container. */
final case class CardNode(
  header: Option[ToolkitNode] = None,
  footer: Option[ToolkitNode] = None,
  body:   ToolkitNode
) extends ToolkitNode

object CardNode:
  def lower(n: CardNode, theme: Theme): View =
    val cardStyle =
      s"background: ${theme.colors.surface}; border: 1px solid ${theme.colors.border}; " +
      s"border-radius: ${theme.radii.md}px; box-shadow: ${theme.shadows.sm}; overflow: hidden;"
    val sectionStyle =
      s"padding: ${theme.spacing.md}px;"
    val divider =
      View.Element("hr",
        attrs = Map("style" -> AttrValue.Str(
          s"border: 0; border-top: 1px solid ${theme.colors.border}; margin: 0;")),
        events = Map.empty, children = Nil)
    val parts = scala.collection.mutable.ArrayBuffer.empty[View]
    n.header.foreach { h =>
      parts += View.Element("div",
        attrs = Map("style" -> AttrValue.Str(sectionStyle)),
        events = Map.empty,
        children = Seq(Toolkit.lower(h, theme)))
      parts += divider
    }
    parts += View.Element("div",
      attrs = Map("style" -> AttrValue.Str(sectionStyle)),
      events = Map.empty,
      children = Seq(Toolkit.lower(n.body, theme)))
    n.footer.foreach { f =>
      parts += divider
      parts += View.Element("div",
        attrs = Map("style" -> AttrValue.Str(sectionStyle)),
        events = Map.empty,
        children = Seq(Toolkit.lower(f, theme)))
    }
    View.Element(
      tag      = "div",
      attrs    = Map("style" -> AttrValue.Str(cardStyle)),
      events   = Map.empty,
      children = parts.toSeq
    )
