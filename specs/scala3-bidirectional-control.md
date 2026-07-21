# Scala 3 ↔ ScalaScript bidirectional control profile

Status: **explicit Tier 1 and lexical macro M1 implemented; remaining host profile planned**
(2026-07-15).

This document is the Scala 3/JVM host profile of
[`control-interoperability.md`](control-interoperability.md). The target-neutral
specification is the sole owner of `Eff`, effect and handler laws, multi-prompt
`shift`/`reset`, continuation multiplicity, `save`/`run`, durable values and
references, capsule admission, security, and common conformance. This profile fixes
only their Scala/JVM realization. If the documents conflict, the target-neutral
specification wins.

Companions:

- [`scala-interop.md`](scala-interop.md) — landed natural-FQN, JAR, `.scim`, and
  build-tool interop;
- [`polyglot-libraries.md`](polyglot-libraries.md) — ordinary host facades and the
  one-reply plugin SPI;
- [`arch-ffi.md`](arch-ffi.md) — explicit raw platform boundaries;
- [`separate-compilation-plan.md`](separate-compilation-plan.md) — interface-first
  compilation;
- [`v2.0-artifact-format.md`](v2.0-artifact-format.md) — current artifacts, which
  are not saved-continuation capsules;
- [`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md)
  — portable-VM evidence and readiness matrix.

The landed Scala interop tiers remain valid. They do not by themselves provide
managed control: a generated JAR, `Future` wrapper, reflective loader, or
one-reply callback adapter is partial library interop until this profile's typed
value and call bridges pass conformance.

---

## 1. Scope and qualification

The profile covers ordinary Scala 3 programs on the JVM calling ScalaScript and
being called from ScalaScript. It also defines the first exact-artifact
saved-continuation runner.

A lane may claim **Scala/JVM control interoperability** only when it supplies:

- a lossless native typed value bridge;
- a typed call bridge in both directions;
- the compiler-independent explicit control API;
- descriptor-driven effect, prompt, callback, multiplicity, barrier, and tail
  metadata;
- managed callback re-entry and optional direct-style transforms;
- both local and saved-continuation conformance rows.

The following capabilities are advertised independently:

```text
ScalaHostBridge
ScalaExplicitControlApi
ScalaInlineDirectStyle
ScalaPluginManagedControl
JvmExactArtifactRunner
JvmPortableCoreIrRunner
```

An implementation may ship them incrementally, but it may not infer one from
another or report the whole profile complete from a partial facade.

## 2. Compiler-independent Scala API

The first implementation tier is the dependency-free
`io.scalascript:scalascript-control_3` artifact. Its public package is
`scalascript.control`. It uses normal Scala 3 binary cross-versioning (`_3`), not
`CrossVersion.full`; full compiler-version coupling belongs only to the later
compiler-plugin artifact. The leaf is aggregated by this repository's build but is
not a dependency of CoreIR, UniML, the seed, the self-hosted compiler, any backend,
or the legacy heavyweight `scalascript-interop` artifact.

The canonical implementation home is `v2/host/scala/control`. Its build-local sbt
project id remains `scala3ControlApi`; that identifier is not a published coordinate
and does not determine the Maven artifact name. The `v2/host`
tree contains outer host-profile SDKs, bridges, transforms, and runners; it is not
part of `v2/src`, the self-hosted compiler, the seed image, or the bootstrap
dependency graph. “Compiler-independent” and “outside bootstrap” constrain the
dependency graph, not the language generation: this API must not be placed under
the legacy `v1/lang` tree. Future generated full-SDK surfaces may live beside this
reference leaf without changing its artifact or package ABI.

The following type bounds, names, and parameter order are the tier-1 ABI freeze.
Method bodies may use private erasure to implement existential requests, but no
API-declared payload or control member may expose that erasure:

```scala
trait Effect
final class EffectKey[Fx <: Effect] private (val id: EffectId)

object EffectKey:
  def named[Fx <: Effect & Singleton](
    id: EffectId,
    witness: Fx
  ): EffectKey[witness.type]

final case class EffectId(value: String)
final case class OperationId(effect: EffectId, name: String)

enum ResumeMultiplicity:
  case Reusable, OneShot

enum ResumeRejected:
  case AlreadyResumed(operation: OperationId)

trait Operation[Fx <: Effect, A]:
  def effect: EffectKey[Fx]
  def id: OperationId
  def multiplicity: ResumeMultiplicity = ResumeMultiplicity.Reusable

sealed trait Eff[+Fx <: Effect, +A]:
  def flatMap[Fx2 >: Fx <: Effect, B](f: A => Eff[Fx2, B]): Eff[Fx2, B]
  def map[B](f: A => B): Eff[Fx, B]
  def step: Eff.Step[Fx, A]

object Eff:
  def pure[A](value: A): Eff[Nothing, A]
  def defer[Fx <: Effect, A](body: => Eff[Fx, A]): Eff[Fx, A]
  def runPure[A](body: Eff[Nothing, A]): A

  sealed trait Step[+Fx <: Effect, +A]
  object Step:
    final case class Done[A](value: A) extends Step[Nothing, A]
    sealed trait Request[+Fx <: Effect, +A] extends Step[Fx, A]:
      type OpFx <: Effect
      type Result
      def operation: Operation[OpFx, Result]
      def resumption: Resumption[Result, Fx, A]

def perform[Fx <: Effect, A](operation: Operation[Fx, A]): Eff[Fx, A]

trait Handler[Handled <: Effect, Residual <: Effect, A, B]:
  def effect: EffectKey[Handled]
  def onReturn(value: A): Eff[Residual, B]
  def onOperation[X](
    operation: Operation[Handled, X],
    resumption: Resumption[X, Residual, B]
  ): Eff[Residual, B]

def handle[Handled <: Effect, Residual <: Effect, A, B](
  body: Eff[Handled | Residual, A]
)(handler: Handler[Handled, Residual, A, B]): Eff[Residual, B]

sealed trait Resumption[-A, +Fx <: Effect, +R]
object Resumption:
  final case class Reusable[A, Fx <: Effect, R] private[control] (
    continuation: Continuation[A, Fx, R]
  ) extends Resumption[A, Fx, R]
  final case class OneShot[A, Fx <: Effect, R] private[control] (
    continuation: OneShotContinuation[A, Fx, R]
  ) extends Resumption[A, Fx, R]

sealed abstract class Continuation[-A, Fx <: Effect, +R] private ():
  def resume(value: A): Eff[Fx, R]
  def save(): Eff[Save, SavedContinuation.Aux[A, Fx, R]]

sealed abstract class OneShotContinuation[-A, +Fx <: Effect, +R] private ():
  def tryResume(value: A): Either[ResumeRejected, Eff[Fx, R]]

sealed abstract class SavedContinuation[-A, +R] private ():
  type Effects <: Effect
  def run(value: A): Eff[Effects | Restore, R]

object SavedContinuation:
  type Aux[A, Fx <: Effect, R] = SavedContinuation[A, R] { type Effects = Fx }

enum CaptureFailure:
  case UnmanagedCapture(site: String)
  case CaptureBarrier(site: String, detail: String)
  case OneShotSource(site: String)
  case MissingCodec(site: String, typeId: String)
  case UnsupportedGraph(site: String, detail: String)

object Save extends Effect:
  val key: EffectKey[Save.type]

  final case class Rejected(failure: CaptureFailure)
      extends Operation[Save.type, Nothing]:
    val effect: EffectKey[Save.type] = Save.key
    val id: OperationId = OperationId(effect.id, "rejected")
    override val multiplicity: ResumeMultiplicity = ResumeMultiplicity.OneShot

type Save = Save.type

object Restore extends Effect
type Restore = Restore.type

sealed trait Control[P] extends Effect

final class Prompt[P, R] private ()

trait ScopedPrompt[R]:
  type Key
  val prompt: Prompt[Key, R]

def freshPrompt[R]: ScopedPrompt[R]

def reset[P, Fx <: Effect, R](prompt: Prompt[P, R])(
  body: => Eff[Fx | Control[P], R]
): Eff[Fx, R]

type ShiftBody[P, A, Fx <: Effect, R] =
  [Residual >: Fx <: Effect] =>
    Continuation[A, Residual, R] => Eff[Residual | Control[P], R]

def shift[P, A, Fx <: Effect, R](prompt: Prompt[P, R])(
  body: ShiftBody[P, A, Fx, R]
): Eff[Fx | Control[P], A]
```

`EffectKey` combines a stable descriptor identity with one exact singleton effect
owner; there is no global effect registry. Both `EffectKey` and `Operation` are
invariant in `Fx`. `named` retains only the owner's identity and returns
`EffectKey[witness.type]`, even if its argument was statically widened. Repeating
`named` with the same descriptor for the same owner produces equivalent runtime
keys; a null owner is rejected. Reusing one owner with a different descriptor is a
malformed declaration: a matching handler rejects it rather than forwarding it as
a residual effect. Distinct owners have distinct singleton row types and runtime
identity even when their descriptor strings are equal. Consequently a handler
owns exactly one atomic row member. It cannot obtain a key for a union by
covariance, and handling several effects is necessarily ordinary nesting.

`perform` validates one non-null operation, key, operation id, and multiplicity;
the operation id's effect component must equal the key descriptor. It snapshots
those runtime fields before constructing `Op`. The private pending node and every
forwarded/public step view retain the validated key snapshot, so a handler never
re-runs a user-defined `operation.effect` getter to decide row elimination. A null
or changing getter is therefore either rejected at `perform` or irrelevant after
the snapshot; it cannot create a residual request under `Eff[Nothing,...]`.

After exact owner identity the runtime may perform one private narrowing cast. That
cast and the iterative bind stack are not part of the public ABI. No safe singleton
owner of type `Nothing` exists, including through a generic wrapper, so the bottom
effect row can never acquire an operation key and `Eff.runPure` cannot encounter a
request constructed through the safe API. `Control[P]` is the one internal
non-singleton surface: its generative `P` is the atomic owner, and only the private
`Prompt[P,R]` constructor may create its key.

`Eff.Step.Request.resumption` preserves the operation's declared multiplicity. A
reusable request carries `Resumption.Reusable`; a one-shot request carries
`Resumption.OneShot`, and the latter deliberately exposes neither reusable `resume`
nor `save`. `OneShotContinuation.tryResume` atomically claims its concurrency-safe
gate **and invokes the captured continuation before returning** `Right(next)`. A
second or concurrent invocation returns
`Left(ResumeRejected.AlreadyResumed(operation))`. The gate must not be deferred into
the returned `Eff`: interpreting an already-produced `next` again may repeat that
description's effects, but it does not invoke the one-shot continuation again.
Forwarding and deep handling preserve the original gate rather than minting a new
one. This preserves multiplicity without exception-based control.

The public defunctionalized builder is also part of the leaf API:

```scala
trait StateMachine[S, Fx <: Effect, A]:
  def step(state: S): MachineStep[S, Fx, A]

enum MachineStep[S, Fx <: Effect, A]:
  case Continue(next: S)
  case Evaluate(next: Eff[Fx, S])
  case Done(value: A)

trait ResumeStateMachine[S, A, Fx <: Effect, R]:
  def resume(state: S, input: A): Eff[Fx, R]

object StateMachine:
  def run[S, Fx <: Effect, A](
    initial: S,
    machine: StateMachine[S, Fx, A]
  ): Eff[Fx, A]

object Continuation:
  def local[S, A, Fx <: Effect, R](
    state: S,
    machine: ResumeStateMachine[S, A, Fx, R]
  ): Continuation[A, Fx, R]
```

The builder executes only typed state transitions. It neither reflects over `S`
nor claims that arbitrary `S` is durable. `Continuation.local(...).save()` produces
the typed, one-shot `Save.Rejected(UnmanagedCapture(...))` operation. Its operation
result is `Nothing`, so a handler cannot resume the rejection with a fabricated
saved value; `Eff` result covariance widens that non-returning request to the
declared `save()` result. The tier-1
artifact intentionally exposes no `SavePlan`, successful `SavedContinuation`
constructor, byte codec, capsule, or admission service. `Continuation`,
`OneShotContinuation`, and `SavedContinuation` are library-controlled sealed
abstract classes with private constructors, so declaring the same package does not
let user code manufacture a false durable result. A
later post-X1 slice may add a typed defunctionalized save-plan descriptor and a
library-controlled factory without changing the contracts above; successful save
is not part of the tier-1 capability claim.

Landed post-X1 (`specs/durable-continuation-save-run.md`, in-process keystone): the
`Continuation.savable(state, machine, codec: DurableValue[S])` builder supplies that
typed defunctionalized evidence, so its `save()` succeeds — returning a reusable
`SavedContinuation` via the library-owned `SavedContinuation.Authority`-guarded
`Reusable` factory — while `Continuation.runtime`/`Continuation.local` (no codec)
still deterministically perform `Save.Rejected(UnmanagedCapture(...))`. This is the
same-process, `Savable`/no-transport case: the byte codec, capsule, and
exact-artifact/portable-CoreIR runners remain later slices, and user code still
cannot forge a successful `SavedContinuation`.

No API-declared payload or control member may expose `Any`, `AnyRef`, interpreter
`Value`, `DataV`, `ClosV`, VM frames, `SpiValue`, reflection, TLS, host stack
objects, or hidden exception control. The unavoidable universal members inherited
from Scala (`equals`, `Product.productElement`, and analogous compiler-generated
members) are outside this rule; the ABI leak check examines the members declared by
this library and rejects project/runtime erasure types. Cancellation metadata
remains descriptor work: its public state transitions are not invented by this
first API slice.

Scala source visibility is not treated as JVM capability enforcement:
`private[control]` may compile to public bytecode. Raw pending-request construction
is therefore entirely absent from the external JVM surface. Closed capability
constructors (`Continuation`, `OneShotContinuation`, `SavedContinuation`, and
`Prompt`) use private nested implementations or validate a private authority token
when Scala requires a JVM-visible constructor; a null or non-identical token is
rejected before an object exists. The tier-1 ABI gate inspects `javap -public` and
must not expose an unguarded request, key, prompt, resumption-gate, or successful
saved-continuation constructor.

The explicit API is always usable without macros or compiler plugins. A
continuation is locally resumable by construction. In the complete profile, `save`
succeeds only when a post-X1 compiler-generated or typed defunctionalized save plan
supplies evidence. In tier 1, absence of such evidence deterministically performs
`Save.Rejected(UnmanagedCapture(...))`; it never triggers reflective stack
discovery.

The path-dependent prompt key prevents prompt forgery or accidental discharge.
Runtime prompt identity is private managed state and is freshly alpha-renamed for
every saved run as required by the common contract.

`ShiftBody` is rank-2 in the actual residual row. `Fx` is the minimum effect row
declared by the shift body; composing more effects after `shift` may widen it to
`Residual`, so the body must accept `Continuation[A, Residual, R]` for every
`Residual >: Fx`. A monomorphic continuation row is unsound: constructing a shift
at `Fx = Nothing`, appending an effect before `reset`, and then calling `runPure` on
the captured suffix would otherwise hide that appended request. The rank-2 form
rejects this program statically.

The private shift operation retains its minimum row. After exact prompt-token
matching, `reset` may perform one quarantined narrowing to invoke the polymorphic
body at its actual handler residual. This is sound by construction: the operation
entered as `Eff[Fx | Control[P], A]`, and covariance plus `flatMap` can only widen
that row, so the residual at the matching reset is a supertype of `Fx`. The cast is
not a second public row semantics and is covered by the negative widening vector.

### 2.1 Tier-1 behavior and results

The `ScalaExplicitControlApi` capability is implemented. This does not imply any
other capability named in section 1.

- [x] The `_3` leaf artifact and `scalascript.control` package have no dependency
  on CoreIR, UniML, a backend, the CLI, or the legacy interop runtime.
- [x] `Eff` and `StateMachine` are stackless across 1,000,000 binds and 1,000,000
  state transitions.
- [x] Deep handlers support zero, one, or many resumes, reinstall around a resumed
  suffix, forward residual operations, and remain stackless across 100,000 handled
  operations.
- [x] Invariant singleton-owned effect keys reject `Nothing`, generic, and union
  row widening; distinct owners remain distinct, while one owner cannot declare
  conflicting descriptors.
- [x] `perform` validates and snapshots operation identity before constructing a
  request; null keys, changing key getters, and wrong descriptors are rejected or
  made irrelevant to row elimination.
- [x] Reusable and one-shot multiplicity is preserved through binds, forwarding,
  and handlers. One-shot ownership is claimed eagerly and atomically; exactly one
  of 64 concurrent attempts wins.
- [x] Prompt keys are generative. Nearest-match reset, foreign-prompt forwarding,
  true `shift` rather than `shift0`, rank-2 residual rows, nested shifts, and local
  heap sharing are covered.
- [x] Local continuations resume repeatedly. Unsupported local save performs typed
  `Save.Rejected(UnmanagedCapture)`, and user code cannot forge a successful
  `SavedContinuation`.
- [x] The complete compiled-class `javap -public` inventory exposes no project
  runtime, reflection, TLS, unguarded request/prompt constructor, or authority
  issuer.
- [x] Package/POM generation, the focused effect/coroutine/tail conformance slice,
  and the independent portable-VM reference harness pass.

Implementation: `528d73af3`; runnable ordinary-Scala example: `7f908e536`.
`scala3ControlApi/test` passes 39 tests in six suites. The measured stress vectors
are 1,000,000 left-associated binds, 1,000,000 mixed state-machine transitions,
100,000 deeply handled operations, and a 64-way concurrent one-shot race. The
focused conformance slice passes 10/10. `tests/interop-conformance/run.sh` passes
all 9 currently measurable portable-VM axes and continues to report the codec and
runtime-dependent axes as pending rather than treating this host leaf as proof for
them. `packageBin` and `makePom` produce
`io.scalascript:scalascript-control_3:0.1.0-SNAPSHOT`; the production
classpath contains only the Scala libraries.

## 3. Canonical Scala value mapping

Every public boundary uses structured descriptor types:

| Canonical type | Scala 3/JVM surface |
|---|---|
| `Unit` | `Unit` |
| `Boolean` | `Boolean` |
| `I32` | `Int` |
| `I64` | `Long` |
| `BigInt` | `scala.math.BigInt` through a pinned codec |
| `Double` | `Double`, with bit-preserving durable codec |
| `String` | `String` under the canonical Unicode contract |
| `Bytes` | owned/copy-isolated `Array[Byte]` or an approved immutable wrapper |
| option/sum/product | `Option`, generated enums/case classes, or pinned equivalents |
| immutable sequence/map | generated canonical adapters with deterministic encoding |
| callback | generated typed function wrapper with declared convention |

ScalaScript source `Int` and `Long` **both map to canonical `I64`** — ssc `Int` is 64-bit
(`v2/specs/10-core-ir.md` §2, `Int = Long`; measured: `2147483647 + 1 => 2147483648`, no
32-bit wrap), so mapping it to `I32` declared a width the value does not have and told hosts
to truncate above 2^31−1. The two spellings are told apart by retained source width evidence
(`declaredWidth`), which keeps `f(x: Int)` and `f(x: Long)` distinct without lying about the
wire width; a bare integer width is rejected as an ambiguous legacy export. `I32` remains in
the canonical algebra but is unreachable from ScalaScript source, reserved for an explicit
narrowing ABI. See `specs/numeric-width-reconciliation.md` (corrected 2026-07-17; this
paragraph previously asserted the `Int` → `I32` mapping and was false).

An arbitrary `Any`, `AnyRef`, Java/Scala object, lambda, `Class`, reflection handle,
mutable collection, `Future`, thread, or resource is not a portable value.
Host-native types may stay local behind an explicit adapter; they never become
`DurableValue` by inheritance, `Serializable`, a codec derivation accident, or exact
artifact mode.

JVM exceptions are host failures. Only an explicit typed adapter maps a declared
exception domain into an effect/value. Catching by generated exception class name
does not implement effects or control.

## 4. Bidirectional calls and build graph

### 4.1 ScalaScript exports to Scala

Generated Scala facades preserve the landed natural-FQN/JAR model:

- pure exports use host-native Scala carriers and direct delegation;
- effectful exports return typed `Eff[Fx, A]`;
- control-aware callbacks use generated managed wrappers;
- prompts and continuations are opaque typed values;
- terminal adapters to `Future`, `Either`, or throwing APIs are explicit runners.

Terminal adapters end the managed control region unless separately transformed.
They do not preserve an enclosing reset or constitute the correctness ABI.

### 4.2 Scala exports to ScalaScript

Only explicitly selected definitions are exported. The exporter emits:

- canonical `ApiDescriptor` entries before bodies compile;
- generated ScalaScript declarations;
- generated typed JVM glue;
- `ControlSummary` data after bodies compile;
- target entrypoints and implementation digests in `ArtifactManifest`.

Portable Scala signatures import naturally. JVM-only types require an explicit
codec/adapter or platform boundary. This profile never weakens the rule that regular
ScalaScript blocks cannot import `scala.*` or `java.*`.

### 4.3 Interface-first build

The initial mixed graph is a module DAG:

1. extract public Scala and ScalaScript interfaces;
2. generate typed facades and declarations;
3. compile managed bodies;
4. compute control summaries;
5. link one managed runtime scope;
6. validate descriptor, implementation, and control ABI hashes.

Same-module Scala↔ScalaScript source cycles require a later two-phase interface
graph. Repeated speculative compilation is not an implicit solution.

### 4.4 JVM descriptor fields

The common descriptors gain JVM bindings only in this profile:

```text
JvmEntrypoint(
  stableSymbolId,
  ownerInternalName,
  methodName,
  methodDescriptor,
  invocationKind,
  bridgeFlags,
  classLoaderProfile,
  implementationDigest
)
```

Overload identity derives from the canonical ABI signature, not a JVM name alone.
Descriptors round-trip without loading user classes. A body-only edit leaves
`apiHash` stable while changing the appropriate control/artifact digest.
Incompatible schema, row, prompt, callback, target, or implementation metadata is
rejected before class loading or user initialization.

## 5. Direct style and compiler integration

Scala integration has three cumulative tiers:

1. **Explicit API.** Stackless local effects/control and typed state machines
   without compiler integration. Tier 1 reports unmanaged `save` explicitly; the
   successful durable save-plan builder is added only after X1 defines its evidence.
2. **Inline macros.** Lexically visible direct-style reset regions lowered to the
   explicit protocol with precise source positions.
3. **Compiler plugin.** Cross-method state-machine/CPS transformation, managed
   callbacks, saveable-frame metadata, mixed tail graphs, and barrier diagnostics.

The implemented first inline tier is frozen independently in
[`scala3-control-macros.md`](scala3-control-macros.md). It remains in
`io.scalascript:scalascript-control_3` under `scalascript.control.direct`, uses a
typed lexical `Scope`, and accepts a bounded ANF/CPS grammar. It expands only to
the public explicit API and fails closed at callbacks, resources, unsupported
control trees, or a marker outside its matching region. This M1 reference transform
is valid before X1 because it changes no ScalaScript frontend, CoreIR,
codec, seed, backend, or self-hosted compiler byte. It does not claim a
ScalaScript bridge, cross-method plugin transform, or complete host profile.

A macro can transform only syntax it receives. The plugin cannot rewrite already
compiled bytecode. Precompiled Scala/Java code, reflection, unknown virtual calls,
JNI, an uninstrumented executor, and raw callbacks are barriers while active.

Native `Future`, `Promise`, async libraries, Loom continuations/fibers, throwing APIs,
and Java callback conventions are adapters or private optimizations. They are not
the semantic continuation representation and cannot silently upgrade one-shot state
to reusable.

Active `try/finally`, `synchronized`, monitor/lock, transaction, resource scope,
thread-local, class initialization, JNI/native frame, or thread-affine callback is a
capture barrier unless a separately specified transform turns it into managed
state. The first offending frame is reported statically when possible and
dynamically at save otherwise.

## 6. Callbacks, re-entry, and concurrency

Generated wrappers preserve all common callback fields:

```text
PureDirect | Effectful | ManagedControl | ForeignBarrier
AtMostOnce | Many | Unknown
NoEscape | MayEscape
reentrant / concurrent / cancellable / threadAffinity
```

A `ManagedControl` callback re-enters the explicit protocol and retains the
descriptor-selected handler context. Ambient thread-local state is not the
semantics.

Passing a callback through Java SAM conversion, an unknown library, reflection,
executor, `CompletableFuture`, reactive stream, JNI, or precompiled collection code
is a barrier unless a generated adapter owns every invocation. An escaping callback
is rejected or converted to a saved continuation. Copying a wrapper never upgrades
`AtMostOnce`.

Concurrent local resumes and saved runs must allocate independent managed frame and
prompt state. Shared JVM objects remain shared only for ordinary local resume;
durable runs share external identity only through `DurableRef`.

## 7. Mixed Scala/JVM proper tail calls

The linker builds a typed mixed Scala↔ScalaScript call graph. Every statically known
fully managed SCC lowers to one iterative `TailStep` dispatcher:

- pure SCCs use the minimal dispatcher;
- effectful/control SCCs use the stackless effect trampoline;
- generated state carriers remain typed and private.

Unknown virtual/higher-order targets, reflection, method handles without a bound
descriptor, precompiled Java/Scala, native calls, resources/finalizers, async
boundaries, and uninstrumented callbacks are loud tail barriers. Non-tail calls keep
normal JVM stack behavior.

Qualification includes alternating two- and three-function Scala↔ScalaScript cycles
at depth at least 1,000,000 without native or managed stack growth. JVM bytecode
tail-call accidents are not accepted as proof.

## 8. `save` and `run` on JVM

This profile implements both common code modes while preserving the independent
frame gate.

### 8.1 Portable mode

A hardened JVM-hosted CoreIR runner accepts the closed resume program as data,
decodes a fresh durable frame, and applies `(frame, input)` to the resume entry.
It needs no original application process or application-specific JAR. JIT or
bytecode caches keyed by `resumeCodeDigest` are disposable accelerators outside
semantic identity.

Accepting CoreIR during Scala/JVM code generation is not enough. The dynamic runner
has its own versioned loader, bounded verifier, dependency manifest, admission
boundary, and common runner conformance.

### 8.2 Exact artifact mode

Mixed managed Scala/JVM state uses:

```text
ExactArtifact(
  jvmProfile,
  javaVersion,
  scalaVersion,
  compilerPluginVersion,
  runtimeControlAbi,
  artifactDigest,
  resumePointId,
  initFreeJvmEntrypoint
)
```

The artifact contains compiler-generated state ids and only codec-approved frame
slots. It never serializes a JVM stack, object, lambda, classloader, monitor, thread,
future, or native handle.

The exact runner may initialize its own infrastructure, but the resume route is
isolated from application `main`, object initialization, effectful/static
initializers, and prefix code. If loading a class necessarily triggers application
effects before the generated entry, that artifact is not exact-resume-safe.

The baseline deployment strategy retains each content-addressed artifact/runtime
bundle while its inline capsules remain valid. New application versions can serve
new work while old compatible runners remain available. Provider-backed identities
may expire or revoke earlier. Cross-version frame migration is optional and does
not block this baseline.

### 8.3 Frame saveability

Both code modes require a frame containing only approved `DurableValue` data and
inert `DurableRef`s. Exact artifact mode never rescues:

- arbitrary Scala/JVM objects, lambdas, reflection or classloader state;
- mutable aliases without an explicit graph codec;
- files, sockets, threads, futures, locks, transactions, monitors, or JNI handles;
- active finalizers/resource scopes or ambient security/thread-local state.

The compiler-generated plan covers all transitive live slots, reachable handler
state, globals, closure environments, and dynamic dependencies. Each item is frozen,
replaced by a declared durable reference, or rejected.

## 9. JVM admission and restoration

The JVM runner implements the common atomic admission sequence before class loading
that can execute user code:

- bounded canonical envelope and CoreIR decode;
- signature, audience, tenant, expiry, revocation, and quota;
- type/row/frame/codec fingerprints;
- exact primitive, plugin, codec, resolver, and capability implementation digests;
- closed portable code or exact JAR/toolchain/entrypoint identity;
- authenticated residual-effect environment.

`DurableRef` decoding is pure. Admission proves that an approved JVM resolver exists
and the caller may attempt restore. Resource lookup occurs afterward as typed
`Restore`; deletion, expiry, or revocation is `ResourceUnavailable` or the more
specific common failure, not capsule corruption.

One admitted run allocates a fresh execution, alpha-renames prompts, invokes the
resume entry once, and never retries automatically. A process loss after admission
is `RunOutcomeUnknown`.

Classloader and plugin registries are not sources of hidden dependency discovery.
Every implementation selected for the run is pinned in the admitted manifest.

## 10. Complete Scala exposure

The Scala SDK is generated from the same module/API/capability inventory as the
ScalaScript standard library. It is not a second hand-maintained implementation.
Every shipped portable capability has exactly one Scala exposure category:

- native typed API;
- generated facade/export;
- inline macro;
- compiler-plugin transform;
- tooling-only operation;
- explicit JVM adapter;
- target-inapplicable with a normative reason and diagnostic.

CI rejects unclassified capabilities. “Full Scala transparency” is complete only
when the matrix covers the whole shipped language runtime and standard-module
inventory, not only the examples in this document. Source-document constructs may
be tooling-only; portable runtime behavior may not be silently omitted.

## 11. Scala/JVM conformance

In addition to every target-neutral vector, this profile proves:

1. exact value widths, constructor data, collections, effects, and failures in both
   call directions;
2. Scala reset → ScalaScript shift and ScalaScript reset → managed Scala shift;
3. capture on either side and local resume on the other;
4. Scala→ScalaScript→Scala→ScalaScript callback ping-pong, with handlers authored on
   either side;
5. explicit API, macro, and plugin differential equivalence;
6. separate-compilation and pre-classloading descriptor rejection;
7. `Future`, async, Java/precompiled, reflective, resource, monitor, JNI, and
   thread-affine barriers;
8. alternating mixed tail SCCs at depth 1,000,000;
9. repeated and at least 100 concurrent portable runs in-process, fresh process, and
   compatible remote JVM;
10. exact-artifact runner restart using pinned JAR/runtime without application
    `main`, initializer, or prefix replay;
11. raw object/lambda/future/resource rejection in both code modes;
12. missing dependency/resolver/artifact, wrong JVM/Scala/plugin/ABI/codec,
    tampering, quotas, expiry/revocation, pre-admission transport failure, and
    post-admission unknown outcome;
13. a JVM runner accepts a portable capsule from every other qualified producer and
    another qualified runner accepts one from the Scala/JVM managed lane.

Conformance uses the assembled runtime and real admission boundary. The legacy
reflective loader or a unit test that bypasses classloading/plugin manifests is not
proof.

## 12. Delivery and architecture gate

The Scala/JVM milestone is mandatory and independently shippable:

1. freeze the `_3` API, value mappings, JVM descriptor bindings, and diagnostics;
2. implement explicit `Eff` and typed state-machine builders outside bootstrap;
3. finish the full P6.5 X1 gate;
4. after X1, reconcile CoreIR/codec/numeric width and rerun the fixed point;
5. add ScalaScript control/save lowering;
6. add macros, plugin transforms, managed callbacks, and mixed SCC dispatch;
7. add init-free exact-artifact save/run and retained artifact routing;
8. harden the JVM portable CoreIR runner;
9. close JVM host and cross-backend N→M rows.

Scala APIs, macros, compiler plugins, JAR launchers, admission services, and resource
resolvers stay outside UniML, the seed, the canonical codec, and the self-hosted
compiler image. This profile introduces no CoreIR node or value semantics.

Specification/API/descriptor/vector work and the landed host-local lexical macro M1 may
proceed now. M1 is qualified only while it expands to the frozen explicit API and
stays outside the bootstrap graph; it does not advance delivery steps 4--9 or claim
cross-language transparency. ScalaScript frontend/lowering, canonical-codec/loader,
seed-image, and other byte-affecting implementation remains blocked until full
P6.5 X1 is green and frozen. The landed P6.6 `C_min` proof does not close X1. Every
later byte-affecting compiler/kernel change reruns the literal fixed point.

## 13. Profile-specific non-goals

- undelimited `callCC` or arbitrary JVM stack capture;
- treating `Future`, Loom, exceptions, reflection, SAM callbacks, or thread locals
  as the semantic control ABI;
- serializing arbitrary JVM objects/closures or rescuing them with exact mode;
- making the original application process/JAR necessary for portable mode;
- invoking application `main`, initializers, or prefix replay to resume exact mode;
- auto-retry or hidden exactly-once semantics;
- compiling arbitrary Scala bytecode to portable CoreIR;
- making Scala/JVM tooling part of the bootstrap trusted base;
- redefining target-neutral control laws in this profile.
