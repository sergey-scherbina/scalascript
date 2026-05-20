package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2d — ShowSignal + ToggleSignal on the Vue backend. */
class ShowSignalEmitTest extends AnyFunSuite:

  test("ShowSignal — ternary referencing this.cond (proxy)") {
    val backend = new VueFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val app = ComponentDef("App", Nil, _ => View.ShowSignal(
      cond      = visible,
      whenTrue  = View.TextNode(() => "T"),
      whenFalse = View.TextNode(() => "F")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const visible = ref(true);"),
      s"cond signal must be a ref in setup:\n$js")
    assert(js.contains("(this.visible ? 'T' : 'F')"),
      s"ShowSignal must lower to ternary on this.cond:\n$js")
  }

  test("ToggleSignal — arrow handler flips this.x via proxy") {
    val backend = new VueFrameworkBackend
    val flag = new ReactiveSignal[Boolean]("flag", false)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.ToggleSignal(flag)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const flag = ref(false);"))
    assert(js.contains("'onClick': () => { this.flag = !this.flag; }"),
      s"toggle must negate via proxy assignment:\n$js")
  }
