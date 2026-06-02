package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class ReactTypedModelsTest extends AnyFunSuite:

  private def emitJs(root: View[?]): String =
    val app = ComponentDef("App", Nil, _ => root)
    new ReactFrameworkBackend().emit(FrontendModule(List(app), "App", "/")).js

  private val tick = new ReactiveSignal[Int]("tick", 0)

  // ── FetchJsonSignal: typed state useState ───────────────────────────────

  test("FetchJsonSignal: useState(null) for model + 3 companion vars") {
    val sig  = new FetchJsonSignal("sheet", "/api/sheet", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "sheet", View.TextNode(() => "ok"))
    val js   = emitJs(view)
    assert(js.contains("const [sheet, setSheet] = useState(null);"))
    assert(js.contains("const [sheet_loading, setSheet_loading] = useState(false);"))
    assert(js.contains("const [sheet_loaded, setSheet_loaded] = useState(false);"))
    assert(js.contains("const [sheet_error, setSheet_error] = useState(\"\");"))
  }

  // ── FetchJsonSignal: r.json() in useEffect ──────────────────────────────

  test("FetchJsonSignal: mount-fetch uses r.json()") {
    val sig  = new FetchJsonSignal("sheet", "/api/sheet", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "sheet", View.TextNode(() => "ok"))
    val js   = emitJs(view)
    assert(js.contains("fetch('/api/sheet').then(r => r.json()).then(t => setSheet(t));"))
  }

  test("FetchJsonSignal: tick-refresh also uses r.json()") {
    val sig  = new FetchJsonSignal("bs", "/api/bs", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "bs", View.TextNode(() => "x"))
    val js   = emitJs(view)
    assert(js.contains("if (tick > 0) fetch('/api/bs').then(r => r.json()).then(t => setBs(t));"))
  }

  // ── Plain FetchUrlSignal still uses r.text() ────────────────────────────

  test("plain FetchUrlSignal still uses r.text()") {
    val fs   = new FetchUrlSignal("raw", "/api/raw", tick.id)
    val view = View.SignalText(fs, Style())
    val js   = emitJs(view)
    assert(js.contains(".then(r => r.text())"))
    assert(!js.contains(".then(r => r.json())"))
  }

  // ── ModelView renderView ────────────────────────────────────────────────

  test("ModelView: renders as signal && h(Fragment, null, ...)") {
    val sig   = new FetchJsonSignal("bs", "/api/bs", tick.id, "BalanceSheet")
    val inner = View.TextNode(() => "inner")
    val view  = View.ModelView(sig, "bs", inner)
    val js    = emitJs(view)
    assert(js.contains("bs && h(Fragment, null,"))
    assert(js.contains("'inner'"))
  }

  // ── ForModel renderView ─────────────────────────────────────────────────

  test("ForModel: renders as fieldPath.map(...)") {
    val inner = View.TextNode(() => "item")
    val view  = View.ForModel("bs", "lines", "line", inner)
    val js    = emitJs(view)
    assert(js.contains("(bs.lines || []).map((line, _idx) =>"))
  }

  // ── ModelText renderView ────────────────────────────────────────────────

  test("ModelText: renders as String(varName.fieldPath)") {
    val view = View.ModelText("bs", "total")
    val js   = emitJs(view)
    assert(js.contains("String(bs.total)"))
  }
