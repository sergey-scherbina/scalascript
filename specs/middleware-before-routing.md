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

### Backend parity (2026-06-10 follow-up)

The same fix now applies to the other serving runtimes so behaviour is uniform:

- **JS/Node** (`JsRuntimePart1d` `_ssc_http_serve`): on the no-route path, when
  middleware is registered, the chain runs with a passthrough-sentinel base — a
  `use {}` that returns a `Response` short-circuits the unrouted path; one that
  calls `next()` falls through to the existing static/404. (The in-process
  `_ssc_ui_backend_transport` stub is an internal call mechanism, not a serving
  path, and is left as-is.)
- **JvmGen real server** (`ProxyRuntime.onHttpRequest`): restructured exactly like
  the interpreter — chain base is the matched route handler or the static/404
  fallback; a fast path preserves the original `Reject(404)` when no middleware
  is registered.

## 3  Verify

`MiddlewareDispatchTest` (interpreter):

- [x] A global middleware returns its own `Response` for an unrouted path → that
  response is served (not a 404).
- [x] A middleware that calls `next()` on an unrouted path falls through to 404.
- [x] A matched route still runs through middleware unchanged.
- [x] With no middleware registered, an unrouted path keeps the fast-path
  `Reject(404)`.

`NodeBackendTest` (JS/Node): the emitted `_ssc_http_serve` runtime contains the
unrouted-middleware block (`_sscUnrouted` passthrough, guarded on registered
middleware). `runtimeServerJvm` suite stays green for the `ProxyRuntime` change.
