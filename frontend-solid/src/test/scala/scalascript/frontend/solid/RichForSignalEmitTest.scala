package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e.2 unit tests — rich ForSignal templates on the Solid backend
 *  (createEffect rebuild with a render-item function that takes
 *  `(__item, __idx)`). */
class RichForSignalEmitTest extends AnyFunSuite:

  test("rich template — ItemText resolves to String(__item) in render-item body") {
    val backend = new SolidFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a"))
    val template = View.Element("li", Map.empty, Map.empty, Seq(View.ItemText))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("function __render_item_todos(__item, __idx)"),
      s"render-item must take (item, idx):\n$js")
    assert(js.contains("document.createTextNode(String(__item))"),
      s"ItemText must lower to String(__item):\n$js")
  }

  test("RemoveSelfFromList — IIFE captures __idx, setX filters that slot") {
    val backend = new SolidFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("first"))
    val template = View.Element("li", Map.empty, Map.empty, Seq(
      View.Element("button",
        Map.empty,
        Map("click" -> EventHandler.RemoveSelfFromList(items)),
        Seq(View.TextNode(() => "x"))
      )
    ))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("((__capturedIdx) => () => setTodos(prev => prev.filter((_, i) => i !== __capturedIdx)))(__idx)"),
      s"RemoveSelfFromList must IIFE-capture __idx and filter:\n$js")
  }

  test("ItemText outside an item template emits empty text node") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty, Seq(View.ItemText)
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.createTextNode('')"))
    assert(!js.contains("String(__item)"))
  }

  test("RemoveSelfFromList outside an item template — inert comment") {
    val backend = new SolidFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.RemoveSelfFromList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("RemoveSelfFromList used outside an item template"))
  }
