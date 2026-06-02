package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** v1.66.5 — Typed model data binding on the Custom (StaticJs) backend:
 *  FetchJsonSignal + __ssc_signals + ModelView + ForModel + ModelText. */
class CustomTypedModelsTest extends AnyFunSuite:

  private def backend = new CustomFrameworkBackend

  // ── Signal registration ────────────────────────────────────────────────────

  test("FetchJsonSignal — __ssc_signals cell with null init + 3 companions") {
    val tick   = new ReactiveSignal[Int]("tick", 0)
    val signal = new FetchJsonSignal("account", "https://api.example.com/account", tick.id, "Account")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "acc",
      View.TextNode(() => "body"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("""__ssc_signals['account'] = { value: null, subs: new Set() }"""), s"null init:\n$js")
    assert(js.contains("""__ssc_signals['account_loading'] = { value: false, subs: new Set() }"""), s"loading:\n$js")
    assert(js.contains("""__ssc_signals['account_loaded'] = { value: false, subs: new Set() }"""), s"loaded:\n$js")
    assert(js.contains("""__ssc_signals['account_error'] = { value: '', subs: new Set() }"""), s"error:\n$js")
  }

  test("FetchUrlSignal (plain text) — __ssc_signals cell with empty string init") {
    val tick   = new ReactiveSignal[Int]("tick2", 0)
    val signal = new FetchUrlSignal("raw", "https://api.example.com/raw", tick.id)
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "data",
      View.TextNode(() => "content"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("""__ssc_signals['raw'] = { value: '', subs: new Set() }"""), s"empty string init:\n$js")
  }

  // ── Mount-fetch ────────────────────────────────────────────────────────────

  test("FetchJsonSignal — fetch + __setSignal + companion updates on resolve") {
    val tick   = new ReactiveSignal[Int]("tick3", 0)
    val signal = new FetchJsonSignal("balance", "https://api.example.com/bal", tick.id, "Balance")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "b",
      View.TextNode(() => "x"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("r.json()"), s"json decode:\n$js")
    assert(js.contains("__setSignal('account_loading'") || js.contains("__setSignal('balance_loading'"), s"loading set:\n$js")
    assert(js.contains("__setSignal('balance'"), s"signal set:\n$js")
    assert(js.contains("__setSignal('balance_loaded'"), s"loaded set:\n$js")
    assert(js.contains("__setSignal('balance_error'"), s"error set:\n$js")
  }

  test("FetchUrlSignal — fetch + r.text()") {
    val tick   = new ReactiveSignal[Int]("tick4", 0)
    val signal = new FetchUrlSignal("content", "https://api.example.com/txt", tick.id)
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "c",
      View.TextNode(() => "x"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("r.text()"), s"text decode:\n$js")
  }

  test("tick subscriber fires when t > 0") {
    val tick   = new ReactiveSignal[Int]("tick5", 0)
    val signal = new FetchJsonSignal("items", "https://api.example.com/items", tick.id, "Item")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "i",
      View.TextNode(() => "x"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("if (t > 0) fetch"), s"tick guard:\n$js")
  }

  // ── ModelView ──────────────────────────────────────────────────────────────

  test("ModelView — __modelview rebuild function + __ssc_signals subscriber") {
    val tick   = new ReactiveSignal[Int]("tick6", 0)
    val signal = new FetchJsonSignal("profile", "https://api.example.com/profile", tick.id, "Profile")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "p",
      View.TextNode(() => "inner"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("function __modelview_profile(p)"), s"rebuild fn:\n$js")
    assert(js.contains("__ssc_signals['profile'].subs.add"), s"subscriber:\n$js")
    assert(js.contains("while ("), s"clear before rebuild:\n$js")
  }

  test("ModelView — span with display:contents wrapper") {
    val tick   = new ReactiveSignal[Int]("tick7", 0)
    val signal = new FetchJsonSignal("inv", "https://api.example.com/inv", tick.id, "Invoice")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "inv",
      View.TextNode(() => "x"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("style.display = 'contents'"), s"contents wrapper:\n$js")
  }

  // ── ForModel ──────────────────────────────────────────────────────────────

  test("ForModel — per-item render function + for-loop over fieldPath") {
    val tick   = new ReactiveSignal[Int]("tick8", 0)
    val signal = new FetchJsonSignal("report", "https://api.example.com/rep", tick.id, "Report")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "r",
      View.ForModel("r", "lines", "line",
        View.TextNode(() => "x"), Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("r.lines"), s"field path:\n$js")
    assert(js.contains("const line"), s"item binding in for:\n$js")
    assert(js.contains("|| []"), s"null-safe fallback:\n$js")
  }

  test("ForModel — render fn parameter = itemVar") {
    val tick   = new ReactiveSignal[Int]("tick9", 0)
    val signal = new FetchJsonSignal("list", "https://api.example.com/list", tick.id, "List")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "m",
      View.ForModel("m", "items", "item",
        View.TextNode(() => "x"), Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("function __formodel_"), s"formodel fn:\n$js")
    assert(js.contains("(item)"), s"itemVar as param:\n$js")
  }

  // ── ModelText ─────────────────────────────────────────────────────────────

  test("ModelText — createTextNode(String(varName.fieldPath))") {
    val tick   = new ReactiveSignal[Int]("tick10", 0)
    val signal = new FetchJsonSignal("order", "https://api.example.com/order", tick.id, "Order")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "o",
      View.ModelText("o", "total", Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("createTextNode(String(o.total))"), s"ModelText field:\n$js")
  }

  test("ModelText — nested field path") {
    val tick   = new ReactiveSignal[Int]("tick11", 0)
    val signal = new FetchJsonSignal("bs2", "https://api.example.com/bs2", tick.id, "BalanceSheet")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "bs",
      View.ModelText("bs", "assets.total", Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("createTextNode(String(bs.assets.total))"), s"dotted path:\n$js")
  }

  // ── Combined ─────────────────────────────────────────────────────────────

  test("Combined ModelView + ForModel + ModelText") {
    val tick   = new ReactiveSignal[Int]("tick12", 0)
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
    assert(js.contains("__modelview_ledger(led)"), s"binding var in rebuild:\n$js")
    assert(js.contains("createTextNode(String(led.name))"), s"top-level ModelText:\n$js")
    assert(js.contains("led.entries"), s"ForModel field:\n$js")
    assert(js.contains("createTextNode(String(entry.amount))"), s"nested ModelText:\n$js")
  }
