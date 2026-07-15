# Portable residual-effect forwarding

> Status: **design freeze / implementation in progress** (2026-07-15).
> Normative owners: [`control-interoperability.md`](control-interoperability.md),
> [`algebraic-effects.md`](algebraic-effects.md), [`../SPEC.md`](../SPEC.md),
> and the one-shot refinement in
> [`control-one-shot-guard.md`](control-one-shot-guard.md).

## Overview

A deep handler discharges only operations for which its handler closure has a
matching arm. If the nearest handler has no arm for an operation, the operation
remains an explicit three-field `Op(label, argument, continuation)` for the next
enclosing handler. Its continuation reinstalls the skipped inner handler before
continuing. This is the standard residual-effect law:

```text
handle(Pure(value), h) =
  h(Return(value)) when that arm is defined, otherwise value

handle(Op(label, argument, k), h) =
  h(OperationEvent(label, argument, deepResume)) when that arm is defined

  Op(label, argument, deepResume)                 otherwise

deepResume(reply) = handle(k(reply), h)
```

The current JVM runtime invokes a generated partial-function closure as an
ordinary function. A missing operation arm therefore becomes the fatal text
`match: no arm ...`, and `Return` fallback separately guesses the same condition
by parsing an exception message. This feature replaces both with one structured,
recoverable handler-dispatch result. A matching arm's own failures remain normal
failures and are never mistaken for forwarding.

## Interface

There is no new ScalaScript surface syntax, CoreIR term, primitive, or public
wire value. The existing source and CoreIR interfaces remain:

```text
handle(computation) { case Effect.operation(args..., resume) => body }

Pure(value)
Op(label, packedArgument, continuation) // exactly three fields
```

The portable JVM runtime adds one package-private dispatch result:

```text
HandlerDispatch =
  Matched(value: Value)
  | Unhandled

Runtime.dispatchHandler1(
  handler: Value.ClosV,
  event: Value
): HandlerDispatch
```

`Unhandled` means exactly that the designated handler-event pattern dispatch had
no selected arm or default. It does not mean that arbitrary code in a selected
arm failed, that an unrelated nested match failed, or that the handler returned
an error-looking string/value. Backends may represent this private dispatch
result differently, but must preserve that distinction without parsing exception
messages, tags that user code can forge, or public exception class names.

## Behavior

- [ ] An operation with no arm in the nearest handler is returned as exactly
      `Op(label, argument, deepResume)` and is consumable by the next enclosing
      handler on portable VM and direct ASM.
- [ ] Invoking the forwarded `deepResume` first invokes the original
      continuation and then reinstalls the skipped handler, so later operations
      handled by that inner handler remain handled.
- [ ] Forwarding preserves the original base continuation and therefore its
      multiplicity: a forwarded plain `.ssc effect` remains one-shot, while a
      forwarded `multi effect` or raw `effect.perform` remains reusable.
- [ ] Selecting an explicit arm or wildcard/default consumes the recoverable
      dispatch marker before evaluating the arm body. Any nested pattern failure,
      exception, or control failure from that body propagates unchanged and is
      never forwarded.
- [ ] A missing `Return` arm uses the same structured `Unhandled` result and
      returns the pure value unchanged. No runtime path recognizes missing arms
      from exception-message text.
- [ ] Concurrent and reentrant handler calls cannot share a dispatch probe or
      observe another invocation's decision.
- [ ] Interoperability axis 19 is a runnable exact-output vector, and the existing
      one-shot violation, multi-shot, deep-handler, native effect e2e, and affected
      conformance vectors remain green.

## Design

### Structured first-dispatch protocol

The JVM compiler marks only the canonical one-argument handler closure whose
root body is `event match { ... }` as dispatch-qualified. That marker is private
closure metadata, not a source value or serialized descriptor. For such a
closure, the runtime scopes one private, unforgeable probe to each
`dispatchHandler1(handler, event)` invocation. The probe records the exact event
object and has a linear state transition:

```text
Pending -> Matched
Pending -> Unhandled
```

Only the first pattern match whose scrutinee is that exact event object may
transition the probe. Reference identity is intentional: a user-created value
with equal fields is not the runtime's handler event and cannot claim the probe.
Selecting a constructor arm or explicit default performs `Pending -> Matched`
before its body starts. A missing arm performs `Pending -> Unhandled` and returns
a private singleton that is not `DataV`, has no source constructor, and is
immediately consumed by `dispatchHandler1` into `HandlerDispatch.Unhandled`.

Portable VM compilation and direct-ASM generation instrument only that qualified
root match with the same three hooks: enter for the exact event, consume on
selected arm/default, and typed miss on no arm. Ordinary/nested matches run their
unchanged failure path. The private sentinel never crosses `dispatchHandler1`,
never enters `PortableEffects` or an `Op`, and is not a CoreIR/runtime data ABI.

