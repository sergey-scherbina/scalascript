package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The State effect, after extraction from interpreter core into `state-effect-plugin`
 *  (core-minimization). Formerly in `StdEffectsTest`, now run with NO explicit `installPlugins` —
 *  `runState(s0)` resolves via the lazy ServiceLoader path, exactly as in production. `runState`
 *  exercises the config-args SPI path; `State.modify(f)` exercises the new `BlockContext.applyFn`
 *  capability (a handler invoking a ScalaScript closure passed as an op argument). */
class StatePluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("runState returns (finalState, result) pair (via plugin)"):
    captured("""
      val (s, r) = runState(0) {
        State.set(10)
        State.set(42)
        "done"
      }
      println(s)
      println(r)
    """) shouldBe "42\ndone"

  test("State.get returns current state (via plugin)"):
    captured("""
      val (s, r) = runState(7) {
        val v = State.get()
        v
      }
      println(r)
    """) shouldBe "7"

  test("State.modify applies a function to the state (via plugin / applyFn)"):
    captured("""
      val (s, r) = runState(10) {
        State.modify(x => x * 2)
        State.modify(x => x + 5)
        State.get()
      }
      println(s)
      println(r)
    """) shouldBe "25\n25"

  test("runState initial state is used when no set performed (via plugin)"):
    captured("""
      val (s, r) = runState(99) {
        42
      }
      println(s)
    """) shouldBe "99"
