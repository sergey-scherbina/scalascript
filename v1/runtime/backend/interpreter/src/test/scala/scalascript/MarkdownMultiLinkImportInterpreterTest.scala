package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class MarkdownMultiLinkImportInterpreterTest extends AnyFunSuite with Matchers:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    buf.toString.trim

  test("interpreter resolves two modules imported from one Markdown paragraph"):
    val dir = os.temp.dir(prefix = "ssc-multi-link-imports-")
    os.write(dir / "one.ssc",
      """---
        |name: one
        |exports:
        |  - one
        |---
        |# One
        |
        |```scalascript
        |def one(): Int = 1
        |```
        |""".stripMargin)
    os.write(dir / "two.ssc",
      """---
        |name: two
        |exports:
        |  - two
        |---
        |# Two
        |
        |```scalascript
        |def two(): Int = 2
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[one](one.ssc) [two](two.ssc)
        |
        |```scalascript
        |println(one() + two())
        |```
        |""".stripMargin)

    run(dir, "main.ssc") shouldBe "3"
