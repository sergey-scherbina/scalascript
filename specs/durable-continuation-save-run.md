# Durable continuation `save()` / `run()` — in-process keystone

> Status: keystone slice of the durable-continuation milestone. Lifts the frozen
> tier-1 "no successful save" contract now that the P6.5 `F1→F2/F3→L1→X1` fixed
> point is green and frozen (`SPRINT.md` GATE-LIFTED note). Governed by the
> lane-neutral contract in [`control-interoperability.md`](control-interoperability.md)
> §8 (`save`/`run`), §9 (durable values), §10 (capsule) and the Scala tier-1
> artifact spec [`scala3-bidirectional-control.md`](scala3-bidirectional-control.md)
> §2.4. This document specifies only what this slice actually lands; the transport
> capsule and the exact-artifact / portable-CoreIR runners are separate follow-on
> slices (see §7).

## 1. The problem this slice removes

Before this slice, `Continuation.save()` unconditionally performs
`Save.Rejected(CaptureFailure.UnmanagedCapture(...))` on every lane, so no
`SavedContinuation` is constructible anywhere. That is **by design** for tier 1,
not a bug: the reference kernel holds two continuation shapes and neither carried a
*save plan* — the typed evidence that a resume entry plus a durable frame exist.

| Shape | Representation | `save()` before / after this slice |
|---|---|---|
| `Runtime` (`Continuation.runtime`) | opaque host closure `A => Eff[Fx, R]` | `Rejected(UnmanagedCapture(site))` — unchanged. A host closure has no reified frame; it is genuinely unsavable in the reference model. |
| `Local` (`Continuation.local`) | managed `(state: S, machine)` with **no** durable evidence for `S` | `Rejected(UnmanagedCapture("Continuation.local"))` — unchanged. The state machine is managed, but without a codec for `S` the snapshot law cannot be honored. |
| `Savable` (`Continuation.savable`, **new**) | managed `(state: S, machine, codec: DurableValue[S])` | **succeeds** — the caller-supplied codec is the typed defunctionalized evidence §8.1 names. |

## 2. New public surface (`scalascript.control`)

```scala
/** Typed durable-frame evidence for a state value. The in-process keystone needs
  * only an independent snapshot; a later slice extends this to a byte codec with
  * the §9.1 canonical encoding. No reflection over `S`. */
trait DurableValue[S]:
  def snapshot(value: S): S

object DurableValue:
  /** For an immutable value type, an independent copy is the value itself. */
  def immutable[S]: DurableValue[S]

/** New managed builder: a resumable state machine whose state carries durable
  * evidence. `save()` on the result succeeds. Mirrors `Continuation.local`. */
object Continuation:
  def savable[S, A, Fx <: Effect, R](
      state: S,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableValue[S]
  ): Continuation[A, Fx, R]

/** In-process admission for a run that performs no provider-backed restore. */
object Restore:
  val key: EffectKey[Restore.type]
  def admitLocally[Fx <: Effect, R](body: Eff[Fx | Restore, R]): Eff[Fx, R]
```

`SavedContinuation.run` keeps its frozen signature
`def run(value: A): Eff[Effects | Restore, R]`.

## 3. Semantics — the laws honored

Multiplicity (§8.1) and snapshot (§8.2), scoped to same-process, `Savable` +
`Portable`/no-transport:

1. `save()` **snapshots** the live state (`codec.snapshot(state)`) and returns a
   reusable `SavedContinuation` via `Eff.pure`. It does **not** consume the source
   continuation and does **not** perform a `Save` operation (no admission service
   in-process; the frozen `Eff[Save, …]` row is inhabited only by the `Rejected`
   path of the other shapes, and `Nothing <: Save` widens the success value).
2. `run(value)` may be called **zero or more times**, sequentially or concurrently.
3. Each run reconstructs an **independent** frame: `codec.snapshot(savedFrame)` per
   run, so mutation of the original after save, and mutation inside one run, are
   invisible to any other run (§8.2). For an immutable `S` the snapshot is identity
   and this holds trivially.
