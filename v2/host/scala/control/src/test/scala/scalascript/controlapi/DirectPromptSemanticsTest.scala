package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*
import scalascript.controlapi.ControlTestFixtures.*

final class DirectPromptSemanticsTest extends AnyFunSuite:
  test("a pure direct reset lowers to the explicit protocol") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result: Eff[Nothing, Int] =
      direct.reset[scoped.Key, Nothing, Int](prompt) {
        val left = 20
        val right = 22
        left + right
      }

    assert(Eff.runPure(result) == 42)
  }

  test("a direct shift binds its resumed value into the suffix") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result: Eff[Nothing, Int] =
      direct.reset[scoped.Key, Nothing, Int](prompt) {
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(41)
          )
        selected + 1
      }

    assert(Eff.runPure(result) == 42)
  }

  test("a direct shift may resume zero times") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      val ignored =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              val _ = continuation
              Eff.pure(7)
        )
      ignored + 1000
    }

    assert(Eff.runPure(result) == 7)
  }

  test("pure prefix runs once and the suffix runs once per resume") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt
    var prefixRuns = 0
    var suffixRuns = 0

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      prefixRuns += 1
      val selected =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(10).flatMap[Residual, Int] { first =>
                continuation.resume(20).map(second => first + second)
              }
        )
      suffixRuns += 1
      selected + 1
    }

    assert(prefixRuns == 0)
    assert(suffixRuns == 0)
    assert(Eff.runPure(result) == 32)
    assert(prefixRuns == 1)
    assert(suffixRuns == 2)
  }

  test("multiple sequential direct shifts lower in source order") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt
    val events = scala.collection.mutable.ArrayBuffer.empty[String]

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      events += "prefix"
      val first =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              events += "first"
              continuation.resume(10)
        )
      events += "middle"
      val second =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              events += "second"
              continuation.resume(20)
        )
      events += "suffix"
      first + second
    }

    assert(Eff.runPure(result) == 30)
    assert(events.toVector == Vector("prefix", "first", "middle", "second", "suffix"))
  }

  test("a tail-position direct shift is passed directly to reset") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(42)
      )
    }

    assert(Eff.runPure(result) == 42)
  }

  test("a direct reusable continuation shares captured heap state") {
    val scoped = freshPrompt[Vector[Int]]
    val prompt = scoped.prompt
    var cell = 0

    val result = direct.reset[scoped.Key, Nothing, Vector[Int]](prompt) {
      val selected =
        direct.shift[scoped.Key, Int, Nothing, Vector[Int]](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Vector[Int]]) =>
              continuation.resume(0).flatMap[Residual, Vector[Int]] {
                first =>
                  continuation.resume(0).map(second => first ++ second)
              }
        )
      cell += 1
      Vector(cell + selected)
    }

    assert(Eff.runPure(result) == Vector(1, 2))
    assert(cell == 2)
  }

  test("a direct shift body remains under the same reset") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      val selected =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (outer: Continuation[Int, Residual, Int]) =>
              val _ = outer
              shift[scoped.Key, Int, Residual, Int](prompt)(
                [Nested >: Residual <: Effect] =>
                  (inner: Continuation[Int, Nested, Int]) =>
                    inner.resume(11)
              )
        )
      selected + 1000
    }

    assert(Eff.runPure(result) == 11)
  }

  test("nested direct reset scopes lower their own markers") {
    val outerScope = freshPrompt[Int]
    val outer = outerScope.prompt
    val innerScope = freshPrompt[Int]
    val inner = innerScope.prompt

    val result = direct.reset[outerScope.Key, Nothing, Int](outer) {
      val outerValue =
        direct.shift[outerScope.Key, Int, Nothing, Int](outer)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(20)
        )
      val innerResult =
        direct.reset[innerScope.Key, Nothing, Int](inner) {
          val innerValue =
            direct.shift[innerScope.Key, Int, Nothing, Int](inner)(
              [Residual >: Nothing <: Effect] =>
                (continuation: Continuation[Int, Residual, Int]) =>
                  continuation.resume(21)
            )
          innerValue + 1
        }
      outerValue + Eff.runPure(innerResult)
    }

    assert(Eff.runPure(result) == 42)
  }

  test("a direct shift preserves its declared residual effect row") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val controlled: Eff[ReadEffect.type, Int] =
      direct.reset[scoped.Key, ReadEffect.type, Int](prompt) {
        val selected =
          direct.shift[scoped.Key, Int, ReadEffect.type, Int](prompt)(
            [Residual >: ReadEffect.type <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                perform(Read).flatMap[Residual | Control[scoped.Key], Int] {
                  value => continuation.resume(value)
                }
          )
        selected + 1
      }

    val handled = handle[ReadEffect.type, Nothing, Int, Int](controlled)(
      new Handler[ReadEffect.type, Nothing, Int, Int]:
        val effect: EffectKey[ReadEffect.type] = ReadEffect.key

        def onReturn(value: Int): Eff[Nothing, Int] = Eff.pure(value)

        def onOperation[X](
            operation: Operation[ReadEffect.type, X],
            resumption: Resumption[X, Nothing, Int]
        ): Eff[Nothing, Int] =
          operation match
            case Read => resumeReusable(resumption, 41)
    )

    assert(Eff.runPure(handled) == 42)
  }

  test("strict local values, givens, and pattern binds cross capture") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      val base = 9
      val (left, right) = (20, 1)
      given bonus: Int = 3
      val selected =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(base + summon[Int])
        )
      base + left + right + selected
    }

    assert(Eff.runPure(result) == 42)
  }

  test("a reusable continuation shares a local mutable prefix cell") {
    val scoped = freshPrompt[Vector[Int]]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Vector[Int]](prompt) {
      var cell = 0
      val selected =
        direct.shift[scoped.Key, Int, Nothing, Vector[Int]](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Vector[Int]]) =>
              continuation.resume(0).flatMap[Residual, Vector[Int]] { first =>
                continuation.resume(0).map(second => first ++ second)
              }
        )
      cell += 1
      Vector(cell + selected)
    }

    assert(Eff.runPure(result) == Vector(1, 2))
  }

  test("a value between sequential captures is rebound in the next shift body") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      val first =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(10)
        )
      val between = first + 1
      val second =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(between + 10)
        )
      between + second
    }

    assert(Eff.runPure(result) == 32)
  }

  test("a return local to a suffix method keeps its local owner") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val result = direct.reset[scoped.Key, Nothing, Int](prompt) {
      val selected =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(1)
        )
      def local(): Int = return 41
      selected + local()
    }

    assert(Eff.runPure(result) == 42)
  }

  test("dependent prompt and owner singleton locals cross capture") {
    val outerScope = freshPrompt[Int]
    val outer = outerScope.prompt

    val result = direct.reset[outerScope.Key, Nothing, Int](outer) {
      val innerScope = freshPrompt[Int]
      val inner: Prompt[innerScope.Key, Int] = innerScope.prompt
      val owner = new Object()
      val same: owner.type = owner
      var mutable: Prompt[innerScope.Key, Int] = inner
      given dependentPrompt: Prompt[innerScope.Key, Int] = inner
      val (patternPrompt, patternTag) = (inner, 1)
      val selected =
        direct.shift[outerScope.Key, Int, Nothing, Int](outer)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(40)
        )
      val checked: Prompt[innerScope.Key, Int] = inner
      mutable = summon[Prompt[innerScope.Key, Int]]
      val allRebound =
        (checked eq innerScope.prompt) &&
          (mutable eq inner) &&
          (patternPrompt eq inner) &&
          patternTag == 1 &&
          (same eq owner)
      selected + (if allRebound then 2 else 1000)
    }

    assert(Eff.runPure(result) == 42)
  }

  test("a nested managed direct reset remains legal inside ShiftBody") {
    val outerScope = freshPrompt[Int]
    val outer = outerScope.prompt
    val innerScope = freshPrompt[Int]
    val inner = innerScope.prompt

    val result = direct.reset[outerScope.Key, Nothing, Int](outer) {
      val selected =
        direct.shift[outerScope.Key, Int, Nothing, Int](outer)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              val nested = direct.reset[innerScope.Key, Nothing, Int](inner) {
                val innerSelected =
                  direct.shift[innerScope.Key, Int, Nothing, Int](inner)(
                    [InnerResidual >: Nothing <: Effect] =>
                      (
                          innerContinuation: Continuation[
                            Int,
                            InnerResidual,
                            Int
                          ]
                      ) => innerContinuation.resume(40)
                  )
                innerSelected
              }
              continuation.resume(Eff.runPure(nested))
        )
      selected + 2
    }

    assert(Eff.runPure(result) == 42)
  }
