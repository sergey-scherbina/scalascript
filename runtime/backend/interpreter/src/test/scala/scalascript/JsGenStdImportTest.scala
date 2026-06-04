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
