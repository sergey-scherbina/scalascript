# Portable one-shot resumption guard

> Status: **implemented and verified for the one-shot primitive scope**
> (2026-07-15). Swift's implicit-`Return` parity follow-up is now verified in
> `f21abfcc8`; residual forwarding remains a separately tracked runtime gap.
> Normative owners: [`control-interoperability.md`](control-interoperability.md),
> [`../SPEC.md`](../SPEC.md), and the source declaration rules in
> [`algebraic-effects.md`](algebraic-effects.md).

## Overview

ScalaScript has two deliberately different effect surfaces over the same
target-neutral `Pure | Op(label, argument, continuation)` substrate:

| Surface | Resume multiplicity |
|---|---|
| Raw CoreIR/Mira `effect.perform` and library `Comp` | Reusable |
| Typed `.ssc effect E` declaration | One-shot |
| Typed `.ssc multi effect E` declaration | Reusable |
| Scala host `Operation` | Its explicit `ResumeMultiplicity` (host default remains `Reusable`) |

The distinction is source metadata, not a new CoreIR node. The v2 parser already
retains it, but both current v2 lowering paths erase it and therefore silently
upgrade every plain `.ssc effect` to reusable. This feature preserves the bit and
adds one shared atomic gate to the base continuation. JVM VM/direct ASM and the
JVM compatibility adapter share `PortableEffects`; qualified generated target profiles
implement the same contract in their own runtimes. Deep handler and forwarding
wrappers in every profile must delegate to the original gate.

## Interface

The existing raw primitive remains reusable for compatibility:

```text
effect.perform(label, args...)             // reusable raw Comp operation
effect.perform.oneshot(effectId, operationName, args...)
                                             // typed .ssc one-shot operation
effect.handle(computation, handler)
```

Both perform primitives produce the existing value shape:

```text
Op(label, packedArgument, continuation)    // exactly three fields
```

There is no `Continuation` or `Effect` CoreIR term and no multiplicity field is
added to `Op`. `effect.perform.oneshot` receives structured identity as two
separate strings; it must not reconstruct `OperationId` by splitting the legacy
display/dispatch label. It still builds the existing handler label from those
parts. Its initial identity continuation is guarded. Every later
bind/sequence/handler/forwarding wrapper delegates to that same continuation, so
it cannot mint a fresh claim.

The target-neutral rejection is:

```text
ResumeRejected.AlreadyResumed(operation: OperationId)
OperationId(effect: EffectId, name: String)
```

The semantic claim operation is exactly:

```text
tryResume(value): Either[ResumeRejected, Next]
```

The Scala host API exposes that result directly. A `.ssc` handler clause keeps
the source-compatible `resume(value): R` shape; its compiler/runtime-generated
elimination continues on `Right(next)` and aborts the current run on
`Left(AlreadyResumed(operation))` with the structured runner outcome
`ControlRunFailure(AlreadyResumed(operation))`. This is a control-contract
violation, not a user exception: `.ssc try/catch` does not intercept it.

`ControlRunFailure` is the runner-boundary envelope, not a second rejection
algebra:

```text
ControlRunFailure(rejection: ResumeRejected)
```

The runner projects the typed outcome to diagnostics as three separate fields:

```text
code    = "ONESHOT_VIOLATION"
message = "One-shot violation: <Effect>.<op> resumed more than once"
rendered = "error [ONESHOT_VIOLATION]: One-shot violation: <Effect>.<op> resumed more than once"
```

Embedders inspect the structured code and `OperationId`; the message is the stable
human text and retains the established v1 wording. A JVM implementation may use a
private non-catchable exception carrier to unwind to the runner boundary, but its
class name/message are not an ABI and user exception handling must rethrow it.

## Behavior

- [x] A plain `.ssc effect` continuation may be resumed zero or one time; its
      second sequential invocation fails with `AlreadyResumed` before the suffix
      executes.
- [x] Concurrent invocations have exactly one winner, selected by an atomic claim;
      every loser observes the same operation identity and no losing suffix runs.
- [x] `multi effect` and raw `effect.perform` remain reusable and may resume zero,
      one, or many times. Concurrent reusable execution/isolation remains a
      separate saved-continuation/profile obligation.
- [x] Existing deep handling, bind/sequence lifting, and VM/ASM wrappers preserve
      the original one-shot gate instead of allocating a new one. Residual
      forwarding itself remains the separate unsupported axis 19; when introduced,
      it must delegate to this same base gate.
