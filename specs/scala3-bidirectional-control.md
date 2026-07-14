# Scala 3 ↔ ScalaScript bidirectional control

Status: **design freeze / implementation planned** (2026-07-14).

This specification defines one control model shared by portable ScalaScript and
ordinary Scala 3 programs. It covers typed algebraic effects, deep handlers,
multi-prompt `shift`/`reset`, callbacks, mixed-language proper tail calls, and
portable saved continuations. It is intentionally a semantic and ABI contract;
the implementation slices are tracked in `SPRINT.md`.

Companion specifications:

- [`algebraic-effects.md`](algebraic-effects.md) — effect rows and handler surface;
- [`coroutines.md`](coroutines.md) — asymmetric one-shot coroutine primitive;
- [`scala-interop.md`](scala-interop.md) — the landed pure JVM interop tiers;
- [`polyglot-libraries.md`](polyglot-libraries.md) — the one-reply plugin SPI;
- [`v2.2-self-hosted-dialect.md`](v2.2-self-hosted-dialect.md) — self-hosting and
  UniML integration;
- [`uniml-portable-gapmap.md`](uniml-portable-gapmap.md) and
  [`corpus-contract.md`](corpus-contract.md) — migration order and differential
  gates;
- [`../v2/specs/10-core-ir.md`](../v2/specs/10-core-ir.md) — the portable inner
  kernel;
- [`../v2/specs/50-effects.md`](../v2/specs/50-effects.md) — the v2 `Pure | Op`
  semantic substrate;
- [`separate-compilation-plan.md`](separate-compilation-plan.md) and
  [`v2.0-artifact-format.md`](v2.0-artifact-format.md) — interfaces and artifacts.

This document supersedes only the old `shift`/`reset` non-goal in
`algebraic-effects.md`. `callCC` remains outside the language contract.

---

## 1. Outcome and scope

Scala 3 and ScalaScript are two source frontends for the same managed control
protocol. A program may cross the language boundary in either direction while
retaining:

- statically described value types and effect rows;
- deep, compositional handler semantics;
- typed, generative prompts and reusable delimited continuations;
- direct-style callbacks when every active frame is compiler-managed;
- proper tail recursion across statically known mixed-language call cycles;
- a portable `save`/`run` boundary for reusable continuations;
- deterministic diagnostics at every unsupported foreign boundary.

“Transparent” does not mean that a JVM stack, a socket, or an arbitrary object
becomes portable by magic. It means that all supported behavior has one typed
contract and one observable semantics, whether the source spelling is ScalaScript,
the explicit Scala API, Scala macros, or compiler-plugin-transformed Scala.
Unsupported capture is rejected rather than silently changing behavior.

The first mixed-language implementation target is the managed JVM lane. The
ScalaScript semantics and its CoreIR lowering remain target-independent and must
continue to agree on VM, JVM, JavaScript, WASM, Rust, and Swift lanes as those
lanes expose the relevant capability.

## 2. Binding architectural invariants

### 2.1 One semantic owner

The semantic owner is this specification plus the portable lowering laws and
differential conformance vectors. Neither the Scala SDK, the Scala compiler plugin,
the JVM runtime, nor the current interpreter implementation is an oracle by itself.

The compiler-independent explicit API is the executable reference model. Direct
syntax, macros, compiler-plugin transforms, generated JVM code, and backend fast
paths are checked implementations of that model.

### 2.2 CoreIR remains the portable inner kernel

No `Effect`, `Handler`, `Prompt`, `Continuation`, `Shift`, `Reset`, `Save`, or
`Resume` node is added to CoreIR. Effects and control lower to ordinary current
CoreIR constructs: `Ctor` (which evaluates to runtime `Data`), `Lam`, `App`, `Let`,
`LetRec`, branching, and primitives.

The semantic computation protocol is:

```text
Comp[Fx, A] = Pure(A) | Op(operation, continuation)
```

This is an outer-language protocol encoded with ordinary constructor data and
lambdas; it is not a new CoreIR value shape or term node. A lowering may construct
and fold it directly or call the existing generic
`Prim("effect.perform"/"effect.handle", ...)` operations that implement the same
protocol. Backend optimizations may use additional private runtime representations,
but they must be observationally equivalent to these laws.

The current CoreIR specification and implementation disagree about the canonical
`While` and `Seq` inventory, and the current canonical codec has known contract
violations. These are recorded in `BUGS.md` and are explicit prerequisites to
freezing a persisted continuation capsule. This design says “the current reconciled
canonical CoreIR”, not “the historical eleven-node list”. It grants no permission to
add a continuation node.

### 2.3 UniML remains a lossless syntax layer

UniML owns tokens, lossless CST shape, spans, containment, injection, and recovery.
It does not own control semantics. A malformed `shift` or `reset` becomes a CST
`Error`, a typed hole, and a deterministic diagnostic; it must not crash the parser
or use a host exception as language behavior.

The ScalaScript projection recognizes compiler-known control regions before strict
call-by-value evaluation. In particular, `reset { body }` is not lowered as an
ordinary eager library call whose body has already run.

### 2.4 Self-hosting remains literal

The Scala 3 API, macros, compiler plugin, and managed JVM runtime are not
dependencies of the seed, UniML, the self-hosted compiler image, or the literal
fixed-point comparison. Compiler implementation source stays inside the measured
Scala-3∩v2 bootstrap subset and does not use direct-style control to implement the
bootstrap compiler.

Specification work, the explicit reference API, descriptors, and semantic vectors
may proceed immediately. Changes to the v2 frontend or lowering wait until the
active P6.5 literal fixed-point sequence `F1 → F2/F3 → L1 → X1` is green and frozen.
Canonical CoreIR codec/loader or other byte-affecting kernel changes wait as well.
This milestone must not move the self-hosting target while it is being measured;
every post-X1 compiler/kernel change re-runs the literal stage1==stage2 check.

### 2.5 Platform isolation remains binding

Portable `.ssc` code cannot import `java.*`, `scala.*`, arbitrary JVM classes, or
backend globals. Platform operations continue to use `std.*`, a plugin intrinsic,
an explicit `@jvm`/`@js` adapter, or a backend-specific fenced block. This interop
work does not create a suppression mechanism or leak Scala/JVM types into CoreIR.

