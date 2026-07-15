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

There is no new ScalaScript surface syntax, CoreIR term kind, public primitive
manifest entry, or public wire value. The existing source and CoreIR interfaces
remain:

```text
handle(computation) { case Effect.operation(args..., resume) => body }

Pure(value)
Op(label, packedArgument, continuation) // exactly three fields
```

The portable JVM runtime adds one package-private dispatch result:

```text
HandlerDispatchStep =
  Matched(value: Value)
  | Unhandled
  | Suspended(label: Value, argument: Value, continue: Value => HandlerDispatchStep)

Runtime.dispatchHandler1(
  handler: Value.ClosV,
  event: Value
): HandlerDispatchStep
```

`Unhandled` means exactly that the designated handler-event pattern dispatch had
no selected arm or default. It does not mean that arbitrary code in a selected
arm failed, that an unrelated nested match failed, or that the handler returned
an error-looking string/value. Backends may represent this private dispatch
result differently, but must preserve that distinction without parsing exception
messages, tags that user code can forge, or public exception class names.
`Suspended` is a private intermediate step, not a third source-level decision:
it means an effect in the pattern/guard ran before either terminal decision.
`PortableEffects` folds it back to the same public three-field `Op`, wrapping
only its continuation so the dispatch decision continues after resume.

The JVM implementation has two private producers of that same result:

```text
compiled source handler     -> qualified event-pattern dispatch
runtime/plugin-owned handler -> Runtime.handlerPartialFunction(partialFunction)
```

`handlerPartialFunction` is package-private runtime construction, not a Scala or
ScalaScript API. It uses the partial function's structured terminal fallback to
return `Unhandled`; once a case is selected, the case body runs normally and any
failure propagates. Standard native runners such as `runLogger`,
`runLoggerToList`, and `runStream` must use this construction rather than a
total `ClosV` whose catch-all throws for foreign operations.

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
- [ ] Guarded, duplicate-constructor, literal, and nested source patterns retain
      ordered partial-function behavior: a false guard/pattern continues to the
      next case, terminal fallthrough is `Unhandled`, and only the selected case
      consumes the dispatch before its body.
- [ ] Pattern guards may themselves perform effects. A guard-side operation is
      exposed as an ordinary residual `Op`; after each resume the pending exact
      handler decision continues, then either selects an arm or forwards the
      original operation. Reusable guard effects create independent decision
      branches, while one-shot guard effects retain their base claim.
- [ ] A missing `Return` arm uses the same structured `Unhandled` result and
      returns the pure value unchanged. No runtime path recognizes missing arms
      from exception-message text.
- [ ] Runtime/plugin-owned partial handlers return the same structured
      `Matched | Unhandled` result. An unknown operation crosses a standard
      Logger/Stream runner, and resuming it reinstalls that runner for later
      Logger/Stream operations.
- [ ] Concurrent and reentrant handler calls cannot share a dispatch probe or
      observe another invocation's decision.
- [ ] A nested/delegated partial function or an ordinary reentrant invocation of
      the same qualified closure while a guard is pending cannot consume or
      reject the outer decision even when it receives the exact same event
      object. Event identity, qualified-root owner, and per-invocation activation
      provenance must all match.
- [ ] Adding handler-root flags to newly generated local-`LetRec` closures does
      not break persisted direct-ASM artifacts: the historical three-argument
      static `Emit.letrec(int[], LamFn[], Value[])` descriptor remains linkable
      and delegates with every handler flag false.
- [ ] Every backend that consumes FrontendBridge CoreIR recognizes the two
      bridge-private decision markers. Without a typed dispatch context,
      `selected` is a no-op and `miss` is the backend's ordinary exhaustive-match
      failure; neither becomes a public primitive/capability nor an unknown-op
      regression.
- [ ] Interoperability axis 19 is a runnable exact-output vector, and the existing
      one-shot violation, multi-shot, deep-handler, native effect e2e, and affected
      conformance vectors remain green.

## Design

### Structured first-dispatch protocol

