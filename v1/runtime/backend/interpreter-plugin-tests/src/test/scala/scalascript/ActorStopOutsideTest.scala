package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Regression for "Unhandled effect: Actor.self" when `stop()` is called
 *  outside `runActors { ... }`.
 *
 *  `ActorGlobals.install` puts a global `stop` that does `Perform Actor.self
 *  + exit("normal")`.  Top-level scripts that intend the http-plugin `stop()`
 *  but don't import it explicitly used to hit the actor `stop` (global
 *  namespace pollution) and crash because no `runActors{}` handler is in
 *  scope.  Fix: `stop()` is a no-op outside actor scope.
 *
 *  In-actor behaviour is preserved: `stop()` inside `runActors{}` still
 *  performs `self + exit("normal")` and the actor terminates immediately. */
class ActorStopOutsideTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val _i = Interpreter(ps); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("stop() outside runActors{} is a no-op (does not crash)"):
    captured(
      """println("before")
        |stop()
        |println("after")""".stripMargin
    ) shouldBe "before\nafter"

  test("stop() inside runActors{} terminates the actor (preserved behaviour)"):
    captured(
      """def actorBody(): Unit =
        |  println("alive")
        |  stop()
        |  println("dead - should NOT print")
        |
        |runActors {
        |  spawn(actorBody)
        |}
        |println("done")""".stripMargin
    ) shouldBe "alive\ndone"

  test("stop() outside runActors does not interfere with subsequent println"):
    captured(
      """println("1")
        |stop()
        |stop()
        |stop()
        |println("2")""".stripMargin
    ) shouldBe "1\n2"
