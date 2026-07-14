package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*

final class PromptSemanticsTest extends AnyFunSuite:
  test("shift captures the suffix up to the nearest matching reset") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val shifted: Eff[Control[scoped.Key], Int] =
      shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            val _ = continuation
            Eff.pure(7)
      )

    val result = reset[scoped.Key, Nothing, Int](prompt) {
      reset[scoped.Key, Nothing, Int](prompt) {
        shifted
      }.map(_ + 1000)
    }

    assert(Eff.runPure(result) == 1007)
  }

  test("a different prompt does not catch another prompt's shift") {
    val pScope = freshPrompt[Int]
    val qScope = freshPrompt[Int]
    val p = pScope.prompt
    val q = qScope.prompt

    val fromP: Eff[Control[pScope.Key], Int] =
      shift[pScope.Key, Int, Nothing, Int](p)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            val _ = continuation
            Eff.pure(7)
      )

    val throughQ: Eff[Control[pScope.Key], Int] =
      reset[qScope.Key, Control[pScope.Key], Int](q) {
        fromP
      }
    val result = reset[pScope.Key, Nothing, Int](p) {
      throughQ.map(_ + 1000)
    }

    assert(Eff.runPure(result) == 7)
  }

  test("a shift body remains under the same reset") {
    val scoped = freshPrompt[Int]
    val prompt = scoped.prompt

    val nested: Eff[Control[scoped.Key], Int] =
      shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (outer: Continuation[Int, Residual, Int]) =>
            val _ = outer
            shift[scoped.Key, Int, Residual, Int](prompt)(
              [Nested >: Residual <: Effect] =>
                (inner: Continuation[Int, Nested, Int]) =>
                  inner.resume(11)
            )
      )

    val result = reset[scoped.Key, Nothing, Int](prompt) {
      nested
    }

    assert(Eff.runPure(result) == 11)
  }

  test("a reusable continuation shares one heap object across resumes") {
    val scoped = freshPrompt[Vector[Int]]
    val prompt = scoped.prompt
    var cell = 0

    val shifted: Eff[Control[scoped.Key], Unit] =
      shift[scoped.Key, Unit, Nothing, Vector[Int]](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Unit, Residual, Vector[Int]]) =>
            continuation.resume(()).flatMap[Residual, Vector[Int]] {
              first =>
                continuation.resume(()).map(second => first ++ second)
            }
      )

    val result = reset[scoped.Key, Nothing, Vector[Int]](prompt) {
      shifted.map { _ =>
        cell += 1
        Vector(cell)
      }
    }

    assert(Eff.runPure(result) == Vector(1, 2))
    assert(cell == 2)
  }
