package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** v1.66.4 — Typed model data binding on the Solid backend:
 *  FetchJsonSignal + mount-fetch + ModelView + ForModel + ModelText. */
class SolidTypedModelsTest extends AnyFunSuite:

  private def backend = new SolidFrameworkBackend

  // ── FetchUrlSignal (plain text) ─────────────────────────────────────────────

  test("FetchUrlSignal — createSignal('') + mount-fetch + tick-refresh") {
    val tick   = new ReactiveSignal[Int]("tick", 0)
    val signal = new FetchUrlSignal("raw", "https://api.example.com/text", tick.id)
    val app2 = ComponentDef("App", Nil, _ => View.ModelView(signal, "data",
      View.TextNode(() => "content"), Style()))
    val js = backend.emit(FrontendModule(List(app2), "App", "/")).js
    assert(js.contains("const [raw, setRaw] = createSignal('')"), s"signal init:\n$js")
    assert(js.contains("fetch('https://api.example.com/text')"), s"mount-fetch:\n$js")
    assert(js.contains("r.text()"), s"text() decode:\n$js")
    assert(js.contains("createEffect(() => { const __t = tick()"), s"tick createEffect:\n$js")
  }

  test("FetchJsonSignal — createSignal(null) + 3 companion signals + r.json()") {
    val tick   = new ReactiveSignal[Int]("tick2", 0)
    val signal = new FetchJsonSignal("balanceSheet", "https://api.example.com/bs", tick.id, "BalanceSheet")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "bs",
      View.TextNode(() => "body"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [balanceSheet, setBalanceSheet] = createSignal(null)"), s"data signal:\n$js")
    assert(js.contains("const [balanceSheet_loading, setBalanceSheet_loading] = createSignal(false)"), s"loading:\n$js")
    assert(js.contains("const [balanceSheet_loaded, setBalanceSheet_loaded] = createSignal(false)"), s"loaded:\n$js")
    assert(js.contains("const [balanceSheet_error, setBalanceSheet_error] = createSignal('')"), s"error:\n$js")
    assert(js.contains("r.json()"), s"json decode:\n$js")
    assert(js.contains("setBalanceSheet_loading(true)"), s"loading flag:\n$js")
    assert(js.contains("setBalanceSheet_loaded(true)"), s"loaded flag:\n$js")
    assert(js.contains("setBalanceSheet_error(String(e))"), s"error capture:\n$js")
  }

  test("FetchJsonSignal — tick createEffect only fires when t > 0") {
    val tick   = new ReactiveSignal[Int]("tick3", 0)
    val signal = new FetchJsonSignal("orders", "https://api.example.com/orders", tick.id, "Order")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "o",
      View.TextNode(() => "item"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("if (__t > 0) fetch"), s"guard in tick effect:\n$js")
  }

  // ── ModelView ───────────────────────────────────────────────────────────────

  test("ModelView — createEffect wraps template; bindingVar = signal()") {
    val tick   = new ReactiveSignal[Int]("tick4", 0)
    val signal = new FetchJsonSignal("profile", "https://api.example.com/profile", tick.id, "Profile")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "p",
      View.TextNode(() => "inner"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const p = profile()"), s"binding var assigned from signal:\n$js")
    assert(js.contains("if (!p) return"), s"guard for null signal:\n$js")
    assert(js.contains("createEffect(() => {"), s"reactive wrapper:\n$js")
    assert(js.contains("while ("), s"children cleared on re-render:\n$js")
  }

  test("ModelView — span with display:contents as wrapper") {
    val tick   = new ReactiveSignal[Int]("tick5", 0)
    val signal = new FetchJsonSignal("account", "https://api.example.com/account", tick.id, "Account")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "acc",
      View.TextNode(() => "data"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("display: 'contents'") || js.contains("display\\u003a 'contents'")
        || js.contains("style.display = 'contents'"), s"contents wrapper:\n$js")
  }

  // ── ForModel ────────────────────────────────────────────────────────────────

  test("ForModel — iterates fieldPath array; itemVar bound per iteration") {
    val tick   = new ReactiveSignal[Int]("tick6", 0)
    val signal = new FetchJsonSignal("report", "https://api.example.com/report", tick.id, "Report")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "r",
      View.ForModel("r", "lines", "line",
        View.TextNode(() => "entry"), Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("r.lines"), s"field path access:\n$js")
    assert(js.contains("const line = "), s"item binding:\n$js")
    assert(js.contains("for (let __i = 0"), s"for loop:\n$js")
  }

  test("ForModel — null-safe with || [] fallback") {
    val tick   = new ReactiveSignal[Int]("tick7", 0)
    val signal = new FetchJsonSignal("list", "https://api.example.com/list", tick.id, "ListModel")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "m",
      View.ForModel("m", "items", "item",
        View.TextNode(() => "x"), Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("|| []"), s"null-safe fallback:\n$js")
  }

  // ── ModelText ───────────────────────────────────────────────────────────────

  test("ModelText — createTextNode(String(varName.fieldPath))") {
    val tick   = new ReactiveSignal[Int]("tick8", 0)
    val signal = new FetchJsonSignal("invoice", "https://api.example.com/invoice", tick.id, "Invoice")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "inv",
      View.ModelText("inv", "total", Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("createTextNode(String(inv.total))"), s"ModelText field path:\n$js")
  }

  test("ModelText — nested field path with dot notation") {
    val tick   = new ReactiveSignal[Int]("tick9", 0)
    val signal = new FetchJsonSignal("bs2", "https://api.example.com/bs2", tick.id, "BalanceSheet")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "bs",
      View.ModelText("bs", "assets.total", Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("createTextNode(String(bs.assets.total))"), s"dotted field path:\n$js")
  }

  // ── Combined ────────────────────────────────────────────────────────────────

  test("Combined ModelView + ForModel + ModelText — all three nodes in one tree") {
    val tick   = new ReactiveSignal[Int]("tick10", 0)
    val signal = new FetchJsonSignal("ledger", "https://api.example.com/ledger", tick.id, "Ledger")
    val app = ComponentDef("App", Nil, _ =>
      View.ModelView(signal, "led",
        View.Column(List(
          View.ModelText("led", "name", Style()),
          View.ForModel("led", "entries", "entry",
            View.ModelText("entry", "amount", Style()), Style())
        ), 0, HAlign.Start, Style()),
        Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const led = ledger()"), s"binding var:\n$js")
    assert(js.contains("createTextNode(String(led.name))"), s"ModelText top-level:\n$js")
    assert(js.contains("led.entries"), s"ForModel field:\n$js")
    assert(js.contains("createTextNode(String(entry.amount))"), s"ModelText inside ForModel:\n$js")
  }

  test("mount-fetch appears before DOM construction in App body") {
    val tick   = new ReactiveSignal[Int]("tick11", 0)
    val signal = new FetchJsonSignal("data2", "https://api.example.com/data", tick.id, "Data")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "d",
      View.TextNode(() => "body"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    val fetchIdx = js.indexOf("fetch('https://api.example.com/data')")
    val domIdx   = js.indexOf("document.createElement")
    assert(fetchIdx < domIdx, s"fetch before DOM: fetchIdx=$fetchIdx domIdx=$domIdx")
  }
