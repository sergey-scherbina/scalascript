package scalascript.frontend.toolkit

import scalascript.frontend.{View, AttrValue, EventHandler, Signal}

/** v1.17 — Widgets v2 pack.  A second batch of widgets layered on top
 *  of the Phase B core (`Toolkit.scala`).  Each widget is a plain
 *  `case class extends ToolkitNode` with a companion `object`
 *  exposing a pure `lower(node, theme): View[?]` — same shape as the
 *  original widgets — wired into `Toolkit.lower` via one `case` per
 *  node type.
 *
 *  What this pack adds:
 *
 *    - **Inputs**: `SliderNode` — `<input type="range">` with paired
 *      label, wired to a `Signal[Double]`.
 *    - **Display**: `BadgeNode`, `AvatarNode`, `IconNode` (placeholder
 *      glyph), `SpinnerNode`, `ProgressNode`.
 *    - **Containers**: `TabsNode` + `TabEntry`, `ModalNode`,
 *      `DrawerNode` + `DrawerSide`, `TooltipNode`.
 *
 *  Accessibility is built in: tabs emit `role="tablist"`/`role="tab"`
 *  + `aria-selected`, modal/drawer emit `role="dialog"` +
 *  `aria-modal`, spinner emits `role="status"` + `aria-label`, etc.
 *
 *  See `docs/specs/frontend-toolkit-spec.md` "Layer 4 — Action + display"
 *  and "Layer 5 — Containers" for the design intent. */

// ─── SliderNode ────────────────────────────────────────────────────

/** Range slider input.  Binds to a `Signal[Double]` two-way; emits
 *  `<input type="range">` with the standard min/max/step/value
 *  attributes plus a paired `<label>` when one is supplied. */
final case class SliderNode(
  value:    Signal[Double],
  min:      Double,
  max:      Double,
  step:     Double         = 1.0,
  label:    Option[String] = None,
  disabled: Boolean        = false
) extends ToolkitNode

object SliderNode:
  def lower(n: SliderNode, theme: Theme): View[?] =
    val id = s"sl-${System.identityHashCode(n.value).toHexString}"
    val inputStyle =
      s"width: 100%; accent-color: ${theme.colors.primary}; " +
      s"cursor: ${if n.disabled then "not-allowed" else "pointer"};"
    val inputAttrs = scala.collection.mutable.Map[String, AttrValue](
      "id"      -> AttrValue.Str(id),
      "type"    -> AttrValue.Str("range"),
      "min"     -> AttrValue.Num(n.min),
      "max"     -> AttrValue.Num(n.max),
      "step"    -> AttrValue.Num(n.step),
      "value"   -> AttrValue.Dynamic(() => n.value().toString),
      "style"   -> AttrValue.Str(inputStyle),
      "role"    -> AttrValue.Str("slider"),
      "aria-valuemin" -> AttrValue.Num(n.min),
      "aria-valuemax" -> AttrValue.Num(n.max),
      "aria-valuenow" -> AttrValue.Dynamic(() => n.value())
    )
    if n.disabled then inputAttrs("disabled") = AttrValue.Bool(true)
    n.label.foreach(l => inputAttrs("aria-label") = AttrValue.Str(l))

    val input = View.Element(
      tag    = "input",
      attrs  = inputAttrs.toMap,
      events =
        if n.disabled then Map.empty
        else Map("input" -> EventHandler.WithEvent { e =>
          // Backends pass the new value already extracted (`e.target.value`
          // parsed to Double) — accept Double, Int, String for safety so
          // both the SPI test harness and JVM unit tests can drive it.
          e match
            case d: Double => n.value.set(d)
            case i: Int    => n.value.set(i.toDouble)
            case s: String =>
              try n.value.set(s.toDouble)
              catch case _: NumberFormatException => ()
            case _ => ()
        }),
      children = Nil
    )

    n.label match
      case None    => input
      case Some(l) =>
        val labelStyle =
          s"color: ${theme.colors.text}; " +
          s"font-size: ${theme.typography.bodySmall.fontSize}px; " +
          s"display: block; margin-bottom: ${theme.spacing.xs}px; font-weight: 500;"
        val wrapStyle = "display: flex; flex-direction: column;"
        View.Element("div",
          attrs    = Map("style" -> AttrValue.Str(wrapStyle)),
          events   = Map.empty,
          children = Seq(
            View.Element("label",
              attrs    = Map("for" -> AttrValue.Str(id),
                             "style" -> AttrValue.Str(labelStyle)),
              events   = Map.empty,
              children = Seq(View.TextNode(() => l))),
            input
          ))

