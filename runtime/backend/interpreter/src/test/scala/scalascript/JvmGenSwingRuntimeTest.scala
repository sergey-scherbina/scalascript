package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

class JvmGenSwingRuntimeTest extends AnyFunSuite:

  test("Swing frontend helper launches same-process runtime instead of nested scala-cli"):
    val src =
      """
        |---
        |frontend: swing
        |---
        |
        |```scalascript
        |val view = text("Hello")
        |serve(view, 0)
        |```
        |""".stripMargin
    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(code.contains("scalascript.frontend.swing.SwingRuntime.run("))
    assert(code.contains("mode:   same-process JVM"))
    assert(code.contains("_ssc_ui_inprocess_fetch(method, url, body)"))
    assert(code.contains("new scalascript.frontend.swing.SwingRuntime.FetchDispatcher"))
    assert(!code.contains("ProcessBuilder(_scalaCli"))
