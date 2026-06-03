package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class DataTableEmitTest extends AnyFunSuite:

  private def emitJs(root: View[?]): String =
    val app = ComponentDef("App", Nil, _ => root)
    new ReactFrameworkBackend().emit(FrontendModule(List(app), "App", "/")).js

  private val tick = new ReactiveSignal[Int]("tick", 0)

  private def makeDataTable(): View.DataTable =
    View.DataTable(
      source  = TableDataSource.Remote(new FetchUrlSignal("empRows", "/api/employees", tick.id)),
      columns = List(FieldColumnDef("Name", "name"), FieldColumnDef("Dept", "department")),
      actions = List(RowActionDef.RowDelete("/api/emp/delete", "id", tick))
    )

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

  // ── ItemAction handler ──────────────────────────────────────────────────

  test("ItemAction inside ForModel emits method fetch with item field and bumps tick") {
    val actTick = new ReactiveSignal[Int]("actTick", 0)
    val btn = View.Button(
      label   = View.Text(() => "Promote", Style()),
      action  = EventHandler.ItemAction("POST", "/api/emp/promote", RowPayload.Field("id"), actTick),
      enabled = () => true,
      style   = Style()
    )
    val view = View.ForModel("items", "", "row", btn)
    val js   = emitJs(view)
    assert(js.contains("fetch('/api/emp/promote', {method: 'POST', body: String(row.id)})"), s"got: $js")
    assert(js.contains("setActTick(t => t + 1)"), s"got: $js")
  }

  // ── SetFieldToSignal handler ────────────────────────────────────────────

  test("SetFieldToSignal inside ForModel emits setter with item field") {
    val selected = new ReactiveSignal[String]("selected", "")
    val btn = View.Button(
      label   = View.Text(() => "Select", Style()),
      action  = EventHandler.SetFieldToSignal(selected, "id"),
      enabled = () => true,
      style   = Style()
    )
    val view = View.ForModel("items", "", "row", btn)
    val js   = emitJs(view)
    assert(js.contains("setSelected(String(row.id))"), s"got: $js")
  }

  // ── DataTable semantic lowering ─────────────────────────────────────────

  test("DataTable lowers: ModelView guard emits empRows &&") {
    val dt = makeDataTable()
    val js = emitJs(dt)
    assert(js.contains("empRows &&"), s"got: $js")
  }

  test("DataTable lowers: ForModel iterates empRows directly (empty fieldPath)") {
    val dt = makeDataTable()
    val js = emitJs(dt)
    assert(js.contains("(empRows || []).map((row, _idx) =>"), s"got: $js")
  }

  test("DataTable lowers: ModelText emits String(row.name) and String(row.department)") {
    val dt = makeDataTable()
    val js = emitJs(dt)
    assert(js.contains("String(row.name)"), s"got: $js")
    assert(js.contains("String(row.department)"), s"got: $js")
  }

  test("DataTable lowers: Delete button emits POST fetch with row.id") {
    val dt = makeDataTable()
    val js = emitJs(dt)
    assert(js.contains("fetch('/api/emp/delete', {method: 'POST', body: String(row.id)})"), s"got: $js")
  }

  test("DataTable lowers: useState([]) + useEffect fetch for initial load") {
    val dt = makeDataTable()
    val js = emitJs(dt)
    assert(js.contains("const [empRows, setEmpRows] = useState([]);"), s"got: $js")
    assert(js.contains("fetch('/api/employees').then(r => r.json())"), s"got: $js")
  }

  test("DataTable lowers: tick signal registered as useState") {
    val dt = makeDataTable()
    val js = emitJs(dt)
    assert(js.contains("const [tick, setTick] = useState(0);"), s"got: $js")
  }