// ─── TabsNode ─────────────────────────────────────────────────────

/** One entry in a `TabsNode`.  `id` is the active-signal key + the
 *  DOM identifier; `label` is the user-visible button text; `content`
 *  is the toolkit subtree displayed when this tab is active. */
final case class TabEntry(id: String, label: String, content: ToolkitNode)

/** Tabbed container.  Renders a `<div role="tablist">` of buttons +
 *  the active tab's content below.  Clicking a tab sets `active`. */
final case class TabsNode(
  active: Signal[String],
  tabs:   Seq[TabEntry]
) extends ToolkitNode

object TabsNode:
  def lower(n: TabsNode, theme: Theme): View[?] =
    val currentId = n.active()
    // Pick the matching tab; fall through to the first if the active
    // signal references an unknown id.
    val activeEntry: Option[TabEntry] =
      n.tabs.find(_.id == currentId).orElse(n.tabs.headOption)

    val tablistStyle =
      s"display: flex; flex-direction: row; gap: ${theme.spacing.xs}px; " +
      s"border-bottom: 1px solid ${theme.colors.border}; " +
      s"margin-bottom: ${theme.spacing.md}px;"

    val tabButtons: Seq[View[?]] = n.tabs.map { tab =>
      val isActive = activeEntry.exists(_.id == tab.id)
      val (bg, fg, borderBottom) =
        if isActive then (theme.colors.background, theme.colors.primary, theme.colors.primary)
        else            (theme.colors.surface,    theme.colors.text,    "transparent")
      val style =
        s"background: $bg; color: $fg; " +
        s"border: none; border-bottom: 2px solid $borderBottom; " +
        s"padding: ${theme.spacing.sm}px ${theme.spacing.md}px; " +
        s"font-size: ${theme.typography.body.fontSize}px; " +
        s"font-family: ${theme.typography.body.fontFamily}; " +
        s"cursor: pointer; font-weight: ${if isActive then 600 else 400};"
      View.Element(
        tag    = "button",
        attrs  = Map(
          "type"          -> AttrValue.Str("button"),
          "role"          -> AttrValue.Str("tab"),
          "aria-selected" -> AttrValue.Bool(isActive),
          "data-tab-id"   -> AttrValue.Str(tab.id),
          "style"         -> AttrValue.Str(style)
        ),
        events = Map("click" -> EventHandler.Simple(() => n.active.set(tab.id))),
        children = Seq(View.TextNode(() => tab.label))
      )
    }

    val tablist = View.Element(
      tag      = "div",
      attrs    = Map(
        "role"  -> AttrValue.Str("tablist"),
        "style" -> AttrValue.Str(tablistStyle)
      ),
      events   = Map.empty,
      children = tabButtons
    )

    val panelStyle =
      s"padding: ${theme.spacing.md}px; " +
      s"background: ${theme.colors.background};"
    val panelChildren: Seq[View[?]] = activeEntry match
      case Some(t) => Seq(Toolkit.lower(t.content, theme))
      case None    => Nil
    val panel = View.Element(
      tag      = "div",
      attrs    = Map(
        "role"  -> AttrValue.Str("tabpanel"),
        "style" -> AttrValue.Str(panelStyle)
      ),
      events   = Map.empty,
      children = panelChildren
    )

    View.Element(
      tag      = "div",
      attrs    = Map("style" -> AttrValue.Str("display: flex; flex-direction: column;")),
      events   = Map.empty,
      children = Seq(tablist, panel)
    )

// ─── ModalNode ────────────────────────────────────────────────────

