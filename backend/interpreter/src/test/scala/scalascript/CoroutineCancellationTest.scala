package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Conformance tests for coroutine cancellation (v1.9.x). */
class CoroutineCancellationTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("coroutineCancel — cancel before first resume makes further resumes fail"):
    an[Exception] should be thrownBy captured("""
      val co = coroutineCreate(() => { suspend(()); "done" })
      coroutineCancel(co)
      coroutineResume(co, ())
    """)

  test("coroutineCancel — cancel mid-flight: body does not advance past last suspend"):
    captured("""
      var step2Reached = false
      val co = coroutineCreate(() => {
        suspend("step1")
        step2Reached = true
        "done"
      })
      coroutineResume(co, ())
      coroutineCancel(co)
      println(step2Reached)
    """) shouldBe "false"

  test("coroutineCancel — cancel already-finished coroutine is a no-op"):
    captured("""
      val co = coroutineCreate(() => "done")
      coroutineResume(co, ())
      coroutineCancel(co)
      println("ok")
    """) shouldBe "ok"

  test("coroutineCancel — cancelled coroutine handle is invalidated for further resumes"):
    an[Exception] should be thrownBy captured("""
      val co = coroutineCreate(() => {
        suspend(1)
        suspend(2)
        "done"
      })
      coroutineResume(co, ())
      coroutineCancel(co)
      coroutineResume(co, ())
    """)
