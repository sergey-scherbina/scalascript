package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser
import java.nio.file.Files

/** Lives in backendInterpreterServer (not backendInterpreter) because it needs
 *  frontendPlugin % Test (for signal) and sqlPlugin % Test (for SQL blocks),
 *  both of which depend on testUtils → backendInterpreter, making them
 *  circular if added to backendInterpreter directly. */
class ToolkitDemoValidateTest extends AnyFunSuite:
  test("toolkit-demo generates JS with onChange and onClick handlers") {
    val outDir = Files.createTempDirectory("ssc-toolkit-demo-test")
    val raw = os.read(
      TestPaths.repoRoot / "examples" / "frontend" / "toolkit-demo" / "toolkit-demo.ssc"
    )
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    // This test checks frontend generation, not HTTP lifecycle.  Replacing the
    // demo's final serve(...) with emit(...) keeps the generated app.js path
    // deterministic and avoids binding or blocking on port 8080.
    val serveCall = "serve(lower(tree, defaultTheme), 8080, mobileOverrideCss(767, mobileTheme))"
    assert(raw.contains(serveCall), "toolkit-demo serve call changed; update this emit-only test")
    val src = raw.replace(serveCall, s"""emit(lower(tree, defaultTheme), "${outDir.toString}")""")
    // baseDir = repoRoot so that `std/ui/*.ssc` imports resolve without needing
    // ssc.lib.path, since the demo uses bare `std/` paths not `../../std/`.
    val interp = Interpreter(out = ps, headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    interp.run(Parser.parse(src))
    val js = Files.readString(outDir.resolve("app.js"))
    assert(js.contains("setAccept(c => !c)"),    "missing checkbox onChange (toggleSignal)")
    assert(js.contains("setName(e.target.value)"), "missing textField onChange (inputChange)")
    assert(js.contains("setSubmitted(true)"),     "missing submit button onClick")
  }
