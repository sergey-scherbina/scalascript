# std/ui `select` — dropdown primitive

## Overview

`std/ui` (the `.ssc` declarative widget toolkit — `runtime/std/ui/*.ssc`, lowered
by `lower.ssc` to `View` and served via `serve(lower(tree, theme), port)`) has
no `<select>`/dropdown widget. Every "pick one of N" interaction today is
hand-rolled from `textField` (free text) or `signalButton`/`rowLinkAction`
(one button per option — fine for a handful of choices, not for a real
picklist). This is the concrete gap that blocks busi's Work tab (pick the
active contract from a list) from dropping its legacy free-text-input +
copy-paste-hint-list workaround.

This is unrelated to the pre-existing `frontend/core.View.Picker` case (used
by the separate Scala-side `frontend/toolkit` `Tk` DSL and already lowered by
every `FrontendFrameworkSpi` backend — react/vue/solid/swiftui/custom). That
system is not reachable from `.ssc` source (see `specs/ssc-toolkit-v2.md` §3,
"Two mirrored widget layers... .ssc cannot reach the Scala toolkit"). This
slice adds the `.ssc`-level equivalent to `std/ui`, following the existing
`TkNode` + `lower.ssc` convention (mirrors `textField`/`checkbox`), not a new
`extern def`.

## Interface

Public API (new file additions only; no changes to existing exports):

```scalascript
// runtime/std/ui/nodes.ssc
case class SelectNode(options: List[(String, String)],
                      selected: Any,
                      label: String,
                      placeholder: String,
                      disabled: Boolean) extends TkNode

// runtime/std/ui/input.ssc
def select(options: List[(String, String)],
           selected: Any,
           label: String = "",
           placeholder: String = "",
           disabled: Boolean = false): TkNode
```

- `options` — `(value, label)` pairs, in display order. Static: built once
  when the tree is constructed (same convention as `TabBarNode.tabs`,
  `TableNode.columns`, `dataTableView`'s column/action descriptor lists — none
  of the existing "list of choices" shapes in `std/ui` are reactive either).
- `selected` — must be a `Signal[String]` (declared `Any` in the node/ctor,
  matching `checkbox(checked: Any, ...)` / `textField(value: Any, ...)`,
  since `Signal[T]` is `opaque type Signal[T] = Any`). Two-way bound: the
  initial value picks the pre-selected `<option>`; user selection writes back
  into the signal; a later `setSignal`/`selected.set(...)` elsewhere updates
  the rendered `<select>`.
- `label` — optional `<label>` above the field (matches `textField`).
- `placeholder` — optional disabled/hidden leading `<option value="">` shown
  while `selected() == ""` (a `<select>`-specific concept, distinct from
  `label`; HTML `<select>` has no native placeholder attribute).
- `disabled` — matches `textField`/`checkbox`.

No new `extern def`. `select` is a pure `.ssc` composition of the existing
`element`, `textNode`, `inputChange` primitives (`std/ui/primitives.ssc`) —
see Design.

## Behavior

- [ ] `select([("a","Alpha"),("b","Beta")], selected)` lowers without
      throwing on both the `int` and `js` conformance lanes.
