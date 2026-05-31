package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** toWire/fromWire must also round-trip Option, Char, and null — case classes
 *  routinely have `Option` fields (e.g. an account's parent code). */
class WireOptionTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("Option (Some/None), Char, and null round-trip"):
    run("""
      println(fromWire(toWire(Some(5))) == Some(5))
      println(fromWire(toWire(None)) == None)
      println(fromWire(toWire(Some("x"))) == Some("x"))
      println(fromWire(toWire('a')) == 'a')
      println(fromWire(toWire(null)) == null)
    """) shouldBe "true\ntrue\ntrue\ntrue\ntrue"

  test("case class with Option fields round-trips (the busi Account shape)"):
    run("""
      case class Account(code: String, name: String, parent: Option[String], currency: Option[String])
      val a = Account("1100", "AR", Some("1000"), None)
      val back = fromWire(toWire(a))
      println(back == a)
      println(back.parent)
      println(back.currency)
    """) shouldBe "true\nSome(1000)\nNone"

  test("list of records with Option fields (event-log shape)"):
    run("""
      case class Acct(code: String, parent: Option[String])
      val xs = List(Acct("1", None), Acct("2", Some("1")))
      println(fromWire(toWire(xs)) == xs)
    """) shouldBe "true"
