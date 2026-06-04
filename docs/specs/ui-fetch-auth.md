# std/ui fetch: request headers + `fetchUrlSignal` GET

Status: **implemented** (v1 + v2 landed 2026-06-01). Owner: agent.

## Problem

`std.ui.primitives` exposes reactive fetch primitives:

```scala
extern def fetchUrlSignal(name: String, url: String, refreshTick: Signal[Int]): Signal[String]
extern def fetchAction(method: String, url: String, body: Signal[String], onSuccessTick: Signal[Int]): EventHandler
extern def fetchActionClear(method: String, url: String, body: Signal[String], onSuccessTick: Signal[Int]): EventHandler
extern def fetchTableView(fetchUrl: String, deleteUrl: String, tick: Signal[Int]): View
```

Two gaps block real authenticated clients (surfaced building the `busi`
dashboard, which talks to an RBAC-gated, bearer-token API):

1. **No request headers.** None of the primitives can send an `Authorization:
   Bearer <token>` (or `Content-Type`, etc.) header, so any authed endpoint
   returns 401/403 from the browser.
2. **`fetchUrlSignal` does not actually fetch (JS backend).** Its JS runtime
   impl is a stub — `function _ssc_ui_fetchUrlSignal(name, url, tick) { return
   Signal(''); }` — so the signal never receives the URL's body. Only
   `fetchAction` (click → `fetch(url, {method, body})`) and `fetchTableView`
   (GET on mount + tick) issue real requests today.

So "add headers" is not a one-line change: it spans the `extern` signatures, the
interpreter intrinsics, and both code-gen backends, and the headline use case
(authed live *reads*) additionally requires implementing `fetchUrlSignal`'s GET.

## Where the wiring lives

| Concern | File |
|---|---|
| `extern def` signatures | `runtime/std/ui/primitives.ssc` |
| Interpreter intrinsics | `runtime/std/frontend-plugin/.../FrontendIntrinsics.scala` |
| JS runtime (descriptors, `renderBody` attrs, mount `fetch()` calls) | `runtime/backend/js/.../JsRuntimeSignals.scala` |
| JVM codegen | `runtime/backend/jvm/.../JvmGen.scala` |
| Tests | `runtime/std/frontend-plugin/.../FrontendPluginInterpreterTest.scala`, a cross-backend emit test |

The empty `runtime/std/ui-fetch-plugin/` directory is a vestige; the real
behaviour is split across the rows above.

## Goals

- Optional request headers on `fetchUrlSignal`, `fetchAction`,
  `fetchActionClear`, and `fetchTableView`.
- A working `fetchUrlSignal` GET in the JS backend (mount + on `refreshTick`).
- Back-compatible: existing call sites compile and behave unchanged.
- Identical behaviour across interpreter snapshot, JS, and JVM where applicable.

## Non-goals (v1)

- Cookie / `credentials: 'include'` modes, CORS preflight tuning.
- Response-header inspection from `.ssc`.
- A global "default headers" / fetch-interceptor mechanism (see Future).

## Design

### Header value shape — the key decision

A bearer token obtained at `/login` lives in a **`Signal`** (it changes after
login), so a *static* `Map[String, String]` captured at construction time would
go stale. Two options:

- **A. Static `Map[String, String]`** (`= Map()` default). Simple; correct for
  fixed headers (`Content-Type`). Wrong for a token that arrives after the
  component mounts — the captured map won't update.
- **B. Reactive headers via a `Signal[String]`** holding a JSON object, read at
  request time. Always current; the click/GET handler does
  `JSON.parse(headersSignal.value)`. Slightly more ceremony at the call site.

**Recommendation:** support **both** — an overload/optional `headers: Map[String,
String] = Map()` for the static case, plus an optional `headersSignal:
Signal[String] = ...` (empty = unused) read at request time. The request merges
static headers with the parsed signal (signal wins). v1 may ship **B only** if
we want one mechanism, since the token case is the motivating one; the spec
leaves the final call to implementation, but the **request-time read** is
mandatory (no construction-time capture of mutable auth state).

### Signature sketch

```scala
extern def fetchUrlSignal(name: String, url: String, refreshTick: Signal[Int],
                          headers: Signal[String] = emptyHeaders): Signal[String]
extern def fetchAction(method: String, url: String, body: Signal[String],
                       onSuccessTick: Signal[Int],
                       headers: Signal[String] = emptyHeaders): EventHandler
// fetchActionClear, fetchTableView: same trailing `headers` param.
```

`headers` is a JSON object string, e.g. `{"Authorization":"Bearer abc"}`. A
small helper `headerJson(pairs: (String,String)*): String` (or a
`Signal`-producing `authHeader(tokenSignal)`) keeps call sites readable.

### JS runtime changes (`JsRuntimeSignals`)

- **Descriptors** (`_ssc_ui_fetchAction`/`Clear`, `_ssc_ui_fetchUrlSignal`,
  `_ssc_ui_fetchTableView`) carry a `headersSig` (signal id).
- **`renderBody`** emits a `data-ssc-fetch-headers="<sigId>"` attribute next to
  the existing `data-ssc-fetch-{method,url,body,tick}`.
- **mount click handler** (currently `fetch(url, {method, body})`): read the
  headers signal at click time, `JSON.parse`, and pass
  `{ method, body, headers }`. Same for the table delete button.
- **`fetchUrlSignal` GET (new):** lower a marker (e.g.
  `data-ssc-fetch-get-url` / `-headers` / `-tick` on the text node, or register
  the (signal, url, headersSig, tick) tuple in a mount list). On mount: `fetch(url,
  { headers })` → `r.text()` → `_set(signalId, text)`; subscribe to `tick` to
  re-fetch. Mirror the existing `data-ssc-fetch-table` pattern.

### Interpreter (`FrontendIntrinsics`)

The primitives build descriptor/`EventHandler` values; the snapshot path does
not perform IO. Intrinsics must accept the new trailing arg (default empty) and
thread it into the descriptor so JVM/JS lowering sees it. No network in the
interpreter.

### JVM (`JvmGen`)

Thread `headers` through wherever these primitives are emitted, matching the JS
attribute/`fetch` shape so a JVM-served page behaves identically.

## Security

- Never log header values (tokens). Redact in any debug/trace output.
- Document that headers are sent from the browser: only put a token there that
  the user is authorized to hold; this is a client, not a secret store.
- Note CORS: the API must allow the `Authorization` header
  (`Access-Control-Allow-Headers`) for cross-origin use.

## Testing

- **Interpreter:** `ssc run` of a component using each primitive with `headers`
  still produces the snapshot (no regression); intrinsic accepts the arg.
- **JS:** `ssc emit-js` golden — the emitted bundle contains the header wiring
  (the `headers` is read and passed into `fetch`), and `fetchUrlSignal` now
  emits a real GET. `node --check` the bundle.
- **Cross-backend:** extend the emit parity tests so JS and JVM agree.
- **Back-compat:** existing fetch tests pass unchanged (default empty headers).

## Phasing

- **v1 — `fetchAction`/`fetchActionClear` headers.** Unblocks authed *mutations*
  (busi's "post entry" with a bearer token). Smallest coherent slice; the
  click-`fetch` already exists.
- **v2 — `fetchUrlSignal` GET + headers** (and `fetchTableView` headers).
  Unblocks authed *live reads* (the dashboard's balance display).

## Future

- A component-scoped "default headers" / request interceptor so a token is set
  once rather than threaded into every call.
- `credentials`/cookie support; response status/header access from `.ssc`.
</content>
