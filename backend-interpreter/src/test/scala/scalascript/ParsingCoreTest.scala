package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.20 Phase 2 — std/parsing/core.ssc: Parser[A] ADT data construction and pattern matching. */
class ParsingCoreTest extends AnyFunSuite with Matchers:

  private val repoRoot = TestPaths.repoRoot

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Position, Span, ParseError, ParseOk, ParseErr, Parser, PChar, PString, PRegex, PSatisfy, PSucceed, PFail](std/parsing/core.ssc)
         |
         |```scalascript
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("core: Position and Span construct correctly"):
    captured("""
      val p1 = Position(0)
      val p2 = Position(5)
      val sp = Span(p1, p2)
      println(sp.start.offset)
      println(sp.end.offset)
    """) shouldBe "0\n5"

  test("core: ParseError carries message and position"):
    captured("""
      val e = ParseError("unexpected char", Position(3))
      println(e.message)
      println(e.pos.offset)
    """) shouldBe "unexpected char\n3"

  test("core: ParseOk wraps value, rest, pos"):
    captured("""
      val r = ParseOk("hello", " world", Position(5))
      r match
        case ParseOk(v, rest, pos) => println(v + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("fail: " + e.message)
    """) shouldBe "hello| world|5"

  test("core: ParseErr wraps ParseError"):
    captured("""
      val r = ParseErr(ParseError("oops", Position(0)))
      r match
        case ParseOk(v, _, _) => println("ok: " + v)
        case ParseErr(e)      => println("err: " + e.message)
    """) shouldBe "err: oops"

  test("core: Parser.char builds PChar node"):
    captured("""
      val p = Parser.char('a')
      p match
        case PChar(c) => println("char:" + c)
        case _        => println("other")
    """) shouldBe "char:a"

  test("core: Parser.string builds PString node"):
    captured("""
      val p = Parser.string("hello")
      p match
        case PString(s) => println("str:" + s)
        case _          => println("other")
    """) shouldBe "str:hello"

  test("core: Parser.regex builds PRegex node"):
    captured("""
      val p = Parser.regex("[0-9]+")
      p match
        case PRegex(pat) => println("regex:" + pat)
        case _           => println("other")
    """) shouldBe "regex:[0-9]+"

  test("core: Parser.succeed builds PSucceed node"):
    captured("""
      val p = Parser.succeed(42)
      p match
        case PSucceed(v) => println("success:" + v)
        case _           => println("other")
    """) shouldBe "success:42"

  test("core: Parser.fail builds PFail node"):
    captured("""
      val p = Parser.fail("expected digit")
      p match
        case PFail(msg) => println("fail:" + msg)
        case _          => println("other")
    """) shouldBe "fail:expected digit"

  test("core: Parser.satisfy builds PSatisfy node"):
    captured("""
      val p = Parser.satisfy(c => c == 'a')
      p match
        case PSatisfy(_) => println("satisfy:ok")
        case _           => println("other")
    """) shouldBe "satisfy:ok"