- [x] VM and direct ASM produce the same structured failure and stable message for
      the reporter's `resume(1) + resume(2)` shape; the same `multi effect` program
      returns `3` on both.
- [x] The self-hosted lowerer and compatibility frontend preserve declaration
      multiplicity. Swift AOT, the generated backend in this delivery matrix that
      already advertises typed algebraic effects, implements the same atomic
      rejection, operation identity, stable diagnostic, and non-user-catchable
      one-shot contract. A missing Swift `Return` arm now applies the same identity
      fallback as JVM VM/direct ASM without converting failures inside a selected
      handler arm into a missing-arm result.
- [x] Existing raw/Mira `Comp` tests remain reusable, and every stale typed `.ssc`
      multi-shot fixture explicitly declares `multi effect`.

## Design

### Atomic base claim

`effect.perform.oneshot` allocates one abstract linearizable `unclaimed | claimed`
cell with the base continuation. Invocation atomically transitions
`unclaimed -> claimed` immediately. The winner invokes the captured continuation;
a loser returns the structured rejection without evaluating the continuation or
any suffix. The claim is not deferred into the returned computation. JVM uses
`AtomicBoolean.compareAndSet`; Swift uses a lock-protected claim; other profiles
must provide an equivalent linearizable operation.

Putting the gate on the base continuation is load-bearing. Runtime and bytecode
threading already transform `k` to wrappers of the form `x => use(k(x))`. Calling
any wrapper therefore reaches the same atomic gate. A guard on each handler-facing
`resume` wrapper would be wrong because deep forwarding could manufacture multiple
independent guards for one semantic suspension.

### Lowering

The self-hosted AST form is already
`effect_decl(name, Pair(isMulti, operations))`. Lowering selects:

```text
isMulti = false  => Prim("effect.perform.oneshot", effectId :: operationName :: args)
isMulti = true   => Prim("effect.perform",         legacyLabel :: args)
```

The compatibility frontend retains ordinary and multi effect-name sets instead of
treating `multi` as advisory. Its bridge-private dispatch marker selects the same
shared portable runtime operation. It does not own a second gate implementation.

Generated backends may lower the one-shot primitive to a native atomic gate, but
the result must still behave as the same three-field `Op` protocol. A backend that
does not implement the primitive cannot advertise typed algebraic-effects support.

### Primitive ABI and self-hosting

`effect.perform.oneshot@1` is an additive portable-effect **primitive ABI** entry.
It is not a new CoreIR term/value form: canonical Reader/Writer and the generic
`Prim(name, args...)` encoding remain byte-for-byte unchanged. Today's in-process
CoreIR carries the primitive name and treats that name as ABI version 1; concrete
runtime/AOT primitive tables and allowlists must add it explicitly. When a durable
capsule or artifact emits the dependency manifest required by
[`control-interoperability.md`](control-interoperability.md), it records the exact
`effect.perform.oneshot@1` implementation and admission rejects a target that
lacks it. No dependency is inferred by reparsing the legacy display label.

Changing self-hosted lowering changes compiler output, so the affected ssc1
front/lower fixed-point gate must run. It does not change the CoreIR codec,
canonical node inventory, `Op` arity, or the post-X1 durable frame/capsule encoding;
therefore it does not bypass the X1 byte-format freeze.

Current delivery scope follows measured capability rather than backend names:

| Lane | Obligation in this slice |
|---|---|
| Portable JVM VM + direct ASM | Mandatory; both share `PortableEffects` |
| Swift AOT | Mandatory; it already advertises and executes typed `effect.*` |
| New v2 JS / Rust / WASM CoreIR lanes | Remain explicitly unsupported for all `effect.*` primitives; qualify all primitives together later |
| Legacy v1 JS / Rust and Mira raw `Comp` | Unchanged |

The new v2 JS/Rust generators must not claim progress merely by recognizing the
one-shot name: qualification requires `pure`, reusable perform, one-shot perform,
deep handle, and the shared vectors. Unknown primitives must continue to fail
honestly rather than becoming dummy values.

## Decisions

- **Keep raw `effect.perform` reusable.** Existing CoreIR/Mira library programs use
  it as the universal multi-shot `Comp` constructor. Changing its default would
  silently break a distinct, intentionally low-level surface.
- **Add an explicit one-shot primitive with structured identity, not an argument
  marker or label parser.** A separate name is unambiguous for operations whose
  first user argument is itself a Boolean/string; separate effect/operation names
  preserve `OperationId` without guessing how a display label was escaped.
