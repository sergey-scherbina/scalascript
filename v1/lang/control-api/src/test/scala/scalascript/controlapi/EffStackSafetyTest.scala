package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*

final class EffStackSafetyTest extends AnyFunSuite:
  test("one million left-associated binds are stack safe"):
    val limit = 1_000_000
    var index = 0
    var computation: Eff[Nothing, Int] = Eff.pure(0)

    while index < limit do
      computation = computation.flatMap(value => Eff.pure(value + 1))
      index += 1

    assert(Eff.runPure(computation) == limit)

  test("StateMachine runs one million mixed transitions stacklessly"):
    val limit = 1_000_000
    val machine = new StateMachine[Int, Nothing, Int]:
      override def step(state: Int): MachineStep[Int, Nothing, Int] =
        if state == limit then MachineStep.Done(state)
        else if (state & 1) == 0 then MachineStep.Continue(state + 1)
        else MachineStep.Evaluate(Eff.pure(state + 1))

    assert(Eff.runPure(StateMachine.run(0, machine)) == limit)
