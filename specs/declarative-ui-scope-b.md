# Declarative dynamic UI — Scope B (richer authoring model)

**Status:** COMPLETE — B.1 + B.2 + B.3 v1 + B.4 v1 (+ B.4+ bodyBuilder) + B.5 + B.6 + B.7 v1 (+ signal-ref lint) implemented (2026-06-11/12). Remaining items are documented v-next follow-ups (B.3 `rowsPath` on native backends, B.7 lint for Markdown `toolkit:` links, typed/keyed `formBody`).
**Upstream proposal:** busi `docs/declarative-ui-authoring.md` (rozum seq-113).
**Predecessor:** Scope A (`specs/js-toolkit-action-rows-registry.md`) — browser
parity for the existing `action` / `rowBindings` registries via the Markdown
`toolkit:button?action=<id>` / `toolkit:table?rows=<id>` *link* surface.

Scope A made the registries render in the browser. Scope B grows the *authoring
surface* on top of those same registries. Per the agreed §9 answers: registries
stay **render-context-scoped**; parity is **staged, browser-first**; v1 uses
**one-way embedding** (declarative-in-code), slot deferred; lint is **both**
render-time fail-soft (already shipped in A) + build-time (later).

## Slice plan (browser-first, incremental)

- **B.1 — YAML control-tree registry resolution (this slice).** Bring the Scope A
  registry resolution into the `@ui=toolkit` YAML control tree, not just Markdown
  links: `{type: button, action: <id>}` → `ActionButtonNode`;
  `{type: table, source: <id>}` (alias `rows:`) → live `DataTableNode` from the
  registered `ContentRowBinding`. Interpreter + JS parity; reuses Scope A's
  `actions` / `rowBindings` registries and the shared `lower.ssc` nodes.
- **B.2 — typed inline columns in YAML `table` (this slice).** `columns: [{label,
  path, kind: money|status|date|link|text, …}]` → typed `DataColumn`s built from
  YAML, so the author declares columns inline at the call site instead of only via
  the `contentRows(id, signal, columns)` registration (which stays valid; inline
  `columns:` overrides it for that table). **Decoupling without moving the model:**
  the interpreter column model (`FieldColumnDef` + kinds) lives in `fetch-plugin`,
  the toolkit in `content-plugin`, which must not depend on it. Rather than extract
  the model to a shared module, B.2 adds a tiny `NativeContext.resolveGlobal(name)`
  SPI bridge (default `None`, the interpreter returns its global binding) so the
  toolkit **reuses the already-registered column-builder natives** — `fieldColumn` /
  `dateColumn` / `moneyColumn` / `statusColumn` / `linkColumn` — via `invokeCallback`,
  producing real `FieldColumnDef` values with no plugin→plugin compile dependency.
  The JS side builds the column objects (`{title, fieldPath, align, kind}`) directly
  in `ContentToolkitJs` (no coupling there). Both feed the existing `DataTableNode`,
  so render stays identical.
- **B.3 — `registerDataSource` (v1 this slice).** A `.ssc` data-source vocabulary
  for `signalSource` / `fetchSource` (managed fetch, re-fetch on tick) /
  `staticSource`, registered by id with `contentDataSource(id, source, columns,
  actions)`; `source:` then references a registered data source, not only a raw
  `contentRows` signal. The genuinely-new runtime capability is `fetchSource`'s
  `rowsPath` — a dotted envelope path so a non-standard fetch envelope can be
  unwrapped. See `B.3 detail`.
- **B.4 — `registerAction` + `onSuccess` vocabulary (v1 this slice).** A structured
  `onSuccess` list for a fetch action — `onBumpTick(sig)` / `onSetSignal(sig, value)`
  / `onNavigate(path)` — run in order after a 2xx, so a `{type: button, action:
  <id>}` declares richer post-success behaviour than the single tick `fetchAction`
  bumps today. See `B.4 detail`. `bodyBuilder` (body from named fields / current row
  / whole row) is deferred — `RowPayload` already covers per-row bodies — and the
  capability/RBAC check stays in ScalaScript (the action handler is still code).