Any new user-visible `extern def` used by save/run belongs in a `runtime/std/`
plugin. Runner coordination hooks belong in the backend/runner SPI. Intrinsic
implementations must not be added to interpreter core; the kernel-owned canonical
CoreIR codec/loader is a separate existing contract.

---

## 3. Reference computation model

The reference model is stackless and reusable:

```scala
sealed trait Eff[+Fx, +A]

object Eff:
  final case class Pure[A](value: A) extends Eff[Nothing, A]
  final case class Op[Fx, X, A](
    operation: Operation[Fx, X],
    continue: X => Eff[Fx, A]
  ) extends Eff[Fx, A]
```

The notation is explanatory; the public Scala API may use an equivalent encoding
that avoids allocation and preserves Scala variance. The following laws are
binding:

1. `flatMap` is stack-safe and left-associated source code does not grow the host
   stack.
2. `Op` stores a reusable semantic continuation unless the operation's declared
   multiplicity is one-shot.
3. Deep handlers reinstall themselves around the continuation.
4. An unhandled operation is forwarded with its continuation intact.
5. Backend-private coroutine, generator, Loom, or bytecode paths are optimizations,
   never alternate semantics.

The public ABI must not expose interpreter `Value`, `DataV`, `ClosV`, VM frames,
backend `SpiValue`, reflective invocation, or class-name-matched exceptions.

## 4. Effects and handlers

### 4.1 Typed operations

Every operation has a stable nominal identity, argument/result types, multiplicity,
and an effect-row member. Effect rows are preserved in exported API metadata and
are not reconstructed from generated method names.

```text
operation : Args -> Result ! EffectKey
```

A handler removes only the operation family it handles; all residual effects are
forwarded. Handler return clauses apply to the handled computation's terminal
result, not independently to every operation-clause body.

### 4.2 Multiplicity

- A reusable or multi-shot operation may resume zero, one, or many times.
- A one-shot operation may resume at most once.
- Persistence never strengthens multiplicity. A one-shot coroutine suspension
  cannot be converted into a reusable `SavedContinuation`.

One-shot effects and asymmetric coroutines may use a lower-allocation fast path.
When a reusable continuation is required, the `Pure | Op` model is authoritative.

### 4.3 Plugin boundary

The existing plugin `EffectHandler.reply(op, args): SpiValue` contract remains a
one-reply host SPI. It does not receive, inspect, save, or resume a continuation.
Multi-shot control uses the managed control ABI defined here, not an accidental
extension of `SpiValue`.

---

## 5. Typed multi-prompt `shift`/`reset`

### 5.1 Prompt identity

A prompt has two identities:

- a fresh generative type key `P` in the outer type system;
- a runtime `PromptId` after type erasure.

Conceptually:

```scala
Prompt[P, R]
Control[P]
Continuation[A, Fx, R]
```

`P` prevents `Shift`/`Control` for one prompt from being discharged by a different
reset. The captured `Continuation` has already sealed its matching prompt and takes
no prompt argument when resumed. `R` is the answer type of the reset region.
`PromptId` is an explicit token in the managed protocol. A JVM runner may use
private object identity to implement token equality within one execution, but raw
host identity is never part of the ABI, descriptor, or saved representation. Every
saved `run` alpha-renames captured prompt ids freshly while preserving all
equality/inequality relations inside that run; concurrent runs therefore cannot
interfere through prompt identity. Ambient thread-local state is not the semantics.

Only prompt binders whose matching reset/handler is closed inside the saved region
are alpha-renamed. A free external prompt in a residual row cannot be portably
matched by raw identity on another machine; base `save` rejects it unless it is
modeled by an explicit durable prompt capability. Prompt values are not general
remote input/result codec values in the base profile.

The API may expose freshness through a path-dependent key, a scoped constructor,
or compiler-generated evidence. The descriptor records the prompt/control
relationship structurally, not as an unstable source-local string.

With the sketched path-dependent API, `val scope = freshPrompt[R]` gives
`scope.prompt: Prompt[scope.Key, R]`; the fresh `scope.Key` cannot be named before
the scope exists or confused with another scope's key.

### 5.2 Exact reset laws

For a prompt `p`, `reset_p` is a fold over `Pure | Op`:

```text
reset_p(Pure(v)) =
  Pure(v)

reset_p(Op(Shift(p, f), k)) =
  reset_p(f(Continuation(a => reset_p(k(a)))))

reset_p(Op(other, k)) =
  Op(other, a => reset_p(k(a)))
```

The last law includes an operation for a different prompt. A `reset` discharges
only its own generative `Control[P]`; it cannot erase all nominal `Control` effects.

The outer `reset_p` around `f(...)` is required. Omitting it gives a `shift0`-like
operator rather than the `shift` specified here. The captured continuation also
reinstalls the same reset around `k(a)`, which gives deep, compositional behavior.

### 5.3 Observable behavior

- The nearest dynamically enclosing reset with the same prompt captures the
  continuation.
- A handler may discard it, invoke it once, or invoke it repeatedly.
- Nested fresh prompts do not interfere.
- Residual effects keep their original order and handler context.
- Local multi-shot resumption copies control context, not the entire process heap.
  Ordinary mutable heap objects and explicit foreign references are shared; there
  is no implicit transaction or rollback.
- Therefore effects in the continuation suffix occur zero, one, or many times,
  according to the number of explicit resumes.

Pure state branching is expressed through an algebraic `State` handler. It is not
obtained by silently snapshotting every mutable object during ordinary local
resume.

### 5.4 `callCC`

Undelimited `callCC` is not part of this milestone. The ABI does not promise an
unbounded whole-program continuation, dynamic-wind semantics, or arbitrary escape
from foreign frames. This exclusion does not restrict typed delimited
`shift`/`reset`.

The base type system is answer-type-preserving: a reset and every continuation it
captures retain the same answer type `R`. Answer-type modification is deferred and
requires a separate type-system/ABI design.

---

## 6. Scala and ScalaScript surface

### 6.1 Compiler-independent explicit API

