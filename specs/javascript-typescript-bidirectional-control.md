# JavaScript/TypeScript ↔ ScalaScript bidirectional control profile

Status: **normative host profile / explicit local control slice in
pre-integration hardening; remaining host/runner profile planned** (2026-07-15).

This is the JavaScript/TypeScript host profile of
[`control-interoperability.md`](control-interoperability.md). That specification
solely owns `Eff`, handlers, `shift`/`reset`, multiplicity, `save`/`run`,
`CodeMode × FrameGate`, durable state, admission, security, and common conformance.
This profile fixes only their JS/TS realization and cannot weaken them.

Companions:

- [`v2-js-lane-bridge.md`](v2-js-lane-bridge.md) — existing CoreIR→JavaScript
  execution evidence;
- [`polyglot-libraries.md`](polyglot-libraries.md) — ordinary package facades;
- [`arch-ffi.md`](arch-ffi.md) — raw JavaScript FFI boundary;
- [`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md)
  — portable-VM reference row and readiness matrix.

Emitting JavaScript, importing an npm package, or hosting a CoreIR evaluator in
JavaScript does not by itself satisfy this host profile.

---

## 1. Scope and qualification

This profile covers ordinary ECMAScript and TypeScript programs on Node.js and
browser-like hosts. A lane may claim JS/TS control interoperability only when it
provides both:

1. a typed, lossless, bidirectional value bridge; and
2. a bidirectional call bridge preserving effect rows, callback policy, prompts,
   continuation multiplicity, barriers, and typed failures.

The host SDK, explicit `Eff` API, transformed direct style, exact-artifact runner,
and portable CoreIR runner are independently advertised capabilities. An npm facade
that exposes only pure functions, a Promise wrapper, raw `globalThis`, or a
one-reply callback adapter is partial library interop.

## 2. Package and generated facade

A compiler-independent ESM package exposes the explicit reference API. Generated
facades consume `ApiDescriptor`, `ControlSummary`, and `ArtifactManifest`, then
publish stable ESM exports and `.d.ts` declarations:

- pure exports are ordinary typed functions;
- effectful exports return `Eff<Fx, A>`;
- control-aware callbacks carry their declared convention and row;
- prompts and continuations are opaque branded values, not forgeable structural
  objects;
- `SavedContinuation<A, Fx, R>` exposes only the canonical `save`/`run` surface;
- failures are typed data/effects, not exception-name tests.

CommonJS, UMD, raw globals, and dynamically discovered functions may remain
compatibility adapters. They are `ForeignBarrier` unless they carry the same
descriptor and managed entry contract.

### 2.1 Compiler-independent explicit package (first delivery slice)

The first independently shippable slice is the local explicit reference package.
Its repository home is exactly `v2/host/js/control`, and its npm package name is
exactly `@scalascript/control`. It is ESM-only, has no production dependencies,
does not run install scripts, and publishes exactly these package subpaths:

```json
{
  "exports": {
    ".": {
      "types": "./index.d.ts",
      "import": "./index.js",
      "default": "./index.js"
    },
    "./package.json": "./package.json"
  }
}
```

Its dry-run and published tarballs contain exactly `LICENSE`, `README.md`,
`index.d.ts`, `index.js`, and `package.json`. The license is the repository's
Apache 2.0 text. Development locks, tests, fixtures, and generated archives are
not package payload.

The root runtime module exports exactly:

```text
CaptureFailure
Continuation
Eff
MachineStep
ResumeMultiplicity
ResumeRejected
Save
StateMachine
defineEffect
freshPrompt
handle
perform
reset
shift
```

`index.d.ts` exposes the corresponding opaque or readonly types:
`Effect`, `EffectId`, `OperationId`, `ResumeMultiplicity`, `ResumeRejected`,
`ResumeAttempt`, `EffectKey`, `Operation`, `OperationFactory`, `Eff`, `Handler`,
`Continuation`, `OneShotContinuation`, `Resumption`, `CaptureFailure`, `Save`,
`Restore`, `SavedContinuation`, `PromptScope`, `PromptKeyOf`, `Control`, `Prompt`,
`ShiftBody`, `StateMachine`, `MachineStep`, and `ResumeStateMachine`. Raw
`Pure`/`Op` nodes, request constructors, the iterative stepper, prompt ids, and
authority tokens are not public ABI.

`defineEffect(id, owner)` returns an opaque runtime-unique `EffectKey`. `owner`
is a JavaScript `symbol`; TypeScript callers must bind `Symbol(...)` to a named
`const` first so its `unique symbol` type becomes the key's generative phantom
owner. The declaration rejects an inline or otherwise widened `symbol`, because
ordinary TypeScript function calls cannot synthesize a fresh type identity:

```ts
const ConsoleOwner = Symbol("example.Console")
const Console = defineEffect("example.Console", ConsoleOwner)
```

The stable descriptor id remains the supplied non-empty string, while handler
matching uses the unforgeable owner identity. Repeating `defineEffect` with the
same owner symbol and descriptor returns the same key; reusing one owner symbol
with a different descriptor is a programmer-contract error. Distinct symbols
remain distinct runtime and declaration owners even when their descriptor strings
are equal. `key.operation(name, options)` creates an exact `OperationFactory`;
the factory snapshots the key, structured id, result type, argument tuple, and
`Reusable` (default) or `OneShot` multiplicity.

The `Eff` surface is deliberately small:

```text
Eff.pure(value)
Eff.defer(() => computation)
computation.map(f)
computation.flatMap(f)
Eff.runPure(computation)
perform(operation)
handle(computation, handler)
```

`Eff.runPure` accepts `Eff<never, A>` at the TypeScript boundary. If untyped
JavaScript violates that precondition, it raises a programmer-contract `TypeError`
naming the unhandled structured operation. Such a misuse exception is not an
effect, a handler escape channel, or a control failure ABI. Effect execution,
resumption, and prompt capture themselves use no exceptions, Promise, async/await,
generator, thread-local state, or stack inspection.

Class-backed runtime capabilities carry no authority-bearing own properties or
symbols. Public observations such as `EffectKey.id` and `Operation` fields may be
implemented as prototype accessors, but `Eff` nodes, continuations, pending
requests, and prompts keep their state in module-private weak storage. In
particular a computation exposes neither `resumption` nor request/prompt keys, and
a prompt exposes neither its effect key nor its shift operation. Every internal
constructor reachable through an instance's standard `.constructor` property
requires an unexported module authority token before registering the instance;
constructor calls, prototype grafting, or cloned public observations cannot mint a
value accepted by `perform`, `handle`, `reset`, `shift`, or `Eff.runPure`.

`Continuation.local(state, machine)` creates a reusable local continuation backed
by `ResumeStateMachine`. Its `resume` may be invoked repeatedly. This first slice
has no successful save plan: `save()` deterministically performs
`Save.Rejected(UnmanagedCapture("Continuation.local"))`. `SavedContinuation` and
`Restore` are reserved opaque types only; no public constructor or cast-free
successful producer exists.

One-shot `tryResume` returns a discriminated `ResumeAttempt` with either
`{ ok: true, computation }` or
`{ ok: false, rejection: AlreadyResumed(OperationId) }`. The gate changes state
before the accepted continuation builder is invoked. Because one JavaScript agent
runs to completion between event-loop turns, this synchronous compare-and-set
region is atomic for the package's local execution model: all later attempts are
rejected before suffix construction or execution. Copying a resumption object does
not copy its gate.

Prompts use the scoped callback form:

```ts
freshPrompt<R, A>(
  body: <P extends PromptScope>(prompt: Prompt<P, R>) => A
): A
```

`Prompt`, its invariant `P`, and `Control<P>` carry private declaration brands.
There is no prompt constructor. Nested callback binders have incompatible keys,
and `reset` removes only the matching `Control<P>` member from the effect row.
`PromptKeyOf<Prompt<P, R>>` extracts `P` while inferring the concrete invariant
answer type `R`; it never tests an invariant prompt against `Prompt<P, unknown>`.
`shift` exposes a reusable `Continuation` and implements the two-reset law from
§4.2 of the target-neutral specification; the shift body therefore remains under
the same delimiter and is observably `shift`, not `shift0`.

The following is the normative declaration shape for this slice (private
`unique symbol` brands other than the owner markers shown below are omitted from
the listing but are required in the published declaration):

```ts
export interface Effect<
  Id extends string = string,
  Owner extends symbol = symbol
> {}
export interface EffectId { readonly value: string }
export interface OperationId {
  readonly effect: EffectId
  readonly name: string
}

export type ResumeMultiplicity = "Reusable" | "OneShot"
export const ResumeMultiplicity: Readonly<{
  Reusable: "Reusable"
  OneShot: "OneShot"
}>

export type ResumeRejected = Readonly<{
  kind: "AlreadyResumed"
  operation: OperationId
}>
export const ResumeRejected: Readonly<{
  AlreadyResumed(operation: OperationId): ResumeRejected
}>

export interface EffectKey<Fx extends Effect> {
  readonly id: EffectId
  operation<A, Args extends readonly unknown[] = readonly []>(
    name: string,
    options?: Readonly<{ multiplicity?: ResumeMultiplicity }>
  ): OperationFactory<Fx, A, Args>
}
export function defineEffect<
  const Id extends string,
  const Owner extends symbol
>(
  id: Id,
  owner: symbol extends Owner ? never : Owner
): EffectKey<Effect<Id, Owner>>

export interface Operation<
  Fx extends Effect,
  A,
  Args extends readonly unknown[] = readonly unknown[]
> {
  readonly effect: EffectKey<Fx>
  readonly id: OperationId
  readonly multiplicity: ResumeMultiplicity
  readonly args: Args
}
export interface OperationFactory<
  Fx extends Effect,
  A,
  Args extends readonly unknown[] = readonly []
> {
  (...args: Args): Operation<Fx, A, Args>
  readonly effect: EffectKey<Fx>
  readonly id: OperationId
  readonly multiplicity: ResumeMultiplicity
  is(operation: Operation<Fx, unknown>): operation is Operation<Fx, A, Args>
}

export interface Eff<Fx extends Effect, A> {
  flatMap<Fx2 extends Effect, B>(
    next: (value: A) => Eff<Fx2, B>
  ): Eff<Fx | Fx2, B>
  map<B>(f: (value: A) => B): Eff<Fx, B>
}
export const Eff: Readonly<{
  pure<A>(value: A): Eff<never, A>
  defer<Fx extends Effect, A>(body: () => Eff<Fx, A>): Eff<Fx, A>
  runPure<A>(body: Eff<never, A>): A
}>
export function perform<Fx extends Effect, A, Args extends readonly unknown[]>(
  operation: Operation<Fx, A, Args>
): Eff<Fx, A>

export interface Handler<
  Handled extends Effect,
  Residual extends Effect,
  A,
  B
> {
  readonly effect: EffectKey<Handled>
  onReturn(value: A): Eff<Residual, B>
  onOperation<X, Args extends readonly unknown[]>(
    operation: Operation<Handled, X, Args>,
    resumption: Resumption<X, Residual, B>
  ): Eff<Residual, B>
}
export function handle<
  Handled extends Effect,
  Residual extends Effect,
  A,
  B
>(body: Eff<Handled | Residual, A>, handler: Handler<Handled, Residual, A, B>):
  Eff<Residual, B>

export interface Continuation<A, Fx extends Effect, R> {
  resume(value: A): Eff<Fx, R>
  save(): Eff<Save, SavedContinuation<A, Fx, R>>
}
export interface OneShotContinuation<A, Fx extends Effect, R> {
  tryResume(value: A): ResumeAttempt<Fx, R>
}
export type ResumeAttempt<Fx extends Effect, R> =
  | Readonly<{ ok: true, computation: Eff<Fx, R> }>
  | Readonly<{ ok: false, rejection: ResumeRejected }>
export type Resumption<A, Fx extends Effect, R> =
  | Readonly<{
      kind: "Reusable"
      continuation: Continuation<A, Fx, R>
    }>
  | Readonly<{
      kind: "OneShot"
      continuation: OneShotContinuation<A, Fx, R>
    }>

export type CaptureFailure =
  | Readonly<{ kind: "UnmanagedCapture", site: string }>
  | Readonly<{ kind: "CaptureBarrier", site: string, detail: string }>
  | Readonly<{ kind: "OneShotSource", site: string }>
  | Readonly<{ kind: "MissingCodec", site: string, typeId: string }>
  | Readonly<{ kind: "UnsupportedGraph", site: string, detail: string }>
export const CaptureFailure: Readonly<{
  UnmanagedCapture(site: string): CaptureFailure
  CaptureBarrier(site: string, detail: string): CaptureFailure
  OneShotSource(site: string): CaptureFailure
  MissingCodec(site: string, typeId: string): CaptureFailure
  UnsupportedGraph(site: string, detail: string): CaptureFailure
}>

declare const saveEffectOwner: unique symbol
declare const restoreEffectOwner: unique symbol
declare const controlEffectOwner: unique symbol
export interface Save
  extends Effect<"scalascript.control.Save", typeof saveEffectOwner> {}
export const Save: Readonly<{
  key: EffectKey<Save>
  Rejected: OperationFactory<Save, never, readonly [CaptureFailure]>
}>
export interface Restore
  extends Effect<"scalascript.control.Restore", typeof restoreEffectOwner> {}
export interface SavedContinuation<A, Fx extends Effect, R> {
  run(value: A): Eff<Fx | Restore, R>
}

export interface PromptScope {}
export type PromptKeyOf<T> =
  T extends Prompt<infer P, infer _R> ? P : never
export interface Control<P extends PromptScope>
  extends Effect<"scalascript.control.Control", typeof controlEffectOwner> {}
export interface Prompt<P extends PromptScope, R> {}
export type ShiftBody<
  P extends PromptScope,
  A,
  Fx extends Effect,
  R
> = <Residual extends Effect>(
  continuation: Continuation<A, Fx | Residual, R>
) => Eff<Fx | Residual | Control<P>, R>
export function freshPrompt<R, A>(
  body: <P extends PromptScope>(prompt: Prompt<P, R>) => A
): A
export function reset<P extends PromptScope, R, Fx extends Effect>(
  prompt: Prompt<P, R>,
  body: () => Eff<Fx, R>
): Eff<Exclude<Fx, Control<P>>, R>
export function shift<
  P extends PromptScope,
  R,
  A,
  Fx extends Effect = never
>(prompt: Prompt<P, R>, body: ShiftBody<P, A, Fx, R>):
  Eff<Fx | Control<P>, A>

export type MachineStep<S, Fx extends Effect, A> =
  | Readonly<{ kind: "Continue", next: S }>
  | Readonly<{ kind: "Evaluate", next: Eff<Fx, S> }>
  | Readonly<{ kind: "Done", value: A }>
export const MachineStep: Readonly<{
  Continue<S>(next: S): MachineStep<S, never, never>
  Evaluate<S, Fx extends Effect>(next: Eff<Fx, S>): MachineStep<S, Fx, never>
  Done<A>(value: A): MachineStep<never, never, A>
}>
export interface StateMachine<S, Fx extends Effect, A> {
  step(state: S): MachineStep<S, Fx, A>
}
export const StateMachine: Readonly<{
  run<S, Fx extends Effect, A>(
    initial: S,
    machine: StateMachine<S, Fx, A>
  ): Eff<Fx, A>
}>
export interface ResumeStateMachine<S, A, Fx extends Effect, R> {
  resume(state: S, input: A): Eff<Fx, R>
}
export const Continuation: Readonly<{
  local<S, A, Fx extends Effect, R>(
    state: S,
    machine: ResumeStateMachine<S, A, Fx, R>
  ): Continuation<A, Fx, R>
}>
```

### 2.2 Accepted scope of the first slice

This slice is local semantic infrastructure and evidence, not a completed JS/TS
host profile. It includes the explicit `Pure | Op` API, handlers, typed state
machines, local continuations, prompt control, multiplicity, and local conformance.
It does not include generated facades, descriptor consumers, ScalaScript↔JS call
or value bridges, a managed source transform, event-loop adapters, mixed-language
SCC dispatch, successful durable save/run, exact-artifact loading, a portable
runner, or lane-matrix wiring. Those remain later steps in §12.

The package may demonstrate a plain JavaScript callback returning through an
`Eff` suffix, but that is not evidence for a generated `ManagedControl` callback
bridge. Likewise its stackless state-machine vector is local control evidence, not
the mixed JavaScript↔ScalaScript SCC qualification from §8.

## 3. Canonical value mapping

The bridge validates every value; JavaScript representation is never type evidence:

| Canonical value | JavaScript/TypeScript surface |
|---|---|
| `Unit` | `undefined`; `null` is distinct |
| `Boolean` | `boolean` |
| `I32` | integral `number` in signed 32-bit range |
| `I64` | `bigint`; conversion through `number` rejects |
| `BigInt` | `bigint` |
| `Double` | `number`, with bit-preserving durable codec |
| `String` | `string` under the canonical Unicode contract |
| `Bytes` | owned/copy-isolated `Uint8Array` |
| sum/product/option | generated immutable tagged records |
| sequence/map | generated readonly views or copied canonical containers |
| callback | generated typed wrapper with descriptor policy |

I32 wrapping occurs only at canonical operations. I64 is never lossily represented
as `number`. The durable Double codec preserves signed zero and its specified NaN
policy.

A plain object, class instance, function, `Symbol`, proxy, DOM object, Promise, or
other opaque host value requires an explicit local adapter. It is never inferred to
be durable. Cycles and observable aliases require the explicit graph codec.
TypeScript `readonly` is documentation, not runtime saveability evidence.

## 4. Bidirectional calls

JavaScript/TypeScript calls ScalaScript and ScalaScript calls selected JS/TS exports
through one descriptor-first flow:

1. extract public interfaces before bodies execute;
2. generate ESM/`.d.ts` facades and ScalaScript declarations;
3. compile and link managed bodies;
4. bind stable target entrypoints;
5. reject descriptor, codec, target, or capability mismatch before user code.

Pure calls may be direct. Effectful/control calls remain in `Eff`. Converting them
to Promise, exceptions, Node callbacks, event emitters, or framework async types is
an explicit terminal adapter. It does not preserve an enclosing reset unless the
adapter is itself transformed and described as managed.

Stable symbol identity cannot derive from property enumeration order, minified
names, bundler chunk order, or source-map positions.

## 5. Explicit `Eff` and direct style

The explicit stackless `Eff` API is always available and requires no generator,
Promise, native stack capture, or engine proper-tail-call support.

Direct style may use a selected TypeScript/SWC/Babel-equivalent source transform
that lowers to the explicit protocol. A managed region may cross methods/modules
only while every intervening frame and call edge carries managed metadata. Source
maps and diagnostics must remain precise.

Native mechanisms are not alternate semantics:

- generators are one-shot and non-cloneable;
- `async`/`await` and Promise are one-shot scheduling adapters;
- exceptions are host failure channels, not effect operations;
- `eval`, `Function`, proxy, and dynamic property dispatch cannot invent missing
  control metadata.

Each saved run allocates a fresh managed state machine and alpha-renames prompts.
No generator or Promise is reused to simulate multi-shot behavior.

## 6. Callbacks and scheduling

Every callback descriptor records convention, multiplicity, escape, reentrancy,
concurrency, cancellation, and event-loop/worker affinity.

A generated managed callback re-enters the explicit protocol and may cross either
language without dropping handlers. A one-shot callback remains one-shot; copying a
wrapper changes no multiplicity.

The following are barriers by default:

- DOM listeners and browser events;
- Node callbacks and EventEmitter handlers;
- timer, microtask, and Promise reactions;
- streams and async iterators;
- native addon callbacks;
- worker messages and callbacks retained by unknown libraries.

They become managed only through a generated adapter whose descriptor fixes the
re-entry point, scheduler, escape, and invocation policy. Event-loop or worker
migration is explicit scheduling metadata, not ambient semantics.

## 7. Capture and frame gate

Direct capture is valid only through transformed stackless frames. These are
`Unsavable(CaptureBarrier)` unless replaced by an approved durable adapter:

- arbitrary closures, functions, class instances, symbols, proxies, and weak maps;
- Promise, generator, iterator, async iterator, timer, or pending microtask state;
- DOM nodes/events, abort objects, streams, sockets, files, ports, workers, and
  native-addon objects;
- WebAssembly host references, `SharedArrayBuffer`/Atomics, and mutable alias graphs;
- active `try/finally`, cleanup, lock/transaction, or foreign callback frames.

Exact artifact mode never rescues such state. A savable frame contains only
`DurableValue` data and inert `DurableRef`s. Admission verifies the resolver
implementation and capability policy; actual resource lookup remains a typed
post-admission restore effect.

## 8. Mixed JS/TS proper tail calls

The linker builds a typed mixed call graph. A statically resolved SCC whose members
and direct tail edges are transformed lowers to one iterative `TailStep`
dispatcher. The guarantee does not depend on engine proper-tail-call optimization.

Dynamic property calls, proxy/eval, unknown virtual dispatch, native addons, foreign
callbacks, Promise/async boundaries, and untransformed functions are loud tail
barriers. Qualification includes alternating two- and three-function JS/TS↔SSC SCCs
at depth at least 1,000,000.

## 9. `save` and `run`

### 9.1 Portable mode

A hardened JavaScript-hosted CoreIR runner accepts a closed resume program as data.
It does not evaluate generated application JavaScript and needs no original bundle.
The runner has versioned bounded decoding, dependency admission, and resource
limits.

### 9.2 Exact artifact mode

The exact payload binds:

```text
JavaScriptTargetProfile(
  nodeOrBrowserOrWorker,
  engineFeatureSet,
  ecmaAndModuleProfile,
  buildAndMinifierIdentity,
  runtimeControlAbi,
  bundleDigest,
  importManifest,
  initFreeResumeExport
)
```

Loading may initialize runner infrastructure, but cannot call application `main`,
evaluate effectful top-level application modules, replay the prefix, or discover
hidden dependencies. If an ESM graph cannot be loaded without application effects,
it is not exact-resume-safe.

Admission verifies envelope/signature, codecs/fingerprints, exact dependencies and
resolvers, authorization, and quotas before user code. One admitted run creates a
fresh execution and calls the resume entry once. There is no automatic retry;
disconnect after admission is `RunOutcomeUnknown`.

## 10. Packaging and deployment

A release may provide:

- an ESM npm host SDK with `.d.ts`;
- a Node exact-artifact runner;
- a browser/worker portable runner where CSP and capabilities allow it;
- framework adapters over the same explicit API.

Node ESM, browser bundle, worker, serverless isolate, and embedded engine are
different exact profiles even when they share source. The manifest names runtime,
module, import, CSP/capability, and content identity. Unsupported loading fails
before the suffix.

## 11. Conformance

In addition to every common vector, this profile proves:

1. typed calls in both directions, including I32/I64/BigInt and constructors;
2. JS reset → ScalaScript shift and the reverse;
3. capture on either side and local resume on the other;
4. JS→ScalaScript→JS→ScalaScript callback ping-pong;
5. explicit API and transformed direct style equivalence;
6. generators, Promise, async, and native callbacks stay one-shot/barriers;
7. declared event-loop, escape, reentrancy, and worker policies;
8. mixed tail SCCs at depth 1,000,000;
9. repeated/concurrent portable runs in-process, fresh process, and compatible
   remote host, with no prefix replay;
10. exact execution with no application entry/module initializer;
11. raw object/function/Promise/DOM/native resource rejection in exact mode too;
12. missing bundle/import/plugin/resolver, wrong engine/ABI/codec, tampering, quotas,
    expiry/revocation, and outcome-phase failures;
13. portable capsules accepted from and by every other qualified runner.

### 11.1 First-slice acceptance

- [ ] The package root and dry-run tarball expose only the frozen ESM files and
      subpaths, with no production dependency or lifecycle script.
- [ ] The published declarations accept typed effect/handler/state-machine and
      prompt programs while rejecting prompt mixing, effectful `runPure`, forged
      branded values, reusable operations on one-shot continuations, and save on
      one-shot continuations.
- [ ] Two equal descriptor strings with different named owner symbols remain
      different effect rows and runtime keys; a wrong-key handler leaves the
      original effect residual, while one owner+descriptor pair is idempotent.
- [ ] `PromptKeyOf<Prompt<P, ConcreteAnswer>>` extracts exactly `P` without
      weakening prompt answer-type invariance.
- [ ] Opaque computations, continuations, and prompts expose no internal request,
      resumption, key, operation, or authority state; every reachable internal
      constructor rejects calls without the module-private authority token.
- [x] All 17 currently `specified` semantic vectors applicable to an explicit
      local host API produce the shared catalog oracle without changing the shared
      catalog or lane registry.
- [x] One million left-associated binds, one million state-machine transitions,
      and 100,000 deeply handled operations complete without native stack growth.
- [x] Zero/one/many resume, handler reinstall, residual forwarding, return-clause
      placement, shared local heap, nested/fresh prompts, and the true-shift case
      are separately tested.
- [x] A losing one-shot attempt returns structured `AlreadyResumed` before suffix
      construction/execution; local `save` returns structured
      `UnmanagedCapture("Continuation.local")`.
- [ ] `npm test`, `npm run typecheck`, `npm pack --dry-run`, and the project
      `effect*,effects*` conformance slice pass from the isolated worktree.

### 11.2 First-slice results

The initial implementation baseline produced 27/27 package tests, all 17
applicable catalog vectors, the 1,000,000/1,000,000/100,000 stack-safety probes,
green TypeScript fixtures, and 5/5 affected project conformance cases. Independent
pre-integration review then found four qualification blockers: descriptor-only
effect typing, invariant-answer prompt-key extraction, forgeable/leaking runtime
capability state, and an omitted Apache license. The affected acceptance items are
reopened above; final post-hardening package counts, tarball bytes, and verification
evidence are pending implementation.

Even after those items close, the evidence qualifies only the local explicit API
in §2.1--§2.2. Generated facades and value/call bridges, managed direct-style
transformation, callback policies, mixed-language SCC dispatch, exact-artifact
execution, portable runners, and shared lane wiring remain open delivery steps;
the whole host/runner profile is not yet qualified.

## 12. Delivery and architecture gate

The JS/TS milestone is mandatory and independently shippable:

1. freeze values/descriptors and ESM/`.d.ts` facades;
2. implement explicit `Eff` and local vectors;
3. add managed source transform and callback diagnostics;
4. add mixed-language SCC dispatch;
5. add init-free exact-artifact save/run;
6. harden the dynamic portable runner;
7. close N→M and negative rows.

UniML owns syntax only. The self-hosted outer compiler owns lowering, save
transformation, durable codecs, dependency closure, and policy verification. CoreIR
and its one canonical codec remain kernel-owned. JS tooling and runners stay outside
the bootstrap graph.

Profile/design/vector work may proceed now. Frontend/lowering, canonical-codec/
loader, seed-image, and other byte-affecting implementation waits for full P6.5 X1.
The landed P6.6 `C_min` proof is not X1. Every later byte-affecting change reruns
the literal fixed point.

## 13. Profile-specific non-goals

- treating Promise, async/await, generators, exceptions, or raw callbacks as the
  control ABI;
- representing I64 as `number`;
- serializing arbitrary JS closures/objects or rescuing them with exact mode;
- relying on module side effects, application entry, prefix replay, stack
  inspection, or automatic retries;
- claiming a host SDK from a JavaScript-hosted runner alone.
