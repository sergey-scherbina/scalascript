package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Scala 3's fewer-braces argument: `e: <arg>` means `e { <arg> }`.
  *
  * `:` is the most overloaded token in the language, so the interesting half of this spec is not
  * that the new forms parse — it is that ASCRIPTION still does. A change here that went too far
  * would turn `val x: Int = 1` into a block argument, and every one of those parses below is the
  * control that would catch it.
  */
final class SpikeFewerBracesSpec extends AnyFunSuite:
  private val src = SourceId("memory:fewer-braces")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def clean(text: String): ParseResult =
    val r = parse(text)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")
    r

  private def kinds(n: UniNode): Vector[String] = n match
    case b: UniNode.Branch => Vector(b.kind) ++ b.edges.flatMap(e => kinds(e.child))
    case _                 => Vector.empty

  test("a lambda header on the colon's own line") {
    val r = clean("def f(xs: List[Int]): Unit =\n  xs.foreach: x =>\n    println(x)")
    assert(r.roots.flatMap(kinds).contains("spike.blockapp"), "must attach as a block argument")
    clean("def f(xs: List[(Int, Int)]): Unit =\n  xs.foreach: (a, b) =>\n    println(a + b)")
  }

  test("case arms on the following line — a partial function without braces") {
    val r = clean("def f(x: Int, s: String): Int =\n  handle(x, s):\n    case 1 => 1\n    case _ => 2")
    assert(r.roots.flatMap(kinds).contains("spike.pfblock"), "case arms must become a partial-function block")
  }

  test("a plain indented block, no lambda header") {
    clean("def f(): Unit =\n  runIt():\n    val a = 1\n    println(a)")
  }

  test("ASCRIPTION still parses — the control, and the reason for the three guards") {
    // A bare name can never reach the fewer-braces branch: the receiver must be a call or a
    // selection. These are the shapes that would break if that guard were dropped.
    clean("def f(): Int =\n  val x: Int = 1\n  x")
    clean("def f(): List[Int] =\n  val xs: List[Int] = List(1, 2)\n  xs")
    clean("def f(v: Int): Int =\n  v match\n    case n: Int => n")
  }

  test("a call with an ascription is a SEPARATE gap, and this pins which side it is on") {
    // `val n = compute(1): Int` does not parse, and did not before fewer-braces either: the guard
    // declines (no `=>` on the line), so the colon is left for the statement parser, which is
    // exactly the previous behaviour. Written as an assertion that it FAILS rather than left out,
    // so that closing the real gap makes this test fail and someone reads this comment.
    val r = parse("def f(): Int =\n  val n = compute(1): Int\n  n")
    assert(r.diagnostics.map(_.message).contains("expected statement, found ':'"),
      "ascription on a call now parses — good; delete this test and note the gap closed")
    assert(!r.roots.flatMap(kinds).contains("spike.blockapp"),
      "an ascription must never become a block argument — the guard has stopped working")
  }

  test("a colon that opens nothing is left alone") {
    // No `=>` on the line and nothing indented after it: the branch must decline rather than
    // consume the rest of the file.
    val r = parse("def f(): Int =\n  val m: Map[String, Int] = Map()\n  m.size")
    assert(r.diagnostics.isEmpty, s"${r.diagnostics.map(_.message)}")
    assert(!r.roots.flatMap(kinds).contains("spike.blockapp"))
  }
