# @scalascript/control-direct

Closed synchronous lexical direct style for the explicit
`@scalascript/control` effects and multi-prompt control ABI.

```ts
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"

const answer = freshPrompt(prompt => Eff.runPure(
  direct.reset(prompt, () => {
    const value = direct.shift(prompt, continuation =>
      continuation.resume(41)
    )
    return value + 1
  })
))
```

Compile it with the wrapper command:

```bash
npx ssc-control-tsc --project tsconfig.json
```

or install the `before` transformer through the tooling API:

```js
import ts from "typescript"
import { createDirectTransform } from "@scalascript/control-direct/transform"

const program = ts.createProgram(/* ordinary TypeScript options */)
const direct = createDirectTransform(ts, program)
if (direct.diagnostics.length === 0) {
  program.emit(undefined, undefined, undefined, false, direct.transformers)
}
```

The package intentionally has no production dependencies. Applications install
`typescript` and `@scalascript/control` explicitly. The root `direct` methods are
authoring markers: if either reaches runtime without transformation it throws the
stable `JS_DIRECT_UNTRANSFORMED` contract error rather than implementing a second
control runtime.

T1 accepts only top-level `const`/`let` shift bindings inside a zero-parameter
synchronous reset arrow. Async/generator/cleanup/loop/switch/branch/callback/class
capture frames and arbitrary marker positions fail closed with source diagnostics.
Generated code uses only the explicit `Eff.pure`, `reset`, `shift`, and `flatMap`
surface.

From this directory:

```bash
npm install --ignore-scripts
npm test
npm run typecheck
npm pack --dry-run
```

The normative contract is the canonical
[JavaScript/TypeScript direct-control specification](https://github.com/sergey-scherbina/scalascript/blob/main/specs/javascript-typescript-control-direct.md).
