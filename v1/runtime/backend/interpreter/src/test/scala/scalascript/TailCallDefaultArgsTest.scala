package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression for the TCO-trampoline default-arg blow-up (2026-06-15).
 *
 *  A tail/mutual call may supply fewer args than the callee declares, relying
 *  on default parameters: `def g(x, y = 10)` tail-called as `g(5)`. `callFun`'s
 *  normal entry runs `applyDefaults`, but the trampoline's tail-dispatch paths
 *  (self `TailCall`, `MutualTailCall`, and the resume re-entries) bound `curArgs`
 *  raw — so the call env builder read `curArgs(1)` off a 1-element list and threw
 *  `IndexOutOfBoundsException` (2 params) / `ArrayIndexOutOfBoundsException`
 *  (3+ params). Found via `std.ui.content.contentView(doc)` (2 params, `options`
 *  defaulted) tail-called from a per-locale selector. The fix fills the missing
 *  trailing args from their defaults in the trampoline's callEnv builder. */
class TailCallDefaultArgsTest extends AnyFunSuite:
  private def run(src: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("mutual tail call to a 2-param fn relying on a default arg fills the default") {
    // `caller` tail-calls `g` (a FunV → mutual-tail target), so `caller` runs on
    // the trampoline; `g(5)` omits `y`, which the trampoline must default to 10.
    val out = run("""
      def g(x: Int, y: Int = 10): Int = x + y
      def caller(b: Boolean): Int = if b then g(5) else g(7)
      println(caller(true))
      println(caller(false))
    """)
    assert(out == "15\n17", s"expected [15\\n17] got [$out]")
  }

  test("self tail-recursive fn with a defaulted trailing param, tail-called short, recurses") {
    // `loop(n - 1)` is a self-tail call omitting `msg`; each iteration the
    // trampoline must re-default it. Final iteration returns the default.
    val out = run("""
      def loop(n: Int, msg: String = "done"): String = if n == 0 then msg else loop(n - 1)
      println(loop(5))
    """)
    assert(out == "done", s"expected [done] got [$out]")
  }

  test("3-param fn tail-called with 1 arg fills two trailing defaults") {
    // Exercises the >2-param (`case ps`) branch of the callEnv builder, which
    // built a short array and blew up in FrameMapN before the fix.
    val out = run("""
      def g(a: Int, b: Int = 2, c: Int = 3): Int = a + b + c
      def caller(): Int = g(1)
      println(caller())
    """)
    assert(out == "6", s"expected [6] got [$out]")
  }