The first implementation is a small `_3` library that can be used without a macro
or compiler plugin. Its exact package and type-parameter order are frozen by the
`scala3-control-api` slice, but it must express these operations without `Any`:

```scala
trait Effect
trait Operation[+Fx, A]
trait Handler[Handled, Residual, A, B]

def perform[Fx, A](operation: Operation[Fx, A]): Eff[Fx, A]
def handle[Handled, Residual, A, B](
  body: Eff[Handled | Residual, A]
)(handler: Handler[Handled, Residual, A, B]): Eff[Residual, B]

trait Continuation[A, Fx, R]:
  def resume(value: A): Eff[Fx, R]
  def save(): Eff[Save, SavedContinuation.Aux[A, Fx, R]]

trait SavedContinuation[A, R]:
  type Effects
  def run(value: A): Eff[Effects | Restore, R]

object SavedContinuation:
  type Aux[A, Fx, R] = SavedContinuation[A, R] { type Effects = Fx }

trait ScopedPrompt[R]:
  type Key
  val prompt: Prompt[Key, R]

def freshPrompt[R]: ScopedPrompt[R]
def reset[P, Fx, R](prompt: Prompt[P, R])(
  body: => Eff[Fx | Control[P], R]
): Eff[Fx, R]

def shift[P, A, Fx, R](prompt: Prompt[P, R])(
  body: Continuation[A, Fx, R] => Eff[Fx | Control[P], R]
): Eff[Fx | Control[P], A]
```

This is a semantic sketch, not permission to expose a union-based effect-row
encoding if the final row representation differs. `save` and `run` are the fixed
user idiom. Runtime persistence records, frame schemas, and capsule internals are
not public concepts. The compiler supplies a hidden, generated `SavePlan` describing
the captured frame; it is not a user-visible type parameter or argument, so the
surface call remains exactly `continuation.save()`.

An arbitrary Scala closure or explicit-library continuation is local-only. `save`
succeeds only when the continuation carries a compiler-generated plan or was built
through a public typed defunctionalized/state-machine builder. Users never manipulate
the raw plan or frame records. The runtime never invents universal evidence from
`A`, `Fx`, and `R`; absence of a plan is typed `UnmanagedCapture`, not reflection or
reverse engineering.

`P` appears on `Prompt`, `Control`, `shift`, and `reset`, where it prevents a wrong
capture/discharge. It is intentionally absent from the resulting `Continuation`:
that value has already sealed the matching reset and `resume` takes no prompt.
Internal capsule metadata still preserves and alpha-renames its prompt relations.

### 6.2 ScalaScript direct syntax

ScalaScript adds compiler-known typed forms equivalent to the explicit API:

```scalascript
reset(prompt) {
  val x = shift(prompt) { continuation =>
    continuation.resume(10).flatMap { first =>
      continuation.resume(20).map(second => first + second)
    }
  }
  Pure(x + 1)
}
```

Exact surface punctuation is finalized with parser vectors. The semantics are the
laws in §5, not an ordinary eager function call. Explicit `Eff` values remain
first-class and may be passed through functions; the compiler-known region is only
the direct-syntax capture boundary.

### 6.3 Scala macros and compiler plugin

The Scala integration has three cumulative tiers:

1. **Explicit API:** portable local effect/control reference semantics, no compiler
   integration; persistence is available only through its typed defunctionalized
   state-machine builder.
2. **Inline macros:** local direct-style reset regions lowered to the explicit API,
   with exact source positions.
3. **Compiler plugin:** cross-method CPS/state-machine transformation, managed
   callback propagation, saveable-frame metadata, and mixed tail-call metadata.

A macro or plugin cannot transform already compiled bytecode. A call through
precompiled Scala/Java, reflection, an unknown virtual target, JNI, or an
uninstrumented callback is allowed as an ordinary call but is a control-capture and
mixed-tail-call barrier while its frame is active.

### 6.4 Managed capture region

Direct capture is valid only when all frames between `shift` and the matching reset
are managed by the explicit stackless protocol or the compiler transformation.
Active `try/finally`, a monitor, lock, transaction, resource scope, socket callback,
thread-affine API, or native frame is a barrier unless that construct has an
explicitly specified transform and restore protocol.

The compiler reports the first barrier with source and callee information. It does
not fall back to exceptions, JVM stack inspection, or partial capture.

---

## 7. Bidirectional module and callback interop

### 7.1 ScalaScript exports to Scala

The landed natural-FQN/JAR model remains intact:

- pure exports use host-native Scala types and direct delegation;
- effectful exports return the public typed `Eff` ABI;
- control-aware callbacks carry their calling convention and effect row;
- terminal adapters to `Future`, `Either`, or throwing APIs are explicit runners,
  not the correctness path.

The old thunk plus catch-by-class-name bridge and actor `AnyRef` stubs are not valid
implementations of managed effects/control. They may remain compatibility adapters
until callers migrate.

### 7.2 Scala exports to ScalaScript

Only explicitly selected Scala definitions are exported. The exporter produces:

- a structured interface descriptor;
- generated `.ssc` declarations;
- JVM glue that targets the managed ABI;
- capability and backend restrictions.

Portable Scala signatures import naturally. A Scala/JVM-only type needs an explicit
adapter/codec or a platform-specific boundary and never weakens the ordinary `.ssc`
platform-type prohibition.

### 7.3 Callback calling conventions

Every exported callback is classified as one of:

1. pure, invoked directly with no managed effect/control protocol;
2. effectful, returning `Eff` and preserving its effect row;
3. managed-control, allowed to capture/resume within a declared region;
4. foreign, callable but a deterministic capture barrier.

The classification is part of the descriptor and linker checks. It is not inferred
from a JVM functional-interface class after linking.

### 7.4 Build graph

The initial mixed build is interface-first over a module DAG:

1. extract Scala and ScalaScript interfaces;
2. generate typed facades/stubs;
3. compile bodies;
4. link one managed runtime scope;
5. validate descriptor hashes and control ABI.

Same-module Scala↔ScalaScript source cycles require a later two-phase interface
graph. They are not silently supported by repeated speculative compilation.

### 7.5 Complete Scala exposure

The Scala SDK is generated from the same module/API/capability metadata as the
ScalaScript standard library; it is not a second hand-maintained implementation.
Every user-visible ScalaScript capability must declare exactly one Scala exposure
category:

