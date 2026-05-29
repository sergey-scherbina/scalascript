package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scala.meta._

class AstDumpTest extends AnyFunSuite:
  test("dump spanMerge AST") {
    val code = """
def spanMerge(a: Span, b: Span): Span =
  val s = if a.start < b.start then a.start else b.start
  val e = if a.end > b.end then a.end else b.end
  Span(s, e)
"""
    val parsed = dialects.Scala3(code).parse[Source].get
    println(parsed.structure)
  }
