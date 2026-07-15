package ssc.plugin.effects

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ListBuffer
import ssc.{Done, PortableEffects, Prims, Runtime, V2EffectContext, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class EffectRunnersNativePluginTest extends AnyFunSuite:
  private def install(): Unit =
    NativePluginHost.installProviders(List(EffectRunnersNativePlugin()))

  private def invoke(fn: Value, args: Value*): Value = fn match
    case clos: Value.ClosV =>
      val env = if args.isEmpty then clos.env else Runtime.extend(clos.env, args.toArray)
      Runtime.run(clos.code, env)
    case Value.ForeignV(obj: Value.NamedMethodObj) =>
      invoke(obj.getField("apply").getOrElse(fail("missing apply")), args*)
    case other => fail(s"not callable: $other")

  private def global(name: String, args: Value*): Value =
    invoke(V2PluginRegistry.lookupGlobal(name).getOrElse(fail(s"missing global $name")), args*)

  private def function(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def async(operation: String, args: Value*): Value =
    V2EffectContext.peek("Async").getOrElse(fail("Async handler is not active"))(
      operation, args.toList)

  private def list(values: List[Value]): Value =
    values.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, tail) =>
      Value.DataV("Cons", Vector(value, tail))
    }

  private def ints(value: Value): List[Long] =
    Prims.unlistPub(value).map {
      case Value.IntV(number) => number
      case other => fail(s"not Int: $other")
    }

  private def handleAsk(computation: Value)(reply: Long => Long): Value =
    PortableEffects.handle(computation, Runtime.handlerPartialFunction {
      case Value.DataV("ask", IndexedSeq(Value.IntV(seed), resume: Value.ClosV)) =>
        invoke(resume, Value.IntV(reply(seed)))
      case Value.DataV("Return", IndexedSeq(value)) => value
    })

  test("runAsync evaluates futures and parallel callbacks deterministically") {
    install()
    val order = ListBuffer.empty[Long]
    val result = global("runAsync", function(0) { _ =>
      val future = async("async", function(0) { _ => order += 1L; Value.IntV(21) })
      val awaited = async("await", future)
      val values = async("parallel", list(List(3L, 1L, 2L).map { number =>
        function(0) { _ => order += number; Value.IntV(number * 10) }
      }))
      Value.DataV("Tuple2", Vector(awaited, values))
    })
    assert(result match
      case Value.DataV("Tuple2", IndexedSeq(Value.IntV(21), values)) =>
        ints(values) == List(30, 10, 20)
      case _ => false)
    assert(order.toList == List(1, 3, 1, 2))
    assert(V2EffectContext.peek("Async").isEmpty)
  }

  test("runAsyncParallel starts every task and joins in declared order") {
    install()
    val started = CountDownLatch(3)
    val gates = Vector.fill(3)(CountDownLatch(1))
    val completed = new java.util.concurrent.ConcurrentLinkedQueue[Long]()
    val coordinator = Thread.ofVirtual().start { () =>
      assert(started.await(2, TimeUnit.SECONDS))
      gates(2).countDown()
      while !completed.contains(30L) do Thread.onSpinWait()
      gates(1).countDown()
      while !completed.contains(20L) do Thread.onSpinWait()
      gates(0).countDown()
    }
    val callbacks = (0 until 3).toList.map { index =>
      function(0) { _ =>
        started.countDown()
        assert(gates(index).await(2, TimeUnit.SECONDS))
        val value = (index + 1L) * 10L
        completed.add(value)
        Value.IntV(value)
      }
    }
    val result = global("runAsyncParallel", function(0) { _ =>
      async("parallel", list(callbacks))
    })
    coordinator.join()
    assert(ints(result) == List(10, 20, 30))
    assert(completed.toArray.toList == List(30L, 20L, 10L))
  }

  test("nested runners restore the outer handler and failures reach await") {
    install()
    val result = global("runAsync", function(0) { _ =>
      val inner = global("runAsync", function(0) { _ => Value.IntV(7) })
      assert(V2EffectContext.peek("Async").nonEmpty)
      Value.IntV(inner.asInstanceOf[Value.IntV].n + 1)
    })
    assert(result == Value.IntV(8))
    assert(V2EffectContext.peek("Async").isEmpty)

    val error = intercept[IllegalStateException] {
      global("runAsync", function(0) { _ =>
        val failed = async("async", function(0) { _ => throw new IllegalArgumentException("boom") })
        async("await", failed)
      })
    }
    assert(error.getMessage.contains("Async.await failed: boom"))
  }

  test("delay validation and recvFrom remain bounded provider operations") {
    install()
    val socket = Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = this
      def getField(name: String): Option[Value] = name match
        case "recv" => Some(function(0)(_ => Value.StrV("message")))
        case _ => None
    )
    val result = global("runAsync", function(0) { _ =>
      assert(async("delay", Value.IntV(0)) == Value.UnitV)
      async("recvFrom", socket)
    })
    assert(result == Value.StrV("message"))

    intercept[IllegalArgumentException] {
      global("runAsync", function(0) { _ => async("delay", Value.StrV("bad")) })
    }
    intercept[IllegalArgumentException] {
      global("runAsync", function(0) { _ => async("await", Value.IntV(1)) })
    }
  }

  test("Logger and Stream runners forward foreign effects and reinstall after resume") {
    install()

    val loggerComputation = Runtime.letThreadOp(
      PortableEffects.perform("Ask.ask", List(Value.IntV(7))),
      answer => Runtime.letThreadOp(
        PortableEffects.perform("Logger.info", List(Value.StrV("after ask"))),
        _ => answer match
          case Value.IntV(number) => Value.IntV(number + 1)
          case other => fail(s"unexpected Ask reply: $other")))
    val loggerResidual = global("runLoggerToList", function(0)(_ => loggerComputation))
    val loggerResult = handleAsk(loggerResidual)(_ * 5)
    assert(loggerResult match
      case Value.DataV("Tuple2", IndexedSeq(Value.IntV(36), messages)) =>
        Prims.unlistPub(messages) == List(
          Value.DataV("Tuple2", Vector(
            Value.StrV("info"), Value.StrV("after ask"))))
      case _ => false)

    val streamComputation = Runtime.letThreadOp(
      PortableEffects.perform("Ask.ask", List(Value.IntV(4))),
      answer => Runtime.letThreadOp(
        PortableEffects.perform("Stream.emit", List(answer)),
        _ => answer match
          case Value.IntV(number) => Value.IntV(number + 1)
          case other => fail(s"unexpected Ask reply: $other")))
    val streamResidual = global("runStream", function(0)(_ => streamComputation))
    val streamResult = handleAsk(streamResidual)(_ + 6)
    assert(streamResult match
      case Value.DataV("Tuple2", IndexedSeq(
          Value.ForeignV(source: Value.NamedMethodObj), Value.IntV(11))) =>
        val values = source.getField("runToList")
          .map(invoke(_))
          .getOrElse(fail("collected source has no runToList"))
        ints(values) == List(10)
      case _ => false)
  }

  test("a selected native runner arm propagates its body failure") {
    install()
    val failingResume = Value.ClosV(Runtime.emptyEnv, 1, _ =>
      throw new IllegalStateException("logger suffix failed"))
    val computation = Value.DataV("Op", Vector(
      Value.StrV("Logger.info"),
      Value.StrV("before failure"),
      failingResume))

    val failure = intercept[IllegalStateException] {
      global("runLoggerToList", function(0)(_ => computation))
    }
    assert(failure.getMessage == "logger suffix failed")
  }
