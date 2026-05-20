package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e.2 unit tests — rich ForSignal templates on the Custom
 *  backend.  The render-item function takes `(__item, __idx)` and
 *  the body walks the user template with ItemText / RemoveSelfFromList
 *  holes resolved against those iteration variables. */
class RichForSignalEmitTest extends AnyFunSuite:

  test("rich template — ItemText resolves to String(__item) inside render-item") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a"))
    // Template: <li><span>String(item)</span></li>
    val template = View.Element("li", Map.empty, Map.empty,
      Seq(View.Element("span", Map.empty, Map.empty, Seq(View.ItemText)))
    )
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("function __render_item_todos(__item, __idx)"),
      s"render-item must take (item, idx) for rich templates:\n$js")
    assert(js.contains("document.createTextNode(String(__item))"),
      s"ItemText must lower to String(__item) inside the template:\n$js")
    // Outer iteration emits the index-counter loop.
    assert(js.contains("let __idx = 0; for (const __item of"),
      s"outer loop must track __idx for RemoveSelfFromList:\n$js")
  }

  test("RemoveSelfFromList — captures __idx in IIFE, filters that slot") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("first"))
    val template = View.Element("li", Map.empty, Map.empty, Seq(
      View.ItemText,
      View.Element("button",
        Map("class" -> AttrValue.Str("del")),
        Map("click" -> EventHandler.RemoveSelfFromList(items)),
        Seq(View.TextNode(() => "x"))
      )
    ))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items, itemTemplate = Some(template)))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // IIFE captures __idx so each emitted listener remembers its slot.
    assert(js.contains("((__capturedIdx) => () => __setSignalList('todos'"),
      s"RemoveSelfFromList must IIFE-capture __idx:\n$js")
    assert(js.contains(".filter((_, i) => i !== __capturedIdx)"),
      s"filter must drop the captured index:\n$js")
  }

  test("ItemText outside an item template emits an empty text node (graceful no-op)") {
    val backend = new CustomFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty, Seq(View.ItemText)
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.createTextNode('')"),
      s"ItemText outside a template falls back to empty text node:\n$js")
    assert(!js.contains("String(__item)"),
      s"no item reference should leak when not in template:\n$js")
  }

  test("RemoveSelfFromList outside an item template — inert listener comment") {
    val backend = new CustomFrameworkBackend
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

  test("rich template — simple-form ForSignal still produces the A2e shape") {
    val backend = new CustomFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a", "b"))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items))  // no itemTemplate
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    // Old shape still produced (with the __idx threading bolted on).
    assert(js.contains("function __render_item_todos(__item, __idx)"))
    assert(js.contains("el.textContent = String(__item);"))
  }
