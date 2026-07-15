# @scalascript/control

Compiler-independent algebraic effects and multi-prompt delimited control for
ordinary JavaScript and TypeScript programs. The package is the explicit local
reference model for ScalaScript control interoperability: it has no production
dependencies and does not depend on CoreIR, a compiler, a backend, or a runtime
service.

The package is ESM-only:

```js
import {
  Eff,
  ResumeMultiplicity,
  defineEffect,
  handle,
  perform
} from "@scalascript/control"

const InputOwner = Symbol("example.Input.owner")
const Input = defineEffect("example.Input", InputOwner)
const Read = Input.operation("read", {
  multiplicity: ResumeMultiplicity.OneShot
})

const program = perform(Read()).map(value => value + 1)
const handled = handle(program, {
  effect: Input,
  onReturn: value => Eff.pure(value),
  onOperation(operation, resumption) {
    if (!Read.is(operation) || resumption.kind !== "OneShot") {
      return Eff.pure(-1)
    }
    const attempt = resumption.continuation.tryResume(41)
    return attempt.ok ? attempt.computation : Eff.pure(-1)
  }
})

console.log(Eff.runPure(handled)) // 42
```

`Eff` is an iterative, reusable `Pure | Op` computation. Deep handlers reinstall
themselves around every accepted resume and forward unmatched operations with
their resumption intact. One-shot ownership is claimed before any suffix is built
or executed; losers receive structured `AlreadyResumed(OperationId)` data.

Prompts are created through a generative callback. A matching `reset` implements
true `shift`, including the reset around the shift body:

```js
import { Eff, freshPrompt, reset, shift } from "@scalascript/control"

const answer = freshPrompt(prompt =>
  Eff.runPure(reset(prompt, () =>
    shift(prompt, continuation => continuation.resume(21)).map(_ => 42)
  ))
)
```

TypeScript declarations brand effect keys, prompts, continuations, and saved
continuations so ordinary structural values cannot forge them. A named
`const Symbol()` supplies each effect's generative owner type: two keys may share a
stable descriptor string without becoming the same effect row. Nested fresh
prompt keys are incompatible, `PromptKeyOf` preserves an invariant concrete answer
type, an effectful `Eff` cannot be passed to `Eff.runPure`, and a one-shot
continuation exposes neither reusable `resume` nor `save`.

Runtime capabilities keep request, resumption, prompt, and continuation state in
private weak storage. Their reachable JavaScript constructors require an
unexported authority token, so property inspection, prototype grafting, or
constructor calls cannot pre-claim or forge control authority. The npm tarball
includes the repository's Apache 2.0 `LICENSE` verbatim.

This first slice is intentionally local. `Continuation.local` is reusable, but
`save()` performs typed `Save.Rejected(UnmanagedCapture("Continuation.local"))`.
Generated ScalaScript↔JavaScript facades, source transforms, managed event-loop
callbacks, mixed-language tail dispatch, durable save/run, and exact/portable
runners remain later host-profile slices.

From this directory:

```bash
npm install --ignore-scripts
npm test
npm run typecheck
npm pack --dry-run
```

The normative contract is
[`specs/javascript-typescript-bidirectional-control.md`](../../../../specs/javascript-typescript-bidirectional-control.md).
