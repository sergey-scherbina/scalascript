# WebAssembly/WASI saved-control runner profile

Status: **normative runner profile / implementation planned** (2026-07-14).

This is a runner target profile of
[`control-interoperability.md`](control-interoperability.md). It defines how a
WebAssembly/WASI deployment admits and executes target-neutral saved continuations.
It does not define a WebAssembly host-language SDK, claim arbitrary
JavaScript/Rust/Swift interop, or redefine common control semantics.

The existing v2 WASM lane in
[`../v2/specs/63-backend-wasm.md`](../v2/specs/63-backend-wasm.md) is an AOT
cross-target of generated Rust. It proves checked CoreIR can be translated and run,
but is not a versioned dynamic CoreIR loader and does not satisfy this runner
profile by itself.

Reference evidence lives in
[`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md).

---

## 1. Runner contract

A conforming runner exposes one typed logical operation:

```text
run(capsuleBytes, inputBytes, admissionContext) -> RunResultBytes
```

Physical transport may be CLI, WASI streams, HTTP, queue, or WIT world. It adds no
language semantics. Inputs/results are bounded target-neutral codec products.
Results distinguish success, pre-admission rejection, execution/restore failure,
and `RunOutcomeUnknown`.

The runner advertises independently:

```text
PortableRunner(coreIrVersion, controlAbiVersion, dependencyProfiles)
ExactWasmArtifactRunner(targetProfile, artifactAbiVersion)
ResolverAndEffectCapabilities(implementations)
```

Advertising a runner does not advertise a WASM host SDK. A future host profile
requires its own native typed value and call bridges.

## 2. Portable execution

The mandatory baseline is `CodeMode.Portable`. A generic CoreIR evaluator compiled
for the declared WASM/WASI target accepts the closed resume program as data, applies
the freshly decoded `(frame, input)` to its arity-two entry, and evaluates the
resulting `Eff`.

It does not regenerate Rust/JavaScript/Swift source or invoke an application
compiler during `run`. It implements the complete reconciled CoreIR contract and
proper tail calls with an explicit control loop/trampoline, not the WebAssembly or
host call stack.

Each run allocates a fresh execution id, linear memory/runtime instance, frame graph,
handler state, and prompt namespace. Reusable/concurrent runs share no mutable
runtime state unless an authorized `DurableRef` names external state.

## 3. No start or prefix replay

The portable runner invokes only the generated resume entry. It never invokes the
original program entry or `_start`, and never reruns application/static
initializers, the prefix, or an event log.

An exact WASM artifact, when supported, is valid only with a versioned init-free
resume export and no application-visible start function. Mandatory instantiation
work must be runner-only, deterministic, and unable to observe or repeat application
effects. A module whose start section or `_start` can execute user code is rejected
before suffix execution.

## 4. Code mode and frame gate

The common axes stay independent:

```text
CodeMode =
  Portable(closed CoreIR)
  | ExactArtifact(pinned module/component and resume export)

FrameGate =
  Savable(DurableValue/DurableRef)
  | Unsavable(CaptureBarrier)
```

Portable is mandatory. Exact artifact is optional and pins WASM/WASI profile,
module/component ABI, toolchain, artifact digest, import manifest, and resume export.
The orchestrating host must select and instantiate those exact bytes; a nested WASI
guest is not assumed to have module-loader authority.

Neither mode snapshots linear memory, mutable globals, tables, `funcref`,
`externref`, host handles, WASI descriptors, sockets, clocks, random generators,
poll subscriptions, or borrowed component resources. They become explicit durable
data/reference or a capture barrier. Exact mode never rescues them.

## 5. Atomic admission

Before instantiating user artifact code or evaluating CoreIR, the runner atomically
validates:

1. framing, signature, audience, tenant, expiry, and capsule hash;
2. capsule/CoreIR/control ABI and canonical codec versions;
3. input/result/frame fingerprints and codec schemas;
4. closed globals, symbols, arities, node/value forms, and bounds;
5. primitive, effect, plugin, capability, resolver, and artifact manifests;
6. the approved implementation digest for every import;
7. authorization, concurrency, memory, fuel, wall-time, and output quotas.

No hidden dependency is discovered by running code. Failed admission executes no
suffix. A crash or transport loss after admission may yield `RunOutcomeUnknown`; the
runner never retries automatically.

## 6. Effects, imports, and durable references

`Eff = Pure | Op` remains ordinary portable data; raw library continuations are
reusable. A future admitted effect-capable WASM runner must preserve a typed
one-shot gate (`.ssc effect`); encoding never upgrades `OneShot` to `Reusable`.
The current v2 WASM CoreIR lane implements none of the `effect.*` primitive family,
so `effect.perform.oneshot` remains an unsupported primitive and must reject rather
than degrade to raw reusable behavior. Once qualified, an operation dispatches
only through its declared versioned capability implementation. WASI imports, WIT
functions, and host callbacks are adapters for explicit effects, not ambient handlers.

`DurableRef` decodes as inert nominal data with resolver ABI, provider, audience,
tenant, and policy. Decode/admission do not open a descriptor or invoke a resolver.
After admission, `Restore` calls the authorized resolver; deletion, expiry,
revocation, or unavailability is a typed operational failure.

A runner without filesystem, socket, time, randomness, environment, or component
resource capabilities rejects a capsule that requires them. It never obtains
undeclared ambient authority from a preopen or import.

## 7. Values and codecs

The baseline accepts only the common durable algebra: scalars, bytes, immutable
nominal data, compiler-converted state, prompts/handlers, and explicit references.
Cycles or aliasing require the graph codec. A decoded graph is fresh per run;
cross-run identity exists only through `DurableRef`.

| Canonical value | WASM core surface |
|---|---|
| `Unit` | empty result |
| `Boolean` | `i32` restricted to `0`/`1` |
| `I32` | `i32` |
| `I64` | `i64` |
| `BigInt` | canonical byte encoding through linear memory + pinned codec |
| `Double` | `f64`, with bit-preserving durable codec |
| `String` | canonical UTF-8 bytes through linear memory |
| `Bytes` | owned/copy-isolated linear-memory region |

**A ScalaScript `Int` crosses as `i64`.** ssc `Int` is 64-bit (`v2/specs/10-core-ir.md` §2,
`Int = Long`) and declares canonical `I64`; lowering it to a WASM `i32` parameter or result
truncates every value above 2^31−1 at the boundary. This binds the JS embedder too: an `i64`
at the JS boundary is a `BigInt`, never a `number`. `I32` is unreachable from ScalaScript
source and reserved for an explicit narrowing ABI. See
`specs/numeric-width-reconciliation.md`.

The CoreIR payload uses the single kernel-owned codec. The self-hosted outer
capsule/frame codec may frame/hash those bytes but cannot define another CoreIR
reader. `.scir`, `.sscc`, `irbin`, generated Rust, or raw WASM memory images are not
portable capsules.

## 8. Security and isolation

Every capsule is untrusted executable code. The runner:

- uses bounded streaming decode;
- validates before allocation proportional to attacker-declared sizes;
- enforces memory/table/stack/fuel/time/output/concurrency limits;
- restricts imports to the admitted manifest;
- isolates executions;
- never logs bearer references, secrets, or raw frame bytes.

A content digest proves identity, not authorization. Signer/origin trust, audience,
tenant, artifact, resolver, and capability policy are independent checks.

## 9. Cross-target semantics

A portable capsule from Scala/JVM, JS/TS, Rust, Swift, or another conforming
producer runs only when its versions and every semantic dependency have approved
WASM/WASI implementations. Source host identity changes no semantics.

Results and failures use target-neutral codecs. The runner does not stream
unresolved operations to the original host unless a separate distributed-handler
protocol is selected; that protocol is outside the base profile.

## 10. Conformance

A runner claim requires:

- one-shot/multi-shot effects, deep handlers, and prompt isolation;
- repeated/concurrent runs with fresh frames and prompt ids;
- 1,000,000-depth self and mutual CoreIR tail recursion in constant runner stack;
- fresh-process, cross-machine, and cross-producer portable execution;
- no-prefix/no-`_start`/no-initializer replay probes;
- declared effects and durable restoration through approved imports/resolvers;
- raw memory/global/table/reference/WASI-resource rejection in both modes;
- malformed/oversized code, missing dependency/resolver/artifact, signature,
  audience, tenant, expiry, revocation, quota, and post-admission crash rows;
- differential results against the portable-VM reference row.

AOT CoreIR→Rust→WASM output or successful module instantiation is insufficient.
Every row runs against the assembled admission boundary.

## 11. Architecture and sequencing

UniML owns syntax/CST only. The self-hosted outer compiler generates closed resume
code, durable frames, manifests, and the capsule. CoreIR and its canonical codec
remain kernel-owned. Evaluator, WIT/WASI adapters, resolvers, admission service, and
deployment host remain outside seed/compiler image.

This profile and its vectors may be designed now. Frontend/lowering,
canonical-codec/loader, seed-image, and other byte-affecting implementation waits
for full P6.5 X1. P6.6 `C_min` does not close X1. After X1, reconcile/version CoreIR
and rerun the literal fixed point after every later byte change.

## 12. Non-goals

- a general WASM host SDK or automatic JS/Rust/Swift interoperability;
- serializing linear memory, tables, references, descriptors, or component
  resources;
- using `_start`, application initialization, prefix, or replay;
- depending on host-stack tail calls;
- ambient undeclared WASI authority;
- automatic retry or exactly-once external effects;
- changing CoreIR or placing the runner in the bootstrap trusted base.
