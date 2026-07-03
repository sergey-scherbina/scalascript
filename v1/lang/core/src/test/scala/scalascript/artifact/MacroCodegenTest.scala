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

  test("codegenWarnings flags an interpreter-only macro (non-expandable impl)"):
    val m = parse(
      """inline def lbl(x: Int): String = ${ lblImpl('x) }
        |def lblImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  "literal: " + x.asValue.getOrElse("?")
        |println(lbl(7))""".stripMargin)
    val warns = MacroCodegen.codegenWarnings(m)
    warns.size shouldBe 1
    warns.head.isWarning shouldBe true
    warns.head.msg should include ("lbl")
    warns.head.msg should include ("interpreter-only")

  test("codegenWarnings is empty for an expandable (asValue match) macro"):
    val m = parse(
      """inline def lbl(x: Int): String = ${ lblImpl('x) }
        |def lblImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  x.asValue match
        |    case Some(n) => Expr("literal: " + n.toString)
        |    case None    => '{ "dynamic: " + $x.toString }
        |println(lbl(7))""".stripMargin)
    MacroCodegen.codegenWarnings(m) shouldBe empty

  test("codegenWarnings is empty for a module with no macros"):
    MacroCodegen.codegenWarnings(parse("val x = 1\nprintln(x)")) shouldBe empty

  // ── arch-meta-v2 C2 (conservative) — post-expansion re-typecheck ─────────────

  test("expansionTypeWarnings flags a macro whose expansion references an undefined name"):
    // The dynamic (None) branch expands to a bare `gone`; with a non-literal arg the
    // call selects None, so `val r = lb(y)` expands to `val r = gone` — a val-rhs
    // reference the strict Typer flags. The source itself type-checks; the expansion
    // does not. (Reach note: the strict undefined-name check is position-sensitive —
    // val-rhs / bare-statement — so this rides exactly the names the Typer flags.)
    val m = parse(
      """inline def lb(x: Int): String = ${ lbImpl('x) }
        |def lbImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  x.asValue match
        |    case Some(n) => Expr("ok")
        |    case None    => '{ gone }
        |val y = 3
        |val r = lb(y)
        |println(r)""".stripMargin)
    val warns = MacroCodegen.expansionTypeWarnings(m)
    withClue(s"warnings: ${warns.map(_.msg)}\n") {
      warns.size shouldBe 1
      warns.head.isWarning shouldBe true
      warns.head.msg should include ("gone")
      warns.head.msg should include ("post-expansion")
    }

  test("expansionTypeWarnings — NO false positive on a valid const-fold (asValue) macro"):
    val m = parse(
      """inline def label(x: Int): String = ${ labelImpl('x) }
        |def labelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  x.asValue match
        |    case Some(n) => Expr("literal: " + n.toString)
        |    case None    => '{ "dynamic: " + $x.toString }
        |println(label(7))""".stripMargin)
    MacroCodegen.expansionTypeWarnings(m) shouldBe empty

  test("expansionTypeWarnings — NO false positive on a valid direct-quote macro"):
    val m = parse(
      """inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
        |def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }
        |println(plusOne(41))""".stripMargin)
    MacroCodegen.expansionTypeWarnings(m) shouldBe empty

  test("expansionTypeWarnings — NO false positive on an interpreter-only macro (stripped name)"):
    // `lbl`/`lblImpl` are interpreter-only; the stripped entrypoint/impl names must not
    // be reported as 'undefined' — that's codegenWarnings' concern, not a broken expansion.
    val m = parse(
      """inline def lbl(x: Int): String = ${ lblImpl('x) }
        |def lblImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  "literal: " + x.asValue.getOrElse("?")
        |println(lbl(7))""".stripMargin)
    MacroCodegen.expansionTypeWarnings(m) shouldBe empty

  test("expansionTypeWarnings is empty (free no-op) for a module with no macros"):
    MacroCodegen.expansionTypeWarnings(parse("val x = 1\nprintln(x + 2)")) shouldBe empty
