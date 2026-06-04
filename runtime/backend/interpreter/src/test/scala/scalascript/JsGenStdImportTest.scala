package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JsRuntimeBrowserPatch}
import scalascript.parser.Parser

class JsGenStdImportTest extends AnyFunSuite:

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
