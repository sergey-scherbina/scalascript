# Portable effect continuation stack safety

Status: **normative implementation design / not yet verified** (2026-07-15).

## Overview

The portable JVM VM and direct-ASM lane already trampoline ordinary proper tail
calls, but the deep-handler wrapper currently invokes the next effect
continuation and then calls `PortableEffects.handle` recursively. A computation
that performs one operation per recursive step consequently overflows the JVM
stack around depth 2,000 even though the same pure recursion runs at depth
2,000,000.

This slice makes handler resumption an explicit private computation and folds it
with an iterative driver. It implements the stackless `Pure | Op(operation, k)`
law in [`control-interoperability.md`](control-interoperability.md) without a new
CoreIR node, a public value constructor, or a second VM/ASM semantics.

## Interface

There is no new source or embedding API. Existing code keeps the same shape:

```scalascript
effect Tick:
  def tick(): Unit

val result = handle(loop(100000, 0)) {
  case Tick.tick(resume) => resume(())
  case Return(value)     => value
}
```

The public effect substrate remains exactly:

```text
Pure(value)
Op(label, argument, continuation) // three fields; continuation is a closure
```

Raw CoreIR/Mira `effect.perform` and `multi effect` continuations remain
reusable. A plain typed `.ssc effect` retains the one atomic gate installed by
`effect.perform.oneshot`; stack-safe wrapping neither duplicates nor bypasses
that gate.

## Behavior

- [ ] Effect-performing tail recursion at depth at least 100,000 completes on
      both installed portable VM and direct ASM with identical output and no
      unbounded native stack growth.
- [ ] At least 100,000 performed operations separated by ordinary bindings and
      statement sequencing complete in order, without skipping or duplicating
      a performed operation.
- [ ] A handler that composes after resume in non-tail position (for example
      `1 + resume(())`) completes at depth at least 20,000 and produces the exact
      accumulated result on both lanes.
- [ ] Deep reinstall applies the matching handler around every resumed suffix;
      the `Return` clause is applied exactly once at the terminal result.
- [ ] A handler-facing continuation may escape its handler arm. Sequential and
      concurrent calls to an escaped reusable continuation run through fresh
      iterative drivers, return ordinary values, and never expose the private
      resume carrier; an escaped one-shot continuation still claims/rejects at
      the later call site.
- [ ] A state-threaded chain in which every handler arm returns a closure that
      invokes its captured `resume` later remains stack-safe at depth at least
      20,000; continuation escape must not move recursion back to the host stack.
- [ ] A nested outer handler still receives a residual operation with the same
      three fields and original base continuation/gate. The private resume
      protocol is never exposed as a residual user operation.
- [ ] Zero-resume, one-shot rejection, reusable multi-shot branching, and
      handler-arm failures retain their existing observable behavior.
- [ ] Interoperability axis 20 is promoted from `pending-runtime` only after the
      tail, bind/sequence, non-tail, nested-handler, and Return-placement vectors
      pass on both installed execution lanes.

## Design

### Private deferred resume request

Calling the handler-facing `resume(input)` no longer executes the captured
continuation recursively. It returns an internal deferred computation encoded
temporarily in the already understood `Op/3` runtime carrier:

```text
Op(
  privateResumeLabel,
  ForeignV(ResumeRequest(privateCapability, nextComputation, handler)),
  afterResume
)
```

The handler-facing closure first invokes the original continuation with the
supplied input and obtains `nextComputation`; it does **not** recursively handle
that result while an iterative driver is already active. This eager invocation
is required for one-shot semantics: the original guarded continuation performs
its atomic claim at the `resume(input)` call, so a losing second/concurrent
resume rejects before any later handler-side observable work. Only deep handling
of the already obtained `nextComputation` is deferred.

The continuation may legally escape the handler arm and be invoked later. A
thread-local active-driver scope distinguishes the two cases:

```text
resume(input):
  next = originalContinuation(input)  // eager claim + next in both cases
  if an iterative driver is active on this thread:
    privateDeferredResume(next, handler)
  else:
    iterativeHandle(next, handler)
```

The driver scope is installed and removed with `try/finally`; it is a depth, not
a process-global Boolean, so nested handlers compose and concurrent calls on
other threads each start their own driver. An escaped raw/multi continuation can
therefore run sequentially or concurrently. An escaped one-shot continuation
retains the same atomic base gate and rejects losing calls before a driver starts.
The fresh-driver path returns the actual handled value: the private carrier must
not cross the resume-call boundary when no driver was active.

`afterResume` starts as the identity continuation. Existing VM and ASM
effect-aware lifting over `Op` composes the rest of the handler expression into
that continuation. This captures tail resume, `val`/sequence suffixes,
arithmetic such as `1 + resume(v)`, matches, method calls, and effect-aware list
HOFs without replaying or inspecting a host stack.

The request is recognized only when all of these hold:

1. the label is the runtime-private resume label;
2. the argument is `ForeignV` containing the private runtime-only
   `ResumeRequest` class;
