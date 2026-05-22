package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.20 Phase 3 — std/parsing/combinators.ssc: combinator ADT nodes + recursive-descent parse. */
class ParsingCombinatorsTest extends AnyFunSuite with Matchers:

  private val repoRoot = TestPaths.repoRoot

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Position, ParseError, ParseOk, ParseErr, Parser, PChar, PString, PRegex, PSatisfy, PSucceed, PFail](std/parsing/core.ssc)
         |
         |[PSequence, PChoice, PMany, POpt, PMapped, PFlatMapped, PNamed, runParser](std/parsing/combinators.ssc)
         |
         |```scalascript
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Primitive parsers execute correctly ──────────────────────────────

  test("combinators: PChar matches single character"):
    captured("""
      val p = Parser.char('a')
      p.parse("abc") match
        case ParseOk(v, rest, pos) => println("" + v + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("err:" + e.message)
    """) shouldBe "a|bc|1"

  test("combinators: PChar fails on wrong character"):
    captured("""
      val p = Parser.char('z')
      p.parse("abc") match
        case ParseErr(e) => println("fail:" + e.pos.offset)
        case _           => println("ok")
    """) shouldBe "fail:0"

  test("combinators: PString matches literal"):
    captured("""
      val p = Parser.string("hello")
      p.parse("hello world") match
        case ParseOk(v, rest, pos) => println(v + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("err")
    """) shouldBe "hello| world|5"

  test("combinators: PRegex matches prefix"):
    captured("""
      val p = Parser.regex("[0-9]+")
      p.parse("123abc") match
        case ParseOk(v, rest, pos) => println(v + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("err")
    """) shouldBe "123|abc|3"

  test("combinators: PSatisfy matches on predicate"):
    captured("""
      val p = Parser.satisfy(c => c == 'a' || c == 'b')
      p.parse("bat") match
        case ParseOk(v, rest, _) => println("ok:" + v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "ok:b|at"

  // ── Combinator ADT node construction ─────────────────────────────────

  test("combinators: PSequence node is built by ~"):
    captured("""
      val p = Parser.char('a') ~ Parser.char('b')
      p match
        case PSequence(l, r) => println("seq:ok")
        case _               => println("other")
    """) shouldBe "seq:ok"

  test("combinators: PChoice node is built by |"):
    captured("""
      val p = Parser.char('a') | Parser.char('b')
      p match
        case PChoice(l, r) => println("choice:ok")
        case _             => println("other")
    """) shouldBe "choice:ok"

  test("combinators: PMapped node is built by map"):
    captured("""
      val p = Parser.string("42").map(s => s.toInt)
      p match
        case PMapped(_, _) => println("mapped:ok")
        case _             => println("other")
    """) shouldBe "mapped:ok"

  test("combinators: PMany node is built by many"):
    captured("""
      val p = Parser.char('a').many()
      p match
        case PMany(_, 0) => println("many:ok")
        case _           => println("other")
    """) shouldBe "many:ok"

  test("combinators: PMany node is built by many1"):
    captured("""
      val p = Parser.char('a').many1()
      p match
        case PMany(_, 1) => println("many1:ok")
        case _           => println("other")
    """) shouldBe "many1:ok"

  test("combinators: POpt node is built by opt"):
    captured("""
      val p = Parser.char('a').opt()
      p match
        case POpt(_) => println("opt:ok")
        case _       => println("other")
    """) shouldBe "opt:ok"

  test("combinators: PNamed node is built by named"):
    captured("""
      val p = Parser.char('a').named("letter-a")
      p match
        case PNamed(_, n) => println("named:" + n)
        case _            => println("other")
    """) shouldBe "named:letter-a"

  // ── Combinator execution ──────────────────────────────────────────────

  test("combinators: ~ sequences two parsers"):
    captured("""
      val p = Parser.char('a') ~ Parser.char('b')
      p.parse("abc") match
        case ParseOk(v, rest, pos) => println("" + v._1 + "" + v._2 + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("err:" + e.message)
    """) shouldBe "ab|c|2"

  test("combinators: | picks first match"):
    captured("""
      val p = Parser.string("foo") | Parser.string("bar")
      p.parse("fooX") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "foo|X"

  test("combinators: | falls back to second on failure"):
    captured("""
      val p = Parser.string("foo") | Parser.string("bar")
      p.parse("barX") match
        case ParseOk(v, rest, _) => println(v + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "bar|X"

  test("combinators: map transforms the result"):
    captured("""
      val p = Parser.regex("[0-9]+").map(s => s.toInt * 2)
      p.parse("21") match
        case ParseOk(v, _, _) => println(v)
        case ParseErr(_)      => println("err")
    """) shouldBe "42"

  test("combinators: ~> keeps right side"):
    captured("""
      val p = Parser.string("foo") ~> Parser.char('!')
      p.parse("foo!") match
        case ParseOk(v, _, _) => println(v)
        case ParseErr(_)      => println("err")
    """) shouldBe "!"

  test("combinators: <~ keeps left side"):
    captured("""
      val p = Parser.string("foo") <~ Parser.char('!')
      p.parse("foo!") match
        case ParseOk(v, _, _) => println(v)
        case ParseErr(_)      => println("err")
    """) shouldBe "foo"

  test("combinators: many collects zero matches"):
    captured("""
      val p = Parser.char('x').many()
      p.parse("abc") match
        case ParseOk(v, rest, _) => println("" + v.length + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "0|abc"

  test("combinators: many collects multiple matches"):
    captured("""
      val p = Parser.char('a').many()
      p.parse("aaabcd") match
        case ParseOk(v, rest, _) => println("" + v.length + "|" + rest)
        case ParseErr(_)         => println("err")
    """) shouldBe "3|bcd"

  test("combinators: many1 fails on zero matches"):
    captured("""
      val p = Parser.char('x').many1()
      p.parse("abc") match
        case ParseOk(_, _, _) => println("ok")
        case ParseErr(e)      => println("fail")
    """) shouldBe "fail"

  test("combinators: opt returns Some on match"):
    captured("""
      val p = Parser.char('a').opt()
      p.parse("abc") match
        case ParseOk(v, rest, _) =>
          v match
            case Some(c) => println("some:" + c + "|" + rest)
            case None    => println("none")
        case ParseErr(_) => println("err")
    """) shouldBe "some:a|bc"

  test("combinators: opt returns None on no match"):
    captured("""
      val p = Parser.char('z').opt()
      p.parse("abc") match
        case ParseOk(v, rest, _) =>
          v match
            case Some(_) => println("some")
            case None    => println("none:" + rest)
        case ParseErr(_) => println("err")
    """) shouldBe "none:abc"

  test("combinators: named augments error message"):
    captured("""
      val p = Parser.char('z').named("z-char")
      p.parse("abc") match
        case ParseErr(e) => println(e.message)
        case _           => println("ok")
    """) shouldBe "expected z-char"

  test("combinators: flatMap chains parsers"):
    captured("""
      val p = Parser.char('a').flatMap(c =>
        if c == 'a' then Parser.char('b')
        else Parser.fail("expected a first")
      )
      p.parse("ab") match
        case ParseOk(v, _, _) => println("ok:" + v)
        case ParseErr(e)      => println("err:" + e.message)
    """) shouldBe "ok:b"

  // ── Calculator conformance ────────────────────────────────────────────
  // Exercises: PRegex, PMapped, PMany, PSequence, PChoice, ~>, <~, map, foldLeft

  test("combinators: calculator parses 1 + 2 * 3 = 7.0"):
    captured("""
      val ws   = Parser.regex("\\s*")
      val num  = Parser.regex("-?[0-9]+").map(s => s.toDouble)
      val mul  = ws ~> Parser.char('*') ~> ws
      val plus = ws ~> Parser.char('+') ~> ws
      val term = (num ~ (mul ~> num).many()).map(pair => pair._2.foldLeft(pair._1)((a, b) => a * b))
      val expr = (term ~ (plus ~> term).many()).map(pair => pair._2.foldLeft(pair._1)((a, b) => a + b))
      expr.parse("1 + 2 * 3") match
        case ParseOk(v, rest, pos) => println("" + v + "|" + rest + "|" + pos.offset)
        case ParseErr(e)           => println("err:" + e.message)
    """) shouldBe "7||9"

  test("combinators: calculator parses 10 * 2 + 5 = 25.0"):
    captured("""
      val ws   = Parser.regex("\\s*")
      val num  = Parser.regex("-?[0-9]+").map(s => s.toDouble)
      val mul  = ws ~> Parser.char('*') ~> ws
      val plus = ws ~> Parser.char('+') ~> ws
      val term = (num ~ (mul ~> num).many()).map(pair => pair._2.foldLeft(pair._1)((a, b) => a * b))
      val expr = (term ~ (plus ~> term).many()).map(pair => pair._2.foldLeft(pair._1)((a, b) => a + b))
      expr.parse("10 * 2 + 5") match
        case ParseOk(v, _, _) => println(v)
        case ParseErr(e)      => println("err:" + e.message)
    """) shouldBe "25"
