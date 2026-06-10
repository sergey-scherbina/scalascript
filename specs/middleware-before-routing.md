# Global `use {}` middleware runs for unrouted paths

**Status**: LANDED 2026-06-10. Reported by busi (Track A resilience, rozum).

## 1  Symptom

`use { (req, next) => … }` middleware only wrapped a **matched** route's handler.
An unrouted path returned `404 Not Found` *before* the middleware chain ran, so a
leading `use {}` meant to short-circuit an unrouted path (a health probe, a
blanket auth gate) silently never fired. busi hit this making `/healthz` a leading
`use {}` intercept — it just 404'd — and had to register health as real routes and
path-guard it out of every cross-cutting middleware.

## 2  Fix (`InterpreterHttpHandler.onHttpRequest`)

Global middleware now runs for **every** request. The base of the chain is the
matched route handler, or — when no route matches — the fallback/404 renderer:

- a `use {}` that returns a `Response` for an unrouted path short-circuits it;
- a `use {}` that calls `next()` falls through to the normal fallback / 404;
- matched routes are unchanged (middleware wraps the route handler as before).

A fast path preserves the original behaviour exactly when **no middleware is
registered**: an unrouted path still returns `HttpResult.Reject(404, …)` with no
chain overhead. Only apps that actually register middleware see the new
(requested) semantics — and for those, middleware seeing unrouted paths is the
point.

This is the JVM interpreter dispatch (busi's runtime). The JS/Node and JvmGen
runtimes keep their own ordering; bringing them to parity is a follow-up if a
caller needs it.

## 3  Verify (`MiddlewareDispatchTest`)

- [x] A global middleware returns its own `Response` for an unrouted path → that
  response is served (not a 404).
- [x] A middleware that calls `next()` on an unrouted path falls through to 404.
- [x] A matched route still runs through middleware unchanged.
- [x] With no middleware registered, an unrouted path keeps the fast-path
  `Reject(404)`.
