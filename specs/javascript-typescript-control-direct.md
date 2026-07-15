# JavaScript/TypeScript closed lexical direct control transform

Status: **repair specification frozen after independent pre-integration REJECT;
implementation repair pending** (2026-07-15).

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
the consuming project's installed `typescript`. T1 supports the TypeScript 5.9 API
line (`versionMajorMinor === "5.9"`); 5.9.3 is the development and qualification
pin. The programmatic API rejects any other line with the stable `RangeError`
message `@scalascript/control-direct supports TypeScript 5.9.x; received <version>`
before inspecting the program. The CLI reports the same incompatibility as an
actionable tooling failure. A missing or unsupported compiler API is never a silent
untransformed emit, and the published package does not bundle or declare a
production compiler dependency.

The CLI resolves `typescript` through Node `createRequire` from a consumer-owned
issuer. With `--project`/`-p`, the issuer is the named configuration's directory
(or the named directory itself); otherwise it is the process working directory.
It does not fall back to the transform package's store location, an ambient global
installation, or a second compiler. This rule applies before TypeScript parses the
rest of the command line, so an extracted or strict-store CLI uses the same compiler
that owns the consuming program.

The npm executable is entered when `process.argv[1]` and `import.meta.url` resolve
to the same real file or filesystem identity. Symlinked `.bin` launchers therefore
run `main`; a missing, non-file, or unreadable `argv[1]` deterministically means the
module was imported and does not auto-run. Tests invoke the installed `.bin` from
the packed tarball rather than calling the repository `cli.js` directly.

The root module exports exactly `DirectMarkerContractError` and `direct`:

```ts
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
```

Both marker methods always throw `DirectMarkerContractError` when executed. This
is the untransformed-marker contract: importing the authoring surface cannot
accidentally provide alternate runtime semantics.

The `/transform` subpath exports:

```ts
import type ts from "typescript"

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
  readonly transformers: ts.CustomTransformers
}

export function createDirectTransform(
  typescript: typeof ts,
  program: ts.Program
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
the direct package never copies or wraps the explicit runtime. The marker package
is build-time-only: a clean transformed file contains no owned `direct` value import
or marker use, and emitted production JavaScript runs without
`@scalascript/control-direct` installed.

## Accepted grammar

Only the symbol imported as a named import (possibly aliased) from exactly
`@scalascript/control-direct` is an authoring marker:

```ts
import { direct } from "@scalascript/control-direct"
import { direct as d } from "@scalascript/control-direct"
```

The type checker's import binding, not spelling, identifies the symbol. A local
`direct`, a property with the same text, a comment, or a string is unrelated and
is not transformed. Parentheses and the TypeScript-only `as`, non-null, and type-
assertion wrappers are recursively transparent around a marker receiver/callee:
`(direct).reset`, `direct!.reset`, and `(direct as typeof direct).reset` have the
same ownership as `direct.reset`. No other expression wrapper is transparent.

Every runtime/value reference to an owned marker binding in a file selected for
transformation must be the receiver of a successfully analyzed `reset` or `shift`
call. Rebinding, computed access, passing/returning the marker, namespace/default
aliasing, and re-exporting the owned value are unsupported and produce
`JS_DIRECT_UNSUPPORTED`; they never survive a successful emit. Type-only references
that TypeScript erases are not value uses. After a file is clean, the transformer
removes only each completed named `direct` import specifier and removes its import
declaration only when no default or named bindings remain. Unrelated import
declarations and other specifiers are preserved exactly. This makes the ordinary
marker-only authoring case independent of the direct package at production time
without rewriting unrelated imports.

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

For a marker at statement index `i`, its shift body may reference lexical bindings
declared before `i`. It must not reference, directly or through any nested closure,
the marker binding itself or any block-scoped binding declared in statement `i` or
the continuation suffix after it. Ownership is determined by TypeScript checker
symbol identity, so a genuinely shadowed nested binding is unrelated. The first
forbidden value reference receives `JS_DIRECT_CAPTURE_BARRIER` and the file is not
transformed. T1 deliberately rejects this forward/own capture instead of hoisting
or re-evaluating declarations: hidden motion would change temporal-dead-zone,
initializer, and per-resume evaluation order.

Reset and shift prompt expressions are identifiers resolving to the same
TypeScript symbol. Textually equal but differently bound prompts fail. A nested
recognized `direct.reset` is a new nearest delimiter and is analyzed independently;
its shifts never become markers of the outer block. A nested reset may occur in a
shift body because that body already returns an explicit `Eff` computation.

T1 is closed and local: the direct body contributes `Fx = never`; effectful host
calls must already be explicit `Eff` code inside a shift body or outside the direct
block. Cross-method managed frames and managed callbacks are later profile slices.
The marker declaration therefore exposes the concrete closed body type
`Continuation<A, Control<P>, R> => Eff<Control<P>, R>` rather than re-exporting
the explicit API's residual-row-polymorphic `ShiftBody`. This keeps ordinary nested
same-prompt shift bodies typeable without inventing an open residual row; lowering
still calls the unchanged explicit `shift` implementation.

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
  return control.shift(prompt, firstBody).flatMap(__sscResume => {
    let first = __sscResume
    between(first)
    return control.shift(prompt, secondBody).flatMap(__sscResume1 => {
      const second = __sscResume1
      suffix(first, second)
      return control.Eff.pure(result(first, second))
    })
  })
})
```

