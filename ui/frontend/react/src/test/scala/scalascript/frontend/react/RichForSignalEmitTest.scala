package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e.2 unit tests — rich ForSignal templates on the React backend
 *  (`.map((item, index) => h(...))` with ItemText → `String(item)`
 *  and RemoveSelfFromList → functional setState filter). */
class RichForSignalEmitTest extends AnyFunSuite:

  test("rich template — ItemText resolves to String(item) inside map") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a"))
    val template = View.Element("li", Map.empty, Map.empty,
      Seq(View.Element("span", Map.empty, Map.empty, Seq(View.ItemText)))
    )
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // .map callback gets (item, index).
    assert(js.contains("todos.map((item, index) =>"),
      s"map callback must accept (item, index):\n$js")
    // ItemText resolved to String(item).
    assert(js.contains("h('span', null, String(item))"),
      s"ItemText must lower to String(item) inside template:\n$js")
    // Template root gets a key injected.
    assert(js.contains("'key': String(item)"),
      s"template root must get a key prop:\n$js")
  }

  test("RemoveSelfFromList — functional setState filter on the captured index") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("first"))
    val template = View.Element("li", Map.empty, Map.empty, Seq(
      View.ItemText,
      View.Element("button",
        Map.empty,
        Map("click" -> EventHandler.RemoveSelfFromList(items)),
        Seq(View.TextNode(() => "x"))
      )
    ))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("'onClick': () => setTodos(prev => prev.filter((_, i) => i !== index))"),
      s"RemoveSelfFromList must lower to functional filter on map's index:\n$js")
  }

  test("ItemText outside an item template emits ''") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty, Seq(View.ItemText)
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("h('div', null, '')"),
      s"ItemText outside a template must fall back to '':\n$js")
  }

  test("RemoveSelfFromList outside an item template — inert comment prop") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.RemoveSelfFromList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("RemoveSelfFromList used outside an item template"),
      s"out-of-template usage must emit a no-op marker:\n$js")
  }
