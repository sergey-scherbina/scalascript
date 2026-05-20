package scalascript.frontend.examples

import scalascript.frontend.*

/** Demo 2 — Show / hide.
 *
 *  Exercises `ReactiveSignal[Boolean]` + `ToggleSignal` +
 *  `View.ShowSignal`.  A single toggle button flips visibility of
 *  a counter span; the counter itself is wired the same way as
 *  `CounterDemo` so the demo also shows that hidden subtrees keep
 *  their state across toggle cycles. */
object ShowHideDemo:

  val Name: String = "show-hide"

  def buildModule(): FrontendModule =
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val count   = new ReactiveSignal[Int]("count", 0)

    val app = ComponentDef("App", Nil, _ =>
      View.Element(
        tag      = "div",
        attrs    = Map("id" -> AttrValue.Str("show-hide-demo")),
        events   = Map.empty,
        children = Seq(
          View.Element(
            "h1",
            attrs    = Map.empty,
            events   = Map.empty,
            children = Seq(View.TextNode(() => "Show / hide"))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("toggle")),
            events   = Map("click" -> EventHandler.ToggleSignal(visible)),
            children = Seq(View.TextNode(() => "toggle"))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("inc")),
            events   = Map("click" -> EventHandler.IncrementSignal(count)),
            children = Seq(View.TextNode(() => "+"))
          ),
          View.ShowSignal(
            cond      = visible,
            whenTrue  = View.Element(
              "span",
              attrs    = Map("id" -> AttrValue.Str("box")),
              events   = Map.empty,
              children = Seq(View.SignalText(count))
            ),
            whenFalse = View.TextNode(() => "")
          )
        )
      )
    )

    FrontendModule(
      components   = List(app),
      entryPoint   = "App",
      initialRoute = "/"
    )
