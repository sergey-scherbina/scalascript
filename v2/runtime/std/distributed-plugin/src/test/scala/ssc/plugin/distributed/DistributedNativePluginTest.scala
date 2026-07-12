package ssc.plugin.distributed

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class DistributedNativePluginTest extends AnyFunSuite:
  private def install(): Unit =
    NativePluginHost.installProviders(List(DistributedNativePlugin()))

  private def invoke(fn: Value, args: Value*): Value = fn match
    case clos: Value.ClosV =>
      val env = if args.isEmpty then clos.env else Runtime.extend(clos.env, args.toArray)
      Runtime.run(clos.code, env)
    case other => fail(s"not callable: $other")

  private def global(name: String, args: Value*): Value =
    invoke(V2PluginRegistry.lookupGlobal(name).getOrElse(fail(s"missing global $name")), args*)

  private def call(receiver: Value, name: String, args: Value*): Value = receiver match
    case Value.ForeignV(obj: Value.NamedMethodObj) =>
      invoke(obj.getField(name).getOrElse(fail(s"missing method $name")), args*)
    case other => fail(s"not a method object: $other")

  private def function(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def list(values: Value*): Value =
    values.reverse.foldLeft[Value](Value.DataV("Nil", Vector.empty)) { (tail, head) =>
      Value.DataV("Cons", Vector(head, tail))
    }

  private def items(value: Value): List[Value] = Prims.unlistPub(value)

  private def register(name: String, fn: Value): Unit =
    Prims.methodOp("register", Value.DataV("HandlerRegistry", Vector.empty),
      List(global("NamedHandler", Value.StrV(name), fn)))

  private def cluster(nodes: Int = 3): Value =
    global("localLoopbackCluster",
      (1 to nodes).map(index => global("Node", Value.StrV(s"node-$index")))* )

  private def resultItems(value: Value): List[Value] = value match
    case Value.DataV("DistributedResult", IndexedSeq(results, _)) => items(results)
    case other => fail(s"not DistributedResult: $other")

  test("registry replacement, lookup, clear, and reinstall are isolated") {
    install()
    val registry = V2PluginRegistry.lookupGlobal("HandlerRegistry").get
    register("value", function(1)(_ => Value.IntV(1)))
    register("value", function(1)(_ => Value.IntV(2)))
    assert(call(registry, "apply", Value.StrV("value"), Value.UnitV) == Value.IntV(2))
    assert(items(call(registry, "registeredNames")) == List(Value.StrV("value")))
    assert(call(registry, "lookup", Value.StrV("value")).isInstanceOf[Value.DataV])
    call(registry, "clear")
    assert(items(call(registry, "registeredNames")).isEmpty)

    register("leak", function(1)(_.head))
    install()
    val fresh = V2PluginRegistry.lookupGlobal("HandlerRegistry").get
    assert(call(fresh, "lookup", Value.StrV("leak")) == Value.DataV("None", Vector.empty))
    assert(V2PluginRegistry.lookup("HandlerRegistry.register").isDefined)
    assert(V2PluginRegistry.lookup("HandlerRegistry.unknown").isEmpty)
  }

  test("map, filter, and flatMap preserve contiguous partition order") {
    install()
    register("plusOne", function(1) {
      case Value.IntV(number) :: Nil => Value.IntV(number + 1)
      case _ => fail("plusOne input")
    })
    register("even", function(1) {
      case Value.IntV(number) :: Nil => Value.BoolV(number % 2 == 0)
      case _ => fail("even input")
    })
    register("duplicate", function(1) {
      case value :: Nil => list(value, value)
      case _ => fail("duplicate input")
    })
    val operations = list(
      global("MapOp", Value.StrV("plusOne")),
      global("FilterOp", Value.StrV("even")),
      global("FlatMapOp", Value.StrV("duplicate")))
    val stage = global("Stage", operations)
    val distributed = global("runDistributed",
      list((1 to 6).map(n => Value.IntV(n))*), stage, cluster(), Value.IntV(1), Value.BoolV(false))
    assert(resultItems(distributed) == List(2, 2, 4, 4, 6, 6).map(Value.IntV(_)))
    assert(distributed match
      case Value.DataV("DistributedResult", IndexedSeq(_, failures)) => items(failures).isEmpty
      case _ => false)
  }

  test("groupBy and reduceByKey retain first-key and value order") {
    install()
    register("parity", function(1) {
      case Value.IntV(number) :: Nil => Value.IntV(number % 2)
      case _ => fail("parity input")
    })
    register("sum", function(1) {
      case Value.DataV("Tuple2", IndexedSeq(Value.IntV(left), Value.IntV(right))) :: Nil =>
        Value.IntV(left + right)
      case _ => fail("sum input")
    })
    val emptyStage = global("Stage", list())
    val input = list(Value.IntV(3), Value.IntV(2), Value.IntV(5), Value.IntV(4))
    val groupedStage = global("ShuffleStage", emptyStage, Value.StrV("parity"),
      Value.StrV(""), Value.BoolV(true))
    val grouped = resultItems(global("runDistributedShuffle", input, groupedStage,
      cluster(2), Value.IntV(0), Value.BoolV(false)))
    assert(grouped == List(
      Value.DataV("Tuple2", Vector(Value.IntV(1), list(Value.IntV(3), Value.IntV(5)))),
      Value.DataV("Tuple2", Vector(Value.IntV(0), list(Value.IntV(2), Value.IntV(4))))))

    val reducedStage = global("ShuffleStage", emptyStage, Value.StrV("parity"),
      Value.StrV("sum"), Value.BoolV(false))
    assert(resultItems(global("runDistributedShuffle", input, reducedStage,
      cluster(2), Value.IntV(0), Value.BoolV(true))) == List(
      Value.DataV("Tuple2", Vector(Value.IntV(1), Value.IntV(8))),
      Value.DataV("Tuple2", Vector(Value.IntV(0), Value.IntV(6)))))
  }

  test("missing handlers, invalid flatMap, and closed clusters fail explicitly") {
    install()
    val missing = global("Stage", list(global("MapOp", Value.StrV("missing"))))
    val open = cluster()
    val missingError = intercept[IllegalArgumentException] {
      global("runDistributed", list(Value.IntV(1)), missing, open,
        Value.IntV(0), Value.BoolV(false))
    }
    assert(missingError.getMessage.contains("no handler registered"))

    register("badFlatMap", function(1)(_ => Value.IntV(1)))
    val invalid = global("Stage", list(global("FlatMapOp", Value.StrV("badFlatMap"))))
    val flatMapError = intercept[IllegalArgumentException] {
      global("runDistributed", list(Value.IntV(1)), invalid, open,
        Value.IntV(0), Value.BoolV(false))
    }
    assert(flatMapError.getMessage.contains("expects a portable List"))

    call(open, "close")
    call(open, "close")
    val closedError = intercept[IllegalStateException] {
      global("runDistributed", list(Value.IntV(1)), global("Stage", list()), open,
        Value.IntV(0), Value.BoolV(false))
    }
    assert(closedError.getMessage.contains("closed localLoopbackCluster"))
  }
