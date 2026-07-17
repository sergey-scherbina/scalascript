# Swift ↔ ScalaScript bidirectional control profile

Status: **normative host profile / implementation planned** (2026-07-14).

This is the Swift host profile of
[`control-interoperability.md`](control-interoperability.md). It defines how
ordinary Swift code realizes that target-neutral value, call, effect, control,
tail-call, and saved-continuation contract. It does not redefine the common laws.

The existing [`v2-swift-swiftui-native.md`](v2-swift-swiftui-native.md) lane
translates checked CoreIR to Swift AOT code and supplies useful `Pure | Op` and
trampoline evidence. AOT generation alone is not a bidirectional host bridge or a
dynamic saved-continuation runner.

The portable-VM evidence and readiness matrix live in
[`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md).

---

## 1. Scope and qualification

A Swift implementation may claim this profile only when it provides:

- Swift calls to exported ScalaScript definitions returning typed `Eff`;
- ScalaScript calls to explicitly exported Swift definitions;
- control-aware callbacks in both directions;
- a native typed value bridge with canonical schemas/codecs;
- descriptors preserving rows, multiplicity, escape, affinity, and barriers;
- common host, tail, save/run, and cross-backend conformance.

A Swift source generator, C ABI shim, generated executable, or one-way facade is
partial interop. The execution lane, host bridge, explicit API, direct transform,
portable runner, and exact runner are independent capabilities.

## 2. Explicit Swift API

The compiler-independent surface implements the common stackless model. A
conceptual shape is:

```swift
indirect enum SscEff<Fx, A> {
  case pure(A)
  case op(SscOperation, SscResume<Fx, A>)
}

struct SscPrompt<Key, Answer> { }

struct SscSavedContinuation<Fx, Input, Output>: Sendable { }
```

Exact spelling may use generated phantom row markers and nominal schemas, but
cannot collapse effects/input/output to `Any`. Operation payloads and replies are
descriptor-checked. One-shot/reusable multiplicity remains explicit.

The explicit API is always available. Direct `shift`/`reset` requires a
macro/source-generator/compiler-managed state machine. An arbitrary Swift stack,
closure, `async` frame, Objective-C frame, or precompiled function is never
captured.

Swift `async`/`await`, `Task`, `withCheckedContinuation`, Combine, and native
callbacks are adapters or barriers unless transformed into the managed protocol.
Checked continuations remain one-shot.

## 3. Values and calls

The baseline bridge admits canonical scalars, bytes, immutable sequences/maps,
tagged constructors, and generated nominal records/enums. Generated adapters retain
numeric width, optionality, field order, enum case identity, and codec
fingerprints.

| Canonical value | Swift surface |
|---|---|
| `Unit` | `Void` |
| `Boolean` | `Bool` |
| `I32` | `Int32` |
| `I64` | `Int64` — never `Int`, whose width is platform-dependent |
| `BigInt` | approved arbitrary-precision wrapper with pinned ABI/codec |
| `Double` | `Double`, with bit-preserving durable codec |
| `String` | `String` under the canonical Unicode contract |
| `Bytes` | owned/copy-isolated `[UInt8]` or an approved immutable wrapper |

**A ScalaScript `Int` crosses as `Int64`.** ssc `Int` is 64-bit (`v2/specs/10-core-ir.md`
§2, `Int = Long`) and declares canonical `I64`; marshalling it through `Int32` truncates
every value above 2^31−1 at the boundary. Swift's `Int` is deliberately excluded even where
it is 64-bit today: the canonical width must not depend on the host platform. `I32` is
unreachable from ScalaScript source and reserved for an explicit narrowing ABI. See
`specs/numeric-width-reconciliation.md`.

Raw `Any`, class identity, metatypes, selectors, Objective-C objects, Foundation
resources, `UnsafePointer`, `Task`, actor executors, Swift closures, and host
exceptions are not portable values. They require a local adapter or remain a
bridge/capture barrier. `Codable` and `Sendable` alone are not durable evidence.

Generated imports/exports carry the common descriptors and bind stable target
entrypoints before invocation. Pure calls may be direct; effectful/control calls
stay in `SscEff`. Host errors map only through a declared typed adapter.

## 4. Callbacks, actors, and concurrency

Every callback records convention, multiplicity, escape, reentrancy, concurrency,
cancellation, `Sendable`, and actor/executor affinity.

A control-aware callback is invoked only through its generated managed entry. An
escaping callback is rejected unless explicitly saved or its declared lifetime
outlives the reset region. Unknown protocol witness dispatch, Objective-C selector,
reflection, C function pointer, arbitrary escaping closure, and uninstrumented
executor are barriers.

`@Sendable` is a concurrency property, not durability or multi-shot evidence.
`MainActor`/actor/executor choice is an explicit residual effect or runner policy;
ambient current-actor state is not captured semantics.

Every reusable saved run creates independent managed frame state and freshly
alpha-renamed prompt ids. ARC identity and mutable class graphs are not implicitly
copied.

## 5. Managed capture

Capture may cross generated Swift and ScalaScript methods only while every active
frame is managed. By default barriers include:

- Swift `async`/Task/native continuation frames;
- Objective-C/C frames and unknown callback/protocol dispatch;
- active `defer`, destructor/resource scope, transaction, lock, or actor-isolated
  critical section;
- class/object identity, mutable alias graphs, pointers, Foundation/OS resources;
- UI/application lifecycle, thread/actor/executor ambient state.

Crossing a barrier either returns through an explicit effect adapter before capture
or fails with `CaptureBarrier`. There is no replay, stack copying, or silent
one-shot fallback.

## 6. Mixed Swift proper tail calls

A statically linked managed SCC containing generated Swift and ScalaScript entries
lowers to one iterative state dispatcher. Self and mutual tail calls run in constant
managed stack.

Indirect or unclosed protocol/virtual targets, Objective-C/C, reflection, native
callbacks, async/task boundaries, resources/finalizers, and uninstrumented Swift
end the SCC. This profile promises no TCO across arbitrary native Swift calls.
Qualification includes alternating two- and three-function cycles at depth at least
1,000,000.

## 7. `save` and `run`

### 7.1 Frame gate

Both common code modes require a fully durable transitive frame. Exact artifact
mode does not make raw Swift closures, objects, pointers, tasks, actors, UI state,
or resources durable. ARC class identity and cycles require an explicit graph codec;
external identity requires a `DurableRef`.

### 7.2 Portable mode

A separately advertised hardened Swift-hosted CoreIR runner may execute a closed
resume program without the original application. The current AOT generator does not
provide this capability implicitly.

### 7.3 Exact artifact mode

The identity includes:

```text
SwiftTargetProfile(
  platformAndArchitecture,
  swiftToolchainAndAbi,
  packageOrFrameworkDigest,
  runtimeControlAbi,
  codeSigningPolicy,
  resumePointId,
  initFreeResumeEntrypoint
)
```

On platforms that forbid new executable code, the signed resume entry must already
be installed or the capsule routes elsewhere. The profile never weakens platform
loading policy.

The entry cannot launch the application, recreate view/app lifecycle, run effectful
global initialization, or replay the prefix. Runner infrastructure may initialize
without observing application state. If this separation is impossible, the artifact
is not exact-resume-safe.

## 8. Durable references and admission

`DurableRef` decodes as inert nominal data. It carries resolver ABI, provider,
audience, tenant, and capability policy. Pure decode opens no file, network,
Keychain, database, actor, or UI resource.

Admission validates envelope/signature, pinned codecs and fingerprints, exact
primitive/plugin/resolver implementations, authorization, target/artifact, and
quotas before user code or application initialization. Restore then invokes the
authorized Swift resolver as a typed effect and may fail because a resource was
deleted, expired, moved, or revoked.

One admitted run invokes one fresh resume entry once. No automatic retry occurs.
Crash or transport loss after admission may be `RunOutcomeUnknown`.

## 9. Packaging

The host API ships through Swift Package Manager with generated nominal facades and
golden API tests. Framework/app adapters layer over the same contract. Package,
toolchain, platform, architecture, runtime ABI, signing identity, capabilities, and
implementation digests are explicit artifact/profile data.

A C/Objective-C facade may coexist, but raw ABI values and callbacks are foreign
barriers without the managed descriptor.

## 10. Conformance

In addition to every common vector, this profile proves:

1. typed calls and values in both directions;
2. Swift reset → ScalaScript shift and the reverse;
3. capture in either language and local resume in the other;
4. Swift→ScalaScript→Swift→ScalaScript callback ping-pong;
5. explicit API and generated direct-state-machine equivalence;
6. checked/native continuation, async/Task, actor, Objective-C/C, resource, and UI
   barriers;
7. actor/`Sendable`/escape/reentrancy policy enforcement;
8. alternating mixed tail SCCs at depth 1,000,000;
9. repeated/concurrent portable runs where advertised;
10. exact runner restart with no app/view/global initializer or prefix replay;
11. raw object/pointer/task/actor/resource rejection even in exact mode;
12. target/toolchain/signing/ABI/codec/artifact, dependency/resolver, tampering,
    quota, lifecycle, and outcome-phase failures;
13. portable capsules accepted from and by other qualified runners.

Existing checked-CoreIR AOT tests are evidence, not qualification for these rows.

## 11. Delivery and architecture gate

The Swift milestone is mandatory and independently shippable:

1. freeze value/descriptors and SwiftPM facades;
2. implement explicit `SscEff`;
3. add generated managed direct style and callback policies;
4. add mixed SCC dispatch;
5. add installed/pinned init-free exact save/run;
6. harden a portable runner where supported;
7. close N→M and negative rows.

UniML owns syntax only. The self-hosted outer owns lowering, save transformation,
durable codecs, dependencies, and policy. CoreIR and its single codec remain
kernel-owned. Swift SDK/generators/runners remain outside bootstrap.

Profile/design/vector work may proceed now. Frontend/lowering, canonical-codec/
loader, seed-image, and other byte-affecting implementation waits for full P6.5 X1.
P6.6 `C_min` does not close X1; later byte changes rerun the fixed point.

## 12. Profile-specific non-goals

- undelimited `callCC` or arbitrary Swift-stack capture;
- treating async/checked continuations as reusable control continuations;
- inferring durability from `Codable`, `Sendable`, or exact mode;
- serializing object identity, tasks, actors, pointers, UI state, or resources;
- promising dynamic code loading where the platform forbids it;
- placing Swift runtime/SDK code in the portable bootstrap.
