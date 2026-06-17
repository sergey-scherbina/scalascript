package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.ast.{Module, Section, Content}

/** arch-meta-v2 `macro-codegen-backends` — the pre-codegen macro expand+strip
 *  pass that lets restricted quoted macros reach the generated backends. */
class MacroCodegenTest extends AnyFunSuite with Matchers:

  private def parse(code: String): Module =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def blockSources(m: Module): List[String] =
    def go(s: Section): List[String] =
      s.content.collect { case cb: Content.CodeBlock => cb.source } ++ s.subsections.flatMap(go)
    m.sections.flatMap(go)

  test("expand folds an asValue-match macro call and strips the defs"):
    val m = parse(
      """inline def label(x: Int): String = ${ labelImpl('x) }
        |def labelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  x.asValue match
        |    case Some(n) => Expr("literal: " + n.toString)
        |    case None    => '{ "dynamic: " + $x.toString }
        |println(label(7))""".stripMargin)
    val out = MacroCodegen.expand(m)
    val src = blockSources(out).mkString("\n")
    withClue(s"expanded source:\n$src\n") {
      src should not include "__ssc_macro__"
      src should not include "labelImpl"
      src should not include "asValue"
      src should not include "inline def label"
      src should include ("literal: ")
      src should include ("7")
    }

  test("expand folds a direct-quote macro call and strips the defs"):
    val m = parse(
      """inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
        |def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }
        |println(plusOne(41))""".stripMargin)
    val out = MacroCodegen.expand(m)
    val src = blockSources(out).mkString("\n")
    withClue(s"expanded source:\n$src\n") {
      src should not include "__ssc_macro__"
      src should not include "plusOneImpl"
      src should include ("41")
    }

  test("expand is a no-op for a module with no macros"):
    val m   = parse("val x = 1\nprintln(x + 2)")
    val out = MacroCodegen.expand(m)
    out shouldBe theSameInstanceAs (m)