- **B.5 — `registerComputed` (LANDED 2026-06-11).** Code-built derived signals
  registered by id (`contentComputed(id, sig)` → `ContentToolkitOptions.computed` /
  the `computed =` builder arg) are merged into the toolkit signal env *under* the
  markdown/YAML `signals:` (a locally-declared signal of the same name wins), so a
  YAML control can reference a derived signal by id — `showWhen: <id>`,
  `enabledWhen: <id>`, `{type: signalText, signal: <id>}`. Interpreter
  (`toolkitEnvFor` merges `options.computed ++ base.signals` at every env-build
  site) + JS (`_ssc_tk_env` merges `options.computed` into the env). Reuses the
  existing `signalRef` resolution — no new control wiring. Unlike the YAML
  `signals:` block (scalar defaults only) these can be `computedSignal`s.
- **B.6 — slot escape-hatch (this slice).** `{type: slot, id: <id>}` injects a
  ScalaScript-authored `TkNode` registered by id with `contentSlot(id, node)` — the
  inverse of the declarative controls, so when the vocabulary is not enough the
  author drops a code-built widget into a declarative panel by id. Proves §0
  two-way coexistence. See `B.6 detail`.
- **B.7 — build-time lint (v1 this slice).** `ssc check` validates that referenced
  source/action ids exist (a static pass like `scanContentUsage`). See `B.7 detail`.

## B.1 detail

The `@ui=toolkit` YAML control renderer previously knew only static + local-signal
controls (`vstack/hstack/heading/text/signalText/show/textField/checkbox/button/
badge/card/divider`). Its `button` was signal-only and there was no `table`. B.1:

**Button `action:`** — `{type: button, action: <id>, label: …, disabled?, enabledWhen?}`
resolves `<id>` in the action registry → `ActionButtonNode(handler, label, disabled)`,
honoring `enabledWhen` (ShowWhenNode wrap), exactly like the Markdown
`toolkit:button?action=` link. Without `action:`, the existing `signal:` button
path is unchanged.

**`table` control** — `{type: table, source: <id>}` (alias `rows: <id>`) resolves
`<id>` in the row-binding registry → `DataTableNode(signal, columns, actions)` from
the registered `ContentRowBinding`, like the Markdown `toolkit:table?rows=` link.

- Interpreter (`ContentIntrinsics.toolkitControl`): the registries already ride on
  `env` (`env.actions` / `env.rowBindings`), so `toolkitButton` reads `action` from
  `env.actions` and a new `table` case reads `source`/`rows` from `env.rowBindings`.
- JS (`ContentToolkitJs._ssc_tk_render_control`): the registries ride on `options`,
  so `options` is now threaded through `_ssc_tk_yaml_block` →
  `_ssc_tk_render_control` → `_ssc_tk_children`; the `button`/`table` cases reuse
  the Scope A helpers `_ssc_tk_action` / `_ssc_tk_row_binding` /
  `_ssc_tk_row_binding_datatable`.

Fail-soft (Scope A) still applies: a bad id throws inside the control render and is
caught at block granularity → inline error node, never a blank SPA.

### Behavior checklist (B.1)

- [x] `{type: button, action: <id>}` → ActionButtonNode (interp + JS).
- [x] `enabledWhen` honored on an action button.
- [x] `{type: table, source: <id>}` / `rows: <id>` → DataTableNode (interp + JS).
- [x] unregistered id → loud error (caught fail-soft on JS).
- [x] existing signal-button / static controls unchanged.
- [x] interpreter + JS regression tests.

## B.7 detail (v1 — id-existence lint)

