package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Infix operators in TYPE position: `A throws E`, `A | B`, `A & B`.
  *
  * All three appear in `v1/runtime/std/error-handling.ssc`, which is standard library — it parses
  * for the reference front by definition, so a diagnostic there was never anything but a gap on
  * this side. Rejecting the type took the `=` and the whole body with it, which is why one
  * unhandled operator cost 14 diagnostics in one file.
  *
  * `throws` is NOT a keyword here — it lexes as a plain identifier — so the rule has to match the
  * lexeme. That is also why the expression cases below matter: `|` and `&` in EXPRESSION position
  * are the ordinary operators, and nothing about this may reach them.
  */
final class SpikeTypeOperatorSpec extends AnyFunSuite:
  private val src = SourceId("memory:type-op")

  private def clean(text: String): Unit =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("throws in return and parameter position") {
    clean("def raise[A, E](e: E): A throws E = Left(e)\n")
    clean("def rethrow[A, E](r: A throws E): A =\n  r\n")
    clean("def parseInt(s: String): Int throws NumberFormatException =\n  1\n")
    clean("def fail[E](e: E): Nothing throws E = Left(e)\n")
  }

  test("union and intersection types") {
    clean("def unbox[A, E](boxed: A throws E): A | E =\n  boxed\n")
    clean("def f(x: Int | String): Int = 1\n")
    clean("def g(x: Readable & Closeable): Int = 1\n")
    clean("def h(): Int | String = 1\n")
  }

  test("the same spellings in EXPRESSION position are still operators") {
    // The control. `|` and `&` are bitwise/boolean operators, and a type rule that leaked into
    // expressions would silently swallow the right operand of every one of them.
    clean("def f(a: Int, b: Int): Int = a | b\n")
    clean("def f(a: Int, b: Int): Int = a & b\n")
    clean("def f(a: Boolean, b: Boolean): Boolean = a && b\n")
    clean("def f(a: Int, b: Int): Int =\n  val c = a | b\n  c & a\n")
  }