`control` above is a fresh namespace import from `@scalascript/control`. Every
`__sscResume*` is a collision-safe generated parameter. The continuation begins
with the original marker declaration kind, name, type annotation, source ownership,
and modifiers, changing only its initializer to that fresh parameter. This preserves
JavaScript `const`/`let` behavior and prevents a generated callback parameter from
silently replacing the authored binding. Zero markers lower to
`control.reset(prompt, () => { ...; return control.Eff.pure(result) })`. The
transform emits only explicit `Eff.pure`, `reset`, `shift`, and `flatMap`; it emits
no Promise, async function, generator, exception-control protocol, replay loop, or
parallel semantics runtime.

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

Transformation is source-file atomic. Analysis may prepare more than one reset in
a file, but any direct-transform diagnostic cancels all rewrites and generated
imports for that file. Programmatic callers therefore cannot emit a mixture of
lowered and surviving marker semantics by ignoring diagnostics.

## Capture barriers and diagnostics

The transform fails closed. It never emits a partially transformed reset or file.

If a file contains at least one owned direct marker call, intrinsic direct eval is
a file-wide capture barrier wherever it appears: top level, inside a reset, or in
any nested function/class. The callee test recursively removes only parentheses,
`as`, non-null, and type assertions and then requires the unshadowed global `eval`
binding; a locally declared `eval` is ordinary code. Indirect forms such as
`(0, eval)(source)`, `eval?.(source)`, or an alias call, and the global `Function`
constructor remain allowed in T1 because they execute against global scope rather
than the rewritten lexical environment. They do not acquire managed-control or
saveability status and remain barriers for later cross-frame/durable profiles.

- `JS_DIRECT_OUTSIDE_RESET` — an exact imported `direct.shift` is not owned by a
  recognized enclosing direct reset.
- `JS_DIRECT_CAPTURE_BARRIER` — a marker occurs under, or the reset region contains,
  `async`, generator, `await`, `yield`, `try`/`finally`, loop, `switch`, branch,
  callback/function, or class syntax that would require a managed frame not present
  in T1; a shift body captures its own or a later suffix binding; or a selected file
  contains intrinsic direct eval. A separately recognized nested direct reset is not
  a barrier.
- `JS_DIRECT_UNSUPPORTED` — the reset or marker misses the closed grammar: wrong
  arity/callback form, `var`, destructuring, multiple declaration, arbitrary marker
  position, early/missing return, unsupported statement, marker aliasing, or any
  surviving owned marker value use.
- `JS_DIRECT_PROMPT_MISMATCH` — a marker prompt does not resolve to the owning
  reset's exact prompt symbol.

The diagnostic span selects the smallest source construct that establishes the
failure: shift call for outside/mismatch/arbitrary position, barrier keyword or
node for a structural capture barrier, the offending identifier for forward/own
capture or a surviving marker use, the direct-eval call for eval capture, and
reset/statement/declaration for the remaining unsupported shape. Comments, strings,
shadowed names, and imports from any other module produce no direct-transform
diagnostic.

## Behavior

- [x] The package publishes only the frozen root, `/transform`, command, and exact
      eight-file Apache-licensed allow-list, with no production dependency,
      lifecycle script, or import-time effect.
- [x] An untransformed root marker throws the stable
      `JS_DIRECT_UNTRANSFORMED` contract error and performs no control operation.
- [x] Exact named imports and aliases transform; shadowing, comments, strings,
      same-spelled properties, and foreign-module imports do not.
- [x] Zero, one, and sequentially many markers lower only to explicit
      `Eff.pure/reset/shift/flatMap` and preserve left-to-right evaluation.
- [x] Prefix-once, suffix-per-resume, true shift, nearest matching reset, fresh
      prompt isolation, and ordinary shared local mutable heap agree with the
      explicit package.
- [x] Direct differential programs for catalog vectors 18, 22, 23, and 24 produce
      their existing target-neutral oracles without editing `vectors.tsv` or
      `lanes.tsv` and without claiming the pending generated-JS lane.
- [x] Async/generator/await/yield, try/finally, loops, switch, branch-nested marker,
      callback-nested marker, class-nested marker, outside-reset marker, prompt
      mismatch, `var`/destructuring marker, and arbitrary marker position fail with
      exact stable code and source span.
- [x] The transformer preserves usable source maps and the CLI preserves ordinary
      TypeScript type diagnostics rather than laundering them through generated
      code.
