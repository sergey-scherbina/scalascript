package scalascript.frontend.examples

import scalascript.frontend.*

/** Demo 1 — Counter.
 *
 *  Exercises `ReactiveSignal[Int]` + the canonical numeric event
 *  handlers (`IncrementSignal`, `SetSignalLiteral`) + `SignalText`
 *  for the text-node binding.  Two buttons bump and reset; a span
 *  shows the live value.
 *
 *  Same IR is emitted by all four backends — see the per-backend
 *  output under `target/frontend-examples/counter/`. */
object CounterDemo:

  val Name: String = "counter"

  /** Build the framework-agnostic IR for this demo.  Fresh
   *  `ReactiveSignal` per call so callers that emit through several
   *  backends in sequence don't share state. */
  def buildModule(): FrontendModule =
    val count = new ReactiveSignal[Int]("count", 0)

    val app = ComponentDef("App", Nil, _ =>
      View.Element(
        tag      = "div",
        attrs    = Map("id" -> AttrValue.Str("counter-demo")),
        events   = Map.empty,
        children = Seq(
          View.Element(
            "h1",
            attrs    = Map.empty,
            events   = Map.empty,
            children = Seq(View.TextNode(() => "Counter"))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("dec")),
            events   = Map("click" -> EventHandler.IncrementSignal(count, by = -1)),
            children = Seq(View.TextNode(() => "-"))
          ),
          View.Element(
            "span",
            attrs    = Map("id" -> AttrValue.Str("display")),
            events   = Map.empty,
            children = Seq(View.SignalText(count))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("inc")),
            events   = Map("click" -> EventHandler.IncrementSignal(count, by = 1)),
            children = Seq(View.TextNode(() => "+"))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("reset")),
            events   = Map("click" -> EventHandler.SetSignalLiteral(count, 0)),
            children = Seq(View.TextNode(() => "reset"))
          )
        )
      )
    )

    FrontendModule(
      components   = List(app),
      entryPoint   = "App",
      initialRoute = "/"
    )
