package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class ReactiveEmitTest extends AnyFunSuite:

  test("SignalText — emits cell + subscriber wiring") {
    val backend = new CustomFrameworkBackend
    val greeting = new ReactiveSignal[String]("greeting", "Hello")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(greeting))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Cell + subscriber Set declared once at the top.
    assert(js.contains("__ssc_signals['greeting']"),
      s"expected signal cell registration:\n$js")
    assert(js.contains("subs: new Set()"))
    // Initial value is embedded as a JS literal.
    assert(js.contains("'Hello'"),
      s"expected initial 'Hello' literal:\n$js")
    // __setSignal helper + window-shim are emitted.
    assert(js.contains("function __setSignal(name, value)"))
    assert(js.contains("window.__setSignal = __setSignal"))
    // Text node reads initial value AND registers a subscriber.
    assert(js.contains("document.createTextNode(__ssc_signals['greeting'].value)"))
    assert(js.contains("__ssc_signals['greeting'].subs.add"))
  }

  test("SignalText — multiple bindings to the same signal share one cell") {
    val backend = new CustomFrameworkBackend
    val count = new ReactiveSignal[String]("count", "0")
    val twiceWired = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(count), View.SignalText(count))
    ))
    val js = backend.emit(FrontendModule(List(twiceWired), "App", "/")).js
    // Cell is declared exactly once.
    val regCount = js.split("\\Q__ssc_signals['count'] = {\\E", -1).length - 1
    assert(regCount == 1,
      s"signal cell registered $regCount times; expected exactly 1:\n$js")
    // But subscriber Set gets `.add(...)` once per binding.
    val addCount = js.split("\\Q__ssc_signals['count'].subs.add\\E", -1).length - 1
    assert(addCount == 2,
      s"expected 2 subscriber registrations; got $addCount:\n$js")
  }

  test("SeedSignal — emits pristine source subscription and dirty input writes") {
    val backend = new CustomFrameworkBackend
    val source = new ReactiveSignal[String]("sourceName", "Ada")
    val draft  = new SeedSignal("draftName", source)
    val app = ComponentDef("App", Nil, _ => View.TextInput(
      draft,
      placeholder = "Name",
      multiline = false,
      secure = false,
      style = Style()
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("__ssc_signals['sourceName'] = { value: 'Ada', subs: new Set() };"), s"source cell:\n$js")
    assert(js.contains("__ssc_signals['draftName'] = { value: 'Ada', subs: new Set() };"), s"draft cell:\n$js")
    assert(js.contains("__ssc_seedPristine['draftName'] = true;"), s"pristine table:\n$js")
    assert(js.contains("if (Object.prototype.hasOwnProperty.call(__ssc_seedPristine, name) && !opts.preserveSeedPristine) __ssc_seedPristine[name] = false;"),
      s"dirty __setSignal:\n$js")
    assert(js.contains("__ssc_signals['sourceName'].subs.add((v) => { if (__ssc_seedPristine['draftName']) __setSignal('draftName', v, { preserveSeedPristine: true }); });"),
      s"source subscription:\n$js")
    assert(js.contains("addEventListener('input', (e) => __setSignal('draftName', e.target.value));"),
      s"input write:\n$js")
  }

  test("ReactiveSignal — invalid jsName rejected at emit time") {
    val backend = new CustomFrameworkBackend
    val bad = new ReactiveSignal[String]("with-dash", "x")
    val app = ComponentDef("App", Nil, _ => View.SignalText(bad))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("with-dash"))
    assert(ex.getMessage.contains("[A-Za-z_]"))
  }

  test("ReactiveSignal — duplicate jsName with different initial values rejected") {
    val backend = new CustomFrameworkBackend
    val a = new ReactiveSignal[String]("x", "alpha")
    val b = new ReactiveSignal[String]("x", "beta")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(a), View.SignalText(b))
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("twice"))
    assert(ex.getMessage.contains("x"))
  }

  test("Static + reactive — pure static View still does not emit signal scaffolding") {
    val backend = new CustomFrameworkBackend
    val pure = ComponentDef("App", Nil, _ => View.TextNode(() => "static"))
    val js = backend.emit(FrontendModule(List(pure), "App", "/")).js
    assert(!js.contains("__ssc_signals"),
      s"static-only emit must not allocate signal scaffolding:\n$js")
    assert(!js.contains("__setSignal"),
      s"static-only emit must not define __setSignal:\n$js")
  }
