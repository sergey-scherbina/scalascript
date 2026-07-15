import {
  Eff,
  defineEffect,
  freshPrompt,
  handle,
  perform,
  reset,
  shift
} from "@scalascript/control"
import type {
  Continuation,
  Control,
  Effect,
  EffectKey,
  Eff as EffType,
  OneShotContinuation,
  Prompt,
  PromptScope
} from "@scalascript/control"

const InputOwner = Symbol("typed.NegativeInput.owner")
const Input = defineEffect("typed.NegativeInput", InputOwner)
const Read = Input.operation<number>("read")

// @ts-expect-error inline Symbol() widens and cannot name a generative owner
defineEffect("typed.InlineOwner", Symbol("typed.InlineOwner"))
declare const widenedOwner: symbol
// @ts-expect-error a widened symbol cannot preserve owner identity
defineEffect("typed.WidenedOwner", widenedOwner)

const FirstOwner = Symbol("typed.Same.first")
const SecondOwner = Symbol("typed.Same.second")
const First = defineEffect("typed.Same", FirstOwner)
const Second = defineEffect("typed.Same", SecondOwner)
type FirstFx = typeof First extends EffectKey<infer Fx> ? Fx : never
type SecondFx = typeof Second extends EffectKey<infer Fx> ? Fx : never
const FirstRead = First.operation<number>("read")
const wrongOwnerHandler = {
  effect: Second,
  onReturn: (value: number) => Eff.pure(value),
  onOperation: () => Eff.pure(73)
} satisfies import("@scalascript/control").Handler<
  SecondFx,
  never,
  number,
  number
>
handle(
  // @ts-expect-error another owner cannot infer discharge of FirstFx
  perform(FirstRead()),
  wrongOwnerHandler
)
const stillFirst: EffType<FirstFx, number> = handle<
  SecondFx,
  FirstFx,
  number,
  number
>(perform(FirstRead()), wrongOwnerHandler)
// @ts-expect-error a handler for the second owner cannot discharge the first
const wrongOwnerWasNotHandled: EffType<never, number> = stillFirst
void wrongOwnerWasNotHandled

// @ts-expect-error an effectful computation is not accepted by runPure
Eff.runPure(perform(Read()))

declare const once: OneShotContinuation<number, never, number>
// @ts-expect-error one-shot continuations expose tryResume, not resume
once.resume(1)
// @ts-expect-error one-shot continuations cannot be saved as reusable values
once.save()

// @ts-expect-error the private brand prevents structural EffectKey forgery
const forgedKey: EffectKey<Effect<"forged">> = { id: { value: "forged" } }
void forgedKey

// @ts-expect-error the private brand prevents structural Continuation forgery
const forgedContinuation: Continuation<number, never, number> = {
  resume: value => Eff.pure(value),
  save: () => Eff.pure(undefined as never)
}
void forgedContinuation

freshPrompt<number, void>(
  <P extends PromptScope>(p: Prompt<P, number>) => {
    freshPrompt<number, void>(
      <Q extends PromptScope>(q: Prompt<Q, number>) => {
        // @ts-expect-error nested fresh prompts have incompatible keys
        const mismatch: Prompt<P, number> = q
        void mismatch

        const fromP: EffType<Control<P>, number> = shift<P, number, number>(
          p,
          <Residual extends Effect>(
            continuation: Continuation<number, Residual, number>
          ): EffType<Residual | Control<P>, number> =>
            continuation.resume(1)
        )
        const stillFromP = reset(q, () => fromP)
        // @ts-expect-error reset(q) cannot discharge Control<P>
        const incorrectlyPure: EffType<never, number> = stillFromP
        void incorrectlyPure
      }
    )
  }
)

// @ts-expect-error Prompt has an unexported invariant brand and no constructor
const forgedPrompt: Prompt<PromptScope, number> = {}
void forgedPrompt
