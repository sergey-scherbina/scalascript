package scalascript.frontend.toolkit

import scalascript.frontend.{View, AttrValue, EventHandler, Signal}

/** v1.18 Phase B++ — extra form input widgets.  Rounds out the
 *  Form widget pack with the remaining common HTML inputs:
 *
 *    - `SelectNode[T]`   — `<select>` dropdown bound to a typed signal
 *    - `RadioGroupNode[T]` — `<input type="radio">` group, typed value
 *    - `TextareaNode`    — multi-line text input
 *    - `DatePickerNode`  — `<input type="date">` with ISO-8601 binding
 *    - `NumberInputNode` — `<input type="number">` with min/max/step
 *
 *  All five lower to plain HTML primitives — no backend-specific code.
 *  Theme tokens drive every colour, spacing, and typography decision.
 *
 *  Form integration: each input takes an optional `error: Signal[Option[String]]`
 *  matching the `FormField[T].error` signal — wrap any of these in
 *  `Tk.form { ctx => ... }` and the standard field-error pipeline
 *  works out of the box. */

// ─── Shared helpers ────────────────────────────────────────────────

private object FormInputs:
  /** Pixel sizing per WidgetSize — shared with TextField/Button so
   *  inputs line up vertically in a Stack.  Mirrors the spacing
   *  scale chosen there. */
  def sizing(theme: Theme, size: WidgetSize): (Int, Int, Int) = size match
    case WidgetSize.Sm => (theme.spacing.sm, theme.spacing.xs, theme.typography.bodySmall.fontSize)
    case WidgetSize.Md => (theme.spacing.md, theme.spacing.sm, theme.typography.body.fontSize)
    case WidgetSize.Lg => (theme.spacing.lg, theme.spacing.md, theme.typography.heading4.fontSize)

  /** Read an error signal defensively — bad state (uninitialised
   *  signal, exception during read) collapses to "no error". */
  def readError(sig: Option[Signal[Option[String]]]): Option[String] =
    try sig.flatMap(_.apply()) catch case _: Throwable => None

  /** Standard input-wrapper layout: optional label above, input in
   *  the middle, optional error message below.  Used by every
   *  FormInput widget for visual consistency. */
  def wrap(
    id:    String,
    theme: Theme,
    label: Option[String],
    required: Boolean,
    input: View,
    error: Option[String]
  ): View =
    val labelStyle =
      s"color: ${theme.colors.text}; font-size: ${theme.typography.bodySmall.fontSize}px; " +
      s"display: block; margin-bottom: ${theme.spacing.xs}px; font-weight: 500;"
    val errorStyle =
      s"color: ${theme.colors.danger}; font-size: ${theme.typography.caption.fontSize}px; " +
      s"margin-top: ${theme.spacing.xs}px;"
    val parts = scala.collection.mutable.ArrayBuffer.empty[View]
    label.foreach { l =>
      parts += View.Element("label",
        attrs    = Map("for" -> AttrValue.Str(id), "style" -> AttrValue.Str(labelStyle)),
        events   = Map.empty,
        children = Seq(View.TextNode(() => l + (if required then " *" else ""))))
    }
    parts += input
    error.foreach { msg =>
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

// ─── Select ────────────────────────────────────────────────────────

/** Dropdown picker bound to a typed signal.
 *
 *  `options` is a sequence of `(value, label)` pairs.  Keying:
 *  `toKey(T)` produces the stable string the `<select>` round-trips
 *  through.  Default `_.toString` works for primitive types + enums.
 *
 *  Example:
 *  ```scala
 *  enum Tier { case Free, Pro, Team }
 *  val tier = signal[Tier](Tier.Free)
 *  Tk.select(
 *    value   = tier,
 *    options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro", Tier.Team -> "Team"),
 *    label   = Some("Subscription")
 *  )
 *  ``` */
final case class SelectNode[T](
  value:       Signal[T],
  options:     Seq[(T, String)],
  toKey:       T => String                = (t: T) => t.toString,
  label:       Option[String]             = None,
  placeholder: Option[String]             = None,
  required:    Boolean                    = false,
  disabled:    Boolean                    = false,
  size:        WidgetSize                 = WidgetSize.Md,
  error:       Option[Signal[Option[String]]] = None
) extends ToolkitNode

object SelectNode:
  def lower[T](n: SelectNode[T], theme: Theme): View =
    val id  = s"sl-${System.identityHashCode(n.value).toHexString}"
    val err = FormInputs.readError(n.error)
    val (px, py, fs) = FormInputs.sizing(theme, n.size)
    val border       = err.fold(theme.colors.border)(_ => theme.colors.danger)
    val style =
      s"border: 1px solid $border; border-radius: ${theme.radii.sm}px; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"font-family: ${theme.typography.body.fontFamily}; " +
      s"background: ${theme.colors.background}; color: ${theme.colors.text}; " +
      s"width: 100%; box-sizing: border-box; appearance: auto;"

    val currentKey: String =
      try n.toKey(n.value())
      catch case _: Throwable => ""

    // Build the list of option views (incl. the placeholder if any).
    val optionViews = scala.collection.mutable.ArrayBuffer.empty[View]
    n.placeholder.foreach { p =>
      val selected = currentKey.isEmpty
      val attrs    = scala.collection.mutable.Map[String, AttrValue](
        "value"    -> AttrValue.Str(""),
        "disabled" -> AttrValue.Bool(true))
      if selected then attrs("selected") = AttrValue.Bool(true)
      optionViews += View.Element("option",
        attrs    = attrs.toMap, events = Map.empty,
        children = Seq(View.TextNode(() => p)))
    }
    n.options.foreach { case (v, lbl) =>
      val k        = n.toKey(v)
      val isActive = k == currentKey
      val attrs    = scala.collection.mutable.Map[String, AttrValue](
        "value" -> AttrValue.Str(k))
      if isActive then attrs("selected") = AttrValue.Bool(true)
      optionViews += View.Element("option",
        attrs    = attrs.toMap, events = Map.empty,
        children = Seq(View.TextNode(() => lbl)))
    }

    val inputAttrs = scala.collection.mutable.Map[String, AttrValue](
      "id"    -> AttrValue.Str(id),
      "style" -> AttrValue.Str(style),
      "value" -> AttrValue.Dynamic(() =>
        try n.toKey(n.value()) catch case _: Throwable => "")
    )
    if n.required then inputAttrs("required")      = AttrValue.Bool(true)
    if n.disabled then inputAttrs("disabled")      = AttrValue.Bool(true)
    if err.isDefined then inputAttrs("aria-invalid") = AttrValue.Bool(true)

    val select = View.Element(
      tag    = "select",
      attrs  = inputAttrs.toMap,
      events = Map("change" -> EventHandler.WithEvent { e =>
        // Backends pass the new <option> value as the payload (string).
        e match
          case s: String =>
            n.options.find((v, _) => n.toKey(v) == s).foreach((v, _) => n.value.set(v))
          case _ => ()
      }),
      children = optionViews.toSeq
    )
    FormInputs.wrap(id, theme, n.label, n.required, select, err)

// ─── RadioGroup ────────────────────────────────────────────────────

enum RadioOrientation:
  case Vertical, Horizontal

/** Typed radio-button group bound to a single signal.  Same `(value,
 *  label)` pair shape as `Select`. */
final case class RadioGroupNode[T](
  value:       Signal[T],
  options:     Seq[(T, String)],
  toKey:       T => String          = (t: T) => t.toString,
  label:       Option[String]       = None,
  required:    Boolean              = false,
  disabled:    Boolean              = false,
  orientation: RadioOrientation     = RadioOrientation.Vertical,
  error:       Option[Signal[Option[String]]] = None
) extends ToolkitNode

object RadioGroupNode:
  def lower[T](n: RadioGroupNode[T], theme: Theme): View =
    // One <input type=radio> + <label> pair per option, all sharing a
    // common `name` to make them mutually-exclusive in the browser.
    val groupName = s"rg-${System.identityHashCode(n.value).toHexString}"
    val err       = FormInputs.readError(n.error)

    val currentKey: String =
      try n.toKey(n.value()) catch case _: Throwable => ""

    val labelTextStyle =
      s"color: ${theme.colors.text}; font-family: ${theme.typography.body.fontFamily}; " +
      s"font-size: ${theme.typography.body.fontSize}px;"

    val optionViews = n.options.map { case (v, lbl) =>
      val k        = n.toKey(v)
      val optId    = s"$groupName-$k"
      val isActive = k == currentKey
      val inputAttrs = scala.collection.mutable.Map[String, AttrValue](
        "id"    -> AttrValue.Str(optId),
        "type"  -> AttrValue.Str("radio"),
        "name"  -> AttrValue.Str(groupName),
        "value" -> AttrValue.Str(k),
        "checked" -> AttrValue.Dynamic(() =>
          try n.toKey(n.value()) == k catch case _: Throwable => false)
      )
      if isActive then inputAttrs("aria-checked") = AttrValue.Str("true")
      if n.disabled then inputAttrs("disabled")   = AttrValue.Bool(true)
      val input = View.Element("input",
        attrs = inputAttrs.toMap,
        events = Map("change" -> EventHandler.Simple { () =>
          if !n.disabled then n.value.set(v)
        }),
        children = Nil)
      val labelStyle =
        s"$labelTextStyle display: flex; align-items: center; cursor: ${if n.disabled then "not-allowed" else "pointer"}; gap: ${theme.spacing.xs}px;"
      View.Element("label",
        attrs    = Map("for" -> AttrValue.Str(optId), "style" -> AttrValue.Str(labelStyle)),
        events   = Map.empty,
        children = Seq(input, View.TextNode(() => lbl)))
    }

    val groupStyle = n.orientation match
      case RadioOrientation.Vertical =>
        s"display: flex; flex-direction: column; gap: ${theme.spacing.xs}px;"
      case RadioOrientation.Horizontal =>
        s"display: flex; flex-direction: row; gap: ${theme.spacing.md}px; align-items: center;"

    val groupAttrs = scala.collection.mutable.Map[String, AttrValue](
      "role"  -> AttrValue.Str("radiogroup"),
      "style" -> AttrValue.Str(groupStyle)
    )
    if err.isDefined then groupAttrs("aria-invalid") = AttrValue.Bool(true)

    val group = View.Element(
      tag      = "div",
      attrs    = groupAttrs.toMap,
      events   = Map.empty,
      children = optionViews
    )
    FormInputs.wrap(groupName, theme, n.label, n.required, group, err)

// ─── Textarea ──────────────────────────────────────────────────────

/** Multi-line text input bound to a `Signal[String]`. */
final case class TextareaNode(
  value:       Signal[String],
  label:       Option[String]  = None,
  placeholder: Option[String]  = None,
  rows:        Int             = 4,
  required:    Boolean         = false,
  disabled:    Boolean         = false,
  size:        WidgetSize      = WidgetSize.Md,
  maxLength:   Option[Int]     = None,
  error:       Option[Signal[Option[String]]] = None
) extends ToolkitNode

object TextareaNode:
  def lower(n: TextareaNode, theme: Theme): View =
    val id  = s"ta-${System.identityHashCode(n.value).toHexString}"
    val err = FormInputs.readError(n.error)
    val (px, py, fs) = FormInputs.sizing(theme, n.size)
    val border = err.fold(theme.colors.border)(_ => theme.colors.danger)
    val style =
      s"border: 1px solid $border; border-radius: ${theme.radii.sm}px; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"font-family: ${theme.typography.body.fontFamily}; " +
      s"background: ${theme.colors.background}; color: ${theme.colors.text}; " +
      s"width: 100%; box-sizing: border-box; resize: vertical; min-height: ${(n.rows * 24).max(24)}px;"
    val attrs = scala.collection.mutable.Map[String, AttrValue](
      "id"    -> AttrValue.Str(id),
      "style" -> AttrValue.Str(style),
      "rows"  -> AttrValue.Num(n.rows.toDouble),
      "value" -> AttrValue.Dynamic(() => n.value())
    )
    n.placeholder.foreach(p => attrs("placeholder") = AttrValue.Str(p))
    n.maxLength.foreach (m => attrs("maxlength")    = AttrValue.Num(m.toDouble))
    if n.required then attrs("required")       = AttrValue.Bool(true)
    if n.disabled then attrs("disabled")       = AttrValue.Bool(true)
    if err.isDefined then attrs("aria-invalid") = AttrValue.Bool(true)

    val textarea = View.Element(
      tag    = "textarea",
      attrs  = attrs.toMap,
      events = Map("input" -> EventHandler.WithEvent {
        case s: String => n.value.set(s)
        case _          => ()
      }),
      children = Nil
    )
    FormInputs.wrap(id, theme, n.label, n.required, textarea, err)

// ─── DatePicker ────────────────────────────────────────────────────

/** Date input via `<input type="date">`.  Browsers handle the UI;
 *  the signal carries ISO-8601 `YYYY-MM-DD` strings.  Empty string
 *  represents "no date selected". */
final case class DatePickerNode(
  value:    Signal[String],
  label:    Option[String]  = None,
  required: Boolean         = false,
  disabled: Boolean         = false,
  size:     WidgetSize      = WidgetSize.Md,
  min:      Option[String]  = None,    // ISO-8601
  max:      Option[String]  = None,    // ISO-8601
  error:    Option[Signal[Option[String]]] = None
) extends ToolkitNode

object DatePickerNode:
  def lower(n: DatePickerNode, theme: Theme): View =
    val id  = s"dp-${System.identityHashCode(n.value).toHexString}"
    val err = FormInputs.readError(n.error)
    val (px, py, fs) = FormInputs.sizing(theme, n.size)
    val border = err.fold(theme.colors.border)(_ => theme.colors.danger)
    val style =
      s"border: 1px solid $border; border-radius: ${theme.radii.sm}px; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"font-family: ${theme.typography.body.fontFamily}; " +
      s"background: ${theme.colors.background}; color: ${theme.colors.text}; " +
      s"width: 100%; box-sizing: border-box;"

    val attrs = scala.collection.mutable.Map[String, AttrValue](
      "id"    -> AttrValue.Str(id),
      "type"  -> AttrValue.Str("date"),
      "style" -> AttrValue.Str(style),
      "value" -> AttrValue.Dynamic(() => n.value())
    )
    n.min.foreach    (m => attrs("min") = AttrValue.Str(m))
    n.max.foreach    (m => attrs("max") = AttrValue.Str(m))
    if n.required then attrs("required")       = AttrValue.Bool(true)
    if n.disabled then attrs("disabled")       = AttrValue.Bool(true)
    if err.isDefined then attrs("aria-invalid") = AttrValue.Bool(true)

    val input = View.Element(
      tag    = "input",
      attrs  = attrs.toMap,
      events = Map("change" -> EventHandler.WithEvent {
        case s: String => n.value.set(s)
        case _          => ()
      }),
      children = Nil
    )
    FormInputs.wrap(id, theme, n.label, n.required, input, err)

// ─── NumberInput ───────────────────────────────────────────────────

/** Numeric input via `<input type="number">`.  Signal carries the
 *  parsed `Double`; the browser handles formatting.  Out-of-range
 *  values are clamped by the browser to `[min, max]`. */
final case class NumberInputNode(
  value:    Signal[Double],
  label:    Option[String]  = None,
  required: Boolean         = false,
  disabled: Boolean         = false,
  size:     WidgetSize      = WidgetSize.Md,
  min:      Option[Double]  = None,
  max:      Option[Double]  = None,
  step:     Double          = 1.0,
  error:    Option[Signal[Option[String]]] = None
) extends ToolkitNode

object NumberInputNode:
  def lower(n: NumberInputNode, theme: Theme): View =
    val id  = s"ni-${System.identityHashCode(n.value).toHexString}"
    val err = FormInputs.readError(n.error)
    val (px, py, fs) = FormInputs.sizing(theme, n.size)
    val border = err.fold(theme.colors.border)(_ => theme.colors.danger)
    val style =
      s"border: 1px solid $border; border-radius: ${theme.radii.sm}px; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"font-family: ${theme.typography.body.fontFamily}; " +
      s"background: ${theme.colors.background}; color: ${theme.colors.text}; " +
      s"width: 100%; box-sizing: border-box;"

    val attrs = scala.collection.mutable.Map[String, AttrValue](
      "id"    -> AttrValue.Str(id),
      "type"  -> AttrValue.Str("number"),
      "style" -> AttrValue.Str(style),
      "step"  -> AttrValue.Num(n.step),
      "value" -> AttrValue.Dynamic(() => n.value().toString)
    )
    n.min.foreach    (m => attrs("min") = AttrValue.Num(m))
    n.max.foreach    (m => attrs("max") = AttrValue.Num(m))
    if n.required then attrs("required")       = AttrValue.Bool(true)
    if n.disabled then attrs("disabled")       = AttrValue.Bool(true)
    if err.isDefined then attrs("aria-invalid") = AttrValue.Bool(true)

    val input = View.Element(
      tag    = "input",
      attrs  = attrs.toMap,
      events = Map("input" -> EventHandler.WithEvent {
        case d: Double  => n.value.set(d)
        case i: Int     => n.value.set(i.toDouble)
        case s: String  => s.toDoubleOption.foreach(n.value.set)
        case _          => ()
      }),
      children = Nil
    )
    FormInputs.wrap(id, theme, n.label, n.required, input, err)