- native typed API;
- generated facade/export;
- inline macro;
- compiler-plugin transform;
- tooling-only operation;
- explicitly platform-specific adapter;
- intentionally unavailable only for source-document/tooling constructs or a
  target-inapplicable platform feature, with a normative reason and diagnostic.

Every portable user-facing runtime/library capability must have a Scala exposure;
the unavailable category cannot waive that goal. CI rejects an unclassified
capability. “Full transparency” is complete only when this matrix covers the whole
shipped feature and standard-module inventory, not only the effects examples in this
specification.

---

## 8. Structured API descriptor

The current string-oriented `.scim` fields remain readable and retain their meaning.
Managed interop adds three separately versioned canonical records.

`ApiDescriptor` is available before bodies compile and contains only public
interface identity:

```text
schemaVersion
controlAbiVersion
moduleId
apiHash
symbols[
  stableSymbolId
  qualifiedName
  kind
  typeParameters / variance / bounds
  parameterLists / names / defaults / using
  structuredResultType
  effectRow
  operationResumeMultiplicity
  callbackParameters[
    callingConvention          // PureDirect | Effectful | ManagedControl | ForeignBarrier
    invocationMultiplicity     // AtMostOnce | Many | Unknown
    escape                     // NoEscape | MayEscape
    reentrancy / concurrency / threadAffinity
  ]
  promptAndControlMetadata
  requiredCapabilities / targets
  extension / given metadata
]
```

`ControlSummary` is emitted after compiling bodies and is keyed by `apiHash` plus
stable symbol id. It contains inferred managed/foreign call edges, direct tail edges,
save sites, live-frame schemas, capture barriers, and save-safety results. These
body-derived facts cannot participate in the interface hash.

`ArtifactManifest` is emitted after linking and binds that interface and its control
summaries to executable code:

```text
artifactManifestVersion
apiHash
target
targetEntrypoints[
  stableSymbolId
  jvmOwner / jvmName / jvmDescriptor       // JVM binding
  targetEquivalent                         // other targets
]
programDigest?
artifactDigest?
runtimeVersion
dependencyManifest
dependencyProfileDigest
controlSummaryDigests
```

`qualifiedName` alone is insufficient for overloads and generated bridges. A stable
symbol id derives from module id + qualified name + canonical ABI signature; any
collision suffix derives from that signature's hash, never source/list order,
position, or JVM name alone.

Descriptors, summaries, and manifests are deterministic, content-addressable, and
round-trip without loading user classes. A body-only change leaves `apiHash`
unchanged while changing only the relevant control/program/artifact digests. Linkers
reject a schema, API, implementation, or control-ABI mismatch before class loading.

`managed-control` is valid only when the callee invokes that callback through the
managed protocol. Passing it through precompiled collection code, Java code, an
unknown executor, or any uninstrumented invocation site is a capture barrier. A
callback that may escape its reset scope must be rejected or explicitly saved.

The existing plugin SPI exposes only plugin identity/installation and cannot yet
prove a version/hash capability profile. The descriptor implementation slice must
add that versioned profile before saved code may depend on a plugin manifest.

### 8.1 Canonical identities

The initial digest algorithm is SHA-256. Every digest has a domain separator,
canonical encoding, and explicit self-field exclusions:

```text
apiHash =
  SHA-256("ssc-api-v1\0" ||
    canonical(ApiDescriptor without apiHash))

programDigest =
  SHA-256("ssc-coreir-program-vN\0" ||
    canonical(static fully linked application CoreIR))

resumeCodeDigest =
  SHA-256("ssc-coreir-resume-vN\0" ||
    canonical(state-abstracted resume CoreIR program))

dependencyProfileDigest =
  SHA-256("ssc-dependencies-vN\0" ||
    canonical(primitive/plugin ABI and capability manifest))

frameDigest =
  SHA-256("ssc-frame-vN\0" ||
    canonical(frame schema id and frozen frame payload))

capsuleHash =
  SHA-256("ssc-capsule-vN\0" ||
    canonical(capsule without capsuleHash and signature))

signatureInput =
  "ssc-capsule-signature-vN\0" || capsuleHash
```

`artifactDigest` hashes reproducible target artifact bytes while excluding the
manifest fields that contain `artifactDigest` or its signature; alternatively it is
stored as detached metadata. The artifact format must select exactly one rule.

`programDigest` identifies the linked application CoreIR. `resumeCodeDigest`
identifies the state-abstracted resume program and excludes the captured frame.
`dependencyProfileDigest` separately binds the primitive/plugin ABI and capability
profile so portable semantic code identity is not confused with one native plugin
implementation. `artifactDigest` is the exact identity of a materialized JVM
JAR/runtime bundle. `capsuleHash` commits to the applicable code identity,
dependency profile, captured frame, and security-relevant envelope. They are not
interchangeable. Existing source hashes and `.sscc`
digests are insufficient for saved frames because equal source can link against
different runtime/plugin code and different source can normalize to equal CoreIR.

### 8.2 Numeric widths

The descriptor has canonical `I32` and `I64` types; it never guesses width from the
source spelling `Int` or from the JVM carrier type.

- Language-level ScalaScript `Int` and `Long` follow the canonical language spec:
  `I32` and `I64` respectively.
- Scala `Int` and `Long` map to `I32` and `I64` respectively.
- The current v2 CoreIR `CInt(Long)` is a signed wrapping-64 kernel value. Lowering
  must preserve `I32` range/wrap semantics explicitly; that kernel value does not define
  the public API width.
- The v2.2 bootstrap subset's implementation spelling `Int` is not an exported ABI
  promise. The frontend must retain source-width evidence before any `Int`/`Long`
  export is allowed. Until it proves a canonical width, an ambiguous legacy export
  is a deterministic error, never a silent narrowing or overload collision.

---

## 9. Mixed-language proper tail calls

Proper tail recursion across Scala and ScalaScript is provided only for a statically
known, instrumented strongly connected component (SCC). The compiler/linker builds a
typed mixed call graph and rewrites tail edges through a dedicated iterative
`TailStep`/dispatcher ABI. `TailStep` is internal/generated and never exposes `Any`
in a public signature.

