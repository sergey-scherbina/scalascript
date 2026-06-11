package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JsRuntimeBrowserPatch}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

class JsGenStdImportTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def writeTempJs(prefix: String, js: String): java.io.File =
    val tmp = java.io.File.createTempFile(prefix, ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    tmp

  private def checkNodeSyntax(js: String): Unit =
    assume(hasNode, "node not available")
    val tmp = writeTempJs("ssc-jsgen-std-import-check-", js)
    val proc = ProcessBuilder("node", "--check", tmp.getAbsolutePath).start()
    val err = Source.fromInputStream(proc.getErrorStream).mkString
    val ok = ProcTestUtil.awaitExit(proc)
    assert(ok == 0, s"node --check failed ($ok):\n$err")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = writeTempJs("ssc-jsgen-std-import-run-", js)
    val proc = ProcessBuilder("node", tmp.getAbsolutePath).start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    val err = Source.fromInputStream(proc.getErrorStream).mkString
    val ok = ProcTestUtil.awaitExit(proc)
    assert(ok == 0, s"node run failed ($ok):\n$err")
    out.trim

  test("JsGen resolves bare std imports from project runtime tree"):
    val source =
      """# App
        |
        |[signal, serve](std/ui/primitives.ssc)
        |
        |```scalascript
        |val count = signal("count", 0)
        |serve(count, 0)
        |```
        |""".stripMargin

    val js = JsGen.generate(
      Parser.parse(source),
      baseDir = Some(TestPaths.repoRoot / "examples" / "desktop-demo")
    )

    assert(js.contains("const std ="), "expected std namespace object from imported module")
    assert(js.contains("const signal = std.ui.primitives.signal"))
    assert(js.contains("const serve = std.ui.primitives.serve"))

  test("browser patch overrides Node helpers without duplicate ES module declarations"):
    assert(!JsRuntimeBrowserPatch.contains("function _ssc_http_serve()"))
    assert(!JsRuntimeBrowserPatch.contains("function _ssc_ui_serve("))
    assert(JsRuntimeBrowserPatch.contains("_ssc_http_serve = function()"))
    assert(JsRuntimeBrowserPatch.contains("_ssc_ui_serve = function("))

  test("browser patch routes same-app fetch calls through registered routes"):
    assert(JsRuntimeBrowserPatch.contains("globalThis.fetch = function(input, init)"))
    assert(JsRuntimeBrowserPatch.contains("_spaRouteResponse(method, pathOnly"))
    assert(JsRuntimeBrowserPatch.contains("Response.notFound('Not Found: ' + pathOnly)"))

  test("browser patch can forward relative fetch calls to injected JVM backend"):
    assert(JsRuntimeBrowserPatch.contains("globalThis.__sscBackendBaseUrl"))
    assert(JsRuntimeBrowserPatch.contains("new URL(rawPath, String(globalThis.__sscBackendBaseUrl)).toString()"))
    assert(JsRuntimeBrowserPatch.contains("return _ssc_native_fetch(target, mergedInit)"))

  test("browser runtime HTTP route helper does not clash with std/ui routing route import"):
    val source =
      """# App
        |
        |[route, hashRouter](std/ui/routing.ssc)
        |
        |```scalascript
        |val home = route("/", [])
        |```
        |""".stripMargin

    val user = JsGen.generate(
      Parser.parse(source),
      baseDir = Some(TestPaths.repoRoot / "examples")
    )
    val bundle = JsGen.generateRuntime(Set(JsGen.Capability.HtmlDsl, JsGen.Capability.Signals)) + "\n" + user

    assert(bundle.contains("function _ssc_http_route("))
    assert(bundle.contains("globalThis.route = _ssc_http_route"))
    assert(!bundle.contains("function route(method, path)"),
      "HTTP route helper must not reserve a lexical route binding in browser bundles")
    assert(bundle.contains("const route = std.ui.routing.route"),
      "regression source should still import the UI route constructor as a local const")
    checkNodeSyntax(bundle)

  test("browser signal runtime seeds hash routes and updates derived show guards"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    val js =
      s"""
         |globalThis.window = {
         |  location: { hash: '#/money' },
         |  _listeners: {},
         |  addEventListener: function(name, fn) { this._listeners[name] = fn; }
         |};
         |globalThis.location = window.location;
         |$runtime
         |function assertRuntime(cond, msg) { if (!cond) throw new Error(msg); }
         |
         |const hash = _ssc_ui_hashSignal();
         |assertRuntime(hash.get() === '/money', 'hashSignal should seed from window.location.hash');
         |const isMoney = _ssc_ui_eqSignal(hash, '/money');
         |const isHome = _ssc_ui_eqSignal(hash, '/home');
         |assertRuntime(isMoney.get() === true, 'initial /money route should match');
         |window.location.hash = '#/home';
         |window._listeners.hashchange();
         |assertRuntime(hash.get() === '/home', 'hashchange should update hash signal');
         |assertRuntime(isMoney.get() === false, 'eqSignal should update to false after hashchange');
         |assertRuntime(isHome.get() === true, 'eqSignal should update to true after hashchange');
         |
         |const tab = Signal('bank');
         |const fx = _ssc_ui_computedSignal(() => tab.get() === 'fx');
         |assertRuntime(fx.get() === false, 'boolean computedSignal should preserve false boolean');
         |tab.set('fx');
         |assertRuntime(fx.get() === true, 'boolean computedSignal should update to true boolean');
         |assertRuntime(_ssc_ui_truthy('false') === false, 'legacy string false should not be visible');
         |
         |hash.set('/home');
         |const trueBranch = { style: {} };
         |const falseBranch = { style: {} };
         |const condEl = {
         |  getAttribute: function(name) { return name === 'data-ssc-cond' ? String(isMoney.id) : null; },
         |  querySelector: function(sel) { return sel.indexOf('true') >= 0 ? trueBranch : falseBranch; }
         |};
         |const setEl = {
         |  _listeners: {},
         |  getAttribute: function(name) {
         |    if (name === 'data-ssc-set') return String(hash.id);
         |    if (name === 'data-ssc-set-val') return JSON.stringify('/money');
         |    return null;
         |  },
         |  addEventListener: function(name, fn) { this._listeners[name] = fn; }
         |};
         |globalThis.document = {
         |  querySelectorAll: function(sel) {
         |    if (sel === '[data-ssc-cond]') return [condEl];
         |    if (sel === '[data-ssc-set]') return [setEl];
         |    return [];
         |  }
         |};
         |_ssc_ui_mount(new Map([[hash.id, hash.get()], [isMoney.id, isMoney.get()]]));
         |assertRuntime(trueBranch.style.display === 'none', 'false eqSignal branch should be hidden on mount');
         |assertRuntime(falseBranch.style.display === 'contents', 'fallback branch should be visible on mount');
         |setEl._listeners.click();
         |assertRuntime(hash.get() === '/money', 'setSignal bridge should update source signal');
         |assertRuntime(isMoney.get() === true, 'setSignal bridge should update computed eqSignal');
         |assertRuntime(trueBranch.style.display === 'contents', 'true branch should become visible after setSignal');
         |assertRuntime(falseBranch.style.display === 'none', 'fallback branch should hide after setSignal');
         |console.log('ok');
         |""".stripMargin

    assert(runNode(js) == "ok")

  // busi seq-94: hand-rolled routing `showSignal(eqSignal(hashSignal(), "#/a"), …)`
  // kept the matched branch hidden because hashSignal() strips the '#' ("/a") while
  // the user compares the URL form ("#/a").  eqSignal is now hash-tolerant, so the
  // matched branch is shown on mount.
  test("eqSignal matches the hash regardless of a leading '#'; showSignal reveals the matched branch"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    val js =
      s"""
         |globalThis.window = { location: { hash: '#/a' }, addEventListener: function(){} };
         |globalThis.location = window.location;
         |$runtime
         |function assertRuntime(cond, msg) { if (!cond) throw new Error(msg); }
         |const route = _ssc_ui_hashSignal();
         |assertRuntime(route.get() === '/a', 'hashSignal strips the leading #');
         |const condA = _ssc_ui_eqSignal(route, '#/a');   // URL form, with '#'
         |const condB = _ssc_ui_eqSignal(route, '#/b');
         |assertRuntime(condA.get() === true,  'eqSignal must match "#/a" against the stripped "/a" hash');
         |assertRuntime(condB.get() === false, 'non-matching route must stay false');
         |// plain (non-'#') comparisons are unaffected
         |assertRuntime(_ssc_ui_eqSignal(Signal('bank'), 'bank').get() === true, 'plain key equality unchanged');
         |assertRuntime(_ssc_ui_eqSignal(Signal('bank'), 'fx').get() === false, 'plain key inequality unchanged');
         |// showSignal toggle: the matched branch (A) must be visible on mount
         |const trueBranch = { style: {} };
         |const falseBranch = { style: {} };
         |const condEl = {
         |  getAttribute: function(name) { return name === 'data-ssc-cond' ? String(condA.id) : null; },
         |  querySelector: function(sel) { return sel.indexOf('true') >= 0 ? trueBranch : falseBranch; }
         |};
         |globalThis.document = { querySelectorAll: function(sel) { return sel === '[data-ssc-cond]' ? [condEl] : []; } };
         |_ssc_ui_mount(new Map([[route.id, route.get()], [condA.id, condA.get()]]));
         |assertRuntime(trueBranch.style.display === 'contents', 'matched "#/a" branch must be visible');
         |assertRuntime(falseBranch.style.display === 'none', 'fallback branch must be hidden');
         |console.log('ok');
         |""".stripMargin
    assert(runNode(js) == "ok")

  test("async browser module failures render a visible error"):
    val source =
      """# App
        |
        |```sql
        |SELECT 1
        |```
        |""".stripMargin

    val js = JsGen.generate(Parser.parse(source))
    assert(js.contains("document.body.textContent = msg"))

  test("JS runtime upload directory default is guarded for browser renderers"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Core))
    assert(runtime.contains("typeof require === 'function'"))
    assert(!runtime.contains("let _uploadDir = require('os').tmpdir();"))

  test("JS signal runtime defines std/ui typed DataTable column helpers"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    List(
      "_ssc_ui_seedSignal",
      "_ssc_ui_dateColumn",
      "_ssc_ui_moneyColumn",
      "_ssc_ui_statusColumn",
      "_ssc_ui_linkColumn"
    ).foreach { helper =>
      assert(runtime.contains(s"function $helper("), s"missing browser UI helper: $helper")
    }
    assert(runtime.contains("kind: c.kind || { type: 'text' }"),
      "DataTable column serialization must preserve kind metadata")
    assert(runtime.contains("_seedPristine"), "seedSignal must track pristine state")
    assert(runtime.contains("preserveSeedPristine"), "source sync must not dirty the seed")
    assert(runtime.contains("_mountFetchGet"), "hidden seed sources must still mount fetchUrlSignal")

  test("typed DataTable helpers trigger the JS Signals runtime capability"):
    val source =
      """# App
        |
        |[staticDataTable, dcol, mcol, scol](std/ui/data.ssc)
        |
        |```scalascript
        |val table = staticDataTable(
        |  [],
        |  [dcol("Created", "createdAt"), mcol("Salary", "salary"), scol("Status", "status")]
        |)
        |```
        |""".stripMargin

    val caps = JsGen.detectCapabilities(Parser.parse(source))
    assert(caps.contains(JsGen.Capability.Signals), s"expected Signals capability, got $caps")

  test("seedSignal triggers the JS Signals runtime capability"):
    val source =
      """# App
        |
        |[seedSignal](std/ui/primitives.ssc)
        |
        |```scalascript
        |val source = signal[String]("source", "Ada")
        |val draft = seedSignal("draft", source)
        |```
        |""".stripMargin

    val caps = JsGen.detectCapabilities(Parser.parse(source))
    assert(caps.contains(JsGen.Capability.Signals), s"expected Signals capability, got $caps")

  test("seedSignal example parses and emits the browser helper shim"):
    val source = os.read(TestPaths.repoRoot / "examples" / "seed-signal.ssc")
    val js = JsGen.generate(Parser.parse(source), baseDir = Some(TestPaths.repoRoot / "examples"))

    assert(js.contains("_ssc_ui_seedSignal"), "std/ui seedSignal extern must bind to the browser helper")
    assert(js.contains("""_call(seedSignal, "draftName", sourceName)"""),
      "example should keep the explicit seedSignal draft construction")

  // Regression: importing the std/json `jsonValue` extern used to emit a bare
  // top-level `function jsonValue(...)` in the runtime AND a top-level
  // `const jsonValue = std.json.jsonValue` import binding — a duplicate
  // declaration that broke every emit-spa screen with
  // "Identifier 'jsonValue' has already been declared".  The runtime impl is
  // now named `_ssc_ui_jsonValue` (the extern convention), so the binding
  // resolves and there is no top-level name clash.
  test("std/json jsonValue extern binds without a duplicate top-level declaration"):
    val source =
      """# App
        |
        |[serve, element, textNode](std/ui/primitives.ssc)
        |[jsonValue, jsonStringify](std/json.ssc)
        |
        |```scalascript
        |val v = element("div", Map(), Map(), [
        |  textNode(jsonValue("{\"a\":1}").get("a").asInt.toString),
        |  textNode(jsonStringify("x"))
        |])
        |serve(v)
        |```
        |""".stripMargin

    // Build the full bundle the way emit-spa does: runtime preamble + module JS.
    // The bare-vs-binding clash only manifests when the two are concatenated.
    val module  = Parser.parse(source)
    val baseDir = TestPaths.repoRoot / "examples"
    val caps    = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val js = runtime + "\n" + moduleJs

    // runtime impls follow the _ssc_ui_ extern convention
    assert(runtime.contains("function _ssc_ui_jsonValue("), "jsonValue runtime impl must be _ssc_ui_jsonValue")
    assert(runtime.contains("function _ssc_ui_jsonStringify("), "jsonStringify runtime impl must be _ssc_ui_jsonStringify")
    // the old bare top-level names must be gone (would clash with the binding)
    assert(!runtime.contains("\nfunction jsonValue("), "bare top-level function jsonValue must not be emitted")
    assert(!runtime.contains("\nfunction jsonStringify("), "bare top-level function jsonStringify must not be emitted")
    // the import binding is present and resolves to the defined impl
    assert(moduleJs.contains("const jsonValue = std.json.jsonValue;"), "expected jsonValue import binding")
    // and the full bundle actually parses as JS (the bug was a parse error)
    checkNodeSyntax(js)

  // fetchCaptureAction / fetchJsonCaptureAction (busi durable-auth login): a POST
  // whose 2xx response body is captured into a signal (instead of discarded).
  test("fetchCaptureAction wires response capture into a signal in the emit-spa bundle"):
    val source =
      """# Login
        |
        |[serve, element, signal, signalText, computedSignal, emptyHeaders](std/ui/primitives.ssc)
        |[fetchJsonCaptureAction, jsonOf](std/ui/fetch-json.ssc)
        |[jStr, jField, jObj](std/json.ssc)
        |
        |```scalascript
        |val user = signal("user", "ada")
        |val pass = signal("pass", "pw")
        |val resp = signal("loginResp", "")
        |val tick = signal("tick", 0)
        |val onLogin = fetchJsonCaptureAction("POST", "/login",
        |  () => jObj([jField("user", jStr(user())), jField("pass", jStr(pass()))]),
        |  resp, tick, emptyHeaders)
        |val token = computedSignal(() => jsonOf(resp)().get("token").asString)
        |val v = element("div", Map(), ["click" -> onLogin], [signalText(token)])
        |serve(v)
        |```
        |""".stripMargin

    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val js       = runtime + "\n" + moduleJs

    // the Signals runtime is pulled in and the capture builder + handler exist
    assert(caps.contains(JsGen.Capability.Signals), s"expected Signals capability, got $caps")
    assert(runtime.contains("function _ssc_ui_fetchCaptureAction("), "capture builder missing from runtime")
    // render emits a data-ssc-fetch-into attribute carrying the target signal id
    assert(runtime.contains("data-ssc-fetch-into"), "render must emit data-ssc-fetch-into")
    // the click handler reads it and captures the body on a 2xx into that signal
    assert(runtime.contains("var intoId    = el.getAttribute('data-ssc-fetch-into');"), "handler must read data-ssc-fetch-into")
    assert(runtime.contains("_set(intoId, text == null ? '' : String(text));"), "handler must capture body into signal")
    // the .ssc sugar lowers onto the capture extern shim
    assert(moduleJs.contains("_ssc_ui_fetchCaptureAction") || moduleJs.contains("fetchCaptureAction"),
      "fetchJsonCaptureAction must lower onto the capture extern")
    // whole bundle parses as JS
    checkNodeSyntax(js)

  // ── js-backend-ui-render-gaps (busi seq-79) ────────────────────────────────
  // The five std/ui row-data natives used to be undefined in the JS Signals
  // runtime — calling them threw "not callable" and blanked the whole SPA.
  test("JS signal runtime defines the std/ui row-data natives"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    List(
      "_ssc_ui_staticRowsSource",
      "_ssc_ui_signalRowsSource",
      "_ssc_ui_fieldPayload",
      "_ssc_ui_wholeRowPayload",
      "_ssc_ui_fieldsPayload"
    ).foreach { helper =>
      assert(runtime.contains(s"function $helper("), s"missing browser UI helper: $helper")
    }
    // RowPayload resolution lives in the _RowPost mount handler
    assert(runtime.contains("function resolvePayload("), "mount must resolve RowPayload markers")
    assert(runtime.contains("body: resolvePayload(r, act.bodyField)"), "_RowPost body must use resolvePayload")

  test("renderBody emits inline rows for a static DataTable source"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    val script =
      runtime + "\n" +
      """
        |const src  = _ssc_ui_staticRowsSource([{ name: 'Ada', salary: '85000' }]);
        |const cols = [{ title: 'Name', fieldPath: 'name', align: '', kind: { type: 'text' } }];
        |const acts = [_ssc_ui_rowPostAction('Promote','POST','/api/promote', _ssc_ui_fieldsPayload(['name','salary']), { id: 't' })];
        |const view = _ssc_ui_dataTableView(src, cols, acts);
        |const out  = _ssc_ui_renderBody(view).body;
        |if (out.indexOf('data-ssc-datatable-rows=') < 0) throw new Error('static rows attr missing: ' + out);
        |if (out.indexOf('Ada') < 0) throw new Error('static row payload missing: ' + out);
        |console.log('static-rows-ok');
        |""".stripMargin
    assert(runNode(script) == "static-rows-ok")

  test("emit-spa datatable-static-spa example binds the row-data natives and parses"):
    val source  = os.read(TestPaths.repoRoot / "examples" / "datatable-static-spa.ssc")
    val module  = Parser.parse(source)
    val baseDir = TestPaths.repoRoot / "examples"
    val caps    = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val js      = runtime + "\n" + moduleJs
    assert(caps.contains(JsGen.Capability.Signals), s"expected Signals capability, got $caps")
    // the row-data natives must bind to defined shims, never `= undefined`
    List("staticRowsSource", "fieldsPayload").foreach { n =>
      assert(!moduleJs.contains(s"const $n = (typeof _ssc_ui_$n !== 'undefined') ? _ssc_ui_$n : undefined;") ||
             runtime.contains(s"function _ssc_ui_$n("),
        s"$n must resolve to a defined _ssc_ui_$n shim")
    }
    // whole bundle parses as JS — proves no `Match failure` syntax / no undefined call sites
    checkNodeSyntax(js)

  // Follow-up to Layer 2: a raw (un-lowered) DataTableNode placed directly in an
  // element()/container's children — a TkNode that reached the renderer because a
  // caller mixed it into an already-lowered View — used to vanish silently on JS
  // (walk had no 'DataTableNode' case → default '').  lower(DataTableNode) is
  // theme-free, so walk now normalises it into a _DataTableView and renders it.
  test("renderBody renders a raw un-lowered DataTableNode child"):
    val source =
      """# App
        |
        |[serve, element, signal, View](std/ui/primitives.ssc)
        |[lower](std/ui/lower.ssc)
        |[defaultTheme](std/ui/theme.ssc)
        |[heading](std/ui/typography.ssc)
        |[dataTable, staticRowsSource, fcol](std/ui/data.ssc)
        |
        |```scalascript
        |val rows = [["name" -> "Ada"]]
        |// dataTable() returns a raw DataTableNode (TkNode), mixed in un-lowered:
        |val tbl  = dataTable(staticRowsSource(rows), [fcol("Name", "name")], [])
        |def content(): View =
        |  element("div", Map(), Map(), [ lower(heading(1, "H"), defaultTheme), tbl ])
        |serve(lower(content(), defaultTheme))
        |```
        |""".stripMargin
    val module  = Parser.parse(source)
    val baseDir = TestPaths.repoRoot / "examples"
    val runtime = JsGen.generateRuntime(JsGen.detectCapabilities(module, Some(baseDir)))
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst out = _ssc_ui_renderBody(globalThis.__captured).body;\n" +
      "if (out.indexOf('data-ssc-datatable') < 0) throw new Error('raw DataTableNode child vanished: ' + out);\n" +
      "if (out.indexOf('data-ssc-datatable-rows=') < 0) throw new Error('normalised static rows missing: ' + out);\n" +
      "console.log('raw-datatable-child-ok');\n"
    assert(runNode(script) == "raw-datatable-child-ok")

  // busi seq-87: a DataTable fetch endpoint may answer a bare array, an envelope
  // {data:[...]} / {count,rows:[...]}, or — on a misrouted path — the SPA HTML.
  // _ssc_ui_rowsOf normalises all of them to an array so renderTable never does
  // `(rows||[]).forEach` on a non-array (the "empty tables" + `<script>` JSON.parse
  // errors busi reported once the SPA finally mounted).
  test("rowsOf normalises array / envelope / string / HTML into a row array"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    val script =
      runtime + "\n" +
      """
        |function eq(a, b, msg) { if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(msg + ': ' + JSON.stringify(a)); }
        |eq(_ssc_ui_rowsOf([{a:1}]), [{a:1}], 'bare array');
        |eq(_ssc_ui_rowsOf({data:[{a:1}], count:1}), [{a:1}], 'data envelope');
        |eq(_ssc_ui_rowsOf({rows:[{b:2}]}), [{b:2}], 'rows envelope');
        |eq(_ssc_ui_rowsOf({items:[{c:3}]}), [{c:3}], 'items envelope');
        |eq(_ssc_ui_rowsOf('[{"d":4}]'), [{d:4}], 'json string');
        |eq(_ssc_ui_rowsOf('{"data":[{"e":5}]}'), [{e:5}], 'json string envelope');
        |eq(_ssc_ui_rowsOf('<!DOCTYPE html><script>x</script>'), [], 'html body');
        |eq(_ssc_ui_rowsOf({count:0}), [], 'object without list field');
        |eq(_ssc_ui_rowsOf(null), [], 'null');
        |eq(_ssc_ui_rowsOf(''), [], 'empty string');
        |console.log('rowsOf-ok');
        |""".stripMargin
    assert(runNode(script) == "rowsOf-ok")

  // ── js-content-toolkit-natives (busi seq-87 cluster-2) ─────────────────────
  // The std/ui/content toolkit externs (contentToolkitNode/Block/Section) were
  // undefined in the JS backend → Rule Pack Studio crashed init with `not
  // callable`.  They are now emitted (parity with JvmGen) and render authored
  // Markdown content — toolkit: control links + @ui=toolkit control trees — into
  // a TkNode tree that lowers to real DOM.
  test("JS runtime defines the content-toolkit natives bound to functions"):
    val source =
      """# App
        |
        |[serve, lower](std/ui/primitives.ssc)
        |[contentToolkitNode, contentToolkitBlock, contentToolkitSection](std/ui/content.ssc)
        |
        |```scalascript
        |serve(contentToolkitNode())
        |```
        |""".stripMargin
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    // bound to real top-level functions, never `= undefined`
    assert(moduleJs.contains("function contentToolkitNode("), "contentToolkitNode runtime function missing")
    assert(moduleJs.contains("function contentToolkitBlock("), "contentToolkitBlock runtime function missing")
    assert(moduleJs.contains("function contentToolkitSection("), "contentToolkitSection runtime function missing")
    // The whole bundle parses — proves the bare-name call sites resolve to the
    // emitted functions, not the undefined namespace member.
    checkNodeSyntax(moduleJs)

  test("contentToolkitNode renders toolkit links and @ui=toolkit controls to DOM"):
    val source =
      "# Panel\n\n" +
      "[Agree](toolkit:checkbox?signal=agree&label=Agree)\n\n" +
      "```yaml @id=cfg @ui=toolkit\n" +
      "controls:\n" +
      "  type: vstack\n" +
      "  gap: 8\n" +
      "  children:\n" +
      "    - type: heading\n" +
      "      level: 2\n" +
      "      text: Settings\n" +
      "    - type: text\n" +
      "      text: Hello toolkit\n" +
      "```\n\n" +
      "## Render\n\n" +
      "[serve](std/ui/primitives.ssc)\n" +
      "[lower](std/ui/lower.ssc)\n" +
      "[defaultTheme](std/ui/theme.ssc)\n" +
      "[contentToolkitNode](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "serve(lower(contentToolkitNode(), defaultTheme))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    assert(caps.contains(JsGen.Capability.Signals), s"expected Signals capability, got $caps")
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst out = _ssc_ui_renderBody(globalThis.__captured).body;\n" +
      "if (out.indexOf('Panel') < 0) throw new Error('section heading missing: ' + out);\n" +
      "if (out.indexOf('type=\"checkbox\"') < 0) throw new Error('toolkit:checkbox control missing: ' + out);\n" +
      "if (out.indexOf('Agree') < 0) throw new Error('checkbox label missing: ' + out);\n" +
      "if (out.indexOf('Settings') < 0) throw new Error('@ui=toolkit heading missing: ' + out);\n" +
      "if (out.indexOf('Hello toolkit') < 0) throw new Error('@ui=toolkit text missing: ' + out);\n" +
      "console.log('content-toolkit-ok');\n"
    assert(runNode(script) == "content-toolkit-ok")

  // busi seq-92 #2: the std/ui/content toolkit import lived only in a
  // transitively-imported module (app → studio → [contentToolkitBlock](…)). The
  // content/toolkit emission gate scanned the top module only, so the runtime
  // was not emitted and the transitive `contentToolkitBlock` call site threw
  // `ReferenceError`. The gate now walks the import graph.
  test("content-toolkit runtime emits when the toolkit is imported transitively"):
    val source  = os.read(TestPaths.repoRoot / "examples" / "content-toolkit-transitive" / "app.ssc")
    val module  = Parser.parse(source)
    val baseDir = TestPaths.repoRoot / "examples"
    val caps    = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    // both the content runtime and the toolkit functions must be present even
    // though the entry module never imports std/(ui/)content directly
    assert(moduleJs.contains("function contentDocument()"), "content runtime missing for transitive import")
    assert(moduleJs.contains("function contentToolkitBlock("), "toolkit runtime missing for transitive import")
    assert(moduleJs.contains("_ssc_tk_error"), "toolkit helpers missing for transitive import")
    checkNodeSyntax(runtime + "\n" + moduleJs)

  test("emit-spa markdown-toolkit-links example binds contentToolkitSection and parses"):
    val source  = os.read(TestPaths.repoRoot / "examples" / "markdown-toolkit-links.ssc")
    val module  = Parser.parse(source)
    val baseDir = TestPaths.repoRoot / "examples"
    val caps    = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    assert(moduleJs.contains("function contentToolkitSection("),
      "contentToolkitSection must be emitted for the toolkit example")
    // whole bundle parses — no undefined `not callable` toolkit native
    checkNodeSyntax(runtime + "\n" + moduleJs)