- [ ] Shift bodies that value-reference their own marker binding or any later
      suffix binding, including through nested closures, fail file-atomically with
      `JS_DIRECT_CAPTURE_BARRIER`; preceding and genuinely shadowed bindings remain
      accepted without initializer reordering.
- [ ] Marker lowering uses collision-safe resume parameters followed by the original
      `const`/`let` declaration, including real JavaScript under
      `allowJs: true, checkJs: false`.
- [ ] Intrinsic direct eval anywhere in a selected file fails file-atomically after
      transparent-wrapper normalization; shadowed/indirect eval and `Function`
      follow the explicit global-only policy above.
- [ ] Every owned marker value use is transformed or diagnosed, completed named
      marker specifiers are removed without changing unrelated imports, and emitted
      production JavaScript runs with no direct package installed.
- [ ] Parenthesized, `as`, non-null, and type-asserted marker receivers/callees obey
      the same transform and diagnostic rules as the unwrapped exact import.
- [ ] The programmatic API and CLI accept only TypeScript 5.9.x, reject other API
      lines actionably, and never bundle or fall back to another compiler.
- [ ] The packed installed `.bin` runs through its symlink, resolves TypeScript only
      from the project/config/cwd issuer even when the package lives in an extracted
      store, and fails non-zero for missing compiler or invalid options.
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
  implementation. A clean emit erases the completed marker import, so this package
  stays a development dependency. Rejected: implementing a second runtime in the
  transform package or retaining a production guard import that contradicts the
  documented install boundary.
- **Reject forward/own shift-body capture instead of moving declarations.** Checker
  symbol identity catches nested closure references without confusing shadowing.
  Rejected: hoisting or pre-evaluating suffix declarations, which changes TDZ,
  prefix-once, and suffix-per-resume behavior.
- **Preserve authored JavaScript declaration semantics.** A fresh generated resume
  parameter feeds the original `const`/`let` declaration. Rejected: replacing that
  declaration with a callback parameter, which weakens `const` and can collide with
  suffix bindings.
- **Make direct eval a selected-file barrier.** Direct eval can observe or mutate the
  exact lexical frame the transform rewrites; indirect eval and `Function` are
  explicitly global-only and remain unmanaged. Rejected: best-effort lexical
  rewriting around dynamic source text.
- **Qualify one compiler API line and load it from the consumer.** TypeScript 5.9.x
  is accepted, 5.9.3 is the qualification pin, and `createRequire` uses the
  project/config/cwd issuer. Rejected: silently accepting untested compiler AST APIs,
  bundling a second compiler, or resolving from a strict-store tool location.
- **Closed statement-position T1 grammar.** It is sufficient to prove lexical
  multi-shot control while every accepted frame has an obvious `flatMap` lowering.
  Rejected: optimistic whole-JavaScript CPS, whose callbacks, abrupt completion,
  cleanup, and async frames need descriptors and a later managed transform.
- **Concrete closed shift-body row at the marker.** The delimiter removes exactly
  `Control<P>`, so the T1 authoring continuation and body name that row directly.
  Rejected: exposing the explicit API's rank-2 residual `ShiftBody` on a closed
  marker; nested same-prompt bodies trigger unrelated-generic TypeScript identities
  even though no residual effect is admitted by this slice.
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

The historical frozen review checkpoint `f6fa34fac` (rebased equivalent
`1d45dcb3b`) produced:

- `npm test` in `v2/host/js/control-direct`: 16/16, covering root contract,
  package/links/license, CLI green/type-error/transform-error paths, exact-import
  ownership and alias/collision handling, source maps, every closed-grammar
  positive/negative, and catalog 18/22/23/24 differentials;
- `npm run typecheck`: green under TypeScript 5.9.3; `node --check` is green for
  all three published JavaScript files;
- `npm pack --dry-run --json`: exactly eight files, 11,376 packed bytes and
  39,819 unpacked bytes, no bundled dependency, executable `cli.js`, and the
  10,837-byte repository license verbatim;
- existing `v2/host/js/control`: 31/31 tests and TypeScript declarations green;
- shared catalog validation: 26 vectors / nine lanes; negative validator: 9/9;
- `tests/conformance/run.sh --only 'effect*,effects*'`: 5/5 affected cases green
  across every declared INT/JS/JVM/V2 lane.

The shared catalog and lane registry, CoreIR/frontends, descriptors, seed/image,
and runners are byte-untouched.

Independent read-only review rejected that checkpoint before integration. It
reproduced escaped forward lexical capture, erased JavaScript declaration kind,
file-wide direct-eval unsoundness, a symlinked npm-bin no-op, retained production
marker imports, compiler lookup from the tool rather than consumer, missed
transparent marker wrappers, and an unbounded compiler-API version. The unchecked
behavior rows above are the repair gate; these historical counts are not final
qualification evidence. A new clean checkpoint requires a fresh independent
APPROVE before bookkeeping, push, or claim release.
