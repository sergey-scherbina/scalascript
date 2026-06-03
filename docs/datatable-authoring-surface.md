# DataTable Authoring Surface

Status: Phase 1 path validation landed, 2026-06-03.

This spec records the post-`FetchTable` public contract for authoring
fetch-backed tables from `.ssc` code and the follow-up work that can make
`DataTable` a more general table component over time.

## Goals

- Keep the landed semantic architecture: `View.DataTable(signal, columns,
  actions)` is the IR node; web backends lower through `DataTableLowering`,
  while native desktop backends may render the node directly.
- Make the `.ssc` authoring surface match the actual runtime contract:
  `fetchUrlSignal` creates the remote row source, and `dataTable` consumes that
  signal plus explicit column and row-action descriptors.
- Preserve the `FetchTable` removal. Do not reintroduce a monolithic
  fetch-table IR node or a second implementation path.
- Record the next steps toward a more universal table without blocking the
  small API cleanup.

## Non-goals

- No new backend rendering behavior in Phase 0.
- No compatibility overload for `dataTable(fetchUrl = ..., tick = ...)` in
  Phase 0. The explicit signal form is the current source of truth.
- No generic in-memory, paged, or typed-model-backed data source in Phase 0.
- No custom cell renderer, sorting, filtering, or pagination contract in
  Phase 0.

## Current contract

Low-level primitive layer:

```scalascript
fetchUrlSignal(name, url, refreshTick, headers = emptyHeaders): Signal[String]
fieldColumn(title, fieldPath, align = "", editAction = null): Any
rowDeleteAction(url, idField, tick, headers = emptyHeaders): Any
rowPostAction(label, method, url, bodyField, tick, headers = emptyHeaders): Any
rowLinkAction(label, signal, fieldPath): Any
rowEditAction(method, url, idField, tick, headers = emptyHeaders): Any
dataTableView(signal, columns, actions): View
```

Toolkit authoring layer:

```scalascript
val tick = signal[Int]("tick", 0)
val rows = fetchUrlSignal("rows", "/api/rows", tick)

val table = dataTable(
  rows,
  [fcol("Name", "name", editable = rowEdit("PATCH", "/api/rows", "id", tick))],
  [rowDelete("/api/rows/delete", "id", tick)]
)
```

`dataTable` is intentionally a table constructor, not a fetch constructor. The
fetch policy, headers, and refresh trigger stay on the signal.

## Architecture

`runtime/std/ui/data.ssc` owns the ergonomic constructors:

- `fcol` wraps `fieldColumn`.
- `rowDelete`, `rowPost`, `rowLink`, and `rowEdit` wrap row action primitives.
- `dataTable(signal, columns, actions)` returns `DataTableNode`.

`runtime/std/ui/lower.ssc` owns toolkit lowering:

- `DataTableNode(signal, columns, actions)` lowers to `dataTableView`.

`runtime/std/fetch-plugin` owns interpreter intrinsic construction:

- primitive constructors produce `FieldColumnDef`, `RowActionDef`, and
  `View.DataTable` foreign values.

Frontend backends consume the semantic IR:

- React and Vue render `DataTableLowering.lower(dt)`.
- Solid and Custom have dedicated handling for their DOM-update model.
- Swing and JavaFX may render native desktop table controls directly.

## Migration

Existing examples or docs that still use the old named form:

```scalascript
dataTable(fetchUrl = "/api/items", tick = tick, columns = cols, actions = acts)
```

must be rewritten to:

```scalascript
val rows = fetchUrlSignal("items", "/api/items", tick)
dataTable(rows, cols, acts)
```

The explicit signal form avoids hiding headers, refresh semantics, and future
data-source choices behind a convenience overload.

## Phases

### Phase 0 - Authoring surface cleanup

- Import `rowEditAction` into `runtime/std/ui/data.ssc` so exported `rowEdit`
  is callable through the public toolkit module.
- Accept a null/default edit action in the interpreter `fieldColumn` intrinsic
  because `fcol` always forwards its `editable` default.