This ABI is separate from `Eff` / `Pure | Op`:

- pure tail SCCs use the minimal tail dispatcher;
- effectful SCCs use the stackless effect/control trampoline;
- neither protocol is wrapped in the other merely for convenience.

Eligibility requires every SCC member and every direct resolved tail edge to be
instrumented. Unknown virtual, indirect/higher-order, reflective, precompiled
foreign, resource/finalizer, and uninstrumented callback edges are tail-guarantee
boundaries. Initially unsupported curried/default/polymorphic SCC shapes are rejected
deterministically rather than partially transformed. Non-tail calls retain normal
stack behavior. Conformance includes two- and three-function Scala↔ScalaScript cycles
at depth at least 1,000,000 and verifies no unbounded managed or JVM stack growth.

---

## 10. Saved continuations: semantic contract

### 10.1 Public behavior

`save` freezes a reusable delimited continuation at its capture point. In the
ordinary direct-effect notation, both persistence and restore failures remain in
the typed row:

```scala
direct[Save | Restore | Fx] {
  saved = continuation.save()
  first = saved.run(a1)
  second = saved.run(a2)
  (first, second)
}
```

The block above uses the planned direct-effect syntax. The compiler-independent
explicit API expresses the same program without special syntax:

```scala
continuation.save().flatMap { saved =>
  saved.run(a1).flatMap { first =>
    saved.run(a2).map { second => (first, second) }
  }
}
```

Binding rules:

1. A `SavedContinuation` has immutable identity/payload and is copyable; an
   explicitly provider-backed lifecycle may still expire or revoke that identity.
2. `save` does not consume or invalidate a reusable source continuation.
3. `run` may be invoked zero or more times, sequentially or concurrently.
4. Each invocation begins directly at the capture point with its supplied value.
5. The computation before capture is never executed again.
6. The resume entry is invoked once per accepted `run`; the suffix then performs
   effects according to its own code, including explicit loops or nested multi-shot
   handlers.
7. The runtime never automatically retries a failed or disconnected run.
8. A crash belongs to that execution; occurrence/result may be unknown, while the
   saved continuation remains reusable.

Repeated suffix execution is multi-shot resumption, not replay infrastructure.
“No replay” means no reconstruction by rerunning the prefix, top-level initializers,
or an event log.

Cancellation is an ordinary typed effect observed at managed suspension/bind points;
it does not imply arbitrary JVM thread interruption. Cancelling one resume/run affects
only that execution and never consumes or revokes the reusable saved value. Active
untransformed finalizer/resource frames remain capture barriers.

### 10.2 Snapshot boundary

Ordinary local multi-shot resume shares the current heap (§5.3). `save` is an
explicit portability boundary and therefore freezes its supported captured value
graph:

- a mutation of the original value after `save` does not change the saved snapshot;
- each `run` reconstructs an independent local captured graph;
- local mutation in one run is not visible to another run;
- an explicit durable reference/capability may intentionally name shared external
  state, and is re-authorized on every run.

An alias-preserving graph codec preserves identity relations only within one
reconstructed run; separate runs receive separate graphs and freshly alpha-renamed
captured prompt tokens. Explicit durable references are the only shared identity.

The baseline directly supports primitive values, immutable acyclic constructor data,
compiler-generated prompts/handler state, and compiler-converted closures. Mutable or
cyclic graphs require an explicit alias-preserving durable codec; arbitrary JVM
objects are never inferred to be durable.

### 10.3 Multiplicity preservation

Only a reusable semantic continuation can produce the reusable type above. A
one-shot handler resume or asymmetric coroutine suspension keeps a separately typed
one-shot contract. Serializing its frame must not make it multi-shot.

An atomic one-shot workflow policy may be added independently, with a linearizable
claim and terminal unknown state after a claimed crash. It is not the meaning of
`SavedContinuation.run` and is tracked as optional follow-up work.

---

## 11. Portable CoreIR capsule

### 11.1 Normative representation

For a fully portable managed continuation, the saved payload separates static resume
code from one frozen frame. The code is a closed ordinary CoreIR `Program` whose
entry evaluates to an arity-two `(frame, input) => Eff` closure:

```text
ResumeProgram(
  defs = [
    __saved_resume_stateN,
    <closure-converted reachable definitions>,
    <portable handler and codec definitions>
  ],
  entry =
    Lam(2,
      App(
        Global(__saved_resume_stateN),
        [Local(1), Local(0)]       // frame, input
      )
    )
)

FrozenFrame(
  schema = frameSchemaHash,
  data = <quoted captured values and durable handler/prompt state>
)
```

`run(input)` loads the entry and applies it at exact arity to `(freshlyDecodedFrame,
input)`. Fixing the immutable frame gives the semantic function
`A => Eff[Fx, R]` requested by the user model. `Pure`, `Op`, frame data, prompt
state, and durable handler state use ordinary constructor data and lambdas. There
is no saved-continuation CoreIR node.

The CoreIR payload is untyped because CoreIR is untyped. Its envelope supplies:

```text
capsuleFormatVersion
coreIrVersion
controlAbiVersion
runtimeVersion
targetProfile
inputTypeFingerprint
resultTypeFingerprint
effectRowFingerprint
inputCodec(semanticAbiId, schemaHash)
resultCodec(semanticAbiId, schemaHash)
frameSchemaHash
frameDigest
framePayload = Inline(FrozenFrame) | ContentAddressed(frameDigest)
dependencyProfileDigest
capsuleHash
requiredPrimitives(name, abi)
requiredPlugins(id, semanticAbiId, semanticAbiVersion)
requiredCapabilities
payload =
  Portable(resumeCodeDigest, closedResumeProgram)
  | ExactArtifact(artifactDigest, target, resumePointId)
lifecycle = Inline(notAfter?) | ProviderIdentity(id, expiryPolicy)
limits / audience / tenant / signature
```

`resumeCodeDigest` identifies only the state-abstracted portable resume program;
`frameDigest` identifies the captured state; `capsuleHash` commits to the tagged
payload, both digests, dependency profile, codecs, and security-relevant envelope.
The two payload variants are mutually exclusive and explicitly tagged.

