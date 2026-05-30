package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Importing a module whose *exported* function internally calls a
 *  *non-exported* helper that uses a name the module itself imported (but did
 *  not re-export) must resolve at call time in the importer.  This is the
 *  busi `isBalanced → validateEntry → minorUnits` scenario. */
class TransitiveImportHelperTest extends AnyFunSuite with Matchers:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush(); buf.toString.trim

  test("exported fn → internal helper → transitively-imported std name"):
    val dir = os.temp.dir(prefix = "ssc-transitive-")
    os.write(dir / "dep.ssc",
      """---
        |name: dep
        |exports:
        |  - outer
        |---
        |# Dep
        |
        |[money, minorUnits](std/money.ssc)
        |
        |```scalascript
        |def helper(s: String): BigInt = minorUnits(money(s, "USD"))
        |def outer(s: String): BigInt = helper(s)
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[outer](dep.ssc)
        |
        |```scalascript
        |println(outer("12.34"))
        |```
        |""".stripMargin)
    run(dir, "main.ssc") shouldBe "1234"

  test("multi-level chain: exported → helper1 → helper2 → imported name"):
    val dir = os.temp.dir(prefix = "ssc-transitive2-")
    os.write(dir / "dep.ssc",
      """---
        |name: dep
        |exports:
        |  - total
        |---
        |# Dep
        |
        |[money, minorUnits, plus, moneyOf](std/money.ssc)
        |
        |```scalascript
        |def cents(s: String): BigInt = minorUnits(money(s, "USD"))
        |def sumTwo(a: String, b: String): BigInt = cents(a) + cents(b)
        |def total(xs: List[String]): BigInt =
        |  xs.foldLeft(BigInt(0))((acc, s) => acc + cents(s))
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[total](dep.ssc)
        |
        |```scalascript
        |println(total(List("1.00", "2.50", "0.34")))
        |```
        |""".stripMargin)
    run(dir, "main.ssc") shouldBe "384"
