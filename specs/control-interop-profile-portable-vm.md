# Control-interoperability — portable-VM reference runner profile

Status: **draft (2026-07-14)** — the runner profile for the portable ScalaScript
VM. Normative ownership of the model lives in the target-neutral core spec
[`control-interoperability.md`](control-interoperability.md); this file is a
**runner-target profile** (parallel to the WASM/WASI runner profile), not a
host-language SDK. It
records how the portable VM realises the core model, which axes it satisfies
today (measured), and the obligations every other runner is checked against —
because the portable VM is the **reference runner**.

Companions: [`control-interoperability.md`](control-interoperability.md) (core),
[`scala3-bidirectional-control.md`](scala3-bidirectional-control.md) (Scala host
profile + `save()`/`run()` durable surface),
[`../tests/interop-conformance/README.md`](../tests/interop-conformance/README.md)
(the executable conformance matrix this profile's reference row comes from).

> Terminology is aligned with the landed core spec: `CodeMode = Portable |
> ExactArtifact` and `FrameGate = Savable | Unsavable(CaptureBarrier)` (core §8.3);
> the portable VM is always `CodeMode = Portable`.

---

## 1. Role — the reference runner

The portable VM (`ssc run` default tree-walking VM, and `ssc run --bytecode` ASM
tier) executes portable CoreIR directly. It is a **runner target**, not a host
language: it exposes no host-language SDK and requires no foreign toolchain to
run. Because it is the substrate all backends share, it is the **reference row**
of the conformance matrix — every host/runner profile (JVM, JS/TS, Rust, Swift,
WASM/WASI) is measured against the same axes, and "runner delivery order" is
chosen by **measured readiness against this matrix**, not by presumed backend age.

Empirically the portable VM is the most complete measured *in-process* reference
row today and already does reusable N→M multi-shot resume. Other profiles have
landed AOT/effect capabilities in different combinations (including Rust
multi-shot), but none may infer host-bridge or dynamic-runner qualification from
those backend tests. This reference row anchors measured ordering without making
backend age or a stale feature inventory normative.

## 2. CodeMode × FrameGate on the portable VM

The core model classifies every capsule on two orthogonal axes. On this runner:

- **CodeMode = Portable (closed CoreIR).** The VM *is* the CoreIR interpreter, so
  its code mode is always `Portable`; it has no `ExactArtifact` path (that mode is
  for runners that capture native code segments). Control lowers to **ordinary
  existing CoreIR data, lambdas, calls, and tail recursion** implementing the
  `Pure | Op` substrate, and adds **no new continuation node**. Runtime-private
  `Perform`/`FlatMap` names or trampolines are implementation details, not CoreIR
  term/value shapes (v2.2 gate, confirmed empirically: effects, handlers,
  multi-shot resume, and TCO run without a CoreIR extension).
  An optional native accelerator/cache is out of scope for the portable runner and,
  when absent, CoreIR is the inherent fallback.
- **FrameGate = Savable | Unsavable.** *(pending — see §4.)* A captured frame is
  `Savable` iff every captured value is a `DurableValue`/`DurableRef`; a frame that
  holds a raw `ForeignV` (live object/socket/lock/closure) is `Unsavable` and
  `save()` must reject with a typed `CaptureBarrier`. The portable VM's runtime
  value model (`DataV`/`ForeignV`) is the natural discriminator: `ForeignV`
  presence in the frame is exactly the barrier. The durable `save()` surface that
  exercises this gate is not yet implemented (post-X1).

## 3. In-process control semantics — reference row

The table records the portable VM's current evidence; an explicitly pending row
is specified but not yet conforming. Every runner profile must pass the same
vectors before it advertises typed algebraic effects. Surface syntax (as accepted
by the self-hosted frontend):

```scalascript
effect E:            // one-shot: zero or one resume per performed operation
  def op(args): T

multi effect M:      // reusable: zero, one, or many resumes
  def op(args): T

handle(prog()) { case E.op(args, resume) => …; case Return(x) => … }
handle { block }  { case E.op(args, resume) => … }   // block form
```

| Axis | Reference row | Notes |
|------|---------------|-------|
| one-shot resume | ✓ `42` | resume invoked once |
| one-shot violation | **pending-runtime** | second/concurrent resume currently runs silently |
| multi-shot resume (reusable N→M) | ✓ `List(1, 2)` | resume invoked ≥2×; `multi effect` |
| nondeterminism (product of choices) | ✓ | `opts.flatMap(o => resume(o))` |
| early return (non-resuming arm) | ✓ | handler arm returns without `resume` |
| deep intra-lane proper tail calls | ✓ `≥10,000,000` | no unbounded managed/native stack |
| effect performed inside a loop | ✓ | `resume` per iteration |
| callback re-entry (managed closure) | ✓ `8` | HOF re-enters a managed closure |
| capture + resume, same host (state-threaded) | ✓ `30` | handler arms return functions |

The one-shot row is conforming only when a second sequential or concurrent
resume is rejected as structured `AlreadyResumed(OperationId)` with portable
diagnostic code `ONESHOT_VIOLATION`. Direct `.ssc resume` exposes that rejection
as `ControlRunFailure(AlreadyResumed(operation))`, which user `.ssc try/catch`
cannot intercept. The message is `One-shot violation: <Effect>.<op> resumed more
than once`, rendered as `error [ONESHOT_VIOLATION]: <message>`. Merely running a
continuation once does not prove that invariant.
Raw CoreIR/Mira `effect.perform` remains reusable; the typed `.ssc effect`
projection selects `effect.perform.oneshot(effectId, operationName, args...)`
without adding a CoreIR node or changing `Op(label, argument, continuation)` arity.

The runnable probes and expected outputs are in
[`../tests/interop-conformance/probes/`](../tests/interop-conformance/probes);
`tests/interop-conformance/run.sh` prints this row and exits non-zero on any
regression. The syntax and multi-shot/nondeterminism/early-return semantics are
also covered by the existing `tests/conformance/effects.ssc` and
`effect-deep-handler-state.ssc` conformance cases.

## 4. Pending — durable and cross-host (post-X1)

The durable-capsule surface is **not yet implemented** on the portable VM and is
gated behind the X1 self-compilation fixed point (byte-affecting codec work starts
only after X1):

- `continuation.save(): Eff[Save, SavedContinuation]` and
  `SavedContinuation.run(value)` — the durable reusable-continuation surface.
- The `DurableValue` wire codec (frame → portable bytes) with `DurableRef`
  (typed schema + resolver ABI + provider/audience/tenant + capability policy).
- Atomic admission (signature, versioned CoreIR codec pin, type/codec
  fingerprints, primitive/plugin/capability/resolver implementation manifest,
  auth, quotas) — checked before any user code runs.

The **one gap the whole model closes** is **cross-host resume**: the portable VM
already does N→M resume *in-process* (§3), so the only missing capability is the
durable serialization needed to cross a process or host boundary. Until then, the
pending axes are enumerated in
[`../tests/interop-conformance/pending/`](../tests/interop-conformance/pending)
(durable save/run, cross-host resume, concurrent multi-shot, no prefix/main
replay, and the negatives: raw-ForeignV reject, missing-resolver reject,
codec/artifact mismatch, signature/quota) so the matrix is never reported as
fully green while they are unmet.

## 5. Prerequisite and conformance obligations

- **Value/call bridge prerequisite.** A *host* profile cannot claim
  control-interop conformance without a native bidirectional value/call bridge
  (`extern → ForeignV` inbound + typed host facades outbound, no `Value`/`SpiValue`
  leaks). The portable VM is a *runner-target* profile, so it carries no host SDK
  bridge; it is measured only on the control axes above.
- **Measuring another runner.** Run the harness with `SSC=/path/to/that-runner`
  (or the runner's equivalent driver) and record its row beside the portable-VM
  reference row. A runner enters its delivery milestone when it passes the
  mandatory measurable subset; full interoperability is not complete until
  JVM/JS/Rust/Swift all pass the durable + cross-host axes.
- **No silent green.** Any axis a runner cannot yet satisfy is reported PENDING,
  never omitted — the reference harness enforces this.