### 11.2 Generation, not reverse engineering

The capsule is generated at a compiler-declared saveable region:

```text
CPS/state split → liveness → closure conversion/defunctionalization
→ quote captured values → close reachable globals → emit resume entry
```

It cannot be reconstructed after the fact from an arbitrary current `ClosV`, JVM
stack, VM frame, or Scala closure. Existing runtime closures may already contain host
functions such as `Env => Step`; source CoreIR is not recoverable from them.

Saveability and liveness cover the entire transitively reachable graph, including
captured handler state, reachable mutable globals, unmodeled ambient reads, and
values reachable through closures—not only syntactic local slots. Every such
dependency is frozen into the frame/code, converted to an explicit durable
reference, or rejected. Declared suffix effects such as Clock, Random, or IO remain
in `Fx` and deliberately execute on each run; they are not snapshotted as ambient
values.

The capsule linker includes only the generated resume segment and its transitive
definitions. It does not package and rerun the original application entry. Already
evaluated pure globals are captured by value. Effectful/static initializers are
captured through an explicit durable protocol or rejected. An exact-artifact runner
invokes the generated resume entry directly and must not invoke `main` or rerun
application initialization on startup. These are the mechanical guarantees against
hidden prefix replay.

### 11.3 Closed code and dependencies

The packed CoreIR program is logically and physically closed with respect to
application `Global`: deterministic linking materializes every reachable definition
before closure verification. Content-addressed storage may deduplicate physical
bytes, but all references resolve and hash before the verifier/evaluator sees one
closed program. No original application artifact is needed to resolve a packed
program.

A `Prim` remains a runtime dependency, so the compiler emits an exact primitive,
plugin, and capability manifest. Dynamic string-based registries cannot be inferred
reliably by a final AST walk and require compiler-emitted dependency metadata. A
capsule is portable to a destination only when every declared primitive/plugin has a
portable ABI and an allowed destination implementation; otherwise the capsule is
target-bound even though its code syntax is CoreIR.

The capsule hashes stable semantic ABI ids/schema hashes, not a floating human
version. An exact-artifact manifest pins concrete implementation bytes through
`artifactDigest`; a portable destination manifest maps each semantic ABI to an
approved target implementation digest and records the chosen digest for the run.

The canonical CoreIR Reader/Writer remains kernel-owned as required by the existing
CoreIR contract. The portable self-hosted outer layer owns the saved-capsule envelope,
durable-value/frame codec, dependency closure, save transform, and closure/policy
verifier, invoking the one canonical kernel codec rather than cloning it per backend.
Host runners may harden loading, but accepted bytes and validation results must agree.

The current `v2/lib/irbin.ssc0`, `.scir`, and `.sscc` formats are not this capsule
format: they lack some combination of the full canonical node/constant set, typed
envelope, durable frame, framing, integrity checks, dependency manifest, and bounded
verification. They may be reused internally only after satisfying this contract.

### 11.4 Two physical cases, one public type

The public `SavedContinuation` is representation-independent. A runtime may encode:

1. **Portable packed capsule.** Closed CoreIR resume code plus a separately hashed
   frozen frame. It can run on a compatible generic CoreIR executor without the
   original application process or an application-specific JVM artifact. Its exact
   resume-code identity is `resumeCodeDigest`.
2. **Exact-artifact state.** Compiler-generated state id plus codec-safe slots and an
   exact `artifactDigest`. This is required when the managed continuation contains
   transformed Scala/JVM bytecode segments or other non-CoreIR code.

Embedding exact JVM artifact classes is only packaging of the second case, not conversion to
portable CoreIR. A general Scala-3-to-CoreIR compiler is not part of this design.

Both cases use the same opaque codec and `save`/`run` API and satisfy the same public
control laws; an exact-artifact payload is not required or claimed to decode into a
CoreIR program. The representation may be inlined, content-addressed, or referenced
from encrypted shared storage. Raw JVM serialization and application-visible frame
bytes are forbidden.

An inline exact-artifact payload must carry a finite `notAfter`; its exact artifact
bundle remains pinned until that time because offline copies cannot be counted.
An inline packed portable capsule may be perpetual when trust/retention policy
allows it because it pins no application artifact.
Provider-backed identities permit earlier deletion/revocation: runs admitted before
successful revocation may finish, and no run admitted afterward may start. A fully
packed portable capsule pins no application-specific JVM artifact.

---

## 12. Network and deployment semantics

A `SavedContinuation` has a portable, versioned codec and may be sent through HTTP,
a message queue, a database field, or ordinary application serialization. The
receiver may call `run` on another process or machine.

“Portable codec” means a target-independent transport envelope, not that every
payload is executable by every target. The tagged representation and destination
capability check determine executability.

Before executing, the receiver validates the envelope, signature, input/result codec
schema hashes, type fingerprints, CoreIR/control ABI, primitive/plugin manifest,
capabilities, and resource bounds. It then admits a fresh `ExecutionId`, reconstructs
a fresh execution, and applies the supplied input exactly once at the resume entry.

One explicit run is one invocation accepted by a runner. ScalaScript runner/client
adapters never automatically retry an admitted run. Any external HTTP retry or MQ
redelivery that reaches admission is a distinct run unless an optional idempotency
policy is enabled. A disconnect or crash after admission produces typed
`RunOutcomeUnknown`: the resume entry or some suffix effects may already have run.
The saved value remains reusable, but starting another run is an explicit caller
decision and may repeat suffix effects. Idempotency/admission keys and one-shot
claims are optional policies.

Transfer moves the saved continuation, not the caller's outer dynamic handler stack.
An in-process `run` returns `Eff[Fx | Restore, R]` to handlers at the invocation site.
A remote endpoint must either require `Fx` to be closed or accept an explicit typed
`RemoteRunEnvironment[Fx]` that provides and authenticates every residual handler or
capability. It rejects an unavailable environment before the suffix starts and never
executes residual effects with ambient handlers. Streaming `Op` requests back to the
original caller is a separate distributed-handler protocol and a base non-goal.

