package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2d unit tests — ShowSignal + ToggleSignal on the Custom
 *  backend (subscribe-and-swap). */
class ShowSignalEmitTest extends AnyFunSuite:

  test("ShowSignal — emits wrapper span + cond subscriber + replaceChild swap") {
    val backend = new CustomFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val app = ComponentDef("App", Nil, _ => View.ShowSignal(
      cond      = visible,
      whenTrue  = View.TextNode(() => "SHOWN"),
      whenFalse = View.TextNode(() => "hidden")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Wrapper span gets a unique var.
    assert(js.contains("document.createElement('span')"),
      s"ShowSignal must build a wrapper <span>:\n$js")
    // Both branches pre-built.
    assert(js.contains("'SHOWN'"))
    assert(js.contains("'hidden'"))
    // Subscriber registered on visible.
    assert(js.contains("__ssc_signals['visible'].subs.add"),
      s"ShowSignal must subscribe to the cond signal:\n$js")
    // Replace-child semantics on update.
    assert(js.contains("replaceChild"),
      s"ShowSignal must use replaceChild on swap:\n$js")
    // Initial value chosen from cond's current value.
    assert(js.contains("__ssc_signals['visible'].value"),
      s"initial branch read must come from cond cell:\n$js")
  }

  test("ToggleSignal — addEventListener flips __ssc_signals[name].value") {
    val backend = new CustomFrameworkBackend
    val flag = new ReactiveSignal[Boolean]("flag", false)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.ToggleSignal(flag)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("__ssc_signals['flag']"),
      s"toggle must register the signal cell:\n$js")
    assert(js.contains("addEventListener('click', () => __setSignal('flag', !__ssc_signals['flag'].value))"),
      s"toggle handler must negate the cell value:\n$js")
  }

  test("ShowSignal + ToggleSignal — counter-hide demo wires up both") {
    val backend = new CustomFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val count   = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element("button",
          Map("id" -> AttrValue.Str("tog")),
          Map("click" -> EventHandler.ToggleSignal(visible)),
          Seq(View.TextNode(() => "toggle"))
        ),
        View.ShowSignal(
          cond      = visible,
          whenTrue  = View.Element("span",
                       Map("id" -> AttrValue.Str("c")),
                       Map.empty,
                       Seq(View.SignalText(count))),
          whenFalse = View.TextNode(() => "")
        )
      )
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("__ssc_signals['visible']"))
    assert(js.contains("__ssc_signals['count']"))
    assert(js.contains("replaceChild"))
    assert(js.contains("__setSignal('visible', !__ssc_signals['visible'].value)"))
  }

  test("ShowSignal with empty Fragment branch falls back to placeholder text node") {
    val backend = new CustomFrameworkBackend
    val cond = new ReactiveSignal[Boolean]("cond", true)
    val app = ComponentDef("App", Nil, _ => View.ShowSignal(
      cond      = cond,
      whenTrue  = View.TextNode(() => "T"),
      whenFalse = View.Fragment(Nil)  // empty → null at compile, needs placeholder
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("replaceChild"),
      s"empty branch must still get a Node placeholder:\n$js")
    assert(js.contains("document.createTextNode('')"),
      s"placeholder is an empty text node:\n$js")
  }
