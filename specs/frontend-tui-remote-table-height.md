# Terminal layout: a remote table needs room to have rows

Status: spec (2026-08-04)
Owner: `tui-remote-table-height`
Affected: static `frontend/tui` (`TuiEmitter`) only
Found by: rozum, building the room picker `tui-table-selection` made possible

## The defect

A vertical `Column` emits one `Constraint::Length(measureHeight(child))` per child, and

```scala
case View.DataTable(source, columns, _, _, _) => source match
  case TableDataSource.StaticRows(rows) => rows.size + 1
  case _                                => 1     // ← every REMOTE table
```

So a fetched table inside a column is given **one line**: the header renders and not a single row
does. The data is there — the fetch runs, `fetch_rows` returns the rows — and the widget is simply
not tall enough to show any of them.

This is worse than a wrong number, because everything around it looks healthy: the table draws, its
column titles appear, the JSON is in the store, and the only symptom is an empty list. That is how
it was found — a room picker with two rooms in it rendered as a header and nothing else.

`1` was not an unreasonable guess: a remote table's row count is genuinely unknown when the layout
is emitted. The mistake is answering an unknowable question with a *fixed* number instead of a
flexible constraint.

## Required behavior

1. In a vertical layout, a child whose height cannot be known at emit time takes the **remaining**
   space rather than a fixed line count: `Constraint::Min(_)`, not `Constraint::Length(1)`.
2. A remote `DataTable` is such a child. Everything else keeps the exact constraint it has today —
   fixed-height children must not start flexing, or every existing layout shifts.
3. A remote table gets a floor of `Min(3)`: a header plus at least two rows, so a picker is usable
   in a cramped column instead of technically-correct-and-empty.
4. A `StaticRows` table is unchanged — its height IS knowable and is already computed.

## Why `Min` and not `Fill`

`Fill` distributes leftovers by weight; `Min` states a floor and lets the layout give what is left.
With one flexible child among fixed ones — the common shape here — they behave the same, and `Min`
degrades more predictably when the terminal is too short: the fixed children keep their sizes and
the table is the one that shrinks, which is the right thing to lose first.

## Verification

Emitter tests:

- a column containing a remote table emits `Constraint::Min(` for that child and `Length(` for its
  siblings, in the right order;
- a column with no remote table emits exactly the constraints it emits today (the negative half,
  which is what protects every existing layout);
- a static-rows table still measures `rows + 1`.

Cargo gate, in `TuiFetchCargoTest`: render a column of [heading, remote table with two rows, button]
into a snapshot and assert **both row values appear**. A string assertion on the constraint proves
the emitter changed; only rendering proves the rows became visible, which is the actual complaint.

## Non-goals

- Scrolling a table longer than its area.
- Horizontal layouts — a table's WIDTH is already ratio-based.
- Guessing a remote table's row count at emit time. It is unknowable; that is the premise.
