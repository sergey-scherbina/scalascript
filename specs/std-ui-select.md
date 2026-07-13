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

- **Reactive/fetched options** (`Signal[List[(String, String)]]`) for the
  BASE `select()` above — that primitive's `options` stays a static
  `List[(String, String)]`, unchanged. A separate, additive variant
  (`selectFrom`) now covers the reactive case — see § "Reactive options
  (`selectFrom`)" below. `select()` itself is not touched.
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

---

## Reactive options (`selectFrom`)

Follow-up to the base `select()` above, closing the `select-from-signal`
BACKLOG entry. `select()` stays exactly as documented above — this is a new,
additive sibling for the case the base slice deferred: a `<select>` whose
`<option>` list tracks a `Signal[List[T]]` (busi's motivating case: a
dropdown of active contracts, fetched from an API and re-fetched when the
owner signs a new one — an item can be **added, removed, or reordered**
after the page has already rendered).

### Why the base `select()`'s own mechanism can't just take a `Signal`

Simply changing `select`'s `options` parameter from `List[(String,String)]`
to `Signal[List[(String,String)]]` would not deliver reactivity — it would
only defer the "static snapshot" from tree-construction time to first-render
time. Something has to actually **subscribe** to the signal and patch the
DOM when it changes. `std/ui` already has exactly one primitive that does
this generically for a list: `forKeyedView`/`KeyedForNode`
(`primitives.ssc`/`nodes.ssc`, lowered by `lower.ssc`'s
`case KeyedForNode(items, key, render) => forKeyedView(items, key, ...)`).
Its two implementations split cleanly by backend:

- **Interpreter fallback** (`FrontendIntrinsics.scala`, `int` conformance
  lane, and `bin/ssc-tools run`'s live-`serve()` path): snapshot-only — reads
  `rs.apply()` once and returns a `View.Fragment`. Confirmed by reading
  `serve`'s own native impl (`QualifiedName("serve")` in
  `FrontendIntrinsics.scala`): it evaluates the whole `View` tree via the
  interpreter (erasing any per-list reactivity into a flat `Fragment`)
  *before* handing it to `uiEmitToTempDir`/the `custom` JS emitter — so a
  page served via `bin/ssc-tools run` is **never** reactive to a keyed
  list's later changes, no matter what, matching this comment in
  `FrontendIntrinsics.scala`: *"Dynamic keyed reconciliation is implemented
  in the JS emit-spa runtime where the original render callback remains
  available in the browser."*
