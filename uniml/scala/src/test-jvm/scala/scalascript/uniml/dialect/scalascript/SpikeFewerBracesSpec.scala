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

  test("a bare name is a valid receiver — the reference front accepts it") {
    // `apiClients:` + indented block. Refused here until the receiver guard came off; v1 parses it
    // and fails at RUNTIME with "Undefined: apiClients", which is a type error, not a syntax one.
    val r = clean("def f(): Unit =\n  apiClients:\n    println(1)\n")
    assert(r.roots.flatMap(kinds).contains("spike.blockapp"))
    // A lambda header after a bare name too.
    clean("def f(): Unit =\n  each: x =>\n    println(x)\n")
  }

  test("ASCRIPTION still parses — the control, and the only thing holding the line") {
    // With the receiver guard gone, `colonOpensBlockArg` is what keeps every one of these an
    // ascription: none has an indented block after the colon, and none has a `=>` on its line.
    clean("def f(): Int =\n  val x: Int = 1\n  x")
    clean("def f(): List[Int] =\n  val xs: List[Int] = List(1, 2)\n  xs")
    clean("def f(v: Int): Int =\n  v match\n    case n: Int => n")
  }

  test("ascription in EXPRESSION position — the gap this spec used to pin as open") {
    // Until 2026-08-05 this failed and the spec asserted that it failed, with a note telling
    // whoever closed the gap to come here. That is what happened; the note is kept because the
    // mechanism is the point — a control that quietly starts testing a different defect is worse
    // than no control, so it was written to break loudly when the world changed.
    clean("def f(): Int =\n  val n = compute(1): Int\n  n")
    clean("def f(): Int =\n  compute(1): Int\n")
    clean("def f(): List[Int] =\n  xs.map(x => x): List[Int]\n")
    // and it must still not become a block argument
    val r = parse("def f(): Int =\n  val n = compute(1): Int\n  n")
    assert(!r.roots.flatMap(kinds).contains("spike.blockapp"),
      "an ascription must never become a block argument — fewer-braces declines first, ascription takes it")
  }

  test("a colon that opens nothing is left alone") {
    // No `=>` on the line and nothing indented after it: the branch must decline rather than
    // consume the rest of the file.
    val r = parse("def f(): Int =\n  val m: Map[String, Int] = Map()\n  m.size")
    assert(r.diagnostics.isEmpty, s"${r.diagnostics.map(_.message)}")
    assert(!r.roots.flatMap(kinds).contains("spike.blockapp"))
  }
