# `fetchActionTo` — reactive-URL fetch action

## Problem

`std/ui/primitives.fetchAction(method, url: String, body, onSuccessTick, headers)`
takes a **static** `url`. For a path-id endpoint whose id is a runtime selection —
e.g. `POST /documents/<selectedDocId>/submit` — the only way to build the URL with
`fetchAction` is to interpolate a signal at *render* time:

```
fetchAction("POST", baseUrl + "/documents/" + selectedSig() + "/submit", body, tick)
```

`selectedSig()` is read **once when the View is built**, baking in the then-current
(usually empty) selection → a static `…/documents//submit` that never updates. The
body already accepts a `Signal[String]` resolved at click; the URL did not.

Found building busi `web/peer.ssc` (document Submit/Approve/Reject) — see the busi
`peer-reactive-url-action` task.

## Design

A sibling primitive whose URL is a `Signal[String]` resolved at click time:

```
extern def fetchActionTo(method: String, urlSig: Signal[String], body: Signal[String],
                         onSuccessTick: Signal[Int], headers: Signal[String] = emptyHeaders): EventHandler
```

Usage (the URL stays correct as the selection changes):

```
val docUrlSig = computedSignal(() => baseUrl + "/documents/" + selectedDocSig() + "/submit")
button("Submit", fetchActionTo("POST", docUrlSig, emptyBody, tick, authHdr))
```

### react / JS SPA backend (the reactive target)

- `_ssc_ui_fetchActionTo` builds `{_type:'_FetchAction', method, urlSig, body, tick, headers}`
  (a regular fetch-action descriptor carrying `urlSig` instead of a static `url`).
- `_ssc_ui_renderBody` collects `urlSig` (so its `_sv` entry is kept fresh by the
  computed→`_sv` bridge) and emits `data-ssc-fetch-url-sig="<id>"` (with an empty
  `data-ssc-fetch-url=""` so the existing `[data-ssc-fetch-url]` click binding still
  matches).
- The click handler resolves the URL from `_sv[urlSigId]` at click time when the
  url-sig attr is present, else uses the static `data-ssc-fetch-url`.

No change to the shared `EventHandler` ADT or any frontend emitter — the JS SPA path
uses runtime descriptor objects, not the Scala ADT.

### interpreter (INT) and JVM backends

These are headless (no SPA click-dispatch loop), so `fetchActionTo` **snapshots** the
`urlSig`'s current value into a regular `EventHandler.FetchAction(method, urlSig(), …)`.
This keeps the descriptor buildable (so `lower`/render works) without widening the
`EventHandler.FetchAction` case (which is positionally pattern-matched across ~30 sites
in the javafx/vue/swing/swiftui/jvm frontends). A native/headless renderer that later
needs a live reactive URL would carry the signal explicitly; out of scope here.

## Verify

`FetchActionToUrlTest` runs the real `JsRuntimeSignals` runtime headless (document/
fetch shims): a field drives a computed URL signal; after typing `42` and clicking,
the captured fetch URL is `/documents/42/submit` (not the baked-in `/documents//submit`).
Existing `fetchAction` unchanged (47 JS/content tests + `SpaComputedBodyBridgeTest` green).