/** Modal dialog with backdrop.  Centered over a translucent overlay
 *  that covers the viewport.  Lowers to an empty `Fragment` when
 *  `open()` is false — no DOM cost while closed.  Backdrop click +
 *  `Escape` key both call `onClose`. */
final case class ModalNode(
  open:    Signal[Boolean],
  title:   Option[String]   = None,
  onClose: () => Unit       = () => (),
  child:   ToolkitNode
) extends ToolkitNode

object ModalNode:
  def lower(n: ModalNode, theme: Theme): View[?] =
    if !n.open() then View.Fragment(Nil)
    else
      val backdropStyle =
        "position: fixed; inset: 0; background: rgba(0,0,0,0.5); " +
        "display: flex; align-items: center; justify-content: center; " +
        s"z-index: 1000;"
      val dialogStyle =
        s"background: ${theme.colors.surface}; " +
        s"border: 1px solid ${theme.colors.border}; " +
        s"border-radius: ${theme.radii.md}px; " +
        s"box-shadow: ${theme.shadows.lg}; " +
        s"padding: ${theme.spacing.md}px; " +
        s"min-width: 320px; max-width: 90vw; max-height: 90vh; " +
        s"overflow: auto; color: ${theme.colors.text};"

      val parts = scala.collection.mutable.ArrayBuffer.empty[View[?]]
      n.title.foreach { t =>
        parts += Toolkit.lower(HeadingNode(3, t), theme)
      }
      parts += Toolkit.lower(n.child, theme)

      // Inner dialog stops click propagation so clicks on the dialog
      // body don't trigger the backdrop's onClose handler.
      val dialog = View.Element(
        tag    = "div",
        attrs  = Map(
          "role"       -> AttrValue.Str("dialog"),
          "aria-modal" -> AttrValue.Bool(true),
          "style"      -> AttrValue.Str(dialogStyle)
        ),
        events = Map("click" -> EventHandler.WithEvent { e =>
          // Best-effort: invoke `stopPropagation` if the runtime event
          // exposes it.  Pure-JVM tests pass arbitrary tokens — fall
          // through silently when the call isn't applicable.
          try
            val m = e.getClass.getMethod("stopPropagation")
            m.invoke(e)
            ()
          catch case _: Throwable => ()
        }),
        children = parts.toSeq
      )

      View.Element(
        tag    = "div",
        attrs  = Map(
          "style"    -> AttrValue.Str(backdropStyle),
          "tabindex" -> AttrValue.Num(-1),
          "data-modal-backdrop" -> AttrValue.Bool(true)
        ),
        events = Map(
          "click"   -> EventHandler.Simple(n.onClose),
          "keydown" -> EventHandler.WithEvent { e =>
            // Accept either the literal string "Escape" (test harness)
            // or a real KeyboardEvent (`.key == "Escape"`).
            val isEscape = e match
              case "Escape" => true
              case other    =>
                try
                  val m = other.getClass.getMethod("key")
                  m.invoke(other) == "Escape"
                catch case _: Throwable => false
            if isEscape then n.onClose()
          }
        ),
        children = Seq(dialog)
      )

// ─── DrawerNode ───────────────────────────────────────────────────

enum DrawerSide:
  case Left, Right, Top, Bottom

/** Slide-out panel anchored to one edge of the viewport.  Renders the
 *  same translucent backdrop as `ModalNode` when open; backdrop click
 *  closes.  Empty `Fragment` when closed. */
final case class DrawerNode(
  open:    Signal[Boolean],
  side:    DrawerSide       = DrawerSide.Left,
  onClose: () => Unit       = () => (),
  child:   ToolkitNode
) extends ToolkitNode

