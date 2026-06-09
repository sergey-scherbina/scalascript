package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** std.ui.fetch_json — `fetchJsonSignal` / `fetchJsonAction` sugar (busi UI
 *  proposals P1).  Runs `examples/ui-fetch-json.ssc` through the real driver
 *  (std-import resolution + frontend/fetch/json plugins) to confirm the
 *  composition compiles and constructs: the structured-body builder produces
 *  escaped JSON and `fetchJsonAction`/`fetchJsonSignal` wire up without error. */
class JsonFetchSugarTest extends AnyFunSuite:

  test("ui-fetch-json.ssc composes fetch sugar over std.json"):
    val src = os.read(TestPaths.repoRoot / "examples" / "ui-fetch-json.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    ps.flush()
    val out = buf.toString
    assert(out.contains("fetch-json:ok"), s"sugar did not construct:\n$out")
    // structured body builds escaped JSON from the std.json builders
    assert(out.contains("""body:{"name":"Acme \"HQ\"","n":5}"""),
      s"structured body builder output wrong:\n$out")
