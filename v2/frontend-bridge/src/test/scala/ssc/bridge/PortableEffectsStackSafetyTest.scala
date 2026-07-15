package ssc.bridge

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import ssc.*

final class PortableEffectsStackSafetyTest extends AnyFunSuite:
  private val tailDepth = 100000
  private val nonTailDepth = 20000

  private def asInt(value: Value): Long = value match
    case Value.IntV(n) => n
    case other         => fail(s"expected Int, got ${Show.show(other)}")

  private def runManaged1(closure: Value.ClosV, argument: Value): Value =
    Runtime.runManaged(closure.code, Runtime.extend(closure.env, Array(argument)))

  private def tailComputation(remaining: Int, accumulated: Long): Value =
    if remaining == 0 then Value.IntV(accumulated)
    else
      Runtime.letThreadOp(
        PortableEffects.performOneShot("Tick", "step", Nil),
        value => tailComputation(remaining - 1, accumulated + asInt(value)),
      )

  private def sequenceComputation(remaining: Int, count: AtomicInteger): Value =
    if remaining == 0 then Value.IntV(count.get().toLong)
    else
      Runtime.seqThreadOp(
        PortableEffects.performOneShot("Tick", "step", Nil),
        () =>
          count.incrementAndGet()
          sequenceComputation(remaining - 1, count),
      )

  private def nonTailComputation(remaining: Int): Value =
    if remaining == 0 then Value.IntV(0)
    else
      Runtime.seqThreadOp(
        PortableEffects.performOneShot("Tick", "step", Nil),
        () => nonTailComputation(remaining - 1),
      )

  private def tailHandler(stepCount: AtomicInteger, returnCount: AtomicInteger): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("step", IndexedSeq(resume: Value.ClosV)) =>
        stepCount.incrementAndGet()
        Done(Prims.runClos1(resume, Value.IntV(1)))
      case Value.DataV("Return", IndexedSeq(value)) =>
        returnCount.incrementAndGet()
        Done(value)
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )

  test("tail resume and binding/sequence suffixes use no recursive handler stack"):
    val tailSteps = new AtomicInteger(0)
    val tailReturns = new AtomicInteger(0)
    val tailResult = PortableEffects.handle(
      tailComputation(tailDepth, 0),
      tailHandler(tailSteps, tailReturns),
    )

    assert(tailResult == Value.IntV(tailDepth.toLong))
    assert(tailSteps.get() == tailDepth)
    assert(tailReturns.get() == 1)

    val sequenceCount = new AtomicInteger(0)
    val sequenceSteps = new AtomicInteger(0)
    val sequenceReturns = new AtomicInteger(0)
    val sequenceResult = PortableEffects.handle(
      sequenceComputation(tailDepth, sequenceCount),
      tailHandler(sequenceSteps, sequenceReturns),
    )

    assert(sequenceResult == Value.IntV(tailDepth.toLong))
    assert(sequenceCount.get() == tailDepth)
    assert(sequenceSteps.get() == tailDepth)
    assert(sequenceReturns.get() == 1)

  test("non-tail handler composition is heap-framed and Return runs once"):
    val stepCount = new AtomicInteger(0)
    val returnCount = new AtomicInteger(0)
    val handler = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("step", IndexedSeq(resume: Value.ClosV)) =>
        stepCount.incrementAndGet()
        val resumed = Prims.runClos1(resume, Value.IntV(1))
        Done(Prims.arithOp("+", Value.IntV(1), resumed))
      case Value.DataV("Return", IndexedSeq(value)) =>
        returnCount.incrementAndGet()
        Done(Prims.arithOp("+", value, Value.IntV(7)))
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )

    val result = PortableEffects.handle(nonTailComputation(nonTailDepth), handler)

    assert(result == Value.IntV(nonTailDepth.toLong + 7))
    assert(stepCount.get() == nonTailDepth)
    assert(returnCount.get() == 1)

  test("one-shot continuation is claimed eagerly before a deferred resume is returned"):
    val order = ArrayBuffer.empty[String]
    val computation = Runtime.letThreadOp(
      PortableEffects.performOneShot("One", "op", Nil),
      value =>
        order += "next"
        value,
    )
    val handler = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("op", IndexedSeq(resume: Value.ClosV)) =>
        Prims.runClos1(resume, Value.IntV(1))
        order += "after-first"
        try Prims.runClos1(resume, Value.IntV(2))
        catch
          case failure: ControlRunFailure =>
            order += "rejected"
            throw failure
        order += "after-second"
        Done(Value.UnitV)
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )

    val failure = intercept[ControlRunFailure](PortableEffects.handle(computation, handler))

    assert(failure.rejection ==
      ResumeRejected.AlreadyResumed(OperationId(EffectId("One"), "op")))
    assert(order.toVector == Vector("next", "after-first", "rejected"))

  test("copying the private carrier label cannot forge a resume request"):
    val handled = new AtomicInteger(0)
    val handler = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("__resume__", IndexedSeq(resume: Value.ClosV)) =>
        handled.incrementAndGet()
        Done(Value.StrV("ordinary operation"))
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )

    val result = PortableEffects.handle(
      PortableEffects.perform("ssc.control.__resume__", Nil),
      handler,
    )

    assert(result == Value.StrV("ordinary operation"))
    assert(handled.get() == 1)

  test("escaped reusable resumes run sequentially and concurrently without leaking requests"):
    val captured = new AtomicReference[Value.ClosV]()
    val handler = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("op", IndexedSeq(resume: Value.ClosV)) =>
        captured.set(resume)
        Done(Value.StrV("captured"))
      case Value.DataV("Return", IndexedSeq(Value.IntV(value))) =>
        Done(Value.IntV(value + 10))
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )

    assert(PortableEffects.handle(
      PortableEffects.perform("Raw.op", Nil),
      handler,
    ) == Value.StrV("captured"))

    val resume = captured.get()
    assert(runManaged1(resume, Value.IntV(1)) == Value.IntV(11))
    assert(PortableEffects.completeManaged(Prims.runClos1(resume, Value.IntV(2))) ==
      Value.IntV(12))

    val callers = 32
    val ready = new CountDownLatch(callers)
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(callers)
    val results = new ConcurrentLinkedQueue[Value]()
    val pool = Executors.newFixedThreadPool(callers)
    try
      (0 until callers).foreach { index =>
        pool.execute(() =>
          ready.countDown()
          start.await()
          try results.add(runManaged1(resume, Value.IntV(index.toLong)))
          finally done.countDown())
      }
      assert(ready.await(10, TimeUnit.SECONDS))
      start.countDown()
      assert(done.await(10, TimeUnit.SECONDS))
    finally
      pool.shutdownNow()

    val collected = results.iterator().asScala.toVector
    assert(collected.size == callers)
    assert(collected.toSet == (0 until callers).map(i => Value.IntV(i.toLong + 10)).toSet)
    assert(collected.forall {
      case Value.DataV("Op", _) => false
      case _                     => true
    })

  test("escaped one-shot resume claims at its later call site"):
    val captured = new AtomicReference[Value.ClosV]()
    val handler = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("op", IndexedSeq(resume: Value.ClosV)) =>
        captured.set(resume)
        Done(Value.StrV("captured"))
      case Value.DataV("Return", IndexedSeq(value)) => Done(value)
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )

    assert(PortableEffects.handle(
      PortableEffects.performOneShot("One", "op", Nil),
      handler,
    ) == Value.StrV("captured"))

    val resume = captured.get()
    assert(PortableEffects.completeManaged(Prims.runClos1(resume, Value.IntV(1))) ==
      Value.IntV(1))
    val failure = intercept[ControlRunFailure](Prims.runClos1(resume, Value.IntV(2)))
    assert(failure.rejection ==
      ResumeRejected.AlreadyResumed(OperationId(EffectId("One"), "op")))

  test("a private resume encountered in Handle mode retains both handlers"):
    val resumeA = new AtomicReference[Value.ClosV]()
    val resumeB = new AtomicReference[Value.ClosV]()
    val returnA = new AtomicInteger(0)
    val returnB = new AtomicInteger(0)

    val handlerA = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("captureA", IndexedSeq(resume: Value.ClosV)) =>
        resumeA.set(resume)
        Done(Value.StrV("captured A"))
      case Value.DataV("Return", IndexedSeq(Value.IntV(value))) =>
        returnA.incrementAndGet()
        Done(Value.IntV(value + 10))
      case other => throw new RuntimeException(s"unexpected A event: ${Show.show(other)}")
    )
    val handlerB = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("captureB", IndexedSeq(resume: Value.ClosV)) =>
        resumeB.set(resume)
        Done(Value.StrV("captured B"))
      case Value.DataV("Return", IndexedSeq(Value.IntV(value))) =>
        returnB.incrementAndGet()
        Done(Value.IntV(value * 2))
      case other => throw new RuntimeException(s"unexpected B event: ${Show.show(other)}")
    )

    assert(PortableEffects.handle(
      PortableEffects.perform("Outer.captureA", Nil),
      handlerA,
    ) == Value.StrV("captured A"))
    assert(PortableEffects.handle(
      PortableEffects.perform("Inner.captureB", Nil),
      handlerB,
    ) == Value.StrV("captured B"))

    val innerRequest = Prims.runClos1(resumeB.get(), Value.IntV(3))
    val outerRequest = Prims.runClos1(resumeA.get(), innerRequest)
    val result = PortableEffects.completeManaged(outerRequest)

    assert(result == Value.IntV(16))
    assert(returnA.get() == 1)
    assert(returnB.get() == 1)

  test("VM primitive arguments thread multiple Ops in order and match direct ASM"):
    val primitiveName = "test.stackSafety.combine4"
    val first = Term.Prim("effect.perform", List(Term.Lit(Const.CStr("Order.first"))))
    val second = Term.Prim("effect.perform", List(Term.Lit(Const.CStr("Order.second"))))
    val computation = Term.Prim(primitiveName, List(
      first,
      Term.Lit(Const.CInt(9)),
      second,
      Term.Lit(Const.CInt(8)),
    ))
    val handler = Term.Lam(1, Term.Match(
      Term.Local(0),
      List(
        Arm("first", 1, Term.Seq(List(
          Term.Prim("io.println", List(Term.Lit(Const.CStr("first")))),
          Term.App(Term.Local(0), List(Term.Lit(Const.CInt(1)))),
        ))),
        Arm("second", 1, Term.Seq(List(
          Term.Prim("io.println", List(Term.Lit(Const.CStr("second")))),
          Term.App(Term.Local(0), List(Term.Lit(Const.CInt(2)))),
        ))),
        Arm("Return", 1, Term.Local(0)),
      ),
      None,
    ))
    val program = Program(Nil, Term.Prim("effect.handle", List(computation, handler)))

    // A FastCode primitive would call its target directly with raw Ops. Known
    // may-Op arguments must force the effect-aware compiler path instead.
    assert(FastCode.tryFC(
      computation,
      collection.mutable.HashMap.empty[String, Value],
    ).isEmpty)

    def runLane(run: => Value): (Value, String, Int) =
      val calls = new AtomicInteger(0)
      V2PluginRegistry.register(primitiveName, args =>
        calls.incrementAndGet()
        println("primitive")
        args match
          case List(
                Value.IntV(a),
                Value.IntV(b),
                Value.IntV(c),
                Value.IntV(d),
              ) => Value.IntV(a * 1000 + b * 100 + c * 10 + d)
          case _ => Value.IntV(-1)
      )
      val output = new java.io.ByteArrayOutputStream
      val result = Console.withOut(output)(run)
      (result, output.toString("UTF-8").stripTrailing(), calls.get())

    val vm = runLane(Runtime.runManaged(
      Compiler.compile(program),
      Runtime.emptyEnv,
    ))

    val asmProgram = ssc.bytecode.OpAnfNative.lift(program)
    Emit.globalsRef = Map.empty
    val asmBytes = ssc.bytecode.JvmByteGen.emitProgram(asmProgram)
    val asm = runLane {
      try ssc.bytecode.JvmByteGen.runProgram(asmBytes)
      catch case e: java.lang.reflect.InvocationTargetException =>
        throw Option(e.getCause).getOrElse(e)
    }

    assert(vm == (Value.IntV(1928), "first\nsecond\nprimitive", 1))
    assert(asm == vm)

  test("managed completion is identity for every ordinary value and public Op"):
    val ordinary = Value.DataV("Result", Vector(Value.IntV(1)))
    val publicOp = PortableEffects.perform("User.op", List(Value.IntV(2)))

    assert(PortableEffects.completeManaged(ordinary) eq ordinary)
    assert(PortableEffects.completeManaged(publicOp) eq publicOp)

  test("zero resume and matching-arm failures keep their observable behavior"):
    val zeroResume = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.DataV("step", IndexedSeq(_: Value.ClosV)) => Done(Value.IntV(42))
      case other => throw new RuntimeException(s"unexpected effect event: ${Show.show(other)}")
    )
    assert(PortableEffects.handle(
      PortableEffects.performOneShot("Tick", "step", Nil),
      zeroResume,
    ) == Value.IntV(42))

    val boom = new RuntimeException("handler arm failed")
    val failing = Value.ClosV(Runtime.emptyEnv, 1, _ => throw boom)
    assert(intercept[RuntimeException](PortableEffects.handle(
      PortableEffects.perform("Tick.step", Nil),
      failing,
    )) eq boom)
