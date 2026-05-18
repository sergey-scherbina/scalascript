package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.20 Phase 5 — std/dsl/ast.ssc, pretty.ssc, builders.ssc + Calc round-trip. */
class DslTest extends AnyFunSuite with Matchers:

  private val repoRoot = os.pwd / os.up

  private def captured(imports: String)(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n$imports\n\n```scalascript\n$code\n```\n"
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private val astImport =
    "[Span, spanEmpty, spanMerge, Node](std/dsl/ast.ssc)"

  private val prettyImport =
    "[DocNil, DocText, DocLine, DocIndent, DocBeside, DocAbove, text, line, empty, indent, renderDoc, render](std/dsl/pretty.ssc)"

  private val buildersImport =
    "[Builder, builder](std/dsl/builders.ssc)"

  private val parseImports =
    "[Position, ParseError, ParseOk, ParseErr, Parser, PChar, PString, PRegex, PSatisfy, PSucceed, PFail](std/parsing/core.ssc)\n\n" +
    "[PSequence, PChoice, PMany, POpt, PMapped, PFlatMapped, PNamed, runParser](std/parsing/combinators.ssc)\n\n" +
    "[whitespace, whitespace1, identifier, integer, number, stringLit, skip, keyword, token](std/parsing/helpers.ssc)"

  // ── ast.ssc ──────────────────────────────────────────────────────────

  test("dsl/ast: Span constructs and merges"):
    captured(astImport)("""
      val s1 = Span(0, 5)
      val s2 = Span(3, 10)
      val m  = spanMerge(s1, s2)
      println("" + m.start + "," + m.end)
    """) shouldBe "0,10"

  test("dsl/ast: Span.empty sentinel"):
    captured(astImport)("""
      val e = spanEmpty
      println("" + e.start + "," + e.end)
    """) shouldBe "0,0"

  test("dsl/ast: Node wraps value with span"):
    captured(astImport)("""
      val n = Node(42, Span(0, 2))
      println("" + n.value + "," + n.span.start + "-" + n.span.end)
    """) shouldBe "42,0-2"

  // ── pretty.ssc ───────────────────────────────────────────────────────

  test("dsl/pretty: text renders literal"):
    captured(prettyImport)("""
      println(render(text("hello")))
    """) shouldBe "hello"

  test("dsl/pretty: beside concatenates"):
    captured(prettyImport)("""
      println(render(text("foo") ++ text("bar")))
    """) shouldBe "foobar"

  test("dsl/pretty: above puts on separate lines"):
    captured(prettyImport)("""
      println(render(text("line1") / text("line2")))
    """) shouldBe "line1\nline2"

  test("dsl/pretty: indent shifts inner doc"):
    captured(prettyImport)("""
      println(render(text("a") / indent(2, text("b"))))
    """) shouldBe "a\nb"

  test("dsl/pretty: line inserts newline at current col"):
    captured(prettyImport)("""
      println(render(text("x") ++ line ++ text("y")))
    """) shouldBe "x\ny"

  test("dsl/pretty: empty renders to empty string"):
    captured(prettyImport)("""
      println(">" + render(empty) + "<")
    """) shouldBe "><"

  // ── builders.ssc ─────────────────────────────────────────────────────

  test("dsl/builders: build with name and value"):
    captured(buildersImport)("""
      val r = builder().withName("key").withValue(99).build()
      r match
        case (n, v, tags) => println(n + "=" + v)
    """) shouldBe "key=99"

  test("dsl/builders: withTag accumulates"):
    captured(buildersImport)("""
      val r = builder().withName("x").withValue(1).withTag("a").withTag("b").build()
      r match
        case (n, v, tags) => println("" + tags.length)
    """) shouldBe "2"

  // ── Calc round-trip acceptance ────────────────────────────────────────
  // parse("1 + 2 * 3") → CalcAdd(CalcNum(1), CalcMul(CalcNum(2), CalcNum(3)))
  // → pretty → "1 + 2 * 3" → parse again → same result

  test("dsl: Calc AST round-trip parse → pretty → parse"):
    captured(s"$parseImports\n\n$prettyImport")("""
      case class CalcNum(v: Any)
      case class CalcAdd(l: Any, r: Any)
      case class CalcMul(l: Any, r: Any)

      val ws   = whitespace
      val num  = Parser.regex("[0-9]+").map(s => CalcNum(s.toInt))
      val mul  = ws ~> Parser.char('*') ~> ws
      val plus = ws ~> Parser.char('+') ~> ws
      val term = (num ~ (mul ~> num).many()).map(pair =>
        pair._2.foldLeft(pair._1)((acc, n) => CalcMul(acc, n))
      )
      val expr = (term ~ (plus ~> term).many()).map(pair =>
        pair._2.foldLeft(pair._1)((acc, t) => CalcAdd(acc, t))
      )

      def prettyCalc(e: Any): Any =
        e match
          case CalcNum(v)    => text("" + v)
          case CalcAdd(l, r) => prettyCalc(l) ++ text(" + ") ++ prettyCalc(r)
          case CalcMul(l, r) => prettyCalc(l) ++ text(" * ") ++ prettyCalc(r)

      expr.parse("1 + 2 * 3") match
        case ParseOk(ast, _, _) =>
          val pretty = render(prettyCalc(ast))
          expr.parse(pretty) match
            case ParseOk(ast2, _, _) =>
              ast2 match
                case CalcAdd(l2, r2) =>
                  l2 match
                    case CalcNum(lv) =>
                      r2 match
                        case CalcMul(ml, mr) =>
                          ml match
                            case CalcNum(mlv) =>
                              mr match
                                case CalcNum(mrv) => println("" + lv + "+" + mlv + "*" + mrv + "|" + pretty)
                                case _ => println("err:mr")
                            case _ => println("err:ml")
                        case _ => println("err:r2")
                    case _ => println("err:l2")
                case _ => println("err:ast2")
            case ParseErr(e2) => println("err2:" + e2.message)
        case ParseErr(e) => println("err:" + e.message)
    """) shouldBe "1+2*3|1 + 2 * 3"
