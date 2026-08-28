package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite

class QuotedMacroPreprocessorTest extends AnyFunSuite:

  test("preprocessQuotedMacros rewrites macro splice with quoted arg"):
    val src = "inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }"
    val out = Parser.preprocessQuotedMacros(src)
    assert(out.contains("""__ssc_macro__( plusOneImpl(__ssc_quote__("x", x)) )"""), out)

  test("preprocessQuotedMacros diagnoses macro splice without quoted args"):
    val src = "inline def plusOne(x: Int): Int = ${ plusOneImpl(x) }"
    val out = Parser.preprocessQuotedMacros(src)
    assert(out.contains("__ssc_macro_error__("), out)
    assert(out.contains("quoted arguments"), out)

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

  // `uniml/markup/PureMarkupCodec.scala` (a real corpus file) has `.append('"')` — an ordinary
  // char literal spelling out the double-quote character — later in the SAME file as a plain
  // `${…}` STRING INTERPOLATION (unrelated to this pass's own quote/splice macro syntax, but this
  // pass still scans past it since its trigger is merely "the file contains the substring `${`").
  // The scanning loop had no case for a bare `'` at all: it fell to the `case c => …` fallback,
  // copying the OPENING `'` as an ordinary character and leaving the NEXT char — the `"` inside
  // the literal — to be examined on its own, where `case '"' => skipString(i)` misread it as the
  // START of a real string and scanned forward for the next unrelated `"` in the source, silently
  // swallowing everything between as "string content". The corruption doesn't fail where it
  // happens; scalameta only reports a mismatch once the (now wrongly nested) parens genuinely
  // don't balance, which can be lines away — this is the shape that produced `` `)` expected but
  // `macro` found `` deep inside `uniml/xml`'s own Rust-backend source, with no `${`/`'{` anywhere
  // near the reported line.
  test("preprocessQuotedMacros does not mistake a char literal's own quote for a string"):
    val src = """sb.append('"').append(s"value ${x}")"""
    val out = Parser.preprocessQuotedMacros(src)
    assert(out == src, s"a bare char literal must pass through untouched: $out")

  test("parseScalaWithDiagnostic accepts a char-literal double-quote beside string interpolation"):
    val src =
      """def f(sb: StringBuilder, x: Int): StringBuilder =
        |  sb.append('"').append(s"value ${x} done").append('"')""".stripMargin
    val (_, err) = Parser.parseScalaWithDiagnostic(src)
    assert(err.isEmpty, s"should parse: $err")
