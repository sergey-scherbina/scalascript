package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{CoreActorRuntimeProvider, Interpreter}
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Actors plugin skeleton: the provider is installed through the same explicit plugin path that
 *  later slices will use when the runtime implementation moves out of core. */
class ActorsPluginProviderTest extends AnyFunSuite:

  private def captured(code: String): String =
    val buf    = java.io.ByteArrayOutputStream()
    val ps     = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps)
    interp.installPlugins(List(new ActorsInterpreterPlugin))
    interp.run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("actors plugin contributes the current provider"):
    assert(new ActorsInterpreterPlugin().actorRuntimeProvider eq CoreActorRuntimeProvider)

  test("runActors still works with the actors provider installed through plugin wiring"):
    val out = captured("""
      runActors {
        val me = self()
        val worker = spawn { () =>
          receive { case msg => me ! msg }
        }
        worker ! "pong"
        receive { case msg => println(msg) }
      }
    """)
    assert(out == "pong", s"expected actor message round-trip via plugin provider, got: [$out]")
