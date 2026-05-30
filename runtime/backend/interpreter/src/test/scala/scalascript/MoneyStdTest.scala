package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** exact-numerics v1.64.6 — the std `money.ssc` module: Money/Currency on top
 *  of Decimal, with cent-preserving allocation.  Loads the real module source
 *  and runs a driver against it on the interpreter. */
class MoneyStdTest extends AnyFunSuite with Matchers:

  private lazy val moneySrc: String =
    os.read(TestPaths.repoRoot / "runtime" / "std" / "money.ssc")

  /** Append a driver section to the module and run the whole thing. */
  private def runWith(driver: String): String =
    val src = moneySrc + "\n## Driver\n\n```scalascript\n" + driver + "\n```\n"
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("construction + formatting normalises to currency scale"):
    runWith("""
      println(formatMoney(money("1.5", "USD")))
      println(formatMoney(money("1000", "JPY")))
    """) shouldBe "$1.50\n¥1000"

  test("plus / minus within a currency"):
    runWith("""
      val a = money("10.00", "USD")
      val b = money("3.50", "USD")
      println(formatMoney(plus(a, b)))
      println(formatMoney(minus(a, b)))
      println(compareMoney(a, b))
    """) shouldBe "$13.50\n$6.50\n1"

  test("minorUnits gives the integer cent count"):
    runWith("""println(minorUnits(money("12.34", "USD")))""") shouldBe "1234"

  test("times scales and rounds to currency scale"):
    runWith("""println(formatMoney(times(money("2.00", "USD"), Decimal("1.5"))))""") shouldBe "$3.00"

  test("allocate preserves cents: $0.05 three ways → 2,2,1"):
    runWith("""
      val parts = allocate(money("0.05", "USD"), List(1, 1, 1))
      println(parts.map(formatMoney))
    """) shouldBe "List($0.02, $0.02, $0.01)"

  test("allocate by uneven weights still sums to the total"):
    runWith("""
      val parts = allocate(money("100.00", "USD"), List(1, 1, 1))
      println(parts.map(formatMoney))
      val sum = parts.foldLeft(money("0.00", "USD"))((acc, p) => plus(acc, p))
      println(formatMoney(sum))
    """) shouldBe "List($33.34, $33.33, $33.33)\n$100.00"

  test("distribute n equal shares"):
    runWith("""
      println(distribute(money("10.00", "USD"), 3).map(formatMoney))
    """) shouldBe "List($3.34, $3.33, $3.33)"
