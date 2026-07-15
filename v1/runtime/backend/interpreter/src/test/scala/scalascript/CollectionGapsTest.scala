package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression: v1-interpreter completeness gaps found by the v2-vs-v1 differential
 *  (sprint #16) — List.reduce/reduceRight/reduceOption/transpose, String.capitalize,
 *  math.max/min. Each was `No method …` before. */
class CollectionGapsTest extends AnyFunSuite:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("List.reduce / reduceLeft / reduceRight") {
    assert(captured("println(List(1,2,3,4).reduce(_ + _))") == "10")
    assert(captured("println(List(1,2,3).reduceLeft(_ - _))") == "-4")   // ((1-2)-3)
    assert(captured("println(List(1,2,3,4).reduceRight(_ - _))") == "-2") // 1-(2-(3-4))
    assert(captured("println(List(10).reduce(_ + _))") == "10")
    assert(captured("println(Vector(2,4,6).reduce(_ + _))") == "12")
  }

  test("reduceOption / reduceLeftOption") {
    assert(captured("println(List(1,2,3).reduceOption(_ + _))") == "Some(6)")
    assert(captured("println(List[Int]().reduceOption(_ + _))") == "None")
  }

  test("List.transpose") {
    assert(captured("println(List(List(1,2),List(3,4)).transpose)") == "List(List(1, 3), List(2, 4))")
    assert(captured("println(List(List(1,2,3),List(4,5,6)).transpose)")
             == "List(List(1, 4), List(2, 5), List(3, 6))")
  }

  test("String.capitalize") {
    assert(captured("""println("hello world".capitalize)""") == "Hello world")
    assert(captured("""println("".capitalize)""") == "")
    assert(captured("""println("A".capitalize)""") == "A")
  }

  test("math.max / math.min") {
    assert(captured("println(math.max(3, 7))") == "7")
    assert(captured("println(math.min(3, 7))") == "3")
    assert(captured("println(math.max(3.5, 2.1))") == "3.5")
    assert(captured("println(math.min(3.5, 2.1))") == "2.1")
  }