The JVM compiler marks only the canonical one-argument handler closure whose
root body is `event match { ... }` as dispatch-qualified. That marker is private
closure metadata, not a source value or serialized descriptor. Every qualified
closure owns one unforgeable executable token, and every invocation of that
closure receives a second, fresh unforgeable **activation token**. For the one
invocation designated by `dispatchHandler1(handler, event)`, the runtime scopes
a private probe containing the exact event object, closure owner, and designated
activation. The probe has a linear state transition:

```text
Pending -> Matched
Pending -> Unhandled
```

Only the first qualified-root pattern decision whose active owner token,
activation token, and scrutinee identity all match the probe may transition it.
Reference identity is intentional: a user-created value with equal fields is
not the runtime's handler event and cannot claim the probe. Owner provenance
separates different qualified closures. Activation provenance also separates a
recursive or ordinary reentrant invocation of the *same* qualified closure:
even when it receives the exact same event object while an outer guard is
pending, its selected or terminal-miss marker cannot claim the outer probe.
Selecting a constructor arm or explicit default performs `Pending -> Matched`
before its body starts. A missing arm performs `Pending -> Unhandled` and returns
a private singleton that is not `DataV`, has no source constructor, and is
immediately consumed by `dispatchHandler1` into `HandlerDispatch.Unhandled`.

Portable VM compilation and direct-ASM generation instrument only that qualified
root match with the same three hooks: enter for the exact event, consume on
selected arm/default, and typed miss on no arm. Ordinary/nested matches run their
unchanged failure path. The private sentinel never crosses `dispatchHandler1`,
never enters `PortableEffects` or an `Op`, and is not a CoreIR/runtime data ABI.

The compatibility frontend's general pattern lowering cannot always retain a
single CoreIR `Match`: guards, repeated outer constructors, literals, and nested
patterns become an ordered `Let`/`If` decision chain. For a partial-function
literal only, that lowering emits two bridge-private markers carrying the exact
root event reference:

```text
handler-dispatch-selected(event) // after pattern + guard succeed, before body
handler-dispatch-miss(event)     // only after the complete case chain falls through
```

The compiler recognizes the terminal marker as private handler-root metadata.
With an active exact-event probe, `selected` consumes `Pending -> Matched` and
returns unit; `miss` performs `Pending -> Unhandled` and returns the same private
sentinel used by canonical `Match`. Without that probe, `selected` is a no-op and
`miss` raises the ordinary exhaustive-match failure, so calling the partial
function as an ordinary function does not gain recovery. The markers are
bridge/compiler implementation primitives: they are absent from the public
primitive manifest and add no source construct or portable data constructor.

Because FrontendBridge emits one target-neutral `Program`, these markers are
also a private CoreIR-consumer contract rather than JVM-only opcodes. Every
backend which accepts FrontendBridge CoreIR must recognize both names. A backend
which has not yet implemented structured handler dispatch uses the exact
compatibility fallback:

```text
handler-dispatch-selected(event) = Unit
handler-dispatch-miss(event)     = ordinary exhaustive-match failure
```

It must not reject either marker during validation, route it to a public plugin,
or report an unknown primitive at runtime. This fallback preserves the behavior
that preceded marker insertion for ordinary partial functions and guarded
handlers. A backend gains residual forwarding only when its handler driver
intercepts the same exact-event markers and maps them to its private
`Matched | Unhandled` result; the marker names themselves are not permission to
copy the JVM thread-local probe implementation.

The marker keeps its one explicit CoreIR argument. During executable
compilation, every qualified partial-function closure receives a fresh
unforgeable owner token and a private raw entry in target-private closure
metadata. Ordinary calls use the public executable wrapper, which creates a
fresh activation before entering that raw code. `dispatchHandler1` creates its
own designated activation and invokes the raw entry directly under its probe,
so the wrapper cannot replace that activation. Canonical root-match hooks and
general-chain markers compare the current owner and activation with the probe as
well as comparing the event by identity. A nested qualified closure or a
reentrant call to the same closure temporarily scopes a different activation
and restores the outer activation in `finally`, so its `selected` or `miss`
marker retains ordinary partial-function semantics even if its scrutinee aliases
the outer event. Owner tokens, activation tokens, and raw entries never enter a
language environment, CoreIR argument, codec, manifest, or saved continuation.

