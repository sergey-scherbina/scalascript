package scalascript.frontend.electron

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** v1.66.6 — Typed model data binding round-trips through the Electron backend.
 *  Electron delegates renderer output to the Custom (StaticJs) emitter, so
 *  FetchJsonSignal / ModelView / ForModel / ModelText are inherited from v1.66.5. */
class ElectronTypedModelsTest extends AnyFunSuite:

  private def backend = new ElectronFrameworkBackend

  test("FetchJsonSignal — __ssc_signals null init round-trips through Electron") {
    val tick   = new ReactiveSignal[Int]("tick", 0)
    val signal = new FetchJsonSignal("invoice", "https://api.example.com/invoice", tick.id, "Invoice")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "inv",
      View.TextNode(() => "body"), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("""__ssc_signals['invoice'] = { value: null"""), s"null init:\n$js")
    assert(js.contains("r.json()"), s"json decode:\n$js")
    assert(js.contains("__setSignal('invoice'"), s"signal set:\n$js")
  }

  test("ModelView — rebuild fn subscribed through Electron renderer") {
    val tick   = new ReactiveSignal[Int]("tick2", 0)
    val signal = new FetchJsonSignal("profile", "https://api.example.com/profile", tick.id, "Profile")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "p",
      View.ModelText("p", "name", Style()), Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("function __modelview_profile(p)"), s"rebuild fn:\n$js")
    assert(js.contains("__ssc_signals['profile'].subs.add"), s"subscriber:\n$js")
    assert(js.contains("createTextNode(String(p.name))"), s"ModelText:\n$js")
  }

  test("ForModel + ModelText — render fn + field access through Electron") {
    val tick   = new ReactiveSignal[Int]("tick3", 0)
    val signal = new FetchJsonSignal("report", "https://api.example.com/report", tick.id, "Report")
    val app = ComponentDef("App", Nil, _ => View.ModelView(signal, "r",
      View.ForModel("r", "lines", "line",
        View.ModelText("line", "amount", Style()), Style()),
      Style()))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("r.lines"), s"field path:\n$js")
    assert(js.contains("const line"), s"item binding:\n$js")
    assert(js.contains("createTextNode(String(line.amount))"), s"nested ModelText:\n$js")
  }

  test("Electron bundle builder injects _ssc_frontend_name into app.js") {
    import scalascript.ast.Module
    val module = Module(manifest = None, sections = Nil)
    val out = os.temp.dir(prefix = "ssc-electron-typedmodels-", deleteOnExit = true)
    ElectronBundleBuilder.write(module, "App", baseDir = None, out)
    val appJs = os.read(out / "app.js")
    assert(appJs.contains("_ssc_frontend_name = 'electron'"), s"Electron marker in bundle:\n${appJs.take(200)}")
  }