- Accept null/default headers in interpreter row-action intrinsics because
  std/ui action helpers forward their `headers` default. In the interpreter,
  bare `emptyHeaders` can arrive as `Value.NativeFnV("emptyHeaders", ...)`
  rather than a materialized `ReactiveSignal`; row-action intrinsics treat that
  sentinel as no headers.
- Update examples and user-facing docs to the explicit signal contract.
- Add executable coverage for editable `FieldColumnDef` construction plus a
  std/ui source guard that prevents `rowEditAction` from disappearing from
  `data.ssc` imports. A full explicit-import regression should wait for the
  package-namespaced import fix tracked elsewhere in the conformance queue.

### Phase 1 - DataTable path validation

Validate `FieldColumnDef.fieldPath`, `RowActionDef` field paths, and inline-edit
paths with the same model/path resolver used by `ModelText` and `ForModel`
where model evidence is available. Unmodelled row shapes should remain allowed
but should not suppress validation when a signal is typed.

Landed 2026-06-03: `ModelPathValidator` now treats `View.DataTable` as a
semantic node. If `dt.signal.codec == CodecHint.Json(rowModelName)`, it
validates:

- each `FieldColumnDef.fieldPath`;
- each editable column's `RowInlineEdit.idField`;
- `RowDelete.idField`;
- `RowPost.bodyField`;
- `RowLink.fieldPath`;
- defensive `RowInlineEdit.idField` values if an inline-edit action appears in
  the row-action list.

Raw `FetchUrlSignal` / `CodecHint.RawText` tables remain permissive. The
validator intentionally does not validate `DataTableLowering.lower(dt)` because
the lowering uses `ModelView(dt.signal, signal.id, ForModel(signal.id, "", ...))`
as table chrome; validating that synthetic empty `ForModel` path would produce
false non-list errors for row-model signals.

### Phase 2 - Data source abstraction

Split table chrome from the fetch-only source:

```scala
enum TableDataSource:
  case Remote(signal: FetchUrlSignal)
  case StaticRows(rows: List[Map[String, Any]])
  case SignalRows(signal: ReactiveSignal[?])
  case Paged(...)
```

This can let the same column/action model serve local data, typed model data,
and paged remote data without replacing `View.DataTable` again.

### Phase 3 - Column and action expressiveness

Add structured column kinds or renderers for common business UI needs:
date, money, status badge, link, width/responsive hints, empty/loading/error
states, sorting, filtering, and row selection.

Normalize row actions around a payload builder rather than a single `bodyField`
string so actions can send an id, whole row, or structured JSON object.

## Testing strategy

- Phase 0: existing fetch plugin tests, editable-column intrinsic coverage,
  null/default edit-action and row-action header coverage, source guard for the
  `rowEditAction` import in `std/ui/data.ssc`, and targeted example
  parse/compile smoke for examples changed by the migration.
- Phase 1: frontend-core validator unit tests for valid and invalid typed
  DataTable paths, plus a raw fetch permissive case. Backend smoke only if
  diagnostics are wired into compile paths.
- Phase 2: core IR construction tests plus one web and one native backend smoke.
- Phase 3: per-backend renderer snapshots for each new column/action kind.

## Open questions

- Should the eventual data-source abstraction be an enum under `frontend/core`
  or a plugin-provided descriptor that backends can opt into incrementally?
- Should `DataTable` become typed over a row model, or should typed validation
  stay an optional module-level check?
- Should row actions remain HTTP-oriented helpers, or should the core action
  contract become transport-neutral with HTTP as one lowering?

## Design notes

The old `FetchTable` API was convenient, but it mixed three concerns in one
constructor: fetching, table layout, and row mutation. The new explicit signal
contract is a better long-term foundation because headers, refresh triggers,
typed fetches, and future local/paged sources remain visible at the call site.

For validation, `DataTable` stays semantic. It is tempting to reuse
`DataTableLowering` and let the existing `ModelText` / `ForModel` validators
catch paths, but the lowering is renderer chrome, not the source contract. The
source contract is row fields relative to the typed fetch signal's JSON model.
