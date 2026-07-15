package scalascript.controlapi.examples

import scalascript.control.*

private object Choice extends Effect:
  val key: EffectKey[Choice.type] =
    EffectKey.named(EffectId("example.choice"), this)

private final case class Choose(values: Vector[Int])
    extends Operation[Choice.type, Int]:
  val effect: EffectKey[Choice.type] = Choice.key
  val id: OperationId = OperationId(effect.id, "choose")

private def resumeReusable[A, Fx <: Effect, R](
    resumption: Resumption[A, Fx, R],
    value: A
): Eff[Fx, R] =
  resumption match
    case Resumption.Reusable(continuation) => continuation.resume(value)
    case Resumption.OneShot(_) =>
      throw new AssertionError("expected a reusable resumption")

@main def effectsAndShift(): Unit =
  val choices: Eff[Choice.type, Int] =
    perform(Choose(Vector(1, 2))).map(_ * 10)

  val handled = handle[Choice.type, Nothing, Int, Vector[Int]](choices)(
    new Handler[Choice.type, Nothing, Int, Vector[Int]]:
      val effect: EffectKey[Choice.type] = Choice.key

      def onReturn(value: Int): Eff[Nothing, Vector[Int]] =
        Eff.pure(Vector(value))

      def onOperation[A](
          operation: Operation[Choice.type, A],
          resumption: Resumption[A, Nothing, Vector[Int]]
      ): Eff[Nothing, Vector[Int]] =
        operation match
          case Choose(values) =>
            values.foldLeft(
              Eff.pure(Vector.empty[Int]): Eff[Nothing, Vector[Int]]
            ) { (result, value) =>
              result.flatMap { prefix =>
                resumeReusable(resumption, value).map(prefix ++ _)
              }
            }
  )

  val scoped = freshPrompt[Int]
  val prompt = scoped.prompt
  val shifted: Eff[Control[scoped.Key], Int] =
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(21).map(_ * 2)
    )
  val shiftResult = reset[scoped.Key, Nothing, Int](prompt)(shifted)

  val directResult: Eff[Nothing, Int] =
    direct.reset[scoped.Key, Nothing, Int](prompt) {
      val resumed =
        direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
          [Residual >: Nothing <: Effect] =>
            (continuation: Continuation[Int, Residual, Int]) =>
              continuation.resume(41)
        )
      resumed + 1
    }

  println(Eff.runPure(handled))
  println(Eff.runPure(shiftResult))
  println(Eff.runPure(directResult))
