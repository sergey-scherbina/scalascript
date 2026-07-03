package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression guards for `foreach-var-mutation` (specs/foreach-var-mutation.md).
 *
 *  Mutating an outer `var` from inside a `foreach` lambda must propagate to the
 *  enclosing scope (Scala closure-by-reference semantics).  The reported bug
 *  (`var` stayed at its pre-loop value, silent wrong result) no longer
 *  reproduces — these tests pin the correct behaviour for each checklist item. */
class ForeachVarMutationTest extends AnyFunSuite:

  private def captured(ssc: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scalascript\n$ssc\n```\n"))
    ps.flush()
    buf.toString.trim

  test("List.foreach appending to an outer typed-list var propagates"):
    assert(captured(
      """var state = List[Int]()
        |List(1, 2, 3).foreach(x => state = state :+ x)
        |println(state.length)""".stripMargin) == "3")

  test("List.foreach accumulating into an outer Int var propagates"):
    assert(captured(
      """var sum = 0
        |List(1, 2, 3).foreach(x => sum = sum + x)
        |println(sum)""".stripMargin) == "6")

  test("nested foreach mutating the same outer var"):
    assert(captured(
      """var c = 0
        |List(1, 2).foreach(x => List(10, 20).foreach(y => c = c + x + y))
        |println(c)""".stripMargin) == "66")

  test("Set.foreach mutation propagates"):
    assert(captured(
      """var n = 0
        |Set(1, 2, 3).toSet.foreach(x => n = n + x)
        |println(n)""".stripMargin) == "6")

  test("Map.foreach mutation propagates"):
    assert(captured(
      """var n = 0
        |Map("a" -> 1, "b" -> 2).foreach(kv => n = n + kv._2)
        |println(n)""".stripMargin) == "3")

  test("a var declared inside the lambda stays local (no leak upward)"):
    assert(captured(
      """var outer = 100
        |List(1, 2, 3).foreach(x => {
        |  var local = x
        |  local = local + 1
        |})
        |println(outer)""".stripMargin) == "100")

  test("foldLeft accumulation is unchanged (the documented workaround)"):
    assert(captured(
      """val total = List(1, 2, 3, 4).foldLeft(0)((acc, x) => acc + x)
        |println(total)""".stripMargin) == "10")

  // ---- for-do (imperative `for x <- xs do …`) outer-var mutation -----------
  // The bug: inside a function, a `for-do` body assignment goes to interp.globals
  // while the function's local frame stays stale, so the function returned the
  // pre-loop value. These pin per-iteration sync-back of the enclosing `var`.

  test("for-do over a range mutating an enclosing var inside a function"):
    assert(captured(
      """def sumTo(n: Int): Int = {
        |  var sum = 0
        |  for i <- 1 to n do sum = sum + i
        |  sum
        |}
        |println(sumTo(5))""".stripMargin) == "15")

  test("for-do over a list mutating an enclosing var inside a function"):
    assert(captured(
      """def total(xs: List[Int]): Int = {
        |  var sum = 0
        |  for x <- xs do sum = sum + x
        |  sum
        |}
        |println(total(List(10, 20, 30)))""".stripMargin) == "60")

  test("for-do with a guard mutating an enclosing var inside a function"):
    assert(captured(
      """def sumEven(n: Int): Int = {
        |  var sum = 0
        |  for i <- 1 to n if i % 2 == 0 do sum = sum + i
        |  sum
        |}
        |println(sumEven(6))""".stripMargin) == "12")

  test("nested for-do mutating the same enclosing var inside a function"):
    assert(captured(
      """def count(n: Int): Int = {
        |  var c = 0
        |  for i <- 1 to n do
        |    for j <- 1 to n do
        |      c = c + 1
        |  c
        |}
        |println(count(3))""".stripMargin) == "9")
