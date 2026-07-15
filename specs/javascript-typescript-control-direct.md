# JavaScript/TypeScript closed lexical direct control transform

Status: **specified / implementation pending** (2026-07-15).

## Overview

This feature is the first bounded managed-source-transform slice of the
[JavaScript/TypeScript bidirectional-control profile](javascript-typescript-bidirectional-control.md).
It lets an ordinary synchronous TypeScript or JavaScript program write a closed
lexical `direct.reset` block with statement-position `direct.shift` bindings and
lowers that block to the already-qualified explicit `@scalascript/control` ABI.
The transform owns syntax only: `Eff`, prompts, continuations, handlers, true
`shift`, and multi-shot behavior remain owned by the explicit package and the
target-neutral control specification.

The implementation lives in `v2/host/js/control-direct` and publishes the ESM npm
package `@scalascript/control-direct`. It has no runtime initialization effects,
no production dependency, and no CoreIR, frontend, seed-image, descriptor, runner,
or lane-registry role.

## Interface

### Package surface

The published package has these subpaths and command:

```json
{
  "exports": {
    ".": {
      "types": "./index.d.ts",
      "import": "./index.js",
      "default": "./index.js"
    },
    "./transform": {
      "types": "./transform.d.ts",
      "import": "./transform.js",
      "default": "./transform.js"
    },
    "./package.json": "./package.json"
  },
  "bin": {
    "ssc-control-tsc": "./cli.js"
  }
}
```

The exact tarball allow-list is `LICENSE`, `README.md`, `cli.js`, `index.d.ts`,
`index.js`, `package.json`, `transform.d.ts`, and `transform.js`. The license is
byte-equal to the repository Apache 2.0 `LICENSE`. The package has no
`dependencies`, `optionalDependencies`, or `peerDependencies`, no lifecycle
script, and `sideEffects: false`. TypeScript is a development/tooling dependency;
the transform API accepts a caller-supplied compiler API object and the CLI resolves
the consuming project's installed `typescript`. A missing compiler API is a clear
tooling failure, never a silent untransformed emit.

The root module exports exactly `DirectMarkerContractError` and `direct`:

```ts
import type {
  Eff,
  Prompt,
  PromptScope,
  ShiftBody
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
    body: ShiftBody<P, A, never, R>
  ): A
}>
```

Both marker methods always throw `DirectMarkerContractError` when executed. This
is the untransformed-marker contract: importing the authoring surface cannot
accidentally provide alternate runtime semantics.

The `/transform` subpath exports:

```ts
export type DirectDiagnosticCode =
  | "JS_DIRECT_OUTSIDE_RESET"
  | "JS_DIRECT_CAPTURE_BARRIER"
  | "JS_DIRECT_UNSUPPORTED"
  | "JS_DIRECT_PROMPT_MISMATCH"

export interface DirectDiagnostic {
  readonly code: DirectDiagnosticCode
  readonly message: string
  readonly fileName: string
  readonly start: number
  readonly length: number
  readonly line: number
  readonly column: number
}

export interface DirectTransform {
  readonly diagnostics: readonly DirectDiagnostic[]
  readonly transformedFiles: readonly string[]
  readonly transformers: import("typescript").CustomTransformers
}

export function createDirectTransform(
  typescript: typeof import("typescript"),
  program: import("typescript").Program
): DirectTransform

export function formatDirectDiagnostic(diagnostic: DirectDiagnostic): string
```

`line` and `column` are one-based display coordinates. `start` and `length` are
the TypeScript source file's zero-based UTF-16 offsets. Diagnostics are sorted by
file and span. The CLI accepts ordinary `tsc` file or `--project` inputs, reports
normal TypeScript diagnostics plus these transform diagnostics, and emits only
when both sets are green. It installs the returned `before` transformer and
otherwise follows the parsed TypeScript compiler options, including source maps.

Applications install and import `@scalascript/control` separately. Transformed
files import that package through one collision-free generated namespace alias;
the direct package never copies or wraps the explicit runtime.

## Accepted grammar

