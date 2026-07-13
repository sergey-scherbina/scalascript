package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression guards for `interp-var-scope-leak-across-calls`.
 *
 *  A callee's `var X` must not clobber a caller's live `var X` of the same name.
 *  Vars are call/block-scoped: a block that DECLARES a var (Defn.Var) saves the
 *  prior globals value on entry and restores it on exit, so nested/recursive
 *  calls each shadow their own copy. */
class VarScopeAcrossCallsTest extends AnyFunSuite:

  private def captured(ssc: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scalascript\n$ssc\n```\n"))
    ps.flush()
    buf.toString.trim

  test("main repro: callee var i must not leak into caller's loop counter"):
    assert(captured(
      """def page(): List[Int] =
        |  var acc: List[Int] = Nil
        |  var i = 0
        |  while i < 512 do
        |    acc = 0 :: acc
        |    i = i + 1
        |  acc
        |def mkpages(n: Int): List[List[Int]] =
        |  var acc: List[List[Int]] = Nil
        |  var i = 0
        |  while i < n do
        |    acc = page() :: acc
        |    i = i + 1
        |  acc
        |println(mkpages(84).length)""".stripMargin) == "84")

  test("recursion with a shadowed var: each level keeps its own counter"):
    assert(captured(
      """def fact(n: Int): Int =
        |  var acc = 1
        |  var k = n
        |  while k > 1 do
        |    acc = acc * k
        |    k = k - 1
        |  acc
        |def sumFact(n: Int): Int =
        |  var total = 0
        |  var k = 1
        |  while k <= n do
        |    total = total + fact(k)
        |    k = k + 1
        |  total
        |println(sumFact(5))""".stripMargin) == "153") // 1+2+6+24+120

  test("closure returned over a function-local var still works after a shadowing call"):
    assert(captured(
      """def makeCounter(): () => Int =
        |  var i = 100
        |  () => i
        |def other(): Int =
        |  var i = 999
        |  var j = 0
        |  while j < 3 do
        |    i = i + 1
        |    j = j + 1
        |  i
        |val c = makeCounter()
        |val o = other()
        |println(c())""".stripMargin) == "100")

  test("plain returned closure over a function-local var (no shadow)"):
    assert(captured(
      """def makeCounter(): () => Int =
        |  var i = 100
        |  () => i
        |val c = makeCounter()
        |println(c())""".stripMargin) == "100")

  test("nested same-name vars in while bodies"):
    assert(captured(
      """def run(): Int =
        |  var total = 0
        |  var i = 0
        |  while i < 3 do
        |    var i2 = 0
        |    var inner = 0
        |    while i2 < 4 do
        |      inner = inner + 1
        |      i2 = i2 + 1
        |    total = total + inner
        |    i = i + 1
        |  total
        |println(run())""".stripMargin) == "12")

  test("mutual recursion each level shadows same-named var"):
    assert(captured(
      """def isEven(n: Int): Boolean =
        |  var x = n
        |  if x == 0 then true else isOdd(x - 1)
        |def isOdd(n: Int): Boolean =
        |  var x = n
        |  if x == 0 then false else isEven(x - 1)
        |println(isEven(10))""".stripMargin) == "true")

  test("module-level top-level var still persists across function calls"):
    assert(captured(
      """var counter = 0
        |def bump(): Unit =
        |  var counter = 5
        |  counter = counter + 1
        |bump()
        |counter = counter + 100
        |println(counter)""".stripMargin) == "100")

  test("callee var does not clobber caller var read after the call"):
    assert(captured(
      """def helper(): Int =
        |  var x = 7
        |  x = x * 2
        |  x
        |def caller(): Int =
        |  var x = 1
        |  val h = helper()
        |  x = x + h
        |  x
        |println(caller())""".stripMargin) == "15")
