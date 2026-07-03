package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** End-to-end check for the std.json typed-JSON surface (busi UI proposals P1).
 *  Runs `examples/ui-typed-json.ssc` through the real driver with std-import
 *  resolution + the json-plugin on the classpath, exercising the `.ssc`
 *  surface (opaque `JsonValue`, total extension-method accessors, exact
 *  `asDecimal`, and the structured string builders) — which the in-isolation
 *  plugin test cannot reach. */
class JsonTypedExampleTest extends AnyFunSuite:

  test("ui-typed-json.ssc decodes + encodes through std.json"):
    val src = os.read(TestPaths.repoRoot / "examples" / "ui-typed-json.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    ps.flush()
    val out = buf.toString
    assert(out.contains("typed-json:ok"), s"example did not complete:\n$out")
    assert(out.contains("name    : Acme"), s"object decode failed:\n$out")
    assert(out.contains("missing : 2"), s"int decode failed:\n$out")
    assert(out.contains("due     : 1000.01"), s"exact decimal decode failed:\n$out")
    assert(out.contains("absent  : ''"), s"missing-key totality failed:\n$out")
    assert(out.contains("steps   : KYC, Bank"), s"array navigation failed:\n$out")
    // structured encode escapes quotes + newlines
    assert(out.contains("""body    : {"name":"Acme \"HQ\"","note":"line1\nline2"}"""),
      s"structured encode/escape failed:\n$out")
