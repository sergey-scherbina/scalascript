package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scala.compiletime.testing.typeCheckErrors

final class ControlApiTypeSafetyTest extends AnyFunSuite:
  test("named preserves the exact singleton owner type") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      object Ping extends Effect
      val key: EffectKey[Ping.type] =
        EffectKey.named(EffectId("ping"), Ping)
    """)

    assert(errors.isEmpty, errors.mkString("\n"))
  }

  test("an EffectKey constructor is not source-accessible") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      object Ping extends Effect
      val key = new EffectKey[Ping.type](EffectId("ping"))
    """)

    assert(errors.nonEmpty)
  }

  test("a singleton key cannot be narrowed to the Nothing row") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      object Ping extends Effect
      val key: EffectKey[Nothing] =
        EffectKey.named(EffectId("ping"), Ping)
    """)

    assert(errors.nonEmpty)
  }

  test("a generic wrapper cannot erase the owner's path-dependent type") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def widen[Fx <: Effect & Singleton](owner: Fx): EffectKey[Fx] =
        EffectKey.named(EffectId("generic"), owner)
    """)

    assert(errors.nonEmpty)
  }

  test("a key for one selected owner cannot be widened to a union key") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      object Ping extends Effect
      object Pong extends Effect
      val owner: Ping.type | Pong.type = Ping
      val key: EffectKey[Ping.type | Pong.type] =
        EffectKey.named(EffectId("union"), owner)
    """)

    assert(errors.nonEmpty)
  }

  test("runPure rejects a computation with an effectful row") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      object Input extends Effect
      object Read extends Operation[Input.type, Int]:
        val effect: EffectKey[Input.type] =
          EffectKey.named(EffectId("input"), Input)
        val id: OperationId = OperationId(effect.id, "read")

      val result: Int = Eff.runPure(perform(Read))
    """)

    assert(errors.nonEmpty)
  }

  test("fresh prompts have incompatible path-dependent keys") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val p = freshPrompt[Int]
      val q = freshPrompt[Int]
      val mismatch: Prompt[p.Key, Int] = q.prompt
    """)

    assert(errors.nonEmpty)
  }

  test("reset cannot discharge a different prompt's control row") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val p = freshPrompt[Int]
      val q = freshPrompt[Int]
      val fromP: Eff[Control[p.Key], Int] = Eff.pure(1)
      val incorrectlyPure: Eff[Nothing, Int] =
        reset[q.Key, Control[p.Key], Int](q.prompt)(fromP)
    """)

    assert(errors.nonEmpty)
  }

  test("a one-shot continuation has no reusable resume operation") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def resumeTwice(
          continuation: OneShotContinuation[Int, Nothing, Int]
      ): Eff[Nothing, Int] = continuation.resume(1)
    """)

    assert(errors.nonEmpty)
  }

  test("a one-shot continuation cannot be saved") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def save(
          continuation: OneShotContinuation[Int, Nothing, Int]
      ) = continuation.save()
    """)

    assert(errors.nonEmpty)
  }

  test("user code cannot subclass Continuation") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      final class Forged extends Continuation[Int, Nothing, Int]:
        def resume(value: Int): Eff[Nothing, Int] = Eff.pure(value)
        def save(): Eff[
          Save,
          SavedContinuation.Aux[Int, Nothing, Int]
        ] = ???
    """)

    assert(errors.nonEmpty)
  }

  test("user code cannot construct a successful SavedContinuation") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      final class Forged extends SavedContinuation[Int, Int]:
        type Effects = Nothing
        def run(value: Int): Eff[Restore, Int] = Eff.pure(value)
    """)

    assert(errors.nonEmpty)
  }

  test("rank-2 shift rejects an appended-effect row narrowing exploit") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      object Later extends Effect
      object Tick extends Operation[Later.type, Unit]:
        val effect: EffectKey[Later.type] =
          EffectKey.named(EffectId("later"), Later)
        val id: OperationId = OperationId(effect.id, "tick")

      def exploit[P](prompt: Prompt[P, Int]) =
        val shifted =
          shift[P, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                Eff.pure(Eff.runPure(continuation.resume(1)))
          )
        shifted.flatMap[Control[P] | Later.type, Int] { _ =>
          perform(Tick).map(_ => 1)
        }
    """)

    assert(errors.nonEmpty)
  }

  test("user code cannot construct or subclass a direct Scope token") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      final class ForgedScope
          extends direct.Scope[Int, Nothing, Int]
    """)

    assert(errors.nonEmpty)
  }

  test("the direct macro implementation is not source-accessible") {
    val errors = typeCheckErrors("""
      val implementation = scalascript.control.DirectMacros
    """)

    assert(errors.nonEmpty)
  }
