package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression for the imported guard predicates used by busi Housing and
 *  Official Documents. `&&` and `||` must not evaluate an unsafe right-hand
 *  side after the left-hand side decides the result. */
class BooleanShortCircuitImportTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parseFile(dir / entry))
    ps.flush()
    buf.toString.trim

  test("imported list and Option guards short-circuit their unsafe right-hand sides"):
    val dir = os.temp.dir(prefix = "ssc-short-circuit-import-")
    try
      os.write(dir / "owner_predicates.ssc",
        """---
          |name: owner_predicates
          |exports:
          |  - eligibleHead
          |  - absentOrExpected
          |---
          |# Owner predicates
          |
          |```scalascript
          |def eligibleHead(values: List[String]): Boolean =
          |  values.nonEmpty && values.head == "eligible"
          |
          |def absentOrExpected(value: Option[String]): Boolean =
          |  value.isEmpty || value.get == "expected"
          |```
          |""".stripMargin)
      os.write(dir / "main.ssc",
        """# Main
          |
          |[eligibleHead, absentOrExpected](owner_predicates.ssc)
          |
          |```scalascript
          |println(eligibleHead(Nil))
          |println(eligibleHead(List("eligible")))
          |println(absentOrExpected(None))
          |println(absentOrExpected(Some("expected")))
          |```
          |""".stripMargin)

      assert(run(dir, "main.ssc") == "false\ntrue\ntrue\ntrue")
    finally os.remove.all(dir)
