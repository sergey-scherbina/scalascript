# std/ui `hstack` `wrap` — optional flex-wrap for the horizontal-row layout primitive

## Overview

`std/ui` (the `.ssc` declarative widget toolkit — `v1/runtime/std/ui/*.ssc`,
lowered by `lower.ssc` to `View` and served via `serve(lower(tree, theme),
port)`) has a horizontal-row constructor, `hstack` (`v1/runtime/std/ui/
layout.ssc`), that lowers to a `<div>` with `display:flex;
flex-direction:row`. `lower.ssc` never sets a `flex-wrap` property, so the
row defaults to CSS's own default, `flex-wrap: nowrap` — every child stays
on one line, shrinking or overflowing when the row is wider than its
container instead of wrapping to a new line.

This is the concrete gap behind a busi navigation-bar problem: busi's
owner-facing app has up to 15 nav buttons with varying Cyrillic label
widths (from `"Сейф"` ~48px to `"Корпоративное закрытие"` ~171px). Busi
currently hand-groups these into fixed-size `hstack` rows (e.g. "always
exactly 4 per row"), which either overflows when a group's labels are too
wide, or leaves rows visibly ragged in total width when a group happens to
be short. The owner wants a real flowing layout instead — list every button
in natural order, in one `hstack`, and let the browser wrap left-to-right
based on available width, the same way `flex-wrap: wrap` (or word-wrap in a
paragraph) works. This slice adds that ability to `hstack`; it does **not**
touch busi's nav markup — that is a separate, busi-side follow-up once this
slice's pin is bumped (out of scope here, see below).

This is the same kind of small, additive, JS-custom-frontend-only slice as
`std-ui-button-variant` / `std-ui-button-size` (`specs/std-ui-button-variant.md`,
`specs/std-ui-button-size.md`) — mirrors its scope discipline: minimal,
additive, no backend churn beyond what's needed, one boolean, one primitive.

## Interface

Public API (parameter addition only; no signature removals, no renames):

```scalascript
// v1/runtime/std/ui/nodes.ssc — `wrap: Boolean` appended as the new LAST field
case class HStackNode(gap: Int, children: List[TkNode], wrap: Boolean) extends TkNode

// v1/runtime/std/ui/layout.ssc — `wrap: Boolean = false` appended to the
// FIRST (non-curried) parameter group, before the curried `children*` group
// (children stays last/curried so `hstack(gap = 8, wrap = true)(a, b, c)`
// reads the same as every existing `hstack(gap = N)(children*)` call).
def hstack(gap: Int = 0, wrap: Boolean = false)(children: TkNode*): TkNode =
  HStackNode(gap, children.toList, wrap)
```

- `wrap` — a plain `Boolean`, default `false`. `false` (the default)
  preserves today's `nowrap` render byte-for-byte — every existing call
  site across this repo (`examples/`, `tests/conformance/`, `v1/runtime/
  std/ui/i18n.ssc`'s `localeSwitcher`, busi) uses `hstack(gap = N)(...)`
  with no `wrap` argument and is therefore unaffected.
- `true` sets `flex-wrap: wrap` on the emitted `<div>`, so children flow
  onto additional lines once a row's children exceed the container's
  available width — real browser-computed wrapping, not a
  pre-computed/pre-grouped row split.
- Parameter position: `wrap` is the **second** parameter in `hstack`'s
  first (non-curried) parameter group, i.e. **before** the curried
  `(children: TkNode*)` group — matching the task brief's required
  signature `def hstack(gap: Int = 0, wrap: Boolean = false)(children:
  TkNode*): TkNode`. This differs from the button-`variant`/`size`
  precedent (which appended their new param to a single, non-curried
  parameter list); `hstack` already curries `children` into its own group,
  so `wrap` naturally joins `gap` in the first group rather than being
  appended after the curried group (which Scala's syntax doesn't allow
  applied to a case-class positional field the way `variant`/`size` did to
  `disabled`).
- On the node (`HStackNode`), `wrap` is still appended as the **last**
  field (after `children`) — matching the button-node precedent's "always
  append, never insert" convention, since `children` is already
  conventionally the trailing field on every stack node and moving it would
  break more pattern matches than adding `wrap` after it.

No new `extern def`. `hstack` remains a pure `.ssc` composition of the
existing `element()` primitive (`v1/runtime/std/ui/primitives.ssc`) — see
Design.

## Behavior

- [ ] `hstack(gap = 8)(a, b, c)` (no `wrap` argument) lowers to the exact
      same `style` string it produced before this slice — backward
      compatibility, verified by a byte-for-byte diff against the
      pre-slice `lower.ssc` output for a no-`wrap` call.
- [ ] `hstack(gap = 8, wrap = true)(a, b, c)` lowers to a `<div>` whose
      `style` attribute contains `flex-wrap:wrap` in addition to the
      existing `display:flex; flex-direction:row; align-items:center;
      gap:8px; box-sizing:border-box`.
- [ ] `wrap = false` explicitly passed behaves identically to omitting
      `wrap` entirely (both take the `false` branch — no separate code
      path for "explicit false" vs. "defaulted false").
- [ ] `flex-wrap: wrap` is the only new CSS property; `gap` continues to
      apply uniformly as both row-gap and column-gap (flexbox `gap`'s own
      default behavior), giving even spacing between wrapped lines with no
      extra `align-content` parameter needed (see Design — "No
      `align-content` parameter").
- [ ] Lowering does not throw on either the `int` or `js` conformance lane
      for `wrap = true`, `wrap = false`, or the no-argument default.
- [ ] `v1/runtime/std/ui/i18n.ssc`'s `localeSwitcher` (the one existing
      internal caller that constructs `HStackNode` directly rather than via
      `hstack(...)`) is updated to pass the new field and its rendered
      output stays byte-identical (still `wrap = false`).
- [ ] `examples/frontend/hstack-wrap/hstack-wrap.ssc` demonstrates
      `hstack(gap = 8, wrap = true)(...)` with enough varying-width children
      (mixed short/long labels) that it visibly wraps onto 2+ lines in a
      narrow viewport, alongside a `wrap = false` row for contrast.
- [ ] Affected `tkv2-*` / `std-ui-*` conformance cases pass before push.

## Design

### Why no new `extern def` and no `StaticJsEmitter.scala` change

Exactly the same reasoning as `std-ui-button-variant.md` § "Why no new
`extern def`": `element(tag, attrs, events, children): View` already
handles an arbitrary `"style"` attribute string generically — `lower.ssc`
only needs to compute a *different string* when `wrap` is true, not a
different `View` shape. No new `View` case, no new emitter branch, no new
native intrinsic; automatically supported on both conformance lanes (`int`
and `js`) with zero backend-specific changes.

### `flex-wrap: wrap` only when `wrap = true`; textually unchanged output otherwise

`lower.ssc`'s existing `HStackNode` case builds one interpolated `style`
string. The new code computes an extra CSS fragment that is the empty
string when `wrap = false`, so the interpolated result is character-for-
character identical to today's output in the default case — no
`flex-wrap: nowrap` is ever emitted (nowrap is already CSS's own default,
so it would be a no-op change with zero behavioral difference, but it
*would* change the literal golden string every existing/future
string-comparison test pins down, for no benefit — see Decisions).

```scalascript
case HStackNode(gap, kids, wrap) =>
  val wrapCss = if wrap then "; flex-wrap:wrap" else ""
  element("div",
    ["style" -> s"display:flex; flex-direction:row; align-items:center; gap:${gap}px; box-sizing:border-box${wrapCss}"],
    Map(),
    kids.map(lower(_, theme)))
```

For `wrap = false`: `wrapCss = ""`, so the interpolated string is exactly
`"display:flex; flex-direction:row; align-items:center; gap:${gap}px;
box-sizing:border-box"` — byte-identical to the pre-slice source. For
`wrap = true`: the string gains a trailing `; flex-wrap:wrap`.

### No `align-content` parameter

CSS flexbox's `gap` property applies as both row-gap and column-gap
uniformly by default — once a row wraps, `gap` already produces even
spacing between wrapped lines with no extra property needed. `hstack`
already exposes `gap`; nothing new is required to get sensible spacing
between wrapped rows. An `align-content` parameter (cross-axis distribution
of the wrapped lines as a group, e.g. `space-between` vs `flex-start`)
would only matter if the container had a fixed height taller than its
wrapped content, which is not how `hstack` is used anywhere in this repo or
in busi's nav case (height is intrinsic to content) — adding it now would
be speculative surface area with no concrete caller. Deferred; not part of
this slice (see Out of Scope).

### Field position: `wrap` in `hstack`'s first parameter group, appended last on the node

See Interface above for why `wrap` sits in `hstack`'s non-curried group
(next to `gap`, before the curried `children*`) while `HStackNode` itself
still appends `wrap` as its last field, after `children`. The two are
independent: `hstack`'s def signature is fixed by the task brief; the
node's field order is this slice's own free choice, and following the
button-node precedent ("always append, never insert") minimizes churn for
positional patterns and keeps `HStackNode(gap, kids, wrap)` reading in the
same gap-then-content-then-modifier order the button nodes already
established with `variant`/`size`.

Implementation points:

- `v1/runtime/std/ui/nodes.ssc` — `wrap: Boolean` field on `HStackNode`.
- `v1/runtime/std/ui/layout.ssc` — `wrap: Boolean = false` param on
  `hstack`, threaded unchanged into the node.
- `v1/runtime/std/ui/lower.ssc` — `HStackNode` case's `style` string gains
  the conditional `flex-wrap:wrap` fragment.
- `v1/runtime/std/ui/i18n.ssc` — `localeSwitcher`'s direct `HStackNode(8,
  ...)` construction updated to pass `wrap = false` explicitly (the node
  has no default on its own field, matching the button-node precedent — a
  node-level default would not save this call site from updating anyway,
  since it already passes `children` positionally with no gap for a
  trailing default to fill).
- `v1/runtime/std/content-plugin/.../ContentIntrinsics.scala`'s
  `hstackNode` helper (content-toolkit markdown `{type: hstack}` support) —
  adds `"wrap" -> PluginValue.bool(false)` to keep the instance's field
  shape in sync with the 3-field node. Content-toolkit's markdown `hstack`
  control has no `wrap` option (out of scope, exactly like `variant`/`size`
  never reached content-toolkit's button markup) — this is a genuine
  interpreter-side compile/construction requirement, not a feature
  addition.
- `v1/runtime/backend/jvm/.../JvmGenContentEmit.scala`'s generated-Scala
  `std.ui.nodes.HStackNode(...)` call site (content-toolkit JVM codegen) —
  gains a third positional `false` argument; a genuine compile-time
  requirement (the JVM-compiled case class has no field-level default,
  matching the button-node precedent).
- `v1/runtime/backend/js/.../ContentToolkitJs.scala`'s hand-written
  `{ _type: 'HStackNode', gap: ..., children: ... }` JS object literal
  needs **no** change — verified by reading `JsGenCpsCodegen.genPattern`'s
  case-class `Extract` branch (same file/verification the button-variant
  slice already did): field access on a case-class pattern compiles to
  plain `scrutVar.fieldName` property access, which evaluates to
  `undefined` (not a throw) for a genuinely absent JS property; the
  lowered `if wrap then ... else ""` conditional compiles to a plain JS
  `if (wrap)`, and `undefined` is falsy in JS — content-toolkit-authored
  `hstack` controls render exactly as they did before this slice, on every
  backend, with no JS-side edit needed.
- `examples/frontend/hstack-wrap/hstack-wrap.ssc` — new runnable example.
- `tests/conformance/tkv2-hstack-wrap.ssc` (+
  `expected/tkv2-hstack-wrap.txt`).
- `v1/runtime/backend/interpreter-server/src/test/scala/scalascript/HStackWrapTest.scala`
  — Scala-level test extracting the real rendered `style` attribute,
  mirroring `ButtonVariantColorTest.scala` / `ButtonSizeTest.scala`.
- `README.md` capabilities-table row, `docs/user-guide.md` §17.5-adjacent
  widget-catalog row.

No changes needed in `FrontendIntrinsics.scala`, `JvmGenContentEmit.scala`
beyond the one call site above, `StaticJsEmitter.scala`,
`CustomFrameworkBackend.scala`, `JsGen.scala`'s pattern-matching machinery
(field lists are derived from the module AST, not hardcoded — confirmed by
reading `caseClassFieldsInModule` usage in `JsGen.scala`), or any
JVM/Swing/SwiftUI native-frontend lowering — same reasoning as
`variant`/`size`'s own Design sections.

## Decisions

- **Empty-string CSS fragment when `wrap = false`, never an explicit
  `flex-wrap: nowrap`** — chosen so the default case's `style` string stays
  byte-for-byte identical to pre-slice output, which is both the strictest
  possible backward-compatibility guarantee and avoids gratuitously
  widening any future string-literal golden test. Rejected: always emitting
  `flex-wrap: nowrap|wrap` explicitly — semantically equivalent (`nowrap`
  is CSS's own default) but changes the literal string for zero behavioral
  gain.
- **`wrap` in `hstack`'s first parameter group (with `gap`), not appended
  after the curried `children*` group** — dictated by the task's required
  signature and by Scala syntax itself: a parameter cannot be inserted
  after a trailing varargs/curried group and still be reachable by a
  simple `hstack(gap = 8, wrap = true)(children*)` call shape. The node's
  own field order is independent and still appends `wrap` last (see
  Design).
- **No `align-content` parameter** — see Design § "No `align-content`
  parameter". Rejected: adding one speculatively — no concrete caller
  (including busi's own motivating nav case) has a fixed-height wrapped
  container where it would matter; `gap` alone already produces sensible
  spacing between wrapped lines.
- **`vstack` and other layout primitives untouched** — task scope is
  `hstack` only. `vstack` already lays out children in the direction that
  never overflows a fixed-width container (it grows vertically, which is
  normally what scrolls), so it doesn't have the same wrapping need that
  motivated this slice.
- **`wrap: Boolean`, not a `String` "wrap"/"nowrap"/"wrap-reverse" enum** —
  the task brief specifies a boolean explicitly ("one boolean"); CSS's own
  `wrap-reverse` has no concrete caller here and would be speculative scope
  expansion.

## Out of Scope

- **Wiring busi's nav bar to `wrap = true`** — a separate, busi-side
  follow-up once this slice's pin is bumped. Not this repo's decision (busi
  also still needs to un-group its manually fixed-4-per-row `hstack` calls
  into one flat list, which is busi-side work, not `std/ui`'s).
- **`vstack` or any other layout primitive** — task scope is `hstack` only.
- **Any backend other than the `custom` JS frontend
  (`StaticJsEmitter`/`CustomFrameworkBackend`) and the plain interpreter
  fallback** — see Design for why this needs zero backend-specific code
  either way (same as `variant`/`size`).
- **An `align-content` / `justify-content` / `wrap-reverse` parameter** —
  see Decisions; no concrete need surfaced.
- **Content-toolkit markdown `{type: hstack}` gaining a `wrap:` markup
  option** — the interpreter/JVM-codegen call sites are updated only to
  keep the node's field shape in sync (a compile requirement), not to
  expose `wrap` through the markdown DSL. A future slice if a content-
  toolkit author needs it.

## Results

What landed:

- `v1/runtime/std/ui/nodes.ssc` — `wrap: Boolean` field appended as the new
  last field on `HStackNode`.
- `v1/runtime/std/ui/layout.ssc` — `wrap: Boolean = false` appended to
  `hstack`'s first (non-curried) parameter group, before the curried
  `(children: TkNode*)` group; threaded unchanged into the node:
  `def hstack(gap: Int = 0, wrap: Boolean = false)(children: TkNode*): TkNode`.
- `v1/runtime/std/ui/lower.ssc` — the `HStackNode` case computes
  `wrapCss = if wrap then "; flex-wrap:wrap" else ""` and appends it to the
  interpolated `style` string; `wrap = false` produces the exact pre-slice
  string, `wrap = true` appends `; flex-wrap:wrap`.
- `v1/runtime/std/ui/i18n.ssc` — `localeSwitcher`'s direct `HStackNode(8,
  ...)` construction updated to pass `false` for the new field.
- `examples/frontend/hstack-wrap/hstack-wrap.ssc` — runnable example: a
  15-button row with mixed short/long Cyrillic labels (mirroring busi's
  owner-nav case, from `"Сейф"` to `"Корпоративное закрытие"`) rendered
  once with `wrap = true` and once with `wrap = false`, both inside
  `box(maxWidth = 480)` so the wrap is visible without resizing a real
  browser window.
- `tests/conformance/tkv2-hstack-wrap.ssc` (+ `expected/`) — mirrors
  `tkv2-button-variant.ssc`'s TkNode-field-assertion style: asserts `wrap`
  threads from `hstack(...)` to `HStackNode` unchanged (no argument,
  explicit `false`, `true`) and that `lower()` does not throw for any of
  them, on both `[int, js]` lanes.
- `v1/runtime/backend/interpreter-server/src/test/scala/scalascript/HStackWrapTest.scala`
  — closes the gap `View`'s opacity leaves in the `.ssc`-level conformance
  test: runs the interpreter, extracts the real
  `frontend.core.View.Element`'s `"style"` attribute string, and asserts
  the byte-for-byte shape. Actual output observed:

  ```
  no-arg / wrap=false: display:flex; flex-direction:row; align-items:center; gap:8px; box-sizing:border-box
  wrap=true:           display:flex; flex-direction:row; align-items:center; gap:8px; box-sizing:border-box; flex-wrap:wrap
  ```

  The no-argument and explicit-`false` cases are identical to each other
  and to the pinned pre-slice literal; `wrap = true` is exactly that same
  string plus `; flex-wrap:wrap` — nothing else in the style string moved
  or changed.
- Found, fixed as a required consequence of adding a field to an existing
  node (not new scope): two other call sites construct `HStackNode`
  outside `layout.ssc` and needed to stay in sync —
  `v1/runtime/std/content-plugin/.../ContentIntrinsics.scala`'s
  `hstackNode` helper (interpreter-side content-toolkit `{type: hstack}`
  support) now also sets `"wrap" -> PluginValue.bool(false)`;
  `v1/runtime/backend/jvm/.../JvmGenContentEmit.scala`'s generated-Scala
  `std.ui.nodes.HStackNode(...)` call site (JVM-codegen content-toolkit
  support) now passes `false` as the 3rd positional arg — a genuine
  compile-time requirement (the JVM-compiled case class has no
  field-level default, matching the button-node precedent). Confirmed
  `v1/runtime/backend/js/.../ContentToolkitJs.scala`'s hand-crafted
  `{ _type: 'HStackNode', ... }` JS object literal needs **no** change —
  verified by reading `JsGenCpsCodegen.genPattern`'s case-class `Extract`
  branch: field access compiles to plain `scrutVar.fieldName` property
  access, `undefined` (not a throw) for a genuinely absent JS property, and
  the lowered `if wrap then ... else ""` conditional treats `undefined` as
  falsy — content-toolkit `hstack` controls render exactly as they did
  before this slice, on every backend.
- `README.md` capabilities-table row; `docs/user-guide.md` `hstack`
  widget-catalog row.

Verification:

- `scripts/sbtc "installBin"` — clean, no compile errors (confirms
  `nodes.ssc`/`layout.ssc`/`lower.ssc`/`i18n.ssc` and the two Scala-side
  fixes all compile together).
- `scripts/sbtc "backendJvm/compile"` and `scripts/sbtc "contentPlugin/compile"`
  — both clean.
- `tests/conformance/run.sh --only 'tkv2-hstack-wrap,tkv2-*,std-ui-*'` —
  **24 passed, 0 failed** (`tkv2-hstack-wrap` PASS `[INT]`/PASS `[JS]`; no
  regressions across the sibling toolkit-v2/std-ui suite, including
  `std-ui-i18n`'s `localeSwitcher`-exercising case).
- `scripts/sbtc "backendInterpreterServer/testOnly scalascript.HStackWrapTest"`
  — green; the actual style-string proof above.
- `scripts/sbtc "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"`
  — **29 passed, 0 failed** (content-toolkit tests unaffected by the
  `ContentIntrinsics.scala` field addition).
- `scripts/sbtc "backendInterpreterServer/testOnly scalascript.StdUiSmokeTest"`
  — both tests green (`smoke:ok`, `lower-idempotent:ok`) — the pre-existing
  `hstack` calls in `examples/frontend/std-ui/smoke-test.ssc` (no `wrap`
  arg) are unaffected.
- `bin/ssc-tools emit-spa --frontend custom
  examples/frontend/hstack-wrap/hstack-wrap.ssc` — succeeds (exit 0),
  produces a real HTML+JS bundle containing exactly one `flex-wrap:wrap`
  occurrence (the `wrap = true` row; the `wrap = false` row emits none).

Follow-ups recorded in `SPRINT.md`/spec Out-of-Scope: busi-side nav wiring
(un-grouping the fixed-4-per-row `hstack` calls into one flat list with
`wrap = true` — not this repo's decision, a separate busi-side follow-up
once this slice's pin is bumped).
