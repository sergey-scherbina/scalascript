import type {
  Continuation,
  Control,
  Eff,
  Prompt,
  PromptScope
} from "@scalascript/control"

export class DirectMarkerContractError extends Error {
  readonly code: "JS_DIRECT_UNTRANSFORMED"
  readonly marker: "reset" | "shift"
}

export const direct: Readonly<{
  reset<P extends PromptScope, R>(
    prompt: Prompt<P, R>,
    body: () => R
  ): Eff<never, R>
  shift<P extends PromptScope, R, A>(
    prompt: Prompt<P, R>,
    body: (
      continuation: Continuation<A, Control<P>, R>
    ) => Eff<Control<P>, R>
  ): A
}>
