# Terminal DataTable: a row can be chosen

Status: spec (2026-08-04)
Owner: `tui-table-selection`
Affected: static `frontend/tui` (`TuiEmitter`) only — the model already carries everything needed
Reported by: rozum — `INBOX.md` entry `tui-table-selection` (`reported-at: 2026-08-04`)

Fourth and last of the same reporter's terminal-fetch series, after
[`frontend-tui-fetch-headers.md`](frontend-tui-fetch-headers.md),
[`frontend-tui-fetch-url-signal.md`](frontend-tui-fetch-url-signal.md) and
[`frontend-tui-fetch-post.md`](frontend-tui-fetch-post.md).

## Goal

A `DataTable` on the terminal target can be focused, its rows walked, and a row **chosen** — writing
a field of that row into a bound signal. Combined with `fetchUrlSignalTo`, that is a picker: choose
a room, the transcript below retargets.

## Baseline defect

`View.DataTable(source, columns, actions, style, rowKeyPath)` — the emitter destructures `actions`
as `_` at both match sites, and emits no focusable and no selection state. A table is a picture of
data: readable, not actionable.

Nothing is missing in the model. `RowActionDef.RowLink(label, signal, fieldPath)` already means
"choosing this row writes `row[fieldPath]` into `signal`", and the web target lowers it through
`DataTableLowering` into a per-row button. Only the terminal emitter ignores it.

## Required behavior

1. A `DataTable` whose `actions` contain a `RowLink` becomes **one focusable** — the table as a
   whole, not one focusable per row. Row count is a runtime property of fetched data; the focus ring
   is emitted at build time, so per-row focusables cannot exist.
2. While that table is focused, `Up`/`Down` move a **row cursor** instead of moving focus. `Tab` and
   `BackTab` still move focus. This is the only defensible split: `Tab` is the canonical focus key,
   and arrows belong to the focused widget. Apps with no selectable table are unaffected, and the
   negative case is gated.
3. The cursor is clamped to the row count each frame — data can shrink under it (a refetch), and a
   cursor past the end must not panic or select nothing.
4. `Enter` writes the cursor row's `fieldPath` value into the bound signal. When there are no rows,
   `Enter` does nothing and the signal is left alone: a picker over an empty list must not blank a
   selection that already exists.
5. The focused table shows which row is current. An unfocused table renders as before.
6. A table with no `RowLink` emits exactly what it does today — no focusable, no cursor, no
   selection code.

## Generated-runtime shape

`Focusable` gains an optional cursor descriptor carrying the cursor signal id, the fetch signal id,
the rows path, the field path and the target signal id — everything both the movement and the write
need, so neither has to re-derive it.

Three small helpers: `is_table(focus)` (mirrors the existing `is_text_input`), `move_row(focus,
signals, delta)`, and `row_field(json, rows_path, index, field)`. The cursor lives in the ordinary
signal store as an `Int`, so it needs no new state plumbing and is visible to the generated
self-tests.

Rendering uses `render_stateful_widget` with a `TableState` selected at the cursor, and
`row_highlight_style` — a real ratatui selection rather than a marker glued into a cell, so it
cannot disagree with the value `Enter` writes.

## Verification

Emitter tests: a table with a `RowLink` emits the focusable, the cursor movement and the write; a
table without one emits none of it (the negative half keeps every existing app byte-identical).

Cargo gate, in `TuiFetchCargoTest` beside the other fetch gates: build a crate whose table is fed by
a local HTTP server, move the cursor, activate, and assert the bound signal holds the chosen row's
field — then, with `fetchUrlSignalTo` bound to that same signal, assert the dependent fetch
retargeted. That last half is the actual product requirement; asserting the signal alone would pass
while the picker still did nothing visible.

## Non-goals

- `RowDelete` / `RowPost` row actions — same mechanism, but they are writes with their own
  confirmation questions, and no reporter needs them on this target yet.
- Multi-select, sorting, scrolling a cursor beyond the visible window.
- Mouse.
