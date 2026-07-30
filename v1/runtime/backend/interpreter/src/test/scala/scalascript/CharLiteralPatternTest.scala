package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** A `Char` literal used as a PATTERN never matched on the interpreter: `compileLit`
 *  had no `Lit.Char` arm, so `case '*' =>` compiled to `NullV` and silently fell
 *  through to the next case. Equality was fine, which is what hid it — `'*' == '*'`
 *  is `true`, so a Char-dispatching parser looked healthy right up to the point
 *  where every branch took its fallback.
 *
 *  Found via `dsl-calc-parser`, whose frozen golden had recorded the WRONG answer:
 *  `1 + 2 => 1`, i.e. `case '+' =>` missing and `case _ => acc` dropping the
 *  operator. v2 matched correctly and was therefore recorded as the divergence.
 *  (int-char-literal-pattern-never-matches.)
 */
class CharLiteralPatternTest extends AnyFunSuite:

  private def run(src: String): String =
    val out = new java.io.ByteArrayOutputStream
    val interp = Interpreter(new java.io.PrintStream(out, true, "UTF-8"), None)
    interp.run(Parser.parse(src))
    out.toString("UTF-8").trim

  test("a Char literal pattern matches, and does not fall through"):
    val out = run(
      """```scalascript
        |def classify(c: Any): String = c match
        |  case '*' => "star"
        |  case '+' => "plus"
        |  case _   => "other"
        |
        |println(classify('*'))
        |println(classify('+'))
        |println(classify('z'))
        |```
        |""".stripMargin)
    // The third line is the control: an unmatched Char must STILL reach the
    // fallback, so this fails if the fix made every Char match instead.
    assert(out.linesIterator.toList == List("star", "plus", "other"), s"got:\n$out")

  test("an escaped Char literal pattern matches too"):
    val out = run(
      """```scalascript
        |def kind(c: Any): String = c match
        |  case '\n' => "nl"
        |  case '\t' => "tab"
        |  case _    => "other"
        |
        |println(kind('\n'))
        |println(kind('\t'))
        |println(kind('x'))
        |```
        |""".stripMargin)
    assert(out.linesIterator.toList == List("nl", "tab", "other"), s"got:\n$out")

  test("a Char literal pattern inside a fold picks the right branch"):
    // The dsl-calc-parser shape, reduced: dispatch on an operator Char while
    // folding. Before the fix this returned the accumulator every time.
    val out = run(
      """```scalascript
        |def apply(acc: Int, op: Any, n: Int): Int = op match
        |  case '+' => acc + n
        |  case '*' => acc * n
        |  case _   => acc
        |
        |println(apply(2, '+', 3))
        |println(apply(2, '*', 3))
        |println(apply(2, '?', 3))
        |```
        |""".stripMargin)
    assert(out.linesIterator.toList == List("5", "6", "2"), s"got:\n$out")

  test("Unit and Float literal patterns match as well"):
    // `compileLit`'s default arm silently produced NullV for every literal kind it
    // did not name, so Char was not the only one — these two were in the same hole.
    val out = run(
      """```scalascript
        |def u(v: Any): String = v match
        |  case () => "unit"
        |  case _  => "other"
        |
        |println(u(()))
        |println(u(1))
        |```
        |""".stripMargin)
    assert(out.linesIterator.toList == List("unit", "other"), s"got:\n$out")
