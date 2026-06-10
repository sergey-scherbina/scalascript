# `fetchCaptureAction` — POST-and-capture-response-into-a-signal

**Status**: LANDED 2026-06-10. Requested by busi (durable-auth login, rozum).

## 1  Motivation

The reactive fetch primitives split cleanly into "read" and "write" — but neither
captures a **write** response:

| primitive | trigger | body | response |
|---|---|---|---|
| `fetchUrlSignal` / `fetchJsonValue` | mount + tick | — (GET) | → signal |
| `fetchAction` / `fetchJsonAction` | click | sent | **discarded** (only bumps tick) |

So a SPA has no way to read the body a `POST` returns — e.g. the bearer token
minted by `POST /login`. busi could not flip its dashboard from dev-seeded auth
(`Bearer tok-owner`) to durable auth without it.

## 2  Surface

`runtime/std/ui/primitives.ssc`:

```scalascript
extern def fetchCaptureAction(method: String, url: String, body: Signal[String],
                              into: Signal[String], onSuccessTick: Signal[Int],
                              headers: Signal[String] = emptyHeaders): EventHandler
```

On click it `fetch`es `url` with `body`; on a **2xx** it writes the raw response
body into `into`, then bumps `onSuccessTick`. A non-2xx leaves `into` and the
tick untouched (a failed POST must not look like a success).

`runtime/std/ui/fetch-json.ssc` adds the JSON sugar (composition, no new runtime):

```scalascript
// structured-builder body + capture, mirrors fetchJsonAction
def fetchJsonCaptureAction(method, url, body: () => String,
                           into: Signal[String], tick, headers): EventHandler
// read a captured signal as a navigable JsonValue (reactive thunk)
def jsonOf(into: Signal[String]): () => JsonValue
```

Login flow:

```scalascript
val resp  = signal("loginResp", "")
val onLogin = fetchJsonCaptureAction("POST", "/login",
  () => jObj([jField("user", jStr(user())), jField("pass", jStr(pass()))]),
  resp, tick, emptyHeaders)
val token = computedSignal(() => jsonOf(resp)().get("token").asString)
// feed `token` into the Authorization header signal for all subsequent fetches
```

## 3  Implementation

Capture is a **browser-runtime** behaviour. `JsRuntimeSignals`:
`_ssc_ui_fetchCaptureAction` builds a `_FetchAction` carrying an `into` signal;
render emits `data-ssc-fetch-into="<id>"`; the mount click handler captures the
2xx body into that signal (`_set(intoId, text)`), gated on `r.ok`. `JsGen`
`detectCapabilities` pulls in the Signals runtime on `CaptureAction`.

Non-browser paths (interpreter `FetchIntrinsics`, `JvmRuntimeUiPrimitives`)
resolve the symbol and **degrade to a plain fetch** — capturing into a client
signal has no meaning under server-side / native render. emit-spa is the
supported target (busi's path).

## 4  Verify

- [x] emit-spa bundle defines `_ssc_ui_fetchCaptureAction`, emits
  `data-ssc-fetch-into`, and the click handler captures the 2xx body into the
  signal (gated on `r.ok`); whole bundle `node --check`s
  (`JsGenStdImportTest`).
- [x] `fetchJsonCaptureAction` / `jsonOf` lower onto the capture extern (sugar).
- [x] interpreter + JvmGen resolve the symbol (degrade), no regression
  (`FetchPluginInterpreterTest`, `JvmGenSwingRuntimeTest`).
