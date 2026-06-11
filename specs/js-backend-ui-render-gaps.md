# JS-backend UI render gaps

> Status: ✓ Landed (2026-06-11). Reported by busi (rozum seq-79).
> Owner: scalascript. Slug: `js-backend-ui-render-gaps`.

## Problem

`emit-spa` produces a browser bundle that renders a **blank white screen** on
every route, while the same `.ssc` source renders correctly under the
interpreter and the JVM real-server backend. busi found this only by driving the
SPA through Playwright — the existing `.ssc` UX tests hit JSON endpoints and
assert string contracts in sources, they never open a browser.

Two **independent** divergences of the JS backend from the interpreter, both of
which the interpreter silently tolerates:

### Layer 1 — five `std/ui/data` row-data natives undefined in the JS backend

These are declared `extern def` in `runtime/std/ui/primitives.ssc` and
implemented in the interpreter (fetch-plugin `FetchIntrinsics`) + JVM
(`JvmRuntimeUiPrimitives`), but have **no `_ssc_ui_*` shim** in the JS Signals
runtime (`runtime/backend/js/.../codegen/JsRuntimeSignals.scala`):

| extern | interpreter result |
|---|---|
| `staticRowsSource(rows)`   | `TableDataSource.StaticRows(rows)` |
| `signalRowsSource(sig)`    | `TableDataSource.SignalRows(sig)` |
| `fieldPayload(name)`       | `RowPayload.Field(name)` |
| `wholeRowPayload()`        | `RowPayload.WholeRow` |
| `fieldsPayload(names)`     | `RowPayload.Fields(names)` |

JsGen emits, for every imported `extern def f`:

```js
const f = (typeof _ssc_ui_f !== 'undefined') ? _ssc_ui_f : undefined;
```

So a missing shim makes `f` literally `undefined`. A call site such as
`fieldsBody(names) = fieldsPayload(names)` then evaluates `undefined(...)`,
throwing `pageerror: not callable: ()` — which kills the whole SPA mount.

This is the direct sequel to the typed-column-native fix (`250e9c75`); those
fixed `dateColumn`/`moneyColumn`/`statusColumn`/`linkColumn`, these five
row-data natives are the remainder.

### Layer 2 — `lower` is not idempotent on an already-lowered `View`

Each busi `*Content` returns `element("div", …, [ lower(child, theme), … ])`,
i.e. an already-lowered `_Element` **View**. `app.ssc` then calls
`lower(content, theme)` — running `lower` on a value that is **already a View**.

`runtime/std/ui/lower.ssc` matches only the `TkNode` ADT (sealed trait, no
catch-all). On a non-`TkNode` scrutinee the JS backend throws
`Match failure: …`; the interpreter tolerates it. Result: blank screen on JS.

## Resolution

### Layer 1 — define the five shims (JsRuntimeSignals)

Mirror the interpreter / `StaticJsEmitter` semantics with plain runtime markers:

- `_ssc_ui_staticRowsSource(rows)` → `{ _source: 'static', rows: rows || [] }`
- `_ssc_ui_signalRowsSource(sig)`  → `{ _source: 'signal', sig }`
- `_ssc_ui_fieldPayload(name)`     → `{ _payload: 'field', name }`
- `_ssc_ui_wholeRowPayload()`      → `{ _payload: 'wholeRow' }`
- `_ssc_ui_fieldsPayload(names)`   → `{ _payload: 'fields', names: names || [] }`

`_ssc_ui_dataTableView(source, columns, actions)` already produces
`{ _type:'_DataTableView', signal: source, columns, actions }`. The first
argument may now be a `TableDataSource` marker (`._source`) **or** the legacy
bare `FetchUrlSignal` (`._fetchGet`). Extend both consumers:

- **`_ssc_ui_renderBody`** (`_DataTableView` case): emit
  `data-ssc-datatable-rows` (inline JSON) for `static`, and
  `data-ssc-datatable-rows-sig` (signal id, collected) for `signal`; keep the
  existing `_fetchGet` → `data-ssc-datatable-url` path otherwise.
- **`_ssc_ui_mount`** (`[data-ssc-datatable]` handler): render inline rows
  directly for `static`; subscribe to the rows signal for `signal`; keep the
  `doFetch()` path otherwise.

**RowPayload in `_RowPost` body.** `rowPostAction(label, method, url, payload, …)`
accepts a `RowPayload` as `bodyField`. The mount currently does
`String(getField(r, act.bodyField))`, assuming a plain field name. Resolve a
`RowPayload` marker per `StaticJsEmitter.customRowPayloadExpr`:

- `field`   → `String(getField(row, name))`
- `wholeRow`→ `JSON.stringify(row)`
- `fields`  → `JSON.stringify(Object.fromEntries(names.map(n => [n, getField(row, n)])))`
- string (back-compat) → `String(getField(row, payload))`

### Layer 2 — make `lower` idempotent

Add a catch-all passthrough so `lower(view) ≡ view` for an already-lowered
value, keeping the `TkNode` cases unchanged. Both backends then agree.

## Acceptance

- `JsGen.generateRuntime(Set(Signals))` defines `function _ssc_ui_staticRowsSource(`,
  `_ssc_ui_signalRowsSource`, `_ssc_ui_fieldPayload`, `_ssc_ui_wholeRowPayload`,
  `_ssc_ui_fieldsPayload`.
- An emit-spa bundle that imports the five natives binds them to defined shims
  (no `= undefined`), and `node --check` passes.
- A `dataTableView(staticRowsSource(rows), cols, acts)` SPA mounts a `<table>`
  with the static rows (jsdom/node smoke or attribute assertion).
- `lower(alreadyLoweredView, theme)` returns the view unchanged on both the
  interpreter and the JS backend (no `Match failure`).
- An example `examples/datatable-static-spa.ssc` emits and `node --check`-passes.

### Layer 2b — raw DataTableNode buried in an already-lowered container

Follow-up (2026-06-11). The idempotent passthrough (Layer 2) returns an
already-lowered `_Element` whole, so it does **not** descend into that element's
children. If a caller mixes a *raw* `DataTableNode` (the `TkNode` returned by
`dataTable(...)`, not the `View` from `dataTableView`/`staticDataTable`) directly
into an `element(...)` children list, that node reaches the renderer un-lowered.
The JS `walk` had no `'DataTableNode'` case, so it hit `default → ''` and the
table **vanished silently** (confirmed: `hasTable:false`, siblings rendered).

`lower(DataTableNode(signal, columns, actions))` is theme-free — it just wraps
into `dataTableView(signal, columns, actions)` — so `walk` now normalises a raw
`DataTableNode` (`v._type === 'DataTableNode'`) into a `_DataTableView` and
renders it through the existing path. Theme-dependent raw TkNodes (vstack,
heading, …) genuinely cannot render without a theme and remain unsupported as
un-lowered children (the "build a TkNode tree, `lower` once at the top" contract
stands); only the theme-free `DataTableNode` is recovered.

- Acceptance: a raw `dataTable(...)` child of an `element(...)`, lowered only at
  the top, renders a `<table>` (no longer dropped).

## Non-goals

- Lowering theme-dependent raw `TkNode`s (vstack/heading/…) that were never
  lowered — they need a `Theme` the renderer does not carry. Only the theme-free
  `DataTableNode` is normalised at render time.
- SignalRows reactive semantics beyond subscribe+re-render of the rows signal.
