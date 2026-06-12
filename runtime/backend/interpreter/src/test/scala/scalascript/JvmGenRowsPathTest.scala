package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** Scope B.3 — emit-jvm parity for `rowsPath`. The generated Scala must call
 *  `fetchRowsSource(sig, "result.items")` and the JVM preamble must define it as
 *  a `TableDataSource.Remote(sig, rowsPath)` builder, so the served custom/static
 *  frontend (StaticJsEmitter `__ssc_rowsOf`) drills the envelope just like the
 *  interpreter `serve` path and the JS browser SPA. */
class JvmGenRowsPathTest extends AnyFunSuite:

  private val baseDir = Some(TestPaths.repoRoot / "runtime")

  // `frontend: custom` → the served View is emitted via the custom/static
  // StaticJsEmitter (which drills `rowsPath`), and `uiHelperFunctions` (with the
  // fetchRowsSource preamble def) is emitted because a frontend is defined.
  private val src =
    """
      |---
      |frontend: custom
      |---
      |
      |[signal, fetchUrlSignal, fetchRowsSource, dataTableView, fieldColumn, serve](std/ui/primitives.ssc)
      |
      |```scalascript
      |val t = signal("t", 0)
      |val src = fetchRowsSource(fetchUrlSignal("rows", "/api/x", t), "result.items")
      |val view = dataTableView(src, [fieldColumn("Name", "name")], [])
      |serve(view, 0)
      |```
      |""".stripMargin

  test("emit-jvm threads rowsPath through fetchRowsSource into a Remote source"):
    val code = JvmGen.generate(Parser.parse(src), baseDir = baseDir, frontendOverride = Some("custom"))
    // The user call keeps the dotted envelope path.
    assert(code.contains("fetchRowsSource(") , s"no fetchRowsSource call:\n$code")
    assert(code.contains("\"result.items\""), "rowsPath argument dropped from generated code")
    // The preamble defines fetchRowsSource as a Remote(sig, rowsPath) builder.
    assert(code.contains("def fetchRowsSource("), "JVM preamble missing fetchRowsSource def")
    assert(code.contains("scalascript.frontend.TableDataSource.Remote("),
      "fetchRowsSource preamble does not build a TableDataSource.Remote")
