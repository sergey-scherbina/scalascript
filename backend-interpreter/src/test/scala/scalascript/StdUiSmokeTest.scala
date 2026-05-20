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
 *  A successful run prints "smoke:ok" to stdout; we pin that here. */
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
