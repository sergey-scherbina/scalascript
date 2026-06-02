package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class FetchTableSemanticEmitTest extends AnyFunSuite:

  private def emitJs(root: View[?]): String =
    val app = ComponentDef("App", Nil, _ => root)
    new ReactFrameworkBackend().emit(FrontendModule(List(app), "App", "/")).js

  private val tick = new ReactiveSignal[Int]("tick", 0)

  @annotation.nowarn("cat=deprecation")
  private def makeFetchTable(tableId: String): View.FetchTable =
    View.FetchTable(tableId, "/api/items", "/api/delete", tick, None)

  // ── ForModel: empty fieldPath fix ──────────────────────────────────────

  test("ForModel with empty fieldPath uses bindingVar directly as list") {
    val inner = View.TextNode(() => "x")
    val view  = View.ForModel("items", "", "row", inner)
    val js    = emitJs(view)
    assert(js.contains("(items || []).map((row, _idx) =>"), s"got: $js")
    assert(!js.contains("items."), s"must not emit items. with empty fieldPath, got: $js")
  }

  test("ForModel with non-empty fieldPath still uses dot notation") {
    val inner = View.TextNode(() => "x")
    val view  = View.ForModel("bs", "lines", "line", inner)
    val js    = emitJs(view)
    assert(js.contains("(bs.lines || []).map((line, _idx) =>"), s"got: $js")
  }

  // ── DeleteItem handler ──────────────────────────────────────────────────

  test("DeleteItem inside ForModel emits POST fetch with item field") {
    val delTick = new ReactiveSignal[Int]("delTick", 0)
    val btn  = View.Button(
      label   = View.Text(() => "Delete", Style()),
      action  = EventHandler.DeleteItem("id", "/api/del", delTick),
      enabled = () => true,
      style   = Style()
    )
    val view = View.ForModel("items", "", "row", btn)
    val js   = emitJs(view)
    assert(js.contains("fetch('/api/del', {method: 'POST', body: String(row.id)})"), s"got: $js")
    assert(js.contains("setDelTick(t => t + 1)"), s"got: $js")
  }

  test("DeleteItem outside ForModel emits no-op comment") {
    val delTick = new ReactiveSignal[Int]("delTick2", 0)
    val btn = View.Button(
      label   = View.Text(() => "Del", Style()),
      action  = EventHandler.DeleteItem("id", "/api/del", delTick),
      enabled = () => true,
      style   = Style()
    )
    val js = emitJs(btn)
    assert(js.contains("DeleteItem outside ForModel"), s"got: $js")
  }

  // ── FetchTable semantic lowering ────────────────────────────────────────

  test("FetchTable lowers: ModelView guard emits tableId && h(Fragment, ...)") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("tasks &&"), s"got: $js")
  }

  test("FetchTable lowers: ForModel iterates tableId directly (empty fieldPath)") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("(tasks || []).map((row, _idx) =>"), s"got: $js")
  }

  test("FetchTable lowers: ModelText emits String(row.text)") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("String(row.text)"), s"got: $js")
  }

  test("FetchTable lowers: Delete button emits POST fetch with row.id") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("fetch('/api/delete', {method: 'POST', body: String(row.id)})"), s"got: $js")
  }

  test("FetchTable lowers: useState([]) + useEffect fetch for initial load") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("const [tasks, setTasks] = useState([]);"), s"got: $js")
    assert(js.contains("fetch('/api/items').then(r => r.json())"), s"got: $js")
  }

  test("FetchTable lowers: tick signal registered as useState") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("const [tick, setTick] = useState(0);"), s"got: $js")
  }
