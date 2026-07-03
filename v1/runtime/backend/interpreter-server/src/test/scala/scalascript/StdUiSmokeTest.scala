package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.29 Phase 7e — interpreter smoke test for all std/ui .ssc widget modules.
 *
 *  Runs `examples/frontend/std-ui/smoke-test.ssc` through the
 *  interpreter in headless mode (no web server, no browser) and asserts
 *  that every widget family lowers to a View tree without throwing and
 *  that `emit` writes the output files successfully.
 *
 *  Exercises all ten std/ui modules: layout, typography, input, reactive,
 *  display, containers, data, and routing.
 *  A successful run prints "smoke:ok" to stdout; we pin that here.
 *
 *  Lives in backendInterpreterServer (not backendInterpreter) because
 *  frontendPlugin % Test is needed here and adding it to backendInterpreter
 *  would create a cycle via testUtils. */
class StdUiSmokeTest extends AnyFunSuite:

  test("std/ui smoke-test.ssc runs without error and prints smoke:ok") {
    val src = os.read(
      TestPaths.repoRoot / "examples" / "frontend" / "std-ui" / "smoke-test.ssc"
    )
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    ps.flush()
    val got = buf.toString.trim
    assert(got.contains("smoke:ok"),
      s"smoke-test.ssc did not print 'smoke:ok'.  Got:\n$got")
  }

  // js-backend-ui-render-gaps (busi seq-79), Layer 2: `lower` must be idempotent
  // on an already-lowered View, so a `*Content` that lowers its own children and
  // a caller that lowers again do not throw.  Mirrors the JS-backend passthrough.
  test("lower is idempotent on an already-lowered View (no MatchError)") {
    val src =
      """# Idempotent lower
        |
        |[lower](std/ui/lower.ssc)
        |[defaultTheme](std/ui/theme.ssc)
        |[heading](std/ui/typography.ssc)
        |[staticDataTable, fcol](std/ui/data.ssc)
        |
        |```scalascript
        |val once  = lower(heading(1, "Hi"), defaultTheme)
        |val twice = lower(once, defaultTheme)        // lower an already-lowered _Element
        |val tbl   = staticDataTable([["name" -> "Ada"]], [fcol("Name", "name")], [])
        |val tbl2  = lower(tbl, defaultTheme)         // staticDataTable is already a View
        |println("lower-idempotent:ok")
        |```
        |""".stripMargin
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    ps.flush()
    val got = buf.toString.trim
    assert(got.contains("lower-idempotent:ok"),
      s"double-lower threw or did not complete.  Got:\n$got")
  }
