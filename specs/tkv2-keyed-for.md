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
- The helper lives in `std/ui` and is exported from the same index used by
  toolkit-v2 consumers.
- Keys are caller-owned stable strings. Duplicate keys are unsupported; the
  browser runtime keeps the last rendered row for a duplicate key and emits no
  extra API.
- The initial implementation targets the production `emit-spa` custom runtime.
  Other framework emitters may continue to lower through ordinary
  `View.ForSignal` until they opt into keyed reconciliation.

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
- Re-rendering an existing row solely because the item value changed while its
  key stayed the same. The first implementation treats stable keys as stable
  row instances; row-local signals carry mutable state.

## Design

Add a frontend-core node that carries:

1. the backing `ReactiveSignalList`,
2. a key extractor available to the Scala-side emitter as `Any => String`, and
3. the row template.

The custom emitter registers the list just like `View.ForSignal`, emits an
initial keyed container, and generates a row renderer that returns one DOM node
per item. The subscription handler builds a `Map(key -> existingNode)`, creates
nodes for new keys, appends nodes in the new order, and removes nodes whose keys
disappeared. Browser `appendChild` moves an existing node, so reordering is a
move rather than a recreate.

The existing template-compilation path for `View.ForSignal` should be reused so
`View.ItemText` and `EventHandler.RemoveSelfFromList` keep working inside keyed
rows. Dynamic event binding must remain local to the generated row renderer, so
newly inserted rows receive the same listeners as initial rows.

## Decisions

- **Add an explicit keyed node** — chosen because `View.ForSignal` has shipped
  wipe/rebuild semantics and changing it globally would risk unrelated UI
  behavior. Rejected: silently making every `ForSignal` keyed by stringified
  item, because list values are not guaranteed unique or stable.
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
