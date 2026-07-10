package ssc.plugin.state

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Runtime, V2EffectContext, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class StateNativePluginTest extends AnyFunSuite:
  private def invoke(fn: Value, args: Value*): Value = fn match
    case closure: Value.ClosV =>
      val env = if args.isEmpty then closure.env else Runtime.extend(closure.env, args.toArray)
      Runtime.run(closure.code, env)
    case _ => fail("value is not callable")

  private def stateHandler = V2EffectContext.peek("State").get

  test("runState handles get, set, and callback-based modify"):
    NativePluginHost.installProviders(List(StateNativePlugin()))
    val plusFive = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.IntV(number) => Done(Value.IntV(number + 5))
      case _ => Done(Value.UnitV))
    val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      val before = stateHandler("get", Nil)
      stateHandler("modify", List(plusFive))
      stateHandler("set", List(Value.IntV(17)))
      Done(before))

    val runner = invoke(V2PluginRegistry.lookupGlobal("runState").get, Value.IntV(10))
    val result = invoke(runner, body)

    assert(result == Value.DataV("Pair", Vector(Value.IntV(17), Value.IntV(10))))
    assert(V2EffectContext.peek("State").isEmpty)

  test("nested runState restores the outer handler"):
    NativePluginHost.installProviders(List(StateNativePlugin()))
    val runState = V2PluginRegistry.lookupGlobal("runState").get
    val innerBody = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      stateHandler("set", List(Value.IntV(101)))
      Done(stateHandler("get", Nil)))
    val outerBody = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      stateHandler("set", List(Value.IntV(2)))
      val inner = invoke(invoke(runState, Value.IntV(100)), innerBody)
      val restored = stateHandler("get", Nil)
      Done(Value.DataV("Pair", Vector(inner, restored))))

    val result = invoke(invoke(runState, Value.IntV(1)), outerBody)

    assert(result == Value.DataV("Pair", Vector(
      Value.IntV(2),
      Value.DataV("Pair", Vector(
        Value.DataV("Pair", Vector(Value.IntV(101), Value.IntV(101))),
        Value.IntV(2))))))
    assert(V2EffectContext.peek("State").isEmpty)
