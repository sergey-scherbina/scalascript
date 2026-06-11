# JS content-toolkit `action=` / `rows=` registry parity + fail-soft

**Status:** implemented (2026-06-11).
**Slice of:** the declarative-dynamic-UI proposal (`busi docs/declarative-ui-authoring.md`,
rozum seq-113). This is **Scope A** of that proposal: browser parity for the
*already-existing* `actions` / `rowBindings` registries — not the richer §3/§4
model (typed YAML columns, `registerDataSource` shape/rowsPath, `onSuccess`
vocabulary, slot, build-time lint), which is Scope B and tracked separately.

## Problem

The content toolkit lets Markdown reference a *registered, typed* host capability
by id — never a URL/method/body — turning a declarative `toolkit:` link into a
real effect while keeping the security boundary:

- `[Save](toolkit:button?action=<id>)` → an `ActionButtonNode` bound to the
  `EventHandler` registered under `<id>` in `ContentToolkitOptions.actions`.
- `[t](toolkit:table?rows=<id>)` → a live `DataTableNode` whose row source is the
  `ContentRowBinding` registered under `<id>` in `ContentToolkitOptions.rowBindings`.

The **interpreter** implements both (`ContentIntrinsics.scala`: `action=` at the
`button` link kind, `rows=` at the `table` link kind → `actionButtonNode` /
`rowBindingDataTable`). The **JS backend** (`ContentToolkitJs`) did not: its
`_ssc_tk_link_node` handled only static + local-signal controls
(textField/checkbox/button-with-`signal=`/badge/divider). So a `toolkit:button?action=`
silently required a `signal=` and threw on its absence, and `toolkit:table?rows=`
hit the `unsupported toolkit link control 'table'` error. busi ships via
`emit-spa`, so the whole "declarative effects" idea was unusable in the browser.

Separately, every toolkit render error in `ContentToolkitJs` went through
`_ssc_tk_error`, which **throws** — in the browser a single bad id / malformed
block aborts the whole render and blanks the SPA (busi's seq-102 white-screen
class of bug). The proposal's §6 requires browser renders to **fail soft**.

## What this slice does

### 1. `action=` parity (port of interpreter `button` link kind)

In `_ssc_tk_link_node`, the `button`/`signalbutton` kind now checks for an
`action` query param **before** `signal`:

- `action=<id>` present → look up `options.actions.get(<id>)`; emit
  `{_type:'ActionButtonNode', handler, label, disabled}`. Unregistered id →
  loud error listing available ids (same message shape as the interpreter).
- `enabledWhen=<sig>` wraps the button in a `ShowWhenNode` whose false branch is
  the same `ActionButtonNode` with `disabled:true` — identical to the interpreter
  and to the existing `signal=` button.
- No `action=` → unchanged: the existing `signal=` SignalButtonNode path.

### 2. `rows=` parity (new `table` link kind)

`_ssc_tk_link_node` gains a `table` case mirroring the interpreter's
`rowBindingDataTable`:

- `rows=<id>` required → look up `options.rowBindings.get(<id>)`, which must be a
  `ContentRowBinding` (`{_type:'ContentRowBinding', rows, columns, actions}`);
  emit `{_type:'DataTableNode', signal:rows, columns, actions}`. Unregistered id
  or wrong-typed binding → loud error.

Both nodes are **already lowered by the shared `std/ui/lower.ssc`**
(`ActionButtonNode` → `<button>` + click→handler; `DataTableNode` →
`_ssc_ui_dataTableView`), which compiles to every backend — so no new lowering or
DOM code is needed; the port only has to produce the right TkNode shape with the
registered value threaded through opaquely.

`options` is now threaded into `_ssc_tk_link_node` (and the `_ssc_tk_list_item`
helper) so the link path can read the registries; previously only the signals
`env` was passed.

### 3. Fail-soft (proposal §6)

`_ssc_tk_error` still throws (callers rely on it for control flow), but the
**render boundaries** now catch and degrade:

- Each block in a node/section walk renders through `_ssc_tk_safe_block`, which
  on any throw returns an inline error node (`{_type:'RawTextNode', text:'⚠ '+msg}`)
  for *that block only* — sibling blocks still render.
- The three entry functions (`contentToolkitNode` / `contentToolkitBlock` /
  `contentToolkitSection`) wrap their whole body in try/catch → inline error node,
  so a missing block id / duplicate id / bad registry ref renders a visible
  placeholder instead of blanking the SPA.

**Intentional backend divergence:** the interpreter throws on a bad id (server
side, caught upstream); the browser fails soft (visible inline error). This is
the §6 requirement, not a parity break — parity holds for all *valid* inputs,
which is what `ContentNativeClientParityTest` checks.

## Non-goals (Scope B — separate slices)

- Typed YAML `table:`/`source:`/`action:` authoring keys (§4) — this slice keeps
  the existing `toolkit:` link surface.
- `registerDataSource` shape/`rowsPath`, `onSuccess` vocabulary, `registerComputed`.
- The `slot` escape-hatch (§4.3) and build-time lint (`ssc check` id validation).
- JvmGen parity for `action=`/`rows=` (no current consumer; interpreter + JS are
  what busi uses).

## Behavior checklist

- [x] `toolkit:button?action=<id>` → `ActionButtonNode` with the registered handler.
- [x] `&disabled=true` and `&enabledWhen=<sig>` honored (ShowWhenNode wrap).
- [x] unregistered `action=` id → loud error (available ids listed).
- [x] `toolkit:table?rows=<id>` → `DataTableNode` with the registered source/columns/actions.
- [x] unregistered `rows=` id / non-ContentRowBinding → loud error.
- [x] a bad id renders an inline error node, never throws out of the entry fn (fail-soft).
- [x] valid content still renders identically to before (existing toolkit tests green).
- [x] regression tests in `JsGenStdImportTest`.
