# Toolkit v2 Route-Derived Typed Client

## Overview

Toolkit v2 already has explicit `apiClients:` support, JS/browser HTTP clients,
`awaitClient(...)`, and JVM/Swing in-process clients. The remaining production
gap is the route-derived client path: when users omit manual `apiClients:`,
`RouteDeriver` synthesizes `Api` metadata from `route(...)`, `mount(...)`, and
`routes:` declarations, but non-body routes with `:path` parameters currently
derive `requestType = Unit`. That produces browser methods that cannot accept
the path value.

This slice makes route-derived path-parameter endpoints callable from browser
ScalaScript over fetch while preserving the already-landed explicit
`apiClients:` behavior.

## Interface

For modules without explicit `apiClients:` front matter, derived client metadata
continues to use a single generated client:

```scalascript
val row = awaitClient(Api.getApiItemsById("42"))
```

Derivation rules for endpoints without typed handler evidence:

- No path parameters and no request body: `requestType = Unit`.
- One path parameter and no request body: `requestType = String`, so generated
  JS/JVM methods accept one argument and substitute it into the path.
- Two or more path parameters and no request body: `requestType = Any`, so
  generated methods accept a product/object-shaped input whose fields match the
  path parameter names.
- Body methods (`POST`, `PUT`, `PATCH`) keep `requestType = Any` unless typed
  handler evidence supplies a more specific type.
- Explicit `apiClients:` / `api-clients:` declarations still win unchanged.

## Behavior

- [ ] `RouteDeriver` derives callable request types for non-body
      `route(...)`, `mount(...)`, and front-matter `routes:` endpoints with
      path parameters instead of `Unit`.
- [ ] JS/browser generated clients accept the derived input, fill the path
      parameter, call `fetch`, and decode the response through the existing
      typed JSON facade.
- [ ] JVM/Swing generated clients see the same derived metadata and generate
      callable in-process methods for derived path-parameter endpoints.
- [ ] Explicit `apiClients:` declarations and existing path-param validation
      warnings remain unchanged.
- [ ] A runnable example documents no-manual-`apiClients:` browser usage, and
      conformance includes a JS-only derived-client smoke.

## Out of scope

- Inferring precise response types from raw `route(...)` bodies.
- Inferring numeric path parameter types from names such as `:id`.
- Replacing explicit `apiClients:` metadata or the existing typed JSON facade.
- New public syntax beyond the already-shipped derived `Api` client object.

## Design

The fix belongs in `RouteDeriver.makeEndpoint`: this is where raw route
metadata falls back from typed handler evidence to default request/response
types. `JsGen` and `JvmGen` already generate callable clients when an endpoint
request type is not `Unit`; the bug is that derived path-param routes are
classified as `Unit`, so the downstream codegen cannot know it should expose an
input argument.

The fallback chooses `String` for one path parameter because every URL segment
can be represented losslessly as a string and callers can pass numbers when
they want; JS path substitution stringifies the value. Multiple parameters use
`Any` so product/object field extraction can fill each named segment.

## Decisions

- **Do not infer `Int` from `:id`** - chosen because route paths do not carry
  enough type evidence. Rejected: name-based heuristics, which would silently
  mis-type non-numeric identifiers.
- **Keep response type `Any` for raw `route(...)`** - chosen because the raw
  handler body may return any `Response` shape. Rejected: scanning
  `Response.json(...)` calls in this slice, which would be brittle and broader
  than the production blocker.

## Results

Fill after implementation verification with exact commands, test counts, and
runtime gotchas.
