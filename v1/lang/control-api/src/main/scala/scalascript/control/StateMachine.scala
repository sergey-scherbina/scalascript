package scalascript.control

trait StateMachine[S, Fx <: Effect, A]:
  def step(state: S): MachineStep[S, Fx, A]

enum MachineStep[S, Fx <: Effect, A]:
  case Continue(next: S)
  case Evaluate(next: Eff[Fx, S])
  case Done(value: A)

trait ResumeStateMachine[S, A, Fx <: Effect, R]:
  def resume(state: S, input: A): Eff[Fx, R]

object StateMachine:
  def run[S, Fx <: Effect, A](
      initial: S,
      machine: StateMachine[S, Fx, A]
  ): Eff[Fx, A] =
    Eff.defer {
      machine.step(initial) match
        case MachineStep.Continue(next) => run(next, machine)
        case MachineStep.Evaluate(next) =>
          next.flatMap(state => run(state, machine))
        case MachineStep.Done(value) => Eff.pure(value)
    }
