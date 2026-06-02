package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class FetchTableSemanticEmitTest extends AnyFunSuite:

  private def emitJs(root: View[?]): String =
    val app = ComponentDef("App", Nil, _ => root)
    new VueFrameworkBackend().emit(FrontendModule(List(app), "App", "/")).js

  private val tick = new ReactiveSignal[Int]("tick", 0)

  @annotation.nowarn("cat=deprecation")
  private def makeFetchTable(tableId: String): View.FetchTable =
    View.FetchTable(tableId, "/api/items", "/api/delete", tick, None)

  // ── ForModel: empty fieldPath fix ──────────────────────────────────────

  test("ForModel with empty fieldPath uses this.bindingVar directly as list") {
    val inner = View.TextNode(() => "x")
    val view  = View.ForModel("items", "", "row", inner)
    val js    = emitJs(view)
    assert(js.contains("(this.items || []).map((row, _idx) =>"), s"got: $js")
    assert(!js.contains("this.items."), s"must not emit this.items. with empty fieldPath, got: $js")
  }

  test("ForModel with non-empty fieldPath still uses dot notation") {
    val inner = View.TextNode(() => "x")
    val view  = View.ForModel("bs", "lines", "line", inner)
    val js    = emitJs(view)
    assert(js.contains("(this.bs.lines || []).map((line, _idx) =>"), s"got: $js")
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
    assert(js.contains("this.delTick++"), s"got: $js")
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

  test("FetchTable lowers: ModelView guard emits this.tasks && h(Fragment, ...)") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("this.tasks &&"), s"got: $js")
  }

  test("FetchTable lowers: ForModel iterates this.tasks directly (empty fieldPath)") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("(this.tasks || []).map((row, _idx) =>"), s"got: $js")
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

  test("FetchTable lowers: tasks ref([]) + initial fetch in setup()") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("const tasks = ref([]);"), s"got: $js")
    assert(js.contains("fetch('/api/items').then(r => r.json())"), s"got: $js")
  }

  test("FetchTable lowers: tick signal registered as ref(0) in setup()") {
    val ft = makeFetchTable("tasks")
    val js = emitJs(ft)
    assert(js.contains("const tick = ref(0);"), s"got: $js")
  }
