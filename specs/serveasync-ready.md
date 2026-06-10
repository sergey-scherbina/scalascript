# `serveAsync(port)` blocks until bound (readiness)

**Status**: LANDED 2026-06-10. Reported by busi (Track A resilience, rozum).

## 1  Symptom

`serveAsync(port)` started the HTTP/WS server on a virtual thread and returned
**immediately** — before the listen socket was bound. A client (or driver test)
that issued an `httpGet` right after `serveAsync` could race the bind and get a
`ConnectException` / `ClosedChannelException`. busi worked around it with a
connect-retry warmup loop; the framework should not make every caller reinvent
that.

## 2  Fix

`serveAsync` now returns only after the socket is actually bound (or binding
fails fast).

- `WebServer.start` gains an `onBound: () => Unit = () => ()` callback, invoked
  right after `backend.start(port, …)` (the synchronous bind) and before the
  blocking serve loop (`latch.await()`).  Existing callers are unaffected (the
  param is defaulted).
- `InterpreterServerSupportImpl.startServer(async = true)` runs the server on a
  virtual thread but **awaits a readiness latch** (counted down by `onBound`)
  before returning.  A bind exception is captured and re-thrown as
  `serveAsync(<port>) failed to bind: …`; a 15s readiness timeout guards against
  a server that never binds, so the call can never hang forever.

The synchronous `serve(...)` path is unchanged.

### TLS form (2026-06-10 follow-up)

`serveAsync(port, tls(cert, key))` previously spawned its own detached virtual
thread in `HttpIntrinsics` that **swallowed exceptions** — a bad cert silently
never started and the caller returned as if it had. It now routes through the
same readiness path: a new `startTlsServerAsync(port, dir, cert, key)` on the
`NativeContext` / plugin cap delegates to `startServer(…, cert, key, async =
true)`, so the call blocks until the TLS socket is bound and a cert/bind failure
surfaces synchronously as `serveAsync(port) failed to bind: …` (never hangs).

## 3  Verify (`ServeAsyncReadyTest`)

- [x] `startServer(async = true)` then an immediate socket connect succeeds — no
  warmup/retry needed (the bind is complete on return).
- [x] On a contended port the call returns within a bounded time (throws a
  `serveAsync(port)` error or returns), never hanging.
- [x] Async TLS startup with a bad cert surfaces the failure fast as a
  `serveAsync(port)` error instead of swallowing it on a detached thread.
