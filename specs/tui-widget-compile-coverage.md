# Compile coverage for the terminal interactive widgets

Status: spec (2026-08-04)
Owner: `tui-widget-compile-coverage`
Affected backend: static `frontend/tui` (`TuiEmitter` → ratatui crate)
Defect: [`BUGS.md`](BUGS.md) `tui-interactive-widgets-have-no-compile-coverage`

## Goal

Every family of code the TUI emitter can produce must be **compiled by a test**, not only string-
matched. Today one family is not.

## Baseline defect

`TuiCargoSmokeTest` builds six shapes: the base crate, `DataTable + TabBar`, a fetch-bound signal, a
headers fetch, `DataTable.Remote`, and a refresh tick.

Slice 3 — `TextInput`, `Button`, `Toggle`, the focus ring, Tab/arrow traversal, `Enter` activation,
typed-char editing — is compiled by **none** of them. Its emissions (`text_input_display`,
`toggle_text`, the focusable table, the event-handler arms) are asserted only by string matching in
`TuiEmitterTest`, and **a string test cannot see Rust that does not compile**.

**This is not a hypothetical class.** The `tui-fetch-headers` work hit it one layer over:
`load_fetch` borrows the signal store mutably while `fetch_headers` borrows it immutably, so the
natural inline call is a borrow-checker error. It was caught only because the fetch path has a cargo
test. The widget emissions touch the same signal store from the same kind of helper, and
`tui-fetch-post` — which must write a signal from an event handler — walks into exactly that shape.

## Required behavior

One cargo smoke over a view containing a `TextInput`, a `Button` and a `Toggle` together, asserting:

1. the emitted crate **builds** — the property no string test can assert;
2. the initial frame renders the widgets (placeholder text, button label, unchecked box);
3. activating the button changes what the next frame renders, i.e. the event-handler arm and the
   signal write are exercised rather than merely emitted.

Point 3 matters because a crate can compile with an event arm that is never reachable; rendering a
changed frame is what proves the focus ring and activation path are wired.

## Verification

The existing `snapshotViaCargo` harness already builds and renders headlessly; this adds a case, not
machinery. The test is `assume(cargoAvailable)`-gated like its siblings, so a machine without cargo
skips rather than fails.

Required gate: `scripts/sbtc 'frontendTui/test'` with `cargo` available.

## Non-goals

- Covering every widget permutation. One view holding all three plus an activation is the cheapest
  thing that would have caught the borrow class; a matrix is not.
- Testing terminal input handling itself (crossterm key decoding) — the harness drives activation
  directly, as the existing smokes do.

## Result

Landed 2026-08-04. One cargo smoke over a view holding `SignalText` + `Button` + `TextInput` +
`Toggle`, run with `runTests = true` so the crate's own generated event tests execute — that is what
covers activation, since a crate can compile with an event arm that is never reachable.
`frontendTui/test` 41/41.

**The gate is proven live, not assumed.** Injecting a deliberate type error into `toggle_text` — a
helper only the widget path emits — makes exactly this test fail with `cargo run failed (exit 101):
error[E0308]: mismatched types`. Without that check the test would have been a green line proving
nothing, which is the failure mode this repo keeps paying for: a coverage test that passes because
nothing is broken looks identical to one that cannot see breakage.
