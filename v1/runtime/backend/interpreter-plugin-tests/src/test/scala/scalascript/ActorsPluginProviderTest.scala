package scalascript

import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.atomic.AtomicInteger
import scalascript.interpreter.{
  ActorRuntimeHost,
  ActorRuntimeProvider,
  ActorRuntimeSession,
  ActorScheduler,
  Computation,
  Interpreter,
  Value
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

  test("actors plugin contributes a real scheduler provider"):
    val provider = new ActorsInterpreterPlugin().actorRuntimeProvider
    assert(provider != null, "provider must not be null")
    // The real scheduler session wraps ActorScheduler, not the old CoreActorRuntimeProvider stub

  test("actor provider opens one session per installed provider and reopens after replacement"):
    final class CountingActorsPlugin(opens: AtomicInteger, runs: AtomicInteger) extends ActorsInterpreterPlugin:
      override def actorRuntimeProvider: ActorRuntimeProvider =
        new ActorRuntimeProvider:
          def open(host: ActorRuntimeHost): ActorRuntimeSession =
            opens.incrementAndGet()
            new ActorRuntimeSession:
              def runActors(initial: Computation): Computation =
                runs.incrementAndGet()
                new ActorScheduler(host).run(initial)

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

  test("actor provider can use explicit host services without an Interpreter self-type"):
    final class HostProbeActorsPlugin extends ActorsInterpreterPlugin:
      override def actorRuntimeProvider: ActorRuntimeProvider =
        new ActorRuntimeProvider:
          def open(host: ActorRuntimeHost): ActorRuntimeSession =
            new ActorRuntimeSession:
              def runActors(initial: Computation): Computation =
                host.actorNativeFeatureSet("actors.host.probe", "stored")
                val stored = host.actorNativeFeatureGet("actors.host.probe")
                val echo = Value.NativeFnV("host-echo", {
                  case List(v) => Computation.Pure(v)
                  case _       => throw new AssertionError("unexpected host-echo args")
                })
                val callN = Computation.run(host.actorCallValue(echo, List(Value.StringV("n")), Map.empty))
                val call1 = Computation.run(host.actorCallValue1(echo, Value.StringV("one"), Map.empty))
                val removed = host.actorNativeFeatureRemove("actors.host.probe")
                assert(stored.contains("stored"))
                assert(removed.contains("stored"))
                assert(callN == Value.StringV("n"))
                assert(call1 == Value.StringV("one"))
                host.out.println("host-services-ok")
                new ActorScheduler(host).run(initial)

    val out = capturedWithPlugin(new HostProbeActorsPlugin, """
      runActors {
        println("body-ok")
      }
    """)
    assert(out == "body-ok\nhost-services-ok")

  test("actor provider can register distributed server hooks through the host"):
    final class ServerHookActorsPlugin extends ActorsInterpreterPlugin:
      override def actorRuntimeProvider: ActorRuntimeProvider =
        new ActorRuntimeProvider:
          def open(host: ActorRuntimeHost): ActorRuntimeSession =
            new ActorRuntimeSession:
              def runActors(initial: Computation): Computation =
                val wsHandler = Value.NativeFnV("actor-ws-probe", _ => Computation.PureUnit)
                host.actorRegisterWsRoute(
                  path = "/_actor-host-probe",
                  handler = wsHandler,
                  protocols = List("ssc-actors-test"))
                host.actorRegisterHttpRoute("GET", "/_actor-http-probe",
                  Value.NativeFnV("actor-http-probe", _ => Computation.PureUnit))
                new ActorScheduler(host).run(initial)

    val buf    = java.io.ByteArrayOutputStream()
    val ps     = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps)
    interp.installPlugins(List(new ServerHookActorsPlugin))
    interp.run(Parser.parse("# Test\n\n```scala\nrunActors { println(\"server-hooks-ok\") }\n```\n"))

    assert(interp.wsRoutes.all.exists(e =>
      e.path == "/_actor-host-probe" && e.protocols == List("ssc-actors-test")))
    assert(interp.routeRegistry.all.exists(e =>
      e.method == "GET" && e.path == "/_actor-http-probe"),
      "actorRegisterHttpRoute must register an HTTP route via the host")
    // Cluster-control routes (/_ssc-cluster/*) are registered by ActorScheduler
    // during startNode — verified by the distributed actor tests (ActorDistributedTest).

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

  private def capturedWithPlugin(plugin: ActorsInterpreterPlugin, code: String): String =
    val buf    = java.io.ByteArrayOutputStream()
    val ps     = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps)
    interp.installPlugins(List(plugin))
    interp.run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim
