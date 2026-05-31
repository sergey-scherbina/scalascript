package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** `toWire` / `fromWire` — Value ⇄ string serialization exposed to .ssc. */
class ValueWireTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("round-trips primitives, collections, BigInt/Decimal, Set"):
    run("""
      val v = (42, "hi", true, List(1, 2, 3), Map("a" -> 1), Set(1, 2), BigInt("100"), Decimal("12.34"))
      println(fromWire(toWire(v)) == v)
    """) shouldBe "true"

  test("round-trips a case-class instance with nested fields"):
    run("""
      case class Money(amount: Decimal, currency: String)
      case class Posting(account: String, side: String, money: Money)
      val p = Posting("1000", "debit", Money(Decimal("99.95"), "USD"))
      val back = fromWire(toWire(p))
      println(back == p)
      println(back.account)
      println(back.money.amount)
    """) shouldBe "true\n1000\n99.95"

  test("round-trips a list of records (an event-log shape)"):
    run("""
      case class Rec(seq: Int, kind: String, amount: Decimal)
      val log = List(Rec(0, "open", Decimal("0")), Rec(1, "post", Decimal("1000.00")))
      val back = fromWire(toWire(log))
      println(back == log)
      println(back.length)
      println(back(1).amount)
    """) shouldBe "true\n2\n1000.00"

  test("serialized form is a parseable string"):
    run("""
      val s = toWire(List(1, 2, 3))
      println(s.length > 0)
      println(fromWire(s) == List(1, 2, 3))
    """) shouldBe "true\ntrue"
