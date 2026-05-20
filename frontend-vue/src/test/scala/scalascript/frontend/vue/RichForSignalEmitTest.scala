package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e.2 unit tests — rich ForSignal templates on the Vue backend
 *  (`this.list.map((item, index) => h(...))` with ItemText →
 *  `String(item)` and RemoveSelfFromList → arrow that re-assigns
 *  `this.list` to the filtered array). */
class RichForSignalEmitTest extends AnyFunSuite:

  test("rich template — ItemText resolves to String(item) in render arrow") {
    val backend = new VueFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a"))
    val template = View.Element("li", Map.empty, Map.empty,
      Seq(View.Element("span", Map.empty, Map.empty, Seq(View.ItemText)))
    )
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("this.todos.map((item, index) =>"),
      s"map callback on this.list must accept (item, index):\n$js")
    // Vue wraps Element children in `[...]` arrays, so the
    // single-text-child case is `h('span', null, [String(item)])`.
    assert(js.contains("h('span', null, [String(item)])"),
      s"ItemText must lower to String(item) inside template:\n$js")
    assert(js.contains("'key': String(item)"),
      s"template root must get a key prop:\n$js")
  }

  test("RemoveSelfFromList — arrow filters this.list by the captured index") {
    val backend = new VueFrameworkBackend
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

    assert(js.contains("'onClick': () => { this.todos = this.todos.filter((_, i) => i !== index); }"),
      s"RemoveSelfFromList must lower to filter assignment via proxy:\n$js")
  }

  test("ItemText outside an item template emits ''") {
    val backend = new VueFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty, Seq(View.ItemText)
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("h('div', null, [''])"),
      s"ItemText outside a template must fall back to '':\n$js")
  }

  test("RemoveSelfFromList outside an item template — inert comment prop") {
    val backend = new VueFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.RemoveSelfFromList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("RemoveSelfFromList used outside an item template"))
  }