- [ ] The initial value of `selected` determines which `<option>` starts
      marked `selected="true"` (verified indirectly: changing the initial
      signal value changes which option's descriptor carries `selected`).
- [ ] `placeholder`, when non-empty, adds a disabled+hidden leading option
      shown only while `selected() == ""`.
- [ ] `disabled = true` renders the `<select>` disabled and omits the change
      wiring (matches `textField`/`checkbox`'s disabled behavior).
- [ ] `label`, when non-empty, renders a `<label>` above the `<select>`
      (matches `textField`); omitted (bare `<select>`) when empty.
- [ ] Selecting a different option updates the bound `Signal[String]`
      (`inputChange`-style: an `'input'` DOM listener firing on selection,
      matching the exact convention `textField`'s `"change" -> inputChange(value)`
      already uses — the event map key is descriptive; the JS lowering for
      `EventHandler.InputChange` always wires the `'input'` DOM event
      regardless of the key string).
- [ ] `examples/frontend/std-ui/smoke-test.ssc` (the whole-toolkit smoke test)
      exercises `select` and the interpreter smoke test
      (`StdUiSmokeTest`) still prints `smoke:ok`.
- [ ] Affected `tkv2-*` / `std-ui-*` conformance cases pass before push.

## Known limitation (discovered during implementation)

`select` has three trailing defaulted parameters (`label`, `placeholder`,
`disabled`) — the natural call shape for "just disable it" is
`select(options, selected, disabled = true)`, skipping `label`/`placeholder`.
That call shape triggers a **pre-existing bug in `bin/ssc run`'s self-hosted
"standard tier" pipeline** (default, and `--v2` explicitly — a different
codebase area from the v1 interpreter), unrelated to this slice's design:
it mis-binds a named argument for a non-first trailing defaulted parameter
to the *first* defaulted parameter instead (silently — wrong value, no
error). Naming every trailing param from the first one overridden onward
(or calling fully positionally) is unaffected. Verified **not** to affect
`bin/ssc-tools run` (v1 — what `StdUiSmokeTest.scala` and this repo's own
`tests/conformance/run.sh` `int` lane actually use), `bin/ssc-tools run
--v2`, or `bin/ssc-tools emit-js` (the `js` lane / busi's `emit-spa`
production path) — all four bind correctly in every case. Filed as
`BUGS.md` § `standard-tier-named-arg-skip-default` (status: open, not fixed
here — a compiler bug in a different, actively-developed pipeline, out of
scope for a std/ui widget slice) and `BACKLOG.md` § Standard-tier compiler
correctness. Every `select(...)` call site added by this slice (examples,
smoke test, conformance test) avoids the trigger shape regardless: either
all-positional, or every trailing param named starting from the first one
overridden — e.g. `select(options, selected, label = "...", placeholder =
"...", disabled = true)`, not `select(options, selected, disabled = true)`.
Callers integrating `select` elsewhere (the busi follow-up) should do the
same as a matter of habit, and should additionally confirm which pipeline
their own `ssc`/`--v2` wrapper actually dispatches to before assuming
either way.

## Out of Scope

- **Reactive/fetched options** (`Signal[List[(String, String)]]`). busi's real
  motivating case is "dropdown of active contracts fetched from an API", but
  `std/ui` has no existing primitive that re-renders an arbitrary child list
  when a fetch signal resolves — `forKeyedView` (`primitives.ssc`) is a
  render-once snapshot in the interpreter path (`FrontendIntrinsics.forKeyedView`
  reads `rs.apply()` once and returns a `View.Fragment`); only `dataTableView`
  has genuine fetch-then-rerender wiring, and that's bespoke, table-specific
  JS runtime code, not a generic mechanism. Building true reactive options
  would mean replicating DataTable-caliber machinery — not minimal. Recorded
  as a BACKLOG follow-up (`select-from-signal`); until then, callers that
  need a fetched option list must either query it server-side before calling
  `select(...)` (busi's other full-stack pages already do this — see
  tutorial 4) or rebuild/re-serve the page after the fetch, same as any other
  static-config `std/ui` widget.
- Multi-select (`<select multiple>`).
- `<optgroup>` grouping.
- A reactive-label variant (`selectR`, mirroring `textFieldR`) — not
  requested; add if/when a locale-switch use case needs it.
- Native styling/theming beyond what `textField`/`checkbox` already do.
- Any backend other than `serve()`'s default `custom` JS frontend
  (`StaticJsEmitter`/`CustomFrameworkBackend`) and the plain interpreter
  fallback. No JVM/Swing/SwiftUI-native lowering — see Design for why this
  requires literally zero new backend code, so there is nothing
  backend-specific to add or skip for those targets either way.
- An explicit `onChange: EventHandler` parameter (considered, rejected — see
  Decisions).

## Design

### Why no new `extern def` and no `StaticJsEmitter.scala` change

`element(tag, attrs, events, children): View` (`std/ui/primitives.ssc`) is a
fully generic HTML-element builder already used by every input-family
`TkNode` (`TextFieldNode`, `CheckboxNode`, ...). Its native impl
(`FrontendIntrinsics.scala`) produces `frontend.core.View.Element(tag, attrs,
events, children)`, and `frontend/custom/StaticJsEmitter.scala` already
handles `View.Element` generically (`compile`, line ~294): attrs are decoded
by shape (`AttrValue.Bool` → JS property assignment, `AttrValue.Reactive` →
initial property set + signal subscription, ...) and `events` dispatch
through `EventHandler`'s own case (`InputChange`, `ToggleSignal`, ...) — not
by tag name. A `<select>` is therefore just another tag: no new `View` case,
no new emitter branch, no new native intrinsic. `select` composes existing
primitives entirely in `.ssc`, so it is automatically supported on **both**
conformance lanes (`int` — the tree-walking interpreter, and `js` — the
`v1/runtime/backend/js` `JsGen` static compiler) with zero backend changes,
verified empirically (see Results) by lowering a `<select>`-shaped tree
through both `bin/ssc run` and `bin/ssc-tools emit-js` + `node`.

### The `<select>`-specific ordering wrinkle

`View.Element`'s generic codegen processes `attrs` (including a `Reactive`
binding on `"value"`) and `events` **before** appending `children`
(`StaticJsEmitter.compile`, `View.Element` case). For an `<input>` this order
doesn't matter. For a `<select>`, setting `.value` before any `<option>`
children exist is a no-op per the DOM spec (`HTMLSelectElement.value`'s
setter only matches against *currently existing* options) — the browser
would silently fall back to auto-selecting the first `<option>` instead of
the bound signal's value. `select` avoids a `StaticJsEmitter` change (which
would touch every other widget's ordering) by computing the correct initial
selection **per-option**, at `lower()` time: read `selected()` once (a plain,
already-established `.ssc` idiom — see `eqSignal`/`computedSignal`'s
`sig()`-inside-a-thunk docs, and `tests/conformance/tkv2-textfield-reactive-label.ssc`'s
bare `label()` read outside any thunk) and mark the matching `<option>`
`"selected" -> true` directly (order-independent: a per-option HTML
attribute, not a post-hoc `.value` assignment). The generic `"value" ->
selected` `Reactive` attr binding is *also* kept on the `<select>` itself,
solely for **later** signal changes (e.g. some other part of the app calling
`setSignal(selected, ...)`): by the time any later change fires, the options
already exist in the DOM, so the existing generic subscription
(`subs.add(nv => $v.value = nv)`) behaves correctly. Net: correct on first
render and correct on every subsequent change, with a single well-understood,
documented reason for the extra per-option `"selected"` attribute.

Verified empirically with a standalone probe mirroring the exact lowering
shape (`bin/ssc run` and `bin/ssc-tools emit-js` + `node`, both green before
this landed in `lower.ssc`).

### Lowering sketch (`lower.ssc`)

```scalascript
case SelectNode(options, selected, label, placeholder, disabled) =>
  val sp   = theme.spacing
  val base = s"font-size:${theme.typography.body.fontSize}px; font-family:${theme.typography.body.fontFamily}; " +
             s"padding:${sp.sm}px ${sp.sm}px; border:1px solid ${theme.colors.muted}; " +
             s"border-radius:${theme.radii.sm}px; width:100%; box-sizing:border-box; background:#fff"
  val style = if disabled then base + "; background:#f3f4f6; color:#9ca3af; cursor:not-allowed" else base + "; cursor:pointer"
  val cur   = selected()
  val placeholderOpt =
    if placeholder == "" then []
    else [element("option",
           ["value" -> "", "selected" -> (cur == ""), "disabled" -> true, "hidden" -> true],
           Map(), [textNode(placeholder)])]
  val optionEls = options.map { (value, optLabel) =>
    element("option", ["value" -> value, "selected" -> (cur == value)], Map(), [textNode(optLabel)])
  }
  val selectEl = element("select",
    ["style" -> style, "disabled" -> disabled, "value" -> selected],
    if disabled then Map() else ["change" -> inputChange(selected)],
    placeholderOpt ++ optionEls)
  // + label wrapper, matching TextFieldNode's div/label/field shape
```

Implementation points:

- `runtime/std/ui/nodes.ssc` — `SelectNode`.
- `runtime/std/ui/input.ssc` — `select(...)`.
- `runtime/std/ui/lower.ssc` — the `SelectNode` case + `SelectNode` added to
  the `nodes.ssc` import list.
- `examples/frontend/std-ui/smoke-test.ssc` — exercise `select` alongside the
  other input widgets.
- `examples/frontend/select-demo/select-demo.ssc` — standalone runnable
  example (busi's actual shape: pick a contract, show the selection live).
- `tests/conformance/tkv2-select.ssc` (+ `expected/tkv2-select.txt`).
- `README.md` (capabilities table rows), `docs/user-guide.md` §17.5 widget
  catalog.

No changes needed (see above) in `FrontendIntrinsics.scala`,
`StaticJsEmitter.scala`, `CustomFrameworkBackend.scala`, `JsGen.scala`, or any
JVM/Swing/SwiftUI native-frontend lowering.

## Decisions

- **`.ssc`-level composition over a new `extern def`** — chosen because
  `element`/`textNode`/`inputChange` already cover everything a `<select>`
  needs, and every backend that matters (interpreter + the `custom` JS
  emitter, both conformance lanes) already implements those. Rejected: a new
  `extern def select(...)` (the task's initial sketch) — it would require a
  matching native impl in `FrontendIntrinsics.scala` *and* a new
  `frontend.core.View` case handled by every `FrontendFrameworkSpi` emitter
  (react/vue/solid/swiftui/custom) to stay exhaustive, all to duplicate what
  `element()` already does generically. Matches design principle #1
  ("reuse, don't invent") and the `tkv2-raw-html` precedent (sentinel over
  new `View` case, for the same reason: avoid emitter exhaustiveness churn).
- **Static `List[(String, String)]` options, not `Signal[List[...]]`** —
  chosen because no existing `std/ui` mechanism actually re-renders an
  arbitrary child list reactively (see Out of Scope); a `Signal[List[...]]`
  parameter would silently promise reactivity the render path can't deliver.
  Matches `TabBarNode.tabs: List[Tab]` (the closest existing precedent: a
  list of choices + one "current" `Any`/signal field). Rejected: an overload
  accepting both — until reactive options are real, a second overload would
  just be a same-behavior alias.
- **Per-option `"selected"` attribute, read once via bare `selected()`** —
  chosen to make first-render correct without touching
  `StaticJsEmitter.scala`'s generic (and shared-by-every-other-widget)
  attrs-before-children ordering. Rejected: reordering `View.Element`'s
  codegen to append children before attrs/events — global, unrelated blast
  radius for a `<select>`-only problem. Rejected: a bespoke `View.Select`
  case with hand-written JS (the `frontend/core`/`View.Picker` pattern) — see
  first decision.
- **No `onChange: EventHandler` parameter** — the task's initial interface
  sketch included one. Rejected in favor of matching `checkbox`/`textField`'s
  exact shape (hardcoded `inputChange`/`toggleSignal`, no raw-handler
  escape hatch) — `select` is a two-way-bound *value* widget, not a
  generic-action widget like `actionButton`. "Do something when the
  selection changes" is already an established idiom via a derived signal
  (`computedSignal`/`fetchUrlSignalTo` keyed off `selected`, exactly as
  `fetchActionTo`'s own docs already show for a `docUrlSig`), not a
  side-channel callback on the widget itself.
- **`label` and `placeholder` as two separate optional params** — chosen
  because they're genuinely different concepts (a field caption above the
  control vs. a disabled "nothing picked yet" option inside it); collapsing
  them the way `TextFieldNode` conflates `label` into both the visible
  `<label>` *and* the `<input placeholder>` would be wrong for `<select>`
  (there is no native placeholder attribute to piggyback on).

## Results

Implemented in `84187250b`.

What landed:

- `std.ui.nodes.SelectNode` and public `std.ui.input.select(options, selected,
  label, placeholder, disabled): TkNode`.
- `lower.ssc` lowers `SelectNode` to a real `<select>` with `<option>`
  children (plus an optional leading placeholder option and an optional
  wrapping `<label>`), reusing `element`/`textNode`/`inputChange` — no new
  `extern def`, no changes to `FrontendIntrinsics.scala`,
  `StaticJsEmitter.scala`, `CustomFrameworkBackend.scala`, or `JsGen.scala`.
- `examples/frontend/std-ui/smoke-test.ssc` exercises `select` alongside the
  other input widgets.
- `examples/frontend/select-demo/select-demo.ssc` — runnable example (pick a
  contract from a static list; a `computedSignal` derives and displays the
  picked label reactively; a second, disabled `select` demonstrates the
  disabled render).
- `tests/conformance/tkv2-select.ssc` (+ `expected/tkv2-select.txt`).
- `BUGS.md` § `standard-tier-named-arg-skip-default` + `BACKLOG.md` §
  Standard-tier compiler correctness — a bug found in `bin/ssc run`'s
  self-hosted standard-tier pipeline while building this slice (named args
  that skip a non-first trailing default mis-bind); confirmed **not** to
  affect `bin/ssc-tools run` (v1), `bin/ssc-tools run --v2`, or
  `bin/ssc-tools emit-js` — not this repo's own conformance/test harness.
  Not fixed here (out of scope); every `select(...)` call site in this
  slice avoids the trigger shape.

Verification:

- `scripts/sbtc "installBin"` passed.
- `tests/conformance/run.sh --only 'tkv2-select'` — **PASS [INT], PASS
  [JS]** (JVM correctly SKIP — `backends: [int, js]`).
- `tests/conformance/run.sh --only 'tkv2-*,std-ui-*' --no-memo` — **20
  passed, 0 failed** (no regressions in the sibling toolkit-v2/std-ui
  suite).
- `scripts/sbtc 'backendInterpreterServer/testOnly scalascript.StdUiSmokeTest'`
  — both tests green (`smoke:ok`, `lower-idempotent:ok`).
- `bin/ssc-tools run examples/frontend/select-demo/select-demo.ssc` served a
  real page; fetched the emitted `app.js` and confirmed by inspection:
  `document.createElement('select')`, one `document.createElement('option')`
  per entry (+ the placeholder), the option matching the signal's initial
  value marked `.selected = true` and every other option `false`, `.value`
  set + subscribed for future updates, and an `'input'` listener wired to
  `__setSignal`. The disabled variant showed `.disabled = true` and no event
  listener, matching `textField`/`checkbox`'s existing convention.
- `git diff --check` passed.

Follow-up recorded in `BACKLOG.md`: `select-from-signal` (reactive
`Signal[List[(String,String)]]` options — needs DataTable-caliber
fetch+re-render wiring, deliberately out of scope here) and
`standard-tier-named-arg-skip-default` (the bug above).
