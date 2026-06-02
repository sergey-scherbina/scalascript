package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class VueTypedModelsTest extends AnyFunSuite:

  private def emitJs(root: View[?]): String =
    val app = ComponentDef("App", Nil, _ => root)
    new VueFrameworkBackend().emit(FrontendModule(List(app), "App", "/")).js

  private val tick = new ReactiveSignal[Int]("tick", 0)

  // ── Mount-fetch parity fix: plain FetchUrlSignal now fetches on mount ────

  test("plain FetchUrlSignal: onMounted fetch emitted (mount-fetch parity fix)") {
    val fs   = new FetchUrlSignal("raw", "/api/raw", tick.id)
    val view = View.SignalText(fs, Style())
    val js   = emitJs(view)
    assert(js.contains("onMounted("))
    assert(js.contains("fetch('/api/raw').then(r => r.text())"))
  }

  test("plain FetchUrlSignal: watch on tick emitted") {
    val fs   = new FetchUrlSignal("raw", "/api/raw", tick.id)
    val view = View.SignalText(fs, Style())
    val js   = emitJs(view)
    assert(js.contains("watch(tick,"))
  }

  test("plain FetchUrlSignal: onMounted import added") {
    val fs   = new FetchUrlSignal("raw", "/api/raw", tick.id)
    val view = View.SignalText(fs, Style())
    val js   = emitJs(view)
    assert(js.contains("onMounted"))
    assert(js.contains("import { ref, h, Fragment"))
  }

  // ── FetchJsonSignal: typed state + r.json() ─────────────────────────────

  test("FetchJsonSignal: ref(null) for model + 3 companion refs") {
    val sig  = new FetchJsonSignal("sheet", "/api/sheet", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "sheet", View.TextNode(() => "ok"))
    val js   = emitJs(view)
    assert(js.contains("const sheet = ref(null);"))
    assert(js.contains("const sheet_loading = ref(false);"))
    assert(js.contains("const sheet_loaded = ref(false);"))
    assert(js.contains("const sheet_error = ref(\"\");"))
  }

  test("FetchJsonSignal: onMounted fetch uses r.json()") {
    val sig  = new FetchJsonSignal("sheet", "/api/sheet", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "sheet", View.TextNode(() => "ok"))
    val js   = emitJs(view)
    assert(js.contains("fetch('/api/sheet').then(r => r.json())"))
  }

  test("FetchJsonSignal: watch on tick uses r.json()") {
    val sig  = new FetchJsonSignal("bs", "/api/bs", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "bs", View.TextNode(() => "x"))
    val js   = emitJs(view)
    assert(js.contains("watch(tick,"))
    assert(js.contains("r.json()"))
  }

  // ── ModelView renderView ────────────────────────────────────────────────

  test("ModelView: renders as this.signal && h(Fragment, ...)") {
    val sig   = new FetchJsonSignal("bs", "/api/bs", tick.id, "BalanceSheet")
    val inner = View.TextNode(() => "content")
    val view  = View.ModelView(sig, "bs", inner)
    val js    = emitJs(view)
    assert(js.contains("this.bs && h(Fragment, null,"))
  }

  // ── ForModel renderView ─────────────────────────────────────────────────

  test("ForModel: renders as (this.bindingVar.fieldPath || []).map(...)") {
    val inner = View.TextNode(() => "row")
    val view  = View.ForModel("bs", "lines", "line", inner)
    val js    = emitJs(view)
    assert(js.contains("(this.bs.lines || []).map((line, _idx) =>"))
  }

  // ── ModelText renderView ────────────────────────────────────────────────

  test("ModelText: renders as String(this.varName.fieldPath)") {
    val view = View.ModelText("bs", "total")
    val js   = emitJs(view)
    assert(js.contains("String(this.bs.total)"))
  }

  // ── setup() return includes fetch signal names ─────────────────────────

  test("FetchJsonSignal: all companion names returned from setup()") {
    val sig  = new FetchJsonSignal("bs", "/api/bs", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "bs", View.TextNode(() => "ok"))
    val js   = emitJs(view)
    assert(js.contains("bs") && js.contains("bs_loading") && js.contains("bs_loaded") && js.contains("bs_error"))
  }