Only the symbol imported as a named import (possibly aliased) from exactly
`@scalascript/control-direct` is an authoring marker:

```ts
import { direct } from "@scalascript/control-direct"
import { direct as d } from "@scalascript/control-direct"
```

The type checker's import binding, not spelling, identifies the symbol. A local
`direct`, a property with the same text, a comment, or a string is unrelated and
is not transformed. Rebinding the marker through another variable is outside T1
and reaches the runtime marker error if executed.

The closed T1 grammar is:

```text
Reset       ::= Direct.reset(PromptIdentifier, () => Block)
Block       ::= PureStmt* Marker (PureStmt* Marker)* PureStmt* Return
              | PureStmt* Return
Marker      ::= (const | let) Identifier =
                  Direct.shift(PromptIdentifier, ShiftBody)
Return      ::= return Expression ;
PureStmt    ::= identifier variable declaration without a marker
              | expression statement without a marker
              | empty statement
ShiftBody   ::= synchronous function or arrow accepted by explicit shift
```

The reset callback has zero parameters, a block body, and is neither async nor a
generator. A marker declaration contains exactly one identifier declaration.
`var`, binding patterns/destructuring, missing initializers, multiple declarations,
and a shift in an argument, condition, return, assignment, nested expression, or
other arbitrary position are rejected. The final top-level statement is the one
`return`; earlier returns and fallthrough are rejected.

Reset and shift prompt expressions are identifiers resolving to the same
TypeScript symbol. Textually equal but differently bound prompts fail. A nested
recognized `direct.reset` is a new nearest delimiter and is analyzed independently;
its shifts never become markers of the outer block. A nested reset may occur in a
shift body because that body already returns an explicit `Eff` computation.

T1 is closed and local: the direct body contributes `Fx = never`; effectful host
calls must already be explicit `Eff` code inside a shift body or outside the direct
block. Cross-method managed frames and managed callbacks are later profile slices.

## Lowering

For a block with two markers:

```ts
direct.reset(prompt, () => {
  prefix()
  let first = direct.shift(prompt, firstBody)
  between(first)
  const second = direct.shift(prompt, secondBody)
  suffix(first, second)
  return result(first, second)
})
```

the semantic shape is:

```ts
control.reset(prompt, () => {
  prefix()
  return control.shift(prompt, firstBody).flatMap(first => {
    between(first)
    return control.shift(prompt, secondBody).flatMap(second => {
      suffix(first, second)
      return control.Eff.pure(result(first, second))
    })
  })
})
```

`control` above is a fresh namespace import from `@scalascript/control`. Zero
markers lower to `control.reset(prompt, () => { ...; return
control.Eff.pure(result) })`. The transform emits only explicit `Eff.pure`,
`reset`, `shift`, and `flatMap`; it emits no Promise, async function, generator,
exception-control protocol, replay loop, or parallel semantics runtime.

This construction gives the required observations:

- the prefix executes once when the reset computation runs;
- each continuation resume enters only its captured suffix;
- sequential markers form nested `flatMap` frames in source order;
- reusable resumes copy control but close over the same ordinary local mutable
  bindings, so the local heap is shared;
- the explicit reset implementation supplies nearest prompt matching, fresh prompt
  isolation, handler reinstall, and the reset around the shift body required for
  true `shift` rather than `shift0`.

Original nodes and text ranges are attached to generated calls, callbacks,
parameters, and retained statements. TypeScript owns JavaScript and source-map
printing; the transform does not print/reparse source text.

## Capture barriers and diagnostics

The transform fails closed. It never emits a partially transformed reset.

- `JS_DIRECT_OUTSIDE_RESET` — an exact imported `direct.shift` is not owned by a
  recognized enclosing direct reset.
- `JS_DIRECT_CAPTURE_BARRIER` — a marker occurs under, or the reset region contains,
  `async`, generator, `await`, `yield`, `try`/`finally`, loop, `switch`, branch,
  callback/function, or class syntax that would require a managed frame not present
  in T1. A separately recognized nested direct reset is not a barrier.
