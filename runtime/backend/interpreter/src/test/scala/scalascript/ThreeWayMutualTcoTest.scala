package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression for the three-way mutual-TCO blow-up (2026-06-12).
 *
 *  `ping→pong→pang→ping` is a mutual-recursion CYCLE where no function calls
 *  itself — each only tail-calls the NEXT. The TCO trampoline's MutualTailCall
 *  handler used to JIT-run such a `next` whenever it had no non-tail self-call;
 *  but the register VM lowers a non-self tail call to a recursing `CALL`, so
 *  running the whole cycle in compiled code grew the stack per step →
 *  FrameOverflow-restart → O(n²) (effectively a hang at depth ~100k). The fix
 *  only JIT-runs a SELF-tail-recursive `next` (e.g. `sumTco`); a non-self cycle
 *  stays on the constant-stack tree-walk trampoline.
 *
 *  These run at depth ~100k with the JIT ON (default) and assert a wall-clock
 *  bound so a re-introduced blow-up fails loudly instead of hanging the suite. */
class ThreeWayMutualTcoTest extends AnyFunSuite:
  private def runTimed(label: String, maxMs: Long, src: String, expected: String): Unit =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val t0  = System.nanoTime()
    Interpreter(out = ps).run(Parser.parse(src))
    ps.flush()
    val ms = (System.nanoTime() - t0) / 1000000
    assert(buf.toString.trim == expected, s"$label: expected [$expected] got [${buf.toString.trim}]")
    assert(ms < maxMs, s"$label took ${ms}ms (> ${maxMs}ms) — mutual-TCO blow-up regressed")

  test("three-way mutual TCO (ping/pong/pang) is constant-stack at depth ~100k") {
    runTimed("3-way", 8000, """
      def ping(n: Int): String = if n == 0 then "ping" else pong(n - 1)
      def pong(n: Int): String = if n == 0 then "pong" else pang(n - 1)
      def pang(n: Int): String = if n == 0 then "pang" else ping(n - 1)
      println(ping(99999))
      println(ping(99998))
      println(ping(99997))
    """, "ping\npang\npong")
  }

  test("two-way mutual TCO (isEven/isOdd) stays fast at depth 100k") {
    runTimed("2-way", 8000, """
      def isEven(n: Int): Boolean = if n == 0 then true else isOdd(n - 1)
      def isOdd(n: Int): Boolean = if n == 0 then false else isEven(n - 1)
      println(isEven(100000)); println(isOdd(100000))
    """, "true\nfalse")
  }

  test("self-tail-recursion via a delegate (workload -> sumTco) still JITs") {
    runTimed("self-TCO", 8000, """
      def sumTco(n: Int, acc: Int): Int = if n == 0 then acc else sumTco(n - 1, acc + n)
      def workload(): Int = sumTco(100000, 0)
      println(workload())
    """, "5000050000")
  }
