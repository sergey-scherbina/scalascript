# 56 — Async / Actor Breadth

## Overview

K46 extends the existing cooperative-concurrency library without touching the frozen
Scala kernel. The feature adds a deterministic scheduler for futures/promises, buffered
channels, and mailbox-style actor helpers on top of the existing `Comp = Pure | Op`
effect representation. It is still single-threaded and cooperative: suspension is an
ordinary effect continuation captured by the scheduler.

## Interface

The public surface lives in `v2/lib/async.ssc0`:

- `future child : Comp Int` — spawn `child` as a result-producing task and return its
  future id to the parent.
- `await id : Comp Value` — suspend until the future with id `id` resolves, then resume
  with its value.
- `send channel value : Comp Unit` — send a value to an integer channel.
- `recv channel : Comp Value` — receive one value from an integer channel, blocking if
  the channel buffer is empty.
- `mailboxSend actorId message : Comp Unit` — actor-oriented alias for `send`.
- `mailboxReceive actorId : Comp Value` — actor-oriented alias for `recv`.
- `runAsync queue : [Comp] -> [Value]` — run a queue of computations and return the
  values logged by `log` in deterministic scheduler order.

The existing `yield`, `fork`, `log`, and `runSched` APIs remain compatible. `runSched`
continues to cover the minimal v1 scheduler; `runAsync` is the richer K46 scheduler.

## Behavior

- [x] A parent can `future` a child, continue running, then `await` the future; the
      parent suspends if the child has not resolved and resumes with the child's final
      value after resolution.
- [x] `await` on an already-resolved future resumes immediately without re-running the
      child.
- [x] `recv` on an empty channel suspends the task; a later `send` to that channel wakes
      exactly one receiver with the sent value.
- [x] `send` to a channel with no waiting receiver buffers the value; a later `recv`
      consumes buffered values FIFO.
- [x] Mailbox helpers are thin actor-facing channel aliases: two actor tasks can block
      on their mailbox ids and exchange messages while the scheduler remains purely
      cooperative.
- [x] The new examples run on VM, JS, and native Rust with identical observable logs.

## Out of scope

- OS threads, timers, network I/O, and real parallelism.
- Cancellation, timeouts, back-pressure, select/choice across channels, and bounded
  channels.
- A distributed actor wire protocol. Serialization remains a separate layer
  (`coreir.encode`, `irbin`, or a future transport primitive).
- Typed channel/future payloads in the raw `ssc0` library. The untyped scheduler carries
  arbitrary Core IR values; typed wrappers can be added in `Mira` later.

## Design

`runAsync` schedules `Task(fid, comp)` records. `fid = 0` means a normal task; non-zero
ids are futures allocated by the scheduler. When a future task reaches `Pure(v)`, the
scheduler records `id -> v` and resumes all waiters suspended by `await id`.

Channels are scheduler-owned integer-keyed FIFO buffers plus integer-keyed receiver
waiter lists. `send ch v` either wakes the oldest waiting receiver for `ch` or appends
`v` to the channel buffer. `recv ch` either consumes the oldest buffered value or stores
the current task continuation as a waiter.

Mailbox helpers do not add a second actor runtime. They deliberately reuse channel ids as
actor mailbox ids. This keeps the actor story library-level: actor loops are just tasks
that repeatedly `mailboxReceive self`, update local state in their closure, and
`mailboxSend` messages.

## Decisions

- **Separate `runAsync` from `runSched`** — chosen to preserve the tiny v1 scheduler as a
  readable artifact and avoid changing existing examples. Rejected: replacing `runSched`
  directly, because the new state machine is larger and mixes the teaching example with
  the richer runtime.
- **Integer ids for futures/channels/mailboxes** — chosen because raw `ssc0` has cheap
  `#i.eq` and no generic equality primitive. Rejected: arbitrary-value channel keys,
  because that belongs in a typed/user-facing wrapper or a structural-map-based library.
- **Pure scheduler state instead of kernel primitives** — chosen to preserve Kernel +0.
  Rejected: adding `thread.*`/event-loop primitives, because this slice is cooperative
  concurrency, not true parallelism.

## Results

Implemented in `lib/async.ssc0` as K46 `runAsync`, leaving the existing small `runSched`
unchanged. Added four examples:

- `examples/async-future.ssc0` -> `Cons(1, Cons(2, Cons(10, Cons(20, Cons(7, Cons(7, Nil))))))`
  on VM/JS/Rust. The second `7` is a repeat `await` on an already-resolved future.
- `examples/async-channel.ssc0` -> `Cons(1, Cons(2, Cons(42, Nil)))` on VM/JS/Rust.
- `examples/async-channel-buffer.ssc0` -> `Cons(1, Cons(5, Cons(6, Nil)))` on VM/JS/Rust.
- `examples/async-mailbox.ssc0` -> `Cons(0, Cons(1, Cons(2, Cons(3, Cons(4, Nil)))))` on
  VM/JS/Rust.

Verification: `cd v2 && conformance/check.sh` passed end-to-end after the K46 checks were
added. The final run also checked captured stdout/stderr for `FAIL` and `command not found`;
none were present. JS/Rust generation for the new raw ssc0 examples uses the same
`java -Xss512m -jar` path as the large JSON showcase because the richer scheduler makes the
backend generator stack-heavy on the Scala VM. Kernel remains unchanged (`Kernel +0`).

Gotcha captured during implementation: raw ssc0 top-level value defs are evaluated before
all imported value defs are initialized on some generated backends. `yield` is therefore
defined directly as `Op("yield", (), ...)` instead of as eager `perform("yield", ())`; the
function-valued ops can still call `perform` lazily.