- **The real reactive mechanism** lives entirely in
  `v1/runtime/backend/js/src/main/resources/scalascript/js-runtime/signals.mjs`
  — the runtime prelude that `bin/ssc-tools emit-js`
  (`JsGen.scala`'s static `.ssc`→JS compiler, the "js" conformance lane, and
  busi's production `emit-spa` build) embeds verbatim into every emitted
  bundle. `_ssc_ui_forKeyedView(items, key, render)` returns a
  `{_type:'_ForKeyed', ...}` marker; `_ssc_ui_renderBody`'s `walk()` renders
  each row wrapped in `<span data-ssc-key="..." style="display:contents">`
  inside an outer `<span data-ssc-forkeyed="seq">` container; `_ssc_ui_mount`
  subscribes to the `items` signal and, on change, **reconciles by key**:
  builds a map of existing DOM children keyed by `data-ssc-key`, then for
  each new-list item either reuses the existing node (preserving its exact
  DOM identity — the target this task's proof leans on) or creates a fresh
  one, appends in the new order, and removes any node whose key dropped out.
  This is proven live by `JsRuntimeKeyedForTest.scala`
  (`v1/runtime/backend/interpreter/src/test/...`): reorder/remove/insert all
  preserve untouched rows' exact DOM node identity.

This confirms the premise stated in the task brief: `forKeyed`/`KeyedForNode`
already has real, fine-grained reactive DOM reconciliation — for busi's
production pipeline (`emit-spa`/`emit-js`), not for `bin/ssc-tools run`'s
live-interpreter `serve()` path. `selectFrom` **reuses this same key-based
reconcile algorithm** (existing-by-key map → keep-or-create → append in new
order → remove stale) rather than inventing new list-diffing logic.

### Why it can't be `forKeyedView` embedded as a `<select>` child, unmodified

`forKeyedView`'s wrapper shape — a `<span data-ssc-key>` around each row,
inside an outer `<span data-ssc-forkeyed>` — works for arbitrary containers
(`<div>`, `<ul>`, ...) but **not** inside `<select>`. Per the HTML Living
Standard's "in select" insertion mode, when the parser encounters a start
tag it doesn't recognise (anything other than `option`/`optgroup`/`hr`/
`script`/`template`/`select`), the rule is "parse error; ignore the token" —
the tag is dropped, not treated as an element. Assigning
`<select><span data-ssc-key="a"><option value="a">A</option></span>...
</select>` via `.innerHTML` (which is exactly how `_ssc_ui_renderBody`'s
output reaches the DOM) makes a real browser silently drop **both** the
opening and closing `<span>` tags, flattening the `<option>` to a direct
child of `<select>` — the visible content is fine, but the `data-ssc-key`
attribute the reconcile algorithm needs was on the now-vanished span, not
the option. Reusing `forKeyedView` verbatim as a `<select>` child would
"work" only in a naive Node-mock DOM that does not enforce HTML5 content
models (like the fake DOM in `JsRuntimeKeyedForTest.scala`) and silently
break reconciliation in every real browser.

The fix: the key must live directly on the `<option>` element (a `data-*`
attribute is always legal there), and the reconcile *container* must be the
`<select>` element itself — the only element in this shape that can validly
carry a marker attribute at all (an `<optgroup>` wrapper was considered and
rejected — see Decisions). Since a container marker attribute has to sit on
the `<select>` tag itself, and the tag's own attrs/events are otherwise
generic (`element()`-composed), the cleanest way to guarantee that
coexistence is a **single dedicated View-level construct that owns the
whole `<select>` element** — attrs, events, and reactive option children
together — rather than composing `forKeyedView`'s existing View marker as
one child inside a generic `element("select", ...)` call. This exactly
mirrors the existing `dataTableView`/`_DataTableView` precedent (a bespoke,
self-contained widget-level View case with its own fetch-and-rerender
wiring, not composed from generic primitives) — not a new architectural
pattern, an existing one applied to a second widget.

### Interface

```scalascript
// runtime/std/ui/nodes.ssc
case class SelectFromNode[A](items: Any,
                             key: A => String,
                             optionFn: A => (String, String),
                             selected: Any,
                             label: String,
                             placeholder: String,
                             disabled: Boolean) extends TkNode

// runtime/std/ui/input.ssc
def selectFrom[A](items: Signal[List[A]],
                  key: A => String,
                  optionFn: A => (String, String),
                  selected: Any,
                  label: String = "",
                  placeholder: String = "",
                  disabled: Boolean = false): TkNode

// runtime/std/ui/primitives.ssc — new extern (the only new one this slice adds)
extern def selectFromView[A](items: Signal[List[A]],
                             key: A => String,
                             optionFn: A => (String, String),
                             selected: Signal[String],
                             style: String,
                             placeholder: String,
                             disabled: Boolean): View
```

- `items` — `Signal[List[A]]`, the reactive source (e.g. a
  `fetchUrlSignalTo`-backed contracts list, or any plain `signal(...)`
  mutated later via `.set(...)`/`setSignal`).
- `key: A => String` — stable per-item identity for reconciliation, exactly
  matching `forKeyedView`/`KeyedForNode`'s own `key` parameter (same
  convention: `item => item.id`-shaped).
