package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class EventEmitTest extends AnyFunSuite:

  test("SetSignalLiteral — emits addEventListener that calls __setSignal") {
    val backend = new CustomFrameworkBackend
    val greeting = new ReactiveSignal[String]("greeting", "Hello")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      attrs    = Map.empty,
      events   = Map("click" -> EventHandler.SetSignalLiteral(greeting, "world")),
      children = Seq(View.TextNode(() => "Greet"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Signal cell auto-registered because the handler references it.
    assert(js.contains("__ssc_signals['greeting']"),
      s"event handler must auto-register its signal:\n$js")
    // addEventListener wired with the right value.
    assert(js.contains("addEventListener('click'"),
      s"expected click listener:\n$js")
    assert(js.contains("__setSignal('greeting', 'world')"),
      s"expected __setSignal call with literal:\n$js")
  }

  test("IncrementSignal — counter wiring reads cell + writes back +by") {
    val backend = new CustomFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      attrs    = Map.empty,
      events   = Map("click" -> EventHandler.IncrementSignal(count, by = 1)),
      children = Seq(View.SignalText(count))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Initial value is a JS number, not a string literal.
    assert(js.contains("__ssc_signals['count'] = { value: 0,"),
      s"Int signal must use bare numeric literal as initial value:\n$js")
    // addEventListener reads the current value + 1.
    assert(js.contains("__setSignal('count', __ssc_signals['count'].value + 1)"),
      s"increment must read current value + by:\n$js")
    // SignalText subscribes to the same cell.
    assert(js.contains("__ssc_signals['count'].subs.add"),
      s"counter display must subscribe to the signal:\n$js")
  }

  test("IncrementSignal — custom step (by = 5)") {
    val backend = new CustomFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 100)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      attrs = Map.empty,
      events = Map("click" -> EventHandler.IncrementSignal(count, by = 5)),
      children = Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains(".value + 5)"),
      s"increment by=5 should appear in handler:\n$js")
    assert(js.contains("value: 100,"))
  }

  test("Boolean signal — initial value emitted as `true` / `false`, not quoted") {
    val backend = new CustomFrameworkBackend
    val flag = new ReactiveSignal[Boolean]("flag", true)
    val app = ComponentDef("App", Nil, _ => View.SignalText(flag))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("value: true,"),
      s"Boolean signal initial must be bare 'true':\n$js")
    assert(!js.contains("value: 'true'"),
      s"Boolean must not be quoted:\n$js")
  }

  test("Double signal — formats integers without .0, fractions with decimal") {
    val backend = new CustomFrameworkBackend
    val whole  = new ReactiveSignal[Double]("whole", 42.0)
    val fract  = new ReactiveSignal[Double]("fract", 3.14)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(whole), View.SignalText(fract))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("value: 42,"))
    assert(js.contains("value: 3.14,"))
  }

  test("SetSignalLiteral — value type mismatch is caught with a helpful message") {
    val backend = new CustomFrameworkBackend
    val countInt = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      // Java collection class isn't supported — should explode loudly.
      events = Map("click" -> EventHandler.SetSignalLiteral(countInt, new java.util.ArrayList[String]())),
      children = Seq.empty
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("Supported"))
    assert(ex.getMessage.contains("String"))
  }
