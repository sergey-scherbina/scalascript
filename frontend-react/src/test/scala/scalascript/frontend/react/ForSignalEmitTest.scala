package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A2e unit tests — ForSignal + PushSignalLiteral + ClearSignalList
 *  on the React backend (useState([...]) + array.map). */
class ForSignalEmitTest extends AnyFunSuite:

  test("ForSignal — list lowers to useState([initial]) and map() with key") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[String]("todos", Seq("a", "b"))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(items))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("const [todos, setTodos] = useState(['a', 'b']);"),
      s"list state must be hoisted as useState:\n$js")
    assert(js.contains("todos.map(item => h('li', { 'key': String(item) }, String(item)))"),
      s"ForSignal must lower to map() with key:\n$js")
  }

  test("ForSignal — custom tag + class attr (React rewrites to className)") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[Int]("rows", Seq(1))
    val app = ComponentDef("App", Nil, _ => View.ForSignal(
      items, tag = "tr",
      attrs = Map("class" -> AttrValue.Str("row"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'className': 'row'"),
      s"class attr must be rewritten to className:\n$js")
    assert(js.contains("rows.map(item => h('tr'"),
      s"tag must thread through:\n$js")
  }

  test("PushSignalLiteral — functional setState concat") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[String]("xs", Seq.empty)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.PushSignalLiteral(items, "new")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [xs, setXs] = useState([]);"),
      s"list signal still gets useState even when only referenced in events:\n$js")
    assert(js.contains("'onClick': () => setXs(prev => prev.concat(['new']))"),
      s"push must lower to functional setState concat:\n$js")
  }

  test("ClearSignalList — setX([])") {
    val backend = new ReactFrameworkBackend
    val items   = new ReactiveSignalList[Int]("counts", Seq(1, 2))
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button", Map.empty,
      Map("click" -> EventHandler.ClearSignalList(items)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'onClick': () => setCounts([])"))
  }
