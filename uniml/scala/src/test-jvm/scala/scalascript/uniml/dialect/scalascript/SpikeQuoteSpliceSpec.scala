package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Scala 3 quote and splice — `'x`, `'{ … }`, `${ … }`, `$x`.
  *
  * The dialect parses the language; it does not run the macro, so all three erase to their
  * contents. They must PARSE because the reference front runs
  * `examples/quoted-macro-constfold.ssc` and prints `literal: 7`.
  *
  * The real defect was in the LEXER and it was not about macros at all: every `'` was assumed to
  * open a char literal of a fixed width, so `'x)` was lexed as the "char" `'x)` and `'{ ` as `'{ `,
  * both `spike.int`. A char literal is now only a char literal when the quote CLOSES — which is
  * why the char cases below are as much the point as the quote ones.
  */
final class SpikeQuoteSpliceSpec extends AnyFunSuite:
  private val src = SourceId("memory:quote")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def clean(text: String): ParseResult =
    val r = parse(text)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
    r

  private def kinds(n: UniNode): Vector[String] = n match
    case b: UniNode.Branch => Vector(b.kind) ++ b.edges.flatMap(e => kinds(e.child))
    case _                 => Vector.empty

  test("quote and splice parse") {
    assert(clean("def f(): Int =\n  ${ g('x) }\n").roots.flatMap(kinds).contains("spike.splice"))
    assert(clean("def f(): Int =\n  '{ 1 + 2 }\n").roots.flatMap(kinds).contains("spike.quote"))
    clean("def f(): Int =\n  '{ \"a\" + $x.toString }\n")
    clean("def f(x: Int): Int =\n  $x\n")
  }

  test("CHAR LITERALS still lex as chars — the half that was actually broken") {
    // Each of these must stay a char, and `'x)` must NOT: the fixed-width rule could not tell them
    // apart, and taking three characters blindly is what produced `spike.int` lexemes like `'x)`.
    def lexemes(text: String): Vector[(String, String)] =
      def go(n: UniNode): Vector[(String, String)] = n match
        case b: UniNode.Branch => b.edges.flatMap(e => go(e.child))
        case UniNode.Token(t)  => Vector(t.kind -> t.lexeme)
      parse(text).roots.flatMap(go).filter(_._1 != "spike.ws")
    assert(lexemes("val c = 'a'\n").exists((k, l) => k == "spike.int" && l == "'a'"))
    assert(lexemes("val c = '\\n'\n").exists((k, l) => k == "spike.int" && l == "'\\n'"))
    clean("def f(c: Char): Boolean =\n  c == 'a'\n")
    clean("def f(c: Char): Boolean =\n  c == '\\n'\n")
    // the shape that used to swallow a paren
    assert(lexemes("def f(): Int =\n  g('x)\n").forall((_, l) => l != "'x)"),
      "a quote followed by a name must not swallow the paren as a char literal")
  }

  test("losslessness holds for every one of them") {
    // The lexer change moves characters between tokens, which is exactly where round-trip breaks.
    def text(n: UniNode): String = n match
      case b: UniNode.Branch => b.edges.map(e => text(e.child)).mkString
      case UniNode.Token(t)  => t.lexeme
    Vector("def f(): Int =\n  ${ g('x) }\n", "def f(): Int =\n  '{ 1 + $x }\n", "val c = 'a'\n").foreach { s =>
      assert(parse(s).roots.map(text).mkString == s, s"round-trip lost: $s")
    }
  }
