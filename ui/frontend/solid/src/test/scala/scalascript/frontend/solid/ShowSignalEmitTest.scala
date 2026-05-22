package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2d — ShowSignal + ToggleSignal on the Solid backend. */
class ShowSignalEmitTest extends AnyFunSuite:

  test("ShowSignal — wrapper span + createEffect drives replaceChild") {
    val backend = new SolidFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val app = ComponentDef("App", Nil, _ => View.ShowSignal(
      cond      = visible,
      whenTrue  = View.TextNode(() => "T"),
      whenFalse = View.TextNode(() => "F")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [visible, setVisible] = createSignal(true);"))
    assert(js.contains("createElement('span')"),
      s"wrapper span expected:\n$js")
    assert(js.contains("createEffect(() => {"),
      s"createEffect drives the swap:\n$js")
    assert(js.contains("replaceChild"),
      s"replaceChild expected:\n$js")
    // cond is read AS A GETTER inside createEffect — that's the
    // Solid auto-tracking idiom.
    assert(js.contains("visible() ?"),
      s"cond getter must be called inside createEffect:\n$js")
  }

  test("ToggleSignal — addEventListener with functional setX(c => !c)") {
    val backend = new SolidFrameworkBackend
    val flag = new ReactiveSignal[Boolean]("flag", false)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.ToggleSignal(flag)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("createSignal(false)"))
    assert(js.contains("addEventListener('click', () => setFlag(c => !c))"))
  }
