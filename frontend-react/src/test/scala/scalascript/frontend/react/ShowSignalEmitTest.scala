package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2d unit tests — ShowSignal + ToggleSignal on the React
 *  backend (ternary in render, re-evaluated on setState). */
class ShowSignalEmitTest extends AnyFunSuite:

  test("ShowSignal — emits ternary referencing useState variable") {
    val backend = new ReactFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val app = ComponentDef("App", Nil, _ => View.ShowSignal(
      cond      = visible,
      whenTrue  = View.TextNode(() => "SHOWN"),
      whenFalse = View.TextNode(() => "hidden")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // useState hoisted for the cond signal.
    assert(js.contains("const [visible, setVisible] = useState(true);"),
      s"cond signal must lower to useState:\n$js")
    // Ternary in render body.
    assert(js.contains("(visible ? 'SHOWN' : 'hidden')"),
      s"ShowSignal must lower to ternary:\n$js")
  }

  test("ToggleSignal — functional setX(c => !c)") {
    val backend = new ReactFrameworkBackend
    val flag = new ReactiveSignal[Boolean]("flag", false)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.ToggleSignal(flag)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [flag, setFlag] = useState(false);"))
    assert(js.contains("'onClick': () => setFlag(c => !c)"),
      s"toggle must lower to functional setState negation:\n$js")
  }

  test("ShowSignal + ToggleSignal — counter-hide demo composes") {
    val backend = new ReactFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val count   = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element("button",
          Map.empty,
          Map("click" -> EventHandler.ToggleSignal(visible)),
          Seq(View.TextNode(() => "toggle"))
        ),
        View.ShowSignal(
          cond      = visible,
          whenTrue  = View.SignalText(count),
          whenFalse = View.TextNode(() => "")
        )
      )
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("useState(true)"))
    assert(js.contains("useState(0)"))
    assert(js.contains("(visible ? count : ''"))
  }
