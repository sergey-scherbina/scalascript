package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** An indented block inside parentheses ends at the closing paren.
  *
  * `f(x =>` + an indented body is common, and its last line ends with the `)` that closes the CALL.
  * A block bounded only by COLUMN tries to start a statement there. `parseBlock` has always had a
  * `stopAtParen` flag for exactly this; a lambda body passed it and an `if`/`then` branch did not,
  * so one level of nesting was enough to lose it — `scripts/smoke-ci.ssc`, a script that runs in CI
  * and therefore parses fine for the reference front, spent four diagnostics on it.
  */
final class SpikeParenBlockSpec extends AnyFunSuite:
  private val src = SourceId("memory:paren-block")

  private def clean(text: String): Unit =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("a branch block inside a parenthesised lambda") {
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      println(x))\n")
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      println(\"a\" +\n              \"b\"))\n")
    // else branch too, and two levels of nesting
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      println(x)\n    else\n      println(0))\n")
    clean("def f(): Unit =\n  xs.foreach(x =>\n    if x > 0 then\n      if x > 1 then\n        println(x))\n")
  }

  test("the shapes that already worked still do — this added a stop, it did not move one") {
    clean("def f(): Unit =\n  xs.foreach(x =>\n    println(x))\n")
    clean("def f(): Unit =\n  xs.foreach(x =>\n    val y = x\n    println(y))\n")
    clean("def f(): Unit =\n  xs.foreach { x =>\n    if x > 0 then\n      println(x)\n  }\n")
  }

  test("a branch block NOT inside parens still runs to its dedent") {
    // The control. `stopAtParen` is conditioned on parenDepth, so a top-level branch must be
    // unaffected — if this ever fails, the stop is firing where no paren is open and every
    // brace-free block just got shorter.
    clean("def f(x: Int): Int =\n  if x > 0 then\n    val a = 1\n    a\n  else\n    0\n")
    clean("def f(x: Int): Unit =\n  if x > 0 then\n    println(1)\n  println(2)\n")
  }
