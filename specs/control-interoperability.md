# Control interoperability

Status: **normative design freeze / implementation planned** (2026-07-14).

This specification is the sole target-neutral semantic owner of ScalaScript control
interoperability. It defines typed effects, handlers, multi-prompt `shift`/`reset`,
managed callbacks, mixed-language proper tail calls, reusable `save`/`run`,
portable continuation capsules, admission, security, and conformance. A host or
runner profile may choose a physical implementation, but it may not redefine these
laws.

Host and runner profiles:

- [`scala3-bidirectional-control.md`](scala3-bidirectional-control.md) — Scala 3/JVM
  host profile;
- [`javascript-typescript-bidirectional-control.md`](javascript-typescript-bidirectional-control.md)
  — JavaScript/TypeScript host profile;
- [`rust-bidirectional-control.md`](rust-bidirectional-control.md) — Rust host
  profile;
- [`swift-bidirectional-control.md`](swift-bidirectional-control.md) — Swift host
  profile;
- [`control-interop-profile-portable-vm.md`](control-interop-profile-portable-vm.md)
  — portable-VM reference runner profile;
- [`wasm-wasi-control-runner.md`](wasm-wasi-control-runner.md) — hardened
  WASM/WASI runner profile.

Reference and empirical evidence:

- [`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md)
  — portable-VM reference row and the executable readiness matrix. It records
  evidence and status; it does not own or redefine the laws in this document.

Companion specifications:

- [`algebraic-effects.md`](algebraic-effects.md) — ScalaScript effect-row and
  handler surface;
- [`coroutines.md`](coroutines.md) — asymmetric one-shot coroutine primitive;
- [`scala-interop.md`](scala-interop.md) — landed Scala/JVM library and artifact
  interop;
- [`polyglot-libraries.md`](polyglot-libraries.md) — one-reply plugin SPI and
  ordinary host-library facades;
- [`arch-ffi.md`](arch-ffi.md) — explicit platform FFI boundaries;
- [`v2.2-self-hosted-dialect.md`](v2.2-self-hosted-dialect.md) — v2.2
  self-hosting and UniML integration;
- [`../v2/specs/10-core-ir.md`](../v2/specs/10-core-ir.md) — portable inner
  kernel;
- [`../v2/specs/50-effects.md`](../v2/specs/50-effects.md) — `Pure | Op`
  reference substrate.

This document supersedes the former target-neutral ownership claims in the Scala
profile and the old `shift`/`reset` non-goal in `algebraic-effects.md`.
Undelimited `callCC` remains a non-goal.

---

## 1. Outcome and scope

ScalaScript and a supported host language are two source frontends for one managed
control protocol. Code may cross a supported boundary in either direction while
retaining:

- canonical value and function types;
- typed effect rows and deep handlers;
- generative typed prompts and exact delimited-control laws;
- callback multiplicity, escape, reentrancy, concurrency, and affinity metadata;
- proper tail recursion across statically linked managed call cycles;
- reusable `SavedContinuation` values that can cross process and machine
  boundaries;
- deterministic typed failures at unsupported foreign boundaries.

Transparency is conditional on a managed region. It never means reverse engineering
an arbitrary host stack, closure, object graph, resource, or VM frame. A profile must
reject an unsupported capture loudly instead of silently switching to exceptions,
prefix replay, lossy serialization, or a weaker one-shot behavior.

Control interoperability and execution are independent axes:

1. A **host profile** supplies native typed value and call bridges in both
   directions, plus the explicit control API. An import/export facade alone does not
   satisfy this contract.
2. A **runner profile** accepts a saved capsule and starts a fresh execution. A
   runner can exist without a native host-language SDK.
3. A backend that accepts CoreIR only at build time is an AOT backend, not
   automatically a dynamic continuation runner.

The required host families are Scala/JVM, JavaScript/TypeScript, Rust, and Swift.
They ship as independently testable milestones, in an order selected by measured
readiness. WASM/WASI is an extensible runner target and does not by itself satisfy a
missing host bridge.

## 2. Normative architecture

### 2.1 Typed outer layer owns control

Effects, prompts, continuation types, saveability, host descriptors, admission, and
security belong to the typed outer language and its libraries. The untyped inner
kernel evaluates the result of lowering. No SDK, compiler plugin, backend, or
runtime implementation is an independent semantic oracle.

The compiler-independent explicit `Eff` API is the executable reference model.
Direct syntax, macros, compiler transforms, generated code, and target-private fast
paths are checked implementations of that model.

### 2.2 CoreIR stays ordinary and singular

No `Effect`, `Handler`, `Prompt`, `Continuation`, `Shift`, `Reset`, `Save`, or
`Resume` node is added to CoreIR. The portable reference lowering uses ordinary
constructor data, lambdas, application, binding, branching, and proper tail calls:

```text
Comp[Fx, A] = Pure(A) | Op(operation, continuation)
```

This is an outer-language protocol, not a new CoreIR value or term shape. A target
may use a private optimized representation. A generic `effect.*` primitive is
permitted only as a versioned optimization with identical laws and an explicit
dependency manifest; this specification does not presume that such a primitive is
already part of the frozen kernel.

“Canonical CoreIR” means the post-X1 reconciled, versioned inventory and codec. It
does not mean either the historical eleven-node prose or today's implementation by
assumption. The known `While`/`Seq` inventory drift and codec defects must be
reconciled before capsule bytes freeze. Reconciliation grants no continuation node.

Exactly one kernel-owned canonical CoreIR codec exists. The outer layer owns the
capsule envelope, frame and durable-value codecs, dependency closure, save
transformation, and policy verification. Existing `.scir`, `.sscc`, and `irbin`
formats are not continuation-capsule formats unless separately upgraded to this
contract.

### 2.3 UniML stays syntax-only

UniML recognizes and projects `shift`, `reset`, direct effect regions, and saveable
regions as lossless syntax. It defines no control semantics and no runtime
representation. TreeVM constructs syntax; it does not execute, typecheck, lower, or
compile control.

A malformed construct produces a CST diagnostic and an outer typed hole. It never
introduces a CoreIR hole or control node. A `reset` body must be recognized before
strict call-by-value evaluation; it cannot be modeled as an ordinary eager library
call whose body has already run.

### 2.4 Self-hosting and byte gate

Specification, host profiles, descriptors, semantic vectors, compiler-independent
host APIs, and a host-local inline transform that expands exclusively into an
already-frozen explicit host API may proceed now. Such a transform stays outside
the bootstrap graph and cannot claim a ScalaScript frontend, cross-language bridge,
save plan, or kernel behavior. No ScalaScript frontend/lowering,
canonical-codec/loader, seed-image, or other byte-affecting implementation begins
before the full P6.5 `F1 → F2/F3 → L1 → X1` compiler fixed point is green and
frozen.

The landed P6.6 `C_min` stage1==stage2 proof is strong evidence, but it does not by
itself close the full X1 gate. After X1, the project must reconcile and version the
CoreIR inventory and codec, then rerun the full literal fixed point. Every later
byte-affecting compiler or kernel change reruns it.

Host SDKs, macros, plugins, artifact launchers, admission services, crypto, storage,
networking, and resource resolvers stay outside the seed and compiler image. Pure
portable envelope/frame codecs belong in the self-hosted outer layer. External
operations are explicit capabilities or plugins.

### 2.5 Platform isolation

Portable `.ssc` code still cannot import `java.*`, `scala.*`, host globals, or
platform object types. Platform access uses `std.*`, a plugin intrinsic, an explicit
FFI annotation, or a backend-specific block. Raw FFI and native callbacks are
capture barriers unless a host profile explicitly adopts them into its managed
transform.

No public control ABI may expose interpreter values, VM frames, `SpiValue`,
`AnyRef`, reflective handles, target stack objects, or hidden exception protocols.

## 3. Reference computation model

The semantic model is stackless:

```text
Eff[Fx, A] =
  Pure(value: A)
  | Op(
      operation: Operation[Fx, X],
      continue: X -> Eff[Fx, A]
    )
```

The notation is explanatory. A host API may use an equivalent allocation-conscious
encoding if it preserves all laws:

1. `flatMap` is stack-safe.
2. `Op.continue` is reusable unless the operation declares one-shot multiplicity.
3. A deep handler reinstalls itself around every continuation invocation.
4. An unhandled operation is forwarded with its continuation intact.
5. A handler return clause applies once to the handled computation's terminal
   result.
6. Target-private coroutines, generators, fibers, native stack switching, and
   bytecode paths are optimizations, never alternate semantics.

Every operation has a stable nominal identity, structured argument and result types,
an effect-row member, and resume multiplicity:

```text
operation : Args -> Result ! EffectKey
multiplicity : OneShot | Reusable
```

A reusable operation may resume zero, one, or many times. A one-shot operation may
resume at most once. Persistence never upgrades `OneShot` to `Reusable`.
Asymmetric coroutines and checked/native one-shot continuations remain one-shot
even if their frame is encodable.

The common rejection model is:

```text
EffectId(value: String)
OperationId(effect: EffectId, name: String)
ResumeRejected = AlreadyResumed(operation: OperationId)
tryResume(value): Either[ResumeRejected, Next]
```

The one-shot claim is atomic and eager: exactly one concurrent caller wins, and
every loser receives `AlreadyResumed` before it can invoke the continuation or
execute any part of the suffix.

### 3.1 Typed `.ssc` multiplicity projection

The raw `Pure | Op`/CoreIR library constructor is reusable. The typed `.ssc`
declaration surface supplies the missing operation metadata:

```text
effect E       => OneShot
multi effect E => Reusable
```

The Scala host profile continues to use each `Operation.multiplicity` value
directly (and its low-level default remains `Reusable`). A source-level `.ssc`
one-shot `resume(value): R` cannot expose an `Either` without changing every
handler result type, so it is defined as checked sugar over `tryResume`: success
continues with `R`; rejection aborts the run with the structured boundary envelope
`ControlRunFailure(AlreadyResumed(operation))`. This envelope is not a second
rejection algebra and user `.ssc try/catch` does not intercept it. Its stable
boundary projection keeps code and message separate:

```text
code     = "ONESHOT_VIOLATION"
message  = "One-shot violation: <Effect>.<op> resumed more than once"
rendered = "error [ONESHOT_VIOLATION]: One-shot violation: <Effect>.<op> resumed more than once"
```

The structured `OperationId` and rejection constructor, not exception class-name
or message parsing, are the embedding contract. The Scala host API exposes the
same law without raising by returning
`Left(ResumeRejected.AlreadyResumed(operation))`.

The existing plugin `reply(op, args): SpiValue` shape remains a one-reply
in-process adapter. It does not receive a managed continuation and is not the
transport, durable-value, or multi-shot ABI.

## 4. Multi-prompt `shift` and `reset`

### 4.1 Types and identity

A prompt has a fresh outer type key and an erased runtime identity:

```text
Prompt[P, R]
Control[P]
Continuation[A, Fx, R]
```

`P` prevents a control operation for one prompt from being discharged by another.
`R` is the answer type of the reset. The captured `Continuation` has sealed its
matching prompt, so `resume` takes only an `A`.

Runtime `PromptId` is an explicit managed token. Ambient thread-local state, host
object identity, and source-local strings are not the contract. Each saved `run`
freshly alpha-renames captured prompt ids while preserving all internal equality
and inequality relations. Concurrent runs cannot interfere through prompt identity.

Only prompt binders closed inside the saved region are portable. A free external
prompt in a residual row is rejected unless a separately specified durable prompt
capability represents it.

### 4.2 Exact laws

For prompt `p`, `reset_p` folds `Eff` as follows:

```text
reset_p(Pure(v)) =
  Pure(v)

reset_p(Op(Shift(p, f), k)) =
  reset_p(f(Continuation(a => reset_p(k(a)))))

reset_p(Op(other, k)) =
  Op(other, a => reset_p(k(a)))
```

`other` includes a control operation for a different prompt. A reset removes only
its own `Control[P]`.

Both reset reinstalls in the middle law are required. Omitting the outer reset gives
`shift0`-like behavior, not the `shift` specified here. Observable consequences:

- the nearest dynamically enclosing reset with the same prompt captures;
- the handler may discard, invoke once, or invoke repeatedly;
- nested fresh prompts do not interfere;
- residual effects retain order and handler context;
- the suffix executes exactly once per explicit resume;
- local multi-shot resume copies control, not the whole process heap.

Ordinary local resumes share normal mutable heap objects. Pure branching state is
expressed by a `State` effect, not implicit heap snapshots.

### 4.3 Surface and direct style

The explicit `Eff` API is always available on every conforming host. Direct-style
syntax is available only inside compiler- or macro-managed regions. If a transform
cannot prove the region, there is no transparent fallback.

The ScalaScript surface conceptually provides:

```text
freshPrompt[R] : ScopedPrompt[R]
reset[P, Fx, R](Prompt[P, R], Eff[Fx | Control[P], R]) : Eff[Fx, R]
shift[P, A, Fx, R](
  Prompt[P, R],
  Continuation[A, Fx, R] -> Eff[Fx | Control[P], R]
) : Eff[Fx | Control[P], A]
```

Exact punctuation is frozen with parser vectors. The base contract is
answer-type-preserving. Answer-type modification requires a later type-system and
ABI extension.

Undelimited `callCC`, arbitrary whole-program capture, `dynamic-wind`, and escape
through unknown foreign frames are not part of this contract.

## 5. Managed host interoperability

### 5.1 Complete host claim

A host profile may claim bidirectional control interoperability only when it
provides all of:

- a native typed **value bridge** for the canonical public value algebra;
- a native typed **call bridge** for ScalaScript→host and host→ScalaScript calls;
- the explicit `Eff`, operation, handler, prompt, continuation, and
  `SavedContinuation` API;
- descriptors for types, effect rows, prompts, callback policies, and
  multiplicities;
- managed callback and direct-style rules;
- deterministic capture and tail barriers;
- the common conformance vectors in both directions.

An FFI import, generated library facade, callback that returns one reply, async
wrapper, or artifact loader is useful but insufficient by itself.

### 5.2 Callback convention

Each callback parameter is classified before linking:

```text
callingConvention =
  PureDirect | Effectful | ManagedControl | ForeignBarrier

invocationMultiplicity =
  AtMostOnce | Many | Unknown

escape =
  NoEscape | MayEscape
```

The descriptor also records reentrancy, concurrency, cancellation, and
thread/actor/event-loop affinity. `ManagedControl` is valid only when every
invocation site enters the managed protocol. Passing the callback through
precompiled, reflective, native, unknown virtual, or uninstrumented code creates a
barrier.

A callback that may escape its reset scope is rejected or explicitly saved. A
native one-shot callback is never silently upgraded to reusable.

### 5.3 Managed capture region

Direct capture is valid only when every active frame between `shift` and its matching
reset is represented by the explicit stackless protocol or an approved compiler
transform. At minimum these are barriers unless a profile supplies a specific
transform and restoration law:

- active `finally`, defer/destructor, resource scope, transaction, lock, or monitor;
- native stack frame, FFI callback, raw pointer, reflective or unknown call target;
- live future/task/generator/coroutine not represented by the managed protocol;
- thread-, actor-, event-loop-, or executor-affine state;
- ambient thread-local, security, process, or host-global state.

The diagnostic identifies the capture site and first offending frame/value. The
runtime never falls back to stack inspection, hidden exceptions, or partial capture.

### 5.4 Feature exposure

Every portable user-visible ScalaScript runtime/library feature must declare, per
host profile, one exposure category:

- native typed API;
- generated facade/export;
- direct-style transform;
- tooling-only operation;
- explicit platform adapter;
- target-inapplicable with a normative reason and diagnostic.

Portable runtime features cannot remain unclassified. The unavailable category
does not waive the goal of complete host coverage; it documents a deliberate
target limitation.

## 6. Mixed-language proper tail calls

Proper tail recursion across ScalaScript and host code is guaranteed only for a
statically linked, fully managed strongly connected component. The compiler/linker
builds a typed mixed call graph and rewrites eligible tail edges through an
iterative target-specific `TailStep`/dispatcher. The dispatcher is generated and
does not weaken public types.

Pure SCCs use a minimal tail dispatcher. Effectful/control SCCs use the stackless
effect trampoline. Neither protocol must wrap the other.

Unknown virtual, indirect/higher-order, reflective, precompiled foreign, native,
resource/finalizer, and uninstrumented callback edges are loud tail barriers.
Non-tail calls retain normal stack behavior. A conforming profile proves two- and
three-function mixed cycles at depth at least 1,000,000 without unbounded managed or
native stack growth.

## 7. Structured descriptors and identities

Managed interop uses three deterministic, separately versioned records:

1. `ApiDescriptor`, available before bodies compile, owns public type and control
   identity.
2. `ControlSummary`, emitted after body compilation, owns managed/foreign call
   edges, tail edges, save sites, frame schemas, and barriers.
3. `ArtifactManifest`, emitted after linking, binds the first two records to target
   entrypoints and concrete implementations.

Minimum `ApiDescriptor` fields:

```text
schemaVersion
controlAbiVersion
moduleId
apiHash
symbols[
  stableSymbolId
  qualifiedName
  kind
  structuredType
  typeParameters / bounds / variance
  parameterLists / names / defaults / implicits
  resultType
  effectRow
  operationResumeMultiplicity
  callbackPolicies
  promptAndControlMetadata
  requiredCapabilities / targets
]
```

Minimum `ArtifactManifest` fields:

```text
artifactManifestVersion
apiHash
target
targetEntrypoints
programDigest?
artifactDigest?
runtimeVersion
dependencyManifest
dependencyProfileDigest
controlSummaryDigests
```

A stable symbol id derives from module id, qualified name, and canonical ABI
signature. It never depends on source order, filesystem order, unstable positions,
or target-generated names alone.

The initial digest algorithm is SHA-256 with explicit domain separators and
self-field exclusions:

```text
apiHash =
  SHA-256("ssc-api-v1\0" || canonical(ApiDescriptor without apiHash))

resumeCodeDigest =
  SHA-256("ssc-coreir-resume-vN\0" || canonical(closed resume program))

dependencyProfileDigest =
  SHA-256("ssc-dependencies-vN\0" || canonical(exact dependency manifest))

frameDigest =
  SHA-256("ssc-frame-vN\0" || canonical(frame schema and payload))

capsuleHash =
  SHA-256("ssc-capsule-vN\0" || canonical(capsule without hash/signature))
```

`artifactDigest` identifies concrete target artifact bytes. `resumeCodeDigest`
identifies the state-abstracted portable resume program. `frameDigest` identifies
captured state. `dependencyProfileDigest` identifies exact semantic and target
implementations. These identities are not interchangeable.

Canonical public numeric types are `I32` and `I64`; a profile maps native carriers
without guessing from spelling. **ScalaScript source `Int` and `Long` both declare `I64`**:
ssc `Int` is 64-bit two's-complement wrapping (`v2/specs/10-core-ir.md` §2, `Int = Long`),
so any profile that marshals an ssc `Int` through a 32-bit carrier truncates every value
above 2^31−1 at the boundary. `I32` stays in the algebra but is unreachable from ScalaScript
source and is reserved for an explicit narrowing ABI. Source-width evidence survives lowering
as `AbiType.Primitive.declaredWidth` (`DeclaredInt`/`DeclaredLong`) — it keeps overload
identity exact and never changes marshalling — and a bare integer width is **rejected** as an
ambiguous legacy export (`AMBIGUOUS_NUMERIC_WIDTH`) rather than defaulted. See
`specs/numeric-width-reconciliation.md`.

## 8. `save` and `run`

### 8.1 Public contract

The public idiom is intentionally small:

```text
Continuation[A, Fx, R].save()
  : Eff[Save, SavedContinuation[A, Fx, R]]

SavedContinuation[A, Fx, R].run(value: A)
  : Eff[Restore | Fx, R]
```

Runtime frames, save plans, code modes, and capsules are opaque implementation
details. A compiler-generated hidden save plan or a public typed
defunctionalized/state-machine builder supplies evidence; the runtime never invents
it through reflection.

A `SavedContinuation` produced from a reusable semantic continuation is immutable,
copyable, and reusable:

1. `save` does not consume the reusable source continuation.
2. `run` may be called zero or more times, sequentially or concurrently.
3. Each admitted run creates a fresh execution and fresh decoded frame graph.
4. Each run begins directly at the capture point with its supplied input.
5. The computation before capture, module `main`, and application initializers are
   never re-executed to reconstruct state.
6. The resume entry is invoked once per admitted run. The suffix may itself loop,
   perform effects, or resume nested continuations.
7. The runtime never automatically retries after admission.
8. A crash may produce `RunOutcomeUnknown`; it does not consume the saved value.

This is multi-shot resumption, not prefix replay. A provider may expire or revoke
future admission, but that lifecycle policy does not change continuation
multiplicity.

A one-shot source returns a separately typed one-shot saved form, if a profile
supports one at all. Encoding a one-shot frame never yields the reusable type above.
Atomic one-shot workflow claims are optional policy, not `SavedContinuation.run`
semantics.

### 8.2 Snapshot law

Ordinary local resume shares the current heap. `save` is an explicit persistence
boundary:

- mutation of an original value after save cannot change the saved frame;
- each run reconstructs an independent local captured graph;
- mutation in one run is invisible to another;
- a `DurableRef` intentionally names shared external state and is re-authorized on
  each run;
- prompt ids are freshly alpha-renamed per run.

Alias relations are preserved inside one decoded run only when an explicit graph
codec says so. Distinct runs still receive distinct graphs. No implicit transaction,
rollback, or whole-heap snapshot is created.

### 8.3 Independent physical axes

Every capture is classified on two independent axes:

```text
CodeMode =
  Portable(closedCoreIrResumeProgram)
  | ExactArtifact(target, toolchain, artifactDigest, resumePointId)

FrameGate =
  Savable(frameSchema, DurableValueGraph)
  | Unsavable(firstCaptureBarrier)
```

`CodeMode` answers where executable suffix code comes from. `FrameGate` answers
whether the live captured state is durably representable. Exact artifact pinning
does not rescue a raw foreign object, live resource, native closure, or stack frame.
`save` succeeds only for `Savable`.

`Portable` contains a closed ordinary CoreIR resume program. A target may cache or
accelerate it, but the cache is outside semantics and may be discarded.

`ExactArtifact` pins the target, ABI/toolchain identity, artifact bytes, and an
init-free resume entry. It packages non-CoreIR managed segments without pretending
to translate arbitrary host code into CoreIR. The runner must not invoke application
`main`, prefix code, or effectful/static application initialization.

## 9. Durable values and references

### 9.1 `DurableValue`

The baseline closed durable algebra contains:

- `Unit`, `Boolean`, `I32`, `I64`, `BigInt`, IEEE-754 `F64`, `String`, and `Bytes`;
- immutable nominal sum/product data with versioned schema identity;
- immutable sequences and canonical-key maps whose elements are durable;
- compiler-generated defunctionalized closures, prompts, and handler state;
- explicit `DurableRef` values;
- explicit graph-codec nodes where a declared schema supports aliasing/cycles.

Encoding is deterministic and bounded. Floating-point bit identity, including
signed zero and NaN payload policy, is specified by the codec. Map/set order cannot
affect canonical bytes.

The following are never durable by inference:

- interpreter/runtime closures and values;
- `SpiValue.Opaque`, `ForeignV`, host objects, reflective handles, and native
  functions;
- live files, sockets, threads, futures/tasks, locks, transactions, and allocators;
- host mutable cells/collections with unmodeled aliasing;
- arbitrary cyclic graphs.

### 9.2 `DurableRef`

A durable reference is inert frame data:

```text
DurableRef(
  nominalTypeId,
  schemaHash,
  providerId,
  resolverSemanticAbiId,
  resolverImplementationDigest,
  audience,
  tenant,
  capabilityPolicy,
  opaqueReference
)
```

Decoding is pure and does not open or contact the resource. Admission proves that
the declared resolver implementation, capability policy, and authorization context
are available and allowed. Actual resolution is an explicit typed restore effect
after admission. The resource may have been deleted, expired, moved, or revoked;
that is a typed operational restoration failure, not capsule corruption.

Secrets and bearer material require an explicit protected codec and policy. A
content hash is not a bearer credential and raw capabilities must not be logged.

### 9.3 Graph codecs

Mutable, aliased, or cyclic state requires an explicit versioned graph codec. It
defines node identity, canonical traversal/order, allowed mutation, schema
evolution, per-run reconstruction, and limits. Without it, save fails with
`UnsupportedGraph`.

## 10. Capsule

### 10.1 Logical shape

The transport envelope is target-neutral:

```text
SavedContinuationCapsule(
  capsuleFormatVersion,
  coreIrVersion,
  controlAbiVersion,
  targetProfile,
  inputTypeFingerprint,
  resultTypeFingerprint,
  effectRowFingerprint,
  inputCodec,
  resultCodec,
  frameSchemaHash,
  frameDigest,
  framePayload,
  dependencyProfileDigest,
  requiredPrimitives,
  requiredPlugins,
  requiredResolvers,
  requiredCapabilities,
  payload =
    Portable(resumeCodeDigest, closedResumeProgram)
    | ExactArtifact(
        target,
        toolchainAbi,
        artifactDigest,
        resumePointId,
        initFreeEntrypoint
      ),
  lifecycle,
  audience,
  tenant,
  limits,
  capsuleHash,
  signature
)
```

The two payload variants are mutually exclusive. The frame is always a
`DurableValue` graph and therefore independent of `CodeMode`.

For `Portable`, the closed program's entry is semantically:

```text
(decodedFrame, input) => Eff[Fx, R]
```

It contains the resume segment and transitive reachable definitions, not the
original application entry. All application globals are materialized before closed
program verification. Runtime primitives/plugins/resolvers remain explicit manifest
dependencies.

### 10.2 Compiler generation

The capsule is generated only at a compiler-declared saveable region:

```text
CPS/state split
→ liveness over the transitive value/dependency graph
→ closure conversion and defunctionalization
→ DurableValue quoting or DurableRef replacement
→ resume-segment closure
→ deterministic manifest and frame emission
```

It is never reconstructed after the fact from a current host stack, VM frame,
`ClosV`, native closure, or arbitrary object. Already evaluated pure globals are
captured by value. Effectful globals and initializers are represented by explicit
durable protocols or rejected.

Declared suffix effects such as time, randomness, or IO stay in `Fx` and execute on
each run. They are not snapshotted ambient reads.

### 10.3 Dependency closure

The manifest names exact primitive, plugin, resolver, codec, capability, and target
implementations. Dynamic string registries are compiler-emitted dependencies, not
reliably rediscovered by a final AST walk.

A portable code payload is executable on a target only when every declared semantic
ABI has an approved target implementation. Otherwise admission rejects it; CoreIR
syntax alone does not make target-specific plugins portable.

Content-addressed storage may deduplicate code and frames, but all referenced bytes
and digests resolve before admission completes. A fully packed portable capsule does
not require the original application artifact. An exact-artifact capsule pins its
artifact until expiry/revocation policy permits collection.

## 11. Admission, restoration, and execution

### 11.1 Atomic admission

Admission is atomic and happens before user suffix code. It validates, without
executing application code:

1. framing, size/depth/item bounds, canonical encoding, capsule hash, and signature;
2. audience, tenant, expiry/revocation, authentication, authorization, and quotas;
3. pinned capsule, CoreIR, control, descriptor, and codec versions;
4. input/result/effect/frame type and schema fingerprints;
5. closed CoreIR, symbol grammar, direct-call arity, and allowed primitives;
6. the exact primitive/plugin/codec/resolver/capability implementation manifest;
7. for `ExactArtifact`, target/toolchain/artifact identity and init-free entrypoint;
8. resource budgets for a fresh execution.

There is no hidden dependency discovery after admission. A missing implementation
rejects the run before user code. Resource **liveness** is different: a valid inert
`DurableRef` may resolve to a deleted or revoked resource after admission and return
a typed restore failure.

### 11.2 Run phases

One explicit `run(input)` has these phases:

```text
transport receive
→ atomic admission
→ allocate ExecutionId and fresh managed runtime
→ pure frame decode and prompt alpha-renaming
→ typed DurableRef restoration
→ invoke init-free resume entry once
→ execute suffix Eff
→ return result or typed failure
```

No phase invokes application `main`, prefix code, or an event log. No runtime or
client adapter automatically retries after admission.

`TransportUnavailable` means dispatch failed before admission and no suffix began.
A disconnect or crash after admission yields `RunOutcomeUnknown`; the resume entry
or some suffix effects may already have executed. Reusing the saved continuation is
an explicit caller choice and may repeat suffix effects.

### 11.3 Residual effects

An in-process run returns `Eff[Restore | Fx, R]` to handlers at the call site. A
remote runner must either require `Fx` to be closed or accept an explicit,
authenticated `RemoteRunEnvironment[Fx]`. It rejects an unavailable environment
before the suffix. Streaming operations back to the original caller is a separate
distributed-handler protocol and not part of the base contract.

## 12. Transport, lifecycle, and security

A capsule may travel over an ordinary binary codec through HTTP, a queue, database,
file, or application message. Transport portability does not imply every target can
execute every `CodeMode`.

An inline exact artifact has a finite retention policy because offline copies cannot
be counted. Provider-backed identities may be revoked or deleted. Runs admitted
before successful revocation may finish; none admitted afterward may start. A fully
packed portable capsule may be perpetual if trust and retention policy allow.

Every runner treats the capsule as executable capability material:

- require accepted signer/origin policy;
- bind tenant, audience, target, types, effect row, dependencies, and limits;
- authenticate provider restore/run/revoke operations;
- encrypt payloads and references where policy requires;
- use an incremental bounded decoder;
- enforce decode bytes/depth/items and execution fuel, heap, wall-time, and
  concurrency limits;
- isolate untrusted code within its declared capability set.

Reusable continuations amplify work; admission quotas are mandatory, not an
operational afterthought.

## 13. Failures

Failures are typed values/effects, never hidden exception class-name protocols:

| Failure | Meaning |
|---|---|
| `AlreadyResumed` | a second or concurrent losing claim tried to invoke a one-shot continuation |
| `UnmanagedCapture` | a frame lacks a stackless/compiler-managed representation |
| `CaptureBarrier` | an active frame or live value forbids capture |
| `OneShotSource` | reusable save was requested from a one-shot suspension |
| `MissingCodec` | a live value has no approved durable codec |
| `UnsupportedGraph` | aliases/cycles/mutation lack an explicit graph codec |
| `AbiMismatch` | CoreIR/control/descriptor/type identity differs |
| `CodecMismatch` | value/frame codec ABI or schema differs |
| `MissingDependency` | primitive/plugin/resolver/artifact implementation is absent |
| `CapabilityDenied` | authorization or declared capability policy rejects |
| `ResourceUnavailable` | a valid durable reference cannot currently rehydrate |
| `TamperedCapsule` | canonical hash/signature/audience/tenant validation failed |
| `ExpiredOrRevoked` | lifecycle forbids a new admission |
| `ResourceLimit` | bounded decode or execution quota was exceeded |
| `TransportUnavailable` | dispatch failed before admission |
| `RunOutcomeUnknown` | failure occurred after admission; suffix occurrence unknown |

Diagnostics identify the capture/run site, profile, and first offending frame,
dependency, value, or policy. Target profiles may add structured detail but cannot
collapse these distinctions.

## 14. Profile conformance and N→M matrix

### 14.1 Semantic vectors

Every conforming execution lane runs:

- fresh/nested prompts and nearest matching reset;
- same-prompt shift inside the shift body, distinguishing `shift` from `shift0`;
- zero-, one-, and many-resume handlers;
- deep handler reinstall, residual forwarding, and return-clause placement;
- local shared-heap behavior and explicit pure-state branching;
- stack safety, cancellation, and one-shot violations;
- deterministic managed-capture and foreign-barrier diagnostics.

### 14.2 Host vectors

Each host profile proves:

- ScalaScript reset → host shift and host reset → ScalaScript shift;
- capture in one language and local resume in the other;
- host→ScalaScript→host→ScalaScript callback ping-pong;
- handlers authored on either side;
- explicit `Eff` equivalence with every direct-style tier;
- separate compilation and descriptor mismatch rejection;
- mixed two-/three-node tail SCCs at depth 1,000,000;
- target-specific native async/resource/affinity barriers.

### 14.3 Saved-continuation vectors

Required common cases:

1. A prefix counter runs once; repeated runs never increment it.
2. Different inputs produce independent suffix results.
3. At least 100 concurrent runs succeed from one immutable capsule.
4. Each accepted failure-free run invokes the resume entry once; no automatic retry.
5. Mutation after save does not alter the snapshot; runs are locally isolated.
6. Zero-slot, nominal-data, and explicit alias/cycle graph frames round-trip.
7. Captured prompts are fresh across concurrent runs; free prompts reject.
8. Transitive globals and ambient dependencies are frozen, explicit, or rejected.
9. Encode → network/process boundary → decode → run on another machine.
10. Fresh-process and process-restart runs do not need the original process.
11. Portable code runs without the original application artifact.
12. Exact artifact routes to pinned bytes and never executes `main`, prefix, or
    application initialization.
13. Missing dependency/resolver/artifact and raw foreign values reject.
14. Deleted/expired/revoked resources return the correct admission or restore
    failure according to phase.
15. Signature, tenant, audience, decode, fuel, heap, time, and concurrency limits
    are enforced.
16. Pre-admission transport failure and post-admission unknown outcome remain
    distinct.

### 14.4 Cross-backend directions

For every pair of qualified runners `N` and `M`, the matrix contains:

- save/encode on N → decode/run on M for a portable capsule;
- save/encode on M → decode/run on N;
- fresh-process and concurrent multi-shot cases in both directions;
- equal observable results and typed failure categories;
- negative rows for missing dependency, unavailable resolver, target-bound exact
  artifact, raw foreign state, signature, and quota.

Full interoperability is not declared until JVM, JavaScript, Rust, and Swift have
qualified host bridges and N→M runners. Milestones remain independently shippable;
the matrix records empirical readiness rather than changing semantics.

## 15. Delivery sequence

The order protects the self-hosting fixed point:

1. Freeze this target-neutral contract, profiles, descriptors, diagnostics, and
   vectors without changing measured compiler/kernel bytes.
2. Implement compiler-independent explicit APIs and typed state-machine builders
   outside the bootstrap graph. Host-local lexical macros may be differentially
   qualified against those APIs here when they introduce no compiler/kernel bytes.
3. Complete and freeze full P6.5 X1.
4. Reconcile/version CoreIR inventory, canonical codec, and numeric-width evidence;
   rerun the full fixed point.
5. Add ScalaScript lowering to ordinary `Pure | Op` plus managed save plans.
6. Deliver Scala/JVM, JavaScript/TypeScript, Rust, and Swift host/runner milestones
   in readiness order, each with native typed value and call bridges.
7. Qualify the WASM/WASI runner independently.
8. Expand the empirical N→M matrix until every mandatory direction is green.

No host SDK becomes a dependency of UniML, the seed, the kernel codec, or the
self-hosted compiler image.

## 16. Non-goals

- undelimited `callCC` or arbitrary whole-stack capture;
- a continuation/effect node or host type in CoreIR;
- reverse engineering an arbitrary current stack, closure, object, VM frame, or
  native coroutine;
- prefix, initializer, `main`, or event-log replay;
- automatic retry after admission or implicit exactly-once external effects;
- upgrading one-shot control to reusable through encoding;
- pretending exact host artifacts are portable CoreIR;
- making `SpiValue`, raw FFI, a legacy reflective loader, or a one-reply plugin the
  managed control ABI;
- cross-version frame migration without an explicit audited migration;
- implicit serialization of mutable/cyclic graphs or live resources.

Optional policies may add one-shot workflow claims, idempotency keys, version
migration, delivery journals/outboxes, richer graph codecs, or resource
virtualization. They cannot redefine base `save`/`run`.

## 17. Acceptance checklist

- [ ] Explicit `Eff` and every fast path satisfy the same effect/control vectors.
- [ ] Generative prompts and exact `shift` laws preserve residual effects.
- [ ] Every qualified host has typed bidirectional value and call bridges.
- [ ] Callback, prompt, effect, barrier, and tail metadata round-trip without
      reflection.
- [ ] Mixed managed SCCs run at depth 1,000,000 in constant managed stack.
- [ ] Reusable saved continuations run repeatedly and concurrently with fresh
      frames and no prefix replay.
- [ ] `Portable` and `ExactArtifact` remain independent from frame saveability.
- [ ] Durable values, references, graphs, admission, restore, and execution phases
      have distinct tested failures.
- [ ] Portable capsules cross process and machine boundaries without the original
      process or artifact.
- [ ] Exact artifacts use an init-free resume entry and pinned implementation.
- [ ] The N→M matrix covers all qualified runners and required negative rows.
- [ ] The full P6.5 X1 gate and every later byte-affecting fixed-point rerun remain
      green.