Until now a typo'd id in a `@ui=toolkit` control — `{type: button, action: refesh}`
when the registry holds `refresh` — only surfaced at **render time**: the bad id
throws inside the control render and is caught fail-soft into an inline error node
(Scope A). On the browser SPA that is a small red box in an otherwise-live page; in
CI it is invisible. B.7 lifts that to **build time**: `ssc check` warns about a
referenced id that has no matching registration, so the typo is caught before the
app is shipped (same spirit as the `examples` smoke-test — silently-broken things
must be caught by tooling).

**Pure analysis** lives in core (`scalascript.transform.ContentToolkitLint`,
mirroring `MarkupInterpolatorCheck`), operating only on the parsed `ast.Module` —
no interpreter, no content pipeline, no plugin YAML parser, no rendering. It only
**harvests id strings**, never re-renders controls.

- **References** are collected from `@ui=toolkit` blocks — a `Content.CodeBlock`
  whose fence attrs carry `ui=toolkit` (`@ui=toolkit`). Its `source` (the raw YAML)
  is line-scanned for the control keys `action:` (→ action registry) and `source:`/
  `rows:` (→ source registry); the value token is the referenced id. File-level line
  numbers come from the block's `lineOffset`.
- **Registrations** are collected by traversing every scalascript `CodeBlock.tree`
  (scala.meta) for `contentAction("id", …)` (→ action ids) and `contentRows("id", …)`
  (→ source ids) with a string-literal first arg — regardless of how the call is
  nested inside `Map(...)` / `contentToolkitOptionsWith*(...)`.
- **Cross-check (conservative, warnings only):** a reference id is flagged *only if*
  its registry has ≥1 registration somewhere in the reachable graph and the id is not
  among them. If a registry is empty in the graph (registrations may be dynamic or
  external) no warning fires — this keeps false positives near zero. Registrations
  are unioned across the entry module **and its transitively-imported `.ssc`
  modules** (CLI resolves + parses imports, like `scanContentUsage`); if any import
  cannot be resolved/parsed the graph is *incomplete* and the lint is suppressed
  entirely for that file (a hidden registration must never produce a false warning).

**Scope:** `action:` (→ `contentAction`), `source:`/`rows:` (→ `contentRows`/
`contentDataSource`), and `signal:`/`showWhen:`/`enabledWhen:` (→ the *signal
universe*: a `contentComputed` registration **or** a locally-declared YAML
`signals:` default, both harvested so a valid reference never falsely warns).
Markdown `toolkit:` *link* references (`toolkit:button?action=`,
`toolkit:table?rows=`) and "shapes match" checking are still deferred (the links
live in raw prose, not the `@ui=toolkit` CodeBlock the pass scans). Warnings, never
errors (exit code unchanged on a clean-but-warned file: `OK (with warnings)`).

### Behavior checklist (B.7 v1)

- [x] `{type: button, action: <unknown>}` with a non-empty action registry → warning.
- [x] `{type: table, source: <unknown>}` / `rows: <unknown>` with a non-empty source
      registry → warning.
- [x] a correct id (registered locally or in a transitively-imported module) → no warning.
- [x] an empty registry for that kind → no warning (conservative).
- [x] an unresolvable (local, non-std) import → lint suppressed for that file (no false positive).
- [x] warnings carry the file-level line of the YAML reference; exit code unchanged.
- [x] **(B.7+)** `signal:`/`showWhen:`/`enabledWhen:` validated against the signal
      universe (`contentComputed` ∪ local `signals:`); unknown → warning, a valid
      local/computed signal → no warning, empty universe → no warning (conservative).
- [x] core unit tests (`ContentToolkitLintTest`, 11) + CLI `ssc check` tests
      (`CheckCommandTest`, +2). An edit-distance "did you mean '…'?" hint is added
      when a registered id is a plausible typo of the reference.

## B.3 detail (v1 — data-source vocabulary + `rowsPath`)

