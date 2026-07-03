package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** interp-poly-closed-form — `tryClosedFormPolyLoop` now handles inline degree-≤2
 *  polynomial addends in left-assoc form (e.g. `acc = acc + (i-c)^2 + k*i`).
 *
 *  Differential: each case is run with FastTier ON (closed-form fires) and OFF
 *  (JIT / interpreter fallback) — both must produce the same result. */
class PolyClosedFormTest extends AnyFunSuite with Matchers:

  private def run(fastTier: Boolean, code: String): String =
    val prev = System.getProperty("ssc.fasttier")
    System.setProperty("ssc.fasttier", if fastTier then "1" else "0")
    try
      val buf = java.io.ByteArrayOutputStream()
      val ps  = java.io.PrintStream(buf, true)
      Interpreter(ps).run(Parser.parse(s"# T\n\n```scalascript\n$code\n```\n"))
      ps.flush(); buf.toString.trim
    finally
      if prev == null then System.clearProperty("ssc.fasttier")
      else System.setProperty("ssc.fasttier", prev)

  private def check(label: String, code: String): Unit =
    val off = run(false, code)
    val on  = run(true,  code)
    withClue(s"$label — fast=$on slow=$off"):
      on shouldBe off

  test("multiVal: quadratic inline polynomial (i-500)^2 + 2*(i-500)"):
    check("multiVal",
      """var i = 0
        |var s = 0
        |while i < 1000000 do
        |  val a = i - 500
        |  val b = a * 2
        |  s = s + a * a + b
        |  i = i + 1
        |println(s)""".stripMargin)

  test("linear inline: acc = acc + 3*i + 1"):
    check("linear-inline",
      """var i = 0
        |var s = 0
        |while i < 100000 do
        |  s = s + 3 * i + 1
        |  i = i + 1
        |println(s)""".stripMargin)

  test("constant inline: acc = acc + 7"):
    check("constant-inline",
      """var i = 0
        |var s = 0
        |while i < 100000 do
        |  s = s + 7
        |  i = i + 1
        |println(s)""".stripMargin)

  test("pure quadratic no offset: acc = acc + i*i"):
    check("pure-quadratic",
      """var i = 0
        |var s = 0
        |while i < 10000 do
        |  s = s + i * i
        |  i = i + 1
        |println(s)""".stripMargin)

  test("step-2 quadratic: counter increments by 2"):
    check("step-2",
      """var i = 0
        |var s = 0
        |while i < 10000 do
        |  s = s + i * i
        |  i = i + 2
        |println(s)""".stripMargin)

  test("negative offset: acc = acc + (i - 250)*(i - 250)"):
    check("neg-offset",
      """var i = 0
        |var s = 0
        |while i < 1000 do
        |  val d = i - 250
        |  s = s + d * d
        |  i = i + 1
        |println(s)""".stripMargin)

  test("non-zero start: counter starts mid-range"):
    check("nonzero-start",
      """var i = 500
        |var s = 0
        |while i < 1500 do
        |  s = s + i * i - i
        |  i = i + 1
        |println(s)""".stripMargin)
