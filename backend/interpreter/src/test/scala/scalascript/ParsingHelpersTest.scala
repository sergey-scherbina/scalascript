package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.20 Phase 4 — std/parsing/helpers.ssc: tokenization helpers + JSON parser acceptance. */
class ParsingHelpersTest extends AnyFunSuite with Matchers:

  private val repoRoot = TestPaths.repoRoot

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test

[Position, ParseError, ParseOk, ParseErr, Parser, PChar, PString, PRegex, PSatisfy, PSucceed, PFail](std/parsing/core.ssc)

[PSequence, PChoice, PMany, POpt, PMapped, PFlatMapped, PNamed, runParser](std/parsing/combinators.ssc)

[whitespace, whitespace1, identifier, integer, number, stringLit, skip, keyword, token](std/parsing/helpers.ssc)

```scalascript
$code
```
""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Individual helper parsers ─────────────────────────────────────────

  test("helpers: whitespace skips spaces"):
    captured("""
      whitespace.parse("   abc") match
        case ParseOk(v, rest, pos) => println("len:" + v.length + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("err")
    """) shouldBe "len:3|abc|3"

  test("helpers: whitespace matches empty"):
    captured("""
      whitespace.parse("abc") match
        case ParseOk(v, rest, _) => println("ok:" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "ok:abc"

  test("helpers: whitespace1 fails on no whitespace"):
    captured("""
      whitespace1.parse("abc") match
        case ParseOk(_, _, _) => println("ok")
        case ParseErr(_)      => println("fail")
    """) shouldBe "fail"

  test("helpers: identifier parses word"):
    captured("""
      identifier.parse("hello_world 123") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "hello_world| 123"

  test("helpers: integer parses positive"):
    captured("""
      integer.parse("42rest") match
        case ParseOk(v, rest, _) => println("" + v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "42|rest"

  test("helpers: integer parses negative"):
    captured("""
      integer.parse("-7xyz") match
        case ParseOk(v, rest, _) => println("" + v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "-7|xyz"

  test("helpers: number parses float"):
    captured("""
      number.parse("3.14rest") match
        case ParseOk(v, rest, _) => println("" + v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "3.14|rest"

  test("helpers: number parses whole as double"):
    captured("""
      number.parse("10") match
        case ParseOk(v, _, _) => println("" + v)
        case ParseErr(_)      => println("err")
    """) shouldBe "10"

  test("helpers: stringLit parses quoted string"):
    captured("""
      stringLit.parse("\"hello\" rest") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "hello| rest"

  test("helpers: stringLit parses empty string"):
    captured("""
      stringLit.parse("\"\"x") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "|x"

  test("helpers: skip strips surrounding whitespace"):
    captured("""
      skip(Parser.string("ok")).parse("  ok  rest") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "ok|rest"

  test("helpers: keyword matches literal"):
    captured("""
      keyword("true").parse("trueX") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "true|X"

  test("helpers: token skips trailing whitespace"):
    captured("""
      token(Parser.string("abc")).parse("abc   def") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "abc|def"

  // ── JSON parser acceptance test ───────────────────────────────────────
  // Exercises: all helpers + recursive parser via flatMap/PFlatMapped.
  // jValue() uses flatMap to defer its body to parse-time, breaking the
  // construction-time mutual recursion with jArr/jObj.

  test("helpers: JSON parses {\"a\":1,\"b\":[2,3]}"):
    captured("""
      case class JNull()
      case class JBool(v: Any)
      case class JNum(v: Any)
      case class JArr(items: Any)
      case class JObj(fields: Any)

      val ws = whitespace

      val jNull  = keyword("null").map(_ => JNull())
      val jTrue  = keyword("true").map(_ => JBool(true))
      val jFalse = keyword("false").map(_ => JBool(false))
      val jNum   = number.map(n => JNum(n))
      val jStr   = stringLit

      def jValue(): Parser[Any] =
        Parser.succeed(()).flatMap(_ =>
          jNull | jTrue | jFalse | jNum | jStr | jArr() | jObj()
        )

      def jArr(): Parser[Any] =
        (ws ~> Parser.char('[') ~> ws ~>
          (jValue() ~ (ws ~> Parser.char(',') ~> ws ~> jValue()).many()).opt() <~
        ws <~ Parser.char(']')).map(opt =>
          opt match
            case None => JArr(Nil)
            case Some(pair) =>
              JArr(pair._2.foldLeft(List(pair._1))((acc, v) => acc :+ v))
        )

      def jObj(): Parser[Any] =
        val field = jStr ~ (ws ~> Parser.char(':') ~> ws ~> jValue())
        (ws ~> Parser.char('{') ~> ws ~>
          (field ~ (ws ~> Parser.char(',') ~> ws ~> field).many()).opt() <~
        ws <~ Parser.char('}')).map(opt =>
          opt match
            case None => JObj(Nil)
            case Some(pair) =>
              JObj(pair._2.foldLeft(List(pair._1))((acc, f) => acc :+ f))
        )

      jObj().parse("{\"a\":1,\"b\":[2,3]}") match
        case ParseOk(v, _, _) =>
          v match
            case JObj(fields) =>
              val f0  = fields.head
              val f1  = fields.tail.head
              val arr = f1._2 match
                case JArr(items) => items
                case _           => Nil
              println(f0._1 + ":" + f0._2.v + "," + f1._1 + ":[" + arr.length + "]")
            case _ => println("not-obj")
        case ParseErr(e) => println("err:" + e.message)
    """) shouldBe "a:1,b:[2]"