Today a `{type: table, source: <id>}` resolves a `contentRows(id, signal, columns)`
registration whose `rows` must be a pre-built fetch `Signal`. B.3 adds a named
**data-source vocabulary** so the author declares *how* the rows are produced and
registers it by id, and folds the envelope-shape concern into `fetchSource`.

**`.ssc` surface** (`std/ui/content.ssc`, reusing the existing `TableDataSource`
machinery from `std/ui/data` — `staticRowsSource` / `signalRowsSource` already
render on interp + JVM + JS):

- `staticSource(rows)` — in-memory rows, no fetch (`= staticRowsSource(rows)`). This
  is the genuinely-missing path: a static row set in the `source:` registry that
  previously required hand-wrapping a signal.
- `signalSource(sig)` — reactive rows from an arbitrary signal (`= signalRowsSource`).
- `fetchSource(id, url, tick, headers, rowsPath = "")` — a managed GET that
  re-fetches when `tick` bumps, with bearer `headers`; the response envelope is
  normalised to a row array. `rowsPath` is an optional dotted path
  (`result.page.items`) tried before the built-in envelope keys.
- `contentDataSource(id, source, columns, actions = [])` — register any of the
  above under an id (builds a `ContentRowBinding`); `source:` / `rows:` resolve it
  exactly like `contentRows`, so render is unchanged.

**`rowsPath` runtime** (the only new runtime capability) is exposed by a new
`fetchRowsSource(signal, rowsPath)` intrinsic. It is a **browser-runtime** concern —
the one place a live fetch envelope is unwrapped at run time — so it is carried on
the signal there rather than on the shared `TableDataSource` model (keeping the
model and the six native frontend backends untouched): the JS shim attaches
`_rowsPath` to the fetch signal, the Remote source emits it into the DataTable
descriptor (`data-ssc-datatable-rows-path`), and `_ssc_ui_rowsOf(v, rowsPath)`
drills the dotted path and, if it yields an array, uses it; otherwise it falls back
to the existing `{data|rows|items|results}` keys (a wrong path degrades to the
default, never crashes). The interpreter's `fetchRowsSource` builds a plain
`TableDataSource.Remote` (a render descriptor that does not unwrap a live envelope).

**Scope of v1 (honest):** `rowsPath` is wired on the **JS browser** fetch path
only — that is where a fetch envelope is unwrapped at run time and matches Scope
B's browser-first staging. Interp/JVM-codegen and native (Swing/JavaFX/SwiftUI)
toolkit tables accept it but do not re-implement envelope unwrapping (consistent
with "JvmGen parity for the YAML registry controls" being a non-goal).
`staticSource` / `signalSource` reuse the existing cross-backend `TableDataSource`
render unchanged.

### Behavior checklist (B.3 v1)

- [x] `staticSource(rows)` registered via `contentDataSource` renders an in-memory
      `source:` table, no fetch (interp via the example; JS reuses the existing
      `staticRowsSource` `_source:'static'` path).
- [x] `signalSource(sig)` renders a reactive `source:` table (alias of the existing
      cross-backend `signalRowsSource`).
- [x] `fetchSource(id, url, tick, headers)` renders a managed-fetch `source:` table
      (interp builds `TableDataSource.Remote`; JS threads the fetch signal).
- [x] `fetchSource(..., rowsPath = "result.items")` unwraps a non-standard envelope
      on the browser runtime; a wrong path falls back to the default keys (JS test
      exercises `_ssc_ui_rowsOf` drill + fallback + legacy).
- [x] `fetchRowsSource(sig, rowsPath)` added without breaking the legacy bare
      FetchUrlSignal / `contentRows` paths (shared `TableDataSource` model unchanged).
- [x] interp test (`FetchPluginInterpreterTest`) + JS emit test
      (`JsGenStdImportTest`, `_rowsPath` thread + `_ssc_ui_rowsOf` drill) +
      runnable `examples/content-data-source.ssc` (`content-data-source:ok`).

## B.4 detail (v1 — structured `onSuccess`)

