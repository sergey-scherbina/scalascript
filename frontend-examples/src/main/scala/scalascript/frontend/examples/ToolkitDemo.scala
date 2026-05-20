package scalascript.frontend.examples

import scalascript.frontend.*
import scalascript.frontend.toolkit.*
import scalascript.frontend.toolkit.Tk

/** v1.18 Phase C — Cross-backend toolkit demo.
 *
 *  A small but real application written **entirely** through the
 *  high-level toolkit (`Tk` facade) — no raw `View` construction
 *  in user code.  The same tree compiles through React, Vue, Solid,
 *  and the Custom backend via the standard `FrontendModule` →
 *  emitter pipeline.
 *
 *  What the demo exercises:
 *    - Layout: vstack / hstack / box / divider / card
 *    - Inputs: textField + checkbox bound to signals
 *    - Display: heading + text + badge + alert + spinner
 *    - Containers: card with header + body + footer
 *    - Theming: theme tokens reach the rendered output
 *
 *  This proves the toolkit's "backend-agnostic" claim isn't just at
 *  the View AST level — the lowered View actually flows through
 *  every backend's emitter without further toolkit-specific logic.
 *
 *  Output lives under `target/frontend-examples/toolkit-demo/{custom,
 *  react,vue,solid}/` after running the demo's emit smoke tests. */
object ToolkitDemo:

  /** Demo name; matches the directory convention of the existing
   *  CounterDemo / ShowHideDemo / TodoListDemo demos. */
  val Name: String = "toolkit-demo"

  /** Build a fresh module per invocation — caller can run the same
   *  demo through several backends in sequence without state leaking
   *  between them. */
  def buildModule(theme: Theme = Theme.default): FrontendModule =
    val name    = new ReactiveSignal[String]("name",    "Alice")
    val accept  = new ReactiveSignal[Boolean]("accept", false)

    val tree: ToolkitNode = Tk.vstack(gap = 16)(
      Tk.heading(1, "Toolkit demo"),
      Tk.text("Backend-agnostic UI built through the Tk facade.",
              variant = TextVariant.Body),

      Tk.divider(),

      // A small "card" with a form-ish layout: name + agreement
      // checkbox + a primary button + a status badge.
      Tk.card(
        header = Some(Tk.heading(3, "Sign-up")),
        footer = Some(Tk.hstack(gap = 8)(
          Tk.button("Submit", onClick = () => (), kind = ButtonKind.Primary),
          Tk.spacer(grow = true),
          Tk.badge("v1", BadgeVariant.Notification)
        ))
      )(
        Tk.vstack(gap = 12)(
          Tk.textField(value = name, label = Some("Display name"), required = true),
          Tk.checkbox (checked = accept, label = "I accept the toolkit terms.")
        )
      ),

      // A second card showing display widgets.
      Tk.notice(severity = AlertSeverity.Success, title = Some("Status")) {
        Tk.text("All systems nominal.")
      },

      Tk.hstack(gap = 12, align = Alignment.Center)(
        Tk.spinner(WidgetSize.Sm),
        Tk.text("Loading data...", variant = TextVariant.BodySmall),
        Tk.spacer(grow = true),
        Tk.badge("3 new", BadgeVariant.Success)
      )
    )

    // Lower once at module-build time — every backend consumes the
    // resulting View identically.
    val rendered: View = Toolkit.lower(tree, theme)

    val app = ComponentDef("App", Nil, _ =>
      // Wrap in a stable root <div id="toolkit-demo"> so backends'
      // selectors can locate the mount point in tests.
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
