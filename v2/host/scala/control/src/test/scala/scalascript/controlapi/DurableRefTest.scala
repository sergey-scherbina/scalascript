package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*

final class DurableRefTest extends AnyFunSuite:
  // A test provider that stores the value as an int-encoded opaque reference.
  private def memRef(value: Int): DurableRef[Int] =
    DurableRef.of[Int]("mem", DurableCodec.int.encode(value))

  private final class CountingResolver extends Restore.Resolver:
    var calls = 0
    def resolve[A](ref: DurableRef[A]): A =
      calls += 1
      assert(ref.providerId == "mem")
      DurableCodec.int.decode(ref.opaqueReference).asInstanceOf[A]

  private val addAfterResolve =
    new ResumeStateMachine[DurableRef[Int], Int, Restore, Int]:
      override def resume(state: DurableRef[Int], input: Int): Eff[Restore, Int] =
        Restore.resolve(state).map(value => value + input)

  private type RefSaved = SavedContinuation.Aux[Int, Restore, Int]

  private def freezeSavable(
      continuation: Continuation[Int, Restore, Int]
  ): RefSaved =
    Eff.runPure(
      handle[Save.type, Nothing, RefSaved, RefSaved](continuation.save())(
        new Handler[Save.type, Nothing, RefSaved, RefSaved]:
          val effect: EffectKey[Save.type] = Save.key
          def onReturn(value: RefSaved): Eff[Nothing, RefSaved] = Eff.pure(value)
          def onOperation[X](
              operation: Operation[Save.type, X],
              resumption: Resumption[X, Nothing, RefSaved]
          ): Eff[Nothing, RefSaved] =
            throw new AssertionError("DurableRef savable save unexpectedly rejected")
      )
    )

  test("DurableRef codec round-trips inert reference data without resolving"):
    val codec = DurableRef.codec[Int]
    val decoded = codec.decode(codec.encode(memRef(99)))
    assert(decoded.providerId == "mem")
    // the reference is still opaque data; nothing was resolved.
    assert(DurableCodec.int.decode(decoded.opaqueReference) == 99)

  test("a savable frame that is a DurableRef resolves post-admission"):
    val resolver = new CountingResolver
    val continuation: Continuation[Int, Restore, Int] =
      Continuation.savable(memRef(40), addAfterResolve, DurableRef.codec[Int])
    val saved = freezeSavable(continuation)
    assert(resolver.calls == 0) // save does not resolve

    assert(Eff.runPure(Restore.withResolver(resolver)(saved.run(2))) == 42)
    assert(Eff.runPure(Restore.withResolver(resolver)(saved.run(5))) == 45)
    assert(resolver.calls == 2) // once per admitted run, resolved independently

  test("withResolver resolves once per resolve in a run"):
    val resolver = new CountingResolver
    val twiceMachine =
      new ResumeStateMachine[DurableRef[Int], Int, Restore, Int]:
        override def resume(state: DurableRef[Int], input: Int): Eff[Restore, Int] =
          Restore
            .resolve(state)
            .flatMap(first =>
              Restore.resolve(state).map(second => first + second + input)
            )
    val continuation: Continuation[Int, Restore, Int] =
      Continuation.savable(memRef(10), twiceMachine, DurableRef.codec[Int])
    val saved = freezeSavable(continuation)
    assert(Eff.runPure(Restore.withResolver(resolver)(saved.run(1))) == 21) // 10+10+1
    assert(resolver.calls == 2) // two resolves in the one run

  test("admitLocally rejects a run that resolves a DurableRef"):
    val continuation: Continuation[Int, Restore, Int] =
      Continuation.savable(memRef(1), addAfterResolve, DurableRef.codec[Int])
    val saved = freezeSavable(continuation)
    intercept[IllegalStateException](
      Eff.runPure(Restore.admitLocally(saved.run(1)))
    )

  test("a capsule whose frame is a DurableRef decodes inert and resolves on run"):
    val resolver = new CountingResolver
    val point: ResumePoint[DurableRef[Int], Int, Restore, Int] =
      ResumePoint.define("ref-point", addAfterResolve, DurableRef.codec[Int])
    val capsule = point.freeze(memRef(100))
    val transported = DurableCapsule.decode(capsule.encode())
    assert(resolver.calls == 0) // decoding the capsule contacts no resource

    val saved = point.restore(transported)
    assert(resolver.calls == 0) // restore admits but does not resolve
    assert(Eff.runPure(Restore.withResolver(resolver)(saved.run(1))) == 101)
    assert(resolver.calls == 1)
