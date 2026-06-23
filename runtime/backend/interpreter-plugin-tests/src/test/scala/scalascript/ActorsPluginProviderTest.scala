package scalascript

import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.atomic.AtomicInteger
import scalascript.interpreter.{
  ActorRuntimeHost,
  ActorRuntimeProvider,
  ActorRuntimeSession,
  Computation,
  CoreActorRuntimeProvider,
  Interpreter
}
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

  test("actor provider opens one session per installed provider and reopens after replacement"):
    final class CountingActorsPlugin(opens: AtomicInteger, runs: AtomicInteger) extends ActorsInterpreterPlugin:
      override def actorRuntimeProvider: ActorRuntimeProvider =
        new ActorRuntimeProvider:
          def open(host: ActorRuntimeHost): ActorRuntimeSession =
            opens.incrementAndGet()
            new ActorRuntimeSession:
              def runActors(initial: Computation): Computation =
                runs.incrementAndGet()
                host.runCoreActorRuntime(initial)

    val buf    = java.io.ByteArrayOutputStream()
    val ps     = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps)
    val opens1 = AtomicInteger(0)
    val runs1  = AtomicInteger(0)
    interp.installPlugins(List(new CountingActorsPlugin(opens1, runs1)))
    interp.run(Parser.parse("# Test\n\n```scala\nrunActors { println(\"one\") }\n```\n"))
    interp.run(Parser.parse("# Test\n\n```scala\nrunActors { println(\"two\") }\n```\n"))

    assert(opens1.get == 1, "same installed provider should open one interpreter-bound session")
    assert(runs1.get == 2, "the cached session should run both actor programs")

    val opens2 = AtomicInteger(0)
    val runs2  = AtomicInteger(0)
    interp.installPlugins(List(new CountingActorsPlugin(opens2, runs2)))
    interp.run(Parser.parse("# Test\n\n```scala\nrunActors { println(\"three\") }\n```\n"))

    assert(opens2.get == 1, "installing a replacement provider should clear the old cached session")
    assert(runs2.get == 1)

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