On the JVM the synchronous probe stack may be implemented with a private
`ThreadLocal` scoped by `try/finally`. This is not ambient program state or a
public control ABI: it exists only between `dispatchHandler1` and the handler
event's first pattern decision, is removed on every return/failure, and is
consumed before user arm code or `resume` executes. Reentrant handler dispatch
pushes a distinct probe; concurrent threads have distinct stacks. A target such
as Swift may return the same `Matched | Unhandled` result directly without using
thread-local storage.

The probe is deliberately not prepended to a closure environment. Although a
prefix would leave De Bruijn indices unchanged, a non-canonical closure could
capture that prefix in an escaping closure or durable-frame analysis. The scoped
probe therefore cannot be captured, enumerated, displayed, serialized, or become
an accidental `ForeignV` save barrier.

### Canonical and non-canonical handler closures

The self-hosted and compatibility frontends generate the canonical one-argument
shape `event => event match { ... }`; it performs the designated decision
immediately. `dispatchHandler1` treats any other one-argument closure as an
ordinary total call:

- if it returns normally, the result is
  `Matched(value)`;
- every missing pattern arm or other failure propagates normally;
- delegated, nested, or scrutinee matches cannot claim a probe because a
  non-qualified closure never installs one.

These rules avoid guessing from exception text or treating an arbitrary function
as a partial handler. A malformed/non-canonical frontend result therefore fails
loudly instead of silently gaining residual semantics.

### Forwarding and multiplicity

For an operation event, `PortableEffects.handle` constructs one `deepResume`:

```text
deepResume(reply) = handle(originalContinuation(reply), sameHandler)
```

Both branches reuse that same closure:

```text
Matched(value) => value
Unhandled      => Op(originalLabel, originalArgument, deepResume)
```

The forwarding branch does not call `perform`, repack the argument, parse the
label, or allocate a new one-shot claim. `deepResume` delegates first to the
original continuation, so the atomic base gate introduced by
`effect.perform.oneshot` remains the only gate. Repeated raw/multi resumes remain
valid because their original continuation is reusable.

For a pure result, the runtime dispatches `Return(value)` through the same API:

```text
Matched(mapped) => mapped
Unhandled       => value
```

Thus implicit return and residual forwarding share one typed absence protocol,
while matching-arm failures share the ordinary failure path.

## Decisions

- **Recover only a designated event-pattern miss.** A generic catch of all
  `match: no arm` failures would swallow bugs in matching arms. Rejected:
  exception-message prefix checks and a global recoverable match exception.
- **Use an unforgeable private identity, not `DataV("Unhandled", ...)`.** User
  code can construct arbitrary ADT tags, so a public/tagged sentinel would make
  handler control forgeable.
- **Keep dispatch metadata out of closure environments.** A prepended marker
  preserves indexing but can become captured implementation state. A scoped
  probe has no observable lifetime beyond the first handler match.
- **Qualify only the canonical root handler match.** Private closure metadata is
  set when compiling the one-argument `event => event match` shape. Rejected:
  allowing an unrelated nested/delegated match to claim a pending probe.
- **Rebuild the existing `Op`, not a new residual node.** The frozen CoreIR and
  `Pure | Op` protocol already express forwarding completely.
- **Reuse one deep resume closure for matching and forwarding.** This preserves
  inner-handler reinstall and the original one-shot gate by construction.
- **Keep private deferred-resume machinery separate.** A stack-safety
  implementation may use its own unforgeable internal payload/sentinel; it is
  not the handler-dispatch result and neither identity is a shared public ABI.

## Out of scope

- Effect-row inference or compile-time rejection of closed residual rows.
- Changing operation identity/dispatch from the current operation-event shape.
- Stack-safe recursive effect evaluation (interop axis 20), which composes with
  this dispatch result but is a separate implementation slice.
- Swift's generated match implementation in this commit. Swift must reuse the
  same `Matched | Unhandled` law after its concurrent implicit-`Return` repair;
  it must not copy JVM-private probe machinery.
- `shift`/`reset`, saved continuations, capsule codecs, or cross-host execution.
- Any CoreIR, codec, `Op` arity, or primitive-manifest change.

## Verification

1. Direct runtime tests cover unmatched operation forwarding, inner-handler
   reinstall after outer resume, forwarded one-shot rejection, forwarded
   raw/multi reuse, implicit `Return`, and a matching arm whose body reaches an
   unrelated missing pattern arm.
2. An assembled source fixture fails before the change as
   `match: no arm for wr/2`, then prints the exact expected result on both
   `bin/ssc run` and `bin/ssc run --bytecode`.
3. Pending interop axis 19 becomes a probe/expected pair and the matrix marks it
   measurable now.
4. `tests/e2e/v21-native-effect-handlers-smoke.sh`, affected
   `effects`/`effect-*` conformance, and existing one-shot/multi-shot axes pass.

## Results

Pre-implementation assembled baseline (2026-07-14): an inner `Rd` handler
receives an outer `Wr.wr` event and both portable VM and direct ASM terminate
with `match: no arm for wr/2`. Implementation results are filled by the
spec-verification commit.