- `optionFn: A => (String, String)` — maps each item to the `(value, label)`
  pair the base `select()` takes statically per-option. Kept as a narrow
  `(String, String)`-returning function (not `A => View`, which is
  `forKeyedView`/`KeyedForNode`'s own shape) deliberately — see Decisions.
- `selected`, `label`, `placeholder`, `disabled` — identical semantics to
  the base `select()` (two-way-bound `Signal[String]`, optional caption,
  optional placeholder option, disabled render omits change wiring).
- `style` (the `selectFromView` extern only, not user-facing) — the
  pre-computed CSS string; `lower.ssc` computes it the same way `SelectNode`
  does today (theme-driven, disabled variant included) and passes the
  finished string down, keeping the extern theme-agnostic.

### Behavior

- [ ] `selectFrom(items, key, optionFn, selected)` lowers without throwing
      on both the `int` and `js` conformance lanes.
- [ ] Initial render: one `<option>` per current item in `items()`, in list
      order, the one matching `selected()` marked as the current selection.
- [ ] `placeholder`/`label`/`disabled` behave identically to the base
      `select()` (same rendering, same omitted-change-wiring-when-disabled
      rule).
- [ ] **`js` lane only** (see "Why the base `select()`'s own mechanism
      can't just take a `Signal`" above — the interpreter/`int` lane and
      `bin/ssc-tools run` are snapshot-only, matching `forKeyedView`'s own
      asymmetry): after `items.set(...)`/`setSignal(items, ...)` with an
      appended, removed, or reordered list, the rendered `<select>`'s
      `<option>` children update to match, without a page reload, and an
      `<option>` whose key survives the change keeps its exact DOM node
      identity (not torn down and recreated).
- [ ] Selecting a different option still updates the bound `selected`
      signal (same `inputChange` wiring as the base `select()`).
- [ ] A runnable example under `examples/frontend/` demonstrates N options,
      then an action that appends one to the source list, then N+1 options
      — verified via `emit-js` + Node (not `bin/ssc-tools run`, see above).
- [ ] A dedicated regression test (mirroring
      `JsRuntimeKeyedForTest.scala`'s method: the real `signals.mjs` runtime
      executed under real Node, not a conformance `.ssc` file — conformance
      has no DOM available in either lane) proves the reconciliation is
      real: initial N options, then an appended item, asserting N+1
      `<option>`s with the original N nodes' DOM identity preserved.
- [ ] Affected `tkv2-*` conformance cases pass before push.

### Design

#### The `<select>`-specific ordering wrinkle still applies, solved the same way as before, plus a rebuild-time re-application

Same underlying issue as the base `select()`'s "ordering wrinkle" (setting
`.value` before `<option>`s exist is a DOM no-op) — but now it recurs every
time the option list is rebuilt, not only once at first render, because a
list change replaces some `<option>` nodes. `selectFromView`'s JS-runtime
reconcile function (mirroring `_mountKeyed`'s `reconcile`) sets
`select.value = <selected signal's current value>` as the *last* step of
every reconcile pass, after all `<option>` nodes for that pass already exist
as real DOM children — the browser's `.value` setter then matches correctly
regardless of whether this is the first pass (mount-time, immediate) or a
later one (an actual list change). The base `select()`'s per-option
`"selected"` HTML attribute trick is also kept at initial-HTML-string-render
time (`_ssc_ui_renderBody`'s `_SelectFrom` case), for the same "correct even
before any JS mounts" reason the base widget has it.

#### Container/key placement (the concrete DOM shape)

- `_ssc_ui_renderBody`'s new `_SelectFrom` case emits
  `<select data-ssc-forkeyed-options="<seq>" style="..." ...>` — the marker
  attribute lives directly on the `<select>` tag (not a wrapper), reusing
  the same shared `keyed` array / `seq` numbering `_ForKeyed` already uses
  (dispatched by `_type` inside `_mountKeyed`, so the existing call sites
  and `_ssc_ui_mount(sigs, keyedRoots)` signature are untouched).
- Each option renders as `<option value="..." data-ssc-key="...">label
  </option>` — the key attribute directly on the option, no wrapper.
- `_mountKeyed` gains one dispatch branch: when `kv._type === '_SelectFrom'`,
  hand off to `_mountSelectFrom`, which finds the `<select>` via
  `[data-ssc-forkeyed-options="seq"]`, subscribes to `items`, and reconciles
  by the *identical* algorithm shape as `_mountKeyed`'s existing `reconcile`
  (existing-by-`data-ssc-key` map on `select.children` → keep-or-create via
  `document.createElement('option')` → `select.appendChild` in new order →
  remove children whose key dropped out), then sets `select.value`. A
  non-keyed placeholder `<option>` (no `data-ssc-key`) is untouched by
  reconcile exactly as `_mountKeyed`'s own existing-children scan already
  skips any child without the attribute — same mechanism, not a new rule.

#### Backend scope

Two backends implement `selectFromView`: the interpreter fallback
(snapshot, parity with `forKeyedView`'s own `int`-lane behavior) and the JS
runtime (`signals.mjs`, real reactivity). No JVM/Swing/SwiftUI-native
lowering — unlike the base `select()`, this is not "zero new backend code
needed" (a brand-new `extern def` is inherently backend-specific until each
backend picks it up), but no existing test exercises `select`/`selectFrom`
on those backends either; `tests/conformance` cases declare
`backends: [int, js]`, matching the base slice's own precedent exactly.

### Decisions

- **A dedicated `_SelectFrom` View-level construct (mirroring
  `dataTableView`), not `forKeyedView` embedded as a child of a generic
  `element("select", ...)`** — chosen because `<select>`'s HTML content
  model rejects a wrapping element around each option (see "Why it can't be
  `forKeyedView` embedded... unmodified" above); the reconcile container
  marker has nowhere legal to live except the `<select>` tag itself, which
  requires the widget to own that tag's construction end-to-end. Rejected:
  reusing `forKeyedView` unmodified (breaks in real browsers, only "works"
  in a content-model-naive DOM mock); reusing `_ForKeyed`'s existing View
  marker/JS functions in place with a special "no wrapper" mode threaded
  through (would entangle the option-list-only special case into the
  general-purpose list mechanism every other `KeyedForNode` caller depends
  on — higher blast radius for no benefit, since the *algorithm* is what's
  shared, and mirroring a small, self-contained function pair costs less
  than a conditional inside the shared one).
- **An `<optgroup>` wrapper (rejected)** — `<optgroup>` IS legal inside
  `<select>` and can carry `data-*` attributes, so wrapping the whole
  reactive option group in one unlabeled `<optgroup>` was considered as an
  alternative to a bespoke View case. Rejected: an empty/unlabeled
  `<optgroup>` is a real, semantically-visible grouping construct to
  assistive technology (unlike the harmlessly-dropped generic `<span>`
  wrapper elsewhere) — introducing one purely as an implementation-detail
  DOM anchor risks a screen reader announcing a spurious group for every
  reactive select, which is a worse trade than the small amount of new
  JS-runtime code the dedicated-View-case approach costs instead.
- **`optionFn: A => (String, String)`, not `A => View`** — `forKeyedView`/
  `KeyedForNode`'s own `render: A => View` is fully generic (any child
  markup per row), which is right for a general list but wrong here:
  `<option>`'s content model is text-only, so a generic `A => View` render
  function would silently invite call sites to build arbitrary nested
  markup that can never legally render inside `<option>` in the first
  place. Narrowing to `(String, String)` (matching the base `select()`'s
  per-option shape exactly) makes the illegal case unrepresentable instead
  of merely undocumented.
- **`key: A => String` kept as a separate parameter from `optionFn`** (not
  derived from the `value` half of the pair) — mirrors
  `forKeyedView`/`KeyedForNode`'s own separation of key and render, and
  keeps key stability independent of what's displayed (a caller can key by
  a stable internal id while `optionFn`'s value differs, or the option
  label changes without changing the key).
- **`items`/`selected` typed `Any` in the node/ctor, matching `SelectNode`**
  — same reasoning as the base spec's own `selected: Any` decision (opaque
  `Signal[T] = Any`).
