package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*
import scalascript.controlapi.ControlTestFixtures.*

final class HandlerSemanticsTest extends AnyFunSuite:
  test("a reusable handler may resume zero, one, or many times"):
    def exercise(values: Vector[Int]): (Vector[Int], Int, Int) =
      var suffixCalls = 0
      var returnCalls = 0
      val body: Eff[Choice.type, Int] =
        perform(Choose(values)).flatMap { value =>
          Eff.defer {
            suffixCalls += 1
            Eff.pure(value * 10)
          }
        }

      val handled = handle[Choice.type, Nothing, Int, Vector[Int]](body)(
        new Handler[Choice.type, Nothing, Int, Vector[Int]]:
          override val effect: EffectKey[Choice.type] = Choice.key

          override def onReturn(value: Int): Eff[Nothing, Vector[Int]] =
            returnCalls += 1
            Eff.pure(Vector(value))

          override def onOperation[X](
              operation: Operation[Choice.type, X],
              resumption: Resumption[X, Nothing, Vector[Int]]
          ): Eff[Nothing, Vector[Int]] =
            operation match
              case Choose(alternatives) =>
                alternatives.foldLeft(
                  Eff.pure(Vector.empty[Int]): Eff[Nothing, Vector[Int]]
                ) { (accumulated, alternative) =>
                  accumulated.flatMap { prefix =>
                    resumeReusable(resumption, alternative).map { suffix =>
                      prefix ++ suffix
                    }
                  }
                }
      )

      (Eff.runPure(handled), suffixCalls, returnCalls)

    assert(exercise(Vector.empty) == ((Vector.empty, 0, 0)))
    assert(exercise(Vector(4)) == ((Vector(40), 1, 1)))
    assert(exercise(Vector(1, 2, 3)) == ((Vector(10, 20, 30), 3, 3)))

  test("deep handlers reinstall themselves around every resumed suffix"):
    var operationCalls = 0
    var returnCalls = 0
    val body: Eff[ReadEffect.type, Int] =
      perform(Read).flatMap { first =>
        perform(Read).map(second => first + second)
      }

    val handled = handle[ReadEffect.type, Nothing, Int, Int](body)(
      new Handler[ReadEffect.type, Nothing, Int, Int]:
        override val effect: EffectKey[ReadEffect.type] = ReadEffect.key

        override def onReturn(value: Int): Eff[Nothing, Int] =
          returnCalls += 1
          Eff.pure(value)

        override def onOperation[X](
            operation: Operation[ReadEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case Read =>
              operationCalls += 1
              val supplied = if operationCalls == 1 then 10 else 20
              resumeReusable(resumption, supplied)
    )

    assert(Eff.runPure(handled) == 30)
    assert(operationCalls == 2)
    assert(returnCalls == 1)

  test("deep handling remains stack safe across a long operation chain"):
    val depth = 100_000
    var operationCalls = 0
    var returnCalls = 0

    def loop(remaining: Int): Eff[TickEffect.type, Int] =
      if remaining == 0 then Eff.pure(0)
      else
        perform(Tick).flatMap { _ =>
          Eff.defer(loop(remaining - 1)).map(_ + 1)
        }

    val handled = handle[TickEffect.type, Nothing, Int, Int](loop(depth))(
      new Handler[TickEffect.type, Nothing, Int, Int]:
        override val effect: EffectKey[TickEffect.type] = TickEffect.key

        override def onReturn(value: Int): Eff[Nothing, Int] =
          returnCalls += 1
          Eff.pure(value)

        override def onOperation[X](
            operation: Operation[TickEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case Tick =>
              operationCalls += 1
              resumeReusable(resumption, ())
    )

    assert(Eff.runPure(handled) == depth)
    assert(operationCalls == depth)
    assert(returnCalls == 1)

  test("unhandled residual operations forward without losing the inner handler"):
    val trace = Vector.newBuilder[String]
    var readCalls = 0
    val body: Eff[ReadEffect.type | TraceEffect.type, Int] =
      perform(Trace("before")).flatMap[ReadEffect.type | TraceEffect.type, Int] {
        _ =>
          perform(Read).flatMap[ReadEffect.type | TraceEffect.type, Int] {
            value =>
              perform(Trace(s"after:$value")).map(_ => value)
          }
      }

    val withoutRead: Eff[TraceEffect.type, Int] =
      handle[ReadEffect.type, TraceEffect.type, Int, Int](body)(
        new Handler[ReadEffect.type, TraceEffect.type, Int, Int]:
          override val effect: EffectKey[ReadEffect.type] = ReadEffect.key

          override def onReturn(value: Int): Eff[TraceEffect.type, Int] =
            Eff.pure(value)

          override def onOperation[X](
              operation: Operation[ReadEffect.type, X],
              resumption: Resumption[X, TraceEffect.type, Int]
          ): Eff[TraceEffect.type, Int] =
            operation match
              case Read =>
                readCalls += 1
                resumeReusable(resumption, 7)
      )

    val fullyHandled =
      handle[TraceEffect.type, Nothing, Int, Int](withoutRead)(
        new Handler[TraceEffect.type, Nothing, Int, Int]:
          override val effect: EffectKey[TraceEffect.type] = TraceEffect.key

          override def onReturn(value: Int): Eff[Nothing, Int] =
            Eff.pure(value)

          override def onOperation[X](
              operation: Operation[TraceEffect.type, X],
              resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case Trace(message) =>
                trace += message
                resumeReusable(resumption, ())
      )

    assert(Eff.runPure(fullyHandled) == 7)
    assert(readCalls == 1)
    assert(trace.result() == Vector("before", "after:7"))

  test("the same singleton owner and descriptor match across distinct keys"):
    var operationCalls = 0
    val body = perform(OwnerRequest(PrimaryOwner.equivalentKey, "read"))
    val handled = handle[PrimaryOwner.type, Nothing, Int, Int](body)(
      new Handler[PrimaryOwner.type, Nothing, Int, Int]:
        override val effect: EffectKey[PrimaryOwner.type] = PrimaryOwner.key

        override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

        override def onOperation[X](
            operation: Operation[PrimaryOwner.type, X],
            resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case OwnerRequest(_, _) =>
                operationCalls += 1
                resumeReusable(resumption, 41)
    )

    assert(Eff.runPure(handled) == 41)
    assert(operationCalls == 1)

  test("distinct singleton owners with the same descriptor forward"):
    var primaryCalls = 0
    var secondaryCalls = 0
    val body: Eff[PrimaryOwner.type | SecondaryOwner.type, Int] =
      perform(OwnerRequest(SecondaryOwner.key, "read"))

    val forwarded: Eff[SecondaryOwner.type, Int] =
      handle[PrimaryOwner.type, SecondaryOwner.type, Int, Int](body)(
        new Handler[PrimaryOwner.type, SecondaryOwner.type, Int, Int]:
          override val effect: EffectKey[PrimaryOwner.type] = PrimaryOwner.key

          override def onReturn(value: Int): Eff[SecondaryOwner.type, Int] =
            Eff.pure(value)

          override def onOperation[X](
              operation: Operation[PrimaryOwner.type, X],
              resumption: Resumption[X, SecondaryOwner.type, Int]
          ): Eff[SecondaryOwner.type, Int] =
            primaryCalls += 1
            throw new AssertionError(
              s"distinct owner was handled as ${operation.id}"
            )
      )

    val handled =
      handle[SecondaryOwner.type, Nothing, Int, Int](forwarded)(
        new Handler[SecondaryOwner.type, Nothing, Int, Int]:
          override val effect: EffectKey[SecondaryOwner.type] =
            SecondaryOwner.key

          override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

          override def onOperation[X](
              operation: Operation[SecondaryOwner.type, X],
              resumption: Resumption[X, Nothing, Int]
          ): Eff[Nothing, Int] =
            operation match
              case OwnerRequest(_, _) =>
                secondaryCalls += 1
                resumeReusable(resumption, 73)
      )

    assert(Eff.runPure(handled) == 73)
    assert(primaryCalls == 0)
    assert(secondaryCalls == 1)

  test("the same singleton owner with a conflicting descriptor is rejected"):
    var operationCalls = 0
    val body = perform(OwnerRequest(PrimaryOwner.conflictingKey, "read"))
    val handled = handle[PrimaryOwner.type, Nothing, Int, Int](body)(
      new Handler[PrimaryOwner.type, Nothing, Int, Int]:
        override val effect: EffectKey[PrimaryOwner.type] = PrimaryOwner.key

        override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

        override def onOperation[X](
            operation: Operation[PrimaryOwner.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operationCalls += 1
          throw new AssertionError(
            s"conflicting descriptor was handled as ${operation.id}"
          )
    )

    val rejected = intercept[IllegalArgumentException]:
      Eff.runPure(handled)

    assert(rejected.getMessage.contains(PrimaryOwner.descriptor.toString))
    assert(rejected.getMessage.contains(PrimaryOwner.conflictingKey.id.toString))
    assert(operationCalls == 0)

  test("perform snapshots a user-defined effect getter exactly once"):
    val operation = new ChangingKeyOperation
    val body = perform(operation)
    val handled = handle[SnapshotEffect.type, Nothing, Int, Int](body)(
      new Handler[SnapshotEffect.type, Nothing, Int, Int]:
        override val effect: EffectKey[SnapshotEffect.type] =
          SnapshotEffect.key

        override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

        override def onOperation[X](
            operation: Operation[SnapshotEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case _: ChangingKeyOperation =>
              resumeReusable(resumption, 17)
    )

    assert(Eff.runPure(handled) == 17)
    assert(operation.effectReads == 1)

  test("perform rejects a null operation key before constructing a request"):
    val rejected = intercept[NullPointerException]:
      perform(NullKeyOperation)

    assert(rejected.getMessage == "operation effect key")

  test("perform rejects an operation id owned by another descriptor"):
    val rejected = intercept[IllegalArgumentException]:
      perform(WrongDescriptorOperation)

    assert(rejected.getMessage.contains("test.wrong-descriptor"))
    assert(rejected.getMessage.contains(SnapshotEffect.key.id.toString))