Today a registered action (`contentAction(id, fetchAction(method, url, body, tick,
headers))`) can bump exactly **one** tick when its POST/PUT succeeds. Real screens
need more after a successful write: refresh a table *and* set a status signal, or
navigate to another view. B.4 v1 adds a structured **`onSuccess` effect list** run in
order on a 2xx.

**`.ssc` surface** (`std/ui/primitives.ssc`):

- `onBumpTick(tick)` — increment an `Int` signal (re-fetch a table, etc.).
- `onSetSignal(sig, value)` — set a signal to a literal (a status/toast/flag).
- `onNavigate(path)` — set the location hash (route) to `path`.
- `fetchActionWith(method, url, body, headers, onSuccess)` — like `fetchAction` but
  carrying the `onSuccess` effect list instead of a single tick. The result is an
  ordinary `EventHandler`, so it registers via the existing `contentAction(id, …)`
  and a `{type: button, action: <id>}` control renders it unchanged (Scope B.1).

**Runtime** is **browser-scoped** (the one place an action actually executes — same
rationale as B.3's `rowsPath`, and avoids touching `EventHandler.FetchAction`, which
is pattern-matched in 11 places across six native backends). The JS marker carries
`onSuccess`; the button descriptor emits `data-ssc-fetch-onsuccess` (effects with
their signal ids); the click handler, after a 2xx, runs each effect (`bumpTick` →
`_set(tick, +1)`, `setSignal` → `_set(sig, value)`, `navigate` → `location.hash =
path`). The interpreter's `fetchActionWith` builds a plain `EventHandler.FetchAction`
(using the first `onBumpTick` as its `onSuccessTick`); native/JVM tables do not
execute the rich effects (consistent with the YAML-registry-controls non-goal).

**Scope of v1 (honest):** `onBumpTick` / `onSetSignal` / `onNavigate`, browser
runtime. A failed (non-2xx) action runs **no** effects. The **bodyBuilder** (a
request body assembled from named field signals) is the follow-up below; the
per-row variants (`current row` / `whole row`) are already covered by `RowPayload`
(`fieldPayload`/`wholeRowPayload`/`fieldsPayload`).

### Behavior checklist (B.4 v1)

- [x] `fetchActionWith(..., [onBumpTick(t)])` bumps `t` on a 2xx
      (`_ssc_ui_runOnSuccess` increments the tick id).
- [x] `onSetSignal(s, v)` sets `s` to `v` on success; `onNavigate(p)` sets the hash.
- [x] multiple effects run in order; a non-2xx runs none (runner is `ok`-guarded).
- [x] a `{type: button, action: <id>}` registered with a `fetchActionWith` handler
      renders and carries `onSuccess` (interp builds an `EventHandler.FetchAction`;
      JS threads the effects into the `ActionButtonNode` handler).
- [x] interp test (`FetchPluginInterpreterTest`) + JS test (`JsGenStdImportTest`:
      structural thread + `_ssc_ui_runOnSuccess` apply-on-2xx / skip-on-failure) +
      runnable `examples/content-action-onsuccess.ssc` (`content-action-onsuccess:ok`).
- Note: `headers` is the last (defaulted) param of `fetchActionWith`; like the other
  fetch natives it handles both the 4-arg (no headers) and 5-arg arities, since
  extern defaults are typer-level (not runtime-filled for plugin natives).

## B.4+ detail (bodyBuilder — `formBody`)

`fetchActionWith` takes the POST/PUT body as a single `Signal[String]` (a pre-built
JSON string the author must keep in sync with the form). A declarative form instead
wants the body assembled from its **named field signals** at submit time — the
top-level analog of `RowPayload` for row actions. `formBody(fields)` is that body
source:

```scalascript
val submit = fetchActionWith("POST", "/api/orders",
  formBody(["customer", "amount"]),       // body = { customer: <sig>, amount: <sig> }
  [onBumpTick(tick), onSetSignal(status, "Saved")])
```

