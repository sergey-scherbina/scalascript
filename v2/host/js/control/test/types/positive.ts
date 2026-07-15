import {
  Continuation,
  Eff,
  MachineStep,
  StateMachine,
  defineEffect,
  freshPrompt,
  handle,
  perform,
  reset,
  shift
} from "@scalascript/control"
import type {
  Continuation as ContinuationType,
  Control,
  Effect,
  EffectKey,
  Eff as EffType,
  Handler,
  Prompt,
  PromptKeyOf,
  PromptScope,
  ResumeStateMachine,
  StateMachine as StateMachineType
} from "@scalascript/control"

const InputOwner = Symbol("typed.Input.owner")
const Input = defineEffect("typed.Input", InputOwner)
const InputAgain: typeof Input = defineEffect("typed.Input", InputOwner)
void InputAgain
type InputFx = typeof Input extends EffectKey<infer Fx> ? Fx : never
const Read = Input.operation<number>("read")

const readProgram: EffType<InputFx, number> = perform(Read()).map(value =>
  value + 1
)
const inputHandler: Handler<InputFx, never, number, number> = {
  effect: Input,
  onReturn: value => Eff.pure(value),
  onOperation: () => Eff.pure(42)
}
const handled: EffType<never, number> = handle(readProgram, inputHandler)
const answer: number = Eff.runPure(handled)
void answer

const inferredHandled: EffType<never, number> = handle(readProgram, {
  effect: Input,
  onReturn: value => Eff.pure(value),
  onOperation: () => Eff.pure(42)
})
void inferredHandled

const machine: StateMachineType<number, never, number> = {
  step(state) {
    return state === 10
      ? MachineStep.Done(state)
      : MachineStep.Continue(state + 1)
  }
}
const machineResult: EffType<never, number> = StateMachine.run(0, machine)
void machineResult

const resumeMachine: ResumeStateMachine<number, number, never, number> = {
  resume: (state, input) => Eff.pure(state + input)
}
const local: ContinuationType<number, never, number> =
  Continuation.local(40, resumeMachine)
const localResult: EffType<never, number> = local.resume(2)
void localResult

const prompted: EffType<never, number> = freshPrompt<
  number,
  EffType<never, number>
>(
  <P extends PromptScope>(prompt: Prompt<P, number>) =>
    {
      const extracted: Prompt<PromptKeyOf<typeof prompt>, number> = prompt
      void extracted
      return reset(prompt, () =>
        shift<P, number, number>(
          prompt,
          <Residual extends Effect>(
            continuation: ContinuationType<number, Residual, number>
          ): EffType<Residual | Control<P>, number> =>
            continuation.resume(41)
        ).map(value => value + 1)
      )
    }
)
void prompted
