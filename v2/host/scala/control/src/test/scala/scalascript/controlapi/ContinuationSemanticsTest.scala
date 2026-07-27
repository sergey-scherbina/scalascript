package scalascript.controlapi

import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*
import scalascript.controlapi.ControlTestFixtures.*

final class ContinuationSemanticsTest extends AnyFunSuite:
  test("a local continuation can resume repeatedly from the same state"):
    var calls = 0
    val machine = new ResumeStateMachine[Int, Int, Nothing, Int]:
      override def resume(state: Int, input: Int): Eff[Nothing, Int] =
        calls += 1
        Eff.pure(state + input)

    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.local(40, machine)

    assert(Eff.runPure(continuation.resume(2)) == 42)
    assert(Eff.runPure(continuation.resume(3)) == 43)
    assert(calls == 2)

  test("saving a local continuation is rejected as an unmanaged capture"):
    type LocalSaved = SavedContinuation.Aux[Int, Nothing, Int]
    var resumeCalls = 0
    val machine = new ResumeStateMachine[Int, Int, Nothing, Int]:
      override def resume(state: Int, input: Int): Eff[Nothing, Int] =
        resumeCalls += 1
        Eff.pure(state + input)

    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.local(40, machine)
    val rejected = handle[Save.type, Nothing, LocalSaved, CaptureFailure](
      continuation.save()
    )(
      new Handler[Save.type, Nothing, LocalSaved, CaptureFailure]:
        override val effect: EffectKey[Save.type] = Save.key

        override def onReturn(value: LocalSaved): Eff[Nothing, CaptureFailure] =
          throw new AssertionError("local save unexpectedly succeeded")

        override def onOperation[X](
            operation: Operation[Save.type, X],
            resumption: Resumption[X, Nothing, CaptureFailure]
        ): Eff[Nothing, CaptureFailure] =
          operation match
            case Save.Rejected(failure) =>
              assert(operation.id == OperationId(Save.key.id, "rejected"))
              assert(operation.multiplicity == ResumeMultiplicity.OneShot)
              resumption match
                case Resumption.OneShot(_) => ()
                case Resumption.Reusable(_) =>
                  throw new AssertionError(
                    "Save.Rejected exposed a reusable resumption"
                  )
              Eff.pure(failure)
    )

    assert(
      Eff.runPure(rejected) ==
        CaptureFailure.UnmanagedCapture("Continuation.local")
    )
    assert(resumeCalls == 0)

  test("one-shot ownership is claimed eagerly before the suffix runs"):
    var suffixCalls = 0
    var accepted: Option[Eff[Nothing, Int]] = None
    val body: Eff[OnceEffect.type, Int] =
      perform(TakeOnce).flatMap { value =>
        Eff.defer {
          suffixCalls += 1
          Eff.pure(value + 1)
        }
      }

    val handled = handle[OnceEffect.type, Nothing, Int, Int](body)(
      new Handler[OnceEffect.type, Nothing, Int, Int]:
        override val effect: EffectKey[OnceEffect.type] = OnceEffect.key

        override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

        override def onOperation[X](
            operation: Operation[OnceEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case TakeOnce =>
              resumption match
                case Resumption.OneShot(continuation) =>
                  val first = continuation.tryResume(41)
                  val second = continuation.tryResume(0)
                  assert(suffixCalls == 0)
                  assert(
                    second == Left(
                      ResumeRejected.AlreadyResumed(TakeOnce.id)
                    )
                  )
                  first match
                    case Right(next) =>
                      accepted = Some(next)
                      next
                    case Left(reason) =>
                      throw new AssertionError(
                        s"first one-shot resume was rejected: $reason"
                      )
                case Resumption.Reusable(_) =>
                  throw new AssertionError("expected a one-shot resumption")
    )

    assert(Eff.runPure(handled) == 42)
    assert(suffixCalls == 1)
    assert(Eff.runPure(accepted.get) == 42)
    assert(suffixCalls == 2)

  test("concurrent one-shot resumes have exactly one winner"):
    val attempts = 64
    val body: Eff[OnceEffect.type, Int] = perform(TakeOnce).map(_ + 1_000)
    val handled = handle[OnceEffect.type, Nothing, Int, Int](body)(
      new Handler[OnceEffect.type, Nothing, Int, Int]:
        override val effect: EffectKey[OnceEffect.type] = OnceEffect.key

        override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

        override def onOperation[X](
            operation: Operation[OnceEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case TakeOnce =>
              resumption match
                case Resumption.OneShot(continuation) =>
                  val ready = new CountDownLatch(attempts)
                  val start = new CountDownLatch(1)
                  val executor = Executors.newVirtualThreadPerTaskExecutor()
                  try
                    val futures = (0 until attempts).map { candidate =>
                      executor.submit(
                        new Callable[
                          Either[ResumeRejected, Eff[Nothing, Int]]
                        ]:
                          override def call()
                              : Either[
                                ResumeRejected,
                                Eff[Nothing, Int]
                              ] =
                            ready.countDown()
                            start.await()
                            continuation.tryResume(candidate)
                      )
                    }
                    assert(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    val outcomes = futures.map(
                      _.get(10, TimeUnit.SECONDS)
                    )
                    val winners = outcomes.collect { case Right(next) => next }
                    val losers = outcomes.collect { case Left(reason) => reason }

                    assert(winners.size == 1)
                    assert(losers.size == attempts - 1)
                    assert(
                      losers.forall(
                        _ == ResumeRejected.AlreadyResumed(TakeOnce.id)
                      )
                    )
                    winners.head
                  finally executor.shutdownNow()
                case Resumption.Reusable(_) =>
                  throw new AssertionError("expected a one-shot resumption")
    )

    val result = Eff.runPure(handled)
    assert(result >= 1_000)
    assert(result < 1_000 + attempts)

  private type IntSaved = SavedContinuation.Aux[Int, Nothing, Int]

  private def freezeSavable(
      continuation: Continuation[Int, Nothing, Int]
  ): IntSaved =
    Eff.runPure(
      handle[Save.type, Nothing, IntSaved, IntSaved](continuation.save())(
        new Handler[Save.type, Nothing, IntSaved, IntSaved]:
          val effect: EffectKey[Save.type] = Save.key

          def onReturn(value: IntSaved): Eff[Nothing, IntSaved] =
            Eff.pure(value)

          def onOperation[X](
              operation: Operation[Save.type, X],
              resumption: Resumption[X, Nothing, IntSaved]
          ): Eff[Nothing, IntSaved] =
            throw new AssertionError(
              "savable save unexpectedly rejected as an unmanaged capture"
            )
      )
    )

  test("a savable continuation saves and reruns from the capture point"):
    var resumeCalls = 0
    val machine = new ResumeStateMachine[Int, Int, Nothing, Int]:
      override def resume(state: Int, input: Int): Eff[Nothing, Int] =
        resumeCalls += 1
        Eff.pure(state + input)

    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.savable(40, machine, DurableValue.immutable[Int])

    val saved = freezeSavable(continuation)
    assert(resumeCalls == 0) // save does not resume

    // reusable multi-shot: run zero-or-more times, each at the capture point.
    assert(Eff.runPure(Restore.admitLocally(saved.run(2))) == 42)
    assert(Eff.runPure(Restore.admitLocally(saved.run(3))) == 43)
    assert(resumeCalls == 2)

  test("save/run never replays the prefix"):
    var prefixRuns = 0
    def prefixState(): Int =
      prefixRuns += 1 // everything before the capture point
      40

    val machine = new ResumeStateMachine[Int, Int, Nothing, Int]:
      override def resume(state: Int, input: Int): Eff[Nothing, Int] =
        Eff.pure(state + input)

    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.savable(prefixState(), machine, DurableValue.immutable[Int])

    val saved = freezeSavable(continuation)
    assert(prefixRuns == 1)

    val _ = Eff.runPure(Restore.admitLocally(saved.run(1)))
    val _ = Eff.runPure(Restore.admitLocally(saved.run(2)))
    assert(prefixRuns == 1) // the prefix is never re-executed to rebuild state

  test("each run reconstructs an independent frame (snapshot law)"):
    val codec = DurableValue.copying[Array[Int]](_.clone())
    val machine = new ResumeStateMachine[Array[Int], Int, Nothing, Int]:
      override def resume(state: Array[Int], input: Int): Eff[Nothing, Int] =
        state(0) += input // mutate only this run's frame
        Eff.pure(state(0))

    val original = Array(100)
    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.savable(original, machine, codec)

    val saved: SavedContinuation.Aux[Int, Nothing, Int] =
      Eff.runPure(
        handle[
          Save.type,
          Nothing,
          SavedContinuation.Aux[Int, Nothing, Int],
          SavedContinuation.Aux[Int, Nothing, Int]
        ](continuation.save())(
          new Handler[
            Save.type,
            Nothing,
            SavedContinuation.Aux[Int, Nothing, Int],
            SavedContinuation.Aux[Int, Nothing, Int]
          ]:
            val effect: EffectKey[Save.type] = Save.key
            def onReturn(
                value: SavedContinuation.Aux[Int, Nothing, Int]
            ): Eff[Nothing, SavedContinuation.Aux[Int, Nothing, Int]] =
              Eff.pure(value)
            def onOperation[X](
                operation: Operation[Save.type, X],
                resumption: Resumption[
                  X,
                  Nothing,
                  SavedContinuation.Aux[Int, Nothing, Int]
                ]
            ): Eff[Nothing, SavedContinuation.Aux[Int, Nothing, Int]] =
              throw new AssertionError("array savable save unexpectedly rejected")
        )
      )

    original(0) = 999 // post-save mutation of the original must be invisible

    val first = Eff.runPure(Restore.admitLocally(saved.run(1)))
    val second = Eff.runPure(Restore.admitLocally(saved.run(5)))
    assert(first == 101) // 100 + 1, not 999 + 1
    assert(second == 105) // 100 + 5, independent of the first run's mutation

  test("an unmanaged runtime continuation still cannot be saved"):
    // A codec-less local continuation stays rejected; only savable succeeds.
    type LocalSaved = SavedContinuation.Aux[Int, Nothing, Int]
    val machine = new ResumeStateMachine[Int, Int, Nothing, Int]:
      override def resume(state: Int, input: Int): Eff[Nothing, Int] =
        Eff.pure(state + input)
    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.local(40, machine)
    val failure =
      handle[Save.type, Nothing, LocalSaved, CaptureFailure](
        continuation.save()
      )(
        new Handler[Save.type, Nothing, LocalSaved, CaptureFailure]:
          val effect: EffectKey[Save.type] = Save.key
          def onReturn(value: LocalSaved): Eff[Nothing, CaptureFailure] =
            throw new AssertionError("codec-less local save unexpectedly succeeded")
          def onOperation[X](
              operation: Operation[Save.type, X],
              resumption: Resumption[X, Nothing, CaptureFailure]
          ): Eff[Nothing, CaptureFailure] =
            operation match
              case Save.Rejected(reason) => Eff.pure(reason)
      )
    assert(
      Eff.runPure(failure) ==
        CaptureFailure.UnmanagedCapture("Continuation.local")
    )

  // ── cancellation transitions (vector 26; owner decisions D1/D4) ────────────────────────────────
  // `cancel` and `resume` compete for the SAME atomic claim, so the eager-claim law extends without
  // a new race algebra — and the LOSER is told which side won. That distinction is the whole point
  // of D1: reporting a lost cancel as `AlreadyResumed` would name a *resume* failure for a caller
  // who asked to *stop* the work, which §13's non-collapsibility rule forbids.

  /** Run `body` under a handler that hands the one-shot continuation to `inspect`, and return what
    * `inspect` decides. The suffix must never run: every case here rejects or cancels. */
  private def withOneShot[A](
      inspect: OneShotContinuation[Int, Nothing, Int] => A
  ): (A, Int) =
    var suffixCalls = 0
    var observed: Option[A] = None
    val body: Eff[OnceEffect.type, Int] =
      perform(TakeOnce).flatMap { value =>
        Eff.defer {
          suffixCalls += 1
          Eff.pure(value + 1)
        }
      }
    val handled = handle[OnceEffect.type, Nothing, Int, Int](body)(
      new Handler[OnceEffect.type, Nothing, Int, Int]:
        override val effect: EffectKey[OnceEffect.type] = OnceEffect.key
        override def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)
        override def onOperation[X](
            operation: Operation[OnceEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case TakeOnce =>
              resumption match
                case Resumption.OneShot(continuation) =>
                  observed = Some(inspect(continuation))
                  Eff.pure(0)
                case Resumption.Reusable(_) =>
                  throw new AssertionError("one-shot operation exposed a reusable resumption")
    )
    Eff.runPure(handled)
    (observed.getOrElse(throw new AssertionError("handler never ran")), suffixCalls)

  test("cancel then resume — the resume is rejected as Cancelled, and the suffix never runs"):
    val (result, suffixCalls) = withOneShot { k =>
      val cancelled = k.tryCancel()
      assert(cancelled == Right(CancelAccepted(TakeOnce.id)))
      k.tryResume(41)
    }
    assert(result == Left(ResumeRejected.Cancelled(TakeOnce.id)))
    assert(suffixCalls == 0, "a cancelled continuation must not run its suffix")

  test("resume then cancel — the cancel is TooLateToCancel, NOT AlreadyResumed (D1)"):
    val (result, _) = withOneShot { k =>
      assert(k.tryResume(41).isRight)
      k.tryCancel()
    }
    assert(result == Left(ResumeRejected.TooLateToCancel(TakeOnce.id)))
    // The distinction is the decision: this must NOT be reported as a resume failure.
    assert(result != Left(ResumeRejected.AlreadyResumed(TakeOnce.id)))

  test("cancelling twice is idempotent — the same evidence, never a failure"):
    val (result, _) = withOneShot { k =>
      val first = k.tryCancel()
      val second = k.tryCancel()
      (first, second)
    }
    assert(result == (Right(CancelAccepted(TakeOnce.id)), Right(CancelAccepted(TakeOnce.id))))

  test("a second resume after a cancel still reports Cancelled, not AlreadyResumed"):
    // The claim is CANCELLED, so every later resume must keep saying so — a state machine that
    // flipped to "resumed" on the first rejected resume would report the wrong reason afterwards.
    val (result, suffixCalls) = withOneShot { k =>
      k.tryCancel()
      k.tryResume(1)
      k.tryResume(2)
    }
    assert(result == Left(ResumeRejected.Cancelled(TakeOnce.id)))
    assert(suffixCalls == 0)

  test("concurrent cancel and resume — exactly one wins, and the loser names the winner"):
    // The §3.1 eager law under real contention: whichever side wins, the other's rejection must
    // identify the winner, and the suffix must run at most once.
    val outcomes = (1 to 200).map { _ =>
      val (pair, suffixCalls) = withOneShot { k =>
        val start = new CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try
          val resumeTask = pool.submit(new Callable[Either[ResumeRejected, Any]] {
            override def call(): Either[ResumeRejected, Any] =
              start.await(5, TimeUnit.SECONDS); k.tryResume(41)
          })
          val cancelTask = pool.submit(new Callable[Either[ResumeRejected, Any]] {
            override def call(): Either[ResumeRejected, Any] =
              start.await(5, TimeUnit.SECONDS); k.tryCancel()
          })
          start.countDown()
          (resumeTask.get(5, TimeUnit.SECONDS), cancelTask.get(5, TimeUnit.SECONDS))
        finally pool.shutdownNow()
      }
      assert(suffixCalls <= 1)
      pair
    }
    outcomes.foreach { case (resumed, cancelled) =>
      (resumed.isRight, cancelled.isRight) match
        case (true, false) =>
          assert(cancelled == Left(ResumeRejected.TooLateToCancel(TakeOnce.id)))
        case (false, true) =>
          assert(resumed == Left(ResumeRejected.Cancelled(TakeOnce.id)))
        case other =>
          throw new AssertionError(s"exactly one side must win, got $other")
    }
