package ssc.plugin.reactive

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class ReactiveNativePluginTest extends AnyFunSuite:
  private def invoke(fn: Value, args: Value*): Value = fn match
    case closure: Value.ClosV =>
      val env = if args.isEmpty then closure.env else Runtime.extend(closure.env, args.toArray)
      Runtime.run(closure.code, env)
    case signal @ Value.DataV("ReactiveSignal", _) if args.isEmpty =>
      V2PluginRegistry.lookupTaggedApply("ReactiveSignal").get(List(signal))
    case _ => fail("value is not callable")

  private def global(name: String, args: Value*): Value =
    invoke(V2PluginRegistry.lookupGlobal(name).get, args*)

  private def method(signal: Value, name: String, args: Value*): Value =
    Prims.methodOp(name, signal, args.toList)

  test("mutable and computed signals publish current values"):
    NativePluginHost.installProviders(List(ReactiveNativePlugin()))
    val count = global("Signal", Value.IntV(1))
    assert(method(count, "get") == Value.IntV(1))
    method(count, "set", Value.IntV(3))
    assert(invoke(count) == Value.IntV(3))
    val doubled = global("computed", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      method(count, "get") match
        case Value.IntV(number) => Done(Value.IntV(number * 2))
        case _ => Done(Value.UnitV)))
    assert(method(doubled, "apply") == Value.IntV(6))
    method(count, "set", Value.IntV(4))
    assert(invoke(doubled) == Value.IntV(8))

  test("diamond dependencies flush once in insertion order"):
    NativePluginHost.installProviders(List(ReactiveNativePlugin()))
    val source = global("Signal", Value.IntV(2))
    val doubled = global("computed", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      method(source, "get") match
        case Value.IntV(number) => Done(Value.IntV(number * 2))
        case _ => Done(Value.UnitV)))
    val observed = scala.collection.mutable.ArrayBuffer.empty[(Long, Long)]
    global("effect", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      val Value.IntV(left) = method(source, "get"): @unchecked
      val Value.IntV(right) = method(doubled, "get"): @unchecked
      observed += left -> right
      Done(Value.UnitV)))
    method(source, "set", Value.IntV(5))
    assert(observed.toList == List(2L -> 4L, 5L -> 10L))

  test("dependencies refresh and self writes do not recurse"):
    NativePluginHost.installProviders(List(ReactiveNativePlugin()))
    val chooseLeft = global("Signal", Value.BoolV(true))
    val left = global("Signal", Value.IntV(1))
    val right = global("Signal", Value.IntV(10))
    val observed = scala.collection.mutable.ArrayBuffer.empty[Long]
    global("effect", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      val selected = method(chooseLeft, "get") match
        case Value.BoolV(true) => method(left, "get")
        case _ => method(right, "get")
      val Value.IntV(number) = selected: @unchecked
      observed += number
      Done(Value.UnitV)))
    method(chooseLeft, "set", Value.BoolV(false))
    method(left, "set", Value.IntV(2))
    method(right, "set", Value.IntV(11))
    assert(observed.toList == List(1L, 10L, 11L))

    val tick = global("Signal", Value.IntV(0))
    val tickObserved = scala.collection.mutable.ArrayBuffer.empty[Long]
    global("effect", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      val Value.IntV(value) = method(tick, "get"): @unchecked
      tickObserved += value
      Done(Value.UnitV)))
    var runs = 0
    global("effect", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      runs += 1
      val Value.IntV(value) = method(tick, "get"): @unchecked
      method(tick, "set", Value.IntV(value + 1))
      Done(Value.UnitV)))
    assert(runs == 1)
    assert(method(tick, "get") == Value.IntV(1))
    assert(tickObserved.toList == List(0L, 1L))
