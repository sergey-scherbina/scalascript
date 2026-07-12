package ssc.plugin.actors

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class ActorsNativePluginTest extends AnyFunSuite:
  private def install(): Unit =
    NativePluginHost.installProviders(List(ActorsNativePlugin()))

  private def invoke(fn: Value, args: Value*): Value = fn match
    case clos: Value.ClosV =>
      val env = if args.isEmpty then clos.env else Runtime.extend(clos.env, args.toArray)
      Runtime.run(clos.code, env)
    case other => fail(s"not callable: $other")

  private def global(name: String, args: Value*): Value =
    invoke(V2PluginRegistry.lookupGlobal(name).getOrElse(fail(s"missing global $name")), args*)

  private def function(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def send(target: Value, message: Value): Unit =
    V2PluginRegistry.lookup("actor.send").getOrElse(fail("missing actor.send"))(
      List(target, message))

  private def exit(target: Value, reason: Value): Unit =
    V2PluginRegistry.lookup("actor.exit").getOrElse(fail("missing actor.exit"))(
      List(target, reason))

  private def field(receiver: Value, name: String): Value = receiver match
    case Value.ForeignV(obj: Value.NamedMethodObj) =>
      obj.getField(name).getOrElse(fail(s"missing ActorRef.$name"))
    case other => fail(s"not ActorRef: $other")

  test("FIFO, timeout, self-send, exit/drop, and quiescence are deterministic") {
    install()
    val seen = ConcurrentLinkedQueue[Long]()
    val result = global("runActors", function(0) { _ =>
      val worker = global("spawn", function(0) { _ =>
        (1 to 3).foreach { _ =>
          global("receive", function(1) {
            case Value.IntV(number) :: Nil => seen.add(number); Value.UnitV
          })
        }
        Value.UnitV
      })
      send(worker, Value.IntV(1)); send(worker, Value.IntV(2)); send(worker, Value.IntV(3))

      val timed = global("receive", Value.IntV(10))
      assert(invoke(timed, function(1)(_ => Value.UnitV)) == Value.DataV("None", Vector.empty))

      val root = global("self")
      global("spawn", function(0) { _ => send(root, Value.StrV("ok")); Value.UnitV })
      val delivered = invoke(global("receive", Value.IntV(1000)), function(1) {
        case Value.StrV(text) :: Nil => Value.StrV(text)
      })

      val killed = global("spawn", function(0) { _ =>
        global("receive", function(1)(_ => fail("killed actor consumed a message")))
      })
      exit(killed, Value.StrV("kill"))
      send(killed, Value.StrV("ignored"))
      delivered
    })
    assert(result == Value.DataV("Some", Vector(Value.StrV("ok"))))
    assert(seen.toArray.toList == List(1L, 2L, 3L))
  }

  test("child failures are propagated by runActors") {
    install()
    val error = intercept[IllegalStateException] {
      global("runActors", function(0) { _ =>
        global("spawn", function(0)(_ => throw new IllegalArgumentException("child-boom")))
        Value.UnitV
      })
    }
    assert(error.getMessage.contains("child-boom"))
  }

  test("typed loopback refs tell, publish, and resolve named behaviors") {
    install()
    val seen = ConcurrentLinkedQueue[String]()
    global("runActors", function(0) { _ =>
      global("startNode", Value.StrV("node-a"))
      global("registerBehavior", Value.StrV("echo"), function(1) { _ =>
        global("receive", function(1) {
          case Value.StrV(message) :: Nil => seen.add(message); Value.UnitV
        })
      })
      val ref = global("spawnRemote", Value.StrV("node-a"), Value.StrV("echo"), Value.UnitV)
      assert(field(ref, "address") == Value.DataV("Some", Vector(Value.StrV("node-a"))))
      assert(field(ref, "isLocal") == Value.BoolV(true))
      assert(field(ref, "tryLocal").isInstanceOf[Value.DataV])
      invoke(field(ref, "publishAs"), Value.StrV("echo.ref"))
      global("globalWhereis", Value.StrV("echo.ref")) match
        case Value.DataV("Some", IndexedSeq(found)) =>
          invoke(field(found, "tell"), Value.StrV("ping"))
        case other => fail(s"missing published actor: $other")
      Value.UnitV
    })
    assert(seen.toArray.toList == List("ping"))
  }

  test("missing named behavior fails explicitly") {
    install()
    val error = intercept[IllegalStateException] {
      global("runActors", function(0) { _ =>
        global("spawnRemote", Value.StrV("node"), Value.StrV("missing"), Value.UnitV)
      })
    }
    assert(error.getMessage.contains("no behavior 'missing'"))
  }
