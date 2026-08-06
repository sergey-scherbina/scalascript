package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Operators lex by MAXIMAL MUNCH, and an operator the table does not name is still an operator.
  *
  * The lexer used a hand-written two-character table, so `<~>` split into `<~` and `>`, `~~` into
  * `~` and `~`, and `++=` into `++` and `=`. A comment above it claimed the reference front does
  * the same and that the corpus wanted it reproduced bug-for-bug — measured false: the interpreter
  * prints 304 for `extension (a: Int) def <~>(b: Int) = a * 100 + b` and
  * `js-symbolic-infix-operator` passes on int.
  *
  * The half that is easy to miss: precedence 0 did NOT mean "unknown operator". For `=>` and `<-`
  * it meant "deliberately not infix", and a first-character fallback that ignored that gave `=>`
  * precedence 5 and broke every lambda — 12 diagnostics became 54. Those tests are below, and they
  * are the ones worth keeping.
  */
final class SpikeOperatorMunchSpec extends AnyFunSuite:
  private val src = SourceId("memory:munch")

  private def clean(text: String): ParseResult =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
    r

  private def lexemes(text: String): Vector[String] =
    def go(n: UniNode): Vector[String] = n match
      case b: UniNode.Branch => b.edges.flatMap(e => go(e.child))
      case UniNode.Token(t)  => if t.kind == "spike.ws" then Vector.empty else Vector(t.lexeme)
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect).roots.flatMap(go)

  test("a symbolic operator lexes as ONE token, however long") {
    assert(lexemes("def f(): Int = 3 <~> 4\n").contains("<~>"))
    assert(lexemes("def f(): Int = 3 ~~ 4\n").contains("~~"))
    assert(lexemes("def f(): Int = a ++= b\n").contains("++="))
    assert(lexemes("def f(): Int = a -- b\n").contains("--"))
  }

  test("a user-defined operator is infix, as a def name and at the call site") {
    clean("extension (a: Int)\n  def <~>(b: Int): Int = a * 100 + b\ndef f(): Int = 3 <~> 4\n")
    clean("extension (a: Int)\n  def ~~(b: Int): String = s\"$a~$b\"\ndef f(): String = 5 ~~ 6\n")
    clean("def f(x: Set[Int], y: Set[Int]): Set[Int] = x -- y\n")
  }

  test("THE ARROWS ARE NOT OPERATORS — the control that cost 42 diagnostics to learn") {
    // `=>` and `<-` reach the precedence table and must stay at 0. If they ever pick up a
    // precedence, they become infix operators and every lambda and every for-generator changes
    // meaning while still parsing — which is why this asserts on shapes, not on absence of errors.
    clean("def f(xs: List[Int]): List[Int] = xs.map(x => x + 1)\n")
    clean("def f(xs: List[Int]): Unit =\n  for x <- xs do println(x)\n")
    clean("def f(v: Int): Int =\n  v match\n    case n => n + 1\n")
    clean("def f(g: Int => Int): Int = g(1)\n")
    val r = clean("def f(xs: List[Int]): List[Int] = xs.map(x => x + 1)\n")
    def kinds(n: UniNode): Vector[String] = n match
      case b: UniNode.Branch => Vector(b.kind) ++ b.edges.flatMap(e => kinds(e.child))
      case _                 => Vector.empty
    assert(r.roots.flatMap(kinds).contains("spike.lambda"), "the lambda must still be a lambda, not an infix application")
  }

  test("the two lex-time REWRITES survive the wider munch") {
    // `:::` means `++` and `+:` means `::` — statements about meaning, not munching, and a maximal
    // run must not lose them. The lexeme stays the SOURCE slice either way, which round-trip needs.
    clean("def f(a: List[Int], b: List[Int]): List[Int] = a ::: b\n")
    clean("def f(x: Int, xs: List[Int]): List[Int] = x +: xs\n")
    assert(lexemes("def f(a: List[Int], b: List[Int]): List[Int] = a ::: b\n").contains(":::"),
      "the LEXEME must stay the source slice — the rewrite is about meaning")
  }
