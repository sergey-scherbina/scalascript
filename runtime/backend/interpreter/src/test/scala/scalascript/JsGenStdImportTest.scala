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

  // busi seq-102 (follow-up to the transitive-emission fix above): the toolkit
  // RUNTIME was emitted, but a block authored in a transitively-imported module
  // was not in the browser registry — `contentDocument()` is the entry document,
  // so `contentToolkitBlock("studio-preview")` (called from the child, where the
  // block lives) threw `no block with id`. The child's content document is now
  // registered transitively and the by-id lookup falls back across imported docs.
  test("contentToolkitBlock resolves a block defined in a transitively-imported module"):
    val source   = os.read(TestPaths.repoRoot / "examples" / "content-toolkit-transitive-register" / "app.ssc")
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    // the child's document (with the studio-preview block) must be registered,
    // even though the entry module never defines or imports it directly
    assert(moduleJs.contains("studio-preview"),
      "child module's content block must be registered in the imported-documents table")
    // and the rendered block actually reaches the DOM via the fallback lookup
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst out = _ssc_ui_renderBody(globalThis.__captured).body;\n" +
      "if (out.indexOf('Studio Preview') < 0) throw new Error('child block heading missing: ' + out);\n" +
      "if (out.indexOf(\"child module's own content block\") < 0) throw new Error('child block text missing: ' + out);\n" +
      "console.log('transitive-block-ok');\n"
    assert(runNode(script) == "transitive-block-ok")

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

  // ── busi-p2: transitive imports through emit-js (A → B → C) ──────────────
  //
  // Regression guard for the busi-p2-emit-js-transitive-imports report: a
  // bundle for `A` that imports `B`, where `B` imports `C` and uses a symbol
  // from `C`, must carry `C`'s definitions so `B`'s code resolves at runtime.
  // `emitJsLike` mirrors `ssc emit-js` precisely — segmented output with
  // tree-shaking ON, plus the per-segment `_output` flush the CLI appends.
  // Each fixture's entry module prints `bVal()` which transitively depends on
  // the deepest module; the expected stdout is `43`.
  private def emitJsLike(dir: String): String =
    val baseDir  = TestPaths.repoRoot / "examples" / dir
    val module   = Parser.parse(os.read(baseDir / "a.ssc"))
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val segs     = JsGen.generateSegmented(module, Some(baseDir), noTreeShake = false)
    val flush    = """if (typeof process !== 'undefined' && process.stdout) { process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); } _output = [];"""
    val moduleJs = segs.collect { case JsGen.Segment.ScalaScriptJs(code) => code + "\n" + flush }.mkString("\n")
    runNode(runtime + "\n" + moduleJs)

  test("emit-js propagates a transitive package import (A->B->C)"):
    assert(emitJsLike("js-transitive-iife") == "43")

  test("emit-js propagates a transitive name-only import (no package, A->B->C)"):
    assert(emitJsLike("js-transitive-iife-nopkg") == "43")

  test("emit-js propagates a 4-level transitive import (A->B->C->D)"):
    assert(emitJsLike("js-transitive-iife-4") == "43")

  // busi declarative-ui Scope A: the JS toolkit runtime now resolves the
  // `action=`/`rows=` registries (parity with the interpreter), turning a
  // declarative `toolkit:` link into a typed effect / live table.  Capture the
  // raw TkNode tree (serve intercepted, no lower) and assert the registered
  // handler / row source threaded through into ActionButtonNode / DataTableNode.
  test("contentToolkitSection resolves toolkit:button?action= and toolkit:table?rows= registries"):
    val source =
      "# Panel\n\n" +
      "## Controls {#controls}\n\n" +
      "- [Save draft](toolkit:button?action=saveDraft)\n" +
      "- [Delete](toolkit:button?action=delete&disabled=true)\n" +
      "- [Invoices](toolkit:table?rows=invoices)\n\n" +
      "[serve](std/ui/primitives.ssc)\n" +
      "[contentToolkitSection, contentToolkitOptionsWithRows, contentAction, contentRows](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      // contentToolkitOptionsWithRows takes (rowBindings, actions) as its leading
      // positional args — register both registries without named args.
      "val opts = contentToolkitOptionsWithRows(\n" +
      "  Map(contentRows(\"invoices\", \"SIG\", [\"COL_NO\", \"COL_AMT\"])),\n" +
      "  Map(contentAction(\"saveDraft\", \"SAVE_H\"), contentAction(\"delete\", \"DEL_H\"))\n" +
      ")\n" +
      "serve(contentToolkitSection(\"controls\", opts))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst j = JSON.stringify(globalThis.__captured);\n" +
      "if (j.indexOf('\"ActionButtonNode\"') < 0) throw new Error('action= did not produce ActionButtonNode: ' + j);\n" +
      "if (j.indexOf('SAVE_H') < 0) throw new Error('registered save handler not threaded through: ' + j);\n" +
      "if (j.indexOf('Save draft') < 0) throw new Error('action button label missing: ' + j);\n" +
      "if (j.indexOf('DEL_H') < 0) throw new Error('registered delete handler not threaded through: ' + j);\n" +
      "if (j.indexOf('\"DataTableNode\"') < 0) throw new Error('rows= did not produce DataTableNode: ' + j);\n" +
      "if (j.indexOf('SIG') < 0) throw new Error('registered row source not threaded through: ' + j);\n" +
      "if (j.indexOf('COL_NO') < 0) throw new Error('row binding columns missing: ' + j);\n" +
      "console.log('toolkit-registry-ok');\n"
    assert(runNode(script) == "toolkit-registry-ok")

  // busi seq-102 white-screen class: an unregistered action id must NOT abort the
  // whole render in the browser — it degrades to a visible inline error node for
  // that block (fail-soft, declarative-ui proposal §6).  The interpreter throws
  // here (server side); the browser fails soft — intentional, error-path-only.
  test("an unregistered toolkit action id renders an inline error, never blanks the render"):
    val source =
      "# Panel\n\n" +
      "## Controls {#controls}\n\n" +
      "- [Save](toolkit:button?action=nope)\n\n" +
      "[serve](std/ui/primitives.ssc)\n" +
      "[contentToolkitSection, contentToolkitOptionsWithActions, contentAction](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "serve(contentToolkitSection(\"controls\",\n" +
      "  contentToolkitOptionsWithActions(Map(contentAction(\"saveDraft\", \"SAVE_H\")))))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst j = JSON.stringify(globalThis.__captured);\n" +
      // fail-soft: the render completed (serve was called) instead of throwing,
      "if (typeof globalThis.__captured === 'undefined') throw new Error('render aborted (white-screen) instead of failing soft');\n" +
      "if (j.indexOf('RawTextNode') < 0) throw new Error('no inline error node emitted: ' + j);\n" +
      "if (j.indexOf('not registered') < 0) throw new Error('inline error lacks the loud message: ' + j);\n" +
      "if (j.indexOf('nope') < 0) throw new Error('inline error should name the bad id: ' + j);\n" +
      "if (j.indexOf('ActionButtonNode') >= 0) throw new Error('a bad id must not produce a wired button: ' + j);\n" +
      "console.log('toolkit-failsoft-ok');\n"
    assert(runNode(script) == "toolkit-failsoft-ok")

  // Regression for the JsGen named-arg-to-imported-function bug: the param-order
  // pre-pass only saw top-level statements, so functions / case classes defined
  // inside an imported module's `package:` namespace object were invisible and a
  // named-arg call to them was emitted positionally in WRITTEN order — landing
  // values in the wrong fields. Here `contentToolkitOptionsWithActions` is called
  // with a named `rowBindings =` that skips the `components`/`bindings` defaults;
  // before the fix `rowBindings` landed in `components` and the registry was empty
  // (`toolkit:table?rows=` → "not registered"). Now it reorders correctly and the
  // DataTableNode resolves.
  test("named arg to an imported option builder reorders (skips defaulted params)"):
    val source =
      "# Panel\n\n" +
      "## Controls {#controls}\n\n" +
      "- [Invoices](toolkit:table?rows=invoices)\n\n" +
      "[serve](std/ui/primitives.ssc)\n" +
      "[contentToolkitSection, contentToolkitOptionsWithActions, contentAction, contentRows](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "val opts = contentToolkitOptionsWithActions(\n" +
      "  Map(contentAction(\"saveDraft\", \"SAVE_H\")),\n" +
      "  rowBindings = Map(contentRows(\"invoices\", \"SIG\", [\"COL_NO\"]))\n" +
      ")\n" +
      "serve(contentToolkitSection(\"controls\", opts))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst j = JSON.stringify(globalThis.__captured);\n" +
      "if (j.indexOf('\"DataTableNode\"') < 0) throw new Error('named-arg rowBindings did not reach the registry (rows= unresolved): ' + j);\n" +
      "if (j.indexOf('SIG') < 0) throw new Error('registered row source missing — rowBindings landed in the wrong field: ' + j);\n" +
      "if (j.indexOf('COL_NO') < 0) throw new Error('row binding columns missing: ' + j);\n" +
      "console.log('named-arg-import-ok');\n"
    assert(runNode(script) == "named-arg-import-ok")

  // Scope B.1: the @ui=toolkit YAML control tree now resolves the same registries
  // as the Markdown `toolkit:` links — {type: button, action: <id>} and
  // {type: table, source: <id>} — at parity with the interpreter, in the browser.
  test("@ui=toolkit YAML controls resolve button action= and table source= in JS"):
    val source =
      "# Panel\n\n" +
      "## P {#p}\n\n" +
      "```yaml @ui=toolkit\n" +
      "controls:\n" +
      "  type: vstack\n" +
      "  children:\n" +
      "    - type: button\n" +
      "      action: saveDraft\n" +
      "      label: Save\n" +
      "    - type: table\n" +
      "      source: invoices\n" +
      "```\n\n" +
      "[serve](std/ui/primitives.ssc)\n" +
      "[contentToolkitNode, contentToolkitOptionsWithRows, contentAction, contentRows](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "serve(contentToolkitNode(contentToolkitOptionsWithRows(\n" +
      "  Map(contentRows(\"invoices\", \"SIG\", [\"COL_NO\"])),\n" +
      "  Map(contentAction(\"saveDraft\", \"SAVE_H\"))\n" +
      ")))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst j = JSON.stringify(globalThis.__captured);\n" +
      "if (j.indexOf('\"ActionButtonNode\"') < 0) throw new Error('YAML button action= did not produce ActionButtonNode: ' + j);\n" +
      "if (j.indexOf('SAVE_H') < 0) throw new Error('registered handler not threaded into YAML button: ' + j);\n" +
      "if (j.indexOf('\"DataTableNode\"') < 0) throw new Error('YAML table source= did not produce DataTableNode: ' + j);\n" +
      "if (j.indexOf('SIG') < 0) throw new Error('registered row source not threaded into YAML table: ' + j);\n" +
      "console.log('toolkit-yaml-registry-ok');\n"
    assert(runNode(script) == "toolkit-yaml-registry-ok")

  // Scope B.5: a code-registered computed signal (options.computed, via the named
  // `computed =` builder arg — also exercises the imported named-arg reorder fix)
  // is merged into the toolkit env so a YAML `signalText` resolves it by id and
  // renders its value in the browser.
  test("@ui=toolkit signalText resolves a registered computed signal in JS (Scope B.5)"):
    val source =
      "# Panel\n\n" +
      "## P {#p}\n\n" +
      "```yaml @ui=toolkit\n" +
      "controls:\n" +
      "  type: signalText\n" +
      "  signal: kpi\n" +
      "```\n\n" +
      "[serve, signal](std/ui/primitives.ssc)\n" +
      "[lower](std/ui/lower.ssc)\n" +
      "[defaultTheme](std/ui/theme.ssc)\n" +
      "[contentToolkitNode, contentToolkitOptionsWithRows, contentComputed](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "val kpi = signal(\"kpiSig\", \"FREE-CASH-42\")\n" +
      "serve(lower(contentToolkitNode(contentToolkitOptionsWithRows(\n" +
      "  Map(), computed = Map(contentComputed(\"kpi\", kpi)))), defaultTheme))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst out = _ssc_ui_renderBody(globalThis.__captured).body;\n" +
      "if (out.indexOf('FREE-CASH-42') < 0) throw new Error('computed signal value did not render (id unresolved?): ' + out);\n" +
      "console.log('toolkit-computed-ok');\n"
    assert(runNode(script) == "toolkit-computed-ok")

  // Scope B.2: inline YAML `columns:` build typed DataTable columns in the browser
  // by reusing the JS column-builder runtime (parity with the interpreter invoking
  // the column natives). Capture the raw tree and assert the typed kind reached
  // the DataTableNode columns.
  test("@ui=toolkit table builds typed columns from inline YAML columns: in JS (Scope B.2)"):
    val source =
      "# Panel\n\n" +
      "## P {#p}\n\n" +
      "```yaml @ui=toolkit\n" +
      "controls:\n" +
      "  type: table\n" +
      "  source: invoices\n" +
      "  columns:\n" +
      "    - label: Name\n" +
      "      path: name\n" +
      "    - label: Amount\n" +
      "      path: total\n" +
      "      kind: money\n" +
      "      currency: PLN\n" +
      "```\n\n" +
      "[serve](std/ui/primitives.ssc)\n" +
      "[contentToolkitNode, contentToolkitOptionsWithRows, contentRows](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "serve(contentToolkitNode(contentToolkitOptionsWithRows(\n" +
      "  Map(contentRows(\"invoices\", \"SIG\", [])))))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst j = JSON.stringify(globalThis.__captured);\n" +
      "if (j.indexOf('\"DataTableNode\"') < 0) throw new Error('table did not produce DataTableNode: ' + j);\n" +
      "if (j.indexOf('\"type\":\"money\"') < 0) throw new Error('money column kind not built from inline columns: ' + j);\n" +
      "if (j.indexOf('\"currency\":\"PLN\"') < 0) throw new Error('money currency not threaded: ' + j);\n" +
      "if (j.indexOf('\"fieldPath\":\"total\"') < 0) throw new Error('money column fieldPath missing: ' + j);\n" +
      "if (j.indexOf('\"type\":\"text\"') < 0) throw new Error('default text column not built: ' + j);\n" +
      "console.log('toolkit-columns-ok');\n"
    assert(runNode(script) == "toolkit-columns-ok")

  // Scope B.3: a `{type: table, source: <id>}` resolves a contentDataSource backed
  // by `fetchSource(..., rowsPath)`.  The managed-fetch source threads `_rowsPath`
  // onto the DataTableNode's source, and the browser `_ssc_ui_rowsOf` drills that
  // dotted envelope path (falling back to the built-in keys on a wrong path).
  test("@ui=toolkit source bound to a fetchSource(rowsPath) data source threads rowsPath in JS (Scope B.3)"):
    val source =
      "# Panel\n\n" +
      "## P {#p}\n\n" +
      "```yaml @ui=toolkit\n" +
      "controls:\n" +
      "  type: table\n" +
      "  source: invoices\n" +
      "```\n\n" +
      "[serve, signal](std/ui/primitives.ssc)\n" +
      "[contentToolkitNode, contentToolkitOptionsWithRows, contentDataSource, fetchSource](std/ui/content.ssc)\n\n" +
      "```scalascript\n" +
      "val tick = signal(\"tick\", 0)\n" +
      "val src = fetchSource(\"invoices\", \"/api/invoices\", tick, signal(\"h\", \"\"), \"result.items\")\n" +
      "serve(contentToolkitNode(contentToolkitOptionsWithRows(\n" +
      "  Map(contentDataSource(\"invoices\", src, [])))))\n" +
      "```\n"
    val module   = Parser.parse(source)
    val baseDir  = TestPaths.repoRoot / "examples"
    val caps     = JsGen.detectCapabilities(module, Some(baseDir))
    val runtime  = JsGen.generateRuntime(caps)
    val moduleJs = JsGen.generate(module, baseDir = Some(baseDir))
    val script =
      runtime + "\n_ssc_ui_serve = function(v){ globalThis.__captured = v; };\n" +
      moduleJs +
      "\nconst j = JSON.stringify(globalThis.__captured);\n" +
      "if (j.indexOf('\"DataTableNode\"') < 0) throw new Error('table did not produce DataTableNode: ' + j);\n" +
      "if (j.indexOf('\"_rowsPath\":\"result.items\"') < 0) throw new Error('rowsPath not threaded onto the fetch source: ' + j);\n" +
      // Directly exercise the envelope normaliser: the dotted path is drilled,
      "var drilled = _ssc_ui_rowsOf({ result: { items: [ { x: 1 }, { x: 2 } ] } }, 'result.items');\n" +
      "if (!Array.isArray(drilled) || drilled.length !== 2) throw new Error('rowsPath drill failed: ' + JSON.stringify(drilled));\n" +
      // a wrong path falls back to the built-in {data:[...]} key (never crashes),
      "var fellBack = _ssc_ui_rowsOf({ data: [ { y: 1 } ] }, 'no.such.path');\n" +
      "if (!Array.isArray(fellBack) || fellBack.length !== 1) throw new Error('rowsPath fallback failed: ' + JSON.stringify(fellBack));\n" +
      // and no rowsPath keeps the legacy behaviour.
      "var legacy = _ssc_ui_rowsOf({ items: [ { z: 1 } ] });\n" +
      "if (!Array.isArray(legacy) || legacy.length !== 1) throw new Error('legacy rowsOf regressed: ' + JSON.stringify(legacy));\n" +
      "console.log('b3-datasource-ok');\n"
    assert(runNode(script) == "b3-datasource-ok")
