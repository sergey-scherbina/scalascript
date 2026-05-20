package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import java.nio.file.{Files, Paths}

class ToolkitDemoValidateTest extends AnyFunSuite:
  test("toolkit-demo generates JS with onChange and onClick handlers") {
    val src = os.read(
      TestPaths.repoRoot / "examples" / "frontend" / "toolkit-demo" / "toolkit-demo.ssc"
    )
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    // serve() emits to a temp dir THEN starts the server.  In test environments
    // port 8080 may already be occupied; we catch BindException so the JS check
    // still runs on the already-written temp dir.
    // baseDir = repoRoot so that `std/ui/*.ssc` imports resolve without needing
    // ssc.lib.path, since the demo uses bare `std/` paths not `../../std/`.
    try
      Interpreter(out = ps, headless = false,
                  baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    catch case _: java.net.BindException => ()
    // serve writes to /tmp/ssc-ui* (macOS: /private/tmp/ssc-ui*)
    val tmpBase = Paths.get(System.getProperty("java.io.tmpdir"))
    val latest = Files.list(tmpBase)
      .filter(p => p.getFileName.toString.startsWith("ssc-ui"))
      .sorted(java.util.Comparator.comparingLong[java.nio.file.Path](
        p => Files.getLastModifiedTime(p).toMillis).reversed)
      .findFirst
    assert(latest.isPresent, "no ssc-ui* temp dir created")
    val js = Files.readString(latest.get.resolve("app.js"))
    assert(js.contains("setAccept(c => !c)"),    "missing checkbox onChange (toggleSignal)")
    assert(js.contains("setName(e.target.value)"), "missing textField onChange (inputChange)")
    assert(js.contains("setSubmitted(true)"),     "missing submit button onClick")
  }
