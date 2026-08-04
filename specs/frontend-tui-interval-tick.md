# Terminal managed-fetch: a tick that advances on its own

Status: **implemented** (landed 2026-08-04, `a81ef559f`) — `tickMs` on the model + a wall clock in the loop, gated by a cargo probe that refetches unattended
Owner: `tui-interval-tick`
Affected: `frontend/core` (model), `v1/runtime/std/fetch-plugin` (site), static `frontend/tui`
Found by: rozum — the last gap between a generated meeting client and retiring the hand-written one
(`rozum/docs/specs/ucc-meetings-in-tk.md`, parity ledger row 4)

Fifth of this reporter's terminal-fetch series, after `headers`, `url-signal`, `post`,
`table-selection` and `remote-table-height`.

## Goal

`intervalTick("t", 5000)` — an Int signal that advances on its own — must work on the terminal
target, so a fetch bound to it re-reads without a keypress. That is the difference between a client
that shows a message when it arrives and one that shows it when you press a button.

## Baseline defect

`IntervalTick` is in the core model (`Primitives.scala`), the JS runtime implements it with
`setInterval`, and the terminal emitter contains **zero references to it**. A source that
auto-polls on the web emits a terminal binary that never refreshes by itself.

There is a second, quieter reason it cannot work today: **the emitter never sees the tick object.**
`FetchUrlSignal` stores `tickId: String`, not the signal, so even the knowledge "this tick is an
interval, and its period is 5000 ms" does not reach the emitter. The intrinsic has the object and
throws that away.

## Required behavior

1. `FetchUrlSignal` carries the tick's period when the tick is an `IntervalTick` — additive, beside
   the `headersId` and `urlId` the same reporter's earlier work added.
2. The emitted event loop advances each such tick on wall-clock time and the existing
   `refresh_fetches` does the rest: it already re-fetches when a tick changes, so auto-polling needs
   no new fetch machinery.
3. Ticks are advanced **before** the refresh in the same iteration, so a due tick is acted on in the
   frame that notices it rather than the next one.
4. Two fetches sharing one interval tick advance it **once**, not twice — a shared tick is one
   clock, and double-bumping would double the poll rate.
5. A non-positive period is ignored rather than turned into a busy loop.
6. A source with no interval tick emits exactly what it emits today: no clock, no bookkeeping.

## Resolution, and the honest limit

The loop polls events with a 100 ms timeout, so a tick's real resolution is ~100 ms and its period
is a floor, not a guarantee: a 5000 ms tick fires at the first iteration at or after 5000 ms. That
is right for polling and wrong for anything that needs a deadline; the doc comment should say so
rather than let a reader assume precision the loop cannot offer.

Elapsed time is measured per tick from its LAST fire, not from process start — otherwise drift
accumulates into the period rather than being absorbed.

## Verification

Emitter tests: a fetch bound to an interval tick emits the clock and the bump for that id; two
fetches sharing one tick emit ONE bump; a fetch with a plain tick emits neither (the negative half).

Cargo gate, in `TuiFetchCargoTest`: a crate whose fetch is bound to a short interval, driven by a
local HTTP server that returns a different body each time. Call the bump helper with the clock past
due, run the refresh, and assert the body CHANGED with no key pressed — that is the requirement.
Then call it again immediately and assert the body did NOT change, which is the half that proves the
tick has a period rather than firing every frame.

## Non-goals

- `setInterval` semantics for anything but a fetch tick (animation, timers in handlers).
- Sub-100 ms resolution; the loop's poll interval bounds it.
- Pausing the clock when the app is unfocused — a terminal app has no such concept here.
