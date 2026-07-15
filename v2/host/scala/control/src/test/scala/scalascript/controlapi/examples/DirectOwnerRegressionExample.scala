package scalascript.controlapi.examples

import scalascript.control.*

private trait OwnerCycle

@main def directOwnerRegressions(): Unit =
  val scoped = freshPrompt[Int]
  val prompt = scoped.prompt

  val capturedSingleton = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val owner = new Object()
    val selected =
      direct.shift[scoped.Key, owner.type, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[owner.type, Residual, Int]) =>
            continuation.resume(owner)
      )
    if selected eq owner then 42 else 0
  }
  val explicitSingleton = reset[scoped.Key, Nothing, Int](prompt) {
    val owner = new Object()
    shift[scoped.Key, owner.type, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[owner.type, Residual, Int]) =>
          continuation.resume(owner)
    ).map(selected => if selected eq owner then 42 else 0)
  }

  val capturedPrompt = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val inner = freshPrompt[Int]
    val selected =
      direct.shift[
        scoped.Key,
        Prompt[inner.Key, Int],
        Nothing,
        Int
      ](prompt)(
        [Residual >: Nothing <: Effect] =>
          (
              continuation: Continuation[
                Prompt[inner.Key, Int],
                Residual,
                Int
              ]
          ) => continuation.resume(inner.prompt)
      )
    if selected eq inner.prompt then 42 else 0
  }
  val explicitPrompt = reset[scoped.Key, Nothing, Int](prompt) {
    val inner = freshPrompt[Int]
    shift[
      scoped.Key,
      Prompt[inner.Key, Int],
      Nothing,
      Int
    ](prompt)(
      [Residual >: Nothing <: Effect] =>
        (
            continuation: Continuation[
              Prompt[inner.Key, Int],
              Residual,
              Int
            ]
        ) => continuation.resume(inner.prompt)
    ).map(selected => if selected eq inner.prompt then 42 else 0)
  }

  val movedLambdas = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val owner = new Object()
    val before: () => owner.type = () => owner
    val selected =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(40)
      )
    val after: () => owner.type = () => owner
    selected + (if (before() eq owner) && (after() eq owner) then 2 else 1000)
  }
  val explicitLambdas = reset[scoped.Key, Nothing, Int](prompt) {
    val owner = new Object()
    val before: () => owner.type = () => owner
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(40)
    ).map { selected =>
      val after: () => owner.type = () => owner
      selected +
        (if (before() eq owner) && (after() eq owner) then 2 else 1000)
    }
  }

  val mutualGivens = direct.reset[scoped.Key, Nothing, Int](prompt) {
    given first: OwnerCycle = second
    given second: OwnerCycle = first
    val selected =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(41)
      )
    selected + 1
  }
  val explicitGivens = reset[scoped.Key, Nothing, Int](prompt) {
    given first: OwnerCycle = second
    given second: OwnerCycle = first
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(41)
    ).map(_ + 1)
  }

  val results = Vector(
    Eff.runPure(capturedSingleton),
    Eff.runPure(explicitSingleton),
    Eff.runPure(capturedPrompt),
    Eff.runPure(explicitPrompt),
    Eff.runPure(movedLambdas),
    Eff.runPure(explicitLambdas),
    Eff.runPure(mutualGivens),
    Eff.runPure(explicitGivens)
  )
  assert(results.forall(_ == 42), results)
  println(results)
