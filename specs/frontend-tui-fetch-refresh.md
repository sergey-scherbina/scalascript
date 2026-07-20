# Terminal managed-fetch refresh

Status: complete (2026-07-20, `6c6fcf21b`)
Owner: `frontend-tui-fetch-refresh`
Affected backend: static `frontend/tui` (`TuiEmitter` → ratatui crate)

## Goal

The terminal frontend must honor the same managed-GET dependency carried by
`FetchUrlSignal(id, url, tickId)` as the browser frontends: load once before the
first frame, then load again whenever the refresh tick changes. This enables a
single Tk view containing `DataTable.Remote` and an incrementing refresh button
to remain live when emitted for either React or ratatui.

## Baseline defect

`TuiEmitter.collectFetches` currently records only `id -> url`. Generated Rust
calls `bootstrap(&mut signals)` once, before entering the event loop. Although
`IncrementSignal(tick)` updates the runtime signal store, no generated code
retains `tickId`, observes that change, or repeats the request. Redraw therefore
shows stale bootstrap data.

This spec applies to the `FrontendFrameworkSpi.emitNative` TUI path. The separate
dynamic `ssc tui`/RustCodeWalk path is outside this change.

## Required behavior

For every reachable `FetchUrlSignal`, the emitter retains:

- destination signal id;
- resolved GET URL;
- refresh signal id (`tickId`).

The emitted crate shall:

1. perform one GET for every binding during bootstrap;
2. snapshot each binding's current integer tick immediately after bootstrap;
3. before every subsequent interactive frame, compare the current tick with the
   snapshot;
4. perform one GET only for each binding whose tick differs, then record the new
   tick;
5. replace the destination signal only after a successful response-body read;
6. retain the last-good destination value when transport or body reading fails.

Several bindings may share a tick and must each refresh once. Several loop
iterations with an unchanged tick must issue no requests. Tick comparison uses
the TUI runtime's existing `sig_int` coercion, so missing or invalid values are
observed as zero consistently with other terminal signal consumers.

## Generated-runtime shape

Emitter metadata becomes an ordered `id -> FetchInfo(url, tickId)` map. Generated
Rust owns an ordered-by-emission (map storage order is not normative) set of last
observed ticks keyed by fetch destination id and exposes three internal steps:

- `bootstrap(signals)` — initial loads;
- `initial_fetch_ticks(signals)` — capture the post-bootstrap baseline;
- `refresh_fetches(signals, observedTicks)` — compare, conditionally GET, and
  advance observations.

The interactive loop initializes both stores and invokes `refresh_fetches`
before drawing each frame. The snapshot path remains a single bootstrap plus
render; it does not invent a refresh event.

When no fetch binding exists, these helpers are no-ops, Cargo.toml has no `ureq`
dependency, and existing generated source behavior remains unchanged.

## Verification

Fast emitter tests must prove that fetch metadata emits the tick comparison and
that no-fetch output remains dependency-free.

The cargo integration test must use a local HTTP server and one generated view
containing:

- a `DataTable.Remote` whose first response contains an initial row;
- a real `ReactiveSignal[Int]` refresh tick;
- a button with `EventHandler.IncrementSignal(tick)`;
- a second server response containing a distinct row.

Inside the emitted Rust test, bootstrap must render/read the initial body,
activating the button must change the tick, and `refresh_fetches` must replace it
with the second body. The test must run without a TTY and must fail if the second
HTTP request is absent.

Required gate: `scripts/sbtc 'frontendTui/test'`, including the cargo smoke when
`cargo` is available.

## Result

Implemented in `TuiEmitter` with ordered `FetchInfo(url, tickId)` metadata and
generated `initial_fetch_ticks` / `refresh_fetches` helpers. The cargo regression
also verifies unchanged-tick no-ops and last-good retention after HTTP 500.
`frontendTui/test` passes 36/36; a staged CLI emits and runs rozum's one-source
message-list as both React and ratatui.

## Non-goals

- background polling without a tick change;
- asynchronous/non-blocking HTTP;
- reactive URL or header support not already present in the static TUI emitter;
- changes to browser frontend semantics;
- unifying the static TUI emitter with RustCodeWalk.
