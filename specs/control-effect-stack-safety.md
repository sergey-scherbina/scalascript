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

There is no new ScalaScript source construct or supported host facade in this
slice. The JVM runtime gains public bytecode methods `completeManaged(Value)`
and `Runtime.runManaged(Code, Env)` because CLI/artifact packages cross Scala
package boundaries; they are internal runtime ABI, not the future typed
`scalascript-control` embedding API. Existing source keeps the same shape:

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
      concurrent managed calls to an escaped reusable continuation are drained
      by the managed-call boundary, return ordinary values, and never expose the
      private resume carrier; an escaped one-shot continuation still
      claims/rejects at the later call site.
- [ ] A state-threaded chain in which every handler arm returns a closure that
      invokes its captured `resume` later remains stack-safe at depth at least
      20,000; continuation escape must not move recursion back to the host stack.
- [ ] VM value positions preserve the direct-ASM `OpAnfNative` contract:
      primitive arguments, application function/arguments, and constructor
      fields evaluate left-to-right, thread each auto-thread operation before
      evaluating the remaining positions, and invoke/build their consumer
      exactly once. Focused raw-CoreIR vectors cover multiple
      operation-producing primitive arguments plus `App`/`Ctor` handler arms
      containing `resume`, with observable VM/direct-ASM ordering parity.
- [ ] An arbitrary primitive supplied by a plugin may return an operation even
      when every primitive argument is pure. Such a result is threaded in an
      application argument, constructor field, and non-final sequence position
      on VM/direct ASM, including when the handler is registered only after the
      CoreIR transformation has run.
- [ ] Effectful `While` conditions and bodies thread resume requests before
      boolean dispatch, discard, or the next iteration. Deep effectful loops
      remain stack-safe on VM/direct ASM; FastCode may not bypass this path.
- [ ] The first curried `App(Global("handle"), computation)` stage preserves
      the computation as raw `Op/3`, while an effectful application function
      position completes before any argument is evaluated.
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

The continuation may legally escape the handler arm and be invoked later.
`resume` uses the same operation in every context:

```text
resume(input):
  next = originalContinuation(input)  // eager claim + next in both cases
  privateDeferredResume(next, handler)
```

This lets ordinary `Op` lifting capture everything after the resume call,
including application of a handler-returned state closure. Starting a fresh
driver inside an escaped `resume` is too early: direct ASM would call the next
escaped closure after that driver returned and rebuild an unbounded
`Emit.app -> Runtime.run` host stack.

The runtime instead exposes an internal `completeManaged(value)` hook. It is
called only at a managed program or host-call boundary and is the identity for
every value except an exact capability-backed private request. For a request it
starts the same iterative driver in `Complete` mode, after the caller suffix has
already been captured in `afterResume`. VM program roots, direct-ASM
`runProgram`, and any future host-to-ScalaScript invocation explicitly declared
managed are mandatory hook sites. `Runtime.run` and `Emit.app` are deliberately
not global hook sites:
draining there would be too early for surrounding effect-aware lifting and
would add a semantic branch to every internal call. Concurrent managed calls
run independent local drivers; the request and driver have no shared mutable
state. An escaped one-shot continuation retains the same atomic base gate and
rejects losing calls before a request is returned.

The JVM implementation uses this explicit hook inventory:

1. `Runtime.runManaged(code, env)` wraps VM **program roots** (`ssc.Main`, the
   installed compatibility/native runners, the v1 CLI `timeV2` benchmark
   wrapper, `BridgeCli`, and `BatchCli`). It is also the explicit hook a future
   descriptor-qualified managed host adapter must call.
2. `JvmByteGen.runProgram` completes the unrolled generated `entry()` result;
   this is the direct-ASM program/library boundary used by installed runners and
   tests.
3. `NativeArtifactRuntime.report` completes the result of a persisted direct-
   ASM artifact before applying the same final-value contract.
4. `V2Result.report` defensively completes its input before classifying Unit,
   an ordinary unresolved public operation, or a printable result. This makes
   every installed CLI path safe even if a caller supplies a raw VM result.

Calling `completeManaged` more than once is harmless: after the first call its
result is an ordinary value or public residual `Op`, for which every later call
is identity. No installed VM/ASM/host boundary in this inventory may return an
exact private request. Ordinary public values, including every public three-
field `Op(label, argument, continuation)`, must cross unchanged.

Generic raw callback paths such as `NativePluginHost.invoke` and the legacy
v1/v2 `PluginBridge` adapters are deliberately not changed by this slice. They
can run inside an effect-aware expression and have no `ManagedControl`
descriptor proving that the callback boundary owns completion; draining there
could consume a request before the caller suffix is lifted. Qualifying those
host paths belongs to the host-profile/descriptor milestone and requires nested
non-tail callback vectors first.

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

Private-request recognition precedes ordinary `Op` dispatch in both modes. A
request carries its own handler and is adopted by the heap loop rather than
being reported to a user handler as a forged operation. When a private request
appears as the computation currently being handled, `Rehandle` retains that
outer handler: the loop finishes the inner request and its suffix first, then
applies the outer handler to the resulting value or residual operation. Merely
overwriting the current handler would lose outer `Return`, residual forwarding,
and deep-reinstall semantics.

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
operation to enter the private driver. Boundary tests additionally assert that
ordinary public `Op` values still escape unchanged while exact private requests
are never observable after a managed program/call returns.

