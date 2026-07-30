package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** An extension whose receiver type is an ALIAS never dispatched on the interpreter.
 *
 *  Registration takes the receiver's type name from the SYNTAX (`StatRuntime`:
 *  `Pass[A, B]` -> `"Pass"`), while dispatch derives it from the runtime VALUE
 *  (`DispatchRuntime.extensionDispatch`), and a function value matches none of the
 *  nominal cases there, so it becomes `"Any"`. `extensions("Pass")` existed and
 *  `extensions("Any")` was looked up.
 *
 *  Found via `dsl-mini-language`, which is blocked by exactly this
 *  (`No method 'andThen' on FunV` — `std/dsl/passes.ssc` declares
 *  `type Pass[A, B] = A => Either[List[PassError], B]` and extends it). v2 dispatches
 *  it correctly, so the interpreter was the outlier.
 *  (int-extension-on-function-type-alias-does-not-dispatch.)
 */
class ExtensionOnAliasDispatchTest extends AnyFunSuite:

  private def run(src: String): String =
    val out = new java.io.ByteArrayOutputStream
    val interp = Interpreter(new java.io.PrintStream(out, true, "UTF-8"), None)
    interp.run(Parser.parse(src))
    out.toString("UTF-8").trim

  test("an extension on a function-type alias dispatches"):
    val out = run(
      """```scalascript
        |type Fn = Int => Int
        |
        |extension (f: Fn)
        |  def twice(n: Int): Int = f(f(n))
        |
        |val inc: Fn = (x) => x + 1
        |println(inc.twice(5))
        |```
        |""".stripMargin)
    assert(out == "7", s"got:\n$out")

  test("the dsl-mini-language shape: a curried alias with a chained extension"):
    val out = run(
      """```scalascript
        |type Step = Int => Int
        |
        |extension (p: Step)
        |  def then2(q: Step): Step = (x) => q(p(x))
        |
        |val add1: Step = (x) => x + 1
        |val dbl: Step  = (x) => x * 2
        |println(add1.then2(dbl)(5))
        |```
        |""".stripMargin)
    assert(out == "12", s"got:\n$out")

  test("a nominal receiver still wins over the fallback"):
    // The control. Two extensions share a method name; the one registered for the
    // receiver's ACTUAL type must be chosen, so the new fallback must not preempt
    // ordinary nominal dispatch.
    val out = run(
      """```scalascript
        |case class Box(n: Int)
        |type Fn = Int => Int
        |
        |extension (b: Box)
        |  def tag(): String = "box"
        |
        |extension (f: Fn)
        |  def tag(): String = "fn"
        |
        |val inc: Fn = (x) => x + 1
        |println(Box(1).tag())
        |println(inc.tag())
        |```
        |""".stripMargin)
    assert(out.linesIterator.toList == List("box", "fn"), s"got:\n$out")

  test("an unknown method on a function still fails, and says so"):
    // The guard must stay a guard: the fallback may only resolve a method that some
    // extension actually registered.
    val error = intercept[Exception](run(
      """```scalascript
        |val inc: Int => Int = (x) => x + 1
        |println(inc.nosuchmethod())
        |```
        |""".stripMargin))
    assert(error.getMessage.contains("nosuchmethod"),
      s"expected a diagnostic naming the missing method, got: ${error.getMessage}")