For a portable packed capsule, the destination needs only a compatible generic
CoreIR runner and the declared dependencies. For an exact-artifact mixed Scala/JVM
payload, routing must locate the exact JAR/runtime bundle and start a compatible
exact-artifact runner on a JVM machine. New work may use a new artifact while saved
handles for earlier artifacts remain runnable.
Reusable handles do not naturally “finish”; a provider-backed artifact reference
can be collected only after its records are deleted/revoked or expire and active
executions have ended. A fully packed portable capsule holds no reference to an
application-specific JVM artifact.

Cross-backend execution is semantically possible for a portable capsule, but a
backend must provide a versioned dynamic CoreIR loader and every required primitive.
The first implementation is the managed JVM runner. AOT-only JS/Rust/Swift/WASM
artifacts do not acquire dynamic loading implicitly. Mixed Scala continuations remain
JVM-specific.

Accepting CoreIR during code generation does not satisfy this runtime requirement.
JavaScript, WASM, Rust, and Swift need explicit hardened dynamic load/eval support
before they may claim remote capsule execution.

---

## 13. Saveability, resources, and capabilities

### 13.1 Baseline saveable values

The baseline may quote:

- `Unit`, `Boolean`, canonical-width integers, `BigInt`, `Double`, `String`, `Bytes`;
- immutable acyclic constructor data whose fields are saveable;
- compiler-generated closures after closure conversion/defunctionalization;
- compiler-generated prompt identities and portable durable handler state;
- explicit durable references whose codecs and authorization policy are declared.

### 13.2 Capture barriers

Saving is rejected when the live region contains an unmodeled:

- arbitrary `ClosV`, Scala lambda/object, `ForeignV`, or reflective handle;
- mutable host collection/cell with observable aliasing;
- file, socket, thread, future, monitor, lock, or transaction;
- active `try/finally` or resource acquisition scope;
- thread-local, ambient security context, native callback, or JNI frame;
- secret without an explicit encrypted/secret codec;
- cyclic graph without an explicit alias-preserving graph codec.

An explicit rehydration capability may replace a live resource by a stable reference,
but restoration rechecks authorization and may fail with a typed error. The runtime
never silently serializes the resource implementation.

### 13.3 Security

An opaque saved value is either a signed capsule or a bearer reference and must be
treated as a capability:

- treat it as executable code: well-formedness alone never grants trust; require an
  accepted signer/origin policy and execute only inside the declared capability set;
- bind tenant, audience, artifact/ABI, and type fingerprints;
- use an independent high-entropy provider capability id and never log raw bearer
  material; a content hash is not a bearer credential;
- authenticate and authorize every provider-backed restore/run/revoke operation;
- authenticate and, when policy requires, encrypt persisted payloads;
- use an incremental bounded decoder; verify declared arities, symbol grammar,
  closed globals, and statically known direct-call arities before execution, while
  higher-order arity remains runtime-checked under quota;
- enforce decode bytes/depth/items plus execution fuel, heap, wall-time, and
  concurrency limits because reusable continuations amplify work;
- make capability expiry/revocation a typed failure.

---

## 14. Failures and diagnostics

Failures cross the public ABI as typed data/effects. They are not recognized by
catching a hidden exception and comparing its class name. The exact ADT is frozen by
the API slice and must distinguish at least:

| Failure | Meaning |
|---|---|
| `UnmanagedCapture` | a frame was not transformed or explicit-stackless |
| `CaptureBarrier` | an active resource/finalizer/lock/native boundary forbids capture |
| `OneShotSource` | a reusable save was requested from a one-shot suspension |
| `MissingCodec` | a live value has no approved durable codec |
| `UnsupportedGraph` | mutable/cyclic/aliased state lacks an explicit graph policy |
| `AbiMismatch` | CoreIR, control ABI, descriptor, or type fingerprint differs |
| `CodecMismatch` | input/result/frame codec ABI or schema hash differs |
| `MissingDependency` | required primitive/plugin/artifact is unavailable |
| `CapabilityDenied` | a durable capability cannot be authorized or rehydrated |
| `TamperedCapsule` | hash/signature/audience/tenant validation failed |
| `ExpiredOrRevoked` | a declared provider/expiry policy forbids a new run |
| `ResourceLimit` | bounded decoding/execution quota was exceeded |
| `TransportUnavailable` | dispatch failed before admission; no suffix started |
| `RunOutcomeUnknown` | connection/process failed after admission; suffix occurrence is unknown |

Diagnostics point to the capture site and the first offending live value or frame,
including its origin on the opposite-language side when applicable.

---

## 15. Conformance contract

### 15.1 Portable semantic vectors

The same source-independent vectors run against the explicit reference API, v2 VM,
direct ASM, generated JVM, JavaScript, Rust, WASM, and Swift as capability lands:

- fresh/nested prompts and nearest matching reset;
- a same-prompt `shift` inside the shift body, distinguishing true `shift` from
  `shift0`;
- zero-, one-, and many-resume handlers;
- the exact true-`shift` law rather than `shift0`;
- deep handler reinstall and residual-effect forwarding;
- return-clause placement;
- local shared-heap behavior and pure `State` branching;
- stack safety and cancellation;
- one-shot multiplicity violations;
- deterministic capture-barrier diagnostics.

### 15.2 Mixed Scala/JVM vectors

The managed JVM matrix proves:

- Scala reset → ScalaScript shift and the reverse;
- capture in one language and local resume in the other;
- Scala → ScalaScript → Scala → ScalaScript callback ping-pong;
- handlers authored on either side;
- separate compilation and descriptor mismatch rejection;
- explicit API vs macro vs compiler-plugin equivalence;
- two-/three-node mixed tail SCCs at 1,000,000 depth;
- precompiled/reflective/resource barriers.

### 15.3 Saved-continuation vectors

Required cases include:

1. A prefix counter runs once; repeated saved runs never increment it.
2. Two different inputs produce independent suffix results.
3. At least 100 concurrent runs succeed from one immutable capsule.
4. One linear marker effect occurs once per accepted failure-free invocation; no
   runtime or transport retry occurs. Explicit duplicate delivery is a second run.
5. Mutation after save does not change the snapshot; per-run local mutation is
   isolated; an explicit durable reference is intentionally shared.