- **Keep `Op` at three fields.** Multiplicity is enforced by continuation behavior;
  changing the data arity would break every existing lift/forwarding pattern and
  would duplicate metadata that the closure already preserves.
- **Use structured rejection plus a non-catchable run failure and stable
  projection.** Host APIs can return `Either`; source-compatible `.ssc resume`
  cannot change its result type. The runner outcome retains the same rejection,
  while `.ssc try/catch` cannot turn a violated linearity contract into ordinary
  control flow. Message parsing is never the embedding API.
- **Do not import the Scala host artifact into the VM.** Both implement the common
  contract in their profile-specific runtime; neither becomes the other's semantic
  owner.

## Out of scope

- Static detection of a handler arm that visibly calls `resume` twice.
- Changing raw/Mira effect-row `Comp` semantics.
- Durable `SavedContinuation` admission, serialization, or one-shot workflow policy.
- The separate stack-safety and residual-forwarding gaps in conformance axes 20/19.
  Any later residual-forwarding wrapper remains obligated to preserve the base
  one-shot gate rather than minting a fresh claim.
- Implementing the currently unsupported v2 JS/Rust/WASM `effect.*` primitive family.
- `callCC` or a new CoreIR continuation/effect node.

## Verification

The implementation closes only when all of these are green:

1. Direct runtime tests for one-shot sequential/concurrent rejection and reusable
   raw/multi behavior.
2. Lowerer/IR tests proving `effect` and `multi effect` select distinct primitives.
3. Assembled `bin/ssc run` and `bin/ssc run --bytecode` negative/positive e2e.
4. Promoted interop axis 21 plus existing multi-shot axes 02 and 09.
5. Native effect-handler smoke and affected `effects`/`effect-*` conformance.
6. Any generated backend currently advertising typed algebraic effects. In this
   delivery matrix that is Swift AOT; the checked-source vector omits `Return` and
   must therefore exercise the specified identity fallback on every qualified lane.

## Results

### Verified implementation (2026-07-15)

- Runtime/lowering landed in `cbdc4791a`; the stage-2 manifest gate correction
  required to verify the self-hosted image landed in `13b29852e`; user-facing
  documentation landed in `f92594ff3`.
- `PortableEffectsOneShotTest`: 4/4, covering structured sequential rejection,
  raw reusable perform, a 64-way linearizable race with exactly one suffix, and
  non-catchability by `.ssc try/catch`.
- Compatibility frontend dispatch tests: 3/3, covering plain, bare/curried, and
  `multi effect` lowering/ANF classification.
- Real Swift focused tests: 3/3, covering stable sequential failure, a 64-way
  native race (one winner and 63 identical losers), and checked-source plain vs
  multi multiplicity. Follow-up `f21abfcc8` runs the shared checked-source fixtures
  without an explicit `Return` arm on Swift and JVM VM/direct ASM; one-shot still
  reaches the stable violation, reusable multi-shot returns `3`, and a nested
  handler match failure remains a real failure rather than an identity fallback.
- Assembled VM and direct ASM both exit non-zero with empty stdout and exactly
  `error [ONESHOT_VIOLATION]: One-shot violation: One.op resumed more than once`;
  the corresponding `multi effect` program returns `3` on both. IR dumps select
  `effect.perform.oneshot` only for the plain declaration and `effect.perform`
  only for the multi declaration.
- `tests/e2e/v21-native-effect-handlers-smoke.sh`: PASS;
  `tests/interop-conformance/run.sh`: 10 measurable axes PASS, 0 FAIL (axis 21
  promoted); affected conformance: 6/6 PASS across available lanes.
- `scripts/v21-stage2-bootstrap-gate`: both single/multi fixed points true and
  `compiler.image.source-exact=true` after a fresh `installBin` (131 image files).
- Residual forwarding (axis 19) and stack-safe deep effect recursion (axis 20)
  remain explicitly open follow-ups; neither is represented as completed by this
  feature result.

### Pre-implementation baseline (2026-07-14)

- plain `effect One`, `resume(1) + resume(2)` returns `3` in both VM and ASM;
- legacy v1 rejects it as `One-shot violation: One.op resumed more than once`;
- the self-hosted parser retains `false|true`, but both lowerers erase it;
- VM and ASM already converge on `PortableEffects`, so this is one shared-runtime
  fix rather than two backend implementations.