The VM compiler must also mirror direct ASM's positional lifting. Its complete
CoreIR value-position audit is:

| Position | Contract |
|---|---|
| `Let` right-hand sides | already threads each binding before later bindings/body |
| non-final `Seq` terms | already threads before the remaining statements |
| `If` condition | already threads before selecting a branch |
| `Match` scrutinee | already threads before dispatching an arm |
| `App` function and arguments | evaluate function, then arguments left-to-right; thread before remaining arguments and apply exactly once; the first curried `Global("handle")` stage is the exact raw-computation exception |
| `Ctor` fields | evaluate left-to-right; thread before remaining fields and construct exactly once |
| `Prim` arguments | evaluate left-to-right; thread before remaining arguments and invoke exactly once, except the substrate exclusions below |
| `While` condition/body | thread a condition before boolean dispatch and a body before discard/next iteration; retain an iterative pure path and tail-recursive generated loop |

`Lam` and `LetRec` bindings are values whose bodies run only when called. A
final `Seq` result flows outward unchanged. These cases complete the CoreIR
position audit; none may bury or discard a private request.

For every primitive except the four effect-substrate operations below, the VM
applies `Runtime.letThreadOp` whenever an evaluated argument is an auto-thread
operation. The continuation evaluates the remaining arguments before invoking
the primitive, so multiple operation-producing arguments preserve order and the
primitive is called exactly once:

```text
effect.handle
effect.perform
effect.perform.oneshot
effect.pure
```

These exclusions exactly match `OpAnfNative.isEffectPrim`: those primitives
intentionally receive the effect computation or substrate values without
lifting them past their own handler/constructor. `App`, `Ctor`, and `Prim` use
specialized pure paths plus an immutable-prefix effect path; no mutable
partially-filled argument/field array may be captured by a reusable
continuation. Application evaluates an effectful function position before any
argument and preserves tail `Call` on the all-pure runtime path.

The bridge's curried compatibility form is a second, syntactically exact
substrate boundary:

```text
App(Global("handle"), List(computation))
```

Its first stage stores the raw computation and returns the handler-taking
closure; lifting `computation` would float the operation past its own handler.
Only this exact first-stage shape is excluded. The outer application still
sequences its (possibly effectful) function position before the handler
argument. `FrontendBridge.OpAnf`, `OpAnfNative`, and the VM compiler must agree
on this exception, and paren-form regression tests guard it.

An effectful `While` uses an iterative runtime loop on the VM: pure iterations
stay in the local loop, while an operation in the condition captures boolean
dispatch and an operation in the body captures discard plus the next
iteration. Direct ASM lowers the same shape to a local tail-recursive
`LetRec` whose condition is an effect-threaded `Let` and whose true branch is
an effect-threaded `Seq(body, loop())`; existing local-tail code generation
keeps arbitrary depth off the host stack.

FastCode must decline an `App`, `Ctor`, non-substrate `Prim`, or `While` whose
consumed position can produce an operation, so the effect-aware compiler path
remains authoritative. The VM, `OpAnfNative`, and direct-ASM classifiers align
on `App`, dynamic method/effect dispatch, and explicit `effect.perform`,
`effect.perform.oneshot`, and `effect.handle` CoreIR sources used by raw
`run-ir` tests. They must also treat a primitive which is not explicitly proven
to have a non-operation result as operation-producing. In particular, this
decision is based on the CoreIR primitive contract rather than the mutable
contents of `V2PluginRegistry`: a saved/direct-ASM artifact may install its
plugin handlers after transformation, and every registered handler has the
unrestricted result type `List[Value] => Value`. A future plugin SPI may add an
explicit non-suspending descriptor; absence of that descriptor remains
effectful-by-default.

The VM predicate remains a conservative runtime-path selector: false positives
only select the correct effect-aware path, and consumed values are checked at
runtime before threading. Direct ASM uses the same predicate to select its
effect-aware `Let`/sequence chains, so an unknown primitive remains safe even
when no handler is present while the artifact is built. Focused tests register
the witness handler after transformation and assert the required VM/ASM
declines. This is an existing `Op/3` evaluation rule, not a new host or CoreIR
ABI.

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
- **Unknown/plugin primitives are effectful by default** — selected because the
  plugin handler result type permits `Op/3`, while registry membership at build
  time is not stable for a saved artifact. Rejected: consulting the current
  `V2PluginRegistry` during transformation, because a handler installed later
  would reintroduce a backend-dependent buried operation.
- **Always defer; drain only at managed boundaries** — selected so lifting sees
  the complete post-resume suffix and both VM and ASM stay stack-safe for deep
  escaped state-thread chains. Rejected: forbidding continuation escape (not an
  algebraic-effect law), starting a fresh driver inside escaped `resume`
  (direct-ASM host recursion), and global `Runtime.run`/`Emit.app` hooks (too
  early and too broad).

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
