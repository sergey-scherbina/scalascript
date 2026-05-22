package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Phase 3: performance regression gate for the coroutine-based Async scheduler.
 *
 *  The coroutine approach eliminates per-operation Free-monad allocations
 *  (no Computation.FlatMap / Perform nodes per await).  These tests verify
 *  that a flatMap-heavy workload completes within a generous wall-clock budget
 *  — a hard regression if the scheduler regresses to O(N) allocation. */
class AsyncPerfTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("perf: 1 000 sequential async/await cycles complete in < 3s"):
    val n   = 1000
    val t0  = System.nanoTime()
    val out = run(s"""
      val result = runAsync {
        def loop(i: Int, acc: Int): Int =
          if i <= 0 then acc
          else
            val v = Async.await(Async.async(() => i))
            loop(i - 1, acc + v)
        loop($n, 0)
      }
      println(result)
    """)
    val ms = (System.nanoTime() - t0) / 1_000_000L
    out.trim shouldBe (n * (n + 1) / 2).toString
    ms should be < 3000L

  test("perf: 500 Async.parallel thunks complete in < 3s"):
    val n   = 500
    val t0  = System.nanoTime()
    val out = run(s"""
      val result = runAsync {
        val xs = Async.parallel(List.tabulate($n)(i => () => i + 1))
        xs.sum
      }
      println(result)
    """)
    val ms = (System.nanoTime() - t0) / 1_000_000L
    out.trim shouldBe (n * (n + 1) / 2).toString
    ms should be < 3000L
