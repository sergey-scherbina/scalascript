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

The wrapper supports the consumer's TypeScript 5.9.x compiler API (qualified with
5.9.3), resolves it from the named project/config directory or current working
directory, and works through the installed npm `.bin` symlink. It does not bundle
or fall back to a compiler from the transform package's store location.

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
`typescript@5.9` for the build and `@scalascript/control` for the application.
After every owned marker use is transformed, the named marker import is removed;
the emitted JavaScript runs without `@scalascript/control-direct` installed. The
root `direct` methods are authoring markers: if either reaches runtime without
transformation it throws the stable `JS_DIRECT_UNTRANSFORMED` contract error rather
than implementing a second control runtime.

T1 accepts only top-level `const`/`let` shift bindings inside a zero-parameter
synchronous reset arrow. Async/generator/cleanup/loop/switch/branch/callback/class
capture frames and arbitrary marker positions fail closed with source diagnostics.
So do intrinsic direct `eval` anywhere in a selected file (including import-only
marker erasure), a marker layer whose prefix or shift body value-references its own
or a later suffix binding (including through object shorthand), and any marker value
use that would survive emit, including shorthand and local runtime exports.
Parentheses and TypeScript `as`/non-null/type assertions preserve marker ownership;
indirect eval and `Function` remain global-only unmanaged operations.

Declaration-level or specifier-level TypeScript type-only exports are erased uses,
not runtime marker escapes, so forms such as `export type { direct as Marker }` and
`export { type direct as Marker }` remain accepted. Changing them to runtime exports
produces one file-atomic `JS_DIRECT_UNSUPPORTED` diagnostic.

Each source file is atomic: one direct diagnostic leaves the whole file unchanged.
Accepted lowering uses a fresh resume parameter followed by the original authored
`const` or `let` declaration, preserving JavaScript declaration behavior. Generated
code otherwise uses only the explicit `Eff.pure`, `reset`, `shift`, and `flatMap`
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
