# Terminal managed-fetch headers

Status: spec (2026-08-04)
Owner: `tui-fetch-headers`
Affected backend: static `frontend/tui` (`TuiEmitter` → ratatui crate)
Reported by: rozum — `INBOX.md` entry `tui-fetch-headers` (`reported-at: 2026-08-04`)

Sibling of [`frontend-tui-fetch-refresh.md`](frontend-tui-fetch-refresh.md), which established the
managed-GET contract this extends. Same acceptance shape, by the reporter's request.

## Goal

`FetchUrlSignal` already carries a headers signal. The terminal frontend must honour it, so that one
`.ssc` source emitting to both `emit-spa --frontend react` and the ratatui target authenticates on
**both**.

## Baseline defect

The model has the field and the emitter drops it:

```scala
sealed class FetchUrlSignal(id2, val fetchUrl, val tickId, val headersId: Option[String] = None)
```

`TuiEmitter.collectFetches` records `FetchInfo(f.fetchUrl, f.tickId)` — `headersId` is discarded —
and the emitted helper is

```rust
fn fetch_text(url: &str) -> Option<String> {
    match ureq::get(url).call() { Ok(resp) => resp.into_string().ok(), Err(_) => None }
}
```

So a source that authenticates correctly on the web emits a terminal binary that sends a bare GET.

**Why this was invisible until now, which is the part worth keeping.** The reporter says it plainly:
their daemon requires HTTP Basic on *every* route, and the dual-target proof so far — this repo's
own `frontend-tui-fetch-refresh` gate and their PoC smoke — has only ever run against **fixtures with
no auth**. The gap did not appear until a generated client was pointed at a production endpoint. A
gate that only ever exercises unauthenticated fixtures cannot see a dropped credential, so the
verification below is written to fail without one.

## Required behavior

For every reachable `FetchUrlSignal`, the emitter additionally retains the headers signal id when
present.

The emitted crate shall, for each fetch:

1. read the headers signal **at fetch time**, not at emit time — its value is a JSON object of
   header name → value, as documented in `std/ui/primitives.ssc`;
2. set every key of that object as a request header on the GET;
3. send the request unchanged when the signal is absent, empty, or not a JSON object — a malformed
   headers value must not turn a working unauthenticated fetch into a failure;
4. apply headers on the refresh path exactly as on bootstrap, so a re-read after a tick change is
   still authenticated.

Header values are applied generically for any key. `Authorization` is the reporter's case, not a
special case.

## Generated-runtime shape

`FetchInfo` becomes `(url, tickId, headersId: Option[String])`. `fetch_text` takes the resolved
header pairs; `load_fetch` reads the headers signal out of the signal store and passes them.

**`serde_json` becomes a conditional dependency of a second feature.** Today Cargo.toml adds it only
when `hasRemoteTable`. A fetch carrying headers needs it too, so the condition becomes
`hasRemoteTable || any fetch has headers` — otherwise the emitted crate references
`serde_json` and does not compile. This is the failure mode most likely to be missed by an emitter
unit test that only inspects strings, so the cargo test below must build.

## Verification

Fast emitter tests must prove that a fetch with a headers signal emits the header-setting code and
the `serde_json` dependency, and that a fetch **without** one emits neither — the second half is
what keeps the no-header path dependency-free.

The cargo integration test must use a local HTTP server that answers:

- `401` when the expected header is absent;
- `200` with a distinguishable body when it is present.

The generated view binds a `FetchUrlSignal` whose headers signal supplies that header. The test must
assert the destination signal holds the `200` body — i.e. it **fails if headers are dropped**, which
is the current behaviour. A companion case asserts a fetch with no headers signal still reads a
plain endpoint.

Required gate: `scripts/sbtc 'frontendTui/test'`, including the cargo smoke when `cargo` is
available.

## Non-goals

- POST — separate report `tui-fetch-post`, separate spec.
- A runtime-chosen URL — separate report `tui-fetch-url-signal`.
- Streaming, retries, redirect policy, or TLS configuration.
- Reading credentials from the environment: the headers signal is the source of truth, and where its
  value comes from is the application's business.

## Result

*(filled in on completion)*
