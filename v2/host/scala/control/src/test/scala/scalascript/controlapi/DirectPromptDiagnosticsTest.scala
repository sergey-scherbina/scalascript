package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scala.compiletime.testing.Error
import scala.compiletime.testing.typeCheckErrors

final class DirectPromptDiagnosticsTest extends AnyFunSuite:
  private def render(error: Error): String =
    s"${error.message}\nline=${error.lineContent}\ncolumn=${error.column}"

  private def assertDiagnostic(
      errors: List[Error],
      expectedMessage: String,
      expectedLine: String,
      expectedColumn: Int
  ): Unit =
    assert(errors.size == 1, errors.map(render).mkString("\n---\n"))
    val error = errors.head
    assert(error.message == expectedMessage, render(error))
    assert(error.lineContent == expectedLine, render(error))
    assert(error.column == expectedColumn, render(error))

  test("a direct shift outside reset is an unmanaged capture") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val escaped = direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(1)
      )
    """)

    assertDiagnostic(
      errors,
      "error [UNMANAGED_CAPTURE]: direct.shift must be lexically enclosed by direct.reset for the same prompt and effect row",
      "      )",
      7
    )
  }

  test("a marker under a lambda reports a capture barrier") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val callback = () =>
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(1)
          )
        callback()
      }
    """)

    assertDiagnostic(
      errors,
      "error [CAPTURE_BARRIER]: direct.shift crosses a lambda boundary at line 7, column 23",
      "          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      10
    )
  }

  test("a marker under try reports a capture barrier") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        try
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(1)
          )
        finally ()
      }
    """)

    assertDiagnostic(
      errors,
      "error [CAPTURE_BARRIER]: direct.shift crosses a try/finally boundary at line 7, column 8",
      "          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      10
    )
  }

  test("a marker in an unsupported expression position fails closed") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val selected =
          if true then
            direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
              [Residual >: Nothing <: Effect] =>
                (continuation: Continuation[Int, Residual, Int]) =>
                  continuation.resume(1)
            )
          else 0
        selected + 1
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift must be a block-level immutable val bind or the reset tail expression",
      "            direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      12
    )
  }

  test("a marker cannot cross a nested direct reset in M1") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val outerScope = freshPrompt[Int]
      val outer = outerScope.prompt
      val innerScope = freshPrompt[Int]
      val inner = innerScope.prompt
      val computation = direct.reset[outerScope.Key, Nothing, Int](outer) {
        val nested = direct.reset[innerScope.Key, Nothing, Int](inner) {
          val escaped =
            direct.shift[outerScope.Key, Int, Nothing, Int](outer)(
              [Residual >: Nothing <: Effect] =>
                (continuation: Continuation[Int, Residual, Int]) =>
                  continuation.resume(1)
            )
          escaped
        }
        Eff.runPure(nested)
      }
    """)

    assertDiagnostic(
      errors,
      "error [UNMANAGED_CAPTURE]: direct.shift belongs to a different lexical direct.reset scope",
      "            direct.shift[outerScope.Key, Int, Nothing, Int](outer)(",
      12
    )
  }

  test("a marker under a loop reports a capture barrier") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        var keepGoing = true
        while keepGoing do
          keepGoing = false
          val selected =
            direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
              [Residual >: Nothing <: Effect] =>
                (continuation: Continuation[Int, Residual, Int]) =>
                  continuation.resume(1)
            )
          val _ = selected
        42
      }
    """)

    assertDiagnostic(
      errors,
      "error [CAPTURE_BARRIER]: direct.shift crosses a loop boundary at line 8, column 8",
      "            direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      12
    )
  }

  test("a marker passed by name reports a capture barrier") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      def evaluate(value: => Int): Int = value
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        evaluate(
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(1)
          )
        )
      }
    """)

    assertDiagnostic(
      errors,
      "error [CAPTURE_BARRIER]: direct.shift crosses a by-name argument boundary at line 8, column 8",
      "          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      10
    )
  }

  test("a mutable marker binding is outside the accepted ANF grammar") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        var selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(1)
          )
        selected + 1
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift must be a block-level immutable val bind or the reset tail expression",
      "          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      10
    )
  }

  test("a pure reset body cannot defer a non-local return") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def escaped(): Eff[Nothing, Int] =
        val scoped = freshPrompt[Int]
        val prompt = scoped.prompt
        direct.reset[scoped.Key, Nothing, Int](prompt) {
          return Eff.pure(7)
        }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset cannot defer a non-local return",
      "          return Eff.pure(7)",
      10
    )
  }

  test("a captured suffix cannot contain a non-local return") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def escaped(): Eff[Nothing, Int] =
        val scoped = freshPrompt[Int]
        val prompt = scoped.prompt
        direct.reset[scoped.Key, Nothing, Int](prompt) {
          val selected =
            direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
              [Residual >: Nothing <: Effect] =>
                (continuation: Continuation[Int, Residual, Int]) =>
                  continuation.resume(1)
            )
          return Eff.pure(selected)
        }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset cannot defer a non-local return",
      "          return Eff.pure(selected)",
      10
    )
  }

  test("a marker in a lazy binding remains behind the lazy capture barrier") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        lazy val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(41)
          )
        42
      }
    """)

    assertDiagnostic(
      errors,
      "error [CAPTURE_BARRIER]: direct.shift crosses a lazy-initializer boundary at line 7, column 8",
      "          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      10
    )
  }

  test("a lazy local cannot cross a later capture") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        lazy val base = 40
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(2)
          )
        base + selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a lazy local across capture; move it outside direct.reset or make it strict",
      "        lazy val base = 40",
      17
    )
  }

  test("a local method cannot cross capture") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        def base(): Int = 40
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(2)
          )
        base() + selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a local method across capture; move it outside direct.reset",
      "        def base(): Int = 40",
      12
    )
  }

  test("a local class cannot cross capture") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        class Box(val value: Int)
        val box = new Box(40)
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(2)
          )
        box.value + selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a local class across capture; move it outside direct.reset",
      "        class Box(val value: Int)",
      14
    )
  }

  test("a local type cannot cross capture") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        type Number = Int
        val base: Number = 40
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(2)
          )
        base + selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot carry a local type across capture; move it outside direct.reset",
      "        type Number = Int",
      13
    )
  }

  test("an inline wrapper around a marker fails closed before prompt evaluation") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      inline def wrapped[P, A, Fx <: Effect, R](prompt: Prompt[P, R])(
          body: ShiftBody[P, A, Fx, R]
      )(using direct.Scope[P, Fx, R]): A =
        direct.shift[P, A, Fx, R](prompt)(body)

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      var promptReads = 0
      def readPrompt(): Prompt[scoped.Key, Int] =
        promptReads += 1
        prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val selected = wrapped[scoped.Key, Int, Nothing, Int](readPrompt())(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(41)
        )
        selected + 1
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: an unexpanded inline application is outside direct.reset M1; write direct.shift directly at block level or move the inline call outside direct.reset",
      "        val selected = wrapped[scoped.Key, Int, Nothing, Int](readPrompt())(",
      23
    )
  }

  test("a direct marker nested in ShiftBody fails at the inner call") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (outer: Continuation[Int, Residual, Int]) =>
                val nested =
                  direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
                    [Nested >: Nothing <: Effect] =>
                      (inner: Continuation[Int, Nested, Int]) =>
                        inner.resume(1)
                  )
                outer.resume(nested)
          )
        selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift inside another direct.shift body is outside M1; use scalascript.control.shift explicitly or a nested direct.reset",
      "                  direct.shift[scoped.Key, Int, Nothing, Int](prompt)(",
      18
    )
  }

  test("a direct marker in a nested reset prompt remains in the outer ShiftBody") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val outerScope = freshPrompt[Int]
      val outer = outerScope.prompt
      val innerScope = freshPrompt[Int]
      val inner = innerScope.prompt
      val computation = direct.reset[outerScope.Key, Nothing, Int](outer) {
        val selected =
          direct.shift[outerScope.Key, Int, Nothing, Int](outer)(
            [Residual >: Nothing <: Effect] =>
              (outerContinuation: Continuation[Int, Residual, Int]) =>
                val nested = direct.reset[innerScope.Key, Nothing, Int](
                  direct.shift[
                    outerScope.Key,
                    Prompt[innerScope.Key, Int],
                    Nothing,
                    Int
                  ](outer)(
                    [NestedResidual >: Nothing <: Effect] =>
                      (
                          promptContinuation: Continuation[
                            Prompt[innerScope.Key, Int],
                            NestedResidual,
                            Int
                          ]
                      ) => promptContinuation.resume(inner)
                  )
                ) { 1 }
                outerContinuation.resume(Eff.runPure(nested))
          )
        selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift inside another direct.shift body is outside M1; use scalascript.control.shift explicitly or a nested direct.reset",
      "                  direct.shift[",
      18
    )
  }

  test("a transparent inline wrapper reports its invocation position") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      transparent inline def wrapped[P, A, Fx <: Effect, R](
          prompt: Prompt[P, R]
      )(
          body: ShiftBody[P, A, Fx, R]
      )(using direct.Scope[P, Fx, R]): A =
        direct.shift[P, A, Fx, R](prompt)(body)

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val selected = wrapped[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(41)
        )
        selected + 1
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift through an inline wrapper is outside M1; write direct.shift directly at block level",
      "        val selected = wrapped[scoped.Key, Int, Nothing, Int](prompt)(",
      23
    )
  }

  test("an unsupported dependent polymorphic local type fails closed") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      val scoped = freshPrompt[Int]
      val prompt = scoped.prompt
      val computation = direct.reset[scoped.Key, Nothing, Int](prompt) {
        val innerScope = freshPrompt[Int]
        val inner: Prompt[innerScope.Key, Int] = innerScope.prompt
        val dependent: [A] => () => Prompt[innerScope.Key, Int] =
          [A] => () => inner
        val selected =
          direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
            [Residual >: Nothing <: Effect] =>
              (continuation: Continuation[Int, Residual, Int]) =>
                continuation.resume(1)
          )
        val _ = dependent[Int]()
        selected
      }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.shift cannot rebind this dependent local type across capture; move the declaration outside direct.reset",
      "        val dependent: [A] => () => Prompt[innerScope.Key, Int] =",
      12
    )
  }

  test("a pure reset body cannot defer boundary break") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def escaped(): Eff[Nothing, Int] =
        scala.util.boundary[Eff[Nothing, Int]]:
          val scoped = freshPrompt[Int]
          val prompt = scoped.prompt
          direct.reset[scoped.Key, Nothing, Int](prompt) {
            scala.util.boundary.break(Eff.pure(7))
          }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset M1 cannot defer scala.util.boundary.break; move boundary control outside direct.reset",
      "            scala.util.boundary.break(Eff.pure(7))",
      12
    )
  }

  test("a captured suffix cannot defer boundary break") {
    val errors = typeCheckErrors("""
      import scalascript.control.*

      def escaped(): Eff[Nothing, Int] =
        scala.util.boundary[Eff[Nothing, Int]]:
          val scoped = freshPrompt[Int]
          val prompt = scoped.prompt
          direct.reset[scoped.Key, Nothing, Int](prompt) {
            val selected =
              direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
                [Residual >: Nothing <: Effect] =>
                  (continuation: Continuation[Int, Residual, Int]) =>
                    continuation.resume(1)
              )
            scala.util.boundary.break(Eff.pure(selected))
          }
    """)

    assertDiagnostic(
      errors,
      "error [DIRECT_STYLE_UNSUPPORTED]: direct.reset M1 cannot defer scala.util.boundary.break; move boundary control outside direct.reset",
      "            scala.util.boundary.break(Eff.pure(selected))",
      12
    )
  }
