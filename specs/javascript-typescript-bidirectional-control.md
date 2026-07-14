# JavaScript/TypeScript ↔ ScalaScript bidirectional control profile

Status: **normative host profile / implementation planned** (2026-07-14).

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
