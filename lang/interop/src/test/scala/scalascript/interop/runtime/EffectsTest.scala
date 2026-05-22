package scalascript.interop.runtime

import org.scalatest.funsuite.AnyFunSuite

class EffectsTest extends AnyFunSuite:

  test("runEffects — success path returns Right"):
    val r = Effects.runEffects(Effects.effect(1 + 2))
    assert(r == Right(3), s"expected Right(3), got: $r")

  test("runEffects — generic throwable surfaces as Crash"):
    val boom = new RuntimeException("kaboom")
    val r = Effects.runEffects(Effects.effect(throw boom))
    r match
      case Left(Effects.EffectError.Crash(t)) =>
        assert(t eq boom, "Crash should carry the original throwable")
      case other => fail(s"expected Crash, got: $other")

  test("runEffects — class-name heuristic recognises UnhandledEffect"):
    // We don't depend on the real runtime here — simulate the wire shape
    // (an exception whose class name contains 'UnhandledEffect').
    class UnhandledEffectStub(msg: String) extends RuntimeException(msg)
    val r = Effects.runEffects(Effects.effect(throw UnhandledEffectStub("readFile")))
    r match
      case Left(Effects.EffectError.UnhandledEffect(name)) =>
        assert(name == "readFile", s"expected name 'readFile', got '$name'")
      case other => fail(s"expected UnhandledEffect, got: $other")

  test("effect — deferred evaluation (thunk not invoked until runEffects)"):
    var invoked = false
    val thunk = Effects.effect { invoked = true; 42 }
    assert(!invoked, "effect must not invoke its body until runEffects")
    val r = Effects.runEffects(thunk)
    assert(invoked, "runEffects must invoke the thunk")
    assert(r == Right(42))

  test("runEffectsAsync — Future success carries the value"):
    import scala.concurrent.{Await, ExecutionContext}
    import scala.concurrent.duration.*
    given ExecutionContext = ExecutionContext.global
    val f = Effects.runEffectsAsync(Effects.effect(7 * 6))
    assert(Await.result(f, 5.seconds) == 42)

  test("runEffectsAsync — Future fails with EffectExecutionException carrying the cause"):
    import scala.concurrent.{Await, ExecutionContext}
    import scala.concurrent.duration.*
    given ExecutionContext = ExecutionContext.global
    val f = Effects.runEffectsAsync(Effects.effect(throw new RuntimeException("bad")))
    val thrown = intercept[Effects.EffectExecutionException](Await.result(f, 5.seconds))
    thrown.error match
      case Effects.EffectError.Crash(_) => ()
      case other => fail(s"expected Crash wrapped in EffectExecutionException, got: $other")