- **No changes to the base `select()`/`SelectNode`/`select` at all** —
  strictly additive; existing callers are unaffected.

### Results

Spec landed in `b614ae3b9`; implementation in `9061aa26a`; the found,
unrelated `custom`-backend bug tracked in `2d6a2b43d`.

What landed:

- `std.ui.nodes.SelectFromNode[A]` and public
  `std.ui.input.selectFrom[A](items, key, optionFn, selected, label,
  placeholder, disabled): TkNode`.
- `std.ui.primitives.selectFromView[A]` — the one new `extern def` this
  slice adds — plus its interpreter/JVM fallback (`FrontendIntrinsics.scala`,
  snapshot-only, parity with `forKeyedView`'s own `int`-lane behavior) and
  its real reactive implementation in the JS runtime (`signals.mjs`):
  `_ssc_ui_selectFromView`, a `_SelectFrom` case in `_ssc_ui_renderBody`
  (emits `<select data-ssc-forkeyed-options="seq">` with keyed `<option
  data-ssc-key="...">` children directly, no wrapper), and `_mountSelectFrom`
  (dispatched from the existing `_mountKeyed` by `_type`, reusing the exact
  same reconcile algorithm shape).
- `lower.ssc`'s `SelectFromNode` case, mirroring `SelectNode`'s own
  style-computation and label-wrapper shape.
- `examples/frontend/std-ui/smoke-test.ssc` exercises `selectFrom` alongside
  the other input widgets.
- `examples/frontend/select-reactive-demo/select-reactive-demo.ssc` —
  runnable example: 2 contracts, a button simulating "sign a new contract"
  (appends a 3rd to the same reactive source), built via `emit-js`/
  `emit-spa` (documented in the example itself — `ssc run`'s live-`serve()`
  path is snapshot-only for this widget, same as any other
  `forKeyedView`-backed one).
- `tests/conformance/tkv2-select-reactive.ssc` (+ expected) — API-level
  check on `[int, js]`.
- `v1/runtime/backend/interpreter/src/test/scala/scalascript/JsRuntimeSelectFromTest.scala`
  — the real reactivity proof: the actual `signals.mjs` runtime
  (`JsGen.generateRuntime`) executed under real Node against a DOM mock
  (mirroring `JsRuntimeKeyedForTest.scala`'s method). Scenario: build a
  `<select>` from a 2-item `Signal[List[Contract]]`; assert the container is
  a real `<select>` whose children are real `<option>` tags; append a 3rd
  item via `setSignal` (simulating the click handler) and assert 3
  `<option>`s with the original 2 nodes' *exact* DOM identity preserved (not
  torn down and recreated); reorder and assert identity again; remove one
  and assert it is actually detached (`parentNode === null`) while survivors
  keep identity; finally select a different option and assert the bound
  `selected` signal updates.
- `BUGS.md` § `custom-jsemitter-signal-list-literal` — a **pre-existing**,
  unrelated bug found while building this slice:
  `frontend/custom/StaticJsEmitter.scala`'s `jsLiteral` has no `List`/`Seq`
  case, so any program with a `Signal[List[_]]` referenced by an event
  handler crashes `ssc run` (both the default v2-VM/`custom`-frontend path
  and `--v1`) — confirmed to already affect the previously-shipped
  `examples/frontend/keyed-for-demo/keyed-for-demo.ssc` too (not new, not
  fixed here, out of scope). `emit-js`/`emit-spa` (the production
  static-compile pipeline, and what actually matters for reactive lists) are
  unaffected — confirmed empirically for both that example and this slice's
  own `select-reactive-demo.ssc`.
- `README.md` capabilities-table row + `docs/user-guide.md` §17.5 widget
  catalog row + runnable-example pointer.

Verification:

- `scripts/sbtc "frontendPlugin/compile"` and `scripts/sbtc "backendJs/compile"`
  — both clean.
- `scripts/sbtc "installBin"` passed.
- `tests/conformance/run.sh --only 'tkv2-select-reactive'` — **PASS [INT],
  PASS [JS]** (JVM correctly SKIP — `backends: [int, js]`).
- `tests/conformance/run.sh --only 'tkv2-select*,std-ui-*,tkv2-keyed-for' --no-memo`
  — **10 passed, 0 failed** (no regressions in the sibling
  toolkit-v2/std-ui/keyed-for suite).
- `scripts/sbtc 'backendInterpreterServer/testOnly scalascript.StdUiSmokeTest'`
  — both tests green (`smoke:ok`, `lower-idempotent:ok`) with `selectFrom`
  now exercised in the tree.
- `scripts/sbtc 'backendInterpreter/testOnly scalascript.JsRuntimeSelectFromTest'`
  — green: the real-reactivity proof described above.
- `scripts/sbtc 'backendInterpreter/testOnly scalascript.JsRuntimeKeyedForTest'`
  — still green (no regression to the shared `_mountKeyed` dispatch point).
- `bin/ssc-tools run --v1` / `emit-js` + `node` on a standalone probe and on
  `tests/conformance/tkv2-select-reactive.ssc` — identical output on both
  lanes, no throw.
- `bin/ssc-tools emit-spa examples/frontend/select-reactive-demo/select-reactive-demo.ssc`
  — succeeds, embeds `_ssc_ui_selectFromView`/`_SelectFrom`/`_mountSelectFrom`
  in the bundle.
- `git diff --check` passed.

Follow-up: none recorded — `select-from-signal` is closed by this slice.
`custom-jsemitter-signal-list-literal` (the found bug above) remains open,
tracked in `BUGS.md`, and is intentionally not fixed here.
