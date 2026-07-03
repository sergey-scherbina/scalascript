package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.CompileResult

/** parser-robustness-npe — a code-block parse error must surface loudly through
 *  the `run`/`compile` dispatch (`compileViaBackend`) as `CompileResult.Failed`
 *  with a non-zero exit, never a silently-dropped block that produced no output
 *  (the original "interpreter hangs with no message" symptom). The structured
 *  `file:line:col` diagnostic is printed to stderr by `reportCodeBlockParseErrors`. */
class ParserNpeDiagnosticTest extends AnyFunSuite:

  private def runWith(body: String): CompileResult =
    val dir  = os.temp.dir(prefix = "ssc-parser-npe-")
    val file = dir / "prog.ssc"
    os.write(file, s"# t\n\n```scalascript\n$body\n```\n")
    compileViaBackend("int", file)

  test("case A — bare \\\" in argument position fails loudly (not a silent run)"):
    runWith("val badCsv = \"x\"\nval r = badCsv.replace(\\\"a\\\", \\\"b\\\")\nprintln(r)") match
      case CompileResult.Failed(_) => ()
      case other                   => fail(s"expected Failed on a parse error; got $other")

  test("case B — unbalanced parens in deep nesting fails loudly"):
    runWith("def jObj(x: Int): Int = x\ndef jField(a: String, b: Int): Int = b\nval v = 1\nval r = jObj(jField(\"a\", jObj(jField(\"b\", v)))\nprintln(r)") match
      case CompileResult.Failed(_) => ()
      case other                   => fail(s"expected Failed on a parse error; got $other")

  test("a plain syntax error fails loudly too (no false negatives)"):
    runWith("val = 5\nprintln(1)") match
      case CompileResult.Failed(_) => ()
      case other                   => fail(s"expected Failed on a parse error; got $other")

  test("a valid program still runs (no false positive)"):
    // The interpreter backend prints directly to stdout, so Executed.stdout may
    // be empty here — the point is that a valid program is NOT rejected as Failed.
    runWith("var sum = 0\nList(1, 2, 3).foreach(x => sum = sum + x)\nprintln(sum)") match
      case CompileResult.Executed(_, _, exit) =>
        assert(exit == 0, s"valid program should exit 0; got $exit")
      case other => fail(s"expected Executed on a valid program; got $other")