3. the request contains the exact process-private capability object by reference.

Ordinary CoreIR has no `ForeignV` constructor. `effect.perform` accepts already
evaluated ScalaScript values and cannot obtain or manufacture the private class
or capability. The private request is not registered as a primitive, plugin,
global, constructor, or serializable value. A user can copy the label string but
cannot satisfy the capability check, so that operation remains an ordinary user
operation. This makes the discriminator unforgeable from portable user code;
the string or `DataV` tag alone is never trusted.

### Iterative two-mode driver

`PortableEffects.handle` becomes a loop with two modes and a typed heap list of
driver frames:

```text
ApplySuffix(afterResume)
Rehandle(handler)
```

```text
Handle(computation, handler)
  Pure(v)          -> Handle(v, handler)
  private resume Q -> push Rehandle(handler)
                      push ApplySuffix(Q.after) // now the top frame
                      Handle(Q.next, Q.handler)
  ordinary Op     -> Complete(call handler(event-with-private-resume))
  terminal value  -> Complete(call Return handler once, or identity fallback)

Complete(handlerResult)
  private resume Op(next, h, afterK)
                   -> push ApplySuffix(afterK); Handle(next, h)
  other, ApplySuffix(afterK) :: rest
                   -> Complete(afterK(other))
  other, Rehandle(h) :: rest
                   -> Handle(other, h)
  other, empty     -> return other
```

No transition calls `handle` recursively. Each explicit resume adds at most one
small heap frame, and a completed nested handled suffix consumes that frame
before continuing the handler expression. The computation prefix is not
replayed. Multi-shot handler code creates one independent request per explicit
resume call and therefore retains its existing branching order. The call to the
original continuation remains eager; only the recursive deep-handler fold moves
onto the heap driver.

Private-request recognition precedes ordinary `Op` dispatch in both modes. This
matters when an escaped resume is invoked inside a different active driver: the
request carries its own handler and is adopted by that driver's heap loop rather
than being reported to either user handler as a forged operation. When a private
request appears as the computation currently being handled, `Rehandle` retains
that outer handler: the loop finishes the inner request and its suffix first,
then applies the outer handler to the resulting value or residual operation.
Merely overwriting the current handler would lose outer `Return`, residual
forwarding, and deep-reinstall semantics.

The real-operation dispatch point remains separate from request recognition.
Residual forwarding may report a recoverable unmatched handler clause and
rebuild the original public `Op(label, argument, continuation)` there; it must
not add, inspect, or manufacture a private resume request. Conversely, the
stack driver never parses a handler exception or changes residual ownership.

### Shared VM/ASM semantics

The implementation lives in `PortableEffects`, which both portable JVM lanes
already call for `effect.handle`. Direct ASM needs no alternate handler or
continuation protocol: its `Emit` helpers already propagate an `Op` result
through bindings, sequences, arithmetic, applications, and list traversal.
Focused tests inspect both final behavior and the inability of a label-only
operation to enter the private driver.

## Decisions

- **Private deferred `Op` plus iterative fold** — selected because existing
  effect-aware lifting already captures the handler suffix for tail and
  non-tail resume positions. Rejected: a tail-only `Step` bounce, because it
  leaves `1 + resume(v)` and other non-tail handler composition recursively
  stack-unsafe.
- **Unforgeable `ForeignV` capability discriminator** — selected because a
  public string/tag sentinel can be forged by raw `effect.perform`. Rejected:
  treating a reserved label as sufficient.
- **One shared portable runtime** — selected because VM and ASM already converge
  at `PortableEffects`. Rejected: backend-specific recursion limits or generated
  special cases.
- **Preserve public `Op/3`** — the temporary request uses the existing private
  runtime carrier and is consumed before returning from the handler driver; no
  CoreIR/value/codec/ABI inventory changes.
- **Thread-local active-driver depth** — selected so an in-driver resume can
  hand off without recursive handling while an escaped resume still returns its
  ordinary value. Rejected: forbidding continuation escape (not an algebraic-
  effect law) and a process-global flag (wrong under nesting/concurrency).

## Out of scope

- `shift`/`reset`, saved continuations, capsule codecs, and cross-host execution;
- native Swift stack qualification, which has its own runtime implementation;
- changing operation multiplicity, residual forwarding ownership, or the stable
  one-shot diagnostic;
- optimizing allocation or wall-clock performance beyond removing unbounded
  native stack growth.

## Baseline

On 2026-07-14, the installed portable VM and direct ASM both completed the
effect-recursion probe at depth 500 and threw `java.lang.StackOverflowError` at
depth 2,000 (also at 8,000 and 50,000). Pure tail recursion remained green at
2,000,000. The recursive path is the handler-facing resume closure in
`PortableEffects.handle`: it calls the base continuation and then invokes
`handle(next, handler)` on the same JVM stack.

## Results

Not yet implemented. Fill this section with the exact VM/ASM depths, focused
test counts, interoperability row, and affected conformance results before
checking the behavior items.
