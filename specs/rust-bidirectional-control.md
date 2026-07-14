# Rust ↔ ScalaScript bidirectional control profile

Status: **normative host profile / implementation planned** (2026-07-14).

This is the Rust host profile of
[`control-interoperability.md`](control-interoperability.md), the sole owner of
`Eff`, handlers, `shift`/`reset`, multiplicity, `save`/`run`,
`CodeMode × FrameGate`, durable state, admission, and common conformance. This
profile defines only their Rust realization.

Companions:

- [`rust-backend.md`](rust-backend.md) and [`rust-effects.md`](rust-effects.md) —
  existing generated Rust and effect paths;
- [`v2-rust-wasm-lanes.md`](v2-rust-wasm-lanes.md) — checked-CoreIR AOT evidence;
- [`polyglot-libraries.md`](polyglot-libraries.md) — ordinary Rust facade work;
- [`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md)
  — portable-VM reference row and readiness matrix.

Existing tagless-final, generated `Free`, and CoreIR→Rust paths are candidate
implementations. None alone constitutes a bidirectional host bridge.

---

## 1. Scope and qualification

This profile covers stable-Rust programs consuming and exporting ScalaScript
modules through a Cargo library/runtime. It requires:

- a typed bidirectional value bridge;
- a typed bidirectional call bridge preserving effects, callbacks, prompts,
  multiplicity, barriers, and failures;
- the explicit stable-Rust `Eff` API;
- descriptor-first generated facades;
- common host, tail, save/run, and cross-backend conformance.

A generated executable, a pure-function crate, a `cdylib` C ABI, or a one-reply
effect trait is partial interop. The explicit API, direct-style transform, exact
runner, and portable runner may ship separately. Nightly generators, unwind
behavior, LLVM tail-call accidents, and raw stack copying are never correctness
requirements.

## 2. Host crate and facade

A compiler-independent crate exposes typed effects and an iterative runner.
Generated crates consume `ApiDescriptor`, `ControlSummary`, and
`ArtifactManifest`:

- pure exports are ordinary typed functions;
- effectful exports produce `Eff<Fx, A>`;
- callback wrappers encode convention, multiplicity, escape, `Send`/`Sync`, and
  affinity;
- prompts and continuations are opaque and cannot be forged from integers/pointers;
- saved continuations expose only `save`/`run`;
- failures are typed values/effects, not panic strings.

`Result`, `Future`, iterators, channels, and panic-catching facades are terminal
adapters. They do not preserve an enclosing reset unless transformed and described
as managed.

## 3. Values, ownership, and borrowing

The bridge uses exact native types and explicit ownership:

| Canonical value | Rust surface |
|---|---|
| `Unit` | `()` |
| `Boolean` | `bool` |
| `I32` | `i32` |
| `I64` | `i64` |
| `BigInt` | approved arbitrary-precision wrapper with pinned ABI/codec |
| `Double` | `f64`, with bit-preserving durable codec |
| `String` | owned `String` at an escaping boundary |
| `Bytes` | owned `Vec<u8>` or approved boxed slice |
| option/sum/product | `Option` and generated enums/structs |
| sequence/map | `Vec` and deterministic canonical map adapter |
| callback | generated typed wrapper/trait |

Borrowing is an optimization only where values cannot escape. Managed capture and
durable state use owned canonical values.

References, trait objects, closures, vtables, raw pointers, FFI handles, and
`dyn Any` are not portable values. Serde traits, `Clone`, `'static`, `Send`, or
`Sync` are not automatic `DurableValue` evidence. The durable codec is nominal,
schema-pinned, bounded, and admitted explicitly. Interior mutability and aliases
require a graph/state policy.

## 4. Bidirectional calls

The descriptor-first flow is:

1. extract public types, effects, callback policy, capabilities, and stable ids;
2. generate Rust facade items and ScalaScript declarations;
3. compile/link managed bodies and control summaries;
4. bind monomorphized target entrypoints;
5. validate descriptors, codecs, targets, and capabilities before invocation.

Pure calls may be direct; effectful/control calls remain in `Eff`. Generic exports
have descriptor-visible parameters or explicit monomorphized entrypoints. Mangled
symbols are not semantic identity. Trait/dynamic dispatch is managed only when the
linker proves the concrete target set.

Panics are host failures, not operations. `catch_unwind` may adapt a declared
unwind-safe failure, but cannot implement handlers, shift, or resume. A panic must
not unwind across FFI.

## 5. Explicit `Eff` and direct style

The explicit stackless API is always available on stable Rust. Direct style may be
provided by procedural/attribute macros and generated state machines. A lexical
macro manages only its input syntax; cross-function capture requires every
participant transformed and every managed edge in `ControlSummary`.

Native mechanisms remain adapters:

- `Future`/`async` is a one-shot progress computation;
- `FnOnce` is one-shot and `FnMut` may carry mutable state;
- generators/coroutines are not cloneable reusable continuations;
- panic/unwind, setjmp-like mechanisms, and stack inspection are forbidden control
  encodings.

Boxing, pinning, or cloning a wrapper never upgrades multiplicity. Every saved run
creates fresh state and prompt identities.

## 6. Callbacks and concurrency

Each callback records convention, multiplicity, escape, reentrancy, concurrency,
cancellation, thread affinity, and `Send`/`Sync` requirements:

- no-escape borrowed callbacks cannot be retained by ScalaScript;
- escaping callbacks own bridge values and satisfy lifetime/thread policy;
- `FnOnce`/`AtMostOnce` is consumed once;
- managed `Fn`/`FnMut` wrappers re-enter the explicit protocol.

C/C++ callbacks, unknown trait objects, undescribed function pointers, foreign
tasks/channels, and callbacks hidden behind dynamic dispatch are barriers. They
become managed only through generated wrappers with complete metadata.

## 7. Capture and frame gate

A frame is savable only when every live slot is an approved durable value/reference.
The following are unsavable by default:

- non-`'static` borrows, slices/views, self-referential or pinned borrows, pointers;
- arbitrary closures/trait objects, FFI handles, native frames, unsafe projections;
- `Rc<RefCell<_>>`, `Arc<Mutex<_>>`, cells, atomics, and observable mutable aliases
  without an explicit policy;
- live lock guards, files, sockets, processes, threads, channels, timers,
  futures/tasks, reactor tokens, thread locals, and OS resources;
- active `Drop`/RAII cleanup, unwind, transaction, lock, or foreign callback scopes.

An owned struct/enum is not automatically durable; every field needs approved
nominal evidence. Exact artifact mode never rescues a borrow, pointer, active
destructor, closure, or resource.

`DurableRef` stores inert schema/resolver/provider/capability identity. Admission
verifies the resolver implementation and authorization; actual lookup is a typed
restore effect and may fail operationally.

## 8. Mixed Rust proper tail calls

After monomorphization/entrypoint selection, each statically known managed SCC
lowers to a shared iterative typed `TailStep` enum/dispatcher. Rust and ScalaScript
calls may alternate arbitrarily within it.

Trait-object dispatch, unknown generics, function pointers, FFI, async/task
boundaries, destructors, panic paths, foreign callbacks, and untransformed functions
are loud tail barriers. Qualification proves alternating two- and three-function
cycles at depth at least 1,000,000 without native stack growth.

## 9. `save` and `run`

### 9.1 Portable mode

A hardened versioned Rust CoreIR runner executes the closed resume program and
durable frame without the original application binary/crate. It admits code and
dependencies as data; it does not compile application source during `run`.

### 9.2 Exact artifact mode

The exact identity includes:

```text
RustTargetProfile(
  targetTriple,
  cpuAndAbi,
  rustcAndToolchain,
  cargoFeaturesAndLock,
  runtimeControlAbi,
  artifactDigest,
  resumePointId,
  initFreeResumeSymbol
)
```

The entry may live in a dedicated executable/library/bundle, but dispatch goes
directly to compiler-generated state. Runner infrastructure may initialize;
application `main`, effectful static initialization, prefix code, and hidden
dependency discovery may not run. If linked code cannot isolate the resume entry,
the continuation is not exact-artifact savable.

No stack, closure environment, vtable, borrow, pointer, or process address is
serialized. Only generated state ids and codec-approved slots cross the boundary.

Admission verifies signatures, schemas, target/toolchain/artifact, exact dependency
and resolver implementations, authorization, and quotas before user code. One
accepted run invokes one fresh execution once. Crash/disconnect after admission is
`RunOutcomeUnknown`; there is no automatic retry.

## 10. Packaging

The SDK ships as a Cargo library with generated public modules and golden
API-signature tests. The manifest pins control ABI, targets, features, dependencies,
resolvers, and artifact digests. A C facade may exist, but raw C values/callbacks
remain foreign barriers unless separately described.

Portable/exact runners may be dedicated binaries. Different target triples are
different exact artifacts. `Cargo.lock`, features, build scripts, proc-macro
versions, and codegen flags that affect bytes are artifact identity or reproducible
build evidence. Build scripts and initialization never become hidden capsule
dependencies.

## 11. Conformance

In addition to every common vector, this profile proves:

1. exact-width owned values, generated data, effects, and failures both ways;
2. Rust reset → ScalaScript shift and the reverse;
3. capture on either side and local resume on the other;
4. Rust→ScalaScript→Rust→ScalaScript callback ping-pong;
5. explicit API and macro/state-machine equivalence;
6. `FnOnce`, Future/async, streams, borrows, and foreign callbacks retain declared
   multiplicity/barriers;
7. `Send`/`Sync`, escape, reentrancy, and affinity enforcement;
8. mixed tail SCCs at depth 1,000,000;
9. repeated/concurrent portable runs in-process, fresh process, and remote runner;
10. exact runner restart without `main`, initializer, or prefix replay;
11. borrow, pointer, closure, trait object, lock guard, Drop scope, future, alias
    graph, and OS-resource rejection even in exact mode;
12. wrong target/rustc/features/ABI/codec/artifact, missing dependency/resolver,
    tampering, quota, lifecycle, and outcome-phase failures;
13. portable capsules accepted from and by every other qualified runner.

Compile-time barriers diagnose at the capture site when provable. Transitive/dynamic
barriers use the canonical runtime save check. Neither may partially save.

## 12. Delivery and architecture gate

The Rust milestone is mandatory and independently shippable:

1. freeze value/ownership mappings, descriptors, generated facades, and ids;
2. implement explicit stable-Rust `Eff`;
3. add managed procedural/state transforms and callback policy;
4. add mixed SCC dispatch;
5. add target-pinned init-free exact save/run;
6. harden the dynamic portable runner;
7. close N→M and negative rows.

UniML is syntax-only. The self-hosted outer owns lowering, save transformation,
durable codecs, dependency closure, and policy verification. CoreIR and its one
codec remain kernel-owned. Rust SDK/build/runner code stays outside bootstrap.

Profile/design/vector work may proceed now. Frontend/lowering, canonical-codec/
loader, seed-image, and byte-affecting implementation waits for full P6.5 X1. The
landed P6.6 `C_min` proof is not X1; later byte changes rerun the fixed point.

## 13. Profile-specific non-goals

- reverse engineering stacks, closures, vtables, borrows, pointers, or RAII state;
- treating panic, Future/async, generators, channels, or trait callbacks as the
  semantic control ABI;
- inferring durability from `Clone`, serde, `'static`, `Send`, or `Sync`;
- using application `main`, initialization, prefix replay, or auto-retry;
- relying on nightly-only behavior for the canonical API.
