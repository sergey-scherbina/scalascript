package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e unit tests — ForSignal + PushSignalLiteral + ClearSignalList
 *  on the Custom backend (wipe-and-rebuild subscription). */
class ForSignalEmitTest extends AnyFunSuite:

  test("ForSignal — registers list cell + emits wrapper + render-item fn + subscriber") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a", "b"))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("__ssc_lists['todos'] = { value: ['a', 'b']"),
      s"list cell must be registered with the initial array:\n$js")
    assert(js.contains("function __setSignalList(name, value)"),
      s"list setter helper must be emitted:\n$js")
    assert(js.contains("__render_item_todos"),
      s"per-list render-item function must be emitted:\n$js")
    assert(js.contains("__ssc_lists['todos'].subs.add"),
      s"subscriber must be wired so changes rebuild children:\n$js")
    assert(js.contains("document.createElement('span')"),
      s"wrapper span must be created:\n$js")
    assert(js.contains("removeChild") && js.contains("appendChild"),
      s"subscription must wipe-and-rebuild children:\n$js")
  }

  test("ForSignal — custom tag + attrs propagate into render-item") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[Int]("rows", Seq(1, 2, 3))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(
      items,
      tag   = "tr",
      attrs = Map("class" -> AttrValue.Str("row"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.createElement('tr')"),
      s"tag must thread through:\n$js")
    assert(js.contains("setAttribute('class', 'row')"),
      s"attrs must apply to every item:\n$js")
    assert(js.contains("[1, 2, 3]"),
      s"initial JS array must reflect the Seq contents:\n$js")
  }

  test("PushSignalLiteral — addEventListener concats value + notifies subscribers") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[String]("xs", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.PushSignalLiteral(items, "new")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("__ssc_lists['xs']"),
      s"push must register the list cell:\n$js")
    assert(js.contains("__setSignalList('xs', __ssc_lists['xs'].value.concat(['new']))"),
      s"push handler must concat the value:\n$js")
  }

  test("ClearSignalList — addEventListener resets to []") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[Int]("counts", Seq(1, 2))
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.ClearSignalList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("__setSignalList('counts', [])"),
      s"clear handler must reset to empty array:\n$js")
  }

  test("ForSignal — duplicate jsName with different initials throws loudly") {
    val backend = new CustomFrameworkBackend
    val a = new ReactiveSignalList[String]("xs", Seq("a"))
    val b = new ReactiveSignalList[String]("xs", Seq("b"))
    val app = ComponentDef("App", Nil, _ => View.Fragment(Seq(
      View.ForSignal(a),
      View.ForSignal(b)
    )))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("xs"))
  }
