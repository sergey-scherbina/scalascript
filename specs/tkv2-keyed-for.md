# Toolkit V2 Keyed For

## Overview

`forKeyed(items, key)(render)` adds keyed list reconciliation to the toolkit-v2
browser path. Today `View.ForSignal` rebuilds a list container whenever the
underlying list signal changes, so component-scoped signals inside rows do not
have a stable DOM/component lifetime across reorder, insert, or remove. This
slice introduces a keyed list surface that lets the custom/JsGen runtime reuse
row DOM nodes by stable keys while preserving the existing non-keyed
`ForSignal` behavior.

## Interface

- Public `.ssc` helper:
  `forKeyed[A](items: Signal[List[A]], key: A => String)(render: A => TkNode): TkNode`.
- The helper lives in `std/ui/reactive.ssc` and is exported from the same index
  used by toolkit-v2 consumers. It builds a `TkNode` so it can be used inside
  existing toolkit layouts and lowered with `std/ui/lower.ssc`.
- `lower.ssc` lowers the `TkNode` through a low-level primitive
  `forKeyedView(items, key, renderView)`; that primitive returns the browser
  runtime marker `_ForKeyed`.
- Keys are caller-owned stable strings. Duplicate keys are unsupported; the
  browser runtime keeps the last rendered row for a duplicate key and emits no
  extra API.
- The initial implementation targets the production `emit-spa` custom runtime.
  The old Scala `frontend-core` `View.ForSignal` path keeps its wipe/rebuild
  behavior; this slice does not retrofit that separate API.

## Behavior

- [ ] Initial render produces the same visible row order as the input list.
- [ ] Updating the list with the same keys in a different order reorders DOM
      nodes instead of recreating them, so per-row DOM identity survives moves.
- [ ] Removing a key removes that row from the container.
- [ ] Inserting a new key creates exactly one new row at the requested position.
- [ ] Event handlers inside a keyed row still work after reorder/insert/remove.
- [ ] Existing `View.ForSignal` and non-keyed toolkit list rendering continue
      to wipe/rebuild as before.

## Out of Scope

- Full component disposal semantics for removed rows. This slice preserves
  mounted DOM identity for surviving keys; unregistering abandoned
  component-scoped signals can land in a later lifecycle slice.
- React/Solid/Vue/SwiftUI keyed reconciliation parity.
- Server-rendered inline-script pages may render the initial keyed body without
  dynamic keyed reconciliation, because the browser only receives HTML + signal
  seeds there, not the original render callback. The production `emit-spa`
  browserpatch path does receive the view tree and reconciles dynamically.
- Re-rendering an existing row solely because the item value changed while its
  key stayed the same. The first implementation treats stable keys as stable
  row instances; row-local signals carry mutable state.

## Design

Add a toolkit node plus a browser runtime marker:

1. `KeyedForNode[A](items, key, render)` in `std/ui/nodes.ssc`,
2. `forKeyed` in `std/ui/reactive.ssc`,
3. `forKeyedView` in `std/ui/primitives.ssc`, and
4. `_ssc_ui_forKeyedView(items, key, render)` in `signals.mjs`.

`_ssc_ui_renderBody` renders `_ForKeyed` as a container with
`data-ssc-forkeyed` and one direct child wrapper per row carrying
`data-ssc-key`. It collects signals while invoking `render(item)` for initial
rows, just like ordinary static rendering.

`_ssc_ui_mount` is refactored into a root bridge plus a scoped binder. The root
mount keeps `_sv`, `_sub`, and `_set` in one bridge, while the scoped binder can
late-bind a newly inserted row subtree. Bound elements are marked so remounting a
scope does not double-attach event listeners or duplicate subscriptions.

When the backing signal changes, the keyed subscriber builds a map from key to
existing row wrapper, creates wrappers for new keys by calling the original
`render(item)` callback, appends wrappers in the new order, and removes wrappers
whose keys disappeared. Browser `appendChild` moves an existing node, so
reordering is a move rather than a recreate. New row wrappers are mounted through
the scoped binder so signal text, input bindings, fetch buttons, and ordinary
event handlers work after insertions.

## Decisions

- **Add an explicit keyed node** — chosen because `View.ForSignal` has shipped
  wipe/rebuild semantics and changing it globally would risk unrelated UI
  behavior. Rejected: silently making every `ForSignal` keyed by stringified
  item, because list values are not guaranteed unique or stable.
- **Implement on the v2 browser runtime, not Scala frontend-core** — chosen
  because toolkit-v2 production goes through `emit-spa`, `signals.mjs`, and
  `browserpatch.mjs`; the Scala `frontend/custom/StaticJsEmitter` path cannot
  call a `.ssc` row-render callback in the browser after initial emission.
- **Custom runtime first** — chosen because `emit-spa`/custom is the production
  toolkit-v2 path in `specs/ssc-toolkit-v2.md`. Rejected: waiting for all
  framework emitters, because that would block the busi migration on demo
  backends.
- **No value-change rerender for stable keys in this slice** — chosen to keep
  the reconciliation model simple and aligned with component instances.
  Rejected: deep row diffing, because no cross-backend row identity contract
  exists yet.

## Results

Fill after verification with exact commands and outcomes.
