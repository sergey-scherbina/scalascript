package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

class QuotedMacroPreprocessorTest extends AnyFunSuite:

  test("preprocessQuotedMacros rewrites macro splice with quoted arg"):
    val src = "inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }"
    val out = Parser.preprocessQuotedMacros(src)
    assert(out.contains("""__ssc_macro__( plusOneImpl(__ssc_quote__("x", x)) )"""), out)

  test("preprocessQuotedMacros rewrites quoted expression splices"):
    val src = "def impl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }"
    val out = Parser.preprocessQuotedMacros(src)
    assert(out.contains("""__ssc_quote_expr__( __ssc_splice__("x", x) + 1 )"""), out)

  test("parseScalaWithDiagnostic accepts restricted quoted macro surface"):
    val src =
      """inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
        |def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }""".stripMargin
    val (_, err) = Parser.parseScalaWithDiagnostic(src)
    assert(err.isEmpty, s"quoted macro source should parse after preprocessing: $err")
