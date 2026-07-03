package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** enum-value-support — enum cases are usable as values: bare and qualified
 *  references, matching, and `EnumName.values`. */
class EnumValueTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("comma-separated nullary cases bind (the reported gap)"):
    run("""
      enum Side:
        case Debit, Credit
      def f(s: Side): String = s match
        case Debit  => "d"
        case Credit => "c"
      println(f(Debit))
      println(f(Credit))
    """) shouldBe "d\nc"

  test("qualified access EnumName.Case"):
    run("""
      enum Side:
        case Debit, Credit
      println(Side.Debit == Debit)
      def f(s: Side): String = s match
        case Side.Debit  => "d"
        case Side.Credit => "c"
      println(f(Side.Credit))
    """) shouldBe "true\nc"

  test("one-case-per-line nullary cases"):
    run("""
      enum Color:
        case Red
        case Green
        case Blue
      def name(c: Color): String = c match
        case Red   => "red"
        case Green => "green"
        case Blue  => "blue"
      println(name(Green))
    """) shouldBe "green"

  test("EnumName.values lists the parameterless cases in order"):
    run("""
      enum Side:
        case Debit, Credit
      println(Side.values.length)
      println(Side.values.map(s => s match { case Debit => "D"; case Credit => "C" }))
    """) shouldBe "2\nList(D, C)"

  test("parametrized enum cases still work as constructors"):
    run("""
      enum Shape:
        case Circle(r: Int)
        case Square(side: Int)
        case Unit
      def area(s: Shape): Int = s match
        case Circle(r)    => 3 * r * r
        case Square(side) => side * side
        case Unit         => 0
      println(area(Circle(2)))
      println(area(Square(3)))
      println(area(Unit))
    """) shouldBe "12\n9\n0"

  test("enum used as a domain element with derived logic (busi-style)"):
    run("""
      enum Element:
        case Asset, Liability, Equity, Income, Expense
      enum Side:
        case Debit, Credit
      def normalSide(e: Element): Side = e match
        case Asset   => Debit
        case Expense => Debit
        case _       => Credit
      println(normalSide(Asset) == Debit)
      println(normalSide(Income) == Credit)
      println(normalSide(Expense) == Debit)
    """) shouldBe "true\ntrue\ntrue"
