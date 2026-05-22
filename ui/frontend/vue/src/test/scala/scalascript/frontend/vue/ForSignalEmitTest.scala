package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e unit tests — ForSignal + PushSignalLiteral + ClearSignalList
 *  on the Vue backend (ref([...]) + this.x.map). */
class ForSignalEmitTest extends AnyFunSuite:

  test("ForSignal — list lowers to ref([initial]) returned from setup + this.x.map in render") {
    val backend = new VueFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a", "b"))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("const todos = ref(['a', 'b']);"),
      s"list must lower to ref(initial):\n$js")
    assert(js.contains("return { todos };"),
      s"setup() must return the ref so the proxy auto-unwraps it:\n$js")
    assert(js.contains("this.todos.map((item, index) => h('li', { 'key': String(item) }, String(item)))"),
      s"ForSignal must lower to this.x.map in render arrow:\n$js")
  }

  test("ForSignal — custom tag + attrs (no class rewrite on Vue)") {
    val backend = new VueFrameworkBackend
    val items   = new ReactiveSignalList[Int]("rows", Seq(1))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(
      items, tag = "tr",
      attrs = Map("class" -> AttrValue.Str("row"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'class': 'row'"),
      s"Vue keeps `class` natively:\n$js")
    assert(js.contains("this.rows.map((item, index) => h('tr'"),
      s"tag must thread through:\n$js")
  }

  test("PushSignalLiteral — arrow handler concats via this assignment") {
    val backend = new VueFrameworkBackend
    val items   = new ReactiveSignalList[String]("xs", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.PushSignalLiteral(items, "new")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const xs = ref([]);"),
      s"list signal still gets ref even when only referenced in events:\n$js")
    assert(js.contains("return { xs };"))
    assert(js.contains("'onClick': () => { this.xs = this.xs.concat(['new']); }"),
      s"push must lower to arrow that re-assigns the proxy field:\n$js")
  }

  test("ClearSignalList — arrow handler sets this.x = []") {
    val backend = new VueFrameworkBackend
    val items   = new ReactiveSignalList[Int]("counts", Seq(1, 2))
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.ClearSignalList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'onClick': () => { this.counts = []; }"))
  }

  test("setup returns scalar + list refs together when both exist") {
    val backend = new VueFrameworkBackend
    val count   = new ReactiveSignal[Int]("count", 0)
    val items   = new ReactiveSignalList[String]("xs", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.SignalText(count),
        View.ForSignal(items)
      )
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("return { count, xs };"),
      s"setup must return both scalar + list refs:\n$js")
  }
