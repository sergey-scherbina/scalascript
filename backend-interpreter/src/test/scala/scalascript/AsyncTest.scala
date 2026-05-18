package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Tests for v1.11 coroutine-based Async scheduler (runAsync / runAsyncParallel). */
class AsyncTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("runAsync: sequential await"):
    run("""
      val r = runAsync {
        val a = Async.async(() => 1)
        val b = Async.async(() => 2)
        Async.await(a) + Async.await(b)
      }
      println(r)
    """) shouldBe "3"

  test("runAsync: parallel keeps declared order"):
    run("""
      val r = runAsync {
        Async.parallel(List(() => 10, () => 20, () => 30))
      }
      println(r)
    """) shouldBe "List(10, 20, 30)"

  test("runAsync: delay is honoured"):
    run("""
      runAsync {
        Async.delay(1)
        println("after-delay")
      }
    """) shouldBe "after-delay"

  test("runAsync: async + await + arithmetic"):
    run("""
      val r = runAsync {
        val a = Async.async(() => 6)
        val b = Async.async(() => 7)
        val sum = Async.await(a) + Async.await(b)
        Async.await(Async.async(() => sum * 2))
      }
      println(r)
    """) shouldBe "26"

  test("runAsync: nested runAsync"):
    run("""
      val r = runAsync {
        val inner = runAsync {
          val f = Async.async(() => 7)
          Async.await(f) + 3
        }
        inner
      }
      println(r)
    """) shouldBe "10"

  test("runAsync: map with effectful callback"):
    run("""
      def doubled(n: Int): Int =
        Async.await(Async.async(() => n * 2))

      val r = runAsync {
        List(1, 2, 3, 4).map(doubled)
      }
      println(r)
    """) shouldBe "List(2, 4, 6, 8)"

  test("runAsync: filter with effectful predicate"):
    run("""
      val r = runAsync {
        List(1, 2, 3, 4, 5).filter(n => Async.await(Async.async(() => n > 2)))
      }
      println(r)
    """) shouldBe "List(3, 4, 5)"

  test("runAsync: foldLeft with effectful accumulator"):
    run("""
      def doubled(n: Int): Int =
        Async.await(Async.async(() => n * 2))

      val r = runAsync {
        List(1, 2, 3, 4).foldLeft(0)((acc, n) => acc + doubled(n))
      }
      println(r)
    """) shouldBe "20"

  test("runAsyncParallel: parallel keeps declared order"):
    run("""
      val r = runAsyncParallel {
        Async.parallel(List(() => 10, () => 20, () => 30))
      }
      println(r)
    """) shouldBe "List(10, 20, 30)"

  test("runAsyncParallel: sequential await"):
    run("""
      val r = runAsyncParallel {
        val a = Async.async(() => 7)
        val b = Async.async(() => 8)
        Async.await(a) + Async.await(b)
      }
      println(r)
    """) shouldBe "15"

  test("runAsyncParallel: delay is honoured"):
    run("""
      runAsyncParallel {
        Async.delay(1)
        println("after-delay")
      }
    """) shouldBe "after-delay"
