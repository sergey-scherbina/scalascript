package ssc.bridge

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import ssc.*

final class PortableEffectsOneShotTest extends AnyFunSuite:
  private val operation = OperationId(EffectId("One"), "op")

  private def continuation(value: Value): Value.ClosV = value match
    case Value.DataV("Op", IndexedSeq(_, _, k: Value.ClosV)) => k
    case other => fail(s"expected Op continuation, got ${Show.show(other)}")

  test("one-shot perform rejects the second sequential resume structurally"):
    val k = continuation(PortableEffects.performOneShot("One", "op", Nil))

    assert(Prims.runClos1(k, Value.IntV(1)) == Value.IntV(1))
    val failure = intercept[ControlRunFailure](Prims.runClos1(k, Value.IntV(2)))
    assert(failure.rejection == ResumeRejected.AlreadyResumed(operation))
    assert(failure.code == "ONESHOT_VIOLATION")
    assert(failure.diagnosticMessage ==
      "One-shot violation: One.op resumed more than once")
    assert(failure.rendered ==
      "error [ONESHOT_VIOLATION]: One-shot violation: One.op resumed more than once")

  test("raw portable perform remains reusable"):
    val k = continuation(PortableEffects.perform("One.op", Nil))

    assert(Prims.runClos1(k, Value.IntV(1)) == Value.IntV(1))
    assert(Prims.runClos1(k, Value.IntV(2)) == Value.IntV(2))

  test("one-shot claim is linearizable and losing callers execute no suffix"):
    val suffixRuns = new AtomicInteger(0)
    val threaded = Runtime.letThreadOp(
      PortableEffects.performOneShot("One", "op", Nil),
      value =>
        suffixRuns.incrementAndGet()
        value)
    val k = continuation(threaded)
    val callers = 64
    val ready = new CountDownLatch(callers)
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(callers)
    val results = new ConcurrentLinkedQueue[Either[ControlRunFailure, Value]]()
    val pool = Executors.newFixedThreadPool(callers)

    try
      (0 until callers).foreach { index =>
        pool.execute(() =>
          ready.countDown()
          start.await()
          try results.add(Right(Prims.runClos1(k, Value.IntV(index))))
          catch case failure: ControlRunFailure => results.add(Left(failure))
          finally done.countDown())
      }
      assert(ready.await(10, TimeUnit.SECONDS))
      start.countDown()
      assert(done.await(10, TimeUnit.SECONDS))
    finally
      pool.shutdownNow()

    val collected = results.iterator().asScala.toVector
    assert(collected.count(_.isRight) == 1)
    val failures = collected.collect { case Left(failure) => failure }
    assert(failures.size == callers - 1)
    assert(failures.forall(_.rejection == ResumeRejected.AlreadyResumed(operation)))
    assert(suffixRuns.get() == 1)

  test("ScalaScript try catch cannot intercept a one-shot contract failure"):
    val k = continuation(PortableEffects.performOneShot("One", "op", Nil))
    val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      Prims.runClos1(k, Value.IntV(1))
      Done(Prims.runClos1(k, Value.IntV(2))))
    val handler = Value.ClosV(Runtime.emptyEnv, 1, _ => Done(Value.StrV("caught")))

    val failure = intercept[ControlRunFailure](
      Prims.resolve("__tryCatch__")(List(body, handler)))
    assert(failure.rejection == ResumeRejected.AlreadyResumed(operation))