object DrawerNode:
  def lower(n: DrawerNode, theme: Theme): View[?] =
    if !n.open() then View.Fragment(Nil)
    else
      val backdropStyle =
        "position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 1000;"

      val panelStyle =
        val base =
          s"position: fixed; background: ${theme.colors.surface}; " +
          s"color: ${theme.colors.text}; " +
          s"border: 1px solid ${theme.colors.border}; " +
          s"box-shadow: ${theme.shadows.lg}; " +
          s"padding: ${theme.spacing.md}px; overflow: auto;"
        val anchor = n.side match
          case DrawerSide.Left   => "top: 0; bottom: 0; left: 0; width: 320px; max-width: 90vw;"
          case DrawerSide.Right  => "top: 0; bottom: 0; right: 0; width: 320px; max-width: 90vw;"
          case DrawerSide.Top    => "top: 0; left: 0; right: 0; height: 320px; max-height: 90vh;"
          case DrawerSide.Bottom => "bottom: 0; left: 0; right: 0; height: 320px; max-height: 90vh;"
        s"$base $anchor"

      val panel = View.Element(
        tag    = "div",
        attrs  = Map(
          "role"         -> AttrValue.Str("dialog"),
          "aria-modal"   -> AttrValue.Bool(true),
          "data-drawer-side" -> AttrValue.Str(n.side.toString.toLowerCase),
          "style"        -> AttrValue.Str(panelStyle)
        ),
        events = Map("click" -> EventHandler.WithEvent { e =>
          try
            val m = e.getClass.getMethod("stopPropagation")
            m.invoke(e)
            ()
          catch case _: Throwable => ()
        }),
        children = Seq(Toolkit.lower(n.child, theme))
      )

      View.Element(
        tag    = "div",
        attrs  = Map(
          "style"               -> AttrValue.Str(backdropStyle),
          "data-drawer-backdrop" -> AttrValue.Bool(true)
        ),
        events = Map(
          "click"   -> EventHandler.Simple(n.onClose),
          "keydown" -> EventHandler.WithEvent { e =>
            val isEscape = e match
              case "Escape" => true
              case other    =>
                try
                  val m = other.getClass.getMethod("key")
                  m.invoke(other) == "Escape"
                catch case _: Throwable => false
            if isEscape then n.onClose()
          }
        ),
        children = Seq(panel)
      )

// ─── TooltipNode ──────────────────────────────────────────────────

/** Native HTML tooltip — wraps `child` in a `<span title="...">`.
 *  Pure-HTML; no portal / positioning logic.  Sufficient for v1 — a
 *  positioned floating popover lands in a later iteration. */
final case class TooltipNode(text: String, child: ToolkitNode) extends ToolkitNode

object TooltipNode:
  def lower(n: TooltipNode, theme: Theme): View[?] =
    val wrapStyle = "display: inline-block;"
    View.Element(
      tag      = "span",
      attrs    = Map(
        "title"      -> AttrValue.Str(n.text),
        "aria-label" -> AttrValue.Str(n.text),
        "style"      -> AttrValue.Str(wrapStyle)
      ),
      events   = Map.empty,
      children = Seq(Toolkit.lower(n.child, theme))
    )

// ─── BadgeNode ────────────────────────────────────────────────────

enum BadgeVariant:
  case Default, Notification, Success, Warning, Danger

/** Small pill / badge.  Variant drives the background colour from
 *  theme tokens; foreground stays on `onPrimary` for the saturated
 *  variants and on `text` for the neutral `Default`. */
final case class BadgeNode(
  content: String,
  variant: BadgeVariant = BadgeVariant.Default
) extends ToolkitNode

object BadgeNode:
  def lower(n: BadgeNode, theme: Theme): View[?] =
    val (bg, fg) = n.variant match
      case BadgeVariant.Default      => (theme.colors.surface,  theme.colors.text)
      case BadgeVariant.Notification => (theme.colors.primary,  theme.colors.onPrimary)
      case BadgeVariant.Success      => (theme.colors.success,  theme.colors.onPrimary)
      case BadgeVariant.Warning      => (theme.colors.warning,  theme.colors.onPrimary)
      case BadgeVariant.Danger       => (theme.colors.danger,   theme.colors.onPrimary)
    val style =
      s"display: inline-block; background: $bg; color: $fg; " +
      s"padding: ${theme.spacing.xs}px ${theme.spacing.sm}px; " +
      s"border-radius: ${theme.radii.full}px; " +
      s"font-size: ${theme.typography.caption.fontSize}px; " +
      s"font-weight: 600; font-family: ${theme.typography.body.fontFamily}; " +
      s"line-height: 1;"
    View.Element(
      tag      = "span",
      attrs    = Map("style" -> AttrValue.Str(style)),
      events   = Map.empty,
      children = Seq(View.TextNode(() => n.content))
    )