The selected marker is deliberately placed after all outer/nested pattern tests
and the guard, but before the selected body. Thus a false guard can try a later
same-constructor case, while a nested match failure inside the chosen body is
outside the recoverable decision. A reentrant dispatch during a guard receives a
new probe and restores the pending outer probe on return.

### Effectful guards and suspended decisions

Guards are ordinary ScalaScript expressions; neither the global language spec
nor the algebraic-effect contract restricts them to pure terms. If a guard
returns an `Op` while the exact-event probe is still pending,
`dispatchHandler1` reports `Suspended` rather than `Matched`. The handler fold
re-emits that operation unchanged except for a continuation wrapper:

```text
fold(Suspended(label, argument, continue), onUnhandled) =
  Op(label, argument, reply => fold(continue(reply), onUnhandled))
```

`continue(reply)` invokes the guard operation's original continuation first,
then installs a **fresh** probe for the same exact handler event while the case
chain continues. Freshness matters for reusable guard operations: two resumes
must make independent pattern decisions instead of sharing one consumed mutable
flag. Each fresh probe retains the original designated activation identity, so
the resumed segment remains the same logical handler invocation while any
reentrant qualified call made by that segment receives a different activation.
The synchronous thread-local scope is removed before the residual `Op` crosses
the handler boundary; no `ThreadLocal` or mutable probe is captured. Only the
event reference, immutable owner and activation identities, and original
continuation are retained by the private wrapper, matching the state a portable
handler-decision frame must retain. Because a guard continuation is not itself
the handler-root closure, each resumed segment explicitly scopes the original
owner and activation while it runs.

If the selected marker ran before a handler body produced an `Op`, the probe is
already consumed and that result is `Matched(Op(...))`, not `Suspended`. The
handler body operation therefore propagates normally and can never turn a later
body failure into terminal handler fallthrough.

On the JVM the synchronous probe and current-owner stacks may be implemented
with private `ThreadLocal`s scoped by `try/finally`. This is not ambient program
state or a public control ABI: it exists only while executing one synchronous
decision segment, is removed on every return/failure, and is consumed before
user arm code or `resume` executes. Reentrant handler/partial-function calls
push distinct scopes; concurrent threads have distinct stacks. A target such as
Swift may return the same `Matched | Unhandled` result directly without using
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

### Runtime/plugin-owned partial handlers

Native standard runners construct their handlers at runtime and therefore have
neither a CoreIR root `Match` nor compiler metadata. `Runtime.handlerPartialFunction`
creates a normal one-argument closure with a private direct-dispatch hook. The
hook evaluates the partial function once using its structured fallback:

```text
case selected  -> Matched(body result)
no case        -> Unhandled
body failure   -> propagate
```

`dispatchHandler1` consults this hook before the compiled-source probe path.
Ordinary closure invocation retains ordinary partial-function exhaustion. The
hook is target-local executable metadata, is never placed in the closure
environment, and is not serializable CoreIR or continuation state. A backend
without JVM `PartialFunction` supplies an equivalent private dispatcher.

### Generated-artifact ABI compatibility

New direct-ASM classes pass one `boolean[]` alongside local-`LetRec` arities and
functions so only qualified handler closures receive dispatch metadata. Runtime
`Emit` must also retain its historical overload:

```text
letrec(arities: int[], functions: LamFn[], environment: Value[]): Value[]
```

That overload delegates to the new four-argument implementation with an
all-false handler flag array. Existing persisted classes therefore keep linking
against a newer runtime; they simply retain their historical behavior of having
no compiler-qualified residual dispatch inside local `LetRec` handlers.

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
- **Require invocation provenance as well as root and event identity.** Event
  identity alone lets a nested marker-bearing partial function consume the
  outer probe when a guard passes it the same event object. A per-closure owner
  token fixes different closures but still lets an ordinary recursive call to
  that same closure steal the probe. Each qualified closure therefore owns an
  unforgeable executable token and every invocation receives a fresh activation
  token; the root hooks compare both. Rejected: owner-only provenance,
  string/integer site ids, language-environment tokens, and guessing from the
  dynamic call stack.
- **Qualify only the canonical root handler match.** Private closure metadata is
  set when compiling the one-argument `event => event match` shape or the
  frontend's explicitly marked root decision chain. Rejected: allowing an
  unrelated nested/delegated match to claim a pending probe.
