package scalascript.controlapi.examples

import scalascript.control.*

private trait OwnerCycle
private trait ParamBound[A]
private final class ParamToken extends ParamBound[ParamToken]

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

  val polymorphicValue = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val identity: [A] => A => A = [A] => (value: A) => value
    val selected =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(40)
      )
    selected + identity[Int](2)
  }
  val explicitPolymorphicValue = reset[scoped.Key, Nothing, Int](prompt) {
    val identity: [A] => A => A = [A] => (value: A) => value
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(40)
    ).map(selected => selected + identity[Int](2))
  }

  val adjacentStructuralCalls = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val identity: [A] => A => A = [A] => (value: A) => value
    val plusOne: Int => Int = value => value + 1
    val prefix = identity[Int](1)
    val selected =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(38)
      )
    selected + prefix + identity.apply[Int](1) + plusOne(1)
  }
  val explicitAdjacentStructuralCalls = reset[scoped.Key, Nothing, Int](prompt) {
    val identity: [A] => A => A = [A] => (value: A) => value
    val plusOne: Int => Int = value => value + 1
    val prefix = identity[Int](1)
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(38)
    ).map(selected => selected + prefix + identity.apply[Int](1) + plusOne(1))
  }

  val nestedPolymorphicValue = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val owner = new Object()
    val nested: [A] => A => ([B] => B => owner.type) =
      [A] => (_: A) => [B] => (_: B) => owner
    val selected =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(41)
      )
    selected + (if nested[Int](1)[String]("value") eq owner then 1 else 1000)
  }
  val explicitNestedPolymorphicValue = reset[scoped.Key, Nothing, Int](prompt) {
    val owner = new Object()
    val nested: [A] => A => ([B] => B => owner.type) =
      [A] => (_: A) => [B] => (_: B) => owner
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(41)
    ).map { selected =>
      selected + (if nested[Int](1)[String]("value") eq owner then 1 else 1000)
    }
  }

  val resultAndBoundParamRefs = direct.reset[scoped.Key, Nothing, Int](prompt) {
    val resultOnly: [A] => () => Option[A] = [A] => () => Option.empty[A]
    val boundOnly: [A <: ParamBound[A]] => () => Int =
      [A <: ParamBound[A]] => () => 1
    val selected =
      direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
        [Residual >: Nothing <: Effect] =>
          (continuation: Continuation[Int, Residual, Int]) =>
            continuation.resume(40)
      )
    selected +
      (if resultOnly[String]().isEmpty then 1 else 1000) +
      boundOnly[ParamToken]()
  }
  val explicitResultAndBoundParamRefs = reset[scoped.Key, Nothing, Int](prompt) {
    val resultOnly: [A] => () => Option[A] = [A] => () => Option.empty[A]
    val boundOnly: [A <: ParamBound[A]] => () => Int =
      [A <: ParamBound[A]] => () => 1
    shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (continuation: Continuation[Int, Residual, Int]) =>
          continuation.resume(40)
    ).map { selected =>
      selected +
        (if resultOnly[String]().isEmpty then 1 else 1000) +
        boundOnly[ParamToken]()
    }
  }

  val results = Vector(
    Eff.runPure(capturedSingleton),
    Eff.runPure(explicitSingleton),
    Eff.runPure(capturedPrompt),
    Eff.runPure(explicitPrompt),
    Eff.runPure(movedLambdas),
    Eff.runPure(explicitLambdas),
    Eff.runPure(mutualGivens),
    Eff.runPure(explicitGivens),
    Eff.runPure(polymorphicValue),
    Eff.runPure(explicitPolymorphicValue),
    Eff.runPure(adjacentStructuralCalls),
    Eff.runPure(explicitAdjacentStructuralCalls),
    Eff.runPure(nestedPolymorphicValue),
    Eff.runPure(explicitNestedPolymorphicValue),
    Eff.runPure(resultAndBoundParamRefs),
    Eff.runPure(explicitResultAndBoundParamRefs)
  )
  assert(results.forall(_ == 42), results)
  println(results)