`fetchActionWith`'s `body` is widened to `Any` so it accepts **either** a
`Signal[String]` (unchanged) **or** a `formBody(...)` descriptor. At submit on the
**browser** (where the action executes — same scoping as `onSuccess`), the field
ids are read from the signal store and serialised to a flat JSON object whose keys
are the field names: `{"customer": <_sv.customer>, "amount": <_sv.amount>}`. The
descriptor emits `data-ssc-fetch-body-fields`; a testable
`_ssc_ui_buildFormBody(fieldsJson, sv)` does the assembly. The interpreter's
`fetchActionWith` accepts the `formBody` descriptor and builds a `FetchAction` with a
synthetic empty body (the assembly is browser-only, like the effect list). Values
are taken verbatim from the signals (strings) — a typed-field / `key: signalId`
mapping is a later refinement.

### Behavior checklist (B.4+ bodyBuilder)

- [ ] `fetchActionWith(..., formBody([a, b]), …)` POSTs `{"a": <sig a>, "b": <sig b>}`
      assembled from the named signals at click (browser).
- [ ] a plain `Signal[String]` body still works (the `body: Any` widening is
      backward-compatible).
- [ ] interp accepts `formBody(...)` and builds an `EventHandler` (browser assembles).
- [ ] interp test + JS test (`_ssc_ui_buildFormBody` assembly) + an example.

## B.6 detail (slot escape-hatch)

The declarative controls go one way — Markdown/YAML drives the View. A `slot` is the
**escape hatch the other way**: a placeholder in the control tree that ScalaScript
fills with an arbitrary code-built `TkNode`. So an author who needs a widget the
vocabulary does not cover (a custom chart, a bespoke composite) builds it in code
with the ordinary `std/ui` node helpers and drops it into the declarative panel by
id — §0 two-way coexistence.

**Authoring:**

```yaml @ui=toolkit
controls:
  type: vstack
  children:
    - type: heading
      text: Dashboard
    - type: slot
      id: revenueChart      # → filled by a code-built TkNode
```

```scalascript
val chart = card(badge("Q3", "success"), [ /* … any TkNode … */ ])
val opts  = contentToolkitOptionsWithSlots(Map(contentSlot("revenueChart", chart)))
```

**Mechanism** reuses the existing registry pattern (parity with
`actions`/`rowBindings`/`computed`): a new `slots: Map[String, Any]` registry on
`ContentToolkitOptions`, populated by `contentSlot(id, node)` and the
`contentToolkitOptionsWithSlots(...)` builder. A `{type: slot, id: <id>}` control
resolves `<id>` in `options.slots` and returns the registered node **verbatim** (it
is already a built `TkNode`, so it composes into the tree and lowers normally — no
re-rendering, no env resolution). Interpreter (`toolkitControl` `case "slot"` →
`slotRegistry`) + JS (`_ssc_tk_render_control` `case 'slot'` → `_ssc_tk_slot`)
parity. An unregistered id is a loud error (fail-soft on JS — caught at block
granularity into an inline error node, like every other registry miss).

### Behavior checklist (B.6)

- [x] `{type: slot, id: <id>}` returns the `contentSlot(id, node)`-registered TkNode
      (interp + JS).
- [x] a slot node composes inside a `vstack`/`card` and lowers like any control
      (the example lowers `card(kpiCard(...))` slotted into a `vstack`).
- [x] an unregistered slot id → loud error (interp test; caught fail-soft on JS).
- [x] `contentToolkitOptionsWithSlots` carries slots alongside the other registries;
      existing builders/options are unchanged (`slots` defaults to empty).
- [x] interp tests (`ContentPluginInterpreterTest`, +2) + JS test
      (`JsGenStdImportTest`) + runnable `examples/content-slot.ssc` (`content-slot:ok`).

## Non-goals (later slices)

B.2–B.7 above; JvmGen parity for the YAML registry controls (no current consumer).