- **Mark general-pattern success and terminal fallthrough explicitly.** Pattern
  guards and nested/duplicate cases cannot be inferred from a constructor arm
  alone. Rejected: consuming at the outer constructor before its guard and
  catching the generic `__match_fail__` path.
- **Give every CoreIR consumer a conservative marker fallback.** FrontendBridge
  produces target-neutral CoreIR, so leaving the private markers unknown outside
  the JVM would regress existing guarded/nested partial functions. Rejected:
  treating a JVM-only primitive as though it could safely remain in shared
  CoreIR, and backend-specific frontend lowering that would create divergent
  programs for the same source.
- **Fold a pending decision through guard effects.** A synchronous probe alone
  disappears before an effectful guard resumes. `Suspended` preserves the
  ordinary public `Op` and resumes the decision with a fresh probe. Rejected:
  declaring guards pure, retaining a thread-local across suspension, or sharing
  one mutable probe across multi-shot resumes.
- **Give runtime-owned handlers a private typed hook.** Standard runners have no
  compiled root match, so their partial function supplies `Matched | Unhandled`
  directly. Rejected: catch-all `IllegalArgumentException`, exception-message
  parsing, or pretending every manually built `ClosV` is partial.
- **Keep the old generated-bytecode descriptor.** The boolean handler-root
  array is additive runtime metadata, not permission to invalidate persisted
  classes. Rejected: silently replacing the three-argument `Emit.letrec` ABI or
  forcing an artifact-format version bump for this private optimization.
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
- Swift residual forwarding in this commit. Swift and the other native/source
  backends implement the conservative marker fallback here, and must reuse the
  same `Matched | Unhandled` law when they add forwarding; they must not copy
  JVM-private probe machinery.
- `shift`/`reset`, saved continuations, capsule codecs, or cross-host execution.
- Any public CoreIR term, codec, `Op` arity, source primitive, or
  primitive-manifest change. Bridge-private decision markers are compiler
  implementation details, not a public primitive contract.

## Verification

1. Direct runtime tests cover unmatched operation forwarding, inner-handler
   reinstall after outer resume, forwarded one-shot rejection, forwarded
   raw/multi reuse, implicit `Return`, and a matching arm whose body reaches an
   unrelated missing pattern arm.
2. Frontend VM/direct-ASM tests cover pure and effectful false guards, an
   effectful true guard, a later same-tag guarded case, nested `Return` patterns,
   ordinary partial-function invocation, and a selected body whose nested match
   fails. A same-event nested marker-bearing partial function exercises both its
   selected and terminal-miss paths without changing the outer pending decision.
   A recursive/ordinary reentrant call to that *same closure* and same event
   exercises both paths again, proving activation provenance rather than merely
   owner provenance.
3. Native effect-runner tests send a foreign operation through
   `runLoggerToList` and `runStream`, resume it outside, and observe later
   Logger/Stream operations captured by the reinstated inner runner. A selected
   plugin arm failure remains fatal.
4. A linkage regression resolves and invokes the historical static
   `Emit.letrec(int[], LamFn[], Value[])` descriptor against the new runtime.
5. An assembled source fixture fails before the change as
   `match: no arm for wr/2`, then prints the exact expected result on both
   `bin/ssc run` and `bin/ssc run --bytecode`.
6. Pending interop axis 19 becomes a probe/expected pair and the matrix marks it
   measurable now.
7. `tests/e2e/v21-native-effect-handlers-smoke.sh`, affected
   `effects`/`effect-*` conformance, and existing one-shot/multi-shot axes pass.
8. Targeted JavaScript, Rust, standalone-JVM-source, and Swift checks feed a
   guarded/general partial function from FrontendBridge to each CoreIR consumer;
   selected cases retain their prior result and terminal miss retains the
   ordinary exhaustive-match failure rather than validation/unknown-primitive
   failure.

## Results

Pre-implementation assembled baseline (2026-07-14): an inner `Rd` handler
receives an outer `Wr.wr` event and both portable VM and direct ASM terminate
with `match: no arm for wr/2`. Implementation results are filled by the
spec-verification commit.
