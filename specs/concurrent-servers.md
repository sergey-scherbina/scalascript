# Concurrent HTTP servers in one process

Source: busi federation regression (rozum `scalascript` room, 2026-06-11).

## Symptom

Two `serveAsync`/`startServer` calls on different ports in the same process — the
second never bound:

```scalascript
startServer(19941)   // A: bound, /healthz → 200
startServer(19942)   // B: never listens → ConnectException on every request
```

Consistent (not flaky). Sequential start→stop→start always worked; only two
**concurrent** servers failed. Broke busi's `federation-readiness.ssc` (peer
ceremony A↔B). It worked on the earlier pin `351cdaf4`.

## Root cause

`WebServer` resolved its backend via `HttpServerBackends.current()`, which returns
a **cached singleton** `HttpServerSpi` instance. `JdkServerBackend.start` begins
with `if _running then return`. So server A's start set the singleton's
`_running = true`; server B's start, on the *same* instance, hit the guard and
**returned without binding** — yet `WebServer.start` still called `onBound()`, so
the readiness latch counted down and `serveAsync` reported success while port B
was never listening.

This single-server limitation was latent. The `serveAsync` block-until-bind
rewrite (`0bf9edc71`) made it visible: by awaiting readiness it **serialized** the
two starts, so B's start always observed `_running = true`. Before, the
fire-and-forget starts raced and (accidentally) both partially bound.

## Fix

Each running server gets its **own fresh backend instance** and serve-loop latch:

- `HttpServerSpi.fresh(): HttpServerSpi` — a fresh, independent instance of the
  same backend (default: reflectively construct another of the same class; impls
  have a no-arg constructor). Overridable if construction needs args.
- `HttpServerBackends.freshInstance()` — same selection logic as `current()`
  (honors `setBackend`) but never returns the shared cached instance.
- `WebServer.start` uses `freshInstance()` and tracks every backend + latch in
  concurrent queues. `WebServer.stop()` stops **all** running servers and releases
  **all** serve loops (idempotent).

N servers now bind independently in one process. `current()` (the cached
singleton) is unchanged for code that wants the selected backend *type*.

## Scope

- Fixes the **interpreter** serving path (`WebServer`, used by `serve` /
  `serveAsync` / `startServer` from `.ssc`) — busi's federation tests run here.
- The JvmGen compiled-server runtime (`ProxyRuntime`) still resolves
  `HttpServerBackends.current()` and has the same single-server shape; not
  exercised by busi's interpreter-run federation tests. Parity is a follow-up if a
  compiled app needs concurrent servers.

## Behavior checklist

- [ ] Two concurrent `startServer(async=true)` on different ports both accept
      connections.
- [ ] `stopServer()` tears down all running servers.
- [ ] Sequential start→stop→start still works (existing resilience tests green).
- [ ] Single-server `serve` / `serveAsync` behavior unchanged (readiness, bad-cert
      fast-fail, bounded return).
