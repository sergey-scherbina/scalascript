# Serial interpreter handler dispatch

## Overview

Interpreter-backed HTTP and WebSocket servers may parse requests and write
responses concurrently, but mutating callbacks must not overlap another
application transaction on the same `Interpreter`. This contract gives safe
HTTP reads a shared section and mutations/WS callbacks an exclusive section. It
prevents an accepted request from racing another request's application-level
read/modify/write work without starving a reactive application behind all of
its concurrent dashboard GETs.

## Interface

There is no source-language or SPI signature change. `route`, `use`,
`onWebSocket`, `serve`, `serveAsync`, `HttpHandler`, and every backend selection
keep their current APIs.

The interpreter-server runtime owns one internal weakly keyed fair reentrant
read/write gate per `Interpreter`. `GET`, `HEAD`, and `OPTIONS` callbacks use the
shared read section. Other HTTP methods, WebSocket callbacks, and any callback
without a safe-method context use the exclusive write section. The gate is
reentrant so a middleware callback can call `next()`, a handler can produce a
stream callback, and existing direct/in-process dispatch does not deadlock.

## Behavior

- [x] Two concurrent mutating HTTP requests targeting one interpreter never
      overlap, and a mutation never overlaps a safe HTTP read.
- [x] Safe GET/HEAD/OPTIONS handlers targeting one interpreter may execute
      concurrently, so an eager reactive dashboard cannot starve a mutation.
- [x] HTTP middleware plus its nested route handler remain one reentrant
      transaction, including `next()` on the same interpreter.
- [x] WebSocket auth, open/message/pong/close callbacks and deferred stream
      callbacks share the same per-interpreter gate as HTTP handlers.
- [x] Requests targeting distinct interpreter instances may execute
      concurrently; socket parsing, response writing and unmatched static
      fallback work are not serialized by this gate.
- [x] Handler exceptions retain the existing HTTP/WS error mapping, and the gate
      is always released after success or failure.
- [x] The assembled `ssc --v2` server survives busi's concurrent Vault
      GET/POST, durable restart and canonical browser regressions without losing
      an accepted fact.

## Design

`InterpreterExecutionGate` is internal to `backend-interpreter-server`. A
synchronized `WeakHashMap[Interpreter, ReentrantReadWriteLock]` gives stable
identity while an interpreter is live without permanently retaining finished
interpreters. Fair ordering prevents a stream of new reads from overtaking a
queued mutation. Each dispatch acquires the method-appropriate lock, evaluates
the callback on the calling/request thread, and releases it in `finally`. This
preserves the backend's virtual-thread and event-loop scheduling.

The complete middleware transaction is protected naturally. The outer
middleware invocation holds its interpreter section while `next()` synchronously
enters the route; the lock permits same-interpreter nesting. Different
middleware interpreters acquire gates in the fixed registered middleware order.

The existing single-thread WebSocket executor remains an ordering mechanism for
frames, but correctness no longer depends on one executor per server: two
servers that expose the same interpreter and direct in-process dispatch share
the same interpreter gate.

## Decisions

- **Fair per-interpreter reentrant read/write gate** — chosen because multiple
  server instances and the in-process transport can expose the same interpreter,
  while a real reactive SPA issues many eager reads. Rejected: dispatching HTTP
  through each server's single-thread executor or an exclusive lock, which
  either races across servers or starves mutations behind dashboard reads.
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
- Parallel mutation within one interpreter; applications needing parallel
  writes use explicit Async/actor facilities and isolate mutable state.

## Results

Implemented by `InterpreterExecutionGate`, a weak per-interpreter fair
`ReentrantReadWriteLock` shared by HTTP, middleware, stream and WebSocket
callback dispatch. Focused execution/HTTP concurrency tests passed 8/8,
including queued-writer fairness and concurrent safe reads. The complete
`backendInterpreterServer/test` module passed 58/58 (one explicit 10k load test
canceled by its normal opt-in gate), and `rest-validate` conformance passed on
INT, JS and JVM after `installBin`.

The assembled runtime then passed busi's paired live Vault HTTP/restart check
and canonical Chromium matrix: JDG, four-locale/offline shell, RBAC, no-JS,
Housing and all eleven Vault transitions were 6/6 green in 2.4 minutes. Housing
and Vault additionally rejected any hidden-area GET during their action loops;
the final Lease/deposit and recovery-rotation facts survived reload. This is
real process/browser evidence over local simulators, not a production external
disclosure or filing.
