package scalascript.frontend.examples

import scalascript.frontend.*
import scalascript.frontend.toolkit.*
import scalascript.frontend.toolkit.Tk

/** v1.28 Phase C — Cross-backend toolkit demo.
 *
 *  Interactive sign-up form:
 *    - Submit button disabled until the checkbox is accepted.
 *    - On submit: alert shows "You have accepted the toolkit terms, <name>".
 *    - After submit: form fields become read-only; button stays pressable.
 *
 *  The React backend supports full interactivity via `AttrValue.Reactive`,
 *  `EventHandler.ToggleSignal`, `EventHandler.InputChange`, and
 *  `EventHandler.SetSignalLiteral`.  Other backends render a static snapshot. */
object ToolkitDemo:

  val Name: String = "toolkit-demo"

  def buildModule(theme: Theme = Theme.default): FrontendModule =
    val name      = new ReactiveSignal[String]("name",      "Alice")
    val accept    = new ReactiveSignal[Boolean]("accept",   false)
    val submitted = new ReactiveSignal[Boolean]("submitted", false)

    // ── Form body ────────────────────────────────────────────────────
    // Two pre-lowered versions; ShowSignal swaps them when submitted.
    val enabledForm = Toolkit.lower(
      Tk.vstack(gap = 12)(
        Tk.textField(value = name, label = Some("Display name"), required = true),
        Tk.checkbox(checked = accept, label = "I accept the toolkit terms.")
      ), theme)

    val disabledForm = Toolkit.lower(
      Tk.vstack(gap = 12)(
        Tk.textField(value = name, label = Some("Display name"), required = true, disabled = true),
        Tk.checkbox(checked = accept, label = "I accept the toolkit terms.", disabled = true)
      ), theme)

    val formBody = View.ShowSignal(submitted, disabledForm, enabledForm)

    // ── Submit button ────────────────────────────────────────────────
    // Built at View level so we can use SetSignalLiteral (translatable).
    // ShowSignal(accept, …) enables it only when the checkbox is ticked.
    val (px, py, fs) = (theme.spacing.md, theme.spacing.sm, theme.typography.body.fontSize)
    val baseBtnStyle =
      s"background: ${theme.colors.primary}; color: ${theme.colors.onPrimary}; border: none; " +
      s"padding: ${py}px ${px}px; font-size: ${fs}px; " +
      s"border-radius: ${theme.radii.md}px; font-weight: 500; " +
      s"font-family: ${theme.typography.body.fontFamily};"

    val enabledButton = View.Element("button",
      attrs    = Map(
        "style"         -> AttrValue.Str(baseBtnStyle + " cursor: pointer; opacity: 1;"),
        "type"          -> AttrValue.Str("button"),
        "role"          -> AttrValue.Str("button"),
        "aria-disabled" -> AttrValue.Bool(false)
      ),
      events   = Map("click" -> EventHandler.SetSignalLiteral(submitted, true)),
      children = Seq(View.TextNode(() => "Submit"))
    )

    val disabledButton = View.Element("button",
      attrs    = Map(
        "style"         -> AttrValue.Str(baseBtnStyle + " cursor: not-allowed; opacity: 0.5;"),
        "type"          -> AttrValue.Str("button"),
        "role"          -> AttrValue.Str("button"),
        "aria-disabled" -> AttrValue.Bool(true)
      ),
      events   = Map.empty,
      children = Seq(View.TextNode(() => "Submit"))
    )

    // Enabled only when accept=true AND submitted=false.
    val reactiveButton = View.ShowSignal(submitted,
      disabledButton,
      View.ShowSignal(accept, enabledButton, disabledButton)
    )

    // ── Card footer ──────────────────────────────────────────────────
    val spacerView = Toolkit.lower(Tk.spacer(grow = true), theme)
    val badgeView  = Toolkit.lower(Tk.badge("v1", BadgeVariant.Notification), theme)
    val footerView = View.Element("div",
      attrs    = Map("style" -> AttrValue.Str(
        s"display: flex; flex-direction: row; gap: 8px; align-items: stretch")),
      events   = Map.empty,
      children = Seq(reactiveButton, spacerView, badgeView)
    )

    // ── Card ─────────────────────────────────────────────────────────
    val cardStyle =
      s"background: ${theme.colors.surface}; border: 1px solid ${theme.colors.border}; " +
      s"border-radius: ${theme.radii.md}px; box-shadow: ${theme.shadows.sm}; overflow: hidden;"
    val sectionStyle = s"padding: ${theme.spacing.md}px;"
    val divider = View.Element("hr",
      attrs    = Map("style" -> AttrValue.Str(
        s"border: 0; border-top: 1px solid ${theme.colors.border}; margin: 0;")),
      events   = Map.empty,
      children = Nil
    )
    val cardView = View.Element("div",
      attrs    = Map("style" -> AttrValue.Str(cardStyle)),
      events   = Map.empty,
      children = Seq(
        View.Element("div", Map("style" -> AttrValue.Str(sectionStyle)), Map.empty,
          Seq(Toolkit.lower(Tk.heading(3, "Sign-up"), theme))),
        divider,
        View.Element("div", Map("style" -> AttrValue.Str(sectionStyle)), Map.empty,
          Seq(formBody)),
        divider,
        View.Element("div", Map("style" -> AttrValue.Str(sectionStyle)), Map.empty,
          Seq(footerView))
      )
    )

    // ── Submission alert ─────────────────────────────────────────────
    // Shown when submitted=true.  Contains static prefix + reactive name.
    val alertStyle =
      s"background: #dcfce7; color: ${theme.colors.text}; padding: ${theme.spacing.md}px; " +
      s"border: 1px solid #86efac; border-radius: ${theme.radii.md}px;"
    val alertView = View.Element("div",
      attrs    = Map("role" -> AttrValue.Str("status"), "style" -> AttrValue.Str(alertStyle)),
      events   = Map.empty,
      children = Seq(View.Fragment(Seq(
        View.TextNode(() => "You have accepted the toolkit terms, "),
        View.SignalText(name)
      )))
    )
    val reactiveAlert = View.ShowSignal(submitted, alertView, View.Fragment(Nil))

    // ── Spinner: spins only after submit ─────────────────────────────
    val spinnerOn  = Toolkit.lower(Tk.spinner(WidgetSize.Sm), theme)
    val spinnerOff = spinnerOn match
      case View.Element(tag, attrs, events, children) =>
        // Replace animation with none so it's static before submit.
        val quietStyle = attrs.get("style") match
          case Some(AttrValue.Str(css)) =>
            AttrValue.Str(css.replace("animation: spin 1s linear infinite;", "animation: none;"))
          case other => other.getOrElse(AttrValue.Absent)
        View.Element(tag, attrs + ("style" -> quietStyle), events, children)
      case other => other
    val reactiveSpinner = View.ShowSignal(submitted, spinnerOn, spinnerOff)

    // ── Outer layout ─────────────────────────────────────────────────
    val tree: ToolkitNode = Tk.vstack(gap = 16)(
      Tk.heading(1, "Toolkit demo"),
      Tk.text("Backend-agnostic UI built through the Tk facade.",
              variant = TextVariant.Body),
      Tk.divider(),
      RawViewNode(cardView),
      RawViewNode(reactiveAlert),
      Tk.hstack(gap = 12, align = Alignment.Center)(
        RawViewNode(reactiveSpinner),
        Tk.text("Loading data...", variant = TextVariant.BodySmall),
        Tk.spacer(grow = true),
        Tk.badge("3 new", BadgeVariant.Success)
      )
    )

    val rendered: View = Toolkit.lower(tree, theme)

    val app = ComponentDef("App", Nil, _ =>
      View.Element(
        tag      = "div",
        attrs    = Map("id" -> AttrValue.Str("toolkit-demo")),
        events   = Map.empty,
        children = Seq(rendered)
      )
    )

    FrontendModule(
      components   = List(app),
      entryPoint   = "App",
      initialRoute = "/"
    )
