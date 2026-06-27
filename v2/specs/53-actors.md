# 53 — Actors / message passing (a library)

> Status: **v1 (2026-06-27)** — `v2/lib/actors.ssc0`. The actor model — addressed processes
> with private state exchanging messages — as an ssc0 library on the frozen kernel.

## Model

```
Msg(to, payload)                       -- an addressed message
behavior : (state, payload) -> Pair(state', [Msg])   -- handle a message, update state, emit messages
```

The system is a delivery loop over a queue: pop a message, look up the target actor's behavior
and state, run it, persist the new state, enqueue the emitted messages, record the delivery,
repeat until the queue drains.

```
runSys behs states (Msg to p : q) trace =
    let (st', outs) = (lookup behs to) (lookup states to) p
    in  runSys behs (update states to st') (q ++ outs) (p : trace)
runSys behs states [] trace = reverse trace
```

States are an assoc list `id -> state` (each actor's private state); routing is by `id`.

## Example (`conformance/check.sh`)

`actors-pingpong.ssc0`: `ping` and `pong` bounce a `Ball(n)`, each forwarding `Ball(n+1)` to
the other until `n` reaches 5. Starting with `ping ! Ball(0)`, the delivery trace is
`Ball 0, Ball 1, Ball 2, Ball 3, Ball 4, Ball 5`.

## Why this matters / next

ssc 1.0 has a full actor runtime (mailboxes, supervision, wire protocol, clustering). v2 gets
the core semantics — addressing, per-actor state, message-driven behavior — from ~20 lines of
ssc0. On top of the cooperative scheduler (`specs/51-async.md`) this becomes *concurrent*
actors with blocking `receive`; supervision is a parent actor watching children; a wire
protocol is `coreir.encode`/serialization over a transport primitive. All libraries; the
kernel stays a strict, single-threaded, effect-free 913-line core.