4. Each run begins **directly at the capture point** (`machine.resume(fresh, value)`);
   the prefix, module `main`, and initializers are never re-executed (no replay).
5. The resume entry runs **once per admitted run**; the suffix may itself loop,
   perform effects, or resume nested continuations.
6. `run` returns `Eff[Effects | Restore, R]`. In this slice no `Restore` operation is
   performed — `Restore` is a declared call-site capability marker discharged by
   `Restore.admitLocally` (provider-backed admission that performs real `Restore`
   operations is a follow-on slice, §7).

This is multi-shot resumption, not prefix replay.

## 4. What stays rejected (unchanged)

- `Continuation.runtime(...).save()` → `Rejected(UnmanagedCapture(site))`.
- `Continuation.local(...).save()` → `Rejected(UnmanagedCapture("Continuation.local"))`.
- One-shot continuations expose no `save()` at all (compile error) — unchanged.
- User code cannot subclass `Continuation`/`SavedContinuation` or forge a successful
  saved value — the authority-guarded closed constructors are unchanged.

## 5. ABI gate impact

`ControlApiAbiTest` must accept two new authority-guarded constructors:

- `Continuation$Savable` guarded by `Continuation$Authority`;
- `SavedContinuation$Reusable` guarded by `SavedContinuation$Authority`.

The public builders `Continuation.savable` and `DurableValue.immutable` are user
entries (like `Continuation.local`) and are exempt from the `runtime`/`delegate`
Eff-authority factory check. `DurableValue`/`ResumeStateMachine`/`Restore` are all
`scalascript.control` types, so the forbidden-reference audit is unaffected. Generic
`S` erases to `java.lang.Object`, which is not forbidden (as already true for `Local`).

## 6. Behavior checklist

- [ ] `Continuation.savable(state, machine, DurableValue.immutable).save()` yields a
      `SavedContinuation`; a `handle[Save, …]` sees it via `onReturn`, not `onOperation`.
- [ ] `saved.run(v)` runs the resume entry once at the capture point; the prefix does
      not re-execute (prefix-counter test: counter unchanged across two runs).
- [ ] `saved.run(a)` then `saved.run(b)` both succeed with independent results
      (reusable multi-shot; save not consumed).
- [ ] A mutable-`S` codec that copies proves run-to-run isolation (§8.2): mutation in
      run 1 invisible to run 2.
- [ ] `Continuation.runtime(...).save()` and `Continuation.local(...).save()` still
      reject with `UnmanagedCapture`.
- [ ] `ControlApiAbiTest` green with the two new guarded constructors listed.
- [ ] `Restore.admitLocally(saved.run(v))` type-checks and discharges `Restore`.

## 7. Follow-on slices (not in this slice)

Tracked in `SPRINT.md` under "Reusable continuation save/run":

- `saved-continuation-format` — versioned capsule + `DurableValue` **byte** codec
  (§9.1 canonical encoding, `DurableRef`, graph codecs), reusing the kernel-owned
  CoreIR codec currently being hardened (`coreir-codec-h4-h5`).
- `continuation-exact-artifact-runner-jvm` — managed-JVM exact-artifact resume,
  init-free entrypoint.
- `portable-coreir-capsule-runner-jvm` — closure-converted `(FrozenFrame,input)=>Eff`
  CoreIR resume program on a generic runner.
- `continuation-artifact-retention` — bounded lifecycle/lease.
- Lane parity: mirror on JS (`v2/host/js/control`), then Rust/Swift; flip cross-lane
  vectors `14-durable-save-run-same-process` / `17-no-prefix-main-replay` per lane.
- `control-interop-examples` — the `.ssc` save→run-twice example, unblocked once a
  lane advertises `durable-save`/`no-replay`.
