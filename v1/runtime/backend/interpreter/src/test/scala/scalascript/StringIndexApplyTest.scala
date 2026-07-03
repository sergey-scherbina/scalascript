package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** specs/string-index-apply.md — `s(i)` must return the i-th char (Scala
 *  `s.apply(i): Char`, i.e. charAt), matching List/Map/Set apply.  Reported by
 *  busi: their MT940 parser used `afterDate(4)` on a `:61:` line and crashed with
 *  a misleading "Not callable" because the interpreter treated the String as a
 *  function. */
class StringIndexApplyTest extends AnyFunSuite:

  private def runProgram(body: String): String =
    val src =
      s"""# T
         |
         |```scalascript
         |$body
         |```
         |""".stripMargin
    val baos = new java.io.ByteArrayOutputStream()
    val ps   = new java.io.PrintStream(baos, true, "UTF-8")
    interpreter.Interpreter(ps).run(Parser.parse(src))
    baos.toString("UTF-8").stripLineEnd

  test("s(i) returns the i-th char"):
    assert(runProgram("""println("abc"(0))""") == "a")
    assert(runProgram("""println("abc"(1))""") == "b")
    assert(runProgram("""println("abc"(2))""") == "c")

  test("s(i) on a binding — the busi MT940 shape"):
    assert(runProgram(
      """val afterDate = "C1234.50"
        |println(afterDate(0))""".stripMargin) == "C")

  test("s(i) inside a boolean expression"):
    assert(runProgram(
      """val s = "0515C1234"
        |println(s(4) == 'C' && s(0) == '0')""".stripMargin) == "true")

  test("out-of-range index raises a clear index error, not 'Not callable'"):
    val err = intercept[Throwable](runProgram("""println("abc"(9))"""))
    assert(err.getMessage.contains("index out of range"),
      s"expected an index error, got: ${err.getMessage}")
    assert(!err.getMessage.contains("Not callable"),
      s"still the misleading message: ${err.getMessage}")

  test("List / Map apply unaffected (regression guard)"):
    assert(runProgram("""println(List(10, 20, 30)(1))""") == "20")
    assert(runProgram("""println(Map("k" -> "v")("k"))""") == "v")