6. A zero-captured-slot capsule and an alias-preserving frame both round-trip.
7. Captured prompt ids are fresh and isolated across 100 concurrent runs; a free
   external prompt is rejected without a durable prompt capability.
8. A transitive mutable global/ambient dependency is frozen, made explicit, or
   rejected—never accidentally shared.
9. Save → encode → network/process boundary → decode → run on another machine.
10. An effect-closed remote run succeeds; missing `RemoteRunEnvironment[Fx]` rejects
    a residual-effect run before the suffix.
11. Save → process restart → repeated runs.
12. A portable packed capsule runs without the original application process or JAR.
13. Mixed Scala state resolves the exact artifact bundle and survives runner restart
    without executing `main` or static/effectful application initialization.
14. Wrong ABI/type/plugin/artifact, A/R codec schema, forbidden resource, and
    one-shot source fail
    before the suffix starts.
15. A CoreIR capsule requiring a target-specific/unavailable plugin is not falsely
    advertised as portable.
16. Tampered, forged, cross-tenant, and expired values are rejected; provider-backed
    revocation is enforced when that lifecycle mode is declared.
17. Decode depth/size/symbol/global/direct-arity limits and execution quotas are
    enforced.
18. Pre-admission transport failure reports `TransportUnavailable`; disconnect/crash
    after admission reports `RunOutcomeUnknown`, never retries, and leaves the saved
    value reusable.

The capsule format also needs canonical encode/decode round trips for every CoreIR
node and constant, including signed zero and bytes, before it is accepted as a
persisted interchange format.

### 15.4 Descriptor and numeric vectors

- Canonical bytes are invariant under map/set iteration order.
- A body-only edit leaves `apiHash` unchanged; a public signature, effect row, prompt,
  or callback-policy edit changes it.
- Legacy `.scim` reads with no new descriptor; overload/JVM target entrypoints
  round-trip without class loading; every hash mismatch fails before class loading.
- `I32` max+1 wraps as I32, an `I64` value above `2^31` round-trips, Scala/SSC
  `Int`/`Long` work in both directions, and Int-vs-Long overloads remain distinct.
- A legacy v2 interface without retained width evidence is rejected deterministically.

---

## 16. Delivery sequence

The implementation order is binding because it protects the self-hosting fixed point:

1. **Design now, no v2 mutation:** freeze the control laws, reference API,
   descriptor/summary/manifest schemas, hash domains, diagnostics, and semantic
   vectors; record CoreIR/numeric blockers without changing measured bytes.
2. **Reference ABI:** implement the compiler-independent Scala `_3`
   `Eff`/handler/prompt/local-control API and typed state-machine builder outside the
   self-hosted compiler dependency graph.
3. **Self-host gate:** finish and freeze UniML P6.5 `F1 → F2/F3 → L1 → X1`.
4. **Reconcile foundations after X1:** version/fix the canonical CoreIR inventory and
   codec, retain/reconcile I32/I64 source-width evidence and lowering, then re-run the
   literal fixed point and portable numeric/canonicalization vectors.
5. **ScalaScript lowering:** add compiler-known `shift`/`reset` lowering to direct
   `Pure | Op` construction/folding or the equivalent stable generic `effect.*` Prim
   ABI.
6. **Managed Scala integration:** add local macros, cross-method plugin,
   descriptor-driven exports/facades, interface-first linking, callbacks, and mixed
   tail SCCs.
7. **Simple JVM persistence first:** generated exact-artifact resume entries,
   codec-safe frames, process restart/remote runner, and finite artifact retention;
   prove no `main`/initializer/prefix replay.
8. **Portable capsule:** implement the self-hosted envelope/frame codec and closure
   verifier, then the packed CoreIR managed-JVM runner. Dynamic non-JVM loaders stay
   optional until separately implemented.
9. **Completion gates and examples:** the full interop matrix, CI-enforced feature
   exposure matrix, and user-facing Scala/ScalaScript save/control examples.

Each slice must be independently specified, conformance-tested, and pushed without
making the Scala SDK a dependency of the portable compiler.

---

## 17. Explicit non-goals for the base milestone

- undelimited `callCC` or arbitrary whole-stack capture;
- a new continuation/effect node or Scala/JVM type in CoreIR;
- serializing an arbitrary current JVM stack, `ClosV`, `ForeignV`, or object graph;
- using the original application entry or an event log to replay the prefix;
- automatic retry or exactly-once claims for external effects;
- making a one-shot source reusable through persistence;
- cross-version frame migration without an explicit audited migration;
- requiring the original application process or JAR for a fully packed portable
  CoreIR capsule;
- pretending mixed Scala bytecode is portable CoreIR;
- same-module Scala↔ScalaScript source cycles in the first mixed-build release;
- weakening `.ssc` platform isolation;
- making the legacy reflective loader or one-reply plugin SPI the control ABI.

Optional one-shot workflow claims, version migration, effect journals/outboxes,
mutable graph codecs, resource virtualization, and dynamic non-JVM CoreIR loaders are
tracked separately in `BACKLOG.md`.

---

## 18. Acceptance checklist

The design is implemented only when all of the following are checked:

- [ ] CoreIR inventory and canonical codec have one tested versioned contract.
- [ ] Explicit Scala and ScalaScript computations satisfy the same effect/control
      vectors.
- [ ] Typed generative prompts preserve residual effects and exact `shift` laws.
- [ ] Macro and plugin direct syntax is differential-equivalent to the explicit API.
- [ ] Pure, effectful, callback, prompt, capture, and tail metadata round-trip through
      `ApiDescriptor` without reflection.
- [ ] Scala↔ScalaScript callbacks and handlers work in both directions.
- [ ] Mixed statically known tail SCCs run at 1,000,000 depth in constant managed
      stack.
- [ ] A reusable `SavedContinuation` runs repeatedly and concurrently without prefix
      replay.
- [ ] A portable CoreIR capsule crosses a machine boundary without the original
      application process or JAR.
- [ ] A mixed Scala/JVM saved continuation routes to its exact compatible artifact.
- [ ] Every unsupported capture/resource/ABI/security case fails before suffix
      execution with a stable typed diagnostic.
- [ ] The self-hosted compiler fixed point remains literal and the Scala integration
      is absent from the seed/bootstrap dependency graph.
