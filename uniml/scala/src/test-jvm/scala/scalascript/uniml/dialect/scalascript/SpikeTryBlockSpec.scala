package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** `try` with an INDENTED BLOCK body, the Scala 3 brace-free form.
  *
  * `catch` was already handled, braceless arms included — the body was not. Taking a single
  * expression kept only the first statement, so the second and the `catch` fell out to the
  * enclosing block, which met `case` at statement level and asked for `case class`. The
  * diagnostic therefore named `case`, three lines below the construct that actually broke.
  */
final class SpikeTryBlockSpec extends AnyFunSuite:
  private val src = SourceId("memory:try")

  private def clean(text: String): Unit =
    val r = UniML.parse(SourceInput.fromString(src, text), SpikeDialect)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("an indented try body with braceless catch arms") {
    clean("def f(): Unit =\n  try\n    val d = q()\n    println(d)\n  catch\n    case e: RuntimeException =>\n      println(e)\n")
    clean("def f(): Unit =\n  try\n    g()\n  catch\n    case e: Exception =>\n      println(e)\n")
  }

  test("try with finally, and both") {
    clean("def f(): Unit =\n  try\n    g()\n    h()\n  finally\n    close()\n")
    clean("def f(): Unit =\n  try\n    g()\n  catch\n    case e: Exception =>\n      println(e)\n  finally\n    close()\n")
  }

  test("the single-expression and braced forms still parse — a body shape was added, not swapped") {
    clean("def f(): Int =\n  try g() catch { case e: Exception => 0 }\n")
    clean("def f(): Int =\n  try { g() } catch { case e: Exception => 0 }\n")
    clean("def f(): Int =\n  try g() finally close()\n")
  }