// ─── AvatarNode ───────────────────────────────────────────────────

/** Circular avatar.  Two rendering modes:
 *    - `src = Some(url)` → `<img>` with the URL, rounded to a circle.
 *    - `src = None`      → initials fallback (first two letters of
 *      `name`, centered, on the theme primary colour). */
final case class AvatarNode(
  name: String,
  src:  Option[String] = None,
  size: Int             = 40
) extends ToolkitNode

object AvatarNode:
  def lower(n: AvatarNode, theme: Theme): View[?] =
    val side = math.max(n.size, 1)
    val baseStyle =
      s"width: ${side}px; height: ${side}px; " +
      s"border-radius: ${theme.radii.full}px; display: inline-block; " +
      s"overflow: hidden; vertical-align: middle;"
    n.src match
      case Some(url) =>
        val imgStyle = s"$baseStyle object-fit: cover;"
        View.Element(
          tag    = "img",
          attrs  = Map(
            "src"   -> AttrValue.Str(url),
            "alt"   -> AttrValue.Str(n.name),
            "style" -> AttrValue.Str(imgStyle)
          ),
          events   = Map.empty,
          children = Nil
        )
      case None =>
        val initials = initialsOf(n.name)
        val fontSize = math.max(side / 2, 10)
        val style =
          s"$baseStyle background: ${theme.colors.primary}; " +
          s"color: ${theme.colors.onPrimary}; " +
          s"display: inline-flex; align-items: center; justify-content: center; " +
          s"font-family: ${theme.typography.body.fontFamily}; " +
          s"font-size: ${fontSize}px; font-weight: 600; text-transform: uppercase;"
        View.Element(
          tag    = "span",
          attrs  = Map(
            "role"       -> AttrValue.Str("img"),
            "aria-label" -> AttrValue.Str(n.name),
            "style"      -> AttrValue.Str(style)
          ),
          events   = Map.empty,
          children = Seq(View.TextNode(() => initials))
        )

  /** First two non-whitespace characters across word boundaries — so
   *  `"Jane Doe"` becomes `"JD"` and `"Carmichael"` becomes `"Ca"`. */
  private[toolkit] def initialsOf(name: String): String =
    val parts = name.trim.split("\\s+").filter(_.nonEmpty)
    val raw = parts.toList match
      case Nil          => ""
      case head :: Nil  => head.take(2)
      case a :: b :: _  => s"${a.head}${b.head}"
    raw.toUpperCase

// ─── IconNode ─────────────────────────────────────────────────────

/** Placeholder icon — emits `<span class="icon" data-icon="$name">`.
 *  Real glyph rendering is deferred to a v2.x icon-library
 *  integration; for now downstream CSS / a registry can drive the
 *  visual on the `data-icon` attribute. */
final case class IconNode(
  name: String,
  size: WidgetSize = WidgetSize.Md
) extends ToolkitNode

object IconNode:
  def lower(n: IconNode, theme: Theme): View[?] =
    val sidePx = n.size match
      case WidgetSize.Sm => theme.spacing.md       // ~16
      case WidgetSize.Md => theme.spacing.lg       // ~24
      case WidgetSize.Lg => theme.spacing.xl       // ~32
    val style =
      s"display: inline-block; width: ${sidePx}px; height: ${sidePx}px; " +
      s"line-height: ${sidePx}px; text-align: center; " +
      s"color: ${theme.colors.text}; font-family: ${theme.typography.body.fontFamily};"
    View.Element(
      tag    = "span",
      attrs  = Map(
        "class"      -> AttrValue.Str("icon"),
        "data-icon"  -> AttrValue.Str(n.name),
        "aria-hidden" -> AttrValue.Bool(true),
        "style"      -> AttrValue.Str(style)
      ),
      events   = Map.empty,
      children = Nil
    )

