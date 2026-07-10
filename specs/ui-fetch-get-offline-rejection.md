# Managed UI GET offline failure semantics

## Overview

`fetchUrlSignal` and `fetchUrlSignalTo` are long-lived browser data bindings.
An unavailable network is a normal state for an offline-capable SPA, so a
rejected managed GET must not escape as an unhandled promise rejection. The
binding retains its last-good value and remains ready for a later tick- or
URL-driven refresh.

## Interface

The public `.ssc` signatures are unchanged:

```scalascript
fetchUrlSignal(name, url, refreshTick, headers)
fetchUrlSignalTo(name, urlSignal, refreshTick, headers)
```

This specification tightens only the generated custom-SPA runtime contract:

- a fulfilled response still contributes its text body to the signal,
  including the existing treatment of non-2xx responses;
- a rejected `fetch` or response-body read keeps the signal unchanged and is
  consumed by the managed binding;
- the rejection does not disable subscriptions, so the next refresh may
  succeed normally.

## Behavior

- [x] A rejected initial managed GET produces no browser/Node unhandled promise
      rejection and preserves the signal's initial value.
- [x] After a rejected GET, a later tick-driven successful GET updates the same
      signal with the response text.
- [x] `fetchUrlSignalTo` uses the same rejection semantics while retaining its
      URL-signal-driven refresh behavior.
- [x] Existing fulfilled-response and HTTP-status behavior is unchanged.
- [x] A real emitted custom SPA contains the managed rejection boundary; the
      fix is not an application-specific patch to generated HTML.

## Out of scope

- Automatic retries, backoff, toast messages, or a new loading/error state.
- Changing `fetchAction*` mutation semantics.
- Treating non-2xx HTTP responses as network failures.
- Unmount/disposal of hidden view bindings.

## Design

The shared `_mountFetchGet` helper in the production browser signal runtime
owns both fixed-URL and signal-URL bindings. Its promise chain consumes a
rejection after response-text conversion and intentionally performs no signal
write on that path. This leaves the previous value intact and keeps the tick
and URL subscriptions installed.

The regression runs the real generated signals runtime under Node with a
minimal DOM, forces the first `fetch` to reject, records process-level
`unhandledRejection`, then increments the binding tick and fulfills the second
request. An assembled `emit-spa --frontend custom` check guards the distributed
runtime resource used by real applications.

## Decisions

- **Preserve last-good data on transport rejection** — an offline refresh is
  absence of newer data, not evidence that the previous data is invalid.
  Rejected: clearing the signal (destroys useful offline state).
- **Consume only promise rejection without inventing UI state** — this is a
  backward-compatible runtime fix. Rejected: adding an implicit error signal or
  console error (changes the public model and still pollutes expected offline
  operation).
- **Keep HTTP response semantics stable** — the current primitive is a text
  binding, not an HTTP policy layer. Rejected: coupling this bug fix to `ok`
  status validation.

## Results

The real `JsRuntimeSignals` Node harness covers a rejected transport, a
rejected response-body read, retained `"last good"` state, a subsequent
successful tick, and a fulfilled 503 text response with zero process-level
unhandled rejections. `FetchUrlSignalOfflineTest` plus the existing
`FetchUrlSignalToTest` pass 2/2. `installBin` produced the assembled CLI; its
custom-SPA emission for `examples/frontend/local-first/local-first.ssc` was
449,026 bytes and contained the managed rejection boundary. Focused conformance
(`std-ui-jobpanel`, `tkv2-busi-home`, `tkv2-offline`) passed 3/3 on both INT and
JS lanes.
