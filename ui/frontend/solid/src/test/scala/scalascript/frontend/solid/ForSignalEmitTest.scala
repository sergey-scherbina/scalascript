package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e unit tests — ForSignal + PushSignalLiteral + ClearSignalList
 *  on the Solid backend (createSignal([...]) + createEffect that
 *  wipes-and-rebuilds children). */
class ForSignalEmitTest extends AnyFunSuite:

  test("ForSignal — list lowers to createSignal([initial]) + createEffect wipe-and-rebuild") {
    val backend = new SolidFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a", "b"))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("const [todos, setTodos] = createSignal(['a', 'b']);"),
      s"list state must be hoisted as createSignal:\n$js")
    assert(js.contains("__render_item_todos"),
      s"per-list render-item function must be emitted:\n$js")
    assert(js.contains("createEffect(() => {"),
      s"subscription must use createEffect:\n$js")
    assert(js.contains("for (const __item of todos())"),
      s"effect body must iterate the current list signal:\n$js")
    // A2e.2 — render-item function now takes (__item, __idx).
    assert(js.contains("function __render_item_todos(__item, __idx)"),
      s"render-item function must take (item, idx):\n$js")
    assert(js.contains("removeChild"))
  }

  test("ForSignal — custom tag + attrs reach render-item") {
    val backend = new SolidFrameworkBackend
    val items = new ReactiveSignalList[Int]("rows", Seq(1, 2, 3))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(
      items, tag = "tr",
      attrs = Map("class" -> AttrValue.Str("row"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.createElement('tr')"))
    assert(js.contains("el.setAttribute('class', 'row');"))
    assert(js.contains("[1, 2, 3]"))
  }

  test("PushSignalLiteral — setX(prev => prev.concat([value]))") {
    val backend = new SolidFrameworkBackend
    val items   = new ReactiveSignalList[String]("xs", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.PushSignalLiteral(items, "new")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [xs, setXs] = createSignal([]);"))
    assert(js.contains("addEventListener('click', () => setXs(prev => prev.concat(['new'])))"))
  }

  test("ClearSignalList — setX([])") {
    val backend = new SolidFrameworkBackend
    val items = new ReactiveSignalList[Int]("counts", Seq(1))
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.ClearSignalList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("addEventListener('click', () => setCounts([]))"))
  }