// ─── SpinnerNode ──────────────────────────────────────────────────

/** CSS-only spinner — a borderless circle with a single-coloured top
 *  border spinning forever.  No inline keyframe definition (browsers
 *  don't honour `@keyframes` inside a `style=` attribute), so the
 *  hosting page is expected to ship a `@keyframes spin` rule in its
 *  global stylesheet.  Accessibility: `role="status"` +
 *  `aria-label="Loading"`. */
final case class SpinnerNode(
  size: WidgetSize = WidgetSize.Md
) extends ToolkitNode

object SpinnerNode:
  def lower(n: SpinnerNode, theme: Theme): View[?] =
    val sidePx = n.size match
      case WidgetSize.Sm => theme.spacing.md
      case WidgetSize.Md => theme.spacing.lg
      case WidgetSize.Lg => theme.spacing.xl
    val style =
      s"display: inline-block; width: ${sidePx}px; height: ${sidePx}px; " +
      s"border: 2px solid transparent; " +
      s"border-top: 2px solid ${theme.colors.primary}; " +
      s"border-radius: ${theme.radii.full}px; " +
      s"animation: spin 1s linear infinite;"
    View.Element(
      tag    = "div",
      attrs  = Map(
        "role"       -> AttrValue.Str("status"),
        "aria-label" -> AttrValue.Str("Loading"),
        "style"      -> AttrValue.Str(style)
      ),
      events   = Map.empty,
      children = Nil
    )

// ─── ProgressNode ─────────────────────────────────────────────────

/** Determinate progress indicator.  Lowers to the native HTML
 *  `<progress>` element so the platform handles indeterminate vs
 *  determinate semantics, ARIA wiring, and high-contrast styling.
 *  When a `label` is supplied, a `<label>` element wraps the progress
 *  bar so screen readers announce both. */
final case class ProgressNode(
  value: Signal[Double],
  max:   Double,
  label: Option[String] = None
) extends ToolkitNode

object ProgressNode:
  def lower(n: ProgressNode, theme: Theme): View[?] =
    val id = s"pg-${System.identityHashCode(n.value).toHexString}"
    val progressStyle =
      s"width: 100%; height: ${theme.spacing.sm}px; " +
      s"accent-color: ${theme.colors.primary};"
    val progressAttrs = scala.collection.mutable.Map[String, AttrValue](
      "id"    -> AttrValue.Str(id),
      "max"   -> AttrValue.Num(n.max),
      "value" -> AttrValue.Dynamic(() => n.value()),
      "style" -> AttrValue.Str(progressStyle),
      "aria-valuemin" -> AttrValue.Num(0),
      "aria-valuemax" -> AttrValue.Num(n.max),
      "aria-valuenow" -> AttrValue.Dynamic(() => n.value())
    )
    n.label match
      case Some(l) =>
        // Wrap progress with a paired <label> above.
        val labelStyle =
          s"color: ${theme.colors.text}; " +
          s"font-size: ${theme.typography.bodySmall.fontSize}px; " +
          s"display: block; margin-bottom: ${theme.spacing.xs}px; font-weight: 500;"
        val wrapStyle = "display: flex; flex-direction: column;"
        val progress = View.Element("progress",
          attrs    = progressAttrs.toMap,
          events   = Map.empty,
          children = Nil)
        View.Element("div",
          attrs    = Map("style" -> AttrValue.Str(wrapStyle)),
          events   = Map.empty,
          children = Seq(
            View.Element("label",
              attrs    = Map("for" -> AttrValue.Str(id),
                             "style" -> AttrValue.Str(labelStyle)),
              events   = Map.empty,
              children = Seq(View.TextNode(() => l))),
            progress
          ))
      case None =>
        progressAttrs("aria-label") = AttrValue.Str("Progress")
        View.Element("progress",
          attrs    = progressAttrs.toMap,
          events   = Map.empty,
          children = Nil)
