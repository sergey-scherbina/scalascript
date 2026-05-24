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

  test("JS runtime upload directory default is guarded for browser renderers"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Core))
    assert(runtime.contains("typeof require === 'function'"))
    assert(!runtime.contains("let _uploadDir = require('os').tmpdir();"))
