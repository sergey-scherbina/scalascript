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
    assert(code.contains("def _ssc_exactRowPayload("), "JVM preamble must validate every row payload")
    assert(code.contains("fields must be unique non-empty dotted field paths"),
      "JVM preamble must reject empty, duplicate, and malformed Fields payloads")

  test("scala-cli executes emitted JVM row payload rejection"):
    assume(ProcTestUtil.commandOk("scala-cli"), "scala-cli not available")
    val invalid =
      """
        |[fieldsPayload](std/ui/primitives.ssc)
        |
        |```scalascript
        |fieldsPayload(List())
        |```
        |""".stripMargin
    val emittedBody = JvmGen.generate(
      Parser.parse(invalid), baseDir = baseDir, frontendOverride = Some("custom"))
      .linesIterator
      // Execute against this checkout's frontend ABI. The published SNAPSHOT can
      // predate View.DataTable and otherwise shadows the local classes below.
      .filterNot(line =>
        line.contains("scalascript-frontend-core") ||
          line.contains("scalascript-frontend-custom"))
      .filterNot(_.contains("def text(content: String) = std.ui.typography.text(content)"))
      .mkString("\n")
    val emitted = "//> using scala 3.8.3\n" + emittedBody
    val script = os.temp(emitted, suffix = ".sc", deleteOnExit = true)
    val localFrontendClasses = List("core", "custom", "swing", "javafx")
      .map(name => (TestPaths.repoRoot / "frontend" / name / "target" / "scala-3.8.3" / "classes").toString)
      .mkString(java.io.File.pathSeparator)
    val classpath = localFrontendClasses + java.io.File.pathSeparator + sys.props("java.class.path")
    val result = os.proc(
      "scala-cli", "run", "--server=false", "--classpath", classpath, script.toString)
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode != 0, "emitted fieldsPayload(List()) unexpectedly succeeded")
    assert(result.err.text().contains("fields must be unique non-empty dotted field paths"),
      s"unexpected emitted helper failure:\n${result.err.text()}\n${result.out.text()}")
