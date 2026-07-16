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

  test("imported module exports identifiers ending in underscore"):
    val dir = os.temp.dir(prefix = "ssc-underscore-export-")
    os.write(dir / "dep.ssc",
      """---
        |name: dep
        |exports:
        |  - type_
        |  - at_
        |  - seq_
        |---
        |# Dep
        |
        |```scalascript
        |val type_ = "event"
        |val at_ = "2026-06-04"
        |def seq_(xs: List[String]): String =
        |  xs.mkString(type_ + "@" + at_ + ":")
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[type_, at_, seq_](dep.ssc)
        |
        |```scalascript
        |println(type_ + "|" + at_ + "|" + seq_(List("a", "b")))
        |```
        |""".stripMargin)
    run(dir, "main.ssc") shouldBe "event|2026-06-04|aevent@2026-06-04:b"

  test("imported module remains exportable with foldLeft brace lambda"):
    val dir = os.temp.dir(prefix = "ssc-brace-fold-export-")
    os.write(dir / "dep.ssc",
      """---
        |name: dep
        |exports:
        |  - joined
        |---
        |# Dep
        |
        |```scalascript
        |def joined(xs: List[String]): String =
        |  xs.foldLeft("") { (acc, item) =>
        |    if acc == "" then item else acc + "," + item
        |  }
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[joined](dep.ssc)
        |
        |```scalascript
        |println(joined(List("a", "b", "c")))
        |```
        |""".stripMargin)
    run(dir, "main.ssc") shouldBe "a,b,c"

  test("an internal helper keeps its module-local override of an imported function"):
    val dir = os.temp.dir(prefix = "ssc-transitive-local-shadow-")
    os.write(dir / "base.ssc",
      """---
        |name: base
        |exports:
        |  - sameName
        |---
        |```scalascript
        |def sameName(a: String, b: String): Unit = ()
        |```
        |""".stripMargin)
    os.write(dir / "dep.ssc",
      """---
        |name: dep
        |exports:
        |  - outer
        |---
        |[sameName](base.ssc)
        |
        |```scalascript
        |def sameName(a: String, b: String): Boolean = a == b
        |def helper(a: String, b: String): Boolean =
        |  sameName(a, b) && a.length == b.length
        |def outer(a: String, b: String): Boolean =
        |  helper(a, b) && a.nonEmpty
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """[sameName](base.ssc)
        |[outer](dep.ssc)
        |
        |```scalascript
        |println(outer("PLN", "PLN"))
        |```
        |""".stripMargin)
    run(dir, "main.ssc") shouldBe "true"
