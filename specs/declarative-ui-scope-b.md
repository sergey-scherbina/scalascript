# Declarative dynamic UI — Scope B (richer authoring model)

**Status:** in progress (B.1 implemented 2026-06-11).
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
- **B.3 — `registerDataSource`.** A `.ssc` API for `signalSource` / `fetchSource`
  (managed fetch, re-fetch on tick) / `staticSource`, with built-in envelope
  normalisation (`{data:[]}` / bare / `rowsPath`). `source:` then references a
  registered data source, not only a `ContentRowBinding`.
- **B.4 — `registerAction` + `onSuccess` vocabulary.** Structured action results
  (`bumpTick` / `setSignal` / `navigate`) + `bodyBuilder` from named fields /
  current row / whole row, with the capability/RBAC check staying in ScalaScript.
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
- **B.6 — slot escape-hatch** (`{type: slot, id: …}` filled by ScalaScript) —
  proves §0 two-way coexistence. Deferred from v1 per §9.4.
- **B.7 — build-time lint.** `ssc check` validates that referenced source/action/
  computed ids exist and shapes match (a static pass like `scanContentUsage`).

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

## Non-goals (later slices)

B.2–B.7 above; JvmGen parity for the YAML registry controls (no current consumer).
