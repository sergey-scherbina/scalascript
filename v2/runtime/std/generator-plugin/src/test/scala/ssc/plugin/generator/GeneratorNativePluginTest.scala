package ssc.plugin.generator

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class GeneratorNativePluginTest extends AnyFunSuite:
  private def install(): Unit =
    NativePluginHost.installProviders(List(GeneratorNativePlugin()))

  private def invoke(fn: Value, args: Value*): Value = fn match
    case clos: Value.ClosV =>
      val env = if args.isEmpty then clos.env else Runtime.extend(clos.env, args.toArray)
      Runtime.run(clos.code, env)
    case other => fail(s"not callable: $other")

  private def global(name: String, args: Value*): Value =
    invoke(V2PluginRegistry.lookupGlobal(name).getOrElse(fail(s"missing global $name")), args*)

  private def call(receiver: Value, name: String, args: Value*): Value = receiver match
    case Value.ForeignV(obj: Value.NamedMethodObj) =>
      invoke(obj.getField(name).getOrElse(fail(s"missing Generator method $name")), args*)
    case other => fail(s"not a Generator: $other")

  private def function(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def source(values: Seq[Value], completed: AtomicBoolean = AtomicBoolean(false)): Value =
    global("generator", function(0) { _ =>
      values.foreach(value => global("suspend", value))
      completed.set(true)
      Value.UnitV
    })

  private def ints(value: Value): List[Long] =
    Prims.unlistPub(value).map {
      case Value.IntV(number) => number
      case other => fail(s"not Int: $other")
    }

  test("synchronous pull applies backpressure and completion is stable") {
    install()
    val completed = AtomicBoolean(false)
    val generator = source(Seq(Value.IntV(1), Value.IntV(2)), completed)
    Thread.sleep(20)
    assert(!completed.get())
    assert(call(generator, "next") == Value.DataV("Some", Vector(Value.IntV(1))))
    assert(!completed.get())
    assert(call(generator, "next") == Value.DataV("Some", Vector(Value.IntV(2))))
    assert(call(generator, "next") == Value.DataV("None", Vector.empty))
    assert(completed.get())
    assert(call(generator, "next") == Value.DataV("None", Vector.empty))
  }

  test("ordered terminals and every combinator preserve portable values") {
    install()
    val base = source((1 to 6).map(Value.IntV(_)))
    val mapped = call(base, "map", function(1) {
      case Value.IntV(number) :: Nil => Value.IntV(number * 10)
    })
    val filtered = call(mapped, "filter", function(1) {
      case Value.IntV(number) :: Nil => Value.BoolV(number % 20 == 0)
    })
    assert(ints(call(call(call(filtered, "drop", Value.IntV(1)), "take", Value.IntV(2)), "toList")) ==
      List(40, 60))

    val visited = collection.mutable.ArrayBuffer.empty[Long]
    val each = source(Seq(Value.IntV(7), Value.IntV(8)))
    assert(call(each, "foreach", function(1) {
      case Value.IntV(number) :: Nil => visited += number; Value.UnitV
    }) == Value.UnitV)
    assert(visited.toList == List(7, 8))

    val flat = call(source(Seq(Value.IntV(1), Value.IntV(2))), "flatMap", function(1) {
      case Value.IntV(number) :: Nil => source(Seq(Value.IntV(number), Value.IntV(number * 10)))
    })
    assert(ints(call(flat, "toList")) == List(1, 10, 2, 20))

    val zipped = call(source(Seq(Value.StrV("a"), Value.StrV("b"), Value.StrV("c"))), "zip",
      source(Seq(Value.IntV(1), Value.IntV(2))))
    assert(Prims.unlistPub(call(zipped, "toList")) == List(
      Value.DataV("Tuple2", Vector(Value.StrV("a"), Value.IntV(1))),
      Value.DataV("Tuple2", Vector(Value.StrV("b"), Value.IntV(2)))))

    val indexed = call(source(Seq(Value.StrV("x"), Value.StrV("y"))), "zipWithIndex")
    assert(Prims.unlistPub(call(indexed, "toList")) == List(
      Value.DataV("Tuple2", Vector(Value.StrV("x"), Value.IntV(0))),
      Value.DataV("Tuple2", Vector(Value.StrV("y"), Value.IntV(1)))))
  }

  test("bounded infinite take cancels its abandoned producer") {
    install()
    val stopped = CountDownLatch(1)
    val emitted = AtomicInteger(0)
    val infinite = global("generator", function(0) { _ =>
      try
        while true do
          global("suspend", Value.IntV(emitted.getAndIncrement().toLong))
      finally stopped.countDown()
      Value.UnitV
    })
    assert(ints(call(call(infinite, "take", Value.IntV(8)), "toList")) == (0L until 8L).toList)
    assert(stopped.await(2, TimeUnit.SECONDS))
    assert(emitted.get() <= 9)
  }

  test("producer errors propagate and large finite conversion is stack safe") {
    install()
    val broken = global("generator", function(0) { _ =>
      global("suspend", Value.IntV(1))
      throw new IllegalArgumentException("boom")
    })
    assert(call(broken, "next") == Value.DataV("Some", Vector(Value.IntV(1))))
    val error = intercept[IllegalStateException](call(broken, "next"))
    assert(error.getMessage.contains("Generator producer failed"))
    assert(error.getMessage.contains("boom"))

    val large = source((1 to 100000).map(Value.IntV(_)))
    assert(Prims.unlistPub(call(large, "toList")).length == 100000)
  }

  test("coroutine is lazy and resume exchanges values until terminal return") {
    install()
    val ran = AtomicBoolean(false)
    val coroutine = global("coroutineCreate", function(0) { _ =>
      ran.set(true)
      val first = global("suspend", Value.StrV("ping")) match
        case Value.StrV(value) => value
        case other => fail(s"unexpected first resume value: $other")
      val second = global("suspend", Value.StrV(s"$first-pong")) match
        case Value.StrV(value) => value
        case other => fail(s"unexpected second resume value: $other")
      Value.StrV(s"$second-final")
    })

    assert(!ran.get())
    assert(global("coroutineResume", coroutine, Value.StrV("ignored")) ==
      Value.DataV("Yielded", Vector(Value.StrV("ping"))))
    assert(ran.get())
    assert(global("coroutineResume", coroutine, Value.StrV("A")) ==
      Value.DataV("Yielded", Vector(Value.StrV("A-pong"))))
    assert(global("coroutineResume", coroutine, Value.StrV("B")) ==
      Value.DataV("Returned", Vector(Value.StrV("B-final"))))
    val completed = intercept[IllegalStateException](
      global("coroutineResume", coroutine, Value.UnitV))
    assert(completed.getMessage.contains("completed or cancelled"))
  }

  test("coroutine errors are terminal values") {
    install()
    val coroutine = global("coroutineCreate", function(0) { _ =>
      global("suspend", Value.IntV(1))
      throw new ssc.SscThrow(Value.DataV(
        "RuntimeException", Vector(Value.StrV("boom"))))
    })

    assert(global("coroutineResume", coroutine, Value.UnitV) ==
      Value.DataV("Yielded", Vector(Value.IntV(1))))
    global("coroutineResume", coroutine, Value.UnitV) match
      case Value.DataV("Errored", Vector(Value.StrV(message))) =>
        assert(message == "boom")
      case other => fail(s"expected Errored, got $other")
    assertThrows[IllegalStateException](
      global("coroutineResume", coroutine, Value.UnitV))
  }

  test("nested coroutine and Generator suspend targets remain isolated") {
    install()
    val generator = source(Seq(Value.IntV(1), Value.IntV(2)))
    val outer = global("coroutineCreate", function(0) { _ =>
      val inner = global("coroutineCreate", function(0) { _ =>
        global("suspend", Value.IntV(99))
        Value.StrV("inner-done")
      })
      global("coroutineResume", inner, Value.UnitV) match
        case Value.DataV("Yielded", fields) if fields.size == 1 =>
          global("suspend", fields.head)
        case other => fail(s"unexpected inner step: $other")
      Value.StrV("outer-done")
    })

    assert(call(generator, "next") == Value.DataV("Some", Vector(Value.IntV(1))))
    assert(global("coroutineResume", outer, Value.UnitV) ==
      Value.DataV("Yielded", Vector(Value.IntV(99))))
    assert(call(generator, "next") == Value.DataV("Some", Vector(Value.IntV(2))))
    assert(global("coroutineResume", outer, Value.UnitV) ==
      Value.DataV("Returned", Vector(Value.StrV("outer-done"))))
  }

  test("coroutine cancellation is idempotent before, during, and after execution") {
    install()
    val neverStarted = AtomicBoolean(false)
    val before = global("coroutineCreate", function(0) { _ =>
      neverStarted.set(true)
      Value.UnitV
    })
    assert(global("coroutineCancel", before) == Value.UnitV)
    assert(global("coroutineCancel", before) == Value.UnitV)
    assert(!neverStarted.get())
    assertThrows[IllegalStateException](
      global("coroutineResume", before, Value.UnitV))

    val advanced = AtomicBoolean(false)
    val during = global("coroutineCreate", function(0) { _ =>
      global("suspend", Value.IntV(1))
      advanced.set(true)
      Value.UnitV
    })
    assert(global("coroutineResume", during, Value.UnitV) ==
      Value.DataV("Yielded", Vector(Value.IntV(1))))
    assert(global("coroutineCancel", during) == Value.UnitV)
    assert(!advanced.get())
    assertThrows[IllegalStateException](
      global("coroutineResume", during, Value.UnitV))

    val finished = global("coroutineCreate", function(0)(_ => Value.IntV(7)))
    assert(global("coroutineResume", finished, Value.UnitV) ==
      Value.DataV("Returned", Vector(Value.IntV(7))))
    assert(global("coroutineCancel", finished) == Value.UnitV)
    assert(global("coroutineCancel", finished) == Value.UnitV)
  }

  test("suspend outside a coroutine or generator fails explicitly") {
    install()
    val error = intercept[IllegalStateException](global("suspend", Value.IntV(1)))
    assert(error.getMessage.contains("outside a coroutine or generator body"))
  }