- `JS_DIRECT_UNSUPPORTED` — the reset or marker misses the closed grammar: wrong
  arity/callback form, `var`, destructuring, multiple declaration, arbitrary marker
  position, early/missing return, unsupported statement, or marker aliasing.
- `JS_DIRECT_PROMPT_MISMATCH` — a marker prompt does not resolve to the owning
  reset's exact prompt symbol.

The diagnostic span selects the smallest source construct that establishes the
failure: shift call for outside/mismatch/arbitrary position, barrier keyword or
node for a capture barrier, and reset/statement/declaration for the remaining
unsupported shape. Comments, strings, shadowed names, and imports from any other
module produce no direct-transform diagnostic.

## Behavior

- [ ] The package publishes only the frozen root, `/transform`, command, and exact
      eight-file Apache-licensed allow-list, with no production dependency,
      lifecycle script, or import-time effect.
- [ ] An untransformed root marker throws the stable
      `JS_DIRECT_UNTRANSFORMED` contract error and performs no control operation.
- [ ] Exact named imports and aliases transform; shadowing, comments, strings,
      same-spelled properties, and foreign-module imports do not.
- [ ] Zero, one, and sequentially many markers lower only to explicit
      `Eff.pure/reset/shift/flatMap` and preserve left-to-right evaluation.
- [ ] Prefix-once, suffix-per-resume, true shift, nearest matching reset, fresh
      prompt isolation, and ordinary shared local mutable heap agree with the
      explicit package.
- [ ] Direct differential programs for catalog vectors 18, 22, 23, and 24 produce
      their existing target-neutral oracles without editing `vectors.tsv` or
      `lanes.tsv` and without claiming the pending generated-JS lane.
- [ ] Async/generator/await/yield, try/finally, loops, switch, branch-nested marker,
      callback-nested marker, class-nested marker, outside-reset marker, prompt
      mismatch, `var`/destructuring marker, and arbitrary marker position fail with
      exact stable code and source span.
- [ ] The transformer preserves usable source maps and the CLI preserves ordinary
      TypeScript type diagnostics rather than laundering them through generated
      code.
- [ ] Package tests, package typecheck, exact dry-run pack, the existing explicit
      package's 31 tests/typecheck, catalog validation/negative validation, and
      affected `effect*,effects*` conformance are green from the isolated worktree.

## Decisions

- **Use the TypeScript compiler API with the caller's compiler instance.** It is
  already pinned as project tooling and supplies parser, binder, checker, emit, and
  source-map ownership. Rejected: regex/text rewriting, which cannot distinguish
  imports, shadowing, comments, strings, or prompt symbols; and a second production
  parser dependency, which would create version and packaging drift.
- **Separate authoring markers from the explicit runtime.** The root package fails
  if it survives emit, while transformed code imports the single existing semantic
  implementation. Rejected: implementing a second runtime in the transform package.
- **Closed statement-position T1 grammar.** It is sufficient to prove lexical
  multi-shot control while every accepted frame has an obvious `flatMap` lowering.
  Rejected: optimistic whole-JavaScript CPS, whose callbacks, abrupt completion,
  cleanup, and async frames need descriptors and a later managed transform.
- **No shared lane registration in T1.** Catalog-id differentials are package-local
  evidence. Rejected: marking `js-generated` ready before generated facade, bridge,
  and cross-language profile qualification exist.

## Out of scope

- `ApiDescriptor`, `ControlSummary`, `ArtifactManifest`, generated facades, value or
  call bridges, CoreIR, `.ssc` frontend/lowering, UniML, bootstrap seed/image, or a
  canonical codec change.
- Cross-method/module managed CPS, callbacks/events, async/Promise/generator
  adapters, mixed-language SCC dispatch, exact artifacts, portable runners, durable
  `save`/`run`, or remote execution.
- Registering a shared conformance lane or claiming the complete JS/TS host profile.
- General statement/expression CPS, destructuring marker binds, cleanup frames,
  exception control, or automatic effect-row inference beyond closed `Fx = never`.

## Results

Pending implementation and independent pre-integration review.
