# Serial interpreter handler dispatch

## Overview

Interpreter-backed HTTP and WebSocket servers may parse requests and write
responses concurrently, but one `Interpreter` instance is mutable and must not
execute two user callbacks at once. This contract makes route handlers,
middleware, WebSocket auth and frame callbacks, and deferred stream callbacks a
single reentrant transaction per interpreter. It prevents an accepted request
from racing another request's application-level read/modify/write work while
preserving concurrency between independent interpreters and in the network
backends.

## Interface

There is no source-language or SPI signature change. `route`, `use`,
`onWebSocket`, `serve`, `serveAsync`, `HttpHandler`, and every backend selection
keep their current APIs.

The interpreter-server runtime owns one internal weakly keyed reentrant gate per
`Interpreter`. Every user callback entered by `InterpreterHttpHandler` or
`InterpreterWsListener` runs through that gate. The gate is reentrant so a
middleware callback can call `next()`, a handler can produce a stream callback,
and existing direct/in-process dispatch does not deadlock.

## Behavior

- [ ] Two concurrent matched HTTP requests targeting one interpreter never have
      more than one user handler body in flight.
- [ ] HTTP middleware plus its nested route handler remain one reentrant
      transaction, including `next()` on the same interpreter.
- [ ] WebSocket auth, open/message/pong/close callbacks and deferred stream
      callbacks share the same per-interpreter gate as HTTP handlers.
- [ ] Requests targeting distinct interpreter instances may execute
      concurrently; socket parsing, response writing and unmatched static
      fallback work are not serialized by this gate.
- [ ] Handler exceptions retain the existing HTTP/WS error mapping, and the gate
      is always released after success or failure.
- [ ] The assembled `ssc --v2` server survives busi's concurrent Vault
      GET/POST, durable restart and canonical browser regressions without losing
      an accepted fact.

## Design

`InterpreterExecutionGate` is internal to `backend-interpreter-server`. A
synchronized `WeakHashMap[Interpreter, ReentrantLock]` gives stable identity
while an interpreter is live without permanently retaining finished
interpreters. Each dispatch acquires the lock, evaluates the callback on the
calling/request thread, and releases it in `finally`. This preserves the
backend's virtual-thread and event-loop scheduling: only user interpreter work
is serialized.

The complete middleware transaction is serialized naturally. The outer
middleware invocation holds its interpreter gate while `next()` synchronously
enters the route; `ReentrantLock` permits same-interpreter nesting. Different
middleware interpreters acquire gates in the fixed registered middleware order.

The existing single-thread WebSocket executor remains an ordering mechanism for
frames, but correctness no longer depends on one executor per server: two
servers that expose the same interpreter and direct in-process dispatch share
the same interpreter gate.

## Decisions

- **Per-interpreter reentrant gate** — chosen because multiple server instances
  and the in-process transport can expose the same interpreter. Rejected:
  dispatching HTTP through each server's existing single-thread executor, which
  still races across servers and can deadlock on reentrant entry.
- **Keep I/O concurrent** — the gate wraps only `Interpreter.invoke` callback
  transactions. Rejected: synchronizing the complete network handler, which
  would serialize request parsing, static files and response writes without a
  correctness benefit.
- **Runtime fix, not application sleeps** — accepted requests must be durable by
  construction. Rejected: delaying reactive GETs or polling less frequently in
  busi, which only changes race probability.

## Out of scope

- Making arbitrary host code outside interpreter-server thread-safe.
- Cross-process repository locking or multi-writer storage transactions.
- Changing the separate v2 native `http-fast` plugin execution model.
- Parallel execution within one interpreter; applications needing parallel
  work use explicit Async/actor facilities and isolate mutable state.

## Results

Pending implementation and verification.
